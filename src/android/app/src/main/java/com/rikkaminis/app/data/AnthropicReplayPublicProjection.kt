package com.rikkaminis.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray

/** Public/portable parts view. Private replay is removed as a whole part. */
object AnthropicReplayPublicProjection {
    const val PART_TYPE = "anthropicReplayV1"

    // This bounds eager parsing here, NOT historical readers' existing limits.
    // The preflight is one linear scan, with a stack of at most MAX_DEPTH frames.
    private const val MAX_INPUT_CHARS = 1_000_000
    private const val MAX_DEPTH = 64

    /** Both faces share the same decision; callers must not reparse [partsJson]. */
    class Projection internal constructor(val partsJson: String, val parts: JSONArray?)

    fun partsJson(raw: String): String = projectImpl(raw, parseOrdinaryParts = false).partsJson

    fun project(raw: String): Projection = projectImpl(raw, parseOrdinaryParts = true)

    private fun projectImpl(raw: String, parseOrdinaryParts: Boolean): Projection {
        val inspection = Inspection(raw)
        val verdict = inspection.scan()
        if (verdict == Verdict.UNSAFE) return empty()
        if (!inspection.isArray) {
            return if (verdict == Verdict.PRIVATE) empty() else Projection(raw, null)
        }
        if (verdict == Verdict.PRIVATE) return removePrivate(raw)
        // String-only export/fork faces retain the previous oversized-row
        // contract: scan once, but do not add another full JSON parse.
        if (!parseOrdinaryParts && raw.length > MAX_INPUT_CHARS) return Projection(raw, null)

        // Same first-array grammar as Android JSONArray(String): no stricter
        // completeness test here that a later lenient reader would undo.
        val parts = readArray(raw)
        if (parts != null && hasPrivatePart(parts)) return removePrivate(raw, parts)
        return Projection(raw, parts)
    }

    private fun readArray(raw: String): JSONArray? = try { JSONArray(raw) } catch (_: Exception) { null }
    private fun empty() = Projection("[]", JSONArray())

    private fun hasPrivatePart(parts: JSONArray): Boolean =
        (0 until parts.length()).any { parts.optJSONObject(it)?.opt("type") == PART_TYPE }

    private fun removePrivate(raw: String, lenient: JSONArray? = null): Projection {
        if (raw.length > MAX_INPUT_CHARS) return empty()
        // Legal JSON keeps numeric tokens exact (Android org.json parses every
        // decimal as Double); prefer the existing kotlinx tree over rewriting.
        val strict = try { Json.parseToJsonElement(raw) as? JsonArray } catch (_: Exception) { null }
        if (strict != null) {
            val kept = strict.filterNot { part ->
                val type = (part as? JsonObject)?.get("type") as? JsonPrimitive
                type?.isString == true && type.content == PART_TYPE
            }
            // A candidate the preflight saw but the tree cannot account for
            // (trailing garbage, duplicate type keys) must not be certified.
            if (kept.size == strict.size) return empty()
            val publicJson = JsonArray(kept).toString()
            return Projection(publicJson, readArray(publicJson))
        }
        // Legacy/lenient spellings cannot go through an exact tree. The array
        // the reader actually parsed is still a safe source, and it never
        // regains the raw spelling (no raw fallback on this path).
        val parsed = lenient ?: readArray(raw) ?: return empty()
        val kept = JSONArray()
        var removed = false
        for (index in 0 until parsed.length()) {
            val part = parsed.opt(index)
            if (part is JSONObject && part.opt("type") == PART_TYPE) removed = true else kept.put(part)
        }
        return if (removed) Projection(kept.toString(), kept) else empty()
    }

    private enum class Verdict { PUBLIC, PRIVATE, UNSAFE }
    private class Frame(val opener: Char, val directPart: Boolean) {
        var typeKey = false
        var typeValue = false
    }

    /**
     * A restricted structural preflight, not a general JSON parser. Only an
     * object directly in the root parts array can declare a private type.
     * Nested tool inputs, strings and non-array legacy prose are not metadata.
     * A trailing root object is an invalid part-like candidate, never a raw
     * fallback escape hatch. The actual array parser owns all ordinary syntax.
     */
    private class Inspection(private val raw: String) {
        var isArray = false
            private set
        private var i = if (raw.startsWith('\ufeff')) 1 else 0
        private val stack = ArrayList<Frame>()
        private var privateType = false
        private var nulAfterPrivateType = false

        fun scan(): Verdict {
            skipTrivia()
            if (i >= raw.length) return Verdict.PUBLIC
            if (raw[i] == '[') {
                isArray = true
                i++
                stack.add(Frame('[', directPart = false))
            } else if (raw[i] == '{') {
                // A non-array object is not a parts list, but a direct private
                // type is still an untrusted candidate, not legacy prose.
                i++
                stack.add(Frame('{', directPart = true))
            } else {
                return Verdict.PUBLIC
            }
            while (i < raw.length) {
                skipTrivia()
                if (i >= raw.length) break
                val c = raw[i]
                val frame = stack.lastOrNull()
                when (c) {
                    '[', '{' -> {
                        // A container value cannot itself be the type string.
                        frame?.typeValue = false
                        frame?.typeKey = false
                        val direct = c == '{' && (stack.isEmpty() ||
                            (stack.size == 1 && stack[0].opener == '['))
                        if (stack.size >= MAX_DEPTH) return Verdict.UNSAFE
                        stack.add(Frame(c, direct))
                        i++
                    }
                    ']', '}' -> {
                        if (frame?.typeValue == true) return Verdict.UNSAFE
                        if (frame == null || frame.opener != if (c == ']') '[' else '{') return Verdict.UNSAFE
                        stack.removeAt(stack.lastIndex)
                        i++
                    }
                    ',', ';' -> {
                        if (frame?.typeValue == true) return Verdict.UNSAFE
                        frame?.typeKey = false
                        i++
                    }
                    ':', '=' -> {
                        frame?.typeValue = frame?.directPart == true && frame.typeKey
                        frame?.typeKey = false
                        i++
                        // AOSP accepts both => and :>, including without spaces.
                        if (i < raw.length && raw[i] == '>') i++
                    }
                    else -> {
                        if (c == '\u0000' && stack.any { it.typeValue }) return Verdict.UNSAFE
                        val token = token()
                        if (frame?.directPart == true) {
                            if (frame.typeValue) {
                                if (token == PART_TYPE) privateType = true
                                // AOSP's lenient readLiteral and Maven's literal
                                // parser disagree when bare type tokens touch
                                // NUL or non-JSON separators. Once the private
                                // discriminator is the token prefix, do not let
                                // either parser's boundary guess authorize echo.
                                if (token?.startsWith(PART_TYPE) == true && token != PART_TYPE) {
                                    return Verdict.UNSAFE
                                }
                            }
                            frame.typeValue = false
                            frame.typeKey = token == "type"
                        }
                    }
                }
            }
            if (stack.any { it.typeValue }) return Verdict.UNSAFE
            return if (privateType) Verdict.PRIVATE else Verdict.PUBLIC
        }

        private fun skipTrivia() {
            while (i < raw.length) {
                when {
                    raw[i] <= ' ' -> i++
                    raw.startsWith("/*", i) -> {
                        val end = raw.indexOf("*/", i + 2)
                        i = if (end < 0) raw.length else end + 2
                    }
                    raw[i] == '#' || raw.startsWith("//", i) -> {
                        while (i < raw.length && raw[i] != '\n' && raw[i] != '\r') i++
                    }
                    else -> return
                }
            }
        }

        // Keep only short decoded tokens needed for the type decision. Bare
        // delimiters follow AOSP readLiteral, including /, # and backslash;
        // controls additionally stop Maven literals (notably its NUL/EOF).
        private fun token(): String? {
            val quote = raw[i].takeIf { it == '"' || it == '\'' }
            if (quote != null) i++
            val text = StringBuilder()
            var short = true
            while (i < raw.length) {
                var ch = raw[i]
                if (quote != null && ch == quote) { i++; return if (short) text.toString() else null }
                if (quote == null && (ch == '\r' || ch == '\n' || ch == '\t' || ch == ' ' || ch == '\u000c' || ch in "{}[]/\\:,=;#")) break
                i++
                if (quote != null && ch == '\\') {
                    if (i >= raw.length) break
                    ch = raw[i++]
                    if (ch == 'u') {
                        if (i + 4 > raw.length) break
                        val decoded = raw.substring(i, i + 4).toIntOrNull(16) ?: break
                        ch = decoded.toChar(); i += 4
                    } else {
                        ch = when (ch) {
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'
                            else -> ch
                        }
                    }
                }
                if (text.length < PART_TYPE.length) text.append(ch) else short = false
            }
            // A bare unsupported delimiter must still make progress. Keeping
            // an unfinished short token lets the type-value arm fail closed.
            if (quote == null && text.isEmpty() && i < raw.length) i++
            return text.toString()
        }
    }
}
