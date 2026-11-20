package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.AgentTerminalReason
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.sandbox.offload.TranscriptCommitHandle

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

    /**
     * [surgery-1120 A-01] The bubble identity used for transcript row ids. It
     * is STABLE for the whole bubble (one user message = one bubble) and is
     * stamped into every run dir of this bubble, so the recovery path — a
     * different process — recomputes the same deterministic row ids.
     */
    var transcriptBubbleId: String = ""

    /**
     * [surgery-1120 A-01] The streamed attempt whose transcript row is being
     * written. Stamped into the run dir's meta before the stream starts and
     * reused as the deterministic row id, so a crash after the DB insert and
     * before the commit marker finds the row instead of inserting a duplicate.
     */
    var currentTranscriptTurnId: String? = null

    /**
     * [surgery-1120 A-01] How many assistant rows this bubble has already
     * planned. A bubble can carry more than one row (a tool turn persists its
     * row, the final text turn persists another), so the deterministic id
     * needs an index — the same index the recovery path can recompute.
     */
    var transcriptRowIndex: Int = 0

    /**
     * [surgery-1120 A-01] Handles of streamed attempts whose terminal spool is
     * waiting for this turn's transcript commit. Length-wall / empty-turn / EOF
     * continuations add one handle per attempt and all of them are released by
     * the ONE terminal persist that carries their text. Cleared on release.
     */
    val pendingTranscriptCommits: MutableList<TranscriptCommitHandle> = mutableListOf()

    /** All tool blocks across the current bubble (reset on queue injection). */
    val allToolBlocks: MutableList<AssistantBlock> = mutableListOf()

    /**
     * [deep-audit-1016c ISSUE-01] Run-scoped start of the block range that has
     * NOT been written to any DB row yet — "尚未落库的块起点".
     *
     * Why this exists (and why it is NOT the same thing as the per-turn
     * `turnStartBlockIndex` the loop captures at its top every iteration):
     * `allToolBlocks` is append-only for the whole run, but five no-tool
     * continuation branches (`continue` without a terminal persist: EOF-stub,
     * length-wall prefill / reminder, verify nudge, residue-refill) leave the
     * text they already streamed unpersisted. The next iteration re-captures
     * `turnStartBlockIndex` at the CURRENT end of the list, so every later
     * persistence slice — which used to start there — skips those blocks
     * forever: the segment reloads as nothing. This index moves ONLY when a
     * persist actually lands, so the next terminal slice still covers the
     * skipped segment.
     *
     * Two-index contract (do not collapse them):
     *  - persistence slices (terminal / early / live preview) → THIS index;
     *  - `rollbackTurnBlocksTo(allToolBlocks, turnStartBlockIndex)` (fallback
     *    retry: roll back ONLY the failed attempt's partial blocks) and the
     *    per-turn empty check (`allToolBlocks.drop(turnStartBlockIndex)` —
     *    "did THIS turn show anything") → `turnStartBlockIndex`.
     *
     * Invariants: `0 <= unpersistedBlockStart <= allToolBlocks.size` at all
     * times; advanced to `allToolBlocks.size` ONLY on a successful persist
     * (never when the DB returned no row id); reset to 0 wherever
     * `allToolBlocks` is cleared (queue-interrupt new bubble).
     */
    var unpersistedBlockStart: Int = 0

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
     * [fix/prewindow-budget-1015] 记下 [lastContextTokens] 是哪个
     * (providerType, baseUrl, model) 上报的。跨 provider 换模型后读数失效：
     * 各端点记账口径可差 4.8×（T321 现场），旧读数偏小 → 输出预算虚高爆窗
     * （预检兜住），偏大 → dynamicMaxTokens 饿死输出（空 length-wall）。引擎
     * 在预算计算前比对 key，不匹配即视为未知（0）。
     */
    var lastContextTokensSourceKey: String? = null

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

    /**
     * [T-android-thinking-delta-main-thread-throttle] Separate throttle clock for
     * the reasoning ("thinking") delta branch.
     *
     * WHY THIS EXISTS: the ThinkingDelta branch used to call
     * `withContext(Dispatchers.Main) { host.updateAssistantMessage(...) }` on
     * EVERY delta with no gate at all, while its sibling Text branch is gated by
     * [lastUiUpdateMs] + `textDeltaThrottleMs`. Measured on device (2026-09-20):
     * 91.8% of all SSE deltas are thinking-only (59,403 of 64,744), peaking at
     * 1,052 deltas/s and 14,258 deltas in a single turn — i.e. ~1,000 main-Looper
     * posts per second, each of which also ran an O(accumulated-text)
     * `Utf16Sanitizer.sanitize()` inside `updateAssistantMessage` BEFORE that
     * function's own throttle gate. Main thread measured at 59.8% of one core
     * with `Number Slow UI thread: 1811`, which is the input-box lag.
     *
     * Deliberately a SEPARATE clock from [lastUiUpdateMs]: the two branches
     * interleave (thinking → text → thinking on a continuation turn), and sharing
     * one clock would let a thinking flush starve the first text delta.
     */
    var lastThinkingUiUpdateMs: Long = 0L

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
     * an empty response. [port-v114 S4] broadened: fires for any nudgeable
     * history tail (tool_result or text part), decided by EmptyTurnRecovery.
     * Fires at most once per
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
     * [deep-audit-1015 BUG-04] Evidence stamps for the turn-end verify guard,
     * all fed by ONE run-scoped monotonic clock.
     *
     * WHY A SHARED CLOCK: the guard asks whether "the newest passing
     * verification evidence PREDATES the last edit" — an order between two
     * events of different kinds. The previous shape (two independent
     * counters, one bumped per edit and one per passing verification) cannot
     * answer it: after (edit, edit, verify) the edit counter reads 2 and the
     * verify counter 1, so "verify > edit" is false even though the
     * verification WAS the newest event. Both counters were also write-only
     * (BUG-04), so the guard fired on code edits alone and nudged for
     * verification the run had already done.
     *
     * [evidenceClock] is bumped once per edit/verify event; each stamp
     * records the clock value of the newest event of its kind, so a plain
     * `>` is a correct happens-after test.
     */
    var evidenceClock: Long = 0L

    /** [evidenceClock] value at the newest successful code edit (0 = none). */
    var lastEditStamp: Long = 0L

    /** [evidenceClock] value at the newest PASSING verification (0 = none). */
    var lastVerifyStamp: Long = 0L

    /** How many verify nudges have been injected this run (bounded by
     *  AgentRuntimeLimitsPrefs.verifyNudges(); 0 = guard off). */
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

    /**
     * [deep-audit-1015 BUG-01b] The inline banner this turn owes the user,
     * held until the turn's own assistant row exists.
     *
     * WHY DEFERRED: `setInlineError` persists with
     * `updateLastAssistantError(sessionId, …)`, which targets the session's
     * LAST assistant row (`ChatDao`: ORDER BY sort_order DESC LIMIT 1). Code
     * that runs before this turn persists a row therefore writes the banner
     * onto the PREVIOUS turn's row — where it survives a reload as a
     * permanent mislabel of a turn that actually succeeded. The empty-part
     * case is the sharpest: `persistAssistantTurn` inserts nothing for an
     * empty part list, so a blank turn has no row of this turn to address at
     * all (both a failure-terminal banner and the empty-response hint land on
     * the previous turn). Branches that persist an empty row up front
     * (~2049 / ~2146) surface inline and are unaffected.
     *
     * Contract: a call site sets this INSTEAD of calling setInlineError, and
     * the no-tool-calls tail consumes it through
     * [TerminalErrorPlacement.terminalErrorToSurface] right after the turn's
     * persist returned a row id. Consume-once: the tail clears it even when
     * it declines to surface.
     */
    var pendingInlineError: String? = null

    /**
     * [fix/tool-call-copy-suppress] The turn text as it looked BEFOR a restated
     * tool call was stripped out of it, or null when this turn's visible text
     * carried none. It is the trigger for the refill branch in
     * `toolCalls.isEmpty()`: markup shaped like a call in the text but no
     * parsed call means the model DID try to call a tool and the call never
     * arrived as one (character-level drift on the model / relai side). The old
     * path fell through to "no tool calls → break (stop)", which reads as a
     * clean completion — a SILENT run stop mid-task.
     */
    var toolCallResidueRaw: String? = null

    /** How many resique refills this run has spent, bounded by
     *  [ToolCallResiduePolicy.MAX_RESIDUE_REFILL_NUDGES]. */
    var toolCallResidueNudges: Int = 0

    /** [fix/tool-call-copy-suppress] Accuмulator length at the last resique
     *  scan of the current turn. A shrink means a new turn began, so the scan
     *  cadence needs no explicit reset. */
    var residueScanAt: Int = 0
}

/**
 * [deep-audit-1015 BUG-01a] The run-level exit decisions of
 * `AgentLoopEngine.runAgentLoop`, lifted out of the engine so they are
 * directly JVM-testable.
 *
 * The engine cannot be driven on the JVM (it needs a full host fake), and the
 * previous shape of this logic is exactly why the defect survived: the exit
 * `when` blocks were *copied* into `TerminalErrorSurfacedTest` instead of
 * called, so the test pinned a mirror of the code rather than the code — a
 * mutation in either copy left the other green.
 *
 * Pure: no Android, no coroutines, no state — just the decision table.
 */
internal object AgentLoopExitDecision {

    /**
     * The value `loopState.loopExitedNormally` must take when the loop leaves
     * via the "no tool calls" tail.
     *
     * BUG-01a: that tail set the flag unconditionally, and every
     * failure-terminal branch (error-shaped stub ceiling / deterministic
     * empty / repetition abort / length-wall ceiling / length ×N empty / EOF
     * stub ceiling) deliberately falls through to it — so a run that had just
     * told the user it failed was recorded as `SUCCEEDED` + `COMPLETED` in the
     * T7 state machine and trace. The flag means "the model finished its
     * reply", and a run whose terminal banner is already on screen did not.
     *
     * Note this is NOT the audit-0907 B5 runaway guard: those branches must
     * still not fall into `finalizeAtTurnLimit`'s "Stopped after N turns"
     * banner, and they don't — [shouldFinalizeAsRunaway] reads
     * `terminalErrorSurfaced` independently of this flag.
     */
    fun cleanCompletionFlag(terminalErrorSurfaced: Boolean): Boolean = !terminalErrorSurfaced

    /**
     * Whether the post-loop tail must finalize the run as a MAX_AGENT_TURNS
     * runaway (the `finalizeAtTurnLimit` hand-holding). True only when the
     * range was exhausted without a clean exit, without an already-surfaced
     * terminal error, and without a budget stop.
     */
    fun shouldFinalizeAsRunaway(
        loopExitedNormally: Boolean,
        terminalErrorSurfaced: Boolean,
        budgetStopReason: String?,
    ): Boolean = !loopExitedNormally && !terminalErrorSurfaced && budgetStopReason == null

    /** T7 terminal state for the run. */
    fun terminal(budgetStopReason: String?, loopExitedNormally: Boolean): AgentTerminal = when {
        budgetStopReason == "deadline_reached" -> AgentTerminal.INTERRUPTED
        budgetStopReason != null -> AgentTerminal.FAILED
        loopExitedNormally -> AgentTerminal.SUCCEEDED
        else -> AgentTerminal.FAILED
    }

    /** T7 terminal reason for the run. */
    fun reason(budgetStopReason: String?, loopExitedNormally: Boolean): AgentTerminalReason = when {
        budgetStopReason == "deadline_reached" -> AgentTerminalReason.DEADLINE_EXCEEDED
        budgetStopReason != null -> AgentTerminalReason.EXECUTION_FAILED
        loopExitedNormally -> AgentTerminalReason.COMPLETED
        else -> AgentTerminalReason.EXECUTION_FAILED
    }

    /**
     * The trace's `error` label. `terminalErrorSurfaced` is tested BEFORE the
     * runaway fallback so a typed failure branch is never labelled
     * MAX_AGENT_TURNS (audit-0907 B5).
     */
    fun errorLabel(
        budgetStopReason: String?,
        terminalErrorSurfaced: Boolean,
        loopExitedNormally: Boolean,
    ): String? = when {
        budgetStopReason != null -> "budget_exhausted($budgetStopReason)"
        terminalErrorSurfaced -> "terminal_error_surfaced"
        !loopExitedNormally -> "MAX_AGENT_TURNS"
        else -> null
    }
}

/**
 * [deep-audit-1015 BUG-01b] Where a turn's terminal error banner may be
 * written — lifted out of the engine for the same JVM-testability reason as
 * [AgentLoopExitDecision].
 */
internal object TerminalErrorPlacement {

    /**
     * The parts to persist for a turn that owes the user a terminal banner.
     *
     * `persistAssistantTurn` refuses an empty part list, and an unsaved turn
     * has no row for [ChatDao.updateLastAssistantError] to address — the
     * banner would land on the previous turn. So a turn with a pending banner
     * and nothing to persist still writes the same empty-text row the two
     * existing early-persist branches write (~2044 / ~2141): the row IS the
     * empty assistant bubble the user is looking at.
     */
    fun partsForTerminalError(
        parts: List<AgentContentPart>,
        pendingInlineError: String?,
    ): List<AgentContentPart> =
        if (pendingInlineError != null && parts.isEmpty()) {
            listOf(AgentContentPart.Text(""))
        } else {
            parts
        }

    /**
     * The banner to surface once the turn's row is durable, or null when
     * nothing may be surfaced.
     *
     * [persistedRowId] is what `persistAssistantTurn` returned: null means
     * this turn produced NO row, and surfacing anyway would persist the
     * banner onto the previous turn's row (see the KDoc above). Passing the
     * persist result in is what makes the ordering "persist, then surface"
     * structural instead of a convention the next edit can silently break.
     */
    fun terminalErrorToSurface(pendingInlineError: String?, persistedRowId: String?): String? =
        if (pendingInlineError != null && persistedRowId != null) pendingInlineError else null
}
