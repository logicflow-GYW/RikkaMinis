package com.rikkaminis.app.backup

import org.json.JSONArray
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
    // ------------------------------------------------------------------
    // [FIX-1028 / A-5] 第三种不完整：片在，但片里有行解析不了
    // ------------------------------------------------------------------

    @Test
    fun unparsableLinesAreReportedOnTheirOwn() {
        val notes = ConfigBackup.chatTruncationNotes(tr("unparsableLines" to 3))

        assertEquals(1, notes.size)
        val note = notes.single()
        assertTrue("must name the line count", note.contains("3 unreadable record line(s)"))
        // 第三种形态不能借缺片的文案：缺片=包少了一片，坏行=片在内容坏，
        // 用户能做的事不同（重新导出 vs 接受丢失）。
        assertTrue("a damaged line is not a missing shard", !note.contains("data shard(s) missing"))
        assertTrue(!note.contains("budget-trimmed"))
    }

    @Test
    fun aDamagedLinePlusAHoleIsTwoLines() {
        val notes = ConfigBackup.chatTruncationNotes(
            tr("shardsMissing" to 1, "unparsableLines" to 2),
        )
        assertEquals(2, notes.size)
        assertTrue(notes[0].contains("1 data shard(s) missing"))
        assertTrue(notes[1].contains("2 unreadable record line(s)"))
    }

    @Test
    fun zeroUnparsableLinesStaysSilent() {
        assertEquals(
            emptyList<String>(),
            ConfigBackup.chatTruncationNotes(tr("unparsableLines" to 0)),
        )
    }

    // ------------------------------------------------------------------
    // [FIX-1028 / A-6] 转换层的 notes 进 skipped
    // ------------------------------------------------------------------

    @Test
    fun conversionNotesAreForwardedWithAPrefix() {
        val raw = JSONArray()
            .put("provider \"X\"：没有模型条目，仍按 0 模型导出")
            .put("   ")
            .put("memory \"GLOBAL.md\"：超过上限跳过")

        val lines = ConfigBackup.conversionNotes(raw)

        // 空白项不占一行（否则 skipped 里会出现「conversion: 」这种空壳）。
        assertEquals(2, lines.size)
        assertEquals("conversion: provider \"X\"：没有模型条目，仍按 0 模型导出", lines[0])
        assertEquals("conversion: memory \"GLOBAL.md\"：超过上限跳过", lines[1])
    }

    @Test
    fun absentConversionNotesAreSilent() {
        assertEquals(emptyList<String>(), ConfigBackup.conversionNotes(null))
        assertEquals(emptyList<String>(), ConfigBackup.conversionNotes(JSONArray()))
    }

    // ------------------------------------------------------------------
    // [fix/meta1028-d1 F-04] 第四种不完整：行在，但本机读不出来
    // ------------------------------------------------------------------

    @Test
    fun unreadableMessagesAreReportedAsAReadFailureNotATrim() {
        val notes = ConfigBackup.chatTruncationNotes(tr("messagesUnreadable" to 5))

        assertEquals(1, notes.size)
        val note = notes.single()
        assertTrue("must name the count", note.contains("5 message(s) could not be read"))
        // 与「预算裁剪」必须分开：裁剪是导出方主动取舍（源设备有全量、可再导一次
        // 更大的窗口），读失败是本机 DB 的行读不出来（同一份窗口再导也还是读不出）。
        assertTrue("a read failure is not a budget trim", !note.contains("budget-trimmed"))
        assertTrue(!note.contains("data shard(s) missing"))
    }

    @Test
    fun zeroUnreadableStaysSilent() {
        assertEquals(
            emptyList<String>(),
            ConfigBackup.chatTruncationNotes(tr("messagesUnreadable" to 0)),
        )
    }

    @Test
    fun aTrimAndAReadFailureAreBothReported() {
        val notes = ConfigBackup.chatTruncationNotes(
            tr("messagesDropped" to 4, "messagesUnreadable" to 2),
        )

        assertEquals(2, notes.size)
        assertTrue(notes[0].contains("budget-trimmed at export"))
        assertTrue(notes[1].contains("2 message(s) could not be read"))
    }
}