#!/usr/bin/env python3
"""deep-audit-1021 FIX-H (ISSUE-11, P2) — MinisOpenUrlBroker scheme gate.

Pins the contract fixed by branch fix/da1021-open-url-broker-scheme for
terminal/MinisOpenUrlBroker.kt: BOTH entry points must funnel through the
single `isSupportedScheme` whitelist before anything lands in `_pendingUrl`.

Bug context (audit-0917 half-fix): `offer(uri: Uri)` published whatever a
caller handed it — no scheme check — while the `[audit-0917]` comment sitting
on `offer(uriString: String)` claimed "Same scheme gate as offer(Uri)". The
comment described a defect that did not exist on the String overload (which
always had its own gate) and the Uri overload itself stayed bare.

Criteria
  U1  the `offer(uri: Uri)` body has a REACHABLE `isSupportedScheme(` call
      (a bare `_pendingUrl.value = uri` revert, or a gate parked in a dead
      `if (false) { … }`, is RED);
  U2a the String overload either delegates to `offer(parsed)` on a reachable
      path or gates on a scheme derived from `parsed` (a delegation parked in
      `if (false) offer(parsed)` is RED), and
  U2b the file has exactly ONE non-null publish point — counted across ALL
      StateFlow publish spellings (`.value =`, `compareAndSet`, `tryEmit`,
      `emit`, `update`, `setValue`), not just `.value =`;
  U3  inside `offer(uri: Uri)` the scheme check precedes the publish;
  U4  the whitelist is neither weakened nor widened: it lowercases,
      fail-closes on null, and the string literals in its return value are
      EXACTLY {http, https, about, minis} (an extra `|| s == "javascript"`
      arm is RED — subset checks cannot see a widened whitelist);
  U8  the guard TERMINATES control flow (or wraps the publish) and EVERY
      publish site is protected by it — `if (!isSupportedScheme(…)) Unit`,
      an empty block, an inverted arm that publishes, and a second
      unprotected publish point are all RED;
  U8b the guard judges the CALLER's Uri: the argument handed to
      `isSupportedScheme(…)` must derive from the overload's own parameter
      (directly or through one local hop). A constant (`"http"`, `"minis"`)
      makes the guard a no-op while leaving every other criterion green.

Consumer half (deep-audit-1027 FIX-G, ISSUE-07):
  U5  `ui/terminal/TerminalScreen.kt`, inside its `LaunchedEffect(pendingUrl)`
      block, must call `MinisOpenUrlBroker.consume()` ONLY inside a
      `if (MinisOpenUrlBroker.isWebScheme(<the pending Uri>))` branch whose
      condition is neither constantly true nor constantly false, and the
      branch body must stay MODELLED: only presentation assignments,
      `consume()`, logging and control flow — no local declarations, and no
      string/char literal whose content carries `{`, `}`, `"` or `'`
      (U5d, fail-closed: the gate refuses to vouch for an anchor region whose
      brace accounting depends on literal content, which is exactly the
      surface every parser-death witness used to smuggle `consume()` out of
      the branch).
  Anchors are fail-closed; U5 is skipped when TerminalScreen.kt is absent.

Terminal deep-link entries (deep-audit-1032 FIX-D, ISSUE-05):
  U6  `MainActivity.kt` hot deep link and
  U7  `AppNavigation.kt` cold-start deep link
      must hand `Routes.terminal(…)` a session id that came out of the alias
      table (`ChatViewModelStore.resolveSessionId(…)` / `.activeSessionId`,
      directly or through a local val). The VALUE is what is judged — the
      argument expression is folded through `value_sources`, so
      `ChatViewModelStore.activeSessionId.let { null }` (text present, value
      discarded) and `<id>.also { … }` are RED.

Implementation note: every structural read goes through the shared lexer
(`_kotlin_lexer.analyse`) and the shared AST/value-flow primitives. No local
comment stripper, brace matcher or constant folding — those are per-gate
copies of the same three bugs this wave is fixing. A source that fails to lex
is a violation (fail-closed), never a fallback to raw text.

Usage: python3 open_url_broker_scheme_check.py [ROOT] [--root ROOT]
       python3 open_url_broker_scheme_check.py --selftest
Exit:  0 = gate green, 1 = violations found (or selftest failures).
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_ast import (const_value, if_blocks, is_pure_literal, match_bracket,
                         split_top, stmt_spans, strip_parens)
from _kotlin_lexer import KotlinLexError
from _kotlin_lexer import analyse as lex_kotlin
from _kotlin_valueflow import (brace_depth, call_args, contains_ident, expr_end,
                               live_positions, path_feasible, returns_exiting,
                               value_sources)

BROKER_REL = "src/android/app/src/main/java/com/rikkaminis/app/terminal/MinisOpenUrlBroker.kt"
TERMINAL_REL = "src/android/app/src/main/java/com/rikkaminis/app/ui/terminal/TerminalScreen.kt"


# --------------------------------------------------------------------------
# 视图 / 结构提取（全部走共享层）
# --------------------------------------------------------------------------


def views(src):
    """(code, literal) —— 两个等长视图；解析失败抛 KotlinLexError（fail-closed）。"""
    ok, reason, v = lex_kotlin(src)
    if not ok:
        raise KotlinLexError(reason)
    return v["code"], v["comments"]


def extract_span(code, decl_re):
    """函数声明（含体外花括号）的 [start, end)；缺失/歧义 → None（fail-closed）。"""
    m = decl_re.search(code)
    if not m:
        return None
    if decl_re.search(code, m.end()):
        return None  # ambiguous
    i = m.end() - 1
    if i < 0 or i >= len(code) or code[i] != "{":
        return None
    close = match_bracket(code, i)
    if close < 0:
        return None
    return (m.start(), close + 1)


def extract_fun(code, decl_re):
    span = extract_span(code, decl_re)
    return None if span is None else code[span[0]:span[1]]


def param_names(code, decl_re):
    """声明里的形参名（`fun offer(uri: Uri)` → ['uri']）。"""
    m = decl_re.search(code)
    if not m:
        return []
    p_open = code.index("(", m.start())
    p_close = match_bracket(code, p_open)
    if p_close < 0:
        return []
    out = []
    for a, b in split_top(code[p_open + 1:p_close], (",",)):
        piece = code[p_open + 1 + a:p_open + 1 + b].split(":")[0].strip()
        piece = re.sub(r"^(?:vararg|noinline|crossinline)\s+", "", piece).strip()
        if re.fullmatch(r"[A-Za-z_]\w*", piece):
            out.append(piece)
    return out


def call_arg(text, open_paren_end):
    """单个实参切片（`(` 之后的位置 → 配对 `)` 之前）。配平失败返回 ""。"""
    close = match_bracket(text, open_paren_end - 1)
    if close < 0:
        return ""
    return text[open_paren_end:close].strip()


def _local_init(text, ident):
    """`val <ident> = <rhs>` 的 rhs（一跳局部绑定；找不到 → None）。"""
    m = re.search(r"(?m)^[ \t]*(?:val|var)\s+%s\s*(?::[^=\n]+)?=(?!=)"
                  % re.escape(ident), text)
    if not m:
        return None
    return text[m.end():expr_end(text, m.end())]


def derives_from(text, arg, roots):
    """实参是否由 roots 中的某个名字派生（含一跳局部 val）。

    常量实参（`"http"` / `"minis"` / 任何可折叠表达式）一律 False —— 那是
    「守卫还在、但判的不是调用方给的 Uri」的形态。
    """
    a = strip_parens(arg or "")
    if not a:
        return False
    if is_pure_literal(a):
        return False
    for src in value_sources(a):
        if any(contains_ident(src, r) for r in roots):
            return True
    for ident in sorted(set(re.findall(r"[A-Za-z_]\w*", a))):
        if ident in ("true", "false", "null", "it"):
            continue
        if ident in roots:
            return True
        rhs = _local_init(text, ident)
        if rhs is None:
            continue
        for src in value_sources(rhs):
            if any(contains_ident(src, r) for r in roots):
                return True
    return False


RE_OFFER_URI = re.compile(r"\bfun\s+offer\(\s*uri:\s*Uri\s*\)\s*\{")
RE_OFFER_STR = re.compile(r"\bfun\s+offer\(\s*uriString:\s*String\s*\)\s*\{")
RE_IS_SUP = re.compile(r"\bfun\s+isSupportedScheme\(\s*scheme:\s*String\?\s*\)\s*:\s*Boolean\s*\{")

GUARD_CALL_RE = re.compile(r"(?<![\w])isSupportedScheme\s*\(")
# Every StateFlow publish spelling. `.value = null` (consume) is not a publish.
RE_PUBLISH = re.compile(
    r"_pendingUrl\s*\.\s*(?:value\s*=\s*(?!null\b)(?=\S)"
    r"|(?:compareAndSet|tryEmit|emit|update|setValue)\s*\()")
WHITELIST_SCHEMES = ("http", "https", "about", "minis")


def return_literals(code_text, lit_text):
    """`return` 表达式里出现的字符串/字符字面量集合（值通道读内容）。"""
    out = set()
    for m in re.finditer(r"(?<![\w])return\b", code_text):
        end = expr_end(code_text, m.end())
        expr = lit_text[m.end():end]
        out |= set(re.findall(r'"([^"\\]*)"', expr))
        out |= set(re.findall(r"'([^'\\]*)'", expr))
    return out


def analyse(code_raw):
    """Return [(criterion, ok, detail)]."""
    try:
        code, lit = views(code_raw)
    except KotlinLexError as exc:
        return [("U0 Kotlin source is lexable", False,
                 "lexer failure: %s — fail-closed" % exc)]
    results = []

    body_uri = extract_fun(code, RE_OFFER_URI)
    if body_uri is None:
        results.append(("U1 offer(uri: Uri) present and unambiguous", False,
                        "declaration missing or ambiguous — fail-closed"))
        return results

    # --- U1: the Uri overload consults the whitelist (reachable) ---
    guard_pos = live_positions(body_uri, GUARD_CALL_RE)
    results.append(("U1 offer(uri: Uri) consults isSupportedScheme on a reachable path",
                    bool(guard_pos),
                    "no reachable scheme gate in the Uri overload "
                    "(missing, or parked in a dead branch)"))

    # --- U2a: the String overload funnels through the same gate ---
    body_str = extract_fun(code, RE_OFFER_STR)
    if body_str is None:
        results.append(("U2a offer(uriString: String) present and unambiguous", False,
                        "declaration missing or ambiguous — fail-closed"))
        return results
    gated = any(derives_from(body_str, call_arg(body_str, m.end()), ["parsed"])
                for m in GUARD_CALL_RE.finditer(body_str)
                if path_feasible(body_str, m.start()))
    delegates = bool(live_positions(body_str, re.compile(r"\boffer\(\s*parsed\s*\)")))
    results.append(("U2a offer(String) funnels through the same gate",
                    gated or delegates,
                    "must gate on the scheme of `parsed` or delegate to offer(parsed) "
                    "on a reachable path"))

    # --- U2b: exactly one publish point, whatever the spelling ---
    n_writes = len(RE_PUBLISH.findall(code))
    results.append(("U2b exactly one non-null _pendingUrl publish point",
                    n_writes == 1,
                    "found %d publish site(s) (.value=/compareAndSet/tryEmit/emit/update); "
                    "a single shared gate requires exactly 1" % n_writes))

    # --- U3: publish must come after the check ---
    idx_gate = guard_pos[0] if guard_pos else -1
    m_write = RE_PUBLISH.search(body_uri)
    idx_write = m_write.start() if m_write else -1
    ok3 = idx_gate >= 0 and idx_write >= 0 and idx_gate < idx_write
    results.append(("U3 scheme check precedes the _pendingUrl publish", ok3,
                    "gate idx=%d write idx=%d" % (idx_gate, idx_write)))

    # --- U4: whitelist neither weakened nor widened ---
    span_sup = extract_span(code, RE_IS_SUP)
    if span_sup is None:
        results.append(("U4 isSupportedScheme present and unambiguous", False,
                        "whitelist missing or ambiguous — fail-closed"))
        return results
    s, e = span_sup
    body_sup, lit_sup = code[s:e], lit[s:e]
    ok_lower = ".lowercase()" in body_sup
    ok_null = re.search(r"\?\s*:\s*return\s+false", body_sup) is not None
    lits = return_literals(body_sup, lit_sup)
    ok_list = all(x in lits for x in WHITELIST_SCHEMES)
    extra = sorted(lits - set(WHITELIST_SCHEMES))
    ok_exact = not extra
    results.append(
        ("U4 whitelist is exactly http/https/about/minis (not weakened, not widened)",
         ok_lower and ok_null and ok_list and ok_exact,
         "lowercase=%s null-close=%s required=%s extra=%s"
         % (ok_lower, ok_null, ok_list, extra or "none")))

    # --- U8 / U8b ---
    results.extend(analyse_guard_termination(
        body_uri, [m.start() for m in RE_PUBLISH.finditer(body_uri)],
        param_names(code, RE_OFFER_URI)))
    return results


RE_IF = re.compile(r"\bif\s*\(")
# Negation spellings accepted on the guard call: `!…`, `!(…)`, `….not()`,
# `… == false` (the common Kotlin equivalents; keep the list narrow — the
# point is to accept real code, not to enumerate every exotic form).
RE_NOT_SUFFIX = re.compile(r"\.not\s*\(")
RE_FALSE_SUFFIX = re.compile(r"==\s*false|!=\s*true")


def _preceded_by_not(cond, call_start):
    """True when the call at call_start is negated (`!…`, `!(…)`, `….not()`,
    `… == false`, `… != true`)."""
    j = call_start - 1
    while j >= 0 and cond[j] in " \t\r\n(":
        j -= 1
    if j >= 0 and cond[j] == "!":
        return True
    op = call_start + len("isSupportedScheme")
    while op < len(cond) and cond[op] in " \t\r\n":
        op += 1
    if op >= len(cond) or cond[op] != "(":
        return False
    close = match_bracket(cond, op)
    if close < 0:
        return False
    tail = cond[close + 1:].lstrip()
    return bool(RE_NOT_SUFFIX.match(tail) or RE_FALSE_SUFFIX.match(tail))


def _arm_terminates(inner):
    """守卫臂是否真的退出外层函数（`return` / `throw`，且不在嵌套 lambda 里）。"""
    if returns_exiting(inner, 0, len(inner), 0):
        return True
    for m in re.finditer(r"(?<![\w])throw\b", inner):
        if brace_depth(inner, m.start()) == 0:
            return True
    return False


def _arm_inner(consequent):
    """守卫臂的语句文本：花括号块取内部，裸单语句原样。"""
    text = consequent.strip()
    if text.startswith("{") and text.endswith("}"):
        return text[1:-1]
    return text


def analyse_guard_termination(body, publishes, roots):
    """U8 — the guard must terminate control flow / wrap every publish site.
    U8b — the guard must judge the caller's Uri, not a constant."""
    label8 = "U8 isSupportedScheme guard terminates control flow (no Unit/no-op arm)"
    label8b = "U8b the guard judges the caller's Uri (no constant argument)"
    if not GUARD_CALL_RE.search(body):
        # No guard call at all: U1 already owns that shape; keep the criterion
        # list stable so fail-closed fixtures cannot double-count it.
        return []
    problems = []
    protected = set()
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(body):
        m = GUARD_CALL_RE.search(cond)
        if not m:
            continue
        negated = _preceded_by_not(cond, m.start())
        inside = [p for p in publishes if t_start <= p < t_end]
        if negated:
            if _arm_terminates(_arm_inner(body[t_start:t_end])):
                protected.update(p for p in publishes if p >= t_end)
            elif inside:
                problems.append("negated guard arm PUBLISHES instead of rejecting "
                                "— inverted guard")
            else:
                problems.append("negated guard arm does not terminate control flow "
                                "(Unit/no-op/other statement)")
        else:
            if inside:
                protected.update(inside)
            else:
                problems.append("positive guard arm neither wraps the publish nor "
                                "terminates — publish reachable without the gate")
    if publishes and any(p not in protected for p in publishes):
        problems.append("a publish site is reachable without a terminating guard "
                        "(unprotected %d of %d)"
                        % (len([p for p in publishes if p not in protected]),
                           len(publishes)))
    if not problems and not protected:
        problems.append("no isSupportedScheme guard terminates control flow or wraps "
                        "the publish — fail-closed")
    out = [(label8, not problems, "; ".join(sorted(set(problems))) if problems
            else "guard arm terminates / wraps the publish")]

    arg_problems = []
    for m in GUARD_CALL_RE.finditer(body):
        if not path_feasible(body, m.start()):
            continue
        if match_bracket(body, m.end() - 1) < 0:
            arg_problems.append("unbalanced guard call at offset %d — fail-closed"
                                % m.start())
            continue
        arg = call_arg(body, m.end())
        if not derives_from(body, arg, roots):
            arg_problems.append(
                "guard argument %r is not derived from the caller's Uri (%s) — a "
                "constant scheme makes the whitelist a no-op while every other "
                "criterion stays green" % (arg[:60], ", ".join(roots) or "?"))
    if not arg_problems and not live_positions(body, GUARD_CALL_RE):
        arg_problems.append("no reachable isSupportedScheme call to inspect "
                            "— fail-closed")
    out.append((label8b, not arg_problems,
                "; ".join(arg_problems) if arg_problems
                else "guard judges the caller's Uri"))
    return out


# ------------------------------------------------------- TerminalScreen (U5) ---
RE_LAUNCHED_PENDING = re.compile(r"LaunchedEffect\(\s*pendingUrl\s*\)\s*\{")
RE_WEB_SCHEME_IF = re.compile(r"if\s*\(\s*MinisOpenUrlBroker\s*\.\s*isWebScheme\s*\(")
RE_WEB_SCHEME_CALL = re.compile(r"isWebScheme\s*\(")
CONSUME_CALL = re.compile(r"MinisOpenUrlBroker\s*\.\s*consume\s*\(\s*\)")
_ASSIGN_STMT = re.compile(r"^[A-Za-z_][\w.]*(?:\s*\[[^\]]*\])?\s*=(?!=)")
_CTRL_STMT = re.compile(r"^(?:if|when|for|while|do|return|throw|try)\b")
_UNMODELLED_LITERAL = re.compile(r'(?s)"""(.*?)"""|"((?:[^"\\]|\\.)*)"'
                                 r"|'((?:[^'\\]|\\.)*)'")


def _modelled_statement(stmt):
    """锚点区间里的语句是否被本门建模（fail-closed：不建模的一律不认）。"""
    s = stmt.strip()
    if not s:
        return True
    if re.match(r"^(?:val|var)\b", s):
        return False  # 局部声明：锚点区间是契约（展示 + claim），不是计算场
    if _ASSIGN_STMT.match(s):
        return True
    if CONSUME_CALL.match(s):
        return True
    if _CTRL_STMT.match(s):
        return True
    if re.match(r"^Log\s*\.", s):
        return True
    return False


def _hostile_literals(lit_text):
    """字面量内容里带定界符（`{ } " '`）—— 花括号账目穿过字符串的形态。"""
    out = []
    for m in _UNMODELLED_LITERAL.finditer(lit_text):
        content = next((g for g in m.groups() if g is not None), "")
        if any(ch in content for ch in '{}"\''):
            out.append(content[:24])
    return out


def analyse_terminal(code_raw):
    """U5 — the fullscreen terminal may only consume URLs it actually presents."""
    try:
        code, lit = views(code_raw)
    except KotlinLexError as exc:
        return [("U5a LaunchedEffect(pendingUrl) block present", False,
                 "lexer failure: %s — fail-closed" % exc)]

    m = RE_LAUNCHED_PENDING.search(code)
    if not m:
        return [("U5a LaunchedEffect(pendingUrl) block present", False,
                 "anchor block missing — fail-closed")]
    open_idx = m.end() - 1
    end_idx = match_bracket(code, open_idx)
    if end_idx < 0:
        return [("U5a LaunchedEffect(pendingUrl) block present", False,
                 "unbalanced block braces — fail-closed")]
    block = code[open_idx:end_idx + 1]
    blit = lit[open_idx:end_idx + 1]
    results = [("U5a LaunchedEffect(pendingUrl) block present", True, "block found")]

    mi = RE_WEB_SCHEME_IF.search(block)
    if not mi:
        results.append(("U5b isWebScheme branch present and screens the pending Uri",
                        False,
                        "no `if (MinisOpenUrlBroker.isWebScheme(` anchor — fail-closed"))
        return results
    mi_if = RE_IF.search(block, mi.start())
    if not mi_if:
        results.append(("U5b isWebScheme branch present and screens the pending Uri",
                        False, "gate anchor without an `if (` — fail-closed"))
        return results
    cond_open = mi_if.end() - 1
    cond_close = match_bracket(block, cond_open)
    if cond_close < 0:
        results.append(("U5b isWebScheme branch present and screens the pending Uri",
                        False, "unbalanced condition parens — fail-closed"))
        return results
    cond_code = block[mi_if.end():cond_close]
    cond_fold = const_value(cond_code)
    problems = []
    if cond_fold == ("bool", True):
        problems.append("the branch condition is CONSTANTLY TRUE — the branch no "
                        "longer screens schemes, so non-web deep links are consumed")
    elif cond_fold == ("bool", False):
        problems.append("the branch condition is CONSTANTLY FALSE — dead gate")
    cm = RE_WEB_SCHEME_CALL.search(cond_code)
    if not cm:
        problems.append("the condition holds no isWebScheme(…) call — fail-closed")
    else:
        arg = call_arg(cond_code, cm.end())
        locals_ = set(re.findall(r"(?:val|var)\s+([A-Za-z_]\w*)\s*=", block))
        roots = sorted(locals_ | {"pendingUrl"})
        if not derives_from(cond_code, arg, roots):
            problems.append("the branch tests %r, not the pending Uri (%s) — a "
                            "constant scheme is constantly true"
                            % (arg[:60], ", ".join(roots)))
    results.append(("U5b isWebScheme branch present and screens the pending Uri",
                    not problems, "; ".join(problems) if problems
                    else "branch condition screens the pending Uri"))

    if cond_close + 1 <= len(block):
        k = cond_close + 1
        while k < len(block) and block[k] in " \t\r\n":
            k += 1
        body_end = match_bracket(block, k) if (k < len(block) and block[k] == "{") else -1
    else:
        k, body_end = -1, -1
    sites = [mm.start() for mm in CONSUME_CALL.finditer(block)]
    if body_end < 0:
        results.append(("U5c consume() only inside the isWebScheme branch", False,
                        "gate body not parsable — fail-closed"))
        return results
    outside = [j for j in sites if not (k < j < body_end)]
    results.append(("U5c consume() only inside the isWebScheme branch",
                    bool(sites) and not outside,
                    "no consume() call in the LaunchedEffect(pendingUrl) block"
                    if not sites else
                    "consume() sites=%d outside-gate=%d" % (len(sites), len(outside))))

    inner_code = block[k + 1:body_end]
    inner_lit = blit[k + 1:body_end]
    stmts = [inner_code[a:b] for a, b in stmt_spans(inner_code)]
    unmodelled = [s.strip()[:48] for s in stmts if not _modelled_statement(s)]
    hostile = _hostile_literals(inner_lit)
    detail = []
    if unmodelled:
        detail.append("unmodelled statement(s) in the anchor region: %s"
                      % unmodelled[:3])
    if hostile:
        detail.append("literal content carries delimiters (%s) — the region's brace "
                      "accounting would depend on lexer luck" % hostile[:3])
    results.append(("U5d the consume anchor region stays modelled and unambiguous",
                    not detail, "; ".join(detail) if detail
                    else "region modelled"))
    return results


# ------------------------------------------- terminal deep-link entries (U6/U7) ---
REL_ACTIVITY = "src/android/app/src/main/java/com/rikkaminis/app/MainActivity.kt"
REL_NAV = "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"

# Every terminal route goes through `Routes.terminal(`, whatever the caller.
RE_TERMINAL_CALL = "Routes.terminal("
# The deep-link dispatch arm (both files carry exactly one).
RE_OPEN_TERMINAL_BRANCH = re.compile(r"is\s+DeepLinkAction\.OpenTerminal\s*->\s*\{")
# A *resolved* session id: the public alias hop, or the store's already-resolved
# "chat currently on screen" seam. Deliberately not a bare identifier — that is
# exactly the defect (draft keys reaching the terminal as-is).
RE_RESOLVED_EXPR = re.compile(
    r"ChatViewModelStore\s*\.\s*resolveSessionId\s*\(|ChatViewModelStore\s*\.\s*activeSessionId"
)
RE_VAL_BIND = re.compile(r"(?m)^[ \t]*(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)")


def resolved_local_names(branch_code):
    """Local vals whose VALUE (not whose text) came out of the alias table.

    `value_sources` peels `.also { … }` (value discarded) and folds
    `?.let { … }` to the lambda's tail, so `<x>.also { resolve(it) }` no longer
    counts as resolved while `x?.let { resolve(it) }` still does.
    """
    names = set()
    for m in RE_VAL_BIND.finditer(branch_code):
        end = expr_end(branch_code, m.end())
        rhs = branch_code[m.end():end]
        if any(RE_RESOLVED_EXPR.search(src) for src in value_sources(rhs)):
            names.add(m.group(1))
    return names


def analyse_terminal_deeplink(code_raw, label):
    """U6/U7 — a terminal deep-link arm must pass a RESOLVED session id."""
    try:
        code, lit = views(code_raw)
    except KotlinLexError as exc:
        return [("%sa OpenTerminal branch present" % label, False,
                 "lexer failure: %s — fail-closed" % exc)]
    matches = list(RE_OPEN_TERMINAL_BRANCH.finditer(code))
    if len(matches) > 1:
        return [("%sa OpenTerminal branch present" % label, False,
                 "more than one `is DeepLinkAction.OpenTerminal ->` arm — ambiguous, fail-closed")]
    if not matches:
        return [("%sa OpenTerminal branch present" % label, False,
                 "`is DeepLinkAction.OpenTerminal ->` not found — fail-closed")]
    m = matches[0]
    end = match_bracket(code, m.end() - 1)
    if end < 0:
        return [("%sa OpenTerminal branch present" % label, False,
                 "unbalanced branch braces — fail-closed")]
    branch = code[m.end() - 1:end + 1]
    blit = lit[m.end() - 1:end + 1]
    results = [("%sa OpenTerminal branch present" % label, True, "branch found")]

    idx = branch.find(RE_TERMINAL_CALL)
    if idx < 0:
        results.append(("%sb branch navigates to the terminal route" % label, False,
                        "no `Routes.terminal(` call inside the branch"))
        return results
    open_idx = idx + len(RE_TERMINAL_CALL) - 1
    close_idx = match_bracket(branch, open_idx)
    if close_idx < 0:
        results.append(("%sb branch navigates to the terminal route" % label, False,
                        "unbalanced `Routes.terminal(` call — fail-closed"))
        return results
    args = branch[open_idx + 1:close_idx]
    results.append(("%sb branch navigates to the terminal route" % label, True, "call found"))

    names = resolved_local_names(branch)
    hit = None
    for a, b in split_top(args, (",",)):
        piece = args[a:b]
        nm = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", piece)
        value = piece[nm.end():] if nm else piece
        if any(RE_RESOLVED_EXPR.search(src) for src in value_sources(value)):
            hit = "resolve call"
            break
        if any(contains_ident(value, n) for n in names):
            hit = "local %s" % sorted(n for n in names if contains_ident(value, n))[0]
            break
    results.append((
        "%sc terminal sessionId comes from a resolved source" % label, bool(hit),
        ("`Routes.terminal(...)` args carry neither a ChatViewModelStore resolve call "
         "(alias hop / activeSessionId) whose VALUE survives, nor a local val bound "
         "from one — a bare, absent or discarded id binds the terminal to the draft "
         "key / global workspace. args=%r" % args.strip()[:160])))
    return results
# ── [r3-t21] deep-link ARM + intent-launch EFFECT judges ──────────────────
# Round-3 witnesses attack the seam between two gates: the deep-link gate pins
# its own spelling anchor (branch name / collectAsState / runCatching) and this
# gate pins the terminal arm, so a patch that keeps every spelling and drops
# the EFFECT passes both. These judges read the arm/launch as control flow:
# the preview must reach the consumer, the navigation must be reachable, and a
# plain-URL launcher must not fall back to an unsanitised `Intent.parseUri`.

REL_OFFLOAD = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt"

_ARM_LABEL_TOKEN = re.compile(
    r"\s*(?:is\s+[A-Za-z_][\w.]*|\"[^\"\\]*\"|null|else)\s*,?\s*$")
_NAV_CALL = re.compile(r"(?<![\w])(?:safeNavigate|navigate)\s*\(")
_RE_PARSE_URI = re.compile(r"(?<![\w])Intent\s*\.\s*parseUri\s*\(")


def _arm_labels(text):
    labels = set(re.findall(r"\bis\s+([A-Za-z_][\w.]*)", text))
    if re.search(r"(?<![\w])null(?![\w])", text):
        labels.add("null")
    if re.search(r"(?<![\w])else(?![\w])", text):
        labels.add("else")
    return frozenset(labels)


def _arm_label_start(text, floor, arrow):
    """Start of the match-label run ending at `arrow` (labels may wrap lines)."""
    i = arrow
    while True:
        head = text.rfind("\n", floor, i) + 1
        if _ARM_LABEL_TOKEN.fullmatch(text[head:i]):
            i = head
            while i > floor and text[i - 1] in " \t":
                i -= 1
            continue
        break
    return max(i, floor)


def _arm_bodies(block):
    """[(labels, body_text, braced)] for the depth-0 arms of `when (…) {…}`."""
    brace = block.find("{")
    close = match_bracket(block, brace) if brace >= 0 else -1
    if brace < 0 or close < 0:
        return []
    body_all = block[brace + 1:close]
    arrows = []
    depth = 0
    i = 0
    while i < len(body_all):
        ch = body_all[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "-" and depth == 0 and body_all.startswith("->", i):
            arrows.append(i)
            i += 2
            continue
        i += 1
    arms = []
    prev_end = 0
    for k, arrow in enumerate(arrows):
        labels = _arm_labels(body_all[prev_end:arrow])
        nxt = (_arm_label_start(body_all, arrow + 2, arrows[k + 1])
               if k + 1 < len(arrows) else len(body_all))
        start = arrow + 2
        while start < nxt and body_all[start] in " \t\r\n":
            start += 1
        if start < nxt and body_all[start] == "{":
            bclose = match_bracket(body_all, start)
            if bclose > 0:
                arms.append((labels, body_all[start:bclose + 1], True))
                prev_end = bclose + 1
                continue
        arms.append((labels, body_all[arrow + 2:nxt], False))
        prev_end = nxt
    return arms


def _dispatch_arms(code, subject):
    """Arms of the deep-link dispatch `when (<subject>)`, or None (fail-closed).

    Selected by the dispatch-only OpenTerminal case; a neighbouring
    `when (<subject>)` resolver stays out of scope.
    """
    hits = []
    for m in re.finditer(re.escape("when (%s)" % subject) + r"\s*\{", code):
        brace = m.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            continue
        block = code[brace - len("when (%s)" % subject):close + 1]
        if "DeepLinkAction.OpenTerminal" in block:
            hits.append(block)
    return _arm_bodies(hits[0]) if len(hits) == 1 else None


def _arm(arms, case):
    for labels, body, braced in arms:
        if case in labels:
            return body, braced
    return None, None


def analyse_preview_arm(code_raw, subject):
    """[r3-t21] U9 — the hot-start HTML-preview arm must PUBLISH the preview."""
    title = "U9 hot-start OpenHtmlPreview arm publishes the pending preview"
    try:
        code, _lit = views(code_raw)
    except KotlinLexError as exc:
        return [(title, False, "lexer failure: %s — fail-closed" % exc)]
    arms = _dispatch_arms(code, subject)
    if arms is None:
        return [(title, False, "deep-link dispatch `when (%s)` missing or ambiguous — "
                               "fail-closed" % subject)]
    body, braced = _arm(arms, "DeepLinkAction.OpenHtmlPreview")
    if body is None or not braced:
        return [(title, False, "`is DeepLinkAction.OpenHtmlPreview ->` arm missing or not "
                               "a brace block — fail-closed")]
    live = live_positions(body, r"(?<![\w])setPendingHtmlPreview\s*\(")
    out = [(title, bool(live),
            "the arm opens the chat but never calls setPendingHtmlPreview(…) on a reachable "
            "path — the deep link consumes the preview request and shows nothing")]
    if not live:
        return out
    args = " ".join(" ".join(body[a:b] for a, b in call_args(body, body.index("(", p)))
                    for p in live)
    missing = [f for f in ("sessionId", "resourcePath", "title")
               if not re.search(r"\.\s*%s\b" % f, args)]
    sourced = bool(re.search(r"(?<![\w])%s\s*\." % re.escape(subject), args))
    out.append(("U9b published preview carries the action's own fields",
                not missing and sourced,
                "setPendingHtmlPreview(…) is fed from %s — the preview opens on a wrong "
                "session/URL/title" % ("a partial payload (missing %s)" % ", ".join(missing)
                                       if missing else "something other than the action")))
    return out


def analyse_nav_arms(code_raw, subject, cases, label):
    """[r3-t21] U10 — an arm whose whole purpose is to open something must open it."""
    title = "%s deep-link arms reach their navigation" % label
    try:
        code, _lit = views(code_raw)
    except KotlinLexError as exc:
        return [(title, False, "lexer failure: %s — fail-closed" % exc)]
    arms = _dispatch_arms(code, subject)
    if arms is None:
        return [(title, False, "deep-link dispatch `when (%s)` missing or ambiguous — "
                               "fail-closed" % subject)]
    problems = []
    for case in cases:
        body, braced = _arm(arms, case)
        if body is None or not braced:
            continue                      # arm presence is U6/U7's job
        sites = [m.start() for m in _NAV_CALL.finditer(body)]
        if not sites:
            problems.append("`%s` carries no navigation call" % case)
        elif not [p for p in sites if path_feasible(body, p)]:
            problems.append("every navigation call in `%s` sits behind a dead guard "
                            "(constant `if (…)`, `if (false)`) — the arm runs and nothing "
                            "opens" % case)
    return [(title, not problems, "; ".join(problems))]


def _enclosing_branch_span(code, pos):
    """Innermost `if` BRANCH (then-arm or else-arm, not the whole if) with `pos`.

    Judging the whole `if` would let a sanitize call in the OTHER arm vouch for
    the arm under inspection — exactly the symmetry hole U11 exists for.
    """
    best = None
    for _cond, t_start, t_end, e_start, e_end in if_blocks(code):
        for lo, hi in ((t_start, t_end), (e_start, e_end)):
            if lo is None or hi is None:
                continue
            if lo <= pos <= hi and (best is None or (hi - lo) < (best[1] - best[0])):
                best = (lo, hi)
    return best


def analyse_intent_symmetry(code_raw):
    """[r3-t21] U11 — a plain-URL launcher must not parse intent URIs."""
    title = ("U11 every Intent.parseUri(…) is scheme-normalised, flag-guarded and "
             "sanitised")
    try:
        code, _lit = views(code_raw)
    except KotlinLexError as exc:
        return [(title, False, "lexer failure: %s — fail-closed" % exc)]
    sites = [m.start() for m in _RE_PARSE_URI.finditer(code)]
    if not sites:
        return [(title, False, "no Intent.parseUri(…) site at all — fail-closed (the "
                               "`#Intent;` handler this criterion pins is gone)")]
    problems = []
    for pos in sites:
        spans = call_args(code, code.index("(", pos))
        parts = [code[a:b].strip() for a, b in spans]
        target = parts[0] if parts else ""
        flag = strip_parens(parts[1]) if len(parts) > 1 else ""
        if not flag:
            problems.append("Intent.parseUri(%s) without the URI_INTENT_SCHEME flag" % target)
        else:
            fv = const_value(flag)
            if fv is not None and fv != 1:
                problems.append("Intent.parseUri(%s, %s) — a non-URI_INTENT_SCHEME flag "
                                "makes the parser honour `#Intent;` fragments on ANY scheme"
                                % (target, flag))
            elif fv is None and "URI_INTENT_SCHEME" not in flag:
                problems.append("Intent.parseUri(%s, %s) — the flag is not pinned to "
                                "Intent.URI_INTENT_SCHEME" % (target, flag))
        if not any("lowercaseIntentScheme" in s for s in value_sources(target)):
            problems.append("Intent.parseUri(%s) — the argument is not scheme-normalised "
                            "through lowercaseIntentScheme(…)" % target)
        span = _enclosing_branch_span(code, pos)
        seg = code[span[0]:span[1]] if span is not None else ""
        if span is None:
            problems.append("Intent.parseUri(…) sits outside any `if` arm — fail-closed")
        elif not live_positions(seg, r"(?<![\w])IntentSanitizer\s*\.\s*sanitize\s*\("):
            problems.append("Intent.parseUri(…) is not followed by IntentSanitizer.sanitize(…) "
                            "in the same arm — grant flags/clipData/component survive")
    out = [(title, not problems, "; ".join(problems))]
    ifm = [m for m in if_blocks(code) if "isIntentUri" in m[0]]
    sym_ok, sym_detail = False, ""
    if not ifm:
        sym_detail = "no `if (isIntentUri)` dispatch — fail-closed"
    else:
        _cond, t_start, t_end, e_start, e_end = ifm[0]
        then = code[t_start:t_end]
        els = code[e_start:e_end] if e_start is not None and e_end is not None else ""
        if not _RE_PARSE_URI.search(then):
            sym_detail = "the intent-uri arm no longer parses `#Intent;` URIs"
        elif _RE_PARSE_URI.search(els) or "ACTION_VIEW" not in els:
            sym_detail = ("the plain-URL arm left ACTION_VIEW and parses intent URIs too, "
                          "bypassing the sanitised arm")
        else:
            sym_ok = True
    out.append(("U11b the plain-URL arm stays on ACTION_VIEW", sym_ok, sym_detail))
    return out

GOOD = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (!isSupportedScheme(uri.scheme)) return
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

BAD_NO_GATE = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

BAD_ORDER = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        _pendingUrl.value = uri
        if (!isSupportedScheme(uri.scheme)) return
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

BAD_SECOND_GATE = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (!isSupportedScheme(uri.scheme)) return
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        // drift: a second inline gate + a second publish point
        if (!isSupportedScheme(parsed.scheme)) return
        _pendingUrl.value = parsed
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""


# ---- meta-audit-1024 FIX-M1 witness arms (U8: guard must terminate) ----------
# The exact external witness: `return` -> `Unit` keeps U1 (substring), U2b (one
# publish point), U3 (gate before publish) and U4 all green, yet the guard is a
# no-op and every scheme lands in _pendingUrl.
BAD_GUARD_UNIT = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (!isSupportedScheme(uri.scheme)) Unit
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

BAD_GUARD_EMPTY_BLOCK = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (!isSupportedScheme(uri.scheme)) { }
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

# Inverted guard: the rejected branch does the publishing.
BAD_GUARD_INVERTED = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (!isSupportedScheme(uri.scheme)) { _pendingUrl.value = uri }
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

# Precision: a multi-statement guard arm that still ends the function is fine.
GOOD_GUARD_BLOCK_MULTI = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (!isSupportedScheme(uri.scheme)) {
            Log.w(TAG, "unsupported scheme: %s", uri.scheme)
            return
        }
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

GOOD_GUARD_THROW = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (!isSupportedScheme(uri.scheme)) throw IllegalArgumentException("scheme")
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

# Precision: the accepted positive-wrapping shape (same decision point, other
# legal spelling).
GOOD_GUARD_POSITIVE_WRAP = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (isSupportedScheme(uri.scheme)) {
            _pendingUrl.value = uri
        }
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""

# Precision: `….not()` / `== false` negations are also real guards.
GOOD_GUARD_NOT_CALL = """package probe
class B {
    private val _pendingUrl = MutableStateFlow<Uri?>(null)
    fun offer(uri: Uri) {
        if (isSupportedScheme(uri.scheme).not()) return
        _pendingUrl.value = uri
    }
    fun offer(uriString: String) {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        offer(parsed)
    }
    fun isSupportedScheme(scheme: String?): Boolean {
        val s = scheme?.lowercase() ?: return false
        return s == "http" || s == "https" || s == "about" || s == "minis"
    }
}
"""


GOOD_TERMINAL = """package probe
@Composable
fun TerminalScreen(pendingUrl: Uri?) {
    var previewUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
            MinisOpenUrlBroker.consume()
        }
    }
}
"""

BAD_TERMINAL_OUTSIDE = """package probe
@Composable
fun TerminalScreen(pendingUrl: Uri?) {
    var previewUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
        MinisOpenUrlBroker.consume()
    }
}
"""

BAD_TERMINAL_NO_CONSUME = """package probe
@Composable
fun TerminalScreen(pendingUrl: Uri?) {
    var previewUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
    }
}
"""

BAD_TERMINAL_NO_ANCHOR = """package probe
@Composable
fun TerminalScreen(pendingUrl: Uri?) {
    val pendingUrl2 = pendingUrl
}
"""

GOOD_TERMINAL_COMMENT_ONLY = """package probe
// LaunchedEffect(pendingUrl) {
//     if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
//         MinisOpenUrlBroker.consume()
//     }
// }
@Composable
fun TerminalScreen(pendingUrl: Uri?) {
    val pendingUrl2 = pendingUrl
}
"""

GOOD_ACTIVITY_TERMINAL = """package probe
class MainActivity {
    private val currentChatSessionId: String? = null
    fun handleDeepLink(action: DeepLinkAction) {
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                val terminalSessionId = currentChatSessionId
                    ?.let { ChatViewModelStore.resolveSessionId(it) }
                nav.navigate(Routes.terminal(action.initCommand, sessionId = terminalSessionId))
            }
            is DeepLinkAction.OpenSession -> {
                nav.navigate(Routes.chat(action.sessionId))
            }
        }
    }
}
"""

# Precision arm: same decision point, a different spelling (positional argument
# + local val), plus an unrelated terminal navigation elsewhere in the file.
GOOD_ACTIVITY_TERMINAL_OTHER_FORM = """package probe
class MainActivity {
    private val currentChatSessionId: String? = null
    fun handleDeepLink(action: DeepLinkAction) {
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                val id = currentChatSessionId?.let { ChatViewModelStore.resolveSessionId(it) }
                if (id != null) nav.navigate(Routes.terminal(action.initCommand, id))
            }
            else -> {}
        }
    }
    fun unrelated() {
        nav.navigate(Routes.terminal(cmd))
    }
}
"""

BAD_ACTIVITY_TERMINAL_BARE = """package probe
class MainActivity {
    fun handleDeepLink(action: DeepLinkAction) {
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                nav.navigate(Routes.terminal(action.initCommand))
            }
        }
    }
}
"""

BAD_ACTIVITY_TERMINAL_UNUSED_RESOLVE = """package probe
class MainActivity {
    fun handleDeepLink(action: DeepLinkAction) {
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                val unused = ChatViewModelStore.resolveSessionId(action.sessionId)
                nav.navigate(Routes.terminal(action.initCommand, sessionId = action.sessionId))
            }
        }
    }
}
"""

BAD_ACTIVITY_TERMINAL_AMBIGUOUS = """package probe
class MainActivity {
    fun handleDeepLink(action: DeepLinkAction) {
        when (action) {
            is DeepLinkAction.OpenTerminal -> {
                val id = ChatViewModelStore.resolveSessionId(action.sessionId)
                nav.navigate(Routes.terminal(action.initCommand, sessionId = id))
            }
            is DeepLinkAction.OpenTerminal -> {
                nav.navigate(Routes.terminal(action.initCommand))
            }
        }
    }
}
"""

BAD_NAV_TERMINAL_MISSING_CALL = """package probe
fun AppNavigation(initialDeepLink: DeepLinkAction) {
    when (initialDeepLink) {
        is DeepLinkAction.OpenTerminal -> {
            navController.safeNavigate()
        }
    }
}
"""

GOOD_NAV_TERMINAL = """package probe
fun AppNavigation(initialDeepLink: DeepLinkAction) {
    when (initialDeepLink) {
        is DeepLinkAction.OpenTerminal -> {
            navController.safeNavigate(
                Routes.terminal(
                    initialDeepLink.initCommand,
                    sessionId = ChatViewModelStore.activeSessionId,
                ),
            )
        }
        is DeepLinkAction.OpenSession -> {
            navController.safeNavigate(Routes.chat(initialDeepLink.sessionId))
        }
    }
}
"""


# ---- meta-audit 1024-B 02-1..02-7 witness arms -------------------------------
# Each fixture keeps every token the OLD criteria looked for. Only the EFFECT
# moves: a constant argument, a dead delegation, a widened whitelist, a
# constant-true branch, a discarded resolve, a delimiter-carrying literal.
BAD_GUARD_CONST_ARG = GOOD.replace(
    "        if (!isSupportedScheme(uri.scheme)) return",
    '        if (!isSupportedScheme("http")) return')

BAD_WHITELIST_WIDENED = GOOD.replace(
    '        return s == "http" || s == "https" || s == "about" || s == "minis"',
    '        return s == "http" || s == "https" || s == "about" || s == "minis"'
    ' || s == "javascript" || s == "file"')

# Precision: a one-hop local scheme must stay green (the check is about the
# VALUE's origin, not about spelling the parameter at the call site).
GOOD_GUARD_LOCAL_SCHEME = GOOD.replace(
    "        if (!isSupportedScheme(uri.scheme)) return",
    "        val scheme = uri.scheme\n"
    "        if (!isSupportedScheme(scheme)) return")

# The delegation is parked in a dead branch and the publish moved to a second
# spelling (`compareAndSet`) — U2a + U2b must both speak.
BAD_DEAD_DELEGATE = GOOD.replace(
    "        offer(parsed)\n",
    "        if (false) offer(parsed)\n"
    "        _pendingUrl.compareAndSet(_pendingUrl.value, parsed)\n")

BAD_TERMINAL_CONST_COND = GOOD_TERMINAL.replace(
    "MinisOpenUrlBroker.isWebScheme(uri.scheme)",
    'MinisOpenUrlBroker.isWebScheme("http") || true')

# A raw string whose content carries a brace: every predecessor lexer shifted
# its brace accounting here (parser-death). U5d refuses to certify the region.
BAD_TERMINAL_RAW_STRING = GOOD_TERMINAL.replace(
    "            previewUrl = uri.toString()\n",
    '            val raw = """\\"}"""\n            previewUrl = uri.toString()\n')

# Precision: a log line inside the anchor region is allowed.
GOOD_TERMINAL_LOG = GOOD_TERMINAL.replace(
    "            previewUrl = uri.toString()\n",
    '            Log.d(TAG, "terminal presenting a web uri")\n'
    "            previewUrl = uri.toString()\n")

# `.also { }` discards the receiver: the resolve call runs, its value is thrown
# away, and the draft id is what actually gets bound.
BAD_ACTIVITY_ALSO = GOOD_ACTIVITY_TERMINAL.replace(
    "                val terminalSessionId = currentChatSessionId\n"
    "                    ?.let { ChatViewModelStore.resolveSessionId(it) }",
    "                val terminalSessionId = currentChatSessionId\n"
    '                    .also { ChatViewModelStore.resolveSessionId(it ?: "") }')

BAD_NAV_LET_NULL = GOOD_NAV_TERMINAL.replace(
    "                    sessionId = ChatViewModelStore.activeSessionId,",
    "                    sessionId = ChatViewModelStore.activeSessionId.let { null },")


# ---- [r3-t21] fixtures: arm EFFECT / intent-symmetry half ------------------

GOOD_PREVIEW_NAV = """package probe

fun handleDeepLink(uri: Uri?) {
    val action = DeepLinkHandler.parse(uri) ?: return
    when (action) {
        is DeepLinkAction.OpenTerminal -> {
            nav.navigate(Routes.terminal(action.initCommand))
        }
        is DeepLinkAction.OpenSession -> {
            nav.navigate(Routes.chat(action.sessionId))
        }
        is DeepLinkAction.OpenHtmlPreview -> {
            DeepLinkCoordinator.setPendingHtmlPreview(
                action.sessionId,
                action.resourcePath,
                action.title,
            )
            nav.navigate(Routes.chat(action.sessionId))
        }
        is DeepLinkAction.CreateEnvironmentVariable -> {
            DeepLinkCoordinator.setPendingEnvVarCreate(action.key, action.value, action.note)
            nav.navigate(Routes.ENV_VARS)
        }
    }
}
"""

BAD_PREVIEW_DROPPED = GOOD_PREVIEW_NAV.replace(
    "            DeepLinkCoordinator.setPendingHtmlPreview(\n"
    "                action.sessionId,\n"
    "                action.resourcePath,\n"
    "                action.title,\n"
    "            )\n", "")

BAD_PREVIEW_PARTIAL = GOOD_PREVIEW_NAV.replace(
    "                action.resourcePath,\n",
    '                "",\n', 1)

BAD_PREVIEW_DEAD = GOOD_PREVIEW_NAV.replace(
    "            DeepLinkCoordinator.setPendingHtmlPreview(",
    "            if (false) DeepLinkCoordinator.setPendingHtmlPreview(")

BAD_NAV_DEAD_NAV = GOOD_PREVIEW_NAV.replace(
    "            nav.navigate(Routes.chat(action.sessionId))\n"
    "        }\n"
    "        is DeepLinkAction.OpenHtmlPreview -> {",
    "            if (false) nav.navigate(Routes.chat(action.sessionId))\n"
    "        }\n"
    "        is DeepLinkAction.OpenHtmlPreview -> {")

GOOD_OFFLOAD = """package probe

import android.content.Intent
import android.net.Uri
import com.rikkaminis.app.util.IntentSanitizer
import com.rikkaminis.app.util.lowercaseIntentScheme

private fun tryOpen(url: String): Intent {
    val isIntentUri = url.startsWith("intent:", ignoreCase = true) ||
        url.startsWith("android-app:", ignoreCase = true)
    val intent: Intent = if (isIntentUri) {
        Intent.parseUri(lowercaseIntentScheme(url), Intent.URI_INTENT_SCHEME).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            IntentSanitizer.sanitize(this, context)
        }
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return intent
}
"""

BAD_OFFLOAD_FLAG0 = GOOD_OFFLOAD.replace(
    "Intent.parseUri(lowercaseIntentScheme(url), Intent.URI_INTENT_SCHEME)",
    "Intent.parseUri(lowercaseIntentScheme(url), 0)")

BAD_OFFLOAD_NOSAN = GOOD_OFFLOAD.replace(
    "            IntentSanitizer.sanitize(this, context)\n", "")

BAD_OFFLOAD_RAW = GOOD_OFFLOAD.replace(
    "Intent.parseUri(lowercaseIntentScheme(url), Intent.URI_INTENT_SCHEME)",
    "Intent.parseUri(url, Intent.URI_INTENT_SCHEME)")

BAD_OFFLOAD_ELSE_PARSEURI = GOOD_OFFLOAD.replace(
    "    } else {\n"
    "        Intent(Intent.ACTION_VIEW, Uri.parse(url))\n"
    "            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n",
    "    } else {\n"
    "        Intent.parseUri(url, 0)\n"
    "            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n")

# precision: statement form (no .apply) is the same effect
GOOD_OFFLOAD_STATEMENT = GOOD_OFFLOAD.replace(
    "        Intent.parseUri(lowercaseIntentScheme(url), Intent.URI_INTENT_SCHEME).apply {\n"
    "            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "            IntentSanitizer.sanitize(this, context)\n"
    "        }\n",
    "        val parsed = Intent.parseUri"
    "(lowercaseIntentScheme(url), Intent.URI_INTENT_SCHEME)\n"
    "        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n"
    "        IntentSanitizer.sanitize(parsed, context)\n"
    "        parsed\n")


def selftest():
    failures = []
    arms = [0]

    def expect(cond, what):
        arms[0] += 1
        if not cond:
            failures.append(what)

    def as_dict(res):
        return dict((c, ok) for c, ok, _ in res)

    U1 = "U1 offer(uri: Uri) consults isSupportedScheme on a reachable path"
    U2A = "U2a offer(String) funnels through the same gate"
    U2B = "U2b exactly one non-null _pendingUrl publish point"
    U3 = "U3 scheme check precedes the _pendingUrl publish"
    U4 = "U4 whitelist is exactly http/https/about/minis (not weakened, not widened)"
    U8 = "U8 isSupportedScheme guard terminates control flow (no Unit/no-op arm)"
    U8B = "U8b the guard judges the caller's Uri (no constant argument)"
    ALL_A = (U1, U2A, U2B, U3, U4, U8, U8B)

    U5A = "U5a LaunchedEffect(pendingUrl) block present"
    U5B = "U5b isWebScheme branch present and screens the pending Uri"
    U5C = "U5c consume() only inside the isWebScheme branch"
    U5D = "U5d the consume anchor region stays modelled and unambiguous"
    ALL_T = (U5A, U5B, U5C, U5D)

    # good fixture: everything green
    good = as_dict(analyse(GOOD))
    expect(len(good) == len(ALL_A),
           "good fixture should produce %d criteria, got %d" % (len(ALL_A), len(good)))
    for c in ALL_A:
        expect(good.get(c), "good fixture must pass %s" % c)

    # bad1: gate removed from offer(Uri) (the original bug) — U1+U3 red, rest OK
    d = as_dict(analyse(BAD_NO_GATE))
    for c, ok in d.items():
        if c.startswith("U1") or c.startswith("U3"):
            expect(not ok, "BAD_NO_GATE %s must be RED" % c)
        else:
            expect(ok, "BAD_NO_GATE %s must stay OK" % c)

    # bad2: publish before the check (order mutation) — U3 red, U1/U2b stay OK
    d = as_dict(analyse(BAD_ORDER))
    expect(not d[U3], "BAD_ORDER U3 must be RED")
    expect(d[U1], "BAD_ORDER U1 must stay OK (gate still reachable)")
    expect(d[U2B], "BAD_ORDER U2b must stay OK")

    # bad3: second inline gate + second publish point — U2b red (drift catcher)
    d = as_dict(analyse(BAD_SECOND_GATE))
    expect(not d[U2B], "BAD_SECOND_GATE U2b must be RED (two publish points)")

    # fail-closed: missing file / garbage input must NOT read as green
    d = as_dict(analyse("class B { }"))
    expect(len(d) == 1 and not list(d.values())[0]
           and list(d)[0].startswith("U1"),
           "garbage input must fail-closed on U1")

    # ---- U8 (guard must TERMINATE control flow) ----
    d = as_dict(analyse(BAD_GUARD_UNIT))
    expect(not d[U8], "BAD_GUARD_UNIT U8 must be RED (return mutated to Unit: the "
                      "guard is a no-op, every scheme reaches _pendingUrl)")
    for c in ALL_A:
        if c == U8:
            continue
        expect(d[c], "BAD_GUARD_UNIT %s must stay OK (old criteria cannot see it)" % c)

    d = as_dict(analyse(BAD_GUARD_EMPTY_BLOCK))
    expect(not d[U8], "BAD_GUARD_EMPTY_BLOCK U8 must be RED (empty guard arm)")
    expect(d[U3], "BAD_GUARD_EMPTY_BLOCK U3 must stay OK (order is unchanged)")

    d = as_dict(analyse(BAD_GUARD_INVERTED))
    expect(not d[U8], "BAD_GUARD_INVERTED U8 must be RED (rejected arm publishes)")

    for name, fixture in (("GOOD_GUARD_BLOCK_MULTI", GOOD_GUARD_BLOCK_MULTI),
                          ("GOOD_GUARD_THROW", GOOD_GUARD_THROW),
                          ("GOOD_GUARD_POSITIVE_WRAP", GOOD_GUARD_POSITIVE_WRAP),
                          ("GOOD_GUARD_NOT_CALL", GOOD_GUARD_NOT_CALL),
                          ("GOOD_GUARD_LOCAL_SCHEME", GOOD_GUARD_LOCAL_SCHEME)):
        g = as_dict(analyse(fixture))
        expect(len(g) == len(ALL_A) and all(g.values()),
               "%s must be fully green: %s" % (name, [c for c, ok in g.items() if not ok]))

    # ---- U8b (guard must judge the caller's Uri, not a constant) ----
    d = as_dict(analyse(BAD_GUARD_CONST_ARG))
    expect(not d[U8B], "BAD_GUARD_CONST_ARG U8b must be RED (guard argument is the "
                       "constant \"http\": the whitelist is never consulted)")
    for c in ALL_A:
        if c == U8B:
            continue
        expect(d[c], "BAD_GUARD_CONST_ARG %s must stay OK (this is the blind shape)" % c)

    # ---- U4 completeness (whitelist widened, not weakened) ----
    d = as_dict(analyse(BAD_WHITELIST_WIDENED))
    expect(not d[U4], "BAD_WHITELIST_WIDENED U4 must be RED (javascript/file smuggled "
                      "into the accepted set)")
    for c in ALL_A:
        if c == U4:
            continue
        expect(d[c], "BAD_WHITELIST_WIDENED %s must stay OK" % c)

    # ---- U2a/U2b (dead delegation + alternate publish spelling) ----
    d = as_dict(analyse(BAD_DEAD_DELEGATE))
    expect(not d[U2A], "BAD_DEAD_DELEGATE U2a must be RED (delegation parked in a dead "
                       "if (false) branch)")
    expect(not d[U2B], "BAD_DEAD_DELEGATE U2b must be RED (compareAndSet is a publish "
                       "spelling the old counter ignored)")

    # ---- U5 (TerminalScreen consumer placement) ----
    tgood = as_dict(analyse_terminal(GOOD_TERMINAL))
    expect(len(tgood) == len(ALL_T),
           "U5 good fixture should produce %d criteria, got %d" % (len(ALL_T), len(tgood)))
    for c in ALL_T:
        expect(tgood.get(c), "U5 good fixture must pass %s" % c)

    d = as_dict(analyse_terminal(BAD_TERMINAL_OUTSIDE))
    expect(not d[U5C], "BAD_TERMINAL_OUTSIDE U5c must be RED (consume outside the gate)")
    expect(d[U5A], "BAD_TERMINAL_OUTSIDE U5a must stay OK")
    expect(d[U5B], "BAD_TERMINAL_OUTSIDE U5b must stay OK")

    d = as_dict(analyse_terminal(BAD_TERMINAL_NO_CONSUME))
    expect(not d[U5C], "BAD_TERMINAL_NO_CONSUME U5c must be RED (nothing consumes)")

    d = as_dict(analyse_terminal(BAD_TERMINAL_NO_ANCHOR))
    expect(len(d) == 1 and not list(d.values())[0] and list(d)[0].startswith("U5a"),
           "BAD_TERMINAL_NO_ANCHOR must fail-closed on U5a")

    # KDoc quoting the anchor must not satisfy the criterion (comments stripped)
    d = as_dict(analyse_terminal(GOOD_TERMINAL_COMMENT_ONLY))
    expect(len(d) == 1 and not list(d.values())[0] and list(d)[0].startswith("U5a"),
           "comment-only anchor must NOT read as green (comments masked)")

    # U5b: constant-true condition (anchor shape intact, screening gone)
    d = as_dict(analyse_terminal(BAD_TERMINAL_CONST_COND))
    expect(not d[U5B], "BAD_TERMINAL_CONST_COND U5b must be RED (constant-true branch: "
                       "non-web deep links get consumed)")
    expect(d[U5C], "BAD_TERMINAL_CONST_COND U5c must stay OK (placement unchanged)")

    # U5d: raw string with a brace inside the anchor region
    d = as_dict(analyse_terminal(BAD_TERMINAL_RAW_STRING))
    expect(not d[U5D], "BAD_TERMINAL_RAW_STRING U5d must be RED (literal content carries "
                       "a brace; the region is not modelled)")
    expect(d[U5C], "BAD_TERMINAL_RAW_STRING U5c must stay OK (consume placement intact)")

    d = as_dict(analyse_terminal(GOOD_TERMINAL_LOG))
    expect(len(d) == len(ALL_T) and all(d.values()),
           "GOOD_TERMINAL_LOG must be fully green (a log line is modelled): %s"
           % [c for c, ok in d.items() if not ok])

    # ---- U6/U7 (terminal deep-link arm carries a resolved session id) ----
    a = as_dict(analyse_terminal_deeplink(GOOD_ACTIVITY_TERMINAL, "U6"))
    expect(len(a) == 3, "U6 good fixture should produce 3 criteria, got %d" % len(a))
    for c, ok in a.items():
        expect(ok, "U6 good fixture must pass %s" % c)

    # precision: positional + local val + an unrelated terminal call elsewhere
    a = as_dict(analyse_terminal_deeplink(GOOD_ACTIVITY_TERMINAL_OTHER_FORM, "U6"))
    expect(len(a) == 3, "U6 precision fixture produced %d criteria" % len(a))
    for c, ok in a.items():
        expect(ok, "U6 precision fixture must pass %s (call form must not matter)" % c)

    # the pre-fix shape: no session id at all — U6c must be RED, U6a/b stay OK
    a = as_dict(analyse_terminal_deeplink(BAD_ACTIVITY_TERMINAL_BARE, "U6"))
    expect(not a["U6c terminal sessionId comes from a resolved source"],
           "BAD_ACTIVITY_TERMINAL_BARE U6c must be RED (no session id in the call)")
    expect(a["U6a OpenTerminal branch present"], "BAD_ACTIVITY_TERMINAL_BARE U6a must stay OK")
    expect(a["U6b branch navigates to the terminal route"],
           "BAD_ACTIVITY_TERMINAL_BARE U6b must stay OK")

    # a resolve call that is NOT the value passed must not read as green
    a = as_dict(analyse_terminal_deeplink(BAD_ACTIVITY_TERMINAL_UNUSED_RESOLVE, "U6"))
    expect(not a["U6c terminal sessionId comes from a resolved source"],
           "BAD_ACTIVITY_TERMINAL_UNUSED_RESOLVE U6c must be RED (resolve call is not the "
           "value handed to Routes.terminal)")

    # `.also { resolve(…) }` runs the resolve and throws the value away
    a = as_dict(analyse_terminal_deeplink(BAD_ACTIVITY_ALSO, "U6"))
    expect(not a["U6c terminal sessionId comes from a resolved source"],
           "BAD_ACTIVITY_ALSO U6c must be RED (.also discards the resolved value; the "
           "draft id is what gets bound)")

    # two OpenTerminal arms = ambiguous → fail-closed on U6a
    a = as_dict(analyse_terminal_deeplink(BAD_ACTIVITY_TERMINAL_AMBIGUOUS, "U6"))
    expect(len(a) == 1 and not a["U6a OpenTerminal branch present"],
           "BAD_ACTIVITY_TERMINAL_AMBIGUOUS must fail-closed on U6a")

    # no branch at all (garbage input) must not read as green
    a = as_dict(analyse_terminal_deeplink("class X { }", "U6"))
    expect(len(a) == 1 and not a["U6a OpenTerminal branch present"],
           "missing branch must fail-closed on U6a")

    a = as_dict(analyse_terminal_deeplink(BAD_NAV_TERMINAL_MISSING_CALL, "U7"))
    expect(not a["U7b branch navigates to the terminal route"],
           "BAD_NAV_TERMINAL_MISSING_CALL U7b must be RED (branch without the route call)")

    a = as_dict(analyse_terminal_deeplink(GOOD_NAV_TERMINAL, "U7"))
    expect(len(a) == 3, "U7 good fixture should produce 3 criteria, got %d" % len(a))
    for c, ok in a.items():
        expect(ok, "U7 good fixture must pass %s" % c)

    # `.let { null }` keeps the token, discards the value
    a = as_dict(analyse_terminal_deeplink(BAD_NAV_LET_NULL, "U7"))
    expect(not a["U7c terminal sessionId comes from a resolved source"],
           "BAD_NAV_LET_NULL U7c must be RED (activeSessionId.let { null } discards the "
           "resolved value)")


    # ---- [r3-t21] U9/U10/U11: arm EFFECT + intent-launch symmetry ----------
    def by_prefix(res, prefix):
        return [(k, ok) for k, ok, _d in res if k.startswith(prefix)]

    nav_cases = ("DeepLinkAction.OpenSession", "DeepLinkAction.OpenTerminal",
                 "DeepLinkAction.OpenHtmlPreview", "DeepLinkAction.CreateEnvironmentVariable")

    res = analyse_preview_arm(GOOD_PREVIEW_NAV, "action")
    expect(len(res) == 2 and all(ok for _k, ok, _d in res),
           "U9 good fixture must pass both criteria: %s" % res)

    res = analyse_preview_arm(BAD_PREVIEW_DROPPED, "action")
    expect(len(by_prefix(res, "U9 ")) == 1
           and not by_prefix(res, "U9 ")[0][1],
           "U9 must be RED when the arm opens the chat but never publishes the preview")

    res = analyse_preview_arm(BAD_PREVIEW_PARTIAL, "action")
    expect(len(by_prefix(res, "U9b")) == 1 and not by_prefix(res, "U9b")[0][1],
           "U9b must be RED when the published preview loses its URL/title")

    res = analyse_preview_arm(BAD_PREVIEW_DEAD, "action")
    expect(not by_prefix(res, "U9 ")[0][1],
           "U9 must be RED when the publish sits behind `if (false)`")

    res = analyse_preview_arm(BAD_OFFLOAD_ELSE_PARSEURI, "action")
    expect(not by_prefix(res, "U9 ")[0][1],
           "U9 must fail-closed when the dispatch arm cannot be found")

    res = analyse_nav_arms(GOOD_PREVIEW_NAV, "action", nav_cases, "U9/U10")
    expect(len(res) == 1 and res[0][1], "U10 good fixture must be green: %s" % res)

    res = analyse_nav_arms(BAD_NAV_DEAD_NAV, "action", nav_cases, "U9/U10")
    expect(len(res) == 1 and not res[0][1],
           "U10 must be RED when every navigation call in an arm is behind a dead guard")

    res = analyse_intent_symmetry(GOOD_OFFLOAD)
    expect(len(res) == 2 and all(ok for _k, ok, _d in res),
           "U11 good fixture must pass both criteria: %s" % res)

    res = analyse_intent_symmetry(GOOD_OFFLOAD_STATEMENT)
    expect(len(res) == 2 and all(ok for _k, ok, _d in res),
           "U11 must accept the statement form (no .apply) — the effect is the same")

    res = analyse_intent_symmetry(BAD_OFFLOAD_FLAG0)
    expect(not by_prefix(res, "U11 ")[0][1],
           "U11 must be RED when parseUri gets flag 0 (`#Intent;` on any scheme)")

    res = analyse_intent_symmetry(BAD_OFFLOAD_NOSAN)
    expect(not by_prefix(res, "U11 ")[0][1],
           "U11 must be RED when the parsed intent never reaches IntentSanitizer")

    res = analyse_intent_symmetry(BAD_OFFLOAD_RAW)
    expect(not by_prefix(res, "U11 ")[0][1],
           "U11 must be RED when the argument is not scheme-normalised")

    res = analyse_intent_symmetry(BAD_OFFLOAD_ELSE_PARSEURI)
    expect(not by_prefix(res, "U11 ")[0][1] and not by_prefix(res, "U11b")[0][1],
           "the pure-URL arm falling back to parseUri must trip BOTH U11 and U11b")

    res = analyse_intent_symmetry("class X {")
    expect(len(res) == 1 and not res[0][1],
           "U11 must fail-closed when there is no parseUri site at all")

    if failures:
        print("OPEN_URL_BROKER_SCHEME_SELFTEST=FAIL")
        for f in failures:
            print("  selftest: %s" % f)
        return 1
    print("OPEN_URL_BROKER_SCHEME_SELFTEST=PASS (%d arms)" % arms[0])
    return 0


def _root(argv):
    args = [a for a in argv if a != "--root"]
    return args[0] if args else "."


def main():
    argv = list(sys.argv[1:])
    if "--selftest" in argv:
        sys.exit(selftest())
    root = _root(argv)
    path = os.path.join(root, BROKER_REL)
    if not os.path.isfile(path):
        print("gate: broker file missing: %s" % path)
        sys.exit(1)
    with open(path, "r", encoding="utf-8") as f:
        code = f.read()
    results = analyse(code)
    tpath = os.path.join(root, TERMINAL_REL)
    if os.path.isfile(tpath):
        with open(tpath, "r", encoding="utf-8") as f:
            results = results + analyse_terminal(f.read())
    else:
        print("  [--] U5 skipped — %s not staged in this tree" % TERMINAL_REL)
    for label, rel in (("U6", REL_ACTIVITY), ("U7", REL_NAV)):
        dpath = os.path.join(root, rel)
        if os.path.isfile(dpath):
            with open(dpath, "r", encoding="utf-8") as f:
                results = results + analyse_terminal_deeplink(f.read(), label)
        else:
            print("  [--] %s skipped — %s not staged in this tree" % (label, rel))
    # ---- [r3-t21] U9/U10/U11: the EFFECT of the arm / the launch ----
    for label, rel, subject, cases in (
            ("U9/U10", REL_ACTIVITY, "action", (
                "DeepLinkAction.OpenSession", "DeepLinkAction.OpenTerminal",
                "DeepLinkAction.OpenHtmlPreview", "DeepLinkAction.CreateEnvironmentVariable")),
            ("U10b", REL_NAV, "initialDeepLink", (
                "DeepLinkAction.OpenSession", "DeepLinkAction.OpenTerminal",
                "DeepLinkAction.CreateEnvironmentVariable")),
    ):
        apath = os.path.join(root, rel)
        if not os.path.isfile(apath):
            results.append(("%s arm effects" % label, False,
                            "%s not staged in this tree — fail-closed" % rel))
            continue
        with open(apath, "r", encoding="utf-8") as f:
            raw = f.read()
        if subject == "action":
            results = results + analyse_preview_arm(raw, subject)
        results = results + analyse_nav_arms(raw, subject, cases, label)
    opath = os.path.join(root, REL_OFFLOAD)
    if not os.path.isfile(opath):
        results.append(("U11 android-open intent-uri hardening", False,
                        "%s not staged in this tree — fail-closed" % REL_OFFLOAD))
    else:
        with open(opath, "r", encoding="utf-8") as f:
            results = results + analyse_intent_symmetry(f.read())
    failed = [(c, d) for c, ok, d in results if not ok]
    for c, ok, d in results:
        print("  [%s] %s%s" % ("ok" if ok else "RED", c,
                               "" if ok else " — %s" % d))
    if failed:
        print("open_url_broker_scheme_check: %d violation(s)" % len(failed))
        sys.exit(1)
    print("open_url_broker_scheme_check: all %d criteria green" % len(results))
    sys.exit(0)


if __name__ == "__main__":
    main()
