package com.rikkaminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * The display-copy overwrite refusal is one call away from the pure decision
 * ([com.rikkaminis.app.data.EnvVarRedactor.wouldEraseCredential], covered by
 * SecretRedactionRegressionTest). A pure decision nothing calls protects
 * nothing, so the wiring is pinned here by source shape — same idiom as
 * CompactSplitCarryWiringTest. Comments are stripped so the rationale blocks
 * cannot satisfy the assertions.
 */
class PrivacyWriteGuardWiringTest {

    @Test
    fun fileWriteChecksTheGuardBeforeItWrites() {
        val code = code("app/src/main/java/com/rikkaminis/app/tools/FileWriteTool.kt")
        val guard = code.indexOf("EnvVarRedactor.wouldEraseCredential(guardText, effectiveContent)")
        val write = code.indexOf("AtomicFileWrite.write(")
        assertTrue("file_write no longer consults the credential-erase guard", guard >= 0)
        assertTrue("the guard must run before the file is replaced", write > guard)
        assertTrue(
            "the guard read must stay bounded",
            "readAtMost(input, PLACEHOLDER_GUARD_SCAN_LIMIT)" in code,
        )
    }

    @Test
    fun fileEditChecksTheGuardBeforeItWrites() {
        val code = code("app/src/main/java/com/rikkaminis/app/tools/FileEditTool.kt")
        val guard = code.indexOf("EnvVarRedactor.wouldEraseCredential(oldString, newString)")
        val write = code.indexOf("AtomicFileWrite.write(")
        assertTrue("file_edit no longer consults the credential-erase guard", guard >= 0)
        assertTrue("the guard must run before the file is replaced", write > guard)
        assertTrue(
            "the placeholder-in-old_string explanation must survive",
            "is a privacy-mode display" in code,
        )
    }

    private fun code(relative: String): String = stripComments(readRepoFile(relative))

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i += 1 }
                inString -> {
                    sb.append(c)
                    if (c == '\\') { sb.append(next); i += 1 } else if (c == '"') inString = false
                }
                else -> when {
                    c == '"' -> { inString = true; sb.append(c) }
                    c == '/' && next == '/' -> { inLine = true; i += 1 }
                    c == '/' && next == '*' -> { inBlock = true; i += 1 }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
