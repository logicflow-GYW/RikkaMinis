package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [review1112-p1-1] The cache accept/reject contract of
 * [decodeCachedModels] — the step the schema-version bump was supposed to
 * police and silently did not.
 *
 * The defect: [ProviderModelsCache.Entry.schemaVersion] declared its default
 * as `CURRENT_SCHEMA_VERSION`. kotlinx.serialization fills a MISSING key with
 * the declared default, so a v1 row (written by the pre-fix parser, which had
 * no such field at all) decoded to `schemaVersion = 2` and passed the
 * `!= CURRENT_SCHEMA_VERSION` guard — the pre-fix rows then kept being served
 * for the remaining 7-day TTL, i.e. the endpoint's own context window stayed
 * invisible for exactly the window in which a user would conclude the fix did
 * not work.
 *
 * Every expectation below is a LITERAL (a raw JSON string / a literal model
 * list), never derived from the constants under test — deriving them would
 * make a mutated constant silently rewrite the expectation and pass
 * (evidence-discipline "装置自指陷阱").
 *
 * Tested through the production decode path on purpose: the whole bug lived in
 * "what does the real serializer do with a missing key", so a hand-rolled
 * replica of the decode step would prove nothing.
 */
class ProviderModelsCacheSchemaTest {

    private val now = 1_700_000_000_000L
    private val ttl = 7L * 24 * 3600 * 1000

    /** A v1 row: the shape the PRE-FIX build wrote — no `schemaVersion` key. */
    private val legacyRowV1 =
        """{"models":[{"id":"m1","displayName":"M1","provider":"Custom"}],"savedAt":$now}"""

    /** A current row: what [ProviderModelsCache.save] writes today. */
    private val currentRowV2 =
        """{"models":[{"id":"m1","displayName":"M1","provider":"Custom"}],"savedAt":$now,"schemaVersion":2}"""

    /** An explicitly-old row (some hypothetical v1 build that DID write the key). */
    private val explicitRowV1 =
        """{"models":[{"id":"m1","displayName":"M1","provider":"Custom"}],"savedAt":$now,"schemaVersion":1}"""

    // ------------------------------------------------------------------
    // 1. THE regression: a row with no version key must MISS
    // ------------------------------------------------------------------

    @Test
    fun legacyRowWithoutVersionKey_isAMiss() {
        assertNull(
            "a pre-fix cache row (no schemaVersion key) was ACCEPTED — the decode " +
                "default is filling in the current version and the upgrade silently " +
                "serves pre-fix rows for the rest of the TTL",
            decodeCachedModels(legacyRowV1, nowMs = now, ttlMs = ttl),
        )
    }

    @Test
    fun explicitOldVersion_isAMiss() {
        assertNull(
            "an explicitly v1 row was accepted",
            decodeCachedModels(explicitRowV1, nowMs = now, ttlMs = ttl),
        )
    }

    // ------------------------------------------------------------------
    // 2. The current row must still LOAD (the guard must not reject everything)
    // ------------------------------------------------------------------

    @Test
    fun currentRow_loads() {
        val models = decodeCachedModels(currentRowV2, nowMs = now, ttlMs = ttl)
        assertNotNull("a current v2 row was rejected — the guard is rejecting valid cache", models)
        assertEquals(listOf("m1"), models!!.map { it.id })
    }

    @Test
    fun currentRow_preservesTheEndpointMetadata() {
        // The whole point of the bump: the v2 row carries the endpoint's own
        // numbers, and loading it must not lose them.
        val row = """{"models":[{"id":"gpt-6-luna","displayName":"GPT-6 Luna","provider":"Custom",""" +
            """"contextWindow":272000,"maxOutputTokens":8192,"supportsReasoning":true}],""" +
            """"savedAt":$now,"schemaVersion":2}"""
        val models = decodeCachedModels(row, nowMs = now, ttlMs = ttl)
        assertNotNull(models)
        val m = models!!.single()
        assertEquals(272000, m.contextWindow)
        assertEquals(8192, m.maxOutputTokens)
        assertEquals(true, m.supportsReasoning)
    }

    // ------------------------------------------------------------------
    // 3. The other two miss reasons still work (no regression in the guard)
    // ------------------------------------------------------------------

    @Test
    fun staleCurrentRow_isAMiss() {
        assertNull(
            "a current row past the TTL was accepted",
            decodeCachedModels(currentRowV2, nowMs = now + ttl + 1, ttlMs = ttl),
        )
    }

    @Test
    fun currentRowAtTheTtlBoundary_stillLoads() {
        // The existing guard is `> ttlMs`, so exactly-at-TTL is still fresh.
        // Pinned so a future "tidy" cannot flip the boundary unnoticed.
        assertNotNull(decodeCachedModels(currentRowV2, nowMs = now + ttl, ttlMs = ttl))
    }

    @Test
    fun tornJson_isAMiss() {
        assertNull(decodeCachedModels("""{"models":[{"id":"m1"""", nowMs = now, ttlMs = ttl))
        assertNull(decodeCachedModels("", nowMs = now, ttlMs = ttl))
    }

    @Test
    fun saveThenLoad_roundTripsThroughTheCurrentVersion() {
        // A real save→load round trip through the production methods, using the
        // directory-scoped entry points (Android's Context is an abstract class
        // with dozens of abstract members — instantiating one in a unit test is
        // not possible without a mocking framework, and the first attempt at
        // this test failed to compile on CI for exactly that reason).
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "review1112-cache-" + System.nanoTime(),
        )
        dir.mkdirs()
        val cache = ProviderModelsCache("review1112", ttlMs = ttl)
        val models = listOf(
            com.rikkaminis.app.data.model.LLMModel(
                id = "gpt-6-luna",
                displayName = "GPT-6 Luna",
                provider = "Custom",
                contextWindow = 272000,
            ),
        )
        cache.saveToDir(dir, "credential-key", models)
        val loaded = cache.loadFromDir(dir, "credential-key")
        assertNotNull("save() wrote a row that load() immediately rejects", loaded)
        assertEquals(272000, loaded!!.single().contextWindow)
        // …and the on-disk text really does carry the version explicitly.
        val onDisk = dir.walkTopDown().first { it.isFile && it.name.endsWith(".json") }.readText()
        assert(onDisk.contains("\"schemaVersion\":2")) {
            "save() no longer writes the version explicitly: $onDisk"
        }
    }
}
