package com.rikkaminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [WAVE-1118 R3-F02] The HOST half of the temp sweep (`PROOT_TMP_DIR`).
 *
 * The guest half has been age-gated since `[P2-guest-tmp-reclaim]`; the host
 * half reaped on sight, gated only by "no agent shell is mid-command". That gate
 * cannot see the other two consumers of `PROOT_TMP_DIR` — an interactive
 * `TerminalSession` and `RootfsManager`'s apk-repair subprocess — and no age gate
 * meant a file proot had just written was gone on the next 60 s tick. With
 * `PROOT_LOADER` unset (the embedded-loader fallback) that file is the ELF loader
 * proot is about to exec.
 *
 * Both halves of the reap decision are pinned here because both fail SILENTLY in
 * production — the directory just looks empty either way.
 */
class ProotTmpHostSweepTest {

    private val now = 10_000_000L

    // ── 1. the age gate ─────────────────────────────────────────────────

    @Test
    fun `a fresh entry survives even though nothing reports it busy`() {
        assertFalse(
            "a 5 s old entry is inside proot's launch window — reaping it is the defect",
            internalProotTmpEntryReapable(mtimeMs = now - 5_000L, nowMs = now, stillInUse = false),
        )
    }

    @Test
    fun `an aged and idle entry is reaped`() {
        assertTrue(
            internalProotTmpEntryReapable(
                mtimeMs = now - PROOT_TMP_HOST_MAX_AGE_MS - 1L,
                nowMs = now,
                stillInUse = false,
            ),
        )
    }

    @Test
    fun `the gate boundary matches the guest sweeper's`() {
        // Exactly maxAge is aged (see internalGuestTmpIsAged): the two sweeps must
        // not disagree about the same timestamp, or "which one deleted it" stops
        // being answerable.
        assertTrue(
            internalProotTmpEntryReapable(now - PROOT_TMP_HOST_MAX_AGE_MS, now, stillInUse = false),
        )
        assertFalse(
            internalProotTmpEntryReapable(now - PROOT_TMP_HOST_MAX_AGE_MS + 1L, now, stillInUse = false),
        )
    }

    @Test
    fun `an aged entry with a live write inside is not reaped`() {
        // The guest-side activity probe's answer must be honoured here too: an old
        // directory whose child was written a second ago is NOT junk.
        assertFalse(internalProotTmpEntryReapable(now - 3_600_000L, now, stillInUse = true))
    }

    @Test
    fun `an unknown mtime is treated as aged — same direction as the guest side`() {
        assertTrue(internalProotTmpEntryReapable(mtimeMs = 0L, nowMs = now, stillInUse = false))
    }

    // ── 2. wiring ────────────────────────────────────────────────────────

    @Test
    fun `cleanupProotTmp consults the gate before deleting`() {
        val code = stripComments(
            readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"),
        )
        val gateAt = code.indexOf("internalProotTmpEntryReapable(")
        assertTrue("the host sweep must consult the gate", gateAt > 0)
        // The unconditional `deleteRecursively()` must be gone from that loop, and
        // the no-follow deleter must be what runs instead.
        assertFalse(
            "the host sweep must not walk symlinks with deleteRecursively()",
            code.contains("tmpDir.listFiles()?.forEach { f ->\n                if (f.deleteRecursively()) removed++"),
        )
        assertTrue(
            "deletion must go through the no-follow tree deleter",
            code.indexOf("internalGuestTmpDeleteTreeNoFollow(path)") > gateAt,
        )
        assertTrue(
            "the busy-gate comment must still be there for agent shells",
            code.contains("if (shells.values.any { it.isBusy }) return"),
        )
    }

    @Test
    fun `a guest temp entry that cannot be deleted is logged, not swallowed`() {
        val code = stripComments(
            readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"),
        )
        assertTrue(
            "the descend-or-fail branch must say something",
            code.contains("sweepGuestTmpDir: cannot remove"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, "src/android/app").isDirectory) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readRepoFile(relative: String): String = File(repoRoot(), "src/android/$relative").readText()

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i += 1 }
                inString -> {
                    sb.append(c)
                    if (c == '\\') { sb.append(next); i += 1 } else if (c == '"') inString = false
                }
                else -> when {
                    c == '"' -> { inString = true; sb.append(c) }
                    c == '/' && next == '/' -> { inLine = true; i += 1 }
                    c == '/' && next == '*' -> { inBlock = true; i += 1 }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }
}
