package com.rikkaminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T3-retry-side-effects] JVM tests for the trusted tool safety registry
 * and the caller-claim downgrade rule.
 */
class ToolRetrySafetyRegistryTest {

    // ── default classification ────────────────────────────────────────

    @Test
    fun `file_read defaults to READ_ONLY`() {
        assertEquals(RetrySafety.READ_ONLY, ToolRetrySafetyRegistry.defaultSafetyFor("file_read"))
    }

    @Test
    fun `read_image and memory_get default to READ_ONLY`() {
        assertEquals(RetrySafety.READ_ONLY, ToolRetrySafetyRegistry.defaultSafetyFor("read_image"))
        assertEquals(RetrySafety.READ_ONLY, ToolRetrySafetyRegistry.defaultSafetyFor("memory_get"))
    }

    @Test
    fun `file_write file_edit and memory_write default to NON_IDEMPOTENT_WRITE`() {
        assertEquals(RetrySafety.NON_IDEMPOTENT_WRITE, ToolRetrySafetyRegistry.defaultSafetyFor("file_write"))
        assertEquals(RetrySafety.NON_IDEMPOTENT_WRITE, ToolRetrySafetyRegistry.defaultSafetyFor("file_edit"))
        assertEquals(RetrySafety.NON_IDEMPOTENT_WRITE, ToolRetrySafetyRegistry.defaultSafetyFor("memory_write"))
        assertEquals(RetrySafety.NON_IDEMPOTENT_WRITE, ToolRetrySafetyRegistry.defaultSafetyFor("memory_rollup"))
        assertEquals(RetrySafety.NON_IDEMPOTENT_WRITE, ToolRetrySafetyRegistry.defaultSafetyFor("spawn_agent"))
    }

    @Test
    fun `shell_execute and browser_use default to UNKNOWN`() {
        assertEquals(RetrySafety.UNKNOWN, ToolRetrySafetyRegistry.defaultSafetyFor("shell_execute"))
        assertEquals(RetrySafety.UNKNOWN, ToolRetrySafetyRegistry.defaultSafetyFor("browser_use"))
    }

    @Test
    fun `unregistered tool defaults to UNKNOWN`() {
        // The registry never guesses: unknown tools are NOT transparently retried.
        assertEquals(RetrySafety.UNKNOWN, ToolRetrySafetyRegistry.defaultSafetyFor("some_future_tool"))
        assertFalse(ToolRetrySafetyRegistry.isRegistered("some_future_tool"))
    }

    @Test
    fun `registered tools are recognized`() {
        assertTrue(ToolRetrySafetyRegistry.isRegistered("file_read"))
        assertTrue(ToolRetrySafetyRegistry.isRegistered("shell_execute"))
    }

    // ── caller claim can only downgrade, never upgrade ─────────────────

    @Test
    fun `caller claim cannot upgrade UNKNOWN to READ_ONLY`() {
        // LLM or untrusted caller claiming "READ_ONLY" must NOT be trusted.
        assertEquals(
            RetrySafety.UNKNOWN,
            ToolRetrySafetyRegistry.lookup("shell_execute", RetrySafety.READ_ONLY),
        )
    }

    @Test
    fun `caller claim cannot upgrade NON_IDEMPOTENT_WRITE to READ_ONLY`() {
        assertEquals(
            RetrySafety.NON_IDEMPOTENT_WRITE,
            ToolRetrySafetyRegistry.lookup("file_write", RetrySafety.READ_ONLY),
        )
    }

    @Test
    fun `caller claim can downgrade READ_ONLY to UNKNOWN`() {
        // A trusted caller may declare "this read-only command must not be
        // auto-retried" — downgrading is allowed.
        assertEquals(
            RetrySafety.UNKNOWN,
            ToolRetrySafetyRegistry.lookup("file_read", RetrySafety.UNKNOWN),
        )
    }

    @Test
    fun `no caller claim keeps default`() {
        assertEquals(RetrySafety.READ_ONLY, ToolRetrySafetyRegistry.lookup("file_read"))
        assertEquals(RetrySafety.UNKNOWN, ToolRetrySafetyRegistry.lookup("shell_execute"))
    }

    // ── RetrySafety.applyCallerClaim unit semantics ───────────────────

    @Test
    fun `applyCallerClaim keeps more conservative claim`() {
        assertEquals(RetrySafety.UNKNOWN, RetrySafety.READ_ONLY.applyCallerClaim(RetrySafety.UNKNOWN))
        assertEquals(RetrySafety.NON_IDEMPOTENT_WRITE, RetrySafety.READ_ONLY.applyCallerClaim(RetrySafety.NON_IDEMPOTENT_WRITE))
        assertEquals(RetrySafety.IDEMPOTENT_WRITE, RetrySafety.READ_ONLY.applyCallerClaim(RetrySafety.IDEMPOTENT_WRITE))
    }

    @Test
    fun `applyCallerClaim rejects less conservative claim`() {
        assertEquals(RetrySafety.UNKNOWN, RetrySafety.UNKNOWN.applyCallerClaim(RetrySafety.READ_ONLY))
        assertEquals(RetrySafety.NON_IDEMPOTENT_WRITE, RetrySafety.NON_IDEMPOTENT_WRITE.applyCallerClaim(RetrySafety.IDEMPOTENT_WRITE))
    }

    @Test
    fun `applyCallerClaim null keeps original`() {
        assertEquals(RetrySafety.READ_ONLY, RetrySafety.READ_ONLY.applyCallerClaim(null))
        assertEquals(RetrySafety.UNKNOWN, RetrySafety.UNKNOWN.applyCallerClaim(null))
    }

    @Test
    fun `applyCallerClaim equal claim keeps original`() {
        assertEquals(RetrySafety.READ_ONLY, RetrySafety.READ_ONLY.applyCallerClaim(RetrySafety.READ_ONLY))
        assertEquals(RetrySafety.UNKNOWN, RetrySafety.UNKNOWN.applyCallerClaim(RetrySafety.UNKNOWN))
    }

    // ── conservativeness ordering ──────────────────────────────────────

    @Test
    fun `conservativeness ordering is read only lt idempotent lt non idempotent lt unknown`() {
        assertTrue(RetrySafety.READ_ONLY.conservativeness < RetrySafety.IDEMPOTENT_WRITE.conservativeness)
        assertTrue(RetrySafety.IDEMPOTENT_WRITE.conservativeness < RetrySafety.NON_IDEMPOTENT_WRITE.conservativeness)
        assertTrue(RetrySafety.NON_IDEMPOTENT_WRITE.conservativeness < RetrySafety.UNKNOWN.conservativeness)
    }
}
