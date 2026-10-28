package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 D4 F-01] The live preview must not publish.
 *
 * `ThinkingRuleEditorDialog`'s preview is evaluated in the dialog's COMPOSITION body, so
 * a preview that writes to the process-wide rule cache bumps `customRulesRevision` on
 * every frame — and that revision is `ThinkingRulesSection`'s Room reload key
 * ([da1030-C ISSUE-05]). Neither half is JVM-drivable (the editor is Compose, the reload
 * is a Room query), so what is pinned here is the WIRING:
 *
 *  ① the resolver exposes a read-only evaluation entry that takes the draft as a value;
 *  ② the editor's preview uses it and never touches the publish verb;
 *  ③ `apply()` still resolves against the cache, so every request path is unchanged;
 *  ④ the publish verb still bumps the revision — the parent screen's refresh is intact
 *     (that is the thing a "just delete the bump" fix would silently break).
 *
 * Source-shape assertions on purpose: they are the only thing that can run in a JVM unit
 * test for this pair of files. Expectations are literals.
 */
class ThinkingRulePreviewNoPublishTest {

    private val resolver: String by lazy {
        readRepoFile(
            "app/src/main/java/com/rikkaminis/app/provider/thinking/ThinkingRuleResolver.kt",
        )
    }
    private val editor: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/ThinkingRuleEditor.kt")
    }

    /** Comment-stripped: the fix's own prose names both verbs. */
    private fun code(src: String): String = withoutComments(src)

    // -- ① a read-only entry exists and takes the draft as a value --

    @Test
    fun resolverExposesADraftEvaluationEntry() {
        assertTrue(
            "ThinkingRuleResolver must expose a draft evaluation entry (the preview has " +
                "no other way to resolve an unsaved rule)",
            code(resolver).contains("fun applyDraftRules("),
        )
        assertTrue(
            "the draft entry must take the rules as a parameter — a cache read would " +
                "reintroduce the publish",
            code(resolver).contains("draftRules: List<ThinkingRule>"),
        )
    }

    // -- ② the editor's preview uses it and never publishes --

    @Test
    fun previewUsesTheReadOnlyEntry() {
        assertTrue(
            "previewJson must evaluate through applyDraftRules",
            code(editor).contains("ThinkingRuleResolver.applyDraftRules("),
        )
    }

    @Test
    fun previewNeverCallsThePublishVerb() {
        assertFalse(
            "previewJson must not call setCustomRules: that is the publish the fix removes " +
                "(it bumped customRulesRevision twice per frame)",
            code(editor).contains("setCustomRules("),
        )
    }

    // -- ③ every request path still resolves against the cache --

    @Test
    fun applyStillReadsTheCache() {
        val src = code(resolver)
        assertTrue(
            "apply() must still resolve against the cache (every request path goes through it)",
            src.contains("fun apply(") && src.contains("customRulesFor(ctx.instanceId)"),
        )
    }

    // -- ④ the parent screen's refresh is intact --

    @Test
    fun publishingStillBumpsTheRevision() {
        // [da1030-C ISSUE-05]: the provider screen collects this to re-query Room.
        // Judged INSIDE setCustomRules' body, not by a bare `contains`: four sites bump
        // the revision, so a bare contains stays green when the publish verb's own bump
        // is deleted — which is exactly the regression this assertion exists for (the fix
        // must not "solve" the storm by removing the parent screen's refresh).
        val body = functionBody(resolver, "fun setCustomRules(")
        assertTrue(
            "setCustomRules must still bump customRulesRevision — removing the bump would " +
                "silently break the parent list's external-write refresh",
            body.contains("_customRulesRevision.value += 1"),
        )
    }

    @Test
    fun theParentListStillCollectsTheRevision() {
        val section = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/settings/ThinkingRulesSection.kt",
        )
        assertTrue(
            "ThinkingRulesSection must still subscribe to customRulesRevision",
            code(section).contains("ThinkingRuleResolver.customRulesRevision.collectAsState()"),
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Body of the declaration at [marker], by brace matching — a bare `contains` on a
     *  verb that appears in several functions cannot tell which one was changed. */
    private fun functionBody(src: String, marker: String): String {
        val i = src.indexOf(marker)
        assertTrue("$marker not found (fail-closed)", i >= 0)
        val open = src.indexOf('{', i)
        assertTrue("no body opened at/after $marker", open >= 0)
        var depth = 0
        for (j in open until src.length) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, j + 1)
                }
            }
        }
        throw AssertionError("unbalanced body at offset $open")
    }

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
