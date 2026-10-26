#!/usr/bin/env python3
"""Guard: ChatMenuSettingsScreen's prefs-change listener must match the two
fixed top-bar/composer keys AND re-read the three order states.

Why this exists (da1021-D): the listener guarded on
`key.startsWith("chatMenu.")` ONLY, but the two fixed knobs it exists to
refresh are `topBar.inputHistory.visible` and `composer.modelPickerButton.visible`
(neither starts with `chatMenu.`), so external `minis-config` /
backup-restore writes never bumped the tick and the two switches kept
rendering the values read at first composition. On top of that, the three
order States (`menuOrder` / `footerOrder` / `attachOrder`) were never
re-read either: an external restore of `chatMenu.order` left the lists
stale and a subsequent in-screen drag wrote the stale order back.

Arms:
  1. the listener predicate matches BOTH fixed keys (via ChatMenuPrefs
     constants or the raw key strings);
  2. the listener body re-reads all three orders (resolveOrder( /
     settingsPinOrder( / resolveAttachOrder();
  3. the re-reads sit inside the `draggingEntry == null` guard (so an async
     listener notification can't interrupt an in-flight drag / write-back);
  4. the two key constants in ChatMenuPrefs are NOT `private const val`
     (the screen references them — a private revert forces a second string
     copy in the UI, the same-family recurrence shape).

[da1028-B / ISSUE-04] Five more arms for the two stale-write screens (only
judged when the caller supplies their sources; the legacy two-file fixtures
leave them out):
  5. SoulStore declares `writeVersion` and `save()` bumps it — Settings → Soul
     keys its reload on it, so without the bump an external write leaves the
     editors stale and the next Save rolls that write back;
  6. SoulStore's `refreshCache()` bumps it too — that is the documented entry
     point for "SOUL.md was rewritten outside save()" (the chat memory sheet
     calls it right after its raw saveFile write);
  7. SoulSettingsScreen loads in a `LaunchedEffect` keyed on `writeVersion`
     and that effect re-reads the file (a `LaunchedEffect(Unit)` load is the
     pre-fix shape this arm exists to reject);
  8. LogManagementScreen registers an OnSharedPreferenceChangeListener for
     `logs.enabled` through AppLogger's constants (no re-typed literal) and
     unregisters it on dispose — the unregister half is required, not optional;
  9. anchors missing (store member / effect / listener block) -> red, never a
     silent skip.

[r3-t05 / dual-gate + symmetric seams] Three properties that live BETWEEN
this gate's arms and its neighbours' — each one passes every spelling check
while the user-visible effect is gone:
  10. arm2 -- the listener's re-read must carry the SAME projection as the
      first-composition read (`resolveOrder(...).filter { defaultMenuVisible }`):
      a bare `resolveOrder(prefs)` reassigns a list a fresh open would never
      render, so two footer-only entries turn into dead switches (witness #99).
  11. arm7 -- after `SoulStore.load`, the editor publications must not sit
      behind `if (!loaded)`: on a re-run that latch is already true, the whole
      block is dead code and the next Save rolls the external write back (#98).
  12. arm8 -- the listener's `AppLogger.isEnabled(…)` result must BECOME the
      Switch's State (derived from the `remember { mutableStateOf(...) }`
      initialiser), not just be called: a discarded read leaves the Switch on
      the value captured at open time (#97).

Judgements are made on comment-stripped code (comment anchors rot); raw text
is also accepted for arm 1 so a string-literal form is not silently missed.
"""
import re
import sys
import tempfile
from pathlib import Path

from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_ast import (
    const_bool,
    const_value,
    enclosing_conditions,
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import code_view, contains_ident, expr_end, literal_view, value_sources

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ChatMenuSettingsScreen.kt"
PREFS_REL = "src/android/app/src/main/java/com/rikkaminis/app/config/ChatMenuPrefs.kt"
# [da1028-B / ISSUE-04] stale-write surfaces
SOUL_STORE_REL = "src/android/app/src/main/java/com/rikkaminis/app/agent/SoulStore.kt"
SOUL_SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/SoulSettingsScreen.kt"
LOG_SCREEN_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/LogManagementScreen.kt"

MARKER = "CHAT_MENU_PREFS_LISTENER_SELFTEST=PASS"

CONST_A = "TOP_BAR_INPUT_HISTORY_PREF"
CONST_B = "COMPOSER_MODEL_PICKER_BUTTON_PREF"
KEY_A = "topBar.inputHistory.visible"
KEY_B = "composer.modelPickerButton.visible"

ORDER_READS = ("resolveOrder(", "settingsPinOrder(", "resolveAttachOrder(")
# wide-1024 (dataflow axis): the read must land in the State it exists to
# refresh -- "called the right pref API" is not the property arm 2/3 guard.
ORDER_TARGETS = {
    "resolveOrder(": "menuOrder",
    "settingsPinOrder(": "footerOrder",
    "resolveAttachOrder(": "attachOrder",
}


def views(text: str):
    """(code, literal)：抹掉注释+字面量的等长视图 / 只剥注释的等长视图。

    两套视图字符数严格相等，所以偏移可以互相回投：结构判定在 code 上做
    （字符串里的 `}`、`'"'` 字符字面量都已经被抹平），读字面量内容（raw key
    名、`"logging_prefs"`）时回投到 literal。解析失败返回 (None, None)，所有
    调用方据此 fail-closed —— 本门不再自带词法器。
    """
    try:
        return code_view(text), literal_view(text)
    except KotlinLexError:
        return None, None


def listener_body(screen_text: str):
    """(code_body, literal_body)：注册 change listener 的 DisposableEffect 块。"""
    code, lit = views(screen_text)
    if code is None:
        return "", ""
    for m in re.finditer(r"\bDisposableEffect\s*\(", code):
        open_paren = m.end() - 1
        close_paren = match_bracket(code, open_paren)
        if close_paren < 0:
            continue
        k = close_paren + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        if k >= len(code) or code[k] != "{":
            continue
        close = match_bracket(code, k)
        if close < 0:
            continue
        if ".registerOnSharedPreferenceChangeListener" in code[k:close]:
            return code[k:close], lit[k:close]
    return "", ""


def _stmt_start(text: str, idx: int) -> int:
    """包含 `idx` 的语句起点（深度 0 的换行 / `;` / 花括号都是边界）。"""
    depth = 0
    i = idx - 1
    while i >= 0:
        ch = text[i]
        if ch in ")]":
            depth += 1
        elif ch in "([":
            depth -= 1
        elif depth <= 0 and ch in "{};":
            return i + 1
        elif depth <= 0 and ch == "\n":
            return i + 1
        i -= 1
    return 0


def _locals_map(body: str):
    """`val/var <name> = <rhs>` 的一次绑定表（用于一跳委托判定）。"""
    out = {}
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*=(?!=)", body):
        out.setdefault(m.group(1), body[m.end():expr_end(body, m.end())])
    return out


def _carries(expr: str, read: str, locals_map, depth: int = 0) -> bool:
    """这个表达式的**值**是否还携带 `read` 的结果（经 value_sources，含一跳局部）。"""
    if depth > 3:
        return False
    identity = re.search(r"\?*\s*\.\s*(?:let|run)\s*\{\s*(?:it|this)\s*\}\s*$",
                         expr.strip())
    if identity:
        return _carries(expr.strip()[:identity.start()], read, locals_map, depth)
    for form in value_sources(expr):
        f = form.strip()
        if not f:
            continue
        if read in f:
            return True
        for name, rhs in locals_map.items():
            if contains_ident(f, name) and _carries(rhs, read, locals_map, depth + 1):
                return True
    return False


def result_consumed(body: str, read: str, target: str) -> bool:
    """`read` 的结果是否真的**成为** `target` 的新值。

    旧判据只看调用点左侧的语句头里有没有 `target =`，于是
    `footerOrder = ChatMenuPrefs.settingsPinOrder(prefs).let { footerOrder }`
    全绿：赋值号在，值却被 `.let` 尾巴换回旧状态。现在判的是值：
    经受 value_sources 规约（丢弃型作用域只留接收者、值型作用域取 lambda 尾
    表达式、elvis 两臂都算）后，还必须能从某个候选形式走到 `read`。
    """
    locals_map = _locals_map(body)
    for m in re.finditer(re.escape(read), body):
        idx = m.start()
        stmt = body[_stmt_start(body, idx):expr_end(body, idx)]
        direct = re.search(r"(?<![\w.])" + target + r"\s*=(?!=)", stmt)
        if direct:
            if _carries(stmt[direct.end():], read, locals_map):
                return True
            continue
        bind = re.match(r"\s*(?:val|var)\s+([A-Za-z_]\w*)\s*=(?!=)", stmt)
        if bind and read in stmt[bind.end():]:
            for m2 in re.finditer(r"(?<![\w.])" + target + r"\s*=(?!=)", body):
                if _carries(body[m2.end():expr_end(body, m2.end())], read, locals_map):
                    return True
    return False


def guarded_reads(body: str) -> bool:
    """三条 order 重读必须落在一条**有效**的 `draggingEntry == null` 守卫里。

    旧判据只 `find("draggingEntry == null")` 再配平花括号 —— 子串在写
    `|| true` 让守卫恒真时照样成立。现在读整条条件并折叠：恒真（守卫失效）
    或恒假（分支永不执行）都判红。
    """
    for cond, t_start, t_end, _es, _ee in if_blocks(body):
        if not contains_ident(cond, "draggingEntry") or "null" not in cond:
            continue
        if const_bool(cond) is not None:
            return False
        inner = body[t_start:t_end]
        return all(r in inner and result_consumed(inner, r, ORDER_TARGETS[r])
                   for r in ORDER_READS)
    return False


def _state_decls(code: str) -> set:
    """文件里由 `remember { mutableStateOf(...) }` 托管的 Compose 状态名。

    缝判据要判「重读的结果真的落到屏幕上那个 State」，所以目标名必须从树里
    派生（`var <name> by remember`），不能写死 —— 写死一个名字，改名/换屏
    就静默失明。
    """
    return set(re.findall(r"\bvar\s+([A-Za-z_]\w*)\s+by\s+remember\s*\{", code))


def _init_read_expr(code: str, target: str):
    """`var <target> by remember { mutableStateOf( <expr> ) }` 的初始表达式。

    这是「首次渲染读什么」的唯一权威副本；listener 的重读必须与它对称。
    """
    m = re.search(r"\bvar\s+" + re.escape(target) +
                  r"\s+by\s+remember\s*\{\s*mutableStateOf\s*\(", code)
    if not m:
        return None
    close = match_bracket(code, m.end() - 1)
    if close < 0:
        return None
    return code[m.end():close]


def _chain_suffix(expr: str, read: str):
    """`read(…)` 调用闭合之后的后缀链（`.filter { … }` 之类），空白归一。

    返回 None 表示这个表达式里根本没有 `read(` 调用。
    """
    pos = expr.find(read)
    if pos < 0:
        return None
    open_paren = expr.find("(", pos + len(read) - 1)
    if open_paren < 0:
        return None
    close = match_bracket(expr, open_paren)
    if close < 0:
        return None
    return re.sub(r"[,\s]+$", "", re.sub(r"\s+", "", expr[close + 1:]))


def _suffix_through(val: str, read: str, locals_map):
    """赋值右值相对 `read` 的后缀：直接调用，或经一跳局部绑定拼接。

    `val r = resolveOrder(prefs)` + `menuOrder = r.filter { … }` 与
    `menuOrder = resolveOrder(prefs).filter { … }` 是同一件事，必须同判。
    """
    direct = _chain_suffix(val, read)
    if direct is not None:
        return direct
    for name, rhs in locals_map.items():
        if not contains_ident(val, name):
            continue
        inner = _chain_suffix(rhs, read)
        if inner is None:
            continue
        idx = val.find(name)
        tail = re.sub(r"\s+", "", val[idx + len(name):])
        return inner + tail
    return None


def projection_mismatch(body: str, code: str):
    """listener 的重读是否丢掉了首次渲染的投影（`.filter { … }`）。

    W99 的缝：arm2 只问「`resolveOrder(prefs)` 有没有被赋给 `menuOrder`」，
    于是 `menuOrder = ChatMenuPrefs.resolveOrder(prefs)`（无 `defaultMenuVisible`
    过滤）全绿 —— 一次外部写入后列表里冒出两个底栏专属的幽灵项。判据 =
    把初始 `remember` 里的投影链当权威副本，listener 的右值必须带上同一条链。
    """
    out = []
    locals_map = _locals_map(body)
    for read in ORDER_READS:
        target = ORDER_TARGETS[read]
        init = _init_read_expr(code, target)
        if init is None:
            continue
        want = _chain_suffix(init, read)
        if not want:
            continue          # 初始读取就没有投影 → 无对称约束
        seen = []
        for m in re.finditer(re.escape(read), body):
            stmt = body[_stmt_start(body, m.start()):expr_end(body, m.start())]
            d = re.search(r"(?<![\w.])" + re.escape(target) + r"\s*=(?!=)", stmt)
            if not d:
                continue
            seen.append(_suffix_through(stmt[d.end():], read, locals_map))
        if seen and all(s != want for s in seen):
            out.append((read, target, want, seen[0]))
    return out


def _truth_under(cond: str, env):
    """把已知取值代进条件再折叠：None = 未知，False = 该分支走不到。"""
    c = cond
    for name, val in env.items():
        c = re.sub(r"(?<![\w`])" + re.escape(name) + r"(?![\w`])",
                   "true" if val else "false", c)
    return const_bool(c)


def blocked_publications(code: str, effect_body: str, load_at: int):
    """reload 之后的状态赋值，是否被「已经 loaded 了」的闩锁挡在门外。

    W98 的缝：arm7 只查 `SoulStore.load` 之前有没有早退，于是
    `load(…); if (!loaded) { name = …; body = … }` 全绿 —— 首次打开正常，
    外部写入触发 effect 重跑时整块赋值变成死代码，输入框永远停在旧值，
    下一次 Save 把外部更新覆盖回去。判据 = 把闩锁代入已知值（重跑时已为
    true）后，包裹赋值的那条条件折叠为假。
    """
    states = _state_decls(code)
    if not states:
        return []
    name_re = "|".join(sorted((re.escape(s) for s in states), key=len, reverse=True))
    out = []
    for latch in sorted(n for n in states
                        if re.search(r"(?<![\w.])" + re.escape(n) + r"\s*=\s*true\b",
                                     effect_body)):
        env = {latch: True}
        for m in re.finditer(r"(?<![\w.])(" + name_re + r")\s*=(?!=)", effect_body):
            name = m.group(1)
            if name == latch or m.start() < load_at:
                continue
            conds = [c for _k, c in enclosing_conditions(effect_body, m.start())]
            if any(_truth_under(c, env) is False for c in conds):
                out.append((name, latch,
                            next(c for c in conds if _truth_under(c, env) is False)))
                break
    return out


def prune_bool(expr: str) -> str:
    """把恒假分支从布尔表达式里剪掉：`A || (false && (B || C))` → `A`。

    arm1 的判据必须判「谓词真的能命中这个键」，而不是「这个名字在块里出现过」：
    剪枝后剩下的析取/合取骨架里若再也看不见某个固定键，就是被短路掉了。
    """
    e = strip_parens(expr.strip())
    if not e:
        return e
    parts = split_top(e, ("||",))
    if len(parts) > 1:
        keep = [p for p in (prune_bool(e[a:b]) for a, b in parts) if p != "false"]
        return " || ".join(keep) if keep else "false"
    parts = split_top(e, ("&&",))
    if len(parts) > 1:
        keep = []
        for a, b in parts:
            p = prune_bool(e[a:b])
            if p == "false":
                return "false"
            if p != "true":
                keep.append(p)
        return " && ".join(keep) if keep else "true"
    if e.startswith("!"):
        inner = prune_bool(e[1:])
        if inner == "true":
            return "false"
        if inner == "false":
            return "true"
        return "!" + inner
    b = const_bool(e)
    if b is True:
        return "true"
    if b is False:
        return "false"
    return e


def _enabled_state(code: str):
    """LogManagementScreen 里由初始 `AppLogger.isEnabled(…)` 托管的开关状态名。"""
    for m in re.finditer(r"\bvar\s+([A-Za-z_]\w*)\s+by\s+remember\s*\{\s*mutableStateOf\s*\(",
                         code):
        close = match_bracket(code, m.end() - 1)
        if close < 0:
            continue
        if "AppLogger.isEnabled(" in code[m.end():close]:
            return m.group(1)
    return None


def listener_predicate(body: str, lit_body: str):
    """(code_cond, literal_cond)：守卫分支体（含 prefsTick / order 重读）的 if 条件。"""
    for cond, t_start, t_end, _es, _ee in if_blocks(body):
        inner = body[t_start:t_end]
        if "prefsTick" not in inner and not any(r in inner for r in ORDER_READS):
            continue
        if_pos = body.rfind("if", 0, t_start)
        if if_pos < 0:
            continue
        open_paren = body.find("(", if_pos)
        close_paren = match_bracket(body, open_paren)
        if open_paren < 0 or close_paren < 0:
            continue
        return body[open_paren + 1:close_paren], lit_body[open_paren + 1:close_paren]
    return None


def brace_body(clean: str, header_re: str) -> str:
    """Brace-matched body of the first member whose header matches header_re.
    Empty string when the header is absent (callers fail closed on "")."""
    m = re.search(header_re, clean)
    if not m:
        return ""
    brace = clean.find("{", m.end())
    if brace < 0:
        return ""
    depth, i, n = 1, brace + 1, len(clean)
    while i < n and depth:
        if clean[i] == "{":
            depth += 1
        elif clean[i] == "}":
            depth -= 1
        i += 1
    return clean[brace:i]


def _rhs_text(body: str, start: int) -> str:
    """Right-hand side of an assignment starting at `start`, cut at a `;` or a
    depth-0 newline / closing brace."""
    depth = 0
    i, n = start, len(body)
    while i < n:
        ch = body[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and ch in ";\n":
            break
        i += 1
    return body[start:i]


# a value that is the same read of the same counter, possibly wrapped in a
# no-op scope function -- rewriting it changes nothing, so nothing emits.
_NOOP_SELF_RE = re.compile(
    r"(?:_writeVersion\.value|writeVersion|_writeVersion)"
    r"(?:\s*\.\s*(?:also|apply|let|run|takeIf|takeUnless)\s*\{[^{}]*\})*\s*$"
)


def _const_num(expr: str):
    """常量折叠成 float；非常量返回 None。"""
    v = const_value(expr)
    return float(v[1]) if v and v[0] == "num" else None


def bumps_write_version(body: str) -> bool:
    """True when the body actually CHANGES `_writeVersion`.

    Counter-property of the spelling hole (wide-1024): the old test was
    `_writeVersion\\.value\\s*(?:\\+=|=\\s*[^=])`, which a self-assignment
    `_writeVersion.value = _writeVersion.value` satisfies while the StateFlow
    stays at the same value and emits nothing -- every keyed reload sleeps.
    So the RHS is read, not just matched.
    """
    compound = {"+=": 0.0, "-=": 0.0, "*=": 1.0, "/=": 1.0}
    for m in re.finditer(r"_writeVersion\.value\s*(?:\+=|-=|\*=|/=)", body):
        op = m.group(0)[-2:]
        num = _const_num(body[m.end():expr_end(body, m.end())])
        if num is None:
            return True          # 未知增量：可能是 1，不据此判红
        if num != compound[op]:
            return True
    if re.search(r"_writeVersion\.value\s*(?:\+\+|--)", body):
        return True
    if re.search(r"(?:\+\+|--)\s*_writeVersion\.value", body):
        return True
    for m in re.finditer(r"_writeVersion\.value\s*=(?!=)", body):
        rhs = _rhs_text(body, m.end()).strip()
        if not rhs:
            continue
        if _NOOP_SELF_RE.match(rhs):
            continue
        return True
    return False


def check_stale_write_arms(soul_store_text: str, soul_screen_text: str,
                           log_screen_text: str):
    """[da1028-B / ISSUE-04] arms 5-8 (see module docstring). Only called when
    the caller supplied the three sources."""
    problems = []

    # ---- arm 5/6: the store's write counter ------------------------------
    store, _store_raw = views(soul_store_text)
    if store is None:
        problems.append("arm5: KotlinLexError while reading SoulStore.kt (fail-closed)")
        return problems
    if not re.search(r"\bval\s+writeVersion\b", store) or "_writeVersion" not in store:
        problems.append(
            "arm5: SoulStore exposes no writeVersion StateFlow -- outside writers "
            "(minis-config / restore / the chat memory sheet) have no way to tell "
            "Settings → Soul that the file changed"
        )
    else:
        save_body = brace_body(store, r"\bfun\s+save\s*\(\s*context\s*:")
        if not save_body:
            problems.append(
                "arm5: SoulStore.save(…) body not found -- re-point this guard"
            )
        elif not bumps_write_version(save_body):
            problems.append(
                "arm5: SoulStore.save(…) no longer bumps _writeVersion -- every "
                "external write becomes invisible to the Soul editors, so the "
                "next Save silently rolls it back"
            )
        refresh_body = brace_body(store, r"\bfun\s+refreshCache\s*\(")
        if not refresh_body:
            problems.append(
                "arm6: SoulStore.refreshCache(…) body not found -- re-point this guard"
            )
        elif not bumps_write_version(refresh_body):
            problems.append(
                "arm6: SoulStore.refreshCache(…) no longer bumps _writeVersion -- "
                "the documented \"rewritten outside save()\" path (the chat memory "
                "sheet's raw saveFile) stops notifying the editors"
            )

    # ---- arm 7: the Soul screen re-loads on that counter ------------------
    soul_clean, _soul_raw = views(soul_screen_text)
    if soul_clean is None:
        problems.append("arm7: KotlinLexError while reading SoulSettingsScreen.kt "
                        "(fail-closed)")
        return problems
    if "writeVersion" not in soul_clean:
        problems.append(
            "arm7: SoulSettingsScreen does not observe SoulStore.writeVersion -- "
            "it loads once and then keeps editing a snapshot of the file it read "
            "at open time"
        )
    else:
        keyed = None
        for m in re.finditer(r"\bLaunchedEffect\s*\(", soul_clean):
            open_paren = m.end() - 1
            close_paren = match_bracket(soul_clean, open_paren)
            if close_paren < 0:
                continue
            if "writeVersion" in soul_clean[open_paren:close_paren]:
                keyed = close_paren + 1
                break
        if keyed is None:
            problems.append(
                "arm7: no LaunchedEffect keyed on writeVersion in "
                "SoulSettingsScreen -- the reload path is not wired to the counter"
            )
        else:
            body = brace_body(soul_clean[keyed:], r"\s*")
            if "SoulStore.load(" not in body:
                problems.append(
                    "arm7: the writeVersion-keyed effect does not re-read the file "
                    "(no SoulStore.load( inside it) -- re-point this guard if the "
                    "reload moved into a helper"
                )
            else:
                # dataflow: the reload has to RUN on every key change. An exit
                # before the load turns LaunchedEffect(writeVersion) into
                # LaunchedEffect(Unit) -- the effect restarts, returns, and the
                # editor keeps the snapshot it read when the screen opened.
                load_at = body.find("SoulStore.load(")
                # 任何在 load 之前的退出都会把 LaunchedEffect(writeVersion) 退化
                # 成一次性加载：除了 `return@LaunchedEffect`，裸 `return`、
                # `?: return`、以及被字符字面量伪装过的形态同样算数（词法由共享
                # 词法器负责，`'"'` 不再能把整行抹白）。
                early = [m for m in re.finditer(r"\breturn\b", body)
                         if m.start() < load_at]
                if early:
                    problems.append(
                        "arm7: the writeVersion-keyed effect returns before "
                        "re-reading the file (%r ahead of SoulStore.load) -- it "
                        "degenerates into a one-shot load, so every external write "
                        "stays invisible to the editor"
                        % body[early[0].start():early[0].start() + 40].replace("\n", " ")
                    )
                # 对称轴（W98）：早退之外还有一条更安静的路 —— 读完文件后把状态
                # 发布整块塞进 `if (!loaded)`。重跑时 loaded 已为 true，赋值全成
                # 死代码：界面不刷新，下一次 Save 还把外部更新覆盖回去。
                for name, latch, cond in blocked_publications(soul_clean, body, load_at):
                    problems.append(
                        "arm7: the reloaded editor state `%s` is published behind `%s` -- "
                        "on a re-run `%s` is already true, so that publication block is dead "
                        "code and external writes never reach the editor"
                        % (name, cond.strip()[:50], latch)
                    )

    # ---- arm 8: the Logs screen listener ---------------------------------
    log_clean, _log_raw = views(log_screen_text)
    if log_clean is None:
        problems.append("arm8: KotlinLexError while reading LogManagementScreen.kt "
                        "(fail-closed)")
        return problems
    body, raw_body = listener_body(log_screen_text)
    if not body:
        problems.append(
            "arm8: LogManagementScreen has no DisposableEffect registering an "
            "OnSharedPreferenceChangeListener -- `minis-config set logs.enabled …` "
            "or a restore leaves the Switch on the value captured at open time"
        )
    else:
        if ".unregisterOnSharedPreferenceChangeListener" not in body:
            problems.append(
                "arm8: the logs listener is never unregistered on dispose -- "
                "the prefs file is process-scoped, so the callback outlives the "
                "screen and leaks its composition scope"
            )
        if "AppLogger.PREF_NAME" not in body or "AppLogger.KEY_ENABLED" not in body:
            problems.append(
                "arm8: the logs listener does not go through AppLogger.PREF_NAME / "
                "AppLogger.KEY_ENABLED -- a re-typed prefs/key literal drifts out "
                "of AppLogger silently (the F-239 key-name shape)"
            )
        elif '"logging_prefs"' in raw_body:
            problems.append(
                'arm8: the logs listener re-spells the prefs name literal '
                '"logging_prefs" instead of AppLogger.PREF_NAME'
            )
        # dataflow（缝 X/Y/Z 之间的空白）：调用到了 ≠ 开关会变。重读的返回值
        # 必须成为那个 State 的新值 —— `AppLogger.isEnabled(context)` 单独立在
        # 回调里，子串检查全过，Switch 却停在旧状态（W97）。
        switch_state = _enabled_state(log_clean)
        if switch_state is None:
            problems.append(
                "arm8: no `var X by remember { mutableStateOf(AppLogger.isEnabled(…)) }` "
                "declaration found in LogManagementScreen -- re-point this guard so it "
                "knows which State the listener has to publish into"
            )
        elif not re.search(r"AppLogger\s*\.\s*isEnabled\s*\(", body):
            problems.append(
                "arm8: the logs listener never re-reads AppLogger.isEnabled(…) -- an "
                "external `minis-config set logs.enabled …` can never move the Switch"
            )
        elif not result_consumed(body, "isEnabled(", switch_state):
            problems.append(
                "arm8: the logs listener calls AppLogger.isEnabled(…) but its result does "
                "not become `" + switch_state + "` -- the callback fires, the Switch keeps "
                "rendering the value captured at open time (called != consumed)"
            )

    return problems


def check_arms(screen_text: str, prefs_text: str,
               soul_store_text: str = None,
               soul_screen_text: str = None,
               log_screen_text: str = None):
    """Return (problems, ok_summary). Four + five arms, judge on stripped code.

    Arms 5-8 are judged only when the corresponding source is supplied: the
    legacy two-file fixtures predate them, the real-tree call always passes
    all three (and main() fails closed when a file is missing).
    """
    problems = []
    screen_code, _screen_raw = views(screen_text)
    if screen_code is None:
        problems.append("KotlinLexError while reading " + SCREEN_REL + " (fail-closed)")
        screen_code = ""
    body, raw_body = listener_body(screen_text)
    if not body:
        problems.append("could not locate the DisposableEffect listener block in "
                        + SCREEN_REL + " -- the registration call may have moved")
        if soul_store_text is not None or soul_screen_text is not None or log_screen_text is not None:
            problems.extend(check_stale_write_arms(
                soul_store_text or "", soul_screen_text or "", log_screen_text or ""))
        return problems, []

    # arm 1: the predicate can ACTUALLY match both fixed keys. Judged on the
    # pruned predicate skeleton (constant-false conjuncts / always-true
    # disjuncts are folded out), not on "the constant name appears in the
    # block": `… || (false && (key == A || key == B))` keeps both names and
    # never fires. The literal view is consulted for the raw-string form.
    pred = listener_predicate(body, raw_body)
    if pred is None:
        a_ok = b_ok = False
        pruned = ""
    else:
        pruned = prune_bool(pred[0])
        a_ok = contains_ident(pruned, CONST_A) or KEY_A in pred[1]
        b_ok = contains_ident(pruned, CONST_B) or KEY_B in pred[1]
    if not (a_ok and b_ok):
        missing = []
        if not a_ok:
            missing.append(KEY_A)
        if not b_ok:
            missing.append(KEY_B)
        problems.append(
            "arm1: listener predicate does not match the fixed key(s) "
            + ", ".join(missing)
            + " -- the `chatMenu.` prefix alone never fires for external writes"
            + ("; predicate skeleton after folding constant branches: "
               + " ".join(pruned.split())[:120] if pruned else "")
        )

    # arm 2: all three order re-reads present in the listener body ...
    missing = [r for r in ORDER_READS if r not in body]
    if missing:
        problems.append(
            "arm2: listener body does not re-read the order state(s): "
            + ", ".join(missing)
            + " -- an external restore of chatMenu.*Order leaves the lists stale"
        )
    # ... AND each re-read's result lands in the State it refreshes
    discarded = [r for r in ORDER_READS
                 if r in body and not result_consumed(body, r, ORDER_TARGETS[r])]
    if discarded:
        problems.append(
            "arm2: listener body calls the order re-read(s) "
            + ", ".join(discarded)
            + " but discards the result (no assignment back to "
            + ", ".join(ORDER_TARGETS[r] for r in discarded)
            + ") -- the calls fire, the States stay stale (called != consumed)"
        )

    # arm2b: ... AND the re-read is the same projection as the first
    # composition's. `menuOrder = resolveOrder(prefs)` without the
    # `defaultMenuVisible` filter reassigns a list that a fresh open would
    # never render: two footer-only entries turn into dead switches (W99).
    for read, target, want, got in projection_mismatch(body, screen_code):
        problems.append(
            "arm2: the listener re-read of " + read.strip("(") + " for `" + target
            + "` drops the first-composition projection (initial `" + want[:60]
            + "`, listener `" + (got if got is not None else "<no read>")[:60]
            + "`) -- one external write re-surfaces entries a fresh open filters out"
        )

    # arm 3: re-reads inside an EFFECTIVE draggingEntry == null guard
    if not guarded_reads(body):
        problems.append(
            "arm3: the order re-reads are not inside the `draggingEntry == null` "
            "guard -- an async listener notification can interrupt an in-flight "
            "drag or overwrite the commit* write-back"
        )

    # arm 4: both key constants non-private in ChatMenuPrefs
    clean_prefs, _prefs_raw = views(prefs_text)
    if clean_prefs is None:
        clean_prefs = ""
    for name in (CONST_A, CONST_B):
        m = re.search(r"(private\s+)?const\s+val\s+" + name + r"\b", clean_prefs)
        if not m:
            problems.append(
                "arm4: ChatMenuPrefs." + name + " declaration not found -- "
                "the constant may have been renamed; re-point this guard"
            )
        elif m.group(1):
            problems.append(
                "arm4: ChatMenuPrefs." + name + " is `private const val` -- "
                "the screen cannot reference it and will drift to a second "
                "string copy (same-family recurrence shape)"
            )

    # arms 5-8: the two stale-write screens ([da1028-B / ISSUE-04])
    if soul_store_text is not None or soul_screen_text is not None or log_screen_text is not None:
        problems.extend(check_stale_write_arms(
            soul_store_text or "", soul_screen_text or "", log_screen_text or ""))

    return problems, []


def run_selftest() -> bool:
    """Compliant fixture green + 4 violation fixtures red."""
    COMPLIANT_SCREEN = '''
@Composable
fun Screen() {
    var menuOrder by remember {
        mutableStateOf(
            ChatMenuPrefs.resolveOrder(prefs)
                .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false },
        )
    }
    var footerOrder by remember {
        mutableStateOf(
            ChatMenuPrefs.settingsPinOrder(prefs),
        )
    }
    var attachOrder by remember {
        mutableStateOf(
            ChatMenuPrefs.resolveAttachOrder(prefs),
        )
    }
    var draggingEntry by remember { mutableStateOf<String?>(null) }
    var prefsTick by remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val prefs = context.applicationContext
            .getSharedPreferences(P, MODE_PRIVATE)
        val listener =
            OnSharedPreferenceChangeListener { _, key ->
                if (key != null && (
                        key.startsWith("chatMenu.") ||
                            key == ChatMenuPrefs.TOP_BAR_INPUT_HISTORY_PREF ||
                            key == ChatMenuPrefs.COMPOSER_MODEL_PICKER_BUTTON_PREF
                    )
                ) {
                    prefsTick++
                    if (draggingEntry == null) {
                        menuOrder = ChatMenuPrefs.resolveOrder(prefs)
                            .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false }
                        footerOrder = ChatMenuPrefs.settingsPinOrder(prefs)
                        attachOrder = ChatMenuPrefs.resolveAttachOrder(prefs)
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
'''
    COMPLIANT_PREFS = '''
object ChatMenuPrefs {
    const val TOP_BAR_INPUT_HISTORY = "topBar.inputHistory"
    internal const val TOP_BAR_INPUT_HISTORY_PREF = "topBar.inputHistory.visible"
    const val COMPOSER_MODEL_PICKER_BUTTON = "composer.modelPickerButton"
    internal const val COMPOSER_MODEL_PICKER_BUTTON_PREF = "composer.modelPickerButton.visible"
}
'''
    M1_SCREEN = COMPLIANT_SCREEN.replace(
        'key == ChatMenuPrefs.TOP_BAR_INPUT_HISTORY_PREF ||\n'
        '                            key == ChatMenuPrefs.COMPOSER_MODEL_PICKER_BUTTON_PREF',
        'false')
    M2_SCREEN = COMPLIANT_SCREEN.replace(
        '''menuOrder = ChatMenuPrefs.resolveOrder(prefs)
                            .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false }
                        footerOrder = ChatMenuPrefs.settingsPinOrder(prefs)
                        attachOrder = ChatMenuPrefs.resolveAttachOrder(prefs)''',
        'prefsTick++')
    M3_SCREEN = COMPLIANT_SCREEN.replace("if (draggingEntry == null) {", "if (true) {")
    M4_PREFS = COMPLIANT_PREFS.replace("internal const val", "private const val")

    cases = [
        ("compliant", COMPLIANT_SCREEN, COMPLIANT_PREFS, 0),
        ("M1 predicate missing fixed keys", M1_SCREEN, COMPLIANT_PREFS, 1),
        ("M2 no order re-reads", M2_SCREEN, COMPLIANT_PREFS, 1),
        ("M3 no draggingEntry guard", M3_SCREEN, COMPLIANT_PREFS, 1),
        ("M4 constants private again", COMPLIANT_SCREEN, M4_PREFS, 1),
    ]
    ok = True
    for name, screen, prefs, want_red in cases:
        problems, _ = check_arms(screen, prefs)
        red = len(problems) > 0
        status = "RED" if red else "GREEN"
        expected = "RED" if want_red else "GREEN"
        mark = "ok" if red == bool(want_red) else "FAIL"
        if mark == "FAIL":
            ok = False
        print(f"  selftest {name}: {status} (expect {expected}) {mark}")
        for p in problems:
            print(f"      - {p}")

    # shape edge: no listener at all -> explicit red, not a silent pass
    problems, _ = check_arms("@Composable fun S() {}", COMPLIANT_PREFS)
    if not problems:
        print("  selftest no-listener: expected explicit red, got GREEN -- FAIL")
        ok = False
    else:
        print("  selftest no-listener: RED (expect RED) ok")

    # ── arms 5-8 fixtures ([da1028-B / ISSUE-04]) ──────────────────────────
    COMPLIANT_STORE = '''
object SoulStore {
    private val _writeVersion = MutableStateFlow(0L)
    val writeVersion: StateFlow<Long> = _writeVersion.asStateFlow()

    fun refreshCache(context: Context) {
        val parsed = load(context)
        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 1
    }

    fun save(context: Context, file: SoulFile) {
        context.filesDir.resolve("SOUL.md").writeText(render(file))
        _cachedMetadata.value = file.metadata
        _writeVersion.value += 1
    }
}
'''
    COMPLIANT_SOUL_SCREEN = '''
@Composable
fun SoulSettingsScreen(onBack: () -> Unit) {
    val writeVersion by SoulStore.writeVersion.collectAsState()
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    LaunchedEffect(writeVersion) {
        val parsed = SoulStore.load(context) ?: SoulFile.defaults()
        name = parsed.metadata.name
        body = parsed.body
        loaded = true
    }
}
'''
    COMPLIANT_LOG_SCREEN = '''
@Composable
fun LogManagementScreen(context: Context) {
    var loggingEnabled by remember { mutableStateOf(AppLogger.isEnabled(context)) }
    DisposableEffect(context) {
        val prefs = context.applicationContext.getSharedPreferences(
            AppLogger.PREF_NAME,
            Context.MODE_PRIVATE,
        )
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == AppLogger.KEY_ENABLED) {
                    loggingEnabled = AppLogger.isEnabled(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
'''
    S5_STORE = COMPLIANT_STORE.replace(
        "        _cachedMetadata.value = file.metadata\n        _writeVersion.value += 1",
        "        _cachedMetadata.value = file.metadata")
    S6_STORE = COMPLIANT_STORE.replace(
        """        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 1""",
        "        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT")
    S7_SCREEN = COMPLIANT_SOUL_SCREEN.replace("LaunchedEffect(writeVersion)", "LaunchedEffect(Unit)")
    S8A_SCREEN = COMPLIANT_LOG_SCREEN.replace(
        "        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }\n", "")
    S8B_SCREEN = COMPLIANT_LOG_SCREEN.replace("AppLogger.PREF_NAME", '"logging_prefs"')
    S8C_SCREEN = COMPLIANT_LOG_SCREEN

    def arm(name, want, s_store, s_screen, l_screen):
        problems, _ = check_arms(COMPLIANT_SCREEN, COMPLIANT_PREFS, s_store, s_screen, l_screen)
        red = len(problems) > 0
        mark = "ok" if red == bool(want) else "FAIL"
        print(f"  selftest arm5-8 {name}: {'RED' if red else 'GREEN'} "
              f"(expect {'RED' if want else 'GREEN'}) {mark}")
        for p in problems:
            print(f"      - {p}")
        return mark == "ok"

    ok &= arm("compliant stale-write surfaces", 0,
              COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm("arm5 save() stops bumping writeVersion", 1,
              S5_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm("arm6 refreshCache() stops bumping writeVersion", 1,
              S6_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm("arm7 soul screen loads once (LaunchedEffect(Unit))", 1,
              COMPLIANT_STORE, S7_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm("arm8a listener never unregistered", 1,
              COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, S8A_SCREEN)
    ok &= arm("arm8b prefs name re-spelled as a literal", 1,
              COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, S8B_SCREEN)
    ok &= arm("arm8c listener block absent (fail-closed)", 1,
              COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, S8C_SCREEN.replace(
                  "DisposableEffect(context) {", "if (true) {"))

    # ── wide-1024 witnesses: the three shapes that defeated this gate ──────
    # (each is the find→replace from the wide-sweep report, replayed verbatim)
    W2_SCREEN = COMPLIANT_SCREEN.replace(
        '''menuOrder = ChatMenuPrefs.resolveOrder(prefs)
                            .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false }
                        footerOrder = ChatMenuPrefs.settingsPinOrder(prefs)
                        attachOrder = ChatMenuPrefs.resolveAttachOrder(prefs)''',
        '''ChatMenuPrefs.resolveOrder(prefs)
                        ChatMenuPrefs.settingsPinOrder(prefs)
                        ChatMenuPrefs.resolveAttachOrder(prefs)''')
    W5_STORE = COMPLIANT_STORE.replace(
        """        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 1""",
        """        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value = _writeVersion.value""")
    W7_SCREEN = COMPLIANT_SOUL_SCREEN.replace(
        "    LaunchedEffect(writeVersion) {\n",
        "    LaunchedEffect(writeVersion) {\n        if (loaded) return@LaunchedEffect\n")
    # clean counter-arms: legitimate refactors must stay green
    L2_SCREEN = COMPLIANT_SCREEN.replace(
        '''menuOrder = ChatMenuPrefs.resolveOrder(prefs)
                            .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false }''',
        '''val resolvedOrder = ChatMenuPrefs.resolveOrder(prefs)
                        menuOrder = resolvedOrder.filter {
                            ChatActionCatalog.spec(it)?.defaultMenuVisible != false
                        }''')
    W5_OK_STORE = COMPLIANT_STORE.replace(
        "_writeVersion.value += 1",
        "_writeVersion.value = _writeVersion.value + 1")

    for name, screen, prefs, want_red in (
        ("W2 order re-reads discard their results", W2_SCREEN, COMPLIANT_PREFS, 1),
        ("L2 local-then-assign still consumed", L2_SCREEN, COMPLIANT_PREFS, 0),
    ):
        problems, _ = check_arms(screen, prefs)
        red = len(problems) > 0
        mark = "ok" if red == bool(want_red) else "FAIL"
        if mark == "FAIL":
            ok = False
        print(f"  selftest wide {name}: {'RED' if red else 'GREEN'} "
              f"(expect {'RED' if want_red else 'GREEN'}) {mark}")
        for p in problems:
            print(f"      - {p}")

    ok &= arm("W5 refreshCache self-assigns writeVersion (spelling)", 1,
              W5_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm("W5ok writeVersion = writeVersion + 1 still bumps", 0,
              W5_OK_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm("W7 soul effect exits before re-reading (dataflow)", 1,
              COMPLIANT_STORE, W7_SCREEN, COMPLIANT_LOG_SCREEN)

    # ── batch7-t12 witnesses（审计方给的补丁逐字重放）────────────────────
    # 06-1 谓词保留两个常量名，但整段被 `false &&` 短路
    T1_SCREEN = COMPLIANT_SCREEN.replace(
        '''key.startsWith("chatMenu.") ||
                            key == ChatMenuPrefs.TOP_BAR_INPUT_HISTORY_PREF ||
                            key == ChatMenuPrefs.COMPOSER_MODEL_PICKER_BUTTON_PREF''',
        '''key.startsWith("chatMenu.") ||
                            (false && (key == ChatMenuPrefs.TOP_BAR_INPUT_HISTORY_PREF ||
                            key == ChatMenuPrefs.COMPOSER_MODEL_PICKER_BUTTON_PREF))''')
    # 06-2 赋值语尾 `.let { <旧状态> }`：赋值号在，值被换回旧列表
    T2_SCREEN = COMPLIANT_SCREEN.replace(
        "footerOrder = ChatMenuPrefs.settingsPinOrder(prefs)",
        "footerOrder = ChatMenuPrefs.settingsPinOrder(prefs).let { footerOrder }").replace(
        "attachOrder = ChatMenuPrefs.resolveAttachOrder(prefs)",
        "attachOrder = ChatMenuPrefs.resolveAttachOrder(prefs).let { attachOrder }")
    # 06-3 守卫原串保留，右置 `|| true` 让它恒真
    T3_SCREEN = COMPLIANT_SCREEN.replace(
        "if (draggingEntry == null) {", "if (draggingEntry == null || true) {")
    # 06-4 复合赋值的加法单位元
    T4_STORE = COMPLIANT_STORE.replace(
        """        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 1""",
        """        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 0""")
    # 06-5 字符字面量 `'"'` 伪装掉 `if (loaded) return@LaunchedEffect`
    T5_SCREEN = COMPLIANT_SOUL_SCREEN.replace(
        "    LaunchedEffect(writeVersion) {\n",
        "    LaunchedEffect(writeVersion) {\n"
        "        val _q1 = '\"'; if (loaded) return@LaunchedEffect; val _q2 = '\"'\n")

    def arm_expect(name, want_red_prefixes, s_store, s_screen, l_screen,
                   screen=COMPLIANT_SCREEN, prefs=COMPLIANT_PREFS, log=None):
        problems, _ = check_arms(screen, prefs, s_store, s_screen, log or l_screen)
        reds = sorted(p.split(":")[0] for p in problems)
        ok_case = True
        if want_red_prefixes is None:
            if reds:
                ok_case = False
        else:
            for pre in want_red_prefixes:
                if pre not in reds:
                    ok_case = False
            extra = [r for r in reds if r not in want_red_prefixes]
            if extra:
                ok_case = False
        print(f"  selftest t12 {name}: RED={reds or 'none'} "
              f"(expect {want_red_prefixes or 'none'}) {'ok' if ok_case else 'FAIL'}")
        for p in problems:
            print(f"      - {p}")
        return ok_case

    ok &= arm_expect("06-1 constant-false conjunct swallows both fixed keys", ["arm1"],
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     screen=T1_SCREEN)
    ok &= arm_expect("06-2 assignment tail swaps the value back", ["arm2", "arm3"],
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     screen=T2_SCREEN)
    ok &= arm_expect("06-3 guard made always-true with `|| true`", ["arm3"],
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     screen=T3_SCREEN)
    ok &= arm_expect("06-4 compound assignment with the additive identity", ["arm6"],
                     T4_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm_expect("06-5 char literal hides the early return", ["arm7"],
                     COMPLIANT_STORE, T5_SCREEN, COMPLIANT_LOG_SCREEN)

    # ── r3-t05：三条「缝上的见证」（dual-gate #97 / 对称轴 #98 #99） ────────
    # 每条都是判据只能看见自己那一半时的空白：调用到了却没赋值、读过文件却把
    # 发布挡在闩锁后面、重读了却没带首次渲染的投影。
    R3_LOG_DISCARDED = COMPLIANT_LOG_SCREEN.replace(
        "                    loggingEnabled = AppLogger.isEnabled(context)\n",
        "                    AppLogger.isEnabled(context)\n")
    R3_LOG_ONE_HOP = COMPLIANT_LOG_SCREEN.replace(
        "                    loggingEnabled = AppLogger.isEnabled(context)\n",
        "                    val nowEnabled = AppLogger.isEnabled(context)\n"
        "                    loggingEnabled = nowEnabled\n")
    R3_SOUL_GUARDED = COMPLIANT_SOUL_SCREEN.replace(
        "        name = parsed.metadata.name\n"
        "        body = parsed.body\n"
        "        loaded = true\n",
        "        if (!loaded) {\n"
        "            name = parsed.metadata.name\n"
        "            body = parsed.body\n"
        "            loaded = true\n"
        "        }\n")
    R3_MENU_NO_FILTER = COMPLIANT_SCREEN.replace(
        "                        menuOrder = ChatMenuPrefs.resolveOrder(prefs)\n"
        "                            .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false }\n",
        "                        menuOrder = ChatMenuPrefs.resolveOrder(prefs)\n")
    R3_MENU_HOISTED = COMPLIANT_SCREEN.replace(
        "                        menuOrder = ChatMenuPrefs.resolveOrder(prefs)\n"
        "                            .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false }\n",
        "                        val resolvedOrder = ChatMenuPrefs.resolveOrder(prefs)\n"
        "                        menuOrder = resolvedOrder.filter {\n"
        "                            ChatActionCatalog.spec(it)?.defaultMenuVisible != false\n"
        "                        }\n")

    ok &= arm_expect("r3-97 listener re-reads isEnabled but discards it", ["arm8"],
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     log=R3_LOG_DISCARDED)
    ok &= arm_expect("r3-97 control one-hop binding still publishes", None,
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     log=R3_LOG_ONE_HOP)
    ok &= arm_expect("r3-98 editor publications gated behind `if (!loaded)`", ["arm7"],
                     COMPLIANT_STORE, R3_SOUL_GUARDED, COMPLIANT_LOG_SCREEN)
    ok &= arm_expect("r3-99 listener re-read drops the first-composition filter",
                     ["arm2"], COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN,
                     COMPLIANT_LOG_SCREEN, screen=R3_MENU_NO_FILTER)
    ok &= arm_expect("r3-99 control filter hoisted to a local keeps symmetry", None,
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     screen=R3_MENU_HOISTED)

    # 对照臂：等价且合法的写法必须仍然 GREEN
    ok &= arm_expect("control identity `.let { it }` stays consumed", None,
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     screen=COMPLIANT_SCREEN.replace(
                         "footerOrder = ChatMenuPrefs.settingsPinOrder(prefs)",
                         "footerOrder = ChatMenuPrefs.settingsPinOrder(prefs).let { it }"))
    ok &= arm_expect("control `+= 2` still bumps", None,
                     COMPLIANT_STORE.replace(
                         """        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 1""",
                         """        _cachedMetadata.value = parsed?.metadata ?: SoulMetadata.DEFAULT
        _writeVersion.value += 2"""),
                     COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN)
    ok &= arm_expect("control guard with a real extra condition stays green", None,
                     COMPLIANT_STORE, COMPLIANT_SOUL_SCREEN, COMPLIANT_LOG_SCREEN,
                     screen=COMPLIANT_SCREEN.replace(
                         "if (draggingEntry == null) {",
                         "if (draggingEntry == null && prefsTick >= 0) {"))
    return ok


def main() -> int:
    if "--selftest" in sys.argv:
        print("chat_menu_prefs_listener_check selftest:")
        if not run_selftest():
            print("FAIL: selftest")
            return 1
        print(MARKER)
        print("CHAT_MENU_PREFS_LISTENER_SELFTEST_ARMS=31")
        return 0

    screen_path = ROOT / SCREEN_REL
    prefs_path = ROOT / PREFS_REL
    soul_store_path = ROOT / SOUL_STORE_REL
    soul_screen_path = ROOT / SOUL_SCREEN_REL
    log_screen_path = ROOT / LOG_SCREEN_REL
    for rel, p in ((SCREEN_REL, screen_path), (PREFS_REL, prefs_path),
                   (SOUL_STORE_REL, soul_store_path),
                   (SOUL_SCREEN_REL, soul_screen_path),
                   (LOG_SCREEN_REL, log_screen_path)):
        if not p.exists():
            print(f"FAIL: missing {rel}")
            return 1
    problems, _ = check_arms(screen_path.read_text(encoding="utf-8", errors="replace"),
                             prefs_path.read_text(encoding="utf-8", errors="replace"),
                             soul_store_path.read_text(encoding="utf-8", errors="replace"),
                             soul_screen_path.read_text(encoding="utf-8", errors="replace"),
                             log_screen_path.read_text(encoding="utf-8", errors="replace"))
    if problems:
        print("FAIL: settings listener / stale-write gaps")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("OK: chat menu prefs listener matches both fixed keys, "
          "re-reads all three orders inside the draggingEntry guard, "
          "both key constants are non-private, and both stale-write screens "
          "re-seed on external writes (7 sites checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
