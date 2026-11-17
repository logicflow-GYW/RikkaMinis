package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [WAVE-1118 D3-F01] External mounts the agent shell could not see.
 *
 * `ExecutionCoordinator.buildSessionBindMounts` built PRoot's `-b` argv from
 * `Entry.resolvedHostPath`, a snapshot written only by `MountedFoldersStore.add()`.
 * `applyMountedFoldersSnapshot` resolves the same tree URIs on demand into
 * `PRootKernel.bindMounts`, so the two views could disagree: an entry whose
 * persisted JSON predates the cache field loads with `resolvedHostPath = null`
 * (nothing backfills it), and the agent's `shell_execute` then saw the empty
 * rootfs placeholder under `/var/minis/mounts/<name>/` while the interactive
 * terminal, `minis-fastio` and `file_read` — all of which read
 * `PRootKernel.bindMounts` / resolve on demand — saw the real files. The agent
 * reports "the folder is empty"; nothing anywhere logs an error.
 *
 * The merge rule is a pure function so the additive/never-repoint contract is
 * pinned here rather than implied by a loop body.
 */
class ExternalMountFallbackTest {

    private val prefix = "/var/minis/mounts/"

    // ── 1. the merge rule ────────────────────────────────────────────────

    @Test
    fun `a cold-cache entry is fed from the resolved snapshot`() {
        val explicit = linkedMapOf("/var/minis/workspace" to "/files/s1/workspace")
        val global = mapOf(
            "/var/minis/workspace" to "/files/s1/workspace",
            "${prefix}vault" to "/storage/emulated/0/Documents/Vault",
        )
        assertEquals(
            mapOf("${prefix}vault" to "/storage/emulated/0/Documents/Vault"),
            internalExternalMountFallbacks(explicit, global),
        )
    }

    @Test
    fun `the store's own resolution always wins`() {
        // Both views know the name but resolve it differently (e.g. a volume that
        // moved): the store's value is authoritative, the snapshot must not
        // repoint a live mount.
        val explicit = mapOf("${prefix}vault" to "/storage/1A2B/Vault")
        val global = mapOf("${prefix}vault" to "/mnt/media_rw/1A2B/Vault")
        assertTrue(internalExternalMountFallbacks(explicit, global).isEmpty())
    }

    @Test
    fun `non-mount keys are ignored`() {
        // /var/minis/{shared,logs,skills,memory,mcp-servers} live in the same
        // global map; copying them through this path would silently widen what
        // the shell can see to whatever else PRootKernel happens to hold.
        val explicit = mapOf("/var/minis/shared" to "/files/minis-global/shared")
        val global = mapOf(
            "/var/minis/shared" to "/files/minis-global/shared",
            "/var/minis/logs" to "/files/logs",
            "/var/minis/skills" to "/files/minis-global/skills",
        )
        assertTrue(internalExternalMountFallbacks(explicit, global).isEmpty())
    }

    @Test
    fun `blank host paths are not turned into binds`() {
        val global = mapOf("${prefix}vault" to "")
        assertTrue(internalExternalMountFallbacks(emptyMap(), global).isEmpty())
    }

    // ── 2. wiring ────────────────────────────────────────────────────────

    @Test
    fun `buildSessionBindMounts consumes the fallback merge`() {
        val code = stripComments(
            readRepoFile(
                "app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt",
            ),
        )
        assertTrue(
            "the shell's bind map must merge what PRootKernel already resolved",
            code.contains("internalExternalMountFallbacks(mounts, PRootKernel.bindMounts)"),
        )
        // The store path must STILL be there (this is additive, not a swap).
        assertTrue(
            "the store's resolved path is still the primary source",
            code.contains("val host = entry.resolvedHostPath ?: return@forEach"),
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
