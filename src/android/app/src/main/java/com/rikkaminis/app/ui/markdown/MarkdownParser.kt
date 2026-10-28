package com.rikkaminis.app.ui.markdown

import com.rikkaminis.app.ui.chat.MediaKind
import com.rikkaminis.app.ui.chat.mediaKindOfUrl

/**
 * Lightweight markdown parser that converts raw markdown text into a list of block nodes.
 * Supports: headings, code blocks (fenced), blockquotes, bullet/numbered/task lists,
 * thematic breaks, tables, and paragraphs. Inline parsing is handled separately.
 */
object MarkdownParser {

    sealed class Block {
        data class Heading(val level: Int, val content: String) : Block()
        data class Paragraph(val content: String) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class Blockquote(val blocks: List<Block>) : Block()
        data class BulletList(val items: List<ListItem>) : Block()
        data class NumberedList(val startNumber: Int, val items: List<ListItem>) : Block()
        data class Table(val headers: List<String>, val alignments: List<Alignment>, val rows: List<List<String>>) : Block()
        data object ThematicBreak : Block()

        /**
         * Display-mode LaTeX. Either extracted from `$$ … $$` / `\[ … \]`,
         * or promoted from a paragraph that contained nothing but a single
         * inline-math placeholder. mhchem (\ce{…}, \pu{…}) is supported via
         * the bundled assets/katex/mhchem.min.js extension.
         */
        data class MathBlock(val latex: String) : Block()

        /**
         * Media blocks extracted from `![alt](url)` when the line only contains
         * that one image-syntax node. Routing by file extension mirrors iOS
         * SelectableMarkdownView (nativeImageExts / nativeVideoExts / nativeAudioExts).
         */
        data class Image(val alt: String, val url: String) : Block()
        data class Video(val alt: String, val url: String) : Block()
        data class Audio(val alt: String, val url: String) : Block()
    }

    /**
     * A single extracted math expression, identified by a unique placeholder
     * inserted into the markdown stream before block-parsing. After the AST
     * is built, the placeholders are looked up by id and restored either as
     * MathBlock (display) or kept inline (rendered by the inline pass).
     */
    data class MathSpan(val placeholder: String, val latex: String, val isBlock: Boolean)

    /**
     * Holder shared with [MarkdownText] so the inline parser can also resolve
     * placeholders to their original LaTeX. Threaded through composition by
     * passing the whole list of spans alongside the parsed blocks.
     */
    data class ParseResult(val blocks: List<Block>, val mathSpans: List<MathSpan>)

    /** Object Replacement Character (U+FFFC) — sentinel used by math placeholders. */
    private const val ORC = '￼'

    /**
     * [r3-c / B09 F-13] Glyphs a price never uses. Used by [isCurrencyShaped] to
     * decide "digit-led content is still math". `=` (an equation), LaTeX
     * structure (`\ ^ _ { }`) and the big operators are in it; `+ - < >` are NOT,
     * because `$5 - $10` / `$10+$5` / `$5>$3` are exactly the money shapes the
     * old operator scan mistook for formulas.
     */
    private val NON_CURRENCY_MATH_GLYPHS = charArrayOf('=', '\\', '^', '_', '{', '}', '∫', '∑', '∏', '√')

    // [dv22/B-06] The extension sets live in one place only
    // (`ui/chat/MarkdownBlockModel.kt`). This parser classifies through
    // `mediaKindOfUrl` so the block renderer, the inline split and the image
    // gallery can never disagree about what counts as video/audio.

    /** Regex for a standalone `![alt](url)` node on its own line. */
    private val standaloneImageRegex = Regex("""^\s*!\[([^\]]*)\]\(([^)\s]+)\)\s*$""")

    /**
     * ATX heading: 1–6 `#` at column 0, whitespace, then text.
     *
     * One regex for both consumers — the heading branch below and the
     * paragraph loop's break test — because the two must agree on where a
     * paragraph ends. [dv15/B-07] The loop used a bare `startsWith("# ")`
     * (level 1 only), so a `##`…`######` heading glued to the previous
     * paragraph (no blank line between them) was collected as paragraph text
     * and never produced a [Block.Heading]. Breaking on a set WIDER than the
     * branch is safe (the line just becomes its own paragraph, `i` always
     * advances); breaking on a narrower one is the bug.
     */
    private val atxHeadingRegex = Regex("^(#{1,6})\\s+(.+)$")

    /** Numbered-list item; the paragraph loop uses the prefix-only form below. */
    private val numberedListItemRegex = Regex("^\\s{0,3}(\\d{1,2})[.)](?:\\s+(.*))?$")

    /**
     * Prefix test for "this line starts a numbered item" — for break/continuation
     * decisions. Kept narrower than a bare `\d{1,2}[.)]` on purpose: `3.5 倍`
     * is prose, not item `3.` (same rule the list branch enforces via
     * [numberedListItemRegex] requiring whitespace or EOL after the marker).
     */
    private val numberedListMarkerRegex = Regex("^\\s{0,3}\\d{1,2}[.)](?:\\s|$)")

    /**
     * A blockquote line is `>` at end of line, or `>` followed by a space or a
     * tab. `>foo` / `>5` / `>=` are PROSE — shell output and comparisons that
     * an LLM emits constantly — and wrapping them in a quote rule is a
     * regression the chat parser already paid for (T117). Same predicate as
     * `ui/chat/MarkdownBlockModel.kt:isBlockquoteLine` — identical predicate,
     * kept as a separate copy because the two parsers serve different surfaces.
     *
     * [dv15/B-07] One function for the three places that must agree (quote
     * branch entry, quote collector, paragraph break): an entry predicate
     * narrower than the collector's would produce a quote block that swallows
     * the following lines, and a paragraph break narrower than the branch
     * leaves quote lines stuck inside prose.
     */
    private fun isBlockquoteLine(line: String): Boolean {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith(">")) return false
        if (trimmed.length == 1) return true
        val next = trimmed[1]
        return next == ' ' || next == '\t'
    }

    /**
     * True when `lines[idx]` starts a GFM table — header line plus a
     * separator row, and at least one of the two carrying a `|`.
     *
     * The pipe requirement is the [T5/md-setext-table] heuristic: a pipe-less
     * `---` matches [isTableSeparator] as a single-column separator, which
     * turned "Title\n---" into an empty Table. Shared by the Table branch and
     * the paragraph loop so the loop can never believe a table starts where
     * the branch does not (that mismatch is what let a table glued to a
     * paragraph be absorbed as prose).
     */
    private fun startsTable(lines: List<String>, idx: Int): Boolean =
        idx + 1 < lines.size && isTableSeparator(lines[idx + 1]) &&
            (lines[idx].contains('|') || lines[idx + 1].contains('|'))

    /**
     * Classify a media URL by file extension. Extracts the extension from the
     * *last path segment only* so filenames that contain '#' or '?' (either
     * literal or percent-encoded) still get their real extension. Handles both
     * unencoded names (e.g. `foo#China.mp4`) and encoded ones (`foo%23China.mp4`).
     */
    private fun mediaBlockFor(alt: String, url: String): Block =
        // [dv22/B-06] One classifier for every media surface (`ui/chat/MarkdownBlockModel.kt`).
        when (mediaKindOfUrl(url)) {
            MediaKind.VIDEO -> Block.Video(alt, url)
            MediaKind.AUDIO -> Block.Audio(alt, url)
            MediaKind.IMAGE -> Block.Image(alt, url)
        }

    data class ListItem(val content: String, val checked: Boolean? = null)

    enum class Alignment { LEFT, CENTER, RIGHT }

    /**
     * Top-level entry: extract math, run the standard block parser on the
     * placeholder-substituted text, then run the math-restore pass that
     * promotes single-math paragraphs to MathBlock. Inline math placeholders
     * stay in the paragraph text so the inline pass can render them via
     * KaTeX. Mirrors iOS MarkdownMathExtractor.extract → cmark → restore.
     */
    fun parseWithMath(markdown: String): ParseResult {
        val (cleaned, spans) = extractMath(markdown)
        val rawBlocks = parse(cleaned)
        val restored = restoreMath(rawBlocks, spans)
        return ParseResult(restored, spans)
    }

    fun parse(markdown: String): List<Block> {
        // [fix/audit-b17 / T10-L6] these logs fire per streaming delta
        // (and per composition) — keep them out of release builds.
        if (com.rikkaminis.app.BuildConfig.DEBUG) {
            android.util.Log.d("MdParser", "parse() len=${markdown.length} preview=${markdown.take(160).replace("\n","\\n")}")
        }
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                val indent = line.indexOf('`')
                val fence = line.trimStart()
                val lang = fence.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size) {
                    val cl = lines[i]
                    if (cl.trimStart().startsWith("```") && cl.trim() == "```") {
                        i++
                        break
                    }
                    codeLines.add(cl)
                    i++
                }
                blocks.add(Block.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            // Thematic break
            if (line.matches(Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1(\\s*\\1)*\\s*$"))) {
                blocks.add(Block.ThematicBreak)
                i++
                continue
            }

            // ATX Heading
            val headingMatch = atxHeadingRegex.find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                // [T5/md-trailing-hash] Closing sequence: strip a trailing run of
                // '#' only when preceded by whitespace (CommonMark). The old
                // removeSuffix("#") both ate content ("## C#" → "C") and left
                // residue on space-preceded closers ("## title ##" → "title #").
                val content = Regex("\\s+#+$").replace(headingMatch.groupValues[2].trimEnd(), "").trimEnd()
                blocks.add(Block.Heading(level, content))
                i++
                continue
            }

            // Blockquote
            if (isBlockquoteLine(line)) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && isBlockquoteLine(lines[i])) {
                    val ql = lines[i].trimStart()
                    // `>` alone → empty line; `>x` / `>\tx` → strip the marker and
                    // at most one following space (CommonMark). The old
                    // `removePrefix("> ")` left `\t` and no-space variants intact
                    // so the raw marker leaked into the quoted text.
                    quoteLines.add(ql.removePrefix(">").removePrefix(" "))
                    i++
                }
                val inner = parse(quoteLines.joinToString("\n"))
                blocks.add(Block.Blockquote(inner))
                continue
            }

            // Table (must have at least header + separator). [T5/md-setext-table]
            // The separator check alone turned "Title\n---" (setext h2 in
            // CommonMark) into an empty Table(header=["Title"]) — a pipe-less
            // "---" matched isTableSeparator as a single-column separator row.
            // A real table always carries a '|' in its header or separator row,
            // so require one; otherwise fall through (paragraph + thematic
            // break), which is the documented non-support of setext headings.
            if (startsTable(lines, i)) {
                val table = parseTable(lines, i)
                if (table != null) {
                    blocks.add(table.first)
                    i = table.second
                    continue
                }
            }

            // Bullet list (-, *, +)
            val bulletMatch = Regex("^\\s{0,3}[-*+]\\s+(.*)$").find(line)
            if (bulletMatch != null) {
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val bm = Regex("^\\s{0,3}[-*+]\\s+(.*)$").find(lines[i])
                    if (bm == null) break
                    val content = bm.groupValues[1]
                    items.add(parseListItem(content))
                    i++
                    // Collect continuation lines (indented)
                    // [fix/audit-0917-b9] `.matches()` requires the WHOLE
                    // string to match "bullet marker + whitespace" — it only
                    // fired for a bare "- " with nothing after it, so an
                    // indented "- item" (a real new list item) matched the
                    // startsWith("  ") test and was swallowed as a
                    // continuation of the previous bullet's content.
                    // containsMatchIn tests the prefix the regex reads.
                    // [crash-1016] A fenced code block under a bullet is a CODE BLOCK,
                    // not continuation text: fence bodies used to leak into item
                    // content and be inline-parsed (the chat parser's list loops had
                    // the same hole; an empty `\(`\)` inside such a body crashed the
                    // renderer there). Keep the two parsers in sync.
                    while (i < lines.size && lines[i].startsWith("  ") &&
                        !Regex("^\\s{0,3}[-*+]\\s").containsMatchIn(lines[i]) &&
                        !lines[i].trimStart().startsWith("```")) {
                        items[items.lastIndex] = items.last().copy(
                            content = items.last().content + "\n" + lines[i].trimStart()
                        )
                        i++
                    }
                }
                blocks.add(Block.BulletList(items))
                continue
            }

            // Numbered list. Marker requires a real punctuation (. or )):
            // CommonMark accepts only `1.` / `1)` — a bare "4 " before a
            // word must NOT become list item #4, else a paragraph opening
            // like "4 分钟没扫完" renders as "4. 分钟没扫完" (user report).
            // Also requires whitespace (or end of line) AFTER the marker so
            // "3.14 是 π" isn't split into item "14 是 π". Digits stay
            // capped at 2 so a year/big-number paragraph ("2020 年…") is
            // never misparsed (user report); CommonMark caps at 9, we
            // deliberately go tighter.
            val numMatch = numberedListItemRegex.find(line)
            if (numMatch != null) {
                val startNum = numMatch.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<ListItem>()
                while (i < lines.size) {
                    val nm = numberedListItemRegex.find(lines[i])
                    if (nm == null) break
                    items.add(ListItem(nm.groupValues[2]))
                    i++
                    // Collect continuation lines (indented) — same shape as the
                    // bullet branch above. [dv15/B-07] Without this, a wrapped
                    // item ("1. long line" + "   still item one") ended the list
                    // at the wrap and rendered the tail as a standalone
                    // paragraph; the bullet branch has merged these since
                    // [fix/audit-0917-b9]. Two exclusions mirror the bullet
                    // branch: a deeper marker is a NEW item, and an indented
                    // fence is a CODE BLOCK rather than continuation prose
                    // ([crash-1016] — fence bodies must not reach the inline
                    // renderer).
                    while (i < lines.size && lines[i].startsWith("  ") &&
                        !numberedListMarkerRegex.containsMatchIn(lines[i]) &&
                        !lines[i].trimStart().startsWith("```")) {
                        items[items.lastIndex] = items.last().copy(
                            content = items.last().content + "\n" + lines[i].trimStart()
                        )
                        i++
                    }
                }
                blocks.add(Block.NumberedList(startNum, items))
                continue
            }

            // Standalone image / video / audio on its own line:
            //   ![alt](minis://workspace/clip.mp4)
            // Routed by extension so the media renderer can pick the right preview.
            val mediaMatch = standaloneImageRegex.find(line)
            if (mediaMatch != null) {
                val alt = mediaMatch.groupValues[1]
                val url = mediaMatch.groupValues[2]
                val blk = mediaBlockFor(alt, url)
                if (com.rikkaminis.app.BuildConfig.DEBUG) {
                    android.util.Log.d("MdParser", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}")
                }
                blocks.add(blk)
                i++
                continue
            } else if (line.contains("![") && line.contains("](")) {
                if (com.rikkaminis.app.BuildConfig.DEBUG) {
                    android.util.Log.d("MdParser", "image-like line did NOT match standalone regex: ${line.take(160)}")
                }
            }

            // Blank line — skip
            if (line.isBlank()) {
                i++
                continue
            }

            // Paragraph (collect contiguous non-blank lines that don't match other patterns)
            val paraLines = mutableListOf(line)
            i++
            while (i < lines.size) {
                val pl = lines[i]
                if (pl.isBlank() || pl.trimStart().startsWith("```") ||
                    // [dv15/B-07] Any ATX level, not just `# `: a `##`…`######`
                    // heading glued to the previous paragraph (no blank line)
                    // used to be collected as paragraph text and never became a
                    // Heading. Same predicate as the heading branch above — the
                    // two must agree or a line can be neither block.
                    // `trimStart()` mirrors the pre-fix predicate's own shape
                    // (it read the trimmed line) and the twin's `isAtxHeading(t)`;
                    // only the level-1-only part was wrong. The heading BRANCH
                    // still reads the raw line, so an indented `  ## x` splits
                    // off into its own Paragraph exactly as `  # x` always did —
                    // a break set wider than the branch is safe, a narrower one
                    // is the bug.
                    atxHeadingRegex.matches(pl.trimStart()) ||
                    isBlockquoteLine(pl) ||
                    Regex("^\\s{0,3}[-*+]\\s+").containsMatchIn(pl) ||
                    // Keep in sync with the numbered-list marker above (≤2 digits).
                    numberedListMarkerRegex.containsMatchIn(pl) ||
                    pl.matches(Regex("^\\s{0,3}([-*_])\\s*\\1\\s*\\1(\\s*\\1)*\\s*$")) ||
                    // [dv15/B-07] A table glued to a paragraph: the Table rule
                    // above only fires when the header row is a block start, so
                    // without this break the header+separator were absorbed as
                    // paragraph text and the whole table rendered as prose.
                    // Exactly the branch's own predicate ([startsTable]) — not a
                    // narrower copy — or the two paths disagree on the same two
                    // lines depending on whether a blank line precedes them.
                    startsTable(lines, i) ||
                    standaloneImageRegex.containsMatchIn(pl)
                ) break
                paraLines.add(pl)
                i++
            }
            blocks.add(Block.Paragraph(paraLines.joinToString("\n")))
        }

        return blocks
    }

    private fun parseListItem(content: String): ListItem {
        // Task list: [x] or [ ]
        if (content.startsWith("[x] ") || content.startsWith("[X] ")) {
            return ListItem(content.substring(4), checked = true)
        }
        if (content.startsWith("[ ] ")) {
            return ListItem(content.substring(4), checked = false)
        }
        return ListItem(content)
    }

    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('-')) return false
        val cells = trimmed.split('|').filter { it.isNotBlank() }
        return cells.all { it.trim().matches(Regex("^:?-+:?$")) }
    }

    private fun parseTable(lines: List<String>, startIdx: Int): Pair<Block.Table, Int>? {
        val headerLine = lines[startIdx]
        val separatorLine = lines[startIdx + 1]

        val headers = splitTableRow(headerLine)
        val sepCells = splitTableRow(separatorLine)
        if (headers.isEmpty() || sepCells.isEmpty()) return null

        val alignments = sepCells.map { cell ->
            val trimmed = cell.trim()
            when {
                trimmed.startsWith(':') && trimmed.endsWith(':') -> Alignment.CENTER
                trimmed.endsWith(':') -> Alignment.RIGHT
                else -> Alignment.LEFT
            }
        }

        var i = startIdx + 2
        val rows = mutableListOf<List<String>>()
        while (i < lines.size) {
            val row = lines[i]
            if (row.isBlank() || !row.contains('|')) break
            rows.add(splitTableRow(row))
            i++
        }

        return Block.Table(headers, alignments, rows) to i
    }

    private fun splitTableRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        // [T-minis-url-fullwidth-pipe-android] Fast path: the only reason to
        // protect a `|` from the cell split is a markdown link whose URL
        // contains a raw ASCII pipe, e.g. `[f](minis://ns/a|b.png)` — the model
        // (or a hand-authored row) can emit an un-encoded `|` inside the URL,
        // and a naive `split('|')` would chop the link in two. App-generated
        // minis:// URLs already percent-encode `|` to `%7C`, so this is purely
        // defence-in-depth. Rows WITHOUT a `](` link keep the original exact
        // `split('|')` behaviour verbatim — important because the paren-aware
        // walk below would otherwise swallow a `|` after an unbalanced `(` in a
        // plain text cell (e.g. `| a (note | b |`). Fullwidth `｜` (U+FF5C) is
        // never split by either path since `split('|')` matches only U+007C.
        if (!trimmed.contains("](")) {
            return trimmed.split('|').map { it.trim() }
        }
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in trimmed) {
            if (ch == '(' && depth == 0) depth++
            else if (ch == ')' && depth > 0) depth--
            if (ch == '|' && depth == 0) {
                cells.add(current.toString().trim())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    // ─── Math extraction ──────────────────────────────────────────────────────
    //
    // Walks the source character-by-character, swallowing four math delimiters:
    //
    //   $$ … $$    display math
    //   \[ … \]    display math (LaTeX-style)
    //   $ … $      inline math
    //   \( … \)    inline math (LaTeX-style)
    //
    // Fenced code blocks and inline code spans are skipped verbatim so the
    // delimiters inside them survive (chat transcripts often paste raw
    // markdown source). Each match is replaced with a sentinel like
    // ￼ MATH<n> ￼ so the placeholder cannot collide with normal
    // Markdown syntax (￼ = Object Replacement Character, never appears
    // in user prose). Mirrors iOS MarkdownMathExtractor.extract verbatim.

    private fun extractMath(markdown: String): Pair<String, List<MathSpan>> {
        val spans = mutableListOf<MathSpan>()
        val out = StringBuilder()
        val chars = markdown
        val n = chars.length
        var i = 0

        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0

        fun atLineStart(idx: Int): Boolean = idx == 0 || chars[idx - 1] == '\n' || chars[idx - 1] == '\r'

        while (i < n) {
            // Detect opening fence at line start
            if (!inFence && atLineStart(i)) {
                val c = chars[i]
                if (c == '`' || c == '~') {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == c) { fl++; j++ }
                    if (fl >= 3) {
                        inFence = true
                        fenceChar = c
                        fenceLen = fl
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
            }

            if (inFence) {
                if (atLineStart(i) && chars[i] == fenceChar) {
                    var fl = 0
                    var j = i
                    while (j < n && chars[j] == fenceChar) { fl++; j++ }
                    if (fl >= fenceLen) {
                        inFence = false
                        while (i < n && chars[i] != '\n') { out.append(chars[i]); i++ }
                        if (i < n) { out.append(chars[i]); i++ }
                        continue
                    }
                }
                out.append(chars[i]); i++
                continue
            }

            // Inline code spans — copy verbatim, including the dollar signs inside.
            if (chars[i] == '`') {
                var run = 0
                var j = i
                while (j < n && chars[j] == '`') { run++; j++ }
                var k = j
                var found = false
                while (k <= n - run) {
                    var match = 0
                    while (k + match < n && chars[k + match] == '`') match++
                    if (match == run) {
                        for (idx in i until k + match) out.append(chars[idx])
                        i = k + match
                        found = true
                        break
                    }
                    k += if (match > 0) match else 1
                }
                if (found) continue
                for (idx in i until j) out.append(chars[idx])
                i = j
                continue
            }

            // \$ — preserve escaped dollar verbatim.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '$') {
                out.append(chars[i]); out.append(chars[i + 1])
                i += 2
                continue
            }

            // \[ … \] — display math.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '[') {
                val end = findClose(chars, i + 2, "\\]")
                if (end != null) {
                    val latex = chars.substring(i + 2, end)
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // \( … \) — inline math.
            if (chars[i] == '\\' && i + 1 < n && chars[i + 1] == '(') {
                val end = findClose(chars, i + 2, "\\)")
                if (end != null) {
                    val latex = chars.substring(i + 2, end)
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // $$ … $$ — display math.
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] == '$') {
                val end = findDoubleDollar(chars, i + 2)
                if (end != null) {
                    val latex = chars.substring(i + 2, end)
                    spans.add(MathSpan(makePlaceholder(spans.size), latex, true))
                    out.append(spans.last().placeholder)
                    i = end + 2
                    continue
                }
            }

            // $ … $ — inline math (with currency-skip heuristic).
            if (chars[i] == '$' && i + 1 < n && chars[i + 1] != '$' && chars[i + 1] != ' ') {
                val end = findSingleDollar(chars, i + 1)
                if (end != null) {
                    val latex = chars.substring(i + 1, end)
                    if (looksLikeMath(latex)) {
                        spans.add(MathSpan(makePlaceholder(spans.size), latex, false))
                        out.append(spans.last().placeholder)
                        i = end + 1
                        continue
                    }
                }
            }

            out.append(chars[i])
            i++
        }

        return out.toString() to spans
    }

    private fun makePlaceholder(idx: Int): String = "${ORC}MATH$idx$ORC"

    private fun findClose(chars: String, from: Int, close: String): Int? {
        val n = chars.length
        var i = from
        while (i <= n - close.length) {
            var match = true
            for (j in close.indices) {
                if (chars[i + j] != close[j]) { match = false; break }
            }
            if (match) return i
            i++
        }
        return null
    }

    private fun findDoubleDollar(chars: String, from: Int): Int? {
        val n = chars.length
        var i = from
        while (i < n - 1) {
            if (chars[i] == '$' && chars[i + 1] == '$') return i
            i++
        }
        return null
    }

    private fun findSingleDollar(chars: String, from: Int): Int? {
        val n = chars.length
        var i = from
        while (i < n) {
            if (chars[i] == '\\' && i + 1 < n) { i += 2; continue }
            if (chars[i] == '$' && (i == 0 || chars[i - 1] != ' ')) return i
            if (chars[i] == '\n') return null
            i++
        }
        return null
    }

    /**
     * Currency-skip heuristic: $5 / $10.99 should stay as plain text. Treat
     * the `$ … $` content as math only if it has a math-ish character
     * (`\\^_{}` / common big-symbol unicode) or is longer than 2 chars (so
     * `x+y` qualifies but bare digits like `5` do not). Mirrors iOS.
     */
    private fun looksLikeMath(content: String): Boolean {
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
        // T208 Layer B: previous heuristic rejected single-letter math like
        // `$c$` or `$i$` — `length > 2` returned false and the literal
        // dollar-wrapped text leaked into the rendered output. Any of:
        //   - obvious LaTeX glyphs (`\`, `^`, `_`, `{`, `}`, integrals…)
        //   - short alphanumeric content with no whitespace and no leading
        //     digit (currency like `$5` filtered out by the leading-digit
        //     check; `$5.99` further filtered by the dot rule below)
        // qualifies as math. Mirrors iOS MarkdownMathExtractor.looksLikeMath.
        val mathChars = charArrayOf('\\', '^', '_', '{', '}', '∫', '∑', '∏', '√', '+', '-', '=', '<', '>')
        for (c in content) {
            if (c in mathChars) return true
        }
        // Currency heuristic: `$5`, `$5.99`, `$1,000` look numeric. Skip.
        // [r3-c] Now a SECOND net, not the only one: `isCurrencyShaped` above
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
    private fun isCurrencyShaped(content: String): Boolean {
        if (!content.first().isDigit()) return false
        for (c in content) {
            if (c.isLetter()) return false
            if (c in NON_CURRENCY_MATH_GLYPHS) return false
        }
        return true
    }

    /**
     * Walk the AST replacing math placeholders. Display-math placeholders
     * promote (or replace) their containing paragraph to a [Block.MathBlock];
     * inline placeholders remain in the paragraph text so the inline parser
     * can render them by looking up the placeholder in the [MathSpan] list.
     */
    private fun restoreMath(blocks: List<Block>, spans: List<MathSpan>): List<Block> {
        if (spans.isEmpty()) return blocks
        val byPlaceholder = spans.associateBy { it.placeholder }
        return blocks.flatMap { restoreBlock(it, byPlaceholder) }
    }

    private fun restoreBlock(block: Block, map: Map<String, MathSpan>): List<Block> {
        return when (block) {
            is Block.Paragraph -> restoreParagraph(block.content, map)
            is Block.Heading -> listOf(block)  // headings keep their inline placeholders
            is Block.Blockquote -> listOf(Block.Blockquote(block.blocks.flatMap { restoreBlock(it, map) }))
            is Block.BulletList,
            is Block.NumberedList,
            is Block.Table,
            is Block.CodeBlock,
            is Block.MathBlock,
            is Block.ThematicBreak,
            is Block.Image,
            is Block.Video,
            is Block.Audio -> listOf(block)
        }
    }

    /**
     * Splits a paragraph that contains math placeholders. Each display-math
     * placeholder breaks the paragraph and emits a [Block.MathBlock] of its
     * own; inline placeholders stay embedded so the inline parser can render
     * them through KaTeX. A paragraph that contained nothing but a single
     * display-math placeholder collapses to one MathBlock with no surrounding
     * Paragraph stub.
     */
    private fun restoreParagraph(content: String, map: Map<String, MathSpan>): List<Block> {
        if (!content.contains(ORC)) return listOf(Block.Paragraph(content))

        val out = mutableListOf<Block>()
        val buf = StringBuilder()
        val regex = Regex("$ORC" + "MATH(\\d+)" + "$ORC")
        var lastEnd = 0
        for (match in regex.findAll(content)) {
            val full = match.value
            val span = map[full] ?: continue
            // Append text before this placeholder.
            if (match.range.first > lastEnd) {
                buf.append(content, lastEnd, match.range.first)
            }
            if (span.isBlock) {
                // Flush any accumulated text as a paragraph, then emit the math block.
                if (buf.isNotBlank()) {
                    out.add(Block.Paragraph(buf.toString().trimEnd()))
                }
                buf.clear()
                out.add(Block.MathBlock(span.latex))
            } else {
                // Keep inline placeholder in-stream — inline pass will render it.
                buf.append(full)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < content.length) buf.append(content, lastEnd, content.length)
        if (buf.isNotBlank()) out.add(Block.Paragraph(buf.toString().trimEnd()))
        // Empty paragraph (only whitespace remained) — drop entirely.
        return if (out.isEmpty()) listOf(Block.Paragraph(content)) else out
    }
}
