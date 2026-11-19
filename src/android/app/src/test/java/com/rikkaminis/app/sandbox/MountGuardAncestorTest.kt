package com.rikkaminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Delegate is ALWAYS harmless, even if the guard is mutated or broken. */
class MountGuardAncestorTest {
    private fun verdict(command: String, target: String, prefix: String = "/var/minis/mounts/vault"): Pair<Int, String> {
        val root = Files.createTempDirectory("mount-ancestor").toFile()
        try {
            val cfg = File(root, "cfg").apply { writeText("$prefix\n") }
            val script = PRootKernel.mountGuardScript(command)
                .replace(PRootKernel.MOUNT_GUARD_CFG_LINUX_PATH, cfg.absolutePath)
                .replace("exec /bin/busybox ", "echo DELEGATE ")
            val wrapper = File(root, "wrapper").apply { writeText(script) }
            val args = if (command == "mv") listOf(target, "/tmp/out") else listOf(target)
            val process = ProcessBuilder(listOf("/bin/sh", wrapper.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        } finally { root.deleteRecursively() }
    }

    @Test fun `rm and mv refuse every ancestor including root`() {
        for (cmd in listOf("rm", "mv", "rmdir")) {
            for (target in listOf("/var/minis/mounts", "/var/minis", "/var", "/", "/var/minis/mounts/../mounts/")) {
                val (code, text) = verdict(cmd, target)
                assertEquals("$cmd $target -> $text", 1, code)
                assertTrue(text, text.contains("read-only mounted folder"))
                assertFalse(text, text.contains("DELEGATE"))
            }
        }
    }

    @Test fun `writable deep sibling paths still reach delegate`() {
        for (cmd in listOf("rm", "mv")) {
            for (target in listOf("/var/minis/mounts/writable/deep/file", "/var/minis/workspace/deep/file", "/var/minis/mounts-vault")) {
                val (code, text) = verdict(cmd, target)
                assertEquals(text, 0, code)
                assertTrue(text, text.contains("DELEGATE"))
            }
        }
    }

    @Test fun `locked mount descendants remain read only not a negative control`() {
        val (code, text) = verdict("rm", "/var/minis/mounts/vault/deep/file")
        assertEquals(1, code)
        assertFalse(text.contains("DELEGATE"))
    }

    @Test fun `reading or creating beside mount is unchanged`() {
        for (cmd in listOf("touch", "mkdir")) {
            val (code, text) = verdict(cmd, "/var/minis/mounts/new")
            assertEquals(text, 0, code)
        }
    }
}
