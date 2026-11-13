package com.rikkaminis.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Clipboard helpers that mirror the iOS chat-message context menu actions
 * (`SelectableMarkdownTextView` → `copyMarkdown` / `copyRichText`).
 *
 * iOS exports rich text as RTF via `UIPasteboard.setData(rtfData, forPasteboardType: "public.rtf")`.
 * Android's pasteboard equivalent is [ClipData.newHtmlText], which carries both
 * plain-text and HTML payloads — Notes, Gmail, Docs, etc. all consume the HTML
 * variant when pasting "with formatting." We render the markdown to a
 * lightweight HTML subset locally instead of pulling in a parser dependency.
 *
 * Three flavors:
 *   - [copyPlain]    — markdown stripped to readable plain text (default Copy)
 *   - [copyMarkdown] — raw markdown source verbatim
 *   - [copyRichText] — HTML+plain dual payload for paste-with-formatting
 */
object MarkdownClipboard {

    // ─── [sweep1014-P11] compiled-once patterns ───────────────────────────
    // Every pattern below used to be written as `Regex(...)` at the point of
    // use, so a line-by-line walk (and every inline span inside it) paid a
    // fresh Pattern.compile — copying a long answer ran hundreds of them on
    // the UI thread. Hoisted verbatim: not one character of any pattern
    // changed, only when it is compiled.
    private val HEADING_LINE = Regex("^(#{1,6})\\s+(.*)$")
    private val FENCE_LINE = Regex("^(```|~~~)(.*)$")
    private val UNORDERED_ITEM = Regex("^([-*+])\\s+(.*)$")
    private val ORDERED_ITEM = Regex("^(\\d+)\\.\\s+(.*)$")
    private val HORIZONTAL_RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-{3,}.*$")

    private val INLINE_CODE = Regex("`([^`]+)`")
    private val IMAGE_PLAIN = Regex("!\\[([^\\]]*)\\]\\([^)]*\\)")
    private val IMAGE_HTML = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")

    private val BOLD_ITALIC_STAR = Regex("\\*\\*\\*(.+?)\\*\\*\\*")
    private val BOLD_ITALIC_UNDERSCORE = Regex("___(.+?)___")
    private val BOLD_STAR = Regex("\\*\\*(.+?)\\*\\*")
    private val BOLD_UNDERSCORE = Regex("__(.+?)__")
    private val ITALIC_STAR = Regex("(?<!\\*)\\*(?!\\*)([^*\\n]+)\\*")
    private val ITALIC_UNDERSCORE = Regex("(?<!_)_(?!_)([^_\\n]+)_")
    private val STRIKETHROUGH = Regex("~~(.+?)~~")

    // Code-span sentinel. Value-identical to the previous inline literal
    // (which held two raw NUL bytes in this source file); written as escapes
    // here so the file stays plain UTF-8 text.
    private val CODE_PLACEHOLDER: String = "\u0000CODE\u0000"
    private val CODE_SPAN = Regex(CODE_PLACEHOLDER + "(\\d+)" + CODE_PLACEHOLDER)

    fun copyPlain(context: Context, markdown: String, label: String = "Message") {
        val plain = markdownToPlainText(markdown)
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, plain))
    }

    fun copyMarkdown(context: Context, markdown: String, label: String = "Markdown") {
        clipboard(context).setPrimaryClip(ClipData.newPlainText(label, markdown))
    }

    fun copyRichText(context: Context, markdown: String, label: String = "Rich Text") {
        val plain = markdownToPlainText(markdown)
        val html = markdownToHtml(markdown)
        clipboard(context).setPrimaryClip(ClipData.newHtmlText(label, plain, html))
    }

    private fun clipboard(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Strip markdown syntax, leaving the visible reading order intact. Goals:
     *   - emphasis/strong/strike markers removed, content kept
     *   - inline code / fenced code unwrapped (content preserved verbatim)
     *   - link `[text](url)` → `text` (drop URL, matches iOS Copy behavior)
     *   - images `![alt](url)` → `alt`
     *   - heading `#`, blockquote `>`, list bullets `- * +` and ordered `1.` stripped
     *   - tables flattened to ` | ` separated lines (good enough for paste targets that don't render markdown)
     */
    fun markdownToPlainText(markdown: String): String {
        val out = StringBuilder()
        val lines = markdown.lines()
        var i = 0
        var inFence = false
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                i++; continue
            }
            if (inFence) {
                out.append(line).append('\n')
                i++; continue
            }
            // Heading
            val heading = HEADING_LINE.find(trimmed)
            if (heading != null) {
                out.append(stripInline(heading.groupValues[2])).append('\n')
                i++; continue
            }
            // Blockquote
            if (trimmed.startsWith(">")) {
                out.append(stripInline(trimmed.removePrefix(">").trimStart())).append('\n')
                i++; continue
            }
            // Unordered list
            val ul = UNORDERED_ITEM.find(trimmed)
            if (ul != null) {
                out.append("• ").append(stripInline(ul.groupValues[2])).append('\n')
                i++; continue
            }
            // Ordered list
            val ol = ORDERED_ITEM.find(trimmed)
            if (ol != null) {
                out.append(ol.groupValues[1]).append(". ")
                    .append(stripInline(ol.groupValues[2])).append('\n')
                i++; continue
            }
            // Horizontal rule
            if (HORIZONTAL_RULE.matches(trimmed)) {
                out.append('\n'); i++; continue
            }
            // Table separator row (|---|---|): drop entirely.
            if (TABLE_SEPARATOR.matches(trimmed) && trimmed.contains('-')) {
                i++; continue
            }
            // Generic line — strip inline markers
            out.append(stripInline(line)).append('\n')
            i++
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * Minimal markdown → HTML for paste-with-formatting. Covers the syntax
     * the chat actually produces. Not a full CommonMark renderer; we lean on
     * reader-side leniency (Gmail/Notes/Docs all tolerate sparse HTML).
     */
    fun markdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        sb.append("<html><body>")
        val lines = markdown.lines()
        var i = 0
        var inFence = false
        var fenceLang: String? = null
        val fenceBuf = StringBuilder()
        var listType: String? = null  // "ul" | "ol"
        fun closeList() {
            if (listType != null) {
                sb.append("</$listType>")
                listType = null
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            // Fenced code
            val fenceMatch = FENCE_LINE.find(trimmed)
            if (fenceMatch != null) {
                if (!inFence) {
                    closeList()
                    inFence = true
                    fenceLang = fenceMatch.groupValues[2].trim().ifEmpty { null }
                    fenceBuf.clear()
                } else {
                    inFence = false
                    sb.append("<pre><code")
                    fenceLang?.let { sb.append(" class=\"language-").append(escapeHtml(it)).append("\"") }
                    sb.append(">").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
                    fenceBuf.clear()
                }
                i++; continue
            }
            if (inFence) {
                fenceBuf.append(line).append('\n')
                i++; continue
            }
            // Blank line: close paragraph context
            if (trimmed.isEmpty()) {
                closeList()
                i++; continue
            }
            // Heading
            val heading = HEADING_LINE.find(trimmed)
            if (heading != null) {
                closeList()
                val level = heading.groupValues[1].length
                sb.append("<h").append(level).append(">")
                    .append(inlineToHtml(heading.groupValues[2]))
                    .append("</h").append(level).append(">")
                i++; continue
            }
            // Blockquote
            if (trimmed.startsWith(">")) {
                closeList()
                sb.append("<blockquote>")
                    .append(inlineToHtml(trimmed.removePrefix(">").trimStart()))
                    .append("</blockquote>")
                i++; continue
            }
            // Horizontal rule
            if (HORIZONTAL_RULE.matches(trimmed)) {
                closeList(); sb.append("<hr/>"); i++; continue
            }
            // Unordered list
            val ul = UNORDERED_ITEM.find(trimmed)
            if (ul != null) {
                if (listType != "ul") { closeList(); sb.append("<ul>"); listType = "ul" }
                sb.append("<li>").append(inlineToHtml(ul.groupValues[2])).append("</li>")
                i++; continue
            }
            // Ordered list
            val ol = ORDERED_ITEM.find(trimmed)
            if (ol != null) {
                if (listType != "ol") { closeList(); sb.append("<ol>"); listType = "ol" }
                sb.append("<li>").append(inlineToHtml(ol.groupValues[2])).append("</li>")
                i++; continue
            }
            // Plain paragraph line
            closeList()
            sb.append("<p>").append(inlineToHtml(line)).append("</p>")
            i++
        }
        closeList()
        if (inFence && fenceBuf.isNotEmpty()) {
            sb.append("<pre><code>").append(escapeHtml(fenceBuf.toString().trimEnd('\n'))).append("</code></pre>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /** Strip inline markdown markers, return readable text. */
    private fun stripInline(s: String): String {
        // [r8-C-F05] Lift code spans out FIRST, exactly like [inlineToHtml].
        // Removing only the backticks left the code's CONTENT in place to be
        // eaten by the emphasis / link regexes: `__init__` → `init`,
        // `fn[0](x)` → `fn`, `a*b*c` → `a b c`, `snake_case` → `snakecase`.
        // The KDoc above [markdownToPlainText] promises inline code is
        // "unwrapped (content preserved verbatim)", so the code is parked in a
        // placeholder and restored byte-for-byte after every other rule ran.
        val codes = mutableListOf<String>()
        var t = INLINE_CODE.replace(s) { m ->
            codes.add(m.groupValues[1])
            "${CODE_PLACEHOLDER}${codes.size - 1}${CODE_PLACEHOLDER}"
        }
        // Images: ![alt](url) → alt
        t = IMAGE_PLAIN.replace(t, "$1")
        // Links: [text](url) → text
        t = LINK.replace(t, "$1")
        // Bold/italic/strike — order matters: longest delimiter first.
        t = BOLD_ITALIC_STAR.replace(t, "$1")
        t = BOLD_ITALIC_UNDERSCORE.replace(t, "$1")
        t = BOLD_STAR.replace(t, "$1")
        t = BOLD_UNDERSCORE.replace(t, "$1")
        t = ITALIC_STAR.replace(t, "$1")
        t = ITALIC_UNDERSCORE.replace(t, "$1")
        t = STRIKETHROUGH.replace(t, "$1")
        // Restore code spans verbatim. [audit-0917] getOrNull, not codes[i]:
        // text that happens to contain the literal placeholder must not throw
        // or substitute the wrong span — out-of-range matches stay as-is.
        t = CODE_SPAN.replace(t) { m ->
            val idx = m.groupValues[1].toIntOrNull()
            val code = idx?.let { codes.getOrNull(it) }
            if (code == null) m.value else code
        }
        return t
    }

    /** Inline markdown → HTML, escaping non-markdown characters. */
    private fun inlineToHtml(s: String): String {
        // Pull code spans out first so their contents aren't re-escaped/processed.
        val codes = mutableListOf<String>()
        var work = INLINE_CODE.replace(s) { m ->
            codes.add(m.groupValues[1])
            "${CODE_PLACEHOLDER}${codes.size - 1}${CODE_PLACEHOLDER}"
        }
        work = escapeHtml(work)
        // Images: ![alt](url)
        work = IMAGE_HTML.replace(work) { m ->
            "<img alt=\"${m.groupValues[1]}\" src=\"${m.groupValues[2]}\"/>"
        }
        // Links: [text](url)
        work = LINK.replace(work) { m ->
            "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
        }
        // Bold ***x*** / ___x___
        work = BOLD_ITALIC_STAR.replace(work, "<strong><em>$1</em></strong>")
        work = BOLD_ITALIC_UNDERSCORE.replace(work, "<strong><em>$1</em></strong>")
        // Bold **x**
        work = BOLD_STAR.replace(work, "<strong>$1</strong>")
        work = BOLD_UNDERSCORE.replace(work, "<strong>$1</strong>")
        // Italic *x* / _x_
        work = ITALIC_STAR.replace(work, "<em>$1</em>")
        work = ITALIC_UNDERSCORE.replace(work, "<em>$1</em>")
        // Strikethrough ~~x~~
        work = STRIKETHROUGH.replace(work, "<del>$1</del>")
        // Restore code spans (and HTML-escape their inside)
        // [audit-0917] getOrNull, not codes[i]: the placeholder is the literal
        // "CODE<n>CODE", so pasted text that happens to contain "CODE0CODE"
        // (or any CODE<n>CODE) matched here and threw IndexOutOfBoundsException
        // / substituted the wrong span. Out-of-range matches are left verbatim.
        work = CODE_SPAN.replace(work) { m ->
            val idx = m.groupValues[1].toIntOrNull()
            val code = idx?.let { codes.getOrNull(it) }
            if (code == null) m.value else "<code>${escapeHtml(code)}</code>"
        }
        return work
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
