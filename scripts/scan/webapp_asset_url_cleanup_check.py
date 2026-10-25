#!/usr/bin/env python3
"""webapp_asset_url_cleanup_check — [da1021-F] gate, extended by [da1032-A].

Pins the webapp asset-URL single-source wiring and the AddToHomeSheet
cleanup closure:

  arm1: WebAppActivity.kt must not paste `file.name` raw into the
        asset-loader URL; it must route through `toAssetLoaderUrl(`.
  arm2: WebAppActivity.kt must not carry a second implementation of the
        asset path encoding (URLEncoder/Uri.encode) — the encoding comes
        from the single source (ui/preview/WebViewHolder.kt) only.
  arm3: AddToHomeSheet.kt must funnel cleanup through a DisposableEffect
        onDispose (universal hook) or an explicit Cancel cleanup.
  arm4: AddToHomeSheet.kt pin-failure branch must delete the DB row
        (`webAppShortcutRepository.delete(entity.id)`).

[dv14-B2] ISSUE-07 — the title field used to start blank and be filled by a
first-frame `LaunchedEffect` from the de-suffixed file name; the async parsed
`<title>` then arrived to a non-blank field and was dropped 100% of the time.
arm7 pins the decision point (only AddToHomeSheet.kt):

  arm7: the title field is seeded from the file name (`var titleText by
        remember(<fileName>)` with the seed deriving from fileName), and the
        `LaunchedEffect(parsedTitle)` backfill is guarded by a user-edited
        flag that the text-field writer flips (`userEditedTitle = true`) —
        so a late parse can neither win the race against the seed nor
        overwrite what the user typed.

[da1032-A] ISSUE-01 — the fullscreen toggle used to have TWO sources (a
plain `Boolean` field on the Activity plus a `remember(initialFullscreen)`
copy inside `WebAppHost`). Two arms pin the *decision point* instead of the
call shape:

  arm5: exactly ONE fullscreen snapshot state is declared in the file (any
        `*fullscreen*`-named `= mutableStateOf(...)`), no plain
        `var/val isFullscreen` boolean field, no residual `initialFullscreen`
        token, no delegated `var *fullscreen* by remember`, and no
        `remember(...)` keyed on fullscreen.
  arm6: the composition observes and the callback writes that SAME state:
        the `WebAppHost(...)` call passes `fullscreen = <that state>.value`
        and a `onFullscreenChange = { … }` writer assigns
        `<that state>.value =`, and the `fun WebAppHost(` signature carries
        no `initialFullscreen` parameter.

Deliberately NOT flagged (precision): a `fullscreen: Boolean` parameter of
the private `FloatingToolbar` composable, and `fullscreen` locals in other
files — the arms are keyed on "a fullscreen-named snapshot state", not on the
word `fullscreen` itself.

[deep-audit-1048 B-06] the primary action had no in-flight guard and the sheet
had no "I am gone" latch. Two arms, both derived from the sheet's own
`remember` states (renames do not evade them):

  arm13 (C-PIN): the CTA's `enabled` expression must reference a `remember`
        state that the CTA's own click entry checks BEFORE setting it true
        (read-then-set in one frame — `enabled` alone cannot stop a second tap
        that lands before the next recomposition).
  arm14 (C-CLOSE): a `remember` boolean set true by a teardown hook
        (`onDismissRequest` / `onDispose` / a click that calls `onDismiss()`,
        the last one being the only hook an external `webAppSheetTarget = null`
        removal runs) must be consumed as a guard AFTER the staging
        `withContext(Dispatchers.IO)` and BEFORE both
        `webAppShortcutRepository.create(` and `ShortcutPinner.pin(`. Order is
        judged: a guard placed after `create(` does not stop the row insert.

[dv21-B04 B-08] the pin verdict was silent on two carriers and the tile
decode ran on the composition thread. Three anchors, all on the decision
point (never on the call spelling):

  arm8a (ShortcutPinner.kt) / arm8b (WebPreviewShortcut.kt): every
        `requestPinShortcut` call is CAPTURED (its nearest enclosing
        `runCatching { }` spans the call, so a throwing OEM launcher cannot
        take the process down), its verdict is classified once through the
        shared `pinOutcome(`, compared against `ShortcutPinOutcome.REQUESTED`,
        and a non-REQUESTED answer reaches the user (`webapp_pin_failed`) —
        `false` and a throw are the same answer to the user.
  arm9: AddToHomeSheet.kt — no decode (`decodeFileBounded(` /
        `loadBitmapFromUri(`) inside a `remember(...) { }` block (that is the
        composition thread), and every decode that lives in a `produceState`
        block hops off it (`Dispatchers.IO` in the same block). Fail-closed
        when the sheet carries no decode call at all (anchor moved).

[dv26-E8] S-08 — the `mount`-scoped WebApp cold start. `WebAppPathResolver`
resolved `SCOPE_MOUNT` exactly once, while `applyMountedFoldersSnapshot` is
fired from `MinisApp.onCreate` on `applicationScope` + IO ([dv20-A4-A06] moved
it off the first frame on purpose). A home-screen shortcut starts
`WebAppActivity` directly — nothing awaits the snapshot first — so losing that
race sent `resolveHostPath` to the rootfs *placeholder directory* and the
Activity painted "WebApp source missing" over a healthy file. Three arms, each
on the decision point:

  arm10 (WebAppPathResolver.kt): the `SCOPE_MOUNT` arm of `resolve()` must
        reach — transitively, over calls BETWEEN FUNCTIONS OF THIS FILE — both
        `resolveHostPath(` (the retry re-probes; re-reading the old `File`
        object would re-read the placeholder) and `applyMountedFoldersSnapshot(`
        (the retry is "refresh the snapshot, then resolve again"). Seeded from
        the arm's own callee, so a refresh sitting in a function `resolve()`
        never calls does not count, and neither does one in `MinisApp.onCreate`.
  arm11 (WebAppActivity.kt): the resolved value is bound to a `val`, and the
        `renderSourceMissing(` exit must sit INSIDE the `if (<that val> ==
        null)` block — the guard cannot be moved to an "early exit before the
        retry" shape, and no source-missing call may appear between the
        resolve call and its guard.
  arm12 (WebAppActivity.kt): the resolve call stays inside
        `withContext(Dispatchers.IO)`, and no `applyMountedFoldersSnapshot(`
        appears in the Activity outside such a block — the snapshot does
        blocking host I/O and must not run on Main.

Deliberately NOT flagged: `SCOPE_SHARED` keeps its direct `resolveHostPath`
(global binds are registered synchronously in `MinisApp.onCreate`, so there is
no window to lose), and the `session_attachment` arm keeps its own guard.

Usage:
  python3 webapp_asset_url_cleanup_check.py <repo_root>   # real tree
  python3 webapp_asset_url_cleanup_check.py --selftest    # fixtures

Selftest marker: WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS
"""

import os
import re
import shutil
import sys
import tempfile

ACTIVITY_REL = "src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppActivity.kt"
SHEET_REL = "src/android/app/src/main/java/com/rikkaminis/app/webapp/AddToHomeSheet.kt"
PINNER_REL = "src/android/app/src/main/java/com/rikkaminis/app/webapp/ShortcutPinner.kt"
PREVIEW_SHORTCUT_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewShortcut.kt"
)
RESOLVER_REL = "src/android/app/src/main/java/com/rikkaminis/app/webapp/WebAppPathResolver.kt"

# --- compliant / violation fixture snippets (code-level, not comments) ---

COMPLIANT_ACTIVITY = """package com.rikkaminis.app.webapp

import com.rikkaminis.app.ui.preview.ASSET_LOADER_HOST
import com.rikkaminis.app.ui.preview.toAssetLoaderUrl

class WebAppActivity {
    private val fullscreenState = mutableStateOf(false)

    private fun handleOnBackPressed() {
        if (fullscreenState.value) {
            fullscreenState.value = false
            applySystemBars(fullscreen = false)
        } else {
            finish()
        }
    }

    private fun renderHost() {
        setContent {
            WebAppHost(
                file = file,
                fullscreen = fullscreenState.value,
                onFullscreenChange = { fs ->
                    fullscreenState.value = fs
                    applySystemBars(fullscreen = fs)
                },
                onClose = { finish() },
            )
        }
    }

    @Composable
    private fun WebAppHost(
        file: File,
        fullscreen: Boolean,
        onFullscreenChange: (Boolean) -> Unit,
        onClose: () -> Unit,
    ) {
        var toolbarVisible by remember { mutableStateOf(true) }
        Box {
            FloatingToolbar(
                fullscreen = fullscreen,
                onToggleFullscreen = { onFullscreenChange(!fullscreen) },
            )
        }
    }

    // `fullscreen` as a *parameter* is legal and must not be flagged.
    @Composable
    private fun FloatingToolbar(
        fullscreen: Boolean,
        onToggleFullscreen: () -> Unit,
    ) {
        val icon = if (fullscreen) FullscreenExit else Fullscreen
    }

    private fun loadShortcut() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                WebAppPathResolver.resolve(this@WebAppActivity, shortcut)
            }
            if (file == null) {
                renderSourceMissing(sourceSessionId = null)
                return@launch
            }
            loadHtml(webViewRef, file)
        }
    }

    private fun loadHtml(webView: WebView, file: File) {
        val url = toAssetLoaderUrl("file://" + file.absolutePath)
            ?: run {
                renderSourceMissing(sourceSessionId = null)
                return
            }
        webView.loadUrl(url)
    }
    private fun isAssetHost(url: Uri): Boolean =
        url.host == ASSET_LOADER_HOST
}
"""

VIOLATION_ARM1 = COMPLIANT_ACTIVITY.replace(
    'toAssetLoaderUrl("file://" + file.absolutePath)',
    '"https://" + ASSET_LOADER_HOST + "/${file.name}"',
)

VIOLATION_ARM2 = COMPLIANT_ACTIVITY.replace(
    'toAssetLoaderUrl("file://" + file.absolutePath)',
    'Uri.encode(file.absolutePath).let { "https://" + ASSET_LOADER_HOST + "/" + it }',
)

# [da1032-A] the pre-fix shape, restored verbatim: plain Boolean field +
# keyed remember copy inside the host.
VIOLATION_ARM5_DOUBLE_SOURCE = COMPLIANT_ACTIVITY.replace(
    "    private val fullscreenState = mutableStateOf(false)\n",
    "    private var isFullscreen: Boolean = false\n",
).replace(
    "        var toolbarVisible by remember { mutableStateOf(true) }\n",
    "        var fullscreen by remember(initialFullscreen) { mutableStateOf(initialFullscreen) }\n"
    "        var toolbarVisible by remember { mutableStateOf(true) }\n",
).replace(
    "                fullscreen = fullscreenState.value,\n",
    "                initialFullscreen = isFullscreen,\n",
)

# A *second* copy, correctly keyed — the subtle regression shape.
VIOLATION_ARM5_SECOND_COPY = COMPLIANT_ACTIVITY.replace(
    "        var toolbarVisible by remember { mutableStateOf(true) }\n",
    "        var fullscreen by remember { mutableStateOf(false) }\n"
    "        var toolbarVisible by remember { mutableStateOf(true) }\n",
)

# arm6-only: the state exists and is read, but the callback never writes it
# (icon and system bars drift apart after a back press).
VIOLATION_ARM6_SILENT_CALLBACK = COMPLIANT_ACTIVITY.replace(
    "                    fullscreenState.value = fs\n",
    "",
)

COMPLIANT_SHEET = """package com.rikkaminis.app.webapp

@Composable
fun AddToHomeSheet(source: WebAppSource, onDismiss: () -> Unit) {
    val cleanupCopiedAttachment = {
        if (!pinned) f.delete()
        Unit
    }
    var isPinning by remember { mutableStateOf(false) }
    var sheetClosed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            sheetClosed = true
            cleanupCopiedAttachment()
        }
    }
    var parsedTitle by remember { mutableStateOf<String?>(null) }
    var titleText by remember(fileName) { mutableStateOf(webAppTitleFallback(fileName)) }
    var userEditedTitle by remember { mutableStateOf(false) }
    LaunchedEffect(parsedTitle) {
        webAppTitleBackfill(parsedTitle, userEditedTitle)?.let { titleText = it }
    }
    OutlinedTextField(
        value = titleText,
        onValueChange = {
            userEditedTitle = true
            titleText = sanitizeSingleLineInput(it)
        },
    )
    ModalBottomSheet(onDismissRequest = {
        sheetClosed = true
        onDismiss()
    })
    OutlinedButton(onClick = {
        sheetClosed = true
        onDismiss()
    }) { Text(stringResource(R.string.cancel)) }
    Button(
        enabled = pinActionEnabled(copiedFile != null, titleText.isNotBlank(), isPinning, sheetClosed),
        onClick = {
            if (isPinning) return@Button
            isPinning = true
            scope.launch {
                try {
                    val iconBitmap = withContext(Dispatchers.IO) { renderIconBitmap() }
                    if (!pinShouldCommit(closed = sheetClosed, inFlight = isPinning)) {
                        cleanupCopiedAttachment()
                        return@launch
                    }
                    val entity = app.webAppShortcutRepository.create(htmlPath = "x")
                    val ok = ShortcutPinner.pin(context, entity, iconBitmap)
                    if (ok) {
                        pinned = true
                    } else {
                        runCatching { app.webAppShortcutRepository.delete(entity.id) }
                        cleanupCopiedAttachment()
                    }
                    onDismiss()
                } finally {
                    isPinning = false
                }
            }
        },
    ) { Text(stringResource(R.string.webapp_sheet_cta)) }
}
"""

# [dv14-B2] arm7 violations (sheet only; arm3/arm4 anchors untouched):
#  - the backfill effect ignores the user-edited guard (a late parse wins);
#  - the text-field writer never flips the guard;
#  - the field is seeded empty again (the pre-fix race).
VIOLATION_ARM7_UNGUARDED_BACKFILL = COMPLIANT_SHEET.replace(
    "        webAppTitleBackfill(parsedTitle, userEditedTitle)?.let { titleText = it }",
    "        titleText = parsedTitle ?: titleText",
)
VIOLATION_ARM7_WRITER_NO_FLAG = COMPLIANT_SHEET.replace(
    "            userEditedTitle = true\n",
    "",
)
VIOLATION_ARM7_EMPTY_SEED = COMPLIANT_SHEET.replace(
    "mutableStateOf(webAppTitleFallback(fileName))",
    'mutableStateOf("")',
)


# [dv21-B04 B-08] compliant carriers: one shared verdict, captured call, the
# decline surfaced. Code-level shapes only.
COMPLIANT_PINNER = """package com.rikkaminis.app.webapp

object ShortcutPinner {
    fun pin(context: Context, shortcut: WebAppShortcutEntity): Boolean {
        val outcome = pinOutcome(
            runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) },
        )
        if (outcome != ShortcutPinOutcome.REQUESTED) {
            Toast.makeText(context, R.string.webapp_pin_failed, Toast.LENGTH_LONG).show()
        }
        return outcome == ShortcutPinOutcome.REQUESTED
    }
}
"""

COMPLIANT_PREVIEW_SHORTCUT = """package com.rikkaminis.app.ui.preview

object WebPreviewShortcut {
    fun pin(context: Context, title: String): Boolean {
        val outcome = pinOutcome(
            runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) },
        )
        Toast.makeText(
            context,
            context.getString(
                if (outcome == ShortcutPinOutcome.REQUESTED) {
                    R.string.webpreview_pin_to_home_requested
                } else {
                    R.string.webapp_pin_failed
                },
            ),
            Toast.LENGTH_SHORT,
        ).show()
        return outcome == ShortcutPinOutcome.REQUESTED
    }
}
"""

# [dv21-B04 B-08] arm8 violations (one per carrier, one criterion each):
#  - the pinner classifies nothing: the verdict is a raw Boolean again, so a
#    decline is silent (and a throw escapes);
#  - the preview carrier keeps the verdict but drops the capture, so an OEM
#    launcher that throws kills the process.
VIOLATION_ARM8_SILENT_DECLINE = COMPLIANT_PINNER.replace(
    """        val outcome = pinOutcome(
            runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) },
        )""",
    """        val outcome = ShortcutManagerCompat.requestPinShortcut(context, info, null)""",
).replace(
    """        if (outcome != ShortcutPinOutcome.REQUESTED) {
            Toast.makeText(context, R.string.webapp_pin_failed, Toast.LENGTH_LONG).show()
        }
        return outcome == ShortcutPinOutcome.REQUESTED""",
    """        return outcome""",
)
VIOLATION_ARM8_NO_CAPTURE = COMPLIANT_PREVIEW_SHORTCUT.replace(
    "runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }",
    "ShortcutManagerCompat.requestPinShortcut(context, info, null)",
)

# [dv21-B04 B-08] arm9 fixture fragment (compliant) + its two violations.
SHEET_DECODE_TILE = """
@Composable
private fun IconTile(path: String) {
    val bmp by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap
        }
    }
    if (bmp != null) {
        Image(bitmap = bmp.asImageBitmap(), contentDescription = null)
    }
}
"""

VIOLATION_ARM9_REMEMBER = SHEET_DECODE_TILE.replace(
    """    val bmp by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap
        }
    }""",
    """    val bmp = remember(path) {
        ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap
    }""",
)
VIOLATION_ARM9_NO_IO = SHEET_DECODE_TILE.replace(
    """        value = withContext(Dispatchers.IO) {
            ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap
        }""",
    """        value = ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap""",
)
# [wide3-06 arm9] The IO hop exists but the decode was hoisted OUT of it: the
# old criterion only asked whether `Dispatchers.IO` appeared in the body.
VIOLATION_ARM9_DECODE_BEFORE_IO = SHEET_DECODE_TILE.replace(
    """        value = withContext(Dispatchers.IO) {
            ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap
        }""",
    """        val decoded = ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap
        value = withContext(Dispatchers.IO) { decoded }""",
)


# [dv26-E8] S-08 fixtures — the mount-scope resolver and its violations.

COMPLIANT_RESOLVER = """package com.rikkaminis.app.webapp

import java.io.File

object WebAppPathResolver {

    fun resolve(context: Context, shortcut: WebAppShortcutEntity): File? {
        val file = when (shortcut.pathScope) {
            WebAppShortcutRepository.SCOPE_SESSION_ATTACHMENT -> resolveSession(context, shortcut)
            WebAppShortcutRepository.SCOPE_SHARED -> PRootKernel.resolveHostPath(shortcut.htmlPath)
            WebAppShortcutRepository.SCOPE_MOUNT -> resolveMount(context, shortcut.htmlPath)
            else -> null
        }
        return file?.takeIf { it.exists() && it.isFile }
    }

    private fun resolveMount(context: Context, htmlPath: String): File? {
        val hostPath = resolveMountWithRetry(
            htmlPath = htmlPath,
            mountKnown = { root -> PRootKernel.bindMounts[root] != null },
            probe = { path -> PRootKernel.resolveHostPath(path)?.absolutePath },
            usable = { path -> File(path).let { it.exists() && it.isFile } },
            refresh = { PRootKernel.applyMountedFoldersSnapshot(context) },
        )
        return hostPath?.let { File(it) }
    }

    private fun resolveSession(context: Context, shortcut: WebAppShortcutEntity): File? = null
}
"""

# The pre-fix shape, verbatim: one shot, no snapshot refresh, no retry.
VIOLATION_ARM10_NO_RETRY = COMPLIANT_RESOLVER.replace(
    "WebAppShortcutRepository.SCOPE_MOUNT -> resolveMount(context, shortcut.htmlPath)",
    "WebAppShortcutRepository.SCOPE_MOUNT -> PRootKernel.resolveHostPath(shortcut.htmlPath)",
)

# The subtle shape: the refresh exists in the file but `resolve()` cannot reach
# it — a file-level grep would call this green, the closure walk must not.
VIOLATION_ARM10_ORPHAN_REFRESH = COMPLIANT_RESOLVER.replace(
    "            refresh = { PRootKernel.applyMountedFoldersSnapshot(context) },",
    "            refresh = { },",
).replace(
    "    private fun resolveSession(context: Context, shortcut: WebAppShortcutEntity): File? = null",
    "    private fun unusedRefresh(context: Context) {\n"
    "        PRootKernel.applyMountedFoldersSnapshot(context)\n"
    "    }\n",
)

# [wide3-06 arm10] An expression body (`= null`) directly above a sibling
# declaration: the old span ran to the next `\n    private fun ` and swallowed
# that sibling, so its `resolveMountWithRetry` call looked like `resolveMount`'s.
VIOLATION_ARM10_EXPR_SWALLOW = COMPLIANT_RESOLVER.replace(
    "    private fun resolveMount(context: Context, htmlPath: String): File? {",
    "    private fun resolveMount(context: Context, htmlPath: String): File? = null\n"
    "\n"
    "    internal fun unusedMountHelper(context: Context, htmlPath: String): File? {",
)

# The source-missing exit without its null guard — "early exit before the
# retry", the shape that would make a failed first probe terminal again.
VIOLATION_ARM11_UNGUARDED_MISSING = COMPLIANT_ACTIVITY.replace(
    """            if (file == null) {
                renderSourceMissing(sourceSessionId = null)
                return@launch
            }
""",
    """            renderSourceMissing(sourceSessionId = null)
            return@launch
""",
)

# Resolve the shortcut on the main thread (the resolver does blocking host I/O).
VIOLATION_ARM12_MAIN_THREAD_RESOLVE = COMPLIANT_ACTIVITY.replace(
    """            val file = withContext(Dispatchers.IO) {
                WebAppPathResolver.resolve(this@WebAppActivity, shortcut)
            }""",
    """            val file = WebAppPathResolver.resolve(this@WebAppActivity, shortcut)""",
)

# Apply the mount snapshot from the Activity's main thread.
VIOLATION_ARM12_MAIN_THREAD_SNAPSHOT = COMPLIANT_ACTIVITY.replace(
    """    private fun loadShortcut() {""",
    """    private fun refreshMounts() {
        PRootKernel.applyMountedFoldersSnapshot(this)
    }

    private fun loadShortcut() {""",
)

def strip_comments(src: str) -> str:
    """Remove //-comments, /* */ comments and KDoc, keeping line structure.

    String-aware: this file carries string literals containing `//`
    (`"file://" + file.absolutePath`) and template literals with embedded
    quotes — a naive per-line `line.find("//")` truncation mangles them.
    """
    out = []
    i = 0
    n = len(src)
    in_string = None
    while i < n:
        c = src[i]
        if in_string is not None:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(src[i + 1])
                i += 2
                continue
            if c == in_string:
                in_string = None
            i += 1
            continue
        if c == '"':
            in_string = c
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            if j == -1:
                j = n
            out.append("")
            i = j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            end = n if j == -1 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in src[i:end]))
            i = end
            continue
        out.append(c)
        i += 1
    return "".join(out)


# --- [da1032-A] fullscreen single-source patterns -------------------------

# A snapshot-state declaration: `(private )? val|var <name> = (androidx...)?mutableStateOf(`
# (the `by remember { mutableStateOf(...) }` delegate form is matched below).
FULLSCREEN_STATE_DECL_RE = re.compile(
    r"\b(?:private\s+)?(?:var|val)\s+([A-Za-z_]\w*)\s*=\s*"
    r"(?:androidx\.compose\.runtime\.)?mutableStateOf\("
)
PLAIN_BOOL_FIELD_RE = re.compile(r"\b(?:var|val)\s+isFullscreen\b")
DELEGATED_VAR_RE = re.compile(r"\bvar\s+([A-Za-z_]\w*)\s+by\s+remember\b")
KEYED_REMEMBER_RE = re.compile(r"\bremember\s*\([^)]*[Ff]ullscreen")


def _fullscreen_states(code: str):
    return [
        m.group(1)
        for m in FULLSCREEN_STATE_DECL_RE.finditer(code)
        if "fullscreen" in m.group(1).lower()
    ]


def _paren_block(code: str, open_idx: int) -> str:
    depth = 0
    for j in range(open_idx, len(code)):
        if code[j] == "(":
            depth += 1
        elif code[j] == ")":
            depth -= 1
            if depth == 0:
                return code[open_idx:j + 1]
    return code[open_idx:]


def call_blocks(code: str, call_name: str):
    """`callName(...)` blocks that are CALLS, not the `fun callName(` decl."""
    blocks = []
    for m in re.finditer(r"\b" + re.escape(call_name) + r"\s*\(", code):
        if re.search(r"\bfun\s+$", code[max(0, m.start() - 12):m.start()]):
            continue
        blocks.append(_paren_block(code, code.index("(", m.start())))
    return blocks


def fun_signature(code: str, fun_name: str):
    m = re.search(r"\bfun\s+" + re.escape(fun_name) + r"\s*\(", code)
    if not m:
        return None
    return code[max(0, m.start() - 24):m.start()] + _paren_block(
        code, code.index("(", m.start())
    )


def check_activity_arm1(src: str) -> bool:
    """No bare `${file.name}` interpolation into the asset-loader URL, and
    the loadUrl path routes through toAssetLoaderUrl."""
    code = strip_comments(src)
    if '"/${file.name}"' in code or "/${file.name}" in code:
        return False
    return "toAssetLoaderUrl(" in code


def check_activity_arm2(src: str) -> bool:
    """No second implementation of the *asset path* encoding: the encoding
    must come from the single source (toAssetLoaderUrl). A pre-existing
    Uri.encode on a session deep link (minis://) is legitimate and stays."""
    code = strip_comments(src)
    if "URLEncoder.encode(" in code or "Uri.encode(file." in code:
        return False
    return "toAssetLoaderUrl(" in code


def check_activity_arm5(src: str) -> bool:
    """[da1032-A] Exactly one fullscreen snapshot state, and no second copy:
    no plain `isFullscreen` boolean field, no residual `initialFullscreen`,
    no delegated `var <fullscreen-ish> by remember`, no remember keyed on
    fullscreen."""
    code = strip_comments(src)
    if PLAIN_BOOL_FIELD_RE.search(code):
        return False
    if "initialFullscreen" in code:
        return False
    if len(_fullscreen_states(code)) != 1:
        return False
    for m in DELEGATED_VAR_RE.finditer(code):
        if "fullscreen" in m.group(1).lower():
            return False
    if KEYED_REMEMBER_RE.search(code):
        return False
    return True


def check_activity_arm6(src: str) -> bool:
    """[da1032-A] The host observes and the callback writes the SAME state
    object; the host signature carries no second fullscreen input."""
    code = strip_comments(src)
    names = _fullscreen_states(code)
    if len(names) != 1:
        return False
    name = names[0]
    blocks = call_blocks(code, "WebAppHost")
    if not blocks:
        return False
    read = re.compile(r"\bfullscreen\s*=\s*" + re.escape(name) + r"\.value\b")
    write = re.compile(re.escape(name) + r"\.value\s*=")
    if not any(read.search(b) and write.search(b) for b in blocks):
        return False
    sig = fun_signature(code, "WebAppHost")
    if sig is None or "initialFullscreen" in sig:
        return False
    return True


def check_sheet_arm3(src: str) -> bool:
    """The universal hook must funnel cleanup: a DisposableEffect whose
    `onDispose` block reaches `cleanupCopiedAttachment()`; a file without any
    DisposableEffect must clean up in the Cancel callback.

    [deep-audit-1048 B-06] The check used to require the single line
    `onDispose { cleanupCopiedAttachment() }`. B-06 needs a second statement in
    that block (the sheet-closed latch), so the anchor is now derived from the
    block itself — same intent (the hook that every disappearance path funnels
    through must clean up), no longer a spelling lock.
    """
    code = strip_comments(src)
    if "cleanupCopiedAttachment()" not in code:
        return False
    for m in re.finditer(r"onDispose\s*\{", code):
        if "cleanupCopiedAttachment()" in _brace_block(code, m.end() - 1):
            return True
    return "DisposableEffect(" not in code and "onClick = { cleanupCopiedAttachment()" in code


def check_sheet_arm4(src: str) -> bool:
    """Pin-failure branch must delete the DB row."""
    code = strip_comments(src)
    return "webAppShortcutRepository.delete(entity.id)" in code


def _brace_block(code: str, open_idx: int) -> str:
    depth = 0
    for j in range(open_idx, len(code)):
        if code[j] == "{":
            depth += 1
        elif code[j] == "}":
            depth -= 1
            if depth == 0:
                return code[open_idx:j + 1]
    return code[open_idx:]


def _balanced_within(text: str, open_idx: int, opener: str, closer: str) -> int:
    """Index of the bracket closing text[open_idx] (quote-aware), or -1."""
    if open_idx < 0 or open_idx >= len(text) or text[open_idx] != opener:
        return -1
    depth = 0
    i = open_idx
    while i < len(text):
        c = text[i]
        if c == '"':
            j = i + 1
            while j < len(text):
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _cond_dead_text(cond: str) -> bool:
    """True when a literal operand decides the condition.

    `false && guard` / `true || guard` preserve the guard's exact spelling and
    delete its decision — the dominant dataflow witness of this wave.
    """
    cond = cond.strip()
    if cond.startswith("("):
        close = _balanced_within(cond, 0, "(", ")")
        if close == len(cond) - 1:
            cond = cond[1:-1]
    parts, depth, buf, i = [], 0, "", 0
    while i < len(cond):
        c = cond[i]
        two = cond[i:i + 2]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if depth == 0 and two in ("&&", "||"):
            parts.append(buf)
            buf = ""
            i += 2
            continue
        buf += c
        i += 1
    parts.append(buf)
    return any(p.strip() in ("true", "false") for p in parts)


def check_sheet_arm7(src: str) -> bool:
    """[dv14-B2] Title seed + guarded backfill (AddToHomeSheet.kt only).

    Decision points, not call shapes:
      - the `titleText` snapshot state is seeded from the file name (so the
        very first composition already shows the fallback title and a slow
        async parse cannot win the race by blanking the field);
      - the text-field writer flips the user-edited guard;
      - the `LaunchedEffect(parsedTitle)` body is guarded by that flag.
    Fail-closed on any missing anchor."""
    code = strip_comments(src)
    if "titleText" not in code or "parsedTitle" not in code:
        return False
    m = re.search(r"\bvar\s+titleText\s+by\s+remember\s*\(", code)
    if not m:
        return False
    open_idx = code.index("(", m.start())
    seed = _paren_block(code, open_idx)
    seed_end = open_idx + len(seed)
    init_brace = code.find("{", seed_end)
    init = _brace_block(code, init_brace) if init_brace >= 0 else ""
    if "fileName" not in init:
        return False
    if "userEditedTitle" not in code:
        return False
    if not re.search(r"userEditedTitle\s*=\s*true", code):
        return False
    eff = re.search(r"LaunchedEffect\s*\(\s*parsedTitle[^)]*\)", code)
    if not eff:
        return False
    brace = code.find("{", eff.end())
    body = _brace_block(code, brace) if brace >= 0 else ""
    return "userEditedTitle" in body


DELEGATED_STATE_RE = re.compile(r"\bvar\s+(\w+)\s*(?::[^=\n]+)?\s+by\s+remember\b")
BUTTON_CALL_RE = re.compile(r"(?<![\w.])Button\s*\(")


def _delegated_states(code: str):
    """Names declared as `var X [by] remember` — the sheet's own state."""
    return set(DELEGATED_STATE_RE.findall(code))


def _top_level_expr_after(block: str, key: str) -> str:
    """Text after `key =` inside `block`, up to the first depth-0 comma."""
    m = re.search(r"\b%s\s*=" % re.escape(key), block)
    if not m:
        return ""
    depth = 0
    out = []
    for ch in block[m.end():]:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif ch == "," and depth == 0:
            break
        out.append(ch)
    return "".join(out)


def check_sheet_arm13(src: str) -> bool:
    """[deep-audit-1048 B-06] C-PIN: the CTA carries an in-flight state.

    Decision point (not the call spelling): the CTA's `enabled` expression
    mentions a `remember` state, and the CTA's own `onClick` body tests that
    state BEFORE assigning it true. Fail-closed when the sheet has no
    `Button(enabled = ...)` at all — the anchor moved."""
    code = strip_comments(src)
    states = _delegated_states(code)
    if not states:
        return False
    for m in BUTTON_CALL_RE.finditer(code):
        block = _paren_block(code, m.end() - 1)
        enabled_expr = _top_level_expr_after(block, "enabled")
        if not enabled_expr:
            continue
        named = {w for w in re.findall(r"\b[A-Za-z_]\w*\b", enabled_expr)} & states
        if not named:
            continue
        click_at = block.find("onClick")
        if click_at < 0:
            continue
        brace = block.find("{", click_at)
        click = _brace_block(block, brace) if brace >= 0 else ""
        for name in named:
            set_at = re.search(r"\b%s\s*=\s*true\b" % re.escape(name), click)
            if not set_at:
                continue
            head = click[:set_at.start()]
            for cond in re.finditer(r"if\s*\(", head):
                cond_text = _paren_block(head, cond.end() - 1)
                if not re.search(r"\b%s\b" % re.escape(name), cond_text):
                    continue
                if _cond_dead_text(cond_text):
                    # [wide3-06 arm13] `if (false && isPinning) return@Button`
                    # keeps the guard's spelling; the frame gap the CTA relies
                    # on is then unguarded again (double submit on a double tap).
                    continue
                close = cond.end() - 1 + len(cond_text)
                tail = head[close:]
                if re.match(r"\s*(?:\{|return\b|return@)", tail):
                    return True
    return False


def check_sheet_arm14(src: str) -> bool:
    """[deep-audit-1048 B-06] C-CLOSE: a cancelled sheet must not commit.

    A `remember` boolean latched true by a teardown hook must be consumed, in
    the coroutine that calls `ShortcutPinner.pin(`, after the staging
    `withContext(Dispatchers.IO)` and before both the row insert and the pin.
    Fail-closed when there is no pin call or no teardown hook."""
    code = strip_comments(src)
    states = _delegated_states(code)
    if not states:
        return False
    hooks = []
    for m in re.finditer(r"onDismissRequest\s*=\s*\{", code):
        hooks.append(_brace_block(code, m.end() - 1))
    for m in re.finditer(r"onDispose\s*\{", code):
        hooks.append(_brace_block(code, m.end() - 1))
    for m in re.finditer(r"onClick\s*=\s*\{", code):
        block = _brace_block(code, m.end() - 1)
        # A CTA that runs the pin also ends with `onDismiss()`, but it is not a
        # teardown hook — its `isPinning = true` would otherwise masquerade as a
        # close latch (and make this arm green with the latch deleted).
        if "onDismiss()" in block and ".launch" not in block:
            hooks.append(block)
    latched = set()
    for hook in hooks:
        latched |= {w for w in re.findall(r"\b(\w+)\s*=\s*true\b", hook)}
    latched &= states
    if not latched:
        return False
    for m in re.finditer(r"\.launch\s*\{", code):
        body = _brace_block(code, m.end() - 1)
        pin_at = body.find("ShortcutPinner.pin(")
        if pin_at < 0:
            continue
        create_at = body.find("webAppShortcutRepository.create(")
        if create_at < 0:
            return False
        io_at = body.find("withContext(Dispatchers.IO)")
        if io_at < 0:
            return False
        for cond in re.finditer(r"if\s*\(", body[:create_at]):
            if cond.start() < io_at:
                continue  # a guard about the target, not about the closed sheet
            cond_text = _paren_block(body, cond.end() - 1)
            if any(re.search(r"\b%s\b" % re.escape(n), cond_text) for n in latched):
                if _cond_dead_text(cond_text):
                    # [wide3-06 arm14] `if (false && !pinShouldCommit(...))`
                    # spells the close latch and deletes its decision.
                    continue
                return cond.start() < pin_at
    return False


DECODE_CALLS = ("decodeFileBounded", "loadBitmapFromUri")


def _call_sites(code: str, name: str):
    """Offsets of `name(` CALLS (the `fun name(` declaration is skipped)."""
    out = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code):
        if re.search(r"\bfun\s+$", code[max(0, m.start() - 12):m.start()]):
            continue
        out.append(m.start())
    return out


def _block_body(code: str, header_re: str):
    """(open_brace, body) for each `header_re` occurrence that opens a lambda."""
    out = []
    for m in re.finditer(header_re, code):
        ob = code.find("(", m.end() - 1) if header_re.endswith(r"\(") else code.find("(", m.start())
        if ob < 0:
            continue
        pb = _paren_block(code, ob)
        br = code.find("{", ob + len(pb))
        if br < 0:
            continue
        body = _brace_block(code, br)
        if not body.endswith("}"):
            continue
        out.append((br, body))
    return out


def check_pin_arm8(src: str) -> bool:
    """[dv21-B04 B-08] ONE captured pin attempt whose verdict is surfaced.

    Decision points, not call shapes:
      - a throwing OEM launcher cannot escape: the call sits inside a
        `runCatching { }` block;
      - the answer is classified once by the shared `pinOutcome(` and compared
        against `REQUESTED` (a bare Boolean is not a verdict — `false` and a
        throw are the same answer to the user);
      - a non-REQUESTED answer is visible: the failure string is referenced.
    Fail-closed when the carrier no longer calls `requestPinShortcut`."""
    code = strip_comments(src)
    sites = _call_sites(code, "requestPinShortcut")
    if not sites:
        return False
    captured_spans = []
    for m in re.finditer(r"\brunCatching\s*\{", code):
        br = code.index("{", m.start())
        body = _brace_block(code, br)
        if body.endswith("}"):
            captured_spans.append((br, br + len(body)))
    for s in sites:
        if not any(a < s < b for a, b in captured_spans):
            return False
    if "pinOutcome(" not in code:
        return False
    if not re.search(r"ShortcutPinOutcome\s*\.\s*REQUESTED", code):
        return False
    return "webapp_pin_failed" in code


def check_sheet_arm9(src: str) -> bool:
    """[dv21-B04 B-08] No decode on the composition thread.

    Two decision points:
      - nothing decodes inside a `remember(...) { }` body (that body runs on
        the composition thread — the sheet used to block for the whole
        read+sample of a 12 Mpx icon);
      - any decode in a `produceState` body hops off it (`Dispatchers.IO` in
        the same body; `produceState` otherwise still runs on the composition
        dispatcher).
    Fail-closed when the sheet carries no decode call at all (anchor moved)."""
    code = strip_comments(src)
    sites = []
    for name in DECODE_CALLS:
        sites.extend(_call_sites(code, name))
    if not sites:
        return False
    for br, body in _block_body(code, r"\bremember\s*\("):
        if any(br < s < br + len(body) for s in sites):
            return False
    for br, body in _block_body(code, r"\bproduceState\s*(?:<[^>{]*>)?\s*\("):
        for s in sites:
            if br < s < br + len(body):
                # [wide3-06 arm9] "Dispatchers.IO appears somewhere in the body"
                # was also true of
                #   val decoded = decode(path); value = withContext(IO) { decoded }
                # — the decode itself still ran on the composition thread. The
                # decode call must sit INSIDE the IO block.
                if not enclosing_io_block(code, s):
                    return False
    return True


# --- [dv26-E8] S-08 mount-scope cold start --------------------------------

FUN_DECL_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\(")
SCOPE_MOUNT_ARM_RE = re.compile(r"\bSCOPE_MOUNT\s*->\s*([^\n}]*)")
IDENT_CALL_RE = re.compile(r"\b([A-Za-z_]\w*)\s*\(")
RESOLVE_CALL = "WebAppPathResolver.resolve("


def _expr_end(code: str, eq_idx: int) -> int:
    """End offset of an expression body that starts after `=` at [eq_idx].

    A depth-0 newline ends it unless the next line is a continuation
    (`.foo()`, `?: x`, `+ x`, `&& x`, …). Deriving the end from the expression
    itself — instead of `find("\\n    private fun ")` — is what stops a
    one-line expression body (`= null`) from swallowing the NEXT declaration
    and lending its calls to the wrong function (witness arm10).
    """
    depth = 0
    i = eq_idx + 1
    n = len(code)
    while i < n:
        c = code[i]
        if c == '"':
            j = i + 1
            while j < n:
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == '"':
                    break
                j += 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif c == "\n" and depth == 0:
            j = i + 1
            while j < n and code[j] in " \t":
                j += 1
            if j < n and code[j] in ".?:+-*/&|,":
                i = j
                continue
            return i
        i += 1
    return n


def fun_spans(code: str):
    """{name: body} for every `fun` in [code] (comments already stripped).

    Block bodies become brace blocks; expression bodies run to the end of the
    expression (see `_expr_end`). A body that cannot be delimited is skipped —
    for arm10 that fails CLOSED (a missing edge only removes a reachable
    function).
    """
    spans = {}
    for m in FUN_DECL_RE.finditer(code):
        name = m.group(1)
        open_paren = code.find("(", m.end() - 1)
        if open_paren < 0:
            continue
        paren = _paren_block(code, open_paren)
        j = open_paren + len(paren)
        brace = code.find("{", j)
        eq = code.find("=", j)
        if brace < 0 and eq < 0:
            continue
        if 0 <= eq and (brace < 0 or eq < brace) and code[eq + 1:eq + 2] != "=":
            # [wide3-06 arm10] stops at the end of THIS expression, so a
            # sibling declaration's calls stay out of this body.
            spans.setdefault(name, code[eq:_expr_end(code, eq)])
        elif brace >= 0:
            spans.setdefault(name, _brace_block(code, brace))
    return spans


def reachable_bodies(bodies, seeds):
    """Body text of every function reachable from [seeds] by call name."""
    seen = set()
    stack = [x for x in seeds if x in bodies]
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        seen.add(name)
        for m in IDENT_CALL_RE.finditer(bodies[name]):
            callee = m.group(1)
            if callee in bodies and callee not in seen:
                stack.append(callee)
    return "\n".join(bodies[n] for n in seen)


def check_mount_arm_snapshot_retry(src: str) -> bool:
    """arm10 — `SCOPE_MOUNT` reaches "refresh the snapshot, then resolve again"."""
    code = strip_comments(src)
    bodies = fun_spans(code)
    resolve_body = bodies.get("resolve")
    if not resolve_body:
        return False
    arm = None
    for m in SCOPE_MOUNT_ARM_RE.finditer(resolve_body):
        arm = m.group(1)
    if not arm:
        return False
    seeds = [m.group(1) for m in IDENT_CALL_RE.finditer(arm) if m.group(1) in bodies]
    if not seeds:
        return False
    closure = reachable_bodies(bodies, seeds)
    return "resolveHostPath(" in closure and "applyMountedFoldersSnapshot(" in closure


def enclosing_io_block(code: str, idx: int) -> bool:
    """Is [idx] inside a `withContext(Dispatchers.IO) { … }` block?"""
    for m in re.finditer(r"\bwithContext\s*\(", code):
        open_paren = code.find("(", m.end() - 1)
        paren = _paren_block(code, open_paren)
        if "Dispatcher" not in paren or not re.search(r"\bIO\b", paren):
            continue
        brace = code.find("{", open_paren + len(paren))
        if brace < 0:
            continue
        block = _brace_block(code, brace)
        if brace < idx < brace + len(block):
            return True
    return False


def check_activity_failure_guard(src: str) -> bool:
    """arm11 — `renderSourceMissing(` is the guarded failure of the resolve."""
    code = strip_comments(src)
    at = code.find(RESOLVE_CALL)
    if at < 0:
        return False
    assigned = None
    for m in re.finditer(r"\bval\s+([A-Za-z_]\w*)\s*=", code[:at]):
        assigned = m.group(1)
    if not assigned:
        return False
    guard = code.find("if (%s == null)" % assigned, at)
    if guard < 0:
        return False
    if "renderSourceMissing(" in code[at:guard]:
        return False
    brace = code.find("{", guard)
    if brace < 0:
        return False
    return "renderSourceMissing(" in _brace_block(code, brace)


def check_activity_resolve_offmain(src: str) -> bool:
    """arm12 — resolve (and any snapshot refresh) never runs on Main."""
    code = strip_comments(src)
    at = code.find(RESOLVE_CALL)
    if at < 0:
        return False
    if not enclosing_io_block(code, at):
        return False
    for m in re.finditer(r"\bapplyMountedFoldersSnapshot\s*\(", code):
        if not enclosing_io_block(code, m.start()):
            return False
    return True


def evaluate_tree(root: str):
    """-> list of failing arm ids (empty = green)."""
    paths = (
        ("WebAppActivity.kt", os.path.join(root, ACTIVITY_REL)),
        ("AddToHomeSheet.kt", os.path.join(root, SHEET_REL)),
        ("ShortcutPinner.kt", os.path.join(root, PINNER_REL)),
        ("WebPreviewShortcut.kt", os.path.join(root, PREVIEW_SHORTCUT_REL)),
        ("WebAppPathResolver.kt", os.path.join(root, RESOLVER_REL)),
    )
    for label, path in paths:
        if not os.path.exists(path):
            return ["missing:" + label]
    act = open(os.path.join(root, ACTIVITY_REL), encoding="utf-8").read()
    sheet = open(os.path.join(root, SHEET_REL), encoding="utf-8").read()
    pinner = open(os.path.join(root, PINNER_REL), encoding="utf-8").read()
    preview = open(os.path.join(root, PREVIEW_SHORTCUT_REL), encoding="utf-8").read()
    resolver = open(os.path.join(root, RESOLVER_REL), encoding="utf-8").read()

    failures = []
    if not check_activity_arm1(act):
        failures.append("arm1")
    if not check_activity_arm2(act):
        failures.append("arm2")
    if not check_sheet_arm3(sheet):
        failures.append("arm3")
    if not check_sheet_arm4(sheet):
        failures.append("arm4")
    if not check_activity_arm5(act):
        failures.append("arm5")
    if not check_activity_arm6(act):
        failures.append("arm6")
    if not check_sheet_arm7(sheet):
        failures.append("arm7")
    if not check_pin_arm8(pinner):
        failures.append("arm8a")
    if not check_pin_arm8(preview):
        failures.append("arm8b")
    if not check_sheet_arm9(sheet):
        failures.append("arm9")
    if not check_sheet_arm13(sheet):
        failures.append("arm13")
    if not check_sheet_arm14(sheet):
        failures.append("arm14")
    if not check_mount_arm_snapshot_retry(resolver):
        failures.append("arm10")
    if not check_activity_failure_guard(act):
        failures.append("arm11")
    if not check_activity_resolve_offmain(act):
        failures.append("arm12")
    return failures


def run_on_tree(root: str) -> int:
    failures = evaluate_tree(root)
    if failures:
        print(f"[webapp-asset-url] FAIL arms={','.join(failures)}")
        return 1
    print("[webapp-asset-url] PASS all 15 arms "
          "(asset-url single source ×2, sheet cleanup ×2, fullscreen single source ×2, "
          "title seed + user-edit guard ×1, pin verdict ×2, decode off the "
          "composition thread ×1, sheet in-flight guard ×1, sheet close latch ×1, "
          "mount cold-start snapshot retry ×3)")
    return 0

def selftest() -> int:
    tmp = tempfile.mkdtemp(prefix="webapp-gate-")
    ok = True
    try:
        cases = [
            ("compliant", COMPLIANT_ACTIVITY, COMPLIANT_SHEET, True),
            ("arm1-violation", VIOLATION_ARM1, COMPLIANT_SHEET, False),
            ("arm2-violation", VIOLATION_ARM2, COMPLIANT_SHEET, False),
            ("arm3-violation", COMPLIANT_ACTIVITY, COMPLIANT_SHEET.replace(
                "        onDispose {\n            sheetClosed = true\n"
                "            cleanupCopiedAttachment()\n        }",
                "        onDispose {\n            onDismiss()\n        }"), False),
            ("arm4-violation", COMPLIANT_ACTIVITY, COMPLIANT_SHEET.replace(
                "runCatching { app.webAppShortcutRepository.delete(entity.id) }", "cleanupOnly()"), False),
            ("arm5-violation double source (plain field + keyed copy)",
             VIOLATION_ARM5_DOUBLE_SOURCE, COMPLIANT_SHEET, False),
            ("arm5-violation second remember copy",
             VIOLATION_ARM5_SECOND_COPY, COMPLIANT_SHEET, False),
            ("arm6-violation callback never writes the state",
             VIOLATION_ARM6_SILENT_CALLBACK, COMPLIANT_SHEET, False),
            ("arm7-violation unguarded backfill overwrites the field",
             COMPLIANT_ACTIVITY, VIOLATION_ARM7_UNGUARDED_BACKFILL, False),
            ("arm7-violation writer never flips the guard",
             COMPLIANT_ACTIVITY, VIOLATION_ARM7_WRITER_NO_FLAG, False),
            ("arm7-violation empty seed (pre-fix race)",
             COMPLIANT_ACTIVITY, VIOLATION_ARM7_EMPTY_SEED, False),
        ]
        def write_tree(name, act, sheet, pinner, preview, resolver=COMPLIANT_RESOLVER):
            tree = os.path.join(tmp, name.replace(" ", "_").replace("(", "").replace(")", ""))
            for rel in (ACTIVITY_REL, SHEET_REL, PINNER_REL, PREVIEW_SHORTCUT_REL, RESOLVER_REL):
                os.makedirs(os.path.join(tree, os.path.dirname(rel)), exist_ok=True)
            open(os.path.join(tree, ACTIVITY_REL), "w", encoding="utf-8").write(act)
            open(os.path.join(tree, SHEET_REL), "w", encoding="utf-8").write(
                sheet + SHEET_DECODE_TILE)
            open(os.path.join(tree, PINNER_REL), "w", encoding="utf-8").write(pinner)
            open(os.path.join(tree, PREVIEW_SHORTCUT_REL), "w", encoding="utf-8").write(preview)
            open(os.path.join(tree, RESOLVER_REL), "w", encoding="utf-8").write(resolver)
            return tree

        for name, act, sheet, expected_pass in cases:
            tree = write_tree(name, act, sheet, COMPLIANT_PINNER, COMPLIANT_PREVIEW_SHORTCUT)
            rc = run_on_tree(tree)
            passed = rc == 0
            if passed != expected_pass:
                print(f"[webapp-asset-url] SELFTEST FAIL case={name} "
                      f"expected={'pass' if expected_pass else 'fail'} got={passed}")
                ok = False
            else:
                print(f"[webapp-asset-url] selftest ok case={name} ({'pass' if passed else 'fail'})")
        # [dv21-B04 B-08] each targeted violation trips its OWN arm only.
        for name, pinner, preview, sheet_extra, want in (
            ("b08-pin-silent-decline", VIOLATION_ARM8_SILENT_DECLINE,
             COMPLIANT_PREVIEW_SHORTCUT, "", (True, False, False)),
            ("b08-pin-no-capture", COMPLIANT_PINNER, VIOLATION_ARM8_NO_CAPTURE,
             "", (False, True, False)),
            ("b08-decode-in-remember", COMPLIANT_PINNER, COMPLIANT_PREVIEW_SHORTCUT,
             VIOLATION_ARM9_REMEMBER, (False, False, True)),
            ("b08-decode-no-io-hop", COMPLIANT_PINNER, COMPLIANT_PREVIEW_SHORTCUT,
             VIOLATION_ARM9_NO_IO, (False, False, True)),
            ("b08-decode-hoisted-out-of-io", COMPLIANT_PINNER, COMPLIANT_PREVIEW_SHORTCUT,
             VIOLATION_ARM9_DECODE_BEFORE_IO, (False, False, True)),
        ):
            tree = os.path.join(tmp, name)
            for rel in (ACTIVITY_REL, SHEET_REL, PINNER_REL, PREVIEW_SHORTCUT_REL):
                os.makedirs(os.path.join(tree, os.path.dirname(rel)), exist_ok=True)
            open(os.path.join(tree, ACTIVITY_REL), "w", encoding="utf-8").write(COMPLIANT_ACTIVITY)
            open(os.path.join(tree, SHEET_REL), "w", encoding="utf-8").write(
                COMPLIANT_SHEET + (sheet_extra or SHEET_DECODE_TILE))
            open(os.path.join(tree, PINNER_REL), "w", encoding="utf-8").write(pinner)
            open(os.path.join(tree, PREVIEW_SHORTCUT_REL), "w", encoding="utf-8").write(preview)
            got = (not check_pin_arm8(pinner), not check_pin_arm8(preview),
                   not check_sheet_arm9(
                       open(os.path.join(tree, SHEET_REL), encoding="utf-8").read()))
            others = (check_activity_arm1(COMPLIANT_ACTIVITY)
                      and check_activity_arm2(COMPLIANT_ACTIVITY)
                      and check_sheet_arm3(COMPLIANT_SHEET)
                      and check_sheet_arm4(COMPLIANT_SHEET)
                      and check_sheet_arm7(COMPLIANT_SHEET))
            if got == want and others:
                print(f"[webapp-asset-url] selftest ok {name}: "
                      f"arm8a/8b/9 red={got}, arm1-7 green")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL {name}: "
                      f"got={got} want={want} arm1-7 green={others}")
                ok = False
        # [da1032-A] Precision: the arms must key on the decision point, not on
        # "anything with the word fullscreen goes red". Each *targeted* violation
        # trips its own arm and leaves the other green. The pre-fix double-source
        # shape is the one case that legitimately trips BOTH (there is no single
        # state left for arm6 to observe) — recorded as such, not hidden.
        for name, act, want in (
            ("double source (no single state left)", VIOLATION_ARM5_DOUBLE_SOURCE, (True, True)),
            ("second remember copy", VIOLATION_ARM5_SECOND_COPY, (True, False)),
            ("silent callback", VIOLATION_ARM6_SILENT_CALLBACK, (False, True)),
        ):
            bad5 = not check_activity_arm5(act)
            bad6 = not check_activity_arm6(act)
            if (bad5, bad6) == want:
                print(f"[webapp-asset-url] selftest ok precision {name}: "
                      f"arm5 red={bad5}, arm6 red={bad6}")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL precision {name}: "
                      f"got arm5 red={bad5}, arm6 red={bad6} (want {want})")
                ok = False
        # [dv14-B2] arm7 precision: each targeted title violation trips arm7
        # only (arm3 cleanup + arm4 pin-failure stay green).
        for name, sheet in (
            ("unguarded backfill", VIOLATION_ARM7_UNGUARDED_BACKFILL),
            ("writer without the flag", VIOLATION_ARM7_WRITER_NO_FLAG),
            ("empty seed", VIOLATION_ARM7_EMPTY_SEED),
        ):
            bad7 = not check_sheet_arm7(sheet)
            others = check_sheet_arm3(sheet) and check_sheet_arm4(sheet)
            if bad7 and others:
                print(f"[webapp-asset-url] selftest ok precision {name}: "
                      f"arm7 red={bad7}, arm3/arm4 green")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL precision {name}: "
                      f"arm7 red={bad7}, arm3/arm4 green={others}")
                ok = False
        # [dv26-E8] S-08 — mount-scope cold start. One arm per criterion, plus
        # a compliant tree (all green) and two violation shapes that a
        # file-level grep would call clean (orphan refresh, snapshot on Main).
        for name, resolver, activity, want in (
            ("e8-compliant", COMPLIANT_RESOLVER, COMPLIANT_ACTIVITY, []),
            ("e8-no-retry pre-fix resolver",
             VIOLATION_ARM10_NO_RETRY, COMPLIANT_ACTIVITY, ["arm10"]),
            ("e8-orphan-refresh", VIOLATION_ARM10_ORPHAN_REFRESH, COMPLIANT_ACTIVITY, ["arm10"]),
            ("e8-expr-body swallows the sibling declaration",
             VIOLATION_ARM10_EXPR_SWALLOW, COMPLIANT_ACTIVITY, ["arm10"]),
            ("e8-unguarded-missing",
             COMPLIANT_RESOLVER, VIOLATION_ARM11_UNGUARDED_MISSING, ["arm11"]),
            ("e8-main-thread-resolve",
             COMPLIANT_RESOLVER, VIOLATION_ARM12_MAIN_THREAD_RESOLVE, ["arm12"]),
            ("e8-main-thread-snapshot",
             COMPLIANT_RESOLVER, VIOLATION_ARM12_MAIN_THREAD_SNAPSHOT, ["arm12"]),
        ):
            tree = write_tree(name, activity, COMPLIANT_SHEET, COMPLIANT_PINNER,
                              COMPLIANT_PREVIEW_SHORTCUT, resolver=resolver)
            got = evaluate_tree(tree)
            if sorted(got) == sorted(want):
                print(f"[webapp-asset-url] selftest ok {name}: "
                      f"red={','.join(got) if got else 'none'}")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL {name}: "
                      f"got={got} want={want}")
                ok = False
        # [deep-audit-1048 B-06] arm13/arm14: each targeted violation trips its
        # OWN arm (the other stays green), and the compliant sheet is green —
        # the arms key on the sheet's own state names, so a rename must not
        # matter (checked by the "renamed state" case below).
        for name, sheet, want in (
            ("b06-no-inflight", COMPLIANT_SHEET.replace(
                "enabled = pinActionEnabled(copiedFile != null, titleText.isNotBlank(), "
                "isPinning, sheetClosed)",
                "enabled = copiedFile != null && titleText.isNotBlank()"), (True, False)),
            ("b06-no-click-guard", COMPLIANT_SHEET.replace(
                "            if (isPinning) return@Button\n", ""), (True, False)),
            ("b06-dead-click-guard", COMPLIANT_SHEET.replace(
                "            if (isPinning) return@Button\n",
                "            if (false && isPinning) return@Button\n"), (True, False)),
            ("b06-dead-close-guard", COMPLIANT_SHEET.replace(
                "if (!pinShouldCommit(closed = sheetClosed, inFlight = isPinning)) {",
                "if (false && !pinShouldCommit(closed = sheetClosed, "
                "inFlight = isPinning)) {"), (False, True)),
            ("b06-no-close-latch",
             re.sub(r"^\s*sheetClosed = true\n", "", COMPLIANT_SHEET, flags=re.M),
             (False, True)),
            ("b06-guard-after-create", COMPLIANT_SHEET.replace(
                "                    if (!pinShouldCommit(closed = sheetClosed, "
                "inFlight = isPinning)) {\n"
                "                        cleanupCopiedAttachment()\n"
                "                        return@launch\n"
                "                    }\n"
                "                    val entity = app.webAppShortcutRepository.create("
                "htmlPath = \"x\")\n",
                "                    val entity = app.webAppShortcutRepository.create("
                "htmlPath = \"x\")\n"
                "                    if (!pinShouldCommit(closed = sheetClosed, "
                "inFlight = isPinning)) {\n"
                "                        cleanupCopiedAttachment()\n"
                "                        return@launch\n"
                "                    }\n"), (False, True)),
            ("b06-renamed-states stay green",
             COMPLIANT_SHEET.replace("isPinning", "busy").replace("sheetClosed", "gone"),
             (False, False)),
        ):
            bad13 = not check_sheet_arm13(sheet)
            bad14 = not check_sheet_arm14(sheet)
            if (bad13, bad14) == want:
                print(f"[webapp-asset-url] selftest ok {name}: "
                      f"arm13 red={bad13}, arm14 red={bad14}")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL {name}: "
                      f"arm13 red={bad13}, arm14 red={bad14} (want {want})")
                ok = False
        if ok:
            print("WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS")
            return 0
        print("WEBAPP_ASSET_URL_CLEANUP_SELFTEST=FAIL")
        return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    if "--selftest" in sys.argv[1:]:
        return selftest()
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    return run_on_tree(sys.argv[1])


if __name__ == "__main__":
    sys.exit(main())
