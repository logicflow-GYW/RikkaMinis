package com.rikkaminis.app.offload

import com.rikkaminis.app.sandbox.offload.PayloadSizePolicy

/**
 * [FULLSCAN-1119 F-22] Byte-level policy for `minis://` HTML previews.
 *
 * Lives here, next to [BoundedFileRead], for one reason: the browser's
 * `minis://` interceptor is an Android class and cannot be unit-tested, so any
 * decision that lives inside it is only ever pinned by reading the code. These
 * two decisions are pure bytes-in/boolean-out and belong somewhere a JVM test
 * can drive them.
 */
internal object MinisPreviewPolicy {

    /**
     * Does this HTML already declare a viewport?
     *
     * ASCII-only, byte-level and case-insensitive. A previewed page can be
     * megabytes of UTF-8, and decoding it to a String just to search for
     * `name="viewport"` was one of the full-file copies the preview path is
     * being fixed for. Non-ASCII bytes never match the pattern, so skipping
     * them cannot hide a match.
     */
    fun declaresViewport(html: ByteArray): Boolean {
        val nameKey = "name=".toByteArray(Charsets.US_ASCII)
        val value = "viewport".toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i + nameKey.size + value.size + 2 <= html.size) {
            if (matchesAsciiIgnoreCase(html, i, nameKey)) {
                // Accept both quote styles: name="viewport" / name='viewport'
                val quote = html[i + nameKey.size].toInt().toChar()
                if (quote == '"' || quote == '\'') {
                    val valueStart = i + nameKey.size + 1
                    if (matchesAsciiIgnoreCase(html, valueStart, value) &&
                        html[valueStart + value.size].toInt().toChar() == quote
                    ) {
                        return true
                    }
                }
            }
            i++
        }
        return false
    }

    /**
     * Can a file of [fileBytes] be previewed under [capBytes]?
     *
     * The cap is the protocol's single-file ceiling
     * ([PayloadSizePolicy.MAX_FILE_BYTES]); this function exists so the
     * comparison is written once instead of at each call site.
     */
    fun fitsPreview(fileBytes: Long, capBytes: Long = PayloadSizePolicy.MAX_FILE_BYTES): Boolean =
        fileBytes in 0..capBytes

    private fun matchesAsciiIgnoreCase(hay: ByteArray, at: Int, needle: ByteArray): Boolean {
        if (at + needle.size > hay.size) return false
        for (k in needle.indices) {
            val a = hay[at + k].toInt() and 0xFF
            val b = needle[k].toInt() and 0xFF
            val la = if (a in 'A'.code..'Z'.code) a + 32 else a
            val lb = if (b in 'A'.code..'Z'.code) b + 32 else b
            if (la != lb) return false
        }
        return true
    }
}
