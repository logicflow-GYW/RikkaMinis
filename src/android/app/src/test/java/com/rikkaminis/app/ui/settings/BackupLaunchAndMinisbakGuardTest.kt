package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WAVE-1118 D4 F-05 + F-07] Two ways the backup screen could end up lying to
 * the user about what just happened:
 *
 *  - **F-05**: `runExport` claims `operationBusy` synchronously and then launches
 *    the SAF picker. If `launch` throws (no document provider on the device, or
 *    a launcher that is not registered yet) the result callback never fires and
 *    nothing else lowers the claim — every export/import/restore row on the
 *    screen stays disabled for the life of the screen.
 *  - **F-07**: the `.minisbak` picker callback refused a busy gate *silently*,
 *    while the JSON branch of the SAME callback reports `backup_err_busy`. Half
 *    an action reported, half vanished.
 *
 * Shape-level because `BackupSettingsScreen` needs a real Context, a WebDAV
 * client and the auto-backup directory (house precedent:
 * `RestorePayloadBusyGateTest`).
 */
class BackupLaunchAndMinisbakGuardTest {

    private val screen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/BackupSettingsScreen.kt")
    }

    private val code: String by lazy { withoutComments(screen) }

    // -- F-05: the launch cannot strand the claim --

    @Test
    fun theExportLaunchIsGuarded() {
        val runs = Regex("exportLauncher\\.launch\\(").findAll(code).map { it.range.first }.toList()
        assertTrue("no exportLauncher.launch( found (fail-closed)", runs.isNotEmpty())
        for (at in runs) {
            // The `try {` must open in the same handler, right before the launch.
            val window = code.substring(maxOf(0, at - 600), at)
            assertTrue(
                "exportLauncher.launch(...) must sit inside a try block — otherwise a throwing " +
                    "launcher keeps operationBusy forever",
                window.contains("try {"),
            )
        }
    }

    @Test
    fun aFailedLaunchReleasesTheClaimAndSaysWhy() {
        val at = code.indexOf("exportLauncher.launch(")
        assertTrue("no exportLauncher.launch( found (fail-closed)", at > 0)
        val after = code.substring(at, minOf(code.length, at + 600))
        assertTrue(
            "the catch arm must lower the claim through the ONE shared release site " +
                "(a hand-written `operationBusy = false` is what the busy-gate count pins against)",
            after.contains("releaseRestorePayload()"),
        )
        assertTrue(
            "the catch arm must report the failure instead of dying inside onClick",
            after.contains("errorMessage = String.format(errWriteFmt"),
        )
    }

    // -- F-07: a refused .minisbak import says so --

    @Test
    fun theMinisbakEntryUsesTheSharedPredicate() {
        val body = blockAt(code, "val convertAndRestore")
        assertTrue(
            "convertAndRestore must gate on canStartRestorePayload — the same predicate the " +
                "JSON branch's claim uses",
            body.contains("canStartRestorePayload("),
        )
        assertTrue(
            "the refusal must report backup_err_busy instead of returning silently",
            body.contains("R.string.backup_err_busy"),
        )
    }

    @Test
    fun theSilentRefusalShapeIsGone() {
        // The pre-fix line, pinned as an absence: re-adding it must redden here.
        assertEquals(
            "a bare `if (operationBusy || minisbakConverting) return@convertAndRestore` is the defect",
            0,
            countOf(code, "if (operationBusy || minisbakConverting) return@convertAndRestore"),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Substring from [marker] through the brace that closes the first block
     *  opened after it. */
    private fun blockAt(src: String, marker: String): String {
        val at = src.indexOf(marker)
        assertTrue("anchor `$marker` not found (fail-closed)", at >= 0)
        var depth = 0
        val open = src.indexOf('{', at)
        assertTrue("no block opened at/after `$marker`", open >= 0)
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced block opened after `$marker`")
    }

    private fun countOf(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

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
