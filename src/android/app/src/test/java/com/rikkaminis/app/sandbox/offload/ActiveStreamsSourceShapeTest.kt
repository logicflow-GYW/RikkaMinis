package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [deep-audit-1018 ISSUE-04] `ChatStreamOffloadHandler.activeStreams` —— 结构面。
 *
 * 与 [ActiveStreamsCounterTest]（行为面）分开文件：`++` 的重新出现意味着有人把
 * 类型改回了 `@Volatile var`，那类变异会让行为用例编译失败（Int 没有 get()），
 * 混在同一文件会掩盖结构断言的红。本文件只读源码、不触碰生产对象，对任何
 * 树都能编译，给的是纯结构红/绿。
 */
class ActiveStreamsSourceShapeTest {

    @Test
    fun `source uses atomic operations only`() {
        val src = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/sandbox/offload/ChatStreamOffloadHandler.kt",
        )
        assertTrue(
            "declaration must be an AtomicInteger",
            src.contains("val activeStreams = java.util.concurrent.atomic.AtomicInteger(0)"),
        )
        assertTrue("increment must be getAndIncrement()", src.contains("activeStreams.getAndIncrement()"))
        assertTrue(
            "decrement must be updateAndGet { ... coerceAtLeast(0) }",
            src.contains("activeStreams.updateAndGet { (it - 1).coerceAtLeast(0) }"),
        )
        assertFalse("no ++ operator", src.contains("activeStreams++"))
        assertFalse("no read-modify-write assignment", src.contains("activeStreams = (activeStreams"))
        assertFalse("must not go back to a @Volatile var", src.contains("var activeStreams"))
    }

    @Test
    fun `any reader of the counter goes through the atomic get`() {
        // [FIX-FW-DEAD-SHUTDOWN-LEVER] This case used to name the ONE production
        // reader — the reclaim guard in `ExecutionCoordinator`
        // (`maybeReclaimModelService` … `activeStreams.get() > 0` plus its log
        // line) — and require it to read atomically. That guard was deleted with
        // the dead shutdown lever it protected, so there is no named site left to
        // pin; re-pinning a specific site would assert that a reader EXISTS, which
        // is not what this file is about.
        //
        // The invariant the audit case (deep-audit-1018 ISSUE-04) was really
        // protecting survives without a named site: every read of the counter,
        // wherever it appears, is an atomic `get()` — never a bare field read and
        // never `++`/`--`. The scan therefore covers the whole production tree
        // instead of one file, which is strictly more surface than before (it
        // would also catch a reader added in a third file).
        val mainDir = File(repoRoot(), "src/android/app/src/main/java")
        val offenders = mutableListOf<String>()
        var readers = 0
        mainDir.walkTopDown()
            // The declaration + its own atomic ++/-- live here (getAndIncrement /
            // updateAndGet): that file is the subject of the case above, not a
            // consumer.
            .filter { it.isFile && it.extension == "kt" && it.name != "ChatStreamOffloadHandler.kt" }
            .forEach { f ->
                val text = f.readText()
                var at = text.indexOf("activeStreams")
                while (at >= 0) {
                    if (text.startsWith("activeStreams.get()", at)) {
                        readers++
                    } else {
                        val from = maxOf(0, at - 60)
                        val to = minOf(text.length, at + 60)
                        offenders += "${f.name}: …${text.substring(from, to).replace('\n', ' ')}…"
                    }
                    at = text.indexOf("activeStreams", at + 1)
                }
            }
        assertTrue(
            "every activeStreams read must be an atomic get(); offenders: $offenders",
            offenders.isEmpty(),
        )
        // Today this is 0 — the counter has no production reader since the lever
        // was deleted (see ChatStreamOffloadHandler KDoc). Logged, not asserted:
        // asserting "0 readers" would forbid a legitimate future consumer, while
        // the assertion above is what has teeth the day one comes back.
        println("[ActiveStreamsSourceShapeTest] atomic readers found: $readers")
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, "src/android/app").isDirectory) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
