package com.rikkaminis.app.sandbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interactive PTY shell session backed by the Termux terminal emulator engine
 * (com.termux.terminal). Replaces the hand-rolled [PtyBridge] + TerminalEmulator
 * stack with industry-standard ANSI/CSI/OSC parsing, TUI compatibility, and
 * mature keyboard / text-selection handling.
 *
 * The Termux-backed TerminalScreen (Layer 4) reads directly from the Termux
 * TerminalView, which is driven by the [termuxSession] below.
 *
 * [sweep1014-B18] The legacy raw-byte delta flow (`outputBytes`) was removed:
 * a repo-wide grep found no consumer of it (the `outputBytes` hits in
 * LLMProvider / ProviderRssProbe are unrelated token-usage fields), and
 * `lastTranscriptLength` — kept "so we can emit delta bytes on text changes" —
 * was assigned but never read. Dead code that implied a live output bridge
 * that no longer exists.
 */
class TerminalSession(private val context: Context) {

    companion object {
        private const val TAG = "TerminalSession"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24

        /**
         * [meta-audit-1028 E3 / F-13] How often a deferred env broadcast
         * re-checks whether the shell is back at an idle prompt. One /proc
         * children read per second is negligible next to a terminal running.
         */
        private const val ENV_BROADCAST_POLL_MS = 1_000L

        /**
         * [meta-audit-1028 E3 / F-13] Longest a broadcast may be deferred.
         * A TUI/build can legitimately own the PTY for hours; typing into it
         * is unacceptable at any point, so past this budget the update is
         * dropped and logged instead.
         */
        private const val ENV_BROADCAST_MAX_DEFER_MS = 10 * 60 * 1000L

        /** 0x15 — Ctrl-U, "kill the current input line" in readline / ash. */
        private const val CTRL_U: Byte = 0x15

        /**
         * Weak-reference registry of live sessions so the Application can push
         * environment updates (e.g. ACTION_TIMEZONE_CHANGED) into already-running
         * interactive shells. Stale weak refs are cleaned on each iteration.
         */
        private val liveSessions = CopyOnWriteArrayList<WeakReference<TerminalSession>>()

        /** Broadcast `export TZ=<value>` to every live interactive shell. */
        fun broadcastTimezone(tz: String) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyTimezone(tz)
            }
            liveSessions.removeAll(dead)
        }

        /** Broadcast the HTTP-proxy env block to every live interactive shell. */
        fun broadcastProxy(env: Map<String, String>) {
            val dead = mutableListOf<WeakReference<TerminalSession>>()
            for (ref in liveSessions) {
                val s = ref.get()
                if (s == null) { dead.add(ref); continue }
                if (s.isRunning) s.applyEnvMap(env)
            }
            liveSessions.removeAll(dead)
        }
    }

    enum class State { IDLE, BOOTING, RUNNING, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Counter bumped on every clearOutput() — lets UI wipe emulator state. */
    private val _clearVersion = MutableStateFlow(0)
    val clearVersion: StateFlow<Int> = _clearVersion.asStateFlow()

    // --- Termux engine internals ---

    /** Attached by [start]; used by [TerminalScreen] to wire its TerminalView. */
    internal var termuxSession: com.termux.terminal.TerminalSession? = null
        private set

    /** The TerminalView currently rendering this session (if any). */
    @Volatile
    private var attachedView: TerminalView? = null

    /** Marshals output-redraw callbacks from the emulator thread to the UI thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * [T3-M1] Monotonic token guarding against a start() that outlives its
     * stop() (or a later start()). Both [start] and [stop] bump it; the boot
     * coroutine bails out whenever the value it captured is stale.
     */
    private val startGeneration = AtomicInteger(0)

    @Volatile private var cols: Int = DEFAULT_COLS
    @Volatile private var rows: Int = DEFAULT_ROWS

    val isRunning: Boolean get() = _state.value == State.RUNNING

    // ──────────────────────────────────────────────
    //  Start / Stop
    // ──────────────────────────────────────────────

    /**
     * Start the PTY-backed shell via Termux engine.
     * Call [setWindowSize] first if you know the target geometry.
     *
     * Termux [com.termux.terminal.TerminalSession] creates the PTY internally
     * from the supplied executable path and arguments — we pass PRoot as the
     * executable with the same args the legacy [buildInteractiveCommand] built.
     */
    fun start(sessionId: String? = null, initialCols: Int = DEFAULT_COLS, initialRows: Int = DEFAULT_ROWS) {
        // [meta1028 D3-F02] A RUNNING PTY whose rootfs was deleted and
        // re-extracted underneath it (`RootfsManager.reset()` → hook →
        // `bootGate.invalidate()`) has to be torn down, not kept: its whole
        // process tree is bound to the tree that no longer exists, and the
        // early return just below would leave it there until the app restarts.
        // `!isBooted` is the only signal for "the tree changed under you" — it
        // is true for every PTY that started normally, so this cannot fire on a
        // healthy session.
        if (_state.value == State.RUNNING && !PRootKernel.isBooted) {
            Log.i(TAG, "rootfs rebuilt under a running PTY — restarting the terminal")
            stop()
        }
        if (_state.value == State.RUNNING) return
        _state.value = State.BOOTING
        cols = initialCols
        rows = initialRows
        val myGeneration = startGeneration.incrementAndGet()

        scope.launch {
            // [1042-A-03] The PTY THIS attempt created, if it got that far.
            // Declared outside the try so the failure handler can compare the
            // slot against a value captured at creation time. The old handler
            // self-bound the comparison (`termuxSession?.let { s -> if
            // (termuxSession === s) … }`), which is a tautology: `s` IS
            // `termuxSession`, so the "ownership" test always passed and the
            // handler killed a newer attempt's PTY and cleared its state.
            var createdSession: com.termux.terminal.TerminalSession? = null
            try {
                PRootKernel.boot(context)

                // [T3-M1] stop() does not cancel this coroutine, so without the
                // generation check a stop() (or a fresh start()) during boot
                // would still spawn a PTY afterwards — the ghost process tree
                // that killTermuxProcessTree's own docs warn about.
                if (myGeneration != startGeneration.get()) {
                    Log.i(TAG, "start() superseded during boot — aborting")
                    return@launch
                }

                // [T3-M2] Deliberately NO putAll of env vars into
                // PRootKernel.customEnvironment. That map is process-global and
                // never pruned, so a variable the user later deleted lingered
                // and was re-inherited by every subsequent PTY (buildTermuxEnv
                // below) and every agent shell (PersistentShell env loop) —
                // deleted secrets came back to life. The terminal reads its env
                // straight from the repository in buildTermuxEnv(); the agent
                // shell gets a full snapshot per command via
                // PersistentShell.applyEnvironment().
                val rootfsManager = RootfsManager.getInstance(context)
                val proot = rootfsManager.prootBinary.absolutePath
                val filesDir = context.filesDir.absolutePath

                // Build PRoot arguments (mirrors legacy buildInteractiveCommand).
                // Pass the proot binary path so it lands in argv[0] — critical
                // for PRoot's option parsing (see buildTermuxArgs docs).
                val args = buildTermuxArgs(sessionId, rootfsManager, proot)
                val env = buildTermuxEnv(rootfsManager, sessionId)

                val client = TermuxSessionClient()
                val session = com.termux.terminal.TerminalSession(
                    proot,
                    filesDir,
                    args.toTypedArray(),
                    env.toTypedArray(),
                    2_000, // scrollback rows — match RikkaHub default
                    client,
                )
                createdSession = session
                session.updateSize(cols, rows)

                // [1044-A02] Superseded before attach: reap the PTY and NOTHING
                // else. The bump that just invalidated this attempt was made by
                // the party that now owns `_state` — stop() publishes STOPPED,
                // a newer start() publishes BOOTING — so any write here lands on
                // top of the newer attempt's state and pins the screen to
                // STOPPED while a fresh PTY is booting (the reported symptom).
                // There is nothing else to settle at this checkpoint either: the
                // slot is not assigned yet (below) and liveSessions holds no
                // entry for this attempt.
                if (myGeneration != startGeneration.get()) {
                    Log.i(TAG, "start() superseded before attach — killing PTY")
                    killTermuxProcessTree(session)
                    return@launch
                }

                termuxSession = session
                liveSessions.add(WeakReference(this@TerminalSession))
                Log.i(TAG, "Termux PTY started: cols=$cols rows=$rows sessionId=$sessionId")

                // If a session is active, cd into its workspace so the user lands
                // in a familiar directory (mirrors legacy behaviour).
                //
                // [da1032-D] This preamble MUST reach the PTY before anything
                // else is typed into it, so it is written while the state is
                // still BOOTING. It used to run 300 ms *after*
                // `_state.value = State.RUNNING`, while the screen prefilled
                // `initCommand` a fixed 500 ms after start(): only 200 ms of
                // margin against `PRootKernel.boot()`. Whenever boot overran
                // that, the prefill bytes were queued FIRST and the two writes
                // coalesced into a single input line —
                // `<prefill>cd /var/minis && clear` + CR — which the interactive
                // shell then EXECUTED as one command (the prefill is only ever
                // supposed to be visible text, never a command), and `clear`
                // wiped the screen on top of that. Sanitising the prefill
                // cannot prevent this: the CR that submits the line belongs to
                // this very write.
                if (sessionId != null) {
                    kotlinx.coroutines.delay(300)
                    val initCmd = "cd /var/minis && clear\r".toByteArray()
                    session.write(initCmd, 0, initCmd.size) // (text, offset, length)
                }

                // [da1032-D] RUNNING is published only NOW, so its meaning
                // upgrades from "the PTY process exists" to "the PTY is ready —
                // the optional cd/clear preamble has been injected". Consumers
                // gate on it before typing (the screen's prefill, the
                // AndroidView attach), which is what makes the preamble the
                // first thing the shell reads instead of racing it.
                //
                // The generation is re-checked first for the same reason as the
                // two earlier guards: stop() cannot cancel this coroutine, so a
                // teardown (or a newer start()) during the 300 ms delay above
                // must not resurrect an already-killed PTY as RUNNING.
                // [1044-A02] Same rule, plus one: the shared slot and the
                // registry are released ONLY while they still point at the PTY
                // THIS attempt created. An unguarded `termuxSession = null` here
                // clears whatever a newer start() has already installed — that
                // newer attempt writes the slot before this checkpoint can run
                // again — which orphans a live bash tree the UI can neither
                // write to nor resize, and strands its liveSessions entry. Same
                // ownership rule as the catch handler below and
                // onSessionFinished (compare against a value captured at
                // creation, never against the slot itself).
                //
                // No `_state` write for the reason spelled out at the checkpoint
                // above: the generation bumper published the state.
                if (myGeneration != startGeneration.get()) {
                    Log.i(TAG, "start() superseded before RUNNING — killing PTY")
                    killTermuxProcessTree(session)
                    if (termuxSession === session) {
                        termuxSession = null
                        liveSessions.removeAll {
                            it.get() === this@TerminalSession || it.get() == null
                        }
                    }
                    return@launch
                }
                _state.value = State.RUNNING
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start Termux PTY session", t)
                // [audit-0917] Kill and release a PTY that was already assigned
                // before the failure. The exception can come from the cd-write
                // or the delay above, i.e. AFTER termuxSession was set — the
                // old handler only flipped the state, orphaning a live shell
                // process that nothing would ever reap.
                //
                // [1042-A-03] Reaping stays keyed on the PTY THIS attempt
                // created (`createdSession`), not on the slot: a newer start()
                // can already have installed its own PTY while this coroutine
                // was failing, and killing what the slot points at (which the
                // old `termuxSession?.let { s -> if (termuxSession === s) … }`
                // self-bound form did — `s` IS `termuxSession`, so the guard
                // was a tautology) would kill the NEW session.
                //
                // The shared-state settlement (slot null, liveSessions
                // unregister, STOPPED publish) is gated the same way: only the
                // owner of the current slot may touch it. `null === null`
                // covers "this attempt never attached and the slot is still
                // empty"; the `else if` covers "this attempt died before
                // attaching, the slot is empty, and nobody has superseded it"
                // (a newer start()/stop() bumps startGeneration, so an
                // unchanged generation means this attempt is still the one
                // responsible for settling the BOOTING state it published).
                createdSession?.let { s ->
                    runCatching { killTermuxProcessTree(s) }
                        .onFailure { Log.w(TAG, "cleanup after start failure: ${it.message}") }
                }
                if (termuxSession === createdSession) {
                    termuxSession = null
                    liveSessions.removeAll { it.get() === this@TerminalSession || it.get() == null }
                    _state.value = State.STOPPED
                } else if (termuxSession == null && startGeneration.get() == myGeneration) {
                    _state.value = State.STOPPED
                }
            }
        }
    }

    fun stop() {
        // [T3-M1] Invalidate any in-flight start() so it can neither attach a
        // PTY after teardown nor clobber a newer start.
        startGeneration.incrementAndGet()
        val s = termuxSession
        termuxSession = null
        attachedView = null
        if (s != null) {
            killTermuxProcessTree(s)
        }
        if (_state.value != State.STOPPED) _state.value = State.STOPPED
        liveSessions.removeAll { it.get() === this || it.get() == null }
        // [F-13] A staged broadcast belongs to the PTY that just died; start()
        // re-derives TZ/proxy from their single sources.
        synchronized(pendingEnv) { pendingEnv.clear() }
        Log.i(TAG, "TerminalSession stopped")
    }

    /** The PRoot pid backing the given Termux session, via reflection. */
    private fun termuxShellPid(s: com.termux.terminal.TerminalSession): Int? = try {
        val f = s.javaClass.getDeclaredField("mShellPid")
        f.isAccessible = true
        f.getInt(s).takeIf { it > 0 }
    } catch (_: Throwable) { null }

    /**
     * Enumerate the full descendant subtree of [pid] by walking the
     * task children entries under /proc. Returns children-first
     * (leaf) order.
     */
    private fun collectDescendants(pid: Int): List<Int> {
        val seen = mutableSetOf<Int>()
        val out = mutableListOf<Int>()
        fun walk(p: Int) {
            if (!seen.add(p)) return
            for (c in readChildPids(p)) { out.add(c); walk(c) }
        }
        walk(pid)
        return out
    }

    /**
     * Direct children of [p], read from every `/proc/<p>/task/<tid>/children`.
     *
     * [meta-audit-1028 E3 / F-13] Extracted from [collectDescendants] so the
     * prompt-state probe ([internalTerminalPromptState]) walks the SAME way the
     * kill path does — one reader, two consumers, no second dialect of /proc.
     */
    private fun readChildPids(p: Int): List<Int> = try {
        File("/proc/$p/task").listFiles()?.flatMap { tidDir ->
            runCatching {
                File(tidDir, "children").readText().trim()
                    .split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt() }
            }.getOrElse { emptyList() }
        } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * Kill the entire PRoot tree owned by this Termux session, not just the
     * tracer itself. [com.termux.terminal.TerminalSession.finishIfRunning]
     * only SIGKILLs mShellPid (the PRoot tracer); its children (bash, and
     * anything bash spawned) survive as orphans whose native memory still
     * counts against this app's process group. Rapid open/close cycles then
     * accumulate orphaned trees and exhaust the Scudo allocator
     * (SIGABRT "Can't populate more pages" — the OOM crashes the user is
     * seeing). Kill leaves first, then the root, and fall back to Termux's
     * own finish for the root's cleanup path.
     *
     * Three layered strategies (each is best-effort):
     *  1. /proc children walk — kills descendants individually (leaves
     *     first, so nobody gets reparented into a zombie gap).
     *  2. Explicit SIGKILL (Process.sendSignal) on the tracer root itself —
     *     redundant belt-and-braces in case the walk missed a reparented
     *     process.
     *  3. finishIfRunning() — Termux's own teardown (closes ByteQueues,
     *     JNI fds; wait-thread cleanupResources runs cleanly).
     */
    private fun killTermuxProcessTree(s: com.termux.terminal.TerminalSession) {
        val pid = termuxShellPid(s)
        if (pid != null) {
            // (1) Descendant walk via /proc — best-effort.
            try {
                val tree = listOf(pid) + collectDescendants(pid)
                for (p in tree.asReversed()) {
                    runCatching { android.os.Process.killProcess(p) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "killTermuxProcessTree walk failed: ${t.message}")
            }
            // (2) Also signal-kill the tracer directly (Process.killProcess does the
            // same under the hood, but keep the explicit sendSignal as a
            // redundant belt-and-braces — e.g. if the walk in (1) missed a
            // reparented process, this still nails the root).
            runCatching {
                android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
            }
        }
        // (3) Termux's own teardown — closes the ByteQueues/JNI fds and
        // triggers the wait-thread cleanupResources for a clean close.
        runCatching { s.finishIfRunning() }
    }

    /**
     * Hand the Android TerminalView to this session so output callbacks
     * ([TermuxSessionClient.onTextChanged]) can redraw it. Called from
     * [TerminalScreen] both on first composition and whenever the view is
     * re-attached. If the PTY already booted, attach and size it immediately.
     */
    fun attachView(view: TerminalView) {
        attachedView = view
        val session = termuxSession
        if (session != null && view.mTermSession != session) {
            view.attachSession(session)
            view.updateSize()
        }
    }

    // ──────────────────────────────────────────────
    //  Input
    // ──────────────────────────────────────────────

    /** Send raw bytes to the PTY (keystrokes, control codes, etc.). */
    fun sendRawBytes(bytes: ByteArray) {
        val s = termuxSession ?: return
        if (bytes.isEmpty()) return
        s.write(bytes, 0, bytes.size)
    }

    /**
     * Send a UTF-8 string to the PTY.
     *
     * Unicode (CJK, emoji) is UTF-8 encoded verbatim. Line endings (`\r\n` and
     * bare `\n`) are collapsed to `\r` so the TTY's ICRNL termios flag maps
     * them to newline as usual.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        val normalized = normalizeLineEndings(text)
        sendRawBytes(normalized.toByteArray(Charsets.UTF_8))
    }

    /** Legacy — same as [sendText] + CR. */
    @Deprecated("Use sendText / sendRawBytes instead — real TTY doesn't line-buffer.")
    fun sendInput(text: String) {
        sendRawBytes((normalizeLineEndings(text) + "\r").toByteArray(Charsets.UTF_8))
    }

    /** Send SIGINT (Ctrl+C, 0x03). */
    fun sendInterrupt() {
        sendRawBytes(byteArrayOf(0x03))
    }

    // ──────────────────────────────────────────────
    //  Window size
    // ──────────────────────────────────────────────

    fun setWindowSize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        termuxSession?.updateSize(newCols, newRows)
    }

    // ──────────────────────────────────────────────
    //  Output control
    // ──────────────────────────────────────────────

    /** Signal UI to wipe the emulator. Sends Ctrl+L (form feed 0x0C) — the
     *  shell's native clear-screen shortcut — so the TTY redraws the prompt. */
    fun clearOutput() {
        _clearVersion.value = _clearVersion.value + 1
        // 0x0C = Ctrl+L. The old ESC c (RIS) got split by readline into
        // meta-prefix + literal "c", printing a stray "c" on the input line.
        sendRawBytes(byteArrayOf(0x0C))
    }

    // ──────────────────────────────────────────────
    //  Env broadcasts
    // ──────────────────────────────────────────────

    // [meta-audit-1028 E3 / F-13] A broadcast is a top-level `export` block
    // typed INTO the PTY. Writing it unconditionally — what this class used to
    // do — corrupts whatever owns the terminal at that instant:
    //   * a half-typed command at the prompt (`git commit -m "…`): the export
    //     text is appended to it and the injected `\r` SUBMITS the joined
    //     line, so a command the user never wrote runs;
    //   * `vim` / `nano` / a REPL: the export lines are keyboard input and land
    //     in the file buffer or the interpreter.
    // [PersistentShell] hit the same defect ([audit-1015 BUG-07]) and stages the
    // update until no command is in flight. A terminal has no command boundary
    // to hook, so the equivalent is two guards:
    //   1. DEFER while a foreground child owns the PTY; an unknowable state also
    //      defers (fail closed — never inject blind);
    //   2. once the shell IS at a prompt, send Ctrl-U first so a partially
    //      typed line is discarded instead of being executed together with the
    //      export block.
    // Escaping was never the broken half (the POSIX `'\''` idiom was already
    // here); this is about WHEN the bytes are written.

    /** Broadcasts waiting for an idle prompt; per-key last-write-wins. */
    private val pendingEnv = LinkedHashMap<String, String>()

    /** One pump coroutine at a time — coalesces a burst of broadcasts. */
    private var envPumpRunning = false

    private fun applyTimezone(tz: String) = queueEnvUpdate(mapOf("TZ" to tz))

    private fun applyEnvMap(env: Map<String, String>) = queueEnvUpdate(env)

    private fun queueEnvUpdate(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        synchronized(pendingEnv) { pendingEnv.putAll(entries) }
        startEnvPump()
    }

    private fun startEnvPump() {
        val start = synchronized(pendingEnv) {
            if (envPumpRunning || pendingEnv.isEmpty()) {
                false
            } else {
                envPumpRunning = true
                true
            }
        }
        if (start) scope.launch { pumpEnvUpdates() }
    }

    private fun drainPendingEnv(): Map<String, String>? = synchronized(pendingEnv) {
        if (pendingEnv.isEmpty()) {
            null
        } else {
            val out = LinkedHashMap(pendingEnv)
            pendingEnv.clear()
            out
        }
    }

    /**
     * Apply the staged broadcast as soon as the shell is back at a prompt.
     *
     * Bounded by [ENV_BROADCAST_MAX_DEFER_MS]: a terminal can sit inside a
     * long-running TUI/build for hours, and dropping the update is the lesser
     * evil compared to typing six `export` lines into it. Dropping is logged
     * (`Log.w`) — and it degrades to "the running terminal keeps the previous
     * TZ/proxy" which the next session start repairs, because
     * [buildTermuxEnv] re-derives both from their single sources.
     */
    private suspend fun pumpEnvUpdates() {
        try {
            var waitedMs = 0L
            while (true) {
                if (_state.value == State.STOPPED) {
                    // PTY gone: start() rebuilds the whole env array anyway.
                    synchronized(pendingEnv) { pendingEnv.clear() }
                    return
                }
                if (isRunning && shellPromptState() == TerminalPromptState.IDLE_AT_PROMPT) {
                    val entries = drainPendingEnv() ?: return
                    val script = internalBuildTerminalEnvScript(entries)
                    if (script.isNotEmpty()) {
                        // Ctrl-U: kill the line the user may have half typed
                        // (see the block comment above). Only reached when the
                        // shell provably has no foreground child, so this
                        // cannot reach an editor's insert buffer.
                        sendRawBytes(byteArrayOf(CTRL_U))
                        sendRawBytes(script.toByteArray(Charsets.UTF_8))
                        Log.i(TAG, "Env broadcast applied at prompt: ${entries.size} key(s)")
                    }
                    return
                }
                if (waitedMs >= ENV_BROADCAST_MAX_DEFER_MS) {
                    Log.w(
                        TAG,
                        "Env broadcast dropped after ${ENV_BROADCAST_MAX_DEFER_MS}ms without an idle " +
                            "prompt: ${pendingEnv.size} key(s) withheld (the PTY owns a foreground " +
                            "program; injecting there would be read as keyboard input)",
                    )
                    synchronized(pendingEnv) { pendingEnv.clear() }
                    return
                }
                kotlinx.coroutines.delay(ENV_BROADCAST_POLL_MS)
                waitedMs += ENV_BROADCAST_POLL_MS
            }
        } finally {
            // Re-arm under the same lock the queue takes: an entry that landed
            // while the last block was being written must not be stranded.
            val restart = synchronized(pendingEnv) {
                envPumpRunning = false
                pendingEnv.isNotEmpty()
            }
            if (restart) startEnvPump()
        }
    }

    /** Prompt state of the live PTY (semantics: [internalTerminalPromptState]). */
    private suspend fun shellPromptState(): TerminalPromptState = withContext(Dispatchers.IO) {
        val s = termuxSession ?: return@withContext TerminalPromptState.UNKNOWN
        val pid = termuxShellPid(s) ?: return@withContext TerminalPromptState.UNKNOWN
        internalTerminalPromptState(pid) { p -> readChildPids(p) }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun normalizeLineEndings(text: String): String {
        return internalNormalizeLineEndings(text)
    }

    /**
     * Build the PRoot argument list for Termux TerminalSession.
     *
     * IMPORTANT: argv[0] must be the program name (the proot binary itself).
     * Termux's JNI does `execvp(cmd, argv)` where `argv[0]` is argv[0] of the
     * exec'd program — PRoot's `parse_config` starts parsing at `argv[1]`.
     * If we omitted the proot path here (i.e. started the list directly with
     * "-0"), then "-0" would land in argv[0] and never be parsed, the fake-id
     * root would silently fail, and bash's PS1 `\u` would resolve to
     * "I have no name!" because the app uid (11576) isn't in /etc/passwd.
     * This mirrors upstream TermuxSession where `arguments[0] = processName`.
     */
    private fun buildTermuxArgs(
        sessionId: String?,
        rootfsManager: RootfsManager,
        proot: String,
    ): List<String> {
        val args = mutableListOf<String>()

        // argv[0] → program name. Do NOT reorder this before proot.
        args.add(proot)
        args.add("-0")
        args.add("--link2symlink")
        args.add("--kill-on-exit")
        args.add("-r"); args.add(rootfsManager.rootfsDir.absolutePath)
        args.add("-b"); args.add("/dev")
        args.add("-b"); args.add("/proc")
        args.add("-b"); args.add("/sys")
        args.add("-w"); args.add("/root")

        // Bind mounts — global + per-session overlay.
        val mounts = PRootKernel.bindMounts.toMutableMap()
        if (sessionId != null) {
            val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
            for (subdir in listOf("attachments", "offloads", "workspace", "browser")) {
                // [sweep1014-B18] mkdirs + mount unconditionally (mirrors
                // ExecutionCoordinator's agent-shell bind, the same pattern).
                // The old `if (hostDir.exists())` guard silently fell back to
                // the global map when the session subdir had never been written
                // (session subdirs are lazy — created on first write), so a
                // fresh session's terminal bound /var/minis/workspace to
                // ANOTHER session's directory.
                val hostDir = File(sessionBase, subdir).also { it.mkdirs() }
                mounts["/var/minis/$subdir"] = hostDir.absolutePath
            }
        }
        for ((linuxPath, hostPath) in mounts) {
            args.add("-b"); args.add("$hostPath:$linuxPath")
        }

        // Native offload socket.
        val handlers = OffloadHandlerCatalog.allHandlerNames
        if (handlers.isNotEmpty()) {
            args.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        // Login + interactive shell.
        // Prefer bash (readline, Ctrl+C-at-prompt semantics), but fall back
        // to busybox ash when bash or its dynamic deps are missing/corrupt.
        // A broken bash must never take the whole terminal down — ash's
        // Ctrl+C SIGINT quirk (ash exits at the prompt, bash just clears the
        // line) is a minor UX regression vs a terminal that can't start.
        val bashFile = File(rootfsManager.rootfsDir, "bin/bash")
        val shell = if (bashFile.exists() && bashFile.canExecute()) {
            "/bin/bash"
        } else {
            Log.w(TAG, "/bin/bash unavailable — falling back to /bin/sh")
            "/bin/sh"
        }
        args.add(shell)
        args.add("-l")
        args.add("-i")
        return args
    }

    /** Build the environment array for Termux TerminalSession. */
    private fun buildTermuxEnv(rootfsManager: RootfsManager, sessionId: String? = null): List<String> {
        val envMap = LinkedHashMap<String, String>()
        envMap["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty())
            envMap["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty())
            envMap["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty())
            envMap["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        envMap["TERM"] = "xterm-256color"
        envMap["LANG"] = "C.UTF-8"
        envMap["LC_ALL"] = "C.UTF-8"
        envMap["TZ"] = PRootKernel.posixTz()
        for ((k, v) in PRootKernel.customEnvironment) envMap[k] = v
        // [meta-audit-1028 E3 / F-14] Guest temp dir, pinned explicitly — the
        // same pair PersistentShell.startProcess writes, for the same reason
        // (full measurement there: 17 213 stale dirs / 3.26 GiB of permanent
        // junk under `<rootfs>/data/user/0/<pkg>/cache`, 2026-10-27).
        //
        // Two things this placement buys:
        //  * after the [PRootKernel.customEnvironment] merge, so a
        //    process-global value can never re-point guest temp writes at a
        //    HOST path — the exact regression the pin exists to prevent;
        //  * before the user env-var repository, so a user who deliberately
        //    sets `TMPDIR=/var/minis/tmp` still wins. PersistentShell behaves
        //    the same way (its per-command [PersistentShell.applyEnvironment]
        //    export lands after the process env).
        //
        // It is written here at all because this entry point can NOT inherit
        // the PersistentShell pin: Termux execs PRoot with an explicit env
        // ARRAY, not the inherited ProcessBuilder environment, so what the
        // guest sees depends on Termux's JNI rather than on anything in this
        // repo. Pinning makes the contract explicit instead of accidental.
        envMap["TMPDIR"] = "/tmp"
        envMap["TMP"] = "/tmp"
        ExecutionCoordinator.envVarRepository?.allAsDict()?.forEach { (k, v) -> envMap[k] = v }
        // [T-minis-fastio] The PTY's own proot `-b` table is session-scoped
        // (buildTermuxArgs above), so an offload handler that rebuilds that
        // table from this env var must see the SAME id — otherwise
        // `/var/minis/workspace` resolves to the empty rootfs placeholder
        // inside `minis-fastio` while `ls` in the same terminal shows the real
        // session dir. Last write wins, so it is set after the user env.
        if (!sessionId.isNullOrEmpty()) envMap["MINIS_CHAT_SESSION_ID"] = sessionId
        return envMap.map { (k, v) -> "$k=$v" }
    }

    // ──────────────────────────────────────────────
    //  TermuxSessionClient — Termux callbacks
    // ──────────────────────────────────────────────

    /**
     * Termux terminal event callbacks. In the TerminalScreen (Layer 4) the
     * Termux TerminalView reads directly from the [termuxSession];
     * onTextChanged drives the view redraw (the only live output path).
     */
    private inner class TermuxSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) {
            // CRITICAL: this callback is what drives the TerminalView to
            // redraw when the PTY emits bytes. Termux 0.118.0's own
            // TermuxTerminalSessionClient.onTextChanged() calls
            // terminalView.onScreenUpdated(). Without forwarding here the view
            // stays frozen (black, no cursor, no output) — exactly the
            // "can't operate the terminal" symptom.
            val view = attachedView
            if (view != null) {
                // onTextChanged fires on the emulator thread; redraw on UI.
                mainHandler.post {
                    if (attachedView == null || attachedView !== view) return@post
                    view.onScreenUpdated()
                    // Keep the PTY size in sync with the real view dimensions
                    // once the view is laid out (it starts at 80×24 defaults).
                    val emu = view.mEmulator
                    if (emu != null) {
                        val c = emu.mColumns
                        val r = emu.mRows
                        if (c != cols || r != rows) {
                            cols = c; rows = r
                            termuxSession?.updateSize(c, r)
                        }
                    }
                }
            }
        }
        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) {}
        override fun onSessionFinished(
            changedSession: com.termux.terminal.TerminalSession,
        ) {
            Log.i(TAG, "Termux session finished (exit=${changedSession.exitStatus})")
            // [audit-0917] Mirror stop()'s bookkeeping. A natural exit (user
            // types `exit`, the process dies) only flipped the state, leaving
            // `termuxSession` and the liveSessions entry pointing at a dead
            // session — later attach()/sendInput() calls then targeted a
            // finished process instead of starting a fresh one.
            //
            // [1042-A-03] The WHOLE settlement is ownership-gated, not just the
            // slot clear. This callback can land after a newer start() installed
            // its own PTY (the screen was re-entered while the old shell was
            // exiting). Ungated, the stale callback did three things to a live
            // session it no longer owned:
            //   · bumped startGeneration — aborting the newer boot coroutine at
            //     its next `myGeneration != startGeneration.get()` checkpoint,
            //     i.e. a fresh PTY that dies before it is ever usable;
            //   · published STOPPED — the screen rendered the new session dead;
            //   · removed this object's liveSessions entry — the live shell
            //     stopped receiving timezone/proxy broadcasts.
            // All four lines are one decision about one slot: if the slot is no
            // longer ours, the only thing left for this callback to do is log.
            if (termuxSession === changedSession) {
                startGeneration.incrementAndGet()
                termuxSession = null
                if (_state.value != State.STOPPED) _state.value = State.STOPPED
                liveSessions.removeAll { it.get() === this@TerminalSession || it.get() == null }
            } else {
                Log.i(TAG, "stale session-finished callback ignored — a newer PTY owns the slot")
            }
        }
        override fun onBell(session: com.termux.terminal.TerminalSession) {}
        override fun onColorsChanged(changedSession: com.termux.terminal.TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun onPasteTextFromClipboard(
            session: com.termux.terminal.TerminalSession,
        ): Unit {} // returns Unit in Termux 0.118.0

        override fun onCopyTextToClipboard(
            session: com.termux.terminal.TerminalSession,
            text: String,
        ) {}

        override fun getTerminalCursorStyle(): Int? = null

        // ── JitPack 0.118.0 log callbacks (no-op — we use our own logging) ──
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: java.lang.Exception) {}
        override fun logStackTrace(tag: String, e: java.lang.Exception) {}
    }
}

/**
 * Normalize line endings for PTY output: converts `\n` to `\r`, collapses
 * `\r\n` to `\r`, and passes bare `\r` through unchanged. The TTY's ICRNL
 * termios flag will map the resulting `\r` to `\n` as usual.
 *
 * Extracted as a top-level function so it can be JVM-tested without loading
 * Android classes ([TerminalSession] itself imports `android.content.Context`).
 */
internal fun internalNormalizeLineEndings(text: String): String {
    if ('\n' !in text && '\r' !in text) return text
    val sb = StringBuilder(text.length)
    var i = 0
    val n = text.length
    while (i < n) {
        when (val c = text[i]) {
            '\r' -> {
                sb.append('\r')
                if (i + 1 < n && text[i + 1] == '\n') i++
            }
            '\n' -> sb.append('\r')
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

/**
 * [meta-audit-1028 E3 / F-13] Where is the interactive PTY?
 *
 * Termux execs PRoot as the session process, so the pid we see is the PRoot
 * tracer: depth 1 is the guest login shell, depth 2+ is whatever that shell is
 * running (vim, a REPL, a build). An empty depth-1 list means the /proc walk
 * saw nothing — the process is gone, or `task/<tid>/children` is not readable
 * on this kernel. That is reported as [TerminalPromptState.UNKNOWN] so callers
 * fail CLOSED (defer the broadcast) instead of typing into an unknown
 * foreground.
 *
 * [childrenOf] is injected so this stays pure and JVM-testable; the caller
 * supplies the /proc reader ([TerminalSession.readChildPids]).
 */
internal fun internalTerminalPromptState(
    tracerPid: Int,
    childrenOf: (Int) -> List<Int>,
): TerminalPromptState {
    if (tracerPid <= 0) return TerminalPromptState.UNKNOWN
    val shellLevel = childrenOf(tracerPid)
    if (shellLevel.isEmpty()) return TerminalPromptState.UNKNOWN
    // Background jobs also land here (they are children of the shell). That is
    // deliberate: a shell with a job attached is not a clean prompt to type a
    // six-line block into, and deferring is the cheap direction to be wrong in.
    for (shellPid in shellLevel) {
        if (childrenOf(shellPid).isNotEmpty()) return TerminalPromptState.FOREGROUND_RUNNING
    }
    return TerminalPromptState.IDLE_AT_PROMPT
}

internal enum class TerminalPromptState {
    /** Could not tell — treat as busy: never inject blind. */
    UNKNOWN,

    /** Guest shell with no children: a broadcast may be typed into it. */
    IDLE_AT_PROMPT,

    /** A foreground program (or a background job) owns the shell. */
    FOREGROUND_RUNNING,
}

/**
 * [meta-audit-1028 E3 / F-13] Build the CR-terminated `export` block an env
 * broadcast types into the interactive PTY: one `export KEY='value'\r` per
 * entry, in iteration order.
 *
 * Values are quoted with the shared POSIX idiom
 * ([internalPosixSingleQuote], the same one the agent shell path uses), and
 * C0 control characters are stripped first: each line is SUBMITTED by its
 * trailing `\r`, so a raw `\n` inside a value would submit a half-written
 * `export KEY='…` line and leave the shell in a continuation prompt. A newline
 * cannot be represented on a TTY input line at all, and no legitimate TZ /
 * proxy value contains one.
 *
 * Keys are not validated: the only producers are [PRootKernel.posixTz]'s `TZ`
 * and the fixed `http_proxy` / `https_proxy` / `no_proxy` names from
 * [PRootKernel.systemProxyEnv].
 *
 * Pure + JVM-tested ([TerminalSessionEnvScriptTest]) — [TerminalSession] itself
 * imports Android classes and cannot be loaded on a desktop JVM.
 */
internal fun internalBuildTerminalEnvScript(entries: Map<String, String>): String {
    if (entries.isEmpty()) return ""
    val sb = StringBuilder()
    for ((key, value) in entries) {
        sb.append("export ").append(key).append("='")
        sb.append(internalPosixSingleQuote(internalStripControlChars(value)))
        sb.append("'\r")
    }
    return sb.toString()
}

/**
 * [meta-audit-1028 E3 / F-13] Drop C0 control characters and DEL.
 *
 * Returns [value] itself when it is already clean (the overwhelmingly common
 * case: every proxy URL and POSIX TZ string), so the broadcast path allocates
 * nothing extra.
 */
internal fun internalStripControlChars(value: String): String {
    var clean = true
    for (ch in value) {
        if (ch.code < 0x20 || ch.code == 0x7F) {
            clean = false
            break
        }
    }
    if (clean) return value
    return value.filter { it.code >= 0x20 && it.code != 0x7F }
}
