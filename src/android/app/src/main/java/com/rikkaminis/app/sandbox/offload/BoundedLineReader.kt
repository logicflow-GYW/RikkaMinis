package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Bounded, incremental line reader for append-only JSONL files
 * (stream.jsonl in the model-exec protocol).
 *
 * Guarantees:
 *   - per-call read is capped at [maxReadBytes]; a big write longer than that
 *     is consumed in bounded windows, never pulled whole into memory.
 *   - a single line longer than [maxLineBytes] is reported as
 *     [ReadResult.OversizedLine] and dropped, never ballooned in memory.
 *   - an unterminated trailing line is NOT consumed: the caller keeps the
 *     returned `newOffset` and simply re-polls from there, so the partial
 *     tail is re-read together with whatever the producer appends — a chunk
 *     is never split or lost across polls.
 *
 * Why no leftover buffer: the original ChatStreamOffloadHandler advances its
 * byte offset only to the last complete newline, so a trailing partial line is
 * naturally re-read on the next poll together with new bytes. Keeping a separate
 * in-memory leftover would DOUBLE-COUNT the partial region on re-read. This
 * class matches that proven, allocation-friendly pattern.
 *
 * Pure JVM (java.io). Single-threaded consumer only.
 */
class BoundedLineReader(
    private val maxReadBytes: Int = PayloadSizePolicy.MAX_POLL_READ_BYTES,
    private val maxLineBytes: Int = 256 * 1024,
) {

    sealed class ReadResult {
        /** Complete newline-terminated lines, and the new byte offset (just past
         *  the last consumed newline). */
        data class Lines(val lines: List<String>, val newOffset: Long) : ReadResult()

        /** A single unterminated line grew past [maxLineBytes] in this window; it
         *  was dropped. [lineStartOffset] is where the oversized line began. */
        data class OversizedLine(val lineStartOffset: Long, val lineByteLength: Long) : ReadResult()

        /** No complete new line available yet (only a partial trail, or nothing new). */
        object Partial : ReadResult()
    }

    /**
     * Read bytes in `[offset, newLen)` and split into complete lines.
     *
     * @param file the append-only JSONL file
     * @param offset byte offset at which to start reading
     * @param newLen current file length
     */
    fun readAppended(file: File, offset: Long, newLen: Long): ReadResult {
        if (offset >= newLen) return ReadResult.Partial
        val window = (newLen - offset).coerceAtMost(maxReadBytes.toLong())
        if (window <= 0) return ReadResult.Partial

        val bytes = ByteArray(window.toInt())
        val got = try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val n = raf.read(bytes, 0, bytes.size)
                if (n < 0) 0 else n
            }
        } catch (e: IOException) {
            return ReadResult.Partial
        }
        if (got <= 0) return ReadResult.Partial

        // window may have been truncated by maxReadBytes mid-line. If there is no
        // newline at all in what we read, it is either a fresh partial (Partial)
        // or an oversized single line (OversizedLine) — never keep it.
        val consumed = ByteArray(got)
        System.arraycopy(bytes, 0, consumed, 0, got)

        // Find last newline byte in this window.
        var lastNl = -1
        for (i in consumed.indices) {
            if (consumed[i] == '\n'.code.toByte()) lastNl = i
        }

        if (lastNl < 0) {
            // No complete line in the window.
            return if (got > maxLineBytes) {
                ReadResult.OversizedLine(offset, got.toLong())
            } else {
                ReadResult.Partial
            }
        }

        // Complete region is [0, lastNl); the trailing partial tail is [lastNl+1, got)
        // and is intentionally NOT consumed — the caller re-reads it next poll.
        val newOffset = offset + lastNl + 1
        val lines = decodeLines(consumed.copyOfRange(0, lastNl))
        return ReadResult.Lines(lines, newOffset)
    }

    private fun decodeLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val text = String(bytes, Charsets.UTF_8)
        val lines = text.split('\n')
        return lines.map { if (it.endsWith('\r')) it.dropLast(1) else it }
    }
}