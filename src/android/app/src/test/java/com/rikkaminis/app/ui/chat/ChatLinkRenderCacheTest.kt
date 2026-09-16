package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [23c-2] Unit tests for the render-time link-resolution cache.
 * The cache is pure logic; resolution is injected as a lambda, so these run
 * on the JVM without touching the filesystem.
 */
class ChatLinkRenderCacheTest {

    private class CountingResolver(var action: ChatLinkAction) {
        var calls = 0
        fun resolve(url: String, sessionId: String?): ChatLinkAction {
            calls++
            return action
        }
    }

    @Test
    fun `resolves via the injected resolver`() {
        val r = CountingResolver(ChatLinkAction.MissingFile("minis://shared/x.txt", ChatLinkMissingReason.FILE_MISSING))
        val cache = ChatLinkRenderCache(r::resolve)
        assertSame(r.action, cache.resolve("minis://shared/x.txt", "s1"))
    }

    @Test
    fun `memoizes - resolver called once per key`() {
        val r = CountingResolver(ChatLinkAction.Web("https://a.b"))
        val cache = ChatLinkRenderCache(r::resolve)
        cache.resolve("u", "s1")
        cache.resolve("u", "s1")
        cache.resolve("u", "s1")
        assertEquals(1, r.calls)
    }

    @Test
    fun `different sessionId is a different key`() {
        val r = CountingResolver(ChatLinkAction.MissingFile("m", ChatLinkMissingReason.FILE_MISSING))
        val cache = ChatLinkRenderCache(r::resolve)
        cache.resolve("u", "s1")
        cache.resolve("u", "s2")
        assertEquals(2, r.calls)
    }

    @Test
    fun `clear forces re-resolution`() {
        val r = CountingResolver(ChatLinkAction.Web("w"))
        val cache = ChatLinkRenderCache(r::resolve)
        cache.resolve("u", "s1")
        cache.clear()
        cache.resolve("u", "s1")
        assertEquals(2, r.calls)
    }

    @Test
    fun `eviction respects maxEntries`() {
        val r = CountingResolver(ChatLinkAction.Web("w"))
        val cache = ChatLinkRenderCache(r::resolve, maxEntries = 2)
        cache.resolve("a", "s")
        cache.resolve("b", "s")
        cache.resolve("c", "s") // evicts "a" (FIFO into LRU map)
        assertEquals(3, r.calls)
        cache.resolve("a", "s") // evicted → re-resolve
        assertEquals(4, r.calls)
        cache.resolve("b", "s") // still cached? "b" was evicted when "a" re-entered
        assertEquals(5, r.calls)
    }

    @Test
    fun `LRU access refreshes entries`() {
        val r = CountingResolver(ChatLinkAction.Web("w"))
        val cache = ChatLinkRenderCache(r::resolve, maxEntries = 2)
        cache.resolve("a", "s")
        cache.resolve("b", "s")
        cache.resolve("a", "s") // touch "a" → "b" becomes eldest
        cache.resolve("c", "s") // evicts "b"
        assertEquals(3, r.calls)
        cache.resolve("a", "s") // still cached
        assertEquals(3, r.calls)
        cache.resolve("b", "s") // evicted
        assertEquals(4, r.calls)
    }
}
