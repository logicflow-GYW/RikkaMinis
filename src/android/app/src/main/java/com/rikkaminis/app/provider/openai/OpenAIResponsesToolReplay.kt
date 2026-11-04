package com.rikkaminis.app.provider.openai

import org.json.JSONObject

/**
 * Wire shape of a client-built `function_call` item replayed into the
 * Responses API `input` array after a tool round.
 *
 * [fix/responses-replay-item-id] Deliberately emits **no `id`**. In the
 * Responses API a client-supplied `function_call` is a *new* item: `id` is
 * server-assigned and optional, while `type`/`call_id`/`name`/`arguments` are
 * the required fields. Because this app runs stateless (`store:false`), the
 * `fc_…` id returned in a previous `response.output_item.done` is never a
 * valid reference — echoing it makes Azure multi-resource relays look the item
 * up by id and reject the next round with
 * `400 The requested item was created under a different Azure OpenAI resource`
 * (real device, 2026-11-05 00:47 / 00:49 / 00:54; retry re-sent the poisoned
 * history and failed identically). rikkahub replays the same shape on the
 * same relay without an `id` and works.
 *
 * The `call_…|fc_…` combined id is still persisted on the message (the Chat
 * Completions branch and tool-id de-dup use it); only the wire replay drops
 * the `fc_…` half. This keeps already-poisoned histories recoverable without
 * a DB migration.
 */
object OpenAIResponsesToolReplay {
    fun functionCall(callId: String, name: String, arguments: String): JSONObject =
        JSONObject().apply {
            put("type", "function_call")
            put("call_id", callId)
            put("name", name)
            put("arguments", arguments)
        }
}
