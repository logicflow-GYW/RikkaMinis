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
    fun `readers use an atomic get`() {
        val src = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt",
        )
        assertTrue(
            "reclaim guard must read the counter atomically",
            src.contains("ChatStreamOffloadHandler.activeStreams.get() > 0"),
        )
        assertTrue(
            "reclaim log must read the counter atomically",
            // NOTE 模板转义：${...} 在 Kotlin 里是字符串模板，不转义会求值成
            // "activeStreams=0"，断言的就不是源码字面量了。
            src.contains("activeStreams=\${ChatStreamOffloadHandler.activeStreams.get()}"),
        )
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
