package com.rikkaminis.app.provider.voice

import com.rikkaminis.app.data.model.LLMModel
import org.json.JSONObject

/**
 * [T-android-provider-voice] Shared value types for the voice subsystem —
 * Android port of iOS VoiceInputModels.swift. `VoiceInput*` covers speech
 * recognition (ASR); `VoiceOutput*` covers speech synthesis (TTS).
 */

/** A speech-recognition (ASR) request: audio in, text out. */
data class VoiceInputRequest(
    /** 16 kHz mono WAV produced by the recorder (or any provider-acceptable audio). */
    val audioData: ByteArray,
    val model: String? = null,
    /** null = let the provider auto-detect the spoken language. */
    val language: String? = null,
    val responseFormat: VoiceInputFormat = VoiceInputFormat.JSON,
    /** Optional biasing prompt to improve recognition of domain terms. */
    val prompt: String? = null,
    /** The resolved model — lets the provider route chat-based ASR models. */
    val resolvedModel: LLMModel? = null,
) {
    // ByteArray field: identity equals is fine for a request value object.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

enum class VoiceInputFormat(val wireValue: String) {
    JSON("json"), TEXT("text"), SRT("srt"), VTT("vtt")
}

data class VoiceInputResponse(
    val text: String,
    val language: String? = null,
    val durationSeconds: Double? = null,
)

/** A speech-synthesis (TTS) request: text in, audio out. */
data class VoiceOutputRequest(
    val input: String,
    val model: String? = null,
    val voice: String? = null,
    /** 0.25 ~ 4.0, null = 1.0 (provider default). */
    val speed: Float? = null,
    val responseFormat: VoiceOutputFormat = VoiceOutputFormat.MP3,
)

enum class VoiceOutputFormat(val wireValue: String) {
    MP3("mp3"), OPUS("opus"), WAV("wav"), AAC("aac")
}

/** Errors thrown by voice providers. Mirrors iOS VoiceProviderError. */
sealed class VoiceProviderException(message: String) : Exception(message) {
    class Unsupported(detail: String) : VoiceProviderException("Unsupported: $detail")
    class Http(val code: Int, val body: ByteArray?) :
        VoiceProviderException(httpMessage(code, body))
    class Parse(detail: String) : VoiceProviderException("Parse failed: $detail")
    class Auth : VoiceProviderException("Authentication failed, please check the API key")
    class NoAudioData : VoiceProviderException("No audio data")

    companion object {
        private fun httpMessage(code: Int, body: ByteArray?): String {
            serverMessage(body)?.let { return "Request failed (HTTP $code): $it" }
            if (code == 404) {
                return "Request failed (HTTP 404) — check the provider's Base URL and model name"
            }
            return "Request failed (HTTP $code)"
        }

        /**
         * Best-effort extraction of an API error message from a JSON/text body.
         *
         * [R8-B B-F14] `optString("error")` on an OBJECT returns its
         * `toString()` — the whole JSON body — so `{"error":{"code":1}}` used to
         * surface as `Request failed (HTTP 500): {"code":1}`, unreadable and it
         * shadowed the `message` fallback. Only a STRING `error` is a message;
         * an object without `message` has nothing to say and falls through.
         *
         * The raw-body fallback stays, but only for a body that is NOT JSON:
         * echoing a JSON body the parser could not read a message out of is the
         * same unreadable output the fix above removes.
         */
        fun serverMessage(body: ByteArray?): String? {
            if (body == null || body.isEmpty()) return null
            val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return null
            val obj = runCatching { JSONObject(text) }.getOrNull()
                ?: return text.trim().takeIf { it.isNotEmpty() && it.length < 200 }
            (obj.optJSONObject("error")?.optString("message"))?.takeIf { it.isNotBlank() }
                ?.let { return it }
            if (obj.has("error") && !obj.isNull("error") && obj.opt("error") is String) {
                obj.optString("error").takeIf { it.isNotBlank() }?.let { return it }
            }
            obj.optString("message").takeIf { it.isNotBlank() }?.let { return it }
            return null
        }
    }
}
