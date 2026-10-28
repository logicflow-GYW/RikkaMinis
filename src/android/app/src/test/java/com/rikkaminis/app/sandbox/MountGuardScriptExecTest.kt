package com.rikkaminis.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * [meta1028 D3-F06] Behavioural test of the generated read-only mount guard.
 *
 * `MountGuardScriptTest` is a string pin: it asserts the codegen contains the
 * shapes we meant to write. That is exactly the kind of test that let the
 * bypass through — `assertTrue(s.contains("-[!-]*t?*) … \${arg##*[t]}"))` was
 * satisfied by the buggy line, because the line was written by the same person
 * who believed it worked. The defect only shows up when `/bin/sh` actually
 * expands `##*[t]` and the guard decides.
 *
 * So this class RUNS the generated wrapper under a real shell and asserts the
 * verdict. Two substitutions, both outside the logic under test:
 *
 *  - `cfg` (the read-only prefix list) is redirected to a temp file, because on
 *    a dev machine / CI runner `/var/minis/.mount-readonly-prefixes` does not
 *    exist — and an absent list makes every check pass, which would make every
 *    "refused" arm assert the opposite of what it means;
 *  - the delegate `exec /bin/busybox <cmd>` is pointed at a real busybox when
 *    one is present, else at `true`. The argv selection and `check_prefix` run
 *    verbatim.
 *
 * Skipped (not failed) when no POSIX shell is available.
 */
class MountGuardScriptExecTest {

    private lateinit var root: File
    private lateinit var mount: File
    private lateinit var cfg: File
    private lateinit var shell: File
    private lateinit var delegate: String

    @Before
    fun setUp() {
        val sh = listOf("/bin/sh", "/bin/ash", "/usr/bin/sh").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("no POSIX shell available", sh != null)
        shell = sh!!
        root = Files.createTempDirectory("minis-guard-exec").toFile()
        mount = File(root, "vault").also { it.mkdirs() }
        cfg = File(root, "readonly-prefixes.txt")
        cfg.writeText("${mount.absolutePath}\n")
        delegate = listOf("/bin/busybox", "/usr/bin/busybox")
            .map(::File)
            .firstOrNull { it.canExecute() }
            ?.absolutePath
            ?: "true"
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    // ── harness ─────────────────────────────────────────────────────────────

    /** The generated wrapper for [name], with cfg + delegate substituted. */
    private fun wrapper(name: String): File {
        val script = PRootKernel.mountGuardScript(name)
            .replace(PRootKernel.MOUNT_GUARD_CFG_LINUX_PATH, cfg.absolutePath)
            .replace("exec /bin/busybox ", "exec $delegate ")
        val f = File(root, name)
        f.writeText(script)
        f.setExecutable(true, false)
        return f
    }

    private data class Run(val exit: Int, val err: String)

    private fun run(name: String, vararg args: String): Run {
        val script = wrapper(name)
        val cmd = mutableListOf(shell.absolutePath, script.absolutePath).apply { addAll(args) }
        // stdout goes to a file rather than being discarded: `ProcessBuilder
        // .Redirect.DISCARD` is a Java 9 API and does not exist on the Android
        // platform classes these unit tests compile against. stderr is drained
        // because that is where the guard's verdict is printed.
        val sink = File(root, "stdout-$name.txt")
        val p = ProcessBuilder(cmd)
            .directory(root)
            .redirectOutput(sink)
            .start()
        val err = p.errorStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        return Run(exit, err)
    }

    private fun assertRefused(what: String, name: String, vararg args: String) {
        val r = run(name, *args)
        assertEquals("$what must be refused (stderr=${r.err.trim()})", 1, r.exit)
        assertTrue(
            "$what must be refused BY THE GUARD, got: ${r.err.trim()}",
            r.err.contains("read-only mount") || r.err.contains("cannot resolve the write target"),
        )
    }

    private fun assertAllowed(what: String, name: String, vararg args: String) {
        val r = run(name, *args)
        assertFalse("$what must not be refused, got: ${r.err.trim()}", r.err.contains("read-only mount guard"))
        assertEquals("$what must run through to the delegate (stderr=${r.err.trim()})", 0, r.exit)
    }

    // ── the reported bypasses ───────────────────────────────────────────────

    @Test
    fun `cp with a clustered -t and a joined target is refused`() {
        // The report's exact spellings. The old code ran `${arg##*[t]}` on the
        // whole argument, which truncates at the LAST 't' — inside "moun(t)s"
        // or at the end of the mount name — leaving "" (short-circuited by
        // `[ -z "$tdest" ] ||`) or a fragment like "s/vault".
        val src = File(root, "src.txt").also { it.writeText("x") }
        assertRefused("cp -rt<mount>", "cp", "-rt${mount.absolutePath}", src.absolutePath)
        assertRefused("cp -t<mount>", "cp", "-t${mount.absolutePath}", src.absolutePath)
        assertRefused("cp -a -rt<mount> x", "cp", "-a", "-rt${mount.absolutePath}", src.absolutePath)
    }

    @Test
    fun `cp with a separated -t is refused`() {
        val src = File(root, "src.txt").also { it.writeText("x") }
        assertRefused("cp -t <mount>", "cp", "-t", mount.absolutePath, src.absolutePath)
        assertRefused("cp -rt <mount>", "cp", "-rt", mount.absolutePath, src.absolutePath)
        assertRefused("cp --target-directory=<mount>", "cp", "--target-directory=${mount.absolutePath}", src.absolutePath)
        assertRefused("cp --target-directory <mount>", "cp", "--target-directory", mount.absolutePath, src.absolutePath)
    }

    @Test
    fun `ln with a joined target is refused`() {
        val src = File(root, "src.txt").also { it.writeText("x") }
        assertRefused("ln -st<mount>", "ln", "-st${mount.absolutePath}", src.absolutePath)
        assertRefused("ln -t<mount>", "ln", "-t${mount.absolutePath}", src.absolutePath)
    }

    @Test
    fun `mv with a flag-value target is refused`() {
        // mv is not a LAST_ARG command: before the fix the joined forms started
        // with '-' and were skipped by `-*) continue`, so only the source was
        // inspected.
        val src = File(root, "src.txt").also { it.writeText("x") }
        assertRefused("mv -t<mount>", "mv", "-t${mount.absolutePath}", src.absolutePath)
        assertRefused("mv --target-directory=<mount>", "mv", "--target-directory=${mount.absolutePath}", src.absolutePath)
        assertRefused("mv --target-directory <mount>", "mv", "--target-directory", mount.absolutePath, src.absolutePath)
        assertRefused("mv -t <mount>", "mv", "-t", mount.absolutePath, src.absolutePath)
    }

    @Test
    fun `mv out of a read-only mount is still refused`() {
        // [audit3 F-07] must not regress: the fix added the flag-value parse to
        // mv, and mv must keep checking every positional (a mv SOURCE is
        // destroyed).
        val victim = File(mount, "f.md").also { it.writeText("v") }
        assertRefused("mv <mount>/f /tmp/", "mv", victim.absolutePath, File(root, "out").absolutePath)
        assertTrue("the file must still be there", victim.exists())
    }

    @Test
    fun `a -t without a directory is refused, not allowed`() {
        val src = File(root, "src.txt").also { it.writeText("x") }
        for (name in listOf("cp", "ln", "mv")) {
            val r = run(name, "-t")
            assertEquals("$name -t must be refused", 1, r.exit)
            assertTrue(
                "$name -t must say why, got: ${r.err.trim()}",
                r.err.contains("given without a directory"),
            )
        }
        // …and the source was never consumed either
        assertTrue(src.exists())
    }

    @Test
    fun `a long option containing the letter t is not a target-directory`() {
        // `--no-target-directory` put a 't' in the cluster walk of a naive fix
        // and would have hidden the real destination.
        val src = File(root, "src.txt").also { it.writeText("x") }
        assertRefused(
            "cp --no-target-directory <mount>",
            "cp",
            "--no-target-directory",
            src.absolutePath,
            mount.absolutePath,
        )
    }

    // ── legal writes must keep working ──────────────────────────────────────

    @Test
    fun `reading out of a read-only mount is allowed`() {
        val f = File(mount, "note.md").also { it.writeText("hello") }
        val out = File(root, "copy.md")
        assertAllowed("cp <mount>/f /tmp/", "cp", f.absolutePath, out.absolutePath)
        if (delegate != "true") {
            assertTrue("the copy must have happened", out.exists())
            assertEquals("hello", out.readText())
            assertTrue("the source must still be there", f.exists())
        }
    }

    @Test
    fun `a write target outside every mount is allowed`() {
        val src = File(root, "src.txt").also { it.writeText("x") }
        val dir = File(root, "outdir").also { it.mkdirs() }
        assertAllowed("cp -t /tmp/out src", "cp", "-t", dir.absolutePath, src.absolutePath)
        assertAllowed("cp -rt/tmp/out src", "cp", "-rt${dir.absolutePath}", src.absolutePath)
        assertAllowed("cp --target-directory=/tmp/out src", "cp", "--target-directory=${dir.absolutePath}", src.absolutePath)
        assertAllowed("cp -r src dst", "cp", src.absolutePath, File(root, "dst.txt").absolutePath)
    }

    @Test
    fun `mv between two writable paths is allowed`() {
        val a = File(root, "a.txt").also { it.writeText("a") }
        assertAllowed("mv a b", "mv", a.absolutePath, File(root, "b.txt").absolutePath)
    }

    @Test
    fun `touching a writable path is allowed`() {
        assertAllowed("touch file", "touch", File(root, "touched").absolutePath)
    }
}
