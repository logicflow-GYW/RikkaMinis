package com.rikkaminis.app.debug

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.rikkaminis.app.BuildConfig
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.sandbox.ExecutionCoordinator
import com.rikkaminis.app.sandbox.PRootKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.resume

/**
 * JSON-RPC 2.0 method dispatcher for the debug server.
 * Mirrors iOS DebugServer methods for cross-platform parity.
 *
 * Call `rpc.discover` to retrieve the full method catalogue with parameter
 * schemas and examples. See [DebugMethodRegistry] for the source of truth.
 */
class DebugRPCHandler(private val context: Context) {

    companion object {
        /** Set this from MainActivity to enable screenshot capture. */
        var currentActivity: WeakReference<Activity>? = null
    }

    suspend fun handle(json: String): String {
        val parsed = try {
            JSONObject(json)
        } catch (_: Exception) {
            return errorResponse(-32700, "Parse error", null)
        }

        val id = parsed.opt("id")
        val jsonrpc = parsed.optString("jsonrpc")
        if (jsonrpc != "2.0") {
            return errorResponse(-32600, "Invalid Request: missing jsonrpc 2.0", id)
        }

        val method = parsed.optString("method")
        if (method.isEmpty()) {
            return errorResponse(-32600, "Invalid Request: missing method", id)
        }

        val params = parsed.optJSONObject("params") ?: JSONObject()

        return try {
            val result = dispatch(method, params)
            successResponse(result, id)
        } catch (e: RPCException) {
            errorResponse(e.code, e.message ?: "Unknown error", id)
        } catch (e: Exception) {
            errorResponse(-32000, e.message ?: "Internal error", id)
        }
    }

    private suspend fun dispatch(method: String, params: JSONObject): Any {
        return when (method) {
            "rpc.discover" -> DebugMethodRegistry.discover()
            "debug.appInfo" -> handleAppInfo()
            "debug.screenshot" -> handleScreenshot(params)
            "debug.ls" -> handleLS(params)
            "debug.rawLs" -> handleRawLS(params)
            "debug.readFile" -> handleReadFile(params)
            "debug.logs.list" -> handleLogsList()
            "debug.logs.read" -> handleLogsRead(params)
            "debug.logs.setEnabled" -> handleLogsSetEnabled(params)
            "debug.tap" -> handleTap(params)
            "debug.scroll" -> handleScroll(params)
            "debug.inputText" -> handleInputText(params)
            "debug.llmRequests" -> handleLLMRequests(params)
            "debug.llmRequests.clear" -> { LLMRequestLog.clear(); JSONObject().put("cleared", true) }
            "debug.agentTrace" -> handleAgentTrace(params)
            "debug.fetch" -> handleFetch(params)
            "debug.shellExecute" -> handleShellExecute(params)
            "debug.update.check" -> handleUpdateCheck()
            "debug.update.download" -> handleUpdateDownload(params)
            "debug.update.install" -> handleUpdateInstall(params)
            "debug.permissions.list" -> handlePermissionsList()

            // UI introspection (Layer A parity with iOS)
            "debug.viewTree" -> handleViewTree(params)
            "debug.search" -> handleSearch(params)
            "debug.inspect" -> handleInspect(params)
            "debug.highlight" -> handleHighlight(params)
            "debug.cloudSync" -> handleCloudSync()
            "debug.writeFile" -> handleWriteFile(params)
            "debug.screenshot.capture" -> handleScreenshotCapture(params)
            "debug.screenshot.list" -> DebugScreenshotRing.listJson()
            "debug.screenshot.get" -> handleScreenshotGet(params)
            "debug.screenshot.clear" -> JSONObject().put("cleared", DebugScreenshotRing.clearAll())

            // Browser
            "debug.browser.listTabs" -> BrowserDebugMethods.listTabs(context)
            "debug.browser.pageInfo" -> BrowserDebugMethods.pageInfo(context, params)
            "debug.browser.executeJS" -> BrowserDebugMethods.executeJS(context, params)
            "debug.browser.getReadable" -> BrowserDebugMethods.getReadable(context, params)
            "debug.browser.getText" -> BrowserDebugMethods.getText(context, params)
            "debug.browser.screenshot" -> BrowserDebugMethods.screenshot(context, params)

            // Provider (read)
            "provider.types" -> ProviderDebugMethods.types()
            "provider.instances.list" -> ProviderDebugMethods.instancesList(context, params)
            "provider.models.list" -> ProviderDebugMethods.modelsList(context, params)
            "provider.groups.list" -> ProviderDebugMethods.groupsList(context, params)
            "provider.export" -> ProviderDebugMethods.export(context, params)
            "provider.import" -> ProviderDebugMethods.import(context, params)

            // Provider (mutate)
            "provider.instances.create" -> ProviderMutationMethods.instancesCreate(context, params)
            "provider.instances.update" -> ProviderMutationMethods.instancesUpdate(context, params)
            "provider.instances.delete" -> ProviderMutationMethods.instancesDelete(context, params)
            "provider.instances.test" -> ProviderMutationMethods.instancesTest(context, params)
            "provider.models.add" -> ProviderMutationMethods.modelsAdd(context, params)
            "provider.models.update" -> ProviderMutationMethods.modelsUpdate(context, params)
            "provider.models.delete" -> ProviderMutationMethods.modelsDelete(context, params)
            "provider.models.refresh" -> ProviderMutationMethods.modelsRefresh(context, params)
            "provider.models.setAgentLoop" -> ProviderMutationMethods.modelsSetAgentLoop(context, params)
            "provider.groups.create" -> ProviderMutationMethods.groupsCreate(context, params)
            "provider.groups.update" -> ProviderMutationMethods.groupsUpdate(context, params)
            "provider.groups.delete" -> ProviderMutationMethods.groupsDelete(context, params)
            "provider.groups.setDefault" -> ProviderMutationMethods.groupsSetDefault(context, params)
            "provider.groups.setAgentLoop" -> ProviderMutationMethods.groupsSetAgentLoop(context, params)

            // Chat (read)
            "chat.sessions.list" -> ChatDebugMethods.sessionsList(context, params)
            "chat.sessions.get" -> ChatDebugMethods.sessionsGet(context, params)
            "chat.sessions.usage" -> ChatDebugMethods.sessionsUsage(context, params)
            "chat.messages.list" -> ChatDebugMethods.messagesList(context, params)
            "chat.models.list" -> ChatDebugMethods.modelsList(context, params)

            // Chat (mutate)
            "chat.prompt" -> ChatMutationMethods.prompt(context, params)
            "chat.uiPrompt" -> ChatMutationMethods.uiPrompt(context, params)
            "chat.retry" -> ChatMutationMethods.retry(context, params)
            "chat.rerunFromToolBlock" -> ChatMutationMethods.rerunFromToolBlock(context, params)
            "chat.session.status" -> ChatMutationMethods.status(context, params)
            "chat.session.cancel" -> ChatMutationMethods.cancel(context, params)
            "chat.session.selectModel" -> ChatMutationMethods.selectModel(context, params)
            "chat.session.delete" -> ChatMutationMethods.delete(context, params)

            // Chat compact (mirrors iOS chat.compact.* namespace).
            "chat.compact.before" -> ChatMutationMethods.compactBefore(context, params)
            "chat.compact.markers.list" -> ChatMutationMethods.compactMarkersList(context, params)
            "chat.compact.revert" -> ChatMutationMethods.compactRevert(context, params)

            // Debug-only: direct CLI / offload-handler invocation (T344).
            // Registered solely on DEBUG builds so release APKs cannot expose it.
            "debug.shizuku.exec" -> {
                if (!BuildConfig.DEBUG) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleShizukuExec(params)
            }
            "debug.modelUse.exec" -> {
                if (!BuildConfig.DEBUG) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleModelUseExec(params)
            }
            // [T-android-sessions-cli-full] DEBUG-only invocation of the
            // SessionsOffloadHandler — parallels debug.modelUse.exec so test
            // harnesses can verify minis-sessions-cli (list / search /
            // messages, incl. --full) end-to-end without an in-shell prompt.
            "debug.sessions.exec" -> {
                if (!BuildConfig.DEBUG) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleSessionsExec(params)
            }
            // [T-minis-config-provider-add] DEBUG-only invocation of the
            // ConfigOffloadHandler — parallels debug.modelUse.exec so test
            // harnesses can exercise minis-config (get / set / set-batch /
            // audit-*) without driving an in-shell prompt. The handler
            // re-uses the production ConfigBridge code path; we override
            // skipConfirmation under the hood via a dedicated arg the
            // production CLI never exposes.
            "debug.minisConfig.exec" -> {
                if (!BuildConfig.DEBUG) {
                    throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
                }
                handleMinisConfigExec(params)
            }

            else -> throw RPCException(-32601, "Method not found: $method. Call 'rpc.discover' to list available methods.")
        }
    }

    // ── App Info ─────────────────────────────────────────────────────────────

    private fun handleAppInfo(): JSONObject {
        val filesDir = context.filesDir
        return JSONObject().apply {
            put("platform", "android")
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.RELEASE)
            put("prootBooted", PRootKernel.isBooted)
            put("filesDir", filesDir.absolutePath)
            put("logFiles", AppLogger.listLogFiles().size)
            put("totalLogSize", AppLogger.totalSize())
            put("diskUsage", JSONObject().apply {
                put("filesDir", dirSize(filesDir))
                put("sessions", dirSize(File(filesDir, "minis-sessions")))
                put("global", dirSize(File(filesDir, "minis-global")))
            })
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ── Screenshot ──────────────────────────────────────────────────────────

    private suspend fun handleScreenshot(params: JSONObject): JSONObject {
        val scale = params.optDouble("scale", 1.0).toFloat().coerceIn(0.05f, 1.0f)

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity for screenshot")

        val bitmap = withContext(Dispatchers.Main) {
            captureActivityScreenshot(activity, scale)
        }

        val baos = ByteArrayOutputStream()
        // record-ok: deep-audit-1048 A-02 radius — this DEBUG RPC screenshot
        // channel is outside the 5 production files in scope. A failed encode
        // still answers a `size: 0` payload (latent, debug-only, no file is
        // published). Deferred as §1048-A2 R5 with a trigger condition; fix it
        // through saveOutcome(streamOpened = true, compressed = …) like the
        // 1048 sites when that surface is next touched.
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        val pngBytes = baos.toByteArray()
        val base64 = android.util.Base64.encodeToString(pngBytes, android.util.Base64.NO_WRAP)

        return JSONObject().apply {
            put("base64", base64)
            put("size", pngBytes.size)
            put("encoding", "png")
        }
    }

    private suspend fun captureActivityScreenshot(activity: Activity, scale: Float): Bitmap =
        suspendCancellableCoroutine { cont ->
            val rootView = activity.window.decorView.rootView
            // Use PixelCopy for accurate capture on API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val width = (rootView.width * scale).toInt().coerceAtLeast(1)
                val height = (rootView.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(
                    activity.window, bitmap,
                    { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            cont.resume(bitmap)
                        } else {
                            // Fallback to canvas draw
                            cont.resume(canvasCapture(rootView, scale))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } else {
                cont.resume(canvasCapture(rootView, scale))
            }
        }

    private fun canvasCapture(view: android.view.View, scale: Float): Bitmap {
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        view.draw(canvas)
        return bitmap
    }

    // ── File System ─────────────────────────────────────────────────────────

    private fun handleLS(params: JSONObject): Any {
        val path = params.optString("path", "/")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        val recursive = params.optBoolean("recursive", false)
        val maxDepth = params.optInt("maxDepth", 3)

        // [dv16-A03] The per-session bind mounts (workspace/attachments/
        // offloads/browser) are NOT in the global bindMounts map, and the
        // global resolveHostPath searches every minis-sessions/<id>/ tree for
        // an existing match — an `ls` from one session would list whichever
        // session's tree came first. The caller's session id (forwarded by
        // DebugOffloadHandler, or sent directly by an external JSON-RPC
        // client) scopes the resolution; null keeps the historical global
        // behaviour for Coil-style callers without a session.
        val sessionId = params.optString("sessionId", "").ifBlank { null }
        val hostFile = PRootKernel.resolvePathForSession(sessionId, path, context)
            ?: throw RPCException(-32602, "Cannot resolve path: $path")

        if (!hostFile.isDirectory) throw RPCException(-32602, "Not a directory: $path")

        return if (recursive) {
            listRecursive(hostFile, path, maxDepth, 0)
        } else {
            listFlat(hostFile)
        }
    }

    /**
     * Raw list of a host-filesystem directory, bypassing the PRoot
     * bindMounts/rootfs resolver. Constrained to filesDir to avoid poking
     * at arbitrary paths. Use `minis-sessions` (default) to enumerate every
     * session's attachments/workspace/... directories and find files that
     * were written into a session we no longer have mounted.
     */
    private fun handleRawLS(params: JSONObject): Any {
        val subPath = params.optString("path", "minis-sessions")
        if (subPath.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
        val recursive = params.optBoolean("recursive", true)
        val maxDepth = params.optInt("maxDepth", 4)

        val base = context.filesDir
        val target = if (subPath.isEmpty() || subPath == "/") base else File(base, subPath)
        if (!target.exists()) throw RPCException(-32602, "Not found: ${target.absolutePath}")
        if (!target.isDirectory) throw RPCException(-32602, "Not a directory: ${target.absolutePath}")

        val root = JSONObject()
        root.put("root", target.absolutePath)
        root.put("entries",
            if (recursive) listRecursive(target, target.absolutePath, maxDepth, 0)
            else listFlat(target)
        )
        return root
    }

    private fun listFlat(dir: File): JSONArray {
        val files = dir.listFiles() ?: return JSONArray()
        val array = JSONArray()
        for (file in files.sortedBy { it.name }) {
            array.put(JSONObject().apply {
                put("name", file.name)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("modified", file.lastModified())
            })
        }
        return array
    }

    private fun listRecursive(dir: File, virtualPath: String, maxDepth: Int, depth: Int): JSONArray {
        val array = JSONArray()
        val files = dir.listFiles()?.sortedBy { it.name } ?: return array
        for (file in files) {
            val childPath = if (virtualPath == "/") "/${file.name}" else "$virtualPath/${file.name}"
            val entry = JSONObject().apply {
                put("name", file.name)
                put("path", childPath)
                put("type", if (file.isDirectory) "directory" else "file")
                put("size", file.length())
                put("modified", file.lastModified())
            }
            if (file.isDirectory && depth < maxDepth) {
                entry.put("children", listRecursive(file, childPath, maxDepth, depth + 1))
            }
            array.put(entry)
        }
        return array
    }

    private fun handleReadFile(params: JSONObject): JSONObject {
        val path = params.optString("path")
        if (path.isEmpty()) throw RPCException(-32602, "Invalid params: 'path' is required")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        // [dv16-A03] Session-scoped resolution — same shape as handleLS. A
        // session hit never falls back to the global resolver, so a `read`
        // from one session cannot pick up another session's same-named file.
        val sessionId = params.optString("sessionId", "").ifBlank { null }
        val hostFile = PRootKernel.resolvePathForSession(sessionId, path, context)
            ?: throw RPCException(-32602, "Cannot resolve path: $path")

        if (!hostFile.exists()) throw RPCException(-32602, "File not found: $path")

        val fileSize = hostFile.length()
        val offset = params.optLong("offset", 0)
        // [fix/audit-s7l2] clamp limit like handleLogsRead/maxDepth/scale —
        // previously a token-authenticated client could pass Int.MAX_VALUE and
        // trigger a huge allocation. Cap at 4 MiB (a short read is normal when
        // the file is smaller).
        val limit = params.optInt("limit", 524_288).coerceIn(1, 4 * 1024 * 1024)
        val forceBase64 = params.optBoolean("base64", false)

        // [da1028-A] was `inputStream().use { it.skip(offset); it.readNBytes(limit) }`:
        // readNBytes(int) is API 33+ (minSdk 26 → NoSuchMethodError on Android
        // 8-12) and `skip`'s return value was dropped, so a short skip silently
        // read from the wrong offset. RandomAccessFile.seek + bounded read has
        // neither problem — same shape as AppLogger.readLogSegment.
        val raw = readFileSegment(hostFile, offset, limit)

        // [R4-R F-10] A page must not split a character. `offset`/`limit` are
        // BYTE windows, so a 1000-byte page over a Chinese log used to cut a
        // 3-byte UTF-8 sequence in half, and `String(bytes, UTF_8)` answered
        // U+FFFD for the fragment — silently, on every page boundary. Trim the
        // page back to the last complete character and report the trimmed size
        // in `bytesRead`: a client advances by that, so the next page starts on
        // the character's lead byte and nothing is lost.
        // `bytesRead` is the caller's advance, so the trim must never empty the
        // page: a `limit` shorter than the single character it starts with would
        // otherwise answer 0 and loop forever. Fail open — keep the page and
        // accept one replacement character.
        val trimmedEnd = if (forceBase64) raw.size else utf8CompletePrefixLength(raw)
        val textEnd = if (trimmedEnd == 0 && raw.isNotEmpty()) raw.size else trimmedEnd
        val textCandidate = if (textEnd == raw.size) raw else raw.copyOf(textEnd)
        // Binary detection is a real UTF-8 validity check rather than the old
        // "any byte above 0x7F counts as text" whitelist, which decoded binary
        // as text (U+FFFD soup) while refusing ANSI-coloured terminal output
        // because ESC (0x1B) sat outside the allowlist.
        val isText = !forceBase64 && looksLikeUtf8Text(textCandidate)
        val bytes = if (isText) textCandidate else raw

        // [R5-F F-F-08] The other half of the same boundary problem: R4-R F-10
        // trims the page TAIL, but a caller that hand-picks an unaligned
        // `offset` opens the window in the MIDDLE of a character, and
        // `String(bytes, UTF_8)` then answered U+FFFD for those orphan
        // continuation bytes — one per page, and with a `limit` that only covers
        // a fragment the whole page was U+FFFD. [decodeUtf8Page] skips them,
        // from the same index [looksLikeUtf8Text] validated, and drops them from
        // `content` (never from `bytesRead`: the caller still advances past the
        // fragment, so it cannot loop on the same offset). An aligned caller —
        // one that advances by `bytesRead` — never trips this, because the
        // previous page's tail trim already left it on a lead byte.
        return JSONObject().apply {
            put("size", fileSize)
            if (isText) {
                put("content", decodeUtf8Page(bytes))
                put("encoding", "utf8")
            } else {
                put("content", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                put("encoding", "base64")
            }
            put("bytesRead", bytes.size)
            if (offset + bytes.size < fileSize) put("truncated", true)
        }
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private fun handleLogsList(): JSONObject {
        val files = AppLogger.listLogFiles()
        val array = JSONArray()
        for (file in files) {
            array.put(JSONObject().apply {
                put("name", file.name)
                put("size", file.length())
                put("modified", file.lastModified())
            })
        }
        return JSONObject().apply {
            put("totalSize", AppLogger.totalSize())
            put("files", array)
        }
    }

    private fun handleLogsRead(params: JSONObject): JSONObject {
        val name = params.optString("name")
        if (name.isEmpty()) throw RPCException(-32602, "Invalid params: 'name' is required")
        if (name.contains("/") || name.contains("..")) {
            throw RPCException(-32602, "Invalid params: 'name' must be a filename")
        }

        // T-android-log-read-oom: bounded segment read via RandomAccessFile
        // instead of a full-file readText() — a multi-MB log loaded whole can
        // OOM the process. offset/limit semantics unchanged.
        val offset = params.optInt("offset", 0)
        val limit = params.optInt("limit", 524_288)
        val segment = AppLogger.readLogSegment(name, offset, limit)
            ?: throw RPCException(-32602, "Log file not found: $name")

        return JSONObject().apply {
            put("name", name)
            put("size", segment.totalSize)
            put("content", segment.content)
            put("bytesRead", segment.bytesRead)
            if (segment.truncated) put("truncated", true)
        }
    }

    // ── Touch & Input Simulation ────────────────────────────────────────────

    private suspend fun handleTap(params: JSONObject): JSONObject {
        val x = params.optDouble("x", -1.0).toFloat()
        val y = params.optDouble("y", -1.0).toFloat()
        if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        withContext(Dispatchers.Main) {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(down)
            down.recycle()

            val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(up)
            up.recycle()
        }

        return JSONObject().apply {
            put("ok", true)
            put("point", JSONObject().put("x", x).put("y", y))
        }
    }

    private suspend fun handleScroll(params: JSONObject): JSONObject {
        val x = params.optDouble("x", -1.0).toFloat()
        val y = params.optDouble("y", -1.0).toFloat()
        val deltaX = params.optDouble("deltaX", 0.0).toFloat()
        val deltaY = params.optDouble("deltaY", 0.0).toFloat()

        if (x < 0 || y < 0) throw RPCException(-32602, "Invalid params: 'x' and 'y' are required")
        if (deltaX == 0f && deltaY == 0f) throw RPCException(-32602, "At least one of deltaX/deltaY must be non-zero")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        withContext(Dispatchers.Main) {
            val downTime = SystemClock.uptimeMillis()
            val steps = 10
            val stepDuration = 16L // ~60fps

            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(down)
            down.recycle()

            for (i in 1..steps) {
                val frac = i.toFloat() / steps
                val moveX = x + deltaX * frac
                val moveY = y + deltaY * frac
                val move = MotionEvent.obtain(downTime, downTime + i * stepDuration, MotionEvent.ACTION_MOVE, moveX, moveY, 0)
                move.source = InputDevice.SOURCE_TOUCHSCREEN
                activity.dispatchTouchEvent(move)
                move.recycle()
            }

            val up = MotionEvent.obtain(downTime, downTime + (steps + 1) * stepDuration, MotionEvent.ACTION_UP, x + deltaX, y + deltaY, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(up)
            up.recycle()
        }

        return JSONObject().apply {
            put("ok", true)
            put("start", JSONObject().put("x", x).put("y", y))
            put("delta", JSONObject().put("x", deltaX).put("y", deltaY))
        }
    }

    private suspend fun handleInputText(params: JSONObject): JSONObject {
        val text = params.optString("text")
        if (text.isEmpty()) throw RPCException(-32602, "Invalid params: 'text' is required")

        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")

        // [T-debug-inputtext-mainthread] Instrumentation.sendStringSync throws
        // "This method can not be called from the main application thread" —
        // it BLOCKS waiting for the injected key events to be processed by the
        // main thread, so it must be dispatched off-main. Note it maps chars
        // through KeyCharacterMap, so only ASCII-typable text lands; CJK input
        // needs the IME path and is out of scope for this harness hook.
        withContext(Dispatchers.IO) {
            val instrumentation = android.app.Instrumentation()
            instrumentation.sendStringSync(text)
        }

        return JSONObject().apply {
            put("ok", true)
            put("length", text.length)
        }
    }

    // ── LLM Request Tracking ──────────────────────────────────────────────

    private fun handleLLMRequests(params: JSONObject): JSONObject {
        val last = if (params.has("last") && !params.isNull("last")) params.optInt("last", -1).takeIf { it > 0 } else null
        val raw = LLMRequestLog.toJSON(last)
        if (!params.optBoolean("formatted", false)) return raw
        return JSONObject().apply {
            put("count", raw.optInt("count", 0))
            put("text", formatLLMRequestsText(raw))
        }
    }

    private fun formatLLMRequestsText(raw: JSONObject): String {
        val arr = raw.optJSONArray("requests") ?: return ""
        val sb = StringBuilder()
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val provider = e.optString("provider")
            val timestamp = e.optLong("timestamp", 0)
            val durMs = e.optLong("durationMs", 0)
            val status = e.optInt("responseStatusCode", 0)
            val usage = e.optJSONObject("usage")
            val usageStr = if (usage != null) {
                buildString {
                    append("in:").append(usage.optInt("inputTokens", usage.optInt("input_tokens", 0)))
                    append(" out:").append(usage.optInt("outputTokens", usage.optInt("output_tokens", 0)))
                    val cr = usage.optInt("cacheReadTokens", usage.optInt("cache_read_tokens", 0))
                    if (cr > 0) append(" cache_read:").append(cr)
                }
            } else ""
            sb.append("// --- #").append(i + 1).append(' ')
                .append(provider).append(' ')
                .append(if (timestamp > 0) ts.format(java.util.Date(timestamp)) else "")
                .append(' ').append(durMs).append("ms")
                .append(" HTTP ").append(status)
                .append(if (usageStr.isNotEmpty()) " | $usageStr" else "")
                .append(" ---\n")
            sb.append("// ").append(e.optString("requestMethod"))
                .append(' ').append(e.optString("requestURL")).append('\n')
            val body = e.opt("requestBody")
            if (body != null) sb.append(body.toString()).append('\n')
            val resp = e.optString("responseBody", "")
            if (resp.isNotEmpty()) {
                sb.append("// --- Response (").append(resp.length).append(" chars) ---\n")
                sb.append(resp).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Aggregate "agent trace" view — alias for `debug.llmRequests` exposed
     * under the iOS-compatible name. `last=true` returns just the most recent
     * trace object instead of an array (mirrors iOS docs).
     */
    private fun handleAgentTrace(params: JSONObject): JSONObject {
        val onlyLast = params.optBoolean("last", false)
        if (onlyLast) {
            val tail = LLMRequestLog.getLast(1)
            if (tail.isEmpty()) return JSONObject().put("traces", org.json.JSONArray())
            // Reuse the same JSON shape `debug.llmRequests` produces so callers
            // get a consistent envelope (the underlying Entry → JSON mapping
            // lives in LLMRequestLog).
            val full = LLMRequestLog.toJSON(1)
            val arr = full.optJSONArray("requests")
            return arr?.optJSONObject(0) ?: JSONObject().put("traces", org.json.JSONArray())
        }
        val full = LLMRequestLog.toJSON(null)
        return JSONObject().put("traces", full.optJSONArray("requests") ?: org.json.JSONArray())
    }

    private fun handleLogsSetEnabled(params: JSONObject): JSONObject {
        if (!params.has("enabled")) throw RPCException(-32602, "Missing 'enabled' param")
        val enabled = params.optBoolean("enabled", false)
        AppLogger.setEnabled(context, enabled)
        return JSONObject().put("enabled", AppLogger.isEnabled(context))
    }

    // ── Response helpers ────────────────────────────────────────────────────

    private fun metaObject(): JSONObject {
        return JSONObject().apply {
            put("app", "MinisApp")
            put("version", BuildConfig.VERSION_NAME)
            put("build", BuildConfig.VERSION_CODE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("platform", "android")
        }
    }

    private fun successResponse(result: Any, id: Any?): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
            put("_meta", metaObject())
        }.toString()
    }

    fun errorJSON(code: Int, message: String): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
            put("_meta", metaObject())
        }.toString()
    }

    private fun errorResponse(code: Int, message: String, id: Any?): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
            put("_meta", metaObject())
        }.toString()
    }

    private fun handleFetch(params: JSONObject): JSONObject {
        val url = params.optString("url").ifEmpty { throw RPCException(-32602, "Missing 'url' param") }
        val result = JSONObject()

        // Test 1: DNS
        try {
            val host = java.net.URI(url).host
            val addrs = java.net.InetAddress.getAllByName(host)
            result.put("dns", addrs.joinToString(", ") { it.hostAddress })
        } catch (e: Exception) {
            result.put("dns_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        // Test 2: HttpURLConnection
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            result.put("httpurlconn_status", conn.responseCode)
            conn.disconnect()
        } catch (e: Exception) {
            result.put("httpurlconn_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        // Test 3: OkHttp
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val req = okhttp3.Request.Builder().url(url).get().build()
            val resp = client.newCall(req).execute()
            result.put("okhttp_status", resp.code)
            resp.close()
        } catch (e: Exception) {
            result.put("okhttp_error", "${e.javaClass.simpleName}: ${e.message}")
        }

        // Test 4: Proxy info
        try {
            val selector = java.net.ProxySelector.getDefault()
            val proxies = selector.select(java.net.URI(url))
            result.put("proxies", proxies.joinToString(", ") { it.toString() })
        } catch (e: Exception) {
            result.put("proxy_error", e.message)
        }

        return result
    }

    // ── Shell Execute (PRoot sandbox) ────────────────────────────────────────

    /**
     * Run a command inside the PRoot sandbox for the given session and return
     * `{ output, exit_code }`. Mirrors iOS `debug.shellExecute`. Debug-only;
     * meant for integration-test harnesses that need to drive shell tools
     * (`minis-browser-use`, `minis-open`, …) without going through the agent.
     *
     * Params:
     *   command  (string, required) — command line to run under /bin/sh -c.
     *   session  (string)           — session id. Defaults to "debug-rpc" so
     *                                 callers don't accidentally mutate a
     *                                 real chat's shell state.
     *   timeout  (int)              — timeout in seconds. Default 60.
     */
    private suspend fun handleShellExecute(params: JSONObject): JSONObject {
        val command = params.optString("command").ifEmpty {
            throw RPCException(-32602, "Missing 'command' param")
        }
        val session = params.optString("session", "debug-rpc").ifEmpty { "debug-rpc" }
        val timeoutSec = params.optInt("timeout", 60).coerceIn(1, 900)

        // Mirror ChatViewModel's terminal lineCallback: scan raw lines for
        // OSC MinisOpenURL markers before TerminalSanitizer strips them and
        // hand captured URLs to the broker so test harnesses driving
        // `minis-open` via this RPC trigger the same in-app preview flow as
        // real chat shell output.
        val capturedUrls = mutableListOf<String>()
        val result = try {
            ExecutionCoordinator.execute(
                sessionId = session,
                command = command,
                timeout = timeoutSec * 1000L,
                lineCallback = { rawLine, _ ->
                    val (_, urls) = com.rikkaminis.app.terminal.MinisUrlMarker.extract(rawLine)
                    capturedUrls.addAll(urls)
                },
            )
        } catch (e: Exception) {
            throw RPCException(-32000, "Shell execute failed: ${e.message}")
        }
        for (raw in capturedUrls) {
            com.rikkaminis.app.terminal.MinisOpenUrlBroker.offer(raw)
        }
        return JSONObject()
            .put("output", result.output)
            .put("exit_code", result.exitCode)
            .put("session", session)
    }

    // ── Update checker (T33) — exposed only via DebugRPC so the e2e flow can
    // be exercised before the production UI entry point lands.
    private suspend fun handleUpdateCheck(): JSONObject {
        return when (val r = com.rikkaminis.app.data.UpdateChecker.check()) {
            is com.rikkaminis.app.data.UpdateChecker.CheckResult.UpdateAvailable -> JSONObject()
                .put("status", "update_available")
                .put("tag_name", r.tagName)
                .put("version_name", r.versionName)
                .put("release_name", r.releaseName)
                .put("apk_url", r.apkUrl)
                .put("apk_size", r.apkSizeBytes)
                .put("changelog", r.changelog)
                // [fix/update-digest-verify] Echo whatever the publisher
                // declared so the e2e flow can hand it back to update.download
                // and so an unverified update is visible as such.
                .put("publisher_digest", r.publisherDigest?.let { "${it.algorithm}:${it.hex}" } ?: JSONObject.NULL)
                .put("verified", r.publisherDigestAvailable)
            com.rikkaminis.app.data.UpdateChecker.CheckResult.UpToDate ->
                JSONObject().put("status", "up_to_date")
            com.rikkaminis.app.data.UpdateChecker.CheckResult.NoReleaseAvailable ->
                JSONObject().put("status", "no_release")
            is com.rikkaminis.app.data.UpdateChecker.CheckResult.NoApkAsset ->
                JSONObject().put("status", "no_apk_asset").put("tag_name", r.tagName)
            com.rikkaminis.app.data.UpdateChecker.CheckResult.Forbidden ->
                JSONObject().put("status", "forbidden")
            com.rikkaminis.app.data.UpdateChecker.CheckResult.NetworkUnreachable ->
                JSONObject().put("status", "network_unreachable")
            is com.rikkaminis.app.data.UpdateChecker.CheckResult.Error ->
                JSONObject().put("status", "error").put("message", r.message)
        }
    }

    private suspend fun handleUpdateDownload(params: JSONObject): JSONObject {
        val url = params.optString("url").ifEmpty {
            throw RPCException(-32602, "Missing 'url' parameter")
        }
        // [fix/update-digest-verify] Both are optional: a caller that only has
        // a URL still works, it just lands on the size-only path. Accepts the
        // raw GitHub shape ("sha256:<hex>") so the value from update.check can
        // be passed straight through.
        val expectedSize = params.optLong("size", 0L)
        val rawDigest = params.optString("digest").ifEmpty { null }
        val expectedDigest = com.rikkaminis.app.data.parsePublisherDigest(rawDigest)
        if (rawDigest != null && expectedDigest == null) {
            throw RPCException(-32602, "Unparseable 'digest' parameter: $rawDigest")
        }
        val result = com.rikkaminis.app.data.UpdateChecker.download(
            context = context,
            url = url,
            expectedSize = expectedSize,
            expectedDigest = expectedDigest,
        )
        return when (result) {
            is com.rikkaminis.app.data.UpdateChecker.DownloadResult.Success -> JSONObject()
                .put("status", "ok")
                .put("path", result.file.absolutePath)
                .put("size", result.file.length())
                .put("integrity", result.integrity.name)
                .put("verified", result.integrity.isVerified)
                // [fix/update-pending-resume] True when a persisted pending
                // record satisfied this request and the network was skipped —
                // lets the e2e flow assert the resume half-loop actually
                // closed. New additive field; fresh downloads report false.
                .put("resumed", result.resumed)
            is com.rikkaminis.app.data.UpdateChecker.DownloadResult.IntegrityFailure -> JSONObject()
                .put("status", "integrity_failed")
                .put("verdict", result.verdict.name)
                .put("expected", result.expected ?: JSONObject.NULL)
                .put("actual", result.actual ?: JSONObject.NULL)
                .put("message", result.reason)
            is com.rikkaminis.app.data.UpdateChecker.DownloadResult.Error -> JSONObject()
                .put("status", "error")
                .put("message", result.message)
        }
    }

    private fun handleUpdateInstall(params: JSONObject): JSONObject {
        val path = params.optString("path").ifEmpty {
            throw RPCException(-32602, "Missing 'path' parameter")
        }
        val file = java.io.File(path)
        if (!file.exists()) throw RPCException(-32000, "APK not found: $path")
        val canInstall = com.rikkaminis.app.data.UpdateChecker.canInstall(context)
        return try {
            com.rikkaminis.app.data.UpdateChecker.installApk(context, file)
            JSONObject().put("status", "launched").put("can_install", canInstall)
        } catch (e: Exception) {
            JSONObject()
                .put("status", "error")
                .put("can_install", canInstall)
                .put("message", e.message ?: e.javaClass.simpleName)
        }
    }

    // ── UI introspection (Layer A) ───────────────────────────────────────────

    private suspend fun handleViewTree(params: JSONObject): Any {
        val maxDepth = params.optInt("maxDepth", 50).coerceIn(1, 200)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val trees = DebugInspectorMethods.viewTree(activity, maxDepth)
        return if (trees.length() == 1) trees.get(0) else trees
    }

    private suspend fun handleSearch(params: JSONObject): JSONArray {
        val keyword = params.optString("keyword").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'keyword' is required")
        }
        val scope = params.optString("scope", "all").ifEmpty { "all" }
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        return DebugInspectorMethods.search(activity, keyword, scope)
    }

    private suspend fun handleInspect(params: JSONObject): JSONObject {
        val address = params.optString("address").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'address' is required")
        }
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        return DebugInspectorMethods.inspect(activity, address)
            ?: JSONObject().put("error", "Node not found at address $address (call viewTree/search first)")
    }

    private suspend fun handleHighlight(params: JSONObject): JSONObject {
        val address = params.optString("address").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'address' is required")
        }
        val color = params.optString("color", "red").ifEmpty { "red" }
        val duration = params.optDouble("duration", 2.0)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val ok = DebugInspectorMethods.highlight(activity, address, color, duration)
        return JSONObject().put("ok", ok)
    }

    /**
     * Stub for parity with iOS `debug.cloudSync`. Android does not have an
     * iCloud-equivalent built into MinisApp, so we report disabled and an
     * empty device list rather than fail the call. Lets cross-platform
     * harnesses skip the check uniformly.
     */
    private fun handleCloudSync(): JSONObject {
        return JSONObject().apply {
            put("enabled", false)
            put("status", "unsupported")
            put("reason", "Android build does not include iCloud sync")
            put("devices", JSONArray())
            put("deviceCount", 0)
        }
    }

    /**
     * Write a file into the proot-mounted Linux namespace. Resolves the
     * Linux path through PRootKernel.resolvePathForSession ([dv16-A03]:
     * session-scoped when the request carries a `sessionId`, so a write from
     * one session cannot overwrite another session's same-named file, and a
     * new session-relative file lands inside that session's tree instead of
     * the rootfs ghost dir), creates parent dirs, writes the bytes, and
     * best-effort applies the requested mode bits. No fakefs registration is
     * needed — proot reads the host directly, so the guest sees the file the
     * moment it lands on disk.
     */
    private fun handleWriteFile(params: JSONObject): JSONObject {
        val path = params.optString("path").ifEmpty {
            throw RPCException(-32602, "Invalid params: 'path' is required")
        }
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")
        if (!params.has("content")) throw RPCException(-32602, "Invalid params: 'content' is required")
        val content = params.optString("content")
        val encoding = params.optString("encoding", "utf8").lowercase().ifEmpty { "utf8" }
        val overwrite = params.optBoolean("overwrite", true)
        val modeStr = params.optString("mode", "0644")

        // [da1031-C] ISSUE-04 B1: resolveHostPath lands on the real host file
        // behind a bind mount, and T219's read-only mount lock is software-only
        // — without this check `minis-debug write /var/minis/mounts/<ro>/f`
        // modified a Locked mount's files. Same guard the write tools call
        // before touching disk; -32000 = the family's server error code (the
        // "File exists and overwrite=false" refusal below uses it too).
        if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
            throw RPCException(-32000, "Read-only mount: '$path' is not writable")
        }

        // Pre-boot fallback: the session resolver returns null until proot
        // boots (it lazy-initializes its RootfsManager). Test harnesses
        // commonly want to stage files BEFORE booting, so route through the
        // rootfs dir directly when the kernel isn't ready yet.
        // [dv16-A03] Session-scoped resolution — same shape as handleLS; the
        // pre-boot rootfsDir fallback is unchanged and still catches every
        // null (unresolvable path or pre-boot), so test staging keeps working.
        val sessionId = params.optString("sessionId", "").ifBlank { null }
        val hostFile = PRootKernel.resolvePathForSession(sessionId, path, context) ?: run {
            val rootfsDir = com.rikkaminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir
            File(rootfsDir, path.removePrefix("/"))
        }

        if (hostFile.exists() && !overwrite) {
            throw RPCException(-32000, "File exists and overwrite=false: $path")
        }

        val bytes = when (encoding) {
            "utf8" -> content.toByteArray(Charsets.UTF_8)
            "base64" -> try {
                android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw RPCException(-32602, "Invalid base64 content: ${e.message}")
            }
            else -> throw RPCException(-32602, "Invalid encoding: '$encoding' (must be utf8 or base64)")
        }

        hostFile.parentFile?.mkdirs()
        hostFile.writeBytes(bytes)

        // Best-effort mode application — on most Android filesystems we can
        // only set readable/writable/executable via java.io.File flags.
        runCatching {
            val modeOct = if (modeStr.startsWith("0")) modeStr.toInt(8) else modeStr.toInt(10)
            val ownerExec = (modeOct and 0b001_000_000) != 0
            val ownerWrite = (modeOct and 0b010_000_000) != 0
            val ownerRead = (modeOct and 0b100_000_000) != 0
            hostFile.setReadable(ownerRead, true)
            hostFile.setWritable(ownerWrite, true)
            hostFile.setExecutable(ownerExec, true)
        }

        return JSONObject().apply {
            put("ok", true)
            put("path", path)
            put("hostPath", hostFile.absolutePath)
            put("size", bytes.size)
        }
    }

    private suspend fun handleScreenshotCapture(params: JSONObject): JSONObject {
        val label = params.optString("label", "")
        val scale = params.optDouble("scale", 0.5).toFloat().coerceIn(0.05f, 1.0f)
        val activity = currentActivity?.get()
            ?: throw RPCException(-32000, "No active Activity")
        val entry = DebugScreenshotRing.capture(activity, label, scale)
        return DebugScreenshotRing.summary(entry)
    }

    private fun handleScreenshotGet(params: JSONObject): JSONObject {
        val id = if (params.has("id") && !params.isNull("id")) params.optInt("id") else null
        val entry = DebugScreenshotRing.get(id)
            ?: throw RPCException(-32602, "Screenshot not found${if (id != null) " for id=$id" else " (buffer empty)"}")
        return JSONObject().apply {
            put("id", entry.id)
            put("label", entry.label)
            put("timestamp", entry.timestamp)
            put("base64", android.util.Base64.encodeToString(entry.pngBytes, android.util.Base64.NO_WRAP))
            put("size", entry.pngBytes.size)
            put("encoding", "png")
        }
    }

    /**
     * Dump the live OffloadPermissionManager registry — one entry per tool with
     * the resolved level (default + persisted overrides applied). Used to
     * verify T34/T35 alignment without driving the Settings UI.
     */
    private fun handlePermissionsList(): JSONObject {
        val arr = org.json.JSONArray()
        for (tool in com.rikkaminis.app.offload.OffloadPermissionManager.toolRegistry) {
            arr.put(JSONObject()
                .put("toolName", tool.toolName)
                .put("displayName", tool.displayName)
                .put("category", tool.category.name)
                .put("defaultLevel", tool.defaultLevel.name)
                .put("currentLevel", com.rikkaminis.app.offload.OffloadPermissionManager.getLevel(tool.toolName).name)
                .put("showInSettings", tool.showInSettings))
        }
        return JSONObject().put("tools", arr).put("count", arr.length())
    }

    /**
     * T344: Direct invocation of [com.rikkaminis.app.sandbox.offload.ShizukuOffloadHandler]
     * for e2e harnesses. Bypasses the agent loop so debug clients can verify Shizuku
     * CLI behavior without driving a chat. DEBUG-build only — gated in [dispatch].
     *
     * Params (one of):
     *   - {"args": ["exec", "id"]}            — argv past `android-shizuku-cli`
     *   - {"command": "exec id"}              — single string, whitespace-split
     *
     * [dv18-A05] Deliberately session-less: this drives a device-level
     * privileged shell where the guest cwd has no meaning, so it keeps
     * `cwd = "/"` / `sessionId = null` even though [handleModelUseExec] and
     * [handleSessionsExec] take the caller's scope. Revisit if a caller ever
     * needs the privileged shell scoped to a chat session.
     */
    private fun handleShizukuExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }
        AppLogger.info("DebugRPC", "debug.shizuku.exec argv=${argvTail.joinToString(" ")}")
        val handler = com.rikkaminis.app.sandbox.offload.ShizukuOffloadHandler(context)
        // ShizukuOffloadHandler.handle() drops argv[0]; prepend the CLI name so the
        // tail aligns with what `android-shizuku-cli` would receive in-shell.
        val request = com.rikkaminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("android-shizuku-cli") + argvTail,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    /**
     * Direct invocation of [com.rikkaminis.app.sandbox.offload.ModelUseOffloadHandler]
     * for e2e harnesses. Mirrors [handleShizukuExec]; lets callers exercise the
     * `minis-model-use` CLI without going through a real Alpine shell prompt.
     * DEBUG-only.
     */
    private fun handleModelUseExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }

        // [dv18-A05] The caller's session scope. `minis-debug model-use` runs
        // through the offload handler, which forwards the shell's cwd and chat
        // session id; an external JSON-RPC client may send them directly.
        // Absent ⇒ the pre-fix behaviour (no session, guest root as cwd), so a
        // relative `--input notes.txt` used to be resolved against "/".
        val scope = execScopeOf(params.optString("sessionId", ""), params.optString("cwd", ""))

        // Optional `input` blob: write to host /tmp and inject `--input <linuxPath>`
        // so the handler reads it via readLinuxPath() exactly like a real shell
        // invocation would. We resolve the host path via PRootKernel to honour
        // the same rootfs layout the offload server uses.
        var inputHostFile: java.io.File? = null
        val finalArgv: List<String> = if (params.has("input")) {
            val inputBlob = params.optString("input", "")
            val linuxPath = "/tmp/.debug-modeluse-input-${System.currentTimeMillis()}.json"
            val hostFile = com.rikkaminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
                ?: throw RPCException(-32603, "cannot resolve $linuxPath under rootfs")
            hostFile.parentFile?.mkdirs()
            hostFile.writeText(inputBlob)
            // T9-L4: keep a handle so the finally below can delete the blob —
            // this used to leave one JSON file per debug.modelUse.exec call in
            // the rootfs /tmp for the lifetime of the install.
            inputHostFile = hostFile
            argvTail + listOf("--input", linuxPath)
        } else argvTail

        try {
            AppLogger.info("DebugRPC", "debug.modelUse.exec argv=${finalArgv.joinToString(" ")}")
            val app = context.applicationContext as com.rikkaminis.app.MinisApp
            val handler = com.rikkaminis.app.sandbox.offload.ModelUseOffloadHandler(context, app.providerRepository)
            val request = com.rikkaminis.app.sandbox.NativeOffloadRequest(
                pid = -1,
                argv = listOf("minis-model-use") + finalArgv,
                env = emptyMap(),
                cwd = scope.cwd,
                sessionId = scope.sessionId,
            )
            val result = handler.handle(request)
            return JSONObject().apply {
                put("exitCode", result.exitCode)
                put("output", result.output)
                put("argv", JSONArray(finalArgv))
            }
        } finally {
            inputHostFile?.let { runCatching { it.delete() } }
        }
    }

    /**
     * [T-android-sessions-cli-full] Direct invocation of
     * [com.rikkaminis.app.sandbox.offload.SessionsOffloadHandler] for e2e
     * harnesses. Mirrors [handleModelUseExec]; lets callers exercise the
     * `minis-sessions-cli` CLI (list / search / messages, incl. --full)
     * without going through a real Alpine shell prompt. DEBUG-only.
     */
    private fun handleSessionsExec(params: JSONObject): JSONObject {
        val argvTail: List<String> = when {
            params.has("args") -> {
                val arr = params.optJSONArray("args")
                    ?: throw RPCException(-32602, "args must be an array of strings")
                List(arr.length()) { arr.optString(it) }
            }
            params.has("command") -> {
                params.optString("command").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            }
            else -> throw RPCException(-32602, "Missing 'args' (array) or 'command' (string)")
        }

        // [dv18-A05] Same session scope contract as [handleModelUseExec]: the
        // sessions CLI is session-aware (it lists/searches/reads chat
        // transcripts), so a caller that names a session must reach the
        // handler instead of the historical session-less "global" arm.
        val scope = execScopeOf(params.optString("sessionId", ""), params.optString("cwd", ""))

        AppLogger.info("DebugRPC", "debug.sessions.exec argv=${argvTail.joinToString(" ")}")
        val app = context.applicationContext as com.rikkaminis.app.MinisApp
        val handler = com.rikkaminis.app.sandbox.offload.SessionsOffloadHandler(app.chatRepository, context)
        val request = com.rikkaminis.app.sandbox.NativeOffloadRequest(
            pid = -1,
            argv = listOf("minis-sessions-cli") + argvTail,
            env = emptyMap(),
            cwd = scope.cwd,
            sessionId = scope.sessionId,
        )
        val result = handler.handle(request)
        return JSONObject().apply {
            put("exitCode", result.exitCode)
            put("output", result.output)
            put("argv", JSONArray(argvTail))
        }
    }

    /**
     * [T-minis-config-provider-add] DEBUG-only minis-config invocation
     * that BYPASSES the user-confirmation gate. Targets the same code
     * path the offload CLI hits (ConfigBridge.performWriteBatch /
     * readField / auditList), so harnesses can verify add / set / get
     * end-to-end without driving the in-app confirm dialog.
     *
     * Params:
     *   - `subcommand` (required): "set" | "get"
     *   - subcommand=set:    `path` (string) + `value_json` (string)
     *   - subcommand=get:    `path` (string)
     */
    private fun handleMinisConfigExec(params: JSONObject): JSONObject {
        val sub = params.optString("subcommand", "").takeIf { it.isNotEmpty() }
            ?: throw RPCException(-32602, "Missing 'subcommand' — one of: set, get")

        return when (sub) {
            "set" -> {
                val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'path' for subcommand=set")
                val valueJson = params.optString("value_json", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'value_json' for subcommand=set")
                val items = JSONArray().put(JSONObject().apply {
                    put("path", path)
                    put("value_json", valueJson)
                })
                AppLogger.info("DebugRPC", "debug.minisConfig.exec set path=$path")
                // Hop to the main thread because performWriteBatch is a
                // suspend fun that uses Dispatchers.Main internally.
                kotlinx.coroutines.runBlocking {
                    com.rikkaminis.app.config.ConfigBridge.performWriteBatch(
                        items = items,
                        caption = "debug.minisConfig.exec",
                        actorRaw = "debug-rpc",
                        sessionId = null,
                        skipConfirmation = true,
                    )
                }
            }
            "get" -> {
                val path = params.optString("path", "").takeIf { it.isNotEmpty() }
                    ?: throw RPCException(-32602, "Missing 'path' for subcommand=get")
                AppLogger.info("DebugRPC", "debug.minisConfig.exec get path=$path")
                com.rikkaminis.app.config.ConfigBridge.readField(
                    path = path,
                    filter = null,
                    page = 0,
                    pageSize = 0,
                )
            }
            else -> throw RPCException(-32602, "Unknown subcommand '$sub'")
        }
    }
}

internal class RPCException(val code: Int, override val message: String) : Exception(message)

/**
 * [da1028-A] Bounded segment read for `debug.readFile`, API-26 safe.
 *
 * The inline shape this replaces (`inputStream().use { it.skip(offset);
 * it.readNBytes(limit) }`) had two defects:
 *   1. `java.io.InputStream.readNBytes(int)` is API 33+ while minSdk is 26 and
 *      desugaring is off — on Android 8-12 the call threw `NoSuchMethodError`,
 *      which is an `Error`, not an `Exception` (the `catch (e: Exception)`
 *      wrappers around RPC handling do not cover it, so the debug client saw a
 *      dead socket instead of an error reply).
 *   2. `skip()` may skip FEWER bytes than requested and its return value was
 *      discarded: the read then started past (or short of) `offset` and
 *      returned plausible-looking wrong bytes, with no way for the client to
 *      notice.
 *
 * Shape mirrors [com.rikkaminis.app.logging.AppLogger.readLogSegment] (same
 * offset/limit contract, already reviewed): clamp the window against the file
 * size, then read exactly that many bytes with `RandomAccessFile`. EOF is not
 * an error — the caller returns `bytesRead`/`truncated` from what it actually
 * got — so a short read yields fewer bytes (no zero padding) and an offset at
 * or past EOF yields an empty array.
 */
internal fun readFileSegment(file: File, offset: Long, limit: Int): ByteArray {
    val size = file.length()
    val start = offset.coerceIn(0L, size)
    val end = (start + limit.coerceAtLeast(0).toLong()).coerceAtMost(size)
    if (end <= start) return ByteArray(0)
    val out = ByteArray((end - start).toInt())
    java.io.RandomAccessFile(file, "r").use { raf ->
        raf.seek(start)
        var read = 0
        while (read < out.size) {
            val n = raf.read(out, read, out.size - read)
            if (n < 0) break
            read += n
        }
        return if (read == out.size) out else out.copyOf(read)
    }
}

/**
 * [R4-R F-10] Length of the longest prefix of [bytes] that ends on a UTF-8
 * character boundary — `bytes.size` when the slice already does, the position
 * of the trailing incomplete sequence's lead byte when it does not.
 *
 * Page reads are byte windows, so the LAST character of a sliced page can be
 * cut in half. Walking back over at most three continuation bytes (a valid
 * UTF-8 sequence has at most three) finds the lead byte; the sequence's own
 * length decides whether the whole character made it into the slice. An
 * invalid lead byte is not a boundary problem — the strict decode in
 * [looksLikeUtf8Text] is what classifies those as binary.
 */
internal fun utf8CompletePrefixLength(bytes: ByteArray): Int {
    var i = bytes.size - 1
    var seen = 0
    while (i >= 0 && (bytes[i].toInt() and 0xC0) == 0x80 && seen < 3) {
        i--
        seen++
    }
    if (i < 0) return bytes.size
    val lead = bytes[i].toInt() and 0xFF
    val needed = when {
        lead and 0x80 == 0x00 -> 1
        lead and 0xE0 == 0xC0 -> 2
        lead and 0xF0 == 0xE0 -> 3
        lead and 0xF8 == 0xF0 -> 4
        else -> 1
    }
    return if (bytes.size - i >= needed) bytes.size else i
}

/**
 * [R4-R F-10] Strict UTF-8 text classification for a sliced page.
 *
 * The old whitelist (`0x09..0x0D || 0x20..0x7E || > 0x7F`) accepted EVERY high
 * byte as text — so a binary file decoded into U+FFFD soup — while refusing a
 * plain ANSI-coloured terminal transcript because ESC (0x1B) was outside the
 * allowlist. This is the same decision the text branch makes, made decodable:
 * no NUL bytes (the whitelist's own "no null bytes" intent) and a decode with
 * `CodingErrorAction.REPORT` that answers `false` on any malformed sequence.
 * An empty slice is text, exactly as `bytes.all { … }` answered before.
 *
 * The decode starts at the first character boundary: [utf8CompletePrefixLength]
 * only guarantees the page does not END mid-character, while a caller-supplied
 * `offset` can land inside one — a leading continuation byte is a fragment of
 * a character the caller asked to skip past, not evidence of binary content.
 * [decodeUtf8Page] drops that same fragment from `content` (never from
 * `bytesRead`), so the bytes this check approved are exactly the bytes the
 * caller sees.
 */
internal fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
    if (bytes.any { it == 0.toByte() }) return false
    // [R5-F F-F-08] Skip leading continuation bytes (up to 3 — the tail of a
    // 4-byte sequence): the window may open mid-character, and the decoder
    // must not even see the orphan tail. ONE rule shared with [decodeUtf8Page].
    val head = utf8SkipLeadingContinuation(bytes)
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching {
        decoder.decode(ByteBuffer.wrap(bytes, head, bytes.size - head))
    }.isSuccess
}

/**
 * [R5-F F-F-08] Decode one `debug.readFile` page: skip the orphan tail of a
 * character the window opened inside, then decode. ONE definition for both the
 * content field and (via [looksLikeUtf8Text]) the validity check, so the bytes
 * the check approved are exactly the bytes that get decoded.
 *
 * Guarantees, in the terms the RPC contract speaks:
 *  * the result never contains U+FFFD for a window that starts mid-character;
 *  * a caller that walks the file page by page, advancing by `bytesRead`,
 *    reconstructs the file exactly (its previous page already trimmed the tail,
 *    so `skip == 0` on every aligned call);
 *  * an unaligned `offset` costs only the ≤3 bytes of the fragment it opened
 *    inside — they are dropped, never rendered as replacement characters.
 */
internal fun decodeUtf8Page(bytes: ByteArray): String {
    val skip = utf8SkipLeadingContinuation(bytes)
    return String(bytes, skip, bytes.size - skip, Charsets.UTF_8)
}

/**
 * [R5-F F-F-08] How many bytes at the front of [bytes] are continuation bytes
 * (`0x80..0xBF`, at most 3 — the tail of one 4-byte sequence).
 *
 * One rule, two consumers: [looksLikeUtf8Text] needs it so the validity check
 * does not reject a page that merely starts mid-character, and `debug.readFile`
 * needs it so `String(bytes, UTF_8)` does not answer U+FFFD for that same
 * orphan tail. They used to be one inline loop in the first and nothing in the
 * second — the decoder was told "starts at 0" after the check had looked at
 * `head`, so a page cut inside a Chinese/emoji character decoded its tail into
 * `content` as one replacement character per page boundary.
 *
 * Caller contract: an aligned caller (one that advances by the returned
 * `bytesRead`) never trips this — the tail trim of the previous page already
 * left it on a lead byte. Only a hand-picked unaligned `offset` pays, and what
 * it pays is the fragment it asked for: the `<= 3` bytes are DROPPED from
 * `content` (bytesRead still counts them, so the client cannot loop on the
 * same offset), never decoded into U+FFFD.
 */
internal fun utf8SkipLeadingContinuation(bytes: ByteArray): Int {
    var head = 0
    while (head < bytes.size && head < 3 && (bytes[head].toInt() and 0xC0) == 0x80) head++
    return head
}


/**
 * [dv18-A05] The session scope a `debug.*.exec` entry point runs its offload
 * handler under.
 *
 * [sessionId] is null for every caller that does not name a chat session — the
 * historical behaviour (interactive terminals, and clients predating this key)
 * where the handler's global resolver runs. [cwd] is the guest working
 * directory a relative operand is joined onto (see
 * [com.rikkaminis.app.sandbox.offload.resolveMaybeRelative]) and defaults to
 * the guest root, which is what the exec entry points hardcoded before this
 * fix — `debug.modelUse.exec` with `--input notes.txt` therefore resolved a
 * path like `notes.txt` against "/" instead of the caller's directory.
 */
internal data class ExecScope(val sessionId: String?, val cwd: String) {
    companion object {
        /** The guest root — the pre-fix, session-less default. */
        const val DEFAULT_CWD = "/"
    }
}

/**
 * [dv18-A05] Pure params → [ExecScope] mapping, shared by every exec entry
 * point that builds an offload request. Absent/blank values fall back to the
 * pre-fix behaviour instead of being invented: a blank session id stays null
 * (the handler's global arm), a blank cwd keeps [ExecScope.DEFAULT_CWD].
 *
 * The cwd itself is passed through verbatim: the single relative-path join
 * ([com.rikkaminis.app.sandbox.offload.resolveMaybeRelative]) is the one place
 * that decides whether a cwd is usable — it only joins onto an absolute one —
 * so a second validity rule here would be a second source of truth.
 */
internal fun execScopeOf(sessionIdRaw: String?, cwdRaw: String?): ExecScope = ExecScope(
    sessionId = sessionIdRaw?.trim()?.takeIf { it.isNotEmpty() },
    cwd = cwdRaw?.trim()?.takeIf { it.isNotEmpty() } ?: ExecScope.DEFAULT_CWD,
)
