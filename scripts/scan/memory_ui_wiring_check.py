#!/usr/bin/env python3
"""[fix/da1023-G] memory_ui_wiring_check

deep-audit-1023 ISSUE-07 gate. Two files, three defect surfaces:

  A. MemoryManagementScreen.kt — `MemoryFileRow` received a nullable
     `onDelete` callback from its caller (built for every non-global,
     non-SOUL file) and the screen carries a full delete-confirmation
     dialog, but the row body NEVER consumed the callback: the daily-log
     delete feature was 100% unreachable dead code. The gate requires the
     row body to actually invoke `onDelete` from an IconButton click
     (code anchors only; the KDoc that explains the wiring must not
     satisfy it).

  B. MemoryManagementScreen.kt — the confirm path ran blocking
     `deleteFile()` + full `listAllFiles()` on the main thread (MemoryRepository
     has no suspend surface). Every `memoryRepository.deleteFile(` /
     `memoryRepository.listAllFiles(` site in the file must now sit inside
     a `withContext(Dispatchers.IO)` region, and the confirm block must go
     through `scope.launch` (dialog state cleared first, result published
     back on the composition thread).

  C. SessionMemorySheet.kt — `autoItems` was `remember(memoryRepository,
     context) { buildAutoInjectedItems(...) }`: both keys stable for the
     life of the sheet, so the cached snapshot (full file contents!) went
     stale after every in-sheet edit. The gate requires:
       - a refresh-tick remember key on the very remember that builds the
         auto items;
       - the tick bumped on ALL THREE success paths that rewrite the
         backing files (AutoFile save / Write-record save / Write revoke).

  D. [deep-audit-1029 ISSUE-03] MemoryFileEditScreen (same file as A/B) —
     this screen exists for 200KB+ files, yet its read ran inside
     `remember { mutableStateOf(memoryRepository.readFile(...)) }` (the
     composition thread of the first frame) and its save ran inline in the
     Save click. The criterion set:
       - `memoryRepository.readFile(` / `.saveFile(` must sit inside a
         `withContext(Dispatchers.IO)` region (same rule as B, extended to
         the two edit-screen call sites);
       - the edit screen must read via a `LaunchedEffect` and write via a
         coroutine scope (`scope.launch`) rather than inline;
       - the IO region that writes `saveFile` must also call
         `SoulStore.refreshCache(` — this raw path bypasses
         `SoulStore.save()`, so without it a SOUL.md edit leaves
         `cachedMetadata` / `writeVersion` on the pre-edit values for the
         chat-bubble header and the Settings → Soul editors (the twin chat
         memory sheet already refreshes; this screen did not).
     D is fail-closed on the `MemoryFileEditScreen` anchor: losing the
     function is gate ROT, not a pass.

  E. [dv16 B-08] SessionMemorySheet.kt — `dailyAutoItemFor` hard-coded the
     200-line injection cap while the real cap is
     `AgentRuntimeLimitsPrefs.memoryInjectLines()` (user-tunable): after the
     user changes the cap the sheet's `N/M lines injected` label lied about
     what was actually injected. The criterion set:
       - the function body must not contain the bare `200` literal (the cap
         flows through the `maxInjectLines` parameter instead);
       - the signature must carry a `maxInjectLines` parameter (default-arg
         or explicit-arg form both bind the decision);
       - the sheet must reference `AgentRuntimeLimitsPrefs.memoryInjectLines()`
         so the shown cap tracks the real one.
     Fail-closed on the function anchor: losing it is gate ROT.

Comments are stripped before every judgment (the fix ships with prose
comments that name each criterion; they must not carry the gate).

Usage: python3 memory_ui_wiring_check.py <repo_root>
       python3 memory_ui_wiring_check.py --root <repo_root>
       python3 memory_ui_wiring_check.py --selftest
Exit 0 = pass, 1 = violation, 2 = usage error.
"""
import re
import sys

MEM_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/MemoryManagementScreen.kt"
MEM_SHEET = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/SessionMemorySheet.kt"
MARKER = "MEMORY_UI_WIRING_SELFTEST=PASS"


def strip_comments(src: str) -> str:
    """Remove // and /* */ comments, honouring string literals."""
    out = []
    i = 0
    n = len(src)
    in_str = False
    while i < n:
        c = src[i]
        if in_str:
            if c == "\\":
                out.append(src[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _match_paren(src: str, open_idx: int):
    depth = 0
    for i in range(open_idx, len(src)):
        c = src[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
    return None


def brace_slice(src: str, sig: str, from_pos: int = 0) -> str:
    """Char-offset slice from `sig` through the brace it opens (balanced).

    `from_pos` matters when the signature appears several times: each
    `withContext(Dispatchers.IO) {` match must slice ITS OWN block, not the
    first one in the file (the selftest caught the always-slice-first bug:
    the second IO region's deleteFile site looked unguarded).

    For a FUNCTION signature the body brace is looked for AFTER the parameter
    list's matching paren, never at the first `{` to the right: a call-shaped
    default (`maxInjectLines: Int = run { … }`) used to end the slice inside the
    signature, so the real body was never judged at all — external witness E
    put `minOf(lineCount, 200)` there and every criterion stayed green
    (`brace_slice` had the same blind spot on MemoryFileRow /
    MemoryFileEditScreen, which this fixes too).
    """
    i = src.find(sig, from_pos)
    if i < 0:
        return ""
    body_from = i + len(sig) - 1
    if "fun " in sig:
        op = src.find("(", i)
        if op < 0:
            return ""
        cl = _match_paren(src, op)
        if cl is None:
            return ""            # unbalanced params → fail closed
        body_from = cl
    ob = src.find("{", body_from)
    if ob < 0:
        return ""
    depth = 0
    for j in range(ob, len(src)):
        c = src[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
    return src[i:]


def _split_top(expr: str, op: str):
    parts, depth, last, i = [], 0, 0, 0
    while i < len(expr):
        c = expr[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and expr.startswith(op, i):
            parts.append(expr[last:i])
            last = i + len(op)
            i = last
            continue
        i += 1
    parts.append(expr[last:])
    return parts


def cond_truth(expr: str):
    """True/False when a LITERAL operand decides the condition, else None."""
    s = expr.strip()
    while s.startswith("(") and s.endswith(")") and _match_paren(s, 0) == len(s) - 1:
        s = s[1:-1].strip()
    if s == "true":
        return True
    if s == "false":
        return False
    if s.startswith("!"):
        inner = cond_truth(s[1:])
        return None if inner is None else (not inner)
    ors = _split_top(s, "||")
    if len(ors) > 1:
        vals = [cond_truth(x) for x in ors]
        if any(v is True for v in vals):
            return True
        if vals and all(v is False for v in vals):
            return False
        return None
    ands = _split_top(s, "&&")
    if len(ands) > 1:
        vals = [cond_truth(x) for x in ands]
        if any(v is False for v in vals):
            return False
        if vals and all(v is True for v in vals):
            return True
        return None
    return None


def dead_guard_spans(text: str):
    """[(start, end)] of bodies whose controlling condition is literal-FALSE.

    `if (false && onDelete != null) { IconButton(onClick = onDelete) … }` keeps
    the callback reference, the IconButton spelling and the whole confirmation
    path in the text — and renders nothing (external witness A). Same shape
    revived the SOUL cache refresh in witness D.
    """
    spans = []
    for m in re.finditer(r"\b(?:if|while)\s*\(", text):
        op = m.end() - 1
        cl = _match_paren(text, op)
        if cl is None or cond_truth(text[op + 1:cl]) is not False:
            continue
        k = cl + 1
        while k < len(text) and text[k] in " \t":
            k += 1
        if k < len(text) and text[k] == "{":
            depth = 0
            for j in range(k, len(text)):
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        spans.append((k, j))
                        break
        else:
            eol = text.find("\n", k)
            spans.append((k, eol if eol >= 0 else len(text)))
    return spans


def inside_spans(spans, pos: int) -> bool:
    return any(a <= pos <= b for a, b in spans)


def check(screen_src: str, sheet_src: str) -> list:
    errs = []
    scr = strip_comments(screen_src)
    sht = strip_comments(sheet_src)

    # ---- A. MemoryFileRow must consume onDelete in its body ----
    row = brace_slice(scr, "private fun MemoryFileRow(")
    if not row:
        errs.append("MemoryFileRow not found in " + MEM_SCREEN + " — gate ROT")
    else:
        refs = len(re.findall(r"\bonDelete\b", row))
        if refs < 2:
            errs.append(
                f"MemoryFileRow body references onDelete {refs} time(s) "
                "(param only) — the row consumes the delete callback nowhere; "
                "the daily-log delete stays unreachable")
        if not re.search(r"IconButton\s*\(\s*onClick\s*=\s*onDelete\b", row) \
                and not re.search(r"IconButton\s*\(\s*onClick\s*=\s*\{\s*onDelete\?\.\s*invoke\(\)\s*\}", row):
            errs.append(
                "MemoryFileRow has no IconButton(onClick = onDelete) — no "
                "rendered handle can reach the confirm dialog")
        else:
            # Inside the body is not the same as reachable: `if (false &&
            # onDelete != null)` keeps every token the checks above match and
            # never renders the row's delete handle (external witness A).
            dead = dead_guard_spans(row)
            live = [m for m in re.finditer(r"IconButton\s*\(", row)
                    if not inside_spans(dead, m.start())]
            if not live:
                errs.append(
                    "MemoryFileRow's IconButton(onClick = onDelete) cannot run — it sits "
                    "inside a literal-decided guard (`if (false && onDelete != null)`) or "
                    "the whole row body is dead: the delete feature is unreachable again "
                    "while every spelling survives")

    # ---- B. every blocking repository call site is inside Dispatchers.IO ----
    io_slices = []
    for m in re.finditer(r"withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{", scr):
        sl = brace_slice(scr, m.group(0), from_pos=m.start())
        if sl:
            io_slices.append((m.start(), m.start() + len(sl)))
    for call in ("memoryRepository.deleteFile(", "memoryRepository.listAllFiles(",
                 "memoryRepository.readFile(", "memoryRepository.saveFile("):
        for m in re.finditer(re.escape(call), scr):
            pos = m.start()
            if not any(a <= pos < b for a, b in io_slices):
                errs.append(
                    f"'{call}' at offset {pos} runs outside any "
                    "withContext(Dispatchers.IO) region — main-thread file IO")
    confirm = brace_slice(scr, "if (deleteFileName != null) {")
    if not confirm:
        errs.append("delete-confirmation block not found — gate ROT")
    else:
        if "scope.launch" not in confirm:
            errs.append(
                "delete confirm is synchronous — expected scope.launch + "
                "withContext(Dispatchers.IO) (state must clear before launch)")
        if "withContext(Dispatchers.IO)" not in confirm:
            errs.append("delete confirm has no withContext(Dispatchers.IO)")

    # ---- D. MemoryFileEditScreen: async read/save + SOUL cache refresh ----
    # [deep-audit-1029 ISSUE-03] Fail-closed on the function anchor: the two
    # async-IO call sites and the SOUL refresh live in its body, so a missing
    # function means the gate can no longer see them at all.
    edit = brace_slice(scr, "fun MemoryFileEditScreen(")
    if not edit:
        errs.append(
            "MemoryFileEditScreen not found in " + MEM_SCREEN + " — gate ROT "
            "(its async read/save + SOUL-refresh anchors cannot be judged)")
    else:
        if not re.search(r"LaunchedEffect\s*\(", edit):
            errs.append(
                "MemoryFileEditScreen has no LaunchedEffect — the initial "
                "readFile() must run in an effect, not on the composition pass")
        if "scope.launch" not in edit:
            errs.append(
                "MemoryFileEditScreen save is not launched on a coroutine "
                "scope — saveFile() would block the click thread")
        save_pos = edit.find("memoryRepository.saveFile(")
        if save_pos < 0:
            errs.append("MemoryFileEditScreen has no memoryRepository.saveFile( — gate ROT")
        else:
            abs_pos = scr.find("fun MemoryFileEditScreen(") + save_pos
            holders = [(a, b) for a, b in io_slices if a <= abs_pos < b]
            # No holder = criterion B already reported saveFile as outside IO;
            # don't double-report here.
            if holders:
                a, b = holders[0]
                if "SoulStore.refreshCache(" not in scr[a:b]:
                    errs.append(
                        "the withContext(Dispatchers.IO) region that writes "
                        "memoryRepository.saveFile( does not call "
                        "SoulStore.refreshCache( — a SOUL.md edit from this "
                        "screen leaves SoulStore.cachedMetadata / writeVersion "
                        "stale for the chat header and Settings → Soul editors")
                else:
                    # Present is not the same as running: `if (false &&
                    # fileName == SOUL_FILE_NAME) { refreshCache(...) }` keeps the
                    # call inside the IO region and never executes it (external
                    # witness D).
                    dead = dead_guard_spans(scr[a:b])
                    live = [m for m in re.finditer(r"SoulStore\.refreshCache\(", scr[a:b])
                            if not inside_spans(dead, m.start())]
                    if not live:
                        errs.append(
                            "SoulStore.refreshCache( in the save IO region cannot run — its "
                            "guarding condition is literal-decided (`if (false && "
                            "fileName == SOUL_FILE_NAME)`), so a SOUL.md save never "
                            "refreshes the cache while the call keeps its spelling")

    # ---- C. autoItems snapshot must rebuild on every in-sheet mutation ----
    tick_decl = re.search(r"var\s+autoItemsRefreshTick\s+by\s+remember", sht)
    if not tick_decl:
        errs.append("no 'var autoItemsRefreshTick by remember' refresh signal")
    if not re.search(
            r"remember\s*\([^)]*autoItemsRefreshTick[^)]*\)\s*\{[^{}]*buildAutoInjectedItems",
            sht):
        errs.append(
            "autoItems remember does not key on autoItemsRefreshTick — "
            "edits made in this sheet leave the list (and the cached file "
            "contents the editor re-opens) permanently stale")
    bumps = len(re.findall(r"autoItemsRefreshTick\+\+", sht))
    if bumps < 3:
        errs.append(
            f"autoItemsRefreshTick bumped {bumps} time(s), expected >= 3 "
            "(AutoFile save + Write save + Write revoke)")

    # ---- E. [dv16 B-08] dailyAutoItemFor cap follows memoryInjectLines() ----
    daily = brace_slice(sht, "fun dailyAutoItemFor(")
    if not daily:
        errs.append(
            "E: dailyAutoItemFor not found in " + MEM_SHEET + " — gate ROT "
            "(its injection-cap anchors cannot be judged)")
    else:
        if re.search(r"\b200\b", daily):
            errs.append(
                "E: dailyAutoItemFor still hardcodes the 200-line injection "
                "cap — it must flow through the maxInjectLines parameter "
                "(AgentRuntimeLimitsPrefs.memoryInjectLines())")
        if not re.search(r"maxInjectLines\s*:", daily):
            errs.append(
                "E: dailyAutoItemFor has no maxInjectLines parameter — the "
                "sheet cannot follow the user-tunable injection cap")
        else:
            body = daily[daily.find(")"): ] if daily.find(")") >= 0 else daily
            # Declared is not the same as consumed: the witness kept the
            # parameter (with a call-shaped default) and hardcoded `200` in the
            # body — both facts green until the slice covered the body at all.
            if "maxInjectLines" not in body:
                errs.append(
                    "E: dailyAutoItemFor declares maxInjectLines but the body never "
                    "consumes it — the injection cap is hard-coded and the `N/M lines "
                    "injected` label lies about what was injected")
    if "AgentRuntimeLimitsPrefs.memoryInjectLines()" not in sht:
        errs.append(
            "E: " + MEM_SHEET + " never reads "
            "AgentRuntimeLimitsPrefs.memoryInjectLines() — the shown cap is "
            "decoupled from the real injection cap")

    return errs


def selftest() -> int:
    good_screen = """
val scope = rememberCoroutineScope()
LaunchedEffect(Unit) {
    files = withContext(Dispatchers.IO) { memoryRepository.listAllFiles() }
}
if (deleteFileName != null) {
    AlertDialog(
        confirmButton = {
            MinisTextButton(onClick = {
                val name = deleteFileName
                deleteFileName = null
                if (name != null) {
                    scope.launch {
                        val refreshed = withContext(Dispatchers.IO) {
                            memoryRepository.deleteFile(name)
                            memoryRepository.listAllFiles()
                        }
                        files = refreshed
                    }
                }
            }) { Text("x") }
        },
    )
}
@Composable
private fun MemoryFileRow(
    file: MemoryRepository.MemoryFileInfo,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row {
        Icon(Icons.Filled.Delete, null)
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null) }
        }
    }
}
@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    var fullBase by remember(fileName) { mutableStateOf("") }
    var isLoaded by remember(fileName) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(fileName) {
        fullBase = withContext(Dispatchers.IO) { memoryRepository.readFile(fileName) }
        isLoaded = true
    }
    MinisTextButton(onClick = {
        val newText = state.text.toString()
        val base = fullBase
        scope.launch {
            try {
                val full = base + newText
                withContext(Dispatchers.IO) {
                    memoryRepository.saveFile(fileName, full)
                    if (fileName == SOUL_FILE_NAME) {
                        SoulStore.refreshCache(context)
                    }
                }
                fullBase = full
            } catch (e: Exception) {
                saveError = e.message
            }
        }
    }) { Text("Save") }
}
"""
    good_sheet = """
var autoItemsRefreshTick by remember { mutableStateOf(0) }
val autoItems = remember(memoryRepository, context, autoItemsRefreshTick) {
    buildAutoInjectedItems(context, memoryRepository)
}
autoItemsRefreshTick++
autoItemsRefreshTick++
autoItemsRefreshTick++

// [dv16 B-08] compliant dailyAutoItemFor: the cap flows through the
// maxInjectLines parameter (prefs-backed default), not a hard-coded literal.
internal fun dailyAutoItemFor(
    fileName: String,
    displayName: String,
    content: String,
    maxInjectLines: Int = AgentRuntimeLimitsPrefs.memoryInjectLines(),
): AutoItem? {
    if (content.isBlank()) return null
    val lineCount = content.lines().size
    val injected = minOf(lineCount, maxInjectLines)
    val detail = if (lineCount > maxInjectLines) "$injected/$lineCount lines injected" else "$lineCount lines (full)"
    return AutoItem(name = displayName, detail = detail, fileName = fileName, content = content)
}
"""

    v1_screen = good_screen.replace(
        "        if (onDelete != null) {\n"
        "            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null) }\n"
        "        }\n", "")
    v2_screen = good_screen.replace(
        "                        val refreshed = withContext(Dispatchers.IO) {\n"
        "                            memoryRepository.deleteFile(name)\n"
        "                            memoryRepository.listAllFiles()\n"
        "                        }\n"
        "                        files = refreshed\n",
        "                        files = memoryRepository.listAllFiles()\n"
        "                        memoryRepository.deleteFile(name)\n").replace(
        "files = withContext(Dispatchers.IO) { memoryRepository.listAllFiles() }",
        "files = memoryRepository.listAllFiles()")
    v3_sheet = good_sheet.replace(
        "remember(memoryRepository, context, autoItemsRefreshTick)",
        "remember(memoryRepository, context)")
    v4_sheet = good_sheet.replace("autoItemsRefreshTick++", "", 1)
    # comment trap: prose naming every criterion must NOT make a stripped tree pass
    v1_comment_screen = """
// [fix] IconButton(onClick = onDelete) wires the row; the confirm path uses
// scope.launch and withContext(Dispatchers.IO).
""" + v1_screen
    v5_sheet = good_sheet.replace(
        "var autoItemsRefreshTick by remember { mutableStateOf(0) }",
        "// autoItemsRefreshTick\nval tick = remember { mutableStateOf(0) }")

    # [dv16 B-08] dailyAutoItemFor arms. Fixture drift guard on the new
    # replace anchor (a no-op .replace proves nothing).
    DAILY_PARAM = "    maxInjectLines: Int = AgentRuntimeLimitsPrefs.memoryInjectLines(),\n"
    DAILY_BODY = (
        "    val injected = minOf(lineCount, maxInjectLines)\n"
        "    val detail = if (lineCount > maxInjectLines)")
    for name, frag in (("DAILY_PARAM", DAILY_PARAM), ("DAILY_BODY", DAILY_BODY)):
        if good_sheet.count(frag) != 1:
            print(f"  FAIL selftest fixture drift: {name} matched "
                  f"{good_sheet.count(frag)} time(s) in good_sheet, expected 1")
            return 1
    # V11: the bare 200 literal is back in the function body
    v11_sheet = good_sheet.replace(
        DAILY_BODY,
        "    val injected = minOf(lineCount, 200)\n"
        "    val detail = if (lineCount > 200)")
    # V12: the maxInjectLines parameter (and the prefs read with it) is gone
    v12_sheet = good_sheet.replace(DAILY_PARAM, "")

    # [deep-audit-1029 ISSUE-03] MemoryFileEditScreen arms (criterion B extended
    # to read/save, plus criterion D). Anchors are spelled with their exact
    # fixture indentation so a drift here is caught (the .replace would no-op
    # and the arm would silently prove nothing — guarded below).
    EDIT_READ = (
        "    LaunchedEffect(fileName) {\n"
        "        fullBase = withContext(Dispatchers.IO) { memoryRepository.readFile(fileName) }\n"
        "        isLoaded = true\n"
        "    }\n")
    EDIT_SAVE_IO = (
        "                withContext(Dispatchers.IO) {\n"
        "                    memoryRepository.saveFile(fileName, full)\n")
    EDIT_REFRESH = (
        "                    if (fileName == SOUL_FILE_NAME) {\n"
        "                        SoulStore.refreshCache(context)\n"
        "                    }\n")
    # newline-prefixed so the 20-space indented launch in the delete-confirm
    # fixture is not a substring match (indentation is not a left boundary).
    EDIT_SCOPE = "\n        scope.launch {\n"
    for name, frag in (("EDIT_READ", EDIT_READ), ("EDIT_SAVE_IO", EDIT_SAVE_IO),
                       ("EDIT_REFRESH", EDIT_REFRESH), ("EDIT_SCOPE", EDIT_SCOPE)):
        if good_screen.count(frag) != 1:
            print(f"  FAIL selftest fixture drift: {name} matched "
                  f"{good_screen.count(frag)} time(s) in good_screen, expected 1")
            return 1
    # V6: initial read back on the composition pass (and no LaunchedEffect)
    v6_screen = good_screen.replace(
        EDIT_READ, "    fullBase = memoryRepository.readFile(fileName)\n")
    # V7: saveFile back on the click thread (IO wrapper replaced by a plain run)
    v7_screen = good_screen.replace(
        EDIT_SAVE_IO,
        "                run {\n                    memoryRepository.saveFile(fileName, full)\n")
    # V8: SOUL cache never refreshed
    v8_screen = good_screen.replace(EDIT_REFRESH, "")
    # V9: refresh named in prose only (comments are stripped before judging)
    v9_screen = good_screen.replace(
        EDIT_REFRESH,
        "                // refresh the SoulStore cache here\n")
    # V10: save not launched on a coroutine scope (inline `run`)
    v10_screen = good_screen.replace(EDIT_SCOPE, "\n        run {\n")

    # ── [wide3-07] the three external witnesses of this gate ───────────────
    # A the dead-guarded delete handle keeps the callback, the IconButton and
    # the confirmation path, and renders nothing.
    A_ROW = ("        if (onDelete != null) {\n"
             "            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null) }\n"
             "        }\n")
    if good_screen.count(A_ROW) != 1:
        print("  FAIL selftest fixture drift: A_ROW matched %d time(s), expected 1"
              % good_screen.count(A_ROW))
        return 1
    v13_screen = good_screen.replace(
        A_ROW,
        "        if (false && onDelete != null) {\n"
        "            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, null) }\n"
        "        }\n")
    # D the SOUL cache refresh stays inside the IO region and is short-circuited.
    v14_screen = good_screen.replace(
        EDIT_REFRESH,
        "                    if (false && fileName == SOUL_FILE_NAME) {\n"
        "                        SoulStore.refreshCache(context)\n"
        "                    }\n")
    if v14_screen == good_screen:
        print("  FAIL selftest fixture drift: EDIT_REFRESH no longer matches")
        return 1
    # E a call-shaped default truncates the slice, so the hardcoded cap in the
    # body is never judged (the parameter and the prefs read both stay).
    v15_sheet = good_sheet.replace(
        DAILY_PARAM,
        "    maxInjectLines: Int = run { AgentRuntimeLimitsPrefs.memoryInjectLines() },\n").replace(
        DAILY_BODY,
        "    val injected = minOf(lineCount, 200)\n"
        "    val detail = if (lineCount > 200)")

    arms = [
        ("compliant pair", good_screen, good_sheet, 0),
        ("V13 delete handle behind `if (false && …)`", v13_screen, good_sheet, 1),
        ("V14 SOUL refresh behind `if (false && …)`", v14_screen, good_sheet, 1),
        ("V15 call-shaped default hides the hardcoded cap", good_screen, v15_sheet, 1),
        ("V1 row never consumes onDelete", v1_screen, good_sheet, 1),
        ("V1b comment-only wiring (comments stripped)", v1_comment_screen, good_sheet, 1),
        ("V2 blocking repository calls outside Dispatchers.IO", v2_screen, good_sheet, 1),
        ("V3 autoItems remember has no refresh key", good_screen, v3_sheet, 1),
        ("V4 only two tick bumps", good_screen, v4_sheet, 1),
        ("V5 tick is prose-only / not a state var", good_screen, v5_sheet, 1),
        ("V6 edit-screen read on the composition pass", v6_screen, good_sheet, 1),
        ("V7 edit-screen saveFile outside Dispatchers.IO", v7_screen, good_sheet, 1),
        ("V8 SOUL cache never refreshed on save", v8_screen, good_sheet, 1),
        ("V9 SOUL refresh named in prose only", v9_screen, good_sheet, 1),
        ("V10 save not launched on a coroutine scope", v10_screen, good_sheet, 1),
        ("V11 daily cap hardcoded 200 back", good_screen, v11_sheet, 1),
        ("V12 maxInjectLines parameter dropped", good_screen, v12_sheet, 1),
    ]
    failures = 0
    for name, s1, s2, expect_red in arms:
        errs = check(s1, s2)
        if expect_red and not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        elif not expect_red and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {'red: ' + errs[0] if errs else 'green'}")
    # [dv16 B-08] teeth: the hardcoded-200 arm must red ONLY the E criteria.
    e11 = check(good_screen, v11_sheet)
    if not e11 or not all(err.startswith("E:") for err in e11):
        print(f"  FAIL V11 teeth: expected only E-prefixed errors, got {e11}")
        failures += 1
    if failures:
        print(f"SELFTEST FAILED ({failures} arms)")
        return 1
    print(MARKER)
    return 0


def main() -> int:
    args = sys.argv[1:]
    if "--selftest" in args:
        return selftest()
    root = None
    if "--root" in args:
        i = args.index("--root")
        if i + 1 >= len(args):
            print("usage: --root <repo_root>", file=sys.stderr)
            return 2
        root = args[i + 1]
    elif len(args) == 1:
        root = args[0]
    else:
        print("usage: <repo_root> | --root <repo_root> | --selftest", file=sys.stderr)
        return 2
    import os
    p1 = os.path.join(root, MEM_SCREEN)
    p2 = os.path.join(root, MEM_SHEET)
    try:
        s1 = open(p1).read()
        s2 = open(p2).read()
    except OSError as e:
        print(f"cannot read target: {e}", file=sys.stderr)
        return 2
    errs = check(s1, s2)
    if errs:
        print(f"memory_ui_wiring_check RED on {MEM_SCREEN} / {MEM_SHEET}:")
        for e in errs:
            print(f"  - {e}")
        return 1
    print(f"memory_ui_wiring_check OK ({MEM_SCREEN}, {MEM_SHEET})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
