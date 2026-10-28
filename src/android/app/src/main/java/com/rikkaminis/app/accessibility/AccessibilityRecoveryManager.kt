package com.rikkaminis.app.accessibility

import android.content.Context
import android.provider.Settings
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.offload.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * [T-android-a11y-force-stop-recovery] Detects a revoked accessibility grant
 * and offers to repair it.
 *
 * ## Why this exists
 *
 * `adb shell am force-stop` — and the equivalent "force stop" button in system
 * Settings, plus the aggressive "clear" action on several OEM ROMs — makes the
 * framework REMOVE our component from `Settings.Secure`
 * .ENABLED_ACCESSIBILITY_SERVICES. Verified empirically on Pixel 4a / Android
 * 13: after a force-stop the key is emptied, `accessibility_enabled` drops to
 * 0, and it never self-heals — waiting does nothing and relaunching the app
 * does nothing. The user has to walk back into Settings → Accessibility.
 *
 * This is deliberate AOSP behaviour, not an app defect: the same test against
 * Google's own TalkBack, Bitwarden and AutoX reproduced it identically. With
 * two services enabled and only one force-stop-killed, the framework
 * surgically removes just the stopped one — i.e. it is a security decision (a
 * force-stopped app must not keep the ability to read the screen).
 *
 * Note that ordinary "swipe away from recents" does NOT do this: an
 * accessibility service pins its host process at adj=100 (bound by `system`),
 * so the process survives and the grant is untouched. Only force-stop-class
 * kills matter here.
 *
 * ## Why an app normally cannot fix this
 *
 * Writing ENABLED_ACCESSIBILITY_SERVICES requires WRITE_SECURE_SETTINGS, which
 * is not grantable to a normal app. That is why none of the apps surveyed even
 * *detect* the loss. RikkaMinis is in a better position because it already
 * ships a Shizuku client: with Shizuku authorized we can run `settings put
 * secure` with shell privilege and repair the grant in place, which was
 * verified to take effect immediately (the service rebinds without a
 * relaunch).
 *
 * So there are two tiers:
 *  - Shizuku READY  → one-tap repair, no trip to Settings.
 *  - otherwise      → explain the situation and deep-link to Settings.
 */
object AccessibilityRecoveryManager {

    private const val TAG = "A11yRecovery"

    /**
     * How long the in-chat repair prompt waits for the user before giving up.
     * Spec'd at 60s: long enough to notice a dialog, short enough that an
     * unattended agent run is not blocked indefinitely. Exposed (not private)
     * so tests can assert the contract without reaching into internals.
     */
    const val PROMPT_TIMEOUT_MS: Long = 60_000L

    /**
     * After a Shizuku write, how long to wait for the framework to actually
     * rebind the service. The write returns before `onServiceConnected` runs,
     * so a naive "did it work" check immediately after would report false.
     */
    private const val REBIND_TIMEOUT_MS: Long = 5_000L
    private const val REBIND_POLL_MS: Long = 250L

    /**
     * Debounce for [isGrantRevoked]'s Settings.Secure read. The check sits on
     * the a11y tool-invocation path, which a single agent turn can hit dozens
     * of times in a row, and each call is a ContentProvider round-trip. A
     * short TTL keeps the common case (grant intact) essentially free while
     * still noticing a revocation within a quarter second.
     *
     * Deliberately NOT a long-lived cache: a stale "grant is fine" answer
     * would suppress the very prompt this class exists to raise.
     */
    private const val CHECK_CACHE_TTL_MS: Long = 250L

    @Volatile private var cachedRevoked: Boolean? = null
    @Volatile private var cachedAtMs: Long = 0L

    /** What the user chose in the repair prompt. */
    enum class Decision { REPAIR, OPEN_SETTINGS, CANCEL }

    /**
     * A pending repair prompt. Collected by MainActivity, which renders it as
     * an AlertDialog and calls [respond].
     *
     * @param shizukuAvailable when true the dialog offers in-place repair;
     *   when false it can only offer a trip to Settings.
     */
    data class RepairPrompt(
        val shizukuAvailable: Boolean,
    )

    private val _pendingPrompt = MutableStateFlow<RepairPrompt?>(null)
    val pendingPrompt: StateFlow<RepairPrompt?> = _pendingPrompt.asStateFlow()

    /**
     * Serializes every read and write of [continuation] / [promptToken] and of
     * the prompt slot they describe. The critical sections are pure field work —
     * no suspension, no callback — so a plain monitor is the right tool: the
     * resumptions happen OUTSIDE it, and `respond` (UI thread) can never
     * interleave with a timeout/cancellation cleanup (agent thread).
     */
    private val slotLock = Any()

    /**
     * The waiter that currently owns the repair prompt.
     *
     * [Volatile] because [respond] runs on the UI thread while the waiting
     * coroutine lives on the agent's; every mutation still goes through
     * [slotLock].
     */
    @Volatile private var continuation: Continuation<Decision>? = null

    /**
     * Identity of the slot owner. Bumped when a waiter publishes, when the UI
     * answers, and claimed by each new waiter up front.
     *
     * Every path that CLEARS the slot (timeout, coroutine cancellation) must
     * still be holding its own token to be allowed to do so. Without this a late
     * cleanup from waiter A tore down the dialog a newer waiter B had just put
     * on screen — B then sat parked until its own full timeout with nothing to
     * answer. The same token keeps a stale responder from mis-delivering a
     * decision to the wrong waiter.
     */
    private var promptToken: Long = 0L

    /**
     * Observable "the grant is currently missing" flag for Settings UI, so the
     * repair row can appear without the screen polling Settings.Secure itself.
     * Refreshed by [refreshRevokedState], which Settings calls on entry /
     * resume.
     */
    private val _revoked = MutableStateFlow(false)
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    /** The `pkg/cls` string the framework expects in ENABLED_ACCESSIBILITY_SERVICES. */
    private fun componentId(context: Context): String =
        "${context.packageName}/${MinisAccessibilityService::class.java.name}"

    private const val PREFS = "a11y_recovery"
    private const val KEY_EVER_GRANTED = "ever_granted"

    /**
     * True once the user has enabled the service at least once on this install.
     *
     * Needed to tell "revoked" apart from "never set up". Both look identical
     * in Settings.Secure — our component simply isn't listed — but only the
     * first is a fault worth surfacing a repair affordance for. Without this a
     * fresh install would greet the user with a "permission was revoked" banner
     * for a permission they had never granted.
     *
     * Latched by [markGranted], which the service calls on connect.
     */
    fun hasEverBeenGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EVER_GRANTED, false)

    /**
     * Latch that the grant has existed. Called from
     * [MinisAccessibilityService.onServiceConnected] — the one moment we know
     * for certain the user granted it, whichever route they took (Settings
     * toggle, Shizuku repair, or a restore).
     */
    fun markGranted(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_EVER_GRANTED, false)) {
            p.edit().putBoolean(KEY_EVER_GRANTED, true).apply()
            AppLogger.info(TAG, "grant latched (first successful connect)")
        }
    }

    /**
     * True when our component is absent from ENABLED_ACCESSIBILITY_SERVICES,
     * i.e. the grant itself is gone and only a Settings write can restore it.
     *
     * This asks Settings.Secure rather than checking
     * `MinisAccessibilityService.getInstance() != null`, because the two mean
     * different things and only one of them is repairable:
     *  - instance == null but grant present → the service is mid-(re)bind, or
     *    the OEM killed it and the framework will bring it back. Writing the
     *    setting would be pointless.
     *  - grant absent → the framework has forgotten us entirely. This is the
     *    force-stop case, and the only one we can repair.
     *
     * Matching is case-insensitive and also accepts the abbreviated
     * `pkg/.Class` form, because the framework normalizes entries it rewrites
     * (observed: TalkBack's entry came back as `pkg/.TalkBackService` after we
     * wrote the fully-qualified form).
     *
     * The accessibility master switch is consulted first, and OFF means revoked:
     * with `ACCESSIBILITY_ENABLED == 0` the framework ignores the service list
     * entirely, so a listed-but-master-off service receives no events and the
     * repair affordance is exactly what the user needs. This is the same verdict
     * [A11yState.isServiceEnabled] reaches for the same fact — one predicate,
     * two readers, no drift.
     */
    fun isGrantRevoked(context: Context): Boolean {
        val now = System.currentTimeMillis()
        cachedRevoked?.let { if (now - cachedAtMs < CHECK_CACHE_TTL_MS) return it }

        val revoked = try {
            // Master switch OFF ⇒ the framework ignores the service list, so a
            // listed service is not running: that IS the revoked state. Read
            // through the same predicate the settings screens use, and stay
            // inside the cache's verdict (see the KDoc note above).
            if (A11yState.isMasterSwitchOn(context)) {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
                isRevokedIn(
                    enabledValue = enabled,
                    pkg = context.packageName,
                    serviceClass = MinisAccessibilityService::class.java.name,
                )
            } else {
                true
            }
        } catch (t: Throwable) {
            // A read failure is not evidence of revocation — do not prompt on it.
            AppLogger.warning(TAG, "isGrantRevoked read failed: ${t.message}")
            false
        }

        cachedRevoked = revoked
        cachedAtMs = now
        return revoked
    }

    /**
     * Pure entry-matching half of [isGrantRevoked], split out so the parsing
     * rules can be exercised on the JVM without a Context.
     *
     * Returns true when [enabledValue] — the raw colon-separated
     * ENABLED_ACCESSIBILITY_SERVICES string — does not contain our component.
     *
     * Accepts both the fully-qualified `pkg/pkg.Class` form we write and the
     * abbreviated `pkg/.Class` form the framework may normalize to. Observed on
     * Pixel 4a / Android 13: after writing TalkBack's fully-qualified name the
     * framework rewrote the value as `…talkback/.TalkBackService`. Matching only
     * the long form would then read as "revoked" while the grant was healthy,
     * producing a repair prompt in a loop.
     */
    fun isRevokedIn(enabledValue: String?, pkg: String, serviceClass: String): Boolean {
        if (enabledValue.isNullOrBlank()) return true
        val fq = "$pkg/$serviceClass"
        val short = "$pkg/.${serviceClass.removePrefix("$pkg.")}"
        return enabledValue.split(':').none { entry ->
            val e = entry.trim()
            e.equals(fq, ignoreCase = true) || e.equals(short, ignoreCase = true)
        }
    }

    /** Drop the debounce so the next [isGrantRevoked] re-reads Settings. */
    fun invalidateCache() {
        cachedRevoked = null
        cachedAtMs = 0L
    }

    /** Re-read the grant and publish it to [revoked] for Settings UI. */
    fun refreshRevokedState(context: Context) {
        invalidateCache()
        _revoked.value = isGrantRevoked(context)
    }

    /**
     * Repair the grant with Shizuku shell privilege.
     *
     * Appends our component to the existing ENABLED_ACCESSIBILITY_SERVICES
     * value rather than overwriting it — a blind overwrite would disable every
     * OTHER accessibility service the user relies on (TalkBack, a password
     * manager's autofill, an OEM gesture tool). Then sets
     * `accessibility_enabled=1`, which force-stop also clears and without
     * which the framework ignores the service list entirely.
     *
     * Runs `settings put secure` through [ShizukuManager.runProcess] — the
     * same privileged path `android-shizuku-cli settings set` uses, so there
     * is one implementation of the write.
     *
     * @return true once the service has actually rebound.
     */
    suspend fun repairWithShizuku(context: Context): Boolean {
        if (!ShizukuManager.isReady()) {
            AppLogger.info(TAG, "repair skipped: Shizuku not ready")
            return false
        }
        val target = componentId(context)

        val existing = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (_: Throwable) { emptyList() }

        val merged = (existing + target).distinct().joinToString(":")

        val r1 = ShizukuManager.runProcess(
            arrayOf("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
        )
        if (r1.exitCode != 0) {
            AppLogger.warning(TAG, "repair failed writing service list: exit=${r1.exitCode} ${r1.combined}")
            return false
        }
        val r2 = ShizukuManager.runProcess(
            arrayOf("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        )
        if (r2.exitCode != 0) {
            AppLogger.warning(TAG, "repair failed writing accessibility_enabled: exit=${r2.exitCode} ${r2.combined}")
            return false
        }

        // The write is async from the framework's perspective; wait for the
        // real signal (our service instance appearing) rather than assuming.
        val bound = withTimeoutOrNull(REBIND_TIMEOUT_MS) {
            while (MinisAccessibilityService.getInstance() == null) delay(REBIND_POLL_MS)
            true
        } == true

        invalidateCache()
        _revoked.value = isGrantRevoked(context)
        // [r3-B11-F09] The KDoc promises "true once the service has actually
        // rebound", and both callers act on exactly that: the Settings row
        // shows "repaired" and the a11y CLI proceeds straight to
        // `svcOrThrow()`. Returning the write's exit code instead of the
        // rebind made the CLI path fail with "service is not running" in the
        // same turn the user was told the repair worked. One last read covers
        // a rebind that landed during the cache refresh above.
        val usable = bound || MinisAccessibilityService.getInstance() != null
        AppLogger.info(TAG, "repair via Shizuku wrote grant; rebound=$bound usable=$usable")
        return usable
    }

    /**
     * Show the repair prompt and wait for a decision, for at most
     * [PROMPT_TIMEOUT_MS].
     *
     * Times out to [Decision.CANCEL] so an unattended run (screen off, user
     * away, or the dialog dismissed by something else) degrades to the normal
     * "service not running" error instead of hanging the agent loop. The
     * pending prompt is always cleared, including on coroutine cancellation,
     * so a stale dialog can never outlive its caller.
     *
     * Both invariants are enforced through [promptToken]: this waiter claims an
     * id before it publishes, and every teardown (timeout here, cancellation in
     * [clearSlotIfOwner], a decision in [respond]) has to still own the slot to
     * touch it. Two consequences the naive version got wrong: a late teardown
     * can no longer clear a NEWER waiter's prompt, and a waiter that finds the
     * slot already taken converges with CANCEL immediately instead of parking
     * for a full timeout on a dialog that is not its own.
     */
    private suspend fun awaitDecision(prompt: RepairPrompt): Decision {
        val token = synchronized(slotLock) { ++promptToken }
        val decision = withTimeoutOrNull(PROMPT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Decision> { cont ->
                var displaced: Continuation<Decision>? = null
                val accepted = synchronized(slotLock) {
                    if (promptToken != token) {
                        false
                    } else {
                        displaced = continuation
                        continuation = cont
                        _pendingPrompt.value = prompt
                        true
                    }
                }
                if (!accepted) {
                    // Superseded while starting up: never suspend on a slot we
                    // do not own, and never leave the caller hanging.
                    runCatching { cont.resume(Decision.CANCEL) }
                    return@suspendCancellableCoroutine
                }
                // A previous waiter was displaced by this publish; converge it
                // now rather than letting it sit until its own timeout.
                displaced?.let { runCatching { it.resume(Decision.CANCEL) } }
                cont.invokeOnCancellation { clearSlotIfOwner(token) }
            }
        }
        if (decision == null) {
            // Timed out: tear the dialog down so it doesn't linger on screen —
            // but only if this waiter still owns it.
            clearSlotIfOwner(token)
            AppLogger.info(TAG, "repair prompt timed out after ${PROMPT_TIMEOUT_MS}ms — treating as cancel")
        }
        return decision ?: Decision.CANCEL
    }

    /**
     * Release the prompt slot, provided [token] is still the current owner.
     *
     * The check is the whole point: teardown from an expired waiter must not
     * dismiss the prompt a newer one is showing.
     */
    private fun clearSlotIfOwner(token: Long) {
        synchronized(slotLock) { clearSlotLocked(token) }
    }

    /**
     * The ONE release site of the prompt slot. Callers MUST hold [slotLock].
     *
     * Single site on purpose: this family (memory, 2026-09-06 → 10-12) loses an
     * ownership guard exactly when a second release path is written without one,
     * so every teardown — timeout, cancellation, and the UI's answer — goes
     * through here.
     *
     * @return the waiting continuation, or null when [token] is no longer the
     *   owner (then nothing is taken and nothing is cleared).
     */
    private fun clearSlotLocked(token: Long): Continuation<Decision>? {
        if (promptToken != token) return null
        val taken = continuation
        continuation = null
        _pendingPrompt.value = null
        return taken
    }

    /**
     * Called by the UI once the dialog closes. Safe to call spuriously.
     *
     * Mints a token first: that bump is what invalidates every teardown still
     * holding the previous one (including the waiting caller's cancellation
     * handler), and it lets the answer go through the same guarded release path
     * as everything else. The resume happens OUTSIDE the lock — the resumed code
     * runs on its own thread and may re-enter this object — and tolerates a
     * continuation that was already cancelled.
     */
    fun respond(decision: Decision) {
        val taken = synchronized(slotLock) {
            val token = ++promptToken
            clearSlotLocked(token)
        }
        runCatching { taken?.resume(decision) }
    }

    /**
     * Entry point for the a11y tool path: if the grant is gone, offer to fix
     * it and report whether the caller may proceed.
     *
     * [hasEverBeenGranted] gates the whole repair affordance here, not just in
     * the Settings UI: a fresh install has no entry in
     * ENABLED_ACCESSIBILITY_SERVICES either, and without the gate the first a11y
     * tool call on a clean device would raise a "permission was revoked" repair
     * prompt and hold the tool turn for [PROMPT_TIMEOUT_MS] over a permission
     * the user never granted. Returning false here keeps the pre-recovery
     * behaviour for that case — the caller fails immediately with its normal
     * "service not running" error, no dialog and no blocking.
     *
     * @return true if the service is usable (grant was intact, or repair
     *   succeeded); false if the caller should fail with its normal
     *   "service not running" error.
     */
    suspend fun ensureGrantOrPrompt(context: Context): Boolean {
        if (!hasEverBeenGranted(context)) return false
        if (!isGrantRevoked(context)) return true

        val shizuku = ShizukuManager.isReady()
        AppLogger.info(TAG, "grant revoked — prompting (shizukuReady=$shizuku)")
        _revoked.value = true

        return when (awaitDecision(RepairPrompt(shizukuAvailable = shizuku))) {
            Decision.REPAIR -> repairWithShizuku(context)
            // The user was sent to Settings; they may or may not finish. Don't
            // block the turn waiting — report unusable and let them retry.
            Decision.OPEN_SETTINGS -> false
            Decision.CANCEL -> false
        }
    }
}
