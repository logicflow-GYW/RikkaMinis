package com.rikkaminis.app.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/terminal-clipboard-1114] The Termux client's clipboard callbacks must
 * actually do something.
 *
 * Defect this pins: `TermuxSessionClient.onCopyTextToClipboard` /
 * `onPasteTextFromClipboard` were empty bodies. They are the ONLY route
 * Termux's own selection ActionMode and its OSC 52 escape have into the Android
 * clipboard — verified by disassembling terminal-view-0.118.0.aar and
 * terminal-emulator-0.118.0.aar:
 *
 *   TextSelectionCursorController$1.onActionItemClicked (copy_text)
 *     → TerminalSession.onCopyTextToClipboard(String)
 *     → TerminalSessionClient.onCopyTextToClipboard(this, text)   ← was {}
 *   TextSelectionCursorController$1.onActionItemClicked (paste_text)
 *     → TerminalSession.onPasteTextFromClipboard()
 *     → TerminalSessionClient.onPasteTextFromClipboard(this)      ← was {}
 *
 * The paste menu item's enabled state is decided by
 * ClipboardManager.hasPrimaryClip() inside Termux, so the menu looked live
 * while both branches dropped the action on the floor.
 *
 * Why a source-shape test instead of a real one: the callbacks take a Termux
 * TerminalSession and need a live ClipboardManager, i.e. a device. `androidTest`
 * does not run in CI (the unit-tests job is `./gradlew test`), so an
 * instrumented test here would be a check that never executes — the same class
 * of false protection this batch is fixing. Same approach as
 * LargeContentGuardStreamEndLatchTest / SystemPromptSegmentOrderTest.
 */
class TerminalClipboardWiringTest {

    private val rel = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/TerminalSession.kt"

    @Test
    fun `the copy callback writes the Android clipboard`() {
        val block = braceBlockAfter(stripComments(readRepoFile(rel)), "override fun onCopyTextToClipboard(")
        assertTrue(
            "onCopyTextToClipboard no longer calls writeClipboardText — Termux's " +
                "selection Copy menu and OSC 52 both go through this callback, so " +
                "empty (or clipboard-less) bodies mean the menu silently does nothing",
            block.contains("writeClipboardText(context"),
        )
        assertTrue(
            "onCopyTextToClipboard no longer guards on empty text — an empty clip " +
                "would be written to the system clipboard",
            block.contains("text.isEmpty()"),
        )
    }

    @Test
    fun `the paste callback writes the harvested text into the PTY`() {
        val block = braceBlockAfter(stripComments(readRepoFile(rel)), "override fun onPasteTextFromClipboard(")
        assertTrue(
            "onPasteTextFromClipboard no longer reads the Android clipboard — " +
                "Termux's selection Paste menu is wired to this callback only, so " +
                "an empty body makes the menu do nothing",
            block.contains("readClipboardText(context)"),
        )
        assertTrue(
            "onPasteTextFromClipboard never delivers the text to the session",
            block.contains("emulator.paste(") || block.contains("session.write("),
        )
    }

    @Test
    fun `paste goes through the emulator so control characters are stripped and bracketed paste applies`() {
        // emulator.paste() owns three behaviours we must not reimplement:
        // strips ESC/C1 from the clip (paste cannot inject control sequences),
        // collapses CRLF, and wraps in ESC[200~…ESC[201~ under DECSET 2004.
        val block = braceBlockAfter(stripComments(readRepoFile(rel)), "override fun onPasteTextFromClipboard(")
        assertTrue(
            "paste bypasses TerminalEmulator.paste() — the clip's ESC/C1 " +
                "characters would reach the PTY unfiltered and bracketed-paste " +
                "mode (vim, readline) would not be honoured",
            block.contains("emulator.paste("),
        )
    }

    @Test
    fun `clipboard helpers never throw`() {
        // A failed clipboard read (service missing, or Android 10+'s
        // background-clipboard restriction) must degrade to "nothing happened"
        // exactly like the old no-op, not kill the terminal session.
        //
        // Assertion shape matters here — v1 of this test checked
        // `block.contains("runCatching")`, which stayed green even when the
        // wrapper was swapped out, because the body still carried its own
        // `return@runCatching null` label. A surviving label is not a guard:
        // the block is only protected if the WRAPPER is on the outside.
        //   read  : `… : String? = runCatching {`   (wrapper sits in the signature)
        //   write : `private fun writeClipboardText(…) { runCatching {`  (first statement)
        val src = stripComments(readRepoFile(rel))

        val readSig = signatureRegion(src, "private fun readClipboardText(")
        assertTrue(
            "readClipboardText is no longer wrapped in runCatching — a clipboard " +
                "read failure (service missing / Android 10+ background restriction) " +
                "would propagate into the Termux callback and can take down the " +
                "terminal session. Signature was:\n${readSig.trimIndent()}",
            readSig.contains("runCatching"),
        )

        val writeHead = braceBlockAfter(src, "private fun writeClipboardText(")
            .let { it.substring(1, minOf(it.length, 120)) }
        assertTrue(
            "writeClipboardText's first statement is no longer runCatching — a " +
                "setPrimaryClip failure would propagate into the Termux callback. " +
                "Body started with:\n${writeHead.trimIndent()}",
            writeHead.contains("runCatching"),
        )
    }

    // ── helpers (same shape as LargeContentGuardStreamEndLatchTest) ─────────

    /** Text from [anchor] up to (excluding) the first `{` after it. */
    private fun signatureRegion(src: String, anchor: String): String {
        val at = src.indexOf(anchor)
        if (at < 0) throw AssertionError("anchor not found: $anchor")
        val open = src.indexOf('{', at)
        if (open < 0) throw AssertionError("no block for anchor: $anchor")
        return src.substring(at, open)
    }

    /** Braces block starting at the first `{` after [anchor] in [src]. */
    private fun braceBlockAfter(src: String, anchor: String): String {
        val at = src.indexOf(anchor)
        if (at < 0) throw AssertionError("anchor not found: $anchor")
        val open = src.indexOf('{', at)
        if (open < 0) throw AssertionError("no block for anchor: $anchor")
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\' && i + 1 < src.length) { i += 2; continue }
                if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced block for anchor: $anchor")
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append(c) }
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; i += 2 } else i++
                }
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2 }
                    else {
                        if (c == '"') inString = false
                        i++
                    }
                }
                c == '/' && next == '/' -> { inLine = true; i += 2 }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                c == '"' -> { inString = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (f in listOf(java.io.File(dir, relative), java.io.File(dir, "src/android/app/$relative"))) {
                if (f.exists()) return f.readText()
            }
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
