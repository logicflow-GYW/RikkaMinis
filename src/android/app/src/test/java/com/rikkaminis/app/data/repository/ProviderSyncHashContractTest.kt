package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [R8-1114 A-F06] `decideSyncHash` must agree with its own contract: the token
 * names a mirror that EXISTS, so a prefs store holding no mirror has no hash to
 * give — `null` (which makes `ProviderRepository` delete the meta key) instead
 * of a hash of the string we merely hoped to write.
 *
 * The load-side rule is pinned alongside it, because the whole point of the
 * token is that `decideConfigSource` reads it back: with no mirror present the
 * DB branch is taken whether or not a token survives, so deleting the key does
 * not change the cold-start conclusion.
 */
class ProviderSyncHashContractTest {

    @Test
    fun noLiveMirrorYieldsNoHash() {
        assertNull(decideSyncHash(intendedMirror = """{"marker":"intended"}""", liveMirror = null))
    }

    @Test
    fun anEmptyLiveMirrorStillNamesItself() {
        // An empty string IS a mirror prefs holds; only `null` means "nothing".
        assertEquals(hashJsonMirror(""), decideSyncHash(intendedMirror = "ignored", liveMirror = ""))
    }

    @Test
    fun theHashAlwaysNamesTheLiveMirrorNotTheIntendedOne() {
        val live = """{"marker":"live"}"""
        val intended = """{"marker":"intended"}"""
        assertEquals(hashJsonMirror(live), decideSyncHash(intendedMirror = intended, liveMirror = live))
    }

    @Test
    fun deletingTheTokenDoesNotChangeTheColdStartConclusion() {
        // No mirror + DB rows: the load path reads the DB either way, so the
        // deleteMetaKey branch is behaviour-preserving, not a downgrade.
        assertEquals(
            ConfigLoadSource.DB,
            decideConfigSource(hasDbConfig = true, hasMirror = false, liveHashMatchesStored = false),
        )
        assertEquals(
            ConfigLoadSource.DB,
            decideConfigSource(hasDbConfig = true, hasMirror = false, liveHashMatchesStored = true),
        )
    }
}
