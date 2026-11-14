package com.rikkaminis.app.ui.markdown

/**
 * [r5-ef / F-F-07, P1] The ONE classifier for `$ … $` spans — "is this content
 * a formula or is it money?".
 *
 * Why it lives in its own file: the app has two parsers that must answer that
 * question the same way. `MarkdownParser` classifies the settled text; the
 * streaming renderer (`StreamingMarkdownText`, and through it
 * `MarkdownBlockModel`) classifies the same text while it is still arriving.
 * Each had its own private `looksLikeMath`, and they drifted: three rounds of
 * fixes landed only in the static copy —
 *  - [r3-c / B09 F-13] currency excluded BEFORE the operator scan,
 *  - the "content still holds a `$`" residue guard,
 *  - [r5-f / F-07] price ranges carrying a unit suffix (`$5k-$10k`).
 *
 * So `$5k-$10k` rendered as KaTeX while streaming and flipped back to dollars
 * the moment the message settled — same sentence, two answers. The duplicated
 * glyph table WAS the split; this file removes the possibility of a third
 * round by keeping one implementation and no second copy.
 *
 * Everything here is pure string work on purpose: both callers run on the UI
 * thread, and the parity between them is asserted by
 * `MarkdownMathParityTest` (differential: static spans vs streaming spans over
 * one shared table).
 */
internal object MarkdownMathHeuristic {

    /**
     * [r3-c / B09 F-13] Glyphs a price never uses. Used by [isCurrencyShaped] to
     * decide "digit-led content is still math". `=` (an equation), LaTeX
     * structure (`\ ^ _ { }`) and the big operators are in it; `+ - < >` are NOT,
     * because `$5 - $10` / `$10+$5` / `$5>$3` are exactly the money shapes the
     * old operator scan mistook for formulas.
     */
    private val NON_CURRENCY_MATH_GLYPHS = charArrayOf('=', '\\', '^', '_', '{', '}', '∫', '∑', '∏', '√')

    /** Characters that make a `$ … $` span a formula regardless of its first char. */
    private val MATH_GLYPHS =
        charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')

    /**
     * [isCurrencyShaped]: true when a `$ … $` candidate is the LEFT HALF of a
     * price range whose amount carries a unit or a magnitude suffix —
     * `$5k-$10k`, `$5M-$10M`, `$10/mo-$20/mo`, `$5B+$2B`, `US$5k-US$10k`.
     */
    private val MONEY_RANGE_CONTENT = Regex(
        "^[0-9][0-9.,]*\\s*(?:[A-Za-z]{1,3})?(?:/[\\p{L}\\p{N}]{1,6})?\\s*[-+<>]\\s*(?:[A-Z]{1,3})?$"
    )

    /**
     * [R8-B B-F09] A `$ … $` content with this many ASCII-letter words, at
     * least one of them this long, is prose. Two constants instead of one
     * because either signal alone misfires: three single letters (`x + y`) is
     * math, and one long word (`\alpha` without the backslash, `price`) can be
     * either. Together they describe "an ordinary sentence" and nothing a
     * formula writes.
     */
    private const val NATURAL_LANGUAGE_MIN_WORDS = 3
    private const val NATURAL_LANGUAGE_MIN_WORD_LENGTH = 3

    /**
     * Currency-skip heuristic: `$5` / `$10.99` should stay plain text. Treat the
     * `$ … $` content as math only if it has a math-ish character
     * (`\ ^ _ { }` / common big-symbol unicode) or is longer than 2 chars (so
     * `x+y` qualifies but bare digits like `5` do not). Mirrors iOS.
     */
    fun looksLikeMath(content: String): Boolean {
        if (content.isEmpty()) return false
        // [r3-c / B09 F-13, P1] A span that still contains a bare `$` is not a
        // formula — it is the residue of a pairing that jumped over a dollar the
        // closing rule rejected (a `$` preceded by a space is not a close
        // delimiter), so the real close was a LATER `$`: `"$5 - $10 and $20"`
        // pairs the first with the third and its content is `"5 - $10 and"`.
        // Rendering that as KaTeX deletes three currency markers and feeds the
        // engine a stray `$`. Reject, keep the sentence as text.
        if (content.indexOf('$') >= 0) return false
        // [r3-c / B09 F-13, P1] Currency is excluded BEFORE the operator scan.
        // The old order scanned `-`/`+`/`=`/`<`/`>` first and only then rejected
        // a leading digit, so the leading-digit guard was unreachable for exactly
        // the shapes it was written for: `$5-$10` → content `"5-"` → hits `-` →
        // "math", and both `$` vanished from the sentence (same for `$10+$5`,
        // `$5>$3`, `US$5-US$10`). Price ranges with no space around the operator
        // are the common case in LLM prose about money.
        if (isCurrencyShaped(content)) return false
        // [r5-f / F-07, P1] The same net, one shape wider: a price whose amount
        // carries a unit/magnitude suffix (`$5k-$10k`, `$5M-$10M`,
        // `$10/mo-$20/mo`, `$5B+$2B`, `US$5k-US$10k`). [isCurrencyShaped]
        // refuses a price by requiring "no letter at all", so these forms
        // (`5k-`, `5M-`, `10/mo-`) slipped past it and the operator scan below
        // accepted the trailing `-`/`+` — both `$` were consumed and `5k-` went
        // to KaTeX. Ruled out BEFORE that scan, same as the digit-only forms.
        if (isCurrencyRangeShaped(content)) return false
        // [R8-B B-F09] Natural-language prose is not a formula, and it must be
        // ruled out BEFORE the glyph scan: `$50 for a 3-day pass or $100` pairs
        // (the streaming close rule allows a space before the closing `$`) into
        // `50 for a 3-day pass or `, whose `-` is in [MATH_GLYPHS] — so the
        // sentence rendered as KaTeX and both `$` vanished. Neither currency net
        // can see it: [isCurrencyShaped] demands "no letter at all" and
        // [isCurrencyRangeShaped] demands "no spaces", and this is English.
        if (looksLikeNaturalLanguage(content)) return false
        // T208 Layer B: previous heuristic rejected single-letter math like
        // `$c$` or `$i$` — `length > 2` returned false and the literal
        // dollar-wrapped text leaked into the rendered output. Any of:
        //   - obvious LaTeX glyphs (`\`, `^`, `_`, `{`, `}`, integrals…)
        //   - short alphanumeric content with no whitespace and no leading
        //     digit (currency like `$5` filtered out by the leading-digit
        //     check; `$5.99` further filtered by the dot rule below)
        // qualifies as math. Mirrors iOS MarkdownMathExtractor.looksLikeMath.
        for (c in content) {
            if (c in MATH_GLYPHS) return true
        }
        // Currency heuristic: `$5`, `$5.99`, `$1,000` look numeric. Skip.
        // [r3-c] Now a SECOND net, not the only one: [isCurrencyShaped] above
        // already caught the digit-led forms that also carry an arithmetic mark
        // (`5-`, `10+`). What still lands here is digit-led content built of
        // glyphs that are not currency punctuation, e.g. `$5x2 rows$`.
        if (content[0].isDigit()) return false
        // Whitespace at either edge usually means a stray dollar, not math.
        if (content.first().isWhitespace() || content.last().isWhitespace()) return false
        // Short ASCII identifier-ish content (`$c$`, `$x$`, `$pi$`) up to
        // 30 chars is almost certainly a variable reference.
        if (content.length <= 30 && content.all { it.isLetterOrDigit() }) return true
        return content.length > 2
    }

    /**
     * [R8-B B-F09] "This content is a sentence, not a formula."
     *
     * A `$ … $` pair whose content is several whitespace-separated words of
     * ordinary prose is never math, whatever glyphs it happens to contain:
     * `50 for a 3-day pass or ` has a `-` but is English. The rule is
     * deliberately narrow so it cannot eat real math:
     *
     *  - content containing `\` is LaTeX structure → NEVER natural language
     *    (`\frac{1}{2}`, `\pi r^2`); checked first, so LaTeX always wins;
     *  - need at least [NATURAL_LANGUAGE_MIN_WORDS] word tokens, where a word is
     *    a run of ASCII letters (so `x + y` = 3 one-letter words would qualify…
     *    hence the next bullet);
     *  - at least one word must be [NATURAL_LANGUAGE_MIN_WORD_LENGTH] letters or
     *    longer — a real formula's "words" are single variables (`x`, `pi`),
     *    while prose always carries an ordinary word (`for`, `pass`, `day`).
     *
     * The two together keep `$x - y$`, `$x+y$`, `$1+1=2$`, `$x^2$` and
     * `$\frac{1}{2}$` classified as math (they fail the word-count or the
     * long-word test) while every multi-word English/Chinese-free sentence is
     * rejected. Chinese prose has no spaces and is already excluded by the
     * single-token path below (it is not ASCII letters).
     */
    private fun looksLikeNaturalLanguage(content: String): Boolean {
        if (content.indexOf('\\') >= 0) return false
        var words = 0
        var hasLongWord = false
        var i = 0
        while (i < content.length) {
            if (!content[i].isAsciiLetter()) {
                i++
                continue
            }
            var j = i
            while (j < content.length && content[j].isAsciiLetter()) j++
            words++
            if (j - i >= NATURAL_LANGUAGE_MIN_WORD_LENGTH) hasLongWord = true
            i = j
        }
        return words >= NATURAL_LANGUAGE_MIN_WORDS && hasLongWord
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /**
     * [r3-c / B09 F-13] "This `$ … $` span is money, not a formula": the content
     * starts with a digit and carries nothing a price would not use — no letter,
     * no `=`, and no LaTeX structure glyph (`\ ^ _ { }` and the big operators).
     *
     * The three exclusions are what keeps real math alive under a leading digit:
     * `$1+1=2$` has an `=`, `$2^{10}$` has `^`/`{`/`}`, `$\frac{1}{2}$` starts
     * with `\` — all still reach the glyph scan and render. Everything else that
     * begins with a digit (`5-`, `10+`, `5>`, `5.99`, `1,000`, `5 - 3`) is a
     * price or a price range, and the `$` markers are the text the user meant.
     *
     * Trade-off recorded on purpose: `$5 > 3$` as a bare numeric inequality reads
     * as money and stays text. Text is the honest failure — a missed formula
     * still shows its own `$…$`, a swallowed price range silently deletes the
     * `$` the user was reading.
     */
    fun isCurrencyShaped(content: String): Boolean {
        if (content.isEmpty() || !content.first().isDigit()) return false
        for (c in content) {
            if (c.isLetter()) return false
            if (c in NON_CURRENCY_MATH_GLYPHS) return false
        }
        return true
    }

    /**
     * [r5-f / F-07, P1] The "price range with a suffix" twin of
     * [isCurrencyShaped]: true when a `$ … $` candidate is the LEFT HALF of a
     * money range whose amounts carry a unit or a magnitude suffix —
     * `$5k-$10k`, `$5M-$10M`, `$10/mo-$20/mo`, `$5B+$2B`, `US$5k-US$10k`.
     *
     * Every part of the shape is something a price writes and a formula does
     * not, read from the inside out:
     *  - a number (`5`, `1.5`, `1,000`);
     *  - an optional magnitude suffix of 1–3 ASCII letters (`k`, `M`, `B`, `bn`);
     *  - an optional `/unit` run of 1–6 letters/digits (`/mo`, `/user`, `/月`);
     *  - exactly one money operator — `-` `+` `<` `>`. `=` `^` `_` `{` `}` are
     *    absent on purpose: those mean equation or LaTeX structure, not price
     *    arithmetic (same split as [NON_CURRENCY_MATH_GLYPHS]);
     *  - an optional CAPS currency prefix of 1–3 letters, which the pairing
     *    swallowed together with the operator (`US$5k-US$10k` → `5k-US`).
     *
     * The CAPS clause is the narrowest one on purpose: `$5-x$` still reads as
     * `5 − x` and stays math, while `US$5k-US$10k` reads as money and stays
     * text. Text is the honest failure here — a missed formula still shows its
     * own `$…$` to the user, a swallowed price range silently deletes the `$`.
     *
     * ponytail: decision by content shape, not by reading the sentence |
     * 天花板: a formula literally shaped `<number><unit><op><CAPS>` in caps
     * (e.g. `$5k-X$`) reads as money and stays text | 升级触发: a report of a
     * swallowed formula whose `$ … $` content matches this pattern verbatim.
     */
    fun isCurrencyRangeShaped(content: String): Boolean =
        MONEY_RANGE_CONTENT.matches(content)
}
