package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fix/fold-paste-split-1114] Regressions the statement-aware classifier
 * introduced, found by re-running the OLD prefix classifier over the device's
 * own command log rather than by reading the new code.
 *
 * Both defects have the same shape: the rewrite enumerated what it knew
 * (`python3.11`, `python3.12`; `nice`'s single numeric flag) and silently
 * demoted everything just outside the enumeration. Enumerations rot; families
 * do not.
 *
 * Every expectation below is a literal on purpose.
 */
class CommandClassifierFamilyTest {

    // ── F5: versioned interpreters ───────────────────────────────────────────

    @Test
    fun `a python minor version nobody enumerated is still heavy`() {
        // The old classifier used startsWith("python3"), so ALL of these were
        // HEAVY before the rewrite. The rewrite listed 3.11/3.12 by hand and the
        // rest fell to LIGHT — a real downgrade, not a missing feature.
        for (v in listOf("python3.9", "python3.10", "python3.13", "python3.14", "python3.20")) {
            assertEquals(
                "$v must not be downgraded to LIGHT (old classifier said HEAVY)",
                CommandClass.HEAVY,
                classifyCommand("$v /tmp/script.py"),
            )
        }
    }

    @Test
    fun `the bare interpreters keep working`() {
        for (v in listOf("python", "python3", "node", "nodejs", "ruby", "perl", "php")) {
            assertEquals(v, CommandClass.HEAVY, classifyCommand("$v x"))
        }
    }

    @Test
    fun `a versioned interpreter behind a path is still heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("./python3.13 x.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("/usr/bin/python3.12 -m pytest"))
    }

    @Test
    fun `a filename that merely starts like an interpreter is not heavy`() {
        // The family regex is anchored at both ends: `python3.cfg` is a file, and
        // the old prefix check wrongly called it HEAVY. Fixing the version hole
        // must not re-open that one.
        assertEquals(CommandClass.LIGHT, classifyCommand("cat python3.cfg"))
        assertEquals(CommandClass.LIGHT, classifyCommand("ls python3.13.log"))
    }

    // ── F6: wrapper arguments ────────────────────────────────────────────────

    @Test
    fun `sudo with its own flags still reaches the real verb`() {
        // The KDoc of stripLeadingNoise explicitly claimed `sudo -u x make` →
        // `make`; the implementation only dropped the wrapper word, leaving `-u`
        // as the verb and matching nothing.
        assertEquals(CommandClass.HEAVY, classifyCommand("sudo -u x make -j4"))
        assertEquals(CommandClass.HEAVY, classifyCommand("sudo -E python3 x.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("sudo -g staff -u x make"))
    }

    @Test
    fun `env with flags or assignments still reaches the real verb`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("env -i FOO=bar python3 x.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("env FOO=bar BAZ=1 make"))
    }

    @Test
    fun `time with a format flag still reaches the real verb`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("time -p python3 x.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("time python3 x.py"))
    }

    @Test
    fun `a wrapper around a light command stays light`() {
        // The point is to reach the real verb, not to flag every sudo.
        assertEquals(CommandClass.LIGHT, classifyCommand("sudo -u x ls -la"))
        assertEquals(CommandClass.LIGHT, classifyCommand("env -i FOO=bar grep x y"))
        assertEquals(CommandClass.LIGHT, classifyCommand("sudo --version"))
    }

    // ── the guard rails that must not regress ────────────────────────────────

    @Test
    fun `a wrapper nested in a pipeline is still seen`() {
        assertEquals(
            CommandClass.HEAVY,
            classifyCommand("cd /tmp && sudo -u x make && echo done"),
        )
    }

    @Test
    fun `plain commands keep their old class`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("ls -la"))
        assertEquals(CommandClass.LIGHT, classifyCommand("git status"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git fetch --all"))
        assertEquals(CommandClass.LEAKY, classifyCommand("minis-model-use list"))
    }
}
