package com.rikkaminis.app.sandbox

/**
 * [fix/classify-compound-1114] Command resource classification.
 *
 * Extracted from ExecutionCoordinator.kt because the classifier is now more
 * than a prefix check and needs to be JVM-testable on its own (the old
 * in-place `classifyCommand` was only reachable through a 9k-line file that
 * pulls Android types).
 *
 * ## Why the old prefix check was wrong
 *
 * It read only the FIRST token of the raw command string. Measured against
 * 4 775 real `shell_execute` calls in the device log (2026-11-01..14):
 *
 *   - 20 already classified HEAVY (0.4%)
 *   - **233 were `cd X && python3 …`, i.e. HEAVY work classified LIGHT (4.9%)**
 *
 * LIGHT bypasses the heavyGate (Semaphore(1)) entirely, so those 233 could run
 * concurrently across sessions and stack their memory; they also skipped the
 * CRITICAL-phase "heavy needs ample memory" check and the forced recycle after.
 *
 * Also misclassified by the old shape: `ENV=prod npm run build` (leading
 * assignment), `# comment` headers, `for f in …; do python3 …; done` (leading
 * keyword), and any of git/compilers/archivers/media tools, which were simply
 * absent from the list.
 *
 * ## Shape of the fix
 *
 * Split into statements on the shell separators (`;` `|` `&` newline, with
 * `&&`/`||` folded into the same split), then for each statement strip leading
 * `KEY=value` assignments and `cd …`, and classify the remaining verb. The
 * command's class is the HIGHEST class any statement reaches.
 *
 * `git` is classified by SUBCOMMAND, not by the `git` verb: measured over the
 * same log, `git status` (220), `diff` (100), `log` (90) dominate — treating
 * them as HEAVY would serialize the agent's most common read-only calls behind
 * the heavy gate for no memory benefit. Only clone/fetch/pull/push/gc/
 * repack/submodule/archive and friends are heavy.
 *
 * ## What is deliberately NOT done
 *
 * - No quoting/heredoc awareness. A `python3` inside a heredoc body counts as
 *   heavy; over-classifying errs toward serialization + recycling, which is
 *   the safe direction (the pre-fix classifier erred the other way).
 * - No variable expansion. `$PY script.py` stays LIGHT — guessing would be
 *   worse than the status quo.
 * - No path resolution. `./python3` matches by basename.
 */

internal enum class CommandClass { LIGHT, HEAVY, LEAKY }

/** Highest class wins when a command has several statements. */
private val CLASS_ORDER = mapOf(
    CommandClass.LIGHT to 0,
    CommandClass.HEAVY to 1,
    CommandClass.LEAKY to 2,
)

/**
 * Statement separators. A `|` (pipe) does not make a statement lighter, and
 * `&` covers both `&&` and a bare background `&`. Splitting on single `|`/`&`
 * chars means `||` and `&&` need no special case.
 */
private val STATEMENT_SEPARATORS = Regex("""[;|&\n]+""")

/** Leading `KEY=value` assignment(s), possibly quoted-ish. */
private val LEADING_ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=\S*\s+""")

/**
 * Verbs whose whole subtree is heavy. Matched against the basename of the verb
 * so `/usr/bin/python3` and `./python3` both count.
 */
/**
 * [fix/heavy-gate-scope] Interpreters: the footprint depends on the SCRIPT, not
 * on the verb. They stay in [HEAVY_VERBS] so memory discipline (recycle tiers,
 * pre-exec rejection) is byte-for-byte unchanged; the second axis
 * ([classifyHeavyGate]) is what decides whether they serialize globally.
 */
private val INTERPRETER_VERBS = setOf(
    "python", "python3", "python3.11", "python3.12", "node", "nodejs", "deno", "bun",
)

/**
 * [fix/heavy-gate-scope] Verbs whose global cost is deterministic — fixed
 * buffers, fixed IO, a known build graph. These keep the process-wide permit
 * and may run one at a time.
 */
private val DETERMINISTIC_HEAVY_VERBS = setOf(
    // Package managers
    "apk", "pip", "pip3", "npm", "npx", "yarn", "pnpm", "go", "cargo", "rustc",
    "gem", "composer", "mvn", "gradle", "gradlew",
    // Compilers / build
    "javac", "kotlinc", "kotlinc-jvm", "gcc", "g++", "cc", "c++", "clang", "clang++",
    "make", "cmake", "ninja", "ld", "as",
    // Archivers / media (big buffers, real IO)
    "tar", "zip", "unzip", "7z", "xz", "gzip", "zstd", "ffmpeg", "ffprobe", "sox",
)

/** Union kept for [isHeavyVerb] — the FIRST axis is deliberately unchanged. */
private val HEAVY_VERBS = INTERPRETER_VERBS + DETERMINISTIC_HEAVY_VERBS + "minis-model-use"

/**
 * [fix/fold-paste-split-1114][F5] Versioned interpreter/runtime names:
 * `python3.13`, `python3.9`, `python2.7`, `node20`, `ruby3.2`.
 *
 * The explicit list above can only ever hold the versions that existed when it
 * was written — `python3.11` / `python3.12` were added by hand, and the very
 * next minor (`python3.13`, a real binary on this device) fell through to
 * LIGHT. That is a REGRESSION, not a gap: the old prefix check classified every
 * `python3*` as HEAVY, so the statement-aware rewrite silently downgraded the
 * newer interpreters. Measured against the pre-fix classifier over the device
 * log: `python3.13`/`python3.9`/`python3.10`/`python3.14` all flipped
 * HEAVY→LIGHT.
 *
 * Matched on the verb AFTER the last `/`, so `./python3.13` counts. Anchored at
 * both ends: `python3.cfg` is not an interpreter.
 */
private val VERSIONED_HEAVY_VERB = Regex(
    """(?:python|python2|python3|node|nodejs|ruby|perl|php|java|kotlin|dotnet|deno|bun)(?:\d+(?:\.\d+)*)?""",
)

/**
 * True when [verb] is a heavy interpreter/runtime, including versioned names.
 *
 * Split out so `python3.13` (regex) and `python3` (set) both work, and so a
 * plain `python` keeps matching the set.
 */
private fun isHeavyVerb(verb: String): Boolean {
    if (verb in HEAVY_VERBS) return true
    return matchesVersionedRuntime(verb)
}

/**
 * Versioned runtime names: `python3.13`, `ruby3.2`, `node20`.
 * Only pay for the regex when the verb looks like a runtime at all — a bare
 * `ls`/`git` never reaches it.
 */
private fun matchesVersionedRuntime(verb: String): Boolean =
    (verb.startsWith("python") || verb.startsWith("node") ||
        verb.startsWith("ruby") || verb.startsWith("perl") || verb.startsWith("php") ||
        verb.startsWith("java") || verb.startsWith("kotlin") || verb.startsWith("dotnet") ||
        verb.startsWith("deno") || verb.startsWith("bun")) &&
        VERSIONED_HEAVY_VERB.matches(verb)

/** Interpreter (fixed set, or a versioned runtime name). */
private fun isInterpreterVerb(verb: String): Boolean =
    verb in INTERPRETER_VERBS || matchesVersionedRuntime(verb)

/**
 * Leading shell keywords that wrap a real command without changing its cost:
 * `for f in a b; do python3 …; done` splits into `do python3 …` (the `;` is a
 * separator), `time python3 x`, `nohup python3 x`, `nice -n 10 python3 x`.
 * Stripping these means the loop body is classified by its verb, not by `do`.
 */
private val PREFIX_KEYWORDS = setOf(
    "do", "then", "else", "elif", "if", "while", "until", "time", "exec",
    "command", "nohup", "sudo", "builtin", "env", "{", "(", "!", "&",
)

/** Wrappers that take a numeric/-n argument before the real verb. */
private val NICE_LIKE = setOf("nice", "ionice", "chrt")

/**
 * [fix/fold-paste-split-1114][F6] Wrappers whose own flags/values sit BETWEEN
 * them and the real verb, where `nice` only handles the single numeric form.
 *
 * `sudo -u x make`, `sudo -E python3 x`, `env -i FOO=bar python3 x`,
 * `time -p python3 x` all reached the classifier with `-u`/`-E`/`-i`/`-p` as the
 * verb, matched nothing, and came out LIGHT — while the KDoc of
 * [stripLeadingNoise] explicitly claimed those wrappers were peeled. The old
 * prefix classifier got them right by accident (it saw `sudo` and stayed
 * LIGHT… and also saw nothing heavy, so this is strictly a fix).
 *
 * `sudo`/`env`/`time` additionally accept `VAR=value` and bare words as their
 * own arguments (`sudo -u x`, `env FOO=bar`, `time python3`), so the peeling
 * rule is "drop flags and their values, then drop any `K=V` word" — bounded by
 * the same guard as everything else.
 */
private val OPTION_WRAPPERS = setOf("sudo", "env", "time", "nohup", "exec", "command")

/**
 * Wrapper flags that consume the FOLLOWING token, **per wrapper**.
 *
 * A single global table is wrong because the same letter means different things
 * to different wrappers: `-p` takes sudo's prompt but is time's POSIX-format
 * switch, and treating time's `-p` as value-taking would swallow `python3` and
 * classify `time -p python3 x` as LIGHT. Wrapper-specific tables make the
 * ambiguity impossible instead of picking a side.
 *
 * Flags absent here are treated as bare (`-E`, `-i`) and dropped alone.
 */
private val WRAPPER_VALUE_FLAGS: Map<String, Set<String>> = mapOf(
    "sudo" to setOf(
        "-u", "--user", "-g", "--group", "-p", "--prompt", "-C", "--close-from",
        "-h", "--host", "-r", "--role", "-t", "--type", "-T", "--command-timeout",
        "-D", "--chdir", "-U", "--other-user", "-R", "--chroot",
    ),
    "env" to setOf("-u", "--unset", "-C", "--chdir", "-S", "--split-string"),
    "time" to setOf("-f", "--format", "-o", "--output"),
    "nohup" to emptySet(),
    "exec" to setOf("-a"),
    "command" to emptySet(),
)

/** `git` global flags that consume the FOLLOWING token as their value. */
private val GIT_VALUE_FLAGS = setOf(
    "-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
    "--config-env",
)

/**
 * `git` subcommands that are memory/IO heavy. Everything else (status, diff,
 * log, show, rev-parse, branch, add, commit, remote, grep, rm, worktree) is
 * LIGHT — see the KDoc for the measured distribution.
 */
private val HEAVY_GIT_SUBCOMMANDS = setOf(
    "clone", "fetch", "pull", "push", "gc", "repack", "prune", "fsck",
    "submodule", "archive", "bundle", "filter-repo", "pack-objects",
    "index-pack", "unpack-objects", "merge", "rebase", "cherry-pick", "am",
)

/**
 * Classify one shell command. Never throws; unparseable input degrades to the
 * old behaviour (classify by leading token only).
 */
internal fun classifyCommand(command: String): CommandClass {
    if (command.isBlank()) return CommandClass.LIGHT
    // [incr1116-C F-05] A tab is whitespace to the shell, and the tokenizer
    // below only knows the space: `python3<TAB>x.py`, `git<TAB>fetch` and
    // `tar<TAB>-czf` arrived as one verb, matched nothing, and were classified
    // LIGHT — a regression from the prefix check this file replaced, which saw
    // `python3` regardless of what followed it. One normalisation keeps the
    // rest of the parser byte-for-byte the same.
    val normalized = command.replace('\t', ' ')
    var best = CommandClass.LIGHT
    for (rawStatement in normalized.split(STATEMENT_SEPARATORS)) {
        val c = classifyStatement(rawStatement)
        if (CLASS_ORDER.getValue(c) > CLASS_ORDER.getValue(best)) best = c
    }
    return best
}

private fun classifyStatement(rawStatement: String): CommandClass {
    var s = stripLeadingNoise(rawStatement.trim())
    if (s.isEmpty()) return CommandClass.LIGHT

    val head = s.substringBefore(' ')
    val verb = head.substringAfterLast('/')

    // `cd` / `pushd` / `popd` cost nothing. Reaching here means the statement is
    // the bare directory change — a trailing `&&`/`;` was already consumed by
    // STATEMENT_SEPARATORS (the old prefix check treated the WHOLE `cd X && …`
    // as LIGHT, which is the 233-command defect this file exists to fix).
    if (verb == "cd" || verb == "pushd" || verb == "popd") return CommandClass.LIGHT

    // minis-model-use: in-process LLM calls whose DirectByteBuffer allocations
    // live in the app native heap, invisible to GC — always isolate.
    if (verb == "minis-model-use") return CommandClass.LEAKY

    if (verb == "git") return classifyGit(s.substringAfter(' ', "").trimStart())

    return if (isHeavyVerb(verb)) CommandClass.HEAVY else CommandClass.LIGHT
}

/**
 * Command-class recycle decision. LEAKY always recycles (leak containment);
 * HEAVY recycles only when app native heap is above [heavyRecycleThresholdMB]
 * (default 80MB — matches HEAVY_CMD_RECYCLE_NATIVE_MB); LIGHT never recycles
 * by class (the generation budget still caps it).
 */
internal fun shouldRecycleByClass(
    cmdClass: CommandClass,
    nativeMB: Long,
    heavyRecycleThresholdMB: Long = 80L,
): Boolean {
    return when (cmdClass) {
        CommandClass.LEAKY -> true
        CommandClass.HEAVY -> nativeMB > heavyRecycleThresholdMB
        CommandClass.LIGHT -> false
    }
}

/**
 * `git` is classified by SUBCOMMAND. Global flags sit between the verb and the
 * subcommand and must be skipped — including the ones that take a VALUE
 * (`git -C /repo fetch` would otherwise read `/repo` as the subcommand, and
 * `git --no-pager clone x` would read nothing).
 */
private fun classifyGit(tail: String): CommandClass {
    return if (gitSubcommand(tail) in HEAVY_GIT_SUBCOMMANDS) CommandClass.HEAVY else CommandClass.LIGHT
}

/** `git <global flags> <subcommand>` → the subcommand token. */
private fun gitSubcommand(tail: String): String {
    var rest = tail
    var guard = 0
    while (rest.startsWith("-") && guard++ < 16) {
        val flag = rest.substringBefore(' ')
        val afterFlag = rest.substringAfter(' ', "").trimStart()
        rest = if (flag in GIT_VALUE_FLAGS) {
            // `git -C /repo fetch` — drop the flag AND its value.
            afterFlag.substringAfter(' ', "").trimStart()
        } else {
            // `--no-pager`, `--version` — drop the flag only. No `=` form of
            // these, and eating the next token would swallow the subcommand.
            afterFlag
        }
    }
    return rest.substringBefore(' ')
}

// ---------------------------------------------------------------------------
// [fix/heavy-gate-scope] SECOND AXIS — who must take the process-wide permit
// ---------------------------------------------------------------------------

/**
 * Whether a command must serialize behind the process-wide heavy permit.
 *
 * WHY a second axis instead of a new [CommandClass] value: the first axis is a
 * MEMORY-DISCIPLINE signal (recycle tiers, pre-exec rejection, generation
 * budget) and 233 tests plus the on-device log distribution are calibrated on
 * it. Changing it would move recycle behaviour for every `python3` call, which
 * is not what this fix is about. This axis answers one narrower question:
 *
 *   - [GLOBAL]  deterministic buffers — keep the permit, one at a time.
 *   - [INTERPRETER] footprint depends on the script — run concurrently while
 *     the system has headroom (the admission check lives in ExecutionCoordinator;
 *     under pressure this falls back to [GLOBAL] behaviour).
 *   - [LIGHT]   never touches the permit (unchanged).
 */
internal enum class HeavyGateClass { LIGHT, INTERPRETER, GLOBAL }

/**
 * Wait-only interpreter invocations stay on the light side of the gate.
 *
 * Evidence (2026-11-22, device log): two `python3 gh_ci.py wait …` runs held the
 * ONE global permit for 687.7 s and 569.2 s while their PRoot child RSS stayed
 * at 3 MB — pure socket polling. During the 687 s window another session's
 * one-second command queued 151.2 s behind it.
 *
 * The exemption is decided from the interpreter's FIRST script argument (and,
 * where relevant, its first subcommand). A substring elsewhere in the command
 * cannot claim it: `python3 build.py --task gh_ci_wait.sh` remains an ordinary
 * interpreter workload.
 */
private val WAIT_ONLY_SCRIPTS = setOf(
    "gh_ci_wait", "gh_ci_wait.py", "gh_ci_wait.sh",
)

private val WAIT_SUBCOMMAND_SCRIPTS = setOf(
    "gh_ci.py", "gh_sync.sh",
)

private fun unquoteArgument(argument: String): String =
    argument.removeSurrounding("\"").removeSurrounding("'")

private fun isWaitOnlyInterpreterInvocation(statement: String): Boolean {
    val tokens = statement.split(' ').filter { it.isNotEmpty() }
    var index = 1
    while (index < tokens.size) {
        val token = tokens[index]
        if (token.startsWith("-")) {
            // Inline code can contain anything, including a wait helper's name.
            // Never infer its resource shape from that string.
            if (token == "-c" || token == "-e") return false
            index++
            continue
        }

        val script = unquoteArgument(token).substringAfterLast('/')
        if (script in WAIT_ONLY_SCRIPTS) return true
        if (script !in WAIT_SUBCOMMAND_SCRIPTS) return false

        val subcommand = tokens.getOrNull(index + 1)?.let(::unquoteArgument)
        return subcommand == "wait"
    }
    return false
}

/**
 * Classify a whole command for the global gate. Never throws; unparseable
 * input degrades to LIGHT, which is the pre-2026-11-22 behaviour for anything
 * the classifier could not read.
 */
internal fun classifyHeavyGate(command: String): HeavyGateClass {
    if (command.isBlank()) return HeavyGateClass.LIGHT
    val normalized = command.replace('\t', ' ')
    var best = HeavyGateClass.LIGHT
    for (rawStatement in normalized.split(STATEMENT_SEPARATORS)) {
        val c = gateClassOfStatement(rawStatement)
        if (c.ordinal > best.ordinal) best = c
    }
    return best
}

private fun gateClassOfStatement(rawStatement: String): HeavyGateClass {
    val s = stripLeadingNoise(rawStatement.trim())
    if (s.isEmpty()) return HeavyGateClass.LIGHT
    val verb = s.substringBefore(' ').substringAfterLast('/')
    if (verb == "cd" || verb == "pushd" || verb == "popd") return HeavyGateClass.LIGHT
    // minis-model-use runs in-process against :modelservice and never took this
    // permit before the split — keep it that way.
    if (verb == "minis-model-use") return HeavyGateClass.LIGHT
    if (verb == "git") {
        return if (gitSubcommand(s.substringAfter(' ', "").trimStart()) in HEAVY_GIT_SUBCOMMANDS) {
            HeavyGateClass.GLOBAL
        } else {
            HeavyGateClass.LIGHT
        }
    }
    if (verb in DETERMINISTIC_HEAVY_VERBS) return HeavyGateClass.GLOBAL
    if (isInterpreterVerb(verb)) {
        return if (isWaitOnlyInterpreterInvocation(s)) {
            HeavyGateClass.LIGHT
        } else {
            HeavyGateClass.INTERPRETER
        }
    }
    return HeavyGateClass.LIGHT
}

/**
 * Peel everything that sits in front of the real verb without changing its
 * resource cost:
 *   - `KEY=value` assignments   (`ENV=prod npm run build`)
 *   - shell keywords / wrappers (`do`, `then`, `time`, `nohup`, `sudo`, `!`)
 *   - `nice`/`ionice`/`chrt` and their numeric argument (`nice -n 10 python3 x`)
 *   - `cd …` is NOT special-cased: the separator split already made its
 *     `&&` body a separate statement, so a surviving `cd /tmp` statement
 *     classifies LIGHT simply because `cd` is not a heavy verb.
 * Bounded by [guard] so pathological input cannot spin.
 */
private fun stripLeadingNoise(input: String): String {
    var s = input
    var guard = 0
    while (guard++ < 64) {
        if (s.isEmpty()) return s
        // KEY=value prefix
        val assign = LEADING_ASSIGNMENT.find(s)
        if (assign != null) {
            s = s.substring(assign.range.last + 1).trimStart()
            continue
        }
        val verb = s.substringBefore(' ').substringAfterLast('/')
        // Peel a wrapper keyword and its own arguments, then re-classify what
        // follows: `nice -n 10 python3` → `python3`, `sudo -u x make` → `make`.
        if (verb in NICE_LIKE) {
            s = s.substringAfter(' ', "").trimStart()
            // Drop the wrapper's own flags/values: `-n 10`, `-c 2`.
            while (s.startsWith("-")) {
                s = s.substringAfter(' ', "").trimStart()
                if (s.isNotEmpty() && !s.startsWith("-") && s.substringBefore(' ').all { it.isDigit() }) {
                    s = s.substringAfter(' ', "").trimStart()
                }
            }
            continue
        }
        // [fix/fold-paste-split-1114][F6] `sudo -u x make` / `env -i K=V python3`
        // / `time -p python3`: consume the wrapper's flags, their values, and any
        // `K=V` words, then classify whatever verb survives.
        if (verb in OPTION_WRAPPERS) {
            s = s.substringAfter(' ', "").trimStart()
            val valueFlags = WRAPPER_VALUE_FLAGS[verb].orEmpty()
            var dropGuard = 0
            while (s.isNotEmpty() && dropGuard++ < 24) {
                val head = s.substringBefore(' ')
                when {
                    head.startsWith("-") -> {
                        val afterFlag = s.substringAfter(' ', "").trimStart()
                        s = if (head in valueFlags) {
                            // Drop the flag AND the token after it.
                            afterFlag.substringAfter(' ', "").trimStart()
                        } else {
                            afterFlag
                        }
                    }
                    // `env FOO=bar python3` — the assignment is the wrapper's
                    // own argument, not part of the real command.
                    LEADING_ASSIGNMENT.find("$head ") != null -> {
                        s = s.substringAfter(' ', "").trimStart()
                    }
                    else -> break // not a wrapper argument: `s` starts at the verb
                }
            }
            // Always `continue`, even when no flag/assignment was present: `s`
            // has already advanced past the wrapper word, and falling through to
            // the PREFIX_KEYWORDS branch below would strip it a SECOND time
            // (`time python3 x` → `python3 x` → `x`, classified LIGHT).
            continue
        }
        if (verb in PREFIX_KEYWORDS) {
            s = s.substringAfter(' ', "").trimStart()
            continue
        }
        return s
    }
    return s
}
