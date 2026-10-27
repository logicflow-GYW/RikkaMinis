package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [deep-audit-1041 B-01] The credential snapshot is a LIVE SOURCE problem, not a
 * write-path one.
 *
 * The stored API key does not live in `ProviderInstance` — it is a separate
 * EncryptedSharedPreferences entry (`providerRepository.loadApiKey(id)`), so no
 * field of the config object carries it and no plain field key can observe an
 * external write. The available signal is `config.revision`, which every
 * credential-writing path bumps (instancesUpdate RPC, provider import, backup
 * restore).
 *
 * Two screens were missed by the earlier fix:
 *
 *  - `ProviderConnectionScreen` read the key once per `instanceId`
 *    (`LaunchedEffect(instanceId)`). An external write therefore left the
 *    previous key on screen, and the next Edit → Save seeded the editor from
 *    that stale snapshot and wrote it back over the newer credential — a silent
 *    credential rollback.
 *  - `ProviderListScreen` read every key inside a `remember(instances,
 *    config.modelEntries)` initializer: blocking keystore I/O on the
 *    composition thread, keyed on two sources that an external credential write
 *    does not move.
 *
 * Nothing here is observable from a JVM test — both properties are Compose
 * wiring — so the invariants are pinned on the production text, like the
 * neighbouring `*ShapeTest`s. Arms are fail-closed: each asserts the exact
 * property it names, and every arm was exercised against the pre-fix file (the
 * L3 probe in this branch's report) so a probe that stopped looking fails
 * loudly instead of passing.
 */
class ProviderCredentialLiveSourceTest {

    private val connectionRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ProviderConnectionScreen.kt"
    private val listRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ProviderListScreen.kt"

    /**
     * Gradle runs unit tests with `user.dir` = the module dir (`src/android/app`)
     * while a local JVM run usually starts at the repo root, so resolve by
     * walking up until the path exists (the idiom `MediaHostSourceKeyTest`
     * established; a root-relative read went red on CI when it did not).
     */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError(
            "$rel not found from user.dir=${System.getProperty("user.dir")}"
        )
    }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** Text of the balanced `{ … }` whose `{` sits at [open]. */
    private fun braced(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced brace at $open — the probe cannot read this file")
    }

    /** The body of every `<call>(…) { … }` invocation of [call]. */
    private fun invocationBodies(code: String, call: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = code.indexOf("$call(", from)
            if (at < 0) return out
            from = at + call.length
            // Skip the argument list, balancing parens, then take the body.
            var i = at + call.length
            var depth = 0
            var close = -1
            while (i < code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            close = i
                            break
                        }
                    }
                }
                i++
            }
            if (close < 0) continue
            val open = code.indexOf('{', close)
            if (open < 0) continue
            out.add(braced(code, open))
            from = open
        }
    }

    @Test
    fun `the list screen never reads credentials during composition`() {
        val code = read(listRel)
        // Every `remember(…) { … }` initializer runs during composition; the
        // pre-fix file fetched each row's key from inside the providerRows one.
        val offenders = invocationBodies(code, "remember").filter { it.contains("loadApiKey(") }
        assertEquals(
            "ProviderListScreen must not call loadApiKey() from a remember initializer " +
                "(blocking encrypted I/O on the composition thread): $offenders",
            0,
            offenders.size,
        )
    }

    @Test
    fun `the list screen produces its credential map in a revision-keyed effect off the main thread`() {
        val code = read(listRel)
        val body = invocationBodies(code, "LaunchedEffect")
            .firstOrNull { it.contains("loadApiKey(") }
        assertTrue(
            "ProviderListScreen needs an effect that reads the credentials — an external " +
                "write changes neither `instances` nor `modelEntries`, so nothing else " +
                "refreshes the row badge",
            body != null,
        )
        assertTrue(
            "the credential producer must be keyed on the instance list AND config.revision: $body",
            code.contains("LaunchedEffect(instances, config.revision)"),
        )
        assertTrue(
            "the producer must hop off the main thread (withContext(Dispatchers.IO)); " +
                "loadApiKey() is synchronous encrypted I/O: $body",
            body!!.contains("withContext(") && body.contains("Dispatchers.IO"),
        )
    }

    @Test
    fun `the list rows re-derive from the credential map`() {
        val code = read(listRel)
        val at = code.indexOf("val providerRows")
        assertTrue("ProviderListScreen has no `val providerRows` derivation", at >= 0)
        val head = code.substring(at, minOf(code.length, at + 220))
        val keys = head.substringAfter("remember(", "").substringBefore(')')
        assertTrue("providerRows lost its `instances` key: $keys", keys.contains("instances"))
        assertTrue(
            "providerRows lost its `config.modelEntries` key (the [da1025-F] modelCount " +
                "freshness key): $keys",
            keys.contains("modelEntries"),
        )
        assertTrue(
            "providerRows must also key on the asynchronously loaded credential map, or a key " +
                "written externally never refreshes the row badge: $keys",
            keys.contains("apiKey"),
        )
    }

    @Test
    fun `the connection screen re-reads the stored key when the revision moves`() {
        val code = read(connectionRel)
        assertTrue(
            "ProviderConnectionScreen must key its stored-key effect on `config.revision` as " +
                "well: an external write leaves the previous key on screen and the next " +
                "Edit → Save writes it back over the newer credential",
            code.contains("LaunchedEffect(instanceId, config.revision) {"),
        )
        val body = invocationBodies(code, "LaunchedEffect")
            .firstOrNull { it.contains("storedKey") }
            ?: throw AssertionError("the stored-key effect is gone")
        assertTrue(
            "the revision-keyed effect must assign the stored key from loadApiKey(): $body",
            body.contains("loadApiKey(instanceId)"),
        )
        assertTrue(
            "the re-read must hop off the main thread: $body",
            body.contains("withContext(") && body.contains("Dispatchers.IO"),
        )
    }

    @Test
    fun `the connection key set stays narrow so an unrelated config emit does not re-read`() {
        val code = read(connectionRel)
        // Keying on the whole `config` object would re-run the keystore read on
        // every emit (every provider write, every DTO → StateFlow frame).
        assertFalse(
            "the stored-key effect must not be keyed on the whole config object",
            code.contains("LaunchedEffect(instanceId, config)"),
        )
        assertEquals(
            "exactly one credential read belongs on this screen (the revision-keyed effect)",
            1,
            Regex("""loadApiKey\(""").findAll(code).count(),
        )
    }

    @Test
    fun `the re-read never clobbers an in-flight draft`() {
        val code = read(connectionRel)
        val bodies = invocationBodies(code, "LaunchedEffect")
        val stored = bodies.firstOrNull { it.contains("storedKey") }
            ?: throw AssertionError("no revision-keyed stored-key effect to inspect")
        assertFalse(
            "the background re-read must not touch the user's unsaved draft (editKeyValue) — " +
                "the draft wins until cancel/save: $stored",
            stored.contains("editKeyValue"),
        )
        // And no other effect may seed the editor behind the user's back either.
        for (body in bodies) {
            assertFalse(
                "a LaunchedEffect assigns editKeyValue — an effect must never overwrite the " +
                    "user's draft: $body",
                body.contains("editKeyValue ="),
            )
        }
    }
}
