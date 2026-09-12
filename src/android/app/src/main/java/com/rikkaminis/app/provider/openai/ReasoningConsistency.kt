package com.rikkaminis.app.provider.openai

import org.json.JSONObject

/**
 * Consistency check between what the upstream BILLED for reasoning and what
 * the stream actually carried.
 *
 * [T321-reasoning-consistency] Real-world case (2026-09-12): a relay billed
 * `completion_tokens_details.reasoning_tokens = 2599` while every SSE delta
 * carried `reasoning_content`/`reasoning` empty AND the reasoning was pasted
 * straight into `content` — 6 tag formats and 4 field names all matched
 * nothing. Before this check the only way to tell "upstream didn't separate
 * thinking" from "our parser missed it" was to grep logcat by hand and compare
 * two numbers across two log lines. This turns that comparison into ONE
 * explicit diagnostic line, so a user (or an agent) can classify the failure
 * without touching a shell.
 *
 * Deliberately NOT a fix: we do not try to strip thinking out of `content`
 * (heuristics would eat real prose). This only makes the inconsistency
 * observable.
 */
object ReasoningConsistency {

    /**
     * Reasoning tokens the upstream billed for this stream: prefers the OpenAI
     * nested `completion_tokens_details.reasoning_tokens`, falls back to the
     * top-level `reasoning_tokens` / `reasoningTokens` (Anthropic-style
     * relays), and takes whichever is larger — some relays populate one but
     * not the other.
     */
    fun billedReasoningTokens(usage: JSONObject?): Long {
        if (usage == null) return 0L
        val top = usage.optLong("reasoning_tokens", usage.optLong("reasoningTokens", 0L))
        val nested = usage.optJSONObject("completion_tokens_details")
            ?.optLong("reasoning_tokens", 0L) ?: 0L
        return maxOf(top, nested)
    }

    /**
     * Non-zero when the upstream billed reasoning tokens but the stream
     * carried no reasoning content at all — i.e. the billed thinking is either
     * mixed into `content` (relay translation gap) or silently dropped.
     * Returns the billed amount so the diagnostic can name the magnitude.
     */
    fun missingReasoningContent(usage: JSONObject?, reasoningLen: Int): Long {
        val billed = billedReasoningTokens(usage)
        return if (billed > 0 && reasoningLen <= 0) billed else 0L
    }
}
