package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
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

    /**
     * Every launch site that starts a PRoot process and builds the environment.
     *
     * [incr1116-CD F-02] `RootfsManager.kt` is the fourth one and it was missed:
     * its three `--link2symlink` launches (`apk fix`, the offline APKs and the
     * apk-world restore) go through one `prootLoaderEnv()` that only knew
     * PATH/PROOT_TMP_DIR/LD_LIBRARY_PATH, so every package installed by any of
     * them wrote its backing files next to the names inside the rootfs — the
     * exact layout this whole wiring exists to avoid.
     */
    private val launchSites = listOf(
        "PersistentShell.kt", "ShellExecutor.kt", "TerminalSession.kt", "RootfsManager.kt",
    )

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
    fun rootfsReinstallUsesTheL2sPreservingClearPath() {
        val src = sandboxSource("RootfsManager.kt")
        assertEquals(
            "the install choke point must preserve the L2S carrier",
            1,
            Regex("(?m)^\\s*clearRootfsForReinstall\\(rootfsDir\\)\\s*$").findAll(src).count(),
        )
        val reset = src.substringAfter("suspend fun reset(): Unit").substringBefore("fun getRootfsSize")
        assertTrue("manual reset must force the install choke point", reset.contains("installIfNeeded(forceReinstall = true)"))
        assertFalse(
            "RootfsManager must not recursively delete the rootfs and its L2S carrier",
            Regex("(?m)^\\s*rootfsDir\\.deleteRecursively\\(\\)\\s*$").containsMatchIn(src),
        )
    }

    @Test
    fun theBackingDirectoryLivesInsideTheGuestRootfs() {
        val src = sandboxSource("PRootKernel.kt")
        val body = src.substringAfter("internal fun getL2sBackingDir(context: Context): File {")
            .substringBefore("\n    }")
        // Comments talk about the mistakes; only the code decides.
        val code = body.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        // A faked hard link is a symbolic link to this host path, and a guest
        // cannot reach a path outside its root: with `filesDir/l2s-backing`
        // every chain of that build resolved to ENOENT on device (measured
        // 2026-11-15, 1.0.0+3125). The upstream tests use `-r /`, which hides
        // the mistake.
        assertTrue(
            "the backing directory must live inside the guest rootfs: " +
                "the links point at it and a guest cannot leave its root",
            code.contains("rootfsDir"),
        )
        assertFalse(
            "filesDir is outside the rootfs -- every link through it resolves to ENOENT",
            code.contains("filesDir"),
        )
        assertTrue("the directory must be created before PRoot opens it", code.contains("mkdirs()"))
        // PRoot matches this path against canonical host paths; `filesDir`
        // goes through /data/user/0 -> /data/data, and with the literal
        // spelling no path matched: every chain broke on device
        // (1.0.0+3126). The canonical spelling also heals the chains that
        // build made, since both spellings resolve to the same directory.
        assertTrue(
            "the path must be canonical: PRoot compares it against resolved host paths",
            code.contains("canonicalFile"),
        )
    }
}
