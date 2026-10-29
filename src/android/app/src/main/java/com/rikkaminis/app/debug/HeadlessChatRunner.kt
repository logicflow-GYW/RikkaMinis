package com.rikkaminis.app.debug

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.config.BindingTarget
import com.rikkaminis.app.config.SessionConfigWriteThrough
import com.rikkaminis.app.config.resolveBindingTarget
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.chat.ChatViewModel
import com.rikkaminis.app.ui.chat.ChatViewModelStore
import com.rikkaminis.app.ui.chat.InputAttachment
import com.rikkaminis.app.ui.chat.SessionModelResolution
import com.rikkaminis.app.ui.chat.addAttachment
import com.rikkaminis.app.ui.chat.applyDefaultModelFallback
import com.rikkaminis.app.ui.chat.decideSessionModelResolution
import com.rikkaminis.app.ui.chat.restoreFromBinding
import com.rikkaminis.app.ui.chat.selectEntry
import com.rikkaminis.app.ui.chat.setThinkingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Headless wrapper around [ChatViewModel] for the debug RPC layer.
 *
 * Issues:
 *  - [ChatViewModel] is bound to a [ViewModelStore] (so `viewModelScope` works
 *    and the streaming Job survives across collectors). The UI binds it to a
 *    NavBackStackEntry; we bind it to a process-scoped store so RPC-driven
 *    sessions survive across calls.
 *  - The runner caches one VM per sessionId so a follow-up `chat.prompt` on
 *    the same id reuses the same `viewModelScope` (no double-starts).
 *
 * Concurrency: [ChatViewModel.sendMessage] auto-enqueues if `_isStreaming` is
 * true, but we still serialize per-session via the cache: a second `wait=true`
 * on the same session can collect the same `isStreaming` Flow.
 */
internal object HeadlessChatRunner {

    private const val TAG = "HeadlessChatRunner"

    /**
     * sessionId → ViewModelProvider that owns its single ChatViewModel.
     * T9-L3: ConcurrentHashMap — writes go through @Synchronized helpers, but
     * [cancel] reads it from the Main dispatcher while other DebugServer
     * connections (each its own coroutine) can be inserting or removing
     * entries; a plain HashMap read could race a structural change.
     */
    private val providers = java.util.concurrent.ConcurrentHashMap<String, ViewModelProvider>()

    private fun app(context: Context): MinisApp =
        context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")

    @Synchronized
    private fun providerFor(context: Context, sessionId: String): ViewModelProvider {
        val cached = providers[sessionId]
        if (cached != null) return cached
        val app = app(context)
        // Share the process-wide ChatViewModelStore so the in-flight VM (with
        // its live streamJob + _isStreaming) is the same instance the UI's
        // ChatScreen will bind to when the user opens this session. Using a
        // private ViewModelStore here split headless and UI into two VMs, so
        // "run now" started streaming on the headless VM while the UI's VM
        // saw only a static snapshot — no thinking indicator, no live text.
        val owner = ChatViewModelStore.ownerFor(sessionId)
        val provider = ViewModelProvider(
            owner,
            ChatViewModel.factory(
                sessionId = sessionId,
                chatRepository = app.chatRepository,
                providerRepository = app.providerRepository,
                appContext = app.applicationContext,
                memoryRepository = app.memoryRepository,
                skillRepository = app.skillRepository,
                mcpRepository = app.mcpRepository,
            ),
        )
        providers[sessionId] = provider
        return provider
    }

    private fun viewModel(context: Context, sessionId: String): ChatViewModel =
        providerFor(context, sessionId)[ChatViewModel::class.java]

    /**
     * [dv29-A2] Fail-closed SESSION-EXISTENCE gate for every entry that can
     * reach [com.rikkaminis.app.data.repository.ChatRepository.appendMessage].
     *
     * `messages.session_id` is a foreign key into `chat_sessions` (MessageEntity,
     * onDelete=CASCADE), so a send into an id with no row raises
     * `FOREIGN KEY constraint failed (code 787)`: the user row never lands, and
     * `nextSortOrder` answers 0 for a session that does not exist. Before
     * [ChatRepository.appendMessage] learned to tell the two constraints apart
     * the replay repeated the identical insert and the second throw escaped the
     * catch — an uncaught coroutine exception that ACRA turned into a process
     * kill (2026-10-24 12:57 ×2, `minis-sessions-cli send --session
     * <missing id>`). The classification fix stops the replay; this gate is the
     * other half, the one that answers the caller: a missing session is a
     * structured `-32602`, not silence followed by a crash.
     *
     * Call it BEFORE building the ViewModel: `viewModel(…)` materialises a VM
     * for any id, and a resident VM is one of the two things that legitimately
     * stands in for a row (a `__new__…` draft that the send path is about to
     * promote).
     */
    internal suspend fun requireSendableSession(app: MinisApp, sessionId: String) {
        val persisted = withContext(Dispatchers.IO) {
            app.chatRepository.dao.getSession(sessionId)
        } != null
        if (persisted) return
        val resident = withContext(Dispatchers.Main) {
            ChatViewModelStore.peekViewModel(sessionId)
        }
        if (resident != null) return
        AppLogger.warning(
            TAG,
            "requireSendableSession: no row and no resident VM for '$sessionId' — " +
                "refusing the send instead of appending into a missing parent",
        )
        throw RPCException(-32602, "session_not_found: $sessionId")
    }

    /**
     * [dv29-A2] The first member of [members] the runtime would actually use,
     * or null when there is none.
     *
     * Same single decision as the session-model gate
     * ([decideSessionModelResolution]) — the exact judgement
     * `ConfigBuiltins.firstUsableGroupMember` applies to
     * `session.primaryModel`, kept call-site-visible here (this file cannot
     * import that private helper) so a group binding write can never pick a
     * DISABLED or KEYLESS head member, nor report a group whose every member is
     * unusable as applied.
     */
    internal fun firstUsableGroupEntry(
        members: List<ModelEntry>,
        instanceOf: (String) -> ProviderInstance?,
        apiKeyOf: (String) -> String?,
    ): ModelEntry? = members.firstOrNull { member ->
        val instance = instanceOf(member.providerInstanceId)
        decideSessionModelResolution(
            member,
            instance,
            instance?.let { apiKeyOf(it.id) },
        ) is SessionModelResolution.Use
    }

    /**
     * [dv29-A2] Why a model binding was refused, as a stable token for the RPC
     * error text — null when the decision says the target IS usable.
     *
     * The reason names come from the shared enum, so the RPC surface cannot
     * drift from the runtime gate's vocabulary.
     */
    internal fun bindingRejectReason(decision: SessionModelResolution): String? =
        (decision as? SessionModelResolution.Unusable)?.reason?.name

    /**
     * Ensure a session exists in the DB before binding a ViewModel. Mirrors
     * the in-app flow that creates a draft session lazily; for RPC-driven
     * automation we materialize it eagerly so subsequent reads can resolve
     * the id.
     */
    suspend fun ensureSession(context: Context, modelId: String? = null): String =
        withContext(Dispatchers.IO) {
            val app = app(context)
            val resolvedModel = modelId
                ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                ?: "unknown"
            // [fix/headless-thinking-default] A headless session used to be
            // created with thinkingOverride = null → the fresh VM's default
            // (OFF), silently downgrading the level the user configured for
            // the group they dispatch with (this install: "max" → OFF). The
            // in-app path folds the bound group's defaultThinkingLevel into
            // the row at insert time (T312 applyGroupSessionDefaults +
            // createSession(thinkingLevel = …)); do the same here so a
            // dispatched session reasons at the user's configured default.
            val defaultThinking = app.providerRepository.defaultPrimaryGroupId
                ?.let { app.providerRepository.group(it) }
                ?.defaultThinkingLevel
            val s = app.chatRepository.createSession(
                modelId = resolvedModel,
                title = null,
                thinkingLevel = defaultThinking?.name,
            )
            // Mark source so the UI session list shows it came from RPC.
            app.chatRepository.dao.updateSource(s.id, "debug")
            s.id
        }

    /**
     * Apply a model-entry / model-group override to a session before send.
     * - `modelEntryId`: bind to a specific entry (`binding=entry`).
     * - `modelGroupId`: bind to a group (`binding=group`); the picker uses
     *   the group's routing strategy at chat time.
     * - neither: leave an ALREADY bound session alone; only a session with no
     *   binding of its own falls back to the default primary group.
     * Returns the human-friendly `modelName` for the response.
     */
    suspend fun applyModelOverride(
        context: Context,
        sessionId: String,
        modelEntryId: String?,
        modelGroupId: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (modelEntryId != null && modelGroupId != null) {
            throw RPCException(-32602, "modelEntryId and modelGroupId are mutually exclusive")
        }
        val app = app(context)
        // [dv29-A2] Structured rejection for a session that does not exist —
        // see [requireSendableSession]. Called before the existing-binding read
        // below, because that read answers "null" for a missing row and the
        // branch then happily writes a binding nobody can restore.
        requireSendableSession(app, sessionId)
        val cfg = app.providerRepository.config.value
        // [dv25-A05] The default primary group is a NEW-SESSION default, not an
        // override. It used to be applied unconditionally when both parameters
        // were null — which is exactly what `chat.prompt` / `chat.retry` pass
        // when the caller did not ask for a model — so every follow-up prompt on
        // a session the user had pinned to a non-default model silently rebound
        // it to the default group (and, with the refresh half missing, left the
        // screen showing the old model while the row said otherwise).
        //
        // The answer has to come from the row, not from a caller flag: a caller
        // cannot tell "session created by the previous RPC call" from "session
        // the user pinned last week". `resolveBindingTarget` is the same pure
        // decision the tests pin, and the row read is its argument — inlined on
        // purpose, so the value that is consulted is visibly the one that is
        // used (no local that can be dropped later).
        val target = resolveBindingTarget(
            requestedEntryId = modelEntryId,
            requestedGroupId = modelGroupId,
            existingBinding = app.chatRepository.dao.getSession(sessionId)?.modelBinding,
            defaultGroupId = cfg.defaultPrimaryGroupId?.takeIf { gid ->
                cfg.modelGroups.any { it.id == gid }
            },
        )
        when (target) {
            // Nothing to write: keep the session's own binding (or, with no
            // request and no default group configured, keep whatever
            // `ensureSession` seeded).
            BindingTarget.Preserve, BindingTarget.None -> return@withContext null
            is BindingTarget.Entry -> {
                val entry = cfg.modelEntries.firstOrNull { it.id == target.entryId }
                    ?: throw RPCException(-32602, "Entry not found: ${target.entryId}")
                // [dv29-A2] Usability, not just existence. `isEnabled` alone
                // let a key-less instance through: the row was written, the
                // resident VM refused to restore it (restoreFromBinding runs
                // the same decision) and fell back to the default model, while
                // this function returned the refused model's name — the RPC
                // said "used X" for a turn that ran on something else. Same
                // single decision `session.primaryModel` already uses.
                val instance = app.providerRepository.instance(entry.providerInstanceId)
                val decision = decideSessionModelResolution(
                    entry,
                    instance,
                    instance?.let { app.providerRepository.loadApiKey(it.id) },
                )
                if (decision !is SessionModelResolution.Use) {
                    val reason = bindingRejectReason(decision) ?: "UNUSABLE"
                    throw RPCException(
                        -32602,
                        "entry:${entry.id} is not usable — $reason",
                    )
                }
                // The binding column stores a JSON object the VM parses in
                // restoreFromBinding (ChatViewModel: {"type":"entry","entryId":…}).
                // Writing the bare literal "entry" left restoreFromBinding unable to
                // resolve the entry, so activeEntryId never flipped non-null, the
                // headless provider-resolve wait timed out, and sendMessage
                // early-returned with no LLM request — RPC-driven sessions produced
                // a lone user message and no assistant turn.
                val binding = """{"type":"entry","entryId":"${entry.id}"}"""
                persistBinding(app, sessionId, binding, entry.baseModel.id)
                return@withContext entry.model.displayName
            }
            is BindingTarget.Group -> {
                val group = cfg.modelGroups.firstOrNull { it.id == target.groupId }
                    ?: throw RPCException(-32602, "Group not found: ${target.groupId}")
                // [dv29-A2] A group is usable only when at least one member is,
                // and `model_id` must name the member the runtime would pick —
                // not `memberEntryIds.first()`, which could itself be the
                // disabled / keyless one (that wrote a `model_id` no member
                // matches while the VM silently fell back).
                val members = group.memberEntryIds.mapNotNull { mid ->
                    cfg.modelEntries.firstOrNull { it.id == mid }
                }
                if (members.isEmpty()) {
                    throw RPCException(
                        -32602,
                        "Group ${group.id} references no existing model entry — " +
                            "every member id in ${group.memberEntryIds} is gone",
                    )
                }
                val usableMember = firstUsableGroupEntry(
                    members = members,
                    instanceOf = { iid -> app.providerRepository.instance(iid) },
                    apiKeyOf = { iid -> app.providerRepository.loadApiKey(iid) },
                ) ?: throw RPCException(
                    -32602,
                    "Group ${group.id} has no usable member — every member's " +
                        "provider instance is disabled or has no API key",
                )
                val resolvedModelId = usableMember.baseModel.id
                // Same JSON-object shape the VM expects for a group binding.
                val groupBinding = """{"type":"group","groupId":"${group.id}"}"""
                persistBinding(app, sessionId, groupBinding, resolvedModelId)
                return@withContext group.name
            }
        }
    }

    /**
     * [dv25-A05] Write a session binding to BOTH surfaces, in the order 1044-A05
     * established for `minis-config session.primaryModel`: the Room row first
     * (the only cross-restart store, and the write must have landed before it
     * reports success), then the resident ViewModel.
     *
     * The refresh goes through [ChatViewModel.restoreFromBinding] — the SAME
     * entry `loadSession` uses — so honours the provider-enabled / credential
     * gates, with the same `applyDefaultModelFallback` second priority. Without
     * it the row said one model while the live chat kept the previous one, and
     * the headless `activeEntryId` wait could return on the OLD binding.
     */
    private fun persistBinding(
        app: MinisApp,
        sessionId: String,
        bindingJson: String,
        modelId: String,
    ) {
        // `peekViewModel` walks the ViewModelStore, so it is main-thread-only and
        // must not create a VM for a chat that is not on screen.
        val vm = runBlocking { withContext(Dispatchers.Main) { ChatViewModelStore.peekViewModel(sessionId) } }
        SessionConfigWriteThrough.deliver(
            vm = vm,
            persist = { app.chatRepository.updateSessionBinding(sessionId, bindingJson, modelId) },
            hasPersistedRow = { app.chatRepository.dao.getSession(sessionId) != null },
            refresh = { resident -> applyBindingToResident(resident, bindingJson, sessionId) },
            onDraftWithoutRow = {
                AppLogger.warning(
                    TAG,
                    "applyModelOverride: session '$sessionId' has no persisted row and no " +
                        "resident view model — the binding write matched 0 rows",
                )
            },
        )
    }

    /**
     * [dv25-A05] Push [bindingJson] into the resident view model.
     *
     * The VM-side entry points are main-thread-only; [SessionConfigWriteThrough
     * .deliver] is synchronous (called from the RPC's IO dispatcher), hence the
     * blocking hop. A throw here must not turn a landed row write into an RPC
     * failure — the row is authoritative and the UI is one load behind — so it
     * is logged, not rethrown.
     */
    private fun applyBindingToResident(
        vm: ChatViewModel,
        bindingJson: String,
        sessionId: String,
    ) {
        runBlocking {
            withContext(Dispatchers.Main) {
                runCatching {
                    if (!vm.restoreFromBinding(bindingJson)) vm.applyDefaultModelFallback()
                }.onFailure {
                    AppLogger.warning(
                        TAG,
                        "applyModelOverride: resident view model refresh failed for " +
                            "'$sessionId': ${it.message}",
                    )
                }
            }
        }
    }

    /**
     * Send a prompt into [sessionId]. When `wait` is true, suspend until
     * `isStreaming` flips back to false (or `timeoutMs` elapses) and return
     * the concatenated text of the last assistant message; otherwise return
     * immediately with `responseText=null`.
     */
    suspend fun prompt(
        context: Context,
        sessionId: String,
        text: String,
        attachments: List<InputAttachment> = emptyList(),
        thinkingLevel: ThinkingLevel? = null,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = withContext(Dispatchers.Main) {
        val app = app(context)
        // [dv29-A2] Before `viewModel(…)` materialises a VM for any id: a send
        // into a session that does not exist appends into a missing FK parent
        // (that is the 2026-10-24 process kill). Structured refusal instead.
        requireSendableSession(app, sessionId)
        val vm = viewModel(context, sessionId)
        // Apply per-call thinking override BEFORE sendMessage so streamMessage
        // picks up the new level. Caller passes null to keep the VM's existing
        // setting (default OFF for a fresh VM, last user-set value otherwise).
        //
        // ChatViewModel.setThinkingLevel silently no-ops when the active model
        // hasn't resolved yet (currentModelSupportsReasoning checks
        // `currentModel?.supportsReasoning == true`, and `currentModel` is
        // populated on a viewModelScope coroutine). For a freshly-created VM
        // bound to a new session, the resolver may not have run by the time we
        // reach this line — wait until activeEntryId flips non-null so the
        // override actually applies.
        if (thinkingLevel != null) {
            // Wait off Main: ChatViewModel's init coroutine runs on viewModelScope
            // (Main.immediate), and Flow.first() suspends the calling dispatcher
            // until the value arrives. If we wait on Main here we deadlock — the
            // VM's loadSession() never gets to populate _activeEntryId. Hop to
            // Default for the wait, then come back to Main for setThinkingLevel.
            // setThinkingLevel itself silently no-ops on a model that doesn't
            // support reasoning — that gating is intentional UI parity, the
            // 3 s ceiling here just keeps us from blocking forever if the VM
            // never resolves a model (no provider configured, etc.).
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3000L) {
                    vm.activeEntryId.first { it != null }
                }
            }
            vm.setThinkingLevel(thinkingLevel)
        }
        // Wait for ChatViewModel.currentProvider to resolve before sendMessage.
        // The VM populates currentProvider on a viewModelScope (Main.immediate)
        // coroutine driven by providerRepository.config + the session's bound
        // entry. activeEntryId flips non-null in the SAME block that assigns
        // currentProvider (see ChatViewModel.kt ~L2030/2230/2265/2558), so we
        // use it as the readiness signal. Without this wait, sendMessage hits
        // its `currentProvider == null` early-return and the RPC returns
        // status:Completed responseText:null — the bug that blocked Step 2 e2e.
        //
        // Hop off Main for the wait: the VM's resolver runs on Main.immediate,
        // so suspending Main here would deadlock the resolver (same pattern as
        // the thinkingLevel wait above).
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
            )
        }
        // [fix/send-dispatch-race] Await the _sessionLoaded latch (set in
        // ChatSessionLifecycle.loadSession's finally) BEFORE appending the
        // dispatch instruction. Pre-fix we only awaited activeEntryId, which
        // flips mid-loadSession — a dispatch that beat the agentHistory
        // rebuild had its just-appended user message WIPED by the rebuild's
        // clear()+addAll (snapshotted from a DB read taken BEFORE this
        // append committed), and the run started with historySize=0: the
        // agent only did the startup ritual, never saw the task (backlog
        // §76, user-reported 3×, 6-dispatch burst lost 1). Awaiting the
        // latch structurally orders append AFTER the DB read. Draft
        // sessions early-return in loadSession with the latch already set,
        // so this never blocks them. Timeout is non-fatal (proceed) — the
        // pre-fix behavior is the floor, not a new failure mode.
        val sessionReady = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.sessionLoaded.first { it }
            }
        }
        if (sessionReady == null) {
            android.util.Log.w(
                "HeadlessChatRunner",
                "sessionLoaded not observed in 5s for $sessionId — proceeding with dispatch (pre-fix behavior)",
            )
        }
        // [deep-audit-1048 A-07] Identity of "before this turn". `isStreaming`
        // flipping back to false below says "no stream is running", NOT "this
        // turn wrote an assistant message": a turn that ends without one
        // (provider error / cancel / empty completion) left the drain scanning
        // for the LAST assistant row in the whole transcript and reporting the
        // PREVIOUS turn as `Completed`. Snapshot the rows that already exist
        // BEFORE the send goes out; the drain accepts only a row that appeared
        // after that snapshot. Set-of-ids, not a position: it must survive the
        // transcript truncation retry/rerun perform.
        val priorAnswerIds = turnAnswerSnapshot(app, sessionId)
        for (att in attachments) vm.addAttachment(att)
        vm.sendMessage(text)
        // [da1016 B-7b] `sendMessage` claims `_isStreaming` SYNCHRONOUSLY and
        // only after every pre-flight gate (the "send _isStreaming=true (sync)"
        // site in ChatViewModel.sendMessage — minted AFTER the safe-mode /
        // no-provider / compacting / context-exhausted / blank-input gates); a
        // send that is dropped returns *before* that line, so nothing will
        // ever stream and no settle signal will ever arrive. Reading the flag
        // here (same Main dispatcher, straight after the call) therefore
        // answers "did a run actually start?".
        //
        // Pre-fix the `!wait` branch returned "Running" unconditionally: the
        // scheduler's fire() then held its in-process single-flight lock until
        // reapAbandonedDispatch's deadline+grace expired (30 min for a default
        // task), blocking every other task in the meantime.
        //
        // Residual window: a drop that happens INSIDE the launched coroutine
        // (awaitAutoCompactIfNeeded, ensureSession failure) still reports
        // "Running" — reapAbandonedDispatch is its backstop. A run that starts
        // *and* finishes before we read the flag cannot happen: the streaming
        // Job is created by an IO coroutine, which only runs after this
        // function returns to its suspension point.
        val started = vm.isStreaming.value
        if (!wait) {
            return@withContext if (started) {
                PromptResult(status = "Running", responseText = null, timedOut = false)
            } else {
                // Status strings are a contract: ScheduledTaskRunner settles
                // any status != "Running" as RESULT_ERROR (and no longer sits
                // on the lock), while the offload/CLI and debug-RPC layers pass
                // the string through verbatim. Reuse the existing "Error"
                // value — plus a reason in responseText, the same shape as
                // "no_provider_resolved_in_5s" — instead of minting a new word.
                // [deep-audit-1044 A-03] ONE envelope for every dropped send:
                // this branch and the `wait=true` guard below both return it.
                droppedSendResult()
            }
        }

        // [deep-audit-1044 A-03] `started == false` here means the send was
        // dropped by one of the pre-flight gates (Safe Mode / no provider /
        // compacting / context exhausted / blank input): `sendMessage` claims
        // `_isStreaming` synchronously, AFTER them, so nothing will ever
        // stream. Falling through used to "settle" immediately (`isStreaming`
        // is already false, so `withTimeoutOrNull` returns at once) and the
        // drain below then read the PREVIOUS assistant turn out of the DB and
        // reported it as `Completed` — the caller (chat.prompt RPC /
        // `minis-sessions send`) accepted a stale reply as this request's
        // answer. A run that starts and finishes before we read the flag
        // cannot happen: the streaming Job is created by an IO coroutine that
        // only runs after we return to the suspension point.
        //
        // Same envelope as the `!wait` branch above and as retry() /
        // rerunFromToolBlock() — status strings are a contract
        // (ScheduledTaskRunner settles any status != "Running" as RESULT_ERROR,
        // the offload/CLI layer passes responseText through as the reason).
        if (!started) {
            return@withContext droppedSendResult()
        }
        val finished = withTimeoutOrNull(timeoutMs) {
            // Skip the initial false (if we were called before sendMessage flipped true)
            if (vm.isStreaming.value) {
                // Wait for the next false transition.
                vm.isStreaming.first { !it }
            }
            true
        } ?: false

        // Best-effort: read this turn's assistant text from the DB so we don't
        // depend on the in-memory UI list (which may not have flushed yet).
        // [deep-audit-1048 A-07] `answerIdx` is -1 when the turn wrote no
        // assistant row of its own — settleTurn turns that into a structured
        // error instead of replaying an earlier turn.
        val msgs = app.chatRepository.loadMessages(sessionId)
        val answerIdx = turnAnswerIndex(msgs.map { TurnAnswerRow(it.id, it.role) }, priorAnswerIds)
        val answer = if (answerIdx >= 0) msgs[answerIdx] else null
        val settled = settleTurn(finished, answer != null)
        PromptResult(
            status = settled.status,
            responseText = settled.reason ?: answer?.let { extractText(it.partsJson) },
            timedOut = !finished,
        )
    }

    suspend fun retry(
        context: Context,
        sessionId: String,
        messageId: String?,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = withContext(Dispatchers.Main) {
        val app = app(context)
        // [dv29-A2] Same gate as prompt(): the retry path re-appends rows.
        requireSendableSession(app, sessionId)
        val vm = viewModel(context, sessionId)
        val targetMsgId = messageId ?: run {
            val msgs = app.chatRepository.loadMessages(sessionId)
            msgs.lastOrNull { it.role == "user" }?.id
                ?: throw RPCException(-32602, "Session has no user messages")
        }
        // Validate it points at a user message.
        val all = app.chatRepository.loadMessages(sessionId)
        val target = all.firstOrNull { it.id == targetMsgId }
            ?: throw RPCException(-32602, "Message not found in session")
        if (target.role != "user") throw RPCException(-32602, "Target is not a user message")
        val deletedCount = all.size - all.indexOf(target) - 1

        // Same readiness gate as prompt() — retryFromMessage hits the same
        // currentProvider-null early-return if invoked before resolve.
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
                deletedMessageCount = deletedCount,
                retriedMessageId = targetMsgId,
            )
        }
        // [deep-audit-1042 A-05] loadSession is TWO steps: `_activeEntryId`
        // flips at step 1 (ChatSessionLifecycle:423), the message list is read
        // at step 2 (:477) and `_sessionLoaded` only in its `finally` (:786).
        // The readiness wait above is therefore satisfied while `_messages` is
        // still empty — and `retryFromMessage` resolves its target with
        // `_messages.value.indexOfFirst { it.id == messageId }`, returning
        // silently on a miss (ChatViewModel:3199). Invoked inside that window
        // it truncated nothing and started nothing, while the `wait` branch
        // below read "not streaming" and settled `Completed` with the previous
        // assistant text: a fake success for a retry that never ran.
        // Await the latch first — the same shape as prompt()'s dispatch
        // barrier: OFF Main (the load resumes on Main.immediate, so waiting on
        // Main would deadlock it) and bounded (a timeout is not thrown; the
        // branch below turns it into a structured error instead of guessing).
        val sessionReady = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.sessionLoaded.first { it } }
        }
        if (sessionReady == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "session_not_loaded_in_5s",
                timedOut = false,
                deletedMessageCount = deletedCount,
                retriedMessageId = targetMsgId,
            )
        }
        // [deep-audit-1048 A-07] Same turn identity as prompt() — and the
        // reason the snapshot is a SET of row ids rather than a position: this
        // entry truncates `deletedCount` rows before re-running, so a boundary
        // index (or "not the previous answer's id") would drift with the turn.
        val priorAnswerIds = turnAnswerSnapshot(app, sessionId)
        vm.retryFromMessage(targetMsgId)
        // `retryFromMessage` claims `_isStreaming` SYNCHRONOUSLY (T145) after
        // every drop gate (already streaming / not a retryable user message /
        // no provider / target missing from `_messages`), so reading the flag
        // here — same Main dispatcher, straight after the call — answers "did
        // a retry actually start?". A dropped retry used to fall through to
        // the wait branch, which saw `isStreaming == false` and reported
        // `Completed` + the stale assistant text. Same envelope family as
        // prompt()'s "send_dropped_before_stream".
        val started = vm.isStreaming.value
        if (!wait) {
            return@withContext if (started) {
                PromptResult(
                    status = "Retrying",
                    responseText = null,
                    timedOut = false,
                    deletedMessageCount = deletedCount,
                    retriedMessageId = targetMsgId,
                )
            } else {
                PromptResult(
                    status = "Error",
                    responseText = "retry_dropped_before_stream",
                    timedOut = false,
                    deletedMessageCount = deletedCount,
                    retriedMessageId = targetMsgId,
                )
            }
        }
        if (!started) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "retry_dropped_before_stream",
                timedOut = false,
                deletedMessageCount = deletedCount,
                retriedMessageId = targetMsgId,
            )
        }
        val finished = withTimeoutOrNull(timeoutMs) {
            if (vm.isStreaming.value) {
                vm.isStreaming.first { !it }
            }
            true
        } ?: false
        val msgs = app.chatRepository.loadMessages(sessionId)
        val answerIdx = turnAnswerIndex(msgs.map { TurnAnswerRow(it.id, it.role) }, priorAnswerIds)
        val answer = if (answerIdx >= 0) msgs[answerIdx] else null
        val settled = settleTurn(finished, answer != null)
        PromptResult(
            status = settled.status,
            responseText = settled.reason ?: answer?.let { extractText(it.partsJson) },
            timedOut = !finished,
            deletedMessageCount = deletedCount,
            retriedMessageId = targetMsgId,
        )
    }

    /**
     * [T-android-rerun-from-tool-block-position] Drive
     * [ChatViewModel.rerunFromToolBlock] headlessly so automation / e2e can
     * exercise the block-boundary re-run (the in-app trigger is a tool-bubble
     * long-press). [assistantMessageId] is the UI assistant bubble id and
     * [blockId] is the tool block's id (== its tool_use id). Returns the same
     * [PromptResult] shape as [retry]; `responseText` is the latest assistant
     * text after the re-run settles (when [wait]).
     */
    suspend fun rerunFromToolBlock(
        context: Context,
        sessionId: String,
        assistantMessageId: String,
        blockId: String,
        wait: Boolean,
        timeoutMs: Long,
    ): PromptResult = withContext(Dispatchers.Main) {
        val app = app(context)
        // [dv29-A2] Same gate as prompt(): rerun deletes and re-appends rows.
        requireSendableSession(app, sessionId)
        val vm = viewModel(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.activeEntryId.first { it != null } }
        }
        if (ready == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "no_provider_resolved_in_5s",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        val before = app.chatRepository.messageCount(sessionId)
        // [deep-audit-1042 A-05] The same two-step load window as retry():
        // `rerunFromToolBlock` resolves the assistant bubble and its tool block
        // from `_messages.value` and returns false on a miss — but its
        // degenerate branch (no preceding content in the turn) delegates to
        // `retryFromMessage` and returns TRUE without claiming `_isStreaming`.
        // Both are the wrong answer before the message list is loaded: the
        // caller then waited on a stream that never started. Await the latch
        // off Main / bounded, exactly like prompt() and retry().
        val sessionReady = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.sessionLoaded.first { it } }
        }
        if (sessionReady == null) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "session_not_loaded_in_5s",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        // The in-memory assistant bubble id is a volatile `assistant_<ts>`
        // runtime id, not the DB row id a harness reads from chat.messages.list.
        // Resolve the live bubble that owns this tool block; fall back to the
        // caller-supplied id (covers a freshly-reloaded session whose bubble id
        // IS the DB row id).
        // [deep-audit-1048 A-07] Same turn identity as prompt()/retry(): this
        // entry also truncates the transcript (block-boundary cut), so only a
        // row absent from this snapshot is the re-run's own answer.
        val priorAnswerIds = turnAnswerSnapshot(app, sessionId)
        val liveAssistantId = vm.assistantMessageIdForToolBlock(blockId) ?: assistantMessageId
        val accepted = vm.rerunFromToolBlock(liveAssistantId, blockId)
        if (!accepted) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "rerun_rejected (streaming / not_found / not_tool_block)",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        // `accepted == true` is not the same as "a stream started": the
        // degenerate branch inside `rerunFromToolBlock` delegates to
        // `retryFromMessage`, which can still drop silently (its own gates),
        // while the non-degenerate path claims `_isStreaming` synchronously.
        // Read the flag on the same Main dispatcher so the settle branches
        // below can never report `Completed` for a run that never began.
        val started = vm.isStreaming.value
        if (!wait) {
            return@withContext if (started) {
                PromptResult(
                    status = "Rerunning",
                    responseText = null,
                    timedOut = false,
                    deletedMessageCount = 0,
                    retriedMessageId = assistantMessageId,
                )
            } else {
                PromptResult(
                    status = "Error",
                    responseText = "rerun_dropped_before_stream",
                    timedOut = false,
                    deletedMessageCount = 0,
                    retriedMessageId = assistantMessageId,
                )
            }
        }
        if (!started) {
            return@withContext PromptResult(
                status = "Error",
                responseText = "rerun_dropped_before_stream",
                timedOut = false,
                deletedMessageCount = 0,
                retriedMessageId = assistantMessageId,
            )
        }
        val finished = withTimeoutOrNull(timeoutMs) {
            if (vm.isStreaming.value) vm.isStreaming.first { !it }
            true
        } ?: false
        val msgs = app.chatRepository.loadMessages(sessionId)
        val answerIdx = turnAnswerIndex(msgs.map { TurnAnswerRow(it.id, it.role) }, priorAnswerIds)
        val answer = if (answerIdx >= 0) msgs[answerIdx] else null
        val settled = settleTurn(finished, answer != null)
        PromptResult(
            status = settled.status,
            responseText = settled.reason ?: answer?.let { extractText(it.partsJson) },
            timedOut = !finished,
            deletedMessageCount = (before - msgs.size).coerceAtLeast(0),
            retriedMessageId = assistantMessageId,
        )
    }

    /**
     * Trigger [ChatViewModel.runCompactNow] on the cached VM for [sessionId].
     * When [wait] is true, suspend until [ChatViewModel.isCompacting] flips
     * back to false (or [timeoutMs] elapses), then return the resulting
     * `summary` text alongside the timing flag.
     *
     * Behaviour parity with the in-app `/compact` slash command: if no turn
     * is in flight and history is non-empty, the VM emits a system-info
     * marker on completion. We do NOT surface that marker text — callers can
     * read `chat.messages.list` to see it. The `summary` returned here is
     * the freshly written compact_marker `summary` column.
     */
    suspend fun compact(
        context: Context,
        sessionId: String,
        wait: Boolean,
        timeoutMs: Long,
        messageId: String? = null,
        includesBoundary: Boolean = true,
    ): CompactResult = withContext(Dispatchers.Main) {
        // [R4-R F-01] Gate BEFORE materialising the ViewModel. `viewModel(…)`
        // runs `ChatViewModelStore.ownerFor(…)` (allocates the process-wide
        // store entry) and then a `ViewModelProvider` lookup (builds the VM),
        // so a compaction aimed at a deleted or misspelled id used to leave a
        // resident, row-less ChatViewModel behind — which `requireSendableSession`
        // then accepts as "the user is looking at it" for every later send.
        // The RPC faces above this one already read the row, so this is the
        // runner's own contract made self-contained.
        requireSendableSession(app(context), sessionId)
        val vm = viewModel(context, sessionId)
        // Same readiness wait as prompt(): compactAll() needs currentProvider
        // resolved before it can call provider.sendMessage for the summary.
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "no_provider_resolved_in_5s",
            )
        }
        // [deep-audit-1042 A-05] Same two-step load window as retry(): before
        // `_sessionLoaded` flips, the in-memory history the compactor walks is
        // empty, so a cold-session compact read as "nothing to compact" —
        // structurally a fake no-op `done` for a session that DID have
        // history. Await the latch off Main / bounded (the readiness wait for
        // `_activeEntryId` alone cannot close this window: it flips at load
        // step 1, the latch only at step 2's finally).
        val sessionReady = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.sessionLoaded.first { it } }
        }
        if (sessionReady == null) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "session_not_loaded_in_5s",
            )
        }
        if (vm.isStreaming.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "stream_in_progress",
            )
        }
        if (vm.isCompacting.value) {
            return@withContext CompactResult(
                status = "Error",
                summary = null,
                timedOut = false,
                error = "compact_already_in_progress",
            )
        }
        // Snapshot prior summary so we can detect a no-op (e.g. nothing-to-
        // compact branch appends a system-info but leaves _compactSummary
        // untouched, never flipping _isCompacting true).
        val priorSummary = vm.compactSummary.value
        // [dv29-A2] …and the prior compact MARKER. `isCompacting` flipping
        // back to false means "the compact job finished", not "it compacted
        // anything": `compactAll` sets the flag before its work and clears it in
        // `finally`, so a provider exception, an empty summary and a stale
        // anchor all end in the identical `false` with no marker written. That
        // used to be reported as `Completed` (the `else -> "Completed"` arm),
        // i.e. "context folded" while the transcript was untouched. The marker
        // row IS the artifact a successful fold writes (ChatDao
        // .insertCompactMarker, one fresh uuid per success), so it is the
        // evidence to settle on.
        val app = app(context)
        val priorMarkerId = withContext(Dispatchers.IO) {
            app.chatRepository.dao.latestCompactMarker(sessionId)?.id
        }
        if (messageId != null) {
            vm.compactBefore(messageId, includesBoundary)
        } else {
            vm.runCompactNow()
        }
        if (!wait) {
            return@withContext CompactResult(
                status = "Running",
                summary = null,
                timedOut = false,
                error = null,
            )
        }
        // Wait for _isCompacting to flip true → false. If runCompactNow
        // early-returned (nothing to compact, no provider, etc.) it never
        // flipped true at all — give it a short grace window then bail.
        val flippedOn = withContext(Dispatchers.Default) {
            withTimeoutOrNull(2000L) {
                if (!vm.isCompacting.value) vm.isCompacting.first { it }
                true
            }
        }
        if (flippedOn != true) {
            return@withContext CompactResult(
                status = "NoOp",
                summary = vm.compactSummary.value,
                timedOut = false,
                error = "compact_skipped_no_change",
            )
        }
        val finished = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                vm.isCompacting.first { !it }
                true
            } ?: false
        }
        val newSummary = vm.compactSummary.value
        // `Changed` alone is not enough: a successful compact that folds a range
        // into an identical string leaves _compactSummary equal, so the marker
        // delta is the primary witness and the string delta the secondary one.
        val markerLanded = withContext(Dispatchers.IO) {
            app.chatRepository.dao.latestCompactMarker(sessionId)?.id
        }.let { it != null && it != priorMarkerId }
        val summaryChanged = newSummary != priorSummary && !newSummary.isNullOrBlank()
        val settled = settleCompactOutcome(finished, markerLanded, summaryChanged)
        CompactResult(
            status = settled.status,
            summary = newSummary,
            timedOut = !finished,
            error = settled.error,
        )
    }

    /**
     * Drive [ChatViewModel.revertCompact] on the cached VM. Returns when the
     * VM has finished the synchronous DB write — revert isn't gated by a
     * coroutine the way compact is.
     */
    suspend fun revertCompact(context: Context, sessionId: String) = withContext(Dispatchers.Main) {
        // [R4-R F-01] Same existence gate as [compact]: a revert for a row-less
        // id must answer `-32602`, never build the resident VM that hides the
        // missing row from every later send.
        requireSendableSession(app(context), sessionId)
        val vm = viewModel(context, sessionId)
        val ready = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) {
                vm.activeEntryId.first { it != null }
            }
        }
        if (ready == null) throw RPCException(-32000, "Session VM did not resolve a provider within 5s")
        // [deep-audit-1042 A-05] `revertCompact` deletes the LATEST compact
        // marker via `_cachedLatestMarker`, which is only populated by the
        // message load ("before the first load completes it is null, so
        // callers treat null as no boundary"). Before the latch flips the
        // in-VM revert is a silent no-op that still appends "nothing to
        // revert" system info, and the RPC layer (ChatMutationMethods.
        // compactRevert) then polls for a marker-drop that never happens and
        // answers `afterMarkerCount == beforeMarkerCount` after a 5 s hang —
        // a structured failure here is the honest answer instead. Await the
        // latch off Main / bounded, same shape as the other entries.
        val sessionReady = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.sessionLoaded.first { it } }
        }
        if (sessionReady == null) {
            throw RPCException(-32000, "session_not_loaded_in_5s: $sessionId")
        }
        vm.revertCompact()
    }

    /**
     * [deep-audit-1039 A-06] Cancel the stream for [sessionId].
     *
     * This used to resolve the ViewModel **only** through [providers], the
     * private cache `providerFor` writes. That cache is populated by the RPC
     * entry points (`chat.prompt` / `retry` / …) — it is empty for a session
     * the **UI** started (ChatScreen binds its VM straight through
     * [ChatViewModelStore]) and for a draft-key id (`__new__<uuid>`) whose
     * store lives under the promoted id. `cancel` therefore returned false and
     * cancelled nothing, while `debug/ChatMutationMethods.deleteSession` calls
     * it *before* deleting the row — the streaming job kept writing into a
     * deleted session.
     *
     * Resolution now goes through the same single source as every other method
     * here: [ChatViewModelStore.ownerFor], which resolves the draft alias and
     * returns the process-wide store the UI binds to. The private cache stays
     * as the fast path for RPC-driven sessions (a `providers` hit means the
     * provider object is already built for that exact key) — it is written at
     * `providerFor` and dropping it would change both performance and the
     * per-key VM identity the RPC layer relies on.
     *
     * Return-value contract is unchanged: false when nothing is streaming.
     */
    suspend fun cancel(context: Context, sessionId: String): Boolean = withContext(Dispatchers.Main) {
        // [R4-R F-01] One row read decides whether the resolver below may build
        // a ViewModel at all: a cancel is a request to stop something that
        // EXISTS, so an id with no `chat_sessions` row must answer `false`
        // (nothing was streaming) instead of creating the resident VM that
        // every later `requireSendableSession` would take as "a live session".
        val persisted = withContext(Dispatchers.IO) {
            app(context).chatRepository.dao.getSession(sessionId) != null
        }
        val vm = viewModelForCancel(context, sessionId, persisted) ?: return@withContext false
        val wasRunning = vm.isStreaming.value
        if (wasRunning) vm.cancelStream()
        wasRunning
    }

    /**
     * Resolve the VM to cancel: the private cache first (the fast path for
     * RPC-driven sessions), then the process-wide store the UI binds through,
     * with the draft alias resolved before the VM is built. Kept as one
     * function so the resolution order is a property of `cancel` alone
     * (asserted in `HeadlessChatCancelStoreWiringTest`).
     *
     * [R4-R F-01] Two rules keep this from becoming a factory:
     *  - the store is read non-destructively first ([ChatViewModelStore.peekViewModel]),
     *    which is what a draft key (`__new__<uuid>`, no row yet) needs;
     *  - the building fallback ([ChatViewModelStore.ownerFor] + a
     *    `ViewModelProvider` lookup) is only reached for a session whose row
     *    actually exists — [persistedSession]. Before this, `chat.session.cancel`
     *    on a deleted or misspelled id materialised a resident, row-less
     *    `ChatViewModel` that never went away.
     */
    private fun viewModelForCancel(
        context: Context,
        sessionId: String,
        persistedSession: Boolean,
    ): ChatViewModel? {
        providers[sessionId]?.let { cached ->
            runCatching { cached[ChatViewModel::class.java] }.getOrNull()?.let { return it }
        }
        ChatViewModelStore.peekViewModel(sessionId)?.let { return it }
        if (!persistedSession) return null
        val owner = runCatching { ChatViewModelStore.ownerFor(sessionId) }.getOrNull() ?: return null
        val app = app(context)
        return runCatching {
            ViewModelProvider(
                owner,
                ChatViewModel.factory(
                    // The store's live VM sits under the promoted id, so any
                    // build that does happen must use the resolved key — the
                    // raw `__new__<uuid>` draft key would create a second VM.
                    sessionId = ChatViewModelStore.resolveSessionId(sessionId),
                    chatRepository = app.chatRepository,
                    providerRepository = app.providerRepository,
                    appContext = app.applicationContext,
                    memoryRepository = app.memoryRepository,
                    skillRepository = app.skillRepository,
                    mcpRepository = app.mcpRepository,
                ),
            )[ChatViewModel::class.java]
        }.getOrNull()
    }

    /**
     * [T-android-thinking-level-arch] Switch the LIVE session's bound model
     * mid-session — exactly what the in-app model picker does, by calling the
     * same [ChatViewModel.selectEntry]. Unlike passing a fresh `modelEntryId` to
     * chat.prompt (which only rewrites the DB binding and is ignored by the
     * already-loaded cached VM), this re-resolves `currentModel`/`currentProvider`
     * on the live VM. selectEntry deliberately leaves the thinking level
     * untouched, so this is the seam for verifying cross-model thinking-level
     * transfer. Returns (resolved model displayName, current thinking level
     * name) so callers can assert the transfer without a second round-trip.
     *
     * [deep-audit-1043 A-05] The cold-session window: `loadSession` publishes
     * in two steps — `_activeEntryId` flips at step 1 (ChatSessionLifecycle
     * :423) while the session row is only re-read and applied at step 2 (:421-
     * 458) and `_sessionLoaded` flips in that block's `finally` (:786). A
     * `viewModel(…)` built for a session that is not resident yet therefore
     * starts its load asynchronously; calling `selectEntry` inside that window
     * did two things wrong: (1) `_thinkingLevel` was still the constructor
     * default `OFF`, so `selectEntry` clamped to OFF and this method ECHOED
     * `"OFF"` to the caller even though the session's stored level is not OFF;
     * (2) `selectEntry` set `currentModel`/`_activeEntryId` on Main, then the
     * late `loadSession` step 2 overwrote all of them from the session row it
     * had already read — the switch silently reverted. Await the latch first,
     * the same bounded OFF-Main shape as retry/rerun/compact/revertCompact;
     * a timeout is a structured error, never a guess (a selectEntry that lands
     * in the window above would report a level that is about to be rolled
     * back).
     */
    suspend fun selectModel(context: Context, sessionId: String, entryId: String):
        Pair<String, String> = withContext(Dispatchers.Main) {
        val app = app(context)
        // [dv29-A2] Refuse before creating a VM for a session that does not exist.
        requireSendableSession(app, sessionId)
        val vm = viewModel(context, sessionId)
        // OFF Main (the load resumes on Main.immediate — waiting on Main would
        // deadlock it) and bounded (an unbounded wait pins the RPC connection).
        val sessionReady = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5000L) { vm.sessionLoaded.first { it } }
        }
        if (sessionReady == null) {
            throw RPCException(-32000, "session_not_loaded_in_5s: $sessionId")
        }
        // [dv29-A2] `ChatViewModel.selectEntry` REFUSES silently on an
        // unknown / disabled / key-less target (a UI contract this entry must
        // not change: ChatScreen taps rely on the silent early return). Left
        // unchecked, this method then returned the PREVIOUS model's name next
        // to the caller's rejected id — "switched to X" for a switch that never
        // happened, which is worse than a refusal because automation continues
        // on the old model believing otherwise. Decide with the same single
        // source the runtime gate uses, then verify the switch landed.
        val cfg = app.providerRepository.config.value
        val entry = cfg.modelEntries.firstOrNull { it.id == entryId }
            ?: throw RPCException(-32602, "Entry not found: $entryId")
        val instance = app.providerRepository.instance(entry.providerInstanceId)
        val decision = decideSessionModelResolution(
            entry,
            instance,
            instance?.let { app.providerRepository.loadApiKey(it.id) },
        )
        if (decision !is SessionModelResolution.Use) {
            val reason = bindingRejectReason(decision) ?: "UNUSABLE"
            throw RPCException(-32602, "entry:$entryId is not usable — $reason")
        }
        vm.selectEntry(entryId)
        // selectEntry sets currentModel/_modelName synchronously on the success
        // path; when it took one of its refusal branches the state is untouched.
        // Read the observable fact instead of trusting the call.
        val active = vm.activeEntryId.value
        if (active != entryId) {
            throw RPCException(
                -32000,
                "model_switch_not_applied: requested=$entryId active=${active ?: "null"}",
            )
        }
        vm.modelName.value to vm.thinkingLevel.value.name
    }

    /** Drop the cached ViewModel for [sessionId] (used after delete). */
    @Synchronized
    fun forget(sessionId: String) {
        providers.remove(sessionId)
    }

    /**
     * [deep-audit-1048 A-07] The identity of "before this turn": the ids of the
     * assistant rows already persisted for [sessionId].
     *
     * Snapshot BEFORE the turn is dispatched — that is the whole point: a row
     * that is already there belongs to an earlier turn, a row that is missing
     * from the snapshot was written during this one.
     */
    private suspend fun turnAnswerSnapshot(
        app: MinisApp,
        sessionId: String,
    ): Set<String> {
        val rows = app.chatRepository.loadMessages(sessionId)
        return assistantIdsOf(rows.map { TurnAnswerRow(it.id, it.role) })
    }

    private fun extractText(partsJson: String): String? {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") == "text") sb.append(o.optString("value", ""))
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) { partsJson.ifEmpty { null } }
    }

    /**
     * [dv29-A2] How one compact attempt settles, from evidence rather than from
     * a flag transition.
     *
     * - `!finished` → the wait timed out; whether it later succeeds is unknown,
     *   so this stays `Timeout` (never a success claim).
     * - marker landed (a fresh `compact_markers` row) OR the summary string
     *   changed → something WAS folded: `Completed`.
     * - `isCompacting` went back to false with neither → `compact_failed:
     *   nothing_folded`. That is the provider-exception path, the empty-summary
     *   path and the anchor-validation path, which `compactAll` reaches without
     *   writing a marker; reporting them as success was the defect.
     *
     * Pure on purpose: the three failure paths are pinned as literals in
     * `HeadlessChatRunnerCompactSettlementTest`.
     */
    internal fun settleCompactOutcome(
        finished: Boolean,
        markerLanded: Boolean,
        summaryChanged: Boolean,
    ): CompactSettlement = when {
        !finished -> CompactSettlement("Timeout", null)
        markerLanded || summaryChanged -> CompactSettlement("Completed", null)
        else -> CompactSettlement("Error", "compact_failed:nothing_folded")
    }

    /** [dv29-A2] `status` / `error` pair produced by [settleCompactOutcome]. */
    internal data class CompactSettlement(val status: String, val error: String?)

    data class PromptResult(
        val status: String,
        val responseText: String?,
        val timedOut: Boolean,
        val deletedMessageCount: Int = 0,
        val retriedMessageId: String? = null,
    )

    data class CompactResult(
        val status: String,
        val summary: String?,
        val timedOut: Boolean,
        val error: String?,
    )
}

/**
 * [deep-audit-1044 A-03] The one envelope for "the send was dropped before any
 * stream was ever claimed".
 *
 * `ChatViewModel.sendMessage` claims `_isStreaming` synchronously, and only
 * after every pre-flight gate (Safe Mode / `currentProvider == null` /
 * `_isCompacting` / context exhausted / blank input). A send that returns
 * before that line will never stream, so the headless wrapper must not pretend
 * a turn happened: it reports `"Error"` with the reason in `responseText`,
 * the same shape as `"no_provider_resolved_in_5s"` / `"session_not_loaded_in_5s"`.
 *
 * Both call sites use it — the `wait=false` branch and the `wait=true` guard —
 * so the two can never drift into different statuses for the same event. The
 * literals are a contract: `ScheduledTaskRunner` settles any status !=
 * "Running" as RESULT_ERROR, and the offload/CLI layer prints `responseText`
 * as the machine-readable reason.
 *
 * Top level (not a member) so a plain JVM test can assert the literals without
 * constructing the Android-bound runner.
 */
internal fun droppedSendResult(): HeadlessChatRunner.PromptResult =
    HeadlessChatRunner.PromptResult(
        status = "Error",
        responseText = "send_dropped_before_stream",
        timedOut = false,
    )
