package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/**
 * [deep-audit-1045 A-03] One shared answer to "does this message still carry
 * anything worth putting on the wire?".
 *
 * The three provider serializers (OpenAI chat/completions, OpenAI responses,
 * Anthropic) each dropped messages that the orphan-tool-pairing sanitizer had
 * emptied, and each wrote the SAME predicate:
 * `contentParts.isNotEmpty() || content.isNotEmpty()`. That predicate answers
 * "is there TEXT?", which is not the question being asked. A `minis-model-use`
 * turn that carries only an image block or only an audio block has no text,
 * so the filter deleted the whole user turn and the model never saw the
 * bytes — the request went out valid and semantically empty.
 *
 * The predicate therefore also accepts:
 *  - [LLMMessage.audioParts] — inline audio attachments (GH#67);
 *  - [LLMMessage.imageParts] — per-message image parts;
 *  - a turn that the REQUEST-level image list attaches to, i.e. the last user
 *    turn when the caller passed top-level images ([turnImageCount] > 0).
 *    The providers attach those bytes to the last user turn only, so that turn
 *    must survive the filter for the bytes to have a home.
 *
 * Pure + top-level so the boundary table is directly unit-testable
 * (see MessagePayloadFilterTest) instead of being triplicated inline.
 */
internal fun LLMMessage.hasWirePayload(
    isLastUserTurn: Boolean,
    turnImageCount: Int,
): Boolean =
    // [r3-b08 F-09] "has parts" is not "has payload": a list holding nothing but
    // `Text("")` is non-empty and used to pass, so the orphan-tool-pairing
    // sanitizer could strip a tool_use block and leave a message that still went
    // on the wire as `{"type":"text","text":""}` — a 400 on the Anthropic
    // Messages API (`text content blocks must be non-empty`) on EVERY later turn
    // of that conversation. The persisted empty assistant bubble
    // (AgentLoopState.TerminalErrorPlacement writes exactly `listOf(Text(""))`)
    // is the consumer-side producer of that shape.
    contentParts.any { it !is AgentContentPart.Text || it.text.isNotEmpty() } ||
        content.isNotEmpty() ||
        audioParts.isNotEmpty() ||
        imageParts.isNotEmpty() ||
        (isLastUserTurn && turnImageCount > 0)

/**
 * [r3-b08 F-13] The payload of one SSE `data:` line, or null when the line is
 * not a data line at all.
 *
 * The HTML5 SSE spec allows `data:{…}` — the single space after the colon is
 * OPTIONAL and, if present, is the only thing a parser may strip. All three
 * stream parsers used to require `data: ` (space included), so a gateway that
 * omits it — a legal, common Go/Nginx-relay output — had EVERY frame dropped:
 * the stream then looked like a silent upstream failure (empty response) or, if
 * it outlived the first-data watchdog, like a connect timeout. OpenAIProvider
 * was fixed for this one-gateway-at-a-time; the other two kept the strict
 * prefix, so the third relay hit the same wall again.
 *
 * Pure + top-level so the boundary is unit-testable once instead of three times
 * (see SseDataPayloadTest), and so a fourth parser has one function to call.
 */
internal fun sseDataPayload(line: String): String? {
    if (!line.startsWith("data:")) return null
    val rest = line.removePrefix("data:")
    return if (rest.startsWith(" ")) rest.removePrefix(" ") else rest
}
