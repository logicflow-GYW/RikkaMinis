package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * [feat/verification-stop] Turn-end verification guard for coding edits —
 * Hermes verification_stop port (Tier-2 item #3).
 *
 * Policy-only: nothing here RUNS a check; it turns the run's passive tool
 * evidence into a bounded follow-up nudge when the model tries to finish
 * right after editing code without fresh verification evidence.
 *
 * The RikkaMinis adaptation is leaner than the Hermes original: instead of
 * a SQLite evidence ledger, the run already carries everything needed —
 *   - edit events:  file_write / file_edit tool calls that SUCCEEDED
 *     (visible in the engine's tool-call stream, args carry the path)
 *   - verification evidence: shell_execute results whose command matches a
 *     verification shape (test/lint/typecheck/build/ad-hoc verify script)
 *     and exited 0 AFTER the last edit
 *
 * Pure classification functions — JVM-testable, no Android deps.
 */
object VerificationStopPolicy {

    /** File extensions whose edits have no verifiable runtime behavior —
     *  a turn touching ONLY prose suppresses the nudge entirely (a SKILL.md
     *  or README edit must never demand a verification script). Mirrors
     *  Hermes _NON_CODE_VERIFY_EXTENSIONS / _NON_CODE_VERIFY_FILENAMES. */
    private val NON_CODE_EXTENSIONS = setOf(
        "md", "markdown", "mdx", "rst", "txt", "text", "adoc", "asciidoc",
        "org", "log", "csv", "tsv", "json", "yaml", "yml", "toml", "ini",
        "xml", "html", "css",
    )
    private val NON_CODE_BASENAMES = setOf(
        "license", "licence", "notice", "authors", "contributors",
        "changelog", "codeowners", "dockerfile", "makefile",
    )

    /** Ordered keyword groups — first match wins (mirrors Hermes
     *  _KIND_KEYWORDS): lint > typecheck > build > format > test. */
    private val KIND_KEYWORDS = listOf(
        "lint" to listOf("lint", "eslint", "ruff", "detekt", "ktlint"),
        "typecheck" to listOf("typecheck", "tsc", "mypy", "pyright"),
        "build" to listOf("build", "compile", "kotlinc", "gradlew"),
        "format" to listOf("fmt", "format"),
    )
    private val TEST_KEYWORDS = listOf(
        "test", "pytest", "junit", "spec", "verify", "check",
    )

    /** Interpreters that make a temp script runnable verification evidence. */
    private val INTERPRETERS = setOf("python", "python3", "node", "bash", "sh", "ruby", "perl")

    /** Max attempts the engine will nudge — beyond this the model has been
     *  told twice and insisting would loop. Hermes default = 2. */
    const val MAX_VERIFY_NUDGES = 2

    /** Max changed paths listed in the nudge text. */
    private const val MAX_PATHS_IN_NUDGE = 8

    // ── edit-side classification ───────────────────────────────────────────

    /** True when the path is documentation/prose/data with nothing to
     *  verify at runtime. Extension-less well-known filenames included. */
    fun isNonCodePath(rawPath: String): Boolean {
        val p = rawPath.trim().lowercase()
        if (p.isEmpty()) return false
        val name = p.substringAfterLast('/')
        val ext = p.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext !in NON_CODE_EXTENSIONS) return false
        // extension matches a code extension → NOT non-code
        if (ext in NON_CODE_EXTENSIONS) return true
        // no recognizable extension: well-known prose basenames only
        return name in NON_CODE_BASENAMES
    }

    /** Extract the changed path from a file tool call's args JSON. */
    fun changedPathFromArgs(toolName: String, argsJson: String): String? {
        if (toolName != "file_write" && toolName != "file_edit") return null
        return try {
            JSONObject(argsJson).optString("path", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // ── verification-side classification ───────────────────────────────────

    /** Classify a shell command as verification-shaped (Hermes
     *  classify_verification_command, command-shape only — the engine owns
     *  ordering vs edits and the exit status).
     *
     *  Returns a kind string ("test" / "lint" / "typecheck" / "build" /
     *  "format" / "ad_hoc"), or null when the command is not verification
     *  evidence at all. */
    fun verificationKind(command: String): String? {
        val lowered = command.lowercase()
        if (lowered.isBlank()) return null
        // strip leading env/VAR=x prefixes and common wrappers
        val tokens = lowered.split(Regex("\\s+"))
        var i = 0
        if (i < tokens.size && tokens[i] == "env") i++
        while (i < tokens.size && tokens[i].contains('=') && !tokens[i].startsWith("-")) i++
        while (i < tokens.size && tokens[i] in setOf("command", "time", "noglob")) i++
        val effectiveTokens = tokens.drop(i)
        if (effectiveTokens.isEmpty()) return null
        val effective = effectiveTokens.joinToString(" ")

        for ((kind, keywords) in KIND_KEYWORDS) {
            if (keywords.any { kw -> effectiveTokens.any { it == kw || it.startsWith("$kw ") || it.startsWith("$kw.") || it.startsWith("./$kw") || it.endsWith("/$kw") } }) return kind
        }
        // ad-hoc temp script: interpreter + /tmp/...verify-* path (checked
        // BEFORE the interpreter short-circuit: python3 /tmp/x.py is evidence
        // only when the script looks like an ad-hoc verify script)
        val first = effectiveTokens.first()
        if (first in INTERPRETERS) {
            val script = effectiveTokens.getOrNull(1)
            if (script != null && isAdHocVerifyScript(script)) return "ad_hoc"
            // interpreter running a NORMAL script (python3 -m pytest, node
            // server.js): fall through to keyword classification below.
        } else if (isAdHocVerifyScript(first)) {
            return "ad_hoc"
        }
        // test-family keywords: match on executable tokens only (first few
        // tokens + any token that is itself a keyword), NOT on arbitrary
        // path substrings — `cat /tmp/verify-notes.md` must not classify.
        // FQCN tokens (org.junit.runner.JUnitCore) count: running a test
        // runner class by name is evidence even at position > 3.
        fun tokenIsTestShaped(t: String): Boolean = TEST_KEYWORDS.any { kw ->
            t == kw || t.startsWith("$kw:") || t.removeSuffix(".exe") == kw ||
                t.substringAfterLast('/') == kw ||
                (t.startsWith(kw) && t.length <= kw.length + 3) ||
                // dotted segments: any dot-separated chunk that IS a keyword —
                // org.junit.runner.JUnitCore → chunks [org, junit, runner, junitcore]
                t.split('.').any { seg -> seg == kw || (seg.startsWith(kw) && seg.length <= kw.length + 6) }
        }
        if (effectiveTokens.take(3).any { tokenIsTestShaped(it) } ||
            effectiveTokens.drop(3).any { it.contains('.') && tokenIsTestShaped(it) }
        ) return "test"
        return null
    }

    /** An ad-hoc verification script: under /tmp, prefixed name, outside
     *  the minis trees (workspace files are payload, not verification). */
    private fun isAdHocVerifyScript(token: String): Boolean {
        if (!token.startsWith("/tmp/")) return false
        val name = token.substringAfterLast('/')
        return name.startsWith("verify-") || name.startsWith("minis-verify-") ||
            name.startsWith("hermes-verify-")
    }

    // ── the nudge ──────────────────────────────────────────────────────────

    /** Build the synthetic follow-up reminder. Null when the guard should
     *  not fire (no code edits, all prose, or attempts exhausted). */
    fun buildNudge(
        changedPaths: List<String>,
        attempts: Int,
        lastEvidenceDetail: String?,
    ): String? {
        val codePaths = changedPaths.filter { !isNonCodePath(it) }.distinct().sorted()
        if (codePaths.isEmpty()) return null
        if (attempts >= MAX_VERIFY_NUDGES) return null

        val pathsList = codePaths.take(MAX_PATHS_IN_NUDGE).joinToString("\n") { "- $it" } +
            (if (codePaths.size > MAX_PATHS_IN_NUDGE) "\n- ... and ${codePaths.size - MAX_PATHS_IN_NUDGE} more" else "")

        val evidenceLine = lastEvidenceDetail?.let { "\nLast verification attempt: $it\n" } ?: ""

        return "<system-reminder>You edited code files in this turn, but this run does not " +
            "yet have fresh PASSING verification evidence for those changes.\n" +
            "$evidenceLine" +
            "Changed paths:\n$pathsList\n\n" +
            "Before finishing: run the relevant verification now (a test, a compile, a " +
            "focused script under /tmp — whatever proves the changed behavior), read any " +
            "failure output, repair the code if needed, and summarize what actually passed. " +
            "If verification is genuinely impossible here, state the concrete blocker " +
            "explicitly instead of claiming the work is verified.</system-reminder>"
    }
}
