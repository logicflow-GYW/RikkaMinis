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
 * Assertions are on masked source (comments and string literals stripped), so
 * prose — including the comments that describe the ordering — can never satisfy
 * them.
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
        // Take the rest of the file: the block's closing brace is the function's,
        // and everything after it is other declarations whose text cannot contain
        // the append sequence being asserted on.
        return src.substring(blockStart)
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
        val hint = body.indexOf("rollupSizeHint != null")
        assertTrue("the rollup size hint is not appended in buildSystemPrompt", hint >= 0)
        assertTrue(
            "the rollup size hint (which tracks daily-log byte sizes, so it moves on " +
                "a memory_write) is appended before `Runtime context:`",
            hint > anchor,
        )
    }

    // ------------------------------------------------------------------
    // 3. …and the slow-changing fragments stay ABOVE it
    // ------------------------------------------------------------------

    @Test
    fun theStableFragmentsStayInTheStandingRegion() {
        val body = buildStringBody()
        val anchor = body.indexOf("\"\\n\\nRuntime context:\\n\"")
        for (name in listOf("append(globalMemoryFragment)", "append(rollupFragment)")) {
            val at = body.indexOf(name)
            assertTrue("$name is not present in the buildString body", at >= 0)
            assertTrue(
                "$name was moved BELOW `Runtime context:` — it changes only when the " +
                    "agent explicitly runs memory_rollup, so demoting it to the volatile " +
                    "tail throws away cache hits for no reason",
                at < anchor,
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. The sub-agent path must keep its daily fragment (the P0 this
    //    branch's ancestor fixed by adding orderForSubagentContext)
    // ------------------------------------------------------------------

    @Test
    fun theSubagentContextStillCarriesTheDailyFragment() {
        // The sub-agent assembly lives in the same file (executeSpawnAgentTool).
        val whole = stripComments(readRepoFile(promptRel))
        assertTrue(
            "the sub-agent context no longer uses orderForSubagentContext — " +
                "orderInStandingRegion FILTERS by membership and silently drops " +
                "DAILY_MEMORY, which is the P0 this path already hit once",
            whole.contains("orderForSubagentContext"),
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
