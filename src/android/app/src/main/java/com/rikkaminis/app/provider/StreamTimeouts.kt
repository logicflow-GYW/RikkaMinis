package com.rikkaminis.app.provider

/**
 * [§24b] Cross-provider streaming timeouts.
 *
 * The time-to-first-byte budget used to be duplicated three ways: OpenAIProvider
 * carried 90s as a private companion constant while AnthropicProvider and
 * GeminiProvider hard-coded `delay(30_000L)` inline. When the 30s value was
 * found wrong for queueing relays (2026-09-12) only the OpenAI copy was raised.
 * The other two kept the stale value, and because the in-flow watchdogs were
 * asleep inside the worker's `runBlocking` at the time, the difference was not
 * observable in logs either. One definition point so the next revision cannot
 * land in a single provider only.
 */
object StreamTimeouts {
    /**
     * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte budget:
     * response HEADERS must arrive within this window. Does NOT bound the SSE
     * body — a flowing stream stays unlimited. This is the dead-upstream
     * signal: a wedged tunnel never reaches headers at all.
     *
     * [fix/ttfb-thinktag-composer] 2026-09-11: raised 30s → 90s. The 30s
     * carried the assumption "headers arrive fast even for slow generations" —
     * measured false for queueing relays/gateways: direct probing of a relay
     * (api.senseaudio.cn) found 4/8 requests sitting 42.9–59.3s BEFORE headers
     * (all latency in the upstream queue, not the body). Killing those at 30s
     * turned ordinary relay queueing into a forced retry loop (retry →
     * re-queue → killed again), which users experience as the provider
     * "failing mid-answer". 90s covers the observed distribution with ~1.5×
     * margin. Trade-off: a genuinely wedged tunnel now surfaces here 60s later;
     * NetworkMonitor's pool eviction on network transitions and the retry
     * ladder remain the first-line recovery, so the dead-tunnel case stays
     * bounded and self-healing.
     *
     * The value is a hardware/network calibration constant: named, single
     * definition, with the measurement that picked it recorded above. Do not
     * inline it back into a provider.
     */
    const val TTFB_TIMEOUT_MS = 90_000L
}

/**
 * [T264] The placeholder text emitted in place of an image part when the
 * target model declares no `image` input modality. One literal, so the three
 * providers cannot drift apart — OpenAIProvider carried this string inline in
 * three places before this file existed.
 */
const val VISION_UNSUPPORTED_PLACEHOLDER =
    "[Image attached but this model does not support vision input]"

/**
 * [T264 / §27a] Decide what to emit for an image part.
 *
 * Returns `null` when the model accepts image input (the caller emits its
 * provider-native image block), or [VISION_UNSUPPORTED_PLACEHOLDER] when it
 * does not (the caller emits that string as a text part instead — otherwise
 * the upstream answers 400 `unknown variant image_url` / `unknown variant
 * image`).
 *
 * `fallbackText` is a parameter rather than a hard-coded literal so a caller
 * that ever needs different wording can pass it without a second helper; all
 * three providers currently pass [VISION_UNSUPPORTED_PLACEHOLDER].
 */
fun visionPlaceholder(supportsImages: Boolean, fallbackText: String): String? =
    if (supportsImages) null else fallbackText
