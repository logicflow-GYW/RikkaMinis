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
    //   ESC [ params intermediates final  (CSI sequences, ECMA-48 §5.4)
    //   ESC ] ... ST                      (OSC, terminated by BEL or ESC\)
    //   ESC ( X / ESC ) X                 (charset designation)
    //   ESC X                             (simple escapes)
    //
    // [meta-audit-1028 E3 / F-08] Three defects fixed in this one expression —
    // each was reproduced against this file before the change
    // (`probe-e3/F08Probe.kt`, `F08_PROBE=FAIL(4)`):
    //
    //  * OSC body was `[^\x07]*`, which happily ate ESC and newlines, so the
    //    greedy match ran from the FIRST `ESC ]` to the LAST terminator in the
    //    whole output. Two OSC 8 hyperlinks (`ls --hyperlink`, gcc/cargo
    //    diagnostics) therefore deleted the visible text BETWEEN them:
    //    `before ESC]8;;url ESC\ TEXT ESC]8;; ESC\ after` → `before  after`.
    //    The body now excludes ESC, so it can only end at its own terminator.
    //  * CSI was `\[[0-9;]*[A-Za-z]`, i.e. no DEC private-mode prefix (`?`) and
    //    no intermediate bytes. `ESC[?25l` / `ESC[?25h` (what `top`, `tqdm`,
    //    npm and pip emit to hide/show the cursor) never matched, pass 3 then
    //    deleted only the ESC, and the literal `[?25l` stayed in the output.
    //    The parameter class is now the full ECMA-48 one (`0x30..0x3F`, which
    //    includes `?`), followed by intermediates (`0x20..0x2F`) and a final
    //    byte (`0x40..0x7E`).
    private val ANSI_REGEX = Regex(
        """\x1B(?:\[[\x30-\x3F]*[\x20-\x2F]*[\x40-\x7E]|\][^\x07\x1B]*(?:\x07|\x1B\\)|[()][0-2AB]|[A-Za-z])"""
    )

    /**
     * Sanitize terminal output in two passes:
     * 1. Strip ANSI/VT escape sequences
     * 2. CR folding — simulate carriage return overwriting
     *
     * [meta-audit-1028 E3 / F-08] The order matters and used to be the reverse.
     * CR folding assigns columns by CHAR INDEX, so every escape byte still in
     * the line counted as a written column. A colored progress line —
     * `ESC[33mDownloading 50%ESC[0m\rDone!          ` — has 9 invisible bytes
     * ahead of the 15 visible ones, so the 15-char overwrite only reached
     * column 15 and the tail of the old line survived the strip:
     * `Done!          g 50%`. When the new line is shorter than the escape
     * prefix (`ESC[33mABC\rOK`) it instead overwrites the escape's own head,
     * leaving `OK33mABC` — the `33m` fragment the report described.
     * Stripping first makes the fold operate on exactly the bytes a real
     * terminal would paint, which is what CR semantics are defined over.
     */
    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return raw

        // Pass 1: Strip ANSI sequences (must precede CR folding — see above)
        val stripped = ANSI_REGEX.replace(raw, "")

        // Pass 2: CR folding
        val crFolded = foldCarriageReturns(stripped)

        // Pass 3: Remove null bytes and non-printable control chars (except \n \t)
        val cleaned = crFolded.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        // Pass 4 (REMOVED 2026-10-15, [verify-1015 BUG-02]): a filter that
        // deleted every line whose trimmed text was "null", plus a
        // `(?:null){2,}` run-collapse applied to the whole text. It claimed to
        // remove "PRoot/pipe artifacts", but that artifact was never
        // reproduced or recorded anywhere, while the collateral damage was
        // real and reproducible:
        //   * a legitimate `null` line vanished from tool output — probe
        //     `printf 'BEGIN\nnull\nEND\n'` returned BEGIN/END while the
        //     sandbox-side text still contained the line (2026-10-15). Same
        //     mechanism as the "shell_execute swallows bare null lines" quirk
        //     first hit on 2026-09-30, where the loss was misattributed to the
        //     output pipe;
        //   * the run-collapse edited text ANYWHERE, not just whole lines: it
        //     had already eaten its own documentation example — the line that
        //     spelled the pattern out now reads `(e.g., "" → "")`, i.e. the
        //     literal was silently excised from this very file.
        // Content fidelity wins over suppressing an unverified artifact: pass
        // the shell's text through unchanged and let the caller see exactly
        // what was written. If a real "null" artifact is ever observed, record
        // the command that produced it first, then narrow the filter to that
        // shape instead of dropping a whole text class.

        // Pass 5: Collapse excessive blank lines (3+ consecutive → 2).
        // Deliberately no .trim() here: leading whitespace (progress-bar
        // prefixes like " 100%[...]") and trailing whitespace (padding that
        // erases a previously longer overwritten line) are semantically
        // meaningful, and sanitize must preserve them.
        return cleaned.replace(Regex("\n{3,}"), "\n\n")
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

    /**
     * UTF-8 encoded length of [s], in bytes.
     *
     * [audit-1015 PERF-01] Takes [CharSequence] so the truncation path can
     * measure an existing StringBuilder WITHOUT materialising a full
     * `toString()` copy first — the per-chunk recompute used to copy the
     * whole accumulated output every 4 KB read just to measure it.
     */
    internal fun utf8Length(s: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
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
