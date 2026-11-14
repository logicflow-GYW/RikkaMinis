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
private val HEAVY_VERBS = setOf(
    // Interpreters / runtimes
    "python", "python3", "python3.11", "python3.12", "node", "nodejs", "deno", "bun",
    // Package managers
    "apk", "pip", "pip3", "npm", "npx", "yarn", "pnpm", "go", "cargo", "rustc",
    "gem", "composer", "mvn", "gradle", "gradlew",
    // Compilers / build
    "javac", "kotlinc", "kotlinc-jvm", "gcc", "g++", "cc", "c++", "clang", "clang++",
    "make", "cmake", "ninja", "ld", "as",
    // Archivers / media (big buffers, real IO)
    "tar", "zip", "unzip", "7z", "xz", "gzip", "zstd", "ffmpeg", "ffprobe", "sox",
    // Heavy host tools
    "minis-model-use",
)

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
    var best = CommandClass.LIGHT
    for (rawStatement in command.split(STATEMENT_SEPARATORS)) {
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

    return if (verb in HEAVY_VERBS) CommandClass.HEAVY else CommandClass.LIGHT
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
    val sub = rest.substringBefore(' ')
    return if (sub in HEAVY_GIT_SUBCOMMANDS) CommandClass.HEAVY else CommandClass.LIGHT
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
        if (verb in PREFIX_KEYWORDS) {
            s = s.substringAfter(' ', "").trimStart()
            continue
        }
        return s
    }
    return s
}
