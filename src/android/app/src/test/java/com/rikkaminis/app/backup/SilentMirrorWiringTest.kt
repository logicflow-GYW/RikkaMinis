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
    // MediaStoreTarget needs a ContentResolver and runLocked needs a Context, so
    // the JVM cannot instantiate them. What the pure layer cannot prove is that
    // the shell USES the decisions, which is what these three assertions pin.

    @Test
    fun aFailedOverwriteDropsTheTruncatedRow() {
        val code = withoutComments(readRepoFile(storeRel))
        assertTrue(
            "the existing-row branch must go through overwrite(): `wt` truncates at OPEN, so " +
                "an inline write leaves a 0-byte or half-written document under a visible name",
            code.contains("return overwrite(existing, relPath, bytes)"),
        )
        val overwrite = braced(code, code.indexOf("private fun overwrite("))
        assertEquals(
            "both failure shapes — a thrown write and a null stream — must reach " +
                "dropHalfWritten(), or one of them still leaves the corpse",
            2,
            count(overwrite, "dropHalfWritten("),
        )
        val drop = braced(code, code.indexOf("private fun dropHalfWritten("))
        assertTrue(
            "the failure path must DELETE the row, so the next sync rebuilds it through the " +
                "IS_PENDING insert path",
            drop.contains("resolver.delete(existing, null, null)"),
        )
        assertTrue("dropping the row is still a failed write", drop.contains("return false"))
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
