package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Refactor-apk-world] Pure-JVM tests for the apk world snapshot pipeline:
 * parsing Alpine's `/lib/apk/db/installed` on the host side, serializing to
 * the host snapshot file (`name=version` lines), parsing it back, and the
 * offline-trio filter that keeps the restore from touching packages the
 * bundled-APK path guarantees.
 */
class ApkWorldSnapshotTest {

    // ── parseApkDbInstalled ────────────────────────────────────────────

    /** A realistic slice of Alpine 3.21 `/lib/apk/db/installed`. */
    private val apkDbSample = """
        C:Q1AAAAq6AADXqxXWY6X/A=
        P:alpine-baselayout
        V:3.6.8-r1
        A:aarch64
        S:12993
        I:60806
        T:Alpine base dir structure and init scripts
        U:https://gitlab.alpinelinux.org/alpine/alpine-baselayout
        L:gpl-2.0-only
        o:alpine-baselayout
        m:Alpine Linux <build@alpinelinux.org>
        t:1700000000
        c:2c1f9b1e

        C:Q1AAAAq6AADXqxXWY6X/A=
        P:busybox
        V:1.37.0-r12
        A:aarch64
        S:276447
        I:925696
        T:Size optimized toolbox of many common UNIX utilities
        L:gpl-2.0-only
        o:busybox
        m:Alpine Linux <build@alpinelinux.org>
        t:1700000000
        c:01l0dm2i
        F:bin
        R:busybox
        a:busybox

        P:bash
        V:5.2.37-r0
        A:aarch64
        p:cmd:bash=5.2.37-r0
        i:readline=8.2.13-r0
        i:ncurses-libs=6.5_p20241006-r3
        o:bash
    """.trimIndent()

    @Test
    fun `parse apk db into name-version pairs`() {
        val pkgs = parseApkDbInstalled(apkDbSample)
        assertEquals(listOf("alpine-baselayout", "busybox", "bash"), pkgs.map { it.name })
        assertEquals(listOf("3.6.8-r1", "1.37.0-r12", "5.2.37-r0"), pkgs.map { it.version })
    }

    @Test
    fun `parse empty apk db yields empty list`() {
        assertTrue(parseApkDbInstalled("").isEmpty())
        assertTrue(parseApkDbInstalled("\n\n  \n").isEmpty())
    }

    @Test
    fun `trailing block without blank line is captured`() {
        val text = """
            P:zeta
            V:1.2.3-r0
        """.trimIndent()
        assertEquals(listOf(ApkPackage("zeta", "1.2.3-r0")), parseApkDbInstalled(text))
    }

    @Test
    fun `block missing version is skipped`() {
        val text = """
            P:no-version-here
            O:1.0.0-r0

            P:ok
            V:2.0.0-r0
        """.trimIndent()
        assertEquals(listOf(ApkPackage("ok", "2.0.0-r0")), parseApkDbInstalled(text))
    }

    @Test
    fun `block with version but no name is skipped`() {
        val text = """
            V:1.0.0-r0

            P:real
            V:4.4.4-r4
        """.trimIndent()
        assertEquals(listOf(ApkPackage("real", "4.4.4-r4")), parseApkDbInstalled(text))
    }

    // ── formatApkWorld / parseApkWorld round-trip ───────────────────────

    @Test
    fun `world format parse round-trips`() {
        val pkgs = listOf(
            ApkPackage("curl", "8.10.1-r0"),
            ApkPackage("python3", "3.12.8-r0"),
            ApkPackage("libreadline", "8.2.13-r0"),
        )
        val text = formatApkWorld(pkgs)
        assertEquals(pkgs, parseApkWorld(text))
    }

    @Test
    fun `parseApkWorld tolerates comments blanks and bad lines`() {
        val text = """
            # apk-world snapshot — <name>=<version> per line
            curl=8.10.1-r0

            python3=3.12.8-r0
            =broken-no-name
            nope-just-a-name
            git=   2.47.3-r0  
        """.trimIndent()
        assertEquals(
            listOf(
                ApkPackage("curl", "8.10.1-r0"),
                ApkPackage("python3", "3.12.8-r0"),
                ApkPackage("git", "2.47.3-r0"),
            ),
            parseApkWorld(text),
        )
    }

    @Test
    fun `parse empty world text yields empty list`() {
        assertTrue(parseApkWorld("").isEmpty())
        assertTrue(parseApkWorld("# only a comment\n").isEmpty())
    }

    // ── excludeOfflinePackages ─────────────────────────────────────────

    @Test
    fun `offline trio is filtered out, everything else kept`() {
        val pkgs = listOf(
            ApkPackage("bash", "5.2.37-r0"),
            ApkPackage("readline", "8.2.13-r0"),
            ApkPackage("ncurses", "6.5_p20241006-r3"),
            ApkPackage("curl", "8.10.1-r0"),
            ApkPackage("python3", "3.12.8-r0"),
        )
        assertEquals(
            listOf(ApkPackage("curl", "8.10.1-r0"), ApkPackage("python3", "3.12.8-r0")),
            excludeOfflinePackages(pkgs),
        )
    }

    @Test
    fun `exclude is order-preserving and empty-safe`() {
        assertEquals(emptyList<ApkPackage>(), excludeOfflinePackages(emptyList()))
        val pkgs = listOf(ApkPackage("curl", "8.10.1-r0"))
        assertEquals(pkgs, excludeOfflinePackages(pkgs))
    }

    @Test
    fun `similar package names are not filtered`() {
        // `bash-completion` / `musl` / `ncurses-libs` share prefixes with the
        // offline trio names but are distinct packages that MUST be restored.
        val pkgs = listOf(
            ApkPackage("bash-completion", "2.14-r0"),
            ApkPackage("ncurses-libs", "6.5_p20241006-r3"),
            ApkPackage("musl", "1.2.5-r1"),
        )
        assertEquals(pkgs, excludeOfflinePackages(pkgs))
    }

    // ---- [meta1028 D3-F03] the snapshot must stay a superset of "owed" ----
    //
    // The failure this pins is a three-step state machine, not one function:
    // restore fails offline → the retry list holds the user's packages → the
    // next dumpApkWorld() (boot's post-retry dump, or reset()'s pre-wipe dump)
    // read ONLY the apk db, which by then held just the factory packages, and
    // overwrote the snapshot with them. The following restore then "succeeded"
    // against the factory list and deleted the retry queue — so the loss was
    // permanent while every log line said OK.

    @Test
    fun `pending packages survive a degraded db`() {
        val factory = listOf(ApkPackage("busybox", "1.36.1-r29"), ApkPackage("alpine-baselayout", "3.4.0-r2"))
        val owed = listOf(ApkPackage("git", "2.47.1-r0"), ApkPackage("python3", "3.12.8-r0"))
        assertEquals(
            listOf(
                ApkPackage("busybox", "1.36.1-r29"),
                ApkPackage("alpine-baselayout", "3.4.0-r2"),
                ApkPackage("git", "2.47.1-r0"),
                ApkPackage("python3", "3.12.8-r0"),
            ),
            mergeApkWorldPending(factory, owed),
        )
    }

    @Test
    fun `the db version wins over the owed one`() {
        val installed = ApkPackage("git", "2.47.1-r0")
        val stale = ApkPackage("git", "2.45.0-r0")
        assertEquals(listOf(installed), mergeApkWorldPending(listOf(installed), listOf(stale)))
    }

    @Test
    fun `nothing owed leaves the snapshot untouched`() {
        val installed = listOf(ApkPackage("curl", "8.10.1-r0"))
        assertEquals(installed, mergeApkWorldPending(installed, emptyList()))
        assertEquals(emptyList<ApkPackage>(), mergeApkWorldPending(emptyList(), emptyList()))
    }

    @Test
    fun `the merged snapshot round-trips through the serializer`() {
        // The file the next restore reads is `formatApkWorld(merged)`, so the
        // owed entries have to survive write → parse or the merge is cosmetic.
        val factory = listOf(ApkPackage("busybox", "1.36.1-r29"))
        val owed = listOf(ApkPackage("git", "2.47.1-r0"))
        val text = formatApkWorld(mergeApkWorldPending(factory, owed))
        assertEquals(
            listOf(ApkPackage("busybox", "1.36.1-r29"), ApkPackage("git", "2.47.1-r0")),
            parseApkWorld(text),
        )
    }

    @Test
    fun `an entry without a version is not merged in`() {
        // `apk add name=` is a broken argument; a pending line the parser
        // dropped must never reach the snapshot.
        val pending = parseApkWorld("# comment\n\nnoversion\n=1.0\nbroken=\ngit=2.47.1-r0\n")
        assertEquals(listOf(ApkPackage("git", "2.47.1-r0")), pending)
        assertEquals(
            listOf(ApkPackage("busybox", "1.36.1-r29"), ApkPackage("git", "2.47.1-r0")),
            mergeApkWorldPending(listOf(ApkPackage("busybox", "1.36.1-r29")), pending),
        )
    }

    @Test
    fun `the dump merges the retry list instead of overwriting`() {
        // Source-shape half: the pure merge above is only protective if
        // dumpApkWorld actually reads the retry list and writes the merged
        // result. Pinned on the production text because the whole path needs a
        // Context + a real apk db.
        val src = repoFile("src/android/app/src/main/java/com/rikkaminis/app/sandbox/RootfsManager.kt")
        val dump = src.substringAfter("suspend fun dumpApkWorld()").substringBefore("\n    /**")
        assertTrue("dump must read the retry list: $dump", dump.contains("readApkWorldPending()"))
        assertTrue("dump must write the MERGED list: $dump", dump.contains("formatApkWorld(merged)"))
        assertTrue(
            "dump must not write the raw db result any more: $dump",
            !Regex("formatApkWorld\\(packages\\)").containsMatchIn(dump),
        )
    }

    private fun repoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}