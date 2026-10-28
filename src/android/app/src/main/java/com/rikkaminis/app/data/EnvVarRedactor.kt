package com.rikkaminis.app.data

import com.rikkaminis.app.data.repository.EnvVarRepository

/**
 * Masks env-var values that leaked into shell tool output before they
 * reach the model.
 *
 * Mask rule: `len < 8` → all `*`; `len >= 8` → first 2 + (len-4) `*` +
 * last 2 (e.g. `sk-1********ajhks`).
 *
 * Matching is plain `String.replace` — no regex — to avoid ReDoS on
 * attacker-influenced output. Values with length `<= 4` are skipped
 * to avoid mangling unrelated text — short common strings (e.g.
 * `"true"`, `"data"`, `"http"`) frequently collide with normal output,
 * and replacing every occurrence with `*` would be more disruptive
 * than leaving them visible.
 *
 * Mirrors iOS `EnvVarRedactor`.
 */
object EnvVarRedactor {
    const val MIN_MATCH_LEN = 5

    /**
     * English-only system reminder appended to a tool result when at
     * least one redaction occurred. Single string so it survives
     * downstream trimming/normalization, with explicit guidance to the
     * model on how to operate without echoing secrets.
     */
    const val SYSTEM_REMINDER = "<system-reminder>Privacy mode is ON: one or more environment variable values were detected in this output and have been masked. Prefer running commands that consume secrets via environment variable references (e.g. `curl -H \"Authorization: Bearer \$API_KEY\" ...`) rather than echoing them. If the user needs to inspect raw values, ask them to disable Privacy Mode at Settings → Environment Variables.</system-reminder>"

    /**
     * Static handoff so non-DI call sites (e.g. ShellExecutor on a
     * background coroutine) can reach the repository without plumbing
     * it through every layer. Set once from [com.rikkaminis.app.MinisApp.onCreate].
     */
    @Volatile
    var envVarRepository: EnvVarRepository? = null

    fun mask(value: String): String {
        val n = value.length
        if (n < 8) return "*".repeat(n)
        return value.substring(0, 2) + "*".repeat(n - 4) + value.substring(n - 2, n)
    }

    /**
     * Redact [output] against the given env-var values. Returns the
     * rewritten string and the number of distinct values that produced
     * at least one replacement.
     *
     * Values are de-duplicated and processed longest-first so that
     * `BAR` appearing as a substring of a longer value `FOOBAR` does
     * not pre-empt the longer match.
     */
    fun redact(output: String, values: Collection<String>): Pair<String, Int> {
        val candidates = values
            .filter { it.length >= MIN_MATCH_LEN }
            .toSet()
            .sortedByDescending { it.length }
        if (candidates.isEmpty()) return output to 0

        var current = output
        var hits = 0
        for (v in candidates) {
            if (current.contains(v)) {
                current = current.replace(v, mask(v))
                hits++
            }
        }
        return current to hits
    }

    /**
     * Convenience wrapper — pulls values from the repository (if set)
     * and returns the possibly reminder-suffixed output plus the hit
     * count. No-op when Privacy Mode is disabled or the repository
     * isn't wired yet (e.g. very early in app boot).
     */
    fun redactIfEnabled(output: String): Pair<String, Int> {
        if (!EnvVarPrivacyStore.isEnabled) return output to 0
        val repo = envVarRepository ?: return output to 0
        // [T-envvar-index-integrity] Fail CLOSED when the index is unknown.
        // Before this branch, an unparseable `env-vars.json` produced an empty
        // entry list, so `allAsDict()` was empty, so Privacy Mode stayed "ON" in
        // Settings while every secret flowed into shell output in cleartext.
        // Not knowing which values are secret is a reason to mask by SHAPE, not
        // a reason to mask nothing.
        if (repo.metadataUnreadable) {
            // Whatever values we DO have still help (they are exact strings), so
            // the degraded path is value matching PLUS shape matching, never
            // shape alone.
            val values = repo.allAsDict().values.filter { it.isNotEmpty() }
            return redactDegradedWithReminder(output, values)
        }
        val values = repo.allAsDict().values.filter { it.isNotEmpty() }
        if (values.isEmpty()) return output to 0
        return redactWithReminder(output, values)
    }

    /**
     * [T-envvar-redactor-testable-tail] The repository-wired tail of
     * [redactIfEnabled] as a pure function: redact, then append the system
     * reminder exactly when at least one value produced a replacement.
     * Extracted so the "mask + reminder suffix" branch (previously only
     * reachable with an Android-backed repository, i.e. on-device only) is
     * pinned by JVM tests instead of being a silent coverage gap.
     */
    internal fun redactWithReminder(output: String, values: List<String>): Pair<String, Int> {
        val (masked, hits) = redact(output, values)
        if (hits == 0) return masked to 0
        return (masked + "\n\n" + SYSTEM_REMINDER) to hits
    }

    // ── [T-envvar-index-integrity] fail-closed fallback ───────────────────
    //
    // When `env-vars.json` cannot be decoded we do not know a single secret
    // VALUE, so value matching is impossible. The only remaining defence is
    // shape: mask what is *shaped* like a secret assignment or a well-known
    // token. Deliberately over-masks — a false positive costs the model a
    // masked string, a false negative costs the user a credential.
    //
    // No regex, same reason as [redact]'s KDoc (ReDoS on attacker-influenced
    // output): every pass below is a single linear scan over the string.

    /** Name segments that mark a value as secret (`API_KEY`, `db-password`, `apiKey`). */
    private val SECRET_NAME_PARTS = setOf(
        "KEY", "TOKEN", "SECRET", "SECRETS", "PASSWORD", "PASSWD", "PWD",
        "CREDENTIAL", "CREDENTIALS", "AUTH", "SIGNATURE", "COOKIE",
    )

    /** Words that mark a value as secret even without a segment boundary (`Authorization`). */
    private val SECRET_NAME_SUBSTRINGS = listOf(
        "PASSWORD", "PASSWD", "SECRET", "TOKEN", "CREDENTIAL", "AUTH", "APIKEY",
    )

    /** Well-known token prefixes that carry a credential with no name beside them. */    private val TOKEN_PREFIXES = listOf(
        "sk-", "sk_", "github_pat_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_",
        "xoxb-", "xoxp-", "xoxa-", "xoxr-", "xoxs-",
        "AKIA", "ASIA", "AIza", "hf_", "rnd_", "eyJ",
    )

    /** Scheme words after which the actual credential is the next token. */
    private val AUTH_SCHEME_WORDS = setOf("BEARER", "BASIC", "TOKEN", "SSWS", "DIGEST")

    /**
     * [T-envvar-index-integrity] Shape-based masking for when the env-var index
     * is unreadable. Returns the rewritten output and the number of spans
     * masked. Pure (no repository, no Android) so the rule is pinned by JVM
     * tests instead of only being exercised on a device that happens to have a
     * corrupt `env-vars.json`.
     */
    fun redactUnindexed(output: String): Pair<String, Int> {
        val (byName, nameHits) = applySpans(output, nameBoundValueSpans(output))
        // Second pass runs on the already-masked text: `*` cannot start a token
        // prefix, so a span can never be masked twice.
        val (byToken, tokenHits) = applySpans(byName, bareTokenSpans(byName))
        return byToken to (nameHits + tokenHits)
    }

    internal fun redactDegradedWithReminder(output: String, values: List<String>): Pair<String, Int> {
        // Value matching first (exact strings, strongest), then shapes on the
        // result. `mask` is length-preserving, so spans stay valid across passes
        // and `*` can never start a token prefix, so nothing is masked twice.
        val (byValue, valueHits) = redact(output, values)
        val (byShape, shapeHits) = redactUnindexed(byValue)
        val hits = valueHits + shapeHits
        if (hits == 0) return byShape to 0
        return (byShape + "\n\n" + UNINDEXED_SYSTEM_REMINDER) to hits
    }

    /**
     * English-only companion to [SYSTEM_REMINDER] for the degraded path. Says
     * what actually happened (best-effort shape matching, not a value list) so
     * the model does not read "masked" as "this output is now safe".
     */
    const val UNINDEXED_SYSTEM_REMINDER = "<system-reminder>Privacy mode is ON, but the stored environment-variable index could not be read this launch, so masking fell back to pattern matching: assignments whose NAME looks secret (KEY / TOKEN / SECRET / PASSWORD / AUTH / CREDENTIAL …) and well-known API token shapes were masked. This is best-effort — anything not matching those shapes is NOT masked. Treat this output as potentially containing credentials, and tell the user to check Settings → Environment Variables.</system-reminder>"

    private fun isNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '.'

    private fun isTokenChar(c: Char): Boolean = isNameChar(c)

    private fun isValueTerminator(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\r' || c == '\n' ||
            c == ',' || c == ';' || c == ')' || c == ']' || c == '}' ||
            c == '"' || c == '\'' || c == '`' || c == '<' || c == '>'

    /** `API_KEY`, `db-password`, `apiKey`, `X-Auth-Token` — not `monkey`, not `hockey`. */
    private fun looksLikeSecretName(name: String): Boolean {
        val upper = name.uppercase()
        if (SECRET_NAME_SUBSTRINGS.any { upper.contains(it) }) return true
        return nameSegments(name).any { it.uppercase() in SECRET_NAME_PARTS }
    }

    /**
     * Split a name on `_` / `-` / `.` and on camelCase boundaries, so `apiKey`
     * yields `KEY` while the all-caps `MONKEY` stays one segment and is not
     * mistaken for one.
     */
    private fun nameSegments(name: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (i in name.indices) {
            val c = name[i]
            if (c == '_' || c == '-' || c == '.') {
                if (cur.isNotEmpty()) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                continue
            }
            if (c.isUpperCase() && cur.isNotEmpty() && name[i - 1].isLowerCase()) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            cur.append(c)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Spans of values that follow a `name=value` / `name: value` separator. */
    private fun nameBoundValueSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        for (i in text.indices) {
            val c = text[i]
            if (c != '=' && c != ':') continue
            // `{"clientSecret": "…"}` puts a closing quote between the name and
            // the separator; `name : value` puts a space. Neither is part of the
            // name, and stopping on either would leave the name empty.
            var end = i
            while (end > 0 && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--
            if (end > 0 && (text[end - 1] == '"' || text[end - 1] == '\'' || text[end - 1] == '`')) end--
            var start = end
            while (start > 0 && isNameChar(text[start - 1])) start--
            val name = text.substring(start, end)
            if (name.isEmpty() || !looksLikeSecretName(name)) continue
            valueSpan(text, i + 1)?.let { spans.add(it) }
        }
        return spans
    }

    /** The value token after [afterSep], honouring one level of quotes. */
    private fun valueSpan(text: String, afterSep: Int): IntRange? {
        var i = afterSep
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        if (i >= text.length) return null
        val quote = text[i]
        if (quote == '"' || quote == '\'' || quote == '`') {
            val end = text.indexOf(quote, i + 1)
            if (end < 0) return null
            return if (end - (i + 1) >= MIN_MATCH_LEN) (i + 1) until end else null
        }
        var j = i
        while (j < text.length && !isValueTerminator(text[j])) j++
        if (j - i < MIN_MATCH_LEN) return null
        // `Authorization: Bearer <credential>` — the credential is the SECOND
        // token, so stopping at the space would mask the scheme and leak the key.
        if (text.substring(i, j).uppercase() in AUTH_SCHEME_WORDS) {
            var k = j
            while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
            var e = k
            while (e < text.length && !isValueTerminator(text[e])) e++
            if (e > k) return i until e
        }
        return i until j
    }

    /** Spans of bare `sk-…` / `ghp_…` / `AKIA…` tokens, at a word boundary. */
    private fun bareTokenSpans(text: String): List<IntRange> {
        val spans = ArrayList<IntRange>()
        for (prefix in TOKEN_PREFIXES) {
            var from = 0
            while (from < text.length) {
                val at = text.indexOf(prefix, from)
                if (at < 0) break
                from = at + 1
                // "task-1" contains "sk-" but is not a token.
                if (at > 0 && isNameChar(text[at - 1])) continue
                var end = at + prefix.length
                while (end < text.length && isTokenChar(text[end])) end++
                if (end - at >= MIN_MATCH_LEN) spans.add(at until end)
                from = end
            }
        }
        return spans
    }

    /**
     * Replace each span with [mask] of the same length (so later passes keep
     * valid indices). Overlapping spans are dropped rather than applied twice,
     * which would double-count a hit.
     */
    private fun applySpans(text: String, spans: List<IntRange>): Pair<String, Int> {
        if (spans.isEmpty()) return text to 0
        val out = StringBuilder(text.length)
        var cursor = 0
        var hits = 0
        for (span in spans.sortedBy { it.first }) {
            if (span.first < cursor || span.last >= text.length) continue
            out.append(text, cursor, span.first)
            out.append(mask(text.substring(span.first, span.last + 1)))
            cursor = span.last + 1
            hits++
        }
        if (cursor == 0) return text to 0
        out.append(text, cursor, text.length)
        return out.toString() to hits
    }
}
