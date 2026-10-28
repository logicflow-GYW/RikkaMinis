package com.rikkaminis.app.sandbox

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta1028 D3] Source-shape probes for the three sandbox fixes that cannot be
 * driven from a JVM unit test without an Android `Context` + a real rootfs:
 *
 *  - **F-01** a rebuild must land the offline trio. `/bin/bash` is not in the
 *    factory tar (verified against the bundled asset: 521 entries, no
 *    `bin/bash` / `libreadline` / `libncurses`) and [excludeOfflinePackages]
 *    filters it out of the snapshot restore on the stated assumption that the
 *    offline installer runs "on every rebuild". The assumption was only true on
 *    autoRepair's Stage 2.6, so every `reset()` — manual or Stage 3 — came back
 *    without bash.
 *  - **F-02** a rebuild must be *announced*. `BootOnceGate` had no reset edge,
 *    so after a mid-session reset `isBooted` stayed true and nothing re-ran
 *    stub installation / mount materialization.
 *  - **F-04** `stopCurrentCommand` must not delete the per-session `Mutex`
 *    while a command holds it.
 *
 * Each arm asserts a property whose negation is the defect, on the LIVE file,
 * with the comment lines blanked out — the prose in these files discusses the
 * very shapes being pinned, so an unstripped read would let a comment satisfy a
 * probe (that is how `[fix/audit-s4l2]`'s own justification survived the
 * retraction of its premise).
 */
class SandboxRootfsRebuildShapeTest {

    private val rootfsRel = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/RootfsManager.kt"
    private val kernelRel = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/PRootKernel.kt"
    private val coordinatorRel = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"
    private val terminalRel = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/TerminalSession.kt"

    // ── F-01 ────────────────────────────────────────────────────────────────

    @Test
    fun `the rebuild installs the offline trio before restoring the snapshot`() {
        val install = sliceFrom(repoFile(rootfsRel), "suspend fun installIfNeeded", "val nativeLibDir")
        assertTrue("installIfNeeded must call the offline installer:\n…", install.contains("installOfflinePackages()"))
        assertTrue(
            "the trio must be in place BEFORE the user snapshot is restored, so a package " +
                "depending on bash resolves in the same pass",
            install.indexOf("installOfflinePackages()") < install.indexOf("restoreApkWorld()"),
        )
        // and its failure must be visible, not swallowed
        assertTrue(
            "a failed offline install must be logged:\n$install",
            install.contains("offline package install failed"),
        )
    }

    @Test
    fun `the offline installer is reachable from the rebuild path, not only from repair`() {
        val src = repoFile(rootfsRel)
        val sites = src.lines()
            .filter { it.contains("installOfflinePackages()") }
            .map { it.trim() }
        // declaration (`private suspend fun installOfflinePackages(): Boolean = …`)
        // is not a call: count only call-shaped lines.
        val calls = sites.filter { !it.startsWith("private suspend fun") }
        assertTrue(
            "installOfflinePackages() must be called from BOTH the rebuild choke point and " +
                "autoRepair Stage 2.6 — one call site is what lost /bin/bash: $sites",
            calls.size >= 2,
        )
    }

    // ── F-02 ────────────────────────────────────────────────────────────────

    @Test
    fun `reset announces the rebuild after the re-install has landed`() {
        val src = repoFile(rootfsRel)
        val reset = sliceFrom(src, "suspend fun reset(): Unit", "getRootfsSize")
        assertTrue("reset must still delete + reinstall:\n$reset", reset.contains("installIfNeeded()"))
        val hook = reset.indexOf("afterRootfsReinstalled?.invoke()")
        assertTrue("reset must publish the rebuild:\n$reset", hook > 0)
        assertTrue(
            "the publish must come AFTER installIfNeeded(), or a hook that re-boots would " +
                "race the extraction",
            hook > reset.indexOf("installIfNeeded()"),
        )
        assertTrue(
            "a hook failure must not fail the reset the user asked for:\n$reset",
            reset.contains("runCatching { afterRootfsReinstalled?.invoke() }"),
        )
    }

    @Test
    fun `the consumers of a rebuilt rootfs notice it`() {
        // Re-arming the gate is only half of it: whoever holds a shell spawned
        // into the deleted tree has to notice before reusing it.
        val coordinator = repoFile(coordinatorRel)
        val branch = sliceFrom(coordinator, "if (!PRootKernel.isBooted) {", "PRootKernel.boot(appContext)")
        assertTrue(
            "execute() must drop shells from the pre-rebuild tree before auto-boot:\n$branch",
            branch.contains("dropShellsFromRebuiltRootfs()"),
        )
        val helper = sliceFrom(coordinator, "private fun dropShellsFromRebuiltRootfs()", "fun stopCurrentCommand")
        assertTrue("the helper must stop each shell:\n$helper", helper.contains("shell.stop()"))
        assertTrue("and clear the map:\n$helper", helper.contains("shells.clear()"))
        assertFalse(
            "and must NOT touch the mutex map (see the s4h4 contract):\n$helper",
            codeOnly(helper).contains("mutexes."),
        )

        val terminal = repoFile(terminalRel)
        val start = sliceFrom(terminal, "fun start(sessionId: String? = null", "val myGeneration")
        assertTrue(
            "a RUNNING PTY under a re-armed gate must be torn down, not kept:\n$start",
            start.contains("_state.value == State.RUNNING && !PRootKernel.isBooted"),
        )
        assertTrue("…by stopping it first:\n$start", start.contains("stop()"))
    }

    // ── F-04 ────────────────────────────────────────────────────────────────

    @Test
    fun `no path removes a session mutex that somebody may hold`() {
        val code = codeOnly(repoFile(coordinatorRel))
        val uses = code.lines()
            .filter { "mutexes." in it }
            .map { it.trim() }
            .filterNot { it.startsWith("private val mutexes") }
        assertEquals(
            "the mutex map must be read by exactly one site — getOrPut. Any `remove`/`clear` " +
                "re-opens the s4h4 hole (a second Mutex minted for a session whose command is " +
                "still inside withLock): $uses",
            listOf("val mutex = mutexes.getOrPut(sessionId) { Mutex() }"),
            uses,
        )
    }

    @Test
    fun `the mutex-identity invariant a session's serialization rests on`() {
        // The defect in one screen, on the same two types production uses:
        // `ConcurrentHashMap<String, Mutex>` + `mutexes.getOrPut`. Removing the
        // entry while a coroutine holds that Mutex does not "free" a lock — it
        // hands the NEXT command a different one, so two commands of the same
        // session run at once and the older one's teardown kills the newer
        // shell.
        val map = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
        val first = map.getOrPut("s1") { kotlinx.coroutines.sync.Mutex() }
        map.getOrPut("s1") { kotlinx.coroutines.sync.Mutex() }
        assertEquals("without a removal, one id keeps one Mutex instance", first, map["s1"])
        assertTrue("the holder takes the lock", first.tryLock())

        map.remove("s1")  // ← what stopCurrentCommand used to do while `first` was locked
        val second = map.getOrPut("s1") { kotlinx.coroutines.sync.Mutex() }
        assertFalse(
            "after a removal the same id gets a NEW Mutex — the serialization is gone",
            first === second,
        )
        assertTrue("the holder still owns the old one", first.isLocked)
        assertTrue("…and the newcomer takes its own immediately", second.tryLock())
        second.unlock()
        first.unlock()
    }

    // ── F-05 ────────────────────────────────────────────────────────────────

    @Test
    fun `the sweeper reads lstat and deletes without following links`() {
        // The helpers being right is only half of it (see GuestTmpSweepPlanTest,
        // which pins their behaviour on real fixtures); the sweeper has to CALL
        // them. `File.lastModified()` + `deleteRecursively()` is the defect.
        val body = sliceFrom(repoFile(coordinatorRel), "private fun sweepGuestTmpDir(", "\n    private fun ")
        val code = codeOnly(body)
        assertTrue(
            "the age gate must use the entry's OWN mtime:\n$body",
            code.contains("internalGuestTmpEntryMtimeMs(child.toPath())"),
        )
        assertTrue(
            "the delete must not follow links:\n$body",
            code.contains("internalGuestTmpDeleteTreeNoFollow(child.toPath())"),
        )
        assertFalse(
            "the following delete is what reached outside the swept entry:\n$body",
            code.contains("child.deleteRecursively()"),
        )
        assertFalse(
            "and the age gate must not read the followed timestamp:\n$body",
            code.contains("internalGuestTmpIsAged(child.lastModified()"),
        )
    }

    // ── probe plumbing ──────────────────────────────────────────────────────

    /** Blank comment lines (offsets preserved) so prose cannot satisfy a probe. */
    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) " ".repeat(line.length) else line
    }

    private fun sliceFrom(src: String, startMarker: String, endMarker: String): String {
        val start = src.indexOf(startMarker)
        assertTrue("marker not found: $startMarker", start >= 0)
        val end = src.indexOf(endMarker, start + startMarker.length)
        return if (end < 0) src.substring(start) else src.substring(start, end)
    }

    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
