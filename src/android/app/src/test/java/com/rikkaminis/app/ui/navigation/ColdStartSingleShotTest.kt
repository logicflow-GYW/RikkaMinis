package com.rikkaminis.app.ui.navigation

import com.rikkaminis.app.deeplink.DeepLinkAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1038 B-02] the cold-start route decision is single-shot.
 *
 * `AppNavigation` recomposes on theme / font-size / system dark-mode changes
 * (it reads `MaterialTheme.colorScheme.background` for the NavHost transition
 * surface), so anything evaluated bare in its body re-runs on every pass. The
 * cold-start seeding used to be two bare `val … = when (…)` bindings, which
 * meant:
 *
 *  - `setPendingChatAction(OPEN_CAMERA)` / `setPendingHtmlPreview(...)`
 *    replayed into a live `ChatScreen` (reactively consumed since da1028-C) —
 *    the camera re-opened, the preview dialog re-popped;
 *  - `ComposerDraftStore.nextDraftId(context)` re-minted a fresh draft id
 *    once the slot was free ⇒ `NavHost.startDestination` drifted (graph
 *    rebuild) and `saveText`'s slot guard silently dropped the draft text.
 *
 * Two halves, both needed and both asserted here:
 *
 *  1. BEHAVIOUR — [coldStartStartDestination], the Android-free seam the
 *     composable now calls, runs every injected seed AT MOST once per cold
 *     start and derives the route deterministically. Plain JVM run.
 *  2. SHAPE — the composable derives `startDestination` from a
 *     `remember(initialDeepLink) { … }` block, so the seam runs once per
 *     composition lifetime. A JVM run cannot execute Compose, so this half is
 *     asserted against the real `AppNavigation.kt` text — same style as
 *     `ColdStartDispatchShapeTest` — with an in-test negative control so a
 *     probe that has quietly stopped looking fails loudly.
 */
class ColdStartSingleShotTest {

    private class Recorder {
        var draftIds = 0
        var cameraSeeds = 0
        val htmlSeeds = mutableListOf<Triple<String, String, String>>()
    }

    /** One cold-start evaluation of the seam, with every side effect counted. */
    private fun eval(link: DeepLinkAction?, rec: Recorder): String =
        coldStartStartDestination(
            initialDeepLink = link,
            nextDraftId = { rec.draftIds += 1; "draft-${rec.draftIds}" },
            chatRoute = { "chat/$it" },
            seedCamera = { rec.cameraSeeds += 1 },
            seedHtmlPreview = { sessionId, resourcePath, title ->
                rec.htmlSeeds += Triple(sessionId, resourcePath, title)
            },
        )

    // ── 1. behaviour of the seam (JVM, real run) ─────────────────────────

    @Test
    fun `pinned html shortcut seeds the preview once and mints no draft id`() {
        val rec = Recorder()
        val route = eval(
            DeepLinkAction.OpenHtmlPreview("sess-7", "/browser/snake.html", "Snake"),
            rec,
        )
        assertEquals("chat/sess-7", route)
        assertEquals(listOf(Triple("sess-7", "/browser/snake.html", "Snake")), rec.htmlSeeds)
        assertEquals(0, rec.draftIds)
        assertEquals(0, rec.cameraSeeds)
    }

    @Test
    fun `camera quick action seeds the camera once and mints one draft id`() {
        val rec = Recorder()
        val route = eval(DeepLinkAction.NewCameraChat, rec)
        assertEquals("chat/draft-1", route)
        assertEquals(1, rec.cameraSeeds)
        assertEquals(1, rec.draftIds)
        assertTrue("the camera path must not seed the html preview", rec.htmlSeeds.isEmpty())
    }

    @Test
    fun `plain new chat mints one draft id and seeds nothing`() {
        val rec = Recorder()
        val route = eval(DeepLinkAction.NewChat, rec)
        assertEquals("chat/draft-1", route)
        assertEquals(1, rec.draftIds)
        assertEquals(0, rec.cameraSeeds)
        assertTrue(rec.htmlSeeds.isEmpty())
    }

    @Test
    fun `null deep link and unrelated actions land on a single fresh draft`() {
        for (link in listOf<DeepLinkAction?>(null, DeepLinkAction.OpenAlarmList)) {
            val rec = Recorder()
            val route = eval(link, rec)
            assertEquals("chat/draft-1", route)
            assertEquals("exactly one draft id per cold start for $link", 1, rec.draftIds)
            assertEquals(0, rec.cameraSeeds)
            assertTrue(rec.htmlSeeds.isEmpty())
        }
    }

    @Test
    fun `every fresh-draft entry mints exactly one draft id per evaluation`() {
        for (link in listOf<DeepLinkAction?>(
            DeepLinkAction.NewChat,
            DeepLinkAction.NewCameraChat,
            null,
        )) {
            val rec = Recorder()
            eval(link, rec)
            assertEquals("$link mints exactly one draft id", 1, rec.draftIds)
        }
    }

    // ── 2. shape of the call site (recomposition safety) ─────────────────

    private val navRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"

    @Test
    fun `cold-start decision is derived from a remember block keyed on initialDeepLink`() {
        val src = readRepoFile(navRel)
        val binding = Regex("\\bval\\s+startDestination\\s*=").find(src)
        assertTrue(
            "`val startDestination =` anchor moved — re-anchor this probe, do not drop it",
            binding != null,
        )
        val from = binding!!.range.last + 1
        val head = src.substring(from).trimStart()
        val remember = Regex("^remember\\s*\\(([^)]*)\\)\\s*\\{").find(head)
        assertTrue(
            "the cold-start decision must run inside `remember(…)`, not bare — got: " +
                head.take(60),
            remember != null,
        )
        assertTrue(
            "the remember key must be initialDeepLink (the immutable onCreate parameter)",
            "initialDeepLink" in remember!!.groupValues[1],
        )
        val span = holderSpan(src, from)
        assertTrue("remember block body not found", span != null)
        val body = src.substring(span!!.first, span.second)
        assertTrue(
            "draft-id minting must live inside the single-shot block",
            body.contains("ComposerDraftStore.nextDraftId("),
        )
        assertTrue(
            "the camera seed must live inside the single-shot block",
            body.contains("DeepLinkCoordinator.setPendingChatAction("),
        )
        assertTrue(
            "the html-preview seed must live inside the single-shot block",
            body.contains("DeepLinkCoordinator.setPendingHtmlPreview("),
        )
    }

    @Test
    fun `composition top level runs no pending seed`() {
        val src = readRepoFile(navRel)
        val topLevel = topLevelSeedCalls(src)
        assertTrue(
            "pending seeds run bare at the composition top level: " +
                "$topLevel (every recomposition re-plays them into a live ChatScreen)",
            topLevel.isEmpty(),
        )
        // Negative control: the probe must reject a re-added bare seed.
        val anchor = "    val startDestination = remember(initialDeepLink) {"
        assertTrue("shape anchor moved — the negative control looked at nothing", src.contains(anchor))
        val broken = src.replace(
            anchor,
            "    val replayedSeed = DeepLinkCoordinator.setPendingHtmlPreview(\"s\", \"/p\", \"t\")\n" +
                anchor,
        )
        assertEquals(
            "the probe has no teeth: a bare top-level seed stayed invisible",
            listOf("setPendingHtmlPreview("),
            topLevelSeedCalls(broken),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Pending-seed calls that sit OUTSIDE every `remember(…)` / `LaunchedEffect(…)`
     * block — i.e. evaluated bare at the composition top level. Span-based
     * (same algorithm as gate criterion 12b): an edit-and-delete loop would
     * eat neighbouring blocks when an anchor's body is removed.
     */
    private fun topLevelSeedCalls(srcRaw: String): List<String> {
        val src = commentFreeLines(srcRaw)
        val spans = holderSpans(src)
        val out = mutableListOf<String>()
        for (call in listOf("setPendingChatAction(", "setPendingHtmlPreview(")) {
            var i = 0
            while (true) {
                val j = src.indexOf(call, i)
                if (j < 0) break
                if (spans.none { (s, e) -> j >= s && j < e }) out += call
                i = j + 1
            }
        }
        return out
    }

    /** Spans of every `remember(…) { … }` / `LaunchedEffect(…) { … }` block. */
    private fun holderSpans(src: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        for (anchor in listOf("remember(", "LaunchedEffect(")) {
            var i = 0
            while (true) {
                val j = src.indexOf(anchor, i)
                if (j < 0) break
                val span = holderSpan(src, j)
                if (span == null) {
                    i = j + anchor.length
                    continue
                }
                spans += span
                i = span.second
            }
        }
        return spans
    }

    /** Span of the `anchor(…) { … }` block whose anchor starts at [anchorIdx]. */
    private fun holderSpan(src: String, anchorIdx: Int): Pair<Int, Int>? {
        val open = src.indexOf('{', anchorIdx)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                i = skipString(src, i)
                continue
            }
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth == 0) return anchorIdx to i + 1
            }
            i++
        }
        return null
    }

    private fun commentFreeLines(src: String): String =
        src.split("\n").filterNot { line ->
            val s = line.trim()
            s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
        }.joinToString("\n")

    private fun skipString(src: String, start: Int): Int {
        if (src.startsWith("\"\"\"", start)) {
            val k = src.indexOf("\"\"\"", start + 3)
            return if (k < 0) src.length else k + 3
        }
        var i = start + 1
        while (i < src.length) {
            if (src[i] == '\\') {
                i += 2
                continue
            }
            if (src[i] == '"' || src[i] == '\n') return i + 1
            i++
        }
        return src.length
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative] (Gradle sets user.dir to the module dir; a sandbox JVM run can
     * start anywhere under the repo — no fixed depth works).
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
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
