package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [review1112-p1-4] Pins the segment ORDER of the real
 * `ChatViewModel.buildSystemPrompt()` — not just the ordering helper.
 *
 * The defect this guards against: the daily-log memory fragment used to sit
 * inside the byte-stable prefix of the system prompt, so every `memory_write`
 * moved bytes above `Runtime context:` and defeated prefix-based prompt
 * caching (OpenAI/DeepSeek head matching, Anthropic cache_control breakpoints).
 * It was moved to the volatile tail.
 *
 * [MemoryPromptOrder] encodes the intended order, but the production prompt is
 * built by a hand-written `append` sequence in `buildSystemPrompt` and does NOT
 * call the helper — so [ChatPromptMemoryOrderTest], which feeds the helper, stays
 * green even if someone moves the daily fragment back above `Runtime context:`.
 * That is the original bug, unpinned. This test reads the real function body.
 *
 * Assertions are on source with COMMENTS STRIPPED and STRING LITERALS KEPT, so
 * prose — including the comments that describe the ordering — can never satisfy
 * them. Literals have to survive: the ordering anchor IS a literal
 * (`"\n\nRuntime context:\n"`), so blanking strings would erase the very thing
 * being located. (An earlier version of this header claimed both were stripped;
 * it was wrong, and the implementation is the one that has to be right.)
 *
 * Every assertion is scoped to `buildSystemPrompt`'s `buildString { … }` block
 * by brace matching, and the matcher fails closed if it returns most of the
 * file: a file-wide slice would let `append(...)` calls from ANY later function
 * satisfy the ordering checks.
 *
 * Why source text instead of calling the function: `buildSystemPrompt` is an
 * internal extension on `ChatViewModel` that reads `context.resources`,
 * repositories and the Android clock; instantiating it in a JVM test would mean
 * stubbing most of the app. The repo already pins prompt-ordering contracts this
 * way (see `CompactModelPinTest`, `CompactCancelOnStopTest`).
 */
class SystemPromptSegmentOrderTest {

    private val promptRel =
        "src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"

    /**
     * The body of `buildString { … }` inside `buildSystemPrompt`, with comments
     * stripped but STRING LITERALS KEPT.
     *
     * Literals must survive: the ordering anchor IS a literal
     * (`"\n\nRuntime context:\n"`), so stripping strings would erase the very
     * thing being located — the first draft of this test did exactly that and
     * failed on its own anchor assertion.
     *
     * Comments must go: the function's comments describe the intended ordering
     * at length, and a prose mention of "Runtime context" would satisfy a naive
     * `indexOf`.
     */
    private fun buildStringBody(): String {
        val src = stripComments(readRepoFile(promptRel))
        val fnStart = src.indexOf("internal fun ChatViewModel.buildSystemPrompt()")
        assertTrue("buildSystemPrompt() not found in $promptRel", fnStart >= 0)
        val blockStart = src.indexOf("return buildString {", fnStart)
        assertTrue("the buildString block is gone from buildSystemPrompt", blockStart >= 0)

        // [incr1113 F-07] Bracket-MATCHED, not `substring(blockStart)`.
        //
        // Taking the rest of the file made every assertion below satisfiable by
        // ANY later function in the same file: `executeSpawnAgentTool` and the
        // tool helpers also call `append(...)`, so moving a fragment out of
        // buildSystemPrompt entirely could still leave its `append(...)` text
        // below the cut and keep the ordering assertions green. The block has to
        // end where the code ends.
        val body = braceBlock(src, blockStart)

        // Fail-closed on a silent fallback: if the matcher returned most of the
        // file the ordering assertions below are back to being file-wide, which
        // is the defect. `buildSystemPrompt` is a few hundred lines in a 2,000+
        // line file, so a body larger than half of it means the match failed.
        assertTrue(
            "buildStringBody() matched ${body.length} of ${src.length} chars — the brace " +
                "matcher fell back to a file-wide slice, so these assertions would " +
                "again be satisfiable by code outside buildSystemPrompt()",
            body.length < src.length / 2,
        )
        return body
    }

    /**
     * From [from], the source text through the brace block that opens at or
     * after it. String literals are skipped, so an `append("{\n")` inside the
     * block cannot be mistaken for structure.
     */
    private fun braceBlock(src: String, from: Int): String {
        val open = src.indexOf('{', from)
        assertTrue("no brace block opens at or after offset $from", open >= 0)
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\' && i + 1 < src.length) { i += 2; continue }
                if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(from, i + 1)
                }
            }
            i++
        }
        throw AssertionError("the brace block opened at $open never closes")
    }

    /** The body of the named top-level `fun`, brace-matched. */
    private fun functionBodyOf(src: String, signature: String): String {
        val at = src.indexOf(signature)
        assertTrue("$signature not found in $promptRel", at >= 0)
        val body = braceBlock(src, at)
        assertTrue(
            "the body matched for $signature is suspiciously short — the matcher " +
                "may have stopped at an inner block",
            body.length > 200,
        )
        return body
    }

    // ------------------------------------------------------------------
    // 1. The anchor itself must exist — otherwise the assertions below
    //    would pass vacuously against an empty/short body.
    // ------------------------------------------------------------------

    @Test
    fun theRuntimeContextAnchorExists() {
        val body = buildStringBody()
        assertTrue(
            "the `Runtime context:` anchor is gone — the volatile-tail contract " +
                "below can no longer be evaluated",
            body.contains("\"\\n\\nRuntime context:\\n\""),
        )
    }

    // ------------------------------------------------------------------
    // 2. THE contract: the daily fragment is emitted AFTER the anchor
    // ------------------------------------------------------------------

    @Test
    fun theDailyMemoryFragmentIsInTheVolatileTail() {
        val body = buildStringBody()
        val anchor = body.indexOf("\"\\n\\nRuntime context:\\n\"")
        // The APPEND, not the `val … = …` declaration (which sits above the
        // buildString block and would always "win" the index comparison).
        val daily = body.indexOf("append(dailyMemoryFragment)")
        assertTrue("the daily fragment is not appended in buildSystemPrompt at all", daily >= 0)
        assertTrue(
            "the daily-log memory fragment is appended BEFORE `Runtime context:` — " +
                "every memory_write rewrites bytes inside the cached prefix and the " +
                "whole prompt cache misses (this is the bug fix/memcache-prefix-split " +
                "fixed; MemoryPromptOrder alone does not enforce it because " +
                "buildSystemPrompt does not call it)",
            daily > anchor,
        )
    }

    @Test
    fun theRollupSizeHintIsInTheVolatileTail() {
        val body = buildStringBody()
        val anchor = body.indexOf("\"\\n\\nRuntime context:\\n\"")
        // [incr1113 F-07] Anchor on the EMISSION, not on the guard condition.
        // `rollupSizeHint != null` alone survives the append being emptied out
        // (`if (rollupSizeHint != null) { }`), which is the one edit that turns
        // the hint off while every assertion still passes.
        val hint = body.indexOf("Note: Daily logs are large")
        assertTrue(
            "the rollup size hint is not APPENDED in buildSystemPrompt — the guard " +
                "may still be there while its body emits nothing",
            hint >= 0,
        )
        assertTrue(
            "the rollup size hint (which tracks daily-log byte sizes, so it moves on " +
                "a memory_write) is appended before `Runtime context:`",
            hint > anchor,
        )
        assertTrue(
            "the hint text must be EMITTED by an append(…) — a bare string in the " +
                "block would never reach the prompt",
            body.substring(maxOf(0, hint - 60), hint).contains("append("),
        )
        assertTrue(
            "the hint must interpolate the size value — a constant note would never " +
                "change and belongs in the standing region instead",
            body.substring(hint, minOf(body.length, hint + 120)).contains("\$rollupSizeHint"),
        )
    }

    // ------------------------------------------------------------------
    // 3. …and the slow-changing fragments stay ABOVE it
    // ------------------------------------------------------------------

    @Test
    fun theStableFragmentsStayInTheStandingRegion() {
        val body = buildStringBody()
        val anchor = body.indexOf("\"\\n\\nRuntime context:\\n\"")
        val positions = listOf("append(globalMemoryFragment)", "append(rollupFragment)").associateWith {
            body.indexOf(it)
        }
        for ((name, at) in positions) {
            assertTrue("$name is not present in the buildString body", at >= 0)
            assertTrue(
                "$name was moved BELOW `Runtime context:` — it changes only when the " +
                    "agent explicitly runs memory_rollup, so demoting it to the volatile " +
                    "tail throws away cache hits for no reason",
                at < anchor,
            )
        }
        // [incr1113 F-07] Relative order WITHIN the standing region.
        //
        // "Both are above the anchor" says nothing about their order relative to
        // each other, and swapping them is a silent cache-prefix change: the two
        // fragments sit back to back, so any reorder moves the whole boundary
        // between them. Pinned in the order the production code emits them
        // (global memory, then rollup) — this test exists to freeze today's
        // shape, not to argue for a better one.
        assertTrue(
            "globalMemoryFragment must be appended BEFORE rollupFragment (both are in " +
                "the byte-stable prefix; swapping them rewrites the cached boundary)",
            positions.getValue("append(globalMemoryFragment)") <
                positions.getValue("append(rollupFragment)"),
        )
    }

    @Test
    fun theVolatileTailKeepsItsRelativeOrder() {
        val body = buildStringBody()
        // [incr1113 F-07] Same reasoning one region over: "both are below the
        // anchor" does not pin which comes first, and the daily fragment is the
        // one that moves on every memory_write — it must stay LAST so the hint's
        // own bytes (which move only when the log sizes cross a threshold) are
        // not dragged by it. Production order: daily fragment, then the hint.
        val daily = body.indexOf("append(dailyMemoryFragment)")
        val hint = body.indexOf("append(\"\\n\\nNote: Daily logs are large (")
        assertTrue("the daily fragment is not appended in buildSystemPrompt", daily >= 0)
        assertTrue("the rollup size hint is not appended in buildSystemPrompt", hint >= 0)
        assertTrue(
            "the daily fragment must come BEFORE the rollup size hint in the volatile " +
                "tail (the daily fragment moves on every memory_write; anything after " +
                "it inherits that churn)",
            daily < hint,
        )
    }

    // ------------------------------------------------------------------
    // 4. The sub-agent path must keep its daily fragment (the P0 this
    //    branch's ancestor fixed by adding orderForSubagentContext)
    // ------------------------------------------------------------------

    @Test
    fun theSubagentContextStillCarriesTheDailyFragment() {
        // [incr1113 F-07] Scoped to the function, and the ARGUMENT is checked.
        //
        // The previous version asserted `whole.contains("orderForSubagentContext")`
        // over the entire file — a bare substring, satisfiable by a comment, by an
        // import, or by the function being called somewhere else while the
        // sub-agent path silently stopped calling it. The regression this guards
        // (orderInStandingRegion filtering DAILY_MEMORY out of the sub-agent
        // context) happens entirely inside `executeSpawnAgentTool`, so that is
        // the only region whose text means anything here.
        val src = stripComments(readRepoFile(promptRel))
        val body = functionBodyOf(src, "internal suspend fun ChatViewModel.executeSpawnAgentTool(")
        assertTrue(
            "executeSpawnAgentTool no longer calls orderForSubagentContext — " +
                "orderInStandingRegion FILTERS by membership and silently drops " +
                "DAILY_MEMORY, which is the P0 this path already hit once",
            body.contains("orderForSubagentContext"),
        )
        // …and the daily fragment must actually be handed IN. A call whose list
        // omits DAILY_MEMORY re-introduces the same hole with the right helper.
        assertTrue(
            "the sub-agent fragment list no longer adds DAILY_MEMORY — the ordering " +
                "helper can only keep what it is given",
            body.contains("MemoryPromptOrder.Key.DAILY_MEMORY"),
        )
    }

    // ------------------------------------------------------------------

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (f in listOf(
                java.io.File(dir, relative),
                java.io.File(dir, "src/android/app/$relative"),
            )) {
                if (f.exists()) return f.readText()
            }
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /**
     * Strip line and block comments only — string literals are KEPT.
     *
     * The ordering anchor is itself a literal (`"\n\nRuntime context:\n"`), so
     * a stripper that blanks strings would erase the thing being located. What
     * must not survive is PROSE: the function's comments describe the intended
     * ordering at length, and a naive `indexOf` over them would be satisfied by
     * a comment rather than by code.
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && src[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                    i += 2
                }
                c == '"' -> {
                    // Copy the literal verbatim (escapes included).
                    out.append(c); i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < n) { out.append(src[i]); i++ }
                        out.append(src[i]); i++
                    }
                    if (i < n) { out.append(src[i]); i++ }
                }
                c == '\'' -> {
                    out.append(c); i++
                    while (i < n && src[i] != '\'') {
                        if (src[i] == '\\' && i + 1 < n) { out.append(src[i]); i++ }
                        out.append(src[i]); i++
                    }
                    if (i < n) { out.append(src[i]); i++ }
                }
                else -> {
                    out.append(c); i++
                }
            }
        }
        return out.toString()
    }
}
