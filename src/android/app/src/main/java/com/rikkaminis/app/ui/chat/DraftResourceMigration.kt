package com.rikkaminis.app.ui.chat

import java.io.File
import java.io.InputStream

/**
 * [meta-audit-1028 C1 / F-03] The file-system half of "promote a draft onto its
 * real session id", extracted from `ChatViewModel.migrateDraftResources` so the
 * JVM can drive it against real directories instead of a mirror.
 *
 * Why it exists: the promotion hops `minis-sessions/__new__<uuid>/{attachments,
 * offloads,workspace,browser}` onto `minis-sessions/<realId>/…` the moment the
 * user sends the first message. The pre-fix loop read
 *
 * ```
 * if (!target.exists() && !child.renameTo(target)) copyRecursive(child, target)
 * ```
 *
 * i.e. "rename, and if that fails copy". Two things were wrong with it and both
 * end the same way — the user's bytes stay behind in a directory nothing ever
 * reads again (`WebAppPathResolver` / the shell / the file browser all resolve
 * through the REAL id once the hop ran):
 *
 *  - `!target.exists()` guards the WHOLE statement, so a name that already
 *    exists in the real session's subdirectory was neither renamed nor copied —
 *    the one case the copy branch was written for (a merge) was the one case it
 *    could not reach;
 *  - `copyRecursive` folded `listFiles() == null` (the directory could not be
 *    read — permissions, I/O error) into `true` via `?: true`, so a subtree that
 *    copied NOTHING reported success, and its caller did not check the result
 *    anyway.
 *
 * The contract here is deliberately narrow and idempotent, because promotion can
 * run more than once for the same pair of directories (a failed run keeps the
 * draft tree on purpose — see the `leftover` check in the caller):
 *
 *  - a rename when the destination name is free (one syscall, whole subtree);
 *  - otherwise a MERGE: directories recurse, a file is copied only when the
 *    destination has nothing under that name, and a name that exists on both
 *    sides is dropped from the draft ONLY when the bytes are identical (a
 *    re-run after a partially-deleted draft), never overwritten otherwise;
 *  - `false` means "something is still in the source", and the caller keeps the
 *    draft directory instead of deleting bytes it could not move.
 */
internal fun migrateDraftEntry(src: File, dst: File): Boolean {
    // Fast path: the destination name is free and rename moves the whole subtree
    // atomically-ish (same filesystem — every session directory lives under
    // filesDir). A `false` here is not an error: the draft may be on a different
    // mount, or the destination may have been created between the check and the
    // call. Both fall through to the merge, which handles them uniformly.
    if (!dst.exists() && src.renameTo(dst)) return true
    return mergeInto(src, dst)
}

/** Merge [src] into [dst] without ever overwriting what [dst] already holds. */
internal fun mergeInto(src: File, dst: File): Boolean =
    if (src.isDirectory) mergeDirectory(src, dst) else mergeFile(src, dst)

/**
 * Directory merge: every child goes through [migrateDraftEntry] (so the nested
 * rename fast path still applies), and the source directory is removed once it
 * is empty. Pre-fix, the directory branch of `copyRecursive` never deleted the
 * source at all — an all-merged subtree left `__new__*` behind for good.
 */
private fun mergeDirectory(src: File, dst: File): Boolean {
    if (!src.isDirectory) return false
    if (dst.exists() && !dst.isDirectory) return false
    dst.mkdirs()
    if (!dst.isDirectory) return false
    // `listFiles()` returning null is an I/O failure, not "an empty directory":
    // reporting it as success is what let a subtree vanish silently (F-03 ②).
    val children = src.listFiles() ?: return false
    var moved = true
    for (child in children) {
        if (!migrateDraftEntry(child, File(dst, child.name))) moved = false
    }
    if (!moved) return false
    return src.delete() || !src.exists()
}

/**
 * File merge. A name that already exists on the real session belongs to the LIVE
 * session and wins: it may have been written after the draft copy (the browser
 * downloads into the current session directory), and silently replacing it would
 * turn "the draft's file is stale" into "the live file is gone".
 *
 * The one exception is an identical copy, which is exactly the shape a previous,
 * partially-failed migration leaves behind (copied, then the source delete
 * failed). Keeping it would make the draft directory immortal, so identical
 * bytes are dropped from the draft side.
 */
private fun mergeFile(src: File, dst: File): Boolean {
    if (!dst.exists()) return copyFile(src, dst)
    if (dst.isDirectory) return false
    if (src.length() != dst.length()) return false
    if (!sameContent(src, dst)) return false
    return src.delete() || !src.exists()
}

/**
 * Copy through a sibling temp name and rename onto the final one, so an aborted
 * copy can never leave a truncated file under the destination's real name — the
 * promotion's failure path keeps the draft tree precisely so nothing is lost,
 * and a half file in the live session would defeat that.
 */
private fun copyFile(src: File, dst: File): Boolean {
    val parent = dst.parentFile ?: return false
    if (!parent.isDirectory && !parent.mkdirs()) return false
    val tmp = File(parent, dst.name + MIGRATION_TEMP_SUFFIX)
    return try {
        if (tmp.exists()) tmp.delete()
        src.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(dst)) return false
        src.delete() || !src.exists()
    } catch (_: Throwable) {
        false
    } finally {
        if (tmp.exists()) {
            try {
                tmp.delete()
            } catch (_: Throwable) {
            }
        }
    }
}

/** Byte-for-byte comparison, buffered; both streams are opened read-only. */
private fun sameContent(a: File, b: File): Boolean {
    val bufA = ByteArray(SAME_CONTENT_BUFFER)
    val bufB = ByteArray(SAME_CONTENT_BUFFER)
    return try {
        a.inputStream().use { ia ->
            b.inputStream().use { ib ->
                var same = true
                var done = false
                while (!done) {
                    val na = ia.readUpTo(bufA)
                    val nb = ib.readUpTo(bufB)
                    when {
                        na != nb -> {
                            same = false
                            done = true
                        }
                        na == 0 -> done = true
                        !regionEquals(bufA, bufB, na) -> {
                            same = false
                            done = true
                        }
                    }
                }
                same
            }
        }
    } catch (_: Throwable) {
        false
    }
}

/**
 * Read until the buffer is full or EOF, so two identical files always produce
 * the same chunk sizes (a bare `read` may legally return a short chunk for one
 * side and a full one for the other).
 */
private fun InputStream.readUpTo(buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) break
        off += n
    }
    return off
}

private fun regionEquals(a: ByteArray, b: ByteArray, len: Int): Boolean {
    for (i in 0 until len) {
        if (a[i] != b[i]) return false
    }
    return true
}

private const val SAME_CONTENT_BUFFER = 64 * 1024

/** Suffix of the in-flight copy in [copyFile]; never a file the app reads. */
internal const val MIGRATION_TEMP_SUFFIX = ".migrating"
