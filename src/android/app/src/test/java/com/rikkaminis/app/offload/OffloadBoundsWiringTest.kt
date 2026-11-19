package com.rikkaminis.app.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FULLSCAN-1119 C2 — wiring half of the offload bounds.
 *
 * The pure decisions are pinned in `OffloadBoundsTest`; this file pins the part
 * that lives in Android classes and therefore cannot be unit-tested by calling
 * it: that each bound is actually INSTALLED at the site the finding named, and
 * that the "observable" half is really reached on the failure path.
 *
 * Shape assertions only — they read source text and never run the Android code.
 */
class OffloadBoundsWiringTest {

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

    private fun read(rel: String): String = sourceFile(rel).readText()

    /** Body of the named function, by brace matching from its declaration. */
    private fun functionBody(source: String, signature: String): String {
        val at = source.indexOf(signature)
        assertTrue("missing function: $signature", at >= 0)
        val open = source.indexOf('{', at + signature.length)
        assertTrue("missing body for $signature", open > 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("unbalanced body for $signature")
    }

    // ── F-03: ShizukuBackend.runProcess ──────────────────────────────────

    @Test
    fun `runProcess captures through the bounded buffer, not a bare StringBuilder`() {
        val src = read("src/main/java/com/rikkaminis/app/offload/ShizukuBackend.kt")
        val body = functionBody(src, "fun runProcess(")
        assertTrue("must use BoundedStreamCapture", body.contains("BoundedStreamCapture("))
        assertTrue(
            "no unbounded StringBuilder capture may remain in runProcess",
            !body.contains("synchronized(out)"),
        )
        // The ceiling must come from the existing user-facing knob, not a new number.
        assertTrue(
            "ceiling must come from shellOutputKb",
            body.contains("AgentRuntimeLimitsPrefs.shellOutputKb()"),
        )
        // Observable: the truncation marker has to be folded into the result.
        assertTrue("truncation must be surfaced", body.contains("clippedResult("))
    }

    @Test
    fun `clippedResult reports the clip in stderr and keeps the normal path byte-identical`() {
        val src = read("src/main/java/com/rikkaminis/app/offload/ShizukuBackend.kt")
        val body = functionBody(src, "private fun clippedResult(")
        assertTrue("marker must reach stderr", body.contains("marker != null"))
        assertTrue("must log the clip", body.contains("AppLogger.warning"))
        // The un-clipped path must not gain a trim() that would change output.
        assertTrue(
            "normal path must keep the historical trimEnd only",
            body.contains("if (marker == null && errMarker == null && extraErr == null)"),
        )
    }

    // ── F-09: stream reader ceiling + observability ─────────────────────

    @Test
    fun `stream reader ceiling is the protocol poll window, not a fresh literal`() {
        val src = read("src/main/java/com/rikkaminis/app/sandbox/offload/ChatStreamOffloadHandler.kt")
        assertTrue(
            "reader must be constructed with the derived ceiling",
            src.contains("BoundedLineReader(maxLineBytes = maxStreamLineBytes)"),
        )
        // Derived from the protocol's inline budget, and strictly below the read
        // window: at exactly one window the reader's oversize test can never fire
        // and an over-window line would sit in Partial forever.
        assertTrue(
            "ceiling must derive from MAX_INLINE_BINARY_BYTES",
            src.contains("PayloadSizePolicy.base64Size(PayloadSizePolicy.MAX_INLINE_BINARY_BYTES)"),
        )
        assertTrue(
            "ceiling must not equal the read window",
            !src.contains("maxStreamLineBytes: Int = PayloadSizePolicy.MAX_POLL_READ_BYTES"),
        )
    }

    @Test
    fun `both oversized-line branches report through the policy`() {
        val src = read("src/main/java/com/rikkaminis/app/sandbox/offload/ChatStreamOffloadHandler.kt")
        // The in-window branch (Lines.oversizedLines) used to be ignored entirely.
        assertTrue(
            "oversizedLines must be reported",
            src.contains("for (dropped in result.oversizedLines)") &&
                src.contains("reportDroppedFrame(streamFile, dropped, emittedChunks)"),
        )
        // The single-line branch must not fall back to a bare debug Log.w.
        assertTrue(
            "single oversized line must use reportDroppedFrame",
            src.contains("reportDroppedFrame(streamFile, result, emittedChunks)"),
        )
        val body = functionBody(src, "private fun reportDroppedFrame(")
        assertTrue("must log loudly", body.contains("Log.e("))
        assertTrue("must reach the app log", body.contains("AppLogger.warning("))
        assertTrue("must be able to fail the stream", body.contains("StreamOversizePolicy.decide("))
    }

    // ── F-15: model-use image read ───────────────────────────────────────

    @Test
    fun `image resolution reads through the bounded helper and fails loudly`() {
        val src = read("src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt")
        assertTrue(
            "must read through BoundedFileRead",
            src.contains("BoundedFileRead.readBytes(hostFile)"),
        )
        assertTrue(
            "no unbounded readBytes may remain on the image path",
            !src.contains("hostFile.readBytes()"),
        )
        assertTrue(
            "oversize must be an explicit image input error",
            src.contains("Outcome.TooLarge -> throw ImageInputError("),
        )
    }

    // ── F-22 / F-16: browser ────────────────────────────────────────────

    @Test
    fun `minis interceptor bounds the preview and answers with a readable page`() {
        val src = read("src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt")
        assertTrue(
            "preview must be size-checked",
            src.contains("MinisPreviewPolicy.fitsPreview(previewBytes, previewCap)"),
        )
        assertTrue(
            "cap must be the protocol single-file ceiling",
            src.contains("PayloadSizePolicy.MAX_FILE_BYTES"),
        )
        assertTrue(
            "oversize must return a readable page, not a blank one",
            src.contains("\"Payload Too Large\""),
        )
        assertTrue(
            "viewport check must not decode the file first",
            src.contains("MinisPreviewPolicy.declaresViewport(html)"),
        )
    }

    @Test
    fun `onCreateWindow return value and result message cannot disagree`() {
        val src = read("src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt")
        val body = functionBody(src, "override fun onCreateWindow(")
        assertTrue(
            "must decide through the policy",
            body.contains("PopupWindowPolicy.decide("),
        )
        assertTrue(
            "a refusal must answer the message",
            body.contains("declinePopup(resultMsg, transport, hasHandler = true)"),
        )
        assertTrue(
            "no handler must also decline (not claim)",
            body.contains("declinePopup(resultMsg, transport = resultMsg.obj as? WebView.WebViewTransport, hasHandler = false)"),
        )
        // The pre-fix shape: invoke + `return onNewWindow != null`.
        assertTrue(
            "the pre-fix contradiction must be gone",
            !body.contains("return onNewWindow != null"),
        )
        val decline = functionBody(src, "private fun declinePopup(")
        assertTrue("decline must null the transport", decline.contains("webView = null"))
        assertTrue("decline must send the message", decline.contains("sendToTarget()"))
    }

    @Test
    fun `pax payload is read as bytes, not as a decoded string`() {
        val src = read("src/main/java/com/rikkaminis/app/sandbox/offload/FastioTar.kt")
        assertTrue(
            "pax must be applied from raw bytes",
            src.contains("applyPax(readRaw(member.size))"),
        )
        assertTrue(
            "pax parser must take a ByteArray",
            src.contains("private fun applyPax(payload: ByteArray)"),
        )
        assertEquals(
            "no char-indexed pax parser may remain",
            false,
            src.contains("private fun applyPax(text: String)"),
        )
    }
}
