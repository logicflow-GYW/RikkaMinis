package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fix/heavy-gate-scope] Second axis: which commands must take the ONE
 * process-wide heavy permit.
 *
 * The FIRST axis ([classifyCommand]) is asserted unchanged at the bottom — the
 * split must not move recycle/pre-exec behaviour for any command.
 */
class HeavyGateClassTest {

    @Test
    fun `deterministic memory consumers keep the global permit`() {
        for (c in listOf(
            "git clone --depth 1 https://x/y.git",
            "git fetch --all",
            "git push origin main",
            "git gc --prune=now",
            "npm run build",
            "apk add ripgrep",
            "pip3 install requests",
            "tar -czf a.tar.gz dir",
            "unzip a.zip",
            "gradle assemble",
            "kotlinc a.kt -d out",
            "make -j4",
        )) {
            assertEquals(c, HeavyGateClass.GLOBAL, classifyHeavyGate(c))
        }
    }

    @Test
    fun `interpreter calls are admitted without the permit`() {
        for (c in listOf(
            "python3 -c 'print(1)'",
            "python3 script.py",
            "cd /var/minis/workspace && python3 train.py",
            "node server.js",
            "python3.13 x.py",
            "/usr/bin/python3.12 -m pytest",
        )) {
            assertEquals(c, HeavyGateClass.INTERPRETER, classifyHeavyGate(c))
        }
    }

    @Test
    fun `wait-only interpreter calls stay light`() {
        for (c in listOf(
            "python3 gh_ci.py wait --repo o/r --workflow build-apk.yml",
            "cd /var/minis/skills/github-ops/scripts && python3 gh_ci.py wait --repo o/r",
            "python3 /var/minis/skills/github-ops/scripts/gh_ci_wait.py wait",
        )) {
            assertEquals(c, HeavyGateClass.LIGHT, classifyHeavyGate(c))
        }
    }

    @Test
    fun `mixed statements take the heaviest gate class`() {
        assertEquals(
            HeavyGateClass.GLOBAL,
            classifyHeavyGate("python3 x.py && git clone a b"),
        )
        assertEquals(
            HeavyGateClass.INTERPRETER,
            classifyHeavyGate("python3 x.py && ls -la"),
        )
        assertEquals(HeavyGateClass.LIGHT, classifyHeavyGate("ls; cat f"))
    }

    @Test
    fun `light commands and the first axis are unchanged`() {
        // First axis: memory discipline still sees every interpreter as HEAVY.
        assertEquals(CommandClass.HEAVY, classifyCommand("python3 x.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git clone a b"))
        assertEquals(CommandClass.LIGHT, classifyCommand("ls -la"))
        // minis-model-use never took the permit; it must not start now.
        assertEquals(HeavyGateClass.LIGHT, classifyHeavyGate("minis-model-use run --model m"))
    }

    @Test
    fun `wrapped verbs are classified by the real verb`() {
        assertEquals(HeavyGateClass.GLOBAL, classifyHeavyGate("nice -n 10 make -j4"))
        assertEquals(HeavyGateClass.INTERPRETER, classifyHeavyGate("sudo -u x python3 x.py"))
        assertEquals(HeavyGateClass.LIGHT, classifyHeavyGate("cd /tmp && git status"))
    }
}
