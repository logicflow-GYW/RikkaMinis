package com.rikkaminis.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/r3-data-1119 F-08 / F-09] The wiring: the two call sites must actually
 * go through [SkillTreeGuard].
 *
 * [SkillTreeGuardTest] proves the primitive is safe. It cannot prove the
 * repository uses it — and the whole finding was "the code did the unsafe
 * thing", so a green primitive plus an untouched `deleteRecursively()` would
 * read as fixed while the defect stayed live.
 *
 * Judged on COMMENT-STRIPPED source: the fix's own KDoc names the pre-fix
 * shape, so a naive `contains` would either pass on a comment (unsafe code
 * plus an explanatory note) or fail on one (safe code plus an explanatory
 * note). Only code counts.
 */
class SkillTreeGuardWiringTest {

    private fun repoSource(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt",
            )
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        // Fail closed: a missing source is a broken harness, not a pass.
        throw AssertionError("SkillRepository.kt not found from ${System.getProperty("user.dir")}")
    }

    /** Blank out line and block comments; string literals are kept. */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var state: String? = null
        while (i < text.length) {
            val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
            when (state) {
                null -> when {
                    two == "//" -> { state = "line"; i += 2 }
                    two == "/*" -> { state = "block"; i += 2 }
                    else -> { out.append(text[i]); i += 1 }
                }
                "line" -> {
                    if (text[i] == '\n') { state = null; out.append('\n') }
                    i += 1
                }
                "block" -> {
                    if (two == "*/") { state = null; i += 2 } else i += 1
                }
                else -> i += 1
            }
        }
        return out.toString()
    }

    /** The declaration starting at [signature] plus its brace body (or its
     *  expression body, for `fun f(...) = expr`). */
    private fun bodyOf(source: String, signature: String): String {
        val at = source.indexOf(signature)
        assertTrue("`$signature` not found in SkillRepository.kt", at >= 0)
        val open = source.indexOf('{', at)
        val arrow = source.indexOf('=', at)
        val lineEnd = source.indexOf('\n', at)
        // Expression body: `= ...` on the declaration line, no `{` before it.
        if (arrow in 0 until lineEnd && (open < 0 || arrow < open)) {
            return source.substring(at, lineEnd)
        }
        assertTrue("`$signature` has no body brace", open > 0)
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
        throw AssertionError("`$signature` body is unbalanced")
    }

    @Test
    fun `delete goes through the guarded delete, not deleteRecursively`() {
        val body = bodyOf(stripComments(repoSource()), "fun delete(id: String) {")
        assertTrue(
            "delete() must call SkillTreeGuard.deleteTree",
            body.contains("SkillTreeGuard.deleteTree("),
        )
        assertFalse(
            "delete() must not use the symlink-following deleteRecursively()",
            body.contains("deleteRecursively()"),
        )
    }

    @Test
    fun `listFilesUnder delegates to the guarded walk`() {
        val body = bodyOf(stripComments(repoSource()), "private fun listFilesUnder(root: File)")
        assertTrue(
            "listFilesUnder must delegate to SkillTreeGuard.listFilesUnder",
            body.contains("SkillTreeGuard.listFilesUnder(root)"),
        )
        assertFalse(
            "the symlink-following walkTopDown() must be gone",
            body.contains("walkTopDown()"),
        )
    }

    @Test
    fun `the export writer re-checks containment before packing an entry`() {
        val body = bodyOf(stripComments(repoSource()), "fun exportSkillToZip(skillId: String)")
        val guardAt = body.indexOf("SkillTreeGuard.isInside(dir, src)")
        val entryAt = body.indexOf("putNextEntry(")
        assertTrue("the zip writer must assert containment before packing", guardAt >= 0)
        assertTrue("putNextEntry( anchor exists", entryAt > 0)
        assertTrue(
            "the containment check must run BEFORE the entry is written",
            guardAt < entryAt,
        )
    }
}
