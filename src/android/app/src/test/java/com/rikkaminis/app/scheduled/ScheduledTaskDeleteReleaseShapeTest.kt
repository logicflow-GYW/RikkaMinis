package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [R5-B2 F-04 / C-02②③ / C-06①] Wiring shape for the four fixes that live in
 * Android-bound code (a `Context`-taking object store and a `MinisApp` runner),
 * so no pure-logic seam exists to test against: what has to be pinned is that
 * the CALL SITES consume the signal, in the order that makes them correct.
 *
 * Each assertion is written so the PRE-FIX tree fails it (verified with a
 * pre/post probe, not by inspection): a shape test that also passes on the bug
 * is a comment, not a test.
 */
class ScheduledTaskDeleteReleaseShapeTest {

    // ── F-04: a refused delete must not release the runner lock ──────────────

    @Test
    fun deleteTask_gatesTheReleaseOnTheTaskReallyBeingGone() {
        val body = functionBody(storeCode(), "suspend fun deleteTask(")
        val guard = body.indexOf("next.tasks.any { it.id == taskId }")
        val release = body.indexOf("ScheduledTaskRunner.onTasksRemoved(")
        assertTrue("deleteTask no longer inspects the returned document", guard >= 0)
        assertTrue("the release call is gone or now precedes the guard", release > guard)
        // The guard has to RETURN: setting a flag and falling through would
        // still release the lock.
        assertTrue(
            "the refused-write guard does not return before the release",
            body.substring(guard, release).contains("return next"),
        )
        // Exactly one release, and it is NOT nested inside the guard: the
        // landed-delete path must stay unconditional (da1016 B-7c).
        assertEquals(
            "deleteTask must call the release exactly once",
            1,
            Regex(Regex.escape("ScheduledTaskRunner.onTasksRemoved(")).findAll(body).count(),
        )
        assertEquals(
            "the release moved inside a block — LOCK ORDER and the "
                + "unconditional-release contract both depend on it being a "
                + "top-level statement of deleteTask",
            1,
            braceDepth(body, release),
        )
    }

    @Test
    fun deleteTask_keepsTheUnconditionalReleaseContractForLandedDeletes() {
        // The rationale lives in a COMMENT, so this reads the raw file: the
        // comment is the contract, and dropping it invites the next reader to
        // "simplify" the guard into a conditional release.
        assertTrue(
            "the [da1016 B-7c] rationale was dropped — the next reader will "
                + "reasonably 'simplify' the guard into a conditional release",
            repoFile(STORE_REL).contains("[da1016 B-7c]"),
        )
        val body = functionBody(storeCode(), "suspend fun deleteTask(")
        // The guard must key on the TASK ID, not on the write outcome being
        // reported some other way: `next` is the document that is on disk.
        assertTrue(body.contains("return next"))
    }

    // ── C-02②: keep the bytes a restore is about to destroy ─────────────────

    @Test
    fun importDocument_preservesTheUnreadableFileBeforeOverwritingIt() {
        val body = functionBody(storeCode(), "suspend fun importDocument(")
        val preserve = body.indexOf("preserveUnreadableLocked(context)")
        val write = body.indexOf("writeLocked(context")
        assertTrue("the unreadable-file preservation is gone", preserve >= 0)
        assertTrue("preservation must run BEFORE the overwrite", write > preserve)
        assertTrue(
            "preservation must be gated on the unreadable flag, otherwise every "
                + "restore leaves a stray copy",
            body.substring(maxOf(0, preserve - 200), preserve).contains("if (unreadable)"),
        )
        val helper = functionBody(storeCode(), "private fun preserveUnreadableLocked(")
        assertTrue("the copy must be verbatim", helper.contains("copyTo("))
        assertTrue("older copies must be pruned, or a restore loop fills the disk",
            helper.contains("UNREADABLE_COPY_KEEP"))
    }

    // ── C-02③: the live file reads `version` as strictly as restore does ────

    @Test
    fun loadLocked_noLongerCoercesABogusVersionIntoThisBuildsVersion() {
        val code = storeCode()
        val body = functionBody(code, "private fun loadLocked(")
        assertTrue(
            "loadLocked still uses optInt(\"version\", VERSION), which maps 0 / "
                + "a negative / a string onto v1 and then rewrites the document",
            body.contains("declaredVersionOf(JSONObject(raw))"),
        )
        assertFalse(
            "the coercing read is still present somewhere in the store — "
                + "`optInt(\"version\", VERSION)` maps 0 / a negative / a string "
                + "onto this build's version; the import side already reads "
                + "`optInt(\"version\", -1)` so a foreign value is refused there",
            code.contains("optInt(\"version\", ScheduledTasksCodec.VERSION)") ||
                code.contains("optInt(\"version\", VERSION)"),
        )
    }

    @Test
    fun declaredVersion_treatsNonPositiveAndNonNumericAsUnusable() {
        val helper = functionBody(storeCode(), "private fun declaredVersionOf(")
        assertTrue(
            "a version that is not a positive integer must be flagged, not "
                + "rounded into a usable one",
            helper.contains("INVALID_DECLARED_VERSION"),
        )
        assertTrue(helper.contains("value <= 0"))
        assertTrue(
            "an absent key must still read as this build's own shape",
            helper.contains("if (!root.has(\"version\")) return null"),
        )
    }

    // ── C-06①: an abandoned fire must not leave an empty session ────────────

    @Test
    fun runner_dropsTheSessionItCreatedOnTheEarlyReturn() {
        val body = functionBody(runnerCode(), "private suspend fun fire(")
        val abandon = body.indexOf("if (!ScheduledTaskStorePolicy.markerLanded(")
        assertTrue("the markerLanded gate moved", abandon >= 0)
        val block = blockFrom(body, body.indexOf('{', abandon))
        val drop = block.indexOf("dropNewSession(app, sid)")
        val clear = block.indexOf("AgentRunBudgetCeilings.clear(sid)")
        assertTrue(
            "the early return still leaves the freshly created scheduled "
                + "session (an empty chat in the drawer) behind",
            drop >= 0,
        )
        assertTrue("the session must be dropped on that path", drop < clear)
    }

    @Test
    fun runner_dropsTheSessionWhenPreparationItselfThrows() {
        val body = functionBody(runnerCode(), "private suspend fun prepareSession(")
        val ensure = body.indexOf("HeadlessChatRunner.ensureSession(app)")
        val drop = body.indexOf("dropNewSession(app, sessionId)")
        assertTrue(ensure >= 0)
        assertTrue(
            "a throwing presentation step leaves an inserted session no caller "
                + "can name — fire's catch has sessionId == null",
            drop > ensure,
        )
        assertTrue("the throw must survive the cleanup", body.contains("throw t"))
    }

    @Test
    fun dropNewSession_containsItsOwnFailure() {
        val body = functionBody(runnerCode(), "private suspend fun dropNewSession(")
        assertTrue(body.contains("deleteSession(sessionId)"))
        assertTrue(
            "a failed cleanup must not take the fire path's error handling down",
            body.contains("catch (t: Throwable)"),
        )
    }

    // ── F-04 (screen half): a refused delete must not pop ────────────────────

    @Test
    fun editScreen_checksTheDeleteResultBeforeLeaving() {
        val code = stripComments(repoFile(EDIT_REL))
        val call = code.indexOf("ScheduledTasksStore.deleteTask(")
        assertTrue("the delete call site moved", call >= 0)
        val before = code.substring(maxOf(0, call - 60), call).trimEnd()
        assertTrue(
            "the delete result is discarded — a refused write then looks exactly "
                + "like a successful one and the screen pops",
            before.endsWith("=") && "val " in before,
        )
        val ret = code.indexOf("onDone()", call)
        assertTrue(ret > call)
        val between = code.substring(call, ret)
        assertTrue(
            "the not-deleted arm must surface the refusal and stay on the screen",
            between.contains("after.tasks.any { it.id == taskId }") &&
                between.contains("deleteRefused = true"),
        )
        assertTrue(
            "deleteRefused is never rendered",
            code.contains("deleteRefused) {"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun storeCode(): String = stripComments(repoFile(STORE_REL))

    private fun runnerCode(): String = stripComments(repoFile(RUNNER_REL))

    /** Body of the function whose signature starts with [signature]. */
    private fun functionBody(code: String, signature: String): String {
        val at = code.indexOf(signature)
        require(at >= 0) { "$signature not found" }
        val open = code.indexOf('{', at)
        require(open > 0) { "$signature has no body" }
        return blockFrom(code, open)
    }

    /** Body of the balanced `{ … }` starting at [open] (which must be a '{'). */
    private fun blockFrom(code: String, open: Int): String {
        if (open < 0 || open >= code.length || code[open] != '{') return ""
        var depth = 0
        var i = open
        while (i < code.length) {
            if (code[i] == '{') depth += 1
            else if (code[i] == '}') {
                depth -= 1
                if (depth == 0) return code.substring(open + 1, i)
            }
            i += 1
        }
        return ""
    }

    /** Brace depth at [index] inside [body], where the function body starts at 1. */
    private fun braceDepth(body: String, index: Int): Int {
        var depth = 1
        for (i in 0 until index.coerceAtMost(body.length)) {
            when (body[i]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
        }
        return depth
    }

    /** Drop `//` line comments and `/* … */` blocks so quoted prose can't match. */
    private fun stripComments(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '/' && i + 1 < code.length && code[i + 1] == '/') {
                while (i < code.length && code[i] != '\n') i += 1
            } else if (c == '/' && i + 1 < code.length && code[i + 1] == '*') {
                i += 2
                while (i + 1 < code.length && !(code[i] == '*' && code[i + 1] == '/')) i += 1
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }

    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val STORE_REL = "app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTasksStore.kt"
        const val RUNNER_REL = "app/src/main/java/com/rikkaminis/app/scheduled/ScheduledTaskRunner.kt"
        const val EDIT_REL = "app/src/main/java/com/rikkaminis/app/ui/settings/ScheduledTaskEditScreen.kt"
    }
}
