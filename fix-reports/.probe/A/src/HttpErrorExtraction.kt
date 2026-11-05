package com.rikkaminis.app.provider

import org.json.JSONObject

/**
 * Extract a readable message from an HTTP error body, tolerating the shapes
 * relays actually return. Candidates, in order:
 *
 *  1. `{"error": {"message": "..."}}` — OpenAI / Anthropic / Gemini standard
 *  2. `{"error": "..."}`             — error carried as a bare string
 *  3. `{"message": "..."}`           — bare relay shape (observed from api.senseaudio.cn:
 *                                      `{"code":"internal","message":"服务繁忙，请稍后再试",...}` on HTTP 500)
 *  4. `{"msg": "..."}`               — close cousin used by other relays
 *
 * When nothing matches — or the body is not JSON at all — falls back to a
 * bounded slice of the raw body, so a diagnostic string never carries an
 * entire undocumented JSON document.
 */
internal fun extractHttpErrorMessage(body: String, fallbackTake: Int = 500): String {
    try {
        val json = JSONObject(body)
        val errorObject = json.optJSONObject("error")
        if (errorObject != null) {
            val message = errorObject.safeOptString("message")
            if (message.isNotBlank()) return message
        } else {
            val errorText = json.safeOptString("error")
            if (errorText.isNotBlank()) return errorText
        }
        val message = json.safeOptString("message")
        if (message.isNotBlank()) return message
        val msg = json.safeOptString("msg")
        if (msg.isNotBlank()) return msg
    } catch (_: Exception) {
        // Not a JSON object — fall through to the bounded raw-body slice.
    }
    return body.take(fallbackTake)
}
