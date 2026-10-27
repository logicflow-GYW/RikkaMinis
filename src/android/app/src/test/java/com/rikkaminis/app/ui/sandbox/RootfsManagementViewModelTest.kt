package com.rikkaminis.app.ui.sandbox

import com.rikkaminis.app.sandbox.RootfsInstallState
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1041 B-02] The failure terminal of the three rootfs lifecycle
 * entry points.
 *
 * Bug context: `install` / `resetRootfs` / `repairRootfs` each called
 * `refresh(ctx)` on the SUCCESS path only. `reset()` deletes the rootfs
 * directory BEFORE re-installing, and `autoRepair()`'s last stage is exactly
 * that reset — so a throw mid-extract left `isInstalled` at its pre-operation
 * `true`: the screen kept rendering the green "Installed" badge with the
 * "Install" button hidden over a directory that no longer existed. `refresh()`
 * additionally read `rootfsSize` only for an INSTALLED rootfs, so the previous
 * figure survived a wipe and sat next to "Not installed".
 *
 * The state transition is exercised on the real function; "does every failure
 * arm reach it" cannot run on a JVM (Compose + `ViewModel` + `Context`), so it
 * is pinned as source shape — the house pattern of `MediaHostSourceKeyTest`.
 * Expected counts are dead literals, NOT derived from the production file.
 */
class RootfsManagementViewModelTest {

    // ── 1. the failure terminal itself (real function, no Android) ───────

    @Test
    fun `the failure terminal clears the in-flight and success flags`() {
        val before = RootfsManagementUiState(
            isInstalled = true,
            isProcessing = true,
            statusMessage = "Resetting…",
            resultMessage = null,
            lastOperationSuccess = true,
            rootfsSize = 111L,
            rootfsPath = "/x",
            installProgress = 0.42f,
        )

        val after = rootfsFailureState(before, "Reset failed")

        assertEquals("Reset failed", after.resultMessage)
        assertFalse("a failed operation must not stay in flight", after.isProcessing)
        assertFalse("a failed operation must not claim success", after.lastOperationSuccess)
        assertEquals("the progress bar must be cleared", null, after.installProgress)
    }

    @Test
    fun `the failure terminal does not invent an install state`() {
        // Which rootfs is really on disk is `refresh()`'s verdict (it re-reads
        // the manager). The pure transition must not guess — the defect was
        // precisely a guessed snapshot surviving the operation.
        val before = RootfsManagementUiState(
            isInstalled = true,
            rootfsSize = 111L,
            rootfsPath = "/x",
            statusMessage = "Repairing…",
        )

        val after = rootfsFailureState(before, "msg")

        assertTrue(after.isInstalled)
        assertEquals(111L, after.rootfsSize)
        assertEquals("/x", after.rootfsPath)
        assertEquals("Repairing…", after.statusMessage)
    }

    // ── 2. the wiring: every failure arm settles through the terminal ────

    @Test
    fun `every lifecycle failure arm reaches the shared terminal`() {
        val code = stripComments(readRepoFile(VIEW_MODEL))
        for (fn in LIFECYCLE) {
            val body = bodyOf(code, "fun $fn(")
            val arms = Regex("""catch\s*\([^)]*\)\s*\{""").findAll(body).toList()
            assertTrue("$fn must keep a failure arm", arms.isNotEmpty())
            for (m in arms) {
                val arm = braceBlockAt(body, m.range.last)
                assertTrue(
                    "$fn's catch arm must settle through the shared failure terminal " +
                        "(it must reach refresh()), got: ${arm.take(160)}",
                    arm.contains("settleRootfsFailure("),
                )
            }
        }
    }

    @Test
    fun `each lifecycle entry point subscribes to install progress`() {
        // `install` and `resetRootfs` always did; `repairRootfs` published
        // `installProgress = 0f` and then left the bar frozen at 0% for the
        // whole minutes-long Stage 3 re-extract because nobody collected
        // `manager.installState`.
        val code = stripComments(readRepoFile(VIEW_MODEL))
        for (fn in LIFECYCLE) {
            assertTrue(
                "$fn must subscribe to install progress",
                bodyOf(code, "fun $fn(").contains("observeInstallProgress("),
            )
        }
    }

    @Test
    fun `refresh zeroes the size of a missing rootfs before the guarded size read`() {
        val body = bodyOf(stripComments(readRepoFile(VIEW_MODEL)), "fun refresh(")
        val decision = Regex("""rootfsSize\s*=\s*if\s*\([^)]*isInstalled[^)]*\)[^,\n]*\belse\s+0L?""")
        val match = decision.find(body)
        assertTrue(
            "refresh must zero rootfsSize when the rootfs is not installed (the old " +
                "figure otherwise outlives a wipe and is rendered beside \"Not installed\")",
            match != null,
        )
        val guardedReadAt = body.indexOf("if (manager.isInstalled) {")
        assertTrue("the guarded size read must stay in refresh", guardedReadAt > 0)
        assertTrue(
            "the size decision must sit outside the installed-only branch — clearing it " +
                "inside that branch would flash 0 on every refresh of a healthy rootfs",
            match!!.range.first < guardedReadAt,
        )
    }

    // ── 3. the StateFlow replay gate ([deep-audit-1042 B-05]) ─────────────

    @Test
    fun `the replay gate skips a prior terminal on the first frame only`() {
        val prior = RootfsInstallState.Installed

        assertTrue(
            "first frame holding the same Installed instance is a replay and must be skipped",
            isStaleInstallReplay(firstFrame = true, state = prior, priorTerminal = prior),
        )
        assertFalse(
            "the same terminal on a later frame is a live event (first frame was consumed)",
            isStaleInstallReplay(firstFrame = false, state = prior, priorTerminal = prior),
        )
    }

    @Test
    fun `a live Failed is never the stale instance the collector started on`() {
        val prior = RootfsInstallState.Failed("old disk-space verdict")
        val live = RootfsInstallState.Failed("old disk-space verdict")

        assertEquals(
            "fixture premise: RootfsManager constructs a fresh Failed for every verdict, " +
                "so the texts match but the instances do not",
            prior.error, live.error,
        )
        assertFalse(
            "a live failure of identical text must NOT be dropped as a replay",
            isStaleInstallReplay(firstFrame = true, state = live, priorTerminal = prior),
        )
        // and the shape that must be dropped: the literal previous instance
        assertTrue(
            "the previous operation's Failed instance itself IS the replay",
            isStaleInstallReplay(firstFrame = true, state = prior, priorTerminal = prior),
        )
    }

    @Test
    fun `non-terminal frames are never dropped`() {
        // A non-terminal current value means an operation is in flight; its
        // progress must be mirrored even on the very first frame — including
        // when the flow happens to be holding that same phase again.
        for (active in listOf(
            RootfsInstallState.Idle,
            RootfsInstallState.Preparing,
            RootfsInstallState.Extracting(0.25f),
            RootfsInstallState.Finalizing,
        )) {
            assertFalse(
                "${active.javaClass.simpleName} against a different prior must be mirrored",
                isStaleInstallReplay(
                    firstFrame = true, state = active, priorTerminal = RootfsInstallState.Installed,
                ),
            )
            assertFalse(
                "${active.javaClass.simpleName} against itself is still an in-flight phase, " +
                    "not a terminal replay",
                isStaleInstallReplay(firstFrame = true, state = active, priorTerminal = active),
            )
        }
    }

    // ── 4. the repair settlement ([deep-audit-1042 B-05]) ─────────────────

    private fun repairFixtureState() = RootfsManagementUiState(
        isInstalled = true,
        isProcessing = true,
        statusMessage = "Repairing…",
        resultMessage = null,
        installProgress = 0.4f,
    )

    @Test
    fun `a published Failed outranks autoRepair returning false`() {
        // Stage 3 of autoRepair() is a full reset(); installIfNeeded() reports
        // its disk-space gate by publishing Failed and RETURNING. The old
        // settlement branched on `repaired` alone and announced the refusal as
        // "repair failed — still missing: …" (rootfs corruption).
        val after = rootfsRepairState(
            repairFixtureState(),
            RootfsInstallState.Failed("Not enough space: need about 900 MB"),
            repaired = false,
            repairedMessage = "REPAIRED-TEXT",
            stillMissingMessage = "STILL-MISSING-TEXT",
        )

        assertEquals(
            "the disk-space verdict must surface, not the corruption text",
            "Not enough space: need about 900 MB", after.resultMessage,
        )
        assertFalse("a refused repair is not a success", after.lastOperationSuccess)
        assertFalse("the operation is over", after.isProcessing)
        assertEquals("the progress bar must be cleared", null, after.installProgress)
    }

    @Test
    fun `repaired with no Failed verdict keeps the repaired contract`() {
        val after = rootfsRepairState(
            repairFixtureState(),
            RootfsInstallState.Installed,
            repaired = true,
            repairedMessage = "REPAIRED-TEXT",
            stillMissingMessage = "STILL-MISSING-TEXT",
        )

        assertEquals("REPAIRED-TEXT", after.resultMessage)
        assertTrue(after.lastOperationSuccess)
        assertFalse(after.isProcessing)
        assertEquals(null, after.installProgress)
    }

    @Test
    fun `autoRepair false without a Failed verdict keeps repair_missing`() {
        // The existing contract from B-02: a repair that genuinely finished but
        // left files missing still reports rootfs_msg_repair_missing with the
        // re-verified list, and is an error.
        val after = rootfsRepairState(
            repairFixtureState(),
            RootfsInstallState.Installed,
            repaired = false,
            repairedMessage = "REPAIRED-TEXT",
            stillMissingMessage = "STILL-MISSING-TEXT",
        )

        assertEquals("STILL-MISSING-TEXT", after.resultMessage)
        assertFalse(after.lastOperationSuccess)
        assertFalse(after.isProcessing)
    }

    @Test
    fun `a live Failed replayed as the first frame keeps the guard in force`() {
        // The two halves of the defect on the real functions: the replay gate
        // drops the STALE Failed, and the settlement still surfaces a LIVE one.
        val stale = RootfsInstallState.Failed("previous run: disk space")
        val live = RootfsInstallState.Failed("Not enough space: need about 900 MB")
        assertTrue(
            "the stale verdict the subscription started on is dropped",
            isStaleInstallReplay(true, stale, stale),
        )
        val settled = rootfsRepairState(
            repairFixtureState(), live, repaired = false,
            repairedMessage = "R", stillMissingMessage = "M",
        )
        assertEquals("Not enough space: need about 900 MB", settled.resultMessage)
        assertFalse(settled.lastOperationSuccess)
    }

    // ── 4b. the inherited terminal ([deep-audit-1043 B-01]) ────────────────

    @Test
    fun `an inherited Failed does not fail a repair that restored the rootfs`() {
        // RootfsManager never resets installState to Idle and autoRepair()
        // publishes nothing in Stages 1/2/2.5/2.6, so yesterday's Failed is
        // still sitting on the flow when a repair fixes everything in place.
        // The unconditional first arm reported that stale error — and
        // lastOperationSuccess = false — over a repair that had just restored
        // every critical file.
        val stale = RootfsInstallState.Failed("Not enough space: need about 900 MB")
        val after = rootfsRepairState(
            repairFixtureState(),
            stale,
            repaired = true,
            repairedMessage = "REPAIRED-TEXT",
            stillMissingMessage = "STILL-MISSING-TEXT",
            priorTerminal = stale,
        )

        assertEquals("the repair's own outcome decides", "REPAIRED-TEXT", after.resultMessage)
        assertTrue("restoring every critical file is a success", after.lastOperationSuccess)
        assertFalse("the operation is over", after.isProcessing)
        assertEquals("the progress bar must be cleared", null, after.installProgress)
    }

    @Test
    fun `a fresh Failed repeating the stale text is still this repair's verdict`() {
        // The discriminator is instance identity, NOT text: RootfsManager
        // constructs a fresh Failed for every verdict it publishes, so a real
        // failure during THIS repair must win even when the message repeats —
        // filtering by `error` equality would swallow it.
        val stale = RootfsInstallState.Failed("Not enough space: need about 900 MB")
        val live = RootfsInstallState.Failed("Not enough space: need about 900 MB")
        assertEquals("fixture premise: identical text, different verdict", stale.error, live.error)
        assertFalse(
            "fixture premise: the two are not the same instance",
            isStaleInstallReplay(firstFrame = true, state = live, priorTerminal = stale),
        )

        val after = rootfsRepairState(
            repairFixtureState(),
            live,
            repaired = false,
            repairedMessage = "REPAIRED-TEXT",
            stillMissingMessage = "STILL-MISSING-TEXT",
            priorTerminal = stale,
        )

        assertEquals(
            "the disk-space verdict this repair published must surface",
            "Not enough space: need about 900 MB", after.resultMessage,
        )
        assertFalse("a refused repair is not a success", after.lastOperationSuccess)
    }

    @Test
    fun `an inherited Failed over a still-broken rootfs reports the current missing list`() {
        // Stage 1/2 finished but files are still missing: the honest message is
        // the re-verified list, not yesterday's unrelated error text.
        val stale = RootfsInstallState.Failed("previous run: disk space")
        val after = rootfsRepairState(
            repairFixtureState(),
            stale,
            repaired = false,
            repairedMessage = "REPAIRED-TEXT",
            stillMissingMessage = "STILL-MISSING-TEXT",
            priorTerminal = stale,
        )

        assertEquals("STILL-MISSING-TEXT", after.resultMessage)
        assertFalse(after.lastOperationSuccess)
        assertFalse(after.isProcessing)
        assertEquals(null, after.installProgress)
    }

    // ── 5. source shape: the replay gate and the settlement wiring ────────

    @Test
    fun `the collector skips the replayed first frame and only that frame`() {
        val code = stripComments(readRepoFile(VIEW_MODEL))
        val body = bodyOf(code, "fun observeInstallProgress(")
        assertTrue(
            "the collector must capture the flow's current value BEFORE subscribing",
            body.indexOf("manager.installState.value") <
                body.indexOf("manager.installState.collect"),
        )
        assertTrue(
            "the first-frame replay must be decided by the shared pure gate",
            body.contains("isStaleInstallReplay("),
        )
        val decide = body.indexOf("isStaleInstallReplay(")
        val consume = body.indexOf("firstFrame = false")
        val skipped = body.indexOf("if (replay) return@collect")
        assertTrue("the replay must be decided", decide > 0)
        assertTrue("the first-frame flag must be consumed", consume > 0)
        assertTrue("a replayed frame must be skipped", skipped > 0)
        assertTrue(
            "the decision must precede the skip",
            decide < skipped,
        )
        assertTrue(
            "the first-frame flag must be consumed right after the decision so only " +
                "frame 1 can be skipped",
            consume - decide < 120,
        )
    }

    @Test
    fun `every settle site cancels the progress collector`() {
        // StateFlow.collect never returns on its own; before B-05 the job
        // survived the operation that started it.
        val code = stripComments(readRepoFile(VIEW_MODEL))
        for (anchor in listOf(
            "fun settleRootfsOperation(",
            "fun settleRootfsFailure(",
        )) {
            assertTrue(
                "$anchor must cancel progressJob — StateFlow.collect never ends itself",
                bodyOf(code, anchor).contains("progressJob?.cancel()"),
            )
        }
        assertTrue(
            "the repair settlement must cancel progressJob too",
            bodyOf(code, "fun repairRootfs(").contains("progressJob?.cancel()"),
        )
    }

    @Test
    fun `repair settles through the published verdict`() {
        val code = stripComments(readRepoFile(VIEW_MODEL))
        val body = bodyOf(code, "fun repairRootfs(")
        assertTrue(
            "repairRootfs must route the settlement through the shared pure helper",
            body.contains("rootfsRepairState("),
        )
        assertTrue(
            "the verdict passed must be the manager's CURRENT terminal state",
            body.contains("manager.installState.value"),
        )
    }

    @Test
    fun `repairRootfs snapshots the pre-operation terminal before it repairs`() {
        val code = stripComments(readRepoFile(VIEW_MODEL))
        val body = bodyOf(code, "fun repairRootfs(")
        val capture = Regex("""val\s+(\w+)\s*=\s*manager\.installState\.value""").find(body)
        assertTrue(
            "repairRootfs must snapshot the terminal the flow was holding before the repair " +
                "publishes anything — without it the settlement cannot tell an inherited " +
                "Failed from one of its own",
            capture != null,
        )
        val captured = capture!!.groupValues[1]
        val repairAt = body.indexOf("manager.autoRepair()")
        assertTrue("autoRepair() must stay in repairRootfs", repairAt > 0)
        assertTrue(
            "the snapshot must be taken BEFORE autoRepair() — Stage 3 republishes the terminal, " +
                "so a later capture would record this operation's own verdict as the prior one",
            capture.range.first < repairAt,
        )
        val args = callArgs(body, "rootfsRepairState(")
        assertTrue(
            "the settlement must hand the snapshot to the helper (got: ${args.take(220)})",
            Regex("\\b" + captured + "\\b").containsMatchIn(args),
        )
    }



    // ── source helpers (same idiom as the other shape tests) ─────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** Body of `<decl>` — brace-balanced, quoted spans excluded. */
    private fun bodyOf(src: String, decl: String): String {
        val at = src.indexOf(decl)
        assertTrue("declaration not found: $decl", at >= 0)
        val open = src.indexOf("{", at)
        return braceBlockAt(src, open)
    }

    /** The argument list of the first `<call>` — paren-balanced, quoted spans excluded. */
    private fun callArgs(src: String, call: String): String {
        val at = src.indexOf(call)
        assertTrue("call not found: $call", at >= 0)
        var i = src.indexOf("(", at)
        val start = i + 1
        var depth = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '"' || c == '\'') {
                val quote = c
                i++
                while (i < src.length && src[i] != quote) {
                    i += if (src[i] == '\\') 2 else 1
                }
                i++
                continue
            }
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) return src.substring(start, i)
            }
            i++
        }
        throw AssertionError("unbalanced call arguments for $call")
    }

    /** The `{ … }` block whose opening brace is at [open]. */
    private fun braceBlockAt(src: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < src.length) {
            val c = src[i]
            if (c == '"' || c == '\'') {
                val quote = c
                i++
                while (i < src.length && src[i] != quote) {
                    i += if (src[i] == '\\') 2 else 1
                }
                i++
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return src.substring(open, i + 1)
            }
            i++
        }
        throw AssertionError("unbalanced braces at offset $open")
    }

    /** Removes // and block comments (nesting-aware) while keeping string literals. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                var nest = 1
                i += 2
                while (i < src.length && nest > 0) {
                    if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        nest--
                        i += 2
                    } else if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        nest++
                        i += 2
                    } else {
                        i++
                    }
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private companion object {
        private const val VIEW_MODEL =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/RootfsManagementViewModel.kt"
        private val LIFECYCLE = listOf("install", "resetRootfs", "repairRootfs")
    }
}
