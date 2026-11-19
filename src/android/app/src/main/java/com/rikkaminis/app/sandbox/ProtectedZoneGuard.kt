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
 *  - the PARENT of the zones is refused only as the whole thing: `rm -rf
 *    /var/minis` is one command that removes all five zones, so it is
 *    refused, while `rm -rf /var/minis/logs` and
 *    `rm -rf /var/minis/attachments` stay ordinary cleanup (equality-only
 *    matching — see [ROOT_ONLY_ZONES]).
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
 * ## The ceiling (lexical, and stated rather than hidden)
 *
 * `rm -rf $X`, `find … -delete`, `xargs rm`, interpreter one-liners, globs and
 * brace expansion (`<zone>/{a,b}`) are not recognised — see [refuseReason]'s
 * doc. Guessing at them would refuse work that is actually fine. `eval rm -rf
 * <zone>` IS recognised (the first word after `eval` is the verb), but `eval
 * "rm -rf <zone>"` is not: the whole command arrives as one word, and seeing
 * through it would mean parsing nested shell. The path-resolved check in the
 * fastio handler is the one that cannot be spelled around.
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

    /**
     * [F-01] Containers that are only themselves protected, never their
     * children.
     *
     * `rm -rf /var/minis` removes **all five** zones in one command, and it
     * used to fall straight through: the parent of every zone was in no list,
     * and [isWideTarget] only looked at paths *under* a zone. It is the single
     * most destructive spelling the guard exists for.
     *
     * Adding `/var/minis` to [ZONES] would be the wrong fix: [ZONES] means
     * "the root, or any immediate child, is a container", so it would also
     * refuse `/var/minis/logs` and `/var/minis/attachments` — the user's own
     * logs and attachments, which are ordinary cleanup (and the model is told
     * to delete them). So these match by EQUALITY only.
     *
     * `/var` is included for the same reason one level further up: `rm -rf
     * /var` takes the zones with it (plus the rootfs's `apk` database), while
     * equality-only matching leaves `rm -rf /var/tmp` and `rm -rf
     * /var/cache/apk` alone.
     */
    private val ROOT_ONLY_ZONES: List<String> = listOf("/var/minis", "/var")

    /**
     * Every literal a raw-text scan must look for: the containers in [ZONES]
     * plus their parents. Order is irrelevant — the path is re-read from the
     * text at each occurrence — but the parents are appended so a zone literal
     * is found by the shorter spelling too.
     */
    private val ALL_ZONES: List<String> = ZONES + ROOT_ONLY_ZONES

    /**
     * The zone a guest path belongs to (longest prefix wins), or null.
     *
     * Deliberately knows nothing about [ROOT_ONLY_ZONES]: those match only
     * themselves, so reporting them as "the zone this path belongs to" would
     * make `/var/minis/logs` look like a container.
     */
    fun zoneOf(guestPath: String): String? =
        ZONES.filter { guestPath == it || guestPath.startsWith("$it/") }
            .maxByOrNull { it.length }

    /**
     * True for the shapes whose removal cannot be described as "these files":
     * the zone root itself, one of its immediate children (a container such as
     * `shared/work`), or a [ROOT_ONLY_ZONES] entry itself. Deeper targets are
     * ordinary work and are allowed.
     */
    fun isWideTarget(guestPath: String): Boolean {
        val path = if (guestPath.length > 1) guestPath.trimEnd('/') else guestPath
        // [F-01] `/var/minis` and `/var`: the whole thing, and nothing below it.
        if (path in ROOT_ONLY_ZONES) return true
        val zone = zoneOf(path) ?: return false
        val rest = path.removePrefix(zone).trim('/')
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
        // [F-04] `eval rm -rf <zone>` — after `eval` the first word IS the verb,
        // so peeling it is enough to see the delete. `eval "rm -rf <zone>"`
        // (the whole command quoted into one word) stays a ceiling: the verb
        // word is then the entire command text, and seeing through it would
        // mean parsing nested shell.
        "eval",
    )

    /**
     * Wrapper options that take a value, so the value is not mistaken for the
     * verb: `env -u FOO rm -rf x`, `sudo -u root rm -rf x`, `stdbuf -oL rm`.
     *
     * Keyed BY WRAPPER, not one flat set: `-i` means "ignore the environment"
     * (a boolean) for `env` but "input buffer size" (takes a value) for
     * `stdbuf`. A flat set cannot hold both, and getting "does this option eat
     * a word" wrong in either direction creates a hole — `env -i` eating the
     * verb is exactly that shape, which is why it is absent from `env`'s row.
     *
     * Only options that really consume the NEXT word belong in a row; attached
     * forms (`-oL`, `-c2`, `--signal=KILL`) are handled by the caller.
     */
    private val WRAPPER_VALUE_FLAGS: Map<String, Set<String>> = mapOf(
        "env" to setOf("-u", "--unset", "-C", "--chdir", "-S", "--split-string"),
        "sudo" to setOf(
            "-u", "--user", "-g", "--group", "-p", "--prompt", "-C", "--close-from",
            "-h", "--host", "-r", "--role", "-t", "--type", "-U", "--other-user",
            "-T", "--command-timeout", "-D", "--chdir", "-R", "--chroot",
            "-c", "--login-class", "-a", "--auth-type",
        ),
        "doas" to setOf("-u", "-C"),
        "stdbuf" to setOf("-i", "-o", "-e"),
        "exec" to setOf("-a"),
        "ionice" to setOf("-c", "-n", "-p"),
        "chrt" to setOf("-p", "--pid"),
        "nice" to setOf("-n", "--adjustment"),
        "time" to setOf("-o", "--output", "-f", "--format"),
        "taskset" to setOf("-c", "--cpu-list", "-p", "--pid"),
        "timeout" to setOf("-k", "--kill-after", "-s", "--signal"),
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
     * [F-02] [F-03] [F-04] The lexical pass over one command line.
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
        // [F-02] Outer word lists of the `$(` substitutions currently open.
        // A stack, because `$(cat $(echo x))` nests.
        val outerWords = ArrayList<ArrayList<String>>()
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
                // [F-03] Shell SYNTAX braces and parentheses are separators,
                // not parts of words. `(rm -rf <zone>)` used to arrive as the
                // word `(rm` (not a verb), and `{ rm …; }` opened with a `{`
                // that was read as the verb, so both spellings fell through.
                //
                // [F-02] THREE kinds of `(`, and only one of them ends a
                // statement. The first version treated all of them alike, and
                // that is the P0 this branch carries: `rm -rf $(echo x) …
                // <zone>` split at `(` into three statements, so `<zone>`
                // landed in one with no verb and the delete was allowed.
                //
                //   `$((`  arithmetic — nothing runs inside, one opaque word.
                //   `$(`   substitution — the interior RUNS (its words are
                //          judged on their own), but what follows `)` still
                //          belongs to the outer statement.
                //   `(`    subshell/group — a real statement boundary.
                c == '(' -> {
                    if (wordStarted && word.length == 1 && word[0] == '$') {
                        word = StringBuilder()
                        wordStarted = false
                        if (i + 1 < command.length && command[i + 1] == '(') {
                            // `$((…))`: an expression, not a command. Kept as
                            // one word that carries `$`, so [absoluteGuestPath]
                            // refuses to read it as a path — the same treatment
                            // `$VAR` gets.
                            val close = arithmeticEnd(command, i)
                            // Keep the `$` in the word (it is at `i - 1`), so the
                            // word is visibly a non-literal to
                            // [absoluteGuestPath] and to the verb scan.
                            word.append(command, i - 1, close + 1)
                            wordStarted = true
                            i = close
                        } else {
                            // Save the outer words, lex the interior as its own
                            // statements, and resume them at the matching `)`.
                            outerWords.add(words)
                            words = ArrayList()
                        }
                    } else {
                        endWord()
                        endStatement()
                    }
                }
                c == ')' -> {
                    if (outerWords.isNotEmpty()) {
                        endWord()
                        if (words.isNotEmpty()) statements.add(stripRedirections(words))
                        words = outerWords.removeAt(outerWords.size - 1)
                    } else {
                        endWord()
                        endStatement()
                    }
                }
                // A brace INSIDE a word is brace expansion (`<zone>/{a,b}`),
                // whose value the guard cannot see — that is the documented
                // ceiling, and splitting it would invent paths nobody wrote.
                // Only a brace that starts a word is the grouping form.
                c == '{' || c == '}' -> if (wordStarted) word.append(c) else endStatement()
                else -> {
                    word.append(c)
                    wordStarted = true
                }
            }
            i++
        }
        if (quote != null || outerWords.isNotEmpty()) {
            // An unclosed `$(`, like an unclosed quote, means the tail of the
            // line cannot be read as words: fail closed through
            // [unbalancedFallback] instead of judging a prefix.
            endStatement()
            return ShellLex(statements, parseOk = false)
        }
        endStatement()
        return ShellLex(statements, parseOk = true)
    }

    /**
     * [F-02] The index of the `)` that closes the arithmetic expansion opened
     * at [open] (`$((` — the second `(` sits at `open + 1`).
     *
     * Nested parentheses inside the expression are balanced; the terminator is
     * the `))` pair. An unterminated expansion swallows the rest of the line
     * (the resulting word carries `$` and is never read as a path).
     */
    private fun arithmeticEnd(command: String, open: Int): Int {
        var level = 0
        var j = open + 2
        while (j < command.length) {
            when (command[j]) {
                '(' -> level++
                ')' -> {
                    if (level == 0) {
                        return if (j + 1 < command.length && command[j + 1] == ')') j + 1 else j
                    }
                    level--
                }
            }
            j++
        }
        return command.length - 1
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
                i = skipWrapperArgs(words, i + 1, name)
                continue
            }
            return i
        }
        return -1
    }

    /** Skips everything after a wrapper that belongs to the wrapper, not the verb. */
    private fun skipWrapperArgs(words: List<String>, from: Int, wrapper: String): Int {
        val valueFlags = WRAPPER_VALUE_FLAGS[wrapper].orEmpty()
        var i = from
        while (i < words.size) {
            val w = words[i]
            when {
                ASSIGNMENT.matches(w) -> i++
                w.startsWith("-") -> i += if (w.contains('=')) 1 else if (w in valueFlags) 2 else 1
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
                a == "--" -> i++
                a.startsWith("--target-directory") -> {
                    targetForm = true
                    i += if (a.contains('=')) 1 else 2
                }
                // [F-05] `--suffix` is the long form of `-S` and takes a value
                // the same way: without this branch its value is read as a
                // positional (and then as a source, or as the destination, with
                // the rest of the command misread behind it).
                a == "--suffix" || a.startsWith("--suffix=") -> i += if (a.contains('=')) 1 else 2
                a.startsWith("-") && a.length > 1 -> {
                    // [F-02] `-ft` / `-vtf` / `-tf`: a short option CLUSTER that
                    // contains `t` is the target-directory form too. The old
                    // check only knew the word `-t` / the `-tDIR` prefix, so
                    // `-ft` took the generic branch, `targetForm` stayed false
                    // and the real source was dropped as the destination.
                    val (isTarget, valueIsNextWord) = mvShortCluster(a.substring(1))
                    if (isTarget) targetForm = true
                    i += if (valueIsNextWord) 2 else 1
                }
                else -> {
                    positionals.add(a)
                    i++
                }
            }
        }
        return if (targetForm) positionals else positionals.dropLast(1)
    }

    /**
     * [F-02] [F-05] What one short option cluster (`-ft`, `-t/tmp`, `-S.bak`, `-S`, `-T`)
     * means for `mv`: (is the target-directory form, does it eat the next word).
     *
     * `t` takes a directory — the rest of the cluster is that directory when
     * something follows it (`-t/tmp`), otherwise it is the next word
     * (`-ft /tmp …`). `T` is `--no-target-directory`, the OPPOSITE flag, and a
     * cluster that reaches an `T` before a `t` is not the target form.
     *
     * `S` is `--suffix`, and it takes a value too: an attached suffix stays in
     * the same word (`-S.bak` — every character after `S` is the suffix, so
     * no later letter in the word can be the target flag), while a bare `S`
     * at the END of the cluster takes the NEXT word as the suffix
     * (`-S .bak`). Without the last clause the value was read as a source
     * (`mv -S <zone> a b` refused to move `a` into `b`).
     */
    private fun mvShortCluster(cluster: String): Pair<Boolean, Boolean> {
        for (index in cluster.indices) {
            when (cluster[index]) {
                'T' -> return Pair(false, false)
                't' -> return Pair(true, index == cluster.lastIndex)
                'S' -> return Pair(false, index == cluster.lastIndex)
            }
        }
        return Pair(false, false)
    }

    /**
     * The characters that delimit a raw-text word or a raw-text path literal.
     *
     * [F-03a] ONE set, shared by both halves of the fail-closed path — the word
     * split in [unbalancedFallback] and the path scan in [zoneWideLiteral]. Two
     * differing copies is exactly how `(rm -rf <zone>) && echo 'unclosed`
     * slipped through it: the word split only knew whitespace, so the verb
     * arrived as `(rm`, and the path scan did not stop at `)`, so the path
     * arrived as `<zone>)` — neither matched, and the line was allowed.
     */
    private const val RAW_WORD_DELIMITERS = " \t\r\n;&|(){}<>"

    /**
     * [F-01] True for a character that may legitimately stand in front of a
     * path literal: nothing (the start of the line), a delimiter, a quote or
     * an escape (the path is quoted), or `=` (an assignment value).
     */
    private fun isRawPathBoundary(c: Char): Boolean =
        c in RAW_WORD_DELIMITERS || c == '"' || c == '\'' || c == '\\' || c == '='

    /**
     * The words of a command whose quotes do not balance, split on
     * [RAW_WORD_DELIMITERS] — the same delimiter set the path scan uses, so a
     * verb and a path can never disagree about what the line contains.
     */
    private fun rawWords(command: String): List<String> {
        val out = ArrayList<String>()
        var word = StringBuilder()
        for (c in command) {
            if (c in RAW_WORD_DELIMITERS) {
                if (word.isNotEmpty()) out.add(word.toString())
                word = StringBuilder()
            } else {
                word.append(c)
            }
        }
        if (word.isNotEmpty()) out.add(word.toString())
        return out
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
        val wide = ALL_ZONES.firstOrNull { zone ->
            command.contains(zone) && zoneWideLiteral(command, zone) != null
        } ?: return null
        val verbSeen = rawWords(command).any { token ->
            val word = token.trim('"', '\'', '\\')
            val name = word.substringAfterLast('/')
            name in DESTRUCTIVE || name == "mv"
        }
        return if (verbSeen) WideDeleteHit(zoneWideLiteral(command, wide)!!, cwdKnown = true) else null
    }

    /**
     * The wide path named at ANY occurrence of [zone] in the raw text, or null.
     *
     * [F-06] Every occurrence is read, not just the first. A command can name
     * a deep path early and delete a wide one later — `ls <zone>/a/b && rm -rf
     * <zone> '` is one stray quote away from being unreadable — and stopping at
     * the first mention reported "nothing wide here" for exactly that shape.
     * The scan advances one character per step, so a zone that repeats cannot
     * make this loop.
     */
    private fun zoneWideLiteral(command: String, zone: String): String? {
        var start = command.indexOf(zone)
        while (start >= 0) {
            // [F-01] The literal must start at a word boundary. Scanning the
            // raw text without this let `/tmp/var` supply the `/var` of
            // `/var/minis`: a line that never mentions a zone was refused for
            // deleting one.
            if (start == 0 || isRawPathBoundary(command[start - 1])) {
                var end = command.length
                for (i in start + zone.length until command.length) {
                    val c = command[i]
                    if (c in RAW_WORD_DELIMITERS || c == '"' || c == '\'' || c == '\\') {
                        end = i
                        break
                    }
                }
                val guest = absoluteGuestPath(command.substring(start, end), null)
                if (guest != null && isWideTarget(guest)) return guest
            }
            start = command.indexOf(zone, start + 1)
        }
        return null
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
     * checked against every base that could make it wide ([ALL_ZONES], which
     * includes the zones' parents), and a hit is reported with `cwdKnown =
     * false` so the refusal can ask for an absolute path instead of guessing.
     */
    internal fun wideDeleteHit(command: String, cwd: String? = null): WideDeleteHit? {
        val lex = shellLex(command)
        if (!lex.parseOk) return unbalancedFallback(command)
        var current: String? = cwd
        // [F-05] `pushd`/`popd` are `cd` with a stack, and the stack is what
        // `popd` returns to: `pushd <zone> && pushd /tmp && popd && rm -rf work`
        // stands back in the zone, which the old code read as "pushd sets cwd,
        // nothing else exists".
        val dirStack = ArrayList<String?>()
        for (words in lex.statements) {
            if (words.isEmpty()) continue
            val i = verbIndex(words)
            if (i < 0) continue
            val verb = words[i].substringAfterLast('/')
            if (verb == "pushd") {
                val operand = words.getOrNull(i + 1)
                if (operand == null) {
                    // `pushd` with no operand swaps the current directory with
                    // the top of the stack; with an empty stack it does nothing.
                    if (dirStack.isNotEmpty()) {
                        val top = dirStack.removeAt(dirStack.size - 1)
                        dirStack.add(current)
                        current = top
                    }
                } else {
                    dirStack.add(current)
                    current = absoluteGuestPath(operand, current)
                }
                continue
            }
            if (verb == "popd") {
                // An empty stack leaves the cwd alone (the shell errors out and
                // stays put), so this must not clear `current`.
                if (dirStack.isNotEmpty()) current = dirStack.removeAt(dirStack.size - 1)
                continue
            }
            if (verb == "cd") {
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
        for (base in ALL_ZONES) {
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
        // [F-04] `zoneOf` deliberately knows nothing about [ROOT_ONLY_ZONES], so
        // a hit that IS one of them came out with the WEAKEST text available —
        // deleting `/var/minis` wipes all five zones at once, which is the
        // shape that most needs the "name the files" confirmation. The parent
        // zones get the same no-referent sentence, not a quieter one.
        val noReferent = if ((zone != null && hit.path == zone) || hit.path in ROOT_ONLY_ZONES) {
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
