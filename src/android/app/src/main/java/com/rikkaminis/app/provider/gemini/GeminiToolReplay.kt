package com.rikkaminis.app.provider.gemini

import org.json.JSONObject

/**
 * Wire shape of a client-rebuilt `functionCall` part replayed into a later
 * `generateContent` / `streamGenerateContent` request.
 *
 * [fix/gemini-thought-signature-echo] Gemini 3.x requires EVERY historical
 * `functionCall` part to carry a `thoughtSignature`; a stateless client that
 * rebuilds its own history otherwise gets a deterministic 400 on the second
 * tool round:
 *
 * ```
 * Function call is missing a thought_signature in functionCall parts.
 * ```
 *
 * Real-device repro: 2026-11-05 17:11:26 (`position 4`) and 17:12:07
 * (`position 2`), gemini-3.1-flash-lite via a first-party API key.
 *
 * The signature sits on the PART (sibling of `functionCall`), not inside the
 * `functionCall` object — putting it inside yields
 * `Invalid JSON payload ... Unknown name "thoughtSignature"`.
 *
 * This app does not persist the model's thought parts, so it cannot echo the
 * real server-issued signature back. Google therefore provides a documented
 * client-rebuild sentinel, `skip_thought_signature_validator`, for stateless
 * clients that manage conversation history themselves. Both the plain value
 * and its base64 form are accepted; the plain form is used here.
 *
 * Measured against the live API (2026-11-05, gemini-3.1-flash-lite,
 * gemini-2.5-flash and gemini-2.5-flash-lite):
 *   no signature / empty string / null  -> 400 missing thought_signature
 *   arbitrary non-empty garbage         -> 400 Corrupted thought signature.
 *   sentinel                            -> 200, normal continuation
 *   two historical calls, only 1 signed -> 400 (EVERY call needs it)
 *   signature inside functionCall       -> 400 Unknown name
 * Older models ignore the field harmlessly, so the sentinel is unconditional.
 */
object GeminiToolReplay {
    /**
     * Google's sentinel for stateless clients that cannot echo the original
     * signature. Hand-written literal — never derived from a constant under test.
     */
    const val THOUGHT_SIGNATURE_SENTINEL: String = "skip_thought_signature_validator"

    /** Build one `role=model` part replaying a past tool call. */
    fun functionCall(name: String, args: JSONObject): JSONObject =
        JSONObject().apply {
            put("thoughtSignature", THOUGHT_SIGNATURE_SENTINEL)
            put("functionCall", JSONObject().apply {
                put("name", name)
                put("args", args)
            })
        }
}
