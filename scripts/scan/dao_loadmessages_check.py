#!/usr/bin/env python3
"""
Gate: production code must not read the chat transcript through the raw DAO
(`<dao>.loadMessages(...)`) — every production read funnels through
ChatRepository.loadMessages / loadMessagePageRaw so the big-session
(>2MB CursorWindow / SQLiteBlobTooBigException) tolerance path is
unavoidable.

ISSUE-08 (deep-audit-1018, P1, fixed 2026-10-16, branch
fix/da1018-dao-loadmessages): twelve call sites bypassed the repository —
CompactOrchestration (compact raw-id verify), ChatDebugMethods,
ChatMutationMethods, HeadlessChatRunner (debug RPC + scheduled-task +
headless CLI channels). A scheduled task or CLI read on a big session
threw SQLiteBlobTooBigException and the channel died. All twelve now route
through ChatRepository (count-only sites use messageCount); both production
paged entries (loadMessagesInner, loadMessagePageRaw) funnel through the
single tolerant loadPageTolerant.

Scope:
  * production sources only: any .kt under */src/main/* (plus src/shared/,
    currently zero .kt) — instrumented/unit tests legitimately drive the
    raw DAO against fresh small databases and are out of scope by design;
  * NO file-level whitelist. The repository and the DAO interface are in
    scope like everything else: the DAO only *declares* `fun loadMessages`
    (a declaration has no receiver, so it cannot match). A whole-file
    exemption made ChatRepository.kt a lawless zone where a new helper could
    re-introduce the bypass this gate exists to stop (wide3 witness
    `WHITELIST_BASENAMES`).

[batch5-t01] What this gate can no longer be fooled by (five witnesses were
run against the previous version; each one kept the defect and passed):

  * `(repo.dao).loadMessages(s)` — the receiver regex `<ident>.method(`
    needed the char before the dot to be an identifier, so a parenthesised
    receiver (semantically identical in Kotlin) dropped the call entirely;
  * `val repoView = repo.dao.let { it }` / `repo.dao.let { repoAlias -> … }`
    — the old alias table matched the literal `= <x>.dao` shape only, and the
    receiver's *name* (`repo*`) then overrode the taint. Value flow now
    decides: a receiver that traces to the DAO reds no matter what it is
    called (spelling is never the criterion);
  * `(repo.dao::loadMessages)(s)` — the callable-reference spelling never
    matched `method(`; references are call sites too;
  * `dao.loadMessagesPage(s, 0, Int.MAX_VALUE)` — the sibling paged DAO method
    returns the very same uncleaned `List<MessageEntity>`; the method set is
    now DERIVED from the DAO declarations in the tree (any `*Dao` method whose
    return type mentions `MessageEntity`), and a derived method is pinned the
    moment the tree routes it through a CursorWindow tolerance path.

Criterion (what counts as a bypass): a live call — `.loadMessages(`, a
wrapped chain, a `::loadMessages` reference, or a bare call inside a
DAO-tainted scope — whose receiver resolves to the DAO, OR whose receiver
cannot be resolved at all (a static gate that silently accepts an unknown
receiver goes blind again the moment a new spelling appears), AND whose
enclosing function is not itself part of the tolerance-guarded funnel
(`loadPageSlice` may call the raw paged DAO method because its only caller is
the function that catches SQLiteBlobTooBigException).

Usage: python3 scripts/scan/dao_loadmessages_check.py [repo_root]
       python3 scripts/scan/dao_loadmessages_check.py --selftest
Exit:  0 = clean (or selftest pass), 1 = violation(s) / selftest fail,
       2 = usage error
"""
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _kotlin_ast import (function_body, match_bracket, split_top, stmt_spans,
                         strip_parens)
from _kotlin_valueflow import (KotlinLexError, code_view, expr_end, scope_calls,
                               value_sources)

# The method this gate was written for. Always pinned, in every tree.
PINNED_METHODS = {"loadMessages"}

# Markers of a CursorWindow tolerance path (the reason the funnel exists).
# A function carrying one of these owns the degradation policy for the raw
# reads it (or its immediate callees, one hop) perform.
TOLERANCE_MARKERS = ("SQLiteBlobTooBigException", "CursorWindow")

DAO_TYPE_RE = re.compile(r"\b(?:interface|class)\s+([A-Za-z_]\w*Dao)\b")
FUN_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\(")
ASSIGN_RE = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::\s*([^=\n]+?))?\s*=(?!=)")


def is_production(path: Path, root: Path) -> bool:
    """Production = .kt under */src/main/*, or under src/shared/."""
    rel = path.relative_to(root).as_posix()
    if not rel.endswith(".kt"):
        return False
    if "/src/main/" in rel:
        return True
    return rel.startswith("src/shared/")


def _structure(src: str):
    """(code_view, None) or (None, error). Fail-closed: an unlexable source is
    a violation, never a reason to fall back to raw scanning."""
    try:
        return code_view(src), None
    except KotlinLexError as exc:
        return None, f"source not lexable ({exc}) — refusing to fall back to raw scanning"


# --------------------------------------------------------------------------
# 1. Derived DAO surface
# --------------------------------------------------------------------------

def _type_bodies(code: str):
    """[(type_name, body_start, body_end)] for every interface/class."""
    out = []
    for m in re.finditer(r"\b(?:interface|class)\s+([A-Za-z_]\w*)", code):
        brace = code.find("{", m.end())
        if brace < 0:
            continue
        close = match_bracket(code, brace)
        if close < 0:
            continue
        out.append((m.group(1), brace, close))
        # a generic supertype list can contain `{`? no — first `{` is the body.
    return out


def _declared_methods(code: str, start: int, end: int):
    """[(method_name, return_type_text)] for `fun` declarations in [start,end)."""
    out = []
    for m in FUN_RE.finditer(code, start, end):
        open_p = m.end() - 1
        close = match_bracket(code, open_p)
        if close < 0:
            continue
        tail = []
        i = close + 1
        while i < end and code[i] not in "{=\n":
            tail.append(code[i])
            i += 1
        out.append((m.group(1), "".join(tail).strip().lstrip(":").strip()))
    return out


def _raw_row_methods(code: str) -> set:
    """DAO methods that hand back raw message rows.

    Derived from the DAO type declarations in the tree (registry axis: a
    written-out method list is the blindness — `loadMessagesPage` was missing
    from the previous hardcoded name and handed back exactly the same
    uncleaned `List<MessageEntity>`).
    """
    out = set()
    for name, start, end in _type_bodies(code):
        if not re.search(r"Dao$", name):
            continue
        for meth, ret in _declared_methods(code, start, end):
            if "MessageEntity" in ret:
                out.add(meth)
    return out


# --------------------------------------------------------------------------
# 2. Receiver extraction (spelling-independent)
# --------------------------------------------------------------------------

def _open_left(code: str, close_pos: int) -> int:
    """Index of the opening bracket matching the closer at close_pos."""
    pairs = {")": "(", "]": "[", "}": "{"}
    want = pairs.get(code[close_pos])
    if want is None:
        return -1
    depth = 0
    i = close_pos
    while i >= 0:
        c = code[i]
        if c in ")]}":
            depth += 1
        elif c in "([{":
            depth -= 1
            if depth == 0:
                return i if c == want else -1
        i -= 1
    return -1


def _receiver_expr(code: str, end: int):
    """Postfix expression ending just before `end`: `a.b(c).d` → the whole
    chain. Parenthesised receivers (`(repo.dao)`) are unwrapped by
    `_kind_of`; nothing here depends on the receiver being an identifier.

    Two phases per token so a *statement* keyword before the receiver
    (`for (entity in chatRepository.loadMessages(…)`) can never be glued onto
    it: a primary (identifier / bracketed group) is collected, and only a `.`
    / `?.` immediately to its left continues the chain.
    """
    j = end - 1
    if j < 0:
        return None
    start = None
    while True:
        k = j
        while k >= 0 and code[k] in " \t\r\n":
            k -= 1
        if k < 0:
            break
        c = code[k]
        if c in ")]}":
            op = _open_left(code, k)
            if op < 0:
                return None
            start = op
            k = op - 1
        elif c.isalnum() or c in "_`$":
            while k >= 0 and (code[k].isalnum() or code[k] in "_`$"):
                k -= 1
            start = k + 1
        else:
            break
        m = k
        while m >= 0 and code[m] in " \t\r\n":
            m -= 1
        if m >= 0 and code[m] == ".":
            j = m - 2 if (m - 1 >= 0 and code[m - 1] == "?") else m - 1
            continue
        break
    if start is None:
        return None
    return code[start:end].strip().rstrip("?")


def _last_stmt(body: str) -> str:
    """Trailing statement of a block body (the value of a let/run lambda)."""
    spans = stmt_spans(body)
    if not spans:
        return body.strip()
    a, b = spans[-1]
    return body[a:b].strip()


def _extend_scope_span(code: str, name: str, start: int, open_pos: int, end: int):
    """`with(x) { … }` / `run { … }`: the shared `scope_calls` span stops at the
    argument list for a bare call, so extend it over the following block — the
    bindings the gate resolves live inside that block, not inside the parens.
    (Shared-primitive gap, reported to the main session; nothing here
    re-implements the lexer.)"""
    if code[start:start + len(name)] != name:
        return start, end  # `.let { … }` — span already covers the lambda
    j = end
    while j < len(code) and code[j] in " \t\r\n":
        j += 1
    if j < len(code) and code[j] == "{":
        close = match_bracket(code, j)
        if close > 0:
            return start, close + 1
    return start, end


def _scope_receiver_in(expr: str, name: str, start: int, open_pos: int):
    """Receiver (or `with` argument) of a scope call inside a bare expression."""
    if expr[start:start + len(name)] == name:
        close = match_bracket(expr, open_pos)
        if close < 0:
            return None
        return expr[open_pos + 1:close].strip() or None
    return _receiver_expr(expr, start)


def _chain_components(expr: str):
    """Top-level `.`-separated components of a postfix chain."""
    e = expr.strip()
    parts = []
    depth = 0
    start = 0
    i = 0
    while i < len(e):
        c = e[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "." and depth == 0:
            parts.append(e[start:i])
            start = i + 1
        i += 1
    parts.append(e[start:])
    return [p.strip() for p in parts if p.strip()]


# --------------------------------------------------------------------------
# 3. Taint environment (value flow decides, names only break ties)
# --------------------------------------------------------------------------

def _name_kind(name: str) -> str:
    if re.search(r"dao$", name, re.I):
        return "dao"
    if re.search(r"(repo|repository)", name, re.I):
        return "repo"
    return "unknown"


class Env:
    """Bindings, lambda parameters and function return types of one file."""

    def __init__(self, code: str):
        self.code = code
        self.decls = []    # pos, name, rhs, rhs_pos, type
        self.params = []   # pos, name, type
        self.scopes = []   # start, end, param, rhs, rhs_pos
        self.returns = {}  # function name -> declared return type
        self._build()

    # -- construction ------------------------------------------------------
    def _build(self) -> None:
        code = self.code
        for m in ASSIGN_RE.finditer(code):
            rhs_pos = m.end()
            rhs = code[rhs_pos:expr_end(code, rhs_pos)].strip()
            self.decls.append({
                "pos": m.start(), "name": m.group(1), "rhs": rhs,
                "rhs_pos": rhs_pos, "type": (m.group(2) or "").strip(),
            })
        # parameter lists: functions AND primary constructors
        # (`class ChatRepository(private val dao: ChatDao)` — a constructor
        # property is the most common DAO carrier in this tree).
        for m in re.finditer(
                r"\b(?:fun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)"
                r"|class\s+([A-Za-z_]\w*))\s*\(",
                code):
            open_p = m.end() - 1
            close = match_bracket(code, open_p)
            if close < 0:
                continue
            inner = code[open_p + 1:close]
            base = open_p + 1
            for a, b in split_top(inner, (",",)):
                seg = inner[a:b]
                mm = re.match(r"\s*(?:@\w+\s+)*(?:vararg\s+)?"
                              r"(?:(?:private|public|internal|protected|override)\s+)*"
                              r"(?:val\s+|var\s+)?([A-Za-z_]\w*)\s*:\s*([^=,)]+)", seg)
                if mm:
                    self.params.append({
                        "pos": base + a, "name": mm.group(1),
                        "type": mm.group(2).strip(),
                    })
            if not m.group(1):
                continue  # primary-constructor params carry no return type
            # declared return type (`fun f(...): T`) — feeds call receivers
            tail = []
            i = close + 1
            while i < len(code) and code[i] not in "{=\n":
                tail.append(code[i])
                i += 1
            ret = "".join(tail).strip().lstrip(":").strip()
            if ret:
                self.returns.setdefault(m.group(1), ret)
            # expression body: `fun f(...): T = expr` keeps `T` above; a
            # typeless expression body stays unknown (fail-closed).
        for name, start, open_pos, end in scope_calls(code):
            start, end = _extend_scope_span(code, name, start, open_pos, end)
            recv = self._scope_receiver(name, start, open_pos)
            head = code[open_pos + 1:end - 1] if end > open_pos else ""
            pm = re.match(r"\s*([A-Za-z_]\w*)\s*->", head)
            self.scopes.append({
                "start": start, "end": end,
                "param": pm.group(1) if pm else "it",
                "rhs": recv, "rhs_pos": start,
            })

    def _scope_receiver(self, name: str, start: int, open_pos: int):
        if name in ("with",) or self.code[start:start + len(name)] == name:
            close = match_bracket(self.code, open_pos)
            if close < 0:
                return None
            return self.code[open_pos + 1:close].strip() or None
        return _receiver_expr(self.code, start)

    # -- queries -----------------------------------------------------------
    def binding(self, name: str, pos: int):
        """Innermost binding visible at pos that carries a value/type."""
        best = None
        for sc in self.scopes:
            if sc["param"] != name or not (sc["start"] <= pos < sc["end"]):
                continue
            if sc["rhs"]:
                if best is None or sc["end"] - sc["start"] < best["end"] - best["start"]:
                    best = {"rhs": sc["rhs"], "rhs_pos": sc["rhs_pos"], "type": ""}
        if best is not None:
            return best
        latest = None
        for d in self.decls:
            if d["name"] != name or d["pos"] > pos:
                continue
            if latest is None or d["pos"] > latest["pos"]:
                latest = d
        if latest is not None and (latest["rhs"] or latest["type"]):
            return {"rhs": latest["rhs"], "rhs_pos": latest["rhs_pos"],
                    "type": latest["type"]}
        for p in self.params:
            if p["name"] == name and p["pos"] <= pos:
                return {"rhs": "", "rhs_pos": p["pos"], "type": p["type"]}
        return None


def _kind_of_name(name: str, env: Env, pos: int, depth: int) -> str:
    b = env.binding(name, pos)
    if b is not None:
        if b["rhs"]:
            return _kind_of(b["rhs"], env, b["rhs_pos"], depth + 1)
        t = b["type"]
        if re.search(r"\b\w*Dao\b", t):
            return "dao"
        if "Repository" in t or re.search(r"\b\w*Repo\b", t):
            return "repo"
    return _name_kind(name)


def _kind_of(expr, env: Env, pos: int, depth: int = 0) -> str:
    """'dao' | 'repo' | 'unknown' for a receiver expression.

    Value flow first (`repo.dao.let { it }`, `(repo.dao)`, an alias of an
    alias), names last — and only to decide WHICH safe carrier a value is, so
    a `repoView` name can never launder a DAO value.
    """
    if expr is None or depth > 6:
        return "unknown"
    e = strip_parens(expr.strip())
    e = re.sub(r"\s*!!$", "", e).strip()
    if not e:
        return "unknown"
    m = re.fullmatch(r"(?:this\s*\.\s*)?([A-Za-z_`]\w*)", e)
    if m:
        return _kind_of_name(m.group(1).strip("`"), env, pos, depth)
    # value scopes whose body just hands the receiver back (`repo.dao.let { it }`)
    for name, start, open_pos, end in scope_calls(e):
        if name not in ("let", "run", "with", "takeIf", "takeUnless"):
            continue
        body = e[open_pos + 1:end - 1] if end > open_pos else ""
        hm = re.match(r"\s*([A-Za-z_]\w*)\s*->", body)
        param = hm.group(1) if hm else "it"
        tail = _last_stmt(body[hm.end():] if hm else body)
        if tail.strip("()") == param:
            recv = _scope_receiver_in(e, name, start, open_pos)
            if recv:
                return _kind_of(recv, env, pos, depth + 1)
    sources = value_sources(e)
    if len(sources) > 1 or strip_parens((sources[0] or "").strip()) != e:
        levels = [_kind_of(s, env, pos, depth + 1) for s in sources]
        if "dao" in levels:
            return "dao"
        if "unknown" in levels:
            return "unknown"
        return "repo" if levels else "unknown"
    comps = _chain_components(e)
    if comps:
        last = comps[-1]
        tk = _kind_of(last, env, pos, depth + 1)
        if tk != "unknown":
            return tk
        # a call receiver (`chat(context)`) is resolved by its return type
        cm = re.match(r"([A-Za-z_`]\w*)\s*\(", last)
        if cm and cm.group(1) in env.returns:
            return _kind_from_type(env.returns[cm.group(1)])
    return "unknown"


def _kind_from_type(type_text: str) -> str:
    if not type_text:
        return "unknown"
    if re.search(r"\b\w*Dao\b", type_text):
        return "dao"
    if "Repository" in type_text or re.search(r"\b\w*Repo\b", type_text):
        return "repo"
    return "unknown"


# --------------------------------------------------------------------------
# 4. Call sites
# --------------------------------------------------------------------------

def _skip_ws_forward(code: str, i: int) -> int:
    while i < len(code) and code[i] in " \t\r\n":
        i += 1
    return i


def _is_declaration(code: str, pos: int) -> bool:
    head = code[max(0, pos - 24):pos]
    return re.search(r"\bfun\s+(?:<[^>]*>\s*)?$", head) is not None


def _call_sites(code: str, method: str):
    """Every call site of `method`: [(name_pos, kind, receiver, ref_pos)].

    kind ∈ {'dot', 'ref', 'bare'}; `receiver` is the expression left of the
    receiver dot / `::` (`None` for a bare call).
    """
    out = []
    pat = re.compile(r"(?<![\w`])" + re.escape(method) + r"(?![\w`])")
    for m in pat.finditer(code):
        pos = m.start()
        if _is_declaration(code, pos):
            continue
        before = code[:pos].rstrip()
        if before.endswith("::"):
            ref_pos = len(before) - 2
            out.append((pos, "ref", _receiver_expr(code, ref_pos), ref_pos))
            continue
        nxt = _skip_ws_forward(code, m.end())
        if nxt >= len(code) or code[nxt] != "(":
            continue  # a mention, not a call
        if before.endswith("."):
            dot = len(before) - 1
            out.append((pos, "dot", _receiver_expr(code, dot), dot))
        elif before.endswith("?."):
            out.append((pos, "dot", _receiver_expr(code, len(before) - 2),
                        len(before) - 2))
        else:
            out.append((pos, "bare", None, pos))
    return out


def _function_bodies(code: str):
    """name -> (start, end) for every `fun` in the file.

    Falls back to a local head scan when `function_body` cannot delimit the
    body: a return type wrapped onto the next line makes the shared helper
    return None, and a function the gate cannot see is a function whose
    tolerance markers (and therefore the whole funnel derivation) vanish —
    fail-open, so it is closed here.
    """
    out = {}
    for m in FUN_RE.finditer(code):
        name = m.group(1)
        if name in out:
            continue
        span = function_body(code, name)
        if span:
            out[name] = (span[0], span[1])
            continue
        close = match_bracket(code, m.end() - 1)
        if close < 0:
            continue
        j = close + 1
        limit = min(len(code), close + 240)
        while j < limit and code[j] not in "{=":
            j += 1
        if j >= limit:
            continue
        if code[j] == "{":
            end = match_bracket(code, j)
            if end > 0:
                out[name] = (j + 1, end)
        else:
            out[name] = (j + 1, expr_end(code, j + 1))
    return out


def _enclosing_fn(bodies, pos: int):
    best = None
    for name, (s, e) in bodies.items():
        if s <= pos < e:
            if best is None or (e - s) < (bodies[best][1] - bodies[best][0]):
                best = name
    return best


def _guarded_functions(code: str, bodies, tolerant: set) -> set:
    """The tolerance funnel: the tolerant functions plus callees that are ONLY
    called from inside them (one hop is what the shipped shape needs:
    `loadPageTolerant` catches, `loadPageSlice` picks the query)."""
    guarded = set(tolerant)
    for _ in range(4):
        grew = False
        for name in bodies:
            if name in guarded:
                continue
            sites = [p for p, _k, _r, _rp in _call_sites(code, name)]
            exprs = [p for p, kind, _r, _rp in _call_sites(code, name) if kind == "bare"]
            if not exprs:
                continue
            owners = {_enclosing_fn(bodies, p) for p in exprs}
            if None in owners:
                continue
            if owners <= guarded:
                guarded.add(name)
                grew = True
        if not grew:
            break
    return guarded


def _in_dao_scope(env: Env, pos: int):
    """True when pos sits inside a scope function whose receiver is the DAO
    (`with(repo.dao) { loadMessages(...) }`, `repo.dao.let { it.loadMessages() }`)."""
    for sc in env.scopes:
        if not (sc["start"] <= pos < sc["end"]):
            continue
        if sc["rhs"] and _kind_of(sc["rhs"], env, sc["rhs_pos"]) == "dao":
            return True
    return False


# --------------------------------------------------------------------------
# 5. Scan
# --------------------------------------------------------------------------

def scan(root: Path) -> list:
    """Return violation descriptions (file:line + snippet), empty if clean."""
    violations = []
    files = []
    for path in sorted(root.rglob("*.kt")):
        if not is_production(path, root):
            continue
        try:
            raw = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:  # unreadable = report it
            violations.append(f"{path}: unreadable ({exc})")
            continue
        code, err = _structure(raw)
        if err is not None:
            violations.append(f"{path}: {err}")
            continue
        files.append((path, code))
    if not files:
        violations.append("no production .kt sources found — scanner is blind on this tree")
        return violations

    raw_methods = set()
    for _path, code in files:
        raw_methods |= _raw_row_methods(code)

    for path, code in files:
        env = Env(code)
        bodies = _function_bodies(code)
        tolerant = {
            name for name, (s, e) in bodies.items()
            if any(marker in code[s:e] for marker in TOLERANCE_MARKERS)
        }
        guarded = _guarded_functions(code, bodies, tolerant)

        banned = set(PINNED_METHODS)
        for meth in raw_methods:
            if meth in banned:
                continue
            for pos, _kind, _recv, _rp in _call_sites(code, meth):
                if _enclosing_fn(bodies, pos) in guarded:
                    banned.add(meth)
                    break

        for meth in sorted(banned):
            for pos, kind, receiver, _rp in _call_sites(code, meth):
                owner = _enclosing_fn(bodies, pos)
                if owner in guarded:
                    continue  # inside the tolerance funnel by construction
                line = code.count("\n", 0, pos) + 1
                snippet = code[max(0, pos - 60):pos + 40].replace("\n", " ")
                if kind == "bare":
                    if _in_dao_scope(env, pos):
                        why = "DAO scope-function read"
                    else:
                        why = ("unqualified " + meth + "( call — cannot prove the "
                               "receiver is the repository (fail-closed)")
                    violations.append(
                        f"{path}:{line}: {why} — `{meth}(` runs on a dao receiver: "
                        f"{snippet.strip()[:120]}")
                    continue
                resolved = _kind_of(receiver, env, pos)
                if resolved == "repo":
                    continue
                why = ("direct DAO read" if resolved == "dao"
                       else "receiver not provably the repository")
                shown = receiver if receiver else ""
                violations.append(
                    f"{path}:{line}: {why} — `{shown}.{meth}(` bypasses "
                    f"ChatRepository.loadPageTolerant: {snippet.strip()[:120]}")
    return violations


def _write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


# --- fixtures --------------------------------------------------------------
DAO_DECL = """package com.example.data

interface ChatDao {
    suspend fun loadMessages(sessionId: String): List<MessageEntity>
    suspend fun loadMessagesPage(sessionId: String, offset: Int, limit: Int): List<MessageEntity>
    suspend fun messageCountForSession(sessionId: String): Int
}
"""

# The shipped tolerant funnel: raw paged reads live behind the function that
# catches the CursorWindow overflow, one hop away.
FUNNEL = """package com.example.data

class ChatRepository(private val dao: ChatDao) {
    suspend fun loadMessages(s: String): List<MessageEntity> = loadInner(s)

    private suspend fun loadInner(s: String): List<MessageEntity> {
        val total = dao.messageCountForSession(s)
        val out = ArrayList<MessageEntity>(total)
        out.addAll(loadPageTolerant(s, 0, total))
        return out
    }

    private suspend fun loadPageTolerant(
        s: String,
        o: Int,
        l: Int,
    ): List<MessageEntity> = try {
        loadPageSlice(s, o, l)
    } catch (e: SQLiteBlobTooBigException) {
        emptyList()
    }

    private suspend fun loadPageSlice(s: String, o: Int, l: Int): List<MessageEntity> =
        dao.loadMessagesPage(s, o, l)
}
"""


def selftest() -> int:
    """Arms: every banned spelling must red, every legal one must stay green.
    Each arm names its own expectation so a broken criterion cannot pass as
    noise from a neighbouring arm."""
    ok = True

    def arm(label: str, files: dict, expect: int, needle: str = "") -> None:
        nonlocal ok
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, content in files.items():
                _write(root / rel, content)
            hits = scan(root)
            good = len(hits) == expect and (not needle or any(needle in h for h in hits))
            if good:
                print(f"[selftest] {label}: OK ({len(hits)} violation(s))")
            else:
                print(f"[selftest] {label}: FAIL expected {expect} hit(s) "
                      f"containing {needle!r}, got {hits!r}")
                ok = False

    P = "src/android/app/src/main/java/com/example/"
    T = "src/android/app/src/test/java/com/example/"

    # ---- banned spellings -------------------------------------------------
    arm("A direct .dao receiver reds",
        {P + "Foo.kt": "class Foo { fun bad(repo: ChatRepository) {"
                       " val rows = repo.dao.loadMessages(\"s1\") } }\n"},
        1, "direct DAO read")

    arm("B local DAO alias reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val rawDao = repo.dao\n"
                       "        val rows = rawDao.loadMessages(\"s1\")\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("C wrapped chain (dot on next line) reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val rows = repo.dao\n"
                       "            .loadMessages(\"s1\")\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("D DAO inside a with() scope reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        with(repo.dao) { loadMessages(\"s1\") }\n"
                       "    }\n}\n"},
        1, "DAO scope-function read")

    arm("E repository file is NOT a lawless zone (no whitelist)",
        {P + "ChatRepository.kt": "class ChatRepository {\n"
                                  "    suspend fun sessionTokenUsages(s: String) =\n"
                                  "        this.dao.loadMessages(s).mapNotNull { it.tokenUsage }\n"
                                  "}\n"},
        1, "direct DAO read")

    arm("F unreadable receiver is fail-closed",
        {P + "Foo.kt": "class Foo { fun f(x: Any) = x.loadMessages(\"s1\") }\n"},
        1, "not provably the repository")

    # ---- batch5 witnesses: the five spellings that used to pass -----------
    arm("W1 parenthesised receiver reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val rows = (repo.dao).loadMessages(\"s1\")\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("W2 .let { it } alias under a repo-ish name reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val repoView = repo.dao.let { it }\n"
                       "        val rows = repoView.loadMessages(\"s1\")\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("W3 lambda parameter named repoAlias reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val rows = repo.dao.let { repoAlias ->"
                       " repoAlias.loadMessages(\"s1\") }\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("W4 callable reference reds",
        {P + "Foo.kt": "class Foo {\n"
                       "    suspend fun f(repo: ChatRepository) {\n"
                       "        val rows = (repo.dao::loadMessages)(\"s1\")\n"
                       "    }\n}\n"},
        1, "direct DAO read")

    arm("W5 sibling paged DAO method outside the funnel reds",
        {P + "ChatRepository.kt": FUNNEL.replace(
            "        out.addAll(loadPageTolerant(s, 0, total))\n",
            "        out.addAll(dao.loadMessagesPage(s, 0, total))\n"),
         P + "data/ChatDao.kt": DAO_DECL},
        1, "direct DAO read")

    arm("W5b the shipped funnel itself stays green",
        {P + "ChatRepository.kt": FUNNEL, P + "data/ChatDao.kt": DAO_DECL},
        0)

    # ---- legal spellings --------------------------------------------------
    arm("G repository receiver green",
        {P + "Clean.kt": "class Clean {\n"
                         "    suspend fun ok(repo: ChatRepository) = repo.loadMessages(\"s1\")\n"
                         "    suspend fun ok2(app: MinisApp) =\n"
                         "        app.chatRepository.loadMessages(\"s1\")\n"
                         "}\n"},
        0)

    arm("G2 receiver from a repository-returning factory green",
        {P + "Clean.kt": "class Clean {\n"
                         "    fun chat(ctx: Any): ChatRepository = ChatRepository()\n"
                         "    suspend fun ok(ctx: Any) {\n"
                         "        val repo = chat(ctx)\n"
                         "        val rows = repo.loadMessages(\"s1\")\n"
                         "    }\n}\n"},
        0)

    arm("G3 DAO value behind a dao-typed declaration reds even when named repo",
        {P + "Clean.kt": "class Clean(private val repoStore: ChatDao) {\n"
                         "    suspend fun bad() = repoStore.loadMessages(\"s1\")\n"
                         "}\n"},
        1, "direct DAO read")

    arm("H the DAO interface declaration green",
        {P + "ChatDao.kt": DAO_DECL},
        0)

    arm("I doc comment mentioning dao.loadMessages green",
        {P + "ChatRepository.kt": "class ChatRepository {\n"
                                  "    // the legacy `dao.loadMessages` path issued one SELECT\n"
                                  "    suspend fun loadMessages(s: String) = loadMessagesInner(s)\n"
                                  "}\n"},
        0)

    arm("J test source sets are out of scope",
        {P + "Clean.kt": "class Clean { fun ok(repo: ChatRepository) ="
                         " repo.loadMessages(\"s1\") }\n",
         T + "BadTest.kt": "class BadTest { fun t(repo: ChatRepository) ="
                           " repo.dao.loadMessages(\"s1\") }\n",
         "src/android/app/src/androidTest/java/com/example/BadIt.kt":
             "class BadIt { fun t(repo: ChatRepository) = repo.dao.loadMessages(\"s1\") }\n"},
        0)

    if ok:
        print("DAO_LOADMESSAGES_SELFTEST=PASS (12 arms)")
        return 0
    print("DAO_LOADMESSAGES_SELFTEST=FAIL")
    return 1


def main(argv: list) -> int:
    root = None
    for i, arg in enumerate(argv):
        if arg == "--selftest":
            return selftest()
        if arg == "--root":
            if i + 1 >= len(argv):
                print("usage: --root needs a value", file=sys.stderr)
                return 2
            root = Path(argv[i + 1])
        elif not arg.startswith("--"):
            root = Path(arg)
    if root is None:
        root = Path.cwd()
    if not root.is_dir():
        print(f"repo root not found: {root}", file=sys.stderr)
        return 2

    violations = scan(root)
    if violations:
        print("[dao-loadmessages] DIRECT dao.loadMessages BYPASSES FOUND:")
        for v in violations:
            print(f"  FAIL: {v}")
        print(
            "Fix: route production reads through ChatRepository.loadMessages "
            "/ loadMessagePageRaw (count-only sites: messageCount)."
        )
        return 1
    print("[dao-loadmessages] clean: 0 direct dao.loadMessages( in production")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
