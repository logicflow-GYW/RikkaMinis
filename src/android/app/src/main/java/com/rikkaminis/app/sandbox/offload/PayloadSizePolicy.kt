package com.rikkaminis.app.sandbox.offload

/**
 * Pure-function payload size policy for the model-exec file protocol.
 *
 * Decides when an in-memory media blob / big text / ToolResult should be
 * spilled to a file reference (BytesFileRef + path on the run's disk dir)
 * instead of inlined as Base64 into the JSON request or result.
 *
 * Design intent: no Android dependencies (JVM-testable), single source of
 * truth for every threshold the Dispatcher / Service / handlers consult.
 * Keeping these as constants + pure functions means TF-D wiring only has to
 * call one function — `decide` / `overflow` — and never hardcode another
 * magic byte count.
 *
 * All units are BYTES unless the function name says otherwise.
 */
object PayloadSizePolicy {

    // ── Thresholds (bytes) ──

    /** Maximum size of a single in-memory binary blob (image/audio) that may
     *  be inlined as Base64 into the JSON body. Above this → file reference. */
    const val MAX_INLINE_BINARY_BYTES: Long = 512 * 1024L          // 512 KiB

    /** Maximum size of a single inlined ToolResult content string. */
    const val MAX_INLINE_TOOL_RESULT_BYTES: Long = 256 * 1024L     // 256 KiB

    /** Maximum size of a single inlined text field (message content, response text). */
    const val MAX_INLINE_TEXT_BYTES: Long = 128 * 1024L            // 128 KiB

    /** Absolute cap for any single file the protocol may touch. Above this
     *  the caller should fail closed rather than read it into memory. */
    const val MAX_FILE_BYTES: Long = 64 * 1024 * 1024L             // 64 MiB

    /** Safety cap for a single JSON request/result file (serialized, on disk). */
    const val MAX_SERIALIZED_JSON_BYTES: Long = 16 * 1024 * 1024L  // 16 MiB

    /** Streaming chunk JSONL: max bytes allowed for a single poll read window.
     *  readAppendedChunks should never pull more than this in one go. */
    const val MAX_POLL_READ_BYTES: Int = 1 * 1024 * 1024           // 1 MiB

    /**
     * `encodeToString` inflates a binary blob by ~4/3 for the Base64 alphabet
     * plus padding. Use this when predicting the JSON-serialized size of an
     * inlined binary without actually allocating the string.
     */
    fun base64Size(byteSize: Long): Long =
        if (byteSize <= 0) 0L else ((byteSize + 2) / 3) * 4L

    // ── Classification ──

    sealed class Kind {
        object Binary : Kind()     // image / audio bytes
        object ToolResult : Kind() // tool result content string
        object Text : Kind()       // message content / response text
    }

    /** The byte size that counts against the inline budget for [kind]. */
    fun inlineBudget(kind: Kind): Long = when (kind) {
        Kind.Binary -> MAX_INLINE_BINARY_BYTES
        Kind.ToolResult -> MAX_INLINE_TOOL_RESULT_BYTES
        Kind.Text -> MAX_INLINE_TEXT_BYTES
    }

    /**
     * Decide whether a payload of [byteSize] may be inlined for [kind].
     * Binary sizes are compared AFTER Base64 inflation (the real serialized
     * cost); text/toolresult are plain UTF-8 byte length.
     */
    fun canInline(kind: Kind, byteSize: Long): Boolean {
        val cost = when (kind) {
            Kind.Binary -> base64Size(byteSize)
            Kind.ToolResult, Kind.Text -> byteSize.coerceAtLeast(0L)
        }
        return cost <= inlineBudget(kind)
    }

    /** The serialized size budget available for a payload of [kind] and [byteSize]. */
    fun overflowBytes(kind: Kind, byteSize: Long): Long {
        val cost = when (kind) {
            Kind.Binary -> base64Size(byteSize)
            Kind.ToolResult, Kind.Text -> byteSize.coerceAtLeast(0L)
        }
        return (cost - inlineBudget(kind)).coerceAtLeast(0L)
    }

    /** True when [byteSize] must be spilled (Base64 + JSON would exceed the budget). */
    fun mustSpill(kind: Kind, byteSize: Long): Boolean = !canInline(kind, byteSize)

    /** Absolute read/write guard: any single file larger than [MAX_FILE_BYTES]
     *  is out of protocol scope — callers should not stream it into memory. */
    fun isFileTooLarge(fileBytes: Long): Boolean = fileBytes > MAX_FILE_BYTES

    /** True when a serialized JSON body would exceed [MAX_SERIALIZED_JSON_BYTES]. */
    fun isSerializedJsonTooLarge(jsonBytes: Long): Boolean = jsonBytes > MAX_SERIALIZED_JSON_BYTES

    /** Human-friendly rendering for logs, e.g. `512.0 KiB`. */
    fun humanSize(bytes: Long): String {
        if (bytes < 1024L) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format("%.1f KiB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format("%.1f MiB", mb)
        return String.format("%.1f GiB", mb / 1024.0)
    }
}