package com.rikkaminis.app.workspace

/**
 * Pure-logic engine for the daily memory rollup (T6).
 *
 * The rollup reads a completed daily log (YYYY-MM-DD.md, written by
 * memory_write) and distills it into "cross-session stable rules" — concise,
 * reusable conventions/decisions/lessons — appended to MEMORY-ROLLUP.md.
 *
 * The original daily log is NEVER modified or deleted: the rollup product is
 * a distilled index, not a migration. The original remains fully searchable
 * via memory_get, so a classification miss costs nothing more than a missing
 * (or extra) line in the rollup file.
 *
 * Not a scheduler — invoked on-demand by the agent via the memory_rollup tool
 * (or triggered automatically when daily log size exceeds a threshold).
 *
 * All functions here are pure (no Android, no I/O) so the distillation logic
 * is unit-testable on the JVM. Heuristics are deliberately conservative:
 * an entry is only distilled when it carries a convergence signal (done /
 * root cause / lesson …) or a titled body; unfinished process notes are left
 * out (they are not yet "stable rules").
 */
object MemoryRollupEngine {

    /** One memory_write entry from a daily log. */
    data class Entry(
        /** Timestamp from the `<!-- yyyy-MM-dd HH:mm:ss -->` marker, or null for marker-less files. */
        val timestamp: String?,
        /** Entry body (title lines included). */
        val body: String,
        /** First `## ...` heading found in the body. */
        val title: String? = null,
    )

    /** Distillation bucket an entry lands in. */
    enum class RollupClass {
        /** Conventions / discipline / protocols ("must", "never", "约定"...). */
        CONVENTION,

        /** User feedback & decisions ("用户决定/确认/要求/批评"...). */
        USER_DECISION,

        /** Converged knowledge: lessons, root causes, completed work, research conclusions. */
        LESSON,

        /** Unfinished process notes / noise — not distilled. */
        TRANSIENT,
    }

    /** Rollup product file name, living next to GLOBAL.md in the memory dir. */
    const val ROLLUP_FILE = "MEMORY-ROLLUP.md"

    private val TIMESTAMP_MARKER = Regex("""<!-- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) -->\s*\n?""")

    // ── Entry splitting ──────────────────────────────────────────────────

    /**
     * Split a daily-log file body into entries on the canonical
     * `<!-- yyyy-MM-dd HH:mm:ss -->` markers (the same boundaries
     * MemoryRepository.revokeEntry/replaceEntryBody rely on).
     * A marker-less file is treated as a single entry.
     */
    fun extractEntries(text: String): List<Entry> {
        if (text.isBlank()) return emptyList()

        val matches = TIMESTAMP_MARKER.findAll(text).toList()
        if (matches.isEmpty()) {
            return listOf(Entry(null, text.trim(), extractTitle(text.trim())))
        }

        val entries = mutableListOf<Entry>()
        for ((i, match) in matches.withIndex()) {
            val bodyStart = match.range.last + 1
            val bodyEnd = matches.getOrNull(i + 1)?.range?.first ?: text.length
            val body = text.substring(bodyStart, bodyEnd).trim()
            if (body.isEmpty()) continue
            entries.add(Entry(match.groupValues[1], body, extractTitle(body)))
        }
        return entries
    }

    /** First `## `-prefixed heading, or null. */
    fun extractTitle(body: String): String? =
        body.lineSequence()
            .firstOrNull { it.trimStart().startsWith("## ") }
            ?.trim()
            ?.removePrefix("## ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // ── Classification heuristics ────────────────────────────────────────

    /** Signals that a note is still in-flight (to-do, unimplemented, pending). */
    private val UNFINISHED_SIGNALS = listOf(
        "待办", "待做", "TODO",
        "待验证", "待查", "待用户", "待确认", "待决定", "待后续", "待定",
        "未实施", "未提交", "未推送", "未合并", "未定位", "未决定", "未开始", "未测试", "未验证", "未完成", "未落地",
        "进行中", "尚未", "下一步",
    )

    /**
     * Strict convergence signals: the note records a completed / confirmed /
     * distilled fact. Ambiguous words such as bare "确认" and "决定" that
     * could be part of an unfinished phrase ("待确认", "未决定") are
     * excluded — they are covered by the unfinished check.
     */
    private val STRICT_CONVERGED_SIGNALS = listOf(
        "✅", "完成", "已确认", "已合并", "已修复", "已上线", "已生效", "已落地",
        "已建立", "已创建", "已交付", "已固化",
        "验证通过", "闭环", "根因", "结论", "经验", "教训", "踩坑",
        "关键", "机制", "原则", "生效", "修复成功",
        "已记录", "已抽", "已补",
    )

    /** Signals that the note states a standing convention / discipline / protocol. */
    private val CONVENTION_SIGNALS = listOf(
        "纪律", "约定", "必须", "不要", "禁止", "绝不", "一律", "优先", "协议", "规矩", "规则", "门控", "惯例", "务必",
    )

    /** Signals that the note records user feedback / a user decision. */
    private val USER_SIGNALS = listOf(
        "用户决定", "用户确认", "用户要求", "用户偏好", "用户选择", "用户选", "用户不", "用户批评", "用户报",
        "用户亲述", "用户一手", "用户指出", "用户否决", "用户拒绝", "用户明确", "用户说", "用户发现",
        "用户观察", "用户提出", "用户诉", "用户认为",
    )

    // ── Classification heuristics ────────────────────────────────────────

    /**
     * Whether the entry's title (or the first 20 chars of body, for
     * marker-less entries) starts with an unfinished signal — a strong
     * indicator this note is a to-do or pending item, not a finished rule.
     *
     * A strong-unfinished entry is always filtered regardless of whether
     * the body also contains convergence words (e.g. "待办：合并到 main"
     * has a convergence word "合并到" but should still be filtered).
     */
    private fun isStrongUnfinished(entry: Entry): Boolean {
        // Check title first
        if (entry.title != null && containsAny(entry.title, UNFINISHED_SIGNALS)) return true
        // Check body start (first 20 characters)
        val bodyStart = entry.body.take(20)
        return containsAny(bodyStart, UNFINISHED_SIGNALS)
    }

    /** Whether the body contains an unfinished signal anywhere. */
    private fun isWeakUnfinished(body: String): Boolean = containsAny(body, UNFINISHED_SIGNALS)

    /** Whether the body contains a strict convergence signal. */
    private fun isStrictlyConverged(body: String): Boolean = containsAny(body, STRICT_CONVERGED_SIGNALS)

    /**
     * Assign an entry to a rollup bucket.
     *
     * Decision order (lowest to highest priority):
     * 1. Strong unfinished (title or body start) → TRANSIENT (always filter).
     * 2. Weak unfinished without strict convergence → TRANSIENT.
     * 3. Convention signals → CONVENTION.
     * 4. User signals → USER_DECISION.
     * 5. Titled or reasonably long body → LESSON.
     * 6. Strict convergence alone → LESSON.
     * 7. Short untitled noise → TRANSIENT.
     */
    fun classify(entry: Entry): RollupClass {
        if (entry.body.isBlank()) return RollupClass.TRANSIENT

        // 1. Strong unfinished → always transient
        if (isStrongUnfinished(entry)) return RollupClass.TRANSIENT

        // 2. Weak unfinished without convergence → transient
        if (isWeakUnfinished(entry.body) && !isStrictlyConverged(entry.body))
            return RollupClass.TRANSIENT

        // 3-5. Bucket assignment
        if (containsAny(entry.body, CONVENTION_SIGNALS)) return RollupClass.CONVENTION
        if (containsAny(entry.body, USER_SIGNALS)) return RollupClass.USER_DECISION
        if (entry.title != null || entry.body.length >= 40) return RollupClass.LESSON
        if (isStrictlyConverged(entry.body)) return RollupClass.LESSON
        return RollupClass.TRANSIENT
    }

    // ── Dedup ────────────────────────────────────────────────────────────

    /**
     * Merge entries that share the same title (or, failing a title, the same
     * first 40 chars of body). Later bodies are line-deduped into the first.
     * Order of first occurrence wins; timestamps keep the earliest value.
     */
    fun dedupeEntries(entries: List<Entry>): List<Entry> {
        val seenKeys = LinkedHashSet<String>()
        val merged = mutableListOf<Entry>()
        val indexByKey = HashMap<String, Int>()

        for (entry in entries) {
            val key = entry.title ?: entry.body.take(40)
            val existingIndex = indexByKey[key]
            if (existingIndex == null) {
                indexByKey[key] = merged.size
                merged.add(entry)
            } else {
                val existing = merged[existingIndex]
                val mergedBody = mergeBodies(existing.body, entry.body)
                merged[existingIndex] = existing.copy(
                    timestamp = existing.timestamp ?: entry.timestamp,
                    body = mergedBody,
                )
            }
            seenKeys.add(key)
        }
        return merged
    }

    private fun mergeBodies(a: String, b: String): String {
        val lines = (a.lineSequence() + b.lineSequence()).toList()
        val seen = LinkedHashSet<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) seen.add(trimmed)
        }
        return seen.joinToString("\n")
    }

    // ── Rollup document building ─────────────────────────────────────────

    /**
     * Build the rollup section for one daily log. Output shape mirrors the
     * memory standard (GLOBAL.md-style `##` sections + `- ` bullet lists):
     *
     * ```
     * ## Rollup 2026-08-11
     *
     * <!-- auto-generated by memory_rollup at 2026-08-12 03:00
     *      from 2026-08-11.md — stable rules distilled from the daily log.
     *      The original log is untouched. -->
     *
     * ### 约定与纪律
     * - **分支隔离纪律**：任何涉及代码仓库的修改，必须在独立分支上完成…
     *
     * ### 用户反馈与决定
     * - **用户开发起点确认**：用户自述开发起点时间线…
     *
     * ### 经验与知识点
     * - **踩坑**：顶层扩展函数 toProviderConfig 需要显式 import…
     * ```
     */
    fun buildRollupText(
        dateStr: String,
        entries: List<Entry>,
        generatedAt: String = "auto",
    ): String {
        val distilled = dedupeEntries(entries)
            .filter { classify(it) != RollupClass.TRANSIENT }

        if (distilled.isEmpty()) return ""

        val byClass = distilled.groupBy { classify(it) }
        val sections = listOf(
            RollupClass.CONVENTION to "约定与纪律",
            RollupClass.USER_DECISION to "用户反馈与决定",
            RollupClass.LESSON to "经验与知识点",
        )

        val sb = StringBuilder()
        sb.append("## Rollup $dateStr\n\n")
        sb.append(
            "<!-- auto-generated by memory_rollup at $generatedAt " +
                "from $dateStr.md — stable rules distilled from the daily log. " +
                "The original log is untouched. -->\n\n",
        )
        for ((rollupClass, heading) in sections) {
            val items = byClass[rollupClass] ?: continue
            if (items.isEmpty()) continue
            sb.append("### $heading\n\n")
            for (entry in items) {
                sb.append(entryToBullet(entry)).append("\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /** Render one entry as a GLOBAL.md-style bullet. */
    fun entryToBullet(entry: Entry): String {
        val title = entry.title ?: entry.body.lineSequence().firstOrNull()?.take(60) ?: "记忆条目"
        val bodyLines = entry.body.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("## ") }
            .toList()
        val sb = StringBuilder("- **$title**")
        for ((i, line) in bodyLines.withIndex()) {
            if (i == 0) {
                sb.append("：").append(line.trim())
            } else {
                sb.append("\n  ").append(line.trim())
            }
        }
        return sb.toString()
    }

    // ── Idempotency ──────────────────────────────────────────────────────

    /** Whether the rollup file already contains a section for [dateStr]. */
    fun hasRollupForDate(rollupFileContent: String, dateStr: String): Boolean {
        if (rollupFileContent.isBlank()) return false
        return Regex("""## Rollup ${Regex.escape(dateStr)}(\s|\n|$)""").containsMatchIn(rollupFileContent)
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private fun containsAny(text: String, signals: List<String>): Boolean =
        signals.any { text.contains(it) }
}