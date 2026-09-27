package me.rerere.rikkahub.data.shopping

import org.junit.Assert.*
import org.junit.Test

class ReviewEvidenceAnalyzerTest {
    private val longReview = "已经连续使用这个商品两个星期，接口连接比较稳定，包装完整，暂时没有发现明显的问题。"

    private class Fixture {
        private val snapshots = linkedMapOf<String, MutableList<ShoppingObservedNode>>()
        private var nextNode = 0

        fun ref(text: String, snapshot: String = "page", node: String = "n${++nextNode}", description: Boolean = false): ShoppingEvidenceRef {
            snapshots.getOrPut(snapshot) { mutableListOf() }.add(
                ShoppingObservedNode(node, if (description) "" else text, if (description) text else ""),
            )
            return ShoppingEvidenceRef(snapshot, node, text)
        }

        fun observed() = snapshots.map { (id, nodes) -> ShoppingObservedEvidence(id, "com.example.shop", nodes.toList()) }
        fun analyze(vararg groups: ReviewEvidenceGroup) = ReviewEvidenceAnalyzer().analyze(ReviewEvidenceRequest(groups.toList()), observed())
    }

    private fun group(vararg refs: ShoppingEvidenceRef, id: String = "candidate") =
        ReviewEvidenceGroup(id, refs.map { ReviewEvidenceSample(it) })

    private fun assertRejected(code: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected a bounded evidence error, got ${error?.javaClass?.simpleName}", error is ReviewEvidenceException)
        assertEquals(code, (error as ReviewEvidenceException).code)
    }

    @Test fun `actual quoted text and description are accepted without claiming product or comment identity`() {
        val f = Fixture()
        val result = f.analyze(group(f.ref(longReview), f.ref("收到后试用了两天，目前还需要继续观察。", description = true)))
        assertEquals(2, result.sourceNodeSamples)
        assertNull(result.independentReviewCount)
        assertFalse(result.productBindingVerified)
        assertNull(result.groups.single().independentReviewCount)
        assertFalse(result.groups.single().productBindingVerified)
        assertTrue(result.limitations.any { it.contains("非全量、非随机") })
        assertTrue(result.limitations.any { it.contains("不是鉴伪工具") })
    }

    @Test fun `fabricated quotes nodes and old snapshots are rejected`() {
        val f = Fixture()
        val ref = f.ref(longReview)
        listOf(ref.copy(quote = "商家刷单已经确认"), ref.copy(nodeId = "missing"), ref.copy(snapshotId = "old-grant")).forEach {
            assertRejected("unverified_evidence") { f.analyze(group(it)) }
        }
    }

    @Test fun `quote cannot span text and description or resolve ambiguous host ids`() {
        val evidence = listOf(ShoppingObservedEvidence("page", "shop", listOf(ShoppingObservedNode("n", "正文", "标签"))))
        val request = ReviewEvidenceRequest(listOf(group(ShoppingEvidenceRef("page", "n", "正文\n标签"))))
        assertRejected("unverified_evidence") { ReviewEvidenceAnalyzer().analyze(request, evidence) }
        val valid = ReviewEvidenceRequest(listOf(group(ShoppingEvidenceRef("page", "n", "正文"))))
        assertRejected("unverified_evidence") { ReviewEvidenceAnalyzer().analyze(valid, evidence + evidence) }
        assertRejected("unverified_evidence") {
            ReviewEvidenceAnalyzer().analyze(valid, listOf(evidence.single().copy(nodes = evidence.single().nodes + evidence.single().nodes)))
        }
    }

    @Test fun `multiple quotes and repeated requests for one node never become independent comments`() {
        val f = Fixture()
        val ref = f.ref(longReview)
        val result = f.analyze(group(ref, ref.copy(quote = "接口连接比较稳定"), ref))
        assertEquals(3, result.submittedBodyFragments)
        assertEquals(1, result.sourceNodeSamples)
        assertEquals(1, result.groups.single().sourceNodeSamples)
        assertTrue(result.repetitionIndicators.isEmpty())
        assertTrue(result.groups.single().samplingGaps.any { it.code == "REUSED_SOURCE_NODE" })
        assertNull(result.independentReviewCount)
    }

    @Test fun `reusing one node in different model groups is counted once globally`() {
        val f = Fixture()
        val ref = f.ref(longReview)
        val result = f.analyze(group(ref, id = "a"), group(ref, id = "b"))
        assertEquals(1, result.sourceNodeSamples)
        assertEquals(1, result.reusedSourceNodesAcrossGroups)
        assertTrue(result.repetitionIndicators.isEmpty())
        assertTrue(result.groups.all { it.sourceNodeSamples == 1 && !it.productBindingVerified })
    }

    @Test fun `different snapshots reusing node id stay source samples with independence unknown`() {
        val f = Fixture()
        val result = f.analyze(group(f.ref(longReview, "page1", "n1"), f.ref(longReview, "page2", "n1")))
        assertEquals(2, result.sourceNodeSamples)
        assertNull(result.independentReviewCount)
        assertEquals(ReviewRepetitionKind.NORMALIZED_FRAGMENT_DUPLICATE, result.repetitionIndicators.single().kind)
        assertTrue(result.limitations.any { it.contains("不同快照可能重复呈现同一评论") })
    }

    @Test fun `generic short reviews and low information repeated characters do not trigger template hints`() {
        val f = Fixture()
        val result = f.analyze(group(f.ref("好评"), f.ref("好评！"), f.ref("非常好，非常好，非常好，非常好，非常好，非常好，非常好，非常好"),
            f.ref("非常好，非常好，非常好，非常好，非常好，非常好，非常好，非常好")))
        assertEquals(4, result.sourceNodeSamples)
        assertEquals(0, result.groups.single().patternEligibleSourceNodes)
        assertTrue(result.repetitionIndicators.isEmpty())
    }

    @Test fun `long normalized duplicates preserve original evidence without outputting authenticity scores`() {
        val f = Fixture()
        val first = f.ref("ＡＢＣ接口测试了两个星期，整体连接稳定，外包装完整，暂时没有发现明显的问题。")
        val second = f.ref("abc 接口测试了两个星期 整体连接稳定 外包装完整 暂时没有发现明显的问题！")
        val result = f.analyze(group(first, second))
        val indicator = result.repetitionIndicators.single()
        assertEquals(ReviewRepetitionKind.NORMALIZED_FRAGMENT_DUPLICATE, indicator.kind)
        assertEquals(first, indicator.first.evidence)
        assertEquals(second, indicator.second.evidence)
        assertTrue(indicator.detail.contains("不能认定"))
    }

    @Test fun `small wording changes in long fragments only produce a text similarity hint`() {
        val f = Fixture()
        val first = "测试了这款数据线两个星期，每天连接电脑和手机传输文件，接口松紧适中，线材柔软方便收纳，包装说明清楚完整，暂时还没有发现断连或明显发热的问题。"
        val second = first.replace("两个星期", "三个星期")
        val result = f.analyze(group(f.ref(first), f.ref(second)))
        assertEquals(ReviewRepetitionKind.HIGH_FRAGMENT_SIMILARITY, result.repetitionIndicators.single().kind)
        assertTrue(result.repetitionIndicators.single().detail.contains("不表示刷单概率"))
    }

    @Test fun `unrelated long reviews do not create repetition indicators`() {
        val f = Fixture()
        val result = f.analyze(group(f.ref(longReview), f.ref("到货后发现商品颜色和图片不一致，联系客服申请退换，目前售后仍然等待处理，希望之后能够解决。")))
        assertEquals(2, result.groups.single().patternEligibleSourceNodes)
        assertTrue(result.repetitionIndicators.isEmpty())
    }

    @Test fun `missing and small samples explicitly retain critical follow up and association gaps`() {
        val f = Fixture()
        val result = f.analyze(group(id = "empty"), group(f.ref("还需要观察"), id = "small"))
        result.groups.forEach { entry ->
            assertTrue(entry.samplingGaps.map { it.code }.containsAll(listOf("FEW_SOURCE_NODE_SAMPLES",
                "NO_CRITICAL_LABEL_EVIDENCE", "NO_FOLLOW_UP_LABEL_EVIDENCE", "NO_RATING_EVIDENCE", "REVIEW_ASSOCIATION_UNVERIFIED")))
        }
        assertEquals(0, result.groups.first().sourceNodeSamples)
        assertNull(result.groups.first().independentReviewCount)
    }

    @Test fun `quoted labels are displayed but never prove annotation to review or product association`() {
        val f = Fixture()
        val body = f.ref(longReview)
        val rating = f.ref("评分 3/5")
        val critical = f.ref("差评") // Could be a filter button, not a review category.
        val followUp = f.ref("追加评价") // Could also be a filter button.
        val result = f.analyze(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(body, rating, critical, followUp))))
        val entry = result.groups.single()
        assertEquals(1, entry.sourceNodeSamples)
        assertEquals(listOf(rating), entry.quotedRatingEvidence)
        assertEquals(listOf(critical), entry.quotedCriticalLabelEvidence)
        assertEquals(listOf(followUp), entry.quotedFollowUpLabelEvidence)
        assertTrue(entry.samplingGaps.any { it.code == "REVIEW_ASSOCIATION_UNVERIFIED" })
        assertFalse(entry.productBindingVerified)
        assertNull(entry.independentReviewCount)
    }

    @Test fun `optional annotations require observed context and cannot promote arbitrary text into labels`() {
        val f = Fixture()
        val body = f.ref(longReview)
        val plain = f.ref("5")
        val samples = listOf(
            ReviewEvidenceSample(body, ratingEvidence = body.copy(quote = "评分5星")),
            ReviewEvidenceSample(body, criticalEvidence = body.copy(nodeId = "missing")),
            ReviewEvidenceSample(body, followUpEvidence = body.copy(snapshotId = "old")),
        )
        samples.forEach { sample -> assertRejected("unverified_evidence") { f.analyze(ReviewEvidenceGroup("a", listOf(sample))) } }
        listOf(ReviewEvidenceSample(body, ratingEvidence = plain), ReviewEvidenceSample(body, criticalEvidence = body),
            ReviewEvidenceSample(body, followUpEvidence = body)).forEach { sample ->
            assertRejected("invalid_arguments") { f.analyze(ReviewEvidenceGroup("a", listOf(sample))) }
        }
    }

    @Test fun `decimal tails and multi digit ratings cannot become low star evidence while one star is allowed`() {
        val f = Fixture()
        val body = f.ref(longReview)
        listOf("评分4.3星", "评分13星", "评分十三星").forEach { text ->
            val critical = f.ref(text)
            assertRejected("invalid_arguments") {
                f.analyze(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(body, criticalEvidence = critical))))
            }
        }
        listOf("评分1星", "一星评价").forEach { text ->
            val critical = f.ref(text)
            val result = f.analyze(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(body, criticalEvidence = critical))))
            assertEquals(listOf(critical), result.groups.single().quotedCriticalLabelEvidence)
            assertFalse(result.groups.single().productBindingVerified)
        }
    }

    @Test fun `request sizes quotes and candidate labels are bounded`() {
        val f = Fixture()
        val ref = f.ref("x".repeat(1001))
        assertRejected("invalid_arguments") { f.analyze(group(ref)) }
        assertRejected("invalid_arguments") { f.analyze() }
        assertRejected("invalid_arguments") { f.analyze(*(1..9).map { group(id = "c$it") }.toTypedArray()) }
        assertRejected("invalid_arguments") { f.analyze(group(id = "a"), group(id = "a")) }
        assertRejected("invalid_arguments") { f.analyze(group(id = "not a label")) }
        val short = f.ref("好评")
        assertRejected("invalid_arguments") { f.analyze(group(*List(21) { short }.toTypedArray())) }
    }

    @Test fun `many duplicate pairs cap output while preserving source counts and sample limits`() {
        val f = Fixture()
        val result = f.analyze(group(*(1..8).map { f.ref(longReview) }.toTypedArray()))
        assertEquals(8, result.sourceNodeSamples)
        assertEquals(20, result.repetitionIndicators.size)
        assertTrue(result.indicatorsTruncated)
        assertFalse(result.groups.single().samplingGaps.any { it.code == "FEW_SOURCE_NODE_SAMPLES" })
        assertTrue(result.limitations.any { it.contains("不代表统计充分") })
    }

    @Test fun `evidence revalidation checks optional annotations and permits unrelated snapshot eviction`() {
        val f = Fixture()
        val body = f.ref(longReview, "body-page")
        val label = f.ref("追加评价", "label-page")
        f.ref("与请求无关的节点", "unrelated")
        val request = ReviewEvidenceRequest(listOf(ReviewEvidenceGroup("a", listOf(ReviewEvidenceSample(body, followUpEvidence = label)))))
        val analyzer = ReviewEvidenceAnalyzer()
        analyzer.verifyEvidence(request, f.observed().filterNot { it.snapshotId == "unrelated" })
        assertRejected("unverified_evidence") { analyzer.verifyEvidence(request, f.observed().filterNot { it.snapshotId == "label-page" }) }
    }
}
