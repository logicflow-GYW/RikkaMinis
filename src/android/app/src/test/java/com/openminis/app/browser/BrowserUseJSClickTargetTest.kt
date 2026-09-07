package com.openminis.app.browser

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/browser-filechooser-gesture] The clickTargetInfo probe decides
 * whether a click becomes a real touch (file inputs) or stays a JS click
 * (everything else). BrowserUseJS is pure Kotlin, so the generated JS is
 * assertable in the JVM closure; the WebView-side dispatch is device-only.
 */
class BrowserUseJSClickTargetTest {

    @Test
    fun `selector form queries with escaped selector`() {
        val js = BrowserUseJS.clickTargetInfo("input[name='photo']", null, null)
        assertTrue(js.contains("document.querySelector('input[name=\\'photo\\']')"))
        assertTrue(js.contains("isFile"))
    }

    @Test
    fun `coordinate form uses elementFromPoint`() {
        val js = BrowserUseJS.clickTargetInfo(null, 120, 340)
        assertTrue(js.contains("document.elementFromPoint(120, 340)"))
    }

    @Test
    fun `probe carries file detection, label resolution and viewport math`() {
        val js = BrowserUseJS.clickTargetInfo("#f", null, null)
        // file-input detection
        assertTrue(js.contains("getAttribute('type')"))
        assertTrue(js.contains("=== 'file'"))
        // label[for] association
        assertTrue(js.contains("label[for="))
        assertTrue(js.contains("closest('label')"))
        // visibility / hittability guard
        assertTrue(js.contains("getClientRects()"))
        // scroll + measure + viewport width for CSS→view scale
        assertTrue(js.contains("scrollIntoView"))
        assertTrue(js.contains("visualViewport"))
        assertTrue(js.contains("window.innerWidth"))
        // accept passthrough for the result text
        assertTrue(js.contains("getAttribute('accept')"))
    }

    @Test
    fun `probe never interpolates a raw selector`() {
        // A malicious / quote-heavy selector must be escaped, not spliced.
        val nasty = "x'); document.title='pwned'; ("
        val js = BrowserUseJS.clickTargetInfo(nasty, null, null)
        assertTrue(!js.contains("document.title='pwned'"))
    }
}
