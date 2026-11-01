package com.rikkaminis.app.agent

import org.json.JSONArray

/**
 * [knowledge-pack] Built-in app structure knowledge (`assets/app_knowledge/<id>/APP.md`).
 *
 * Why this exists: every session currently rediscovers "where is the setting in
 * WeChat" from zero by poking the screen. A small, curated pack per app turns
 * that into one tool call — page structure, common entries, easy-to-confuse
 * spots, login/security pages — the four things a screen-driven agent needs.
 *
 * Format is the same frontmatter shape SoulStore / SKILL.md already use:
 * `---` … `---` with `key: value` lines, where `packages` / `aliases` are JSON
 * array strings. Parsed by hand on purpose — no YAML dependency, and the
 * parser must never throw on an asset it doesn't understand (startup path).
 *
 * **This file is deliberately Android-free** (no `android.*` imports): parse,
 * index and render are plain Kotlin so a JVM unit test can drive them with the
 * exact production source. The AssetManager glue lives in
 * `tools/AppGuideTool.kt` — keep it that way, or the tests stop being tests.
 */

/** Asset root scanned for `<id>/APP.md`. Also the source line shown to the model. */
internal const val APP_KNOWLEDGE_ASSETS_DIR = "app_knowledge"

/** How many pack names a miss reply lists, so the tool result stays small. */
internal const val APP_KNOWLEDGE_MAX_LISTED = 10

/**
 * A `---` delimited frontmatter block plus everything after it.
 *
 * [fields] holds the raw (unquoted) scalar values; interpretation — JSON
 * arrays, fallbacks, required keys — belongs to the caller, not the splitter.
 */
internal data class FrontmatterBlock(
    val fields: Map<String, String>,
    val body: String,
)

/**
 * Split [raw] into frontmatter fields + body. Returns null when there is no
 * frontmatter or it is never closed (an unterminated header is a broken file,
 * not an empty one — the caller decides whether that is fatal).
 *
 * Tolerated inside the block: blank lines, `#` comments, lines without a
 * colon, and CRLF line endings. Duplicate keys: last wins.
 */
internal fun parseFrontmatter(raw: String): FrontmatterBlock? {
    val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
    val lines = text.split("\n")
    if (lines.firstOrNull()?.trim() != "---") return null
    val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null

    val fields = LinkedHashMap<String, String>()
    for (i in 1 until close) {
        val line = lines[i]
        if (line.isBlank()) continue
        if (line.trimStart().startsWith("#")) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim()
        if (key.isEmpty()) continue
        fields[key] = unquote(line.substring(colon + 1).trim())
    }
    val body = lines.drop(close + 1).joinToString("\n").trim()
    return FrontmatterBlock(fields, body)
}

/** Strip one matching pair of surrounding quotes; anything else is left alone. */
private fun unquote(value: String): String = when {
    value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") ->
        value.substring(1, value.length - 1)
    value.length >= 2 && value.startsWith("'") && value.endsWith("'") ->
        value.substring(1, value.length - 1)
    else -> value
}

/**
 * `["a", "b"]` → the two values; `a` or `a, b` → the bare values; anything
 * unparseable → empty list. Never throws: a malformed list costs the pack its
 * packages (so it is skipped), not the whole index.
 */
internal fun parseKnowledgeStringList(value: String): List<String> {
    val text = value.trim()
    if (text.isEmpty()) return emptyList()
    if (text.startsWith("[")) {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i, "").trim().ifEmpty { null }
        }
    }
    return text.split(',')
        .map { unquote(it.trim()).trim() }
        .filter { it.isNotEmpty() }
}

/** Index-side view of one pack: everything a lookup needs, nothing heavy. */
internal data class AppKnowledgeEntry(
    val id: String,
    val display: String,
    val description: String,
    val packages: List<String>,
    val aliases: List<String>,
) {
    /** Every string this pack answers to, lowercased, empty entries dropped. */
    internal fun searchKeys(): List<String> =
        (listOf(id, display) + packages + aliases)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
}

/** One parsed pack: index metadata + the Markdown body handed to the model. */
internal data class AppKnowledgePack(
    val entry: AppKnowledgeEntry,
    val body: String,
)

/**
 * Parse one `APP.md`. Null (never an exception) when the file has no
 * frontmatter, no usable `packages`, or an empty body — a pack that cannot be
 * looked up by package and has nothing to say is not a pack.
 */
internal fun parseAppKnowledgePack(id: String, text: String): AppKnowledgePack? {
    val doc = parseFrontmatter(text) ?: return null
    val packages = parseKnowledgeStringList(doc.fields["packages"].orEmpty())
    if (packages.isEmpty()) return null
    if (doc.body.isBlank()) return null

    val name = doc.fields["name"]?.trim().orEmpty()
    val display = doc.fields["display"]?.trim()?.ifEmpty { null }
        ?: name.ifEmpty { id }
    // `name` is a lookup key too ("wechat"), but only when it adds something.
    val extraAlias = name.takeIf { it.isNotEmpty() && it != id && it != display }
    val aliases = parseKnowledgeStringList(doc.fields["aliases"].orEmpty()) + listOfNotNull(extraAlias)

    return AppKnowledgePack(
        entry = AppKnowledgeEntry(
            id = id.trim().lowercase(),
            display = display,
            description = doc.fields["description"].orEmpty().trim(),
            packages = packages,
            aliases = aliases,
        ),
        body = doc.body,
    )
}

/**
 * Lowercased key → entry, first writer wins (a package name owned by two
 * packs would otherwise depend on iteration order).
 */
private fun buildLookupTable(entries: List<AppKnowledgeEntry>): Map<String, AppKnowledgeEntry> {
    val map = HashMap<String, AppKnowledgeEntry>()
    for (entry in entries) {
        for (key in entry.searchKeys()) map.putIfAbsent(key, entry)
    }
    return map
}

/**
 * Lookup table over parsed packs: exact package / id / name / alias first,
 * then a substring fallback for queries of two characters or more ("weixin",
 * "设置"). Sorted by id so the "available:" list is stable across runs.
 */
internal class AppKnowledgeIndex(packs: List<AppKnowledgePack>) {

    val entries: List<AppKnowledgeEntry> = packs.map { it.entry }.sortedBy { it.id }

    // Built by a top-level helper, not `HashMap(...).apply { }`: inside that
    // receiver `entries` resolves to `Map.entries` (Set<MutableEntry>) and
    // silently shadows the class property — a compile error that reads like a
    // type mismatch, worth the extra function.
    private val byKey: Map<String, AppKnowledgeEntry> = buildLookupTable(entries)

    fun byId(id: String): AppKnowledgeEntry? = byKey[id.trim().lowercase()]

    /**
     * Resolve [query] (package name, id, display name or alias, case
     * insensitive) to a pack entry; null when nothing matches.
     */
    fun find(query: String): AppKnowledgeEntry? {
        val raw = query.trim()
        if (raw.isEmpty()) return null
        val key = raw.lowercase()
        byKey[key]?.let { return it }
        if (key.length < 2) return null
        return entries.firstOrNull { entry -> entry.searchKeys().any { it.contains(key) } }
    }

    /** `wechat (微信, com.tencent.mm), alipay (支付宝) …` — for the miss reply. */
    fun availableList(limit: Int = APP_KNOWLEDGE_MAX_LISTED): String =
        entries.take(limit).joinToString(", ") { entry ->
            val packages = entry.packages.joinToString("/")
            "${entry.id} (${entry.display}, $packages)"
        }
}

/**
 * Tool-result text for a hit: header (what was matched), a freshness caveat,
 * the pack body, and the asset path it came from so the model can cite it.
 */
internal fun renderAppKnowledge(entry: AppKnowledgeEntry, body: String): String {
    val header = "[App knowledge] ${entry.display} (${entry.id}) — packages: " +
        entry.packages.joinToString(", ")
    val caveat = "Built-in reference, not live state: labels and layout shift between app " +
        "versions, so confirm each step against what is actually on screen."
    val source = "Source: assets/$APP_KNOWLEDGE_ASSETS_DIR/${entry.id}/APP.md"
    return buildString {
        appendLine(header)
        appendLine(caveat)
        appendLine()
        appendLine(body.trim())
        appendLine()
        append(source)
    }
}
