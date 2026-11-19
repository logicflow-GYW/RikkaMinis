package com.rikkaminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R2 F-01] The exported share receiver's URI admission gate, pinned on the JVM.
 *
 * `ShareReceiverActivity` is `exported="true"` and answers ACTION_SEND /
 * ACTION_VIEW from every app on the device, so the incoming URI is
 * attacker-chosen. Before this gate the activity called
 * `contentResolver.openInputStream(uri)` on whatever arrived:
 *
 *  - `file:///data/user/0/com.rikkaminis.app/databases/minis.db` opened a raw
 *    `FileInputStream` as THIS app, so a third-party app made us copy our own
 *    database into the shareable staging dir;
 *  - `content://com.rikkaminis.app.fileprovider/share/x` resolved because the
 *    caller of `openInputStream` was our own process, so every declared
 *    provider root was readable on demand.
 *
 * Two layers, and every arm below is a literal expectation: an arm that
 * silently widens (a scheme accepted "for compatibility", an unresolvable path
 * treated as fine) shows up red rather than as a quietly adjusted answer.
 *
 * The NEGATIVE CONTROL matters as much as the refusals: a real share — a
 * `content://` URI from the system picker or another app's provider — must
 * still be admitted, otherwise "refuse everything" would look like a fix.
 */
class SharedUriAdmissionTest {

    private val pkg = "com.rikkaminis.app"
    private val ownAuthority = "$pkg.fileprovider"
    private val dataDir = "/data/user/0/$pkg"

    /** Stands in for `File(raw).canonicalPath`: the fixture's own resolution
     *  rules, including a symlinked spelling of the private dir. */
    private fun canonical(raw: String): String? = when {
        raw.contains("..") -> {
            // Emulate the kernel: `a/../b` collapses, and a climb above "/" stays at "/".
            val out = ArrayList<String>()
            for (seg in raw.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                    else -> out.add(seg)
                }
            }
            "/" + out.joinToString("/")
        }
        raw == "/sdcard/secret-link" || raw.startsWith("/sdcard/secret-link/") ->
            "$dataDir/shared_prefs" + raw.removePrefix("/sdcard/secret-link")
        else -> raw
    }

    private fun admit(
        scheme: String?,
        authority: String? = null,
        path: String? = null,
        appDataDir: String? = dataDir,
    ) = admitSharedUri(
        scheme = scheme,
        authority = authority,
        path = path,
        appDataDir = appDataDir,
        ownProviderAuthority = ownAuthority,
        canonicalize = ::canonical,
    )

    private fun refusalOf(verdict: SharedUriAdmission): SharedUriRefusal? =
        (verdict as? SharedUriAdmission.Refused)?.reason

    // ── layer 1: scheme / authority whitelist ─────────────────────────────

    @Test
    fun `a file uri naming the private database is refused`() {
        val verdict = admit("file", path = "$dataDir/databases/minis.db")
        assertEquals(SharedUriRefusal.FileScheme, refusalOf(verdict))
    }

    @Test
    fun `every file uri is refused, even one outside the app`() {
        // The scheme IS the attack: `file://` is a raw FileInputStream opened as
        // this app, so "the path looks harmless" is not a reason to admit it.
        assertEquals(SharedUriRefusal.FileScheme, refusalOf(admit("file", path = "/sdcard/DCIM/a.jpg")))
        assertEquals(SharedUriRefusal.FileScheme, refusalOf(admit("FILE", path = "/tmp/x")))
    }

    @Test
    fun `the app's own fileprovider authority is refused`() {
        val verdict = admit("content", ownAuthority, "/share/exported.json")
        assertEquals(SharedUriRefusal.OwnFileProvider, refusalOf(verdict))
    }

    @Test
    fun `an unknown scheme is refused`() {
        assertEquals(SharedUriRefusal.NoScheme, refusalOf(admit("javascript", path = "/x")))
        assertEquals(SharedUriRefusal.NoScheme, refusalOf(admit("", path = "/x")))
        assertEquals(SharedUriRefusal.NoScheme, refusalOf(admit(null, path = "/x")))
    }

    @Test
    fun `a content uri with no authority is refused`() {
        assertEquals(SharedUriRefusal.NoAuthority, refusalOf(admit("content", authority = null, path = "/x")))
        assertEquals(SharedUriRefusal.NoAuthority, refusalOf(admit("content", authority = "", path = "/x")))
    }

    // ── layer 2: canonical prefix guard, fail-closed ──────────────────────

    @Test
    fun `an absolute content path inside the private data dir is refused`() {
        val verdict = admit("content", "com.other.app.provider", "$dataDir/shared_prefs/secrets.xml")
        assertEquals(SharedUriRefusal.PrivateDataDir, refusalOf(verdict))
    }

    @Test
    fun `a dot-dot spelling that lands in the private data dir is refused`() {
        // The prefix guard runs on the CANONICAL form, so `..` cannot walk into
        // a directory the raw string does not name.
        val verdict = admit("content", "com.other.app.provider", "/data/user/0/$pkg/../$pkg/databases/minis.db")
        assertEquals(SharedUriRefusal.PrivateDataDir, refusalOf(verdict))
    }

    @Test
    fun `an absolute content path that cannot be resolved is refused`() {
        // Fail-closed: "I cannot tell where this lands" is never "it lands
        // somewhere allowed". Pre-fix this shape fell back to the raw path.
        val verdict = admitSharedUri(
            scheme = "content",
            authority = "com.other.app.provider",
            path = "$dataDir/databases/minis.db",
            appDataDir = dataDir,
            ownProviderAuthority = ownAuthority,
            canonicalize = { null },
        )
        assertEquals(SharedUriRefusal.UnresolvablePath, refusalOf(verdict))
    }

    // ── negative controls: real shares still work ─────────────────────────

    @Test
    fun `a system picker content uri is admitted`() {
        // `document/primary:Download/report.pdf` is provider-internal, not a
        // host path — canonicalizing it as one would refuse every real share.
        val verdict = admit("content", "com.android.providers.downloads.documents", "document/primary:Download/report.pdf")
        assertTrue("a real share must be admitted, got $verdict", verdict is SharedUriAdmission.Admitted)
        assertNull((verdict as SharedUriAdmission.Admitted).canonicalPath)
    }

    @Test
    fun `another apps content uri with an absolute path outside the app is admitted`() {
        val verdict = admit("content", "com.other.app.provider", "/sdcard/DCIM/photo.jpg")
        assertTrue("a real share must be admitted, got $verdict", verdict is SharedUriAdmission.Admitted)
        assertEquals("/sdcard/DCIM/photo.jpg", (verdict as SharedUriAdmission.Admitted).canonicalPath)
    }

    @Test
    fun `a content uri with no path is admitted`() {
        assertTrue(admit("content", "com.other.app.provider", null) is SharedUriAdmission.Admitted)
        assertTrue(admit("content", "com.other.app.provider", "") is SharedUriAdmission.Admitted)
    }

    // ── the prefix predicate itself ───────────────────────────────────────

    @Test
    fun `the prefix predicate compares whole segments`() {
        assertTrue(isPathUnder("$dataDir/databases", dataDir))
        assertTrue(isPathUnder(dataDir, dataDir))
        assertTrue(isPathUnder("$dataDir/", dataDir))
        // A sibling whose NAME starts with the same characters is not inside.
        assertFalse(isPathUnder("/data/user/0/$pkg-backup/x", dataDir))
        assertFalse(isPathUnder("/data/user/0", dataDir))
        assertFalse(isPathUnder(null, dataDir))
        assertFalse(isPathUnder("$dataDir/x", null))
        assertFalse(isPathUnder("$dataDir/x", ""))
    }

    @Test
    fun `a symlinked spelling of the private dir is refused`() {
        // The canonical form is what is compared, so a link the attacker can
        // create (or that the fixture emulates) cannot disguise the target.
        val verdict = admit("content", "com.other.app.provider", "/sdcard/secret-link/x")
        assertEquals(SharedUriRefusal.PrivateDataDir, refusalOf(verdict))
    }
}
