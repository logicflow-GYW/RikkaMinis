package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON repair for malformed / incomplete tool calls (T-tool-json-repair b2c4f8a6).
 *
 * Mirrors the iOS implementation in AIChatViewModel.swift (repairToolArgs / preflight
 * pre-pass). Operates on the already-parsed [JSONObject] that the streaming provider
 * surfaced, optionally consulting a raw stream "tail" snapshot from the tool-input
 * chunk ring when the dict is empty (truncation case).
 *
 * Three strategies, applied in order, each gated on actually being needed:
 *
 * 1. Truncation repair — if [args] is empty but [rawTail] looks like a JSON object
 *    that just got cut, retry parsing with a small set of closure suffixes appended.
 * 2. Type coercion — for each required field present but not a String, coerce via
 *    `toString()` so the downstream blank-string preflight check has something usable.
 * 3. Fuzzy field-name match — for each missing required field, look for a sibling key
 *    whose Levenshtein distance is exactly 1 and rename it. Catches one-off typos
 *    like `comand` → `command`.
 *
 * The repaired [JSONObject] shadows the original at the preflight call site; the
 * caller logs the [Outcome.repairs] tags at WARNING level when non-empty.
 */
object ToolJsonRepair {

    /**
     * Mutates [args] in-place and returns the list of repair strategy tags
     * that fired (empty when nothing changed). Caller is responsible for
     * logging the tags at WARNING level when non-empty.
     */
    fun repair(
        toolName: String,
        args: JSONObject,
        rawTail: String?,
        tools: List<AgentToolDefinition>,
    ): List<String> {
        val toolDef = tools.firstOrNull { it.name == toolName }
            ?: return emptyList()

        val repairs = mutableListOf<String>()

        // Strategy 1: truncation repair. Only fires when the dict is empty
        // (or otherwise unusable) but the raw stream tail looks like a JSON
        // object that just got cut. Try appending closure suffixes; the
        // first one that parses wins, and we copy fields back into [args].
        if (args.length() == 0 && !rawTail.isNullOrBlank()) {
            val tail = rawTail.trim()
            val suffixes = listOf("", "\"", "\"}", "\"]}", "}", "}}", "]}", "]}}", "]", "]]")
            for (suffix in suffixes) {
                val candidate = tail + suffix
                val parsed = tryParseObject(candidate) ?: continue
                val keys = parsed.keys().asSequence().toList()
                for (k in keys) args.put(k, parsed.opt(k))
                repairs.add("truncation+" + if (suffix.isEmpty()) "noop" else suffix)
                break
            }
        }

        // Strategy 2: type coercion on required fields — SCALARS ONLY.
        //
        // Number/boolean-for-string is a common, genuinely repairable deviation
        // (`{"timeout": 30}`). An OBJECT or ARRAY handed to a scalar field is
        // not: `toString()` turns it into its own JSON text, so
        // `{"path": {"a": 1}}` becomes the string `{"a": 1}` and travels on as
        // a path-shaped value that only fails much later, far from the cause.
        // Leave those untouched so preflight can refuse them outright — see the
        // structural check in ChatViewModel.preflightValidateToolCallImpl.
        // [T-preflight-enum-and-type]
        for (field in toolDef.required) {
            if (!args.has(field)) continue
            val raw = args.opt(field) ?: continue
            if (raw is String) continue
            if (raw === JSONObject.NULL) continue
            if (raw is JSONObject || raw is JSONArray) continue
            val coerced = raw.toString()
            if (coerced.trim().isNotEmpty()) {
                args.put(field, coerced)
                repairs.add("type-coerce:$field")
            }
        }

        // Strategy 3: fuzzy field-name match for missing required fields. Skip
        // sibling keys that are themselves a recognized schema field — don't
        // steal a sibling that the tool helper would have read directly.
        val schemaFields = toolDef.parameters.keys
        for (field in toolDef.required) {
            if (args.has(field)) continue
            val keys = args.keys().asSequence().toList()
            val candidate = keys.firstOrNull { key ->
                key !in schemaFields && levenshteinAtMostOne(key, field)
            } ?: continue
            val moved = args.opt(candidate)
            // [fix/audit0917-b8] Strategy 2 has already run, so a scalar moved
            // here used to arrive UNCOERCED — `{"tmeout": 30}` produced a
            // Number in a string field, while `{"timeout": 30}` produced "30"
            // via type-coerce. Apply the same rule, and the same
            // object/array carve-out (leave it for preflight to refuse).
            // `JSONObject.NULL` (or a missing key) moves verbatim — never as the
            // literal string "null".
            if (moved is JSONObject || moved is JSONArray) continue
            val value = if (moved == null || moved === JSONObject.NULL || moved is String) moved else moved.toString()
            args.put(field, value)
            args.remove(candidate)
            repairs.add("fuzzy:$candidate->$field")
        }

        return repairs
    }

    private fun tryParseObject(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (_: Throwable) {
        null
    }

    /**
     * `true` iff Levenshtein edit distance between [a] and [b] is exactly 1
     * (case-insensitive). Tight short-circuit — we don't care about distance > 1.
     */
    private fun levenshteinAtMostOne(a: String, b: String): Boolean {
        val al = a.lowercase()
        val bl = b.lowercase()
        if (al == bl) return false // distance 0 = same key, not a repair candidate
        val diff = al.length - bl.length
        if (diff > 1 || diff < -1) return false
        if (al.length == bl.length) {
            var mismatches = 0
            for (i in al.indices) {
                if (al[i] != bl[i]) {
                    mismatches += 1
                    if (mismatches > 1) return false
                }
            }
            return mismatches == 1
        }
        val longer = if (al.length > bl.length) al else bl
        val shorter = if (al.length > bl.length) bl else al
        var i = 0
        var j = 0
        var skipped = false
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) {
                i += 1; j += 1
            } else if (!skipped) {
                i += 1; skipped = true
            } else {
                return false
            }
        }
        return true
    }
}

