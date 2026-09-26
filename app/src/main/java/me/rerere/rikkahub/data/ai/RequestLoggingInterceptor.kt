package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Request

/** Content can include screen text and credentials even after a phone session has stopped. */
internal fun privateRequestLog(
    request: Request,
    responseCode: Int? = null,
    durationMs: Long? = null,
    error: Throwable? = null,
) = LogEntry.RequestLog(
    tag = "HTTP",
    url = "${request.url.scheme}://${request.url.host}:${request.url.port}",
    method = request.method,
    requestHeaders = emptyMap(),
    requestBody = null,
    responseCode = responseCode,
    responseHeaders = emptyMap(),
    durationMs = durationMs,
    error = error?.javaClass?.simpleName,
)

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val response: Response

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            Logging.logRequest(privateRequestLog(request, error = e))
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        Logging.logRequest(privateRequestLog(request, responseCode = response.code, durationMs = durationMs))

        return response
    }

}
