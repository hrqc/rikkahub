package me.rerere.rikkahub.data.research

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PublicWebPolicyTest {
    @Test fun `only public standard HTTP URLs without credentials are accepted`() {
        assertEquals("https://example.com/docs?q=android", PublicWebPolicy.url("https://example.com/docs?q=android#part").toString())
        listOf("file:///etc/passwd", "http://localhost/", "http://localhost./", "http://router.local/",
            "https://user:secret@example.com/", "https://example.com:8443/", "https://example.com/\nHeader:x",
            "http://127.0.0.1/", "http://2130706433/", "http://[::1]/", "http://[fc00::1]/").forEach {
            assertTrue(it, runCatching { PublicWebPolicy.url(it) }.exceptionOrNull() is ResearchReadException)
        }
    }

    @Test fun `address policy excludes private shared local mapped and special ranges`() {
        listOf("0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.2", "169.254.169.254", "172.16.0.1",
            "192.168.1.1", "192.0.2.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::",
            "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "64:ff9b::a00:1", "2002:a00:1::",
            "2001:db8::1").forEach { assertFalse(it, PublicWebPolicy.isPublic(InetAddress.getByName(it))) }
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888").forEach {
            assertTrue(it, PublicWebPolicy.isPublic(InetAddress.getByName(it)))
        }
    }

    @Test fun `mixed public and private DNS answers are rejected as a whole`() {
        val dns = PublicWebDns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("10.0.0.1"))
        })
        val error = runCatching { dns.lookup("example.com") }.exceptionOrNull()
        assertEquals("URL_NOT_PUBLIC", (error as ResearchReadException).code)
    }

    @Test fun `every socket DNS lookup rechecks a hostname after rebinding`() {
        var calls = 0
        val dns = PublicWebDns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName(if (++calls == 1) "1.1.1.1" else "127.0.0.1"))
        })
        assertEquals("1.1.1.1", dns.lookup("example.com").single().hostAddress)
        assertTrue(runCatching { dns.lookup("example.com") }.exceptionOrNull() is ResearchReadException)
        assertEquals(2, calls)
    }
}
