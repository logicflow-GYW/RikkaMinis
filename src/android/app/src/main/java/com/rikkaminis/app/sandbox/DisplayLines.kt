package com.rikkaminis.app.sandbox

/**
 * Display-line plumbing for streamed shell output.
 *
 * The persistent-shell reader hands the display layer one *read chunk* at a
 * time, and a chunk boundary routinely falls in the middle of a line: the
 * marker scan withholds the trailing `markerPattern.length - 1` characters of
 * every chunk so a completion marker split across reads is still detected
 * (see [internalScanMarker]). Feeding that truncated text straight to a
 * "here is a line" callback therefore emitted the unterminated tail as if it
 * were a complete line, and the next chunk's continuation started a new one —
 * phantom line breaks in the middle of words ("Deleted branch fix/audi" /
 * "t-0909-b22 (was 54e" / "ade2)."). Because `AgentLoopEngine` keeps the
 * *longer* of {streamed content, final output}, the polluted text (strictly
 * longer thanks to the extra newlines) then won and stayed on screen in the
 * "RikkaMinis Computer" sheet even after the tool finished.
 *
 * Two pure pieces fix that without giving up real-time updates:
 *  - [splitDisplayLines] carries the unterminated tail across chunks instead of
 *    emitting it as a line, and
 *  - [DisplayLineBuffer] lets a fragment be *replaced* by its completed form
 *    instead of being appended after it.
 *
 * No Android imports, so [PersistentShellTest] can cover both on the JVM.
 */

/** Complete lines of a chunk plus its unterminated tail. */
internal data class DisplayLineChunk(
    val complete: List<String>,
    val pendingPartial: String,
)

/**
 * Split [text] into display lines, prepending [previousPartial] (the
 * unterminated tail of the previous chunk) so a line straddling a chunk
 * boundary is reassembled rather than split in two.
 *
 * @return the `'\n'`-terminated lines plus the new unterminated tail (empty
 *   when [text] ends with `'\n'`). An empty [text] changes nothing.
 */
internal fun splitDisplayLines(previousPartial: String, text: String): DisplayLineChunk {
    if (text.isEmpty()) return DisplayLineChunk(emptyList(), previousPartial)
    val combined = previousPartial + text
    val parts = combined.split('\n')
    return if (combined.endsWith('\n')) {
        // split() leaves an empty element after the final '\n'.
        DisplayLineChunk(parts.dropLast(1), "")
    } else {
        DisplayLineChunk(parts.dropLast(1), parts.last())
    }
}

/**
 * Rolling window of the last [maxLines] display lines of one tool block.
 *
 * [onPartialLine] *replaces* the trailing fragment — a terminal appends to the
 * current line, it never starts a new one. [onCompleteLine] commits a line and
 * supersedes any pending fragment, which is that same line's prefix.
 */
internal class DisplayLineBuffer(private val maxLines: Int = 50) {
    private val committed = ArrayDeque<String>()
    private var partial: String = ""

    fun onCompleteLine(line: String) {
        if (partial.isNotEmpty()) partial = "" // completed form supersedes it
        committed.addLast(line)
        while (committed.size > maxLines) committed.removeFirst()
    }

    fun onPartialLine(line: String) {
        partial = line
    }

    fun render(): String {
        val lines = ArrayList<String>(committed.size + 1)
        lines.addAll(committed)
        if (partial.isNotEmpty()) lines.add(partial)
        return lines.takeLast(maxLines).joinToString("\n")
    }
}
