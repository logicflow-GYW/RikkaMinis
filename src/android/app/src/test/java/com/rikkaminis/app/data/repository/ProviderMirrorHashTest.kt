package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/meta1028-d1 F-01] `meta.json_sync_hash` must be a FACT about the prefs
 * mirror, not a claim about what we tried to write.
 *
 * The failure (B07 F-01): `persistToDbAndMirror` computed `hashJsonMirror(mirrorStr)`
 * from the string it was ABOUT to commit, wrote the DB (rows + that hash), and only
 * then called `prefs.commit()`. When the commit was rejected (disk full, corrupted
 * prefs XML) the store was left as DB(new rows) + `json_sync_hash = hash(new)` +
 * mirror(OLD string). `loadConfigSuspending` reads exactly that shape as "the mirror
 * is fresher than the DB — an older build wrote it" and back-fills the OLD config
 * over the rows the user just saved: their last provider/key/model-group edit is
 * gone on the next cold start, with a log line saying "re-importing JSON mirror".
 *
 * [decideSyncHash] hashes the read-back value instead, and [decideConfigSource] is
 * the load-side rule it has to stay consistent with. Both are pure, so the whole
 * save → crash → restart cycle is simulated here on fakes. Expected values are
 * literals / independent recomputations — never the production constants.
 */
class ProviderMirrorHashTest {

    /**
     * prefs + provider.db rows + `meta.json_sync_hash`, with the two failure knobs
     * a real save can hit. Mirrors the POST-FIX shape of
     * `persistToDbAndMirror`: DB write (with the claimed hash) → mirror commit →
     * repair the hash to the truth when the commit disagreed.
     */
    private class FakeStore(
        var mirrorCommitSucceeds: Boolean = true,
        var dbWriteThrows: Boolean = false,
        var repairThrows: Boolean = false,
    ) {
        var mirrorOnDisk: String? = null
        var dbRows: String? = null
        var dbHash: String? = null
        var reimports = 0
        var repairs = 0

        fun persist(config: String) {
            val claimedHash = hashJsonMirror(config)
            // 1. DB write (arm 10 pins this as the first step).
            if (!dbWriteThrows) {
                dbRows = config
                dbHash = claimedHash
            }
            // 2. Mirror write.
            if (mirrorCommitSucceeds) mirrorOnDisk = config
            // 3. [fix/meta1028-d1 F-01] Repair the token to what prefs really holds.
            val truth = decideSyncHash(intendedMirror = config, liveMirror = mirrorOnDisk)
            if (truth != claimedHash) {
                if (!repairThrows) {
                    repairs += 1
                    dbHash = truth
                }
                // repairThrows: the repair is best-effort, the token stays claimed.
            }
        }

        /**
         * `loadConfigSuspending`: hash match → DB; mismatch → re-import the mirror
         * and reseed DB + hash; no mirror → DB.
         */
        fun coldStart(): String? {
            val liveHash = mirrorOnDisk?.let(::hashJsonMirror)
            val source = decideConfigSource(
                hasDbConfig = dbRows != null,
                hasMirror = mirrorOnDisk != null,
                liveHashMatchesStored = dbHash != null && liveHash == dbHash,
            )
            if (source == ConfigLoadSource.DB) return dbRows
            val mirror = mirrorOnDisk ?: return null
            reimports += 1
            dbRows = mirror
            dbHash = liveHash
            return mirror
        }

        fun seededWith(config: String): FakeStore {
            persist(config)
            check(dbRows == config && mirrorOnDisk == config) { "seed save must land in both stores" }
            return this
        }
    }

    /**
     * CONTRAST ARM — faithful replica of the PRE-FIX save: the claimed hash is
     * recorded and nothing ever corrects it. Kept so the arms above are proven to
     * measure something: with the same fakes this replica must roll the user's
     * save back.
     */
    private fun legacyPersistAndColdStart(store: FakeStore, config: String): String? {
        val claimedHash = hashJsonMirror(config)
        if (!store.dbWriteThrows) {
            store.dbRows = config
            store.dbHash = claimedHash
        }
        if (store.mirrorCommitSucceeds) store.mirrorOnDisk = config
        // …no repair: the claim stands whatever commit() answered.
        return store.coldStart()
    }

    private val oldConfig = """{"marker":"old"}"""
    private val newConfig = """{"marker":"new"}"""

    // ── decideSyncHash ────────────────────────────────────────────────────

    @Test
    fun aCommittedMirrorIsHashedAsWritten() {
        assertEquals(
            hashJsonMirror(newConfig),
            decideSyncHash(intendedMirror = newConfig, liveMirror = newConfig),
        )
    }

    @Test
    fun aRejectedCommitHashesWhatTheMirrorActuallyHolds() {
        assertEquals(
            "the hash must name the OLD mirror, so the DB cannot claim a write that never landed",
            hashJsonMirror(oldConfig),
            decideSyncHash(intendedMirror = newConfig, liveMirror = oldConfig),
        )
    }

    @Test
    fun aPrefsThatReportsNothingFallsBackToTheIntendedString() {
        assertEquals(
            hashJsonMirror(newConfig),
            decideSyncHash(intendedMirror = newConfig, liveMirror = null),
        )
    }

    @Test
    fun anEmptyMirrorStillProducesAHash() {
        // An empty-string mirror is a real (if useless) mirror; the DB may name it.
        assertEquals(
            hashJsonMirror(""),
            decideSyncHash(intendedMirror = "", liveMirror = ""),
        )
    }

    // ── decideConfigSource ────────────────────────────────────────────────

    @Test
    fun matchingHashesReadTheDb() {
        assertEquals(
            ConfigLoadSource.DB,
            decideConfigSource(hasDbConfig = true, hasMirror = true, liveHashMatchesStored = true),
        )
    }

    @Test
    fun mismatchedHashesReimportTheMirror() {
        assertEquals(
            ConfigLoadSource.MIRROR,
            decideConfigSource(hasDbConfig = true, hasMirror = true, liveHashMatchesStored = false),
        )
    }

    @Test
    fun noDbRowsButAMirrorIsTheFirstLaunchImport() {
        assertEquals(
            ConfigLoadSource.MIRROR,
            decideConfigSource(hasDbConfig = false, hasMirror = true, liveHashMatchesStored = false),
        )
    }

    @Test
    fun noMirrorFallsBackToTheDbSnapshot() {
        assertEquals(
            ConfigLoadSource.DB,
            decideConfigSource(hasDbConfig = true, hasMirror = false, liveHashMatchesStored = false),
        )
    }

    // ── simulated save → restart cycles ───────────────────────────────────

    @Test
    fun healthySaveSurvivesRestartWithoutAReimport() {
        val store = FakeStore().seededWith(oldConfig)

        store.persist(newConfig)

        assertEquals(newConfig, store.coldStart())
        assertEquals("a healthy store must not decode an ~800KB mirror it does not need", 0, store.reimports)
    }

    /**
     * The F-01 headline. A rejected commit leaves DB(new rows) + mirror(old) with
     * a hash that names the OLD mirror — so the load path takes the in-step branch
     * and reads the DB, i.e. the user KEEPS their save. Pre-fix the hash claimed
     * the new mirror, the load path took the re-import branch, and the OLD config
     * was back-filled over those very rows.
     */
    @Test
    fun aRejectedCommitDoesNotRollBackTheSave() {
        val store = FakeStore().seededWith(oldConfig)
        store.mirrorCommitSucceeds = false

        store.persist(newConfig)

        assertEquals(
            "the DB rows must survive — a rejected mirror write must not resurrect the old config",
            newConfig,
            store.coldStart(),
        )
        assertEquals(
            "and they survive because the hash was repaired to name the mirror that exists",
            0,
            store.reimports,
        )
        assertEquals(1, store.repairs)
        assertEquals(
            "the mirror is still the old string: that is what the repaired hash names",
            oldConfig,
            store.mirrorOnDisk,
        )
    }

    @Test
    fun aFailedRepairLeavesTheClaimedHashAsTheDocumentedRisk() {
        val store = FakeStore().seededWith(oldConfig)
        store.mirrorCommitSucceeds = false
        store.repairThrows = true

        store.persist(newConfig)

        // The repair is best-effort: when it cannot run, the token keeps naming a
        // mirror that never landed and the load path takes the re-import branch.
        // That is the pre-fix behaviour, now logged and confined to this path.
        assertEquals(oldConfig, store.coldStart())
        assertEquals(1, store.reimports)
    }

    @Test
    fun contrastLegacyClaimedHashRollsTheSaveBack() {
        val store = FakeStore().seededWith(oldConfig)
        store.mirrorCommitSucceeds = false

        val afterRestart = legacyPersistAndColdStart(store, newConfig)

        assertEquals(
            "the pre-fix code must be shown to lose the save",
            oldConfig,
            afterRestart,
        )
        assertEquals(
            "and it loses it through the re-import branch, logging 're-importing JSON mirror'",
            1,
            store.reimports,
        )
    }

    @Test
    fun dbFailureAfterACommittedMirrorIsRecoveredOnRestart() {
        val store = FakeStore().seededWith(oldConfig)
        store.dbWriteThrows = true

        store.persist(newConfig)

        // Mirror(new) + DB(old rows) + hash(old) ⇒ mismatch ⇒ re-import ⇒ recovered.
        assertEquals(newConfig, store.coldStart())
        assertEquals(1, store.reimports)
        assertEquals(newConfig, store.dbRows)
        assertEquals(hashJsonMirror(newConfig), store.dbHash)
    }

    @Test
    fun anExternalMirrorWriteStillWins() {
        val store = FakeStore().seededWith(oldConfig)

        // An older build (downgrade window) rewrote prefs without touching the DB.
        store.mirrorOnDisk = """{"marker":"downgrade"}"""

        assertEquals("""{"marker":"downgrade"}""", store.coldStart())
        assertEquals("""{"marker":"downgrade"}""", store.dbRows)
    }

    @Test
    fun repeatedRejectedCommitsKeepServingTheLatestRows() {
        val store = FakeStore().seededWith(oldConfig)
        store.mirrorCommitSucceeds = false
        val newer = """{"marker":"newer"}"""

        store.persist(newConfig)
        store.persist(newer)

        assertEquals(newer, store.coldStart())
        assertEquals("no phantom downgrade is ever inferred", 0, store.reimports)
        assertEquals("each rejected commit repairs the token", 2, store.repairs)
    }
}
