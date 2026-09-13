package com.rikkaminis.app.ui.markdown

/**
 * [fix/memory-hardening-capture-cap] Upper bound for a single WebView→Bitmap
 * capture whose size is derived from CONTENT (the WebView reports the laid-out
 * box in CSS px; the capture multiplies by density to get physical px).
 *
 * ## Why this exists
 *
 * `Bitmap.createBitmap(w, h, ARGB_8888)` allocates `w * h * 4` bytes on the
 * **native** heap in one shot. When `w`/`h` come from rendered content there is
 * no natural ceiling: a pathological LaTeX formula — or ordinary prose carrying
 * two `$` signs, which the inline-math scanner legitimately reads as a formula
 * — can make KaTeX lay out a box thousands of px tall, and the capture then
 * asks for hundreds of MB (or more) in one allocation. That is a direct Scudo
 * abort, and it is the exact shape of the 2026-09-13 transient native spike
 * (native heap 38MB → 1141MB in ~4s, freed again seconds later).
 *
 * The browser path already learned this lesson and caps its own full-page
 * capture (`BrowserUseManager.MAX_FULL_PAGE_HEIGHT_PX`, whose comment spells
 * out the same `32768 × ~1130 px × 4B ≈ 144 MB` arithmetic). Both KaTeX capture
 * sites — display-mode in `KaTeXView` and inline in `KatexWebViewPool` — went
 * without any cap; this file is the shared guard for them.
 *
 * ## Fit, don't refuse
 *
 * The requested rectangle is scaled down (same aspect ratio) until it fits
 * under BOTH caps, instead of failing. The bitmap is drawn into the same
 * on-screen box either way (both call sites size their layout from the CSS
 * dimensions, not from the bitmap), so the only visible effect for a formula
 * past the cap is slightly softer glyphs — strictly better than the monospace
 * fallback an error would produce.
 */

/** Max physical px on either edge of a captured bitmap (8192² ARGB ≈ 256 MB). */
internal const val CAPTURE_MAX_EDGE_PX = 8192

/** Max bytes for one captured ARGB_8888 bitmap (64 MB). */
internal const val CAPTURE_MAX_BYTES = 64L * 1024L * 1024L

/**
 * Result of fitting a requested capture rectangle under the caps.
 * [scale] is the factor to draw with (`canvas.scale(scale, scale)`) so the full
 * source content still lands inside the smaller bitmap; `1.0` means the
 * request passed through untouched. [width]/[height] are ≥ 1 whenever
 * [ok] is true.
 */
internal data class CaptureFit(
    val width: Int,
    val height: Int,
    val scale: Float,
    val ok: Boolean,
)

/**
 * Fit a requested ARGB_8888 capture rectangle under [maxEdgePx] / [maxBytes].
 *
 * Returns `ok = false` only for a degenerate request (non-positive edge), which
 * callers treat like a failed render. Otherwise the result always satisfies
 * `width ≤ maxEdgePx`, `height ≤ maxEdgePx` and `width * height * 4 ≤ maxBytes`.
 * Pure — JVM-testable.
 */
internal fun internalFitCaptureSize(
    widthPx: Int,
    heightPx: Int,
    maxEdgePx: Int = CAPTURE_MAX_EDGE_PX,
    maxBytes: Long = CAPTURE_MAX_BYTES,
): CaptureFit {
    if (widthPx <= 0 || heightPx <= 0) return CaptureFit(0, 0, 0f, ok = false)
    val bytes = widthPx.toLong() * heightPx.toLong() * 4L
    val edge = maxOf(widthPx, heightPx)
    if (edge <= maxEdgePx && bytes <= maxBytes) {
        return CaptureFit(widthPx, heightPx, 1f, ok = true)
    }
    val edgeScale = maxEdgePx.toDouble() / edge.toDouble()
    val byteScale = if (bytes > maxBytes) {
        kotlin.math.sqrt(maxBytes.toDouble() / bytes.toDouble())
    } else {
        1.0
    }
    val scale = minOf(edgeScale, byteScale, 1.0)
    val w = (widthPx * scale).toInt().coerceAtLeast(1)
    val h = (heightPx * scale).toInt().coerceAtLeast(1)
    return CaptureFit(w, h, (w.toDouble() / widthPx.toDouble()).toFloat(), ok = true)
}
