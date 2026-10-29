package com.rikkaminis.app.sandbox

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit3 F-08] The session-vs-global path decision, pinned on the JVM.
 *
 * `PRootKernel.resolveSessionHostPath` / `resolveHostPath` need a real
 * `filesDir` (Context), so the decision itself lives in the pure
 * [PRootKernel.sessionScopedResolve], which these tests drive directly, plus
 * source-shape assertions that the production wiring and the eight migrated
 * call sites still go through it.
 *
 * The arm that matters is the third one: a null from the *session* resolver
 * means "rejected or absent" and must NOT be re-resolved by the global
 * resolver. That elvis fallback is exactly the cross-session hole F-08
 * reports — the containment check defeated by its own caller.
 *
 * Expectations are literals (plus call counters); nothing is derived from a
 * production constant, so a changed constant shows up as a red test rather
 * than a silently adjusted expectation.
 *
 * Composite argument with [com.rikkaminis.app.sandbox.offload.SessionScopedPathGuardTest]:
 * that test pins "an escape out of the session tree yields null"
 * (`safeResolveWithin`), this one pins "such a null is not re-resolved
 * globally". Both halves are needed to close F-08.
 */
class SessionPathResolutionTest {

    private val sessionId = "371e1802-0254-4ca1-9b90-0bb4cdc74656"

    // ── arm 1: no session in scope → global resolver ────────────────────

    @Test
    fun `no session uses the global resolver`() {
        var sessionCalls = 0
        val resolved = PRootKernel.sessionScopedResolve(
            sessionId = null,
            linuxPath = "/var/minis/workspace/a.md",
            resolveGlobal = { "global:$it" },
            resolveSession = { _, _ -> sessionCalls++; "session" },
        )
        assertEquals("global:/var/minis/workspace/a.md", resolved)
        assertEquals(0, sessionCalls)
    }

    /** Empty-string sessionId is *not* "no session" — it must not be coerced. */
    @Test
    fun `empty session id still takes the session branch`() {
        var globalCalls = 0
        val resolved = PRootKernel.sessionScopedResolve(
            sessionId = "",
            linuxPath = "/var/minis/workspace/a.md",
            resolveGlobal = { globalCalls++; "global:$it" },
            resolveSession = { sid, path -> "session[$sid]:$path" },
        )
        assertEquals("session[]:/var/minis/workspace/a.md", resolved)
        assertEquals(0, globalCalls)
    }

    // ── arm 2: session in scope, session resolver hits ──────────────────

    @Test
    fun `session hit wins and the global resolver is not consulted`() {
        var globalCalls = 0
        val resolved = PRootKernel.sessionScopedResolve(
            sessionId = sessionId,
            linuxPath = "/var/minis/workspace/a.md",
            resolveGlobal = { globalCalls++; "global:$it" },
            resolveSession = { sid, path -> "session:$sid:$path" },
        )
        assertEquals("session:$sessionId:/var/minis/workspace/a.md", resolved)
        assertEquals(0, globalCalls)
    }

    // ── arm 3 (the regression): a session reject must not fall back ─────

    @Test
    fun `session reject returns null and never falls back to the global resolver`() {
        var globalCalls = 0
        val escaped = "/var/minis/workspace/../../../debug_server_token"
        val resolved = PRootKernel.sessionScopedResolve(
            sessionId = sessionId,
            linuxPath = escaped,
            resolveGlobal = { globalCalls++; "global:$it" },
            resolveSession = { _, _ -> null },
        )
        assertNull(resolved)
        assertEquals(
            "the global resolver was consulted for a path the session resolver rejected",
            0,
            globalCalls,
        )
    }

    @Test
    fun `session reject with File-typed resolvers stays null`() {
        // Mirrors the production wiring: PRootKernel.resolvePathForSession passes
        // File?-typed lambdas. The session arm stands in for safeResolveWithin's
        // rejection; the global arm stands in for the every-session-tree search
        // that used to answer instead (possibly another session's file).
        var globalCalls = 0
        val resolved: File? = PRootKernel.sessionScopedResolve(
            sessionId = sessionId,
            linuxPath = "/var/minis/workspace/../../shared_prefs/offload_permissions.xml",
            resolveGlobal = { globalCalls++; File("/global-root$it") },
            resolveSession = { _, _ -> null },
        )
        assertNull(resolved)
        assertEquals(0, globalCalls)
    }

    // ── production wiring (source shape) ────────────────────────────────

    @Test
    fun `resolvePathForSession delegates to the pure decision core`() {
        val body = declarationSlice(readRepoFile(PRootKernelPath), "fun resolvePathForSession(")
        assertTrue(
            "resolvePathForSession no longer delegates to sessionScopedResolve:\n$body",
            body.contains("sessionScopedResolve("),
        )
        assertTrue(
            "resolvePathForSession must keep the no-session → resolveHostPath arm",
            body.contains("resolveGlobal = { resolveHostPath(it) }"),
        )
        assertTrue(
            "resolvePathForSession must pin the session arm to resolveSessionHostPath",
            body.contains("resolveSession = { sid, path -> resolveSessionHostPath(sid, path, context) }"),
        )
        // The pure core is a block-bodied if/else — the elvis form is the exact
        // mutation that reopens F-08, so it is asserted away here as well as by
        // the behavioural arms above.
        val core = declarationSlice(readRepoFile(PRootKernelPath), "fun <T> sessionScopedResolve(")
        assertTrue("pure core lost its explicit null-session branch:\n$core", core.contains("if (sessionId == null)"))
        assertTrue("pure core lost the session branch:\n$core", core.contains("resolveSession(sessionId, linuxPath)"))
        assertFalse("pure core must not fall back through an elvis:\n$core", core.contains("?:"))
    }

    // ── the migrated call sites (source shape) ─────────────────────────

    @Test
    fun `no migrated site keeps the elvis fallback to the global resolver`() {
        var migrated = 0
        for ((relative, expected) in migratedSites) {
            val src = readRepoFile(relative)
            val offenders = src.lines().filter { it.contains("?: PRootKernel.resolveHostPath") }
            assertTrue(
                "$relative still contains the F-08 elvis fallback: $offenders",
                offenders.isEmpty(),
            )
            val calls = src.lines().count { it.contains("resolvePathForSession(") }
            assertEquals("$relative migrated call sites", expected, calls)
            migrated += calls
        }
        // [R4-V F-11] 9, not 8: FileBrowserViewModel gained the second site above.
        assertEquals("total migrated call sites on this branch", 9, migrated)
    }

    /**
     * The fallback shape may only survive where the caller has *no* session in
     * scope (e.g. `ModelUseOffloadHandler`, handed to FIX-C on this batch). Any
     * other file that both resolves session-scoped paths and falls back must be
     * migrated — otherwise the F-08 hole reopens at the next site someone adds.
     */
    @Test
    fun `other session-scoped resolvers keep no global elvis fallback`() {
        val referenceSites = listOf(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt",
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatLinkResolver.kt",
            "src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppPathResolver.kt",
        )
        for (relative in referenceSites) {
            val src = readRepoFile(relative)
            val offenders = src.lines().filter { it.contains("?: PRootKernel.resolveHostPath") }
            assertTrue("$relative keeps the F-08 elvis fallback: $offenders", offenders.isEmpty())
        }
    }

    private fun readRepoFile(relative: String): String {
        // Gradle (CI) sets user.dir to the module dir; a sandbox JVM run may
        // start anywhere under the repo — walk up instead of assuming a depth.
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * Source lines of the declaration at [marker], up to the next top-level
     * member (next KDoc / annotation / function). Works for expression-bodied
     * declarations, where naive brace counting would stop at the first lambda.
     */
    private fun declarationSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") ||
                t.startsWith("    fun ") || t.startsWith("    internal fun ") ||
                t.startsWith("    private fun ") || t.startsWith("    public fun ") ||
                t.startsWith("    internal val ") || t.startsWith("    private val ")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        private const val PRootKernelPath =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/PRootKernel.kt"

        /** file → migrated call sites on this branch (FIX-F: 8 across 6 files). */
        private val migratedSites = listOf(
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt" to 2,
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/BrowserUseOffloadHandler.kt" to 2,
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ConfigOffloadHandler.kt" to 1,
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SpeechOffloadHandler.kt" to 1,
            "src/android/app/src/main/java/com/rikkaminis/app/tools/ReadImageTool.kt" to 1,
            // Textual shape the audit's grep for `?: PRootKernel.resolveHostPath`
            // missed (`?.let { return it }` fall-through, same defect).
            // [R4-V F-11] 2, not 1: resolving a symlink's absolute target is a
            // second, legitimately session-scoped site (list entry vs link
            // target). Neither falls back through the elvis — the assertion
            // above still guards that.
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FileBrowserViewModel.kt" to 2,
        )
    }
}
