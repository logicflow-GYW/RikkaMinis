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
        assertEquals(
            listOf(listOf("echo"), listOf("rm", "-rf", "/var/minis/shared")),
            ProtectedZoneGuard.shellLex("echo \$(rm -rf /var/minis/shared)").statements,
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
