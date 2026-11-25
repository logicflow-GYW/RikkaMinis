package com.rikkaminis.app.data

/**
 * Pure, Android-free rules for redacting credentials out of tool output before
 * it reaches the model, persisted diagnostics, the summarizer or a replayed
 * history.
 *
 * Contract (see shared/work/vcp-research/PRIVACY-PLAN.md):
 *  - Deterministic evidence only: known credential VALUES, NAME-bound secret
 *    fields, and high-confidence credential FORMATS. A merely long or random
 *    string is never redacted — git SHAs, hashes, UUIDs, account ids, model and
 *    resource ids must survive verbatim.
 *  - No backtracking regex on attacker-influenced output: every pass is a
 *    single linear scan. `String.replace` is literal, not regex.
 *  - Idempotent textually: a second pass returns the same string (a placeholder
 *    value is never re-redacted into something different).
 *  - Failure shape belongs to the caller. This layer has no I/O, no Android
 *    dependency, and no "return the original on error" fallback.
 *  - Replacement is [PLACEHOLDER], deliberately distinct from the shell layer's
 *    `sk-1*****` mask so a redacted copy cannot be mistaken for real content.
 */
internal data class SecretRedaction(val text: String, val hits: Int)

internal object SecretRedactionRules {
    /** Marker written into the safe copy. Never contains a token prefix. */
    const val PLACEHOLDER = "[MINIS_REDACTED]"

    /** Known values shorter than this are too collision-prone to replace globally. */
    const val MIN_VALUE_LENGTH = 5

    /** A bare token must be at least this long to count as a credential shape. */
    private const val MIN_TOKEN_LENGTH = 12

    /** Names whose value is a credential when bound as `NAME=value` / `NAME: value`. */
    private val SECRET_SEGMENTS = setOf(
        "TOKEN", "SECRET", "PASSWORD", "PASSWD", "PWD", "CREDENTIAL", "CREDENTIALS",
        "AUTH", "AUTHORIZATION", "COOKIE", "APIKEY",
    )

    private val SECRET_NAMES = setOf(
        "APIKEY", "XAPIKEY", "ACCESSKEY", "ACCESSKEYID", "SECRETACCESSKEY", "PRIVATEKEY",
        "SIGNINGKEY", "ENCRYPTIONKEY", "CLIENTSECRET", "CLIENTTOKEN", "ACCESSTOKEN",
        "REFRESHTOKEN", "IDTOKEN", "SESSIONTOKEN", "BEARERTOKEN", "AUTHTOKEN",
        "XAUTHTOKEN", "AUTHORIZATION", "PROXYAUTHORIZATION", "SETCOOKIE", "COOKIES",
    )

    /**
     * Explicit non-credential names. Kept small and literal: the proof is that a
     * redactor which fails here corrupts ordinary tool output (token metrics,
     * max_tokens, key codes), which is worse than a missed heuristic.
     */
    private val NON_SECRET_NAMES = setOf(
        "TOKENCOUNT", "TOKENS", "MAXTOKENS", "MINTOKENS", "TOKENIZER", "TOKENUSAGE",
        "TOKENLIMIT", "TOKENBUDGET", "TOKENESTIMATE", "INPUTTOKENS", "OUTPUTTOKENS",
        "TOTLTOKENS", "TOTALTOKENS", "LATESTCONTEXTTOKENS", "CONTEXTTOKENS",
        "KEYCODE", "KEYSTROKE", "KEYSTROKES", "KEYS", "KEYBOARD", "KEYMAP", "SIGNATURES",
    )

    /**
     * Well-known prefixes that carry a credential with no name beside them.
     *
     * [fix/redactor-token-prefixes-1125] This is the ONE list: [EnvVarRedactor]
     * derives its degraded-path copy from here. The two used to be separate
     * literals and they drifted — Groq (`gsk_`) and Cloudflare (`cfat_`) keys
     * were known only to the diagnostics recorder's regex, so the model-facing
     * rules handed a real key through untouched even with Privacy Mode ON.
     * A key whose prefix is not here has no other defence when it appears bare.
     */
    internal val TOKEN_PREFIXES = listOf(
        "sk-", "sk_", "gsk_", "cfat_", "github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_",
        "xoxb-", "xoxp-", "xoxa-", "xoxr-", "xoxs-",
        "AKIA", "ASIA", "AIza", "hf_", "rnd_", "glpat-",
    )

    /** Scheme words after which the actual credential is the next token. */
    private val AUTH_SCHEME_WORDS = setOf("BEARER", "BASIC", "TOKEN", "SSWS", "DIGEST")

    // ── entry point ───────────────────────────────────────────────────────

    fun redact(input: String, knownValues: Collection<String>, onRedacted: ((String) -> Unit)? = null): SecretRedaction {
        if (input.isEmpty()) return SecretRedaction(input, 0)
        val values = knownValues.asSequence().filter {
            it.length >= MIN_VALUE_LENGTH && it.any { c -> !c.isDigit() }
        }.toSet().sortedByDescending { it.length }.toList()
        // Match the ORIGINAL bytes once. Repeated replace() could mask a value
        // occurring inside a placeholder emitted by an earlier replacement.
        val protected = placeholderRanges(input)
        val spans = ArrayList<IntRange>()
        for (value in values) {
            var from = 0
            while (from < input.length) {
                val at = input.indexOf(value, from)
                if (at < 0) break
                spans.add(at until at + value.length)
                from = at + value.length
            }
        }
        spans.addAll(nameBoundValueSpans(input))
        spans.addAll(quotedValueSpans(input, values))
        val data = dataUriRanges(input)
        spans.addAll((bareTokenSpans(input) + jwtSpans(input)).filter { span ->
            data.none { p -> p.first <= span.first && span.last <= p.last }
        })
        spans.addAll(pemPrivateKeySpans(input))
        val usable = spans.filter { span ->
            span.first >= 0 && span.last < input.length &&
                protected.none { p -> p.first <= span.first && span.last <= p.last }
        }
        val (masked, hits) = applySpans(input, usable, onRedacted)
        return SecretRedaction(masked, hits)
    }

    /** True when [text] is exactly the placeholder written by this layer. */
    fun isPlaceholder(text: String): Boolean = text.trim() == PLACEHOLDER

    // ── name-bound values ─────────────────────────────────────────────────

    private fun isNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    private fun isValueTerminator(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c == '\n' ||
            c == ',' || c == ';' || c == ')' || c == ']' || c == '}' ||
            c == '"' || c == '\'' || c == '`' || c == '<' || c == '>' ||
            c == '&' || c == '#'

    internal fun isSecretName(name: String): Boolean = looksLikeSecretName(name)

    private fun looksLikeSecretName(name: String): Boolean {
        val upper = name.filter { it.isLetterOrDigit() }.uppercase(java.util.Locale.ROOT)
        if (upper.isEmpty() || upper in NON_SECRET_NAMES) return false
        if (upper in SECRET_NAMES) return true
        return nameSegments(name).any { it.uppercase(java.util.Locale.ROOT) in SECRET_SEGMENTS }
    }

    /** Split on `_` / `-` / `.` and camelCase boundaries; `MONKEY` stays one segment. */
    private fun nameSegments(name: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (i in name.indices) {
            val c = name[i]
            if (c == '_' || c == '-' || c == '.') {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                continue
            }
            if (c.isUpperCase() && cur.isNotEmpty() && name[i - 1].isLowerCase()) {
                out.add(cur.toString()); cur.setLength(0)
            }
            cur.append(c)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private data class Quoted(val from: Int, val end: Int, val closed: Boolean, val value: String)

    /** Decode only a string token; do not round/reorder the surrounding JSON. */
    private fun quoted(text: String, from: Int): Quoted {
        val quote = text[from]
        val out = StringBuilder()
        var i = from + 1
        while (i < text.length && text[i] != '\n' && text[i] != '\r') {
            val c = text[i]
            if (c == quote) return Quoted(from, i, true, out.toString())
            if (c == '\\' && i + 1 < text.length) {
                val escaped = text[i + 1]
                if (escaped == 'u' && i + 5 < text.length) {
                    val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) { out.append(code.toChar()); i += 6; continue }
                }
                out.append(when (escaped) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'
                    else -> escaped
                })
                i += 2
            } else { out.append(c); i++ }
        }
        return Quoted(from, i, false, out.toString())
    }

    private fun quotedValueSpans(text: String, values: List<String>): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '"' && text[i] != '\'' && text[i] != '`') { i++; continue }
            val token = quoted(text, i)
            var after = if (token.closed) token.end + 1 else token.end
            while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
            val isKey = token.closed && after < text.length && (text[after] == ':' || text[after] == '=')
            if (isKey && looksLikeSecretName(token.value)) {
                valueSpan(text, after + 1, token.value, text[after] == ':')?.let { spans.add(it) }
            } else if (!isKey && token.value != PLACEHOLDER &&
                (values.any { token.value.contains(it) } ||
                    bareTokenSpans(token.value).isNotEmpty() || jwtSpans(token.value).isNotEmpty() ||
                    nameBoundValueSpans(token.value, skipQuoted = false).isNotEmpty() || pemPrivateKeySpans(token.value).isNotEmpty()) &&
                !token.value.startsWith("data:")
            ) {
                if (token.end > i + 1) spans.add((i + 1) until token.end)
            } else if (!isKey && values.any { token.value.contains(it) } && token.value != PLACEHOLDER) {
                if (token.end > i + 1) spans.add((i + 1) until token.end)
            }
            i = maxOf(i + 1, if (token.closed) token.end + 1 else token.end)
        }
        return spans
    }

    private fun nameBoundValueSpans(text: String, skipQuoted: Boolean = true): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (skipQuoted && (c == '"' || c == '\'' || c == '`')) {
                val token = quoted(text, i)
                i = maxOf(i + 1, if (token.closed) token.end + 1 else token.end)
                continue
            }
            if (c != '=' && c != ':') { i++; continue }
            var end = i
            while (end > 0 && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--
            var start = end
            while (start > 0 && isNameChar(text[start - 1])) start--
            val name = text.substring(start, end)
            if (looksLikeSecretName(name) && !(i + 1 < text.length &&
                    (text[i + 1] == '=' || text[i + 1] == '>')) &&
                !(i > 0 && text[i - 1] == '=')
            ) valueSpan(text, i + 1, name, c == ':' && start > 0 && text[start - 1] == '{')
                ?.let { spans.add(it) }
            i++
        }
        return spans
    }

    private fun valueSpan(text: String, afterSep: Int, name: String, json: Boolean): IntRange? {
        var i = afterSep
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length || text.startsWith(PLACEHOLDER, i)) return null
        val q = text[i]
        if (q == '"' || q == '\'' || q == '`') {
            val token = quoted(text, i)
            return if (token.value.isNotEmpty() && token.value != PLACEHOLDER) (i + 1) until token.end else null
        }
        if (text[i] == '{' || text[i] == '[') return null
        val normalized = name.filter { it.isLetterOrDigit() }.uppercase(java.util.Locale.ROOT)
        // Cookie/Digest headers are compound credentials; hiding only their
        // first token leaks session cookies or nonce material later in the line.
        if (!json && normalized in setOf("COOKIE", "COOKIES", "SETCOOKIE", "AUTHORIZATION", "PROXYAUTHORIZATION")) {
            var end = i
            while (end < text.length && text[end] != '\n' && text[end] != '\r' && text[end] != '}') end++
            return if (end > i) i until end else null
        }
        var j = i
        while (j < text.length && !isValueTerminator(text[j])) j++
        if (j == i) return null
        val token = text.substring(i, j)
        if (json && token in setOf("null", "true", "false")) return null
        // A name-bound numeric password must become a STRING placeholder,
        // otherwise the replacement makes a valid JSON object invalid.
        if (json) return i until j
        if (token.uppercase(java.util.Locale.ROOT) in AUTH_SCHEME_WORDS) {
            var k = j
            while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
            var e = k
            while (e < text.length && !isValueTerminator(text[e])) e++
            if (e > k) return i until e
        }
        return i until j
    }

    // ── bare token shapes ─────────────────────────────────────────────────

    private fun isTokenChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    private fun bareTokenSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        for (prefix in TOKEN_PREFIXES) {
            var from = 0
            while (from < text.length) {
                val at = text.indexOf(prefix, from)
                if (at < 0) break
                from = at + 1
                if (at > 0 && isNameChar(text[at - 1])) continue
                var end = at + prefix.length
                while (end < text.length && isTokenChar(text[end])) end++
                if (end - at >= MIN_TOKEN_LENGTH) spans.add(at until end)
                from = end
            }
        }
        return spans
    }

    // ── JWT ───────────────────────────────────────────────────────────────

    /**
     * A JWT is only redacted when its header segment actually decodes to a JSON
     * object carrying `alg`. A bare `eyJ...` string is not evidence.
     */
    private fun jwtSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            if (!isJwtChar(text[i])) { i++; continue }
            var j = i
            while (j < text.length && isJwtChar(text[j])) j++
            val run = text.substring(i, j)
            val parts = run.split('.')
            if (parts.size == 3) {
                val candidate = parts
                if (candidate.all { it.length >= 8 } && looksLikeJwtHeader(candidate[0])) {
                    spans.add(i until j)
                }
            }
            i = j
        }
        return spans
    }

    private fun isJwtChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    private fun looksLikeJwtHeader(segment: String): Boolean = try {
        val padded = segment + "=".repeat((4 - segment.length % 4) % 4)
        val decoded = String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        val trimmed = decoded.trim()
        trimmed.startsWith("{") && trimmed.contains("\"alg\"")
    } catch (_: Exception) {
        false
    }

    // ── PEM private keys ──────────────────────────────────────────────────

    private fun pemPrivateKeySpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val begin = text.indexOf("-----BEGIN ", from)
            if (begin < 0) break
            val labelEnd = text.indexOf("-----", begin + 11)
            if (labelEnd < 0) break
            val label = text.substring(begin + 11, labelEnd)
            if (!label.contains("PRIVATE KEY")) { from = labelEnd + 5; continue }
            val endMarker = "-----END $label-----"
            val end = text.indexOf(endMarker, labelEnd + 5)
            if (end < 0) {
                // Truncated key block: hide to the end of the text, not past it.
                spans.add(begin until text.length)
                break
            }
            spans.add(begin until end + endMarker.length)
            from = end + endMarker.length
        }
        return spans
    }

    // ── data URIs ─────────────────────────────────────────────────────────

    /** Ranges of `data:...;base64,...` tokens, exempt from shape inference. */
    private fun dataUriRanges(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf("data:", from)
            if (at < 0) break
            var j = at + 5
            var seenBase64 = false
            while (j < text.length && !isUriTerminator(text[j])) {
                if (looksLikeBase64Marker(text, j)) seenBase64 = true
                j++
            }
            if (seenBase64) ranges.add(at until j)
            from = if (j > at) j else at + 5
        }
        return ranges
    }

    private fun looksLikeBase64Marker(text: String, at: Int): Boolean {
        val marker = ";base64,"
        if (at + marker.length > text.length) return false
        return text.regionMatches(at, marker, 0, marker.length)
    }

    private fun isUriTerminator(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c == '\n' ||
            c == '"' || c == '\'' || c == '`' || c == '<' || c == '>' ||
            c == ')' || c == ']' || c == '}'

    /** Ranges occupied by an existing placeholder (idempotence guard). */
    private fun placeholderRanges(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(PLACEHOLDER, from)
            if (at < 0) break
            ranges.add(at until at + PLACEHOLDER.length - 1)
            from = at + PLACEHOLDER.length
        }
        return ranges
    }

    // ── span replacement ──────────────────────────────────────────────────

    private fun applySpans(text: String, spans: List<IntRange>, onRedacted: ((String) -> Unit)?): Pair<String, Int> {
        if (spans.isEmpty()) return text to 0
        val merged = ArrayList<IntRange>()
        for (span in spans.sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })) {
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, span.last)
            } else merged.add(span)
        }
        val out = StringBuilder(text.length)
        var cursor = 0
        for (span in merged) {
            onRedacted?.invoke(text.substring(span.first, span.last + 1))
            out.append(text, cursor, span.first)
            val numericJson = span.first > 0 && span.last + 1 < text.length &&
                text[span.first].let { it.isDigit() || it == '-' } &&
                run { var p = span.first - 1; while (p >= 0 && text[p].isWhitespace()) p--; p >= 0 && text[p] == ':' } &&
                run { var p = span.last + 1; while (p < text.length && text[p].isWhitespace()) p++; p < text.length && (text[p] == ',' || text[p] == '}') }
            if (numericJson) out.append('"')
            out.append(PLACEHOLDER)
            if (numericJson) out.append('"')
            cursor = span.last + 1
        }
        out.append(text, cursor, text.length)
        return out.toString() to merged.size
    }
}
