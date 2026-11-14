package com.rikkaminis.app.ui.settings

import java.io.File

/**
 * [rc-1114 U-08] Which directory the file browser should open at for a
 * session's media, given the per-day leaves [mediaSessionDirs] reports.
 *
 * A session's media lives at `filesDir/media/<yyyy>/<MM>/<dd>/<sid>/` — one
 * leaf per day the session was active. The storage detail page displayed those
 * bytes but gave the user no way to reach them (the session file browser is
 * rooted at `minis-sessions/<sid>`, which does not contain `media/`), so the
 * only way to reclaim a single big attachment was to clear the whole session,
 * workspace included.
 *
 * Two shapes, both complete — neither strands a file:
 *
 *  - exactly one leaf ⇒ root the browser AT it, so the user lands on the
 *    media itself instead of drilling through three date folders;
 *  - zero or several leaves ⇒ root at [mediaRoot]. With several, any single
 *    leaf would be an arbitrary pick, and the browser clamps "back" to the
 *    directory it opened at ([com.rikkaminis.app.ui.sandbox.FileBrowserViewModel]'s
 *    `initialRelativePath`), so opening one leaf would make the others
 *    unreachable. Rooting at the shared parent keeps every day reachable.
 *
 * [mediaRoot] is returned even when the list is empty: the caller only offers
 * the row when bytes were measured, and an empty list at that point means the
 * tree moved between the scan and the tap — the browser then shows the media
 * root rather than a dialog that claims "no files" while the size says
 * otherwise.
 */
internal fun mediaBrowseRoot(mediaSessionDirs: List<File>, mediaRoot: File): File =
    mediaSessionDirs.singleOrNull() ?: mediaRoot
