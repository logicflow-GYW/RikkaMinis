package com.rikkaminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the one wiring that fixes the `rm -rf` failure measured on device
 * (2026-11-15): with the backing files of faked hard links kept next to the
 * names, the chain owner's directory cannot be removed while another name of
 * the chain still exists -- `rm -rf` of a `cp -a` copy whose owner landed in a
 * subdirectory failed once with "Directory not empty".
 *
 * PRoot already supports keeping them in a dedicated host directory
 * (`PROOT_L2S_DIR`). Every entry point that starts a PRoot process has to pass
 * it: one that does not would keep putting backing files in guest directories
 * and the failure would come back for whatever that entry point creates.
 *
 * Source-text test: this class cannot build a Context, and the defect is
 * exactly "a launch site forgot the variable".
 */
class L2sBackingDirWiringTest {

    // Same root discovery as SilentMirrorWiringTest: Gradle sets user.dir to
    // the module directory, the JVM harness sets it to the repo root.
    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun sandboxSource(name: String): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/sandbox/$name")

    /** Every launch site that starts a PRoot process and builds the environment. */
    private val launchSites = listOf("PersistentShell.kt", "ShellExecutor.kt", "TerminalSession.kt")

    @Test
    fun everyPRootLaunchSitePassesTheBackingDirectory() {
        for (site in launchSites) {
            val src = sandboxSource(site)
            assertTrue(
                "$site starts PRoot but never sets PROOT_L2S_DIR -- its chains keep their " +
                    "backing files in guest directories, and rm -rf trips over them",
                src.contains("PROOT_L2S_DIR"),
            )
            assertTrue(
                "$site must take the directory from PRootKernel.getL2sBackingDir() " +
                    "instead of building a path of its own",
                src.contains("PRootKernel.getL2sBackingDir(context)"),
            )
        }
    }

    @Test
    fun theBackingDirectoryLivesInPersistentStorage() {
        val src = sandboxSource("PRootKernel.kt")
        val body = src.substringAfter("internal fun getL2sBackingDir(context: Context): File {")
            .substringBefore("\n    }")
        assertTrue(
            "the backing directory must survive restarts: a wiped directory turns every " +
                "existing faked hard link into a dangling symlink",
            body.contains("filesDir"),
        )
        assertFalse(
            "cacheDir is evictable; a wipe disconnects every existing chain",
            body.contains("cacheDir"),
        )
        assertTrue("the directory must be created before PRoot opens it", body.contains("mkdirs()"))
    }
}
