package com.rikkaminis.app.share

import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.repository.ChatRepository
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real streamTranscript writes its JSON/text files; only the DAO is synthetic. */
class ChatExporterPublicProjectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val good = """[{"type":"text","value":"公开 hello"},{"type":"anthropicReplayV1","value":{"signature":"SIG_SYNTH_D05","data":"DATA_SYNTH_D05"}},{"type":"toolUse","value":{"toolUseId":"c","name":"file_read","input":"{}"}},{"type":"toolResult","value":{"toolUseId":"c","output":"ok"}},{"type":"futurePart","value":"keep"}]"""
    private val bad = """[{"type":"anthropicReplayV1","value":{"data":"DATA_SYNTH_D05"}"""

    private fun repository(rows: List<MessageEntity>): ChatRepository {
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, args ->
            when (method.name) {
                "messageCountForSession" -> rows.size
                "loadMessagesPage" -> rows.drop(args!![1] as Int).take(args[2] as Int)
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ChatDao
        return ChatRepository(dao)
    }

    private suspend fun render(raws: List<String>, isJson: Boolean): String {
        val rows = raws.mapIndexed { index, raw -> MessageEntity(
            id = "m$index", sessionId = "s", role = "assistant", partsJson = raw,
            createdAt = 1L + index, sortOrder = index,
        ) }
        val session = ChatSessionEntity(id = "s", modelId = "m", title = "public", createdAt = 1L, updatedAt = 1L)
        val out = File(tmp.root, if (isJson) "messages.json" else "messages.txt")
        val method = ChatExporter::class.java.declaredMethods.single { it.name == "streamTranscript" }
        method.isAccessible = true
        val summary = suspendCoroutineUninterceptedOrReturn<ChatExporter.Summary> { continuation ->
            method.invoke(ChatExporter, repository(rows), session, isJson, out, continuation)
        }
        assertEquals(raws.size, summary.messageCount)
        // The same production ZIP writer used by exportToZip; no FileProvider
        // or Android Context is involved in the content-boundary fixture.
        val entryWriter = ChatExporter::class.java.getDeclaredMethod(
            "zipFileEntry", java.util.zip.ZipOutputStream::class.java, String::class.java, File::class.java,
        ).apply { isAccessible = true }
        val archive = File(tmp.root, "transcript.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            entryWriter.invoke(ChatExporter, zip, out.name, out)
        }
        return java.util.zip.ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry(out.name)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    @Test fun zipJsonTranscriptStripsNestedPartsAndPreservesTools() = runBlocking {
        val rendered = render(listOf(good, bad, "ordinary broken ["), true)
        assertFalse(rendered.contains("SIG_SYNTH_D05"))
        assertFalse(rendered.contains("DATA_SYNTH_D05"))
        val rows = JSONArray(rendered)
        val parts = JSONArray(rows.getJSONObject(0).getString("content"))
        assertEquals(4, parts.length())
        assertEquals("公开 hello", parts.getJSONObject(0).getString("value"))
        assertEquals("file_read", parts.getJSONObject(1).getJSONObject("value").getString("name"))
        assertEquals("ok", parts.getJSONObject(2).getJSONObject("value").getString("output"))
        assertEquals("futurePart", parts.getJSONObject(3).getString("type"))
        assertEquals("[]", rows.getJSONObject(1).getString("content"))
        assertEquals("ordinary broken [", rows.getJSONObject(2).getString("content"))
    }

    @Test fun textTranscriptNeverEchoesMalformedPrivatePayload() = runBlocking {
        val rendered = render(listOf(good, bad, "ordinary broken ["), false)
        assertFalse(rendered.contains("SIG_SYNTH_D05"))
        assertFalse(rendered.contains("DATA_SYNTH_D05"))
        assertTrue(rendered.contains("公开 hello"))
        assertTrue(rendered.contains("ordinary broken ["))
    }

    @Test fun quotedUserExampleRemainsPublicAndLargeRowsStayBounded() = runBlocking {
        val text = "用户提到 anthropicReplayV1 " + "日".repeat(160_000)
        val raw = JSONArray().put(org.json.JSONObject().put("type", "text").put("value", text)).toString()
        val rendered = render(listOf(raw), true)
        assertEquals(text, JSONArray(JSONArray(rendered).getJSONObject(0).getString("content")).getJSONObject(0).getString("value"))
        assertEquals("[]", JSONArray(render(listOf("[".repeat(100)), true)).getJSONObject(0).getString("content"))
    }
}
