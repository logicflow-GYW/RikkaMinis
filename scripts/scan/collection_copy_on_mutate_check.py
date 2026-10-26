#!/usr/bin/env python3
"""collection_copy_on_mutate_check — deep-audit-1023 FIX-D (ISSUE-04, P1) gate.

Pins the config-collections write contract: a `mutate` helper must NEVER
persist a mutated LIVE object from `_config.value` — the object handed back
by `repo.group(id)` / `repo.instance(id)` / `repo.entry(id)` is the exact
instance concurrent readers (UI model picker, group detail screen reorder,
`groupsList` / `groups.update` RPCs) are iterating. Three compliant shapes,
mirroring the sibling idioms that already existed before this gate:

  arm0 (delegated copy core — GroupsCollection since [fix/da1023-D]):
        the body delegates to the shared pure helper `mutatedGroupCopy`
        and persists ITS RETURN (`repo.updateGroup(mutatedGroupCopy(g,
        apply))`). The helper's own body (GroupWriteRules.kt) must
        materialise a detached copy (`.copy(` with the mutable member
        re-instantiated via `toMutableList()`), hand THE COPY to the lambda,
        and return THE COPY — a hollowed helper (`apply(g); return g`) is a
        violation.
  arm1 (in-place style, `apply: (T) -> Unit` — legacy shape, still accepted):
        the body must materialise a detached copy FIRST
        (`val working = g.copy(...)` with any shared mutable member
        re-instantiated), hand THE COPY to `apply(...)`, and persist THE
        COPY via `repo.update*(...)`. Persisting the originally-fetched
        identifier, or copying only after `apply`, is a violation.
  arm2 (functional style, `apply: (T) -> T` — Providers/ModelsCollection):
        the persisted value must be the lambda's return, i.e. the update
        call takes the invocation inline (`repo.updateInstance(apply(x))`).
        Ignoring the return and re-persisting the loaded object violates.

--- Judged on the effect, not the spelling (batch7-t09) ---------------------
The 13 witnesses of this round were all "the gate matched a token; the defect
moved the token's effect". Each one is now covered by a structural judgment:

  * 13-1  a function-typed value is called BOTH `f(x)` and `f.invoke(x)`; the
          `apply( )` regex only knew the first, so `apply.invoke(g)` left no
          trace. The lambda is now resolved by spelling set, and EVERY return
          that hands a value back must be the working copy (the old check read
          `returns[-1]`, so an unreachable `return working` after `return g`
          re-certified the live object).
  * 13-2  `toMutableList()` must be inside the `.copy(...)` arguments; a decoy
          binding elsewhere in the body used to satisfy the substring test.
  * 13-3/13-5  `repo.update*()` calls are taken only from REACHABLE code
          (`path_feasible`), and ALL of them are checked — `if (false)
          repo.updateGroup(mutatedGroupCopy(...))` no longer occupies the `[0]`
          slot that used to be the only one inspected.
  * 13-4  the persisted argument must be exactly `apply(<loaded object>)`: a
          `.let { return }` INSIDE the parentheses is textually "one apply(…)
          call" but the bare `return` exits `mutate()` before the write.
  * 13-6  no self-written comment stripper: string literals are masked by the
          shared lexer, so `val m = "/*"` cannot open a hiding place.

History: GroupsCollection.mutate was the third entry of the
[fix/audit-b18 / T9-L2] family (ProviderRepository.updateGroup and
ProviderMutationMethods.groupsUpdate were already converted) — this gate
exists so a fourth entry or a revert of the Groups fix cannot land silently.

Usage:
  python3 collection_copy_on_mutate_check.py <repo_root>   # real tree
  python3 collection_copy_on_mutate_check.py --selftest    # fixtures

Selftest marker: COLLECTION_COPY_ON_MUTATE_SELFTEST=PASS
"""

import glob
import os
import re
import sys
import tempfile

# [batch7-t09] 共享 Kotlin 词法/语法/值流原语。本门上一轮自己写了
# `strip_comments`（完全没有字符串状态机：写两处 `"/*"` / `"*/"` 就能让中间
# 的真代码整段隐身）、`extract_braced_fun`（裸花括号计数）、`split_args`，并
# 用裸正则判断调用形态。外部见证各一击穿 —— 门自己再手写词法器 = 直接违规。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    function_body as _ast_function_body,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    expr_end,
    path_feasible,
    value_sources,
)

COLLECTIONS_DIR = "src/android/app/src/main/java/com/rikkaminis/app/config/collections"
COPY_HELPER = "mutatedGroupCopy"

MARKER = "COLLECTION_COPY_ON_MUTATE_SELFTEST=PASS"

# [wide-1024 arm-scope] The visibility modifier is DERIVED, not spelled: the
# pre-write-funnel shape pinned `private fun mutate(` literally, so widening the
# helper to `internal` (a legitimate-looking refactor) dropped the whole
# function out of the scan while `check_tree`'s aggregate `seen` list stayed
# non-empty thanks to the sibling collections. `check_tree` now also holds the
# per-file declaration count equal to the analysed count (fail-closed).
SIG_PAT = re.compile(
    r"(?:(?:private|internal|public|protected|override|suspend)\s+)*"
    r"fun\s+mutate\s*\(\s*id:\s*String\s*,\s*(\w+)\s*:\s*\(\s*(\w+)\s*\)\s*->\s*(\w+)\s*\)"
)
UPDATE_PAT = re.compile(r"\brepo\s*\.\s*update\w*\s*\(")
COPY_DECL_RE = re.compile(r"\bval\s+(\w+)\s*=\s*\w+\s*\.\s*copy\s*\(")
LOADER_RE = re.compile(
    r"\bval\s+(\w+)\s*=\s*[^\n=]*?\b(?:repo\s*\.\s*\w+|entry|instance|group)\s*\(")
FRESH_LIST_RE = re.compile(
    r"\.\s*toMutableList\s*\(\s*\)"
    r"|\bmutableListOf\s*\("
    r"|\bArrayList\s*<[^>]*>\s*\("
    r"|\bArrayList\s*\("
    r"|\.\s*toList\s*\(\s*\)\s*\.\s*toMutableList\s*\(")
RETURN_RE = re.compile(r"\breturn\b(?!\s*@)")


def code_view(src: str) -> str:
    """Structural view (comments + string/char bodies blanked, equal length)."""
    return mask_code(src)


def invocation_res(name: str):
    """`name(` / `name.invoke(` / `name?.invoke(` — a function-typed value can
    be called either way, and the gate used to grade only the first spelling
    (witness 13-1: `apply.invoke(g)` left no trace at all)."""
    return re.compile(
        r"(?<![\w`])" + re.escape(name) + r"\s*(?:\?\.\s*)?(?:\.\s*invoke\s*)?\(")


def update_call_args(code):
    """(offset, whole argument text or None) for every REACHABLE `repo.update*()`.

    Reachable: `if (false) repo.updateGroup(g)` is not a call the program makes,
    and letting it sit in slot `[0]` used to satisfy the whole arm while the
    real persist call in slot `[1]` went unread (witness 13-3 / 13-5).
    None marks an unparsable argument list (fail-closed)."""
    out = []
    for m in UPDATE_PAT.finditer(code):
        if not path_feasible(code, m.start()):
            continue
        open_paren = m.end() - 1
        close = match_bracket(code, open_paren)
        if close < 0:
            out.append((m.start(), None))
            continue
        out.append((m.start(), code[open_paren + 1:close]))
    return out


def call_is_whole(argtext, name, live=None):
    """True when [argtext] is EXACTLY one `name(...)` call — nothing appended.

    `apply(inst)` passes, `apply(inst).let { inst }` does not: the trailing
    `.let` discards the value the call produced, which is the shape that made
    every ProvidersCollection write vanish while the gate stayed green.

    [batch7-t09 witness 13-4] With [live] given, the single argument must ALSO
    be that identifier itself and must not contain a bare `return`:
    `apply(inst.let { return })` is textually "one apply(…) call", yet the
    `return` exits `mutate()` before the write ever happens.
    """
    if argtext is None:
        return False
    s = strip_parens(argtext.strip())
    if not s:
        return False
    m = invocation_res(name).match(s)
    if not m:
        return False
    close = match_bracket(s, m.end() - 1)
    if close < 0 or s[close + 1:].strip() != "":
        return False
    if live is not None:
        inner = s[m.end():close]
        if strip_parens(inner.strip()) != live:
            return False
        if RETURN_RE.search(inner):
            return False
    return True


def apply_args(body, name):
    """Argument text of every REACHABLE invocation of the function-typed [name]."""
    out = []
    for m in invocation_res(name).finditer(body):
        if not path_feasible(body, m.start()):
            continue
        open_paren = m.end() - 1
        close = match_bracket(body, open_paren)
        if close < 0:
            out.append(None)
            continue
        out.append(body[open_paren + 1:close].strip())
    return out


def copy_args_text(body):
    """Argument list text of the working-copy `x.copy(...)` call (None if absent)."""
    m = re.search(r"\bcopy\s*\(", body)
    if not m:
        return None
    open_paren = m.end() - 1
    close = match_bracket(body, open_paren)
    if close < 0:
        return None
    return body[open_paren + 1:close]


def returned_values(body):
    """Every value a `return` hands back, resolved through `value_sources`.

    ALL returns are collected, not just the last one: an unreachable
    `return working` after `return g` used to re-certify the live object.
    """
    out = []
    for m in RETURN_RE.finditer(body):
        expr = body[m.end():expr_end(body, m.end())].strip()
        if not expr:
            continue
        for src in value_sources(expr):
            out.append(strip_parens(src.strip()))
    return out


def loaded_identifier(body):
    """The identifier bound to the object fetched from the repository."""
    m = LOADER_RE.search(body)
    return m.group(1) if m else None


def lambda_param(code, fn_name):
    """Name of the function-typed parameter of `fun <fn_name>(...)`."""
    m = re.search(r"fun\s+" + re.escape(fn_name) + r"\s*\(", code)
    if not m:
        return None
    open_paren = m.end() - 1
    close = match_bracket(code, open_paren)
    if close < 0:
        return None
    inner = code[open_paren + 1:close]
    for a, b in split_top(inner, (",",)):
        piece = inner[a:b]
        if "->" in piece:
            return piece.split(":")[0].strip()
    return None


def extract_mutate_bodies(code):
    """(param, ret_type, body) for every `fun mutate(id: String, …)`."""
    out = []
    for m in SIG_PAT.finditer(code):
        brace = code.find("{", m.end())
        if brace < 0:
            continue
        close = match_bracket(code, brace)
        if close < 0:
            continue
        out.append((m.group(1), m.group(3), code[brace:close + 1]))
    return out


def extract_helper_body(code, name):
    """Body text of `fun <name>(...)` (top-level or member), or None."""
    got = _ast_function_body(code, name)
    if not got:
        return None
    start, end, _kind = got
    return code[start:end]


def check_copy_core(rel, core_body, what, require_return=False, live_param=None):
    """Shared arm0/arm1 copy-shape check. Returns violation or None.

    [wide-1024 arm0] A `.copy(` + `apply(copy)` prefix is not the contract:
    the copy must be the value that LEAVES the function. `apply(live); return
    live` alongside a decoy working copy (the witnessed shape) kept the old
    criteria green because they only ever asked "does a copy exist and is it
    handed to apply somewhere"."""
    copy_m = COPY_DECL_RE.search(core_body)
    if copy_m is None:
        return (
            f"{rel}: {what} is hollowed — no `.copy(` working copy inside "
            f"(live-object mutation of _config.value, ISSUE-04 regression)"
        )
    copy_var = copy_m.group(1)

    # [batch7-t09 witness 13-2] the fresh mutable member must be inside the
    # `.copy(...)` arguments — `val _unused = g.memberEntryIds.toMutableList()`
    # beside a bare `g.copy()` satisfied the old substring test while the copy
    # shared the live backing list.
    copy_args = copy_args_text(core_body)
    if copy_args is None or not FRESH_LIST_RE.search(copy_args):
        return (
            f"{rel}: {what} copy does not re-materialise the mutable member inside "
            f"`.copy(... args)` — a data-class copy shares its backing list, so "
            f"`apply()` still mutates the LIVE list (a `toMutableList()` elsewhere "
            f"in the body is a decoy)"
        )

    invocations = apply_args(core_body, live_param or "apply")
    if not invocations:
        return (
            f"{rel}: {what} never hands the working copy `{copy_var}` to the lambda"
        )
    stray = [a for a in invocations if a != copy_var]
    if stray:
        return (
            f"{rel}: {what} hands the lambda the LIVE object (`{stray[0]}`) beside the "
            f"working copy — concurrent readers iterate the live instance "
            f"(ISSUE-04 ConcurrentModification / lost emission)"
        )
    if require_return:
        returns = returned_values(core_body)
        if not returns:
            return (
                f"{rel}: {what} has no `return` statement (fail-closed) — cannot "
                f"verify the copy is what leaves the function"
            )
        bad = [r for r in returns if r != copy_var]
        if bad:
            return (
                f"{rel}: {what} returns `{bad[0]}`, not the working copy "
                f"`{copy_var}` — the caller is handed the live object again "
                f"(copy-on-write collapsed back into in-place mutation; every "
                f"return value is graded, not just the last one)"
            )
    return None


def check_mutation_site(rel, param, ret_type, body, helpers=None):
    """Return a violation string or None."""
    ups = update_call_args(body)
    # arm0: delegated copy core (mutatedGroupCopy) — check BEFORE the
    # `apply(` invocation check, since the lambda is passed, not invoked.
    if COPY_HELPER + "(" in body:
        if not ups:
            return f"{rel}: mutate never persists via repo.update*()"
        for _pos, arg in ups:
            if not call_is_whole(arg, COPY_HELPER):
                shown = "<unparsable>" if arg is None else arg.strip()
                return (
                    f"{rel}: delegated mutate must persist the helper's return "
                    f"(`repo.update*({COPY_HELPER}(…))` and nothing else), got "
                    f"`{shown}`"
                )
        live_calls = apply_args(body, param)
        if live_calls:
            return (
                f"{rel}: delegated mutate also invokes `{param}(…)` on the live object "
                f"({live_calls[0]!r}) beside the helper — the copy-on-write funnel is "
                f"bypassed at the call site"
            )
        entry = (helpers or {}).get(COPY_HELPER)
        if entry is None:
            return (
                f"{rel}: `{COPY_HELPER}` definition not found under "
                f"{COLLECTIONS_DIR} — the delegate target moved; update this gate"
            )
        hbody, hparam = entry
        return check_copy_core(rel, hbody, f"`{COPY_HELPER}`", require_return=True,
                               live_param=hparam)

    invocations = [m.start() for m in invocation_res(param).finditer(body)
                   if path_feasible(body, m.start())]
    if not invocations:
        return (
            f"{rel}: mutate lambda `{param}` is never invoked on a reachable path "
            f"(every call sits in a dead branch such as `if (false)`) — the edit is "
            f"silently dropped while the body still reads like a working funnel"
        )

    if ret_type == "Unit":
        # arm1: copy-before-apply; persist & apply THE COPY
        v = check_copy_core(rel, body, "in-place mutate `(T) -> Unit`", live_param=param)
        if v:
            return v
        copy_m = COPY_DECL_RE.search(body)
        copy_var = copy_m.group(1)
        if copy_m.start() > invocations[0]:
            return (
                f"{rel}: `.copy(` happens AFTER `{param}(` — the lambda mutates "
                f"the live object first"
            )
        if not ups:
            return f"{rel}: mutate never persists via repo.update*()"
        for _pos, arg in ups:
            got = "<unparsable>" if arg is None else arg.strip()
            if got != copy_var:
                return (
                    f"{rel}: repo.update*() persists `{got}` — must persist the working "
                    f"copy `{copy_var}` (every reachable persist call is graded)"
                )
        return None

    # arm2: functional style — persist the lambda's return
    # [wide-1024 arm2 + batch7-t09 13-4] The persisted expression must be
    # EXACTLY `apply(<loaded object>)`: `apply(inst).let { inst }` matched the
    # old prefix check and threw the new instance away, and
    # `apply(inst.let { return })` kept the prefix while exiting `mutate()`
    # before the write — both silently discard the edit.
    if not ups:
        return f"{rel}: mutate never persists via repo.update*()"
    live = loaded_identifier(body)
    for _pos, arg in ups:
        if call_is_whole(arg, param, live=live):
            continue
        got = "<unparsable>" if arg is None else arg.strip()
        if not invocation_res(param).match(strip_parens(got)):
            return (
                f"{rel}: functional mutate calls the lambda but persists "
                f"`{got}` — the loaded object is re-persisted and the mutation "
                "is silently dropped"
            )
        return (
            f"{rel}: functional mutate must persist the lambda's return "
            f"(`repo.update*({param}({live or 'x'}))` and nothing else), got `{got}` "
            "— a trailing `.let`/`.also` discards the mutation, and a `return` "
            "inside the argument exits mutate() before the write"
        )
    return None


def check_tree(root):
    violations = []
    seen = []
    pattern = os.path.join(root, COLLECTIONS_DIR, "*.kt")
    files = sorted(glob.glob(pattern))
    if not files:
        return [f"no collections found under {COLLECTIONS_DIR}"]
    stripped = {}
    for path in files:
        rel = os.path.relpath(path, root).replace(os.sep, "/")
        src = open(path, encoding="utf-8", errors="replace").read()
        try:
            stripped[rel] = code_view(src)
        except KotlinLexError as exc:
            # fail-closed: an unlexable file is a violation, never a reason to
            # fall back to raw-text scanning (string bodies would be graded as
            # code, which is exactly the 13-6 hiding place).
            violations.append(
                f"{rel}: the lexer refused the file ({exc}) — the mutate contract "
                "cannot be established (fail-closed)"
            )
    # helper definitions are cross-file (GroupWriteRules.kt)
    helpers = {}
    for _rel, code in stripped.items():
        hbody = extract_helper_body(code, COPY_HELPER)
        if hbody is not None:
            helpers[COPY_HELPER] = (hbody, lambda_param(code, COPY_HELPER))
    for rel, code in stripped.items():
        declared = len(re.findall(r"\bfun\s+mutate\s*\(", code))
        bodies = extract_mutate_bodies(code)
        if declared != len(bodies):
            violations.append(
                f"{rel}: {declared} `fun mutate` declaration(s) but {len(bodies)} "
                "analysed — a mutate site slipped out of the scan's signature "
                "grammar (visibility/spelling drift, fail-closed)"
            )
        for param, ret_type, body in bodies:
            seen.append(f"{rel}:{ret_type}")
            v = check_mutation_site(rel, param, ret_type, body, helpers)
            if v:
                violations.append(v)
    if not seen:
        violations.append(
            "no `fun mutate` found in config/collections — the write "
            "funnel changed shape, update this gate"
        )
    return violations

# --- fixtures -------------------------------------------------------------

FIX_DELEGATE_OK = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList())
    apply(working)
    return working
}
"""

FIX_DELEGATE_HOLLOW = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    apply(g)
    return g
}
"""

FIX_DELEGATE_NO_COPY_SHARING = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    val working = g.copy()
    apply(working)
    return working
}
"""

# [wide-1024 arm0] the witnessed shape: a real working copy is materialised and
# handed to apply(), then the LIVE object is mutated and returned beside it.
FIX_DELEGATE_APPLIES_LIVE = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList())
    apply(working)
    apply(g)
    return g
}
"""

# [wide-1024 arm2] the persisted expression is not the lambda's return: the
# `.let { inst }` suffix keeps the prefix `apply(inst)` the old arm matched.
FIX_FUNCTIONAL_DISCARDS_RETURN = """package com.rikkaminis.app.config.collections

class ProvidersCollection {
    private fun mutate(id: String, apply: (ProviderInstance) -> ProviderInstance) {
        val inst = repo.instance(id) ?: throw ConfigError.UnknownPath("providers.$id")
        repo.updateInstance(apply(inst).let { inst })
    }
}
"""

# [wide-1024 scope] `internal` is not `private`: the old signature grammar left
# the whole function unscanned while the aggregate `seen` list stayed non-empty.
FIX_SCOPE_INTERNAL_LIVE = """package com.rikkaminis.app.config.collections

class ModelsCollection {
    internal fun mutate(id: String, apply: (ModelEntry) -> ModelEntry) {
        val e = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        apply(e)
        repo.updateEntry(e)
    }
}
"""

FIX_SCOPE_INTERNAL_OK = """package com.rikkaminis.app.config.collections

class ModelsCollection {
    internal fun mutate(id: String, apply: (ModelEntry) -> ModelEntry) {
        val e = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        repo.updateEntry(apply(e))
    }
}
"""

FIX_INPLACE_OK = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList())
        apply(working)
        repo.updateGroup(working)
    }
}
"""

FIX_INPLACE_DIRTY = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        apply(g)
        repo.updateGroup(g)
    }
}
"""

FIX_INPLACE_LATE_COPY = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        apply(g)
        val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList())
        repo.updateGroup(working)
    }
}
"""

FIX_INPLACE_PERSIST_LIVE = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList())
        apply(working)
        repo.updateGroup(g)
    }
}
"""

FIX_FUNCTIONAL_OK = """package com.rikkaminis.app.config.collections

class ProvidersCollection {
    private fun mutate(id: String, apply: (ProviderInstance) -> ProviderInstance) {
        val inst = repo.instance(id) ?: throw ConfigError.UnknownPath("providers.$id")
        repo.updateInstance(apply(inst))
    }
}
"""

FIX_FUNCTIONAL_DIRTY = """package com.rikkaminis.app.config.collections

class ProvidersCollection {
    private fun mutate(id: String, apply: (ProviderInstance) -> ProviderInstance) {
        val inst = repo.instance(id) ?: throw ConfigError.UnknownPath("providers.$id")
        apply(inst)
        repo.updateInstance(inst)
    }
}
"""



# ── batch7-t09 witness fixtures (every one was GREEN before this branch) ──

# 13-1 (axis spelling): the lambda is invoked through `.invoke`, and an
# unreachable `return working` keeps the last-return check happy.
B7_DELEGATE_INVOKE = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList())
    apply(working)
    apply.invoke(g)
    return g
    return working
}
"""

# 13-2 (axis order): the `toMutableList()` happens, but outside the copy call.
B7_DELEGATE_DECOY_COPY = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    val _unused = g.memberEntryIds.toMutableList()
    val working = g.copy()
    apply(working)
    return working
}
"""

# 13-3 (axis anchor): the compliant call sits in a dead branch; the live path
# mutates and persists the live object.
B7_GROUP_DEAD_BRANCH = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        if (false) repo.updateGroup(mutatedGroupCopy(g, apply))
        apply(g)
        repo.updateGroup(g)
    }
}
"""

# 13-4 (axis dataflow): "one apply(...) call" whose argument exits the helper.
B7_FUNCTIONAL_INNER_RETURN = """package com.rikkaminis.app.config.collections

class ProvidersCollection {
    private fun mutate(id: String, apply: (ProviderInstance) -> ProviderInstance) {
        val inst = repo.instance(id) ?: throw ConfigError.UnknownPath("providers.$id")
        repo.updateInstance(apply(inst.let { return }))
    }
}
"""

# 13-5 (axis anchor): the dead branch occupies the first persist slot.
B7_MODELS_DEAD_BRANCH = """package com.rikkaminis.app.config.collections

class ModelsCollection {
    private fun mutate(id: String, apply: (ModelEntry) -> ModelEntry) {
        val e = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        if (false) repo.updateEntry(apply(e))
        repo.updateEntry(e)
    }
}
"""

# 13-6 (axis parser-death): two harmless string literals create a hiding place
# for the self-written comment stripper.
B7_DELEGATE_STRING_MASK = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList())
    val openMask = "/*"
    apply(g)
    if (openMask.isNotEmpty()) return g
    val closeMask = "*/"
    apply(working)
    return working
}
"""

# control: `.also` on the working copy is still the working copy.
B7_DELEGATE_COPY_ALSO = """package com.rikkaminis.app.config.collections

class GroupsCollection {
    private fun mutate(id: String, apply: (ModelGroup) -> Unit) {
        val g = group(id) ?: throw ConfigError.UnknownPath("groups.$id")
        repo.updateGroup(mutatedGroupCopy(g, apply))
    }
}

internal fun mutatedGroupCopy(g: ModelGroup, apply: (ModelGroup) -> Unit): ModelGroup {
    val working = g.copy(memberEntryIds = g.memberEntryIds.toMutableList()).also { log(it) }
    apply(working)
    return working
}
"""

# control: a persist call behind a live condition is still graded, and a
# compliant one stays green.
B7_FUNCTIONAL_GUARDED_OK = """package com.rikkaminis.app.config.collections

class ModelsCollection {
    private fun mutate(id: String, apply: (ModelEntry) -> ModelEntry) {
        val e = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        if (e.isHidden) repo.updateEntry(apply(e))
        else repo.updateEntry(apply(e))
    }
}
"""

# control: the dead-branch persist is ignored, the live one is compliant.
B7_MODELS_DEAD_BRANCH_OK = """package com.rikkaminis.app.config.collections

class ModelsCollection {
    private fun mutate(id: String, apply: (ModelEntry) -> ModelEntry) {
        val e = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        if (1 == 2) repo.updateEntry(e)
        repo.updateEntry(apply(e))
    }
}
"""


def run_fixture(name, content, expect_ok):
    with tempfile.TemporaryDirectory() as tmp:
        dst_dir = os.path.join(tmp, COLLECTIONS_DIR)
        os.makedirs(dst_dir)
        with open(os.path.join(dst_dir, "Fixture.kt"), "w") as f:
            f.write(content)
        v = check_tree(tmp)
        ok = not v
        status = "PASS" if ok == expect_ok else "FAIL"
        print(f"  [{status}] fixture {name}: violations={v}")
        return status == "PASS"


ARMS = [
    ("delegate compliant (GroupWriteRules shape)", FIX_DELEGATE_OK, True),
    ("delegate helper hollowed (apply(g); return g)", FIX_DELEGATE_HOLLOW, False),
    ("delegate copy without re-materialise", FIX_DELEGATE_NO_COPY_SHARING, False),
    ("in-place compliant (fixed Groups shape)", FIX_INPLACE_OK, True),
    ("in-place live-object (pre-fix ISSUE-04)", FIX_INPLACE_DIRTY, False),
    ("in-place copy AFTER apply", FIX_INPLACE_LATE_COPY, False),
    ("in-place persists live object despite copy", FIX_INPLACE_PERSIST_LIVE, False),
    ("functional compliant (Providers shape)", FIX_FUNCTIONAL_OK, True),
    ("functional ignoring lambda return", FIX_FUNCTIONAL_DIRTY, False),
    # ── wide-1024 witness arms ──
    ("helper also mutates + returns the LIVE object", FIX_DELEGATE_APPLIES_LIVE, False),
    ("functional persists apply(x).let { x } (return discarded)",
     FIX_FUNCTIONAL_DISCARDS_RETURN, False),
    ("scope: `internal fun mutate` mutating the live object", FIX_SCOPE_INTERNAL_LIVE, False),
    ("scope control: `internal fun mutate` stays clean", FIX_SCOPE_INTERNAL_OK, True),
    # ── batch7-t09 witness arms (each was green before this branch) ──
    ("13-1 lambda invoked via `apply.invoke(g)` + unreachable return",
     B7_DELEGATE_INVOKE, False),
    ("13-2 toMutableList() decoy outside the copy args", B7_DELEGATE_DECOY_COPY, False),
    ("13-3 compliant call hidden in a dead branch, live path mutates",
     B7_GROUP_DEAD_BRANCH, False),
    ("13-4 `apply(x.let { return })` exits before the write",
     B7_FUNCTIONAL_INNER_RETURN, False),
    ("13-5 dead branch occupies the first persist slot", B7_MODELS_DEAD_BRANCH, False),
    ("13-6 `\"/*\"` masking hides apply(g) + return g", B7_DELEGATE_STRING_MASK, False),
    # ── controls (must stay green) ──
    ("13-1c `.also` on the working copy is still the copy", B7_DELEGATE_COPY_ALSO, True),
    ("13-4c persist behind a live condition stays graded", B7_FUNCTIONAL_GUARDED_OK, True),
    ("13-5c dead-branch persist ignored, live persist compliant",
     B7_MODELS_DEAD_BRANCH_OK, True),
]


def selftest():
    print("collection_copy_on_mutate_check selftest:")
    ok = True
    for name, source, expect_ok in ARMS:
        ok &= run_fixture(name, source, expect_ok)
    print("%s (%d arms)" % (MARKER, len(ARMS)))
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    if len(sys.argv) < 2:
        print("usage: python3 collection_copy_on_mutate_check.py <repo_root>", file=sys.stderr)
        sys.exit(2)
    violations = check_tree(sys.argv[1])
    if violations:
        for v in violations:
            print("FAIL:", v)
        sys.exit(1)
    print("collection_copy_on_mutate_check: OK (all mutate sites persist a copy)")


if __name__ == "__main__":
    main()
