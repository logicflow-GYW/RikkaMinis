package com.rikkaminis.app.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import com.rikkaminis.app.R
import com.rikkaminis.app.config.AttachActionCatalog
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.MinisMenu
import com.rikkaminis.app.data.ChatTuningPrefs
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.util.ExternalIntent

// [refactor/split-chatscreen] Batch 1: the composer/input area composable,
// moved VERBATIM from ChatScreen.kt (was lines 4911-6439). Only change:
// private -> internal so ChatScreen.kt (same package) can still call it.
// All internal state (TextFieldValue mirroring, IME burst debounce, swipe-to-
// send, slash/mention pickers, attachment chips) lives inside this composable
// and does not read ChatScreen-scope state beyond its explicit parameters.
//
// NOTE on imports: the verbatim move copied the full original import list from
// ChatScreen.kt; the 2026-10-27 dead-import sweep pruned it to the symbols this
// file actually uses. Comment archaeology elsewhere is left intact.

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun ChatInputArea(
    viewModel: ChatViewModel,
    sessionId: String,
    chatRepository: com.rikkaminis.app.data.repository.ChatRepository,
    chatActions: ChatActionState,
    isStreaming: Boolean,
    isNearBottom: androidx.compose.runtime.State<Boolean>,
    onFollowEvent: (FollowEvent) -> Unit,
    onMoveToSession: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onPreviewAttachment: (com.rikkaminis.app.ui.sandbox.FileItem) -> Unit,
    onPreviewImageGallery: (List<com.rikkaminis.app.ui.components.ImageGalleryItem>, Int) -> Unit,
    onOpenWebAppSheet: (InputAttachment) -> Unit,
    chatInputFontScale: Float,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onLaunchCamera: () -> Unit,
    onLaunchCameraPermission: () -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    focusManager: androidx.compose.ui.focus.FocusManager,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    // [feat/chat-tuning-panel] User-tunable composer knobs (Settings →
    // Appearance → Chat Tuning). Defaults are the previously hard-coded
    // values, so callers that don't pass them behave byte-identically.
    inputMaxLines: Int = ChatTuningPrefs.INPUT_MAX_LINES_DEFAULT,
    sendSwipeThresholdDp: Int = ChatTuningPrefs.SEND_SWIPE_THRESHOLD_DEFAULT,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val inputText by viewModel.inputText.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    // [feat/paste-fold-marker] Long pastes folded out of the field: each one is
    // a `[Pasted#N]` literal in the text plus a chip in the row below. Chips are
    // rendered from the buffer but FILTERED by marker presence, so a composer
    // that no longer holds the literal (sent / cleared) can never leave an
    // orphan chip behind — the text is the reference, the buffer row is data.
    val pastedTexts by viewModel.pastedTexts.collectAsState()
    var showMoveSheet by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    // [fix/ime-overlay-focus-coverage] MoveToSessionSheet owns its own window
    // (ModalBottomSheet) and restores focus to the composer on dismiss — the same
    // IME pop-back loop ChatScreen guards for its overlays. Shared implementation
    // (ImeOverlayGuard.kt) so the two hosts don't drift; this flag is local to
    // ChatInputArea, so the guard is invoked here rather than in ChatScreen.
    DismissImeWhileOverlayOpen(showMoveSheet)
    // Mirrors `inputText` for the BasicTextField but tracks selection so we
    // can position the cursor (e.g. AFTER the leading "/" when the slash
    // button inserts it) — a plain String overload would reset cursor to 0
    // on every external write.
    var inputFieldValue by remember {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    }
    // [Txxx-android-composer-caret] Authoritative caret written on EVERY
    // onValueChange from the IME's own TextFieldValue.selection. The old
    // fallback read inputFieldValue.selection.end — but under the long-paste
    // race inputFieldValue holds the PRE-write text, so its selection.end was
    // a stale index that, coerced into the new text, landed at a random
    // mid-string position. This never contains the corruption: onValueChange
    // is the single writer of the true caret, and LaunchedEffect merely reads
    // this stable value back when no external pendingCaret intent exists.
    var lastTrueCaretEnd by remember { mutableStateOf(0) }
    // T217-2: suppress IME commits arriving briefly after send. clearFocus
    // triggers finishComposingText, which makes voice/Pinyin IMEs commit
    // their pending candidate back through onValueChange even after we
    // cleared inputText. Drop those late commits during a short window.
    var lastSendTimeMs by remember { mutableStateOf(0L) }
    // [fix/voice-crash-observability tail] IME burst debounce state. Buffers
    // the latest large-increment edit and the job that flushes it 150 ms
    // after the last burst, so voice dictation's N-event bursts collapse to
    // a single setInputText commit (see shouldDebounceImeBurst). Null buffer =
    // nothing pending; the job is cancelled+replaced on every new burst.
    var imeBurstBuffer by remember { mutableStateOf<String?>(null) }
    var imeBurstJob by remember { mutableStateOf<Job?>(null) }
    // [meta-audit-1028 C3/F-03] One-shot bridge from the hardware key handler to
    // the soft-IME Enter intercept in `onValueChange`. Compose inserts the
    // newline for a Shift+Enter itself (the key handler returns false), so the
    // two paths are indistinguishable by text shape alone — only the key event
    // knows which one it was. Set on KeyDown, consumed by the next
    // `onValueChange` whatever it is (a flag outliving its insertion would eat
    // the next genuine soft-keyboard Enter = a lost send).
    var shiftEnterNewlinePending by remember { mutableStateOf(false) }
    // [fix/dv19-b1] Drop the pending burst flush. Called at every point that acts
    // on — or clears — the composer: send / Enter-send (the two sites that used to
    // spell these three writes out inline) plus the slash dismiss, the slash
    // command and the cross-session transfer, each of which now commits the
    // field's LIVE value instead of the 150 ms-debounced VM string. Committing the
    // live text makes the snapshot redundant, and leaving the job alive lets a
    // stale snapshot land on a composer that has since been cleared or handed to
    // another session (inputText is per-ViewModel, so the late write hit the old
    // session's composer after a move).
    val cancelPendingImeBurst: () -> Unit = {
        imeBurstJob?.cancel()
        imeBurstJob = null
        imeBurstBuffer = null
        // [dv25-B08] The buffer is gone, so the ViewModel's pending copy must go
        // with it — every caller of this funnel commits the field's live text
        // itself, which is newer than the snapshot.
        viewModel.publishPendingComposerText(null)
    }
    // [dv20-B03] Single funnel for "clear the composer from outside
    // onValueChange". Every clear entrance used to spell
    // `viewModel.setInputText("")` on its own, and that write alone is a no-op
    // on the FIELD: `_inputText` is a StateFlow, so clearing an already-empty
    // composer is an equal write, it is suppressed, the
    // `LaunchedEffect(inputText)` above never re-runs, and whatever the user
    // typed during a >8-char IME burst (still buffered, not yet committed)
    // stayed in the box after the message had been sent. Clearing is three
    // writes, not one — drop the pending burst flush, commit the empty string,
    // and reset the field plus the caret tracker. Declared before its
    // consumers on purpose: a local `val` lambda cannot be referenced before
    // its declaration (the 1039-B1 lesson — the compiler rejected the
    // subscription written after its consumer).
    val clearComposerInput: () -> Unit = {
        cancelPendingImeBurst()
        viewModel.setInputText("")
        inputFieldValue = androidx.compose.ui.text.input.TextFieldValue("")
        lastTrueCaretEnd = 0
        // [meta-audit-1028 C3/F-04] Every clear entrance is a send / cancel —
        // the message leaves the composer, and the pickers that were floating
        // over it must leave with it. Neither `setInputText("")` nor the field
        // write above touches menu state (a StateFlow-only write is not an
        // `onValueChange`), so `@`/`/` used to stay on screen over an empty
        // composer. Worse, `savedInputBeforeSlash` survived the send: the next
        // tap on the chat list raises `DismissSlashMenu`, whose resolution
        // restores that saved draft — a message already sent reappeared in the
        // box. Drop the saved original FIRST (so `updateSlashMenuState` cannot
        // take the over-content branch and re-open the menu on the way out).
        viewModel.dismissMentionMenu()
        viewModel.savedInputBeforeSlash = null
        viewModel.updateSlashMenuState("")
    }
    // [dv24/B-05] Single funnel for "replace / append the composer body from
    // outside onValueChange" — the twin of `clearComposerInput` for the two text
    // intents. It writes BOTH copies on purpose: the ViewModel string feeds the
    // inputText→field mirror, and the direct field write is what makes a write
    // land when the new text already EQUALS the ViewModel string (the StateFlow
    // drops the equal emission, and the mirror's `!=` guard then skips too — the
    // message-edit prefill used to lose its caret that way, and the still-live
    // 150 ms IME burst then flushed its pre-edit snapshot straight over the
    // edit). Declared before its consumers for the same reason as the funnel
    // above (1039-B1: a local `val` lambda cannot be referenced before its
    // declaration).
    //
    // [deep-audit-1049 B-06] `caret` is nullable: the slash/`@` outlets below
    // that carry no caret intent pass `null` and keep the user's true caret
    // (`lastTrueCaretEnd`), which is exactly what the inputText mirror does for
    // them. They used to call `viewModel.setInputText(...)` on their own, and
    // that path is the one that can lose: while a >8-char IME burst is still
    // buffered, `inputFieldValue` is AHEAD of `viewModel.inputText`, so
    // `setInputText(newText)` where newText == the VM string is an equal write —
    // the StateFlow suppresses it, the mirror never re-runs, and the field keeps
    // the old slash prefix. `_pendingCaret` was also left dangling for the next
    // input.
    // [meta-audit-1028 C3/F-01] The funnel must also DRAIN the ViewModel's
    // one-shot caret intent. Before: the four slash outlets pass `null` and the
    // funnel resolved the caret from `lastTrueCaretEnd` alone, so the value
    // `showSlashMenuOverInput` / `executeSlashCommand` had just parked in
    // `_pendingCaret` was stranded — and it could never be picked up later
    // either, because the funnel writes BOTH copies, so the
    // `inputFieldValue.text != inputText` guard on the mirror effect (the only
    // reader of `consumePendingCaret()`) is already false when it re-runs.
    // Left the composer showing `/ hello wor|ld` (caret at the pre-slash index)
    // and left a stale intent to hijack the caret on the NEXT external write.
    // Drain unconditionally — a stale intent must never outlive a commit — and
    // let an explicit caller caret win over it.
    val commitComposerBody: (String, Int?) -> Unit = { text, caret ->
        cancelPendingImeBurst()
        val at = composerFunnelCaret(
            caret = caret,
            pendingCaret = viewModel.consumePendingCaret(),
            lastTrueCaretEnd = lastTrueCaretEnd,
            length = text.length,
        )
        viewModel.setInputText(text)
        inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
            text = text,
            selection = androidx.compose.ui.text.TextRange(at),
            composition = null,
        )
        lastTrueCaretEnd = at
    }
    // [fix/dv23-b3] Composer intents raised outside the composer (topbar `/`
    // button, tap on the chat list, system back, clear-chat confirm) are
    // executed HERE. ChatScreen used to resolve them against
    // `viewModel.inputText` — the 150 ms-debounced snapshot — which rolled a
    // dictation/paste that had already landed in `inputFieldValue` back to a
    // stale string; and because the slash menu's Popup sets
    // `dismissOnClickOutside = false` / `focusable = false`, every outside tap
    // reaches this path rather than the Popup's own onDismissRequest above.
    // Declared after `clearComposerInput` on purpose: a local `val` lambda
    // cannot be referenced before its declaration (the 1039-B1 lesson).
    LaunchedEffect(Unit) {
        viewModel.composerCommand.collect { command ->
            when (command) {
                ComposerCommand.ToggleSlashMenu -> {
                    cancelPendingImeBurst()
                    // [meta-audit-1028 C3/F-04] The two pickers are mutually
                    // exclusive (`updateMentionMenuState` defers to the slash
                    // menu), but opening the slash menu over a live `@` token
                    // used to leave the mention popup underneath it — two
                    // overlays stacked over one composer. Tapping `/` is an
                    // explicit "switch pickers" intent, so drop the other one.
                    viewModel.dismissMentionMenu()
                    // Brace-less if/else on purpose: both resolutions sit in THIS
                    // arm's block, the one that dropped the pending burst.
                    val menuOpen = viewModel.showSlashMenu.value
                    val next =
                        if (menuOpen) viewModel.dismissSlashMenu(inputFieldValue.text)
                        else viewModel.showSlashMenuOverInput(inputFieldValue.text)
                    // [deep-audit-1049 B-06] Through the funnel: a bare
                    // setInputText equal to the (burst-lagged) VM string is
                    // suppressed, and the field keeps the pre-toggle text.
                    commitComposerBody(next, null)
                    runCatching { inputFocusRequester.requestFocus() }
                    keyboardController?.show()
                }
                ComposerCommand.DismissSlashMenu -> {
                    commitComposerBody(
                        viewModel.dismissSlashMenu(inputFieldValue.text),
                        null,
                    )
                }
                ComposerCommand.Clear -> clearComposerInput()
                // [dv24/B-05] The two text intents: resolved HERE against the
                // field's live text, then committed through the funnel above.
                is ComposerCommand.ReplaceText -> commitComposerBody(
                    command.text,
                    composerCaretFor(command.text, command.caretOverride),
                )
                is ComposerCommand.AppendText -> {
                    // `inputFieldValue.text` inline on purpose: it is the
                    // composer's own live value (E-family criteria anchor).
                    val next = composeAppend(
                        live = inputFieldValue.text,
                        text = command.text,
                        separator = command.separator,
                        trimExistingEnd = command.trimExistingEnd,
                        trailingSpace = command.trailingSpace,
                    )
                    commitComposerBody(next, next.length)
                }
            }
        }
    }
    // Paired with imeBurstBuffer: the caret that must be scanned against the
    // FLUSHED text when a debounced burst finally commits. Stored alongside
    // the buffer (not read late from inputFieldValue) so the mention scan in
    // the flush sees the caret that belonged to that exact burst, not a
    // later-arrived one.
    var imeBurstCaret by remember { mutableStateOf(0) }
    // Commit the composer input-mode pref on send. Voice input was removed, so
    // every composition is now "text"; the old voiceUsedSinceClear tracker and
    // the VoiceCorrection vocabulary miner went with it.
    val noteSendForInputModePref: () -> Unit = {
        ComposerInputModePrefs.save(context, voice = false)
    }
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (inputFieldValue.text != inputText) {
            // [Txxx-android-composer-caret-jump] Honor a one-shot caret
            // override from the slash flow (prepend "/ " → caret 1; insert
            // "/<skill> " → caret after the prefix). Read-and-clear so it
            // applies exactly once.
            //
            // When there's NO pendingCaret (normal typing / paste / external
            // write that didn't tag a caret), PRESERVE the user's current caret
            // instead of forcing it to inputText.length. The old `?: inputText.length`
            // reset the selection to the END on every relaunch where the guard
            // above fired — and because this LaunchedEffect races the
            // BasicTextField.onValueChange writer to inputFieldValue, a long
            // paste could see a mid-frame text mismatch, trigger the reset, and
            // randomly yank the caret (often to the end) while the user was
            // editing the middle of a long message. The slash/mention/draft
            // intent paths all set pendingCaret, so this keeps their precise
            // positioning intact; only the unintended end-jump is removed.
            // Coerce into bounds both ways defensively.
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: lastTrueCaretEnd.coerceIn(0, inputText.length)
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                // T217: explicitly drop any pending IME composing buffer so voice
                // recognition / Pinyin candidates don't get re-committed back into
                // the field after send (mirrors iOS unmarkText in AIChatView.swift
                // updateUIView L5638).
                composition = null,
            )
        }
    }
    // Mirror of iOS `inputFocused` — needed so the swipe-up-on-empty-input
    // gesture only pops the keyboard when it's actually collapsed.
    var inputFocused by remember { mutableStateOf(false) }

    // --- Swipe-up-to-send (parity with iOS AIChatView.swift) ---------------
    // Drag progress 0..1 as fraction of the trigger distance. Drives the
    // floating send-arrow hint + "Release to send" capsule overlay. Only
    // updated while the input has non-empty text.
    var sendSwipeProgress by remember { mutableStateOf(0f) }
    // Live fingertip position inside the input bar (px). Hint floats ~60dp
    // above this point so it isn't hidden under the user's thumb.
    var sendSwipeLocation by remember { mutableStateOf(Offset.Zero) }
    val swipeThresholdPxState = rememberUpdatedState(
        with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() },
    )
    // [fix/dv16-b] rememberUpdatedState, not a plain val capture: a dynamic
    // ChatTuning adjustment of the threshold must reach the gesture without
    // restarting pointerInput(Unit), which freezes the first composition's px.
    // Match iOS: haptic + capsule full-opacity + release-fires-send all
    // engage at this fraction (below 1.0 so user gets earlier confirmation).
    val swipeArmFraction = 0.8f
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // [fix/dv16-b] Single send-eligibility predicate for the swipe-up gesture.
    // The three decision sites (drag hint / release-send / empty-input
    // keyboard) each used to re-derive it — the release branch even re-derived
    // it INSIDE an already-`hasText`-guarded block, a dead branch that routed
    // attachment-only drags to the keyboard instead of sending them. Reads the
    // field's own TextFieldValue (the single-writer truth the user sees, never
    // staler than the debounced VM string) + the live attachment list, both at
    // call time — the gesture block is a long-lived pointerInput(Unit) closure,
    // so eligibility must be read per-event, not captured once per composition.
    val canSwipeSendNow: () -> Boolean = {
        inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()
    }

    // [feat/paste-fold-marker] Fail-closed send guard, evaluated per event for
    // the same reason as `canSwipeSendNow` above: the gesture/send callbacks are
    // long-lived closures, so the condition has to be read when it matters.
    //
    // A local function, deliberately NOT a second `() -> Boolean` predicate:
    // `canSwipeSendNow` stays the ONE send-eligibility predicate (that is what
    // ComposerSendSwipeTest pins, because a second per-site re-derivation of
    // "can we send" is exactly how the dead swipe branch came back). This one
    // answers a different question — "is this composer's text unsendable?" —
    // and the eligibility predicate stays untouched, so `showStop` keeps its
    // meaning (blocked-by-marker is not "no content", and must not turn the
    // send button into a Stop button mid-stream).
    //
    // A restored draft can hold a `[Pasted#N]` whose text died with the process;
    // sending it verbatim would hand the model a literal marker in place of the
    // user's paragraphs — silent content loss. Expansion itself stays lenient
    // (a hand-typed marker is just text), so only "orphan" markers from the
    // restore point block here.
    fun pasteSendBlockedNow(): Boolean = viewModel.pasteSendBlockedBy(inputFieldValue.text)

    val performSendOrEnqueue: () -> Unit = handler@{
        // [feat/paste-fold-marker] Fail-closed: a restored draft can still hold
        // a marker nothing can expand. The button is disabled in that state and
        // the notice above the field says why, but the swipe gesture and the
        // Enter key reach this same funnel — refuse here, before anything is
        // cleared, so nothing is lost and the text stays where the user can fix
        // it.
        if (pasteSendBlockedNow()) return@handler
        // [fix/ttfb-thinktag-composer] Cancel the pending IME-burst flush and
        // read the send text from the field's own TextFieldValue — the
        // single-writer truth the user actually sees. History: [T2-M2]
        // started folding the burst buffer in because the send used to read
        // the committed VM value (dictation tail lost). But the buffer is
        // only a SNAPSHOT of the last large edit: after its 150 ms flush
        // committed, any later small edit (typing a prefix, a trailing word,
        // deletes — either end) updated only the field/VM, and the stale
        // snapshot silently overwrote everything on send ("my message went
        // out missing the part I typed"). The field value can never be
        // staler than the buffer or the VM value: onValueChange writes it
        // first on every path, and it is exactly what the user reads on
        // screen.
        cancelPendingImeBurst()
        val toSend = inputFieldValue.text
        if (viewModel.tryExecuteInputAsSlashCommand(toSend)) {
            clearComposerInput()
            keyboardController?.hide()
            focusManager.clearFocus()
            return@handler
        }
        lastSendTimeMs = SystemClock.elapsedRealtime()
        // [P2-scroll-user-send] Capture the viewport anchor BEFORE sendMessage:
        // sending inserts the user row at index 0, which in reverseLayout
        // pushes firstVisibleItemIndex from 0 → 1, so reading isNearBottom
        // AFTER the insert would wrongly read "scrolled into history" for a
        // bottom-anchored sender. Snapshot first, then act.
        val wasScrolledIntoHistory = !isNearBottom.value
        clearComposerInput()
        keyboardController?.hide()
        focusManager.clearFocus()
        viewModel.sendMessage(toSend)
        noteSendForInputModePref()
        // [P2-scroll-user-send] The send re-engages follow ONLY if the user was
        // already at the bottom (wasScrolledIntoHistory==false) — sending does
        // NOT turn a history reader into a follower. The FAB remains the way a
        // reader re-engages. [forward-stable] The reducer raises exactly ONE
        // pending bottom request (no initial+settle double scroll); a history
        // reader stays detached and is never yanked.
        if (!wasScrolledIntoHistory) {
            // [P2-scroll-user-send] Re-engage follow (a bottom-anchored sender
            // wants the new turn followed). SIMPLE_FOLLOW's dedicated effect
            // also covers this (streaming just started + sender at bottom), and
            // this reducer request is a harmless duplicate targeting the same
            // sentinel — kept for the explicit-intent path.
            onFollowEvent(FollowEvent.Send)
        } else {
            AppLogger.debug("USER-SEND", "reader-in-history, skip yank (firstIdx drift scene)")
        }
    }
    // [forward-stable] Anchor-guard REMOVED — the reverseLayout compensation
    // loop is gone with the forward list. Content grows at the tail; the
    // viewport anchor (first visible row key) never moves; there is no
    // (index0 → index1 → index0) transient to fight, so no drift watcher, no
    // deadzone, no 100ms throttle, no forced re-pin. Follow decisions live in
    // the drag-stop handler + explicit intents below (FollowController in
    // Commit D turns these into a single request protocol).

        // ─── Input area (iOS-style: rounded box with text + buttons below) ───
        // [composer-width-align-rikkahub] The input card's horizontal inset
        // is raised from 12dp to 16dp to line up its edges with the message
        // list's 16dp gutter — the list and the composer previously sat on
        // different rails, which read as a narrower input box than the chat.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        ) {
            // T13 banner moved INSIDE the LazyColumn so it renders at the
            // visual end of the message list (mirrors iOS — see the
            // banner item before items() in the LazyColumn block above).

            // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
            // a Popup so it overlays content (tool status bar, chat list)
            // instead of pushing them up. Anchored above the composer via
            // PopupProperties so its bottom edge sits just above this Column.
            // Tap-outside dismisses via dismissOnClickOutside.
            val showSlashMenu by viewModel.showSlashMenu.collectAsState()
            // [dv17-4] The list is only ever RENDERED under `showSlashMenu` (the
            // `if` below), yet it used to be computed on every composition: the
            // remember ran unconditionally, so entering the chat, switching
            // sessions or touching a key spent N+M synchronous `db.rawQuery`
            // calls (one `isEnabledForSession` per skill + per MCP server) on
            // the main thread and dropped the result on the very next line.
            // The gate still wraps the whole computation — closed menu = no
            // producer, no query. The keys name the live sources the body
            // actually reads: `slashFilter` / `memoryEnabled` (built-in rows),
            // `currentSessionId` and the two repositories' lists (the
            // per-session override the picker filters on). `thinkingLevel` is
            // gone: `/thinking` is no longer in `availableSlashCommands`, so
            // that key had 0 reads in the body.
            //
            // [dv22-B4] The compute itself left the composition: it stayed a
            // synchronous `remember` here, so every keystroke with the menu
            // open still ran the N+M queries ON MAIN (inside composition), and
            // `setSessionOverride` writes only the DB table — the `.skills` /
            // `.servers` flows never re-emitted, so an open menu went stale
            // after a toggle in SessionSkillsSheet/SessionMcpsSheet. Now:
            //  - the load runs in a `produceState` producer, with
            //    `withContext(Dispatchers.IO)` — the producer inherits the
            //    composition's context (Main), so the IO hop is explicit
            //    (1041-B3 lesson: produceState is NOT an IO context);
            //  - the two `sessionOverridesRevision` keys are the new
            //    invalidation channel: both repositories bump their revision
            //    on every override-table write, so an open menu refreshes
            //    after a toggle — no polling;
            //  - `filteredSlashCommands()` itself is batched (one `IN (…)`
            //    SELECT per repository — see ChatViewModelSlashExt), so the IO
            //    cost is a constant two queries per producer run.
            val slashMenuPayload: androidx.compose.runtime.State<SlashMenuPayload> = if (showSlashMenu) {
                produceState(
                    initialValue = SlashMenuPayload(),
                    viewModel.slashFilter.collectAsState().value,
                    viewModel.memoryEnabled.collectAsState().value,
                    viewModel.currentSessionId,
                    viewModel.skillRepository?.skills?.collectAsState()?.value,
                    viewModel.mcpRepository?.servers?.collectAsState()?.value,
                    viewModel.skillRepository?.sessionOverridesRevision?.collectAsState()?.value,
                    viewModel.mcpRepository?.sessionOverridesRevision?.collectAsState()?.value,
                ) {
                    withContext(Dispatchers.IO) {
                        value = SlashMenuPayload(
                            commands = viewModel.filteredSlashCommands(),
                            loaded = true,
                        )
                    }
                }
            } else {
                remember { mutableStateOf(SlashMenuPayload()) }
            }
            val filteredSlashCommands = slashMenuPayload.value.commands

            if (showSlashMenu && (filteredSlashCommands.isNotEmpty() || !slashMenuPayload.value.loaded)) {
                val effectiveThinkingLevelState by viewModel.thinkingLevel.collectAsState()
                // [T-thinking-effective-level] The two are DIFFERENT values and
                // must not be swapped. `effectiveThinkingLevelState` (above) is
                // what this turn actually sends; `requestedThinkingLevelState`
                // is the user's raw stored choice. The picker needs one for its
                // highlight and the other for its up-arrow — feeding the
                // effective value to BOTH silently kills the up-arrow (the
                // isCappedBy test becomes trivially false; it matched 15 of the
                // 64 (ceiling, choice) combinations before that wiring error and
                // 0 after, with no error, log or failing test).
                val requestedThinkingLevelState by viewModel.requestedThinkingLevel.collectAsState()
                val thinkingSupported = viewModel.currentModelSupportsReasoning
                val memoryOnState by viewModel.memoryEnabled.collectAsState()
                androidx.compose.ui.window.Popup(
                    popupPositionProvider = remember {
                        object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize,
                            ): androidx.compose.ui.unit.IntOffset {
                                // Anchor: top-edge of the composer column. Place
                                // the popup so its bottom sits 12dp above that edge.
                                // T301: bumped from 6dp — at 6dp the panel was
                                // visually glued to the composer; 12dp gives a
                                // clear breathing gap matching the iOS spacing.
                                val gap = 12
                                val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top - popupContentSize.height - gap)
                                    .coerceAtLeast(0)
                                return androidx.compose.ui.unit.IntOffset(x, y)
                            }
                        }
                    },
                    onDismissRequest = {
                        // [fix/dv19-b1] Commit the field's LIVE text: the debounced
                        // VM string can be empty (or a burst behind) inside the
                        // 150 ms window, and dismissSlashMenu() echoes that string
                        // back as the composer's value — clearing the dictation or
                        // paste the user just made. Drop the pending flush first so
                        // the stale snapshot cannot restore itself afterwards.
                        commitComposerBody(
                            viewModel.dismissSlashMenu(inputFieldValue.text),
                            null,
                        )
                    },
                    properties = androidx.compose.ui.window.PopupProperties(
                        focusable = false,
                        dismissOnBackPress = true,
                        // dismissOnClickOutside=false: with focusable=false the popup
                        // never receives focus, so the system "click outside" detector
                        // can't tell a tap on the BasicTextField below from a tap on
                        // the chat list — flipping this off would dismiss the popup
                        // every time the IME caret was moved. Dismiss is driven from
                        // the chat list / topbar tap-spy below instead, which lets
                        // the input field keep focus while still closing the menu
                        // when the user clearly looks elsewhere.
                        dismissOnClickOutside = false,
                    ),
                ) {
                    // [dv22-B4] First open of the menu: the producer has been
                    // launched but the IO round-trip has not landed yet —
                    // draw the same fixed frame with a single subdued row so
                    // the popup does not appear empty-then-jump. Once the
                    // first load lands, key changes keep the previous value
                    // (produceState holds it across relaunches), so typing
                    // never flashes this placeholder again.
                    if (!slashMenuPayload.value.loaded) {
                        SlashMenuLoadingPanel()
                        return@Popup
                    }
                    // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                    // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
                    // Short lists show empty space below the last row;
                    // long lists scroll inside the same frame with a
                    // visible scroll indicator. Prevents installed
                    // Skills + built-ins from pushing the menu past
                    // the input bar / off the top of the screen.
                    val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            // T240: keep a thin visible border instead of the
                            // diffuse 8dp halo that bled out past the panel edge.
                            .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
                            .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                    ) {
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = slashListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SLASH_PICKER_FIXED_HEIGHT)
                                .verticalScrollbar(slashListState),
                        ) {
                        itemsIndexed(filteredSlashCommands, key = { _, c -> c.id }) { index, cmd ->
                            // Section divider between builtins and the
                            // installed rows (skills + MCP servers —
                            // mirrors iOS divider at the first installed
                            // row). Drawn as the top of the row, not
                            // between every row — keeps the menu visually
                            // grouped without splitting every command.
                            // [fix/dv16-b] MCP rows live in the installed
                            // section too (they come after the skills), so
                            // the divider condition is the shared installed
                            // predicate — a bare isSkill test left the first
                            // MCP row visually merged into the skill group.
                            val isInstalledRow = cmd.isSkill || cmd.isMcp
                            val prevIsInstalledRow = index > 0 &&
                                (filteredSlashCommands[index - 1].isSkill ||
                                    filteredSlashCommands[index - 1].isMcp)
                            if (isInstalledRow && index > 0 && !prevIsInstalledRow) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = ChatColors.toolBorder,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                            val isThinking = cmd.id == "thinking"
                            val isThinkingActive = isThinking && effectiveThinkingLevelState.isEnabled && thinkingSupported
                            val titleColor = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                            val subtitleColor = if (isThinking && !thinkingSupported) {
                                ChatColors.secondaryText
                            } else if (isThinkingActive) {
                                ChatColors.sendButton.copy(alpha = 0.7f)
                            } else ChatColors.secondaryText
                            val iconTint = if (isThinkingActive) ChatColors.sendButton else ChatColors.primaryText
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .let {
                                        if (!isThinking) {
                                            it.clickable {
                                                // [T-android-slash-menu-clears-input] Pass the
                                                // LIVE input so an action command keeps the
                                                // user's body text instead of wiping it.
                                                // [fix/dv19-b1] "LIVE" now means the field's
                                                // own TextFieldValue: the VM string lags it by
                                                // the 150 ms burst window, and the pending
                                                // flush is dropped so it cannot re-commit a
                                                // snapshot over the post-command value.
                                                cancelPendingImeBurst()
                                                commitComposerBody(
                                                    viewModel.executeSlashCommand(cmd, inputFieldValue.text),
                                                    null,
                                                )
                                                // For the typing-aid rows
                                                // (skill + MCP), "/<name> "
                                                // is a typing aid — the user
                                                // still needs to type
                                                // arguments. Bring the IME
                                                // back up + grab focus so
                                                // they can keep typing
                                                // without an extra tap on
                                                // the composer.
                                                // [fix/dv16-b] MCP rows are
                                                // typing aids too — a bare
                                                // isSkill test dropped the
                                                // keyboard on an MCP tap.
                                                if (cmd.isSkill || cmd.isMcp) {
                                                    try {
                                                        inputFocusRequester.requestFocus()
                                                    } catch (_: IllegalStateException) {
                                                        // FocusRequester not attached yet.
                                                    }
                                                    keyboardController?.show()
                                                }
                                            }
                                        } else if (thinkingSupported) {
                                            it.clickable {
                                                val newLevel = if (effectiveThinkingLevelState.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
                                                viewModel.setThinkingLevel(newLevel)
                                            }
                                        } else it
                                    }
                                    // [T-android-slash-menu-density] Tighter
                                    // vertical padding (10→7) so slash rows
                                    // read as compact as iOS, not sparse.
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = cmd.icon,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "/${cmd.title.lowercase()}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = titleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // Cap to one line + ellipsis (mirrors
                                    // iOS T-slash-picker-product-rules
                                    // 051896e2). Long Skill descriptions
                                    // would otherwise stretch the row,
                                    // breaking the locked 4-row band and
                                    // crowding the menu visually.
                                    Text(
                                        text = cmd.subtitle,
                                        fontSize = 11.sp,
                                        color = subtitleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (cmd.id == "memory") {
                                    Icon(
                                        imageVector = if (memoryOnState) Icons.Default.CheckCircle else Icons.Default.Block,
                                        contentDescription = null,
                                        tint = if (memoryOnState) ChatColors.sendButton else ChatColors.secondaryText,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (isThinking && thinkingSupported) {
                                    ThinkingLevelPicker(
                                        // [T-thinking-effective-level] Two
                                        // distinct values: `current` drives the
                                        // highlight (= what's in force this
                                        // turn), `requested` drives the orange
                                        // up-arrow (= the user's raw choice
                                        // being capped). Passing the raw choice
                                        // for both is what made a capped level
                                        // render with nothing selected.
                                        current = viewModel.effectiveThinkingLevel,
                                        // [T-thinking-effective-level] The RAW
                                        // stored choice — NOT effectiveThinkingLevelState,
                                        // which is now the EFFECTIVE level (see
                                        // ChatViewModel.thinkingLevel). Feeding
                                        // the effective value here makes the
                                        // up-arrow's `isCappedBy` test
                                        // trivially false, silently deleting the
                                        // cue that explains "your Max is capped
                                        // at High by this model".
                                        requested = requestedThinkingLevelState,
                                        // [T-android-thinking-level-arch] Only
                                        // offer tiers the bound model supports.
                                        availableLevels = viewModel.availableThinkingLevels,
                                        onSelect = { level -> viewModel.setThinkingLevel(level) },
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }

            // T4: @ file-mention picker — same anchoring + tap-spy
            // contract as the slash popup (mutually exclusive in the VM,
            // so they never both render). Reuses Popup so the bar over
            // the composer is consistent and respects IME inset.
            val showMentionMenu by viewModel.showMentionMenu.collectAsState()
            val mentionEntries by viewModel.mentionEntries.collectAsState()
            val isMentionScanning by viewModel.isMentionScanning.collectAsState()
            val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsState()
            if (showMentionMenu) {
                androidx.compose.ui.window.Popup(
                    popupPositionProvider = remember {
                        object : androidx.compose.ui.window.PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: androidx.compose.ui.unit.IntRect,
                                windowSize: androidx.compose.ui.unit.IntSize,
                                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                popupContentSize: androidx.compose.ui.unit.IntSize,
                            ): androidx.compose.ui.unit.IntOffset {
                                val gap = 6
                                val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                val y = (anchorBounds.top - popupContentSize.height - gap)
                                    .coerceAtLeast(0)
                                return androidx.compose.ui.unit.IntOffset(x, y)
                            }
                        }
                    },
                    onDismissRequest = { viewModel.dismissMentionMenu() },
                    properties = androidx.compose.ui.window.PopupProperties(
                        focusable = false,
                        dismissOnBackPress = true,
                        // Same rationale as the slash popup: dismissOnClickOutside=false
                        // because the input field below the popup sits in the
                        // "outside" region (focusable=false → caret moves still
                        // count as outside). The chat-list tap-spy that drives
                        // dismissSlashMenu also dismisses this menu via
                        // dismissMentionMenu(); see the LazyColumn pointerInput.
                        dismissOnClickOutside = false,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                            .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                    ) {
                        if (mentionEntries.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isMentionScanning) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.5.dp,
                                        color = ChatColors.secondaryText,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = stringResource(
                                        if (isMentionScanning) R.string.mention_scanning
                                        else R.string.mention_no_match
                                    ),
                                    fontSize = 13.sp,
                                    color = ChatColors.secondaryText,
                                )
                            }
                        } else {
                            // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                            // Mention picker shares the slash picker's
                            // locked 192dp height (4 rows × 46dp + 8dp)
                            // so both popups have the same band on screen.
                            val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                            // Keep the highlighted row visible when the user
                            // navigates with a hardware keyboard. iOS gets this
                            // for free from SwiftUI's List/scrollTo binding;
                            // mimic it explicitly here.
                            LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                                val idx = mentionSelectedIndex
                                if (idx in mentionEntries.indices) {
                                    mentionListState.animateScrollToItem(idx)
                                }
                            }
                            LazyColumn(
                                state = mentionListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(SLASH_PICKER_FIXED_HEIGHT)
                                    .verticalScrollbar(mentionListState),
                            ) {
                                itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                    val isSelected = i == mentionSelectedIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                } else {
                                                    Color.Transparent
                                                },
                                            )
                                            .clickable {
                                                // [fix/dv23-b3] Drop the pending 150 ms burst FIRST. A
                                                // paste containing "@doc" is still buffered; the flush
                                                // would compare its snapshot against the completed text,
                                                // see a difference, and write the un-completed text back —
                                                // silently undoing the mention the user just picked.
                                                cancelPendingImeBurst()
                                                val (newText, newCaret) = viewModel.selectMention(
                                                    entry,
                                                    currentText = inputFieldValue.text,
                                                    currentCaret = inputFieldValue.selection.end,
                                                )
                                                // [fix/setinputtext-caret-intent] Explicit caret intent:
                                                // the funnel places the field's selection exactly here, so
                                                // the consuming LaunchedEffect does not have to race it.
                                                // [deep-audit-1049 B-06] …and it goes through the funnel so
                                                // an equal-to-VM write still lands on the field.
                                                commitComposerBody(newText, newCaret)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // Single doc icon for every entry — the scope/mount
                                        // capsule on the right already labels what bucket
                                        // this is (workspace / skills / shared / memory /
                                        // <mountName>). iOS varies the icon per scope but
                                        // we keep it uniform here so the row stays
                                        // visually consistent at small sizes on Pixel 4a.
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = null,
                                            tint = ChatColors.secondaryText,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.basename,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = ChatColors.primaryText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = entry.displayPath,
                                                fontSize = 11.sp,
                                                color = ChatColors.secondaryText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        // Scope / mount badge — matches iOS capsule.
                                        Text(
                                            text = entry.mountName ?: entry.scope.displayLabel,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.secondaryText,
                                            modifier = Modifier
                                                .background(
                                                    ChatColors.toolCapsuleBg,
                                                    RoundedCornerShape(8.dp),
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Input box: iOS-style floating card — no visible border, separated
            // from the backdrop by a symmetric soft shadow painted by hand
            // (Android's Modifier.shadow only casts downward).
            val inputBgArgb = ChatColors.inputBg.toArgb()
            val shadowPaint = remember(inputBgArgb) {
                android.graphics.Paint().apply {
                    color = inputBgArgb
                    isAntiAlias = true
                }
            }
            // T185: Move-to-session capsule mirrors iOS
            // AIChatView.swift:1816 (.overlay(alignment: .topTrailing))
            // on the input card. We render it as the first child of the
            // composer Column, right-aligned, so it visually sits inside
            // the input card's top-right corner — Compose doesn't have a
            // free overlay primitive that doesn't need a Box wrapper,
            // and an in-flow Row at the top with Arrangement.End is the
            // cleanest equivalent.
            val showMoveCapsule by viewModel.hasInjectedShareContent.collectAsState()
            // Mirrors iOS swipe-up-to-send: drag the input bar upward —
            // if it holds text, a floating send-arrow + "Release to send"
            // capsule track the finger; releasing past `swipeArmFraction`
            // sends. With empty text + collapsed keyboard, releasing
            // activates the keyboard instead. Box wraps the existing
            // composer Column so the gesture + overlay live in the same
            // coordinate space without disturbing the bar's own layout.
            Box(modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var totalDx = 0f
                        var totalDy = 0f
                        var claimed = false
                        var lastPos = down.position
                        verticalDrag(down.id) { change ->
                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            lastPos = change.position
                            if (!claimed) {
                                // Wait until a clearly vertical drag of
                                // at least `slop` px before claiming.
                                // Below that the TextField / list still
                                // get the events (taps, text scroll, …).
                                if (kotlin.math.abs(totalDy) < slop) return@verticalDrag
                                if (kotlin.math.abs(totalDy) <= kotlin.math.abs(totalDx)) return@verticalDrag
                                claimed = true
                            }
                            change.consume()
                            if (totalDy < 0) {
                                // Swiping up. Show the hint only when there is
                                // something to send (text or attachments);
                                // otherwise keep the overlay hidden and defer
                                // keyboard activation to onEnd.
                                if (canSwipeSendNow()) {
                                    val newProgress = (-totalDy / swipeThresholdPxState.value).coerceIn(0f, 1f)
                                    if (newProgress >= swipeArmFraction && sendSwipeProgress < swipeArmFraction) {
                                        swipeHaptics.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                        )
                                    }
                                    sendSwipeProgress = newProgress
                                    sendSwipeLocation = lastPos
                                } else if (sendSwipeProgress != 0f) {
                                    sendSwipeProgress = 0f
                                }
                            } else if (sendSwipeProgress != 0f) {
                                // Reversed direction; clear any hint.
                                sendSwipeProgress = 0f
                            }
                        }
                        // Drag ended (finger up or pointer cancel). One shared
                        // eligibility predicate: swiped up with something to
                        // send → send (or enqueue during streaming); swiped up
                        // with NOTHING to send and no focus → pop the keyboard.
                        val swipedUp = claimed && totalDy < 0 &&
                            kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                        if (swipedUp && canSwipeSendNow()) {
                            if (sendSwipeProgress >= swipeArmFraction) {
                                // T-drag-send-queue: route through the
                                // shared send-or-enqueue handler so a
                                // drag-to-send during streaming enqueues
                                // the prompt instead of being dropped —
                                // matches the send-button tap path which
                                // already enqueues mid-stream via
                                // viewModel.sendMessage → enqueuePrompt.
                                performSendOrEnqueue()
                            }
                            sendSwipeProgress = 0f
                        } else if (swipedUp && !canSwipeSendNow() && !inputFocused) {
                            // Empty input + collapsed keyboard -> bring
                            // up the keyboard. If the keyboard is
                            // already open, do nothing so a stray drag
                            // doesn't re-trigger anything.
                            inputFocusRequester.requestFocus()
                            keyboardController?.show()
                            sendSwipeProgress = 0f
                        } else {
                            sendSwipeProgress = 0f
                        }
                    }
                },
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val radiusPx = 20.dp.toPx()
                        val canvas = drawContext.canvas.nativeCanvas
                        // Pass 1: symmetric ambient halo — small blur, low alpha.
                        shadowPaint.setShadowLayer(
                            6.dp.toPx(), 0f, 0f,
                            android.graphics.Color.argb(22, 0, 0, 0),
                        )
                        canvas.drawRoundRect(
                            0f, 0f, size.width, size.height,
                            radiusPx, radiusPx,
                            shadowPaint,
                        )
                        // Pass 2: soft downward shadow (spot light).
                        shadowPaint.setShadowLayer(
                            10.dp.toPx(), 0f, 3.dp.toPx(),
                            android.graphics.Color.argb(24, 0, 0, 0),
                        )
                        canvas.drawRoundRect(
                            0f, 0f, size.width, size.height,
                            radiusPx, radiusPx,
                            shadowPaint,
                        )
                    }
                    .padding(top = if (attachments.isNotEmpty()) 8.dp else 4.dp),
            ) {
                // T185: Move-to capsule lives INSIDE the composer card,
                // pinned 8dp from the top-right corner, mirroring iOS
                // AIChatView.swift:1816 (.overlay(alignment: .topTrailing)
                // padding(.top, 6).padding(.trailing, 10)). A Popup
                // keeps it out of the composer's layout flow so the
                // attachment row + text field still own the full
                // vertical rhythm.
                if (showMoveCapsule) {
                    // T185: align Move-to right edge with the
                    // attachment row + button row (both 12dp). The
                    // anchorBounds rect is in px, so convert via
                    // LocalDensity rather than treating the constant
                    // as dp directly.
                    val popupDensity = androidx.compose.ui.platform.LocalDensity.current
                    val rightInsetPx = with(popupDensity) { 12.dp.roundToPx() }
                    val topInsetPx = with(popupDensity) { 6.dp.roundToPx() }
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember(rightInsetPx, topInsetPx) {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = {},
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            // Mirrors iOS .ultraThinMaterial — solid-
                            // looking pill against the input bg.
                            // Without a hairline border the capsule
                            // washed out into the input card on the
                            // light theme, which is why it stopped
                            // reading as a pill.
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 0.dp,
                            tonalElevation = 0.dp,
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                ChatColors.thumbnailBorder,
                            ),
                            modifier = Modifier.clickable { showMoveSheet = true },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                // arrow.right.circle look-alike: an
                                // outlined ring around a → glyph.
                                Box(
                                    modifier = Modifier
                                        .size(15.dp)
                                        .border(
                                            1.dp,
                                            ChatColors.secondaryText,
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = ChatColors.secondaryText,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Move to…",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = ChatColors.secondaryText,
                                )
                            }
                        }
                    }
                }
                // Folded long pastes and attachment thumbnails share ONE row
                // (iOS: 64×64 squares): both are content held outside the field
                // and both use the same chip geometry, so two rows would read as
                // two different kinds of thing.
                // [feat/paste-fold-marker] Two kinds of row live in this list:
                //  - inline: listed only while its `[Pasted#N]` literal is still
                //    in the field — the literal is the reference, so a sent or
                //    cleared composer drops the chip with no extra bookkeeping.
                //  - detached: pasted into an EMPTY field, so there is no
                //    literal at all; it stays listed until the user removes it
                //    or sends (send appends it after the body). Same lifetime
                //    as an attachment chip, which also survives a cleared text.
                val visiblePastes = pastedTexts.filter {
                    !it.inlineMarker || inputFieldValue.text.contains(it.placeholder)
                }
                if (attachments.isNotEmpty() || visiblePastes.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            // T185: 12dp horizontal so the row's left
                            // edge lines up with the +/slash button
                            // column and the typed text below.
                            .padding(horizontal = 12.dp),
                        // The chip itself now bakes in 8dp of trailing
                        // visual room for the remove badge that spills
                        // past the top-right; no extra spacedBy needed.
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        // String keys keep pastes and attachments in one key
                        // space without relying on the attachment id type.
                        items(visiblePastes, key = { "paste-${it.id}" }) { pasted ->
                            PastedTextChip(
                                pasted = pasted,
                                onRemove = {
                                    // Drop the buffer row and strip its marker
                                    // in the SAME edit (through the composer
                                    // funnel, which also kills a pending IME
                                    // burst so the snapshot cannot re-add the
                                    // literal). Exact-literal strip: removing
                                    // #2 cannot disturb #20.
                                    viewModel.removePastedText(pasted.id)
                                    commitComposerBody(
                                        removePasteMarker(inputFieldValue.text, pasted.id),
                                        null,
                                    )
                                },
                            )
                        }
                        items(attachments, key = { it.id }) { attachment ->
                            // T-pwa-2: long-press menu only appears for
                            // .html / .htm attachments. The menu lives in
                            // a Box that anchors to the chip; the sheet
                            // itself is hosted at screen level (see
                            // webAppSheetTarget).
                            val isHtmlAttachment = attachment.fileName
                                .substringAfterLast('.', "")
                                .lowercase()
                                .let { it == "html" || it == "htm" }
                            var webAppMenuExpanded by remember(attachment.id) { mutableStateOf(false) }
                            Box {
                            AttachmentChip(
                                attachment = attachment,
                                onRemove = { viewModel.removeAttachment(attachment.id) },
                                // Intentionally hidden (WebApp entry point).
                                onLongClick = if (false && isHtmlAttachment) {
                                    { webAppMenuExpanded = true }
                                } else null,
                                onClick = {
                                    // Mirror iOS InputAttachmentTile
                                    // (AIChatView.swift:3699) which
                                    // .sheet's an AttachmentPreviewView
                                    // routed by file type. Images go
                                    // through the in-app fullscreen
                                    // viewer; non-image files take the
                                    // in-app FilePreviewScreen when we
                                    // hold a host file path, falling
                                    // back to the system viewer for
                                    // foreign content:// URIs.
                                    if (attachment.isImage) {
                                        // Collect every image chip in
                                        // the composer row so the user
                                        // can swipe through them.
                                        val imageChips = attachments.filter { it.isImage }
                                        val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                            .coerceAtLeast(0)
                                        onPreviewImageGallery(
                                            imageChips.map { ic ->
                                                com.rikkaminis.app.ui.components.ImageGalleryItem(
                                                    model = ic.uri,
                                                    caption = ic.fileName,
                                                )
                                            },
                                            startIdx,
                                        )
                                    } else {
                                        // T162: shares funnel through
                                        // addAttachmentFromStagedShare,
                                        // which copies the bytes into
                                        // cacheDir/share_inbound/<uuid>-
                                        // <name> and returns a
                                        // Uri.fromFile() URI. Handing
                                        // that file:// URI directly to
                                        // Intent.ACTION_VIEW raises
                                        // FileUriExposedException on
                                        // API 24+ and crashed the app
                                        // on the user's first chip tap.
                                        // Route file:// chips into the
                                        // in-app FilePreviewScreen via
                                        // the host onPreviewAttachment
                                        // callback (same path the user-
                                        // bubble chip uses); leave
                                        // content:// chips on the
                                        // system viewer because we
                                        // don't have a host path for
                                        // those.
                                        val uri = attachment.uri
                                        val asFile = if (uri.scheme == "file") {
                                            uri.path?.let { java.io.File(it) }
                                        } else null
                                        if (asFile != null && asFile.exists()) {
                                            onPreviewAttachment(
                                                com.rikkaminis.app.ui.sandbox.FileItem(
                                                    file = asFile,
                                                    name = attachment.fileName,
                                                    isDirectory = false,
                                                    isSymlink = false,
                                                    size = asFile.length(),
                                                    modifiedMs = asFile.lastModified(),
                                                )
                                            )
                                        } else {
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                            ).apply {
                                                setDataAndType(uri, attachment.mimeType)
                                                addFlags(
                                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                                )
                                            }
                                            // [deep-audit-1049 B-03] Shared launcher instead of a
                                            // bare startActivity: the intent carries no
                                            // NEW_TASK (a chooser would not have inherited one
                                            // either), and the failure toast used to be a
                                            // hard-coded English sentence.
                                            ExternalIntent.start(context, intent) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.filepreview_no_app),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                            )
                            // Intentionally hidden (WebApp entry point).
                            if (false && isHtmlAttachment) {
                                com.rikkaminis.app.ui.components.MinisMenu(
                                    expanded = webAppMenuExpanded,
                                    onDismissRequest = { webAppMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.webapp_add_to_home)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.AppShortcut,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            webAppMenuExpanded = false
                                            onOpenWebAppSheet(attachment)
                                        },
                                    )
                                }
                            }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // [feat/paste-fold-marker] Fail-closed notice: a restored draft
                // can hold a `[Pasted#N]` whose text died with the process.
                // Sending is disabled (button + Enter + swipe) and this says
                // why, quoting the exact literal so the user can find it.
                PasteSendBlockedNotice(markers = viewModel.blockingPasteMarkers(inputFieldValue.text))

                // Composer input field. (Voice input mode was removed
                // with the mic button; the field is now the only path.)
                run {
                    val interactionSource = remember { MutableInteractionSource() }
                    val mergedTextStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.5.sp * chatInputFontScale,
                        lineHeight = 20.sp * chatInputFontScale,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // [T-android-enter-to-send-broken] Live read of the
                    // "Return key sends" preference. Bound here (not
                    // captured at BasicTextField construction) so a
                    // toggle in Settings reflects on the next IME
                    // commit without recomposing the chat tree.
                    val sendOnEnter = com.rikkaminis.app.ui.settings
                        .returnKeySendsMessage(context)
                    // Shared "Enter pressed → send" body used by BOTH
                    // the hardware-keyboard onKeyEvent path AND the
                    // soft-keyboard KeyboardActions.onSend below.
                    // Pre-fix only the onKeyEvent path existed and
                    // most soft IMEs (Gboard, Sogou, MIUI) never
                    // route an Enter through onKeyEvent under
                    // ImeAction.Default — they just inserted a '\n'
                    // and the preference appeared not to work. We
                    // now flip imeAction to Send when the toggle is
                    // on, so the IME shows the send icon AND fires
                    // onSend; this lambda is the single source of
                    // truth for what "press Enter to send" means.
                    val performEnterSend: () -> Boolean = handler@{
                        // [feat/paste-fold-marker] Same fail-closed gate as
                        // performSendOrEnqueue: the Enter key is the one send
                        // path that can fire while the button is disabled.
                        if (pasteSendBlockedNow()) return@handler false
                        // [fix/ttfb-thinktag-composer] Same contract as
                        // performSendOrEnqueue (see the full history there):
                        // cancel the pending burst flush so it cannot
                        // re-inject text after the send cleared the composer
                        // (ghost input → double send), and take the send text
                        // from the field's live value — never the snapshot.
                        cancelPendingImeBurst()
                        val effectiveInput = inputFieldValue.text
                        if (effectiveInput.isBlank() && attachments.isEmpty()) return@handler false
                        // Intercept slash commands so "/compact" et al.
                        // run locally instead of being sent as a chat
                        // turn. Mirrors iOS performSend().
                        if (viewModel.tryExecuteInputAsSlashCommand(effectiveInput)) {
                            clearComposerInput()
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            return@handler true
                        }
                        // T160: snapshot → clear state + IME →
                        // sendMessage. Same ordering as the send-
                        // button click; finishComposingText fires
                        // when focus drops so any IME composing
                        // buffer is committed/dropped before the
                        // empty inputText becomes visible.
                        val toSend = effectiveInput
                        lastSendTimeMs = SystemClock.elapsedRealtime()
                        // [P2-scroll-user-send] snapshot BEFORE sendMessage
                        // (insert pushes index 0 → 1; must not read after).
                        val wasScrolledIntoHistory = !isNearBottom.value
                        clearComposerInput()
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        viewModel.sendMessage(toSend)
                        noteSendForInputModePref()
                        // [bottom-trigger] Re-engage follow only if the
                        // reader was anchored at the bottom (P2: must not
                        // turn a history reader into a follower).
                        // [forward-stable] Exactly one pending request.
                        if (!wasScrolledIntoHistory) {
                            onFollowEvent(FollowEvent.Send)
                        } else {
                            AppLogger.debug("USER-SEND", "ime-action reader-in-history, skip yank")
                        }
                        true
                    }
                    BasicTextField(
                        value = inputFieldValue,
                        onValueChange = { tfv ->
                            // T217-2: drop IME residue commits after send.
                            // clearFocus triggers finishComposingText, which makes
                            // voice/Pinyin IMEs replay their composing candidate
                            // through onValueChange after we cleared inputText.
                            // Residue always carries a non-null composition region;
                            // normal user typing arrives with composition==null,
                            // so we only gate the post-send window when the IME
                            // is mid-composition — fast typing right after a send
                            // is never dropped.
                            val now = SystemClock.elapsedRealtime()
                            if (tfv.composition != null && now - lastSendTimeMs < 500L && tfv.text.isNotEmpty()) {
                                return@BasicTextField
                            }
                            // [feat/paste-fold-marker] Fold a long paste into a
                            // `[Pasted#N]` marker BEFORE anything downstream
                            // reads the edit. Two properties depend on this
                            // exact position:
                            //
                            //  1. The burst gate below fires on the INSERTED
                            //     length. After folding, a 40 KB paste looks
                            //     like a 10-char insertion, so it commits
                            //     immediately instead of being held for 150 ms
                            //     — and, crucially, nothing downstream ever
                            //     holds the raw block, so the burst flush can
                            //     never write it back over the marker.
                            //  2. Every caret/text mirror below
                            //     (`previousFieldText`, `imeBurstBuffer`,
                            //     `imeBurstCaret`, `lastTrueCaretEnd`,
                            //     `publishPendingComposerText`) is computed from
                            //     the FOLDED value. One of them reading the raw
                            //     text is exactly how the marker would get
                            //     overwritten ~150 ms later.
                            //
                            // `old` is the field's own TextFieldValue, which
                            // onValueChange writes on every path, so it is never
                            // staler than the ViewModel copy.
                            run {
                                // The shadowing binding: from here down `tfv` IS the
                                // folded edit, so every mirror below (previousFieldText,
                                // imeBurstBuffer, imeBurstCaret, lastTrueCaretEnd,
                                // publishPendingComposerText) reads the marker, never the
                                // raw block. The initializer still sees the outer `tfv`
                                // (the raw edit) because a local is in scope from its
                                // declaration point onward.
                                val tfv = foldLongPasteIfNeeded(
                                    old = inputFieldValue,
                                    new = tfv,
                                    stash = { pasted, inlineMarker ->
                                        viewModel.stashPastedText(pasted, inlineMarker)
                                    },
                                )
                                // [T-android-enter-to-send-multiline] Root cause:
                                // the composer is a multi-line BasicTextField
                                // (maxLines=6 ⇒ EditorInfo carries
                                // TYPE_TEXT_FLAG_MULTI_LINE, confirmed inputType
                                // 0x28001 in dumpsys input_method). In multi-line
                                // mode soft IMEs (Gboard/LatinIME, Sogou, MIUI)
                                // render Enter as a newline and IGNORE
                                // IME_ACTION_SEND — so KeyboardActions.onSend
                                // never fires and the "Return key sends" pref
                                // looked inert. The IME commits the Enter as a
                                // plain '\n' through onValueChange (not through
                                // onKeyEvent / a KEYCODE_ENTER), so the only
                                // place to catch it for soft keyboards is here.
                                //
                                // Detect a single '\n' freshly inserted into the
                                // text (one Enter keypress) and convert it to a
                                // send. Guarded to a single added newline so a
                                // paste containing newlines is NOT swallowed —
                                // those increase the count by >1 and fall through
                                // to the normal multi-line edit. Hardware
                                // Shift+Enter is the exception: `onKeyEvent` below
                                // deliberately lets it through so Compose inserts a
                                // newline, and that insertion is byte-identical to
                                // the soft-IME shape caught here — so the key
                                // handler flags it and this block stands down
                                // ([meta-audit-1028 C3/F-03]; before, Shift+Enter
                                // sent the message instead of breaking the line).
                                // A single pasted '\n' is still indistinguishable
                                // from a soft Enter and still sends (see report §6).
                                val previousFieldText = inputFieldValue.text
                                val hardwareShiftEnterNewline = shiftEnterNewlinePending
                                // Consume unconditionally: one key event, at most
                                // one insertion. A flag that outlived its insertion
                                // would swallow the next genuine soft Enter.
                                shiftEnterNewlinePending = false
                                if (sendOnEnter && !showMentionMenu && !hardwareShiftEnterNewline &&
                                    isEnterSendEdit(
                                        oldText = previousFieldText,
                                        newText = tfv.text,
                                        caretEnd = tfv.selection.end,
                                    )
                                ) {
                                    performEnterSend()
                                    return@BasicTextField
                                }
                                inputFieldValue = tfv
                                // [Txxx-android-composer-caret] The IME's
                                // tfv.selection.end is the authoritative user
                                // caret right now. Persist it so a later
                                // LaunchedEffect can fall back to it instead of
                                // an already-stale inputFieldValue.selection.
                                lastTrueCaretEnd = tfv.selection.end.coerceAtLeast(0)
                                // [meta-audit-1028 C3/F-05] `|| imeBurstBuffer != null`:
                                // while a burst is buffered the VM string is stale,
                                // so "text equals the VM copy" is NOT "nothing
                                // changed" — the user may have just cut / undone the
                                // paste back to that very string, and this branch is
                                // the only place that drops the buffered snapshot
                                // (and the ViewModel's pending copy) on the way out.
                                if (inputText != tfv.text || imeBurstBuffer != null) {
                                    // [fix/voice-crash-observability tail] IME
                                    // voice dictation drives onValueChange with
                                    // high-frequency, large text bursts. Each
                                    // burst used to call setInputText
                                    // immediately, re-serializing the draft +
                                    // recomputing slash/mention state + running
                                    // the full ledger reconcile on the main
                                    // thread. A single large increment (>8
                                    // chars — see shouldDebounceImeBurst) is
                                    // debounced: buffer locally and flush once
                                    // 150 ms after the last burst, so a burst
                                    // of N events collapses to one commit.
                                    // Ordinary typing (<=8 chars) and deletes
                                    // go through immediately — instant feedback
                                    // is preserved.
                                    //
                                    // [meta-audit-1028 C3/F-05] The delta is
                                    // measured against the PREVIOUS FIELD TEXT, not
                                    // `inputText`. The field is the authority and
                                    // is never more than one edit behind; the VM
                                    // string can be a whole burst behind, so after
                                    // a debounced paste every single-char edit kept
                                    // seeing `new - stale > 8` and was debounced
                                    // again — one 150 ms stall per keystroke.
                                    if (shouldDebounceImeBurst(previousFieldText, tfv.text)) {
                                        imeBurstBuffer = tfv.text
                                        imeBurstCaret = tfv.selection.end
                                        // [dv25-B08] Publish the pending snapshot to the
                                        // ViewModel: the field's live text is the newest
                                        // copy of what the user typed, and for 150 ms it
                                        // exists ONLY here. `promoteDraftIfNeeded` (the
                                        // "New Chat" path) reads it so a dictated tail is
                                        // not promoted as the pre-burst string.
                                        viewModel.publishPendingComposerText(tfv.text)
                                        imeBurstJob?.cancel()
                                        imeBurstJob = coroutineScope.launch {
                                            delay(150)
                                            val flushed = imeBurstBuffer
                                            // [fix/ttfb-thinktag-composer] Always
                                            // clear the buffer so a stale snapshot
                                            // can never outlive its flush (the old
                                            // code left it set, and the send path
                                            // then preferred the stale snapshot
                                            // over newer edits). Commit only while
                                            // the snapshot still matches the
                                            // field's live text — if newer edits
                                            // landed during the window (they
                                            // commit immediately), committing the
                                            // snapshot would overwrite them.
                                            imeBurstJob = null
                                            imeBurstBuffer = null
                                            // [dv25-B08] The pending snapshot is spent:
                                            // whatever the field commits next is the
                                            // authoritative text, so the ViewModel's
                                            // pending copy must not outlive its flush
                                            // (the `fix/ttfb-thinktag-composer` lesson).
                                            viewModel.publishPendingComposerText(null)
                                            if (flushed != null &&
                                                shouldCommitImeBurst(flushed, inputText, inputFieldValue.text)
                                            ) {
                                                viewModel.setInputText(flushed)
                                                viewModel.updateSlashMenuState(flushed)
                                                // [fix/voice-ime-mention-scan] Sweep the
                                                // @-mention scan into the same flush as the
                                                // slash state. A voice/Pinyin burst arrives as
                                                // dozens of large, space-less CJK edits; the old
                                                // unconditional mention scan below was walking back
                                                // from the caret to the text head (O(n)) on EVERY
                                                // one of them, re-typing unchanged menu state.
                                                // Folding it into the 150 ms flush keeps the scan
                                                // semantics identical while collapsing N scans to 1.
                                                viewModel.updateMentionMenuState(
                                                    text = flushed,
                                                    caret = imeBurstCaret.coerceIn(0, flushed.length),
                                                )
                                            }
                                        }
                                    } else {
                                        // [meta-audit-1028 C3/F-05] A concrete edit
                                        // supersedes any buffered burst: its
                                        // snapshot is older than what the user just
                                        // typed, and its 150 ms flush would either
                                        // be rejected later or (worse) publish a
                                        // zombie pending copy that `New Chat` reads.
                                        cancelPendingImeBurst()
                                        viewModel.setInputText(tfv.text)
                                        // [dv25-B08] No burst pending any more — the
                                        // committed string IS the newest copy.
                                        viewModel.publishPendingComposerText(null)
                                        viewModel.updateSlashMenuState(tfv.text)
                                        viewModel.updateMentionMenuState(
                                            text = tfv.text,
                                            caret = tfv.selection.end,
                                        )
                                    }
                                } else {
                                    // [meta-audit-1028 C3/F-05] Reached only with no
                                    // burst buffered (see the guard above), so the
                                    // cancel is a no-op there — kept as the funnel's
                                    // terminal state assertion: "no edit path exits
                                    // with a live burst job or a pending VM copy".
                                    cancelPendingImeBurst()
                                    // Text unchanged, caret may have moved: the @-mention
                                    // token/filter must still re-scan on a pure selection change
                                    // (the only case that doesn't go through the branches above).
                                    viewModel.updateMentionMenuState(
                                        text = tfv.text,
                                        caret = tfv.selection.end,
                                    )
                                }
                                                    }
},
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 25.dp)
                            .focusRequester(inputFocusRequester)
                            .onFocusChanged { inputFocused = it.isFocused }
                            .onKeyEvent { event ->
                                // T-at-filepicker-keyboard: while the @-mention
                                // menu is open, hardware Up/Down navigates the
                                // list and Return commits the highlighted entry.
                                // Falls through to the normal Return-send path
                                // when there are no mention candidates so the
                                // user isn't stuck if the menu is empty.
                                if (showMentionMenu && event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            viewModel.mentionMenuUp()
                                            return@onKeyEvent true
                                        }
                                        Key.DirectionDown -> {
                                            viewModel.mentionMenuDown()
                                            return@onKeyEvent true
                                        }
                                        Key.Enter -> {
                                            val result = viewModel.executeSelectedMention(
                                                currentText = inputFieldValue.text,
                                                currentCaret = inputFieldValue.selection.end,
                                            )
                                            if (result != null) {
                                                // [fix/dv23-b3] Same as the tap path: a pending burst
                                                // flush would overwrite the completed mention with the
                                                // pre-completion snapshot.
                                                cancelPendingImeBurst()
                                                val (newText, newCaret) = result
                                                // [fix/setinputtext-caret-intent] Explicit caret intent,
                                                // same as the tap path — single writer is the funnel
                                                // ([deep-audit-1049 B-06]).
                                                commitComposerBody(newText, newCaret)
                                                return@onKeyEvent true
                                            }
                                            // Menu open but no candidates → fall
                                            // through to Return-send / newline.
                                        }
                                        Key.Escape -> {
                                            viewModel.dismissMentionMenu()
                                            return@onKeyEvent true
                                        }
                                        else -> Unit
                                    }
                                }
                                // Return-key behavior is user-configurable
                                // (Appearance → Return Key, default Newline =
                                // iOS shipping default). Shift+Enter always
                                // inserts a newline regardless of the setting,
                                // mirroring iOS hardware-keyboard semantics.
                                // [T-android-enter-to-send-broken] Hardware-
                                // keyboard path. Soft IME route goes through
                                // KeyboardActions.onSend below.
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    !event.isShiftPressed &&
                                    sendOnEnter
                                ) {
                                    performEnterSend()
                                } else {
                                    // [meta-audit-1028 C3/F-03] Shift+Enter is
                                    // "newline", not "send", whatever the
                                    // Return-Key preference says. Returning
                                    // false lets Compose insert the `\n`, and
                                    // the flag tells the soft-IME intercept in
                                    // `onValueChange` that this newline is the
                                    // user's line break, not an IME Enter.
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Enter &&
                                        event.isShiftPressed
                                    ) {
                                        shiftEnterNewlinePending = true
                                    }
                                    false
                                }
                            },
                        textStyle = mergedTextStyle,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = inputMaxLines,
                        // [T-android-enter-to-send-broken] When the
                        // user has Return-Key=Send turned on, ask the
                        // IME for the Send action so it (a) shows the
                        // send glyph instead of "Enter" and (b)
                        // actually invokes KeyboardActions.onSend
                        // instead of silently inserting '\n'. With
                        // Default, Gboard / Sogou / MIUI etc. never
                        // routed Enter through onKeyEvent so the
                        // preference appeared inert.
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { performEnterSend() },
                        ),
                        interactionSource = interactionSource,
                        decorationBox = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = inputText,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = false,
                                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                                interactionSource = interactionSource,
                                // Placeholder removed per user request: the
                                // "Message <SoulName>" hint was dropped so
                                // the composer starts visually empty. No
                                // placeholder param = no hint text; the "@
                                // to mention files" affordance is still
                                // discoverable by typing "@".
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                // T7: vertical 10dp → 7dp (≈ −15%) to slim the
                                // chat composer. Settings TextFields keep
                                // Material3 default padding — those are
                                // 1-shot config inputs, not the daily-
                                // friction surface the user wants tightened.
                                // T185: 12dp horizontal lines the
                                // typed text up with the +/slash and
                                // mic/send icon-button row below
                                // (Modifier.padding(horizontal = 12.dp)
                                // there) and the attachment chip row
                                // (also 12dp). 16dp left an unaligned
                                // jog where the text started further
                                // right than every other composer
                                // element.
                                contentPadding = PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 7.dp,
                                ),
                            )
                        },
                    )
                }

                // Button row below text field (iOS layout: + / ... mic send)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // T185: 12dp horizontal lines the +/slash and
                        // mic/send icon-button column up with the
                        // attachment row + textfield + Move-to popup.
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Circular model-picker button — matches the + and send
                    // button style for a unified look. The model name and
                    // resolved-status dot live in the nav-bar subtitle;
                    // this is just a compact trigger that can't be
                    // mistaken for the text field (no long text label).
                    //
                    // [T-composer-model-picker-hide] Hideable via Settings
                    // → Appearance → Chat Menu → "Model picker button": the
                    // nav-bar subtitle (model name + status dot) remains
                    // tappable and opens the same picker, so hiding this
                    // button only removes the redundant in-composer trigger.
                    if (chatActions.composerModelPickerVisible) {
                        InputCircleButton(onClick = { onOpenModelPicker() }) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.model_picker_default_badge),
                                tint = ChatColors.secondaryText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    // The "/" slash-command circle button used to sit here.
                    // Moved into the top-right chat menu (above Token Usage)
                    // to reclaim composer width; typing "/" still opens the
                    // same sheet, so no functionality was removed.

                    // T187: Exit Edit Mode pill, only while editingMessageId
                    // is non-null. Tap clears the edit flag + composer text
                    // without truncating history. iOS parity:
                    // AIChatView.swift L1586 editExitButton.
                    val editingId by viewModel.editingMessageId.collectAsState()
                    if (editingId != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ChatColors.inputBg,
                            modifier = Modifier.clickable {
                                viewModel.cancelEdit()
                                clearComposerInput()
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.chat_edit_exit_button),
                                style = MaterialTheme.typography.labelMedium,
                                color = ChatColors.secondaryText,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Attach (+): moved from the left edge to sit
                    // directly beside mic/send. The three actions
                    // here (photos, file, camera) all produce
                    // something that gets SENT, so grouping them
                    // with the send affordance keeps the
                    // "compose -> attach -> send" gesture inside one
                    // thumb arc instead of spanning the full width.
                    //
                    // [T-attach-menu-customizable] The "+" menu mirrors
                    // the top-right "..." menu pattern: the three attach
                    // actions are individually hideable and reorderable
                    // from Settings → Appearance → Chat Menu → Attach.
                    // Rendering follows chatActions.visibleAttachOrder
                    // (user order filtered by visibility) and converges:
                    //   • 2+ visible → "+" opens a menu of exactly those;
                    //   • exactly 1 visible → promoted to a direct
                    //     InputCircleButton (one tap instead of two,
                    //     mirroring the "..." menu's soloCustomKey);
                    //   • 0 visible → no "+" renders at all (it would
                    //     open an empty menu — a dead control).
                    // Taps funnel through launchAttach() so the promoted
                    // button and the menu share one implementation.
                    val attachKeys = chatActions.visibleAttachOrder
                    fun launchAttach(key: String) {
                        when (key) {
                            AttachActionCatalog.CHOOSE_PHOTOS ->
                                onPickMedia()
                            AttachActionCatalog.ADD_FILE ->
                                // OpenMultipleDocuments takes a mime-
                                // type array; "*/*" stays the wildcard.
                                onPickFile()
                            AttachActionCatalog.TAKE_PHOTO -> {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    onLaunchCamera()
                                } else {
                                    onLaunchCameraPermission()
                                }
                            }
                        }
                    }
                    // Solo attach key → direct button (no "+" menu).
                    val soloAttachKey = attachKeys.singleOrNull()
                    if (attachKeys.isNotEmpty()) {
                        Box {
                            InputCircleButton(
                                onClick = {
                                    if (soloAttachKey != null) {
                                        launchAttach(soloAttachKey)
                                    } else {
                                        showAttachMenu = true
                                    }
                                },
                            ) {
                                if (soloAttachKey != null) {
                                    val soloSpec = AttachActionCatalog.spec(soloAttachKey)
                                    Icon(
                                        soloSpec?.icon ?: Icons.Default.Add,
                                        contentDescription = soloSpec?.let {
                                            stringResource(it.titleRes)
                                        } ?: "Attach",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Attach",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (soloAttachKey == null) {
                                MinisMenu(
                                    expanded = showAttachMenu,
                                    onDismissRequest = { showAttachMenu = false },
                                ) {
                                    // Orders differ per user; the default is
                                    // Choose Photos & Videos / Add File / Take
                                    // Photo. Picking existing media is by far
                                    // the most frequent attach action (first
                                    // slot, closest to the thumb), while Take
                                    // Photo is the rarest and also the only
                                    // destructive-ish one (opens the camera,
                                    // can lose the draft on some OEM camera
                                    // apps), so it sits last where it can't be
                                    // hit by accident — unless the user
                                    // reorders it in Chat Menu settings.
                                    for (entryKey in attachKeys) {
                                        val spec = AttachActionCatalog.spec(entryKey) ?: continue
                                        key(entryKey) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(spec.titleRes)) },
                                                leadingIcon = {
                                                    Icon(spec.icon, contentDescription = null)
                                                },
                                                onClick = {
                                                    showAttachMenu = false
                                                    launchAttach(entryKey)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Voice input (mic button, language pill, inline
                    // voice panel, read-replies TTS) removed here.

                    // Right: 3-state Send / Enqueue / Stop button (mirrors iOS sendButton).
                    //   • streaming + content → SEND (routes through viewModel.sendMessage,
                    //     which dispatches to enqueuePrompt since _isStreaming is true).
                    //     Visual feedback for the queued prompt comes from the dashed
                    //     bubble that ChatViewModel.enqueuePrompt appends to the message
                    //     list — no extra button badge needed (matches iOS).
                    //   • streaming + !content → STOP (cancel current run).
                    //   • !streaming           → SEND (full color when content, dimmed
                    //     when empty; same as before).
                    // T180: an attachments-only send (no caption) is a
                    // valid message — mirrors iOS where !attachments.isEmpty
                    // satisfies the composer's send guard. Without this an
                    // image-only "look at this" send is impossible.
                    // [fix/dv19-b1] Content comes from the shared eligibility
                    // predicate (the field's own TextFieldValue + attachments), the
                    // same read the swipe gesture uses. The old
                    // `inputText.isNotBlank()` read the 150 ms-debounced VM string,
                    // so inside a dictation/paste burst the button rendered a red
                    // STOP over a live stream (a tap then called cancelStream and
                    // killed the run the user meant to enqueue) and the idle Send
                    // stayed dimmed over a composer the user could see was full.
                    val hasContent = canSwipeSendNow()
                    val showStop = isStreaming && !hasContent
                    if (showStop) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(ChatColors.error, CircleShape)
                                .clip(CircleShape)
                                .clickable { viewModel.cancelStream() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        // Streaming with content → Send-into-queue; Idle with content → Send.
                        // Idle without text or attachments → disabled.
                        // [feat/paste-fold-marker] ...and disabled while the
                        // composer still holds a marker this session cannot
                        // expand (the notice above the field explains it).
                        val canActivate = hasContent && !pasteSendBlockedNow()
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    if (canActivate) ChatColors.sendButton
                                    else ChatColors.sendButtonDisabled,
                                    CircleShape,
                                )
                                .clip(CircleShape)
                                .clickable(enabled = canActivate) {
                                    // T-drag-send-queue: route through the
                                    // shared send-or-enqueue handler. Same
                                    // semantics as before: slash short-
                                    // circuit, snapshot text, clear input
                                    // + focus, then sendMessage (which
                                    // routes to enqueuePrompt when
                                    // _isStreaming is true), then re-pin
                                    // the list to index 0 with a 100ms
                                    // re-pin to catch the late-mounting
                                    // "thinking" indicator.
                                    performSendOrEnqueue()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (canActivate) ChatColors.background
                                else ChatColors.primaryText.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
            // --- Swipe-to-send floating hint (extracted helper) ---
            SwipeToSendHint(
                progress = sendSwipeProgress,
                armFraction = swipeArmFraction,
                location = sendSwipeLocation,
                hoverAbovePx = swipeHapticOffsetPx,
                arrowHalfPx = swipeArrowHalfPx,
                // While streaming, sendMessage() routes the prompt
                // through enqueuePrompt() instead — surface that in
                // the hint so the user knows the gesture still works
                // mid-stream (mirrors the send-button's send/enqueue
                // toggle, since on Android there's no separate visual
                // state for the queued case).
                isEnqueue = isStreaming,
            )
        } // end swipe-to-send Box wrapping the composer Column

        if (showMoveSheet) {
            MoveToSessionSheet(
                currentSessionId = sessionId,
                chatRepository = chatRepository,
                onDismiss = { showMoveSheet = false },
                onSelect = { targetId ->
                    // [fix/dv19-b1] Stash the field's LIVE text (the VM string can
                    // still be a 150 ms burst behind, i.e. exactly empty right
                    // after dictation) and drop the pending flush: the job is
                    // launched on ChatScreen's scope, so it outlives this composer
                    // and would commit the snapshot into the session the user just
                    // left (inputText is per-ViewModel) after the move cleared it.
                    cancelPendingImeBurst()
                    // [feat/paste-fold-marker] `expandPastedText`: the paste
                    // buffer is per-ViewModel, so a marker would arrive in the
                    // target session with nothing to expand it (that session's
                    // guard would then refuse to send). The TEXT travels; the
                    // chips stay behind with the buffer they belong to.
                    ChatViewModelStore.stashPendingTransfer(
                        ChatViewModelStore.PendingTransfer(
                            inputText = viewModel.expandPastedText(inputFieldValue.text),
                            attachments = viewModel.attachments.value,
                        ),
                    )
                    clearComposerInput()
                    viewModel.clearAttachments()
                    viewModel.clearShareInjectedFlag()
                    showMoveSheet = false
                    onMoveToSession(targetId)
                },
            )
        }

}

/**
 * [dv22-B4] Async payload of the slash menu. [commands] is the batched IO
 * result; [loaded] is false only before the FIRST load for the current keys
 * (initial value / closed menu) — it drives the loading placeholder, never
 * the empty-vs-populated distinction (an empty `commands` with `loaded == true`
 * simply renders no popup, exactly like the old synchronous shape).
 */
private data class SlashMenuPayload(
    val commands: List<SlashCommand> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * [dv22-B4] First-load placeholder for the async slash menu: the same locked
 * frame the list uses ([SLASH_PICKER_FIXED_HEIGHT], 4 rows) with one subdued
 * "Loading…" row, so the popup's geometry never changes when the data lands.
 */
@Composable
private fun SlashMenuLoadingPanel() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // T240: same thin visible border as the list panel (see the list
            // Box below) so the placeholder IS the menu frame.
            .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
            .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
            .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp))
            .height(SLASH_PICKER_FIXED_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.common_loading),
            color = ChatColors.secondaryText,
            fontSize = 12.sp,
        )
    }
}

/**
 * [deep-audit-1049 B-06] Where the caret lands after a funnel rewrite.
 *
 * `null` means "this outlet has no caret intent" — the slash and `@` sites that
 * just replace the body keep the user's true caret (`lastTrueCaretEnd`, the
 * authoritative value maintained by `onValueChange`, NOT the possibly-lagging
 * `TextFieldValue.selection`). Both shapes are clamped to the new text, because
 * a shift-delete during the 150 ms burst can leave the remembered caret past the
 * end of the replacement and `TextRange` would throw.
 *
 * Pure and top-level so the arithmetic is pinned on the JVM; the funnel itself
 * needs a composition (`BasicTextField` + a ViewModel).
 */
internal fun composerCommitCaret(caret: Int?, lastTrueCaretEnd: Int, length: Int): Int {
    val wanted = caret ?: lastTrueCaretEnd
    if (length <= 0) return 0
    return wanted.coerceIn(0, length)
}

/**
 * [meta-audit-1028 C3/F-01] How the body funnel resolves the caret: an explicit
 * caller caret wins, then the ViewModel's one-shot intent
 * (`_pendingCaret`, parked by the slash flows and the mention insert), then the
 * user's true caret.
 *
 * The middle term is the one that used to be missing. `composerCommitCaret`
 * alone cannot see it, and the intent's only other reader (the
 * `LaunchedEffect(inputText)` mirror) is skipped precisely when the funnel runs,
 * because the funnel writes the field copy too. Pure so the precedence is
 * pinned on the JVM: the funnel itself calls `viewModel.consumePendingCaret()`,
 * which needs a composition.
 */
internal fun composerFunnelCaret(
    caret: Int?,
    pendingCaret: Int?,
    lastTrueCaretEnd: Int,
    length: Int,
): Int = composerCommitCaret(caret ?: pendingCaret, lastTrueCaretEnd, length)

/**
 * [meta-audit-1028 C3/F-03] Does this `onValueChange` look like a soft-keyboard
 * Enter (→ send) rather than an ordinary edit?
 *
 * Multi-line composers report Enter as a single inserted `'\n'` (the IME never
 * fires `KeyboardActions.onSend`), so that is the whole signal: exactly one
 * character added, exactly one newline more, and the newline sitting before the
 * caret. A paste with line breaks adds more than one newline and falls through
 * to the normal multi-line edit.
 *
 * The shape cannot separate "IME Enter" from "hardware Shift+Enter" — Compose
 * inserts the same `'\n'` for both — so that discrimination lives one level up
 * (the `shiftEnterNewlinePending` flag the key handler raises). A clipboard
 * paste of a lone newline is likewise indistinguishable from a soft Enter and
 * still sends; see the fix report's residual section.
 */
internal fun isEnterSendEdit(oldText: String, newText: String, caretEnd: Int): Boolean {
    if (newText.length != oldText.length + 1) return false
    if (newText.count { it == '\n' } != oldText.count { it == '\n' } + 1) return false
    return caretEnd in 1..newText.length && newText[caretEnd - 1] == '\n'
}
