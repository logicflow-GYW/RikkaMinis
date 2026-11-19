package com.rikkaminis.app.webapp

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.R
import com.rikkaminis.app.crash.CrashFrequencyDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WebAppShortcutLaunchInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MinisApp

    @Test
    fun skippedInitShowsMissingSourceInsteadOfCrashing() {
        // Application.onCreate has already run in the instrumentation process.
        // Reproduce the state left by its early return, then restore it.
        val latch = CrashFrequencyDetector::class.java.getDeclaredField("_initSkipped").apply { isAccessible = true }
            .get(CrashFrequencyDetector) as AtomicBoolean
        val field = MinisApp::class.java.getDeclaredField("webAppShortcutRepository").apply { isAccessible = true }
        val original = field.get(app)
        val wasSkipped = latch.get()
        instrumentation.runOnMainSync {
            latch.set(true)
            field.set(app, null)
        }
        try {
            ActivityScenario.launch<WebAppActivity>(shortcutIntent(app, "skipped", 1)).use { scenario ->
                lateinit var missingTitle: String
                scenario.onActivity { activity ->
                    missingTitle = activity.getString(R.string.webapp_source_missing_title)
                    assertNull(findWebView(activity.window.decorView))
                }
                compose.onNodeWithText(missingTitle).assertIsDisplayed()
            }
        } finally {
            instrumentation.runOnMainSync {
                field.set(app, original)
                latch.set(wasSkipped)
            }
        }
    }

    @Test
    fun warmIntentSwitchesUrlAndTitleAndRepeatKeepsThePage() {
        val repo = app.webAppShortcutRepository
        val sessionId = "webapp-test-${UUID.randomUUID()}"
        val root = File(app.filesDir, "minis-sessions/$sessionId")
        val ids = mutableListOf<String>()
        fun create(folder: String, fileName: String, pageTitle: String): String = runBlocking {
            val html = File(root, "attachments/$folder/$fileName")
            assertTrue(html.parentFile!!.mkdirs())
            html.writeText("<!doctype html><title>$pageTitle</title>")
            repo.create("$folder/$fileName", "session_attachment", sessionId, pageTitle, "html", null, null)
                .id.also { ids += it }
        }
        try {
            val a = create("one", "a.html", "WebApp A")
            val b = create("two", "b.html", "WebApp B")
            ActivityScenario.launch<WebAppActivity>(shortcutIntent(app, a, 1)).use { scenario ->
                val first = awaitPage(scenario, "https://appassets.androidplatform.net/a.html", "WebApp A")
                scenario.onActivity { it.startActivity(shortcutIntent(it, b, 2)) }
                val second = awaitPage(scenario, "https://appassets.androidplatform.net/b.html", "WebApp B")
                assertTrue(first !== second)

                val jsResult = AtomicReference<String>()
                scenario.onActivity {
                    second.evaluateJavascript("window.repeatProbe = 'kept'") { jsResult.set(it) }
                }
                compose.waitUntil(10_000) { jsResult.get() == "\"kept\"" }
                jsResult.set(null)
                scenario.onActivity { it.startActivity(shortcutIntent(it, b, 3)) }
                compose.waitUntil(10_000) {
                    var delivered = false
                    scenario.onActivity { delivered = it.intent.getIntExtra("test_tap", 0) == 3 }
                    delivered
                }
                scenario.onActivity {
                    assertSame(second, findWebView(it.window.decorView))
                    second.evaluateJavascript("window.repeatProbe") { value -> jsResult.set(value) }
                }
                compose.waitUntil(10_000) { jsResult.get() != null }
                assertEquals("\"kept\"", jsResult.get())
            }
        } finally {
            runBlocking { ids.forEach { repo.delete(it) } }
            root.deleteRecursively()
        }
    }

    private fun awaitPage(scenario: ActivityScenario<WebAppActivity>, url: String, title: String): WebView {
        var loaded: WebView? = null
        compose.waitUntil(10_000) {
            scenario.onActivity { activity ->
                val view = findWebView(activity.window.decorView)
                if (view?.url == url && view.title == title && activity.title.toString() == title) loaded = view
            }
            loaded != null
        }
        return checkNotNull(loaded)
    }

    private fun shortcutIntent(context: Context, id: String, tap: Int) =
        Intent(context, WebAppActivity::class.java)
            .setAction("com.rikkaminis.app.action.OPEN_WEBAPP")
            .putExtra("webapp_shortcut_id", id)
            .putExtra("test_tap", tap)

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
