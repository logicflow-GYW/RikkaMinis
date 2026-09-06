package com.openminis.app.ui.chat

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

    // ── buildNudge ─────────────────────────────────────────────────────────

    @Test
    fun `nudge fires for code edits`() {
        val nudge = VerificationStopPolicy.buildNudge(
            listOf("/tmp/app/Main.kt"), attempts = 0, lastEvidenceDetail = null)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("/tmp/app/Main.kt"))
        assertTrue(nudge.contains("verification"))
    }

    @Test
    fun `prose-only edits never nudge`() {
        assertNull(VerificationStopPolicy.buildNudge(
            listOf("/var/minis/skills/x/SKILL.md", "README.md"), attempts = 0, lastEvidenceDetail = null))
    }

    @Test
    fun `attempts exhausted never nudge`() {
        assertNull(VerificationStopPolicy.buildNudge(
            listOf("/tmp/app/Main.kt"),
            attempts = VerificationStopPolicy.MAX_VERIFY_NUDGES,
            lastEvidenceDetail = null))
    }

    @Test
    fun `mixed edits list only code paths`() {
        val nudge = VerificationStopPolicy.buildNudge(
            listOf("README.md", "/src/A.kt", "/src/B.kt"), attempts = 0, lastEvidenceDetail = "test run FAILED (exit 1)")
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("/src/A.kt"))
        assertFalse(nudge.contains("README.md"))
        assertTrue(nudge.contains("FAILED (exit 1)"))
    }

    @Test
    fun `long path lists are capped`() {
        val paths = (1..20).map { "/src/File$it.kt" }
        val nudge = VerificationStopPolicy.buildNudge(paths, attempts = 0, lastEvidenceDetail = null)
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("... and 12 more"))
        assertFalse(nudge.contains("File20.kt"))
    }
}
