package me.rerere.rikkahub.data.research

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** Applied both to every URL/redirect and to the exact DNS answers used by the socket client. */
internal object PublicWebPolicy {
    fun url(value: String): HttpUrl {
        if (value.length !in 1..4_096 || value.any { it.code < 32 || it.code == 127 }) reject()
        val url = value.toHttpUrlOrNull() ?: reject()
        if (url.scheme !in setOf("http", "https") || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.port != (if (url.isHttps) 443 else 80)
        ) reject()
        val host = url.host.lowercase().trimEnd('.')
        if (host.isBlank() || host == "localhost" || ('.' !in host && ':' !in host) ||
            listOf(".localhost", ".local", ".internal", ".lan", ".home", ".test", ".invalid", ".onion").any(host::endsWith)
        ) reject()
        // Literal addresses may bypass a client's DNS resolver; validate them before any request.
        if (':' in host || host.all { it.isDigit() || it == '.' }) {
            val address = runCatching { InetAddress.getByName(host) }.getOrElse { reject() }
            if (!isPublic(address)) reject()
        }
        return url.newBuilder().fragment(null).build()
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val b = address.address.map { it.toInt() and 0xff }
        if (b.size == 4) {
            return when {
                b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 -> false
                b[0] == 100 && b[1] in 64..127 -> false
                b[0] == 169 && b[1] == 254 -> false
                b[0] == 172 && b[1] in 16..31 -> false
                b[0] == 192 && b[1] == 168 -> false
                b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2) -> false
                b[0] == 192 && b[1] == 88 && b[2] == 99 -> false
                b[0] == 198 && b[1] in 18..19 -> false
                b[0] == 198 && b[1] == 51 && b[2] == 100 -> false
                b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
                else -> true
            }
        }
        if (b.size != 16 || (b[0] and 0xe0) != 0x20) return false
        // Exclude special/tunnel ranges as well as documentation addresses.
        if (b[0] == 0x20 && b[1] == 0x02) return false
        if (b[0] == 0x20 && b[1] == 0x01 && (b[2] and 0xfe) == 0) return false
        if (b.take(4) == listOf(0x20, 0x01, 0x0d, 0xb8)) return false
        if (b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0) return false
        return true
    }

    private fun reject(): Nothing = throw ResearchReadException("URL_NOT_PUBLIC", "仅支持无登录凭据、标准端口的公开 HTTP(S) 网页；内网和本机地址不可读取。")
}

internal class PublicWebDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !PublicWebPolicy.isPublic(it) }) {
            throw ResearchReadException("URL_NOT_PUBLIC", "域名解析包含非公开地址，已停止读取。")
        }
        return addresses
    }
}
