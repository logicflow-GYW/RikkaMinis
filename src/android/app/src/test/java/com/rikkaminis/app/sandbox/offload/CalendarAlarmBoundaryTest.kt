package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1038 FIX-1 / A-04] `--alarm` is read by two writers (`create`,
 * `update`) through one question — "is it a number?" — which conflated "the flag
 * was not supplied" with "the flag was supplied and is unusable":
 *
 *   - `create --alarm -5` wrote no reminder and still answered exit 0;
 *   - `update --id X --alarm -5` wrote nothing at all (no column, no reminder)
 *     and still answered `{"updated": true, "rows": 0}` — the honesty comment in
 *     that branch reasoned about `values == 0 && alarm == null` and never
 *     imagined a negative alarm.
 *
 * [resolveAlarmSelector] gives the unusable case a verdict of its own, and the
 * source shape below pins that BOTH writers read that one verdict — the two
 * boundaries are the defect, not either one alone.
 */
class CalendarAlarmBoundaryTest {

    private val handler =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/CalendarOffloadHandler.kt"

    // ── the classification matrix ───────────────────────────────────────────

    @Test
    fun `an absent alarm is absent`() {
        assertEquals(AlarmSelector.Absent, resolveAlarmSelector(null))
    }

    @Test
    fun `zero and positive minutes are a reminder lead time`() {
        assertEquals(AlarmSelector.Minutes(0), resolveAlarmSelector("0"))
        assertEquals(AlarmSelector.Minutes(30), resolveAlarmSelector("30"))
    }

    @Test
    fun `a negative alarm is unreadable, not absent`() {
        assertEquals(AlarmSelector.Unreadable("-5"), resolveAlarmSelector("-5"))
        // The old shape answered `null` here, exactly as if the flag were missing.
        assertFalse(resolveAlarmSelector("-5") is AlarmSelector.Absent)
        assertFalse(resolveAlarmSelector("-5") is AlarmSelector.Minutes)
    }

    @Test
    fun `a non-numeric alarm is unreadable too`() {
        assertEquals(AlarmSelector.Unreadable("abc"), resolveAlarmSelector("abc"))
        assertEquals(AlarmSelector.Unreadable("30m"), resolveAlarmSelector("30m"))
        assertEquals(AlarmSelector.Unreadable(""), resolveAlarmSelector(""))
    }

    @Test
    fun `the refusal quotes the value and names the flag`() {
        val message = alarmRefusalMessage("update", "-5")
        assertTrue(message, message.contains("android-calendar update"))
        assertTrue(message, message.contains("--alarm"))
        assertTrue(message, message.contains("-5"))
        assertTrue(message, message.endsWith("\n"))
        // Both verbs share one wording, so neither can drift.
        assertTrue(alarmRefusalMessage("create", "-5").contains("android-calendar create"))
    }

    // ── both writers read the same verdict ─────────────────────────────────

    @Test
    fun `create and update both decide through the shared classifier`() {
        val src = stripComments(readHandler())
        val create = braceSlice(src, "private fun doCreate(")
        val update = braceSlice(src, "private fun doUpdate(")
        assertTrue(create, create.contains("resolveAlarmSelector("))
        assertTrue(update, update.contains("resolveAlarmSelector("))
        // The old per-writer read, which answered two questions with one null.
        assertFalse("create must not parse --alarm on its own", create.contains("getInt(\"alarm\")"))
        assertFalse("update must not parse --alarm on its own", update.contains("getInt(\"alarm\")"))
    }

    @Test
    fun `both writers refuse an unusable alarm before writing`() {
        val src = stripComments(readHandler())
        for (marker in listOf("private fun doCreate(", "private fun doUpdate(")) {
            val body = braceSlice(src, marker)
            assertTrue(
                "$marker must refuse an unreadable alarm",
                Regex("""AlarmSelector\.Unreadable\s*->\s*return NativeOffloadResult\(2, alarmRefusalMessage\(""")
                    .containsMatchIn(body),
            )
        }
    }

    @Test
    fun `the non-negative bound lives in the classifier, not in each writer`() {
        val src = stripComments(readHandler())
        val classifier = braceSlice(src, "internal fun resolveAlarmSelector(")
        assertTrue(classifier, classifier.contains(">= 0"))
        assertTrue(classifier, classifier.contains("toIntOrNull()"))
        // The old per-writer re-check that let create and update disagree.
        assertFalse("a per-writer `>= 0` guard can drift again", src.contains("alarmMinutes >= 0"))
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun readHandler(): String {
        val f = File(repoRoot(), handler)
        if (!f.exists()) throw FileNotFoundException(f.path)
        return f.readText()
    }

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, handler).exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** `src` from the first `{` after [marker], balanced. */
    private fun braceSlice(src: String, marker: String): String {
        val at = src.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        val open = src.indexOf('{', at)
        assertTrue("no block after: $marker", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(at, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced block after: $marker")
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i = if (i + 2 > src.length) src.length else i + 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
