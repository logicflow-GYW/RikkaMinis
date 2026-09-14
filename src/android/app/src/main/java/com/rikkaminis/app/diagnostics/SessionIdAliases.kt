package com.rikkaminis.app.diagnostics

import com.rikkaminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Draft → canonical session-id aliases, mirrored for diagnostic output.
 *
 * A chat session is born under a draft id (`__new__<uuid>`) and re-keyed to its
 * persisted UUID when the first message materialises it (ChatViewModelStore.rename).
 * UI-held references (nav args, the VM's own key) keep the draft id on purpose, so
 * diagnostics emitted from those layers used to print the stale draft name for the
 * rest of the screen's lifetime. One session then appeared under two names in
 * `logs/`, and when the rename itself predated the capture window no record was
 * left to stitch them together — silently breaking single-file chain
 * reconstruction (found 2026-09-14: __new__577217c7 vs 19550430).
 *
 * Contract:
 *  - [register]/[unregisterByCanonical] are called by ChatViewModelStore next to
 *    its own alias table (single writer, main thread). This registry mirrors it
 *    for readers that cannot reach the ui/chat layer (diagnostics, service).
 *  - [resolve] is a per-emit call: diagnostics must never cache the result, so the
 *    name switches the moment the alias exists.
 *  - The first rewrite of an id emits a one-shot "alias resolved …" line, so the
 *    mapping stays visible to log readers even when the original rename happened
 *    outside the capture window.
 */
object SessionIdAliases {
    private const val TAG = "ChatVMStore"

    private val aliases = ConcurrentHashMap<String, String>()
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /** Test seam, mirroring PerfLongCtx.sinkForTest. */
    internal var sinkForTest: ((String, String) -> Unit)? = null

    fun register(fromId: String, toId: String) {
        if (fromId == toId) return
        aliases[fromId] = toId
    }

    fun unregisterByCanonical(toId: String) {
        aliases.entries.removeAll { it.value == toId }
    }

    /** Follows at most one hop (draft → canonical); unknown ids pass through. */
    fun resolve(sessionId: String): String {
        val mapped = aliases[sessionId] ?: return sessionId
        val canonical = aliases[mapped] ?: mapped
        if (canonical != sessionId && reported.add(sessionId)) {
            val message = "alias resolved $sessionId -> $canonical (diag first-use)"
            val sink = sinkForTest
            if (sink != null) sink(TAG, message) else AppLogger.info(TAG, message)
        }
        return canonical
    }

    internal fun clearForTest() {
        aliases.clear()
        reported.clear()
        sinkForTest = null
    }
}
