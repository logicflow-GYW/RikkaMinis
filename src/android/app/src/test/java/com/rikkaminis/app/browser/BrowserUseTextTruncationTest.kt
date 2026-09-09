package com.rikkaminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUseTextTruncationTest {

    @Test
    fun getTextUsesSafeCapAndReportsTruncationMetadata() {
        val source = BrowserUseJS.getText(null)
        assertTrue(source.contains("substring(0, ${BrowserUseJS.MAX_GET_TEXT_CHARS})"))
        assertTrue(source.contains("fullLength: innerTextVal.length"))
        assertTrue(source.contains("truncated: text.length < innerTextVal.length"))
        assertEquals(900 * 1024, BrowserUseJS.MAX_GET_TEXT_CHARS)
    }

    @Test
    fun resultCarriesOriginalLengthOnlyWhenTextWasTruncated() {
        val fullText = "x".repeat(1_250_000)
        val returnedText = fullText.substring(0, BrowserUseJS.MAX_GET_TEXT_CHARS)
        val truncated = returnedText.length < fullText.length
        assertEquals(BrowserUseJS.MAX_GET_TEXT_CHARS, returnedText.length)
        assertTrue(truncated)

        val shortText = "x".repeat(10_001)
        val shortResult = BrowserActionResult(
            text = shortText,
            truncated = false,
            fullTextLength = shortText.length,
        )
        assertFalse(shortResult.truncated)
        assertEquals(shortText.length, shortResult.fullTextLength)
    }

    @Test
    fun largeResultMetadataDoesNotChangeTextPayload() {
        val text = "x".repeat(BrowserUseJS.MAX_GET_TEXT_CHARS)
        val result = BrowserActionResult(text = text, truncated = true, fullTextLength = 1_250_000)
        assertEquals(BrowserUseJS.MAX_GET_TEXT_CHARS, result.text.length)
        assertEquals(1_250_000, result.fullTextLength)
    }
}
