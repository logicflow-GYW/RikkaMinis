package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1024-G] — deep-audit-1024 ISSUE-07.
 *
 * `cacheDir/shared/` has exactly three producers and none of them pruned it:
 * `ProviderDetailScreen.exportProviderInstance` (here), `MCPIntegrationsScreen
 * .shareMcpServerJson` (here), `ChatExporter.exportToZip`. The first two also
 * ran their whole body — `exportInstanceJSON` (EncryptedSharedPreferences read
 * + full-instance serialization), `mkdirs()`, `writeText()` — straight from a
 * click callback on the UI thread, and the provider export carries the API key
 * in plain Base64 (see the confirmation dialog above the function).
 *
 * Both screens are Compose-only (no JVM-instantiable seam: they need a real
 * Context, FileProvider and a Chooser intent), so the contract is pinned by
 * source-shape assertions — the same approach `SkillFileViewerSaveShapeTest`
 * uses for its screen. Expected values are hardcoded literals, never derived
 * from the code under test, so a mutation cannot masquerade as PASS.
 *
 * The TTL boundary behaviour itself is not re-derived here: the pruner is
 * `ui/components/pruneShareDir`, whose `<` semantics are pinned behaviourally
 * by `ui.chat.LargeMessageExportPruneTest` ("exactly at the ttl boundary
 * survives", named as pruneShareDir parity). [pruner keeps the 24h strictly
 * older cutoff] pins the other half of that parity claim — the literal 24h
 * cutoff and the strict comparison — mechanically, so the two cannot drift
 * apart silently.
 */
class SharedCacheExportPruneTest {

    private val provider: String by lazy { readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/ProviderDetailScreen.kt") }
    private val mcp: String by lazy { readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt") }
    private val exporter: String by lazy { readRepoFile("app/src/main/java/com/rikkaminis/app/share/ChatExporter.kt") }
    private val pruner: String by lazy { readRepoFile("app/src/main/java/com/rikkaminis/app/ui/components/FullscreenImageViewer.kt") }

    private val providerFn: String by lazy { bracedBlock(provider, "private suspend fun exportProviderInstance(") }
    private val mcpFn: String by lazy { bracedBlock(mcp, "private suspend fun shareMcpServerJson(") }
    private val exporterFn: String by lazy { bracedBlock(exporter, "suspend fun exportToZip(") }

    // -- headline contract --

    @Test
    fun `every cacheDir shared writer calls pruneShareDir and runs disk IO on Dispatchers IO`() {
        val writers = mapOf(
            "ProviderDetailScreen" to provider,
            "MCPIntegrationsScreen" to mcp,
            "ChatExporter" to exporter,
        )
        for ((name, src) in writers) {
            assertEquals(
                "$name must construct cacheDir/shared exactly once",
                1,
                src.split("cacheDir, \"shared\")").size - 1,
            )
            assertTrue(
                "$name writes cacheDir/shared and must prune it (nothing else deletes that dir)",
                src.contains("pruneShareDir("),
            )
            assertTrue(
                "$name must run its cacheDir/shared disk work on Dispatchers.IO",
                src.contains("withContext(Dispatchers.IO)"),
            )
        }
    }

    // -- the two UI writers: suspend + IO + prune, share intent still on main --

    @Test
    fun `provider export keeps mkdirs prune and the encrypted read inside the IO block`() {
        val io = bracedBlock(providerFn, "withContext(Dispatchers.IO)")
        assertTrue("exportInstanceJSON reads EncryptedSharedPreferences — must be off the main thread", io.contains("exportInstanceJSON("))
        assertTrue("mkdirs() must ride the IO block", io.contains("mkdirs()"))
        assertTrue("the shared/ staging dir must be pruned before the write", io.contains("pruneShareDir("))
        assertTrue("the export write must ride the IO block", io.contains(".writeText("))
        assertTrue(
            "pruneShareDir( must precede the staging write",
            io.indexOf("pruneShareDir(") < io.indexOf(".writeText("),
        )
        assertTrue("exportProviderInstance must stay a suspend fun", provider.contains("private suspend fun exportProviderInstance("))
        // [deep-audit-1048 B-04] Shape, not spelling: the call now also CONSUMES
        // its verdict (`val launched = exportProviderInstance(…)`) so a failed
        // share can be reported, which broke the old
        // `scope.launch { exportProviderInstance(` literal. The intent — started
        // from a launch block, never inline on the click callback — is what this
        // asserts.
        assertTrue(
            "it must be launched, not called inline",
            insideLaunchBlock(provider, "exportProviderInstance("),
        )
    }

    @Test
    fun `provider share intent is staged after the IO block on the main thread`() {
        val marker = "withContext(Dispatchers.IO)"
        val io = bracedBlock(providerFn, marker)
        val ioEnd = providerFn.indexOf(marker) + io.length
        val providerUri = providerFn.indexOf("FileProvider.getUriForFile(")
        assertTrue("FileProvider URI construction must exist", providerUri > 0)
        assertTrue("FileProvider URI construction must happen after the IO block", providerUri > ioEnd)
        assertTrue("the chooser must exist", providerFn.indexOf("startActivity(") > ioEnd)
        assertTrue(
            "the FileProvider authority must be unchanged",
            providerFn.contains("\"\${context.packageName}.fileprovider\""),
        )
    }

    @Test
    fun `mcp share keeps mkdirs prune and the write inside the IO block`() {
        val io = bracedBlock(mcpFn, "withContext(Dispatchers.IO)")
        assertTrue("mkdirs() must ride the IO block", io.contains("mkdirs()"))
        assertTrue("the shared/ staging dir must be pruned before the write", io.contains("pruneShareDir("))
        assertTrue("the staging write must ride the IO block", io.contains(".writeText("))
        assertTrue(
            "pruneShareDir( must precede the staging write",
            io.indexOf("pruneShareDir(") < io.indexOf(".writeText("),
        )
        assertTrue("shareMcpServerJson must stay a suspend fun", mcp.contains("private suspend fun shareMcpServerJson("))
        // [deep-audit-1048 B-04] Same shape-not-spelling fix as the provider
        // export above: the verdict is consumed before the toast.
        assertTrue(
            "it must be launched, not called inline",
            insideLaunchBlock(mcp, "shareMcpServerJson("),
        )
        val ioEnd = mcpFn.indexOf("withContext(Dispatchers.IO)") + io.length
        assertTrue("FileProvider URI construction must happen after the IO block", mcpFn.indexOf("FileProvider.getUriForFile(") > ioEnd)
        assertTrue("the chooser must exist", mcpFn.indexOf("startActivity(") > ioEnd)
        assertTrue(
            "the FileProvider authority must be unchanged",
            mcpFn.contains("\"\${context.packageName}.fileprovider\""),
        )
    }

    // -- the zip exporter: already on IO, only the prune is new --

    @Test
    fun `zip exporter prunes between the shared dir and the zip stream`() {
        val ioPos = exporterFn.indexOf("withContext(Dispatchers.IO)")
        val dirPos = exporterFn.indexOf("cacheDir, \"shared\")")
        val prunePos = exporterFn.indexOf("pruneShareDir(")
        val zipPos = exporterFn.indexOf("ZipOutputStream(")
        assertTrue("exportToZip must still run on Dispatchers.IO", ioPos >= 0)
        assertTrue("the shared/ dir construction must exist", dirPos >= 0)
        assertTrue("exportToZip must prune cacheDir/shared", prunePos >= 0)
        assertTrue("the zip stream must exist", zipPos >= 0)
        assertTrue("expected IO < dir < prune < ZipOutputStream", ioPos < dirPos && dirPos < prunePos && prunePos < zipPos)
        assertTrue("the pruner must receive the shared dir", exporterFn.contains("pruneShareDir(sharedDir)"))
    }

    // -- the pruner itself: parity anchor for the behavioural test --

    @Test
    fun `pruner keeps the 24h strictly older cutoff`() {
        assertTrue(
            "pruneShareDir must keep the literal 24h cutoff (parity with LargeMessageExportPruneTest)",
            pruner.contains("val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000"),
        )
        assertTrue(
            "expiry must stay strictly-older-than (age == ttl survives)",
            pruner.contains("f.isFile && f.lastModified() < cutoff)"),
        )
        assertTrue(
            "pruneShareDir must stay reachable from other packages (internal, not private)",
            pruner.contains("internal fun pruneShareDir(dir: File)"),
        )
    }

    // -- helpers --

    /** Same repo-root walk as SkillFileViewerSaveShapeTest: Gradle sets user.dir
     *  to the module dir, a sandbox JVM run may start anywhere below it. */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** True when [callMarker] sits inside SOME `launch { … }` block of [src]. */
    private fun insideLaunchBlock(src: String, callMarker: String): Boolean {
        var from = 0
        while (true) {
            val at = src.indexOf("launch {", from)
            if (at < 0) return false
            val block = bracedBlock(src.substring(at), "launch {")
            if (block.contains(callMarker)) return true
            from = at + "launch {".length
        }
    }

    /** Substring from [marker] through the brace that closes the first block
     *  opened after it — tolerates multi-line signatures, which
     *  `exportProviderInstance` has. */
    private fun bracedBlock(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("no block opened after: $marker", open >= 0)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced block after: $marker")
    }
}
