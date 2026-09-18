package com.rikkaminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-log-single-writer] Regression anchor for per-process log file names.
 * The worker (":modelservice") must never share a daily file with the main
 * process — measured 2026-09-18: out-of-order lines (main lagging 4-24s) plus
 * one garbled line once d09f3adf made both processes write the same files.
 */
class LogFileNameProcessTest {

    private val pkg = "com.rikkaminis.app"

    @Test fun `main process keeps the historical file names`() {
        assertNull(processLogSuffix(pkg, pkg))
        assertEquals("minis-2026-09-18.log", channelFileName("minis", "2026-09-18", null))
        assertEquals("debug-2026-09-18.log", channelFileName("debug", "2026-09-18", null))
    }

    @Test fun `worker process gets suffixed files on both channels`() {
        assertEquals("modelservice", processLogSuffix("$pkg:modelservice", pkg))
        assertEquals(
            "minis-2026-09-18.modelservice.log",
            channelFileName("minis", "2026-09-18", "modelservice"),
        )
        assertEquals(
            "debug-2026-09-18.modelservice.log",
            channelFileName("debug", "2026-09-18", "modelservice"),
        )
    }

    @Test fun `rotate name strips only the trailing log segment`() {
        val base = channelFileName("debug", "2026-09-18", "modelservice")
        assertEquals(
            "debug-2026-09-18.modelservice.1.log",
            base.substringBeforeLast(".log") + ".1.log",
        )
    }

    @Test fun `trailing NUL from cmdline is stripped`() {
        assertEquals("modelservice", processLogSuffix("$pkg:modelservice\u0000", pkg))
    }

    @Test fun `null and empty cmdline fall back to the main name`() {
        assertNull(processLogSuffix(null, pkg))
        assertNull(processLogSuffix("", pkg))
    }

    @Test fun `cmdline without a tag falls back to the main name`() {
        assertNull(processLogSuffix(pkg, pkg))
    }

    @Test fun `other tagged process keeps its own suffix`() {
        assertEquals("toolservice", processLogSuffix("$pkg:toolservice", pkg))
    }

    @Test fun `foreign package names still get suffixed`() {
        assertEquals(
            "sandboxed_process0",
            processLogSuffix("org.chromium:sandboxed_process0", pkg),
        )
    }
}
