package com.rikkaminis.app.offload

import com.rikkaminis.app.sandbox.offload.PayloadSizePolicy

/**
 * [FULLSCAN-1119 F-09] What to do when a streaming JSONL line exceeds the
 * protocol's own frame ceiling.
 *
 * The reader must never carry such a line in memory, so the drop itself is not
 * negotiable — what used to be wrong is that it happened **silently**: the
 * reader's `Lines.oversizedLines` list was ignored by the caller and the
 * single-line branch only logged at debug level, so a dropped
 * `{"t":"media","b64":…}` chunk looked exactly like a model that simply
 * produced no picture, and a dropped `tu_done` looked like a tool call that
 * never finished.
 *
 * The ceiling is now the protocol's own poll window
 * ([PayloadSizePolicy.MAX_POLL_READ_BYTES]) instead of a hardcoded 256 KiB, so a
 * *legal* frame (the 512 KiB inline-binary budget this protocol declares,
 * Base64-inflated) is no longer oversized by construction. This policy covers
 * the residual: a frame the protocol itself says must not exist.
 *
 * Two cases, because they carry different risk:
 *  - **nothing delivered yet** → fail the stream. A retry costs nothing and the
 *    caller can fall back; silently continuing would hand the user an answer
 *    built on a lost frame.
 *  - **content already delivered** → keep the answer and shout. Failing here
 *    would throw away text the user has already seen (and re-sending would
 *    duplicate it — the `hadChunks` contract in
 *    [ModelExecutionStreamException]); the drop is recorded loudly instead.
 *
 * Pure JVM: the decision is pinned by a unit test rather than by reading the
 * call site.
 */
internal object StreamOversizePolicy {

    enum class Action {
        /** Throw a typed stream error — safe, because no chunk was emitted. */
        FAIL_BEFORE_OUTPUT,

        /** Log loudly and keep streaming — the answer outranks the lost frame. */
        LOG_AND_CONTINUE,
    }

    fun decide(hadChunks: Boolean): Action =
        if (hadChunks) Action.LOG_AND_CONTINUE else Action.FAIL_BEFORE_OUTPUT

    /**
     * The human/agent-facing message for a dropped frame. Names the byte count,
     * the ceiling it crossed, and what was probably lost — enough for a reader
     * of the log (or the error banner) to tell a protocol bug from a provider
     * quirk.
     */
    fun message(lineBytes: Long, ceilingBytes: Int, atOffset: Long): String =
        "stream frame dropped: ${lineBytes}B at offset $atOffset exceeds the " +
            "${ceilingBytes}B per-line ceiling; the chunk it carried " +
            "(model text, an image attachment, or a tool-call frame) was NOT delivered"

    /**
     * Short content fingerprint for the log. Reads at most [headBytes] from the
     * front — the point is to recognise WHICH frame was lost (a `media` line, a
     * `tu_done`, …) without materialising the whole line.
     */
    fun summary(head: ByteArray, headBytes: Int = 48): String {
        val n = minOf(headBytes, head.size)
        if (n <= 0) return "(empty)"
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xFF
            if (b in 0x20..0x7E) sb.append(b.toChar()) else sb.append('.')
        }
        return sb.toString()
    }
}
