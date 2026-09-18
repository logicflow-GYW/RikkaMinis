#!/usr/bin/env python3
"""Process-logging coverage gate — every isolated process must have an
explicit AppLogger.init decision.

[T-logging-full-coverage] The 2026-09-18 worker-log incident: `:modelservice`
never ran AppLogger.init, so the process that actually sends LLM requests
logged zero lines to the daily file — 9 provider 400s with no trace. The fix
(d09f3ad) wired init in MinisApp's `:modelservice` branch. This gate prevents
the THIRD recurrence pattern: a new `android:process` declaration in the
Manifest without a corresponding explicit decision (wire AppLogger.init, or
justify the silence inline with `logging-ok: <reason>` in the Manifest).

Mechanical rule:
  - Every `android:process="..."` value in AndroidManifest.xml must be either
    in PROCESSES_WITH_LOGGING (wired in MinisApp.onCreate) or justified by a
    `logging-ok:` comment on the same declaration line.
  - Unknown process + no justification = FAIL (exit 1).

--self-test runs against fixture trees and verifies the gate catches what it
claims to catch. Pure Python, no third-party deps.
"""
import re
import sys
import tempfile
import os

PROCESSES_WITH_LOGGING = {
    # Wired in MinisApp.onCreate (d09f3ad): AppLogger.init + own LogcatTailer.
    ":modelservice",
}
ESCAPE_RE = re.compile(r"logging-ok\s*:")
# XML forbids comments inside a start tag, so the justification lives in the
# comment block ABOVE the declaration. Look back this many lines for it.
ESCAPE_LOOKBACK_LINES = 12


def scan_manifest(root):
    """Returns (process_values, lines) from the main AndroidManifest.xml."""
    manifest = os.path.join(root, "src/android/app/src/main/AndroidManifest.xml")
    if not os.path.exists(manifest):
        return [], []
    values = []
    lines = []
    with open(manifest, encoding="utf-8", errors="replace") as f:
        for line in f:
            lines.append(line)
            m = re.search(r'android:process="([^"]+)"', line)
            if m:
                values.append((m.group(1), line))
    return values, lines


def check(root):
    declared, lines = scan_manifest(root)
    if not declared:
        print("✅ No isolated process declarations — nothing to gate")
        return 0
    failures = []
    for value, line in declared:
        if value in PROCESSES_WITH_LOGGING:
            continue
        # Locate this declaration's line, then look back for the escape
        # comment (XML forbids comments inside a tag, so it sits above).
        try:
            idx = lines.index(line)
        except ValueError:
            idx = -1
        window = lines[max(0, idx - ESCAPE_LOOKBACK_LINES):idx + 1] if idx >= 0 else [line]
        if any(ESCAPE_RE.search(l) for l in window):
            continue
        failures.append(value)
    if failures:
        print(f"❌ Isolated process(es) without an AppLogger.init decision: {failures}")
        print("   Either wire AppLogger.init in that process's Application.onCreate")
        print("   branch, or justify the silence with a `logging-ok: <reason>` comment")
        print("   on the android:process line in AndroidManifest.xml.")
        return 1
    print(f"✅ All isolated processes have an AppLogger.init decision "
          f"({sorted(set(v for v, _ in declared))})")
    return 0


def self_test():
    """Fixture-based: gate must catch an unlogged process, pass wired ones."""
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        # Case 1: clean tree — wired process passes.
        d1 = _fixture(tmp, "a1", 'android:process=":modelservice"')
        ok &= _case("clean: wired process passes", check(d1) == 0)
        # Case 2: new process without init and without justification = caught.
        d2 = _fixture(tmp, "a2", 'android:process=":newservice"')
        ok &= _case("dirty: unwired process caught", check(d2) == 1)
        # Case 3: escape hatch with logging-ok comment passes.
        d3 = _fixture(tmp, "a3",
                      'android:process=":newservice" <!-- logging-ok: dormant, no requests -->')
        ok &= _case("escape: logging-ok comment passes", check(d3) == 0)
    return 0 if ok else 1


def _fixture(tmp, name, process_line):
    d = os.path.join(tmp, name, "src/android/app/src/main")
    os.makedirs(d)
    with open(os.path.join(d, "AndroidManifest.xml"), "w") as f:
        f.write('<manifest>\n  <service %s/>\n</manifest>\n' % process_line)
    return os.path.join(tmp, name)


def _case(name, ok):
    print(f"  {'✅' if ok else '❌'} {name}")
    return ok


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    sys.exit(check(root))
