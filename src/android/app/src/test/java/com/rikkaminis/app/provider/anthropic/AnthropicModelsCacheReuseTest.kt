package com.rikkaminis.app.provider.anthropic

import android.content.ContextWrapper
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.provider.ProviderModelsCache
import com.rikkaminis.app.provider.decodeCachedModels
import com.rikkaminis.app.provider.usesBuiltinCatalogOnFailure
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real API -> shared cache -> on-disk rows. All credentials below are fixed dummy fixtures. */
class AnthropicModelsCacheReuseTest {
    private val dir = Files.createTempDirectory("anthropic-cache-reuse-").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getCacheDir(): File = dir
    }
    private val cache = ProviderModelsCache("anthropic")
    private val server = MockWebServer()
    private lateinit var base: String
    private val model = LLMModel("cache-reuse-model", "Cached", "Anthropic")
    private val registryField = ModelsDevApi::class.java.getDeclaredField("cachedRegistry")
        .apply { isAccessible = true }
    private val timestampField = ModelsDevApi::class.java.getDeclaredField("cacheTimestamp")
        .apply { isAccessible = true }
    private var previousRegistry: Any? = null
    private var previousTimestamp = 0L

    @Before
    fun setUp() {
        server.start()
        base = server.url("/v1").toString()
        previousRegistry = registryField.get(ModelsDevApi)
        previousTimestamp = timestampField.getLong(ModelsDevApi)
        registryField.set(ModelsDevApi, emptyMap<String, ModelsDevApi.ProviderEntry>())
        timestampField.setLong(ModelsDevApi, System.currentTimeMillis())
        respondWith()
    }

    @After
    fun tearDown() {
        registryField.set(ModelsDevApi, previousRegistry)
        timestampField.setLong(ModelsDevApi, previousTimestamp)
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun key(baseURL: String? = base, credential: String = "dummy-key") =
        (baseURL ?: "") + "|" + credential

    private fun rowFile(cacheKey: String, namespace: String? = null): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val root = File(dir, "models-cache")
        return File(if (namespace == null) root else File(root, namespace), "$hash.json")
    }

    private fun legacy(cacheKey: String): File = rowFile(cacheKey).apply {
        parentFile!!.mkdirs()
        writeText("""{"models":[{"id":"legacy","displayName":"Legacy","provider":"Anthropic"}],"savedAt":${System.currentTimeMillis()}}""")
    }

    private fun respondWith(status: Int = 200, id: String = "network-model") {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                    .setBody(if (status == 200) """{"data":[{"id":"$id","display_name":"Network"}]}""" else "{}")
        }
    }

    private suspend fun fetch(forceRefresh: Boolean = false, credential: String = "dummy-key") =
        AnthropicModelsApi.fetchModels(credential, base, context, forceRefresh)

    @Test
    fun legacyFlatRowMissesAndSuccessfulFetchRetiresOnlySameKey() = runBlocking {
        val old = legacy(key())
        val sibling = legacy(key(credential = "dummy-sibling"))
        sibling.setLastModified(1L) // Even an expired sibling is not ours to delete.
        val otherProvider = rowFile(key(), "openai").apply {
            parentFile!!.mkdirs()
            writeText("other-provider-sentinel")
        }
        val config = File(dir, "provider-config-sentinel").apply { writeText("untouched") }
        assertNull(decodeCachedModels(old.readText(), System.currentTimeMillis(), 604_800_000L))
        assertNull(cache.load(context, key()))

        assertEquals("network-model", fetch().single().id)

        assertEquals(1, server.requestCount)
        assertFalse(old.exists())
        assertTrue(sibling.isFile)
        assertEquals("other-provider-sentinel", otherProvider.readText())
        assertEquals("untouched", config.readText())
        assertEquals("network-model", cache.load(context, key())!!.single().id)
        val saved = rowFile(key(), "anthropic")
        assertTrue(saved.name.matches(Regex("[0-9a-f]{64}\\.json")))
        assertTrue(saved.readText().contains("\"schemaVersion\":2"))
        assertFalse(saved.readText().contains("dummy-key"))
        val request = server.takeRequest()
        assertEquals("/v1/models?limit=512", request.path)
        assertEquals("Bearer dummy-key", request.getHeader("Authorization"))
    }

    @Test
    fun currentNamespaceHitReenrichesWithoutNetwork() = runBlocking {
        assertTrue(cache.save(context, key(), listOf(model)))
        registryField.set(ModelsDevApi, mapOf("anthropic" to ModelsDevApi.ProviderEntry(
            id = "anthropic", name = "Anthropic", api = null,
            models = mapOf(model.id to ModelsDevApi.ModelDevEntry(
                id = model.id, name = null, family = null, contextWindow = 123_456,
                maxOutputTokens = 8_192, reasoning = true, interleavedField = null,
                inputModalities = listOf("text", "image"), outputModalities = listOf("text"),
                reasoningEffortValues = null, declaresNoEffortTiers = false,
            )),
        )))
        val hit = fetch().single()
        assertEquals(model.id, hit.id)
        assertEquals(123_456, hit.contextWindow)
        assertEquals(8_192, hit.maxOutputTokens)
        assertEquals(true, hit.supportsReasoning)
        assertEquals(listOf("text", "image"), hit.inputModalities)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun officialAndCustomUseSameNamespaceButDifferentHashes() = runBlocking {
        val officialKey = key(baseURL = null)
        val customKey = key()
        assertNotEquals(officialKey, customKey)
        assertTrue(cache.save(context, officialKey, listOf(model.copy(id = "official-model"))))
        assertTrue(cache.save(context, customKey, listOf(model.copy(id = "custom-model"))))
        assertEquals("official-model", AnthropicModelsApi.fetchModels("dummy-key", context = context).single().id)
        assertEquals("custom-model", fetch().single().id)
        assertNotEquals(rowFile(officialKey, "anthropic"), rowFile(customKey, "anthropic"))
        assertEquals(2, File(dir, "models-cache/anthropic").listFiles()!!.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun separateCredentialsAtSameBaseNeverShareRows() = runBlocking {
        assertTrue(cache.save(context, key(), listOf(model)))
        respondWith(id = "second-key-model")
        assertEquals("second-key-model", fetch(credential = "dummy-second-key").single().id)
        assertEquals(model.id, fetch().single().id)
        assertEquals("second-key-model", cache.load(context, key(credential = "dummy-second-key"))!!.single().id)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun forceRefreshBypassesHitButStillWritesThrough() = runBlocking {
        assertTrue(cache.save(context, key(), listOf(model)))
        val old = legacy(key())
        assertEquals("network-model", fetch(forceRefresh = true).single().id)
        assertEquals(1, server.requestCount)
        assertFalse(old.exists())
        assertEquals("network-model", fetch().single().id)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun writeFailureKeepsLegacyFileAndNetworkResult() = runBlocking {
        val old = legacy(key())
        val before = old.readText()
        // A non-empty directory cannot be replaced by either rename or writeText.
        val blocked = rowFile(key(), "anthropic").apply { mkdirs() }
        File(blocked, "blocker").writeText("keep")
        assertFalse(cache.save(context, key(), listOf(model)))
        assertEquals("network-model", fetch(forceRefresh = true).single().id)
        assertEquals(before, old.readText())
        assertEquals("keep", File(blocked, "blocker").readText())
        assertNull(cache.load(context, key()))
    }

    private suspend fun assertAuthFailure(status: Int) {
        assertTrue(cache.save(context, key(), listOf(model)))
        val siblingKey = key(credential = "dummy-sibling")
        assertTrue(cache.save(context, siblingKey, listOf(model.copy(id = "sibling"))))
        val old = legacy(key())
        val siblingOld = legacy(siblingKey)
        respondWith(status = status)

        assertTrue(fetch(forceRefresh = true).isEmpty())

        assertTrue(server.requestCount > 0)
        assertFalse(rowFile(key(), "anthropic").exists())
        assertNull(cache.load(context, key()))
        assertFalse(old.exists())
        assertTrue(siblingOld.isFile)
        assertEquals("sibling", cache.load(context, siblingKey)!!.single().id)
        assertFalse(usesBuiltinCatalogOnFailure(false, status))
        assertFalse(usesBuiltinCatalogOnFailure(true, status))
    }

    @Test
    fun unauthorizedInvalidatesCurrentKeyWithoutFallback() = runBlocking { assertAuthFailure(401) }

    @Test
    fun forbiddenInvalidatesCurrentKeyWithoutFallback() = runBlocking { assertAuthFailure(403) }

    @Test
    fun failedRefreshDoesNotRetireFlatFile() = runBlocking {
        val old = legacy(key())
        respondWith(status = 500)
        assertTrue(fetch(forceRefresh = true).isEmpty())
        assertTrue(old.isFile)
        assertNull(cache.load(context, key()))
    }

    @Test
    fun expiredV2RowTriggersFetchAndReplacesTheRow() = runBlocking {
        val file = rowFile(key(), "anthropic").apply {
            parentFile!!.mkdirs()
            writeText("""{"models":[{"id":"expired","displayName":"Expired","provider":"Anthropic"}],"savedAt":1,"schemaVersion":2}""")
        }
        assertNull(cache.load(context, key()))
        assertEquals("network-model", fetch().single().id)
        assertNotNull(cache.load(context, key()))
        assertTrue(file.readText().contains("\"schemaVersion\":2"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingContextBypassesBothReadAndWrite() = runBlocking {
        assertTrue(cache.save(context, key(), listOf(model)))
        val old = legacy(key())
        val namespaced = rowFile(key(), "anthropic")
        val before = namespaced.readText()
        assertEquals("network-model", AnthropicModelsApi.fetchModels("dummy-key", base).single().id)
        assertEquals(before, namespaced.readText())
        assertTrue(old.isFile)
        assertEquals(1, server.requestCount)
    }
}
