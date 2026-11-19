package com.rikkaminis.app

import com.rikkaminis.app.network.NetworkMonitor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ModelServiceNetworkCallbackTest {
    @Test
    fun `the worker callback evicts the shared idle connection before notifying the warmer`() {
        val pool = NetworkMonitor.sharedLLMConnectionPool
        pool.evictAll()
        val client = OkHttpClient.Builder().connectionPool(pool).build()
        val observedPoolSizes = mutableListOf<Int>()
        var callback: (() -> Unit)? = null
        var registrations = 0
        registerModelServiceNetworkChanges(notifyWarmer = { observedPoolSizes += pool.connectionCount() }) {
            registrations++
            callback = it
        }
        assertEquals(1, registrations)
        assertEquals(emptyList<Int>(), observedPoolSizes) // no work at registration time
        try {
            MockWebServer().use { server ->
                server.start()
                repeat(2) {
                    server.enqueue(MockResponse().setBody("ok"))
                    val request = Request.Builder().url(server.url("/")).build()
                    client.newCall(request).execute().use { assertEquals("ok", it.body?.string()) }
                    assertEquals(1, pool.idleConnectionCount())
                    checkNotNull(callback).invoke()
                    assertEquals(0, pool.connectionCount())
                }
            }
            assertEquals(listOf(0, 0), observedPoolSizes)
            assertNull(NetworkMonitor.activeMonitor)
        } finally {
            pool.evictAll()
        }
    }

    @Test
    fun `registration is wired to the process declared for ModelExecutionService and not the main boot path`() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(androidFile("AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val services = doc.getElementsByTagName("service")
        val worker = (0 until services.length).map { services.item(it) as Element }.single {
            it.getAttributeNS(ns, "name") == ".sandbox.offload.ModelExecutionService"
        }
        assertEquals(":modelservice", worker.getAttributeNS(ns, "process"))
        val app = doc.getElementsByTagName("application").item(0) as Element
        assertEquals(".MinisApp", app.getAttributeNS(ns, "name"))
        assertEquals("", app.getAttributeNS(ns, "process"))

        // Pin the call in the worker's actual early-return branch, not merely
        // the existence of a helper with a worker-sounding name.
        val src = androidFile("java/com/rikkaminis/app/MinisApp.kt").readText()
        val startup = src.substringAfter("override fun onCreate() {", "").substringBefore("\n    }\n")
        val workerBranch = Regex("(?m)^        if \\(isModelServiceProcess\\(\\)\\) \\{\\n([\\s\\S]*?)^        }")
            .find(startup)?.groupValues?.get(1) ?: error("Missing modelservice startup branch")
        val code = workerBranch.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("//") }.toList()
        assertEquals(listOf("startModelServiceNetworkMonitoring()", "return"), code.takeLast(2))
        assertEquals(1, Regex("\\bstartModelServiceNetworkMonitoring\\s*\\(").findAll(startup).count())
        assertFalse(workerBranch.contains("networkMonitor.start(this)"))
        assertEquals(1, startup.lineSequence().count { it.trim() == "networkMonitor.start(this)" })

        val processCheck = src.substringAfter("private fun isModelServiceProcess(): Boolean {", "")
            .substringBefore("\n    }\n")
        assertTrue(processCheck.contains("Application.getProcessName()?.endsWith(\":modelservice\") == true"))
        assertTrue(processCheck.contains("it.pid == pid && it.processName.endsWith(\":modelservice\")"))

        val registration = src.substringAfter("private fun startModelServiceNetworkMonitoring() {", "")
            .substringBefore("\n    }\n")
        assertTrue(registration.contains("manager.registerDefaultNetworkCallback("))
        assertTrue(registration.contains("override fun onAvailable(network: Network) = onChanged()"))
        assertTrue(registration.contains("override fun onLost(network: Network) = onChanged()"))
        assertFalse(registration.contains("networkMonitor.start("))
    }

    private fun androidFile(relative: String): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "src/android/app/src/main/$relative") }
            .first { it.isFile }
}
