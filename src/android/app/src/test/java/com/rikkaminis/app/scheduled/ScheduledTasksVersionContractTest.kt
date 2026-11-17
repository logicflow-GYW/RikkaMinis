package com.rikkaminis.app.scheduled

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * [WAVE-1118 D7 F-04] `version` has ONE contract, and both readers use it.
 *
 * The store validated `version` strictly when reading the live file
 * ([ScheduledTasksStore.declaredVersionOf]: positive INTEGER or bust) but read
 * the backup section with `JSONObject.optInt("version", -1)` — which truncates
 * `1.5` to `1`, i.e. to "this build's version". A package declaring a format
 * this build does not read therefore passed the restore gate
 * ([ScheduledTasksRestorePlan.decide]) and landed on disk, where the very next
 * [ScheduledTasksStore.loadLocked] would call the same bytes unreadable. Two
 * contracts for one field: import accepts what the reader refuses.
 *
 * The behaviour arms pin the strict reader with literals (never derived from
 * the code under test). The wiring arm pins that the import path CALLS it —
 * with a negative control that mutates a copy back to `optInt` and proves the
 * assertion can fail.
 */
class ScheduledTasksVersionContractTest {

    // ── behaviour: the strict reader ─────────────────────────────────────────

    @Test
    fun `a fractional version is not a version this build reads`() {
        assertEquals(
            "1.5 must not be truncated to 1 (that is what optInt did)",
            ScheduledTasksStore.INVALID_DECLARED_VERSION,
            ScheduledTasksStore.declaredVersionOf(JSONObject("""{"version":1.5}""")),
        )
        assertEquals(
            ScheduledTasksStore.INVALID_DECLARED_VERSION,
            ScheduledTasksStore.declaredVersionOf(JSONObject("""{"version":1.0001}""")),
        )
    }

    @Test
    fun `non-positive and non-numeric versions are rejected`() {
        for (raw in listOf("""{"version":0}""", """{"version":-3}""", """{"version":"v2"}""", """{"version":null}""", """{"version":true}""")) {
            assertEquals(
                "$raw must be rejected",
                ScheduledTasksStore.INVALID_DECLARED_VERSION,
                ScheduledTasksStore.declaredVersionOf(JSONObject(raw)),
            )
        }
    }

    @Test
    fun `a whole number is read as declared and an absent key is pre-versioning`() {
        assertEquals(1, ScheduledTasksStore.declaredVersionOf(JSONObject("""{"version":1}""")))
        assertEquals(2, ScheduledTasksStore.declaredVersionOf(JSONObject("""{"version":2}""")))
        assertEquals("2.0 is a whole number", 2, ScheduledTasksStore.declaredVersionOf(JSONObject("""{"version":2.0}""")))
        assertEquals(
            "a document without the key is the pre-versioning shape (accepted as v1)",
            null,
            ScheduledTasksStore.declaredVersionOf(JSONObject("""{"tasks":[]}""")),
        )
    }

    // ── wiring: the import path reads through the SAME gate ──────────────────

    @Test
    fun `importDocument declares the version through declaredVersionOf`() {
        assertWiring(importUsesStrictReader(storeSource()), audience = "importDocument")
    }

    @Test
    fun `negative control - the pre-fix optInt read is rejected by the same assertion`() {
        val mutated = storeSource().replace(
            "val declaredVersion = declaredVersionOf(document)",
            """val declaredVersion = if (document.has("version")) document.optInt("version", -1) else null""",
        )
        assertTrue("the negative control must actually mutate the source", mutated != storeSource())
        val ok = importUsesStrictReader(mutated)
        assertFalse(
            "a mutated copy that reads version with optInt must be rejected — " +
                "otherwise this arm proves nothing (reason: ${ok.reason})",
            ok.ok,
        )
    }

    // ── the checks, as functions of the body text ───────────────────────────

    private data class Verdict(val ok: Boolean, val reason: String)

    private fun importUsesStrictReader(source: String): Verdict {
        // Comments are not code: the KDoc here quotes the pre-fix `optInt` call,
        // and a comment must never satisfy (or break) a wiring assertion.
        val body = stripComments(bodyOf(source, "suspend fun importDocument("))
        if (body.contains("optInt(\"version\"")) {
            return Verdict(false, "importDocument still truncates version with optInt")
        }
        if (!body.contains("declaredVersionOf(document)")) {
            return Verdict(false, "importDocument does not read version through declaredVersionOf")
        }
        return Verdict(true, "ok")
    }

    /** Drop `//` and `/* */` comments, keeping string literals intact. */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inStr = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; out.append(c); i++ }
                c == '/' && text.getOrNull(i + 1) == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) i++
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun assertWiring(verdict: Verdict, audience: String) {
        assertTrue("$audience: ${verdict.reason}", verdict.ok)
    }

    private fun storeSource(): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTasksStore.kt")

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Function body: balanced braces from the first `{` after [header]. */
    private fun bodyOf(source: String, header: String): String {
        val at = source.indexOf(header)
        assertTrue("anchor missing: $header", at >= 0)
        val open = source.indexOf('{', at)
        assertTrue("no body brace after $header", open >= 0)
        var depth = 0
        var inStr = false
        var escaped = false
        var i = open
        while (i < source.length) {
            val c = source[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(at, i + 1)
                    }
                }
            }
            i++
        }
        error("unbalanced braces after $header")
    }
}
