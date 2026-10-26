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

Every judgment runs on `code_view()`: comments and the *inside* of string /
char literals are blanked with the same length, so offsets back-project onto
the source and the prose that names each criterion cannot carry the gate.
A file that cannot be lexed is reported red (fail-closed), never re-scanned
in raw form. [batch6-t08] criterion `live` (reachability) is decided by the
shared `path_feasible` + a local dead-span set, never by matching the
spelling `false`/`true`.

Usage: python3 memory_ui_wiring_check.py <repo_root>
       python3 memory_ui_wiring_check.py --root <repo_root>
       python3 memory_ui_wiring_check.py --selftest
Exit 0 = pass, 1 = violation, 2 = usage error.
"""
import re
import sys

from pathlib import Path

# [batch6-t08] 共享词法 / 语法 / 值流原语（**唯一**实现）。本门不再自写词法器：
# 结构分析一律在 `code_view()`（注释与字符串/字符字面量内部抹成空格，长度与源码
# **严格相等**，位置可回投原文）上做；括号配平走 `match_bracket`；常量折叠走
# `const_bool` / `const_value`；可达性走 `path_feasible`（覆盖 if / else / when）；
# 值流走 `value_sources`。解析失败一律 fail-closed（报违规，绝不回退原文扫描）。
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from _kotlin_lexer import KotlinLexError  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    function_body,
    if_blocks,
    match_bracket,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    code_view,
    contains_ident,
    expr_end,
    path_feasible,
)

MEM_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/MemoryManagementScreen.kt"
MEM_SHEET = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/SessionMemorySheet.kt"
MARKER = "MEMORY_UI_WIRING_SELFTEST=PASS"


def _view(src: str):
    """结构视图；解析失败 → None（调用方 fail-closed，绝不回退原文扫描）。"""
    try:
        return code_view(src)
    except KotlinLexError:
        return None


def _fn_body(text: str, name: str):
    """函数体（不含两侧花括号）；找不到 → None。派生自共享 `function_body`。"""
    got = function_body(text, name)
    if not got:
        return None
    return text[got[0]:got[1]]


def _block_at(text: str, sig: str, from_pos: int = 0):
    """从 sig 起到它开启的块结束（含）的切片；配平失败 → ""（fail-closed）。

    `from_pos` 必须给：同一文件里有多个 `withContext(Dispatchers.IO) {`，每个
    调用点要切自己那块（曾经"永远切第一块"让第二个 IO 区域看起来没有守卫）。
    """
    i = text.find(sig, from_pos)
    if i < 0:
        return ""
    op = text.find("{", i + len(sig) - 1)
    if op < 0:
        return ""
    close = match_bracket(text, op)
    if close < 0:
        return ""
    return text[i:close + 1]


def _dead_spans(text: str):
    """字面常量判死的分支体：`if` 的 then 体 + **无主题** `when` 的常量假分支。

    [batch6-t08 A04-1/A04-2] 旧实现只认 `true`/`false` 两个词：`1 == 2` 折不出
    常量，`when { false && … -> }` 连扫都不扫。这里常量折叠交给共享 `const_bool`
    （`1 == 2`、`!true`、`x && false` 全折）；`when` 分支按顶层 `->` 切分，
    分支体从箭头到下一个箭头（无主题 when 的条件是布尔表达式，才可折）。
    """
    spans = []
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(text):
        if const_bool(cond) is False:
            spans.append((t_start, t_end))
    for m in re.finditer(r"\bwhen\s*\{", text):
        close = match_bracket(text, m.end() - 1)
        if close < 0:
            continue
        body_start, body_end = m.end(), close
        arrows, depth, i = [], 0, body_start
        while i < body_end:
            ch = text[i]
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
            elif depth == 0 and text.startswith("->", i):
                arrows.append(i)
                i += 2
                continue
            i += 1
        prev = body_start
        for k, arrow in enumerate(arrows):
            end = arrows[k + 1] if k + 1 < len(arrows) else body_end
            if const_bool(strip_parens(text[prev:arrow])) is False:
                spans.append((arrow + 2, end))
            prev = arrow + 2
    return spans


def _live(text: str, pos: int) -> bool:
    """pos 是否可达：条件都可行 **且** 不在任何字面常量判死的分支体里。"""
    if not path_feasible(text, pos):
        return False
    return not any(a <= pos < b for a, b in _dead_spans(text))


def _live_matches(text: str, pattern: str):
    return [m for m in re.finditer(pattern, text) if _live(text, m.start())]


def _fn_params(code: str, name: str):
    """函数参数名集合（从签名派生，不写死名字）。"""
    m = re.search(r"\bfun\s+(?:[A-Za-z_][\w.]*\.)?" + re.escape(name) + r"\s*\(",
                  code)
    if not m:
        return set()
    open_pos = m.end() - 1
    close = match_bracket(code, open_pos)
    if close < 0:
        return set()
    names = set()
    for a, b in call_args(code, open_pos):
        mm = re.match(r"\s*(?:(?:vararg|noinline|crossinline|val|var)\s+)?"
                      r"([A-Za-z_]\w*)\s*:", code[a:b])
        if mm:
            names.add(mm.group(1))
    return names


_CAP_CALLS = ("minOf(", "maxOf(", "min(", "max(", "coerceAtMost(", "coerceAtLeast(")


def _cap_operands(body: str):
    """「上限操作数」：min/max/coerce 的实参 + 比较两侧。"""
    out = []
    for name in _CAP_CALLS:
        for m in re.finditer(r"\b" + re.escape(name), body):
            for a, b in call_args(body, m.end() - 1):
                out.append(body[a:b].strip())
    for m in re.finditer(r"([A-Za-z_][\w.()]*)\s*(?:<=|>=|<|>)\s*([A-Za-z_][\w.()]*)",
                         body):
        out.extend([m.group(1).strip(), m.group(2).strip()])
    return [c for c in out if c]


def _resolve(body: str, expr: str, hops: int = 3) -> str:
    """局部 `val` 一跳（最多 hops）展开：`fixedCap` → `100 + 100`。"""
    cur = expr.strip()
    for _ in range(hops):
        if not re.fullmatch(r"[A-Za-z_]\w*", cur):
            break
        m = re.search(r"\bval\s+" + re.escape(cur) + r"\b[^=\n]*=(?!=)", body)
        if not m:
            break
        cur = body[m.end():expr_end(body, m.end())].strip()
    return cur
def check(screen_src: str, sheet_src: str) -> list:
    errs = []
    scr = _view(screen_src)
    if scr is None:
        return [MEM_SCREEN + " cannot be lexed (unterminated string/comment) — "
                "fail-closed"]
    sht = _view(sheet_src)
    if sht is None:
        return [MEM_SHEET + " cannot be lexed (unterminated string/comment) — "
                "fail-closed"]

    # ---- A. MemoryFileRow must consume onDelete from a LIVE branch ----
    row = _fn_body(scr, "MemoryFileRow")
    if not row:
        errs.append("MemoryFileRow not found in " + MEM_SCREEN + " — gate ROT")
    else:
        refs = len(re.findall(r"\bonDelete\b", row))
        if refs < 2:
            errs.append(
                f"MemoryFileRow body references onDelete {refs} time(s) "
                "(param only) — the row consumes the delete callback nowhere; "
                "the daily-log delete stays unreachable")
        handles = []
        for m in re.finditer(r"IconButton\s*\(", row):
            close = match_bracket(row, m.end() - 1)
            args = row[m.end():close] if close > 0 else ""
            if not (re.search(r"onClick\s*=\s*onDelete\b", args)
                    or re.search(r"onClick\s*=\s*\{\s*onDelete\?\s*\.\s*invoke\(\)\s*\}",
                                 args)):
                continue
            handles.append(m.start())
        if not [p for p in handles if _live(row, p)]:
            errs.append(
                "MemoryFileRow has no IconButton(onClick = onDelete) that can RUN "
                "— the handle is missing, its argument list does not pass the "
                "callback, or every such handle sits in a literal-decided branch "
                "(`if (1 == 2 && onDelete != null)` / `when { false && … -> }`): "
                "the delete feature is unreachable again while every spelling "
                "survives")

    # ---- B. every blocking repository call site is inside Dispatchers.IO ----
    # A dead IO region (`if (false) { withContext(Dispatchers.IO) { … } }`) does
    # not hold anything: the region has to be reachable to count as a wrapper.
    io_slices = []
    for m in re.finditer(r"withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{", scr):
        if not _live(scr, m.start()):
            continue
        close = match_bracket(scr, m.end() - 1)
        if close < 0:
            continue
        io_slices.append((m.start(), close))
    for call in ("memoryRepository.deleteFile(", "memoryRepository.listAllFiles(",
                 "memoryRepository.readFile(", "memoryRepository.saveFile("):
        for m in re.finditer(re.escape(call), scr):
            pos = m.start()
            if not any(a <= pos < b for a, b in io_slices):
                errs.append(
                    f"'{call}' at offset {pos} runs outside any reachable "
                    "withContext(Dispatchers.IO) region — main-thread file IO")
    confirm = _block_at(scr, "if (deleteFileName != null) {")
    if not confirm:
        errs.append("delete-confirmation block not found — gate ROT")
    else:
        if not _live_matches(confirm, r"scope\.launch"):
            errs.append(
                "delete confirm is synchronous — no REACHABLE scope.launch "
                "(expected scope.launch + withContext(Dispatchers.IO); state must "
                "clear before launch)")
        if not _live_matches(confirm, r"withContext\s*\(\s*Dispatchers\.IO\s*\)"):
            errs.append("delete confirm has no reachable "
                        "withContext(Dispatchers.IO)")

    # ---- D. MemoryFileEditScreen: async read/save + SOUL cache refresh ----
    got = function_body(scr, "MemoryFileEditScreen")
    if not got:
        errs.append(
            "MemoryFileEditScreen not found in " + MEM_SCREEN + " — gate ROT "
            "(its async read/save + SOUL-refresh anchors cannot be judged)")
    else:
        edit = scr[got[0]:got[1]]
        if not re.search(r"LaunchedEffect\s*\(", edit):
            errs.append(
                "MemoryFileEditScreen has no LaunchedEffect — the initial "
                "readFile() must run in an effect, not on the composition pass")
        if not _live_matches(edit, r"scope\.launch"):
            errs.append(
                "MemoryFileEditScreen save is not launched on a reachable "
                "coroutine scope — saveFile() would block the click thread")
        save_pos = edit.find("memoryRepository.saveFile(")
        if save_pos < 0:
            errs.append("MemoryFileEditScreen has no memoryRepository.saveFile( — gate ROT")
        else:
            abs_pos = got[0] + save_pos
            holders = [(a, b) for a, b in io_slices if a <= abs_pos < b]
            # No holder = criterion B already reported saveFile as outside IO;
            # don't double-report here.
            if holders:
                a, b = holders[0]
                if not _live_matches(scr[a:b], r"SoulStore\.refreshCache\("):
                    errs.append(
                        "the withContext(Dispatchers.IO) region that writes "
                        "memoryRepository.saveFile( has no REACHABLE "
                        "SoulStore.refreshCache( — a SOUL.md edit from this screen "
                        "leaves SoulStore.cachedMetadata / writeVersion stale, or "
                        "the refresh hides behind a literal-decided guard "
                        "(`if (false && fileName == SOUL_FILE_NAME)` / "
                        "`when { false && … -> }`)")

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
    bumps = _live_matches(sht, r"autoItemsRefreshTick\+\+")
    if len(bumps) < 3:
        errs.append(
            f"autoItemsRefreshTick is bumped {len(bumps)} time(s) that can RUN, "
            "expected >= 3 (AutoFile save + Write save + Write revoke) — a bump "
            "inside a literal-decided branch publishes nothing")
    resets = list(re.finditer(r"autoItemsRefreshTick\s*(?:--|-=|=(?!=))", sht))
    if resets:
        errs.append(
            "autoItemsRefreshTick is reset after a bump (`%s`) — the remember key "
            "must only move forward; a later decrement/assignment collapses two "
            "bumps into one rebuild" % resets[0].group(0).strip())

    # ---- E. [dv16 B-08] dailyAutoItemFor cap follows memoryInjectLines() ----
    daily_span = function_body(sht, "dailyAutoItemFor")
    if not daily_span:
        errs.append(
            "E: dailyAutoItemFor not found in " + MEM_SHEET + " — gate ROT "
            "(its injection-cap anchors cannot be judged)")
    else:
        daily = sht[daily_span[0]:daily_span[1]]
        if "maxInjectLines" not in _fn_params(sht, "dailyAutoItemFor"):
            errs.append(
                "E: dailyAutoItemFor has no maxInjectLines parameter — the "
                "sheet cannot follow the user-tunable injection cap")
        if re.search(r"(?<![\w.])200(?![\w.])", daily):
            errs.append(
                "E: dailyAutoItemFor still carries the bare 200 literal — the cap "
                "must flow through the maxInjectLines parameter")
        caps = _cap_operands(daily)
        consts = [c for c in caps if const_value(_resolve(daily, c)) is not None]
        if consts:
            errs.append(
                "E: the injection cap is a constant expression (%s) — it must "
                "flow from the maxInjectLines parameter "
                "(AgentRuntimeLimitsPrefs.memoryInjectLines())"
                % ", ".join(sorted(set(consts))[:3]))
        if not any(contains_ident(_resolve(daily, c), "maxInjectLines")
                   for c in caps):
            errs.append(
                "E: dailyAutoItemFor declares maxInjectLines but no cap operand "
                "derives from it — the injection cap is hard-coded and the "
                "`N/M lines injected` label lies about what was injected")
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

    # ── [batch6-t08] witnesses A04-1..A04-6 ────────────────────────────────
    extra_arms = []
    # A04-1 (family-hole): the guard survives as a spelling but folds to false.
    extra_arms.append((
        "A04-1 `if (1 == 2 && onDelete != null)`",
        good_screen.replace("if (onDelete != null) {",
                            "if (1 == 2 && onDelete != null) {"), good_sheet, 1))
    # A04-2 (family-hole): the SOUL refresh hides in a subject-less `when` branch
    # whose condition folds to false — braces stay balanced.
    extra_arms.append((
        "A04-2 `when { false && … -> refresh }`",
        good_screen.replace(
            "                    if (fileName == SOUL_FILE_NAME) {\n"
            "                        SoulStore.refreshCache(context)\n"
            "                    }\n",
            "                    when {\n"
            "                        false && fileName == SOUL_FILE_NAME -> "
            "SoulStore.refreshCache(context)\n"
            "                    }\n"), good_sheet, 1))
    # A04-3 (family-hole): the confirm-side launch guard folds to false.
    extra_arms.append((
        "A04-3 `if (name != null && false)`",
        good_screen.replace("if (name != null) {",
                            "if (name != null && false) {"), good_sheet, 1))
    # A04-4 (order): four bumps, two of them inside a dead branch — the count is
    # unchanged (4) but only two can ever run.
    _a044_sheet = good_sheet.replace("autoItemsRefreshTick++\n", "", 1)
    extra_arms.append((
        "A04-4 two of four bumps are dead",
        good_screen,
        _a044_sheet + "\nif (false) { autoItemsRefreshTick++; "
                      "autoItemsRefreshTick++ }\n", 1))
    # A04-5 (spelling): the cap becomes a constant expression bound to a local.
    extra_arms.append((
        "A04-5 cap = `100 + 100` via a local",
        good_screen,
        good_sheet.replace(
            "    val injected = minOf(lineCount, maxInjectLines)",
            "    val _ignoredCap = maxInjectLines\n"
            "    val fixedCap = 100 + 100\n"
            "    val injected = minOf(lineCount, fixedCap)").replace(
            "if (lineCount > maxInjectLines)",
            "if (lineCount > fixedCap)"), 1))
    # A04-6 (parser-death): a raw string holding a brace + the read pushed out of
    # Dispatchers.IO — the pre-fix brace counter ended the function early.
    extra_arms.append((
        'A04-6 `"""` trap + read outside IO',
        good_screen.replace(
            "LaunchedEffect(Unit) {\n"
            "    files = withContext(Dispatchers.IO) { "
            "memoryRepository.listAllFiles() }\n"
            "}\n",
            'LaunchedEffect(Unit) {\n'
            '    val note = """\n'
            '        {"text":""} // this brace used to end the body\n'
            '    """\n'
            '    if (false) { withContext(Dispatchers.IO) { } }\n'
            '    files = memoryRepository.listAllFiles()\n'
            '}\n'), good_sheet, 1))

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
    arms += extra_arms
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
