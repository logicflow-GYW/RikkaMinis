package com.rikkaminis.app.backup

/**
 * [feat/auto-backup-external-mirror] The decisions behind the external mirror:
 * what changed since the last sync, whether now is a good moment to write, and
 * which old copies may be dropped. All of it pure — no Android, no filesystem,
 * no clock — so the JVM harness pins it byte for byte (MirrorPlanTest).
 *
 * Shape (decided with the user 2026-10-29, `design/EXPECTED-BEHAVIOR-*.md`):
 *
 *  - `mirror/current` always equals the newest state of the selected trees. A
 *    loss is recovered by copying files back — one step, no patch chain.
 *  - `mirror/previous/<stamp>` holds the copies that were OVERWRITTEN at that
 *    sync (only the changed files, not the whole tree). This is what makes the
 *    mirror safe against the mirror's own blind spot: if a document is edited
 *    into a broken state, `current` faithfully carries the broken version, and
 *    `previous` is the only way back to the last good one. Rolling content, not
 *    a differential chain — restoring never means applying a sequence.
 *
 * Why a minimum interval at all: a sync costs one stat per selected file
 * (~1810 measured 2026-10-29) plus the bytes that actually changed. Re-running
 * on every stream settlement is cheap; the interval exists so a burst of
 * settlements (tool-heavy turns ending seconds apart) does not pay the walk
 * over and over for a few hundred bytes. Ten minutes is the user-approved
 * value: below it, the recovery value of a newer copy does not cover the cost.
 */
internal object MirrorPlan {

    /** User-approved throttle (2026-10-29): a sync at most every 10 minutes. */
    const val MIN_INTERVAL_MS = 10L * 60 * 1000

    /** How many `previous/` points to keep. Three independent moments of
     *  "before the edit" — enough to notice a bad edit and step back without
     *  turning the folder into a version-control system. */
    const val PREVIOUS_KEEP = 3

    /** One selected file's identity. [modifiedMs] is the file's own mtime; the
     *  pair (size, modifiedMs) is the change key. Hashing every selected file
     *  on every sync would cost more than the sync saves; a same-second,
     *  same-size edit is the accepted blind spot (documented, not hidden). */
    data class Entry(val relPath: String, val size: Long, val modifiedMs: Long)

    /** What a sync would do, computed from the manifest and the current walk. */
    data class Diff(
        /** Present now, absent from the manifest. */
        val added: List<Entry>,
        /** Present in both, but size or mtime moved. */
        val changed: List<Entry>,
        /** Absent now, present in the manifest — the file was deleted at the
         *  source, so the mirror copy must go too (its old bytes still land in
         *  `previous/<stamp>`). */
        val removed: List<String>,
        /** Present in both with identical size and mtime. */
        val unchanged: Int,
    ) {
        val hasChanges: Boolean get() = added.isNotEmpty() || changed.isNotEmpty() || removed.isNotEmpty()

        /** Files that must be WRITTEN to `current`. */
        val toWrite: List<Entry> get() = added + changed
    }

    /**
     * Compare the manifest against the current selection.
     *
     * Duplicate relPaths in [current] are impossible by construction
     * ([MirrorScope.select] returns a sorted list of leaves), so the second
     * occurrence simply wins here rather than throwing at sync time.
     */
    fun diff(previous: List<Entry>, current: List<Entry>): Diff {
        val before = HashMap<String, Entry>(previous.size * 2)
        for (e in previous) before[e.relPath] = e
        val added = ArrayList<Entry>()
        val changed = ArrayList<Entry>()
        var unchanged = 0
        val seen = HashSet<String>(current.size * 2)
        for (c in current) {
            if (!seen.add(c.relPath)) continue
            val old = before[c.relPath]
            when {
                old == null -> added.add(c)
                old.size != c.size || old.modifiedMs != c.modifiedMs -> changed.add(c)
                else -> unchanged++
            }
        }
        val removed = previous.asSequence()
            .map { it.relPath }
            .filter { it !in seen }
            .toList()
        return Diff(added, changed, removed, unchanged)
    }

    /**
     * Whether to actually write now.
     *
     * [hasChanges] false always means no: rewriting an identical tree would
     * move `syncedAt` forward and make a broken pipeline look healthy. When
     * there ARE changes, the first sync after a fresh install (no
     * `lastSyncMs`) runs immediately; afterwards the interval applies.
     */
    fun shouldWrite(
        lastSyncMs: Long?,
        nowMs: Long,
        hasChanges: Boolean,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean {
        if (!hasChanges) return false
        if (lastSyncMs == null) return true
        if (nowMs < lastSyncMs) return true // clock rolled back: "unknown age" is not "too recent"
        return nowMs - lastSyncMs >= minIntervalMs
    }

    /**
     * `previous/` points to delete: everything beyond the newest [keep], newest
     * first by their stamp names. Names that do not parse (a foreign directory
     * a user dropped in) are never victims — same rule as [BackupRotation].
     */
    fun previousVictims(points: List<String>, keep: Int = PREVIOUS_KEEP): List<String> {
        val stamped = points.filter { BackupRotation.hasStamp(it, "", "") }
            .sortedWith(Comparator { a, b ->
                val ka = BackupRotation.stampMillis(a, "", "") ?: Long.MIN_VALUE
                val kb = BackupRotation.stampMillis(b, "", "") ?: Long.MIN_VALUE
                if (ka != kb) kb.compareTo(ka) else b.compareTo(a)
            })
        return if (keep <= 0) stamped else stamped.drop(keep)
    }

    // ---------------------------------------------------------------- manifest
    //
    // Plain text, one record per line, hand-parsed. Deliberately NOT org.json:
    // android.jar's org.json and the JVM test jar differ in escaping and null
    // handling, and a manifest that decodes differently on two platforms is a
    // silent "everything changed, resync all" (or worse, "nothing changed").
    // Paths containing tab/newline are excluded by [MirrorScope], which is what
    // makes the one-record-per-line encoding safe.

    /** Current manifest format version. An unknown version is treated as "no
     *  manifest" (full resync) rather than being guessed at. */
    const val MANIFEST_VERSION = 1

    const val MANIFEST_NAME = "manifest.json"

    data class Manifest(
        /** Wall-clock of the last WRITTEN sync. Null before the first one. */
        val syncedAtMs: Long,
        val entries: List<Entry>,
        /** Existing `previous/<stamp>` directory names, newest first. */
        val previousPoints: List<String>,
    )

    fun encodeManifest(m: Manifest): String {
        val sb = StringBuilder(m.entries.size * 48 + 128)
        sb.append("v").append(MANIFEST_VERSION).append('\n')
        sb.append("synced\t").append(m.syncedAtMs).append('\n')
        for (p in m.previousPoints) {
            if (p.contains('\n') || p.contains('\t')) continue
            sb.append("prev\t").append(p).append('\n')
        }
        for (e in m.entries) {
            sb.append("file\t").append(e.size).append('\t')
                .append(e.modifiedMs).append('\t').append(e.relPath).append('\n')
        }
        return sb.toString()
    }

    /**
     * Parse a manifest. Returns null for ANY deviation (bad header, bad number,
     * truncated last line) — the caller treats null as "no manifest", i.e. a
     * full resync into `current` with `previous` untouched. That is the safe
     * direction: the mirror is rebuilt, no history is deleted, and the next
     * sync has a valid manifest again. Guessing at a damaged manifest is how a
     * restore discovers it was protecting the wrong state.
     */
    fun decodeManifest(text: String?): Manifest? {
        if (text.isNullOrEmpty()) return null
        val lines = text.split('\n')
        if (lines.isEmpty()) return null
        if (lines[0] != "v$MANIFEST_VERSION") return null
        var syncedAt = -1L
        val points = ArrayList<String>()
        val entries = ArrayList<Entry>()
        var sawSynced = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue // trailing newline
            val parts = line.split('\t')
            when (parts[0]) {
                "synced" -> {
                    if (parts.size != 2) return null
                    syncedAt = parts[1].toLongOrNull() ?: return null
                    sawSynced = true
                }
                "prev" -> {
                    if (parts.size != 2 || parts[1].isEmpty()) return null
                    points.add(parts[1])
                }
                "file" -> {
                    if (parts.size != 4) return null
                    val size = parts[1].toLongOrNull() ?: return null
                    val mtime = parts[2].toLongOrNull() ?: return null
                    val rel = parts[3]
                    if (rel.isEmpty()) return null
                    entries.add(Entry(rel, size, mtime))
                }
                else -> return null
            }
        }
        if (!sawSynced) return null
        return Manifest(syncedAt, entries, points)
    }

    /** A manifest that describes [entries] written at [nowMs], keeping at most
     *  [keep] previous points (newest kept). */
    fun nextManifest(
        nowMs: Long,
        entries: List<Entry>,
        existingPoints: List<String>,
        newPointName: String?,
        keep: Int = PREVIOUS_KEEP,
    ): Manifest {
        val points = if (newPointName.isNullOrEmpty()) existingPoints else existingPoints + newPointName
        val kept = (if (keep <= 0) emptyList() else {
            val victims = previousVictims(points, keep)
            points.filterNot { it in victims }
        }).sortedDescending()
        return Manifest(nowMs, entries, kept)
    }
}
