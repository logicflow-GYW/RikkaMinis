package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * native-offload abstract socket 取名：必须同时区分安装身份（applicationId）
 * 与运行实例身份（uid），否则应用双开（同 applicationId、不同 uid）下的第二个
 * 实例会撞名 → bind 全失败 → onCreate 崩溃/重启循环。
 */
class OffloadSocketTest {

    @Test
    fun `name embeds the application id with dots replaced`() {
        val name = offloadSocketName("com.rikkaminis.app", 10_123)
        assertTrue(name.startsWith("native-offload-"))
        assertTrue(name.contains("com_rikkaminis_app"))
        assertTrue(name.endsWith("-10123"))
    }

    @Test
    fun `name distinguishes two installs of the same device`() {
        val stable = offloadSocketName("com.rikkaminis.app", 10_123)
        val lab = offloadSocketName("com.rikkaminis.app.lab", 10_124)
        assertNotEquals(stable, lab)
    }

    @Test
    fun `name distinguishes dual-app instances of the same application id`() {
        // 应用双开：同一 applicationId，uid 不同（userId 前缀不同）
        val primary = offloadSocketName("com.rikkaminis.app", 10_123)
        val dual = offloadSocketName("com.rikkaminis.app", 99910123)
        assertNotEquals("双开实例必须绑不同的 socket 名", primary, dual)
        assertTrue(dual.endsWith("-99910123"))
    }

    @Test
    fun `name is stable for the same identity`() {
        assertEquals(
            offloadSocketName("com.rikkaminis.app", 42),
            offloadSocketName("com.rikkaminis.app", 42),
        )
    }
}
