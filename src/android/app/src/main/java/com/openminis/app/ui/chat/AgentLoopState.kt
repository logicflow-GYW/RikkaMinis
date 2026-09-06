package com.openminis.app.ui.chat

import com.openminis.app.provider.LLMProvider

/**
 * FE-5 route C step 2: the mutable run-scoped state of
 * ChatViewModel.runAgentLoop, lifted out of ~40 scattered `var`s into one
 * data holder. The VM constructs it at loop entry; the engine (and later
 * steps) mutate fields on it directly — same single-threaded (loop
 * coroutine) discipline as when they were locals, so no new sync.
 *
 * Field-by-field this is a verbatim lift of the local `var`/`val` block at
 * runAgentLoop entry (see git history for the exact original comments);
 * only the trace observer fields stayed on the observer itself.
 *
 * NOTE on threading: [AssistantBlock]/tool-input maps are mutated only
 * from the loop coroutine (plus Main-hopped UI snapshot flips driven by
 * that same coroutine), exactly as before. Do NOT touch these from other
 * coroutines.
 */

/** A fallback provider candidate: provider + the group entry it came from.
 *  Was a private nested VM class; promoted to top-level internal (FE-5
 *  route C) so AgentLoopState / the engine layer can reference it. */
internal data class FallbackCandidate(
    val provider: LLMProvider,
    val entryId: String,
)

internal class AgentLoopState(
    /** The provider currently being tried (reassigned by fallback). */
    var currentProvider: LLMProvider,
    /** Fallback candidates not yet attempted (consumed head-first). */
    val remainingFallbacks: MutableList<FallbackCandidate>,
    /** Human-readable reasons for each fallback switch so far. */
    val fallbackReasons: MutableList<String>,
) {
    // ── bubble identity & blocks ───────────────────────────────────────────

    /**
     * Normally a single message id for the whole agent loop (iOS-parity:
     * multiple tool/text turns folded into one bubble). Reassigned ONLY when
     * a queued mid-loop prompt is injected as a new turn: the just-finished
     * bubble is sealed and a fresh assistantId starts so the queued user
     * message renders BETWEEN them. `allToolBlocks` and `accumulatedText`
     * are also reset at that point so the new bubble starts empty.
     */
    var assistantId: String = ""

    /** All tool blocks across the current bubble (reset on queue injection). */
    val allToolBlocks: MutableList<AssistantBlock> = mutableListOf()

    /**
     * [fix/stream-segmenter-duplication] Monotonic, run-scoped (NOT
     * turn-scoped) block sequence. Text block ids are built as
     *   "text_${turn}_${allToolBlocks.size}_${blockSeq++}"
     * so a block id can NEVER be recycled across turns, retries, or fallback
     * rollbacks. This is the structural fix for the StableChatRowLedger
     * segmenter-reattach bug: a recycled id previously let a stale
     * AppendOnlyMarkdownSegmenter (holding the PREVIOUS stream's full text)
     * re-attach to the NEW stream and re-emit ghost content (whole-paragraph
     * duplication). With ids globally unique, textReset's id-set comparison
     * is naturally correct and stale segmenters are guaranteed unreachable.
     */
    var blockSeq: Int = 0

    /**
     * Per-tool ring of the most recent `accumulated` JSON snapshots emitted
     * by `LLMStreamChunk.ToolInputDelta`. Capped at TOOL_INPUT_CHUNK_RING_MAX
     * entries per tool id so memory stays bounded even on long streams.
     * The preflight validator drains this on a blocked call so we can
     * reconstruct how the model assembled (or failed to assemble) the args.
     */
    val toolInputChunkRings: MutableMap<String, MutableList<String>> = mutableMapOf()

    // ── accumulated text & context ─────────────────────────────────────────

    /** Cross-turn accumulated visible text of the current bubble. */
    var accumulatedText: String = ""

    /** Context tokens as reported by the last API usage block. */
    var lastContextTokens: Int = 0

    /**
     * Accumulate tool inputs across all turns (so persist includes all, not
     * just current turn).
     */
    val allToolInputs: MutableMap<String, String> = mutableMapOf()

    // ── streaming throttles & buffers (T94/T256/T307) ──────────────────────

    /**
     * T94 fix 2: throttle text-delta UI updates to ~20fps (50ms base) with
     * T256 tiered gates (150ms → 2s by turn length) and T307 StringBuilder
     * accumulation. These are the hot per-delta fields — they live together
     * so a future step can lift the whole emit-block without re-hunting.
     */
    var lastUiUpdateMs: Long = 0L
    var lastFlushedLen: Int = 0
    val pendingChunkSb: StringBuilder = StringBuilder()

    /** T256 tier 2: per-tool-kind input-delta gates (1Hz file tools / 5Hz other). */
    var lastFileToolInputMs: Long = 0L
    var lastOtherToolInputMs: Long = 0L

    // ── run-level one-shot / recovery guards ───────────────────────────────

    /**
     * Tracks whether the loop was exited via a `break` (any reason — no tool
     * calls, msgIdx safety, etc.) or fell off the end of the range. Set false
     * by every break path that *isn't* "the model wanted to keep going past
     * MAX_AGENT_TURNS". Without this flag the post-loop tail can't tell the
     * runaway path apart from a normal turn ending, which previously slapped
     * a fake "200 turns hit" error on every ordinary completion.
     */
    var loopExitedNormally: Boolean = false

    /**
     * [T-android-empty-after-toolresult-reminder] One-shot guard for the
     * "<system-reminder> + retry one round" recovery when the server returns
     * an empty response right after a tool result. Fires at most once per
     * runAgentLoop so it can never loop; if the reminder round is also empty
     * we surface a real error instead of a silent blank bubble.
     */
    var didInjectEmptyToolReminder: Boolean = false

    var didRetryTruncatedTurn: Boolean = false

    /**
     * [fix/eof-stub-continuation] EOF-truncated-stream continuation count.
     * Replaces the one-shot [didRetryTruncatedTurn] semantics: instead of
     * deleting the partial answer and regenerating (waste + re-emission) or
     * breaking silently on the second EOF, the engine keeps the partial
     * text as the model's own last turn and appends a network-stub reminder
     * (Hermes conversation_loop network-stub pattern). Capped at
     * [MAX_EOF_STUB_CONTINUES] per run; the counter resets on tool-call
     * turns (model produced new work) so long tool-heavy runs keep full
     * allowance.
     */
    var eofStubContinues: Int = 0

    /**
     * [T-length-wall-continue] Consecutive finish_reason="length" turns that
     * produced NO visible content and NO tool calls. First hit: continue the
     * loop. 3+ empty walls in a row: drop the per-turn max_tokens cap and
     * retry, then give up with a visible error.
     */
    var lengthWallEmptyHits: Int = 0

    /**
     * [feat/hermes-tier1] Text-continuation attempts for the CURRENT
     * length-wall wall (finish_reason="length" with visible text). Hermes
     * caps text continuation at 4 nudges then aborts with a typed result —
     * without a cap a model that re-truncates on every continuation attempt
     * burns unbounded billed calls. Reset on a successful tool-call turn
     * (the wall was cleared) and on a clean finish.
     */
    var lengthWallContinues: Int = 0

    /**
     * [feat/hermes-tier1] Consecutive empty completions whose usage proves
     * zero output tokens (deterministic empty, Hermes empty_response_guard
     * port). After [REPETITION_DETERMINISTIC_EMPTY_LIMIT] consecutive
     * deterministic empties the loop stops retrying the same provider and
     * surfaces the empty-turn hint — retrying a provider that PROVABLY
     * produced zero tokens just re-bills full input for nothing.
     */
    var deterministicEmptyStreak: Int = 0

    /**
     * [T-length-wall-seam-dedup] True when the PREVIOUS turn ended with
     * finish_reason="length" and had visible text. Only then is the next
     * turn's text a "continuation" whose head may illegally repeat the
     * truncated tail — mergeLengthWallSeam trims that overlap at the fold
     * point. Normal turn boundaries must NOT go through seam-dedup.
     */
    var lastTurnWasLengthWall: Boolean = false

    // ── [feat/verification-stop] edit/verify evidence tracking ────────────

    /**
     * Code file paths changed by successful file_write / file_edit calls in
     * this run (deduped). Feeds VerificationStopPolicy.buildNudge at the
     * turn-end guard.
     */
    val changedCodePaths: MutableSet<String> = linkedSetOf()

    /**
     * Human-readable detail of the newest verification-shaped shell result
     * (command + outcome), or null when none ran yet this run.
     */
    var lastVerificationDetail: String? = null

    /**
     * Monotonic sequence stamps: an edit and a verification only satisfy the
     * guard when the verification's stamp is NEWER than every edit's stamp.
     */
    var lastEditSeq: Long = 0
    var lastVerifySeq: Long = 0

    /** How many verify nudges have been injected this run (bounded by
     *  VerificationStopPolicy.MAX_VERIFY_NUDGES). */
    var verifyNudgeAttempts: Int = 0

    /**
     * [audit-0907 B5] A terminal inline error was already surfaced inside
     * the loop body (error-shaped finish ceiling / repetition abort /
     * deterministic-empty fast-exit / length-wall continuation ceiling).
     * Those branches fall through to the normal persist+break path with
     * loopExitedNormally still false — without this flag the exit-side
     * MAX_AGENT_TURNS check misclassifies them as a runaway and calls
     * finalizeAtTurnLimit, OVERWRITING the specific error banner the user
     * already saw with a generic "Stopped after 200 agent turns" sticker.
     * Failure-terminal, deliberately distinct from loopExitedNormally
     * (which maps to SUCCEEDED/COMPLETED in t7EndRun — these map to FAILED).
     */
    var terminalErrorSurfaced: Boolean = false
}
