package com.openminis.app.sandbox

import com.openminis.app.R
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/**
 * Observable state for rootfs installation. Mirrors the conceptual iOS
 * `downloadRootfs(progress:)` contract: discrete phases with a 0..1 fraction
 * during the long-running extract step. Neither platform actually downloads
 * the rootfs from the network today — it ships bundled as an asset — so
 * "progress" tracks asset-stream consumption (compressed bytes) instead of
 * HTTP transfer. The fraction semantics are identical from the UI's viewpoint.
 */
sealed class RootfsInstallState {
    object Idle : RootfsInstallState()
    object Preparing : RootfsInstallState()
    /** progress in 0f..1f, based on compressed asset bytes consumed. */
    data class Extracting(val progress: Float) : RootfsInstallState()
    object Finalizing : RootfsInstallState()
    object Installed : RootfsInstallState()
    data class Failed(val error: String) : RootfsInstallState()
}

/**
 * File-level integrity snapshot of the extracted rootfs.
 *
 * The install marker (`.arch`) only proves extraction happened — it says
 * nothing about whether the files the runtime actually needs survived. A
 * silent_kill mid-write (HyperOS memory pressure kills the app while a file
 * write or apk operation is half-done) leaves the rootfs with a valid marker
 * but missing/corrupt binaries — the classic symptom is the terminal dying
 * with `'/bin/bash' not found` while the app still reports "installed".
 *
 * Each check is a single stat() — cheap enough to run on every boot.
 */
data class RootfsHealth(
    /** /bin/bash — interactive terminal shell (readline-based). */
    val bash: Boolean,
    /** /bin/sh — busybox ash fallback (symlink → /bin/busybox, apk-managed). */
    val sh: Boolean,
    /** /lib/ld-musl-aarch64.so.1 — dynamic loader every ELF needs. */
    val libc: Boolean,
    /** /usr/lib/libreadline.so.8 — bash's line editing (symlink to .so.8.2). */
    val libreadline: Boolean,
    /** /usr/lib/libncursesw.so.6 — readline's terminal rendering. */
    val libncursesw: Boolean,
    /** /sbin/apk — package manager, needed for in-place auto-repair. */
    val apk: Boolean,
    /** /lib/apk/db/installed — apk's package database. */
    val apkDatabase: Boolean,
) {
    /** Everything needed for the sandbox to function. */
    val healthy: Boolean
        get() = bash && sh && libc && apk && apkDatabase

    /** Everything needed for an interactive bash terminal. */
    val terminalOk: Boolean
        get() = bash && libc && libreadline && libncursesw

    /** Human-readable list of missing paths (empty when fully healthy). */
    val missing: List<String>
        get() = buildList {
            if (!bash) add("/bin/bash")
            if (!sh) add("/bin/sh")
            if (!libc) add("/lib/ld-musl-aarch64.so.1")
            if (!libreadline) add("/usr/lib/libreadline.so.8")
            if (!libncursesw) add("/usr/lib/libncursesw.so.6")
            if (!apk) add("/sbin/apk")
            if (!apkDatabase) add("/lib/apk/db/installed")
        }
}

/**
 * Manages Alpine Linux rootfs installation and PRoot binary extraction.
 * Corresponds to iOS RootfsManager.swift.
 */
class RootfsManager private constructor(private val context: Context) {

    val rootfsDir: File = File(context.filesDir, "alpine-rootfs")
    val prootBinary: File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    private val archFile: File get() = File(rootfsDir, ".arch")
    private val integrityManifest: File get() = File(rootfsDir, ".integrity_manifest")

    val isInstalled: Boolean
        get() = rootfsDir.exists() && archFile.exists() &&
                archFile.readText().trim() == ARCH

    /**
     * Observable install progress. UI layers (OnboardingScreen,
     * RootfsManagementScreen) bind this and render a progress bar during
     * `installIfNeeded()` / `reset()`. Emits `Installed` on success and
     * `Failed` on error so callers can surface retry affordances.
     */
    private val _installState = MutableStateFlow<RootfsInstallState>(
        if (isInstalled) RootfsInstallState.Installed else RootfsInstallState.Idle
    )
    val installState: StateFlow<RootfsInstallState> = _installState.asStateFlow()

    /**
     * Install Alpine rootfs from assets if not already present.
     * Extracts alpine-minirootfs.tar.gz using manual POSIX tar parsing.
     * Progress is published to [installState] (Preparing → Extracting(f) →
     * Finalizing → Installed / Failed).
     */
    suspend fun installIfNeeded() = withContext(Dispatchers.IO) {
        if (isInstalled) {
            Log.d(TAG, "Rootfs already installed at $rootfsDir")
            _installState.value = RootfsInstallState.Installed
            return@withContext
        }

        try {
            // --- Disk-space gate (P4) -----------------------------------------
            // Refuse to start extraction when the device can't plausibly hold
            // the extracted rootfs. Checking up front avoids a half-written,
            // corrupt rootfs when storage runs out mid-extract (a silent_kill
            // situation that otherwise only surfaces on the next boot as
            // "installed but broken"). Conservative estimate: compressed asset
            // × 4 (observed Alpine minirootfs expansion) + 64 MiB margin.
            val spaceGateAssetName = try {
                context.assets.open(ROOTFS_ASSET).close()
                ROOTFS_ASSET
            } catch (_: java.io.FileNotFoundException) {
                ROOTFS_ASSET_TAR
            }
            val compressedBytes: Long = try {
                context.assets.openFd(spaceGateAssetName).use { it.length }
            } catch (_: Exception) { 0L }

            val usableBytes = context.filesDir.usableSpace
            if (compressedBytes > 0 && !hasEnoughSpaceForRootfs(usableBytes, compressedBytes)) {
                val neededMB = (compressedBytes * ROOTFS_EXPANSION_FACTOR + ROOTFS_SPACE_MARGIN_MB * 1024L * 1024L) / (1024L * 1024L)
                val freeMB = usableBytes / (1024L * 1024L)
                Log.w(TAG, "Rootfs install aborted: need ~${neededMB} MiB free but only ${freeMB} MiB available")
                _installState.value = RootfsInstallState.Failed(
                    context.getString(R.string.rootfs_insufficient_space, neededMB, freeMB)
                )
                return@withContext
            }

            _installState.value = RootfsInstallState.Preparing
            Log.i(TAG, "Installing Alpine rootfs...")

            // Clean up any partial install
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            // Extract rootfs from assets.
            // AAPT may decompress .tar.gz → .tar automatically, so try both names.
            val assetName = try {
                context.assets.open(ROOTFS_ASSET).close()
                ROOTFS_ASSET
            } catch (_: java.io.FileNotFoundException) {
                ROOTFS_ASSET_TAR
            }

            // Asset size for progress calculation — compressed length (for .gz)
            // or uncompressed length (for .tar). openFd() fails for 0-length
            // assets on some devices; fall back to 0 which disables progress.
            val assetTotal: Long = try {
                context.assets.openFd(assetName).use { it.length }
            } catch (_: Exception) { 0L }

            // Emit an initial 0% so the UI flips from Preparing → progress bar.
            _installState.value = RootfsInstallState.Extracting(0f)

            context.assets.open(assetName).use { rawAsset ->
                // Wrap the ASSET stream (not the gzip stream) so progress tracks
                // compressed bytes consumed — monotonic and matches the size we
                // have a total for. Throttle updates to avoid flooding the StateFlow.
                val progressStream = ProgressInputStream(rawAsset, assetTotal) { fraction ->
                    _installState.value = RootfsInstallState.Extracting(fraction)
                }
                if (assetName.endsWith(".gz")) {
                    GZIPInputStream(progressStream).use { gzipStream ->
                        extractTar(gzipStream, rootfsDir)
                    }
                } else {
                    extractTar(progressStream, rootfsDir)
                }
            }

            _installState.value = RootfsInstallState.Finalizing

            // Write arch marker
            archFile.writeText(ARCH)

            // Write integrity manifest so verifyIntegrity can detect
            // partial/corrupt files on subsequent boots.
            writeIntegrityManifest()

            // Pre-create /var/minis directories. Mirrors iOS
            // RootfsManager.swift:76-80 (attachments/offloads/workspace/skills/
            // shared) plus Android-specific `memory` kept from prior parity work.
            // T219-6: also pre-create `mounts/` so PRoot's `-b host:/var/minis/mounts/<name>`
            // has the parent directory to bind into; without this, PRoot silently
            // skips bind mounts whose target path doesn't exist.
            val minisSubdirs = listOf("attachments", "offloads", "workspace", "skills", "memory", "shared", "mounts", "logs")
            for (subdir in minisSubdirs) {
                File(rootfsDir, "var/minis/$subdir").mkdirs()
            }

            // Pre-create /opt/bin — appears in PATH so users can drop third-party
            // binaries here without first `mkdir -p`. Matches iOS PATH layout.
            File(rootfsDir, "opt/bin").mkdirs()

            // Write resolv.conf from system DNS (fallback to 8.8.8.8)
            refreshDns()

            // Flag a fresh install so MirrorSpeedTestViewModel.autoDetectOnceIfNeeded()
            // picks the fastest mirror for each category on first boot.
            context.getSharedPreferences("mirror_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("rootfs.freshInstall", true)
                .apply()

            // [T-rootfs-event-log] Persist the install generation (survives
            // wipes — lives outside rootfsDir) and record why this extraction
            // happened, so "did the sandbox reset?" has a durable answer.
            installGeneration += 1
            RootfsEventLog.writeBootId(context.filesDir, installGeneration)
            RootfsEventLog.logEvent(
                eventLogsDir,
                RootfsEventLog.Events.INSTALL,
                "gen=$installGeneration",
            )

            // [Refactor-apk-world] A fresh extraction ships only the factory
            // packages. Re-apply the host-side snapshot of user packages
            // (recorded by dumpApkWorld) so a reset / rebuild doesn't wipe
            // what the user installed. Skip is cheap (no snapshot = no-op);
            // failures are queued for retryFailedApkWorld on next boot.
            restoreApkWorld()

            Log.i(TAG, "Rootfs installation complete")
            _installState.value = RootfsInstallState.Installed
        } catch (t: Throwable) {
            Log.e(TAG, "Rootfs installation failed", t)
            _installState.value = RootfsInstallState.Failed(t.message ?: t.javaClass.simpleName)
            throw t
        }
    }

    /** Directory containing extracted native libraries (read-only, executable). */
    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    /**
     * Verify PRoot binary is available in the native library directory.
     */
    suspend fun installProotIfNeeded() = withContext(Dispatchers.IO) {
        if (!prootBinary.exists() || !prootBinary.canExecute()) {
            throw IllegalStateException(
                "PRoot binary not found at $prootBinary. " +
                "It should be auto-extracted from jniLibs."
            )
        }

        // No libtalloc staging: deps/build_proot.sh links talloc statically
        // (the binary carries no DT_NEEDED for libtalloc.so), so there is no
        // shared object to version-rename. Older builds shipped libtalloc.so
        // in jniLibs and copied it here as libtalloc.so.2.

        Log.d(TAG, "PRoot binary available at $prootBinary")
    }

    /**
     * Snapshot which critical rootfs files are present, executable, and
     * match their expected sizes from the integrity manifest.
     *
     * Cheap (7 stat calls), safe to call on every boot. See [RootfsHealth]
     * for the rationale — a silent_kill can leave `.arch` valid but bash
     * (or its readline/ncurses symlinks) missing.
     *
     * When `.integrity_manifest` exists, each file's size is verified against
     * the recorded value — a file that exists but has the wrong size (e.g.
     * truncated by a mid-write kill) is treated as missing. This catches
     * silent_kill scenarios that leave the file system structurally intact
     * but the file content incomplete.
     *
     * When the manifest file is absent (pre-upgrade installations), the check
     * falls back to existence-only — backward compatible with rootfs images
     * that predate this feature.
     */
    fun verifyIntegrity(): RootfsHealth {
        val expectedSizes = readIntegrityManifest()

        fun exists(rel: String): Boolean {
            val f = File(rootfsDir, rel)
            if (!f.exists()) return false
            return integritySizePasses(rel, f.length(), expectedSizes)
        }
        fun executable(rel: String): Boolean {
            val f = File(rootfsDir, rel)
            if (!f.exists() || !f.canExecute()) return false
            return integritySizePasses(rel, f.length(), expectedSizes)
        }

        return RootfsHealth(
            bash = exists("bin/bash"),
            sh = exists("bin/sh"),
            libc = exists("lib/ld-musl-aarch64.so.1"),
            libreadline = exists("usr/lib/libreadline.so.8"),
            libncursesw = exists("usr/lib/libncursesw.so.6"),
            apk = executable("sbin/apk"),
            apkDatabase = exists("lib/apk/db/installed"),
        )
    }

    /**
     * Read the integrity manifest file and return a map of relative path →
     * expected file size in bytes. Returns an empty map when the manifest
     * doesn't exist or is malformed (backward compat).
     */
    private fun readIntegrityManifest(): Map<String, Long> {
        if (!integrityManifest.exists()) return emptyMap()
        return try {
            parseIntegrityManifest(integrityManifest.readText())
        } catch (e: Exception) {
            Log.w(TAG, "[Integrity] Failed to read integrity manifest: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Write the integrity manifest after successful extraction.
     * Records the actual file sizes of all critical paths so [verifyIntegrity]
     * can detect partial/corrupt files on subsequent boots.
     *
     * Written as the very last step of installation before the dirs setup,
     * so it serves as the definitive completion signal — if a silent_kill
     * interrupts extraction, the manifest is never written, and the next boot
     * will see `.arch` present but manifest absent, enabling a more thorough
     * integrity check.
     */
    private fun writeIntegrityManifest() {
        val criticalPaths = listOf(
            "bin/bash", "bin/sh", "lib/ld-musl-aarch64.so.1",
            "usr/lib/libreadline.so.8", "usr/lib/libncursesw.so.6",
            "sbin/apk", "lib/apk/db/installed",
        )
        try {
            val lines = criticalPaths.map { rel ->
                val f = File(rootfsDir, rel)
                val size = if (f.exists()) f.length() else 0L
                "$rel=$size"
            }
            integrityManifest.writeText(lines.joinToString("\n") + "\n")
            Log.i(TAG, "[Integrity] Manifest written with ${lines.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "[Integrity] Failed to write manifest: ${e.message}")
        }
    }

    /**
     * Attempt to repair a broken rootfs in place, least-to-most destructive:
     *
     *  1. `apk fix --no-cache` — restore missing/corrupt files owned by
     *     installed packages (readline/ncursesw symlinks, bash, …). Uses the
     *     local apk database; no network needed when `.apk` files are cached.
     *  2. `apk add --no-cache bash readline ncurses` — belt-and-braces for
     *     the terminal's dynamic-linking chain.
     *  3. Full [reset] — delete + re-extract from the bundled asset. Last
     *     resort (wipes user-installed packages), only when apk is unusable.
     *
     * Returns true when the rootfs is healthy after the attempt.
     */
    suspend fun autoRepair(): Boolean = withContext(Dispatchers.IO) {
        val initial = verifyIntegrity()
        if (initial.healthy) {
            Log.i(TAG, "[Repair] rootfs healthy, nothing to do")
            return@withContext true
        }
        Log.w(TAG, "[Repair] rootfs damaged, missing: ${initial.missing}")

        // Stage 1+2: apk repair inside the guest via proot, so it operates on
        // the real rootfs with the user's mirror config intact.
        val prootFile = prootBinary
        if (prootFile.exists() && initial.apk) {
            val repairCmd = listOf(
                prootFile.absolutePath,
                "-0", "--link2symlink", "--kill-on-exit",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-w", "/root",
                "/bin/sh", "-c",
                "/sbin/apk fix --no-cache ; /sbin/apk add --no-cache bash readline ncurses ; true"
            )
            // PROOT_LOADER[_32] MUST point at the standalone loaders in
            // nativeLibraryDir — proot's embedded-loader fallback writes to
            // PROOT_TMP_DIR and fails under Android noexec (see
            // deps/build_proot.sh). Without these, proot aborts in ~20ms
            // with status=1 and no output, and the repair silently no-ops.
            // PATH is set explicitly too — ProcessBuilder inherits the app
            // process env (Android PATH), so a bare `apk` would be
            // `apk: not found` (exit 127) inside the guest.
            val loaderEnv = prootLoaderEnv()

            runCatching {
                val p = ProcessBuilder(repairCmd)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(loaderEnv) }
                    .start()
                val output = p.inputStream.readBytes().toString(Charset.forName("UTF-8"))
                val code = p.waitFor()
                Log.i(TAG, "[Repair] apk repair exit=$code output=${output.takeLast(500)}")
            }.onFailure { t ->
                Log.e(TAG, "[Repair] apk repair process failed", t)
            }
        }

        val after = verifyIntegrity()
        if (after.healthy) {
            Log.i(TAG, "[Repair] rootfs healthy after apk repair")
            return@withContext true
        }

        // Stage 2.5: targeted restore of factory files from the bundled
        // asset — no network, no user-package loss. This closes the hole
        // where apk itself was fine (so Stage 3's database guard would NOT
        // fire) but `apk fix` failed because the network was down: without
        // it, a broken bash stayed broken forever until a manual reset.
        // Only factory files safe to overwrite are restored; the apk
        // database (user package records) is deliberately untouched.
        val restored = restoreCriticalFromAssets()
        if (restored) {
            Log.i(TAG, "[Repair] rootfs healthy after targeted asset restore")
            return@withContext true
        }

        // Stage 2.6: offline install of bash/readline/ncurses from bundled
        // APK files (assets/apk-offline/). These packages are NOT in the
        // factory minirootfs (Stage 2.5 can't restore them), and `apk add`
        // fails when the network is down. The bundled APK files cover the
        // gap without requiring network access. Stage 2.5 must have restored
        // busybox (/bin/sh) first, so proot can run inside the guest.
        Log.i(TAG, "[Repair] Stage 2.6: offline install of extra packages")
        installOfflinePackages()
        val afterOffline = verifyIntegrity()
        if (afterOffline.healthy) {
            Log.i(TAG, "[Repair] rootfs healthy after offline package install")
            return@withContext true
        }

        // Stage 3: last resort — full reset. Only when the apk *database*
        // (user package records) is unusable: an apk binary that Stage 2.5
        // could not fix is not in the safe-restore set either, so it resets
        // too. A healthy database with a still-broken bash is NOT reset —
        // wiping user packages over a few missing binaries is worse than
        // leaving the terminal broken for a manual retry.
        if (!verifyIntegrity().apkDatabase) {
            Log.w(TAG, "[Repair] apk database unusable, falling back to full reset")
            // [T-rootfs-event-log] This is the SILENT WIPE that used to be
            // invisible outside rotating logcat — record it on the host.
            RootfsEventLog.logEvent(
                eventLogsDir,
                RootfsEventLog.Events.STAGE3_RESET,
                "trigger=apkDatabaseUnusable missing=${initial.missing.joinToString(",")}",
            )
            runCatching { reset() }
        }

        val final = verifyIntegrity()
        RootfsEventLog.logEvent(
            eventLogsDir,
            RootfsEventLog.Events.AUTO_REPAIR,
            "result=${if (final.healthy) "healthy" else "still-broken"}",
        )
        Log.i(TAG, "[Repair] final health: ${final.missing.ifEmpty { listOf("OK") }}")
        final.healthy
    }

    /**
     * Restore the rootfs's critical system files from the bundled asset tar,
     * without wiping user-installed packages. Only factory files that are
     * safe to overwrite are restored (bash/busybox/sh, musl loader, readline,
     * ncursesw, apk binary); the apk database (`lib/apk/db/installed`, which
     * holds user package records) is deliberately excluded — if it is broken
     * the caller must fall back to a full reset. Network-independent, so it
     * also covers the "apk fix failed because the proxy is down" case.
     *
     * Returns true when every non-database critical path is healthy after
     * the restore (the apk database is ignored — restoring it would discard
     * user package records).
     */
    suspend fun restoreCriticalFromAssets(): Boolean = withContext(Dispatchers.IO) {
        val assetName = try {
            context.assets.open(ROOTFS_ASSET).close()
            ROOTFS_ASSET
        } catch (_: Exception) {
            ROOTFS_ASSET_TAR
        }
        try {
            context.assets.open(assetName).use { raw ->
                val tarInput = if (assetName.endsWith(".gz")) GZIPInputStream(raw) else raw
                extractTar(tarInput, rootfsDir, onlyPrefixes = CRITICAL_RESTORE_PREFIXES)
            }
            // Some Android filesystems reject the tar's absolute `/bin/busybox`
            // symlink target during targeted extraction. Recreate the Alpine
            // canonical relative link explicitly; it remains inside the rootfs
            // under both ordinary File checks and PRoot's guest root.
            ensureBusyboxShellSymlink(rootfsDir)
            val h = verifyIntegrity()
            val nonDbOk = h.bash && h.sh && h.libc && h.libreadline && h.libncursesw && h.apk
            if (nonDbOk) {
                Log.i(TAG, "[Repair] targeted restore OK")
            } else {
                Log.w(TAG, "[Repair] targeted restore incomplete, still missing: ${h.missing}")
            }
            nonDbOk
        } catch (t: Exception) {
            Log.e(TAG, "[Repair] targeted restore failed", t)
            false
        }
    }

    /**
     * Install bash, readline, and ncurses from bundled APK files
     * (assets/apk-offline/) via proot, without network access.
     * These packages are NOT in the factory minirootfs (Stage 2.5 can't
     * restore them), and `apk add` fails when the network is down.
     * The bundled APK files cover the gap without requiring network access.
     * Stage 2.5 must have restored busybox (/bin/sh) first, so proot runs.
     *
     * Called from [autoRepair] Stage 2.6 after factory file restoration.
     */
    private suspend fun installOfflinePackages(): Boolean = withContext(Dispatchers.IO) {
        if (!prootBinary.exists()) {
            Log.w(TAG, "[OfflinePackages] proot binary not available")
            return@withContext false
        }
        val apkDir = File(rootfsDir, "tmp/apk-offline")
        apkDir.mkdirs()
        try {
            val apkFiles = mutableListOf<String>()
            for (apkName in OFFLINE_PACKAGES) {
                try {
                    context.assets.open("apk-offline/$apkName").use { src ->
                        val dst = File(apkDir, apkName)
                        dst.outputStream().use { dstStream -> src.copyTo(dstStream) }
                        apkFiles.add(dst.absolutePath)
                    }
                } catch (_: java.io.FileNotFoundException) {
                    Log.w(TAG, "[OfflinePackages] asset not found: apk-offline/$apkName")
                }
            }
            if (apkFiles.isEmpty()) {
                Log.w(TAG, "[OfflinePackages] no APK files to install")
                return@withContext false
            }
            val cmd = listOf(
                prootBinary.absolutePath, "-0", "--link2symlink", "--kill-on-exit",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root",
                "-b", "${apkDir.absolutePath}:/tmp/apk-offline",
                "/bin/sh", "-c",
                // Absolute apk path + explicit PATH: the app-process PATH
                // (Android's) is what proot children inherit, so a bare
                // `apk` used to be `apk: not found` (exit 127) here. The
                // trailing `; true` ALSO masked every real failure by
                // forcing exit 0 — remove it so failures surface.
                "/sbin/apk add --allow-untrusted /tmp/apk-offline/*.apk"
            )
            val loaderEnv = prootLoaderEnv()
            runCatching {
                val p = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(loaderEnv) }
                    .start()
                val output = p.inputStream.readBytes().toString(Charset.forName("UTF-8"))
                val finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                val exitCode = if (finished) p.exitValue() else -1
                if (p.isAlive) p.destroyForcibly()
                Log.i(TAG, "[OfflinePackages] apk add exit=$exitCode output=${output.takeLast(500)}")
                exitCode == 0
            }.onFailure { t ->
                Log.e(TAG, "[OfflinePackages] process failed", t)
                false
            }.getOrDefault(false)
        } catch (t: Exception) {
            Log.e(TAG, "[OfflinePackages] failed", t)
            false
        } finally {
            apkDir.deleteRecursively()
        }
    }

    // ── Apk world snapshot (user-package persistence) ─────────────────────
    //
    // The factory minirootfs ships no bash/readline/ncurses and no user
    // packages — everything a user `apk add`s lives only inside the rootfs.
    // A full rebuild (manual reset, or [autoRepair]'s Stage 3 after an
    // unusable apk database) wipes it all. The snapshot makes user packages
    // a recoverable state: [dumpApkWorld] persists `name=version` to the
    // host side on every boot, and [restoreApkWorld] re-applies it right
    // after a fresh extraction inside [installIfNeeded].

    /** Host-side snapshot of installed packages (`name=version` per line). */
    val apkWorldFile: File get() = File(context.filesDir, "apk-world.txt")

    /** Host-side retry list for packages that failed to restore. */
    val apkWorldFailedFile: File get() = File(context.filesDir, "apk-world-failed.txt")

    /** [T-rootfs-event-log] Host-side append-only lifecycle event log dir (survives rootfs wipes). */
    private val eventLogsDir: File get() = File(context.filesDir, "logs")

    /** [T-rootfs-event-log] Install-generation counter, bumped on every fresh extraction. */
    private var installGeneration: Long = 0L

    /**
     * Snapshot the currently installed packages to the host side
     * (filesDir/apk-world.txt). Reads Alpine's `lib/apk/db/installed`
     * directly on the host filesystem — no proot involved, cheap enough to
     * run on every boot. A restorable snapshot is only meaningful when the
     * rootfs is in its final state, so callers invoke this AFTER any
     * auto-repair (PRootKernel.boot) or right before a wipe ([reset]).
     */
    suspend fun dumpApkWorld() = withContext(Dispatchers.IO) {
        val db = File(rootfsDir, "lib/apk/db/installed")
        if (!db.exists()) {
            Log.d(TAG, "[ApkWorld] apk db not present — rootfs not installed, skip dump")
            return@withContext
        }
        try {
            val packages = parseApkDbInstalled(db.readText())
            // Guard: an unreadable/corrupt apk db parses to an empty list.
            // The factory rootfs always ships packages, so an empty parse
            // means the db is broken (the classic pre-Stage-3-reset state) —
            // overwriting the snapshot with it would erase user packages on
            // the next restore. Keep the previous snapshot instead.
            if (packages.isEmpty()) {
                Log.w(TAG, "[ApkWorld] apk db unreadable (${db.length()} bytes, 0 packages) — keeping previous snapshot")
                return@withContext
            }
            apkWorldFile.writeText(formatApkWorld(packages))
            Log.i(TAG, "[ApkWorld] snapshot ${packages.size} packages -> ${apkWorldFile.name}")
        } catch (t: Exception) {
            Log.w(TAG, "[ApkWorld] dump failed: ${t.message}")
        }
    }

    /**
     * Re-apply the snapshot after a fresh extraction: `apk add name=version`
     * for every recorded package (order preserved), skipping the offline
     * trio (bash/readline/ncurses) which the bundled-APK Stage 2.6 path
     * guarantees on every rebuild.
     *
     * Failure (offline, repo issue, pruned version) is NON-blocking: the
     * failed list is persisted to [apkWorldFailedFile] for the next boot's
     * [retryFailedApkWorld], and the rootfs stays usable — a missing user
     * package just surfaces as "not installed".
     */
    suspend fun restoreApkWorld(): Boolean = withContext(Dispatchers.IO) {
        if (!apkWorldFile.exists()) {
            Log.d(TAG, "[ApkWorld] no snapshot file, nothing to restore")
            return@withContext true
        }
        val packages = try {
            parseApkWorld(apkWorldFile.readText())
        } catch (t: Exception) {
            Log.e(TAG, "[ApkWorld] failed to read snapshot, skip restore", t)
            return@withContext false
        }
        val targets = excludeOfflinePackages(packages)
        if (targets.isEmpty()) {
            Log.i(TAG, "[ApkWorld] snapshot holds only offline-trio packages, nothing to restore")
            return@withContext true
        }
        val args = targets.map { "${it.name}=${it.version}" }
        Log.i(TAG, "[ApkWorld] restoring ${args.size} packages: ${args.take(6).joinToString()}")
        val code = runApkAddInGuest(args)
        if (code == 0) {
            Log.i(TAG, "[ApkWorld] restore OK (${args.size} packages)")
            // [T-rootfs-event-log] The auto-restore that makes apk packages
            // "survive" resets — record it so users can see it happened.
            RootfsEventLog.logEvent(
                eventLogsDir,
                RootfsEventLog.Events.APKWORLD_RESTORE,
                "ok=${args.size} failed=0",
            )
            try { apkWorldFailedFile.delete() } catch (_: Exception) {}
            return@withContext true
        }
        writeApkWorldFailed(args)
        Log.w(TAG, "[ApkWorld] restore failed (exit=$code) — ${args.size} pkg(s) queued for retry")
        RootfsEventLog.logEvent(
            eventLogsDir,
            RootfsEventLog.Events.APKWORLD_RESTORE,
            "ok=0 failed=${args.size} exit=$code",
        )
        false
    }

    /**
     * Retry the previously-failed packages at boot time, one by one
     * (the list is usually small). Runs with the user's mirror config
     * already applied, so packages that failed against the factory repos
     * get a second chance. Clears the retry list on full success.
     */
    suspend fun retryFailedApkWorld(): Boolean = withContext(Dispatchers.IO) {
        if (!apkWorldFailedFile.exists()) return@withContext true
        val args = try {
            apkWorldFailedFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        } catch (t: Exception) {
            Log.w(TAG, "[ApkWorld] failed to read retry list", t)
            return@withContext false
        }
        if (args.isEmpty()) {
            try { apkWorldFailedFile.delete() } catch (_: Exception) {}
            return@withContext true
        }
        Log.i(TAG, "[ApkWorld] retrying ${args.size} previously-failed package(s)")
        var allOk = true
        val stillFailed = mutableListOf<String>()
        for (arg in args) {
            val code = runApkAddInGuest(listOf(arg))
            if (code == 0) {
                Log.i(TAG, "[ApkWorld] retry OK: $arg")
            } else {
                allOk = false
                stillFailed.add(arg)
            }
        }
        if (stillFailed.isEmpty()) {
            try { apkWorldFailedFile.delete() } catch (_: Exception) {}
            Log.i(TAG, "[ApkWorld] retry fully succeeded")
            RootfsEventLog.logEvent(
                eventLogsDir,
                RootfsEventLog.Events.APKWORLD_RETRY,
                "ok=${args.size} failed=0",
            )
        } else {
            writeApkWorldFailed(stillFailed)
            Log.w(TAG, "[ApkWorld] ${stillFailed.size} package(s) still failing: ${stillFailed.take(5).joinToString()}")
            RootfsEventLog.logEvent(
                eventLogsDir,
                RootfsEventLog.Events.APKWORLD_RETRY,
                "ok=${args.size - stillFailed.size} failed=${stillFailed.size}",
            )
        }
        allOk
    }

    /** Persist a failed `name=version` list for the next boot's retry. */
    private fun writeApkWorldFailed(args: List<String>) {
        try {
            apkWorldFailedFile.writeText(
                buildString {
                    appendLine("# apk-world retry list — packages that failed to restore")
                    args.forEach { appendLine(it) }
                }
            )
        } catch (t: Exception) {
            Log.w(TAG, "[ApkWorld] failed to write retry list: ${t.message}")
        }
    }

    /**
     * Loader env for every proot child process. MUST include:
     *  - `PROOT_LOADER[_32]` → standalone loaders in nativeLibraryDir
     *    (proot's embedded-loader fallback writes to PROOT_TMP_DIR and
     *    fails under Android noexec — without these proot aborts ~20ms in
     *    with status=1 and no output)
     *  - `PATH` → the ALPINE guest PATH. ProcessBuilder inherits the app
     *    process env, whose PATH is Android's (`/sbin:/vendor/bin:...`) —
     *    the guest `/bin/sh` then can't find `apk` (exit 127). This was
     *    the real reason both Stage 2.6 and the apk-world restore silently
     *    failed on device: `apk: not found` inside proot.
     */
    private fun prootLoaderEnv(): Map<String, String> {
        val env = mutableMapOf(
            "PATH" to ALPINE_PATH,
            "PROOT_TMP_DIR" to PRootKernel.getProotTmpDir(context).absolutePath,
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
        )
        File(nativeLibDir, "libproot-loader.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER"] = it.absolutePath
        }
        File(nativeLibDir, "libproot-loader32.so").takeIf { it.exists() }?.let {
            env["PROOT_LOADER_32"] = it.absolutePath
        }
        return env
    }

    /**
     * Run `apk add --no-cache <name>=<version>...` inside the guest via
     * proot. Shares the loader-env boilerplate with [installOfflinePackages].
     * Returns the apk exit code (0 = all installed), or -1 on process
     * failure / timeout.
     */
    private suspend fun runApkAddInGuest(pkgArgs: List<String>): Int = withContext(Dispatchers.IO) {
        if (!prootBinary.exists()) {
            Log.w(TAG, "[ApkWorld] proot binary not available")
            return@withContext -1
        }
        val cmd = listOf(
            prootBinary.absolutePath, "-0", "--link2symlink", "--kill-on-exit",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root",
            "/bin/sh", "-c",
            // Absolute path: the guest PATH is only guaranteed inside a
            // session shell, not in proot children (see prootLoaderEnv).
            "/sbin/apk add --no-cache ${pkgArgs.joinToString(" ")}"
        )
        val loaderEnv = prootLoaderEnv()
        runCatching {
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply { environment().putAll(loaderEnv) }
                .start()
            val output = p.inputStream.readBytes().toString(Charset.forName("UTF-8"))
            val finished = p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
            val code = if (finished) p.exitValue() else -1
            if (p.isAlive) p.destroyForcibly()
            Log.i(TAG, "[ApkWorld] apk add exit=$code pkgs=${pkgArgs.size} output=${output.takeLast(400)}")
            code
        }.onFailure { t ->
            Log.e(TAG, "[ApkWorld] apk add process failed", t)
            -1
        }.getOrDefault(-1)
    }

    /**
     * Reset rootfs to factory state: delete everything and reinstall.
     *
     * [M-rootfs-drop-backup] Backup / restore ("Reset & Backup") was removed
     * deliberately — real persistent data lives in the cross-session shared
     * area (/var/minis/shared) outside the rootfs, so backing up the in-container
     * /root backed up a disposable temp layer for zero practical value. The old
     * back up path also crashed on dangling runtime links (pulse runtime
     * socket under .config/pulse) inside /root via copyRecursively.
     * Reset is now a pure delete + reinstall; there is nothing to restore.
     */
    /**
     * Instance shim kept for the instrumented tests (which call
     * manager.extractTar); delegates to the top-level function.
     */
    internal fun extractTar(input: InputStream, targetDir: File) {
        extractTar(input, targetDir, onlyPrefixes = null)
    }

    suspend fun reset(): Unit = withContext(Dispatchers.IO) {
        // [Refactor-apk-world] Snapshot user packages BEFORE the wipe — the
        // boot path also dumps, but a manual reset can happen mid-session
        // between boots, so dump here too as the authoritative last state.
        dumpApkWorld()
        // [T-rootfs-event-log] Manual resets come from the Rootfs management
        // screen; log them so a wipe is always attributable to a human action
        // vs an automatic repair.
        RootfsEventLog.logEvent(eventLogsDir, RootfsEventLog.Events.MANUAL_RESET)
        rootfsDir.deleteRecursively()
        installIfNeeded()
    }

    /**
     * Calculate the total size of the rootfs directory in bytes.
     */
    suspend fun getRootfsSize(): Long = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) return@withContext 0L
        RootfsUsageScanner.scan(rootfsDir, RootfsUsageScanner.androidStat()).totalBytes
    }

    /**
     * Ensure session-specific directories exist on the host filesystem.
     */
    fun ensureSessionDirs(sessionId: String) {
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        val subdirs = listOf("attachments", "offloads", "workspace", "browser")
        for (subdir in subdirs) {
            File(sessionBase, subdir).mkdirs()
        }
    }

    /**
     * Read system DNS servers and search domains from ConnectivityManager,
     * then write resolv.conf into the Alpine rootfs.
     * Mirrors iOS ISHKernel.configureDns / refreshDns.
     * Falls back to 8.8.8.8 / 8.8.4.4 if no system DNS available.
     */
    fun refreshDns() {
        if (!rootfsDir.exists()) return

        val resolvConf = StringBuilder()

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps: LinkProperties? = if (network != null) cm.getLinkProperties(network) else null

            if (linkProps != null) {
                // Search domains
                val domains = linkProps.domains
                if (!domains.isNullOrBlank()) {
                    resolvConf.append("search $domains\n")
                    Log.i(TAG, "[DNS] search domains: $domains")
                }

                // Nameservers
                val dnsServers = linkProps.dnsServers
                if (dnsServers.isNotEmpty()) {
                    for (server in dnsServers) {
                        val addr = server.hostAddress ?: continue
                        resolvConf.append("nameserver $addr\n")
                        Log.i(TAG, "[DNS] system server: $addr")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DNS] Failed to read system DNS: ${e.message}")
        }

        // Fallback to public DNS if no system servers were found
        if (!resolvConf.contains("nameserver")) {
            Log.i(TAG, "[DNS] no system DNS — using fallback: 8.8.8.8, 8.8.4.4")
            resolvConf.append("nameserver 8.8.8.8\n")
            resolvConf.append("nameserver 8.8.4.4\n")
        }

        try {
            val file = File(rootfsDir, "etc/resolv.conf")
            file.parentFile?.mkdirs()
            file.writeText(resolvConf.toString())
            Log.i(TAG, "[DNS] resolv.conf updated:\n$resolvConf")
        } catch (e: Exception) {
            Log.e(TAG, "[DNS] Failed to write resolv.conf: ${e.message}")
        }
    }

    /**
     * Copy every file under `assets/default_mount/` into the rootfs, preserving
     * the directory layout. Mirrors iOS RootfsManager.applyDefaultMountOverlay
     * (src/ios/iSH/RootfsManager.swift:153-225) — runs on every boot so shipping
     * updated profile scripts / URL wrappers with an app release just works.
     *
     * Files under `bin` / `sbin` paths get the execute bit set. Existing files
     * are overwritten so users see the latest shipped version even if they
     * previously edited the file — matching iOS behavior.
     *
     * No-op when the asset dir is missing or the rootfs hasn't been extracted.
     */
    suspend fun applyDefaultMountOverlay() = withContext(Dispatchers.IO) {
        if (!rootfsDir.exists()) {
            Log.d(TAG, "[DefaultMount] rootfs missing, skipping overlay")
            return@withContext
        }

        // AssetManager.list() returns an empty array for both "leaf file" and
        // "missing path", so probe for children before recursing — otherwise
        // a missing overlay dir would be mistaken for a single leaf file.
        val rootEntries = try {
            context.assets.list(DEFAULT_MOUNT_ASSET)
        } catch (t: Throwable) {
            null
        }
        if (rootEntries.isNullOrEmpty()) {
            Log.d(TAG, "[DefaultMount] no default_mount assets found, skipping overlay")
            return@withContext
        }

        val startNs = System.nanoTime()
        var fileCount = 0
        try {
            fileCount = copyAssetDir(DEFAULT_MOUNT_ASSET, rootfsDir)
        } catch (t: Throwable) {
            Log.w(TAG, "[DefaultMount] overlay failed: ${t.message}", t)
            return@withContext
        }

        // Mirror iOS removeExternallyManagedMarker() — drop PEP 668 marker so
        // `pip install` Just Works inside this embedded Alpine rootfs even
        // when the shipped pip.conf isn't being read (e.g. pip invoked with
        // --isolated or via a venv). Safe: this is a single-tenant sandbox.
        val markerRemoved = removeExternallyManagedMarker()

        // [T-mcp-cli-readonly-android] Make the shipped minis-mcp-cli Python lib
        // read-only inside the guest so a user can't `vi`-tamper the bundled
        // scripts (mirrors iOS #707). Scoped to /usr/local/lib/minis-mcp-cli/
        // ONLY — the wrapper at /usr/local/bin/minis-mcp-cli stays executable +
        // writable (app-managed). Re-applied on every boot AFTER the copy; the
        // copyAssetDir leaf-copy above re-opens read-only files writable first,
        // so the next app-upgrade overlay still overwrites cleanly.
        val lockedCount = lockMcpCliLibReadOnly()

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        Log.i(TAG, "[DefaultMount] Done. $fileCount file(s) overlaid, $markerRemoved EXTERNALLY-MANAGED marker(s) removed, $lockedCount minis-mcp-cli lib path(s) locked read-only in %.1fms".format(elapsedMs))
    }

    /**
     * [T-mcp-cli-readonly-android] Set the `/usr/local/lib/minis-mcp-cli/`
     * subtree read-only for the guest: directories 0555 (read+execute, no
     * write), files 0444 (read-only). Java's File API has no octal chmod, so
     * we use setWritable(false, false) + setReadable(true, false)
     * (+ setExecutable(true, false) on dirs) — the `false` ownerOnly arg makes
     * the permission apply to all users, matching the setReadable style used
     * for libtalloc above. Returns the number of paths adjusted; 0 if the lib
     * dir is absent. Idempotent — the pre-copy unlock in copyAssetDir keeps the
     * upgrade path working across boots.
     */
    private fun lockMcpCliLibReadOnly(): Int {
        val libDir = File(rootfsDir, "usr/local/lib/minis-mcp-cli")
        if (!libDir.isDirectory) return 0
        var count = 0
        // walkBottomUp so child files are locked before their parent dir loses
        // write — dir mode doesn't gate chmod of already-visited children, but
        // bottom-up keeps the intent clear and avoids any traversal surprise.
        for (f in libDir.walkBottomUp()) {
            f.setWritable(false, false)
            f.setReadable(true, false)
            if (f.isDirectory) f.setExecutable(true, false)
            count++
        }
        return count
    }

    /**
     * Remove the PEP 668 EXTERNALLY-MANAGED marker file from every
     * `usr/lib/python3*` directory in the rootfs. Mirrors iOS
     * RootfsManager.removeExternallyManagedMarker (src/ios/iSH/RootfsManager.swift:228-239).
     * Returns the number of markers removed.
     */
    private fun removeExternallyManagedMarker(): Int {
        val usrLib = File(rootfsDir, "usr/lib")
        val children = usrLib.listFiles() ?: return 0
        var removed = 0
        for (entry in children) {
            if (!entry.name.startsWith("python3")) continue
            val marker = File(entry, "EXTERNALLY-MANAGED")
            if (marker.exists() && marker.delete()) {
                Log.i(TAG, "[DefaultMount] Removed EXTERNALLY-MANAGED from ${entry.name}")
                removed++
            }
        }
        return removed
    }

    /**
     * Recursively copy an assets path into [targetBase]. Returns the number of
     * regular files written. Files under /bin/, /sbin/, /usr/bin/, /usr/sbin/,
     * /usr/local/bin/, /usr/local/sbin/ get the execute bit set.
     */
    private fun copyAssetDir(assetPath: String, targetBase: File, prefix: String = ""): Int {
        val entries = context.assets.list(assetPath) ?: return 0
        if (entries.isEmpty()) {
            // Leaf: asset is a file. Copy it.
            val dest = File(targetBase, prefix)
            dest.parentFile?.mkdirs()
            // [T-mcp-cli-readonly-android] A prior boot may have set this file
            // (and its dir) read-only — the minis-mcp-cli lib subtree, locked
            // below. Re-open both writable before overwriting, otherwise an app
            // upgrade can't replace the shipped file: truncating an existing
            // file needs write on the FILE, and creating a new one needs write
            // on the PARENT DIR. No-op when already writable / not yet present.
            dest.parentFile?.setWritable(true, true)
            if (dest.exists()) dest.setWritable(true, true)
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            if (isUnderBinDir(prefix)) {
                dest.setExecutable(true, false)
            }
            return 1
        }
        var count = 0
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val childPrefix = if (prefix.isEmpty()) name else "$prefix/$name"
            count += copyAssetDir(childAsset, targetBase, childPrefix)
        }
        return count
    }

    private fun isUnderBinDir(relativePath: String): Boolean {
        val normalized = "/$relativePath"
        return BIN_DIR_PREFIXES.any { normalized.startsWith(it) }
    }

    // --- POSIX tar extraction ---


    /**
     * InputStream wrapper that reports cumulative bytes read as a 0..1 fraction.
     * Throttles updates so each +1% bump emits at most once. Designed to wrap
     * the asset stream (not the gzip stream) so progress is monotonic against
     * a size we can cheaply know up front.
     */
    private class ProgressInputStream(
        inner: InputStream,
        private val total: Long,
        private val onProgress: (Float) -> Unit,
    ) : FilterInputStream(inner) {
        private var read: Long = 0
        private var lastReportedPercent: Int = -1

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { read += 1; publish() }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { read += n; publish() }
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) { read += skipped; publish() }
            return skipped
        }

        private fun publish() {
            if (total <= 0) return
            val pct = ((read * 100) / total).toInt().coerceIn(0, 99)
            if (pct != lastReportedPercent) {
                lastReportedPercent = pct
                onProgress(pct / 100f)
            }
        }
    }

    companion object {
        private const val TAG = "RootfsManager"
        private const val ARCH = "aarch64"
        private const val ROOTFS_ASSET = "alpine-minirootfs.tar.gz"
        private const val ROOTFS_ASSET_TAR = "alpine-minirootfs.tar"
        /**
         * Worst-case expansion factor for the compressed rootfs asset.
         * Alpine minirootfs compresses to roughly 1/3–1/4 of its extracted
         * size, so `compressed × ROOTFS_EXPANSION_FACTOR` is a conservative
         * estimate of the space extraction will actually need.
         */
        private const val ROOTFS_EXPANSION_FACTOR = 4L
        /**
         * Extra margin (MiB) kept above the estimated extracted size so a
         * half-full disk still has room for the apk world snapshot, integrity
         * manifest and the first boot's package operations.
         */
        private const val ROOTFS_SPACE_MARGIN_MB = 64L

        /**
         * Conservative disk-space gate for rootfs installation.
         *
         * Returns true when `usableBytes` can hold the estimated extracted
         * rootfs (compressed asset size × [ROOTFS_EXPANSION_FACTOR]) plus a
         * fixed [ROOTFS_SPACE_MARGIN_MB] margin. Pure so the threshold can be
         * JVM-tested without an Android Context ([RootfsDiskGuardTest]).
         */
        internal fun hasEnoughSpaceForRootfs(usableBytes: Long, compressedAssetBytes: Long): Boolean {
            if (compressedAssetBytes < 0 || usableBytes < 0) return false
            val needed = compressedAssetBytes * ROOTFS_EXPANSION_FACTOR + ROOTFS_SPACE_MARGIN_MB * 1024L * 1024L
            return usableBytes >= needed
        }
        private const val PROOT_ASSET = "proot-aarch64"
        private const val DEFAULT_MOUNT_ASSET = "default_mount"
        private val OFFLINE_PACKAGES = listOf(
            "bash-5.2.37-r0.apk",
            "readline-8.2.13-r0.apk",
            "ncurses-6.5_p20241006-r3.apk",
        )

        /**
         * Parse the line-based integrity manifest text ("rel/path=size" per
         * line) into a map. Malformed lines are skipped; an empty or
         * unparseable manifest yields an empty map, which `verifyIntegrity`
         * treats as "no size expectations" (backward compat).
         *
         * Extracted as a pure function so the parsing contract is JVM-testable
         * without an Android Context ([RootfsHealthTest]).
         */
        internal fun parseIntegrityManifest(text: String): Map<String, Long> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyMap()
            return trimmed.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && '=' in it }
                .mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) return@mapNotNull null
                    val key = line.substring(0, eq)
                    val value = line.substring(eq + 1).toLongOrNull() ?: return@mapNotNull null
                    key to value
                }
                .toMap()
        }

        /**
         * Rootfs paths whose contents must be executable. Matches iOS
         * RootfsManager.swift:199 byte-for-byte so the same overlay tree
         * produces identical file modes on both platforms.
         */
        private val BIN_DIR_PREFIXES = listOf(
            "/bin/",
            "/sbin/",
            "/usr/bin/",
            "/usr/sbin/",
            "/usr/local/bin/",
            "/usr/local/sbin/",
        )

        @Volatile
        private var instance: RootfsManager? = null

        fun getInstance(context: Context): RootfsManager {
            return instance ?: synchronized(this) {
                instance ?: RootfsManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
/**
 * Extract a POSIX tar stream into the target directory.
 * files, directories, and symlinks.
 *
 * When [onlyPrefixes] is non-null, only entries whose full path
 * starts with one of the prefixes are materialized; every other
 * entry is still consumed (data + padding) so the stream stays
 * aligned for the next header. Used by the targeted-restore
 * repair path to recover factory files without wiping
 * user-installed packages.
 * Does not depend on any external library.
 */
internal fun extractTar(
    input: InputStream,
    targetDir: File,
    onlyPrefixes: Set<String>? = null,
) {
    val header = ByteArray(512)

    while (true) {
        val bytesRead = readFully(input, header)
        if (bytesRead < 512) break

        // Check for end-of-archive (two consecutive zero blocks)
        if (header.all { it == 0.toByte() }) break

        val name = extractString(header, 0, 100)
        val modeOctal = extractString(header, 100, 8)
        val sizeOctal = extractString(header, 124, 12)
        val typeFlag = header[156].toInt().toChar()
        val linkName = extractString(header, 157, 100)
        val mode = modeOctal.trim().toIntOrNull(8) ?: 0

        // Handle GNU/POSIX long names via prefix field (bytes 345-500)
        val prefix = extractString(header, 345, 155)
        val rawName = if (prefix.isNotEmpty()) "$prefix/$name" else name
        // GNU/bsd tar archives commonly prefix entries with "./" — normalize
        // it away so onlyPrefixes matching and extraction paths are
        // consistent with the on-disk rootfs layout (bin/bash, sbin/apk, ...).
        var fullName = rawName
        while (fullName.startsWith("./")) fullName = fullName.removePrefix("./")

        val size = sizeOctal.trim().toLongOrNull(8) ?: 0L

        // A "./" root-directory entry (typeflag 5, size 0) normalizes to an
        // empty path. It is a real archive entry (the root dir marker of GNU/
        // bsd tars), NOT the end-of-archive marker — that is a zero-filled
        // block already handled above. Skip it and keep extracting; aborting
        // here used to yield an empty rootfs on every install/reset/repair
        // once an archive's first entry was "./" (every real minirootfs).
        if (fullName.isEmpty()) {
            if (size > 0) {
                val blocks = (size + 511) / 512 * 512
                skipFully(input, blocks)
            }
            continue
        }
        val outFile = File(targetDir, fullName)
        val isTarget = onlyPrefixes == null || onlyPrefixes.any { fullName.startsWith(it) }

        when (typeFlag) {
            '5', 'D' -> {
                // Directory
                if (isTarget) outFile.mkdirs()
            }
            '2' -> {
                // Symbolic link
                if (isTarget) {
                    outFile.parentFile?.mkdirs()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName)
                        )
                    } catch (_: Exception) {
                        // Symlinks may fail on some Android versions; skip
                        Log.w("RootfsManager", "Failed to create symlink: $fullName -> $linkName")
                    }
                }
            }
            '0', '\u0000' -> {
                if (isTarget) {
                    // Regular file (type '0' or null/legacy)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // Preserve executable permission from tar header
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                } else {
                    // Filtered out: consume data so the stream stays aligned
                    skipFully(input, size)
                }
                // Skip padding to next 512-byte boundary (both branches)
                val remainder = (size % 512).toInt()
                if (remainder != 0) {
                    skipFully(input, (512 - remainder).toLong())
                }
                continue // Already consumed data + padding
            }
            '1' -> {
                // Hard link — create a copy
                if (isTarget) {
                    outFile.parentFile?.mkdirs()
                    val linkTarget = File(targetDir, linkName)
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }
            }
            else -> {
                // Unknown type, skip data
            }
        }
        // Skip data blocks for non-file entries that we didn't consume above
        if (typeFlag != '0' && typeFlag != '\u0000' && size > 0) {
            val blocks = (size + 511) / 512 * 512
            skipFully(input, blocks)
        }
    }
}

internal fun extractString(header: ByteArray, offset: Int, length: Int): String {
    val end = minOf(offset + length, header.size)
    var actualEnd = offset
    for (i in offset until end) {
        if (header[i] == 0.toByte()) break
        actualEnd = i + 1
    }
    return String(header, offset, actualEnd - offset, Charset.forName("UTF-8"))
}

internal fun readFully(input: InputStream, buf: ByteArray): Int {
    var offset = 0
    while (offset < buf.size) {
        val n = input.read(buf, offset, buf.size - offset)
        if (n < 0) return offset
        offset += n
    }
    return offset
}

internal fun skipFully(input: InputStream, count: Long) {
    var remaining = count
    val buf = ByteArray(8192)
    while (remaining > 0) {
        val toRead = minOf(buf.size.toLong(), remaining).toInt()
        val n = input.read(buf, 0, toRead)
        if (n < 0) break
        remaining -= n
    }
}


/**
 * Paths managed by apk (installed/upgraded at runtime, or grown by it —
 * the apk database). Their on-disk size legitimately differs from the
 * factory snapshot after any package change, so [verifyIntegrity] must only
 * check existence for them. Asserting the factory size made a freshly
 * `apk add`-ed bash (size != 0) look "missing" and a grown apk db look
 * "unusable" — which pushed autoRepair into a full-reset loop on EVERY boot
 * (2026-08-13). `/bin/sh` is a symlink to `/bin/busybox`, so it inherits
 * busybox's runtime size changes too — it was the last dynamic path still
 * size-asserted, causing a false `missing=[/bin/sh]` on every boot after a
 * busybox upgrade (fixed 2026-08-15).
 */
internal val DYNAMIC_INTEGRITY_PATHS = setOf(
    "bin/bash",
    "bin/sh",
    "usr/lib/libreadline.so.8",
    "usr/lib/libncursesw.so.6",
    "lib/apk/db/installed",
)

/**
 * Decide whether a path passes the manifest size check. Dynamic
 * (apk-managed) paths and paths absent from the manifest are existence-only;
 * everything else must match the factory snapshot size (catches truncation).
 * Pure so the boot contract is JVM-testable without an Android Context
 * ([RootfsHealthTest]).
 */
internal fun integritySizePasses(
    rel: String,
    actualSize: Long,
    expectedSizes: Map<String, Long>,
): Boolean {
    if (rel in DYNAMIC_INTEGRITY_PATHS) return true
    val expected = expectedSizes[rel] ?: return true
    return actualSize == expected
}

/**
 * Factory files that are safe to restore over a damaged rootfs without
 * wiping user-installed packages. Prefix-matched so symlink chains
 * (e.g. libreadline.so.8 -> libreadline.so.8.x) are restored together with
 * their targets. The apk database (lib/apk/db/installed) is deliberately
 * excluded — it holds user package records and can only be rebuilt by a
 * full reset.
 */
internal val CRITICAL_RESTORE_PREFIXES = setOf(
    "bin/bash",
    "bin/sh",
    "bin/busybox",
    "lib/ld-musl-",
    "usr/lib/libreadline",
    "usr/lib/libncurses",
    "sbin/apk",
)

internal fun ensureBusyboxShellSymlink(rootfsDir: File): Boolean {
    val binDir = File(rootfsDir, "bin")
    val shell = File(binDir, "sh").toPath()
    val busybox = File(binDir, "busybox")
    if (java.nio.file.Files.exists(shell)) return true
    if (!busybox.exists()) return false
    return try {
        binDir.mkdirs()
        if (java.nio.file.Files.exists(shell, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            java.nio.file.Files.delete(shell)
        }
        java.nio.file.Files.createSymbolicLink(shell, java.nio.file.Paths.get("busybox"))
        java.nio.file.Files.exists(shell)
    } catch (t: Exception) {
        Log.w("RootfsManager", "Failed to rebuild bin/sh -> busybox", t)
        false
    }
}

// ── Apk world snapshot — pure functions (JVM-testable) ─────────────────

/**
 * Package names guaranteed by the offline-install path
 * (assets/apk-offline/ via [RootfsManager.installOfflinePackages],
 * autoRepair Stage 2.6). The snapshot restore skips them: they are
 * re-installed from bundled APK files on every rebuild anyway.
 * File-level (private top-level) so [excludeOfflinePackages] can use it
 * without an Android Context.
 */
private val OFFLINE_PACKAGE_NAMES = setOf("bash", "readline", "ncurses")

/**
 * The Alpine guest PATH, matching what PRootKernel sets for session shells.
 * proot child processes inherit the app process env (Android PATH:
 * /sbin:/vendor/bin:/system/sbin:...) — without an explicit override,
 * `/bin/sh -c "apk ..."` fails with `apk: not found` (exit 127).
 */
private const val ALPINE_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin"

/**
 * A package name + version pair as recorded in Alpine's apk database.
 * Canonical form for `apk add <name>=<version>`.
 */
data class ApkPackage(val name: String, val version: String)

/**
 * Parse Alpine's `/lib/apk/db/installed` (read host-side — no proot needed)
 * into ordered (name, version) pairs. Each package is a block of
 * `KEY:value` lines terminated by a blank line; the name lives under `P:`,
 * the version under `V:` (apk writes P before V in every block). Blocks
 * missing either field are skipped; a trailing block without a closing
 * blank line is still captured.
 */
internal fun parseApkDbInstalled(text: String): List<ApkPackage> {
    val result = mutableListOf<ApkPackage>()
    var name: String? = null
    var version: String? = null
    fun flush() {
        val n = name
        val v = version
        if (n != null && v != null) result.add(ApkPackage(n, v))
        name = null
        version = null
    }
    for (line in text.lines()) {
        val t = line.trim()
        if (t.isEmpty()) {
            flush()
            continue
        }
        when {
            t.startsWith("P:") -> name = t.substring(2).trim()
            t.startsWith("V:") -> version = t.substring(2).trim()
        }
    }
    flush()
    return result
}

/**
 * Serialize packages as one `name=version` line each, with a header
 * comment (parseable back by [parseApkWorld]).
 */
internal fun formatApkWorld(packages: List<ApkPackage>): String =
    buildString {
        appendLine("# apk-world snapshot — `<name>=<version>` per line, written by RootfsManager.dumpApkWorld()")
        for (p in packages) appendLine("${p.name}=${p.version}")
    }

/**
 * Parse an apk-world snapshot (or retry list) back into packages.
 * Tolerates blank lines, `#` comments, and malformed lines (skipped).
 */
internal fun parseApkWorld(text: String): List<ApkPackage> {
    val result = mutableListOf<ApkPackage>()
    for (line in text.lines()) {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) continue
        val eq = t.indexOf('=')
        if (eq <= 0) continue
        val name = t.substring(0, eq).trim()
        val version = t.substring(eq + 1).trim()
        if (name.isNotEmpty() && version.isNotEmpty()) {
            result.add(ApkPackage(name, version))
        }
    }
    return result
}

/**
 * Drop the offline trio (bash/readline/ncurses) from a snapshot before
 * restoring: [RootfsManager.installOfflinePackages] (autoRepair Stage 2.6)
 * re-installs them from bundled APK files on every rebuild — no network,
 * always the same version — so the snapshot restore must not touch them
 * (no duplicate work, no surprise version downgrades).
 */
internal fun excludeOfflinePackages(packages: List<ApkPackage>): List<ApkPackage> =
    packages.filterNot { it.name in OFFLINE_PACKAGE_NAMES }
