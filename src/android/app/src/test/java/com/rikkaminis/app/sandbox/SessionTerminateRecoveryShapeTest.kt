package com.rikkaminis.app.sandbox

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/first-send-recovery-skip-1123] Source-shape probe for the first-send
 * stall, in the same spirit as [SandboxRootfsRebuildShapeTest]: the property
 * cannot be driven from a JVM unit test (it needs a real PRoot shell and the
 * real GC), so it is pinned on the LIVE file with comments blanked out.
 *
 * The defect: `sessionDidTerminate` ran the recycle GC unconditionally. Draft
 * promotion calls it for an id that never had a shell, on the send coroutine,
 * BEFORE the user row is persisted — so two `System.gc()` rounds plus 120 ms
 * settles sat between the send tap and the bubble (device log 2026-11-23:
 * 887-981 ms to the first row, 643-714 ms of it explicit GC).
 *
 * Both halves are asserted, because a fix that skips recovery for everyone
 * would trade a stall for an OOM: the GC must be guarded by the removed shell,
 * and the per-id bookkeeping must NOT be.
 */
class SessionTerminateRecoveryShapeTest {

    private val coordinatorRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"

    private fun terminateBody(): String {
        val src = codeOnly(repoFile(coordinatorRel))
        val start = src.indexOf("fun sessionDidTerminate(sessionId: String)")
        assertTrue("sessionDidTerminate not found", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("sessionDidTerminate has no body", open >= 0)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced braces in sessionDidTerminate")
    }

    @Test
    fun `the recycle GC only runs when a shell was actually removed`() {
        val body = terminateBody()
        assertTrue(
            "sessionDidTerminate must still reclaim native heap after a real " +
                "shell teardown:\n$body",
            body.contains("postRecycleMemoryRecovery()"),
        )
        val guarded = body.contains("if (shell != null) postRecycleMemoryRecovery()")
        assertTrue(
            "the recycle GC must be guarded by the shell removed from the registry — " +
                "an id that never had a shell (draft promotion) must not run two " +
                "System.gc() rounds on the send coroutine:\n$body",
            guarded,
        )
    }

    @Test
    fun `per-id bookkeeping still runs for every terminate`() {
        val body = terminateBody()
        for (key in listOf("lastInjectedKeys", "lastActiveMs")) {
            assertTrue(
                "sessionDidTerminate must still drop $key for an id with no shell " +
                    "(T124a: the next shell for this id starts from a clean baseline):\n$body",
                body.contains("$key.remove(sessionId)"),
            )
        }
        // A conditional cleanup would leave a stale env/activity record behind.
        assertFalse(
            "per-id cleanup must not be moved behind a shell check:\n$body",
            body.contains("if (shell != null) {") &&
                body.substringAfter("if (shell != null) {").substringBefore("}")
                    .contains("lastInjectedKeys"),
        )
    }

    @Test
    fun `the shell is still stopped before recovery`() {
        val body = terminateBody()
        val stop = body.indexOf("shell?.stop()")
        val recovery = body.indexOf("postRecycleMemoryRecovery()")
        assertTrue("shell must be stopped in sessionDidTerminate:\n$body", stop >= 0)
        assertTrue("recovery call missing:\n$body", recovery >= 0)
        assertTrue(
            "recovery must run AFTER the shell is stopped (it collects what the " +
                "teardown released):\n$body",
            stop < recovery,
        )
    }

    // ── probe plumbing (mirrors SandboxRootfsRebuildShapeTest) ──────────────

    /** Blank comment lines (offsets preserved) so prose cannot satisfy a probe. */
    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
