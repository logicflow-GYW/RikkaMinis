package com.rikkaminis.app.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android-bound call sites complement the executable policy tests. */
class BackupR2WiringTest {
    private fun readSource(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val source = File(dir, "src/android/app/src/main/java/com/rikkaminis/app/$path")
            if (source.isFile) return source.readText()
            dir = dir.parentFile
        }
        error("Source file not found: $path")
    }

    /** Semantic boundaries, not character-count windows; missing anchors fail. */
    private fun between(source: String, start: String, end: String): String {
        val a = source.indexOf(start)
        check(a >= 0) { "Missing start: $start" }
        val b = source.indexOf(end, a + start.length)
        check(b > a) { "Missing end: $end" }
        return source.substring(a, b)
    }

    @Test
    fun `stage 5 feeds imported and saved flags and consumes the write target`() {
        val block = between(
            readSource("backup/ConfigBackup.kt"),
            "                    skillEnableWriteTarget(",
            "                    // [fix/audit-1015-b2b]",
        )
        assertTrue(block.contains("current = imported.isEnabled"))
        assertTrue(block.contains("backupSaysEnabled = s.optBoolean(\"isEnabled\", true)"))
        assertTrue(block.contains("setEnabled(imported.id, desired)"))
    }

    @Test
    fun `import outcome controls both existing tray variables`() {
        val block = between(
            readSource("ui/settings/BackupSettingsScreen.kt"),
            "                    importReport = report",
            "            } catch (t: Throwable)",
        )
        assertTrue(block.contains("val outcome = restoreNotifyOutcome("))
        assertTrue(block.contains("fatal = report?.fatal"))
        assertTrue(block.contains("fatalHeadline = context.getString(R.string.backup_done_fatal)"))
        assertTrue(block.contains("restoredOk = outcome.ok"))
        assertTrue(block.contains("restoredMsg = outcome.body"))
    }

    @Test
    fun `tray title and body still consume the resolved state`() {
        val block = between(
            readSource("ui/settings/BackupSettingsScreen.kt"),
            "            // Always notify completion from the tray",
            "    // [meta-audit-1028 D4 F-02]",
        )
        assertTrue(block.contains("if (restoredOk) R.string.webdav_notify_title_restored"))
        assertTrue(block.contains("else R.string.webdav_notify_title_failed"))
        assertTrue(block.contains("body = restoredMsg ?: \"\""))
    }
}
