package com.rikkaminis.app.speech

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.util.Locale

/**
 * [T-da1021-G] Behavior probes for `SpeechRecognitionManager`'s active-engine
 * binding. The manager is an `object` with Android-only dependencies
 * (Context / SharedPreferences / Log), so they are stubbed and the private
 * state is seeded through reflection. Expectations are written as hard
 * literals, never derived from production constants.
 *
 * Regression targets:
 * - `stopRecording` / `cancelRecording` used to re-resolve `currentEngine()`,
 *   which silently falls back to the first available engine when the selected
 *   one degrades — redirecting stop/cancel to a never-started engine while the
 *   real one keeps the mic.
 * - `selectEngine` had no mid-recording teardown — an in-flight session
 *   survived the switch and the state machine wedged in FINISHING.
 */
class SpeechActiveEngineTest {

    private class FakeEngine(
        override val id: String,
        available: Boolean = true,
    ) : SpeechRecognitionEngine {
        override val displayName: String = id
        override var isAvailable: Boolean = available
        override val supportsPartialResults: Boolean = false
        // [r3-B11-F06] mutable so a test can prove selectEngine re-reads the
        // NEW engine's list instead of keeping the previous engine's.
        var locales: List<Locale> = emptyList()
        override val supportedLocales: List<Locale> get() = locales
        var started: Int = 0
        var stopped: Int = 0
        var cancelled: Int = 0
        var listener: SpeechRecognitionEngine.Listener? = null
        override fun start(locale: Locale, listener: SpeechRecognitionEngine.Listener) {
            started++
            this.listener = listener
        }
        override fun stop() { stopped++ }
        override fun cancel() { cancelled++ }
    }

    private val a = FakeEngine("sys")
    private val b = FakeEngine("prov")
    private var finalCount: Int = 0

    private val onResult: (String, Boolean) -> Unit = { _, isFinal ->
        if (isFinal) finalCount++ else Unit
    }
    private val onError: (RecognitionError, String?) -> Unit = { _, _ -> Unit }

    private fun field(name: String): Field =
        SpeechRecognitionManager.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Before
    fun reset() {
        val engines = field("engines").get(SpeechRecognitionManager) as MutableList<SpeechRecognitionEngine>
        engines.clear()
        engines.add(a)
        engines.add(b)
        (field("_state").get(SpeechRecognitionManager) as MutableStateFlow<RecognitionState>).value = RecognitionState.IDLE
        (field("_selectedEngineId").get(SpeechRecognitionManager) as MutableStateFlow<String>).value = "sys"
        field("prefs").set(SpeechRecognitionManager, FakePrefs())
        field("activeCallbacks").set(SpeechRecognitionManager, null)
        field("activeEngine").set(SpeechRecognitionManager, null)
        a.started = 0; a.stopped = 0; a.cancelled = 0; a.isAvailable = true
        b.started = 0; b.stopped = 0; b.cancelled = 0; b.isAvailable = true
        finalCount = 0
    }

    private fun activeEngine(): SpeechRecognitionEngine? =
        field("activeEngine").get(SpeechRecognitionManager) as? SpeechRecognitionEngine

    private fun state(): RecognitionState =
        (field("_state").get(SpeechRecognitionManager) as MutableStateFlow<RecognitionState>).value

    private fun start() {
        SpeechRecognitionManager.startRecording(onResult, onError)
    }

    /** ① stop must land on the engine that actually started, not a re-resolved one. */
    @Test
    fun stopRecordingStopsTheStartedEngine() {
        start()
        assertEquals(1, a.started)
        assertEquals(0, b.started)
        SpeechRecognitionManager.stopRecording()
        assertEquals(1, a.stopped)
        assertEquals(0, b.stopped)
        assertEquals(RecognitionState.FINISHING, state())
    }

    /** ② the key regression: currentEngine() falls back to another engine when the
     *  active one degrades mid-session — stop must still land on the active instance. */
    @Test
    fun stopRecordingUsesActiveInstanceWhenCurrentEngineFallsBack() {
        start()
        a.isAvailable = false
        SpeechRecognitionManager.stopRecording()
        assertEquals(1, a.stopped)
        assertEquals(0, b.stopped)
    }

    /** ③ cancel likewise, and it must clear the binding and reset state. */
    @Test
    fun cancelRecordingUsesActiveInstanceAndClearsIt() {
        start()
        a.isAvailable = false
        SpeechRecognitionManager.cancelRecording()
        assertEquals(1, a.cancelled)
        assertEquals(0, b.cancelled)
        assertEquals(RecognitionState.IDLE, state())
        assertNull(activeEngine())
    }

    /** ④ mid-recording selectEngine tears down the in-flight session: the old
     *  engine is cancelled, state resets to IDLE, and a fresh start lands on the
     *  new engine (state machine must not wedge in FINISHING/RECORDING). */
    @Test
    fun selectEngineMidRecordingTearsDownInFlightSession() {
        start()
        assertEquals(RecognitionState.STARTING, state())
        SpeechRecognitionManager.selectEngine("prov")
        assertEquals(1, a.cancelled)
        assertEquals(0, b.cancelled)
        assertEquals(RecognitionState.IDLE, state())
        assertNull(activeEngine())
        // a fresh session must be possible and must land on the new engine
        start()
        assertEquals(1, b.started)
        assertEquals(1, a.started)
        assertEquals(RecognitionState.STARTING, state())
    }

    /** ⑤ canonical final callback clears the binding and lets a new session start
     *  (no FINISHING wedge, no stale binding). */
    @Test
    fun finalCallbackClearsBindingAndAllowsRestart() {
        start()
        SpeechRecognitionManager.stopRecording()
        assertEquals(RecognitionState.FINISHING, state())
        val listener = a.listener
        listener!!.onFinal("hi")
        assertEquals(1, finalCount)
        assertEquals(RecognitionState.IDLE, state())
        assertNull(activeEngine())
        start()
        assertEquals(2, a.started)
        assertEquals(RecognitionState.STARTING, state())
    }

    /** ⑥ terminal error likewise clears the binding and allows a restart. */
    @Test
    fun errorCallbackClearsBindingAndAllowsRestart() {
        start()
        val listener = a.listener
        listener!!.onError(RecognitionError.UNKNOWN, "boom")
        assertEquals(RecognitionState.IDLE, state())
        assertNull(activeEngine())
        start()
        assertEquals(2, a.started)
    }

    /**
     * [r3-B11-F06] The locale list belongs to the ENGINE. `_supportedLocales` was
     * only refreshed from init()/selectLocale(), so after switching engines the
     * panel kept offering the previous engine's languages (and re-validated the
     * saved locale against a list that engine never advertised).
     */
    @Test
    fun selectEngineRefreshesTheSupportedLocaleList() {
        a.locales = listOf(Locale.US)
        b.locales = listOf(Locale.JAPAN)
        SpeechRecognitionManager.refreshSupportedLocales() // seed from "sys"
        assertEquals(
            listOf("en-US"),
            SpeechRecognitionManager.supportedLocales.value.map { it.toLanguageTag() },
        )
        SpeechRecognitionManager.selectEngine("prov")
        assertEquals(
            "an engine switch must carry the new engine's locale list",
            listOf("ja-JP"),
            SpeechRecognitionManager.supportedLocales.value.map { it.toLanguageTag() },
        )
    }

    /** ⑦ idle selectEngine must not touch any engine (no in-flight session). */
    @Test
    fun idleSelectEngineDoesNotTouchEngines() {
        SpeechRecognitionManager.selectEngine("prov")
        assertEquals(0, a.cancelled)
        assertEquals(0, b.cancelled)
        assertEquals("prov", (field("_selectedEngineId").get(SpeechRecognitionManager) as MutableStateFlow<*>).value)
    }

    /** House-pattern fake prefs (mirrors ChatMenuPrefsTest.FakePrefs): a FULL
     *  implementation of the real android.content.SharedPreferences interface —
     *  a partial stub compiles against the sandbox's minimal android stub but
     *  fails CI's real SDK ("is not abstract and does not implement"). */
    private class FakePrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        private val listeners = HashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = HashMap(map)

        override fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getInt(key: String, defValue: Int): Int =
            map[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long =
            map[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            map[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in map

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = null

        private class FakeEditor(private val prefs: FakePrefs) : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                pending[key] = null
            }

            override fun clear(): SharedPreferences.Editor = apply {
                prefs.map.keys.forEach { pending[it] = null }
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                pending.forEach { (k, v) ->
                    if (v == null) prefs.map.remove(k) else prefs.map[k] = v
                }
                pending.clear()
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
        }
    }
}
