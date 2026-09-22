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


def applogger_log_methods(appjava_dir):
    """Public AppLogger log methods, from their `fun X(category: String` shape.

    The private sink `log(level, category, message)` is level-first, so the
    category-first signature is exactly the public per-level API.
    """
    text = read(os.path.join(appjava_dir, APPLOGGER_REL))
    return re.findall(r"\bfun\s+(\w+)\(\s*category\s*:", text)


def corpus_of(appjava_dir):
    """(corpus_text, file_count) over all main-source .kt files."""
    parts = []
    for r, _, ns in os.walk(appjava_dir):
        for n in ns:
            if n.endswith(".kt"):
                parts.append(read(os.path.join(r, n)))
    return "\n".join(parts), len(parts)


def run_checks(appjava_dir):
    """Returns a list of human-readable problems (empty = clean)."""
    problems = []
    corpus, nfiles = corpus_of(appjava_dir)
    cats, _tags, _steps, _events = liveness_audit.declared(appjava_dir)

    # ── A. method coverage: signature-discovered methods must be enumerated ──
    methods = applogger_log_methods(appjava_dir)
    if not methods:
        problems.append(
            "A: no `fun X(category: String` signatures found in AppLogger.kt — "
            "either the file moved (update APPLOGGER_REL) or the public log API "
            "changed shape; the census is running blind either way."
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
    return problems


def self_test():
    """Fixture ground truth: each check must CATCH the violation class it owns."""
    failures = []

    def expect(name, cond, detail=""):
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
            "    fun info(category: String, message: String) {}\n"
            "    fun warning(category: String, message: String) {}\n"
            "    fun error(category: String, message: String) {}\n"
            "    fun debug(category: String, message: String) {}\n"
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
            "    fun info(category: String, message: String) {}\n"
            "    fun fatal(category: String, message: String) {}\n"
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
            "    fun info(category: String, message: String) {}\n"
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
            "    fun info(category: String, message: String) {}\n"
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

    if failures:
        for f in failures:
            print("FAIL  " + f)
        return 1
    print("✅ self-test: clean tree passes; A/B/C violation classes all caught")
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
