package com.rikkaminis.app.data

/**
 * [fix/offload-payload-stub] Second line of defence behind
 * [com.rikkaminis.app.ui.chat.isOffloadEligible]: detects a
 * `[CONTEXT OFFLOADED] …` disk stub sitting where a tool *payload* should be,
 * and decides what the write tools must do about it.
 *
 * [ContextOffload.stub] renders a short pointer ("…saved to: <path>. Use
 * file_read tool to retrieve if needed."). That is the correct representation
 * for an *observation* in the history — the bytes are on disk and the model
 * can fetch them back. It is the wrong representation for a *write payload*,
 * and until the eligibility fix nothing stopped it from landing there:
 * a 3714-byte file was silently truncated to the ~150-byte stub while
 * `file_write` reported success.
 *
 * The eligibility fix removes the source. This guard exists because the
 * source is not the only way a stub can reach a payload slot — a model can
 * also copy a stub it saw in a tool result, and an already-mutated in-memory
 * history from the running build is not retroactively cleaned. A stub in a
 * payload slot must be a loud error, never a silent write.
 *
 * Pure by design (no Android imports) so the decision table is JVM-testable.
 */
internal object OffloadedPayloadGuard {

    private const val SAVED_MARKER = "saved to: "

    /**
     * The exact shape [ContextOffload.stub] mints. Matching it (rather than
     * just the prefix) is what tells a real stub apart from a document that
     * merely quotes one — see [decide]'s byte check.
     */
    private val STRICT_STUB = Regex(
        "^\\[CONTEXT OFFLOADED] Content \\(~(\\d+) tokens, (\\d+) bytes\\) saved to: (\\S+)",
    )

    /**
     * @param offloadPath where the pointer claims the real bytes live, or null
     *   if it does not say.
     * @param declaredBytes byte count the stub itself advertises. Non-null only
     *   for the strict shape; a drifted or hand-edited marker yields null, which
     *   [decide] treats as unhealable.
     */
    data class Stub(val offloadPath: String?, val declaredBytes: Int? = null)

    /** What a write tool should do with an incoming payload. */
    sealed interface Action {
        /** Real content — write it unchanged. */
        data object WriteAsIs : Action

        /** The payload was a stub; write [content] instead, recovered from [from]. */
        data class Heal(val content: String, val from: String) : Action

        /** The payload was a stub and must not be written at all. */
        data class Refuse(val reason: RefusalReason, val offloadPath: String?) : Action
    }

    enum class RefusalReason {
        /**
         * Appending recovered bytes would duplicate content that the original
         * call very likely already appended — refuse instead of guessing.
         */
        APPEND_STUB,

        /** The stub names no path, or the bytes behind it are gone. */
        UNRECOVERABLE,
    }

    /**
     * @return non-null when [content] is an offload stub rather than real
     *   payload bytes. Prefix-anchored, so a document that merely *mentions*
     *   the marker mid-text is left alone.
     */
    fun asStub(content: String): Stub? {
        if (!content.startsWith(ContextOffload.OFFLOADED_PREFIX)) return null
        val strict = STRICT_STUB.find(content)
        if (strict != null) {
            return Stub(
                offloadPath = strict.groupValues[3],
                declaredBytes = strict.groupValues[2].toIntOrNull(),
            )
        }
        // Detection stays anchored on the prefix so a future format tweak can
        // never silently disable the guard — it just loses the byte count, and
        // [decide] refuses rather than guessing.
        return Stub(offloadPathOf(content))
    }

    /**
     * Decide what to do with a write payload.
     *
     * @param append the tool's own append flag. Healing an append is refused
     *   ([RefusalReason.APPEND_STUB]) because the original call almost
     *   certainly already appended these bytes — re-appending would silently
     *   duplicate them, which is a different corruption than the one we are
     *   fixing.
     * @param recover maps a stub's offload path to its bytes, or null when
     *   they are gone. Offload directories are per-session, so a stub minted
     *   in a previous session will not resolve here — that is expected, and
     *   it is why the unrecoverable branch must be a refusal rather than a
     *   best-effort write.
     */
    fun decide(content: String, append: Boolean, recover: (String) -> String?): Action {
        val stub = asStub(content) ?: return Action.WriteAsIs
        if (append) return Action.Refuse(RefusalReason.APPEND_STUB, stub.offloadPath)
        val path = stub.offloadPath
            ?: return Action.Refuse(RefusalReason.UNRECOVERABLE, null)
        // [audit-0914] Only heal when the bytes we found are the bytes the stub
        // promised. Without this check a payload that merely *looks* like a stub
        // — a document quoting one, a fixture, a file whose own text opens with
        // the marker — would have its content silently replaced by whatever file
        // the quoted path happens to resolve to. A size mismatch means the path
        // is not the stub's file (or the marker was not minted by us), so it is
        // treated as unrecoverable: writing the stub out verbatim is still the
        // corruption this guard exists to prevent, so refusal is the only safe
        // direction.
        val declared = stub.declaredBytes
            ?: return Action.Refuse(RefusalReason.UNRECOVERABLE, path)
        val bytes = recover(path)
            ?: return Action.Refuse(RefusalReason.UNRECOVERABLE, path)
        if (bytes.toByteArray(Charsets.UTF_8).size != declared) {
            return Action.Refuse(RefusalReason.UNRECOVERABLE, path)
        }
        return Action.Heal(bytes, path)
    }

    /**
     * Human-readable tool output for a refusal. Names the offload path when
     * there is one so the caller can still `file_read` it, and tells the model
     * what to do next instead of leaving it to guess.
     */
    fun refusalMessage(displayPath: String, refuse: Action.Refuse): String {
        val where = when {
            refuse.offloadPath != null ->
                " The original bytes are at ${refuse.offloadPath} — use the file_read tool if that path still resolves."
            else -> " The original bytes are not recoverable from this stub."
        }
        val why = when (refuse.reason) {
            RefusalReason.APPEND_STUB ->
                "Refusing to append it: the earlier call that produced this content most likely already appended it, and appending again would duplicate data."
            RefusalReason.UNRECOVERABLE ->
                "Refusing to write it: a stub is a pointer, not content."
        }
        return "Error: the 'content' argument is a [CONTEXT OFFLOADED] stub, not real file content. " +
            "Writing it would have replaced $displayPath with a ~150-byte placeholder. $why$where " +
            "Re-issue the call with the intended content."
    }

    private fun offloadPathOf(stub: String): String? {
        val at = stub.indexOf(SAVED_MARKER)
        if (at < 0) return null
        val firstLine = stub.substring(at + SAVED_MARKER.length)
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
        return firstLine.ifEmpty { null }
    }
}
