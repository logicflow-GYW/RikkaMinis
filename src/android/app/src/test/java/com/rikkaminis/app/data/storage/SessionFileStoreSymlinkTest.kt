package com.rikkaminis.app.data.storage

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [R2 F-06] Deleting a session must never reach THROUGH a link.
 *
 * The defect: `deleteRecursivelyChecked` called `File.deleteRecursively()`,
 * which walks with `File.listFiles()` — and `listFiles()` FOLLOWS a directory
 * symlink. A session whose `workspace` held `link -> /sdcard/DCIM` therefore
 * emptied DCIM when the chat was deleted, while the link's own target directory
 * survived (the shape the report measured: the target's files gone, the link's
 * target still there).
 *
 * TWO guards are pinned here because they stop different things:
 *
 *  - **no-follow walk** — the link is unlinked as a link, its target untouched;
 *  - **canonical prefix, fail-closed** — a session id carrying `..`, or a root
 *    that moved, cannot delete a tree that was never the subject.
 *
 * The NEGATIVE CONTROL is the second half of every test: a normal session tree
 * must still be removed completely. Deleting the feature would satisfy the
 * refusal arms and is not a fix.
 *
 * Every fixture is a fresh temp dir — nothing here touches the real
 * `/var/minis/memory`.
 */
class SessionFileStoreSymlinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): SessionFileStore = SessionFileStore(tmp.newFolder("filesDir"))

    private fun sid(): String = UUID.randomUUID().toString()

    private fun link(target: File, link: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (_: Exception) {
        false // filesystem without symlink support: the arm cannot be asserted
    }

    // ── the defect ─────────────────────────────────────────────────────────

    @Test
    fun `deleting a session leaves a linked outside directory intact`() {
        val s = store()
        val id = sid()
        val outside = tmp.newFolder("outside")
        File(outside, "keep1.txt").writeText("1")
        File(outside, "keep2.txt").writeText("2")
        val ws = File(s.sessionDir(id), "workspace").apply { mkdirs() }
        File(ws, "own.txt").writeText("own")
        if (!link(outside, File(ws, "link"))) return

        val result = s.deleteSessionFiles(id)

        assertTrue("the session itself must be gone", result.sessionDeleted)
        assertFalse("the session dir must be removed", s.sessionDir(id).exists())
        assertTrue("the linked directory must survive", outside.exists())
        assertEquals(
            "every file behind the link must survive",
            listOf("keep1.txt", "keep2.txt"),
            outside.listFiles()?.map { it.name }?.sorted(),
        )
    }

    @Test
    fun `a normal session directory is still fully deleted`() {
        val s = store()
        val id = sid()
        val ws = File(s.sessionDir(id), "workspace").apply { mkdirs() }
        File(ws, "a.txt").writeText("a")
        File(ws, "sub").apply { mkdirs() }
        File(File(ws, "sub"), "b.txt").writeText("b")
        File(s.sessionDir(id), "attachments").apply { mkdirs() }

        val result = s.deleteSessionFiles(id)

        assertTrue(result.sessionDeleted)
        assertFalse("the whole tree must be gone (negative control)", s.sessionDir(id).exists())
    }

    @Test
    fun `reclaiming orphans leaves a linked outside directory intact`() {
        val s = store()
        val orphan = sid()
        val live = sid()
        val outside = tmp.newFolder("outside-orphan")
        File(outside, "keep.txt").writeText("keep")
        val ws = File(s.sessionDir(orphan), "workspace").apply { mkdirs() }
        if (!link(outside, File(ws, "link"))) return

        val report = s.reclaimOrphans(setOf(live))

        assertEquals(listOf(orphan), report.sessionIds)
        assertFalse(s.sessionDir(orphan).exists())
        assertTrue("the linked directory must survive the orphan sweep", outside.exists())
        assertEquals(listOf("keep.txt"), outside.listFiles()?.map { it.name })
    }

    @Test
    fun `reclaiming orphans still removes a normal orphan tree`() {
        val s = store()
        val orphan = sid()
        val live = sid()
        val ws = File(s.sessionDir(orphan), "workspace").apply { mkdirs() }
        File(ws, "x.txt").writeText("x")

        s.reclaimOrphans(setOf(live))

        assertFalse("a normal orphan must still be reclaimed (negative control)", s.sessionDir(orphan).exists())
    }

    @Test
    fun `a media leaf holding a link keeps the linked target`() {
        val s = store()
        val orphan = sid()
        val live = sid()
        val outside = tmp.newFolder("outside-media")
        File(outside, "keep.txt").writeText("keep")
        val leaf = File(s.mediaRootForTesting(), "2026-11-19/$orphan").apply { mkdirs() }
        if (!link(outside, File(leaf, "link"))) return

        s.reclaimOrphans(setOf(live))

        assertTrue("the linked target must survive the media sweep", outside.exists())
        assertEquals(listOf("keep.txt"), outside.listFiles()?.map { it.name })
    }

    @Test
    fun `a session dir that is itself a link out of the root deletes nothing`() {
        val s = store()
        val id = sid()
        // The session dir IS a link: lexically `minis-sessions/<sid>` (inside the
        // root), really a directory elsewhere. Deleting through it would empty
        // the target while the canonical check — if it only looked at the
        // lexical form — said "inside".
        val outside = tmp.newFolder("outside-session-link")
        File(outside, "keep.txt").writeText("keep")
        val sessionDir = s.sessionDir(id)
        sessionDir.parentFile?.mkdirs()
        if (!link(outside, sessionDir)) return

        val result = s.deleteSessionFiles(id)

        assertFalse("a session dir that resolves outside the root must be refused", result.sessionDeleted)
        assertTrue("the linked directory must survive", outside.exists())
        assertEquals(listOf("keep.txt"), outside.listFiles()?.map { it.name })
    }

    @Test
    fun `a media date folder link is not walked into`() {
        val s = store()
        val live = sid()
        // `media/<date>/link -> outside`, where `outside` holds a directory
        // named like a dead session id. Following the link during enumeration
        // makes that directory an orphan candidate — and the sweep then deletes
        // a tree that lives outside mediaRoot entirely.
        val deadName = sid()
        val outside = tmp.newFolder("outside-media-walk")
        val victim = File(outside, deadName).apply {
            mkdirs()
            File(this, "keep.txt").writeText("keep")
        }
        val dateDir = File(s.mediaRootForTesting(), "2026-11-19").apply { mkdirs() }
        if (!link(outside, File(dateDir, "link"))) return

        s.reclaimOrphans(setOf(live))

        assertTrue("the linked tree must not be enumerated as media", victim.exists())
        assertEquals(listOf("keep.txt"), victim.listFiles()?.map { it.name })
    }

    // ── the canonical prefix guard ─────────────────────────────────────────

    @Test
    fun `a session id that climbs out of the sessions root deletes nothing`() {
        val s = store()
        // A `..`-carrying id: `minis-sessions/../../victim` names a sibling of
        // the files dir. The string is a legal File path, so without the
        // canonical prefix guard the delete would leave the sessions root.
        val victim = File(tmp.root, "victim").apply {
            mkdirs()
            File(this, "keep.txt").writeText("keep")
        }
        // filesDir/minis-sessions/../../../victim == tmp.root/victim
        val result = s.deleteSessionFiles("../../victim")

        assertFalse("a climbing id must be refused", result.sessionDeleted)
        assertTrue("the victim tree must survive", victim.exists())
        assertEquals(listOf("keep.txt"), victim.listFiles()?.map { it.name })
    }

    @Test
    fun `an absent session still reports success`() {
        val s = store()
        val result = s.deleteSessionFiles(sid())
        assertTrue("absent == nothing to delete == success", result.sessionDeleted)
        assertTrue(result.mediaDeleted)
    }

    // ── the browser tab-state half ─────────────────────────────────────────

    @Test
    fun `the tab state file is deleted with the session`() {
        val s = store()
        val id = sid()
        val tab = s.browserTabFile(id).apply {
            parentFile?.mkdirs()
            writeText("{\"tabs\":[]}")
        }
        File(s.sessionDir(id), "workspace").apply { mkdirs() }

        val result = s.deleteSessionFiles(id)

        assertTrue(result.sessionDeleted)
        assertFalse("the tab state file must go with the session", tab.exists())
    }

    @Test
    fun `a live session's tab state file survives the orphan sweep`() {
        val s = store()
        val live = sid()
        val dead = sid()
        val liveTab = s.browserTabFile(live).apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        s.browserTabFile(dead).apply {
            parentFile?.mkdirs()
            writeText("{}")
        }

        s.reclaimOrphans(setOf(live))

        assertTrue("a live session's tab state must stay", liveTab.exists())
        assertFalse("a dead session's tab state must go", s.browserTabFile(dead).exists())
        assertNotNull(s.browserTabFile(live))
    }
}
