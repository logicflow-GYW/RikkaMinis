package com.rikkaminis.app.tools

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Real FileWriteTool/FileEditTool with only Android path/storage leaves stubbed. */
class FileToolPrivacyTest {
    private val context = object : Context() {}
    private fun write(path: File, content: String, append: Boolean = false) = FileWriteTool.execute(JSONObject(mapOf(
        "tool_title" to "privacy probe", "path" to path.absolutePath, "content" to content, "append" to append)).toString(), "probe", context)
    private fun edit(path: File, old: String, new: String) = FileEditTool.execute(JSONObject(mapOf(
        "tool_title" to "privacy probe", "path" to path.absolutePath, "old_string" to old, "new_string" to new)).toString(), "probe", context)
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    @Test fun redactedWholeCopyCannotEraseCredentialAndNormalLocalEditWorks() {
        val file = File.createTempFile("minis-write-privacy-", ".txt")
        try {
            file.writeText("API_KEY=opaqueCanary9283\nTIMEOUT=30")
            val before = hash(file)
            val refused = write(file, "API_KEY=[MINIS_REDACTED]\nTIMEOUT=30")
            assertFalse(refused.success)
            assertEquals(before, hash(file))
            val edited = edit(file, "TIMEOUT=30", "TIMEOUT=40")
            assertTrue(edited.success)
            assertTrue(file.readText().contains("opaqueCanary9283"))
            assertTrue(file.readText().contains("TIMEOUT=40"))
            val refusedEdit = edit(file, "API_KEY=opaqueCanary9283", "API_KEY=[MINIS_REDACTED]")
            assertFalse(refusedEdit.success)
            assertTrue(file.readText().contains("opaqueCanary9283"))
        } finally { file.delete() }
    }
    @Test fun ordinaryMarkerDocumentAndAppendAreNotBlocked() {
        val file = File.createTempFile("minis-marker-doc-", ".txt")
        try {
            val created = write(file, "documentation [MINIS_REDACTED]")
            assertTrue(created.success)
            val appended = write(file, "\nmore", append = true)
            assertTrue(appended.success)
            assertTrue(file.readText().contains("documentation [MINIS_REDACTED]"))
        } finally { file.delete() }
    }
    @Test fun oversizedExistingFileFailsClosedWithoutChangingBytes() {
        val file = File.createTempFile("minis-large-privacy-", ".txt")
        val payloadBytes = 2 * 1024 * 1024
        try {
            file.outputStream().buffered().use { out ->
                out.write("API_KEY=opaqueCanary9283\n".toByteArray())
                val chunk = ByteArray(8192) { 'x'.code.toByte() }
                var left = payloadBytes + 10
                while (left > 0) { val n = minOf(left, chunk.size); out.write(chunk, 0, n); left -= n }
            }
            val before = hash(file)
            val result = write(file, "API_KEY=[MINIS_REDACTED]")
            assertFalse(result.success)
            assertEquals(before, hash(file))
        } finally { file.delete() }
    }
}
