package com.rikkaminis.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic accent colors for data-classified UI: tool-type accents, session
 * category icons, and provider brand dots.
 *
 * These are NOT part of [ChatPalette] (which covers app chrome: background,
 * text, bubbles, borders, etc.) and NOT part of Material [androidx.compose.material3.ColorScheme]
 * (which covers the settings/component surfaces). They describe *what an item
 * is* — which tool, which category, which provider — so their meaning is data,
 * not styling. Centralising them here is the single authority that keeps the
 * same tool/category/provider the same colour everywhere it appears.
 *
 * Before FE-1 this was three near-identical hand-copied `when` tables spread
 * across ChatToolFormatting / SessionsShared+MoveToSessionSheet /
 * ChatModelPickerSheet+ModelEntryPicker. Keeping them in sync by hand is how
 * the two `categoryStyle` copies (SessionsShared vs MoveToSessionSheet) and
 * two `providerDotColor` copies drifted into existence.
 */

/**
 * Accent colour for a tool pill / duration / status, mirroring the iOS
 * AIChatView tool-type tints (SystemGreen/SystemBlue/SystemOrange/SystemPink…).
 */
object ToolAccents {
    val shell = Color(0xFF34C759)
    val fileRead = Color(0xFF32ADE6)
    val fileWrite = Color(0xFF007AFF)
    val fileEdit = Color(0xFFFF9500)
    val browser = Color(0xFF007AFF)
    val image = Color(0xFFAF52DE)
    val memory = Color(0xFFFF2D55)
    val search = Color(0xFF32ADE6) // iOS: .cyan for search
    val fallback = Color(0xFF8E8E93)
}

/**
 * Session category icon tint. Mirrors the iOS 16-category table
 * (ContentView.swift) — same RGB in the session list and the "Move to…" sheet.
 */
object CategoryAccents {
    val code = Color(0xFFF09A37)
    val writing = Color(0xFF3478F6)
    val research = Color(0xFF30B0C7)
    val analysis = Color(0xFF5856D6)
    val creative = Color(0xFFFF2D55)
    val chat = Color(0xFF34C759)
    val math = Color(0xFF9B59B6)
    val translation = Color(0xFF00BCD4)
    val health = Color(0xFFFF3B30)
    val finance = Color(0xFF00C7BE)
    val travel = Color(0xFFF09A37)
    val education = Color(0xFF3478F6)
    val design = Color(0xFFFF2D55)
    val productivity = Color(0xFFFFCC00)
    val support = Color(0xFF8B6914)
    val other = Color(0xFF8E8E93)
    val fallback = Color(0xFF8E8E93)
}

/**
 * Provider brand dot. Same colour across the model picker, model-group sheets,
 * and the agent-loop sheets so the cue stays consistent (see ModelEntryPicker).
 */
object ProviderAccents {
    val anthropic = Color(0xFFAB47BC) // purple
    val gemini = Color(0xFF42A5F5)    // blue
    val openAI = Color(0xFF4CAF50)    // green
    val openRouter = Color(0xFF00BCD4) // cyan
    val xAI = Color(0xFFFF7043)        // orange — Grok brand
    val kimiCode = Color(0xFF5C6BC0)   // indigo — Kimi accent
    val fallback = Color(0xFF8E8E93)   // gray
}
