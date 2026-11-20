package com.rikkaminis.app.data.repository

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Real production files + real Python writers. All data is synthetic and directory-local. */
class MCPConfigTransactionTest {
    private fun config(id: String, enabled: Boolean = true) =
        MCPRepository.MCPServerConfig(id, enabled = enabled, command = "synthetic", createdAt = 123)

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("mcp-tx-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun root(dir: File) = JSONObject(File(dir, "servers.json").readText())
    private fun entries(dir: File) = root(dir).getJSONObject("mcpServers")
    private fun ids(dir: File) = entries(dir).keys().asSequence().toSet()
    private fun seed(dir: File, vararg names: String) {
        val obj = JSONObject()
        for (name in names) obj.put(name, JSONObject().put("command", "synthetic").put("enabled", true))
        File(dir, "servers.json").writeText(JSONObject().put("mcpServers", obj).toString())
    }

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            if (File(dir, CONFIG_REL).isFile) return dir
            dir = dir.parentFile
        }
        error("production config.py not found")
    }

    private inner class Worker(dir: File, operation: String, name: String) : AutoCloseable {
        private val process = ProcessBuilder(
            "python3", "-u", "-c", WORKER, File(repoRoot(), CONFIG_REL).absolutePath,
            dir.absolutePath, operation, name,
        ).redirectErrorStream(true).start()
        private val reader = process.inputStream.bufferedReader()
        private val writer = process.outputStream.bufferedWriter()
        private val reads = Executors.newSingleThreadExecutor()
        fun line(expected: String) {
            val next = reads.submit(Callable { reader.readLine() })
            assertEquals(expected, next.get(10, TimeUnit.SECONDS))
        }
        fun go() { writer.write("go\n"); writer.flush() }
        fun finish() {
            assertTrue("Python worker timeout", process.waitFor(15, TimeUnit.SECONDS))
            val output = reader.readText()
            assertEquals("Python failed: $output", 0, process.exitValue())
            assertTrue("Python did not finish: $output", output.contains("DONE"))
        }
        override fun close() {
            process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
            reads.shutdownNow()
        }
    }

    /** Both Python writers queue behind Java's lock, with both pre-lock hooks observed. */
    private fun pythonPair(dir: File, opA: String, nameA: String, opB: String, nameB: String) {
        Worker(dir, opA, nameA).use { a ->
            Worker(dir, opB, nameB).use { b ->
                a.line("READY"); b.line("READY")
                ServersJsonIo.withTransaction(dir, "servers.json") {
                    a.go(); b.go()
                    a.line("WAIT"); b.line("WAIT")
                }
                a.finish(); b.finish()
            }
        }
    }

    @Test fun pythonAddsKeepBothDeltas() = withDir { dir ->
        seed(dir, "base")
        pythonPair(dir, "add", "a", "add", "b")
        assertEquals(setOf("base", "a", "b"), ids(dir))
    }

    @Test fun pythonAddAndRemoveKeepBothDeltas() = withDir { dir ->
        seed(dir, "old", "keep")
        pythonPair(dir, "add", "new", "remove", "old")
        assertEquals(setOf("keep", "new"), ids(dir))
    }

    @Test fun pythonEnableAndAddKeepBothDeltas() = withDir { dir ->
        seed(dir, "target")
        pythonPair(dir, "disable", "target", "add", "new")
        assertEquals(setOf("target", "new"), ids(dir))
        assertFalse(entries(dir).getJSONObject("target").getBoolean("enabled"))
    }

    @Test fun javaLockExcludesPythonRecordLock() = withDir { dir ->
        seed(dir)
        Worker(dir, "try", "unused").use { worker ->
            worker.line("READY")
            ServersJsonIo.withTransaction(dir, "servers.json") {
                worker.go(); worker.line("BUSY")
            }
            worker.finish()
        }
        assertTrue(File(dir, "servers.json.lock").isFile)
    }

    @Test fun pythonLockExcludesJavaAndAndroidRereadsAfterWaiting() = withDir { dir ->
        seed(dir, "base")
        val repo = MCPRepository(dir)
        Worker(dir, "hold", "guest").use { worker ->
            worker.line("READY"); worker.go(); worker.line("WAIT"); worker.line("LOCKED")
            RandomAccessFile(File(dir, "servers.json.lock"), "rw").use { file ->
                val unexpected = file.channel.tryLock()
                unexpected?.release()
                assertNull("Java must use the same record-lock namespace", unexpected)
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val entered = CountDownLatch(1)
                val future = executor.submit(Callable {
                    entered.countDown()
                    repo.add(config("android"))
                })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                try {
                    future.get(250, TimeUnit.MILLISECONDS)
                    fail("Android transaction did not wait for Python")
                } catch (_: TimeoutException) { /* expected exclusion */ }
                worker.go(); worker.finish()
                assertTrue(future.get(10, TimeUnit.SECONDS))
                assertEquals(setOf("base", "guest", "android"), ids(dir))
                assertEquals(ids(dir), repo.servers.value.map { it.id }.toSet())
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun staleAndroidAddPreservesGuestAndUnknownFields() = withDir { dir ->
        seed(dir, "base")
        val repo = MCPRepository(dir)
        val changed = root(dir).put("extension", JSONObject().put("keep", 7))
        changed.getJSONObject("mcpServers").put("guest", JSONObject().put("command", "x").put("future", "keep"))
        File(dir, "servers.json").writeText(changed.toString())
        assertTrue(repo.add(config("android")))
        assertEquals(setOf("base", "guest", "android"), ids(dir))
        assertEquals(7, root(dir).getJSONObject("extension").getInt("keep"))
        assertEquals("keep", entries(dir).getJSONObject("guest").getString("future"))
        assertEquals(ids(dir), repo.servers.value.map { it.id }.toSet())
    }

    @Test fun updateChecksLatestDiskNotStaleMemory() = withDir { dir ->
        seed(dir, "old")
        val repo = MCPRepository(dir)
        seed(dir, "guest")
        assertFalse(repo.update(config("old")))
        assertTrue(repo.update(config("guest", enabled = false)))
        assertEquals(setOf("guest"), ids(dir))
        assertFalse(repo.servers.value.single().enabled)
    }

    @Test fun staleDeletePreservesIndependentGuestAdd() = withDir { dir ->
        seed(dir, "old")
        val repo = MCPRepository(dir)
        seed(dir, "old", "guest")
        repo.delete("old")
        assertEquals(setOf("guest"), ids(dir))
        assertEquals(listOf("guest"), repo.servers.value.map { it.id })
    }

    @Test fun enabledIsAFieldDeltaAndToggleUsesLatestValue() = withDir { dir ->
        seed(dir, "target")
        val repo = MCPRepository(dir)
        val changed = root(dir)
        val target = changed.getJSONObject("mcpServers").getJSONObject("target")
        target.put("enabled", false).put("command", "changed-by-guest").put("future", 9)
        changed.getJSONObject("mcpServers").put("new", JSONObject().put("command", "x"))
        File(dir, "servers.json").writeText(changed.toString())
        repo.toggle("target")
        assertTrue(entries(dir).getJSONObject("target").getBoolean("enabled"))
        repo.setEnabled("target", false)
        val result = entries(dir).getJSONObject("target")
        assertFalse(result.getBoolean("enabled"))
        assertEquals("changed-by-guest", result.getString("command"))
        assertEquals(9, result.getInt("future"))
        assertEquals(setOf("target", "new"), ids(dir))
    }

    @Test fun importMergesIntoLatestDiskWithNameScopedLastWriteWins() = withDir { dir ->
        seed(dir, "same")
        val repo = MCPRepository(dir)
        seed(dir, "same", "guest")
        val imported = repo.importJSON("""{"mcpServers":{"same":{"command":"winner"},"new":{"command":"x"}}}""")
        assertEquals(2, imported.size)
        assertEquals(setOf("same", "guest", "new"), ids(dir))
        assertEquals("winner", entries(dir).getJSONObject("same").getString("command"))
        assertTrue(repo.add(config("same", enabled = false)))
        assertEquals("synthetic", entries(dir).getJSONObject("same").getString("command"))
        assertFalse(entries(dir).getJSONObject("same").getBoolean("enabled"))
    }

    @Test fun backfillPreservesRawFieldsAndStableTimestamps() = withDir { dir ->
        File(dir, "servers.json").writeText("""{"meta":17,"mcpServers":{"a":{"command":"x","future":{"keep":1}},"b":{"url":"https://example.invalid/mcp"}}}""")
        val repo = MCPRepository(dir)
        val first = entries(dir).getJSONObject("a").getLong("createdAt")
        repo.load()
        assertEquals(first, entries(dir).getJSONObject("a").getLong("createdAt"))
        assertTrue(entries(dir).getJSONObject("b").has("createdAt"))
        assertEquals(17, root(dir).getInt("meta"))
        assertEquals(1, entries(dir).getJSONObject("a").getJSONObject("future").getInt("keep"))
    }

    @Test fun unreadableAndFailedWritesNeverPublishSpeculativeState() = withDir { dir ->
        seed(dir, "base")
        val repo = MCPRepository(dir)
        File(dir, "servers.json").writeText("{broken")
        assertFalse(repo.add(config("lost")))
        assertEquals("{broken", File(dir, "servers.json").readText())
        assertEquals(listOf("base"), repo.servers.value.map { it.id })
        File(dir, "servers.json").delete()
        val target = File(dir, "servers.json").apply { mkdir() }
        File(target, "do-not-replace").writeText("kept")
        assertFalse(repo.add(config("lost")))
        assertEquals(listOf("base"), repo.servers.value.map { it.id })
        assertFalse(ServersJsonIo.writeAtomic(dir, "servers.json", "{}"))
        assertEquals("kept", File(target, "do-not-replace").readText())
    }

    @Test fun lockReleasesOnExceptionAndUniqueTempsStayUnique() = withDir { dir ->
        try {
            ServersJsonIo.withTransaction(dir, "servers.json") { error("synthetic") }
        } catch (e: IllegalStateException) { assertEquals("synthetic", e.message) }
        assertTrue(ServersJsonIo.withTransaction(dir, "servers.json") {
            ServersJsonIo.writeAtomic(dir, "servers.json", """{"mcpServers":{}}""")
        })
        val a = ServersJsonIo.tempFileFor(dir, "servers.json")
        val b = ServersJsonIo.tempFileFor(dir, "servers.json")
        assertNotEquals(a, b)
        assertEquals(emptySet<String>(), ids(dir))
        assertTrue(File(dir, "servers.json.lock").exists())
    }

    @Test fun javaThreadsSerializeWithoutOverlappingLockAndLoseNoDeltas() = withDir { dir ->
        seed(dir)
        val a = MCPRepository(dir)
        val b = MCPRepository(dir)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable { repeat(8) { assertTrue(a.add(config("a$it"))) } })
            val second = executor.submit(Callable { repeat(8) { assertTrue(b.add(config("b$it"))) } })
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS)
            assertEquals((0..7).flatMap { listOf("a$it", "b$it") }.toSet(), ids(dir))
        } finally { executor.shutdownNow() }
    }

    @Test fun crossLanguageSameNameUpdatesAreLastCommitWins() = withDir { dir ->
        seed(dir, "same", "keep")
        val repo = MCPRepository(dir)
        assertTrue(repo.add(config("same", enabled = false)))
        Worker(dir, "add", "same").use { worker ->
            worker.line("READY"); worker.go(); worker.line("WAIT"); worker.finish()
        }
        assertFalse(entries(dir).getJSONObject("same").has("enabled"))
        assertTrue(repo.update(config("same", enabled = false)))
        assertFalse(entries(dir).getJSONObject("same").getBoolean("enabled"))
        assertEquals(setOf("same", "keep"), ids(dir))
    }

    @Test fun pythonReleasesLockOnFailureAndDoesNotLoseLaterWrite() = withDir { dir ->
        seed(dir)
        Worker(dir, "recover", "saved").use { worker ->
            worker.line("READY"); worker.go(); worker.line("WAIT"); worker.line("WAIT"); worker.finish()
        }
        assertEquals(setOf("saved"), ids(dir))
    }

    @Test fun backfillWaitsForGuestAndRereadsItsLatestAdd() = withDir { dir ->
        seed(dir, "base")
        val repo = MCPRepository(dir)
        Worker(dir, "hold-unmarked", "guest").use { worker ->
            worker.line("READY"); worker.go(); worker.line("WAIT"); worker.line("LOCKED")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val entered = CountDownLatch(1)
                val load = executor.submit(Callable { entered.countDown(); repo.load() })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                try {
                    load.get(250, TimeUnit.MILLISECONDS)
                    fail("backfill escaped the shared lock")
                } catch (_: TimeoutException) { /* lock must cover backfill too */ }
                worker.go(); worker.finish(); load.get(10, TimeUnit.SECONDS)
                assertEquals(setOf("base", "guest"), ids(dir))
                assertTrue(entries(dir).getJSONObject("guest").has("createdAt"))
                assertEquals(ids(dir), repo.servers.value.map { it.id }.toSet())
            } finally { executor.shutdownNow() }
        }
    }

    private companion object {
        private const val CONFIG_REL = "src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/utils/config.py"
        private val WORKER = """
            import contextlib, fcntl, importlib.util, json, os, sys
            spec = importlib.util.spec_from_file_location("tested_config", sys.argv[1])
            config = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(config)
            config.CONFIG_DIR = sys.argv[2]
            config.CONFIG_PATH = os.path.join(config.CONFIG_DIR, "servers.json")
            op, name = sys.argv[3:5]
            real_lock = config._config_lock
            @contextlib.contextmanager
            def observed_lock():
                print("WAIT", flush=True)
                with real_lock():
                    yield
            config._config_lock = observed_lock
            print("READY", flush=True)
            assert sys.stdin.readline()
            if op == "try":
                with open(config.CONFIG_PATH + ".lock", "a+b") as f:
                    try:
                        fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        print("BUSY", flush=True)
                    else:
                        print("UNLOCKED", flush=True)
            elif op in ("hold", "hold-unmarked"):
                with config._config_lock():
                    print("LOCKED", flush=True)
                    assert sys.stdin.readline()
                    doc = config.load_config()
                    doc["mcpServers"][name] = {"command": "synthetic"}
                    if op == "hold":
                        doc["mcpServers"][name]["createdAt"] = 123
                    config.save_config(doc)
            elif op == "recover":
                save = config.save_config
                def fail_save(doc):
                    raise OSError("synthetic")
                config.save_config = fail_save
                try:
                    config.upsert_server("failed", {"command": "synthetic"})
                    raise AssertionError("write should fail")
                except OSError:
                    pass
                finally:
                    config.save_config = save
                config.upsert_server(name, {"command": "synthetic", "createdAt": 123})
            elif op == "add":
                config.upsert_server(name, {"command": "synthetic", "createdAt": 123})
            elif op == "remove":
                assert config.remove_server(name)
            elif op == "disable":
                assert config.set_enabled(name, False)
            else:
                raise AssertionError("unknown operation")
            print("DONE", flush=True)
        """.trimIndent()
    }
}
