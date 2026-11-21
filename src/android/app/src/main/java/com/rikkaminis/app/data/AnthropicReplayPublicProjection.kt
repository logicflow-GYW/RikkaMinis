package com.rikkaminis.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Public/portable view of a stored parts_json array. Replay belongs only to the
 * private live/persisted lane: remove its whole part, never just its signature.
 * No carrier DTO is needed here, and ordinary text/tool JSON is not inspected.
 */
object AnthropicReplayPublicProjection {
    const val PART_TYPE = "anthropicReplayV1"

    // Twice the current 500,000-byte row ceiling, in UTF-16 chars. Historical
    // oversized/deep rows must not trigger unbounded JSON parsing or raw echoes.
    private const val MAX_INPUT_CHARS = 1_000_000
    private const val MAX_DEPTH = 64

    /**
     * Returns the original spelling when nothing is removed. Malformed legacy
     * text stays readable unless a structural private type is confirmed. Inputs
     * outside the bounded parser budget have only an empty, safe projection.
     * Call BEFORE wrapping this string in export.content or RPC.parts.
     */
    fun partsJson(raw: String): String {
        if (raw.length > MAX_INPUT_CHARS) {
            // Oversized rows are never parsed (that is the unbounded path). One
            // bounded structural scan decides: a confirmed private type or an
            // exhausted scan budget ⇒ unusable projection; otherwise the legacy
            // readable fallback stays.
            return if (inspectStructure(raw) == 0) raw else "[]"
        }
        val inspection = inspectStructure(raw)
        if (inspection == 2) return "[]"
        val parts = try {
            val parser = JSONTokener(raw)
            val array = parser.nextValue() as? JSONArray
            if (array == null || parser.nextClean() != '\u0000') {
                return if (inspection == 1) "[]" else raw
            }
            array
        } catch (_: Exception) {
            return if (inspection == 1) "[]" else raw
        }
        val kept = JSONArray()
        var removed = false
        for (i in 0 until parts.length()) {
            val part = parts.opt(i)
            if (part is JSONObject && part.opt("type") == PART_TYPE) {
                removed = true
            } else {
                kept.put(part)
            }
        }
        return if (removed) kept.toString() else raw
    }

    /**
     * Bounded lexical preflight, not a secret-name regex. Strings are consumed
     * as a unit (so a text/tool value quoting a sample marker is NOT metadata).
     * Recognises escaped keys/values and org.json's legacy single-quoted or
     * unquoted tokens, even when the remainder of the array is truncated.
     */
    // 0 = no private type seen; 1 = confirmed type; 2 = parser budget exceeded.
    private fun inspectStructure(raw: String): Int {
        var i = 0
        var depth = 0
        var privateType = false
        var previous = '\u0000'
        var typeKey = false
        var typeValue = false
        while (i < raw.length) {
            val c = raw[i]
            if (c.isWhitespace()) { i++; continue }
            // Android JSONTokener also accepts comments between key/colon/value.
            // Ignore their contents, but retain the surrounding structural state.
            if (raw.startsWith("/*", i)) {
                val end = raw.indexOf("*/", i + 2)
                i = if (end < 0) raw.length else end + 2
                continue
            }
            if (raw.startsWith("//", i) || c == '#') {
                while (i < raw.length && raw[i] != '\n' && raw[i] != '\r') i++
                continue
            }
            when (c) {
                '{', '[' -> {
                    if (++depth > MAX_DEPTH) return 2
                    previous = c; typeKey = false; typeValue = false; i++
                }
                '}', ']' -> {
                    depth--
                    previous = c; typeKey = false; typeValue = false; i++
                }
                ',', ';' -> {
                    previous = ','; typeKey = false; typeValue = false; i++
                }
                ':', '=' -> {
                    typeValue = typeKey
                    typeKey = false; previous = ':'; i++
                    if (c == '=' && i < raw.length && raw[i] == '>') i++
                }
                else -> {
                    val token = StringBuilder()
                    var short = true
                    val quote = if (c == '"' || c == '\'') c else null
                    if (quote != null) i++
                    var closed = quote == null
                    while (i < raw.length) {
                        var ch = raw[i]
                        if (quote != null && ch == quote) { i++; closed = true; break }
                        if (quote == null && (ch.isWhitespace() || ch in "{}[],:;=\"'")) break
                        i++
                        if (quote != null && ch == '\\') {
                            if (i >= raw.length) break
                            ch = raw[i++]
                            if (ch == 'u') {
                                if (i + 4 > raw.length) break
                                val code = raw.substring(i, i + 4).toIntOrNull(16) ?: break
                                ch = code.toChar(); i += 4
                            } else {
                                ch = when (ch) {
                                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                                    'b' -> '\b'; 'f' -> '\u000c'
                                    else -> ch
                                }
                            }
                        }
                        if (token.length < PART_TYPE.length) token.append(ch) else short = false
                    }
                    if (closed && short && typeValue && token.toString() == PART_TYPE) privateType = true
                    typeKey = closed && short && previous in "{," && token.toString() == "type"
                    typeValue = false
                    previous = 'v'
                    // A delimiter not handled above (e.g. a stray quote) must
                    // still make progress on malformed input.
                    if (quote == null && token.isEmpty()) i++
                }
            }
        }
        return if (privateType) 1 else 0
    }
}
