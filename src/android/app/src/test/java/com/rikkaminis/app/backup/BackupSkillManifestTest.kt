package com.rikkaminis.app.backup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Full production .minisbak converter, no Android context or replica. */
class BackupSkillManifestTest {
    private fun converted(missingBlob: Boolean = false, indexHole: Boolean = false,
        skipped: Boolean = false, malformed: Boolean = false): JSONObject {
        val md = "---\nname: integrity\ndescription: test\n---\nbody".toByteArray()
        val sibling = "script".toByteArray()
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val mdHash = hash(md); val fileHash = hash(sibling)
        val first = JSONObject().put("path", "skills/integrity/SKILL.md").put("sha256", mdHash).toString() + "\n"
        val row = JSONObject().put("path", "skills/integrity/scripts/a.py").put("sha256", fileHash)
        if (skipped) row.put("skipped", "unreadable")
        val entries = linkedMapOf("manifest.json" to "{\"created_at\":\"2026-11-20T00:00:00Z\"}".toByteArray(),
            "files.index.jsonl" to (first + if (indexHole) "" else row.toString() + "\n" + if (malformed) "{broken\n" else "").toByteArray(),
            "blobs/${mdHash.take(2)}/$mdHash" to md)
        if (indexHole) entries["files.index-0003.jsonl"] = (row.toString() + "\n").toByteArray()
        if (!missingBlob) entries["blobs/${fileHash.take(2)}/$fileHash"] = sibling
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
        return JSONObject(OpenMinisBackupCompat.convert(out.toByteArray(), includeChats = false))
    }
    @Test fun completeManifestAllowsStaleCleanup() {
        assertTrue(converted().getJSONArray("skills").getJSONObject(0).getBoolean("archiveComplete"))
    }
    @Test fun missingBlobMarksManifestIncompleteAndKeepsWarning() {
        val doc = converted(missingBlob = true)
        assertFalse(doc.getJSONArray("skills").getJSONObject(0).getBoolean("archiveComplete"))
        assertTrue(doc.getJSONArray("conversionNotes").toString().contains("blob"))
    }
    @Test fun knownIndexHoleCannotAuthorizePruning() {
        assertFalse(converted(indexHole = true).getJSONArray("skills").getJSONObject(0).getBoolean("archiveComplete"))
    }
    @Test fun skippedIndexedSiblingCannotAuthorizePruning() {
        assertFalse(converted(skipped = true).getJSONArray("skills").getJSONObject(0).getBoolean("archiveComplete"))
    }
    @Test fun damagedIndexCannotAuthorizePruning() {
        assertFalse(converted(malformed = true).getJSONArray("skills").getJSONObject(0).getBoolean("archiveComplete"))
    }
}
