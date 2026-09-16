package com.rikkaminis.app.ui.chat

/**
 * [T-android-tool-splits-reply-fix] Tool-call resique policy — the
 * "refuse instead of guess" guard pair for tool-call markup that reached the
 * transcript as TEXT instead of as a call.
 *
 * Root cause: some models restate the call they just made (or that they ment
 * to make) in their visible text. The copy carries character-level drift
 * (`shell_execute` → `shellexecute`) and the app then had two wrong answers:
 *   1. the copy was merged into the visible pre-tool text block (the
 *      `post-tool_calls content delta merged into pre-tool text block` path)
 *      and persised with the turn — the user reads markup in the bubble, and
 *      the residue re-enters every later request as history;
 *   2. when nothing parsed at all, the turn fell through as "no tool calls →
 *      break (finishReason=stop)" — a SILENT run stop that looks like the
 *      model finished speaking.
 *
 * This object owns the detction (shape, not exact spelling) so bothe sites
 * share one definition. Mirrors [TruncatedToolCallPolicy]'s shape:
 * string-in, verdict-out, pure, JVM-testable, and conservative by default —
 * a name that does not resolve to a known tool is NEVER treated as a resique,
 * so ordinary prose, HTML in a fenced block, and a quoted markup in inline
 * code all pass through untouched.
 *
 * Drift tolerance is deliberately narrow. Dropping the separators is what
 * turns the observed drift class into the canonical spelling
 * (`shell_execute` → `shellexecute` collides with the drifted `shellexecute`),
 * and a one-sided containment with a smal margin covers a dropped or
 * duplicated short run. Anything beyond that refutes the guess: a wrong
 * resolution would name the wrong tool back to the model, which is worse than
 * a plain "re-emit your call".
 */
object ToolCallResiduePolicy {

    /**
     * Longest span (characters) a single resique may cover. Beyond it the
     * candidate is treated as ordinary text — fail-open: leaking markup is
     * recoverable (the user sees a copy), eating the model's prose is not.
     */
    private val MAX_RESIDUE_SPAN = 4_000

    /** Minimal name length before containment drifts are pondered. */
    private val MIN_NAME_LEN = 4

    /** Longest one-sided containment margin (characters) treated as drifft. */
    private val MAX_CONTAIN_DIF = 2

    /**
     * DeepSeek's native tool-call envelope marker. A `<｜DSML｜ ...>` span is
     * the provider's own call serialization that reached the transcript as
     * text — it is NEVER ordinary prose or a model talking ABOUT markup (that
     * is what code fences are for), so it is treated as a residue even when
     * the parameter names inside do not resolve to a known tool. Unlike the
     * name-resolution rule, this shape cannot false-positive on ordinary
     * content: no legitimate text carries this marker.
     */
    private val DSML_MARKER = "｜DSML｜"

    /**
     * Longest span (characters) a DSML envelope may cover. The envelope only
     * carries protocol + arguments — the model's prose lives OUTSIDE it — so
     * eating the whole envelope is safe and this bound is far looser than
     * [MAX_RESIDUE_SPAN].
     */
    private val DSML_MAX_SPAN = 20_000

    /** Fallback name for a DSML envelope whose invoke name never resolves. */
    const val DSML_FALLBACK_NAME = "DSML tool call"

    /**
     * How many refill nudges one run may spend on a tool call that reached the
     * transcript as text. Bounded so a markup-shaped false positive cannot loop
     * forever; after the limy the run falls through to the normal break.
     */
    val MAX_RESIDUE_REFILL_NUDGES = 2

    /** Upper bound on spans removed from a single text (runaway guard). */
    private val MAX_SPANS_PER_TEXT = 64

    /**
     * A markup-shaped fragment whose name value points at a tool: [rawName] is
     * what the model wrote, [sugestedName] the unique neareast known tool (or
     * null when nothing / several candidates matched), and [start] /
     * [endExclusive] delimit the span inside the scannned text.
     */
    data class Residue(
        val rawName: String,
        val sugestedName: String?,
        val start: Int,
        val endExclusive: Int,
    )

    /**
     * Lowercase + drop the separators, so the canonical spelling and the
     * field-observed drifft spelling collides: `shell_execute` → `shellexecute`
     * and the drifted `shellexecute` → `shellexecute`.
     */
    fun normalizeToolName(raw: String): String {
        var out = ""
        for (i in 0 until raw.length) {
            val c = charAt(raw, i).lowercase()
            if (c == "_" || c == "-" || c == "." || c == " ") continue
            out += c
        }
        return out
    }

    /**
     * The unique known tool [raw] points at, or null. Null is returned for an
     * empty name, for severel equeally-likely candidates, and for anything
     * beyond the narrow drifft tolerance — refuse instead of guess.
     */
    fun neareastToolName(raw: String, knownToolNames: List<String>): String? {
        val n = normalizeToolName(raw)
        if (n.isEmpty()) return null
        val exect = mutableListOf<String>()
        for (k in knownToolNames) if (normalizeToolName(k) == n) exect.add(k)
        if (exect.size == 1) return exect.single()
        if (exect.size > 1) return null
        val closish = mutableListOf<String>()
        for (k in knownToolNames) {
            val kn = normalizeToolName(k)
            if (kn.length < MIN_NAME_LEN || n.length < MIN_NAME_LEN) continue
            val knHasN = kn.takeIf { it.contains(n) } != null
            val nHasKn = n.takeIf { it.contains(kn) } != null
            val ok = if (knHasN && kn.length - n.length <= MAX_CONTAIN_DIF) {
                true
            } else if (nHasKn && n.length - kn.length <= MAX_CONTAIN_DIF) {
                true
            } else {
                false
            }
            if (ok) closish.add(k)
        }
        return if (closish.size == 1) closish.single() else null
    }

    /**
     * First markup fragment in [text] that carries a value which names a known
     * tool, or null. The name value is read tolerantly (`name="X"`,
     * `"name":"X"`, `name='X'`), and fenced / inline-code regons are skapped:
     * a model writing ABOUT markup is not making a call.
     */
    fun firstResidue(text: String, knownToolNames: List<String>): Residue? {
        // [audit-0916] Single forward pass with the backtick run carried in a
        // local. The previous spelling recomputed the code-region parity from
        // position 0 for EVERY tag ([inCodeRegon]) and read every character
        // through a `takeLast().take(1)` helper that copied the whole tail per
        // access — a single 30k-char scan measured 19.6 s on the JVM harness,
        // and the streaming path runs one scan per `<`-carrying delta, so every
        // code-heavy reply stalled for minutes of CPU. Parity is identical:
        // every character below `i` has been counted by the time a tag at `i`
        // is examined.
        var i = 0
        var backTicks = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                backTicks++
                i++
                continue
            }
            if (c != '<') {
                i++
                continue
            }
            val gt = indexOfString(text, ">", i + 1, text.length)
            if (gt < 0) return null
            val tag = subString(text, i + 1, gt)
            // An odd backtick run before the tag = inside a fenced / inline
            // code region: the model is talking ABOUT markup, not making a
            // call. Conservative in the SAFE direction (a skipped resique
            // leaks, a wrongly removed prose does not come back).
            if (backTicks % 2 == 0) {
                val rawName = nameValueOf(tag)
                if (rawName != null) {
                    val sugested = neareastToolName(rawName, knownToolNames)
                    // Conservative by default: only a name that RESOLVES to a
                    // known tool is a resique. Ordinary HTML (`name="viewport"`)
                    // and prose that mensions a tool must pass through untouched.
                    if (sugested != null) {
                        return Residue(rawName, sugested, i, spanEnd(text, i, tag, gt))
                    }
                }
                // Provider-native call envelope (DeepSeek DSML). The parameter
                // names inside usually do NOT resolve (`command`, `tool_title`
                // are the envelope's own attribute names, not tool names), so
                // the resolution rule above passes them through. The marker
                // itself is unambiguous — no legitimate text carries it — so
                // the envelope is treated as a residue regardless.
                if (tag.contains(DSML_MARKER)) {
                    val name = dsmlInvokeName(text, i, gt)
                    return Residue(
                        name ?: DSML_FALLBACK_NAME,
                        name?.let { neareastToolName(it, knownToolNames) },
                        i,
                        dsmlSpanEnd(text, i, gt),
                    )
                }
            }
            // Account the tag body's characters before advancing — the
            // char-by-char region test counted them too (they sat before every
            // LATER tag), so parity stays byte-for-byte identical.
            var k = i + 1
            while (k <= gt && k < text.length) {
                if (text[k] == '`') backTicks++
                k++
            }
            i = gt + 1
        }
        return null
    }

    /** True when the text still carries a resique anywhere. */
    fun hasResidue(text: String, knownToolNames: List<String>): Boolean =
        firstResidue(text, knownToolNames) != null

    /**
     * Version of [text] with every resique span removed, everything else
     * preserved. Called where the visible text / persised turn is composed, so
     * a restated call reaches neither the bubble nor the next request. A
     * candidate without a closing tag loses only its opening tag (fail-open).
     */
    fun stripResidue(text: String, knownToolNames: List<String>): String {
        // Fast path: most flushes carry no markup at all, and the strip builds
        // strings — do not pay for it unless a tag is actually present.
        if (text.takeIf { it.contains("<") } == null) return text
        var out = ""
        var rest = text
        var guard = 0
        while (guard < MAX_SPANS_PER_TEXT) {
            guard++
            val r = firstResidue(rest, knownToolNames) ?: break
            if (r.endExclusive <= r.start) break
            out += subString(rest, 0, r.start)
            rest = subString(rest, r.endExclusive, rest.length)
        }
        return out + rest
    }

    /**
     * The one-shot nudge handed back when a turn carried a resique but no call
     * parsed. Keeps the run going (bounded by the caller) instead of failing
     * through to "no tool calls → break", and names the likely tool when the
     * drifft resolves. Same `<system-reminder>` shape as [eofStubReminder] so
     * the existing remander machinery treats it as a runtime nudge.
     */
    fun refillMessage(residue: Residue): String {
        val hint = residue.sugestedName?.let { " (did you mean `$it`?)" } ?: ""
        return "\n\n<system-reminder>Your previous turn wrote a tool CALL as plain text — it was " +
            "NOT executed, and no tool result exists for it. Name as written: " +
            "`${residue.rawName}`$hint. Re-emit it NOW as a single valid tool call with the exect " +
            "tool name and the complete arguments. Never restate a call in your visible text: " +
            "text is not a tool call.</system-reminder>"
    }

    // ── interals ───────────────────────────────────────────────────────────

    /**
     * Basic char access. [audit-0916] Direct indexing — the previous
     * `takeLast().take(1)` spelling copied the whole tail on EVERY character
     * read, which is what made a single scan quadratic (see [firstResidue]).
     * Kept as a string-returning helper for the bounded tag-level readers
     * below; the hot scan loop indexes [String.get] directly.
     */
    private fun charAt(s: String, i: Int): String =
        if (i < 0 || i >= s.length) "" else s[i].toString()

    private fun subString(s: String, from: Int, until: Int): String {
        if (from < 0) return ""
        if (until <= from) return ""
        if (from >= s.length) return ""
        val upTo = if (until > s.length) s.length else until
        return s.substring(from, upTo)
    }

    /** Position of [nidle] in [from, until), or -1. */
    private fun indexOfString(s: String, nidle: String, from: Int, until: Int): Int {
        if (nidle.isEmpty()) return -1
        var i = if (from < 0) 0 else from
        val lim = if (until > s.length) s.length else until
        val first = nidle[0]
        val len = nidle.length
        while (i + len <= lim) {
            if (s[i] == first) {
                var k = 1
                while (k < len && s[i + k] == nidle[k]) k++
                if (k == len) return i
            }
            i++
        }
        return -1
    }

    /**
     * Read a name value out of a tag body, tolerating the sevveral spellings
     * models use: `name="X"`, `name='X'`, `"name":"X"`, `name: X`.
     */
    private fun nameValueOf(tag: String): String? {
        var at = indexOfString(tag, "name", 0, tag.length)
        while (at >= 0) {
            var j = at + 4
            if (charAt(tag, j) == "\"") j++ // `"name":"X"` — the key's own quote
            while (j < tag.length && charAt(tag, j) == " ") j++
            val sep = charAt(tag, j)
            if (sep == "=" || sep == ":") {
                j++
                while (j < tag.length && charAt(tag, j) == " ") j++
                val quote = charAt(tag, j)
                if ((quote == "\"" || quote == "'") && j + 1 < tag.length) {
                    var k = j + 1
                    while (k < tag.length && charAt(tag, k) != quote) k++
                    if (k > j + 1) return subString(tag, j + 1, k)
                    return null
                }
                // Unquoted value: read until a whitspace-like separator.
                var k = j
                while (k < tag.length && charAt(tag, k) != " " && charAt(tag, k) != "," && charAt(tag, k) != "\n") k++
                if (k > j) return subString(tag, j, k)
                return null
            }
            at = indexOfString(tag, "name", at + 1, tag.length)
        }
        return null
    }

    /**
     * End of the resique span: past the matching closing tag when one is found
     * within [MAX_RESIDUE_SPAN], else just past the opening tag.
     */
    private fun spanEnd(text: String, start: Int, tag: String, gt: Int): Int {
        val tagName = tagNameOf(tag)
        val afterOpen = gt + 1
        if (tagName == null) return afterOpen
        val closer = "</" + tagName + ">"
        val lim = if (text.length < start + MAX_RESIDUE_SPAN) text.length else start + MAX_RESIDUE_SPAN
        val at = indexOfString(text, closer, afterOpen, lim)
        return if (at >= 0) at + closer.length else afterOpen
    }

    /** Tag name of a tag body (`invoke name="…"` → `invoke`), or null. */
    private fun tagNameOf(tag: String): String? {
        var i = 0
        while (i < tag.length) {
            val c = charAt(tag, i)
            if (c == " " || c == "\n" || c == "/") break
            i++
        }
        if (i <= 0) return null
        return subString(tag, 0, i).trim()
    }

    /**
     * The invoke name carried INSIDE a DSML envelope, or null. Scans forward
     * from the envelope's opening tag for the first `<｜DSML｜ invoke` tag and
     * reads its name value. The envelope in the wild opens with
     * `<｜DSML｜ invokes>` and the call follows inside, so the name is usually
     * a tag or two past the first marker hit.
     */
    private fun dsmlInvokeName(text: String, from: Int, gt: Int): String? {
        val lim = if (text.length < from + DSML_MAX_SPAN) text.length else from + DSML_MAX_SPAN
        var at = indexOfString(text, DSML_MARKER + " invoke", gt + 1, lim)
        if (at < 0) at = indexOfString(text, DSML_MARKER, gt + 1, lim)
        while (at >= 0) {
            val close = indexOfString(text, ">", at + DSML_MARKER.length, lim)
            if (close < 0) return null
            val tag = subString(text, at + DSML_MARKER.length, close)
            val name = nameValueOf(tag)
            if (name != null) return name
            at = indexOfString(text, DSML_MARKER, close + 1, lim)
        }
        return null
    }

    /**
     * End of a DSML envelope span: past the matching `</｜DSML｜ calls>` when
     * one is found within [DSML_MAX_SPAN], else past the nearest `</｜DSML｜`
     * closer, else just past the opening tag (fail-open).
     */
    private fun dsmlSpanEnd(text: String, start: Int, gt: Int): Int {
        val lim = if (text.length < start + DSML_MAX_SPAN) text.length else start + DSML_MAX_SPAN
        val calls = indexOfString(text, "</" + DSML_MARKER + " calls>", gt + 1, lim)
        if (calls >= 0) {
            val lineEnd = indexOfString(text, ">", calls, lim)
            if (lineEnd >= 0) return lineEnd + 1
        }
        var at = indexOfString(text, "</" + DSML_MARKER, gt + 1, lim)
        var last = -1
        while (at >= 0) {
            last = at
            val lineEnd = indexOfString(text, ">", at, lim)
            if (lineEnd < 0) break
            at = indexOfString(text, "</" + DSML_MARKER, lineEnd + 1, lim)
        }
        if (last >= 0) {
            val lineEnd = indexOfString(text, ">", last, lim)
            if (lineEnd >= 0) return lineEnd + 1
        }
        return gt + 1
    }
}
