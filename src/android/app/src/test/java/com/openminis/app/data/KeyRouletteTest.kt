package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyRouletteTest {

    @Test
    fun `single key passes through verbatim`() {
        val k = "sk-test-123"
        assertEquals(k, KeyRoulette.next(k, "p1"))
        assertEquals(k, KeyRoulette.next(k, "p1"))
    }

    @Test
    fun `multi key rotates round robin`() {
        val keys = "k1, k2, k3"
        val seen = mutableSetOf<String>()
        repeat(6) {
            val next = KeyRoulette.next(keys, "p-rot")
            assertTrue("picked key must come from the list", next in listOf("k1", "k2", "k3"))
            seen.add(next)
        }
        assertTrue("all keys must be picked across 6 draws", seen.size == 3)
    }

    @Test
    fun `multi key rotation is LRU balanced`() {
        val keys = "a b"
        val picks = List(10) { KeyRoulette.next(keys, "p-lru") }
        assertEquals(5, picks.count { it == "a" })
        assertEquals(5, picks.count { it == "b" })
    }

    @Test
    fun `whitespace and comma delimiters both split`() {
        val comma = "x1,x2"
        val space = "y1 y2"
        assertEquals("x1", KeyRoulette.next(comma, "p-c1"))
        assertEquals("x2", KeyRoulette.next(comma, "p-c1"))
        assertEquals("y1", KeyRoulette.next(space, "p-c2"))
        assertEquals("y2", KeyRoulette.next(space, "p-c2"))
    }

    @Test
    fun `rotation state persists across reinit`() {
        val dir = File.createTempFile("keyroulette", "test").let {
            it.delete()
            File(it.absolutePath + ".d")
        }.apply { mkdirs() }
        val keys = "p1, p2"
        val first = KeyRoulette.next(keys, "p-persist")
        KeyRoulette.init(dir)
        val second = KeyRoulette.next(keys, "p-persist")
        assertTrue(first != second)
        dir.deleteRecursively()
    }

    @Test
    fun `per provider isolation`() {
        val k1 = KeyRoulette.next("a1 a2", "prov-A")
        val k2 = KeyRoulette.next("b1 b2", "prov-B")
        assertTrue(k1 in listOf("a1", "a2"))
        assertTrue(k2 in listOf("b1", "b2"))
    }
}
