package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the stall-guard pure helpers in [PersistentShellStall] — the
 * policy that kills a command which shows no output AND no CPU progress for
 * [STALL_NO_PROGRESS_MS] (2026-09-13: `rg` hung twice in the PRoot sandbox and
 * was only stopped by the user tapping interrupt).
 *
 * [PersistentShell] itself depends on Android (Context, ProcessBuilder, PTY),
 * so the process/PTY orchestration is verified by inspection; everything with a
 * decision inside it is a pure function exercised here.
 */
class PersistentShellStallTest {

    // ── /proc/<pid>/stat parsing ──────────────────────────────────────

    private fun statLine(comm: String, ppid: Int, utime: Long, stime: Long): String =
        // pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt
        // majflt cmajflt utime stime …
        "42 ($comm) S $ppid 42 42 0 -1 4194560 100 0 0 0 $utime $stime 0 0 20 0 1 0 12345 6789"

    @Test
    fun `parse proc stat reads ppid and sums utime plus stime`() {
        val parsed = internalParseProcStat(statLine("sh", ppid = 1200, utime = 5, stime = 3))
        assertEquals(ProcCpu(ppid = 1200, jiffies = 8), parsed)
    }

    @Test
    fun `parse proc stat handles comm with spaces and parentheses`() {
        // A real WebView/PRoot child is literally named "(Web Content)" — the
        // fields after it must be located from the LAST ')' or ppid/utime shift.
        val parsed = internalParseProcStat(statLine("Web Content)", ppid = 7, utime = 11, stime = 1))
        assertEquals(ProcCpu(ppid = 7, jiffies = 12), parsed)
    }

    @Test
    fun `parse proc stat rejects malformed bodies`() {
        assertNull(internalParseProcStat(""))
        assertNull(internalParseProcStat("42 sh S 1 2 3"))                 // no comm parens
        assertNull(internalParseProcStat("42 (sh) S 1 2 3"))               // too few fields
        assertNull(internalParseProcStat("42 (sh) S notanint 2 3 0 -1 0 0 0 0 0 5 3 0 0 0 0"))
    }

    // ── process-tree CPU summation ────────────────────────────────────

    @Test
    fun `tree cpu sums the root and every transitive descendant`() {
        val procs = mapOf(
            100 to ProcCpu(ppid = 1, jiffies = 10),      // tracer
            200 to ProcCpu(ppid = 100, jiffies = 5),     // /bin/sh
            300 to ProcCpu(ppid = 200, jiffies = 400),   // the busy command
            400 to ProcCpu(ppid = 300, jiffies = 7),     // its child
            999 to ProcCpu(ppid = 1, jiffies = 9999),    // unrelated process
        )
        assertEquals(10 + 5 + 400 + 7, internalSumTreeCpuJiffies(100, procs))
    }

    @Test
    fun `tree cpu of an unknown root is zero`() {
        assertEquals(0L, internalSumTreeCpuJiffies(555, mapOf(100 to ProcCpu(1, 10))))
        assertEquals(0L, internalSumTreeCpuJiffies(100, emptyMap()))
    }

    @Test
    fun `tree cpu tolerates a parent cycle without looping forever`() {
        // Not reachable in a real process tree, but a truncated /proc read can
        // pair two pids in either direction; the walk must still terminate.
        val procs = mapOf(
            100 to ProcCpu(ppid = 200, jiffies = 1),
            200 to ProcCpu(ppid = 100, jiffies = 2),
        )
        assertEquals(3L, internalSumTreeCpuJiffies(100, procs))
    }

    @Test
    fun `tree cpu counts a deep chain`() {
        val procs = (0 until 50).associate { i ->
            val pid = 100 + i
            pid to ProcCpu(ppid = if (i == 0) 1 else pid - 1, jiffies = 1L)
        }
        assertEquals(50L, internalSumTreeCpuJiffies(100, procs))
    }

    // ── the stall decision ────────────────────────────────────────────

    @Test
    fun `not stalled before the threshold`() {
        assertFalse(internalIsStalled(nowMs = 179_000, lastProgressMs = 1_000, thresholdMs = 180_000))
    }

    @Test
    fun `stalled exactly at the threshold`() {
        assertTrue(internalIsStalled(nowMs = 181_000, lastProgressMs = 1_000, thresholdMs = 180_000))
    }

    @Test
    fun `progress resets the clock`() {
        assertFalse(internalIsStalled(nowMs = 500_000, lastProgressMs = 499_000, thresholdMs = 180_000))
    }

    @Test
    fun `a non-positive threshold disables the guard`() {
        assertFalse(internalIsStalled(nowMs = 10_000_000, lastProgressMs = 1_000, thresholdMs = 0))
    }

    @Test
    fun `unknown progress never stalls`() {
        assertFalse(internalIsStalled(nowMs = 10_000_000, lastProgressMs = 0, thresholdMs = 180_000))
    }

    @Test
    fun `command pids select the shell's children and deeper, never the shell`() {
        // tracer 100 → /bin/sh 200 → command 300 → its child 400.
        // A terminal's Ctrl-C hits the foreground group (the command), not the
        // shell, so depth ≥ 2 from the tracer is exactly the right selection.
        val procs = mapOf(
            100 to ProcCpu(ppid = 1, jiffies = 10),      // PRoot tracer
            200 to ProcCpu(ppid = 100, jiffies = 5),     // persistent /bin/sh
            300 to ProcCpu(ppid = 200, jiffies = 400),   // the running command
            400 to ProcCpu(ppid = 300, jiffies = 7),     // its own child
            999 to ProcCpu(ppid = 1, jiffies = 9999),    // unrelated process
        )
        val pids = internalDescendantPidsAtDepth(100, procs, minDepth = 2).sorted()
        assertEquals(listOf(300, 400), pids)
    }

    @Test
    fun `command pids are empty when only the tracer and shell exist`() {
        val procs = mapOf(
            100 to ProcCpu(ppid = 1, jiffies = 10),
            200 to ProcCpu(ppid = 100, jiffies = 5),
        )
        assertTrue(internalDescendantPidsAtDepth(100, procs, minDepth = 2).isEmpty())
    }

    @Test
    fun `command pids tolerate cycles and unknown roots`() {
        val procs = mapOf(
            100 to ProcCpu(ppid = 200, jiffies = 1),
            200 to ProcCpu(ppid = 100, jiffies = 2),
        )
        assertTrue(internalDescendantPidsAtDepth(100, procs, minDepth = 1).isNotEmpty())
        assertTrue(internalDescendantPidsAtDepth(777, procs, minDepth = 1).isEmpty())
        assertTrue(internalDescendantPidsAtDepth(100, emptyMap(), minDepth = 1).isEmpty())
    }

    // ── the message the agent sees ────────────────────────────────────

    @Test
    fun `stall message states the window and tells the agent what to do`() {
        val msg = stallMessage(STALL_NO_PROGRESS_MS)
        assertTrue(msg.contains("180s"))
        assertTrue(msg.contains("interrupted"))
        assertTrue(msg.contains("timeout"))
    }

    @Test
    fun `stall exit code stays distinct from timeout and shell-death codes`() {
        assertEquals(125, STALL_EXIT_CODE)
        assertFalse(STALL_EXIT_CODE == 124)
        assertFalse(STALL_EXIT_CODE == -1)
    }
}
