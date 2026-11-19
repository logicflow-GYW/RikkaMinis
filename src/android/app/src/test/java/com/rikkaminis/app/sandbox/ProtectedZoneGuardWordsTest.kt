package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-1118C-C] The lexical layer of the protected-zone guard.
 *
 * Four holes in the same `wideDeleteHit` wording, all closed by one change of
 * judgement: the command is read as words by [ProtectedZoneGuard.shellLex]
 * instead of being `split` on `\s+`, and the words are then judged. Nothing
 * here derives an expectation from [ProtectedZoneGuard.ZONES] — the paths are
 * literals, so a future zone edit cannot move a verdict with it.
 *
 * Two groups carry equal weight: the 11 bypasses must be refused, and the
 * no-false-positive set must stay allowed. A guard that refuses too freely
 * teaches the model to work around it, which is the same failure as a bypass.
 */
class ProtectedZoneGuardWordsTest {

    // ── F-01: `mv` destroys every source, and `-t` makes all of them sources ──

    /** [F-01] `mv -t DIR <zone>`: the flag value is the destination, so the zone is a source. */
    @Test
    fun `mv with a target directory flags the zone as a source`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("mv -t /tmp /var/minis/shared"))
    }

    /** [F-01] `mv a b c`: two sources, one destination — the old code judged only the first. */
    @Test
    fun `mv with several sources judges all of them`() {
        assertNotNull(
            ProtectedZoneGuard.refuseReason("mv /tmp/a /var/minis/shared /tmp/dst/"),
        )
    }

    /** [F-01] The long spelling of the same flag. */
    @Test
    fun `mv long target directory flags the zone as a source`() {
        assertNotNull(
            ProtectedZoneGuard.refuseReason("mv --target-directory=/tmp /var/minis/shared/work"),
        )
    }

    // ── F-02: assignments are not the verb ────────────────────────────────────

    /** [F-02] `FOO=1 rm -rf <zone>` — the assignment used to be read as the verb. */
    @Test
    fun `a leading assignment is not the verb`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("FOO=1 rm -rf /var/minis/shared"))
    }

    /** [F-02] The same assignment after a wrapper. */
    @Test
    fun `an assignment after a wrapper is not the verb`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("env FOO=1 rm -rf /var/minis/shared"))
        assertNotNull(ProtectedZoneGuard.refuseReason("env -i FOO=1 rm -rf /var/minis/shared"))
        // The assignment sits between the wrapper's own options and the verb, so
        // the wrapper-side skip is the only thing that reaches `rm` here: the
        // bare form above is rescued by the verb-scan's own assignment skip.
        assertNotNull(
            ProtectedZoneGuard.refuseReason("env FOO=1 -u HOME rm -rf /var/minis/shared"),
        )
    }

    // ── F-03: the verb is compared after quotes and backslashes are resolved ──

    /** [F-03] `\rm` and `"rm"` both arrive as `rm` after the lexer. */
    @Test
    fun `a backslash or quoted verb is the same verb`() {        assertNotNull(ProtectedZoneGuard.refuseReason("\\rm -rf /var/minis/shared"))
        assertNotNull(ProtectedZoneGuard.refuseReason("\"rm\" -rf /var/minis/shared"))
        assertNotNull(ProtectedZoneGuard.refuseReason("'rm' -rf /var/minis/shared"))
    }

    /** [F-03] A wrapper that the old name list did not enumerate. */
    @Test
    fun `process wrappers are peeled before the verb`() {
        for (cmd in listOf(
            "nice -n 10 rm -rf /var/minis/shared",
            "ionice -c 2 -n 5 rm -rf /var/minis/shared",
            "timeout 5 rm -rf /var/minis/shared",
            "timeout 5s --signal=KILL rm -rf /var/minis/shared",
            "setsid rm -rf /var/minis/shared",
            "stdbuf -oL rm -rf /var/minis/shared",
            "taskset -c 0-3 rm -rf /var/minis/shared",
            "chrt 50 rm -rf /var/minis/shared",
            "doas -u root rm -rf /var/minis/shared",
            "/usr/bin/nice -n 10 rm -rf /var/minis/shared",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    // ── F-04: quoting is not an escape hatch for the path ────────────────────

    /** [F-04] A quoted path with a space is ONE word, and it is still wide. */
    @Test
    fun `a quoted path containing a space stays one wide target`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf \"/var/minis/shared/my folder\""))
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf '/var/minis/shared/my folder'"))
    }

    // ── the 1118B baselines keep refusing ────────────────────────────────────

    @Test
    fun `the already fixed spellings still refuse`() {
        for (cmd in listOf(
            "rm -rf /var/minis/shared",
            "busybox rm -rf /var/minis/shared",
            "rm -rf \"/var/minis/shared\"",
            "rm -rfv /var/minis/shared",
            "cd /var/minis/shared && rm -rf work",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    // ── the no-false-positive set: these must stay allowed ───────────────────

    @Test
    fun `a single file inside a zone is still allowed`() {
        assertNull(ProtectedZoneGuard.refuseReason("rm /var/minis/shared/README.md"))
    }

    @Test
    fun `a second level subdirectory of a zone is still allowed`() {
        assertNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared/work/one-batch-dir"))
    }

    @Test
    fun `moving a file into a zone is not a delete of the zone`() {
        assertNull(ProtectedZoneGuard.refuseReason("mv /tmp/report.md /var/minis/shared/"))
    }

    @Test
    fun `moving one deep file out of a zone is still allowed`() {
        assertNull(ProtectedZoneGuard.refuseReason("mv /var/minis/shared/work/a.md /tmp/"))
    }

    @Test
    fun `mv with a target directory moving one file out is allowed`() {
        assertNull(ProtectedZoneGuard.refuseReason("mv -t /tmp /var/minis/shared/work/a.md"))
    }

    /** The explicit rule from the task book: quoting the verb in a search must not be refused. */
    @Test
    fun `a search that quotes a delete is still allowed`() {
        assertNull(ProtectedZoneGuard.refuseReason("grep -rn 'rm -rf' /var/minis/shared"))
        assertNull(ProtectedZoneGuard.refuseReason("grep -rn \"rm -rf\" /var/minis/shared"))
    }

    @Test
    fun `a wrapped read only command is still allowed`() {
        assertNull(ProtectedZoneGuard.refuseReason("nice -n 10 ls /var/minis/shared"))
    }

    /** Regression protection: the `cd`-tracking behaviour is unchanged. */
    @Test
    fun `a delete from a cd inside a zone still refuses and the deeper one does not`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("cd /var/minis/shared && rm -rf work"))
        assertNull(ProtectedZoneGuard.refuseReason("cd /var/minis/shared && rm -rf work/sub"))
    }

    /** Redirections were plain words before, which refused ordinary work. */
    @Test
    fun `redirections are not arguments`() {
        for (cmd in listOf(
            "rm -rf /var/minis/shared/work/batch > /tmp/log",
            "rm -rf /var/minis/shared/work/batch 2>&1 | tail -5",
            "rm -rf /var/minis/shared/work/batch >> /tmp/log",
            "rm -rf /var/minis/shared/work/batch >/tmp/log",
            "find /var/minis/shared -name '*.md' 2>&1",
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
        // The redirection is gone, the target is not: the wide delete still refuses.
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared 2>&1"))
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared > /tmp/x"))
    }

    // ── shellLex: the pure function ───────────────────────────────────────────

    @Test
    fun `quotes keep a path with a space in one word`() {
        assertEquals(
            listOf("rm", "-rf", "/var/minis/shared/my folder"),
            ProtectedZoneGuard.shellLex("rm -rf \"/var/minis/shared/my folder\"").statements[0],
        )
        assertEquals(
            listOf("rm", "-rf", "/var/minis/shared/my folder"),
            ProtectedZoneGuard.shellLex("rm -rf '/var/minis/shared/my folder'").statements[0],
        )
    }

    @Test
    fun `backslash escapes resolve inside a word`() {
        assertEquals(
            listOf("rm", "-rf", "/var/minis/shared"),
            ProtectedZoneGuard.shellLex("\\rm -rf /var/minis/shared").statements[0],
        )
        assertEquals(
            listOf("rm", "-rf", "/var/minis/a b"),
            ProtectedZoneGuard.shellLex("rm -rf /var/minis/a\\ b").statements[0],
        )
        assertEquals(
            listOf("echo", "rm -rf x"),
            ProtectedZoneGuard.shellLex("echo \"rm -rf x\"").statements[0],
        )
        // Two backslashes are one literal backslash, so the word is NOT the verb
        // — but it is still one word, and the space after it is a real one.
        assertEquals(
            listOf("echo", "\\rm", "-rf", "x"),
            ProtectedZoneGuard.shellLex("echo \\\\rm -rf x").statements[0],
        )
    }

    @Test
    fun `a quote inside the other quote kind is literal`() {
        assertEquals(
            listOf("echo", "it's here"),
            ProtectedZoneGuard.shellLex("echo \"it's here\"").statements[0],
        )
        assertEquals(
            listOf("echo", "a \"b\" c"),
            ProtectedZoneGuard.shellLex("echo 'a \"b\" c'").statements[0],
        )
    }

    @Test
    fun `statement separators are only separators outside quotes`() {
        val lex = ProtectedZoneGuard.shellLex("echo \"a;b\" && rm -rf x")
        assertEquals(true, lex.parseOk)
        assertEquals(2, lex.statements.size)
        assertEquals(listOf("echo", "a;b"), lex.statements[0])
        assertEquals(listOf("rm", "-rf", "x"), lex.statements[1])
    }

    @Test
    fun `an unterminated quote is reported as unreadable`() {
        assertFalse(ProtectedZoneGuard.shellLex("rm -rf 'unclosed").parseOk)
        assertFalse(ProtectedZoneGuard.shellLex("rm -rf \"unclosed").parseOk)
    }

    // ── mvSources: the pure function ───────────────────────────────────────────

    @Test
    fun `mv sources are every positional except the destination`() {
        assertEquals(
            listOf("/tmp/a", "/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("/tmp/a", "/var/minis/shared", "/tmp/dst/")),
        )
        // One positional and no `-t`: nothing is destroyed.
        assertEquals(emptyList<String>(), ProtectedZoneGuard.mvSources(listOf("/tmp/a")))
    }

    @Test
    fun `mv with a target directory makes every positional a source`() {
        assertEquals(
            listOf("/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("-t", "/tmp", "/var/minis/shared")),
        )
        assertEquals(
            listOf("/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("-t/tmp", "/var/minis/shared")),
        )
        assertEquals(
            listOf("/var/minis/shared", "/var/minis/memory"),
            ProtectedZoneGuard.mvSources(listOf("--target-directory=/tmp", "/var/minis/shared", "/var/minis/memory")),
        )
        assertEquals(
            listOf("/var/minis/shared", "/var/minis/memory"),
            ProtectedZoneGuard.mvSources(listOf("--target-directory", "/tmp", "/var/minis/shared", "/var/minis/memory")),
        )
    }

    @Test
    fun `mv flags do not become sources`() {
        assertEquals(
            listOf("/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("-f", "/var/minis/shared", "/tmp/dst")),
        )
        assertEquals(
            listOf("/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("--force", "/var/minis/shared", "/tmp/dst")),
        )
    }

    // ── the fail-closed path ───────────────────────────────────────────────────

    @Test
    fun `an unreadable command that names a wide zone target and a verb is refused`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared\""))
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf \"/var/minis/shared"))
        // The wide literal is what decides, not the unreadable text around it.
        val hit = ProtectedZoneGuard.wideDeleteHit("rm -rf /var/minis/shared\"")
        assertNotNull(hit)
        assertEquals("/var/minis/shared", hit?.path)
        assertEquals(true, hit?.cwdKnown)
    }

    @Test
    fun `an unreadable command naming a whole immediate child of a zone is refused`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared/work\""))
        assertNotNull(ProtectedZoneGuard.refuseReason("grep -rn 'rm -rf' /var/minis/shared/work\""))
        val hit = ProtectedZoneGuard.wideDeleteHit("rm -rf /var/minis/shared/work\"")
        assertNotNull(hit)
        assertEquals("/var/minis/shared/work", hit?.path)
    }

    @Test
    fun `an unreadable command with a deep zone path is not refused`() {
        assertNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared/work/batch\""))
        // The zone literal is followed by a deeper path: nothing wide is named.
        assertNull(ProtectedZoneGuard.refuseReason("grep -rn 'rm -rf' /var/minis/shared/work/batch\""))
    }

    @Test
    fun `an unreadable command with no zone path is not refused`() {
        assertNull(ProtectedZoneGuard.refuseReason("rm -rf work/batch \"unterminated"))
    }

    @Test
    fun `the ceiling is unchanged`() {
        for (cmd in listOf(
            "rm -rf \$DIR",
            "rm -rf /var/minis/shared/*",
            "find /var/minis/shared -delete",
            "python3 -c 'import shutil; shutil.rmtree(\"/var/minis/shared\")'",
            "bash -c 'rm -rf /var/minis/shared'",
            "xargs rm -rf",
        )) {
            assertNull("documented ceiling: not recognised ($cmd)", ProtectedZoneGuard.refuseReason(cmd))
        }
    }
}

/**
 * [FIX-2D-1119-1] The six shapes the 1118C lexical layer still let through —
 * five bypasses and one P0.
 *
 * Every expectation is a literal path. The zone list is the thing under test,
 * so deriving an expectation from it (or from any other list the guard is
 * built from) would make a regression in that list invisible here.
 *
 * The negative-control block at the bottom is not optional: a guard that
 * refuses ordinary cleanup teaches the model to work around it, which is the
 * same failure as a bypass.
 */
class ProtectedZoneGuardParentZoneTest {

    // ── F-01 (P0): the parent of every zone was in no list ───────────────────

    /**
     * [F-01] `rm -rf /var/minis` deletes all five zones in one command. It was
     * the single most destructive spelling in the language and the only one
     * that fell through every arm.
     */
    @Test
    fun `deleting the parent of every zone is refused`() {
        for (cmd in listOf(
            "rm -rf /var/minis",
            "rm -rf /var/minis/",
            "rm -rf //var//minis//",
            "mv /var/minis /tmp/",
            "rm -rf /var",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
        val hit = ProtectedZoneGuard.wideDeleteHit("rm -rf /var/minis")
        assertEquals("/var/minis", hit?.path)
        // An absolute path needs no cwd, so the verdict is fully determined and
        // must not tell the agent to "retry with an absolute path".
        assertEquals(true, hit?.cwdKnown)
    }

    /**
     * [F-01] The parent is protected as a WHOLE only. `/var/minis/logs` is the
     * user's log directory (and `/var/tmp` the rootfs scratch): refusing those
     * would be over-protection, and the guard stays useful only while ordinary
     * cleanup still works.
     */
    @Test
    fun `a child of the parent is still ordinary cleanup`() {
        for (cmd in listOf(
            "rm -rf /var/minis/logs",
            "rm -rf /var/minis/logs/x",
            "rm -rf /var/minis/attachments",
            "rm -rf /var/minis/attachments/tmp",
            "rm -rf /var/minis/workspace",
            "rm -rf /var/tmp",
            "rm -rf /var/cache/apk",
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    /** [F-01] The five zones themselves are unchanged by the new parent rule. */
    @Test
    fun `the zones themselves still refuse next to their parent`() {
        for (cmd in listOf(
            "rm -rf /var/minis/shared",
            "rm -rf /var/minis/memory",
            "rm -rf /var/minis/skills",
            "rm -rf /var/minis/mcp-servers",
            "rm -rf /var/minis/mounts",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    // ── F-02: `-ft` is `-f` plus the target-directory flag ──────────────────

    /** [F-02] A short cluster containing `t` makes every positional a source. */
    @Test
    fun `mv with a clustered target flag flags the zone as a source`() {
        for (cmd in listOf(
            "mv -ft /tmp /var/minis/shared",
            "mv -vt /tmp /var/minis/shared",
            "mv -tf /tmp /var/minis/shared",
            "mv -vtf /tmp /var/minis/shared",
            "mv -t/tmp /var/minis/shared",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
        assertEquals(
            listOf("/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("-ft", "/tmp", "/var/minis/shared")),
        )
    }

    /**
     * [F-02] `-T` is `--no-target-directory`, the OPPOSITE flag: the last
     * positional is still the destination, and moving INTO a zone deletes
     * nothing there.
     */
    @Test
    fun `mv uppercase T is not the target flag`() {
        assertNull(ProtectedZoneGuard.refuseReason("mv -T /tmp/report.md /var/minis/shared"))
        assertNull(ProtectedZoneGuard.refuseReason("mv -fT /tmp/report.md /var/minis/shared"))
        assertEquals(
            listOf("/tmp/report.md"),
            ProtectedZoneGuard.mvSources(listOf("-T", "/tmp/report.md", "/var/minis/shared")),
        )
        // A plain `-f` cluster is not the target form either.
        assertEquals(
            listOf("/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("-vf", "/var/minis/shared", "/tmp/dst")),
        )
    }

    // ── F-03: parentheses and grouping braces are separators ─────────────────

    /** [F-03] `(rm …)` / `{ rm …; }` are statements, and the verb is `rm`. */
    @Test
    fun `subshell and group separators do not hide the verb`() {
        for (cmd in listOf(
            "(rm -rf /var/minis/shared)",
            "{ rm -rf /var/minis/shared; }",
            "(cd /var/minis/shared && rm -rf work)",
            "echo \$(rm -rf /var/minis/shared)",
            "true && (rm -rf /var/minis/shared) && true",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
        assertEquals(
            listOf("rm", "-rf", "/var/minis/shared"),
            ProtectedZoneGuard.shellLex("(rm -rf /var/minis/shared)").statements[0],
        )
        assertEquals(
            listOf("rm", "-rf", "/var/minis/shared"),
            ProtectedZoneGuard.shellLex("{ rm -rf /var/minis/shared; }").statements[0],
        )
        // `$(` is a substitution marker, not an argument: `echo` keeps no `$`.
        // Its interior RUNS first, so the interior statement is emitted before
        // the outer word list resumes ([FIX-3D1119-A] F-02 — the interior no
        // longer borrows the outer statement's verb, and the outer statement no
        // longer ends at the `(`).
        assertEquals(
            listOf(listOf("rm", "-rf", "/var/minis/shared"), listOf("echo")),
            ProtectedZoneGuard.shellLex("echo \$(rm -rf /var/minis/shared)").statements,
        )
        // `rm -rf <substitution> <zone>` keeps the verb and the target in ONE
        // statement, which is the P0 this branch used to lose.
        assertEquals(
            listOf(listOf("echo", "/tmp/x"), listOf("rm", "-rf", "/var/minis/shared")),
            ProtectedZoneGuard.shellLex("rm -rf \$(echo /tmp/x) /var/minis/shared").statements,
        )
    }

    /**
     * [F-03] A brace INSIDE a word is brace expansion, not grouping: its value
     * cannot be read, and splitting it would invent paths nobody wrote. This is
     * the documented ceiling, and it must stay a pass-through rather than
     * become a half-read path.
     */
    @Test
    fun `brace expansion stays a documented ceiling`() {
        for (cmd in listOf(
            "rm -rf /var/minis/{shared,memory}",
            "rm -rf /var/minis/shared/{a,b}",
            "find /var/minis/shared -name '*.md' -exec rm -rf {} +",
        )) {
            assertNull("documented ceiling: not recognised ($cmd)", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    // ── F-04: the wrapper value flags, by wrapper ───────────────────────────

    /** [F-04] Each wrapper's own value-taking options, in the spelling that used to slip. */
    @Test
    fun `wrapper value options are skipped per wrapper`() {
        for (cmd in listOf(
            "stdbuf -i 0 -o L rm -rf /var/minis/shared",
            "stdbuf -i L rm -rf /var/minis/shared",
            "stdbuf -e L rm -rf /var/minis/shared",
            "sudo -h host -u root rm -rf /var/minis/shared",
            "sudo -p 'pw' -g wheel rm -rf /var/minis/shared",
            "exec -a name rm -rf /var/minis/shared",
            "ionice -c 2 -p 1234 rm -rf /var/minis/shared",
            "chrt -p 50 rm -rf /var/minis/shared",
            "env -C /tmp -u HOME rm -rf /var/minis/shared",
            "taskset -c 0-3 rm -rf /var/minis/shared",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    /**
     * [F-04] `eval` runs the rest of the line as a command, so the first word
     * after it IS the verb. `env -i` must stay a boolean flag (it eats no
     * word) — that is why the flags are keyed by wrapper instead of one set.
     */
    @Test
    fun `eval peels to the verb and env dash i stays boolean`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("eval rm -rf /var/minis/shared"))
        assertNotNull(ProtectedZoneGuard.refuseReason("eval env -i rm -rf /var/minis/shared"))
        assertNotNull(ProtectedZoneGuard.refuseReason("env -i rm -rf /var/minis/shared"))
        assertNull(ProtectedZoneGuard.refuseReason("env -i ls /var/minis/shared"))
        // Quoting the whole command into one word is the new ceiling: the verb
        // word is the entire text, and reading it would mean parsing nested shell.
        assertNull(ProtectedZoneGuard.refuseReason("eval \"rm -rf /var/minis/shared\""))
    }

    // ── F-05: `popd` returns to the directory the stack remembers ───────────

    /**
     * [F-05] `pushd`/`popd` is `cd` with a stack: after the `popd` the shell
     * stands in the zone again, and `rm -rf work` is the zone's whole child.
     */
    @Test
    fun `popd restores the directory the next delete runs in`() {
        assertNotNull(
            ProtectedZoneGuard.refuseReason(
                "pushd /var/minis/shared && pushd /tmp && popd && rm -rf work",
            ),
        )
        assertNotNull(
            ProtectedZoneGuard.refuseReason("pushd /var/minis/shared && popd && rm -rf work"),
        )
        // Swapping back out of the zone is ordinary work again.
        assertNull(
            ProtectedZoneGuard.refuseReason(
                "pushd /var/minis/shared && pushd /tmp && popd && rm -rf /tmp/build",
            ),
        )
        // Nothing on the stack: `popd` leaves the cwd alone, so a deep relative
        // target is still ordinary work.
        assertNull(ProtectedZoneGuard.refuseReason("popd && rm -rf work/batch"))
    }

    // ── F-06: the fail-closed scan reads every occurrence ───────────────────

    /**
     * [F-06] An unreadable command that names a deep zone path first and a wide
     * one later used to be let through: the raw-text scan stopped at the first
     * occurrence. Every occurrence is now read.
     */
    @Test
    fun `an unreadable command is refused when any occurrence is wide`() {
        val cmd = "ls /var/minis/shared/a/b && rm -rf /var/minis/shared '"
        assertNotNull(ProtectedZoneGuard.refuseReason(cmd))
        assertEquals("/var/minis/shared", ProtectedZoneGuard.wideDeleteHit(cmd)?.path)
        assertEquals(true, ProtectedZoneGuard.wideDeleteHit(cmd)?.cwdKnown)
        // Only deep occurrences: still ordinary work.
        assertNull(
            ProtectedZoneGuard.refuseReason(
                "ls /var/minis/shared/a/b && rm -rf /var/minis/shared/work/batch '",
            ),
        )
        // The same rule for the parent zone.
        assertNotNull(ProtectedZoneGuard.refuseReason("ls /var/minis/logs && rm -rf /var/minis '"))
    }

    // ── the negative control set: eight ordinary commands ───────────────────

    /**
     * The eight commands from the task book, verbatim. All six fixes widen the
     * refusal surface, so this set is what keeps the guard from turning into
     * "any mention of a zone".
     */
    @Test
    fun `the eight ordinary commands still pass`() {
        for (cmd in listOf(
            "rm /var/minis/shared/README.md",
            "rm -rf /var/minis/shared/work/one-batch-dir",
            "rm -rf /var/minis/logs/x",
            "rm -rf /var/minis/attachments/tmp",
            "mv /tmp/report.md /var/minis/shared/",
            "mv /var/minis/shared/work/a.md /tmp/",
            "grep -rn 'rm -rf' /var/minis/shared",
            "nice -n 10 ls /var/minis/shared",
        )) {
            assertNull("must still allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }
}

/**
 * [FIX-3D1119-A] The five lexical defects the 3D-1119 audit found in the
 * guard's FINAL state ([ProtectedZoneGuardParentZoneTest] pins the shapes the
 * 1118C fixes added; these are the spellings that still slipped through it).
 *
 * Every expectation is a literal command and a literal boolean. A guard fails
 * in two directions and neither is tolerable: a bypass loses data, a false
 * refusal teaches the agent to work around the guard — and the workaround
 * (`find -delete`) is worse than the bypass it escapes. Both sides are pinned
 * here, for every change.
 */
class ProtectedZoneGuardLexTest {

    // ── F-02 (P0): `$(` and `$((` are NOT statement separators ────────────

    /**
     * [F-02] A substitution inside a delete must not cut the verb off from the
     * paths that follow it: the old `(`-is-a-separator reading parsed
     * `rm -rf $(echo x) <zone>` as three statements and left `<zone>` in one
     * without a verb.
     */
    @Test
    fun `a substitution does not cut the verb off from its targets`() {
        for (cmd in listOf(
            "rm -rf \$(echo /tmp/x) /var/minis/shared",
            "rm -rf \$(echo /tmp/x) /var/minis/shared/work",
            "rm -rf \$(echo /tmp/x) /var/minis",
            "rm -rf \$(cat \$(echo /tmp/x)) /var/minis/shared",
            "sudo rm -rf \$(echo /tmp/x) /var/minis/shared",
            "rm -rf \$((1+2)) /var/minis/shared",
            "rm -rf \$((1+2)) /var/minis",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
        assertEquals(
            listOf(listOf("echo", "/tmp/x"), listOf("rm", "-rf", "/var/minis/shared")),
            ProtectedZoneGuard.shellLex("rm -rf \$(echo /tmp/x) /var/minis/shared").statements,
        )
    }

    /**
     * [F-02] The interior of a substitution RUNS, so it is judged as its own
     * statement; `$((` is an expression, not a command, and stays one opaque
     * word (it carries `$`, so [ProtectedZoneGuard.absoluteGuestPath] reads it
     * as a non-literal exactly like `$VAR`).
     */
    @Test
    fun `the interior of a substitution is judged and arithmetic stays opaque`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("echo \$(rm -rf /var/minis/shared)"))
        assertEquals(
            listOf("rm", "-rf", "\$((1+2))", "/var/minis/shared"),
            ProtectedZoneGuard.shellLex("rm -rf \$((1+2)) /var/minis/shared").statements[0],
        )    }

    /**
     * [F-02] The change must not widen the refusal surface: the value a
     * substitution PRODUCES is not a literal, and moving INTO a zone is not a
     * delete of it.
     */
    @Test
    fun `the substitution does not widen the refusal surface`() {
        for (cmd in listOf(
            "mv \$(echo /tmp/a) /var/minis/shared",
            "rm -rf \$(echo /tmp/x) /tmp/build",
            "rm -rf \$(echo /tmp/x)",
            // The substitution's output is not readable as a path: the same
            // documented ceiling `$VAR` sits under.
            "rm -rf \$(echo /var/minis/shared)",
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    /** [F-02] An unclosed substitution is unreadable, so it fails closed. */
    @Test
    fun `an unclosed substitution is refused`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf \$(echo /var/minis/shared"))
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf \$(echo /var/minis/shared && echo 'x"))
    }

    /**
     * [F-02] The words that follow `)` still belong to the verb that opened
     * the statement: `rm -rf $(echo x) work` is `rm` with two arguments, not
     * `rm` with one and a bare `work` statement.
     */
    @Test
    fun `the words after a substitution stay arguments of the outer verb`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf \$(echo /tmp/x) work"))
        assertEquals(
            listOf(listOf("echo", "/tmp/x"), listOf("rm", "-rf", "work")),
            ProtectedZoneGuard.shellLex("rm -rf \$(echo /tmp/x) work").statements,
        )
    }

    // ── F-03a: the fail-closed path uses the same delimiters ───────────────

    /**
     * [F-03a] `(`/`)` must delimit words and path literals in the raw-text scan
     * too. It did not: the verb arrived as `(rm` and the path as
     * `<zone>)`, and neither matched, so `(rm -rf <zone>) && <stray quote>`
     * was allowed.
     */
    @Test
    fun `the fail-closed scan reads parentheses as delimiters`() {
        for (cmd in listOf(
            "(rm -rf /var/minis/shared) && echo 'unclosed",
            "(cd /var/minis/shared && rm -rf work) && echo \"oops",
            "{ rm -rf /var/minis/shared; } && echo 'unclosed",
            "true && (rm -rf /var/minis/shared) && echo 'unclosed",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
        assertEquals(
            "/var/minis/shared",
            ProtectedZoneGuard.wideDeleteHit("(rm -rf /var/minis/shared) && echo 'unclosed")?.path,
        )
        // Only the WIDE targets are refused here: a deep path in a subshell is
        // ordinary work, and a subshell that only mentions the zone is not a
        // delete.
        assertNull(
            ProtectedZoneGuard.refuseReason("(rm -rf /var/minis/shared/work/batch) && echo 'unclosed"),
        )
        assertNull(ProtectedZoneGuard.refuseReason("(ls /var/minis/shared) && echo 'unclosed"))
    }

    // ── F-01: a zone literal must start at a word boundary ─────────────────

    /**
     * [F-01] `indexOf` found `/var` inside `/tmp/var` and `/var/minis` inside
     * `/opt/var/minis`, so an unbalanced line naming an unrelated path was
     * refused for deleting a zone.
     */
    @Test
    fun `a zone literal must start at a word boundary in the fail-closed scan`() {
        for (cmd in listOf(
            "rm -rf /tmp/var && echo 'unclosed",
            "rm -rf /tmp/var/minis && echo 'unclosed",
            "rm -rf /opt/var/minis && echo 'unclosed",
            "rm -rf /var/minis2 && echo 'unclosed",
            "rm -rf /var/minis2/shared && echo 'unclosed",
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
        // The real literals are still caught — bare, quoted, and as a value.
        for (cmd in listOf(
            "rm -rf /var && echo 'unclosed",
            "rm -rf /var/minis && echo 'unclosed",
            "rm -rf \"/var/minis/shared\" 'unclosed",
            "DST=/var/minis && rm -rf x 'unclosed",
        )) {
            assertNotNull("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    // ── F-04: the parents must not get a weaker refusal ─────────────────────

    /**
     * [F-04] `zoneOf` deliberately knows nothing about `ROOT_ONLY_ZONES`, so a
     * hit that IS one of them used to come out with the weakest text available
     * — deleting `/var/minis` clears all five zones, which is the shape that
     * most needs the referent confirmation.
     */
    @Test
    fun `the parent zones get the same referent confirmation as a zone root`() {
        val zoneRoot = ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared") ?: ""
        assertTrue("zone root must ask for the referent", zoneRoot.contains("confirm which files"))
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis"))
        assertNotNull(ProtectedZoneGuard.refuseReason("rm -rf /var"))
        for (pair in listOf(
            "rm -rf /var/minis" to "/var/minis",
            "rm -rf /var" to "/var",
            "mv /var/minis /tmp/" to "/var/minis",
        )) {
            val msg = ProtectedZoneGuard.refuseReason(pair.first) ?: ""
            assertTrue("must name the path: ${pair.first}", msg.contains(pair.second))
            assertTrue("must ask for the referent: ${pair.first}", msg.contains("confirm which files"))
        }
        // A named child is a real instruction, not a no-referent one: unchanged.
        val sub = ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared/work") ?: ""
        assertFalse(sub.contains("confirm which files"))
    }

    // ── F-05: `-S` / `--suffix` take a value ────────────────────────────────

    /** [F-05] A bare `S` at the end of the cluster eats the NEXT word. */
    @Test
    fun `mv short suffix option consumes its value instead of treating it as a source`() {
        assertEquals(
            listOf("/tmp/a"),
            ProtectedZoneGuard.mvSources(listOf("-S", "/var/minis/shared", "/tmp/a", "/tmp/b")),
        )
        assertNull(ProtectedZoneGuard.refuseReason("mv -S /var/minis/shared /tmp/a /tmp/b"))
        // Same rule when `S` closes a cluster.
        assertEquals(
            emptyList<String>(),
            ProtectedZoneGuard.mvSources(listOf("-fS", "/var/minis/shared", "/tmp/a")),
        )
        assertNull(ProtectedZoneGuard.refuseReason("mv -fS /var/minis/shared /tmp/a"))
        // An attached suffix stays in the same word, so the zone is still a source.
        assertEquals(
            listOf("/var/minis/shared"),
            ProtectedZoneGuard.mvSources(listOf("-S.bak", "/var/minis/shared", "/tmp/a")),
        )
        assertNotNull(ProtectedZoneGuard.refuseReason("mv -S.bak /var/minis/shared /tmp/a"))
    }

    /** [F-05] The long form of the suffix flag takes a value too. */
    @Test
    fun `mv long suffix flag consumes its value`() {
        assertEquals(
            listOf("/tmp/a"),
            ProtectedZoneGuard.mvSources(listOf("--suffix", "/var/minis/shared", "/tmp/a", "/tmp/b")),
        )
        assertNull(ProtectedZoneGuard.refuseReason("mv --suffix /var/minis/shared /tmp/a /tmp/b"))
        assertNull(ProtectedZoneGuard.refuseReason("mv --suffix=/var/minis/shared /tmp/a /tmp/b"))
    }

    /** [F-05] `stdbuf -o` gets its own anchor, not only coverage inside a cluster. */
    @Test
    fun `stdbuf -o alone still reaches the verb`() {
        assertNotNull(ProtectedZoneGuard.refuseReason("stdbuf -o L rm -rf /var/minis/shared"))
        assertNotNull(ProtectedZoneGuard.refuseReason("stdbuf -o L rm -rf /var/minis"))
        assertNull(ProtectedZoneGuard.refuseReason("stdbuf -o L ls /var/minis/shared"))
    }
}
