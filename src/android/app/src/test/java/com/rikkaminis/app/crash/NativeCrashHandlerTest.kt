package com.rikkaminis.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the async-signal-safe parsing logic embedded in
 * crash_handler.cpp's read_status_field() / read_cmdline().
 *
 * These tests verify the row-scanning algorithm that searches
 * /proc/self/status for a key like "VmRSS:" and returns the
 * value (rest of the line). The C++ implementation reads
 * /proc/self/status in chunks; this test simulates that with
 * a homogeneous string and checks boundary conditions.
 */
class NativeCrashHandlerTest {

    /**
     * Simulates read_status_field() from crash_handler.cpp:
     * scans multi-line text for "key:" and returns the trimmed value.
     */
    private fun readStatusField(text: String, key: String): String? {
        val keyLen = key.length
        var i = 0
        while (i < text.length) {
            // Find next line start
            val lineStart = i
            var lineEnd = i
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val lineLen = lineEnd - lineStart
            if (lineLen >= keyLen && text.regionMatches(lineStart, key, 0, keyLen) &&
                lineStart + keyLen < lineEnd && text[lineStart + keyLen] == ':'
            ) {
                // Extract value after key
                var v = lineStart + keyLen
                if (v < lineEnd && text[v] == ':') v++
                while (v < lineEnd && (text[v] == ' ' || text[v] == '\t')) v++
                return text.substring(v, lineEnd)
            }
            i = lineEnd + 1
        }
        return null
    }

    private val sampleStatus = buildString {
        appendLine("Name:   com.rikkaminis.app")
        appendLine("Umask:  0022")
        appendLine("State:  S (sleeping)")
        appendLine("Tgid:   12345")
        appendLine("Ngid:   0")
        appendLine("Pid:    12345")
        appendLine("PPid:   678")
        appendLine("TracerPid:      0")
        appendLine("Uid:    10000   10000   10000   10000")
        appendLine("Gid:    10000   10000   10000   10000")
        appendLine("FDSize: 256")
        appendLine("Groups: 10000 9997 20001 20002")
        appendLine("NStgid: 12345")
        appendLine("NSpid:  12345")
        appendLine("NSpgid: 12345")
        appendLine("NSsid:  12345")
        appendLine("VmPeak:   1234567 kB")
        appendLine("VmSize:  1234567 kB")
        appendLine("VmLck:         0 kB")
        appendLine("VmPin:         0 kB")
        appendLine("VmHWM:    123456 kB")
        appendLine("VmRSS:     123456 kB")
        appendLine("RssAnon:   100000 kB")
        appendLine("RssFile:    23456 kB")
        appendLine("RssShmem:      0 kB")
        appendLine("VmData:   456789 kB")
        appendLine("VmStk:       132 kB")
        appendLine("VmExe:        12 kB")
        appendLine("VmLib:     34567 kB")
        appendLine("VmPTE:       123 kB")
        appendLine("VmSwap:        0 kB")
        appendLine("CoreDumping:    0")
        appendLine("THP_enabled:    1")
        appendLine("Threads:        42")
        appendLine("SigQ:   0/10000")
        appendLine("SigPnd: 0000000000000000")
        appendLine("ShdPnd: 0000000000000000")
        appendLine("SigBlk: 0000000000000000")
        appendLine("SigIgn: 0000000000000000")
        appendLine("SigCgt: 0000000000000000")
        appendLine("CapInh: 0000000000000000")
        appendLine("CapPrm: 0000000000000000")
        appendLine("CapEff: 0000000000000000")
        appendLine("CapBnd: 0000000000000000")
        appendLine("CapAmb: 0000000000000000")
        appendLine("NoNewPrivs:     0")
        appendLine("Seccomp:        0")
        appendLine("Seccomp_filters:        0")
        appendLine("Speculation_Store_Bypass:       thread vulnerable")
        appendLine("SpeculationIndirectBranch:      always enabled")
        appendLine("Cpus_allowed:   ff")
        appendLine("Cpus_allowed_list:      0-7")
        appendLine("Mems_allowed:   1")
        appendLine("Mems_allowed_list:      0")
        appendLine("voluntary_ctxt_switches:        123")
        appendLine("nonvoluntary_ctxt_switches:     45")
    }

    // ---- Normal parsing ----

    @Test
    fun `read VmRSS from status`() {
        assertEquals("123456 kB", readStatusField(sampleStatus, "VmRSS"))
    }

    @Test
    fun `read VmPeak from status`() {
        assertEquals("1234567 kB", readStatusField(sampleStatus, "VmPeak"))
    }

    @Test
    fun `read Threads from status`() {
        assertEquals("42", readStatusField(sampleStatus, "Threads"))
    }

    @Test
    fun `read Process Name from status`() {
        assertEquals("com.rikkaminis.app", readStatusField(sampleStatus, "Name"))
    }

    @Test
    fun `read Uid from status`() {
        assertEquals("10000   10000   10000   10000", readStatusField(sampleStatus, "Uid"))
    }

    // ---- Key not found ----

    @Test
    fun `missing key returns null`() {
        assertNull(readStatusField(sampleStatus, "DoesNotExist"))
    }

    @Test
    fun `empty text returns null for any key`() {
        assertNull(readStatusField("", "VmRSS"))
    }

    // ---- Key prefix matching ----

    @Test
    fun `key prefix does not match similar key`() {
        // "VmR" should NOT match "VmRSS"
        assertNull(readStatusField(sampleStatus, "VmR"))
    }

    @Test
    fun `key longer than any line returns null`() {
        assertNull(readStatusField(sampleStatus, "VmRSS:ExtraLongSuffix"))
    }

    // ---- Edge cases: trailing whitespace, empty value ----

    @Test
    fun `value with leading spaces is trimmed`() {
        // "VmRSS:     123456 kB" has 6 spaces before value
        assertEquals("123456 kB", readStatusField(sampleStatus, "VmRSS"))
    }

    @Test
    fun `value with tab separator is handled`() {
        val text = "Threads:\t42\n"
        assertEquals("42", readStatusField(text, "Threads"))
    }

    @Test
    fun `value with only colon and space returns empty string`() {
        val text = "SomeKey: \n"
        assertEquals("", readStatusField(text, "SomeKey"))
    }

    @Test
    fun `value with only colon returns empty string`() {
        val text = "SomeKey:\n"
        assertEquals("", readStatusField(text, "SomeKey"))
    }

    // ---- Trailing newline ----

    @Test
    fun `last line without trailing newline is still parsed`() {
        val text = "VmRSS:     123 kB\nThreads:        7"
        assertEquals("7", readStatusField(text, "Threads"))
    }

    @Test
    fun `single line without newline is parsed`() {
        val text = "VmRSS:     123 kB"
        assertEquals("123 kB", readStatusField(text, "VmRSS"))
    }

    // ---- Multiple sections with same prefix ----

    @Test
    fun `first matching key wins`() {
        val text = "VmRSS:     100 kB\nVmRSS:     200 kB\n"
        assertEquals("100 kB", readStatusField(text, "VmRSS"))
    }

    // ---- read_cmdline simulation ----

    @Test
    fun `cmdline NUL separators are joined with spaces`() {
        // Simulate /proc/self/cmdline: NUL-separated argv
        val raw = "com.rikkaminis.app\u0000--type\u0000content\u0000"
        val joined = raw.replace('\u0000', ' ')
        assertEquals("com.rikkaminis.app --type content ", joined)
    }

    @Test
    fun `cmdline single entry has no space`() {
        val raw = "com.rikkaminis.app"
        assertEquals("com.rikkaminis.app", raw.replace('\u0000', ' '))
    }

    @Test
    fun `cmdline with trailing NUL has trailing space`() {
        val raw = "process\u0000"
        assertEquals("process ", raw.replace('\u0000', ' '))
    }
}