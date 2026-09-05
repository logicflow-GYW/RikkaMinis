package com.openminis.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.StringFormat
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.backup.MultiDeviceSync
import com.openminis.app.backup.WebDavClient
import com.openminis.app.backup.WebDavConfigStore
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.ui.chat.clearMarkdownParseCachesForMemoryPressure
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.notification.BackgroundTaskNotifier
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.MountedFolderCoordinator
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.offload.AccessibilityOffloadHandler
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.BrowserUseOffloadHandler
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ClipboardOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.ModelUseOffloadHandler
import com.openminis.app.sandbox.offload.SessionsOffloadHandler
import com.openminis.app.sandbox.offload.ShizukuOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PhotosOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.SpeakOffloadHandler
import com.openminis.app.sandbox.offload.SpeechOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler
import com.openminis.app.service.MemoryPressureGate
import com.openminis.app.service.MemoryPressureLevel
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.TrimPolicy
import com.openminis.app.ui.MinisImageFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MinisApp : Application(), ImageLoaderFactory {
    /**
     * App-scoped coroutine scope that survives UI composition. Network /
     * file work that MUST complete even if the user leaves the current
     * screen (e.g. WebDAV backup upload / restore) launches here instead of
     * on a rememberCoroutineScope, whose cancellation would abort the
     * transfer the moment the composable leaves the hierarchy. SupervisorJob
     * so a failing child never cancels siblings. Property access is safe from
     * any thread (initialised in the constructor, read-only thereafter).
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: AppDatabase
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var providerRepository: ProviderRepository
        private set
    lateinit var envVarRepository: EnvVarRepository
        private set
    lateinit var skillRepository: SkillRepository
        private set
    lateinit var mcpRepository: MCPRepository
        private set
    lateinit var memoryRepository: MemoryRepository
        private set
    lateinit var webAppShortcutRepository: WebAppShortcutRepository
        private set
    lateinit var backgroundSettingsRepository: BackgroundSettingsRepository
        private set
    lateinit var backgroundTaskNotifier: BackgroundTaskNotifier
        private set
    lateinit var mountedFoldersStore: MountedFoldersStore
        private set

    /**
     * T180-bg-notif: foreground-Activity counter, mutated by the
     * ActivityLifecycleCallbacks registered in [onCreate]. Read by
     * [BackgroundTaskNotifier] to decide whether to suppress completion
     * notifications (no notification while the user is already looking
     * at the app).
     */
    @Volatile
    private var foregroundActivityCount: Int = 0

    fun isAppForeground(): Boolean = foregroundActivityCount > 0

    /**
     * T-multidevice: kick off a multi-device auto-sync when the app comes to
     * the foreground, but ONLY if the user turned the feature on. The actual
     * WebDAV round-trip runs on [applicationScope] so it never blocks activity
     * startup, and is fully guarded — a failure (network down, bad creds, no
     * folder) is logged and swallowed, never allowed to surface into normal
     * app operation. First launch is naturally covered: the first Activity's
     * onActivityStarted reports wasBackgrounded=true.
     */
    fun syncMultiDeviceIfEnabled() {
        if (!MultiDeviceSync.isEnabled(this)) return
        applicationScope.launch {
            runCatching {
                val config = WebDavConfigStore(this@MinisApp).load()
                if (config == null) {
                    AppLogger.info("MultiDeviceSync", "enabled but no WebDAV config; not starting")
                    return@launch
                }
                val result = MultiDeviceSync.syncNow(
                    context = this@MinisApp,
                    providerRepo = providerRepository,
                    envVarRepo = envVarRepository,
                    memoryRepo = memoryRepository,
                    config = config,
                    client = WebDavClient.defaultClient(),
                    includeSecrets = MultiDeviceSync.hasConfirmedSecretsSync(this@MinisApp),
                )
                AppLogger.info("MultiDeviceSync", result)
            }.onFailure {
                AppLogger.warning("MultiDeviceSync", "sync failed: ${it.message}")
            }
        }
    }

    /**
     * T-bg-overlay phase 2: live "is the app foreground?" stream so the
     * AgentForegroundService can react to background ↔ foreground
     * transitions and toggle the floating tool-status overlay. Same
     * source as [isAppForeground] (started/stopped balanced count) —
     * just exposed as a StateFlow for collectors.
     */
    // Initial true: prevents overlay flash before first Activity onStart emits foreground=true
    // (T-overlay-startup-flash). ActivityLifecycleCallbacks below will flip to the real value
    // on the next lifecycle tick.
    private val _isAppForegroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isAppForegroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _isAppForegroundFlow

    /**
     * App-wide network monitor. Mirrors iOS NetworkMonitor.shared — observes
     * connectivity transitions, evicts shared OkHttp connection pools, and
     * refreshes the sandbox's /etc/resolv.conf when DNS servers change.
     */
    val networkMonitor: NetworkMonitor = NetworkMonitor()

    /**
     * Application-scoped BrowserTabPool for shell-invoked `minis-browser-use`.
     * Separate from the per-ChatViewModel pool so browser state driven from
     * within an ish shell doesn't collide with the agent's own tabs.
     */
    val sharedBrowserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(this).also { it.setSession("minis-browser-use") }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // T283: install ACRA before any app-level singleton runs so a crash
        // anywhere from onCreate forward is captured. CrashFileSender
        // (registered via META-INF/services/org.acra.sender.ReportSenderFactory)
        // writes filesDir/logs/crash-<stamp>.log — same dir + .log extension
        // that AppLogger.listLogFiles already filters for, so reports surface
        // in LogManagementScreen with no extra UI.
        ACRA.init(
            this,
            CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportFormat(StringFormat.JSON)
                .withLogcatArguments(listOf("-t", "200", "-v", "time"))
                .withReportContent(
                    ReportField.APP_VERSION_NAME,
                    ReportField.APP_VERSION_CODE,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                ),
        )
    }

    override fun onCreate() {
        super.onCreate()

        // T287-followup: ACRA spawns a separate reporter process named
        // "<package>:acra" (declared by the library's manifest) to send
        // the crash report after the main process dies. Application
        // subclasses run in EVERY process of the package, so without
        // this early-return the :acra process would also try to bind
        // the abstract socket / boot the database / register offload
        // handlers — racing the next main-process spawn for resources
        // it doesn't need. Symptom: main process gets EADDRINUSE on
        // LocalServerSocket and stays in a permanent restart loop on
        // the splash screen. Skip everything except ACRA.init (already
        // done in attachBaseContext, which is what makes the :acra
        // process do its job).
        if (ACRA.isACRASenderServiceProcess()) {
            Log.i("MinisApp", "skipping app init in :acra reporter process")
            return
        }

        // [model-exec-service] The :modelservice process (ModelExecutionService)
        // only runs short-lived LLM provider calls and must NEVER touch the
        // heavy subsystems (Room DB, offload server, PRoot, repositories) —
        // doing so would re-bind the native-offload abstract socket and race
        // the main process, exactly like the :acra early-return above. It only
        // needs: EncryptedPrefsFactory (api key), ProviderFactory, and the
        // provider implementations. FastModePrefs is primed first (T-codex-
        // fast-mode) because OpenAIProvider's request-build path reads it.
        if (isModelServiceProcess()) {
            Log.i("MinisApp", "skipping app init in :modelservice process")
            com.openminis.app.data.FastModePrefs.prime(this)
            return
        }

        // [native-oom Phase 1] The :toolservice process (ToolExecutionService)
        // will own the native-offload socket + handler registry. It must
        // NEVER run the main-process heavy init (Room, PRoot, offload-server
        // bind, repositories) — it only builds the tool-specific dependency
        // graph inside ToolExecutionService. This branch is dormant until the
        // socket ownership actually moves to :toolservice (Migration step 3);
        // right now nothing spawns this process.
        if (isToolServiceProcess()) {
            Log.i("MinisApp", "skipping app init in :toolservice process")
            com.openminis.app.data.FastModePrefs.prime(this)
            return
        }

        // [T-codex-fast-mode] Capture the app context + warm the Fast Mode
        // flag cache so the provider layer (no Context) can read it at
        // request-build time — including offload / title-gen calls that
        // never pass through a ViewModel.
        com.openminis.app.data.FastModePrefs.prime(this)

        // [T-provider-key-roulette] Warm the LRU multi-key rotation state so
        // the first provider build of the process rotates against the
        // cold-start file hint instead of always picking key #1.
        com.openminis.app.data.KeyRoulette.init(cacheDir)

        // [D-2] Warm the cross-session concurrency cap from prefs so the three
        // coordinated gates (SessionConcurrencyManager / ExecutionCoordinator /
        // NativeOffloadServer) read the user-configured value at first use.
        // Runs before ExecutionCoordinator.init / NativeOffloadServer.start.
        com.openminis.app.data.ConcurrencyPrefs.prime(this)

        // T283: install NDK signal handler for native crashes (SIGSEGV/
        // SIGABRT/SIGBUS/SIGFPE/SIGILL/SIGSYS). Writes a one-shot text
        // report to filesDir/logs/native-crash-<stamp>.log before re-raising
        // the signal so the system tombstone is also generated. Runs
        // before any other native lib (proot, pty_bridge, …) is dlopen'd
        // by the rest of onCreate so the handler is in place when those
        // libs first execute.
        try {
            com.openminis.app.crash.NativeCrashHandler.install(
                java.io.File(filesDir, "logs"),
            )
        } catch (t: Throwable) {
            Log.w("MinisApp", "NativeCrashHandler install failed: ${t.message}")
        }

        // T-android-fgs-timeout-crash: chain an UncaughtExceptionHandler
        // ahead of ACRA's so we can intercept
        // android.app.RemoteServiceException$ForegroundServiceDidNotStopInTimeException
        // specifically. The mediaPlayback FGS type change removes the
        // dataSync 6h cap that was tripping this, but this handler keeps
        // the user from seeing a raw process-death if a future Android
        // version adds a new cap to mediaPlayback too. We can't actually
        // survive this exception (it's thrown on the main looper after
        // SystemServer has already decided to kill us) but we CAN:
        //  - stop the foreground service explicitly so the notification
        //    drops cleanly instead of lingering as a zombie row
        //  - delegate to ACRA so the crash log still hits disk
        try {
            val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val isFgsTimeout = throwable.javaClass.name.endsWith(
                        "RemoteServiceException\$ForegroundServiceDidNotStopInTimeException",
                    ) || (throwable.message?.contains("foreground service of type") == true &&
                        throwable.message?.contains("did not stop within its timeout") == true)
                    if (isFgsTimeout) {
                        Log.w(
                            "MinisApp",
                            "FGS timeout caught; stopping service before deferring to ACRA: ${throwable.message}",
                        )
                        // Stop the service so the system tears the
                        // sticky binding down cleanly instead of
                        // re-spawning into the same trap.
                        runCatching {
                            val intent = Intent(this, com.openminis.app.service.AgentForegroundService::class.java)
                            stopService(intent)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MinisApp", "FGS-timeout handler internal failure: ${t.message}")
                }
                // Always defer to the prior handler so ACRA's
                // dump-and-relaunch flow runs intact.
                priorHandler?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.w("MinisApp", "install FGS-timeout handler failed: ${t.message}")
        }

        // T-android-crash-freq-share: local fallback for Crashlytics (#458).
        // Scan filesDir/logs/ for crash-*.log + native-crash-*.log files
        // touched in the last hour; if THRESHOLD+ are present, stash the
        // list so MainActivity.onCreate can prompt to share them.
        com.openminis.app.crash.CrashFrequencyDetector.checkAtLaunch(this)

        // Hard short-circuit: when checkAtLaunch flips safe-mode ON, skip
        // every heavy subsystem (DB, repositories, offload server, PRoot
        // bind mounts, network monitor, …). The only thing MainActivity
        // will do is pop the share-or-dismiss dialog and finish. Without
        // this guard, anything from `chatRepository = ChatRepository(...)`
        // onward is a potential re-crash trigger on a loop — the whole
        // point of safe-mode is to stop the bleeding before another
        // segfault rewrites the log files.
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w("MinisApp", "safe-mode ON — skipping app subsystem init")
            return
        }

        // Initialize the daily-rotating file logger first. When the user has
        // logging enabled in Settings, this also kicks off stdout/stderr
        // capture so subsequent println / Throwable.printStackTrace lines from
        // the rest of onCreate land in today's log file. Mirrors iOS
        // `LoggingManager.startIfEnabled()` (called from MinisApp.swift:143).
        AppLogger.init(this)

        // Bug 2 (MIUI silent kill) diagnostic: write a launch-cycle beacon
        // so a subsequent launch can observe whether the previous run
        // exited cleanly (onTerminate hit) or was force-killed by LMK /
        // MIUI's aggressive background cleaner. Read on next launch by
        // [com.openminis.app.diagnostics.LaunchCycleBeacon].
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordLaunch(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordLaunch failed: ${t.message}")
        }

        // Start the main-thread hang watchdog before the heavier subsystems
        // (DB / repositories / iSH bring-up) get going so it can observe any
        // stall in onCreate itself. Posts heartbeats at 1s cadence; if the
        // main thread fails to land one for 3s, the detector dumps the main
        // stack to filesDir/logs/stall-<date>.log and bumps a persisted
        // counter. AppNavigation reads that counter on cold start to
        // override the launch destination to home after 3 hangs in a row,
        // so a user trapped opening a session that hangs the UI gets
        // unstuck on the next launch.
        com.openminis.app.diagnostics.HangDetector.start(this)

        database = AppDatabase.getInstance(this)
        chatRepository = ChatRepository(
            database.chatDao(),
            com.openminis.app.data.storage.SessionFileStore(this),
        )
        providerRepository = ProviderRepository(this)
        envVarRepository = EnvVarRepository(this)
        skillRepository = SkillRepository(this)
        mcpRepository = MCPRepository(this)
        memoryRepository = MemoryRepository(java.io.File(filesDir, "minis-global/memory"))

        webAppShortcutRepository = WebAppShortcutRepository(database.webAppShortcutDao())

        // [T-soul-md] Seed SOUL.md with the default content on first launch
        // so the Soul settings page and chat bubble identity have a real
        // file to read. Safe no-op on subsequent launches — never
        // overwrites existing user edits. Cache refresh primes the
        // synchronous metadata read-path (chat header / system prompt).
        com.openminis.app.agent.SoulStore.ensureExists(this)
        com.openminis.app.agent.SoulStore.refreshCache(this)

        // T-config: minis-config CLI surface — registry / audit log /
        // master-switch store. Initialized eagerly here so
        // ConfigRegistry.get() is safe from any thread for the rest of
        // the process. Mirrors iOS ConfigRegistry.shared.registerBuiltinsIfNeeded().
        com.openminis.app.config.MinisConfigPermissionStore.init(this)
        com.openminis.app.config.ConfigRegistry.init(
            this, providerRepository, envVarRepository, chatRepository,
            // [T8-2] Config layer no longer imports service/UI classes —
            // session-id provider is injected here.
            { com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId },
        )

        // Initialize models.dev registry (loads from bundled asset, refreshes in background)
        ModelsDevApi.init(this)

        // Initialize sandbox singletons (does not trigger extraction)
        RootfsManager.getInstance(this)
        ExecutionCoordinator.init(this)
        ExecutionCoordinator.envVarRepository = envVarRepository

        // Privacy Mode store + redactor wiring. Mirrors iOS
        // EnvVarPrivacyStore.init / EnvVarRedactor static handoff.
        com.openminis.app.data.EnvVarPrivacyStore.init(this)
        com.openminis.app.data.EnvVarRedactor.envVarRepository = envVarRepository

        // Start network monitoring — mirrors iOS NetworkMonitor.shared.start().
        // The monitor writes /etc/resolv.conf immediately and on every
        // ConnectivityManager callback so shells inside the sandbox see fresh
        // DNS servers after Wi-Fi ↔ cellular swaps or VPN toggles.
        networkMonitor.start(this)

        // Register global /var/minis/{memory,skills,shared} bind mounts up-front
        // so direct file I/O tools (file_read) resolve these paths even before
        // PRoot has booted or any shell has started.
        PRootKernel.registerGlobalBindMounts(this)

        // T219-1: load user-mounted external folders and seed PRoot's
        // bindMounts before the first proot invocation, so the very first
        // `shell_execute` already has `/var/minis/mounts/<name>/` visible.
        // Entries whose SAF tree URI didn't resolve to a real POSIX path
        // (cloud providers, unmounted SD card) are silently skipped by
        // bindMountSpecs.
        mountedFoldersStore = MountedFoldersStore(this)
        // T219-5: hand the singleton to PRootKernel so applyMountedFoldersSnapshot
        // can read the live state, and wire an onChange callback so any UI CRUD
        // (add/remove/rename/toggle) re-applies the snapshot.
        // T277: PersistentShell reuses one PRoot process per chat session for the
        // session's lifetime, so an applyMountedFoldersSnapshot call alone never
        // reaches the live shell — proot's `-b` argv is frozen at spawn time.
        // Kill any live shells so the next execute() rebuilds them with the
        // updated bind set. Mount CRUD is a Settings-screen action; the user
        // is not in chat mid-command, so this restart is safe and user-invisible.
        PRootKernel.mountedFoldersStore = mountedFoldersStore
        mountedFoldersStore.onChange = {
            PRootKernel.applyMountedFoldersSnapshot(this)
            ExecutionCoordinator.stopCurrentCommand()
        }
        // T219-6: route launch-time seeding through applyMountedFoldersSnapshot
        // so it (a) reads the live store consistently and (b) materializes the
        // /var/minis/mounts/<name> placeholder dirs that PRoot's `-b` needs.
        // Note: this runs before PRootKernel.boot, so rootfs may not yet exist —
        // applyMountedFoldersSnapshot tolerates that case (mkdirs fails silently
        // and PRootKernel.boot calls applyMountedFoldersSnapshot again at the
        // end of boot to materialize the targets once rootfs is on disk).
        PRootKernel.applyMountedFoldersSnapshot(this)

        // Register native_offload handlers and start the server eagerly —
        // the server only needs the rootfs tmp directory, which can be
        // materialized lazily. Starting here means the abstract socket is
        // reachable even before any shell session is launched.
        NativeOffloadServer.register("android-alarm", AlarmOffloadHandler(this))
        NativeOffloadServer.register("android-calendar", CalendarOffloadHandler(this))
        NativeOffloadServer.register("android-clipboard", ClipboardOffloadHandler(this))
        NativeOffloadServer.register("android-contacts", ContactsOffloadHandler(this))
        NativeOffloadServer.register("android-device", DeviceOffloadHandler(this))
        NativeOffloadServer.register("android-location", LocationOffloadHandler(this))
        NativeOffloadServer.register("android-notification", NotificationOffloadHandler(this))
        NativeOffloadServer.register("android-open", OpenOffloadHandler(this))
        NativeOffloadServer.register("android-photos", PhotosOffloadHandler(this))
        NativeOffloadServer.register("android-player", PlayerOffloadHandler())
        NativeOffloadServer.register("android-speak", SpeakOffloadHandler(this))
        NativeOffloadServer.register("android-speech", SpeechOffloadHandler(this))
        NativeOffloadServer.register("android-weather", WeatherOffloadHandler(this))
        // T323: UI-layer automation backed by MinisAccessibilityService.
        NativeOffloadServer.register("android-a11y-cli", AccessibilityOffloadHandler(this))
        NativeOffloadServer.register("minis-model-use", ModelUseOffloadHandler(this, providerRepository))
        // T-config: minis-config — agent-facing settings management
        // (read/write registered ConfigFields with audit + revert).
        // Mirrors iOS `config_offload_register()` in ISHKernel.m.
        NativeOffloadServer.register(
            "minis-config",
            com.openminis.app.sandbox.offload.ConfigOffloadHandler(),
        )
        NativeOffloadServer.register("minis-browser-use", BrowserUseOffloadHandler(this))
        // T188: minis-sessions-cli — agent-side query of chat history.
        // Registers next to the other minis-* tools so PRootKernel.
        // installHandlerStubs() picks it up on the next rootfs boot
        // (writes a 17-byte exit-0 stub at /usr/local/bin/minis-sessions-cli
        // so PATH lookup succeeds; PRoot intercepts the execve before
        // the stub runs and routes to this handler).
        NativeOffloadServer.register("minis-sessions-cli", SessionsOffloadHandler(chatRepository, this))
        // T322: android-shizuku-cli — privileged Android control via Shizuku.
        // The handler short-circuits with a typed error envelope when the
        // user hasn't installed / started / authorized Shizuku, so we
        // can register unconditionally; ShizukuManager.init below wires
        // up the binder lifecycle listeners + StateFlow.
        NativeOffloadServer.register("android-shizuku-cli", ShizukuOffloadHandler(this))
        com.openminis.app.offload.ShizukuManager.init(this)

        // T-android-minis-debug-cli: shell-side CLI wrapper around the in-app
        // DebugServer (127.0.0.1:5321) JSON-RPC. DEBUG-only — Release builds
        // ship neither the DebugServer nor this handler, so the
        // `/usr/local/bin/minis-debug` stub is also absent (PRootKernel.
        // installHandlerStubs enumerates currently-registered handlers).
        if (BuildConfig.DEBUG) {
            NativeOffloadServer.register(
                "minis-debug",
                com.openminis.app.sandbox.offload.DebugOffloadHandler(this),
            )
        }

        NativeOffloadServer.start(RootfsManager.getInstance(this).rootfsDir)

        // Initialize session activity tracker for foreground service management
        SessionActivityTracker.init(this)

        // [memory-pressure-gate] Wire the process-RSS gate into app-level
        // resources: reclaim hook = idle shell teardown (releases PRoot
        // tracer native footprint) + browser tab eviction + GC; pressure
        // listener = structured log line. Without these, acquireSlot's
        // memory check would have nothing to reclaim and no signal.
        MemoryPressureGate.reclaimHook = {
            runCatching { ExecutionCoordinator.recycleIdleShells() }
            runCatching { sharedBrowserTabPool.evictIdleTabs() }
        }
        MemoryPressureGate.pressureListener = { level, rssMB ->
            AppLogger.warning("MemoryPressureGate", "level=$level rss=${rssMB}MB — " +
                (if (level == MemoryPressureLevel.CRITICAL) "admission throttled (reclaim + 2s wait)" else "admission delayed 500ms"))
        }

        // [offload-rss-governance] 把 OffloadRssProbe 的「观测」接到「治理」：
        // 当 offload handler 的累计/单次 VmRSS 增量越过阈值时，回收 idle 的
        // PRoot tracer（释放其 native 足迹）+ 触发 GC 善后。补上之前「只打点
        // 不回收」的口径漏洞——泄漏涨在 app 主进程（offload server / native
        // 映射），而非 PRoot child，之前的 child-RSS 高水位读不到它。
        com.openminis.app.sandbox.OffloadRssProbe.governanceHook = {
            runCatching { ExecutionCoordinator.recycleIdleShells() }
            runCatching { sharedBrowserTabPool.evictIdleTabs() }
        }

        // [T-android-session-paused-badge] Per-session badge-state queue
        // displayed in the session-list cell corner. Init early so the
        // session list can read persisted PAUSED badges on first compose.
        com.openminis.app.service.SessionBadgeStore.init(this)

        // [T-android-session-paused-badge-hardkill] Reconcile PAUSED badges
        // against the DB's interrupted-session set. The lifecycle-callback push
        // (onActivityStarted, below) only fires on a graceful background→
        // foreground round-trip; a hard kill (force-quit / process death) never
        // runs it, so the badge would be missing after restart. The persisted
        // message tail is the durable source of truth — scan it off-main and
        // reconcile. Runs after init() so it merges with the restored queues.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
            // Exclude any session that is already actively streaming (defensive;
            // at cold start this is empty, but keeps the rule "active ⇒ never
            // paused" uniform with the foreground reconcile path).
            val active = SessionActivityTracker.activeSessions.value
            com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
        }

        // T180-bg-notif: background-settings + task-completion notifier.
        // The notifier is wired into SessionActivityTracker's completion
        // hook so any session whose stream finishes (success or error)
        // posts a tap-to-open notification when the app is backgrounded.
        // Mirrors iOS BackgroundKeepAliveManager.postBackgroundTaskNotification.
        backgroundSettingsRepository = BackgroundSettingsRepository(this)
        backgroundTaskNotifier = BackgroundTaskNotifier(
            context = this,
            chatRepository = chatRepository,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        SessionActivityTracker.setCompletionListener { sessionId, isError ->
            backgroundTaskNotifier.notifyTaskCompleted(sessionId, isError)
        }

        // [T-android-config-confirm-timeout] Wire the config-confirm background
        // notifier into the (Context-free) gate, so a minis-config approval that
        // is waiting while the app is backgrounded nudges the user before the
        // 120s timeout. Mirrors iOS ConfigConfirmationGate.notifyIfBackgrounded.
        val configConfirmNotifier = com.openminis.app.notification.ConfigConfirmNotifier(
            context = this,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        com.openminis.app.config.confirm.ConfigConfirmationGate.backgroundNotifier = {
            configConfirmNotifier.notifyIfBackgrounded(it)
        }
        com.openminis.app.config.confirm.ConfigConfirmationGate.cancelNotification = {
            configConfirmNotifier.cancel(it)
        }

        // Track foreground state via ActivityLifecycleCallbacks. Counting
        // started/stopped balances out around configuration changes (the
        // Activity is briefly destroyed-then-created, so the count would
        // momentarily drop to zero if we used onResume/onPause). Started/
        // stopped is more conservative — counts non-zero while the
        // Activity is even partially visible.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                val wasBackgrounded = foregroundActivityCount == 0
                foregroundActivityCount++
                if (wasBackgrounded) _isAppForegroundFlow.value = true
                // T-MIUI-FGS-race: app came to the foreground — unhide FG
                // service starts and refresh it if a session was running.
                // Must set the flag BEFORE the sync / badge work so a
                // concurrent setActive in the stream can start the service.
                if (wasBackgrounded) {
                    SessionActivityTracker.setAppForeground(true)
                    SessionActivityTracker.maybeRefreshService()
                }
                // T-multidevice: app came to the foreground (first launch or
                // background→foreground). Trigger an auto-sync if enabled —
                // the first launch pulls the latest remote snapshot, a resume
                // after edits refetches any sibling changes. Cheap no-op when
                // the feature is off or no WebDAV is configured.
                if (wasBackgrounded) syncMultiDeviceIfEnabled()
                // T298: as soon as the app transitions background → foreground,
                // clear any task-completed notifications still in the tray.
                // The user is back in front of the app — there's no point
                // making them swipe away a "task completed" entry for the
                // result they're about to look at directly.
                if (wasBackgrounded && ::backgroundTaskNotifier.isInitialized) {
                    backgroundTaskNotifier.cancelAllCompletedNotifications()
                }
                // [T-android-session-paused-badge-hardkill] On foreground,
                // reconcile PAUSED badges from the DB tail (authoritative
                // interrupted-state) instead of the old heuristic that marked
                // every STILL-ACTIVE session paused. That heuristic was wrong:
                // a session still in activeSessions after backgrounding kept
                // RUNNING (keep-alive) — it is executing, not paused — which
                // surfaced a ⏸ badge on a live, spinning session. The DB tail
                // only looks "interrupted" for a session whose loop is actually
                // stranded; we additionally exclude currently-active sessions so
                // a mid-loop running session is never flagged.
                if (wasBackgrounded) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val interrupted = runCatching { chatRepository.interruptedSessionIds() }.getOrElse { emptySet() }
                        val active = SessionActivityTracker.activeSessions.value
                        com.openminis.app.service.SessionBadgeStore.reconcileInterruptedSessions(interrupted - active)
                    }
                }
            }
            override fun onActivityResumed(activity: Activity) {
                // T-android-crash-freq-share: if checkAtLaunch flagged a
                // recent burst, show the share-logs dialog on the first
                // Activity that resumes. One-shot — clears the pending
                // list internally so config-change re-resumes don't
                // re-prompt. Safe no-op when nothing is pending.
                com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(activity)
                // T219-1: re-probe mounted-folder writability on every
                // foreground resume so OS permission revocations (user
                // toggled "Allow access" off in system Files, removable
                // storage unmounted, etc.) propagate into the UI badge
                // and the read-only enforcement gate. Mirrors iOS
                // MountedFoldersManager.refreshAllWritability().
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { mountedFoldersStore.refreshWritability() }
                }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                if (foregroundActivityCount == 0) {
                    _isAppForegroundFlow.value = false
                    // T-MIUI-FGS-race: app is now fully invisible — stop
                    // initiating new FG-service starts so a background
                    // kill can't race a startForeground deadline.
                    SessionActivityTracker.setAppForeground(false)
                    // [T-android-config-confirm-timeout] The user switched away
                    // while a config-confirm dialog may still be showing — nudge
                    // them so they can come back before the 120s timeout.
                    com.openminis.app.config.confirm.ConfigConfirmationGate.notifyPending()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Initialize offload permission manager
        OffloadPermissionManager.init(this)

        // [P2-proot-native-leak] Background shell-recycle sweeper. Long-lived
        // PRoot persistent shells leak native memory monotonically (measured
        // 6.2-6.9GB on 2026-08-07, enough to OOM the device). The sweeper runs
        // on the app IO scope and terminates any shell idle for >10 min
        // (see ExecutionCoordinator.recycleIdleShells); the next command in
        // that session transparently re-spawns a fresh PRoot. Post-command
        // completion also enforces a hard 512MB native-heap water mark. Cheap
        // no-ops when no shells exist.
        applicationScope.launch {
            while (isActive) {
                try {
                    ExecutionCoordinator.recycleIdleShells()
                    // [P2-proot-resource-hygiene] On the same low cadence, sweep
                    // PRoot's temp cache once no shell needs it. Long-running
                    // usage accumulates transients in cache/proot-tmp which grow
                    // disk/IO pressure over weeks (a "flash crash" contributor).
                    // No-op when any shell is alive (see implementation).
                    ExecutionCoordinator.cleanupProotTmp()
                } catch (t: Throwable) {
                    Log.w("MinisApp", "recycleIdleShells failed: ${t.message}")
                }
                kotlinx.coroutines.delay(com.openminis.app.sandbox.ExecutionCoordinator.IDLE_SWEEP_INTERVAL_MS)
            }
        }

        // Initialize speech-recognition adapter layer (system + provider engines).
        com.openminis.app.speech.SpeechRecognitionManager.init(this)

        // Refresh model lists once per calendar day (mirrors iOS MinisApp.swift).
        // Runs per-instance in parallel; `autoRefreshModels` skips instances with custom models.
        providerRepository.refreshAllModelsIfNeeded(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        )

        // Propagate system timezone and HTTP-proxy changes into the sandbox.
        // iOS recomputes TZ for every command (ISHShellExecutor.m:335-353);
        // here we update PRootKernel.customEnvironment and push `export …`
        // into every live shell so interactive sessions pick up the change
        // without a restart.
        val sandboxSystemReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastTimezoneChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastTimezoneChange failed: ${t.message}")
                        }
                    }
                    android.net.Proxy.PROXY_CHANGE_ACTION -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastProxyChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastProxyChange failed: ${t.message}")
                        }
                    }
                }
            }
        }
        registerReceiver(
            sandboxSystemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(android.net.Proxy.PROXY_CHANGE_ACTION)
            },
        )

        // Debug server: only start in debug builds (NEVER in release)
        if (BuildConfig.DEBUG) {
            try {
                com.openminis.app.debug.DebugServer(this).start()
            } catch (e: Exception) {
                Log.w("MinisApp", "Failed to start debug server: ${e.message}")
            }
        }

        // T268: one-shot migration of pre-T266 internal alarms into the
        // system Clock app. Pre-T266 builds wrote alarms into Minis's own
        // SharedPreferences + AlarmManager; T266 retired that path but old
        // installs still have ghost entries that fire only inside Minis.
        // Replay each future-dated entry through the same SET_ALARM /
        // SET_TIMER intents the new path uses, then clear prefs so the
        // migration runs at most once. Wrapped in runCatching so an
        // unexpected prefs shape never blocks app launch.
        runCatching { migrateGhostAlarms() }
            .onFailure { Log.w("MinisApp", "ghost alarm migration failed: ${it.message}") }
    }

    /**
     * T268: replay any pre-T266 internal alarm/timer entries from
     * minis_alarms_prefs through SET_ALARM / SET_TIMER, then clear the
     * prefs blob so subsequent launches no-op. Past-dated entries are
     * dropped (the OS never re-fires them anyway). Idempotent: if the
     * blob is missing or empty the function returns immediately.
     *
     * Silent migration rather than an in-app dialog — Application has no
     * Activity context to host one, and the user-visible outcome (alarms
     * reappear in their Clock app) is what they want regardless of any
     * prompt. AlarmOffloadManager's PendingIntents are left in place; the
     * OS will fire them once more if scheduled, but T268 also clears the
     * prefs blob that AlarmOffloadHandler previously read, so list/cancel
     * commands will no longer surface them.
     */
    /**
     * [native-oom Phase 1] True when the current process is the isolated
     * `:toolservice` process (see [ToolExecutionService]). Used by [onCreate]
     * to skip the heavy main-process subsystem initialisation (below) and
     * instead let ToolExecutionService own the tool-specific dependency
     * graph — mirroring how `:modelservice` skips app init.
     */
    private fun isToolServiceProcess(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()?.endsWith(":toolservice") == true
            } else {
                // minSdk 26 (< API 28): fall back to ActivityManager pid lookup.
                val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
                val pid = android.os.Process.myPid()
                am.runningAppProcesses?.any { it.pid == pid && it.processName.endsWith(":toolservice") } == true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * [model-exec-service] True when the current process is the isolated
     * `:modelservice` process (see [ModelExecutionService]). Used by
     * [onCreate] to skip the heavy subsystem initialisation that would
     * otherwise race the main process for the native-offload abstract
     * socket / Room / PRoot.
     */
    private fun isModelServiceProcess(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()?.endsWith(":modelservice") == true
            } else {
                // minSdk 26 (< API 28): fall back to ActivityManager pid lookup.
                val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
                val pid = android.os.Process.myPid()
                am.runningAppProcesses?.any { it.pid == pid && it.processName.endsWith(":modelservice") } == true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun migrateGhostAlarms() {
        val prefs = getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("alarms_json", null) ?: return
        if (raw.isBlank() || raw == "[]") return
        val arr = org.json.JSONArray(raw)
        if (arr.length() == 0) {
            prefs.edit().remove("alarms_json").apply()
            return
        }
        val now = System.currentTimeMillis()
        var migrated = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val triggerAt = entry.optLong("triggerAtMs", 0L)
            if (triggerAt in 1L..now && entry.optString("type") == "timer") {
                skipped++; continue  // Past timer — nothing to recover.
            }
            if (triggerAt in 1L..now && entry.optString("repeatMode", "ONCE") == "ONCE") {
                skipped++; continue  // Past one-shot alarm.
            }
            val migrationOk = runCatching {
                if (entry.optString("type") == "timer") {
                    val secs = entry.optInt("durationSec", -1)
                    val remaining = ((triggerAt - now) / 1000L).toInt()
                    if (remaining <= 0 && secs <= 0) return@runCatching false
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (remaining > 0) remaining else secs)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Timer"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                } else {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, entry.optInt("hour", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, entry.optInt("minute", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Alarm"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (migrationOk) migrated++ else skipped++
        }
        // Clear the blob unconditionally — entries we couldn't replay are
        // still useless ghosts, and leaving the blob would re-trigger
        // migration on every launch.
        prefs.edit().remove("alarms_json").apply()
        Log.i("MinisApp", "T268 ghost alarm migration: migrated=$migrated skipped=$skipped (prefs cleared)")
    }

    /**
     * Coil global ImageLoader — registers [MinisImageFetcher] so `minis://`
     * URIs in Markdown images (e.g. `![alt](minis://attachments/x.png)`)
     * resolve to local files under /var/minis/.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(MinisImageFetcher.Factory())
                add(MinisImageFetcher.UriFactory())
                // T-image-cache-mtime-35133: include File.lastModified() in
                // memory + disk cache key so Grok-style in-place rewrites of
                // minis://attachments/foo.jpg invalidate Coil's cached bitmap.
                add(MinisImageFetcher.MtimeKeyer())
                add(MinisImageFetcher.StringMtimeKeyer())
            }
            .build()

    override fun onTerminate() {
        // onTerminate is called only on emulators or when the system
        // explicitly tears down — real devices usually skip it. Still
        // worth marking the beacon: a present clean_exit on a real
        // device proves we shut down voluntarily; its absence is the
        // signal we care about for [LaunchCycleBeacon].
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordCleanExit(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordCleanExit failed: ${t.message}")
        }
        super.onTerminate()
    }

    /**
     * [memory-pressure-gate] System memory pressure callback. The OS calls
     * this on the main process when the whole device is under pressure —
     * the last signal we get before [onLowMemory] / process death.
     *
     * Defense ladder (in addition to the browser tab pool which already
     * trims its own WebViews via its own ComponentCallbacks2 registration):
     * - TRIM_MEMORY_RUNNING_MODERATE+: force a synchronous GC pass and
     *   reclaim idle shells (releases the PRoot tracer's native footprint).
     * - TRIM_MEMORY_RUNNING_CRITICAL: also notify [MemoryPressureGate] so
     *   new agent-loop slots wait for memory to recover before acquiring.
     *
     * Everything is wrapped in runCatching: a misbehaving recycle step must
     * never turn the trim callback itself into a second crash.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            if (TrimPolicy.shouldReclaimShellsAndGc(level)) {
                // [P2-active-session-guard] Foreground pressure: recycle idle
                // shells unconditionally (that's side-effect-free for in-flight
                // work — only shells idle past the timeout are recycled), but
                // SKIP the synchronous System.gc() when a session is actively
                // executing (stream or tool in flight). A forced GC mid-execution
                // pauses all threads and can stall/abort the very task that's
                // driving the pressure — degrading the "peak-hour" experience the
                // trim callback is supposed to protect.
                val hasActiveWork =
                    SessionActivityTracker.activeSessions.value.isNotEmpty() ||
                        SessionActivityTracker.isToolRunning.value
                Log.w(
                    "MinisApp",
                    "onTrimMemory($level): reclaiming shells (foreground pressure, activeWork=$hasActiveWork)" +
                        (if (hasActiveWork) " — skipping sync gc" else " + gc"),
                )
                ExecutionCoordinator.recycleIdleShells()
                if (!hasActiveWork) {
                    System.gc()
                }
            } else if (TrimPolicy.isBackground(level)) {
                Log.i("MinisApp", "onTrimMemory($level): background/UI-hidden — skip aggressive reclaim (keep view state)")
            }
            if (TrimPolicy.shouldEngageMemoryGate(level)) {
                // Flip the gate: new session admissions must wait for
                // RSS to drop below the critical watermark before acquiring.
                Log.w("MinisApp", "onTrimMemory($level): CRITICAL — memory gate engaged")
                // [fix/voice-crash-observability tail] Give lmkd a "self-rescue"
                // window: drop the markdown parse caches (inline/math/blocks Lru
                // caches hold large AnnotatedString backing arrays) and force a
                // second GC pass. The next render re-parses as cache misses —
                // acceptable when the alternative is being OOM-killed mid-voice-
                // dictation.
                runCatching { clearMarkdownParseCachesForMemoryPressure() }
                System.gc()
            }
        }.onFailure {
            Log.w("MinisApp", "onTrimMemory($level) handler failed: ${it.message}")
        }
    }

}
