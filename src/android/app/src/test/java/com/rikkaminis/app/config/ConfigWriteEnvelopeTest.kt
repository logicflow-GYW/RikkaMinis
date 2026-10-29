package com.rikkaminis.app.config

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * [R5-E F-03] Behavioural contract of the write-batch envelope and the row
 * failure classifier. These are the REAL functions from `ConfigBridge.kt`
 * (top-level, dependency-free) running against real `org.json` — no mocks, no
 * source-text assertions.
 *
 * The contract the old code got wrong: a batch where SOME rows applied and
 * SOME failed answered `ok=true` / "Settings updated." with exit code 0, so
 * the agent told the user every setting was saved. It now answers
 * `ok=false` + `error="partial_write"` + `applied[]` + `failed[]`.
 * `ok=false` (not `ok=true` + non-empty `failed[]`) is the deliberate choice:
 * `ok` is the ONLY field the offload handler maps onto the process exit code
 * (`ConfigOffloadHandler.exitCodeFromEnvelope`), so a success-looking
 * envelope would still exit 0 — see the contract table on
 * [ConfigBridge.performWriteBatch].
 *
 * SELF-TEST DISCIPLINE: expectations below are LITERALS, never derived from
 * production constants.
 */
class ConfigWriteEnvelopeTest {

    // ------------------------------------------------------------------
    // The headline case: 1 applied + 1 failed.
    // ------------------------------------------------------------------
    @Test
    fun oneAppliedOneFailedReportsPartialWrite() {
        val applied = JSONArray().put(
            JSONObject().put("path", "models.a.contextWindow"),
        )
        val failed = JSONArray().put(
            writeFailureEntry("models.b.maxOutputTokens", "already_exists", "duplicate"),
        )
        val env = buildWriteEnvelope(applied, failed, null)

        assertFalse("partial write must NOT claim ok", env.optBoolean("ok", true))
        assertEquals("partial_write", env.getString("error"))
        assertEquals(1, env.getJSONArray("applied").length())
        assertEquals("models.a.contextWindow", env.getJSONArray("applied").getJSONObject(0).getString("path"))
        assertEquals(1, env.getJSONArray("failed").length())
        val row = env.getJSONArray("failed").getJSONObject(0)
        assertEquals("models.b.maxOutputTokens", row.getString("path"))
        assertEquals("already_exists", row.getString("error"))
        assertEquals("duplicate", row.getString("reason"))
        assertTrue(
            "user_message must tell the agent to retry the failed subset",
            env.getString("user_message").contains("Retry"),
        )
    }

    @Test
    fun oneAppliedTwoFailedCarriesBothFailedRows() {
        val applied = JSONArray().put(JSONObject().put("path", "p1"))
        val failed = JSONArray()
            .put(writeFailureEntry("p2", "invalid_value", "bad enum"))
            .put(writeFailureEntry("p3", "permission_denied", "read-only"))
        val env = buildWriteEnvelope(applied, failed, null)
        assertEquals("partial_write", env.getString("error"))
        assertEquals(2, env.getJSONArray("failed").length())
        assertEquals("p2", env.getJSONArray("failed").getJSONObject(0).getString("path"))
        assertEquals("p3", env.getJSONArray("failed").getJSONObject(1).getString("path"))
    }

    // ------------------------------------------------------------------
    // Unchanged arms (regression guard).
    // ------------------------------------------------------------------
    @Test
    fun nothingAppliedWithFailureAnswersTheFirstFailureEnvelope() {
        val early = JSONObject()
            .put("ok", false)
            .put("error", "write_failed")
            .put("reason", "boom")
        val env = buildWriteEnvelope(JSONArray(), JSONArray(), early)
        assertFalse(env.optBoolean("ok", true))
        assertEquals("write_failed", env.getString("error"))
        assertEquals("boom", env.getString("reason"))
        assertFalse("whole-batch failure has no partial marker", env.has("failed"))
    }

    @Test
    fun nothingAppliedNoFailureAnswersAllRejected() {
        val env = buildWriteEnvelope(JSONArray(), JSONArray(), null)
        assertFalse(env.optBoolean("ok", true))
        assertEquals("all_rejected", env.getString("error"))
        assertEquals("No changes applied.", env.getString("user_message"))
    }

    @Test
    fun everythingAppliedStillAnswersPlainSuccess() {
        val applied = JSONArray()
            .put(JSONObject().put("path", "a"))
            .put(JSONObject().put("path", "b"))
        val env = buildWriteEnvelope(applied, JSONArray(), null)
        assertTrue(env.optBoolean("ok", false))
        assertEquals(2, env.getJSONArray("applied").length())
        assertFalse("plain success must not invent a failed list", env.has("failed"))
    }

    // ------------------------------------------------------------------
    // Row classification.
    // ------------------------------------------------------------------
    @Test
    fun cancellationIsNotAWriteFailure() {
        // The loop rethrows on null; if classification ever returned a code
        // here, a cancelled batch would be recorded as a failed row again.
        assertNull(classifyWriteFailure(CancellationException("job cancelled")))
    }

    @Test
    fun alreadyExistsKeepsItsCode() {
        val (code, reason) = classifyWriteFailure(
            ConfigError.AlreadyExists("models.b: duplicate"),
        )!!
        assertEquals("already_exists", code)
        // ConfigError prefixes its own message; classification passes it through
        // verbatim (pre-existing behaviour, not re-formatted here).
        assertEquals("already_exists: models.b: duplicate", reason)
    }

    @Test
    fun permissionDeniedKeepsItsCode() {
        val (code, reason) = classifyWriteFailure(
            ConfigError.PermissionDenied("read-only"),
        )!!
        assertEquals("permission_denied", code)
        assertEquals("permission_denied: read-only", reason)
    }

    @Test
    fun otherConfigErrorsAreInvalidValue() {
        val (code, _) = classifyWriteFailure(
            ConfigError.InvalidValue("invalid_value: bad enum"),
        )!!
        assertEquals("invalid_value", code)
    }

    @Test
    fun plainRuntimeFailuresAreWriteFailedWithReason() {
        val (code, reason) = classifyWriteFailure(IllegalStateException("disk full"))!!
        assertEquals("write_failed", code)
        assertEquals("disk full", reason)
    }

    @Test
    fun throwableWithoutMessageFallsBackToCodeText() {
        val (code, reason) = classifyWriteFailure(RuntimeException())!!
        assertEquals("write_failed", code)
        assertEquals("Write failed.", reason)
    }

    @Test
    fun failureEntryShapeIsPathErrorReason() {
        val entry = writeFailureEntry("x.y", "already_exists", "why")
        assertEquals("x.y", entry.getString("path"))
        assertEquals("already_exists", entry.getString("error"))
        assertEquals("why", entry.getString("reason"))
    }
}
