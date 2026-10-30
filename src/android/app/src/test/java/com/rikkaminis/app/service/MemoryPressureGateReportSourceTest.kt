package com.rikkaminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LOOP-1030 R1-b] 组装层口径形状：`MinisApp` 装给 `pressureListener` 的日志必须
 * **按采样点分流**，并把「是否真越线」写进行里。
 *
 * 钉结构：判据是「tick 分支与准入分支各有自己的字符串」「行里带 src 与 crossed」，
 * 不是「某符号出现 N 次」——计数对「新增一条绕过路径」零敏感。
 */
class MemoryPressureGateReportSourceTest {

    private fun wiring(): String = readRepoFile(
        "app/src/main/java/com/rikkaminis/app/MinisApp.kt",
    ).substringAfter("MemoryPressureGate.pressureListener = {")
        .substringBefore("// [fix/memory-gate-anon-metric]")

    @Test
    fun `tick samples are labelled as observation only`() {
        val src = wiring()
        assertTrue(
            "tick branch missing",
            src.contains(
                "src == MemoryPressureGate.SRC_TICK -> \"observe-only tick sample (no admission effect)\"",
            ),
        )
    }

    @Test
    fun `admission branches keep the old delay and throttle wording`() {
        val src = wiring()
        assertTrue(src.contains("\"admission delayed 500ms\""))
        assertTrue(src.contains("\"admission throttled (reclaim + 2s wait)\""))
    }

    @Test
    fun `the line carries both the source and the crossing flag`() {
        val src = wiring()
        assertTrue(src.contains("src=\$src crossed=\$crossed"))
    }

    @Test
    fun `the listener no longer decides on level alone`() {
        val src = wiring()
        assertFalse(
            "level-only wording must not survive",
            src.contains("(if (level == MemoryPressureLevel.CRITICAL) \"admission throttled"),
        )
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
