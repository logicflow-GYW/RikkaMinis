package com.rikkaminis.app.provider.anthropic

import org.json.JSONObject

/**
 * [Rhub-1105 F-03] State of the Anthropic `tool_use` block being streamed.
 *
 * Two wire shapes reach the same place:
 *  • the native stream — arguments arrive as `input_json_delta` fragments in
 *    the delta buffer;
 *  • a relay that wraps a NON-streaming upstream tool_use into
 *    `content_block_start` with the whole argument object already in
 *    `content_block.input`, followed by `content_block_stop` and no delta at
 *    all. The old code read only id/name from the block announcement, so
 *    every argument-bearing tool arrived parameterless (`{}`) and was
 *    rejected downstream by the preflight validator.
 *
 * The delta buffer always wins; [initialInput] is only the fallback for a
 * block that streamed no delta. The two are never concatenated — a relay that
 * sends both would otherwise yield invalid JSON.
 *
 * An unparsable buffer still degrades to an empty object (the previous
 * behaviour) so a truncated argument stream keeps reaching the JSON-repair /
 * preflight path instead of vanishing.
 *
 * Kept in its own file, free of Android dependencies, so the decision table
 * and the block lifecycle can be exercised directly by JVM unit tests.
 */
internal class AnthropicToolBlock {

    /**
     * `optString` with the same null semantics as the provider's
     * `safeOptString`: a missing key or a JSON null yields the fallback.
     */
    private fun safeString(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.optString(key, "")
    }

    var toolId: String? = null
        private set
    var toolName: String? = null
        private set

    /** Accumulated `input_json_delta` fragments for the current block. */
    val inputBuffer = StringBuilder()

    /**
     * The block announcement's own argument object, kept aside as the
     * fallback for a block that streams no delta. Block-scoped: reset on
     * every `content_block_start`, consumed by [resolveArgs] / [clear].
     */
    var initialInput: JSONObject? = null
        private set

    /** True while a tool_use block is open. */
    val isOpen: Boolean get() = toolId != null && toolName != null

    /**
     * Handle a `content_block_start` announcement. Returns true when the
     * block is a `tool_use` block (i.e. the caller should announce it).
     *
     * Any initial input from a previous block is dropped here — two tool_use
     * blocks in a row must not share arguments.
     */
    fun onBlockStart(contentBlock: JSONObject?): Boolean {
        initialInput = null
        if (contentBlock == null) return false
        if (safeString(contentBlock, "type") != "tool_use") return false
        toolId = safeString(contentBlock, "id")
        toolName = safeString(contentBlock, "name")
        inputBuffer.clear()
        initialInput = contentBlock.optJSONObject("input")?.takeIf { it.length() > 0 }
        return true
    }

    /** Append one `input_json_delta` fragment. Returns the characters added. */
    fun appendDelta(partialJson: String): Int {
        if (partialJson.isEmpty() || toolId == null) return 0
        inputBuffer.append(partialJson)
        return partialJson.length
    }

    /**
     * The argument object of the current block: the delta buffer when it
     * carries anything, otherwise the announcement's initial input.
     */
    fun resolveArgs(): JSONObject = resolveAnthropicToolArgs(inputBuffer.toString(), initialInput)

    /** Close the block and drop every piece of its state. */
    fun clear() {
        toolId = null
        toolName = null
        inputBuffer.clear()
        initialInput = null
    }
}

/**
 * [Rhub-1105 F-03] Resolve the argument object of a streamed Anthropic
 * `tool_use` block.
 *
 * The delta buffer always wins; [initialInput] is only the fallback for a
 * block that streamed no delta — where "no delta" includes a delta of `{}`
 * (R8-B B-F07): an empty argument object carries nothing, so letting it win
 * over the announcement silently dropped every announced parameter. The two
 * are never concatenated — a relay that sends both would otherwise yield
 * invalid JSON.
 */
internal fun resolveAnthropicToolArgs(deltaBuffer: String, initialInput: JSONObject?): JSONObject {
    // [R8-B B-F07] `"{}"` is not "arguments arrived": a relay that announces the
    // block with the real `input` and then streams an empty delta object used to
    // take the delta branch, parse `{}` and DISCARD the announced parameters — the
    // tool then ran parameterless and was rejected by preflight.
    //
    // Narrow on purpose: only a delta that PARSES to an empty object (or is
    // blank) counts as "no arguments". An UNPARSABLE buffer keeps the documented
    // behaviour below (degrade to `{}` so the truncated stream still reaches the
    // JSON-repair / preflight path) — falling back to `initialInput` there would
    // hide a truncated stream behind stale arguments.
    val parsed = runCatching { JSONObject(deltaBuffer) }.getOrNull()
    // Real arguments arrived: they win.
    if (parsed != null && parsed.length() > 0) return parsed
    // Unparsable non-blank buffer: keep the documented degrade so a truncated
    // stream still reaches JSON repair / preflight instead of silently taking
    // the announced arguments.
    if (parsed == null && deltaBuffer.isNotBlank()) return JSONObject()
    // Blank buffer or `{}`: no arguments streamed — the announcement is all we have.
    if (initialInput == null) return JSONObject()
    return try {
        JSONObject(initialInput.toString())
    } catch (_: Exception) {
        JSONObject()
    }
}
