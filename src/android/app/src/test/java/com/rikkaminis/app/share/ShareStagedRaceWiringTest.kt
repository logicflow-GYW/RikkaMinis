package com.rikkaminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-A3 C-09① / F-07] Shape assertions for the staged-share race fix against
 * the REAL sources. The decisions themselves are unit-tested in
 * [ShareStagedInFlightTest]; what cannot run on the JVM is the wiring, and each
 * of the four wiring facts below is one half of a defect:
 *
 *  1. **incremental staging** (C-09①) — the item list must be appended per file
 *     while the extraction runs, not assigned once after it returned. The old
 *     shape saved an EMPTY list on a mid-extraction rotation, so the rebuilt
 *     instance re-extracted the intent (second copy + second ledger credit) and
 *     orphaned the files already on disk.
 *  2. **claim before create** (F-07) — `StagedInFlightFiles.begin` before the
 *     file exists, so a concurrent cleanup can never see an unclaimed file.
 *  3. **list before snapshot** (F-07) — the ordering that closes the reverse
 *     window (see [shouldKeepStagedFile]).
 *  4. **release at hand-off / discard only** (F-07) — a name released earlier
 *     re-opens the race; one never released protects garbage forever.
 *
 * Expectations are literals on purpose (a renamed helper is a deliberate act
 * and must update this file in the same commit).
 */
class ShareStagedRaceWiringTest {

    private val activity = readRepoFile(
        "app/src/main/java/com/rikkaminis/app/share/ShareReceiverActivity.kt",
    )
    private val store = readRepoFile(
        "app/src/main/java/com/rikkaminis/app/share/SharedShareStore.kt",
    )

    // ─── 1. incremental staging (C-09①) ─────────────────────────────────────

    @Test
    fun `staged items are appended in place instead of assigned after extraction`() {
        assertTrue(
            "extractedItems must be the in-place list the handlers append to",
            activity.contains("private val extractedItems: MutableList<PendingShare.Item> ="),
        )
        // Writers are on Dispatchers.IO, the reader is the main thread.
        assertTrue(
            "the list must be synchronized — onSaveInstanceState reads it while extraction writes",
            activity.contains("java.util.Collections.synchronizedList(mutableListOf())"),
        )
        assertFalse(
            "the single post-extraction assignment is the defect shape (empty list saved on rotation)",
            activity.contains("extractedItems = items"),
        )
        // Every action handler must be handed the live list (not a local one):
        // `items` in extractItems is the field itself, not a copy.
        assertTrue(
            "extractItems must alias the live accumulator, not build a second list",
            hasLine(activity, "val items = extractedItems"),
        )
        assertTrue(
            "ACTION_SEND must append into that live list",
            activity.contains("Intent.ACTION_SEND -> handleSingleSend(intent, items)"),
        )
        assertTrue(
            "ACTION_SEND_MULTIPLE must append into that live list",
            activity.contains("Intent.ACTION_SEND_MULTIPLE -> handleMultipleSend(intent, items)"),
        )
        assertTrue(
            "ACTION_VIEW must append into that live list",
            activity.contains("Intent.ACTION_VIEW -> handleView(intent, items)"),
        )
        // extractItems must no longer build a private list to return.
        val extract = activity.substringAfter("private suspend fun extractItems()")
        assertTrue(
            "extractItems must delegate to the handlers and return nothing",
            extract.trimStart().startsWith("{") && extract.contains("extractedItems"),
        )
        assertFalse(
            "extractItems must not allocate its own list — the field is the accumulator",
            extract.substringBefore("Intent.ACTION_SEND").contains("mutableListOf<PendingShare.Item>()"),
        )
    }

    @Test
    fun `saved state stores a snapshot taken under the list monitor`() {
        val save = activity.substringAfter("override fun onSaveInstanceState")
        assertTrue(
            "onSaveInstanceState must snapshot via stagedItemsSnapshot()",
            save.contains("val staged = stagedItemsSnapshot()"),
        )
        assertTrue(
            "the pending share must be built from the snapshot, not the live list",
            save.contains("PendingShare(staged, System.currentTimeMillis())"),
        )
        val snapshot = activity.substringAfter("private fun stagedItemsSnapshot()")
        assertTrue(
            "the snapshot must copy under synchronized(extractedItems)",
            snapshot.contains("synchronized(extractedItems) { extractedItems.toList() }"),
        )
    }

    @Test
    fun `a restored staged set is re-registered as in flight before it is handed off`() {
        val restore = activity.substringAfter("if (restoredItems.isNotEmpty()) restoreStagedItems")
        assertTrue(
            "a rebuild must seed the item list before extracting",
            restore.isNotEmpty(),
        )
        val body = activity.substringAfter("private fun restoreStagedItems")
        assertTrue(
            "restored items repopulate the field in place",
            body.contains("extractedItems.addAll(items)"),
        )
        assertTrue(
            "restored files have no pending-share entry yet — they must be claimed",
            body.contains("StagedInFlightFiles.begin(item.value)"),
        )
    }

    // ─── 2. claim before create (F-07) ───────────────────────────────────────

    @Test
    fun `the stream copy claims its name before the destination file is created`() {
        val copy = activity.substringAfter("private fun copyUriToStaging")
        val begin = copy.indexOf("StagedInFlightFiles.begin(name)")
        val dest = copy.indexOf("val dest = File(SharedShareStore.sharedFileDirectory(this), name)")
        assertTrue("copyUriToStaging must claim the staged name", begin >= 0)
        assertTrue("the destination file must still be created", dest >= 0)
        assertTrue("the claim must precede the file creation ($begin vs $dest)", begin < dest)
        // The failure arm must release: the core deleted the partial file.
        val failed = copy.indexOf("is SharedStreamCopy.Failed -> {")
        assertTrue("the copy failure arm must still exist", failed >= 0)
        assertTrue(
            "a failed copy must release its claim",
            copy.substring(failed).contains("StagedInFlightFiles.end(name)"),
        )
    }

    @Test
    fun `the text attachment claims its name before the write and releases on failure`() {
        val send = activity.substringAfter("private fun handleSingleSend")
        val begin = send.indexOf("StagedInFlightFiles.begin(name)")
        val write = send.indexOf("dest.writeText")
        assertTrue("the AttachText arm must claim the staged name", begin >= 0)
        assertTrue("the AttachText arm must still write the text", write >= 0)
        assertTrue("the claim must precede the write ($begin vs $write)", begin < write)
        val staged = send.indexOf("val staged = try {")
        assertTrue("the write must still be wrapped for failure cleanup", staged >= 0)
        assertTrue(
            "a failed text write must release its claim",
            send.substring(staged, send.indexOf("if (staged) {")).contains(
                "StagedInFlightFiles.end(name)",
            ),
        )
        // The credit must still land only after a successful write.
        val credit = send.indexOf("stagedBytes.credit(textBytes)")
        assertTrue("the credit must stay after the write", credit > write)
    }

    // ─── 3. list before snapshot (F-07) ──────────────────────────────────────

    @Test
    fun `cleanSharedFiles lists the directory before snapshotting the keep sets`() {
        val clean = store.substringAfter("fun cleanSharedFiles(context: Context)")
        val listed = clean.indexOf("val listed = dir.listFiles()")
        val inFlight = clean.indexOf("StagedInFlightFiles.snapshot()")
        assertTrue("the directory listing must still happen", listed >= 0)
        assertTrue("the in-flight keep set must be read", inFlight >= 0)
        assertTrue(
            "the listing must come FIRST — otherwise a file created in between is deleted " +
                "while its producer is still copying into it ($listed vs $inFlight)",
            listed < inFlight,
        )
        assertTrue(
            "the per-entry decision must go through the shared keep rule",
            clean.contains("shouldKeepStagedFile(f.name, referenced, inFlight)"),
        )
        // The old unconditional `f.name in referenced` branch must be gone: it
        // is the exact shape that deleted in-flight files.
        assertFalse(
            "cleanSharedFiles must not decide keep/no-keep from `referenced` alone",
            clean.contains("if (f.name in referenced) {"),
        )
    }

    // ─── 4. release at hand-off / discard only (F-07) ────────────────────────

    @Test
    fun `in flight names are released after the hand-off save, not before it`() {
        val flow = activity.substringAfter("private fun finishWithAttachmentFlow")
        val save = flow.indexOf("SharedShareStore.savePendingShare(")
        val release = flow.indexOf("StagedInFlightFiles.endAll(attachmentNames(items))")
        assertTrue("the hand-off must still persist the pending share", save >= 0)
        assertTrue("the hand-off must release the in-flight names", release >= 0)
        assertTrue(
            "releasing before the save would re-open the deletion race ($save vs $release)",
            release > save,
        )
    }

    @Test
    fun `the provider-import discard path releases each consumed name`() {
        val discard = activity.substringAfter("private fun discardStagedAttachments")
        assertTrue("the discard path must still delete the staged files", discard.contains("f.delete()"))
        assertTrue(
            "every consumed attachment must leave the in-flight set",
            discard.contains("StagedInFlightFiles.end(item.value)"),
        )
    }

    @Test
    fun `the in-flight registry is process-lifetime and its snapshot is a copy`() {
        val ledger = readRepoFile("app/src/main/java/com/rikkaminis/app/share/StagedBytesLedger.kt")
        assertTrue(
            "the registry must be a singleton object (producers and the cleaner share it)",
            ledger.contains("internal object StagedInFlightFiles"),
        )
        assertTrue(
            "the registry must be thread-safe — producers run on Dispatchers.IO",
            ledger.contains("ConcurrentHashMap.newKeySet"),
        )
        val snapshot = ledger.substringAfter("fun snapshot(): Set<String>")
        assertEquals(
            "the snapshot must copy (a live view would mutate during the walk)",
            true,
            snapshot.substringBefore("\n").contains("names.toSet()"),
        )
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    /**
     * True iff some line of [source] is EXACTLY [statement] (leading/trailing
     * whitespace ignored). `contains` would accept `val items = extractedItems
     * .toMutableList()` — the very pre-fix shape ("a private copy the handlers
     * fill") that this assertion exists to reject.
     */
    private fun hasLine(source: String, statement: String): Boolean =
        source.lineSequence().any { it.trim() == statement }

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
}
