package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-singleline-paste-newline] Guards [sanitizeSingleLineInput], the
 * fold that every single-line input field routes its text through.
 *
 * Why this exists: `singleLine = true` only clips the DISPLAY (one visible
 * line) and stops the keyboard from inserting newlines — newlines arriving
 * via PASTE survive in the state. The field then shows line 1 while lines
 * 2..n stay invisible ("deleting" the visible text leaves an apparently
 * empty field that still holds `\n`s). The fold is the fix; these tests pin
 * its contract.
 *
 * The full paste pipeline (ClipboardManager -> BasicTextField -> this fold)
 * cannot be exercised on the JVM, so the end-to-end proof is the manual
 * repro: paste a multi-line header/URL/key into a single-line field, verify
 * the whole paste is visible on one line and that clearing it leaves the
 * field genuinely empty.
 */
class SanitizeSingleLineInputTest {

    @Test
    fun `a crlf pair folds to exactly one space`() {
        // Windows-style clipboard text: "\r\n" must NOT become two spaces.
        assertEquals("a b", sanitizeSingleLineInput("a\r\nb"))
    }

    @Test
    fun `lone lf and lone cr each fold to a space`() {
        assertEquals("a b", sanitizeSingleLineInput("a\nb"))
        assertEquals("a b", sanitizeSingleLineInput("a\rb"))
    }

    @Test
    fun `a blank line folds to a space, not a deletion`() {
        // Collapsing to "" would merge the surrounding tokens ("k1" + "k2"
        // -> "k1k2"); a space keeps them separable downstream.
        assertEquals("k1 k2", sanitizeSingleLineInput("k1\n\nk2"))
    }

    @Test
    fun `clean single-line text passes through byte-for-byte`() {
        val clean = "https://llmhost.net/v1?x=1&y=2"
        assertEquals(clean, sanitizeSingleLineInput(clean))
        val key = "sk-A1b2C3d4E5f6G7h8I9j0"
        assertEquals(key, sanitizeSingleLineInput(key))
    }

    @Test
    fun `a pasted header block comes out on one visible line`() {
        val pasted = "User-Agent: antigravity\nX-Goog-Api-Client: google-cloud-sdk vscode_cloudshelleditor/0.1"
        val out = sanitizeSingleLineInput(pasted)
        assertFalse("no newline may survive", out.contains('\n'))
        assertFalse("no carriage return may survive", out.contains('\r'))
        assertEquals(
            "User-Agent: antigravity X-Goog-Api-Client: google-cloud-sdk vscode_cloudshelleditor/0.1",
            out,
        )
    }

    @Test
    fun `no input can put a line separator into a single-line field`() {
        // Property check over the shapes real pastes take.
        val corpus = listOf(
            "a\nb",
            "a\r\nb",
            "a\rb",
            "\n",
            "\r\n",
            "a\n\n\nb",
            "trailing\n",
            "\nleading",
            "a\nb\nc\nd\ne",
        )
        for (input in corpus) {
            val out = sanitizeSingleLineInput(input)
            assertFalse("LF survived: ${input.replace("\n", "\\n").replace("\r", "\\r")}", out.contains('\n'))
            assertFalse("CR survived: ${input.replace("\n", "\\n").replace("\r", "\\r")}", out.contains('\r'))
        }
    }

    @Test
    fun `newline-separated api keys still split into the same tokens downstream`() {
        // KeyRoulette.SPLIT is Regex("[\\s,]+") — spaces and commas are
        // already separators, so folding "\n" to " " keeps multi-key pastes
        // working at the network layer (before this fold they "worked" too,
        // but were invisible in the UI).
        val folded = sanitizeSingleLineInput("k1\nk2,\nk3")
        val tokens = folded.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        assertEquals(listOf("k1", "k2", "k3"), tokens)
        assertTrue(tokens.none { it.contains('\n') || it.contains('\r') })
    }
}
