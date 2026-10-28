package com.rikkaminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

/**
 * [meta-audit-1028 E3 / F-13] Env-broadcast scripts for the INTERACTIVE PTY.
 *
 * Two properties are pinned here; both are about what gets typed into a
 * terminal that already owns a foreground program.
 *
 *  1. DATA SAFETY — a value containing `'`, newlines or control bytes must end
 *     up as exactly one `export KEY='…'` line. Each line is SUBMITTED by its
 *     trailing `\r`, so a raw `\n` inside a value would execute a half-written
 *     `export KEY='…` and leave the shell in a continuation prompt. That is why
 *     [internalStripControlChars] runs before quoting, not after.
 *
 *  2. TIMING — [internalTerminalPromptState] decides whether the PTY is safe to
 *     write to at all, and it deliberately fails closed: anything it cannot
 *     determine (no /proc read, gone process) is UNKNOWN, which the caller
 *     treats as "not safe" rather than "go ahead".
 *
 * Both are the prerequisites of the fix; the scheduler that defers the write
 * until the prompt is idle lives in TerminalSession itself (not JVM-testable)
 * and is covered by the walkthrough in the fix report.
 */
class TerminalSessionEnvScriptTest {

    // ── data safety: POSIX single-quote escaping ───────────────────────────

    @Test
    fun `escaping produces one export line per entry`() {
        val entries = linkedMapOf("TZ" to "LCL-8")
        assertEquals("export TZ='LCL-8'\r", internalBuildTerminalEnvScript(entries))
    }

    @Test
    fun `a value containing a single quote is escaped with the POSIX idiom`() {
        // `it's` must survive as a literal — the shell must not see the quote
        // as the end of the value and then execute `s'` as a command.
        val script = internalBuildTerminalEnvScript(mapOf("TZ" to "it's fine"))
        assertEquals("export TZ='it'\\''s fine'\r", script)
        // One line only: no second CR anywhere.
        assertEquals(0, script.trimEnd('\r').count { it == '\r' })
    }

    @Test
    fun `a value with several quotes round-trips through POSIX escaping`() {
        val script = internalBuildTerminalEnvScript(mapOf("TZ" to "'''"))
        assertEquals("export TZ=''\\'''\\'''\\'''\r", script)
    }

    @Test
    fun `empty map yields an empty script`() {
        assertEquals("", internalBuildTerminalEnvScript(emptyMap()))
    }

    @Test
    fun `a newline inside a value cannot submit a half-written export`() {
        // This is the injection the old code allowed: `\r` terminates every
        // line, so a `\n`/`\r` in the value would end the line early and the
        // shell would execute whatever followed.
        val script = internalBuildTerminalEnvScript(mapOf("http_proxy" to "http://a\rrm -rf /"))
        assertTrue("script must contain exactly one CR (its own terminator)",
            script.count { it == '\r' } == 1)
        assertEquals("export http_proxy='http://arm -rf /'\r", script)
    }

    @Test
    fun `control characters are stripped, printable ones preserved`() {
        assertEquals("AB", internalStripControlChars("A\u0000B"))
        assertEquals("AB", internalStripControlChars("A\u007FB"))
        // A printable value must come back as the SAME instance — the
        // allocation-free fast path is what keeps this cheap per broadcast.
        val value = "http://127.0.0.1:8080"
        assertSame(value, internalStripControlChars(value))
    }

    @Test
    fun `script lines are CR terminated and LF free`() {
        val script = internalBuildTerminalEnvScript(
            linkedMapOf("http_proxy" to "http://p", "https_proxy" to "http://s", "no_proxy" to "localhost")
        )
        assertTrue(script.endsWith("\r"))
        assertFalse(script.contains("\n"))
        assertEquals(3, script.count { it == '\r' })
        assertEquals(
            "export http_proxy='http://p'\r" +
                "export https_proxy='http://s'\r" +
                "export no_proxy='localhost'\r",
            script,
        )
    }

    @Test
    fun `the two writers of export lines agree on escaping`() {
        // internalPosixSingleQuote is shared with the agent-shell path
        // (internalBuildEnvironmentScript). Same value ⇒ same quoting, even
        // though the line terminators differ by contract.
        val terminal = internalBuildTerminalEnvScript(mapOf("TZ" to "a'b"))
        val agent = internalBuildEnvironmentScript(mapOf("TZ" to "a'b"))
        assertEquals(
            agent.removeSuffix("\n"),
            terminal.removeSuffix("\r"),
        )
    }

    // ── timing: is the PTY safe to write to? ───────────────────────────────

    @Test
    fun `shell with no children is idle at a prompt`() {
        assertEquals(
            TerminalPromptState.IDLE_AT_PROMPT,
            internalTerminalPromptState(100) { pid -> if (pid == 100) listOf(200) else emptyList() },
        )
    }

    @Test
    fun `shell running vim or a REPL is reported as busy`() {
        val children = mapOf(100 to listOf(200), 200 to listOf(300))
        assertEquals(
            TerminalPromptState.FOREGROUND_RUNNING,
            internalTerminalPromptState(100) { children[it] ?: emptyList() },
        )
    }

    @Test
    fun `a grandchild of the tracer does not by itself mean busy`() {
        // Depth 2+ is the shell's own child; depth 1 is the shell. A pid tree
        // that never descends past the tracer's direct children means the guest
        // shell is at its prompt. This pins WHERE the depth cut lands.
        assertEquals(
            TerminalPromptState.IDLE_AT_PROMPT,
            internalTerminalPromptState(100) { pid -> if (pid == 100) listOf(200, 201) else emptyList() },
        )
    }

    @Test
    fun `an unreadable or missing process fails closed`() {
        assertEquals(TerminalPromptState.UNKNOWN, internalTerminalPromptState(0) { emptyList() })
        assertEquals(TerminalPromptState.UNKNOWN, internalTerminalPromptState(-1) { emptyList() })
        assertEquals(TerminalPromptState.UNKNOWN, internalTerminalPromptState(100) { emptyList() })
    }

    @Test
    fun `the pid walk stops at the shell level, never descending further`() {
        // Guards against a future "optimisation" that recurses the whole tree:
        // the answer only depends on the shell's immediate children.
        val asked = mutableListOf<Int>()
        internalTerminalPromptState(100) { pid -> asked.add(pid); if (pid == 100) listOf(200) else emptyList() }
        assertEquals(listOf(100, 200), asked)
    }
}
