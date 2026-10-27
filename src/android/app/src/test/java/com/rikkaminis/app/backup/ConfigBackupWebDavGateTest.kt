package com.rikkaminis.app.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [D-B fix/backup-bootstrap-size] The WebDAV bootstrap deadlock.
 *
 * What deadlocked: the whole `webdavConfig` block rode the secrets gate, and
 * the config itself lives in EncryptedSharedPreferences — which a re-signed
 * reinstall always loses. "Restore your remote backups" therefore required the
 * remote credentials, i.e. exactly what had just been lost (2026-10-27 field
 * report: the restore ran with `webdavConfig=false` and the user had to retype
 * url + username + password).
 *
 * The fix splits the gate (url / username / path always, password only with
 * secrets) and — the half that is easy to get wrong — makes the import side
 * merge into whatever this install already stores instead of writing the
 * backup's blank password over it. [WebDavConfigStore.save] documents that a
 * BLANK password CLEARS the stored one, so "backup has no password" must never
 * be turned into `save(blank)`.
 *
 * Arms are on the two pure seams ([ConfigBackup.buildWebDavConfigSection] and
 * [ConfigBackup.resolveWebDavRestore]) rather than through export()/import(),
 * which need Android repositories (see ConfigBackupPayloadTest's header). The
 * seam pairing is asserted as a round trip, so the two halves cannot drift
 * into "export writes a password key the importer does not expect".
 */
class ConfigBackupWebDavGateTest {

    private fun server(password: String = "s3cret") = WebDavConfig(
        url = "https://dav.example.com/remote.php/dav/files/alice",
        username = "alice",
        password = password,
        path = "RikkaMinis_backups",
    )

    // ── Export side: the gate split ──────────────────────────────────────

    @Test
    fun `secrets-free export keeps url username path and omits password`() {
        val section = ConfigBackup.buildWebDavConfigSection(
            includeSecrets = false,
            webDavConfig = server(),
        )
        assertNotNull(section)
        assertEquals(
            listOf("path", "url", "username"),
            section!!.keys().asSequence().toList().sorted(),
        )
        assertFalse("password must not even be a key", section.has("password"))
        assertEquals("alice", section.getString("username"))
        assertEquals("RikkaMinis_backups", section.getString("path"))
    }

    @Test
    fun `secrets export carries all four fields`() {
        val section = ConfigBackup.buildWebDavConfigSection(
            includeSecrets = true,
            webDavConfig = server(),
        )
        assertNotNull(section)
        assertEquals(
            listOf("password", "path", "url", "username"),
            section!!.keys().asSequence().toList().sorted(),
        )
        assertEquals("s3cret", section.getString("password"))
    }

    @Test
    fun `no configured server writes no section at all`() {
        // Import keys off section presence, so an empty object would be a
        // different (and wrong) signal from "no server in this backup".
        assertNull(ConfigBackup.buildWebDavConfigSection(false, null))
        assertNull(ConfigBackup.buildWebDavConfigSection(true, null))
    }

    // ── Import side: the merge (the trap) ────────────────────────────────

    @Test
    fun `fresh reinstall restores three fields and asks for the password`() {
        val section = ConfigBackup.buildWebDavConfigSection(false, server())!!
        val plan = ConfigBackup.resolveWebDavRestore(section, existing = null)
        val config = plan.config
        assertNotNull(config)
        assertEquals("https://dav.example.com/remote.php/dav/files/alice", config!!.url)
        assertEquals("alice", config.username)
        assertEquals("RikkaMinis_backups", config.path)
        assertEquals("password must stay blank, not invented", "", config.password)
        assertNotNull("user has to be told to retype it", plan.notice)
        assertTrue(
            "notice must name the missing secret: ${plan.notice}",
            plan.notice!!.contains("重新填写") && plan.notice.contains("密钥"),
        )
    }

    @Test
    fun `a stored password is never cleared by a secrets-free backup`() {
        // The regression this pins: WebDavConfigStore.save() = "blank password
        // clears the stored one", so a blank from the backup would wipe the
        // password the user had just retyped after reinstalling.
        val section = ConfigBackup.buildWebDavConfigSection(false, server())!!
        val plan = ConfigBackup.resolveWebDavRestore(
            section,
            existing = WebDavConfig(
                url = "https://old.example.com",
                username = "olduser",
                password = "typed-just-now",
                path = "OldDir",
            ),
        )
        assertEquals("typed-just-now", plan.config!!.password)
        // …while the non-secret fields do follow the backup.
        assertEquals("alice", plan.config!!.username)
        assertEquals("RikkaMinis_backups", plan.config!!.path)
        assertEquals(
            "https://dav.example.com/remote.php/dav/files/alice",
            plan.config!!.url,
        )
        assertTrue(
            "notice must say the password was kept: ${plan.notice}",
            plan.notice!!.contains("沿用"),
        )
    }

    @Test
    fun `a backed-up password wins over the stored one`() {
        val section = ConfigBackup.buildWebDavConfigSection(true, server())!!
        val plan = ConfigBackup.resolveWebDavRestore(
            section,
            existing = WebDavConfig(
                url = "https://old.example.com",
                username = "olduser",
                password = "stale",
                path = "OldDir",
            ),
        )
        assertEquals("s3cret", plan.config!!.password)
        assertNull("backup-wins needs no warning", plan.notice)
    }

    @Test
    fun `an explicit empty password key is treated like a missing one`() {
        // A secrets export of a server whose password was never filled writes
        // "password": "" — same information as absent, and equally must not
        // clear a stored one.
        val section = JSONObject()
            .put("url", "https://dav.example.com/x")
            .put("username", "alice")
            .put("path", "RikkaMinis_backups")
            .put("password", "")
        val plan = ConfigBackup.resolveWebDavRestore(
            section,
            existing = WebDavConfig(url = "https://dav.example.com/x", password = "keep-me"),
        )
        assertEquals("keep-me", plan.config!!.password)
    }

    @Test
    fun `a section without a url restores nothing and says nothing`() {
        val plan = ConfigBackup.resolveWebDavRestore(
            JSONObject().put("url", "").put("username", "alice"),
            existing = null,
        )
        assertNull(plan.config)
        assertNull("an empty block is not a user-visible event", plan.notice)
    }

    @Test
    fun `export and import agree on the section shape`() {
        // Round trip through the real serializer: the importer must accept
        // exactly what the exporter emits, for both gate settings.
        for (includeSecrets in listOf(false, true)) {
            val section = ConfigBackup.buildWebDavConfigSection(includeSecrets, server())!!
            val wire = JSONObject(section.toString()) // what a backup file holds
            val plan = ConfigBackup.resolveWebDavRestore(wire, existing = null)
            assertEquals(
                "url must survive the wire for includeSecrets=$includeSecrets",
                "https://dav.example.com/remote.php/dav/files/alice",
                plan.config!!.url,
            )
            assertEquals(
                if (includeSecrets) "s3cret" else "",
                plan.config!!.password,
            )
        }
    }
}
