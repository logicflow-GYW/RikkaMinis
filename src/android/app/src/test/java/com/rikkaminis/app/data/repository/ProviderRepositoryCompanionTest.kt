package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * JVM unit tests for [ProviderRepository] companion pure functions:
 * [ProviderRepository.normalizedShadowKey] and [ProviderRepository.permuteById].
 *
 * These are pure Kotlin functions with no Android dependency, constructible
 * without a [android.content.Context].
 */
class ProviderRepositoryCompanionTest {

    // ── normalizedShadowKey ────────────────────────────────────────────────

    @Test fun `normalizedShadowKey null input returns empty string`() {
        assertEquals("", ProviderRepository.normalizedShadowKey(null))
    }

    @Test fun `normalizedShadowKey empty input returns empty string`() {
        assertEquals("", ProviderRepository.normalizedShadowKey(""))
    }

    @Test fun `normalizedShadowKey blank input returns empty string`() {
        assertEquals("", ProviderRepository.normalizedShadowKey("   "))
    }

    @Test fun `normalizedShadowKey lowercases the input`() {
        assertEquals("https://api.example.com", ProviderRepository.normalizedShadowKey("https://API.EXAMPLE.COM"))
    }

    @Test fun `normalizedShadowKey strips trailing slash`() {
        assertEquals("https://api.example.com", ProviderRepository.normalizedShadowKey("https://api.example.com/"))
    }

    @Test fun `normalizedShadowKey strips trailing slash after v1`() {
        assertEquals("https://api.example.com", ProviderRepository.normalizedShadowKey("https://api.example.com/v1/"))
    }

    @Test fun `normalizedShadowKey strips v1 suffix`() {
        assertEquals("https://api.example.com", ProviderRepository.normalizedShadowKey("https://api.example.com/v1"))
    }

    @Test fun `normalizedShadowKey strips only trailing v1 not internal`() {
        assertEquals("https://api.example.com/v1/models", ProviderRepository.normalizedShadowKey("https://api.example.com/v1/models"))
    }

    @Test fun `normalizedShadowKey handles multiple trailing slashes`() {
        assertEquals("https://api.example.com", ProviderRepository.normalizedShadowKey("https://api.example.com///"))
    }

    @Test fun `normalizedShadowKey trims whitespace`() {
        assertEquals("https://api.example.com", ProviderRepository.normalizedShadowKey("  https://api.example.com  "))
    }

    @Test fun `normalizedShadowKey full pipeline - mixed case slash v1`() {
        assertEquals("https://api.openai.com", ProviderRepository.normalizedShadowKey("https://API.OpenAI.COM/v1/"))
    }

    @Test fun `normalizedShadowKey preserves path without v1`() {
        assertEquals("https://api.example.com/path", ProviderRepository.normalizedShadowKey("https://api.example.com/path"))
    }

    @Test fun `normalizedShadowKey handles v1 as part of longer path`() {
        assertEquals("https://api.example.com/v1something", ProviderRepository.normalizedShadowKey("https://api.example.com/v1something"))
    }

    // ── permuteById ────────────────────────────────────────────────────────

    private data class Named(val id: String, val name: String)

    @Test fun `permuteById valid reorder returns permuted list`() {
        val items = listOf(Named("a", "Alpha"), Named("b", "Beta"), Named("c", "Gamma"))
        val result = ProviderRepository.permuteById(items, listOf("c", "a", "b")) { it.id }
        assertNotNull(result)
        assertEquals(listOf("Gamma", "Alpha", "Beta"), result!!.map { it.name })
    }

    @Test fun `permuteById identity order returns same list`() {
        val items = listOf(Named("a", "Alpha"), Named("b", "Beta"), Named("c", "Gamma"))
        val result = ProviderRepository.permuteById(items, listOf("a", "b", "c")) { it.id }
        assertNotNull(result)
        assertEquals(items.map { it.name }, result!!.map { it.name })
    }

    @Test fun `permuteById length mismatch returns null`() {
        val items = listOf(Named("a", "Alpha"), Named("b", "Beta"))
        assertNull(ProviderRepository.permuteById(items, listOf("a", "b", "c")) { it.id })
    }

    @Test fun `permuteById empty list returns empty`() {
        val items = emptyList<Named>()
        val result = ProviderRepository.permuteById(items, emptyList()) { it.id }
        assertNotNull(result)
        assertEquals(0, result!!.size)
    }

    @Test fun `permuteById duplicate ids in current returns null`() {
        val items = listOf(Named("a", "Alpha"), Named("a", "Alpha2"), Named("b", "Beta"))
        assertNull(ProviderRepository.permuteById(items, listOf("a", "a", "b")) { it.id })
    }

    @Test fun `permuteById duplicate ids in newOrder returns null`() {
        val items = listOf(Named("a", "Alpha"), Named("b", "Beta"), Named("c", "Gamma"))
        assertNull(ProviderRepository.permuteById(items, listOf("a", "b", "b")) { it.id })
    }

    @Test fun `permuteById set mismatch returns null`() {
        val items = listOf(Named("a", "Alpha"), Named("b", "Beta"), Named("c", "Gamma"))
        assertNull(ProviderRepository.permuteById(items, listOf("a", "b", "d")) { it.id })
    }

    @Test fun `permuteById single element returns same list`() {
        val items = listOf(Named("a", "Alpha"))
        val result = ProviderRepository.permuteById(items, listOf("a")) { it.id }
        assertNotNull(result)
        val r = result!!
        assertEquals(1, r.size)
        assertEquals("Alpha", r[0].name)
    }

    @Test fun `permuteById reverse order`() {
        val items = listOf(Named("a", "Alpha"), Named("b", "Beta"), Named("c", "Gamma"))
        val result = ProviderRepository.permuteById(items, listOf("c", "b", "a")) { it.id }
        assertNotNull(result)
        assertEquals(listOf("Gamma", "Beta", "Alpha"), result!!.map { it.name })
    }

    @Test fun `permuteById carries elements across unchanged`() {
        val items = listOf(Named("a", "Alpha"), Named("b", "Beta"))
        val result = ProviderRepository.permuteById(items, listOf("b", "a")) { it.id }
        assertNotNull(result)
        // Same object references
        val r = result!!
        assert(r[0] === items[1])
        assert(r[1] === items[0])
    }
}