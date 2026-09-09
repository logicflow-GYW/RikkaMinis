package com.rikkaminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.Color
import com.rikkaminis.app.ui.theme.ToolAccents

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers have been moved to ChatFormattingUtils.kt for JVM testability.
// This file retains only the Compose-dependent helpers.

// Helper: tool accent color (single source: ToolAccents)
internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "shell_execute" -> ToolAccents.shell
    "file_read" -> ToolAccents.fileRead
    "file_write" -> ToolAccents.fileWrite
    "file_edit" -> ToolAccents.fileEdit
    "browser_use" -> ToolAccents.browser
    "read_image" -> ToolAccents.image
    "memory_write", "memory_get" -> ToolAccents.memory
    "web_search" -> ToolAccents.search    // iOS: .cyan for search
    else -> ToolAccents.fallback
}

// Helper: tool icon (iOS: distinct SF Symbols per tool type)
internal fun toolIconFor(toolName: String) = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description         // iOS: doc.text
    "file_write" -> Icons.AutoMirrored.Filled.NoteAdd   // iOS: doc.text.fill (filled variant)
    "file_edit" -> Icons.Default.EditNote             // iOS: square.and.pencil
    "browser_use" -> Icons.Default.Language            // iOS: globe
    "read_image" -> Icons.Default.Image                // iOS: photo
    "memory_write", "memory_get" -> Icons.Default.Psychology // iOS: brain.head.profile
    "web_search" -> Icons.Default.Search               // iOS: magnifyingglass
    else -> Icons.Default.Build
}