package com.rikkaminis.app.sandbox.offload

/**
 * [meta1028-B05 F-12] `minis-sessions-cli` option surface: an option a command
 * does not implement must be REFUSED, not ignored.
 *
 * Top-level and free of Android types (no `Context`, no `ChatRepository`) so
 * the decision runs in a plain JVM unit test — the handler needs both, which is
 * exactly why the earlier date/page-size contracts were lifted out the same way
 * ([sessionsDateBound], [PageSizeOutcome]).
 *
 * The defect being closed, verbatim: `list --kewords python` (one typo'd
 * letter) parses as a value under a key nobody reads, no command consults it,
 * and `list` answers EVERY session at exit 0 — which reads as "the filter ran
 * and matched everything". The value-option-without-a-value spelling
 * (`list --start --end 2026-01-01`) is the same class: [OffloadArgs] files a
 * bare `--start` as a boolean flag, the bound then reads as null = "no filter",
 * and an inverted window of dates comes back as the whole history.
 */

/**
 * What each subcommand actually reads.
 *
 * The tables are the ONLY source [sessionsOptionProblem] judges against, so a
 * new option has to be declared where it is read — the alternative (a
 * hand-copied list) is how `send` ended up dispatchable while the help text
 * still advertised three commands ([da1032-C] ISSUE-08).
 *
 * `--compact` / `-q` / `--quiet` / `--help` / `-h` are deliberately NOT listed:
 * every CLI accepts them ([OffloadOutput.OUTPUT_FLAGS]), and
 * [OffloadArgs.unknownFlags] already subtracts them, so a table that repeated
 * them would only add a second place to forget one.
 */
internal object SessionsOptions {
    /** Options that take a value, per subcommand. */
    private val VALUES: Map<String, Set<String>> = mapOf(
        "list" to setOf("keywords", "ids", "start", "end", "limit"),
        "search" to setOf("keywords", "ids", "start", "end", "limit"),
        "messages" to setOf("id", "offset", "limit", "start", "end"),
        // `id` as well as `session`: cmdSend falls back to it, so refusing it
        // would break the spelling that fallback exists for.
        "send" to setOf("prompt", "session", "id", "wait-timeout"),
    )

    /** Pure switches, per subcommand. */
    private val SWITCHES: Map<String, Set<String>> = mapOf(
        "list" to emptySet(),
        "search" to emptySet(),
        "messages" to setOf("full"),
        "send" to setOf("async", "wait"),
    )

    /** Value-taking names for [sub] — the ones a bare spelling must be caught on. */
    fun values(sub: String): Set<String> = VALUES[sub].orEmpty()

    /** Every option [sub] implements. Empty for an unknown subcommand. */
    fun known(sub: String): Set<String> = VALUES[sub].orEmpty() + SWITCHES[sub].orEmpty()
}

/**
 * The reason [sub] cannot run with [args], or null when the invocation is
 * clean. Pure — it reads the parsed argv only.
 *
 * `--limit` sits in both worlds: [OffloadPageSize] already refuses a bare
 * spelling, and this check reaches it first with the same exit code, so the
 * contract a caller sees (a usage error, never a default) is unchanged.
 */
internal fun sessionsOptionProblem(sub: String, args: OffloadArgs): String? {
    val known = SessionsOptions.known(sub)
    // An unknown SUBCOMMAND has its own error path (with the command list) —
    // reporting its flags here would answer a different question.
    if (known.isEmpty()) return null

    // [meta1028-B05 F-12] BOTH channels, because a typo reaches either one
    // depending on what follows it: `list --kewords python` binds "python" as
    // the VALUE of the unknown key (it is not declared boolean, and the next
    // token does not start with `-`), while `list --kewords` alone, or
    // `--full` on `list`, is filed as a FLAG. `unknownFlags` sees only the
    // second — a guard that read it alone would let the single most likely
    // typo (`--kewords x`) through, which is the whole defect.
    val unknownValues = args.unknownValueKeys(known)
    if (unknownValues.isNotEmpty()) {
        return "$sub: unknown option ${unknownValues.sorted().joinToString(", ") { "--$it" }}; " +
            "$sub accepts ${known.sorted().joinToString(", ") { "--$it" }} " +
            "(plus --compact / -q / --help)"
    }

    // The bare-value check MUST come before the unknown-flag check. `--start
    // --end 2026-01-01` files `--start` as a FLAG (its follower is itself an
    // option), and `start` IS a known name — so an unknown-flag-first ordering
    // cannot see it, while a value-flag-first ordering reports "unknown option
    // --start", which tells the caller the option does not exist when in fact
    // it is missing its value. The message has to be true, or the caller
    // rewrites the wrong thing.
    val bare = SessionsOptions.values(sub).filter { args.hasFlag(it) }
    if (bare.isNotEmpty()) {
        return "$sub: ${bare.sorted().joinToString(", ") { "--$it" }} needs a value " +
            "(the option was given without one)"
    }

    val unknown = args.unknownFlags(known - SessionsOptions.values(sub))
    if (unknown.isNotEmpty()) {
        return "$sub: unknown option ${unknown.sorted().joinToString(", ") { "--$it" }}; " +
            "$sub accepts ${known.sorted().joinToString(", ") { "--$it" }} " +
            "(plus --compact / -q / --help)"
    }
    return null
}
