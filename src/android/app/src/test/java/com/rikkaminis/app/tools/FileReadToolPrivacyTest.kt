package com.rikkaminis.app.tools

import com.rikkaminis.app.data.EnvVarPrivacyStore
import com.rikkaminis.app.data.EnvVarRedactor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Exercises the REAL file_read reader on actual files, not a copied redactor. */
class FileReadToolPrivacyTest {
    private var privacy = true
    @Before fun setup() { privacy = EnvVarPrivacyStore.isEnabled; EnvVarPrivacyStore.setEnabled(true) }
    @After fun cleanup() { EnvVarPrivacyStore.setEnabled(privacy) }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    @Test fun completeSecretLineIsRedactedBeforeTheCharacterCapAndFileIsUnchanged() {
        val file = File.createTempFile("minis-privacy-", ".txt")
        try {
            file.writeText("API_KEY=opaqueCanary9283\r\nTIMEOUT=30")
            val hash = digest(file)
            for (cap in listOf(1, 9, 14, 19, 80)) {
                val out = FileReadTool.readFileText(file, "/probe/config", "head", 1, null, cap)
                assertTrue(out.success)
                assertTrue(out.privacyRedacted)
                assertFalse(out.output.contains("opaque"))
                assertEquals(hash, digest(file))
                assertTrue(out.output.contains("2 lines"))
            }
        } finally { file.delete() }
    }
    @Test fun tailOffsetAndPrivacyOffKeepExistingSemantics() {
        val file = File.createTempFile("minis-tail-privacy-", ".txt")
        try {
            file.writeText("head\npassword=1234\nTIMEOUT=30")
            val tail = FileReadTool.readFileText(file, "/probe/config", "tail", 1, 2, 80)
            assertFalse(tail.output.contains("password=1234"))
            assertTrue(tail.output.contains("showing 2-3 of 3"))
            EnvVarPrivacyStore.setEnabled(false)
            val raw = FileReadTool.readFileText(file, "/probe/config", "head", 2, 1, 80)
            assertTrue(raw.output.contains("password=1234"))
            assertFalse(raw.privacyRedacted)
            assertTrue(EnvVarRedactor.redactForDiagnostics(raw.output).text.contains("MINIS_REDACTED"))
        } finally { file.delete() }
    }
    @Test fun secretCrossingMaxLengthBoundaryIsRemovedBeforeCap() {
        val file = File.createTempFile("minis-boundary-privacy-", ".txt")
        try {
            file.writeText("prefix123 API_KEY=opaqueCanary9283 suffix")
            val out = FileReadTool.readFileText(file, "/probe/config", "head", 1, null, 25)
            assertTrue(out.privacyRedacted)
            assertFalse(out.output.contains("opaque"))
            assertTrue(out.output.contains("prefix123"))
        } finally { file.delete() }
    }
    @Test fun rescanCeilingFailsClosedInsteadOfLeakingUnknownTail() {
        val file = File.createTempFile("minis-rescan-privacy-", ".txt")
        try {
            file.bufferedWriter().use { w -> repeat(MAX_PRIVACY_RESCAN_LINES + 4) { i -> w.append("line$i ordinary\n") } }
            val out = FileReadTool.readFileText(file, "/probe/large", "head", 1, null, 10)
            assertTrue(out.privacyRedacted)
            assertTrue(out.output.contains("incomplete captured tail hidden"))
            EnvVarPrivacyStore.setEnabled(false)
            val raw = FileReadTool.readFileText(file, "/probe/large", "head", 1, null, 10)
            assertFalse(raw.privacyRedacted)
            assertTrue(raw.output.contains("line0 ordi"))
            assertFalse(raw.output.contains("incomplete captured tail hidden"))
        } finally { file.delete() }
    }
    @Test fun resultCopyEqualityAndHashIncludePrivacyFactAndKeepImageBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        val raw = ToolExecutionResult("normal", true, imageData = bytes, timedOut = true, outcomeKnown = false)
        val changed = raw.copy(privacyRedacted = true)
        assertNotEquals(raw, changed)
        assertNotEquals(raw.hashCode(), changed.hashCode())
        assertSame(bytes, changed.imageData)
        assertTrue(changed.timedOut)
        assertFalse(changed.outcomeKnown)
        assertTrue(changed.copy(output = "other").privacyRedacted)
    }
}
