package com.rikkaminis.app.offload

import com.rikkaminis.app.sandbox.offload.PayloadSizePolicy
import java.io.File
import java.io.InputStream

/**
 * [FULLSCAN-1119 F-15 / F-22] Bounded "read this host file into memory" helper.
 *
 * Three call sites used `File.readBytes()` / `readText()` with no ceiling at all
 * — `minis-model-use` image inputs, and the browser's `minis://` HTML preview —
 * so pointing any of them at a large file (a multi-hundred-MB video, a log
 * dump, an accidental `/var/minis/shared` blob) allocated the whole thing into
 * the UI process and could get the app killed.
 *
 * Contract:
 *  - **Bound**: at most [PayloadSizePolicy.MAX_FILE_BYTES] — the protocol's own
 *    "absolute cap for any single file it may touch; above this fail closed
 *    rather than read it into memory". No second magic number is introduced.
 *  - **Observable**: the caller gets [Outcome.TooLarge] carrying the actual size
 *    and the cap, so it can answer with a real error instead of an empty page
 *    or a half image.
 *  - **Normal path unchanged**: under the cap the bytes are identical to
 *    `File.readBytes()`.
 *
 * The size check happens twice on purpose: once on [File.length] (cheap, and
 * the answer for a file that is already oversized), and once while reading —
 * a file that grows between the two must not be able to walk past the ceiling.
 */
internal object BoundedFileRead {

    sealed class Outcome {
        data class Ok(val bytes: ByteArray) : Outcome()
        data class TooLarge(val actualBytes: Long, val capBytes: Long) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    fun readBytes(
        file: File,
        capBytes: Long = PayloadSizePolicy.MAX_FILE_BYTES,
    ): Outcome {
        val declared = file.length()
        if (declared > capBytes) return Outcome.TooLarge(declared, capBytes)
        return try {
            file.inputStream().use { readStream(it, capBytes) }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Stream variant for callers that already hold an [InputStream]. */
    fun readStream(stream: InputStream, capBytes: Long = PayloadSizePolicy.MAX_FILE_BYTES): Outcome {
        val out = java.io.ByteArrayOutputStream(minOf(capBytes, 64 * 1024L).toInt())
        val buf = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buf, 0, buf.size)
            if (n <= 0) break
            total += n
            if (total > capBytes) return Outcome.TooLarge(total, capBytes)
            out.write(buf, 0, n)
        }
        return Outcome.Ok(out.toByteArray())
    }

    /**
     * The user/agent-facing sentence for [Outcome.TooLarge]. One wording, so a
     * user hitting this from `minis-model-use` and from a `minis://` preview
     * reads the same explanation.
     */
    fun tooLargeMessage(actualBytes: Long, capBytes: Long): String =
        "file is too large to read: ${PayloadSizePolicy.humanSize(actualBytes)} " +
            "exceeds the ${PayloadSizePolicy.humanSize(capBytes)} read ceiling"
}
