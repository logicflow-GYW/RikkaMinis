package com.rikkaminis.app.backup

import com.rikkaminis.app.data.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real encoder -> decoder -> same-name merge; literal expectations. */
class BackupIntegrityConfigTest {
    private fun local() = ModelGroup(id = "local", name = "work", defaultThinkingLevel = ThinkingLevel.HIGH,
        contextLimitTokens = 64000, lastContextLimitTokens = 32000)

    @Test fun productionExportNullClearsSameNameOverridesWithoutWarning() {
        val payload = encodeBackupGroup(ModelGroup(id = "src", name = "work"))
        for (key in listOf("defaultThinkingLevel", "contextLimitTokens", "lastContextLimitTokens")) {
            assertTrue(key, payload.has(key)); assertTrue(key, payload.isNull(key))
        }
        val warnings = mutableListOf<String>()
        val merged = applyGroupRunConfig(local(), readGroupRunConfig(payload) { warnings.add(it) })
        assertNull(merged.defaultThinkingLevel); assertNull(merged.contextLimitTokens); assertNull(merged.lastContextLimitTokens)
        assertEquals("local", merged.id); assertTrue(warnings.isEmpty())
    }
    @Test fun legacyMissingOverridesKeepLocalValues() {
        val merged = applyGroupRunConfig(local(), readGroupRunConfig(JSONObject().put("name", "work")) { fail(it) })
        assertEquals(ThinkingLevel.HIGH, merged.defaultThinkingLevel)
        assertEquals(64000, merged.contextLimitTokens); assertEquals(32000, merged.lastContextLimitTokens)
    }
    @Test fun productionExportValuesRestoreAllOverrides() {
        val src = local().apply { defaultThinkingLevel = ThinkingLevel.MEDIUM; contextLimitTokens = 128000; lastContextLimitTokens = 96000 }
        val merged = applyGroupRunConfig(local(), readGroupRunConfig(encodeBackupGroup(src)) { fail(it) })
        assertEquals(ThinkingLevel.MEDIUM, merged.defaultThinkingLevel)
        assertEquals(128000, merged.contextLimitTokens); assertEquals(96000, merged.lastContextLimitTokens)
    }
    @Test fun twoHundredMessagesHaveNoLimitOmissions() = runBlocking {
        val tail = readBackupMessageTail(200, 200) { limit ->
            assertEquals(200, limit); (200 downTo 1).toList() to 0
        }
        assertEquals(200, tail.messages.size); assertEquals(0, tail.limitDropped); assertEquals(0, tail.unreadableRows)
    }
    @Test fun twoHundredAndOneMessagesReportOneOlderOmission() = runBlocking {
        val tail = readBackupMessageTail(201, 200) { limit -> (201 downTo 2).take(limit) to 0 }
        assertEquals(200, tail.messages.size); assertEquals(201, tail.messages.first()); assertEquals(2, tail.messages.last())
        assertEquals(1, tail.limitDropped)
        val packed = packChatHistoryWithBudgetLazily(0, 1000000, listOf(JSONObject().put("id", "s")),
            messagesAt = { tail.messages.map { n -> BudgetChatMessage("m$n", "s", "user", "[]", n.toLong(), n) } },
            sanitize = { it }, capReasoning = { it })
        assertEquals(200, packed.messages.size)
        assertEquals(0, packed.messagesDropped); assertEquals(0, packed.sessionsDropped)
        val notes = ConfigBackup.chatTruncationNotes(JSONObject().put("messagesLimitDropped", tail.limitDropped))
        assertEquals(1, notes.size); assertTrue(notes.single().contains("1 older")); assertTrue(notes.single().contains("200"))
        assertFalse(notes.single().contains("budget-trimmed"))
    }
    @Test fun rowLimitBudgetAndUnreadableHaveSeparateCountersAndNotices() = runBlocking {
        val tail = readBackupMessageTail(201, 200) { (201 downTo 3).toList() to 1 }
        assertEquals(1, tail.limitDropped); assertEquals(1, tail.unreadableRows)
        val notes = ConfigBackup.chatTruncationNotes(JSONObject().put("messagesLimitDropped", 1)
            .put("messagesUnreadable", 1).put("messagesDropped", 2).put("sessionsDropped", 0))
        assertEquals(3, notes.size)
        assertEquals(1, notes.count { it.contains("budget-trimmed") && it.contains("2 message(s)") })
        assertEquals(1, notes.count { it.contains("1 older") })
        assertEquals(1, notes.count { it.contains("1 message(s) could not be read") })
    }
}
