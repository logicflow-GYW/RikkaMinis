#!/usr/bin/env python3
"""
liveness_static_guard — the log-free half of the probe-liveness audit (CI gate).

`liveness_audit.py --strict` compares declared probes against a real log
window, so it can never run in CI (no logs there). This gate enforces the two
structural properties that need NO logs, both born from measured incidents:

  A. Method coverage — every public AppLogger log method (signatures
     `fun <name>(category: String, ...)` in AppLogger.kt) must actually be
     enumerated by liveness_audit.declared(). Incident class: declared() knew
     only info|warning|error while `fun debug` existed — every DEBUG-only
     probe was invisible to the census BY CONSTRUCTION, on every run, forever
     (found 2026-09-22; the sandbox twin of this bug — a `[dwiev]` single-char
     regex — had already hidden 47.6% of call sites from an evidence pipeline).

  B. RARE freshness — every RARE registry entry in liveness_audit.py must
     still resolve to a live declaration in main source (substring match:
     keys like `coldParse.offmain` are embedded inside larger log literals,
     so exact-quoted grep false-negatives — verified 2026-09-22).
     docs/stability/observability.md §6.5: the registry "has an expiry".
     Incident: `buildFlatChatItems.ledgerReseed` outlived its probe and sat
     in the registry as a dead ruler holding a permission slip.

  C. Call-site completeness — for every AppLogger log method, the raw count
     of `AppLogger.<method>(` occurrences in main source must equal the count
     declared() enumerates. Catches silent regex drift inside declared()
     (the exact mechanism that made the sandbox `verify.py` under-count).

Usage:
  python3 scripts/scan/liveness_static_guard.py <repo_root>
  python3 scripts/scan/liveness_static_guard.py --self-test

Exit: 0 = clean, 1 = any check failed (or any self-test assertion failed).
"""
import os
import re
import shutil
import sys
import tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(SCRIPT_DIR, "..", "diag"))
sys.path.insert(0, SCRIPT_DIR)
import liveness_audit  # noqa: E402  (single source of truth: RARE + declared())
from _kotlin_lexer import KotlinLexError, analyse as kotlin_analyse  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool, enclosing_conditions, match_bracket, split_top, when_branches,
)

MAIN_JAVA = os.path.join("src", "android", "app", "src", "main", "java")
APPLOGGER_REL = os.path.join("com", "rikkaminis", "app", "logging", "AppLogger.kt")


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


# ── [wide3/10] Two structural helpers ────────────────────────────────────────
# blank_comment(text): comments become spaces, string literals are KEPT (the
#   RARE keys live inside string literals), length and newlines are preserved so
#   offsets/line numbers stay valid.
# dead_guarded(code, off): a constant-false condition governs `off`. `if (false)
#   log(...)` keeps every literal the old criteria matched and emits nothing.
# [wide4-02] Both are now thin wrappers over the two shared primitives
# (`_kotlin_lexer` for masking, `_kotlin_ast` for condition folding) — the
# hand-written state machines in this file were the thing the witnesses broke.
def lex_views(text):
    """(comments_view, code_view) — same length as `text`, positions identical."""
    ok, reason, views = kotlin_analyse(text)
    if not ok:
        raise KotlinLexError(reason)
    return views["comments"], views["code"]


def blank_comment(text):
    """Comments blanked, string literals KEPT, every offset preserved.

    [wide3/10] The RARE keys live inside string literals (`"step=coldParse.offmain"`)
    and comments must not count as declarations, so neither `mask_code` nor a
    raw copy does the job — this is the lexer's `comments` view exactly.

    [wide4-02 parser-death/4] This used to be a hand-written state machine. It
    handled `//`, `/* */`, `"` strings and triple quotes, but NOT `${...}`
    template interpolation: in `val _open = "${"/*"}"` the inner quote moved the
    scanner out of string mode, the `"/*"` then opened a REAL block comment and
    everything up to the next `*/` — including a whole `fun trace(...)`
    declaration — was blanked away. Checks A/C/D never saw it. The shared lexer
    tracks template nesting, nested block comments, raw strings and backtick
    identifiers; an unparsable file raises and the caller reports it instead of
    judging a half-masked view.
    """
    ok, reason, views = kotlin_analyse(text)
    if not ok:
        raise KotlinLexError(reason)
    return views["comments"]


def dead_guarded(code, off):
    """True when a constant-false condition governs `off`.

    `if (false) log(...)` keeps every literal the old criteria matched and emits
    nothing.

    [wide4-02 family-hole/1] The old implementation was a regex blacklist
    (`if (false|!true|0 == 1|true |||false &&…)`) plus a brace walk that only
    knew `if`/`while`. `when (false) { true -> log(...) else -> Unit }` is the
    same dead emission spelled as a `when`: the regex saw no `if`, the brace walk
    saw a `{` whose header matched nothing, and the probe passed as alive.
    The condition is now folded by the shared const folder and every enclosing
    condition is enumerated (`if` + `when` branches, including the `when`
    subject), so the spelling no longer decides.

    `code` must be the lexer's code view (strings masked) — the caller passes it
    even when it located the call site in the comments view: both views are
    exactly the same length, so offsets are interchangeable.
    """
    for kind, cond in enclosing_conditions(code, off):
        if kind == "if" and const_bool(cond) is False:
            return True
    for subject, cond, b_start, b_end in when_branches(code):
        if b_start <= off < b_end and (const_bool(subject) is False
                                       or const_bool(cond) is False):
            return True
    return False


def _paren_end(code, open_idx):
    depth = 0
    for j in range(open_idx, len(code)):
        if code[j] == "(":
            depth += 1
        elif code[j] == ")":
            depth -= 1
            if depth == 0:
                return j
    return -1


def _brace_end(code, open_idx):
    depth = 0
    for j in range(open_idx, len(code)):
        if code[j] == "{":
            depth += 1
        elif code[j] == "}":
            depth -= 1
            if depth == 0:
                return j
    return -1


def _depth_map(text):
    """Brace depth at every offset of `text` (string contents already masked)."""
    depth, out = 0, [0] * len(text)
    for i, ch in enumerate(text):
        if ch == "{":
            depth += 1
            out[i] = depth
        elif ch == "}":
            out[i] = depth
            depth -= 1
        else:
            out[i] = depth
    return out


def applogger_log_methods(appjava_dir):
    """Public AppLogger log methods — derived from the BODY, not from spelling.

    [wide3/10] The old pattern was `fun (\\w+)\\(\\s*category\\s*:` — it assumed
    every public log method names its first parameter `category`. A new
    `fun trace(cat: String, message: String)` was invisible to checks A and C by
    construction.

    [wide4-02 family-hole/2] The follow-up pattern still required "two
    `String` parameters, no defaults, one line": `fun trace(category: String,
    message: String = "")` — or the same signature wrapped across lines — walked
    straight past it, and the census stayed blind to a brand-new log level. The
    shape that actually identifies a log method is now BEHAVIOURAL: a
    non-private member of `object AppLogger` whose body calls `log(`/`enqueue(`.
    Nothing about parameter names, defaults or line breaks can hide it.
    """
    text = blank_comment(read(os.path.join(appjava_dir, APPLOGGER_REL)))
    m = re.search(r"\bobject\s+AppLogger\b", text)
    if not m:
        return []
    b = text.find("{", m.end())
    if b < 0:
        return []
    end = _brace_end(text, b)
    if end < 0:
        return []
    body = text[b + 1:end]
    depths = _depth_map(body)
    out = []
    for fm in re.finditer(r"\bfun\s+(\w+)\s*\(", body):
        # The slice starts INSIDE the object's braces, so its direct members sit
        # at depth 0; a `fun` inside a nested class sits deeper and is not
        # reachable as `AppLogger.<name>(...)`.
        if depths[fm.start()] != 0:
            continue
        line_start = body.rfind("\n", 0, fm.start()) + 1
        if re.search(r"\bprivate\b", body[line_start:fm.start()]):
            continue
        p_close = _paren_end(body, fm.end() - 1)
        if p_close < 0:
            continue
        j = p_close + 1
        # Skip a return-type annotation (`fun x(…): String {`).
        k = j
        while k < len(body) and body[k] in " \t":
            k += 1
        if k < len(body) and body[k] == ":":
            while j < len(body) and body[j] not in "{=\n":
                j += 1
        else:
            j = k
        if j >= len(body):
            continue
        if body[j] == "=":
            eol = body.find("\n", j)
            seg = body[j:eol if eol > 0 else len(body)]
            if EMIT_RE.search(seg):
                out.append(fm.group(1))
            continue
        if body[j] != "{":
            continue
        be = _brace_end(body, j)
        if be < 0:
            continue
        if EMIT_RE.search(body[j:be]) or _log_shaped_params(body, fm.end(), p_close):
            out.append(fm.group(1))
    return out


def _log_shaped_params(text, p_open, p_close):
    """Two `String` parameters — the shape of a log API, whatever the names.

    [wide4-02] Discovery is behavioural (the body emits), but a *declared* log
    entry point with an EMPTY body is exactly the shape witness #4 hid: a
    template-interpolation bug blanked `fun trace(cat: String, message: String)
    { }`, the census never saw it, and nothing complained. A public member that
    takes (String, String) and is not a log method is a log method that emits
    nothing — that is check A's business either way.
    """
    params = [text[p_open + 1 + a:p_open + 1 + b]
              for a, b in split_top(text[p_open + 1:p_close])]
    if len(params) < 2:
        return False
    for raw in params[:2]:
        p = raw.strip()
        if ":" not in p:
            return False
        typ = p.split(":", 1)[1].split("=", 1)[0].strip()
        if typ != "String":
            return False
    return True


EMIT_RE = re.compile(r"\b(?:log\s*\(|enqueue\s*\()")


def _fun_body(code, name):
    """(body_start_offset, body_text) of `fun <name>(…) { … }`, or None."""
    m = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", code)
    if not m:
        return None
    op = code.find("(", m.end() - 1)
    cl = _paren_end(code, op)
    if cl < 0:
        return None
    brace = code.find("{", cl)
    if brace < 0:
        return None
    head = code[cl + 1:brace]
    if re.search(r"(?<![=!<>+\-*/])=(?!=)", head):
        return None  # expression body: no emission to inspect
    end = _brace_end(code, brace)
    if end < 0:
        return None
    return (brace + 1, code[brace + 1:end])


def emission_problems(appjava_dir, methods):
    """D: a signature is not a side effect.

    [wide3/10] `fun info(category: String, message: String) { if (false) log(…) }`
    keeps the signature (A passes), keeps the call-site count (C passes) and
    emits nothing — every AppLogger.info probe in the app becomes a no-op while
    the census still counts it as alive. Each discovered method must contain a
    LIVE emission (`log(` sink call, or the debug channel's `enqueue(`).

    [wide4-02] "Live" is decided by `dead_guarded` against the lexer's code view
    (see there for the `when (false)` witness). The body is located in the
    comments view — both views keep the source length, so the offsets agree.
    """
    problems = []
    comments, code = lex_views(read(os.path.join(appjava_dir, APPLOGGER_REL)))
    for name in methods:
        body = _fun_body(comments, name)
        if body is None:
            problems.append(
                "D: cannot locate the body of AppLogger.%s — the emission check "
                "cannot run (expression body or renamed shape)" % name)
            continue
        base, text = body
        calls = list(EMIT_RE.finditer(text))
        live = [c for c in calls if not dead_guarded(code, base + c.start())]
        if not live:
            why = "its only emission sits in dead code" if calls else "it never emits"
            problems.append(
                "D: AppLogger.%s emits nothing — %s. Every probe fired through "
                "this method is silently dropped at runtime." % (name, why))
    return problems


def masked_tree(appjava_dir):
    """[(relpath, raw_text, comment_masked_view)] over main-source .kt files.

    One lexer pass for the whole tree: `corpus_of` (check B/C), the census
    mirror (check C) and the comment-probe scan (check E) all need the same
    view, and each used to strip every file again — three full-tree passes for
    one answer (37 s vs 21 s before the shared lexer). A file the lexer cannot
    settle raises `KotlinLexError`; the caller turns that into a problem instead
    of judging a half-masked view.
    """
    out = []
    for r, _, ns in os.walk(appjava_dir):
        for n in ns:
            if not n.endswith(".kt"):
                continue
            p = os.path.join(r, n)
            raw = read(p)
            ok, reason, views = kotlin_analyse(raw)
            if not ok:
                raise KotlinLexError("%s: %s" % (os.path.relpath(p, appjava_dir), reason))
            out.append((os.path.relpath(p, appjava_dir), raw, views["comments"]))
    return out


def corpus_of(tree):
    """Comment-stripped corpus text over all main-source .kt files.

    [wide3/10] Comments were part of the corpus, so a commented-out probe (or a
    `// AppLogger.info("Cat", …)` example in prose) counted as a live declaration
    and kept a dead RARE entry's permission slip alive. String literals are KEPT:
    RARE keys are embedded inside log literals (`"step=coldParse.offmain"`).
    """
    return "\n".join(comments for _rel, _raw, comments in tree), len(tree)


def stripped_mirror(tree):
    """Temp copy of the main source with comments blanked.

    The census (`liveness_audit.declared`) is the OTHER side of check C's
    comparison; it reads the tree itself, so it must see the same notion of "call
    site" as `corpus_of` — otherwise one prose mention (`// Routed through
    AppLogger.warning()`) makes the counts disagree. The caller removes the dir.
    """
    d = tempfile.mkdtemp(prefix="liveness-mirror-")
    for rel, _raw, comments in tree:
        dst = os.path.join(d, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "w", encoding="utf-8") as f:
            f.write(comments)
    return d


COMMENT_PROBE_RE = re.compile(
    r"\bAppLogger\s*\.\s*(?:info|warning|error|debug)\s*\(")


def _paren_close_quoted(text, open_idx):
    """Index of the `)` closing the `(` at open_idx, string-aware (or -1)."""
    depth, quote, i = 0, None, open_idx
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def commented_probe_problems(tree):
    """E: a probe that exists only inside a comment is a lie ([wide3/10]).

    The corpus used to include comments, so `// AppLogger.info("FakeCat", "m")`
    (or a probe someone commented out) counted as a live declaration: the raw
    count and the census agreed ON THE COMMENT (`raw == enumerated`) and a dead
    RARE entry kept its permission slip. Comments are now stripped before either
    side looks (see `corpus_of` / `stripped_mirror`) — but stripping silently is
    not enough: the shape must be LOUD, or the next reader believes the probe
    exists. Real tree: zero such lines (verified 2026-10-25); a hit means someone
    disabled a probe with `//` instead of deleting it.

    [wide4-02 family-hole/3] The old regex demanded `Name(` with no space and a
    double-quoted FIRST argument on the SAME LINE. `// AppLogger.info (\\n//
    "Fake", "x"\\n// )` walked past on the space, and — far more common in this
    repo — `// AppLogger.info(TAG, "…")` walked past on the identifier, because
    production probes pass a constant tag, not a literal. The call SHAPE is now
    matched loosely and the argument list is read structurally; only a bare
    mention (`AppLogger.warning()` in prose) is exempt, because it has no
    arguments to restore.
    """
    problems = []
    for rel, text, blanked in tree:
        for m in COMMENT_PROBE_RE.finditer(text):
            if blanked[m.start():m.end()].strip():
                continue  # live code, not a comment
            close = _paren_close_quoted(text, m.end() - 1)
            args = text[m.end():close] if close > 0 else ""
            if not args.strip():
                continue  # bare mention of the method name, nothing to restore
            line = text.count("\n", 0, m.start()) + 1
            problems.append(
                "E: %s:%d spells a probe call inside a comment (%r) — comments "
                "are not declarations: the census does not count it and the next "
                "reader will believe the probe exists. Restore the call or delete "
                "the line." % (rel, line,
                               re.sub(r"\s+", " ", m.group(0) + args)[:52]))
    return problems


def run_checks(appjava_dir):
    """Returns a list of human-readable problems (empty = clean)."""
    problems = []
    # [wide4-02] A file the lexer cannot settle must be a PROBLEM, not a silent
    # skip: every criterion below compares a stripped view against the census,
    # and a half-masked view is exactly how a probe disappears from both sides.
    try:
        tree = masked_tree(appjava_dir)
    except KotlinLexError as exc:
        return ["L: main source cannot be tokenised (%s) — every criterion "
                "compares a comment-stripped view against the census, so a "
                "half-masked tree cannot be judged" % exc]
    corpus, nfiles = corpus_of(tree)
    mirror = stripped_mirror(tree)
    try:
        cats, _tags, _steps, _events = liveness_audit.declared(mirror)
    finally:
        shutil.rmtree(mirror, ignore_errors=True)

    # ── A. method coverage: behaviour-discovered methods must be enumerated ──
    try:
        methods = applogger_log_methods(appjava_dir)
    except KotlinLexError as exc:
        return problems + ["L: AppLogger.kt cannot be tokenised (%s) — the "
                           "method census cannot run" % exc]
    if not methods:
        problems.append(
            "A: no public `fun X(<a>: String, <b>: String)` signatures found in "
            "AppLogger.kt — either the file moved (update APPLOGGER_REL) or the "
            "public log API changed shape; the census is running blind either way."
        )
    for m in methods:
        fixture_dir = tempfile.mkdtemp(prefix="liveness-a-")
        try:
            fx = os.path.join(fixture_dir, "ProbeFixture.kt")
            with open(fx, "w", encoding="utf-8") as f:
                f.write(
                    'package probe\n\n'
                    'fun probeCall() {\n'
                    '    AppLogger.%s("GateProbeCategory", "msg")\n'
                    '}\n' % m
                )
            fcats, _t, _s, _e = liveness_audit.declared(fixture_dir)
            if "GateProbeCategory" not in fcats:
                problems.append(
                    "A: AppLogger.%s( call sites are NOT enumerated by "
                    "liveness_audit.declared() — every probe fired through this "
                    "method is invisible to the census by construction. Extend "
                    "the method alternation in scripts/diag/liveness_audit.py." % m
                )
        finally:
            shutil.rmtree(fixture_dir, ignore_errors=True)

    # ── B. RARE freshness: every entry must resolve to a live declaration ──
    for (kind, key) in sorted(liveness_audit.RARE):
        if key not in corpus:
            problems.append(
                "B: RARE entry (%s, %s) no longer exists anywhere in main "
                "source — the probe was deleted but its permission slip "
                "remains. Prune the entry from scripts/diag/liveness_audit.py "
                "(RARE dict)." % (kind, key)
            )

    # ── C. call-site completeness: raw count == enumerated count ──
    enumerated = sum(len(v) for v in cats.values())
    raw = 0
    for m in methods:
        raw += len(re.findall(r"AppLogger\.%s\(" % re.escape(m), corpus))
    if raw != enumerated:
        problems.append(
            "C: declared() enumerates %d AppLogger call sites but main source "
            "contains %d — the census regex has drifted (the D4 incident "
            "shape: a `[dwiev]` single-char class hid 47.6%% of sites). Diff "
            "the patterns in scripts/diag/liveness_audit.py declared()."
            % (enumerated, raw)
        )

    # ── D. emission: a signature is not a side effect ([wide3/10]) ──
    # ── E. a probe that exists only in a comment is not a probe ([wide3/10]) ──
    try:
        problems.extend(emission_problems(appjava_dir, methods))
        problems.extend(commented_probe_problems(tree))
    except KotlinLexError as exc:
        problems.append("L: main source cannot be tokenised (%s) — the emission "
                        "and comment-probe checks cannot run" % exc)
    return problems


def self_test():
    """Fixture ground truth: each check must CATCH the violation class it owns."""
    failures = []

    def expect(name, cond, detail=""):
        # [wide3/10] print every arm: a self-test that only speaks when it fails
        # is indistinguishable from a self-test that never ran.
        print("  %s %s" % ("✅" if cond else "❌", name))
        if not cond:
            failures.append("%s  %s" % (name, detail))

    # CLEAN tree: real-shaped AppLogger.kt (the four real methods), corpus
    # containing every live RARE key so Check B passes, call counts aligned.
    clean_dir = tempfile.mkdtemp(prefix="liveness-clean-")
    pkg = os.path.join(clean_dir, "com", "rikkaminis", "app", "logging")
    os.makedirs(pkg)
    rare_keys = [k for (_kind, k) in liveness_audit.RARE]
    with open(os.path.join(pkg, "AppLogger.kt"), "w", encoding="utf-8") as f:
        f.write(
            "package com.rikkaminis.app.logging\n"
            "object AppLogger {\n"
            "    fun info(category: String, message: String) { "
            "log(\"INFO\", category, message) }\n"
            "    fun warning(category: String, message: String) { "
            "log(\"WARN\", category, message) }\n"
            "    fun error(category: String, message: String) { "
            "log(\"ERROR\", category, message) }\n"
            "    fun debug(category: String, message: String) { "
            "enqueue(\"DEBUG\", category, message) }\n"
            "}\n"
        )
    body_lines = ["package com.example", ""]
    for i, k in enumerate(rare_keys):
        body_lines.append('val rareAnchor%d = "step=%s"' % (i, k))
    body_lines += [
        "",
        "fun a() { AppLogger.info(\"Cat\", \"m\") }",
        "fun b() { AppLogger.debug(\"Dbg\", \"m\") }",
        "fun c() { AppLogger.warning(\"Cat\", \"m\") }",
        "fun d() { AppLogger.error(\"Cat\", \"m\") }",
    ]
    with open(os.path.join(pkg, "Other.kt"), "w", encoding="utf-8") as f:
        f.write("\n".join(body_lines) + "\n")
    expect("self/clean passes", run_checks(clean_dir) == [],
           str(run_checks(clean_dir)))
    shutil.rmtree(clean_dir, ignore_errors=True)

    # DIRTY-A: a public log method the census does not know → Check A fires.
    dirty_dir = tempfile.mkdtemp(prefix="liveness-da-")
    pkg = os.path.join(dirty_dir, "com", "rikkaminis", "app", "logging")
    os.makedirs(pkg)
    with open(os.path.join(pkg, "AppLogger.kt"), "w", encoding="utf-8") as f:
        f.write(
            "package com.rikkaminis.app.logging\n"
            "object AppLogger {\n"
            "    fun info(category: String, message: String) { "
     "log(\"INFO\", category, message) }\n"
            "    fun fatal(category: String, message: String) { "
     "log(\"FATAL\", category, message) }\n"
            "}\n"
        )
    with open(os.path.join(pkg, "Other.kt"), "w", encoding="utf-8") as f:
        f.write(
            "package com.rikkaminis.app.logging\n"
            + "".join('val k%d = "step=%s"\n' % (i, k) for i, k in enumerate(rare_keys))
            + "fun a() { AppLogger.info(\"Cat\", \"m\") }\n"
        )
    problems = run_checks(dirty_dir)
    expect("self/DIRTY-A caught", any(p.startswith("A:") for p in problems),
           str(problems))
    shutil.rmtree(dirty_dir, ignore_errors=True)

    # DIRTY-B: a RARE key absent from the corpus → Check B fires.
    dirty_dir = tempfile.mkdtemp(prefix="liveness-db-")
    pkg = os.path.join(dirty_dir, "com", "rikkaminis", "app", "logging")
    os.makedirs(pkg)
    with open(os.path.join(pkg, "AppLogger.kt"), "w", encoding="utf-8") as f:
        f.write(
            "package com.rikkaminis.app.logging\n"
            "object AppLogger {\n"
            "    fun info(category: String, message: String) { "
     "log(\"INFO\", category, message) }\n"
            "}\n"
        )
    with open(os.path.join(pkg, "Other.kt"), "w", encoding="utf-8") as f:
        f.write("package com.example\nfun a() { AppLogger.info(\"Cat\", \"m\") }\n")
    problems = run_checks(dirty_dir)
    expect("self/DIRTY-B caught", any(p.startswith("B:") for p in problems),
           str(problems))
    shutil.rmtree(dirty_dir, ignore_errors=True)

    # DIRTY-C: regex drift — declared() under-counts real call sites.
    dirty_dir = tempfile.mkdtemp(prefix="liveness-dc-")
    pkg = os.path.join(dirty_dir, "com", "rikkaminis", "app", "logging")
    os.makedirs(pkg)
    with open(os.path.join(pkg, "AppLogger.kt"), "w", encoding="utf-8") as f:
        f.write(
            "package com.rikkaminis.app.logging\n"
            "object AppLogger {\n"
            "    fun info(category: String, message: String) { "
     "log(\"INFO\", category, message) }\n"
            "}\n"
        )
    with open(os.path.join(pkg, "Other.kt"), "w", encoding="utf-8") as f:
        # 3 real info() calls; one passes a const reference as the category.
        # The drifted pattern below (literal-only first arg — the D4 shape)
        # then enumerates only 2 of the 3, and Check C must fire.
        f.write(
            "package com.rikkaminis.app.logging\n"
            "val CAT = \"Cat\"\n"
            "fun a() { AppLogger.info(CAT, \"m\") }\n"
            "fun b() { AppLogger.info(\"Cat\", \"m\") }\n"
            "fun c() { AppLogger.info(\"Cat\", \"m\") }\n"
        )
    orig = liveness_audit.DECLARED_CAT_PAT
    liveness_audit.DECLARED_CAT_PAT = re.compile(
        r"AppLogger\.info\(\s*\"([^,]+?)\"\s*,")
    try:
        problems = run_checks(dirty_dir)
    finally:
        liveness_audit.DECLARED_CAT_PAT = orig
    expect("self/DIRTY-C caught", any(p.startswith("C:") for p in problems),
           str(problems))
    shutil.rmtree(dirty_dir, ignore_errors=True)

    # ── [wide3/10] three re-introduction shapes, one arm each ────────────────
    def _appdir(prefix, applogger_text, other_text):
        d = tempfile.mkdtemp(prefix=prefix)
        pkg = os.path.join(d, "com", "rikkaminis", "app", "logging")
        os.makedirs(pkg)
        with open(os.path.join(pkg, "AppLogger.kt"), "w", encoding="utf-8") as fh:
            fh.write(applogger_text)
        with open(os.path.join(pkg, "Other.kt"), "w", encoding="utf-8") as fh:
            fh.write(other_text)
        return d

    live_anchors = "".join('val k%d = "step=%s"\n' % (i, k)
                           for i, k in enumerate(rare_keys))

    # A2 (scope): a NEW public log method whose first parameter is not named
    # `category`. Positional call sites are unaffected by the rename, so the
    # method is real API and the census must enumerate it.
    d = _appdir(
        "liveness-a2-",
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    fun info(category: String, message: String) { log("INFO", category, message) }\n'
        '    fun trace(cat: String, message: String) { log("TRACE", cat, message) }\n'
        "}\n",
        "package com.example\n" + live_anchors
        + 'fun a() { AppLogger.info("Cat", "m") }\n')
    problems = run_checks(d)
    expect("self/A2 method with a renamed first parameter is caught",
           any(p.startswith("A:") and "trace" in p for p in problems), str(problems))
    shutil.rmtree(d, ignore_errors=True)

    # D (dataflow): the sink call exists but sits in dead code — signature and
    # call-site count are untouched, the probe emits nothing.
    d = _appdir(
        "liveness-d2-",
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    fun info(category: String, message: String) { '
        'if (false) log("INFO", category, message) }\n'
        "}\n",
        "package com.example\n" + live_anchors
        + 'fun a() { AppLogger.info("Cat", "m") }\n')
    problems = run_checks(d)
    expect("self/D2 a dead sink call in the method body is caught",
           any(p.startswith("D:") and "info" in p for p in problems), str(problems))
    shutil.rmtree(d, ignore_errors=True)

    # B2 (parser-death): the RARE key survives only inside a comment. Unstripped
    # corpora counted that as a live declaration and kept a dead entry's
    # permission slip alive.
    ghost = rare_keys[0]
    commented = "".join('val k%d = "step=%s"\n' % (i, k)
                        for i, k in enumerate(rare_keys[1:]))
    commented += '// val ghost = "step=%s"\n' % ghost
    d = _appdir(
        "liveness-b2-",
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    fun info(category: String, message: String) { log("INFO", category, message) }\n'
        "}\n",
        "package com.example\n" + commented
        + 'fun a() { AppLogger.info("Cat", "m") }\n')
    problems = run_checks(d)
    expect("self/B2 a RARE key that survives only in a comment is caught",
           any(p.startswith("B:") and ghost in p for p in problems), str(problems))
    shutil.rmtree(d, ignore_errors=True)

    # E2: the exact re-introduction shape — a probe call that lives only in a
    # comment inside a real log method. Inert for the census, so the gate has to
    # say it out loud or the next reader believes the probe exists.
    d = _appdir(
        "liveness-e2-",
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    fun warning(category: String, message: String) {\n'
        '        // AppLogger.info("FakeCommentCat", "fake")\n'
        '        log("WARN", category, message)\n'
        "    }\n"
        "}\n",
        "package com.example\n" + live_anchors
        + 'fun a() { AppLogger.warning("Cat", "m") }\n')
    problems = run_checks(d)
    expect("self/E2 a probe spelled only inside a comment is caught",
           any(p.startswith("E:") and "FakeCommentCat" in p for p in problems),
           str(problems))
    shutil.rmtree(d, ignore_errors=True)

    # ── [wide4-02] arms: the four shapes the external witnesses got through ──
    base_logger = (
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    fun info(category: String, message: String) { log("INFO", category, message) }\n')

    # A3 (family-hole/2): the method is real and emits, but the signature has a
    # DEFAULT and the parameter list is wrapped across lines — the old
    # "two String params, one line" pattern could not see it.
    d = _appdir(
        "liveness-a3-",
        base_logger
        + "    fun trace(category: String,\n"
        '              message: String = "") { log("TRACE", category, message) }\n'
        "}\n",
        "package com.example\n" + live_anchors
        + 'fun a() { AppLogger.info("Cat", "m") }\n')
    problems = run_checks(d)
    expect("self/A3 a default-parameter multi-line log method is discovered",
           any(p.startswith("A:") and "trace" in p for p in problems), str(problems))
    shutil.rmtree(d, ignore_errors=True)

    # D3 (family-hole/1): the emission is real only inside `when (false)`.
    d = _appdir(
        "liveness-d3-",
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    fun info(category: String, message: String) {\n'
        '        when (false) { true -> log("INFO", category, message) '
        "else -> Unit }\n"
        "    }\n"
        "}\n",
        "package com.example\n" + live_anchors
        + 'fun a() { AppLogger.info("Cat", "m") }\n')
    problems = run_checks(d)
    expect("self/D3 a sink call under `when (false)` is dead, not live",
           any(p.startswith("D:") and "info" in p for p in problems), str(problems))
    shutil.rmtree(d, ignore_errors=True)

    # E3 (family-hole/3): the commented probe passes an identifier tag instead of
    # a literal — exactly how every production probe is written.
    d = _appdir(
        "liveness-e3-",
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    fun warning(category: String, message: String) {\n'
        '        // AppLogger.info(TAG, "disabled probe")\n'
        '        log("WARN", category, message)\n'
        "    }\n"
        "}\n",
        "package com.example\n" + live_anchors
        + 'fun a() { AppLogger.warning("Cat", "m") }\n')
    problems = run_checks(d)
    expect("self/E3 a commented probe with a non-literal first argument is caught",
           any(p.startswith("E:") and "disabled probe" in p for p in problems),
           str(problems))
    shutil.rmtree(d, ignore_errors=True)

    # L3 (parser-death/4): `"${"/*"}"` used to move the stripper out of string
    # mode and open a REAL block comment, blanking the emitting `trace` method
    # (and therefore check A) until the next `"*/"`.
    d = _appdir(
        "liveness-l3-",
        "package com.rikkaminis.app.logging\n"
        "object AppLogger {\n"
        '    val _open = "${"/*"}"\n'
        "    fun info(category: String, message: String) { "
        'log("INFO", category, message) }\n'
        '    fun trace(category: String, message: String) { '
        'log("TRACE", category, message) }\n'
        '    val _close = "*/"\n'
        "}\n",
        "package com.example\n" + live_anchors
        + 'fun a() { AppLogger.info("Cat", "m") }\n')
    problems = run_checks(d)
    expect('self/L3 a `${"/*"}` template cannot blank the following declaration',
           any(p.startswith("A:") and "trace" in p for p in problems), str(problems))
    shutil.rmtree(d, ignore_errors=True)

    if failures:
        for f in failures:
            print("FAIL  " + f)
        return 1
    print("✅ self-test: clean tree passes; A/B/C/D + renamed-parameter/dead-sink/commented-key arms all caught")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(
        os.path.join(SCRIPT_DIR, "..", ".."))
    problems = run_checks(os.path.join(root, MAIN_JAVA))
    if problems:
        print("❌ liveness static guard — %d problem(s):" % len(problems))
        for p in problems:
            print("  " + p)
        return 1
    print("✅ liveness static guard: method coverage / RARE freshness / "
          "call-site completeness all clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
