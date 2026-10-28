package com.rikkaminis.app.ui.chat

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 C1 / F-03] Promoting a draft must move EVERY byte it promised.
 *
 * The production functions under test live in `DraftResourceMigration.kt` and are
 * driven against real directories on the JVM (no Android types involved), which
 * is the whole reason they were extracted from `ChatViewModel.migrateDraftResources`.
 *
 * Every test that pins a fixed defect also runs the PRE-FIX algorithm
 * ([legacyMoveEntry] / [legacyCopyRecursive] — transcribed verbatim from
 * `ChatSessionEntryExt.kt` @ main `bfb5b0e6` lines 347-356 / 396-405) on the same
 * fixture, so the fixture is proven to exhibit the bug rather than merely to pass
 * after the change. A criterion that can no longer see the pre-fix shape fails
 * here instead of passing silently.
 */
class DraftResourceMigrationTest {

    private val root: File =
        File(System.getProperty("java.io.tmpdir"), "draft-migrate-" + UUID.randomUUID()).apply { mkdirs() }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ── 1. a name that already exists in the live session must MERGE ────────

    @Test
    fun `an entry whose name already exists in the live session is merged, not skipped`() {
        val draft = dir("draft/workspace/tool-run")
        val real = dir("real/workspace/tool-run")
        file("draft/workspace/tool-run/result.txt", "draft-side output")
        file("real/workspace/tool-run/marker.txt", "live-side file")

        // Witness: the pre-fix statement is a no-op exactly here — `!target.exists()`
        // guards the rename AND the copy, so a name collision means nothing moves.
        legacyMoveEntry(draft, real)
        assertTrue(
            "pre-fix behaviour: the colliding subtree stays in the draft directory",
            File(draft, "result.txt").exists(),
        )
        assertFalse(
            "pre-fix behaviour: and its file never reaches the real session",
            File(real, "result.txt").exists(),
        )

        assertTrue(
            "a name collision is the merge case, not a reason to skip",
            migrateDraftEntry(draft, real),
        )
        assertEquals(
            "the draft's file lands next to the live one",
            "draft-side output",
            File(real, "result.txt").readText(),
        )
        assertEquals(
            "and the live session's own file is untouched",
            "live-side file",
            File(real, "marker.txt").readText(),
        )
        assertFalse("the merged draft directory is gone", draft.exists())
    }

    // ── 2. an unreadable directory is a failure, not an empty one ───────────

    @Test
    fun `a directory that cannot be listed reports failure instead of success`() {
        val draft = dir("draft/attachments")
        file("draft/attachments/a.png", "png")
        val unreadable = object : File(draft.path) {
            override fun listFiles(): Array<File>? = null
        }

        assertTrue(
            "pre-fix behaviour: `src.listFiles()?.all { … } ?: true` folds an I/O failure " +
                "into 'everything copied'",
            legacyCopyRecursive(unreadable, File(root, "legacy-target")),
        )
        assertFalse(
            "a subtree that copied nothing must not report success",
            mergeInto(unreadable, dir("real/attachments")),
        )
        assertTrue(
            "and the bytes it could not move are still where they were",
            File(draft, "a.png").exists(),
        )
    }

    // ── 3. a merged directory is removed once it is empty ───────────────────

    @Test
    fun `a fully merged source directory is removed, not left as an empty shell`() {
        val draft = dir("draft/offloads/run-1")
        val real = dir("real/offloads/run-1")
        file("draft/offloads/run-1/stdout.txt", "line")

        legacyCopyRecursive(draft, real)
        assertTrue(
            "pre-fix behaviour: the file is copied and its source deleted, but the now-empty " +
                "directory is never removed — a permanent `__new__*` shell",
            draft.exists(),
        )
        assertEquals(
            "the pre-fix copy does reach the live side",
            "line",
            File(real, "stdout.txt").readText(),
        )

        val draft2 = dir("draft/offloads/run-2")
        val real2 = dir("real/offloads/run-2")
        file("draft/offloads/run-2/stdout.txt", "line2")
        assertTrue("the merge succeeds", migrateDraftEntry(draft2, real2))
        assertFalse("and the emptied source directory is gone", draft2.exists())
        assertEquals("line2", File(real2, "stdout.txt").readText())
    }

    // ── 4. idempotency: re-running never duplicates and never overwrites ─────

    @Test
    fun `an identical copy left by a previous run is dropped from the draft side`() {
        val draft = dir("draft/attachments")
        val real = dir("real/attachments")
        file("draft/attachments/a.png", "identical-bytes")
        file("real/attachments/a.png", "identical-bytes")

        assertTrue(
            "a re-run after a failed source-delete must converge, not report a permanent failure",
            migrateDraftEntry(File(draft, "a.png"), File(real, "a.png")),
        )
        assertFalse("the duplicate is gone from the draft side", File(draft, "a.png").exists())
        assertEquals("and the live copy is unchanged", "identical-bytes", File(real, "a.png").readText())
    }

    @Test
    fun `a name that exists on both sides with different bytes is never overwritten`() {
        val draft = file("draft/attachments/a.png", "draft-version")
        val real = file("real/attachments/a.png", "live-version")

        assertFalse(
            "the live session wins a genuine conflict — replacing it would turn a stale draft " +
                "copy into a lost live file",
            migrateDraftEntry(draft, real),
        )
        assertEquals("the live copy keeps its bytes", "live-version", real.readText())
        assertTrue("and the draft copy is kept rather than destroyed", draft.exists())
        assertEquals("draft-version", draft.readText())
    }

    // ── 5. the fast path still moves whole subtrees in one rename ────────────

    @Test
    fun `a free destination name moves the whole subtree`() {
        val draft = dir("draft/browser/session")
        file("draft/browser/session/page.html", "<html>")
        val real = File(root, "real/browser/session")

        assertTrue("rename when the name is free", migrateDraftEntry(draft, real))
        assertEquals("<html>", File(real, "page.html").readText())
        assertFalse("the source is gone with the subtree it carried", draft.exists())
    }

    // ── 6. collisions that cannot be merged report failure ──────────────────

    @Test
    fun `a file cannot be merged into a directory of the same name`() {
        val draft = file("draft/workspace/run", "i am a file")
        val real = dir("real/workspace/run")
        file("real/workspace/run/inside.txt", "x")

        assertFalse(migrateDraftEntry(draft, real))
        assertTrue("the file is kept", draft.exists())
        assertTrue("the directory is kept", File(real, "inside.txt").exists())
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun dir(rel: String): File =
        File(root, rel).apply { mkdirs() }.also { check(it.isDirectory) { "not a directory: $it" } }

    private fun file(rel: String, content: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    // ── the pre-fix algorithm, transcribed from main `bfb5b0e6` ─────────────

    private fun legacyMoveEntry(child: File, target: File) {
        if (!target.exists() && !child.renameTo(target)) {
            legacyCopyRecursive(child, target)
        }
    }

    private fun legacyCopyRecursive(src: File, dst: File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { legacyCopyRecursive(it, File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)
}
