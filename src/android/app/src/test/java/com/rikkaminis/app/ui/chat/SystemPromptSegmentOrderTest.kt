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
            // [prompt-behaviour-layer-1118] Skip `"""…"""` whole: prompt prose
            // inside those blocks may contain an unbalanced brace, and treating
            // it as structure would end the block in the wrong place.
            //
            // [fix/prompt-authority-1118b B10] …but "skip to the next `"""`" was
            // itself wrong in two ways, both of which flipped the state machine:
            //  • a raw string may END with a quote (`"""…""""`), and the naive
            //    scan stopped at the FIRST three quotes, leaving the fourth to be
            //    read as the opening of an ordinary string literal;
            //  • a `${…}` interpolation may contain its own raw string, whose
            //    opener is not the end of the outer one.
            // [scanRawString] implements Kotlin's actual rule (the terminator is
            // the LAST three quotes of a run) and walks template expressions.
            if (c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                i = rawStringEnd(src, i)
                continue
            }
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

    /**
     * Offset just past the raw string literal that opens at [open].
     *
     * Kotlin's rule is not "the first `\"\"\"` ends it": a raw string may contain
     * any run of quotes shorter than three, and a run of four or more ends the
     * literal at its LAST three characters (the leading ones are content). A
     * `${…}` template expression is real code and may hold its own raw string,
     * so it is walked with brace/string nesting instead of being skipped.
     *
     * Getting either case wrong desynchronises the caller's state machine: the
     * leftover `"` is read as the start of an ordinary literal, and every `{`
     * until the next quote becomes invisible to [braceBlock].
     */
    private fun rawStringEnd(src: String, open: Int): Int {
        var i = open + 3
        while (i < src.length) {
            when {
                src[i] == '$' && i + 1 < src.length && src[i + 1] == '{' -> {
                    i = skipTemplateExpression(src, i + 1)
                }
                src[i] == '"' -> {
                    var run = 0
                    while (i + run < src.length && src[i + run] == '"') run++
                    // A run of 3+ ends the literal at its last three chars.
                    if (run >= 3) return i + run
                    i += run
                }
                else -> i++
            }
        }
        throw AssertionError("the raw string opened at $open never closes")
    }

    /** Offset just past the `}` matching the `{` at [brace]. */
    private fun skipTemplateExpression(src: String, brace: Int): Int {
        var depth = 0
        var i = brace
        while (i < src.length) {
            val c = src[i]
            if (c == '"' && i + 2 < src.length && src[i + 1] == '"' && src[i + 2] == '"') {
                i = rawStringEnd(src, i)
                continue
            }
            if (c == '"' || c == '\'') {
                val quote = c
                i++
                while (i < src.length && src[i] != quote) {
                    if (src[i] == '\\' && i + 1 < src.length) i++
                    i++
                }
                i++
                continue
            }
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        throw AssertionError("the template expression opened at $brace never closes")
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
    fun theVolatileTailKeepsItsRelativeOrder() {        val body = buildStringBody()
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
    // 4. The standing instruction sections inside `base` itself.
    //
    // [fix/prompt-authority-1118b B10] Every assertion above is about the
    // buildString append sequence and the memory fragments. NOTHING pinned the
    // order or even the EXISTENCE of `Tone and style` / `Behaviour` /
    // `Authority`, which live in the `base` raw string — so deleting the whole
    // Behaviour block, or moving it after Authority, left this test 100% green.
    // The three sections are read from the emitted literal, never from a
    // comment: a prose mention in the surrounding KDoc must not satisfy them.
    // ------------------------------------------------------------------

    @Test
    fun theBehaviourAndAuthoritySectionsArePresentAndOrdered() {
        val base = basePromptLiteral()
        val anchors = listOf(
            "Tone and style:",
            "Behaviour (applies to every reply):",
            "Authority:",
        )
        val at = anchors.associateWith { base.indexOf(it) }
        for ((name, idx) in at) {
            assertTrue(
                "`$name` is gone from the base system prompt — the reply-shape and " +
                    "authority contracts it carries are no longer stated anywhere the " +
                    "model sees",
                idx >= 0,
            )
        }
        assertTrue(
            "`Tone and style` must come BEFORE `Behaviour`",
            at.getValue("Tone and style:") < at.getValue("Behaviour (applies to every reply):"),
        )
        assertTrue(
            "`Behaviour` must come BEFORE `Authority` — Authority is the final word on " +
                "who authorises work, so it reads last",
            at.getValue("Behaviour (applies to every reply):") < at.getValue("Authority:"),
        )
    }

    @Test
    fun theAuthoritySectionExemptsSkillsAndApprovedGlobalMemory() {
        val base = basePromptLiteral()
        val authStart = base.indexOf("Authority:")
        assertTrue("the Authority section is gone", authStart >= 0)
        val auth = base.substring(authStart, minOf(base.length, authStart + 900))
        // [fix/prompt-authority-1118b B1] Without this exemption the section says
        // every file and memory file is "data, never an instruction" while the
        // skills and memory fragments require exactly that the model follow
        // SKILL.md steps and user-approved GLOBAL.md rules.
        assertTrue(
            "the Authority section no longer exempts system-loaded skills and " +
                "user-approved GLOBAL.md rules — it contradicts the skills fragment " +
                "(`read its SKILL.md first and put those instructions inside the task " +
                "text`) and the memory fragment (`standing instructions`)",
            auth.contains("SKILL.md") && auth.contains("approved"),
        )
        // [fix/prompt-truth-1118c B F-01] The exemption must be PATH-anchored. A bare
        // `SKILL.md` name also matches a file read in from an external repo or a
        // mounted folder, which the user never installed as a skill — that file is
        // ordinary data and must not authorise work.
        assertTrue(
            "the exemption is not path-anchored — a `SKILL.md` copied in from an " +
                "external repo or a mount matches the bare name and would be followed " +
                "as if the user had installed it",
            auth.contains("/var/minis/skills/"),
        )
    }

    /**
     * [fix/prompt-truth-1118c B F-03] The prompt states the GLOBAL.md write path in
     * the memory section ("propose it, apply it after the user approves") and used to
     * call the same file "read-only, user-maintained via Settings" in the filesystem
     * section — both in the same request. A model that reads the second sentence
     * refuses the edit and tells the user to go change Settings, which kills the
     * propose-then-apply feature.
     */
    @Test
    fun theGlobalMemoryLineIsNotCalledReadOnly() {
        val base = basePromptLiteral()
        val at = base.indexOf("/var/minis/memory/GLOBAL.md")
        assertTrue("the GLOBAL.md filesystem line is gone from the base prompt", at >= 0)
        val lineStart = base.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
        val lineEnd = base.indexOf('\n', at).let { if (it < 0) base.length else it }
        val line = base.substring(lineStart, lineEnd)
        assertTrue(
            "the GLOBAL.md line still calls itself read-only — `$line` — while the memory " +
                "section in the SAME prompt requires the model to edit it once the user " +
                "approves a proposal",
            !line.contains("read-only"),
        )
        assertTrue(
            "the GLOBAL.md line lost the approval requirement — `$line` — so the file " +
                "reads as freely writable state instead of user-owned",
            line.contains("approv", ignoreCase = true),
        )
    }

    /**
     * [fix/prompt-truth-1118c B F-03] …and the write path itself must stay. Deleting
     * the contradiction by deleting the permission is the other way to make the two
     * statements agree, and it would silently drop the feature the memory section
     * was added for.
     */
    @Test
    fun theGlobalMemoryWritePathIsStillStated() {
        val sec = rawLiteralAfter("val memorySystemSection = if (memoryOn) {")
        assertTrue(
            "the memory section no longer says how to update GLOBAL.md (file_read then " +
                "file_edit) — the propose-then-apply flow has no write path left",
            sec.contains("To update it, use file_read first then file_edit"),
        )
    }

    /**
     * [fix/prompt-truth-1118c B F-02] The admission criteria live only in
     * `memory-hygiene/SKILL.md`, which is loaded on demand. A pointer that says "run
     * the idea past the criteria" without telling the model to READ the skill leaves
     * an unread skill and a proposal that was never checked against the four tests.
     */
    @Test
    fun theMemoryAdmissionCriteriaLineAsksForAnActualRead() {
        val sec = rawLiteralAfter("val memorySystemSection = if (memoryOn) {")
        val at = sec.indexOf("admission criteria")
        assertTrue("the admission-criteria line is gone from the memory section", at >= 0)
        val lineStart = sec.lastIndexOf('\n', at).let { if (it < 0) 0 else it + 1 }
        val lineEnd = sec.indexOf('\n', at).let { if (it < 0) sec.length else it }
        val line = sec.substring(lineStart, lineEnd)
        assertTrue(
            "the admission-criteria line does not ask the model to READ the skill — " +
                "`$line` — the criteria are in an on-demand SKILL.md, so a bare " +
                "reference leaves them unread in the very turn that needs them",
            line.contains("read skill", ignoreCase = true),
        )
    }

    /**
     * The `base` prompt literal, with comments stripped. Read through the same
     * comment stripper as everything else so a KDoc mention of a section title
     * cannot stand in for the section itself.
     */
    private fun basePromptLiteral(): String {
        val src = stripComments(readRepoFile(promptRel))
        val start = src.indexOf("val base = identitySection + \"\"\"")
        assertTrue(
            "the `base` prompt literal is gone from $promptRel — the sections it " +
                "carries are no longer emitted at all",
            start >= 0,
        )
        val open = src.indexOf("\"\"\"", start)
        val end = rawStringEnd(src, open)
        return src.substring(open + 3, end - 3)
    }

    // ------------------------------------------------------------------
    // Scanner self-checks: the two matchers above run over a source file that
    // is mostly `"""…"""` prompt prose. Prose legitimately contains double
    // quotes and `//`, and a brace can be unbalanced inside it — both used to
    // derail the matchers, which showed up as five ordering tests failing on a
    // prompt-only edit (2026-11-18).

    @Test
    fun theCommentStripperLeavesRawPromptProseIntact() {
        val triple = "\"\"\""
        val src = buildString {
            append("val before = 1\n")
            append("val block = ").append(triple).append("\n")
            append("prompt text with \"quoted words\" and // not a comment\n")
            append("also /* not a block comment */ here\n")
            append(triple).append("\n")
            append("fun after() = 2\n")
        }
        val stripped = stripComments(src)
        assertTrue(
            "a `\"` inside a raw string must not end the string and swallow the rest of the file",
            stripped.contains("fun after() = 2"),
        )
        assertTrue("text after `//` inside a raw string is prose, not a comment", stripped.contains("not a comment"))
        assertTrue("a `/*` inside a raw string is prose", stripped.contains("not a block comment"))
    }

    @Test
    fun theBraceMatcherIgnoresBracesInsideRawPromptProse() {
        val triple = "\"\"\""
        val src = buildString {
            append("fun f() {\n")
            append("val block = ").append(triple).append("\n")
            append("prose with an unmatched { brace\n")
            append(triple).append("\n")
            append("val after = 1\n")
            append("}\n")
        }
        val body = braceBlock(src, 0)
        assertTrue(
            "an unbalanced `{` inside prompt prose must not end the block early",
            body.contains("val after = 1"),
        )
    }

    /**
     * [fix/prompt-authority-1118b B10] A raw string may END with a quote, written
     * `"""prose""""`. The terminator is the LAST three quotes of the run, so the
     * leading one is content. A scanner that stops at the first three leaves a
     * stray `"`, reads it as the start of an ordinary literal, and then ignores
     * every `{` until the next quote — the block ends in the wrong place.
     */
    @Test
    fun theBraceMatcherSurvivesAQuoteTerminatedRawString() {
        val triple = "\"\"\""
        val src = buildString {
            append("fun f() {\n")
            append("val block = ").append(triple)
            append("prose whose last char is a quote").append("\"\"\"\"\n")
            append("val after = 1\n")
            append("}\n")
        }
        val body = braceBlock(src, 0)
        assertTrue(
            "a `\"\"\"…\"\"\"\"` raw string must not desynchronise the brace matcher — " +
                "the fourth quote is content, not the start of a new literal",
            body.contains("val after = 1"),
        )
        assertTrue(
            "the whole literal must be skipped, not just up to the first three quotes",
            body.contains("\"\"\"\""),
        )
    }

    /**
     * [fix/prompt-authority-1118b B10] A `${…}` template expression is real code
     * and may hold its own raw string. Skipping the outer literal blind to that
     * means the inner opener looks like the outer terminator, and the rest of the
     * file is read as prose — here the inner raw string holds an UNBALANCED brace,
     * so a blind skip cannot even find the end of the outer block.
     */
    @Test
    fun theBraceMatcherSurvivesARawStringInsideATemplateExpression() {
        val triple = "\"\"\""
        val src = buildString {
            append("fun f() {\n")
            append("val block = ").append(triple).append("head \${g(").append(triple)
            append("x{y").append(triple).append(")}\n").append(triple).append("\n")
            append("val after = 1\n")
            append("}\n")
        }
        val body = braceBlock(src, 0)
        assertTrue(
            "a raw string nested inside ${'$'}{…} must not end the outer literal — the " +
                "matcher has to walk the template expression as code",
            body.contains("val after = 1"),
        )
    }

    /**
     * Same shapes, through the comment stripper.
     *
     * [fix/prompt-authority-1118b B10] The four-quote case is asserted on the
     * COMMENT, not on "the tail survived": a stripper that desynchronises stops
     * stripping, so the tail survives either way and the assertion would be
     * blind (measured: it was).
     */
    @Test
    fun theCommentStripperSurvivesQuoteTerminatedAndNestedRawStrings() {
        val triple = "\"\"\""
        val src = buildString {
            append("val a = ").append(triple).append("ends with a quote").append("\"\"\"\" // gone\n")
            append("val b = ").append(triple).append("nested \${h(").append(triple)
            append("x").append(triple).append(")}\n").append(triple).append("\n")
            append("fun after() = 2\n")
        }
        val stripped = stripComments(src)
        assertTrue(
            "a `//` comment right after a `\"\"\"…\"\"\"\"` literal must still be stripped — " +
                "the stray fourth quote used to open an ordinary string and swallow it",
            !stripped.contains("gone"),
        )
        assertTrue(
            "code after a raw string containing ${'$'}{…(\"\"\"…\"\"\")} must survive too",
            stripped.contains("fun after() = 2"),
        )
    }

    /**
     * The first `"""…"""` literal that opens after [anchor], with comments stripped.
     *
     * Same fail-closed shape as [basePromptLiteral]: read through the comment stripper
     * so a KDoc mention cannot stand in for the emitted text, and reject a suspiciously
     * short slice so a stale anchor surfaces as a failure instead of an empty string.
     */
    private fun rawLiteralAfter(anchor: String): String {
        val src = stripComments(readRepoFile(promptRel))
        val start = src.indexOf(anchor)
        assertTrue("`$anchor` not found in $promptRel", start >= 0)
        val open = src.indexOf("\"\"\"", start)
        assertTrue("no raw string literal opens after `$anchor`", open >= 0)
        val end = rawStringEnd(src, open)
        val body = src.substring(open + 3, end - 3)
        assertTrue(
            "the literal after `$anchor` is only ${body.length} chars — the anchor or " +
                "the matcher drifted, so assertions on it would be vacuous",
            body.length > 500,
        )
        return body
    }

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
            val rawString = c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"'
            when {
                // [prompt-behaviour-layer-1118] Raw strings FIRST: the prompt
                // blocks are `"""…"""` and their prose legitimately contains
                // `"quoted words"` and `//`-looking text. The single-quote
                // branch below used to end the "string" at the next `"`, which
                // flipped the state machine and made everything after it look
                // like a comment (5 ordering tests failed on a prompt edit).
                rawString -> {
                    // [fix/prompt-authority-1118b B10] Same terminator rule as
                    // [rawStringEnd]: the LAST three quotes of a run, and
                    // `${…}` walked as real code. Stopping at the first three
                    // left a stray `"` that opened an ordinary literal and made
                    // everything after it look like a comment (or vice versa).
                    val end = rawStringEnd(src, i)
                    out.append(src, i, end)
                    i = end
                }
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
