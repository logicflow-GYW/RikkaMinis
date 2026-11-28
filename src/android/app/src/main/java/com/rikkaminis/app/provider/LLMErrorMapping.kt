package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMError

/** Classify transport failures without replacing an already classified error. */
public fun llmErrorOf(error: Throwable): LLMError {
    if (error is LLMError) return error
    if (error is java.io.IOException) return LLMError.NetworkError(error)
    return LLMError.Unknown(error)
}
