package com.rikkaminis.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Activity that handles ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW
 * intents. Mirrors iOS ShareExtension/ShareViewModel.swift wire format
 * (`{items: [{kind, value}], timestamp}`) so a future cross-platform
 * sync (if it ever lands) reads the same `PendingShare` JSON.
 *
 * Producer-side behavior:
 *   - inline text under [INLINE_TEXT_LIMIT] chars → Item(INLINE_TEXT, text)
 *   - longer text or any binary URI → Item(ATTACHMENT, "shared-…<ext>")
 *     with the bytes copied to filesDir/share_extension/<filename>
 *
 * Re-launches MainActivity with extra `shared_content=true` so
 * MainActivity.onCreate / onNewIntent can ask [ShareCoordinator] to
 * process the prefs entry into the in-memory buffer.
 *
 * ACTION_VIEW vs ACTION_SEND: VIEW carries its content in `intent.data`
 * (a Uri), not `EXTRA_STREAM`. Triggered by the system "Open with"
 * chooser (long-press image in gallery, file manager Open as…, Telegram
 * tap-to-open). The downstream PendingShare → MainActivity → composer
 * prefill path is identical, so handleView funnels into the same
 * `copyUriToStaging` helper SEND uses.
 */
class ShareReceiverActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareReceiver"
        private const val INLINE_TEXT_LIMIT = 1000
        private const val MAX_ATTACHMENTS = 50
        private const val MAX_FILE_BYTES = 100L * 1024 * 1024   // 100 MB per file
        private const val MAX_TOTAL_BYTES = 500L * 1024 * 1024  // 500 MB total
        private const val IO_TIMEOUT_MS = 30_000L               // 30 s per file

        /** [FIX-R3-E F-14] Saved-state slot for the staged items (see [extractedItems]). */
        private const val STATE_ITEMS = "share_receiver_items"

        /**
         * Process-lifetime cumulative budget of bytes staged for the pending
         * share, owned by the pure [StagedBytesLedger] (companion-scoped, not
         * an instance field) so it survives Activity re-creation (rotation /
         * low-memory death while the staged files themselves remain on disk).
         * Resets only when the whole process is reaped, matching the lifecycle
         * of [SharedShareStore]'s dirty staging files. Guards against a single
         * user flooding many share intents past [MAX_TOTAL_BYTES] across
         * Activity instances.
         */
        private val stagedBytes = StagedBytesLedger()

        /** Reset the cumulative staged-byte counter after the staged set is deleted. */
        fun resetStagedBytes() {
            stagedBytes.reset()
        }

        /**
         * [FIX-R3-E F-14] Absolute ledger reconcile: set the counter to the
         * summed size of the files actually left on disk after a selective
         * cleanup. [SharedShareStore.cleanSharedFiles] now KEEPS the files the
         * newest pending share still references, so a blind reset would
         * under-count the surviving bytes and the 500 MB budget would silently
         * loosen.
         */
        fun setStagedBytes(bytes: Long) {
            stagedBytes.set(bytes)
        }

        /**
         * Debit the cumulative counter when staged files are deleted outside
         * [SharedShareStore.cleanSharedFiles] (e.g. the provider-import path).
         * Clamped at 0 — over-counting would falsely trip [MAX_TOTAL_BYTES]
         * and reject legitimate shares while nothing is on disk.
         * [§1042-A-07] The clamp used to be applied to a DISCARDED copy
         * (`addAndGet(-bytes).coerceAtLeast(0L)` as a statement), so a double
         * debit drove the real counter negative and the cumulative check
         * under-counted. The ledger writes the clamped balance back.
         */
        fun debitStagedBytes(bytes: Long) {
            stagedBytes.debit(bytes)
        }
    }

    // T-n01-andmenu-l10n: pre-Tiramisu locale override (see MainActivity).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.rikkaminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    /**
     * [FIX-R3-E F-14] Items staged by THIS instance (or restored from a
     * previous one). Saved in [onSaveInstanceState] so a configuration-change
     * rebuild reuses the already-staged files instead of re-running the
     * extraction against the same intent — which used to write a SECOND copy
     * of every attachment (`shared-<newid>`), credit its bytes a second time,
     * and leave the first copy orphaned: no item ever referenced it, so no
     * cleanup path could reach it, and its bytes stayed in the process-lifetime
     * ledger until the 500 MB budget falsely tripped.
     */
    private var extractedItems: List<PendingShare.Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // [FIX-R3-E F-14] A rebuild arrives with a non-null savedInstanceState
        // and the SAME intent — re-extracting it would re-stage (and re-credit)
        // everything. Restore the item list instead; the staged files behind
        // the ATTACHMENT values are untouched by a rebuild, so the values stay
        // valid. Process death mid-dialog loses the dialog, not the staged
        // files: the restored flow re-offers them.
        val restoredItems = savedInstanceState?.getString(STATE_ITEMS)?.let { raw ->
            runCatching { PendingShare.fromJson(org.json.JSONObject(raw))?.items }.getOrNull()
        }.orEmpty()
        // Extract share items on the IO dispatcher so the main thread is
        // never blocked by potentially slow ContentResolver reads.
        lifecycleScope.launch(Dispatchers.IO) {
            val items = if (restoredItems.isNotEmpty()) restoredItems else extractItems()
            extractedItems = items
            // Provider-JSON detection also reads the staged file — keep on IO.
            val providerJson = items.singleOrNull()?.let { providerExportJsonOrNull(it) }
            withContext(Dispatchers.Main) {
                if (providerJson != null) {
                    promptProviderImportOrAttach(providerJson, items)
                } else {
                    finishWithAttachmentFlow(items)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (extractedItems.isNotEmpty()) {
            outState.putString(
                STATE_ITEMS,
                PendingShare(extractedItems, System.currentTimeMillis()).toJson().toString(),
            )
        }
    }

    /** Extract items from the incoming intent. Runs on IO. */
    private suspend fun extractItems(): List<PendingShare.Item> {
        val items = mutableListOf<PendingShare.Item>()
        try {
            when (intent?.action) {
                Intent.ACTION_SEND -> handleSingleSend(intent, items)
                Intent.ACTION_SEND_MULTIPLE -> handleMultipleSend(intent, items)
                Intent.ACTION_VIEW -> handleView(intent, items)
                else -> AppLogger.warning(TAG, "unhandled action: ${intent?.action}")
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "extraction failed: ${e.message}")
        }
        return items
    }

    /**
     * Persist the staged items as a [PendingShare] (so the chat composer picks
     * them up) and hand off to MainActivity. The default path for every share
     * that isn't a provider-import candidate.
     */
    private fun finishWithAttachmentFlow(items: List<PendingShare.Item>) {
        var saved = false
        try {
            if (items.isNotEmpty()) {
                SharedShareStore.savePendingShare(
                    this,
                    PendingShare(items, System.currentTimeMillis()),
                )
                saved = true
            } else {
                AppLogger.info(TAG, "no shareable items extracted")
            }
        } catch (e: Throwable) {
            AppLogger.error(TAG, "savePendingShare failed: ${e.message}")
        }
        // shared_content=true only when the pending share actually persisted —
        // otherwise MainActivity runs the ShareCoordinator lookup for nothing.
        launchMainActivity(withSharedContent = saved)
        finish()
    }

    /**
     * Read the staged file for [item] and return its content when the top-level
     * JSON carries the Provider-export shape (providerType + credentialType +
     * models[]) — the same required fields [ProviderRepository.importInstanceJSON]
     * needs. Returns null for non-JSON, unreadable, or non-provider payloads so
     * the caller falls through to the attachment flow.
     */
    private fun providerExportJsonOrNull(item: PendingShare.Item): String? {
        if (item.kind != PendingShare.Item.Kind.ATTACHMENT) return null
        val name = item.value
        if (!name.endsWith(".json", ignoreCase = true)) return null
        val file = File(SharedShareStore.sharedFileDirectory(this), name)
        // Guard against pathologically large "JSON" files — a provider export
        // is a few KB; anything past 1 MB is certainly something else.
        if (!file.isFile || file.length() > 1_000_000L) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "providerExportJsonOrNull read failed: ${e.message}")
            return null
        }
        val obj = try {
            org.json.JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val looksLikeProvider = obj.optString("providerType").isNotEmpty() &&
            obj.optString("credentialType").isNotEmpty() &&
            obj.optJSONArray("models") != null
        return if (looksLikeProvider) text else null
    }

    /**
     * Two-choice dialog: import the JSON as a provider, or add it to the chat as
     * an attachment. Dismiss / back == attachment (the pre-existing behaviour),
     * so the user can't lose the file by dismissing.
     */
    private fun promptProviderImportOrAttach(
        json: String,
        items: List<PendingShare.Item>,
    ) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(com.rikkaminis.app.R.string.share_provider_json_title))
            .setMessage(getString(com.rikkaminis.app.R.string.share_provider_json_message))
            .setPositiveButton(getString(com.rikkaminis.app.R.string.share_provider_json_import)) { _, _ ->
                // [deep-audit-1026 ISSUE-07] Both steps below are disk-bound:
                // importInstanceJSON runs ensureConfigLoaded (runBlocking) + an
                // EncryptedSharedPreferences commit + the Room write, and
                // discardStagedAttachments does length() + delete() per staged
                // file. Executing them straight in the positive-button callback
                // froze the main thread for the whole import — and this Activity
                // is the cold-start share entry point, so a multi-MB provider
                // JSON plus many staged attachments could stall the first frame
                // long enough for Input dispatching timeout (ANR).
                //
                // Mirrors the [T6-M4] shape in ProviderListScreen: do the I/O on
                // the IO dispatcher, then return to Main for the UI hand-off.
                // Toasts raised inside importProviderJson hop back via
                // runOnUiThread (Toast needs a Looper on its calling thread).
                lifecycleScope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        val ok = importProviderJson(json)
                        if (ok) {
                            // Imported: the staged JSON has served its purpose.
                            // Drop it (and debit the cumulative byte counter) so
                            // the file doesn't linger in share_extension/
                            // forever — cleanSharedFiles only runs on the
                            // pending-share consumption path.
                            discardStagedAttachments(items)
                        }
                        ok
                    }
                    if (imported) {
                        finish()
                    } else {
                        // Import failed — fall through to the attachment flow so
                        // the user doesn't lose the file (same promise as
                        // dismiss/back).
                        finishWithAttachmentFlow(items)
                    }
                }
            }
            .setNegativeButton(getString(com.rikkaminis.app.R.string.share_provider_json_attach)) { _, _ ->
                finishWithAttachmentFlow(items)
            }
            .setOnCancelListener {
                // Back / tap-outside falls through to the attachment flow so
                // the dropped file still lands somewhere useful.
                finishWithAttachmentFlow(items)
            }
            .show()
    }

    /** Run the existing provider-import logic and toast the outcome. Returns true on success. */
    private fun importProviderJson(json: String): Boolean {
        val repo = (applicationContext as? com.rikkaminis.app.MinisApp)?.providerRepository
        if (repo == null) {
            AppLogger.warning(TAG, "providerRepository unavailable; cannot import")
            toast(getString(com.rikkaminis.app.R.string.share_provider_json_import_failed))
            return false
        }
        val label = repo.importInstanceJSON(json)
        if (label != null) {
            AppLogger.info(TAG, "imported provider \"$label\" from shared JSON")
            toast(getString(com.rikkaminis.app.R.string.share_provider_json_imported, label))
            return true
        }
        AppLogger.warning(TAG, "importInstanceJSON returned null")
        toast(getString(com.rikkaminis.app.R.string.share_provider_json_import_failed))
        return false
    }

    /**
     * Delete the staged files backing [items] and debit the cumulative byte
     * counter. Used when the staged set has been consumed without going through
     * [SharedShareStore.cleanSharedFiles] (the provider-import path).
     */
    private fun discardStagedAttachments(items: List<PendingShare.Item>) {
        val dir = SharedShareStore.sharedFileDirectory(this)
        for (item in items) {
            if (item.kind != PendingShare.Item.Kind.ATTACHMENT) continue
            val f = File(dir, item.value)
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false) && len > 0) {
                debitStagedBytes(len)
            }
        }
    }

    /**
     * Show [msg] on the main thread. [deep-audit-1026 ISSUE-07]: the provider
     * import now runs on the IO dispatcher, and `Toast` needs a Looper on the
     * thread that shows it — calling it from a `Dispatchers.IO` worker throws
     * "Can't toast on a thread that has not called Looper.prepare()".
     * `runOnUiThread` posts when we are not already on Main and runs inline
     * when we are, so both the import path and any future Main-thread caller
     * behave identically.
     */
    private fun toast(msg: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSingleSend(intent: Intent, out: MutableList<PendingShare.Item>) {
        val type = intent.type ?: ""
        // [B28] Resolve the send route with the pure decision function:
        // file managers share .txt/.log files as text/plain via EXTRA_STREAM
        // (a content:// Uri) with EXTRA_TEXT absent — fall back to the stream
        // staging path instead of silently dropping.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val streamUri = getParcelableExtra<Uri>(intent, Intent.EXTRA_STREAM)
        when (val d = decideSingleSend(type, text, streamUri != null, INLINE_TEXT_LIMIT)) {
            is SingleSendDecision.InlineText ->
                out += PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, d.text)
            is SingleSendDecision.AttachText -> {
                // [§1042-A-07] Text attachments used to skip the staging
                // budget entirely: no MAX_FILE_BYTES / MAX_TOTAL_BYTES guard
                // and no credit, so a flood of huge EXTRA_TEXT shares grew the
                // staging dir past the 500 MB cap the binary path enforces.
                // Same two guards as copyUriToStaging, decided by the pure
                // [admitAttachText] core (JVM-pinned) and counted in UTF-8
                // bytes — the budget is bytes, not characters.
                val textBytes = attachTextByteCount(d.text)
                val admission = admitAttachText(
                    textBytes,
                    stagedBytes.current(),
                    MAX_FILE_BYTES,
                    MAX_TOTAL_BYTES,
                )
                when (admission) {
                    AttachTextAdmission.RejectedPerFile ->
                        AppLogger.warning(TAG, "AttachText not staged: $textBytes bytes exceeds per-file cap $MAX_FILE_BYTES")
                    AttachTextAdmission.RejectedCumulative ->
                        AppLogger.warning(TAG, "AttachText not staged: $textBytes bytes would exceed cumulative cap $MAX_TOTAL_BYTES")
                    AttachTextAdmission.Admitted -> {
                        val name = "shared-text-${shortId()}.txt"
                        val dest = File(SharedShareStore.sharedFileDirectory(this), name)
                        // [FIX-R3-E F-14] A write that throws mid-text used to
                        // leave the partial file on disk with NOTHING in the
                        // ledger and no item referencing it — an unaccounted
                        // orphan only a full clean would reach. Delete on
                        // failure, credit only what landed.
                        val staged = try {
                            dest.writeText(d.text, Charsets.UTF_8)
                            true
                        } catch (e: Throwable) {
                            runCatching { dest.delete() }
                            AppLogger.warning(TAG, "AttachText staging failed: ${e.message}")
                            false
                        }
                        if (staged) {
                            stagedBytes.credit(textBytes)
                            out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
                        }
                    }
                }
            }
            is SingleSendDecision.Stream ->
                streamUri?.let { uri ->
                    copyUriToStaging(uri, type)?.let {
                        out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, it)
                    }
                }
            SingleSendDecision.Drop -> Unit
        }
    }

    private fun handleMultipleSend(intent: Intent, out: MutableList<PendingShare.Item>) {
        val type = intent.type ?: ""
        val uris = getParcelableArrayListExtra<Uri>(intent, Intent.EXTRA_STREAM) ?: return
        for (uri in uris.take(MAX_ATTACHMENTS)) {
            if (out.size >= MAX_ATTACHMENTS) break
            copyUriToStaging(uri, type)?.let {
                out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, it)
            }
        }
    }

    /**
     * Handle the system "Open with" chooser path. The Uri lives in
     * `intent.data` (vs `EXTRA_STREAM` for SEND). `intent.type` is what the
     * caller declared, but Telegram and a few file managers leave it null,
     * so we fall back to ContentResolver's resolved type before staging.
     */
    private fun handleView(intent: Intent, out: MutableList<PendingShare.Item>) {
        val uri = intent.data
        if (uri == null) {
            AppLogger.warning(TAG, "ACTION_VIEW with no data uri")
            return
        }
        val type = intent.type ?: contentResolver.getType(uri) ?: ""
        AppLogger.info(TAG, "ACTION_VIEW uri=$uri type=$type")
        copyUriToStaging(uri, type)?.let {
            out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, it)
        }
    }

    /** Copy a ContentResolver-backed URI to the staging dir. Runs on the
     *  IO dispatcher (the caller is [extractItems]). Returns the staged
     *  filename (relative to sharedFileDirectory) or null on failure.
     *  Enforces per-file and cumulative size limits and cleans up partial
     *  files on any failure.
     *
     *  [§1044-A08] The byte-copying itself lives in the JVM-pinned
     *  [copySharedStream] core, which null-guards the resolver stream BEFORE
     *  the destination file is created. `ContentResolver.openInputStream`
     *  returns null (without throwing) when a remote provider refuses or its
     *  process died; the old inline shape `openInputStream(uri)?.use { … }`
     *  then skipped its whole block and still returned `name`, so the caller
     *  built a `PendingShare.Item` for a file that never existed — a ghost
     *  attachment that reached the composer with no "could not read" error.
     *  Nothing is staged unless the core reports [SharedStreamCopy.Staged]. */
    private fun copyUriToStaging(uri: Uri, mimeType: String): String? {
        val ext = guessExtension(mimeType, uri)
        val prefix = when {
            mimeType.startsWith("image/") -> "shared-image"
            mimeType.startsWith("video/") -> "shared-video"
            else -> "shared"
        }
        val name = "$prefix-${shortId()}${if (ext.isNotEmpty()) ".$ext" else ""}"
        val dest = File(SharedShareStore.sharedFileDirectory(this), name)
        return when (
            val outcome = copySharedStream(
                openStream = { contentResolver.openInputStream(uri) },
                openDest = { dest.outputStream() },
                deleteDest = { dest.delete() },
                stagedBytesNow = { stagedBytes.current() },
                credit = { stagedBytes.credit(it) },
                maxFileBytes = MAX_FILE_BYTES,
                maxTotalBytes = MAX_TOTAL_BYTES,
                ioTimeoutMs = IO_TIMEOUT_MS,
                now = { System.currentTimeMillis() },
            )
        ) {
            is SharedStreamCopy.Staged -> name
            is SharedStreamCopy.Failed -> {
                AppLogger.warning(TAG, "copyUriToStaging($uri): ${outcome.describe()}")
                null
            }
        }
    }

    /** Human-readable reason for a failed staging copy. Keeps the log lines the
     *  pre-§1044-A08 inline copy emitted (same wording, same numbers). */
    private fun SharedStreamCopy.Failed.describe(): String = when (reason) {
        SharedStreamFailure.OpenStreamNull -> "openInputStream returned null — nothing to stage"
        SharedStreamFailure.TimedOut -> "timed out after ${IO_TIMEOUT_MS}ms"
        SharedStreamFailure.ExceededPerFile -> "exceeded $MAX_FILE_BYTES bytes"
        SharedStreamFailure.ExceededCumulative -> "would exceed cumulative $MAX_TOTAL_BYTES bytes"
        SharedStreamFailure.ReadFailed -> detail ?: "read failed"
    }

    private fun guessExtension(mimeType: String, uri: Uri): String {
        val mm = android.webkit.MimeTypeMap.getSingleton()
        val fromMime = mm.getExtensionFromMimeType(mimeType.substringBefore(';'))
        if (!fromMime.isNullOrBlank()) return fromMime
        return uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 1..6 } ?: ""
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    private fun launchMainActivity(withSharedContent: Boolean) {
        val mainIntent = Intent(this, Class.forName("com.rikkaminis.app.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (withSharedContent) putExtra("shared_content", true)
        }
        startActivity(mainIntent)
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableExtra(
        intent: Intent, key: String,
    ): T? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableExtra(key, T::class.java)
    else intent.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableArrayListExtra(
        intent: Intent, key: String,
    ): ArrayList<T>? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableArrayListExtra(key, T::class.java)
    else intent.getParcelableArrayListExtra(key)
}

/** [B28] Where a single ACTION_SEND goes. */
internal sealed class SingleSendDecision {
    /** Short inline text — prefill the composer directly. */
    data class InlineText(val text: String) : SingleSendDecision()
    /** Long text — stage it as a .txt attachment. */
    data class AttachText(val text: String) : SingleSendDecision()
    /** No usable text — stage the content:// stream (EXTRA_STREAM) instead. */
    object Stream : SingleSendDecision()
    /** Neither text nor stream — nothing shareable arrived. */
    object Drop : SingleSendDecision()
}

/**
 * [B28] Pure dispatch core of [ShareReceiverActivity] single-send handling,
 * extracted for JVM testing. A text/plain share that carries no usable
 * EXTRA_TEXT (file managers share .txt/.log bytes via EXTRA_STREAM with a
 * null EXTRA_TEXT) falls back to the stream path instead of being silently
 * dropped; the inline-vs-attach split by [inlineTextLimit] and the
 * no-EXTRA_STREAM drop are unchanged.
 */
internal fun decideSingleSend(
    type: String,
    text: String?,
    hasStream: Boolean,
    inlineTextLimit: Int,
): SingleSendDecision = when {
    type == "text/plain" && text != null && text.isNotBlank() ->
        if (text.length <= inlineTextLimit) SingleSendDecision.InlineText(text)
        else SingleSendDecision.AttachText(text)
    hasStream -> SingleSendDecision.Stream
    else -> SingleSendDecision.Drop
}
