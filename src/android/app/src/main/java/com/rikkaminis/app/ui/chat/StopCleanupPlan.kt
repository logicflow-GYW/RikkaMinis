package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMMessage

// [meta-audit-1028 C2] Pure decision core for `handleUserCancelledCleanup`
// (ChatQueueInterruption.kt) — the "user pressed Stop, no tool is in flight"
// tail. Extracted so the two defects this file fixes are directly
// JVM-testable (same shape as `TurnVisibleContent` / `queueDrainShouldDeferForCompact`),
// instead of only reachable through the ViewModel/runtime.
//
// Defect A (duplicate persistence). The bubble (`ChatMessage`) is ONE assistant
// row per agent run (iOS parity), so both of its text carriers are CROSS-TURN:
//   · `content` = `loopState.accumulatedText + turnText` at every flush site
//     (`updateAssistantMessage(assistantId, accumulatedText + turnSnap, …)`),
//     and `accumulatedText` is the run-scoped accumulator of the whole bubble
//     (AgentLoopState: "Cross-turn accumulated visible text of the current bubble").
//   · `toolBlocks` = `loopState.allToolBlocks`, append-only for the run.
// The pre-fix collection concatenated BOTH, so on round N's Stop it re-wrote
// rounds 1..N-1's text into a fresh assistant row — visible as a duplicated
// reply after reload and as duplicated context on the next request.
//
// Defect B (no arm at all). The old chain was
//   `if (empty && !toolUse && !histAssistant) drop` /
//   `if (text && !histAssistant) persist` /
//   `else if (histAssistant) canResume`
// leaving `text empty && toolUse && !histAssistant` — exactly "round 1 was a
// pure tool_use round, user stopped during round 2's thinking gap" — with no
// arm: the bubble was neither dropped nor resumable, so the Resume banner
// never appeared and the run could not be continued.
//
// The persistence boundary this planner slices on: a tool-call turn's
// assistant row is written BEFORE its tools execute ([fix/early-turn-persist],
// slice = `unpersistedBlockStart .. allToolBlocks.size`) and that turn's text
// block sits immediately BEFORE its `tool_use` blocks (each turn opens a fresh
// text block — `currentTextBlockSb` is declared per turn — and the monolithic
// provider path inserts content-after-tool_calls before the turn's first tool
// block). The tool results then land as a USER row. So at Stop time the text of
// the turn still in flight == every text block AFTER the last `tool_use` block.
// The loop-local, run-scoped `loopState.unpersistedBlockStart` is not reachable
// from the ViewModel; this is its equivalent at the UI mirror.

/**
 * Text of the turn that is still in flight: every non-empty `text` block after
 * the LAST `tool_use` block of the bubble.
 *
 * No `tool_use` block in the bubble → the whole bubble is this one turn (a
 * bubble only keeps looping past a finished turn when a tool ran, or through the
 * no-tool continuation branches, which each end in a terminal persist — that
 * second shape always leaves `agentHistory` ending on ASSISTANT, and
 * [planStopCleanup] then commits nothing rather than re-writing it).
 *
 * Returns "" when the in-flight turn has streamed no text yet.
 */
internal fun unpersistedTurnText(blocks: List<AssistantBlock>): String {
    val boundary = blocks.indexOfLast { it.kind == "tool_use" } + 1
    if (boundary >= blocks.size) return ""
    return buildString {
        for (i in boundary until blocks.size) {
            val b = blocks[i]
            if (b.kind == "text" && b.content.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(b.content)
            }
        }
    }
}

/**
 * What `handleUserCancelledCleanup` should do with the stopped bubble.
 *
 * The three fields are read in this order and are mutually exclusive by
 * construction: [dropPlaceholder] ⇒ nothing else; [persistText] != null ⇒
 * [canResume] is true; otherwise [canResume] alone decides whether the Resume
 * banner is offered.
 */
internal data class StopCleanupPlan(
    /** Case 0: a thinking-only placeholder with no tool activity — remove it. */
    val dropPlaceholder: Boolean,
    /** Non-null: commit this text (+ truncation reminder) as an assistant row. */
    val persistText: String?,
    /** Offer Resume (`_canResume`). True for every bubble that is kept. */
    val canResume: Boolean,
    /**
     * [R5-C C-C-06] Also remove the orphaned `resume()` continuation reminder
     * row from the tail of the transcript (agentHistory + DB). Only ever set
     * together with [dropPlaceholder]: the reminder exists solely as the
     * anchor for a turn that produced nothing.
     */
    val dropContinuationReminder: Boolean = false,
)

/**
 * [R5-C C-C-06] The exact text `resume()` persists as its continuation
 * reminder — a REAL user transcript row (not a system-only message), which is
 * why a stop in the pre-first-packet window leaves it behind.
 *
 * Single-sourced here because three places must agree on it verbatim: the
 * writer (`ChatViewModel.resume`), the plan arm below, and the tail matcher
 * used by the stop cleanup. A drifting copy would silently stop matching and
 * the orphan would survive again.
 */
internal const val CONTINUATION_REMINDER_TEXT =
    "<system-reminder>The user stopped the previous response but now wants to continue. Pick up exactly where you left off.</system-reminder>"

/**
 * [R5-C C-C-06] True when the transcript's tail IS the continuation reminder
 * `resume()` wrote for the turn now being stopped.
 *
 * Shape-only on purpose: an assistant turn committed after the reminder always
 * sets [historyEndsWithAssistant], so reaching the reminder-tail arm implies
 * the run never persisted anything — the reminder is the only row after the
 * last committed turn.
 */
internal fun isContinuationReminderTail(role: LLMMessage.Role?, content: String?): Boolean =
    role == LLMMessage.Role.USER && content == CONTINUATION_REMINDER_TEXT

/**
 * Exhaustive decision for the stopped bubble. Every cell of
 * (`text` empty?, `hasAnyToolUse`, `historyEndsWithAssistant`) lands on exactly
 * one arm — the pre-fix chain had a hole at
 * (empty, true, false), which is defect B:
 *
 * | text | tool | hist=assistant | arm |
 * |------|------|----------------|-----|
 * | ""   | no   | no             | drop placeholder (Case 0) |
 * | ""   | yes  | no             | keep + resume (defect B fix) |
 * | ""   | any  | yes            | keep + resume |
 * | text | any  | no             | commit text + resume |
 * | text | any  | yes            | keep + resume (already committed — a second row would duplicate it) |
 *
 * Idempotency: after the commit arm runs, `agentHistory` ends with ASSISTANT, so
 * a second Stop on the same bubble takes the (text, any, yes) arm and appends
 * nothing — one Stop, one row.
 */
internal fun planStopCleanup(
    blocks: List<AssistantBlock>,
    historyEndsWithAssistant: Boolean,
    historyEndsWithContinuationReminder: Boolean = false,
): StopCleanupPlan {
    val text = unpersistedTurnText(blocks)
    val hasAnyToolUse = blocks.any { it.kind == "tool_use" }
    return when {
        text.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant -> {
            if (historyEndsWithContinuationReminder) {
                // [R5-C C-C-06] resume() persisted its continuation reminder as
                // a real USER row before the loop produced anything, and the
                // user stopped in that window. Dropping only the placeholder
                // left the reminder orphaned at the tail — a USER turn with no
                // answer behind it, re-read as "not resumable" — while
                // `historyEndsWithAssistant` was false SOLELY because of that
                // reminder. Removing it returns the transcript to its exact
                // pre-resume shape (history ends with ASSISTANT again), so the
                // bubble state stays resumable instead of the banner dying.
                StopCleanupPlan(
                    dropPlaceholder = true,
                    persistText = null,
                    canResume = true,
                    dropContinuationReminder = true,
                )
            } else {
                StopCleanupPlan(dropPlaceholder = true, persistText = null, canResume = false)
            }
        }
        text.isNotEmpty() && !historyEndsWithAssistant ->
            StopCleanupPlan(dropPlaceholder = false, persistText = text, canResume = true)
        else ->
            StopCleanupPlan(dropPlaceholder = false, persistText = null, canResume = true)
    }
}
