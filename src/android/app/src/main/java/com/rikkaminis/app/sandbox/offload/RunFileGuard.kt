package com.rikkaminis.app.sandbox.offload

import java.io.File

/**
 * Filesystem guard that restricts file access to a single run directory.
 *
 * The model-exec protocol only ever reads/writes files under
 * `[cacheDir]/model-exec/run-<uuid>/`. Any path handed across the JSON
 * boundary (BytesFileRef.relativePath, legacy imageLinuxPath, etc.) must be
 * canonicalized and proven to stay under that run root before the process
 * touches it — otherwise a crafted relative-path (`../../..` or symlink)
 * could escape the sandbox. This is the canonicalize + root-prefix guard
 * mandated by the task.
 *
 * Pure JVM (java.io) — no Android dependency.
 */
class RunFileGuard(
    /** The canonicalized run directory root. Access outside this is refused. */
    private val root: File,
) {
    /** Canonical root, computed lazily once. */
    val rootCanonical: String by lazy {
        runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
    }

    /**
     * Resolve a *relative* path against the run root and return the
     * canonical absolute path IF (and only if) it falls under the root.
     * Returns null on any escape attempt or unresolved path.
     *
     * @param relativePath path relative to the run dir; may contain `..`.
     *        Must NOT be absolute and MUST NOT be empty.
     */
    fun resolveUnderRoot(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        // Reject absolute paths outright (they can never be "under root").
        if (relativePath.startsWith('/') || relativePath.startsWith("\\")) return null
        // Guard against drive-letter colon on the first component (Windows-style).
        val firstComponent = relativePath.split('/', '\\').firstOrNull().orEmpty()
        if (firstComponent.length == 2 && firstComponent[1] == ':') return null

        val candidate = File(root, relativePath)
        val canonical = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        if (!isPathUnderRoot(canonical)) return null
        return File(canonical)
    }

    /**
     * The same checks but for an already-absolute canonical path — used at
     * read time after [resolveUnderRoot] when the consumer wants a second
     * confirmation, or to validate a path that was stored absolute.
     */
    fun assertUnderRoot(canonical: File): Boolean =
        isPathUnderRoot(canonical.canonicalPath)

    /** Pure predicate: does [candidate] canonical path live under [rootCanonical]?
     *  Uses a prefix boundary check (trailing slash) so a sibling dir like
     *  `root-evil` is NOT counted as under `root`. */
    internal fun isPathUnderRoot(candidateCanonical: String): Boolean {
        val rootPath = rootCanonical
        if (rootPath.isEmpty()) return false
        // root itself is allowed (rare), and anything with the root as a
        // path-component boundary is allowed.
        return candidateCanonical == rootPath ||
            candidateCanonical.startsWith(rootPath + File.separator)
    }

    companion object {
        /**
         * Standalone pure predicate — no guard instantiation needed. 
         * Returns true when [candidateCanonical] is under [rootCanonical]
         * with a proper path-component boundary.
         */
        fun isPathUnderRoot(rootCanonical: String, candidateCanonical: String): Boolean {
            if (rootCanonical.isEmpty()) return false
            return candidateCanonical == rootCanonical ||
                candidateCanonical.startsWith(rootCanonical + File.separator)
        }
    }
}