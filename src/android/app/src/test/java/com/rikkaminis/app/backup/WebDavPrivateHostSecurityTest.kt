package com.rikkaminis.app.backup

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r2-backup-1119 F-22] Regression for the cleartext WebDAV credentials
 * gate. These cases use literal-only hosts and buildUrl only: no request is
 * sent, no DNS lookup is performed, and no public endpoint is contacted.
 */
class WebDavPrivateHostSecurityTest {

    private val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            throw AssertionError("Security tests must never resolve a host")
    }).build()

    private fun dav(url: String) = WebDavClient(
        WebDavConfig(url = url, username = "literal-user", password = "literal-fake-password"),
        client,
    )

    @Test
    fun `domain names are never classified as private even with private looking prefixes`() {
        val candidates = listOf(
            "127.0.0.1.nip.io",
            "10.example.com",
            "192.168.mycloud.com",
            "172.16.example.com",
            "localhost.example.com",
        )
        for (host in candidates) {
            assertFalse("domain must not be treated as private: $host", dav("http://$host/dav").isLoopbackOrPrivateHost(host))
            try {
                dav("http://$host/dav").buildUrl()
                throw AssertionError("plain HTTP must be refused for $host")
            } catch (e: WebDavException) {
                assertTrue(
                    "must fail at the credential gate for $host: ${e.message}",
                    e.message.orEmpty().contains("Refusing to send credentials"),
                )
            }
        }
    }

    @Test
    fun `private IPv4 literals and localhost remain allowed`() {
        val allowed = listOf(
            "127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255",
            "localhost", "nas.localhost", "::1", "[::1]",
        )
        for (host in allowed) {
            assertTrue("private literal must be allowed: $host", dav("http://127.0.0.1/dav").isLoopbackOrPrivateHost(host))
            val urlHost = if (host == "::1") "[::1]" else host
            assertFalse("private HTTP must build: $host", dav("http://$urlHost/dav").buildUrl().isHttps)
        }
    }

    @Test
    fun `out of range malformed and adjacent IPv4 literals fail closed`() {
        val denied = listOf(
            "172.15.0.1", "172.32.0.1", "256.1.1.1", "10.256.1.1", "127.0.0.256",
            "192.168.1.256", "10.1.2", "10.1.2.3.4", "10..1.3", "10.1.2.-3",
            "10.1.2.+3", "10.1.2.٣", "[fd00::1]", "",
        )
        for (host in denied) {
            assertFalse("must not be treated as private: $host", dav("http://127.0.0.1/dav").isLoopbackOrPrivateHost(host))
        }
    }
}
