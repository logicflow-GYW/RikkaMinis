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
