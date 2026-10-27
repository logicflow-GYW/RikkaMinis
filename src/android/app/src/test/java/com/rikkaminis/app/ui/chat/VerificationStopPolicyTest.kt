package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [feat/verification-stop] Pure classification tests. */
class VerificationStopPolicyTest {

    // ── isNonCodePath ──────────────────────────────────────────────────────

    @Test
    fun `prose extensions are non-code`() {
        for (p in listOf("/a/b/SKILL.md", "README.md", "notes.txt", "data.csv",
                         "conf.yaml", "style.css", "page.html")) {
            assertTrue("expected non-code: $p", VerificationStopPolicy.isNonCodePath(p))
        }
    }

    @Test
    fun `build scripts are code not prose`() {
        // .gradle/.kts are build code — they gate verification.
        assertFalse(VerificationStopPolicy.isNonCodePath("build.gradle"))
        assertFalse(VerificationStopPolicy.isNonCodePath("settings.kts"))
    }

    @Test
    fun `code extensions are NOT non-code`() {
        for (p in listOf("Main.kt", "app.py", "index.js", "main.rs", "lib.go",
                         "App.java", "tool.sh", "run.rb")) {
            assertFalse("expected code: $p", VerificationStopPolicy.isNonCodePath(p))
        }
    }

    @Test
    fun `extensionless well-known basenames are non-code`() {
        assertTrue(VerificationStopPolicy.isNonCodePath("LICENSE"))
        assertTrue(VerificationStopPolicy.isNonCodePath("/repo/CHANGELOG"))
        assertTrue(VerificationStopPolicy.isNonCodePath("Dockerfile"))
    }

    @Test
    fun `blank and unknown paths are not non-code`() {
        assertFalse(VerificationStopPolicy.isNonCodePath(""))
        assertFalse(VerificationStopPolicy.isNonCodePath("somebinary"))
    }

    // ── changedPathFromArgs ────────────────────────────────────────────────

    @Test
    fun `file tools yield their path`() {
        assertEquals("/tmp/x.kt",
            VerificationStopPolicy.changedPathFromArgs("file_write", """{"path":"/tmp/x.kt","content":"..."}"""))
        assertEquals("/var/minis/workspace/a.py",
            VerificationStopPolicy.changedPathFromArgs("file_edit", """{"path":"/var/minis/workspace/a.py"}"""))
    }

    @Test
    fun `non-file tools and bad json yield null`() {
        assertNull(VerificationStopPolicy.changedPathFromArgs("shell_execute", """{"command":"ls"}"""))
        assertNull(VerificationStopPolicy.changedPathFromArgs("file_write", "{not json"))
        assertNull(VerificationStopPolicy.changedPathFromArgs("file_write", """{"path":""}"""))
    }

    // ── verificationKind ───────────────────────────────────────────────────

    @Test
    fun `test lint typecheck build commands classify`() {
        assertEquals("lint", VerificationStopPolicy.verificationKind("ruff check src/"))
        assertEquals("lint", VerificationStopPolicy.verificationKind("npm run lint"))
        assertEquals("typecheck", VerificationStopPolicy.verificationKind("tsc --noEmit"))
        assertEquals("build", VerificationStopPolicy.verificationKind("./gradlew assembleDebug"))
        assertEquals("build", VerificationStopPolicy.verificationKind("kotlinc A.kt -d out.jar"))
        assertEquals("test", VerificationStopPolicy.verificationKind("python3 -m pytest test_x.py"))
        assertEquals("test", VerificationStopPolicy.verificationKind("java -cp app.jar org.junit.runner.JUnitCore com.x.T"))
    }

    @Test
    fun `env and wrapper prefixes are stripped`() {
        assertEquals("test", VerificationStopPolicy.verificationKind("env LC_ALL=C python3 -m pytest"))
        assertEquals("lint", VerificationStopPolicy.verificationKind("time ruff check ."))
        assertEquals("test", VerificationStopPolicy.verificationKind("FOO=1 BAR=2 pytest"))
    }

    @Test
    fun `ad-hoc temp verify scripts classify`() {
        assertEquals("ad_hoc", VerificationStopPolicy.verificationKind("sh /tmp/verify-fix.sh"))
        assertEquals("ad_hoc", VerificationStopPolicy.verificationKind("python3 /tmp/minis-verify-t84.py"))
        assertEquals("ad_hoc", VerificationStopPolicy.verificationKind("/tmp/verify-smoke.sh"))
    }

    @Test
    fun `ordinary commands are not verification`() {
        assertNull(VerificationStopPolicy.verificationKind("ls -la"))
        assertNull(VerificationStopPolicy.verificationKind("cat /tmp/verify-notes.md"))
        assertNull(VerificationStopPolicy.verificationKind("echo hello"))
        assertNull(VerificationStopPolicy.verificationKind("rm -rf /tmp/junk"))
        assertNull(VerificationStopPolicy.verificationKind("python3 /var/minis/workspace/script.py"))
        assertNull(VerificationStopPolicy.verificationKind(""))
    }

    // ── hasFreshVerification ───────────────────────────────────────────────
    // [deep-audit-1015 BUG-04] The turn-end guard must not demand verification
    // the run has already done. Both stamps are readings of ONE monotonic
    // clock (AgentLoopState.evidenceClock), so a plain `>` is the happens-after
    // test; a run that never edited code has nothing to be fresh about.

    @Test
    fun `a passing verification after the last edit is fresh`() {
        assertTrue(VerificationStopPolicy.hasFreshVerification(lastEditStamp = 1, lastVerifyStamp = 3))
    }

    @Test
    fun `no verification, or an older one, is not fresh`() {
        assertFalse("no verification yet", VerificationStopPolicy.hasFreshVerification(1, 0))
        assertFalse("verification predates the edit", VerificationStopPolicy.hasFreshVerification(3, 2))
        assertFalse("never edited code", VerificationStopPolicy.hasFreshVerification(0, 5))
        assertFalse("nothing at all", VerificationStopPolicy.hasFreshVerification(0, 0))
    }

    @Test
    fun `an edit after the passing verification makes the evidence stale again`() {
        // edit(1) → verify(2) → edit(3): the newest edit has no evidence.
        assertFalse(VerificationStopPolicy.hasFreshVerification(lastEditStamp = 3, lastVerifyStamp = 2))
    }

    @Test
    fun `two independent per-kind counters could not express this order`() {
        // The shape BUG-04 had: one counter bumped per edit, one per passing
        // verification. After (edit, edit, verify) the edit counter is 2 and
        // the verify counter 1, so a "verify > edit" comparison on those
        // numbers reads as NO — the exact mis-order the shared clock removes.
        val editsSeen = 2
        val passesSeen = 1
        assertFalse("independent counters mis-order this history", passesSeen > editsSeen)
        // The shared-clock stamps for the same history.
        assertTrue(
            "the shared clock records that the verification was the newest event",
            VerificationStopPolicy.hasFreshVerification(lastEditStamp = 2, lastVerifyStamp = 3),
        )
    }

    // ── buildNudge ─────────────────────────────────────────────────────────

    @Test
    fun `nudge fires for code edits`() {
        val nudge = VerificationStopPolicy.buildNudge(
            listOf("/tmp/app/Main.kt"), attempts = 0, lastEvidenceDetail = null, nudgeLimit = 2)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("/tmp/app/Main.kt"))
        assertTrue(nudge.contains("verification"))
    }

    @Test
    fun `prose-only edits never nudge`() {
        assertNull(VerificationStopPolicy.buildNudge(
            listOf("/var/minis/skills/x/SKILL.md", "README.md"), attempts = 0, lastEvidenceDetail = null,
            nudgeLimit = 2))
    }

    // [fix/verify-nudges-default-off] The shipped default is 0 (= guard off),
    // so every case above pins its own limit explicitly. These two cases are
    // the ones the default flip actually depends on: 0 must never nudge, and
    // an exhausted budget must still stop.
    @Test
    fun `limit zero never nudges`() {
        assertNull(VerificationStopPolicy.buildNudge(
            listOf("/tmp/app/Main.kt"), attempts = 0, lastEvidenceDetail = null, nudgeLimit = 0))
    }

    @Test
    fun `attempts exhausted never nudge`() {
        assertNull(VerificationStopPolicy.buildNudge(
            listOf("/tmp/app/Main.kt"),
            attempts = 2,
            lastEvidenceDetail = null,
            nudgeLimit = 2))
    }

    @Test
    fun `mixed edits list only code paths`() {
        val nudge = VerificationStopPolicy.buildNudge(
            listOf("README.md", "/src/A.kt", "/src/B.kt"), attempts = 0, lastEvidenceDetail = "test run FAILED (exit 1)",
            nudgeLimit = 2)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("/src/A.kt"))
        assertFalse(nudge.contains("README.md"))
        assertTrue(nudge.contains("FAILED (exit 1)"))
    }

    @Test
    fun `long path lists are capped`() {
        val paths = (1..20).map { "/src/File$it.kt" }
        val nudge = VerificationStopPolicy.buildNudge(paths, attempts = 0, lastEvidenceDetail = null, nudgeLimit = 2)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("... and 12 more"))
        assertFalse(nudge.contains("File20.kt"))
    }

    // ── wiring: declared AND called ────────────────────────────────────────

    @Test
    fun `the engine actually consults the freshness stamp`() {
        // [deep-audit-1015 BUG-04] The original defect was exactly this: the
        // ordering bookkeeping existed and was never read. A pure function
        // plus a unit test would reproduce that shape perfectly, so pin the
        // call site in the engine too.
        val lines = readRepoFile(ENGINE).split("\n")
        val callIdx = lines.indexOfFirst { it.contains("VerificationStopPolicy.hasFreshVerification(") }
        assertTrue("the verify-nudge gate must consult hasFreshVerification", callIdx > 0)

        // …inside the `verifyNudge` decision, i.e. above the buildNudge call
        // that the gate would otherwise take unconditionally.
        val buildIdx = lines.indexOfFirst { it.contains("VerificationStopPolicy.buildNudge(") }
        assertTrue("gate must precede the nudge it guards, $callIdx vs $buildIdx", callIdx < buildIdx)
        assertTrue("the two must belong to the same decision", buildIdx - callIdx <= TAIL_WINDOW)
    }

    /** Walks up from user.dir so the test runs from the module dir or the repo root. */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val ENGINE = "app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"
        const val TAIL_WINDOW = 40
    }
}
