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
}