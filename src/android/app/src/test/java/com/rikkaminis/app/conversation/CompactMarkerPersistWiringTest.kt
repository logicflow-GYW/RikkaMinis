package com.rikkaminis.app.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [meta-audit-1029 R3 B11] F-02 — a marker that did not persist must fail the
 * compact instead of being published as in-memory compact state.
 *
 * The [audit-0917] contract ("only publish the in-memory compact state when
 * the marker actually persisted … not advertised as durable") was quoted but
 * not enforced: the failure branch logged a warning and fell through to
 * publish anyway. These assertions pin the enforced shape on the
 * comment-stripped source, so the publish cannot silently re-attach itself to
 * the failure branch.
 */
class CompactMarkerPersistWiringTest {

    @Test
    fun failedMarkerInsertThrowsBeforeAnythingIsPublished() {
        val code = code(ORCH_REL)
        val at = requireCall(code, "chatRepository.dao.insertCompactMarker(marker)")
        val publishAt = code.indexOf("_compactSummary.value = summary", at)
        assertTrue("_compactSummary publish not found after the marker insert", publishAt > at)
        val between = code.substring(at, publishAt)
        assertTrue(
            "the marker write failure does not throw — it falls through and publishes "
                + "_compactSummary/_cachedLatestMarker for a compact the next reload will "
                + "silently revert",
            Regex("""throw\s+IllegalStateException\(""").containsMatchIn(between),
        )
    }

    @Test
    fun theBestEffortFallbackShapeIsGone() {
        val code = code(ORCH_REL)
        assertFalse(
            "the pre-fix boolean flag is back — a failed insert must not be a boolean",
            "markerSaved" in code,
        )
        assertFalse(
            "the pre-fix in-memory-only fallback is back — it is exactly the "
                + "'compacted in memory, full history after reload' state the "
                + "[audit-0917] contract forbids",
            "keeping the summary in memory only" in code,
        )
    }

    @Test
    fun cachedMarkerIsOnlyAssignedAfterThePublishLine() {
        val code = code(ORCH_REL)
        val at = requireCall(code, "chatRepository.dao.insertCompactMarker(marker)")
        val publishAt = code.indexOf("_compactSummary.value = summary", at)
        val cachedAt = code.indexOf("_cachedLatestMarker = marker", publishAt)
        assertTrue(
            "_cachedLatestMarker is assigned before the summary publish — the failure "
                + "path can no longer be distinguished from success",
            cachedAt > publishAt,
        )
        val drainAt = code.indexOf("compactSucceeded = true", cachedAt)
        assertTrue("compactSucceeded must only be set for a persisted compact", drainAt > cachedAt)
    }

    @Test
    fun manualCompactsStillSeeTheFailureCard() {
        // The throw lands in compactAll's own catch, which surfaces
        // sysmsg_compact_failed for !silent — assert the card is still wired.
        val code = code(ORCH_REL)
        assertTrue(
            "the failure card disappeared — a manual /compact with a failed marker "
                + "write must surface a user-visible error",
            "R.string.sysmsg_compact_failed" in code,
        )
    }

    // ── helpers (independent of the scan gates on purpose) ────────────

    private fun requireCall(code: String, needle: String): Int {
        val at = code.indexOf(needle)
        assertTrue("call site `$needle` not found — re-anchor this test", at >= 0)
        return at
    }

    private fun code(relative: String): String = stripComments(readRepoFile(relative))

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

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ORCH_REL = "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt"
    }
}
