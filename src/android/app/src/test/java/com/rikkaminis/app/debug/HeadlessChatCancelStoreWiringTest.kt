package com.rikkaminis.app.debug

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1039 A-06] `HeadlessChatRunner.cancel` must resolve the
 * ChatViewModel through the process-wide single source
 * (`ChatViewModelStore`), not only through the runner's private cache.
 *
 * The defect: `cancel` read `providers[sessionId]` — a map that only the RPC
 * entry points (`chat.prompt` / `retry` / …) write. A session the **UI**
 * started (ChatScreen binds its VM directly through
 * `ChatViewModelStore.ownerFor`) and a draft-key id (`__new__<uuid>`) whose
 * store lives under the promoted id are both absent from that map, so
 * `cancel` returned false and cancelled nothing — while
 * `debug/ChatMutationMethods.deleteSession` calls it *before* deleting the
 * row, leaving a streaming job writing into a deleted session.
 *
 * These are wiring shapes across the ViewModelProvider / ViewModelStore
 * boundary, and `ChatViewModelStore` pulls in `AppLogger` + `SessionIdAliases`
 * (no Android-free seam), so the contract is asserted on the production text —
 * the same stance as `FastioWiringTest` / `A11yRecoverySlotShapeTest`. Every
 * expected string is a literal, and each arm has a negative control that
 * mutates a copy of the real source and asserts the very same check fails.
 */
class HeadlessChatCancelStoreWiringTest {

    private fun sourceFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }

    private fun source(rel: String): String = sourceFile(rel).readText()

    private val runnerFile =
        "src/android/app/src/main/java/com/rikkaminis/app/debug/HeadlessChatRunner.kt"

    /** The whole `cancel` function, from its declaration to the closing brace. */
    private fun cancelBody(runner: String): String {
        val start = runner.indexOf("suspend fun cancel(")
        assertTrue("cancel must exist", start >= 0)
        var depth = 0
        var i = runner.indexOf('{', start)
        assertTrue("cancel must have a body", i >= 0)
        val from = i
        while (i < runner.length) {
            when (runner[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return runner.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after cancel(")
    }

    /** The resolver that `cancel` delegates its session-id resolution to. */
    private fun resolverBody(runner: String): String {
        val start = runner.indexOf("private fun viewModelForCancel(")
        assertTrue("the resolver must exist", start >= 0)
        val from = runner.indexOf('{', start)
        var depth = 0
        var i = from
        while (i < runner.length) {
            when (runner[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return runner.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces after viewModelForCancel(")
    }

    private fun assertResolvesThroughStore(cancel: String, resolver: String) {
        assertTrue(
            "cancel must not read the private providers map directly — that map is " +
                "written only by the RPC entry points, so a UI-started session (bound " +
                "straight through ChatViewModelStore) would never be found",
            !cancel.contains("providers[sessionId]"),
        )
        assertTrue(
            "cancel must resolve the ViewModel through the shared resolver, so the " +
                "decision of WHERE a session's VM lives is made in exactly one place",
            cancel.contains("viewModelForCancel("),
        )
        assertTrue(
            "the resolver must consult the process-wide ChatViewModelStore (the cache " +
                "the UI's ChatScreen binds through)",
            resolver.contains("ChatViewModelStore.ownerFor("),
        )
        assertTrue(
            "the resolver must resolve the draft alias when building from the store — " +
                "a __new__<uuid> key's store lives under the promoted id",
            resolver.contains("ChatViewModelStore.resolveSessionId("),
        )
    }

    private fun assertCacheStillFastPath(cancel: String, resolver: String) {
        // The private cache is a deliberate performance path for RPC-driven
        // sessions; the fix must not have removed it, only demoted it.
        assertTrue(
            "the private providers cache must stay (it is the fast path for " +
                "RPC-started sessions) — dropping it would change both performance " +
                "and per-key VM identity",
            resolver.contains("providers[sessionId]"),
        )
        assertTrue(
            "a cache hit must short-circuit (no store round-trip when the provider " +
                "object is already built for this exact key)",
            resolver.contains("providers[sessionId]") &&
                resolver.contains("return"),
        )
    }

    @Test
    fun `cancel resolves the session through the shared store single source`() {
        val runner = source(runnerFile)
        assertResolvesThroughStore(cancelBody(runner), resolverBody(runner))
    }

    @Test
    fun `the private cache remains as the fast path`() {
        val runner = source(runnerFile)
        assertCacheStillFastPath(cancelBody(runner), resolverBody(runner))
    }

    @Test
    fun `cancelling a stream still reports it was running`() {
        // Return-value contract: unchanged from before the fix — false only
        // when nothing is streaming.
        val cancel = cancelBody(source(runnerFile))
        assertTrue(
            "cancel must read the streaming flag before cancelling (the return value " +
                "is the caller's only signal that a stream was actually stopped)",
            cancel.contains("isStreaming.value"),
        )
        assertTrue(
            "cancel must invoke cancelStream, not merely observe the flag",
            cancel.contains("vm.cancelStream()"),
        )
        assertTrue(
            "cancel must return early (false) when no ViewModel resolves at all",
            cancel.contains("?: return@withContext false"),
        )
    }

    // ── negative controls ──────────────────────────────────────────────────
    // Each mutates a copy of the real source and asserts the very same check
    // fails. Without these, a probe whose target was deleted would read green.

    private fun withResolver(runner: String, replacement: String): String {
        val start = runner.indexOf("private fun viewModelForCancel(")
        assertTrue("resolver must exist to be mutated", start >= 0)
        val from = runner.indexOf('{', start)
        var depth = 0
        var i = from
        while (i < runner.length) {
            when (runner[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        return runner.substring(0, from) + replacement + runner.substring(i + 1)
    }

    @Test
    fun `negative control - cancel back to reading only the private cache is caught`() {
        val original = source(runnerFile)
        val mutated = original.replace(
            "        val vm = viewModelForCancel(context, sessionId, persisted) " +
                "?: return@withContext false\n",
            "        val cached = providers[sessionId] ?: return@withContext false\n" +
                "        val vm = cached[ChatViewModel::class.java]\n",
        )
        assertTrue("the wiring must exist to be mutated", mutated != original)
        assertTrue(
            "a cancel that only reads the cache must fail the single-source probe",
            runCatching {
                assertResolvesThroughStore(cancelBody(mutated), resolverBody(mutated))
            }.isFailure,
        )
    }

    @Test
    fun `negative control - resolver that skips the store is caught`() {
        val original = source(runnerFile)
        val mutated = withResolver(
            original,
            "{\n" +
                "        val cached = providers[sessionId] ?: return null\n" +
                "        return cached[ChatViewModel::class.java]\n" +
                "    }\n",
        )
        assertTrue("the resolver body must have been replaced", mutated != original)
        assertTrue(
            "a resolver that never consults ChatViewModelStore must fail",
            runCatching {
                assertResolvesThroughStore(cancelBody(mutated), resolverBody(mutated))
            }.isFailure,
        )
    }

    @Test
    fun `negative control - resolver that forgets the draft alias hop is caught`() {
        val original = source(runnerFile)
        val mutated = withResolver(
            original,
            "{\n" +
                "        providers[sessionId]?.let { return it[ChatViewModel::class.java] }\n" +
                "        val owner = ChatViewModelStore.ownerFor(sessionId)\n" +
                "        val app = app(context)\n" +
                "        return ViewModelProvider(owner, ChatViewModel.factory(\n" +
                "            sessionId = sessionId,\n" +
                "            chatRepository = app.chatRepository,\n" +
                "            providerRepository = app.providerRepository,\n" +
                "            appContext = app.applicationContext,\n" +
                "            memoryRepository = app.memoryRepository,\n" +
                "            skillRepository = app.skillRepository,\n" +
                "            mcpRepository = app.mcpRepository,\n" +
                "        ))[ChatViewModel::class.java]\n" +
                "    }\n",
        )
        assertTrue("the resolver body must have been replaced", mutated != original)
        assertTrue(
            "a resolver that builds the VM under the raw draft key must fail — the " +
                "store's live VM sits under the promoted id",
            runCatching {
                assertResolvesThroughStore(cancelBody(mutated), resolverBody(mutated))
            }.isFailure,
        )
    }

    @Test
    fun `negative control - dropping the cache fast path is caught`() {
        val original = source(runnerFile)
        val mutated = withResolver(
            original,
            "{\n" +
                "        val owner = ChatViewModelStore.ownerFor(sessionId)\n" +
                "        val app = app(context)\n" +
                "        return ViewModelProvider(owner, ChatViewModel.factory(\n" +
                "            sessionId = ChatViewModelStore.resolveSessionId(sessionId),\n" +
                "            chatRepository = app.chatRepository,\n" +
                "            providerRepository = app.providerRepository,\n" +
                "            appContext = app.applicationContext,\n" +
                "            memoryRepository = app.memoryRepository,\n" +
                "            skillRepository = app.skillRepository,\n" +
                "            mcpRepository = app.mcpRepository,\n" +
                "        ))[ChatViewModel::class.java]\n" +
                "    }\n",
        )
        assertTrue("the resolver body must have been replaced", mutated != original)
        assertTrue(
            "a resolver that dropped the private cache must fail the fast-path probe",
            runCatching {
                assertCacheStillFastPath(cancelBody(mutated), resolverBody(mutated))
            }.isFailure,
        )
    }

    // ── [R4-R F-01] the row gate ───────────────────────────────────────────
    // A cancel/compact for an id with no `chat_sessions` row must not BUILD a
    // ChatViewModel: `ChatViewModelStore.ownerFor` allocates the process-wide
    // store entry and the ViewModelProvider lookup constructs the VM, and
    // `requireSendableSession` accepts a resident VM as "the user is looking at
    // it" for every later send. Returns a Boolean so the negative control can
    // assert the same check fails on a mutated copy.
    private fun holdsRowGate(cancel: String, resolver: String): Boolean =
        cancel.contains("dao.getSession(") &&
            cancel.contains("persisted") &&
            resolver.contains("ChatViewModelStore.peekViewModel(") &&
            resolver.contains("if (!persistedSession) return null")

    @Test
    fun `cancel reads the session row before building a view model`() {
        val runner = source(runnerFile)
        assertTrue(
            "the row read must happen in cancel and gate the resolver — a row-less " +
                "id must answer false, not materialise a resident VM",
            holdsRowGate(cancelBody(runner), resolverBody(runner)),
        )
    }

    @Test
    fun `negative control - building a view model for a row-less id is caught`() {
        val original = source(runnerFile)
        val mutated = original.replace(
            "        ChatViewModelStore.peekViewModel(sessionId)?.let { return it }\n",
            "",
        )
        assertTrue("the store peek must exist to be mutated", mutated != original)
        assertTrue(
            "a resolver that skips the non-destructive store read must fail the row-gate probe",
            !holdsRowGate(cancelBody(mutated), resolverBody(mutated)),
        )
    }

    @Test
    fun `probes look at a real source tree, not a fixture`() {
        // Sanity: the file under test is the production runner, so a deleted
        // function shows up as a failed lookup rather than a green no-op.
        val runner = source(runnerFile)
        assertTrue(
            "the runner must be the production file (package line present)",
            runner.contains("package com.rikkaminis.app.debug"),
        )
        assertTrue(runner.contains("internal object HeadlessChatRunner"))
        assertEquals(
            1,
            runner.split("suspend fun cancel(").size - 1,
        )
    }
}
