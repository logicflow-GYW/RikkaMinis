package com.openminis.app.browser

/**
 * [fix/browser-filechooser-gesture] Pure CSS→view touch coordinate math for
 * real-touch clicks on file inputs. Extracted from BrowserUseManager so it
 * is JVM-testable (the MotionEvent dispatch itself is not).
 *
 * The WebView renders a CSS-viewport page at
 * `webView.width px / visualViewport.width CSS px` physical pixels per CSS
 * pixel — that single ratio covers density scaling, setInitialScale
 * shrink-to-fit and set_viewport overrides alike, so measuring both sides
 * at click time is the only conversion that stays correct in all of them.
 */
object BrowserTouchPlanner {

    data class TouchPoint(val x: Float, val y: Float)

    /**
     * Map a CSS-viewport element center to WebView-local touch coordinates.
     * Returns null when the inputs are unusable or the point would land
     * outside the view — an outside touch would hit some other element, so
     * failing loudly (and telling the agent to scroll) beats mis-tapping.
     */
    fun plan(
        cssX: Double,
        cssY: Double,
        cssViewportWidth: Double,
        viewWidthPx: Int,
        viewHeightPx: Int,
    ): TouchPoint? {
        if (cssViewportWidth <= 0.0 || viewWidthPx <= 0 || viewHeightPx <= 0) return null
        if (cssX.isNaN() || cssY.isNaN() || cssX < 0.0 || cssY < 0.0) return null
        val scale = viewWidthPx / cssViewportWidth
        val x = (cssX * scale).toFloat()
        val y = (cssY * scale).toFloat()
        if (x < 1f || y < 1f || x > viewWidthPx - 1f || y > viewHeightPx - 1f) return null
        return TouchPoint(x, y)
    }
}
