package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WAVE-1118 D4 F-01] Forcing a non-`auto` image endpoint must clear the cached
 * probe result — the same decision the `minis-config` writer and
 * `ProviderRepository.updateInstance` already make.
 *
 * The defect in one sentence: the segmented picker was the ONE write face that
 * only moved `imageEndpointMode`, so a probe cached before the user forced a
 * mode survived and (on the way back to `auto`) was routed on instead of being
 * re-probed, while the footer advertised it as a fresh resolution.
 *
 * `ProviderConnectionScreen` itself is Compose + repository, so the decision is
 * pinned twice: behaviour on the extracted pure functions, and a source-shape
 * arm that every picker branch still goes THROUGH them (a fourth write face
 * added inline would otherwise stay green).
 */
class ProviderImageEndpointSelectionTest {

    private fun instance(
        mode: ImageEndpointMode = ImageEndpointMode.auto,
        resolved: ImageEndpointMode? = null,
    ): ProviderInstance = ProviderInstance(
        id = "p1",
        label = "OpenAI",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        imageEndpointMode = mode,
        imageEndpointResolved = resolved,
    )

    // ── behaviour ────────────────────────────────────────────────

    @Test
    fun forcingImagesGenerationsClearsACachedChatCompletions() {
        val updated = withImageEndpointMode(
            instance(ImageEndpointMode.auto, ImageEndpointMode.chatCompletions),
            ImageEndpointMode.imagesGenerations,
        )
        assertEquals(ImageEndpointMode.imagesGenerations, updated.imageEndpointMode)
        assertNull(
            "a probe cached under auto must not outlive a forced mode",
            updated.imageEndpointResolved,
        )
    }

    @Test
    fun forcingChatCompletionsClearsACachedImagesGenerations() {
        val updated = withImageEndpointMode(
            instance(ImageEndpointMode.auto, ImageEndpointMode.imagesGenerations),
            ImageEndpointMode.chatCompletions,
        )
        assertEquals(ImageEndpointMode.chatCompletions, updated.imageEndpointMode)
        assertNull(updated.imageEndpointResolved)
    }

    @Test
    fun switchingBackToAutoKeepsTheCache() {
        // The other half of the contract: `auto` means "let the probe decide",
        // and the cached result is still a fact about this upstream. Clearing it
        // here would make every switch back to auto re-probe — the over-fix
        // this arm exists to catch.
        val updated = withImageEndpointMode(
            instance(ImageEndpointMode.imagesGenerations, ImageEndpointMode.chatCompletions),
            ImageEndpointMode.auto,
        )
        assertEquals(ImageEndpointMode.auto, updated.imageEndpointMode)
        assertEquals(ImageEndpointMode.chatCompletions, updated.imageEndpointResolved)
    }

    @Test
    fun forcingAModeWithNoCacheLeavesItNull() {
        assertNull(resolvedAfterForcingImageEndpointMode(ImageEndpointMode.imagesGenerations, null))
        assertNull(resolvedAfterForcingImageEndpointMode(ImageEndpointMode.auto, null))
    }

    @Test
    fun theInstanceIsCopiedNotMutated() {
        val original = instance(ImageEndpointMode.auto, ImageEndpointMode.chatCompletions)
        withImageEndpointMode(original, ImageEndpointMode.imagesGenerations)
        assertEquals(ImageEndpointMode.auto, original.imageEndpointMode)
        assertEquals(ImageEndpointMode.chatCompletions, original.imageEndpointResolved)
    }

    // ── wiring: the picker has no private write face ──────────────

    @Test
    fun everyPickerBranchGoesThroughTheSharedHelper() {
        val screen = withoutComments(
            readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/ProviderConnectionScreen.kt"),
        )
        assertEquals(
            "the picker must not keep an inline copy(imageEndpointMode = …) write face",
            0,
            countOf(screen, "copy(imageEndpointMode"),
        )
        assertEquals(
            "all three segments must write through withImageEndpointMode",
            3,
            countOf(screen, "withImageEndpointMode(currentInstance()"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun countOf(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    private fun withoutComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val nl = src.indexOf('\n', i)
                    i = if (nl < 0) src.length else nl
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                c == '"' -> {
                    out.append(c)
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            out.append(src, i, i + 2)
                            i += 2
                            continue
                        }
                        out.append(src[i])
                        if (src[i] == '"') {
                            i++
                            break
                        }
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(
            System.getProperty("user.dir") ?: error("user.dir unset"),
        )
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
