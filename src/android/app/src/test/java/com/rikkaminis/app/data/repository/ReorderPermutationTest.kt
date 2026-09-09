package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [reorder-unify] Contract tests for [ProviderRepository.permuteById], the
 * shared guard behind every drag-reorder mutator (`reorderGroups`,
 * `reorderAgentLoopEntries`, `reorderAgentLoopGroups`).
 *
 * Why this is a pure companion function rather than logic inlined in each
 * mutator: `ProviderRepository` needs an Android `Context`, so it can't be
 * constructed in a JVM unit test. Extracting the permutation check makes the
 * part that can silently corrupt user data — dropping or duplicating a group
 * because the UI dragged against a stale snapshot — directly testable, and
 * gives all three mutators one implementation instead of three drifting
 * copies.
 */
class ReorderPermutationTest {

    private data class Row(val id: String, val payload: String)

    private fun rows(vararg ids: String): List<Row> =
        ids.map { Row(it, payload = "payload-$it") }

    private fun permute(current: List<Row>, newOrder: List<String>): List<Row>? =
        ProviderRepository.permuteById(current, newOrder) { it.id }

    // --- happy path ---------------------------------------------------------

    @Test
    fun `applies a valid permutation`() {
        val current = rows("a", "b", "c")
        val result = permute(current, listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), result?.map { it.id })
    }

    @Test
    fun `an unchanged order is still a valid permutation`() {
        val current = rows("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), permute(current, listOf("a", "b", "c"))?.map { it.id })
    }

    @Test
    fun `carries elements across untouched rather than rebuilding them`() {
        val current = rows("a", "b")
        val result = permute(current, listOf("b", "a"))!!
        // Identity, not just equality: reordering must never clone or
        // reconstruct a ModelGroup (its memberEntryIds are mutable state).
        assertEquals(true, result[0] === current[1])
        assertEquals(true, result[1] === current[0])
    }

    @Test
    fun `single element and empty lists are permutable`() {
        assertEquals(listOf("only"), permute(rows("only"), listOf("only"))?.map { it.id })
        assertEquals(emptyList<String>(), permute(emptyList(), emptyList())?.map { it.id })
    }

    // --- stale-snapshot rejections ------------------------------------------

    @Test
    fun `rejects an order missing an element`() {
        // A group was removed (cascade cleanup) after the UI read its snapshot.
        assertNull(permute(rows("a", "b", "c"), listOf("a", "b")))
    }

    @Test
    fun `rejects an order naming an unknown id`() {
        // Same length, different membership — the UI's snapshot predates a
        // remove+add pair.
        assertNull(permute(rows("a", "b", "c"), listOf("a", "b", "zz")))
    }

    @Test
    fun `rejects an order with an extra element`() {
        assertNull(permute(rows("a", "b"), listOf("a", "b", "c")))
    }

    // --- duplicate-id rejections -------------------------------------------

    @Test
    fun `rejects a newOrder that names the same id twice`() {
        // Would duplicate "a" and silently drop "b".
        assertNull(permute(rows("a", "b"), listOf("a", "a")))
    }

    /**
     * The case a plain set-comparison guard misses: `current` ids [a, b, b]
     * vs `newOrder` [b, a, b] agree on both length AND id-set, so a
     * `newOrder.toSet() != currentIds.toSet()` check passes it — yet applying
     * it collapses the two distinct `b` rows into one repeated element and
     * loses the other. An already-corrupted list must be refused, not
     * "reordered" into further corruption.
     */
    @Test
    fun `refuses to reorder a list already holding duplicate ids`() {
        val corrupted = listOf(
            Row("a", "payload-a"),
            Row("b", "first-b"),
            Row("b", "second-b"),
        )
        assertNull(permute(corrupted, listOf("b", "a", "b")))
        // ...and also for an otherwise innocent-looking target order.
        assertNull(permute(corrupted, listOf("a", "b", "b")))
    }

    // --- id-list flavour (agent-loop reorders) ------------------------------

    @Test
    fun `works for plain id lists with identity idOf`() {
        // reorderAgentLoopEntries / reorderAgentLoopGroups permute
        // List<String> directly, using the element as its own id.
        val current = listOf("e1", "e2", "e3")
        val result = ProviderRepository.permuteById(current, listOf("e3", "e2", "e1")) { it }
        assertEquals(listOf("e3", "e2", "e1"), result)
    }

    @Test
    fun `rejects a duplicated id list`() {
        val current = listOf("e1", "e2")
        assertNull(ProviderRepository.permuteById(current, listOf("e1", "e1")) { it })
    }
}
