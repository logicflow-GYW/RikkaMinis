package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [C9/C7] Wiring probes for the protected-zone check on the fastio path.
 *
 * Measured defect (2026-11-18): `minis-fastio rm -r /var/minis/shared/__probe__`
 * deleted the directory, exit 0, zero refusals — [FastioOffloadHandler.rmRefusal]
 * had four checks (external mount / bind root / not found / is-directory) and
 * none of them was the zone guard, while [com.rikkaminis.app.sandbox.ProtectedZoneGuard]'s
 * own KDoc claimed this handler was "the one that cannot be spelled around".
 * The shell guard never sees a fastio call: this handler deletes HOST files
 * directly, so `rm -rf /var/minis/shared` through it removed the whole zone
 * with the lexical guard silent.
 *
 * The edges cannot be exercised on the JVM (a resolution needs a real
 * Application for `context.filesDir` and the bind-mount map), so the wiring is
 * asserted against the sources — same shape as [FastioWiringTest], and every
 * probe has a negative control that mutates a copy and asserts the check fails.
 */
class FastioZoneGuardTest {

    private val handlerFile =
        "src/main/java/com/rikkaminis/app/sandbox/offload/FastioOffloadHandler.kt"
    private val builtinsFile = "src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt"
    private val registryFile = "src/main/java/com/rikkaminis/app/config/ConfigRegistry.kt"

    // ── probes ──────────────────────────────────────────────────────────────

    /**
     * `rm` must consult the SAME predicate and the SAME switch as the shell
     * path. A second copy of the zone list here would be the first step back
     * to two policies that disagree.
     */
    private fun assertRmChecksZones(handler: String) {
        val body = bodyOf(handler, "rmRefusal")
        assertTrue(
            "rmRefusal must judge the target with ProtectedZoneGuard.isRefusedDelete " +
                "(single source of truth for what counts as a wide delete)",
            body.contains("ProtectedZoneGuard.isRefusedDelete(resolution.guestPath, recursive)"),
        )
        assertTrue(
            "rmRefusal must read the zone switch — the same store the shell path reads",
            body.contains("ZoneDeleteGuardStore.isEnabled"),
        )
        assertTrue(
            "the refusal must carry its own reason code so the audit line can key on it",
            body.contains("\"protected_zone\""),
        )
        // The zone verdict must not depend on the directory's current state:
        // "refused because it is a zone" has to come before "does not exist".
        // Anchored on the RETURN statements, not on the words: a KDoc that
        // mentions `not_found` above the check would otherwise satisfy this.
        val zoneIdx = body.indexOf("return \"protected_zone\"")
        val notFoundIdx = body.indexOf("return \"not_found\"")
        assertTrue("rmRefusal must still carry the not_found arm", notFoundIdx >= 0)
        assertTrue(
            "the zone check must precede not_found, or a missing zone reports the wrong reason",
            zoneIdx in 1 until notFoundIdx,
        )
    }

    /** [C5] `mv` destroys its source, and it has no `-r` to test. */
    private fun assertMvChecksZone(handler: String) {
        val body = bodyOf(handler, "transferRefusal")
        assertTrue(
            "mv's SOURCE must be judged as a recursive removal — a moved-out zone child " +
                "is the same delete as rm -rf",
            body.contains("ProtectedZoneGuard.isRefusedDelete(src.guestPath, recursive = true)"),
        )
        assertTrue(
            "mv must read the same zone switch",
            body.contains("ZoneDeleteGuardStore.isEnabled"),
        )
        // Only the source: `mv x /var/minis/shared` puts x INTO the zone.
        assertFalse(
            "the DESTINATION must not be judged — moving something into a zone deletes nothing",
            body.contains("ProtectedZoneGuard.isRefusedDelete(dst.guestPath"),
        )
    }

    /** [C7] Both paths record the attempt, so "I turned it off" stays reviewable. */
    private fun assertZoneAuditRecorded(handler: String) {
        assertTrue(
            "the allowed-delete audit event must still be emitted when the switch is off",
            handler.contains("\"zone-delete-allowed\""),
        )
        assertTrue(
            "the refusal must be recorded as well, or a blocked attempt leaves no trace",
            handler.contains("\"refuse-zone\""),
        )
    }

    /**
     * [C7] The switch must NOT be reachable through `minis-config`: an agent
     * that was just refused is exactly the caller that would otherwise turn its
     * own guard off, which makes the guard decorative.
     */
    private fun assertSwitchNotInConfigRegistry(builtins: String, registry: String) {
        for (text in listOf(builtins, registry)) {
            assertFalse(
                "ZoneDeleteGuardStore must not appear in the config registry — " +
                    "minis-config would then be a path to disable the guard",
                text.contains("ZoneDeleteGuardStore"),
            )
        }
        assertFalse(
            "no config field may expose the zone switch by name",
            builtins.contains("zone_delete_guard"),
        )
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test
    fun `fastio rm refuses a wide delete inside a protected zone`() {
        assertRmChecksZones(source(handlerFile))
    }

    @Test
    fun `fastio mv refuses a wide source inside a protected zone`() {
        assertMvChecksZone(source(handlerFile))
    }

    @Test
    fun `both fastio paths record the zone verdict`() {
        assertZoneAuditRecorded(source(handlerFile))
    }

    @Test
    fun `the zone switch is not reachable from minis-config`() {
        assertSwitchNotInConfigRegistry(source(builtinsFile), source(registryFile))
    }

    // ── negative controls ───────────────────────────────────────────────────

    @Test
    fun `negative control - dropping the zone check from rm makes the probe fail`() {
        val original = source(handlerFile)
        val mutated = original.replace(
            "ProtectedZoneGuard.isRefusedDelete(resolution.guestPath, recursive)",
            "false",
        )
        assertTrue("the check must exist to be removable", mutated != original)
        assertProbeFails { assertRmChecksZones(mutated) }
    }

    @Test
    fun `negative control - reading auto-approve instead of the zone switch makes the probe fail`() {
        val original = source(handlerFile)
        val mutated = original.replace(
            "ZoneDeleteGuardStore.isEnabled",
            "com.rikkaminis.app.config.AutoApproveStore.isEnabled",
        )
        assertTrue("the switch read must exist to be swappable", mutated != original)
        assertProbeFails { assertRmChecksZones(mutated) }
    }

    @Test
    fun `negative control - moving the zone check after not_found makes the probe fail`() {
        val original = source(handlerFile)
        val body = bodyOf(original, "rmRefusal")
        val zoneBlock = body.substringAfter("        // [C9]").substringBefore("        if (!Files.exists")
        assertTrue("the zone block must be locatable", zoneBlock.isNotEmpty())
        val mutated = original.replace(zoneBlock, "")
        assertTrue("the mutation must change the source", mutated != original)
        assertProbeFails { assertRmChecksZones(mutated) }
    }

    @Test
    fun `negative control - dropping the mv zone check makes the probe fail`() {
        val original = source(handlerFile)
        val mutated = original.replace(
            "ProtectedZoneGuard.isRefusedDelete(src.guestPath, recursive = true)",
            "false",
        )
        assertTrue("the check must exist to be removable", mutated != original)
        assertProbeFails { assertMvChecksZone(mutated) }
    }

    @Test
    fun `negative control - removing the allowed-delete audit makes the probe fail`() {
        val original = source(handlerFile)
        val mutated = original.replace("\"zone-delete-allowed\"", "\"unused-event\"")
        assertTrue("the event must exist to be renamed", mutated != original)
        assertProbeFails { assertZoneAuditRecorded(mutated) }
    }

    @Test
    fun `negative control - registering the switch in the config registry makes the probe fail`() {
        val original = source(builtinsFile)
        val mutated = original + "\n// ZoneDeleteGuardStore is registered here\n"
        assertTrue("the mutation must change the source", mutated != original)
        assertProbeFails { assertSwitchNotInConfigRegistry(mutated, source(registryFile)) }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun assertProbeFails(probe: () -> Unit) {
        var failed = false
        try {
            probe()
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the probe must fail on the mutated source", failed)
    }

    /** Body of `private fun <name>(…)` up to the next member. */
    private fun bodyOf(handler: String, funName: String): String {
        val start = handler.indexOf("private fun $funName(")
        assertTrue("$funName(...) must exist", start >= 0)
        val candidates = listOf(
            handler.indexOf("\n    private fun ", start + 1),
            handler.indexOf("\n    // ──", start + 1),
        ).filter { it > 0 }
        val end = candidates.minOrNull() ?: handler.length
        return handler.substring(start, end)
    }

    private fun source(rel: String): String = sourceFile(rel).readText()

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }
}
