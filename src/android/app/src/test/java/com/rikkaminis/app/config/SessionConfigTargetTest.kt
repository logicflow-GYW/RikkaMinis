package com.rikkaminis.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv24-A05] the routing decision behind `minis-config`'s `session.*` write,
 * pinned as behaviour.
 *
 * `ConfigBuiltins` itself is not JVM-compilable (ConfigRegistry, ChatViewModel,
 * Android Context), so the decision lives in [SessionConfigWriteThrough] — a
 * pure, Android-free object — and this test is the ground truth for the truth
 * table. Expectations are written as literals, never derived from the
 * production predicate: a decision that silently flips must fail here, not
 * agree with itself.
 */
class SessionConfigTargetTest {

    // ── the routing decision ────────────────────────────────────────────────

    @Test
    fun `a live view model means the write has to reach it`() {
        assertEquals(
            SessionConfigWriteThrough.Route.ResidentViewModel,
            SessionConfigWriteThrough.route(hasLiveViewModel = true, hasPersistedRow = true),
        )
    }

    @Test
    fun `a live view model outranks the row`() {
        // A draft chat (`__new__…`) that the user is looking at has a VM but no
        // row yet — the VM is the only surface that can hold the value.
        assertEquals(
            SessionConfigWriteThrough.Route.ResidentViewModel,
            SessionConfigWriteThrough.route(hasLiveViewModel = true, hasPersistedRow = false),
        )
    }

    @Test
    fun `without a live view model the row is all there is`() {
        assertEquals(
            SessionConfigWriteThrough.Route.RowOnly,
            SessionConfigWriteThrough.route(hasLiveViewModel = false, hasPersistedRow = true),
        )
    }

    @Test
    fun `neither surface means the write evaporates`() {
        assertEquals(
            SessionConfigWriteThrough.Route.DraftWithoutRow,
            SessionConfigWriteThrough.route(hasLiveViewModel = false, hasPersistedRow = false),
        )
    }

    // ── the delivery contract ───────────────────────────────────────────────

    @Test
    fun `the row is written before the live chat is refreshed`() {
        val order = mutableListOf<String>()
        SessionConfigWriteThrough.deliver(
            vm = "live",
            persist = { order.add("persist") },
            hasPersistedRow = { order.add("row-read"); true },
            refresh = { order.add("refresh") },
        )
        assertEquals(listOf("persist", "row-read", "refresh"), order)
    }

    @Test
    fun `without a live view model the chat is not refreshed`() {
        val order = mutableListOf<String>()
        SessionConfigWriteThrough.deliver(
            vm = null as String?,
            persist = { order.add("persist") },
            hasPersistedRow = { true },
            refresh = { order.add("refresh") },
        )
        assertEquals(listOf("persist"), order)
    }

    @Test
    fun `a draft without a row is reported instead of silently accepted`() {
        var reported = false
        val order = mutableListOf<String>()
        val route = SessionConfigWriteThrough.deliver(
            vm = null as String?,
            persist = { order.add("persist") },
            hasPersistedRow = { false },
            refresh = { order.add("refresh") },
            onDraftWithoutRow = { reported = true },
        )
        assertEquals(SessionConfigWriteThrough.Route.DraftWithoutRow, route)
        assertTrue("the evaporated write must be reported", reported)
        assertEquals(listOf("persist"), order)
    }

    @Test
    fun `a draft WITH a live view model is delivered to the chat`() {
        var reported = false
        val order = mutableListOf<String>()
        val route = SessionConfigWriteThrough.deliver(
            vm = "live",
            persist = { order.add("persist") },
            hasPersistedRow = { false },
            refresh = { order.add("refresh") },
            onDraftWithoutRow = { reported = true },
        )
        assertEquals(SessionConfigWriteThrough.Route.ResidentViewModel, route)
        assertTrue("a live VM holds the value — nothing is lost", !reported)
        assertEquals(listOf("persist", "refresh"), order)
    }
}
