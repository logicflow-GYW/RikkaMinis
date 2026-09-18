package com.rikkaminis.app.logging

/**
 * [T-logging-full-coverage] Bounded in-memory ring of the most recent log
 * lines, across ALL levels — including the high-volume DEBUG channel.
 *
 * Purpose: post-hoc diagnosis of failures. The daily file can rotate and the
 * debug file can roll over, so "what did the app log right before the 400?"
 * was answerable only by watching live (the 64KiB kernel logcat ring holds a
 * few minutes of streaming traffic). This buffer holds the last [capacity]
 * delivered lines and [snapshot] turns it into a file the moment an ERROR
 * arrives — every error carries its own scene.
 *
 * Cost: pure memory (capacity × line length) plus one file write per error
 * episode; zero hot-path disk pressure.
 *
 * Pure JVM — unit-tested in LogRingBufferTest.
 */
internal class LogRingBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 500
        /**
         * Minimum interval between two snapshot files. An error loop (retry
         * storm, failover fan-out) would otherwise create a file per error —
         * one snapshot per episode window instead.
         */
        const val SNAPSHOT_MIN_INTERVAL_MS = 30_000L
    }

    private val lines = ArrayDeque<String>(capacity)
    // Initialize so the FIRST shouldSnapshot() call passes: now - last must
    // exceed SNAPSHOT_MIN_INTERVAL_MS. Long.MIN_VALUE would overflow
    // `now - last` into a negative and silence the first snapshot.
    private var lastSnapshotAt: Long = -SNAPSHOT_MIN_INTERVAL_MS - 1

    /** Append one delivered line; drops the oldest when full. */
    @Synchronized
    fun append(line: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
    }

    /** Current snapshot content, oldest first. */
    @Synchronized
    fun content(): List<String> = lines.toList()

    /**
     * Whether an error-triggered snapshot should be written now. Dedup by
     * [SNAPSHOT_MIN_INTERVAL_MS]; the caller MUST call [markSnapshot] when it
     * actually writes the file (after a true return) — a false return must
     * not move the window or a burst of errors would silence all later ones.
     */
    @Synchronized
    fun shouldSnapshot(nowMillis: Long): Boolean {
        if (nowMillis - lastSnapshotAt < SNAPSHOT_MIN_INTERVAL_MS) return false
        lastSnapshotAt = nowMillis
        return true
    }

    @Synchronized
    fun clear() {
        lines.clear()
        lastSnapshotAt = -SNAPSHOT_MIN_INTERVAL_MS - 1
    }
}
