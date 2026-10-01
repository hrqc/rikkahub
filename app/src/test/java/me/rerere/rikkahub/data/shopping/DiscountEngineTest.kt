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
        fun cable(id: String, price: String = "10.00", connectors: String = "USB-C对USB-C", power: String = "60W",
            length: String = "1米", pack: String = "单条装", quantity: Int = 1, priceText: String? = null): ShoppingCandidate {
            val original = candidate(id, price)
            val title = "品牌$id USB-C 数据线"
            return original.copy(productIdentity = ShoppingFact(title, ref(title)),
                specification = ShoppingFact("数据线所选规格", ref("数据线所选规格")), quantity = quantity,
                quantityEvidence = if (quantity == 1) null else ref("购买数量$quantity"),
                unitPrice = if (priceText == null) original.unitPrice else ShoppingMoney(price, ref(priceText)),
                brand = ShoppingFact("品牌$id", ref("品牌$id")), store = ShoppingFact("店铺$id", ref("店铺$id")),
                cableSpecEvidence = ShoppingCableSpecEvidence(ref(connectors), ref(power), ref(length), ref(pack)))
        }
        fun alternatives(candidates: List<ShoppingCandidate>) = DiscountEngine { 1_000 }.compare(
            ShoppingComparisonRequest(candidates = candidates, comparisonMode = ShoppingComparisonMode.FUNCTIONAL_ALTERNATIVES,
                functionalCategory = ShoppingFunctionalCategory.USB_C_CABLE), observed())
    }

    @Test fun `functional cable alternatives group different brands with locally equivalent units and retain identities`() {
        val f = Fixture()
        val a = f.cable("a", "19.90")
        val b = f.cable("b", "9.90", connectors = "双C", power = "60瓦", length = "100cm", pack = "1根装")
        val c = f.cable("c", "12.90", connectors = "Type-C to Type-C", length = "1000毫米", pack = "一条装")
        val result = f.alternatives(listOf(a, b, c))
        val group = result.groups.single()
        assertEquals(listOf("b", "c", "a"), group.rankedCandidates.map { it.id })
        assertEquals(listOf(b.productIdentity, c.productIdentity, a.productIdentity), group.rankedCandidates.map { it.productIdentity })
        assertEquals(listOf(b.brand, c.brand, a.brand), group.rankedCandidates.map { it.brand })
        assertEquals(listOf(b.store, c.store, a.store), group.rankedCandidates.map { it.store })
        val parsed = checkNotNull(group.functionalSpecification)
        assertEquals(60, parsed.powerWatts)
        assertEquals(1_000, parsed.lengthMm)
        assertEquals(1, parsed.unitsPerPack)
        assertEquals("条", parsed.itemUnit)
        assertEquals("包", parsed.priceUnit)
        assertTrue(result.unrankedCandidates.isEmpty())
        assertFalse(result.productBindingVerified)
        assertFalse(group.displayedSubtotalPrefilter.isFinalBest)
        assertEquals(3, f.compare(listOf(a, b, c)).groups.size)
    }

    @Test fun `functional grouping is not hardcoded to one test task and separates power length packs and purchases`() {
        val f = Fixture()
        val candidates = listOf(f.cable("a", power = "100W", length = "1.5米", pack = "2条装", quantity = 2, priceText = "整包价¥10.00"),
            f.cable("b", power = "100瓦", length = "150cm", pack = "两根装", quantity = 2, priceText = "每包¥10.00"),
            f.cable("power", power = "60W", length = "1.5米", pack = "2条装", quantity = 2, priceText = "¥10.00/包"),
            f.cable("length", power = "100W", length = "2米", pack = "2条装", quantity = 2, priceText = "每套价格为¥10.00"),
            f.cable("pack", power = "100W", length = "1.5米", quantity = 2),
            f.cable("quantity", power = "100W", length = "1.5米", pack = "2条装", quantity = 1, priceText = "10.00元一包"))
        val result = f.alternatives(candidates)
        assertEquals(5, result.groups.size)
        assertEquals(setOf("a", "b"), result.groups.single { it.rankedCandidates.size == 2 }.rankedCandidates.map { it.id }.toSet())
        assertTrue(result.unrankedCandidates.isEmpty())
    }

    @Test fun `missing cable evidence stays unranked rather than guessing power length or a single pack`() {
        val f = Fixture()
        val known = f.cable("known")
        val missing = listOf(
            f.cable("all").copy(cableSpecEvidence = null),
            f.cable("connector").let { it.copy(cableSpecEvidence = it.cableSpecEvidence!!.copy(connectors = null)) },
            f.cable("power").let { it.copy(cableSpecEvidence = it.cableSpecEvidence!!.copy(power = null)) },
            f.cable("length").let { it.copy(cableSpecEvidence = it.cableSpecEvidence!!.copy(length = null)) },
            f.cable("pack").let { it.copy(cableSpecEvidence = it.cableSpecEvidence!!.copy(packQuantity = null)) },
        )
        val result = f.alternatives(listOf(known) + missing)
        assertEquals(listOf("known"), result.groups.single().rankedCandidates.map { it.id })
        assertEquals(missing.map { it.id }, result.unrankedCandidates.map { it.candidate.id })
        assertTrue(result.unrankedCandidates.all { it.reasons.isNotEmpty() })
        assertTrue(f.alternatives(missing).groups.isEmpty())
    }

    @Test fun `full attribute nodes reject multi spec alternatives despite a narrowed model quote`() {
        val f = Fixture()
        val power = f.cable("power", power = "60W/100W可选").let {
            val specs = checkNotNull(it.cableSpecEvidence)
            it.copy(cableSpecEvidence = specs.copy(power = specs.power!!.copy(quote = "60W")))
        }
        val length = f.cable("length", length = "1米、2米可选")
        val pack = f.cable("pack", pack = "1条装或2条装")
        val range = f.cable("range", length = "0.5-1m")
        val connector = f.cable("connector", connectors = "双C和USB-A转USB-C可选")
        val result = f.alternatives(listOf(power, length, pack, range, connector))
        assertTrue(result.groups.isEmpty())
        assertEquals(5, result.unrankedCandidates.size)
    }

    @Test fun `unsupported units approximate dimensions and non cable purposes are not functionally ranked`() {
        val f = Fixture()
        val candidates = listOf(f.cable("inches", length = "3英尺"), f.cable("approx", length = "约1米"),
            f.cable("negative", power = "-60W"), f.cable("fractional", length = "0.0001米"),
            f.cable("pack", pack = "十一条装"), f.cable("unit", pack = "1件"),
            f.cable("bundle").copy(productIdentity = ShoppingFact("充电器和数据线套装", f.ref("充电器和数据线套装"))))
        val result = f.alternatives(candidates)
        assertTrue(result.groups.isEmpty())
        assertEquals(candidates.size, result.unrankedCandidates.size)
    }

    @Test fun `functional attributes cannot borrow another page or override truncated host evidence`() {
        val f = Fixture()
        val candidate = f.cable("a")
        val specs = checkNotNull(candidate.cableSpecEvidence)
        val ref = checkNotNull(specs.length)
        val altered = candidate.copy(cableSpecEvidence = specs.copy(length = ref.copy(snapshotId = "later")))
        val later = f.observed().single().copy(snapshotId = "later")
        val request = ShoppingComparisonRequest(candidates = listOf(altered), comparisonMode = ShoppingComparisonMode.FUNCTIONAL_ALTERNATIVES,
            functionalCategory = ShoppingFunctionalCategory.USB_C_CABLE)
        assertEquals("candidate_evidence_mismatch", (runCatching { DiscountEngine().compare(request, f.observed() + later) }
            .exceptionOrNull() as ShoppingComparisonException).code)
        val truncated = f.observed().map { page -> page.copy(nodes = page.nodes.map {
            if (it.nodeId == ref.nodeId) it.copy(truncated = true) else it
        }) }
        assertEquals("incomplete_evidence", (runCatching { DiscountEngine().compare(request.copy(candidates = listOf(candidate)), truncated) }
            .exceptionOrNull() as ShoppingComparisonException).code)
    }

    @Test fun `functional selection preserves unknown prices and refuses per-item prices for multi-item packs`() {
        val f = Fixture()
        val unknown = f.cable("unknown").copy(shipping = null, otherFees = null, priceBeforeListedCoupons = false)
        val perItem = f.cable("peritem", pack = "2条装").copy(unitPrice = ShoppingMoney("10.00", f.ref("¥10.00/条")))
        val result = f.alternatives(listOf(unknown, perItem))
        assertNull(result.groups.single().rankedCandidates.single().confirmedPlan)
        assertEquals(ShoppingRankingBasis.DISPLAYED_SUBTOTAL, result.groups.single().rankingBasis)
        assertFalse(result.groups.single().displayedSubtotalPrefilter.isPayable)
        assertEquals(listOf("peritem"), result.unrankedCandidates.map { it.candidate.id })
    }

    @Test fun `functional mode requires a supported category and cannot hide duplicate candidate sources`() {
        val f = Fixture()
        val candidate = f.cable("a")
        val request = ShoppingComparisonRequest(candidates = listOf(candidate), comparisonMode = ShoppingComparisonMode.FUNCTIONAL_ALTERNATIVES)
        assertEquals("invalid_arguments", (runCatching { DiscountEngine().compare(request, f.observed()) }
            .exceptionOrNull() as ShoppingComparisonException).code)
        assertEquals("duplicate_candidate_evidence", (runCatching { f.alternatives(listOf(candidate, candidate.copy(id = "renamed"))) }
            .exceptionOrNull() as ShoppingComparisonException).code)
    }

    @Test fun `multi item packs require the quoted amount to explicitly price one whole pack`() {
        for (text in listOf("单条价¥10.00", "¥10.00一条", "每根¥10.00", "单根10.00元", "10.00元/条",
            "¥10.00", "2包合计¥10.00", "整包价¥10.00/2包", "每包¥5.00，2包¥10.00",
            "整包价¥20.00，单条价¥10.00", "整包价¥20.00，其他数值10.00")) {
            val f = Fixture()
            val candidate = f.cable("a", pack = "2条装", quantity = 2, priceText = text)
            val result = f.alternatives(listOf(candidate))
            assertTrue(text, result.groups.isEmpty())
            assertEquals(candidate, result.unrankedCandidates.single().candidate)
            assertTrue(text, result.unrankedCandidates.single().reasons.any { "每包/每套报价" in it })
            // Rejection preserves only raw input facts, with no speculative derived payable plan.
            val encoded = kotlinx.serialization.json.Json.encodeToString(ShoppingComparisonResult.serializer(), result)
            assertFalse(text, encoded.contains("\"confirmed_plan\""))
            assertFalse(text, encoded.contains("\"merchandise_subtotal_cents\""))
        }
    }

    @Test fun `explicit whole pack prices retain pack count and multiply only purchased packs`() {
        for (text in listOf("整包价¥10.00", "每包¥10.00", "每套价格为¥10.00", "10.00元/包", "10.00元一套")) {
            val f = Fixture()
            val candidate = f.cable("a", pack = "2条装", quantity = 2, priceText = text)
            val result = f.alternatives(listOf(candidate))
            assertTrue(text, result.unrankedCandidates.isEmpty())
            val group = result.groups.single()
            assertEquals(2, group.quantity)
            assertEquals(2, group.functionalSpecification!!.unitsPerPack)
            assertEquals(2_000L, group.rankedCandidates.single().merchandiseSubtotalCents)
        }
    }

    @Test fun `Chinese and Arabic pack ranges approximations and bounds are rejected as complete fields`() {
        for (text in listOf("一至三条装", "约2条装", "两—三根装", "2–3条装", "一条至三条",
            "≥2条装", "最多二条装", "2条左右", "两根装以上", "3条+", "二/三根装")) {
            val f = Fixture()
            val candidate = f.cable("a", pack = text, priceText = "整包价¥10.00")
            val result = f.alternatives(listOf(candidate))
            assertTrue(text, result.groups.isEmpty())
            assertTrue(text, result.unrankedCandidates.single().reasons.any { "每包条数" in it })
        }
    }

    @Test fun `unsupported compound Chinese pack counts cannot be parsed from a trailing supported numeral`() {
        for (text in listOf("一百零一条装", "一百三条装", "零一条装", "〇一条装", "二十一条装",
            "一千零一条装", "一万一条装", "一佰一条装", "一億一条装")) {
            val f = Fixture()
            val candidate = f.cable("a", pack = text, priceText = "整包价¥10.00")
            val result = f.alternatives(listOf(candidate))
            assertTrue(text, result.groups.isEmpty())
            assertTrue(text, result.unrankedCandidates.single().reasons.any { "每包条数" in it })
        }
    }

    @Test fun `Unicode measurement ranges and bounds cannot normalize only the last endpoint`() {
        val separators = listOf("-", "‐", "‑", "‒", "–", "—", "―", "−", "~", "〜", "∼", "至", "到", "/")
        for (text in separators.map { "0.5${it}1m" } + listOf("约1m", "至少1米", "1米左右", "≤1m", "1m+")) {
            val f = Fixture()
            val candidate = f.cable("a", length = text)
            val result = f.alternatives(listOf(candidate))
            assertTrue(text, result.groups.isEmpty())
            assertTrue(text, result.unrankedCandidates.single().reasons.any { "长度" in it })
        }
        for (text in listOf("60–100W", "约60瓦", "≥60W", "60W以上")) {
            val f = Fixture()
            val result = f.alternatives(listOf(f.cable("a", power = text)))
            assertTrue(text, result.groups.isEmpty())
            assertTrue(text, result.unrankedCandidates.single().reasons.any { "额定功率" in it })
        }
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

    @Test fun `candidate core facts cannot splice valid same-app evidence across snapshots`() {
        val f = Fixture()
        val original = f.candidate()
        val nextPage = "next-page"
        val observed = f.observed() + f.observed().single().copy(snapshotId = nextPage)
        val mismatched = listOf(
            original.copy(productIdentity = original.productIdentity.copy(
                evidence = original.productIdentity.evidence.copy(snapshotId = nextPage),
            )),
            original.copy(specification = original.specification.copy(
                evidence = original.specification.evidence.copy(snapshotId = nextPage),
            )),
            original.copy(unitPrice = original.unitPrice.copy(
                evidence = original.unitPrice.evidence.copy(snapshotId = nextPage),
            )),
        )
        mismatched.forEach { candidate ->
            val error = runCatching {
                DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = listOf(candidate)), observed)
            }.exceptionOrNull()
            assertTrue(error is ShoppingComparisonException)
            assertEquals("candidate_evidence_mismatch", (error as ShoppingComparisonException).code)
        }
    }

    @Test fun `three candidates on separate snapshots remain eligible for a limited price prefilter`() {
        val f = Fixture()
        val originals = listOf(f.candidate("expensive", "29.90"), f.candidate("cheap", "9.90"), f.candidate("middle", "19.90"))
        val candidates = originals.mapIndexed { index, candidate ->
            val snapshot = "page-$index"
            candidate.copy(
                productIdentity = candidate.productIdentity.copy(evidence = candidate.productIdentity.evidence.copy(snapshotId = snapshot)),
                specification = candidate.specification.copy(evidence = candidate.specification.evidence.copy(snapshotId = snapshot)),
                unitPrice = candidate.unitPrice.copy(evidence = candidate.unitPrice.evidence.copy(snapshotId = snapshot)),
                quantityEvidence = null, shipping = null, otherFees = null, priceBeforeListedCoupons = false,
            )
        }
        val observed = candidates.map { candidate ->
            val ids = setOf(candidate.productIdentity.evidence.nodeId, candidate.specification.evidence.nodeId, candidate.unitPrice.evidence.nodeId)
            ShoppingObservedEvidence(candidate.productIdentity.evidence.snapshotId, "shop", f.observed().single().nodes.filter { it.nodeId in ids })
        }
        val result = DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = candidates), observed)
        val group = result.groups.single()
        assertEquals(listOf("cheap", "middle", "expensive"), group.displayedSubtotalPrefilter.rankedCandidates.map { it.id })
        assertEquals(listOf(990L, 1990L, 2990L), group.displayedSubtotalPrefilter.rankedCandidates.map { it.merchandiseSubtotalCents })
        assertEquals(ShoppingRankingBasis.DISPLAYED_SUBTOTAL, group.rankingBasis)
        assertTrue(group.lowestConfirmedCandidateIds.isEmpty())
        assertFalse(group.displayedSubtotalPrefilter.isPayable)
        assertFalse(group.displayedSubtotalPrefilter.isFinalBest)
        assertFalse(result.productBindingVerified)
        assertEquals(candidates.map { it.id }, result.possibleDuplicateCandidateIds)
        assertTrue(result.limitations.any { "不证明同页节点属于同一商品或SKU" in it })
    }

    @Test fun `duplicate evidence remains rejected among otherwise valid cross-page candidates`() {
        val f = Fixture()
        val first = f.candidate("first")
        val nextPage = first.copy(
            id = "next",
            productIdentity = first.productIdentity.copy(evidence = first.productIdentity.evidence.copy(snapshotId = "next-page")),
            specification = first.specification.copy(evidence = first.specification.evidence.copy(snapshotId = "next-page")),
            unitPrice = first.unitPrice.copy(evidence = first.unitPrice.evidence.copy(snapshotId = "next-page")),
        )
        val renamed = nextPage.copy(id = "renamed", unitPrice = nextPage.unitPrice.copy(
            evidence = nextPage.unitPrice.evidence.copy(quote = "100.00"),
        ))
        val error = runCatching {
            DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = listOf(first, nextPage, renamed)),
                f.observed() + f.observed().single().copy(snapshotId = "next-page"))
        }.exceptionOrNull()
        assertTrue(error is ShoppingComparisonException)
        assertEquals("duplicate_candidate_evidence", (error as ShoppingComparisonException).code)
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

    @Test fun `truncated candidate facts and fee nodes reject even a matching shorter quote`() {
        val f = Fixture()
        val candidate = f.candidate()
        val refs = listOf(candidate.productIdentity.evidence, candidate.specification.evidence, candidate.unitPrice.evidence,
            candidate.shipping!!.evidence, candidate.otherFees!!.evidence)
        refs.forEach { ref ->
            val observed = f.observed().map { page -> page.copy(nodes = page.nodes.map { node ->
                node.copy(truncated = node.nodeId == ref.nodeId)
            }) }
            val shortened = candidate.copy(unitPrice = candidate.unitPrice.copy(evidence = candidate.unitPrice.evidence.copy(quote = "100.00")))
            val error = runCatching {
                DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = listOf(shortened)), observed)
            }.exceptionOrNull()
            assertTrue(error is ShoppingComparisonException)
            assertEquals("incomplete_evidence", (error as ShoppingComparisonException).code)
        }
    }

    @Test fun `clipped price or coupon text cannot hide conditions beyond the retained prefix`() {
        val f = Fixture()
        val price = f.ref("¥100.00".padEnd(240, ' ') + "券后价")
        val candidate = f.candidate().copy(unitPrice = ShoppingMoney("100.00", price.copy(quote = "100.00")))
        assertNull(f.compare(listOf(candidate)).groups.single().rankedCandidates.single().confirmedPlan)
        val acquisition = f.ref("已领取可用".padEnd(240, ' ') + "仅会员使用")
        val coupon = f.coupon().copy(acquisitionEvidence = acquisition.copy(quote = "已领取可用"))
        assertFalse(f.compare(listOf(candidate), listOf(coupon)).groups.single().rankedCandidates.single().couponDecisions.single().included)

        listOf(price, acquisition).forEach { clipped ->
            val observed = f.observed().map { page -> page.copy(nodes = page.nodes.map { node ->
                if (node.nodeId == clipped.nodeId) node.copy(text = node.text.take(240), truncated = true) else node
            }) }
            val error = runCatching {
                DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = listOf(candidate), coupons = listOf(coupon)), observed)
            }.exceptionOrNull()
            assertTrue(error is ShoppingComparisonException)
            assertEquals("incomplete_evidence", (error as ShoppingComparisonException).code)
        }
    }

    @Test fun `unreferenced truncated nodes do not poison complete evidence and fabricated quotes remain unverified`() {
        val f = Fixture()
        val candidate = f.candidate()
        val observed = f.observed().map { it.copy(nodes = it.nodes + ShoppingObservedNode("r-truncated", "unrelated", truncated = true)) }
        val result = DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = listOf(candidate)), observed)
        assertEquals(10500L, result.groups.single().rankedCandidates.single().confirmedPlan!!.payableCents)
        assertFalse(result.productBindingVerified)
        val fake = candidate.copy(unitPrice = ShoppingMoney("1.00", ShoppingEvidenceRef("snapshot-shop", "r-truncated", "¥1.00")))
        val error = runCatching {
            DiscountEngine { 1_000 }.compare(ShoppingComparisonRequest(candidates = listOf(fake)), observed)
        }.exceptionOrNull() as ShoppingComparisonException
        assertEquals("unverified_evidence", error.code)
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
