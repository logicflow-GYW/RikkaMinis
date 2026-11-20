package com.rikkaminis.app.ui.sandbox

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs the real detector, shared speed-test body, user entry points, prefs and
 * config writes. Only HTTP is intercepted: every probe parks until user input
 * has landed. One reachable non-official mirror per category makes the fastest
 * result deterministic without timing thresholds or external network access. */
@OptIn(ExperimentalCoroutinesApi::class)
class MirrorAutoDetectSettlementTest {
    @get:Rule val temp = TemporaryFolder()
    private val vm = MirrorSpeedTestViewModel
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val release = CountDownLatch(1)
    private val arrived = CountDownLatch(MirrorCatalog.allMirrors.size)
    private val prefs = FakePrefs()
    private lateinit var context: Context
    private lateinit var client: OkHttpClient
    private lateinit var originalInterceptors: List<Interceptor>
    private val winners = mutableListOf("alpine.aliyun", "pip.tuna", "npm.npmmirror")

    private fun field(name: String) = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun job(name: String) = field(name).get(vm) as? Job
    private fun onMain(block: () -> Unit) = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun clearIntent() {
        // Optional so the exact same behavioral tests can replay the pre-fix VM.
        val intent = vm.javaClass.declaredFields.firstOrNull { it.name == "userIntentCategories" } ?: return
        intent.isAccessible = true
        (intent.get(vm) as MutableSet<*>).clear()
    }

    @Before fun setUp() {
        Dispatchers.setMain(main)
        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = temp.root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        onMain {
            vm.selectedMirrorId.clear()
            vm.useCustomMirror.clear()
            vm.results.clear()
            clearIntent()
            field("loaded").setBoolean(vm, false)
            field("settleOnComplete").setBoolean(vm, true)
        }
        client = field("httpClient").get(vm) as OkHttpClient
        originalInterceptors = client.interceptors
        // Replace an instance field only; no production seam or static-final
        // mutation. Restored in tearDown so the singleton cannot leak this fake.
        OkHttpClient::class.java.getDeclaredField("interceptors").apply {
            isAccessible = true
            set(client, listOf(Interceptor { chain ->
                arrived.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "probe release timed out" }
                val reachable = winners.any { MirrorCatalog.findById(it)!!.testURL == chain.request().url.toString() }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (reachable) 200 else 503).message("test")
                    .body("".toResponseBody()).build()
            }))
        }
    }

    @After fun tearDown() {
        release.countDown()
        try {
            awaitJobs()
        } finally {
            if (::client.isInitialized && ::originalInterceptors.isInitialized) {
                OkHttpClient::class.java.getDeclaredField("interceptors").apply {
                    isAccessible = true
                    set(client, originalInterceptors)
                }
            }
            onMain {
                vm.selectedMirrorId.clear()
                vm.useCustomMirror.clear()
                vm.results.clear()
                clearIntent()
                field("loaded").setBoolean(vm, false)
            }
            Dispatchers.resetMain()
            main.close()
        }
    }

    private fun startPaused() {
        prefs.edit().putBoolean("rootfs.freshInstall", true).apply()
        onMain { vm.autoDetectOnceIfNeeded(context) }
        assertTrue("real HTTP probes must reach the barrier", arrived.await(10, TimeUnit.SECONDS))
        assertTrue("detector must still be in flight", job("autoDetectJob")!!.isActive)
    }

    private fun finish() {
        release.countDown()
        awaitJobs()
    }

    private fun awaitJobs() = runBlocking {
        withTimeout(15_000) {
            job("autoDetectJob")?.join()
            val scope = field("scope").get(vm) as CoroutineScope
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        }
    }

    private fun assertChoice(cat: MirrorCategory, id: String) {
        assertEquals(id, vm.selectedMirrorId[cat])
        assertEquals(true, vm.useCustomMirror[cat])
        assertEquals(MirrorCatalog.findById(id)!!.baseURL, prefs.getString("mirror.selected.${cat.key}", null))
        assertTrue(prefs.getBoolean("mirror.useCustom.${cat.key}", false))
        assertTrue(File(temp.root, "alpine-rootfs/${cat.configPath}").readText().contains(MirrorCatalog.findById(id)!!.baseURL))
    }

    @Test fun manualChoiceDuringDetectionWinsWithoutCancellingOtherCategories() {
        startPaused()
        onMain { vm.selectMirror(context, MirrorCatalog.findById("alpine.tuna")!!) }
        assertTrue("a manual choice must NOT cancel the detector", job("autoDetectJob")!!.isActive)
        finish()
        assertChoice(MirrorCategory.ALPINE, "alpine.tuna")
        assertChoice(MirrorCategory.PIP, "pip.tuna")
        assertChoice(MirrorCategory.NPM, "npm.npmmirror")
    }

    @Test fun offWithoutSelectedIdDuringDetectionIsNotReenabled() {
        startPaused()
        onMain { vm.setUseCustom(context, MirrorCategory.ALPINE, false) }
        assertTrue("OFF must only protect its category", job("autoDetectJob")!!.isActive)
        finish()
        assertNull(vm.selectedMirrorId[MirrorCategory.ALPINE])
        assertEquals(false, vm.useCustomMirror[MirrorCategory.ALPINE])
        assertFalse(prefs.getBoolean("mirror.useCustom.alpine", true))
        assertNull(prefs.getString("mirror.selected.alpine", null))
        assertChoice(MirrorCategory.PIP, "pip.tuna")
        assertChoice(MirrorCategory.NPM, "npm.npmmirror")
    }

    @Test fun choiceThenOffDuringDetectionKeepsTheIdButStaysOfficial() {
        startPaused()
        onMain {
            vm.selectMirror(context, MirrorCatalog.findById("alpine.tuna")!!)
            vm.setUseCustom(context, MirrorCategory.ALPINE, false)
        }
        finish()
        assertEquals("alpine.tuna", vm.selectedMirrorId[MirrorCategory.ALPINE])
        assertEquals(false, vm.useCustomMirror[MirrorCategory.ALPINE])
        assertFalse(prefs.getBoolean("mirror.useCustom.alpine", true))
        assertChoice(MirrorCategory.PIP, "pip.tuna")
    }

    @Test fun persistedChoiceBeforeDetectionIsPreserved() {
        prefs.edit().putString("mirror.selected.alpine", MirrorCatalog.findById("alpine.tuna")!!.baseURL)
            .putBoolean("mirror.useCustom.alpine", false).apply()
        startPaused()
        finish()
        assertEquals("alpine.tuna", vm.selectedMirrorId[MirrorCategory.ALPINE])
        assertEquals(false, vm.useCustomMirror[MirrorCategory.ALPINE])
        assertFalse(prefs.getBoolean("mirror.useCustom.alpine", true))
        assertChoice(MirrorCategory.PIP, "pip.tuna")
    }

    @Test fun offBeforeDetectionWithoutAnIdIsPreserved() {
        onMain { vm.setUseCustom(context, MirrorCategory.ALPINE, false) }
        startPaused()
        finish()
        assertNull(vm.selectedMirrorId[MirrorCategory.ALPINE])
        assertEquals(false, vm.useCustomMirror[MirrorCategory.ALPINE])
        assertFalse(prefs.getBoolean("mirror.useCustom.alpine", true))
        assertChoice(MirrorCategory.NPM, "npm.npmmirror")
    }

    @Test fun untouchedCategoriesStillAutomaticallyApply() {
        startPaused()
        finish()
        assertChoice(MirrorCategory.ALPINE, "alpine.aliyun")
        assertChoice(MirrorCategory.PIP, "pip.tuna")
        assertChoice(MirrorCategory.NPM, "npm.npmmirror")
    }

    @Test fun categoryWithNoReachableResultIsNotInvented() {
        // No NPM success; other categories still exercise real settlement.
        winners.removeIf { it.startsWith("npm.") }
        startPaused()
        finish()
        assertNull(vm.selectedMirrorId[MirrorCategory.NPM])
        assertEquals(false, vm.useCustomMirror[MirrorCategory.NPM])
        assertNull(prefs.getString("mirror.selected.npm", null))
        assertChoice(MirrorCategory.ALPINE, "alpine.aliyun")
    }

    private class FakePrefs : SharedPreferences {
        private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()
        override fun getAll(): Map<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                pending.clear()
                clear = false
            }
        }
    }
}
