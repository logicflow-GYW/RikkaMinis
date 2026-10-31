package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [feat/paste-fold-marker] Placement probes for the long-paste folding.
 *
 * Everything that can silently undo this feature is a PLACEMENT question, not a
 * wording one, so the assertions are about order and about which value is read:
 *
 *  1. the fold must sit in `onValueChange` BEFORE the IME-burst gate — the burst
 *     path buffers a snapshot of the edit and flushes it 150 ms later; if the
 *     snapshot were the RAW block, that flush would write it back over the
 *     marker (the one failure the whole feature cannot survive);
 *  2. the fold's `old` must be the field's own TextFieldValue (never the
 *     debounced ViewModel string) and its result must be what gets written back;
 *  3. expansion must live in `ChatViewModel.sendMessage` — the single funnel for
 *     the UI, the debug RPC and the headless runner — so no persistence path can
 *     ship a literal marker;
 *  4. the fail-closed gate must run before anything clears the composer in BOTH
 *     send funnels (button/swipe, and the Enter key);
 *  5. a restored draft must be adopted at the one place a draft comes back.
 *
 * Compose cannot run on the JVM here, so these read source shapes (the same
 * probe style as `ComposerLiveSourceTest`) — the executable half of the feature
 * lives in `PasteTextBufferTest`.
 */
class PasteFoldWiringTest {

    private val inputArea by lazy {
        readSource("src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt")
    }
    private val viewModel by lazy {
        readSource("src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun readSource(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (prefix in listOf("", "src/android/app/", "app/")) {
                val f = File(dir, prefix + relative)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** The `{...}` body that follows the first [marker], brace-matched. */
    private fun blockAfter(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("no brace after $marker", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces after $marker")
    }

    // ── 1 + 2: the fold, and where it sits ───────────────────────────────────

    @Test
    fun `the fold runs inside onValueChange before the IME burst gate`() {
        val body = blockAfter(inputArea, "onValueChange = { tfv ->")
        val fold = body.indexOf("foldLongPasteIfNeeded(")
        val burst = body.indexOf("shouldDebounceImeBurst(")
        val writeBack = body.indexOf("inputFieldValue = tfv")
        assertTrue("onValueChange must fold long pastes", fold >= 0)
        assertTrue("the burst gate must still be there", burst >= 0)
        assertTrue(
            "the fold must run BEFORE the IME-burst gate: the gate buffers a snapshot " +
                "and flushes it 150 ms later, so folding afterwards would let the raw " +
                "block overwrite the marker",
            fold < burst,
        )
        assertTrue("the folded value must be what the field is written back with", writeBack > fold)
    }

    @Test
    fun `the fold reads the field value as old and the raw edit as new`() {
        val body = blockAfter(inputArea, "onValueChange = { tfv ->")
        val fold = body.substring(body.indexOf("foldLongPasteIfNeeded("))
        val call = fold.substring(0, fold.indexOf(')') + 1)
        assertTrue(
            "old must be the field's own TextFieldValue (written on every onValueChange, " +
                "never staler than the debounced VM copy): $call",
            call.contains("old = inputFieldValue"),
        )
        assertTrue(
            "new must be the raw edit — inside `run { }` the binding shadows it, so the " +
                "initializer is the last place the raw value is visible: $call",
            call.contains("new = tfv,"),
        )
        assertTrue(
            "the stash must mint the marker through the ViewModel buffer: $call",
            call.contains("viewModel.stashPastedText("),
        )
        assertTrue(
            "the write-back must use the shadowed (folded) `tfv`, never a raw copy",
            body.contains("inputFieldValue = tfv"),
        )
    }

    @Test
    fun `every downstream mirror is fed the folded value, not the raw edit`() {
        val body = blockAfter(inputArea, "onValueChange = { tfv ->")
        val foldAt = body.indexOf("val tfv = foldLongPasteIfNeeded(")
        assertTrue("the fold's binding must be exactly `val tfv = foldLongPasteIfNeeded(`", foldAt >= 0)
        // The residue gate above the fold is the one legal reader of the raw
        // value (it inspects composition state, which the fold does not touch);
        // everything below it sees the shadowed `tfv`.
        val runAt = body.indexOf("run {")
        assertTrue("the fold must live in a shadowing `run { }` scope", runAt >= 0)
        assertTrue("the fold must be the first thing inside that scope", runAt < foldAt)
        for (mirror in listOf(
            "previousFieldText = inputFieldValue.text",
            "imeBurstBuffer = tfv.text",
            "lastTrueCaretEnd = tfv.selection.end",
            "viewModel.publishPendingComposerText(tfv.text)",
        )) {
            assertTrue("$mirror must still be present", body.contains(mirror))
        }
    }

    // ── 3: expansion at the single send funnel ───────────────────────────────

    @Test
    fun `sendMessage expands before anything else reads the text`() {
        val body = blockAfter(viewModel, "fun sendMessage(rawText: String)")
        assertTrue(
            "expansion must be the FIRST transform — it happens INSIDE the sanitize call, so " +
                "the folded block is sanitized too",
            body.contains("Utf16Sanitizer.sanitize(expandPastedText(rawText))"),
        )
        val expand = body.indexOf("expandPastedText(rawText)")
        assertTrue("sendMessage must expand the folded pastes", expand >= 0)
        val firstUse = body.indexOf("if (_isStreaming.value)")
        assertTrue("the expansion must precede the enqueue branch", expand < firstUse)
    }

    @Test
    fun `the expansion consumes exactly the entries it expanded`() {
        val body = blockAfter(viewModel, "internal fun expandPastedText(raw: String)")
        assertTrue(body.contains("expandPastePlaceholders(raw, buffer)"))
        assertTrue(
            "consumed ids must be dropped from the buffer",
            body.contains("filterNot { it.id in consumed || it.id in detachedIds }"),
        )
        assertTrue(
            "the no-marker and no-detached-block fast paths must not touch the buffer",
            body.contains("if (buffer.isEmpty()) return raw") &&
                body.contains("if (consumed.isEmpty() && detachedIds.isEmpty()) return raw"),
        )
    }

    // ── 4: the fail-closed gate, before every clear ──────────────────────────

    @Test
    fun `both send funnels refuse before clearing the composer`() {
        for ((marker, label) in listOf(
            "val performSendOrEnqueue: () -> Unit = handler@{" to "send/swipe funnel",
            "val performEnterSend: () -> Boolean = handler@{" to "Enter funnel",
        )) {
            val body = blockAfter(inputArea, marker)
            val guard = body.indexOf("pasteSendBlockedNow()")
            val clear = body.indexOf("clearComposerInput()")
            assertTrue("$label must carry the paste guard", guard >= 0)
            assertTrue("$label must still clear the composer on send", clear >= 0)
            assertTrue(
                "$label: the guard must run BEFORE the clear, or a blocked send still " +
                    "wipes the draft the user has to fix",
                guard < clear,
            )
        }
    }

    @Test
    fun `the send button is disabled by the guard and the notice is rendered`() {
        assertTrue(
            "the send button must be gated by the paste guard as well as by content",
            inputArea.contains("val canActivate = hasContent && !pasteSendBlockedNow()"),
        )
        assertTrue(
            "the fail-closed notice must be rendered from the same predicate",
            inputArea.contains("PasteSendBlockedNotice(markers = viewModel.blockingPasteMarkers(inputFieldValue.text))"),
        )
        assertTrue(
            "the guard must be scoped to ORPHAN markers (a hand-typed marker is text and " +
                "must not fail a send)",
            viewModel.contains("fun pasteSendBlockedBy(text: String): Boolean = blockingPasteMarkers(text).isNotEmpty()"),
        )
    }

    // ── 5: restored drafts ───────────────────────────────────────────────────

    @Test
    fun `a restored draft adopts its unexpandable markers and never reuses their ids`() {
        val adopt = blockAfter(viewModel, "private fun adoptRestoredDraftPasteMarkers(restored: String)")
        assertTrue(
            "the adopted literals must come from the pure detector",
            adopt.contains("unexpandedPasteMarkers(restored, _pastedTexts.value)"),
        )
        // The stale-id defence moved with the allocation scheme: ids are handed
        // out from the smallest FREE value, and a literal still on screen counts
        // as used — so a restored orphan can never be given to a fresh paste.
        assertTrue(
            "allocation must reserve every id still mentioned by a literal on screen, or " +
                "a fresh paste would take over a stale marker and expand to the WRONG text",
            viewModel.contains("used += pasteMarkerIdsIn(_inputText.value)"),
        )
        assertTrue(
            "the adopted orphan set itself must be reserved too (draft restored into an " +
                "already-buffered composer)",
            viewModel.contains(
                "used += orphanPasteMarkers.mapNotNullTo(mutableSetOf()) { pasteMarkerId(it) }",
            ),
        )
        val restore = viewModel.indexOf("ComposerDraftStore.restoreText(context, sessionId)")
        val call = viewModel.indexOf("adoptRestoredDraftPasteMarkers(restored)")
        assertTrue("the draft restore point must exist", restore >= 0)
        assertTrue(
            "adoption must happen at the restore point itself (the one place a draft " +
                "comes back from disk)",
            call > restore && call - restore < 700,
        )
    }

    // ── chip row / preview ───────────────────────────────────────────────────

    @Test
    fun `a chip is shown while its marker is in the composer, or always when detached`() {
        assertTrue(
            "inline chips must be filtered by marker presence — that is what keeps a sent / " +
                "cleared composer from leaving an orphan chip without any extra " +
                "bookkeeping on the dozens of outside composer writers. Detached pastes " +
                "(no literal by design) stay listed until the user removes them or sends.",
            inputArea.contains("!it.inlineMarker || inputFieldValue.text.contains(it.placeholder)"),
        )
        val pasteItems = inputArea.indexOf("items(visiblePastes")
        assertTrue("the paste chip items block must exist", pasteItems >= 0)
        // Anchor on OUR chip, not the attachment chip above it in the same row.
        val remove = inputArea.substring(inputArea.indexOf("onRemove = {", pasteItems))
        val window = remove.take(800)
        assertTrue(
            "deleting a chip must strip its marker in the same edit:\n$window",
            window.contains("removePasteMarker(inputFieldValue.text, pasted.id)"),
        )
        assertTrue(
            "…and must go through the composer funnel so a pending burst cannot re-add " +
                "the literal",
            window.contains("commitComposerBody("),
        )
    }

    @Test
    fun `the pure half stays Compose-free so it can be unit tested`() {
        val pure = readSource("src/main/java/com/rikkaminis/app/ui/chat/PastedTextBuffer.kt")
        assertFalse(
            "PastedTextBuffer must not import Compose UI — it is the JVM-testable half",
            pure.contains("androidx.compose.material"),
        )
        assertTrue(
            "it still needs TextFieldValue/TextRange (the fold returns one)",
            pure.contains("androidx.compose.ui.text.input.TextFieldValue"),
        )
        val ui = readSource("src/main/java/com/rikkaminis/app/ui/chat/PastedTextChip.kt")
        assertTrue("the chip/preview half is the Compose file", ui.contains("@Composable"))
    }

    @Test
    fun `ChatScreen is untouched by this feature`() {
        // The parallel IME-guard branch owns ChatScreen.kt; zero intersection is
        // the merge-safety property, so it is asserted rather than assumed.
        val chatScreen = readSource("src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt")
        for (token in listOf("PastedText", "pastedTexts", "foldLongPasteIfNeeded", "PasteSendBlockedNotice")) {
            assertFalse("ChatScreen must not reference $token", chatScreen.contains(token))
        }
        assertNotNull(chatScreen)
        assertEquals(
            "no paste folding may be wired from ChatScreen",
            0,
            Regex("""\bfoldLongPasteIfNeeded\b""").findAll(chatScreen).count(),
        )
    }
}
