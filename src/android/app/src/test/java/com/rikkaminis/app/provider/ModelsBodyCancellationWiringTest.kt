package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Each fetcher must consume AND parse inside the owning cancellation overload. */
class ModelsBodyCancellationWiringTest {
    @Test fun openAIConsumesInsideWatcher() = checkFetcher("openai/OpenAIModelsApi.kt", "JSONObject(body)")
    @Test fun anthropicConsumesInsideWatcher() = checkFetcher("anthropic/AnthropicModelsApi.kt", "parseAndCache(body,")
    @Test fun geminiConsumesInsideWatcher() = checkFetcher("gemini/GeminiModelsApi.kt", "JSONObject(body)")
    @Test fun openRouterConsumesInsideWatcher() = checkFetcher("openrouter/OpenRouterModelsApi.kt", "JSONObject(body)")

    private fun checkFetcher(relative: String, parse: String) {
        val path = "src/main/java/com/rikkaminis/app/provider/$relative"
        val roots = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        val file = roots.flatMap { dir -> sequenceOf(
            File(dir, path), File(dir, "src/android/app/$path"), File(dir, "app/$path")
        ) }.firstOrNull { it.isFile } ?: error("Cannot find $relative")
        val source = file.readLines().filterNot {
            val line = it.trimStart()
            line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
        }.joinToString("\n")
        val anchor = ".executeOrCancel() { response ->\n"
        val start = source.indexOf(anchor)
        assertTrue("$relative must use the response-owning overload", start >= 0)
        val end = source.indexOf("\n            }", start + anchor.length)
        assertTrue("$relative owning consumer must have a closing boundary", end > start)
        val consumer = source.substring(start + anchor.length, end)
        val read = "response.body?.string()"
        assertEquals("$relative must have one body-read owner", 1, source.split(read).size - 1)
        assertTrue("$relative body read escaped the watcher", read in consumer)
        assertTrue("$relative parsing escaped the watcher", parse in consumer)
        assertTrue("$relative must propagate cancellation before ordinary fallback",
            Regex("catch \\(e: (?:kotlinx\\.coroutines\\.)?CancellationException\\) \\{\\s*throw e").containsMatchIn(source))
    }
}
