package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Exercise the production response writer, not only the ledger's rotate API. */
class OffloadResponseRetentionTest {

    private fun withReplyDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("offload-retention-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `successful replies keep only the newest files on disk`() = withReplyDir { dir ->
        val ledger = OffloadTmpFileLedger(capacity = 3)
        for (n in 1..12) {
            val file = File(dir, ".native-offload-42-$n")
            assertTrue(writeResponseFile(file, "reply-$n\n", ledger).isSuccess)
        }

        assertEquals(3, ledger.size)
        assertEquals(
            listOf(".native-offload-42-10", ".native-offload-42-11", ".native-offload-42-12"),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
        assertEquals("reply-12\n", File(dir, ".native-offload-42-12").readText())
    }

    @Test
    fun `an overflowing failed reply reclaims both the old and partial files`() = withReplyDir { dir ->
        val ledger = OffloadTmpFileLedger(capacity = 3)
        for (n in 1..3) {
            assertTrue(writeResponseFile(File(dir, ".native-offload-42-$n"), "ok", ledger).isSuccess)
        }
        val partial = File(dir, ".native-offload-42-4")
        val result = writeResponseFile(partial, "bad", ledger) { file, _ ->
            file.writeText("partial")
            throw IOException("simulated write failure")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(2, ledger.size)
        assertEquals(
            listOf(".native-offload-42-2", ".native-offload-42-3"),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
    }

    @Test
    fun `replies within capacity keep their exact payload including empty output`() = withReplyDir { dir ->
        val ledger = OffloadTmpFileLedger(capacity = 4)
        val bodies = listOf("", "中文\n\n", "no trailing newline", "last\n")
        bodies.forEachIndexed { index, body ->
            assertTrue(writeResponseFile(File(dir, ".native-offload-42-$index"), body, ledger).isSuccess)
        }
        assertEquals(4, dir.listFiles()!!.size)
        bodies.forEachIndexed { index, body ->
            assertEquals(body, File(dir, ".native-offload-42-$index").readText())
        }
        ledger.clearAll()
        assertEquals(0, dir.listFiles()!!.size)
    }
}
