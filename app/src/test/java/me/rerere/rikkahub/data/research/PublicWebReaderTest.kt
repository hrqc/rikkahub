package me.rerere.rikkahub.data.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class PublicWebReaderTest {
    private val instant = Instant.parse("2026-09-26T01:02:03Z")
    private fun html(value: String) = PublicWebResponse(200, "text/html; charset=utf-8", null, value.toByteArray())

    @Test fun `public redirect preserves actual final source and retained text hash`() = runBlocking {
        val requested = mutableListOf<String>()
        val reader = PublicWebReader(PublicWebTransport { url ->
            requested += url.toString()
            if (requested.size == 1) PublicWebResponse(302, null, "/article", ByteArray(0))
            else html("<html><title>Source</title><main><p>" + "Actual source evidence. ".repeat(15) + "</p></main></html>")
        }, now = { instant })
        val source = reader.read("https://example.com/start")
        assertEquals(listOf("https://example.com/start", "https://example.com/article"), requested)
        assertEquals("https://example.com/article", source.finalUrl)
        assertEquals("https://example.com/start", source.requestedUrl)
        assertEquals(instant.toString(), source.retrievedAt)
        assertEquals(researchSha256(source.text), source.textSha256)
        assertNull(source.pageDeclaredPublishedAt)
    }

    @Test fun `redirect to metadata address is rejected before a second fetch`() = runBlocking {
        var calls = 0
        val reader = PublicWebReader(PublicWebTransport {
            calls++; PublicWebResponse(302, null, "http://169.254.169.254/latest/meta-data", ByteArray(0))
        })
        assertEquals("URL_NOT_PUBLIC", (runCatching { reader.read("https://example.com/start") }.exceptionOrNull() as ResearchReadException).code)
        assertEquals(1, calls)
    }

    @Test fun `redirect loops and downgrade terminate without replay`() = runBlocking {
        listOf("https://example.com/start" to "REDIRECT_LOOP", "http://example.com/unsafe" to "INSECURE_REDIRECT").forEach { (target, code) ->
            var calls = 0
            val reader = PublicWebReader(PublicWebTransport { calls++; PublicWebResponse(302, null, target, ByteArray(0)) })
            assertEquals(code, (runCatching { reader.read("https://example.com/start") }.exceptionOrNull() as ResearchReadException).code)
            assertEquals(1, calls)
        }
    }

    @Test fun `redirect count is limited to three hops`() = runBlocking {
        var calls = 0
        val reader = PublicWebReader(PublicWebTransport { calls++; PublicWebResponse(302, null, "/hop$calls", ByteArray(0)) })
        assertEquals("TOO_MANY_REDIRECTS", (runCatching { reader.read("https://example.com/start") }.exceptionOrNull() as ResearchReadException).code)
        assertEquals(4, calls)
    }

    @Test fun `unsupported downloads access denial and oversize pages never become evidence`() = runBlocking {
        listOf(
            PublicWebResponse(200, "application/pdf", null, ByteArray(0)) to "UNSUPPORTED_CONTENT",
            PublicWebResponse(403, "text/html", null, "Forbidden".toByteArray()) to "ACCESS_RESTRICTED",
            PublicWebResponse(500, "text/html", null, "Error".toByteArray()) to "HTTP_STATUS",
            PublicWebResponse(200, "text/plain", null, ByteArray(RESEARCH_MAX_BYTES + 1)) to "PAGE_TOO_LARGE",
        ).forEach { (response, code) ->
            val reader = PublicWebReader(PublicWebTransport { response })
            assertEquals(code, (runCatching { reader.read("https://example.com/source") }.exceptionOrNull() as ResearchReadException).code)
        }
    }

    @Test fun `cancellation propagates and never causes another fetch`() = runBlocking {
        var calls = 0
        val reader = PublicWebReader(PublicWebTransport { calls++; throw CancellationException("user stopped") })
        val error = runCatching { reader.read("https://example.com/source") }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals("user stopped", error?.message)
        assertEquals(1, calls)
    }

    @Test fun `DNS connection and TLS diagnostics never copy sensitive exception text or causes`() {
        val secret = "https://secret.example/path?token=private-key body=private-page-text"
        listOf(
            UnknownHostException(secret) to "DNS_ERROR",
            SocketTimeoutException(secret) to "CONNECT_TIMEOUT",
            SSLHandshakeException(secret) to "TLS_FAILED",
            IOException(secret) to "FETCH_FAILED",
        ).forEach { (error, code) ->
            val safe = safeResearchNetworkFailure(IOException(secret, error), connected = false) as ResearchReadException
            assertEquals(code, safe.code)
            assertFalse(safe.message.orEmpty().contains("secret.example"))
            assertFalse(safe.message.orEmpty().contains("private-key"))
            assertFalse(safe.message.orEmpty().contains("private-page-text"))
            assertNull(safe.cause)
        }
    }

    @Test fun `response timeout is not mislabeled as connection failure and cancellation stays cancellation`() {
        val read = safeResearchNetworkFailure(SocketTimeoutException("private response URL"), connected = true) as ResearchReadException
        assertEquals("READ_TIMEOUT", read.code)
        assertFalse(read.message.orEmpty().contains("private response URL"))
        assertNull(read.cause)
        assertTrue(safeResearchNetworkFailure(CancellationException("stopped"), connected = true) is CancellationException)
        val policy = ResearchReadException("URL_NOT_PUBLIC", "域名解析包含非公开地址，已停止读取。")
        assertEquals("URL_NOT_PUBLIC", (safeResearchNetworkFailure(IOException("hidden request URL", policy), false) as ResearchReadException).code)
    }
}
