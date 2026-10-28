package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 D4 F-02] Every restore that has to build a payload must claim the
 * shared busy gate BEFORE the payload, not after it.
 *
 * `BackupSettingsScreen` is not JVM-instantiable (it needs a real `Context`, a WebDAV
 * client and the auto-backup directory), so the wiring is pinned by source-shape
 * assertions — the same technique `BackupFetchBusyGateTest` uses for its own handler.
 *
 * The defect in one sentence: `restoreWithSnapshot` was the ONLY claim entry, and it
 * claims at the END — after the WebDAV download, after `file.readText()`, after
 * `String(bytes, …)` — so the window in which a second 70MB payload would be allocated
 * was unguarded, and a restore that arrived second was refused by the very gate it should
 * have been holding (bytes paid for, `backup_err_busy` shown). The fix moves the claim to
 * before the payload and hands it over (`preClaimed`), which is also why the handoff must
 * be in the same Main frame as the read result: no suspension, no lost claim.
 */
class RestorePayloadBusyGateTest {

    /** How far back a handler's claim may sit from its payload. Comments are stripped
     *  first, so this is a generous bound that still cannot reach a sibling handler. */
    private val CLAIM_WINDOW = 1200

    private val SNAPSHOT_ROW_BODY =
        "onClick = if (operationBusy || minisbakConverting) null else ({\n" +
            "                                snapshotRestoreTarget = file\n" +
            "                            }),"

    private val screen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt")
    }

    /** Comment-stripped: the fix's own prose names every verb below. */
    private val code: String by lazy { withoutComments(screen) }

    // -- ① a single claim entry exists and it is the shared gate --

    @Test
    fun claimHelperExists() {
        assertTrue(
            "the screen must expose one claim helper for restore payloads",
            code.contains("val claimRestorePayload"),
        )
        assertTrue(
            "the claim helper must set the shared operationBusy gate",
            braced(code, code.indexOf("val claimRestorePayload")).contains("operationBusy = true"),
        )
    }

    @Test
    fun claimHelperUsesTheSharedPredicate() {
        assertTrue(
            "the claim helper must go through canStartRestorePayload (not a bare check)",
            code.contains("canStartRestorePayload("),
        )
        assertTrue(
            "the screen must import/call the shared predicate with both flags",
            code.contains("minisbakConverting = minisbakConverting"),
        )
    }

    // -- ② every payload-building restore claims BEFORE the payload --

    @Test
    fun manualWebDavRestoreClaimsBeforeTheDownload() {
        assertClaimsBeforePayload(
            "WebDavSync.restore(",
            "the manual WebDAV restore must claim the gate before the download",
        )
    }

    @Test
    fun autoWebDavRestoreClaimsBeforeTheDownload() {
        assertClaimsBeforePayload(
            "WebDavSync.restoreAuto(",
            "the auto-backup WebDAV restore must claim the gate before the download",
        )
    }

    @Test
    fun snapshotDialogClaimsBeforeTheRead() {
        assertClaimsBeforePayload(
            "snap.readText()",
            "the local snapshot restore must claim the gate before readText()",
        )
        assertClaimsBeforePayload(
            "file.readText()",
            "the snapshot confirm dialog must claim the gate before readText()",
        )
    }

    @Test
    fun saflImportClaimsBeforeTheDecode() {
        assertClaimsBeforePayload(
            "restoreWithSnapshot(String(bytes, Charsets.UTF_8), true)",
            "the SAF import must hand the payload over with the claim it took first",
        )
    }

    // -- ③ the handoff: a claimed restore is never refused by its own gate --

    @Test
    fun handoffSkipsTheRecheck() {
        assertTrue(
            "restoreWithSnapshot must take preClaimed so a claimed restore is not refused " +
                "by the gate it already holds",
            code.contains("doRestore@{ json, preClaimed ->"),
        )
        assertTrue(
            "the recheck must be skipped for a pre-claimed restore",
            code.contains("if (!preClaimed && operationBusy)"),
        )
    }

    @Test
    fun everyPayloadHandoffIsPreClaimed() {
        val calls = Regex("restoreWithSnapshot\\((?:[^()]*(?:\\([^()]*\\))?)*\\)")
            .findAll(code).map { it.value }.toList()
        assertTrue("no restoreWithSnapshot call found (fail-closed)", calls.isNotEmpty())
        for (c in calls) {
            assertTrue(
                "every payload restore must hand off the claim, got: $c",
                c.endsWith(", true)"),
            )
        }
    }

    @Test
    fun aFailedPayloadReleasesTheGate() {
        assertTrue(
            "an un-handoff'd payload must lower the gate again (releaseRestorePayload)",
            code.contains("releaseRestorePayload()"),
        )
    }

    // -- ④ the rows that open the payload cannot be tapped into a second one --

    @Test
    fun restoreRowsAreDisabledWhileBusy() {
        // Both snapshot rows (local + auto-backup), pinned by their own body: the import
        // row shares the `if (…) null else ({` prefix but launches the picker, so a bare
        // `contains` on the prefix would stay green after both rows regress.
        assertEquals(
            "both snapshot rows must be disabled while a payload is in flight",
            2,
            countOf(code, SNAPSHOT_ROW_BODY),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun countOf(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    /**
     * The claim must be taken in the same handler as the payload — not merely somewhere
     * earlier in the file (the helper's own definition would satisfy that, which is the
     * false-green this window exists to prevent).
     */
    private fun assertClaimsBeforePayload(payloadMarker: String, why: String) {
        val i = code.indexOf(payloadMarker)
        assertTrue("$why — anchor `$payloadMarker` not found (fail-closed)", i >= 0)
        val window = code.substring(maxOf(0, i - CLAIM_WINDOW), i)
        assertTrue(why, window.contains("claimRestorePayload()"))
    }

    /** Substring from [marker] through the brace that closes the first block
     *  opened after it. */
    private fun braced(src: String, marker: Int): String {
        val open = src.indexOf('{', marker)
        assertTrue("no block opened at/after offset $marker", open >= 0)
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
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            out.append(src, i, i + 2)
                            i += 2
                            continue
                        }
                        out.append(src[i])
                        if (src[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(
            System.getProperty("user.dir") ?: error("user.dir unset"),
        )
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
