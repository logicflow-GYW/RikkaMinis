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
    // [INCR-1113 F-09] `Application.getProcessName()` is API 28 on a minSdk 26
    // app: on API 26–27 it throws NoSuchMethodError, which the old
    // `runCatching` folded into null — and null means "pass through", so the
    // runtime guard silently did nothing on exactly the oldest supported
    // devices. The `/proc/self/cmdline` fallback works on every API level and
    // is what the worker's own identity probe reads (ModelExecutionRunDir),
    // so the two answers agree instead of disagreeing.
    @Suppress("NewApi")
    fun currentProcessName(): String? = overrideProcessName
        ?: readProcessName(
            getProcessName = { Application.getProcessName() },
            readCmdline = { readProcCmdlineName() },
        )

    /**
     * Pure decision: refuse provider network entry in any process other than
     * `:modelservice`. Returns the process name unchanged so senders can log it.
     *
     * @param processName null when NO process name could be determined — a JVM
     *   unit test (no Android runtime) or an environment where both
     *   [currentProcessName] sources failed. That case passes through: the
     *   static guard ([NoInProcessProviderGuardTest]) owns it, and the runtime
     *   guard has nothing to inspect. It is NOT "fail closed" — an earlier
     *   revision of this doc claimed that and the code never did it.
     *   Tests against the pure logic may pass any String.
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

/**
 * [INCR-1113 F-09] Resolve the current process name from two injectable
 * sources, in order:
 *
 *  1. `Application.getProcessName()` — the cheap, authoritative answer, but
 *     API 28+ only (it throws `NoSuchMethodError` on API 26–27, and on the JVM
 *     it is an Android stub that returns null);
 *  2. `/proc/self/cmdline` — available on every API level, and the same source
 *     the worker's own identity probe reads (`ModelExecutionRunDir`), so the
 *     two views of "who am I" cannot disagree.
 *
 * Both sources are parameters (not direct calls) so the fallback logic is a
 * pure function a JVM test can drive without an Android runtime or a real
 * `/proc` read. A blank answer counts as "did not answer" — `/proc` can return
 * an empty cmdline for zombies / restricted processes.
 *
 * Returns null only when NEITHER source answered; see [ProviderBoundary.enforce]
 * for what null means (pass through, not fail closed).
 *
 * **Only a THROW falls back.** A null RETURN is the JVM-unit-test stub
 * (`unitTests.isReturnDefaultValues = true`), i.e. "there is no Android
 * runtime here", and the static guard owns that case. Falling back to
 * `/proc/self/cmdline` there would answer "the Gradle test worker", which is
 * not a process this app ever runs in — every provider unit test that calls
 * sendMessage would then trip the runtime guard.
 */
internal fun readProcessName(
    getProcessName: () -> String?,
    readCmdline: () -> String?,
): String? {
    val direct = try {
        getProcessName()?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        // API 26–27: the method exists in the bytecode but not in the
        // platform, so it throws NoSuchMethodError. That is the ONE case the
        // /proc fallback exists for.
        return runCatching { readCmdline() }
            .getOrNull()
            // Tolerate a RAW `/proc/self/cmdline` payload: argv entries are
            // NUL-separated, so the name is everything before the first NUL.
            // [readProcCmdlineName] already does this; doing it here too means
            // an injected reader cannot reintroduce the terminator.
            ?.substringBefore('\u0000')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    return direct
}

/**
 * `/proc/self/cmdline`'s argv0 — the full process name including the
 * `:modelservice` suffix. Null when the file is missing or unreadable (the
 * same tolerance `ModelExecutionRunDir.readProcIdentity` applies).
 *
 * The NUL terminator is what separates argv entries, so the first entry is
 * everything up to the first `\u0000`; a file with no terminator at all is
 * returned whole.
 */
internal fun readProcCmdlineName(
    readFile: (String) -> String? = { path ->
        runCatching { java.io.File(path).readText() }.getOrNull()
    },
): String? = readFile("/proc/self/cmdline")
    ?.substringBefore('\u0000')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
