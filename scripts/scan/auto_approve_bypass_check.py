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

from _kotlin_ast import (const_bool, function_body, if_blocks, match_bracket,
                         split_top, stmt_spans, strip_parens, when_branches)
from _kotlin_lexer import KotlinLexError, analyse, mask_code
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

def _decision_consumers(body):
    """Reachable, non-constant places a value can be CONSUMED as the decision:
    `if (…)` conditions and `when (…)` subjects (nothing in them folds away)."""
    out = list(_live_conditions(body))
    for subject, _cond, b_start, _b_end in when_branches(body):
        if subject.strip() and const_bool(subject) is None \
                and path_feasible(body, b_start):
            out.append((subject, b_start))
    return out


def check_permission_consumption(manager_code):
    """C6 — [R3-t17] the DECISION must be the resolved level, and nothing else.

    Witness #3 (dual-gate with offload_permission_reset_sync_check, both rc=0):
    C3/C5 pin `getLevel`'s body and this gate's C4 pins the mode's config
    surface, while the CONSUMER — `checkPermission` — was nobody's business.
    `val level = if (AutoApproveStore.isEnabled) PermissionLevel.BYPASS else
    getLevel(toolName)` therefore left all five criteria green: the auto-approve
    overlay moved OUT of `OffloadPermissionLevelPolicy` (the one place it is
    allowed, where `privilegedToolsEnabled` gates it) into the decision, so a
    plain "免确认模式" silently bypassed a tool the user had set to NOT_ALLOWED.

    Three things are required of the consumer:
      a) it must not mention `AutoApproveStore` at all — every overlay belongs to
         the policy, which is where the privileged-tools sub-flag lives;
      b) the level it decides on must BE `getLevel(...)`'s value (all sources),
         not an expression that merely contains the call;
      c) nothing may decide (return / throw) before that level is resolved.
    """
    body = function_body(manager_code, "checkPermission")
    if body is None:
        return ["C6: checkPermission not found in OffloadPermissionManager — the "
                "consumer of getLevel() cannot be judged (fail-closed)"]
    text = manager_code[body[0]:body[1]]
    v = []

    for m in re.finditer(r"\bAutoApproveStore\b", text):
        line = manager_code.count("\n", 0, body[0] + m.start()) + 1
        v.append(
            "C6: checkPermission consults AutoApproveStore (line %d) — the auto-approve "
            "overlay must stay inside OffloadPermissionLevelPolicy, where the "
            "privileged-tools sub-flag is honoured; here it bypasses the user's stored "
            "NOT_ALLOWED / ASK_ONCE level" % line)

    spans = stmt_spans(text)
    binds = []
    for a, b in spans:
        stmt = text[a:b].strip()
        if "getLevel(" not in stmt:
            continue
        m = RE_LOCAL_BIND.match(stmt)
        if m is not None:
            rhs = stmt[stmt.index("=") + 1:]
            binds.append((a, m.group(1), rhs))
    if not binds:
        v.append(
            "C6: checkPermission does not bind `getLevel(...)` to a local — the "
            "decision no longer comes from the resolved level (anchor drift, or the "
            "level was inlined away)")
    consumers = _decision_consumers(text)
    for start, name, rhs in binds:
        if not value_flows_from(text, rhs, "getLevel(", require_all=True):
            v.append(
                "C6: `%s` is decided by `%s`, which is not the getLevel() verdict on "
                "every path — an overlay (auto-approve / a constant) replaces the "
                "resolved level for at least one branch" % (name, rhs.strip()[:70]))
            continue
        if not any(contains_ident(cond, name) for cond, _pos in consumers):
            v.append(
                "C6: `%s` (the resolved level) never reaches a reachable condition or "
                "`when` subject — the level is computed and then ignored" % name)
        for a, b in spans:
            if a >= start:
                break
            if re.search(r"\breturn\b|\bthrow\b", text[a:b]):
                v.append(
                    "C6: checkPermission decides (`%s`) BEFORE `%s` is resolved — an "
                    "early verdict skips the level entirely"
                    % (text[a:b].strip()[:60], name))
    return v


# ── C7: the risk tag is the auto-approve policy's INPUT ────────────────────
# [R3-t17] #4 · dual-gate with backup_remap_rollup_check (both rc=0). C4 only
# forbids the `auto-approve` NAME inside the registry; the field's `risk` tag —
# the thing ConfigBridge feeds into `ConfigAutoApprovePolicy.shouldSkip` — was
# never judged, so `defaults.voiceInputGroup` could be downgraded from SENSITIVE
# to NORMAL and rewritten by the agent with the ordinary (non-sensitive) auto-
# approve switch on, no dialog.
RISK_RANK = {"NORMAL": 0, "SENSITIVE": 1, "DESTRUCTIVE": 2}

# Frozen from the tree at R3-t17 (ConfigBuiltins.registerDefaults / the soul +
# permissions registrations). The direction that matters is DOWNWARD: adding a
# new SENSITIVE field is fine, silently re-classifying one of these as routine
# is the defect. `test_scan`-registered arms pin both directions.
PINNED_RISK = {
    "permissions.minisConfig.enabled": "DESTRUCTIVE",
    "session.primaryModel": "SENSITIVE",
    "envvars.privacyMode": "SENSITIVE",
    "defaults.primaryGroup": "SENSITIVE",
    "defaults.subGroup": "SENSITIVE",
    "defaults.voiceInputGroup": "SENSITIVE",
    "defaults.voiceOutputGroup": "SENSITIVE",
    "defaults.agentLoopEntries": "SENSITIVE",
    "defaults.agentLoopGroups": "SENSITIVE",
    "soul.name": "SENSITIVE",
    "soul.style": "SENSITIVE",
    "soul.lang": "SENSITIVE",
    "soul.body": "SENSITIVE",
}

# A registration whose writer rebinds WHICH model group a surface uses is a
# routing change: it must be SENSITIVE no matter what path it is registered
# under, or a NEW field of the same family starts out silently writable.
RE_GROUP_BINDING = re.compile(r"\brepo\s*\.\s*([A-Za-z_]\w*GroupId)\s*=(?!=)")


def literal_view(root, rel):
    """`rel` with comments blanked and string literals KEPT (length preserved).

    `path = "defaults.voiceInputGroup"` lives inside a literal, which
    `mask_code` blanks — the registry sweep needs the literal spelling; the
    offsets still line up with the code view, so both are read side by side.
    """
    src = read(os.path.join(root, rel))
    ok, reason, views = analyse(src)
    if not ok:
        raise LexFail("%s: Kotlin lexer refused the source (%s) — fail-closed, the "
                      "criterion cannot be judged" % (rel, reason))
    return views["comments"]


def registered_fields(lit, code):
    """[(path, risk_token, block_code)] for every `ClosureField(...)` registration."""
    out = []
    for m in re.finditer(r"\bClosureField\s*\(", lit):
        close = match_bracket(lit, m.end() - 1)
        if close < 0:
            out.append((None, None, ""))
            continue
        lit_blk = lit[m.end() - 1:close + 1]
        code_blk = code[m.end() - 1:close + 1]
        path_m = re.search(r'\bpath\s*=\s*"([^"]*)"', lit_blk)
        risk_m = re.search(r"\brisk\s*=\s*ConfigRisk\.([A-Za-z_]\w*)", code_blk)
        out.append((path_m.group(1) if path_m else None,
                    risk_m.group(1) if risk_m else None,
                    code_blk))
    return out


def check_risk_tags(lit, code):
    """C7 — a registered field's risk tag must not be silently weakened."""
    v = []
    problems = {}

    def note(key, msg):
        problems.setdefault(key, []).append(msg)

    fields = registered_fields(lit, code)
    seen = {}
    opaque = 0
    for path, risk, blk in fields:
        if path is None:
            # A FACTORY registration (`fontScaleField(path = path, …)`): the path
            # arrives from the caller, so the pin cannot be resolved here. The
            # path-independent rail (a model-group rebinding must be SENSITIVE)
            # still applies; the pin rail is registered as a residual.
            opaque += 1
            rank = RISK_RANK.get(risk if risk is not None else "", None)
            if RE_GROUP_BINDING.search(blk) and (rank is None or rank < RISK_RANK["SENSITIVE"]):
                note("<factory risk=%s>" % (risk or "none"),
                     "rebinds a model-group pointer (%s) yet is registered as %s"
                     % (RE_GROUP_BINDING.search(blk).group(1), risk or "no risk"))
            continue
        seen[path] = risk
        if risk is not None and risk not in RISK_RANK:
            note(path, "declares `ConfigRisk.%s`, which is not a risk level — an "
                       "unclassified field is treated as routine" % risk)
        rank = RISK_RANK.get(risk if risk is not None else "", None)
        pinned = PINNED_RISK.get(path)
        if pinned is not None:
            if rank is None:
                note(path, "declares no readable risk (pinned minimum %s)" % pinned)
            elif rank < RISK_RANK[pinned]:
                note(path, "is registered as %s but is pinned %s — the auto-approve "
                           "policy treats it as routine and rewrites it without the "
                           "dialog the user's setting asks for" % (risk, pinned))
        if RE_GROUP_BINDING.search(blk) and (rank is None or rank < RISK_RANK["SENSITIVE"]):
            note(path, "rebinds a model-group pointer (%s) yet is registered as %s — "
                       "every model-routing rebinding must be SENSITIVE"
                 % (RE_GROUP_BINDING.search(blk).group(1), risk or "no risk"))
    for path, pinned in sorted(PINNED_RISK.items()):
        if path not in seen:
            note(path, "is no longer registered at all (pinned %s) — the field either "
                       "vanished or was renamed without updating this rail" % pinned)
    for path in sorted(problems):
        v.append("C7: `%s` %s" % (path, "; ".join(problems[path])))
    check.opaque = opaque          # reported by main() as a [note], never a red
    return v



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

    # --- Criterion 6: the offload decision must be the RESOLVED level --------
    failures.extend(check_permission_consumption(manager))

    # --- Criterion 7: the risk tags the policy reads must not be weakened ----
    try:
        failures.extend(check_risk_tags(literal_view(root, CONFIG_BUILTINS),
                                        view(root, CONFIG_BUILTINS)))
    except LexFail as exc:
        failures.append("C7: %s" % exc)

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
    # -- [R3-t17] the dead-code-gaps between this gate and its neighbours ----
    # #3 dual-gate (×offload_permission_reset_sync_check): the overlay moves
    # from the policy into the CONSUMER.
    "checkperm_auto_approve_overlay": (
        PERM_MANAGER,
        [("        val level = getLevel(toolName)\n",
          "        val level = if (AutoApproveStore.isEnabled) PermissionLevel.BYPASS"
          " else getLevel(toolName)\n")],
    ),
    # #3 sibling: the level is resolved and then not used as the decision's value.
    "checkperm_level_discarded": (
        PERM_MANAGER,
        [("        val level = getLevel(toolName)\n        return when (level) {",
          "        val levelIgnored = PermissionLevel.BYPASS\n"
          "        val level = levelIgnored\n        return when (level) {")],
    ),
    # #4 dual-gate (×backup_remap_rollup_check): a SENSITIVE field downgraded to
    # the routine level, so the ordinary auto-approve switch covers it.
    "risk_voice_input_downgraded": (
        CONFIG_BUILTINS,
        [('                path = "defaults.voiceInputGroup",\n'
          '                displayName = "Default voice input group",\n'
          '                description = "Group that resolves the voice-INPUT model. '
          'Empty string clears the default.",\n'
          '                valueSchema = ConfigSchema.Str(maxLength = 200),\n'
          '                risk = ConfigRisk.SENSITIVE,\n',
          '                path = "defaults.voiceInputGroup",\n'
          '                displayName = "Default voice input group",\n'
          '                description = "Group that resolves the voice-INPUT model. '
          'Empty string clears the default.",\n'
          '                valueSchema = ConfigSchema.Str(maxLength = 200),\n'
          '                risk = ConfigRisk.NORMAL,\n')],
    ),
    # #4 as the external model literally wrote it: a token that is not a level.
    "risk_unknown_token": (
        CONFIG_BUILTINS,
        [('                path = "defaults.voiceInputGroup",\n'
          '                displayName = "Default voice input group",\n'
          '                description = "Group that resolves the voice-INPUT model. '
          'Empty string clears the default.",\n'
          '                valueSchema = ConfigSchema.Str(maxLength = 200),\n'
          '                risk = ConfigRisk.SENSITIVE,\n',
          '                path = "defaults.voiceInputGroup",\n'
          '                displayName = "Default voice input group",\n'
          '                description = "Group that resolves the voice-INPUT model. '
          'Empty string clears the default.",\n'
          '                valueSchema = ConfigSchema.Str(maxLength = 200),\n'
          '                risk = ConfigRisk.SAFE,\n')],
    ),
    # The pin: a registered path that quietly disappears (renamed).
    "risk_pinned_path_missing": (
        CONFIG_BUILTINS,
        [('                path = "defaults.subGroup",', '                path = "defaults.subGroupAlias",')],
    ),
    # A NEW member of the model-group family must not start out routine.
    "risk_new_group_family_safe": (
        CONFIG_BUILTINS,
        [('        r.register(\n            ClosureField(\n'
          '                path = "defaults.agentLoopEntries",',
          '        r.register(\n            ClosureField(\n'
          '                path = "defaults.fallbackGroup",\n'
          '                displayName = "Fallback group",\n'
          '                description = "probe",\n'
          '                valueSchema = ConfigSchema.Str(maxLength = 200),\n'
          '                risk = ConfigRisk.NORMAL,\n'
          '                revertable = true,\n'
          '                reader = { ConfigValue.Str(repo.fallbackGroupId ?: "") },\n'
          '                writer = { v -> repo.fallbackGroupId = (v as ConfigValue.Str).value },\n'
          '            )\n        )\n'
          '        r.register(\n            ClosureField(\n'
          '                path = "defaults.agentLoopEntries",')],
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
    "checkperm_auto_approve_overlay": "AUTO_APPROVE_BYPASS_SELFTEST checkperm_auto_approve_overlay arm: OK",
    "checkperm_level_discarded": "AUTO_APPROVE_BYPASS_SELFTEST checkperm_level_discarded arm: OK",
    "risk_voice_input_downgraded": "AUTO_APPROVE_BYPASS_SELFTEST risk_voice_input_downgraded arm: OK",
    "risk_unknown_token": "AUTO_APPROVE_BYPASS_SELFTEST risk_unknown_token arm: OK",
    "risk_pinned_path_missing": "AUTO_APPROVE_BYPASS_SELFTEST risk_pinned_path_missing arm: OK",
    "risk_new_group_family_safe": "AUTO_APPROVE_BYPASS_SELFTEST risk_new_group_family_safe arm: OK",
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
    # [primitive-round2 A2] `value_sources` 现在真的拆 if/else 分叉（此前那段
    # 分叉是死代码，返回整条表达式）。本臂的见证本身是错的（overlay 替代了
    # resolved level），因此两条 C6 判据**都**成立：①checkPermission 咨询了
    # AutoApproveStore；②`level` 不是每条路径上的 getLevel() 判决。
    # 同一个站点报两条 = 判据变准的直接后果，计数随之从 1 改 2。
    ("checkperm_auto_approve_overlay", 2),
    ("checkperm_level_discarded", 1),
    ("risk_voice_input_downgraded", 1),
    ("risk_unknown_token", 1),
    ("risk_pinned_path_missing", 1),
    ("risk_new_group_family_safe", 1),
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
    opaque = getattr(check, "opaque", 0)
    if opaque and not failures:
        print("  [note] C7: %d factory registration(s) carry their `path` from the "
              "caller — the pinned risk rail does not apply to them (see the C7 "
              "residual in the gate report)" % opaque)
    if failures:
        print("AUTO_APPROVE_BYPASS_CHECK: RED")
        for f in failures:
            print("  -", f)
        return 1
    print("AUTO_APPROVE_BYPASS_CHECK: OK (all 5 criteria hold)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
