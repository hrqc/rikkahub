package me.rerere.rikkahub.data.ai

import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestLoggingPrivacyTest {
    @Test
    fun `request logs retain metadata without reading payload or leaking credentials and errors`() {
        val secret = "private-phone-input"
        val request = Request.Builder()
            .url("https://user:$secret@example.test/chat/$secret?text=$secret")
            .header("Authorization", "Bearer $secret")
            .header("Cookie", "session=$secret")
            .post(object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun writeTo(sink: BufferedSink) { error("Logging must never read the request body") }
            })
            .build()

        val result = privateRequestLog(request, responseCode = 200, durationMs = 15, error = IllegalStateException(secret))

        assertEquals("https://example.test:443", result.url)
        assertEquals("POST", result.method)
        assertEquals(200, result.responseCode)
        assertEquals(15L, result.durationMs)
        assertEquals("IllegalStateException", result.error)
        assertNull(result.requestBody)
        assertTrue(result.requestHeaders.isEmpty())
        assertTrue(result.responseHeaders.isEmpty())
        assertFalse(result.toString().contains(secret))
    }
}
