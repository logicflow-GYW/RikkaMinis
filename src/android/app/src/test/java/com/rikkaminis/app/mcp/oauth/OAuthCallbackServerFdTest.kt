package com.rikkaminis.app.mcp.oauth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [da1018-FIX-G] ISSUE-11 behavioural half: the OAuth callback listener against
 * a REAL socket, exercising the accept loop end to end.
 *
 *  - `callback round trip decodes the code exactly once` pushes an encoded
 *    "abc%2Bdef" through the wire and asserts the code the server hands to
 *    onCode is "abc+def" — the double-decode path yielded "abc def".
 *  - `a peer that connects and sends nothing leaks no descriptor` measures
 *    /proc/self/fd socket descriptors: N connections that connect and close
 *    without sending a byte used to be `readLine() ?: continue`-ed past
 *    socket.close() and leaked one FD each.
 *
 * The FD case is a measured observable (descriptor count), not a structural
 * assertion; a sentinel request after the ghosts is the causal barrier that
 * the single-threaded accept loop really drained them (accept order is FIFO).
 * If /proc/self/fd is not readable the FD case is skipped, not failed.
 */
class OAuthCallbackServerFdTest {

    private var server: OAuthCallbackServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `callback round trip decodes the code exactly once`() {
        val port = freePort()
        val captured = AtomicReference<Pair<String, String?>>()
        val latch = CountDownLatch(1)
        val srv = OAuthCallbackServer(port) { code, state ->
            captured.set(code to state)
            latch.countDown()
        }
        server = srv
        srv.start()

        val body = request(port, "GET /callback?code=abc%2Bdef&state=s1 HTTP/1.1")
        assertTrue("server sent the completion page", body.contains("Authorization complete"))
        assertTrue("onCode fired", latch.await(5, TimeUnit.SECONDS))
        assertEquals("abc+def", captured.get()!!.first)
        assertEquals("s1", captured.get()!!.second)
    }

    @Test
    fun `a peer that connects and sends nothing leaks no descriptor`() {
        requireProcFd()

        val port = freePort()
        val srv = OAuthCallbackServer(port) { _, _ -> }
        server = srv
        srv.start()

        // Barrier: a real request proves the listener is up and processed.
        request(port, "GET /favicon.ico HTTP/1.1")
        val baseline = awaitStableSocketFds()

        // 8 connections that connect and immediately close without sending a
        // byte: readLine() returns null, the loop `continue`s past close() in
        // the old code (one leaked FD each).
        repeat(GHOST_CONNECTIONS) { ghost(port) }

        // Barrier: this request is handled only after the single-threaded
        // accept loop has drained the ghosts (FIFO accept order).
        request(port, "GET /favicon.ico HTTP/1.1")
        val after = awaitStableSocketFds()

        assertTrue(
            "socket FDs grew after $GHOST_CONNECTIONS dead connections: baseline=$baseline after=$after",
            after <= baseline + 1,
        )
    }

    // ── [r3-B11-F08] a refused authorization must end the wait ─────────────

    /**
     * RFC 6749 §4.1.2.1: a denial redirects to the callback URI with
     * `error=...` and no `code`. The listener used to answer "Authorization
     * complete", fire no callback and stay up, so the in-flight
     * `suspendCancellableCoroutine` hung for its whole 5-minute timeout with no
     * way back.
     */
    @Test
    fun `a denial redirect cancels the wait instead of hanging`() {
        val port = freePort()
        val codeCalls = CountDownLatch(1)
        val cancelCalls = CountDownLatch(1)
        val srv = OAuthCallbackServer(port) { _, _ -> codeCalls.countDown() }
        server = srv
        srv.onExternalCancel = { cancelCalls.countDown() }
        srv.start()

        val response = request(port, "GET /callback?error=access_denied&state=s1 HTTP/1.1")
        assertTrue(
            "the browser must be told the authorization was not completed",
            response.contains("Authorization was not completed"),
        )
        assertTrue("the caller's wait must be resumed (cancel), not left hanging",
            cancelCalls.await(5, TimeUnit.SECONDS))
        assertEquals("no code exists, so onCode must not fire", 1L, codeCalls.count)
        // The listener must be closed, not left accepting another 5 minutes of
        // connections.
        var refused = false
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { }
        } catch (_: Exception) {
            refused = true
        }
        assertTrue("the callback server must stop after a denial", refused)
    }

    @Test
    fun `a denial error text is html-escaped in the page`() {
        val port = freePort()
        val srv = OAuthCallbackServer(port) { _, _ -> }
        server = srv
        srv.start()

        val response = request(
            port,
            "GET /callback?error=%3Cscript%3Ealert(1)%3C%2Fscript%3E&state=s1 HTTP/1.1",
        )
        assertTrue("the error is echoed", response.contains("The authorization server reported"))
        assertFalse(
            "the echoed error must not inject markup: $response",
            response.contains("<script>alert(1)"),
        )
        assertTrue("it must appear escaped", response.contains("&lt;script&gt;alert(1)"))
    }

    // ── [FIX-FW-OAUTH-D1/D2] present-but-empty error, non-ASCII reason ──────

    /**
     * [FIX-FW-OAUTH-D2] `?error=` (present, empty) used to render the refusal
     * page and then fall through BOTH exits: the predicate was
     * `!error.isNullOrBlank()`, so `denied` was false, and `code` was null, so
     * the success branch was skipped too. Nothing called stop(), so the
     * caller's suspendCancellableCoroutine sat out its whole 5-minute timeout
     * while the browser had already been told the authorization failed.
     */
    @Test
    fun `an empty error param terminates the wait instead of hanging`() {
        val port = freePort()
        val codeCalls = CountDownLatch(1)
        val cancelCalls = CountDownLatch(1)
        val srv = OAuthCallbackServer(port) { _, _ -> codeCalls.countDown() }
        server = srv
        srv.onExternalCancel = { cancelCalls.countDown() }
        srv.start()

        val response = request(port, "GET /callback?error=&error_description= HTTP/1.1")
        assertTrue(
            "the browser must see the refusal page",
            response.contains("Authorization was not completed"),
        )
        assertTrue(
            "an empty reason must not render a sentence with a hole in it",
            response.contains("gave no reason"),
        )
        assertFalse("no dangling 'reported: .'", response.contains("reported:"))
        assertTrue(
            "the caller's wait must resume on error= too",
            cancelCalls.await(5, TimeUnit.SECONDS),
        )
        assertEquals("no code exists, so onCode must not fire", 1L, codeCalls.count)
        var refused = false
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { }
        } catch (_: Exception) {
            refused = true
        }
        assertTrue("the callback server must stop on an empty error too", refused)
    }

    /**
     * [FIX-FW-OAUTH-D1] A non-ASCII `error` (a Chinese refusal text here) makes
     * `html.length` (UTF-16 code units) smaller than the UTF-8 bytes actually
     * written: the browser truncates at the declared length, and the missing
     * `charset` mojibakes what survives. Both halves are asserted on the wire.
     */
    @Test
    fun `a non-ascii error is served complete and declared in bytes`() {
        val port = freePort()
        val srv = OAuthCallbackServer(port) { _, _ -> }
        server = srv
        srv.start()

        val raw = rawRequest(port, "GET /callback?error=%E6%8B%92%E7%BB%9D&state=s1 HTTP/1.1")
        val text = String(raw, Charsets.UTF_8)
        val declared = Regex("Content-Length: (\\d+)").find(text)!!.groupValues[1].toInt()
        // Hand-counted: the page is 193 chars / 197 UTF-8 bytes ("拒绝" = 6 bytes).
        assertEquals("declared length must be the BYTE count", 197, declared)
        assertTrue("the charset must be declared", text.contains("Content-Type: text/html; charset=utf-8"))
        assertTrue("the reason survives the wire in utf-8", text.contains("拒绝"))
        val body = text.substringAfter("\r\n\r\n")
        assertEquals(
            "the bytes actually written must equal the declaration",
            declared,
            body.toByteArray(Charsets.UTF_8).size,
        )
    }

    private companion object {
        const val GHOST_CONNECTIONS = 8

        fun freePort(): Int =
            ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        fun requireProcFd() {
            val d = File("/proc/self/fd")
            Assume.assumeTrue(
                "/proc/self/fd readable",
                d.isDirectory && d.canRead() && !d.list().isNullOrEmpty(),
            )
        }

        /** Counts open socket descriptors in this JVM (/proc/self/fd symlinks). */
        fun socketFdCount(): Int {
            val dir = File("/proc/self/fd")
            return dir.listFiles()?.count { f ->
                val target = try {
                    Files.readSymbolicLink(f.toPath()).toString()
                } catch (_: Exception) {
                    ""
                }
                target.startsWith("socket:")
            } ?: -1
        }

        /** Returns the descriptor count once two consecutive reads agree. */
        fun awaitStableSocketFds(timeoutMs: Long = 8_000): Int {
            var last = Int.MIN_VALUE
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val c = socketFdCount()
                if (c == last) return c
                last = c
                Thread.sleep(150)
            }
            return last
        }

        fun awaitConnect(port: Int, timeoutMs: Long = 5_000): Socket {
            val deadline = System.currentTimeMillis() + timeoutMs
            var last: Exception? = null
            while (System.currentTimeMillis() < deadline) {
                try {
                    return Socket(InetAddress.getLoopbackAddress(), port)
                } catch (e: Exception) {
                    last = e
                    Thread.sleep(50)
                }
            }
            throw AssertionError("server never accepted on port $port", last)
        }

        fun request(port: Int, requestLine: String): String =
            String(rawRequest(port, requestLine), Charsets.UTF_8)

        /** Same as [request] but keeps the raw bytes (Content-Length checks). */
        fun rawRequest(port: Int, requestLine: String): ByteArray =
            awaitConnect(port).use { s ->
                s.getOutputStream().write("$requestLine\r\nHost: localhost\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                s.getInputStream().readBytes()
            }

        /** Connects and closes without sending anything. */
        fun ghost(port: Int) {
            awaitConnect(port).use { }
        }
    }
}
