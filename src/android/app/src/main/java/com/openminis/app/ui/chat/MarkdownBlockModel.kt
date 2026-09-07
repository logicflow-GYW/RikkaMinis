package com.openminis.app.ui.chat

// [refactor/split-streaming-markdown] Batch 2: the markdown BLOCK MODEL layer
// moved VERBATIM from StreamingMarkdownText.kt:
//   - MdBlock sealed class (Paragraph/Heading/CodeBlock/BlockQuote/lists/Table/
//     Image/Video/Audio/MathDisplay)  [private -> internal: renderers use it]
//   - the full block parser (parseMarkdownBlocks + regex bank + media/math/
//     list/table helpers)  [internal: the runBlocking bridge in the render
//     file calls it; everything else stays private]
// (MarkdownParseCaches stayed in StreamingMarkdownText.kt — it depends on the
// inline-parse layer (parseInline / safeInlineSplitOffset / MdColors) and is
// not model-layer.)
// No Compose dependency except the suspend parser's cooperative cancellation
// (kotlin.coroutines). JVM-testable in isolation.

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

// ─── Block model ────────────────────────────────────────────────────────────

internal sealed class MdBlock(val raw: String) {
    class Paragraph(raw: String) : MdBlock(raw)
    class Heading(raw: String, val level: Int, val text: String) : MdBlock(raw)
    class CodeBlock(raw: String, val language: String, val code: String) : MdBlock(raw)
    class BlockQuote(raw: String, val innerBlocks: List<MdBlock>) : MdBlock(raw)
    class UnorderedList(raw: String, val items: List<ListItem>) : MdBlock(raw)
    class OrderedList(raw: String, val items: List<ListItem>, val startNum: Int = 1) : MdBlock(raw)
    class TaskList(raw: String, val items: List<TaskItem>) : MdBlock(raw)
    class HorizontalRule(raw: String) : MdBlock(raw)
    class Table(raw: String, val headers: List<String>, val rows: List<List<String>>) : MdBlock(raw)
    class Image(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Video(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Audio(raw: String, val alt: String, val url: String) : MdBlock(raw)
    /** T155: display-mode LaTeX rendered via KaTeX (`$$…$$` or `\[…\]`). */
    class MathDisplay(raw: String, val latex: String) : MdBlock(raw)
}

private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

private fun mediaBlockFrom(raw: String, alt: String, url: String): MdBlock {
    // Classify by the last path segment's extension. Decoding first means a
    // filename like `foo%23China.mp4` or `foo#China.mp4` still resolves to
    // `.mp4` instead of being swallowed by `substringBefore('#')`.
    val lastSeg = url.substringAfterLast('/')
    val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
    val ext = decoded.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in nativeVideoExts -> MdBlock.Video(raw, alt, url)
        in nativeAudioExts -> MdBlock.Audio(raw, alt, url)
        else -> MdBlock.Image(raw, alt, url)
    }
}

/** Matches any `![alt](url)` anywhere in a line. Non-greedy to handle multiple per line. */
private val inlineMediaRegex = Regex("""!\[([^\]\n]*)]\(([^)\s]+)\)""")

// ─── Hoisted block-parser regexes ─────────────────────────────────────────────
//
// parseMarkdownBlocks runs on every recompose during streaming — once per
// chunk, often dozens of times a second. Constructing each Regex inline
// triggered Pattern.compile (an ICU JNI call) on the main thread for every
// pattern, every chunk, on every block; long markdown documents pinned the
// main thread inside Pattern.compile long enough that the OS posted ANRs
// (>5 s waited for input). Hoisting to file-level vals compiles each pattern
// exactly once, at class init, so the streaming hot path is allocation-free
// for these matches.
private val thematicBreakRegex = Regex("^[-*_]{3,}\\s*$")
private val standaloneImageLineRegex = Regex("^!\\[.*]\\(.*\\)\\s*$")
private val imageMatchRegex = Regex("^!\\[(.*)\\]\\((.*)\\)")
private val tableSeparatorRegex = Regex("^\\|?[\\s\\-:|]+\\|?$")
private val taskListItemRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+.*")
private val taskListPrefixRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+")
private val bulletListItemRegex = Regex("^[-*+]\\s+.*")
private val bulletListPrefixRegex = Regex("^[-*+]\\s+")
private val numberedListItemRegex = Regex("^\\d+[.)\\s]+.*")
private val numberedListStartRegex = Regex("^(\\d+)")
private val numberedListPrefixRegex = Regex("^\\d+[.)\\s]+")

/**
 * A blockquote line must be `>` followed by a space, a tab, or end of line.
 * Anything else (e.g. `>foo`, `>5`, `>=`) is regular prose — likely shell
 * output or a comparison emitted by the LLM, not an intentional quote.
 */
private fun isBlockquoteLine(trimmed: String): Boolean {
    if (!trimmed.startsWith(">")) return false
    if (trimmed.length == 1) return true
    val next = trimmed[1]
    return next == ' ' || next == '\t'
}

/**
 * Split a paragraph's raw text at inline `![alt](url)` occurrences, extracting
 * video/audio references into standalone MdBlock.Video/Audio blocks. Image
 * references stay inline (Compose doesn't render inline bitmap attachments in
 * text here, but the `[alt]` link fallback is acceptable for images).
 *
 * Why: LLMs very commonly emit `"Here's the video: ![robot](minis://attachments/x.mp4)"`
 * on a single line alongside explanatory text. Without this split, the line
 * becomes one Paragraph and the video markdown is rendered as just a blue
 * `[alt]` link — no preview card, no tap-to-play.
 */
private fun splitParagraphOnInlineMedia(text: String): List<MdBlock> {
    val matches = inlineMediaRegex.findAll(text).toList()
    if (matches.isEmpty()) return listOf(MdBlock.Paragraph(text))

    // Pre-check: only split when at least one match is a media (video/audio)
    // that we can render as a card. Plain image-extension matches stay inline.
    val hasMediaExt = matches.any { m ->
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        ext in nativeVideoExts || ext in nativeAudioExts
    }
    if (!hasMediaExt) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (m in matches) {
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        val isMedia = ext in nativeVideoExts || ext in nativeAudioExts
        if (!isMedia) continue

        val preceding = text.substring(cursor, m.range.first).trim('\n', ' ', '\t')
        if (preceding.isNotBlank()) result.add(MdBlock.Paragraph(preceding))
        val alt = m.groupValues[1]
        result.add(mediaBlockFrom(m.value, alt, url))
        cursor = m.range.last + 1
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

/**
 * T208-4 part 3: heuristic for "wide" inline math that should be promoted
 * to a display-mode block instead of stuffed into Compose's fixed-size
 * `InlineTextContent` placeholder.
 *
 * Wide constructs (matrices, aligned, multi-row \\, large \frac, long
 * formulas) overflow the inline slot — Compose's Placeholder API can't
 * resize per-formula, so the only options inside an inline span are
 * "clip" or "scale-down to unreadable". Promoting to a display block
 * lets it render at its natural size on its own line (same shape that
 * Markwon and MathJax adopt for `\displaystyle` / `\begin{...}`).
 *
 * Short inline math (`$x$`, `$x_i$`, `$f(x)=5$`) stays inline so prose
 * still flows naturally.
 */
private fun looksLikeWideMath(latex: String): Boolean {
    if (latex.length > 30) return true
    if (latex.contains("\\begin{")) return true        // bmatrix, pmatrix, aligned, cases…
    if (latex.contains("\\\\")) return true            // explicit LaTeX line break / matrix row sep
    if (latex.contains("\\frac")) return true          // fractions render two-line
    if (latex.contains("\\sum") || latex.contains("\\int") || latex.contains("\\prod")) return true
    if (latex.contains("\\sqrt")) return true
    if (latex.contains("\\mathbf{") || latex.contains("\\mathbb{") || latex.contains("\\mathcal{")) return true
    if (latex.contains("\\overline") || latex.contains("\\underline")) return true
    if (latex.contains("\\binom")) return true
    return false
}

/**
 * T208-4 part 3: split a paragraph at *wide* inline math spans, promoting
 * each one to a `MathDisplay` block. Mirrors the inline-media split: the
 * text before the math becomes a Paragraph, the math becomes its own
 * block, the trailing text becomes a Paragraph. Short math stays inline.
 *
 * Recognises the same delimiters as `parseInline`: `\(...\)` and
 * single-`$...$` (skipping `$$` which is already a block-level form).
 *
 * Walking the string by hand (rather than regex) so escape rules and
 * the "stop at newline" behavior of `findInlineMathClose` stay in sync
 * with the inline parser.
 */
private fun splitParagraphOnWideMath(text: String): List<MdBlock> {
    if (!text.contains('\\') && !text.contains('$')) return listOf(MdBlock.Paragraph(text))

    data class Span(val start: Int, val end: Int, val latex: String)
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        // Skip escaped chars inside prose so `\$5` doesn't open a math span.
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                val latex = text.substring(i + 2, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 2, latex))
                }
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 1, latex))
                }
                i = end + 1; continue
            }
        }
        i++
    }
    if (spans.isEmpty()) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (s in spans) {
        val before = text.substring(cursor, s.start).trim('\n', ' ', '\t')
        if (before.isNotBlank()) result.add(MdBlock.Paragraph(before))
        result.add(MdBlock.MathDisplay(text.substring(s.start, s.end), s.latex))
        cursor = s.end
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

internal data class ListItem(val text: String, val children: List<MdBlock> = emptyList())
internal data class TaskItem(val checked: Boolean, val text: String)

// ─── Block parser ───────────────────────────────────────────────────────────

internal suspend fun parseMarkdownBlocks(content: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = content.lines()
    var i = 0
    // Counter so we don't query coroutineContext on EVERY line (small but
    // measurable allocation overhead at ~thousands of lines per pass).
    var sinceLastCheck = 0

    while (i < lines.size) {
        if (sinceLastCheck >= 64) {
            coroutineContext.ensureActive()
            sinceLastCheck = 0
        }
        sinceLastCheck++
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            // T155: display math `$$...$$` (single-line or multi-line) and `\[...\]`
            trimmed.startsWith("$$") -> {
                val rest = trimmed.removePrefix("$$")
                val inlineEnd = rest.indexOf("$$")
                if (inlineEnd >= 0) {
                    // Same-line `$$ … $$`
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    // Multi-line: scan forward until a line containing `$$`
                    val rawLines = mutableListOf(line)
                    val mathLines = mutableListOf<String>()
                    if (rest.isNotEmpty()) mathLines.add(rest)
                    i++
                    while (i < lines.size) {
                        rawLines.add(lines[i])
                        val close = lines[i].indexOf("$$")
                        if (close >= 0) {
                            val pre = lines[i].substring(0, close)
                            if (pre.isNotEmpty()) mathLines.add(pre)
                            i++
                            break
                        }
                        mathLines.add(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.MathDisplay(rawLines.joinToString("\n"), mathLines.joinToString("\n").trim()))
                }
            }
            trimmed.startsWith("\\[") -> {
                val rest = trimmed.removePrefix("\\[")
                val inlineEnd = rest.indexOf("\\]")
                if (inlineEnd >= 0) {
                    val latex = rest.substring(0, inlineEnd).trim()
                    blocks.add(MdBlock.MathDisplay(line, latex))
                    i++
                } else {
                    val rawLines = mutableListOf(line)
                    val mathLines = mutableListOf<String>()
                    if (rest.isNotEmpty()) mathLines.add(rest)
                    i++
                    while (i < lines.size) {
                        rawLines.add(lines[i])
                        val close = lines[i].indexOf("\\]")
                        if (close >= 0) {
                            val pre = lines[i].substring(0, close)
                            if (pre.isNotEmpty()) mathLines.add(pre)
                            i++
                            break
                        }
                        mathLines.add(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.MathDisplay(rawLines.joinToString("\n"), mathLines.joinToString("\n").trim()))
                }
            }
            // Fenced code block
            trimmed.startsWith("```") -> {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                val rawLines = mutableListOf(line)
                i++
                while (i < lines.size) {
                    rawLines.add(lines[i])
                    if (lines[i].trimStart().startsWith("```")) { i++; break }
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeBlock(rawLines.joinToString("\n"), lang, codeLines.joinToString("\n")))
            }

            // Heading
            trimmed.startsWith("#") && (trimmed.length == 1 || trimmed[trimmed.indexOfFirst { it != '#' }.coerceAtLeast(0)] == ' ') -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                val text = trimmed.drop(level).trimStart()
                blocks.add(MdBlock.Heading(line, level, text))
                i++
            }

            // Horizontal rule
            trimmed.matches(thematicBreakRegex) -> {
                blocks.add(MdBlock.HorizontalRule(line))
                i++
            }

            // Image / Video / Audio: ![alt](url) on its own line — routed by file extension.
            trimmed.matches(standaloneImageLineRegex) -> {
                val match = imageMatchRegex.find(trimmed)
                if (match != null) {
                    val alt = match.groupValues[1]
                    val url = match.groupValues[2]
                    val blk = mediaBlockFrom(line, alt, url)
                    android.util.Log.d("MdStream", "media match: alt=\"$alt\" url=$url -> ${blk::class.simpleName}")
                    blocks.add(blk)
                }
                i++
            }

            // Table (line contains | and next line is separator)
            trimmed.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(tableSeparatorRegex) -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].contains('|') ||
                        lines[i].trim().matches(tableSeparatorRegex))) {
                    tableLines.add(lines[i])
                    i++
                }
                val (headers, rows) = parseTable(tableLines)
                blocks.add(MdBlock.Table(tableLines.joinToString("\n"), headers, rows))
            }

            // Blockquote — strict CommonMark match: `>` followed by space or end
            // of line. The looser `startsWith(">")` accidentally swallowed
            // shell-output prompts like `>foo`, comparisons (`>5`, `>=`), and
            // generally any inline `>`-led token an LLM happens to emit, which
            // wrapped innocent prose in an orange leading rule (T117).
            isBlockquoteLine(trimmed) -> {
                val rawLines = mutableListOf<String>()
                val innerLines = mutableListOf<String>()
                while (i < lines.size && isBlockquoteLine(lines[i].trimStart())) {
                    rawLines.add(lines[i])
                    innerLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                val innerBlocks = parseMarkdownBlocks(innerLines.joinToString("\n"))
                blocks.add(MdBlock.BlockQuote(rawLines.joinToString("\n"), innerBlocks))
            }

            // Task list: - [x] or - [ ]
            trimmed.matches(taskListItemRegex) -> {
                val items = mutableListOf<TaskItem>()
                val rawLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().matches(taskListItemRegex)) {
                    rawLines.add(lines[i])
                    val t = lines[i].trimStart()
                    val checked = t.contains("[x]", ignoreCase = true)
                    val text = t.replaceFirst(taskListPrefixRegex, "")
                    items.add(TaskItem(checked, text))
                    i++
                }
                blocks.add(MdBlock.TaskList(rawLines.joinToString("\n"), items))
            }

            // Unordered list
            trimmed.matches(bulletListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(bulletListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        // Continuation or nested — append to last item
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(bulletListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.UnorderedList(rawLines.joinToString("\n"), items))
            }

            // Ordered list
            trimmed.matches(numberedListItemRegex) -> {
                val items = mutableListOf<ListItem>()
                val rawLines = mutableListOf<String>()
                val startMatch = numberedListStartRegex.find(trimmed)
                val startNum = startMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val baseIndent = line.length - trimmed.length
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    val indent = l.length - t.length
                    if (t.isEmpty()) { i++; continue }
                    if (!t.matches(numberedListItemRegex) && indent <= baseIndent) break
                    if (indent > baseIndent) {
                        if (items.isNotEmpty()) {
                            val last = items.last()
                            items[items.lastIndex] = last.copy(text = last.text + "\n" + t)
                        }
                    } else {
                        rawLines.add(l)
                        items.add(ListItem(t.replaceFirst(numberedListPrefixRegex, "")))
                    }
                    i++
                }
                blocks.add(MdBlock.OrderedList(rawLines.joinToString("\n"), items, startNum))
            }

            // Empty line
            trimmed.isEmpty() -> { i++ }

            // Paragraph
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val l = lines[i]
                    val t = l.trimStart()
                    if (t.isEmpty() || t.startsWith("#") || t.startsWith("```") ||
                        isBlockquoteLine(t) || t.matches(thematicBreakRegex) ||
                        t.matches(bulletListItemRegex) || t.matches(numberedListItemRegex) ||
                        t.matches(standaloneImageLineRegex) ||
                        (t.contains('|') && i + 1 < lines.size &&
                            lines[i + 1].trim().matches(tableSeparatorRegex))
                    ) break
                    paraLines.add(l)
                    i++
                }
                val text = paraLines.joinToString("\n")
                if (text.isNotBlank()) {
                    // Split out inline media (`![alt](url)` that's a .mp4/.mp3/etc)
                    // so videos/audio that the LLM emits adjacent to text still
                    // get their dedicated preview card. Image extensions stay
                    // inline (rendered as `[alt]` link) since Compose's inline
                    // text-image attachment path isn't implemented here.
                    //
                    // T208-4 part 3: after the media split, run a second pass
                    // that promotes "wide" inline math (matrices, multi-row
                    // \\, large \frac, long formulas) to standalone display
                    // blocks. Compose's `InlineTextContent` placeholder is
                    // fixed-size — wide formulas inside it either clip or
                    // scale to unreadable. Splitting at parse time lets each
                    // wide span render at its natural display-mode size on
                    // its own line; short inline math (`$x_i$`) stays inline.
                    val mediaBlocks = splitParagraphOnInlineMedia(text)
                    for (b in mediaBlocks) {
                        if (b is MdBlock.Paragraph) {
                            blocks.addAll(splitParagraphOnWideMath(b.raw))
                        } else {
                            blocks.add(b)
                        }
                    }
                }
            }
        }
    }
    return blocks
}

private fun parseTable(lines: List<String>): Pair<List<String>, List<List<String>>> {
    val headers = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.matches(tableSeparatorRegex)) continue
        // T308: Strip the leading/trailing pipe (if present) before splitting.
        // The previous `.filter { isNotEmpty() }` swallowed legitimate empty
        // cells like the first column of `| | Manus | TikTok |`, leaving the
        // header with fewer columns than body rows and breaking alignment.
        val core = trimmed.removePrefix("|").removeSuffix("|")
        val cells = core.split("|").map { it.trim() }
        if (headers.isEmpty()) headers.addAll(cells) else rows.add(cells)
    }
    return headers to rows
}
