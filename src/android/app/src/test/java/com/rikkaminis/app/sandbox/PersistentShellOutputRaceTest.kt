package com.rikkaminis.app.sandbox

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PersistentShellOutputRaceTest {
    @Test fun `all callback readers use the synchronized snapshot`() {
        val s = source()
        assertFalse(s.contains("cb.output.toString()"))
        assertFalse(s.contains("it.output.toString()"))
        assertEquals(5, Regex("(?:cb|it)\\.snapshotOutput\\(\\)").findAll(s).count())
        assertTrue(s.contains("fun snapshotOutput(): String = synchronized(outputLock)"))
        assertTrue(s.contains("synchronized(outputLock) { byteBudget.appendTo(output, text) }"))
        assertTrue(s.contains("private val output: StringBuilder"))
    }
    private fun source(): String {
        var d: File? = File(System.getProperty("user.dir"))
        while (d != null) {
            val f=File(d,"src/android/app/src/main/java/com/rikkaminis/app/sandbox/PersistentShell.kt")
            if(f.isFile) return f.readText()
            d=d.parentFile
        }
        error("PersistentShell missing")
    }
}
