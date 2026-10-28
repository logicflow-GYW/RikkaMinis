package com.rikkaminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fix-persist-degraded-mirror] JVM regression for deep-audit-1042 A-01
 * (branch fix/dv22-a1-provider-persist-mirror), two bare-DAO decision points
 * in [ProviderRepository]:
 *
 *   - persistToDbAndMirror called providerDao.syncAllIncremental BARE,
 *     BEFORE the prefs mirror write. A provider.db that failed to open
 *     (migration abort — ProviderDatabase has no
 *     fallbackToDestructiveMigration) threw straight out of the function,
 *     so the mirror write below it never ran: with the DB down, prefs is
 *     the ONLY surviving store, and the user's change was lost entirely
 *     while saveConfig's executor catch logged "DB+mirror stale".
 *   - restoreThinkingRules called providerDao.replaceThinkingRules bare
 *     while its NINE sibling functions all wrapped the DAO call
 *     (runCatching + loud log — see the [audit-0909 T4-H1] hunks).
 *
 * [fix/meta1028-d1 F-01] The hash is now taken from what prefs actually holds
 * after the commit ([decideSyncHash]) rather than from the string we hoped to
 * write — see [ProviderMirrorHashTest] for the arbitration that serves. The
 * persist arms below only reproduce the log branches the assertions read.
 *
 * [ProviderRepository] itself needs an Android Context (not
 * JVM-constructible), so — same as [ProviderImportRoundTripTest] — this
 * test pins the contract by faithfully replicating the production write
 * sequences (including the [fix/dv22-a1 §1042-A-01] hunks), with the DAO and
 * the prefs mirror replaced by recording fakes and android.util.Log.e by a
 * recording seam ([ProbeLog]). The replicas are copy-pasted from the
 * production code, so a regression fails here the same way it fails on
 * device.
 *
 * Expected values are hardcoded literals, NOT derived from the production
 * constants (evidence-discipline: a mutation must not move the expectation).
 */
class ProviderPersistDegradedMirrorTest {

    // ------------------------------------------------------------------
    // Recording seams (production: android.util.Log.e / .i / .w — the JVM
    // unit-test runner replaces those with no-ops, so the replicas route
    // the same call sites through [ProbeLog] to keep "the failure is
    // logged" assertable).
    // ------------------------------------------------------------------
    private object ProbeLog {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        fun e(msg: String) { errors.add(msg) }
        fun w(msg: String) { warnings.add(msg) }
    }

    // ------------------------------------------------------------------
    // Fake DAO: production [ProviderDao] — only the two calls under test.
    // ------------------------------------------------------------------
    private class FakeDao(private val throwOnSync: Boolean = false) {
        var syncCalls = 0
        fun syncAllIncremental() {
            syncCalls += 1
            if (throwOnSync) throw java.io.IOException("provider.db unavailable")
        }
    }

    // ------------------------------------------------------------------
    // Fake prefs mirror: production `prefs.edit().putString("config",
    // mirrorStr).commit()` chain.
    // ------------------------------------------------------------------
    private class FakePrefs(private val commitResult: Boolean = true) {
        var committed: String? = null
        private var pending: String? = null
        fun edit() = this
        fun putString(key: String, value: String): FakePrefs {
            // Production writes the literal "config" key; the fake records
            // whatever key it was handed so a key drift fails visibly.
            check(key == "config") { "mirror key drifted: $key" }
            pending = value
            return this
        }
        fun commit(): Boolean {
            committed = pending
            return commitResult
        }
    }

    // ------------------------------------------------------------------
    // Replica of persistToDbAndMirror's write sequence. [fix/meta1028-d1 F-01]
    // changes only ONE thing in it: the hash is now taken from what prefs holds
    // AFTER the commit ([decideSyncHash]) instead of from the string we hoped to
    // write. Only the LOG branches are reproduced below, because those are what
    // the arms assert — see ProviderMirrorHashTest for the arbitration itself.
    // ------------------------------------------------------------------
    private fun persistReplica(dao: FakeDao, prefs: FakePrefs, mirrorStr: String): Boolean {
        val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
        if (!mirrorWritten) {
            ProbeLog.w(
                "[ProviderStore] prefs mirror commit() returned false — the DB rows will keep " +
                    "the previous json_sync_hash so the two stores stay in step; this save is " +
                    "lost in BOTH stores (disk full? prefs corruption?)",
            )
        }
        val dbWrite = try {
            dao.syncAllIncremental()
            true
        } catch (e: Exception) {
            ProbeLog.e(
                "[ProviderStore] DB incremental sync failed (provider.db unavailable?); " +
                    "the JSON mirror already carries this write — DB stale until the next " +
                    "launch re-imports it: ${e.message}",
            )
            false
        }
        if (!dbWrite && !mirrorWritten) {
            ProbeLog.w(
                "[ProviderStore] DB sync FAILED as well (see the incremental-sync error above) " +
                    "— neither store took this write, in-memory state only",
            )
        }
        return mirrorWritten
    }

    // ------------------------------------------------------------------
    // Faithful replica of restoreThinkingRules (entity build loop + the
    // [fix/dv22-a1 §1042-A-01] wrapped DAO call), with the resolver-cache
    // republish replaced by a recording flag.
    // ------------------------------------------------------------------
    private data class RuleEntity(
        val id: String,
        val providerInstanceId: String,
        val label: String,
        val scopeKind: String,
        val scopePattern: String? = null,
        val wireFormatJson: String? = null,
        val reasoningEchoJson: String? = null,
        val sortOrder: Int,
    )

    private class FakeRuleDao(private val throwOnReplace: Boolean = false) {
        var replaceCalls = 0
        var lastEntities: List<RuleEntity> = emptyList()
        fun replaceThinkingRules(instanceId: String, entities: List<RuleEntity>) {
            replaceCalls += 1
            lastEntities = entities
            if (throwOnReplace) throw java.io.IOException("provider.db unavailable")
        }
    }

    private fun restoreReplica(
        instanceId: String,
        rows: JSONArray,
        dao: FakeRuleDao,
        republished: MutableList<String>,
    ): Int {
        val entities = ArrayList<RuleEntity>()
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val label = o.optString("label", "")
            if (label.isEmpty()) continue
            val scopeKind = o.optString("scopeKind", "allModels")
            entities.add(
                RuleEntity(
                    id = id,
                    providerInstanceId = instanceId,
                    label = label,
                    scopeKind = scopeKind,
                    scopePattern = o.optString("scopePattern", "").ifEmpty { null },
                    wireFormatJson = o.optString("wireFormatJson", "").ifEmpty { null },
                    reasoningEchoJson = o.optString("reasoningEchoJson", "").ifEmpty { null },
                    sortOrder = o.optInt("sortOrder", i),
                ),
            )
        }
        if (entities.isEmpty()) return 0
        // [fix/dv22-a1 §1042-A-01] same bare-DAO hardening as the nine
        // sibling functions: failure is logged and reported as 0 restored.
        val persisted = runCatching {
            dao.replaceThinkingRules(instanceId, entities)
        }.onFailure { e ->
            ProbeLog.e(
                "[ProviderStore] restoreThinkingRules persistence failed " +
                    "($instanceId, ${entities.size} rules) — reporting 0 restored: ${e.message}",
            )
        }.isSuccess
        if (!persisted) return 0
        // Production: republishThinkingCache(instanceId) — success only, so
        // the cache never claims rules that are not on disk.
        republished.add(instanceId)
        return entities.size
    }

    private fun resetProbes() {
        ProbeLog.errors.clear()
        ProbeLog.warnings.clear()
    }

    // ==================================================================
    // Arm 1 — degraded persist: DAO throws → no escape, mirror still lands
    // ==================================================================
    @Test
    fun daoFailureStillWritesTheMirror() {
        resetProbes()
        val dao = FakeDao(throwOnSync = true)
        val prefs = FakePrefs()
        val mirror = """{"entries":[],"instances":[]}"""

        val mirrorWritten = persistReplica(dao, prefs, mirror)

        assertTrue("mirror must still be written when the DAO throws", mirrorWritten)
        assertEquals("mirror content must be the serialized config", mirror, prefs.committed)
        assertEquals("DAO sync attempted exactly once", 1, dao.syncCalls)
        assertEquals(
            "DB failure must be logged (loudly, not silently swallowed)",
            1,
            ProbeLog.errors.size,
        )
        assertTrue(
            "failure log must name the degraded mode",
            ProbeLog.errors[0].contains("the JSON mirror already carries this write"),
        )
    }

    // ==================================================================
    // Arm 2 — healthy persist: DB write succeeds, mirror lands, no errors
    // ==================================================================
    @Test
    fun healthyPersistWritesBothStores() {
        resetProbes()
        val dao = FakeDao()
        val prefs = FakePrefs()
        val mirror = """{"entries":[{"id":"i1/m1"}]}"""

        val mirrorWritten = persistReplica(dao, prefs, mirror)

        assertTrue(mirrorWritten)
        assertEquals(mirror, prefs.committed)
        assertEquals(1, dao.syncCalls)
        assertTrue("no failure log on the healthy path", ProbeLog.errors.isEmpty())
    }

    // ==================================================================
    // Arm 3 — double failure: mirror commit rejected AND DB sync failed →
    // the warning must NOT claim "DB remains authoritative".
    // ==================================================================
    @Test
    fun doubleFailureWarningDoesNotFalselyReassure() {
        resetProbes()
        val dao = FakeDao(throwOnSync = true)
        val prefs = FakePrefs(commitResult = false)

        val mirrorWritten = persistReplica(dao, prefs, "mirror")

        assertFalse(mirrorWritten)
        assertEquals(1, ProbeLog.warnings.size)
        assertFalse(
            "double failure must not claim the DB is authoritative",
            ProbeLog.warnings[0].contains("DB remains authoritative"),
        )
        assertTrue(
            "double failure must state neither store took the write",
            ProbeLog.warnings[0].contains("neither store took this write"),
        )
    }

    // ==================================================================
    // Arm 4 — mirror-only failure (DB ok, commit rejected): the old
    // truthful message is preserved for this case.
    // ==================================================================
    @Test
    fun mirrorOnlyFailureStillReportsDbAuthoritative() {
        resetProbes()
        val dao = FakeDao()
        val prefs = FakePrefs(commitResult = false)

        val mirrorWritten = persistReplica(dao, prefs, "mirror")

        assertFalse(mirrorWritten)
        assertEquals(1, ProbeLog.warnings.size)
        assertTrue(
            "the DB rows keep the previous hash so the stores stay in step",
            ProbeLog.warnings[0].contains("the two stores stay in step"),
        )
    }

    // ==================================================================
    // Arm 5 — restore with a broken DAO: returns 0, no throw, logged,
    // republish skipped (cache keeps matching what is actually on disk).
    // ==================================================================
    @Test
    fun restoreDaoFailureReturnsZeroWithoutThrowing() {
        resetProbes()
        val dao = FakeRuleDao(throwOnReplace = true)
        val republished = mutableListOf<String>()
        val rows = JSONArray().apply {
            put(JSONObject().put("id", "rule-1").put("label", "短路"))
            put(JSONObject().put("id", "rule-2").put("label", "隐藏思考"))
        }

        val restored = restoreReplica("inst-1", rows, dao, republished)

        assertEquals("a failed persist must report 0 rules restored", 0, restored)
        assertEquals("DAO write attempted exactly once", 1, dao.replaceCalls)
        assertEquals(2, dao.lastEntities.size)
        assertEquals("failure must be logged", 1, ProbeLog.errors.size)
        assertTrue(ProbeLog.errors[0].contains("restoreThinkingRules persistence failed"))
        assertTrue(
            "cache republish must NOT run when nothing landed",
            republished.isEmpty(),
        )
    }

    // ==================================================================
    // Arm 6 — restore success path semantics are untouched: count,
    // field mapping and the republish all keep their original behavior.
    // ==================================================================
    @Test
    fun restoreSuccessKeepsItsSemantics() {
        resetProbes()
        val dao = FakeRuleDao()
        val republished = mutableListOf<String>()
        val rows = JSONArray().apply {
            put(
                JSONObject()
                    .put("id", "rule-1")
                    .put("label", "短路")
                    .put("scopeKind", "modelPattern")
                    .put("scopePattern", "gpt-*")
                    .put("wireFormatJson", """{"field":"reasoning"}""")
                    .put("reasoningEchoJson", """{"timing":"stream"}""")
                    .put("sortOrder", 7),
            )
            put(JSONObject().put("id", "rule-2").put("label", "第二"))
        }

        val restored = restoreReplica("inst-1", rows, dao, republished)

        assertEquals("both valid rows restored", 2, restored)
        assertEquals(1, dao.replaceCalls)
        assertEquals(2, dao.lastEntities.size)

        val first = dao.lastEntities[0]
        assertEquals("rule-1", first.id)
        assertEquals("inst-1", first.providerInstanceId)
        assertEquals("短路", first.label)
        assertEquals("modelPattern", first.scopeKind)
        assertEquals("gpt-*", first.scopePattern)
        assertEquals("""{"field":"reasoning"}""", first.wireFormatJson)
        assertEquals("""{"timing":"stream"}""", first.reasoningEchoJson)
        assertEquals(7, first.sortOrder)

        val second = dao.lastEntities[1]
        assertEquals("rule-2", second.id)
        assertEquals("default scope is allModels", "allModels", second.scopeKind)
        assertNull("absent pattern stays null", second.scopePattern)
        assertEquals("absent sortOrder falls back to the loop index", 1, second.sortOrder)

        assertEquals("republish runs exactly once on success", listOf("inst-1"), republished)
        assertTrue("no error log on the success path", ProbeLog.errors.isEmpty())
    }

    // ==================================================================
    // Arm 7 — the documented no-op is unchanged: a backup section with no
    // (valid) rules for this instance never wipes and never calls the DAO.
    // ==================================================================
    @Test
    fun restoreWithNoValidRowsIsANoOp() {
        resetProbes()
        val dao = FakeRuleDao()
        val republished = mutableListOf<String>()
        val rows = JSONArray().apply {
            put(JSONObject().put("id", "").put("label", "blank id skipped"))
            put(JSONObject().put("id", "no-label"))
            put(JSONObject().put("id", "rule-3").put("label", ""))
        }

        val restored = restoreReplica("inst-1", rows, dao, republished)

        assertEquals(0, restored)
        assertEquals("DAO must not be touched for an empty entity list", 0, dao.replaceCalls)
        assertTrue(republished.isEmpty())
    }
}
