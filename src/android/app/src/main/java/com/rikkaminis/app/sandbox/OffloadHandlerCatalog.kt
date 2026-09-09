package com.rikkaminis.app.sandbox

import com.rikkaminis.app.BuildConfig

/**
 * Static catalog of every native_offload handler NAME the PRoot guest can
 * execve. [native-oom Phase 1] makes this the single source of truth for the
 * name list that main-process code (PRootKernel / PersistentShell /
 * TerminalSession) uses to generate:
 *
 *   - the `/usr/local/bin/<name>` stub binaries inside the rootfs, and
 *   - the `--native-offload=<socket>:<name1,name2,...>` proot argument.
 *
 * Before Phase 1 the main process owned the actual handler INSTANCES in
 * `NativeOffloadServer.registeredHandlers` (a ConcurrentHashMap) and
 * consulted that map to build those strings. After Phase 1 the handler
 * instances move into the `:toolservice` process (the sole socket owner);
 * the main process only ever needs the NAMES, which is exactly what this
 * catalog provides — so stub/arg generation no longer depends on the main
 * process holding a handler instance.
 *
 * The tool:service process registers instances for every name in this list
 * (via the same names), so the stubs/args generated here always line up with
 * what the server can dispatch.
 *
 * DEBUG-only handlers (minis-debug) are excluded on Release so a Release
 * build never materializes a stub for a handler that isn't registered
 * anywhere.
 */
object OffloadHandlerCatalog {

    /** Non-debug handler names — always valid on every build type. */
    val baseHandlerNames: List<String> = listOf(
        "android-alarm",
        "android-calendar",
        "android-clipboard",
        "android-contacts",
        "android-device",
        "android-location",
        "android-notification",
        "android-open",
        "android-photos",
        "android-player",
        "android-speak",
        "android-speech",
        "android-weather",
        "android-a11y-cli",
        "minis-model-use",
        "minis-config",
        "minis-browser-use",
        "minis-sessions-cli",
        "android-shizuku-cli",
        // "minis-debug" is DEBUG-only, appended below.
    )

    /**
     * Every handler name installed in the current build type. Consumed by
     * stub/arg generation in the main process; the :toolservice process
     * registers matching instances.
     */
    val allHandlerNames: List<String>
        get() = if (BuildConfig.DEBUG) baseHandlerNames + "minis-debug" else baseHandlerNames
}
