package me.rerere.rikkahub.data.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val RESEARCH_MAX_BYTES = 1_048_576

internal data class PublicWebResponse(val status: Int, val contentType: String?, val location: String?, val bytes: ByteArray)
internal fun interface PublicWebTransport { suspend fun fetch(url: HttpUrl): PublicWebResponse }

/** Reads public documents directly; never opens a browser, runs JavaScript, or inherits API headers. */
class PublicWebReader internal constructor(
    private val transport: PublicWebTransport,
    private val now: () -> Instant = Instant::now,
) {
    constructor() : this(OkHttpPublicWebTransport())

    suspend fun read(value: String): ResearchSource {
        val requested = PublicWebPolicy.url(value)
        return withTimeoutOrNull(15_000) {
            var url = requested
            val visited = mutableSetOf<String>()
            repeat(4) { hop ->
                if (!visited.add(url.toString())) throw ResearchReadException("REDIRECT_LOOP", "网页重定向循环，已停止读取。")
                val response = transport.fetch(url)
                if (response.status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 3) throw ResearchReadException("TOO_MANY_REDIRECTS", "网页超过三次重定向，已停止读取。")
                    val next = response.location?.let(url::resolve)
                        ?: throw ResearchReadException("INVALID_REDIRECT", "网页重定向缺少有效公开链接。")
                    // Every hop repeats URL validation; the socket DNS is independently checked too.
                    val checked = PublicWebPolicy.url(next.toString())
                    if (url.isHttps && !checked.isHttps) throw ResearchReadException("INSECURE_REDIRECT", "HTTPS 页面跳转到不加密 HTTP，已停止读取。")
                    url = checked
                } else {
                    if (response.status in setOf(401, 403)) throw ResearchReadException("ACCESS_RESTRICTED", "网页拒绝公开访问，未尝试绕过登录或访问限制。")
                    if (response.status !in 200..299) throw ResearchReadException("HTTP_STATUS", "网页返回 HTTP ${response.status}，未取得可核验正文。")
                    if (response.bytes.size > RESEARCH_MAX_BYTES) throw ResearchReadException("PAGE_TOO_LARGE", "网页超过 1 MiB 读取上限，未继续下载。")
                    val mediaType = response.contentType?.toMediaTypeOrNull()
                    val mime = mediaType?.let { "${it.type}/${it.subtype}" }
                    if (mime !in setOf("text/html", "application/xhtml+xml", "text/plain")) {
                        throw ResearchReadException("UNSUPPORTED_CONTENT", "这里只读取 HTML 或纯文本；PDF、安装包和压缩包链接不会自动下载或执行。")
                    }
                    val page = withContext(Dispatchers.Default) {
                        val readContext = currentCoroutineContext()
                        ResearchPageExtractor.extract(response.bytes, checkNotNull(mime), mediaType?.charset()?.name(), url) {
                            readContext.ensureActive()
                        }
                    }
                    return@withTimeoutOrNull ResearchSource(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        requestedUrl = requested.toString(), finalUrl = url.toString(), title = page.title,
                        retrievedAt = now().toString(), pageDeclaredPublishedAt = page.declaredDate,
                        mimeType = checkNotNull(mime), text = page.text, textSha256 = researchSha256(page.text),
                        links = page.links, extractionMethod = page.method,
                        removedAdvertisingElements = page.adsRemoved, removedNoiseElements = page.noiseRemoved,
                        truncated = page.truncated, limitations = page.limitations,
                    )
                }
            }
            throw ResearchReadException("TOO_MANY_REDIRECTS", "网页重定向次数超限。")
        } ?: throw ResearchReadException("READ_TIMEOUT", "公开网页读取超过 15 秒，已取消请求；未取得可核验正文。")
    }
}

private class OkHttpPublicWebTransport : PublicWebTransport {
    private val client = OkHttpClient.Builder()
        .dns(PublicWebDns())
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(url: HttpUrl): PublicWebResponse = suspendCancellableCoroutine { continuation ->
        // Classify timeout by the actual connection lifecycle, never by parsing exception text.
        val connected = AtomicBoolean(false)
        val requestClient = client.newBuilder().eventListener(object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) { connected.set(true) }
        }).build()
        val call = requestClient.newCall(Request.Builder().url(url)
            .header("User-Agent", "MobileAgentV1-PublicResearch/1.0")
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
            .get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(safeFailure(e, connected.get()))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = it.body
                        val type = it.header("Content-Type")?.toMediaTypeOrNull()?.let { media -> "${media.type}/${media.subtype}" }
                        val readable = it.code in 200..299 && type in setOf("text/html", "application/xhtml+xml", "text/plain")
                        if (readable && body.contentLength() > RESEARCH_MAX_BYTES) throw ResearchReadException("PAGE_TOO_LARGE", "网页超过 1 MiB 读取上限，已停止读取。")
                        val bytes = if (readable) body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            while (true) {
                                if (!continuation.isActive) throw CancellationException("Research request cancelled")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > RESEARCH_MAX_BYTES) {
                                    throw ResearchReadException("PAGE_TOO_LARGE", "解压后的网页超过 1 MiB 读取上限，已停止读取。")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } else ByteArray(0)
                        PublicWebResponse(it.code, it.header("Content-Type"), it.header("Location"), bytes)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(safeFailure(error, connected = true))
                }
            }
        })
    }

    private fun safeFailure(error: Exception, connected: Boolean): Exception = safeResearchNetworkFailure(error, connected)
}

/** Fixed diagnostics only: neither exception text/causes nor request URLs become tool output. */
internal fun safeResearchNetworkFailure(error: Exception, connected: Boolean): Exception {
    if (error is CancellationException) return error
    val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    chain.filterIsInstance<ResearchReadException>().firstOrNull()?.let { return it }
    val (code, detail) = when {
        chain.any { it is UnknownHostException } -> "DNS_ERROR" to "公开网页域名解析失败，未取得正文。"
        chain.any { it is SSLException } -> "TLS_FAILED" to "公开网页的 TLS 连接或证书验证失败，未降低安全要求。"
        chain.any { it is SocketTimeoutException } -> if (connected) {
            "READ_TIMEOUT" to "连接已建立，但等待或读取网页响应超时，未取得可核验正文。"
        } else {
            "CONNECT_TIMEOUT" to "公开网页连接阶段超时，未取得正文。"
        }
        chain.any { it is ConnectException } -> "CONNECT_FAILED" to "无法建立公开网页连接，未取得正文。"
        else -> "FETCH_FAILED" to "公开网页请求失败，未取得可核验正文。"
    }
    return ResearchReadException(code, detail)
}
