package com.rikkaminis.app.tools

/**
 * Sandbox defect-registry recall — when a tool fails, match its output
 * against the user-maintained symptom registry
 * (`<filesDir>/minis-global/shared/sandbox-limits.md`) and let the caller
 * append the matched entries' recovery paths to the failing tool result.
 *
 * Why (2026-10-03): the registry existed but only reached the agent when it
 * remembered to look — a proven failure mode. A known git-push defect was
 * recorded in memory, and the failure still burned 3 rounds of probe
 * commands. Knowledge has to appear at the moment of failure, not one
 * file-read away.
 *
 * Design constraints (all load-bearing):
 * - Pure JVM, no Android dependency → unit-testable in the sandbox JVM
 *   (same shape as ToolFailureHook).
 * - Never throws: callers wrap file IO in runCatching and swallow — a
 *   registry problem must never break the tool-result path (same discipline
 *   as the ToolFailureHook call this runs next to).
 * - Conservative matching: an entry is auto-matchable ONLY through the
 *   backticked seeds on its `- 症状：` line (the author marks raw error text
 *   with backticks); every non-stopword token of at least one seed group
 *   must occur in the output. Prefer a miss over a wrong hint.
 *
 * 2026-10-27 hardening (two live defects found by replaying real failing
 * output through this matcher — see SandboxDefectRegistryTest):
 * 1) BLEED: ENTRY_RE only accepted `###`, and a non-matching heading did NOT
 *    close the current entry. Twelve entries are authored as `## A19 …` /
 *    `## S28 …`, so their `- 症状：` lines were absorbed by the PREVIOUS `###`
 *    entry — B5 ("陈旧视图") fired carrying A19's seeds and recovery path.
 *    Now ANY markdown heading closes the entry, and `##`/`###` both open one
 *    only when the token is id-shaped (`A1b`, `S28`) — so `## A 类：…` and
 *    `## 元规则` stay non-entries.
 * 2) LOOSENESS: an all-generic seed group is a near-universal hit. The
 *    example-style seed `grep -n X file` tokenizes to [grep, file] (single
 *    chars and `-n` are dropped) and fired on every `grep: …: No such file or
 *    directory`; `" CODE "` → [code] fired on the literal "(exit code: 1)"
 *    trailer; `wait` → [wait] fired on the stall-guard text. A group now
 *    needs at least one SPECIFIC token (structural char, long latin run, or
 *    CJK run) to be eligible.
 */
object SandboxDefectRegistry {

    /** Max entries appended to one tool result; a single failure rarely matches more. */
    const val MAX_HINTS = 2

    /** Per-line cap in the rendered hint (defensive; entries are human-authored). */
    const val LINE_CAP = 400

    /**
     * Latin runs of 3+ chars, CJK runs of 2+ chars. Wildcards (`*`) and
     * one-char placeholders (`X`) simply terminate a run — the resulting
     * token then matches as a prefix (`l2s.tmp_obj_*` → token `l2s.tmp_obj`,
     * found inside any concrete random-suffixed name).
     */
    private val LATIN_TOKEN_RE = Regex("""[A-Za-z0-9_.\-]{3,}""")
    private val CJK_TOKEN_RE = Regex("""[\u4e00-\u9fff]{2,}""")

    /**
     * Generic words that would make AND-matching too loose (any output with
     * "error" + "not" + "failed" would hit). Curated, not exhaustive — the
     * all-tokens-present rule plus specific tokens is the primary guard.
     */
    private val STOPWORDS = setOf(
        "error", "failed", "fatal", "warning", "cannot", "could", "would",
        "should", "have", "has", "had", "does", "not", "the", "and", "for",
        "this", "that", "with", "from", "when", "where", "which", "there",
        "their", "your", "you", "was", "were", "but",
    )

    /**
     * Entry heading. `##` and `###` are both accepted (the registry uses both
     * in practice), but the id must be id-shaped — letters + digits, optional
     * lowercase suffix: `A1b`, `S28`. The shape guard is what keeps
     * `## A 类：路径缺陷` and `## 元规则` from parsing as entries.
     */
    private val ENTRY_RE = Regex("""^#{2,3}\s+([A-Za-z]{1,3}\d+[A-Za-z]?)(?:\s+(.*))?$""")

    /**
     * Any markdown heading closes the entry being parsed — including headings
     * that are NOT entries. Without this, a `## 元规则` section's field lines
     * would be appended to whatever `###` entry came before it (the 2026-10-27
     * bleed that made B5 fire with A19's seeds).
     */
    private val HEADING_RE = Regex("""^#{1,6}\s""")

    /**
     * Field line, tolerant of bold markup and of both colon widths:
     * `- 症状：…` / `- **症状**：…` / `- **症状**: …`. Nine registry entries
     * write the bold form, and the old strict-prefix check silently skipped
     * all of them (no symptom ⇒ never matchable).
     */
    private val FIELD_RE = Regex("""^-\s*(?:\*\*)?(症状|健康路径|救不动)(?:\*\*)?\s*[:：]\s*(.*)$""")

    /** One backticked seed: `` `…` ``. */
    private val BACKTICK_RE = Regex("""`([^`\n]+)`""")

    /**
     * A token is *specific* when it carries structure a generic English/log
     * word does not: a path/markup character, a ≥6 char latin run, or a CJK
     * run. 6 is chosen as the shortest length at which log vocabulary stops
     * colliding (`pack`/`file`/`code`/`wait` are 4, `error` is a stopword,
     * while `binary`, `generated`, `permitted`, `pointer` are all ≥6).
     */
    private const val SPECIFIC_MIN_LEN = 6

    private val STRUCTURAL_RE = Regex("""[/\\._\-:$*'<>\[\]()@=]""")

    // ── model ─────────────────────────────────────────────────────────────

    /** One parsed registry entry. */
    data class Defect(
        val id: String,
        val title: String,
        /** `- 健康路径：` content, null when the entry has none. */
        val recovery: String?,
        /** `- 救不动：` content, null when the entry has none. */
        val frozen: String?,
        /**
         * Token groups from the backticked seeds of the symptom line. One
         * group holds the tokens of ONE candidate phrasing (`a / b` inside a
         * single backtick pair = two candidates). The entry matches when
         * every token of at least one group occurs in the output.
         */
        val seedGroups: List<List<String>>,
    )

    // ── parsing ───────────────────────────────────────────────────────────

    /** Parse registry markdown; unknown lines (断点层/验证/…) are ignored. */
    fun parse(text: String): List<Defect> {
        val out = mutableListOf<Defect>()
        var id: String? = null
        var title = ""
        var symptom: String? = null
        var recovery: String? = null
        var frozen: String? = null

        fun flush() {
            val cur = id
            if (cur != null) {
                out += Defect(
                    id = cur,
                    title = title,
                    recovery = recovery,
                    frozen = frozen,
                    seedGroups = seedGroupsOf(symptom),
                )
            }
            id = null; title = ""; symptom = null; recovery = null; frozen = null
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (HEADING_RE.containsMatchIn(line)) {
                flush()
                val entry = ENTRY_RE.find(line.trimStart())
                if (entry != null) {
                    id = entry.groupValues[1]
                    title = entry.groupValues[2].trim()
                }
                continue
            }
            if (id == null) continue
            val field = FIELD_RE.find(line)
            if (field != null) {
                when (field.groupValues[1]) {
                    "症状" -> symptom = field.groupValues[2].trim()
                    "健康路径" -> recovery = field.groupValues[2].trim()
                    "救不动" -> frozen = field.groupValues[2].trim()
                }
            }
        }
        flush()
        return out
    }

    /**
     * Backticked seeds of a symptom line; ` / ` inside one seed splits
     * phrasings. A group made only of generic words is dropped — see
     * [hasSpecificToken] and the 2026-10-27 note on the type.
     */
    internal fun seedGroupsOf(symptom: String?): List<List<String>> {
        if (symptom.isNullOrBlank()) return emptyList()
        val groups = mutableListOf<List<String>>()
        for (m in BACKTICK_RE.findAll(symptom)) {
            for (alt in m.groupValues[1].split(" / ")) {
                val tokens = tokensOf(alt)
                if (tokens.isNotEmpty() && hasSpecificToken(tokens)) groups += tokens
            }
        }
        return groups
    }

    /**
     * True when at least one token could not plausibly appear in unrelated
     * output. AND-matching over generic-only tokens degenerates into a
     * universal hit: `[grep, file]` (from the example-style seed
     * `grep -n X file`, where `X` and `-n` are dropped by the tokenizer)
     * matches every `grep: …: No such file or directory`; `[code]` (from
     * `" CODE "`) matches the literal `(exit code: 1)` trailer.
     */
    internal fun hasSpecificToken(tokens: List<String>): Boolean = tokens.any { t ->
        STRUCTURAL_RE.containsMatchIn(t) || t.length >= SPECIFIC_MIN_LEN || CJK_TOKEN_RE.matches(t)
    }

    /** Lowercased distinct tokens: latin runs ≥3 + CJK runs ≥2, stopwords dropped. */
    internal fun tokensOf(s: String): List<String> {
        val latin = LATIN_TOKEN_RE.findAll(s)
            .map { it.value.lowercase().trim('.', '_', '-') }
            .filter { it.length >= 3 && it !in STOPWORDS }
            .toList()
        val cjk = CJK_TOKEN_RE.findAll(s).map { it.value }.toList()
        return (latin + cjk).distinct()
    }

    // ── matching ──────────────────────────────────────────────────────────

    // ponytail: 自动召回只认反引号 seed，且组内必须有特异性锚点（保守优先，宁漏勿错）| 天花板: 纯描述性症状条目永不命中（现注册表大多数）；收紧后「seed 全由通用短词构成」的组也转瞎（实测 A1 `bad pack` / S26 `git log`,`git fsck` / S29 `http=000 size=0` / S31 `git log`），靠人工查表 + 把 seed 改写成逐字报错原文兜底 | 升级触发: 召回失效实证（agent 仍在已登记缺陷上烧试错轮）→ 放宽到断点层/关键词行匹配，或短词组改用词边界匹配替代子串包含
    /** AND-match: an entry hits when every token of at least one seed group occurs. */
    fun match(output: String, defects: List<Defect>): List<Defect> {
        if (output.isBlank()) return emptyList()
        val hay = output.lowercase()
        val hits = mutableListOf<Defect>()
        for (d in defects) {
            val hit = d.seedGroups.any { group -> group.isNotEmpty() && group.all { hay.contains(it) } }
            if (hit) {
                hits += d
                if (hits.size >= MAX_HINTS) break
            }
        }
        return hits
    }

    // ── rendering ─────────────────────────────────────────────────────────

    /**
     * Compose the suffix appended to a failed tool result, or null when
     * nothing matches. A null/blank registry text keeps the feature dormant
     * (users without a registry are unaffected).
     */
    fun hintSuffix(failureOutput: String, registryText: String?): String? {
        if (registryText.isNullOrBlank()) return null
        val hits = match(failureOutput, parse(registryText))
        if (hits.isEmpty()) return null
        return formatHints(hits)
    }

    /** Render matched entries: id + title, recovery path, frozen variants. */
    fun formatHints(hits: List<Defect>): String = buildString {
        appendLine()
        appendLine()
        append("[sandbox-defect-registry] 输出命中已知沙箱缺陷的报错特征（自动召回）：")
        for (d in hits) {
            appendLine()
            append("· ${d.id} ${d.title}")
            val rec = d.recovery
            if (!rec.isNullOrBlank()) {
                appendLine()
                append("  健康路径：${rec.take(LINE_CAP)}")
            }
            val fr = d.frozen
            if (!fr.isNullOrBlank()) {
                appendLine()
                append("  救不动（别再试）：${fr.take(LINE_CAP)}")
            }
        }
        appendLine()
        append("完整条目与验证日期：/var/minis/shared/sandbox-limits.md")
    }
}
