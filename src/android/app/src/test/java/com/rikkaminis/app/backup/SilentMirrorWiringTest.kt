package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [silent-mirror] 2026-10-29: the external mirror runs silently — the settings
 * row lost its "sync now" button because a time-throttled sync behind a
 * human-initiated control is a control surface that lies. The bug it replaces:
 * `syncNow()` had no force parameter, the button ignored both the coalescing
 * window and the returned outcome, and the row displayed a real pending count
 * next to a button that could do nothing about it for up to ten minutes.
 *
 * These are SHAPE assertions; the decisions themselves are behaviour-tested in
 * [MirrorPlanTest] and the Android shell cannot be instantiated on the JVM.
 * What the shape must keep true:
 *  - no unforced human-initiated sync call exists (the retry is forced — that
 *    is what makes it answer the state it is shown for);
 *  - the automatic triggers stay unforced (default `force = false`);
 *  - a background transition is a trigger: it is the flush point the removed
 *    button used to be;
 *  - the row branches on [MirrorPlan.classifyHealth] instead of an inline
 *    "is the timestamp old" comparison (which cannot tell "nothing changed"
 *    from "the trigger stopped firing").
 */
class SilentMirrorWiringTest {

    private val screenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt"
    private val storeRel = "src/android/app/src/main/java/com/rikkaminis/app/backup/MirrorStore.kt"
    private val targetRel = "src/android/app/src/main/java/com/rikkaminis/app/backup/MirrorTarget.kt"
    private val appRel = "src/android/app/src/main/java/com/rikkaminis/app/MinisApp.kt"

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            n++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return n
    }

    /** Comment-stripped source: the prose in these files quotes the shapes
     *  being counted (the row explains why the button is gone), so a raw count
     *  would either be satisfied or broken by a sentence. */
    private fun withoutComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val nl = src.indexOf('\n', i)
                    i = if (nl < 0) src.length else nl
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** The braced body of the first block opened after [from] (a `{` at or
     *  after `from`), so an assertion can be scoped to one callback instead of
     *  a whole file. */
    private fun braced(src: String, from: Int): String {
        val open = src.indexOf('{', from)
        if (open < 0) throw AssertionError("no block opened after offset $from")
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
        throw AssertionError("unbalanced block opened at offset $open")
    }

    // ------------------------------------------------- the human-intent path

    @Test
    fun theOnlyHumanInitiatedSyncIsForced() {
        val code = withoutComments(readRepoFile(screenRel))
        assertEquals(
            "the row must have no unforced sync call: an unforced one is exactly the " +
                "swallowed button (the coalescing window answers Throttled and the " +
                "caller used to discard the outcome)",
            0,
            count(code, "syncNow(context)"),
        )
        assertEquals(
            "exactly one forced sync call — the status row's retry",
            1,
            count(code, "syncNow(context, force = true)"),
        )
    }

    @Test
    fun theManualSyncStringIsGone() {
        val code = withoutComments(readRepoFile(screenRel))
        assertEquals(
            "`mirror_sync_now` must not come back: the row is passive by design, and a " +
                "dead string is how the button gets re-added unnoticed",
            0,
            count(code, "mirror_sync_now"),
        )
    }

    @Test
    fun theAutomaticTriggersStayUnforced() {
        val code = withoutComments(readRepoFile(storeRel))
        assertTrue(
            "syncNow keeps `force = false` as its default, so the automatic triggers " +
                "cannot start bypassing the coalescing window by accident",
            code.contains("suspend fun syncNow(context: Context, force: Boolean = false)"),
        )
    }

    // ------------------------------------------------------------- triggers

    @Test
    fun goingToTheBackgroundIsATrigger() {
        val code = withoutComments(readRepoFile(appRel))
        assertEquals(
            "three mirror triggers: session settlement, background→foreground, and " +
                "foreground→background (2026-10-29: the last one is the flush point the " +
                "removed button used to be — failing to add it would have silently moved " +
                "the gap, not closed it)",
            3,
            count(code, "backup.MirrorStore.runAsync("),
        )
        val stopped = braced(code, code.indexOf("override fun onActivityStopped("))
        assertTrue(
            "the background transition itself must fire the mirror, inside the same " +
                "count-reaches-zero branch that tears the foreground state down",
            stopped.contains("backup.MirrorStore.runAsync("),
        )
    }

    // ---------------------------------------------------------- the verdict

    @Test
    fun theRowDecidesFromTheClassifier() {
        val code = withoutComments(readRepoFile(screenRel))
        assertEquals(
            "the row's verdict comes from MirrorPlan.classifyHealth exactly once — an " +
                "inline age comparison cannot distinguish 'nothing changed' from 'the " +
                "trigger stopped firing', which is the whole job of a passive row",
            1,
            count(code, "MirrorPlan.classifyHealth("),
        )
        assertTrue(
            "the retry must stay disabled while a restore/fetch holds the shared gate",
            code.contains("enabled = !mirrorRunning && !operationBusy"),
        )
        assertTrue(
            "a retry site that is not inside the unhealthy branch would put the button back",
            code.contains("classifyHealth(") && code.contains("Health.Disabled"),
        )
    }

    // --------------------------------------------------- the two decisions

    @Test
    fun forceSkipsOnlyTheWriteWindow() {
        val code = withoutComments(readRepoFile(storeRel))
        assertEquals(
            "force must gate the coalescing window and nothing else",
            1,
            count(code, "!force && !MirrorPlan.shouldWrite("),
        )
        assertTrue(
            "force must NOT bypass the previous/ point spacing: history density is a " +
                "property of time, not of who asked",
            code.contains("MirrorPlan.shouldMintPoint(manifest?.previousPoints"),
        )
    }

    // ------------------------------------- [R5-A] the I/O shell's own contracts
    //
    // `runLocked` needs a Context, so the JVM cannot instantiate it. What the pure
    // layer cannot prove is that the shell USES the decisions, which is what
    // these assertions pin.

    @Test
    fun everyWrittenFileIsReadBackBeforeItCountsAsSynced() {
        // [mirror-filetarget-1114] The one check that would have caught the
        // 2026-11-14 outage on day one. `write()` returning true only means the
        // bytes reached some stream; the retired MediaStore target returned true
        // while MediaProvider had renamed the row, so `current/` served 16-day-old
        // bytes with a healthy row and pending 0. Nothing in the pipeline
        // compared what was asked for against what is on disk.
        val code = withoutComments(readRepoFile(storeRel))
        val runLocked = braced(code, code.indexOf("private suspend fun runLocked("))
        assertTrue(
            "a write that is not read back is a write nobody verified",
            runLocked.contains("mirrorBytesMatch(target, rel, bytes)"),
        )
        val guard = runLocked.substring(runLocked.indexOf("mirrorBytesMatch(target, rel, bytes)"))
        assertTrue(
            "a mismatch must FAIL the sync — logging it and recording the file as synced " +
                "reproduces the silent-inconsistency the check exists to prevent",
            guard.contains("throw IOException(\"write not readable back:"),
        )
        val helper = braced(code, code.indexOf("private fun mirrorBytesMatch("))
        assertTrue(
            "the helper must compare the bytes, not just the existence of a file",
            helper.contains("contentEquals(expected)") && helper.contains("size =="),
        )
    }

    @Test
    fun theAutoSnapshotVerifiesItsWriteToo() {
        // `auto/` holds the daily full snapshot and the rotation below counts on
        // `list()` to decide what to keep. A snapshot that landed under another
        // name is invisible to the rotation — and `auto/` is the only external
        // copy of the whole app state, so "written but unreadable" is the same
        // class of lie as the 2026-11-14 mirror.
        val code = withoutComments(readRepoFile(storeRel))
        val auto = braced(code, code.indexOf("fun mirrorAutoSnapshot("))
        assertTrue(
            "the auto snapshot must be read back after writing",
            auto.contains("mirrorBytesMatch(target, rel, bytes)"),
        )
        val writeAt = auto.indexOf("target.write(rel, bytes)")
        val checkAt = auto.indexOf("mirrorBytesMatch(target, rel, bytes)")
        assertTrue(
            "the check must come AFTER the write",
            writeAt in 0 until checkAt,
        )
    }

    @Test
    fun theWriterIsAPlainFileTargetWithNoContentResolver() {
        // The whole class of defect this change removes is MediaProvider's
        // naming/visibility behaviour. If a future refactor routes the mirror
        // back through a ContentResolver, this assertion is the one that says
        // "then you must also re-pin overwrite/read/delete on a device".
        val target = withoutComments(readRepoFile(targetRel))
        assertFalse(
            "MirrorTarget must stay Android-free — that is what makes every one of its " +
                "behaviours testable in the JVM harness instead of only on a device",
            target.contains("android.content") || target.contains("android.net") ||
                target.contains("android.provider"),
        )
        val store = withoutComments(readRepoFile(storeRel))
        assertFalse(
            "no writer may go through MediaStore again: measured on device, its display-name " +
                "normalisation forked, blinded and silently-deleted the mirror for 16 days",
            store.contains("MediaStoreTarget("),
        )
        assertTrue(
            "openTarget must resolve through the pure decision, so the row and the writer " +
                "cannot disagree about which devices can write",
            store.contains("MirrorPlan.targetKind(") && store.contains("FileTarget(publicBase())"),
        )
    }

    @Test
    fun aMissingGrantIsARefusalNotAPrivateFallback() {
        // On 29+ the visible folder IS the feature. Quietly writing to the
        // app-specific dir instead would rebuild the original defect: a copy that
        // exists, but not where the user was told it is.
        val code = withoutComments(readRepoFile(storeRel))
        val openTarget = braced(code, code.indexOf("private fun openTargetWithKind("))
        assertFalse(
            "a missing grant must not construct a target",
            openTarget.contains("?: appExternalBase(") ||
                openTarget.contains("?: FileTarget("),
        )
        assertTrue(
            "a missing grant must be a logged refusal, not a silent null",
            openTarget.contains("currentTargetKind(app) ?: run") &&
                openTarget.contains("no mirror target"),
        )
        assertTrue(
            "only the APP_EXTERNAL kind may fall back to the app-specific dir",
            openTarget.contains("MirrorPlan.TargetKind.APP_EXTERNAL -> appExternalBase"),
        )
        assertTrue(
            "no legacy MediaStore path may survive the switch",
            !openTarget.contains("mediaStoreTarget("),
        )
    }

    @Test
    fun theLegacyTreeIsParkedAndTheLayoutIsVersioned() {
        // Migration, not deletion: a file deleted from the source since it was
        // mirrored exists only in `current/`, so retiring v1 must MOVE it.
        val code = withoutComments(readRepoFile(storeRel))
        val migrate = braced(code, code.indexOf("private fun migrateLegacyLayout("))
        assertTrue(
            "the old tree is renamed aside",
            migrate.contains("legacy.renameTo("),
        )
        assertFalse(
            "it must never be deleted — 49 MB of maybe-only-copy",
            migrate.contains("deleteRecursively()"),
        )
        val parkAt = migrate.indexOf("legacy.renameTo(")
        val rowsAt = migrate.indexOf("deleteLegacyRows(app)")
        assertTrue(
            "the tree must be parked BEFORE the rows are dropped: deleting a MediaStore row " +
                "also deletes its backing file, which would destroy what we are preserving",
            parkAt in 0 until rowsAt,
        )
        assertTrue(
            "the layout pref is written last, so an interrupted run resumes instead of " +
                "leaving two live trees",
            migrate.lastIndexOf("prefs.edit().putInt(KEY_LAYOUT") > rowsAt,
        )
    }

    @Test
    fun aNonPublicTargetNeverMarksTheLayoutAsMigrated() {
        // `File.exists()` answers false for a directory this process cannot read,
        // so a v1 tree behind a revoked grant is indistinguishable from "no v1
        // tree". Recording v2 from that state would strand the real files: the
        // migration would never run again after the user granted the access it
        // was waiting for, and the stale names would sit next to the new ones
        // forever.
        val code = withoutComments(readRepoFile(storeRel))
        val migrate = braced(code, code.indexOf("private fun migrateLegacyLayout("))
        val guardAt = migrate.indexOf("if (!targetIsPublic) return true")
        assertTrue(
            "the migration must refuse to touch the layout pref unless it is writing the " +
                "public tree",
            guardAt in 0 until migrate.indexOf("prefs.edit().putInt(KEY_LAYOUT"),
        )
        val sync = braced(code, code.indexOf("private suspend fun syncNow("))
        assertTrue(
            "the caller must derive the flag from the SAME read that produced the target, " +
                "not from a second grant check that could disagree",
            sync.contains("openTargetWithKind(app)") &&
                sync.contains("kind == MirrorPlan.TargetKind.PUBLIC_DOWNLOADS"),
        )
    }

    @Test
    fun aMigrationThatCannotParkTheOldTreeDoesNotSync() {
        // The park can fail (tree in use, volume read-only). The previous shape
        // logged, returned, and let syncNow write the plain layout into the very
        // tree the next run would park as "legacy".
        val code = withoutComments(readRepoFile(storeRel))
        val sync = braced(code, code.indexOf("private suspend fun syncNow("))
        val guardAt = sync.indexOf("if (!migrateLegacyLayout(app,")
        assertTrue("the migration verdict must gate the write", guardAt >= 0)
        assertTrue(
            "the guard must run before anything is written",
            guardAt < sync.indexOf("runLocked(app, target, force)"),
        )
        assertTrue(
            "a blocked migration must surface as a failure, not a silent no-op",
            sync.contains("Outcome.Failed(\"legacy mirror tree could not be moved aside\")"),
        )
    }

    @Test
    fun theRowCleanupStopsAtTheParkedTree() {
        // Deleting a MediaStore row deletes the file behind it. `auto/` was never
        // parked: those rows index the v1 daily snapshots we keep on purpose.
        val code = withoutComments(readRepoFile(storeRel))
        val cleanup = braced(code, code.indexOf("private fun deleteLegacyRows("))
        assertTrue(
            "only the parked tree's rows are ghosts",
            cleanup.contains("arrayOf(\"\$MIRROR_REL/%\")"),
        )
        assertFalse(
            "a backup-wide prefix would delete the v1 daily snapshots with their rows",
            cleanup.contains("\$ROOT_REL/%"),
        )
    }

    @Test
    fun unreadableSourceFilesAreNotRecordedAsSynced() {
        val code = withoutComments(readRepoFile(storeRel))
        val runLocked = braced(code, code.indexOf("private suspend fun runLocked("))
        assertTrue(
            "the write loop must collect what it could not read",
            runLocked.contains("skipped += e.relPath"),
        )
        assertTrue(
            "the manifest must be built through manifestEntries(…, skipped)",
            runLocked.contains("MirrorPlan.manifestEntries(") &&
                runLocked.contains("MirrorPlan.Manifest(now, nextEntries,"),
        )
        assertFalse(
            "recording the raw walk selection is the defect itself: a transient read failure " +
                "then reads as 'synced' forever, with pending 0 and a healthy row",
            runLocked.contains("MirrorPlan.Manifest(now, currentEntries,"),
        )
    }

    @Test
    fun aRecordedFailureSurvivesAnAttemptFreeOutcome() {
        val code = withoutComments(readRepoFile(storeRel))
        val record = braced(code, code.indexOf("private fun record("))
        assertTrue(
            "record() must route the stored text through MirrorPlan.nextRecordedOutcome, or " +
                "`throttled`/`busy` overwrite the row's only failure channel",
            record.contains("MirrorPlan.nextRecordedOutcome(p.getString(KEY_RESULT, null), text)"),
        )
        assertFalse(
            "writing the raw text back is exactly the C-01④ / C-03② defect",
            record.contains("putString(KEY_RESULT, text)"),
        )
    }
}
