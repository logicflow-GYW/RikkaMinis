package com.rikkaminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRiskControlTest {

    @Test
    fun searchHostsUseMoreConservativeThrottle() {
        val normalDelay = BrowserRiskControl.baseThrottleDelayMs(
            action = BrowserAction.NAVIGATE,
            rawUrl = "https://example.com/search?q=agent"
        )
        val searchDelay = BrowserRiskControl.baseThrottleDelayMs(
            action = BrowserAction.NAVIGATE,
            rawUrl = "https://www.google.com/search?q=agent"
        )

        assertTrue("search host should have higher delay", searchDelay > normalDelay)
        assertTrue(
            "bing.com should be recognized as search host",
            BrowserRiskControl.isSearchHost("https://www.bing.com/search?q=agent")
        )
        assertFalse(
            "example.com should NOT be recognized as search host",
            BrowserRiskControl.isSearchHost("https://example.com/search?q=agent")
        )
    }

    @Test
    fun throttleDelayAddsDeficitAndJitter() {
        assertEquals(
            "deficit = 550 - 100 = 450, plus 50 jitter = 500",
            500L,
            BrowserRiskControl.computeThrottleDelayMs(
                baseDelayMs = 550L,
                elapsedSinceLastActionMs = 100L,
                jitterMs = 50L
            )
        )
        assertEquals(
            "elapsed 1000ms > base 550ms → no deficit, only jitter",
            50L,
            BrowserRiskControl.computeThrottleDelayMs(
                baseDelayMs = 550L,
                elapsedSinceLastActionMs = 1000L,
                jitterMs = 50L
            )
        )
        assertEquals(
            "null elapsed → full deficit = base = 550ms",
            550L,
            BrowserRiskControl.computeThrottleDelayMs(
                baseDelayMs = 550L,
                elapsedSinceLastActionMs = null,
                jitterMs = 0L
            )
        )
        assertEquals(
            "0 base delay → 0 result",
            0L,
            BrowserRiskControl.computeThrottleDelayMs(
                baseDelayMs = 0L,
                elapsedSinceLastActionMs = 100L,
                jitterMs = 50L
            )
        )
    }

    @Test
    fun shouldThrottleReturnsTrueForVisualChangeActions() {
        assertTrue("navigate should be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.NAVIGATE))
        assertTrue("click should be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.CLICK))
        assertTrue("type should be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.TYPE))
        assertTrue(
            "scroll_and_collect should be throttled",
            BrowserRiskControl.shouldThrottle(BrowserAction.SCROLL_AND_COLLECT)
        )
    }

    @Test
    fun shouldThrottleReturnsFalseForReadOnlyActions() {
        assertFalse("screenshot should NOT be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.SCREENSHOT))
        assertFalse("get_text should NOT be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.GET_TEXT))
        assertFalse("scroll should NOT be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.SCROLL))
        assertFalse("execute_js should NOT be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.EXECUTE_JS))
        assertFalse("get_readable should NOT be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.GET_READABLE))
        assertFalse("get_page_info should NOT be throttled", BrowserRiskControl.shouldThrottle(BrowserAction.GET_PAGE_INFO))
    }

    @Test
    fun baseThrottleDelayReturnsCorrectValues() {
        assertEquals("navigate base", 550L, BrowserRiskControl.baseThrottleDelayMs(BrowserAction.NAVIGATE, null))
        assertEquals("click base", 180L, BrowserRiskControl.baseThrottleDelayMs(BrowserAction.CLICK, null))
        assertEquals("type base", 260L, BrowserRiskControl.baseThrottleDelayMs(BrowserAction.TYPE, null))
        assertEquals(
            "scroll_and_collect base",
            320L,
            BrowserRiskControl.baseThrottleDelayMs(BrowserAction.SCROLL_AND_COLLECT, null)
        )
        assertEquals(
            "non-throttled action → 0",
            0L,
            BrowserRiskControl.baseThrottleDelayMs(BrowserAction.SCREENSHOT, null)
        )
    }

    @Test
    fun searchHostBonusAppliedOnTopOfBase() {
        val navigateOnSearch = BrowserRiskControl.baseThrottleDelayMs(
            BrowserAction.NAVIGATE, "https://www.google.com/search"
        )
        val navigateOnNormal = BrowserRiskControl.baseThrottleDelayMs(
            BrowserAction.NAVIGATE, "https://example.com/"
        )
        assertEquals("navigate: base 550 + search bonus 900 = 1450", 1450L, navigateOnSearch)
        assertEquals("navigate on normal: base 550", 550L, navigateOnNormal)
    }

    @Test
    fun detectsSearchEngineTrafficChallenge() {
        val challenge = BrowserRiskControl.detectChallenge(
            title = "Google",
            bodyText = "Our systems have detected unusual traffic from your computer network.",
            currentUrl = "https://www.google.com/sorry/index?continue=https://www.google.com/search"
        )

        assertEquals("search_engine_challenge", challenge?.kind)
        assertEquals(
            "ask_user_to_complete_verification_manually",
            challenge?.recommendedNextAction
        )
    }

    @Test
    fun detectsCloudflareCaptchaAndHttpRateLimits() {
        val cloudflare = BrowserRiskControl.detectChallenge(
            title = "Attention Required! | Cloudflare",
            bodyText = "Checking if the site connection is secure. Challenge platform.",
            currentUrl = "https://example.com/"
        )
        val captcha = BrowserRiskControl.detectChallenge(
            title = "Security check",
            bodyText = "Please verify you are human and complete the CAPTCHA.",
            currentUrl = "https://example.com/"
        )
        val rateLimited = BrowserRiskControl.detectChallenge(
            statusCode = 429,
            currentUrl = "https://example.com/"
        )
        val denied = BrowserRiskControl.detectChallenge(
            statusCode = 403,
            currentUrl = "https://example.com/"
        )

        assertEquals("cloudflare_challenge", cloudflare?.kind)
        assertEquals("captcha_challenge", captcha?.kind)
        assertEquals("rate_limited", rateLimited?.kind)
        assertEquals("access_denied", denied?.kind)
    }

    @Test
    fun detectsChallengeFromTitleOnly() {
        // Title-only detection covers the common cases without needing
        // an expensive JS bodyText fetch.
        val cloudflare = BrowserRiskControl.detectChallenge(
            title = "Attention Required! | Cloudflare"
        )
        val captcha = BrowserRiskControl.detectChallenge(
            title = "Security check"
        )
        val rateLimited = BrowserRiskControl.detectChallenge(
            title = "429 Too Many Requests"
        )
        val denied = BrowserRiskControl.detectChallenge(
            title = "403 Forbidden"
        )

        assertEquals("cloudflare_challenge", cloudflare?.kind)
        assertEquals("captcha_challenge", captcha?.kind)
        assertEquals("rate_limited", rateLimited?.kind)
        assertEquals("access_denied", denied?.kind)
    }

    @Test
    fun returnsNullForNormalPage() {
        val challenge = BrowserRiskControl.detectChallenge(
            title = "Home - Wikipedia",
            bodyText = "Wikipedia is a free online encyclopedia.",
            currentUrl = "https://en.wikipedia.org/wiki/Main_Page"
        )
        assertNull("normal page should not trigger a challenge", challenge)
    }

    @Test
    fun normalizedHostHandlesVariousUrlFormats() {
        assertNull("null input", BrowserRiskControl.normalizedHost(null))
        assertNull("empty input", BrowserRiskControl.normalizedHost(""))
        assertEquals("simple", "google.com", BrowserRiskControl.normalizedHost("https://google.com"))
        assertEquals("www prefix stripped", "google.com", BrowserRiskControl.normalizedHost("https://www.google.com"))
        assertEquals("path ignored", "google.com", BrowserRiskControl.normalizedHost("https://www.google.com/search?q=test"))
        assertEquals("port ignored", "google.com", BrowserRiskControl.normalizedHost("https://www.google.com:443/"))
        assertEquals("ip address", "192.168.1.1", BrowserRiskControl.normalizedHost("http://192.168.1.1/"))
    }

    @Test
    fun isSearchHostRecognizesAllSearchEngines() {
        val searchHosts = listOf(
            "https://www.google.com/search",
            "https://google.com/search",
            "https://www.bing.com/search",
            "https://duckduckgo.com/",
            "https://search.brave.com/search",
            "https://www.baidu.com/s?wd=test",
            "https://yandex.ru/search",
            "https://ecosia.org/search",
            "https://www.sogou.com/web",
        )
        val nonSearchHosts = listOf(
            "https://github.com/",
            "https://stackoverflow.com/",
            "https://en.wikipedia.org/",
            "https://www.reddit.com/",
        )

        searchHosts.forEach { url ->
            assertTrue("$url should be recognized as search host", BrowserRiskControl.isSearchHost(url))
        }
        nonSearchHosts.forEach { url ->
            assertFalse("$url should NOT be recognized as search host", BrowserRiskControl.isSearchHost(url))
        }
    }
}