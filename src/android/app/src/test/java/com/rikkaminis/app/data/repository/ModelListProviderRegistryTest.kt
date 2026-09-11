package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [T-provider-key-roulette] Behavioural pin for the model-list choke point.
 *
 * The regression this covers: `refreshModels` used to hand the RAW stored key
 * straight to the fetcher. With several keys configured that sent
 * `Authorization: Bearer k1, k2, k3`, every fetch 401'd, and a custom-base
 * (relay) provider silently kept its old — possibly empty — model list. These
 * tests drive the real registry against a recording fetcher so both halves are
 * pinned: no raw string ever leaves, and one live key among dead ones still
 * refreshes the catalog.
 */
class ModelListProviderRegistryTest {

    private fun instance(id: String) = ProviderInstance(
        id = id,
        label = id,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
    )

    private fun ok(key: String) = listOf(LLMModel(id = key, displayName = key, provider = "openai"))

    /** Records every credential string the fetcher was handed. */
    private class RecordingFetcher(private val alive: Set<String>) : ModelListProvider {
        val seen = mutableListOf<String?>()
        override suspend fun fetchModels(
            apiKey: String?,
            instance: ProviderInstance,
            thirdParty: Boolean,
            forceRefresh: Boolean,
        ): List<LLMModel> {
            seen += apiKey
            return if (apiKey != null && apiKey in alive) {
                listOf(LLMModel(id = apiKey, displayName = apiKey, provider = "openai"))
            } else {
                emptyList()
            }
        }
    }

    private class ThrowingFetcher(private val throwFor: Set<String>) : ModelListProvider {
        val seen = mutableListOf<String?>()
        override suspend fun fetchModels(
            apiKey: String?,
            instance: ProviderInstance,
            thirdParty: Boolean,
            forceRefresh: Boolean,
        ): List<LLMModel> {
            seen += apiKey
            val k = apiKey ?: return emptyList()
            if (k in throwFor) throw IOException("boom-$k")
            return listOf(LLMModel(id = k, displayName = k, provider = "openai"))
        }
    }

    private class CancellingFetcher : ModelListProvider {
        var calls = 0
        override suspend fun fetchModels(
            apiKey: String?,
            instance: ProviderInstance,
            thirdParty: Boolean,
            forceRefresh: Boolean,
        ): List<LLMModel> {
            calls++
            throw CancellationException("cancelled")
        }
    }

    @After
    fun tearDown() = ModelListProviderRegistry.clear()

    @Test
    fun `multi key string never reaches the fetcher raw`() = runBlocking {
        val f = RecordingFetcher(alive = setOf("k1", "k2", "k3"))
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        val models = ModelListProviderRegistry.fetchModels(instance("p-raw"), "k1, k2, k3", false, true)
        assertEquals("k1", f.seen.joinToString("|"))
        assertEquals(1, models.size)
    }

    @Test
    fun `dead key falls through to a live one`() = runBlocking {
        val f = RecordingFetcher(alive = setOf("live"))
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        val models = ModelListProviderRegistry.fetchModels(instance("p-fall"), "dead, live", false, true)
        assertEquals("dead|live", f.seen.joinToString("|"))
        assertEquals(1, models.size)
    }

    @Test
    fun `all keys dead probes each exactly once then reports empty`() = runBlocking {
        val f = RecordingFetcher(alive = emptySet())
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        val models = ModelListProviderRegistry.fetchModels(instance("p-dead"), "a1, a2, a3", false, true)
        assertEquals("a1|a2|a3", f.seen.joinToString("|"))
        assertTrue(models.isEmpty())
    }

    @Test
    fun `single key is fetched once and comes back cleaned`() = runBlocking {
        val f = RecordingFetcher(alive = setOf("sk-one"))
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        ModelListProviderRegistry.fetchModels(instance("p-one"), "  sk-one  ", false, true)
        assertEquals(listOf<String?>("sk-one"), f.seen)
    }

    @Test
    fun `null credential is delegated once`() = runBlocking {
        val f = RecordingFetcher(alive = emptySet())
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        ModelListProviderRegistry.fetchModels(instance("p-null"), null, false, true)
        assertEquals(listOf<String?>(null), f.seen)
    }

    @Test
    fun `unregistered type touches nothing`() = runBlocking {
        assertTrue(
            ModelListProviderRegistry.fetchModels(instance("p-none"), "k1, k2", false, true).isEmpty(),
        )
    }

    @Test
    fun `transport failure on one key still probes the next`() = runBlocking {
        val f = ThrowingFetcher(throwFor = setOf("bad"))
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        val models = ModelListProviderRegistry.fetchModels(instance("p-throw"), "bad, good", false, true)
        assertEquals("bad|good", f.seen.joinToString("|"))
        assertEquals(1, models.size)
    }

    @Test
    fun `all keys throwing rethrows the last transport error`() = runBlocking {
        val f = ThrowingFetcher(throwFor = setOf("b1", "b2"))
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        val err = runCatching {
            ModelListProviderRegistry.fetchModels(instance("p-throw2"), "b1, b2", false, true)
        }.exceptionOrNull()
        assertTrue("expected IOException, got $err", err is IOException)
        assertEquals("boom-b2", err?.message)
        assertEquals("b1|b2", f.seen.joinToString("|"))
    }

    @Test
    fun `cancellation propagates instead of being probed away`() = runBlocking {
        val f = CancellingFetcher()
        ModelListProviderRegistry.register(ProviderType.openAI, f)
        val err = runCatching {
            ModelListProviderRegistry.fetchModels(instance("p-cancel"), "c1, c2", false, true)
        }.exceptionOrNull()
        assertTrue("expected CancellationException, got $err", err is CancellationException)
        assertEquals(1, f.calls)
    }
}
