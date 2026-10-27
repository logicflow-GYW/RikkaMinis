package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1016 9c/9d] Session binding for the shared shell browser pool,
 * and the session-scoped viewport, pinned on the JVM.
 *
 * Both decisions live in the pure [BrowserSessionBinding] because the code
 * around them needs a Context, a WebView and a filesystem. The arms that
 * matter:
 *
 *  - 9c: `minis-browser-use` must follow the *requesting* chat session, or
 *    minis:// resolution and downloads land in the placeholder session tree.
 *    Re-binding to the same session must stay a no-op — dropping the viewport
 *    override on every call would break the multi-call `set_viewport` …
 *    `get_page_info` sequence.
 *  - 9d: "the new session has no override" and "keep the previous session's
 *    override" must not be the same code path; a partial/degenerate pair
 *    collapses to no-override instead of leaving the old size applied.
 *
 * Expectations are literals; nothing is derived from a production constant, so
 * a changed constant shows up as a red test rather than a silently adjusted
 * expectation.
 */
class BrowserSessionBindingTest {

    // ── 9c: re-pointing the shared pool at a session ─────────────────────

    @Test
    fun `same session is a no-op`() {
        val decision = BrowserSessionBinding.decideRebind("session-a", "session-a")
        assertFalse("a repeat call must not re-bind", decision.rebind)
        assertFalse("a repeat call must not drop the viewport override", decision.resetViewport)
    }

    @Test
    fun `different session rebinds and drops the previous override`() {
        val decision = BrowserSessionBinding.decideRebind("session-a", "session-c")
        assertTrue("a new session must re-bind", decision.rebind)
        assertTrue("the previous session's viewport must not carry over", decision.resetViewport)
    }

    @Test
    fun `never bound pool counts as a change`() {
        val decision = BrowserSessionBinding.decideRebind(null, "session-a")
        assertTrue("a pool with no session must bind", decision.rebind)
        assertTrue("no session means no override to preserve", decision.resetViewport)
    }

    // ── 9d: viewport restored from a session's persisted state ───────────

    @Test
    fun `a complete positive pair is restored verbatim`() {
        val viewport = BrowserSessionBinding.viewportForLoadedSession(390, 844)
        assertEquals("width", 390, viewport.width)
        assertEquals("height", 844, viewport.height)
        assertTrue("390x844 is a real override", viewport.hasOverride)
    }

    @Test
    fun `absent override collapses to no-override`() {
        val viewport = BrowserSessionBinding.viewportForLoadedSession(0, 0)
        assertEquals("width", 0, viewport.width)
        assertEquals("height", 0, viewport.height)
        assertFalse("0x0 means no override", viewport.hasOverride)
    }

    @Test
    fun `half a viewport is not an override`() {
        // The pre-9d shape skipped the write entirely for these, which left the
        // previous session's size applied — the value the flow must land on is
        // "no override", not "whatever was there".
        for ((w, h) in listOf(390 to 0, 0 to 844)) {
            val viewport = BrowserSessionBinding.viewportForLoadedSession(w, h)
            assertEquals("width for ${w}x$h", 0, viewport.width)
            assertEquals("height for ${w}x$h", 0, viewport.height)
            assertFalse("${w}x$h is not an override", viewport.hasOverride)
        }
    }

    @Test
    fun `corrupt negative values do not resurrect the old size`() {
        val viewport = BrowserSessionBinding.viewportForLoadedSession(-390, 844)
        assertEquals("width", 0, viewport.width)
        assertEquals("height", 0, viewport.height)
        assertFalse("a negative width is corrupt, not an override", viewport.hasOverride)
    }

    // ── production wiring (source shape) ────────────────────────────────

    @Test
    fun `bindSessionContextOnly rebinds without loading persisted state`() {
        val body = memberSlice(readRepoFile(TabPoolPath), "fun bindSessionContextOnly(")
        assertTrue(
            "bindSessionContextOnly no longer consults the binding decision:\n$body",
            body.contains("BrowserSessionBinding.decideRebind("),
        )
        assertTrue(
            "the bound session must be stamped onto live tabs as well",
            body.contains("it.manager.minisSessionId = sessionId"),
        )
        // [dv29-B4 B-04] The literal zeroing moved into the collection point
        // `clearSessionViewportOverride()`, which drops the override AND
        // replays the resolved size onto the surviving tabs. Asserting the
        // `_sessionViewportWidth.value = 0` literals *here* would pin the
        // pre-fix shape (zero with no replay) — exactly the bug. The semantic
        // this arm keeps ("a session change must drop the previous viewport
        // override") is honoured through the collection call, and the two
        // halves of that collection point are pinned by
        // `the collection point clears and replays` below.
        assertTrue(
            "a session change must drop the previous viewport override through the " +
                "collection point (which also replays it — dv29-B4 B-04):\n$body",
            body.contains("if (decision.resetViewport) {") &&
                body.contains("clearSessionViewportOverride()"),
        )
        // The whole point of the context-only bind: setSession() also loads the
        // new session's browser_tabs/<sid>.json, which would drag that session's
        // saved tabs into the shared driver pool on every shell call.
        assertFalse(
            "bindSessionContextOnly must not load another session's persisted state:\n$body",
            body.contains("loadSavedState("),
        )
    }

    @Test
    fun `the shell handler binds the pool to the requesting session before executing`() {
        val src = readRepoFile(OffloadHandlerPath)
        val idxBind = src.indexOf("bindSessionContextOnly(")
        val idxExec = src.indexOf("sharedBrowserTabPool.execute(")
        assertTrue(
            "the shell handler no longer binds the shared pool to the requesting session",
            idxBind >= 0,
        )
        assertTrue(
            "the binding must happen before the pool executes the action " +
                "(bind@$idxBind execute@$idxExec)",
            idxBind < idxExec,
        )
        assertTrue(
            "the binding must use the request's session id",
            src.substring(maxOf(0, idxBind - 240), idxBind).contains("input.sessionId?.let"),
        )
        assertFalse(
            "the handler must not re-point the pool through setSession (it loads " +
                "another session's persisted tabs)",
            src.contains("sharedBrowserTabPool.setSession("),
        )
    }

    @Test
    fun `loadSavedState clears a stale viewport on both no-override paths`() {
        val body = memberSlice(readRepoFile(TabPoolPath), "private fun loadSavedState(")

        // arm 1: the session has no state file at all — the pre-9d code cleared
        // savedURLs here (rv1014-E5) but left the previous viewport applied.
        // [dv29-B4 B-04] …and "cleared the flows" alone still left the *tabs*
        // applied, so the branch must go through the collection point
        // (zero + replay) rather than touching the flows itself.
        val noFile = body.substringAfter("if (!file.exists()) {").substringBefore("return")
        assertTrue(
            "the no-state-file branch must drop the previous session's viewport " +
                "through the collection point (zero + replay on the live tabs):\n$noFile",
            noFile.contains("clearSessionViewportOverride()"),
        )

        // arm 2: a state file exists but carries no override — the write must be
        // unconditional, so "no override" cannot mean "keep the old one".
        val idxDecision = body.indexOf("BrowserSessionBinding.viewportForLoadedSession(")
        val idxWidth = body.indexOf("_sessionViewportWidth.value = restoredViewport.width")
        val idxHeight = body.indexOf("_sessionViewportHeight.value = restoredViewport.height")
        assertTrue("loadSavedState no longer uses the viewport decision core:\n$body", idxDecision >= 0)
        assertTrue("the restored width must come from the decision core", idxWidth > idxDecision)
        assertTrue("the restored height must come from the decision core", idxHeight > idxWidth)
        assertFalse(
            "loadSavedState must not gate the write on a positive pair again — " +
                "skipping it is exactly how the previous session's size survived:\n$body",
            body.contains("if (w > 0 && h > 0) {"),
        )
        // [dv29-B4 B-04] …and the restored pair must reach the live tabs. The
        // old shape replayed only inside `if (restoredViewport.hasOverride)`, so
        // "restore a 0 × 0 session" wrote 0 × 0 into the flows, replayed
        // nothing, and every surviving tab kept the previous session's
        // `lastAppliedViewport`.
        val idxReplay = body.indexOf("replayViewportToLiveTabs()", idxHeight)
        assertTrue(
            "the restored viewport must be replayed onto the live tabs after it is " +
                "written — a 0 × 0 restore is not \"nothing to do\" (dv29-B4 B-04):\n$body",
            idxReplay > idxHeight,
        )
        assertFalse(
            "loadSavedState must not gate the replay on `hasOverride` again — the " +
                "0 × 0 direction is the leak (dv29-B4 B-04):\n$body",
            body.contains("if (restoredViewport.hasOverride)"),
        )
    }

    // ── [dv29-B4 B-04] zeroing a viewport is only half a reset ──────────

    /**
     * The flows are the pool's intent; `BrowserUseManager.lastAppliedViewport`
     * is what the WebView actually keeps, and every later `navigate`,
     * `minis://` injection and full-page capture replays it. So *every* write
     * to a session viewport flow must be followed, in the same member, by a
     * replay — the pre-fix shape wrote 0 and stopped.
     *
     * Derived from the file (member slices + call graph), not from a list of
     * names, so a fourth path that writes the flows is covered by construction.
     */
    @Test
    fun `every session viewport write is followed by a replay`() {
        val src = stripComments(readRepoFile(TabPoolPath))
        val members = membersOf(src)
        val replaying = replayBearingMembers(members)
        assertTrue(
            "no member calls `applyViewportToAllTabs(` — the replay this criterion " +
                "anchors on is gone (ROT: re-point the test)\n",
            replaying.isNotEmpty(),
        )
        val writers = members.filter { m ->
            valueWrite.containsMatchIn(m.body)
        }
        assertTrue(
            "no member writes `_sessionViewportWidth/Height.value` — this criterion " +
                "lost its subject (ROT: re-point the test)",
            writers.isNotEmpty(),
        )
        for (m in writers) {
            val lastWrite = valueWrite.findAll(m.body).map { it.range.first }.maxOrNull()!!
            val lastReplay = replayPositions(m.body, replaying).maxOrNull() ?: -1
            assertTrue(
                "`${m.header.trim()}` writes a session viewport but never replays it " +
                    "afterwards — the live tabs keep the previous size in " +
                    "`lastAppliedViewport` (write@$lastWrite replay@$lastReplay)",
                lastReplay > lastWrite,
            )
        }
    }

    @Test
    fun `the collection point clears and replays`() {
        val src = stripComments(readRepoFile(TabPoolPath))
        val cz = membersOf(src).filter { m -> zeroWrite.containsMatchIn(m.body) && !m.suspends }
        assertEquals(
            "exactly one non-suspend member may drop the session viewport — a fourth " +
                "path that clears the flows itself is the defect this pins " +
                "(found: ${cz.map { it.header.trim() }})",
            1,
            cz.size,
        )
        val point = cz.single()
        assertTrue(
            "the collection point must be private (it is the pool's own invariant)",
            point.header.contains("private fun "),
        )
        assertTrue(
            "the collection point must zero BOTH flows:\n${point.body}",
            point.body.contains("_sessionViewportWidth.value = 0") &&
                point.body.contains("_sessionViewportHeight.value = 0"),
        )
        assertTrue(
            "the collection point must replay the resolved viewport:\n${point.body}",
            replayPositions(point.body, replayBearingMembers(membersOf(src)))
                .isNotEmpty(),
        )
    }

    @Test
    fun `a disposed pool never replays`() {
        val src = stripComments(readRepoFile(TabPoolPath))
        val members = membersOf(src)
        val point = members
            .filter { m -> zeroWrite.containsMatchIn(m.body) && !m.suspends }
            .singleOrNull()
        assertTrue(
            "no single member drops the session viewport (found: " +
                "${members.filter { m -> zeroWrite.containsMatchIn(m.body) }.map { it.name }}) — " +
                "this criterion has no subject",
            point != null,
        )
        // The replay the clear hands off to must refuse to launch on a disposed
        // pool: dispose() owns every async face (dv24-B4 B-07), and a replay
        // that already reached Main would drive a destroyed WebView.
        val launched = members.filter { m ->
            m.name != point!!.name && calls(point.body, m.name) &&
                m.body.contains("applyViewportToAllTabs(")
        }
        assertTrue(
            "the collection point never hands the replay to a helper — nothing to " +
                "check disposal on (found: ${launched.map { it.name }})",
            launched.isNotEmpty(),
        )
        for (m in launched) {
            val guard = Regex("""if\s*\(\s*isDisposed\s*\)\s*return""").find(m.body)
            val work = m.body.indexOf("applyViewportToAllTabs(")
            assertTrue(
                "`${m.name}` replays without consulting `isDisposed` — a disposed pool " +
                    "owns no tabs and its scopes are cancelled (dv24-B4 B-07)",
                guard != null && guard.range.first < work,
            )
        }
    }

    /** Each mutation removes exactly the thing one of the three criteria pins;
     *  a mutation that stays green means that criterion is toothless. */
    @Test
    fun `the viewport replay criteria have teeth`() {
        val src = readRepoFile(TabPoolPath)
        viewportReplayAssertions(src) // sanity: green on the shipped source

        val arms = listOf(
            "drop the replay inside the collection point" to
                src.replace(
                    """        _sessionViewportHeight.value = 0
        replayViewportToLiveTabs()""",
                    "        _sessionViewportHeight.value = 0",
                ),
            "a fourth path clears the flows itself" to
                src.replace(
                    "    fun releaseAllTabs() {",
                    "    fun releaseAllTabs() {\n        _sessionViewportWidth.value = 0",
                ),
            "the binding stops dropping the override" to
                src.replaceFirst("            clearSessionViewportOverride()\n", ""),
            "the restore stops replaying" to
                src.replaceFirst("            replayViewportToLiveTabs()\n", ""),
            "the restore gates the replay on hasOverride again" to
                src.replace(
                    "            replayViewportToLiveTabs()\n",
                    "            if (restoredViewport.hasOverride) {\n" +
                        "                replayViewportToLiveTabs()\n            }\n",
                ),
        )

        var caught = 0
        for ((label, mutated) in arms) {
            if (mutated == src) throw AssertionError("mutation `$label` changed nothing (anchor drift)")
            var failed = false
            try {
                viewportReplayAssertions(mutated)
            } catch (e: AssertionError) {
                failed = true
            }
            if (failed) caught++
        }
        assertEquals(
            "the mutation guard caught only $caught of ${arms.size} removals — some " +
                "viewport-replay criterion is toothless",
            arms.size,
            caught,
        )
    }

    private fun viewportReplayAssertions(src: String) {
        val masked = stripComments(src)
        val members = membersOf(masked)
        val replaying = replayBearingMembers(members)
        assertTrue("no replay-bearing member", replaying.isNotEmpty())
        val writers = members.filter { valueWrite.containsMatchIn(it.body) }
        assertTrue("no write sites", writers.isNotEmpty())
        for (m in writers) {
            val lastWrite = valueWrite.findAll(m.body).map { it.range.first }.maxOrNull()!!
            val lastReplay = replayPositions(m.body, replaying).maxOrNull() ?: -1
            assertTrue("`${m.header.trim()}` writes without replaying", lastReplay > lastWrite)
            assertFalse(
                "`${m.name}` conditions the session viewport on `hasOverride` — the " +
                    "0 × 0 direction is the leak (dv29-B4 B-04)",
                m.body.contains("hasOverride"),
            )
        }
        val cz = members.filter { zeroWrite.containsMatchIn(it.body) && !it.suspends }
        assertEquals("not exactly one zero-clearing member", 1, cz.size)
        val point = cz.single()
        assertTrue("collection point not private", point.header.contains("private fun "))
        assertTrue(
            "collection point does not zero both flows",
            point.body.contains("_sessionViewportWidth.value = 0") &&
                point.body.contains("_sessionViewportHeight.value = 0"),
        )
        assertTrue(
            "collection point does not replay",
            replayPositions(point.body, replaying).isNotEmpty(),
        )
        // the session-repointing entry points must still reach the clear
        val repointers = members.filter { m ->
            Regex("""this\.sessionId\s*=\s*sessionId""").containsMatchIn(m.body)
        }
        assertTrue("no session-repointing member found", repointers.isNotEmpty())
        for (m in repointers) {
            assertTrue(
                "`${m.header.trim()}` re-points the pool without reaching the viewport " +
                    "clear — the incoming session inherits the previous size",
                reachesZeroClear(m.name, members, cz.map { it.name }.toSet(), 3),
            )
        }
    }

    // ── [dv14-B2] savedURLs semantics + pool teardown ordering ──────────

    @Test
    fun `a blank evicted url is not remembered`() {
        assertEquals("blank urls must not enter savedURLs", null, rememberEvictedUrl(""))
    }

    @Test
    fun `a real evicted url is remembered verbatim`() {
        assertEquals(
            "the evicted tab's url must survive verbatim",
            "https://example.com/a?b=1",
            rememberEvictedUrl("https://example.com/a?b=1"),
        )
    }

    @Test
    fun `dispose must not re-persist the pool state`() {
        val body = memberSlice(readRepoFile(TabPoolPath), "fun dispose(")
        assertFalse(
            "dispose() must not call saveState() — an unconditional write here " +
                "resurrects a just-deleted session's browser_tabs/<sid>.json:\n$body",
            body.contains("saveState("),
        )
        assertTrue(
            "dispose() must still release the tabs it owns:\n$body",
            body.contains("destroyTab("),
        )
    }

    @Test
    fun `trim and idle eviction store the url after destroyTab`() {
        val src = readRepoFile(TabPoolPath)
        for (fn in listOf("fun onTrimMemory(", "fun evictIdleTabs(")) {
            val body = memberSlice(src, fn)
            val destroy = body.indexOf("destroyTab(")
            val store = Regex("savedURLs\\[[^\\]]*\\]\\s*=").find(body)?.range?.first ?: -1
            assertTrue("$fn no longer calls destroyTab(:\n$body", destroy >= 0)
            assertTrue(
                "$fn no longer stores the evicted tab's url into savedURLs:\n$body",
                store >= 0,
            )
            assertTrue(
                "$fn stores the url BEFORE destroyTab( — destroyTab removes the " +
                    "entry, so the url is silently wiped:\n$body",
                store > destroy,
            )
        }
    }

    @Test
    fun `a deleted session's url is not written back by dispose`() {
        // Model of the two teardown orders, with destroyTab's remove() mirrored:
        // the production order (store after destroy) keeps the evicted url; the
        // pre-fix order (store before destroy) lost it.
        val productionOrder = LinkedHashMap<Int, String>()
        val id = 7
        productionOrder[id] = "https://kept.example"
        productionOrder.remove(id) // destroyTab's savedURLs.remove(tab.id)
        rememberEvictedUrl("https://kept.example")?.let { productionOrder[id] = it }
        assertEquals(
            "the evicted tab's url must still be known after teardown",
            "https://kept.example",
            productionOrder[id],
        )

        val preFixOrder = LinkedHashMap<Int, String>()
        preFixOrder[id] = "https://kept.example"
        rememberEvictedUrl("https://kept.example")?.let { preFixOrder[id] = it }
        preFixOrder.remove(id) // destroyTab runs after the store in the pre-fix shape
        assertEquals(
            "the pre-fix order loses the url — this arm documents the defect",
            null,
            preFixOrder[id],
        )
    }

    @Test
    fun `saveState merge keeps evicted urls and skips blank live urls`() {
        val merged = mergedTabUrls(
            listOf(Pair(1, "https://live.example"), Pair(2, ""), Pair(3, "https://shared.example")),
            mapOf(4 to "https://evicted.example", 3 to "https://evicted-wins.example"),
        )
        assertEquals(
            "live tab urls are kept, blanks dropped, evicted entries retained — " +
                "a later entry wins on id conflict",
            mapOf(
                1 to "https://live.example",
                3 to "https://evicted-wins.example",
                4 to "https://evicted.example",
            ),
            merged,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    @Test
    fun `the borrow latch is set by the context-only bind and cleared by a full bind`() {
        val src = readRepoFile(TabPoolPath)
        val contextOnly = memberSlice(src, "fun bindSessionContextOnly(")
        val full = memberSlice(src, "fun setSession(")
        assertTrue(
            "a context-only bind must latch the borrow:\n$contextOnly",
            contextOnly.contains("isContextOnlySession = true"),
        )
        assertTrue(
            "a full session bind must take ownership back:\n$full",
            full.contains("isContextOnlySession = false"),
        )
        assertFalse(
            "a full session bind must not latch the borrow:\n$full",
            full.contains("isContextOnlySession = true"),
        )
    }

    @Test
    fun `a context-only pool never persists into the borrowed session's tab file`() {
        val body = stripComments(memberSlice(readRepoFile(TabPoolPath), "private fun saveState("))
        val guard = body.indexOf("if (isContextOnlySession) return")
        assertTrue(
            "saveState must refuse to persist for a context-only bind:\n$body",
            guard >= 0,
        )
        // The guard has to precede the tab-file access, not merely exist: a
        // check placed after `File(context.filesDir, ...)` would still let the
        // borrowed session's file be created/overwritten on the way in.
        // Comments are stripped first: this function's own KDoc names
        // `filesDir/browser_tabs/<sid>.json`, and a comment cannot count as a
        // touch (the same comment trap the gates guard against).
        val firstTouch = listOf(
            body.indexOf("browser_tabs"),
            body.indexOf("getSharedPreferences"),
        ).filter { it >= 0 }.minOrNull() ?: -1
        assertTrue(
            "the borrow guard must run before the tab file is touched " +
                "(guard@$guard touch@$firstTouch):\n$body",
            firstTouch >= 0 && guard < firstTouch,
        )
    }

    /**
     * Blank out comments while keeping every line (and therefore every
     * character offset) intact. A mention inside a comment must never satisfy
     * a criterion — nor be mistaken for one.
     */
    private fun stripComments(src: String): String {        val out = StringBuilder(src)
        var i = 0
        var inLine = false
        var inBlock = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> {
                    if (c == '\n') inLine = false else out.setCharAt(i, ' ')
                }
                inBlock -> {
                    if (c == '*' && next == '/') {
                        out.setCharAt(i, ' ')
                        out.setCharAt(i + 1, ' ')
                        inBlock = false
                        i++
                    } else if (c != '\n') out.setCharAt(i, ' ')
                }
                c == '/' && next == '/' -> {
                    out.setCharAt(i, ' ')
                    out.setCharAt(i + 1, ' ')
                    inLine = true
                    i++
                }
                c == '/' && next == '*' -> {
                    out.setCharAt(i, ' ')
                    out.setCharAt(i + 1, ' ')
                    inBlock = true
                    i++
                }
            }
            i++
        }
        return out.toString()
    }

    // ── [dv29-B4 B-04] member/call-graph helpers for the viewport criteria ──

    /** One member of the pool class: its header line, its name and its slice
     *  (from the header to the next member header, so comments of the next
     *  member are included but blanked by [stripComments]). */
    private data class Member(val header: String, val name: String, val body: String) {
        /** A suspend member may await the replay itself; a plain one cannot. */
        val suspends: Boolean get() = header.contains("suspend fun ")
    }

    /** `fun name(` at class-member indentation (4 spaces). Handles the
     *  visibility / `suspend` prefixes and a generic parameter list; extension
     *  receivers keep the last identifier before `(` as the name. */
    private val funHeader = Regex(
        """^ {4}(?:(?:private|internal|public|protected)\s+)?(?:suspend\s+)?""" +
            """fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(""",
    )

    private val valueWrite = Regex("""_sessionViewport(?:Width|Height)\s*\.\s*value\s*=""")
    private val zeroWrite = Regex("""_sessionViewport(?:Width|Height)\s*\.\s*value\s*=\s*0\b""")

    private fun membersOf(src: String): List<Member> {
        val lines = src.lines()
        val starts = lines.indices.filter { i -> funHeader.containsMatchIn(lines[i]) }
        return starts.mapIndexed { idx, start ->
            val header = lines[start]
            val end = if (idx + 1 < starts.size) starts[idx + 1] else lines.size
            Member(
                header = header,
                name = funHeader.find(header)!!.groupValues[1],
                body = lines.subList(start, end).joinToString("\n"),
            )
        }
    }

    private fun calls(body: String, name: String): Boolean =
        Regex("""\b""" + Regex.escape(name) + """\s*\(""").containsMatchIn(body)

    /** Members whose body re-applies the resolved viewport: the direct ones
     *  (`applyViewportToAllTabs(`) plus the one hop that only delegates to one
     *  of them (e.g. `replayViewportToLiveTabs`). Derived, never a name list. */
    private fun replayBearingMembers(members: List<Member>): Set<String> {
        val direct = members.filter { it.body.contains("applyViewportToAllTabs(") }.map { it.name }.toSet()
        val indirect = members
            .filter { m -> m.name !in direct && direct.any { n -> calls(m.body, n) } }
            .map { it.name }
            .toSet()
        return direct + indirect
    }

    private fun replayPositions(body: String, replaying: Set<String>): List<Int> =
        replaying.flatMap { n ->
            Regex("""\b""" + Regex.escape(n) + """\s*\(""").findAll(body).map { it.range.first }
        }

    /** Breadth-first walk over the pool's own call graph (depth-bounded): does
     *  [start] reach a member that drops the session viewport? */
    private fun reachesZeroClear(
        start: String,
        members: List<Member>,
        targets: Set<String>,
        maxDepth: Int,
    ): Boolean {
        val byName = members.associateBy { it.name }
        var frontier = setOf(start)
        val seen = mutableSetOf(start)
        repeat(maxDepth) {
            val next = mutableSetOf<String>()
            for (n in frontier) {
                val body = byName[n]?.body ?: continue
                if (targets.any { t -> t != n && calls(body, t) }) return true
                for (m in members) {
                    if (m.name !in seen && calls(body, m.name)) next.add(m.name)
                }
            }
            seen += next
            if (next.isEmpty()) return false
            frontier = next
        }
        return false
    }

    private fun readRepoFile(relative: String): String {
        // Gradle (CI) sets user.dir to the module dir; a sandbox JVM run may
        // start anywhere under the repo — walk up instead of assuming a depth.
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Lines of the member declared at [marker], up to the next sibling member. */
    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") ||
                t.startsWith("    fun ") || t.startsWith("    internal fun ") ||
                t.startsWith("    private fun ") ||
                t.startsWith("    internal val ") || t.startsWith("    private val ") ||
                t.startsWith("    // --")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        private const val TabPoolPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
        private const val OffloadHandlerPath =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/BrowserUseOffloadHandler.kt"
    }
}
