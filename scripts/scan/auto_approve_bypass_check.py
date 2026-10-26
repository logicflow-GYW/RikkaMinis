#!/usr/bin/env python3
"""
auto_approve_bypass_check — [auto-approve] structural gate.

Guarantees the "auto-approve mode" overlays stay behind their designed single
points of decision, and only there:

  1. ConfigBridge.performWriteBatch must consult ConfigAutoApprovePolicy.
     (A future edit that removes the consult would silently re-ask for every
     write — or, worse, an edit that consults nothing and skips everything
     would silently apply SENSITIVE rows.)
  2. ConfigConfirmationGate.requestConfirmation must have EXACTLY one call
     site in production code (ConfigBridge). A second call site would be a
     dialog path the auto-approve policy never sees.
  3. OffloadPermissionManager.getLevel must resolve through
     OffloadPermissionLevelPolicy (the pure precedence table), not inline
     ad-hoc overlay logic — AND the resolved value must actually BE the
     return value (see C5).
  4. AutoApproveStore must NOT be reachable from the ConfigRegistry builtins:
     the mode is UI-only, the agent must never be able to flip it via
     minis-config (that would be self-authorization).
  5. [meta-audit-1024 FIX-M1, axis=dataflow] every
     `OffloadPermissionLevelPolicy.resolve(` call inside getLevel must be
     CONSUMED as the return value — either `return OffloadPermissionLevelPolicy
     .resolve(...)`, or bound to a local (`val lvl = …`) that a later
     `return <lvl>` hands back. A bare, discarded call followed by an
     unconditional `return <level>` keeps C3 green (the symbol is still in the
     body) while voiding every user setting: all offload tools would get that
     one level. That patch was externally witnessed passing this gate.

[batch7 B7-t04] Six externally-witnessed bypasses passed this gate; the shared
primitives (`_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`) are now the
only lexer/AST/value-flow used here, and each criterion was moved from
"the token is present" to "the token's EFFECT happens":

  * C1 (dataflow)  the shouldSkip verdict must be BOUND to a local that appears
                   in a reachable, non-constant condition. `val _ignored =
                   shouldSkip(...)` + `val autoSkip = !skipConfirmation` kept
                   the call in the body and skipped every dialog.
  * C2 (spelling)  call sites are derived from the CALL NAME, not from the
                   receiver spelling: `ConfigConfirmationGate\\n  .request
                   Confirmation(` (line-broken, or fully qualified) is the same
                   call. Only definitions are skipped.
  * C2 (order)     the single call must be REACHABLE and its value must be the
                   decision (`if (false) requestConfirmation(...)` +
                   `ConfirmOutcome.Approved(...)` kept the one call site and
                   skipped the dialog unconditionally).
  * C5 (dataflow)  the returned value must BE the policy value:
                   `return resolve(...).let { PermissionLevel.BYPASS }` kept the
                   `return` head and replaced the verdict.
  * C5 (order)     no live early return may jump over the policy call. The one
                   designed early exit (`?: return BYPASS` for an unknown tool)
                   is the only accepted form.
  * C4 (parser-death)  comments/strings come from `mask_code`, which understands
                   Kotlin raw strings (`\"\"\" \"/* \"\"\"` used to open a fake
                   block comment and hide live code from the registry sweep).

Fail-closed: a source the lexer refuses, or a call whose statement cannot be
attributed, is a violation — never a skip. Zero calls stays C3's business.

Run:  python3 scripts/scan/auto_approve_bypass_check.py <repo-root>
      python3 scripts/scan/auto_approve_bypass_check.py --selftest
Exit: 0 = all criteria hold, 1 = violation found (prints which).
"""
import os
import re
import shutil
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from _kotlin_ast import (const_bool, function_body, if_blocks, split_top,
                         stmt_spans, strip_parens, when_branches)
from _kotlin_lexer import KotlinLexError, mask_code
from _kotlin_valueflow import (brace_depth, contains_ident, expr_end,
                               path_feasible, value_sources)

CONFIG_BRIDGE = "src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBridge.kt"
PERM_MANAGER = "src/android/app/src/main/java/com/rikkaminis/app/offload/OffloadPermissionManager.kt"
CONFIG_REGISTRY = "src/android/app/src/main/java/com/rikkaminis/app/config/ConfigRegistry.kt"
CONFIG_BUILTINS = "src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt"

PROD_JAVA = "src/android/app/src/main/java"

POLICY_CALL = "OffloadPermissionLevelPolicy.resolve"
RE_POLICY_CALL = re.compile(re.escape(POLICY_CALL) + r"\s*\(")
# The consult is matched through the call name, so an added qualifier or a
# line break between receiver and name does not hide it.
RE_POLICY_CONSULT = re.compile(r"\bConfigAutoApprovePolicy\s*\.\s*shouldSkip\s*\(")
# Same reasoning for the confirmation gate: the CALL NAME is the site.
RE_CONFIRM_CALL = re.compile(r"\brequestConfirmation\s*\(")
# A statement head that CONSUMES the value.
RE_RETURN_HEAD = re.compile(r"^(?:return(?:@[A-Za-z_][A-Za-z0-9_]*)?|throw)\b")
# `val name = ` / `var name: Type = ` — the local-val spelling that is only
# acceptable when the value really reaches a condition / the returned value.
RE_LOCAL_BIND = re.compile(r"^(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]+)?=")


class LexFail(Exception):
    """The lexer refused the source → violation (fail-closed), never a fallback."""


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def code_of(src, label):
    """Structural view: comments + string/char literal INTERIORS blanked,
    length strictly preserved. Raises LexFail (never falls back to raw text)."""
    try:
        return mask_code(src)
    except KotlinLexError as exc:
        raise LexFail(
            "%s: Kotlin lexer refused the source (%s) — fail-closed, the "
            "criterion cannot be judged" % (label, exc))


def view(root, rel):
    return code_of(read(os.path.join(root, rel)), rel)


# --------------------------------------------------------------------------
# small shared helpers
# --------------------------------------------------------------------------

def _statement_at(text, spans, pos):
    for a, b in spans:
        if a <= pos < b:
            return a, b
    return None, None


def _ident_values(code, ident):
    """Every definition of `ident` that could supply its value: the RHS of
    `val/var ident = …` (all sites) and the body of a same-named function."""
    out = []
    for m in re.finditer(r"\b(?:val|var)\s+" + re.escape(ident) +
                         r"\s*(?::[^=\n]+?)?=\s*", code):
        out.append(code[m.end():expr_end(code, m.end())])
    body = function_body(code, ident)
    if body is not None:
        out.append(code[body[0]:body[1]])
    return out


def value_flows_from(code, expr, needle, depth=0, require_all=False):
    """Does [expr]'s evaluated value carry [needle]?

    The expression is run through the shared value-flow (`value_sources`), so a
    discarded scope wrapper (`.also{}`), a `let`/`run` tail, an elvis half and
    an if/else half each count as their REAL value, and a one-hop local alias /
    same-named helper is followed. `require_all=True` demands every possible
    source carry the needle — "the value IS it", not "the text contains it".
    """
    if depth > 3:
        return False
    seen = []
    for src in value_sources(expr):
        s = strip_parens(src.strip())
        if not s:
            continue
        if needle in s:
            seen.append(True)
            continue
        hit = False
        for ident in sorted(set(re.findall(r"\b([A-Za-z_][A-Za-z0-9_]*)\b", s))):
            for rhs in _ident_values(code, ident):
                if value_flows_from(code, rhs, needle, depth + 1, require_all):
                    hit = True
                    break
            if hit:
                break
        seen.append(hit)
    if not seen:
        return False
    return all(seen) if require_all else any(seen)


def _live_conditions(body):
    """Reachable, non-constant conditions of [body]: [(cond_text, pos)].

    A condition that folds to a constant (`if (true)`, `if (1 == 2)`) proves
    nothing about whether the value was consulted, so it is dropped.
    """
    out = []
    for cond, t_start, t_end, e_start, e_end in if_blocks(body):
        if const_bool(cond) is None and path_feasible(body, t_start):
            out.append((cond, t_start))
        if e_start is not None and const_bool("!" + cond) is None \
                and path_feasible(body, e_start):
            out.append(("!" + cond, e_start))
    for _subject, cond, b_start, b_end in when_branches(body):
        if cond.strip() in ("else", ""):
            continue
        if const_bool(cond) is None and path_feasible(body, b_start):
            out.append((cond, b_start))
    return out


# --------------------------------------------------------------------------
# criteria
# --------------------------------------------------------------------------

def check_policy_consult(batch_body):
    """C1 — the auto-approve verdict must DECIDE the confirmation.

    Presence of the call is not the contract (the external witness kept it and
    discarded the value). The verdict has to be bound to a local that appears
    in a reachable, non-constant condition of the same function.
    """
    calls = list(RE_POLICY_CONSULT.finditer(batch_body))
    if not calls:
        return [
            "C1: performWriteBatch no longer consults ConfigAutoApprovePolicy — "
            "the auto-approve mode would be dead or bypassed"
        ]
    spans = stmt_spans(batch_body)
    conds = _live_conditions(batch_body)
    v = []
    for m in calls:
        if not path_feasible(batch_body, m.start()):
            v.append(
                "C1: the ConfigAutoApprovePolicy.shouldSkip(...) consult sits on an "
                "unreachable path (constant-false guard) at offset %d — the "
                "auto-approve decision never runs" % m.start())
            continue
        a, b = _statement_at(batch_body, spans, m.start())
        stmt = (batch_body[a:b] if a is not None else "").strip()
        bind = RE_LOCAL_BIND.match(stmt)
        if bind is None:
            v.append(
                "C1: the ConfigAutoApprovePolicy.shouldSkip(...) verdict is not bound "
                "to a local that decides the confirmation (statement %r) — the policy "
                "is consulted and its answer thrown away" % stmt[:70])
            continue
        name = bind.group(1)
        if not any(contains_ident(cond, name) for cond, _pos in conds):
            v.append(
                "C1: '%s' (the ConfigAutoApprovePolicy.shouldSkip verdict) never "
                "reaches a reachable, non-constant condition — the confirm/skip "
                "outcome is decided without the policy" % name)
    return v


def confirmation_call_sites(root, prod_views):
    """Every `requestConfirmation(` CALL in production sources.

    Derived from the call name so a line break or a fully-qualified receiver is
    the same site; definitions (`fun requestConfirmation(`) are skipped.
    """
    sites = []
    for rel, code in prod_views:
        for m in RE_CONFIRM_CALL.finditer(code):
            if re.search(r"\bfun\s+$", code[max(0, m.start() - 12):m.start()]):
                continue
            sites.append((rel, m.start()))
    return sites


def check_confirmation_decision(batch_body, pos):
    """C2 — the ONE call site must be live AND be the decision's value.

    `if (false) requestConfirmation(pending)` followed by an unconditional
    `ConfirmOutcome.Approved(...)` keeps the single call site and never asks.
    """
    v = []
    if not path_feasible(batch_body, pos):
        return [
            "C2: the single ConfigConfirmationGate.requestConfirmation(...) call sits "
            "on an unreachable path (constant-false guard) — the dialog is skipped "
            "while the call site still exists"
        ]
    spans = stmt_spans(batch_body)
    a, b = _statement_at(batch_body, spans, pos)
    stmt = (batch_body[a:b] if a is not None else "").strip()
    if RE_RETURN_HEAD.match(stmt):
        return v
    after = batch_body[b:].lstrip() if b is not None else ""
    if after.startswith("}") or after.startswith("else"):
        return v  # tail expression of the block / branch == the value
    bind = RE_LOCAL_BIND.match(stmt)
    if bind is not None:
        name = re.escape(bind.group(1))
        rest = batch_body[b:]
        if re.search(r"\breturn\b[^\n]*\b%s\b" % name, rest) or \
                re.search(r"\b%s\b\s*->" % name, rest):
            return v
    v.append(
        "C2: the single requestConfirmation(...) call is not what decides the "
        "outcome (statement %r) — a discarded call plus an unconditional outcome "
        "approves every SENSITIVE/DESTRUCTIVE write without a dialog" % stmt[:70])
    return v


def check_policy_return(level_body):
    """C5 — the returned value must BE the policy value.

    `return resolve(...).let { PermissionLevel.BYPASS }` keeps the `return`
    head, so the head alone proves nothing: the value is traced through the
    shared value-flow, and EVERY possible source has to be the call itself.
    """
    calls = [m.start() for m in RE_POLICY_CALL.finditer(level_body)]
    if not calls:
        return []  # C3's criterion (presence) — do not double-count
    spans = stmt_spans(level_body)
    v = []
    for idx in calls:
        a, b = _statement_at(level_body, spans, idx)
        stmt = (level_body[a:b] if a is not None else "").strip()
        m = re.match(r"return\b\s*(.*)$", stmt, re.S)
        if m is not None:
            expr = m.group(1).strip()
            if not expr:
                v.append(
                    "C5: getLevel returns nothing while the policy verdict is computed "
                    "(statement %r) — the resolved level never leaves the function"
                    % stmt[:70])
            elif not value_flows_from(level_body, expr, POLICY_CALL, require_all=True):
                v.append(
                    "C5: getLevel returns an expression that is not the policy verdict "
                    "(%r) — a scope wrapper (`.let { PermissionLevel.BYPASS }`) or an "
                    "elvis on the call replaces the resolved level, so every stored "
                    "level / NOT_ALLOWED ban is void" % expr[:70])
            continue
        # Accepted other spelling: bind, then hand the SAME local back.
        bind = RE_LOCAL_BIND.match(stmt)
        if bind is not None:
            name = re.escape(bind.group(1))
            if re.search(r"\breturn\b[^\n]*\b%s\b" % name, level_body[b:]):
                continue
        v.append(
            "C5: getLevel discards the OffloadPermissionLevelPolicy.resolve() result "
            "(statement %r) — the policy value must BE the returned value, or a "
            "local val that a later `return` hands back; a discarded call lets an "
            "unconditional level override every user setting" % stmt[:70])
    return v


def check_policy_reachable(level_body):
    """C5 — no live early return may jump over the policy call.

    The one designed early exit is the registry-lookup fallback
    (`?: return PermissionLevel.BYPASS` for an unknown tool). Any OTHER live
    return above the call makes the policy dead code.
    """
    first = RE_POLICY_CALL.search(level_body)
    if first is None:
        return []
    # Cut at the STATEMENT that contains the call — its own `return` head sits
    # before the call text and must not count as "an early exit above it".
    a, _b = _statement_at(level_body, stmt_spans(level_body), first.start())
    cutoff = a if a is not None else first.start()
    v = []
    for m in re.finditer(r"\breturn\b(@\s*[A-Za-z_]\w*)?", level_body[:cutoff]):
        if m.group(1):
            continue  # `return@label` leaves a lambda, not getLevel
        pos = m.start()
        if brace_depth(level_body, pos) != 0:
            continue  # nested lambda / block body
        if not path_feasible(level_body, pos):
            continue
        if re.search(r"\?\:\s*$", level_body[max(0, pos - 24):pos]):
            continue  # designed lookup fallback
        line = level_body.count("\n", 0, pos) + 1
        v.append(
            "C5: getLevel returns before reaching OffloadPermissionLevelPolicy"
            ".resolve(...) (return at body line %d: %r) — an early exit above the "
            "policy call voids every user setting for every offload tool"
            % (line, level_body[max(0, pos - 20):pos + 40].split("\n")[0][:70]))
    return v


def prod_files(root):
    base = os.path.join(root, PROD_JAVA)
    hits = []
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if name.endswith(".kt"):
                hits.append(os.path.join(dirpath, name))
    return hits


def prod_views(root):
    """[(rel, code_view)] for every production .kt — one lex per file, reused by
    the C2 call-site sweep and the C4b registry sweep."""
    out = []
    for path in prod_files(root):
        rel = os.path.relpath(path, root).replace(os.sep, "/")
        out.append((rel, code_of(read(path), rel)))
    return out


def check(root):
    try:
        return _check(root)
    except LexFail as exc:
        return [str(exc)]


def _check(root):
    failures = []

    bridge = view(root, CONFIG_BRIDGE)
    manager = view(root, PERM_MANAGER)
    try:
        views = prod_views(root)
    except LexFail as exc:
        failures.append("C2/C4b: %s" % exc)
        views = [(CONFIG_BRIDGE, bridge)]

    # --- Criterion 1: the write path must CONSULT + CONSUME the policy ------
    batch = function_body(bridge, "performWriteBatch")
    batch_body = None
    if batch is None:
        failures.append("C1: performWriteBatch not found in ConfigBridge")
    else:
        batch_body = bridge[batch[0]:batch[1]]
        failures.extend(check_policy_consult(batch_body))

    # --- Criterion 2: the confirmation gate keeps exactly one call site ------
    sites = confirmation_call_sites(root, views)
    rels = sorted({rel for rel, _pos in sites})
    if len(sites) != 1 or rels != [CONFIG_BRIDGE]:
        failures.append(
            "C2: ConfigConfirmationGate.requestConfirmation call sites = "
            f"{[(r, 0) for r in rels] or []}, expected exactly "
            "[config/ConfigBridge.kt] (a second call site is a dialog path the "
            "auto-approve policy never sees)"
        )
    elif batch_body is not None:
        # Criterion 2b: that one call must be live and BE the decision.
        failures.extend(
            check_confirmation_decision(batch_body, sites[0][1] - batch[0]))

    # --- Criterion 3: getLevel must resolve through the pure policy ----------
    level = function_body(manager, "getLevel")
    if level is None:
        failures.append("C3: getLevel not found in OffloadPermissionManager")
    else:
        level_body = manager[level[0]:level[1]]
        if POLICY_CALL not in level_body:
            failures.append(
                "C3: getLevel no longer resolves through OffloadPermissionLevelPolicy — "
                "auto-approve overlays would be ad-hoc and untestable"
            )
        else:
            # --- Criterion 5: the resolved level must be what getLevel returns ---
            failures.extend(check_policy_return(level_body))
            failures.extend(check_policy_reachable(level_body))

    # --- Criterion 4: the mode must be invisible to the agent's config surface
    for name, path in (("ConfigRegistry", CONFIG_REGISTRY), ("ConfigBuiltins", CONFIG_BUILTINS)):
        src = view(root, path)
        if re.search(r"auto[_-]?approve", src, re.IGNORECASE):
            failures.append(
                f"C4: {name} references auto-approve — the mode must not be "
                "registered/exposed via minis-config (self-authorization)"
            )
    # Also: the store must not register itself anywhere under config/ registry
    # surface (belt for the suspenders above).
    for rel, src in views:
        if "config" not in rel:
            continue
        if rel.endswith(("AutoApproveStore.kt",)):
            continue
        if "registerHidden" in src and re.search(r"auto[_-]?approve", src, re.IGNORECASE):
            failures.append(f"C4b: hidden registration of auto-approve found in {rel}")

    return failures


# ---------------------------------------------------------------------------
# Self-test: mutation arms. Each arm breaks one criterion on a temp copy of the
# tree; the gate must flip red for exactly that criterion.
# ---------------------------------------------------------------------------

ARMS = {
    "compliant": None,
    "policy_consult_removed": (
        CONFIG_BRIDGE,
        [("ConfigAutoApprovePolicy.shouldSkip(", "false || ConfigAutoApprovePolicy.__gone(")],
    ),
    "second_call_site": (
        "src/android/app/src/main/java/com/rikkaminis/app/debug/DebugRPCHandler.kt",
        [("skipConfirmation = true,", "skipConfirmation = true; ConfigConfirmationGate.requestConfirmation(null)")],
    ),
    "getlevel_inlined": (
        PERM_MANAGER,
        [("OffloadPermissionLevelPolicy.resolve", "__inlined_resolve")],
    ),
    "registry_exposure": (
        CONFIG_BUILTINS,
        [("internal object ConfigBuiltins {", "internal object ConfigBuiltins {\n    private val autoApproveProbe = 0")],
    ),
    # meta-audit-1024 FIX-M1 — the exact external witness: the resolve call is
    # KEPT (so C3's symbol check stays green), but its value is dropped and an
    # unconditional level is returned ⇒ every user setting (NOT_ALLOWED /
    # ASK_ONCE) is void for all offload tools.
    "getlevel_result_discarded": (
        PERM_MANAGER,
        [
            ("        return OffloadPermissionLevelPolicy.resolve(",
             "        OffloadPermissionLevelPolicy.resolve("),
            ("        )\n    }\n\n    fun setLevel",
             "        )\n        return PermissionLevel.BYPASS\n    }\n\n    fun setLevel"),
        ],
    ),
    # Precision arm: binding the value and returning it later is the other
    # accepted spelling (must stay green — the criterion is about consumption,
    # not about one syntactic form).
    "getlevel_via_local_val": (
        PERM_MANAGER,
        [
            ("        return OffloadPermissionLevelPolicy.resolve(",
             "        val resolvedLevel = OffloadPermissionLevelPolicy.resolve("),
            ("        )\n    }\n\n    fun setLevel",
             "        )\n        return resolvedLevel\n    }\n\n    fun setLevel"),
        ],
    ),
    # Regression arm for a false GREEN found by the fixer's own probe: a
    # discarded resolve() followed by `return <other local>` must be RED.
    "getlevel_discard_return_other_val": (
        PERM_MANAGER,
        [
            ("        return OffloadPermissionLevelPolicy.resolve(",
             "        OffloadPermissionLevelPolicy.resolve("),
            ("        )\n    }\n\n    fun setLevel",
             "        )\n        return stored ?: info.defaultLevel\n    }\n\n    fun setLevel"),
        ],
    ),
    # -- [batch7 B7-t04] the six externally-witnessed bypasses ---------------
    # 02-1 dataflow: the verdict is consulted and thrown away.
    "policy_consult_discarded": (
        CONFIG_BRIDGE,
        [(
            """        val autoSkip = !skipConfirmation &&
            ConfigAutoApprovePolicy.shouldSkip(
                risks = resolvedRisks,
                autoApproveEnabled = AutoApproveStore.isEnabled,
                sensitiveAutoApproveEnabled = AutoApproveStore.isSensitiveEnabled,
            )""",
            """        val _ignoredPolicy = ConfigAutoApprovePolicy.shouldSkip(
            risks = resolvedRisks,
            autoApproveEnabled = AutoApproveStore.isEnabled,
            sensitiveAutoApproveEnabled = AutoApproveStore.isSensitiveEnabled,
        )
        val autoSkip = !skipConfirmation""",
        )],
    ),
    # 02-2 spelling: the same call, written across a line break + fully qualified.
    "second_call_site_multiline": (
        "src/android/app/src/main/java/com/rikkaminis/app/debug/DebugRPCHandler.kt",
        [(
            "skipConfirmation = true,",
            "skipConfirmation = com.rikkaminis.app.config.confirm.ConfigConfirmationGate\n"
            "                            .requestConfirmation(null) != null,",
        )],
    ),
    # 02-3 dataflow: the verdict is replaced by a scope wrapper on the return.
    "getlevel_let_bypass": (
        PERM_MANAGER,
        [(
            """            privilegedToolsEnabled = AutoApproveStore.isPrivilegedToolsEnabled,
        )
    }""",
            """            privilegedToolsEnabled = AutoApproveStore.isPrivilegedToolsEnabled,
        ).let { PermissionLevel.BYPASS }
    }""",
        )],
    ),
    # 02-4 order: a live early return jumps over the policy call.
    "getlevel_early_return": (
        PERM_MANAGER,
        [(
            """        val stored = prefs.getString("level_$toolName", null)?.let {""",
            """        if (toolName.isNotEmpty()) return PermissionLevel.BYPASS
        val stored = prefs.getString("level_$toolName", null)?.let {""",
        )],
    ),
    # 02-5 parser-death: a raw string opens a fake block comment and hides live
    # registry code from the sweep.
    "raw_string_comment_hole": (
        CONFIG_BUILTINS,
        [(
            "internal object ConfigBuiltins {",
            'internal object ConfigBuiltins {\n'
            '    private val stealthOpen = """ "/* """\n'
            '    val autoApproveBackdoor: Boolean get() = AutoApproveStore.isEnabled\n'
            '    private val stealthClose = """ */" """',
        )],
    ),
    # 02-6 family-hole: the single call site stays, inside a dead branch.
    "confirm_call_dead_branch": (
        CONFIG_BRIDGE,
        [(
            """                ConfigConfirmationGate.requestConfirmation(pending)""",
            """                if (false) ConfigConfirmationGate.requestConfirmation(pending)
                ConfirmOutcome.Approved(resolvedItems)""",
        )],
    ),
}

SELFTEST_TAGS = {
    "compliant": "AUTO_APPROVE_BYPASS_SELFTEST compliant arm: OK",
    "policy_consult_removed": "AUTO_APPROVE_BYPASS_SELFTEST policy_consult_removed arm: OK",
    "second_call_site": "AUTO_APPROVE_BYPASS_SELFTEST second_call_site arm: OK",
    "getlevel_inlined": "AUTO_APPROVE_BYPASS_SELFTEST getlevel_inlined arm: OK",
    "registry_exposure": "AUTO_APPROVE_BYPASS_SELFTEST registry_exposure arm: OK",
    "getlevel_result_discarded": "AUTO_APPROVE_BYPASS_SELFTEST getlevel_result_discarded arm: OK",
    "getlevel_via_local_val": "AUTO_APPROVE_BYPASS_SELFTEST getlevel_via_local_val arm: OK",
    "getlevel_discard_return_other_val": "AUTO_APPROVE_BYPASS_SELFTEST getlevel_discard_return_other_val arm: OK",
    "policy_consult_discarded": "AUTO_APPROVE_BYPASS_SELFTEST policy_consult_discarded arm: OK",
    "second_call_site_multiline": "AUTO_APPROVE_BYPASS_SELFTEST second_call_site_multiline arm: OK",
    "getlevel_let_bypass": "AUTO_APPROVE_BYPASS_SELFTEST getlevel_let_bypass arm: OK",
    "getlevel_early_return": "AUTO_APPROVE_BYPASS_SELFTEST getlevel_early_return arm: OK",
    "raw_string_comment_hole": "AUTO_APPROVE_BYPASS_SELFTEST raw_string_comment_hole arm: OK",
    "confirm_call_dead_branch": "AUTO_APPROVE_BYPASS_SELFTEST confirm_call_dead_branch arm: OK",
}

# arm name -> expected violation count (exact). 0 = accepted spelling.
ARM_EXPECT = [
    ("compliant", 0),
    ("policy_consult_removed", 1),
    ("second_call_site", 1),
    ("getlevel_inlined", 1),
    ("registry_exposure", 1),
    ("getlevel_result_discarded", 1),
    ("getlevel_via_local_val", 0),
    ("getlevel_discard_return_other_val", 1),
    ("policy_consult_discarded", 1),
    ("second_call_site_multiline", 1),
    ("getlevel_let_bypass", 1),
    ("getlevel_early_return", 1),
    ("raw_string_comment_hole", 1),
    ("confirm_call_dead_branch", 1),
]


def run_arm(root, arm_name):
    edits = ARMS[arm_name]
    if edits is None:
        return check(root)
    path, replacements = edits
    src_path = os.path.join(root, path)
    original = read(src_path)
    tmp_path = src_path + ".mutated"
    mutated = original
    for old, new in replacements:
        if old not in mutated:
            return ["arm %s: anchor %r not found" % (arm_name, old)]
        mutated = mutated.replace(old, new, 1)
    try:
        with open(tmp_path, "w", encoding="utf-8") as f:
            f.write(mutated)
        os.replace(tmp_path, src_path)
        return check(root)
    finally:
        with open(src_path, "w", encoding="utf-8") as f:
            f.write(original)


def selftest(root):
    tmp = tempfile.mkdtemp(prefix="auto_approve_gate_")
    try:
        # Copy the whole tree: check() reads the two focal files, the config
        # registry pair, and walks every production .kt for the call-site sweep.
        shutil.copytree(root, tmp, ignore=shutil.ignore_patterns(".git", "build", ".gradle"),
                        dirs_exist_ok=True)
        ok = True
        for arm_name, expected in ARM_EXPECT:
            failures = run_arm(tmp, arm_name)
            tag = SELFTEST_TAGS[arm_name]
            if len(failures) != expected:
                print(f"{tag} — FAIL: expected {expected} violation(s), got "
                      f"{len(failures)}: {failures}")
                ok = False
            elif failures:
                print(f"{tag} ({len(failures)} violation, red as designed)")
            else:
                print(f"{tag} (0 violations — accepted spelling)")
        if not ok:
            print("AUTO_APPROVE_BYPASS_SELFTEST: FAIL")
            return 1
        print("AUTO_APPROVE_BYPASS_SELFTEST: PASS (%d arms, %d violating)"
              % (len(ARM_EXPECT), sum(1 for _n, e in ARM_EXPECT if e)))
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    root = args[0] if args else "."
    if "--selftest" in sys.argv[1:]:
        return selftest(root)

    failures = check(root)
    if failures:
        print("AUTO_APPROVE_BYPASS_CHECK: RED")
        for f in failures:
            print("  -", f)
        return 1
    print("AUTO_APPROVE_BYPASS_CHECK: OK (all 5 criteria hold)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
