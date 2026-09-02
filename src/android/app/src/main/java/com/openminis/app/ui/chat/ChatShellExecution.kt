package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.BashismReminder
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * FE-5 route B: the shell_execute engine extracted from
 * ChatViewModel.executeShellCommand.
 *
 * The engine is a pure function of (argsJson, session id, bash context) —
 * the ONLY impure dependencies are parameterized:
 *  - [onBlockUpdate] — the live tool-block content updates (countdown /
 *    streamed lines) that used to mutate toolBlocks + call
 *    updateAssistantMessage directly. NON-suspend (the coordinator's
 *    lineCallback is a plain (String) -> Unit); the ViewModel wires it to a
 *    viewModelScope.launch(Dispatchers.Main) hop exactly like the original
 *    inline closure did. The engine only decides WHAT to show.
 *  - [ExecutionCoordinator] stays a direct call (sandbox domain).
 *
 * T7 budget plumbing (t7ConsumeAndTrace / t7ResourceAcquire / release)
 * stays in the ChatViewModel wrapper — it is run-scoped trace state, not
 * shell semantics.
 */

/** Sentinel returned by the bash wrapper when bash is missing at run time,
 *  distinct from a script that legitimately exits 127 (T-bash-on-demand M5). */
internal const val BASH_MISSING_SENTINEL = 119

/** Wrap a script to run under bash via a guest-side self-written temp file
 *  (base64, single line, self-cleaning), guarding on `command -v bash` so a
 *  vanished bash is detected precisely for inline self-heal.
 *
 *  The whole wrapper runs inside a SUBSHELL `( … )`. This is load-bearing on
 *  Android: PersistentShell drives commands as `{cmd}; echo …_EXIT_$?…` and
 *  reads the exit code from that marker line. A bare `|| exit 119` would exit
 *  the persistent shell process itself BEFORE the marker echo runs, so no
 *  marker is emitted and PersistentShell.parseExitCode falls back to -1 —
 *  the M5 self-heal sentinel check (== 119 / 30464) then never matches and a
 *  vanished bash is never re-installed. Wrapping in a subshell makes
 *  `exit 119` leave only the subshell, so `$?` = 119 reaches the marker. */
internal fun wrapForBash(script: String): String {
    // [T-heredoc-trailing-newline] A heredoc that ends the decoded file with
    // no trailing newline fails with "unexpected end of file". Guarantee one.
    val normalized = if (script.endsWith("\n")) script else script + "\n"
    val b64 = android.util.Base64.encodeToString(
        normalized.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    return "( command -v bash >/dev/null 2>&1 || exit $BASH_MISSING_SENTINEL; " +
        "printf %s '$b64' | base64 -d > /tmp/.minis-exec-\$\$.sh && " +
        "bash /tmp/.minis-exec-\$\$.sh; rc=\$?; rm -f /tmp/.minis-exec-\$\$.sh; exit \$rc )"
}

/**
 * The shell_execute engine. See the file-level KDoc for the parameterization
 * contract. [onBlockUpdate] receives the raw display content the block
 * should show (countdown text, streamed + trimmed lines); the caller owns
 * mutating toolBlocks + repainting.
 */
internal suspend fun executeShellCommandEngine(
    argsJson: String,
    dispatchSessionId: String,
    context: Context,
    toolKey: String,
    onBlockUpdate: (displayContent: String) -> Unit,
): ToolResultShell {
    val args = JSONObject(argsJson)
    var command = args.optString("command", "")
    val timeoutSec = args.optInt("timeout", 900).coerceIn(1, 900)
    val delaySec = args.optInt("delay", 0).coerceAtLeast(0)
    val toolTitle = args.optString("tool_title", "shell_execute")

    if (command.isBlank()) {
        return ToolResultShell.error("'command' is required", toolTitle)
    }

    // [T-android-overlay-finalize item 1] Removed the
    // shell-specific status hack ("shell: $toolTitle"). Since the
    // dispatch loop (~5003) now surfaces `tool_title` in the overlay
    // label uniformly via SessionActivityTracker.updateToolStatus(
    // status, toolName, isRunning, toolTitle), the per-tool override
    // produced redundant "shell / shell: <title>" rows. Lifecycle
    // status ("Running: shell_execute") set by the dispatch loop is
    // sufficient.

    // Delay execution: block the agent flow without occupying the shell,
    // allowing other concurrent tasks to use it during the wait period.
    if (delaySec > 0) {
        for (remaining in delaySec downTo 1) {
            val mm = remaining / 60
            val ss = remaining % 60
            val countdown = if (mm > 0) String.format("%d:%02d", mm, ss) else "${ss}s"
            onBlockUpdate("⏳ Waiting $countdown before executing...")
            kotlinx.coroutines.delay(1000)
        }
        onBlockUpdate("")
    }

    // [T-bash-on-demand] Detect busybox-ash-incompatible bash syntax and,
    // if found, transparently install + switch to bash. Install time is
    // NOT charged against the command timeout (OnDemandBash has its own
    // budget). `command` is rewritten to the bash-wrapped form on the S/E
    // path; `bashReminder` is attached if we fall back to sh. Only this
    // agent path runs here; the in-app terminal is untouched.
    BashismDetector.ensureLoaded(context)
    val bashism = BashismDetector.detect(command)
    var bashReminder: String? = null
    val originalCommand = command
    var bashScript: String? = null   // set when we bash-wrapped; enables M5 self-heal retry
    if (bashism.needsBash) {
        val executor = OnDemandBash.Executor { c, t ->
            ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
        }
        when (val outcome = OnDemandBash.ensureBash(context, executor)) {
            is OnDemandBash.Outcome.Available -> {
                if (bashism.mustSwitchInterpreter) {
                    // §3.2 M3: self-write the script in the guest (base64,
                    // single line, self-cleaning) and run it under bash.
                    // The `command -v bash || exit 119` guard detects a
                    // bash that vanished after our cache check (M5) so we
                    // can self-heal below instead of failing.
                    command = wrapForBash(command)
                    bashScript = originalCommand // remember for self-heal retry
                }
                // T1-only (script invokes bash itself) → run as-is under sh.
            }
            is OnDemandBash.Outcome.Unavailable ->
                bashReminder = BashismReminder.build(bashism.hits, outcome.reason)
        }
    }

    var result = ExecutionCoordinator.execute(
        sessionId = dispatchSessionId,
        command = command,
        timeout = timeoutSec * 1000L,
        lineCallback = lc@{ rawLine ->
            // Strip any OSC MinisOpenURL markers emitted by
            // /usr/local/bin/minis-open and forward the captured
            // URLs to the broker so the chat screen can present the
            // in-app preview. Lines that were *entirely* a marker
            // (nothing visible afterwards) are dropped so the tool
            // output doesn't grow blank rows.
            val (cleanedLine, capturedUrls) = MinisUrlMarker.extract(rawLine)
            for (raw in capturedUrls) MinisOpenUrlBroker.offer(raw)
            if (cleanedLine.isEmpty() && rawLine.isNotEmpty()) return@lc

            // Streaming display: accumulate into this tool's window and
            // push the trimmed last-50-lines each time.
            streamedLinesForDisplay(rawLine, toolKey)?.let { onBlockUpdate(it) }
        },
    )

    // [T-bash-on-demand] M5 self-heal: our bash wrapper returns sentinel
    // 119 when bash vanished (user apk del'd) after we cached it
    // available. Re-probe + reinstall once and rerun THIS command under
    // bash inline, so it still succeeds instead of failing.
    // Accept both the raw sentinel (119) and the wait(2)-encoded status
    // (119 << 8 = 30464) the coordinator may surface.
    if ((result.exitCode == BASH_MISSING_SENTINEL ||
            result.exitCode == (BASH_MISSING_SENTINEL shl 8)) && bashScript != null) {
        OnDemandBash.markDisappeared()
        val executor = OnDemandBash.Executor { c, t ->
            ExecutionCoordinator.execute(sessionId = dispatchSessionId, command = c, timeout = t).exitCode
        }
        val healed = OnDemandBash.ensureBash(context, executor)
        command = if (healed is OnDemandBash.Outcome.Available) wrapForBash(bashScript!!) else bashScript!!
        result = ExecutionCoordinator.execute(
            sessionId = dispatchSessionId, command = command, timeout = timeoutSec * 1000L)
    }

    // Also scrub markers from the aggregated one-shot output and
    // broker any URLs that only appeared there (defensive — handles
    // executors that don't fire lineCallback for every line).
    val (cleanedOutput, oneShotUrls) = MinisUrlMarker.extract(result.output)
    for (raw in oneShotUrls) MinisOpenUrlBroker.offer(raw)
    val output = if (cleanedOutput.isBlank()) "(no output)" else cleanedOutput
    val exitInfo = if (result.exitCode != 0) " (exit code ${result.exitCode})" else ""
    // Exit code 124 is the BusyBox/GNU timeout-utility convention for
    // a command that exceeded its budget. PersistentShell returns this
    // when its `withTimeoutOrNull(timeout)` wrapper fires.
    val timedOut = result.exitCode == 124

    // Redact env-var values that leaked into the captured output
    // before the model sees them. No-op when Privacy Mode is OFF.
    // Done after exitInfo is appended so the suffix can't accidentally
    // contain a secret that escaped masking. The user-visible streamed
    // content (onBlockUpdate above) is intentionally left unmasked.
    val finalOutput = "$output$exitInfo"
    val (redactedOut, redactHits) = com.openminis.app.data.EnvVarRedactor.redactIfEnabled(finalOutput)
    if (redactHits > 0) {
        android.util.Log.i("EnvVarRedact", "shell_execute: masked $redactHits env-var value(s) in tool result")
    }

    // [T-bash-on-demand] M5 self-heal: bash disappeared (user apk del'd)
    // → re-probe next time.
    if (result.exitCode == 127 && bashism.mustSwitchInterpreter) {
        OnDemandBash.markDisappeared()
    }
    // §4.2: append the bashism reminder when we fell back to sh and the
    // command failed OR any silent-class rule was hit (S-class exit-0
    // exception, default-on).
    val withReminder = bashReminder?.let { rem ->
        if (result.exitCode != 0 || bashism.hasSilent) "$redactedOut\n\n$rem" else redactedOut
    } ?: redactedOut

    return ToolResultShell(
        output = withReminder,
        success = result.exitCode == 0,
        toolTitle = toolTitle,
        timedOut = timedOut,
    )
}

/** Engine result — mapped to ToolExecutionResult by the VM wrapper. */
internal data class ToolResultShell(
    val output: String,
    val success: Boolean,
    val toolTitle: String,
    val timedOut: Boolean,
) {
    companion object {
        fun error(message: String, toolTitle: String) =
            ToolResultShell("Error: $message", false, toolTitle, timedOut = false)
    }
}

/**
 * The streamed-line accumulator the original inline closure owned as
 * `toolBlocks[idx].content`. The engine keeps its own window so the
 * parameterized [onBlockUpdate] contract stays "here is the display text".
 */
private val displayLineBuffers = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()

internal fun streamedLinesForDisplay(rawLine: String, toolKey: String = "default"): String? {
    val buf = displayLineBuffers.computeIfAbsent(toolKey) { StringBuilder() }
    val (cleanedLine, _) = MinisUrlMarker.extract(rawLine)
    if (cleanedLine.isEmpty() && rawLine.isNotEmpty()) return null
    if (buf.isNotEmpty()) buf.append('\n')
    buf.append(cleanedLine)
    val trimmed = buf.toString().lines().takeLast(50).joinToString("\n")
    return trimmed
}

internal fun resetDisplayBuffer(toolKey: String = "default") {
    displayLineBuffers.remove(toolKey)
}
