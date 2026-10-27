package com.rikkaminis.app.offload

import com.rikkaminis.app.offload.OffloadPermissionManager.AndroidPermissionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [da1031-B ISSUE-02] The three UI round-trip slots of
 * [OffloadPermissionManager] must admit at most one caller at a time, and every
 * release path must go through the one guarded release point.
 *
 * Three layers, labelled honestly (1027-E convention):
 *
 *  1. **Behaviour, real objects** — the two concurrency tests drive the real
 *     `requestAndroidPermission` / `respondToAndroidPermission` /
 *     `pendingAndroidPermission` from real threads. `requestAndroidPermission`
 *     touches no `Context`/`SharedPreferences`, so the JVM source set can run it
 *     as-is; the only stand-in is the coroutines event loop.
 *  2. **Behaviour, real fields** — the rename/clear test seeds the real
 *     `sessionGrants` / `sessionDenials` maps by reflection and calls the real
 *     production functions; nothing is mirrored.
 *  3. **Source shape** — the release-point uniqueness and the untouched-contract
 *     assertions read the compiled sources, the same probe shape as
 *     `OffloadPermissionResetTest` / `FastioWiringTest`. Each shape probe has
 *     negative controls that mutate a copy of the real source and assert the
 *     same probe fails, so a deleted line cannot show up as a green test that
 *     checks nothing.
 */
class OffloadPermissionSlotLockTest {

    private val permission = "android.permission.ACCESS_FINE_LOCATION"

    /**
     * Rounds of the simultaneous-entry race. See `raceOnce` for why one round is
     * not a fair test of the pre-fix tree.
     */
    private val raceRounds = 20

    private val managerRel = "src/main/java/com/rikkaminis/app/offload/OffloadPermissionManager.kt"
    private val chatEntryRel = "src/main/java/com/rikkaminis/app/ui/chat/ChatSessionEntryExt.kt"
    private val appRel = "src/main/java/com/rikkaminis/app/MinisApp.kt"
    private val viewModelRel = "src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"

    /** The three slots, by field name. */
    private val slotFields = listOf(
        "androidPermissionContinuation",
        "settingsGateContinuation",
        "pendingContinuation",
    )

    // ── behaviour: the real round-trip, driven from two threads ───────────────

    @Test
    fun `exactly one of several simultaneous requests owns the slot and the rest are rejected at once`() {
        // One round is not enough to be a fair test of the pre-fix tree: the
        // window between the claim (a bare read) and the install is only a few
        // instructions wide, so a single race reproduced the orphaned waiter
        // roughly one run in three on this box (measured — see the FIX-B report
        // §5). Repeating the race keeps the fixed-tree assertion deterministic
        // while turning the pre-fix failure into a near-certainty.
        repeat(raceRounds) { round -> raceOnce(round) }
    }

    private fun raceOnce(round: Int) {
        val callers = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(callers)
        val granted = AtomicInteger()
        val rejected = AtomicInteger()
        val unexpected = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()

        // Every caller enters at the same instant: the pre-fix claim was a pure
        // read, so callers that read the slot before the first install all
        // believed they owned it and parked; only the last one could ever be
        // answered. Reserving under the lock makes exactly one of them the owner.
        (1..callers).map { index ->
            Thread {
                try {
                    start.await()
                    val result = runBlocking {
                        withTimeout(5_000L) {
                            OffloadPermissionManager.requestAndroidPermission(listOf(permission))
                        }
                    }
                    when (result) {
                        AndroidPermissionResult.GRANTED -> granted.incrementAndGet()
                        AndroidPermissionResult.TIMEOUT -> rejected.incrementAndGet()
                        // Nobody answered the dialog yet, so nobody may inherit
                        // an answer: a DENIED here would mean one caller read
                        // another caller's dialog outcome.
                        AndroidPermissionResult.DENIED -> unexpected.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }.apply {
                isDaemon = true
                name = "slot-caller-$round-$index"
            }
        }.forEach { it.start() }

        start.countDown()

        runBlocking {
            withTimeout(5_000L) { OffloadPermissionManager.pendingAndroidPermission.first { it != null } }
        }

        // Everyone except the owner must be out before the user has answered.
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline && rejected.get() < callers - 1) {
            Thread.sleep(20L)
        }
        assertEquals(
            "round $round: every caller but the owner must be rejected at once",
            callers - 1,
            rejected.get(),
        )
        assertEquals("round $round: nobody may be answered before the user answered", 0, granted.get())
        assertNotNull(OffloadPermissionManager.pendingAndroidPermission.value)

        OffloadPermissionManager.respondToAndroidPermission(AndroidPermissionResult.GRANTED)

        assertTrue("round $round: not every caller returned", done.await(5, TimeUnit.SECONDS))
        assertEquals(1, granted.get())
        assertEquals(callers - 1, rejected.get())
        assertEquals("round $round: an answer leaked to a non-owner", 0, unexpected.get())
        assertEquals(emptyList<String>(), failures.map { it::class.java.simpleName })
        assertNull(OffloadPermissionManager.pendingAndroidPermission.value)
    }

    @Test
    fun `a cancelled waiter releases the slot for the next caller`() {
        runBlocking {
            val first = async { OffloadPermissionManager.requestAndroidPermission(listOf(permission)) }
            withTimeout(5_000L) { OffloadPermissionManager.pendingAndroidPermission.first { it != null } }

            first.cancel()
            runCatching { first.await() }

            assertNull(
                "the cancellation handler must release the slot and its dialog",
                OffloadPermissionManager.pendingAndroidPermission.value,
            )

            // Successor on the same slot: claimable, and its own answer lands.
            val second = async { OffloadPermissionManager.requestAndroidPermission(listOf(permission)) }
            withTimeout(5_000L) { OffloadPermissionManager.pendingAndroidPermission.first { it != null } }
            OffloadPermissionManager.respondToAndroidPermission(AndroidPermissionResult.DENIED)

            assertEquals(AndroidPermissionResult.DENIED, second.await())
            assertNull(OffloadPermissionManager.pendingAndroidPermission.value)
        }
    }

    // ── behaviour: the real session maps ─────────────────────────────────────

    @Test
    fun `renameSessionGrants moves the draft verdicts onto the real id`() {
        val draft = "__new__1031-b-draft"
        val real = "1031-b-real"
        val other = "1031-b-other"
        val absent = "1031-b-absent"
        seed(draft, "fastio_rm", "a11y_cli")
        seed(other, "calendar_events", null)
        try {
            OffloadPermissionManager.renameSessionGrants(fromDraft = draft, toReal = real)

            assertEquals(setOf("fastio_rm"), grants()[real])
            assertEquals(setOf("a11y_cli"), denials()[real])
            assertFalse("the draft key must not survive the hop", grants().containsKey(draft))
            assertFalse("the draft key must not survive the hop", denials().containsKey(draft))
            // Sibling sessions are untouched.
            assertEquals(setOf("calendar_events"), grants()[other])

            // rename is a move, not a clear: the verdicts are still there.
            assertNotNull(grants()[real])

            OffloadPermissionManager.clearSessionGrants(real)
            assertFalse("clearSessionGrants still clears", grants().containsKey(real))
            assertFalse("clearSessionGrants still clears", denials().containsKey(real))
            assertEquals(setOf("calendar_events"), grants()[other])

            // A rename with nothing to move must not create an empty entry.
            OffloadPermissionManager.renameSessionGrants(fromDraft = absent, toReal = "1031-b-nothing")
            assertFalse(grants().containsKey("1031-b-nothing"))
            assertFalse(denials().containsKey("1031-b-nothing"))
        } finally {
            listOf(draft, real, other, absent, "1031-b-nothing").forEach {
                grants().remove(it)
                denials().remove(it)
            }
        }
    }

    // ── source shape: one guarded release point, no unguarded install ─────────

    @Test
    fun `every release path goes through the single guarded release point`() {
        val real = source(managerRel)
        assertEquals(emptyList<String>(), slotProtocolViolations(real))

        // Negative controls: each mutation must break the very probe above.
        val unguardedCancel = real.replaceFirst(
            "clearSlotLocked(cont)",
            "if (androidPermissionContinuation === cont) { androidPermissionContinuation = null }",
        )
        assertFalse(
            "a cancellation that clears the slot itself must fail the probe",
            slotProtocolViolations(unguardedCancel).isEmpty(),
        )

        val unreservedClaim = real.replaceFirst("androidPermissionContinuation = SlotClaimed", "")
        assertFalse(
            "a claim that only checks (does not reserve) must fail the probe",
            slotProtocolViolations(unreservedClaim).isEmpty(),
        )

        val suspenderUnderLock = real.replaceFirst(
            "if (androidPermissionContinuation !== SlotClaimed) {",
            "if (androidPermissionContinuation !== SlotClaimed) {\n" +
                "                    suspendCancellableCoroutine<AndroidPermissionResult> { }",
        )
        assertFalse(
            "a suspender inside the lock must fail the probe",
            slotProtocolViolations(suspenderUnderLock).isEmpty(),
        )
    }

    /** Every invariant the [da1031-B] slot protocol must keep; empty == all good. */
    private fun slotProtocolViolations(src: String): List<String> {
        val bad = mutableListOf<String>()
        val clear = bodyOf(src, "private fun clearSlotLocked(")
        if (clear.isEmpty()) {
            bad += "clearSlotLocked is missing"
        } else {
            if (!clear.contains("if (token == null) return null")) bad += "clearSlotLocked lost its null-token guard"
            slotFields.forEach { field ->
                if (!clear.contains("$field === token")) bad += "clearSlotLocked does not guard $field by identity"
            }
            val nulled = slotFields.sumOf { occurrences(src, "$it = null") }
            val nulledInClear = slotFields.sumOf { occurrences(clear, "$it = null") }
            if (nulled != nulledInClear) {
                bad += "a slot is nulled outside clearSlotLocked ($nulled in source vs $nulledInClear in the release point)"
            }
        }

        val claimBlocks = synchronizedBlocks(src).filter { it.contains("claimSlot(") }
        if (claimBlocks.size != 3) bad += "expected 3 claim sites, found ${claimBlocks.size}"
        claimBlocks.forEach {
            if (!it.contains("= SlotClaimed")) bad += "a claim site does not reserve the slot with SlotClaimed"
        }

        slotFields.forEach { field ->
            val installs = occurrences(src, "$field = cont")
            if (installs != 1) bad += "expected exactly one install of $field, found $installs"
            val guarded = synchronizedBlocks(src).any {
                it.contains("if ($field !== SlotClaimed)") && it.contains("$field = cont")
            }
            if (!guarded) bad += "$field is installed outside the guarded critical section"
        }

        if (occurrences(src, "clearSlotLocked(cont)") != 3) {
            bad += "not every invokeOnCancellation releases through clearSlotLocked"
        }
        if (occurrences(src, "clearSlotLocked(slotToken)") != 3) {
            bad += "not every timeout path releases through clearSlotLocked"
        }
        listOf(
            "fun respondToAndroidPermission(",
            "fun respondToSettingsGate(",
            "fun respondToRequest(",
        ).forEach { header ->
            val body = bodyOf(src, header)
            if (body.isEmpty()) {
                bad += "$header is missing"
                return@forEach
            }
            if (!body.contains("clearSlotLocked(token)")) bad += "$header does not release through clearSlotLocked"
            if (!body.contains("=== SlotClaimed")) bad += "$header does not treat a reservation as 'nothing to answer'"
        }

        synchronizedBlocks(src).forEach { block ->
            if (block.contains("suspendCancellableCoroutine")) {
                bad += "suspendCancellableCoroutine sits inside a synchronized block"
            }
        }
        // Call sites only: the import line and the `return@suspendCancellableCoroutine`
        // labels carry the same word, so the type-argument form is the marker.
        if (occurrences(src, "suspendCancellableCoroutine<") != 3) bad += "expected 3 round-trip suspenders"

        return bad
    }

    // ── source shape: the contract that must not move ────────────────────────

    @Test
    fun `the untouched contract of the permission manager is still in place`() {
        val real = source(managerRel)
        assertEquals(emptyList<String>(), contractViolations(real))

        // Negative control: a moved timeout literal must fail the same probe.
        assertFalse(
            "a changed timeout literal must fail the contract probe",
            contractViolations(real.replaceFirst("120_000L", "60_000L")).isEmpty(),
        )

        val chatEntry = source(chatEntryRel)
        val app = source(appRel)
        val viewModel = source(viewModelRel)
        assertEquals(emptyList<String>(), wiringViolations(chatEntry, app, viewModel))
        assertFalse(
            "a missing delete-hook cleanup must fail the wiring probe",
            wiringViolations(
                chatEntry,
                app.replace("OffloadPermissionManager.clearSessionGrants(sid)", ""),
                viewModel,
            ).isEmpty(),
        )
    }

    /** The union of every invariant this fix promises not to move. */
    private fun contractViolations(src: String): List<String> {
        val bad = mutableListOf<String>()
        val literals = listOf(
            "const val SYSTEM_DIALOG_TIMEOUT_MS: Long = 120_000L",
            "const val SETTINGS_GATE_TIMEOUT_MS: Long = 120_000L",
            "const val OFFLOAD_GLOBAL_SESSION_ID = \"offload-global\"",
            "private const val MAX_TRACKED_SESSIONS = 64",
            "private fun claimSlot(current: Any?): Boolean = current == null",
            "_revision.update { it + 1 }",
            "while (sessionGrants.size > MAX_TRACKED_SESSIONS)",
            // The three rejection warnings are user-visible log text.
            "\"requestAndroidPermission(\$permissions) rejected — another system permission \"",
            "\"requestSettingsGate(\${request.id}) rejected — another settings gate is already in flight\"",
            "\"checkPermission(\$toolName) rejected — another permission dialog is already in flight\"",
            // The gate observability line ([phase-3]).
            "\"gate: awaiting user approval tool=\$toolName session=\$sessionId timeout_ms=\$SYSTEM_DIALOG_TIMEOUT_MS\"",
            // The invariants the earlier rounds are anchored on.
            "[audit-0919 F-282]",
            "[audit-0919 F-284]",
            "[T3-M5]",
            "[fix/audit0917-b8]",
            "[fix/da1024-H]",
            "[phase-3]",
            "[fix/audit-s3m3]",
        )
        literals.forEach { if (!src.contains(it)) bad += "missing literal: $it" }
        return bad
    }

    /** The two halves of ISSUE-02 that live outside the manager. */
    private fun wiringViolations(chatEntry: String, app: String, viewModel: String): List<String> {
        val bad = mutableListOf<String>()
        if (!chatEntry.contains("renameSessionGrants(fromDraft = sessionId, toReal = session.id)")) {
            bad += "promoteDraftIfNeeded does not move the draft's permission verdicts"
        }
        if (!chatEntry.contains("renameSessionShortcuts(fromDraft = sessionId, toReal = session.id)")) {
            bad += "the pre-existing shortcut hop disappeared"
        }
        if (!app.contains("OffloadPermissionManager.clearSessionGrants(sid)")) {
            bad += "onSessionDeleted does not clear the deleted session's permission verdicts"
        }
        // The three cleanups that were already on the hook stay.
        listOf(
            "skillRepository.clearSessionOverrides(sid)",
            "mcpRepository.clearSessionOverrides(sid)",
            "SessionBadgeStore.clear(sid)",
        ).forEach { if (!app.contains(it)) bad += "missing pre-existing cleanup: $it" }
        // onCleared()'s own call sites are untouched.
        if (!viewModel.contains("OffloadPermissionManager.clearSessionGrants(activeSessionId)")) {
            bad += "ChatViewModel.onCleared lost its clearSessionGrants call"
        }
        return bad
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun seed(sessionId: String, granted: String?, denied: String?) {
        granted?.let { grants()[sessionId] = ConcurrentHashMap.newKeySet<String>().apply { add(it) } }
        denied?.let { denials()[sessionId] = ConcurrentHashMap.newKeySet<String>().apply { add(it) } }
    }

    private fun grants(): MutableMap<String, MutableSet<String>> = sessionMap("sessionGrants")

    private fun denials(): MutableMap<String, MutableSet<String>> = sessionMap("sessionDenials")

    @Suppress("UNCHECKED_CAST")
    private fun sessionMap(name: String): MutableMap<String, MutableSet<String>> {
        val field = OffloadPermissionManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(OffloadPermissionManager) as MutableMap<String, MutableSet<String>>
    }

    private fun occurrences(src: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = src.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** Every `synchronized(...) { ... }` span, with strings/comments skipped. */
    private fun synchronizedBlocks(src: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = src.indexOf("synchronized(", from)
            if (at < 0) return out
            val open = src.indexOf('{', at)
            if (open < 0) return out
            val end = matchBrace(src, open)
            if (end < 0) return out
            out.add(src.substring(at, end + 1))
            from = end + 1
        }
    }

    /**
     * Index of the `}` closing the `{` at [open], skipping string literals and
     * comments. A naive walker runs off the rails on the first `{` inside a
     * string and then reports nonsense for the rest of the file (1027-E).
     */
    private fun matchBrace(src: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
                '"' -> i = skipString(src, i)
                '/' -> when {
                    i + 1 < src.length && src[i + 1] == '/' -> i = skipLineComment(src, i)
                    i + 1 < src.length && src[i + 1] == '*' -> i = skipBlockComment(src, i)
                }
            }
            i++
        }
        return -1
    }

    private fun skipString(src: String, start: Int): Int {
        if (start + 2 < src.length && src[start + 1] == '"' && src[start + 2] == '"') {
            val end = src.indexOf("\"\"\"", start + 3)
            return if (end < 0) src.length else end + 3
        }
        var i = start + 1
        while (i < src.length) {
            when (src[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return i
    }

    private fun skipLineComment(src: String, start: Int): Int {
        val end = src.indexOf('\n', start)
        return if (end < 0) src.length else end
    }

    private fun skipBlockComment(src: String, start: Int): Int {
        val end = src.indexOf("*/", start + 2)
        return if (end < 0) src.length else end + 2
    }

    /** Braces-balanced body of a function whose declaration contains [header]. */
    private fun bodyOf(src: String, header: String): String {
        val start = src.indexOf(header)
        if (start < 0) return ""
        val open = src.indexOf('{', start)
        if (open < 0) return ""
        val end = matchBrace(src, open)
        return if (end < 0) "" else src.substring(open, end + 1)
    }

    // ── source location (same technique as FastioWiringTest) ─────────────────

    private fun source(rel: String): String = sourceFile(rel).readText()

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }
}
