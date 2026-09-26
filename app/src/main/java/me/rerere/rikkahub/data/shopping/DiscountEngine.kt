package me.rerere.rikkahub.data.shopping

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** Supplied by the host from real observations in the current grant, never by tool arguments. */
data class ShoppingObservedEvidence(val snapshotId: String, val packageName: String, val nodes: List<ShoppingObservedNode>)
data class ShoppingObservedNode(val nodeId: String, val text: String, val description: String = "")

@Serializable
data class ShoppingEvidenceRef(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("node_id") val nodeId: String,
    val quote: String,
)

@Serializable
data class ShoppingFact(val value: String, val evidence: ShoppingEvidenceRef)

/** Decimal input is parsed once into integer fen; floating point never participates in prices. */
@Serializable
data class ShoppingMoney(val amount: String, val evidence: ShoppingEvidenceRef)

@Serializable
data class ShoppingRating(
    val value: String,
    val scale: String,
    val evidence: ShoppingEvidenceRef,
    @SerialName("review_count") val reviewCount: Long? = null,
    @SerialName("review_count_evidence") val reviewCountEvidence: ShoppingEvidenceRef? = null,
)

@Serializable
data class ShoppingCandidate(
    val id: String,
    @SerialName("product_identity") val productIdentity: ShoppingFact,
    val specification: ShoppingFact,
    val quantity: Int,
    @SerialName("quantity_evidence") val quantityEvidence: ShoppingEvidenceRef? = null,
    @SerialName("unit_price") val unitPrice: ShoppingMoney,
    val shipping: ShoppingMoney? = null,
    @SerialName("other_fees") val otherFees: ShoppingMoney? = null,
    @SerialName("price_before_listed_coupons") val priceBeforeListedCoupons: Boolean = false,
    val rating: ShoppingRating? = null,
    @SerialName("quality_evidence") val qualityEvidence: List<ShoppingFact> = emptyList(),
    val unknowns: List<String> = emptyList(),
)

@Serializable
enum class ShoppingCouponAcquisition { AVAILABLE, FREE_CLAIMABLE, ACCOUNT_CHANGE, PAID, POINTS, STORED_VALUE, UNKNOWN }
@Serializable
enum class ShoppingCouponKind { MERCHANDISE_FIXED, SHIPPING_FIXED, UNKNOWN }
@Serializable
enum class ShoppingThresholdBasis { ORIGINAL_MERCHANDISE, UNKNOWN }
@Serializable
enum class ShoppingEligibility { CONFIRMED, INELIGIBLE, UNKNOWN }

@Serializable
data class ShoppingCoupon(
    val id: String,
    val kind: ShoppingCouponKind,
    val discount: ShoppingMoney,
    @SerialName("minimum_spend") val minimumSpend: ShoppingMoney,
    @SerialName("threshold_basis") val thresholdBasis: ShoppingThresholdBasis = ShoppingThresholdBasis.UNKNOWN,
    @SerialName("eligible_candidate_ids") val eligibleCandidateIds: List<String>,
    @SerialName("scope_evidence") val scopeEvidence: ShoppingEvidenceRef,
    val eligibility: ShoppingEligibility = ShoppingEligibility.UNKNOWN,
    @SerialName("eligibility_evidence") val eligibilityEvidence: ShoppingEvidenceRef,
    val acquisition: ShoppingCouponAcquisition = ShoppingCouponAcquisition.UNKNOWN,
    @SerialName("acquisition_evidence") val acquisitionEvidence: ShoppingEvidenceRef,
    @SerialName("valid_from_epoch_ms") val validFromEpochMillis: Long? = null,
    @SerialName("valid_until_epoch_ms") val validUntilEpochMillis: Long? = null,
    @SerialName("validity_evidence") val validityEvidence: ShoppingEvidenceRef,
    @SerialName("exclusive_group") val exclusiveGroup: String? = null,
    val unknowns: List<String> = emptyList(),
)

@Serializable
data class ShoppingCompatibility(
    @SerialName("first_coupon_id") val firstCouponId: String,
    @SerialName("second_coupon_id") val secondCouponId: String,
    val stackable: Boolean,
    val evidence: ShoppingEvidenceRef,
)

@Serializable
data class ShoppingComparisonRequest(
    val currency: String = "CNY",
    val candidates: List<ShoppingCandidate>,
    val coupons: List<ShoppingCoupon> = emptyList(),
    val compatibility: List<ShoppingCompatibility> = emptyList(),
)

@Serializable
data class ShoppingCouponDecision(val id: String, val included: Boolean, val reasons: List<String>)

@Serializable
data class ShoppingPricePlan(
    @SerialName("payable_cents") val payableCents: Long,
    @SerialName("coupon_ids") val couponIds: List<String>,
    @SerialName("free_claim_required") val freeClaimRequired: List<String>,
    @SerialName("merchandise_discount_cents") val merchandiseDiscountCents: Long,
    @SerialName("shipping_discount_cents") val shippingDiscountCents: Long,
)

@Serializable
data class ShoppingCandidateResult(
    val id: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("merchandise_subtotal_cents") val merchandiseSubtotalCents: Long,
    @SerialName("shipping_cents") val shippingCents: Long?,
    @SerialName("other_fees_cents") val otherFeesCents: Long?,
    @SerialName("baseline_cents") val baselineCents: Long?,
    @SerialName("confirmed_plan") val confirmedPlan: ShoppingPricePlan?,
    @SerialName("after_free_claim_plan") val afterFreeClaimPlan: ShoppingPricePlan?,
    val rating: ShoppingRating?,
    @SerialName("quality_evidence") val qualityEvidence: List<ShoppingFact>,
    @SerialName("coupon_decisions") val couponDecisions: List<ShoppingCouponDecision>,
    val unknowns: List<String>,
)

@Serializable
data class ShoppingComparisonGroup(
    @SerialName("product_identity") val productIdentity: String,
    val specification: String,
    val quantity: Int,
    @SerialName("ranked_candidates") val rankedCandidates: List<ShoppingCandidateResult>,
    @SerialName("lowest_confirmed_candidate_ids") val lowestConfirmedCandidateIds: List<String>,
    @SerialName("has_unpriced_candidates") val hasUnpricedCandidates: Boolean,
)

@Serializable
data class ShoppingComparisonResult(
    val currency: String = "CNY",
    @SerialName("evaluated_at_epoch_ms") val evaluatedAtEpochMillis: Long,
    val groups: List<ShoppingComparisonGroup>,
    val limitations: List<String> = listOf(
        "排序仅限本次已观察证据中商品身份、规格和数量相同的候选，不是全平台最低价。",
        "可确认实付基于页面提取的已知价格、费用及已可用优惠；领取后方案仍须实际免费领取成功并在结算页复核。",
        "评分、评价数量和质量证据分别展示；评分高不等于质量最好，页面宣传不构成质量保证。",
        "未计入隐藏优惠、未知规则、会员/订阅、账户状态变更、积分或储值；计算不执行领取、选券、下单或付款。",
    ),
)

class ShoppingComparisonException(val code: String, message: String) : IllegalArgumentException(message)

/** Bounded, deterministic simulation of fixed coupons, shipping and other mandatory charges. */
class DiscountEngine(private val nowMillis: () -> Long = System::currentTimeMillis) {
    fun compare(request: ShoppingComparisonRequest, observed: List<ShoppingObservedEvidence>): ShoppingComparisonResult {
        checkInput(request.currency == "CNY", "目前仅支持人民币，不能混合币种比较。")
        checkInput(request.candidates.size in 1..12 && request.coupons.size <= 12, "每次支持1至12个候选、最多12张券。")
        checkInput(request.candidates.map { it.id }.distinct().size == request.candidates.size &&
            request.coupons.map { it.id }.distinct().size == request.coupons.size, "候选和券的ID必须分别唯一。")
        checkInput(request.compatibility.size <= 66, "叠加关系数量超过上限。")
        val evidence = EvidenceIndex(observed)
        request.candidates.forEach { validateCandidate(it, evidence) }
        request.coupons.forEach { coupon ->
            checkId(coupon.id)
            checkInput(coupon.eligibleCandidateIds.isNotEmpty() && coupon.eligibleCandidateIds.size <= 12 &&
                coupon.eligibleCandidateIds.all { id -> request.candidates.any { it.id == id } }, "券适用候选必须来自本次请求。")
            evidence.money(coupon.discount)
            evidence.money(coupon.minimumSpend, allowNoMinimum = true)
            couponRefs(coupon).forEach(evidence::source)
            checkInput(couponRefs(coupon).map(evidence::source).distinct().size == 1, "同一张券不能拼接不同应用的规则。")
            checkInput(coupon.unknowns.size <= 20 && coupon.unknowns.all { it.length <= 300 }, "未知条件过长。")
            checkInput(coupon.exclusiveGroup == null || coupon.exclusiveGroup.length <= 80, "互斥组名称过长。")
        }
        val couponIds = request.coupons.map { it.id }.toSet()
        val pairRules = mutableMapOf<Set<String>, Boolean>()
        request.compatibility.forEach { rule ->
            checkInput(rule.firstCouponId in couponIds && rule.secondCouponId in couponIds &&
                rule.firstCouponId != rule.secondCouponId, "叠加关系引用无效。")
            val source = evidence.source(rule.evidence)
            val ruleText = evidence.fullText(rule.evidence)
            checkInput(!rule.stackable || allowsStacking.containsMatchIn(ruleText) && !deniesStacking.containsMatchIn(ruleText),
                "叠加证据未明确允许或含互斥条件；不能只把stackable填true就合并优惠。")
            checkInput(request.coupons.filter { it.id in setOf(rule.firstCouponId, rule.secondCouponId) }
                .all { evidence.source(it.scopeEvidence) == source }, "叠加证据与券不属于同一应用。")
            val key = setOf(rule.firstCouponId, rule.secondCouponId)
            checkInput(key !in pairRules, "同一对券只能提供一条明确的叠加或互斥关系。")
            pairRules[key] = rule.stackable
        }
        val now = nowMillis()
        val groups = request.candidates.groupBy { Triple(it.productIdentity.value.trim(), it.specification.value.trim(), it.quantity) }
            .map { (key, candidates) ->
                val ranked = candidates.map { evaluate(it, request.coupons, pairRules, evidence, now) }
                    .sortedWith(compareBy<ShoppingCandidateResult> { it.confirmedPlan?.payableCents ?: Long.MAX_VALUE }.thenBy { it.id })
                val lowest = ranked.mapNotNull { it.confirmedPlan?.payableCents }.minOrNull()
                ShoppingComparisonGroup(key.first, key.second, key.third, ranked,
                    ranked.filter { lowest != null && it.confirmedPlan?.payableCents == lowest }.map { it.id },
                    ranked.any { it.confirmedPlan == null })
            }
        return ShoppingComparisonResult(evaluatedAtEpochMillis = now, groups = groups)
    }

    private fun validateCandidate(candidate: ShoppingCandidate, evidence: EvidenceIndex) {
        checkId(candidate.id)
        checkInput(candidate.quantity in 1..1_000, "商品数量必须在1至1000之间。")
        evidence.fact(candidate.productIdentity)
        evidence.fact(candidate.specification)
        checkInput(candidate.quantity == 1 || candidate.quantityEvidence != null, "多件价格比较必须有实际数量证据；无数量证据时只比较单件。")
        candidate.quantityEvidence?.let {
            evidence.source(it)
            checkInput(numberAppears(candidate.quantity.toString(), it.quote), "商品数量未在引用证据中出现。")
        }
        evidence.money(candidate.unitPrice)
        candidate.shipping?.let { evidence.money(it, allowFree = true) }
        candidate.otherFees?.let { evidence.money(it, allowFree = true) }
        checkInput(candidate.qualityEvidence.size <= 8 && candidate.unknowns.size <= 20 && candidate.unknowns.all { it.length <= 300 }, "候选证据或未知条件过长。")
        candidate.qualityEvidence.forEach(evidence::fact)
        candidate.rating?.let { rating ->
            evidence.source(rating.evidence)
            val value = rating.value.toBigDecimalOrNull()
            val scale = rating.scale.toBigDecimalOrNull()
            checkInput(value != null && scale != null && value >= BigDecimal.ZERO && scale > BigDecimal.ZERO &&
                scale <= BigDecimal(100) && value <= scale && numberAppears(rating.value, rating.evidence.quote) &&
                numberAppears(rating.scale, rating.evidence.quote), "评分或满分值缺少明确页面证据。")
            if (rating.reviewCount != null) {
                checkInput(rating.reviewCount >= 0 && rating.reviewCountEvidence != null, "评价数量必须有证据且不能为负。")
                evidence.source(rating.reviewCountEvidence!!)
                checkInput(numberAppears(rating.reviewCount.toString(), rating.reviewCountEvidence.quote), "仅接受页面明确评价数量，不把模糊销量或万+转换成精确评价数。")
            }
        }
        val refs = listOf(candidate.productIdentity.evidence, candidate.specification.evidence, candidate.unitPrice.evidence) +
            listOfNotNull(candidate.quantityEvidence, candidate.shipping?.evidence, candidate.otherFees?.evidence, candidate.rating?.evidence, candidate.rating?.reviewCountEvidence) +
            candidate.qualityEvidence.map { it.evidence }
        checkInput(refs.map(evidence::source).distinct().size == 1, "单个候选必须来自同一应用，不能拼接不同店铺页面的价格。")
    }

    private fun evaluate(candidate: ShoppingCandidate, coupons: List<ShoppingCoupon>, pairs: Map<Set<String>, Boolean>, evidence: EvidenceIndex, now: Long): ShoppingCandidateResult {
        val source = evidence.source(candidate.unitPrice.evidence)
        val subtotal = Math.multiplyExact(moneyToCents(candidate.unitPrice.amount), candidate.quantity.toLong())
        val shipping = candidate.shipping?.let { moneyToCents(it.amount) }
        val fees = candidate.otherFees?.let { moneyToCents(it.amount) }
        val unknowns = candidate.unknowns.toMutableList()
        if (shipping == null) unknowns += "运费/配送费尚未核实，不能确认实付。"
        if (fees == null) unknowns += "包装费、服务费等其他必付费用尚未核实，不能确认实付。"
        val priceBasisKnown = candidate.priceBeforeListedCoupons && !uncertainPrice.containsMatchIn(evidence.fullText(candidate.unitPrice.evidence))
        if (!priceBasisKnown) unknowns += "商品价格可能已含券、仅是预估/起价，或未明确优惠前口径，不能重复减券或确认实付。"
        val decisions = coupons.filter { candidate.id in it.eligibleCandidateIds }.map { coupon ->
            val reasons = mutableListOf<String>()
            if (evidence.source(coupon.scopeEvidence) != source) reasons += "优惠与候选不属于同一应用。"
            if (coupon.kind == ShoppingCouponKind.UNKNOWN) reasons += "减免方式不明确。"
            if (coupon.thresholdBasis != ShoppingThresholdBasis.ORIGINAL_MERCHANDISE) reasons += "门槛计算口径未知；不能猜测包含运费或折后门槛。"
            if (moneyToCents(coupon.minimumSpend.amount) > subtotal) reasons += "商品原小计未达到满减门槛，运费和其他费用不凑门槛。"
            if (coupon.eligibility != ShoppingEligibility.CONFIRMED) reasons += "本账号领取/使用资格未确认或不符合。"
            if (coupon.acquisition !in setOf(ShoppingCouponAcquisition.AVAILABLE, ShoppingCouponAcquisition.FREE_CLAIMABLE)) reasons += "不是当前已可用或普通免费可领券；不得自动开通、付费、消耗积分或储值。"
            if (riskyAcquisition.containsMatchIn(evidence.fullText(coupon.acquisitionEvidence))) reasons += "领取条款含账户权益、付费或资产条件，须由用户处理。"
            val start = coupon.validFromEpochMillis
            val end = coupon.validUntilEpochMillis
            if (start == null || end == null || start < 0 || end <= start) reasons += "有效期不完整或无效。"
            else if (now < start || now >= end) reasons += "尚未生效或已过期。"
            reasons += coupon.unknowns
            ShoppingCouponDecision(coupon.id, reasons.isEmpty(), reasons)
        }
        val usableIds = decisions.filter { it.included }.map { it.id }.toSet()
        val usable = coupons.filter { it.id in usableIds }
        for (i in usable.indices) for (j in i + 1 until usable.size) {
            val first = usable[i]; val second = usable[j]
            if (!sameExclusiveGroup(first, second) && setOf(first.id, second.id) !in pairs) {
                unknowns += "${first.id} 与 ${second.id} 的叠加关系未知，未合并计算。"
            }
        }
        val baseline = if (shipping != null && fees != null) Math.addExact(Math.addExact(subtotal, shipping), fees) else null
        val priceKnown = baseline != null && priceBasisKnown && candidate.unknowns.isEmpty()
        val confirmed = if (priceKnown) bestPlan(subtotal, shipping!!, fees!!,
            usable.filter { it.acquisition == ShoppingCouponAcquisition.AVAILABLE }, pairs) else null
        val afterClaim = if (priceKnown) bestPlan(subtotal, shipping!!, fees!!, usable, pairs)
            .takeIf { it.freeClaimRequired.isNotEmpty() && it.payableCents < confirmed!!.payableCents } else null
        return ShoppingCandidateResult(candidate.id, source, subtotal, shipping, fees, baseline, confirmed, afterClaim, candidate.rating,
            candidate.qualityEvidence, decisions, unknowns.distinct())
    }

    private fun bestPlan(subtotal: Long, shipping: Long, fees: Long, coupons: List<ShoppingCoupon>, pairs: Map<Set<String>, Boolean>): ShoppingPricePlan {
        var best = ShoppingPricePlan(subtotal + shipping + fees, emptyList(), emptyList(), 0, 0)
        for (mask in 1 until (1 shl coupons.size)) {
            val chosen = coupons.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            if (chosen.indices.any { i -> (i + 1 until chosen.size).any { j ->
                    sameExclusiveGroup(chosen[i], chosen[j]) || pairs[setOf(chosen[i].id, chosen[j].id)] != true
                } }) continue
            val merchandiseDiscount = minOf(subtotal, chosen.filter { it.kind == ShoppingCouponKind.MERCHANDISE_FIXED }.sumOf { moneyToCents(it.discount.amount) })
            val shippingDiscount = minOf(shipping, chosen.filter { it.kind == ShoppingCouponKind.SHIPPING_FIXED }.sumOf { moneyToCents(it.discount.amount) })
            val claims = chosen.filter { it.acquisition == ShoppingCouponAcquisition.FREE_CLAIMABLE }.map { it.id }.sorted()
            val plan = ShoppingPricePlan(subtotal - merchandiseDiscount + shipping - shippingDiscount + fees,
                chosen.map { it.id }.sorted(), claims, merchandiseDiscount, shippingDiscount)
            if (plan.payableCents < best.payableCents || plan.payableCents == best.payableCents &&
                (plan.freeClaimRequired.size < best.freeClaimRequired.size || plan.freeClaimRequired.size == best.freeClaimRequired.size && plan.couponIds.size < best.couponIds.size)) best = plan
        }
        return best
    }

    private fun sameExclusiveGroup(a: ShoppingCoupon, b: ShoppingCoupon) = !a.exclusiveGroup.isNullOrBlank() && a.exclusiveGroup == b.exclusiveGroup

    private fun couponRefs(c: ShoppingCoupon) = listOf(c.discount.evidence, c.minimumSpend.evidence, c.scopeEvidence,
        c.eligibilityEvidence, c.acquisitionEvidence, c.validityEvidence)

    private class EvidenceIndex(private val observed: List<ShoppingObservedEvidence>) {
        fun source(ref: ShoppingEvidenceRef): String {
            checkInput(ref.quote.isNotBlank() && ref.quote.length <= 1_000, "证据引用必须非空且不超过1000字符。")
            val snapshot = observed.singleOrNull { it.snapshotId == ref.snapshotId }
            val node = snapshot?.nodes?.singleOrNull { it.nodeId == ref.nodeId }
            if (snapshot == null || node == null || !(node.text + "\n" + node.description).contains(ref.quote)) {
                throw ShoppingComparisonException("unverified_evidence", "证据未命中本次授权的真实观察，请重新观察并引用原始节点，不得自报已核验。")
            }
            return snapshot.packageName
        }
        fun fact(fact: ShoppingFact) {
            source(fact.evidence)
            checkInput(fact.value.isNotBlank() && fact.value.length <= 300 && fact.evidence.quote.contains(fact.value), "商品信息或质量证据必须来自引用原文。")
        }
        fun fullText(ref: ShoppingEvidenceRef): String {
            source(ref)
            val node = observed.single { it.snapshotId == ref.snapshotId }.nodes.single { it.nodeId == ref.nodeId }
            return node.text + "\n" + node.description
        }
        fun money(value: ShoppingMoney, allowFree: Boolean = false, allowNoMinimum: Boolean = false) {
            source(value.evidence)
            val cents = moneyToCents(value.amount)
            checkInput(numberAppears(value.amount, value.evidence.quote) || allowFree && cents == 0L &&
                fullText(value.evidence).trim() in setOf("包邮", "免运费", "免费配送", "无其他费用", "免包装费") ||
                allowNoMinimum && cents == 0L && fullText(value.evidence).trim() == "无门槛", "金额必须出现在页面证据中；条件包邮不能当作无条件免运费。")
        }
    }

    companion object {
        private val riskyAcquisition = Regex("会员|订阅|续费|积分|储值|充值|付费|试用|绑定|年卡|月卡")
        private val uncertainPrice = Regex("券后|到手|预估|预计|低至|起价|[0-9]\\s*元?起")
        private val allowsStacking = Regex("可.{0,20}叠加|允许叠加|支持叠加|可同时使用|可以同时使用")
        private val deniesStacking = Regex("不可|不能|不支持|不允许|不可以|互斥|二选一|仅选|限选")
        fun moneyToCents(amount: String): Long {
            checkInput(amount.matches(Regex("(?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,2})?")), "金额必须是非负十进制字符串，最多两位小数，禁止浮点、科学计数或超范围值。")
            return amount.toBigDecimal().movePointRight(2).longValueExact()
        }
        private fun numberAppears(value: String, text: String) = Regex("(?<![0-9.])${Regex.escape(value)}(?![0-9.万亿+＋])").containsMatchIn(text)
        private fun checkId(id: String) = checkInput(id.matches(Regex("[A-Za-z0-9_-]{1,64}")), "ID必须为1至64位字母、数字、下划线或连字符。")
        private fun checkInput(ok: Boolean, message: String) { if (!ok) throw ShoppingComparisonException("invalid_arguments", message) }
    }
}
