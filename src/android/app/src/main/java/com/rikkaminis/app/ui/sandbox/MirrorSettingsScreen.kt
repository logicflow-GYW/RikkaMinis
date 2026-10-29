package com.rikkaminis.app.ui.sandbox

import com.rikkaminis.app.R

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.outlined.Javascript
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import com.rikkaminis.app.ui.theme.ChatColors

// ─── Data Model ──────────────────────────────────────────────────────────────

// [da1021-C] The enum itself moved to MirrorCategory.kt (dependency-free, so
// the fromKey route parsing is unit-testable from the JVM test source set).
// Icon/color presentation stays here — it needs Compose + material-icons —
// re-declared as extension properties; call sites are unchanged.
val MirrorCategory.icon: ImageVector
    get() = when (this) {
        MirrorCategory.ALPINE -> Icons.Filled.Terrain
        MirrorCategory.PIP -> Icons.Filled.Inventory2
        MirrorCategory.NPM -> Icons.Outlined.Javascript
    }

val MirrorCategory.iconColor: Color
    get() = when (this) {
        MirrorCategory.ALPINE -> Color(0xFF007AFF)
        MirrorCategory.PIP -> Color(0xFF34C759)
        MirrorCategory.NPM -> Color(0xFFFF3B30)
    }

data class MirrorEntry(
    val id: String,
    val name: String,
    val baseURL: String,
    val testURL: String,
    val category: MirrorCategory,
    val region: String,
    val isOfficial: Boolean = false,
)

data class MirrorTestResult(
    val mirror: MirrorEntry,
    val latencyMs: Int?,   // null = failed/timeout
    val error: String? = null,
) {
    val isSuccess: Boolean get() = latencyMs != null
}

// ─── Mirror Definitions ──────────────────────────────────────────────────────

object MirrorCatalog {
    private const val ALPINE_PROBE = "v3.21/main/aarch64/APKINDEX.tar.gz"

    private fun alpine(id: String, name: String, url: String, region: String, official: Boolean = false) =
        MirrorEntry("alpine.$id", name, url, url + ALPINE_PROBE, MirrorCategory.ALPINE, region, official)

    private fun pip(id: String, name: String, url: String, region: String, official: Boolean = false) =
        MirrorEntry("pip.$id", name, url, url, MirrorCategory.PIP, region, official)

    private fun npm(id: String, name: String, url: String, region: String, official: Boolean = false) =
        MirrorEntry("npm.$id", name, url, url, MirrorCategory.NPM, region, official)

    val alpineMirrors = listOf(
        alpine("official", "Official CDN", "https://dl-cdn.alpinelinux.org/alpine/", "Global", official = true),
        alpine("tuna", "Tsinghua TUNA", "https://mirrors.tuna.tsinghua.edu.cn/alpine/", "China"),
        alpine("aliyun", "Alibaba", "https://mirrors.aliyun.com/alpine/", "China"),
        alpine("ustc", "USTC", "https://mirrors.ustc.edu.cn/alpine/", "China"),
        alpine("huawei", "Huawei", "https://repo.huaweicloud.com/alpine/", "China"),
        alpine("tencent", "Tencent", "https://mirrors.cloud.tencent.com/alpine/", "China"),
        alpine("leaseweb", "LEASEWEB UK", "https://mirror.leaseweb.com/alpine/", "Europe"),
        alpine("rwth", "RWTH Germany", "https://ftp.halifax.rwth-aachen.de/alpine/", "Europe"),
        alpine("jaist", "JAIST Japan", "https://ftp.jaist.ac.jp/pub/Linux/alpine/", "Asia"),
        alpine("kakao", "Kakao Korea", "https://mirror.kakao.com/alpine/", "Asia"),
    )

    val pipMirrors = listOf(
        pip("official", "Official PyPI", "https://pypi.org/simple/", "Global", official = true),
        pip("tuna", "Tsinghua TUNA", "https://pypi.tuna.tsinghua.edu.cn/simple/", "China"),
        pip("aliyun", "Alibaba", "https://mirrors.aliyun.com/pypi/simple/", "China"),
        pip("ustc", "USTC", "https://mirrors.ustc.edu.cn/pypi/web/simple/", "China"),
        pip("huawei", "Huawei", "https://repo.huaweicloud.com/repository/pypi/simple/", "China"),
        pip("tencent", "Tencent", "https://mirrors.cloud.tencent.com/pypi/simple/", "China"),
    )

    val npmMirrors = listOf(
        npm("official", "Official npm", "https://registry.npmjs.org/", "Global", official = true),
        npm("npmmirror", "npmmirror", "https://registry.npmmirror.com/", "China"),
        npm("huawei", "Huawei", "https://repo.huaweicloud.com/repository/npm/", "China"),
        npm("tencent", "Tencent", "https://mirrors.cloud.tencent.com/npm/", "China"),
    )

    val allMirrors: List<MirrorEntry> = alpineMirrors + pipMirrors + npmMirrors

    fun mirrors(category: MirrorCategory): List<MirrorEntry> = when (category) {
        MirrorCategory.ALPINE -> alpineMirrors
        MirrorCategory.PIP -> pipMirrors
        MirrorCategory.NPM -> npmMirrors
    }

    fun findByBaseURL(baseURL: String): MirrorEntry? =
        allMirrors.firstOrNull { it.baseURL == baseURL }

    fun findById(id: String): MirrorEntry? =
        allMirrors.firstOrNull { it.id == id }

    fun categoryFromKey(key: String): MirrorCategory? =
        MirrorCategory.entries.firstOrNull { it.key == key }
}

// ─── ViewModel (singleton) ───────────────────────────────────────────────────

object MirrorSpeedTestViewModel {
    private const val TAG = "MirrorSpeedTest"
    private const val PREFS = "mirror_settings"

    // §29: mirror-fallback switch cap. Alpine has 9 non-official candidates;
    // each switch triggers a full apk retry round with real network
    // timeouts, so the boot path must not walk all of them in one boot.
    // The tried list persists across boots, so the next boot continues
    // from where this one stopped.
    private const val APK_MIRROR_FALLBACK_MAX_SWITCHES = 4

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentJob: Job? = null
    // [dv25-B1] First-boot auto-detection settles long after the run it reuses
    // has finished, so it needs a job slot of its own: a user-initiated test
    // cancels this (runAllTests / runTest) and the settlement re-checks that it
    // is still the owner before touching the selection maps.
    @Volatile private var autoDetectJob: Job? = null

    // Observable state — Compose observes these Snapshot-backed maps.
    val results: SnapshotStateMap<MirrorCategory, List<MirrorTestResult>> = mutableStateMapOf()
    val selectedMirrorId: SnapshotStateMap<MirrorCategory, String> = mutableStateMapOf()
    val useCustomMirror: SnapshotStateMap<MirrorCategory, Boolean> = mutableStateMapOf()

    var isTesting by mutableStateOf(false)
        private set
    var testProgress by mutableStateOf(0f)
        private set

    @Volatile private var loaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            // [da1022-F] ensureLoaded runs on background threads too (boot path
            // via applyAllActiveMirrors, and retryApkWorldWithMirrorFallback on
            // Dispatchers.IO), so these SnapshotStateMap writes must land as one
            // atomic snapshot — a concurrent main-thread reader would otherwise
            // observe a half-loaded map (selection written, toggle flag not yet).
            Snapshot.withMutableSnapshot {
                for (cat in MirrorCategory.entries) {
                    val savedUrl = p.getString("mirror.selected.${cat.key}", null)
                    if (savedUrl != null) {
                        val entry = MirrorCatalog.findByBaseURL(savedUrl)
                        if (entry != null) selectedMirrorId[cat] = entry.id
                    }
                    useCustomMirror[cat] = p.getBoolean("mirror.useCustom.${cat.key}", false)
                }
            }
            loaded = true
        }
    }

    private fun persist(context: Context, category: MirrorCategory) {
        val p = prefs(context)
        val editor = p.edit()
        val mirrorId = selectedMirrorId[category]
        val entry = mirrorId?.let { MirrorCatalog.findById(it) }
        if (entry != null) {
            editor.putString("mirror.selected.${category.key}", entry.baseURL)
        }
        editor.putBoolean("mirror.useCustom.${category.key}", useCustomMirror[category] == true)
        editor.apply()
    }

    // -- Speed Test --

    fun runAllTests(context: Context) {
        // [dv25-B1] A user-initiated run invalidates an in-flight first-boot
        // auto-detection: its settlement writes the same maps, prefs and rootfs
        // config, so a stale detector would overwrite the mirror this run is
        // about to pick. autoDetectOnceIfNeeded calls startAllTests directly —
        // cancelling here would cancel the job it is running in.
        autoDetectJob?.cancel()
        startAllTests(context)
    }

    /**
     * Shared body of [runAllTests] and [autoDetectOnceIfNeeded] (the boot path
     * reuses the normal test flow). Separate from the public entry point because
     * only a user tap may preempt auto-detection.
     */
    private fun startAllTests(context: Context, autoApplyOnComplete: Boolean = true) {
        currentJob?.cancel()
        // [dv25-B1] LAZY + explicit start publishes `currentJob` *before* the
        // body can read it — the settlement/finally ownership checks compare
        // against this field, and a plain `scope.launch` dispatches the body
        // before the assignment lands.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // T10-L8: capture this job so the finally block can tell whether a
            // newer run already replaced it. A cancelled predecessor's reset
            // could otherwise land *after* the successor set isTesting = true,
            // leaving the UI showing "not testing" while a run is in flight.
            val self = coroutineContext[Job]
            withContext(Dispatchers.Main) {
                isTesting = true
                testProgress = 0f
                results.clear()
            }

            // [fix/audit-s2m1] isTesting must reset even when this job is
            // cancelled mid-flight (user retaps → currentJob?.cancel() at the
            // top, or runTest cancels a running runAllTests). The reset lived
            // only on the normal-completion path, so a cancellation skipped it
            // and left the singleton's isTesting=true forever — the "test
            // fastest" button (enabled = !isTesting) was permanently dead
            // until process restart.
            try {
                val all = MirrorCatalog.allMirrors
                val total = all.size
                var completed = 0
                val perCategory = mutableMapOf<MirrorCategory, MutableList<MirrorTestResult>>()

                val deferred = all.map { mirror ->
                    async { testMirror(mirror) }
                }
                for (d in deferred) {
                    val result = d.await()
                    completed++
                    perCategory.getOrPut(result.mirror.category) { mutableListOf() }.add(result)
                    val progress = completed.toFloat() / total.toFloat()
                    withContext(Dispatchers.Main) { testProgress = progress }
                }

                val sorted = perCategory.mapValues { (_, list) -> list.sortedWith(latencyComparator) }
                val autoApplied = mutableListOf<MirrorCategory>()
                withContext(Dispatchers.Main) {
                    // [dv25-B1] A superseded run must not settle: its results
                    // belong to a cancelled attempt and would replace the
                    // successor's map plus its auto-selection. Cancellation
                    // normally stops this dispatch; the identity check covers
                    // the case where the job was replaced without cancelling.
                    if (currentJob !== self) return@withContext
                    results.clear()
                    results.putAll(sorted)

                    // Auto-select fastest for categories without prior selection.
                    // [da1022-F] A selection recorded without useCustomMirror=true
                    // + applyMirror never reached the rootfs config — the speed
                    // test conclusion was persisted but had no effect
                    // (autoDetectOnceIfNeeded is the reference shape). applyMirror
                    // is disk IO, so applied categories are collected here and
                    // applied below, off the main thread (same shape as
                    // selectMirror / setUseCustom).
                    // [R4-V F-03] The first-boot path passes
                    // autoApplyOnComplete = false: detection has its OWN rule
                    // (prior selection wins, official mirrors only when nothing
                    // else is known — decideAutoApply), and letting this body
                    // settle first made that rule unreachable, because the map
                    // it inspects was already filled by the write below.
                    if (autoApplyOnComplete) {
                        for ((cat, fastest) in autoSelectFastest(sorted) { selectedMirrorId[it] != null }) {
                            selectedMirrorId[cat] = fastest.mirror.id
                            useCustomMirror[cat] = true
                            persist(context, cat)
                            autoApplied += cat
                        }
                    }
                    Log.i(TAG, "Mirror speed test complete: ${sorted.mapValues { it.value.size }}")
                }
                for (cat in autoApplied) applyMirror(context, cat)
            } finally {
                // T10-L8: NonCancellable is required — a plain withContext in
                // the finally of a cancelled job throws JobCancellationException
                // before running its body (verified against coroutines 1.9.0),
                // which is why the [fix/audit-s2m1] reset never actually fired
                // on the cancel path. The identity check keeps a cancelled
                // predecessor from clearing a successor's flag.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === self) isTesting = false
                }
            }
        }
        currentJob = job
        job.start()
    }

    fun runTest(context: Context, category: MirrorCategory) {
        // [dv25-B1] Same preemption as runAllTests: a single-category test is a
        // user-initiated run too, and must invalidate a pending auto-detection.
        autoDetectJob?.cancel()
        currentJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // T10-L8: same identity guard as runAllTests.
            val self = coroutineContext[Job]
            withContext(Dispatchers.Main) {
                isTesting = true
                testProgress = 0f
            }
            try {
                val mirrors = MirrorCatalog.mirrors(category)
                val total = mirrors.size
                var completed = 0
                val list = mutableListOf<MirrorTestResult>()

                // [dv15-B2 / B-02] Progress must advance as each probe settles.
                // The previous `deferred.awaitAll()` held this loop back until the
                // last latency came in, so the bar sat at 0 and jumped to 100 —
                // and on a slow or dead mirror that is the whole timeout, which is
                // why the tap read as ignored. Concurrency is unchanged: every
                // async is still launched up front, only the order in which the
                // completions are observed changed.
                val deferred = mirrors.map { mirror -> async { testMirror(mirror) } }
                for (d in deferred) {
                    val r = d.await()
                    completed++
                    list.add(r)
                    val p = completed.toFloat() / total.toFloat()
                    withContext(Dispatchers.Main) { testProgress = p }
                }

                val sorted = list.sortedWith(latencyComparator)
                // [da1022-F] Same auto-select contract as runAllTests: a speed
                // test has to reach the rootfs config, not stop at the results
                // map. The previous body recorded the results and returned, so the
                // mirror the user had just measured was never selected, persisted
                // or applied — the `context` parameter sat unused, which is what
                // made the truncation invisible.
                val autoApplied = mutableListOf<MirrorCategory>()
                withContext(Dispatchers.Main) {
                    // [dv25-B1] Same ownership gate as runAllTests: a run that a
                    // newer one replaced must not write its older results.
                    if (currentJob !== self) return@withContext
                    this@MirrorSpeedTestViewModel.results[category] = sorted
                    for ((cat, fastest) in autoSelectFastest(mapOf(category to sorted)) { selectedMirrorId[it] != null }) {
                        selectedMirrorId[cat] = fastest.mirror.id
                        useCustomMirror[cat] = true
                        persist(context, cat)
                        autoApplied += cat
                    }
                }
                // applyMirror is disk IO — same split as runAllTests /
                // selectMirror / setUseCustom: state writes on Main, the rootfs
                // write off it.
                for (cat in autoApplied) applyMirror(context, cat)
            } finally {
                // T10-L8: NonCancellable is required — a plain withContext in
                // the finally of a cancelled job throws JobCancellationException
                // before running its body (verified against coroutines 1.9.0),
                // which is why the [fix/audit-s2m1] reset never actually fired
                // on the cancel path. The identity check keeps a cancelled
                // predecessor from clearing a successor's flag.
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === self) isTesting = false
                }
            }
        }
        currentJob = job
        job.start()
    }

    private val latencyComparator = Comparator<MirrorTestResult> { a, b ->
        when {
            a.latencyMs != null && b.latencyMs != null -> a.latencyMs.compareTo(b.latencyMs)
            a.latencyMs != null && b.latencyMs == null -> -1
            a.latencyMs == null && b.latencyMs != null -> 1
            else -> 0
        }
    }

    private fun testMirror(mirror: MirrorEntry): MirrorTestResult {
        return try {
            val req = Request.Builder()
                .url(mirror.testURL)
                .head()
                .build()
            val start = System.nanoTime()
            httpClient.newCall(req).execute().use { resp ->
                val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).toInt()
                if (resp.code >= 400) {
                    MirrorTestResult(mirror, null, "HTTP ${resp.code}")
                } else {
                    MirrorTestResult(mirror, elapsedMs, null)
                }
            }
        } catch (e: Exception) {
            MirrorTestResult(mirror, null, e.message ?: e.javaClass.simpleName)
        }
    }

    // -- Selection & Apply --

    fun selectMirror(context: Context, mirror: MirrorEntry) {
        selectedMirrorId[mirror.category] = mirror.id
        persist(context, mirror.category)
        if (useCustomMirror[mirror.category] == true) {
            // T10-L7: applyMirror copies the current config to .bak and rewrites
            // it — disk IO that used to run inside the row's onClick.
            scope.launch { applyMirror(context, mirror.category) }
        }
    }

    fun setUseCustom(context: Context, category: MirrorCategory, enabled: Boolean) {
        useCustomMirror[category] = enabled
        persist(context, category)
        // T10-L7: both branches touch the rootfs config files; keep them off
        // the main thread like every other IO path in this file.
        scope.launch {
            if (enabled) {
                applyMirror(context, category)
            } else {
                restoreOfficial(context, category)
            }
        }
    }

    /**
     * Re-apply the user's selected mirror for every category that has
     * stringResource(R.string.mirror_use_mirror_toggle) enabled. Call this after the default_mount overlay runs on
     * boot — the overlay ships stock config files that would otherwise wipe
     * user-chosen mirrors.
     */
    fun applyAllActiveMirrors(context: Context) {
        ensureLoaded(context)
        for (category in MirrorCategory.entries) {
            if (useCustomMirror[category] == true && selectedMirrorId[category] != null) {
                applyMirror(context, category)
            }
        }
    }

    /**
     * On the first boot after a fresh rootfs install, auto-detect the fastest
     * mirror for each category and apply it. The `rootfs.freshInstall` flag is
     * set by RootfsManager.installIfNeeded() and cleared here.
     */
    fun autoDetectOnceIfNeeded(context: Context) {
        ensureLoaded(context)
        val p = prefs(context)
        if (!p.getBoolean("rootfs.freshInstall", false)) return
        p.edit().remove("rootfs.freshInstall").apply()
        Log.i(TAG, "Fresh rootfs detected — running auto mirror detection")

        // [dv25-B1] The detector owns its own job. Before this the coroutine was
        // a bare `scope.launch`, so the only cancellation target was the inner
        // runAllTests job: a user tap cancelled that inner job, `join()` returned
        // immediately on it, and this coroutine settled anyway — overwriting the
        // mirror the user had just picked by hand (selectMirror writes the same
        // maps, prefs and rootfs config).
        autoDetectJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            // A run the user started wins over first-boot convenience: detection
            // only fills a gap, so it must never cancel work already in flight.
            if (currentJob?.isActive == true) {
                Log.i(TAG, "Auto mirror detection skipped: a speed test is already in flight")
                return@launch
            }
            // [R4-V F-03] Snapshot the prior selection BEFORE the shared body
            // runs. It used to be read after `join()`, i.e. after startAllTests
            // had auto-applied into `selectedMirrorId` — so `alreadySelected`
            // was always the complete set, `decideAutoApply` returned nothing,
            // and the boot path silently behaved like a user tap (official
            // mirrors included). The snapshot is a read of a map whose writes
            // are main-thread gated below.
            val alreadySelected = selectedMirrorId.keys.toSet()
            // Reuse the normal test flow — startAllTests, not runAllTests: the
            // public entry point cancels auto-detection first, which here would
            // be the job this very coroutine is running in. `autoApplyOnComplete
            // = false` keeps the settlement out of the decision: this function
            // settles through decideAutoApply, not through the shared body's
            // "fastest wins" loop.
            startAllTests(context, autoApplyOnComplete = false)
            currentJob?.join()
            val toApply = mutableListOf<MirrorCategory>()
            withContext(Dispatchers.Main) {
                // Ownership gate: a detector superseded by a user tap must not
                // settle. It sits inside the main-thread block so it cannot
                // interleave with the user's own selectMirror writes below.
                if (autoDetectJob !== self) return@withContext
                // First-boot detection is a convenience, not a decision: a
                // category the user already picked keeps its mirror — the same
                // "prior selection wins" predicate the user-initiated path hands
                // to autoSelectFastest. `alreadySelected` is the snapshot taken
                // before the speed test (F-03), not a read of the settled map.
                for ((cat, fastest) in decideAutoApply(results.toMap(), alreadySelected)) {
                    selectedMirrorId[cat] = fastest.mirror.id
                    useCustomMirror[cat] = true
                    persist(context, cat)
                    toApply += cat
                }
            }
            // [da1022-F] applyMirror is disk IO — it used to run inside the
            // withContext(Dispatchers.Main) block above. State writes stay on
            // the main thread, the file writes happen here (same split as
            // selectMirror / setUseCustom, T10-L7).
            for (cat in toApply) applyMirror(context, cat)
            Log.i(TAG, "Auto mirror detection applied")
        }
        autoDetectJob = job
        job.start()
    }

    /**
     * §29: APK-world retry with automatic mirror switching. Runs
     * [attemptOnce] (a full retry round against the CURRENT source); when
     * it fails, switches to the next not-yet-tried non-official ALPINE
     * mirror, re-applies the mirror config, and retries. State machine:
     *   - tried mirror ids persist under "apkRetry.triedMirrorIds" so a
     *     bad source cannot loop forever across boots;
     *   - stops when every non-official source has been tried or
     *     [APK_MIRROR_FALLBACK_MAX_SWITCHES] switches have run (each round
     *     carries real network timeouts — the boot path must not walk all
     *     nine candidates in one boot);
     *   - the tried list clears on success so a later failure starts fresh;
     *     returning false KEEPS the list so the next boot continues from
     *     where this one left off.
     * The switched mirror is persisted as the user selection (persist +
     * applyMirror), so a source that actually works becomes the standing
     * choice — that is the self-healing intent.
     */
    suspend fun retryApkWorldWithMirrorFallback(
        context: Context,
        attemptOnce: suspend () -> Boolean,
    ): Boolean {
        ensureLoaded(context)
        var ok = attemptOnce()
        if (ok) return true
        val p = prefs(context)
        val triedKey = "apkRetry.triedMirrorIds"
        val tried = (p.getStringSet(triedKey, emptySet()) ?: emptySet()).toMutableSet()
        var switches = 0
        while (switches < APK_MIRROR_FALLBACK_MAX_SWITCHES) {
            val currentId = selectedMirror(MirrorCategory.ALPINE)?.id
            val next = MirrorCatalog.mirrors(MirrorCategory.ALPINE).firstOrNull {
                !it.isOfficial && it.id != currentId && it.id !in tried
            } ?: break // every non-official source already tried — stop instead of looping
            tried.add(next.id)
            switches++
            p.edit().putStringSet(triedKey, tried).apply()
            // [da1022-F] This runs on the boot's IO dispatcher (RootfsManager
            // .retryFailedApkWorld); the two snapshot-state writes must land as
            // one atomic snapshot so a concurrent main-thread reader never sees
            // "selection switched, custom flag still false". No Main-thread hop
            // here — the boot path must not wait on the UI thread.
            Snapshot.withMutableSnapshot {
                selectedMirrorId[MirrorCategory.ALPINE] = next.id
                useCustomMirror[MirrorCategory.ALPINE] = true
            }
            persist(context, MirrorCategory.ALPINE)
            applyMirror(context, MirrorCategory.ALPINE)
            Log.i(TAG, "APK mirror fallback: switched to ${next.name} (switch $switches/${APK_MIRROR_FALLBACK_MAX_SWITCHES})")
            ok = attemptOnce()
            if (ok) {
                p.edit().remove(triedKey).apply()
                Log.i(TAG, "APK mirror fallback succeeded with ${next.name}")
                return true
            }
        }
        Log.w(TAG, "APK mirror fallback exhausted after $switches switch(es); tried list kept for next boot")
        return false
    }

    fun selectedMirror(category: MirrorCategory): MirrorEntry? {
        val id = selectedMirrorId[category] ?: return null
        return MirrorCatalog.findById(id)
    }

    fun fastestResult(category: MirrorCategory): MirrorTestResult? =
        results[category]?.firstOrNull { it.isSuccess }

    fun isActive(category: MirrorCategory): Boolean =
        useCustomMirror[category] == true && selectedMirrorId[category] != null

    private fun rootfsDataDir(context: Context): File =
        File(context.applicationContext.filesDir, "alpine-rootfs")

    private fun applyMirror(context: Context, category: MirrorCategory) {
        val mirror = selectedMirror(category) ?: return
        val dataDir = rootfsDataDir(context)
        val configFile = File(dataDir, category.configPath)
        val bakFile = File(dataDir, category.configPath + ".bak")

        try {
            if (!bakFile.exists() && configFile.exists()) {
                configFile.copyTo(bakFile, overwrite = false)
                Log.i(TAG, "Backed up ${category.configPath} -> .bak")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to backup ${category.configPath}: ${e.message}")
        }

        val content = when (category) {
            MirrorCategory.ALPINE ->
                "${mirror.baseURL}v3.21/main\n${mirror.baseURL}v3.21/community\n"
            MirrorCategory.PIP -> {
                val host = try { URI(mirror.baseURL).host ?: "" } catch (_: Exception) { "" }
                """
                [global]
                break-system-packages = true
                index-url = ${mirror.baseURL}
                trusted-host = $host

                """.trimIndent()
            }
            MirrorCategory.NPM -> "registry=${mirror.baseURL}\n"
        }

        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(content)
            Log.i(TAG, "Applied mirror ${mirror.name} to ${category.configPath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${category.configPath}: ${e.message}")
        }
    }

    private fun restoreOfficial(context: Context, category: MirrorCategory) {
        val dataDir = rootfsDataDir(context)
        val configFile = File(dataDir, category.configPath)
        val bakFile = File(dataDir, category.configPath + ".bak")

        if (!bakFile.exists()) {
            Log.i(TAG, "No .bak for ${category.configPath}, skipping restore")
            return
        }
        try {
            if (configFile.exists()) configFile.delete()
            bakFile.copyTo(configFile, overwrite = true)
            Log.i(TAG, "Restored ${category.configPath} from .bak")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore ${category.configPath}: ${e.message}")
        }
    }
}

// ─── Views ───────────────────────────────────────────────────────────────────

/**
 * [da1022-F] Pure selection decision for the speed-test auto-select: for every
 * category without a prior selection, take the first successful result of the
 * caller-ordered list (runAllTests sorts by latencyComparator — successes
 * ascending, failures last — so that entry is the fastest reachable mirror).
 * Dependency-free (MirrorCategory / MirrorEntry / MirrorTestResult are all pure
 * Kotlin) so it is unit-testable from the JVM test source set — same split as
 * [da1021-C] did for MirrorCategory. The caller performs the state writes,
 * persistence, and the applyMirror disk IO.
 */
internal fun autoSelectFastest(
    sorted: Map<MirrorCategory, List<MirrorTestResult>>,
    hasPriorSelection: (MirrorCategory) -> Boolean,
): List<Pair<MirrorCategory, MirrorTestResult>> =
    MirrorCategory.entries.mapNotNull { cat ->
        if (hasPriorSelection(cat)) return@mapNotNull null
        sorted[cat]?.firstOrNull { it.isSuccess }?.let { cat to it }
    }

/**
 * [dv25-B1] Pure decision for first-boot auto-detection: which categories the
 * detector may apply, and with which mirror.
 *
 * Two properties, both load-bearing:
 *  - a category in [alreadySelected] is skipped. Detection runs on the boot
 *    path, so the user may already have picked a mirror by hand — and
 *    `selectMirror` writes the same maps, prefs and rootfs config, so an
 *    unguarded detector silently overwrote that choice. The predicate is the
 *    one the user-initiated path already hands [autoSelectFastest];
 *  - the official CDN is never chosen: on first boot it is what the config
 *    already points at, not a discovery result.
 *
 * Dependency-free like [autoSelectFastest], so the JVM test source set can
 * exercise it without Compose. The caller performs the state writes,
 * persistence and the applyMirror disk IO.
 */
internal fun decideAutoApply(
    results: Map<MirrorCategory, List<MirrorTestResult>>,
    alreadySelected: Set<MirrorCategory>,
): List<Pair<MirrorCategory, MirrorTestResult>> =
    autoSelectFastest(results) { it in alreadySelected }
        .filterNot { (_, fastest) -> fastest.mirror.isOfficial }

/**
 * Body-only rows (no section header) for embedding inside an outer
 * `SettingsSection(stringResource(R.string.mirror_mirrors_section)) { ... }` in RootfsManagementScreen.
 * T168 moved the stringResource(R.string.mirror_mirrors_section) title up to the SettingsSection wrapper so
 * the iOS-style inset-grouped card frames *just* the rows.
 */
@Composable
fun MirrorsSectionView(onNavigate: (MirrorCategory) -> Unit) {
    val context = LocalContext.current
    MirrorSpeedTestViewModel.ensureLoaded(context)

    val vm = MirrorSpeedTestViewModel

    ListItem(
        headlineContent = { Text(if (vm.isTesting) stringResource(R.string.mirror_test_speed_testing) else stringResource(R.string.mirror_detect_fast_label)) },
        leadingContent = {
            CircleIconBadge(icon = Icons.Filled.Bolt, tint = Color(0xFFFF9500))
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !vm.isTesting) { vm.runAllTests(context) },
    )

    if (vm.isTesting) {
        LinearProgressIndicator(
            progress = { vm.testProgress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    for (category in MirrorCategory.entries) {
        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        MirrorCategoryRow(category = category, onClick = { onNavigate(category) })
    }
}

@Composable
private fun MirrorCategoryRow(category: MirrorCategory, onClick: () -> Unit) {
    val vm = MirrorSpeedTestViewModel
    val selected = vm.selectedMirror(category)
    val active = vm.isActive(category)
    val fastest = vm.fastestResult(category)

    ListItem(
        headlineContent = { Text(category.displayName) },
        leadingContent = {
            CircleIconBadge(icon = category.icon, tint = category.iconColor)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active && selected != null) {
                    Text(
                        text = selected.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIfShort(),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (fastest?.latencyMs != null) {
                    LatencyBadge(fastest.latencyMs)
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun Modifier.widthIfShort(): Modifier = this

@Composable
private fun CircleIconBadge(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun LatencyBadge(ms: Int) {
    val color = when {
        ms < 200 -> ChatColors.success
        ms < 500 -> Color(0xFFFF9500)
        else -> ChatColors.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "${ms}ms",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorCategoryDetailScreen(
    category: MirrorCategory,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    MirrorSpeedTestViewModel.ensureLoaded(context)
    val vm = MirrorSpeedTestViewModel
    val scope = rememberCoroutineScope()

    val sortedResults = vm.results[category] ?: emptyList()
    val allForCategory = MirrorCatalog.mirrors(category)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mirror_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Current section ---
            Text(
                text = stringResource(R.string.mirror_current_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val selected = vm.selectedMirror(category)
            ListItem(
                headlineContent = { Text(stringResource(R.string.mirror_use_mirror_toggle)) },
                trailingContent = {
                    Switch(
                        checked = vm.useCustomMirror[category] == true,
                        onCheckedChange = { checked ->
                            vm.setUseCustom(context, category, checked)
                        },
                        // [dv15-B2 / B-02] Always enabled. The previous
                        // `enabled = selected != null` required picking a mirror
                        // before the switch could be touched, which forced one
                        // order of operations and left the switch looking dead.
                        // Both directions are safe with no selection —
                        // setUseCustom persists the flag and its IO branch
                        // no-ops (applyMirror returns early without a selection,
                        // restoreOfficial returns early without a .bak), and
                        // selectMirror re-applies once the flag is already on.
                        enabled = true,
                    )
                },
            )

            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            if (vm.isActive(category) && selected != null) {
                ListItem(
                    headlineContent = {
                        Text(
                            selected.name,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            selected.baseURL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mirror_official_default)) },
                    colors = ListItemDefaults.colors(
                        headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }

            // --- Mirrors / Results section ---
            HorizontalDivider()
            Text(
                text = if (sortedResults.isEmpty()) stringResource(R.string.mirror_mirrors_section) else stringResource(R.string.mirror_speed_test_results_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (sortedResults.isEmpty()) {
                allForCategory.forEachIndexed { index, mirror ->
                    MirrorRow(
                        mirror = mirror,
                        latencyMs = null,
                        failed = false,
                        isSelected = vm.selectedMirrorId[category] == mirror.id,
                        onClick = { vm.selectMirror(context, mirror) },
                    )
                    if (index < allForCategory.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
                    }
                }
            } else {
                sortedResults.forEachIndexed { index, result ->
                    MirrorRow(
                        mirror = result.mirror,
                        latencyMs = result.latencyMs,
                        failed = result.latencyMs == null,
                        isSelected = vm.selectedMirrorId[category] == result.mirror.id,
                        onClick = { vm.selectMirror(context, result.mirror) },
                    )
                    if (index < sortedResults.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
                    }
                }
            }

            // --- Test button ---
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(if (vm.isTesting) stringResource(R.string.mirror_test_speed_testing) else stringResource(R.string.mirror_test_speed_label)) },
                leadingContent = {
                    CircleIconBadge(icon = Icons.Filled.Bolt, tint = Color(0xFFFF9500))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !vm.isTesting) {
                        scope.launch { vm.runTest(context, category) }
                    },
            )
            if (vm.isTesting) {
                LinearProgressIndicator(
                    progress = { vm.testProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MirrorRow(
    mirror: MirrorEntry,
    latencyMs: Int?,
    failed: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mirror.name, style = MaterialTheme.typography.bodyMedium)
                if (mirror.isOfficial) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            stringResource(R.string.mirror_official_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    mirror.region,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Text(
                mirror.baseURL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (latencyMs != null) {
            LatencyBadge(latencyMs)
        } else if (failed) {
            Text(
                stringResource(R.string.mirror_timeout_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

