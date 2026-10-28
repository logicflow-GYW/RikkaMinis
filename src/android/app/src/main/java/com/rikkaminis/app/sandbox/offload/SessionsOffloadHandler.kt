package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * T188 — `minis-sessions-cli` offload handler. Lets the in-shell agent
 * query historical chat sessions and messages without round-tripping
 * back through the LLM. Three subcommands:
 *
 *   list       List recent sessions, optionally filtered by id, keyword,
 *              and date range. Default if no subcommand is given.
 *   search     Search message content across sessions; --keywords
 *              required.
 *   messages   Read a paginated transcript of one session; --id required.
 *
 * Mirrors iOS canonical CLI surface: argv shape, JSON envelope, exit
 * codes, error messages all line up. The on-device db (Room) is the
 * same shape as iOS (sqlite tables `sessions`/`messages` with matching
 * column names), so query semantics translate one-to-one.
 *
 * Output flags `--compact` / `-q` / `--quiet` are honored uniformly via
 * [OffloadOutput.formatBody], same as every other android-* / minis-*
 * tool.
 */
class SessionsOffloadHandler(
    private val repo: ChatRepository,
    // [T-subagent-toggle] Context is needed to drive headless session
    // creation + prompt via ChatMutationMethods (the same machinery the
    // debug RPC `chat.prompt` uses). Gated by SubagentPrefs — `send` refuses
    // when the user has not enabled the "sub-agent / cross-session dispatch"
    // switch, so the extra injection is inert until then.
    private val context: android.content.Context,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name ("minis-sessions-cli"); subcommand
        // and options follow. Drop argv[0] before parsing so positional[0]
        // is the subcommand name. `full` / `async` / `wait` / `help` are
        // declared boolean so `--flag <token>` never greedily consumes the next
        // token as a value (e.g. `--async send --prompt "hi"` would otherwise
        // swallow "send" as the value of --async and degrade to `list`).
        // [dv19-A1 A-02] `wait` was missing from that list — the same defect one
        // flag over: `minis-sessions-cli --wait send --prompt "hi"` stored
        // "send" as the VALUE of `--wait` (the KDoc below documents `--wait` as
        // the default mode, so it is a spelling callers legitimately write), the
        // positional list came out empty and the command listed sessions with
        // exit 0 — a silent wrong answer instead of a dispatch. `--wait-timeout`
        // stays a value option: declaring IT boolean would eat its `<sec>`.
        val args = OffloadArgs(
            request.argv.drop(1),
            booleanFlags = setOf("full", "async", "wait", "help", "h"),
        )
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: "list"
        // [meta1028-B05 F-12] Refuse an option this subcommand does not
        // implement BEFORE dispatching — the rule the fastio CLI already
        // enforces ([FastioOffloadHandler.unknownOption]): `list --kewords
        // python` parsed as an unknown key, was ignored, and answered EVERY
        // session at exit 0, which reads as "the filter matched everything".
        // A value option spelled without its value (`list --start --end
        // 2026-01-01`) is the same failure one spelling over: OffloadArgs files
        // it as a BOOLEAN flag, the bound then reads as "no filter" and the
        // whole history comes back.
        sessionsOptionProblem(sub, args)?.let { problem ->
            val err = errorEnvelope(sub, "INVALID_ARGS", problem)
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        return try {
            when (sub) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "messages" -> cmdMessages(args)
                "send" -> cmdSend(args)
                else -> {
                    // [da1032-C] ISSUE-08: the list is built from SUBCOMMANDS —
                    // the same table the dispatch above covers — so the message
                    // can no longer fall behind the dispatch (it said "list,
                    // search, messages" while `send` was already reachable).
                    val err = errorEnvelope(
                        sub,
                        "INVALID_ARGS",
                        "Unknown command '$sub'. Valid: ${SUBCOMMANDS.joinToString(", ")}. " +
                            "Use --help for details.",
                    )
                    NativeOffloadResult(
                        EXIT_INVALID_ARGS,
                        OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                    )
                }
            }
        } catch (e: InvertedDateWindow) {
            // [dv15-A1 A-08] Both bounds parsed, but the window is inside out:
            // refused with the SAME envelope + exit 2 as the unreadable-date
            // case below, instead of answering exit 0 with an empty history.
            val err = errorEnvelope(sub, "INVALID_ARGS", e.message ?: "invalid date window")
            NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        } catch (e: InvalidPageSize) {
            // [dv29-A3 A-05] A spelled page size the command cannot honour is a
            // usage error, not a clamp: the clamped answer (1 / 50 / 100) read
            // as a real page and hid the caller's mistake.
            val err = errorEnvelope(sub, "INVALID_ARGS", e.message ?: "invalid page size")
            NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        } catch (e: InvalidDateArg) {
            // [da1032-C] ISSUE-08: a date flag the caller supplied that this
            // command cannot read refuses the command, instead of silently
            // dropping the bound and answering the whole history.
            val err = errorEnvelope(sub, "INVALID_ARGS", e.message ?: "invalid date")
            NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        } catch (e: Throwable) {
            // Last-ditch: any unexpected exception (sql parse error, OOM,
            // …) becomes an INTERNAL envelope rather than a crash so the
            // shell stays alive and the agent can read the failure.
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val err = errorEnvelope(sub, "INTERNAL", e.message ?: "unknown error")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
    }

    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val ids = parseIds(args)
        val kws = parseKeywords(args)
        val (startMs, endMs) = resolveDateBounds(args)
        val limit = parseLimit(args)

        val metas = runBlocking { repo.querySessionsMeta(ids, kws, limit, startMs, endMs) }

        val sessions = JSONArray()
        for (m in metas) {
            val s = JSONObject()
                .put("session_id", m.id)
                .put("started_at", DATE_FMT.format(Instant.ofEpochMilli(m.startedAt)))
                .put("last_active", DATE_FMT.format(Instant.ofEpochMilli(m.lastActive)))
                .put("message_count", m.messageCount)
            // Optional fields — only emit when non-null so the JSON
            // matches iOS's `(optional)` shape rather than carrying
            // null literals through to the agent prompt.
            m.title?.let { if (it.isNotBlank()) s.put("title", it) }
            m.preview?.let { if (it.isNotBlank()) s.put("preview", it) }
            m.source?.let { if (it.isNotBlank()) s.put("source", it) }
            sessions.put(s)
        }
        val data = JSONObject()
            .put("count", sessions.length())
            .put("sessions", sessions)
        return emit("list", data, args)
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val kws = parseKeywords(args).orEmpty()
        if (kws.isEmpty()) {
            val err = errorEnvelope(
                "search",
                "INVALID_ARGS",
                "--keywords is required for search. " +
                    "Example: minis-sessions-cli search --keywords \"API error\"",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        val ids = parseIds(args)
        val (startMs, endMs) = resolveDateBounds(args)
        val limit = parseLimit(args)

        val matches = runBlocking { repo.searchMessages(ids, kws, limit, startMs, endMs) }

        val msgs = JSONArray()
        for (m in matches) {
            msgs.put(
                JSONObject()
                    .put("session_id", m.sessionId)
                    .put("message_id", m.messageId)
                    .put("role", m.role)
                    .put("created_at", DATE_FMT.format(Instant.ofEpochMilli(m.createdAt)))
                    .put("snippet", m.snippet),
            )
        }
        val data = JSONObject()
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("search", data, args)
    }

    private fun cmdMessages(args: OffloadArgs): NativeOffloadResult {
        val sessionId = args.get("id")?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope(
                    "messages",
                    "INVALID_ARGS",
                    "--id <session_id> is required. Use 'list' first to find session IDs.",
                )
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val limit = parseLimit(args)
        val offset = (args.getInt("offset") ?: 0).coerceAtLeast(0)
        // [T-android-sessions-cli-full] --full lifts the per-message text cap
        // from the default 600 to 50000 chars (iOS parity). Previously the flag
        // was silently ignored — every message stayed truncated at 600 no
        // matter what the caller passed, gutting transcript exports.
        val full = args.hasFlag("full")
        val maxChars = if (full) ChatRepository.MESSAGE_TEXT_MAX_FULL else ChatRepository.MESSAGE_TEXT_MAX
        // [T-android-sessions-cli-messages-daterange] GH#200. HELP_TEXT
        // documents --start / --end and `list` / `search` honour them, but
        // `messages` parsed neither — the CLI accepted the flags and silently
        // returned the whole session, which reads as "the filter worked and
        // matched everything".
        val (startMs, endMs) = resolveDateBounds(args)

        val (page, total) = runBlocking {
            // Single coroutine block so the two reads see a consistent
            // snapshot — ordering matters less than ensuring we don't
            // surface a `total` that disagrees with the slice we
            // returned (e.g. another session interleaving inserts mid-
            // call). Room serializes via the suspending dispatcher so
            // these run sequentially in the same coroutine.
            // Both reads take the SAME date range on purpose: an unfiltered
            // count next to a filtered page would make `total` describe the
            // whole session while the slice covers only the matches, so
            // `hasMore` would lie.
            repo.loadMessagePage(sessionId, offset, limit, maxChars, startMs, endMs) to
                repo.messageCountInRange(sessionId, startMs, endMs)
        }

        val msgs = JSONArray()
        for (m in page) {
            val obj = JSONObject()
                .put("message_id", m.messageId)
                .put("role", m.role)
                .put("created_at", DATE_FMT.format(Instant.ofEpochMilli(m.createdAt)))
                .put("text", m.text)
            // Only present when the stored text exceeded the cap, so normal
            // messages serialize byte-identically to before (iOS parity).
            if (m.truncated) obj.put("truncated", true)
            msgs.put(obj)
        }
        val data = JSONObject()
            .put("session_id", sessionId)
            .put("offset", offset)
            .put("limit", limit)
            .put("full", full)
            .put("max_chars", maxChars)
            .put("total", total)
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("messages", data, args)
    }

    /**
     * [T-subagent-toggle] `send` — create/continue a session and prompt it
     * headlessly. This is the production surface for cross-session dispatch
     * (the capability that makes `task-dispatch`'s manual copy-paste step
     * redundant and lets `spawn_agent` graduate to a real independent session).
     *
     * Gated by [com.rikkaminis.app.data.SubagentPrefs] — OFF (default) returns
     * a typed error envelope, so the capability is inert until the user flips
     * the switch. When ON it reuses [com.rikkaminis.app.debug.ChatMutationMethods]
     * (the same `chat.prompt` path as the debug RPC): create a session when no
     * `--session` is given, else continue the existing one; `--wait` (default)
     * blocks for the final assistant text, `--async` returns immediately with
     * the session id so the caller can poll via `messages`.
     */
    private fun cmdSend(args: OffloadArgs): NativeOffloadResult {
        if (!com.rikkaminis.app.data.SubagentPrefs.isEnabled(context)) {
            val err = errorEnvelope(
                "send",
                "SUBAGENT_DISABLED",
                "Cross-session dispatch is disabled. Enable the sub-agent switch " +
                    "in Settings → Agent Runtime to use 'send'.",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
        val prompt = args.get("prompt")
        if (prompt.isNullOrBlank()) {
            val err = errorEnvelope(
                "send",
                "INVALID_ARGS",
                "--prompt <text> is required. Optionally pass --session <id> to " +
                    "continue an existing session (default: create a new one).",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        val sessionId = args.get("session") ?: args.get("id")
        val async = args.hasFlag("async")
        val waitTimeoutSec = (args.getInt("wait-timeout") ?: 600).coerceIn(1, 1800)

        val params = org.json.JSONObject()
            .put("prompt", prompt)
            .put("wait", !async)
            .put("waitTimeout", waitTimeoutSec)
        if (!sessionId.isNullOrBlank()) params.put("sessionId", sessionId)

        return try {
            val result = runBlocking {
                com.rikkaminis.app.debug.ChatMutationMethods.prompt(context, params)
            }
            // [deep-audit-1044 A-04] `ChatMutationMethods.prompt` does NOT throw
            // for a dropped send — it answers with a structured `status`
            // ("Error" + the reason in `responseText`), and for a timed-out wait
            // with `"Timeout"`. The old shape only caught exceptions and then
            // emitted unconditionally, so every one of those settled `ok: true`
            // with exit code 0 while the machine reason was printed in the
            // place an assistant reply belongs — a caller (`minis-sessions
            // send` from a shell, or a scheduler) could not tell a delivered
            // prompt from one that never left the device. Route them through
            // the same `errorEnvelope` funnel the catch branch and every other
            // subcommand use, and keep the whole payload as `detail` so the
            // session id / timeout flag stay available for diagnosis.
            val status = result.optString("status")
            val data = JSONObject()
                .put("session_id", result.optString("sessionId"))
                .put("is_new_session", result.optBoolean("isNewSession"))
                .put("status", status)
                .put("model", result.opt("modelName"))
            if (result.has("responseText") && !result.isNull("responseText")) {
                data.put("response_text", result.optString("responseText"))
            }
            if (result.has("timedOut")) data.put("timed_out", result.optBoolean("timedOut"))
            if (!sendSucceeded(status)) {
                // The reason leaves the envelope as the machine code in
                // `message` — NOT as `response_text`, which is the field a
                // caller reads as "the assistant's answer". The diagnostic
                // fields that are genuinely diagnostic (session id / status /
                // timeout flag / model) stay, under `detail`.
                val detail = JSONObject()
                    .put("session_id", data.optString("session_id"))
                    .put("is_new_session", data.optBoolean("is_new_session"))
                    .put("status", status)
                if (data.has("model")) detail.put("model", data.opt("model"))
                if (data.has("timed_out")) detail.put("timed_out", data.optBoolean("timed_out"))
                val err = errorEnvelope(
                    "send",
                    sendErrorCode(status),
                    sendErrorReason(status, data.optString("response_text")),
                    detail,
                )
                return NativeOffloadResult(
                    sendExitFor(status),
                    OffloadOutput.formatBody(err.toString(2), args) + "\n",
                )
            }
            emit("send", data, args)
        } catch (t: Throwable) {
            val err = errorEnvelope(
                "send",
                "SEND_FAILED",
                t.message ?: t.javaClass.simpleName,
            )
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
    }

    // ─── arg parsing helpers ──────────────────────────────────────────

    private fun parseIds(args: OffloadArgs): List<String>? {
        val raw = args.get("ids") ?: return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseKeywords(args: OffloadArgs): List<String>? {
        val raw = args.get("keywords") ?: return null
        // Split on any whitespace (the user may quote multi-word keywords
        // at the shell, in which case we get one big token containing a
        // space — collapse runs of whitespace into a single delimiter).
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    /**
     * [da1032-C] ISSUE-08: the `--start` / `--end` bounds shared by list,
     * search and messages.
     *
     * An absent (or blank) flag stays null — "no filter", the documented
     * behaviour, and the only reading of "the caller did not ask for a
     * window". A flag the caller DID pass that this command cannot read as a
     * date throws [InvalidDateArg], which [handle] answers with the standard
     * INVALID_ARGS envelope + exit 2. The old shape fed the parse result
     * straight into the query, so `--start 2026-13-40` degraded to "no filter"
     * and the command returned the ENTIRE history — an unreadable argument
     * read as a successful, wider query.
     */
    private fun resolveDateBounds(args: OffloadArgs): Pair<Long?, Long?> {
        val startMs = sessionsDateBound("--start", args.get("start")) { parseSessionsDate(it) }
        val endMs = sessionsDateBound("--end", args.get("end")) { parseEndDate(it) }
        // [dv15-A1 A-08] Both bounds readable but inside out — see
        // [sessionsDateWindow]. Kept in the same funnel as the unreadable-flag
        // refusal so one catch answers both with INVALID_ARGS + exit 2.
        return sessionsDateWindow(startMs, endMs, args.get("start"), args.get("end"))
    }

    /**
     * Inclusive end-of-day: `--end 2025-03-31` includes everything up to
     * and including 2025-03-31 23:59:59.999. Adding (1 day - 1 ms) is
     * safer than (24h - 1ms) across DST transitions on devices that
     * don't run UTC. Mirrors iOS SessionsOffload.m L141-145.
     */
    private fun parseEndDate(raw: String?): Long? {
        val ms = parseSessionsDate(raw) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - 1
    }

    /**
     * [dv29-A3 A-05] The page size, through the shared contract. The old
     * `getInt("limit") ?: DEFAULT_LIMIT` + `coerceIn(1, MAX_LIMIT)` answered
     * `--limit 0` with ONE row, `--limit 1000` with 100 and `--limit abc` with
     * 50 — three different false answers, all at exit 0, all indistinguishable
     * from a real page. Refused instead, with the same INVALID_ARGS envelope +
     * exit 2 the date bounds already use.
     */
    private fun parseLimit(args: OffloadArgs): Int =
        when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.SESSIONS)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid -> throw InvalidPageSize(size.message)
        }

    // ─── envelope helpers ─────────────────────────────────────────────

    private fun emit(action: String, data: JSONObject, args: OffloadArgs): NativeOffloadResult {
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", data)
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(envelope.toString(2), args) + "\n",
        )
    }

    private fun errorEnvelope(
        action: String,
        code: String,
        msg: String,
        detail: JSONObject? = null,
    ): JSONObject {
        val data = JSONObject().put("error", code).put("message", msg)
        if (detail != null) data.put("detail", detail)
        return JSONObject()
            .put("ok", false)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", data)
    }

    companion object {
        private const val TAG = "SessionsOffload"
        private const val TOOL_NAME = "minis-sessions-cli"
        private const val EXIT_FAILURE = 1

        /**
         * [deep-audit-1044 A-04] The `prompt` statuses that mean the send (or
         * its async dispatch) really happened: `"Completed"` for `--wait`,
         * `"Running"` for `--async`. Everything else is a refusal — `"Error"`
         * carries a machine reason in `responseText` (`send_dropped_before_stream`
         * / `no_provider_resolved_in_5s` / `session_not_loaded_in_5s`), and
         * `"Timeout"` means the wait elapsed. Unknown/empty statuses are
         * refusals too: the old code read "no status" as success, which is how
         * the dropped-send defect stayed invisible.
         */
        internal fun sendSucceeded(status: String): Boolean =
            status == "Completed" || status == "Running"

        /**
         * Exit code for a `send` that did not succeed. Split from
         * [sendErrorCode] so the CLI contract (nonzero = the prompt was not
         * delivered) is one decision, and stays testable off-device.
         */
        internal fun sendExitFor(status: String): Int =
            if (sendSucceeded(status)) 0 else EXIT_FAILURE

        /** Envelope `error` code, per the file's other subcommands' vocabulary. */
        internal fun sendErrorCode(status: String): String =
            if (status == "Timeout") "SEND_TIMEOUT" else "SEND_FAILED"

        /**
         * The envelope `message`: the machine reason from `responseText` when
         * there is one (`send_dropped_before_stream` / `no_provider_resolved_in_5s`
         * / `session_not_loaded_in_5s` — the same strings the RPC surface uses),
         * else `"timeout"` for an elapsed wait, else the lowercased status so an
         * unknown refusal still names itself.
         */
        internal fun sendErrorReason(status: String, responseText: String?): String {
            val reason = responseText?.trim()
            if (!reason.isNullOrEmpty()) return reason
            return if (status == "Timeout") "timeout" else status.lowercase()
        }

        // iOS NOFF_EXIT_INVALID_ARGS — the shell convention is exit 2
        // for invalid CLI args, distinct from exit 1 for runtime errors.
        private const val EXIT_INVALID_ARGS = 2

        // [FIX-R16/B26b] Immutable + thread-safe formatter — the same pattern
        // as HangDetector.DATE_FORMAT / CrashFileSender.STAMP_FMT. cmdList /
        // cmdSearch / cmdMessages may format concurrently (any offload handler
        // call can land on a different worker thread), and a shared
        // SimpleDateFormat is not thread-safe — it can emit a garbled stamp
        // exactly when the agent reads the transcript.
        private val DATE_FMT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
                .withZone(java.time.ZoneId.systemDefault())

        /**
         * [da1032-C] ISSUE-08: the dispatchable subcommands, single-sourced.
         * [handle]'s `when` branches and the unknown-command message both key
         * off this list, so a subcommand can no longer be reachable while the
         * message still lists an older, shorter set (`send` was dispatchable
         * while the text said "list, search, messages").
         */
        private val SUBCOMMANDS = listOf("list", "search", "messages", "send")

        private const val HELP_TEXT = """minis-sessions-cli - Query historical chat sessions and messages

USAGE:
  minis-sessions-cli <command> [options]

COMMANDS:
  list      List recent sessions (default: 50, max: 100)
  search    Search message content across sessions (requires --keywords)
  messages  Read messages from a specific session (requires --id)
  send      Create/continue a session and prompt it headlessly
            (requires the sub-agent switch in Settings → Agent Runtime)

OPTIONS:
  --keywords <words>    Space-separated keywords (AND logic, required for search)
  --ids <id1,id2,...>   Filter by comma-separated session IDs (list/search)
  --id <session_id>     Session ID to read messages from (messages)
  --full                (messages only) Return full message text up to 50000 chars
  --offset <n>          Skip first n messages, 0-based (default: 0)
  --start <YYYY-MM-DD>  Filter results after this date (inclusive)
  --end <YYYY-MM-DD>    Filter results before this date (inclusive, end of day)
  --limit <n>           Max results (default: 50, range 1..100; outside that range is
                        a usage error, exit 2)
  --prompt <text>       (send only) The prompt to send
  --session <id>        (send only) Continue an existing session (default: new)
  --async               (send only) Return immediately with the session id
  --wait                (send only) Block until the reply completes (default; --async wins)
  --wait-timeout <sec>  (send only) Max wait for --wait completion (default 600)
  --help, -h            Show this help message
  --compact             Minimize JSON output
  -q, --quiet           Output only data field

OPTIONS ARE PER COMMAND: an option this command does not implement — including a
value option spelled without its value — is a usage error (exit 2), never
silently ignored. 'list --kewords x' answering every session is precisely the
silent wrong answer this rule exists to prevent.

OUTPUT (list):
  Each session includes: session_id, title, preview (first user message,
  60 chars), source, started_at, last_active, message_count.

OUTPUT (search):
  Each message includes: session_id, message_id, role, created_at,
  snippet (up to 600 chars centered on keyword match).

OUTPUT (messages):
  Each message includes: message_id, role, created_at, text (up to 600 chars
  by default; up to 50000 with --full). Messages longer than the cap carry
  "truncated": true. Response also includes: session_id, offset, limit, full,
  max_chars, total (message count under the same --start/--end range as the
  returned slice, so hasMore = offset + count < total is always truthful).

WORKFLOW:
  1. Use 'list' or 'list --keywords <topic>' to find relevant sessions
  2. Use 'search --keywords <terms>' to find specific messages
  3. Use 'messages --id <session_id>' to read a conversation
  4. Use --offset to paginate through long conversations

EXAMPLES:
  minis-sessions-cli list
  minis-sessions-cli list --limit 10
  minis-sessions-cli list --keywords python flask
  minis-sessions-cli list --start 2025-01-01 --end 2025-03-31
  minis-sessions-cli search --keywords "API error" --limit 20
  minis-sessions-cli search --keywords deploy --ids abc123,def456
  minis-sessions-cli messages --id <session_id>
  minis-sessions-cli messages --id <session_id> --full
  minis-sessions-cli messages --id <session_id> --offset 20 --limit 10
"""
    }
}

/**
 * [da1032-C] ISSUE-08: a caller-supplied date flag this command cannot read
 * as a date. [sessionsDateBound] throws it; [SessionsOffloadHandler.handle]
 * answers it with the same INVALID_ARGS envelope + exit 2 that cmdSearch /
 * cmdMessages already use for a missing --keywords / --id.
 */
internal class InvalidDateArg(flag: String, raw: String) :
    Exception("$flag '$raw' is not a YYYY-MM-DD date (e.g. 2026-10-19).")

/**
 * [dv29-A3 A-05] A `--limit` the command cannot honour (not an integer, zero,
 * negative, or above the surface's documented cap).
 *
 * Its own class rather than a reuse of [InvalidDateArg] because the message has
 * to be true — the caller's date was fine, the page size was not. [handle]
 * answers it with the same `INVALID_ARGS` envelope + exit 2 the date refusals
 * use, after [OffloadPageSize.SESSIONS]' range is applied.
 */
internal class InvalidPageSize(message: String) : Exception(message)

/**
 * [dv15-A1 A-08] Both bounds readable, but `--end` lands before `--start`.
 *
 * Its own class rather than a reuse of [InvalidDateArg] because the message has
 * to be true: `InvalidDateArg`'s text says the value "is not a YYYY-MM-DD
 * date", which is FALSE here — both dates parsed fine and only the pair is
 * impossible. An agent told its well-formed date is malformed would keep
 * rewriting the wrong thing. [SessionsOffloadHandler.handle] answers this class
 * with the SAME `INVALID_ARGS` envelope + exit 2 as the unreadable-date case.
 */
internal class InvertedDateWindow(startRaw: String, endRaw: String) :
    Exception(
        "--end '$endRaw' is before --start '$startRaw': the window can match nothing " +
            "(swap the bounds, or widen --start / --end)"
    )

/**
 * [dv15-A1 A-08] The `--start` / `--end` window, refused when it is inside out.
 *
 * `resolveDateBounds` feeds this: two readable bounds, neither of them the
 * problem on its own, but `endMs < startMs` makes the query
 * (`lastActive >= start AND lastActive <= end`) unsatisfiable. The command used
 * to answer exit 0 with `{"count":0,"sessions":[]}`, which the caller reads as
 * "nothing happened in that period" — an impossible window reported as an empty
 * history. Photos ([PhotoRange.Invalid]) and Calendar already refuse it;
 * Sessions was the last one answering success.
 *
 * Strict `<`, deliberately: `--start X --end X` stays legal, because
 * [parseEndDate] aligns the end to that day's 23:59:59.999, so a single-day
 * window keeps `endMs > startMs` and must not become an error.
 */
internal fun sessionsDateWindow(
    startMs: Long?,
    endMs: Long?,
    startRaw: String?,
    endRaw: String?,
): Pair<Long?, Long?> {
    if (startMs != null && endMs != null && endMs < startMs) {
        throw InvertedDateWindow(startRaw.orEmpty(), endRaw.orEmpty())
    }
    return startMs to endMs
}

/**
 * [da1032-C] ISSUE-08: the date parser for the `--start` / `--end` flags.
 * Strict (`isLenient = false`), date-only, local zone — lifted out of the
 * handler so the fail-closed decision below is JVM-testable against the real
 * parser instead of a hand copy.
 */
internal fun parseSessionsDate(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        sdf.isLenient = false
        sdf.parse(raw)?.time
    }.getOrNull()
}

/**
 * [da1032-C] ISSUE-08 decision core for ONE `--start` / `--end` flag:
 *
 *  • null result — the caller did not supply the flag → "no filter", the
 *    documented behaviour;
 *  • the parsed instant — the caller's bound;
 *  • [InvalidDateArg] — the caller DID supply the flag and this command
 *    cannot read it → the whole command is refused.
 *
 * The old shape collapsed the first and third case, so a typo'd `--start`
 * silently widened list/search/messages to the entire history. [parse] is
 * injected so the truth table runs against the real parser in JVM tests.
 */
internal fun sessionsDateBound(flag: String, raw: String?, parse: (String) -> Long?): Long? {
    if (raw.isNullOrBlank()) return null
    return parse(raw) ?: throw InvalidDateArg(flag, raw)
}

