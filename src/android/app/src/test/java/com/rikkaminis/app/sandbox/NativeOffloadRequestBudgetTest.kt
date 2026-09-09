package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM-side verification of the offload request frame size cap
 * (fix/native-offload-size-limit): a multi-MB request frame must be
 * rejected by [OffloadRequestBudget] with a clear error — instead of
 * materializing unbounded arg/env strings on the host — while
 * normal-sized frames decode exactly as before.
 *
 * The byte-budget decode helpers are pure JVM ([DataInputStream]),
 * so the frame protocol can be exercised headless; only the LocalSocket
 * accept loop needs the Android runtime.
 */
class NativeOffloadRequestBudgetTest {

    private val magic = 0x46464F4E
    private val version = 1

    // ---- frame helpers (same LE encoding as the proot extension) ----

    private fun ByteArrayOutputStream.writeLEInt(v: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    }

    private fun ByteArrayOutputStream.writeLEString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLEInt(bytes.size)
        if (bytes.isNotEmpty()) write(bytes)
    }

    private fun encodeRequest(
        pid: Int,
        argv: List<String>,
        env: Map<String, String>,
        cwd: String,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        bos.writeLEInt(magic)
        bos.writeLEInt(version)
        bos.writeLEInt(pid)
        bos.writeLEInt(argv.size)
        argv.forEach { bos.writeLEString(it) }
        bos.writeLEInt(env.size)
        env.forEach { (k, v) -> bos.writeLEString("$k=$v") }
        bos.writeLEString(cwd)
        out.flush()
        return bos.toByteArray()
    }

    /** Decode a frame the same way handleClient does; returns (argc, envc, cwd). */
    private fun decodeRequest(bytes: ByteArray, budget: OffloadRequestBudget): Triple<Int, Int, String> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(magic, input.readLEInt(budget))
        assertEquals(version, input.readLEInt(budget))
        input.readLEInt(budget) // pid
        val argc = input.readLEInt(budget)
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(input.readLEString(budget)) }
        val envc = input.readLEInt(budget)
        val env = LinkedHashMap<String, String>(envc)
        repeat(envc) {
            val s = input.readLEString(budget)
            val eq = s.indexOf('=')
            if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
        }
        val cwd = input.readLEString(budget)
        return Triple(argv.size, env.size, cwd)
    }

    // ---- tests ----

    @Test
    fun `normal request decodes within the default budget`() {
        val frame = encodeRequest(
            pid = 1234,
            argv = listOf("android-shizuku-cli", "exec", "pm", "list", "packages"),
            env = mapOf(
                "MINIS_CHAT_SESSION_ID" to "sess-abc",
                "PATH" to "/usr/bin:/bin",
            ),
            cwd = "/data/local/tmp",
        )
        val budget = OffloadRequestBudget(MAX_REQUEST_BYTES)
        val (argc, envc, cwd) = decodeRequest(frame, budget)

        assertEquals(5, argc)
        assertEquals(2, envc)
        assertEquals("/data/local/tmp", cwd)
        // A realistic request uses only a tiny fraction of the 1 MiB cap.
        assertTrue("expected small frame, got ${budget.total} bytes", budget.total < 1024)
    }

    @Test
    fun `oversized request is rejected with a clear error`() {
        // ~1.5 MiB total frame: 3 env entries of ~500 KiB each. Each
        // string is under the 1 MiB per-field cap, so only the aggregate
        // budget can catch it. Mirrors the "5 MB request" verification.
        val big = String(CharArray(500 * 1024) { 'x' })
        val frame = encodeRequest(
            pid = 1,
            argv = listOf("android-shizuku-cli"),
            env = mapOf(
                "A" to big,
                "B" to big,
                "C" to big,
            ),
            cwd = "/",
        )
        assertTrue("sanity: frame must exceed the 1 MiB cap", frame.size > MAX_REQUEST_BYTES)

        val budget = OffloadRequestBudget(MAX_REQUEST_BYTES)
        val e = assertThrows(IllegalStateException::class.java) {
            decodeRequest(frame, budget)
        }
        assertTrue("unexpected message: ${e.message}", e.message!!.contains("offload request too large"))
        // The budget stops the decode the moment the cap is crossed —
        // it never accumulates the whole payload.
        assertTrue(budget.total < frame.size)
    }

    @Test
    fun `same oversized frame decodes fine with a generous budget`() {
        val big = String(CharArray(500 * 1024) { 'x' })
        val frame = encodeRequest(
            pid = 1,
            argv = listOf("android-shizuku-cli"),
            env = mapOf("A" to big, "B" to big, "C" to big),
            cwd = "/",
        )
        val budget = OffloadRequestBudget(8 * 1024 * 1024)
        val (argc, envc, cwd) = decodeRequest(frame, budget)

        assertEquals(1, argc)
        assertEquals(3, envc)
        assertEquals("/", cwd)
    }

    @Test
    fun `per-field string cap still enforced independent of budget`() {
        // A single string larger than 1 MiB is rejected by the per-field
        // check even when the aggregate budget would allow it.
        val huge = String(CharArray(2 * 1024 * 1024) { 'y' })
        val frame = encodeRequest(pid = 1, argv = listOf("tool"), env = mapOf("BIG" to huge), cwd = "/")

        val budget = OffloadRequestBudget(8 * 1024 * 1024)
        val e = assertThrows(IllegalStateException::class.java) {
            decodeRequest(frame, budget)
        }
        assertTrue("unexpected message: ${e.message}", e.message!!.contains("bad string len"))
    }

    // ---- budget unit behavior ----

    @Test
    fun `budget accumulates and only throws past the cap`() {
        val budget = OffloadRequestBudget(100)
        budget.charge(60)
        assertEquals(60, budget.total)
        budget.charge(40) // exactly at the cap: allowed
        assertEquals(100, budget.total)
        assertThrows(IllegalStateException::class.java) { budget.charge(1) }
        // CHARGE IS EFFECTIVE (total already incremented past the cap).
        assertEquals(101, budget.total)
    }
}