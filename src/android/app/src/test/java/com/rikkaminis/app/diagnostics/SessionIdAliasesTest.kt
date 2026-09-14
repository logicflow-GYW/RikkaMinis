package com.rikkaminis.app.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionIdAliasesTest {
    private val lines = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        SessionIdAliases.clearForTest()
        SessionIdAliases.sinkForTest = { category, message -> lines.add(category to message) }
    }

    @After
    fun tearDown() {
        SessionIdAliases.clearForTest()
    }

    @Test
    fun `unknown id passes through untouched`() {
        assertEquals("__new__abc", SessionIdAliases.resolve("__new__abc"))
        assertEquals(0, lines.size)
    }

    @Test
    fun `alias resolves to canonical and reports exactly once`() {
        SessionIdAliases.register("__new__abc", "uuid-1")

        assertEquals("uuid-1", SessionIdAliases.resolve("__new__abc"))
        assertEquals("uuid-1", SessionIdAliases.resolve("__new__abc"))
        assertEquals("uuid-1", SessionIdAliases.resolve("uuid-1"))

        assertEquals(1, lines.size)
        assertEquals("ChatVMStore", lines[0].first)
        assertTrue(lines[0].second.startsWith("alias resolved __new__abc -> uuid-1"))
    }

    @Test
    fun `self registration is ignored`() {
        SessionIdAliases.register("same", "same")
        assertEquals("same", SessionIdAliases.resolve("same"))
        assertEquals(0, lines.size)
    }

    @Test
    fun `chained aliases follow through to final canonical`() {
        SessionIdAliases.register("__new__a", "b")
        SessionIdAliases.register("b", "uuid-c")
        assertEquals("uuid-c", SessionIdAliases.resolve("__new__a"))
    }

    @Test
    fun `unregisterByCanonical restores pass-through`() {
        SessionIdAliases.register("__new__abc", "uuid-1")
        SessionIdAliases.unregisterByCanonical("uuid-1")
        assertEquals("__new__abc", SessionIdAliases.resolve("__new__abc"))
    }

    @Test
    fun `releasing a session also drops its one-shot report bit`() {
        // [audit-0914] `reported` used to be append-only: every draft id that
        // resolved once stayed in memory for the process lifetime (~100 B each).
        // Tying it to the alias table keeps the set bounded by live sessions.
        SessionIdAliases.register("__new__abc", "uuid-1")
        SessionIdAliases.resolve("__new__abc")
        assertEquals(1, SessionIdAliases.reportedCountForTest())

        SessionIdAliases.unregisterByCanonical("uuid-1")
        assertEquals(0, SessionIdAliases.reportedCountForTest())

        // Re-registering later reports once more — that is what "one-shot"
        // means here.
        SessionIdAliases.register("__new__abc", "uuid-1")
        assertEquals("uuid-1", SessionIdAliases.resolve("__new__abc"))
        assertEquals(2, lines.size)
    }

    @Test
    fun `report set stays bounded when sessions are never released`() {
        // Backstop for a caller that registers aliases without a matching
        // unregister: the set must not grow without bound.
        repeat(600) { i ->
            SessionIdAliases.register("__new__d$i", "uuid-$i")
            SessionIdAliases.resolve("__new__d$i")
        }
        assertTrue(SessionIdAliases.reportedCountForTest() <= 512)
    }

    @Test
    fun `re-register updates the target`() {
        SessionIdAliases.register("__new__abc", "uuid-1")
        SessionIdAliases.register("__new__abc", "uuid-2")
        assertEquals("uuid-2", SessionIdAliases.resolve("__new__abc"))
    }
}
