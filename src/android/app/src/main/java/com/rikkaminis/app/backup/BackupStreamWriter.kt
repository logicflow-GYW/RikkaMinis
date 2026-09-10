package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer

/**
 * [T-backup-streaming-export] Streaming writer for the backup document.
 *
 * WHY THIS EXISTS: building the payload as one in-memory String does not
 * scale. A 64MB document (budget-trimmed chat history) means a 129MB
 * char[] (UTF-16) for the final String, a ~130MB intermediate
 * StringBuilder during toString, AND the fully materialized JSONObject
 * tree (~250MB for ~150k messages) — 500MB+ of transient allocations on
 * a 512MB largeHeap. Measured on-device 2026-08-31: OOM at the final
 * toString with exactly payload×2 as the failing allocation.
 *
 * The fix keeps the *budget algorithm* unchanged (it decides WHAT to
 * include) and replaces the *assembly* with a streaming write: the
 * document is emitted chunk by chunk into a [Writer] (file output stream
 * / network request body), so the final String and the intermediate
 * StringBuilder never exist. Peak drops to the JSON tree of the kept
 * messages + per-chunk temporaries.
 *
 * Chunk shapes are byte-identical to what JSONObject.toString() emits for
 * the same data (compact form, same key order — see JsonChunkSink notes),
 * so an import cannot tell a streamed document from a legacy one.
 */
internal object BackupStreamWriter {

    /**
     * Emits `{"k":v,...}` with proper leading `{`/trailing `}` and `,`
     * separators, calling [emitValue] once per entry. Pure control of the
     * object framing; each value is written by the caller so a huge value
     * (e.g. chatMessages) can stream itself.
     */
    fun writeObjectFrame(
        writer: Writer,
        entries: List<String>,
        emitValue: (Writer, String) -> Unit,
    ) {
        writer.write("{")
        for ((i, key) in entries.withIndex()) {
            if (i > 0) writer.write(",")
            writer.write(JSONObject.quote(key))
            writer.write(":")
            emitValue(writer, key)
        }
        writer.write("}")
    }

    /**
     * Streams an array of pre-built JSONObjects without ever concatenating
     * them into one string. Each element is `element.toString()` written
     * straight to the writer — a per-message temporary String of a few KB,
     * not a 65M-char one.
     */
    fun writeJsonArray(
        writer: Writer,
        elements: List<JSONObject>,
    ) {
        writer.write("[")
        for ((i, el) in elements.withIndex()) {
            if (i > 0) writer.write(",")
            writer.write(el.toString())
        }
        writer.write("]")
    }

    /**
     * Streams an array whose elements are produced lazily. Used for the
     * chatMessages array so the message JSONObjects can be built, written,
     * and dropped one at a time when the caller streams directly from the
     * DAO cursor. Budget packing already materialized them as its decision
     * output, so this overload is for callers that have the list.
     */
    fun writeJsonArrayLazy(
        writer: Writer,
        count: Int,
        emitElement: (Writer, Int) -> Unit,
    ) {
        writer.write("[")
        for (i in 0 until count) {
            if (i > 0) writer.write(",")
            emitElement(writer, i)
        }
        writer.write("]")
    }
}
