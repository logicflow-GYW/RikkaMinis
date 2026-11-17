package com.rikkaminis.app.share

/**
 * [W1118-T6] Pure decisions behind the host-side share paths — the entry point
 * that runs OUTSIDE `MainActivity` ([ShareReceiverActivity]) and the warm-start
 * hand-off that runs inside it.
 *
 * Android-free on purpose: these rules are the parts that used to be implicit
 * ("the Application always finished init", "the Activity is always destroyed
 * through its hand-off", "a chat is always on screen") and each of them is
 * false on some real lifecycle path. Keeping them here lets the JVM tests pin
 * the decision instead of the source shape.
 */
internal object ShareHostPolicy {

    /**
     * [D11 F-01] May this process touch `MinisApp`'s `lateinit` repositories?
     *
     * `MinisApp.onCreate` returns early — before `providerRepository` is
     * assigned — in three states: safe mode (crash burst), the one-way
     * `_initSkipped` latch that outlives it, and the "database written by a
     * newer build" degraded mode. A `lateinit` read in any of them throws
     * `UninitializedPropertyAccessException`, so the share entry point has to
     * ask BEFORE it dereferences the field: `ShareReceiverActivity` is an
     * exported ACTIVITY_SEND target and is launched by the system directly,
     * without `MainActivity`'s guards in front of it.
     *
     * `_initSkipped` is checked separately from `isSafeMode()` because
     * `finishClose()` clears `_safeMode` while init stays skipped for the rest
     * of the process (that is the whole reason MainActivity gates on both).
     */
    fun mayUseAppRepositories(
        isSafeMode: Boolean,
        isInitSkipped: Boolean,
        isNewerDbGuidance: Boolean,
    ): Boolean = !isSafeMode && !isInitSkipped && !isNewerDbGuidance

    /**
     * [D11 F-02] Must a destroyed `ShareReceiverActivity` release the staged
     * files it claimed but never handed off?
     *
     * A claimed name in [StagedInFlightFiles] is protected from
     * `SharedShareStore.cleanSharedFiles` — that is the point of the registry
     * ([R5-A3 F-07]) — so a name whose owner vanished without handing it off is
     * protected forever: the file stays on disk AND its bytes stay counted in
     * the 500 MB budget until the process dies.
     *
     * Three instances must NOT release:
     *  - `handedOff`: the pending share (or the provider-import discard path)
     *    already owns the outcome; releasing again would re-open the deletion
     *    race the registry exists to close;
     *  - `isChangingConfigurations`: a rebuild restores the same staged set and
     *    re-claims exactly these names ([restoreStagedItems]), so releasing
     *    them in the destroy that precedes it would leave live files
     *    unprotected — the next clean would delete a set the user is still
     *    looking at in the dialog;
     *  - `stateSavedForRestore`: the same thing, one step further out. A
     *    system-initiated destroy of a stopped Activity (low memory, "don't
     *    keep activities") keeps `savedInstanceState`, and `onSaveInstanceState`
     *    is skipped for a finishing Activity — so "state was saved" means a
     *    rebuild is coming and these files are its content. Deleting them would
     *    hand the recreated instance ghost attachments; if that rebuild never
     *    comes, the leak is bounded by the process (the registry dies with it,
     *    leaving plain orphans the next clean reaps).
     *
     * Everything else — finishing for real (back press, task removed) — is a
     * genuine abandon: release the claims and delete the bytes now.
     */
    fun shouldReleaseStagedOnDestroy(
        handedOff: Boolean,
        isChangingConfigurations: Boolean,
        stateSavedForRestore: Boolean,
    ): Boolean = !handedOff && !isChangingConfigurations && !stateSavedForRestore

    /**
     * [D11 F-05] Does a buffered share need an explicit navigation to a chat?
     *
     * [ShareCoordinator]'s buffer has a 30 s TTL and exactly one consumer:
     * `ChatScreen`'s `LaunchedEffect(shareBufferVersion)`. A warm start lands
     * on whatever route the user left behind — settings, a full-screen page —
     * where no `ChatScreen` is composed, so the buffer expires unread and the
     * shared text/image is silently dropped. The receiver's re-launch intent
     * carries no `data` URI (it is only `shared_content=true`), so the
     * deep-link branch does not navigate either.
     *
     * No navigation is needed when a chat is on screen RIGHT NOW:
     *  - the top destination is a chat; or
     *  - the top destination is a dialog (`FILE_PREVIEW`) pushed over a chat —
     *    the chat keeps rendering underneath and still consumes the buffer
     *    (`ChatRoutePresence.isChatKeptAliveOverlay`).
     *
     * A full destination stacked on a chat (settings pushed from a chat) does
     * NOT count: the chat left composition with its `LaunchedEffect`.
     */
    fun shareNeedsChatRoute(
        topRoute: String?,
        topIsChatKeptAliveOverlay: Boolean,
        belowRoute: String?,
        chatRoute: String,
    ): Boolean = when {
        topRoute == chatRoute -> false
        topIsChatKeptAliveOverlay && belowRoute == chatRoute -> false
        else -> true
    }
}
