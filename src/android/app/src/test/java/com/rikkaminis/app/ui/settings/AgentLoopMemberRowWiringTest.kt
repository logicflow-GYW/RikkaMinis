package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [agentloop-member-id] Settings → Agent Loop Models, the entry row's secondary
 * line.
 *
 * The agent-loop list is the DRAG-REORDER surface for the curated usable set —
 * exactly like Settings → Model Group detail, which got `provider · model id`
 * in [model-group-member-id]. The agent-loop rows kept showing the provider
 * alone, so two entries sharing a display name under one provider (an OpenRouter
 * instance with 300+ entries is the real case) were indistinguishable while
 * reordering them.
 *
 * The DECISION is not duplicated here: `ModelGroupMemberRowText.secondaryLine`
 * already owns what the line says when a half is missing, and
 * `SystemVoiceMemberPresentation.memberSubtitle` owns "a System entry's provider
 * is the device". Both are pinned by ModelGroupMemberRowTextTest. This file
 * therefore asserts only the WIRING of the second consumer, on production text
 * with line comments blanked out — a `?: return` on a missing file would make
 * every arm green forever, so a missing source is a hard failure.
 */
class AgentLoopMemberRowWiringTest {

    private val settingsDir = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings"
    private val screenRel = "$settingsDir/AgentLoopModelsScreen.kt"

    private fun sourceFileOrNull(rel: String): File? {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        return null
    }

    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) " ".repeat(line.length) else line
    }

    private fun requireSource(rel: String): String =
        sourceFileOrNull(rel)?.let { codeOnly(it.readText()) }
            ?: throw IllegalStateException("cannot locate $rel — a source-shape arm must never skip")

    /**
     * The whole point: the entry row must name the exact model, not just the
     * provider. Built through the shared helper so the two surfaces cannot
     * drift apart in how they degrade.
     */
    @Test
    fun `the entry row uses the shared secondary line`() {
        val code = requireSource(screenRel)
        assertTrue(
            "AgentLoopModelsScreen must build the entry line through ModelGroupMemberRowText",
            code.contains("ModelGroupMemberRowText.secondaryLine("),
        )
        assertTrue(
            "the line must carry the model id, not just the provider label",
            code.contains("modelId = entry.model.id"),
        )
    }

    /**
     * The row must RENDER the composed line. Composing it and then passing
     * `instanceLabel` to the row would keep the id invisible while every
     * assertion above still passed — this is the arm that catches that.
     */
    @Test
    fun `the entry row renders the composed line, not the bare provider`() {
        val code = requireSource(screenRel)
        assertTrue(
            "the entry row must pass the composed secondaryLine to the row",
            code.contains("subtitle = secondaryLine"),
        )
        assertTrue(
            "the entry row must not pass the bare provider label any more",
            !code.contains("subtitle = instanceLabel"),
        )
    }

    /**
     * A System voice member has no ProviderInstance — the device is its
     * provider. Reading `instance?.label` alone left that row's line blank
     * (the same defect [dv27-B3] fixed on the model-group page).
     */
    @Test
    fun `the provider label comes from the shared presentation helper`() {
        val code = requireSource(screenRel)
        assertTrue(
            "the provider name must be resolved through SystemVoiceMemberPresentation",
            code.contains("SystemVoiceMemberPresentation.memberSubtitle("),
        )
    }

    /**
     * Provider ids are long (`custom_model_tokenpony_deepseek_v4_1_flash`). The
     * row is a drag target, so a wrapping secondary line would change row
     * heights mid-reorder and make the list jump under the finger.
     */
    @Test
    fun `the secondary line is single line with an ellipsis`() {
        val code = requireSource(screenRel)
        assertTrue(
            "the secondary line must be capped at one line",
            code.contains("maxLines = 1"),
        )
        assertTrue(
            "the secondary line must ellipsize rather than clip mid-glyph",
            code.contains("TextOverflow.Ellipsis"),
        )
    }
}
