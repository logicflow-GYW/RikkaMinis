package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for [normalizeDotSegments] (RC1 path-normalization fix). */
class NormalizeDotSegmentsTest {

    @Test
    fun `plain relative path is unchanged`() {
        assertEquals(listOf("a", "b", "c"), normalizeDotSegments("a/b/c"))
    }

    @Test
    fun `leading slash and empty segments are dropped`() {
        assertEquals(listOf("a", "b"), normalizeDotSegments("//a//b/"))
    }

    @Test
    fun `dot segments are dropped`() {
        assertEquals(listOf("a", "b"), normalizeDotSegments("a/./b/./."))
    }

    @Test
    fun `internal dotdot pops the stack`() {
        assertEquals(listOf("b"), normalizeDotSegments("a/../b"))
    }

    @Test
    fun `dotdot resolving back to base yields empty list`() {
        assertEquals(emptyList<String>(), normalizeDotSegments("a/.."))
    }

    @Test
    fun `dotdot climbing above base returns null`() {
        assertNull(normalizeDotSegments("../etc/passwd"))
    }

    @Test
    fun `dotdot climbing above base after internal pop returns null`() {
        // a pops, '..' pops, then '..' climbs above base -> rejected
        assertNull(normalizeDotSegments("a/../.."))
    }

    @Test
    fun `traversal that stays inside base is allowed`() {
        assertEquals(listOf("x"), normalizeDotSegments("a/b/../../x"))
    }

    @Test
    fun `multiple escaped segments return null`() {
        assertNull(normalizeDotSegments("../../../../../../etc/passwd"))
    }

    @Test
    fun `empty tail yields empty list`() {
        assertEquals(emptyList<String>(), normalizeDotSegments(""))
    }
}
