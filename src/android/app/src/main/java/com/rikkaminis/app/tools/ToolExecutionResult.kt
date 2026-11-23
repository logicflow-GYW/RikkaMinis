package com.rikkaminis.app.tools

data class ToolExecutionResult(
    val output: String,
    val success: Boolean,
    val imageData: ByteArray? = null,
    val imageMimeType: String? = null,
    val toolTitle: String = "",
    /** Page URL at time of browser action (for display in preview). */
    val pageURL: String? = null,
    /** Local file path to screenshot JPEG (for thumbnail/detail view). */
    val imageFilePath: String? = null,
    /**
     * iSH-visible linux path the image bytes were persisted to (e.g.
     * `/var/minis/browser/<sid>/screenshot_<ts>.jpg`,
     * `/var/minis/attachments/generated/...`). Used by request-level image
     * budgeting to emit a re-fetchable text placeholder instead of the
     * bytes when the cumulative payload would exceed the per-request cap.
     * Distinct from [imageFilePath] which is a host-OS absolute path that
     * agents cannot directly address.
     */
    val imageLinuxPath: String? = null,
    /**
     * True when the tool did not return within its per-tool-category timeout.
     * Distinct from generic `!success` so the UI can render a TIMEOUT status
     * (clock icon) instead of a FAILED status (error icon). Mirrors a failure
     * subclassification iOS handles inline via error-message parsing.
     */
    val timedOut: Boolean = false,
    /**
     * [deep-audit-1018 ISSUE-03] Mirrors
     * [com.rikkaminis.app.sandbox.ExecutionCoordinator.CommandResult.outcomeKnown]:
     * false = "the command may have run but its result is not trustworthy"
     * (shell replay withheld / outcome unknown). Consumed by the T7 trace layer
     * (`AgentRunEvent.ToolFinished.resultKnown`) so the run reducer can classify
     * SUCCEED_WITH_UNKNOWN_TOOL instead of SUCCEEDED. Defaults to true: every
     * tool family except shell is outcome-known by construction.
     */
    val outcomeKnown: Boolean = true,
    /** Execution-local fact: a model-facing projection changed real tool bytes. Not persisted/IPC. */
    val privacyRedacted: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolExecutionResult) return false
        return output == other.output && success == other.success &&
            imageData.contentEquals(other.imageData) &&
            imageMimeType == other.imageMimeType && toolTitle == other.toolTitle &&
            pageURL == other.pageURL && imageFilePath == other.imageFilePath &&
            imageLinuxPath == other.imageLinuxPath &&
            timedOut == other.timedOut && outcomeKnown == other.outcomeKnown &&
            privacyRedacted == other.privacyRedacted
    }

    override fun hashCode(): Int {
        var result = output.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (imageMimeType?.hashCode() ?: 0)
        result = 31 * result + toolTitle.hashCode()
        result = 31 * result + (pageURL?.hashCode() ?: 0)
        result = 31 * result + (imageFilePath?.hashCode() ?: 0)
        result = 31 * result + (imageLinuxPath?.hashCode() ?: 0)
        result = 31 * result + timedOut.hashCode()
        result = 31 * result + outcomeKnown.hashCode()
        result = 31 * result + privacyRedacted.hashCode()
        return result
    }
}
