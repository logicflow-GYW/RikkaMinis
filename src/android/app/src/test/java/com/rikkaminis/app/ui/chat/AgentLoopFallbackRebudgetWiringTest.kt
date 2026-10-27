package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1016 6a + 6b] Source-shape pins for the two edits that live in
 * `AgentLoopEngine.runAgentLoop` itself (the loop cannot be driven on the JVM —
 * same reason as [AgentLoopBudgetStopExitTest], which pins the budget-stop exit
 * shape instead of a transition).
 *
 * **6a — every provider switch re-derives the request budget.** The engine has
 * exactly two fallback switches, both of which replace `loopState.currentProvider`
 * mid-turn and then re-enter the retry loop. The budget captured at turn entry
 * belongs to the member those switches leave behind, so each one must call the
 * single shared `rebudgetForSwitchedProvider(…)`. This is the "fixed one of two"
 * shape that [audit2 BUG-02] already produced once — the probe therefore
 * enumerates the switch sites from the source instead of looking for one
 * expected call.
 *
 * **6b — a blocked-empty reply is not a healthy serve.** The turn's trailing
 * `RouteOutcome.Success` record must skip the case that the content-filter
 * branch below consumes the fallback chain for, and that decision must be
 * single-sourced (`blockedEmptyReply`) rather than spelled twice.
 *
 * The assertions are pure functions of the source text, so the same probe runs
 * against the pre-fix tree (see the negative controls, and the branch report's
 * differential closure).
 */
class AgentLoopFallbackRebudgetWiringTest {

    private val enginePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

    // ── 6a ────────────────────────────────────────────────────────────────

    @Test
    fun `every provider switch in the turn loop re-derives the request budget`() {
        val src = readRepoFile(enginePath)
        val sites = collectRebudgetCallSites(src)

        // Self-check: the probe must actually be looking at the two fallback
        // switches of THIS file (a probe that silently matched nothing would
        // "pass" every source, mutated or not — including the pre-fix tree).
        assertEquals(
            "expected the two AgentLoopEngine fallback switch sites",
            2,
            sites.size,
        )
        assertEquals(
            "the two switches are the error-path + content-filter fallbacks",
            listOf("content-filter-fallback", "error-fallback"),
            sites.map { it.site }.sorted(),
        )
        sites.forEach {
            assertTrue(
                "switch site at line ${it.line} does not re-derive the budget: " +
                    "the captured maxTokens/calibration still belongs to the member we left",
                it.rebudgets,
            )
        }

        // One definition, two call sites.
        assertEquals(
            "expected ONE rebudget definition + its two call sites",
            3,
            Regex("rebudgetForSwitchedProvider\\(").findAll(src).count(),
        )
        assertTrue(
            "the shared helper must pass the engine's own output floor",
            src.contains("outputFloor = MIN_MAX_TOKENS_CONST,"),
        )
        assertTrue(
            "the shared helper must be defined once (a second copy would drift)",
            src.contains("fun rebudgetForSwitchedProvider(site: String) {"),
        )
    }

    // ── 6b ────────────────────────────────────────────────────────────────

    @Test
    fun `the health record skips a blocked-empty reply, single-sourced`() {
        val src = readRepoFile(enginePath)
        val violations = checkBlockedEmptyReplyGate(src)
        assertEquals(
            "6b gate violations: ${violations.joinToString("; ")}",
            emptyList<String>(),
            violations,
        )

        // Self-checks: the predicate is single-sourced (one definition, one
        // consumer) and the old inline spelling is gone.
        assertEquals(
            "expected exactly one blockedEmptyReply definition",
            1,
            Regex("val blockedEmptyReply = ").findAll(src).count(),
        )
        assertEquals(
            "expected exactly one isBlockedFinish consumer (the single-source val)",
            1,
            Regex("isBlockedFinish\\(").findAll(src).count(),
        )
    }

    @Test
    fun `the raw buffer is untouched between the gate and its turnTextRaw snapshot`() {
        val src = readRepoFile(enginePath)
        val gate = src.indexOf("val blockedEmptyReply = ")
        val snapshot = src.indexOf("val turnTextRaw = turnTextSb.toString()")
        assertTrue("the gate must be defined before the snapshot", gate in 1 until snapshot)
        // Exactly ONE reference: the gate's own `turnTextSb.isEmpty()`. Any
        // second one would be a write (or a second read that could drift), and
        // then the gate and the content-filter branch could disagree.
        assertEquals(
            "the raw turn buffer must be referenced exactly once between the two " +
                "points (the gate's own emptiness test)",
            1,
            Regex("turnTextSb").findAll(src.substring(gate, snapshot)).count(),
        )
        assertTrue(
            "the single reference must be the emptiness test",
            src.substring(gate, snapshot).contains("turnTextSb.isEmpty()"),
        )
    }

    // ── negative controls: the probes must fail on the pre-fix shapes ──────

    @Test
    fun `negative control - a switch site without the re-budget is rejected`() {
        val src = readRepoFile(enginePath)
        val mutated = src.replace(
            "                        rebudgetForSwitchedProvider(\"error-fallback\")\n",
            "",
        )
        assertTrue("mutation must change the source", mutated != src)
        assertEquals("the mutated tree still enumerates both sites", 2, collectRebudgetCallSites(mutated).size)
        assertTrue(
            "the probe must flag the site that kept the left member's budget",
            collectRebudgetCallSites(mutated).any { !it.rebudgets },
        )
    }

    @Test
    fun `negative control - an ungated Success record is rejected`() {
        val src = readRepoFile(enginePath)
        val mutated = src.replace("                if (!blockedEmptyReply) {\n", "")
        assertTrue("mutation must change the source", mutated != src)
        val violations = checkBlockedEmptyReplyGate(mutated)
        assertTrue(
            "un-gating the health record must be reported, got $violations",
            violations.any { it.contains("not guarded") },
        )
    }

    @Test
    fun `negative control - a second inline spelling of the predicate is rejected`() {
        val src = readRepoFile(enginePath)
        val mutated = src.replace(
            "            if (blockedEmptyReply) {",
            "            if (toolCalls.isEmpty() && turnTextRaw.isEmpty() &&\n" +
                "                ContentFilterFinishPolicy.isBlockedFinish(turnFinishReason)\n" +
                "            ) {",
        )
        assertTrue("mutation must change the source", mutated != src)
        val violations = checkBlockedEmptyReplyGate(mutated)
        assertTrue(
            "a second spelling must be reported, got $violations",
            violations.any { it.contains("single definition") },
        )
    }

    @Test
    fun `negative control - a buffer write between gate and snapshot is rejected`() {
        val src = readRepoFile(enginePath)
        // Insert the write INSIDE the window (after the gate's own emptiness
        // test line, before the snapshot). Inserting before the `val` anchor
        // would land outside the window and the control would pass vacuously.
        val mutated = src.replace(
            "                turnTextSb.isEmpty() &&",
            "                turnTextSb.isEmpty() &&\n" +
                "                turnTextSb.setLength(0)\n",
        )
        assertTrue("mutation must change the source", mutated != src)
        val gate = mutated.indexOf("val blockedEmptyReply = ")
        val snapshot = mutated.indexOf("val turnTextRaw = turnTextSb.toString()")
        assertTrue(
            "the probe must see the write between gate and snapshot",
            Regex("turnTextSb").findAll(mutated.substring(gate, snapshot)).count() != 1,
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

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

/** One `loopState.currentProvider = …` site and whether it re-derives the budget. */
internal data class RebudgetCallSite(
    val line: Int,
    val site: String,
    val rebudgets: Boolean,
)

/**
 * Enumerates every provider switch in [engineSrc] and asks whether the enclosing
 * block re-derives the request budget (`rebudgetForSwitchedProvider(…)`).
 *
 * The window is the switch's own block: braces are counted over string-literal-
 * and comment-stripped text, starting at the assignment, and the block ends at
 * the first line that would close it.
 */
internal fun collectRebudgetCallSites(engineSrc: String): List<RebudgetCallSite> {
    val raw = engineSrc.split("\n")
    val text = raw.map { stripNoise(it) }
    val out = mutableListOf<RebudgetCallSite>()
    text.forEachIndexed { i, line ->
        if (!line.contains("loopState.currentProvider = ")) return@forEachIndexed
        var balance = 0
        val block = StringBuilder()
        var j = i
        while (j < text.size) {
            val l = text[j]
            block.append(l).append('\n')
            balance += l.count { it == '{' } - l.count { it == '}' }
            if (j > i && balance < 0) break
            j++
        }
        // The call carries a `site = "<label>"` argument; recover it from the RAW
        // lines of the same window (the stripped text has literal contents
        // blanked, so the label would read as ""), so callers can assert WHICH
        // switch recovered rather than just "some switch did".
        val rawWindow = raw.subList(i, minOf(j + 1, raw.size)).joinToString("\n")
        val label = Regex("rebudgetForSwitchedProvider\\(\"([^\"]+)\"\\)")
            .find(rawWindow)?.groupValues?.get(1) ?: "line-${i + 1}"
        out += RebudgetCallSite(
            line = i + 1,
            site = label,
            rebudgets = block.contains("rebudgetForSwitchedProvider("),
        )
    }
    return out
}

/**
 * 6b invariants: the blocked-empty reply is detected ONCE, the health record is
 * guarded by it, the content-filter branch consumes the same val, and the raw
 * turn buffer is not written between the definition and its snapshot.
 */
internal fun checkBlockedEmptyReplyGate(engineSrc: String): List<String> {
    val violations = mutableListOf<String>()
    val defs = Regex("val blockedEmptyReply = ").findAll(engineSrc).toList()
    if (defs.size != 1) {
        violations += "expected a single definition of blockedEmptyReply, found ${defs.size}"
        return violations
    }
    val gate = defs[0].range.first
    val defText = engineSrc.substring(gate, minOf(engineSrc.length, gate + 400))
    if (!defText.contains("toolCalls.isEmpty()")) {
        violations += "the gate must require an empty tool-call list"
    }
    if (!defText.contains("ContentFilterFinishPolicy.isBlockedFinish(turnFinishReason)")) {
        violations += "the gate must test the content-filter finish policy"
    }
    if (Regex("isBlockedFinish\\(").findAll(engineSrc).count() != 1) {
        violations +=
            "expected one single definition of the blocked-finish test (single-source), " +
                "found ${Regex("isBlockedFinish\\(").findAll(engineSrc).count()}"
    }

    val record = engineSrc.indexOf("host.activeEntryId?.let { entryId ->")
    if (record < 0) {
        violations += "the per-turn health record was not found"
    } else {
        val block = engineSrc.substring(record, minOf(engineSrc.length, record + 900))
        if (!block.contains("if (!blockedEmptyReply) {")) {
            violations += "the Success record is not guarded by the blocked-empty gate"
        }
        if (!block.contains("RouteOutcome.Success")) {
            violations += "the guarded call is not the Success record"
        }
    }

    val consumer = engineSrc.indexOf("            if (blockedEmptyReply) {")
    if (consumer < 0) {
        violations += "the content-filter branch does not consume the single-source value"
    } else if (consumer < gate) {
        violations += "the consumer appears above the definition"
    }

    val snapshot = engineSrc.indexOf("val turnTextRaw = turnTextSb.toString()")
    if (snapshot < 0) {
        violations += "the turnTextRaw snapshot anchor was not found"
    } else if (gate in 1 until snapshot &&
        Regex("turnTextSb").findAll(engineSrc.substring(gate, snapshot)).count() != 1
    ) {
        violations += "the raw turn buffer is written between the gate and its snapshot"
    }
    return violations
}

/**
 * Blanks `//` comments and string literals (keeping escapes and interpolation
 * braces balanced) so brace counting over Kotlin source is safe.
 */
internal fun stripNoise(line: String): String {
    val sb = StringBuilder()
    var i = 0
    var quote = false
    while (i < line.length) {
        val c = line[i]
        if (!quote) {
            if (c == '/' && i + 1 < line.length && line[i + 1] == '/') break
            if (c == '"') {
                quote = true
                sb.append('"')
            } else {
                sb.append(c)
            }
        } else {
            when {
                c == '\\' && i + 1 < line.length -> i++ // skip the escaped char
                c == '"' -> {
                    quote = false
                    sb.append('"')
                }
                // keep `${…}` interpolation braces so the balance stays true
                else -> Unit
            }
        }
        i++
    }
    return sb.toString()
}
