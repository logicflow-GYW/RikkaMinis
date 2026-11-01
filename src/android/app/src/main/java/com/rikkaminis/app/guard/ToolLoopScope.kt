package com.rikkaminis.app.guard

import com.rikkaminis.app.tools.ToolConcurrencyPolicy
import com.rikkaminis.app.tools.ToolExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which tool calls the loop guard ([ActionGuard] / [LoopGuard] / [OutwardPacing], ported verbatim
 * from OpenMinis-Minis `com.openminis.app.guard`) is allowed to judge, and how a call has to be
 * presented to it so that its UI-semantics thresholds mean the same thing in a shell-semantics
 * agent.
 *
 * The upstream tables assume a UI agent: `ui_read` / `ui_action`, screen fingerprints, taps at
 * coordinates. RikkaMinis drives the same world through `browser_use` (action-granular) and
 * `shell_execute` (mostly ordinary commands, occasionally `android-a11y-cli tap`). Handing every
 * call to [ActionGuard] unchanged would refuse "the 12th `git log`" — a legitimate read-only
 * workflow — so the scope is spelled out here, as two pure functions plus a thin adapter:
 *
 *   1. [decide] — `(toolName, argsJson)` → [Decision]`(managed, read, outward)`. Pure, no state,
 *      directly unit-testable. This is the SPECIFICATION of what the guard may judge:
 *      - `browser_use` — every action is managed; the read actions that [ToolConcurrencyPolicy]
 *        also treats as read-only (`get_text`, `screenshot`, `get_page_info`, `get_readable`,
 *        `get_backbone`, `list_tabs`) feed [Signal.Reread]; every other action (navigate / click /
 *        type / scroll / …) feeds [Signal.Repeat] + [Signal.NoEffect].
 *      - `shell_execute` — managed ONLY when the command starts with a prefix in
 *        [SHELL_GUARDED_PREFIXES] (the three ways a shell command pokes the physical screen).
 *        Every other shell command is untouched: repeating `git log` is not a loop.
 *      - everything else — unmanaged. `file_edit` / `file_write` retries are legitimate.
 *   2. [beforeCall] / [afterCall] / [refusal] / [onUserTurn] — the adapter the agent loop calls.
 *
 * ### How a decision is handed to [ActionGuard] (and why it has to be adapted)
 *
 * [ActionGuard] is kept byte-identical to upstream (only the `package` line differs), so all
 * adaptation happens on the way in:
 *
 *  - **Read-ness is name-keyed upstream** ([ActionGuard.READ_TOOLS] is a set of tool NAMES) while
 *    ours is action-keyed. A managed read is therefore handed over as [GUARD_READ_KEY]
 *    (`"screen_read"`, an entry of that upstream table): it bypasses the Repeat window and feeds
 *    the Reread ladder, which is exactly the upstream contract for a read. Actions keep the real
 *    tool name, so a repeat signature reads `browser_use(action=click,…)`.
 *  - **Pacing is decided here, once.** [OutwardPacing.KEYWORDS] (upstream's implicit table) is not
 *    our specification — [OUTWARD_KEYWORDS] is — so the facade must not pace on its own. Its check
 *    reads the `text` argument, hence the guard copy carries that value under [GUARD_TEXT_KEY]:
 *    the value still enters the repeat signature (two different `type` payloads stay different),
 *    but the facade's own keyword table never fires and a single outward action is never waited on
 *    twice.
 *
 * State is in memory and dies with the process — the accepted semantics of the ported guard.
 * Thresholds, ladders and wordings are upstream's; nothing here re-tunes them.
 */
object ToolLoopScope {

    /**
     * `browser_use` actions that only look at the page. Single source of truth:
     * [ToolConcurrencyPolicy.BROWSER_READ_ACTIONS] — the concurrency policy already has to classify
     * these as pure reads to schedule them in parallel, so keeping a second copy here only invited
     * drift (it did: `get_cookies` was missing, and the guard refused a legitimate repeated read at
     * the Repeat ladder's 12th rung). Add new read actions there, not here.
     */
    val BROWSER_READ_ACTIONS: Set<String> = ToolConcurrencyPolicy.BROWSER_READ_ACTIONS

    /** `browser_use` actions that put words in front of another human/service and therefore get paced. */
    val OUTWARD_ACTIONS: Set<String> = setOf("click", "type")

    /** `shell_execute` is guard-managed only for commands starting with one of these. */
    val SHELL_GUARDED_PREFIXES: List<String> = listOf(
        "android-a11y-cli",
        "android-shizuku-cli",
        "am start",
    )

    /** Keyword table for [Decision.outward] — Chinese and English, Chinese first for readability. */
    val OUTWARD_KEYWORDS: List<String> = listOf(
        "发送", "发布", "提交", "关注", "购买", "支付",
        "send", "post", "publish", "submit", "follow", "buy", "pay",
    )

    /**
     * Args fields whose text is scanned for an outward keyword: what is typed (`text`) and what is
     * clicked at (`selector` — `button:has-text("发送")` is how a button is named in this repo's
     * browser tool).
     */
    val OUTWARD_ARG_KEYS: List<String> = listOf("text", "selector")

    /**
     * Tool name a managed read is presented under: one of [ActionGuard.READ_TOOLS], used purely as
     * the "this call only looks" marker the byte-identical facade understands.
     */
    private const val GUARD_READ_KEY = "screen_read"

    private const val TEXT_ARG = "text"

    /** Key the `text` argument travels under inside the guard copy (see the class KDoc). */
    private const val GUARD_TEXT_ARG = "typedText"

    private const val FALLBACK_SESSION_KEY = "default"

    /** What the guard is allowed to do with one call. Pure output of [decide]. */
    data class Decision(
        /** False = the call never reaches [ActionGuard]; no ladder, no pacing. */
        val managed: Boolean,
        /** True = routed to the Reread signal (bypasses the Repeat window). */
        val read: Boolean,
        /** True = an outward action that gets the 8–20 s (capped 30 s) random pacing. */
        val outward: Boolean,
    ) {
        companion object {
            val Unmanaged = Decision(managed = false, read = false, outward = false)
        }
    }

    // Process-wide pacing history (upstream keys it per session / target app, and so do we).
    private val pacing = OutwardPacing { lo, hi -> ActionGuard.randomSource(lo, hi) }

    // ────────────────────────────── pure scope ──────────────────────────────

    /** [decide] over the raw JSON string the model produced (malformed JSON = unmanaged). */
    fun decide(toolName: String, argsJson: String): Decision = decide(toolName, parseArgs(argsJson))

    /** [decide] over parsed args. Pure: same input, same output, no state touched. */
    fun decide(toolName: String, args: JsonObject): Decision {
        return when (toolName.trim().lowercase()) {
            "browser_use" -> {
                val action = stringArg(args, "action").trim().lowercase()
                Decision(
                    managed = true,
                    read = action in BROWSER_READ_ACTIONS,
                    outward = action in OUTWARD_ACTIONS && OUTWARD_ARG_KEYS.any { key ->
                        OUTWARD_KEYWORDS.any { keyword -> stringArg(args, key).contains(keyword, ignoreCase = true) }
                    },
                )
            }

            "shell_execute" -> {
                val command = stringArg(args, "command").trimStart()
                Decision(
                    managed = SHELL_GUARDED_PREFIXES.any { command.startsWith(it) },
                    read = false,
                    outward = false,
                )
            }

            else -> Decision.Unmanaged
        }
    }

    // ────────────────────────────── adapter ─────────────────────────────────

    /**
     * Pre-call verdict for one tool call. Performs the outward-pacing wait itself (through
     * [ActionGuard.sleeper], i.e. before execution, exactly like upstream) and returns
     * [GuardVerdict.Wait] so the host shows the "waited Ns" notice.
     */
    fun beforeCall(sessionId: String?, toolName: String, argsJson: String): GuardVerdict {
        // [dv1101-review F-05] Short-circuit before parsing: every unmanaged tool (file_write with a
        // large payload, shell_execute with a long command) used to pay a full JSON parse here and
        // again in afterCall for nothing. Tool name decides "managed" without looking at the args.
        if (!isManagedToolName(toolName)) return GuardVerdict.Proceed
        val args = parseArgs(argsJson)
        val decision = decide(toolName, args)
        if (!decision.managed) return GuardVerdict.Proceed
        val verdict = ActionGuard.beforeCall(sessionId, guardToolName(toolName, decision), guardArgs(args).toString())
        if (verdict is GuardVerdict.Refuse) return verdict
        if (!decision.outward) return verdict
        val millis = pacing.waitBefore(pacingKey(sessionId, args), ActionGuard.clock())
        if (millis <= 0L) return verdict
        ActionGuard.sleeper(millis)
        return GuardVerdict.Wait(millis, waitNotice(millis))
    }

    /**
     * Post-call decoration for a call that really ran: prepends the [verdict]'s notice (the model
     * must see "you waited" before the result) and appends whatever ladder rung was reached.
     * A refused call must NOT come through here — upstream's `afterCall` contract.
     */
    fun afterCall(
        sessionId: String?,
        toolName: String,
        argsJson: String,
        result: ToolExecutionResult,
        verdict: GuardVerdict,
    ): ToolExecutionResult {
        // [dv1101-review F-05] Same short-circuit as beforeCall — nothing below
        // can fire for an unmanaged tool, and parsing its args buys nothing.
        if (!isManagedToolName(toolName)) return result
        val args = parseArgs(argsJson)
        val decision = decide(toolName, args)
        val ladder = if (decision.managed) {
            ActionGuard.afterCall(
                sessionId = sessionId,
                tool = guardToolName(toolName, decision),
                argsJson = guardArgs(args).toString(),
                ok = result.success,
                // First version: no screen fingerprint on this platform, so the no-effect and
                // re-read signals stay dormant. The facade interface keeps them for later.
                noEffect = false,
                unchanged = false,
            )
        } else {
            null
        }
        if (decision.managed && decision.outward && result.success) {
            pacing.noteDone(pacingKey(sessionId, args), ActionGuard.clock())
        }
        val notice = when (verdict) {
            is GuardVerdict.Wait -> verdict.notice
            is GuardVerdict.ProceedWithNotice -> verdict.notice
            else -> null
        }
        if (notice == null && ladder == null) return result
        val head = notice?.let { "$it\n\n" }.orEmpty()
        val tail = ladder?.let { "\n\n$it" }.orEmpty()
        return result.copy(output = head + result.output + tail)
    }

    /** The result for a [GuardVerdict.Refuse]: the tool never ran, the model gets the reason back. */
    fun refusal(toolName: String, reason: String): ToolExecutionResult = ToolExecutionResult(
        output = "[loop_guard] $toolName was not run. $reason",
        success = false,
        // [dv1101-review F-05] Every other tool path hands the UI a title; a
        // refusal used to render an untitled failed block.
        toolTitle = toolName,
    )

    /**
     * The user spoke (new turn or steering): the session's ladder state and its session-scoped
     * pacing history are cleared, so the next turn starts from zero.
     */
    fun onUserTurn(sessionId: String?) {
        ActionGuard.onUserTurn(sessionId)
        pacing.reset(sessionKey(sessionId))
    }

    /** Process-wide reset (tests only). */
    fun reset() {
        ActionGuard.resetAll()
        pacing.resetAll()
    }

    // ────────────────────────────── internals ──────────────────────────────

    private fun guardToolName(toolName: String, decision: Decision): String =
        if (decision.read) GUARD_READ_KEY else toolName

    /**
     * Name-only "is this one of the two tools in [decide]'s `when`" — used by [beforeCall] /
     * [afterCall] to skip parsing args for the other ~30 tools. Must stay in sync with that `when`.
     */
    private fun isManagedToolName(toolName: String): Boolean {
        val normalized = toolName.trim().lowercase()
        return normalized == "browser_use" || normalized == "shell_execute"
    }

    /** The guard copy of the args: `text` travels under [GUARD_TEXT_ARG] so the facade never paces. */
    private fun guardArgs(args: JsonObject): JsonObject {
        if (!args.containsKey(TEXT_ARG)) return args
        val renamed = LinkedHashMap<String, JsonElement>(args.size)
        for ((key, value) in args) {
            renamed[if (key == TEXT_ARG) GUARD_TEXT_ARG else key] = value
        }
        return JsonObject(renamed)
    }

    private fun stringArg(args: JsonObject, key: String): String =
        (args[key] as? JsonPrimitive)?.content.orEmpty()

    private fun parseArgs(argsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argsJson) }.getOrNull() as? JsonObject ?: JsonObject(emptyMap())

    private fun sessionKey(sessionId: String?): String =
        sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_SESSION_KEY

    private fun pacingKey(sessionId: String?, args: JsonObject): String =
        OutwardPacing.appKey(args) ?: sessionKey(sessionId)

    private fun waitNotice(millis: Long): String =
        "[Outward pacing] Waited ${millis / 1000}s before this outward action to keep a human-like " +
            "pace toward the target page; the call has now run."
}
