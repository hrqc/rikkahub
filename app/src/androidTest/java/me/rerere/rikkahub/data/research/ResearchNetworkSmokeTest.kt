package me.rerere.rikkahub.data.research

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Opt-in public GET only. It never launches an app, reads its UI, uses Root, or calls a model. */
@RunWith(AndroidJUnit4::class)
class ResearchNetworkSmokeTest {
    @Test
    fun readsOfficialSourceAndVerifiesLocalEvidence() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "公开网络 smoke 默认不运行；需显式传入 researchNetwork=true",
            arguments.getString("researchNetwork") == "true",
        )
        val requested = arguments.getString("researchUrl") ?: DEFAULT_URL
        val url = requested.toHttpUrlOrNull()
        assertTrue("researchUrl 必须是允许的官方 HTTPS 域名及标准端口，不能含登录凭据", url != null &&
            url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.host in OFFICIAL_HOSTS)
        val checkedUrl = checkNotNull(url).newBuilder().fragment(null).build().toString()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.randomUUID().toString()
        val directory = File(instrumentation.targetContext.cacheDir, "research_smoke_$runId")
        val conversation = "research-network-smoke-$runId"
        try {
            // With network explicitly enabled, HTTP/parse/storage exceptions fail this test.
            // There is deliberately no assume/skip or fallback mock after this point.
            val source = ResearchRepository(directory).read(conversation, checkedUrl)
            assertEquals("实际请求来源应保留", checkedUrl, source.requestedUrl)
            assertTrue("最终来源必须仍是允许的官方域名", source.finalUrl.toHttpUrlOrNull()?.host in OFFICIAL_HOSTS)
            assertTrue("应取得足够正文，不能把空壳页面作为成功", source.text.length in 160..24_000)
            assertTrue("应取得来源标题", source.title.isNotBlank())
            assertTrue("正文 MIME 必须明确", source.mimeType in setOf("text/html", "application/xhtml+xml", "text/plain"))
            assertTrue("抓取时间必须可解析", Instant.parse(source.retrievedAt).epochSecond > 0)
            assertTrue("链接数量必须受限", source.links.size <= 24)
            assertTrue("应说明提取局限", source.limitations.isNotEmpty())
            assertEquals("哈希必须匹配实际留存的 UTF-8 正文", sha256(source.text), source.textSha256)
            if (url.host in setOf("developer.android.com", "developer.android.google.cn") &&
                url.encodedPath == "/guide/topics/ui/accessibility/service") {
                assertTrue("默认 Android 官方页面应包含 AccessibilityService 正文证据", source.text.contains("AccessibilityService"))
            }

            // A fresh repository instance proves this came from disk, not an in-memory result.
            val reopened = ResearchRepository(directory)
            val cached = reopened.source(conversation, source.id)
            assertEquals(source.id, cached.id)
            assertEquals(source.finalUrl, cached.finalUrl)
            assertEquals(source.retrievedAt, cached.retrievedAt)
            assertEquals(source.textSha256, sha256(cached.text))
            val otherChat = runCatching { reopened.source("other-$conversation", source.id) }.exceptionOrNull()
            assertTrue("另一个聊天不能复用本来源 ID", otherChat is ResearchReadException)
            assertEquals("SOURCE_NOT_FOUND", (otherChat as ResearchReadException).code)
            assertFalse("成功后应存在本次来源缓存", directory.listFiles().isNullOrEmpty())

            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "research_network=verified; host=${url.host}; textChars=${source.text.length}; " +
                    "links=${source.links.size}; cached=verified; sha256=${source.textSha256}\n")
            })
        } finally {
            // Remove only files created in this exact, newly named smoke-test cache directory.
            val expected = directory.canonicalFile
            directory.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == expected }
                .forEach { it.delete() }
            directory.delete()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val DEFAULT_URL = "https://developer.android.com/guide/topics/ui/accessibility/service"
        val OFFICIAL_HOSTS = setOf("developer.android.com", "developer.android.google.cn", "github.com", "raw.githubusercontent.com")
    }
}
