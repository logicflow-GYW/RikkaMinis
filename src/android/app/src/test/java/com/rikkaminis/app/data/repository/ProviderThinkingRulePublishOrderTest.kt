package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/dv23-a1 §1043-A01-A02] JVM regression for two silent-loss defects in
 * [ProviderRepository]:
 *
 *   A-01 — `saveThinkingRule` / `deleteThinkingRule` / `reorderThinkingRules`
 *   assigned `persistedRules` BEFORE `providerDao.replaceThinkingRules(...)`.
 *   When the write threw (disk full / SQLite lock / IO error) the variable was
 *   still non-null, so `republishThinkingCache(instanceId, rules)` took the
 *   "persisted" fast path and published rules that never reached `provider.db`
 *   into `ThinkingRuleResolver` (and bumped `customRulesRevision`). The cache
 *   then disagreed with disk until the next restart, when the user's edit
 *   silently vanished.
 *
 *   A-02 — the six agent-loop pin mutators (`addAgentLoopEntry`,
 *   `removeAgentLoopEntry`, `addAgentLoopGroup`, `removeAgentLoopGroup`,
 *   `reorderAgentLoopEntries`, `reorderAgentLoopGroups`) read
 *   `_config.value.agentLoop*` without calling `ensureConfigLoaded()` first
 *   (the two reorders also read outside `synchronized(configLock)`). During the
 *   cold-start async load window `_config` holds the empty placeholder
 *   `ProviderConfig()`, so the read-modify-write persisted `listOf(newId)` over
 *   the user's real pins.
 *
 * [ProviderRepository] needs an Android Context and is not JVM-constructible,
 * so — following [ProviderPersistDegradedMirrorTest] — the two write sequences
 * are replicated here with recording fakes (the replica is copy-pasted from
 * the production shape, and the same ordering mutation flips the assertions).
 * Because a replica alone can drift away from production, this test ALSO pins
 * the real source: [productionSource] is read from the module tree and the
 * ordering / guard / lock contracts are asserted on it, so a regression in
 * `ProviderRepository.kt` fails here even if nobody updates the replica.
 *
 * Expected values are hardcoded literals, never derived from production
 * constants (evidence-discipline: a mutation must not move the expectation).
 */
class ProviderThinkingRulePublishOrderTest {

    // ------------------------------------------------------------------
    // Recording seams.
    // ------------------------------------------------------------------
    private object Cache {
        var published: List<String>? = null
        var publishes = 0
        fun set(ids: List<String>) {
            published = ids.toList()
            publishes += 1
        }
        fun reset() {
            published = null
            publishes = 0
        }
    }

    private class FakeDao(
        rows: List<String> = listOf("a", "b"),
        private val failWrite: Boolean = false,
    ) {
        val db = rows.toMutableList()
        var loads = 0
        var writes = 0
        fun load(): List<String> {
            loads += 1
            return db.toList()
        }
        fun replace(rows: List<String>) {
            writes += 1
            if (failWrite) throw IllegalStateException("SQLiteFullException (simulated)")
            db.clear()
            db.addAll(rows)
        }
    }

    /**
     * Order-faithful replica of the thinking-rule mutator shape.
     *
     * [assignAfterWrite] = true is the fixed shape (assert after the DAO write);
     * false reproduces the baseline defect.
     */
    private class RuleMutator(
        private val dao: FakeDao,
        private val assignAfterWrite: Boolean,
    ) {
        fun save(newId: String) {
            var persistedRules: List<String>? = null
            runCatching {
                val existing = dao.load().toMutableList()
                existing.add(0, newId)
                if (assignAfterWrite) {
                    dao.replace(existing)
                    persistedRules = existing.toList()
                } else {
                    persistedRules = existing.toList()
                    dao.replace(existing)
                }
            }.onFailure { /* production logs via android.util.Log.e */ }
            republish(persistedRules)
        }

        private fun republish(rules: List<String>?) {
            if (rules != null) {
                Cache.set(rules)
                return
            }
            // Null ⇒ the production no-arg overload re-reads the DB.
            Cache.set(dao.load())
        }
    }

    /**
     * Order-faithful replica of the agent-loop thin mutator.
     *
     * The sink ([setIds]) carries its own `ensureConfigLoaded()` — exactly what
     * makes the defect subtle: by the time the sink loads the real config the
     * caller has already computed `cur` from the empty placeholder, so the
     * sink's write overwrites the pins with the placeholder-derived list.
     */
    private class AgentLoopRepo(private val guardInCaller: Boolean) {
        var ids: List<String> = emptyList() // placeholder until loaded
        var loaded = false
        var loads = 0

        fun ensureConfigLoaded() {
            if (loaded) return
            loads += 1
            ids = listOf("pin1", "pin2") // the user's real pins, from disk
            loaded = true
        }

        fun add(entryId: String) {
            if (guardInCaller) ensureConfigLoaded()
            val cur = ids.toList()
            if (entryId in cur) return
            writeIds(cur + entryId)
        }

        private fun writeIds(newIds: List<String>) {
            ensureConfigLoaded() // the sink's own guard
            ids = newIds.distinct()
        }
    }

    // ------------------------------------------------------------------
    // A-01 — behavioural: a failed write must publish DISK TRUTH.
    // ------------------------------------------------------------------
    @Test
    fun publishAfterPersist_onWriteFailure_publishesDiskTruth() {
        Cache.reset()
        val dao = FakeDao(rows = listOf("a", "b"), failWrite = true)
        RuleMutator(dao, assignAfterWrite = true).save("c")

        // The write threw, so disk still holds the pre-edit rows.
        assertEquals(listOf("a", "b"), dao.db)
        // …and the resolver cache must hold exactly that, not the pending row.
        assertEquals(listOf("a", "b"), Cache.published)
        assertEquals(1, Cache.publishes)
    }

    @Test
    fun publishAfterPersist_onWriteSuccess_publishesWithoutRequery() {
        Cache.reset()
        val dao = FakeDao(rows = listOf("a", "b"))
        RuleMutator(dao, assignAfterWrite = true).save("c")

        assertEquals(listOf("c", "a", "b"), dao.db)
        assertEquals(listOf("c", "a", "b"), Cache.published)
        // Single-query contract preserved: one load, no post-write re-read.
        assertEquals(1, dao.loads)
    }

    /** The baseline ordering, kept as executable evidence that it diverges. */
    @Test
    fun baselineAssignBeforePersist_onWriteFailure_publishesUnpersistedRows() {
        Cache.reset()
        val dao = FakeDao(rows = listOf("a", "b"), failWrite = true)
        RuleMutator(dao, assignAfterWrite = false).save("c")

        assertEquals(listOf("a", "b"), dao.db) // nothing was written
        assertEquals(listOf("c", "a", "b"), Cache.published) // yet c is live
        assertNotEquals(dao.db, Cache.published) // the defect, stated plainly
    }

    // ------------------------------------------------------------------
    // A-02 — behavioural: add during the load window must not wipe pins.
    // ------------------------------------------------------------------
    @Test
    fun agentLoopAdd_withLoadGuard_preservesExistingPins() {
        val repo = AgentLoopRepo(guardInCaller = true)
        repo.add("newPin")
        assertEquals(listOf("pin1", "pin2", "newPin"), repo.ids)
        assertEquals(1, repo.loads)
    }

    @Test
    fun agentLoopAdd_withoutLoadGuard_wipesExistingPins() {
        val repo = AgentLoopRepo(guardInCaller = false)
        repo.add("newPin")
        // The placeholder was read first, so the sink's load cannot save it.
        assertEquals(listOf("newPin"), repo.ids)
        assertEquals(1, repo.loads)
    }

    // ------------------------------------------------------------------
    // Source pins — the real file, so the replica cannot drift silently.
    // ------------------------------------------------------------------
    private fun productionSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt",
            )
            if (candidate.isFile) return candidate.readText()
            val nested = File(
                dir,
                "android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt",
            )
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError(
            "ProviderRepository.kt not found from " + System.getProperty("user.dir"),
        )
    }

    private fun bodyOf(source: String, header: String): String {
        val at = source.indexOf(header)
        assertTrue("declaration not found: $header", at >= 0)
        val brace = source.indexOf('{', at)
        assertTrue("no body for $header", brace > 0)
        var depth = 0
        var i = brace
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(brace, i + 1)
                }
            }
            i += 1
        }
        throw AssertionError("unbalanced body for $header")
    }

    /**
     * Drop `//` line comments and `/* */` block comments before indexing.
     * The production hunks explain their own contract in prose
     * ("…must call ensureConfigLoaded() BEFORE reading _config.value"), so an
     * unstripped search would be satisfied by the comment and would survive a
     * mutation that deletes the real call. Same discipline as the scan gates'
     * code_only().
     */
    private fun stripComments(text: String): String {
        val noBlocks = Regex("/\\*[\\s\\S]*?\\*/").replace(text, "")
        return noBlocks.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    @Test
    fun production_publishesOnlyAfterTheDaoWrite() {
        val src = productionSource()
        val headers = listOf(
            "fun saveThinkingRule(",
            "fun deleteThinkingRule(",
            "fun reorderThinkingRules(",
        )
        for (header in headers) {
            assertTrue("$header must use the shared writer", src.substringAfter(header).substringBefore("\n\n").contains("mutateThinkingRules("))
        }
        val body = stripComments(bodyOf(src, "fun mutateThinkingRules("))
        val dao = body.indexOf("providerDao.replaceThinkingRules(")
        val assign = body.indexOf("persistedRules =")
        val publish = body.indexOf("republishThinkingCache(instanceId, persistedRules)")
        assertTrue("the shared writer must persist, then publish those rows", dao > 0 && dao < assign && assign < publish)
    }

    @Test
    fun production_agentLoopMutatorsGuardBeforeReadingConfig() {
        val src = productionSource()
        val headers = listOf(
            "fun addAgentLoopEntry(",
            "fun removeAgentLoopEntry(",
            "fun addAgentLoopGroup(",
            "fun removeAgentLoopGroup(",
            "fun reorderAgentLoopEntries(",
            "fun reorderAgentLoopGroups(",
        )
        for (header in headers) {
            val declAt = src.indexOf(header)
            assertTrue("declaration not found: $header", declAt >= 0)
            // The declaration's own expression body carries the lock.
            val line = src.substring(declAt, src.indexOf('\n', declAt))
            assertTrue(
                "$header does not take configLock — the read races the async loader",
                line.contains("synchronized(configLock)"),
            )
            val body = stripComments(bodyOf(src, header))
            val guard = body.indexOf("ensureConfigLoaded()")
            val read = body.indexOf("_config.value.agentLoop")
            assertTrue("$header no longer reads the pin list", read > 0)
            assertTrue("$header is missing ensureConfigLoaded()", guard > 0)
            assertTrue(
                "$header calls ensureConfigLoaded() AFTER reading the placeholder config",
                guard < read,
            )
        }
    }
}
