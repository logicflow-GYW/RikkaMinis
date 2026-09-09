package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [fix/chat-sentinel-crash-on-import] Regression test for the bottom-sentinel
 * scroll index resolver. `requestScrollToItem(totalItemsCount - 1)` throws
 * `IllegalArgumentException("Index should be non-negative (-1)")` when
 * `totalItemsCount` is 0 (cold open on an imported long session before the
 * LazyColumn has measured anything). The resolver must return null for empty
 * layouts instead of a negative index.
 */
class SafeBottomScrollIndexTest {

    @Test
    fun `empty layout resolves to null, not -1`() {
        assertNull(safeBottomScrollIndex(0))
    }

    @Test
    fun `single item resolves to index 0`() {
        assertEquals(0, safeBottomScrollIndex(1))
    }

    @Test
    fun `many items resolve to last index`() {
        assertEquals(9, safeBottomScrollIndex(10))
        assertEquals(1145, safeBottomScrollIndex(1146))
    }

    @Test
    fun `negative total is never trusted`() {
        // Defensive: a malformed layoutInfo must not leak a negative index.
        assertNull(safeBottomScrollIndex(-1))
    }
}
