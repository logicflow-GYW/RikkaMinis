package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persisted error is visible content even when a provider emitted no body. */
class ChatTranscriptErrorRecoveryTest {
    private val contextError = "The response stopped because the context window was exhausted."

    private fun row(id: String, parts: String, error: String? = null, order: Int = 0) = MessageEntity(
        id = id,
        sessionId = "cold-error-session",
        role = "assistant",
        partsJson = parts,
        createdAt = 1000L + order,
        sortOrder = order,
        errorInfo = error,
    )

    private fun rebuild(vararg rows: MessageEntity): List<ChatMessage> =
        buildChatMessagesTranscript(parseRows(rows.toList()), File("/tmp"))

    @Test fun emptyTextWithPersistedErrorSurvivesColdRebuild() {
        val message = rebuild(row("empty-text", """[{"type":"text","value":""}]""", contextError)).single()
        assertEquals("empty-text", message.id)
        assertEquals("", message.content)
        assertEquals(contextError, message.error)
        assertEquals(listOf("empty-text"), message.sourceDbIds)
    }

    @Test fun emptyPartsWithPersistedErrorSurvivesColdRebuild() {
        val message = rebuild(row("empty-array", "[]", contextError)).single()
        assertEquals(contextError, message.error)
        assertEquals(listOf("empty-array"), message.sourceDbIds)
    }

    @Test fun noErrorEmptyTextStillDisappears() {
        assertTrue(rebuild(row("empty-text", """[{"type":"text","value":""}]""")).isEmpty())
    }

    @Test fun whitespaceErrorDoesNotInventAnEmptyBubble() {
        assertTrue(rebuild(row("blank-error", "[]", " \n\t ")).isEmpty())
    }

    @Test fun ordinaryEmptyArrayStillDisappears() {
        assertTrue(rebuild(row("empty-array", "[]")).isEmpty())
    }

    @Test fun partialTextAndItsPersistedErrorRemainExact() {
        val message = rebuild(row("partial", """[{"type":"text","value":"partial answer"}]""", contextError)).single()
        assertEquals("partial answer", message.content)
        assertEquals(contextError, message.error)
    }

    @Test fun normalAnswerStillHasNoError() {
        val message = rebuild(row("normal", """[{"type":"text","value":"complete answer"}]""")).single()
        assertEquals("complete answer", message.content)
        assertNull(message.error)
    }

    @Test fun emptyErrorAfterSuccessfulToolKeepsItsOwnRowIdentityWithoutChangingPriorRow() {
        val tool = row("successful-tool", """[{"type":"toolUse","value":{"toolUseId":"call-1","name":"file_read","input":"{}","description":"read"}}]""")
        val result = MessageEntity(
            id = "successful-result",
            sessionId = "cold-error-session",
            role = "user",
            partsJson = """[{"type":"toolResult","value":{"toolUseId":"call-1","name":"file_read","output":"ok","success":true}}]""",
            createdAt = 1001L,
            sortOrder = 1,
        )
        val emptyError = row("context-error", """[{"type":"text","value":""}]""", contextError, 2)
        val message = rebuild(tool, result, emptyError).single()
        assertEquals("context-error", message.id)
        assertEquals(listOf("successful-tool", "context-error"), message.sourceDbIds)
        assertEquals(contextError, message.error)
        assertEquals(ToolBlockStatus.SUCCESS, message.toolBlocks.single().toolStatus)
        assertNull(tool.errorInfo)
        assertNull(result.errorInfo)
    }
}
