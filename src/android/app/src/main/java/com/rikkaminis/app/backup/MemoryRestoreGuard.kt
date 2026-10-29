package com.rikkaminis.app.backup

import org.json.JSONArray
import java.io.File

/**
 * [FIX-1028B / backlog L9033] Stage 6 of [ConfigBackup.import] used to write
 * every `memoryFiles` entry over the local copy unconditionally:
 *
 * ```
 * memoryRepo.saveFile(name, content)   // whole-file overwrite, no questions
 * ```
 *
 * `memoryFiles` is not a static asset bundle — it is `GLOBAL.md`, every
 * `YYYY-MM-DD.md` daily log and `MEMORY-ROLLUP.md`, i.e. the one directory on
 * the device that is *appended to continuously* (memory_write on every agent
 * turn). Restoring any backup therefore truncated the memory directory back to
 * the export moment: the daily log written after the backup was replaced by the
 * backup's copy of it (or, for a log the backup predates, left alone only
 * because it is not in the payload at all), and GLOBAL.md / the rollup silently
 * rolled back. The pre-restore snapshot is the only net, and nothing told the
 * user it had been needed.
 *
 * The backlog entry recorded this as a race ("entries written during the
 * 14:50→14:55 window"), which is the *narrow* form: no race is required, any
 * restore of an older backup does it.
 *
 * Direction of the fix: memory is append-only user data, so the *newer* copy
 * wins per file — the backup may only fill in what is missing or untouched.
 * That is deliberately the conservative half of "restore": it cannot lose an
 * entry, at the cost of not being able to roll memory back (see the residual in
 * `fix-reports/FIX-1028B-MEMORY-GUARD.md` for the "force memory rollback" gap).
 */
internal sealed class MemoryRestoreDecision {
    /** No local copy, or the local copy is not newer than the backup. */
    internal data object Restore : MemoryRestoreDecision()

    /**
     * The local copy wins. [reason] is appended to `ImportResult.skipped`
     * verbatim (and logged line by line by `ConfigBackup.import`) — the
     * existing "deliberately not applied" channel, so the user is told instead
     * of being shown a clean restore.
     */
    internal data class KeepLocal(val reason: String) : MemoryRestoreDecision()
}

/**
 * Per-file decision for one `memoryFiles` entry.
 *
 * @param localExists whether the memory dir already holds [name]. A device that
 *   never had the file (fresh install / first sync of a daily log) is the only
 *   case where the backup is the sole owner of the content.
 * @param localLastModified `File.lastModified()` of the local copy, in epoch
 *   millis. `0` means "cannot tell" (deleted between exists() and the stat, or
 *   an unreadable inode) and is treated as *not* safe to overwrite.
 * @param backupCreatedAt the payload's document-level timestamp (epoch millis)
 *   as resolved by [documentCreatedAt] — `createdAt` when the payload has one,
 *   else the `.minisbak` converter's `exportedAt`. `<= 0` means the payload
 *   carries no usable timestamp, so no ordering can be established.
 *
 * Three "cannot decide" outcomes all fall to [MemoryRestoreDecision.KeepLocal]:
 * an unreadable local mtime, a payload without `createdAt`, and (below) a local
 * copy that is strictly newer. Fail-closed is the only defensible default here:
 * the cost of wrongly overwriting is a lost memory entry, the cost of wrongly
 * keeping is a restore that reports the file as kept.
 */
internal fun decideMemoryRestore(
    name: String,
    localExists: Boolean,
    localLastModified: Long,
    backupCreatedAt: Long,
    // [FIX-R3-D / F-05] An empty payload entry is not "restore an empty file":
    // [memoryBackupEntries]'s reader folds "the file is not there / cannot be
    // read" into `""` (MemoryRepository.readFile never returns null), so a
    // device that never created GLOBAL.md exported `{"name":"GLOBAL.md",
    // "content":""}` — and restoring that backup onto a device WITH a GLOBAL.md
    // truncated it to zero bytes (newer-mtime ordering does not help: the
    // export stamp is newer than the local file).
    backupContentBlank: Boolean = false,
    /** Whether the local copy has anything in it. Unknown reads fail CLOSED
     *  ("assume it has content"): refusing to overwrite is always recoverable,
     *  a wiped GLOBAL.md is not.
     *
     *  [FIX-R5-B1 / F-06] The default is `true`, not `false`: it is the value a
     *  caller that says nothing gets, and `false` there would license the
     *  overwrite arm below for every future call site that forgot the
     *  argument. Only the one production caller ([restoreMemoryFiles]) passes
     *  it explicitly. */
    localHasContent: Boolean = true,
): MemoryRestoreDecision {
    if (!localExists) return MemoryRestoreDecision.Restore
    if (backupContentBlank && localHasContent) {
        return MemoryRestoreDecision.KeepLocal(
            "memory file \"$name\": the backup's copy is empty, local copy kept"
        )
    }
    // [FIX-R5-B1 / F-06] The mirror of the rule above, and the reason the two
    // halves have to be written together: an empty LOCAL copy holds nothing to
    // protect, so a backup that DOES carry content must be applied even when
    // its stamp is older than the (blank) local file's. Without this arm the
    // asymmetry was user-visible: a device whose GLOBAL.md had been created
    // empty (first launch writes the template, a failed write leaves "") kept
    // that empty file forever and every restore reported "local copy is newer
    // than the backup, kept" — the backup's real content was refused by a file
    // with nothing in it.
    //
    // Placed BEFORE the timestamp guards on purpose: "unreadable mtime" and
    // "no usable backup stamp" only exist to protect content, and there is no
    // content here. `localHasContent` is fail-closed on unreadable paths, so a
    // directory or an EACCES file never reaches this arm.
    if (!localHasContent && !backupContentBlank) return MemoryRestoreDecision.Restore
    if (localLastModified <= 0L) {
        return MemoryRestoreDecision.KeepLocal(
            "memory file \"$name\": local copy's write time is unreadable, kept"
        )
    }
    if (backupCreatedAt <= 0L) {
        return MemoryRestoreDecision.KeepLocal(
            "memory file \"$name\": backup carries no timestamp to order against, kept"
        )
    }
    // `<=` and not `<`: a file written in the same millisecond as the export is
    // the export's own read, not a later edit.
    if (localLastModified <= backupCreatedAt) return MemoryRestoreDecision.Restore
    return MemoryRestoreDecision.KeepLocal(
        "memory file \"$name\": local copy is newer than the backup, kept"
    )
}

/**
 * [FIX-R3-D / F-05] Does the local copy actually hold anything?
 *
 * Fail-closed in both directions of "cannot tell": an unreadable file, a
 * directory in its place, or a vanished path all answer `true` ("assume there
 * is content worth protecting"), because the only decision this feeds is
 * "may an EMPTY payload overwrite it?" — and answering `false` there would
 * mean "yes, wipe it".
 */
internal fun localHasContent(local: File?, localExists: Boolean): Boolean {
    if (!localExists || local == null) return false
    return try {
        // [FIX-R5-B1 / F-06] `isNotBlank()` and not `isNotEmpty()`: the payload
        // side measures "does the backup carry content" with `isBlank()` (see
        // [restoreMemoryFiles]' `content.isBlank()`), so a local copy holding
        // only a newline / spaces was content on one side of the comparison and
        // empty on the other. Whichever way that asymmetry fell, one of the two
        // rules fired against the user's interest — an all-whitespace daily log
        // counted as "has content" (so an older backup's real text was refused)
        // while the same file on the payload side counted as empty. One measure
        // for both sides now.
        local.readText().isNotBlank()
    } catch (_: Throwable) {
        true
    }
}

/**
 * [FIX-R5-B1 / F-01] The document-level timestamp this payload can order a
 * restore against, in epoch millis; `0` when it carries none.
 *
 * Why a fallback exists: two exporters write the field under different names.
 * RikkaMinis' own `export` writes `createdAt`; the OpenMinis `.minisbak`
 * converter only had `exportedAt` (its "document produced at" stamp), so
 * `import`'s single-key read returned `0` for EVERY converted package and
 * [decideMemoryRestore] then refused every memory file that already existed on
 * this device — including the `SOUL.md` / `GLOBAL.md` template a fresh install
 * writes at first launch. A `.minisbak` restore could therefore never bring
 * memory over, and the "no timestamp to order against, kept" line was the only
 * clue.
 *
 * `createdAt` stays primary: it is the snapshot moment both sides agree on. The
 * fallback is deliberately narrow (a positive value only) so a payload with
 * neither key still fails closed in [decideMemoryRestore].
 */
internal fun documentCreatedAt(root: org.json.JSONObject): Long {
    val primary = positiveEpochMillis(root.opt("createdAt"))
    if (primary > 0L) return primary
    return positiveEpochMillis(root.opt("exportedAt"))
}

/** Numeric epoch millis from a JSON value; `0` for anything unusable. */
private fun positiveEpochMillis(raw: Any?): Long {
    val v = when (raw) {
        null -> return 0L
        is Number -> raw.toLong()
        // Hand-written / third-party payloads carry the field as a string.
        is String -> raw.trim().toLongOrNull() ?: return 0L
        else -> return 0L
    }
    return if (v > 0L) v else 0L
}

/**
 * Stage 6 body: apply a `memoryFiles` JSON array, one file at a time.
 *
 * Lifted out of `ConfigBackup.import` unchanged except for the
 * [decideMemoryRestore] gate, and taking its two filesystem edges as lambdas
 * ([localFile] / [saveFile]) so the whole body — name validation, the guard,
 * the counters, the skipped lines — is exercised by [MemoryRestoreGuardTest] off
 * device. `MemoryRepository` cannot be compiled there (it imports
 * `android.util.Log` and the runtime-limits prefs), and re-implementing the
 * decision in a test would pin the copy, not the code.
 *
 * @return how many files were actually written, which is what
 *   `ImportResult.memoryFilesImported` has always meant. Files kept because the
 *   local copy is newer are NOT counted — the UI's "N memory files restored"
 *   line must not claim work that did not happen.
 */
internal fun restoreMemoryFiles(
    section: JSONArray,
    backupCreatedAt: Long,
    localFile: (String) -> File?,
    saveFile: (String, String) -> Unit,
    skipped: MutableList<String>,
): Int {
    var written = 0
    for (i in 0 until section.length()) {
        val m = section.optJSONObject(i) ?: continue
        val name = m.optString("name", "").trim()
        if (name.isEmpty()) {
            skipped.add("memory file #${i + 1}: missing name")
            continue
        }
        // Defence in depth: these names become file names under the memory
        // dir, so anything with a path separator is rejected outright rather
        // than trusted from the payload.
        if (name.contains('/') || name.contains('\\') || name.contains("..")) {
            skipped.add("memory file \"$name\": unsafe name, skipped")
            continue
        }
        val content = m.optString("content", "")
        // Stat failures are the "cannot tell" case, not an exception to bubble:
        // a resolved-but-vanished file must not turn a restore into a fatal.
        val local = runCatching { localFile(name) }.getOrNull()
        val localExists = local != null && runCatching { local.exists() }.getOrDefault(false)
        val localLastModified = if (localExists) {
            runCatching { local.lastModified() }.getOrDefault(0L)
        } else {
            0L
        }
        val decision = decideMemoryRestore(
            name = name,
            localExists = localExists,
            localLastModified = localLastModified,
            backupCreatedAt = backupCreatedAt,
            // [FIX-R3-D / F-05] "empty" and "could not be read" are the same
            // string on the wire (see decideMemoryRestore); the local side is
            // read here so an empty payload can never wipe real content.
            backupContentBlank = content.isBlank(),
            localHasContent = localHasContent(local, localExists),
        )
        if (decision is MemoryRestoreDecision.KeepLocal) {
            skipped.add(decision.reason)
            continue
        }
        try {
            saveFile(name, content)
            written++
        } catch (t: Throwable) {
            skipped.add("memory file \"$name\": ${t.message ?: "write failed"}")
        }
    }
    return written
}
