#!/usr/bin/env python3
"""da1020-F gate: deep-link settings routes + route-segment encoding.

Usage (repo root, positional arg supported):
    python3 scripts/scan/deeplink_settings_routes_check.py [ROOT]

PASS  = exit 0, prints DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS after --selftest
FAIL  = exit 1, prints violating 文件:行

Judged invariants:
  1. The settings-path whitelist covers the EMITTER list (minis://settings/
     links the app itself emits, incl. the MCP OAuth round-trip emitted by
     minis-mcp-cli transport/http.py).
  2. Every parameterised Routes segment builder encodes through the single
     helper (seg()) — a raw id would hand nav.navigate a malformed route
     (uncaught IllegalArgumentException).
  3. The unknown-path fallback to Settings home is still present
     (deliberate design — must not regress into an error).
  4. MainActivity's deep-link navigate has a runCatching backstop.
  5. [da1021-C] AppNavigation's cold-start deep-link navigate keeps its
     runCatching backstop.
  6. [da1024-E] No composable block decodes a route argument itself: the Nav
     framework %-decodes each path segment exactly once
     (NavDeepLink.getMatchingPathArguments -> Uri.decode), so a second decode
     rewrites ids carrying a literal '%'. Uri.encode stays legal (builders).
     Scope = the composable blocks, which is where route args are read; a
     decode added elsewhere in this file is out of scope by design (see the
     branch report's negative decisions).
  7. [da1024-E] The MODEL_ENTRY_DETAIL block still reads both args via
     getString and keeps the `?: return@composable` fail-closed guard.
  8. [da1028-C] EnvironmentVariablesScreen must OBSERVE its pending deep-link
     create request: `pendingEnvVarCreate` read through `collectAsState()`,
     and no `LaunchedEffect(Unit)` that consumes it (a one-shot effect never
     re-runs, so a warm-start deep link into an already-composed screen is
     dropped). EnvVarFormSheet's four field shadows (`keyText`, `valueText`,
     `noteText`, `groupText`) must each be keyed on what they are seeded from
     — keyless keeps the FIRST entry's text after `editEntry` switches.
  9. [da1028-C] ChatScreen must observe `pendingChatAction` the same way
     (`collectAsState()`; no `.value` snapshot). Regression anchor: the
     neighbouring `pendingHtmlPreview` subscription must stay reactive — that
     is the shape this file already uses and nothing may "simplify" it back.
  10. Anchors missing in either file = RED (a rename must not retire 8/9).
  11. [da1029-A] AppNavigation's cold-start `when (initialDeepLink)` dispatch
      names EVERY `DeepLinkAction` subclass and has no catch-all `else ->`,
      and its `OpenAlarmList` branch launches
      `AlarmClock.ACTION_SHOW_ALARMS`. `minis://views/alarm` used to be a
      silent cold-start no-op because 4 of the 11 subclasses fell into the
      old `else -> {}`. The neighbouring `quickActionStart` resolver
      (`else -> null`) is deliberately out of scope — the criterion selects
      the dispatch block by requiring its dispatch-only OpenTerminal case.
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    function_body,
    if_blocks,
    is_pure_literal,
    last_write,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
    when_branches,
)
from _kotlin_valueflow import (  # noqa: E402
    brace_depth,
    call_args,
    code_view,
    contains_ident,
    enclosing_conditions,
    expr_end,
    literal_view,
    live_positions,
    path_feasible,
    scope_calls,
    value_sources,
)

# Emitter list: minis://settings/<head> links the app itself emits.
# Sources (extracted 2026-10-17 from main 942b0111d):
#   - src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/transport/http.py:140
#       "[Authorize](minis://settings/mcp-servers/%s)"
#   - Kotlin prompt strings (ChatPromptAndTools.kt / ConfigBridge.kt /
#     EnvVarsCollection.kt / ProvidersCollection.kt):
#       minis://settings/{permissions,environments,memory}
# Re-extract with:
#   find src -type f \( -name '*.kt' -o -name '*.py' \) | xargs grep -rn "minis://settings"
EMITTER_HEADS = ["mcp-servers", "permissions", "environments", "memory"]

REL_DLH = "src/android/app/src/main/java/com/rikkaminis/app/deeplink/DeepLinkHandler.kt"
REL_NAV = "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"
REL_MAIN = "src/android/app/src/main/java/com/rikkaminis/app/MainActivity.kt"
REL_HTTP_PY = "src/android/app/src/main/assets/default_mount/usr/local/lib/minis-mcp-cli/transport/http.py"
# [da1028-C] reactive deep-link consumers
REL_ENV_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/EnvironmentVariablesScreen.kt"
REL_CHAT_SCREEN = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"
# the four shadow copies EnvVarFormSheet seeds from its entry
ENV_FORM_FIELDS = ("keyText", "valueText", "noteText", "groupText")

def launch_effect_blocks(code, arg):
    """[(lineno, block_text)] for every `LaunchedEffect(<arg>) { ... }`.

    A block ends at the first following `}` sitting at the opener's own
    indentation (same rule as composable_blocks).
    """
    blocks = []
    indexed = code_lines(code)
    pat = re.compile(r"LaunchedEffect\s*\(\s*" + re.escape(arg) + r"\s*\)")
    i = 0
    while i < len(indexed):
        lineno, line = indexed[i]
        if not pat.search(line):
            i += 1
            continue
        indent = len(line) - len(line.lstrip())
        j = i + 1
        while j < len(indexed):
            text = indexed[j][1]
            if text.strip() == "}" and (len(text) - len(text.lstrip())) == indent:
                break
            j += 1
        blocks.append((lineno, "\n".join(t for _, t in indexed[i:j + 1])))
        i = j + 1
    return blocks

# Parameterised Routes segment builders that MUST encode their id.
# [da1021-C] Extended to ALL 17 path-segment builders (the 6 da1020-F ones +
# the 10 that were still raw-interpolated + the new mirrorCategoryDetail).
# Excluded on purpose: fileBrowser / terminal / filePreview encode through
# their own local `enc` helpers (same android.net.Uri.encode semantics, no
# seg() dependency).
SEG_BUILDERS = [
    "providerDetail", "providerConnection", "modelGroupDetail",
    "addModelsToGroup", "addCustomModel", "modelEntryDetail",
    "scheduledTaskEdit", "skillDetail", "skillFile", "chatFiles",
    "mountedFoldersDetail", "sharedFoldersDetail", "logDetail",
    "sessionStorageDetail", "memoryFileEdit", "chat",
    "mirrorCategoryDetail",
]

VIOLATIONS = []
# [r3-t21] arm bookkeeping for the selftest marker: one arm = one fixture pair
CHECKS = [0]



def views(rel, src, what):
    """(code, literal) projections of a source file, or (None, None).

    `code_view` blanks comments AND literal bodies (length-preserving, so an
    offset still indexes the original); `literal_view` blanks only comments and
    is what reads template holes / emitted strings. A parse failure is a
    violation, never a fallback to the raw text (parser-death axis).
    """
    try:
        return code_view(src), literal_view(src)
    except KotlinLexError as exc:
        fail(rel, f"{what} cannot be parsed by the shared Kotlin lexer ({exc}) — "
                  "fail-closed: an unreadable source is a violation, not a skip")
        return None, None

def fail(path, msg):
    VIOLATIONS.append(f"{path}: {msg}")

# [wide-1024 12-misc-b] Form-aware helpers. The criteria below used to ask
# "does the file contain this substring / does the block mention seg() once";
# every one of the four witnesses in this branch keeps the substring and drops
# the EFFECT (a raw sibling id, a constant LaunchedEffect key, a constant
# remember key, a constructed-but-never-launched Intent). Judging a spelling
# needs a shape-equivalence class; judging an effect needs the AST-level
# neighbours, so these helpers read the expression rather than the text.
KEYWORDS = {
    "true", "false", "null", "this", "it", "super", "return", "if", "else",
    "val", "var", "by", "fun", "object", "class", "when", "for", "while",
    "in", "is", "as", "try", "catch", "throw", "Unit", "String", "Boolean",
    "Int", "Long", "Float", "Double", "Any", "Nothing",
}


def string_literals(text):
    """[(start, end)] of every string/char literal, delimiters included.

    Derived from the shared lexer instead of a hand-written quote scanner: a
    position is literal content when the literal view still has text there
    while the code view has blanked it (comments are blanked by BOTH views, so
    a quote inside a comment is never a literal). Raw strings may carry a
    run of up to three delimiters on each side.
    """
    if not text:
        return []
    try:
        code = code_view(text)
        lit = literal_view(text)
    except KotlinLexError:
        return []
    spans = []
    i, n = 0, len(lit)
    while i < n:
        if lit[i] in " \t\r\n" or code[i] != " ":
            i += 1
            continue
        j = i
        while j < n and lit[j] not in " \t\r\n" and code[j] == " ":
            j += 1
        start, end = i, j
        quote = text[start - 1] if start > 0 else ""
        if quote not in ('"', "'"):
            quote = ""
        pre = post = 0
        while quote and pre < 3 and start - pre > 0 and text[start - pre - 1] == quote:
            pre += 1
        while quote and post < 3 and end + post < n and text[end + post] == quote:
            post += 1
        spans.append((start - pre, end + post))
        i = j
    return spans




def ident_roots(expr):
    """Root identifiers an expression READS (`editEntry?.key` -> editEntry).

    Runs on the code view, so an identifier that only appears inside a literal
    body is text, not a read.
    """
    try:
        blank = code_view(expr)
    except KotlinLexError:
        return set()
    out = set()
    for m in re.finditer(r"[A-Za-z_]\w*", blank):
        name = m.group(0)
        if blank[: m.start()].rstrip().endswith("."):
            continue                      # member access, not a root
        if blank[m.end():].lstrip().startswith("("):
            continue                      # call target
        if name in KEYWORDS:
            continue
        out.add(name)
    return out


def paren_text(text, open_idx):
    """Text inside the balanced (…) starting at open_idx, or None."""
    close = match_bracket(text, open_idx)
    return None if close < 0 else text[open_idx + 1: close]


def brace_text(text, open_idx):
    """Text inside the balanced {…} at open_idx, or None (callers fail-closed).

    `text` must be the code view: literals are already blanked, so a brace in
    a string cannot unbalance the walk.
    """
    close = match_bracket(text, open_idx)
    return None if close < 0 else text[open_idx + 1: close]

def path_interpolations(lit):
    """The `$…` holes of a template literal, PATH part only (before the first
    `?` — query args are not path segments and have their own encoders)."""
    body = lit[1:-1] if lit.endswith('"') else lit[1:]
    q = body.find("?")
    if q >= 0:
        body = body[:q]
    out = []
    i = 0
    while i < len(body):
        if body[i] != "$":
            i += 1
            continue
        if i + 1 < len(body) and body[i + 1] == "{":
            depth, j = 0, i + 2
            while j < len(body):
                if body[j] == "{":
                    depth += 1
                elif body[j] == "}":
                    if depth == 0:
                        break
                    depth -= 1
                j += 1
            out.append(body[i + 2: j])
            i = j + 1
            continue
        m = re.match(r"[A-Za-z_]\w*", body[i + 1:])
        if m:
            out.append(m.group(0))
            i += 1 + len(m.group(0))
            continue
        i += 1
    return out

def string_params(sig):
    """Builder parameters declared with type String / String? — exactly the
    values that carry an id and must therefore be encoded."""
    return {m.group(1) for m in re.finditer(r"(\w+)\s*:\s*String\b", sig)}


def launched_effects(code):
    """[(lineno, key_expr, block_body)] for every `LaunchedEffect(<key>) { … }`.
    `code` must be the code view."""
    out = []
    for m in re.finditer(r"LaunchedEffect\s*\(", code):
        arg = paren_text(code, m.end() - 1)
        if arg is None:
            continue                      # unbalanced -> caller fail-closed
        close = m.end() + len(arg) + 1
        brace = code.find("{", close)
        if brace < 0:
            continue
        body = brace_text(code, brace)
        if body is None:
            continue
        out.append((code[: m.start()].count("\n") + 1, arg, body))
    return out


def remember_decl(env, field):
    """(keys, initializer) of `var <field> by remember(<keys>) { … }`, else None.
    `env` must be the code view."""
    m = re.search(rf"var\s+{field}\s+by\s+remember\s*\(", env)
    if not m:
        return None
    keys = paren_text(env, m.end() - 1)
    if keys is None:
        return None
    close = m.end() + len(keys) + 1
    brace = env.find("{", close)
    if brace < 0:
        return None
    init = brace_text(env, brace)
    if init is None:
        return None
    return keys, init


def case_block(code, case_re):
    """Body of the `is … -> { … }` branch matching case_re, or None.

    `code` must be the code view; the block extent comes from `match_bracket`.
    """
    m = re.search(case_re, code)
    if not m:
        return None
    brace = code.find("{", m.end())
    if brace < 0:
        return None
    return brace_text(code, brace)

# [da1024-E] A second decode of a route argument. Builder-side Uri.encode is
# NOT covered (the nav graph must keep encoding when it builds a route).
DECODE_RE = re.compile(r"(?:android\.net\.|java\.net\.)?(?:Uri|URLDecoder)\.decode\s*\(")

# [da1029-A] Every DeepLinkAction subclass the cold-start dispatcher must name.
# Written out literally on purpose: deriving it from the source would make the
# criterion vacuous (the file could silently drop a branch and the expectation
# would follow it down).
COLD_START_ACTION_CASES = [
    "OpenShare", "OpenAlarmList", "OpenTerminal",
    "CreateEnvironmentVariable", "OpenPermissionSettings", "OpenSession",
    "NewChat", "NewCameraChat", "OpenSettingsScreen",
    "OpenHtmlPreview", "Unknown",
]


def when_blocks(code: str, header: str) -> "list[str]":
    """Brace-bounded text of every `when (<header>) { … }` occurrence.

    `code` must be the code view, so a `{`/`}` inside a string cannot unbalance
    the walk; the block extent is the shared `match_bracket`. Returns [] when
    the header is absent — callers treat that as a failure (fail-closed),
    never as a skip.
    """
    blocks = []
    for m in re.finditer(re.escape(header) + r"\s*\{", code):
        brace = m.end() - 1
        close = match_bracket(code, brace)
        if close < 0:
            continue
        blocks.append(code[m.start(): close + 1])
    return blocks

def is_comment_line(line: str) -> bool:
    s = line.strip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*")

def code_lines(src: str):
    """(1-based lineno, text) for every non-comment line.

    code_only() drops comment lines, so it shifts line numbers; the criteria
    below report positions a human can grep, so they walk the raw lines and
    skip comments themselves.
    """
    return [(i + 1, l) for i, l in enumerate(src.split("\n")) if not is_comment_line(l)]

def composable_blocks(indexed):
    """[(start_lineno, [(lineno, text), ...])] for every `composable(...) { }`.

    A block ends at the first following `}` that sits at the opener's own
    indentation, so the scan cannot bleed into the next composable's body.
    """
    blocks = []
    i = 0
    while i < len(indexed):
        lineno, line = indexed[i]
        if line.strip().startswith("composable("):
            indent = len(line) - len(line.lstrip())
            j = i + 1
            while j < len(indexed):
                text = indexed[j][1]
                if text.strip() == "}" and (len(text) - len(text.lstrip())) == indent:
                    break
                j += 1
            blocks.append((lineno, indexed[i:j + 1]))
            i = j + 1
        else:
            i += 1
    return blocks


def read(root: Path, rel: str) -> str:
    p = root / rel
    if not p.exists():
        return ""
    return p.read_text(encoding="utf-8")


# ── [batch6-t06] effect judges ────────────────────────────────────────────
# The witnesses in this batch all keep the SPELLING and move the EFFECT: a
# label that is no longer matched, an id that left the literal, a key that no
# longer re-reads the state, a launch that cannot run. These helpers read the
# value / the control flow instead of the text.

ENCODER_RE = re.compile(
    r"\b(?:seg|enc|encode|URLEncoder\s*\.\s*encode|Uri\s*\.\s*encode)\s*\("
    r"|\s\.\s*replace\s*\(")


def _is_constant_expr(expr):
    """The expression's VALUE cannot depend on any state read."""
    e = strip_parens(expr.strip())
    if not e:
        return False
    if e in ("Unit", "Nothing"):
        return True
    if const_value(e) is not None:
        return True
    return is_pure_literal(e)


def _last_stmt(body):
    spans = stmt_spans(body)
    return body[spans[-1][0]:spans[-1][1]].strip() if spans else body.strip()


def key_parts(key_expr):
    """The top-level comma-separated key arguments of `f(a, b)`."""
    return [key_expr[a:b].strip() for a, b in split_top(key_expr, (",",))]


def expr_reads_state(expr, names):
    """Does this KEY expression's evaluated value still depend on `names`?

    `pendingCreate`                          -> True
    `sessionId, pendingCreate`               -> True  (per-part check)
    `Unit` / `true` / `0`                    -> False
    `pendingCreate == null && false`         -> False (folds to a constant)
    `pendingCreate.let { Unit }`             -> False (the lambda discards it)
    `pendingCreate.let { it.id }`            -> True  (the tail still reads it)

    This is the difference between "the state name appears somewhere" (which
    every witness in this batch keeps) and "the state is an actual
    recomposition key".
    """
    if not names:
        return False
    e = strip_parens(expr.strip())
    if not e:
        return False
    if const_bool(e) is not None or _is_constant_expr(e):
        return False
    if not any(contains_ident(e, n) for n in names):
        return False
    for scope_name, start, open_pos, end in scope_calls(e):
        if scope_name not in ("let", "run", "with", "takeIf", "takeUnless"):
            continue
        receiver = e[:start]
        tail = _last_stmt(e[open_pos + 1:end - 1])
        if any(contains_ident(receiver, n) for n in names) and _is_constant_expr(tail):
            # the receiver is consumed by this lambda; only what sits OUTSIDE
            # the call can still read the state
            head_rest = re.sub(r"[\w.]+\s*$", "", e[:start])
            if not any(contains_ident(head_rest + " " + e[end:], n) for n in names):
                return False
    return True


def _is_literal_operand(expr):
    """Operand carries no identifier reads (a literal / a separator)."""
    return not ident_roots(expr)


def concat_operands(defn, params):
    """Ids spliced into the route string with `+` instead of a template hole.

    `"a/${seg(x)}/" + y` compiles to the same bytes as `"a/${seg(x)}/$y"` but
    the raw id sits OUTSIDE the literal, where a template-hole scan cannot see
    it. A String parameter read by a `+` chain is therefore treated exactly
    like an un-encoded hole; one hop through a local is followed, and a local
    built by an encoder (`seg`/`enc`/`URLEncoder.encode`/`.replace`) is the
    documented pre-encoded exception.
    """
    out = []
    if not params:
        return out
    for s_start, s_end in stmt_spans(defn):
        stmt = defn[s_start:s_end]
        parts = split_top(stmt, ("+",))
        if len(parts) < 2:
            continue
        if not any(_is_literal_operand(stmt[a:b]) for a, b in parts):
            continue                      # numeric / non-string arithmetic
        for a, b in parts:
            op = strip_parens(stmt[a:b].strip())
            if not op or _is_literal_operand(op):
                continue
            if re.match(r"seg\s*\(", op):
                continue                  # encoded through the single helper
            head = re.match(r"[A-Za-z_]\w*", op)
            root = head.group(0) if head else ""
            if root in params:
                out.append(op)
                continue
            wr = last_write(defn, root) if root else None
            if wr and any(contains_ident(wr[1], p) for p in params) \
                    and not ENCODER_RE.search(wr[1]):
                out.append(op)
    return out


def dispatch_labels(code, lit, fn_name, subject):
    """The string labels MATCHED by `when (<subject>)` inside `fn_name`.

    A label routes the link; the same bytes in a log line, in a dead
    `.also { }` body, or in a commented-out branch do not. Returns None when
    the function or its dispatch is gone — callers fail-closed.
    """
    fb = function_body(code, fn_name)
    if fb is None:
        return None
    start, end, _kind = fb
    m = re.search(r"\bwhen\s*\(\s*" + re.escape(subject) + r"\s*\)\s*\{",
                  code[start:end])
    if not m:
        return None
    brace = start + m.end() - 1
    close = match_bracket(code, brace)
    if close < 0:
        return None
    block = code[brace + 1:close]
    block_lit = lit[brace + 1:close]
    labels = set()
    depth = 0
    i = 0
    while i < len(block):
        ch = block[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "-" and depth == 0 and block.startswith("->", i):
            run = _label_run_start(block, block_lit, i)
            for lm in re.finditer(r'"((?:[^"\\]|\\.)*)"', block_lit[run:i]):
                labels.add(lm.group(1))
            i += 2
            continue
        i += 1
    return labels


def _label_run_start(block, block_lit, arrow):
    """Start of the match-label list ending at `arrow` (labels may wrap)."""
    start = block.rfind("\n", 0, arrow) + 1
    while start > 0:
        prev_end = start - 1
        prev_start = block.rfind("\n", 0, prev_end) + 1
        if "->" in block[prev_start:prev_end]:
            break
        prev_lit = block_lit[prev_start:prev_end]
        if not re.fullmatch(
                r'\s*"(?:[^"\\]|\\.)*"\s*(?:,\s*"(?:[^"\\]|\\.)*"\s*)*,\s*',
                prev_lit):
            break
        start = prev_start
    return start


def live_launch(code, ident):
    """Is there a `startActivity(<ident>)` that can actually RUN?

    Presence is not the effect: `if (false) startActivity(x)` and an early
    `return` in front of the call both keep the substring and drop the launch.
    """
    pat = (r"startActivity\s*\(\s*%s\s*\)" % re.escape(ident)) if ident \
        else r"startActivity\s*\([^)]*(?:Action|ACTION_SHOW_ALARMS)"
    for m in re.finditer(pat, code):
        pos = m.start()
        if not path_feasible(code, pos):
            continue                      # dead branch / constant guard
        call_depth = brace_depth(code, pos)
        dead = False
        for r in re.finditer(r"\breturn\b(@\s*[A-Za-z_]\w*)?", code[:pos]):
            if brace_depth(code, r.start()) <= call_depth:
                dead = True
                break
        if not dead:
            return True
    return False


# ── [r3-t21] dispatch-arm EFFECT judges ───────────────────────────────────
# The round-3 witnesses attack the SEAM BETWEEN two gates: one gate pins its
# own spelling anchor (a branch NAME, a `.collectAsState()` call, a
# `runCatching` backstop) and the other pins a different one, so a patch that
# keeps every spelling and drops the EFFECT passes both. These judges read the
# dispatch arm / consumer effect as control flow instead:
#   * the coordinator write must EXIST, be REACHABLE and be fed from the action;
#   * the navigation must EXIST and be REACHABLE;
#   * the consumed request must be turned into the visible state it exists for;
#   * the permission branch must have the polarity and the request branch that
#     make the shortcut work in both permission states.

REL_COLD = "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/ColdStartDispatch.kt"

# A `when` match label list: `is DeepLinkAction.X`, a string label, `null`, `else`.
LABEL_TOKEN_RE = re.compile(
    r"\s*(?:is\s+[A-Za-z_][\w.]*|\"[^\"\\]*\"|null|else)\s*,?\s*$")
NAV_CALL_RE = re.compile(r"(?<![\w])(?:safeNavigate|navigate)\s*\(")


def _labels_of(text):
    """Case labels written in one arm's label run."""
    labels = set(re.findall(r"\bis\s+([A-Za-z_][\w.]*)", text))
    if re.search(r"(?<![\w])null(?![\w])", text):
        labels.add("null")
    if re.search(r"(?<![\w])else(?![\w])", text):
        labels.add("else")
    return frozenset(labels)


def _arm_label_run_start(text, floor, arrow):
    """Start of the match-label run that ends at `arrow` (labels may wrap)."""
    i = arrow
    while True:
        head = text.rfind("\n", floor, i) + 1
        if LABEL_TOKEN_RE.fullmatch(text[head:i]):
            i = head
            while i > floor and text[i - 1] in " \t":
                i -= 1
            continue
        break
    return max(i, floor)


def when_arm_bodies(block):
    """[(labels, body_text, braced)] for every depth-0 arm of `when (…) {…}`.

    Depth tracking keeps a NESTED `when (action)` (the warm-start camera
    selector inside the NewChat arm) inside its parent arm instead of reading
    its arrows as siblings. `block` must be the code view.
    """
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
        labels = _labels_of(body_all[prev_end:arrow])
        nxt = (_arm_label_run_start(body_all, arrow + 2, arrows[k + 1])
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


def dispatch_arms(code, subject):
    """[(labels, body, braced)] of the deep-link dispatch `when (<subject>)`.

    Selected by its dispatch-only OpenTerminal case, so a neighbouring
    `when (<subject>)` resolver (quickActionStart, `else -> null`) stays out of
    scope. Absence / ambiguity -> None (callers fail-closed).
    """
    blocks = [b for b in when_blocks(code, "when (%s)" % subject)
              if "DeepLinkAction.OpenTerminal" in b]
    if len(blocks) != 1:
        return None
    return when_arm_bodies(blocks[0])


def arm_body(arms, case):
    for labels, body, braced in arms:
        if case in labels:
            return body, braced
    return None, None


def _args_text(text, positions):
    """Concatenated argument text of the calls starting at `positions`."""
    out = []
    for pos in positions:
        open_pos = text.index("(", pos)
        spans = call_args(text, open_pos)
        out.append(" ".join(text[a:b] for a, b in spans))
    return " ".join(out)


# (file, dispatch subject, case, coordinator setter, payload fields, headline)
PAYLOAD_ARMS = [
    (REL_MAIN, "action", "DeepLinkAction.CreateEnvironmentVariable",
     "setPendingEnvVarCreate", ("key", "value", "note"),
     "warm-start env-var create"),
    (REL_MAIN, "action", "DeepLinkAction.OpenHtmlPreview",
     "setPendingHtmlPreview", ("sessionId", "resourcePath", "title"),
     "warm-start HTML preview"),
    (REL_NAV, "initialDeepLink", "DeepLinkAction.CreateEnvironmentVariable",
     "setPendingEnvVarCreate", ("key", "value", "note"),
     "cold-start env-var create"),
]

# arms whose whole purpose is to open something; a dead guard must not hide it
NAVIGATING_ARMS = [
    (REL_MAIN, "action", (
        "DeepLinkAction.OpenSession", "DeepLinkAction.OpenTerminal",
        "DeepLinkAction.CreateEnvironmentVariable", "DeepLinkAction.OpenHtmlPreview",
        "DeepLinkAction.OpenPermissionSettings",
    )),
    (REL_NAV, "initialDeepLink", (
        "DeepLinkAction.OpenSession", "DeepLinkAction.OpenTerminal",
        "DeepLinkAction.CreateEnvironmentVariable",
        "DeepLinkAction.OpenPermissionSettings",
    )),
]


def judge_dispatch_effects(sources):
    """[r3-t21] criteria 12/13 — every dispatch arm DELIVERS and NAVIGATES."""
    for rel, subject, case, setter, fields, headline in PAYLOAD_ARMS:
        code = sources.get(rel)
        if not code:
            continue                      # missing file already failed earlier
        arms = dispatch_arms(code, subject)
        if arms is None:
            fail(rel, "deep-link dispatch `when (%s)` not found or ambiguous — "
                      "fail-closed: criterion 12 pins the arm that DELIVERS the "
                      "%s payload" % (subject, headline))
            continue
        body, braced = arm_body(arms, case)
        if body is None:
            fail(rel, "`is %s ->` arm not found in the deep-link dispatch — the "
                      "%s deep link stops delivering its payload" % (case, headline))
            continue
        if not braced:
            fail(rel, "`is %s ->` arm is not a brace block — fail-closed: criterion 12 "
                      "judges the arm BODY" % case)
            continue
        live = live_positions(body, r"(?<![\w])%s\s*\(" % setter)
        if not live:
            fail(rel, "`is %s ->` arm never reaches `%s(` on a reachable path — the "
                      "screen opens but the %s payload is dropped (warm-start and "
                      "cold-start parities must BOTH deliver)"
                      % (case, setter, headline))
            continue
        args = _args_text(body, live)
        missing = [f for f in fields if not re.search(r"\.\s*%s\b" % f, args)]
        if missing:
            fail(rel, "`%s(` in the `%s` arm does not read %s off the action — a partial "
                      "or hardcoded payload loses those fields"
                      % (setter, case, ", ".join(missing)))
            continue
        if not re.search(r"(?<![\w])%s\s*\." % re.escape(subject), args):
            fail(rel, "`%s(` in the `%s` arm is fed from something other than the "
                      "deep-link action (`%s.…`), so the request carries no payload"
                      % (setter, case, subject))

    for rel, subject, cases in NAVIGATING_ARMS:
        code = sources.get(rel)
        if not code:
            continue
        arms = dispatch_arms(code, subject)
        if arms is None:
            fail(rel, "deep-link dispatch `when (%s)` not found or ambiguous — "
                      "fail-closed: criterion 13 pins navigation reachability" % subject)
            continue
        for case in cases:
            body, braced = arm_body(arms, case)
            if body is None or not braced:
                continue                  # arm presence is criterion 11's job
            sites = [m.start() for m in NAV_CALL_RE.finditer(body)]
            if not sites:
                fail(rel, "`is %s ->` arm carries no navigation call — the deep link "
                          "lands on whatever was already on screen" % case)
                continue
            if not [p for p in sites if path_feasible(body, p)]:
                fail(rel, "every navigation call in the `%s` arm sits behind a dead "
                          "guard (constant `if (…)`, `if (false)`) — the arm runs and "
                          "nothing opens" % case)


def judge_html_preview_delivery(chat, rel):
    """[r3-t21] criterion 14 — the consumed preview must become VISIBLE state."""
    target = None
    for lineno, key, blk in launched_effects(chat):
        if "consumePendingHtmlPreview(" in blk:
            target = (lineno, key, blk)
            break
    if target is None:
        fail(rel, "no LaunchedEffect consumes consumePendingHtmlPreview() — fail-closed: "
                  "criterion 14 pins the preview a deep link opens")
        return
    lineno, _key, blk = target
    holder = False
    for m in re.finditer(r"\bhtmlPreviewHolder\s*=(?!=)", blk):
        if not path_feasible(blk, m.start()):
            continue
        rhs = strip_parens(blk[m.end():expr_end(blk, m.end())].strip())
        if not re.search(r"\bWebViewHolder\s*\(", rhs):
            continue
        if is_pure_literal(rhs):
            continue
        holder = True
    if not holder:
        fail(rel, f"the pendingHtmlPreview consumer (LaunchedEffect at line {lineno}) "
                  "does not build the preview holder on a reachable path — the deep link "
                  "consumes the request and shows nothing")
        return
    fullscreen = False
    for m in re.finditer(r"\bhtmlPreviewFullscreen\s*=(?!=)", blk):
        if not path_feasible(blk, m.start()):
            continue
        rhs = strip_parens(blk[m.end():expr_end(blk, m.end())].strip())
        if const_bool(rhs) is True:
            fullscreen = True
    if not fullscreen:
        fail(rel, f"the pendingHtmlPreview consumer (LaunchedEffect at line {lineno}) "
                  "never sets `htmlPreviewFullscreen = true` on a reachable path — the "
                  "holder is built and the preview still never opens")


def judge_camera_permission_polarity(chat, rel):
    """[r3-t21] criterion 15 — the camera quick action works in BOTH states."""
    target = None
    for lineno, key, blk in launched_effects(chat):
        if "consumePendingChatAction(" in blk:
            target = (lineno, key, blk)
            break
    if target is None:
        fail(rel, "no LaunchedEffect consumes consumePendingChatAction() — fail-closed: "
                  "criterion 15 pins the camera permission gate")
        return
    lineno, _key, blk = target
    gate = None
    innermost = None
    for cond, t_start, t_end, e_start, e_end in if_blocks(blk):
        if "launchCamera(" not in blk[t_start:t_end]:
            continue
        # an OUTER `if (state == OPEN_CAMERA)` also contains the call; only a
        # condition that reads the permission state can be the gate
        cond_c = strip_parens(cond.strip())
        if not (contains_ident(cond_c, "granted") or "PERMISSION" in cond_c
                or cond_c.startswith("!")):
            continue
        # the gate is the INNERMOST such if whose then-arm launches the camera
        if innermost is not None and (t_end - t_start) >= innermost[0]:
            continue
        els = blk[e_start:e_end] if e_start is not None and e_end is not None else None
        innermost = (t_end - t_start, cond, els)
    if innermost is not None:
        gate = (innermost[1], innermost[2])
    if gate is None:
        fail(rel, f"the camera quick action (LaunchedEffect at line {lineno}) launches the "
                  "camera outside any permission branch — an ungranted start throws and "
                  "the shortcut is dead")
        return
    cond, els = gate
    c = strip_parens(cond.strip())
    if c.startswith("!") and const_bool(c) is None:
        fail(rel, f"the camera permission polarity is inverted (`if ({cond.strip()}) "
                  f"launchCamera()` at line {lineno}) — the camera opens only when the "
                  "permission is DENIED, so an already-granted shortcut does nothing")
        return
    if const_bool(c) is not None:
        fail(rel, f"the camera permission guard is a constant (`if ({cond.strip()})` at "
                  f"line {lineno}) — the permission state is never consulted")
        return
    if not (contains_ident(c, "granted") or "PERMISSION_GRANTED" in c):
        fail(rel, f"the camera permission guard (`if ({cond.strip()})` at line {lineno}) "
                  "does not read the granted state")
        return
    asks = bool(re.search(r"cameraPermissionLauncher\s*\.\s*launch\s*\(", els or "")) \
        and "CAMERA" in (els or "")
    # a named request helper is the same effect as the launcher lambda; a
    # permission *check* is not, so the helper must read as request/ask/launch
    asks = asks or bool(re.search(r"(?<![\w])(?:request|ask|launch)\w*"
                                  r"(?:Permission|Camera)\w*\s*\(", els or ""))
    if els is None or not asks:
        fail(rel, f"the camera quick action (line {lineno}) has no request-the-permission "
                  "branch for the denied case — the shortcut dies silently whenever the "
                  "permission is not already granted")


def judge_cold_start_draft_wiring(nav, cold):
    """[r3-t21] criterion 16 — the cold-start new-chat path mints a REAL draft id."""
    if not cold:
        fail(REL_COLD, "missing source file (fail-closed: this file hosts the cold-start "
                       "route decision)")
        return
    fb = function_body(cold, "coldStartStartDestination")
    if fb is None:
        fail(REL_COLD, "coldStartStartDestination( not found — fail-closed: criterion 16 "
                       "pins the cold-start draft minting")
    else:
        start, end, _kind = fb
        defn = cold[start:end]
        calls = [m.start() for m in re.finditer(r"\bchatRoute\s*\(", defn)]
        if not calls:
            fail(REL_COLD, "coldStartStartDestination never builds a route through "
                           "chatRoute(…) — fail-closed")
        for pos in calls:
            # Only the DRAFT-producing branches owe a minted id. The pinned
            # shortcut and OpenSession name a REAL session (no draft is created),
            # and requiring nextDraftId() there would be a false positive.
            conds = " ".join(c for _sub, c, lo, hi in when_branches(defn)
                             if lo <= pos <= hi)
            guards = " ".join(c for _k, c in enclosing_conditions(defn, pos))
            scope = conds + " " + guards
            if re.search(r"htmlShortcut|OpenSession", scope):
                continue          # names a REAL session: no draft is minted here
            if scope.strip() and not re.search(
                    r"NewCameraChat|NewChat|(?:^|\W)else(?:\W|$)", scope):
                continue
            spans = call_args(defn, defn.index("(", pos))
            arg = " ".join(defn[a:b] for a, b in spans)
            if not any(re.search(r"\bnextDraftId\s*\(", s) for s in value_sources(arg)):
                fail(REL_COLD, "coldStartStartDestination hands chatRoute(%r) a route that "
                               "is not minted through nextDraftId() — a constant draft id "
                               "orphans the draft slot and saveText drops the user's text"
                               % arg.strip()[:60])
    m = re.search(r"\bcoldStartStartDestination\s*\(", nav)
    if not m:
        fail(REL_NAV, "the cold-start route decision no longer runs through "
                      "coldStartStartDestination(…) — fail-closed: criterion 16 pins the "
                      "injected nextDraftId/seed* wiring")
        return
    named = {}
    for a, b in call_args(nav, nav.index("(", m.start())):
        piece = nav[a:b]
        nm = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", piece)
        if nm:
            named[nm.group(1)] = piece[nm.end():].strip()
    nid = named.get("nextDraftId")
    if nid is None:
        fail(REL_NAV, "coldStartStartDestination(…) is called without a nextDraftId lambda")
    elif not any(re.search(r"\bnextDraftId\s*\(", s) for s in value_sources(nid)):
        fail(REL_NAV, "the injected `nextDraftId` does not come from "
                      "ComposerDraftStore.nextDraftId(…): nextDraftId = %r — a constant id "
                      "orphans the draft slot (saveText then drops the text)"
                      % nid[:80])
    seed_html = named.get("seedHtmlPreview")
    if seed_html is None:
        fail(REL_NAV, "coldStartStartDestination(…) is called without a seedHtmlPreview "
                      "lambda — a cold-start HTML-preview shortcut opens an empty chat")
    elif not re.search(r"\bsetPendingHtmlPreview\s*\(", seed_html):
        fail(REL_NAV, "the injected `seedHtmlPreview` never calls "
                      "setPendingHtmlPreview(…) — the cold-start preview request is "
                      "consumed and dropped")
    seed_cam = named.get("seedCamera")
    if seed_cam is None:
        fail(REL_NAV, "coldStartStartDestination(…) is called without a seedCamera lambda")
    elif not re.search(r"\bsetPendingChatAction\s*\(", seed_cam):
        fail(REL_NAV, "the injected `seedCamera` never calls setPendingChatAction(…) — a "
                      "cold-start camera shortcut opens the chat without the camera")
    host = re.search(r"\bNavHost\s*\(", nav)
    if not host:
        fail(REL_NAV, "NavHost( not found — fail-closed: criterion 16 cannot tell whether "
                      "the cold-start decision reaches the host")
        return
    start_arg = None
    for a, b in call_args(nav, nav.index("(", host.start())):
        piece = nav[a:b]
        nm = re.match(r"\s*startDestination\s*=(?!=)", piece)
        if nm:
            start_arg = piece[nm.end():].strip()
    if not start_arg:
        fail(REL_NAV, "NavHost has no `startDestination =` argument — fail-closed")
        return
    decl = re.search(r"(?m)^[ \t]*(?:val|var)\s+%s\s*(?::[^=\n]+)?=(?!=)"
                     % re.escape(start_arg), nav)
    rhs = nav[decl.end():expr_end(nav, decl.end())] if decl else ""
    if "coldStartStartDestination(" not in rhs:
        fail(REL_NAV, "`NavHost(startDestination = %s)` does not come from "
                      "coldStartStartDestination(…) — the cold-start decision is computed "
                      "and thrown away" % start_arg)


def check(root: Path) -> None:
    CHECKS[0] += 1
    dlh_raw = read(root, REL_DLH)
    nav_raw = read(root, REL_NAV)
    main_raw = read(root, REL_MAIN)
    if not dlh_raw:
        fail(REL_DLH, "missing source file")
        return
    if not nav_raw:
        fail(REL_NAV, "missing source file")
    if not main_raw:
        fail(REL_MAIN, "missing source file")
    dlh_views = views(REL_DLH, dlh_raw, "the settings deep-link resolver")
    nav_views = views(REL_NAV, nav_raw, "the navigation graph")
    main_views = views(REL_MAIN, main_raw, "MainActivity")
    if dlh_views[0] is None or nav_views[0] is None or main_views[0] is None:
        return
    dlh, dlh_lit = dlh_views
    nav, nav_lit = nav_views
    main = main_views[0]

    # --- 1. emitter whitelist coverage: the heads must be MATCH LABELS ---
    # [batch6-t06] The old test was `head in dlh`: a substring hit anywhere in
    # the file satisfied it, so moving "mcp-servers" from the branch labels into
    # a dead `.also { "mcp-servers" }` body (or a log line) kept the gate green
    # while the OAuth round-trip fell through to Settings home. A whitelist
    # entry is a label of the `when (head)` dispatch and nothing else.
    labels = dispatch_labels(dlh, dlh_lit, "resolveSettingsDeepLink", "head")
    if labels is None:
        fail(REL_DLH,
             "resolveSettingsDeepLink's `when (head)` settings dispatch not found — "
             "fail-closed: criterion 1 pins the whitelist as `when` MATCH LABELS "
             "(re-anchor this criterion if the resolver was restructured, do not drop it)")
    else:
        for head in EMITTER_HEADS:
            if head not in labels:
                fail(REL_DLH,
                     f"emitter head '{head}' is not a `when (head)` match label in "
                     "resolveSettingsDeepLink — the emitted minis://settings/"
                     f"{head} link falls through to the Settings-home fallback "
                     "(a quote that survives elsewhere in the file — a log line, a dead "
                     "`.also { }` body, a commented-out branch — does not route it)")
        # live cross-check against the actual Python emitter (SKIP-able: the gate
        # must not depend on asset directory structure at runtime)
        http_py = read(root, REL_HTTP_PY)
        if http_py:
            emitted = sorted(set(re.findall(r"minis://settings/([a-zA-Z0-9_\-]+)", http_py)))
            for head in emitted:
                if head not in labels:
                    fail(REL_HTTP_PY,
                         f"emitted head '{head}' is not a `when (head)` match label in "
                         "DeepLinkHandler.resolveSettingsDeepLink — the link this emitter "
                         "sends cannot reach its screen")
        else:
            print(f"SKIP: emitter cross-check — {REL_HTTP_PY} not found "
                  "(hardcoded list still enforced)")

    # --- 2. segment builders encode through the single helper ---
    # Judge the ROUTE VALUE, not the presence of helper calls: every id that
    # reaches a path segment must ride seg(), whether it arrives as a template
    # hole (`"…/${seg(a)}/$b"`) or as a `+` operand (`"…/${seg(a)}/" + b`).
    # Both compile to the same bytes; the second one hides the raw id outside
    # the literal, where a hole scan cannot see it.
    nav_spans = string_literals(nav_raw)
    for name in SEG_BUILDERS:
        fb = function_body(nav, name)
        m = re.search(rf"fun {name}\(", nav)
        if fb is None or not m:
            fail(REL_NAV, f"Routes.{name} builder not found")
            continue
        b_start, b_end, _kind = fb
        defn = nav[b_start:b_end]          # code view: `+` chains, statements
        defn_lit = nav_lit[b_start:b_end]  # literal view: template holes
        sig = nav[m.start():b_start]
        if "seg(" not in defn_lit:
            fail(REL_NAV,
                 f"Routes.{name} does not encode through seg() (raw id -> malformed route)")
            continue
        params = string_params(sig)
        raw = []
        for a, b in nav_spans:
            if a < b_start or b > b_end:
                continue
            for hole in path_interpolations(nav_raw[a:b]):
                hole_s = hole.strip()
                if hole_s.startswith("seg(") and hole_s.endswith(")"):
                    continue
                base = re.split(r"[?.\s]", hole_s)[0]
                if base in params:
                    raw.append(hole_s)
        raw += concat_operands(defn, params)
        if raw:
            fail(REL_NAV,
                 f"Routes.{name} interpolates raw id(s) {sorted(set(raw))} into a path "
                 "segment — every id hole must ride seg(...); one encoded sibling no "
                 "longer satisfies the criterion, and an id concatenated with `+` "
                 "outside the literal is the same defect")

    # --- 3. fallback still present (deliberate design) ---
    if "else -> SettingsDeepLink.Route(Routes.SETTINGS)" not in dlh:
        fail(REL_DLH, "unknown-path fallback to Routes.SETTINGS is missing (deliberate design must not regress)")

    # --- 4. navigate backstop in MainActivity ---
    if "runCatching { nav.navigate(action.route) }" not in main:
        fail(REL_MAIN, "OpenSettingsScreen navigate lacks the runCatching backstop")

    # --- 5. [da1021-C] cold-start deep-link backstop in AppNavigation ---
    # The cold-start LaunchedEffect(initialDeepLink) branch runs after
    # awaitResumed(), so bare navigate() is safe there (da1020-F reasoning);
    # the runCatching is the never-crash backstop mirroring MainActivity.
    if "runCatching { navController.navigate(initialDeepLink.route) }" not in nav:
        fail(REL_NAV, "cold-start OpenSettingsScreen deep link lacks the runCatching backstop")

    # --- 6. [da1024-E] no second decode of a route argument ---
    # The Nav framework %-decodes every route path segment exactly once
    # (navigation-common 2.8.5 NavDeepLink.getMatchingPathArguments ->
    # `Uri.decode(matcher.group(index + 1))`). Anything the nav graph decodes
    # itself is therefore a SECOND decode: it rewrites an id carrying a literal
    # '%' ("100%25" -> "100%") and turns a bare '%' into U+FFFD — on Android
    # 13+ Uri.decode substitutes the replacement character instead of throwing
    # (android.net.UriCodec.decode(..., throwOnFailure = false)) — so the
    # destination's lookup misses and the screen pops straight back.
    for start, block in composable_blocks(code_lines(nav_raw)):
        for lineno, text in block:
            if DECODE_RE.search(text):
                fail(REL_NAV,
                     f"composable at line {start} decodes a route argument "
                     f"(line {lineno}): the Nav framework already "
                     "%-decodes each path segment once")

    # --- 7. [da1024-E] MODEL_ENTRY_DETAIL reads both args, fail-closed ---
    entry_blocks = [bl for _, bl in composable_blocks(code_lines(nav_raw))
                    if any("Routes.MODEL_ENTRY_DETAIL" in t for _, t in bl)]
    if not entry_blocks:
        fail(REL_NAV, "MODEL_ENTRY_DETAIL composable block not found")
    else:
        blk = "\n".join(t for _, t in entry_blocks[0])
        for arg in ("instanceId", "entryId"):
            if f'getString("{arg}")' not in blk:
                fail(REL_NAV, f"MODEL_ENTRY_DETAIL does not read '{arg}' via getString")
        if blk.count("?: return@composable") < 2:
            fail(REL_NAV,
                 "MODEL_ENTRY_DETAIL lost its `?: return@composable` fail-closed guard")

    # --- 8. [da1028-C] EnvironmentVariablesScreen observes its deep link ----
    # The deep-link create request is a StateFlow. A `LaunchedEffect(Unit)` +
    # `.value` read fires only on first composition, so a request delivered to
    # a screen that is already up (warm start — the Activity is not recreated)
    # is silently dropped. The screen must subscribe to the flow instead.
    env_raw = read(root, REL_ENV_SCREEN)
    if not env_raw:
        fail(REL_ENV_SCREEN,
             "missing source file (fail-closed: this file hosts criteria 8/10)")
    else:
        env_views = views(REL_ENV_SCREEN, env_raw, "EnvironmentVariablesScreen")
        env = env_views[0]
        if env is not None:
            if not re.search(r"pendingEnvVarCreate\s*\.\s*collectAsState\s*\(", env):
                fail(REL_ENV_SCREEN,
                     "pendingEnvVarCreate is not observed through collectAsState() — the "
                     "screen cannot see a request that arrives after first composition")
            if not re.search(r"\bconsumePendingEnvVarCreate\s*\(", env):
                fail(REL_ENV_SCREEN,
                     "consumePendingEnvVarCreate() call not found (fail-closed: criterion 8 "
                     "pins the one-shot-vs-reactive shape, re-derive it if the consume path "
                     "was renamed)")
            else:
                # [batch6-t06] Judge the KEY's value, not the spelling of the
                # key: `pendingCreate.let { Unit }` keeps the identifier and
                # discards it, so the runtime key is the constant `Unit` and a
                # warm-start request is dropped exactly like `LaunchedEffect(Unit)`.
                collected = re.findall(
                    r"(\w+)\s+by\s+[\w.]*\s*\.\s*pendingEnvVarCreate\s*\."
                    r"\s*collectAsState\s*\(", env)
                reactive = 0
                consumers = 0
                for lineno, key, blk in launched_effects(env):
                    if "consumePendingEnvVarCreate(" not in blk:
                        continue
                    consumers += 1
                    if any(expr_reads_state(p, collected) for p in key_parts(key)):
                        reactive += 1
                        continue
                    fail(REL_ENV_SCREEN,
                         f"LaunchedEffect({key.strip()}) at line {lineno} still consumes the "
                         "pending create request — a one-shot effect never re-runs, so a "
                         "warm-start deep link into an already-composed screen is dropped "
                         "(the key must EVALUATE to the observed state, not merely mention it)")
                if consumers == 0:
                    fail(REL_ENV_SCREEN,
                         "no LaunchedEffect keyed on the observed pending-create state consumes "
                         "consumePendingEnvVarCreate() — the reactive binding is gone "
                         "(fail-closed: re-derive this criterion, never drop it)")
            # 10. the form's four shadow copies must be keyed on their source
            if "fun EnvVarFormSheet(" not in env:
                fail(REL_ENV_SCREEN,
                     "EnvVarFormSheet( not found (fail-closed: criterion 10 pins its field "
                     "seeding)")
            else:
                for field in ENV_FORM_FIELDS:
                    decl = remember_decl(env, field)
                    if decl is None:
                        fail(REL_ENV_SCREEN,
                             f"`var {field} by remember(...)` not found — the field is keyless "
                             "or renamed (fail-closed: it must be keyed on what it seeds from)")
                        continue
                    keys, init = decl
                    if not keys.strip():
                        fail(REL_ENV_SCREEN,
                             f"`var {field} by remember()` carries no keys — it keeps the first "
                             "entry's text after editEntry switches identity, and Save writes "
                             "that stale text back over an external write")
                        continue
                    # [batch6-t06] "The root identifier appears in the key list"
                    # is not the contract: `remember(editEntry.let { 0 })` keeps
                    # the identifier and gives the composer the constant `0`, so
                    # the field never re-seeds. Require every seed the
                    # initializer READS to be read by a key whose VALUE depends
                    # on it.
                    seeds = ident_roots(init)
                    parts = key_parts(keys)
                    missing = sorted(s for s in seeds
                                     if not any(expr_reads_state(p, [s]) for p in parts))
                    if missing:
                        fail(REL_ENV_SCREEN,
                             f"`var {field} by remember({keys.strip()})` is not keyed on "
                             f"{', '.join(missing)} — the initializer seeds from it, so the "
                             "field keeps the previous entry's/prefill's value instead of "
                             "re-seeding when it changes (a key that folds to a constant, "
                             "e.g. `x.let { 0 }`, is not a key on x)")

    # --- 9. [da1028-C] ChatScreen observes its pending quick action ---------
    chat_raw = read(root, REL_CHAT_SCREEN)
    if not chat_raw:
        fail(REL_CHAT_SCREEN,
             "missing source file (fail-closed: this file hosts criterion 9)")
    else:
        chat_views = views(REL_CHAT_SCREEN, chat_raw, "ChatScreen")
        chat = chat_views[0]
        if chat is not None:
            if not re.search(r"pendingChatAction\s*\.\s*collectAsState\s*\(", chat):
                fail(REL_CHAT_SCREEN,
                     "pendingChatAction is not observed through collectAsState() — a quick "
                     "action delivered to an already-composed ChatScreen never opens the camera")
            if re.search(r"pendingChatAction\s*\.\s*value\b", chat):
                fail(REL_CHAT_SCREEN,
                     "pendingChatAction is still read via a `.value` snapshot — that read only "
                     "happens when the effect first composes")
            if not re.search(r"pendingHtmlPreview\w*\s*\.\s*collectAsState\s*\(", chat):
                fail(REL_CHAT_SCREEN,
                     "pendingHtmlPreview lost its collectAsState() subscription (regression "
                     "anchor: this subscription is the shape criterion 9 mirrors, it must not "
                     "be 'simplified' back to .value)")
            # [batch6-t06] Criterion 8 got the key check in wide-1024; its twin
            # next door did not, so `LaunchedEffect(Unit)` still consumed the
            # state and a warm-start camera_chat was dropped. Same judge, same
            # code path: every effect that consumes the observed state must have
            # a key whose VALUE re-reads it.
            collected_chat = re.findall(
                r"(\w+)\s+by\s+[\w.]*\s*\.\s*pendingChatAction\s*\."
                r"\s*collectAsState\s*\(", chat)
            consumers = 0
            for lineno, key, blk in launched_effects(chat):
                if not any(contains_ident(blk, v) for v in collected_chat):
                    continue
                consumers += 1
                if any(expr_reads_state(p, collected_chat) for p in key_parts(key)):
                    continue
                fail(REL_CHAT_SCREEN,
                     f"LaunchedEffect({key.strip()}) at line {lineno} consumes "
                     "pendingChatAction but its key never re-evaluates it — a quick action "
                     "that lands after the first composition (minis://action/camera_chat on "
                     "an already-composed ChatScreen) is dropped silently")
            if collected_chat and consumers == 0:
                fail(REL_CHAT_SCREEN,
                     "no LaunchedEffect consumes the observed pendingChatAction state — the "
                     "reactive binding is gone (fail-closed: re-derive this criterion)")

    # --- 11. [da1029-A] cold-start dispatch names every DeepLinkAction case --
    # `minis://views/alarm` was a silent no-op on cold start: the sealed family
    # had 11 subclasses, the dispatch named 7 of them and swallowed the rest in
    # `else -> {}`, so a new subclass landed in a hole nobody could see.
    # (The neighbouring `quickActionStart` resolver keeps its `else -> null` —
    # it is selected out by requiring the dispatch-only OpenTerminal case.)
    dispatches = [
        b for b in when_blocks(nav, "when (initialDeepLink)")
        if "DeepLinkAction.OpenTerminal" in b
    ]
    if not dispatches:
        fail(REL_NAV,
             "cold-start `when (initialDeepLink)` dispatch block not found "
             "(renamed or restructured? re-anchor criterion 11, do not drop it)")
    else:
        dispatch = dispatches[0]
        for case in COLD_START_ACTION_CASES:
            if f"DeepLinkAction.{case}" not in dispatch:
                fail(REL_NAV,
                     f"cold-start dispatch has no `is DeepLinkAction.{case}` branch — "
                     "every sealed subclass must be named (an unnamed case falls into "
                     "the old catch-all hole, which is exactly how OpenAlarmList broke)")
        if re.search(r"\belse\s*->", dispatch):
            fail(REL_NAV,
                 "cold-start dispatch still ends in a catch-all `else ->` — name the "
                 "remaining subclasses explicitly so adding one is a compile error here "
                 "instead of a silent no-op")
        # The subject is `DeepLinkAction?`; with the catch-all gone the compiler
        # demands an explicit null branch ("'when' expression must be
        # exhaustive"). Mirror that at the source layer so the failure is caught
        # offline instead of on the CI compile pass.
        if re.search(r"initialDeepLink\s*:\s*DeepLinkAction\s*\?", nav) and \
                not re.search(r"(?:^|,)\s*null\s*->", dispatch, re.M):
            fail(REL_NAV,
                 "cold-start dispatch: `initialDeepLink` is nullable "
                 "(`DeepLinkAction?`) and the catch-all `else` is gone — the `when` "
                 "must name the `null` case explicitly (otherwise the compiler "
                 "rejects it: \"'when' expression must be exhaustive\")")
        # [batch6-t06] "The branch mentions ACTION_SHOW_ALARMS" only proves the
        # Intent was CONSTRUCTED. `if (false) startActivity(showAlarms)` (or an
        # early `return`) keeps the call text and launches nothing — judge the
        # side effect on the reachable path.
        alarm = case_block(dispatch, r"is\s+DeepLinkAction\.OpenAlarmList\s*->")
        if alarm is None:
            fail(REL_NAV,
                 "cold-start dispatch: `is DeepLinkAction.OpenAlarmList` branch must launch "
                 "AlarmClock.ACTION_SHOW_ALARMS (minis://views/alarm was a cold-start "
                 "no-op; parity with MainActivity's warm-start handler)")
        elif "AlarmClock.ACTION_SHOW_ALARMS" not in alarm:
            fail(REL_NAV,
                 "cold-start dispatch: `is DeepLinkAction.OpenAlarmList` branch must launch "
                 "AlarmClock.ACTION_SHOW_ALARMS (minis://views/alarm was a cold-start "
                 "no-op; parity with MainActivity's warm-start handler)")
        else:
            built = re.search(r"val\s+(\w+)\s*=\s*[\w.]*Intent\s*\(", alarm)
            launched = live_launch(alarm, built.group(1) if built else None)
            if not launched:
                fail(REL_NAV,
                     "cold-start dispatch: the OpenAlarmList branch builds the "
                     "AlarmClock.ACTION_SHOW_ALARMS Intent but never hands it to "
                     "startActivity() on a reachable path — the tap is a silent no-op (a call "
                     "behind `if (false)` / after an early return does not launch)")

    # --- 12..16. [r3-t21] dispatch-arm EFFECT judges -----------------------
    # Round-3 witnesses live in the seam between two gates: every spelling
    # anchor here (branch name, collectAsState, runCatching) survived while the
    # arm stopped DELIVERING. These five judges read the effect, not the names.
    cold_raw = read(root, REL_COLD)
    chat_raw2 = read(root, REL_CHAT_SCREEN)
    cold_views = views(REL_COLD, cold_raw, "the cold-start route decision")
    chat_views2 = views(REL_CHAT_SCREEN, chat_raw2, "ChatScreen")
    if cold_views[0] is None or chat_views2[0] is None:
        fail(REL_COLD, "source view unavailable — fail-closed: criteria 12..16 cannot "
                       "judge the dispatch effects")
    else:
        judge_dispatch_effects({REL_MAIN: main, REL_NAV: nav})
        judge_html_preview_delivery(chat_views2[0], REL_CHAT_SCREEN)
        judge_camera_permission_polarity(chat_views2[0], REL_CHAT_SCREEN)
        judge_cold_start_draft_wiring(nav, cold_views[0])

# ---------------- selftest fixtures (minimal synthetic repo) ----------------

FIXTURE_DLH = '''package com.rikkaminis.app.deeplink

internal fun resolveSettingsDeepLink(path: String, query: Map<String, String>): SettingsDeepLink {
    val segments = path.trimStart('/').split('/').filter { it.isNotEmpty() }
    val head = segments.firstOrNull() ?: return SettingsDeepLink.Route(Routes.SETTINGS)
    return when (head) {
        "providers" -> SettingsDeepLink.Route(Routes.providerDetail(head))
        "mcp-servers", "mcp_servers" -> SettingsDeepLink.Route(Routes.MCP)
        "permissions" -> SettingsDeepLink.PermissionSettings
        "environments" -> SettingsDeepLink.Route(Routes.ENV_VARS)
        "memory" -> SettingsDeepLink.Route(Routes.MEMORY)
        else -> SettingsDeepLink.Route(Routes.SETTINGS)
    }
}
'''

FIXTURE_NAV = '''package com.rikkaminis.app.ui.navigation

object Routes {
    internal fun seg(v: String): String = android.net.Uri.encode(v)
    fun providerDetail(instanceId: String) = "provider/${seg(instanceId)}"
    fun providerConnection(instanceId: String) = "provider_connection/${seg(instanceId)}"
    fun modelGroupDetail(groupId: String) = "model_group/${seg(groupId)}"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/${seg(groupId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/${seg(instanceId)}"
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${seg(instanceId)}/${seg(entryId)}"
    fun scheduledTaskEdit(taskId: String?) =
        if (taskId == null) "scheduled_task_edit/-" else "scheduled_task_edit/${seg(taskId)}"
    fun skillDetail(skillId: String) = "skill/${seg(skillId)}"
    fun skillFile(skillId: String, relativePath: String): String {
        val encoded = relativePath.replace("+", "%20")
        return "skill_file/${seg(skillId)}/$encoded"
    }
    fun chatFiles(sessionId: String) = "chat_files/${seg(sessionId)}"
    fun mountedFoldersDetail(mountId: String) = "mounted_folders_detail/${seg(mountId)}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/${seg(folderId)}"
    fun logDetail(fileName: String) = "log_detail/${seg(fileName)}"
    fun sessionStorageDetail(sessionId: String) = "session_storage/${seg(sessionId)}"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/${seg(fileName)}/$isGlobal"
    fun chat(sessionId: String, focusMessageId: String? = null): String {
        val base = "chat/${seg(sessionId)}"
        if (focusMessageId.isNullOrBlank()) return base
        return "$base?focusMessageId=$focusMessageId"
    }
    fun mirrorCategoryDetail(categoryId: String) = "mirror_category/${seg(categoryId)}"
}

suspend fun handleColdStartDeepLink(navController: NavController, initialDeepLink: DeepLinkAction?) {
    when (initialDeepLink) {
        is DeepLinkAction.OpenTerminal -> {
            navController.navigate(Routes.terminal(initialDeepLink.initCommand))
        }
        is DeepLinkAction.OpenSession -> {
            navController.navigate(Routes.chat(initialDeepLink.sessionId))
        }
        is DeepLinkAction.CreateEnvironmentVariable -> {
            DeepLinkCoordinator.setPendingEnvVarCreate(
                initialDeepLink.key,
                initialDeepLink.value,
                initialDeepLink.note,
            )
            navController.navigate(Routes.ENV_VARS)
        }
        is DeepLinkAction.OpenSettingsScreen -> {
            runCatching { navController.navigate(initialDeepLink.route) }
                .onFailure { runCatching { navController.navigate(Routes.SETTINGS) } }
        }
        is DeepLinkAction.OpenPermissionSettings -> {
            navController.navigate(Routes.PERMISSIONS)
        }
        is DeepLinkAction.OpenHtmlPreview -> {}
        is DeepLinkAction.NewChat,
        is DeepLinkAction.NewCameraChat -> {}
        is DeepLinkAction.OpenAlarmList -> {
            val showAlarms =
                android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
            context.startActivity(showAlarms)
        }
        is DeepLinkAction.OpenShare,
        is DeepLinkAction.Unknown,
        null -> {}
    }
}

@Composable
fun AppNavigation(initialDeepLink: DeepLinkAction?) {
    val startDestination = remember(initialDeepLink) {
        coldStartStartDestination(
            initialDeepLink = initialDeepLink,
            nextDraftId = { com.rikkaminis.app.data.ComposerDraftStore.nextDraftId(context) },
            chatRoute = { Routes.chat(it) },
            seedCamera = {
                DeepLinkCoordinator.setPendingChatAction(
                    DeepLinkCoordinator.ChatAction.OPEN_CAMERA,
                )
            },
            seedHtmlPreview = { sessionId, resourcePath, title ->
                DeepLinkCoordinator.setPendingHtmlPreview(sessionId, resourcePath, title)
            },
        )
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
    }
}

fun NavGraphBuilder.modelEntryDetailGraph(navController: NavController) {
    composable(
        route = Routes.MODEL_ENTRY_DETAIL,
        arguments = listOf(
            navArgument("instanceId") { type = NavType.StringType },
            navArgument("entryId") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val instanceId = backStackEntry.arguments?.getString("instanceId")
            ?: return@composable
        val entryId = backStackEntry.arguments?.getString("entryId")
            ?: return@composable
        ModelEntryDetailScreen(instanceId = instanceId, entryId = entryId)
    }
}
'''

FIXTURE_MAIN = '''package com.rikkaminis.app

private fun handleDeepLink(uri: Uri?) {
    val action = DeepLinkHandler.parse(uri) ?: return
    when (action) {
        is DeepLinkAction.OpenTerminal -> {
            nav.navigate(Routes.terminal(action.initCommand))
        }
        is DeepLinkAction.OpenSession -> {
            nav.navigate(Routes.chat(action.sessionId))
        }
        is DeepLinkAction.CreateEnvironmentVariable -> {
            DeepLinkCoordinator.setPendingEnvVarCreate(action.key, action.value, action.note)
            nav.navigate(Routes.ENV_VARS)
        }
        is DeepLinkAction.OpenHtmlPreview -> {
            DeepLinkCoordinator.setPendingHtmlPreview(
                action.sessionId,
                action.resourcePath,
                action.title,
            )
            nav.navigate(Routes.chat(action.sessionId))
        }
        is DeepLinkAction.OpenSettingsScreen -> {
            runCatching { nav.navigate(action.route) }
                .onFailure { nav.navigate(Routes.SETTINGS) }
        }
        is DeepLinkAction.OpenPermissionSettings -> {
            nav.navigate(Routes.PERMISSIONS)
        }
        is DeepLinkAction.NewChat,
        is DeepLinkAction.NewCameraChat -> {
            nav.navigate(Routes.chat(action.sessionId))
        }
    }
}
'''

# --- [da1028-C] fixtures: reactive deep-link consumers ----------------------

FIXTURE_ENV_SCREEN = '''package com.rikkaminis.app.ui.settings

@Composable
fun EnvironmentVariablesScreen() {
    val pendingCreate by DeepLinkCoordinator.pendingEnvVarCreate.collectAsState()
    LaunchedEffect(pendingCreate) {
        pendingCreate?.let {
            editEntryId = null
            prefill = it
            showAddSheet = true
            DeepLinkCoordinator.consumePendingEnvVarCreate()
        }
    }
}

@Composable
fun EnvVarFormSheet(editEntry: EnvVarEntry?, editEntryId: String?) {
    val currentSecretValue = editEntry?.let { envVarRepository.getValue(it.key) } ?: prefillValue
    var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) {
        mutableStateOf(editEntry?.key ?: prefillKey)
    }
    var valueText by remember(editEntry?.id, currentSecretValue, prefillValue) {
        mutableStateOf(currentSecretValue)
    }
    var noteText by remember(editEntry?.id, editEntry?.note, prefillNote) {
        mutableStateOf(editEntry?.note ?: prefillNote)
    }
    var groupText by remember(editEntry?.id, editEntry?.group) {
        mutableStateOf(editEntry?.group ?: "")
    }
}
'''

# the pre-fix shape: one-shot consume, invisible to a warm start
FIXTURE_ENV_SINGLE_SHOT = '''package com.rikkaminis.app.ui.settings

@Composable
fun EnvironmentVariablesScreen() {
    LaunchedEffect(Unit) {
        DeepLinkCoordinator.consumePendingEnvVarCreate()?.let {
            prefill = it
            showAddSheet = true
        }
    }
}

@Composable
fun EnvVarFormSheet(editEntry: EnvVarEntry?, editEntryId: String?) {
    var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) { mutableStateOf("") }
    var valueText by remember(editEntry?.id, currentSecretValue, prefillValue) { mutableStateOf("") }
    var noteText by remember(editEntry?.id, editEntry?.note, prefillNote) { mutableStateOf("") }
    var groupText by remember(editEntry?.id, editEntry?.group) { mutableStateOf("") }
}
'''

# the form still seeds each field once
FIXTURE_ENV_KEYLESS_FIELDS = FIXTURE_ENV_SCREEN.replace(
    "var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) {",
    "var keyText by remember {",
).replace(
    "var groupText by remember(editEntry?.id, editEntry?.group) {",
    "var groupText by remember {",
)

# `remember()` with an empty key list is the same defect wearing a hat
FIXTURE_ENV_EMPTY_KEY_LIST = FIXTURE_ENV_SINGLE_SHOT \
    .replace("var keyText by remember(editEntry?.id, editEntry?.key, prefillKey)",
             "var keyText by remember()") \
    .replace("var valueText by remember(editEntry?.id, currentSecretValue, prefillValue)",
             "var valueText by remember()") \
    .replace("var noteText by remember(editEntry?.id, editEntry?.note, prefillNote)",
             "var noteText by remember()") \
    .replace("var groupText by remember(editEntry?.id, editEntry?.group)",
             "var groupText by remember()")

FIXTURE_CHAT_SCREEN = '''package com.rikkaminis.app.ui.chat
@Composable
fun ChatScreen(sessionId: String) {
    val pendingChatActionState by com.rikkaminis.app.deeplink.DeepLinkCoordinator
        .pendingChatAction.collectAsState()
    LaunchedEffect(sessionId, pendingChatActionState) {
        if (pendingChatActionState == com.rikkaminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.rikkaminis.app.deeplink.DeepLinkCoordinator.consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }
    val pendingHtmlPreviewState by com.rikkaminis.app.deeplink.DeepLinkCoordinator
        .pendingHtmlPreview.collectAsState()
    LaunchedEffect(sessionId, pendingHtmlPreviewState) {
        val pending = pendingHtmlPreviewState ?: return@LaunchedEffect
        com.rikkaminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        val url = "file://" + pending.resourcePath
        htmlPreviewHolder?.destroy()
        htmlPreviewHolder = com.rikkaminis.app.ui.preview.WebViewHolder(appCtx, url)
        htmlPreviewFallbackTitle = pending.title
        htmlPreviewFullscreen = true
    }
}
'''

# the pre-fix shape: `.value` snapshot
FIXTURE_CHAT_STATIC_READ = '''package com.rikkaminis.app.ui.chat

@Composable
fun ChatScreen(sessionId: String) {
    LaunchedEffect(sessionId) {
        val pending = com.rikkaminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.rikkaminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.rikkaminis.app.deeplink.DeepLinkCoordinator.consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }
    val pendingHtmlPreviewState by com.rikkaminis.app.deeplink.DeepLinkCoordinator
        .pendingHtmlPreview.collectAsState()
    LaunchedEffect(sessionId, pendingHtmlPreviewState) {
        val pending = pendingHtmlPreviewState ?: return@LaunchedEffect
        com.rikkaminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        val url = "file://" + pending.resourcePath
        htmlPreviewHolder?.destroy()
        htmlPreviewHolder = com.rikkaminis.app.ui.preview.WebViewHolder(appCtx, url)
        htmlPreviewFallbackTitle = pending.title
        htmlPreviewFullscreen = true
    }
}
'''

# regression anchor: the neighbouring subscription was "simplified" away
FIXTURE_CHAT_HTML_PREVIEW_REGRESSED = FIXTURE_CHAT_SCREEN.replace(
    "    val pendingHtmlPreviewState by com.rikkaminis.app.deeplink.DeepLinkCoordinator\n"
    "        .pendingHtmlPreview.collectAsState()\n"
    "    LaunchedEffect(sessionId, pendingHtmlPreviewState) {\n"
    "        val pending = pendingHtmlPreviewState ?: return@LaunchedEffect\n"
    "        com.rikkaminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()\n"
    "        val url = \"file://\" + pending.resourcePath\n"
    "        htmlPreviewHolder?.destroy()\n"
    "        htmlPreviewHolder = com.rikkaminis.app.ui.preview.WebViewHolder(appCtx, url)\n"
    "        htmlPreviewFallbackTitle = pending.title\n"
    "        htmlPreviewFullscreen = true\n"
    "    }\n",
    "    LaunchedEffect(sessionId) {\n"
    "        com.rikkaminis.app.deeplink.DeepLinkCoordinator.pendingHtmlPreview.value\n"
    "            ?.let { openHtmlPreview(it) }\n"
    "    }\n",
)


# [r3-t21] the cold-start route decision: the new-chat branches must mint a real
# draft id, and the html-preview shortcut names a REAL session (no draft).
FIXTURE_COLD = '''package com.rikkaminis.app.ui.navigation

internal fun coldStartStartDestination(
    initialDeepLink: DeepLinkAction?,
    nextDraftId: () -> String,
    chatRoute: (String) -> String,
    seedCamera: () -> Unit,
    seedHtmlPreview: (String, String, String) -> Unit,
): String {
    val htmlShortcut = initialDeepLink as? DeepLinkAction.OpenHtmlPreview
    if (htmlShortcut != null) {
        seedHtmlPreview(htmlShortcut.sessionId, htmlShortcut.resourcePath, htmlShortcut.title)
        return chatRoute(htmlShortcut.sessionId)
    }
    return when (initialDeepLink) {
        is DeepLinkAction.OpenSession -> chatRoute(initialDeepLink.sessionId)
        is DeepLinkAction.NewCameraChat -> {
            seedCamera()
            chatRoute(nextDraftId())
        }
        is DeepLinkAction.NewChat -> chatRoute(nextDraftId())
        else -> chatRoute(nextDraftId())
    }
}
'''


def write_fixture(root: Path, dlh: str, nav: str, main: str,
                  env: str = FIXTURE_ENV_SCREEN, chat: str = FIXTURE_CHAT_SCREEN,
                  cold: str = FIXTURE_COLD) -> None:
    for rel, content in ((REL_DLH, dlh), (REL_NAV, nav), (REL_MAIN, main),
                         (REL_ENV_SCREEN, env), (REL_CHAT_SCREEN, chat),
                         (REL_COLD, cold)):
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")


def selftest() -> None:
    ok = True
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        # compliant fixture must pass
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if VIOLATIONS:
            ok = False
            print(f"SELFTEST compliant fixture unexpectedly failed: {VIOLATIONS}")

        # fixture B: drop the mcp-servers whitelist entry -> must fail
        write_fixture(root, FIXTURE_DLH.replace('"mcp-servers", "mcp_servers" ->', '->'), FIXTURE_NAV, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("mcp-servers" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture B (drop mcp-servers) did not trip the gate")
        else:
            print("SELFTEST B (drop whitelist entry) RED as expected")

        # fixture C: remove the seg() encoding from providerDetail -> must fail
        bad_nav = FIXTURE_NAV.replace('"provider/${seg(instanceId)}"', '"provider/$instanceId"')
        write_fixture(root, FIXTURE_DLH, bad_nav, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("providerDetail" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture C (raw providerDetail) did not trip the gate")
        else:
            print("SELFTEST C (drop encode) RED as expected")

        # fixture D: fallback turned into an error -> must fail
        bad_dlh = FIXTURE_DLH.replace("else -> SettingsDeepLink.Route(Routes.SETTINGS)", "else -> SettingsDeepLink.Unknown")
        write_fixture(root, bad_dlh, FIXTURE_NAV, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("fallback" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture D (fallback removed) did not trip the gate")
        else:
            print("SELFTEST D (fallback removed) RED as expected")

        # fixture E: navigate backstop removed -> must fail
        bad_main = FIXTURE_MAIN.replace("runCatching { nav.navigate(action.route) }", "nav.navigate(action.route)")
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV, bad_main)
        VIOLATIONS.clear()
        check(root)
        if not any("runCatching" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture E (backstop removed) did not trip the gate")
        else:
            print("SELFTEST E (backstop removed) RED as expected")

        # fixture F: [da1021-C] cold-start backstop removed -> must fail
        bad_nav_cold = FIXTURE_NAV.replace(
            "runCatching { navController.navigate(initialDeepLink.route) }",
            "navController.navigate(initialDeepLink.route)",
        )
        write_fixture(root, FIXTURE_DLH, bad_nav_cold, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("cold-start" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture F (cold-start backstop removed) did not trip the gate")
        else:
            print("SELFTEST F (cold-start backstop removed) RED as expected")

        # fixture G: [da1021-C] raw mirrorCategoryDetail -> must fail
        bad_nav_mirror = FIXTURE_NAV.replace(
            '"mirror_category/${seg(categoryId)}"', '"mirror_category/$categoryId"'
        )
        write_fixture(root, FIXTURE_DLH, bad_nav_mirror, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("mirrorCategoryDetail" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST fixture G (raw mirrorCategoryDetail) did not trip the gate")
        else:
            print("SELFTEST G (raw mirrorCategoryDetail) RED as expected")

        # fixture H: [da1024-E] ONE second decode back inside the composable
        # block -> exactly one decode violation, and the fail-closed guard
        # survives (so criterion 7 must stay green: each criterion has a
        # fixture that breaks it and one that does not).
        bad_nav_h = FIXTURE_NAV.replace(
            'val instanceId = backStackEntry.arguments?.getString("instanceId")\n'
            '            ?: return@composable',
            'val instanceId = backStackEntry.arguments?.getString("instanceId")\n'
            '            ?.let { android.net.Uri.decode(it) } ?: return@composable',
        )
        write_fixture(root, FIXTURE_DLH, bad_nav_h, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        dec = [v for v in VIOLATIONS if "decodes a route argument" in v]
        guard = [v for v in VIOLATIONS if "fail-closed guard" in v]
        if len(dec) != 1 or guard:
            ok = False
            print(f"SELFTEST fixture H (one second decode) wrong verdict: {VIOLATIONS}")
        else:
            print("SELFTEST H (one second decode) RED as expected")

        # fixture I: both args decoded again -> two decode violations (the
        # criterion counts sites, it is not satisfied by the first hit).
        bad_nav_i = bad_nav_h.replace(
            'val entryId = backStackEntry.arguments?.getString("entryId")\n'
            '            ?: return@composable',
            'val entryId = backStackEntry.arguments?.getString("entryId")\n'
            '            ?.let { android.net.Uri.decode(it) } ?: return@composable',
        )
        write_fixture(root, FIXTURE_DLH, bad_nav_i, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        dec = [v for v in VIOLATIONS if "decodes a route argument" in v]
        if len(dec) != 2:
            ok = False
            print(f"SELFTEST fixture I (both decoded again) expected 2 violations, got {len(dec)}")
        else:
            print("SELFTEST I (both decoded again) RED as expected")

        # fixture I2: URLDecoder variant of the same defect -> still RED.
        bad_nav_i2 = bad_nav_h.replace(
            "?.let { android.net.Uri.decode(it) } ?: return@composable",
            '?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return@composable',
        )
        write_fixture(root, FIXTURE_DLH, bad_nav_i2, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not [v for v in VIOLATIONS if "decodes a route argument" in v]:
            ok = False
            print("SELFTEST fixture I2 (URLDecoder double decode) did not trip the gate")
        else:
            print("SELFTEST I2 (URLDecoder double decode) RED as expected")

        # fixture J: fail-closed guard dropped (no decode at all) -> only
        # criterion 7 may fire.
        bad_nav_j = FIXTURE_NAV.replace(
            'arguments?.getString("instanceId")\n            ?: return@composable',
            'arguments?.getString("instanceId") ?: ""',
        ).replace(
            'arguments?.getString("entryId")\n            ?: return@composable',
            'arguments?.getString("entryId") ?: ""',
        )
        write_fixture(root, FIXTURE_DLH, bad_nav_j, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        dec = [v for v in VIOLATIONS if "decodes a route argument" in v]
        guard = [v for v in VIOLATIONS if "fail-closed guard" in v]
        if not guard or dec:
            ok = False
            print(f"SELFTEST fixture J (guard dropped) wrong verdict: {VIOLATIONS}")
        else:
            print("SELFTEST J (fail-closed guard dropped) RED as expected")

        # --- [da1028-C] fixtures K..O: reactive deep-link consumers ---------
        # fixture K: the one-shot consume shape -> two violations (no
        # collectAsState + a LaunchedEffect(Unit) that consumes).
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV, FIXTURE_MAIN,
                      env=FIXTURE_ENV_SINGLE_SHOT)
        VIOLATIONS.clear()
        check(root)
        sub = [v for v in VIOLATIONS if "pendingEnvVarCreate is not observed" in v]
        one = [v for v in VIOLATIONS if "still consumes the pending create request" in v]
        if not sub or not one:
            ok = False
            print(f"SELFTEST K (one-shot consume) wrong verdict: {VIOLATIONS}")
        else:
            print("SELFTEST K (one-shot deep-link consume) RED as expected")

        # fixture L: the form seeds each field once (the pre-fix keyless shape)
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV, FIXTURE_MAIN,
                      env=FIXTURE_ENV_KEYLESS_FIELDS)
        VIOLATIONS.clear()
        check(root)
        keyless = [v for v in VIOLATIONS if "the field is keyless or renamed" in v]
        if len(keyless) != 2 or not all("keyText" in v or "groupText" in v for v in keyless):
            ok = False
            print(f"SELFTEST L (keyless form fields) expected 2 violations, got {keyless}")
        else:
            print("SELFTEST L (keyless form fields) RED as expected")

        # fixture L2: `remember()` with an empty key list is the same defect
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV, FIXTURE_MAIN,
                      env=FIXTURE_ENV_EMPTY_KEY_LIST)
        VIOLATIONS.clear()
        check(root)
        empty_keys = [v for v in VIOLATIONS if "carries no keys" in v]
        if len(empty_keys) != 4:
            ok = False
            print(f"SELFTEST L2 (empty key list) expected 4 violations, got {empty_keys}")
        else:
            print("SELFTEST L2 (empty key list) RED as expected")

        # fixture M: ChatScreen keeps the `.value` snapshot
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV, FIXTURE_MAIN,
                      chat=FIXTURE_CHAT_STATIC_READ)
        VIOLATIONS.clear()
        check(root)
        static = [v for v in VIOLATIONS if "still read via a `.value` snapshot" in v]
        missing = [v for v in VIOLATIONS if "pendingChatAction is not observed" in v]
        if not static or not missing:
            ok = False
            print(f"SELFTEST M (static quick-action read) wrong verdict: {VIOLATIONS}")
        else:
            print("SELFTEST M (static quick-action read) RED as expected")

        # fixture N: the neighbouring pendingHtmlPreview subscription regressed
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV, FIXTURE_MAIN,
                      chat=FIXTURE_CHAT_HTML_PREVIEW_REGRESSED)
        VIOLATIONS.clear()
        check(root)
        if not [v for v in VIOLATIONS if "pendingHtmlPreview lost its collectAsState()" in v]:
            ok = False
            print(f"SELFTEST N (pendingHtmlPreview regression anchor) missed: {VIOLATIONS}")
        else:
            print("SELFTEST N (pendingHtmlPreview regression anchor) RED as expected")

        # fixture O: precision — editing an unrelated file (the deep-link
        # resolver) must not make criteria 8/9/10 speak up.
        write_fixture(root, FIXTURE_DLH.replace(
            '"memory" -> SettingsDeepLink.Route(Routes.MEMORY)',
            '"memory", "memories" -> SettingsDeepLink.Route(Routes.MEMORY)',
        ), FIXTURE_NAV, FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        noise = [v for v in VIOLATIONS if REL_ENV_SCREEN + ":" in v or REL_CHAT_SCREEN + ":" in v]
        if noise:
            ok = False
            print(f"SELFTEST O (unrelated edit) leaked into the consumer criteria: {noise}")
        else:
            print("SELFTEST O (unrelated edit stays silent) GREEN as expected")

        # --- [da1029-A] criterion 11 ---------------------------------------
        # fixture P: drop the OpenAlarmList branch -> must fail
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV.replace(
            "        is DeepLinkAction.OpenAlarmList -> {\n"
            "            val showAlarms =\n"
            "                android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)\n"
            "            context.startActivity(showAlarms)\n"
            "        }\n",
            "",
        ), FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("OpenAlarmList" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST P (drop OpenAlarmList branch) did not trip criterion 11")
        else:
            print("SELFTEST P (drop OpenAlarmList branch) RED as expected")

        # fixture Q: put the catch-all `else -> {}` back -> must fail
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV.replace(
            "        is DeepLinkAction.OpenShare,\n"
            "        is DeepLinkAction.Unknown,\n        null -> {}",
            "        else -> {}",
        ), FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("catch-all" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST Q (catch-all else restored) did not trip criterion 11")
        else:
            print("SELFTEST Q (catch-all else restored) RED as expected")

        # fixture R: OpenAlarmList branch launches the WRONG Clock action ->
        # the branch presence alone must not satisfy the criterion.
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV.replace(
            "android.provider.AlarmClock.ACTION_SHOW_ALARMS",
            "android.provider.AlarmClock.ACTION_SET_ALARM",
        ), FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("ACTION_SHOW_ALARMS" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST R (wrong alarm action) did not trip criterion 11")
        else:
            print("SELFTEST R (wrong alarm action) RED as expected")

        # fixture S: precision — a SECOND `when (initialDeepLink)` resolver
        # carrying `else -> null` (the real `quickActionStart` shape) must not
        # make criterion 11 speak up: it selects the dispatch block by its
        # dispatch-only OpenTerminal case.
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV.replace(
            "fun NavGraphBuilder.modelEntryDetailGraph",
            "fun quickActionStartOf(initialDeepLink: DeepLinkAction): String? =\n"
            "    when (initialDeepLink) {\n"
            '        is DeepLinkAction.NewChat -> Routes.chat("x")\n'
            "        else -> null\n"
            "    }\n\n"
            "fun NavGraphBuilder.modelEntryDetailGraph",
        ), FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        noise = [v for v in VIOLATIONS if "dispatch" in v]
        if noise:
            ok = False
            print(f"SELFTEST S (resolver else -> null) leaked into criterion 11: {noise}")
        else:
            print("SELFTEST S (startDestination resolver stays out of scope) GREEN as expected")

        # fixture T [da1029-A]: nullable subject with the `null ->` case dropped.
        # The catch-all is gone, so this is a hard compile error in the real
        # build ("'when' expression must be exhaustive") — the gate mirrors it
        # offline instead of spending a CI round on it.
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV.replace(
            "        is DeepLinkAction.Unknown,\n        null -> {}\n", "",
        ), FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if not any("nullable" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST T (nullable subject, null case dropped) did not trip criterion 11")
        else:
            print("SELFTEST T (nullable subject, null case dropped) RED as expected")

        # fixture U [da1029-A] precision: a NON-nullable subject needs no `null`
        # branch, so dropping it must stay silent.
        write_fixture(root, FIXTURE_DLH, FIXTURE_NAV.replace(
            "initialDeepLink: DeepLinkAction?", "initialDeepLink: DeepLinkAction",
        ).replace("        is DeepLinkAction.Unknown,\n        null -> {}\n", ""),
            FIXTURE_MAIN)
        VIOLATIONS.clear()
        check(root)
        if any("nullable" in v for v in VIOLATIONS):
            ok = False
            print("SELFTEST U (non-nullable subject) leaked the nullable criterion")
        else:
            print("SELFTEST U (non-nullable subject needs no null branch) GREEN as expected")

        # --- [wide-1024 12-misc-b] witness arms: the four shapes the report
        # proved blind. Each one keeps every substring the old criteria looked
        # for and drops the EFFECT, so each arm asserts the new criterion speaks
        # AND nothing else does.
        def witness_arm(label, needle, dlh=FIXTURE_DLH, nav=FIXTURE_NAV,
                        env=FIXTURE_ENV_SCREEN, chat=FIXTURE_CHAT_SCREEN,
                        main=FIXTURE_MAIN, cold=FIXTURE_COLD):
            nonlocal ok
            write_fixture(root, dlh, nav, main, env=env, chat=chat, cold=cold)
            VIOLATIONS.clear()
            check(root)
            hits = [v for v in VIOLATIONS if needle in v]
            noise = [v for v in VIOLATIONS if needle not in v]
            if not hits or noise or len(hits) != 1:
                ok = False
                print(f"SELFTEST {label} wrong verdict: {VIOLATIONS}")
            else:
                print(f"SELFTEST {label} RED as expected")

        # V: one encoded sibling + one raw composite id (entryId carries '/').
        witness_arm(
            "V (raw sibling id in a 2-arg route builder)",
            "Routes.modelEntryDetail interpolates raw id",
            nav=FIXTURE_NAV.replace(
                '"model_entry/${seg(instanceId)}/${seg(entryId)}"',
                '"model_entry/${seg(instanceId)}/$entryId"',
            ),
        )
        # W: constant LaunchedEffect key — same defect as Unit, different spelling.
        witness_arm(
            "W (LaunchedEffect(true) keeps the consume)",
            "still consumes the pending create request",
            env=FIXTURE_ENV_SCREEN.replace(
                "LaunchedEffect(pendingCreate) {", "LaunchedEffect(true) {"),
        )
        # X: constant remember key.
        witness_arm(
            "X (remember(Unit) field shadow)",
            "`var keyText by remember(Unit)` is not keyed on",
            env=FIXTURE_ENV_SCREEN.replace(
                "var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) {",
                "var keyText by remember(Unit) {"),
        )
        # X2: half a key list — the prefill half goes stale.
        witness_arm(
            "X2 (key list drops the prefill half)",
            "is not keyed on prefillKey",
            env=FIXTURE_ENV_SCREEN.replace(
                "var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) {",
                "var keyText by remember(editEntry?.id, editEntry?.key) {"),
        )
        # Y: the Intent is built and then only read — never launched.
        witness_arm(
            "Y (alarm Intent built, never launched)",
            "never hands it to startActivity()",
            nav=FIXTURE_NAV.replace(
                "context.startActivity(showAlarms)", "showAlarms.action"),
        )

        # ── [batch6-t06] the six witnesses this branch's report proved blind ──
        # Each keeps every substring the old criterion looked for and moves the
        # EFFECT: the head stops being a match label, the id leaves the literal,
        # the key stops evaluating the state, the launch becomes unreachable.

        # Z1 (anchor): "mcp-servers" survives only as a value inside a dead
        # `.also { }` body — the branch no longer MATCHES it, so the emitted
        # minis://settings/mcp-servers link still falls through to Settings home.
        witness_arm(
            "Z1 (head demoted out of the when labels into a dead value)",
            "emitter head 'mcp-servers' is not a `when (head)` match label",
            dlh=FIXTURE_DLH.replace(
                '"mcp-servers", "mcp_servers" -> SettingsDeepLink.Route(Routes.MCP)',
                '"mcp_servers" -> SettingsDeepLink.Route(Routes.MCP.also { "mcp-servers" })',
            ),
        )
        # Z2 (spelling/dataflow): the raw id leaves the template for a `+`
        # operand — same bytes, invisible to a template-hole scan.
        witness_arm(
            "Z2 (raw id concatenated outside the literal)",
            "Routes.modelEntryDetail interpolates raw id",
            nav=FIXTURE_NAV.replace(
                '"model_entry/${seg(instanceId)}/${seg(entryId)}"',
                '"model_entry/${seg(instanceId)}/" + entryId',
            ),
        )
        # Z3 (dataflow): the key keeps the identifier and discards it — the
        # composer sees the constant Unit, so a warm-start request is dropped.
        witness_arm(
            "Z3 (LaunchedEffect(pendingCreate.let { Unit }))",
            "still consumes the pending create request",
            env=FIXTURE_ENV_SCREEN.replace(
                "LaunchedEffect(pendingCreate) {",
                "LaunchedEffect(pendingCreate.let { Unit }) {"),
        )
        # Z4 (dataflow): both remember keys fold to a constant — the field never
        # re-seeds even though both seed identifiers appear in the key list.
        witness_arm(
            "Z4 (remember keys that fold to constants)",
            "is not keyed on editEntry, prefillKey",
            env=FIXTURE_ENV_SCREEN.replace(
                "var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) {",
                "var keyText by remember(editEntry.let { 0 }, prefillKey.let { 0 }) {"),
        )
        # Z5 (completeness): criterion 8's twin. ChatScreen keeps the reactive
        # subscriber and the consume call, but the effect key is the constant
        # Unit — camera_chat on an already-composed screen is dropped.
        witness_arm(
            "Z5 (ChatScreen LaunchedEffect(Unit) keeps the consume)",
            "consumes pendingChatAction but its key never re-evaluates it",
            chat=FIXTURE_CHAT_SCREEN.replace(
                "LaunchedEffect(sessionId, pendingChatActionState) {",
                "LaunchedEffect(Unit) {"),
        )
        # Z6 (family-hole): the startActivity call text stays, the branch can
        # never run — `if (false)` is folded instead of whitelisted.
        witness_arm(
            "Z6 (startActivity behind `if (false)`)",
            "never hands it to startActivity() on a reachable path",
            nav=FIXTURE_NAV.replace(
                "context.startActivity(showAlarms)",
                "if (false) context.startActivity(showAlarms)"),
        )


        # --- [r3-t21] dispatch-arm EFFECT arms -------------------------------
        # Every arm below keeps the SPELLING a name-anchored criterion looks for
        # (branch label, collectAsState, runCatching) and breaks only the
        # EFFECT: the payload is not delivered, the navigation is dead, the
        # consumed request never becomes visible state, or the permission
        # branch cannot work in one of its two states.

        def witness_clean(label, **kw):
            """Precision arm: a LEGAL variant must raise no violation at all."""
            nonlocal ok
            write_fixture(root, kw.get("dlh", FIXTURE_DLH), kw.get("nav", FIXTURE_NAV),
                          kw.get("main", FIXTURE_MAIN),
                          env=kw.get("env", FIXTURE_ENV_SCREEN),
                          chat=kw.get("chat", FIXTURE_CHAT_SCREEN),
                          cold=kw.get("cold", FIXTURE_COLD))
            VIOLATIONS.clear()
            check(root)
            if VIOLATIONS:
                ok = False
                print(f"SELFTEST {label} wrong verdict: {VIOLATIONS}")
            else:
                print(f"SELFTEST {label} GREEN as expected")

        # T21-1: warm-start env-var create — the coordinator write is dropped
        witness_arm(
            "T21-1 (warm env-var create write dropped)",
            "never reaches `setPendingEnvVarCreate(",
            main=FIXTURE_MAIN.replace(
                "            DeepLinkCoordinator.setPendingEnvVarCreate"
                "(action.key, action.value, action.note)\n", ""),
        )
        # T21-2: cold-start env-var create — same write, other dispatch site
        witness_arm(
            "T21-2 (cold env-var create write dropped)",
            "never reaches `setPendingEnvVarCreate(",
            nav=FIXTURE_NAV.replace(
                "            DeepLinkCoordinator.setPendingEnvVarCreate(\n"
                "                initialDeepLink.key,\n"
                "                initialDeepLink.value,\n"
                "                initialDeepLink.note,\n"
                "            )\n", ""),
        )
        # T21-3: the write survives as text, parked in a dead branch
        witness_arm(
            "T21-3 (warm env-var create write behind `if (false)`)",
            "never reaches `setPendingEnvVarCreate(",
            main=FIXTURE_MAIN.replace(
                "            DeepLinkCoordinator.setPendingEnvVarCreate"
                "(action.key, action.value, action.note)",
                "            if (false) DeepLinkCoordinator.setPendingEnvVarCreate"
                "(action.key, action.value, action.note)"),
        )
        # T21-4: partial payload — the note field is lost
        witness_arm(
            "T21-4 (env-var create drops the note field)",
            "does not read note off the action",
            main=FIXTURE_MAIN.replace(
                "setPendingEnvVarCreate(action.key, action.value, action.note)",
                'setPendingEnvVarCreate(action.key, action.value, "")'),
        )
        # T21-5: the published payload comes from somewhere else
        witness_arm(
            "T21-5 (env-var create fed from another object)",
            "is fed from something other than the deep-link action",
            main=FIXTURE_MAIN.replace(
                "setPendingEnvVarCreate(action.key, action.value, action.note)",
                "setPendingEnvVarCreate(other.key, other.value, other.note)"),
        )
        # T21-6: warm-start HTML preview — the publish is dropped, the route stays
        witness_arm(
            "T21-6 (warm HTML preview write dropped)",
            "never reaches `setPendingHtmlPreview(",
            main=FIXTURE_MAIN.replace(
                "            DeepLinkCoordinator.setPendingHtmlPreview(\n"
                "                action.sessionId,\n"
                "                action.resourcePath,\n"
                "                action.title,\n"
                "            )\n", ""),
        )
        # T21-7: cold-start preview seeding degrades to a no-op lambda
        witness_arm(
            "T21-7 (cold-start seedHtmlPreview turned into a no-op)",
            "never calls setPendingHtmlPreview",
            nav=FIXTURE_NAV.replace(
                "            seedHtmlPreview = { sessionId, resourcePath, title ->\n"
                "                DeepLinkCoordinator.setPendingHtmlPreview"
                "(sessionId, resourcePath, title)\n"
                "            },\n",
                "            seedHtmlPreview = { _, _, _ -> },\n"),
        )
        # T21-8: the arm navigates, but behind a dead guard
        witness_arm(
            "T21-8 (cold OpenSession navigation behind `if (false)`)",
            "sits behind a dead guard",
            nav=FIXTURE_NAV.replace(
                "            navController.navigate(Routes.chat(initialDeepLink.sessionId))",
                "            if (false) navController.navigate"
                "(Routes.chat(initialDeepLink.sessionId))"),
        )
        # T21-9: the preview request is consumed, the holder is never built
        witness_arm(
            "T21-9 (preview holder never built)",
            "does not build the preview holder",
            chat=FIXTURE_CHAT_SCREEN.replace(
                "        htmlPreviewHolder = com.rikkaminis.app.ui.preview.WebViewHolder"
                "(appCtx, url)\n", ""),
        )
        # T21-10: the holder is built, the fullscreen flag never flips to true
        witness_arm(
            "T21-10 (preview built but never fullscreen)",
            "never sets `htmlPreviewFullscreen = true`",
            chat=FIXTURE_CHAT_SCREEN.replace(
                "        htmlPreviewFullscreen = true",
                "        htmlPreviewFullscreen = false"),
        )
        # T21-11: permission polarity inverted — works only when DENIED
        witness_arm(
            "T21-11 (camera polarity inverted)",
            "polarity is inverted",
            chat=FIXTURE_CHAT_SCREEN.replace(
                "            if (granted) launchCamera()\n"
                "            else cameraPermissionLauncher.launch"
                "(android.Manifest.permission.CAMERA)\n",
                "            if (!granted) launchCamera()\n"),
        )
        # T21-12: no permission branch at all
        witness_arm(
            "T21-12 (camera launched outside any permission branch)",
            "outside any permission branch",
            chat=FIXTURE_CHAT_SCREEN.replace(
                "            val granted = ContextCompat.checkSelfPermission(\n"
                "                context,\n"
                "                android.Manifest.permission.CAMERA,\n"
                "            ) == PackageManager.PERMISSION_GRANTED\n"
                "            if (granted) launchCamera()\n"
                "            else cameraPermissionLauncher.launch"
                "(android.Manifest.permission.CAMERA)\n",
                "            launchCamera()\n"),
        )
        # T21-13: cold-start draft id hardcoded -> orphaned draft slot
        witness_arm(
            "T21-13 (cold-start draft id hardcoded)",
            "does not come from ComposerDraftStore.nextDraftId",
            nav=FIXTURE_NAV.replace(
                "            nextDraftId = { com.rikkaminis.app.data.ComposerDraftStore"
                ".nextDraftId(context) },",
                '            nextDraftId = { "__new__orphan" },'),
        )
        # T21-14: the route decision itself hardcodes the draft id
        witness_arm(
            "T21-14 (cold-start route hardcodes the draft id)",
            "is not minted through nextDraftId()",
            cold=FIXTURE_COLD.replace(
                "        is DeepLinkAction.NewChat -> chatRoute(nextDraftId())",
                '        is DeepLinkAction.NewChat -> chatRoute("__new__orphan")'),
        )
        # T21-15: the decision is computed and thrown away
        witness_arm(
            "T21-15 (cold-start decision computed and thrown away)",
            "does not come from coldStartStartDestination",
            nav=FIXTURE_NAV.replace(
                "        startDestination = startDestination,",
                '        startDestination = "chat/__home",'),
        )

        # --- precision arms: legal variants must stay GREEN ------------------
        # a request helper + braces is the same effect as the launcher lambda
        witness_clean(
            "T21-16 (braced permission arms with a request helper)",
            chat=FIXTURE_CHAT_SCREEN.replace(
                "            if (granted) launchCamera()\n"
                "            else cameraPermissionLauncher.launch"
                "(android.Manifest.permission.CAMERA)\n",
                "            if (granted) {\n"
                "                launchCamera()\n"
                "            } else {\n"
                "                requestCameraPermission()\n"
                "            }\n"),
        )
        # one dead nav call beside a live one is not a defect
        witness_clean(
            "T21-17 (one dead nav call beside a live one)",
            nav=FIXTURE_NAV.replace(
                "            navController.navigate(Routes.chat(initialDeepLink.sessionId))",
                "            if (false) navController.navigate(Routes.SHARE)\n"
                "            navController.navigate(Routes.chat(initialDeepLink.sessionId))"),
        )
        # an extra unrelated arm does not disturb the judges
        witness_clean(
            "T21-18 (extra unrelated dispatch arm)",
            main=FIXTURE_MAIN.replace(
                "        is DeepLinkAction.OpenPermissionSettings -> {",
                "        is DeepLinkAction.OpenShare -> {\n"
                "            nav.navigate(Routes.SHARE)\n"
                "        }\n"
                "        is DeepLinkAction.OpenPermissionSettings -> {"),
        )

    if not ok:
        sys.exit("DEEPLINK_SETTINGS_ROUTES_SELFTEST=FAIL")


def main() -> None:
    args = sys.argv[1:]
    if "--selftest" in args:
        selftest()
        print("DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS (%d arms)" % max(CHECKS[0] - 1, 0))
        return
    root = Path(args[0]) if args else Path.cwd()
    if not root.exists():
        sys.exit(f"FAIL: root {root} does not exist")
    check(root)
    if VIOLATIONS:
        print(f"FAIL: {len(VIOLATIONS)} violation(s)")
        for v in VIOLATIONS:
            print(f"  {v}")
        sys.exit(1)
    print("DEEPLINK_SETTINGS_ROUTES_CHECK=PASS")


if __name__ == "__main__":
    main()
