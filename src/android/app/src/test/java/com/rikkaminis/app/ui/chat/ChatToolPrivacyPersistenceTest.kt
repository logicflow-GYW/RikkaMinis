package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.EnvVarPrivacyStore
import com.rikkaminis.app.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Real persistence methods + real serializers + in-memory Room boundary. */
class ChatToolPrivacyPersistenceTest {
    private var privacy = true
    @Before fun setup() { privacy = EnvVarPrivacyStore.isEnabled; EnvVarPrivacyStore.setEnabled(true) }
    @After fun cleanup() { EnvVarPrivacyStore.setEnabled(privacy) }
    private fun result(text: String) = AgentContentPart.ToolResult("id", "read_probe", text)
    @Test fun realPersistenceStoresSafeCopyInBothPrivacyStates() {
        val vm = ChatViewModel()
        for (enabled in listOf(true, false)) {
            EnvVarPrivacyStore.setEnabled(enabled)
            val raw = result("password=1234")
            runBlocking { vm.persistToolResultMessage(listOf(raw)) }
            val row = vm.chatRepository.rows.values.last()
            assertFalse(row.partsJson.contains("1234"))
            assertTrue(row.partsJson.contains("MINIS_REDACTED"))
            assertEquals("password=1234", raw.content)
        }
    }
    @Test fun realColdReloadRedactsOldRawRowAndDropsOnlyItsReplay() {
        val rawJson = """[{"type":"toolResult","value":{"toolUseId":"one","name":"read_probe","output":"password=1234","success":true}}]"""
        val row = com.rikkaminis.app.data.db.MessageEntity("legacy", "privacy", "user", rawJson, 0L, sortOrder = 0)
        val messages = buildLlmMessagesFromParsed(parseRows(listOf(row)), java.io.File(".")) { _, _ -> null }
        assertFalse((messages.single().contentParts.single() as AgentContentPart.ToolResult).content.contains("1234"))
        assertTrue(row.partsJson.contains("1234")) // fixture bypassed the new writer on purpose
    }
}
