package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static active-path evidence only. Does not claim GPU sampling or screenshot equivalence. */
class GlassSurfaceWiringTest {
    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (prefix in listOf("src/android/app", "app")) {
                val file = File(dir, "$prefix/src/main/java/com/rikkaminis/app/$path")
                // Only whole comment lines/blocks beginning a line: an unanchored
                // slash-star regexp eats code after minis://attachments/* literals.
                if (file.isFile) return file.readText()
                    .replace(Regex("(?ms)^\\h*/\\*.*?\\*/"), "")
                    .replace(Regex("(?m)^\\h*//[^\\n]*"), "")
            }
            dir = dir.parentFile
        }
        error("source not found: $path")
    }

    @Test fun `settings has one local source outside the top bar and an off path`() {
        val s = source("ui/settings/SettingsComponents.kt")
        assertEquals(1, Regex("val hazeState = rememberHazeState\\(\\)").findAll(s).count())
        assertTrue(s.contains("hasSafeSource = scrollable"))
        assertTrue(s.contains("LocalGlassBackdrop provides backdrop"))
        val topBar = s.indexOf("GlassTopBarLayers(backdrop, scrolledUnder,")
        val record = s.indexOf("Modifier.topBarTail(topExtent).hazeSource(hazeState)")
        assertTrue(topBar >= 0 && record > topBar)
        assertTrue(s.contains("if (backdrop != null) padding.calculateTopPadding() else 0.dp"))
        assertTrue(s.contains("baseMod.verticalScroll(scrollState).padding(top = topExtent) else baseMod"))
    }

    @Test fun `settings retains both title alignments slots IME and focus safety`() {
        val s = source("ui/settings/SettingsComponents.kt")
        assertTrue(s.contains("CenterAlignedTopAppBar("))
        assertTrue(s.contains("navigation != null -> navigation()"))
        assertTrue(s.contains("onBack != null -> IconButton(onClick = onBack)"))
        assertEquals(2, Regex("actions = \\{ actions\\?\\.invoke\\(\\) \\}").findAll(s).count())
        assertTrue(s.contains(".imePadding()"))
        assertTrue(s.contains("ProvideGlassBringIntoViewSpec(topExtent)"))
    }

    @Test fun `toolbar floor is separate opaque and drawn before the sampled layer`() {
        val s = source("ui/components/GlassSurfaces.kt")
        val floor = s.indexOf("TopBarGlassOcclusion.floorAlpha(0f)")
        val effect = s.indexOf("hazeEffect(state = backdrop.state, style = style)")
        assertTrue(floor >= 0 && effect > floor)
        assertTrue(s.contains("if (backdrop == null) return"))
        assertTrue(s.contains("GlassSurfacePolicy.showTopBar(backdrop != null, scrolledUnder)"))
        assertTrue(s.contains("fallbackTint = HazeTint(base)"))
        assertFalse(s.contains("hazeSource("))
    }

    @Test fun `popup samples only its ready owning source and preserves caller overrides`() {
        val s = source("ui/components/MinisMenu.kt")
        assertTrue(s.contains("val backdrop = LocalGlassBackdrop.current"))
        assertTrue(s.contains("it.contentLayer != null"))
        assertTrue(s.contains("GlassSurfacePolicy.menuGlass(backdrop != null, sourceReady, containerColor != null)"))
        assertTrue(s.contains("if (useGlass) Color.Transparent else resolvedContainerColor"))
        assertTrue(s.contains("if (useGlass && backdrop != null)"))
        assertTrue(s.contains("hazeEffect(state = backdrop.state, style = glassStyle)"))
        assertFalse(s.contains("OnSharedPreferenceChangeListener"))
        assertFalse(s.contains("hazeSource("))
    }

    @Test fun `chat only lends its source to chat content not drawer or external sheets`() {
        val s = source("ui/chat/ChatScreen.kt")
        assertTrue(s.contains("rememberGlassBackdrop(hazeState, tuning, hasSafeSource = true)"))
        val drawer = s.indexOf("ModalNavigationDrawer(")
        val provider = s.indexOf("CompositionLocalProvider(LocalGlassBackdrop provides glassBackdrop)")
        val scaffold = s.indexOf("Scaffold(", provider)
        val externalSheet = s.indexOf("if (showBrowserSheet)")
        assertTrue(drawer >= 0 && provider > drawer && scaffold > provider && externalSheet > scaffold)
        assertTrue(s.contains("if (composerGlassOn) Modifier.hazeSource(hazeState) else Modifier"))
        assertTrue(s.contains("TopBarGlassOcclusion.floorAlpha(topBarGlassAlpha)"))
        assertTrue(s.contains(".clip(composerArcClip)"))
    }

    @Test fun `native markdown source reaches real document while WebView stays solid`() {
        val s = source("ui/sandbox/FilePreviewScreen.kt")
        assertTrue(s.contains("val previewHazeState = key(item.file) { rememberHazeState() }"))
        assertTrue(s.contains("hasSafeSource = item.isMarkdownFile"))
        assertTrue(s.contains(".hazeSource(previewHazeState)"))
        assertTrue(s.contains(".nestedScroll(markdownScrollConnection)"))
        assertTrue(s.contains("GlassSurfacePolicy.scrollOffset(markdownScroll, consumed.y)"))
        assertTrue(s.contains("modifier = Modifier.fillMaxSize().then(documentLayout.modifier)"))
        assertTrue(s.contains("top = 12.dp + documentLayout.topInset"))
        assertTrue(s.contains("item.isHtmlFile -> HtmlPreview(item)"))
        assertEquals(1, Regex("\\.hazeSource\\(").findAll(s).count())
        // The bounded-reader gate pins this exact signature; the geometry must
        // travel through the local carrier instead of growing the parameter list.
        assertEquals(1, Regex("private fun MarkdownPreview\\(item: FileItem\\) \\{").findAll(s).count())
        assertTrue(s.contains("LocalGlassDocumentLayout provides GlassDocumentLayout("))
        assertTrue(s.contains(") { MarkdownPreview(item) }"))
        val renderer = source("ui/chat/StreamingMarkdownText.kt")
        val doc = renderer.substringAfter("fun MarkdownDocument(").substringBefore("// ─── Block-level splitting")
        assertTrue(doc.contains("androidx.compose.foundation.lazy.LazyColumn("))
        assertTrue(doc.contains("modifier = modifier,"))
    }

    @Test fun `shared reader reuses the existing stable disposed listener without writes`() {
        val s = source("ui/components/GlassSurfaces.kt")
        assertTrue(s.contains("rememberChatTuning(LocalContext.current)"))
        assertTrue(s.contains("GlassSurfacePolicy.canRecord(tuning.composerGlassEnabled, Build.VERSION.SDK_INT, hasSafeSource)"))
        assertFalse(s.contains(".edit()"))
        val reader = source("ui/settings/ChatTuningScreen.kt").substringAfter("fun rememberChatTuning(")
        assertTrue(reader.contains("val prefs = remember(context)"))
        assertTrue(reader.contains("DisposableEffect(prefs)"))
        assertTrue(reader.contains("onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }"))
    }
}
