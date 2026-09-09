package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RC5 / P0-pinned + GH#68] JVM round-trip regression for the ProviderInstance
 * run-config fields on the backup export/import path.
 *
 * These five fields (isEnabled / azureMode / imageEndpointMode /
 * imageEndpointResolved / pinned) were previously NEVER written by the backup
 * export, so a config restore / device migration silently reset them to
 * defaults — same root cause as the Room-layer P0-pinned & GH#68 fixes, only
 * the Room/DB layer was patched and the JSON backup serializer drifted.
 *
 * ProviderRepository itself needs an Android Context (not JVM-constructible),
 * so the test pins the contract through the pure, internal seam
 * [writeRunConfigFields] / [readRunConfigFields] that both [exportInstanceJSON]
 * and [importInstanceJSON] delegate to. A regression here means the backup
 * round-trip silently drops one of the run-config fields again.
 */
class ProviderRunConfigRoundTripTest {

    private fun instance(
        isEnabled: Boolean,
        azureMode: Boolean,
        imageEndpointMode: ImageEndpointMode,
        imageEndpointResolved: ImageEndpointMode?,
        pinned: Boolean,
    ) = ProviderInstance(
        id = "i1",
        label = "P",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = isEnabled,
        azureMode = azureMode,
        imageEndpointMode = imageEndpointMode,
        imageEndpointResolved = imageEndpointResolved,
        pinned = pinned,
    )

    /**
     * Round-trip through the exact seam the production export/import use:
     * write into a fresh JSONObject (as [exportInstanceJSON] does), then
     * read it back (as [importInstanceJSON] does), and assert none of the five
     * fields are lost. This is the core RC5 regression: previously the exporter
     * never wrote these keys, so the read side silently fell back to defaults.
     */
    @Test
    fun roundTrip_preservesAllFiveRunConfigFields() {
        val src = instance(
            isEnabled = true,
            azureMode = true,
            imageEndpointMode = ImageEndpointMode.chatCompletions,
            imageEndpointResolved = ImageEndpointMode.imagesGenerations,
            pinned = true,
        )

        val obj = JSONObject()
        writeRunConfigFields(obj, src)
        val back = readRunConfigFields(obj)

        assertEquals(src.isEnabled, back.isEnabled)
        assertEquals(src.azureMode, back.azureMode)
        assertEquals(src.imageEndpointMode, back.imageEndpointMode)
        assertEquals(src.imageEndpointResolved, back.imageEndpointResolved)
        assertEquals(src.pinned, back.pinned)
    }

    /** All keys written verbatim (so a future drift in the writer is caught). */
    @Test
    fun export_writesAllFiveKeysWithCorrectValues() {
        val src = instance(
            isEnabled = true,
            azureMode = true,
            imageEndpointMode = ImageEndpointMode.imagesGenerations,
            imageEndpointResolved = ImageEndpointMode.chatCompletions,
            pinned = true,
        )
        val obj = JSONObject()
        writeRunConfigFields(obj, src)

        assertTrue(obj.getBoolean("isEnabled"))
        assertTrue(obj.getBoolean("azureMode"))
        assertEquals("imagesGenerations", obj.getString("imageEndpointMode"))
        assertEquals("chatCompletions", obj.getString("imageEndpointResolved"))
        assertTrue(obj.getBoolean("pinned"))
    }

    /** A fully-off instance must not emit the optional resolved cache key. */
    @Test
    fun export_omitsResolvedKeyWhenNull() {
        val src = instance(
            isEnabled = false,
            azureMode = false,
            imageEndpointMode = ImageEndpointMode.auto,
            imageEndpointResolved = null,
            pinned = false,
        )
        val obj = JSONObject()
        writeRunConfigFields(obj, src)

        assertFalse(obj.has("imageEndpointResolved"))
    }

    /**
     * Backward compatibility: an OLD backup (e.g. pre-RC5) lacks all five keys.
     * The reader must fall back to the model's defaults without crashing, so a
     * legacy restore behaves byte-for-byte as before.
     */
    @Test
    fun read_missingKeysFallsBackToDefaults() {
        val dict = JSONObject()
        val back = readRunConfigFields(dict)

        assertTrue(back.isEnabled)
        assertFalse(back.azureMode)
        assertEquals(ImageEndpointMode.auto, back.imageEndpointMode)
        assertNull(back.imageEndpointResolved)
        assertFalse(back.pinned)
    }

    /** An unknown enum name (future build) degrades to auto / no cache, not a crash. */
    @Test
    fun read_unknownEnumNamesDegradeGracefully() {
        val dict = JSONObject()
            .put("isEnabled", true)
            .put("azureMode", true)
            .put("imageEndpointMode", "SOME_FUTURE_MODE")
            .put("imageEndpointResolved", "ANOTHER_FUTURE_MODE")
            .put("pinned", true)

        val back = readRunConfigFields(dict)

        assertEquals(ImageEndpointMode.auto, back.imageEndpointMode)
        assertNull(back.imageEndpointResolved)
    }

    /**
     * Round-trip the full default instance too — ancestors of the RC5 fix often
     * wrote only the "interesting" values and forgot the default ones. A plain
     * default instance must round-trip to exactly the defaults.
     */
    @Test
    fun roundTrip_defaultInstanceKeepsDefaults() {
        val src = instance(
            isEnabled = true,
            azureMode = false,
            imageEndpointMode = ImageEndpointMode.auto,
            imageEndpointResolved = null,
            pinned = false,
        )
        val obj = JSONObject()
        writeRunConfigFields(obj, src)
        val back = readRunConfigFields(obj)

        assertEquals(src.isEnabled, back.isEnabled)
        assertEquals(src.azureMode, back.azureMode)
        assertEquals(src.imageEndpointMode, back.imageEndpointMode)
        assertEquals(src.imageEndpointResolved, back.imageEndpointResolved)
        assertEquals(src.pinned, back.pinned)
    }
}
