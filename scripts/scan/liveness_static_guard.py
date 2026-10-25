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
import liveness_audit  # noqa: E402  (single source of truth: RARE + declared())

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
DEAD_COND_RE = re.compile(
    r"(?:\bif\s*\(\s*|\bwhile\s*\(\s*)"
    r"(?:false\b|!\s*true\b|0\s*==\s*1\b|true\s*\|\||false\s*&&)"
    r"|\bfalse\s*&&|\btrue\s*\|\|")


def blank_comment(text):
    out = []
    i, n = 0, len(text)
    mode = None
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if mode == "line":
            if ch == "\n":
                mode = None
                out.append(ch)
            else:
                out.append(" ")
        elif mode == "block":
            if ch == "*" and nxt == "/":
                mode = None
                out.append("  ")
                i += 1
            else:
                out.append("\n" if ch == "\n" else " ")
        elif mode == "str":
            out.append(ch)
            if ch == "\\":
                out.append(nxt)
                i += 1
            elif ch == '"':
                mode = None
        elif mode == "raw":
            out.append(ch)
            if text[i:i + 3] == '"""':
                out.append('""')
                i += 2
                mode = None
        else:
            if ch == "/" and nxt == "/":
                mode = "line"
                out.append("  ")
                i += 1
            elif ch == "/" and nxt == "*":
                mode = "block"
                out.append("  ")
                i += 1
            elif text[i:i + 3] == '"""':
                mode = "raw"
                out.append('"""')
                i += 2
            elif ch == '"':
                mode = "str"
                out.append(ch)
            else:
                out.append(ch)
        i += 1
    return "".join(out)


def _stmt_start(code, off):
    depth = 0
    i = off - 1
    while i >= 0:
        ch = code[i]
        if ch in ")]":
            depth += 1
        elif ch in "([":
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and ch in "\n;{}":
            return i + 1
        i -= 1
    return i + 1


def dead_guarded(code, off):
    if DEAD_COND_RE.search(code[_stmt_start(code, off):off]):
        return True
    depth = 0
    i = off
    while i > 0:
        i -= 1
        ch = code[i]
        if ch in "})":
            depth += 1
        elif ch in "({":
            if depth == 0:
                if ch == "{" and DEAD_COND_RE.search(code[_stmt_start(code, i):i]):
                    return True
            else:
                depth -= 1
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


def applogger_log_methods(appjava_dir):
    """Public AppLogger log methods — derived from the SIGNATURE SHAPE, not from
    a parameter NAME.

    [wide3/10] The old pattern was `fun (\\w+)\\(\\s*category\\s*:` — it assumed
    every public log method names its first parameter `category`. A new
    `fun trace(cat: String, message: String)` (positional calls are unaffected by
    the rename) was invisible to checks A and C by construction: the census could
    never see probes fired through it. The shape that actually identifies a log
    method is "non-private member of the object taking two String parameters".
    """
    text = blank_comment(read(os.path.join(appjava_dir, APPLOGGER_REL)))
    out = []
    for m in re.finditer(
            r"^    (?!private\b)(?:public\s+|internal\s+)?fun\s+(\w+)\s*\(\s*"
            r"\w+\s*:\s*String\s*,\s*\w+\s*:\s*String\s*\)", text, re.M):
        out.append(m.group(1))
    return out


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
    """
    problems = []
    code = blank_comment(read(os.path.join(appjava_dir, APPLOGGER_REL)))
    for name in methods:
        body = _fun_body(code, name)
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


def corpus_of(appjava_dir):
    """(comment-stripped corpus_text, file_count) over all main-source .kt files.

    [wide3/10] Comments were part of the corpus, so a commented-out probe (or a
    `// AppLogger.info("Cat", …)` example in prose) counted as a live declaration
    and kept a dead RARE entry's permission slip alive. String literals are KEPT:
    RARE keys are embedded inside log literals (`"step=coldParse.offmain"`).
    """
    parts = []
    count = 0
    for r, _, ns in os.walk(appjava_dir):
        for n in ns:
            if n.endswith(".kt"):
                parts.append(blank_comment(read(os.path.join(r, n))))
                count += 1
    return "\n".join(parts), count


def stripped_mirror(appjava_dir):
    """Temp copy of the main source with comments blanked.

    The census (`liveness_audit.declared`) is the OTHER side of check C's
    comparison; it reads the tree itself, so it must see the same notion of "call
    site" as `corpus_of` — otherwise one prose mention (`// Routed through
    AppLogger.warning()`) makes the counts disagree. The caller removes the dir.
    """
    d = tempfile.mkdtemp(prefix="liveness-mirror-")
    for r, _, ns in os.walk(appjava_dir):
        for n in ns:
            if not n.endswith(".kt"):
                continue
            src = os.path.join(r, n)
            dst = os.path.join(d, os.path.relpath(src, appjava_dir))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "w", encoding="utf-8") as f:
                f.write(blank_comment(read(src)))
    return d


COMMENT_PROBE_RE = re.compile(
    r"AppLogger\.(?:info|warning|error|debug)\(\s*\"[^\"\n]*\"")


def commented_probe_problems(appjava_dir):
    """E: a probe that exists only inside a comment is a lie ([wide3/10]).

    The corpus used to include comments, so `// AppLogger.info("FakeCat", "m")`
    (or a probe someone commented out) counted as a live declaration: the raw
    count and the census agreed ON THE COMMENT (`raw == enumerated`) and a dead
    RARE entry kept its permission slip. Comments are now stripped before either
    side looks (see `corpus_of` / `stripped_mirror`) — but stripping silently is
    not enough: the shape must be LOUD, or the next reader believes the probe
    exists. Real tree: zero such lines (verified 2026-10-25); a hit means someone
    disabled a probe with `//` instead of deleting it.
    """
    problems = []
    for r, _, ns in os.walk(appjava_dir):
        for n in ns:
            if not n.endswith(".kt"):
                continue
            p = os.path.join(r, n)
            text = read(p)
            blanked = blank_comment(text)
            for m in COMMENT_PROBE_RE.finditer(text):
                if blanked[m.start():m.end()].strip():
                    continue  # live code, not a comment
                line = text.count("\n", 0, m.start()) + 1
                problems.append(
                    "E: %s:%d spells a probe call inside a comment (%r) — comments "
                    "are not declarations: the census does not count it and the next "
                    "reader will believe the probe exists. Restore the call or delete "
                    "the line." % (os.path.relpath(p, appjava_dir), line,
                                   m.group(0)[:52]))
    return problems


def run_checks(appjava_dir):
    """Returns a list of human-readable problems (empty = clean)."""
    problems = []
    corpus, nfiles = corpus_of(appjava_dir)
    mirror = stripped_mirror(appjava_dir)
    try:
        cats, _tags, _steps, _events = liveness_audit.declared(mirror)
    finally:
        shutil.rmtree(mirror, ignore_errors=True)

    # ── A. method coverage: signature-discovered methods must be enumerated ──
    methods = applogger_log_methods(appjava_dir)
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
    problems.extend(emission_problems(appjava_dir, methods))

    # ── E. a probe that exists only in a comment is not a probe ([wide3/10]) ──
    problems.extend(commented_probe_problems(appjava_dir))
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
