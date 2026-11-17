package com.rikkaminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-T6 D11 F-01 / F-02 / F-05] The three share-path lifecycle rules, plus
 * the wires that must keep calling them.
 *
 * All three defects are "a callback landed on an instance the code assumed
 * could not exist":
 *
 *  1. **F-01** — `ShareReceiverActivity` is an exported `ACTION_SEND` target,
 *     so the system can launch it while `MinisApp.onCreate` has early-returned
 *     (safe mode / `_initSkipped` latch / newer-database degraded mode) and
 *     `providerRepository` is still `lateinit`-uninitialised. Reading it threw
 *     `UninitializedPropertyAccessException` — a crash while the app was
 *     already collecting crash logs.
 *  2. **F-02** — the staged-file registry has no owner and no TTL, so an
 *     Activity destroyed mid-copy (back press, task switch, low-memory kill of
 *     the Activity while the process lives) left its claims protected forever:
 *     the file stayed on disk and kept occupying the 500 MB share budget.
 *  3. **F-05** — a warm-start share lands on whatever route the user left; only
 *     `ChatScreen` drains [ShareCoordinator]'s 30 s buffer, so a share that
 *     arrived while the user stood on settings expired unread.
 *
 * The decision tests use HARD-CODED literals (no expectation derived from a
 * production constant) and the wiring tests read the REAL sources; each wire
 * has a negative control that mutates the pre-fix shape back in and requires
 * the probe to reject it.
 */
class ShareHostPolicyTest {

    private val activity = readRepoFile(
        "app/src/main/java/com/rikkaminis/app/share/ShareReceiverActivity.kt",
    )
    private val main = readRepoFile("app/src/main/java/com/rikkaminis/app/MainActivity.kt")

    // ─── F-01: the repositories gate ─────────────────────────────────────────

    @Test
    fun `repositories are reachable only after a complete init`() {
        // The only state that may touch MinisApp's lateinit fields.
        assertTrue(
            ShareHostPolicy.mayUseAppRepositories(
                isSafeMode = false,
                isInitSkipped = false,
                isNewerDbGuidance = false,
            ),
        )
        // Safe mode: MinisApp returned before providerRepository was assigned.
        assertFalse(
            ShareHostPolicy.mayUseAppRepositories(
                isSafeMode = true,
                isInitSkipped = true,
                isNewerDbGuidance = false,
            ),
        )
        // finishClose() clears _safeMode while init stays skipped for the whole
        // process — the latch alone must still block the read.
        assertFalse(
            ShareHostPolicy.mayUseAppRepositories(
                isSafeMode = false,
                isInitSkipped = true,
                isNewerDbGuidance = false,
            ),
        )
        // Degraded mode returns before the database — and every repository.
        assertFalse(
            ShareHostPolicy.mayUseAppRepositories(
                isSafeMode = false,
                isInitSkipped = false,
                isNewerDbGuidance = true,
            ),
        )
    }

    @Test
    fun `provider import asks the gate before it reads the lateinit repository`() {
        assertImportGuardHolds(activity)
    }

    /**
     * The provider-import path may only dereference `providerRepository` after
     * [ShareHostPolicy.mayUseAppRepositories] said the process is fully
     * initialised — and the pre-fix one-liner (`?.providerRepository` straight
     * off the cast) must be gone, because it is the exact shape that threw.
     */
    private fun assertImportGuardHolds(source: String) {
        val body = source.substringAfter("private fun importProviderJson")
        val guard = body.indexOf("ShareHostPolicy.mayUseAppRepositories(")
        val read = body.indexOf("app.providerRepository")
        assertTrue("importProviderJson must consult the gate", guard >= 0)
        assertTrue("the repository read must still be there", read >= 0)
        assertTrue(
            "the gate must run BEFORE the read (gate@$guard, read@$read)",
            guard < read,
        )
        assertEquals(
            "the flag must be reported as safe-mode, not as something else",
            true,
            body.substring(guard, read).contains("CrashFrequencyDetector.isSafeMode()"),
        )
        assertEquals(
            "the one-way init latch is a separate state and must be checked too",
            true,
            body.substring(guard, read).contains("CrashFrequencyDetector.isInitSkipped()"),
        )
        assertTrue(
            "the gate result must be what the read is conditioned on",
            body.substring(guard, read).contains("if (mayUseRepositories"),
        )
        assertFalse(
            "the pre-fix shape (lateinit read straight off the cast) must be gone",
            source.contains("(applicationContext as? com.rikkaminis.app.MinisApp)?.providerRepository"),
        )
    }

    // ─── F-02: destroy-time release of uncommitted staged files ─────────────

    @Test
    fun `destroy releases staged files that were never handed off`() {
        assertTrue(
            "no hand-off, no rotation, nothing saved => the files are abandoned",
            ShareHostPolicy.shouldReleaseStagedOnDestroy(
                handedOff = false,
                isChangingConfigurations = false,
                stateSavedForRestore = false,
            ),
        )
        assertFalse(
            "a handed-off set belongs to the pending share / the disk, not to us",
            ShareHostPolicy.shouldReleaseStagedOnDestroy(
                handedOff = true,
                isChangingConfigurations = false,
                stateSavedForRestore = false,
            ),
        )
        assertFalse(
            "a rotation restores and re-claims the same names — releasing them in the "
                + "preceding destroy would leave live files unprotected",
            ShareHostPolicy.shouldReleaseStagedOnDestroy(
                handedOff = false,
                isChangingConfigurations = true,
                stateSavedForRestore = false,
            ),
        )
        assertFalse(
            "a system-initiated destroy with saved state is a rebuild in progress: the "
                + "restored dialog re-offers exactly these files (deleting them would hand "
                + "it ghost attachments)",
            ShareHostPolicy.shouldReleaseStagedOnDestroy(
                handedOff = false,
                isChangingConfigurations = false,
                stateSavedForRestore = true,
            ),
        )
    }

    @Test
    fun `onDestroy releases only what this instance never handed off`() {
        assertDestroyReleaseWired(activity)
    }

    private fun assertDestroyReleaseWired(source: String) {
        // Scope to onDestroy's own body: the release helper's DEFINITION also
        // contains its name, so an unscoped `contains` would stay green after
        // the call was deleted (caught by the CI round trip on 524eaa3f).
        val body = source.substringAfter("override fun onDestroy()")
            .substringBefore("private fun releaseUncommittedStagedFiles(")
        assertTrue("onDestroy must consult the pure decision", body.contains(
            "ShareHostPolicy.shouldReleaseStagedOnDestroy(",
        ))
        assertTrue(
            "a configuration change must be reported to the decision",
            body.contains("isChangingConfigurations = isChangingConfigurations"),
        )
        assertTrue(
            "the hand-off flag must be reported to the decision",
            body.contains("handedOff = stagedHandedOff"),
        )
        assertTrue(
            "a restoreable destroy must be reported to the decision",
            body.contains("stateSavedForRestore = stateSavedForRestore"),
        )
        assertTrue(
            "onSaveInstanceState must arm that flag only when it really saved the set",
            source.substringAfter("override fun onSaveInstanceState(outState: Bundle)")
                .substringBefore("private fun stagedItemsSnapshot()")
                .contains("stateSavedForRestore = true"),
        )
        assertTrue(
            "the release must actually run when the decision allows it",
            body.contains("releaseUncommittedStagedFiles()"),
        )
        // Both producers must remember what they claimed: one entry per
        // `StagedInFlightFiles.begin(name)` site (stream copy + text attach).
        assertEquals(
            "every claim site must record the name for the destroy path",
            2,
            source.split("claimedNames.add(name)").size - 1,
        )
        // And every hand-off must disarm the destroy path.
        assertTrue(
            "the pending-share hand-off must mark the set handed off",
            source.contains("stagedHandedOff = true"),
        )
        assertTrue(
            "the provider-import discard is a hand-off too: deleting the set ends this "
                + "instance's ownership, so the destroy must not repeat it",
            source.substringAfter("private fun discardStagedAttachments(items: List<PendingShare.Item>) {")
                .substringBefore("\n    }")
                .contains("stagedHandedOff = true"),
        )
    }

    // ─── F-05: route a buffered share to a chat ─────────────────────────────

    @Test
    fun `a live chat needs no navigation`() {
        assertFalse(
            ShareHostPolicy.shareNeedsChatRoute(
                topRoute = "chat/{sessionId}?focusMessageId={focusMessageId}",
                topIsChatKeptAliveOverlay = false,
                belowRoute = null,
                chatRoute = "chat/{sessionId}?focusMessageId={focusMessageId}",
            ),
        )
    }

    @Test
    fun `a dialog above a chat still counts as a live chat`() {
        assertFalse(
            ShareHostPolicy.shareNeedsChatRoute(
                topRoute = "chat/files/{sessionId}",
                topIsChatKeptAliveOverlay = true,
                belowRoute = "chat/{sessionId}?focusMessageId={focusMessageId}",
                chatRoute = "chat/{sessionId}?focusMessageId={focusMessageId}",
            ),
        )
    }

    @Test
    fun `a full destination stacked on a chat is not a live chat`() {
        // Settings is a real destination: the chat left composition with its
        // LaunchedEffect, so nothing drains the buffer.
        assertTrue(
            ShareHostPolicy.shareNeedsChatRoute(
                topRoute = "settings",
                topIsChatKeptAliveOverlay = false,
                belowRoute = "chat/{sessionId}?focusMessageId={focusMessageId}",
                chatRoute = "chat/{sessionId}?focusMessageId={focusMessageId}",
            ),
        )
    }

    @Test
    fun `a dialog that is not above a chat still needs a route`() {
        assertTrue(
            ShareHostPolicy.shareNeedsChatRoute(
                topRoute = "settings/file_preview",
                topIsChatKeptAliveOverlay = true,
                belowRoute = "settings",
                chatRoute = "chat/{sessionId}?focusMessageId={focusMessageId}",
            ),
        )
    }

    @Test
    fun `warm-start share arms a chat route and navigates only when no chat is live`() {
        assertShareDeliveryWired(main)
    }

    private fun assertShareDeliveryWired(source: String) {
        val onCreate = source.substringAfter("if (intent?.getBooleanExtra(\"shared_content\", false) == true) {")
        assertTrue(
            "a cold start / Activity restore with a buffered share must arm a chat route",
            onCreate.substringBefore("\n        }").contains("pendingShareChatRoute.value = Routes.chat("),
        )
        val onNewIntent = source.substringAfter("val sharedContent = intent.getBooleanExtra(\"shared_content\", false)")
        assertTrue(
            "the warm-start path must arm a chat route too (this is F-05 itself)",
            onNewIntent.substringBefore("\n        }").contains("pendingShareChatRoute.value = Routes.chat("),
        )
        val effect = source.substringAfter("LaunchedEffect(pendingShareChatRoute.value) {")
        assertTrue("the armed route must be consumed exactly once", effect.contains("pendingShareChatRoute.value = null"))
        assertTrue(
            "the effect must ask the pure policy before navigating",
            effect.contains("ShareHostPolicy.shareNeedsChatRoute("),
        )
        assertTrue(
            "the chat route pattern must be the production one, not a literal copy",
            effect.contains("chatRoute = Routes.CHAT"),
        )
        assertTrue(
            "a buffered share with no chat on screen must actually navigate",
            effect.contains("navController.navigate(route)"),
        )
    }

    // ─── negative controls: the probes must reject the pre-fix shapes ───────

    @Test
    fun `the wire probes reject the pre-fix sources`() {
        val preFixImport = activity.replace(
            "        val repo = if (mayUseRepositories && app != null) {",
            "        val repo = (applicationContext as? com.rikkaminis.app.MinisApp)?.providerRepository"
                + "\n        if (true) {",
        )
        assertFalse(
            "the mutation must really reintroduce the pre-fix shape",
            preFixImport == activity,
        )
        val noDestroy = activity.replace(
            "        if (!releaseUncommitted) return\n        releaseUncommittedStagedFiles()",
            "        if (!releaseUncommitted) return",
        )
        assertFalse("the destroy mutation must really remove the release", noDestroy == activity)
        assertFalse("the mutation must really remove the release", noDestroy == activity)
        val noArm = main.replace(
            "                pendingShareChatRoute.value = null\n",
            "",
        )
        assertFalse("the mutation must really remove the consume", noArm == main)

        val rejected = mutableListOf<String>()
        runCatching { assertImportGuardHolds(preFixImport) }.onFailure { rejected += "import-guard" }
        runCatching { assertDestroyReleaseWired(noDestroy) }.onFailure { rejected += "destroy-release" }
        runCatching { assertShareDeliveryWired(noArm) }.onFailure { rejected += "share-delivery" }
        assertEquals(
            "each mutation must be rejected by its own probe",
            "import-guard, destroy-release, share-delivery",
            rejected.joinToString(", "),
        )
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
