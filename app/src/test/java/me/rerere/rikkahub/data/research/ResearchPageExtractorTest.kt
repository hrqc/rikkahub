package me.rerere.rikkahub.data.research

import org.junit.Assert.*
import org.junit.Test

class ResearchPageExtractorTest {
    private fun extract(html: String) = ResearchPageExtractor.extract(html.toByteArray(), "text/html", "UTF-8", PublicWebPolicy.url("https://example.com/docs/start"))

    @Test fun `extracts main facts and links while marking removed advertising and navigation`() {
        val result = extract("""
            <html><head><title>真实资料</title><meta property="article:published_time" content="2026-09-20"></head>
            <body><nav>导航诱导</nav><script>假正文脚本</script><main>
            <h1>技术资料</h1><p>${"这是可复核的主要事实，需要对照原始文档。".repeat(8)}</p>
            <div class="ad-banner">广告博彩假资料</div><div style="display:none">隐藏的内容</div>
            <p class="advice">正常建议不能误删。</p>
            <a href="/guide.pdf">PDF文档</a><a href="https://github.com/example/project">项目</a>
            <a href="http://127.0.0.1/private">私网不可用</a><a href="/guide.pdf">重复文档</a>
            </main><footer>页脚噪声</footer></body></html>
        """.trimIndent())
        assertEquals("真实资料", result.title)
        assertEquals("2026-09-20", result.declaredDate)
        assertTrue(result.text.contains("可复核"))
        assertTrue(result.text.contains("正常建议"))
        listOf("导航诱导", "假正文脚本", "广告博彩假资料", "隐藏的内容", "页脚噪声").forEach { assertFalse(result.text.contains(it)) }
        assertEquals(1, result.adsRemoved)
        assertTrue(result.noiseRemoved >= 3)
        assertEquals(listOf("pdf", "github"), result.links.map { it.kind })
        assertTrue(result.limitations.isNotEmpty())
    }

    @Test fun `login and explicit paywall declarations are not bypassed`() {
        listOf("<form><input type='password'></form><article>private</article>",
            "<div class='paywall'>Subscribe</div><article>private</article>",
            "<script type='application/ld+json'>{\"isAccessibleForFree\":false,\"articleBody\":\"private\"}</script>").forEach {
            assertEquals("ACCESS_RESTRICTED", (runCatching { extract(it) }.exceptionOrNull() as ResearchReadException).code)
        }
    }

    @Test fun `empty script app and sparse documents never claim a complete read`() {
        assertEquals("NO_READABLE_CONTENT", (runCatching { extract("<script>renderEverything()</script>") }.exceptionOrNull() as ResearchReadException).code)
        val sparse = extract("<body><p>Only a short preview.</p></body>")
        assertTrue(sparse.limitations.any { it.contains("很少") })
        assertEquals("body_without_common_noise", sparse.method)
    }

    @Test fun `long retained text and outgoing links are independently bounded`() {
        val source = extract("<main><p>${"正文".repeat(15_000)}</p>" +
            (1..40).joinToString("") { "<a href='/ref$it'>参考$it</a>" } + "</main>")
        assertEquals(24_000, source.text.length)
        assertTrue(source.truncated)
        assertEquals(24, source.links.size)
    }

    @Test fun `plain text is retained without invented title date or links`() {
        val source = ResearchPageExtractor.extract("abc".toByteArray(), "text/plain", "UTF-8", PublicWebPolicy.url("https://example.com/source.txt"))
        assertEquals("abc", source.text)
        assertNull(source.declaredDate)
        assertTrue(source.links.isEmpty())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", researchSha256(source.text))
    }

    @Test fun `declared HTML base resolves real links and private base links remain excluded`() {
        val page = "<head><base href='/v1/'></head><body><p>Public document</p><a href='guide'>Guide</a></body>"
        assertEquals("https://example.com/v1/guide", extract(page).links.single().url)
        assertTrue(extract(page.replace("/v1/", "http://127.0.0.1/")).links.isEmpty())
    }
}
