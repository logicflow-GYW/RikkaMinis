package com.rikkaminis.app.sandbox.offload

import android.content.Context
import com.rikkaminis.app.sandbox.GuestPathMapper
import com.rikkaminis.app.sandbox.NativeOffloadHandler
import com.rikkaminis.app.sandbox.NativeOffloadRequest
import com.rikkaminis.app.sandbox.NativeOffloadResult
import com.rikkaminis.app.sandbox.PRootKernel
import com.rikkaminis.app.sandbox.RootfsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.PatternSyntaxException

/**
 * `minis-fastio` — file-intensive primitives executed by the HOST process on
 * the real filesystem instead of through PRoot's ptrace boundary
 * ([T-minis-fastio]).
 *
 * ## Why
 *
 * PRoot charges ~100-200µs per path-touching syscall (stop/resume round trip
 * plus a POKEDATA rewrite of the translated path, which grows with path
 * length). A directory walk or a recursive delete over N files therefore costs
 * N × tax inside the guest — measured at 206µs per create+write+close and
 * 167µs per unlink. This handler does the same work from the app process with
 * ordinary JVM file calls (~1µs/op), so the guest pays one offload round trip
 * instead of N ptrace stops. The heavier and more file-dense the operation,
 * the larger the win.
 *
 * ## Scope
 *
 * Seven primitives, all defined by the same translation + guard:
 *
 *   minis-fastio du <path>...              read-only: entries + apparent bytes per tree
 *   minis-fastio rm [-r] <path>...         destructive: recursive delete
 *   minis-fastio find <path> [opts]        read-only: walk + filter (capped listing)
 *   minis-fastio grep <pattern> <path>...  read-only: line search (capped matches)
 *   minis-fastio cp [-r] <src> <dst>       write: copy file or tree
 *   minis-fastio mv <src> <dst>            write: rename (copy+delete across filesystems)
 *   minis-fastio tar -cf <a.tar> <path>...  write: pack (POSIX ustar, optional -z)
 *   minis-fastio tar -xf <a.tar> [-C dir]   write: unpack (tar-slip guarded)
 *
 * The read-only primitives (`du` / `find` / `grep`) are NOT gated — they grant
 * nothing the agent's `file_read` does not already have. The WRITE primitives
 * are gated by [OffloadGate] under their own rows (`fastio_rm`, `fastio_cp`,
 * `fastio_mv`, `fastio_tar` — one row per write tool so a per-tool override
 * stays possible), but their default is BYPASS: [GuestPathMapper] limits their
 * reachable set to the app's own sandbox, which the guest shell already
 * reaches unrestricted, so asking per delete bought nothing a shell `rm -rf`
 * does not already allow. A user who WANTS a prompt can still downgrade a row.
 *
 * ## Guards on the destructive / write paths
 *
 * Beyond the path guard in [GuestPathMapper] (refuse-instead-of-guess:
 * `..` above `/`, host /dev //proc //sys, symlink escapes), the write
 * primitives refuse:
 *   - user-mounted external folders (`/var/minis/mounts/<name>`) as a WRITE
 *     target — the shell's own read-only-mount wrappers are the authority
 *     there, and this handler cannot see the mount's writability (`mv` refuses
 *     an external SOURCE too, because it deletes it);
 *   - bind-mount roots (`/`, `/var/minis/workspace`, …) as the thing being
 *     replaced or removed (copying INTO such a directory is fine — the target
 *     is then `<root>/<name>`, which the handler resolves first);
 *   - an existing target unless `--force` is passed (refuse-instead-of-guess:
 *     an accidental overwrite has no undo);
 *   - a target inside its own source (`cp -r /a /a/b` never terminates).
 */
class FastioOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgv = request.argv.drop(1)
        // [da1020-C] The subcommand token is found PAST any leading options —
        // the global output flags (`--compact` / `-q` / `--quiet`) take no
        // value, so `--compact tar -cf a.tar x` still names `tar` as the
        // subcommand. `normalizeTarShorts` calls the SAME helper for its own
        // guard, so the expander and the flag set below can never drift apart
        // (they used to be two copies of `firstOrNull() == "tar"`, which broke
        // whenever a global output flag preceded the subcommand: the tar verbs
        // went undeclared and the `-cf` expansion never ran).
        val isTarCommand = firstSubcommandToken(rawArgv) == "tar"
        val args = OffloadArgs(
            // `find`-style single-dash long options (`-name`, `-type`, `-limit`)
            // are rewritten to their `--` spelling first: OffloadArgs reads any
            // `-word` as a short FLAG, so `find /usr -name "*.log"` would
            // otherwise parse `-name` as a flag and `*.log` as a positional
            // path. Rewriting here leaves the parser shared by all ~46 handlers
            // untouched.
            normalizeLongOptionSpelling(normalizeTarShorts(rawArgv)),
            // Declared booleans — see [fastioBooleanFlags] for why the tar
            // verbs are scoped to `tar` only.
            booleanFlags = fastioBooleanFlags(isTarCommand),
        )
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)

        val sub = args.positional.firstOrNull()
        val paths = args.positional.drop(1)
        return when (sub) {
            null -> NativeOffloadResult(2, usage("missing subcommand", args))
            "du" -> du(paths, args, request)
            "rm" -> rm(paths, args, request)
            "find" -> find(paths, args, request)
            "grep" -> grep(paths, args, request)
            "cp" -> cp(paths, args, request)
            "mv" -> mv(paths, args, request)
            "tar" -> tar(paths, args, request)
            else -> NativeOffloadResult(2, usage("unknown subcommand '$sub'", args))
        }
    }

    // ── du ──────────────────────────────────────────────────────────────────

    private fun du(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (paths.isEmpty()) return NativeOffloadResult(2, usage("du: missing <path>", args))

        val bindings = bindingsFor(request)
        val maxEntries = (args.getLong("max-entries") ?: DEFAULT_MAX_ENTRIES)
            .coerceIn(1L, HARD_MAX_ENTRIES)
        val startedNs = System.nanoTime()

        val results = JSONArray()
        var totalFiles = 0L
        var totalDirs = 0L
        var totalBytes = 0L
        var truncated = false
        var failures = 0

        for (raw in paths) {
            val entry = JSONObject()
            when (val resolution = GuestPathMapper.resolve(raw, request.cwd, bindings)) {
                is GuestPathMapper.Resolution.Denied -> {
                    failures++
                    results.put(deniedEntry(raw, resolution))
                }
                is GuestPathMapper.Resolution.Ok -> {
                    entry.put("path", resolution.guestPath).put("host", resolution.hostPath)
                    val path = Paths.get(resolution.hostPath)
                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        failures++
                        entry.put("error", "not_found")
                    } else {
                        val totals = WalkTotals()
                        walkForSize(path, totals, maxEntries)
                        entry.put("files", totals.files)
                            .put("dirs", totals.dirs)
                            .put("bytes", totals.bytes)
                        if (totals.truncated) entry.put("truncated", true)
                        if (totals.errors.isNotEmpty()) {
                            entry.put("errors", JSONArray(totals.errors))
                            failures++
                        }
                        totalFiles += totals.files
                        totalDirs += totals.dirs
                        totalBytes += totals.bytes
                        truncated = truncated || totals.truncated
                    }
                    results.put(entry)
                }
            }
        }

        val body = JSONObject()
            .put("results", results)
            .put("totals", JSONObject().put("files", totalFiles).put("dirs", totalDirs).put("bytes", totalBytes))
            .put("elapsed_ms", (System.nanoTime() - startedNs) / 1_000_000)
        if (truncated) body.put("truncated", true)
        return NativeOffloadResult(if (failures > 0) 1 else 0, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    // ── rm ──────────────────────────────────────────────────────────────────

    private fun rm(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (paths.isEmpty()) return NativeOffloadResult(2, usage("rm: missing <path>", args))

        // Destructive: gate before touching anything. Denied → PERMISSION_DENIED
        // envelope pointing at Settings → Permissions (OffloadGate builds it).
        OffloadGate.enforce(TOOL_NAME, DISPLAY_NAME, args, request)?.let { return it }

        // OffloadArgs does not split combined short options (`-rf` lands as one
        // flag "rf"), and teaching the shared parser to split them would change
        // argv handling for all ~46 handlers. Accept the combined spellings an
        // agent actually types instead — `rm -rf` failing with "is a directory;
        // pass -r" was the single most likely way to call this tool wrong.
        val recursive = args.hasFlag("r", "recursive", "rf", "fr", "R")
        val bindings = bindingsFor(request)
        val startedNs = System.nanoTime()

        val results = JSONArray()
        var deletedFiles = 0L
        var deletedDirs = 0L
        var freedBytes = 0L
        var failures = 0

        for (raw in paths) {
            val entry = JSONObject()
            when (val resolution = GuestPathMapper.resolve(raw, request.cwd, bindings)) {
                is GuestPathMapper.Resolution.Denied -> {
                    failures++
                    results.put(deniedEntry(raw, resolution))
                }
                is GuestPathMapper.Resolution.Ok -> {
                    entry.put("path", resolution.guestPath).put("host", resolution.hostPath)
                    val path = Paths.get(resolution.hostPath)
                    val refusal = rmRefusal(resolution, path, recursive)
                    if (refusal != null) {
                        failures++
                        entry.put("error", refusal.first).put("detail", refusal.second)
                    } else {
                        val totals = WalkTotals()
                        deleteTree(path, totals)
                        entry.put("deleted_files", totals.files)
                            .put("deleted_dirs", totals.dirs)
                            .put("freed_bytes", totals.bytes)
                        if (totals.errors.isNotEmpty()) {
                            entry.put("errors", JSONArray(totals.errors))
                            failures++
                        }
                        deletedFiles += totals.files
                        deletedDirs += totals.dirs
                        freedBytes += totals.bytes
                    }
                    results.put(entry)
                }
            }
        }

        val body = JSONObject()
            .put("results", results)
            .put(
                "totals",
                JSONObject()
                    .put("deleted_files", deletedFiles)
                    .put("deleted_dirs", deletedDirs)
                    .put("freed_bytes", freedBytes),
            )
            .put("elapsed_ms", (System.nanoTime() - startedNs) / 1_000_000)
        return NativeOffloadResult(if (failures > 0) 1 else 0, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    /**
     * The destructive-path policy, applied AFTER translation. Returns
     * `(errorCode, detail)` when the target must not be deleted, or null when
     * it may be. Split out so the JVM wiring test can assert the order of the
     * checks against the source text.
     */
    private fun rmRefusal(
        resolution: GuestPathMapper.Resolution.Ok,
        path: Path,
        recursive: Boolean,
    ): Pair<String, String>? {
        if (resolution.binding.isExternalMount) {
            return "external_mount" to
                "'${resolution.guestPath}' is a user-mounted external folder; " +
                "delete it through the shell so the read-only-mount guard applies"
        }
        if (resolution.isBindingRoot) {
            return "bind_mount_root" to
                "'${resolution.guestPath}' is a bind-mount root (${resolution.binding.hostBase}); refusing"
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "not_found" to "'${resolution.guestPath}' does not exist"
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !recursive) {
            return "is_directory" to
                "'${resolution.guestPath}' is a directory; pass -r to delete it recursively"
        }
        return null
    }

    // ── find ────────────────────────────────────────────────────────────────

    private fun find(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (paths.isEmpty()) return NativeOffloadResult(2, usage("find: missing <path>", args))
        unknownOption(args, FIND_OPTIONS)?.let { return NativeOffloadResult(2, usage("find: $it", args)) }
        missingValue(args, "name", "type", "limit", "max-entries")?.let {
            return NativeOffloadResult(2, usage("find: -$it needs a value", args))
        }

        val matcher = args.get("name")?.let { pattern ->
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
            } catch (e: Exception) {
                return NativeOffloadResult(2, usage("find: bad -name glob '$pattern': ${e.message}", args))
            }
        }
        val typeFilter: Char? = args.get("type")?.let { raw ->
            when (raw.lowercase()) {
                "f" -> 'f'
                "d" -> 'd'
                "l" -> 'l'
                else -> return NativeOffloadResult(2, usage("find: -type must be f, d or l (got '$raw')", args))
            }
        }
        val limit = (args.getInt("limit") ?: DEFAULT_FIND_LIMIT).coerceIn(1, HARD_MATCH_LIMIT)
        val maxEntries = (args.getLong("max-entries") ?: DEFAULT_MAX_ENTRIES)
            .coerceIn(1L, HARD_MAX_ENTRIES)
        val bindings = bindingsFor(request)
        val startedNs = System.nanoTime()

        val results = JSONArray()
        var totalFiles = 0L
        var totalDirs = 0L
        var totalMatched = 0L
        var truncated = false
        var capped = false
        var failures = 0
        var remaining = limit

        for (raw in paths) {
            when (val resolution = GuestPathMapper.resolve(raw, request.cwd, bindings)) {
                is GuestPathMapper.Resolution.Denied -> {
                    failures++
                    results.put(deniedEntry(raw, resolution))
                }
                is GuestPathMapper.Resolution.Ok -> {
                    val entry = JSONObject().put("path", resolution.guestPath).put("host", resolution.hostPath)
                    val path = Paths.get(resolution.hostPath)
                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        failures++
                        entry.put("error", "not_found")
                    } else {
                        val outcome = FindOutcome()
                        findTree(path, resolution.guestPath, matcher, typeFilter, remaining, maxEntries, outcome)
                        val matches = JSONArray()
                        for (match in outcome.matches) {
                            matches.put(
                                JSONObject()
                                    .put("path", match.guestPath)
                                    .put("type", match.type.toString())
                                    .put("size", match.size),
                            )
                        }
                        entry.put("matches", matches)
                            .put("files", outcome.files)
                            .put("dirs", outcome.dirs)
                            .put("matched", outcome.matched)
                        if (outcome.truncated) entry.put("truncated", true)
                        if (outcome.matchesCapped) entry.put("matches_capped", true)
                        if (outcome.errors.isNotEmpty()) {
                            entry.put("errors", JSONArray(outcome.errors))
                            failures++
                        }
                        totalFiles += outcome.files
                        totalDirs += outcome.dirs
                        totalMatched += outcome.matched
                        truncated = truncated || outcome.truncated
                        capped = capped || outcome.matchesCapped
                        remaining -= outcome.matches.size
                    }
                    results.put(entry)
                }
            }
        }

        val body = JSONObject()
            .put("results", results)
            .put(
                "totals",
                JSONObject().put("files", totalFiles).put("dirs", totalDirs).put("matched", totalMatched),
            )
            .put("limit", limit)
            .put("elapsed_ms", (System.nanoTime() - startedNs) / 1_000_000)
        if (truncated || capped) body.put("truncated", true)
        if (capped) body.put("matches_capped", true)
        // Compact, not indented: the match list IS the payload here, and
        // pretty-printing 500 records doubles the bytes /bin/cat pulls back
        // through proot — the transfer, not the walk, would become the cost.
        return NativeOffloadResult(if (failures > 0) 1 else 0, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    // ── grep ────────────────────────────────────────────────────────────────

    private fun grep(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        if (paths.size < 2) return NativeOffloadResult(2, usage("grep: expected <pattern> <path>...", args))
        unknownOption(args, GREP_OPTIONS)?.let { return NativeOffloadResult(2, usage("grep: $it", args)) }
        missingValue(args, "limit", "max-entries")?.let {
            return NativeOffloadResult(2, usage("grep: -$it needs a value", args))
        }

        val pattern = paths.first()
        val roots = paths.drop(1)
        val regex = try {
            Regex(pattern, if (args.hasFlag("i", "ignore-case")) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: PatternSyntaxException) {
            // Dialect stated in the error as well as in the help text: a user
            // coming from ripgrep/PCRE must be able to see WHY a pattern that
            // works there was rejected (variable-length lookbehind and PCRE
            // verbs are not supported).
            return NativeOffloadResult(
                2,
                usage("grep: invalid pattern (java.util.regex, not PCRE): ${e.description}", args),
            )
        }
        val limit = (args.getInt("limit") ?: DEFAULT_GREP_LIMIT).coerceIn(1, HARD_MATCH_LIMIT)
        val maxEntries = (args.getLong("max-entries") ?: DEFAULT_MAX_ENTRIES)
            .coerceIn(1L, HARD_MAX_ENTRIES)
        val bindings = bindingsFor(request)
        val startedNs = System.nanoTime()

        val results = JSONArray()
        var totalMatches = 0L
        var totalScanned = 0L
        var totalBinary = 0L
        var totalLarge = 0L
        var totalLinks = 0L
        var totalSpecial = 0L
        var totalBytes = 0L
        var truncated = false
        var capped = false
        var failures = 0
        var remaining = limit

        for (raw in roots) {
            when (val resolution = GuestPathMapper.resolve(raw, request.cwd, bindings)) {
                is GuestPathMapper.Resolution.Denied -> {
                    failures++
                    results.put(deniedEntry(raw, resolution))
                }
                is GuestPathMapper.Resolution.Ok -> {
                    val entry = JSONObject().put("path", resolution.guestPath).put("host", resolution.hostPath)
                    val path = Paths.get(resolution.hostPath)
                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        failures++
                        entry.put("error", "not_found")
                    } else {
                        val outcome = GrepOutcome()
                        grepTree(path, resolution.guestPath, regex, remaining, maxEntries, outcome)
                        val matches = JSONArray()
                        for (match in outcome.matches) {
                            matches.put(
                                JSONObject()
                                    .put("path", match.guestPath)
                                    .put("line", match.lineNumber)
                                    .put("text", match.text),
                            )
                        }
                        entry.put("matches", matches)
                            .put("files_scanned", outcome.filesScanned)
                            .put("files_skipped_binary", outcome.filesSkippedBinary)
                            .put("files_skipped_large", outcome.filesSkippedLarge)
                            .put("files_skipped_link", outcome.filesSkippedLink)
                            .put("files_skipped_special", outcome.filesSkippedSpecial)
                            .put("bytes_scanned", outcome.bytesScanned)
                        if (outcome.truncated) entry.put("truncated", true)
                        if (outcome.matchesCapped) entry.put("matches_capped", true)
                        if (outcome.errors.isNotEmpty()) {
                            entry.put("errors", JSONArray(outcome.errors))
                            failures++
                        }
                        totalMatches += outcome.matches.size
                        totalScanned += outcome.filesScanned
                        totalBinary += outcome.filesSkippedBinary
                        totalLarge += outcome.filesSkippedLarge
                        totalLinks += outcome.filesSkippedLink
                        totalSpecial += outcome.filesSkippedSpecial
                        totalBytes += outcome.bytesScanned
                        truncated = truncated || outcome.truncated
                        capped = capped || outcome.matchesCapped
                        remaining -= outcome.matches.size
                    }
                    results.put(entry)
                }
            }
        }

        val body = JSONObject()
            .put("results", results)
            .put(
                "totals",
                JSONObject()
                    .put("matches", totalMatches)
                    .put("files_scanned", totalScanned)
                    .put("files_skipped_binary", totalBinary)
                    .put("files_skipped_large", totalLarge)
                    .put("files_skipped_link", totalLinks)
                    .put("files_skipped_special", totalSpecial)
                    .put("bytes_scanned", totalBytes),
            )
            .put("engine", "java.util.regex")
            .put("limit", limit)
            .put("elapsed_ms", (System.nanoTime() - startedNs) / 1_000_000)
        if (truncated || capped) body.put("truncated", true)
        if (capped) body.put("matches_capped", true)
        return NativeOffloadResult(if (failures > 0) 1 else 0, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    // ── cp / mv ─────────────────────────────────────────────────────────────

    private fun cp(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        OffloadGate.enforce(CP_TOOL_NAME, CP_DISPLAY_NAME, args, request)?.let { return it }
        return transfer(paths, args, request, copy = true)
    }

    private fun mv(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        OffloadGate.enforce(MV_TOOL_NAME, MV_DISPLAY_NAME, args, request)?.let { return it }
        return transfer(paths, args, request, copy = false)
    }

    /**
     * Shared body of `cp` / `mv`: translate, refuse, then hand two host paths to
     * [copyTree] / [moveTree]. The gate has already run by the time this is
     * called — every decision below happens behind it.
     */
    private fun transfer(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
        copy: Boolean,
    ): NativeOffloadResult {
        val verb = if (copy) "cp" else "mv"
        if (paths.size != 2) return NativeOffloadResult(2, usage("$verb: expected <src> <dst>", args))
        unknownOption(args, TRANSFER_OPTIONS)?.let { return NativeOffloadResult(2, usage("$verb: $it", args)) }

        val recursive = args.hasFlag("r", "recursive", "rf", "fr", "R")
        val force = args.hasFlag("force", "f", "rf", "fr")
        val bindings = bindingsFor(request)
        val startedNs = System.nanoTime()

        val srcResolution = GuestPathMapper.resolve(paths[0], request.cwd, bindings)
        val dstResolution = GuestPathMapper.resolve(paths[1], request.cwd, bindings)
        val src = srcResolution as? GuestPathMapper.Resolution.Ok
        val dst = dstResolution as? GuestPathMapper.Resolution.Ok
        val entry = JSONObject()
        if (src == null || dst == null) {
            val denied = (srcResolution as? GuestPathMapper.Resolution.Denied)
                ?: (dstResolution as GuestPathMapper.Resolution.Denied)
            entry.put("error", denied.reason.name.lowercase()).put("detail", denied.detail)
            return finishTransfer(entry, failed = true, startedNs = startedNs, args = args)
        }

        entry.put("src", src.guestPath).put("host", src.hostPath).put("dst", dst.guestPath)
        val srcPath = Paths.get(src.hostPath)
        val dstPath = Paths.get(dst.hostPath)

        // `cp -r a dir` puts a INSIDE dir (shell semantics), so the thing that
        // must not already exist / must not be a mount root is the RESOLVED
        // target, not the dst the caller typed.
        val intoDirectory = Files.isDirectory(dstPath, LinkOption.NOFOLLOW_LINKS)
        val target = if (intoDirectory) dstPath.resolve(srcPath.fileName) else dstPath
        val targetGuest = if (intoDirectory) "${dst.guestPath.trimEnd('/')}/${srcPath.fileName}" else dst.guestPath
        entry.put("target", targetGuest).put("target_host", target.toString())

        val refusal = transferRefusal(src, dst, srcPath, intoDirectory, recursive, copy)
        if (refusal != null) {
            entry.put("error", refusal.first).put("detail", refusal.second)
            return finishTransfer(entry, failed = true, startedNs = startedNs, args = args)
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !force) {
            entry.put("error", "already_exists")
                .put("detail", "'$targetGuest' already exists; pass --force to overwrite")
            return finishTransfer(entry, failed = true, startedNs = startedNs, args = args)
        }
        if (targetInsideSource(srcPath, target)) {
            entry.put("error", "target_inside_source")
                .put(
                    "detail",
                    "'$targetGuest' is inside '${src.guestPath}'; refusing " +
                        "(a copy into itself never terminates)",
                )
            return finishTransfer(entry, failed = true, startedNs = startedNs, args = args)
        }

        var failed = false
        if (copy) {
            val outcome = CopyOutcome()
            copyTree(srcPath, target, force, outcome)
            entry.put("copied_files", outcome.files)
                .put("copied_dirs", outcome.dirs)
                .put("bytes", outcome.bytes)
            if (outcome.overwritten > 0) entry.put("overwritten", outcome.overwritten)
            if (outcome.errors.isNotEmpty()) {
                entry.put("errors", JSONArray(outcome.errors))
                failed = true
            }
        } else {
            val outcome = MoveOutcome()
            moveTree(srcPath, target, force, outcome)
            entry.put("method", outcome.method)
            if (outcome.counted) {
                entry.put("copied_files", outcome.files)
                    .put("copied_dirs", outcome.dirs)
                    .put("bytes", outcome.bytes)
            }
            if (outcome.errors.isNotEmpty()) {
                entry.put("errors", JSONArray(outcome.errors))
                failed = true
            }
        }
        return finishTransfer(entry, failed, startedNs, args)
    }

    // ── tar ─────────────────────────────────────────────────────────────────

    private fun tar(
        paths: List<String>,
        args: OffloadArgs,
        request: NativeOffloadRequest,
    ): NativeOffloadResult {
        val create = args.hasFlag("create")
        val extract = args.hasFlag("extract")
        if (create == extract) {
            return NativeOffloadResult(
                2,
                usage("tar: pass exactly one of -c (create) or -x (extract)", args),
            )
        }
        unknownOption(args, TAR_OPTIONS)?.let { return NativeOffloadResult(2, usage("tar: $it", args)) }
        missingValue(args, "file", "directory")?.let {
            return NativeOffloadResult(2, usage("tar: -$it needs a value", args))
        }

        // Write primitive either way: creating an archive writes it, extracting
        // writes everything inside it. One row covers both.
        OffloadGate.enforce(TAR_TOOL_NAME, TAR_DISPLAY_NAME, args, request)?.let { return it }

        val fileOption = args.get("file")
        val archiveRaw = fileOption ?: paths.firstOrNull()
            ?: return NativeOffloadResult(2, usage("tar: missing <archive>", args))
        val rest = if (fileOption == null) paths.drop(1) else paths
        val gzip = args.hasFlag("gzip", "z")
        val force = args.hasFlag("force")
        val bindings = bindingsFor(request)
        val startedNs = System.nanoTime()

        val archiveResolution = GuestPathMapper.resolve(archiveRaw, request.cwd, bindings)
        if (archiveResolution is GuestPathMapper.Resolution.Denied) {
            // A refused archive path means NO archive was written, so the exit
            // code must say so: `false` here returned 0 with an error object in
            // the body, which `set -e` and `&&` chains read as success. Matches
            // the destination-denied branch in tarExtractFrom (same shape, true).
            // (deep-audit-1019 ISSUE-03)
            return finishTar(deniedEntry(archiveRaw, archiveResolution), true, startedNs, args)
        }
        val archive = archiveResolution as GuestPathMapper.Resolution.Ok
        val archivePath = Paths.get(archive.hostPath)

        val entry = JSONObject()
            .put("archive", archive.guestPath)
            .put("host", archive.hostPath)
            .put("gzip", gzip)
        if (archive.binding.isExternalMount && create) {
            return finishTar(
                entry.put("error", "external_mount").put(
                    "detail",
                    "'${archive.guestPath}' is a user-mounted external folder; write to it through " +
                        "the shell so the read-only-mount guard applies",
                ),
                true,
                startedNs,
                args,
            )
        }

        return if (create) {
            tarCreateInto(entry, archivePath, rest, bindings, request, gzip, force, startedNs, args)
        } else {
            tarExtractFrom(entry, archivePath, args, bindings, request, gzip, force, startedNs)
        }
    }

    /** `tar -c`: pack [memberRaws] into the (already resolved) archive path. */
    private fun tarCreateInto(
        entry: JSONObject,
        archivePath: Path,
        memberRaws: List<String>,
        bindings: List<GuestPathMapper.Binding>,
        request: NativeOffloadRequest,
        gzip: Boolean,
        force: Boolean,
        startedNs: Long,
        args: OffloadArgs,
    ): NativeOffloadResult {
        if (memberRaws.isEmpty()) {
            return NativeOffloadResult(2, usage("tar: -cf needs at least one <path>", args))
        }
        val members = ArrayList<Pair<Path, String>>()
        val guestNames = JSONArray()
        val denied = JSONArray()
        for (raw in memberRaws) {
            when (val resolution = GuestPathMapper.resolve(raw, request.cwd, bindings)) {
                is GuestPathMapper.Resolution.Denied -> denied.put(deniedEntry(raw, resolution))
                is GuestPathMapper.Resolution.Ok -> {
                    // The member name is the ARGUMENT SPELLING, not the resolved
                    // guest path: `tar -cf a.tar src` must store `src/...`, or
                    // every third-party extraction nests under
                    // `dest/var/minis/workspace/src` (see [tarMemberPrefix]).
                    members.add(Paths.get(resolution.hostPath) to tarMemberPrefix(raw))
                    guestNames.put(resolution.guestPath)
                }
            }
        }
        entry.put("members", guestNames)
        if (denied.length() > 0) entry.put("denied", denied)
        if (members.isEmpty()) {
            return finishTar(entry.put("error", "no_members"), true, startedNs, args)
        }
        if (Files.exists(archivePath, LinkOption.NOFOLLOW_LINKS) && !force) {
            return finishTar(
                entry.put("error", "already_exists")
                    .put("detail", "'${entry.getString("archive")}' already exists; pass --force to overwrite"),
                true,
                startedNs,
                args,
            )
        }

        // [dv15-A2] A-04: the archive path's LAST segment is deliberately left
        // unresolved by GuestPathMapper (that is what keeps `rm <link>` working),
        // so a symbolic link sitting here would make newOutputStream WRITE
        // THROUGH it to whatever host path it names — `--force` is what made that
        // reachable. Refuse the link itself (exit 2). Without `--force` a link is
        // already refused above as `already_exists`; this guard is what stops the
        // `--force` write-through.
        if (Files.isSymbolicLink(archivePath)) {
            return refuseLinkedArchive(entry, "write", startedNs, args)
        }

        val outcome = TarCreateOutcome()
        try {
            Files.createDirectories(archivePath.parent)
            val raw = Files.newOutputStream(archivePath)
            val sink = if (gzip) java.util.zip.GZIPOutputStream(raw) else raw
            sink.use { tarCreate(it, members, tarEntryBudget(args), outcome, archivePath) }
        } catch (e: Exception) {
            outcome.errors.add("${entry.getString("archive")}: ${e.message}")
        }
        entry.put("files", outcome.files)
            .put("dirs", outcome.dirs)
            .put("symlinks", outcome.symlinks)
            .put("bytes", outcome.bytes)
        if (outcome.skippedSpecial > 0) {
            entry.put("skipped_special", outcome.skippedSpecial)
            entry.put("skipped_names", JSONArray(outcome.skippedNames))
        }
        // [§6 F8] The archive was inside the tree it packs, so the walk met it.
        // Reported, not silent: the member list of the written archive genuinely
        // does not contain `a.tar` even though `a.tar` was named by the root.
        if (outcome.skippedSelf > 0) {
            entry.put("skipped_self", outcome.skippedSelf)
            entry.put("skipped_self_names", JSONArray(outcome.skippedSelfNames))
        }
        if (outcome.truncated) entry.put("truncated", true)
        val archiveBytes = try {
            Files.size(archivePath)
        } catch (_: Exception) {
            -1L
        }
        entry.put("archive_bytes", archiveBytes)
        if (outcome.errors.isNotEmpty()) entry.put("errors", JSONArray(outcome.errors))
        return finishTar(entry, outcome.errors.isNotEmpty() || denied.length() > 0, startedNs, args)
    }

    /**
     * `tar -x`: validate the whole archive first, then extract. The two passes
     * are what make "a hostile entry anywhere leaves the destination untouched"
     * true — refusing halfway would still have written everything before it.
     */
    private fun tarExtractFrom(
        entry: JSONObject,
        archivePath: Path,
        args: OffloadArgs,
        bindings: List<GuestPathMapper.Binding>,
        request: NativeOffloadRequest,
        gzip: Boolean,
        force: Boolean,
        startedNs: Long,
    ): NativeOffloadResult {
        val destRaw = args.get("directory") ?: request.cwd
        val destResolution = GuestPathMapper.resolve(destRaw, request.cwd, bindings)
        if (destResolution is GuestPathMapper.Resolution.Denied) {
            return finishTar(deniedEntry(destRaw, destResolution), true, startedNs, args)
        }
        val dest = destResolution as GuestPathMapper.Resolution.Ok
        entry.put("dest", dest.guestPath).put("dest_host", dest.hostPath)
        if (dest.binding.isExternalMount) {
            return finishTar(
                entry.put("error", "external_mount").put(
                    "detail",
                    "'${dest.guestPath}' is a user-mounted external folder; extract through the shell " +
                        "so the read-only-mount guard applies",
                ),
                true,
                startedNs,
                args,
            )
        }
        if (!Files.exists(archivePath, LinkOption.NOFOLLOW_LINKS)) {
            return finishTar(entry.put("error", "not_found"), true, startedNs, args)
        }
        if (Files.isDirectory(archivePath, LinkOption.NOFOLLOW_LINKS)) {
            return finishTar(
                entry.put("error", "is_directory").put("detail", "the archive path is a directory"),
                true,
                startedNs,
                args,
            )
        }

        // [dv15-A2] A-04: the two checks above are NOFOLLOW, so a link passes
        // both of them; openArchive's newInputStream is NOT NOFOLLOW, so it would
        // READ THROUGH the link to whatever host path it names. Same boundary as
        // the write side — refuse the link instead of following it.
        if (Files.isSymbolicLink(archivePath)) {
            return refuseLinkedArchive(entry, "read", startedNs, args)
        }

        val destPath = Paths.get(dest.hostPath)
        val budget = tarEntryBudget(args)
        val validation = TarExtractOutcome()
        val safe = try {
            openArchive(archivePath, gzip).use { tarValidate(it, destPath, budget, validation) }
        } catch (e: Exception) {
            validation.errors.add("${entry.getString("archive")}: ${e.message}")
            false
        }
        if (!safe) {
            entry.put("error", "unsafe_archive").put("errors", JSONArray(validation.errors))
            return finishTar(entry, true, startedNs, args)
        }

        val outcome = TarExtractOutcome()
        try {
            openArchive(archivePath, gzip).use { tarExtract(it, destPath, force, budget, outcome) }
        } catch (e: Exception) {
            outcome.errors.add("${entry.getString("archive")}: ${e.message}")
        }
        entry.put("files", outcome.files)
            .put("dirs", outcome.dirs)
            .put("symlinks", outcome.symlinks)
            .put("bytes", outcome.bytes)
        if (outcome.skippedHardlink > 0) entry.put("skipped_hardlink", outcome.skippedHardlink)
        if (outcome.skippedSpecial > 0) entry.put("skipped_special", outcome.skippedSpecial)
        if (outcome.paxHeaders > 0) entry.put("pax_headers", outcome.paxHeaders)
        if (outcome.skippedNames.isNotEmpty()) entry.put("skipped_names", JSONArray(outcome.skippedNames))
        if (outcome.truncated) entry.put("truncated", true)
        if (outcome.errors.isNotEmpty()) entry.put("errors", JSONArray(outcome.errors))
        return finishTar(entry, outcome.errors.isNotEmpty(), startedNs, args)
    }

    private fun openArchive(path: Path, gzip: Boolean): java.io.InputStream {
        val raw = Files.newInputStream(path)
        return if (gzip) java.util.zip.GZIPInputStream(raw) else raw
    }

    private fun tarEntryBudget(args: OffloadArgs): Long =
        (args.getLong("max-entries") ?: DEFAULT_MAX_ENTRIES).coerceIn(1L, HARD_MAX_ENTRIES)

    private fun finishTar(
        entry: JSONObject,
        failed: Boolean,
        startedNs: Long,
        args: OffloadArgs,
    ): NativeOffloadResult = finishTarWith(entry, if (failed) 1 else 0, startedNs, args)

    /**
     * [dv15-A2] A-04: [finishTar] with an explicit exit code and the same body
     * shape, because "the archive path itself is unusable" is a usage error
     * (exit 2) and not the plain failure (exit 1) the boolean can express.
     */
    private fun finishTarWith(
        entry: JSONObject,
        exitCode: Int,
        startedNs: Long,
        args: OffloadArgs,
    ): NativeOffloadResult {
        val body = JSONObject()
            .put("results", JSONArray().put(entry))
            .put("elapsed_ms", (System.nanoTime() - startedNs) / 1_000_000)
        return NativeOffloadResult(exitCode, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    /**
     * [dv15-A2] A-04: the archive path is a symbolic link. GuestPathMapper leaves
     * the LAST segment of a guest path unresolved on purpose (that is what keeps
     * `rm <link>` working), so the archive path is a host path whose final
     * component may be a link to ANY host file: `newOutputStream` would write
     * through it (create, reachable with `--force`) and `newInputStream` would
     * read through it (extract). That crosses the guest/host boundary this
     * handler exists to enforce. The link itself is refused (exit 2) rather than
     * resolved or deleted — resolving would lose `rm <link>` semantics, and
     * deleting would clobber a path the caller never named.
     */
    private fun refuseLinkedArchive(
        entry: JSONObject,
        verb: String,
        startedNs: Long,
        args: OffloadArgs,
    ): NativeOffloadResult = finishTarWith(
        entry.put("error", "unsafe_archive_path").put(
            "detail",
            "'${entry.getString("archive")}' is a symbolic link; fastio tar refuses to $verb through it. " +
                "Remove the link first, or name a path whose last segment is a real file.",
        ),
        2,
        startedNs,
        args,
    )

    /**
     * Write-path policy, applied AFTER translation and BEFORE the target is
     * resolved away. Returns `(errorCode, detail)` when the transfer must not
     * happen, or null when it may. Split out so the JVM wiring test can assert
     * the order of the checks against the source text.
     */
    private fun transferRefusal(
        src: GuestPathMapper.Resolution.Ok,
        dst: GuestPathMapper.Resolution.Ok,
        srcPath: Path,
        intoDirectory: Boolean,
        recursive: Boolean,
        copy: Boolean,
    ): Pair<String, String>? {
        if (!Files.exists(srcPath, LinkOption.NOFOLLOW_LINKS)) {
            return "not_found" to "'${src.guestPath}' does not exist"
        }
        if (dst.binding.isExternalMount) {
            return "external_mount" to
                "'${dst.guestPath}' is a user-mounted external folder; write to it through the shell " +
                "so the read-only-mount guard applies"
        }
        if (!copy && src.binding.isExternalMount) {
            return "external_mount" to
                "'${src.guestPath}' is a user-mounted external folder; move it through the shell " +
                "so the read-only-mount guard applies"
        }
        // Only when the mount root ITSELF would be replaced or removed: copying
        // INTO `/var/minis/workspace` resolves to `<root>/<name>`, which is a
        // normal write the caller plainly meant.
        if (dst.isBindingRoot && !intoDirectory) {
            return "bind_mount_root" to "'${dst.guestPath}' is a bind-mount root (${dst.binding.hostBase}); refusing"
        }
        if (!copy && src.isBindingRoot) {
            return "bind_mount_root" to "'${src.guestPath}' is a bind-mount root (${src.binding.hostBase}); refusing"
        }
        if (copy && !recursive && Files.isDirectory(srcPath, LinkOption.NOFOLLOW_LINKS)) {
            return "is_directory" to "'${src.guestPath}' is a directory; pass -r to copy it recursively"
        }
        return null
    }

    private fun finishTransfer(
        entry: JSONObject,
        failed: Boolean,
        startedNs: Long,
        args: OffloadArgs,
    ): NativeOffloadResult {
        val body = JSONObject()
            .put("results", JSONArray().put(entry))
            .put("elapsed_ms", (System.nanoTime() - startedNs) / 1_000_000)
        return NativeOffloadResult(if (failed) 1 else 0, OffloadOutput.formatBody(body.toString(2), args) + "\n")
    }

    // ── shared plumbing ─────────────────────────────────────────────────────

    /**
     * The `-b` table for THIS session, rebuilt from the same inputs proot was
     * launched with: the live global map plus the calling session's own subdirs.
     * `request.sessionId` is the chat session the shell belongs to
     * (`MINIS_CHAT_SESSION_ID`); without it the session-scoped subdirs fall back
     * to the rootfs placeholders rather than guessing another session's tree.
     */
    private fun bindingsFor(request: NativeOffloadRequest): List<GuestPathMapper.Binding> =
        GuestPathMapper.sessionBindings(
            filesDir = context.filesDir.absolutePath,
            sessionId = request.sessionId,
            globalBindings = PRootKernel.bindMounts.toMap(),
            rootfsDir = RootfsManager.getInstance(context).rootfsDir.absolutePath,
        )

    private fun deniedEntry(raw: String, denied: GuestPathMapper.Resolution.Denied): JSONObject =
        JSONObject()
            .put("path", raw)
            .put("error", denied.reason.name.lowercase())
            .put("detail", denied.detail)

    private fun usage(reason: String, args: OffloadArgs): String =
        // [da1034-A4] ISSUE-08: every caller is an exit-2 usage error, so the
        // payload is the JSON envelope the error paths use — prose (plus a
        // bare HELP dump) passed through formatBody untouched and broke a
        // `jq` / `json.loads` reader. `help` keeps the full HELP text
        // reachable; `--quiet` now returns just the `error` field, like every
        // other envelope in this repo.
        OffloadOutput.formatBody(
            JSONObject()
                .put("error", "usage")
                .put("message", reason)
                .put("help", HELP)
                .toString(),
            args,
        ) + "\n"

    /**
     * Refuse an option this subcommand does not implement. Without this a typo
     * (`find /x -nmae "*.log"`) parses as an unknown flag, is ignored, and
     * returns every entry — a silently wrong answer, which is worse than an
     * error. Returns the message, or null when every option is known.
     */
    private fun unknownOption(args: OffloadArgs, known: Set<String>): String? {
        val unknown = args.unknownFlags(known)
        return if (unknown.isEmpty()) null else "unknown option --${unknown.first()}; see --help"
    }

    /** An option that needs a value but was written bare (`find /x --name`). */
    private fun missingValue(args: OffloadArgs, vararg names: String): String? =
        names.firstOrNull { args.hasFlag(it) }

    companion object {
        private const val TOOL_NAME = "fastio_rm"
        private const val DISPLAY_NAME = "minis-fastio (delete)"

        // One permission row per WRITE primitive: trusting deletion must not
        // silently authorise overwriting a different file.
        private const val CP_TOOL_NAME = "fastio_cp"
        private const val CP_DISPLAY_NAME = "minis-fastio (copy)"
        private const val MV_TOOL_NAME = "fastio_mv"
        private const val MV_DISPLAY_NAME = "minis-fastio (move)"
        private const val TAR_TOOL_NAME = "fastio_tar"
        private const val TAR_DISPLAY_NAME = "minis-fastio (archive)"

        // ponytail: one entry budget for the whole command, not per path — a
        // walk that would run for minutes is a stuck offload worker (the pool
        // is 2 threads and a saturated pool stalls every other tool call).
        // 天花板: trees with more than this many entries report
        // `truncated: true` and under-count; 升级触发: a real task hits the cap.
        private const val DEFAULT_MAX_ENTRIES = 2_000_000L
        private const val HARD_MAX_ENTRIES = 50_000_000L

        /** Match budget for `find` / `grep` listings — see HARD_MATCH_LIMIT. */
        private const val DEFAULT_FIND_LIMIT = 500
        private const val DEFAULT_GREP_LIMIT = 200

        // ponytail: a listing is read back by /bin/cat INSIDE proot, so an
        // unbounded one turns a fast walk into a slow transfer; the cap is what
        // keeps the win. 天花板: a task genuinely needs >50k hits in one call
        // (it can page with -limit and a narrower root).
        // 升级触发: a user hits the cap and asks for more.
        private const val HARD_MATCH_LIMIT = 50_000

        // Options each subcommand understands, for the typo guard. Value-taking
        // options are listed too, so a bare `--name` is reported as "needs a
        // value" rather than as an unknown option.
        private val FIND_OPTIONS = setOf("name", "type", "limit", "max-entries")
        private val GREP_OPTIONS = setOf("i", "ignore-case", "limit", "max-entries")
        private val TRANSFER_OPTIONS = setOf("r", "recursive", "rf", "fr", "R", "force", "f")
        private val TAR_OPTIONS = setOf(
            "c", "x", "create", "extract", "f", "file", "z", "gzip", "C", "directory",
            "v", "verbose", "force", "max-entries",
        )

        /**
         * [da1020-C] Boolean flags the shared parser must declare, keyed by
         * whether the invocation is a `tar` command. Kept pure so both arms
         * stay testable off-device:
         *
         * `--recursive` / `--force` / `--ignore-case` are booleans, not
         * `--key value` pairs. Without this declaration OffloadArgs consumes
         * the NEXT token as the option's value, so `rm --recursive /tmp/x`
         * lost the path and reported "missing <path>" instead of deleting
         * anything.
         *
         * The tar verbs (`--create`/`--extract`/`--gzip`/`--verbose`) are
         * modes too, but they are declared ONLY for `tar`. The parser turns
         * any UNKNOWN `--key` into a `--key value` pair, so declaring them
         * for every subcommand made `du --create btree` treat `--create` as
         * a flag and silently walk `btree` (exit 0), where the intended
         * behaviour is "missing <path>" (exit 2). Scope — not acceptance —
         * is the contract.
         */
        internal fun fastioBooleanFlags(isTarCommand: Boolean): Set<String> =
            if (isTarCommand) {
                setOf(
                    "recursive", "force", "ignore-case",
                    // tar verbs: `--create`/`--gzip`/... are modes, never `--key value`.
                    "create", "extract", "gzip", "verbose",
                )
            } else {
                setOf("recursive", "force", "ignore-case")
            }

        /**
         * [da1020-C] The subcommand is the first token that is not part of a
         * leading option cluster. Mirrors OffloadArgs' own token rule so the
         * raw-argv scan and the shared parse agree:
         *   - global output flags (`--compact` / `-q` / `--quiet`, see
         *     [OffloadOutput.OUTPUT_FLAGS]) and short flags take no value;
         *   - a `--key value` pair consumes the next token, exactly as an
         *     UNKNOWN `--key` does in OffloadArgs;
         *   - `--key=value` is self-contained;
         *   - the first non-option token names the subcommand.
         * `isTarCommand` and [normalizeTarShorts] both call this, so the flag
         * set and the expander can never drift apart.
         */
        internal fun firstSubcommandToken(rawArgv: List<String>): String? {
            // [dv14-A1] ISSUE-04: the "takes no value" set must mirror the ONE
            // the shared parse will actually use. OffloadArgs treats every name
            // in `effectiveBooleanFlags` (declared `booleanFlags` +
            // [OffloadOutput.OUTPUT_FLAGS]) as a bare flag, so this scan must
            // too: [fastioBooleanFlags] is passed `isTarCommand = true` — the
            // superset — because this scan runs BEFORE the subcommand is known.
            // It used to know only about OUTPUT_FLAGS, so a leading `--force` /
            // `--verbose` / `--create` swallowed `tar` as if it were that
            // option's value: `isTarCommand` came out false, [normalizeTarShorts]
            // bailed out, and both the `-cf`/`-xf` expansion and the tar-only
            // boolean verbs went missing (`minis-fastio --force tar -xf a.tar`
            // failed with exit 2).
            //
            // The `find`-style single-dash longs ([SHORT_LONG_OPTIONS]) are
            // modelled too: [normalizeLongOptionSpelling] rewrites them to their
            // `--` spelling before the parse, so `-limit 10 tar …` names `tar`
            // here even though the raw token still carries one dash.
            //
            // [dv18-A A-01b] The same "which raw spelling will consume a value"
            // question applies to the raw single-dash shorts that
            // [normalizeTarShorts] turns into a value-taking long form
            // ([SHORT_VALUE_OPTIONS]): `-C /tmp tar -xf a.tar` used to read `-C`
            // as a bare switch, take `/tmp` for the subcommand and answer
            // `unknown subcommand '/tmp'` — with `isTarCommand` false, the tar
            // verbs went undeclared and the `-C`/`-xf` expansion never ran.
            val noValue = OffloadOutput.OUTPUT_FLAGS + fastioBooleanFlags(isTarCommand = true)
            var i = 0
            while (i < rawArgv.size) {
                val token = rawArgv[i]
                if (!token.startsWith("-") || token == "-") return token
                // `--key` and a rewritten `-limit` share the key namespace: the
                // rewrite produces `--limit`, so the value decision is the same
                // "is the long name a boolean flag" test for both spellings.
                val key = when {
                    token.startsWith("--") && !token.contains('=') -> token.substring(2)
                    token in SHORT_LONG_OPTIONS && !token.contains('=') -> token.substring(1)
                    else -> null
                }
                val nextToken = rawArgv.getOrNull(i + 1)
                val hasValue = nextToken != null && !nextToken.startsWith("-")
                val consumesValue = when {
                    token in SHORT_VALUE_OPTIONS -> hasValue
                    key != null -> key !in noValue && hasValue
                    else -> false
                }
                i += if (consumesValue) 2 else 1
            }
            return null
        }

        /**
         * [dv18-A A-01b] Raw single-dash tokens that take a VALUE, i.e. the raw
         * spellings [normalizeTarShorts] rewrites into a value-taking long form:
         * `-C <dir>` becomes `--directory <dir>`.
         *
         * `-f` is deliberately NOT here, and neither is any other letter: the
         * expander maps `f` to *nothing* (the archive must stay a positional, as
         * [normalizeTarShorts] documents) and every other group reads `-f` as
         * the boolean `--force` alias, so it never consumes the next token in
         * the parse this scan mirrors. The single-dash LONG spellings are not
         * here either — they already take a value by the [SHORT_LONG_OPTIONS]
         * rule above.
         *
         * ponytail: only the exact `-C` token is modelled, not a cluster that
         * carries `C` (`-Cf dir`). | 天花板: an invocation whose value-taking
         * short option sits inside a cluster AND before the subcommand. |
         * 升级触发: the first real invocation that does — widen to the cluster
         * letters the expander knows (`c`/`x`/`z`/`v` take no value, so only `C`
         * would still qualify).
         */
        private val SHORT_VALUE_OPTIONS = setOf("-C")

        /**
         * Single-dash long options an agent types out of `find`/`grep` habit,
         * rewritten to the `--` spelling OffloadArgs reads as `key value`.
         * Exact matches only, so a path that merely contains a dash is safe.
         */
        private val SHORT_LONG_OPTIONS = setOf(
            "-name", "-type", "-limit", "-max-entries", "-ignore-case",
        )

        internal fun normalizeLongOptionSpelling(argv: List<String>): List<String> =
            argv.map { if (it in SHORT_LONG_OPTIONS) "-$it" else it }

        /**
         * Expand `tar`'s clustered short options before the shared parse
         * (`-czf a.tar x` → `--create --gzip --file ... `), because OffloadArgs
         * reads any `-word` as ONE flag. Only `tar` gets this: `-rf` must keep
         * its meaning for `rm`/`cp`.
         *
         * `-f` is dropped rather than mapped to `--file`: its value (the
         * archive) must stay a POSITIONAL, and `--key value` would consume it
         * into `values`. `-C` maps to `--directory` (it also takes a value,
         * and the directory is not a positional). An unknown letter inside a
         * cluster leaves the whole token untouched, so the caller reports it
         * as an unknown option instead of silently dropping it.
         */
        internal fun normalizeTarShorts(argv: List<String>): List<String> {
            // [da1020-C] The guard calls the SAME helper as `isTarCommand` —
            // `firstOrNull() != "tar"` broke whenever a global output flag
            // (`--compact` / `-q` / `--quiet`) preceded the subcommand.
            if (firstSubcommandToken(argv) != "tar") return argv
            val out = ArrayList<String>(argv.size)
            for (token in argv) {
                if (token.length < 2 || token[0] != '-' || token.startsWith("--")) {
                    out.add(token)
                    continue
                }
                val expanded = ArrayList<String>(token.length)
                var known = true
                for (ch in token.substring(1)) {
                    when (ch) {
                        'c' -> expanded.add("--create")
                        'x' -> expanded.add("--extract")
                        'z' -> expanded.add("--gzip")
                        'v' -> expanded.add("--verbose")
                        'C' -> expanded.add("--directory")
                        'f' -> Unit   // the archive value stays a positional
                        else -> known = false
                    }
                }
                if (known) out.addAll(expanded) else out.add(token)
            }
            return out
        }

        private val HELP = """minis-fastio — file-intensive primitives run by the host process on the real filesystem, bypassing the PRoot syscall tax (~100-200µs/op → ~1µs/op).

Usage:
  minis-fastio du <path>...            Read-only: file/dir counts + apparent bytes per tree (JSON).
                                       Use instead of `du -sb` / `find | wc -l` / `find | xargs stat`
                                       when walking a large tree.
  minis-fastio find <path> [opts]      Read-only: walk a tree and list matches (compact JSON, capped).
                                       Use instead of `find <path> -type f | wc -l` / `find ... -name`
                                       / any `find` that stats every entry on a large tree: it pays
                                       the tax once per entry. A bare `find | wc -l` does no stat and
                                       is already cheap, so keep that one. Totals cover the WHOLE tree
                                       even when the listing is capped.
  minis-fastio grep <pattern> <path>...  Read-only: search file contents line by line.
                                       Pattern is java.util.regex (NOT PCRE / ripgrep): lookahead
                                       and bounded lookbehind work, variable-length lookbehind and
                                       PCRE verbs (\K, (*SKIP)) do not.
  minis-fastio rm [-r] <path>...       Destructive: recursive delete (-r required for directories).
  minis-fastio cp [-r] <src> <dst>     Write: copy a file, or a tree with -r.
  minis-fastio mv <src> <dst>          Write: rename (copy+delete only across filesystems).
  minis-fastio tar -cf <a.tar> <path>...   Write: pack into a POSIX ustar archive (-czf to gzip).
  minis-fastio tar -xf <a.tar> [-C dir]    Write: unpack (-xzf to gunzip; -C defaults to the current directory).
  The write primitives still pass through OffloadGate (one row each:
  minis-fastio (delete) / (copy) / (move) / (archive)) so a per-tool override
  stays possible, but their default level is BYPASS — the reachable set is the
  app's own sandbox, which the guest shell already writes to unrestricted.

Options:
  -r, --recursive    (rm/cp) descend into directories. `-rf` / `-fr` work too; for `rm`, `-f`
                     alone is accepted and ignored (a missing path is still reported as an error
                     — POSIX `rm -f` would stay silent). For `cp`/`mv`, `-f` / `--force` means
                     overwrite.
  --force, -f        (cp/mv) allow replacing an existing target. Without it an existing target is
                     refused with "already_exists" — an accidental overwrite has no undo.
  --name GLOB        (find) match the entry NAME (shell-style glob, e.g. '*.log'). `-name` works.
  --type f|d|l       (find) only regular files / directories / symlinks. `-type` works.
                     A hit's `type` is f (regular file) / d / l / o — `o` is anything else
                     (named pipe, socket, device), reported but never opened.
  -i, --ignore-case  (grep) case-insensitive match.
  -c, -x             (tar) create / extract. -f <archive> then names the archive; -C <dir> sets the
                     extraction directory. Clustered forms work: -cf, -czf, -xf, -xzf.
  -z, --gzip         (tar) gzip the archive (also accepted as the -z inside a cluster).
  --limit N          (find/grep) max hits to LIST (find default $DEFAULT_FIND_LIMIT, grep default $DEFAULT_GREP_LIMIT).
                     Totals are still counted over the whole tree.
  --max-entries N    Entry budget for the traversal (default $DEFAULT_MAX_ENTRIES); over budget → "truncated": true
  --compact, -q      Standard output shaping (see other minis-* tools)

Notes:
  - Paths are guest paths (/tmp, /var/minis/workspace, /var/minis/mounts/<name>, …) and are
    translated to the real host paths before use.
  - `du` bytes are the apparent sizes of FILE entries only — directories are counted but contribute
    no bytes. This matches the guest's PATH `du -sb`, which in this rootfs resolves to GNU coreutils
    9.5 (file entries only). busybox `du -sb` DOES count directory st_size and reads larger by
    dirs × directory-size — do not "fix" it back. `rm`'s freed_bytes uses the same file-only
    accounting, so a fully deleted tree reports freed_bytes == its `du` bytes.
  - `find`/`grep` emit COMPACT json (the hit list is the payload); `du`/`rm`/`cp`/`mv` emit indented
    json. "truncated": true means the output is incomplete — either the hit budget was reached
    ("matches_capped": true, the scan/list stops there) or the traversal budget ran out.
  - `grep` skips symlinks, binary files (NUL in the first 8 KiB), files larger than 8 MiB and
    special files (named pipe / socket / device — never opened: a writer-less FIFO blocks in
    open(2)); the per-file skip counts are reported so an empty result is diagnosable.
  - `cp`/`mv` copy a symlink as a LINK (never followed), refuse to overwrite without --force,
    refuse a target inside its own source, and treat an existing directory `dst` as "copy into it"
    (`cp -r a dir` → `dir/a`), like the shell.
  - `tar` writes POSIX ustar and is interoperable with the guest's busybox tar 1.37 in BOTH
    directions (verified, including names too long for the 100-byte field: ustar prefix split,
    and GNU `L` long-name entries beyond 155+100). Symlinks are stored as links; device nodes,
    fifos and sockets cannot be represented and are SKIPPED — the count and up to 20 names are
    reported under "skipped_special" so an archive that lacks them says so. An archive that is
    itself inside the packed tree is skipped and counted ("skipped_self"), never read while it
    grows. Hard links are not
    materialised on extract ("skipped_hardlink"): the file itself is still unpacked.
    Member names follow the ARGUMENT as spelled (`-cf a.tar src` stores `src/...`, NOT the
    resolved `/var/minis/workspace/src/...`), exactly as GNU/busybox do — with a leading `/`
    and leading `../` dropped the way both of them drop them — so whoever extracts the archive
    lands the tree where the caller meant.
  - `tar -x` is tar-slip guarded: an entry that is absolute, that climbs above the extraction
    root with `..`, or that writes THROUGH a symlink (one already on disk, or one the archive
    itself declares) is refused as "tar_slip". The whole archive is validated first, so a
    hostile entry appearing anywhere leaves the destination untouched — nothing is written.
    Extraction never follows a symlink to place a file; `--force` replaces the link itself.
  - `tar -x` refuses to replace an existing FILE or symlink without --force, but always reuses an
    existing DIRECTORY (re-extracting over a tree is the normal case).
  - Cost model: every call pays a FIXED round trip (~40 ms, the host handler plus the guest-side
    write/cat). On a handful of entries the guest's own tools win — reach for these primitives once
    a tree is large (thousands of entries), where the per-entry translation tax dominates.
  - Write primitives refuse: host /dev //proc //sys, user-mounted external folders as a write
    target (`mv` also an external source, since it deletes it), bind-mount roots as the thing
    being replaced, and `cp` without -r on a directory. Nothing outside the app sandbox is reachable.
  - All subcommands report JSON on stdout; exit code 1 when any path failed, 2 for a usage error.
"""
    }
}

// ── walk engine (top-level for JVM testability; pure, no instance state) ────

/**
 * Entry budget shared by every fastio walk (`du` / `find` / `grep`). One
 * implementation of "have we looked at enough entries" instead of one per
 * primitive — the three copies would be the drift this project keeps paying
 * for.
 */
internal interface EntryBudget {
    var visited: Long
    var truncated: Boolean
}

/** Returns true once the entry budget is exhausted (and flags truncation). */
internal fun charge(budget: EntryBudget, maxEntries: Long): Boolean {
    if (budget.visited >= maxEntries) {
        budget.truncated = true
        return true
    }
    budget.visited++
    return false
}

internal class WalkTotals : EntryBudget {
    var files = 0L
    var dirs = 0L
    var bytes = 0L
    override var visited = 0L
    override var truncated = false
    val errors = ArrayList<String>()
}

/**
 * Sum apparent sizes of FILE entries over the tree without following symlinks
 * (matching `du`'s default). Directories are counted but contribute NO bytes:
 * the guest's PATH `du -sb` resolves to GNU coreutils 9.5 in this rootfs, which
 * reports file entries only. Do not "align" this to busybox — busybox `du -sb`
 * DOES count directory st_size (in-sandbox fixture 2026-09-30: 5-byte file +
 * 2 dirs → GNU du -sb = 5, busybox du -sb = 6909; every directory here reports
 * st_size 3452). File-only accounting is also what keeps `rm`'s freed_bytes
 * cross-checkable against `du`'s bytes for the same tree.
 */
internal fun walkForSize(root: Path, totals: WalkTotals, maxEntries: Long) {
    try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (charge(totals, maxEntries)) return FileVisitResult.TERMINATE
                totals.dirs++
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (charge(totals, maxEntries)) return FileVisitResult.TERMINATE
                totals.files++
                totals.bytes += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (charge(totals, maxEntries)) return FileVisitResult.TERMINATE
                totals.errors.add("$file: ${exc.message}")
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: Exception) {
        totals.errors.add("$root: ${e.message}")
    }
}

/**
 * Post-order delete: children first, then the directory itself. Errors are
 * collected per entry and the walk continues — a single permission failure
 * deep in a tree must not abort the rest. freed_bytes counts FILE entries
 * only (directories are freed but contribute no bytes) — same accounting
 * as `du`, so a fully deleted tree reports freed_bytes == its `du` bytes.
 */
internal fun deleteTree(root: Path, totals: WalkTotals) {
    try {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    Files.delete(file)
                    totals.files++
                    totals.bytes += attrs.size()
                } catch (e: Exception) {
                    totals.errors.add("$file: ${e.message}")
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) {
                    totals.errors.add("$dir: ${exc.message}")
                    return FileVisitResult.CONTINUE
                }
                try {
                    Files.delete(dir)
                    totals.dirs++
                } catch (e: Exception) {
                    totals.errors.add("$dir: ${e.message}")
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                totals.errors.add("$file: ${exc.message}")
                return FileVisitResult.CONTINUE
            }
        })
    } catch (e: Exception) {
        totals.errors.add("$root: ${e.message}")
    }
}
