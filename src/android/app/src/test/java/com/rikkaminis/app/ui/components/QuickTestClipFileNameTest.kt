package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-V F-12] The Quick Test audio clip's cache name.
 *
 * The old name was `"quicktest-audio-${data.hashCode()}.bin"`. Kotlin arrays do
 * not override `hashCode`, so that is `Object.hashCode()` — IDENTITY. The name
 * therefore changed for byte-identical clips on every composition (no reuse)
 * and was meaningless in a cache-dir listing. The name is now content-derived.
 *
 * The first arm is a **negative control**: it pins that the pre-fix expression
 * really is identity-based, so this test would catch a regression back to it
 * (and would itself go red if Kotlin ever changed array hashing, which is the
 * moment the fix stops being necessary).
 */
class QuickTestClipFileNameTest {

    @Test
    fun `negative control - ByteArray hashCode is identity, not content`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 5)
        // same content, different identity hash — this is what the old cache
        // name was built from
        assertNotEquals(a.hashCode(), b.hashCode())
        // ...while the content hash agrees, which is the property the fix uses
        assertEquals(a.contentHashCode(), b.contentHashCode())
    }

    @Test
    fun `identical bytes share one cache name`() {
        val a = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x11, 0x22)
        val b = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x11, 0x22)
        assertEquals(quickTestClipFileName(a), quickTestClipFileName(b))
        assertEquals("quicktest-audio-7-${a.contentHashCode().toUInt().toString(16)}.bin", quickTestClipFileName(a))
    }

    @Test
    fun `different bytes never collide in the name`() {
        // same length, one byte apart — a size-only name would collide here
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 5)
        assertNotEquals(quickTestClipFileName(a), quickTestClipFileName(b))
    }

    @Test
    fun `the name is a plain file name inside the cache dir`() {
        val name = quickTestClipFileName(ByteArray(1024) { it.toByte() })
        assertTrue("size must be readable in the name: $name", name.contains("-1024-"))
        assertTrue("no separators: $name", !name.contains("/") && !name.contains("\\"))
        assertTrue("stable suffix: $name", name.startsWith("quicktest-audio-") && name.endsWith(".bin"))
        // negative content hashes must not produce a '-' in the middle of a name
        assertTrue("no sign prefix: $name", !name.contains("--"))
    }
}
