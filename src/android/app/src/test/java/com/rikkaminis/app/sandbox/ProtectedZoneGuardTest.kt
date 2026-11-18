package com.rikkaminis.app.sandbox

import com.rikkaminis.app.config.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [protect-zones-1118] The guard that refuses a delete aimed at a whole
 * protected zone, plus [ZoneDeleteGuardStore] — the switch that owns it.
 *
 * Defect this pins: in the prompt-eval harness (2026-11-18) the production
 * prompt answered "把那些文件删掉" — no referent at all — by running `rm` in 6
 * of 8 runs, and one of those calls was `rm -v /var/minis/shared/README.md`.
 * `shared/` is the zone the backup protects; the backup restores the moment it
 * ran, so a file written and deleted in between is gone.
 *
 * Expectations are literals, never derived from [ProtectedZoneGuard.ZONES]:
 * deriving them would let a zone edit move the expectation with it and turn a
 * regression into a PASS.
 */
class ProtectedZoneGuardTest {

    // ── the shapes that must be refused ──────────────────────────────────────

    @Test
    fun `deleting a protected zone root is refused`() {
        for (cmd in listOf(
            "rm -rf /var/minis/shared",
            "rm -rf /var/minis/memory",
            "rm -r /var/minis/skills",
            "rm -rf /var/minis/mcp-servers",
            "rm -rf /var/minis/mounts",
        )) {
            assertTrue("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd) != null)
        }
    }

    @Test
    fun `deleting a whole subdirectory of a zone is refused`() {
        for (cmd in listOf(
            "rm -rf /var/minis/shared/work",
            "rm -rf /var/minis/memory/rules",
            "cd /var/minis/shared && rm -rf work",
        )) {
            assertTrue("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd) != null)
        }
    }

    /** [C2] `busybox` is a wrapper in this rootfs, not a verb. */
    @Test
    fun `busybox prefix does not hide the verb`() {
        assertTrue(ProtectedZoneGuard.refuseReason("busybox rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("busybox rm -rf /var/minis/shared/work") != null)
    }

    /**
     * [C2] Recursion is judged by the letters in the short cluster, not by a
     * fixed list of spellings: `-rfv`, `-irf`, `-Rfv` are the same delete, and
     * the enumerated set let every one of them through.
     */
    @Test
    fun `combined short flags still count as recursive`() {
        for (cmd in listOf(
            "rm -rfv /var/minis/shared",
            "rm -irf /var/minis/shared",
            "rm -Rfv /var/minis/shared",
            "rm -vrf /var/minis/shared/work",
        )) {
            assertTrue("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd) != null)
        }
        // The long spelling stays literal: `--force` contains an `r` but is not
        // recursion, and reading it as such would refuse `rm --force <file>`.
        assertNull(ProtectedZoneGuard.refuseReason("rm --force /var/minis/shared/README.md"))
    }

    /** [C2] Quoting the path is not an escape hatch. */
    @Test
    fun `quoted targets are unquoted before judging`() {
        assertTrue(ProtectedZoneGuard.refuseReason("rm -rf \"/var/minis/shared\"") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("rm -rf '/var/minis/shared/work'") != null)
        assertEquals("/var/minis/shared", ProtectedZoneGuard.absoluteGuestPath("\"/var/minis/shared\"", "/root"))
        // A quoted ABSOLUTE path needs no cwd, so its verdict must not be
        // reported as "unknown directory" — that note tells the agent to do
        // something (add an absolute path) it already did.
        val hit = ProtectedZoneGuard.wideDeleteHit("rm -rf \"/var/minis/shared\"")
        assertEquals(true, hit?.cwdKnown)
        assertEquals(false, ProtectedZoneGuard.refusalFor(hit!!).contains("absolute path"))
    }

    /** [C2] A wrapper's own options must not be mistaken for the verb. */
    @Test
    fun `wrapper options are skipped before the verb`() {
        assertTrue(ProtectedZoneGuard.refuseReason("env -i rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("sudo -u root rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("env -u HOME rm -rf /var/minis/shared") != null)
    }

    /** [C8] The verbs that had no coverage at all. */
    @Test
    fun `rmdir shred and mv are recognised as tree removal`() {
        for (cmd in listOf(
            "rmdir /var/minis/shared",
            "shred /var/minis/memory",
            "mv /var/minis/shared /tmp/gone",
            "mv /var/minis/shared/work /tmp/gone",
            "mv -f /var/minis/memory /tmp/gone",
        )) {
            assertTrue("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd) != null)
        }
    }

    /**
     * [C5] `mv` destroys its SOURCE, so a zone child moved out is the same
     * delete. The accepted cost is that a single FILE inside a zone cannot be
     * moved either — a lexical guard cannot tell a file from a directory, and
     * one retry with `cp` + `rm` costs far less than a whole folder that no
     * backup brings back.
     */
    @Test
    fun `moving a single file out of a zone is refused too, and the cost is documented`() {
        assertTrue(
            ProtectedZoneGuard.refuseReason("mv /var/minis/shared/README.md /tmp/") != null,
        )
        // The DESTINATION is not a target: moving something INTO a zone deletes
        // nothing there.
        assertNull(ProtectedZoneGuard.refuseReason("mv /tmp/report.md /var/minis/shared/"))
    }

    /** [C8] A separator or a full path must not hide the command. */
    @Test
    fun `separators and absolute binary paths do not hide the delete`() {
        for (cmd in listOf(
            "ls && rm -rf /var/minis/shared",
            "true; rm -rf /var/minis/shared",
            "echo x | rm -rf /var/minis/shared",
            "/bin/rm -rf /var/minis/shared",
            "/usr/bin/busybox rm -rf /var/minis/shared",
            "rm -rf -- /var/minis/shared",
            "rm --recursive /var/minis/shared",
        )) {
            assertTrue("should refuse: $cmd", ProtectedZoneGuard.refuseReason(cmd) != null)
        }
    }

    /** [C8] `..` must be resolved before the zone test, not after. */
    @Test
    fun `dot-dot is resolved before judging`() {
        assertTrue(
            ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared/work/../../shared") != null,
        )
        // Resolves out of every zone: ordinary work.
        assertNull(ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared/work/../work/batch"))
        // Climbing above the root stays at the root, so this is `/shared`.
        assertEquals("/shared", ProtectedZoneGuard.absoluteGuestPath("/../../shared", "/root"))
    }

    /** [C8] `pushd` is `cd` with a stack; the following delete sees the same cwd. */
    @Test
    fun `pushd is tracked like cd`() {
        assertTrue(ProtectedZoneGuard.refuseReason("pushd /var/minis/shared && rm -rf work") != null)
    }

    // ── the shapes that must still work (no over-refusal) ────────────────────

    @Test
    fun `deleting files inside a zone is allowed`() {
        for (cmd in listOf(
            "rm /var/minis/shared/README.md",
            "rm -rf /var/minis/shared/work/prompteval-1118",
            "rm -f /var/minis/memory/2026-11-18.md",
            "rm -v /var/minis/shared/README.md",
            "unlink /var/minis/shared/README.md",
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    @Test
    fun `scratch areas are never protected`() {
        for (cmd in listOf(
            "rm -rf /var/minis/workspace",
            "rm -rf /var/minis/workspace/src",
            "rm -rf /tmp/build",
            "rm -rf /var/minis/offloads",
            "rm -rf /var/minis/browser",
            "mv /tmp/a /tmp/b",
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    @Test
    fun `read-only and unrelated commands are untouched`() {
        for (cmd in listOf(
            "ls -la /var/minis/shared",
            "find /var/minis/shared -name '*.md'",
            "grep -r x /var/minis/shared",
            "cat /var/minis/memory/GLOBAL.md",
            "git rm docs/x.md",
            "cp -r /var/minis/shared /tmp/backup",
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    // ── [C4] the working directory is either known or admitted unknown ───────

    @Test
    fun `a known cwd resolves a relative target`() {
        assertTrue(
            ProtectedZoneGuard.refuseReason("rm -rf work", cwd = "/var/minis/shared") != null,
        )
        assertNull(
            ProtectedZoneGuard.refuseReason("rm -rf build", cwd = "/var/minis/workspace"),
        )
    }

    /**
     * [C4] The old call site passed nothing, so the default `/root` silently
     * judged `rm -rf work` as `/root/work`. Unknown is now unknown: a relative
     * target is refused when any base that could hold a zone makes it wide, and
     * the refusal says which fact is missing.
     */
    @Test
    fun `an unknown cwd fails closed on a relative wide target`() {
        val reason = ProtectedZoneGuard.refuseReason("rm -rf work")
        assertTrue("should refuse with no cwd", reason != null)
        assertTrue("must say why", reason!!.contains("absolute path"))
        val hit = ProtectedZoneGuard.wideDeleteHit("rm -rf work")
        assertEquals(false, hit?.cwdKnown)
        // An absolute target is decided either way, and is marked known.
        assertEquals(true, ProtectedZoneGuard.wideDeleteHit("rm -rf /var/minis/shared")?.cwdKnown)
    }

    /** A relative NON-wide target is still ordinary work when the cwd is unknown. */
    @Test
    fun `an unknown cwd does not refuse a relative deep target`() {
        assertNull(ProtectedZoneGuard.refuseReason("rm -rf work/batch"))
        assertNull(ProtectedZoneGuard.refuseReason("rm -f notes.md"))
    }

    // ── [C7] the switch ─────────────────────────────────────────────────────

    @Test
    fun `the guard is a pure function of path and recursion`() {
        // Policy lives in one predicate so the shell path and the fastio path
        // cannot disagree; the switch is read by the CALLER, never in here.
        assertTrue(ProtectedZoneGuard.isRefusedDelete("/var/minis/shared", recursive = true))
        assertTrue(ProtectedZoneGuard.isRefusedDelete("/var/minis/shared/work", recursive = true))
        assertFalse(ProtectedZoneGuard.isRefusedDelete("/var/minis/shared/work/batch", recursive = true))
        assertFalse(ProtectedZoneGuard.isRefusedDelete("/var/minis/shared", recursive = false))
        assertFalse(ProtectedZoneGuard.isRefusedDelete("/var/minis/workspace", recursive = true))
        val params = ProtectedZoneGuard::class.java.methods
            .first { it.name == "refuseReason" }
            .parameterTypes.map { it.simpleName }
        // No boolean switch parameter: the old `autoApprove` arm is gone, and
        // the only disable path is the store the UI owns.
        assertEquals(listOf("String", "String"), params)
    }

    // ── [C6b] the refusal text must not teach a bypass ───────────────────────

    @Test
    fun `the refusal names the target and does not point at a way around it`() {
        val msg = ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared") ?: ""
        assertTrue("must name the path", msg.contains("/var/minis/shared"))
        assertTrue("must say what to do instead", msg.contains("name the files"))
        // The old text told the model "turn on auto-approve in Settings to skip
        // this check", which invited it to talk the user into disabling the
        // guard so its own task could finish.
        assertFalse("must not name the switch", msg.contains("auto-approve"))
        assertFalse("must not point at Settings", msg.contains("Settings"))
        // The zone ROOT is the no-referent case the harness measured: ask for
        // confirmation instead of retrying.
        assertTrue("must ask for the referent", msg.contains("confirm which files"))
        // A named subdirectory is a real instruction, not a no-referent one.
        val sub = ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared/work") ?: ""
        assertFalse(sub.contains("confirm which files"))
    }

    // ── lexical limits, stated rather than hidden ────────────────────────────

    @Test
    fun `non-literal targets fall through by design`() {
        for (cmd in listOf(
            "rm -rf \$DIR",
            "rm -rf /var/minis/shared/*",
            "find /var/minis/shared -delete",
            "python3 -c 'import shutil; shutil.rmtree(\"/var/minis/shared\")'",
        )) {
            assertNull(
                "documented ceiling: not recognised ($cmd)",
                ProtectedZoneGuard.refuseReason(cmd),
            )
        }
    }

    // ── path normalisation ───────────────────────────────────────────────────

    @Test
    fun `relative and dotted paths resolve before judging`() {
        assertEquals("/var/minis/shared", ProtectedZoneGuard.absoluteGuestPath("../shared", "/var/minis/memory"))
        assertEquals("/var/minis/shared", ProtectedZoneGuard.absoluteGuestPath("/var/minis/memory/../shared", "/root"))
        assertEquals("/var/minis/shared", ProtectedZoneGuard.absoluteGuestPath("//var//minis//shared", "/root"))
        assertNull(ProtectedZoneGuard.absoluteGuestPath("\$HOME/x", "/root"))
        // Unknown cwd + relative token = no resolution at all (never `/root`).
        assertNull(ProtectedZoneGuard.absoluteGuestPath("shared", null))
    }

    @Test
    fun `wrapper prefixes are peeled`() {
        assertTrue(ProtectedZoneGuard.refuseReason("sudo rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("time rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("cd /tmp && rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("nohup command rm -rf /var/minis/shared") != null)
    }

    @Test
    fun `no zone nests inside another`() {
        // `zoneOf` picks the LONGEST matching prefix so a future nested zone
        // (say `/var/minis/shared/work` protected separately) would still
        // resolve correctly. With today's flat list that branch is equivalent
        // to "first match", and a mutation flipping it survives — this test is
        // what keeps the invariant honest: add a nested zone and the guard's
        // longest-prefix behaviour becomes load-bearing, not decorative.
        for (a in ProtectedZoneGuard.ZONES) {
            for (b in ProtectedZoneGuard.ZONES) {
                if (a == b) continue
                assertTrue(
                    "zone '$b' nests inside '$a' — zoneOf's longest-prefix rule is now load-bearing",
                    !b.startsWith("$a/"),
                )
            }
        }
    }
}

/**
 * [C7] The switch itself: default ON, persisted, idempotent install, and NOT
 * reachable from `minis-config` (an agent that was just refused must not be
 * able to switch its own guard off).
 */
class ZoneDeleteGuardStoreTest {

    companion object {
        private val prefs = FakeSharedPreferences()

        init {
            // install() is idempotent and the store is a process singleton, so
            // the fake is wired exactly once for the whole class.
            ZoneDeleteGuardStore.install(prefs)
        }
    }

    @Test
    fun `the default is protection on`() {
        assertTrue("protection must be the default", ZoneDeleteGuardStore.DEFAULT_ENABLED)
        assertTrue(ZoneDeleteGuardStore.isEnabled)
        assertTrue(ZoneDeleteGuardStore.enabled.value)
    }

    @Test
    fun `toggling off persists and publishes`() {
        ZoneDeleteGuardStore.setEnabled(false)
        assertFalse(ZoneDeleteGuardStore.isEnabled)
        assertEquals(false, prefs.getBoolean(ZoneDeleteGuardStore.KEY_ENABLED, true))
        ZoneDeleteGuardStore.setEnabled(true)
        assertTrue(ZoneDeleteGuardStore.isEnabled)
        assertEquals(true, prefs.getBoolean(ZoneDeleteGuardStore.KEY_ENABLED, false))
    }

    @Test
    fun `an unset preference reads as on`() {
        // An upgrade from a build without this flag must arrive protected: the
        // key is absent, and absent must mean ON, not "whatever false is".
        val fresh = FakeSharedPreferences()
        assertEquals(
            true,
            fresh.getBoolean(ZoneDeleteGuardStore.KEY_ENABLED, ZoneDeleteGuardStore.DEFAULT_ENABLED),
        )
    }
}
