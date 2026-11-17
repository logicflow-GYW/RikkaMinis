package com.rikkaminis.app.providers

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes `<filesDir>/minis-global/{memory,skills,shared}` to the system
 * Files app as a storage root. Mirrors iOS `FileProviderExtension`
 * (spec_FileMount §1). Runs in the main app process — no IPC / no
 * independent extension target needed on Android.
 *
 * Capabilities match iOS:
 *   - `memory/` and `skills/` are read-only (tools own their content)
 *   - `shared/` accepts new subitems and in-place edits
 *   - root itself is read-only (can't create a new top-level folder)
 *
 * **Manifest registration is not yet wired.** AndroidManifest.xml has
 * pre-existing in-flight edits this commit can't touch — once that file
 * gets its own cleanup commit, add:
 * ```xml
 * <provider android:name=".providers.MinisDocumentsProvider"
 *     android:authorities="com.rikkaminis.documents"
 *     android:exported="true"
 *     android:grantUriPermissions="true"
 *     android:permission="android.permission.MANAGE_DOCUMENTS">
 *   <intent-filter>
 *     <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
 *   </intent-filter>
 * </provider>
 * ```
 * Until then this class compiles cleanly but is unreachable by the
 * Files app.
 */
class MinisDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.rikkaminis.documents"
        private const val ROOT_ID = "minis-root"
        private const val ROOT_DOC_ID = ""        // empty = providerRoot
        private val TOP_LEVEL = listOf("memory", "skills", "shared")
        private val READ_ONLY_TOP = setOf("memory", "skills")
        /** Never expose internal metadata that happens to sit in filesDir. */
        private val METADATA_BLACKLIST = setOf(
            "mounted-folders.json",
            "logs",
            ".",
            "..",
        )

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_MIME_TYPES,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )

        // [fix-R12 D7] Pure helpers for id normalization / read-only mapping /
        // canonical prefix guard — unit-testable without a Provider context.

        /** Normalized document id: trims whitespace and leading/trailing slashes. */
        internal fun normalizeDocId(documentId: String): String =
            documentId.trim().trim('/')

        /** Read-only top-level subtree for a *normalized* id, or null if writable/root. */
        internal fun readOnlyTopOf(clean: String): String? {
            val top = clean.substringBefore('/')
            return if (top in READ_ONLY_TOP) top else null
        }

        /**
         * Canonical prefix guard: target must equal root or live under
         * root + File.separator ("shared_evil" must not pass a "shared" check).
         */
        internal fun isWithinRoot(rootCanonical: String, targetCanonical: String): Boolean =
            targetCanonical == rootCanonical ||
                targetCanonical.startsWith(rootCanonical + File.separator)

        // [da1034-A2 ISSUE-02] The two id decisions that used to read the RAW
        // document id. Clients legitimately address the root as "", " ", "/"
        // or "//" (DocumentsContract does not normalize), so "isEmpty()" on the
        // raw string made "/" a *non-root* parent: the TOP_LEVEL whitelist below
        // was skipped and every internal filesDir entry (mcp-servers, logs, …)
        // leaked into the Files app listing. Deriving the answer from the one
        // normalizer keeps a single definition of "the root".

        /** True when [documentId] addresses the provider root, normalized. */
        internal fun isRootDocId(documentId: String): Boolean =
            normalizeDocId(documentId).isEmpty()

        /**
         * True when [documentId] names a top-level entry ITSELF ("shared"),
         * as opposed to something inside one ("shared/notes.txt").
         *
         * The provider's top level is a fixed, structural set: memory/ and
         * skills/ are read-only (see [readOnlyTopOf]) and shared/ may only
         * change *within*. A top-level entry therefore cannot be deleted or
         * renamed away — `deleteDocument("shared")` used to call
         * `deleteRecursively()` on the whole shared tree.
         */
        internal fun isTopLevelDocId(documentId: String): Boolean {
            val clean = normalizeDocId(documentId)
            return clean.isNotEmpty() && !clean.contains('/')
        }
    }

    override fun onCreate(): Boolean = true

    private fun providerRoot(): File {
        val ctx = context ?: throw IllegalStateException("Provider has no context")
        return File(ctx.filesDir, "minis-global").apply { mkdirs() }
    }

    private fun resolveDoc(documentId: String): File {
        val root = providerRoot()
        val clean = normalizeDocId(documentId)
        if (clean.isEmpty()) return root
        if (clean.contains("..")) throw FileNotFoundException("Invalid path: $documentId")
        // [dv20-A4-A07] The top-level whitelist used to guard the LISTING only:
        // `queryChildDocuments` skipped anything outside TOP_LEVEL when the
        // parent was root, but every other entry point resolved an arbitrary id
        // straight to a File — `mcp-servers/…`, `logs/…` and any other internal
        // filesDir entry were addressable by document id even though they never
        // appear in the Files app. Judging the id's first segment here (the one
        // funnel every entry point goes through) keeps a single definition of
        // what the provider exposes; it is latent today because the provider is
        // not registered in AndroidManifest.xml, and stays that way.
        val top = clean.substringBefore('/')
        if (top !in TOP_LEVEL) {
            throw FileNotFoundException("Not a top-level entry: $documentId")
        }
        // [fix-R12 D7] Canonicalize + prefix guard. Previously this refused only a
        // literal ".." ([audit-P3] left canonicalization as a pre-registration
        // obligation); now a symlink or alias resolving outside providerRoot is
        // rejected before any file access.
        val target = File(root, clean)
        if (!isWithinRoot(root.canonicalPath, target.canonicalPath)) {
            throw FileNotFoundException("Path escapes provider root: $documentId")
        }
        return target
    }

    private fun documentIdFor(file: File): String {
        val rootPath = providerRoot().absolutePath
        val abs = file.absolutePath
        if (abs == rootPath) return ROOT_DOC_ID
        if (!abs.startsWith("$rootPath/")) throw FileNotFoundException("Outside provider root: $abs")
        return abs.substring(rootPath.length + 1)
    }

    private fun flagsFor(documentId: String, file: File): Int {
        val clean = normalizeDocId(documentId)
        if (clean.isEmpty()) return 0 // root is immutable through SAF
        val readOnly = readOnlyTopOf(clean)
        if (readOnly != null) {
            val isTop = clean.indexOf('/') < 0
            return if (isTop && file.isDirectory) Document.FLAG_DIR_PREFERS_LAST_MODIFIED else 0
        }
        // `shared/` tree is writable.
        var flags = 0
        if (file.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        else flags = flags or Document.FLAG_SUPPORTS_WRITE
        // [da1034-A2 ISSUE-02] Advertise DELETE/RENAME only where those
        // operations are actually allowed — delete/renameDocument refuse
        // top-level entries themselves, so "shared" must not claim them.
        if (!isTopLevelDocId(documentId)) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        return flags
    }

    private fun mimeFor(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_TITLE, "Minis")
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
            .add(Root.COLUMN_ICON, 0)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        // [W1118-T6 D11 F-09] Answer with the NORMALIZED id, the same one
        // `queryChildDocuments` hands out. `DocumentsContract` does not
        // normalize what the client sends: a client that asks for
        // "/shared/a.txt" (or " shared/a.txt ") resolved fine — `resolveDoc` and
        // `flagsFor` both normalize — but got its own raw string back, so the
        // provider reported a document id that no listing of it ever produces
        // and the client's cache keys split. Latent today (this provider is not
        // registered in the manifest yet, see the class KDoc), which is exactly
        // why it is worth closing before registration rather than after.
        val cleanId = normalizeDocId(documentId)
        val file = resolveDoc(cleanId)
        val displayName = if (cleanId.isEmpty()) "Minis" else file.name
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, cleanId)
            .add(Document.COLUMN_DISPLAY_NAME, displayName)
            .add(Document.COLUMN_MIME_TYPE, mimeFor(file))
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flagsFor(cleanId, file))
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val parent = resolveDoc(parentDocumentId)
        // [da1034-A2 ISSUE-02] Root must be judged on the NORMALIZED id: the
        // raw isEmpty() let "/" or " " slip through as a non-root parent, the
        // TOP_LEVEL filter below was skipped, and every internal filesDir entry
        // (mcp-servers, logs, …) leaked into the Files app listing.
        val cleanParent = normalizeDocId(parentDocumentId)
        val isRoot = cleanParent.isEmpty()
        val children = parent.listFiles()?.toList() ?: emptyList()
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            if (name in METADATA_BLACKLIST) continue
            if (isRoot && name !in TOP_LEVEL) continue
            val docId = if (isRoot) name else "$cleanParent/$name"
            cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, docId)
                .add(Document.COLUMN_DISPLAY_NAME, name)
                .add(Document.COLUMN_MIME_TYPE, mimeFor(child))
                .add(Document.COLUMN_LAST_MODIFIED, child.lastModified())
                .add(Document.COLUMN_FLAGS, flagsFor(docId, child))
                .add(Document.COLUMN_SIZE, if (child.isFile) child.length() else 0L)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolveDoc(documentId)
        val parcelMode = ParcelFileDescriptor.parseMode(mode)
        // Reject writes against the root and the read-only subtrees even if the
        // client ignored the flags cursor — belt + suspenders for AI skills.
        // [fix-R12 D7] The old raw substringBefore('/') turned "/memory" into
        // top "" and slipped past this check; the normalized id closes it.
        if (parcelMode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0 ||
            parcelMode and ParcelFileDescriptor.MODE_READ_WRITE != 0
        ) {
            val clean = normalizeDocId(documentId)
            if (clean.isEmpty()) throw UnsupportedOperationException("Provider root is read-only")
            val top = readOnlyTopOf(clean)
            if (top != null) throw UnsupportedOperationException("$top is read-only")
        }
        return ParcelFileDescriptor.open(file, parcelMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = resolveDoc(parentDocumentId)
        val clean = normalizeDocId(parentDocumentId)
        // [fix-R12 D7] "/memory" normalized to "memory": the old raw
        // substringBefore('/') made its top "" and skipped the read-only check.
        if (clean.isEmpty()) throw UnsupportedOperationException("Provider root is read-only")
        val top = readOnlyTopOf(clean)
        if (top != null) throw UnsupportedOperationException("$top is read-only")
        val safeName = displayName.replace("/", "_").replace("..", "_")
        val created = File(parent, safeName)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) created.mkdirs() else created.createNewFile()
        if (!ok && !created.exists()) throw FileNotFoundException("Failed to create $safeName")
        return documentIdFor(created)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolveDoc(documentId)
        val clean = normalizeDocId(documentId)
        // [fix-R12 D7] "" used to yield top "" → fell through to
        // deleteRecursively() on the provider root itself; now explicitly refused.
        if (clean.isEmpty()) throw UnsupportedOperationException("Provider root cannot be deleted")
        val top = readOnlyTopOf(clean)
        if (top != null) throw UnsupportedOperationException("$top is read-only")
        // [da1034-A2 ISSUE-02] A top-level entry itself ("shared") may not be
        // deleted either — deleteDocument("shared") used to deleteRecursively()
        // the entire shared tree. Only its children are deletable; the top level
        // is the provider's fixed structure (memory/, skills/ read-only,
        // shared/ writable within).
        if (isTopLevelDocId(documentId)) {
            throw UnsupportedOperationException(
                "Top-level '$clean' cannot be deleted; delete its children individually",
            )
        }
        if (!file.deleteRecursively()) throw FileNotFoundException("Failed to delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolveDoc(documentId)
        val clean = normalizeDocId(documentId)
        // [fix-R12 D7] Same empty/leading-slash hole as deleteDocument.
        if (clean.isEmpty()) throw UnsupportedOperationException("Provider root cannot be renamed")
        val top = readOnlyTopOf(clean)
        if (top != null) throw UnsupportedOperationException("$top is read-only")
        // [da1034-A2 ISSUE-02] Renaming a top-level entry moves the whole tree
        // (deleteDocument's rename twin) — refused for the same structural
        // reason: "shared" is the mount's public name, not a movable folder.
        if (isTopLevelDocId(documentId)) {
            throw UnsupportedOperationException("Top-level '$clean' cannot be renamed")
        }
        val safe = displayName.replace("/", "_").replace("..", "_")
        val target = File(file.parentFile, safe)
        if (!file.renameTo(target)) throw FileNotFoundException("Failed to rename $documentId")
        return documentIdFor(target)
    }
}
