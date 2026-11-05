package com.rikkaminis.app.provider

/**
 * Tool-call input logging cadence shared by OpenAIProvider and AnthropicProvider.
 *
 * The debug "→ ToolInputDelta" line fires on the first delta of a tool input
 * and once per this many characters of growth, per fragment. This keeps the
 * debug log readable for very large tool payloads (100KB+) without one line
 * per chunk — and keeps the two providers' sampling density identical, so a
 * cross-provider comparison in the log isn't silently skewed by one side
 * having been tuned and the other not.
 */
const val TOOL_LOG_INPUT_STEP_CHARS = 2048
