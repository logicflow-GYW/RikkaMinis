package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [incr1118 D1-F02 / D1-F06 / D2-F01] Three composer/transcript clears that
 * happened next to the state they should have cleared, instead of including it.
 *
 *  - **D1-F02** — the exit-edit pill called `cancelEdit()` (restores the draft
 *    chips) and THEN the composer's clear funnel, which drops the paste buffer
 *    (audit P1-C). The draft's folded pastes died one statement after they came
 *    back. Fix = order: let the composer go first, then restore.
 *  - **D1-F06** — `clearChat()` wiped messages, markers, attachments, edit
 *    state and the share arm, but not the paste chips: a cleared chat still
 *    showed chips, and the next message carried blocks from the conversation
 *    the user had just deleted. The Context-Full dialog's "Clear Chat" calls
 *    `clearChat()` directly (no ComposerCommand.Clear), so nothing else did it.
 *  - **D2-F01** — `truncateBeforeEdit` is the only rewind path that forgot
 *    `dropCompactMarkerIfRewound` + `resetContextPressureState`; the other three
 *    (`retryFromMessage`, `rerunFromToolBlock`, `switchModelAndRerun`) and
 *    `retryLast` all call them. Editing a message above a compaction anchor
 *    therefore kept a summary of deleted turns in the prompt and a pre-edit
 *    pressure reading that could block or re-compact the edited conversation.
 *
 * The rewind assertion is written as a parity rule over the whole package: **a
 * function that both truncates the DB tail and rebuilds `agentHistory` is a
 * rewind, and every rewind must do both resets.** That is what would have caught
 * the fourth copy, and it catches the fifth.
 */
class ComposerClearWiringTest {

    private fun repoDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            val here: File = dir
            for (rel in listOf(
                "src/main/java/com/rikkaminis/app/ui/chat",
                "src/android/app/src/main/java/com/rikkaminis/app/ui/chat",
            )) {
                val d = File(here, rel)
                if (d.isDirectory) return d
            }
            dir = here.parentFile
            hops++
        }
        throw IllegalStateException("chat package not found from ${System.getProperty("user.dir")}")
    }

    private fun src(name: String): String = File(repoDir(), name).readText()

    /**
     * Body of the member function [name] in [file], sliced by INDENTATION.
     *
     * Brace counting is not usable here: these files are full of string
     * templates and raw strings whose braces are not guaranteed balanced, and a
     * miscount silently yields a body that is too short (a test that passes for
     * the wrong reason). A member function ends at the first non-blank line
     * indented at or left of its own `fun` line.
     */
    private fun funBody(file: String, name: String): String {
        val lines = src(file).lines()
        val decl = Regex("^\\s*(?:internal |private |public |protected )?(?:suspend )?fun (?:[A-Za-z0-9_.]+\\.)?$name\\(")
        val start = lines.indexOfFirst { decl.containsMatchIn(it) }
        if (start < 0) throw AssertionError("fun $name not found in $file")
        val indent = lines[start].takeWhile { it == ' ' }.length
        val out = StringBuilder(lines[start])
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) break
            out.append('\n').append(line)
            i++
        }
        return out.toString()
    }

    /** Member-function names declared at file/class level (4-space indent). */
    private fun memberFuns(file: String): List<String> =
        Regex(
            "^ {0,4}(?:internal |private |public |protected )?(?:suspend )?fun (?:[A-Za-z0-9_.]+\\.)?([A-Za-z0-9_]+)\\(",
            RegexOption.MULTILINE,
        ).findAll(src(file))
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    // ── D1-F02: order ───────────────────────────────────────────────────────

    @Test
    fun `exit-edit hands the draft chips back after the clear funnel`() {
        // The funnel must stay LAST on this path: `chat_ui_interaction_check`'s
        // F4 pins that "every composer exit routes through the clear funnel",
        // and the funnel is what resets the field. So the chips cannot be
        // restored by `cancelEdit()` alone — it runs before the funnel and the
        // funnel drops the paste buffer (audit P1-C). The draft snapshot is
        // therefore read first and written back afterwards.
        val input = src("ChatInputArea.kt")
        val cancelAt = input.indexOf("viewModel.cancelEdit()")
        assertTrue("exit-edit cancelEdit call not found", cancelAt >= 0)
        val blockAt = input.lastIndexOf("clickable {", cancelAt)
        assertTrue("exit-edit clickable block not found", blockAt >= 0)
        val blockEnd = input.indexOf("},", cancelAt).let { if (it < 0) input.length else it }
        val block = input.substring(blockAt, blockEnd)

        val peekAt = block.indexOf("peekPreEditPastes()")
        val clearAt = block.indexOf("clearComposerInput()")
        val restoreAt = block.indexOf("restoreDraftPastesAfterClear(")
        assertTrue("draft chips are not remembered before the funnel", peekAt in 0 until clearAt)
        assertTrue("the exit no longer routes through the clear funnel", clearAt in 0 until restoreAt)
        assertTrue(
            "the chips must come back AFTER clearComposerInput() (which drops the buffer), " +
                "otherwise they are wiped again",
            restoreAt > clearAt,
        )
    }

    // ── D1-F06: clearChat includes the chips ────────────────────────────────

    @Test
    fun `clearChat drops the paste chips`() {
        val body = funBody("ChatViewModel.kt", "clearChat")
        assertTrue(
            "a cleared chat must not keep folded pastes for the next message to swallow",
            body.contains("clearPastedText()"),
        )
        assertTrue(
            "the edit snapshot must be gone too (a stale cancelEdit would resurrect it)",
            body.contains("_preEditPastes.value = null"),
        )
    }

    // ── D2-F01: rewind parity ───────────────────────────────────────────────

    private val rewindResets = listOf("dropCompactMarkerIfRewound(", "resetContextPressureState()")

    @Test
    fun `truncateBeforeEdit resets the marker and the pressure reading`() {
        val body = funBody("ChatQueueInterruption.kt", "truncateBeforeEdit")
        for (needle in rewindResets) {
            assertTrue("truncateBeforeEdit is missing $needle", body.contains(needle))
        }
    }

    @Test
    fun `truncateBeforeEdit resets AFTER the history rebuild`() {
        // `dropCompactMarkerIfRewound` derives "is this marker still anchored"
        // from agentHistory. Run it before the rebuild and the deleted rows are
        // still live, so an orphaned marker never looks orphaned.
        val body = funBody("ChatQueueInterruption.kt", "truncateBeforeEdit")
        val rebuildAt = body.indexOf("for (entity in remaining)")
        assertTrue("history rebuild not found", rebuildAt >= 0)
        for (needle in rewindResets) {
            assertTrue(
                "$needle must run after the agentHistory rebuild",
                body.indexOf(needle) > rebuildAt,
            )
        }
    }

    @Test
    fun `every rewind path in the package resets the marker and the pressure`() {
        val offenders = mutableListOf<String>()
        val checked = mutableListOf<String>()
        for (file in repoDir().listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()) {
            for (name in memberFuns(file.name)) {
                val body = runCatching { funBody(file.name, name) }.getOrNull() ?: continue
                val isRewind = body.contains("deleteMessagesAfter(") &&
                    body.contains("agentHistory.clear()")
                if (!isRewind) continue
                checked += "${file.name}:$name"
                val missing = rewindResets.filterNot { body.contains(it) }
                if (missing.isNotEmpty()) offenders += "${file.name}:$name missing $missing"
            }
        }
        assertTrue(
            "the rewind scan found no rewind path at all — the rule is not being applied " +
                "(funBody slice or signature drift)",
            checked.size >= 4,
        )
        assertTrue(
            "rewind paths that keep a stale compact marker / pressure reading:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
