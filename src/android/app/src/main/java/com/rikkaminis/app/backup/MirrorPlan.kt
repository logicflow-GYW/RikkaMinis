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
 * on every stream settlement is cheap; the coalescing window exists so a burst
 * of settlements (tool-heavy turns ending seconds apart) does not pay the walk
 * over and over for a few hundred bytes.
 *
 * 2026-10-29 silent-mirror revision: the old single 10-minute constant gated
 * FRESHNESS, not cost — `shouldWrite` requires `hasChanges`, so it was only
 * ever hit when real work was piling up, i.e. it precisely blocked the
 * productive case (and the walk it was thought to save still ran: the walk
 * precedes the check in `MirrorStore.runLocked`). The two jobs it was
 * conflating are now separate: a 60 s write-coalescing window (cost of one
 * MediaStore copy per change burst) and a 10-minute `previous/` point spacing
 * (rollback history density). The settings row lost its "sync now" button —
 * the mirror runs silently, and the row is passive: healthy is one line of
 * text, only an unhealthy verdict gets a retry action.
 */
internal object MirrorPlan {

    /** Write coalescing window: with changes, a write at most every 60 s.
     *  Saves the MediaStore copy of a settlement burst; never gates freshness
     *  beyond one minute, and never applies to a forced sync. */
    const val WRITE_COALESCE_MS = 60L * 1000

    /** Minimum spacing between `previous/` points. With [PREVIOUS_KEEP] = 3,
     *  points spaced 10 minutes apart span at least 30 minutes of rollback
     *  history even though writes now coalesce at 60 s. The trade: an
     *  overwrite inside the window does not archive its old bytes — the
     *  selected trees are app-generated documents, so a 10-minute-old
     *  intermediate is acceptable to lose; what must not shrink is the
     *  horizon (the pre-existing R2 risk: a bad edit synced 4 times being
     *  unrecoverable). */
    const val POINT_SPACING_MS = 10L * 60 * 1000

    /** Status row: "pending > 0 and this much time since the last write" is
     *  stale, not coalescing. Below it, a non-zero pending count is the normal
     *  state of an actively used mirror. Above it, the only explanation is
     *  that the trigger chain is not firing — that is the warning. */
    const val STALE_AFTER_MS = 30L * 60 * 1000

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
     * Whether to write now (the coalescing window, not freshness).
     *
     * [hasChanges] false always means no: rewriting an identical tree would
     * move `syncedAt` forward and make a broken pipeline look healthy. When
     * there ARE changes, the first sync after a fresh install (no
     * `lastSyncMs`) runs immediately; afterwards the 60 s window applies.
     *
     * A caller with human intent (the status row's retry) passes a forced sync
     * instead of a shorter interval — see `MirrorStore.syncNow`.
     */
    fun shouldWrite(
        lastSyncMs: Long?,
        nowMs: Long,
        hasChanges: Boolean,
        minIntervalMs: Long = WRITE_COALESCE_MS,
    ): Boolean {
        if (!hasChanges) return false
        if (lastSyncMs == null) return true
        if (nowMs < lastSyncMs) return true // clock rolled back: "unknown age" is not "too recent"
        return nowMs - lastSyncMs >= minIntervalMs
    }

    /**
     * Whether this sync may mint a new `previous/` point.
     *
     * Independent of [shouldWrite] on purpose: writes coalesce at 60 s, points
     * at 10 minutes. Points are the rollback history, and history density must
     * not follow write frequency — three points minted in three minutes cover
     * three minutes. Yes when there are no points yet, when the newest is at
     * least [spacingMs] old, and when the clock rolled back (unknown age is
     * not "recent enough" — same polarity as [shouldWrite]). Names that do not
     * parse carry no age and must not block minting, same rule as
     * [previousVictims] uses for deletion.
     */
    fun shouldMintPoint(
        previousPoints: List<String>,
        nowMs: Long,
        spacingMs: Long = POINT_SPACING_MS,
    ): Boolean {
        val newest = previousPoints.asSequence()
            .filter { BackupRotation.hasStamp(it, "", "") }
            .mapNotNull { BackupRotation.stampMillis(it, "", "") }
            .maxOrNull() ?: return true
        if (nowMs < newest) return true
        return nowMs - newest >= spacingMs
    }

    // ---------------------------------------------------------------- health

    /**
     * Kind of a recorded outcome line — the text [MirrorStore.record] stores in
     * `KEY_RESULT`. Pure so the status row branches on a value the JVM harness
     * pins, instead of parsing strings in Compose. Unrecognised text answers
     * "unknown", which every caller treats as "not a failure".
     */
    fun outcomeKind(text: String?): String = when {
        text == null -> "none"
        text.startsWith("ok ") -> "ok"
        text == "no-changes" -> "no-changes"
        text == "throttled" -> "throttled"
        text == "busy" -> "busy"
        text == "unavailable" -> "unavailable"
        text == "disabled" -> "disabled"
        text.startsWith("failed") -> "failed"
        else -> "unknown"
    }

    /**
     * The settings row's verdict. 2026-10-29 silent-mirror revision: the "sync
     * now" row is gone — the mirror runs silently, so this row must answer "is
     * it alive" by itself and turn into a warning ONLY when something is
     * actually wrong. Otherwise a passive row that can be wrong silently is
     * the same failure the button had, minus the button.
     *
     * Precedence:
     *  - [Health.Disabled] — the toggle is off;
     *  - [Health.Failed] — the last run failed or found no usable target. It
     *    outranks staleness because it explains it;
     *  - [Health.Never] — enabled, nothing written yet ([pending] > 0 is its
     *    warning form: the first trigger writes immediately, so "never" plus
     *    waiting files means the chain is not firing);
     *  - [Health.Stale] — files waiting AND the last write is [staleAfterMs]
     *    old. A 60 s coalescing window never looks like this;
     *  - [Health.Healthy] — the default reading. `pending > 0` alone is NOT
     *    unhealthy: it is the normal state of an actively used mirror.
     *
     * [pending] null means the target could not be read at all — the row shows
     * no count rather than claiming zero.
     */
    sealed class Health {
        /** Mirror switched off; the row is not rendered. */
        object Disabled : Health()

        /** Enabled, nothing written yet. `pending > 0` is the warning form. */
        data class Never(val pending: Int?) : Health()

        /** Normal. `pending > 0` is coalescing, not staleness. */
        data class Healthy(val pending: Int?) : Health()

        /** Waiting files plus a stale last write: the trigger chain is dead.
         *  [minutes] is the idle time in whole minutes. */
        data class Stale(val pending: Int?, val minutes: Long) : Health()

        /** The last run failed (or found no target). [reason] is the recorded
         *  diagnostic, null for "no usable target". */
        data class Failed(val reason: String?) : Health()
    }

    fun classifyHealth(
        enabled: Boolean,
        lastSyncMs: Long,
        pending: Int?,
        lastOutcome: String?,
        nowMs: Long,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Health {
        if (!enabled) return Health.Disabled
        when (outcomeKind(lastOutcome)) {
            "failed" -> return Health.Failed(lastOutcome?.removePrefix("failed: "))
            "unavailable" -> return Health.Failed(null)
        }
        if (lastSyncMs <= 0L) return Health.Never(pending)
        val idle = nowMs - lastSyncMs
        if (pending != null && pending > 0 && idle >= staleAfterMs) {
            return Health.Stale(pending, idle / 60_000L)
        }
        return Health.Healthy(pending)
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
