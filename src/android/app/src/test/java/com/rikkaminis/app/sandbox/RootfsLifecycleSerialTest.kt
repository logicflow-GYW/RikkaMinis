package com.rikkaminis.app.sandbox

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RootfsLifecycleSerialTest {
    @Test fun `every lifecycle entrypoint holds one common mutex without recursive reacquisition`() {
        val source = source()
        for (fn in listOf("installIfNeeded", "reset", "autoRepair", "restoreCriticalFromAssets")) {
            val start = source.indexOf("suspend fun $fn(")
            assertTrue(start >= 0)
            val wrapper = source.substring(start, source.indexOf("\n    }", start))
            assertTrue(wrapper, wrapper.contains("lifecycleMutex.withLock"))
            assertTrue(wrapper, wrapper.contains("${fn}Locked("))
        }
        assertTrue(source.contains("installIfNeededLocked(forceReinstall = true)"))
        assertTrue(source.contains("runCatching { resetLocked() }"))
        assertTrue(source.contains("val restored = restoreCriticalFromAssetsLocked()"))
        assertEquals(3, Regex("checkActive = currentCoroutineContext\\(\\)::ensureActive").findAll(source).count())
    }

    @Test fun `cancellation interrupts a large entry before it finishes writing`() = runBlocking {
        val root = Files.createTempDirectory("rootfs-cancel").toFile()
        val job = Job()
        var payloadReads = 0
        val header = ByteArray(512)
        "large.bin".toByteArray().copyInto(header)
        "00010000000".toByteArray().copyInto(header, 124) // 2 MiB
        header[156] = '0'.code.toByte()
        val input = object : InputStream() {
            var position = 0
            override fun read(): Int = error("bulk reads only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position < 512) {
                    header.copyInto(b, off, position, position + len)
                    position += len
                    return len
                }
                payloadReads++
                if (payloadReads == 2) job.cancel()
                b.fill(1, off, off + len)
                return len
            }
        }
        try {
            extractTar(input, root, checkActive = job::ensureActive)
            fail("cancelled extraction must throw")
        } catch (_: CancellationException) {
            assertEquals(2, payloadReads)
            assertTrue(File(root, "large.bin").length() < 32768)
        } finally { root.deleteRecursively() }
    }

    @Test fun `skip loop checks cancellation as well`() {
        var checks = 0
        val input = java.io.ByteArrayInputStream(ByteArray(65536))
        try {
            skipFully(input, 65536) {
                checks++
                if (checks == 2) throw CancellationException("stopped")
            }
            fail("skip should stop")
        } catch (_: CancellationException) { assertEquals(2, checks) }
    }

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = File(dir, "src/android/app/src/main/java/com/rikkaminis/app/sandbox/RootfsManager.kt")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("RootfsManager.kt missing")
    }
}
