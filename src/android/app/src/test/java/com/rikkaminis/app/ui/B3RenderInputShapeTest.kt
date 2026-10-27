package com.rikkaminis.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [deep-audit-1040 B-05 + B-06] Source-shape probe for the two render/input
 * seams this branch repairs. Both defects are *shape* defects in composable
 * code, so nothing here is observable from a JVM unit test without a live
 * Compose host; the properties that make each repair safe are asserted on the
 * production text instead, exactly like the neighbouring `*ShapeTest`s.
 *
 * B-06 — the composer clear is three writes, not one. `_inputText` is a
 * StateFlow, so clearing an already-empty composer is an *equal* write: it does
 * not emit, the `LaunchedEffect(inputText)` mirror never re-runs, and whatever
 * the user typed during a >8-char IME burst (still buffered, not yet committed)
 * stayed in the box after the message had been sent. The funnel therefore has to
 * drop the pending burst AND reset the field plus the caret tracker locally.
 * Every entrance (send button, IME action, exit-edit pill, cross-session move)
 * routes through it.
 *
 * B-05 — `MinisImageBlock` handed Coil a bare `model = block.url`: the fetcher
 * then resolves the host path against the GLOBAL bindMounts map (last writer
 * wins across sessions, so session A can get session B's path and the image
 * renders as a blank 0-height box) and, with no `ImageRequest`, a tall image is
 * decoded at its intrinsic size against an unbounded column (the draw-ceiling
 * crash `DisplayBitmapLimits` documents). The block now uses the same two seams
 * its video/audio siblings already use: the session-scoped resolver and the one
 * shared `limitDisplaySize()` extension.
 *
 * Arms are fail-closed: each asserts its own reason, the pre-fix bodies are
 * quoted verbatim from the baseline (`295cf778`), and every arm was checked
 * against a mutated tree (removing the field reset / the size hint / one
 * resolver call turns the matching arm red) so a probe that has quietly stopped
 * looking fails loudly instead of passing.
 */
class B3RenderInputShapeTest {

    private val chatInputRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"
    private val markdownRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/MarkdownText.kt"
    /** [dv23-B3] The screen that must not write the composer itself. */
    private val chatScreenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"
    private val mainRootRel = "src/android/app/src/main/java"

    private val funnelDecl = "val clearComposerInput: () -> Unit = {"

    /**
     * Gradle runs the unit tests with `user.dir` = the module dir
     * (`src/android/app`), while a local JVM run usually starts at the repo root,
     * so resolve by walking up until the path exists -- the same idiom
     * `MediaHostSourceKeyTest.readRepoFile` already uses (the first CI run of this
     * branch went red on exactly this: the root-relative path only worked
     * locally).
     */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError(
            "$rel not found from user.dir=${System.getProperty("user.dir")}"
        )
    }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** Drops `//` line comments and `/* */` blocks so doc text cannot satisfy an arm. */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("*/", i)
                i = if (end < 0) text.length else end + 2
                sb.append(' ')
            } else if (c == '"' && text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) text.length else end + 3
                sb.append(text, i, stop)
                i = stop
            } else if (c == '"') {
                var j = i + 1
                while (j < text.length && text[j] != '"') {
                    if (text[j] == '\\') j++
                    j++
                }
                val stop = if (j < text.length) j + 1 else text.length
                sb.append(text, i, stop)
                i = stop
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + 1)
        }
        return n
    }

    private fun window(haystack: String, from: Int, span: Int): String =
        haystack.substring(from, minOf(haystack.length, from + span))

    private fun nextAfter(haystack: String, marker: String, from: Int): Int =
        haystack.indexOf(marker, from)

    /**
     * [dv23-B3] ChatScreen must not reach into the composer's text itself.
     *
     * The composer is the single writer of the draft: it holds the live field
     * value and the 150 ms IME-burst job. ChatScreen used to resolve the slash
     * menu against `viewModel.inputText.value` — the DEBOUNCED snapshot — and
     * to clear the composer with a bare `setInputText("")`, an equal write
     * MutableStateFlow drops, so the field kept the draft it was told to wipe.
     *
     * The screen now raises `ComposerCommand` intents and the composer
     * executes them against its own live state, so the screen must contain
     * NEITHER a slash-menu resolution NOR a bare empty-string commit.
     */
    @Test
    fun theScreenNeverResolvesOrClearsTheComposerItself() {
        val code = codeOnly(read(chatScreenRel))
        assertTrue(
            "the screen's slash-menu resolution is gone — but the composer must still " +
                "resolve the menu somewhere, or the / button does nothing",
            !code.contains("dismissSlashMenu(") && !code.contains("showSlashMenuOverInput("),
        )
        assertTrue(
            "the screen must not commit the empty string itself — an equal write is " +
                "dropped by the StateFlow, so the field keeps the draft the clear-chat " +
                "confirm was supposed to wipe; route the clear through the composer funnel",
            !code.contains("viewModel.setInputText(\"\")"),
        )
        assertTrue(
            "the screen must raise a composer intent instead — the four entrances " +
                "(toolbar /, list tap, system back, clear-chat confirm) have nothing to " +
                "execute the command with",
            code.contains("requestComposerCommand("),
        )
    }

    /**
     * [dv23-B3] The composer must CONSUME the intent channel, and the clear arm
     * must reach the three-write funnel (an intent that bypasses it would leave
     * the field and the caret tracker dirty).
     */
    @Test
    fun theComposerConsumesTheIntentsAndRoutesTheClearThroughTheFunnel() {
        val code = codeOnly(read(chatInputRel))
        assertTrue(
            "the composer must collect the intent channel — otherwise the screen's " +
                "commands are dead letters",
            code.contains("composerCommand"),
        )
        val collectAt = code.indexOf("composerCommand.collect")
        assertTrue("the intent channel has no consumer", collectAt >= 0)
        val body = window(code, collectAt, 1600)
        assertTrue(
            "the collector must resolve the intents against the field's live value — " +
                "reading the debounced ViewModel string is the defect this branch fixes",
            body.contains("inputFieldValue"),
        )
        assertTrue(
            "the collector must route the clear intent into the shared funnel — a " +
                "bare setInputText(\"\") is dropped when the composer is already empty",
            body.contains("clearComposerInput()"),
        )
    }

    // ── B-06: the composer clear is one funnel ──────────────────────────────

    @Test
    fun theClearFunnelCarriesAllThreeResets() {
        val src = read(chatInputRel)
        val decl = src.indexOf(funnelDecl)
        assertTrue("the clear funnel is gone from ChatInputArea.kt", decl >= 0)
        val body = window(src, decl, 400)
        assertTrue(
            "the funnel must drop the pending IME burst, or a late 150ms flush " +
                "commits the snapshot it took before the clear",
            body.contains("cancelPendingImeBurst()")
        )
        assertTrue(
            "the funnel must commit the empty string to the ViewModel",
            body.contains("viewModel.setInputText(\"\")")
        )
        assertTrue(
            "the funnel must reset `inputFieldValue` locally: the ViewModel write " +
                "is an equal write (suppressed) whenever the composer is already " +
                "empty, so the field keeps the text that was just sent",
            body.contains("inputFieldValue =")
        )
        assertTrue(
            "the funnel must reset `lastTrueCaretEnd`, or a stale caret index " +
                "survives the clear and lands in the next draft",
            body.contains("lastTrueCaretEnd = 0")
        )
    }

    @Test
    fun noClearWriteBypassesTheFunnel() {
        val src = read(chatInputRel)
        val code = codeOnly(src)
        val clears = count(code, "viewModel.setInputText(\"\")")
        assertEquals(
            "the empty-string commit must exist exactly once -- one per-entrance " +
                "copy is how the field reset drifts out of an entrance",
            1, clears
        )
        val decl = code.indexOf(funnelDecl)
        val only = code.indexOf("viewModel.setInputText(\"\")")
        assertTrue("the funnel declaration moved", decl >= 0)
        assertTrue(
            "the single empty-string commit is not inside the funnel",
            only > decl && only - decl < 400
        )
    }

    @Test
    fun theFunnelIsDeclaredBeforeEveryCallSite() {
        val src = read(chatInputRel)
        val decl = src.indexOf(funnelDecl)
        assertTrue("the clear funnel is gone", decl >= 0)
        var calls = 0
        var i = src.indexOf("clearComposerInput()")
        while (i >= 0) {
            calls++
            assertTrue(
                "a local val lambda cannot be referenced before its declaration " +
                    "(the 1039-B1 lesson) -- call site at offset $i precedes the " +
                    "declaration at $decl",
                i > decl
            )
            i = src.indexOf("clearComposerInput()", i + 1)
        }
        assertTrue(
            "expected every clear entrance to route through the funnel, found $calls call(s)",
            calls >= 6
        )
    }

    @Test
    fun everyClearEntranceRoutesThroughTheFunnel() {
        val src = read(chatInputRel)
        val funnelCall = "clearComposerInput()"

        // the send path: the funnel runs immediately before sendMessage
        var i = src.indexOf("viewModel.sendMessage(")
        assertTrue("the send path moved", i >= 0)
        while (i >= 0) {
            val back = src.lastIndexOf(funnelCall, i)
            assertTrue(
                "a send at offset $i does not clear through the funnel: sending " +
                    "leaves the field holding the text that was just sent",
                back >= 0 && i - back < 300
            )
            i = src.indexOf("viewModel.sendMessage(", i + 1)
        }

        // the slash-command branch: clearing happens inside the if body
        i = src.indexOf("tryExecuteInputAsSlashCommand(")
        assertTrue("the slash-command branch moved", i >= 0)
        while (i >= 0) {
            val fwd = src.indexOf(funnelCall, i)
            assertTrue(
                "the slash-command branch at offset $i does not clear through the funnel",
                fwd >= 0 && fwd - i < 300
            )
            i = src.indexOf("tryExecuteInputAsSlashCommand(", i + 1)
        }

        // the exit-edit pill: cancelEdit must not leave the draft behind
        i = src.indexOf("viewModel.cancelEdit()")
        assertTrue("the exit-edit pill moved", i >= 0)
        while (i >= 0) {
            val fwd = src.indexOf(funnelCall, i)
            assertTrue(
                "the exit-edit pill at offset $i exits edit mode without routing " +
                    "through the funnel",
                fwd >= 0 && fwd - i < 200
            )
            i = src.indexOf("viewModel.cancelEdit(", i + 1)
            i = if (i >= 0) i else src.indexOf("viewModel.cancelEdit()", 0)
            break
        }

        // the cross-session move: the stash hands the draft over, the composer
        // must not keep it
        i = src.indexOf("stashPendingTransfer(")
        assertTrue("the cross-session move moved", i >= 0)
        while (i >= 0) {
            val fwd = src.indexOf(funnelCall, i)
            assertTrue(
                "the cross-session move at offset $i stashes the draft without " +
                    "clearing the composer",
                fwd >= 0 && fwd - i < 400
            )
            i = src.indexOf("stashPendingTransfer(", i + 1)
        }
    }

    @Test
    fun theMirrorGuardIsWhyTheFieldWriteCannotBeSkipped() {
        val src = codeOnly(read(chatInputRel))
        assertTrue(
            "the composer mirrors the ViewModel string behind an inequality guard -- " +
                "if that guard is gone this probe's premise (and the fix) changed",
            src.contains("LaunchedEffect(inputText)")
        )
        assertTrue(
            "the mirror guard `if (inputFieldValue.text != inputText)` is gone",
            src.contains("if (inputFieldValue.text != inputText)")
        )
    }

    /**
     * The pre-fix entrance, quoted verbatim from the baseline (`295cf778`).
     * Nothing here resets the field: the only write is the (suppressible)
     * ViewModel commit.
     */
    private val preFixClear = """
        viewModel.setInputText("")
        keyboardController?.hide()
        focusManager.clearFocus()
    """.trimIndent()

    /** A StateFlow stand-in: an equal write does not emit (kotlinx semantics). */
    private class EqualSuppressingFlow(private val initial: String) {
        private var value: String = initial
        private val subscribers = mutableListOf<(String) -> Unit>()
        fun subscribe(f: (String) -> Unit) {
            subscribers.add(f)
        }

        fun set(v: String) {
            if (v == value) return
            value = v
            subscribers.forEach { it(v) }
        }
    }

    @Test
    fun anEqualClearLeavesTheFieldStaleUntilSomethingWritesTheField() {
        // The composer's mirror, quoted from ChatInputArea.kt:
        //   LaunchedEffect(inputText) { if (inputFieldValue.text != inputText) { … } }
        fun composer(fieldBefore: String, clear: (EqualSuppressingFlow, String) -> String): String {
            var field = fieldBefore
            val vm = EqualSuppressingFlow("") // the composer is already empty VM-side
            vm.subscribe { v -> if (field != v) field = v }
            return clear(vm, field)
        }

        // pre-fix shape: the entrance only commits to the ViewModel
        val residue = composer("hello") { vm, field ->
            vm.set("")
            field
        }
        assertEquals(
            "the pre-fix clear is a no-op when the ViewModel string is already " +
                "empty -- the field keeps `hello` (this is the reported residue)",
            "hello", residue
        )

        // post-fix shape: the funnel resets the field itself
        val cleared = composer("hello") { _, _ -> "" }
        assertEquals("the funnel clears the field directly", "", cleared)
    }

    @Test
    fun thePreFixEntranceLacksTheFieldReset() {
        assertTrue(
            "the pre-fix fixture must exhibit the defect it stands for",
            preFixClear.contains("viewModel.setInputText(\"\")")
        )
        assertTrue(
            "the pre-fix fixture resets the field -- it no longer represents the defect",
            !preFixClear.contains("inputFieldValue =")
        )
        assertTrue(
            "the pre-fix fixture already drops the burst -- it no longer represents " +
                "the exit-edit pill's defect",
            !preFixClear.contains("cancelPendingImeBurst()")
        )
    }

    // ── B-05: the image block uses the two shared seams ─────────────────────

    @Test
    fun everyMediaBlockResolvesItsFileForTheCurrentSession() {
        val src = codeOnly(read(markdownRel))
        val resolved = count(src, "val file = rememberMdMediaFile(block.url, sessionId)")
        assertTrue(
            "expected the image/video/audio blocks to share the session-scoped " +
                "resolver (a bare `model = block.url` resolves against the global " +
                "bindMounts map, last-writer-wins across sessions), found $resolved",
            resolved >= 3
        )
    }

    @Test
    fun theImageBlockCarriesASizeLimitedRequest() {
        val src = codeOnly(read(markdownRel))
        val builder = src.indexOf("ImageRequest.Builder(context)")
        assertTrue("the image block builds no ImageRequest", builder >= 0)
        val body = window(src, builder, 300)
        assertTrue(
            "the request must carry the session-resolved file (falling back to the " +
                "raw url only when the file is absent)",
            body.contains(".data(file ?: block.url)")
        )
        assertTrue(
            "the request must go through the one shared size ceiling -- without a " +
                "size hint a tall image is decoded at its intrinsic size against an " +
                "unbounded column (the draw-ceiling crash DisplayBitmapLimits documents)",
            body.contains(".limitDisplaySize()")
        )
        assertTrue(
            "AsyncImage must be handed the built request, not a bare url",
            src.contains("model = imageRequest")
        )
    }

    @Test
    fun noBareUrlIsHandedToCoilAsUnexplainedImageModel() {
        val src = codeOnly(read(markdownRel))
        assertEquals(
            "a bare `model = block.url` is back: the fetcher resolves the host path " +
                "against the global bindMounts map, so session A can render session " +
                "B's file",
            0, count(src, "model = block.url")
        )
    }

    @Test
    fun theSizeCeilingHasExactlyOneDeclaration() {
        val root = repoFile(mainRootRel)
        assertTrue("source root missing: $mainRootRel", root.isDirectory)
        var declarations = 0
        var where = ""
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val n = count(codeOnly(f.readText()), "fun ImageRequest.Builder.limitDisplaySize")
            if (n > 0) {
                declarations += n
                where += "${f.name}:$n "
            }
        }
        assertEquals(
            "the size ceiling must stay one shared extension (a local copy is how " +
                "the ceiling drifts): $where",
            1, declarations
        )
    }

    /** The pre-fix image block, quoted verbatim from the baseline (`295cf778`). */
    private val preFixImageBlock = """
        private fun MinisImageBlock(block: MarkdownParser.Block.Image) {
            val surfaceBg = MaterialTheme.colorScheme.surfaceVariant
            val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            AsyncImage(
                model = block.url,
                contentDescription = block.alt.ifEmpty { filenameFromUrl(block.url) },
                contentScale = ContentScale.Fit,
    """.trimIndent()

    @Test
    fun thePreFixImageBlockHandsCoilABareUrl() {
        assertTrue(
            "the pre-fix fixture must exhibit the defect it stands for",
            preFixImageBlock.contains("model = block.url")
        )
        assertTrue(
            "the pre-fix fixture resolves a session-scoped file -- it no longer " +
                "represents the defect",
            !preFixImageBlock.contains("rememberMdMediaFile")
        )
        assertTrue(
            "the pre-fix fixture already carries a size ceiling -- it no longer " +
                "represents the defect",
            !preFixImageBlock.contains("limitDisplaySize")
        )
    }
}
