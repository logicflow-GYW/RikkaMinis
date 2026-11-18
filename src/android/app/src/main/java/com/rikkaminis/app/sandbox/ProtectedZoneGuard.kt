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
 * The rule is deliberately NOT "refuse deletes in protected zones". Cleaning
 * `shared/work/<one-batch-dir>` is ordinary, correct work; refusing it would
 * make the agent lie about what it did. What is refused is a **recursive**
 * delete aimed at the zone root or at a whole immediate child of it — a
 * container, not "these files". A plain `rm /var/minis/shared/README.md`
 * removes one named file and is allowed.
 *
 * ## The user's switch, not a new one
 *
 * This guard yields to the existing auto-approve master switch
 * (`AutoApproveStore.isEnabled`). Turning that on is the user saying "I accept
 * the risk for the convenience", and it already governs every other
 * confirmation in the app; a separate flag would add a knob nobody finds and a
 * second mental model to keep in sync. With it on, the delete proceeds and is
 * only *recorded* — the audit line is what makes "I turned it on" reviewable
 * afterwards.
 *
 * Ceiling: this is a lexical guard on shell text. `rm -rf $X`, `find … -delete`
 * and a script that deletes internally are not recognised — see
 * [refuseReason]'s doc. The path-resolved enforcement in
 * `FastioOffloadHandler.rm` is the one that cannot be spelled around; this is
 * the cheap early refusal for the shapes that actually occur.
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

    /** Verbs that delete their last argument or a `-r` tree. */
    private val DESTRUCTIVE = setOf("rm", "rmdir", "unlink", "shred")
    private val STATEMENT_SEPARATORS = Regex("""[;&|\n]+""")
    private val WRAPPERS = setOf("sudo", "env", "time", "nohup", "command", "exec")

    /** Flags that make `rm`/`mv` act on a whole tree. */
    private val RECURSIVE_FLAGS = setOf(
        "-r", "-R", "-rf", "-fr", "-Rf", "-fR", "-rF", "-Fr",
        "--recursive",
    )

    private fun isRecursive(verb: String, flags: List<String>): Boolean =
        verb == "rmdir" || verb == "shred" || flags.any { it in RECURSIVE_FLAGS }

    /**
     * The first wide target this command would delete inside a protected zone,
     * or null. Single source for both the refusal and the audit line — the two
     * must never disagree about what counts as a wide delete.
     *
     * `cd` statements are tracked: `cd /var/minis/shared && rm -rf work` is the
     * same delete as the absolute form (the first version of this guard missed
     * it and its own test caught that).
     */
    fun wideDeleteTarget(command: String, cwd: String = "/root"): String? {
        var current = cwd
        for (statement in command.split(STATEMENT_SEPARATORS)) {
            val tokens = statement.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            var i = 0
            while (i < tokens.size && tokens[i] in WRAPPERS) i++
            if (i >= tokens.size) continue
            val verb = tokens[i].substringAfterLast('/')
            if (verb == "cd" || verb == "pushd") {
                val target = tokens.getOrNull(i + 1)?.let { absoluteGuestPath(it, current) }
                if (target != null) current = target
                continue
            }
            if (verb !in DESTRUCTIVE && verb != "mv") continue
            val args = tokens.drop(i + 1)
            // A non-recursive delete names files, so an immediate child of a
            // zone (a README, a log) is ordinary work. Recursion is what turns
            // the same path into "remove everything under here".
            if (!isRecursive(verb, args.filter { it.startsWith("-") })) continue
            for (arg in args) {
                if (arg.startsWith("-")) continue
                val guest = absoluteGuestPath(arg, current) ?: continue
                if (isWideTarget(guest)) return guest
            }
        }
        return null
    }

    /**
     * A one-line refusal for a shell command that deletes a wide target inside
     * a protected zone, or null.
     *
     * Recognised: `rm [-r] <path…>`, `rmdir <path>`, `mv <src> <dst>` where a
     * path argument is written as a literal (absolute, or relative to [cwd]).
     * Everything else — `$VAR` targets, `find -delete`, `xargs rm`, interpreter
     * one-liners — falls through, because guessing at them would refuse work
     * that is actually fine. The path-resolved check in the fastio handler
     * covers those callers that go through it.
     *
     * [autoApprove] is the user's own standing choice (see the class doc): with
     * it on this returns null and the caller only records the target.
     */
    fun refuseReason(
        command: String,
        cwd: String = "/root",
        autoApprove: Boolean = false,
    ): String? {
        if (autoApprove) return null
        val target = wideDeleteTarget(command, cwd) ?: return null
        return "refusing to delete '$target': it is a protected zone (or a whole " +
            "subdirectory of one) — name the files to remove, or move them aside first. " +
            "(Turn on auto-approve in Settings → Permissions to skip this check.)"
    }

    /** Literal path → guest absolute path, or null when it is not a literal. */
    fun absoluteGuestPath(token: String, cwd: String): String? {
        if (token.isEmpty()) return null
        // Not a literal: variable, glob, command substitution, quote-wrapped.
        if (token.any { it in "$\"'`*?[{" }) return null
        val raw = if (token.startsWith("/")) token else "$cwd/$token"
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
