package com.rikkaminis.app.ui.sandbox

/**
 * [fix/r3-data-1119 F-16] What turning a custom mirror OFF should do to the
 * rootfs config file.
 *
 * The defect: `restoreOfficial` only ever restored `configPath.bak`. A fresh
 * Alpine rootfs ships no `root/.npmrc`, so `applyMirror` had nothing to back
 * up (its own guard is `if (!bakFile.exists() && configFile.exists())`) and
 * then WROTE a custom `root/.npmrc`. Turning the switch back off found no
 * `.bak` and took the "skipping restore" early return: the UI said "official"
 * while every `npm`/`npx` inside the sandbox kept using the custom registry,
 * permanently, with no way back except hand-editing the file in a shell.
 *
 * The decision is pure so it can be pinned on a JVM (the caller does the disk
 * IO). Three inputs, three outcomes:
 *
 *  - a backup exists  → RESTORE_FROM_BACKUP (the pre-fix path, unchanged);
 *  - no backup, file exists → OVERWRITE_WITH_OFFICIAL. The file is one we
 *    (or a previous install) wrote, so the honest "official" state is the
 *    official config written in place. For NPM that is a registry line
 *    pointing at the public registry — NOT deleting the file: `root/.npmrc`
 *    may also carry the user's own unrelated settings, and the app's contract
 *    is "the mirror source is official again", not "your npm config is gone";
 *  - no backup, no file → NOTHING_TO_DO (already official).
 */
internal enum class MirrorRestoreAction {
    RESTORE_FROM_BACKUP,
    OVERWRITE_WITH_OFFICIAL,
    NOTHING_TO_DO,
}

/**
 * [fix/r3-data-1119 F-16] Decide the restore action. See
 * [MirrorRestoreAction] for why "no backup" must not mean "skip".
 */
internal fun decideMirrorRestore(bakExists: Boolean, configExists: Boolean): MirrorRestoreAction =
    when {
        bakExists -> MirrorRestoreAction.RESTORE_FROM_BACKUP
        configExists -> MirrorRestoreAction.OVERWRITE_WITH_OFFICIAL
        else -> MirrorRestoreAction.NOTHING_TO_DO
    }

/**
 * [fix/r3-data-1119 F-16] The official config body for a category, i.e. what
 * the file must contain once the custom mirror is off.
 *
 * Deliberately derived from the SAME catalogue the apply path uses
 * ([MirrorCatalog.mirrors]) rather than from a second hard-coded list: the
 * official entry is the one flagged `isOfficial`, so the two directions can
 * never point at different hosts.
 */
internal fun officialConfigContent(category: MirrorCategory): String {
    val official = MirrorCatalog.mirrors(category).firstOrNull { it.isOfficial }
    val base = official?.baseURL
    return when (category) {
        // A stock Alpine rootfs resolves `main` and `community` from the CDN.
        MirrorCategory.ALPINE ->
            if (base == null) "" else "${base}v3.21/main\n${base}v3.21/community\n"
        // A pip.conf with only these three keys is exactly the shape
        // applyMirror writes, so turning the mirror off cannot leave a
        // half-customised file behind.
        MirrorCategory.PIP ->
            if (base == null) "" else
                "[global]\nbreak-system-packages = true\nindex-url = $base\ntrusted-host = pypi.org\n"
        // npm reads `registry=` from .npmrc; the official value is the public
        // registry. Deleting the file would also drop any unrelated user
        // settings it carries, so the official line is written instead.
        MirrorCategory.NPM ->
            if (base == null) "" else "registry=$base\n"
    }
}
