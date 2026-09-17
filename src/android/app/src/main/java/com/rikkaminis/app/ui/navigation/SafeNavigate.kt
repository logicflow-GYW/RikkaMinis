package com.rikkaminis.app.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.currentStateFlow
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import kotlinx.coroutines.flow.first

/**
 * Wrapper around `NavController.navigate` that drops the call when the
 * current `NavBackStackEntry`'s lifecycle is not at `RESUMED`.
 *
 * Android Navigation has a long-standing rough edge: when the user taps
 * one button that pops the stack and *immediately* taps another button
 * (e.g. "back" then "settings") the second `navigate` lands while the
 * source destination is still mid-pop — the new destination ends up on
 * the stack but with no UI rendered (the Composition for the popped
 * screen is gone, the new one never inflates because the ViewTree is
 * confused by two state transitions in one frame). The user sees a blank
 * white screen and the only recovery is to press back again.
 *
 * Compose Multiplatform docs and the Now-In-Android sample both call out
 * the same fix: only allow `navigate` from a destination whose lifecycle
 * is RESUMED (i.e. fully on-screen and stable). Anything else means
 * we're in a transition window — drop the call.
 *
 * Usage:
 *
 *   onSettingsClick = { navController.safeNavigate(Routes.SETTINGS) }
 *
 * Mirror `popBackStack` with the same guard via `safePopBackStack`.
 */
fun NavController.safeNavigate(
    route: String,
    builder: (NavOptionsBuilder.() -> Unit)? = null,
) {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    if (builder != null) navigate(route, builder) else navigate(route)
}

/**
 * [fix/audit0917-b8] [safeNavigate] for callers that legitimately run BEFORE
 * the current destination reaches RESUMED — a `LaunchedEffect` that fires on
 * the NavHost's first composition pass, where the start entry is still STARTED.
 *
 * `safeNavigate` exists to defang the back-then-tap race (a source destination
 * mid-tear-down), not to gate startup dispatch. Using it there dropped the
 * navigation silently — the AppNavigation deep-link handler shipped with
 * exactly that bug: every `minis://…` cold-start deep link (terminal, session,
 * settings screen, env-var create, permissions) was swallowed, so the app
 * opened on the default destination instead of the requested one. The T314
 * comment on the launch-session dispatcher documents the same failure for the
 * same reason and works around it by calling `navigate` directly.
 *
 * Suspends until the entry is RESUMED, then performs the *guarded* navigate —
 * so the teardown protection `safeNavigate` was chosen for is preserved. No
 * timeout: the effect is keyed on the deep link and dies with the composable.
 */
suspend fun NavController.awaitResumed() {
    val entry = currentBackStackEntry ?: return
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) return
    entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
}

fun NavController.safePopBackStack(): Boolean {
    // User-triggered pops (top-bar back button) must not be silently
    // swallowed during transient transitions. RESUMED is too strict —
    // when a ModalBottomSheet / Dialog has the chat NavBackStackEntry
    // demoted to STARTED, the back tap would otherwise no-op with only
    // a ripple animation to show for it. Bug 3 in the MIUI feedback
    // report was exactly this case.
    val state = currentBackStackEntry?.lifecycle?.currentState ?: return false
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    return popBackStack()
}
