#!/usr/bin/env python3
"""
Gate: model-entry composite-key guard + canonical id return
(deep-audit-1023 ISSUE-03, branch fix/da1023-entry-key-provider-parity).

Two config entries with the same (providerInstanceId, baseModel.id) collapse
to ONE composite id at the persistence boundary: ProviderConfigMapping writes
`idMap[entry.uuid] = compositeEntryKey(instanceId, modelId)` and the load side
restores `uuid = row.id`. Two such rows therefore share a primary key —
LazyColumn `key = entry.id` throws "Key was already used", and one of the pair
silently vanishes on the next canonicalize round-trip.

The pre-fix code guarded only API-reported entries (`if (!entry.isCustom)`);
custom entries (AddCustomModelScreen / provider.models.add) could mint the
collision. This gate pins the fixed shape:

  Arm 1  addEntry carries the unified guard (decideEntryAdd) and its
         `!entry.isCustom` branch is GONE (the guard is not type-conditional).
  Arm 2  the pure decision helpers exist (same file, JVM-testable).
  Arm 3  ModelsCollection.add returns the canonical persisted id.
  Arm 4  provider.models.add pre-checks duplicates and reports the persisted
         entry (no pre-persist uuid).
  Arm 5  provider.models.update reports the persisted entry (fail-closed on a
         rejected rename instead of claiming success).

Arm 6  ConfigRegistry.resolveField resolves a collection child WITHOUT
       counting dots — the composite id itself contains dots
       (`<instanceId>/gpt-5.5`), so a `split('.', limit = N)` boundary cuts
       every dotted id and answers `unknown_path` for all of its children
       (deep-audit-1041 A-01).
Arm 7  the boundary is delegated to the collection's own id table
       (`childIds()`) — the only authority that knows where an id ends.
Arm 8  no per-child materialisation: a field list must not be built for
       every child id on each lookup.
Arm 9  a near match is a miss: the child field is confirmed against the
       requested path before being returned.

Arms 6–9 share one anchor: the resolver region. It follows the delegation
from `ConfigRegistry.resolveField` into the pure top-level function that
owns the grammar, wherever the KDoc/comments sit (block comments are
stripped first — the old shape is allowed to appear in prose).

Arm 10 [meta-audit-1028 W-B00-08] the QUARANTINE delete-set. An entry row that
       failed to parse is deliberately skipped on load and remembered in
       `quarantinedEntryRowIds` — the DAO's delete-set is derived from the
       entries that are PRESENT, so the skipped row is physically deleted by
       the next `syncAllIncremental` unless those ids are handed over. The
       gate reads the value the save path actually passes (named OR positional,
       parameter order derived from the DAO, not hardcoded) and rejects:
         a) no `preserveEntryIds` argument at all  → the DAO default (`emptySet()`)
            is silent, so an unrelated rename / reorder / refresh deletes the row;
         b) a constant-empty collection literal (`emptySet()`, `setOf()`,
            `mutableSetOf<String>()`, `listOf()` …) — the audited witness;
         c) an id set that is NEVER fed a non-empty value anywhere in the file
            (declared `emptySet()` and never assigned) — same defect one level
            up: every save passes an empty set.
       Any other spelling (member property, local val, call result, a chain) is
       accepted: the criterion is the VALUE, not the token.

Usage: python3 scripts/scan/entry_composite_key_guard_check.py [root]
       python3 scripts/scan/entry_composite_key_guard_check.py --root <root>
       python3 scripts/scan/entry_composite_key_guard_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError          # noqa: E402
from _kotlin_lexer import mask_comments           # noqa: E402  (comments out, literals kept)
from _kotlin_ast import (                         # noqa: E402
    if_blocks,
    match_bracket,
    split_top,
    strip_parens,
)
from _kotlin_valueflow import (                   # noqa: E402
    call_args,
    expr_end,
    guard_feasible,
    live_positions,
    path_feasible,
    value_sources,
)

APP = "src/android/app/src/main/java/com/rikkaminis/app"
PR_REL = APP + "/data/repository/ProviderRepository.kt"
MC_REL = APP + "/config/collections/ModelsCollection.kt"
PM_REL = APP + "/debug/ProviderMutationMethods.kt"
REG_REL = APP + "/config/ConfigRegistry.kt"
# Arm 10 — the DAO owns the parameter ORDER (positional call sites are legal).
DAO_REL = APP + "/data/db/ProviderConfigDao.kt"

# Arm 1 — unified guard wiring inside addEntry.
ARM1_ANCHORS = (
    "decideEntryAdd(",
    "decision.persistedId",
    "?: compositeEntryKey(",
)
# The type-conditional guard is the pre-fix regression, in its textual form.
ARM1_FORBIDDEN = ("!entry.isCustom",)

# Arm 2 — pure decision helpers must exist (top-level, same file).
ARM2_ANCHORS = (
    "internal fun decideEntryAdd(",
    "internal fun decideCompactPinMigration(",
    "internal fun decideCompactPinCleanup(",
)

# Arm 3 — ModelsCollection.add must return the persisted id, keeping the
# agent-facing AlreadyExists pre-check (the typed error surface).
ARM3_ANCHORS = (
    "return repo.addEntry(",
    "ConfigError.AlreadyExists",
)

# Arm 4 — modelsAdd RPC: duplicate pre-check + persisted-entry response.
ARM4_ANCHORS = (
    "already exists",
    "persistedId",
    "RPCException",
)

# Arm 5 — modelsUpdate RPC: canonical id, fail-closed on rejection.
ARM5_ANCHORS = (
    "repo.updateEntry(updated)",
    "persistedId",
    "?: throw RPCException",
)


def code_only(text):
    """Length-preserving code view from the shared lexer (`mask_comments`).

    Comments are blanked (their newlines kept, so offsets still map to real
    line numbers) and string/char literal interiors are KEPT — the
    dot-truncation patterns below need the literal `'.'` text, and the fix's
    own KDoc quotes the pre-fix idiom (`split('.', limit = 3)`), which a gate
    that read prose would red on. The hand-written comment scanner this
    replaces only knew `/* … */` and `//`; the shared lexer also handles
    nesting, char literals, triple-quoted strings and `${}` templates.
    """
    return mask_comments(text)


def function_text(src, header):
    """`header` … through the end of its brace body (shared bracket matcher)."""
    i = src.find(header)
    if i < 0:
        return None
    brace = src.find("{", i)
    if brace < 0:
        return None
    close = match_bracket(src, brace)
    return None if close < 0 else src[i:close + 1]


def _decl_body(text, m):
    """(body_start, body_text) of the `fun` whose regex match is [m], or None.

    Handles both body forms: `{ … }` (block, shared bracket matcher) and
    `= <expr>` (expression body, from the `=` up to the first following
    non-blank line indented at or below the `fun`'s own indentation, or a
    depth-0 non-continued newline). After the fix the two routers in
    ConfigRegistry are exactly one of each, so a matcher that only understood
    block bodies would silently drop the delegating half.
    """
    indent = len(m.group(1).expandtabs(4))
    params_end = match_bracket(text, m.end() - 1)
    if params_end < 0:
        return None
    i = params_end + 1
    while i < len(text) and text[i] in " \t":
        i += 1
    if i < len(text) and text[i] == ":":
        while i < len(text) and text[i] not in "{=":
            i += 1
    if i >= len(text):
        return None
    if text[i] == "{":
        close = match_bracket(text, i)
        if close < 0:
            return None
        return (i, text[i:close + 1])
    if text[i] != "=":
        return None
    pos = i
    while True:
        nl = text.find("\n", pos)
        if nl < 0:
            return (i, text[i:])
        j = nl + 1
        while j < len(text) and text[j] in " \t":
            j += 1
        if j >= len(text):
            return (i, text[i:])
        if text[j] == "\n":
            pos = j
            continue
        if len(text[nl + 1:j].expandtabs(4)) <= indent:
            return (i, text[i:nl])
        pos = j
    return None


def kotlin_functions(text):
    """Yield (name, signature, body) for every `fun` in [text].

    Both body forms are understood (see `_decl_body`): after the fix the two
    routers in ConfigRegistry are exactly one of each, so a matcher that only
    understood block bodies would silently drop the delegating half.
    """
    out = []
    pat = re.compile(
        r"^([ \t]*)(?:@\w+\s+)?"
        r"(?:(?:private|internal|public|protected|override|suspend|inline|open|final)\s+)*"
        r"fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(", re.M)
    for m in pat.finditer(text):
        body = _decl_body(text, m)
        if body is None:
            continue
        # the signature must include the return type: callers filter on it
        out.append((m.group(2), text[m.start():body[0]], body[1]))
    return out


def path_router_bodies(src):
    """Comment-stripped bodies of every function that routes a dot-path to a
    `ConfigField?` (i.e. takes `path: String`, returns `ConfigField?`).

    The rule under test is a DECISION, not a spelling: whichever function owns
    "where does the child id end" must be inside this set. Renaming or
    splitting the resolver across two functions keeps the union intact; only
    deleting the decision (no such function at all) makes the set empty, which
    the caller reports as gate ROT.

    Returns [(body_code, src_offset)] — `code_only` preserves character offsets,
    so an offset inside a body maps straight to a line in the original file and
    findings stay clickable.
    """
    code = code_only(src)
    out = []
    for _name, sig, body in kotlin_functions(code):
        if "path: String" in sig and "ConfigField?" in sig:
            body_code = code_only(body)
            out.append((body_code, code.find(body_code)))
    return out


def line_of(src, idx):
    return src.count("\n", 0, idx) + 1


# ── value-flow helpers (shared primitives) ──────────────────────────────────
def _init_expr(code, start):
    """The initialiser expression that begins at [start] (spans continuations)."""
    return code[start:expr_end(code, start)]


def _binding_expr(code, name):
    """RHS of the first `val|var name =` in [code] (spans continuation lines)."""
    m = re.search(r"\b(?:val|var)\s+%s\s*(?::[^=\n]*)?=" % re.escape(name), code)
    if not m:
        return None
    return _init_expr(code, m.end())


def _traces_to(code, expr, pattern, depth=2):
    """Does the VALUE of [expr] trace (through local val bindings) to something
    matching [pattern]?

    Reads the expression as dataflow (`value_sources`): elvis / if-else arms
    both count, and a discarding scope (`.also {}` / `.apply {}`) keeps its
    RECEIVER — so a resolver call glued onto a raw value inside `.also {}` does
    not make the value clean, and a bare identifier is followed to its binding.
    """
    for s in value_sources(expr):
        if re.search(pattern, s):
            return True
        t = strip_parens(s.strip())
        if depth and re.fullmatch(r"[A-Za-z_]\w*", t):
            rhs = _binding_expr(code, t)
            if rhs is not None and _traces_to(code, rhs, pattern, depth - 1):
                return True
    return False



def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def check_anchors(src, rel, header, arm, anchors, what):
    if header is None:
        code = code_only(src)
        base = 0
    else:
        body = function_text(src, header)
        if body is None:
            return [(rel, 0, arm, header + " not found — gate ROT")]
        code = code_only(body)
        base = src.find(header)
    out = []
    for anchor in anchors:
        if anchor not in code:
            out.append((rel, line_of(src, base) if base >= 0 else 0, arm,
                        "%s is missing required code anchor `%s`" % (what, anchor)))
    return out


def check_absent(src, rel, header, arm, forbidden, what):
    body = function_text(src, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    code = code_only(body)
    base = src.find(header)
    out = []
    for token in forbidden:
        if token in code:
            out.append((rel, line_of(src, base), arm,
                        "%s must not be type-conditional: found `%s`" % (what, token)))
    return out


def check_arm1_guard_live(src, rel, header, what):
    """Arm 1 — the dedupe guard must be a LIVE decision.

    `if (false && !decision.allowed)` keeps every anchor of the guard
    (`decideEntryAdd(`, `decision.persistedId`, `?: compositeEntryKey(`) and the
    forbidden `!entry.isCustom` stays away, yet the duplicate check can never
    fire. So the conditional that returns the duplicate's persisted id is read
    as CONTROL FLOW: at least one such guard must exist, and its condition must
    be feasible (`guard_feasible`, i.e. the shared constant folder over `&&` /
    `||` / `!` / int comparisons / null tests).
    """
    body = function_text(src, header)
    if body is None:
        return [(rel, 0, "1", header + " not found — gate ROT")]
    code = code_only(body)
    base = src.find(header)
    out = []
    seen = False
    for cond, t_start, t_end, _e_start, _e_end in if_blocks(code):
        if "persistedId" not in code[t_start:t_end]:
            continue
        seen = True
        if not guard_feasible(cond):
            out.append((rel, line_of(src, base + t_start), "1",
                        "%s: the duplicate guard is DEAD (`%s` can never be true) — a repeat add "
                        "falls through to the write and collides on the composite key"
                        % (what, " ".join(cond.split()))))
    if not seen:
        out.append((rel, line_of(src, base) if base >= 0 else 0, "1",
                    "%s: no conditional returns the duplicate's persisted id — the dedupe guard "
                    "is gone (fail-closed)" % what))
    return out


def check_decide_entry_add(src, rel):
    """Arm 2 — the pure helper must actually DECIDE.

    `check_anchors` only proves the signature `internal fun decideEntryAdd(`
    exists; a body that returns `EntryAddDecision(true, …)` unconditionally
    satisfies every anchor while minting the collision. The body is therefore
    read on its own: it must query the existing table, and it must return a
    NEGATIVE decision on a reachable path.
    """
    header = "internal fun decideEntryAdd("
    body = function_text(src, header)
    if body is None:
        return [(rel, 0, "2", header + " not found — gate ROT")]
    code = code_only(body)
    # Drop the parameter list: `existing: List<…>` in the signature is not a
    # consultation of the table.
    impl = code[code.find("{"):] if "{" in code else code
    base = src.find(header)
    out = []
    if not re.search(r"\b(?:firstOrNull|any|none|find|indexOf|contains|filter|count|"
                     r"singleOrNull|first|last)\b", impl):
        out.append((rel, line_of(src, base), "2",
                    "decideEntryAdd never queries the existing entries — the collision lookup "
                    "is gone (a duplicate is always allowed)"))
    if not live_positions(impl, r"EntryAddDecision\s*\(\s*false"):
        out.append((rel, line_of(src, base), "2",
                    "decideEntryAdd never returns a negative decision on a reachable path — "
                    "`allowed` is a constant true, so `addEntry` writes every duplicate"))
    return out


def check_canonical_return(src, rel, header, arm, what):
    """Arm 3 — the persisted id must be what comes back, on a REACHABLE path.

    `if (false) return repo.addEntry(entry)` satisfies the old substring anchor
    while the executed `return entry.id` hands back the pre-persist uuid, which
    `saveConfig` has already rewritten to the composite key — the caller then
    holds a dangling id. So: a live `return repo.addEntry(...)` must exist, and
    the function must not return the in-memory object's own id.
    """
    body = function_text(src, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    code = code_only(body)
    base = src.find(header)
    out = []
    if not live_positions(code, r"\breturn\s+repo\s*\.\s*addEntry\s*\("):
        out.append((rel, line_of(src, base), arm,
                    "%s never returns `repo.addEntry(...)` on a reachable path — the canonical "
                    "persisted id is not what comes back" % what))
    for m in re.finditer(r"\breturn\s+([A-Za-z_]\w*)\s*\.\s*id\b", code):
        if not path_feasible(code, m.start()):
            continue
        out.append((rel, line_of(src, base + m.start()), arm,
                    "%s returns the pre-persist id `%s.id` — the composite key is minted by "
                    "saveConfig's canonicalize pass, not by the in-memory object"
                    % (what, m.group(1))))
    return out


def check_response_persisted(src, rel, header, arm, what):
    """Arms 4 / 5 — the RPC response must report the PERSISTED entry.

    `return entryToJson(entry)` (or any other pre-persist copy) keeps the
    `persistedId` anchor while handing the client the uuid the store already
    rewrote. The `entryToJson(...)` argument is therefore traced as DATAFLOW:
    its value must come out of the persisted-id lookup.
    """
    body = function_text(src, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    code = code_only(body)
    base = src.find(header)
    out = []
    calls = list(re.finditer(r"\bentryToJson\s*\(", code))
    if not calls:
        out.append((rel, line_of(src, base), arm,
                    "%s never builds a response with entryToJson(...) — gate ROT" % what))
    for m in calls:
        args = call_args(code, m.end() - 1)
        if not args:
            out.append((rel, line_of(src, base + m.start()), arm,
                        "%s: entryToJson(...) could not be delimited — fail-closed" % what))
            continue
        arg = code[args[0][0]:args[0][1]].strip()
        if _traces_to(code, arg, r"\bpersistedId\b"):
            continue
        out.append((rel, line_of(src, base + m.start()), arm,
                    "%s reports `%s` — the response must carry the persisted entry (traced from "
                    "the persistedId lookup), not the pre-persist copy"
                    % (what, arg or "<empty>")))
    return out


def check_elvis_throws(src, rel, header, arm, what, call="repo.updateEntry(updated)"):
    """Arm 5 — a rejected rename must THROW.

    `?: current.id.also { if (false) (null ?: throw RPCException(…)) }` keeps
    the `?: throw RPCException` substring while falling back to the stale id, so
    a colliding rename is reported as success. The elvis fallback is therefore
    read as a VALUE: every value source of the fallback must be a `throw`.
    """
    body = function_text(src, header)
    if body is None:
        return [(rel, 0, arm, header + " not found — gate ROT")]
    code = code_only(body)
    base = src.find(header)
    out = []
    sites = list(re.finditer(re.escape(call), code))
    if not sites:
        out.append((rel, line_of(src, base), arm,
                    "%s no longer resolves the write through `%s` — gate ROT" % (what, call)))
    for m in sites:
        expr = code[m.start():expr_end(code, m.start())]
        parts = split_top(expr, ("?:",))
        if len(parts) != 2:
            out.append((rel, line_of(src, base + m.start()), arm,
                        "%s: `%s` has no fail-closed elvis — a rejected write is not refused"
                        % (what, call)))
            continue
        rhs = expr[parts[1][0]:parts[1][1]].strip()
        sources = value_sources(rhs)
        if not sources or not all(strip_parens(s).startswith("throw") for s in sources):
            out.append((rel, line_of(src, base + m.start()), arm,
                        "%s: the elvis fallback of `%s` is not a throw (`%s`) — a rejected rename "
                        "falls back to the stale id and is reported as success"
                        % (what, call, " ".join(rhs.split())[:80])))
    return out


def _expr_after(text, idx):
    """The expression starting at `idx`, up to the end of that statement.

    Statement end = a newline at bracket depth 0 whose next non-blank character
    is not a continuation (`.`, `?:`, an operator…). This is what lets a
    multi-line binding (`val childId = coll.childIds()\\n .filter { … }`) be read
    as ONE expression rather than one line — the id table is spread over four
    lines in the fixed shape.
    """
    depth = 0
    i = idx
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
            if depth < 0:
                return text[idx:i]
        elif ch == "\n" and depth == 0:
            j = i + 1
            while j < n and text[j] in " \t\n":
                j += 1
            if j < n and text[j] in ".?:+-*/&|":
                i = j
                continue
            return text[idx:i]
        i += 1
    return text[idx:]


# ── dot-truncation equivalence class. Every one of these produces "the part of
# the remainder before the first dot", which is NOT where a composite id ends:
# `<instanceId>/gpt-5.5` itself contains dots, so truncating the path text
# yields `/gpt-5` and the child never resolves (deep-audit-1041 A-01).
DOT_TRUNC = (
    (r"""split\s*\(\s*["']\.["']""", "splits the remainder on '.'"),
    (r"""substringBefore\s*\(\s*["']\.["']""", "cuts the remainder at the first '.'"),
    (r"""substringBeforeLast\s*\(\s*["']\.["']""", "cuts the remainder at the last '.'"),
    (r"""substringAfter\s*\(\s*["']\.["']""", "cuts the remainder at the first '.'"),
    (r"""takeWhile\s*\{[^}]*!=\s*["']\.["']""", "truncates the remainder at '.'"),
    (r"""indexOf\s*\(\s*["']\.["']""", "locates '.' and slices the remainder"),
    (r"""lastIndexOf\s*\(\s*["']\.["']""", "locates '.' and slices the remainder"),
)


def _child_id_sources(whole):
    """Every expression that PRODUCES the resolved child id.

    Anchored on the consumption site (`fields(forId = …)`) and traced back to the
    binding(s) that assign it, so the rule follows the DECISION instead of a
    variable name: renaming `childId` keeps the gate working, while removing the
    explicit id hand-off makes it fail closed.

    Returns (sources, names) where each source is `(expr, from_id_table)`:
    `expr` is the producing expression (None = traced but no visible producer),
    and `from_id_table` records whether the collection's own id table was what
    the value came out of — a `val` binding that calls `childIds()`, or a lambda
    parameter of a `childIds().mapNotNull { id -> … }` chain.
    """
    sources = []
    names = []
    # Anchored on the CONSUMPTION site — the call that asks the collection for a
    # child's fields. Both the named (`fields(forId = x)`) and positional
    # (`fields(x)`) forms are accepted, so a signature that loses the parameter
    # name does not turn the gate into a spelling checker.
    for m in re.finditer(r"\.fields\s*\(", whole):
        arg = _expr_after(whole, m.end())
        cut = len(arg)
        for ch in ",\n":
            k = arg.find(ch)
            if 0 <= k < cut:
                cut = k
        arg = arg[:cut].strip()
        arg = re.sub(r"^forId\s*=\s*", "", arg).strip()
        if not arg:
            continue
        if re.fullmatch(r"[A-Za-z_]\w*", arg):
            names.append(arg)
        else:
            sources.append((arg, "childIds()" in arg))
    for name in names:
        found = False
        for m in re.finditer(r"\b(?:val|var)\s+%s\s*=" % re.escape(name), whole):
            expr = _expr_after(whole, m.end()).strip()
            sources.append((expr, "childIds()" in expr))
            found = True
        if not found:
            # Inline `it` / a lambda parameter: fall back to every place the name
            # is bound, so a `let { id -> … }` cannot hide the derivation. The id
            # table may sit on the RECEIVER of the lambda
            # (`childIds().mapNotNull { id -> … }`), so the lookback counts.
            for m in re.finditer(r"\b%s\s*->" % re.escape(name), whole):
                expr = _expr_after(whole, m.end()).strip()
                ctx = whole[max(0, m.start() - 300):m.start()]
                sources.append((expr, "childIds()" in expr or "childIds()" in ctx))
                found = True
        if not found:
            sources.append((None, False))  # traced name with no visible producer
    return sources, names


def check_resolver(src, rel):
    """Arms 6–9 — the collection-child path grammar in ConfigRegistry.

    The anchor is the resolver region: every function that takes
    `path: String` and returns `ConfigField?`. Arms 6–9 each judge one
    DECISION over the union of those bodies, so a rename or a two-function
    delegation keeps passing while the decision itself is what gets tested.
    """
    bodies = path_router_bodies(src)
    if not bodies:
        return [(rel, 0, "6",
                 "no `fun (path: String): ConfigField?` resolver found — gate ROT")]
    whole = "\n".join(b for b, _o in bodies)
    # Offset map: a position in `whole` → the same character in `src`, so a
    # finding reports a real line instead of "somewhere in the resolver".
    seg = []
    pos = 0
    for body_code, src_off in bodies:
        seg.append((pos, pos + len(body_code), src_off))
        pos += len(body_code) + 1  # + the joining "\n"

    def src_index(whole_idx):
        for a, b, src_off in seg:
            if a <= whole_idx < b:
                return src_off + (whole_idx - a)
        return None

    out = []

    # Arm 6 — the id boundary must not be taken from the path TEXT.
    #
    # Judged as a POSITION rule rather than a call-spelling rule: inside the
    # resolver, exactly one dot-truncation is legitimate — the one that derives
    # the COLLECTION key (`collections[path.substringBefore('.')]`), because
    # collection ids carry no dots by contract. Every other truncation at a dot
    # is the id boundary being read off the text, whichever spelling or
    # intermediate variable it takes:
    #     split('.'), split(".", limit = N), substringBefore/After(Last)('.'),
    #     takeWhile { it != '.' }, indexOf('.') + slicing, inline chains,
    #     `val head = rest.substringBefore('.')` fed into a membership test…
    #
    # This is the arm the audited witness aimed at: `rest.substringBefore('.')`
    # carrying a childIds() membership test satisfies arms 7/8/9, so only a rule
    # about WHERE the boundary came from can see it.
    dot_split = re.compile(r"""split\(\s*['"]\.['"]\s*,\s*limit""")
    coll_index = [m.span() for m in re.finditer(r"collections\s*\[[^\]]*\]", whole)]
    reported = set()
    for pattern, _phrase in DOT_TRUNC:
        for m in re.finditer(pattern, whole):
            if any(a <= m.start() < b for a, b in coll_index):
                continue  # the collection key: the only legitimate truncation
            key = m.start()
            if key in reported:
                continue
            reported.add(key)
            idx = src_index(m.start())
            # The pre-fix "fast path" is worth naming as such: counting dots is
            # a different intent from cutting at one, and a reader should know
            # which one came back.
            counting = bool(dot_split.search(m.string[max(0, m.start() - 20):m.start() + 40]))
            if counting:
                msg = ("resolver splits the path by counting dots "
                       "(`split('.' , limit …)`) — a composite id containing '.' gets truncated")
            else:
                msg = ("child id is derived by cutting the path remainder at a dot "
                       "(`%s`) — a composite id containing '.' is truncated; the "
                       "boundary must come from childIds()" % m.group(0).strip())
            out.append((rel, line_of(src, idx) if idx is not None else 0, "6", msg))
            if len(reported) >= 3:
                break

    sources, id_names = _child_id_sources(whole)
    if not sources:
        out.append((rel, 0, "6",
                    "resolver never hands an explicit child id to `fields(forId = …)` "
                    "— the id hand-off cannot be traced (fail-closed)"))
    for expr, _from_table in sources:
        if expr is None:
            out.append((rel, 0, "6",
                        "child id %s is produced nowhere visible — the derivation "
                        "cannot be traced (fail-closed)" % (id_names or ["?"])[0]))

    # Arm 7 — the boundary comes from the collection's own id table.
    if "childIds()" not in whole:
        out.append((rel, 0, "7",
                    "resolver never consults childIds() — the id boundary must come "
                    "from the collection's id table, not from the path text"))
    else:
        # …and it is used as a PREFIX test of the remainder (the only symmetric
        # way to find where a dotted id ends).
        if not re.search(r"startsWith\(\s*\"?\$\{?\w+\}?\.", whole):
            out.append((rel, 0, "7",
                        "resolver reads childIds() but never prefix-tests it against the "
                        "remainder (`rest.startsWith(\"$id.\")`)"))
        # …and the membership test must be LIVE. `false && rest.startsWith("$it.")`
        # keeps the `childIds()` data source AND the `startsWith("$id.")` spelling
        # while the selection can never pass — the audited witness. Every
        # id-table lambda is therefore judged as CONTROL FLOW: its predicate must
        # be feasible under the shared constant folder.
        for m in re.finditer(r"childIds\(\)\s*\.\s*(?:filter|filterNot|firstOrNull|first|"
                             r"any|none|find|singleOrNull|mapNotNull|maxByOrNull|minByOrNull)"
                             r"\s*\{", whole):
            brace = m.end() - 1
            close = match_bracket(whole, brace)
            if close < 0:
                continue
            pred = whole[brace + 1:close].strip()
            pred = re.sub(r"^[A-Za-z_]\w*\s*->\s*", "", pred)
            if guard_feasible(pred):
                continue
            idx = src_index(m.start())
            out.append((rel, line_of(src, idx) if idx is not None else 0, "7",
                        "the childIds() membership test is DEAD (`%s` can never be true) — "
                        "selection over the id table never matches, so every collection "
                        "sub-field resolves to unknown_path"
                        % " ".join(whole[m.start():close + 1].split())[:90]))
        # POSITIVE criterion: the child id is a MEMBER of the id table, not just
        # some string the resolver happened to pass on. Judged on the producing
        # expression, so `childIds().contains(x)` counts but `x` alone does not.
        if not any(from_table for _expr, from_table in sources):
            out.append((rel, 0, "7",
                        "child id does not come from the collection's id table — no "
                        "producing expression consults childIds()"))

    # Arm 8 — no per-child materialisation (building a field list for every
    # child id would make each lookup O(children × fields)).
    for m in re.finditer(r"childIds\(\)\s*\.\s*(map|flatMap|mapNotNull|associate|associateBy)",
                         whole):
        out.append((rel, 0, "8",
                    "resolver materialises fields for every child id "
                    "(`childIds().%s…`) — resolve only the matched id" % m.group(1)))

    # Arm 9 — a near match is a miss: the child's field is confirmed against
    # the requested path before being returned.
    if not re.search(r"\.path\s*==\s*path\b", whole):
        out.append((rel, 0, "9",
                    "resolver returns the first field of a child without confirming "
                    "`.path == path` — a near-match leaf would resolve as the request"))
    return out


# ── Arm 10 — [meta-audit-1028 W-B00-08] the delete-set the save path hands over
# The quarantined rows are the ones the LOAD path skipped. The DAO derives its
# delete-set from the entries it is given, so "skip on load" is only a promise
# if the save path names them on the way back. Everything below judges the
# VALUE at the call site (named or positional), never the spelling.
SYNC_CALL = "syncAllIncremental"
PRESERVE_ARG = "preserveEntryIds"
EMPTY_CTORS = {
    "emptySet", "setOf", "mutableSetOf", "hashSetOf", "linkedSetOf", "sortedSetOf",
    "emptyList", "listOf", "mutableListOf", "arrayListOf", "setOfNotNull",
    "emptyArray", "arrayOf", "emptySequence", "sequenceOf", "emptyMap", "mapOf",
}
_CTOR_RE = re.compile(r"^(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*(?:<[^<>]*>)?\s*\((.*)\)$", re.S)
_NAMED_ARG = re.compile(r"^([A-Za-z_]\w*)\s*=(?!=)")
_IDENT_ONLY = re.compile(r"^`?[A-Za-z_]\w*`?$")


def _calls(code, name):
    """[(pos, [arg texts])] for every real call to `name(…)` (decls skipped)."""
    out = []
    for m in re.finditer(r"\b%s\s*\(" % re.escape(name), code):
        if re.search(r"\bfun\s+$", code[:m.start()]):
            continue
        spans = call_args(code, m.end() - 1)
        out.append((m.start(), [code[a:b].strip() for a, b in spans]))
    return out


def _constant_empty(expr):
    """`emptySet()` / `setOf()` / `mutableSetOf<String>()` — a single empty ctor."""
    e = strip_parens(" ".join(expr.split()))
    if e in ("emptySet()", "emptyList()", "emptyMap()") and "(" not in e[:-2]:
        return True
    m = _CTOR_RE.match(e)
    return bool(m) and m.group(1) in EMPTY_CTORS and not m.group(2).strip()


def _param_index(dao_src):
    """Index of `preserveEntryIds` in the DAO's `syncAllIncremental` signature."""
    if dao_src is None:
        return None
    code = code_only(dao_src)
    m = re.search(r"\bfun\s+%s\s*\(" % re.escape(SYNC_CALL), code)
    if m is None:
        return None
    close = match_bracket(code, m.end() - 1)
    if close < 0:
        return None
    inner = code[m.end():close]
    parts = [inner[a:b] for a, b in split_top(inner, (",",))]
    names = []
    for part in parts:
        head = part.split(":", 1)[0]
        head = re.sub(r"@\w+(\([^)]*\))?", " ", head)
        head = re.sub(r"\b(?:vararg|noinline|crossinline|val|var)\b", " ", head)
        names.append(head.strip())
    return names.index(PRESERVE_ARG) if PRESERVE_ARG in names else None


def _rhs_list(code, name):
    """Every expression assigned to `name` (declaration initialisers included)."""
    pat = re.compile(r"(?<![\w.`])(?:(?:val|var)\s+)?`?" + re.escape(name) +
                     r"`?\s*(?::[^=\n]+?)?=(?!=)")
    out = []
    for m in pat.finditer(code):
        out.append(code[m.end():expr_end(code, m.end())].strip())
    return out


def _fed_non_empty(code, name, depth=3):
    """Is `name` ever given a value that is not a constant-empty collection?"""
    for rhs in _rhs_list(code, name):
        if not rhs or _constant_empty(rhs):
            continue
        if _IDENT_ONLY.match(strip_parens(rhs.strip())):
            if depth and _fed_non_empty(code, strip_parens(rhs.strip()), depth - 1):
                return True
            continue
        return True
    return False


def check_preserve_entry_ids(src, rel, dao_src, dao_rel):
    """Arm 10 — the save path must hand the quarantined ids to the DAO."""
    try:
        code = code_only(src)
    except KotlinLexError as exc:
        return [(rel, 0, "10", "unlexable source (%s) — fail closed" % exc)]
    calls = _calls(code, SYNC_CALL)
    if not calls:
        return [(rel, 0, "10",
                 "no `%s(` call in the repository — the write path was moved and "
                 "the quarantine delete-set cannot be judged (gate ROT)" % SYNC_CALL)]
    idx = _param_index(dao_src)
    if idx is None:
        return [(dao_rel, 0, "10",
                 "`%s` no longer takes a `%s` parameter — the delete-set has no name "
                 "at the DAO boundary, so every skipped row is droppable (gate ROT)"
                 % (SYNC_CALL, PRESERVE_ARG))]
    out = []
    for pos, args in calls:
        line = line_of(src, pos)
        named = {}
        positional = []
        for a in args:
            m = _NAMED_ARG.match(a)
            if m:
                named[m.group(1)] = a[m.end():].strip()
            else:
                positional.append(a)
        if PRESERVE_ARG in named:
            value = named[PRESERVE_ARG]
        elif len(positional) > idx:
            value = positional[idx]
        else:
            out.append((rel, line, "10",
                        "`%s(` is called WITHOUT `%s`: the DAO falls back to its own "
                        "`emptySet()` default, so the rows the load path only meant to "
                        "SKIP are physically deleted by any unrelated save "
                        "(rename / reorder / refresh)" % (SYNC_CALL, PRESERVE_ARG)))
            continue
        if not value.strip():
            out.append((rel, line, "10",
                        "`%s = ` is blank — the delete-set cannot be established "
                        "(fail closed)" % PRESERVE_ARG))
            continue
        if _constant_empty(value):
            out.append((rel, line, "10",
                        "`%s = %s` is a CONSTANT EMPTY collection: the quarantined rows are "
                        "absent from the entries list by construction, so the very next save "
                        "deletes them (the load path's skip is defeated at the write path)"
                        % (PRESERVE_ARG, " ".join(value.split()))))
            continue
        roots = [t for t in re.findall(r"(?<![\w.])`?([A-Za-z_]\w*)`?(?![\w(])",
                                       strip_parens(value))
                 if t not in ("this", "it", "true", "false", "null")]
        holders = [r for r in roots
                   if re.search(r"\b(?:val|var)\s+`?" + re.escape(r) + r"`?\s*[:\s=]",
                                code)]
        if holders and not any(_fed_non_empty(code, r) for r in holders):
            out.append((rel, line, "10",
                        "`%s = %s` never receives a non-empty value anywhere in the file "
                        "(declared empty and never assigned): every save still passes an "
                        "empty delete-set"
                        % (PRESERVE_ARG, " ".join(value.split()))))
    return out


def run_checks(root, rels=None):
    rels = rels or {"pr": PR_REL, "mc": MC_REL, "pm": PM_REL, "reg": REG_REL,
                    "dao": DAO_REL}
    srcs = {k: read(rel, root) for k, rel in rels.items()}
    missing = [(rel, 0, "?", "file not found — gate ROT")
               for k, rel in rels.items() if srcs[k] is None]
    if missing:
        return missing
    try:
        return _run_checks(srcs, rels)
    except KotlinLexError as exc:
        # fail-closed: an unterminated literal/comment must not read as "clean"
        return [(rels["pr"], 0, "?", "KotlinLexError: %s — fail-closed" % exc)]


def _run_checks(srcs, rels):
    checks = []
    checks += check_anchors(srcs["pr"], rels["pr"], "fun addEntry(", "1",
                            ARM1_ANCHORS, "addEntry")
    checks += check_absent(srcs["pr"], rels["pr"], "fun addEntry(", "1",
                           ARM1_FORBIDDEN, "addEntry guard")
    checks += check_arm1_guard_live(srcs["pr"], rels["pr"], "fun addEntry(", "addEntry")
    checks += check_anchors(srcs["pr"], rels["pr"], None, "2",
                            ARM2_ANCHORS, "ProviderRepository helpers")
    checks += check_decide_entry_add(srcs["pr"], rels["pr"])
    checks += check_anchors(srcs["mc"], rels["mc"], "override fun add(", "3",
                            ARM3_ANCHORS, "ModelsCollection.add")
    checks += check_canonical_return(srcs["mc"], rels["mc"], "override fun add(", "3",
                                     "ModelsCollection.add")
    checks += check_anchors(srcs["pm"], rels["pm"], "fun modelsAdd(", "4",
                            ARM4_ANCHORS, "modelsAdd RPC")
    checks += check_response_persisted(srcs["pm"], rels["pm"], "fun modelsAdd(", "4",
                                       "modelsAdd RPC")
    checks += check_anchors(srcs["pm"], rels["pm"], "fun modelsUpdate(", "5",
                            ARM5_ANCHORS, "modelsUpdate RPC")
    checks += check_elvis_throws(srcs["pm"], rels["pm"], "fun modelsUpdate(", "5",
                                 "modelsUpdate RPC")
    checks += check_response_persisted(srcs["pm"], rels["pm"], "fun modelsUpdate(", "5",
                                       "modelsUpdate RPC")
    checks += check_resolver(srcs["reg"], rels["reg"])
    checks += check_preserve_entry_ids(srcs["pr"], rels["pr"], srcs["dao"], rels["dao"])
    return checks


# ----------------------------------------------------------------------
# Selftest fixtures — minimal Kotlin-shaped files exercising every arm.
# ----------------------------------------------------------------------
SAVE_PATH = """
private var quarantinedEntryRowIds: Set<String> = emptySet()

private suspend fun loadFromDb() {
    val loaded = loadRows()
    quarantinedEntryRowIds = loaded.quarantinedEntryRowIds
}

private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
    val snapshot = config.toSnapshot()
    providerDao.syncAllIncremental(
        instances = snapshot.instances,
        entries = snapshot.entries,
        groups = snapshot.groups,
        loopIds = snapshot.loopIds,
        meta = snapshot.meta,
        preserveEntryIds = quarantinedEntryRowIds,
    )
    return config
}
"""

FIX_DAO_OK = """package p

interface ProviderConfigDao {
    suspend fun syncAllIncremental(
        instances: List<Inst>,
        entries: List<Entry>,
        groups: List<Grp>,
        loopIds: List<Loop>,
        meta: List<Meta>,
        preserveEntryIds: Set<String> = emptySet(),
    ) {
        clearEntries()
    }
}
"""

FIX_PR_OK = """fun addEntry(entry: ModelEntry): String = synchronized(configLock) {
    ensureConfigLoaded()
    val config = mutationSnapshot(_config.value)
    val decision = decideEntryAdd(entry.providerInstanceId, entry.baseModel.id, existing)
    if (!decision.allowed) {
        return@synchronized decision.persistedId
    }
    config.modelEntries.add(entry)
    saveConfig(config)
    _config.value.modelEntries.firstOrNull { it.id == entry.id }?.id
        ?: compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
}

internal fun decideEntryAdd(a: String, b: String, c: List<Pair<String, String>>): EntryAddDecision {
    val newKey = "$a/$b"
    val hit = c.firstOrNull { it.second == newKey }
    return if (hit != null) EntryAddDecision(false, hit.first) else EntryAddDecision(true, newKey)
}

internal fun decideCompactPinMigration(p: String, o: String, n: String): String? = TODO()

internal fun decideCompactPinCleanup(p: String, r: Collection<String>): Boolean = TODO()
""" + SAVE_PATH
FIX_PR_BAD_TYPECONDITIONAL = """fun addEntry(entry: ModelEntry) = synchronized(configLock) {
    ensureConfigLoaded()
    val config = mutationSnapshot(_config.value)
    if (!entry.isCustom) {
        val exists = config.modelEntries.any { it.baseModel.id == entry.baseModel.id }
        if (exists) return@synchronized
    }
    config.modelEntries.add(entry)
    saveConfig(config)
}

internal fun decideEntryAdd(a: String, b: String, c: List<Pair<String, String>>): EntryAddDecision {
    val newKey = "$a/$b"
    val hit = c.firstOrNull { it.second == newKey }
    return if (hit != null) EntryAddDecision(false, hit.first) else EntryAddDecision(true, newKey)
}

internal fun decideCompactPinMigration(p: String, o: String, n: String): String? = TODO()

internal fun decideCompactPinCleanup(p: String, r: Collection<String>): Boolean = TODO()
""" + SAVE_PATH

FIX_MC_OK = """override fun add(payload: ConfigValue): String {
    val duplicate = repo.config.value.modelEntries.firstOrNull { it.baseModel.id == modelIdStr }
    if (duplicate != null) {
        throw ConfigError.AlreadyExists("Model already exists")
    }
    val entry = ModelEntry(baseModel = model)
    return repo.addEntry(entry)
}
"""

FIX_MC_BAD_TEMPUUID = """override fun add(payload: ConfigValue): String {
    val entry = ModelEntry(baseModel = model)
    repo.addEntry(entry)
    return entry.id
}
"""

FIX_PM_OK = """fun modelsAdd(context: Context, params: JSONObject): JSONObject {
    val entry = ModelEntry(baseModel = model)
    if (repo.config.value.modelEntries.any { it.baseModel.id == modelId }) {
        throw RPCException(-32602, "model already exists")
    }
    val persistedId = repo.addEntry(entry)
    val persisted = repo.config.value.modelEntries.firstOrNull { it.id == persistedId } ?: entry
    return entryToJson(persisted)
}

fun modelsUpdate(context: Context, params: JSONObject): JSONObject {
    val updated = current.copy(baseModel = newModel)
    val persistedId = repo.updateEntry(updated)
        ?: throw RPCException(-32602, "Entry update rejected")
    val persisted = repo.config.value.modelEntries.firstOrNull { it.id == persistedId } ?: updated
    return entryToJson(persisted)
}
"""

FIX_PM_BAD_STALE = """fun modelsAdd(context: Context, params: JSONObject): JSONObject {
    val entry = ModelEntry(baseModel = model)
    repo.addEntry(entry)
    return entryToJson(entry)
}

fun modelsUpdate(context: Context, params: JSONObject): JSONObject {
    val updated = current.copy(baseModel = newModel)
    repo.updateEntry(updated)
    return entryToJson(updated)
}
"""

# ---- ConfigRegistry resolver fixtures (arms 6–9) ----------------------

# The fixed shape AND the documentation trap in one file: the KDoc quotes the
# pre-fix idiom, so a gate that reads comments would red on its own docs.
FIX_REG_OK = '''package com.rikkaminis.app.config

/**
 * A previous implementation used `path.split('.', limit = 3)` and truncated
 * every dotted id. Do not bring that back.
 */
class ConfigRegistry {
    fun resolveField(path: String): ConfigField? =
        resolveRegistryField(path, fields, collections)
}

internal fun resolveRegistryField(
    path: String,
    fields: Map<String, ConfigField>,
    collections: Map<String, ConfigCollection>,
): ConfigField? {
    fields[path]?.let { return it }
    val coll = collections[path.substringBefore('.', missingDelimiterValue = "")] ?: return null
    val prefix = coll.basePath + "."
    if (!path.startsWith(prefix)) return null
    val rest = path.substring(prefix.length)
    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null
    return coll.fields(forId = childId).firstOrNull { it.path == path }
}
'''

# The realistic regression is NOT "revert the whole function" (that trips arm 7
# too, see FIX_REG_PREFIX_SHAPE) but a dot-counting FAST PATH left in front of
# the id table — the shape a future "shortcut the common case" edit produces.
FIX_REG_DOTSPLIT = FIX_REG_OK.replace(
    '''    val childId = coll.childIds()''',
    '''    if (rest.split('.', limit = 2).size != 2) return null
    val childId = coll.childIds()''')

# The pre-fix function, verbatim in shape: dot counting and no id table, so it
# necessarily trips both arms. Kept as a combined control (the pair is the
# defect; arms 6 and 7 are its two separable halves).
FIX_REG_PREFIX_SHAPE = FIX_REG_OK.replace(
    '''    val coll = collections[path.substringBefore('.', missingDelimiterValue = "")] ?: return null
    val prefix = coll.basePath + "."
    if (!path.startsWith(prefix)) return null
    val rest = path.substring(prefix.length)
    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null
    return coll.fields(forId = childId).firstOrNull { it.path == path }''',
    '''    val segments = path.split('.', limit = 3)
    if (segments.size != 3) return null
    val coll = collections[segments[0]] ?: return null
    return coll.fields(forId = segments[1]).firstOrNull { it.path == path }''')

# Derived from the right-hand side only: no childIds() at all. Deliberately
# NOT a dot-truncation — the two decisions are separate arms (a fixture that
# violates both cannot answer "which decision broke"), so the truncating
# variants live in their own fixtures below.
FIX_REG_NO_CHILDIDS = FIX_REG_OK.replace(
    '''    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null''',
    '''    val childId = rest.takeIf { it.isNotBlank() }
        ?: return null''')

# ── the dot-truncation equivalence class (arm 6). Each of these produces
# "everything before the first dot", which is not where a composite id ends.
# Fixture 1 is the AUDITED WITNESS verbatim: it keeps a childIds() membership
# test, a prefix test and the `.path == path` confirmation, so arms 7/8/9 are
# all satisfied — only the boundary itself is wrong. That is exactly why the
# old arm 6 (which only knew `split('.', limit)`) let it through.
FIX_REG_SUBSTRING_BEFORE = FIX_REG_OK.replace(
    '''    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null''',
    '''    val childId = rest.substringBefore('.')
        .takeIf { id -> id in coll.childIds() && rest.startsWith("$id.") }
        ?: return null''')

FIX_REG_SPLIT_NO_LIMIT = FIX_REG_OK.replace(
    '''    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null''',
    '''    val childId = rest.split(".").firstOrNull()
        ?.takeIf { id -> id in coll.childIds() && rest.startsWith("$id.") }
        ?: return null''')

FIX_REG_SUBSTRING_BEFORE_LAST = FIX_REG_OK.replace(
    '''    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null''',
    '''    val childId = rest.substringBeforeLast('.')
        .takeIf { id -> id in coll.childIds() && rest.startsWith("$id.") }
        ?: return null''')

# Consults childIds() but without a prefix test (an id is `contains`ed).
FIX_REG_NO_PREFIX = FIX_REG_OK.replace(
    '''    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null''',
    '''    val childId = coll.childIds().firstOrNull { rest.contains(it) }
        ?: return null''')

# Investigates every child instead of only the matched one.
FIX_REG_MATERIALISE = FIX_REG_OK.replace(
    '''    return coll.fields(forId = childId).firstOrNull { it.path == path }''',
    '''    return coll.childIds()
        .mapNotNull { id -> coll.fields(forId = id).firstOrNull { it.path == path } }
        .firstOrNull()''')

# Returns the child's first field without confirming the requested leaf.
FIX_REG_NEAR_MATCH = FIX_REG_OK.replace(
    '''    return coll.fields(forId = childId).firstOrNull { it.path == path }''',
    '''    return coll.fields(forId = childId).firstOrNull()''')

# ROT: the resolver was renamed to something that no longer takes `path: String`
# returning `ConfigField?` (a signature-level loss of the decision).
FIX_REG_ROT = '''package com.rikkaminis.app.config

class ConfigRegistry {
    fun lookupAnything(selector: String): Any? = null
}
'''



def _report(checks, fixture_name, label):
    if not checks:
        print("  [OK]   %s (%s)" % (fixture_name, label))
        return True
    for rel, line, arm, msg in checks:
        print("  [FAIL] %s arm %s line %s: %s" % (fixture_name, arm, line, msg))
    return False


def selftest():
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        rels = {"pr": "pr.kt", "mc": "mc.kt", "pm": "pm.kt", "reg": "reg.kt",
                "dao": "dao.kt"}

        def write(name, text):
            with open(os.path.join(tmp, rels[name]), "w", encoding="utf-8") as f:
                f.write(text)

        write("pr", FIX_PR_OK)
        write("dao", FIX_DAO_OK)
        write("mc", FIX_MC_OK)
        write("pm", FIX_PM_OK)
        write("reg", FIX_REG_OK)
        ok &= _report(run_checks(tmp, rels), "fixed", "all arms clean")

        # Dirty 1: type-conditional guard (the pre-fix shape).
        write("pr", FIX_PR_BAD_TYPECONDITIONAL)
        checks = run_checks(tmp, rels)
        got = [c for c in checks if c[2] == "1"]
        if not any("type-conditional" in c[3] for c in got):
            print("  [FAIL] typec-conditional fixture did not trip the forbidden check")
            ok = False
        else:
            print("  [OK]   typec-conditional fixture trips arm 1 (%d findings)" % len(got))
        write("pr", FIX_PR_OK)

        # Dirty 2: ModelsCollection returns the pre-persist uuid.
        write("mc", FIX_MC_BAD_TEMPUUID)
        checks = run_checks(tmp, rels)
        got = [c for c in checks if c[2] == "3"]
        if not got:
            print("  [FAIL] temp-uuid fixture did not trip arm 3")
            ok = False
        else:
            print("  [OK]   temp-uuid fixture trips arm 3 (%d findings)" % len(got))
        write("mc", FIX_MC_OK)

        # Dirty 3: RPC surfaces report the pre-persist copy.
        write("pm", FIX_PM_BAD_STALE)
        checks = run_checks(tmp, rels)
        got = [c for c in checks if c[2] in ("4", "5")]
        if not got:
            print("  [FAIL] stale-response fixture did not trip arms 4/5")
            ok = False
        else:
            print("  [OK]   stale-response fixture trips arms 4/5 (%d findings)" % len(got))
        write("pm", FIX_PM_OK)

        # ── batch5 / B5-t10 witnesses: each mutant keeps EVERY anchor the old
        # substring checks looked for, so only a value/control-flow criterion
        # can catch it. Each must red its OWN arm and no sibling.
        def _mutate(base, needle, replacement, label):
            if needle not in base:
                print("  [FAIL] %s: fixture needle missing — harness drift" % label)
                return None
            return base.replace(needle, replacement, 1)

        def _arm_only(label, key, text, arm, needle):
            nonlocal ok
            if text is None:
                ok = False
                return
            write(key, text)
            checks = run_checks(tmp, rels)
            got = [c for c in checks if c[2] == arm and needle in c[3]]
            stray = [c for c in checks if c[2] != arm]
            if not got:
                print("  [FAIL] %s did not trip arm %s (%r)" % (label, arm, needle))
                ok = False
            elif stray:
                print("  [FAIL] %s also trips arms %s" % (label, sorted({c[2] for c in stray})))
                ok = False
            else:
                print("  [OK]   %s trips arm %s only" % (label, arm))
            write(key, {"pr": FIX_PR_OK, "mc": FIX_MC_OK,
                        "pm": FIX_PM_OK, "reg": FIX_REG_OK, "dao": FIX_DAO_OK}[key])

        # W1: the dedupe guard is constant-folded dead but keeps every anchor.
        _arm_only("G08-A1 dead duplicate guard", "pr",
                  _mutate(FIX_PR_OK, "if (!decision.allowed) {",
                          "if (false && !decision.allowed) {", "G08-A1"),
                  "1", "DEAD")

        # W2: the pure helper always allows, so the collision is always minted.
        _arm_only("G08-A2 gutted decideEntryAdd", "pr",
                  _mutate(FIX_PR_OK,
                          '    val newKey = "$a/$b"\n'
                          '    val hit = c.firstOrNull { it.second == newKey }\n'
                          '    return if (hit != null) EntryAddDecision(false, hit.first) '
                          'else EntryAddDecision(true, newKey)\n',
                          '    return EntryAddDecision(true, "$a/$b")\n', "G08-A2"),
                  "2", "never queries the existing entries")

        # W3: the canonical return sits behind a dead guard, the executed return
        # hands back the pre-persist uuid.
        _arm_only("G08-A3 dead canonical return", "mc",
                  _mutate(FIX_MC_OK, "    return repo.addEntry(entry)\n",
                          "    if (false) return repo.addEntry(entry)\n    return entry.id\n",
                          "G08-A3"),
                  "3", "pre-persist id")

        # W4: the RPC reports the pre-persist copy while the persisted-id
        # lookup stays in place.
        _arm_only("G08-A4 stale response copy", "pm",
                  _mutate(FIX_PM_OK, "return entryToJson(persisted)\n}\n\nfun modelsUpdate",
                          "return entryToJson(entry)\n}\n\nfun modelsUpdate", "G08-A4"),
                  "4", "pre-persist copy")

        # W5: the fail-closed elvis keeps its `?: throw RPCException` spelling
        # inside a discarding scope and falls back to the stale id.
        _arm_only("G08-A5 dead fail-closed elvis", "pm",
                  _mutate(FIX_PM_OK,
                          '        ?: throw RPCException(-32602, "Entry update rejected")\n',
                          '        ?: current.id.also {\n'
                          '            if (false) {\n'
                          '                null ?: throw RPCException(-32602, "Entry update rejected")\n'
                          '            }\n'
                          '        }\n', "G08-A5"),
                  "5", "not a throw")

        # W7: the id-table membership test is dead-guarded — childIds() and
        # `startsWith("$it.")` both survive, nothing ever matches.
        _arm_only("G08-A6 dead id-table predicate", "reg",
                  _mutate(FIX_REG_OK, '.filter { rest.startsWith("$it.") }',
                          '.filter { false && rest.startsWith("$it.") }', "G08-A6"),
                  "7", "membership test is DEAD")

        # Dirty 4–8: the resolver arms. Each fixture must red its OWN arm and
        # only that arm — a fixture that also trips a sibling proves the arms
        # are not separable (and would make the "which decision broke" answer
        # meaningless in CI).
        resolver_fixtures = [
            ("dot-split", FIX_REG_DOTSPLIT, "6", "resolver splits the path by counting dots"),
            # ── the dot-truncation class: same defect, four spellings. The first
            # is the AUDITED WITNESS (gate-green before this fix).
            ("substringBefore", FIX_REG_SUBSTRING_BEFORE, "6", "cutting the path remainder at a dot"),
            ("split-without-limit", FIX_REG_SPLIT_NO_LIMIT, "6", "cutting the path remainder at a dot"),
            ("substringBeforeLast", FIX_REG_SUBSTRING_BEFORE_LAST, "6", "cutting the path remainder at a dot"),
            ("no-childIds", FIX_REG_NO_CHILDIDS, "7", "never consults childIds()"),
            ("no-prefix-test", FIX_REG_NO_PREFIX, "7", "never prefix-tests"),
            ("materialised", FIX_REG_MATERIALISE, "8", "materialises fields for every child id"),
            ("near-match", FIX_REG_NEAR_MATCH, "9", "without confirming"),
        ]
        for label, text, arm, needle in resolver_fixtures:
            write("reg", text)
            checks = run_checks(tmp, rels)
            got = [c for c in checks if c[2] == arm and needle in c[3]]
            others = [c for c in checks if c not in got]
            if not got:
                print("  [FAIL] %s fixture did not trip arm %s (%r)" % (label, arm, needle))
                ok = False
            elif others:
                print("  [FAIL] %s fixture also tripped %d other arm(s): %s"
                      % (label, len(others), [c[2] for c in others]))
                ok = False
            else:
                print("  [OK]   %s fixture trips arm %s only" % (label, arm))
        write("reg", FIX_REG_OK)

        # ROT control 1: a repo without the target files must fail loudly.
        with tempfile.TemporaryDirectory() as empty:
            rot = run_checks(empty, rels)
            if not rot:
                print("  [FAIL] ROT control: missing files produced no finding")
                ok = False
            else:
                print("  [OK]   ROT control: missing files reported (%d findings)" % len(rot))

        # Combined control: the pre-fix function trips arms 6 AND 7 — the two
        # halves of one defect, each independently pinned above.
        write("reg", FIX_REG_PREFIX_SHAPE)
        checks = run_checks(tmp, rels)
        got = sorted(c[2] for c in checks)
        if got != ["6", "7"]:
            print("  [FAIL] pre-fix shape control: expected arms ['6','7'], got %s" % got)
            ok = False
        else:
            print("  [OK]   pre-fix shape control trips arms 6+7")
        write("reg", FIX_REG_OK)

        # ROT control 2 (fail-closed): a ConfigRegistry whose resolver no
        # longer exists in the `path: String → ConfigField?` shape must be
        # reported, never silently treated as "clean".
        write("reg", FIX_REG_ROT)
        checks = run_checks(tmp, rels)
        got = [c for c in checks if c[2] == "6" and "gate ROT" in c[3]]
        if not got:
            print("  [FAIL] fail-closed control: a missing resolver was not reported")
            ok = False
        else:
            print("  [OK]   fail-closed control: missing resolver reported as gate ROT")
        write("reg", FIX_REG_OK)

        # ── [meta-audit-1028 W-B00-08] Arm 10: the quarantine delete-set ─────
        # The witness: the shipped call site hands the DAO a constant empty set,
        # so an unrelated save physically deletes the rows the load path skipped.
        _arm_only("G10-A1 witness: constant empty delete-set", "pr",
                  _mutate(FIX_PR_OK, "preserveEntryIds = quarantinedEntryRowIds",
                          "preserveEntryIds = emptySet()", "G10-A1"),
                  "10", "CONSTANT EMPTY")

        # Same defect, another spelling of "empty": the DAO default is silent, so
        # dropping the argument is equal to passing one.
        _arm_only("G10-A2 the argument is not passed at all", "pr",
                  _mutate(FIX_PR_OK, "        preserveEntryIds = quarantinedEntryRowIds,\n",
                          "", "G10-A2"),
                  "10", "WITHOUT `preserveEntryIds`")

        _arm_only("G10-A3 typed empty constructor", "pr",
                  _mutate(FIX_PR_OK, "preserveEntryIds = quarantinedEntryRowIds",
                          "preserveEntryIds = mutableSetOf<String>()", "G10-A3"),
                  "10", "CONSTANT EMPTY")

        # One level up: the set is named on the call, declared empty, never filled.
        _arm_only("G10-A4 the id set is never fed", "pr",
                  _mutate(FIX_PR_OK,
                          "    quarantinedEntryRowIds = loaded.quarantinedEntryRowIds\n",
                          "    quarantinedEntryRowIds = quarantinedEntryRowIds\n",
                          "G10-A4"),
                  "10", "never receives a non-empty value")

        # Tolerant arms: the SAME value written three other legitimate ways.
        for label, text in (
            # Kotlin forbids positional-after-named, so the positional spelling
            # is the whole argument list written without names.
            ("G10-T1 positional argument", FIX_PR_OK.replace(
                "        instances = snapshot.instances,\n"
                "        entries = snapshot.entries,\n"
                "        groups = snapshot.groups,\n"
                "        loopIds = snapshot.loopIds,\n"
                "        meta = snapshot.meta,\n"
                "        preserveEntryIds = quarantinedEntryRowIds,\n",
                "        snapshot.instances,\n"
                "        snapshot.entries,\n"
                "        snapshot.groups,\n"
                "        snapshot.loopIds,\n"
                "        snapshot.meta,\n"
                "        quarantinedEntryRowIds,\n")),
            ("G10-T2 local val bound to the set", FIX_PR_OK.replace(
                "    val snapshot = config.toSnapshot()\n",
                "    val snapshot = config.toSnapshot()\n"
                "    val preserve = quarantinedEntryRowIds\n").replace(
                "preserveEntryIds = quarantinedEntryRowIds",
                "preserveEntryIds = preserve")),
            ("G10-T3 defensive copy of the set", FIX_PR_OK.replace(
                "preserveEntryIds = quarantinedEntryRowIds",
                "preserveEntryIds = quarantinedEntryRowIds.toSet()")),
        ):
            write("pr", text)
            checks = run_checks(tmp, rels)
            if checks:
                print("  [FAIL] %s stays green: %s" % (label, [(c[2], c[3][:60]) for c in checks]))
                ok = False
            else:
                print("  [OK]   %s stays green" % label)
        write("pr", FIX_PR_OK)

        # Fail-closed: a DAO that no longer names the parameter cannot be judged.
        write("dao", FIX_DAO_OK.replace("preserveEntryIds", "keepIds"))
        checks = run_checks(tmp, rels)
        got = [c for c in checks if c[2] == "10" and "gate ROT" in c[3]]
        if not got:
            print("  [FAIL] fail-closed control: a renamed DAO parameter was not reported")
            ok = False
        else:
            print("  [OK]   fail-closed control: renamed DAO parameter reported as gate ROT")
        write("dao", FIX_DAO_OK)

    print("ENTRY_COMPOSITE_KEY_SELFTEST=%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("root", nargs="?", default=None)
    ap.add_argument("--root", dest="root_opt", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    root = args.root_opt or args.root
    if not root:
        ap.print_usage(sys.stderr)
        return 2
    if not os.path.isdir(root):
        print("root not found: %s" % root, file=sys.stderr)
        return 2

    checks = run_checks(root)
    if not checks:
        print("entry_composite_key_guard_check: OK (10 arms clean)")
        return 0
    for rel, line, arm, msg in checks:
        print("%s:%s [arm %s] %s" % (rel, line, arm, msg))
    print("entry_composite_key_guard_check: %d violation(s)" % len(checks))
    return 1


if __name__ == "__main__":
    sys.exit(main())
