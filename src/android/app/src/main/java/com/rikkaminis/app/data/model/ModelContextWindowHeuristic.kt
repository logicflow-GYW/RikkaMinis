package com.rikkaminis.app.data.model

/**
 * T-ctxslider 54ab8e93: heuristic context-window inference used by the
 * model-group "Limit Context Window" slider when a member's
 * `LLMModel.contextWindow` field is missing or non-positive.
 *
 * IMPORTANT: this is intentionally NOT folded into [LLMModel.contextWindowTokens]
 * — that getter is read by the agent loop / token accounting paths and we
 * don't want to silently overwrite source-of-truth metadata. This helper is
 * used ONLY when computing the slider's "Unlimited" ceiling so that a group
 * whose members lack contextWindow metadata still gets a reasonable cap
 * instead of degrading to the 64K floor.
 *
 * [gateway-context-1112] This helper used to carry its OWN id-shape table,
 * which had drifted from [LLMModel.contextWindowTokens] — the two disagreed
 * on the unknown-id default (this file said 128 000, the other said 1 000 000)
 * and on several families. Two tables for one question means the number the
 * user is shown depends on WHICH screen asks, and the "same model, different
 * context window" report is exactly that symptom.
 *
 * It now DELEGATES to the single owner, [LLMModel.contextWindowTokens]. The
 * caller-facing behaviour is unchanged for every id both tables agreed on
 * (the group slider still gets a sane cap for a metadata-less member); only
 * the divergent ids change, and they change towards the one table the
 * runtime actually uses — so what the UI shows is what the agent loop
 * enforces.
 */
internal fun inferContextWindowTokens(model: LLMModel): Int =
    model.contextWindowTokens
