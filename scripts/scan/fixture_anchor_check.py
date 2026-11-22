#!/usr/bin/env python3
"""[surgery-1122 G4] Fast gate: every fixture anchor still exists where its gate reads.

The fixture self-test is the expensive part of the scan gate (~121 s), so CI
skips it when `scripts/scan/**` is untouched. That optimisation has a hole
proven on 2026-11-22: a src-only change re-spelled the text a fixture anchor
was pinned to, the arm raised AssertionError, and nobody saw it for days
because the fixtures never ran. The gate was effectively FAILING OPEN.

This gate is the cheap half: it never executes a fixture, it only asks
"does the verbatim anchor that each arm substitutes still occur in the text
this gate actually reads?". A drifted anchor means the arm cannot run at all —
which is always a defect, never a passing state.

Recognised mutation helpers: sub_once / sub_all / replace_once / _mutate /
mutate. Their second positional argument is the anchor; a computed (non-literal)
anchor is not judged — it is derived at runtime and its own arm proves it.

Acceptance — an anchor is accepted if ANY of these holds:

  (R) it occurs in a file this gate READS. The read set is derived from the
      gate's own string literals that resolve to existing paths under the repo
      root (declared files, or a declared directory when the gate walks it).
      A gate that walks the app tree (`os.walk` / `rglob` / `listdir` over
      `src/android/app/src` or an ancestor) reads the whole app tree, and that
      is its scope. This is the rule that matters: an anchor living in a file
      the gate never opens is not evidence the arm can run.

  (F) it occurs inside a strictly LONGER string literal in the gate itself.
      Synthetic fixtures are assembled from literal text held in the gate
      (e.g. `BASE = \"...\"` then `_mutate(BASE, \"...\", ...)`), so the base
      text legitimately lives only in the gate source. The anchor literal
      itself is not allowed to satisfy this rule (every anchor is a literal).

  (P) it is PRODUCED by the gate: it appears as the replacement argument of
      another mutation call in the same file. A fixture built from a fixture
      (mutate A→B, then mutate B→C) legitimately contains text that exists
      nowhere else.

Escapes: `# anchor-ok: <why>` on the line above an intentionally-absent anchor
(an arm that asserts "this text is gone" has a reason; a pinned hand-off has
none).

Known residual (deliberate, documented): anchors passed to a BARE
`.replace(old, new)` are not judged — the suite has ~1580 such literals and
judging them would cost more than it buys. Gates that want that coverage
should route the mutation through one of the five helpers above.

Usage: python3 scripts/scan/fixture_anchor_check.py [repo_root] [--selftest]
Exit:  0 PASS, 1 drifted anchor (file:line printed), 2 usage.
"""
import ast
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCAN_DIR = HERE
APP_TREE = os.path.join("src", "android", "app", "src")
ESCAPE = "anchor-ok:"
SELFTEST_MARK = "FIXTURE_ANCHOR_SELFTEST=PASS"
# Anchors shorter than this are too generic to judge (a bare identifier or a
# punctuation run occurs everywhere; substituting it proves nothing anyway).
MIN_ANCHOR = 24
# The mutation helpers whose 2nd positional argument is the anchor text.
HELPERS = ("sub_once", "sub_all", "replace_once", "_mutate", "mutate")
# A gate that contains one of these walks a directory, so its read set is the
# directory contents rather than one file.
WALK_MARKERS = ("os.walk", "rglob", "listdir", "glob(")
# Guard against a directory literal that would drag the whole repo in.
MAX_DIR_FILES = 6000

def _read(path):
    try:
        with open(path, encoding="utf-8", errors="ignore") as fh:
            return fh.read()
    except OSError:
        return ""

def iter_source_files(root):
    base = os.path.join(root, APP_TREE)
    if not os.path.isdir(base):
        return []
    out = []
    for dirpath, _dirs, files in os.walk(base):
        for fn in files:
            if fn.endswith(".kt"):
                out.append(os.path.join(dirpath, fn))
    return out

def load_tree(root):
    texts = []
    for path in iter_source_files(root):
        text = _read(path)
        if text:
            texts.append(text)
    return "\n".join(texts)

def load_dir(dirpath):
    texts = []
    count = 0
    for dirpath2, _dirs, files in os.walk(dirpath):
        for fn in files:
            if count >= MAX_DIR_FILES:
                return "\n".join(texts)
            count += 1
            text = _read(os.path.join(dirpath2, fn))
            if text:
                texts.append(text)
    return "\n".join(texts)

def literal_anchor(node):
    """The `old` string of a substitution call, or None when it is computed."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.BinOp):
        try:
            value = ast.literal_eval(node)
        except (ValueError, SyntaxError):
            return None
        return value if isinstance(value, str) else None
    return None

def anchor_calls(tree):
    """[(lineno, anchor, replacement)] for every helper(text, anchor, repl…) call."""
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        fn = node.func
        name = fn.id if isinstance(fn, ast.Name) else (fn.attr if isinstance(fn, ast.Attribute) else "")
        if name not in HELPERS:
            continue
        if len(node.args) < 2:
            continue
        anchor = literal_anchor(node.args[1])
        if not anchor:
            continue
        repl = literal_anchor(node.args[2]) if len(node.args) >= 3 else None
        out.append((node.lineno, anchor, repl))
    return out

def string_literals(tree):
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            out.append(node.value)
    return out

def read_scope(root, src, tree):
    """The text this gate reads: declared files, declared dirs when it walks,
    and the app tree when it walks the app tree."""
    literals = string_literals(tree)
    walks = any(marker in src for marker in WALK_MARKERS)
    app_tree_abs = os.path.abspath(os.path.join(root, APP_TREE))
    root_abs = os.path.abspath(root)
    parts = []
    covered_dirs = []
    wants_app_tree = False
    for value in literals:
        if not value or "\n" in value or len(value) < 6:
            continue
        full = os.path.abspath(os.path.join(root, value))
        if os.path.isfile(full):
            parts.append(_read(full))
            continue
        if not walks or not os.path.isdir(full) or full == root_abs:
            continue
        if full == app_tree_abs or app_tree_abs.startswith(full + os.sep):
            # the gate walks the app tree (or an ancestor of it)
            wants_app_tree = True
        elif full.startswith(app_tree_abs + os.sep):
            covered_dirs.append(full)
    if wants_app_tree:
        parts.append(load_tree(root))
    for full in covered_dirs:
        parts.append(load_dir(full))
    return "\n".join(parts), literals

def escaped(lines, lineno):
    for back in range(1, 4):
        idx = lineno - 1 - back
        if idx < 0:
            break
        line = lines[idx]
        if ESCAPE in line:
            return True
        if line.strip() and not line.strip().startswith("#"):
            break
    return False

def check_gate(path, root):
    violations = []
    src = _read(path)
    if not src:
        return ["%s: cannot read (fail-closed)" % os.path.basename(path)]
    lines = src.split("\n")
    try:
        tree = ast.parse(src)
    except SyntaxError as exc:
        return ["%s: cannot parse (fail-closed): %s" % (os.path.basename(path), exc)]
    calls = anchor_calls(tree)
    if not calls:
        return violations
    scope_text, literals = read_scope(root, src, tree)
    produced = set(repl for _ln, _a, repl in calls if repl)
    for lineno, anchor, _repl in calls:
        if len(anchor.strip()) < MIN_ANCHOR:
            continue
        if anchor in scope_text:
            continue
        if anchor in produced:
            continue
        if any(len(text) > len(anchor) and anchor in text for text in literals):
            continue
        if escaped(lines, lineno):
            continue
        first = anchor.strip().split("\n")[0][:100]
        violations.append(
            "%s:%d: fixture anchor no longer exists where this gate reads — the arm cannot "
            "run, so this gate is FAILING OPEN on it: %r" % (os.path.basename(path), lineno, first))
    return violations

def run(root):
    if not os.path.isdir(root):
        print("fixture_anchor_check: not a directory: %s" % root)
        return 2
    if not os.path.isdir(os.path.join(root, APP_TREE)):
        print("fixture_anchor_check: cannot read the app tree under %s (fail-closed)" % root)
        return 2
    violations = []
    for fn in sorted(os.listdir(SCAN_DIR)):
        if not fn.endswith(".py") or fn.startswith("_"):
            continue
        violations.extend(check_gate(os.path.join(SCAN_DIR, fn), root))
    if violations:
        print("FAIL fixture_anchor_check")
        for violation in violations:
            print("  " + violation)
        return 1
    print("PASS fixture_anchor_check (every literal fixture anchor still occurs where its gate reads)")
    return 0

# ── selftest ───────────────────────────────────────────────────────────────
SOURCE = '''package com.rikkaminis.app.backup

class ConfigBackup {
    fun import() {
        try {
            field.valueSchema.validate(value)
            persistence.capture { field.write(value) }
        } catch (t: Throwable) { }
    }
}
'''

OTHER = '''package com.rikkaminis.app.worker

class ModelExecutionService {
    private fun finish() {
        if (queuedRequests.isEmpty() && activeRequests.isEmpty()) selfReap()
    }
}
'''

WORKFLOW = '''name: build-apk
jobs:
  build:
    steps:
      - run: echo "the publish step is gated on the main branch here"
'''

GOOD_GATE = '''
A_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"

def arm(src):
    sub_once(src, "            persistence.capture { field.write(value) }\\n", "x")
'''

DRIFTED_GATE = '''
A_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"

def arm(src):
    sub_once(src, "            field.valueSchema.validate(value)\\n"
                  "            field.write(value)\\n",
             "x")
'''

# The anchor exists in the app tree — but in a file this gate never reads.
# That is exactly the hole F7 reported: today's rule says "in the tree" and
# passes, so the arm could not run at all and nobody would know.
CROSS_FILE_GATE = '''
A_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"

def arm(src):
    sub_once(src, "        if (queuedRequests.isEmpty() && activeRequests.isEmpty()) selfReap()\\n", "x")
'''

# The anchor lives outside the app tree, in a file this gate declares. That is
# legitimate and must NOT be reported.
OUTSIDE_TREE_GATE = '''
W_REL = ".github/workflows/build-apk.yml"

def arm(src):
    sub_once(src, "      - run: echo \\"the publish step is gated on the main branch here\\"\\n", "x")
'''

# Same shape, but the helper is `_mutate` — the family the old gate ignored.
MUTATE_DRIFT_GATE = '''
BASE = "some synthetic fixture text that is long enough to be a fixture base"

def _mutate(base, needle, replacement, label):
    if needle not in base:
        raise SystemExit("drifted")
    return base.replace(needle, replacement, 1)

def arm():
    return _mutate(BASE, "this anchor exists nowhere at all in any fixture", "x", "t")
'''

# A fixture built from a fixture: the anchor is the replacement produced by an
# earlier mutation, so it legitimately exists nowhere in the tree or in a base.
MUTATE_PRODUCED_GATE = '''
BASE = "some synthetic fixture text that is long enough to be a fixture base"

def _mutate(base, needle, replacement, label):
    if needle not in base:
        raise SystemExit("drifted")
    return base.replace(needle, replacement, 1)

def arm():
    first = _mutate(BASE, "some synthetic fixture text", "produced text that only exists after a mutation", "a")
    return _mutate(first, "produced text that only exists after a mutation", "x", "b")
'''

EXEMPT_GATE = '''
A_REL = "src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt"

def arm(src):
    # anchor-ok: this arm asserts the text is GONE, so absence is the point
    sub_once(src, "            field.valueSchema.validate(value)\\n", "x")
'''

SHORT_GATE = '''
def arm(src):
    sub_once(src, "field.write(value)", "y")
'''

def _arm(label, gate_src, expect_rc, marker, failures, extra=None):
    import shutil
    import tempfile
    root = tempfile.mkdtemp(prefix="anchor-arm-")
    try:
        scan = os.path.join(root, "scripts", "scan")
        os.makedirs(scan)
        app = os.path.join(root, APP_TREE, "main", "java", "com", "rikkaminis", "app", "backup")
        os.makedirs(app)
        worker = os.path.join(root, APP_TREE, "main", "java", "com", "rikkaminis", "app", "worker")
        os.makedirs(worker)
        with open(os.path.join(app, "ConfigBackup.kt"), "w", encoding="utf-8") as fh:
            fh.write(SOURCE)
        with open(os.path.join(worker, "ModelExecutionService.kt"), "w", encoding="utf-8") as fh:
            fh.write(OTHER)
        for rel, body in (extra or {}).items():
            target = os.path.join(root, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8") as fh:
                fh.write(body)
        with open(os.path.join(scan, "fixture_gate_under_test.py"), "w", encoding="utf-8") as fh:
            fh.write(gate_src)
        rc, out = _call(root, scan)
        print("selftest %-32s -> rc=%d" % (label, rc))
        if rc != expect_rc or marker not in out:
            failures.append("%s: rc=%d out=%s" % (label, rc, out[-400:]))
    finally:
        shutil.rmtree(root)

def _call(root, scan_dir):
    import subprocess
    proc = subprocess.run(
        [sys.executable, os.path.abspath(__file__), root, "--scan-dir", scan_dir],
        capture_output=True, text=True)
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")

def selftest():
    failures = []
    _arm("live anchor in a read file", GOOD_GATE, 0, "PASS fixture_anchor_check", failures)
    _arm("drifted anchor", DRIFTED_GATE, 1, "FAILING OPEN", failures)
    _arm("anchor in a file this gate never reads", CROSS_FILE_GATE, 1, "FAILING OPEN", failures)
    _arm("anchor outside the app tree, declared",
         OUTSIDE_TREE_GATE, 0, "PASS fixture_anchor_check", failures,
         extra={".github/workflows/build-apk.yml": WORKFLOW})
    _arm("_mutate drifted anchor", MUTATE_DRIFT_GATE, 1, "FAILING OPEN", failures)
    _arm("_mutate anchor produced by a fixture", MUTATE_PRODUCED_GATE, 0, "PASS fixture_anchor_check", failures)
    _arm("documented exemption", EXEMPT_GATE, 0, "PASS fixture_anchor_check", failures)
    _arm("short anchor not judged", SHORT_GATE, 0, "PASS fixture_anchor_check", failures)
    if failures:
        for failure in failures:
            print("SELFTEST FAIL " + failure)
        return 1
    print(SELFTEST_MARK)
    return 0

def main():
    args = sys.argv[1:]
    global SCAN_DIR
    if "--selftest" in args:
        return selftest()
    if "--scan-dir" in args:
        idx = args.index("--scan-dir")
        if idx + 1 >= len(args):
            print("fixture_anchor_check: --scan-dir needs a value")
            return 2
        SCAN_DIR = args[idx + 1]
        args = args[:idx] + args[idx + 2:]
    root = next((arg for arg in args if not arg.startswith("-")), os.getcwd())
    return run(root)

if __name__ == "__main__":
    sys.exit(main())
