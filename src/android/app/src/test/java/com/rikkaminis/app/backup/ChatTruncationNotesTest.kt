package com.rikkaminis.app.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-P1B / RT-S1] `chatTruncated` carries TWO independent kinds of
 * incompleteness, and the import must report them as different things:
 *
 *  - `sessionsDropped` / `messagesDropped` — the exporter trimmed to fit its
 *    byte budget on purpose; the source device still holds everything.
 *  - `shardsMissing` — the source PACKAGE lost a JSONL shard. Those messages
 *    are gone here and the exporter never knew: before P1-B the reader treated
 *    the hole as end-of-sequence, so the tail vanished and the import looked
 *    clean (world W3).
 *
 * Before this writer existed the key was recorded by the converter and read by
 * nobody — "no longer silently dropped" had only been half-fixed. These
 * assertions are dead literals: never derived from the constants under test.
 */
class ChatTruncationNotesTest {

    private fun tr(vararg pairs: Pair<String, Int>): JSONObject =
        JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v) } }

    @Test
    fun missingShardIsReportedAndIsNotCalledBudgetTrimmed() {
        val notes = ConfigBackup.chatTruncationNotes(tr("shardsMissing" to 1))

        assertEquals(1, notes.size)
        val note = notes.single()
        assertTrue("must name the missing shard count", note.contains("1 data shard(s) missing"))
        assertTrue("must say the package was incomplete", note.contains("backup package was incomplete"))
        // The two causes have different user remedies; conflating them makes the
        // trim message ("source device still has it") cover a real hole.
        assertTrue("a hole is not a budget trim", !note.contains("budget-trimmed"))
    }

    @Test
    fun budgetTrimIsReportedAndDoesNotMentionShards() {
        val notes = ConfigBackup.chatTruncationNotes(
            tr("sessionsDropped" to 2, "messagesDropped" to 7),
        )

        assertEquals(1, notes.size)
        val note = notes.single()
        assertTrue(note.contains("budget-trimmed at export"))
        assertTrue(note.contains("7 message(s) and 2 session(s) not carried"))
        assertTrue("a trim is not a hole", !note.contains("data shard(s) missing"))
    }

    @Test
    fun bothKindsAreReportedSeparately() {
        val notes = ConfigBackup.chatTruncationNotes(
            tr("messagesDropped" to 3, "shardsMissing" to 2),
        )

        assertEquals(2, notes.size)
        assertTrue(notes[0].contains("budget-trimmed at export"))
        assertTrue(notes[1].contains("2 data shard(s) missing"))
    }

    @Test
    fun wholePackageProducesNoNotes() {
        assertEquals(emptyList<String>(), ConfigBackup.chatTruncationNotes(JSONObject()))
        // Zero counts are the same thing as absent keys — a package written by
        // an exporter that always stamps the keys must stay silent.
        assertEquals(
            emptyList<String>(),
            ConfigBackup.chatTruncationNotes(
                tr("sessionsDropped" to 0, "messagesDropped" to 0, "shardsMissing" to 0),
            ),
        )
    }
}
