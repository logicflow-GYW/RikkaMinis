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
     * [incr1117-B F-11] Prose's SECOND, count-independent signal: English
     * function words.
     *
     * The word-count gate above is a proxy — "three or more ASCII words, one of
     * them long" — and every proxy has a hole. Widening the call exemption (see
     * [looksLikeNaturalLanguage]) opened this one: `100 + min(fee) total ` has
     * only two counted words left, so the gate said "not a sentence" and the
     * `+` scan rendered a price sentence as KaTeX, deleting both `$`. Same shape
     * as backlog §CD F-11 (`10 min - 20 max or`).
     *
     * A formula, however, never writes an English function word as a standalone
     * token — not as an argument name either, which is why this net reads the
     * WHOLE content and not just the text outside the call groups (`x +
     * sin(the angle)` must stay prose). Prose almost always writes one, and one
     * is enough, whatever the word count is.
     *
     * Deliberately closed and short. `to` is NOT in it on purpose:
     * `lim n to inf` is a documented formula shape (see
     * [MATH_FUNCTION_TOKENS]) and `to`/`in`/`of` style prepositions would eat
     * it. Single letters (`a`, `x`) are never included — `f(a, b)` is a formula.
     * The trade-off is the same one the other nets make: a span holding a
     * variable literally named `in`/`of` stays text (missed formula, `$` still
     * visible) instead of a price sentence being swallowed by KaTeX.
     */
    private val PROSE_FUNCTION_WORDS: Set<String> = setOf(
        "the", "and", "or", "of", "for", "per", "each", "total", "with",
        "from", "by", "as", "in", "at", "is", "than", "that", "this", "if", "not",
    )

    /**
     * [review-1114 B] Tokens that are ordinary 3+ letter ASCII words AND the
     * strongest possible evidence that the span is a formula.
     *
     * The prose guard below counts "ASCII words" as prose evidence. That rule
     * has one blind spot it cannot see on its own: every mathematical function
     * name IS such a word. `log x + log y` has four word tokens, one of them
     * 3 letters long — so the guard called a two-logarithm sum a sentence and
     * both `$` vanished from it. Same for `sin x cos x`, `max a b`,
     * `lim n to inf`, `O(n log n)`. Five regressions, one cause.
     *
     * So these tokens are excluded from the LONG-word test: a formula may use
     * any number of them and still be a formula. They are NOT excluded from the
     * word count in general — see [looksLikeNaturalLanguage] for the money
     * sentence that needs `min` to count. The one exception is the CALL form
     * ([incr1117-B F-06], widened in [incr1117-B F-11]): a name followed by a
     * BALANCED `( … )` list — arguments, commas and nested calls included — is
     * one structural unit of a formula, so neither the name nor anything inside
     * the parentheses is counted, and none of it can be the long word.
     *
     * Exact-token match, case-insensitive — `maximum` is not `max`, `sine` is
     * not `sin`, `logo` is not `log`. The list is deliberately closed: adding
     * an ordinary English word here would re-open the hole in the other
     * direction, which is why `for` / `and` / `the` can never join it.
     */
    private val MATH_FUNCTION_TOKENS: Set<String> = setOf(
        "log", "ln", "lg", "sin", "cos", "tan", "cot", "sec", "csc",
        "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "exp", "max", "min", "sup", "inf", "lim", "det", "dim", "ker",
        "mod", "gcd", "lcm", "deg", "arg", "re", "im", "pr", "var", "cov",
    )

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
     *    while prose always carries an ordinary word (`for`, `pass`, `day`);
     *  - [review-1114 B] a token in [MATH_FUNCTION_TOKENS] never satisfies the
     *    LONG-word test (it still counts toward the total). Function names are
     *    the one class of 3+ letter ASCII word that appears INSIDE formulas, so
     *    letting them be the long word inverted the signal — it made
     *    `log x + log y` and `sin x cos x` read as sentences while the `-` in
     *    `3-day` still reads as prose evidence.
     *
     *    [incr1116-B F-10] They still count toward the total UNLESS they are
     *    written as a call — and that distinction is what keeps unit
     *    abbreviations out of the formula branch: `50 for 30-min or ` has three
     *    words only if `min` is one of them, and its long word is `for` — so it
     *    stays prose. Excluding them from the count entirely (the first cut)
     *    made the span look like a two-word formula, and the `-` of `30-min`
     *    then rendered both dollars as KaTeX.
     *
     *    [incr1117-B F-06] That first cut over-corrected the other way:
     *    `sin(theta) + cos(theta)` is four counted words with `theta` as the
     *    long one, so the guard called a formula a sentence and both `$`
     *    vanished again. The fix is the lexical call shape, not a wider
     *    exemption: a token followed by `(` (whitespace allowed in between) is
     *    a function CALL — structural evidence of a formula — so it is not
     *    counted and cannot be the long word. `30-min` has no `(` after `min`,
     *    so the unit abbreviation keeps counting and `$50 for 30-min or $100`
     *    stays prose; and a sentence whose first token merely looks call-shaped
     *    (`sin (the angle) of a circle`) keeps its other long words (`angle`,
     *    `circle`) and stays prose.
     *
     *    [incr1117-B F-11] That exemption stopped at the function NAME: the
     *    arguments inside the parentheses still counted, so
     *    `sin(alpha) + cos(beta) + tan(gamma)` came back as three words with a
     *    long word — a formula called a sentence again. The unit is therefore
     *    the whole CALL, not the head token: `name` + the BALANCED `( … )`
     *    group behind it (arguments, commas, nested calls and all) is one piece
     *    of formula structure. An unbalanced `(` is not a call — the head keeps
     *    counting, which is the fail-safe side.
     *
     *    Widening that exemption has a price the count gate cannot pay on its
     *    own: `100 + min(fee) total ` now counts two words, drops under the
     *    three-word gate, and the `+` scan would render a price sentence as
     *    KaTeX. That is what [PROSE_FUNCTION_WORDS] is for — see the top of the
     *    function body.
     *
     * The rules together keep `$x - y$`, `$x+y$`, `$1+1=2$`, `$x^2$` and
     * `$\frac{1}{2}$` classified as math (they fail the word-count or the
     * long-word test) while every multi-word English/Chinese-free sentence is
     * rejected, and a formula built out of function names (`log x + log y`,
     * `lim n to inf`) survives. Chinese prose has no spaces and is already
     * excluded by the single-token path below (it is not ASCII letters).
     */
    private fun looksLikeNaturalLanguage(content: String): Boolean {
        if (content.indexOf('\\') >= 0) return false
        // [incr1117-B F-11] Net 1 — function words, count-independent. Read over
        // the WHOLE content on purpose: a call group must not hide prose
        // (`x + sin(the angle) + 3`), so this scan does not skip anything.
        if (containsProseFunctionWord(content)) return true
        // Net 2 — the original count gate, now blind to whole call groups.
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
            // [incr1117-B F-06 / F-11] A CALL — the name plus its balanced
            // `( … )` list — is one structural unit of a formula: neither the
            // name nor its arguments are words, and none of them can be the
            // long word. The `(` is what separates it from `30-min` — the unit
            // abbreviation has no call and therefore still counts
            // ([incr1116-B F-10]).
            val groupEnd = callGroupEnd(content, j)
            if (groupEnd > 0) {
                i = groupEnd
                continue
            }
            // [incr1116-B F-10] Every other token is a word; a function name
            // just cannot be the LONG one. See the KDoc: the count has to
            // include `min`/`max`/`sec` so `50 for 30-min or ` reaches three
            // words, while `max a b` still fails the long-word test and stays
            // math.
            words++
            if (content.substring(i, j).lowercase() !in MATH_FUNCTION_TOKENS &&
                j - i >= NATURAL_LANGUAGE_MIN_WORD_LENGTH
            ) {
                hasLongWord = true
            }
            i = j
        }
        return words >= NATURAL_LANGUAGE_MIN_WORDS && hasLongWord
    }

    /**
     * [incr1117-B F-11] "There is an English function word in here."
     *
     * Exact token match, case-insensitive, over the raw content — the call
     * groups included (see [PROSE_FUNCTION_WORDS] for why that matters).
     */
    private fun containsProseFunctionWord(content: String): Boolean {
        var i = 0
        while (i < content.length) {
            if (!content[i].isAsciiLetter()) {
                i++
                continue
            }
            var j = i
            while (j < content.length && content[j].isAsciiLetter()) j++
            if (content.substring(i, j).lowercase() in PROSE_FUNCTION_WORDS) return true
            i = j
        }
        return false
    }

    /**
     * [incr1117-B F-11] Offset just past the balanced `( … )` list that follows
     * [tokenEnd] (whitespace before the `(` allowed), or `-1` when the token is
     * not call-shaped — no `(` at all, or the parentheses do not balance.
     *
     * Unbalanced input returns `-1` on purpose: while a formula streams in,
     * `sin(x + 1` is half written, and the fail-safe answer is "this is not a
     * structural group yet" — the head keeps counting as a word rather than the
     * rest of the content disappearing into a group that never closes.
     */
    private fun callGroupEnd(content: String, tokenEnd: Int): Int {
        var k = tokenEnd
        while (k < content.length && content[k].isWhitespace()) k++
        if (k >= content.length || content[k] != '(') return -1
        var depth = 0
        var i = k
        while (i < content.length) {
            when (content[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return -1
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
