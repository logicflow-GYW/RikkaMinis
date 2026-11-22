package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.LLMStreamChunk
import java.io.File

/**
 * [surgery-1120 A-01 review] Cold-start recovery MUST NOT load a run's whole
 * `stream.jsonl` (or hold every dir's body at once).
 *
 * The live collector never does: it streams through [BoundedLineReader] in
 * bounded windows with a per-line ceiling. Recovery used `File.readLines()`
 * twice per dir — once so the eligibility check could see the DONE frame, then
 * again to join the text — for EVERY directory in the staging root, including
 * active, terminal-committed and non-chat dirs that are never recovered. A
 * long answer (or a handful of them on a device killed mid-run) pulled tens of
 * MB of strings through one pass, on the same IO dispatcher the next launch
 * needs.
 *
 * This scanner reproduces the per-line semantics without the blow-up:
 *  - same per-line byte ceiling as the wire protocol
 *    (`base64(MAX_INLINE_BINARY_BYTES)`, the collector's own number);
 *  - a frame longer than the ceiling is counted as dropped and skipped, never
 *    decoded (the live reader drops it too, so the recovered text matches what
 *    the user could have seen);
 *  - at most ONE decoded frame is held at a time, and only its visible text is
 *    appended to the caller's StringBuilder;
 *  - [maxBytes] bounds how much of the file is read at all; the scan reports
 *    `complete = false` when it stops early, so a caller can refuse to write a
 *    partial row rather than silently truncate an answer.
 *
 * Pure JVM (java.io + the project's own JSONL decoder) so the recovery test
 * can drive it on real temp files.
 */
internal object StreamScan {

    /**
     * Mirrors the collector's `maxStreamLineBytes`: Base64(512 KiB inline
     * budget) ≈ 683 KiB, kept strictly below one read window so the reader's
     * "no newline in window" branch can actually fire.
     */
    const val MAX_LINE_BYTES: Int = 700 * 1024

    /** Read window, mirroring [PayloadSizePolicy.MAX_POLL_READ_BYTES]. */
    const val READ_WINDOW_BYTES: Int = PayloadSizePolicy.MAX_POLL_READ_BYTES

    data class Result(
        /** Visible text deltas in order (the recovered answer). */
        val text: String,
        /** A clean-completion frame was seen. */
        val done: Boolean,
        /** An error frame was seen. */
        val error: Boolean,
        /** The whole file was scanned (false = [maxBytes] stopped it). */
        val complete: Boolean,
        /** Frames dropped for exceeding [MAX_LINE_BYTES]. */
        val droppedFrames: Int,
    )

    /**
     * Scan [file] once. [maxBytes] caps the read; a file longer than that is
     * scanned only up to the last complete line inside the budget and reported
     * `complete = false`. Terminal-only probes set [collectText] to false so
     * visible text is not aggregated into memory.
     */
    fun scan(
        file: File,
        maxBytes: Long = Long.MAX_VALUE,
        collectText: Boolean = true,
    ): Result {
        if (!file.isFile || file.length() <= 0L) {
            return Result("", done = false, error = false, complete = true, droppedFrames = 0)
        }
        val length = file.length()
        val limit = minOf(length, maxBytes.coerceAtLeast(0L))
        val sb = StringBuilder()
        var done = false
        var error = false
        var dropped = 0
        var complete = true
        val reader = BoundedLineReader(maxReadBytes = READ_WINDOW_BYTES, maxLineBytes = MAX_LINE_BYTES)
        var offset = 0L
        while (offset < limit) {
            when (val r = reader.readAppended(file, offset, limit)) {
                is BoundedLineReader.ReadResult.Lines -> {
                    // A dropped frame inside the window is skipped by newOffset;
                    // count it so the caller can log a shape it cannot recover.
                    dropped += r.oversizedLines.size
                    for (line in r.lines) {
                        when {
                            ChatStreamJsonl.isDone(line) -> done = true
                            ChatStreamJsonl.isError(line) -> error = true
                            else -> {
                                val chunk = ChatStreamJsonl.decode(line)
                                if (collectText && chunk is LLMStreamChunk.Text) sb.append(chunk.text)
                            }
                        }
                    }
                    if (r.newOffset <= offset) break // defensive: no progress
                    offset = r.newOffset
                }
                is BoundedLineReader.ReadResult.OversizedLine -> {
                    dropped++
                    if (r.lineByteLength <= 0L) break
                    offset = r.lineStartOffset + r.lineByteLength
                }
                BoundedLineReader.ReadResult.Partial -> {
                    // No complete line in the rest of the budget: either the
                    // file ends in a partial tail (a killed worker) or the
                    // budget cut mid-line. Both mean "not a complete frame".
                    complete = false
                    break
                }
            }
        }
        if (limit < length) complete = false
        return Result(sb.toString(), done, error, complete, dropped)
    }
}
