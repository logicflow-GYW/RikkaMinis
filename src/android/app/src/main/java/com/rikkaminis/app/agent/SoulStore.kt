package com.rikkaminis.app.agent

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-soul-md] Persistent personality / identity file mirroring iOS
 * SoulStore (`src/ios/Agent/Session/SoulStore.swift`, commit 6370d5a).
 *
 * SOUL.md lives next to GLOBAL.md and the daily memory logs under
 * `<filesDir>/minis-global/memory/`. Format: YAML frontmatter delimited
 * by `---` followed by a Markdown body. The body becomes Layer 1 of the
 * system prompt; `name` + `emoji` drive the chat assistant bubble header.
 *
 * Components below mirror iOS one-for-one:
 *   - [SoulMetadata]  – four-field struct (name/emoji/style/lang)
 *   - [SoulFile]      – metadata + body pair
 *   - [SoulMDParser]  – lossless-ish parse / serialize
 *   - [SoulStore]     – file I/O, default content, fallback identity,
 *                       cachedMetadata + onChanged listener
 *   - [SystemPromptBuilder] – injection-scrubbed identitySection() for the
 *                             agent system prompt
 */

data class SoulMetadata(
    val name: String,
    /**
     * Raw `emoji` value as it appears in SOUL.md's YAML frontmatter.
     *
     * Kept ONLY for round-trip preservation when serializing back to disk:
     * a user might have set a custom emoji on another platform / older
     * build, and we don't want to silently overwrite their file when
     * Settings → Save runs on this device.
     *
     * **Do NOT use this for UI.** All UI surfaces (Settings preview,
     * chat bubble header) must read [displayEmoji] instead, which is
     * locked to the canonical ✨ sparkle. This decision matches the
     * iOS rollback of the emoji-customization field.
     */
    val emoji: String,
    val style: String,
    /** `"auto"`, `"zh"`, `"en"`, or any free-form tag. */
    val lang: String,
) {
    /**
     * The emoji the user actually sees in Settings preview + chat bubble
     * header. Locked to ✨ regardless of what's in the SOUL.md file —
     * the emoji-customization field was removed without breaking
     * file-format compatibility.
     */
    val displayEmoji: String get() = DISPLAY_EMOJI

    companion object {
        /** Locked identity emoji shown in every UI surface. */
        const val DISPLAY_EMOJI = "✨"

        val DEFAULT = SoulMetadata(
            // Default personality name is aligned with the app name
            // (R.string.app_name = "RikkaMinis"). Kept as a plain literal
            // here because SoulMetadata.DEFAULT is a Context-free constant;
            // every UI surface falls back to this single source of truth.
            name = "RikkaMinis",
            // Default emoji is intentionally empty — UI uses the fixed
            // [displayEmoji] sparkle and [SoulMDParser.serialize] no longer
            // writes the `emoji:` line. The field is kept on the struct only
            // so the parser can round-trip an `emoji: "..."` line that
            // survives in an old user-authored SOUL.md; the next save drops
            // it on disk too.
            emoji = "",
            style = "",
            lang = "auto",
        )
    }
}

data class SoulFile(val metadata: SoulMetadata, val body: String)

/**
 * Lightweight YAML-frontmatter parser. Intentionally NOT a real YAML
 * implementation — only handles the four keys used by SOUL.md, each on
 * its own line in `key: "value"` form. Designed to be lossless enough
 * that `serialize(parse(s)) == s` for files this app writes itself.
 */
object SoulMDParser {

    fun parse(source: String): SoulFile {
        val trimmedLeading = source.dropWhile { it == '\n' || it == '\r' }
        if (!trimmedLeading.startsWith("---")) {
            return SoulFile(SoulMetadata.DEFAULT, source)
        }
        val lines = trimmedLeading.split("\n")
        if (lines.firstOrNull()?.trim() != "---") {
            return SoulFile(SoulMetadata.DEFAULT, source)
        }
        val closeIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (closeIdx < 0) return SoulFile(SoulMetadata.DEFAULT, source)
        // indexOfFirst on drop(1) result is offset by 1 — restore absolute index.
        val absCloseIdx = closeIdx + 1
        val frontmatter = lines.subList(1, absCloseIdx)
        val bodyLines = if (absCloseIdx + 1 <= lines.size) lines.subList(absCloseIdx + 1, lines.size) else emptyList()
        // Strip leading AND trailing blank lines so parse(serialize(x)).body
        // round-trips — serialize guarantees a trailing "\n", and without the
        // trailing strip the body would gain a newline on every save/load
        // cycle. (Consumers re-trim anyway, but the parser should be lossless
        // both directions, not just serialize(parse(s)) == s.)
        val body = bodyLines.joinToString("\n")
            .dropWhile { it == '\n' || it == '\r' }
            .trimEnd('\n', '\r')

        var name = SoulMetadata.DEFAULT.name
        var emoji = SoulMetadata.DEFAULT.emoji
        var style = SoulMetadata.DEFAULT.style
        var lang = SoulMetadata.DEFAULT.lang
        for (raw in frontmatter) {
            val line = raw.trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            var value = line.substring(colon + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
                value = unescape(value)
            }
            when (key) {
                "name" -> if (value.isNotEmpty()) name = value
                "emoji" -> if (value.isNotEmpty()) emoji = value
                "style" -> style = value
                "lang" -> if (value.isNotEmpty()) lang = value
                else -> Unit
            }
        }
        return SoulFile(SoulMetadata(name, emoji, style, lang), body)
    }

    /**
     * Serialize back to SOUL.md text. Emits a 3-key frontmatter block
     * (name / style / lang) followed by an empty line and the body.
     *
     * The `emoji` field is deliberately NOT written — the UI is locked to a
     * fixed sparkle ([SoulMetadata.DISPLAY_EMOJI]), so persisting a `emoji:`
     * line would imply user-controlled customization that doesn't exist.
     * Old files containing `emoji: "..."` still parse cleanly (the value is
     * kept in memory for round-trip safety) but the line is dropped on the
     * next save, naturally migrating disk state to the new schema.
     */
    fun serialize(file: SoulFile): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: \"").append(escape(file.metadata.name)).append("\"\n")
        sb.append("style: \"").append(escape(file.metadata.style)).append("\"\n")
        sb.append("lang: \"").append(escape(file.metadata.lang)).append("\"\n")
        sb.append("---\n\n")
        sb.append(file.body)
        if (!sb.endsWith("\n")) sb.append("\n")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Reverse of [escape]: `\"` → `"` and `\\` → `\`. Applied to quoted
     * frontmatter values so a save/load cycle is lossless for metadata that
     * contains quotes or backslashes. Without this, each cycle would bake in
     * one more layer of backslash escaping (`a"b` → `a\"b` → `a\\\"b` …).
     * Order matters: unescape escaped-quote before doubled-backslash.
     */
    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\")
}

/**
 * Result of a SOUL.md body length check. The hard limit depends on what
 * language the body is written in — Chinese / Japanese / Korean text is
 * information-dense per character so the cap is in chars, while English /
 * other Latin-alphabet text is capped by word count.
 *
 * Mirrors `SoulStore.isOverLimit(_:)` on iOS. The associated value carries
 * the observed magnitude and the matching cap so callers can surface a
 * precise "your body is N / max M" message.
 */
sealed class SoulBodyLimitCheck {
    object Ok : SoulBodyLimitCheck()
    data class OverLimitChinese(val chars: Int, val cap: Int) : SoulBodyLimitCheck()
    data class OverLimitEnglish(val words: Int, val cap: Int) : SoulBodyLimitCheck()

    val isOverLimit: Boolean get() = this !is Ok
}

/**
 * The unit a SOUL.md body is counted in, decided by the same CJK-ratio
 * rule that picks the cap (see [SoulStore.isOverLimit]). [magnitude] is the
 * observed count and [cap] the matching limit. Single source of truth for
 * "how much did the user write, and against which cap" so the Settings
 * editor counter and the over-limit gate can never drift apart.
 */
enum class SoulBodyUnit { CHARS, WORDS }

data class SoulBodyCount(
    val unit: SoulBodyUnit,
    val magnitude: Int,
    val cap: Int,
)

/**
 * File-system helpers + cached metadata. Methods are intentionally
 * `Context`-scoped instead of taking a global singleton — keeps the
 * dependency surface explicit and avoids depending on MinisApp from
 * non-application call sites (tests, previews).
 */
object SoulStore {

    private const val TAG = "SoulStore"
    // [FIX-6 / F-224] Public so the two other components that must recognise
    // the persona file can reference this single declaration instead of
    // re-spelling "SOUL.md": MemoryRepository (keeps it out of the daily-log
    // list, out of memory_get, and refuses to delete it) and
    // MemoryManagementScreen (hides the Delete button). Mirrors the existing
    // `MemoryRollupEngine.ROLLUP_FILE` precedent — two copies of a path
    // literal is exactly how the sibling key-name drift bug (F-239) started.
    const val FILE_NAME = "SOUL.md"
    private const val MEMORY_SUBDIR = "minis-global/memory"

    fun fileLocation(context: Context): File =
        File(File(context.filesDir, MEMORY_SUBDIR), FILE_NAME)

    // -- Body length rules (language-aware) ----------------------------
    //
    // The personality body has a hard cap applied at every write surface
    // (Settings UI Save button, minis-config writer, and the
    // prompt-build-time fallback in `SystemPromptBuilder`). The cap is
    // language-dependent: CJK text is information-dense per character so
    // 1600 chars is the limit; Latin / mixed text gets a 1000-word cap.
    // (T-soul-body-limit-2000 — both axes doubled from 800 / 500 to align
    //  with iOS bumping its single 1000→2000 token cap.)
    //
    // CJK ratio of 30% switches the rule. A body whose CJK glyphs make up
    // more than 30% of all unicode code points is treated as CJK-leaning
    // and counted by character; anything at-or-below 30% is counted by
    // word. 30% is the lowest watermark at which Chinese / Japanese
    // clearly dominates — high enough to ignore stray CJK quotes or
    // proper nouns in an English document, low enough that a majority-CJK
    // paragraph with a few inline English terms still counts as CJK.

    const val CJK_RATIO_THRESHOLD: Double = 0.3
    const val CHINESE_CHAR_LIMIT: Int = 1600
    const val ENGLISH_WORD_LIMIT: Int = 1000

    /**
     * Classify [body] under the language-aware length rules above. Empty
     * / whitespace-only bodies always return [SoulBodyLimitCheck.Ok].
     */
    fun isOverLimit(body: String): SoulBodyLimitCheck {
        val count = countBody(body)
        if (count.magnitude == 0) return SoulBodyLimitCheck.Ok
        return when (count.unit) {
            SoulBodyUnit.CHARS ->
                if (count.magnitude > CHINESE_CHAR_LIMIT)
                    SoulBodyLimitCheck.OverLimitChinese(count.magnitude, CHINESE_CHAR_LIMIT)
                else SoulBodyLimitCheck.Ok
            SoulBodyUnit.WORDS ->
                if (count.magnitude > ENGLISH_WORD_LIMIT)
                    SoulBodyLimitCheck.OverLimitEnglish(count.magnitude, ENGLISH_WORD_LIMIT)
                else SoulBodyLimitCheck.Ok
        }
    }

    /**
     * Count [body] under the language-aware rules without deciding the
     * over-limit verdict. Returns the counted unit plus magnitude and the
     * matching cap; an empty/whitespace-only body is `(CHARS, 0, cap)`.
     * This is the single source of truth shared by [isOverLimit] and the
     * Settings editor's live counter so the two can never disagree about
     * which rule applies (previously the UI re-implemented the CJK
     * classifier with a narrower range and could drift from the gate).
     */
    fun countBody(body: String): SoulBodyCount {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return SoulBodyCount(SoulBodyUnit.CHARS, 0, CHINESE_CHAR_LIMIT)
        }

        var cjk = 0
        var total = 0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            total += 1
            if (isCJKCodePoint(cp)) cjk += 1
            i += Character.charCount(cp)
        }
        val ratio = if (total > 0) cjk.toDouble() / total else 0.0
        return if (ratio > CJK_RATIO_THRESHOLD) {
            // Code-point count — closest analogue to iOS grapheme cluster
            // count for the CJK ranges we care about (no combining marks
            // / no flag emojis in Han/Kana/Hangul). Bytes / UTF-16 code
            // units would over-count surrogate-pair CJK extensions.
            val chars = trimmed.codePointCount(0, trimmed.length)
            SoulBodyCount(SoulBodyUnit.CHARS, chars, CHINESE_CHAR_LIMIT)
        } else {
            // Whitespace-delimited word count. `split(Regex("\\s+"))` on
            // a trimmed string collapses consecutive whitespace into a
            // single delimiter.
            val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
            SoulBodyCount(SoulBodyUnit.WORDS, words, ENGLISH_WORD_LIMIT)
        }
    }

    /**
     * True if [cp] belongs to any CJK Unified Ideograph or Kana / Hangul
     * range. Covers Chinese (Simplified + Traditional), Japanese
     * (Hiragana + Katakana + Kanji shared with CJK Unified), and Korean
     * (Hangul Syllables + Jamo). Wider than just U+4E00–U+9FFF so the
     * extensions and Hangul also count toward the ratio.
     */
    private fun isCJKCodePoint(cp: Int): Boolean {
        // CJK Unified Ideographs + Ext A
        if (cp in 0x4E00..0x9FFF) return true
        if (cp in 0x3400..0x4DBF) return true
        // CJK Unified Ideographs Ext B, C/D/E/F, G/H
        if (cp in 0x20000..0x2A6DF) return true
        if (cp in 0x2A700..0x2EBEF) return true
        if (cp in 0x30000..0x323AF) return true
        // Hiragana, Katakana, Katakana Phonetic Extensions
        if (cp in 0x3040..0x309F) return true
        if (cp in 0x30A0..0x30FF) return true
        if (cp in 0x31F0..0x31FF) return true
        // Hangul Syllables, Jamo, Compatibility Jamo
        if (cp in 0xAC00..0xD7AF) return true
        if (cp in 0x1100..0x11FF) return true
        if (cp in 0x3130..0x318F) return true
        return false
    }

    /**
     * The verbatim default file content used both for first-run
     * auto-create and the "Restore Default" button in Settings.
     *
     * IMPORTANT: the body contains ONLY personality / character / voice
     * guidance. It must NOT contain the "You are <name>, …" identity
     * sentence — that boilerplate is owned by [SystemPromptBuilder] and
     * stitched in around the body at prompt-build time. Mixing identity
     * boilerplate into the body would (a) expose internal prompt
     * structure to users in the Settings Personality editor, and (b)
     * double-up "You are X" lines whenever the template-rendered prompt
     * also produces one.
     *
     * Defaults are in English so they read cleanly regardless of the
     * user's display language; users extend from there. Mirrors iOS
     * `SoulStore.defaultContent` byte-for-byte (74c0daf).
     */
    val DEFAULT_CONTENT: String = """---
name: "RikkaMinis"
style: ""
lang: "auto"
---

**Don't perform — help.** Skip the "Sure!" and "Happy to assist!" — just do the work.

**Have a stance.** It's fine to disagree, prefer one thing over another, find some things interesting and others dull.

**Act first, ask second.** If you can look it up, look it up. Come back with answers, not questions.
"""

    /**
     * Create SOUL.md with [DEFAULT_CONTENT] iff it does not exist yet.
     * Safe to call on every launch — never overwrites existing user edits.
     */
    fun ensureExists(context: Context) {
        val file = fileLocation(context)
        if (file.exists()) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_CONTENT)
            AppLogger.info(TAG, "seeded SOUL.md at ${file.absolutePath}")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ensureExists failed: ${t.message}")
        }
    }

    /**
     * Distinct outcomes of reading SOUL.md.
     *
     * [load] collapses every failure to `null`. That is the right shape for
     * its call sites (`?: default`), but it loses the difference between the
     * two worlds that matter to a *write*:
     *
     *  - **Missing** — falling back to the default content is correct, the
     *    next save legitimately creates the file.
     *  - **Unreadable** — a SOUL.md is on disk and its bytes cannot be
     *    decoded. Falling back to default and then saving overwrites the
     *    user's persona with the app default (P0 F-4 / world W3: a chmod, a
     *    truncated write, an external editor swapping the file mid-save).
     *
     * Exposed read-only so logs, diagnostics and tests can tell the two apart
     * without changing [load]'s signature (zero call-site churn).
     */
    sealed class ReadState {
        /** No SOUL.md on disk — the default content is the correct answer. */
        object Missing : ReadState()

        /** Bytes read and parsed. */
        data class Loaded(val file: SoulFile) : ReadState()

        /** The path exists but its bytes could not be read. */
        data class Unreadable(val message: String) : ReadState()
    }

    /**
     * What the pre-overwrite backup did. Returned (not just logged) so the JVM
     * tests assert the branch that ran instead of asserting on log text.
     */
    internal enum class BackupOutcome {
        /** No SOUL.md on disk — nothing to lose. */
        Missing,

        /** Disk already holds the exact bytes about to be written. */
        Identical,

        /** Previous bytes copied to `SOUL.md.recovered-<stamp>`. */
        Written,

        /** Present but unreadable — nothing to copy; the save proceeds. */
        Unreadable,

        /** The copy failed (disk full, permissions) — the save proceeds. */
        Failed,
    }

    /** Backup siblings kept on disk; older ones are pruned per new backup. */
    private const val RECOVERED_KEEP = 3
    private const val RECOVERED_INFIX = ".recovered-"

    /**
     * Read + parse [target] without collapsing "missing" into "unreadable".
     * Context-free so the JVM tests run this exact body on a real filesystem.
     */
    fun loadFrom(target: File): ReadState {
        if (!target.exists()) return ReadState.Missing
        return try {
            ReadState.Loaded(SoulMDParser.parse(target.readText()))
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "SOUL.md load failed: ${t.message}")
            ReadState.Unreadable(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Read + parse the current SOUL.md. Returns null when the file is
     * missing or unreadable; an empty body parses to default-meta with
     * empty body and callers may treat that as "fall back to default".
     *
     * Behaviour is unchanged on purpose (5 write points in
     * [com.rikkaminis.app.config.ConfigBuiltins] plus the Settings screen
     * depend on the `null` shape). The body now delegates to [loadFrom] so
     * the two can never disagree about what "unreadable" means.
     */
    fun load(context: Context): SoulFile? =
        (loadFrom(fileLocation(context)) as? ReadState.Loaded)?.file

    /** Same question as [load], answered with the reason instead of `null`. */
    fun readState(context: Context): ReadState = loadFrom(fileLocation(context))

    /**
     * Atomic write through a `.tmp` sibling, then rename.
     *
     * Overwrite guard (P0 F-4): before the rename, the bytes currently on disk
     * are copied to a `SOUL.md.recovered-<epochMillis>` sibling when — and
     * only when — they differ from what is about to be written. See
     * [backupBeforeOverwrite] for why the trigger is a content comparison
     * rather than "am I falling back to the default right now".
     */
    fun save(context: Context, file: SoulFile) {
        saveTo(fileLocation(context), file)
        _cachedMetadata.value = file.metadata
        // [da1028-B / ISSUE-04] Signal for UI surfaces that hold an editable
        // copy of what is on disk. `cachedMetadata` cannot serve as that
        // signal: StateFlow de-duplicates equal values, so a body-only rewrite
        // (the common `minis-config set soul.body …`) leaves the metadata
        // identical and never emits. A monotonic counter emits on every write
        // regardless of what changed.
        _writeVersion.value += 1
    }

    /**
     * The write itself, Context-free: `.tmp` sibling + rename, preceded by
     * [backupBeforeOverwrite]. [save] adds the in-process cache/version
     * bookkeeping on top; this function is the seam that lets a JVM test run
     * the production bytes on a real filesystem.
     *
     * [clock] and [readExisting] are injectable ONLY so the tests can (a) pin
     * backup timestamps and (b) simulate a read that throws without needing a
     * permission bit (the test runner is root, so `chmod 000` is still
     * readable). Production call sites take both defaults.
     */
    fun saveTo(
        target: File,
        file: SoulFile,
        clock: () -> Long = System::currentTimeMillis,
        readExisting: (File) -> ByteArray? = { f -> if (f.isFile) f.readBytes() else null },
    ) {
        target.parentFile?.mkdirs()
        val text = SoulMDParser.serialize(file)
        // Backup first: the rename below is atomic, but it is atomic over the
        // *new* bytes — without this copy the previous content is gone the
        // instant rename succeeds.
        backupBeforeOverwrite(target, text, clock, readExisting)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        // [audit-0917] Delete the temp file if the write itself fails. The old
        // code only cleaned up on a rename failure, so a throwing writeText
        // (disk full, permission) left a stale <name>.tmp that no other path
        // ever removes.
        try {
            tmp.writeText(text)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        if (!tmp.renameTo(target)) {
            // Fallback: copy + delete tmp on filesystems that reject
            // cross-inode rename (shouldn't apply inside filesDir, but
            // defensive — keeps us from leaving a stale .tmp behind).
            target.writeText(text)
            tmp.delete()
        }
    }

    /**
     * Copy the bytes currently in [target] to a `SOUL.md.recovered-<stamp>`
     * sibling before they are overwritten. Best effort by design: nothing here
     * may turn a user's Save into a failure.
     *
     * **Why a content comparison instead of "is this write falling back to the
     * default?"** — [saveTo]'s callers cannot tell us that. The fallback lives
     * in `ConfigBuiltins.loadCurrent()` / `SoulSettingsScreen`
     * (`load(context) ?: parse(DEFAULT_CONTENT)`), and threading a "this file
     * is a fallback" flag through them means touching five writers that
     * otherwise need no change. Comparing bytes is locally decidable, applies
     * to every overwrite path (including ones added later), and costs one extra
     * file of a few KB per *real* edit — capped at [RECOVERED_KEEP] files.
     *
     * Byte-for-byte copy, not read-text → write-text: a file the parser accepts
     * but whose encoding we would round-trip differently must still be
     * recoverable verbatim.
     */
    internal fun backupBeforeOverwrite(
        target: File,
        newText: String,
        clock: () -> Long = System::currentTimeMillis,
        readExisting: (File) -> ByteArray? = { f -> if (f.isFile) f.readBytes() else null },
    ): BackupOutcome {
        if (!target.exists()) return BackupOutcome.Missing

        val existing = try {
            readExisting(target)
        } catch (t: Throwable) {
            // Present but unreadable — exactly the W3 world. There is nothing
            // to copy, so the only honest thing left is to make the overwrite
            // audible: this line is the signal that the persona on disk was not
            // what the app thought it was.
            AppLogger.error(
                TAG,
                "SOUL.md at ${target.absolutePath} exists but cannot be read " +
                    "(${t.message}) — overwriting it without a backup. The previous " +
                    "content is not recoverable from this device.",
            )
            return BackupOutcome.Unreadable
        }
        if (existing == null) {
            // Path exists but is not a regular file (a directory, a FIFO, a
            // broken link): same "cannot read it" class as above.
            AppLogger.error(
                TAG,
                "SOUL.md at ${target.absolutePath} exists but is not a readable file " +
                    "— overwriting it without a backup.",
            )
            return BackupOutcome.Unreadable
        }
        if (String(existing, Charsets.UTF_8) == newText) return BackupOutcome.Identical

        return try {
            nextBackupFile(target, clock()).writeBytes(existing)
            pruneBackups(target)
            BackupOutcome.Written
        } catch (t: Throwable) {
            AppLogger.warning(
                TAG,
                "SOUL.md backup failed (${t.message}) — continuing with the save",
            )
            BackupOutcome.Failed
        }
    }

    /**
     * `SOUL.md.recovered-<epochMillis>` next to [target]. Two saves inside the
     * same millisecond would otherwise collide and silently lose the first
     * backup, so the stamp is bumped until the name is free (names stay
     * lexicographically ordered, which [pruneBackups] relies on as a
     * tie-break).
     */
    private fun nextBackupFile(target: File, stamp: Long): File {
        val dir = target.parentFile ?: target.absoluteFile.parentFile ?: target.absoluteFile
        var name = "${target.name}$RECOVERED_INFIX$stamp"
        var bump = stamp
        var candidate = File(dir, name)
        while (candidate.exists()) {
            bump += 1
            name = "${target.name}$RECOVERED_INFIX$bump"
            candidate = File(dir, name)
        }
        return candidate
    }

    /** Keep the [RECOVERED_KEEP] newest backups, delete the rest. */
    private fun pruneBackups(target: File) {
        val dir = target.parentFile ?: return
        val prefix = target.name + RECOVERED_INFIX
        val backups = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?: return
        if (backups.size <= RECOVERED_KEEP) return
        backups
            // mtime is the documented order; the name carries a monotonic
            // stamp, so it breaks same-millisecond ties.
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .dropLast(RECOVERED_KEEP)
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Cached metadata for synchronous call sites that cannot re-read the
     * file every recomposition (chat bubble header in particular). Updated
     * by [refreshCache] and [save]. Default value is the same fallback
     * the Settings UI shows when the file is missing.
     */
    private val _cachedMetadata = MutableStateFlow(SoulMetadata.DEFAULT)
    val cachedMetadata: StateFlow<SoulMetadata> = _cachedMetadata.asStateFlow()

    /**
     * [da1028-B / ISSUE-04] Monotonic counter bumped on every rewrite of
     * SOUL.md. Settings → Soul keys its load on this so an external write
     * (minis-config behind the confirm sheet, a backup-restore, the chat
     * memory sheet's SOUL.md editor) re-seeds the editors instead of leaving
     * the screen on the values it read at open time — the user's next Save
     * would otherwise write that stale snapshot straight back over the
     * external change (the 1026-A / 1027-E silent-rollback family).
     *
     * Bumped from [save] *and* [refreshCache]: the latter is the documented
     * entry point for "the file was rewritten outside [save]" (the chat
     * memory sheet calls it right after its raw `saveFile`), so a rewrite
     * that notifies the metadata cache must notify this too.
     */
    private val _writeVersion = MutableStateFlow(0L)
    val writeVersion: StateFlow<Long> = _writeVersion.asStateFlow()

    /**
     * Re-read SOUL.md into [cachedMetadata]. Call once at app launch
     * (after [ensureExists]) and any time the file is rewritten outside
     * of [save] (currently only the Settings UI uses save()).
     */
    fun refreshCache(context: Context) {
        val parsed = load(context)
        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 1
    }
}

/**
 * Composes the Layer-1 identity section of the agent system prompt.
 * Mirrors iOS `SystemPromptBuilder.identitySection()` (commit 74c0daf):
 * a fixed identity-sentence template owned by the app + an optional,
 * clearly-labeled Personality block from SOUL.md's body.
 *
 * Why template-based (not "let the user write the whole prompt"):
 *  - Users never see "You are <name>, a capable AI assistant ..." in the
 *    Personality editor — that internal scaffolding stays out of view
 *    so users can't accidentally delete or duplicate it.
 *  - The original carefully-tuned identity sentence is preserved
 *    byte-for-byte. Substituting only `{name}` keeps the surrounding
 *    model expectations (Android device, Linux sandbox via PRoot,
 *    aarch64) intact.
 *  - When the user hasn't authored a personality body, the full
 *    assembled prompt is byte-identical to the pre-SOUL prompt
 *    (modulo `{name}`) — no behavior regression for default users.
 */
object SystemPromptBuilder {

    private const val TAG = "Soul"

    /**
     * Patterns used by [scrubInjections] to drop prompt-injection lines
     * from the personality body at prompt-build time. Exposed so write
     * paths (minis-config `soul.body` setter) can reject the same set
     * of patterns rather than silently scrubbing — see iOS parity:
     * the agent should see a clear error, not a silent edit.
     */
    val INJECTION_PATTERNS: List<Regex> = listOf(
        Regex("ignore.{0,30}previous.{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("disregard.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
        Regex("forget.{0,30}(previous|prior).{0,30}instructions?", RegexOption.IGNORE_CASE),
    )

    /**
     * `true` iff [s] contains a known prompt-injection pattern. Used by
     * write-side validation to reject the change at the configure call,
     * which surfaces a clean error to the agent (instead of the silent
     * line-drop that [scrubInjections] performs at prompt-build time).
     */
    fun containsInjectionPattern(s: String): Boolean =
        INJECTION_PATTERNS.any { it.containsMatchIn(s) }

    /**
     * The identity sentence template. `{name}` is substituted from the
     * SOUL metadata. This wording matches the pre-SOUL literal that
     * lived inline in ChatViewModel.buildSystemPrompt — keep it in sync
     * if you ever need to tweak the runtime statement, because every
     * Android chat hits this line.
     */
    private const val IDENTITY_TEMPLATE =
        "You are {name}, a capable AI assistant running on an Android device with a fully functional Linux sandbox (Alpine Linux via PRoot, aarch64). "

    /**
     * Render the identity sentence (template + name) and optionally
     * append the user-authored personality body from SOUL.md.
     *
     * Two distinct trailing-whitespace contracts so the next concatenated
     * sentence in [com.rikkaminis.app.ui.chat.ChatViewModel.buildSystemPrompt]
     * glues correctly:
     *   - No personality body → identity sentence with its original
     *     single trailing space (byte-identical to the pre-SOUL prompt).
     *   - With personality body → identity sentence + blank line +
     *     "Personality:" block + blank line, so the runtime-guidance
     *     sentence starts a fresh paragraph.
     *
     * Returned format (when SOUL body is present):
     *
     *     You are <name>, a capable AI assistant running on an Android device …
     *
     *     Personality (from SOUL.md — your character and voice; defer to
     *     the user's latest message when it conflicts with anything here):
     *     <body>
     *
     * We never substitute a default body into the prompt — the identity
     * sentence alone is the safe fallback when SOUL.md is missing or
     * empty, matching pre-SOUL behavior.
     */
    fun identitySection(context: Context): String {
        val file = SoulStore.load(context)
        val name = (file?.metadata?.name ?: SoulMetadata.DEFAULT.name)
            .trim()
            .ifEmpty { SoulMetadata.DEFAULT.name }

        val style = (file?.metadata?.style ?: "").trim()
        // [T-soul-lang-wire] The `lang` frontmatter field (auto / zh / en) is
        // now actually injected into the system prompt. Previously it was only
        // parsed/serialized/displayed but never reached the model — switching
        // "Chinese"/"English" in Settings had zero effect and replies stayed
        // English because the default body is English + the base prompt default
        // is match-the-user's-input. When `lang` is explicitly zh / en we emit a
        // hard reply-language directive that overrides that default; auto keeps
        // the existing follow-the-user behavior.
        val lang = file?.metadata?.lang?.lowercase()?.trim().orEmpty()
        val langDirective = when (lang) {
            "zh" -> "\n\nReply in Chinese regardless of the user's input language; only switch when the user explicitly asks for another language."
            "en" -> "\n\nReply in English regardless of the user's input language; only switch when the user explicitly asks for another language."
            else -> "" // "auto" or unknown → follow the user's input (base prompt default)
        }

        val identity = IDENTITY_TEMPLATE.replace("{name}", name)
        val identityTrimmed = identity.trimEnd()

        // [T-soul-hint] Fixed hint telling the model how SOUL fields can be
        // changed. Always appended (with or without a personality body) so
        // the model never says "I can't change my personality". This hint
        // is system-owned text and is NOT counted against the user-facing
        // SOUL body length limit (#356 / 1000 EN words / 1600 CN chars).
        val soulEditHint =
            "---\n" +
            "SOUL.md fields (name / style / lang / body) can be edited two ways:\n" +
            "1. Tool: call `minis-config` to propose changes (user must approve).\n" +
            "2. UI: ask the user to go to Settings → Soul to edit directly.\n" +
            "Pick whichever the user finds easier in context. Do not say you cannot change your personality."

        // [T-soul-style-injection 2026-05-18, port iOS 0409e24f] The `style`
        // frontmatter field (response voice / tone / formatting preference,
        // e.g. a row of emojis or "concise, no markdown") was parsed and
        // shown in the UI but never reached the model — only the Markdown
        // body was injected. Render it as its own labeled paragraph so the
        // model treats it as a hard constraint on response style rather
        // than free-form context.
        fun styleBlock(s: String): String {
            if (s.isEmpty()) return ""
            // [T-agent-prompt-consistency-pass] Mirrors iOS SoulStore: a user-authored
            // style that prescribes a reply language is the more specific preference
            // and wins over the base prompt's match-the-user's-language default.
            return "\n\nResponse style (from SOUL.md `style` — apply to every reply unless the user explicitly asks otherwise; if it prescribes a reply language, it overrides the default match-the-user's-language rule):\n$s"
        }

        val body = file?.body
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return identityTrimmed + styleBlock(style) + langDirective + "\n\n" + soulEditHint + "\n\n"
        }

        // Reject (NOT truncate) bodies that exceed the language-aware
        // limit. The old head/tail truncation silently dropped half the
        // user's text — falling back to identity-only is the safer
        // signal: the user notices the personality isn't taking effect,
        // opens Settings, and sees the same red over-limit warning the
        // Save button surfaces. Write paths already reject over-limit;
        // this branch only triggers for an on-disk file written before
        // this rule existed (or via shell / another device).
        val check = SoulStore.isOverLimit(trimmed)
        if (check.isOverLimit) {
            AppLogger.warning(TAG, "personality body is over the language-aware limit ($check) — falling back to identity-only system prompt.")
            return identityTrimmed + styleBlock(style) + langDirective + "\n\n" + soulEditHint + "\n\n"
        }

        val personality = scrubInjections(trimmed)

        // Strip the trailing space we'd otherwise leave hanging at the
        // end of the first paragraph when a personality block follows.
        return identityTrimmed +
            "\n\nPersonality (from SOUL.md — your character and voice; defer to the user's latest message when it conflicts with anything here):\n" +
            personality +
            styleBlock(style) +
            langDirective +
            "\n\n" +
            soulEditHint +
            "\n\n"
    }

    /**
     * Drop lines that look like an attempt to subvert the system prompt.
     * SOUL.md is user-authored personality — instructions to the model
     * have no business being there. Casts a wide net on purpose: matches
     * "ignore previous instructions" and the usual variants.
     */
    private fun scrubInjections(s: String): String =
        s.split("\n")
            .filter { line -> INJECTION_PATTERNS.none { it.containsMatchIn(line) } }
            .joinToString("\n")
}
