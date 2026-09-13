package com.rikkaminis.app.util

/**
 * [backlog #1 / fix/utf16-lone-surrogate] Lone UTF-16 surrogate sanitizer.
 *
 * A Kotlin `String` is a UTF-16 sequence and does NOT enforce surrogate
 * pairing, so a lone high surrogate (U+D800..U+DBFF) or lone low surrogate
 * (U+DC00..U+DFFF) can travel through the app untouched — from a relay that
 * mangles its upstream bytes, from a clipboard paste, or from a provider that
 * cut a response mid-code-point. Most consumers tolerate it, but the ones that
 * do not (strict `CharsetEncoder`s, JNI bridges, some text-shaping paths) fail
 * hard and far from the source, which makes them expensive to diagnose.
 *
 * Two entry points, on purpose:
 *  - [sanitize] — for COMPLETE text (user input, a finished turn). Replaces
 *    every lone surrogate with U+FFFD.
 *  - [hasLoneSurrogate] — the O(n) no-alloc probe backing the fast path; also
 *    exposed so callers can cheaply skip work on the common clean case.
 *
 * Why REPLACE (U+FFFD) instead of OmniBot's `AgentTextSanitizer` DELETE: the
 * reference implementation drops the offending code units, which silently
 * shortens the text. U+FFFD is what every other decoding layer in this stack
 * (and `String.getBytes(UTF_8)`) already produces, so a lone surrogate shows
 * up as *something* in the transcript instead of vanishing — a debugging
 * difference that matters when the source is a broken relay.
 *
 * NOT for per-chunk streaming use: replacing a trailing high surrogate that is
 * only *temporarily* alone (the next SSE delta carries its low half) would
 * corrupt a legitimate character. Sanitize the accumulated text instead. Pairs
 * that straddle a flush boundary survive because the accumulator itself is
 * never mutated — only the published copy is.
 */
internal object Utf16Sanitizer {

    /** U+FFFD REPLACEMENT CHARACTER. */
    private const val REPLACEMENT: Char = '\uFFFD'

    /** True when [text] contains at least one unpaired surrogate code unit. */
    fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                        i += 2
                    } else {
                        return true
                    }
                }

                Character.isLowSurrogate(c) -> return true
                else -> i += 1
            }
        }
        return false
    }

    /**
     * Replace every lone surrogate in [text] with U+FFFD. Returns the SAME
     * instance when nothing is wrong (no allocation, no copy) — safe to call
     * on every publish of a potentially large accumulation.
     */
    fun sanitize(text: String): String {
        if (text.isEmpty() || !hasLoneSurrogate(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                        out.append(c).append(text[i + 1])
                        i += 2
                    } else {
                        out.append(REPLACEMENT)
                        i += 1
                    }
                }

                Character.isLowSurrogate(c) -> {
                    out.append(REPLACEMENT)
                    i += 1
                }

                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }
}
