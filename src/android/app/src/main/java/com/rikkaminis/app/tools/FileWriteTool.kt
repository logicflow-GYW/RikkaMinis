package com.rikkaminis.app.tools

import android.content.Context
import com.rikkaminis.app.data.OffloadedPayloadGuard
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import com.rikkaminis.app.sandbox.PRootKernel
import org.json.JSONObject

object FileWriteTool {
    const val NAME = "file_write"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Write content to a file on the Linux filesystem. Faster than shell_execute for writing files. Creates the file if it doesn't exist. Use append mode to add to existing files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Create Python statistics script', 'Write configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to write (e.g. /root/test.txt)"),
            "content" to AgentToolParam("string", "The text content to write to the file"),
            "append" to AgentToolParam("boolean", "If true, append to existing file instead of overwriting (default: false)"),
            "create_dirs" to AgentToolParam("boolean", "If true, create parent directories if they don't exist (default: false)"),
        ),
        required = listOf("tool_title", "path", "content"),
        propertyOrdering = listOf("tool_title", "path", "content", "append", "create_dirs"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        // [fix/audit-0917-b9] Resolve the title BEFORE the try: it used to be
        // a try-scoped val, so the catch arm had no title to report (the
        // compiler rejected the reference outright — CI 35198111318). A
        // malformed argsJson falls back to NAME, which is what optString's
        // default did anyway.
        val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", NAME) }
            .getOrDefault(NAME)
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val content = args.optString("content", "")
            val append = args.optBoolean("append", false)
            val createDirs = args.optBoolean("create_dirs", false)

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // [fix/offload-payload-stub] Never write a disk-offload *stub* over
            // a real file. A stub is a pointer ("…saved to: <path>"), and the
            // model can end up sending one as `content` after a context offload
            // replaced its own earlier payload. Observed damage: a 3714-byte
            // file truncated to the ~150-byte stub while this tool reported
            // success. Recover the real bytes when the stub still resolves and
            // the call is an overwrite; otherwise refuse loudly.
            // See data/OffloadedPayloadGuard.kt and ui/chat.isOffloadEligible.
            val payload = OffloadedPayloadGuard.decide(content, append) { offloadPath ->
                runCatching {
                    PRootKernel.resolveSessionHostPath(sessionId, offloadPath, context)
                        ?.takeIf { it.isFile }
                        ?.readText()
                }.getOrNull()
            }
            if (payload is OffloadedPayloadGuard.Action.Refuse) {
                return ToolExecutionResult(
                    OffloadedPayloadGuard.refusalMessage(path, payload),
                    false,
                    toolTitle = toolTitle,
                )
            }
            val effectiveContent =
                (payload as? OffloadedPayloadGuard.Action.Heal)?.content ?: content
            val healedFrom = (payload as? OffloadedPayloadGuard.Action.Heal)?.from

            // T219: read-only mount guard. Reject before opening so we don't
            // half-create files inside a Locked external mount and surface a
            // friendly hint pointing the user at Settings. Mirrors iOS
            // MountedFolderCoordinator.isLinuxPathUnderReadOnlyMount used by
            // AIChatViewModel.fileWrite (AIChatViewModel.swift:8333-8341).
            if (PRootKernel.isLinuxPathUnderReadOnlyMount(path)) {
                return ToolExecutionResult(
                    "Error: $path is inside a read-only mounted folder and cannot be modified. " +
                        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
                    false, toolTitle = toolTitle,
                )
            }

            // T123: per-session resolver so /var/minis/workspace/...,
            // /var/minis/attachments/..., /var/minis/offloads/...,
            // /var/minis/browser/... land in this session's host dir
            // rather than the global bind-mount map (which is overwritten
            // every time another session boots its shell, last-writer-wins).
            val file = PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            // [fix/audit-b18 / T8-L2] This used to be a try/catch around
            // content.toByteArray(UTF_8), which never throws — the JVM encoder
            // substitutes unpaired surrogates instead of failing, so the guard
            // was unreachable and gave a false sense that the write was
            // validated. Use an encoder configured to REPORT malformed input,
            // which does reject unpaired surrogates.
            try {
                Charsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(effectiveContent))
            } catch (e: java.nio.charset.CharacterCodingException) {
                return ToolExecutionResult(
                    "Error: Content contains unpaired surrogates and cannot be encoded as UTF-8",
                    false,
                    toolTitle = toolTitle,
                )
            }

            // T123: mirror iOS AIChatViewModel L8339 — auto-create the
            // parent dir whenever it doesn't exist, regardless of the
            // create_dirs flag. Per-session subdirs (workspace, etc.) are
            // materialized lazily, so a fresh session writing into
            // /var/minis/workspace/foo/bar.md would otherwise hit "Parent
            // directory does not exist" on the very first call.
            val parent = file.parentFile
            if (parent != null && (createDirs || !parent.exists())) {
                parent.mkdirs()
            }

            if (append) {
                file.appendText(effectiveContent)
            } else {
                file.writeText(effectiveContent)
            }

            val bytes = file.length()
            // Diagnose "write reported success but nothing on disk" (Android 10
            // legacy-storage FUSE shadow writes): confirm the file is actually
            // there with the expected size right after writing. A mounted-folder
            // write that silently no-ops shows exists=false / size mismatch here.
            if (path.startsWith("/var/minis/mounts/")) {
                val landed = file.exists() && file.length() == bytes
                com.rikkaminis.app.logging.AppLogger.info(
                    "FileWrite",
                    "mount write path=$path host=${file.absolutePath} bytes=$bytes " +
                        "exists=${file.exists()} landedOk=$landed",
                )
                if (!landed) {
                    com.rikkaminis.app.logging.AppLogger.warning(
                        "FileWrite",
                        "mount write to $path reported success but did NOT persist to " +
                            "${file.absolutePath} — likely missing WRITE_EXTERNAL_STORAGE / " +
                            "shadowed FUSE view on this device",
                    )
                }
            }
            val healedNote = healedFrom?.let {
                // Loud on purpose: the payload we were handed was a stub, and the
                // bytes actually written came from the offload file. The model
                // must learn that its own last write payload was not what it
                // thought it was — silently healing would just move the lie.
                com.rikkaminis.app.logging.AppLogger.warning(
                    "FileWrite",
                    "[fix/offload-payload-stub] 'content' was an offload stub; " +
                        "recovered ${effectiveContent.length} chars from $it and wrote $path",
                )
                " — NOTE: the 'content' argument you sent was a [CONTEXT OFFLOADED] stub, not real " +
                    "content. The bytes written were recovered from $it. If that is not what you " +
                    "intended, re-issue the call with the real content."
            } ?: ""
            ToolExecutionResult("Wrote to $path ($bytes bytes)$healedNote", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            // [fix/audit-0917-b9] toolTitle — every other return path (L37,
            // L59, L75, L85, L102, L158) passes it, so an exception after
            // parsing lost the tool title and the offload summary / UI showed
            // a bare failure row.
            ToolExecutionResult("Error writing file: ${e.message}", false, toolTitle = toolTitle)
        }
    }
}
