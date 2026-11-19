package com.rikkaminis.app.offload

import com.rikkaminis.app.sandbox.TerminalSanitizer

/**
 * [FULLSCAN-1119 F-03] Byte-bounded capture buffer for a child process's
 * stdout/stderr.
 *
 * Why this exists: `ShizukuBackend.runProcess` used to append every line into a
 * bare `StringBuilder` with no ceiling, so one command with a large output
 * (`du -sb` over a big tree, `find | xargs`, a large log dump) grew the UI
 * process until the system killed it. That is an app-level crash, not a
 * sandbox problem.
 *
 * Contract:
 *  - **Bound**: at most [maxBytes] UTF-8 bytes are retained per stream.
 *    Accounting is in BYTES, not chars or lines — one 200 MB line must not be
 *    able to balloon the buffer, and a CJK-heavy line must not silently get 3×
 *    the declared budget.
 *  - **Observable**: [truncated] flips as soon as anything is dropped, and
 *    [truncationMarker] returns a one-line explanation carrying the actual cap
 *    and the dropped byte count. Callers must surface it (ShizukuBackend
 *    appends it to stderr), never swallow it.
 *  - **Normal path unchanged**: with output under the cap the returned text is
 *    byte-for-byte what the previous `readLine()` loop produced — `\r`, `\n`
 *    and `\r\n` are all line terminators (that is exactly what
 *    `BufferedReader.readLine()` did), each line is re-terminated with `\n`,
 *    and the caller keeps its trailing `trimEnd('\n')`.
 *
 * Memory: the pending partial line is capped at [maxBytes] CHARS, so the worst
 * case before a flush is 4×[maxBytes] bytes (astral-plane text) — still
 * bounded, and the pipe keeps draining so a chatty child never blocks.
 *
 * Pure JVM (no Android imports) so the bound is pinned by a unit test instead
 * of by reading the code.
 */
internal class BoundedStreamCapture(
    /** Stream label used in the truncation marker ("stdout" / "stderr"). */
    private val label: String,
    /** Retained-byte ceiling for this stream. */
    val maxBytes: Int,
) {
    private val kept = StringBuilder()
    private val pending = StringBuilder()

    /** UTF-8 bytes currently retained in [kept]. */
    private var keptBytes: Int = 0

    /** True once any byte was dropped. */
    var truncated: Boolean = false
        private set

    /** Bytes (approximate, UTF-8) that were discarded after the ceiling. */
    var droppedBytes: Long = 0L
        private set

    /** Feed the next chunk of chars read from the child's pipe. */
    fun append(chars: CharArray, length: Int) {
        var i = 0
        while (i < length) {
            val c = chars[i]
            if (c == '\n' || c == '\r') {
                // CRLF is one terminator, matching readLine().
                if (c == '\r' && i + 1 < length && chars[i + 1] == '\n') i++
                flushLine()
            } else {
                appendToPending(c)
            }
            i++
        }
    }

    /** Flush the last (unterminated) line. Call once, after the pipe is drained. */
    fun finish() {
        if (pending.isNotEmpty()) flushLine()
    }

    /** Retained text, with the same trailing-newline shape the caller trims. */
    fun text(): String = kept.toString()

    /**
     * One-line explanation for the caller to surface (appended to stderr), or
     * null when nothing was dropped. Names the ACTUAL cap so a user who hit it
     * can tell whether raising the knob will help.
     */
    fun truncationMarker(): String? {
        if (!truncated) return null
        return "[$label truncated: kept the first $maxBytes of $maxBytes+ bytes, dropped ~$droppedBytes byte(s); " +
            "raise the shell output ceiling (runtime.shellOutputKb) if you need the whole output]"
    }

    private fun appendToPending(c: Char) {
        if (truncated) {
            droppedBytes += utf8WidthOf(c)
            return
        }
        // Cap the pending line so a single enormous line cannot balloon the
        // buffer before its terminator arrives.
        if (pending.length >= maxBytes) {
            truncated = true
            droppedBytes += utf8WidthOf(c)
            return
        }
        pending.append(c)
    }

    private fun flushLine() {
        if (pending.isEmpty()) {
            // An empty line still occupies one byte in the old loop's output.
            if (keptBytes < maxBytes) {
                kept.append('\n')
                keptBytes += 1
            } else {
                truncated = true
                droppedBytes += 1
            }
            return
        }
        val remaining = maxBytes - keptBytes
        if (remaining <= 0) {
            truncated = true
            droppedBytes += TerminalSanitizer.utf8Length(pending.toString()) + 1L
            pending.setLength(0)
            return
        }
        // Never split a surrogate pair / multi-byte char at the cut.
        val line = pending.toString()
        val fitted = TerminalSanitizer.byteSafePrefixLength(line, remaining)
        if (fitted < pending.length) {
            truncated = true
            droppedBytes += TerminalSanitizer.utf8Length(line.substring(fitted)) + 1L
        }
        kept.append(line, 0, fitted)
        keptBytes += TerminalSanitizer.utf8Length(line.substring(0, fitted))
        pending.setLength(0)
        if (keptBytes < maxBytes) {
            kept.append('\n')
            keptBytes += 1
        } else {
            truncated = true
            droppedBytes += 1
        }
    }

    private fun utf8WidthOf(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        // Surrogates are counted as 3 each here; this path only runs AFTER the
        // ceiling, where the number is a diagnostic estimate, not a budget.
        else -> 3
    }
}
