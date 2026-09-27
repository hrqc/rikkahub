package me.rerere.rikkahub.data.shopping

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

@Serializable
data class ReviewEvidenceRequest(val groups: List<ReviewEvidenceGroup>)

@Serializable
data class ReviewEvidenceGroup(
    @SerialName("candidate_id") val candidateId: String,
    val reviews: List<ReviewEvidenceSample>,
)

@Serializable
data class ReviewEvidenceSample(
    val body: ShoppingEvidenceRef,
    @SerialName("rating_evidence") val ratingEvidence: ShoppingEvidenceRef? = null,
    @SerialName("critical_evidence") val criticalEvidence: ShoppingEvidenceRef? = null,
    @SerialName("follow_up_evidence") val followUpEvidence: ShoppingEvidenceRef? = null,
)

@Serializable
data class ReviewSamplingGap(val code: String, val detail: String)

@Serializable
data class ReviewEvidenceGroupResult(
    @SerialName("candidate_id") val candidateId: String,
    @SerialName("product_binding_verified") val productBindingVerified: Boolean = false,
    @SerialName("independent_review_count") val independentReviewCount: Int? = null,
    @SerialName("submitted_body_fragments") val submittedBodyFragments: Int,
    @SerialName("source_node_samples") val sourceNodeSamples: Int,
    @SerialName("pattern_eligible_source_nodes") val patternEligibleSourceNodes: Int,
    @SerialName("source_packages") val sourcePackages: List<String>,
    @SerialName("quoted_rating_evidence") val quotedRatingEvidence: List<ShoppingEvidenceRef>,
    @SerialName("quoted_critical_label_evidence") val quotedCriticalLabelEvidence: List<ShoppingEvidenceRef>,
    @SerialName("quoted_follow_up_label_evidence") val quotedFollowUpLabelEvidence: List<ShoppingEvidenceRef>,
    @SerialName("sampling_gaps") val samplingGaps: List<ReviewSamplingGap>,
)

@Serializable
enum class ReviewRepetitionKind { NORMALIZED_FRAGMENT_DUPLICATE, HIGH_FRAGMENT_SIMILARITY }

@Serializable
data class ReviewQuotedSource(
    @SerialName("candidate_labels") val candidateLabels: List<String>,
    val evidence: ShoppingEvidenceRef,
)

@Serializable
data class ReviewRepetitionIndicator(
    val kind: ReviewRepetitionKind,
    val first: ReviewQuotedSource,
    val second: ReviewQuotedSource,
    val detail: String,
)

@Serializable
data class ReviewEvidenceResult(
    val groups: List<ReviewEvidenceGroupResult>,
    @SerialName("submitted_body_fragments") val submittedBodyFragments: Int,
    @SerialName("source_node_samples") val sourceNodeSamples: Int,
    @SerialName("independent_review_count") val independentReviewCount: Int? = null,
    @SerialName("product_binding_verified") val productBindingVerified: Boolean = false,
    @SerialName("reused_source_nodes_across_groups") val reusedSourceNodesAcrossGroups: Int,
    @SerialName("repetition_indicators") val repetitionIndicators: List<ReviewRepetitionIndicator>,
    @SerialName("indicators_truncated") val indicatorsTruncated: Boolean,
    val limitations: List<String> = listOf(
        "仅分析本次授权中实际观察且被引用的片段；样本非全量、非随机，未覆盖内容和评价总量未知。",
        "candidate_id 仅为模型分组标签，商品归属未核证；来源节点不等于独立评论，独立评论数、作者和实际购买均未核证。",
        "不同快照可能重复呈现同一评论，同一节点也可能聚合多条评论；不能把节点样本数当作评价人数或相加推算全量。",
        "评分、中差评和追评仅列原文线索；缺少宿主评论条目归属，不能确认标签与正文、商品对应，也不能据标签证明已采样相应评论。",
        "重复或高相似仅指引用片段的文字模式，也可能来自同一评论、平台默认文案或正常表达，不能确认刷单或虚假评价。",
        "未发现重复不代表评价真实；本工具不是鉴伪工具，不输出刷单概率、可信度分数、商品质量排名或购买结论。",
        "没有分析评价日期或时间集中度，不依据未观察到的日期推断集中发布。",
        "短句或信息量低的片段不参与模板检测；每组5个来源节点只是采样不足提示阈值，不代表统计充分或具有代表性。",
    ),
)

class ReviewEvidenceException(val code: String, message: String) : IllegalArgumentException(message)

/** Pure, bounded analysis of quoted fragments. It never authenticates reviews or ranks products. */
class ReviewEvidenceAnalyzer {
    fun analyze(request: ReviewEvidenceRequest, observed: List<ShoppingObservedEvidence>): ReviewEvidenceResult {
        val index = validatedIndex(request, observed)
        val allBodies = linkedMapOf<NodeKey, BodySource>()
        val groups = request.groups.map { group ->
            val bodies = linkedMapOf<NodeKey, BodySource>()
            group.reviews.forEach { sample ->
                addBody(bodies, sample.body, group.candidateId)
                addBody(allBodies, sample.body, group.candidateId)
            }
            val ratings = group.reviews.mapNotNull { it.ratingEvidence }.distinct()
            val critical = group.reviews.mapNotNull { it.criticalEvidence }.distinct()
            val followUps = group.reviews.mapNotNull { it.followUpEvidence }.distinct()
            val packages = group.reviews.flatMap(::references).map(index::packageName).distinct()
            ReviewEvidenceGroupResult(
                candidateId = group.candidateId,
                submittedBodyFragments = group.reviews.size,
                sourceNodeSamples = bodies.size,
                patternEligibleSourceNodes = bodies.values.count { it.eligible },
                sourcePackages = packages,
                quotedRatingEvidence = ratings,
                quotedCriticalLabelEvidence = critical,
                quotedFollowUpLabelEvidence = followUps,
                samplingGaps = buildList {
                    if (bodies.size < MIN_SAMPLE_HINT) add(ReviewSamplingGap("FEW_SOURCE_NODE_SAMPLES",
                        "当前仅有${bodies.size}个正文来源节点样本，少于提示阈值5；不足以概括商品评价，达到阈值也不代表充分。"))
                    if (bodies.size < group.reviews.size) add(ReviewSamplingGap("REUSED_SOURCE_NODE",
                        "同一快照节点被引用多次，已按一个来源节点计数，不能拆分片段增加独立评论数。"))
                    if (critical.isEmpty()) add(ReviewSamplingGap("NO_CRITICAL_LABEL_EVIDENCE",
                        "未提供带有中差评或低星文字的真实引用；中差评采样缺失，不能据此认为没有差评。"))
                    if (followUps.isEmpty()) add(ReviewSamplingGap("NO_FOLLOW_UP_LABEL_EVIDENCE",
                        "未提供带有追评或追加评价文字的真实引用；追评采样缺失，不能据此推断长期使用表现。"))
                    if (ratings.isEmpty()) add(ReviewSamplingGap("NO_RATING_EVIDENCE", "未提供可核对的评分文字。"))
                    if (packages.size > 1) add(ReviewSamplingGap("MIXED_SOURCE_PACKAGES",
                        "此模型分组含多个应用的引用，不能确认属于同一商品或商家。"))
                    add(ReviewSamplingGap("REVIEW_ASSOCIATION_UNVERIFIED",
                        "商品、正文、评分与中差评/追评标签的条目关联均未核证，不能确认独立评论数或各类评论已充分采样。"))
                },
            )
        }
        val eligible = allBodies.values.filter { it.eligible }
        val indicators = mutableListOf<ReviewRepetitionIndicator>()
        var truncated = false
        for (firstIndex in eligible.indices) {
            for (secondIndex in firstIndex + 1 until eligible.size) {
                val first = eligible[firstIndex]
                val second = eligible[secondIndex]
                val kind = when {
                    first.normalized == second.normalized -> ReviewRepetitionKind.NORMALIZED_FRAGMENT_DUPLICATE
                    highlySimilar(first, second) -> ReviewRepetitionKind.HIGH_FRAGMENT_SIMILARITY
                    else -> continue
                }
                if (indicators.size >= MAX_INDICATORS) {
                    truncated = true
                    continue
                }
                indicators += ReviewRepetitionIndicator(kind,
                    ReviewQuotedSource(first.labels.toList(), first.evidence),
                    ReviewQuotedSource(second.labels.toList(), second.evidence),
                    if (kind == ReviewRepetitionKind.NORMALIZED_FRAGMENT_DUPLICATE)
                        "两个不同来源节点的长片段在统一全半角、大小写并移除标点空白后相同；只提示重复文字，不能认定是两条独立评论或刷单。"
                    else "两个不同来源节点的长片段具有高度相似的文字模式；只提示核查原文和更多样本，不表示刷单概率或商品质量。",
                )
            }
        }
        return ReviewEvidenceResult(
            groups = groups,
            submittedBodyFragments = request.groups.sumOf { it.reviews.size },
            sourceNodeSamples = allBodies.size,
            reusedSourceNodesAcrossGroups = allBodies.values.count { it.labels.size > 1 },
            repetitionIndicators = indicators,
            indicatorsTruncated = truncated,
        )
    }

    /** Recheck only this request's references after computation, including every optional annotation. */
    fun verifyEvidence(request: ReviewEvidenceRequest, observed: List<ShoppingObservedEvidence>) {
        validatedIndex(request, observed)
    }

    private fun validatedIndex(request: ReviewEvidenceRequest, observed: List<ShoppingObservedEvidence>): EvidenceIndex {
        requireInput(request.groups.size in 1..8, "每次支持1至8个模型分组；样本不足仍应如实报告，不能凑数。")
        requireInput(request.groups.map { it.candidateId }.distinct().size == request.groups.size, "分组标签必须唯一。")
        val index = EvidenceIndex(observed)
        request.groups.forEach { group ->
            requireInput(group.candidateId.matches(Regex("[A-Za-z0-9_-]{1,64}")), "分组标签须为1至64位字母、数字、下划线或连字符。")
            requireInput(group.reviews.size <= 20, "每组最多20个正文片段。")
            group.reviews.forEach { sample ->
                references(sample).forEach(index::verify)
                sample.ratingEvidence?.let {
                    requireInput(RATING_MARKER.containsMatchIn(it.quote), "评分引用必须保留评分、星级或分值单位等上下文，不能只提交无含义的数字。")
                }
                sample.criticalEvidence?.let {
                    requireInput(CRITICAL_MARKER.containsMatchIn(it.quote), "中差评引用必须保留中评、差评或低星文字；不能由模型自报评价类别。")
                }
                sample.followUpEvidence?.let {
                    requireInput(FOLLOW_UP_MARKER.containsMatchIn(it.quote), "追评引用必须保留追评或追加评价文字；不能由模型自报追评身份。")
                }
            }
        }
        return index
    }

    private fun addBody(destination: MutableMap<NodeKey, BodySource>, evidence: ShoppingEvidenceRef, label: String) {
        val key = NodeKey(evidence.snapshotId, evidence.nodeId)
        val normalized = normalize(evidence.quote)
        val existing = destination[key]
        if (existing == null) destination[key] = BodySource(evidence, normalized, linkedSetOf(label))
        else {
            existing.labels += label
            // One representative per source node; multiple quotes can never create extra comments.
            if (normalized.size > existing.normalized.size) {
                destination[key] = BodySource(evidence, normalized, existing.labels)
            }
        }
    }

    private fun highlySimilar(first: BodySource, second: BodySource): Boolean {
        val shorter = minOf(first.normalized.size, second.normalized.size)
        val longer = maxOf(first.normalized.size, second.normalized.size)
        if (shorter * 100 < longer * 85) return false
        val intersection = first.shingles.count { it in second.shingles }
        val union = first.shingles.size + second.shingles.size - intersection
        // Internal text-overlap threshold, never returned as an authenticity or fraud score.
        return union > 0 && intersection * 100 >= union * 85
    }

    private data class NodeKey(val snapshotId: String, val nodeId: String)

    private data class BodySource(
        val evidence: ShoppingEvidenceRef,
        val normalized: List<Int>,
        val labels: MutableSet<String>,
    ) {
        val eligible = normalized.size >= MIN_PATTERN_LENGTH && normalized.distinct().size >= 8
        val shingles: Set<List<Int>> by lazy { normalized.windowed(3).toSet() }
    }

    private class EvidenceIndex(private val observed: List<ShoppingObservedEvidence>) {
        fun verify(ref: ShoppingEvidenceRef) {
            requireInput(ref.snapshotId.isNotBlank() && ref.snapshotId.length <= 160 &&
                ref.nodeId.isNotBlank() && ref.nodeId.length <= 160 && ref.quote.isNotBlank() && ref.quote.length <= 1000,
                "证据引用必须提供有界的快照ID、节点ID和最多1000字符的非空原文。")
            val snapshot = observed.singleOrNull { it.snapshotId == ref.snapshotId }
            val node = snapshot?.nodes?.singleOrNull { it.nodeId == ref.nodeId }
            if (node == null || !(node.text.contains(ref.quote) || node.description.contains(ref.quote))) {
                throw ReviewEvidenceException("unverified_evidence", "引用未对应本次宿主观察中的唯一节点原文，不能使用模型改写、旧快照或自行提供的评价。")
            }
        }

        fun packageName(ref: ShoppingEvidenceRef): String = observed.single { it.snapshotId == ref.snapshotId }.packageName
    }

    companion object {
        private const val MIN_SAMPLE_HINT = 5
        private const val MIN_PATTERN_LENGTH = 24
        private const val MAX_INDICATORS = 20
        private val RATING_MARKER = Regex("评分|星级|[0-9一二三四五](?:\\.[0-9]+)?\\s*(?:星|分)|[0-9](?:\\.[0-9]+)?\\s*/\\s*[0-9]")
        private val CRITICAL_MARKER = Regex("中评|差评|(?<![0-9.])[123](?![0-9.])\\s*星|(?<![零一二三四五六七八九十百千万])[一二三]\\s*星")
        private val FOLLOW_UP_MARKER = Regex("追评|追加评价|追加评论")

        private fun references(sample: ReviewEvidenceSample) = listOfNotNull(
            sample.body, sample.ratingEvidence, sample.criticalEvidence, sample.followUpEvidence,
        )

        private fun normalize(text: String): List<Int> = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).codePoints().toArray().filter { Character.isLetterOrDigit(it) }

        private fun requireInput(condition: Boolean, detail: String) {
            if (!condition) throw ReviewEvidenceException("invalid_arguments", detail)
        }
    }
}
