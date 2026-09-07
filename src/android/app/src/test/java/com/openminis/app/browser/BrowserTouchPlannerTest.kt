package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/browser-filechooser-gesture] Pure CSS→view touch coordinate math.
 * The MotionEvent dispatch itself needs a device, but this mapping decides
 * WHERE the touch lands — a wrong conversion means the synthesized tap hits
 * the wrong element, so the math is pinned here.
 */
class BrowserTouchPlannerTest {

    @Test
    fun `density scale maps css center to view px`() {
        // 412 CSS px viewport laid out at 1133 physical px (density 2.75)
        val t = BrowserTouchPlanner.plan(206.0, 457.5, 412.0, 1133, 2516)!!
        assertEquals(566.5f, t.x, 0.01f)
        assertEquals(1258.125f, t.y, 0.01f)
    }

    @Test
    fun `shrink-to-fit ratio also works`() {
        // Wide page (980 CSS px) shrunk into the same 1133 px view
        val t = BrowserTouchPlanner.plan(490.0, 500.0, 980.0, 1133, 2516)!!
        assertEquals(490.0 * 1133.0 / 980.0, t.x.toDouble(), 0.01)
        assertEquals(500.0 * 1133.0 / 980.0, t.y.toDouble(), 0.01)
    }

    @Test
    fun `point outside view is rejected`() {
        assertNull(BrowserTouchPlanner.plan(500.0, 457.5, 412.0, 1133, 2516)) // cx beyond CSS viewport width
        assertNull(BrowserTouchPlanner.plan(206.0, 990.0, 412.0, 1133, 2516)) // cy beyond visible height
    }

    @Test
    fun `degenerate inputs are rejected`() {
        assertNull(BrowserTouchPlanner.plan(10.0, 10.0, 0.0, 1133, 2516))      // zero CSS viewport
        assertNull(BrowserTouchPlanner.plan(10.0, 10.0, 412.0, 0, 2516))       // zero view width
        assertNull(BrowserTouchPlanner.plan(10.0, 10.0, 412.0, 1133, -1))      // negative view height
        assertNull(BrowserTouchPlanner.plan(Double.NaN, 10.0, 412.0, 1133, 2516))
        assertNull(BrowserTouchPlanner.plan(-1.0, 10.0, 412.0, 1133, 2516))
    }

    @Test
    fun `edge point just inside view is allowed`() {
        assertNotNull(BrowserTouchPlanner.plan(1.0, 1.0, 412.0, 1133, 2516))
    }
}
