package com.rikkaminis.app.offload

import com.rikkaminis.app.offload.OffloadPermissionManager.PermissionLevel

/**
 * Pure resolution of a tool's effective [PermissionLevel], extracted from
 * `OffloadPermissionManager.getLevel` so the auto-approve overlays are
 * unit-testable on the JVM (no SharedPreferences, no Android).
 *
 * Precedence, in order:
 *
 *  1. **A stored level always wins.** The moment the user touches a row in
 *     Settings → Permissions, that explicit choice is what the gate uses — the
 *     auto-approve mode must never silently override it. This is what keeps
 *     "I turned that one OFF" meaningful while the mode is on.
 *  2. `ASK_ONCE` + master switch ON → `BYPASS`. This is the everyday case the
 *     mode exists for.
 *  3. `NOT_ALLOWED` + privileged tool + master switch ON + its own sub-flag ON →
 *     `BYPASS`. Privileged CLIs (`a11y_cli`, `shizuku_cli`) are kept on a
 *     separate flag because they drive other apps and read on-screen content,
 *     but that flag is an OVERLAY of the mode, not a second mode: with the
 *     master switch off nothing is auto-approved, privileged tools included.
 *  4. Anything else → the registry default.
 *
 * Note what is deliberately absent: there is no path from the master switch to
 * a `NOT_ALLOWED` **non**-privileged row, and no path that turns a stored
 * `NOT_ALLOWED` into `BYPASS`. Both absences are the safety half of the mode.
 */
internal object OffloadPermissionLevelPolicy {

    fun resolve(
        storedLevel: PermissionLevel?,
        defaultLevel: PermissionLevel,
        isPrivilegedTool: Boolean,
        autoApproveEnabled: Boolean,
        privilegedToolsEnabled: Boolean,
    ): PermissionLevel {
        if (storedLevel != null) return storedLevel
        if (defaultLevel == PermissionLevel.ASK_ONCE && autoApproveEnabled) {
            return PermissionLevel.BYPASS
        }
        if (defaultLevel == PermissionLevel.NOT_ALLOWED && isPrivilegedTool &&
            autoApproveEnabled && privilegedToolsEnabled) {
            return PermissionLevel.BYPASS
        }
        return defaultLevel
    }
}
