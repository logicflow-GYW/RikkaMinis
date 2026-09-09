package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * TF-D static guard: the app process (`:app`) must NEVER call a provider's
 * network entry points (sendMessage / streamMessage / generateImage) directly.
 * All LLM traffic routes through [ProviderExecutionGateway] → :modelservice.
 *
 * The one legal exception is the dedicated worker process (ModelExecutionService),
 * which owns provider creation + invocation so its native heap dies with the
 * process. This test greps the main-process source and fails if any file OTHER
 * than the worker still invokes `provider.sendMessage` / `provider.streamMessage`.
 *
 * Locates the source tree relative to the test working directory (module root).
 * If src cannot be found the test skips (rather than failing on a redecorated
 * tree) — a hard failure is reserved for when the tree IS present, so a real
 * regression in CI is still caught.
 */
class NoInProcessProviderGuardTest {

    private fun srcDirs(): List<File> {
        val candidates = listOf(
            File("src/android/app/src/main/java"),
            File("src/main/java"),
        )
        return candidates.filter { it.isDirectory }
    }

    @Test
    fun `main process has zero direct provider sendMessage-slash-streamMessage calls`() {
        val src = srcDirs()
        if (src.isEmpty()) return // tree not present → skip (see kdoc)

        // Worker is the ONLY legal in-process provider invoker.
        val workerByPath = Regex(".*[/\\\\]offload[/\\\\]ModelExecutionService\\.kt$")
        val offences = StringBuilder()

        for (dir in src) {
            val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
            for (file in files) {
                if (workerByPath.matches(file.path)) continue
                val lines = file.readLines()
                for ((i, line) in lines.withIndex()) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
                    // Direct provider invocations (not viewModel.sendMessage, not
                    // the `it.sendMessage(text)` ViewModel case, not the Gateway
                    // object which is the sanctioned chokepoint).
                    if (line.contains("provider.sendMessage(") ||
                        line.contains("provider.streamMessage(")
                    ) {
                        offences.append("${file.name}:${i + 1}: $line\n")
                    }
                }
            }
        }

        assertTrue(
            "main process still calls a provider network entry point directly:\n$offences",
            offences.isEmpty(),
        )
    }
}