package com.rikkaminis.app.ui.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/r3-data-1119 F-16] The wiring: `restoreOfficial` must actually consult
 * the decision.
 *
 * [MirrorRestorePlanTest] proves the decision is right. It cannot prove the
 * caller uses it — and the defect WAS the caller: a green decision function
 * next to an unchanged `if (!bakFile.exists()) return` would read as fixed
 * while the sandbox stayed pinned to the custom registry.
 *
 * Judged on comment-stripped source, scoped to the function body, so the
 * fix's own KDoc (which names the pre-fix shape) cannot satisfy or break it.
 */
class MirrorRestoreWiringTest {

    private fun screenSource(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/MirrorSettingsScreen.kt",
            )
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("MirrorSettingsScreen.kt not found")
    }

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var state: String? = null
        while (i < text.length) {
            val c = text[i]
            val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
            when (state) {
                null -> when {
                    two == "//" -> { state = "line"; i += 2 }
                    two == "/*" -> { state = "block"; i += 2 }
                    c == '"' -> { state = "str"; out.append('"'); i += 1 }
                    else -> { out.append(c); i += 1 }
                }
                "line" -> {
                    if (c == '\n') { state = null; out.append('\n') }
                    i += 1
                }
                "block" -> if (two == "*/") { state = null; i += 2 } else i += 1
                "str" -> when {
                    c == '\\' && i + 1 < text.length -> { out.append(text[i]).append(text[i + 1]); i += 2 }
                    c == '"' -> { state = null; out.append('"'); i += 1 }
                    else -> { out.append(c); i += 1 }
                }
                else -> i += 1
            }
        }
        return out.toString()
    }

    private fun restoreBody(): String {
        val source = stripComments(screenSource().readText())
        val at = source.indexOf("private fun restoreOfficial(context: Context, category: MirrorCategory) {")
        assertTrue("restoreOfficial not found", at >= 0)
        val open = source.indexOf('{', at)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("restoreOfficial body is unbalanced")
    }

    @Test
    fun `restoreOfficial consults the decision`() {
        val body = restoreBody()

        assertTrue(
            "restoreOfficial must call decideMirrorRestore",
            body.contains("decideMirrorRestore("),
        )
        assertTrue(
            "the no-backup branch must write the official body",
            body.contains("officialConfigContent(category)"),
        )
    }

    @Test
    fun `the skip-on-missing-backup early return is gone`() {
        val body = restoreBody()

        assertFalse(
            "a bare `if (!bakFile.exists()) { ... return }` is exactly the defect " +
                "(the switch reads official while the file stays custom)",
            body.contains("if (!bakFile.exists()) {\n            Log.i") &&
                body.contains("skipping restore"),
        )
        assertFalse(
            "restoreOfficial must not skip solely because the .bak is absent",
            body.contains("No .bak for ${'$'}{category.configPath}, skipping restore"),
        )
    }

    @Test
    fun `every restore action is handled`() {
        val body = restoreBody()

        for (action in MirrorRestoreAction.entries) {
            assertTrue(
                "MirrorRestoreAction.$action must have a branch — an unhandled action " +
                    "silently does nothing (which is the original bug shape)",
                body.contains("MirrorRestoreAction.$action"),
            )
        }
    }
}
