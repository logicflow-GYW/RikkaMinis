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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_ast import const_bool, if_blocks, match_bracket  # noqa: E402
from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    contains_ident,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
    value_sources,
)

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
    """`(...)` slice. Bracket matching is the shared `match_bracket`: the local
    counter it replaces walked past a `)` inside a masked literal."""
    close = match_bracket(code, open_idx)
    return code[open_idx:close + 1] if close > 0 else ""


def _brace_block(code: str, open_idx: int) -> str:
    """`{...}` slice (same contract as `_paren_block`; "" = fail-closed)."""
    close = match_bracket(code, open_idx)
    return code[open_idx:close + 1] if close > 0 else ""


def _named_arg(code: str, args, name: str):
    """(start, end) of the `name = …` argument inside `call_args` spans."""
    for a, b in args:
        m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", code[a:b])
        if m and m.group(1) == name:
            return (a + m.end(), b)
    return None


def _local_rhs(code: str, name: str, before: int):
    """Right-hand side of the nearest `val/var name =` declared before [before]."""
    rhs = None
    for m in re.finditer(r"\b(?:val|var)\s+" + re.escape(name) + r"\b[^=\n]*=(?!=)", code):
        if m.start() >= before:
            break
        rhs = code[m.end():expr_end(code, m.end())].strip()
    return rhs


def _value_origins(code: str, expr: str, before: int):
    """"The call exists" is not "the call's result is the value".

    A02-6 keeps `toAssetLoaderUrl(…)` alive, binds its result to an unused local
    and hands `loadUrl` a hand-built string. Following one local `val` hop is
    what turns the criterion from a spelling search into a dataflow judgement.
    """
    out = []
    for src_expr in value_sources(expr):
        out.append(src_expr)
        name = src_expr.strip()
        if re.fullmatch(r"[A-Za-z_]\w*", name):
            rhs = _local_rhs(code, name, before)
            if rhs:
                out.extend(value_sources(rhs))
    return out


LOAD_URL_RE = re.compile(r"\bloadUrl\s*\(")


def _load_url_bound_to_single_source(code: str) -> bool:
    """Every `loadUrl(x)` argument must evaluate to `toAssetLoaderUrl(…)`."""
    sites = [m.start() for m in LOAD_URL_RE.finditer(code)]
    if not sites:
        return False                    # anchor moved → fail-closed
    for at in sites:
        args = call_args(code, code.index("(", at))
        if len(args) != 1:
            return False
        arg = code[args[0][0]:args[0][1]]
        if not any("toAssetLoaderUrl(" in o for o in _value_origins(code, arg, at)):
            return False
    return True


def _branch_end(text: str, start: int) -> int:
    """End of one branch body (block, or a single expression up to `else`/EOL)."""
    n = len(text)
    i = start
    while i < n and text[i] in " \t":
        i += 1
    if i < n and text[i] == "{":
        close = match_bracket(text, i)
        return close + 1 if close > 0 else n
    depth = 0
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return i
            depth -= 1
        elif depth == 0:
            if text.startswith("else", i) and not re.match(r"\w", text[i + 4:i + 5] or " "):
                return i
            if ch == "\n":
                return i
        i += 1
    return n


def _else_extent(text: str, after_then: int):
    """(start, end) of an `else` clause that follows, or (None, None)."""
    n = len(text)
    i = after_then
    while i < n and text[i] in " \t\r\n":
        i += 1
    if not text.startswith("else", i):
        return (None, None)
    j = i + 4
    while j < n and text[j] in " \t":
        j += 1
    if j < n and text[j] == "{":
        close = match_bracket(text, j)
        return (j, close + 1 if close > 0 else n)
    return (j, _branch_end(text, j))


def _dead_spans(text: str):
    """Spans of branches a constant condition proves unreachable.

    Shared `path_feasible` covers block-bodied `if`/`when`; it does NOT cover an
    expression-shaped `if`, because `_branch_extent` ends a braceless branch at
    the first depth-0 newline and therefore swallows a same-line `else` into the
    then-branch. That is exactly A02-3's second shape
    (`value = if (true) null else withContext(IO) { decode(…) }`), so the gap is
    closed here and registered for the shared module in the report (§6).
    """
    spans = []
    for m in re.finditer(r"\bif\s*\(", text):
        p_close = match_bracket(text, m.end() - 1)
        if p_close < 0:
            continue
        val = const_bool(text[m.end():p_close])
        if val is None:
            continue
        then_end = _branch_end(text, p_close + 1)
        else_start, else_end = _else_extent(text, then_end)
        if val is False:
            spans.append((p_close + 1, then_end))
        elif else_start is not None:
            spans.append((else_start, else_end))
    return spans


def _live_at(text: str, pos: int) -> bool:
    """Is [pos] on a reachable path? `path_feasible` + the expression-`else` fix."""
    if not path_feasible(text, pos):
        return False
    return not any(a <= pos < b for a, b in _dead_spans(text))


_EXIT_RE = re.compile(r"\breturn\b(?:\s*@\s*[A-Za-z_]\w*)?|\bthrow\b")


def _exits_block(text: str) -> bool:
    """Does this branch body actually leave the lambda/function?

    `return@Button` / `return@launch` / `throw` do; `{}` (A02-2's emptied guard)
    and `Unit` do not.
    """
    return _EXIT_RE.search(text) is not None


def _if_sites(text: str):
    """`if` structures WITH the condition offset: [(at, cond, then, else)].

    `if_blocks` yields extents but not the `if` position; the guard criteria need
    the position (order vs the staging IO, path feasibility), so both iterators
    walk the same text in the same order and are zipped.
    """
    out = []
    sites = []
    for m in re.finditer(r"\bif\s*\(", text):
        p_close = match_bracket(text, m.end() - 1)
        if p_close > 0:
            sites.append(m.start())
    for at, (cond, t_start, t_end, e_start, e_end) in zip(sites, if_blocks(text)):
        out.append((at, cond, t_start, t_end, e_start, e_end))
    return out



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
    """No raw file name in the asset-loader URL, and every `loadUrl` argument
    must evaluate to `toAssetLoaderUrl(…)` (A02-6: an unused
    `toAssetLoaderUrl(…)` call satisfied "the function appears")."""
    code = code_view(src)
    if re.search(r"\$\{\s*file\s*\.\s*name\s*\}", literal_view(src)):
        return False
    return _load_url_bound_to_single_source(code)



def check_activity_arm2(src: str) -> bool:
    """No second implementation of the *asset path* encoding: the value handed
    to `loadUrl` must come from `toAssetLoaderUrl` (the single source), and no
    URLEncoder/Uri.encode result may be pasted into it."""
    code = code_view(src)
    if "URLEncoder.encode(" in code or re.search(r"\bUri\s*\.\s*encode\s*\(\s*file\s*\.", code):
        return False
    return _load_url_bound_to_single_source(code)



def check_activity_arm5(src: str) -> bool:
    """[da1032-A] Exactly one fullscreen snapshot state, and no second copy:
    no plain `isFullscreen` boolean field, no residual `initialFullscreen`,
    no delegated `var <fullscreen-ish> by remember`, no remember keyed on
    fullscreen."""
    code = code_view(src)
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
    code = code_view(src)
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
    """The universal hook must funnel cleanup — LIVE.

    A02-5 kept `cleanupCopiedAttachment()` textually inside `onDispose` and
    wrapped it in `if (false)`: the substring test passed while the copy leaked
    on every teardown. Same for arm4 below (the self-doubt in the same witness).
    """
    code = code_view(src)
    if "cleanupCopiedAttachment()" not in code:
        return False
    hooks = [r"onDispose\s*\{"]
    if "DisposableEffect(" not in code:
        hooks.append(r"\bonClick\s*=\s*\{")
    for header in hooks:
        for m in re.finditer(header, code):
            brace = code.index("{", m.end() - 1)
            close = match_bracket(code, brace)
            if close < 0:
                continue
            block = code[brace:close + 1]
            for site in re.finditer(r"\bcleanupCopiedAttachment\s*\(\s*\)", block):
                if _live_at(block, site.start()):
                    return True
    return False



def check_sheet_arm4(src: str) -> bool:
    """Pin-failure branch must delete the DB row — and the delete must be on a
    reachable path (`if (false) …delete(…)` is not a delete)."""
    code = code_view(src)
    for m in re.finditer(r"webAppShortcutRepository\s*\.\s*delete\s*\(", code):
        if _live_at(code, m.start()):
            return True
    return False









def check_sheet_arm7(src: str) -> bool:
    """[dv14-B2] Title seed + guarded backfill (AddToHomeSheet.kt only).

    Decision points, not call shapes:
      - the `titleText` snapshot state is seeded from the file name (so the
        very first composition already shows the fallback title and a slow
        async parse cannot win the race by blanking the field);
      - the text-field writer flips the user-edited guard;
      - the `LaunchedEffect(parsedTitle)` body is guarded by that flag.
    Fail-closed on any missing anchor."""
    code = code_view(src)
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




def check_sheet_arm13(src: str) -> bool:
    """[deep-audit-1048 B-06] C-PIN: the CTA carries an in-flight state.

    Decision point (not the call spelling): the CTA's `enabled` expression
    mentions a `remember` state, and the CTA's own `onClick` body GUARDS on that
    state before assigning it true — and the guard must actually leave the
    handler. Two witnesses:
      * A02-1  `if (!true && isPinning) return@Button` — the dead-condition
        whitelist compared operands to `true`/`false`, and `!true` is neither
        (`guard_feasible` folds it, `(false) && …` too);
      * A02-2  `if (isPinning) {}` — the old tail test accepted a bare `{`, so an
        empty block counted as a guard.
    Fail-closed when the sheet has no `Button(enabled = ...)` at all."""
    code = code_view(src)
    states = _delegated_states(code)
    if not states:
        return False
    for m in BUTTON_CALL_RE.finditer(code):
        args = call_args(code, code.index("(", m.end() - 1))
        enabled = _named_arg(code, args, "enabled")
        if enabled is None:
            continue
        named = {w for w in re.findall(r"\b[A-Za-z_]\w*\b", code[enabled[0]:enabled[1]])} & states
        if not named:
            continue
        click = _named_arg(code, args, "onClick")
        if click is None:
            continue
        click_body = code[click[0]:click[1]]
        for name in named:
            set_at = re.search(r"\b%s\s*=\s*true\b" % re.escape(name), click_body)
            if not set_at:
                continue
            head = click_body[:set_at.start()]
            for at, cond, t_start, t_end, _es, _ee in _if_sites(head):
                if not contains_ident(cond, name):
                    continue
                if not guard_feasible(cond):
                    continue          # `!true && guard` / `(false) && guard`
                if not _live_at(head, at):
                    continue
                if _exits_block(head[t_start:t_end]):
                    return True
    return False



def check_sheet_arm14(src: str) -> bool:
    """[deep-audit-1048 B-06] C-CLOSE: a cancelled sheet must not commit.

    A `remember` boolean latched true by a teardown hook must be consumed, in
    the coroutine that calls `ShortcutPinner.pin(`, after the staging
    `withContext(Dispatchers.IO)` and before both the row insert and the pin —
    and the guard must TERMINATE (A02-2 emptied its block: the condition stayed
    live and correct, the `return@launch` was deleted, and the row was still
    inserted for a sheet the user had already closed)."""
    code = code_view(src)
    states = _delegated_states(code)
    if not states:
        return False
    hooks = []
    for m in re.finditer(r"onDismissRequest\s*=\s*\{", code):
        hooks.append(_brace_block(code, code.index("{", m.end() - 1)))
    for m in re.finditer(r"onDispose\s*\{", code):
        hooks.append(_brace_block(code, code.index("{", m.end() - 1)))
    for m in re.finditer(r"onClick\s*=\s*\{", code):
        block = _brace_block(code, code.index("{", m.end() - 1))
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
        brace = code.index("{", m.end() - 1)
        close = match_bracket(code, brace)
        if close < 0:
            continue
        body = code[brace:close + 1]
        pin_at = body.find("ShortcutPinner.pin(")
        if pin_at < 0:
            continue
        create_at = body.find("webAppShortcutRepository.create(")
        if create_at < 0:
            return False
        io_at = body.find("withContext(Dispatchers.IO)")
        if io_at < 0:
            return False
        for at, cond, t_start, t_end, e_start, e_end in _if_sites(body):
            if at >= create_at or at < io_at:
                continue      # after the insert, or a guard about the target
            if not any(contains_ident(cond, n) for n in latched):
                continue
            if not guard_feasible(cond):
                continue          # `false && !pinShouldCommit(…)`
            if not _live_at(body, at):
                continue
            then_body = body[t_start:t_end]
            else_body = body[e_start:e_end] if e_start is not None else ""
            if _exits_block(then_body) or _exits_block(else_body):
                return at < pin_at
    return False



# The decode FAMILY, not one spelling: A02-3 hoisted the real work to
# `BitmapFactory.decodeStream(…)` while leaving a dead `loadBitmapFromUri(…)`
# call behind. Any of these decodes on the composition thread is the defect.
DECODE_CALL_RE = re.compile(
    r"\b(?:decodeFileBounded|loadBitmapFromUri|BitmapFactory\s*\.\s*decode\w+)\s*\("
)


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
        open_paren = code.find("(", m.start())
        if open_paren < 0:
            continue
        paren = _paren_block(code, open_paren)
        if not paren:
            continue
        brace = code.find("{", open_paren + len(paren))
        if brace < 0:
            continue
        body = _brace_block(code, brace)
        if not body.endswith("}"):
            continue
        out.append((brace, body))
    return out


def check_sheet_arm9(src: str) -> bool:
    """[dv21-B04 B-08] No decode on the composition thread.

    Two decision points:
      - nothing decodes inside a REACHABLE `remember(...) { }` body (that body
        runs on the composition thread);
      - any decode in a `produceState` body sits inside a `withContext(
        Dispatchers.IO)` block that is itself on a reachable path — A02-3 parked
        a dead `if (false) { withContext(IO) { loadBitmapFromUri(…) } }` next to
        a real `BitmapFactory.decodeStream(…)` on the composition thread, and
        the "decode is inside some IO block" test was satisfied by the dead one.
    Fail-closed when the sheet carries no decode call at all (anchor moved)."""
    code = code_view(src)
    sites = [m.start() for m in DECODE_CALL_RE.finditer(code)]
    if not sites:
        return False
    for brace, body in _block_body(code, r"\bremember\s*\("):
        for s in sites:
            if brace < s < brace + len(body) and _live_at(body, s - brace):
                return False
    for brace, body in _block_body(code, r"\bproduceState\s*(?:<[^>{]*>)?\s*\("):
        for s in sites:
            if brace < s < brace + len(body):
                if not enclosing_live_io(code, s):
                    return False
    return True



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
    code = code_view(src)
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




# --- [dv26-E8] S-08 mount-scope cold start --------------------------------

FUN_DECL_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\(")
SCOPE_MOUNT_ARM_RE = re.compile(r"\bSCOPE_MOUNT\s*->\s*([^\n}]*)")
IDENT_CALL_RE = re.compile(r"\b([A-Za-z_]\w*)\s*\(")
RESOLVE_CALL = "WebAppPathResolver.resolve("




def fun_spans(code: str):
    """{name: body} for every `fun` in [code] (already `code_view`ed).

    Block bodies become brace slices; expression bodies run to the end of the
    expression (`expr_end`), so a one-line `= null` cannot swallow the NEXT
    declaration and lend its calls to the wrong function (witness arm10).
    A body that cannot be delimited is skipped — for arm10 that fails CLOSED (a
    missing edge only removes a reachable function).
    """
    spans = {}
    for m in FUN_DECL_RE.finditer(code):
        name = m.group(1)
        if name in spans:
            continue
        open_paren = code.find("(", m.end() - 1)
        p_close = match_bracket(code, open_paren) if open_paren >= 0 else -1
        if p_close < 0:
            continue
        i = p_close + 1
        n = len(code)
        while i < n and code[i] not in "={":
            if code[i] == "\n":
                i = n
                break
            i += 1
        if i >= n:
            continue
        if code[i] == "=":
            spans[name] = code[i:expr_end(code, i + 1)]
        else:
            body = _brace_block(code, i)
            if body.endswith("}"):
                spans[name] = body
    return spans


def reachable_bodies(bodies, seeds):
    """Names of every function reachable from [seeds] by call name."""
    seen = []
    stack = [x for x in seeds if x in bodies]
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        seen.append(name)
        for m in IDENT_CALL_RE.finditer(bodies[name]):
            callee = m.group(1)
            if callee in bodies and callee not in seen:
                stack.append(callee)
    return seen


def _has_live_call(body_texts, name):
    """Is there a REACHABLE `name(` call across these bodies?

    A02-4 kept the call and wrapped it in `if (false)`: the closure-string test
    found `applyMountedFoldersSnapshot(` and the cold-start refresh stayed dead.
    """
    pat = re.compile(r"\b" + re.escape(name) + r"\s*\(")
    for text in body_texts:
        for m in pat.finditer(text):
            if _live_at(text, m.start()):
                return True
    return False


def check_mount_arm_snapshot_retry(src: str) -> bool:
    """arm10 — `SCOPE_MOUNT` reaches "refresh the snapshot, then resolve again"."""
    code = code_view(src)
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
    closure = [bodies[n] for n in reachable_bodies(bodies, seeds)]
    return (_has_live_call(closure, "resolveHostPath")
            and _has_live_call(closure, "applyMountedFoldersSnapshot"))







def enclosing_io_block(code: str, idx: int) -> bool:
    """Is [idx] inside a `withContext(Dispatchers.IO) { … }` block?"""
    for m in re.finditer(r"\bwithContext\s*\(", code):
        open_paren = code.index("(", m.end() - 1)
        paren = _paren_block(code, open_paren)
        if "Dispatcher" not in paren or not re.search(r"\bIO\b", paren):
            continue
        brace = code.find("{", open_paren + len(paren))
        if brace < 0:
            continue
        close = match_bracket(code, brace)
        if close < 0:
            continue
        if brace < idx < close:
            return True
    return False


def enclosing_live_io(code: str, idx: int) -> bool:
    """...and the IO hop itself is on a reachable path (not `if (false)`)."""
    return _live_at(code, idx) and enclosing_io_block(code, idx)



def check_activity_failure_guard(src: str) -> bool:
    """arm11 — `renderSourceMissing(` is the guarded failure of the resolve."""
    code = code_view(src)
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
    code = code_view(src)
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
    try:
        return _evaluate_sources(act, sheet, pinner, preview, resolver)
    except KotlinLexError as exc:
        # Fail-closed: a tree the shared lexer cannot read must never pass.
        return ["kotlin-lex:" + str(exc).replace(" ", "_")]


def _evaluate_sources(act, sheet, pinner, preview, resolver):
    """Arm body of `evaluate_tree`, split out so the lexer error is caught."""
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
        # [B6-t04] the six A02 witnesses. Every shape below passed this gate
        # before the batch; each must now be red on the arm it attacks — and the
        # precision checks must keep a live sibling green (no over-red).
        a02_1 = COMPLIANT_SHEET.replace(
            "            if (isPinning) return@Button\n",
            "            if (!true && isPinning) return@Button\n")
        a02_1b = COMPLIANT_SHEET.replace(
            "            if (isPinning) return@Button\n",
            "            if ((false) && isPinning) return@Button\n")
        a02_2 = COMPLIANT_SHEET.replace(
            "                    if (!pinShouldCommit(closed = sheetClosed, "
            "inFlight = isPinning)) {\n"
            "                        cleanupCopiedAttachment()\n"
            "                        return@launch\n"
            "                    }\n",
            "                    if (!pinShouldCommit(closed = sheetClosed, "
            "inFlight = isPinning)) {\n"
            "                    }\n")
        a02_2b = COMPLIANT_SHEET.replace(
            "            if (isPinning) return@Button\n",
            "            if (isPinning) { }\n")
        for name, sheet, want in (
            ("A02-1 `!true && isPinning` dead click guard", a02_1, (True, False)),
            ("A02-1b `(false) && isPinning` dead click guard", a02_1b, (True, False)),
            ("A02-2 emptied close-guard block", a02_2, (False, True)),
            ("A02-2b `if (isPinning) { }` empty click guard", a02_2b, (True, False)),
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
        # A02-3: a dead IO block next to a real composition-thread decode.
        a02_3 = """
@Composable
private fun GalleryTile(galleryUri: Uri) {
    val bmp by produceState<Bitmap?>(initialValue = null, galleryUri) {
        if (false) { withContext(Dispatchers.IO) { loadBitmapFromUri(context, galleryUri) } }
        value = android.graphics.BitmapFactory.decodeStream(
            context.contentResolver.openInputStream(galleryUri),
        )
    }
}
"""
        # ...and the same defect spelled as a constant-dead else branch.
        a02_3b = """
@Composable
private fun GalleryTile2(galleryUri: Uri) {
    val bmp by produceState<Bitmap?>(initialValue = null, galleryUri) {
        value = if (true) null else withContext(Dispatchers.IO) {
            loadBitmapFromUri(context, galleryUri)
        }
    }
}
"""
        for name, extra in (("A02-3 dead IO + BitmapFactory on composition thread", a02_3),
                            ("A02-3b decode only in a constant-dead else", a02_3b)):
            bad = not check_sheet_arm9(COMPLIANT_SHEET + SHEET_DECODE_TILE + extra)
            clean = check_sheet_arm9(COMPLIANT_SHEET + SHEET_DECODE_TILE)
            if bad and clean:
                print(f"[webapp-asset-url] selftest ok {name}: arm9 red={bad}, "
                      f"live-IO sibling green")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL {name}: arm9 red={bad}, "
                      f"live-IO sibling green={clean}")
                ok = False
        # A02-4: the refresh call is present but behind `if (false)`.
        a02_4 = COMPLIANT_RESOLVER.replace(
            "            refresh = { PRootKernel.applyMountedFoldersSnapshot(context) },",
            "            refresh = { if (false) "
            "PRootKernel.applyMountedFoldersSnapshot(context) },")
        for name, resolver, activity, want in (
            ("A02-4 dead refresh closure", a02_4, COMPLIANT_ACTIVITY, ["arm10"]),
        ):
            tree = write_tree(name, activity, COMPLIANT_SHEET, COMPLIANT_PINNER,
                              COMPLIANT_PREVIEW_SHORTCUT, resolver=resolver)
            got = evaluate_tree(tree)
            if sorted(got) == sorted(want):
                print(f"[webapp-asset-url] selftest ok {name}: red={','.join(got)}")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL {name}: got={got} want={want}")
                ok = False
        # A02-5: cleanup / row-delete kept textually but wrapped in `if (false)`.
        a02_5 = COMPLIANT_SHEET.replace(
            "        onDispose {\n            sheetClosed = true\n"
            "            cleanupCopiedAttachment()\n        }",
            "        onDispose {\n            sheetClosed = true\n"
            "            if (false) cleanupCopiedAttachment()\n        }")
        a02_5b = COMPLIANT_SHEET.replace(
            "runCatching { app.webAppShortcutRepository.delete(entity.id) }",
            "runCatching { if (false) app.webAppShortcutRepository.delete(entity.id) }")
        for name, sheet, want in (
            ("A02-5 dead cleanup in onDispose", a02_5, (True, False)),
            ("A02-5b dead row delete on pin failure", a02_5b, (False, True)),
        ):
            bad3 = not check_sheet_arm3(sheet)
            bad4 = not check_sheet_arm4(sheet)
            if (bad3, bad4) == want:
                print(f"[webapp-asset-url] selftest ok {name}: "
                      f"arm3 red={bad3}, arm4 red={bad4}")
            else:
                print(f"[webapp-asset-url] SELFTEST FAIL {name}: "
                      f"arm3 red={bad3}, arm4 red={bad4} (want {want})")
                ok = False
        # A02-6: `toAssetLoaderUrl` result dropped, `loadUrl` fed a hand-built URL.
        a02_6 = COMPLIANT_ACTIVITY.replace(
            '        val url = toAssetLoaderUrl("file://" + file.absolutePath)\n'
            "            ?: run {\n"
            "                renderSourceMissing(sourceSessionId = null)\n"
            "                return\n"
            "            }\n",
            '        val ignoredUrl = toAssetLoaderUrl("file://" + file.absolutePath)\n'
            '        val url = "https://" + ASSET_LOADER_HOST + "/" + file.name\n')
        bad1 = not check_activity_arm1(a02_6)
        bad2 = not check_activity_arm2(a02_6)
        clean = check_activity_arm1(COMPLIANT_ACTIVITY) and check_activity_arm2(COMPLIANT_ACTIVITY)
        if bad1 and bad2 and clean:
            print(f"[webapp-asset-url] selftest ok A02-6 dropped encoder result: "
                  f"arm1/arm2 red={bad1, bad2}, bound sibling green")
        else:
            print(f"[webapp-asset-url] SELFTEST FAIL A02-6 dropped encoder result: "
                  f"arm1 red={bad1} arm2 red={bad2} sibling green={clean}")
            ok = False
        if ok:
            print("WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS")
            print("WEBAPP_ASSET_URL_CLEANUP_WITNESS_ARMS=PASS (10 arms: "
                  "A02-1/1b/2/2b/3/3b/4/5/5b/6)")
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
