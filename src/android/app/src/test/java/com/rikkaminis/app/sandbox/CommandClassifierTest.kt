package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fix/classify-compound-1114] Statement-aware command classification.
 *
 * Defect this pins: `classifyCommand` read only the FIRST token of the raw
 * command, so `cd /var/minis/workspace && python3 train.py` — 233 of 4 775 real
 * shell_execute calls in the 2026-11-01..14 device log — classified LIGHT.
 * LIGHT bypasses the heavyGate (Semaphore(1)), skips the CRITICAL-phase
 * "heavy needs ample memory" check, and is never recycled by class.
 *
 * Expected values are literals: deriving them from the verb sets would let a
 * set edit move the expectation with it and turn a regression into a PASS.
 */
class CommandClassifierTest {

    // ── the defect itself ────────────────────────────────────────────────────

    @Test
    fun `cd-prefixed heavy work is heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("cd /var/minis/workspace && python3 train.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("cd /tmp && node server.js"))
        assertEquals(CommandClass.HEAVY, classifyCommand("cd /tmp && npm run build"))
    }

    @Test
    fun `cd-only statements stay light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /tmp"))
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /tmp && ls -la"))
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /tmp && cat f"))
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /var/minis/shared/work/repo"))
    }

    @Test
    fun `leading env assignment does not hide the verb`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("ENV=prod npm run build"))
        assertEquals(CommandClass.HEAVY, classifyCommand("A=1 B=2 make all"))
        assertEquals(CommandClass.LIGHT, classifyCommand("VERBOSE=1 ls -la"))
    }

    @Test
    fun `leading comment header does not hide the verb`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("# build it\npython3 build.py"))
        assertEquals(CommandClass.LIGHT, classifyCommand("# just a note\nls"))
    }

    @Test
    fun `the heaviest statement wins`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("ls; python3 x.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("cat f | python3 -"))
        assertEquals(CommandClass.HEAVY, classifyCommand("echo a\npython3 x.py"))
        assertEquals(CommandClass.LIGHT, classifyCommand("ls; cat f | wc -l"))
    }

    @Test
    fun `a for loop body is still seen`() {
        // The loop keyword is LIGHT on its own; the body's verb is what matters.
        assertEquals(CommandClass.HEAVY, classifyCommand("for f in a b; do python3 run.py; done"))
    }

    // ── git is classified by SUBCOMMAND (measured distribution) ──────────────

    @Test
    fun `read-only git subcommands stay light`() {
        // 220x `git status`, 100x `diff`, 90x `log` in the log sample: making
        // these heavy would serialize the agent's most common calls for zero
        // memory benefit.
        assertEquals(CommandClass.LIGHT, classifyCommand("git status"))
        assertEquals(CommandClass.LIGHT, classifyCommand("git diff HEAD~3"))
        assertEquals(CommandClass.LIGHT, classifyCommand("git log --oneline -20"))
        assertEquals(CommandClass.LIGHT, classifyCommand("git rev-parse HEAD"))
        assertEquals(CommandClass.LIGHT, classifyCommand("git show HEAD:file.kt"))
        assertEquals(CommandClass.LIGHT, classifyCommand("git add -A"))
        assertEquals(CommandClass.LIGHT, classifyCommand("git commit -m x"))
        assertEquals(CommandClass.LIGHT, classifyCommand("cd /repo && git status"))
    }

    @Test
    fun `network and repack git subcommands are heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("git clone --depth 1 https://x/y.git"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git fetch --all"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git pull"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git push origin main"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git gc --prune=now"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git repack -ad"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git fsck --full"))
        assertEquals(CommandClass.HEAVY, classifyCommand("cd /repo && git clone a b"))
    }

    @Test
    fun `git global flags do not hide the subcommand`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("git -C /repo status"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git -C /repo fetch"))
        assertEquals(CommandClass.HEAVY, classifyCommand("git --no-pager clone x"))
    }

    // ── newly covered verb families ──────────────────────────────────────────

    @Test
    fun `compilers and archivers are heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("gcc -O2 a.c"))
        assertEquals(CommandClass.HEAVY, classifyCommand("clang++ -std=c++20 a.cpp"))
        assertEquals(CommandClass.HEAVY, classifyCommand("javac Foo.java"))
        assertEquals(CommandClass.HEAVY, classifyCommand("kotlinc a.kt -d out"))
        assertEquals(CommandClass.HEAVY, classifyCommand("make -j4"))
        assertEquals(CommandClass.HEAVY, classifyCommand("cmake .."))
        assertEquals(CommandClass.HEAVY, classifyCommand("tar -czf a.tar.gz dir"))
        assertEquals(CommandClass.HEAVY, classifyCommand("unzip a.zip"))
        assertEquals(CommandClass.HEAVY, classifyCommand("ffmpeg -i a.mp4 b.mp3"))
        assertEquals(CommandClass.HEAVY, classifyCommand("rustc a.rs"))
    }

    @Test
    fun `absolute and relative verb paths are matched by basename`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("/usr/bin/python3 x.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("./python3 x.py"))
        assertEquals(CommandClass.LIGHT, classifyCommand("/bin/ls -la"))
    }

    // ── preserved behaviour (do not regress) ────────────────────────────────

    @Test
    fun `leaky is still leaky and wins over heavy`() {
        assertEquals(CommandClass.LEAKY, classifyCommand("minis-model-use list"))
        assertEquals(CommandClass.LEAKY, classifyCommand("  minis-model-use run --model x"))
        assertEquals(CommandClass.LEAKY, classifyCommand("cd /tmp && minis-model-use list"))
        assertEquals(CommandClass.LEAKY, classifyCommand("python3 x.py && minis-model-use list"))
    }

    @Test
    fun `plain lightweight commands stay light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand(""))
        assertEquals(CommandClass.LIGHT, classifyCommand("   "))
        assertEquals(CommandClass.LIGHT, classifyCommand("ls -la"))
        assertEquals(CommandClass.LIGHT, classifyCommand("grep -r foo /var/minis"))
        assertEquals(CommandClass.LIGHT, classifyCommand("echo hello"))
        assertEquals(CommandClass.LIGHT, classifyCommand("which python3"))
        assertEquals(CommandClass.LIGHT, classifyCommand("cat /etc/hostname"))
    }

    @Test
    fun `bare heavy verbs stay heavy`() {
        assertEquals(CommandClass.HEAVY, classifyCommand("python3 /tmp/script.py"))
        assertEquals(CommandClass.HEAVY, classifyCommand("apk add py3-numpy"))
        assertEquals(CommandClass.HEAVY, classifyCommand("apk"))
        assertEquals(CommandClass.HEAVY, classifyCommand("pip3 install torch"))
        assertEquals(CommandClass.HEAVY, classifyCommand("go build ./..."))
    }

    @Test
    fun `a command that is only assignments or separators is light`() {
        assertEquals(CommandClass.LIGHT, classifyCommand("A=1"))
        assertEquals(CommandClass.LIGHT, classifyCommand("&&"))
        assertEquals(CommandClass.LIGHT, classifyCommand(";;"))
    }

    // ── recycle policy is unchanged (it moved files, not behaviour) ──────────

    @Test
    fun `recycle policy is unchanged`() {
        assertEquals(true, shouldRecycleByClass(CommandClass.LEAKY, 0))
        assertEquals(false, shouldRecycleByClass(CommandClass.HEAVY, 80))
        assertEquals(true, shouldRecycleByClass(CommandClass.HEAVY, 81))
        assertEquals(false, shouldRecycleByClass(CommandClass.LIGHT, 10_000))
    }
}
