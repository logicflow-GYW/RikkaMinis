package com.rikkaminis.app.ui.chat

/**
 * [T-sensitive-transcript] Which shell command lines produce output that must
 * not survive in the persisted conversation.
 *
 * The android-* helpers are not tools of their own — the model reaches them
 * through `shell_execute`, so a ToolResult carries only `name =
 * "shell_execute"` plus the payload. The command line is the only place the
 * provenance exists, and it exists only while the call is in flight:
 * AgentContentPart.ToolResult has no command field and the persisted parts JSON
 * never sees one. That is why the decision is taken during execution and the
 * outcome is baked into the persisted content, rather than filtered later at
 * backup-export time.
 *
 * Scope is deliberately two helpers, not the whole personal-data family:
 * `android-clipboard` (the payload is whatever was last copied — routinely a
 * password, an OTP or a token) and `android-speech` (a transcript of what was
 * said out loud). Both are *inputs* rather than answers: nobody re-reads a
 * clipboard dump or a dictation in a chat log, so replacing them costs the
 * reader nothing. notification / contacts / calendar / location / photos are
 * things a user legitimately scrolls back to, so they stay intact — their
 * exposure is the backup path, which is a separate decision: it would need the
 * provenance written into the persisted parts to be filterable independently.
 *
 * Wider set, for reference: android-clipboard, android-speech,
 * android-notification, android-contacts, android-location, android-photos,
 * android-calendar. Explicitly NOT treated as sensitive: android-speak
 * (text-to-speech writes no personal data) and android-shizuku-cli (a general
 * escape hatch — redacting it would break the legitimate "show me what this
 * returns" case).
 */
internal object SensitiveCommandPolicy {

    /** Helpers whose payload is replaced before the turn is persisted. */
    private val TRANSCRIPT_REDACTED_HELPERS = setOf(
        "android-clipboard",
        "android-speech",
    )

    /**
     * Split on shell separators and quoting so `sh -c 'android-clipboard get'`
     * and `x && android-clipboard set ...` read the same as a bare call.
     * Over-splitting is harmless — every resulting token is still compared
     * against the helper names.
     */
    private val TOKEN_SEPARATORS = Regex("""[\s;|&()<>`'"]+""")

    /**
     * True when [command] invokes one of the redacted helpers.
     *
     * Token-based rather than prefix-based so an absolute path, a pipe or a
     * shell wrapper does not slip past. `echo android-clipboard` DOES match,
     * which is the conservative direction: a redacted echo costs one
     * placeholder line, a missed clipboard dump leaks a credential.
     */
    fun shouldRedactFromTranscript(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        return command.split(TOKEN_SEPARATORS)
            .any { it.substringAfterLast('/') in TRANSCRIPT_REDACTED_HELPERS }
    }

    /**
     * What replaces the payload in the persisted transcript. Names the helper
     * so a later reader — and the model, on a revived turn — can tell that
     * something was withheld on purpose rather than lost.
     */
    fun redactionPlaceholder(command: String?): String {
        val helper = command
            ?.split(TOKEN_SEPARATORS)
            ?.firstOrNull { it.substringAfterLast('/') in TRANSCRIPT_REDACTED_HELPERS }
            ?.substringAfterLast('/')
        return if (helper == null) {
            "[sensitive output withheld from the saved transcript]"
        } else {
            "[$helper output withheld from the saved transcript]"
        }
    }
}
