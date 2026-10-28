package com.rikkaminis.app.sandbox.offload

/**
 * Tiny long-option parser shared by android-* offload handlers.
 *
 * Supports:
 *   --flag               boolean flag (returned by [hasFlag])
 *   --key value          value read by [get]
 *   --key=value          value read by [get]
 *   positional args      [positional]
 *
 * Short flags (`-h`) are matched literally by [hasFlag] — unless the handler
 * declared the name in [valueShortOptions], in which case `-f x` is read as the
 * value `x` under key `f` (which `get("filter", "f")` already reads).
 *
 * Two shapes the "is the next token a value?" decision must not get wrong:
 *   - a NEGATIVE NUMBER is a value, not the next flag (`--lat -33.86`);
 *   - a declared value-taking short option consumes its value (`-f pinned`).
 *
 * The mirror-image question — "is this token a dash NUMBER or a short option?"
 * — is answered by the same principle in reverse: a token spelled like a
 * negative number is a short option ONLY when the handler declared its name
 * ([booleanFlags] / [OffloadOutput.OUTPUT_FLAGS]), so `-33.86` stays the
 * caller's value while `package list -3` (declared `"3"`) stays a switch.
 */
internal class OffloadArgs(
    argv: List<String>,
    booleanFlags: Set<String> = emptySet(),
    /**
     * Short option names (no dash) this handler reads as values: `-f x` fills
     * `values["f"]`. A name that is ALSO a boolean flag stays a flag —
     * [booleanFlags] and [OffloadOutput.OUTPUT_FLAGS] win, so `-q` keeps
     * meaning `--quiet`.
     */
    private val valueShortOptions: Set<String> = emptySet(),
) {
    val positional: List<String>
    private val flags: Set<String>
    private val values: Map<String, String>

    init {
        // T54: every android-* command supports --compact / -q / --quiet for
        // output shaping (see OffloadOutput). Always declare these as boolean
        // flags so e.g. `--compact list` parses as flag + positional rather
        // than `--compact=list` greedy consumption. Recognized at parser level
        // so handlers that haven't been wired through OffloadOutput.formatBody
        // yet still reject the flag's value as a no-op rather than as a
        // missing-positional error.
        val effectiveBooleanFlags = booleanFlags + OffloadOutput.OUTPUT_FLAGS
        val pos = mutableListOf<String>()
        val fs = mutableSetOf<String>()
        val vs = mutableMapOf<String, String>()
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            when {
                a.startsWith("--") && a.contains('=') -> {
                    val eq = a.indexOf('=')
                    vs[a.substring(2, eq)] = a.substring(eq + 1)
                }
                a.startsWith("--") -> {
                    val key = a.substring(2)
                    // A flag is treated as boolean when (a) the caller declared
                    // it as such, or (b) the next token is missing / itself a
                    // flag. Without the declared-boolean set, `--compact foo`
                    // would greedily consume `foo` as the value of `--compact`
                    // and swallow the positional.
                    // [dv15-A1 A-01] "itself a flag" is not the same as "starts
                    // with a dash": a negative number IS the value the caller
                    // spelled. `--lat -33.86` used to leave `lat` unset (the
                    // caller's `getDouble("lat")` returned null) and turn the
                    // number into a stray flag — an unconstrained query.
                    val value = valueTokenOrNull(argv.getOrNull(i + 1))
                    if (key in effectiveBooleanFlags || value == null) {
                        fs.add(key)
                    } else {
                        vs[key] = value
                        i++
                    }
                }
                // [dv16-A A-02] A negative number is the CALLER'S VALUE, not a
                // short option name: `android-photos near -33.86 151.21` used
                // to read `-33.86` as the flag `33.86`, so the latitude
                // vanished and the positionals shifted by one (the command
                // then refused with "missing argument", or — worse, when a
                // later token filled the slot — answered for ANOTHER place).
                // The exception is a name the handler DECLARED, which is the
                // only thing that makes a digit-spelled short flag legal:
                // `android-shizuku-cli package list -3` reaches
                // `hasFlag("third-party", "3")` through this same branch.
                // Deciding by the declared vocabulary (not by the token's
                // shape) is what keeps `-33.86` a value everywhere while `-3`
                // stays a flag where it is declared.
                a.startsWith("-") && a.length > 1 &&
                    (!isNegativeNumberToken(a) || a.substring(1) in effectiveBooleanFlags) -> {
                    val name = a.substring(1)
                    // [dv15-A1 A-02] A declared value-taking short option takes
                    // the next token as its value, exactly like its long form:
                    // `-f pinned` fills `values["f"]`, which the existing
                    // `get("filter", "f")` alias lookup reads. Before this, the
                    // token was split as the FLAG `f` plus a stray positional —
                    // i.e. "no filter" with no complaint. A name the handler
                    // declared boolean wins, so `-q` still means `--quiet`.
                    val value = valueTokenOrNull(argv.getOrNull(i + 1))
                    if (name in valueShortOptions && name !in effectiveBooleanFlags && value != null) {
                        vs[name] = value
                        i++
                    } else {
                        fs.add(name)
                    }
                }
                else -> pos.add(a)
            }
            i++
        }
        positional = pos
        flags = fs
        values = vs
    }

    /**
     * The token that follows an option reads as its value, or null when it is
     * itself an option. [dv15-A1 A-01]: a negative number is a VALUE — the
     * caller's own spelling of the number, not the next flag.
     */
    private fun valueTokenOrNull(token: String?): String? =
        token?.takeIf { !it.startsWith("-") || isNegativeNumberToken(it) }

    /**
     * `-` followed by a digit (`-5`, `-33.86`, `-1739773001`) — a number, never
     * an option name. Word flags (`-f`), long options (`--force`) and a bare
     * dash are untouched.
     *
     * ponytail: a negative decimal spelled without its leading zero (`-.5`) is
     * not recognized and would read as a flag. | 天花板: a caller passing a
     * bare `-.5` token as a value. | 升级触发: the first real invocation that
     * does — widen to a numeric parse then.
     */
    private fun isNegativeNumberToken(token: String): Boolean =
        token.length > 1 && token[0] == '-' && token[1].isDigit()

    fun hasFlag(vararg names: String): Boolean = names.any { it in flags }

    /**
     * Flag names present that are not in [known] — for handlers that want to
     * refuse a typo'd option instead of silently ignoring it. Read-only: the
     * output-shaping flags every command accepts are always excluded, so a
     * caller lists only its own options.
     */
    fun unknownFlags(known: Set<String>): Set<String> = flags - known - OffloadOutput.OUTPUT_FLAGS

    /**
     * Keys that were filed as `--key value` / `--key=value` and are not in
     * [known] — the mirror of [unknownFlags], and the channel a typo most
     * often lands in: `--kewords python` is NOT a flag (the next token does not
     * start with `-`, and the name is not declared boolean), so it is filed as
     * a VALUE under the misspelled key and [unknownFlags] never sees it.
     *
     * [meta1028-B05 F-12] Added for that reason; a guard reading only
     * [unknownFlags] lets the single most likely typo through.
     */
    fun unknownValueKeys(known: Set<String>): Set<String> =
        values.keys - known - OffloadOutput.OUTPUT_FLAGS
    fun get(vararg names: String): String? = names.firstNotNullOfOrNull { values[it] }
    fun getInt(vararg names: String): Int? = get(*names)?.toIntOrNull()
    fun getLong(vararg names: String): Long? = get(*names)?.toLongOrNull()
    fun getDouble(vararg names: String): Double? = get(*names)?.toDoubleOrNull()
    fun getBool(vararg names: String): Boolean? =
        get(*names)?.lowercase()?.let {
            when (it) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                // [audit-0917] Refuse instead of guess: an unrecognized value
                // returns null so the caller's ?: default applies, instead of
                // silently reading garbage as false.
                else -> null
            }
        }
}
