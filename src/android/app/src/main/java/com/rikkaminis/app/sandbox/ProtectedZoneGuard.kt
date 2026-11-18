package com.rikkaminis.app.sandbox

/**
 * [protect-zones-1118] Which parts of `/var/minis` an agent may delete freely,
 * and which parts need a narrower aim.
 *
 * Why this exists (measured, not hypothetical): in the prompt-eval harness
 * (`shared/work/prompteval-1118`, 2026-11-18) the production prompt answered
 * "把那些文件删掉" — an instruction with **no referent** — by running `rm` in
 * 6 of 8 runs, and one of those 16 delete calls reached outside the scratch
 * area: `rm -v /var/minis/shared/README.md`. The scratch area is regenerable;
 * `shared/` is the one the backup protects, and a backup only restores the
 * moment it ran — a file written and deleted in between is simply gone.
 *
 * ## What is refused — containers, not files
 *
 * The rule is deliberately NOT "refuse deletes in protected zones". Cleaning
 * `shared/work/<one-batch-dir>` is ordinary, correct work; refusing it would
 * make the agent lie about what it did. What is refused is a **recursive**
 * delete aimed at the zone root or at a whole immediate child of it — a
 * container, not "these files".
 *
 * Stated plainly, because the earlier KDoc let this read as broader than it is:
 *
 *  - a single named file is **never** refused (`rm /var/minis/shared/README.md`
 *    is allowed, and so is `rm /var/minis/memory/2026-11-18.md`);
 *  - a glob is **never** refused (`rm -rf /var/minis/shared/<star>` falls
 *    through — see the ceiling below);
 *  - a deep directory is never refused (`rm -rf /var/minis/shared/work/batch`).
 *
 * So this guard does **not** stop the delete that motivated it (one file). It
 * stops the shape that cannot be undone in one step: "remove the whole thing".
 * The narrow-file case is covered by the backup, not by this guard.
 *
 * ## The user's switch, and it is its own switch
 *
 * [ZoneDeleteGuardStore] — one flag, default **ON**, UI-only (deliberately not
 * in `ConfigRegistry`: an agent that gets refused here must not be able to
 * reach `minis-config` and switch its own guard off). It used to ride on the
 * auto-approve master switch, which was wrong in the direction that matters:
 * users turn auto-approve on to stop seeing dialogs, not to give up data
 * protection, so the guard degraded to a pure logging pass at exactly the
 * moment most deletes flow through (protection rate 0%). The two switches are
 * now independent, and both the shell path ([ExecutionCoordinator]) and the
 * path-resolved fastio path ([com.rikkaminis.app.sandbox.offload.FastioOffloadHandler])
 * read this same flag, so the two can never disagree about policy.
 *
 * ## The ceiling (unchanged, and it is lexical)
 *
 * `rm -rf $X`, `find … -delete`, `xargs rm`, interpreter one-liners and globs
 * are not recognised — see [refuseReason]'s doc. Guessing at them would refuse
 * work that is actually fine. The path-resolved check in the fastio handler is
 * the one that cannot be spelled around.
 */
internal object ProtectedZoneGuard {

    /**
     * Zones whose contents are either backed up or expensive to recreate.
     * `/var/minis/workspace`, `/tmp`, `offloads` and `browser` are deliberately
     * absent: they are per-session scratch by design (see the sandbox prompt's
     * own wording), and the user's rule is that scratch is discardable.
     */
    val ZONES: List<String> = listOf(
        "/var/minis/shared",
        "/var/minis/memory",
        "/var/minis/skills",
        "/var/minis/mcp-servers",
        "/var/minis/mounts",
    )

    /** The zone a guest path belongs to (longest prefix wins), or null. */
    fun zoneOf(guestPath: String): String? =
        ZONES.filter { guestPath == it || guestPath.startsWith("$it/") }
            .maxByOrNull { it.length }

    /**
     * True for the shapes whose removal cannot be described as "these files":
     * the zone root itself, or one of its immediate children (a container such
     * as `shared/work`). Deeper targets are ordinary work and are allowed.
     */
    fun isWideTarget(guestPath: String): Boolean {
        val zone = zoneOf(guestPath) ?: return false
        val rest = guestPath.removePrefix(zone).trim('/')
        return rest.isEmpty() || !rest.contains('/')
    }

    /**
     * The whole policy in one predicate, so the shell path and the fastio path
     * cannot drift: a **recursive** removal of a wide target is refused.
     *
     * `mv` counts as recursive — it has no `-r`, it always moves a whole tree,
     * and it *destroys its source*, so `mv <zone>/<child> /tmp/` is the same
     * delete as `rm -rf` as far as the zone is concerned. Accepted cost: a
     * `mv <zone>/<single-file> <dst>` is refused too, because a lexical guard
     * cannot tell a file from a directory. The cost of that false refusal is
     * one retry with an absolute path; the cost of the miss is a whole
     * directory that no backup will bring back.
     *
     * Callers gate on [ZoneDeleteGuardStore.isEnabled] — the switch is read by
     * the caller, never inside this object, so the guard stays a pure function
     * of (path, recursive).
     */
    fun isRefusedDelete(guestPath: String, recursive: Boolean): Boolean =
        recursive && isWideTarget(guestPath)

    /** Verbs that delete their last argument or a `-r` tree. */
    private val DESTRUCTIVE = setOf("rm", "rmdir", "unlink", "shred")

    /**
     * Prefixes that do not change which verb runs.
     *
     * `busybox` is here because the rootfs ships one and `busybox rm -rf <zone>`
     * is a natural way to spell the same delete — it used to be read as a verb
     * called `busybox` and fell straight through. The process-control entries
     * (`nice`, `timeout`, `setsid`, …) are here for the same reason one level
     * up: each takes its own argument and then runs the rest of the line, so
     * `timeout 5 rm -rf <zone>` is the same delete as the bare form.
     *
     * Enumerating names is NOT the judgement — the lexer is. Spraying
     * `\timeout`, `"timeout"`, `/usr/bin/timeout` at the old string compare is
     * what made the last round of this bug: the word arriving here has already
     * had its quotes and backslashes resolved, so a plain set membership is a
     * decision about the program's name and nothing else.
     */
    private val WRAPPERS = setOf(
        "sudo", "doas", "env", "time", "nohup", "command", "exec", "busybox",
        "nice", "ionice", "timeout", "setsid", "stdbuf", "taskset", "chrt",
    )

    /**
     * Wrapper options that take a value, so the value is not mistaken for the
     * verb: `env -u FOO rm -rf x`, `sudo -u root rm -rf x`, `taskset -c 0-3 rm`.
     *
     * Only options that really consume the NEXT word belong here — a missing
     * entry costs one wrapper form, an entry that is actually a boolean flag
     * eats the verb and hands back a bypass (`env -i rm …` was exactly that
     * shape, so `-i` is deliberately absent).
     */
    private val WRAPPER_VALUE_FLAGS = setOf(
        "-u", "--unset", "-C", "--chdir", "-S", "--split-string",
        "-n", "--adjustment", "-c", "--cpu-list", "-p", "--pid",
        "-k", "--kill-after", "-s", "--signal",
    )

    /** `FOO=1` / `_x=2` — an assignment, never the verb, at any position. */
    private val ASSIGNMENT = Regex("""[A-Za-z_][A-Za-z0-9_]*=.*""")

    /** A wrapper's own numeric argument: `timeout 5`, `chrt 50`, `taskset 0x3`. */
    private val WRAPPER_NUMBER = Regex("""[0-9][0-9A-Za-z.,:_+-]*""")

    /** Sentinel word standing in for a redirection operator inside a statement. */
    private const val REDIRECT = "\u0000redir"

    /**
     * Flags that make `rm`/`mv` act on a whole tree.
     *
     * Judged by CONTENT, not by a fixed list: `-rf`, `-fr`, `-irf`, `-rfv` are
     * all "short option cluster containing r/R", and the old literal set let
     * every spelling it did not enumerate through (`rm -rfv <zone>` deleted the
     * zone). Long options stay literal — `--force` contains an `r` and must not
     * be read as recursive.
     */
    private fun isRecursiveFlag(flag: String): Boolean = when {
        !flag.startsWith("-") -> false
        flag.startsWith("--") -> flag == "--recursive"
        else -> flag.drop(1).any { it == 'r' || it == 'R' }
    }

    private fun isRecursive(verb: String, flags: List<String>): Boolean =
        verb == "mv" || verb == "rmdir" || verb == "shred" || flags.any { isRecursiveFlag(it) }

    // ────────────────────────────────────────────────────────────────────────
    // Reading a command as words: the lexical pass.
    //
    // The first version of this guard split on `\s+` and compared the raw
    // token against `rm`. That is not a shell. Quoting, backslash escapes,
    // redirections and assignment prefixes were all invisible to it, and each
    // of them was a hole: `\rm`, `"rm"`, `nice -n 10 rm`, `FOO=1 rm`,
    // `rm -rf "/var/minis/shared/my folder"` — all the same delete, none of
    // them recognised. They are now one pass that produces the literal words
    // the shell would hand to exec, so every later judgement is a comparison of
    // program names and path strings, not a reading of raw text.

    /**
     * A command split into statements of words, with quoting resolved.
     *
     * [parseOk] is false when a quote never closes. Then [statements] is a
     * prefix of the real command and nothing after the stray quote can be
     * trusted, so the caller must fail closed rather than read the words.
     */
    internal data class ShellLex(val statements: List<List<String>>, val parseOk: Boolean)

    /**
     * [F-03] [F-04] The lexical pass over one command line.
     *
     * Handles what `split(Regex("\\s+"))` could not: `'…'` and `"…"` (whitespace
     * inside a quote does not split, and the quote itself is dropped from the
     * word it wraps), backslash escaping outside quotes (`\r` → `r`, `\ ` →
     * space), and `<`/`>` redirection (the operator and its target are removed,
     * so `2>&1` or `> x` never become arguments). Words keep their escape
     * results — `\rm` and `"rm"` and `/bin/rm` all arrive as `rm`.
     */
    internal fun shellLex(command: String): ShellLex {
        val statements = ArrayList<List<String>>()
        var words = ArrayList<String>()
        var word = StringBuilder()
        var wordStarted = false
        var quote: Char? = null
        var i = 0

        fun endWord() {
            if (wordStarted) {
                words.add(word.toString())
                word = StringBuilder()
                wordStarted = false
            }
        }

        fun endStatement(): Unit {
            endWord()
            if (words.isNotEmpty()) {
                statements.add(stripRedirections(words))
                words = ArrayList()
            }
        }

        while (i < command.length) {
            val c = command[i]
            when {
                quote != null -> {
                    if (c == quote) {
                        quote = null
                        wordStarted = true
                    } else if (c == '\\' && quote == '"' && i + 1 < command.length) {
                        i++
                        word.append(command[i])
                        wordStarted = true
                    } else {
                        word.append(c)
                        wordStarted = true
                    }
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    wordStarted = true
                }
                c == '\\' -> {
                    if (i + 1 < command.length) {
                        i++
                        word.append(command[i])
                    } else {
                        word.append(c)
                    }
                    wordStarted = true
                }
                c == '<' || c == '>' -> {
                    // `2>` — the file-descriptor digits are not an argument.
                    if (wordStarted && word.isNotEmpty() && word.all { it.isDigit() }) {
                        word = StringBuilder()
                        wordStarted = false
                    }
                    endWord()
                    while (i + 1 < command.length && command[i + 1] in "<>&") i++
                    words.add(REDIRECT)
                }
                c == ' ' || c == '\t' || c == '\r' -> endWord()
                c == '\n' || c == ';' || c == '&' || c == '|' -> endStatement()
                else -> {
                    word.append(c)
                    wordStarted = true
                }
            }
            i++
        }
        if (quote != null) {
            endStatement()
            return ShellLex(statements, parseOk = false)
        }
        endStatement()
        return ShellLex(statements, parseOk = true)
    }

    /** Drops redirection operators and the words they target (see [shellLex]). */
    private fun stripRedirections(words: List<String>): List<String> {
        if (words.none { it == REDIRECT }) return words
        val kept = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            if (words[i] == REDIRECT) {
                i += 2 // the operator and what it names
                continue
            }
            kept.add(words[i])
            i++
        }
        return kept
    }

    /**
     * The index of the verb that runs the statement, or -1 when the statement
     * opens with wrappers/assignments that never reach a verb.
     *
     * Assignment tokens are skipped wherever they appear (`FOO=1 rm …`,
     * `env -i FOO=1 rm …`), and a wrapper word is followed by its own
     * arguments — options (value-taking ones consume their value), numbers and
     * more assignments — before the next verb is looked for.
     */
    private fun verbIndex(words: List<String>): Int {
        var i = 0
        while (i < words.size) {
            if (ASSIGNMENT.matches(words[i])) {
                i++
                continue
            }
            val name = words[i].substringAfterLast('/')
            if (name in WRAPPERS) {
                i = skipWrapperArgs(words, i + 1)
                continue
            }
            return i
        }
        return -1
    }

    /** Skips everything after a wrapper that belongs to the wrapper, not the verb. */
    private fun skipWrapperArgs(words: List<String>, from: Int): Int {
        var i = from
        while (i < words.size) {
            val w = words[i]
            when {
                ASSIGNMENT.matches(w) -> i++
                w.startsWith("-") -> i += if (w.contains('=')) 1 else if (w in WRAPPER_VALUE_FLAGS) 2 else 1
                WRAPPER_NUMBER.matches(w) -> i++
                else -> return i
            }
        }
        return i
    }

    /**
     * [F-01] The source arguments of an `mv`.
     *
     * `mv` destroys its sources, so every source is a target for the guard —
     * not just the first one. `mv -t DIR a b` puts DIR first and makes **all**
     * of `a b` sources, and `mv a b c` has two sources and one destination, so
     * with no `-t` the LAST positional is the destination.
     *
     * Flags are consumed first (long options with `=`, short options with an
     * attached value), and a bare `--` passes the rest through untouched.
     */
    internal fun mvSources(args: List<String>): List<String> {
        val positionals = ArrayList<String>()
        var targetForm = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-t" || a == "--target-directory" -> {
                    targetForm = true
                    i += 2
                }
                a.startsWith("--target-directory=") -> {
                    targetForm = true
                    i++
                }
                a.startsWith("-t") && !a.startsWith("--") -> {
                    // `-tDIR`, `-t DIR`'s attached form; `-T` is the opposite.
                    targetForm = true
                    i++
                }
                a == "--" -> i++
                a.startsWith("-") && a.length > 1 -> i++
                else -> {
                    positionals.add(a)
                    i++
                }
            }
        }
        return if (targetForm) positionals else positionals.dropLast(1)
    }

    /**
     * The fail-closed answer for a command whose quotes do not balance.
     *
     * The words of such a command cannot be trusted, so no per-argument
     * reasoning applies. The guard refuses only when BOTH of those hold: a
     * zone literal that resolves to a **wide** target (the zone root or a whole
     * immediate child, read straight out of the raw text) and a word in
     * [DESTRUCTIVE] or `mv` somewhere in the line. Narrow targets still fall
     * through, which keeps `rm -rf … && echo Don't` — an unbalanced apostrophe
     * in ordinary text — from being refused for what it merely mentions.
     */
    private fun unbalancedFallback(command: String): WideDeleteHit? {
        val wide = ZONES.firstOrNull { zone ->
            command.contains(zone) && zoneWideLiteral(command, zone) != null
        } ?: return null
        val verbSeen = command.split(Regex("""[\s;&|\n]+""")).any { token ->
            val word = token.trim('"', '\'', '\\')
            val name = word.substringAfterLast('/')
            name in DESTRUCTIVE || name == "mv"
        }
        return if (verbSeen) WideDeleteHit(zoneWideLiteral(command, wide)!!, cwdKnown = true) else null
    }

    /** The wide path written immediately after [zone] in the raw text, or null. */
    private fun zoneWideLiteral(command: String, zone: String): String? {
        val start = command.indexOf(zone)
        var end = command.length
        for (i in start + zone.length until command.length) {
            val c = command[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '|' || c == '&' ||
                c == '"' || c == '\'' || c == '\\'
            ) {
                end = i
                break
            }
        }
        return absoluteGuestPath(command.substring(start, end), null)?.let { guest ->
            if (isWideTarget(guest)) guest else null
        }
    }

    /**
     * One wide delete the scan found, plus whether the path came out of a
     * resolution that needed no guess.
     *
     * [cwdKnown] is false only for the undecidable case: a RELATIVE target with
     * no `cd` in the same command. An absolute target is known even when the
     * shell's directory is not — nothing about its verdict depends on the cwd.
     */
    internal data class WideDeleteHit(val path: String, val cwdKnown: Boolean)

    /**
     * The first wide target this command would delete inside a protected zone,
     * or null. Single source for both the refusal and the audit line — the two
     * must never disagree about what counts as a wide delete.
     *
     * The command is read by [shellLex] first: quoting, backslash escapes,
     * redirections and assignment prefixes are resolved into literal words
     * before anything is judged. A command whose quotes do not balance cannot
     * be read that way; that case fails closed through [unbalancedFallback]
     * rather than being waved through as "unknown".
     *
     * `cd` statements are tracked: `cd /var/minis/shared && rm -rf work` is the
     * same delete as the absolute form (the first version of this guard missed
     * it and its own test caught that).
     *
     * [cwd] is the shell's working directory, or **null when it is unknown**.
     * The persistent shell keeps its cwd inside the guest (proot `-w /root`,
     * then a `cd`-trap file) and the app process cannot read it, so a bare
     * relative target has no resolvable base. The old signature defaulted to
     * `/root` and silently judged `rm -rf work` as `/root/work` — a false
     * negative whenever the shell actually stood in a zone. Unknown is handled
     * as unknown: an absolute target is still decided, a relative one is
     * checked against every base that could make it wide (the zones and
     * `/var/minis`), and a hit is reported with `cwdKnown = false` so the
     * refusal can ask for an absolute path instead of guessing.
     */
    internal fun wideDeleteHit(command: String, cwd: String? = null): WideDeleteHit? {
        val lex = shellLex(command)
        if (!lex.parseOk) return unbalancedFallback(command)
        var current: String? = cwd
        for (words in lex.statements) {
            if (words.isEmpty()) continue
            val i = verbIndex(words)
            if (i < 0) continue
            val verb = words[i].substringAfterLast('/')
            if (verb == "cd" || verb == "pushd") {
                current = words.getOrNull(i + 1)?.let { absoluteGuestPath(it, current) }
                continue
            }
            if (verb !in DESTRUCTIVE && verb != "mv") continue
            val args = words.drop(i + 1)
            // A non-recursive delete names files, so an immediate child of a
            // zone (a README, a log) is ordinary work. Recursion is what turns
            // the same path into "remove everything under here".
            if (!isRecursive(verb, args.filter { it.startsWith("-") && it != "--" })) continue
            // `mv a b` destroys a; b is where a LANDS (`mv x /var/minis/shared`
            // moves x INTO shared, which is not a delete of shared). [mvSources]
            // decides which of the positionals destroys and which is land.
            val targets = if (verb == "mv") mvSources(args) else args
            for (arg in targets) {
                if (arg.startsWith("-")) continue
                // Quotes are peeled before deciding whether the token is
                // absolute: `rm -rf "/var/minis/shared"` needs no cwd at all,
                // and routing it through the guess-the-base branch would make
                // its verdict depend on a fact it does not use.
                if (current != null || isAbsoluteLiteral(arg)) {
                    val guest = absoluteGuestPath(arg, current) ?: continue
                    if (isWideTarget(guest)) {
                        // An absolute target needs no cwd, so the verdict is
                        // fully determined; only a relative one inherits doubt.
                        return WideDeleteHit(guest, cwdKnown = current != null || isAbsoluteLiteral(arg))
                    }
                    continue
                }
                // cwd unknown + relative token: undecidable by construction, so
                // refuse when ANY base that could hold a zone makes it wide.
                val candidate = candidateWideTarget(arg) ?: continue
                return WideDeleteHit(candidate, cwdKnown = false)
            }
        }
        return null
    }

    /**
     * The absolute path [token] would have if the shell stood in one of the
     * bases a zone can hang off, when that resolution is a wide target.
     *
     * Only used when the cwd is unknown. A single-segment token (`work`,
     * `build`) is wide against `/var/minis/shared` and is therefore refused —
     * the accepted cost of not knowing where the shell is. Prefixing the
     * delete with its own `cd`, or spelling the absolute path, resolves it.
     */
    private fun candidateWideTarget(token: String): String? {
        for (base in ZONES + "/var/minis") {
            val guest = absoluteGuestPath(token, base) ?: continue
            if (isWideTarget(guest)) return guest
        }
        return null
    }

    /**
     * The refusal text for a hit [wideDeleteHit] already produced.
     *
     * Split from [refuseReason] so the caller scans the command ONCE: the
     * coordinator needs both the target (for the audit line) and the message,
     * and two scans could in principle disagree.
     *
     * The text is written for the model that will read it, so it says what to
     * do instead and nothing else: no "you can turn this off in Settings"
     * pointer, which invited the agent to talk the user into disabling the
     * guard to finish its own task.
     */
    fun refusalFor(hit: WideDeleteHit): String {
        val zone = zoneOf(hit.path)
        val base = "refusing to delete '${hit.path}': it is a protected zone" +
            " (or a whole subdirectory of one) — name the files to remove, or move them aside first."
        val noReferent = if (zone != null && hit.path == zone) {
            " If you were asked to clean this up, confirm which files the user meant before retrying."
        } else {
            ""
        }
        val cwdNote = if (hit.cwdKnown) "" else {
            " The shell's working directory is not visible here, so a relative path cannot be" +
                " checked — retry with an absolute path, or put the cd in the same command."
        }
        return base + noReferent + cwdNote
    }

    /**
     * A one-line refusal for a shell command that deletes a wide target inside
     * a protected zone, or null.
     *
     * Recognised: `rm [-r] <path…>`, `rmdir <path>`, `mv <src> <dst>` where a
     * path argument is written as a literal (absolute, or relative to [cwd]).
     * Everything else — `$VAR` targets, globs, `find -delete`, `xargs rm`,
     * interpreter one-liners — falls through, because guessing at them would
     * refuse work that is actually fine. The path-resolved check in the fastio
     * handler covers those callers that go through it.
     */
    fun refuseReason(command: String, cwd: String? = null): String? =
        wideDeleteHit(command, cwd)?.let { refusalFor(it) }

    /**
     * True when [token] names an absolute path once a matching pair of
     * surrounding quotes is peeled — the same peeling [absoluteGuestPath] does,
     * so the two decisions cannot disagree about whether the cwd is needed.
     */
    private fun isAbsoluteLiteral(token: String): Boolean =
        unquote(token).startsWith("/")

    /** One matching pair of surrounding quotes, removed. */
    private fun unquote(token: String): String = when {
        token.length >= 2 && token.startsWith("\"") && token.endsWith("\"") ->
            token.substring(1, token.length - 1)
        token.length >= 2 && token.startsWith("'") && token.endsWith("'") ->
            token.substring(1, token.length - 1)
        else -> token
    }

    /**
     * Literal path → guest absolute path, or null when it is not a literal (or
     * when it is relative and [cwd] is unknown).
     *
     * One matching pair of surrounding quotes is peeled first: `rm -rf
     * "/var/minis/shared"` is the same command as the bare form, and treating
     * the quotes as "not a literal" let every quoted spelling through. What
     * remains must still be quote-free and free of `$`, backtick, `*`, `?`,
     * `[`, `{` — those are the shapes whose value the guard cannot see.
     */
    fun absoluteGuestPath(token: String, cwd: String?): String? {
        if (token.isEmpty()) return null
        val literal = unquote(token)
        if (literal.isEmpty()) return null
        // Not a literal: variable, glob, command substitution, unbalanced quote.
        if (literal.any { it in "$\"'`*?[{" }) return null
        val raw = if (literal.startsWith("/")) {
            literal
        } else {
            val base = cwd ?: return null
            "$base/$literal"
        }
        val parts = ArrayList<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
