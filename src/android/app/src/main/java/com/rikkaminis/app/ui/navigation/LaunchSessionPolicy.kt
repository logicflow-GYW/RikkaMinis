package com.rikkaminis.app.ui.navigation

/**
 * Launch-session modes.
 *
 * The numeric values are the contract with `KEY_LAUNCH_SESSION` in
 * `ui/settings/AppearanceScreen.kt` (0=Auto, 1=LastSession, 2=NewChat,
 * 3=Safe Start): the settings screen writes the *index of the row the user
 * tapped*, so these constants and that preference must stay in sync — the
 * unit test pins the numeric values for exactly that reason.
 */
object LaunchSessionMode {
    const val AUTO = 0
    const val LAST_SESSION = 1
    const val NEW_CHAT = 2
    const val SAFE_START = 3
}

/**
 * [fix/coldstart-restore-vs-launch-mode] Whether a TRUE cold start
 * (`savedInstanceState == null`, i.e. the user swiped the task away / never had
 * a saved bundle) may restore the process-level last session id
 * ([MainActivity.PREF_CRASH_RECOVERY]).
 *
 * Why this needs a gate at all: the restore is synthesised into an
 * `OpenSession` deep link, and AppNavigation's launch-mode dispatcher returns
 * early whenever a deep link is present — so the Launch Session preference
 * never gets a say. With New Chat / Safe Start that read as "the app opens my
 * previous conversation no matter what I asked for".
 *
 * Why the preference and not "did the last cycle crash": on a real device
 * `Application.onTerminate` is never called, so the launch beacon reports
 * `silent_kill` both for "the user closed the app" and for "MIUI/LMK killed it
 * in the background" — the two are indistinguishable from the app's side
 * (verified against launch-beacon.log: every cycle is silent_kill). The
 * preference is therefore the only honest signal available.
 *
 * Auto / Last Session ask for "pick up where I left off", so they keep the
 * restore; every other mode — including an unknown future value, which the
 * dispatcher resolves through its own fallback — hands the decision back to
 * the launch-mode dispatcher.
 */
internal fun coldStartRestoreAllowed(launchMode: Int): Boolean =
    launchMode == LaunchSessionMode.AUTO || launchMode == LaunchSessionMode.LAST_SESSION
