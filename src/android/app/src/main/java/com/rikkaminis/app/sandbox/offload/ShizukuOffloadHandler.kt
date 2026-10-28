package com.rikkaminis.app.sandbox.offload

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.offload.ShizukuManager
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import com.rikkaminis.app.sandbox.PRootKernel
import org.json.JSONArray
import org.json.JSONObject

/**
 * T322: `android-shizuku-cli` — full CLI surface from the design doc.
 *
 * Routes argv through [ShizukuManager.runProcess], which executes
 * privileged shell tools (`pm`, `am`, `cmd`, `settings`, `dumpsys`,
 * `input`, `wm`, `appops`) inside the Shizuku service's process (uid
 * 2000 with adb-startup, uid 0 with root-startup). This is exactly the
 * same surface area `adb shell` provides — so any subcommand a developer
 * could run from a USB-tethered laptop is now reachable from inside the
 * agent's sandboxed shell, without launching the device's settings UI.
 *
 * Subcommand groups (12, per design doc):
 *   package      list / info / install / uninstall / enable / disable / clear / path
 *   permission   list / grant / revoke / appops
 *   activity     start / force-stop / kill / broadcast / top
 *   display      list / set / reset
 *   settings     get / set / delete / list
 *   user         list / create / remove / switch / start / stop
 *   network      restrict / allow / stats
 *   input        tap / swipe / key / text
 *   notification list / dismiss / channel
 *   file         ls / pull / push / rm
 *   device       info / battery / usage
 *   service      status / ping
 *
 * All subcommands honor the project-wide `--compact` / `-q` / `--quiet`
 * flags via [OffloadOutput.formatBody], and `--format json|text|csv`
 * (text is the default; csv is wired only on `package list`).
 */

/**
 * [da1034-A2 ISSUE-04] Pure uid extraction from `dumpsys package <pkg>` output.
 *
 * Returns the first `userId=<digits>` capture, or **null** when the dump
 * carries none (unknown/uninstalled package, dumpsys failure). Callers must
 * treat null as a refusal: the old inline version fell back to `"0"`, which
 * aimed `cmd netpolicy` at uid 0 (root) for every unresolvable package and
 * made `network stats` report root's traffic as the package's.
 *
 * Top-level (not a private companion member) so the JVM unit test can pin it
 * without the handler's Android dependencies — same split as
 * `resolveMaybeRelative` in ConfigOffloadHandler.kt.
 */
internal fun extractUidFromDumpsys(dumpsysOutput: String): String? =
    Regex("""userId=(\d+)""").find(dumpsysOutput)?.groupValues?.get(1)

class ShizukuOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)
        // Top-level help / version short-circuits — no Shizuku dependency.
        if (argv.isEmpty() || argv[0] == "--help" || argv[0] == "-h" || argv[0] == "help") {
            return ok(HELP)
        }
        if (argv[0] == "--version") return ok("android-shizuku-cli 1.0 (T322)")

        // [deep-audit-1020 ISSUE-02] Read the group **by position**, not by
        // index. A leading option that belongs to the invocation (`--compact`,
        // `--format json`, …) used to become the group name, so
        // `android-shizuku-cli --compact package list` died as
        // `unknown subcommand '--compact'`. [splitShizukuArgv] returns the first
        // token the parser reads as a positional plus every other token in
        // order, so the group handler still sees the leading options through
        // [args] and `args.positional.first()` is its sub-command.
        val split = splitShizukuArgv(argv)
        val group = split.group ?: return NativeOffloadResult(
            2,
            "android-shizuku-cli: no subcommand given.\n" +
                "  - Run `android-shizuku-cli` with no args to see available subcommands.\n",
        )
        // [deep-audit-1017 ISSUE-08b / deep-audit-1020 ISSUE-02] Declare this
        // handler's value-less long options: OffloadArgs treats `--flag` as
        // boolean only when the caller declares it (a flag it doesn't know
        // greedily eats the next token as its value — the B26a failure mode
        // pinned by SessionsCliArgsTest). See [NO_VALUE_FLAGS]; the declaration
        // is per group because `--data` is read as a value by `activity`
        // ([noValueFlagsFor]).
        // [deep-audit-1029 ISSUE-07] For `exec` the parser gets only the CLI's
        // own tokens ([ShizukuArgv.execOptionTokens]): the shell payload is not
        // an option list, so `android-shizuku-cli exec grep -q foo /p` must not
        // hand `-q` to the output shaper — that read as `--quiet` and made
        // [OffloadOutput.formatBody] drop the whole `data` object (stdout /
        // stderr / exitCode) on a non-zero exit, the one thing `handleExec`
        // promises to keep. Every other group still parses the full argv minus
        // the group.
        val args = if (group == "exec") {
            OffloadArgs(
                split.execOptionTokens,
                booleanFlags = noValueFlagsFor(group),
                valueShortOptions = SHIZUKU_VALUE_SHORT_OPTIONS,
            )
        } else {
            OffloadArgs(
                split.withoutGroup,
                booleanFlags = noValueFlagsFor(group),
                valueShortOptions = SHIZUKU_VALUE_SHORT_OPTIONS,
            )
        }
        // `exec` joins the tokens *after* the group into one shell command, so a
        // leading option must never leak into the command string.
        val rest = if (group == "exec") split.trailing else split.withoutGroup

        // `service ping/status` reports state without requiring READY.
        if (group == "service") return handleService(rest, args)

        // T330: tri-state agent gate via OffloadPermissionManager. ASK_ONCE
        // pops a UI dialog and resumes when the user responds; "Always
        // Allow" upgrades the stored level to BYPASS for next time. See
        // OffloadGate for the sync→suspend rationale (handler interface
        // is sync, manager API is suspend).
        if (!OffloadGate.allow("shizuku_cli", "android-shizuku-cli", request)) {
            return errEnvelope(
                "PERMISSION_DENIED",
                "Agent is not allowed to use android-shizuku-cli. Open Settings → Permissions → Integrations to change.",
                args,
            )
        }

        // Every other group needs binder + permission.
        ShizukuManager.refresh()
        when (val st = ShizukuManager.snapshot.value.state) {
            ShizukuManager.State.NOT_INSTALLED ->
                return errEnvelope("SERVICE_NOT_RUNNING", "Shizuku is not installed. Open Settings → Permissions → Shizuku.", args)
            ShizukuManager.State.NOT_RUNNING ->
                return errEnvelope("SERVICE_NOT_RUNNING", "Shizuku service is not running. Start it via adb or root, then retry.", args)
            ShizukuManager.State.NEED_PERMISSION ->
                return errEnvelope("PERMISSION_DENIED", "RikkaMinis is not authorized for Shizuku. Grant permission in Settings → Permissions → Shizuku.", args)
            ShizukuManager.State.READY -> { /* fall through */ }
        }

        return try {
            when (group) {
                "help", "--help", "-h" -> ok(HELP)
                "package" -> handlePackage(rest, args, request)
                "permission" -> handlePermission(rest, args)
                "activity" -> handleActivity(rest, args)
                "display" -> handleDisplay(rest, args)
                "settings" -> handleSettings(rest, args)
                "user" -> handleUser(rest, args)
                "network" -> handleNetwork(rest, args)
                "input" -> handleInput(rest, args)
                "notification" -> handleNotification(rest, args)
                "file" -> handleFile(rest, args, request)
                "device" -> handleDevice(rest, args)
                "exec" -> handleExec(rest, args)
                else -> NativeOffloadResult(
                    2,
                    "android-shizuku-cli: unknown subcommand '$group'.\n" +
                        "  - Try `android-shizuku-cli exec $group ${rest.joinToString(" ")}`".trimEnd() + " to run as a raw shell command.\n" +
                        "  - Run `android-shizuku-cli` with no args to see available subcommands.\n",
                )
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "group=$group failed: ${t.message}")
            errEnvelope("OPERATION_FAILED", t.message ?: "unknown", args)
        }
    }

    // ─── Group: service ────────────────────────────────────────────────────
    private fun handleService(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: "status"
        val snap = ShizukuManager.snapshot.value
        return when (sub) {
            "status" -> {
                val data = JSONObject()
                    .put("state", snap.state.name)
                    .put("running", snap.state == ShizukuManager.State.READY ||
                        snap.state == ShizukuManager.State.NEED_PERMISSION)
                    .put("authorized", snap.state == ShizukuManager.State.READY)
                    .put("version", snap.version)
                    .put("uid", snap.uid)
                    .put("startup_type", when (snap.uid) { 0 -> "root"; 2000 -> "adb"; else -> "unknown" })
                okEnvelope(data, args)
            }
            "ping" -> {
                if (snap.state == ShizukuManager.State.READY) {
                    ok("OK Shizuku service is running (uid=${snap.uid}, version=${snap.version})\n")
                } else {
                    NativeOffloadResult(1, "FAIL Shizuku service is not READY (state=${snap.state})\n")
                }
            }
            "help", "--help", "-h" -> ok(SERVICE_HELP)
            else -> NativeOffloadResult(2, "service: unknown subcommand '$sub'\n$SERVICE_HELP")
        }
    }

    // ─── Group: package ────────────────────────────────────────────────────
    private fun handlePackage(rest: List<String>, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(PACKAGE_HELP)
        return when (sub) {
            "list" -> packageList(args)
            "info" -> packageInfo(args.positional.getOrNull(1), args)
            "install" -> packageInstall(args.positional.getOrNull(1), args, request)
            "uninstall" -> packageUninstall(args.positional.getOrNull(1), args)
            "enable" -> packageEnableDisable(args.positional.getOrNull(1), args, enable = true)
            "disable" -> packageEnableDisable(args.positional.getOrNull(1), args, enable = false)
            "clear" -> packageClear(args.positional.getOrNull(1), args)
            "path" -> packagePath(args.positional.getOrNull(1), args)
            "help", "--help", "-h" -> ok(PACKAGE_HELP)
            else -> NativeOffloadResult(2, "package: unknown subcommand '$sub'\n$PACKAGE_HELP")
        }
    }

    private fun packageList(args: OffloadArgs): NativeOffloadResult {
        val cmd = mutableListOf("pm", "list", "packages")
        if (args.hasFlag("system")) cmd.add("-s")
        if (args.hasFlag("third-party", "3")) cmd.add("-3")
        if (args.hasFlag("disabled")) cmd.add("-d")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        args.get("filter")?.let { cmd.add(it) }
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val pkgs = r.stdout.lineSequence()
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()
        if (args.get("format") == "json") {
            val arr = JSONArray()
            for (p in pkgs) arr.put(JSONObject().put("packageName", p))
            return okEnvelope(arr, args)
        }
        if (args.get("format") == "csv") {
            val csv = "packageName\n" + pkgs.joinToString("\n")
            return ok(csv + "\n")
        }
        return ok(pkgs.joinToString("\n") + if (pkgs.isEmpty()) "" else "\n")
    }

    private fun packageInfo(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package info <packageName>", args)
        val cmd = mutableListOf("dumpsys", "package", pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("PACKAGE_NOT_FOUND", r.combined, args)
        val d = parseDumpsysPackage(r.stdout, pkg)
        return okEnvelope(d, args)
    }

    private fun packageInstall(apk: String?, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        if (apk.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package install <apkPath>", args)
        // [da1034-A2 ISSUE-03] `pm install` runs outside the sandbox, so a
        // guest path (the only kind the agent can produce) must be translated
        // first — the raw spelling used to fail with "file not found" even
        // though the APK was sitting right there in /var/minis/workspace.
        val apkHost = shizukuHostPath(apk, request)
            ?: return errEnvelope(
                "PATH_NOT_RESOLVED",
                "package install: '$apk' does not resolve inside this session's sandbox mounts. " +
                    "Copy the APK into the sandbox (e.g. /var/minis/workspace/) and pass that path; relative paths resolve against the shell cwd.",
                args,
            )
        val cmd = mutableListOf("pm", "install")
        if (args.hasFlag("replace", "r")) cmd.add("-r") else cmd.add("-r")  // default -r
        if (args.hasFlag("downgrade")) cmd.add("-d")
        if (args.hasFlag("grant-permissions")) cmd.add("-g")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(apkHost)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 60_000)
        return if (r.exitCode == 0 && r.stdout.contains("Success")) {
            okEnvelope(JSONObject().put("installed", apk), args)
        } else {
            errEnvelope("OPERATION_FAILED", r.combined.ifBlank { "install failed" }, args)
        }
    }

    private fun packageUninstall(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package uninstall <packageName>", args)
        val cmd = mutableListOf("pm", "uninstall")
        if (args.hasFlag("keep-data", "k")) cmd.add("-k")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 30_000)
        return if (r.exitCode == 0 && r.stdout.contains("Success")) {
            okEnvelope(JSONObject().put("uninstalled", pkg), args)
        } else {
            errEnvelope("OPERATION_FAILED", r.combined.ifBlank { "uninstall failed" }, args)
        }
    }

    private fun packageEnableDisable(pkg: String?, args: OffloadArgs, enable: Boolean): NativeOffloadResult {
        if (pkg.isNullOrBlank()) {
            return errEnvelope("INVALID_ARGS", "package ${if (enable) "enable" else "disable"} <packageName>", args)
        }
        val verb = if (enable) "enable" else "disable-user"
        val cmd = mutableListOf("pm", verb)
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("package", pkg).put("state", if (enable) "enabled" else "disabled"), args)
        } else {
            errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
    }

    private fun packageClear(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package clear <packageName>", args)
        val cacheOnly = args.hasFlag("cache-only")
        val cmd = buildPackageClearCommand(pkg, cacheOnly, args.get("user"))
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 15_000)
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("cleared", pkg).put("scope", clearScope(cacheOnly)), args)
        } else {
            errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
    }

    private fun packagePath(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "package path <packageName>", args)
        val r = ShizukuManager.runProcess(arrayOf("pm", "path", pkg))
        if (r.exitCode != 0) return errEnvelope("PACKAGE_NOT_FOUND", r.combined, args)
        val paths = r.stdout.lineSequence()
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()
        return if (args.get("format") == "json") okEnvelope(JSONArray(paths), args)
        else ok(paths.joinToString("\n") + "\n")
    }

    // ─── Group: permission ────────────────────────────────────────────────
    private fun handlePermission(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(PERMISSION_HELP)
        return when (sub) {
            "list" -> permissionList(args.positional.getOrNull(1), args)
            "grant" -> permissionGrantRevoke(args.positional.getOrNull(1), args.positional.getOrNull(2), args, grant = true)
            "revoke" -> permissionGrantRevoke(args.positional.getOrNull(1), args.positional.getOrNull(2), args, grant = false)
            "appops" -> permissionAppops(args.positional.drop(1), args)
            "help", "--help", "-h" -> ok(PERMISSION_HELP)
            else -> NativeOffloadResult(2, "permission: unknown subcommand '$sub'\n$PERMISSION_HELP")
        }
    }

    private fun permissionList(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "permission list <packageName>", args)
        val cmd = mutableListOf("dumpsys", "package", pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("PACKAGE_NOT_FOUND", r.combined, args)
        val perms = parseDumpsysPermissions(r.stdout)
        val onlyGranted = args.hasFlag("granted")
        val onlyDenied = args.hasFlag("denied")
        val onlyDangerous = args.hasFlag("dangerous")
        val filtered = perms.filter { p ->
            (!onlyGranted || p.granted) &&
                (!onlyDenied || !p.granted) &&
                (!onlyDangerous || p.dangerous)
        }
        if (args.get("format") == "json") {
            val arr = JSONArray()
            for (p in filtered) {
                arr.put(JSONObject()
                    .put("name", p.name)
                    .put("granted", p.granted)
                    .put("dangerous", p.dangerous))
            }
            return okEnvelope(arr, args)
        }
        val text = filtered.joinToString("\n") { "${if (it.granted) "✓" else "✗"} ${it.name}" }
        return ok(text + if (text.isNotEmpty()) "\n" else "")
    }

    private fun permissionGrantRevoke(pkg: String?, perm: String?, args: OffloadArgs, grant: Boolean): NativeOffloadResult {
        if (pkg.isNullOrBlank() || perm.isNullOrBlank()) {
            return errEnvelope("INVALID_ARGS", "permission ${if (grant) "grant" else "revoke"} <pkg> <permission>", args)
        }
        val cmd = mutableListOf("pm", if (grant) "grant" else "revoke")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg); cmd.add(perm)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("package", pkg).put("permission", perm).put("granted", grant), args)
        } else {
            errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
    }

    private fun permissionAppops(pos: List<String>, args: OffloadArgs): NativeOffloadResult {
        if (pos.size < 3) return errEnvelope("INVALID_ARGS", "permission appops <pkg> <op> <mode>", args)
        val (pkg, op, mode) = Triple(pos[0], pos[1], pos[2])
        val r = ShizukuManager.runProcess(arrayOf("appops", "set", pkg, op, mode))
        return if (r.exitCode == 0) {
            okEnvelope(JSONObject().put("package", pkg).put("op", op).put("mode", mode), args)
        } else {
            errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
    }

    // ─── Group: activity ──────────────────────────────────────────────────
    private fun handleActivity(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(ACTIVITY_HELP)
        return when (sub) {
            "start" -> activityStart(args)
            "force-stop" -> activityForceStop(args.positional.getOrNull(1), args)
            "kill" -> activityKill(args.positional.getOrNull(1), args)
            "broadcast" -> activityBroadcast(args.positional.getOrNull(1), args)
            "top" -> activityTop(args)
            "help", "--help", "-h" -> ok(ACTIVITY_HELP)
            else -> NativeOffloadResult(2, "activity: unknown subcommand '$sub'\n$ACTIVITY_HELP")
        }
    }

    private fun activityStart(args: OffloadArgs): NativeOffloadResult {
        val cmd = mutableListOf("am", "start", "-W")
        args.get("action", "a")?.let { cmd.addAll(listOf("-a", it)) }
        args.get("data", "d")?.let { cmd.addAll(listOf("-d", it)) }
        args.get("package", "p")?.let { cmd.addAll(listOf("-p", it)) }
        args.get("component", "c")?.let { cmd.addAll(listOf("-n", it)) }
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        // Extras: --extra-string key=value (parser surfaces as values)
        for ((k, v) in extras(args, "extra-string")) {
            cmd.addAll(listOf("--es", k, v))
        }
        for ((k, v) in extras(args, "extra-int")) {
            cmd.addAll(listOf("--ei", k, v))
        }
        for ((k, v) in extras(args, "extra-bool")) {
            cmd.addAll(listOf("--ez", k, v))
        }
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 15_000)
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("status", "started").put("output", r.stdout), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityForceStop(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "activity force-stop <pkg>", args)
        val cmd = mutableListOf("am", "force-stop")
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        cmd.add(pkg)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("stopped", pkg), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityKill(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "activity kill <pkg>", args)
        val r = ShizukuManager.runProcess(arrayOf("am", "kill", pkg))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("killed", pkg), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityBroadcast(action: String?, args: OffloadArgs): NativeOffloadResult {
        if (action.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "activity broadcast <action>", args)
        val cmd = mutableListOf("am", "broadcast", "-a", action)
        args.get("package")?.let { cmd.addAll(listOf("-p", it)) }
        args.get("component")?.let { cmd.addAll(listOf("-n", it)) }
        args.get("user")?.let { cmd.addAll(listOf("--user", it)) }
        for ((k, v) in extras(args, "extra-string")) cmd.addAll(listOf("--es", k, v))
        for ((k, v) in extras(args, "extra-int")) cmd.addAll(listOf("--ei", k, v))
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("broadcast", action).put("output", r.stdout), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun activityTop(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "activity", "activities"), timeoutMs = 6_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        // Look for "topResumedActivity=ActivityRecord{... pkg/cls ...}"
        val regex = Regex("""topResumedActivity=ActivityRecord\{[^ ]+ \d+ ([^/]+)/([^ ]+)""")
        val m = regex.find(r.stdout)
        val obj = if (m != null) {
            JSONObject().put("packageName", m.groupValues[1]).put("activityName", m.groupValues[2])
        } else {
            JSONObject().put("packageName", "unknown").put("activityName", "unknown")
        }
        return okEnvelope(obj, args)
    }

    // ─── Group: display ───────────────────────────────────────────────────
    private fun handleDisplay(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(DISPLAY_HELP)
        return when (sub) {
            "list" -> displayList(args)
            "set" -> displaySet(args)
            "reset" -> displayReset(args)
            "help", "--help", "-h" -> ok(DISPLAY_HELP)
            else -> NativeOffloadResult(2, "display: unknown subcommand '$sub'\n$DISPLAY_HELP")
        }
    }

    private fun displayList(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "display"), timeoutMs = 6_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        // Parse "DisplayDeviceInfo" blocks; each contains "<id>" + width/height/density/refresh.
        val displays = JSONArray()
        val blockRegex = Regex("""DisplayDeviceInfo\{"[^"]*"[^}]*\}""")
        for (block in blockRegex.findAll(r.stdout)) {
            val text = block.value
            val w = Regex("""(\d+) x (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val h = Regex("""(\d+) x (\d+)""").find(text)?.groupValues?.get(2)?.toIntOrNull()
            val dpi = Regex("""density (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val rate = Regex("""(\d+(?:\.\d+)?) fps""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val rotation = Regex("""rotation (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            displays.put(JSONObject()
                .put("width", w ?: -1)
                .put("height", h ?: -1)
                .put("density", dpi ?: -1)
                .put("refreshRate", rate ?: -1.0)
                .put("rotation", rotation ?: 0))
        }
        return okEnvelope(displays, args)
    }

    private fun displaySet(args: OffloadArgs): NativeOffloadResult {
        // [meta1028-B05 F-09] Decide BEFORE touching `wm`. The old body ran
        // whatever it could parse and answered `{"ok":true,"data":{}}` for the
        // rest, so three different misspellings all read as a successful
        // override: a bare `display set`, `--density abc` (getInt → null → the
        // call was skipped), and `--width 1080` alone (`wm size` needs WxH, the
        // half-specified pair was dropped). A no-op reported as success is worse
        // than a refusal — the caller re-reads the resolution believing it
        // changed. [planDisplaySet] holds the whole decision so it is testable
        // off-device.
        val plan = planDisplaySet(args)
        if (plan is DisplaySetPlan.Refused) {
            return errEnvelope("INVALID_ARGS", "${plan.message}\n$DISPLAY_HELP", args)
        }
        plan as DisplaySetPlan.Ready
        var ok = true
        val results = JSONObject()
        plan.density?.let {
            val r = ShizukuManager.runProcess(arrayOf("wm", "density", it.toString()))
            results.put("density", r.exitCode == 0)
            ok = ok && r.exitCode == 0
        }
        val w = plan.width; val h = plan.height
        if (w != null && h != null) {
            val r = ShizukuManager.runProcess(arrayOf("wm", "size", "${w}x${h}"))
            results.put("size", r.exitCode == 0)
            ok = ok && r.exitCode == 0
        }
        return if (ok) okEnvelope(results, args)
        else errEnvelope("OPERATION_FAILED", "one or more `wm` calls failed: $results", args)
    }

    private fun displayReset(args: OffloadArgs): NativeOffloadResult {
        val r1 = ShizukuManager.runProcess(arrayOf("wm", "size", "reset"))
        val r2 = ShizukuManager.runProcess(arrayOf("wm", "density", "reset"))
        return if (r1.exitCode == 0 && r2.exitCode == 0) okEnvelope(JSONObject().put("reset", true), args)
        else errEnvelope("OPERATION_FAILED", "size:${r1.combined} density:${r2.combined}", args)
    }

    // ─── Group: settings ──────────────────────────────────────────────────
    private fun handleSettings(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(SETTINGS_HELP)
        val ns = args.positional.getOrNull(1)
        return when (sub) {
            "get" -> settingsGet(ns, args.positional.getOrNull(2), args)
            "set" -> settingsSet(ns, args.positional.getOrNull(2), args.positional.getOrNull(3), args)
            "delete" -> settingsDelete(ns, args.positional.getOrNull(2), args)
            "list" -> settingsList(ns, args)
            "help", "--help", "-h" -> ok(SETTINGS_HELP)
            else -> NativeOffloadResult(2, "settings: unknown subcommand '$sub'\n$SETTINGS_HELP")
        }
    }

    private fun validNs(ns: String?): Boolean = ns in listOf("global", "secure", "system")

    private fun settingsGet(ns: String?, key: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns) || key.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "settings get <global|secure|system> <key>", args)
        val r = ShizukuManager.runProcess(arrayOf("settings", "get", ns!!, key))
        // T339: `settings get` returns exitCode=1 with empty stdout/stderr when the key
        // is missing from the namespace database. Don't pass an empty message through —
        // synthesize a useful one so callers see what went wrong.
        if (r.exitCode == 0) return okEnvelope(JSONObject().put("key", key).put("value", r.stdout.trim()), args)
        // T343: branch the synthesized hint by exitCode — `settings` returns 1
        // for missing keys, 124 for our local timeout, 143 when something
        // SIGTERM'd the child, anything else is a generic privileged-shell
        // failure.
        val hint = r.combined.ifBlank {
            when (r.exitCode) {
                1 -> "settings $ns key='$key' does not exist or is not readable (exitCode=1). " +
                    "Try `android-shizuku-cli settings list $ns` to see available keys."
                124 -> "settings get $ns $key timed out (exitCode=124); raise --timeout-ms or check Shizuku service health."
                143 -> "settings get $ns $key was killed mid-flight (SIGTERM/exitCode=143); " +
                    "the privileged process was destroyed before it could finish."
                else -> "settings get $ns $key failed (exitCode=${r.exitCode})"
            }
        }
        return errEnvelope("OPERATION_FAILED", hint, args)
    }

    private fun settingsSet(ns: String?, key: String?, value: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns) || key.isNullOrBlank() || value == null) {
            return errEnvelope("INVALID_ARGS", "settings set <ns> <key> <value>", args)
        }
        val r = ShizukuManager.runProcess(arrayOf("settings", "put", ns!!, key, value))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("key", key).put("value", value), args)
        else errEnvelope(
            "OPERATION_FAILED",
            r.combined.ifBlank { "settings put $ns $key failed (exitCode=${r.exitCode})" },
            args,
        )
    }

    private fun settingsDelete(ns: String?, key: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns) || key.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "settings delete <ns> <key>", args)
        val r = ShizukuManager.runProcess(arrayOf("settings", "delete", ns!!, key))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("deleted", key), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r, "settings delete $ns $key"), args)
    }

    private fun settingsList(ns: String?, args: OffloadArgs): NativeOffloadResult {
        if (!validNs(ns)) return errEnvelope("INVALID_ARGS", "settings list <global|secure|system>", args)
        val r = ShizukuManager.runProcess(arrayOf("settings", "list", ns!!), timeoutMs = 8_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r, "settings list $ns"), args)
        val filter = args.get("filter")
        val obj = JSONObject()
        for (line in r.stdout.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq); val v = line.substring(eq + 1)
            if (filter != null && !k.contains(filter)) continue
            obj.put(k, v)
        }
        return okEnvelope(obj, args)
    }

    // ─── Group: user ──────────────────────────────────────────────────────
    private fun handleUser(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(USER_HELP)
        return when (sub) {
            "list" -> userList(args)
            "create" -> userCreate(args.positional.getOrNull(1), args)
            "remove" -> userRemove(args.positional.getOrNull(1), args)
            "switch" -> userSwitch(args.positional.getOrNull(1), args)
            "start" -> userStartStop(args.positional.getOrNull(1), args, start = true)
            "stop" -> userStartStop(args.positional.getOrNull(1), args, start = false)
            "help", "--help", "-h" -> ok(USER_HELP)
            else -> NativeOffloadResult(2, "user: unknown subcommand '$sub'\n$USER_HELP")
        }
    }

    private fun userList(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("pm", "list", "users"))
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val users = JSONArray()
        // Format: "UserInfo{<id>:<name>:<flags>} running"
        val regex = Regex("""UserInfo\{(\d+):([^:}]*):([\da-fA-Fx]+)\}\s*(running)?""")
        for (m in regex.findAll(r.stdout)) {
            users.put(JSONObject()
                .put("userId", m.groupValues[1].toIntOrNull() ?: -1)
                .put("name", m.groupValues[2])
                .put("flags", m.groupValues[3])
                .put("isRunning", m.groupValues[4] == "running"))
        }
        return okEnvelope(users, args)
    }

    private fun userCreate(name: String?, args: OffloadArgs): NativeOffloadResult {
        if (name.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user create <name>", args)
        val cmd = mutableListOf("pm", "create-user")
        if (args.hasFlag("managed-profile")) cmd.add("--profileOf")
        if (args.hasFlag("guest")) cmd.add("--guest")
        cmd.add(name)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("created", r.stdout.trim()), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun userRemove(id: String?, args: OffloadArgs): NativeOffloadResult {
        if (id.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user remove <userId>", args)
        val r = ShizukuManager.runProcess(arrayOf("pm", "remove-user", id))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("removed", id), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun userSwitch(id: String?, args: OffloadArgs): NativeOffloadResult {
        if (id.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user switch <userId>", args)
        val r = ShizukuManager.runProcess(arrayOf("am", "switch-user", id))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("switched", id), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun userStartStop(id: String?, args: OffloadArgs, start: Boolean): NativeOffloadResult {
        if (id.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "user ${if (start) "start" else "stop"} <userId>", args)
        val r = ShizukuManager.runProcess(arrayOf("am", if (start) "start-user" else "stop-user", id))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put(if (start) "started" else "stopped", id), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    // ─── Group: network ───────────────────────────────────────────────────
    private fun handleNetwork(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(NETWORK_HELP)
        return when (sub) {
            "restrict" -> networkRestrict(args.positional.getOrNull(1), args, restrict = true)
            "allow" -> networkRestrict(args.positional.getOrNull(1), args, restrict = false)
            "stats" -> networkStats(args.positional.getOrNull(1), args)
            "help", "--help", "-h" -> ok(NETWORK_HELP)
            else -> NativeOffloadResult(2, "network: unknown subcommand '$sub'\n$NETWORK_HELP")
        }
    }

    private fun networkRestrict(pkg: String?, args: OffloadArgs, restrict: Boolean): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "network ${if (restrict) "restrict" else "allow"} <pkg>", args)
        // [da1034-A2 ISSUE-04] Resolve the uid ONCE, before any arm runs, and
        // fail closed when it cannot be resolved. The old `?: "0"` fallback
        // (see pkgUid) silently aimed `cmd netpolicy` at uid 0 — i.e. at root —
        // for unknown packages, so "restrict <typo>" reported every arm as
        // success while changing root's policy.
        val uid = pkgUid(pkg)
            ?: return errEnvelope(
                "OPERATION_FAILED",
                "network ${if (restrict) "restrict" else "allow"}: could not resolve a uid for '$pkg' (is it installed?)",
                args,
            )
        val results = JSONObject()
        val all = args.hasFlag("all")
        if (args.hasFlag("background") || all) {
            // RUN_ANY_IN_BACKGROUND appop
            val mode = if (restrict) "ignore" else "allow"
            val r = ShizukuManager.runProcess(arrayOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", mode))
            results.put("background", r.exitCode == 0)
        }
        if (args.hasFlag("wifi") || all) {
            // cmd netpolicy is the modern surface; older devices use `cmd netpolicy set restrict-background`
            // [da1034-A2 ISSUE-04] `restrict-background-whitelist` EXEMPTS a uid
            // from the background restriction, so restricting must REMOVE from
            // the exemption list and allowing must ADD to it. The directions
            // were inverted: `network restrict --wifi` whitelisted the package
            // (keeping it online) and `network allow --wifi` blacklisted it.
            val r = ShizukuManager.runProcess(arrayOf("cmd", "netpolicy",
                if (restrict) "remove" else "add", "restrict-background-whitelist", uid))
            results.put("wifi", r.exitCode == 0)
        }
        if (args.hasFlag("data") || all) {
            val r = ShizukuManager.runProcess(arrayOf("cmd", "netpolicy",
                if (restrict) "add" else "remove", "restrict-background-blacklist", uid))
            results.put("data", r.exitCode == 0)
        }
        return okEnvelope(results, args)
    }

    private fun networkStats(pkg: String?, args: OffloadArgs): NativeOffloadResult {
        if (pkg.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "network stats <pkg>", args)
        // dumpsys netstats detail; surface the per-uid block.
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "netstats", "detail"), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        // [da1034-A2 ISSUE-04] `?: -1` used to print uid -1 with a 0/0 reading
        // as if it were real data for the package; refuse instead. (Root's
        // traffic was never reportable either — the old `?: "0"` in pkgUid
        // made every unknown package "uid 0".)
        val uid = pkgUid(pkg)?.toIntOrNull()
            ?: return errEnvelope(
                "OPERATION_FAILED",
                "network stats: could not resolve a uid for '$pkg' (is it installed?)",
                args,
            )
        val (rx, tx) = parseNetstats(r.stdout, uid)
        val obj = JSONObject().put("package", pkg).put("uid", uid).put("rx_bytes", rx).put("tx_bytes", tx)
        return okEnvelope(obj, args)
    }

    private fun pkgUid(pkg: String): String? {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "package", pkg), timeoutMs = 6_000)
        return extractUidFromDumpsys(r.stdout)
    }

    // ─── Group: input ─────────────────────────────────────────────────────
    private fun handleInput(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(INPUT_HELP)
        return when (sub) {
            "tap" -> inputTap(args.positional.getOrNull(1), args.positional.getOrNull(2), args)
            "swipe" -> inputSwipe(args.positional.drop(1), args)
            "key" -> inputKey(args.positional.getOrNull(1), args)
            "text" -> inputText(args.positional.drop(1).joinToString(" "), args)
            "help", "--help", "-h" -> ok(INPUT_HELP)
            else -> NativeOffloadResult(2, "input: unknown subcommand '$sub'\n$INPUT_HELP")
        }
    }

    private fun inputTap(x: String?, y: String?, args: OffloadArgs): NativeOffloadResult {
        if (x.isNullOrBlank() || y.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "input tap <x> <y>", args)
        val r = ShizukuManager.runProcess(arrayOf("input", "tap", x, y))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("tap", "$x,$y"), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun inputSwipe(coords: List<String>, args: OffloadArgs): NativeOffloadResult {
        if (coords.size < 4) return errEnvelope("INVALID_ARGS", "input swipe <x1> <y1> <x2> <y2>", args)
        val cmd = mutableListOf("input", "swipe", coords[0], coords[1], coords[2], coords[3])
        args.get("duration")?.let { cmd.add(it) }
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("swipe", coords.take(4)), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun inputKey(key: String?, args: OffloadArgs): NativeOffloadResult {
        if (key.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "input key <keycode>", args)
        val cmd = mutableListOf("input", "keyevent")
        if (args.hasFlag("long-press")) cmd.add("--longpress")
        cmd.add(key)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("key", key), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun inputText(text: String, args: OffloadArgs): NativeOffloadResult {
        if (text.isBlank()) return errEnvelope("INVALID_ARGS", "input text <text>", args)
        val safe = encodeInputText(text)
        val r = ShizukuManager.runProcess(arrayOf("input", "text", safe))
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("text", text), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    // ─── Group: notification ──────────────────────────────────────────────
    private fun handleNotification(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(NOTIFICATION_HELP)
        return when (sub) {
            "list" -> notificationList(args)
            "dismiss" -> notificationDismiss(args)
            "channel" -> notificationChannel(args.positional.drop(1), args)
            "help", "--help", "-h" -> ok(NOTIFICATION_HELP)
            else -> NativeOffloadResult(2, "notification: unknown subcommand '$sub'\n$NOTIFICATION_HELP")
        }
    }

    private fun notificationList(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "notification", "--noredact"), timeoutMs = 8_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val arr = JSONArray()
        val pkgFilter = args.get("package")
        // Each NotificationRecord block starts with "NotificationRecord(...)"
        val regex = Regex("""NotificationRecord\(.*?pkg=([^\s]+).*?id=(\d+).*?tag=([^\s]+)?""")
        for (m in regex.findAll(r.stdout)) {
            val pkg = m.groupValues[1]
            if (pkgFilter != null && pkg != pkgFilter) continue
            arr.put(JSONObject()
                .put("packageName", pkg)
                .put("id", m.groupValues[2].toIntOrNull() ?: -1)
                .put("tag", m.groupValues[3].ifBlank { null }))
        }
        return okEnvelope(arr, args)
    }

    private fun notificationDismiss(args: OffloadArgs): NativeOffloadResult {
        if (args.hasFlag("all")) {
            val r = ShizukuManager.runProcess(arrayOf("cmd", "notification", "cancel_all"))
            return if (r.exitCode == 0) okEnvelope(JSONObject().put("cancelled", "all"), args)
            else errEnvelope("OPERATION_FAILED", failMessage(r), args)
        }
        val pkg = args.get("package")
        val id = args.get("id")
        return when {
            pkg != null -> {
                val r = ShizukuManager.runProcess(arrayOf("cmd", "notification", "cancel", pkg))
                if (r.exitCode == 0) okEnvelope(JSONObject().put("cancelled", pkg), args)
                else errEnvelope("OPERATION_FAILED", failMessage(r), args)
            }
            id != null -> {
                val r = ShizukuManager.runProcess(arrayOf("cmd", "notification", "cancel", id))
                if (r.exitCode == 0) okEnvelope(JSONObject().put("cancelled_id", id), args)
                else errEnvelope("OPERATION_FAILED", failMessage(r), args)
            }
            else -> errEnvelope("INVALID_ARGS", "notification dismiss --all | --package <pkg> | --id <id>", args)
        }
    }

    /**
     * [deep-audit-1017 ISSUE-08b] `pos` is the **parsed** positional list
     * (`args.positional.drop(1)`, the convention `permission appops` / `input
     * swipe` already use), not the raw argv tail.
     *
     * Reading raw argv meant a flag that precedes the package mis-placed every
     * argument: `notification channel set --importance 3 com.pkg chid` took
     * `--importance` as the package and `3` as the channel id, and
     * `… set --block com.pkg chid` took `--block` as the package — the latter
     * then reached `cmd notification` as a privileged call with a garbage
     * target. With the parsed list the sub-command, package and channel id are
     * taken from the same tokens the flags were stripped out of.
     */
    private fun notificationChannel(pos: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = pos.firstOrNull() ?: return errEnvelope("INVALID_ARGS", "notification channel list <pkg> | set <pkg> <channelId> [--block|--unblock|--importance N]", args)
        return when (sub) {
            "list" -> {
                val pkg = pos.getOrNull(1) ?: return errEnvelope("INVALID_ARGS", "notification channel list <pkg>", args)
                val r = ShizukuManager.runProcess(arrayOf("dumpsys", "notification"), timeoutMs = 8_000)
                if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
                val arr = JSONArray()
                // ponytail: pkg is validated but not used to filter the dump |
                // 天花板: this regex targets `id=/name=/importance=` while the
                // device emits `mId=/mName=/mImportance=` (probe/pre/p4b), so the
                // list comes back empty regardless of the filter | 升级触发:
                // deep-audit-1017 FIX-E report §同族观察 (dispatched as own item)
                val regex = Regex("""NotificationChannel\{.*?id=([^,]+).*?name=([^,]+).*?importance=(\d+)""")
                for (m in regex.findAll(r.stdout)) {
                    arr.put(JSONObject()
                        .put("id", m.groupValues[1])
                        .put("name", m.groupValues[2])
                        .put("importance", m.groupValues[3].toIntOrNull() ?: -1))
                }
                okEnvelope(arr, args)
            }
            "set" -> {
                val pkg = pos.getOrNull(1)
                val ch = pos.getOrNull(2)
                if (pkg == null || ch == null) return errEnvelope("INVALID_ARGS", "notification channel set <pkg> <channelId>", args)
                val cmd = mutableListOf("cmd", "notification")
                when {
                    args.hasFlag("block") -> { cmd.addAll(listOf("set_bubbles", pkg, "0")) }  // best-effort
                    args.hasFlag("unblock") -> { cmd.addAll(listOf("set_bubbles", pkg, "1")) }
                    else -> {
                        val imp = args.get("importance") ?: return errEnvelope("INVALID_ARGS", "channel set requires --block/--unblock/--importance", args)
                        cmd.addAll(listOf("set_channel_importance", pkg, ch, imp))
                    }
                }
                val r = ShizukuManager.runProcess(cmd.toTypedArray())
                if (r.exitCode == 0) okEnvelope(JSONObject().put("channel", ch).put("package", pkg), args)
                else errEnvelope("OPERATION_FAILED", failMessage(r), args)
            }
            else -> errEnvelope("INVALID_ARGS", "notification channel list|set", args)
        }
    }

    // ─── Group: file ──────────────────────────────────────────────────────
    private fun handleFile(rest: List<String>, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(FILE_HELP)
        return when (sub) {
            "ls" -> fileLs(args.positional.getOrNull(1), args, request)
            "pull" -> filePullPush(args.positional.getOrNull(1), args.positional.getOrNull(2), args, request, pull = true)
            "push" -> filePullPush(args.positional.getOrNull(1), args.positional.getOrNull(2), args, request, pull = false)
            "rm" -> fileRm(args.positional.getOrNull(1), args, request)
            "help", "--help", "-h" -> ok(FILE_HELP)
            else -> NativeOffloadResult(2, "file: unknown subcommand '$sub'\n$FILE_HELP")
        }
    }

    /**
     * [da1034-A2 ISSUE-03] Guest → host path translation for the `file` group.
     *
     * The agent lives inside the PRoot sandbox: paths it writes are GUEST
     * paths (`/var/minis/workspace/x.apk`, `shared/../report.md`, …). The
     * Shizuku service runs OUTSIDE the sandbox on the real filesystem, so
     * handing the guest spelling straight to `cp`/`ls`/`rm` used to make every
     * sandbox path a silent no-op (host `/var/minis/...` does not exist)
     * while host-only paths worked — the exact mirror image of the
     * [SpeechOffloadHandler.resolveSourcePath] bug class. Single source, no
     * second implementation: [resolveMaybeRelative] joins cwd + relative,
     * [PRootKernel.resolvePathForSession] maps the guest path to the session's
     * host tree and refuses anything outside the sandbox's mounts. Return null
     * = fail closed (PATH_NOT_RESOLVED); there is deliberately no
     * "pass the raw string through" fallback, same as [PhotosOffloadHandler]'s
     * "host paths are refused instead of being read straight off the host".
     */
    private fun shizukuHostPath(raw: String, request: NativeOffloadRequest): String? {
        val guestPath = resolveMaybeRelative(raw.trim(), request.cwd)
        return try {
            PRootKernel.resolvePathForSession(request.sessionId, guestPath, context)?.absolutePath
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "shizukuHostPath('$raw') failed: ${t.message}")
            null
        }
    }

    private fun fileLs(path: String?, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        if (path.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "file ls <path>", args)
        val host = shizukuHostPath(path, request)
            ?: return errEnvelope(
                "PATH_NOT_RESOLVED",
                "file ls: '$path' does not resolve inside this session's sandbox mounts. " +
                    "Paths are sandbox paths (e.g. /var/minis/workspace/<file>); relative paths resolve against the shell cwd.",
                args,
            )
        val cmd = mutableListOf("ls")
        if (args.hasFlag("long", "l")) cmd.add("-la") else cmd.add("-1")
        if (args.hasFlag("recursive", "r")) cmd.add("-R")
        cmd.add(host)
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 8_000)
        return if (r.exitCode == 0) ok(r.stdout + "\n")
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun filePullPush(
        src: String?,
        dst: String?,
        args: OffloadArgs,
        request: NativeOffloadRequest,
        pull: Boolean,
    ): NativeOffloadResult {
        if (src.isNullOrBlank() || dst.isNullOrBlank()) {
            return errEnvelope("INVALID_ARGS", "file ${if (pull) "pull" else "push"} <src> <dst>", args)
        }
        // [da1034-A2 ISSUE-03] Both operands are guest paths; translate each
        // through the session resolver before it reaches the privileged `cp`.
        val srcHost = shizukuHostPath(src, request)
            ?: return errEnvelope(
                "PATH_NOT_RESOLVED",
                "file ${if (pull) "pull" else "push"}: src '$src' does not resolve inside this session's sandbox mounts.",
                args,
            )
        val dstHost = shizukuHostPath(dst, request)
            ?: return errEnvelope(
                "PATH_NOT_RESOLVED",
                "file ${if (pull) "pull" else "push"}: dst '$dst' does not resolve inside this session's sandbox mounts.",
                args,
            )
        // Use cp via Shizuku — works for both directions because the
        // Shizuku service has full FS access, while our own uid usually
        // does not for /Android/data/* paths.
        val r = ShizukuManager.runProcess(arrayOf("cp", "-r", srcHost, dstHost), timeoutMs = 30_000)
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("src", src).put("dst", dst), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    private fun fileRm(path: String?, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        if (path.isNullOrBlank()) return errEnvelope("INVALID_ARGS", "file rm <path>", args)
        val host = shizukuHostPath(path, request)
            ?: return errEnvelope(
                "PATH_NOT_RESOLVED",
                "file rm: '$path' does not resolve inside this session's sandbox mounts.",
                args,
            )
        val cmd = mutableListOf("rm")
        if (args.hasFlag("recursive", "r")) cmd.add("-rf") else cmd.add("-f")
        cmd.add(host)
        val r = ShizukuManager.runProcess(cmd.toTypedArray())
        return if (r.exitCode == 0) okEnvelope(JSONObject().put("removed", path), args)
        else errEnvelope("OPERATION_FAILED", failMessage(r), args)
    }

    // ─── Group: device ────────────────────────────────────────────────────
    private fun handleDevice(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        val sub = args.positional.firstOrNull() ?: return ok(DEVICE_HELP)
        return when (sub) {
            "info" -> deviceInfo(args)
            "battery" -> deviceBattery(args)
            "usage" -> deviceUsage(args)
            "help", "--help", "-h" -> ok(DEVICE_HELP)
            else -> NativeOffloadResult(2, "device: unknown subcommand '$sub'\n$DEVICE_HELP")
        }
    }

    private fun deviceInfo(args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject()
            .put("brand", android.os.Build.BRAND)
            .put("model", android.os.Build.MODEL)
            .put("manufacturer", android.os.Build.MANUFACTURER)
            .put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("sdkInt", android.os.Build.VERSION.SDK_INT)
            .put("buildId", android.os.Build.ID)
        return okEnvelope(obj, args)
    }

    private fun deviceBattery(args: OffloadArgs): NativeOffloadResult {
        val r = ShizukuManager.runProcess(arrayOf("dumpsys", "battery"), timeoutMs = 5_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        val obj = JSONObject()
        for (line in r.stdout.lineSequence()) {
            val t = line.trim()
            val colon = t.indexOf(':')
            if (colon <= 0) continue
            val k = t.substring(0, colon).trim()
            val v = t.substring(colon + 1).trim()
            obj.put(k.replace(' ', '_').lowercase(), v)
        }
        return okEnvelope(obj, args)
    }

    private fun deviceUsage(args: OffloadArgs): NativeOffloadResult {
        val pkg = args.get("package")
        val cmd = mutableListOf("dumpsys", "usagestats")
        val r = ShizukuManager.runProcess(cmd.toTypedArray(), timeoutMs = 10_000)
        if (r.exitCode != 0) return errEnvelope("OPERATION_FAILED", failMessage(r), args)
        // Surface raw usagestats text — full structured parsing would dwarf this handler.
        val filtered = if (pkg != null) {
            r.stdout.lineSequence().filter { it.contains(pkg) }.joinToString("\n")
        } else {
            r.stdout
        }
        val top = args.getInt("top")
        val limited = if (top != null) filtered.lineSequence().take(top).joinToString("\n") else filtered
        return ok(limited + "\n")
    }

    // ─── Group: exec (raw shell passthrough) ───────────────────────────────
    /**
     * T341: wildcard fallback. Curated subcommands cover the well-known
     * surface, but LLM priors include a long tail of intuitive shell calls
     * (`pm list packages -f`, `cmd statusbar expand-notifications`, …) that
     * don't fit any group. `exec` joins remaining argv into a single shell
     * command string and runs it under Shizuku via `sh -c` so metacharacters
     * (`;`, `|`, `>`, backticks) keep their shell meaning. Caller is
     * responsible for quoting.
     */
    private fun handleExec(rest: List<String>, args: OffloadArgs): NativeOffloadResult {
        if (rest.isEmpty() || rest.firstOrNull() in listOf("help", "--help", "-h")) return ok(EXEC_HELP)
        val cmd = rest.joinToString(" ")
        val timeout = args.getInt("timeout-ms")?.toLong() ?: 30_000L
        val r = ShizukuManager.runProcess(arrayOf("sh", "-c", cmd), timeoutMs = timeout)
        val data = JSONObject()
            .put("command", cmd)
            .put("exitCode", r.exitCode)
            .put("stdout", r.stdout)
            .put("stderr", r.stderr)
            .put("combined", r.combined)
        return if (r.exitCode == 0) {
            okEnvelope(data, args)
        } else {
            // Surface non-zero exit with the same envelope shape so callers
            // get stdout/stderr even on failure — many shell tools (e.g.
            // `settings get` for an unknown key) exit non-zero with content.
            val obj = JSONObject().put("ok", false)
                .put("error", JSONObject()
                    .put("code", "OPERATION_FAILED")
                    .put("message", failMessage(r, "exec `$cmd`")))
                .put("data", data)
            val body = OffloadOutput.formatBody(obj.toString(2), args)
            NativeOffloadResult(r.exitCode, body + "\n")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private data class PermInfo(val name: String, val granted: Boolean, val dangerous: Boolean)

    private fun parseDumpsysPermissions(s: String): List<PermInfo> {
        val out = mutableListOf<PermInfo>()
        val lines = s.lineSequence()
        var inSection = false
        var dangerous = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("requested permissions:")) { inSection = true; dangerous = false; continue }
            if (line.startsWith("install permissions:")) { inSection = true; dangerous = false; continue }
            if (line.startsWith("runtime permissions:")) { inSection = true; dangerous = true; continue }
            if (line.isEmpty()) { inSection = false; continue }
            if (!inSection) continue
            // `android.permission.X: granted=true`
            val colon = line.indexOf(':')
            val name = if (colon > 0) line.substring(0, colon) else line
            val granted = line.contains("granted=true")
            // Plain requested permissions section has no granted= marker;
            // skip those (they're shadowed by install/runtime entries).
            if (line.contains("granted=") || dangerous) {
                out.add(PermInfo(name.trim(), granted, dangerous))
            }
        }
        return out.distinctBy { it.name }
    }

    private fun parseDumpsysPackage(s: String, pkg: String): JSONObject {
        val obj = JSONObject().put("packageName", pkg)
        val regexes = mapOf(
            "versionName" to Regex("""versionName=([^\s]+)"""),
            "versionCode" to Regex("""versionCode=(\d+)"""),
            "targetSdk" to Regex("""targetSdk=(\d+)"""),
            "minSdk" to Regex("""minSdk=(\d+)"""),
            "firstInstallTime" to Regex("""firstInstallTime=([^\n]+)"""),
            "lastUpdateTime" to Regex("""lastUpdateTime=([^\n]+)"""),
            "installerPackageName" to Regex("""installerPackageName=([^\s]+)"""),
            "dataDir" to Regex("""dataDir=([^\s]+)"""),
            "codePath" to Regex("""codePath=([^\s]+)"""),
        )
        for ((k, r) in regexes) {
            r.find(s)?.groupValues?.get(1)?.let { obj.put(k, it) }
        }
        return obj
    }

    private fun parseNetstats(dump: String, uid: Int): Pair<Long, Long> {
        if (uid < 0) return 0L to 0L
        var rx = 0L; var tx = 0L
        // dumpsys netstats detail prints per-uid blocks: "uid=10042 ... rb=12345 tb=6789"
        val regex = Regex("""uid=$uid\D[^\n]*rb=(\d+)[^\n]*tb=(\d+)""")
        for (m in regex.findAll(dump)) {
            rx += m.groupValues[1].toLongOrNull() ?: 0L
            tx += m.groupValues[2].toLongOrNull() ?: 0L
        }
        return rx to tx
    }

    /** Pull all `--<flag-prefix> key=value` repeated args. */
    private fun extras(@Suppress("UNUSED_PARAMETER") args: OffloadArgs, prefix: String): List<Pair<String, String>> {
        // OffloadArgs collapses repeated --key value into a single map entry.
        // For multiplexed extras, callers pass `--extra-string` once with a
        // semicolon-separated list: --extra-string foo=bar;baz=qux.
        val raw = args.get(prefix) ?: return emptyList()
        return raw.split(';').mapNotNull {
            val eq = it.indexOf('='); if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }
    }

    private fun ok(body: String) = NativeOffloadResult(0, body)

    private fun okEnvelope(payload: Any, args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject().put("ok", true).put("data", payload)
        val body = OffloadOutput.formatBody(obj.toString(2), args)
        return NativeOffloadResult(0, body + "\n")
    }

    /**
     * T339: many shell commands invoked via Shizuku exit non-zero with empty
     * stdout/stderr (e.g. `settings get` for an unknown key, `pm clear` on a
     * non-existent package). Wrap [r] so callers never surface a blank message
     * — the synthesized fallback keeps the original command context in view.
     */
    private fun failMessage(r: ShizukuManager.ProcessResult, context: String): String =
        r.combined.ifBlank { "$context failed (exitCode=${r.exitCode})" }

    /**
     * T343 overload: when no specific command context is available, still
     * synthesize a non-blank message keyed on exitCode so callers don't see
     * empty `OPERATION_FAILED` envelopes (the SIGTERM-143 + empty-output
     * combo was the surface symptom of the runProcess reflection bug).
     */
    private fun failMessage(r: ShizukuManager.ProcessResult): String =
        r.combined.ifBlank {
            when (r.exitCode) {
                124 -> "command timed out (exitCode=124); raise --timeout-ms or check Shizuku service health."
                143 -> "command was killed (SIGTERM/exitCode=143); privileged process destroyed mid-flight."
                else -> "command failed (exitCode=${r.exitCode})"
            }
        }

    private fun errEnvelope(code: String, message: String, args: OffloadArgs): NativeOffloadResult {
        val obj = JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", code).put("message", message),
        )
        val body = OffloadOutput.formatBody(obj.toString(2), args)
        AppLogger.warning(TAG, "$code: $message")
        return NativeOffloadResult(1, body + "\n")
    }

    companion object {
        private const val TAG = "ShizukuOffload"

        /**
         * [deep-audit-1017 ISSUE-08b] Long options this handler accepts that take
         * no value.
         *
         * [OffloadArgs] classifies `--flag` as boolean only when (a) the caller
         * declared it, (b) it is the last token, or (c) the next token starts
         * with `-`. An undeclared `--block com.pkg` therefore reads "com.pkg" as
         * the *value* of `--block` and silently drops a positional — and the raw
         * `rest` indexing that `notification channel` used then handed that
         * value straight to `cmd notification`. The parser is built once in
         * [handle], so the declaration lives here.
         *
         * [deep-audit-1020 ISSUE-02] Every long switch read through `hasFlag`
         * belongs in this set — 20 of them were missing, so `--system`,
         * `--cache-only`, `--grant-permissions`, `--recursive`, … silently ate
         * the token following them (`package clear --cache-only com.pkg` read
         * the package as the value of the switch and then reported "package
         * clear <packageName>" for a perfectly good command). The handler gate
         * (`scripts/scan/shizuku_args_check.py`) now cross-checks the set
         * against every `hasFlag("…")` long name in this file (that check
         * reads names of two characters or more, which is why the digit
         * spelling below is not part of it).
         *
         * Only pure switches belong here: `--importance` takes a value and must
         * stay out (declaring it would leave `3` as a stray positional).
         * `--data` is the one name read two ways — see [noValueFlagsFor], which
         * drops it for the group that reads it as a value.
         */
        internal val NO_VALUE_FLAGS = setOf(
            // permission appops / notification channel
            "block", "unblock",
            // package — `3` is the short spelling of `third-party`
            // (`pm list packages -3`); it is declared so the parser can tell
            // the SWITCH `-3` from a negative number used as a value
            // ([OffloadArgs], dv16-A A-02). Nothing else here is a digit.
            "system", "third-party", "3", "disabled",
            "replace", "downgrade", "grant-permissions", "keep-data", "cache-only",
            // permission
            "granted", "denied", "dangerous",
            // user
            "managed-profile", "guest",
            // network
            "all", "background", "wifi", "data",
            // input / file
            "long-press", "long", "recursive",
        )

        private const val HELP = """android-shizuku-cli — privileged Android system control via Shizuku.

Usage:
  android-shizuku-cli <group> <subcommand> [flags]
  android-shizuku-cli exec <any shell command>      ← fallback passthrough

Fallback:
  exec         Run an arbitrary shell command with Shizuku privilege.
               Use this when no curated subcommand fits — `exec` accepts
               any `adb shell`-style invocation (pm, am, cmd, dumpsys,
               settings, wm, ime, appops, input, …) and returns
               {stdout, stderr, exitCode}. Curated groups below are
               convenience wrappers around `exec` that return structured
               JSON for common operations.

Groups:
  package      list / info / install / uninstall / enable / disable / clear / path
  permission   list / grant / revoke / appops
  activity     start / force-stop / kill / broadcast / top
  display      list / set / reset
  settings     get / set / delete / list
  user         list / create / remove / switch / start / stop
  network      restrict / allow / stats
  input        tap / swipe / key / text
  notification list / dismiss / channel
  file         ls / pull / push / rm
  device       info / battery / usage
  service      status / ping

Common flags:
  --format json|text|csv     output format (default text)
  --compact                  single-line JSON
  -q, --quiet                strip envelope (data only on success, error on failure)
  --user <id>                target user ID
  --help                     group / subcommand help

Run `android-shizuku-cli <group> --help` for group-specific flags.
"""

        private const val EXEC_HELP = """exec — raw shell passthrough (T341).

Usage:
  android-shizuku-cli exec <shell command...>     [--timeout-ms N]

Runs the joined argv via `sh -c` under the Shizuku service uid (same
privilege as `adb shell`). Returns {ok, data:{command, exitCode, stdout,
stderr, combined}}. On non-zero exit the stdout/stderr are still
included in `data` so callers can diagnose without re-running.

Examples:
  android-shizuku-cli exec pm list packages -f
  android-shizuku-cli exec dumpsys battery
  android-shizuku-cli exec "settings get global airplane_mode_on"
  android-shizuku-cli exec "logcat -d -t 200 | grep MyTag"

Notes:
  - Caller owns quoting/escaping. Metacharacters (; | > backticks) work.
  - Default timeout 30s; override via `--timeout-ms N` (digits). That option
    belongs to `exec` itself and is lifted out of the command line.
  - Output-shaping flags go **before** the group —
    `android-shizuku-cli --compact exec pm list packages`. After `exec`,
    every token belongs to the shell command, so `exec grep -q foo` hands
    `-q` to grep rather than to this CLI.
  - Prefer curated subcommands when they exist — they emit structured JSON.
"""

        private const val SERVICE_HELP = """service — Shizuku runtime status.

Usage:
  android-shizuku-cli service status        State + version + uid
  android-shizuku-cli service ping          Quick connection check
"""

        private const val PACKAGE_HELP = """package — installed-app management.

Usage:
  android-shizuku-cli package list [--system|-3|--disabled] [--filter X]
  android-shizuku-cli package info <pkg>
  android-shizuku-cli package install <apkPath> [--grant-permissions] [--downgrade]
  android-shizuku-cli package uninstall <pkg> [--keep-data]
  android-shizuku-cli package enable <pkg>
  android-shizuku-cli package disable <pkg>
  android-shizuku-cli package clear <pkg> [--cache-only]
  android-shizuku-cli package path <pkg>
"""

        private const val PERMISSION_HELP = """permission — runtime + AppOps permissions.

Usage:
  android-shizuku-cli permission list <pkg> [--granted|--denied|--dangerous]
  android-shizuku-cli permission grant <pkg> <permission>
  android-shizuku-cli permission revoke <pkg> <permission>
  android-shizuku-cli permission appops <pkg> <op> <allow|deny|ignore|default>
"""

        private const val ACTIVITY_HELP = """activity — Activity / process management.

Usage:
  android-shizuku-cli activity start [-p pkg] [-c component] [-a action] [-d uri]
                               [--extra-string k=v;k=v] [--extra-int k=v]
  android-shizuku-cli activity force-stop <pkg>
  android-shizuku-cli activity kill <pkg>
  android-shizuku-cli activity broadcast <action> [--package pkg]
  android-shizuku-cli activity top
"""

        private const val DISPLAY_HELP = """display — display & resolution.

Usage:
  android-shizuku-cli display list
  android-shizuku-cli display set [--width N --height N] [--density DPI]
                                  (pass --density and/or the width+height PAIR; a size
                                   needs both, a value that is not a whole number in
                                   range is refused, and an empty `set` is an error —
                                   it never reports a change it did not make)
  android-shizuku-cli display reset
"""

        private const val SETTINGS_HELP = """settings — system Settings DB.

Usage:
  android-shizuku-cli settings get <global|secure|system> <key>
  android-shizuku-cli settings set <ns> <key> <value>
  android-shizuku-cli settings delete <ns> <key>
  android-shizuku-cli settings list <ns> [--filter X]
"""

        private const val USER_HELP = """user — multi-user management.

Usage:
  android-shizuku-cli user list
  android-shizuku-cli user create <name> [--managed-profile|--guest]
  android-shizuku-cli user remove <userId>
  android-shizuku-cli user switch <userId>
  android-shizuku-cli user start <userId>
  android-shizuku-cli user stop <userId>
"""

        private const val NETWORK_HELP = """network — net policy & stats.

Usage:
  android-shizuku-cli network restrict <pkg> [--background|--wifi|--data|--all]
  android-shizuku-cli network allow <pkg> [--background|--wifi|--data|--all]
  android-shizuku-cli network stats <pkg>
"""

        private const val INPUT_HELP = """input — input simulation.

Usage:
  android-shizuku-cli input tap <x> <y>
  android-shizuku-cli input swipe <x1> <y1> <x2> <y2> [--duration MS]
  android-shizuku-cli input key <KEYCODE> [--long-press]
  android-shizuku-cli input text <text>
"""

        private const val NOTIFICATION_HELP = """notification — system notifications.

Usage:
  android-shizuku-cli notification list [--package pkg]
  android-shizuku-cli notification dismiss [--all|--package pkg|--id N]
  android-shizuku-cli notification channel list <pkg>
  android-shizuku-cli notification channel set <pkg> <channelId>
                                         [--block|--unblock|--importance N]
"""

        private const val FILE_HELP = """file — privileged file access.

Usage:
  android-shizuku-cli file ls <path> [-l] [-r]
  android-shizuku-cli file pull <remote> <local>
  android-shizuku-cli file push <local> <remote>
  android-shizuku-cli file rm <path> [-r]
"""

        private const val DEVICE_HELP = """device — device state.

Usage:
  android-shizuku-cli device info
  android-shizuku-cli device battery
  android-shizuku-cli device usage [--package pkg] [--top N]
"""
    }
}

/**
 * [deep-audit-1017 ISSUE-08a] Encode a string for `input text`.
 *
 * Ground truth (`probe/pre/InputShellCommand.java`, AOSP android15-release —
 * `services/core/java/com/android/server/input/InputShellCommand.java:371-386`):
 * `sendText` rewrites the two-character sequence `%s` into a space and passes
 * every other `%` through untouched. `/system/bin/input` is `cmd input "$@"`,
 * so argv reaches that parser verbatim and the escape is the only way to
 * represent a space. The previous encoder emitted a bare `%`
 * (`replace(' ', '%')`), which the parser does not recognize: every space was
 * typed as a literal `%`.
 *
 * Single quotes are still stripped — pre-existing behavior, they are a
 * shell-quoting hazard for callers that route the text through `exec`.
 */
internal fun encodeInputText(text: String): String =
    text.replace(" ", "%s").replace("'", "")

/**
 * [deep-audit-1017 ISSUE-08c] argv for `package clear`.
 *
 * Ground truth: `pm help clear` on the target device (Android 15 / HyperOS 3)
 * documents `clear [--user USER_ID] [--cache-only] PACKAGE`, and AOSP
 * `PackageManagerShellCommand.runClear` (:2312-2358) routes `--cache-only` to
 * `deleteApplicationCacheFilesAsUser(pkg, userId, obs)` — a per-package cache
 * clear. The previous implementation ran the **global** `pm trim-caches 1G`
 * while still reporting `cleared=<pkg>`, i.e. it claimed a per-package clear it
 * never performed. Global trimming is still reachable via
 * `android-shizuku-cli exec 'pm trim-caches <space>'` — it just isn't what this
 * sub-command promises.
 *
 * Platform caveat (not ours): for a package that does not exist the platform
 * never fires the clear observer, so the call blocks until the caller's timeout
 * (`runProcess` -> `ProcessResult(124)`) instead of failing fast.
 */
internal fun buildPackageClearCommand(pkg: String, cacheOnly: Boolean, user: String?): List<String> {
    val cmd = mutableListOf("pm", "clear")
    if (cacheOnly) cmd.add("--cache-only")
    if (user != null) cmd.addAll(listOf("--user", user))
    cmd.add(pkg)
    return cmd
}

/** [deep-audit-1017 ISSUE-08c] What a successful `package clear` actually removed. */
internal fun clearScope(cacheOnly: Boolean): String = if (cacheOnly) "cache-only" else "data+cache"

/**
 * [deep-audit-1020 ISSUE-02] The value-less long options in force for one group.
 *
 * `data` is the single name this CLI reads two ways: `activity start` takes it
 * as a value (`--data <uri>`, read through [OffloadArgs.get] next to
 * `-a`/`-p`/`-c`), while `network restrict|allow` uses it as a switch
 * (NETWORK_HELP documents `--background|--wifi|--data|--all`). Declaring it
 * value-less for every group would turn the activity uri into a stray
 * positional and drop it silently, so the one group that reads it as a value
 * opts out. `--data=<uri>` keeps working under both readings — `--key=value`
 * is classified before the switch rule.
 */
/**
 * [dv15-A1 A-02] The single-dash options that take a value, declared once for
 * every group: `-a <intent>` / `-d <uri>` / `-p <package>` / `-c <component>`
 * ([ShizukuOffloadHandler.activityStart] forwards each to `am start`, spelled
 * with the same letters). Before this the parser read `-a foo` as the FLAG `a`
 * plus a stray positional `foo`, so `activity start -p com.x` launched nothing
 * and `activityStart` never called [OffloadArgs.get] for that key.
 *
 * The long spellings (`--action`, `--package`, …) are NOT here: they were
 * already value-looking by the `--key value` rule. None of these four is a
 * boolean flag in [ShizukuOffloadHandler.NO_VALUE_FLAGS], so the declared-flag
 * precedence in [OffloadArgs] leaves them free to take a value.
 */
internal val SHIZUKU_VALUE_SHORT_OPTIONS = setOf("a", "d", "p", "c")

internal fun noValueFlagsFor(group: String?): Set<String> =
    if (group == "activity") ShizukuOffloadHandler.NO_VALUE_FLAGS - "data"
    else ShizukuOffloadHandler.NO_VALUE_FLAGS

/** [splitShizukuArgv] result — see that function. */
internal data class ShizukuArgv(
    val group: String?,
    /** Every token except [group], in order — leading options included. */
    val withoutGroup: List<String>,
    /** The tokens after [group]; what `exec` joins into one shell command. */
    val trailing: List<String>,
    /**
     * [deep-audit-1029 ISSUE-07] The tokens of an `exec` invocation that belong
     * to **the CLI itself**: the leading options plus whatever option tokens
     * [stripExecTimeoutTokens] lifted out of [trailing]. For every other group
     * it is just the leading options.
     *
     * This is the set the parser is fed for `exec`, precisely so that a
     * sub-command token such as `grep -q foo` stays out of it: `-q` used to be
     * read as the output-shaping `--quiet` (which makes [OffloadOutput] strip
     * the whole `data` envelope on a non-zero exit). Defaults to the empty list
     * so a hand-built instance keeps compiling.
     */
    val execOptionTokens: List<String> = emptyList(),
)

/**
 * [deep-audit-1020 ISSUE-02] Top-level split of an `android-shizuku-cli` argv:
 * the group name is the first token the parser reads as a **positional**, so a
 * leading `--compact` / `--format json` / … stays an option instead of becoming
 * the group (`argv[0]` used to be read directly, which made
 * `android-shizuku-cli --compact package list` die as
 * `unknown subcommand '--compact'`).
 *
 * The option classification is delegated to [firstPositionalIndex] — the full
 * [ShizukuOffloadHandler.NO_VALUE_FLAGS] set (a leading option can belong to any
 * group's switch vocabulary and `--data` leading an invocation is not a real
 * shape) **and** the full [SHIZUKU_VALUE_SHORT_OPTIONS] set, for the mirror-image
 * reason: `-p com.example package info` carries all four value-taking shorts
 * before the group, so a scan that knows only the switches reads `com.example`
 * as the group ([dv18-A A-01a]).
 */
internal fun splitShizukuArgv(argv: List<String>): ShizukuArgv {
    val index = firstPositionalIndex(argv, ShizukuOffloadHandler.NO_VALUE_FLAGS, SHIZUKU_VALUE_SHORT_OPTIONS)
    if (index < 0) return ShizukuArgv(null, argv, emptyList(), execOptionTokens = argv)
    val group = argv[index]
    // [deep-audit-1029 ISSUE-07] `exec` owns one trailing option of its own
    // (`--timeout-ms N`): it is read by the parser and must not be joined into
    // the shell command. Every other token after the group is payload — a
    // sub-command's `-q` / `--quiet` included, which is exactly what the
    // top-level parser must never see.
    val leading = argv.take(index)
    val tail = argv.drop(index + 1)
    val (payload, execOptions) =
        if (group == EXEC_GROUP) stripExecTimeoutTokens(tail) else tail to emptyList()
    return ShizukuArgv(
        group = group,
        withoutGroup = argv.filterIndexed { i, _ -> i != index },
        trailing = payload,
        execOptionTokens = leading + execOptions,
    )
}

/** The group whose trailing tokens are a shell command, not parsed positionals. */
private const val EXEC_GROUP = "exec"

/** The one option `exec` reads itself; declared in `EXEC_HELP`. */
private const val EXEC_TIMEOUT_OPT = "--timeout-ms"

/**
 * [deep-audit-1029 ISSUE-07] Split `exec`'s trailing tokens into
 * (shell payload, option tokens the CLI owns).
 *
 * Only a **well-formed** `--timeout-ms <digits>` / `--timeout-ms=<digits>` is
 * claimed. A token that merely looks like the option but carries anything else
 * (`--timeout-ms foo`, a bare `--timeout-ms` at the end) stays in the payload,
 * so an over-eager strip cannot silently eat a sub-command argument: the shell
 * still sees it and fails loudly instead of running a mangled command.
 *
 * Known boundary (registered, not a goal): a sub-command that has its own
 * numeric `--timeout-ms N` cannot be reached through `exec` — write the option
 * as one shell word (`--timeout-ms\ N`) or use a curated subcommand.
 */
internal fun stripExecTimeoutTokens(tokens: List<String>): Pair<List<String>, List<String>> {
    val payload = mutableListOf<String>()
    val options = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        val next = tokens.getOrNull(i + 1)
        when {
            token.startsWith("$EXEC_TIMEOUT_OPT=") &&
                isExecTimeoutValue(token.substring(EXEC_TIMEOUT_OPT.length + 1)) -> {
                options.add(token)
                i += 1
            }
            token == EXEC_TIMEOUT_OPT && next != null && isExecTimeoutValue(next) -> {
                options.add(token)
                options.add(next)
                i += 2
            }
            else -> {
                payload.add(token)
                i += 1
            }
        }
    }
    return payload to options
}

/** A `--timeout-ms` value is digits only — `getInt` would read anything else as null. */
private fun isExecTimeoutValue(value: String): Boolean =
    value.isNotEmpty() && value.all { it in '0'..'9' }

/**
 * Index of the first token [OffloadArgs] reads as a positional, or -1 when
 * every token is an option.
 *
 * The classification is delegated to the parser itself, one prefix at a time,
 * instead of re-implemented here: a second copy of the "is this `--flag` a
 * switch or does it take the next token?" rule is exactly how the parser and
 * its callers drift apart — the deep-audit-1020 ISSUE-02 class of bug. A CLI
 * invocation is short, so the prefix walk is free.
 *
 * [dv18-A A-01a] `valueShortOptions` must be the SAME set [ShizukuOffloadHandler]
 * hands its own parser. The walk used to take only `booleanFlags`, so a
 * value-taking short option leading the invocation was read as a bare switch and
 * its VALUE became the first positional: `-p com.example package info` reported
 * the group as `com.example` (unknown subcommand) instead of `package`. Same
 * rule as the switch set, same reason: this scan is the parser's second reader,
 * and a second reader with a smaller vocabulary answers a different question.
 */
internal fun firstPositionalIndex(
    argv: List<String>,
    booleanFlags: Set<String>,
    valueShortOptions: Set<String> = emptySet(),
): Int {
    var seen = 0
    for (i in argv.indices) {
        val count = OffloadArgs(
            argv.subList(0, i + 1),
            booleanFlags = booleanFlags,
            valueShortOptions = valueShortOptions,
        ).positional.size
        if (count > seen) return i
        seen = count
    }
    return -1
}
