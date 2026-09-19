package com.rikkaminis.app.sandbox

/**
 * Strips ANSI escape sequences and handles CR-based line overwrites
 * from terminal output. Corresponds to iOS AIChatViewModel.sanitizeTerminalOutput().
 */
object TerminalSanitizer {

    /**
     * [audit-0919 F-215] Fallback output ceiling for callers that do not pass
     * the user's `shellOutputKb` knob. Kept at the historical 50 000 figure
     * (now interpreted as BYTES, so ~50 KB of ASCII / ~16.6 K CJK chars —
     * slightly smaller than the old char-based 50 000 for non-ASCII, which is
     * the point: the old value silently permitted 3× that in UTF-8).
     */
    const val DEFAULT_OUTPUT_CAP_BYTES = 50_000

    // Matches ANSI/VT escape sequences:
    //   ESC [ ... final_byte (CSI sequences)
    //   ESC ] ... ST (OSC sequences terminated by BEL or ESC\)
    //   ESC followed by single character (simple escapes)
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[0-9;]*[A-Za-z]|\][^\x07]*(?:\x07|\x1B\\)|\[[0-9;]*m|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * Sanitize terminal output in two passes:
     * 1. CR folding — simulate carriage return overwriting
     * 2. Strip remaining ANSI/VT escape sequences
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // Pass 1: CR folding
        val crFolded = foldCarriageReturns(raw)

        // Pass 2: Strip ANSI sequences
        val stripped = ANSI_REGEX.replace(crFolded, "")

        // Pass 3: Remove null bytes and non-printable control chars (except \n \t)
        val cleaned = stripped.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        // Pass 4: Remove "null" artifacts from PRoot/pipe issues
        // - Lines that are entirely "null"
        // - Runs of repeated "null" (e.g., "nullnullnull" → "")
        // - Lines that are just "null" appended to a prefix (e.g., "file:nullnullnull")
        val noNullLines = cleaned.lines()
            .filter { it.trim() != "null" }
            .joinToString("\n")
            .replace(Regex("(?:null){2,}"), "") // Remove runs of 2+ consecutive "null"

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2).
        // Deliberately no .trim() here: leading whitespace (progress-bar
        // prefixes like " 100%[...]") and trailing whitespace (padding that
        // erases a previously longer overwritten line) are semantically
        // meaningful, and sanitize must preserve them.
        return noNullLines.replace(Regex("\n{3,}"), "\n\n")
    }

    /**
     * Truncate output so its UTF-8 encoding fits [maxBytes], keeping head and
     * tail.
     *
     * [audit-0919 F-215] Accounting is in BYTES, not chars. The budget this
     * receives is `AgentRuntimeLimitsPrefs.shellOutputKb() * 1024` (a KB knob,
     * default 128 KB), and the same knob is what caps the shell-side buffer in
     * [PersistentShell] — so the unit has to be the one the knob is named in.
     * Measuring `String.length` made the effective ceiling 3× the declared one
     * for CJK (each char is 3 UTF-8 bytes) and 4× for astral-plane emoji.
     *
     * Cuts always land on a code-point boundary so a surrogate pair is never
     * split into a lone surrogate (which would corrupt the string when it is
     * re-encoded by the prompt builder / JSON serializer downstream).
     */
    fun truncateIfNeeded(output: String, maxBytes: Int = DEFAULT_OUTPUT_CAP_BYTES): String {
        val totalBytes = utf8Length(output)
        if (totalBytes <= maxBytes) return output

        val keepEach = maxBytes / 2
        val headEnd = byteSafePrefixLength(output, keepEach)
        val tailStart = byteSafeSuffixStart(output, keepEach)
        if (tailStart <= headEnd) return output.substring(0, headEnd)

        val head = output.substring(0, headEnd)
        val tail = output.substring(tailStart)
        val omittedBytes = totalBytes - utf8Length(head) - utf8Length(tail)
        return "$head\n\n[... $omittedBytes bytes omitted ...]\n\n$tail"
    }

    /**
     * Simulate CR (\r) behavior with real terminal column semantics: each \r
     * resets the write cursor to column 0 and subsequent characters overwrite
     * in place. This matters when a shorter line overwrites a longer one —
     * "AAAA\rBB" must yield "BBAA", not "BB" (the last-segment shortcut would
     * drop the tail that a real terminal keeps).
     *
     * \r\n is handled naturally: lines are split on \n first, so a trailing
     * \r in "line1\r" just resets the cursor with nothing following it.
     */
    private fun foldCarriageReturns(text: String): String {
        val lines = text.split('\n')
        val result = StringBuilder()

        for ((index, line) in lines.withIndex()) {
            if (index > 0) result.append('\n')

            if ('\r' !in line) {
                result.append(line)
                continue
            }

            result.append(foldLineWithCarriageReturns(line))
        }

        return result.toString()
    }

    private fun foldLineWithCarriageReturns(line: String): String {
        val buffer = StringBuilder()
        var cursor = 0
        for (ch in line) {
            if (ch == '\r') {
                cursor = 0
            } else {
                if (cursor == buffer.length) {
                    buffer.append(ch)
                } else {
                    buffer.setCharAt(cursor, ch)
                }
                cursor++
            }
        }
        return buffer.toString()
    }

    // ── UTF-8 byte accounting ([audit-0919 F-215]) ───────────────────────
    //
    // `String.toByteArray().size` would be the obvious implementation, but the
    // truncation path runs on every command result and allocating a full
    // UTF-16→UTF-8 copy of the output just to measure it doubles peak memory
    // for the largest strings the app handles. These walk code points instead
    // (O(n) time, O(1) allocation) and give the exact same numbers.

    /** UTF-8 encoded length of [s], in bytes. */
    internal fun utf8Length(s: String): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            bytes += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(cp)
        }
        return bytes
    }

    /**
     * Largest index into [s] such that `s.substring(0, index)` is at most
     * [maxBytes] UTF-8 bytes, never splitting a surrogate pair.
     */
    internal fun byteSafePrefixLength(s: String, maxBytes: Int): Int {
        if (maxBytes <= 0) return 0
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val w = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + w > maxBytes) return i
            bytes += w
            i += Character.charCount(cp)
        }
        return s.length
    }

    /**
     * Smallest index into [s] such that `s.substring(index)` is at most
     * [maxBytes] UTF-8 bytes, never starting on a low surrogate.
     */
    internal fun byteSafeSuffixStart(s: String, maxBytes: Int): Int {
        if (maxBytes <= 0) return s.length
        var bytes = 0
        var i = s.length
        while (i > 0) {
            val start = if (i >= 2 && Character.isLowSurrogate(s[i - 1]) &&
                Character.isHighSurrogate(s[i - 2])
            ) i - 2 else i - 1
            val cp = s.codePointAt(start)
            val w = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + w > maxBytes) return i
            bytes += w
            i = start
        }
        return 0
    }
}
