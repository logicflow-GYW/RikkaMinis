package com.rikkaminis.app.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.rikkaminis.app.data.FileMentionIndex
import com.rikkaminis.app.data.MountedFoldersStore
import com.rikkaminis.app.data.resolveVolumeRoot
import java.io.File
import java.io.IOException
import java.util.TimeZone
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PRoot configuration holder and command builder.
 * Corresponds to iOS ISHKernel — but since PRoot is process-per-command
 * (not a persistent kernel), this object holds configuration state
 * and builds proot command lines.
 */
object PRootKernel {

    private const val TAG = "PRootKernel"

    /**
     * True once [boot] has completed its critical section.
     *
     * [deep-audit-1016 FIX-C 3a] Backed by [bootGate] rather than a plain
     * non-volatile `var`: the visibility flag and the mutual exclusion must be
     * the same fact, or a concurrent entrant can observe "booted" while the
     * install is still running.
     */
    val isBooted: Boolean
        get() = bootGate.isDone

    /**
     * Serializes [boot]'s critical section. Two callers race on cold start —
     * `ExecutionCoordinator`'s auto-boot and `TerminalSession`'s
     * open-a-terminal — and the body *deletes and re-extracts the rootfs*
     * (`RootfsManager.installIfNeeded`), so the second entrant used to destroy
     * the tree the first was unpacking.
     */
    private val bootGate = BootOnceGate()

    /** Path to native library directory (for LD_LIBRARY_PATH). */
    var nativeLibDir: String = ""
        private set

    /** Path to PRoot loader binary (64-bit). */
    var prootLoaderPath: String = ""
        private set

    /** Path to PRoot loader binary (32-bit). */
    var prootLoader32Path: String = ""
        private set

    private lateinit var rootfsManager: RootfsManager

    /** Application context captured at boot — used by session-scoped path
     *  fallbacks in [resolveHostPath] that need filesDir without a caller-
     *  supplied Context. */
    private var bootContext: Context? = null

    /**
     * Custom environment variables injected into every proot command.
     *
     * [deep-audit-1016 FIX-C 3b] ConcurrentHashMap, not mutableMapOf(): writers
     * are [boot] (a dozen putIfAbsent/put for PATH/LANG/TZ/PROOT_*),
     * [updateTimezone] and [updateProxy] — the last two run from
     * `MinisApp`'s timezone / proxy broadcasts, i.e. at arbitrary times while a
     * shell is starting. Readers iterate it while building the child envp
     * (`PersistentShell`, `ShellExecutor`, `TerminalSession`). A plain
     * LinkedHashMap threw ConcurrentModificationException out of whichever
     * shell_execute happened to be starting — the exact same race
     * [bindMounts] was fixed for in [audit-0917], in the sibling declaration
     * below: that fix changed one map and left this one. Env-var iteration
     * order carries no meaning (the kernel takes an unordered envp block), so
     * the weakly-consistent iterator is a strict improvement.
     *
     * Declared type stays `MutableMap<String, String>`: the three iteration
     * sites keep compiling unchanged, and `putIfAbsent` keeps its atomic
     * JDK-8 ConcurrentMap meaning (Kotlin's `MutableMap.putIfAbsent` delegates
     * to `java.util.Map.putIfAbsent` when the receiver implements it).
     */
    val customEnvironment: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Bind mounts: Linux path -> host filesystem path.
     *
     * [audit-0917] ConcurrentHashMap, not linkedMapOf(): writers
     * (addBindMount / registerGlobalBindMounts / applyMountedFoldersSnapshot,
     * driven by launch + mount CRUD) race readers that iterate the map while
     * building a proot command line (buildProotCommand / buildTermuxArgs).
     * A plain LinkedHashMap threw ConcurrentModificationException out of
     * whichever shell_execute happened to be starting. Iteration order is
     * irrelevant — `proot -b` is order-independent. The reconcile sequence in
     * [applyMountedFoldersSnapshot] is still not atomic, but it is idempotent
     * and re-run on every mount change, so a torn snapshot converges.
     */
    val bindMounts: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Initialize the PRoot environment: install rootfs and proot binary.
     */
    suspend fun boot(context: Context) {
        if (isBooted) {
            Log.d(TAG, "Already booted")
            return
        }
        // [deep-audit-1016 FIX-C 3a] The fast path above stays lock-free; the
        // gate re-checks under the lock and runs the body at most once. A loser
        // waits here and then returns without touching the rootfs.
        val performed = bootGate.run { bootCriticalSection(context) }
        if (!performed) Log.d(TAG, "Already booted (concurrent entrant)")
    }

    /**
     * [boot]'s one-shot body — rootfs install/repair, env, bind mounts, offload
     * server. Split out so [boot] is only "fast path + gate"; never call this
     * directly (it is the thing [bootGate] exists to serialize).
     */
    private suspend fun bootCriticalSection(context: Context) {
        bootContext = context.applicationContext
        rootfsManager = RootfsManager.getInstance(context)
        // [meta1028 D3-F02] Wire the rebuild notification FIRST: a
        // `RootfsManager.reset()` (manual reset, or autoRepair Stage 3) wipes
        // and re-extracts the very tree the rest of this section materializes
        // into, and `bootGate` has no reset edge of its own — so before this
        // hook existed, `isBooted` stayed true after a mid-session reset and
        // nothing ever re-ran installHandlerStubs / applyDefaultMountOverlay /
        // applyMountedFoldersSnapshot: every offload stub answered `127: not
        // found` and read-only mount guards were gone until the app restarted.
        // The hook only re-arms the gate; the next `boot()` (command, terminal)
        // re-runs this whole body, which is idempotent by design.
        rootfsManager.afterRootfsReinstalled = {
            onRootfsReinstalled(context)
        }
        rootfsManager.installIfNeeded()
        rootfsManager.installProotIfNeeded()

        // [T-rootfs-integrity] Verify critical files survived extraction.
        // A silent_kill (HyperOS memory pressure) can leave the rootfs with
        // a valid `.arch` marker but missing bash / readline / ncurses —
        // the terminal dies with `'/bin/bash' not found` while the app still
        // reports "installed". Repair in place when possible, falling back to
        // ash in the terminal session if repair fails.
        val health = rootfsManager.verifyIntegrity()
        if (!health.healthy) {
            Log.w(TAG, "Rootfs integrity check failed: missing=${health.missing}")
            val repaired = rootfsManager.autoRepair()
            if (repaired) {
                Log.i(TAG, "Rootfs auto-repair succeeded")
            } else {
                Log.e(TAG, "Rootfs auto-repair failed — terminal will fall back to /bin/sh")
            }
        }

        // Overlay assets/default_mount/ onto the rootfs on every boot so
        rootfsManager.applyDefaultMountOverlay()

        // Re-apply user mirror selections — the overlay above ships stock
        // config files (pip.conf, .npmrc, repositories) and would otherwise
        // revert user-chosen mirrors on every boot. Mirrors iOS ordering in
        // ISHTerminalView.startShell().
        com.rikkaminis.app.ui.sandbox.MirrorSpeedTestViewModel.applyAllActiveMirrors(context)
        com.rikkaminis.app.ui.sandbox.MirrorSpeedTestViewModel.autoDetectOnceIfNeeded(context)

        // Refresh DNS from system (mirrors iOS ISHKernel.configureDns)
        rootfsManager.refreshDns()

        // [Refactor-apk-world] Retry packages that failed to restore after a
        // rebuild. Runs AFTER mirror re-application, so the retry uses the
        // user's chosen repositories instead of the factory defaults that
        // may have caused the original failure.
        rootfsManager.retryFailedApkWorld()

        // [Refactor-apk-world] Snapshot user-installed packages to the host
        // side LAST — the rootfs is only in its final state after
        // auto-repair AND the restore-retry above. Dumping earlier could
        // overwrite a good snapshot with a pre-retry (partially restored)
        // state, losing user packages on the next rebuild. A later full
        // rebuild (manual reset or Stage-3 reinstall) restores from it.
        rootfsManager.dumpApkWorld()

        // LD_LIBRARY_PATH for the extracted native libs. talloc used to be
        // staged here under a versioned name; deps/build_proot.sh now links it
        // statically, so only the native lib dir is needed.
        nativeLibDir = rootfsManager.nativeLibDir.absolutePath

        // PROOT_LOADER / PROOT_LOADER_32 overrides. The loader is bundled into
        // the proot binary (extracted via /proc/self/fd at runtime), so these
        // files normally do not exist — kept only to honour a side-loaded
        // loader if one is present.
        val loaderPath = File(rootfsManager.nativeLibDir, "libproot-loader.so")
        val loader32Path = File(rootfsManager.nativeLibDir, "libproot-loader32.so")
        if (loaderPath.exists()) prootLoaderPath = loaderPath.absolutePath
        if (loader32Path.exists()) prootLoader32Path = loader32Path.absolutePath

        // Set default PATH for Alpine Linux
        customEnvironment.putIfAbsent("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin")
        customEnvironment.putIfAbsent("HOME", "/root")

        // URL interception: seed $BROWSER directly into every process envp
        // so non-login shells (which never source /etc/profile.d/minis.sh)
        // still route webbrowser.open()/etc into the host OpenOffloadHandler.
        // Mirrors iOS ISHShellExecutor.m:333.
        customEnvironment["BROWSER"] = "/usr/local/bin/minis-open"   // T195: force override; user dotfile BROWSER= would otherwise win

        // ash-specific: ENV points at a file the shell sources on startup.
        // Our /etc/profile sources /etc/profile.d/*.sh, so non-login shells
        // (PersistentShell uses plain `/bin/sh`) still pick up HISTFILE,
        // aliases, and the rest of the shipped profile. Mirrors iOS
        // ISHShellExecutor.m:326.
        customEnvironment.putIfAbsent("ENV", "/etc/profile")

        // Character-set declaration for tools that probe $CHARSET instead of
        // $LANG (e.g. some ncurses builds, older Python scripts). iOS sets
        // this in ISHShellExecutor.m:325.
        customEnvironment.putIfAbsent("CHARSET", "UTF-8")

        // Tool-compat env — match iOS ISHShellExecutor.m:358-360 so scripts
        // that run identically in both sandboxes produce identical output.
        // NO_COLOR: suppress ANSI color in shell tools that honor the
        //   informal NO_COLOR standard (curl, ls --color=auto, etc.).
        // PYTHONDONTWRITEBYTECODE: don't litter the rootfs with .pyc files
        //   next to user scripts.
        // GOMAXPROCS=2: keep Go runtimes modest on mobile; iOS caps at 2
        //   for the iSH emulator, same cap is a sensible default under
        //   PRoot on Android where the sandbox is also IO-bound.
        customEnvironment.putIfAbsent("NO_COLOR", "1")
        customEnvironment.putIfAbsent("PYTHONDONTWRITEBYTECODE", "1")
        customEnvironment.putIfAbsent("GOMAXPROCS", "2")

        // T222: PRoot's link2symlink extension creates .l2s.* sentinel files
        // alongside every hardlinked file. uv's default `hardlink` mode tries
        // to re-link these sentinels when populating site-packages from cache,
        // and PRoot rejects the second link with EPERM. Force uv to symlink
        // package files instead so the sentinels are never used as link
        // sources. Reported as openminis/openminis#7.
        // Mirrored in default_mount/etc/profile.d/minis.sh for login shells.
        customEnvironment.putIfAbsent("UV_LINK_MODE", "symlink")

        // Inject device timezone so Alpine userspace sees local time.
        // Mirrors iOS ISHShellExecutor.m:335-353 — uses POSIX TZ format with a
        // fixed name ("LCL") to avoid abbreviations like "GMT+8" which contain
        // +/- and confuse musl's TZ parser. POSIX sign is reversed from UTC
        // offset (UTC+8 → "LCL-8"), matching iOS exactly.
        customEnvironment["TZ"] = posixTz()

        // Propagate the Android system HTTP proxy into every sandboxed
        // process so curl/wget/pip/npm reuse the user's system or enterprise
        // proxy (and packet-capture tools like Charles / mitmproxy just work).
        // PROXY_CHANGE_ACTION is wired up in MinisApp so live shells pick up
        // proxy toggles without a restart.
        customEnvironment.putAll(systemProxyEnv(context))

        // Register global bind mounts so direct file I/O tools (file_read, file_edit)
        // can resolve /var/minis/{memory,skills,shared}/... (idempotent).
        registerGlobalBindMounts(context)

        // Start the native_offload server so the proot extension can reach it
        // over the abstract unix socket. Handlers must have been registered
        // via NativeOffloadServer.register() before this point.
        NativeOffloadServer.start(rootfsManager.rootfsDir)

        // Materialize stub binaries inside the rootfs for each handler so
        // /bin/sh's PATH search succeeds and triggers an execve the extension
        // can intercept. The stub's content is irrelevant — proot rewrites
        // the execve before it runs.
        installHandlerStubs(rootfsManager.rootfsDir)

        // T219-6: now that rootfs is on disk, materialize /var/minis/mounts/<name>
        // placeholder dirs that PRoot's `-b` needs as bind targets. The earlier
        // applyMountedFoldersSnapshot in MinisApp.onCreate ran before boot and
        // its mkdirs went nowhere; this re-run covers the mount entries that
        // were known at app launch.
        //
        // [dv20-A4-A06] Pin the IO hop at the call site: the snapshot does
        // blocking host I/O (SAF resolution readdir, rootfs mkdirs, wrapper-
        // script writes), and bootCriticalSection runs on whatever context its
        // caller used — it must not depend on that.
        withContext(Dispatchers.IO) { applyMountedFoldersSnapshot(context) }

        // [deep-audit-1016 FIX-C 3a] `bootGate` marks completion here — the
        // flag-before-body ordering (`isBooted = true` used to sit one line
        // earlier) would let a concurrent entrant skip the tail of this body.
        Log.i(TAG, "PRoot kernel booted " +
            "rootfs=${rootfsManager.rootfsDir.absolutePath} " +
            "nativeLibDir=$nativeLibDir " +
            "loader=$prootLoaderPath " +
            "bindMounts=${bindMounts.size} " +
            "offloadHandlers=${OffloadHandlerCatalog.allHandlerNames.sorted()}")
    }

    /**
     * [meta1028 D3-F02] Called by [RootfsManager.reset] after the wipe +
     * re-extract has landed (hook wired at the top of
     * [bootCriticalSection]).
     *
     * Only re-arms the one-shot gate. That is the whole fix: `isBooted` is the
     * single fact every entry point consults (`ExecutionCoordinator.execute`
     * auto-boot, `TerminalSession.start`, `ShellExecutor`), so invalidating it
     * makes the tree's dependents rebuild themselves through the ONE code path
     * that knows how ([bootCriticalSection]) instead of a second, drifting copy
     * of "what has to be re-applied after a wipe".
     *
     * Deliberately does NOT kill shells here. The two owners do that on their
     * own next entry, where they can also serialize against a running command:
     * [ExecutionCoordinator] drops shells built against the pre-wipe tree
     * before its auto-boot, and [TerminalSession.start] restarts a PTY that is
     * still pointing at it.
     *
     * Re-entrancy: when the wipe comes from inside [bootCriticalSection]
     * (autoRepair Stage 3), this runs while `bootGate`'s mutex is held by that
     * same body. `invalidate` is a plain volatile write with no lock, so there
     * is no self-deadlock — and the body's own tail (stubs, overlay, mounts)
     * re-materializes everything AFTER the reset, which is exactly why
     * re-arming here is safe as well as necessary.
     */
    private fun onRootfsReinstalled(@Suppress("UNUSED_PARAMETER") context: Context) {
        bootGate.invalidate()
        Log.w(
            TAG,
            "rootfs re-extracted underneath a booted kernel — boot gate re-armed; " +
                "the next boot re-applies stubs / mounts / default_mount overlay",
        )
    }

    fun addBindMount(linuxPath: String, hostPath: String) {
        bindMounts[linuxPath] = hostPath
    }

    /**
     * Register the global (session-independent) Minis bind mounts so direct
     * file I/O tools (file_read, file_edit) can resolve
     * `/var/minis/{memory,skills,shared}/...` without needing PRoot to be
     * booted or any shell to have started. Safe to call repeatedly.
     */
    fun registerGlobalBindMounts(context: Context) {
        val globalBase = File(context.filesDir, "minis-global")
        // [T-mcp-integration-android] mcp-servers is global (like memory/skills):
        // binding it here makes the in-PRoot minis-mcp-cli read/write the SAME
        // servers.json the Android Settings UI does (host: minis-global/mcp-servers).
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            bindMounts["/var/minis/$subdir"] = hostDir.absolutePath
        }
        // [T-logs-bind-android] Expose AppLogger's daily logs (files/logs/
        // minis-*.log) to direct file I/O tools (file_read/file_edit) via
        // /var/minis/logs, mirroring the shell bind in ExecutionCoordinator.
        val logsDir = File(context.filesDir, "logs").also { it.mkdirs() }
        bindMounts["/var/minis/logs"] = logsDir.absolutePath
    }

    fun removeBindMount(linuxPath: String) {
        bindMounts.remove(linuxPath)
    }

    fun clearBindMounts() {
        bindMounts.clear()
    }

    // ── User-mounted external folders (T219) ──────────────────────────────
    //
    // Linux prefix every external mount lives under inside the rootfs.
    // PRoot's `-b host:linux` flag is per-invocation, so the diff-sync
    // applied via [applyMountedFoldersSnapshot] only affects subsequent
    // shell_execute calls — live processes won't observe a CRUD mid-flight.
    private const val MOUNTS_LINUX_PREFIX = "/var/minis/mounts/"

    // Sentinel in the read-only write-guard wrapper scripts so we can recognize
    // and remove our own wrappers (vs a user/busybox binary of the same name).
    // internal: the test package pins the marker ([MountGuardScriptTest]).
    internal const val GUARD_MARKER = "minis-mount-readonly-guard"

    // Commands whose LAST positional argument is the write target; the earlier
    // ones are sources (cp/ln). Everything else in [installMountWriteGuards]
    // takes every positional argument as a target, except dd (writes via `of=`).
    // [audit3 F-07] `mv` moved HERE from "last arg": `mv <read-only>/f /tmp/`
    // deletes f from the mount, so a source IS a write target for mv — the B5a
    // "sources are reads" model only holds for cp/ln (a cp source is read, an
    // ln source is read).
    // internal: the test package pins the shapes ([MountGuardScriptTest]).
    internal val MOUNT_GUARD_LAST_ARG_CMDS = setOf("cp", "ln")

    // [meta1028 D3-F06] Commands that accept `-t DIR` / `--target-directory[=]DIR`
    // (in joined forms too: `-tDIR`, `-rtDIR`), i.e. the commands whose write
    // target can arrive as a FLAG VALUE instead of the last positional.
    // `mv` is in here WITHOUT being in [MOUNT_GUARD_LAST_ARG_CMDS]: with `-t`
    // its positionals are all sources (must still be checked — audit3 F-07), so
    // it takes the flag-value parse but keeps the check-every-positional rule.
    internal val MOUNT_GUARD_TARGET_DIR_CMDS = setOf("cp", "ln", "mv")

    // Full wrapper set installed by [installMountWriteGuards]. [audit3 F-07] the
    // list is ALSO hardcoded into every generated script as an unwritable target:
    // `rm /usr/local/bin/cp` issued through the `rm` wrapper used to be allowed —
    // each wrapper only knew its own name — which deleted a sibling and reopened
    // every bypass for that command.
    // internal: pinned by [MountGuardScriptTest] + read by install + codegen.
    internal val MOUNT_GUARD_CMDS =
        listOf("touch", "tee", "cp", "mv", "mkdir", "rm", "rmdir", "ln", "dd")

    // Guest path of the read-only prefix list the wrappers read at run time.
    // [audit3 F-07] hardcoded unwritable too — an absent list makes every check
    // pass, so deleting it was the cheapest full bypass.
    internal const val MOUNT_GUARD_CFG_LINUX_PATH = "/var/minis/.mount-readonly-prefixes"

    // Guest dir the wrappers live in (rootfs `usr/local/bin`, first on PATH).
    internal const val MOUNT_GUARD_BIN_LINUX_PREFIX = "/usr/local/bin"

    /**
     * Reference to the user-mounted folders store, set by [MinisApp] at
     * launch (the file is owned by T219-1 / worker A — we cannot touch it
     * from here, so we receive the instance via this setter instead of a
     * top-level singleton). Nullable: tools and the file-mention index
     * tolerate `null` and treat it as "no mounts".
     */
    @Volatile
    var mountedFoldersStore: MountedFoldersStore? = null

    /**
     * Reconcile [bindMounts] keys under `/var/minis/mounts/` with the
     * current snapshot of [mountedFoldersStore]. Removes stale entries,
     * adds new ones, updates host paths in place when the user re-points
     * a mount. Idempotent — call from boot + after every CRUD on the
     * store.
     *
     * Mounts whose tree URI doesn't decode to a POSIX path readable by
     * us (cloud-only providers, `Android/data/<otherPkg>` under strict
     * scoped storage, etc.) are skipped silently — surfacing an error
     * is the responsibility of the picker UI in T219-2.
     */
    fun applyMountedFoldersSnapshot(context: Context) {
        val store = mountedFoldersStore
        val desired: Map<String, String> = if (store == null) {
            emptyMap()
        } else {
            store.entries.value
                .mapNotNull { entry ->
                    val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
                    "$MOUNTS_LINUX_PREFIX${entry.name}" to host
                }
                .toMap()
        }

        // Remove stale /var/minis/mounts/* keys not in desired.
        val stale = bindMounts.keys
            .filter { it.startsWith(MOUNTS_LINUX_PREFIX) }
            .filter { it !in desired }
        for (key in stale) bindMounts.remove(key)

        // Add or update.
        for ((linuxPath, hostPath) in desired) {
            bindMounts[linuxPath] = hostPath
        }

        // T219-6: PRoot's `-b host:linux` requires the linux target to exist
        // inside the rootfs as a real directory; otherwise PRoot silently skips
        // the bind. Materialize a placeholder dir for each /var/minis/mounts/<name>
        // (and clean up stale ones) so the bind actually takes effect.
        materializeMountTargets(context, desired.keys)

        // T219-4: raw shell writes (touch/cp/mv/tee/… and `>` redirects) bypass
        // the FileWriteTool/FileEditTool read-only guard because proot `-b` has no
        // read-only modifier. Install shell wrappers that reject writes whose
        // target lands under an effectively-read-only mount, so the agent gets a
        // clear "read-only mounted folder" error in the shell instead of a write
        // that silently no-ops (Android 10 shadow FUSE) or a confusing raw EACCES.
        val readOnlyLinuxPrefixes = store?.entries?.value.orEmpty()
            .filter { it.name in desired.keys.map { k -> k.removePrefix(MOUNTS_LINUX_PREFIX) } }
            .filterNot { it.effectiveWritable }
            .map { "$MOUNTS_LINUX_PREFIX${it.name}" }
        installMountWriteGuards(context, readOnlyLinuxPrefixes)

        val entryCount = store?.entries?.value?.size ?: 0
        Log.i(
            TAG,
            "applyMountedFoldersSnapshot: entries=$entryCount active=${desired.size} removed=${stale.size} " +
                storageAccessDiag(context),
        )
        if (entryCount > desired.size) {
            Log.w(
                TAG,
                "applyMountedFoldersSnapshot: ${entryCount - desired.size} mount(s) dropped — host path " +
                    "unresolved or unreadable (see resolveTreeUriToHostPath warnings above)",
            )
        }
    }

    /**
     * Ensure each `/var/minis/mounts/<name>` has a real (empty) directory
     * inside the rootfs that PRoot can bind onto. Also removes stale
     * placeholder dirs whose mount entry was unmounted, so old names don't
     * keep appearing in `ls /var/minis/mounts/`.
     */
    private fun materializeMountTargets(context: Context, desiredLinuxPaths: Set<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            Log.w(TAG, "materializeMountTargets: rootfs not yet available: ${t.message}")
            return
        }
        val mountsRoot = File(rootfs, "var/minis/mounts").also { it.mkdirs() }

        // Create placeholder dirs for desired names.
        for (linuxPath in desiredLinuxPaths) {
            val name = linuxPath.removePrefix(MOUNTS_LINUX_PREFIX)
            File(mountsRoot, name).also { d ->
                if (!d.exists()) d.mkdirs()
            }
        }

        // Clean placeholder dirs that no longer correspond to a mount.
        val desiredNames = desiredLinuxPaths.map { it.removePrefix(MOUNTS_LINUX_PREFIX) }.toSet()
        mountsRoot.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name !in desiredNames) {
                f.deleteRecursively()
            }
        }
    }

    /**
     * True when [linuxPath] resolves under a `/var/minis/mounts/<name>`
     * mount whose effective writability is false. Used by [FileWriteTool]
     * and [FileEditTool] to short-circuit before touching disk; mirrors
     * iOS `MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount`.
     *
     * [audit-0919 F-214] T219-3's Android landing spot is THIS method, not the
     * former `sandbox/MountedFolderCoordinator.kt` — that file was a zero-caller
     * duplicate (its `requireWritable` / `bindMountSpecs` / `ReadOnlyMountException`
     * had no production reference anywhere) and has been deleted. Do not
     * reintroduce a coordinator object here: the read-only guard the write tools
     * actually call is this one, and [applyMountedFoldersSnapshot] is what feeds
     * `proot -b`.
     *
     * The shell can technically still write through a PRoot bind because
     * `-b` has no read-only modifier — that's why the mount-detail screen
     * uses honest "Locked / Unlocked" wording. Defense-in-depth via a
     * shell-level wrapper is tracked as T219-4 follow-up (optional).
     */
    fun isLinuxPathUnderReadOnlyMount(linuxPath: String): Boolean {
        // Normalize BEFORE matching the prefix (audit-1014 B5b): the raw string
        // could carry `.`/`//` segments (`/var/minis/./mounts/...`), which
        // slipped past the guard while resolveHostPath's safeResolveWithin
        // still resolved the write INTO the read-only mount.
        val normalized = normalizeLinuxPath(linuxPath)
        if (normalized == null || !normalized.startsWith(MOUNTS_LINUX_PREFIX)) return false
        val store = mountedFoldersStore ?: return false
        val rest = normalized.removePrefix(MOUNTS_LINUX_PREFIX)
        val name = rest.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.effectiveWritable
    }

    /**
     * Snapshot of mount roots for the @-mention index. Skips entries
     * whose tree URI doesn't resolve to a POSIX path on this device.
     */
    fun mountEntriesForIndex(context: Context): List<FileMentionIndex.MountEntry> {
        val store = mountedFoldersStore ?: return emptyList()
        return store.entries.value.mapNotNull { entry ->
            val host = resolveTreeUriToHostPath(entry.treeUri, context) ?: return@mapNotNull null
            FileMentionIndex.MountEntry(name = entry.name, root = File(host))
        }
    }

    /**
     * Decode a SAF tree URI (the `content://com.android.externalstorage.documents/tree/<volume>:<rel>`
     * form returned by `ACTION_OPEN_DOCUMENT_TREE`) into an absolute POSIX
     * path on the host filesystem so PRoot's `-b` flag can bind it. This
     * is "Option A" from the T219 spec: the dominant Obsidian-vault /
     * Downloads / DCIM use cases all live under `/storage/emulated/0` or
     * a removable volume's mount directory and are openable directly by
     * our app uid.
     *
     * Returns null when:
     *   - the URI is from a non-`externalstorage` provider (Drive,
     *     Dropbox, OneDrive — those have no POSIX path),
     *   - the volume id can't be resolved (rare — likely a removed
     *     SD card),
     *   - the resolved path doesn't exist or isn't readable by us
     *     (e.g. another app's `Android/data/<pkg>` under strict scoped
     *     storage).
     *
     * Workers A's later data-layer hoist landed as the `resolvedHostPath`
     * cache field on `MountedFoldersStore.Entry`; this method still
     * resolves on demand for entries whose cache is cold.
     */
    private fun resolveTreeUriToHostPath(treeUriString: String, context: Context): String? = try {
        val treeUri = Uri.parse(treeUriString)
        if (treeUri.authority != "com.android.externalstorage.documents") {
            null
        } else {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(':', limit = 2)
            val volumeId = parts.getOrNull(0).orEmpty()
            val relPath = parts.getOrNull(1).orEmpty()
            // [dv20-A4-A06] Single-source volume-root resolution (MountedFoldersStore.kt):
            // the union rule keeps the < API 30 `/storage/<uuid>` fallback, so a
            // removable volume resolves here too instead of dropping the mount.
            val volumeRoot = resolveVolumeRoot(context, volumeId)
            if (volumeRoot == null) {
                Log.w(TAG, "resolveTreeUriToHostPath: unknown volume '$volumeId' for $treeUriString")
                null
            } else {
                val candidate = if (relPath.isEmpty()) File(volumeRoot) else File(volumeRoot, relPath)
                // Diagnose the common "mounted folder reads empty" failure: the
                // resolved host dir exists but the app process (raw POSIX, via
                // proot) can't readdir it because the ROM only grants the
                // /mnt/runtime/default FUSE view. list() returning null == EACCES.
                if (candidate.exists() && candidate.canRead()) {
                    val childCount = candidate.list()?.size
                    if (childCount == null) {
                        Log.w(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} canRead=true " +
                                "but list() returned null (readdir EACCES / scoped-storage filtered) — " +
                                "mount will appear empty. ${storageAccessDiag(context)}",
                        )
                    } else {
                        Log.i(
                            TAG,
                            "resolveTreeUriToHostPath: host=${candidate.absolutePath} readable childCount=$childCount",
                        )
                    }
                    candidate.absolutePath
                } else {
                    Log.w(
                        TAG,
                        "resolveTreeUriToHostPath: host=${candidate.absolutePath} " +
                            "exists=${candidate.exists()} canRead=${candidate.canRead()} — bind skipped",
                    )
                    null
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "resolveTreeUriToHostPath failed for $treeUriString: ${t.message}")
        null
    }

    /**
     * One-line summary of the app's effective external-storage access, for the
     * mount log trail. Explains *why* a resolved host dir might readdir empty:
     * on Android 11+ it's MANAGE_EXTERNAL_STORAGE; on Android 10 base ROMs
     * (HarmonyOS/EMUI) it's whether legacy READ_EXTERNAL_STORAGE was granted.
     */
    private fun storageAccessDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return when {
            sdk >= Build.VERSION_CODES.R ->
                "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
            else -> {
                val granted = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                "sdk=$sdk legacyStorageGranted=$granted legacyExtStorageOptIn=${Environment.isExternalStorageLegacy()}"
            }
        }
    }

    // [dv20-A4-A06] The private `resolveVolumeRoot(volumeId, context)` that used
    // to live here is gone: it was a drifted twin of MountedFoldersStore's copy
    // with no < API 30 fallback, so a removable volume (API 26–29) resolved to
    // null on this side and applyMountedFoldersSnapshot silently dropped the
    // mount. Both readers now call the single top-level
    // `resolveVolumeRoot(context, volumeId)` in MountedFoldersStore.kt.

    /**
     * Build a POSIX TZ string from the current system timezone.
     *
     * Matches iOS ISHShellExecutor.m:335-353 byte-for-byte:
     *   UTC+8:00 → "LCL-8"
     *   UTC+5:30 → "LCL-5:30"
     *   UTC-3:00 → "LCL+3"
     *
     * POSIX TZ sign is inverted relative to UTC offset, and we use the fixed
     * name "LCL" to avoid musl's confused parsing of abbreviations like
     * "GMT+8" (which contain an embedded sign).
     */
    fun posixTz(): String {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val secs = offsetMs / 1000L
        val hrs = (secs / 3600).toInt()
        val mins = ((abs(secs) % 3600) / 60).toInt()
        // POSIX sign is reversed: UTC+8 → "LCL-8"
        val posixHrs = -hrs
        val sign = if (posixHrs >= 0) "+" else "-"
        return if (mins != 0) {
            "LCL$sign${abs(posixHrs)}:${"%02d".format(mins)}"
        } else {
            "LCL$sign${abs(posixHrs)}"
        }
    }

    /**
     * Recompute TZ and update customEnvironment. Returns the new TZ string.
     * Callers should also propagate the new value to live shells via
     * [ExecutionCoordinator.broadcastTimezoneChange].
     */
    fun updateTimezone(): String {
        val tz = posixTz()
        customEnvironment["TZ"] = tz
        Log.i(TAG, "Updated TZ=$tz")
        return tz
    }

    /**
     * Env-variable keys for HTTP proxy injection. All six are always set as a
     * block — even when no system proxy is configured the values are empty
     * strings, which (a) signals "no proxy" to curl/wget/pip/npm (they treat
     * empty as unset) and (b) lets a proxy-disabled transition overwrite any
     * previously exported value in a live shell without a separate `unset`.
     */
    private val PROXY_KEYS = listOf(
        "http_proxy", "https_proxy",
        "HTTP_PROXY", "HTTPS_PROXY",
        "no_proxy", "NO_PROXY",
    )

    /** Standard bypass list for localhost traffic — matches the `curl` convention. */
    private const val PROXY_NO_PROXY = "localhost,127.0.0.1,::1"

    /**
     * Read the system HTTP proxy configuration and produce the env-var block
     * to inject into every sandboxed process. Returns all six keys even when
     * no proxy is set (values are empty strings) so callers can unconditionally
     * `export` the block and get the right behavior on both transitions:
     *
     *   proxy set → values carry http://host:port
     *   proxy unset → values are empty, tools fall back to direct connection
     */
    fun systemProxyEnv(context: Context): Map<String, String> {
        val proxyUri = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val info = cm?.defaultProxy
            if (info != null && info.host.isNotBlank() && info.port > 0) {
                "http://${info.host}:${info.port}"
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read system proxy: ${t.message}")
            ""
        }

        val out = linkedMapOf<String, String>()
        for (key in PROXY_KEYS) {
            out[key] = when (key) {
                "no_proxy", "NO_PROXY" -> if (proxyUri.isNotEmpty()) PROXY_NO_PROXY else ""
                else -> proxyUri
            }
        }
        return out
    }

    /**
     * Recompute proxy env vars and update customEnvironment. Returns the map
     * that was written so callers can propagate to live shells via
     * [ExecutionCoordinator.broadcastProxyChange].
     */
    fun updateProxy(context: Context): Map<String, String> {
        val env = systemProxyEnv(context)
        customEnvironment.putAll(env)
        val hasProxy = env["http_proxy"].orEmpty().isNotEmpty()
        Log.i(TAG, "Updated proxy — ${if (hasProxy) "active=${env["http_proxy"]}" else "cleared"}")
        return env
    }

    /**
     * Build the full proot command line for a given shell command.
     *
     * Produces:
     * ```
     * <proot> -0 --link2symlink -r <rootfs> -b /dev -b /proc -b /sys -w /root
     *         [-b <host>:<linux> ...for each bind mount]
     *         /bin/sh -c "<command>"
     * ```
     */
    fun buildProotCommand(shellCommand: String): List<String> {
        check(isBooted) { "PRootKernel.boot() must be called before building commands" }

        val cmd = mutableListOf<String>()

        cmd.add(rootfsManager.prootBinary.absolutePath)

        // Run as fake root (uid 0)
        cmd.add("-0")

        // T141: Translate hardlink() syscalls to symlink(). Android's /data
        // (ext4/F2FS as mounted by zygote) refuses cross-directory hardlinks
        // for app uids, so PRoot would otherwise pass link() straight through
        // to the host kernel and the host kernel rejects with EPERM. Alpine's
        // apk installs busybox-applet packages (binutils, gcc deps, etc.) by
        // hardlinking ar/ld/nm/strip → busybox; without this flag every such
        // install fails with "Permission denied". Symlinks are functionally
        // equivalent for the apk consumer.
        cmd.add("--link2symlink")

        // [P2-proot-resource-hygiene] Ask PRoot to exit once its child
        // (/bin/sh) terminates. When a command shell is destroyForcibly'd
        // (timeout, user cancel, oversize recycle) this makes the PRoot
        // tracer itself exit instead of lingering with leaked native memory
        // held via the PTY/pipe master fd (measured 6.2-6.9GB → device OOM →
        // SIGABRT). RikkaHub uses the same flag for per-command proot runs so
        // the tracer never outlives the command. Long-lived interactive shells
        // are unaffected — the /bin/sh stays alive the whole session.
        cmd.add("--kill-on-exit")

        // Set rootfs
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)

        // Bind essential pseudo-filesystems
        cmd.add("-b")
        cmd.add("/dev")
        cmd.add("-b")
        cmd.add("/proc")
        cmd.add("-b")
        cmd.add("/sys")

        // Working directory
        cmd.add("-w")
        cmd.add("/root")

        // User bind mounts.
        // [audit-0917] Iterate a snapshot: addBindMount / applyMountedFoldersSnapshot
        // (launch + mount CRUD) mutate this LinkedHashMap from other threads, and a
        // concurrent put during iteration would throw ConcurrentModificationException
        // out of buildProotCommand, killing an unrelated shell_execute.
        for ((linuxPath, hostPath) in bindMounts.entries.toList()) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
            // Log external-folder binds specifically — these are the ones that
            // read empty when the host view is filtered, and the proot child's
            // own EACCES on readdir is otherwise invisible in the log file.
            if (linuxPath.startsWith(MOUNTS_LINUX_PREFIX)) {
                Log.i(TAG, "proot bind mount: $hostPath -> $linuxPath")
            }
        }

        // Native offload: route cataloged handler names to the host-side
        // NativeOffloadServer over the abstract unix socket. The main process
        // only needs the STATIC name list (OffloadHandlerCatalog) — it does
        // not own handler instances, which live in :toolservice (Phase 1).
        val handlers = OffloadHandlerCatalog.allHandlerNames
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        // Shell command
        cmd.add("/bin/sh")
        cmd.add("-c")
        cmd.add(shellCommand)

        Log.d(TAG, "proot cmd: ${cmd.take(cmd.size - 1).joinToString(" ")} <shellCommand ${shellCommand.length} bytes>")
        return cmd
    }

    /**
     * Subdirs that live under `minis-sessions/<sessionId>/` rather than the
     * global pool. Shared with [GuestPathMapper], which rebuilds the proot
     * `-b` table for offload handlers that touch the real filesystem — one
     * source of truth, so the two can never disagree about which subdirs are
     * session-scoped.
     */
    private val perSessionSubdirs: Set<String> = GuestPathMapper.PER_SESSION_SUBDIRS.toSet()

    /**
     * Join [tail] under [base] after normalizing dot-segments, and return null
     * if the result would land outside [base] (either by `..` traversal or by
     * a symlink pointing out of the tree). Guards every `File(base, userPath)`
     * style join against escaping a session/mount/rootfs boundary.
     *
     * Dot-segment normalization: split [tail] on '/', drop empty/`.` segments,
     * pop the stack on `..`; a `..` that would pop an already-empty stack means
     * the path climbs above [base] and is rejected.
     */
    internal fun safeResolveWithin(base: File, tail: String): File? {
        if (tail.isEmpty()) return base
        val segments = normalizeDotSegments(tail) ?: return null
        if (segments.isEmpty()) return base

        var resolved = base
        for (segment in segments) resolved = File(resolved, segment)

        val basePath = try { base.canonicalPath } catch (e: IOException) { return null }
        val resolvedPath = try { resolved.canonicalPath } catch (e: IOException) { return null }
        if (resolvedPath == basePath) return resolved
        if (!resolvedPath.startsWith("$basePath${File.separator}")) return null
        return resolved
    }

    /**
     * Resolve a `/var/minis/...` Linux path directly against a specific session's
     * host directory, bypassing the global [bindMounts] map. Use this when the
     * caller knows the owning session (chat link resolver, file preview, etc.) —
     * the global map is overwritten every time another session boots its shell,
     * so its answer is last-writer-wins rather than "this session's view".
     *
     * Falls back to [resolveHostPath] for paths outside `/var/minis/` or for the
     * shared subdirs (memory/skills/shared) which don't depend on sessionId.
     *
     * [deep-audit-1016 FIX-C 2a] The decision lives in the pure
     * [resolveSessionGuestHostPath], which normalizes `linuxPath` BEFORE any
     * prefix match. The raw-string form this used to be (and the read-only
     * guard's own [normalizeLinuxPath] call) disagreed on malformed input:
     * `/var/minis/./workspace/x` extracted `subdir == "."`, matched no
     * per-session scope and no bind mount, and fell through to the rootfs
     * join — i.e. a *ghost* file at `<rootfs>/var/minis/workspace/x`, shared
     * between sessions and invisible to the session's own workspace.
     */
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? =
        resolveSessionGuestHostPath(
            sessionId = sessionId,
            linuxPath = linuxPath,
            filesDir = context.filesDir,
            perSessionSubdirs = perSessionSubdirs,
            resolveGlobal = { resolveHostPath(it) },
        )

    /**
     * Resolve a Linux path, honouring the owning session whenever one is known.
     *
     * [audit3 F-08] This is the single replacement for the hand-spelled
     * `sessionId?.let { resolveSessionHostPath(it, p, ctx) } ?: resolveHostPath(p)`
     * shape that callers used to repeat at each site (browser URL interception,
     * file_upload, cookies-file, screenshot dir, config `set --file`, speech
     * source, read_image, model-use). That shape conflated the two unrelated
     * nulls [resolveSessionHostPath] can return:
     *
     *  - "no session scoping applies" (path outside `/var/minis/`, or a shared
     *    subdir) — the global resolver is genuinely the right answer; and
     *  - "[safeResolveWithin] rejected a `..`/symlink escape out of the session
     *    tree" — the elvis then re-resolved the *rejected* path through
     *    [resolveHostPath], which searches every `minis-sessions/<id>/` tree
     *    and finally the rootfs placeholder. The containment check was defeated
     *    by its own caller: a path refused for this session still resolved,
     *    possibly to another session's file.
     *
     * Contract:
     *  - `sessionId == null` → [resolveHostPath]. "No session in scope" is the
     *    documented case for Coil / generic media callers — unchanged.
     *  - `sessionId != null` → [resolveSessionHostPath] verbatim, including its
     *    internal non-session fallbacks. A null from it means "rejected or
     *    absent" and is returned as null; callers keep their existing
     *    file-not-found / warning handling. Never re-resolve globally.
     */
    fun resolvePathForSession(sessionId: String?, linuxPath: String, context: Context): File? =
        sessionScopedResolve(
            sessionId = sessionId,
            linuxPath = linuxPath,
            resolveGlobal = { resolveHostPath(it) },
            resolveSession = { sid, path -> resolveSessionHostPath(sid, path, context) },
        )

    /**
     * Pure decision core of [resolvePathForSession]: no [Context], no
     * filesystem, no [PRootKernel] state — so the JVM test source set can pin
     * the three arms (no session → global; session hit → session; session
     * *reject* → null, never the global resolver). Generic over the result type
     * so tests can feed plain strings instead of [File]s.
     *
     * Keep the "session present ⇒ no global fallback" branch exactly as is: the
     * `?:`/elvis form compiles, looks equivalent, and silently reopens the
     * cross-session hole [resolvePathForSession] documents.
     */
    internal fun <T> sessionScopedResolve(
        sessionId: String?,
        linuxPath: String,
        resolveGlobal: (String) -> T?,
        resolveSession: (String, String) -> T?,
    ): T? = if (sessionId == null) {
        resolveGlobal(linuxPath)
    } else {
        resolveSession(sessionId, linuxPath)
    }

    /**
     * Resolve a Linux path to a host filesystem File by checking bind mounts.
     * Returns null if no matching mount is found.
     *
     * Per-session subdirs (attachments/workspace/offloads/browser) are NOT in
     * the global bindMounts map — buildSessionBindMounts keeps them
     * session-local, and the terminal overlays its own. Callers that know the
     * owning session should use [resolveSessionHostPath]. For callers without
     * a session context (Coil image fetcher, generic media resolvers), fall
     * back to searching every `minis-sessions/<id>/<subdir>` tree for a file
     * that actually exists — the same fallback resolveMdMediaFile uses — so a
     * `minis://attachments/x.png` URL still resolves to a real file instead of
     * the empty rootfs placeholder.
     *
     * [deep-audit-1016 FIX-C 2a] Delegates to the pure [resolveGuestHostPath],
     * which normalizes the guest path before every prefix match (bind mounts
     * and the per-session subdir scan alike) — see
     * [resolveSessionGuestHostPath] for the malformed-input symptom.
     */
    fun resolveHostPath(linuxPath: String): File? = resolveGuestHostPath(
        linuxPath = linuxPath,
        bindMounts = bindMounts,
        perSessionSubdirs = perSessionSubdirs,
        sessionsRoot = bootContext?.filesDir?.let { File(it, "minis-sessions") },
        rootfsDir = if (::rootfsManager.isInitialized) rootfsManager.rootfsDir else null,
    )

    /**
     * Create a stub executable inside the rootfs for every registered
     * native_offload handler. The shell needs to find *something* on PATH
     * to trigger an execve — proot's native_offload extension intercepts
     * that execve before the stub actually runs.
     */
    private fun installHandlerStubs(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin").also { it.mkdirs() }
        var created = 0
        var existed = 0
        for (name in OffloadHandlerCatalog.allHandlerNames) {
            val stub = File(binDir, name)
            if (stub.exists()) { existed++; continue }
            stub.writeText("#!/bin/sh\nexit 0\n")
            stub.setExecutable(true, false)
            created++
        }
        Log.d(TAG, "installHandlerStubs dir=${binDir.absolutePath} created=$created existed=$existed")

        installShellWrappers(binDir)
    }

    /**
     * Install /usr/local/bin/ wrapper scripts that work around Android-specific
     * sandbox limitations. These take precedence over /bin/busybox in PATH.
     *
     * top: Android's hidepid=2 + SELinux blocks reads of /proc/<other_pid>/stat
     * for non-system UIDs, so plain `top` fails with "can't open 'stat': Permission
     * denied". Restrict it transparently to the PIDs we can actually read.
     *
     * Always rewritten so improvements ship with each app update.
     */
    private fun installShellWrappers(binDir: File) {
        // busybox `top` walks all of /proc and aborts on the first unreadable
        // /proc/<pid>/stat (Android sandbox blocks reads of other UIDs' procfs).
        // busybox in this rootfs doesn't accept `-p PIDS` either. Workaround:
        // simulate `top` with `ps` in a refresh loop, scoped to our session
        // (only PIDs whose /proc/<pid>/stat is actually readable by us).
        val topWrapper = File(binDir, "top")
        topWrapper.writeText(
            """#!/bin/sh
            |# Auto-installed by MinisApp PRootKernel.
            |# busybox top walks all of /proc and aborts on the first unreadable
            |# entry under Android's procfs hidepid restrictions, and this build
            |# of busybox doesn't accept `-p`. Emulate `top` with `ps` over the
            |# PIDs we can actually inspect.
            |
            |# Parse a few common top-style flags so existing muscle memory works.
            |delay=2
            |iters=-1
            |batch=0
            |while [ ${'$'}# -gt 0 ]; do
            |    case "${'$'}1" in
            |        -d) delay=${'$'}2; shift 2;;
            |        -n) iters=${'$'}2; shift 2;;
            |        -b) batch=1; shift;;
            |        -h|--help)
            |            echo "Usage: top [-b] [-n COUNT] [-d SECONDS]"
            |            exit 0;;
            |        *) shift;;
            |    esac
            |done
            |
            |saved_stty=""
            |on_exit() {
            |    [ ${'$'}batch -eq 0 ] && printf '\033[?25h'
            |    [ -n "${'$'}saved_stty" ] && stty "${'$'}saved_stty" 2>/dev/null
            |}
            |trap on_exit INT TERM EXIT
            |
            |list_readable_pids() {
            |    for d in /proc/[0-9]*; do
            |        [ -r "${'$'}d/stat" ] && printf '%s\n' "${'$'}{d##*/}"
            |    done
            |}
            |
            |render_once() {
            |    pids=${'$'}(list_readable_pids | tr '\n' ',' | sed 's/,${'$'}//')
            |    if [ -z "${'$'}pids" ]; then
            |        echo "top: no readable processes in /proc (Android sandbox)"
            |        return
            |    fi
            |    uptime_s=${'$'}(awk '{print int(${'$'}1)}' /proc/uptime 2>/dev/null)
            |    [ -z "${'$'}uptime_s" ] && uptime_s=0
            |    days=${'$'}((uptime_s / 86400))
            |    hours=${'$'}(((uptime_s % 86400) / 3600))
            |    mins=${'$'}(((uptime_s % 3600) / 60))
            |    visible=${'$'}(echo "${'$'}pids" | tr ',' '\n' | wc -l)
            |    printf 'top — up %dd %02d:%02d  visible processes: %d (own session only)\n' "${'$'}days" "${'$'}hours" "${'$'}mins" "${'$'}visible"
            |    printf '%s\n' '  PID USER     STAT  RSS  PPID COMMAND'
            |    /bin/busybox ps -o pid,user,stat,rss,ppid,comm 2>/dev/null \
            |        | awk -v pids=",${'$'}pids," 'NR==1 {next} {if (index(pids,","${'$'}1",")) print "  " ${'$'}0}'
            |}
            |
            |if [ ${'$'}batch -eq 1 ]; then
            |    count=0
            |    while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |        render_once
            |        count=${'$'}((count + 1))
            |        [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |        sleep "${'$'}delay"
            |    done
            |    exit 0
            |fi
            |
            |# Interactive mode: clear screen + redraw each interval. Exits on
            |# Ctrl+C OR when the user presses 'q' / 'Q'. We put the TTY into
            |# raw, no-echo mode so single keypresses are read without Enter.
            |if [ -t 0 ]; then
            |    saved_stty=${'$'}(stty -g 2>/dev/null)
            |    stty -echo -icanon min 0 time 0 2>/dev/null
            |fi
            |printf '\033[?25l'
            |count=0
            |while [ ${'$'}iters -lt 0 ] || [ ${'$'}count -lt ${'$'}iters ]; do
            |    printf '\033[H\033[2J'
            |    render_once
            |    count=${'$'}((count + 1))
            |    [ ${'$'}iters -ge 0 ] && [ ${'$'}count -ge ${'$'}iters ] && break
            |    # Sleep in small slices so 'q' is responsive within ~0.1s.
            |    end=${'$'}((${'$'}(date +%s) + delay))
            |    while [ ${'$'}(date +%s) -lt ${'$'}end ]; do
            |        if [ -t 0 ]; then
            |            ch=${'$'}(dd bs=1 count=1 2>/dev/null)
            |            case "${'$'}ch" in q|Q) exit 0;; esac
            |        fi
            |        sleep 0.1
            |    done
            |done
            |""".trimMargin()
        )
        topWrapper.setExecutable(true, false)
        Log.d(TAG, "installShellWrappers: /usr/local/bin/top installed")
    }

    /**
     * T219-4: install /usr/local/bin/ wrappers for the common file-writing
     * commands so a write whose target is under an effectively-read-only mount
     * fails loudly in the shell (mirrors how iOS `MountedFolderCoordinator`
     * throws `ReadOnlyMountError` before touching disk — the Android in-app
     * equivalent of that guard is [isLinuxPathUnderReadOnlyMount], see F-214),
     * instead of the write silently no-op'ing on ROMs that shadow blocked writes.
     *
     * The list of read-only linux prefixes is written to a config file the
     * wrappers source at run time, so re-mounting / toggling writability just
     * rewrites the config — no rootfs churn. When there are no read-only mounts
     * the wrappers are removed entirely so they add zero overhead in the common
     * case (every argument would pass the check anyway).
     *
     * Coverage note: this catches the argv-target commands agents actually use
     * (touch/cp/mv/tee/mkdir/rm/…). Pure shell `>` redirects are handled by the
     * OS itself — proot forwards the open(2) to the host FS, which returns EACCES
     * for a read-only mount — so those already fail truthfully.
     *
     * [audit-1014 B5a] the wrapper used to prefix-match EVERY positional
     * argument, so `cp /var/minis/mounts/vault/f /var/minis/workspace/` —
     * reading OUT of a read-only mount, perfectly legal — was refused; while
     * conversely `dd of=<read-only path>` wrote straight past the guard
     * (the `of=` value never matched the `"$prefix"` or `"$prefix"/…`
     * patterns). The argument
     * SELECTION is now command-specific ([mountGuardScript]); the guard
     * semantics are unchanged — any write target under a read-only prefix
     * still exits 1.
     */
    private fun installMountWriteGuards(context: Context, readOnlyLinuxPrefixes: List<String>) {
        val rootfs = try {
            RootfsManager.getInstance(context).rootfsDir
        } catch (t: Throwable) {
            return
        }
        val binDir = File(rootfs, MOUNT_GUARD_BIN_LINUX_PREFIX.trimStart('/')).also { it.mkdirs() }
        val guardedCmds = MOUNT_GUARD_CMDS
        val configFile = File(rootfs, MOUNT_GUARD_CFG_LINUX_PATH.trimStart('/'))

        if (readOnlyLinuxPrefixes.isEmpty()) {
            // No read-only mounts — remove the config + wrappers so plain busybox
            // commands run unimpeded.
            runCatching { configFile.delete() }
            for (name in guardedCmds) {
                val w = File(binDir, name)
                if (w.exists() && w.readText().contains(GUARD_MARKER)) w.delete()
            }
            Log.i(TAG, "installMountWriteGuards: no read-only mounts, guards cleared")
            return
        }

        configFile.parentFile?.mkdirs()
        configFile.writeText(readOnlyLinuxPrefixes.joinToString("\n", postfix = "\n"))

        for (name in guardedCmds) {
            val wrapper = File(binDir, name)
            wrapper.writeText(mountGuardScript(name))
            wrapper.setExecutable(true, false)
        }
        Log.i(
            TAG,
            "installMountWriteGuards: installed for ${guardedCmds.size} cmds, " +
                "readOnlyPrefixes=${readOnlyLinuxPrefixes.joinToString(",")}",
        )
    }

    /**
     * The shell wrapper for ONE guarded command. The argument selection is
     * command-specific (audit-1014 B5a) — three shapes by write semantics:
     *
     *  - cp/ln: only the LAST positional argument is the write target. The
     *    earlier ones are sources — `cp /var/minis/mounts/vault/f /tmp/`
     *    (reading OUT of a read-only mount) must stay legal. `[audit3 F-07]`
     *    exception: with `-t DIR` / `--target-directory[=]DIR` (clustered forms
     *    `-rt DIR` / `-rtDIR` too) the write target is the FLAG VALUE and every
     *    positional is a source, so the positional tail must NOT be checked.
     *    `[meta1028 D3-F06]` the flag-value parse is character-wise (see the
     *    selection block) and a `-t` with no value is REFUSED, not allowed;
     *  - mv: every positional (plus the `-t` value, which is positional too) —
     *    a mv SOURCE is destroyed, `mv <read-only>/f /tmp/` deletes f from the
     *    mount, so B5a's "sources are reads" model does not hold here.
     *    `[meta1028 D3-F06]` mv therefore gets the SAME flag-value parse as
     *    cp/ln while keeping the check-every-positional rule: before, the
     *    joined forms (`-tDIR`, `--target-directory=DIR`) began with `-` and
     *    were skipped, so only the source was ever inspected;
     *  - dd: the write target is the value of `of=`, never a positional
     *    argument — and `oflag=`/`ofs=` must not be confused with `of=`;
     *  - touch/tee/mkdir/rm/rmdir: every positional argument IS a target
     *    (tee's files are all written, rm's are all destroyed).
     *
     * Flags are skipped in all shapes. The guard semantics are unchanged
     * from the every-argument version: any selected target under a
     * read-only prefix still exits 1 — only WHICH arguments are checked got
     * command-specific.
     *
     * [audit3 F-07] every SELECTED target is canonicalized ([canon_target]
     * below) and matched against the guard's own control files before the
     * prefix list. The four bypasses that closes:
     *
     *  1. relative targets — `cd /var/minis/mounts/vault && rm secret.md` handed
     *     `$1="secret.md"` to a raw `"$prefix"/…` match and was allowed through;
     *  2. `.`/`..`/`//` spellings — `rm /var/minis/mounts/x/../../etc/f` (and
     *     the inverse `cp a /var/minis/mounts/x/../x/b`) never matched the raw
     *     prefix either, so the guard was blind in both directions;
     *  3. self-destruction — `rm /var/minis/.mount-readonly-prefixes` deleted
     *     the list, after which every later call allowed everything (the whole
     *     check sat behind `if [ -f "$cfg" ]`); deleting a sibling wrapper
     *     (`rm /usr/local/bin/cp` — each wrapper only knew its own name) had
     *     the same effect for that command;
     *  4. `cp -t <read-only dir> src` — only `src` was inspected;
     *  5. `mv <read-only>/f /tmp/` — B5a classified a mv source as a read, but a
     *     mv source is destroyed (see the shape list above), so moving a file
     *     OUT of a read-only mount deleted it from the mount unchecked.
     *
     * Deliberate boundary (do not "fix" it here): this stays best-effort
     * interception over the argv whitelist. Canonicalization is LEXICAL only —
     * a symlinked alias of a mount is not resolved, because busybox `realpath`
     * in this rootfs has no `-m` and `readlink -f` needs the target to exist,
     * so an external dependency would either fail open (missing `-m`) or refuse
     * every legal write (missing binary). `python3 -c "open(...)"`, `sed -i`,
     * `> redirect` and the in-app [isLinuxPathUnderReadOnlyMount] guard cover
     * those paths instead.
     */
    internal fun mountGuardScript(name: String): String {
        // [audit3 F-07] hardcoded unwritable targets (self-protection): the
        // prefix list + every wrapper of the set, this one included.
        val guardBinPatterns = MOUNT_GUARD_CMDS
            .joinToString(separator = "|") { "\"$MOUNT_GUARD_BIN_LINUX_PREFIX/$it\"" }
        val selection = when {
            name in MOUNT_GUARD_TARGET_DIR_CMDS -> {
                // [meta1028 D3-F06] Two bypasses lived in this block:
                //  1. `-[!-]*t?*) tdest="${arg##*[t]}"` used a GREEDY prefix
                //     strip: `##*[t]` removes everything up to the LAST `t` in
                //     the whole argument, not the option letter. Every mount
                //     path contains `t` twice over ("/var/minis/moun**t**s/…"
                //     plus most names), so `cp -rt/var/minis/mounts/vault f`
                //     produced tdest="" (or a fragment like "s/vault") — and
                //     the next line's `[ -z "$tdest" ] ||` SHORT-CIRCUITED on
                //     the empty value and allowed the write. The positional
                //     tail was skipped too, so nothing else caught it.
                //  2. `mv` had no flag-value parse at all (it is not a
                //     LAST_ARG cmd): `-tDIR` / `--target-directory=DIR` start
                //     with `-` and the old `-*) continue` skipped them, so
                //     only the source was checked.
                // The fix parses the short cluster CHARACTER BY CHARACTER
                // (`t` is the only value-taking option for these three), so
                // the value is whatever follows that `t` and nothing else, and
                // a missing value is REFUSED instead of allowed.
                val positionalRule =
                    if (name in MOUNT_GUARD_LAST_ARG_CMDS) "dest=\"${'$'}arg\""
                    else "check_prefix \"${'$'}arg\" || exit 1"
                """
                |dest=""
                |tdest=""
                |tdir=""
                |take=""
                |endopts=""
                |for arg in "${'$'}@"; do
                |    if [ "${'$'}take" = 1 ]; then take=""; tdir=1; tdest="${'$'}{arg}"; continue; fi
                |    if [ -n "${'$'}endopts" ]; then $positionalRule; continue; fi
                |    case "${'$'}arg" in
                |        --) endopts=1;;
                |        --target-directory) take=1;;
                |        --target-directory=*) tdir=1; tdest="${'$'}{arg#*=}";;
                |        --*) ;;
                |        -?*)
                |            rest="${'$'}{arg#-}"
                |            while [ -n "${'$'}rest" ]; do
                |                ch="${'$'}{rest%"${'$'}{rest#?}"}"
                |                rest="${'$'}{rest#?}"
                |                if [ "${'$'}ch" = t ]; then
                |                    tdir=1
                |                    if [ -n "${'$'}rest" ]; then tdest="${'$'}rest"; rest=""; else take=1; fi
                |                fi
                |            done;;
                |        *) $positionalRule;;
                |    esac
                |done
                |if [ "${'$'}tdir" = 1 ]; then
                |    if [ -z "${'$'}tdest" ]; then
                |        echo "$name: -t/--target-directory given without a directory — refused by the read-only mount guard" >&2
                |        exit 1
                |    fi
                |    check_prefix "${'$'}tdest" || exit 1
                |elif [ -n "${'$'}dest" ]; then
                |    check_prefix "${'$'}dest" || exit 1
                |fi
                """.trimMargin()
            }
            name == "dd" -> """
                |for arg in "${'$'}@"; do
                |    case "${'$'}arg" in
                |        oflag=*|ofs=*) ;;
                |        of=*) check_prefix "${'$'}{arg#of=}" || exit 1;;
                |    esac
                |done
            """.trimMargin()
            else -> """
                |for arg in "${'$'}@"; do
                |    case "${'$'}arg" in -*) continue;; esac
                |    check_prefix "${'$'}arg" || exit 1
                |done
            """.trimMargin()
        }
        return """#!/bin/sh
            |# $GUARD_MARKER — reject writes into read-only mounted folders.
            |# Auto-installed by MinisApp PRootKernel (T219-4).
            |cfg=$MOUNT_GUARD_CFG_LINUX_PATH
            |canon_out=""
            |# [audit3 F-07] POSIX-lexical canonicalization: collapses `//`, `/./`,
            |# `/../` and a trailing `/`, and resolves RELATIVE paths against the
            |# wrapper's cwd (the caller shell's cwd). Publishes through canon_out
            |# instead of stdout so the common absolute argument costs no command
            |# substitution. Lexical on purpose — see mountGuardScript's KDoc.
            |canon_target() {
            |    p=${'$'}1
            |    case "${'$'}p" in
            |        "") return 1;;
            |        /*) ;;
            |        *) cw=$(pwd) || return 1
            |           [ -n "${'$'}cw" ] || return 1
            |           p="${'$'}cw/${'$'}p";;
            |    esac
            |    out=""
            |    while [ -n "${'$'}p" ]; do
            |        case "${'$'}p" in
            |            */*) seg=${'$'}{p%%/*}; p=${'$'}{p#*/};;
            |            *) seg=${'$'}p; p="";;
            |        esac
            |        case "${'$'}seg" in
            |            ""|.) continue;;
            |            ..)
            |                case "${'$'}out" in
            |                    */*) out=${'$'}{out%/*};;
            |                    *) out="";;
            |                esac
            |                continue;;
            |        esac
            |        out="${'$'}out/${'$'}seg"
            |    done
            |    [ -n "${'$'}out" ] || out="/"
            |    canon_out="${'$'}out"
            |    return 0
            |}
            |# $1 is one selected write target. Refuses (return 1) an unresolvable
            |# target, the guard's own control files, and any target under a
            |# read-only prefix; allows everything else.
            |check_prefix() {
            |    canon_target "${'$'}1" || {
            |        echo "$name: ${'$'}1: cannot resolve the write target — refused by the read-only mount guard" >&2
            |        return 1
            |    }
            |    resolved="${'$'}canon_out"
            |    # Self-protection first, and independent of the list's existence:
            |    # the list and every wrapper are writable targets for the very
            |    # commands they gate, and destroying either turns the guard into a
            |    # silent no-op for every later call.
            |    if [ "${'$'}resolved" = "${'$'}cfg" ]; then
            |        echo "$name: ${'$'}1: read-only mount list — writes are disabled (manage mounts in Settings → Mount External Folders)" >&2
            |        return 1
            |    fi
            |    case "${'$'}resolved" in
            |        $guardBinPatterns)
            |            echo "$name: ${'$'}1: read-only mount guard wrapper — writes are disabled (manage mounts in Settings → Mount External Folders)" >&2
            |            return 1;;
            |    esac
            |    if [ ! -f "${'$'}cfg" ]; then
            |        return 0
            |    fi
            |    while IFS= read -r prefix; do
            |        [ -z "${'$'}prefix" ] && continue
            |        case "${'$'}resolved" in
            |            "${'$'}prefix"|"${'$'}prefix"/*)
            |                echo "$name: ${'$'}1: read-only mounted folder — writes are disabled (toggle in Settings → Mount External Folders)" >&2
            |                return 1;;
            |        esac
            |    done < "${'$'}cfg"
            |    return 0
            |}
            |$selection
            |exec /bin/busybox $name "${'$'}@"
            |""".trimMargin()
    }

    /**
     * Get the cache directory for PROOT_TMP_DIR.
     * Must be called after boot().
     */
    internal fun getProotTmpDir(context: Context): File {
        val tmpDir = File(context.cacheDir, "proot-tmp")
        tmpDir.mkdirs()
        return tmpDir
    }
}

/**
 * Pure lexical normalization of a guest linux path: collapses `//`, `/./` and
 * `seg/../` (POSIX rules — `/..` at the absolute root stays at the root).
 * Returns null for an empty input or a relative path that climbs above its
 * root (there is no meaningful mount check for such a string).
 *
 * [audit-1014 B5b] [PRootKernel.isLinuxPathUnderReadOnlyMount] used to match
 * the mount prefix on the RAW string, so `/var/minis/./mounts/...` or
 * `//var/minis/mounts/...` slipped past the guard while resolveHostPath's
 * safeResolveWithin still resolved the write INTO the read-only mount — the
 * guard was checked before the normalization that moved the path into it.
 */
internal fun normalizeLinuxPath(path: String): String? {
    if (path.isEmpty()) return null
    val absolute = path.startsWith("/")
    val out = ArrayList<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (out.isNotEmpty()) {
                    out.removeAt(out.size - 1)
                } else if (!absolute) {
                    return null
                }
            }
            else -> out.add(segment)
        }
    }
    return if (absolute) "/" + out.joinToString("/") else out.joinToString("/")
}

/**
 * True when [path] carries a literal `..` segment ([deep-audit-1016 FIX-C 2a]).
 *
 * Segment-wise, not a substring test: `a..b` is an ordinary file name. Used by
 * the session-scoped write classifier to treat `..` as the deliberate-escape
 * signal *before* normalization — normalizing it away would silently re-target
 * the write (`workspace/../browser/c` → `browser/c`) instead of refusing it.
 *
 * Top-level (not a member) so it is JVM-testable with zero Android deps, like
 * [normalizeLinuxPath].
 */
internal fun hasDotDotSegment(path: String): Boolean = path.split('/').any { it == ".." }

/**
 * One-shot initialization gate ([deep-audit-1016 FIX-C 3a]).
 *
 * `PRootKernel.boot()` used to be guarded by a bare `if (isBooted) return` on
 * a non-volatile flag, and it is called from two independent places on cold
 * start (`ExecutionCoordinator`'s auto-boot, `TerminalSession`'s
 * open-a-terminal). Both could observe `false` and run the body concurrently;
 * the body calls `RootfsManager.installIfNeeded`, which *deletes* the rootfs
 * directory and re-extracts it — so the second entrant deleted the tree the
 * first was still unpacking.
 *
 * A plain class (no Android, no `PRootKernel` state) so the concurrency
 * contract is JVM-testable: [run] executes the body at most once across
 * concurrent callers, and every later caller observes [isDone] == true and
 * skips. The flag is the *same fact* as the mutual exclusion, so a reader can
 * never see "done" while the body is still running.
 *
 * [meta1028 D3-F02] "At most once" is bounded by [invalidate]: the body writes
 * files into the rootfs, and `RootfsManager.reset()` deletes that rootfs, so
 * the latch has to have a reset edge or it asserts a state the disk no longer
 * has. Re-arming is the only transition; [run] stays at-most-once per arming.
 */
internal class BootOnceGate {

    private val mutex = Mutex()

    /** True once [run]'s body has completed. Volatile: read from other threads. */
    @Volatile
    var isDone: Boolean = false
        private set

    /**
     * Runs [body] unless it already completed; returns true when this call ran it.
     *
     * [body] is `suspend` because the only production caller is [PRootKernel.boot],
     * whose body awaits `RootfsManager`'s suspend API. The first version of this
     * refactor declared the parameter (and [PRootKernel.bootCriticalSection])
     * non-suspend and CI's `compileReleaseKotlin` rejected the file — the local
     * extraction harness slices fragments, so it cannot see a signature-level
     * error in a body it does not extract.
     */
    suspend fun run(body: suspend () -> Unit): Boolean {
        if (isDone) return false
        return mutex.withLock {
            if (isDone) return@withLock false
            body()
            isDone = true
            true
        }
    }

    /**
     * [meta1028 D3-F02] Forget that the body completed, so the next [run]
     * executes it again.
     *
     * Needed because the body's effect is not a one-way fact: it materializes
     * files INSIDE `RootfsManager.rootfsDir` (offload handler stubs, the
     * `default_mount` overlay, `/var/minis/mounts` placeholders, the read-only
     * mount wrappers), and `RootfsManager.reset()` deletes that directory and
     * re-extracts the factory tar. Without this edge the gate claimed "booted"
     * about a tree that no longer contained any of it — the asymmetry was the
     * defect: a one-way latch guarding a re-creatable artifact.
     *
     * Volatile store only, deliberately: callers never hold this class's mutex
     * across it (the reset path may run inside the body, where taking the
     * mutex again would deadlock), and a re-arm that races an in-flight [run]
     * is resolved by [run]'s final `isDone = true` — the body's tail always
     * re-materializes what the reset removed.
     */
    fun invalidate() {
        isDone = false
    }
}

/**
 * Pure core of [PRootKernel.resolveSessionHostPath]
 * ([deep-audit-1016 FIX-C 2a]).
 *
 * Decides "this session's `<subdir>` tree, or hand the path to the global
 * resolver" for a caller that knows the owning session. No `Context`, no
 * `PRootKernel` state, no boot requirement — the session base comes in as
 * [filesDir], the global arm as [resolveGlobal] — so the JVM test source set
 * can pin the whole parse matrix (canonical, malformed, escaping, symlinked).
 *
 * ## Normalize first, then match
 *
 * Every prefix/length decision runs on [normalizeLinuxPath]'s output. Matching
 * the RAW string (the pre-fix shape, and the shape [normalizeLinuxPath]'s own
 * KDoc describes for the read-only guard) made `.`/`//`-noise paths fall
 * through every scope check and land in the rootfs join instead — a ghost file
 * at `<rootfs>/var/minis/<subdir>/...`, shared by all sessions, while the
 * caller's real session directory stayed empty.
 *
 * ## Fail closed
 *
 * A null from [normalizeLinuxPath] (empty input, or a relative path that
 * climbs above its root) returns null — it is NOT re-matched as a raw string.
 * Falling back to the raw form here would reproduce the `?:` elvis hole
 * `resolvePathForSession` documents (backlog §111 R3).
 *
 * @param resolveGlobal consulted only when no session scope applies — a path
 *   outside `/var/minis/`, or one of the shared subdirs. Never consulted for a
 *   path this function rejects.
 */
internal fun resolveSessionGuestHostPath(
    sessionId: String,
    linuxPath: String,
    filesDir: File,
    perSessionSubdirs: Set<String>,
    resolveGlobal: (String) -> File?,
): File? {
    val normalized = normalizeLinuxPath(linuxPath) ?: return null
    if (!normalized.startsWith("/var/minis/")) return resolveGlobal(normalized)
    val rest = normalized.removePrefix("/var/minis/")
    val slash = rest.indexOf('/')
    val subdir = if (slash < 0) rest else rest.substring(0, slash)
    if (subdir !in perSessionSubdirs) return resolveGlobal(normalized)
    val sessionBase = File(filesDir, "minis-sessions/$sessionId/$subdir")
    val tail = if (slash < 0) "" else rest.substring(slash + 1)
    if (tail.isEmpty()) return sessionBase
    // The resolved path must stay inside sessionBase (guards symlink escapes
    // out of the session tree; dot-segments are already gone).
    return PRootKernel.safeResolveWithin(sessionBase, tail)
}

/**
 * Pure core of [PRootKernel.resolveHostPath] ([deep-audit-1016 FIX-C 2a]).
 *
 * Longest-bind-mount-prefix match, then the per-session tree scan (no session
 * context — picks the first existing match), then the rootfs join. State comes
 * in as parameters ([bindMounts], [sessionsRoot], [rootfsDir] — the latter two
 * null before boot) so the JVM test source set can pin the full parse matrix
 * without an Android `Context`.
 *
 * Normalization happens ONCE, on entry, and every later match uses the
 * normalized string — the pre-fix code matched prefixes on the raw input while
 * [PRootKernel.safeResolveWithin] (and the read-only-mount guard) resolved
 * dot-segments themselves, so the two disagreed about which file a malformed
 * path named.
 *
 * Behaviour notes for the parse matrix (see the FIX-C report):
 *  - `/var/minis/./workspace/x` and `//var/minis/workspace/x` now resolve like
 *    `/var/minis/workspace/x` (pre-fix: rootfs ghost);
 *  - `/var/minis/../etc/passwd` now resolves like `/etc/passwd` (pre-fix:
 *    null). This is POSIX/PRoot semantics and matches
 *    [com.rikkaminis.app.sandbox.GuestPathMapper.resolve]'s own normalization;
 *    the result is still inside the sandbox (rootfs or a bind mount), never on
 *    a host path outside it;
 *  - `""` now returns null (pre-fix: the rootfs directory itself);
 *  - every canonical path returns exactly what it returned before — that is
 *    the regression contract pinned by `GuestPathParseMatrixTest`.
 */
internal fun resolveGuestHostPath(
    linuxPath: String,
    bindMounts: Map<String, String>,
    perSessionSubdirs: Set<String>,
    sessionsRoot: File?,
    rootfsDir: File?,
): File? {
    val normalized = normalizeLinuxPath(linuxPath) ?: return null

    // Check bind mounts (longest prefix match)
    val sorted = bindMounts.keys.sortedByDescending { it.length }
    for (mountPoint in sorted) {
        if (normalized == mountPoint || normalized.startsWith("$mountPoint/")) {
            val hostBase = bindMounts[mountPoint]!!
            val relativePath = normalized.removePrefix(mountPoint).removePrefix("/")
            if (relativePath.isEmpty()) {
                File(hostBase)
            } else {
                // Keep the resolved path inside hostBase (guards `..`/symlink
                // escapes out of the mount).
                PRootKernel.safeResolveWithin(File(hostBase), relativePath)
            }.let { return it }
        }
    }

    // Per-session subdir fallback: no session context available — search
    // all minis-sessions/<id>/ trees for an existing file (or existing
    // dir) at this Linux path. Picks the first match; ambiguity is
    // acceptable here because these callers can't know the session anyway.
    if (normalized.startsWith("/var/minis/")) {
        val rest = normalized.removePrefix("/var/minis/")
        val subdir = rest.substringBefore('/')
        if (subdir in perSessionSubdirs) {
            val tail = rest.substringAfter('/', missingDelimiterValue = "")
            if (sessionsRoot?.isDirectory == true) {
                sessionsRoot.listFiles()?.forEach { sessionDir ->
                    if (!sessionDir.isDirectory) return@forEach
                    val candidate = if (tail.isEmpty()) File(sessionDir, subdir)
                    else PRootKernel.safeResolveWithin(File(sessionDir, subdir), tail)
                    if (candidate?.exists() == true) return candidate
                }
            }
            // No existing file in any session — fall through to rootfs
            // placeholder (matches pre-existing behavior for missing files).
        }
    }

    // Fallback: resolve relative to rootfs
    if (rootfsDir == null) return null
    val stripped = normalized.removePrefix("/")
    if (stripped.isEmpty()) return rootfsDir
    // Keep the resolved path inside the rootfs — never allow a symlink to
    // climb out into the host filesystem (dot-segments are already gone).
    return PRootKernel.safeResolveWithin(rootfsDir, stripped)
}
