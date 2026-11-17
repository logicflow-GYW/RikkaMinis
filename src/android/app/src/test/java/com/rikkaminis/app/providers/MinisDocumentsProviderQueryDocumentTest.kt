package com.rikkaminis.app.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-T6 D11 F-09] `queryDocument` must answer with the same id spelling the
 * rest of the provider produces.
 *
 * `DocumentsContract` does not normalize what a client sends: the Files app and
 * third-party clients legitimately address the root as `""`, `" "`, `"/"` or
 * `"//"`, and a document as `"/shared/a.txt"`. `resolveDoc` and `flagsFor` both
 * normalize the id, so the lookup worked — but the returned
 * `COLUMN_DOCUMENT_ID` was the raw string the caller passed, i.e. an id no
 * `queryChildDocuments` result ever contains. Clients key their caches on that
 * column, so one document could show up twice (once per spelling) or fail to
 * match the child it came from.
 *
 * Latent today: the provider is not registered in `AndroidManifest.xml` yet
 * (see the class KDoc), which is exactly why the fix lands before registration
 * rather than after a client bug report.
 *
 * The probe reads the real source and is paired with the pre-fix shape as its
 * negative control; the pure id case is hard-coded.
 */
class MinisDocumentsProviderQueryDocumentTest {

    private val sourcePath = "src/main/java/com/rikkaminis/app/providers/MinisDocumentsProvider.kt"

    @Test
    fun `the document id is normalized like every other id decision`() {
        // Literal expectations, not derived from the helper under test.
        assertEquals("shared/a.txt", MinisDocumentsProvider.normalizeDocId(" /shared/a.txt/ "))
        assertEquals("", MinisDocumentsProvider.normalizeDocId(" / "))
        assertEquals("shared/notes.txt", MinisDocumentsProvider.normalizeDocId("shared/notes.txt"))
    }

    @Test
    fun `queryDocument reports the normalized id`() {
        assertQueryDocumentNormalizes(source())
    }

    private fun assertQueryDocumentNormalizes(body: String) {
        val fn = body.substringAfter("override fun queryDocument(")
            .substringBefore("override fun queryChildDocuments(")
        assertTrue("queryDocument must normalize once, up front", fn.contains("val cleanId = normalizeDocId(documentId)"))
        assertTrue(
            "the resolved file must be looked up through the normalized id",
            fn.contains("val file = resolveDoc(cleanId)"),
        )
        assertTrue(
            "the cursor must report the normalized id",
            fn.contains(".add(Document.COLUMN_DOCUMENT_ID, cleanId)"),
        )
        assertFalse(
            "the raw id must not be reported back",
            fn.contains(".add(Document.COLUMN_DOCUMENT_ID, documentId)"),
        )
        assertFalse(
            "and it must not drive the flags either (flagsFor normalizes internally, but the "
                + "call site is what a future reader copies)",
            fn.contains("flagsFor(documentId, file)"),
        )
    }

    @Test
    fun `the probe rejects the pre-fix queryDocument`() {
        val real = source()
        val preFix = real.replace(
            ".add(Document.COLUMN_DOCUMENT_ID, cleanId)",
            ".add(Document.COLUMN_DOCUMENT_ID, documentId)",
        )
        assertFalse("the mutation must really restore the raw id", preFix == real)
        assertEquals(
            "the pre-fix shape must be rejected",
            true,
            runCatching { assertQueryDocumentNormalizes(preFix) }.isFailure,
        )
    }

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, sourcePath)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$sourcePath not found from user.dir")
    }
}
