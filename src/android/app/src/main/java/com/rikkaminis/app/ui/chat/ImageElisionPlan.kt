package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage

/**
 * [W-1027 W-05] Pure rewrite phase of `ChatViewModel.applyRequestImageBudget`:
 * take the request budget's verdict ([droppedIds]) and rewrite BOTH carriers of
 * each elided image — `contentParts` (where the bytes sit as
 * `ImageData`/`ToolResult.imageData`) and `imageParts` (the second copy every
 * provider appends to the wire: OpenAI `:2277`/`:3246`, Gemini `:515`,
 * Anthropic `:917`).
 *
 * Extracted from the Android-side caller so a JVM arm can drive the exact
 * production judgement: the interesting part is not the placeholder text, it is
 * *which* ids end up recorded as "this message really lost those bytes".
 *
 * ## The judgement, and why it is not `===`
 *
 * A part is recorded in [ImageElision.droppedIdsByMessage] only when the
 * rewrite ACTUALLY replaced it (`replaced !== original`). Flipping that
 * comparison records nothing (every elided part was replaced, so the identity
 * test is false under `===`) and `imageParts` is then filtered by an empty set
 * — the elision becomes a no-op exactly where it matters, and every provider
 * re-sends the "elided" megabytes at full size (the 413 incident
 * `b9f6df75f` fixed). Pinned by `ImageElisionPlanTest` arm 1 + arm 3 under the
 * `!==` → `===` mutation.
 *
 * ## Byte identity, not equality
 *
 * Ids are `System.identityHashCode` of the `ByteArray` — the same key
 * `ImageBudget.ImagePartId.of` uses — because the reload path hands the SAME
 * array to both carriers. Two byte-equal arrays on different messages are
 * different images and must be decided per message (arm 3).
 *
 * Android-free on purpose: [placeholder]/[pathFor] are injected, so the text
 * still comes from `ImageBudget.elidedImagePlaceholder` and the spillover path
 * from the caller that owns `filesDir`.
 */
internal data class ImageElision(
    /** Rewritten messages; the input instance when nothing was elided. */
    val messages: List<LLMMessage>,
    /** Per message index: byte-identity hashes actually removed from it. */
    val droppedIdsByMessage: List<Set<Int>>,
) {
    val mutated: Boolean get() = droppedIdsByMessage.any { it.isNotEmpty() }
}

/**
 * @param messages history about to be sent; untouched parts keep their
 *   instances so downstream identity checks stay valid.
 * @param droppedIds identity hashes of the bytes the budget plan elided.
 * @param placeholder builds the model-visible text for an elided image from
 *   its still-addressable linux path (or null when the bytes are gone).
 * @param pathFor identity hash → linux path the caller resolved (existing
 *   `linuxPath`, a fresh spillover write, or null).
 */
internal fun planImageElision(
    messages: List<LLMMessage>,
    droppedIds: Set<Int>,
    placeholder: (String?) -> String,
    pathFor: (Int) -> String?,
): ImageElision {
    if (droppedIds.isEmpty()) return ImageElision(messages, emptyList())
    var mutated = false
    val out = ArrayList<LLMMessage>(messages.size)
    val droppedByMessage = ArrayList<Set<Int>>(messages.size)
    for (msg in messages) {
        val droppedHere = LinkedHashSet<Int>()
        val newParts = msg.contentParts.toMutableList()
        for (i in msg.contentParts.indices) {
            val id = imageIdOf(msg.contentParts[i]) ?: continue
            if (id !in droppedIds) continue
            val text = placeholder(pathFor(id))
            val original = newParts[i]
            val replaced: AgentContentPart = when (original) {
                is AgentContentPart.ImageData -> AgentContentPart.Text(text)
                // Strip the bytes but keep the structural ToolResult role;
                // append the elision marker into content so the model sees it
                // next to the rest of the tool output.
                is AgentContentPart.ToolResult -> original.copy(
                    imageData = null,
                    imageMimeType = null,
                    content = original.content +
                        (if (original.content.isEmpty()) "" else "\n") + text,
                )
                else -> original
            }
            newParts[i] = replaced
            if (replaced !== original) droppedHere.add(id)
        }
        droppedByMessage.add(droppedHere)
        if (droppedHere.isEmpty()) {
            out.add(msg)
            continue
        }
        mutated = true
        out.add(
            msg.copy(
                contentParts = newParts,
                imageParts = filterImagePartsByHash(msg.imageParts, droppedHere),
            )
        )
    }
    return ImageElision(if (mutated) out else messages, droppedByMessage)
}

/** Byte-identity key of the image bytes a part carries, or null if it carries none. */
internal fun imageIdOf(part: AgentContentPart): Int? = when (part) {
    is AgentContentPart.ImageData -> System.identityHashCode(part.data)
    is AgentContentPart.ToolResult -> part.imageData?.let { System.identityHashCode(it) }
    else -> null
}

/**
 * Same contract as [filterElidedImageParts], keyed by the raw identity hash so
 * this file stays free of `ImageBudget` (which imports `android.graphics`).
 * Unchanged lists are returned as the same instance.
 */
private fun filterImagePartsByHash(
    parts: List<LLMMessage.ImagePart>,
    dropped: Set<Int>,
): List<LLMMessage.ImagePart> {
    if (parts.isEmpty() || dropped.isEmpty()) return parts
    val kept = parts.filter { System.identityHashCode(it.data) !in dropped }
    return if (kept.size == parts.size) parts else kept
}
