package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class AnthropicUsageAccountingTest {

    @Test
    fun `input tokens are already fresh - no cache subtraction`() {
        // Live capture 2026-09-12 (Antigravity relay): input_tokens=50 with
        // cache_read=1924 — total input was 1974, so input_tokens is fresh-only.
        val (fresh, context) = anthropicUsageAccounting(50, 1924, null)
        assertEquals(50, fresh)
        assertEquals(1974, context)
    }

    @Test
    fun `context includes cache creation portion`() {
        val (fresh, context) = anthropicUsageAccounting(100, null, 50)
        assertEquals(100, fresh)
        assertEquals(150, context)
    }

    @Test
    fun `context includes both cache kinds`() {
        val (fresh, context) = anthropicUsageAccounting(100, 30, 50)
        assertEquals(100, fresh)
        assertEquals(180, context)
    }

    @Test
    fun `zero caches leave context equal to input`() {
        val (fresh, context) = anthropicUsageAccounting(10, 0, 0)
        assertEquals(10, fresh)
        assertEquals(10, context)
    }

    @Test
    fun `no cache fields - context equals fresh input`() {
        val (fresh, context) = anthropicUsageAccounting(10, null, null)
        assertEquals(10, fresh)
        assertEquals(10, context)
    }
}