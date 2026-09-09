package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [composer-draft-v1] JVM tests for the pure KV core of ComposerDraftStore.
 *
 * These exercise the REAL production functions (not a mirror): the Android
 * bindings delegate to exactly these, swapping SharedPreferences for the
 * HashMap-backed [FakeKV] below.
 */
class ComposerDraftStoreTest {

    private class FakeKV : ComposerDraftStore.KV {
        val map = LinkedHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private var counter = 0
    private fun nextGen(): String = "__new__draft-${counter++}"

    @Test
    fun `nextDraftId persists a fresh id when no draft exists`() {
        val kv = FakeKV()
        val id = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        assertEquals("__new__draft-0", id)
        assertEquals("__new__draft-0", kv.map[ComposerDraftStore.KEY_ID])
    }

    @Test
    fun `nextDraftId returns the SAME id while a draft exists`() {
        val kv = FakeKV()
        val first = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        ComposerDraftStore.saveText(kv, first, "hello")
        // A second "New Chat" must resume the draft, not mint a fresh id.
        val second = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        assertEquals(first, second)
        assertEquals("__new__draft-0", second)
    }

    @Test
    fun `saveText stores text only for the active draft`() {
        val kv = FakeKV()
        val active = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        ComposerDraftStore.saveText(kv, active, "draft text")
        assertEquals("draft text", ComposerDraftStore.restoreText(kv, active))

        // A stale id (e.g. a draft already sent/discarded) must not write.
        ComposerDraftStore.saveText(kv, "__new__stale", "ghost")
        assertEquals("", ComposerDraftStore.restoreText(kv, "__new__stale"))
        assertEquals("draft text", ComposerDraftStore.restoreText(kv, active))
    }

    @Test
    fun `restoreText returns empty for unknown or cleared ids`() {
        val kv = FakeKV()
        assertEquals("", ComposerDraftStore.restoreText(kv, "__new__never-existed"))
    }

    @Test
    fun `clearDraft only clears when the id matches`() {
        val kv = FakeKV()
        val active = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        ComposerDraftStore.saveText(kv, active, "text")

        // Clearing a different id must be a no-op (protects against racing
        // the slot being re-taken by a newer draft).
        ComposerDraftStore.clearDraft(kv, "__new__other")
        assertEquals("text", ComposerDraftStore.restoreText(kv, active))

        ComposerDraftStore.clearDraft(kv, active)
        assertNull(kv.map[ComposerDraftStore.KEY_ID])
        assertNull(kv.map[ComposerDraftStore.KEY_TEXT])
        assertEquals("", ComposerDraftStore.restoreText(kv, active))
    }

    @Test
    fun `full lifecycle - type, leave, resume, send, fresh slot`() {
        val kv = FakeKV()
        // New Chat #1
        val draftA = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        // User types...
        ComposerDraftStore.saveText(kv, draftA, "half-typed message")
        // ...switches to another session, comes back via New Chat
        val resumed = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        assertEquals(draftA, resumed)
        assertEquals("half-typed message", ComposerDraftStore.restoreText(kv, resumed))
        // Sends: composer blanked -> draft slot freed
        ComposerDraftStore.clearDraft(kv, resumed)
        // New Chat #2 is genuinely fresh
        val draftB = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        assertNotEquals(draftA, draftB)
        assertEquals("", ComposerDraftStore.restoreText(kv, draftB))
    }

    @Test
    fun `saveText re-claims a free slot after the composer was blanked`() {
        val kv = FakeKV()
        val draft = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        ComposerDraftStore.saveText(kv, draft, "hello")

        // User selects-all + deletes: the composer goes blank and the slot is
        // released so "New Chat" can hand out a genuinely new draft.
        ComposerDraftStore.clearDraft(kv, draft)
        assertNull(kv.map[ComposerDraftStore.KEY_ID])

        // ...but the user is still ON that draft route and types again. If
        // saveText refused to write to a free slot, this second round of text
        // would be silently lost on the next session switch.
        ComposerDraftStore.saveText(kv, draft, "world")
        assertEquals("world", ComposerDraftStore.restoreText(kv, draft))
        assertEquals(draft, ComposerDraftStore.nextDraftId(kv) { nextGen() })
    }

    @Test
    fun `an occupied slot is never hijacked by another draft route`() {
        val kv = FakeKV()
        val live = ComposerDraftStore.nextDraftId(kv) { nextGen() }
        ComposerDraftStore.saveText(kv, live, "live text")

        // A different draft route (an already-sent chat still mounted under
        // its __new__ alias, or a group draft) must not take the slot over —
        // otherwise "New Chat" would resolve straight back into that chat.
        ComposerDraftStore.saveText(kv, "__new__other__grp__g1", "hijack")
        assertEquals(live, kv.map[ComposerDraftStore.KEY_ID])
        assertEquals("live text", ComposerDraftStore.restoreText(kv, live))
        assertEquals("", ComposerDraftStore.restoreText(kv, "__new__other__grp__g1"))
    }
}
