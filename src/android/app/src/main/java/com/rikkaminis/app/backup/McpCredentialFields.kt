package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * [FULLSCAN-1119 A / F-01 + F-02] The two halves of the MCP credential
 * invariant, both Android-free so they can be pinned on a plain JVM:
 *
 *  * **export** — [redactSecretArgs] / [redactUrlCredentials] blank the
 *    credential carriers of a credentials-free backup. They live here rather
 *    than inline in [ConfigBackup.stripMcpSecretValues] because the inline
 *    version judged one spelling (`--key=value`, full-match) and missed the
 *    space-separated twin (`--token abc`), and because it only looked at a URL
 *    when the URL contained `?` — so `https://user:pass@host/` kept its
 *    password in every unattended auto backup.
 *
 *  * **import** — [mergeRedactedMcpServerPayload] puts this device's live
 *    credential values back into a same-id server the backup carries with
 *    blanked/placeholder values. Without it a restore of any
 *    `includeSecrets=false` payload (i.e. every daily auto backup) replaced the
 *    live `Authorization` header / `env` token / `?key=` with the empty string
 *    and the server silently stopped authenticating.
 *
 * Deliberately pure: `org.json` + `List`/`String` only, no Android, no
 * repository — so the credential rules are testable without a device.
 */
internal object McpCredentialFields {

    /**
     * Credential-bearing CLI flags, normalized (lowercase, `-`/`_` removed) so
     * `--api-key` / `--api_key` / `--apikey` are one class.
     *
     * Normalizing is what keeps `--tokenize` / `--tokenizer` OUT: they
     * normalize to `tokenize` / `tokenizer`, which are not in this set. A
     * substring test (`arg.contains("token")`) would redact them and destroy a
     * perfectly good argument.
     */
    private val SECRET_FLAGS: Set<String> = setOf(
        "token", "apikey", "secret", "password", "accesstoken",
    )

    /** `[redacted]` (URL userinfo) / `[redacted:N]` (query value of length N). */
    private val REDACTED_MARKER = Regex("""^\[redacted(?::\d+)?]$""")

    /** True when [value] carries no usable credential: blank, or a marker this
     *  file's export half writes. Used by the import merge to decide which
     *  incoming values must fall back to the device's live ones. */
    internal fun isPlaceholder(value: String?): Boolean {
        if (value == null) return true
        val v = value.trim()
        return v.isEmpty() || REDACTED_MARKER.matches(v)
    }

    // ── export: args ──────────────────────────────────────────────────────

    /** `--token` → `token`; `--tokenize` → null (not a credential flag). */
    private fun flagName(arg: String): String? {
        if (!arg.startsWith("--") || arg.length <= 2) return null
        val body = arg.substring(2).substringBefore('=')
        if (body.isEmpty()) return null
        val normalized = body.lowercase().replace("-", "").replace("_", "")
        return if (normalized in SECRET_FLAGS) normalized else null
    }

    /**
     * Blank every credential value in [args], keeping the flag (and the list
     * arity) so the restored entry still shows WHICH credential to refill.
     *
     * Equivalence classes handled — the whole point of this function:
     *   * `--token=abc`            → `--token=`
     *   * `--token abc`            → `--token`, `""`
     *   * `--token abc def`        → `--token`, `""`, `""` (the value ran to
     *     the next `--flag`; blanking only the first token left `def` behind)
     *   * `--port 8080` / `--help` / `--tokenize x` → untouched
     */
    internal fun redactSecretArgs(args: List<String>): List<String> {
        val out = args.toMutableList()
        var i = 0
        while (i < out.size) {
            val arg = out[i]
            val flag = flagName(arg)
            if (flag == null) {
                i++
                continue
            }
            if (arg.contains('=')) {
                out[i] = arg.substringBefore('=') + "="
                i++
                continue
            }
            // Space-separated form: the value is everything up to the next
            // `--`-prefixed token. Blanking the whole span is what makes
            // `--token abc def` not leak `def`.
            var j = i + 1
            while (j < out.size && !out[j].startsWith("--")) {
                out[j] = ""
                j++
            }
            i = j
        }
        return out
    }

    /** flag → first non-placeholder value, in list order (the import merge's
     *  source of truth for `args`). */
    private fun credentialValues(args: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val flag = flagName(arg)
            if (flag == null) {
                i++
                continue
            }
            if (arg.contains('=')) {
                val v = arg.substringAfter('=')
                if (!isPlaceholder(v) && flag !in out) out[flag] = v
                i++
                continue
            }
            var j = i + 1
            val span = mutableListOf<String>()
            while (j < args.size && !args[j].startsWith("--")) {
                span.add(args[j])
                j++
            }
            val joined = span.joinToString(" ")
            if (!isPlaceholder(joined) && flag !in out) out[flag] = joined
            i = j
        }
        return out
    }

    /**
     * Put this device's live `args` credential values back where the backup
     * carries the redacted placeholder. Non-credential args (command line,
     * `--port`, positional paths) keep the BACKUP's values — a restore is still
     * a restore, only the credential slots fall back.
     */
    internal fun restoreSecretArgs(incoming: List<String>, local: List<String>): List<String> {
        val localValues = credentialValues(local)
        if (localValues.isEmpty()) return incoming
        val out = mutableListOf<String>()
        var i = 0
        while (i < incoming.size) {
            val arg = incoming[i]
            val flag = flagName(arg)
            if (flag == null) {
                out.add(arg)
                i++
                continue
            }
            if (arg.contains('=')) {
                val v = arg.substringAfter('=')
                out.add(
                    if (isPlaceholder(v)) {
                        localValues[flag]?.let { arg.substringBefore('=') + "=" + it } ?: arg
                    } else {
                        arg
                    }
                )
                i++
                continue
            }
            var j = i + 1
            val span = mutableListOf<Int>()
            while (j < incoming.size && !incoming[j].startsWith("--")) {
                span.add(j)
                j++
            }
            val joined = span.joinToString(" ") { incoming[it] }
            if (isPlaceholder(joined) && localValues[flag] != null) {
                // One value slot carrying the live value; the extra blank slots
                // the redactor left behind are dropped rather than kept as `""`
                // arguments (a blank argv entry is not "the same command").
                out.add(arg)
                out.add(localValues[flag]!!)
            } else {
                out.add(arg)
                for (k in span) out.add(incoming[k])
            }
            i = j
        }
        return out
    }

    // ── export: url ───────────────────────────────────────────────────────

    /** Sensitive URL query keys — same list as LLMRequestLog.SENSITIVE_QUERY_KEYS. */
    private val SENSITIVE_QUERY_KEYS = setOf(
        "key", "api_key", "apikey", "api-key", "token", "access_token",
        "secret", "password", "signature", "sig", "auth", "authorization",
    )

    /**
     * Redact a server URL's credentials while keeping it connectable:
     * the host and the non-sensitive query parameters survive.
     *
     * Covers BOTH carriers — the old inline version only ran when the URL
     * contained `?`, so `https://user:pass@host/` shipped the password in the
     * clear:
     *   * userinfo → `https://[redacted]@host/path`
     *   * sensitive query values → `?key=[redacted:11]`
     */
    internal fun redactUrlCredentials(url: String): String {
        val withoutUserInfo = redactUserInfo(url)
        return if (withoutUserInfo.contains("?")) redactUrlQuery(withoutUserInfo) else withoutUserInfo
    }

    private fun redactUserInfo(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authStart = schemeEnd + 3
        var authEnd = url.length
        for (i in authStart until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                authEnd = i
                break
            }
        }
        val authority = url.substring(authStart, authEnd)
        val at = authority.lastIndexOf('@')
        if (at < 0) return url
        val userInfo = authority.substring(0, at)
        if (userInfo.isEmpty() || userInfo == "[redacted]") return url
        return url.substring(0, authStart) + "[redacted]" + url.substring(authStart + at)
    }

    private fun redactUrlQuery(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val query = url.substring(qIdx + 1)
        val fragIdx = query.indexOf('#')
        val (qBody, frag) =
            if (fragIdx >= 0) query.substring(0, fragIdx) to query.substring(fragIdx) else query to ""
        val redacted = qBody.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = pair.substring(0, eq)
                val v = pair.substring(eq + 1)
                if (k.lowercase() in SENSITIVE_QUERY_KEYS) "$k=[redacted:${v.length}]" else pair
            } else {
                pair
            }
        }
        return url.substring(0, qIdx + 1) + redacted + frag
    }

    // ── import: merge a redacted payload onto the live server ─────────────

    /** header/env map: blank or marker values fall back to [local]; keys the
     *  backup dropped stay dropped (the exporting device's set is authoritative
     *  for WHICH credentials exist, only the values are protected). */
    private fun mergeMap(incoming: Map<String, String>, local: Map<String, String>): Map<String, String> {
        if (local.isEmpty()) return incoming
        val out = LinkedHashMap<String, String>()
        for ((k, v) in incoming) {
            out[k] = if (isPlaceholder(v)) local[k] ?: v else v
        }
        return out
    }

    private fun readStringMap(obj: JSONObject?, key: String): Map<String, String> {
        val section = obj?.optJSONObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in section.keys().asSequence()) out[k] = section.optString(k, "")
        return out
    }

    private fun writeStringMap(target: JSONObject, key: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        val obj = JSONObject()
        for ((k, v) in values) obj.put(k, v)
        target.put(key, obj)
    }

    private fun readStringList(obj: JSONObject, key: String): List<String> {
        val arr: JSONArray = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }
    }

    /**
     * Restore the live credential values into ONE server entry, in place.
     *
     * Only the credential carriers are touched: `headers` / `env` values,
     * `args` credential slots, `url` (whole value — a redacted URL's userinfo or
     * query cannot be rebuilt piecewise). Everything else in [incoming]
     * (`command`, non-secret args, `note`, `enabled`, `oauth`, timeouts) keeps
     * the BACKUP's value, so a restore still restores.
     */
    internal fun mergeEntryCredentials(incoming: JSONObject, local: JSONObject) {
        for (key in listOf("headers", "env")) {
            val inMap = readStringMap(incoming, key)
            if (inMap.isEmpty()) continue
            writeStringMap(incoming, key, mergeMap(inMap, readStringMap(local, key)))
        }
        val inArgs = readStringList(incoming, "args")
        if (inArgs.isNotEmpty()) {
            val merged = restoreSecretArgs(inArgs, readStringList(local, "args"))
            if (merged != inArgs) {
                val arr = JSONArray()
                for (a in merged) arr.put(a)
                incoming.put("args", arr)
            }
        }
        val inUrl = incoming.optString("url", "")
        val loUrl = local.optString("url", "")
        if (inUrl.isNotEmpty() && loUrl.isNotEmpty() && isRedactedUrl(inUrl) && !isRedactedUrl(loUrl)) {
            incoming.put("url", loUrl)
        }
    }

    private fun isRedactedUrl(url: String): Boolean {
        if (url.contains("[redacted]")) return true
        return Regex("""\[redacted:\d+]""").containsMatchIn(url)
    }

    /** Credential identity includes scheme/port/path, username and
     * non-secret query parameters. Passwords/secret values are not retained;
     * this transient comparison key must never be logged. */
    internal fun endpointIdentity(raw: String): String? {
        val masked = raw.trim().replace(Regex("""\[redacted(?::\d+)?]"""), "credential")
        val url = masked.toHttpUrlOrNull() ?: return null
        if (url.fragment != null) return null
        val query = url.queryParameterNames.sorted().flatMap { key ->
            url.queryParameterValues(key).map { value ->
                key to if (key.lowercase() in SENSITIVE_QUERY_KEYS) "credential" else value
            }
        }
        return listOf(url.scheme, url.host, url.port, url.encodedPath.trimEnd('/'), url.username, query).toString()
    }

    internal fun sameEndpoint(incoming: String, local: String): Boolean {
        val identity = endpointIdentity(incoming) ?: return false
        return identity == endpointIdentity(local)
    }

    private fun sameEntryDestination(incoming: JSONObject, local: JSONObject): Boolean {
        val inUrl = incoming.optString("url", "").trim()
        val loUrl = local.optString("url", "").trim()
        if (inUrl.isNotEmpty() || loUrl.isNotEmpty()) {
            // Redacted userinfo hides the subject, so identity is unknown:
            // retain the complete old connection rather than guess a match.
            return inUrl.isNotEmpty() && loUrl.isNotEmpty() && sameEndpoint(inUrl, loUrl)
        }
        val command = incoming.optString("command", "")
        if (command.isBlank() || command != local.optString("command", "")) return false
        // Compare the whole non-secret argv, including endpoint/tenant flags.
        // Only the established benign local listen-port setting is ignored.
        fun subject(entry: JSONObject): List<String> {
            val args = redactSecretArgs(readStringList(entry, "args"))
            val out = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (arg == "--port" || arg.startsWith("--port=")) {
                    out.add("--port")
                    i += if (arg == "--port" && i + 1 < args.size) 2 else 1
                } else {
                    if (arg.isNotEmpty()) out.add(arg)
                    i++
                }
            }
            return out
        }
        return subject(incoming) == subject(local)
    }

    /** False means a same-id endpoint/transport/subject changed. Preserve the
     * complete old entry instead of pairing live credentials with new routing. */
    internal fun mergeRedactedMcpServerPayload(incoming: JSONObject, local: JSONObject): Boolean {
        val inInner = incoming.optJSONObject("mcpServers") ?: return false
        val loInner = local.optJSONObject("mcpServers") ?: return false
        val id = inInner.keys().asSequence().firstOrNull() ?: return false
        val inEntry = inInner.optJSONObject(id) ?: return false
        val loEntry = loInner.optJSONObject(id) ?: return false
        val needsLocal = listOf("headers", "env").any { key ->
            val values = readStringMap(loEntry, key)
            readStringMap(inEntry, key).any { (k, v) -> isPlaceholder(v) && !isPlaceholder(values[k]) }
        } || restoreSecretArgs(readStringList(inEntry, "args"), readStringList(loEntry, "args")) !=
            readStringList(inEntry, "args") ||
            (isRedactedUrl(inEntry.optString("url", "")) && loEntry.optString("url", "").isNotEmpty())
        if (needsLocal && !sameEntryDestination(inEntry, loEntry)) {
            inInner.put(id, JSONObject(loEntry.toString()))
            return false
        }
        mergeEntryCredentials(inEntry, loEntry)
        return true
    }
}
