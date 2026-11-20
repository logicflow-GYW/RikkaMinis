package com.rikkaminis.app.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadLogRedactionTest {
    @Test
    fun signedDownloadOmitsQueryAndFragment() {
        val url = "https://cdn.example.test/report.pdf?X-Amz-Signature=test-sig" +
            "&token=test-query&access_token=test-access#test-fragment"
        val message = downloadStartLogMessage(url, "application/pdf", 42)

        for (secret in listOf("test-sig", "test-query", "test-access", "test-fragment")) {
            assertFalse("signed URL details must not reach the log", secret in message)
        }
        assertFalse("the entire query and fragment must be omitted", '?' in message || '#' in message)
        assertTrue("retain the download origin", "https://cdn.example.test/" in message)
        assertTrue("retain MIME and length", "mime=application/pdf" in message && "len=42" in message)
    }

    @Test
    fun httpDownloadOmitsUserInfoAndPath() {
        val url = "http://test-user:test-pass@cdn.example.test:8080/private/test-path"
        val message = downloadStartLogMessage(url, null, -1)

        assertFalse("userinfo must not reach the log", "test-user" in message || "test-pass" in message)
        assertFalse("the path can also carry a secret", "test-path" in message)
        assertTrue("retain the host and port", "http://cdn.example.test:8080/" in message)
    }

    @Test
    fun dataDownloadOmitsInlinePayload() {
        val message = downloadStartLogMessage("data:text/plain,test-inline", "text/plain", 11)

        assertFalse("inline data must not reach the log", "test-inline" in message)
        assertTrue("retain the scheme, MIME and length", "data: mime=text/plain len=11" in message)
    }

    @Test
    fun blobDownloadOmitsObjectUrl() {
        val message = downloadStartLogMessage("blob:https://cdn.example.test/test-object-id", null, -1)

        assertFalse("blob IDs and origins are not diagnostic metadata", "test-object-id" in message || "cdn.example.test" in message)
        assertTrue("retain the blob scheme", "blob:" in message)
    }

    @Test
    fun unsupportedOrMalformedUrlDoesNotFallBackToRawInput() {
        for (url in listOf("file:///test-private", "https://@/test-private")) {
            val message = downloadStartLogMessage(url, null, -1)
            assertFalse("unparseable or unsupported input must not reach the log", "test-private" in message)
            assertTrue("retain the unknown content length", "len=-1" in message)
        }
    }

    @Test
    fun mimeRetainsTypeWithoutParameters() {
        val message = downloadStartLogMessage("https://cdn.example.test/", "text/plain; name=test-mime", 9)

        assertFalse("MIME parameters can carry arbitrary data", "test-mime" in message)
        assertTrue("retain only the media type and subtype", "mime=text/plain len=9" in message)
    }
}
