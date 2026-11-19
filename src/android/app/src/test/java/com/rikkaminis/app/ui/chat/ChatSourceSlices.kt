package com.rikkaminis.app.ui.chat

import java.io.File

/**
 * Shared source-slice helper for the chat-domain wiring tests.
 *
 * The production files hold hundreds of Android/Compose dependencies, so the
 * behaviour-free placement contracts (which call, in what order, inside which
 * function body) are asserted on the raw source text. Sequence assertions must
 * never span two functions — [function] cuts at the next declaration.
 */
internal object ChatSourceSlices {

    fun source(fileName: String): String =
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/$fileName")

    /** Any repo-relative source file (e.g. the agent-loop engine, conversation pkg). */
    fun anySource(relativePath: String): String = readRepoFile(relativePath)

    /** The body of ONE function, from its declaration to the next member. */
    fun function(fileName: String, marker: String): String {
        val text = source(fileName)
        val start = text.indexOf(marker)
        check(start >= 0) { "$fileName: declaration not found: $marker" }
        val rest = text.substring(start + marker.length)
        val cut = listOf(
            "\ninternal fun ", "\nfun ", "\n    fun ", "\n    internal fun ",
            "\n    private fun ", "\n    suspend fun ",
        ).map { rest.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, cut)
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir, so a single-level
     * parent lookup is not enough — CI caught this shape.
     */
    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
