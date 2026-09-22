package com.rikkaminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.rikkaminis.app.BuildConfig
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub releases for an APK newer than [BuildConfig.VERSION_NAME] and
 * coordinates download → install. iOS has no equivalent (sideloading is not
 * permitted) so this is Android-only.
 *
 * Comparison strategy: strip a leading `v` from `tag_name`, then split both
 * the tag and the local versionName on `.` and compare numerically component
 * by component. A tag like `v1.0.1` beats local `1.0.0`; `v1.0.0-rc1` beats
 * `1.0.0` because the suffix sorts higher under string fallback.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val OWNER = "logicflow-GYW"
    // RikkaMinis: this fork's own android-latest release is published on
    // logicflow-GYW/RikkaMinis by the build-apk.yml workflow, so the in-app
    // update check points at our own repo (RikkaMinis-*.apk asset), not the
    // upstream OpenMinis/OpenMinis.
    private const val REPO = "RikkaMinis"
    private const val DOWNLOAD_FILENAME = "minis-update.apk"
    /**
     * Sub-directory of `filesDir` where we stage downloaded update APKs. We
     * moved off `cacheDir/shared/` (the original location) so the OS can't
     * evict a freshly-downloaded APK between the moment we hand the user off
     * to "install unknown apps" settings and the moment they return — the
     * eviction was a contributing factor to the "re-download after grant"
     * bug. See [PendingUpdateStore]. Exposed via `file_provider_paths.xml`
     * `<files-path name="updates" path="updates/" />`.
     */
    private const val UPDATES_DIR = "updates"

    sealed class CheckResult {
        data class UpdateAvailable(
            val tagName: String,
            val versionName: String,
            val releaseName: String,
            val changelog: String,
            val apkUrl: String,
            val apkSizeBytes: Long,
            /**
             * Digest the release publisher declared for this asset, or null
             * when the release carries none (older releases). Callers must
             * pass it to [download] so the bytes are checked end-to-end, and
             * surface [publisherDigestAvailable] so an unverified update is
             * never presented as a verified one.
             */
            val publisherDigest: PublisherDigest? = null,
        ) : CheckResult() {
            /** False = the update can only be size-checked; say so in the UI. */
            val publisherDigestAvailable: Boolean get() = publisherDigest != null
        }
        data object UpToDate : CheckResult()
        // The repo has zero non-draft releases (or 404'd entirely).
        data object NoReleaseAvailable : CheckResult()
        // A newer release exists but no .apk asset was attached. Distinct
        // from NoReleaseAvailable so the UI can say "newer release exists,
        // but it didn't ship an APK" instead of misleading "no release yet".
        data class NoApkAsset(val tagName: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
        // GitHub returned 403 / 451 — usually a geo-block or rate-limit in CN
        // without a VPN. UI surfaces a hint with a clickable Releases link.
        data object Forbidden : CheckResult()
        // DNS / connect / read timeout — network unreachable. UI nudges the
        // user to check connectivity and retry.
        data object NetworkUnreachable : CheckResult()
    }

    sealed class DownloadResult {
        /**
         * @param integrity how much of the download was actually verified —
         *   see [DownloadIntegrity]; only [DownloadIntegrity.isVerified] means
         *   the publisher's declared hash vouched for the bytes.
         * @param resumed true when the file came from a previously-persisted
         *   pending record ([fix/update-pending-resume]) instead of the
         *   network. Purely informational — callers treat resumed and
         *   freshly-downloaded successes identically.
         */
        data class Success(
            val file: File,
            val integrity: DownloadIntegrity = DownloadIntegrity.SIZE_ONLY,
            val resumed: Boolean = false,
        ) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
        /**
         * The bytes did not match what the release declared. The file has
         * already been deleted and any pending record cleared; the caller
         * shows [reason] verbatim. Kept separate from [Error] so a corrupt
         * download is never mistaken for a transient network failure (the
         * user must not be told to "retry" a file we refused to install).
         */
        data class IntegrityFailure(
            val verdict: DownloadIntegrity,
            val expected: String?,
            val actual: String?,
            val reason: String,
        ) : DownloadResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Hit `repos/{owner}/{repo}/releases` (the list endpoint, NOT
     * `/releases/latest`), pick the highest-version non-draft release that
     * carries an APK asset, and decide whether the user should upgrade.
     *
     * T133: switched from `/releases/latest` to `/releases` because
     * `/releases/latest` excludes prereleases by GitHub design — our
     * `0.1 preview` release is flagged as a prerelease, so the old endpoint
     * 404'd and the UI falsely showed "No release published yet". The list
     * endpoint includes prereleases; we filter drafts client-side.
     *
     * All network work happens on [Dispatchers.IO]; safe to call from any
     * coroutine scope.
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
        AppLogger.info(TAG, "GET $url (local=${BuildConfig.VERSION_NAME})")
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(req).execute().use { resp ->
                AppLogger.info(TAG, "HTTP ${resp.code}")
                if (resp.code == 404) {
                    return@withContext CheckResult.NoReleaseAvailable
                }
                // 403 = rate-limit or geo-blocked. 451 = legal block. Both
                // map to the same "open Releases in browser" hint — there's
                // nothing the app can do client-side.
                if (resp.code == 403 || resp.code == 451) {
                    AppLogger.warning(TAG, "GitHub API ${resp.code} — geo-block or rate-limit")
                    return@withContext CheckResult.Forbidden
                }
                if (!resp.isSuccessful) {
                    val msg = "GitHub API ${resp.code}"
                    AppLogger.warning(TAG, msg)
                    return@withContext CheckResult.Error(msg)
                }
                val body = resp.body?.string() ?: return@withContext CheckResult.Error("empty body")
                val arr = runCatching { JSONArray(body) }.getOrNull()
                if (arr == null || arr.length() == 0) {
                    AppLogger.info(TAG, "releases list empty")
                    return@withContext CheckResult.NoReleaseAvailable
                }

                // Build a list of non-draft releases. GitHub already returns
                // them sorted by created_at desc, but we re-sort by parsed
                // version number to be robust against odd ordering.
                data class ReleaseInfo(
                    val tagName: String,
                    val versionName: String,
                    val releaseName: String,
                    val changelog: String,
                    val isPrerelease: Boolean,
                    val apkUrl: String?,
                    val apkSize: Long,
                    val apkDigest: PublisherDigest?,
                )

                val candidates = mutableListOf<ReleaseInfo>()
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    if (r.optBoolean("draft", false)) continue
                    val tag = r.optString("tag_name")
                    if (tag.isEmpty()) continue
                    val parsedVersion = normalizeTag(tag)
                    // [fix/updatechecker-semver-prerelease] Only version-shaped
                    // tags may enter the comparison. The rolling download tag
                    // "android-latest" normalizes to "android", which used to
                    // compare GREATER than any "1.x.y" local version (letter >
                    // digit) and therefore reported a permanent update. A tag
                    // whose normalized form does not start with a digit is not
                    // a version and is skipped — the rolling release stays a
                    // download entry point, it just no longer votes on
                    // precedence.
                    if (parsedVersion.firstOrNull()?.isDigit() != true) {
                        AppLogger.info(TAG, "skipping non-version tag=$tag parsed=$parsedVersion")
                        continue
                    }
                    val apkAsset = findApkAsset(r.optJSONArray("assets"))
                    candidates += ReleaseInfo(
                        tagName = tag,
                        versionName = parsedVersion,
                        releaseName = r.optString("name").ifEmpty { tag },
                        changelog = r.optString("body", ""),
                        isPrerelease = r.optBoolean("prerelease", false),
                        apkUrl = apkAsset?.url,
                        apkSize = apkAsset?.size ?: 0L,
                        apkDigest = apkAsset?.digest,
                    )
                }
                AppLogger.info(
                    TAG,
                    "non-draft releases=${candidates.size} (apk-bearing=${candidates.count { it.apkUrl != null }})",
                )
                if (candidates.isEmpty()) {
                    return@withContext CheckResult.NoReleaseAvailable
                }

                // [T-android-updatechecker-localver-normalize] Normalize the
                // LOCAL version the same way remote tags are (normalizeTag),
                // otherwise the comparison is asymmetric: remote "v0.11-preview"
                // becomes "0.11" but local "0.11-preview" stays raw, and
                // compareVersions("0.11","0.11-preview") puts "" before
                // "preview" in the 3rd component → remote judged OLDER → the
                // user is told they're up to date when they're actually on the
                // matching version (and a real newer "0.12-preview" → "0.12"
                // still compares greater, so updates still surface).
                val localVer = normalizeTag(BuildConfig.VERSION_NAME)
                // Highest version we've seen at all (used for the "release
                // exists but is older or equal" → UpToDate decision and for
                // logging).
                // T4-L4: order by the full parsed version, not by a key that
                // only reflects the first component — `compareVersions(v, "0")`
                // maps both "1.2.3" and "1.9.0" to 1, so ties fell back to list
                // order (created_at desc) and a re-published older release
                // could win the "highest" slot.
                val highest = candidates.maxWithOrNull { a, b ->
                    compareVersions(a.versionName, b.versionName)
                } ?: candidates.first()
                AppLogger.info(
                    TAG,
                    "highest-published tag=${highest.tagName} parsed=${highest.versionName} prerelease=${highest.isPrerelease} apk=${highest.apkUrl != null}",
                )

                // First APK-bearing release with version > local. We pick the
                // highest such release so a stale older APK never shadows a
                // newer non-APK preview.
                val upgradeCandidate = candidates
                    .filter { it.apkUrl != null }
                    .filter { compareVersions(it.versionName, localVer) > 0 }
                    .maxWithOrNull { a, b -> compareVersions(a.versionName, b.versionName) }

                if (upgradeCandidate != null) {
                    AppLogger.info(
                        TAG,
                        "Update available: $localVer → ${upgradeCandidate.versionName} (${upgradeCandidate.tagName}) digest=${upgradeCandidate.apkDigest?.algorithm ?: "none"}",
                    )
                    return@withContext CheckResult.UpdateAvailable(
                        tagName = upgradeCandidate.tagName,
                        versionName = upgradeCandidate.versionName,
                        releaseName = upgradeCandidate.releaseName,
                        changelog = upgradeCandidate.changelog,
                        apkUrl = upgradeCandidate.apkUrl!!,
                        apkSizeBytes = upgradeCandidate.apkSize,
                        publisherDigest = upgradeCandidate.apkDigest,
                    )
                }

                // No newer-with-APK candidate exists. Decide between three
                // remaining states:
                //   1. Highest release ≤ local version → UpToDate.
                //   2. Highest release > local but no APK in the listing →
                //      NoApkAsset (mention the tag so the user can grab the
                //      release manually if they really want).
                //   3. Otherwise (all releases ≤ local) → UpToDate as well.
                val highestVsLocal = compareVersions(highest.versionName, localVer)
                if (highestVsLocal > 0 && highest.apkUrl == null) {
                    AppLogger.info(
                        TAG,
                        "Release ${highest.tagName} > local but no APK asset",
                    )
                    return@withContext CheckResult.NoApkAsset(highest.tagName)
                }

                AppLogger.info(TAG, "Up to date: local=$localVer highest=${highest.versionName}")
                CheckResult.UpToDate
            }
        } catch (e: UnknownHostException) {
            AppLogger.error(TAG, "check failed: UnknownHostException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: ConnectException) {
            AppLogger.error(TAG, "check failed: ConnectException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: SocketTimeoutException) {
            AppLogger.error(TAG, "check failed: SocketTimeoutException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: IOException) {
            // Catch-all for okhttp connection plumbing (e.g.
            // "failed to connect", SSL handshake errors). Most of these in
            // the CN-no-VPN scenario are effectively "can't reach github".
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: Exception) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Public so UI can deep-link users to manual download when GitHub is blocked. */
    const val RELEASES_URL: String = "https://github.com/logicflow-GYW/RikkaMinis/releases"

    /** Returns the first .apk asset's download entry point, or null. */
    private fun findApkAsset(assets: JSONArray?): ApkAsset? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name").lowercase()
            if (name.endsWith(".apk")) {
                val u = a.optString("browser_download_url").ifEmpty { null } ?: continue
                // `digest` was added to the release-assets API in 2025 and is
                // absent on older releases — parsePublisherDigest returns null
                // for both "missing" and "malformed", which [download] treats as
                // "no digest to check" (size-only), never as a failure.
                val rawDigest = a.optString("digest").ifEmpty { null }
                val parsed = parsePublisherDigest(rawDigest)
                if (rawDigest != null && parsed == null) {
                    AppLogger.warning(TAG, "asset digest unparseable, ignoring: $rawDigest")
                }
                if (parsed != null && !parsed.isSupported) {
                    AppLogger.warning(
                        TAG,
                        "asset digest uses unsupported algorithm=${parsed.algorithm}; size-only",
                    )
                }
                return ApkAsset(u, a.optLong("size", 0), parsed)
            }
        }
        return null
    }

    /** One release asset's download coordinates: url + declared size + declared digest. */
    private data class ApkAsset(
        val url: String,
        val size: Long,
        val digest: PublisherDigest?,
    )

    // [fix/updatechecker-semver-prerelease] normalizeTag and compareVersions
    // moved to data/VersionCompare.kt (same package, so the call sites in this
    // file are unchanged). They were private members with no in-repo test;
    // compareVersions now implements real semver prerelease precedence.

    /**
     * Stream the APK from [url] into `${cacheDir}/shared/minis-update.apk`,
     * surfacing progress (0..1) through [onProgress] roughly every 64 KiB.
     * Returns the on-disk [File] on success so the caller can hand it to
     * [installApk]. The path is intentionally inside `shared/` because that's
     * the only sub-directory of cacheDir already exposed by FileProvider in
     * `file_provider_paths.xml`.
     */
    suspend fun download(
        context: Context,
        url: String,
        versionName: String? = null,
        expectedSize: Long = 0L,
        expectedDigest: PublisherDigest? = null,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        // [fix/update-pending-resume] Resume half-loop: a pending APK from a
        // previous download that survived process death is still on disk and
        // intact — skip the network entirely and hand it back. The pending
        // store does not record the request URL, so the request must
        // correlate with the record first ([judgeResumePending]); anything
        // uncorrelated falls through to a fresh download below.
        resumablePending(context)?.let { pending ->
            val integrity = judgeResumePending(
                recordedPublisherDigest = pending.publisherDigest,
                recordedSha256 = pending.sha256,
                recordedSize = pending.apkSize,
                declared = expectedDigest,
                expectedSize = expectedSize,
            )
            if (integrity != null) {
                AppLogger.info(
                    TAG,
                    "resume pending download version=${pending.targetVersionName} " +
                        "size=${pending.apkSize} integrity=$integrity (skipped network)",
                )
                return@withContext DownloadResult.Success(
                    File(pending.apkPath),
                    integrity,
                    resumed = true,
                )
            }
        }
        // [audit-0917] Declared outside try so the catch path can delete a
        // partial file — a truncated APK left on disk could later be consumed
        // by the installer as a valid update.
        var partialFile: File? = null
        try {
            // Stage under filesDir (NOT cacheDir) so the OS doesn't evict
            // the APK mid-flow while the user is in system Settings granting
            // install permission — that eviction caused the "re-download
            // after grant" regression (T-android-update-resume-33637).
            val outDir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
            // Filename keyed by version so a partial old-version download
            // can't accidentally satisfy a check for a newer version.
            val safeName = versionName
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "minis-$it.apk" }
                ?: DOWNLOAD_FILENAME
            val outFile = File(outDir, safeName)
            partialFile = outFile
            // A previous, possibly-aborted download could leave a stale APK
            // behind that the installer would happily try to consume. Wipe it.
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext DownloadResult.Error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var totalRead = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalRead += read
                            if (total > 0) {
                                val pct = ((totalRead * 100) / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            AppLogger.info(TAG, "Downloaded ${outFile.length()} bytes to ${outFile.absolutePath}")
            // [fix/update-digest-verify] End-to-end check against what the
            // release declared. Until this landed the only hash comparison in
            // the whole flow was the local one [PendingUpdateStore.verify]
            // re-does at resume time — i.e. it proved the file had not changed
            // since we wrote it, never that it was the file the publisher
            // shipped. Failure here deletes the bytes and drops any pending
            // record (an older record could otherwise point at this path).
            val actualSize = outFile.length()
            val sha = runCatching { PendingUpdateStore.sha256(outFile) }
                .onFailure { AppLogger.warning(TAG, "sha256 compute failed: ${it.message}") }
                .getOrNull()
            val integrity = judgeDownloadIntegrity(
                declared = expectedDigest,
                expectedSize = expectedSize,
                actualSize = actualSize,
                actualSha256 = sha,
            )
            if (integrity.isFailure) {
                val expected = when (integrity) {
                    DownloadIntegrity.SIZE_MISMATCH -> "$expectedSize bytes"
                    else -> expectedDigest?.hex ?: "(none)"
                }
                val actual = when (integrity) {
                    DownloadIntegrity.SIZE_MISMATCH -> "$actualSize bytes"
                    DownloadIntegrity.HASH_FAILED -> "(hash unavailable)"
                    else -> sha ?: "(none)"
                }
                AppLogger.error(
                    TAG,
                    "download integrity FAILED verdict=$integrity expected=$expected actual=$actual file=${outFile.name}",
                )
                runCatching { outFile.delete() }
                    .onFailure { AppLogger.warning(TAG, "failed to delete rejected file: ${it.message}") }
                PendingUpdateStore.clearPending(context)
                return@withContext DownloadResult.IntegrityFailure(
                    verdict = integrity,
                    expected = expected,
                    actual = actual,
                    reason = integrityMessage(integrity),
                )
            }
            if (!integrity.isVerified) {
                // Not silent: an unverified-but-installable download says why.
                AppLogger.warning(
                    TAG,
                    "download accepted UNVERIFIED verdict=$integrity size=$actualSize digestDeclared=${expectedDigest != null}",
                )
            }
            // Persist so a subsequent Activity recreate (e.g. after the user
            // returns from "install unknown apps" settings) can resume the
            // install without re-downloading. sha256 computed best-effort;
            // verify() falls back to size-only when null.
            if (versionName != null) {
                PendingUpdateStore.setPending(
                    context,
                    PendingUpdateStore.PendingUpdate(
                        targetVersionName = versionName,
                        apkPath = outFile.absolutePath,
                        apkSize = actualSize,
                        sha256 = sha,
                        downloadedAtMs = System.currentTimeMillis(),
                        // Carried so a cross-session file swap must match the
                        // publisher's hash too, not just our own re-hash.
                        publisherDigest = expectedDigest?.takeIf { it.isSupported }?.hex,
                    ),
                )
            }
            DownloadResult.Success(outFile, integrity)
        } catch (e: Exception) {
            AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
            // [audit-0917] Drop the partial file on the exception path too. The
            // integrity-failure path already deletes it; leaving a truncated
            // APK behind meant a later install could consume half a binary.
            partialFile?.let { f ->
                runCatching { f.delete() }
                    .onFailure { AppLogger.warning(TAG, "failed to delete partial download: ${it.message}") }
            }
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** User-facing sentence for a refused download. Kept out of the enum so it stays data. */
    private fun integrityMessage(verdict: DownloadIntegrity): String = when (verdict) {
        DownloadIntegrity.DIGEST_MISMATCH ->
            "Downloaded file does not match the publisher's SHA-256 digest — discarded."
        DownloadIntegrity.SIZE_MISMATCH ->
            "Downloaded file size does not match the release asset — discarded."
        DownloadIntegrity.HASH_FAILED ->
            "Could not hash the downloaded file to verify it — discarded."
        DownloadIntegrity.VERIFIED -> "Verified against the publisher's SHA-256 digest."
        DownloadIntegrity.SIZE_ONLY -> "Checked by size only (release declares no digest)."
        DownloadIntegrity.UNSUPPORTED_ALGORITHM ->
            "Checked by size only (release digest uses an unsupported algorithm)."
    }

    /**
     * Whether the OS will allow this app to launch a package-installer
     * intent. On Android 8+ the user must grant "install unknown apps" per
     * source-app; older releases inherit the system-wide setting.
     */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Send the user to the system "install unknown apps" preferences page
     * for this package. Caller should re-check [canInstall] after the user
     * returns.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Hand [apk] to the system package installer via FileProvider.
     * Caller must ensure [canInstall] before calling, otherwise the system
     * silently bounces back to the launcher. Returns false on any
     * launch failure so callers can surface an error instead of closing
     * the dialog with no visible feedback.
     */
    /**
     * If a pending APK from a previous download is still on disk and intact,
     * returns the [File]. The caller is responsible for checking
     * [canInstall] and firing [installApk]. Returns null when nothing pending
     * or when the cached file failed integrity checks — in the latter case
     * the pending record is cleared so the UI falls through to a fresh
     * download.
     */
    fun resumablePendingFile(context: Context): File? =
        resumablePending(context)?.let { File(it.apkPath) }

    /**
     * Same gates as [resumablePendingFile] (freshness → version → integrity)
     * but returns the full persisted record instead of just the file, so the
     * [download] resume short-circuit can correlate the incoming request with
     * what was recorded before skipping the network. When this returns
     * non-null, [PendingUpdateStore.verify] has already validated the file at
     * [PendingUpdateStore.PendingUpdate.apkPath].
     */
    private fun resumablePending(context: Context): PendingUpdateStore.PendingUpdate? {
        val pending = PendingUpdateStore.getPending(context) ?: return null
        // Only resume if the persisted target is still newer than the running
        // build — protects against the case where the user updated by some
        // other means since the download.
        // [T-android-updatechecker-localver-normalize] targetVersionName is a
        // normalized version (set from upgradeCandidate.versionName), so the
        // local side must be normalized too — same asymmetry fix as check().
        if (compareVersions(pending.targetVersionName, normalizeTag(BuildConfig.VERSION_NAME)) <= 0) {
            AppLogger.info(TAG, "pending target ${pending.targetVersionName} <= local; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        if (PendingUpdateStore.verify(pending) == null) {
            AppLogger.info(TAG, "pending APK failed integrity; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        return pending
    }

    fun installApk(context: Context, apk: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.info(TAG, "installApk launched apk=${apk.absolutePath} size=${apk.length()}")
            // Once the installer is in flight we don't want a subsequent
            // resume to re-fire the intent (would double-prompt). Clear the
            // pending record now; if the user backs out, the next "Check for
            // Updates" tap will re-discover and re-download.
            PendingUpdateStore.clearPending(context)
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "installApk failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // [fix/updatechecker-semver-prerelease] compareVersions moved to
    // data/VersionCompare.kt (same package, so the five call sites above are
    // unchanged) and now implements real semver prerelease precedence. The old
    // inline version let "1.0.0-beta" outrank "1.0.0".
}
