package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [meta-audit-1028 E5] Wiring shape for B06 F-07 / F-09: the two defects were
 * NOT in the pure logic (there was none — the decisions were inline in the
 * Composables), so what has to be pinned is that the CALL SITES now consume
 * the signal. These assertions are deliberately source-shape based and
 * independently implemented (not shared with the scan gates): they run in the
 * same JVM suite as the pure-logic tests above, so a refactor that drops the
 * consumer turns red here before it ever reaches CI.
 *
 * Every assertion below is written so the PRE-FIX tree fails it: that is the
 * point of a shape test — it has to have teeth on the shape it replaced.
 */
class Meta1028E5WiringTest {

    // -- F-07 ① ModelEntryDetailScreen: a refused update must not pop --------

    @Test
    fun detailScreen_bindsTheUpdateResult() {
        val code = screenCode(ME_REL)
        val at = code.indexOf("providerRepository.updateEntry(")
        assertTrue("updateEntry call site not found in $ME_REL", at >= 0)
        // The return value must be BOUND, not discarded: `… = providerRepository
        // .updateEntry(` is the only shape that can consume a refusal.
        val before = code.substring(maxOf(0, at - 80), at).trimEnd()
        assertTrue(
            "updateEntry's return value is discarded — a refused write (rename "
                + "collision / blank id) is then indistinguishable from a saved "
                + "one and the screen pops with the edit lost",
            before.endsWith("=") && "val " in before,
        )
    }

    @Test
    fun detailScreen_routesRefusalAwayFromOnBack() {
        val code = screenCode(ME_REL)
        val at = code.indexOf("providerRepository.updateEntry(")
        assertTrue("updateEntry call site not found in $ME_REL", at >= 0)
        // Everything from the call to the end of the `when` that consumes it.
        val decisionAt = code.indexOf("classifyUpdateResult(", at)
        assertTrue(
            "updateEntry's result is bound but never classified — a refused "
                + "write is then indistinguishable from a saved one",
            decisionAt > at,
        )
        val whenAt = code.lastIndexOf("when (", decisionAt)
        val body = blockFrom(code, code.indexOf('{', whenAt))
        // Three DISTINCT outcomes: conflating ID_TAKEN with either neighbour is
        // the whole bug family (lost edit / misleading reason).
        for (arm in listOf("EntrySaveOutcome.SAVED", "EntrySaveOutcome.ID_TAKEN", "EntrySaveOutcome.ENTRY_GONE")) {
            assertTrue("missing `$arm` arm in $ME_REL", arm in body)
        }
        // The refusal arm must NOT navigate: popping on a refused rename is
        // exactly the pre-fix behaviour behind a new spelling.
        val refusalArm = armBody(body, "EntrySaveOutcome.ID_TAKEN")
        assertTrue(
            "the ID_TAKEN arm calls onBack() — a refused save would still pop "
                + "and lose the edit",
            refusalArm.isNotBlank() && "onBack()" !in refusalArm,
        )
        assertTrue(
            "the ID_TAKEN arm says nothing (silent refusal)",
            Regex("saveError\\s*=\\s*(?!null\\b)\\S").containsMatchIn(refusalArm),
        )
    }

    @Test
    fun detailScreen_surfacesTheRefusalToTheUser() {
        val code = screenCode(ME_REL)
        assertTrue(
            "the refused save must name a string resource (silent `return` "
                + "would still lose the edit with no signal)",
            "R.string.modeldetail_error_model_id_exists" in code,
        )
    }

    // -- F-07 ② AddCustomModelScreen: a refused add must not pop ------------

    @Test
    fun addScreen_bindsTheAddResult() {
        val code = screenCode(ADD_REL)
        val at = code.indexOf("providerRepository.addEntry(")
        assertTrue("addEntry call site not found in $ADD_REL", at >= 0)
        // The write is wrapped in withContext(Dispatchers.IO) { … }, so the
        // binding to look for is that lambda's assigned val.
        val m = Regex("""val\s+([A-Za-z_]\w*)\s*=\s*withContext\(\s*Dispatchers\.IO\s*\)\s*\{""")
            .find(code)
        assertTrue(
            "addEntry's return value is discarded — its duplicate refusal is "
                + "ONLY visible as 'the reported id already existed', so "
                + "ignoring it makes a rejected add look like a saved one",
            m != null && m.range.first < at && at < m.range.last + 200,
        )
        if (m != null) {
            assertTrue(
                "the bound id ('${m.groupValues[1]}') must reach the refusal check",
                "addEntryRejected(${m.groupValues[1]}," in code,
            )
        }
    }

    @Test
    fun addScreen_guardsOnBackWithTheRefusal() {
        val code = screenCode(ADD_REL)
        val at = code.indexOf("providerRepository.addEntry(")
        assertTrue("addEntry call site not found in $ADD_REL", at >= 0)
        val backAt = code.indexOf("onBack()", at)
        assertTrue("no onBack() after the addEntry call", backAt > at)
        val between = code.substring(at, backAt)
        assertTrue(
            "onBack() runs unconditionally after addEntry — the duplicate "
                + "refusal must be checked between the two",
            "addEntryRejected(" in between,
        )
        assertTrue(
            "the refusal guard is dead (constant-false / absorbed)",
            guardIsLive(between, "addEntryRejected("),
        )
        // The refusal is only observable against the PRE-write id snapshot:
        // a snapshot taken after the write contains the new row's id, so a
        // successful add would be mis-reported as a duplicate.
        assertTrue(
            "addEntryRejected needs the id set from before the write",
            "idsBefore" in code,
        )
    }

    @Test
    fun addScreen_snapshotPrecedesTheWrite() {
        val code = screenCode(ADD_REL)
        val snapAt = code.indexOf("idsBefore")
        val writeAt = code.indexOf("providerRepository.addEntry(")
        assertTrue("no id snapshot ('idsBefore') in $ADD_REL", snapAt >= 0)
        assertTrue("addEntry call site not found in $ADD_REL", writeAt >= 0)
        assertTrue(
            "the id snapshot must be taken BEFORE addEntry — taken after, it "
                + "contains the new row's id and every add looks like a duplicate",
            snapAt < writeAt,
        )
    }

    // -- F-09 LogManagementScreen: crash merge by real timestamp -------------

    @Test
    fun logScreen_mergesCrashListsByTimestampNotName() {
        val code = screenCode(LOG_REL)
        assertTrue(
            "the crash merge must go through newestFirstCapped (real mtime), "
                + "not a file-name sort — 'native-crash-' vs 'crash-' puts the "
                + "whole native family above every Java crash",
            "newestFirstCapped(" in code,
        )
        assertTrue(
            "newestFirstCapped must be driven by the captured mtime",
            "modifiedOf = { it.lastModified }" in code,
        )
    }

    @Test
    fun logScreen_noNameSortLeftInTheCrashMerge() {
        val code = screenCode(LOG_REL)
        assertTrue(
            "a file-name sort is still present in $LOG_REL — the two crash "
                + "families do not share a name prefix or a stamp format, so a "
                + "name sort cannot order them",
            "sortedByDescending { it.name }" !in code,
        )
    }

    // -- helpers (independently implemented, not shared with the scan gate) --

    /**
     * True when the `if (…)` whose condition contains [marker] can actually be
     * taken: a literal-false conjunct (`false && …`) or an absorbed disjunct
     * (`… || true`) keeps the spelling while disabling the branch, which is
     * exactly the failure mode that made the original defect silent.
     *
     * Deliberately coarse (it reads the whole condition text) — the point is
     * "the guard is not textually disabled", not full Kotlin constant folding.
     */
    private fun guardIsLive(code: String, marker: String): Boolean {
        val at = code.indexOf(marker)
        if (at < 0) return false
        // The `if (` that ENCLOSES the marker: the nearest "if (" before it.
        // (Walking back to the marker's own '(' gives the call's parenthesis,
        // not the guard's — that mistake made this predicate constant-false.)
        val ifAt = code.lastIndexOf("if (", at)
        val plainIf = code.lastIndexOf("if(", at)
        val open = maxOf(ifAt, plainIf)
        if (open < 0) return false
        val paren = code.indexOf('(', open)
        var depth = 0
        var close = paren
        while (close < code.length) {
            if (code[close] == '(') depth += 1
            else if (code[close] == ')') {
                depth -= 1
                if (depth == 0) break
            }
            close += 1
        }
        val cond = code.substring(paren + 1, close)
        val lowered = cond.lowercase()
        if (lowered.startsWith("false") || "false &&" in lowered || "&& false" in lowered) return false
        if (lowered.startsWith("!true") || "!true &&" in lowered || "&& !true" in lowered) return false
        if ("|| true" in lowered || "|| !false" in lowered) return false
        return true
    }

    private fun screenCode(relative: String): String = stripComments(readRepoFile(relative))

    /** Body of the balanced `{ … }` starting at [open] (which must be a '{'). */
    private fun blockFrom(code: String, open: Int): String {
        if (open < 0 || open >= code.length || code[open] != '{') return ""
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth += 1
            else if (code[i] == '}') {
                depth -= 1
                if (depth == 0) return code.substring(open + 1, i)
            }
            i += 1
        }
        return code.substring(open + 1)
    }

    /**
     * Text of one `when` arm: from after [arm]'s `->` to the start of the next
     * `EntrySaveOutcome.` arm (or the end of the body given).
     */
    private fun armBody(body: String, arm: String): String {
        val at = body.indexOf(arm)
        if (at < 0) return ""
        val arrow = body.indexOf("->", at)
        if (arrow < 0) return ""
        val next = body.indexOf("EntrySaveOutcome.", arrow + 2)
        return (if (next < 0) body.substring(arrow + 2) else body.substring(arrow + 2, next)).trim()
    }

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
                    if (c == '\\') { sb.append(next); i += 1 }
                    else if (c == '"') inString = false
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
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ME_REL = "app/src/main/java/com/rikkaminis/app/ui/settings/ModelEntryDetailScreen.kt"
        const val ADD_REL = "app/src/main/java/com/rikkaminis/app/ui/settings/AddCustomModelScreen.kt"
        const val LOG_REL = "app/src/main/java/com/rikkaminis/app/ui/settings/LogManagementScreen.kt"
    }
}
