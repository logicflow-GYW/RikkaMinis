package com.rikkaminis.app.provider.openai

/**
 * [T-rhub1105-F01] Responses-API tool-call accumulator and the ONE lookup rule
 * that resolves a streaming frame to its accumulator.
 *
 * Lives in its own file (not inside `OpenAIProvider`) for two reasons:
 *   1. the lookup rule is shared by three frames
 *      (`response.output_item.added` / `response.function_call_arguments.delta` /
 *      `response.output_item.done`) — one function instead of three re-implementations;
 *   2. it is pure and Android-free, so it is directly unit-testable.
 *
 * `itemId` is the server-assigned `fc_…` id — OPTIONAL in the Responses schema
 * (see `OpenAIResponsesToolReplay`), so a relay that omits it must still yield
 * a usable tool call; `callId` is the stable agent-loop correlation id.
 */
internal data class ResponsesToolCallAccumulator(
    var callId: String = "",
    var name: String = "",
    val args: StringBuilder = StringBuilder(),
    var started: Boolean = false,
    var lastLoggedInputChars: Int = -1,
    /** Server item id (`fc_…`); empty when the relay did not send one. */
    var itemId: String = "",
    /** Position of the item in the response output array; -1 when unknown. */
    var outputIndex: Int = -1,
)

/**
 * [T-rhub1105-F01] Single lookup rule for a Responses tool-call accumulator.
 *
 * Priority: `item_id` → `call_id` → `output_index`. The first two are exact
 * identity; the third is positional and therefore only consulted when the
 * server did not identify the item at all.
 */
internal fun resolveResponsesAccumulator(
    accumulators: Collection<ResponsesToolCallAccumulator>,
    itemId: String,
    callId: String,
    outputIndex: Int,
): ResponsesToolCallAccumulator? =
    accumulators.firstOrNull { itemId.isNotEmpty() && it.itemId == itemId }
        ?: accumulators.firstOrNull { callId.isNotEmpty() && it.callId == callId }
        ?: accumulators.firstOrNull { outputIndex >= 0 && it.outputIndex == outputIndex }
