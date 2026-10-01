package me.rerere.rikkahub.data.shopping

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** Supplied by the host from real observations in the current grant, never by tool arguments. */
data class ShoppingObservedEvidence(val snapshotId: String, val packageName: String, val nodes: List<ShoppingObservedNode>)
/** Truncation is host metadata; model evidence arguments cannot override it. */
data class ShoppingObservedNode(val nodeId: String, val text: String, val description: String = "", val truncated: Boolean = false)

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
enum class ShoppingComparisonMode { EXACT_PRODUCT, FUNCTIONAL_ALTERNATIVES }

@Serializable
enum class ShoppingFunctionalCategory { USB_C_CABLE }

/** Values are parsed locally from complete observed nodes, never supplied as a model grouping key. */
@Serializable
data class ShoppingCableSpecEvidence(
    val connectors: ShoppingEvidenceRef? = null,
    val power: ShoppingEvidenceRef? = null,
    val length: ShoppingEvidenceRef? = null,
    @SerialName("pack_quantity") val packQuantity: ShoppingEvidenceRef? = null,
)

@Serializable
data class ShoppingFunctionalSpecification(
    val category: ShoppingFunctionalCategory,
    val connectors: String,
    @SerialName("power_watts") val powerWatts: Int,
    @SerialName("length_mm") val lengthMm: Int,
    @SerialName("units_per_pack") val unitsPerPack: Int,
    @SerialName("item_unit") val itemUnit: String = "条",
    @SerialName("price_unit") val priceUnit: String = "包",
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
    val brand: ShoppingFact? = null,
    val store: ShoppingFact? = null,
    @SerialName("cable_spec_evidence") val cableSpecEvidence: ShoppingCableSpecEvidence? = null,
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
    @SerialName("comparison_mode") val comparisonMode: ShoppingComparisonMode = ShoppingComparisonMode.EXACT_PRODUCT,
    @SerialName("functional_category") val functionalCategory: ShoppingFunctionalCategory? = null,
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
    @SerialName("product_identity") val productIdentity: ShoppingFact? = null,
    val specification: ShoppingFact? = null,
    val brand: ShoppingFact? = null,
    val store: ShoppingFact? = null,
)

@Serializable
/** Unresolved units/specifications retain raw facts, never an already simulated payable total. */
data class ShoppingUnrankedCandidate(val candidate: ShoppingCandidate, val reasons: List<String>)

@Serializable
enum class ShoppingRankingBasis { CONFIRMED_PAYABLE, CONFIRMED_PAYABLE_THEN_DISPLAYED_SUBTOTAL, DISPLAYED_SUBTOTAL }

@Serializable
data class ShoppingDisplayedSubtotalEntry(
    val id: String,
    @SerialName("merchandise_subtotal_cents") val merchandiseSubtotalCents: Long,
    @SerialName("unverified_fees") val unverifiedFees: List<String>,
    @SerialName("pending_conditions") val pendingConditions: List<String>,
)

@Serializable
data class ShoppingDisplayedSubtotalPrefilter(
    @SerialName("ranked_candidates") val rankedCandidates: List<ShoppingDisplayedSubtotalEntry>,
    @SerialName("ranking_basis") val rankingBasis: ShoppingRankingBasis = ShoppingRankingBasis.DISPLAYED_SUBTOTAL,
    @SerialName("is_payable") val isPayable: Boolean = false,
    @SerialName("is_final_best") val isFinalBest: Boolean = false,
    val detail: String = "仅按页面展示单价乘以数量升序预筛，同价保持输入顺序，不表示优劣；不加计运费和其他费用，也不另减优惠券。这不是实付价或最终最优，未知费用和券条件仍须核实。",
)

@Serializable
data class ShoppingComparisonGroup(
    @SerialName("product_identity") val productIdentity: String,
    val specification: String,
    val quantity: Int,
    @SerialName("ranked_candidates") val rankedCandidates: List<ShoppingCandidateResult>,
    @SerialName("lowest_confirmed_candidate_ids") val lowestConfirmedCandidateIds: List<String>,
    @SerialName("has_unpriced_candidates") val hasUnpricedCandidates: Boolean,
    @SerialName("ranking_basis") val rankingBasis: ShoppingRankingBasis,
    @SerialName("displayed_subtotal_prefilter") val displayedSubtotalPrefilter: ShoppingDisplayedSubtotalPrefilter,
    @SerialName("functional_specification") val functionalSpecification: ShoppingFunctionalSpecification? = null,
)

@Serializable
data class ShoppingComparisonResult(
    val currency: String = "CNY",
    @SerialName("evaluated_at_epoch_ms") val evaluatedAtEpochMillis: Long,
    val groups: List<ShoppingComparisonGroup>,
    @SerialName("possible_duplicate_candidate_ids") val possibleDuplicateCandidateIds: List<String> = emptyList(),
    @SerialName("product_binding_verified") val productBindingVerified: Boolean = false,
    @SerialName("comparison_mode") val comparisonMode: ShoppingComparisonMode = ShoppingComparisonMode.EXACT_PRODUCT,
    @SerialName("unranked_candidates") val unrankedCandidates: List<ShoppingUnrankedCandidate> = emptyList(),
    val limitations: List<String> = listOf(
        "EXACT_PRODUCT仅比较同身份、规格与数量；FUNCTIONAL_ALTERNATIVES仅比较本地可解析的同用途、核心规格、每包数量及购买数量，品牌和店铺仍分别保留，不是全平台最低价。",
        "功能属性一致只说明已引用文字的比较口径一致，不证明性能、质量相同或商品归属；未解析、缺失或多规格候选不参与横向排序。",
        "当前仅核对引用来自真实观察节点，尚未核证标题、规格、价格、店铺和优惠属于同一商品；候选ID或条数不能证明不同SKU，排序不是最终最优。",
        "单个候选的商品身份、规格和单价必须来自同一快照；此约束只拦截跨快照拼接，不证明同页节点属于同一商品或SKU。",
        "跨快照相同标题和规格仅标记可能重复，可能是同一商品再次出现，也可能是不同店铺同款；不会自动合并或宣称已确认独立商品。",
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
        checkInput(request.comparisonMode != ShoppingComparisonMode.FUNCTIONAL_ALTERNATIVES ||
            request.functionalCategory == ShoppingFunctionalCategory.USB_C_CABLE,
            "功能替代比较须明确支持的用途类别；当前仅支持USB-C数据线，不猜测其他商品的核心规格。")
        val evidence = EvidenceIndex(observed)
        request.candidates.forEach { validateCandidate(it, evidence) }
        val coreKeys = request.candidates.map { candidate ->
            coreRefs(candidate).map { it.snapshotId to it.nodeId }
        }
        if (coreKeys.distinct().size != coreKeys.size) throw ShoppingComparisonException(
            "duplicate_candidate_evidence", "多个候选重复引用同一组商品身份、规格和价格节点；更换ID或摘录不能增加不同候选数量，请保留一项并继续观察。",
        )
        val possibleDuplicateIds = request.candidates.groupBy {
            Triple(evidence.source(it.productIdentity.evidence), normalizedFact(it.productIdentity.value), normalizedFact(it.specification.value))
        }.values.filter { candidates ->
            candidates.size > 1 && candidates.map { coreRefs(it).map { ref -> ref.snapshotId } }.distinct().size > 1
        }.flatten().map { it.id }.toSet()
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
        val results = request.candidates.associate { it.id to evaluate(it, request.coupons, pairRules, evidence, now) }
        val unranked = mutableListOf<ShoppingUnrankedCandidate>()
        data class GroupKey(val identity: String, val specification: String, val quantity: Int,
            val functional: ShoppingFunctionalSpecification? = null)
        val grouped = linkedMapOf<GroupKey, MutableList<ShoppingCandidateResult>>()
        request.candidates.forEach { candidate ->
            val functional = if (request.comparisonMode == ShoppingComparisonMode.FUNCTIONAL_ALTERNATIVES) {
                val parsed = parseCableSpecification(candidate, evidence)
                if (parsed.specification == null) {
                    unranked += ShoppingUnrankedCandidate(candidate, parsed.reasons)
                    return@forEach
                }
                parsed.specification
            } else null
            val key = if (functional == null) GroupKey(candidate.productIdentity.value.trim(), candidate.specification.value.trim(), candidate.quantity)
            else GroupKey("USB-C数据线（功能替代比较，商品身份分别保留）",
                "${functional.connectors} / ${functional.powerWatts}W / ${functional.lengthMm}mm / ${functional.unitsPerPack}条装",
                candidate.quantity, functional)
            grouped.getOrPut(key) { mutableListOf() } += results.getValue(candidate.id)
        }
        val groups = grouped.map { (key, evaluated) ->
                val ranked = evaluated.sortedWith(
                    compareBy<ShoppingCandidateResult> { it.confirmedPlan?.payableCents ?: Long.MAX_VALUE }
                        .thenBy { if (it.confirmedPlan == null) it.merchandiseSubtotalCents else 0L },
                )
                val lowest = ranked.mapNotNull { it.confirmedPlan?.payableCents }.minOrNull()
                val basis = when {
                    ranked.all { it.confirmedPlan != null } -> ShoppingRankingBasis.CONFIRMED_PAYABLE
                    lowest == null -> ShoppingRankingBasis.DISPLAYED_SUBTOTAL
                    else -> ShoppingRankingBasis.CONFIRMED_PAYABLE_THEN_DISPLAYED_SUBTOTAL
                }
                val prefilter = ShoppingDisplayedSubtotalPrefilter(evaluated.sortedBy { it.merchandiseSubtotalCents }.map {
                    ShoppingDisplayedSubtotalEntry(it.id, it.merchandiseSubtotalCents, buildList {
                        if (it.shippingCents == null) add("shipping")
                        if (it.otherFeesCents == null) add("other_fees")
                    }, it.unknowns)
                })
                ShoppingComparisonGroup(key.identity, key.specification, key.quantity, ranked,
                    ranked.filter { lowest != null && it.confirmedPlan?.payableCents == lowest }.map { it.id },
                    ranked.any { it.confirmedPlan == null }, basis, prefilter, key.functional)
            }
        return ShoppingComparisonResult(evaluatedAtEpochMillis = now, groups = groups,
            possibleDuplicateCandidateIds = request.candidates.map { it.id }.filter { it in possibleDuplicateIds },
            comparisonMode = request.comparisonMode, unrankedCandidates = unranked)
    }

    private fun coreRefs(candidate: ShoppingCandidate) = listOf(
        candidate.productIdentity.evidence, candidate.specification.evidence, candidate.unitPrice.evidence,
    )

    private fun normalizedFact(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

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
        if (coreRefs(candidate).map { it.snapshotId }.distinct().size != 1) throw ShoppingComparisonException(
            "candidate_evidence_mismatch", "单个候选的商品身份、规格和单价必须引用同一快照，不能跨页拼接；请重新观察并在同一快照内补齐。不同候选可分别来自不同快照，同快照也不证明商品或SKU归属。",
        )
        candidate.shipping?.let { evidence.money(it, allowFree = true) }
        candidate.otherFees?.let { evidence.money(it, allowFree = true) }
        checkInput(candidate.qualityEvidence.size <= 8 && candidate.unknowns.size <= 20 && candidate.unknowns.all { it.length <= 300 }, "候选证据或未知条件过长。")
        candidate.qualityEvidence.forEach(evidence::fact)
        candidate.brand?.let(evidence::fact)
        candidate.store?.let(evidence::fact)
        val addedRefs = listOfNotNull(candidate.brand?.evidence, candidate.store?.evidence) +
            candidate.cableSpecEvidence?.let { listOfNotNull(it.connectors, it.power, it.length, it.packQuantity) }.orEmpty()
        addedRefs.forEach(evidence::source)
        if (addedRefs.any { it.snapshotId != candidate.productIdentity.evidence.snapshotId }) throw ShoppingComparisonException(
            "candidate_evidence_mismatch", "商品品牌、店铺和功能规格引用必须与该候选核心证据同一快照；尚无商品归属时不能跨页拼接。",
        )
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
        val refs = coreRefs(candidate) +
            listOfNotNull(candidate.quantityEvidence, candidate.shipping?.evidence, candidate.otherFees?.evidence, candidate.rating?.evidence, candidate.rating?.reviewCountEvidence) +
            candidate.qualityEvidence.map { it.evidence } + addedRefs
        checkInput(refs.map(evidence::source).distinct().size == 1, "单个候选必须来自同一应用，不能拼接不同店铺页面的价格。")
    }

    private data class ParsedCable(val specification: ShoppingFunctionalSpecification?, val reasons: List<String>)

    /** Deliberately small grammar: unclear/ranged/multiple specifications stay outside rankings. */
    private fun parseCableSpecification(candidate: ShoppingCandidate, evidence: EvidenceIndex): ParsedCable {
        val refs = candidate.cableSpecEvidence
        val reasons = mutableListOf<String>()
        fun full(ref: ShoppingEvidenceRef?): String? = ref?.let { normalizeSpec(evidence.fullText(it)) }
        val identity = normalizeSpec(evidence.fullText(candidate.productIdentity.evidence) + "\n" +
            evidence.fullText(candidate.specification.evidence))
        if (!Regex("数据线|充电线|快充线|连接线|cable").containsMatchIn(identity) ||
            Regex("充电器|充电头|电源适配器|转接头|扩展坞").containsMatchIn(identity)) {
            reasons += "用途未能确认是单独数据线，不能把充电器、转接器或组合商品当作相同用途。"
        }
        val connectorText = full(refs?.connectors)?.replace(Regex("usb\\s*-?\\s*c|type\\s*-?\\s*c"), "c")
            ?.replace(Regex("\\s+"), "")
        val connectorsKnown = connectorText != null &&
            Regex("双(?:头)?c|c(?:公)?(?:对|转|到|to|-|2)c(?:公)?").containsMatchIn(connectorText) &&
            !Regex("lightning|雷电接口|micro.?usb|usb-?a|type-?a|a(?:对|转|到|to|-|2)c|c(?:对|转|到|to|-|2)a|一拖|三合一|多接口|非双c|不是|不支持")
                .containsMatchIn(connectorText)
        if (!connectorsKnown) reasons += "连接器缺失、存在其他接口或表达未能解析为USB-C对USB-C。"
        val powerText = full(refs?.power)
        val power = parseSingleMeasurement(powerText, "w|瓦", mapOf("w" to BigDecimal.ONE, "瓦" to BigDecimal.ONE), 1_000)
        if (power == null) reasons += "额定功率缺失、表达不明确或同时出现多个功率规格，不能猜测W数。"
        val length = parseSingleMeasurement(full(refs?.length), "毫米|厘米|mm|cm|米|m",
            mapOf("毫米" to BigDecimal.ONE, "mm" to BigDecimal.ONE, "厘米" to BigDecimal.TEN,
                "cm" to BigDecimal.TEN, "米" to BigDecimal(1_000), "m" to BigDecimal(1_000)), 100_000)
        if (length == null) reasons += "长度缺失、范围或多规格不能合并比较；须有唯一可解析的米/厘米/毫米证据。"
        val packText = full(refs?.packQuantity)
        val pack = parsePackQuantity(packText)
        if (pack == null) reasons += "每包条数缺失、范围或多包装规格不能猜测为单条；须有明确条/根单位。"
        val priceText = normalizeSpec(evidence.fullText(candidate.unitPrice.evidence))
        if (Regex("(?:每|/)\\s*(?:米|厘米|毫米|m|cm|mm)(?![a-z])").containsMatchIn(priceText)) {
            reasons += "展示单价的计价单位与每包价格不一致，不能直接乘购买包数。"
        }
        if (pack != null && pack > 1 && !hasExplicitPackPrice(priceText, candidate.unitPrice.amount)) {
            reasons += "多条包装须有与所报金额直接对应的每包/每套报价；单条、单根、混合口径或单位未明确的价格不能当整包价。"
        }
        return ParsedCable(if (reasons.isEmpty()) ShoppingFunctionalSpecification(
            ShoppingFunctionalCategory.USB_C_CABLE, "USB_C_TO_USB_C", checkNotNull(power), checkNotNull(length), checkNotNull(pack),
        ) else null, reasons)
    }

    private fun normalizeSpec(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun hasExplicitPackPrice(text: String, amount: String): Boolean {
        // A pack label elsewhere in the node cannot reclassify an individually quoted amount.
        if (Regex("(?:每|单|一|1|/)\\s*(?:条|根)").containsMatchIn(text)) return false
        if (hasUncertainNumericExpression(text, "包|套|元") ||
            Regex("(?<![0-9.])(?:[2-9]|[1-9][0-9]+|二|两|三|四|五|六|七|八|九|十)\\s*(?:包|套)").containsMatchIn(text)) return false
        val value = "(?<![0-9.])${Regex.escape(amount)}(?![0-9.])"
        val prefix = "(?:每\\s*(?:包|套)(?:售价|价格|报价|价)?|整\\s*(?:包|套)(?:售价|价格|报价|价)|(?:包|套)价|(?<![0-9])(?:1|一)\\s*(?:包|套))"
        return Regex("$prefix\\s*(?:为|是)?\\s*[:：]?\\s*[¥￥]?\\s*$value").containsMatchIn(text) ||
            Regex("$value\\s*元?\\s*(?:/\\s*(?:包|套)|(?:每|一|1)\\s*(?:包|套))").containsMatchIn(text)
    }

    /** Check the complete field before extracting any endpoint; Chinese counts follow the same rules. */
    private fun hasUncertainNumericExpression(text: String, units: String): Boolean {
        val number = "(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万]+|单|双)"
        val separator = "(?:[\\p{Pd}−~〜∼]|至|到|或|/|、)"
        val unit = "(?:$units)"
        val range = Regex("$number\\s*(?:$unit\\s*)?$separator\\s*$number")
        val prefix = Regex("(?:大约|约为|约|大概|接近|近|大于|小于|超过|不足|少于|多于|至少|至多|最多|最少|不超过|不少于|不低于|不高于|不满|[≈≃≅≥≤><±−-])\\s*$number\\s*$unit")
        val suffix = Regex("$number\\s*$unit\\s*(?:装\\s*)?(?:及以上|及以下|以上|以下|以内|以外|左右|上下|起|不等|[+＋])")
        return range.containsMatchIn(text) || prefix.containsMatchIn(text) || suffix.containsMatchIn(text)
    }

    private fun parseSingleMeasurement(text: String?, units: String, factors: Map<String, BigDecimal>, maximum: Int): Int? {
        if (text == null) return null
        val number = "[0-9]+(?:\\.[0-9]+)?"
        if (hasUncertainNumericExpression(text, units) ||
            Regex("(?:不支持|不是|不含|不提供|非)\\s*$number\\s*(?:$units)(?![a-z])").containsMatchIn(text)) return null
        val matches = Regex("(?<![0-9.])($number)\\s*($units)(?![a-z])").findAll(text).toList()
        if (matches.isEmpty()) return null
        val values = matches.map { match ->
            val amount = match.groupValues[1].toBigDecimalOrNull() ?: return null
            val scaled = amount.multiply(factors.getValue(match.groupValues[2]))
            val value = try { scaled.intValueExact() } catch (_: ArithmeticException) { return null }
            if (value !in 1..maximum) return null
            value
        }.distinct()
        return values.singleOrNull()
    }

    private fun parsePackQuantity(text: String?): Int? {
        if (text == null) return null
        if (Regex("赠|随机|任选|不含|不带").containsMatchIn(text)) return null
        if (hasUncertainNumericExpression(text, "条|根")) return null
        // Do not extract a supported suffix from an unsupported compound Chinese numeral.
        val values = Regex("(?<![0-9.零〇一二两兩三四五六七八九十百千万萬亿億兆壹贰貳叁參肆伍陆陸柒捌玖拾佰仟-])([0-9]+|单|一|二|两|三|四|五|六|七|八|九|十)\\s*(?:条|根)")
            .findAll(text).toList().map { match ->
                val value = match.groupValues[1].toIntOrNull() ?: when (match.groupValues[1]) {
                    "单", "一" -> 1; "二", "两" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
                    "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
                    else -> return null
                }
                if (value !in 1..1_000) return null
                value
            }.toList().distinct()
        return values.singleOrNull()
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
            candidate.qualityEvidence, decisions, unknowns.distinct(), candidate.productIdentity, candidate.specification,
            candidate.brand, candidate.store)
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
            if (node.truncated) throw ShoppingComparisonException(
                "incomplete_evidence", "所引用节点正文已被截断，可能缺少价格、规格或优惠限制，不能用于正式比较；请取得完整节点证据，缩短quote不能消除宿主截断状态。",
            )
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
