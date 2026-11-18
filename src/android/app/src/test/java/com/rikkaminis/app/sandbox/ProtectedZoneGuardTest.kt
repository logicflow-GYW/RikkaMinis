package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [protect-zones-1118] The guard that refuses a delete aimed at a whole
 * protected zone.
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

    @Test
    fun `the refusal names the target and says how to override`() {
        val msg = ProtectedZoneGuard.refuseReason("rm -rf /var/minis/shared") ?: ""
        assertTrue("must name the path", msg.contains("/var/minis/shared"))
        assertTrue("must point at the existing switch", msg.contains("auto-approve"))
    }

    // ── the shapes that must still work (no over-refusal) ────────────────────

    @Test
    fun `deleting files inside a zone is allowed`() {
        for (cmd in listOf(
            "rm /var/minis/shared/README.md",
            "rm -rf /var/minis/shared/work/prompteval-1118",
            "rm -f /var/minis/memory/2026-11-18.md",
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
        )) {
            assertNull("should allow: $cmd", ProtectedZoneGuard.refuseReason(cmd))
        }
    }

    // ── the user's own switch ────────────────────────────────────────────────

    @Test
    fun `auto-approve turns the refusal off but keeps the target visible`() {
        val cmd = "rm -rf /var/minis/shared/work"
        assertTrue(ProtectedZoneGuard.refuseReason(cmd) != null)
        assertNull(ProtectedZoneGuard.refuseReason(cmd, autoApprove = true))
        // The audit line comes from this same call, so an allowed delete is
        // still attributable after the fact.
        assertEquals("/var/minis/shared/work", ProtectedZoneGuard.wideDeleteTarget(cmd))
    }

    @Test
    fun `the guard uses the existing master switch, not a new one`() {
        // Guard against someone "helpfully" adding a second toggle: the only
        // parameter that can disable the refusal must be the auto-approve one.
        val params = ProtectedZoneGuard::class.java.methods
            .first { it.name == "refuseReason" }
            .parameterTypes.map { it.simpleName }
        assertEquals(listOf("String", "String", "boolean"), params)
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
    }

    @Test
    fun `wrapper prefixes are peeled`() {
        assertTrue(ProtectedZoneGuard.refuseReason("sudo rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("time rm -rf /var/minis/shared") != null)
        assertTrue(ProtectedZoneGuard.refuseReason("cd /tmp && rm -rf /var/minis/shared") != null)
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
