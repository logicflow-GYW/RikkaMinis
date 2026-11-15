
package com.rikkaminis.app.sandbox

import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs

// ──────────────────────────────────────────────────────────────────────────
// [fix/memory-hardening-stall] Stall-guard pure helpers (JVM-testable).
// ──────────────────────────────────────────────────────────────────────────

/**
 * A command that produces no output AND burns no CPU anywhere in its process
 * tree for this long is treated as hung (spin, blocked read, stuck network
 * wait) and aborted — see `PersistentShell.executeCommand(stallAfterMs)`.
 * [feat/runtime-sandbox-knobs] The window is now user-tunable
 * ([AgentRuntimeLimitsPrefs.stallNoProgressSec], default 180 s — the literal
 * that used to live here; 0 disables the guard). 180 s sits far below the
 * default 900 s command timeout (a hang used to hold the agent's turn for
 * 15 minutes) and far above any legitimate quiet phase of a working command.
 * Measured context: `rg` under PRoot hung twice on 2026-09-13 and was only
 * stopped by the user tapping interrupt.
 */
internal fun stallNoProgressMs(): Long =
    AgentRuntimeLimitsPrefs.stallNoProgressSec() * 1000L

/** Grace given to the stage-1 SIGINT before the hard kill. */
internal const val STALL_INT_GRACE_MS = 5_000L

/**
 * Stage-1 interrupt signal. `SIGINT` is 2 on Linux; `android.os.Process` only
 * exposes `SIGNAL_KILL`, so the numeric value is spelled out here.
 *
 * The shell is spawned with **pipes**, not a PTY, so the classic "write 0x03
 * to the terminal" trick does NOT work — 0x03 would just be a literal ETX byte
 * fed to the shell as input. A real terminal delivers SIGINT to the FOREGROUND
 * PROCESS GROUP (the command, not the shell); [internalDescendantPidsAtDepth]
 * reproduces that selection and `Process.sendSignal` delivers it.
 */
internal const val SIGNAL_INT = 2

/** Sample the process-tree CPU every N poll ticks (the /proc scan is the
 *  expensive half; 5s granularity is ample for "is it still doing anything"). */
internal const val STALL_CPU_POLL_EVERY_TICKS = 5L

/**
 * Exit code reported when the command outran its `timeout` window —
 * `withTimeoutOrNull` cancels the coroutine while the command keeps running in
 * the PTY, so the coordinator must still reclaim the shell (see
 * [internalShouldReclaimOnExhaustedTimeout]).
 *
 * [audit-0916] Named because the value was spelled as a bare `124` in four
 * places (producer, retry predicate, reclaim predicate, failure classifier).
 * The retry predicate no longer retries it — see [internalShouldRetryCommand].
 */
internal const val TIMEOUT_EXIT_CODE = 124

/**
 * Exit code reported for a command the stall guard had to kill. Distinct from
 * [TIMEOUT_EXIT_CODE] (timeout) and -1 (shell died) so [internalShouldRetryCommand]
 * never auto-retries it — a retry would just hang again for the same reason.
 */
internal const val STALL_EXIT_CODE = 125

/** One process's `(ppid, utime+stime jiffies)` from /proc/<pid>/stat. */
internal data class ProcCpu(
    val ppid: Int,
    val jiffies: Long,
    /** Kernel starttime (field 22); zero means identity is unavailable. */
    val startTimeTicks: Long = 0L,
)

/**
 * Parse a `/proc/<pid>/stat` body into [ProcCpu], or null when malformed.
 *
 * The `comm` field (2nd) is wrapped in parentheses and may itself contain
 * spaces AND parentheses (`(Web Content)`, `(sh)`), so the fields after it are
 * located from the LAST `)` — parsing by splitting the whole line is the
 * classic way to mis-read pid/ppid.
 */
internal fun internalParseProcStat(text: String): ProcCpu? {
    val close = text.lastIndexOf(')')
    if (close < 0 || close + 2 >= text.length) return null
    // After "comm)": state ppid pgrp session tty_nr tpgid flags minflt cminflt
    // majflt cmajflt utime stime …
    val fields = text.substring(close + 2).split(' ')
    if (fields.size < 13) return null
    val ppid = fields[1].toIntOrNull() ?: return null
    val utime = fields[11].toLongOrNull() ?: return null
    val stime = fields[12].toLongOrNull() ?: return null
    val startTimeTicks = fields.getOrNull(19)?.toLongOrNull() ?: 0L
    return ProcCpu(ppid, utime + stime, startTimeTicks)
}

/**
 * Sum [ProcCpu.jiffies] over [rootPid] plus every transitive descendant found
 * in [procs] (pid → stat). Cycles are guarded by a visited set even though a
 * real process tree cannot contain one.
 */
internal fun internalSumTreeCpuJiffies(rootPid: Int, procs: Map<Int, ProcCpu>): Long {
    if (procs.isEmpty()) return 0L
    val children = HashMap<Int, MutableList<Int>>()
    for ((pid, p) in procs) {
        children.getOrPut(p.ppid) { mutableListOf() }.add(pid)
    }
    var total = 0L
    val seen = HashSet<Int>()
    val stack = ArrayDeque<Int>()
    stack.addLast(rootPid)
    while (stack.isNotEmpty()) {
        val pid = stack.removeLast()
        if (!seen.add(pid)) continue
        procs[pid]?.let { total += it.jiffies }
        children[pid]?.let { kids -> for (c in kids) if (c !in seen) stack.addLast(c) }
    }
    return total
}

/**
 * Pure stall decision: has the command shown no progress (no output, no CPU)
 * for at least [thresholdMs]? A non-positive threshold disables the guard, and
 * `lastProgressMs <= 0` means "unknown" (never stalls).
 */
internal fun internalIsStalled(nowMs: Long, lastProgressMs: Long, thresholdMs: Long): Boolean =
    thresholdMs > 0L && lastProgressMs > 0L && nowMs - lastProgressMs >= thresholdMs

/**
 * Pids of every descendant of [rootPid] at depth ≥ [minDepth] (children of the
 * root are depth 1), used to pick the processes to SIGINT when a command hangs.
 *
 * Depth ≥ 2 is deliberate: under the persistent shell the root is the PRoot
 * tracer, its direct child is the long-lived `/bin/sh`, and the running command
 * is the shell's child. In a real terminal Ctrl-C hits the foreground process
 * group — the command — and leaves the shell standing, which is exactly this
 * selection.
 */
internal fun internalDescendantPidsAtDepth(
    rootPid: Int,
    procs: Map<Int, ProcCpu>,
    minDepth: Int,
): List<Int> {
    if (procs.isEmpty()) return emptyList()
    val children = HashMap<Int, MutableList<Int>>()
    for ((pid, p) in procs) {
        children.getOrPut(p.ppid) { mutableListOf() }.add(pid)
    }
    val out = ArrayList<Int>()
    val seen = HashSet<Int>()
    var frontier = listOf(rootPid)
    var depth = 0
    while (frontier.isNotEmpty()) {
        val next = ArrayList<Int>()
        for (pid in frontier) {
            children[pid]?.let { kids ->
                for (c in kids) {
                    if (!seen.add(c)) continue
                    next.add(c)
                    if (depth + 1 >= minDepth) out.add(c)
                }
            }
        }
        frontier = next
        depth++
        if (depth > 64) break // defensive: a malformed ppid map can't spin forever
    }
    return out
}

/**
 * Signal a shell's snapshot descendants deepest-first, then its tracer root.
 * Process.destroyForcibly() is not a reliable SIGKILL on every Android ROM:
 * on the verified device it sent SIGTERM and a blocked tracee kept the whole
 * PRoot tree alive after the caller had already reported a timeout.
 *
 * Reuse the stall guard's tree snapshot instead of another process registry.
 * Verify kernel starttime immediately before each signal to reduce PID-reuse
 * risk. Unknown identities are refused; Java's Process teardown remains the
 * fallback. Matching descendants may have been reparented since the snapshot,
 * but are still the same owned tasks. Signal/wait is not atomic without pidfd.
 * [signalPid] supplies Android's explicit SIGNAL_KILL in production.
 */
internal fun internalKillProcessTree(
    rootPid: Int,
    procs: Map<Int, ProcCpu>,
    currentProcess: (Int) -> ProcCpu?,
    rootIsAlive: () -> Boolean,
    signalPid: (Int) -> Unit,
): Int {
    if (rootPid <= 1 || procs[rootPid]?.startTimeTicks?.takeIf { it > 0L } == null) return 0
    if (!runCatching { rootIsAlive() }.getOrDefault(false)) return 0
    val descendants = internalDescendantPidsAtDepth(rootPid, procs, minDepth = 1)
        .asReversed().filter { it > 1 && it != rootPid }.distinct()
    var sent = 0
    for (pid in descendants + rootPid) {
        val expected = procs[pid]?.startTimeTicks?.takeIf { it > 0L } ?: continue
        val current = runCatching { currentProcess(pid) }.getOrNull() ?: continue
        if (current.startTimeTicks != expected) continue
        if (pid == rootPid && !runCatching { rootIsAlive() }.getOrDefault(false)) continue
        if (runCatching { signalPid(pid) }.isSuccess) sent++
    }
    return sent
}

/**
 * Message appended to the output of a command the stall guard interrupted, so
 * the agent knows why its command came back early instead of assuming the task
 * completed.
 */
internal fun stallMessage(thresholdMs: Long): String =
    "\n[stall-guard] no output and no CPU progress for ${thresholdMs / 1000}s — " +
        "the command was interrupted and its process tree was killed. If it was " +
        "waiting for input, pass explicit arguments/flags; if it was waiting on " +
        "something slow, emit progress or raise the timeout instead of blocking " +
        "silently."
