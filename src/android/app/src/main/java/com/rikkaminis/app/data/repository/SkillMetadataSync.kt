package com.rikkaminis.app.data.repository

/**
 * Pure decision logic for [SkillRepository.loadAll]'s metadata re-sync.
 *
 * Why this exists (found 2026-09-25): a `skills.db` row is written once at
 * import time, and `reloadFromDisk()` re-reads the DB but never the frontmatter
 * on disk. So a SKILL.md edited outside the in-app editor — by the agent's
 * `file_write` / `file_edit`, or by a shell `sed`/`cp` in the sandbox — kept its
 * original `description` forever. That description is exactly what the model
 * receives in `<available_skills>` (`skillPromptFragment` reads
 * `Skill.description`, truncated to 200 chars), so a stale row means the model
 * triggers skills on outdated text. Observed in the wild: every skill whose
 * description had been rewritten externally still shipped its pre-edit text.
 *
 * The on-disk file is what agents and users actually edit, so it wins — but
 * only for the fields it really carries:
 *  - a missing / frontmatter-less / unparseable file (`disk == null`) leaves the
 *    row untouched;
 *  - a blank disk field falls back to the stored value, so a partial
 *    frontmatter can never blank out a good description;
 *  - `null` means "already equal", which keeps `loadAll` write-free in the
 *    common case (no UPDATE, no `updated_at` churn on every app start).
 *
 * Kept dependency-free on purpose: `SkillRepository` needs Android's
 * Context/SQLite and can't be unit-tested on the JVM, so the decision itself
 * lives here — the same split `SubagentSkill.extractFrontmatterBlock` and
 * `SkillRepository.mergeFrontmatter` already use.
 */
// ponytail: 只对账 name/description/version 三个库托管键 | 天花板: 其它 frontmatter 键（subagent/max_turns/allowed_tools）仍只从盘上实时读、不进库，因此不能按它们做库查询或排序 | 升级触发: 出现需要按非托管键查询、排序或批量校验的功能时
internal object SkillMetadataSync {

    /** The three frontmatter keys mirrored into the `skills` table. */
    data class Metadata(val name: String, val description: String, val version: String)

    /**
     * Metadata the row should now carry, or null when it already matches (or
     * when disk has nothing usable to say).
     */
    fun reconcile(stored: Metadata, disk: Metadata?): Metadata? {
        if (disk == null) return null
        val merged = Metadata(
            name = disk.name.ifBlank { stored.name },
            description = disk.description.ifBlank { stored.description },
            version = disk.version.ifBlank { stored.version },
        )
        return if (merged == stored) null else merged
    }
}
