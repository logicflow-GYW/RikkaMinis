package com.rikkaminis.app.service

import android.content.ComponentCallbacks2
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM unit tests for [TrimPolicy] — the fix for trim memory semantics.
 * 2026-08-20 real-device evidence: the old `level >= RUNNING_CRITICAL(15)`
 * numeric comparison routed every background/UI-hidden event (20/40/60/80)
 * into the "foreground & critical" branch, destroying WebViews and forcing
 * GC on every app-background switch.
 */
class TrimPolicyTest {

    // Android trim level constants (for test readability)
    private val RUNNING_MODERATE = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE  // 5
    private val RUNNING_LOW = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW            // 10
    private val RUNNING_CRITICAL = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL  // 15
    private val UI_HIDDEN = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN                // 20
    private val BACKGROUND = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND              // 40
    private val MODERATE = ComponentCallbacks2.TRIM_MEMORY_MODERATE                  // 60
    private val COMPLETE = ComponentCallbacks2.TRIM_MEMORY_COMPLETE                  // 80

    @Test
    fun `phase classification`() {
        assertEquals(TrimPhase.RUNNING_LIGHT, TrimPolicy.phase(RUNNING_MODERATE))
        assertEquals(TrimPhase.RUNNING_LIGHT, TrimPolicy.phase(RUNNING_LOW))
        assertEquals(TrimPhase.RUNNING_HEAVY, TrimPolicy.phase(RUNNING_CRITICAL))
        assertEquals(TrimPhase.BACKGROUND_LIGHT, TrimPolicy.phase(UI_HIDDEN))
        assertEquals(TrimPhase.BACKGROUND_LIGHT, TrimPolicy.phase(BACKGROUND))
        assertEquals(TrimPhase.BACKGROUND_HEAVY, TrimPolicy.phase(MODERATE))
        assertEquals(TrimPhase.BACKGROUND_HEAVY, TrimPolicy.phase(COMPLETE))
    }

    @Test
    fun `isForegroundPressure returns true only for 5-10-15`() {
        assertTrue(TrimPolicy.isForegroundPressure(RUNNING_MODERATE))
        assertTrue(TrimPolicy.isForegroundPressure(RUNNING_LOW))
        assertTrue(TrimPolicy.isForegroundPressure(RUNNING_CRITICAL))
        assertFalse(TrimPolicy.isForegroundPressure(UI_HIDDEN))
        assertFalse(TrimPolicy.isForegroundPressure(BACKGROUND))
        assertFalse(TrimPolicy.isForegroundPressure(MODERATE))
        assertFalse(TrimPolicy.isForegroundPressure(COMPLETE))
    }

    @Test
    fun `isBackground returns true for 20+`() {
        assertFalse(TrimPolicy.isBackground(RUNNING_MODERATE))
        assertFalse(TrimPolicy.isBackground(RUNNING_LOW))
        assertFalse(TrimPolicy.isBackground(RUNNING_CRITICAL))
        assertTrue(TrimPolicy.isBackground(UI_HIDDEN))
        assertTrue(TrimPolicy.isBackground(BACKGROUND))
        assertTrue(TrimPolicy.isBackground(MODERATE))
        assertTrue(TrimPolicy.isBackground(COMPLETE))
    }

    @Test
    fun `shouldReclaimShellsAndGc true only for foreground pressure`() {
        // Foreground pressure (5/10/15): yes
        assertTrue(TrimPolicy.shouldReclaimShellsAndGc(RUNNING_MODERATE))
        assertTrue(TrimPolicy.shouldReclaimShellsAndGc(RUNNING_LOW))
        assertTrue(TrimPolicy.shouldReclaimShellsAndGc(RUNNING_CRITICAL))
        // Background (20+): no — don't destroy shells on mere background switch
        assertFalse(TrimPolicy.shouldReclaimShellsAndGc(UI_HIDDEN))
        assertFalse(TrimPolicy.shouldReclaimShellsAndGc(BACKGROUND))
        assertFalse(TrimPolicy.shouldReclaimShellsAndGc(MODERATE))
        assertFalse(TrimPolicy.shouldReclaimShellsAndGc(COMPLETE))
    }

    @Test
    fun `shouldEngageMemoryGate true only for RUNNING_CRITICAL`() {
        assertFalse(TrimPolicy.shouldEngageMemoryGate(RUNNING_MODERATE))
        assertFalse(TrimPolicy.shouldEngageMemoryGate(RUNNING_LOW))
        assertTrue(TrimPolicy.shouldEngageMemoryGate(RUNNING_CRITICAL))  // 15
        assertFalse(TrimPolicy.shouldEngageMemoryGate(UI_HIDDEN))        // 20 — old bug: >= 15 routed this here
        assertFalse(TrimPolicy.shouldEngageMemoryGate(BACKGROUND))
        assertFalse(TrimPolicy.shouldEngageMemoryGate(MODERATE))
        assertFalse(TrimPolicy.shouldEngageMemoryGate(COMPLETE))
    }

    @Test
    fun `browserTabKillPolicy RUNNING_CRITICAL drops all but selected`() {
        assertEquals(
            TrimPolicy.BrowserTabKillPolicy.DROP_ALL_BUT_SELECTED,
            TrimPolicy.browserTabKillPolicy(RUNNING_CRITICAL)
        )
    }

    @Test
    fun `browserTabKillPolicy RUNNING_LIGHT drops all idle`() {
        assertEquals(
            TrimPolicy.BrowserTabKillPolicy.DROP_ALL_IDLE,
            TrimPolicy.browserTabKillPolicy(RUNNING_MODERATE)
        )
        assertEquals(
            TrimPolicy.BrowserTabKillPolicy.DROP_ALL_IDLE,
            TrimPolicy.browserTabKillPolicy(RUNNING_LOW)
        )
    }

    @Test
    fun `browserTabKillPolicy BACKGROUND_LIGHT drops long-idle only`() {
        // Key fix: UI_HIDDEN(20) and BACKGROUND(40) should be conservative,
        // not aggressive like the old `level >= RUNNING_LOW(10)` branch.
        assertEquals(
            TrimPolicy.BrowserTabKillPolicy.DROP_LONG_IDLE_ONLY,
            TrimPolicy.browserTabKillPolicy(UI_HIDDEN)
        )
        assertEquals(
            TrimPolicy.BrowserTabKillPolicy.DROP_LONG_IDLE_ONLY,
            TrimPolicy.browserTabKillPolicy(BACKGROUND)
        )
    }

    @Test
    fun `browserTabKillPolicy BACKGROUND_HEAVY drops all idle`() {
        assertEquals(
            TrimPolicy.BrowserTabKillPolicy.DROP_ALL_IDLE,
            TrimPolicy.browserTabKillPolicy(MODERATE)
        )
        assertEquals(
            TrimPolicy.BrowserTabKillPolicy.DROP_ALL_IDLE,
            TrimPolicy.browserTabKillPolicy(COMPLETE)
        )
    }
}
