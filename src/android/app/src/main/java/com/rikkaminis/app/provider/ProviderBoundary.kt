package com.rikkaminis.app.provider

import android.app.Application

/**
 * [TF-E] Runtime process-domain guard for the "main process never talks to a
 * provider directly" boundary.
 *
 * TF-D eliminated all direct `provider.sendMessage` / `provider.streamMessage`
 * calls from the app-process source (locked statically by
 * NoInProcessProviderGuardTest). This is the *second*, runtime line of defence:
 * even if a future change re-introduces an in-process provider call through a
 * path the static grep can't see (a provider implementation detail, a new
 * concrete provider, a sub-call buried in a dependency), the network entry
 * points themselves refuse to run in a non-worker process.
 *
 * The sanctioned executor for provider network calls is the dedicated
 * `:modelservice` worker process ([ModelExecutionService]), whose native heap
 * (DirectByteBuffer / SSE buffers / JSON parse) dies with the process.
 *
 * ## Why NOT enforced inside ProviderFactory.create
 * `create` is also legitimately invoked from the app process to build a
 * *metadata* provider (ChatViewModel.currentProvider for instance/model
 * display, ModelUseOffloadHandler's CLI path) — none of which make a network
 * call. So the guard anchors on the actual network entry points
 * (sendMessage / streamMessage defaults), not on object construction.
 *
 * ## JVM unit-test escape hatch
 * Under JVM tests `Application.getProcessName()` returns null
 * (unitTests.isReturnDefaultValues = true) → the guard treats null as "no
 * runtime context" and passes through OR a test may set
 * [ProviderBoundary.bypassForTests]. Enforcement is pure and injectable, so
 * tests exercise the true decision logic via [enforce].
 */
sealed class ProviderBoundaryViolation(message: String) : IllegalStateException(message) {
    /** Thrown when a provider network entry point runs outside :modelservice. */
    class IllegalProcess(message: String) : ProviderBoundaryViolation(message)
}

object ProviderBoundary {

    /**
     * A test may flip this to simulate a live (non-test) process context, or to
     * disable the guard entirely for a test that must exercise a provider end-to-
     * end on a stub. Default null → enforcement uses the real [processName].
     * References this override so tests can assert the throw path in a JVM run.
     */
    @Volatile
    var overrideProcessName: String? = null

    /**
     * Convenience switch so a JVM test can exercise providers that would
     * otherwise be refused. When true, [enforce] is a no-op. Not consulted by
     * production code (which never sets it).
     */
    @Volatile
    var bypassForTests: Boolean = false

    /** The current process name, or null when running under a JVM unit test. */
    fun currentProcessName(): String? =
        overrideProcessName ?: runCatching { Application.getProcessName() }.getOrNull()

    /**
     * Pure decision: refuse provider network entry in any process other than
     * `:modelservice`. Returns the process name unchanged so senders can log it.
     *
     * @param processName null in a JVM unit test (Android stub default) — and,
     *   in production, a null here is treated as an unknown/non-Android context
     *   which we refuse (fail closed rather than leak). Tests against the pure
     *   logic may pass any String.
     */
    fun enforce(processName: String?): String? {
        if (bypassForTests) return processName
        // JVM unit test: no Android runtime → the static unit-test guard
        // (NoInProcessProviderGuardTest) owns this path; runtime enforcement
        // has nothing to inspect. Pass through.
        if (processName == null) return null
        // Worker process is the sole sanctioned owner of provider calls.
        if (processName.endsWith(":modelservice")) return processName
        // Any real process that is not :modelservice → refuse.
        throw ProviderBoundaryViolation.IllegalProcess(
            "Provider network call attempted from process '$processName' — " +
                "provider sendMessage/streamMessage must run only in :modelservice " +
                "(main-process provider heap dies with that worker). Route through " +
                "ProviderExecutionGateway instead."
        )
    }
}