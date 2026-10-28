package com.rikkaminis.app.data

import java.io.File

/**
 * [E2-F11] Rollback for a failed `duplicateSession`.
 *
 * `duplicateSession` writes in four phases, each with a different durability
 * story, and only one of them was inside a try/catch:
 *
 *   | phase          | storage                                    | cleanup before this change |
 *   |----------------|--------------------------------------------|----------------------------|
 *   | session        | `sessions` row + 4 metadata updates        | only when the throw came from the message loop |
 *   | messages       | `messages` rows                            | FK cascade — but only reached from that one catch |
 *   | compactMarkers | `compact_markers` rows                     | none (phase ran below the try) |
 *   | artifacts      | `minis-sessions/<new>/…` + `media/<yyyy>/<MM>/<dd>/<new>`| **none — not even a cascade candidate** |
 *
 * Phase 1 sat ABOVE the try and survived only because all four of its writes are
 * columns of the row the catch deletes — an accident of the storage layout, not
 * something the code said. Phase 3 sat BELOW the try with no cleanup of its own:
 * a constraint or disk-full failure while inserting markers left the row, every
 * copied message and half the markers behind, and nothing ever removes those.
 * Phase 4 is best-effort by contract — `copySessionArtifacts` collects per-entry
 * failures and logs PARTIAL instead of aborting the clone — but it is the only
 * phase that writes FILES, and no CASCADE covers `minis-sessions/<new>/…` or
 * `media/<yyyy>/<MM>/<dd>/<new>`. Once anything above it throws, or the coroutine is cancelled
 * while it runs, that tree is orphaned with no owner at all.
 *
 * `duplicateSession` now wraps all four phases and unwinds through [unwind] on
 * any throw. This object holds the plan itself so it is stated once, readable as
 * data, and JVM-testable: everything here is `java.io` + trace strings, with no
 * Room and no Android classes.
 */
object SessionForkRollback {

    /** What one phase's undo does, and why. */
    data class Step(
        /** Phase that wrote something, in WRITE order. */
        val target: String,
        /** How it is undone: `wipe` (files), `cascade-with-session` (FK), `delete` (row). */
        val verb: String,
        /** Stated reason — read by the probe so the plan can never go silent. */
        val why: String,
    )

    /**
     * Reverse-of-write order. Files come FIRST because nothing else removes
     * them; the session row comes LAST because every other step names something
     * the row owns, and deleting it first would leave no pointer to the trees.
     */
    fun plan(): List<Step> = listOf(
        Step(
            target = "artifacts",
            verb = "wipe",
            why = "no CASCADE touches files: minis-sessions/<new>/** and media/<yyyy>/<MM>/<dd>/<new> must be " +
                "deleted explicitly. The phase is best-effort by contract, so it logs PARTIAL " +
                "instead of throwing — but once anything above it fails, or the coroutine is " +
                "cancelled while it runs, the tree is orphaned below the old try with no owner.",
        ),
        Step(
            target = "compactMarkers",
            verb = "cascade-with-session",
            why = "rows inserted below the old try with no cleanup of their own: a constraint or " +
                "disk-full failure there left a half-built copy nobody removed. Now covered by the " +
                "same unwind, via the FK cascade.",
        ),
        Step(
            target = "messages",
            verb = "cascade-with-session",
            why = "FK → CASCADE on sessions.id; removed when the row goes. This was the ONLY phase " +
                "the old try covered.",
        ),
        Step(
            target = "session",
            verb = "delete",
            why = "last: every earlier step names something the row owns, and deleting it first " +
                "would leave no pointer to the file trees.",
        ),
    )

    /**
     * Undo a half-built copy. Returns one `"verb:target"` line per step, in plan
     * order, so a probe can assert the SEQUENCE (the part the old shape lacked)
     * as well as the outcome.
     *
     * Only phase 4 does work here. The three row phases need Room and are
     * performed by the caller's single `deleteSession` call, which already
     * cascades messages and markers — they are traced for order and nothing else.
     *
     * Idempotent by construction: every step targets something that may already
     * be gone, which is the normal case when the failure happened before that
     * phase ran.
     */
    fun unwind(newSessionId: String, sessionsRoot: File, mediaRoot: File): List<String> {
        val trace = mutableListOf<String>()
        for (step in plan()) {
            if (step.verb == "wipe") {
                wipeArtifacts(newSessionId, sessionsRoot, mediaRoot)
            }
            trace.add("${step.verb}:${step.target}")
        }
        return trace
    }

    /**
     * Remove the copy's file trees. Uses the same recursive leaf-match
     * `SessionFileStore.mediaSessionDirs` applies (only the leaf of
     * `<yyyy>/<MM>/<dd>/<sid>` carries the session id), so the two cannot
     * disagree again — same reasoning as the single-sourced `resolveVolumeRoot`.
     */
    private fun wipeArtifacts(newSessionId: String, sessionsRoot: File, mediaRoot: File) {
        File(sessionsRoot, newSessionId).takeIf { it.exists() }?.let {
            removeRecursive(it)
        }
        for (dir in mediaSessionDirs(mediaRoot, newSessionId)) {
            removeRecursive(dir)
        }
    }

    /**
     * Every dir under [mediaRoot] whose NAME is [sessionId], at any depth — the
     * MediaStore dated-path layout puts the session id in the leaf only.
     */
    fun mediaSessionDirs(mediaRoot: File, sessionId: String): List<File> {
        if (!mediaRoot.exists()) return emptyList()
        val out = mutableListOf<File>()
        fun walk(dir: File) {
            for (child in dir.listFiles() ?: return) {
                if (!child.isDirectory) continue
                if (child.name == sessionId) out += child else walk(child)
            }
        }
        walk(mediaRoot)
        return out
    }

    /**
     * `File.deleteRecursively()` returns false and says nothing when a file is
     * held open or a path is unroutable — the "fake close" trap. Verify against
     * the filesystem afterwards instead of trusting the boolean.
     */
    private fun removeRecursive(dir: File) {
        dir.deleteRecursively()
        if (dir.exists()) {
            throw IllegalStateException("fork rollback: could not remove $dir")
        }
    }
}
