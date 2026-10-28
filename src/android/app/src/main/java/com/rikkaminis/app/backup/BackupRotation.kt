package com.rikkaminis.app.backup

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [FIX-1028 / B-12] The ONE ordering key for every file this app rotates, and
 * the ONE deletion rule built on it.
 *
 * The defect this replaces (world W7, clock that jumps): every list — pre-restore
 * snapshots, local auto backups, remote auto backups — was ordered by
 * `File.lastModified()` / the server's `getlastmodified`, and rotation then
 * deleted "everything past the newest N". Three ways that goes wrong, all
 * silent:
 *  - a restore performed after a clock rollback writes a snapshot whose mtime
 *    looks OLDER than the one already on disk, so pruning deletes the file it
 *    just wrote — the only rollback point for the restore the user just did;
 *  - a WebDAV server that omits `getlastmodified` makes every entry
 *    `Instant.EPOCH` ([WebDavSync.resourceToBackupItem]), i.e. all entries tie
 *    at the bottom and "newest first" is really listing order — rotation then
 *    evicts an arbitrary copy, possibly the fresh upload;
 *  - one `touch` (or a backup restored via a file manager) reorders history
 *    without changing any content.
 *
 * The stamp is already IN the file name — every writer here names its output
 * `<prefix>yyyyMMdd-HHmmss<suffix>` ([BackupRotation.stamp]) — and a name is
 * immune to `touch`, to clock rollback during a later write, and to a server
 * that will not tell us when the file was made.
 *
 * Deliberately pure `java.io`/`java.text` (no Android, no Context): the JVM
 * harness compiles this file verbatim and the ordering/deletion rules are
 * pinned there (BackupRotationTest).
 */
internal object BackupRotation {

    /** The stamp every writer embeds in its file name. Two places produced this
     *  format before (snapshots, auto backups + remote pushes); they now share
     *  this one literal, because the readers below parse exactly this shape. */
    const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

    /** Length of a canonical stamp (`20261028-120000`), digits + one dash. */
    const val STAMP_LENGTH = 15

    /** Minute-precision stamp (`20261028-1200`) — the shape
     *  [ConfigBackup.suggestedFileName] gives a LOCAL export, and therefore
     *  also the shape of any export a user dropped into the WebDAV folder by
     *  hand. Accepted when reading so those files keep an age; never written
     *  by the rotation-relevant paths (they are second-precision). */
    const val MINUTE_STAMP_PATTERN = "yyyyMMdd-HHmm"

    /** Length of a minute-precision stamp. */
    const val MINUTE_STAMP_LENGTH = 13

    /** `yyyyMMdd-HHmmss` for [now]. */
    fun stamp(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(now))

    /**
     * Epoch millis encoded in [name]'s stamp, or null when the name does not
     * carry one.
     *
     * Null is a MEANINGFUL answer — "this file's age cannot be established from
     * its name" — and both callers below treat it as "never delete" rather than
     * "oldest", which is the whole point: a file whose name came from a foreign
     * producer ([WebDavSync.sanitizeAutoBackupStamp] passes arbitrary
     * server-side stamps through) must not be the one rotation picks.
     *
     * Accepted shapes:
     *  - `<prefix>yyyyMMdd-HHmmss<suffix>` — the canonical name;
     *  - `<prefix>yyyyMMdd-HHmmss-<digits><suffix>` — the same-second collision
     *    suffix [ConfigBackup.allocateSnapshotFile] mints.
     * Anything else after the prefix (a different scheme, a foreign stamp) is
     * null. Parsing is STRICT (`isLenient = false`): `20261308-000000` is not
     * a date, and silently rolling it into January 2027 would put a corrupt
     * name in charge of rotation.
     */
    fun stampMillis(name: String, prefix: String, suffix: String = ".json"): Long? {
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) return null
        val middle = name.substring(prefix.length, name.length - suffix.length)
        // Second precision first: it is the only shape the rotation-relevant
        // writers produce, so it wins when a name could parse either way.
        for ((pattern, len) in STAMP_SHAPES) {
            if (middle.length < len) continue
            val raw = middle.substring(0, len)
            val tail = middle.substring(len)
            if (!hasStampShape(raw, len)) continue
            // Only the collision suffix may follow the stamp.
            if (tail.isNotEmpty() &&
                !(tail.startsWith('-') && tail.length > 1 && tail.substring(1).all { it.isDigit() })
            ) {
                return null
            }
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val pos = ParsePosition(0)
            val parsed = fmt.parse(raw, pos) ?: return null
            if (pos.index != len) return null
            return parsed.time
        }
        return null
    }

    private val STAMP_SHAPES = listOf(
        STAMP_PATTERN to STAMP_LENGTH,
        MINUTE_STAMP_PATTERN to MINUTE_STAMP_LENGTH,
    )

    /** `yyyyMMdd-HHmm…` — digits, one dash at index 8. Checked before the
     *  formatter sees the text so a name like `2026-10-28-abcd` is "no stamp"
     *  rather than a parse error rolled into some other date. */
    private fun hasStampShape(raw: String, len: Int): Boolean {
        for (i in 0 until len) {
            if (i == 8) {
                if (raw[i] != '-') return false
            } else if (!raw[i].isDigit()) {
                return false
            }
        }
        return true
    }

    /** True when [name] carries a parseable stamp under [prefix]. */
    fun hasStamp(name: String, prefix: String, suffix: String = ".json"): Boolean =
        stampMillis(name, prefix, suffix) != null

    /**
     * Newest first by stamp. Entries without a stamp sort LAST (they are
     * display-only — see [rotationVictims]) and among themselves by name
     * descending so the order is stable across calls and platforms.
     * Same-second ties resolve by name descending, which puts the `-2`
     * collision copy (written later) ahead of the stem — same rule
     * [ConfigBackup.allocateSnapshotFile] applies when it mints names.
     */
    fun <T> newestFirst(
        items: List<T>,
        name: (T) -> String,
        key: (T) -> Long?,
        suffix: String = ".json",
    ): List<T> = items.sortedWith(ordering(name, key, suffix))

    /**
     * The entries rotation may delete: everything past the first [keep]
     * stamped entries, newest first, minus [protect].
     *
     * Two deliberate exclusions, both "rather keep a file than delete the wrong
     * one":
     *  - entries with NO stamp are never victims (age unknown ⇒ not a candidate);
     *  - [protect] is never a victim, which is how "the copy I just wrote" stays
     *    on disk even when a rolled-back clock gave it an old-looking name.
     *
     * [keep] <= 0 means "no history": every stamped entry is a victim (the
     * explicit branch both call sites already rely on).
     */
    fun <T> rotationVictims(
        items: List<T>,
        name: (T) -> String,
        key: (T) -> Long?,
        keep: Int,
        protect: (T) -> Boolean = { false },
        suffix: String = ".json",
    ): List<T> {
        val stamped = items.filter { key(it) != null }.sortedWith(ordering(name, key, suffix))
        val beyond = if (keep <= 0) stamped else stamped.drop(keep)
        return beyond.filterNot(protect)
    }

    private fun <T> ordering(
        name: (T) -> String,
        key: (T) -> Long?,
        suffix: String,
    ): Comparator<T> =
        Comparator { a, b ->
            val ka = key(a)
            val kb = key(b)
            when {
                ka != null && kb != null ->
                    if (ka != kb) kb.compareTo(ka) else tieBreak(name(a), name(b), suffix)
                ka != null -> -1
                kb != null -> 1
                else -> tieBreak(name(a), name(b), suffix)
            }
        }

    /**
     * Same stamp (two writes inside one second): the collision copy comes
     * first, because [ConfigBackup.allocateSnapshotFile] mints `-2`, `-3`… for
     * the LATER write. Plain string order put `…-120000.json` ahead of
     * `…-120000-2.json` (`-` sorts before `.`), i.e. the newest of two
     * same-second rollback points was listed second — and the one-tap rollback
     * takes the first row.
     *
     * Recognising the shape needs no prefix: `a` is the collision copy of `b`
     * when `a` is exactly `b`'s stem plus `-<digits>`. Anything else falls back
     * to descending name order (stable, platform-independent).
     */
    private fun tieBreak(nameA: String, nameB: String, suffix: String): Int {
        val a = stripSuffix(nameA, suffix)
        val b = stripSuffix(nameB, suffix)
        if (isCollisionCopyOf(a, b)) return -1
        if (isCollisionCopyOf(b, a)) return 1
        return nameB.compareTo(nameA)
    }

    private fun stripSuffix(name: String, suffix: String): String =
        if (suffix.isNotEmpty() && name.endsWith(suffix)) {
            name.substring(0, name.length - suffix.length)
        } else {
            name
        }

    private fun isCollisionCopyOf(candidate: String, base: String): Boolean {
        if (!candidate.startsWith("$base-")) return false
        val tail = candidate.substring(base.length + 1)
        return tail.isNotEmpty() && tail.all { it.isDigit() }
    }
}
