#!/usr/bin/env python3
"""[surgery-1122 G4] Fast gate: every fixture anchor still exists in the tree.

The fixture self-test is the expensive part of the scan gate (~121 s), so CI
skips it when `scripts/scan/**` is untouched. That optimisation has a hole
proven on 2026-11-22: a src-only change re-spelled the text a fixture anchor
was pinned to, the arm raised AssertionError, and nobody saw it for days
because the fixtures never ran. The gate was effectively FAILING OPEN.

This gate is the cheap half: it never executes a fixture, it only asks
"does the verbatim anchor that each arm substitutes still occur in the tree?".
A drifted anchor means the arm cannot run at all — which is always a defect,
never a passing state.

Scope: `sub_once(text, "<literal>", …)` style calls inside `scripts/scan/*.py`
whose anchor is a string literal (computed anchors are not judged — they are
derived at runtime and their own arm proves them). An anchor is accepted if it
appears in the gate's own declared source files or anywhere under the app tree.

Escapes: `# anchor-ok: <why>` on the line above an intentionally-absent anchor
(an arm that asserts "this text is gone" has a reason; a pinned hand-off has
none).

Usage: python3 scripts/scan/fixture_anchor_check.py [repo_root] [--selftest]
Exit:  0 PASS, 1 drifted anchor (file:line printed), 2 usage.
"""
import ast
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCAN_DIR = HERE
APP_TREE = os.path.join("src", "android", "app", "src")
ESCAPE = "anchor-ok:"
SELFTEST_MARK = "FIXTURE_ANCHOR_SELFTEST=PASS"
# Anchors shorter than this are too generic to judge (a bare identifier or a
# punctuation run occurs everywhere; substituting it proves nothing anyway).
MIN_ANCHOR = 24


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
        try:
            with open(path, encoding="utf-8", errors="ignore") as fh:
                texts.append(fh.read())
        except OSError:
            continue
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
    """[(lineno, anchor_text)] for every sub_once/…(text, anchor, …) call."""
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        fn = node.func
        name = fn.id if isinstance(fn, ast.Name) else (fn.attr if isinstance(fn, ast.Attribute) else "")
        if name not in ("sub_once", "sub_all", "replace_once"):
            continue
        if len(node.args) < 2:
            continue
        anchor = literal_anchor(node.args[1])
        if anchor:
            out.append((node.lineno, anchor))
    return out


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


def check_gate(path, tree_text):
    violations = []
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    lines = src.split("\n")
    try:
        tree = ast.parse(src)
    except SyntaxError as exc:
        return ["%s: cannot parse (fail-closed): %s" % (os.path.basename(path), exc)]
    for lineno, anchor in anchor_calls(tree):
        if len(anchor.strip()) < MIN_ANCHOR:
            continue
        if anchor in tree_text:
            continue
        if escaped(lines, lineno):
            continue
        first = anchor.strip().split("\n")[0][:100]
        violations.append(
            "%s:%d: fixture anchor no longer exists in the tree — the arm cannot run, so this "
            "gate is FAILING OPEN on it: %r" % (os.path.basename(path), lineno, first))
    return violations


def run(root):
    if not os.path.isdir(root):
        print("fixture_anchor_check: not a directory: %s" % root)
        return 2
    tree_text = load_tree(root)
    if not tree_text:
        print("fixture_anchor_check: cannot read the app tree under %s (fail-closed)" % root)
        return 2
    violations = []
    for fn in sorted(os.listdir(SCAN_DIR)):
        if not fn.endswith(".py") or fn.startswith("_"):
            continue
        violations.extend(check_gate(os.path.join(SCAN_DIR, fn), tree_text))
    if violations:
        print("FAIL fixture_anchor_check")
        for violation in violations:
            print("  " + violation)
        return 1
    print("PASS fixture_anchor_check (every literal fixture anchor still occurs in the tree)")
    return 0


# ── selftest ───────────────────────────────────────────────────────────────
GOOD_GATE = '''
def arm():
    sub_once(src, "            persistence.capture { field.write(value) }\\n", "            persistence.capture { field.write(decoded) }\\n")
'''

DRIFTED_GATE = '''
def arm():
    sub_once(src, "            field.valueSchema.validate(value)\\n"
                  "            field.write(value)\\n",
             "x")
'''

EXEMPT_GATE = '''
def arm():
    # anchor-ok: this arm asserts the text is GONE, so absence is the point
    sub_once(src, "            field.valueSchema.validate(value)\\n",
             "x")
'''

SHORT_GATE = '''
def arm():
    sub_once(src, "field.write(value)", "y")
'''

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


def _arm(label, gate_src, expect_rc, marker, failures):
    import shutil
    import tempfile
    root = tempfile.mkdtemp(prefix="anchor-arm-")
    try:
        # the gate lives in SCAN_DIR, so write the fixture gate into a temp copy
        # of the layout and run THIS file with that root's tree
        scan = os.path.join(root, "scripts", "scan")
        os.makedirs(scan)
        app = os.path.join(root, APP_TREE, "main", "java", "com", "rikkaminis", "app", "backup")
        os.makedirs(app)
        with open(os.path.join(app, "ConfigBackup.kt"), "w", encoding="utf-8") as fh:
            fh.write(SOURCE)
        with open(os.path.join(scan, "fixture_gate_under_test.py"), "w", encoding="utf-8") as fh:
            fh.write(gate_src)
        rc, out = _call(root, scan)
        print("selftest %-30s -> rc=%d" % (label, rc))
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
    _arm("live anchor", GOOD_GATE, 0, "PASS fixture_anchor_check", failures)
    _arm("drifted anchor", DRIFTED_GATE, 1, "FAILING OPEN", failures)
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
