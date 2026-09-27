package me.rerere.rikkahub.data.shopping

import org.junit.Assert.*
import org.junit.Test

class DiscountEngineTest {
    private class Fixture {
        private val nodes = mutableMapOf<String, MutableList<ShoppingObservedNode>>()
        private var serial = 0
        fun ref(text: String, source: String = "shop"): ShoppingEvidenceRef {
            val id = "n${++serial}"
            nodes.getOrPut(source) { mutableListOf() } += ShoppingObservedNode(id, text)
            return ShoppingEvidenceRef("snapshot-$source", id, text)
        }
        fun money(amount: String, source: String = "shop") = ShoppingMoney(amount, ref("¥$amount", source))
        fun candidate(id: String = "a", price: String = "100.00", shipping: String = "5.00", source: String = "shop") = ShoppingCandidate(
            id, ShoppingFact("Brand Model1", ref("Brand Model1", source)), ShoppingFact("500g", ref("500g", source)),
            1, ref("数量1", source), money(price, source), money(shipping, source), money("0.00", source), true,
        )
        fun coupon(id: String = "c", discount: String = "20.00", threshold: String = "100.00", candidates: List<String> = listOf("a"), source: String = "shop") = ShoppingCoupon(
            id, ShoppingCouponKind.MERCHANDISE_FIXED, money(discount, source), money(threshold, source),
            ShoppingThresholdBasis.ORIGINAL_MERCHANDISE, candidates, ref("适用所选商品", source),
            ShoppingEligibility.CONFIRMED, ref("当前账号可用", source), ShoppingCouponAcquisition.AVAILABLE,
            ref("已领取可用", source), 100, 2_000, ref("有效期100至2000", source),
        )
        fun stack(a: String, b: String, allowed: Boolean = true) = ShoppingCompatibility(a, b, allowed, ref(if (allowed) "两券可叠加" else "两券互斥"))
        fun observed() = nodes.map { (source, values) -> ShoppingObservedEvidence("snapshot-$source", source, values.toList()) }
        fun compare(candidates: List<ShoppingCandidate>, coupons: List<ShoppingCoupon> = emptyList(), pairs: List<ShoppingCompatibility> = emptyList(), now: Long = 1_000) =
            DiscountEngine { now }.compare(ShoppingComparisonRequest(candidates = candidates, coupons = coupons, compatibility = pairs), observed())
    }

    @Test fun `money is exact integer cents and rejects fractional cents exponents negative and huge input`() {
        assertEquals(10L, DiscountEngine.moneyToCents("0.10"))
        assertEquals(1999L, DiscountEngine.moneyToCents("19.99"))
        assertEquals(999999999999L, DiscountEngine.moneyToCents("9999999999.99"))
        listOf("0.001", "1e3", "-1", "NaN", "99999999999", "1,000", "01", "1.999").forEach {
            assertTrue(it, runCatching { DiscountEngine.moneyToCents(it) }.exceptionOrNull() is ShoppingComparisonException)
        }
    }

    @Test fun `same product includes quantity shipping and packing fees and keeps ties`() {
        val f = Fixture()
        val a = f.candidate("a", "9.90", "2.00").copy(quantity = 2, quantityEvidence = f.ref("数量2"), otherFees = f.money("1.20"))
        val b = f.candidate("b", "10.00", "1.80").copy(quantity = 2, quantityEvidence = f.ref("数量2"), otherFees = f.money("1.20"))
        val group = f.compare(listOf(a, b)).groups.single()
        assertEquals(2300L, group.rankedCandidates.first().confirmedPlan!!.payableCents)
        assertEquals(listOf("a", "b"), group.lowestConfirmedCandidateIds)
    }

    @Test fun `different identities specifications and quantities never enter one price ranking`() {
        val f = Fixture()
        val base = f.candidate()
        val otherSize = f.candidate("b", "1.00").copy(specification = ShoppingFact("50g", f.ref("50g")))
        val otherModel = f.candidate("c", "1.00").copy(productIdentity = ShoppingFact("Brand Model2", f.ref("Brand Model2")))
        val otherQuantity = f.candidate("d", "1.00").copy(quantity = 2, quantityEvidence = f.ref("数量2"))
        assertEquals(4, f.compare(listOf(base, otherSize, otherModel, otherQuantity)).groups.size)
    }

    @Test fun `same core evidence rejects renamed candidates and changing quote cannot bypass rejection`() {
        val f = Fixture()
        val original = f.candidate()
        val renamed = original.copy(id = "renamed")
        val requoted = renamed.copy(unitPrice = renamed.unitPrice.copy(
            evidence = renamed.unitPrice.evidence.copy(quote = "100.00"),
        ))
        listOf(renamed, requoted).forEach { duplicate ->
            val error = runCatching { f.compare(listOf(original, duplicate)) }.exceptionOrNull()
            assertTrue(error is ShoppingComparisonException)
            assertEquals("duplicate_candidate_evidence", (error as ShoppingComparisonException).code)
        }
    }

    @Test fun `cross snapshot normalized identity and specification only warn without merging or verifying sku binding`() {
        val f = Fixture()
        val first = f.candidate("first", "90.00")
        val second = f.candidate("second", "100.00").copy(
            productIdentity = ShoppingFact("  brand   model1  ", f.ref("  brand   model1  ")),
            specification = ShoppingFact("500G", f.ref("500G")),
        )
        val samePage = f.compare(listOf(first, second))
        assertTrue(samePage.possibleDuplicateCandidateIds.isEmpty())
        assertFalse(samePage.productBindingVerified)
        assertEquals(2, samePage.groups.sumOf { it.rankedCandidates.size })

        val nextPage = second.copy(
            productIdentity = second.productIdentity.copy(evidence = second.productIdentity.evidence.copy(snapshotId = "next-page")),
            specification = second.specification.copy(evidence = second.specification.evidence.copy(snapshotId = "next-page")),
            unitPrice = second.unitPrice.copy(evidence = second.unitPrice.evidence.copy(snapshotId = "next-page")),
        )
        val result = DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = listOf(first, nextPage)),
            f.observed() + f.observed().single().copy(snapshotId = "next-page"))
        assertEquals(listOf("first", "second"), result.possibleDuplicateCandidateIds)
        assertEquals(2, result.groups.sumOf { it.rankedCandidates.size })
        assertEquals(setOf("first", "second"), result.groups.flatMap { it.rankedCandidates }.map { it.id }.toSet())
        assertFalse(result.productBindingVerified)
        assertTrue(result.limitations.any { "不能证明不同SKU" in it })
        assertTrue(result.groups.all { !it.displayedSubtotalPrefilter.isFinalBest })
    }

    @Test fun `prefilter retains outstanding conditions even when all fee amounts exist`() {
        val f = Fixture()
        val candidate = f.candidate().copy(unknowns = listOf("偏远地区附加费待核"))
        val group = f.compare(listOf(candidate)).groups.single()
        val result = group.rankedCandidates.single()
        val prefilter = group.displayedSubtotalPrefilter.rankedCandidates.single()
        assertNotNull(result.shippingCents)
        assertNotNull(result.otherFeesCents)
        assertNull(result.confirmedPlan)
        assertTrue(prefilter.unverifiedFees.isEmpty())
        assertEquals(result.unknowns, prefilter.pendingConditions)
        assertEquals(listOf("偏远地区附加费待核"), prefilter.pendingConditions)
    }

    @Test fun `unconfirmed candidates use displayed subtotals instead of ids without inventing fees or coupon savings`() {
        val f = Fixture()
        fun unknown(id: String, price: String) = f.candidate(id, price).copy(
            shipping = null, otherFees = null, priceBeforeListedCoupons = false,
        )
        val expensive = unknown("a", "29.90")
        val cheap = unknown("z", "9.90")
        val middle = unknown("m", "19.90")
        val unknownCoupon = f.coupon(discount = "8.00", threshold = "0.00", candidates = listOf("z"))
            .copy(eligibility = ShoppingEligibility.UNKNOWN)
        val group = f.compare(listOf(expensive, cheap, middle), listOf(unknownCoupon)).groups.single()

        assertEquals(ShoppingRankingBasis.DISPLAYED_SUBTOTAL, group.rankingBasis)
        assertEquals(listOf("z", "m", "a"), group.rankedCandidates.map { it.id })
        assertTrue(group.hasUnpricedCandidates)
        assertTrue(group.lowestConfirmedCandidateIds.isEmpty())
        group.rankedCandidates.forEach {
            assertNull(it.shippingCents)
            assertNull(it.otherFeesCents)
            assertNull(it.baselineCents)
            assertNull(it.confirmedPlan)
            assertNull(it.afterFreeClaimPlan)
        }
        assertFalse(group.rankedCandidates.first().couponDecisions.single().included)
        val prefilter = group.displayedSubtotalPrefilter
        assertEquals(ShoppingRankingBasis.DISPLAYED_SUBTOTAL, prefilter.rankingBasis)
        assertFalse(prefilter.isPayable)
        assertFalse(prefilter.isFinalBest)
        assertEquals(listOf("z", "m", "a"), prefilter.rankedCandidates.map { it.id })
        assertEquals(listOf(990L, 1990L, 2990L), prefilter.rankedCandidates.map { it.merchandiseSubtotalCents })
        assertTrue(prefilter.rankedCandidates.all { it.unverifiedFees == listOf("shipping", "other_fees") })
        assertEquals(group.rankedCandidates.map { it.unknowns }, prefilter.rankedCandidates.map { it.pendingConditions })
    }

    @Test fun `confirmed payable ranking remains separate from displayed subtotal prefilter`() {
        val f = Fixture()
        val discounted = f.candidate("discounted", "100.00", "0.00")
        val lowerDisplay = f.candidate("lower_display", "90.00", "0.00")
        val incomplete = f.candidate("incomplete", "1.00").copy(shipping = null)
        val coupon = f.coupon(discount = "50.00", threshold = "100.00", candidates = listOf("discounted"))
        val group = f.compare(listOf(incomplete, lowerDisplay, discounted), listOf(coupon)).groups.single()

        assertEquals(ShoppingRankingBasis.CONFIRMED_PAYABLE_THEN_DISPLAYED_SUBTOTAL, group.rankingBasis)
        assertEquals(listOf("discounted", "lower_display", "incomplete"), group.rankedCandidates.map { it.id })
        assertEquals(listOf("discounted"), group.lowestConfirmedCandidateIds)
        assertEquals(5000L, group.rankedCandidates.first().confirmedPlan!!.payableCents)
        assertNull(group.rankedCandidates.last().confirmedPlan)
        val prefilter = group.displayedSubtotalPrefilter
        assertEquals(listOf("incomplete", "lower_display", "discounted"), prefilter.rankedCandidates.map { it.id })
        assertEquals(listOf(100L, 9000L, 10000L), prefilter.rankedCandidates.map { it.merchandiseSubtotalCents })
        assertEquals(listOf("shipping"), prefilter.rankedCandidates.first().unverifiedFees)
        assertTrue(prefilter.rankedCandidates.drop(1).all { it.unverifiedFees.isEmpty() })
        assertFalse(prefilter.isPayable)
        assertFalse(prefilter.isFinalBest)
        assertEquals(ShoppingRankingBasis.CONFIRMED_PAYABLE,
            f.compare(listOf(lowerDisplay, discounted), listOf(coupon)).groups.single().rankingBasis)
    }

    @Test fun `subtotal prefilter keeps equal price order and separates specifications and quantities`() {
        val f = Fixture()
        val first = f.candidate("z", "9.90").copy(otherFees = null)
        val second = f.candidate("a", "9.90").copy(otherFees = null)
        val otherSpec = f.candidate("small", "1.00").copy(specification = ShoppingFact("50g", f.ref("50g")))
        val otherQuantity = f.candidate("two", "9.90").copy(quantity = 2, quantityEvidence = f.ref("数量2"))
        val groups = f.compare(listOf(first, second, otherSpec, otherQuantity)).groups

        assertEquals(3, groups.size)
        val same = groups.single { it.specification == "500g" && it.quantity == 1 }
        assertEquals(listOf("z", "a"), same.rankedCandidates.map { it.id })
        assertEquals(listOf("z", "a"), same.displayedSubtotalPrefilter.rankedCandidates.map { it.id })
        assertEquals(listOf("small"), groups.single { it.specification == "50g" }
            .displayedSubtotalPrefilter.rankedCandidates.map { it.id })
        assertEquals(1980L, groups.single { it.quantity == 2 }
            .displayedSubtotalPrefilter.rankedCandidates.single().merchandiseSubtotalCents)
    }

    @Test fun `shipping does not satisfy product threshold and shipping coupon cannot reduce merchandise`() {
        val f = Fixture()
        val a = f.candidate(price = "99.00", shipping = "10.00")
        val threshold = f.coupon()
        val delivery = f.coupon("delivery", "50.00", "0.00").copy(kind = ShoppingCouponKind.SHIPPING_FIXED)
        val result = f.compare(listOf(a), listOf(threshold, delivery)).groups.single().rankedCandidates.single()
        assertEquals(9900L, result.confirmedPlan!!.payableCents)
        assertEquals(listOf("delivery"), result.confirmedPlan.couponIds)
        assertFalse(result.couponDecisions.first { it.id == "c" }.included)
        assertEquals(1000L, result.confirmedPlan.shippingDiscountCents)
    }

    @Test fun `only explicitly compatible pairs stack and fixed discounts never reduce mandatory fees`() {
        val f = Fixture()
        val a = f.candidate(price = "0.30", shipping = "0.20").copy(otherFees = f.money("0.10"))
        val coupons = listOf(f.coupon("a", "0.10", "0.00"), f.coupon("b", "0.20", "0.00"), f.coupon("c", "0.20", "0.00").copy(kind = ShoppingCouponKind.SHIPPING_FIXED))
        val unconfirmed = f.compare(listOf(a), coupons).groups.single().rankedCandidates.single()
        assertEquals(40L, unconfirmed.confirmedPlan!!.payableCents)
        assertTrue(unconfirmed.unknowns.any { "叠加关系未知" in it })
        val confirmed = f.compare(listOf(a), coupons, listOf(f.stack("a", "b"), f.stack("a", "c"), f.stack("b", "c")))
            .groups.single().rankedCandidates.single()
        assertEquals(10L, confirmed.confirmedPlan!!.payableCents)
        assertEquals(3, confirmed.confirmedPlan.couponIds.size)
    }

    @Test fun `one missing pair and exclusive groups prevent seemingly cheapest combined coupons`() {
        val f = Fixture()
        val a = f.candidate()
        val coupons = listOf(f.coupon("x"), f.coupon("y"), f.coupon("z"))
        val partial = f.compare(listOf(a), coupons, listOf(f.stack("x", "y"), f.stack("y", "z"))).groups.single().rankedCandidates.single()
        assertEquals(6500L, partial.confirmedPlan!!.payableCents)
        val exclusive = coupons.take(2).map { it.copy(exclusiveGroup = "shop_coupon") }
        assertEquals(8500L, f.compare(listOf(a), exclusive, listOf(f.stack("x", "y")))
            .groups.single().rankedCandidates.single().confirmedPlan!!.payableCents)
        assertEquals(8500L, f.compare(listOf(a), coupons.take(2), listOf(f.stack("x", "y", false)))
            .groups.single().rankedCandidates.single().confirmedPlan!!.payableCents)
    }

    @Test fun `unknown fees coupon status period threshold or price basis never become an invented optimal total`() {
        val f = Fixture()
        val known = f.candidate()
        listOf(known.copy(shipping = null), known.copy(otherFees = null), known.copy(priceBeforeListedCoupons = false), known.copy(unknowns = listOf("价格是否限首单未知"))).forEach {
            val result = f.compare(listOf(it)).groups.single()
            assertTrue(result.hasUnpricedCandidates)
            assertNull(result.rankedCandidates.single().confirmedPlan)
            assertTrue(result.lowestConfirmedCandidateIds.isEmpty())
        }
        val coupon = f.coupon()
        listOf(coupon.copy(validUntilEpochMillis = null), coupon.copy(thresholdBasis = ShoppingThresholdBasis.UNKNOWN),
            coupon.copy(eligibility = ShoppingEligibility.UNKNOWN), coupon.copy(unknowns = listOf("是否可用于该规格未知"))).forEach {
            val result = f.compare(listOf(known), listOf(it)).groups.single().rankedCandidates.single()
            assertEquals(10500L, result.confirmedPlan!!.payableCents)
            assertFalse(result.couponDecisions.single().included)
        }
    }

    @Test fun `coupon effective start is inclusive expiration is exclusive and future or expired coupons are excluded`() {
        val f = Fixture(); val a = f.candidate(); val coupon = f.coupon()
        assertEquals(8500L, f.compare(listOf(a), listOf(coupon), now = 100).groups.single().rankedCandidates.single().confirmedPlan!!.payableCents)
        listOf(99L, 2_000L, 3_000L).forEach { now ->
            assertEquals(10500L, f.compare(listOf(a), listOf(coupon), now = now).groups.single().rankedCandidates.single().confirmedPlan!!.payableCents)
        }
    }

    @Test fun `free unclaimed coupon is a conditional plan and account changes assets and subscriptions are excluded`() {
        val f = Fixture(); val a = f.candidate()
        val coupon = f.coupon().copy(acquisition = ShoppingCouponAcquisition.FREE_CLAIMABLE, acquisitionEvidence = f.ref("普通免费券可领取"))
        val result = f.compare(listOf(a), listOf(coupon)).groups.single().rankedCandidates.single()
        assertEquals(10500L, result.confirmedPlan!!.payableCents)
        assertEquals(8500L, result.afterFreeClaimPlan!!.payableCents)
        assertEquals(listOf("c"), result.afterFreeClaimPlan.freeClaimRequired)
        listOf(ShoppingCouponAcquisition.ACCOUNT_CHANGE, ShoppingCouponAcquisition.PAID, ShoppingCouponAcquisition.POINTS,
            ShoppingCouponAcquisition.STORED_VALUE, ShoppingCouponAcquisition.UNKNOWN).forEach { kind ->
            assertNull(f.compare(listOf(a), listOf(coupon.copy(acquisition = kind))).groups.single().rankedCandidates.single().afterFreeClaimPlan)
        }
        val mislabeled = coupon.copy(acquisitionEvidence = f.ref("免费试用会员后可领券，自动续费"))
        assertFalse(f.compare(listOf(a), listOf(mislabeled)).groups.single().rankedCandidates.single().couponDecisions.single().included)
    }

    @Test fun `coupons cannot cross apps even with the same comparable product`() {
        val f = Fixture()
        val candidates = listOf(f.candidate("a", source = "taobao"), f.candidate("b", source = "jd"))
        val coupon = f.coupon(candidates = listOf("a", "b"), source = "taobao")
        val group = f.compare(candidates, listOf(coupon)).groups.single()
        assertEquals(listOf("a"), group.lowestConfirmedCandidateIds)
        assertFalse(group.rankedCandidates.single { it.id == "b" }.couponDecisions.single().included)
    }

    @Test fun `missing fabricated or revoked source evidence cannot be marked verified`() {
        val f = Fixture(); val a = f.candidate()
        listOf(a.copy(unitPrice = a.unitPrice.copy(evidence = a.unitPrice.evidence.copy(snapshotId = "old-grant"))),
            a.copy(unitPrice = a.unitPrice.copy(evidence = a.unitPrice.evidence.copy(quote = "¥1.00")))).forEach {
            val error = runCatching { f.compare(listOf(it)) }.exceptionOrNull() as ShoppingComparisonException
            assertEquals("unverified_evidence", error.code)
        }
        val revoked = runCatching { DiscountEngine().compare(ShoppingComparisonRequest(candidates = listOf(a)), emptyList()) }.exceptionOrNull() as ShoppingComparisonException
        assertEquals("unverified_evidence", revoked.code)
        assertTrue(runCatching { f.compare(listOf(a.copy(unitPrice = a.unitPrice.copy(amount = "1.00")))) }.isFailure)
    }

    @Test fun `ratings review count and quality evidence stay separate from price ranking and cannot be fabricated`() {
        val f = Fixture()
        val cheap = f.candidate("cheap", "90.00").copy(rating = ShoppingRating("4.5", "5", f.ref("商品评分4.5/5"), 80, f.ref("80条评价")),
            qualityEvidence = listOf(ShoppingFact("检测报告可查看", f.ref("检测报告可查看"))))
        val expensive = f.candidate("high_rating", "100.00").copy(rating = ShoppingRating("4.9", "5", f.ref("商品评分4.9/5")))
        val result = f.compare(listOf(expensive, cheap))
        assertEquals("cheap", result.groups.single().rankedCandidates.first().id)
        assertEquals(80L, result.groups.single().rankedCandidates.first().rating!!.reviewCount)
        assertTrue(result.limitations.any { "不等于质量最好" in it })
        val fakeCount = cheap.copy(rating = cheap.rating!!.copy(reviewCount = 10_000, reviewCountEvidence = f.ref("1万+销量")))
        assertTrue(runCatching { f.compare(listOf(fakeCount)) }.isFailure)
    }

    @Test fun `conditioned free shipping and unsupported decimal precision are not silently accepted`() {
        val f = Fixture(); val a = f.candidate()
        assertTrue(runCatching { f.compare(listOf(a.copy(shipping = ShoppingMoney("0.00", f.ref("满199包邮"))))) }.isFailure)
        assertEquals(10000L, f.compare(listOf(a.copy(shipping = ShoppingMoney("0.00", f.ref("包邮")))))
            .groups.single().rankedCandidates.single().confirmedPlan!!.payableCents)
        val conditioned = f.ref("满199包邮").copy(quote = "包邮")
        assertTrue(runCatching { f.compare(listOf(a.copy(shipping = ShoppingMoney("0.00", conditioned)))) }.isFailure)
    }

    @Test fun `clipped quotes cannot hide coupon membership conditions or an already discounted price`() {
        val f = Fixture(); val a = f.candidate()
        val price = f.ref("券后¥100.00").copy(quote = "100.00")
        assertNull(f.compare(listOf(a.copy(unitPrice = ShoppingMoney("100.00", price)))).groups.single().rankedCandidates.single().confirmedPlan)
        val coupon = f.coupon().copy(acquisition = ShoppingCouponAcquisition.FREE_CLAIMABLE,
            acquisitionEvidence = f.ref("会员免费领取").copy(quote = "免费领取"))
        assertNull(f.compare(listOf(a), listOf(coupon)).groups.single().rankedCandidates.single().afterFreeClaimPlan)
    }

    @Test fun `explicit zero threshold is supported but multiple quantities require evidence`() {
        val f = Fixture(); val a = f.candidate(price = "50.00")
        val coupon = f.coupon().copy(minimumSpend = ShoppingMoney("0.00", f.ref("无门槛")))
        assertEquals(3500L, f.compare(listOf(a), listOf(coupon)).groups.single().rankedCandidates.single().confirmedPlan!!.payableCents)
        assertTrue(runCatching { f.compare(listOf(a.copy(quantity = 2, quantityEvidence = null))) }.isFailure)
    }

    @Test fun `model stackable flag cannot override a negative or missing stacking rule`() {
        val f = Fixture(); val a = f.candidate(); val coupons = listOf(f.coupon("x"), f.coupon("y"))
        listOf("两券不可叠加", "优惠金额20.00").forEach { text ->
            assertTrue(runCatching { f.compare(listOf(a), coupons, listOf(ShoppingCompatibility("x", "y", true, f.ref(text)))) }.isFailure)
        }
        val clipped = f.ref("两券不可叠加").copy(quote = "可叠加")
        assertTrue(runCatching { f.compare(listOf(a), coupons, listOf(ShoppingCompatibility("x", "y", true, clipped))) }.isFailure)
    }
}
