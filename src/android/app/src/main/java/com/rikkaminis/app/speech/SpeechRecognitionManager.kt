package com.rikkaminis.app.speech

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Application-scoped orchestrator for speech-to-text. Owns:
 *
 *  - A registry of [SpeechRecognitionEngine] adapters (system + provider).
 *  - The user's selected engine id (persisted) and active locale.
 *  - Lifecycle of the current capture session (single in-flight request).
 *  - Public state flows the UI collects:
 *      * [isAvailable] — whether the mic button should render at all.
 *      * [state] — idle / starting / recording / finishing.
 *      * [recognizedText] — live transcription (empty between sessions).
 *      * [locale] — current recognition locale.
 *
 * Mirrors the responsibilities of iOS `SpeechRecognitionManager.swift`.
 */
object SpeechRecognitionManager {

    private const val TAG = "SpeechManager"
    private const val PREFS = "speech_recognition"
    private const val KEY_ENGINE_ID = "engineId"
    private const val KEY_LOCALE = "locale"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val engines = mutableListOf<SpeechRecognitionEngine>()

    private val _state = MutableStateFlow(RecognitionState.IDLE)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _locale = MutableStateFlow(Locale.getDefault())
    val locale: StateFlow<Locale> = _locale.asStateFlow()

    private val _selectedEngineId = MutableStateFlow("system")
    val selectedEngineId: StateFlow<String> = _selectedEngineId.asStateFlow()

    private val _lastError = MutableStateFlow<RecognitionError?>(null)
    val lastError: StateFlow<RecognitionError?> = _lastError.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    /**
     * Locales the active engine reports as supported. Populated lazily after
     * the first availability probe; see [refreshSupportedLocales]. The UI's
     * language picker observes this; while empty it falls back to showing the
     * current locale only.
     */
    private val _supportedLocales = MutableStateFlow<List<Locale>>(emptyList())
    val supportedLocales: StateFlow<List<Locale>> = _supportedLocales.asStateFlow()

    /**
     * Rolling window of normalized (0f..1f) audio levels for the live waveform.
     * New samples push in at the tail; oldest drop off the head. Mirrors iOS
     * `SpeechRecognitionManager.audioLevels` (40-element buffer).
     */
    private val _audioLevels = MutableStateFlow(List(WAVEFORM_SIZE) { 0f })
    val audioLevels: StateFlow<List<Float>> = _audioLevels.asStateFlow()

    /** How many rolling level samples to keep. */
    const val WAVEFORM_SIZE = 40

    /**
     * Android's onRmsChanged reports values roughly in [-2, 10]. Clamp + scale
     * to [0, 1] with a floor at the mid-point of the noise range so idle silence
     * doesn't look like speech.
     */
    private fun normalizeRms(db: Float): Float {
        val clamped = db.coerceIn(0f, 12f)
        return (clamped / 12f).coerceIn(0f, 1f)
    }

    internal fun pushLevel(rawDb: Float) {
        val n = normalizeRms(rawDb)
        val current = _audioLevels.value
        val updated = ArrayList<Float>(current.size)
        for (i in 1 until current.size) updated.add(current[i])
        updated.add(n)
        _audioLevels.value = updated
    }

    private fun resetLevels() {
        _audioLevels.value = List(WAVEFORM_SIZE) { 0f }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        engines.clear()
        engines.add(SystemSpeechRecognitionEngine(appContext))
        engines.add(ProviderSpeechRecognitionEngine(appContext))

        _selectedEngineId.value = prefs.getString(KEY_ENGINE_ID, null) ?: pickDefaultEngineId()
        prefs.getString(KEY_LOCALE, null)?.let { tag ->
            runCatching { _locale.value = Locale.forLanguageTag(tag) }
        }
        refreshAvailability()
        refreshSupportedLocales()
    }

    /**
     * Ask the active engine which languages it supports (async) and update
     * [_supportedLocales]. Also re-validates the current locale: if the saved
     * locale isn't in the list (e.g. user changed regions) it falls back to
     * the system default, then en-US.
     */
    fun refreshSupportedLocales() {
        val engine = currentEngine() ?: return
        if (engine is SystemSpeechRecognitionEngine) {
            engine.fetchSupportedLocales { locales ->
                // [r5f-F05] The broadcast is asynchronous (up to 2500 ms), and
                // its result belongs to the engine that ASKED for it. Without
                // this check, selectEngine("provider") inside that window let
                // the late system reply overwrite the provider engine's (empty)
                // locale table with the system offline list — and re-validate /
                // move the user's `_locale` against a list that does not
                // describe the engine now in use.
                if (localeProbeSuperseded(engine.id, currentEngine()?.id)) return@fetchSupportedLocales
                _supportedLocales.value = locales
                if (locales.isNotEmpty() && locales.none { it.toLanguageTag() == _locale.value.toLanguageTag() }) {
                    val fallback = locales.firstOrNull { it.language == Locale.getDefault().language }
                        ?: locales.firstOrNull { it.language == "en" }
                        ?: locales.first()
                    _locale.value = fallback
                }
            }
        } else {
            // Synchronous branch: no suspension between currentEngine() and the
            // write, so the probe cannot be superseded here.
            _supportedLocales.value = engine.supportedLocales
        }
    }

    private fun pickDefaultEngineId(): String =
        engines.firstOrNull { it.isAvailable }?.id ?: "system"

    fun selectEngine(id: String) {
        if (_selectedEngineId.value == id) return
        // Mid-recording engine switch: cancel the in-flight session and reset the
        // state machine so a stale session can never keep the mic (or wedge the
        // state in FINISHING). This deliberately does NOT restart like
        // [selectLocale]: a locale switch is semantically continuous (same engine,
        // same transcript), while an engine switch would splice transcripts from
        // two different backends into one session. Minimal cleanup preferred.
        if (_state.value != RecognitionState.IDLE) {
            activeEngine?.cancel()
            activeEngine = null
            _state.value = RecognitionState.IDLE
        }
        _selectedEngineId.value = id
        prefs.edit().putString(KEY_ENGINE_ID, id).apply()
        refreshAvailability()
        // [r3-B11-F06] The locale list belongs to the ENGINE, not to the app:
        // it was only ever populated from init()/selectLocale(), so switching
        // engines left _supportedLocales showing the previous engine's list
        // (and re-validating the saved locale against a list it does not
        // belong to could silently switch the user's language). The waveform
        // is cleared for the same reason — it was the cancelled session's.
        refreshSupportedLocales()
        resetLevels()
    }

    fun selectLocale(locale: Locale) {
        if (_locale.value.toLanguageTag() == locale.toLanguageTag()) return
        _locale.value = locale
        prefs.edit().putString(KEY_LOCALE, locale.toLanguageTag()).apply()
        // If the user changed language mid-recording, iOS restarts the
        // session in the new language. Matching that behavior.
        if (_state.value == RecognitionState.RECORDING || _state.value == RecognitionState.STARTING) {
            val cb = activeCallbacks
            if (cb != null) {
                (activeEngine ?: currentEngine())?.cancel()
                activeEngine = null
                _state.value = RecognitionState.IDLE
                startRecording(cb.first, cb.second)
            }
        }
    }

    /** Callbacks from the most recent [startRecording] so [selectLocale] can restart with them. */
    private var activeCallbacks: Pair<(String, Boolean) -> Unit, (RecognitionError, String?) -> Unit>? = null

    /**
     * [T-da1021-G] Engine instance bound to the in-flight session. `currentEngine()`
     * re-resolves from `_selectedEngineId` and silently falls back to the first
     * available engine when the selected one degrades — so a mid-session change
     * (explicit `selectEngine`, or that internal fallback) would otherwise redirect
     * stop/cancel to a different, never-started engine and leak the real one's
     * AudioRecord / SpeechRecognizer handle. `@Volatile` is sufficient: every site
     * is a single write or a single read, no read-modify-write compound.
     */
    @Volatile
    private var activeEngine: SpeechRecognitionEngine? = null

    fun availableEngines(): List<SpeechRecognitionEngine> = engines.toList()

    /**
     * Recompute [isAvailable]. Cheap — the UI may call this before rendering
     * the mic button so runtime degradation (first-failure observations in
     * the active engine) takes effect.
     */
    fun refreshAvailability() {
        _isAvailable.value = engines.any { it.isAvailable }
    }

    private fun currentEngine(): SpeechRecognitionEngine? {
        val byId = engines.firstOrNull { it.id == _selectedEngineId.value }
        if (byId != null && byId.isAvailable) return byId
        return engines.firstOrNull { it.isAvailable }
    }

    /**
     * Start a capture session. [onPartialOrFinal] fires with the current best
     * transcription; the caller is responsible for delta-appending into the
     * composer (mirrors iOS `lastRecognizedLength` algorithm).
     *
     * No-op if a session is already in flight.
     */
    fun startRecording(
        onPartialOrFinal: (text: String, isFinal: Boolean) -> Unit,
        onError: (RecognitionError, String?) -> Unit,
    ) {
        if (_state.value != RecognitionState.IDLE) {
            Log.d(TAG, "startRecording ignored; state=${_state.value}")
            return
        }
        val engine = currentEngine()
        if (engine == null) {
            _lastError.value = RecognitionError.OEM_NO_SERVICE
            onError(RecognitionError.OEM_NO_SERVICE, "No recognition engine available")
            return
        }

        _state.value = RecognitionState.STARTING
        _recognizedText.value = ""
        _lastError.value = null
        resetLevels()
        activeCallbacks = onPartialOrFinal to onError
        activeEngine = engine

        val listener = object : SpeechRecognitionEngine.Listener {
            override fun onReadyForSpeech() {
                _state.value = RecognitionState.RECORDING
            }

            override fun onPartial(text: String) {
                _recognizedText.value = text
                onPartialOrFinal(text, false)
            }

            override fun onFinal(text: String) {
                if (text.isNotEmpty()) _recognizedText.value = text
                onPartialOrFinal(text, true)
                activeEngine = null
                _state.value = RecognitionState.IDLE
                resetLevels()
                refreshAvailability()
            }

            override fun onError(error: RecognitionError, message: String?) {
                _lastError.value = error
                activeEngine = null
                _state.value = RecognitionState.IDLE
                resetLevels()
                onError(error, message)
                refreshAvailability()
            }

            override fun onRmsDb(rms: Float) { pushLevel(rms) }
        }

        try {
            engine.start(_locale.value, listener)
        } catch (e: Throwable) {
            activeEngine = null
            _state.value = RecognitionState.IDLE
            _lastError.value = RecognitionError.UNKNOWN
            onError(RecognitionError.UNKNOWN, e.message)
        }
    }

    fun stopRecording() {
        if (_state.value != RecognitionState.RECORDING && _state.value != RecognitionState.STARTING) return
        _state.value = RecognitionState.FINISHING
        // Active instance first: currentEngine() re-resolves (and silently falls
        // back when the selected engine degraded mid-session), so it can point at
        // a different, never-started engine. Not nulled here — the engine is still
        // expected to flush its final result via [SpeechRecognitionEngine.Listener],
        // which clears the binding on the terminal callback.
        (activeEngine ?: currentEngine())?.stop()
    }

    fun cancelRecording() {
        (activeEngine ?: currentEngine())?.cancel()
        activeEngine = null
        _state.value = RecognitionState.IDLE
        _recognizedText.value = ""
    }

    /**
     * [T-android-asr-provider-shutdown] Process-scope teardown entry point.
     * The manager is an app-scoped singleton with no lifecycle callback in
     * [MinisApp] today, so this is currently unreferenced — it exists so a
     * future teardown hook can release provider-engine resources (coroutine
     * scope / job handles) in one place. Safe to call regardless.
     */
    fun shutdown() {
        engines.forEach { it.shutdown() }
    }
}

/**
 * [r5f-F05] Pure staleness predicate for an asynchronous locale probe. A
 * probe issued for one engine only applies while [SpeechRecognitionManager]
 * would still resolve that same engine (same id ⇔ same registry instance:
 * engines are singletons re-created together in [init], ids are distinct).
 *
 * The resolved-engine accessor is used on both sides on purpose: a fallback
 * resolution (selected engine degraded) that lands back on the probed engine
 * is not "stale" — the data still describes the engine in use. Expected
 * verdicts are pinned by unit tests:
 *
 *  | probeEngineId | activeEngineId | superseded |
 *  |---------------|----------------|------------|
 *  | "system"      | "system"       | false      |
 *  | "provider"    | "provider"     | false      |
 *  | "system"      | "provider"     | true       |
 *  | "system"      | null           | true       |
 */
internal fun localeProbeSuperseded(probeEngineId: String, activeEngineId: String?): Boolean =
    activeEngineId != probeEngineId
