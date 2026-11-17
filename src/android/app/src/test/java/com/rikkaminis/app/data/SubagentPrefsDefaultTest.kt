package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [C-INCR1117-F02] The sub-agent switch has ONE shipped default, and every
 * writer agrees on it.
 *
 * The defect this pins: the default flipped to ON (2026-11-17) but Settings →
 * Agent Runtime → "Restore defaults" still wrote a literal `false`, so
 * restoring defaults turned a shipped-on feature off. Three call sites now
 * read [SubagentPrefs.DEFAULT]:
 *   - `isEnabled` seeds a device that never made a choice,
 *   - `ConfigBuiltins` declares the registry default (minis-config + backup),
 *   - `RuntimeLimitsScreen` writes it back on reset.
 *
 * The two values below are LITERALS on purpose: deriving them from the
 * constant would make a flip of the constant invisible (the mutation would
 * move the expectation with it).
 */
class SubagentPrefsDefaultTest {

    @Test
    fun `the shipped sub-agent default is ON`() {
        assertEquals(
            "a fresh install ships sub-agent dispatch ON — the whole point of the 2026-11-17 rework",
            true,
            SubagentPrefs.DEFAULT,
        )
    }

    @Test
    fun `the registry registration passes the constant, not a literal`() {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt",
        )
        val at = src.indexOf("""path = "runtime.subagentEnabled"""")
        assertTrue("the sub-agent field must still be registered", at >= 0)
        val ctorAt = src.lastIndexOf("PrefsBoolField(", at)
        assertTrue(
            "the sub-agent field must still be a PrefsBoolField (the path sits inside its argument list)",
            ctorAt >= 0,
        )
        val block = balancedParens(src.substring(ctorAt), "PrefsBoolField")
        val defaultArg = block
            .removePrefix("(")
            .removeSuffix(")")
            .lineSequence()
            .firstOrNull { it.trim().startsWith("defaultValue =") }
            ?.trim()
        assertEquals(
            "the registration must read SubagentPrefs.DEFAULT — a literal default drifts away from the constant again (C-F02)",
            "defaultValue = com.rikkaminis.app.data.SubagentPrefs.DEFAULT,",
            defaultArg,
        )
        // Negative control: the pre-fix line must NOT satisfy the predicate.
        assertFalse(
            "the pre-fix literal default must be rejected by the same predicate",
            "defaultValue = true," == defaultArg,
        )
    }

    /** The balanced `(...)` block of the first [marker] call at/after the start of [src]. */
    private fun balancedParens(src: String, marker: String): String {
        val at = src.indexOf(marker)
        require(at >= 0) { "no call found for $marker" }
        val open0 = src.indexOf('(', at + marker.length)
        require(open0 >= 0) { "the call for $marker has no argument list" }
        var depth = 0
        for (j in open0 until src.length) {
            if (src[j] == '(') depth++
            if (src[j] == ')') {
                depth--
                if (depth == 0) return src.substring(open0, j + 1)
            }
        }
        error("the call for $marker is unbalanced")
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative] (same helper as `PrefsListenerPrimeOrderTest`).
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? =
            java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
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
