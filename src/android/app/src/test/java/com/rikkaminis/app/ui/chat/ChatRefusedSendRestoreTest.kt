package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [incr1118 D1-F04] A refused send must hand the composer back.
 *
 * The send contract is "ChatInputArea clears the field FIRST, then calls
 * sendMessage" — that is what makes a send feel instant and what every other
 * early return compensates for (`setInputText(text)` for the edit-while-
 * streaming refusal, `pendingExhaustedText` for the Context-Full stash,
 * `enqueuePrompt` for the mid-stream queue). Two branches returned bare:
 * `_isCompacting` and `initialProvider == null`, so the text the user had just
 * typed (and the paste ChatInputArea had already expanded into it) was
 * destroyed in exchange for an inline notice.
 *
 * This is a SHAPE test on purpose: the guards live inside a 4,000-line
 * ViewModel that no JVM test can instantiate (it needs an Android Context, a
 * repository, a provider factory). The assertion is the rule, not a line
 * number: **every early `return` a send can take before it claims the turn must
 * be preceded by something that gives the text back.**
 *
 * The share-arm rule is pinned the same way but as order, because it IS order:
 * `_hasInjectedShareContent` records "this draft came from a system share and
 * can be moved", so it may only be spent by a send that actually starts.
 */
class ChatRefusedSendRestoreTest {

    private fun repoFile(vararg candidates: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            val here: File = dir
            for (rel in candidates) {
                val f = File(here, rel)
                if (f.exists()) return f
            }
            dir = here.parentFile
            hops++
        }
        throw IllegalStateException(
            "repo file not found walking up from ${System.getProperty("user.dir")}",
        )
    }

    private fun chatSrc(name: String): String = repoFile(
        "src/main/java/com/rikkaminis/app/ui/chat/$name",
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/$name",
    ).readText()

    /**
     * `sendMessage`'s body, from the `fun` line to the claim of the turn
     * (`_isStreaming.value = true`). Everything before that claim is a refusal
     * surface: nothing has been persisted and the composer is already empty.
     */
    private fun sendMessageRefusalRegion(): List<String> {
        val src = chatSrc("ChatViewModel.kt")
        val funAt = src.indexOf("fun sendMessage(")
        assertTrue("sendMessage not found", funAt >= 0)
        val claimAt = src.indexOf("_isStreaming.value = true", funAt)
        assertTrue("turn-claim enqueue point not found", claimAt > funAt)
        return src.substring(funAt, claimAt).lines()
    }

    /** Statements that put the user's text back somewhere they can reach it. */
    private val handBack = listOf(
        "setInputText(text)",
        "enqueuePrompt(",
        "pendingExhaustedText =",
    )

    @Test
    fun `no early refusal in sendMessage drops the composer text`() {
        // Comments are noise: several refusals are explained by 15-line
        // rationales, so a raw line window measures prose, not statements.
        val lines = sendMessageRefusalRegion().filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        val offenders = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            if (line.trim() != "return") continue
            val window = lines.subList(maxOf(0, i - 8), i)
            // NOTE: the blank-draft guard needs no exemption — it writes `return`
            // on the same line as its condition, so it never reaches this loop.
            // Exempting its neighbours (the previous version did) silently
            // covered the compaction branch a few statements below it: M5 proved
            // that arm could then lose its `setInputText` and stay green.
            if (window.none { w -> handBack.any { w.contains(it) } }) {
                offenders += "line ${i + 1}: ${window.joinToString(" | ") { it.trim() }}"
            }
        }
        assertTrue(
            "these refusals returned without restoring the composer:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `compaction refusal is one of the paths that restores the text`() {
        val body = sendMessageRefusalRegion().joinToString("\n")
        val at = body.indexOf("if (_isCompacting.value)")
        assertTrue("compaction gate not found", at >= 0)
        val gate = body.substring(at, minOf(body.length, at + 900))
        assertTrue(
            "the compaction branch must call setInputText(text) before returning",
            gate.contains("setInputText(text)"),
        )
    }

    @Test
    fun `missing provider refusal restores the text`() {
        val body = sendMessageRefusalRegion().joinToString("\n")
        val at = body.indexOf("if (initialProvider == null)")
        assertTrue("provider gate not found", at >= 0)
        val gate = body.substring(at, minOf(body.length, at + 400))
        assertTrue(
            "a send refused for lack of a provider must hand the text back",
            gate.contains("setInputText(text)"),
        )
    }

    @Test
    fun `the share arm is spent only after the provider gate`() {
        val body = sendMessageRefusalRegion().joinToString("\n")
        val gateAt = body.indexOf("if (initialProvider == null)")
        val armAt = body.indexOf("_hasInjectedShareContent.value = false")
        assertTrue("provider gate not found", gateAt >= 0)
        assertTrue("share arm clear not found", armAt >= 0)
        assertTrue(
            "clearing the arm above the gate burns it on a send that never starts " +
                "(the 'Move to…' capsule then has nothing to move)",
            armAt > gateAt,
        )
    }
}
