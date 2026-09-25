package com.rikkaminis.app.ui.components

/**
 * [T-android-memory-file-jank] Line chunking for the virtualized memory-file
 * viewer.
 *
 * Background: memory files (GLOBAL.md / daily logs) are plain monospace text
 * that grows past 200KB. Both the old reader path and the settings editor path
 * handed the WHOLE string to a single [androidx.compose.foundation.text.BasicTextField]
 * / `Text`. Compose text layout is not incremental across scroll: every frame
 * re-measures the full text, so past ~100KB each scroll frame costs 150-250ms
 * (measured on device: 60KB -> 26ms, 110KB -> 200ms, 206KB -> 200ms, with
 * rendered-frame count collapsing 426 -> 50 for the same gesture).
 *
 * The fix is to give the reader a [androidx.compose.foundation.lazy.LazyColumn]
 * of bounded chunks so only the on-screen chunks are measured. This file is the
 * pure, Android-free half of that fix — it decides where the chunk boundaries
 * go and nothing else, so it is unit-testable on the JVM.
 *
 * [chunkText] keeps the exact character stream: joining the returned chunks with
 * "\n" reproduces [text] byte-for-byte. That property is asserted in
 * `MemoryTextChunksTest`; it is what proves no character was added, dropped or
 * reordered at a chunk boundary.
 *
 * Note the viewer does NOT actually join the chunks — it renders one item per
 * chunk, and vertical stacking supplies the line break. The "\n" in the join is
 * the round-trip *test*'s reconstruction of the original stream, not something
 * the render path re-inserts. (An earlier version prefixed "\n" to every chunk
 * after the first, which rendered one blank line per boundary and duplicated
 * into cross-chunk clipboard copies — SelectionManager already appends '\n'
 * between selectables.)
 */

/** Lines per chunk before the byte cap is consulted. ~1 screen of 18sp text. */
public const val MEMORY_CHUNK_MAX_LINES: Int = 40

/**
 * Per-chunk byte ceiling. Bounds the cost of laying out a chunk that contains
 * very long lines (real memory files have single lines up to 2221 chars, which
 * wrap into dozens of display lines each). 4KB keeps a chunk's layout well
 * under one frame.
 */
public const val MEMORY_CHUNK_MAX_BYTES: Int = 4 * 1024

/**
 * Chunks of slack added on each side of the viewer's visible range when the
 * edit window is built ([buildEditWindow]). 1 gives the editor ~1 screen of
 * scroll room in each direction beyond what the user was looking at, keeping
 * the window at ~3-5 chunks (~12-20KB) so text layout stays O(window).
 */
public const val MEMORY_EDIT_WINDOW_MARGIN_CHUNKS: Int = 1

/**
 * Split [text] into ordered chunks of at most [maxLines] lines and at most
 * [maxBytes] UTF-8 bytes, whichever binds first.
 *
 * Guarantees (all asserted in tests):
 *  - `chunks.joinToString("\n") == text` — no character is added, dropped or
 *    reordered.
 *  - Every chunk holds at least one line, so a single line longer than
 *    [maxBytes] still advances the walk instead of stalling it.
 *  - Empty input yields an empty list (callers render their own empty state).
 */
public fun chunkText(
    text: String,
    maxLines: Int = MEMORY_CHUNK_MAX_LINES,
    maxBytes: Int = MEMORY_CHUNK_MAX_BYTES,
): List<String> {
    if (text.isEmpty()) return emptyList()
    require(maxLines >= 1) { "maxLines must be >= 1, was $maxLines" }
    require(maxBytes >= 1) { "maxBytes must be >= 1, was $maxBytes" }

    val lines = text.split('\n')
    val out = ArrayList<String>((lines.size / maxLines) + 1)
    var start = 0
    while (start < lines.size) {
        var end = start
        var bytes = 0
        while (end < lines.size) {
            // +1 accounts for the "\n" that the join re-inserts after this line.
            val lineBytes = lines[end].toByteArray(Charsets.UTF_8).size + 1
            // `end > start` keeps the first line of every chunk unconditionally:
            // without it an over-cap single line would loop forever.
            if (end > start && (end - start >= maxLines || bytes + lineBytes > maxBytes)) break
            bytes += lineBytes
            end++
        }
        out.add(lines.subList(start, end).joinToString("\n"))
        start = end
    }
    return out
}

/**
 * Start offset of each chunk in `chunks.joinToString("\n")`. Chunk k starts
 * at the sum of (chunk j length + 1) for all j < k — the +1 is the "\n" the
 * join re-inserts after every chunk but the last.
 */
public fun chunkStartOffsets(chunks: List<String>): List<Int> {
    val out = ArrayList<Int>(chunks.size)
    var offset = 0
    for (chunk in chunks) {
        out.add(offset)
        offset += chunk.length + 1
    }
    return out
}

/**
 * A contiguous window of chunks plus its byte-exact position in the full
 * text. This is the unit of the windowed memory-file editor: the editor only
 * holds [text] (O(window) layout cost), and [startOffset]/[endOffset] let
 * Save splice the edited window back into the full file via
 * [spliceEditWindow].
 */
public data class EditWindow(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Build an [EditWindow] covering chunks [firstChunk]..[lastChunkInclusive]
 * (both clamped into range, order-insensitive). Returns null for empty input.
 * Guarantees (asserted in tests):
 *  - `base.substring(startOffset, endOffset) == text` for the base the chunks
 *    came from — the window is byte-exact, so splice-back cannot lose or
 *    duplicate characters at the boundary.
 *  - [text] round-trips: it is exactly the joined chunks, no character added
 *    or dropped.
 */
public fun buildEditWindow(
    chunks: List<String>,
    firstChunk: Int,
    lastChunkInclusive: Int,
): EditWindow? {
    if (chunks.isEmpty()) return null
    val first = minOf(firstChunk, lastChunkInclusive).coerceIn(0, chunks.size - 1)
    val last = maxOf(firstChunk, lastChunkInclusive).coerceIn(first, chunks.size - 1)
    val text = chunks.subList(first, last + 1).joinToString("\n")
    val start = chunkStartOffsets(chunks)[first]
    return EditWindow(
        text = text,
        startOffset = start,
        endOffset = start + text.length,
    )
}

/**
 * Replace the byte range [startOffset, endOffset) of [base] with [newText].
 * Offsets must come from an [EditWindow] built from the SAME base text —
 * callers keep the base captured at edit entry instead of re-reading the
 * file, so offsets stay valid. Any stale/malformed offset returns [base]
 * unchanged (refuse instead of guess).
 */
public fun spliceEditWindow(
    base: String,
    startOffset: Int,
    endOffset: Int,
    newText: String,
): String {
    if (startOffset < 0 || startOffset > base.length) return base
    if (endOffset < startOffset || endOffset > base.length) return base
    return base.substring(0, startOffset) + newText + base.substring(endOffset)
}
