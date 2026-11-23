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
        "KEY", "TOKEN", "SECRET", "SECRETS", "PASSWORD", "PASSWD", "PWD",
        "CREDENTIAL", "CREDENTIALS", "AUTH", "AUTHORIZATION", "SIGNATURE", "COOKIE", "APIKEY",
    )

    /** Unsegmented names that still signal a credential (`clientSecret`, `dbPassword`). */
    private val SECRET_SUBSTRINGS = listOf("PASSWORD", "PASSWD", "SECRET", "CREDENTIAL", "APIKEY")

    /**
     * Explicit non-credential names. Kept small and literal: the proof is that a
     * redactor which fails here corrupts ordinary tool output (token metrics,
     * max_tokens, key codes), which is worse than a missed heuristic.
     */
    private val NON_SECRET_NAMES = setOf(
        "TOKENCOUNT", "TOKENS", "MAXTOKENS", "MINTOKENS", "TOKENIZER", "TOKENUSAGE",
        "TOKENLIMIT", "TOKENBUDGET", "TOKENESTIMATE", "INPUTTOKENS", "OUTPUTTOKENS",
        "TOTLTOKENS", "TOTALTOKENS", "LATESTCONTEXTTOKENS", "CONTEXTTOKENS",
        "KEYCODE", "KEYSTROKE", "KEYSTROKES", "KEYS", "KEYBOARD", "KEYMAP",
        "COOKIES", "SIGNATURES",
    )

    /** Well-known prefixes that carry a credential with no name beside them. */
    private val TOKEN_PREFIXES = listOf(
        "sk-", "sk_", "github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_",
        "xoxb-", "xoxp-", "xoxa-", "xoxr-", "xoxs-",
        "AKIA", "ASIA", "AIza", "hf_", "rnd_", "glpat-",
    )

    /** Scheme words after which the actual credential is the next token. */
    private val AUTH_SCHEME_WORDS = setOf("BEARER", "BASIC", "TOKEN", "SSWS", "DIGEST")

    // ── entry point ───────────────────────────────────────────────────────

    fun redact(input: String, knownValues: Collection<String>): SecretRedaction {
        if (input.isEmpty()) return SecretRedaction(input, 0)

        var current = input
        var hits = 0

        // 1) Known real values first: exact strings are the strongest evidence.
        //    Longest-first so a shorter value inside a longer one cannot pre-empt it.
        val values = knownValues.asSequence()
            .filter { it.length >= MIN_VALUE_LENGTH }
            .toSet()
            .sortedByDescending { it.length }
        for (value in values) {
            if (current.contains(value)) {
                current = current.replace(value, PLACEHOLDER)
                hits++
            }
        }

        // 2) Inline data URIs are exempt from *shape* inference (a base64 image
        //    can contain any prefix by chance); known values were already handled.
        //    Existing placeholders are protected too, so a second pass cannot
        //    re-mask the marker itself (textual idempotence).
        val protected = dataUriRanges(current) + placeholderRanges(current)

        // 3) Shape evidence: name-bound values, bare tokens, JWTs, PEM keys.
        val spans = ArrayList<IntRange>()
        spans.addAll(nameBoundValueSpans(current))
        spans.addAll(bareTokenSpans(current))
        spans.addAll(jwtSpans(current))
        spans.addAll(pemPrivateKeySpans(current))
        val usable = spans.filter { span ->
            span.first >= 0 && span.last < current.length &&
                protected.none { p -> p.first <= span.last && span.first <= p.last }
        }
        val (masked, shapeHits) = applySpans(current, usable)
        hits += shapeHits
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
            c == '"' || c == '\'' || c == '`' || c == '<' || c == '>'

    private fun looksLikeSecretName(name: String): Boolean {
        val upper = name.uppercase()
        if (upper.isEmpty()) return false
        if (upper in NON_SECRET_NAMES) return false
        if (SECRET_SUBSTRINGS.any { upper.contains(it) }) return true
        return nameSegments(name).any { it.uppercase() in SECRET_SEGMENTS }
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

    private fun nameBoundValueSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        for (i in text.indices) {
            val c = text[i]
            if (c != '=' && c != ':') continue
            var end = i
            while (end > 0 && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--
            if (end > 0 && (text[end - 1] == '"' || text[end - 1] == '\'' || text[end - 1] == '`')) end--
            var start = end
            while (start > 0 && isNameChar(text[start - 1])) start--
            val name = text.substring(start, end)
            if (name.isEmpty() || !looksLikeSecretName(name)) continue
            // `=` inside `==` / `=>` is not an assignment to a secret name.
            if (i + 1 < text.length && (text[i + 1] == '=' || text[i + 1] == '>')) continue
            valueSpan(text, i + 1)?.let { spans.add(it) }
        }
        return spans
    }

    private fun valueSpan(text: String, afterSep: Int): IntRange? {
        var i = afterSep
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length) return null
        val quote = text[i]
        if (quote == '"' || quote == '\'' || quote == '`') {
            val end = text.indexOf(quote, i + 1)
            // Unterminated quote: be conservative and hide the rest of the line.
            if (end < 0) {
                var j = i + 1
                while (j < text.length && text[j] != '\n' && text[j] != '\r') j++
                return if (j - (i + 1) >= MIN_VALUE_LENGTH) (i + 1) until j else null
            }
            val inner = text.substring(i + 1, end).trim()
            if (inner == PLACEHOLDER) return null
            return if (end - (i + 1) >= MIN_VALUE_LENGTH) (i + 1) until end else null
        }
        var j = i
        while (j < text.length && !isValueTerminator(text[j])) j++
        if (j - i < MIN_VALUE_LENGTH) return null
        val token = text.substring(i, j)
        if (token == PLACEHOLDER) return null
        // `Authorization: Bearer <credential>` — the credential is the SECOND
        // token, so stopping at the space would mask the scheme and leak the key.
        if (token.uppercase() in AUTH_SCHEME_WORDS) {
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

    private fun applySpans(text: String, spans: List<IntRange>): Pair<String, Int> {
        if (spans.isEmpty()) return text to 0
        val out = StringBuilder(text.length)
        var cursor = 0
        var hits = 0
        for (span in spans.sortedBy { it.first }) {
            if (span.first < cursor || span.last >= text.length) continue
            out.append(text, cursor, span.first)
            out.append(PLACEHOLDER)
            cursor = span.last + 1
            hits++
        }
        if (cursor == 0) return text to 0
        out.append(text, cursor, text.length)
        return out.toString() to hits
    }
}
