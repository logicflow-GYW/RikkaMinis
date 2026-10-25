#!/usr/bin/env python3
r"""Process-logging coverage gate — every isolated process must have an
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
    WIRED (its branch in MinisApp.onCreate really calls AppLogger.init) or
    justified by a `logging-ok:` comment on that declaration.
  - Unknown process + no justification = FAIL (exit 1).

## Second wave (2026-10-25, wide3/10) — three ways the above was already blind

  1. **registry**: `PROCESSES_WITH_LOGGING = {":modelservice"}` was a Python
     constant justified by a comment ("wired in MinisApp.onCreate — d09f3ad")
     and the gate only ever opened AndroidManifest.xml. `if (false)
     AppLogger.init(this)` inside the `:modelservice` branch re-created the
     2026-09-18 incident byte-for-byte and the gate still printed ✅. The wiring
     half is now READ FROM MinisApp.kt: the branch's body must contain a LIVE
     `AppLogger.init(`. A missing MinisApp.kt / predicate / branch is a violation
     (fail closed), never a pass.
  2. **anchor**: the `logging-ok:` escape looked back a fixed 12 LINES, so a new
     `<service>` declared right below a justified one silently borrowed its
     comment (8 lines away). The window is now the COMMENT BLOCK that precedes
     THIS tag — the walk stops at the previous element's tag end, so comments
     cannot cross an element boundary.
  3. **parser-death**: `android:process="…"` was matched with a literal `"`
     only. XML allows `'…'` and whitespace around `=`
     (`android:process = ':unloggedfgs'`), both of which AAPT2 accepts and the
     gate skipped entirely. Both quote styles and `\s*=\s*` are accepted now.

--self-test runs against fixture trees and verifies the gate catches what it
claims to catch. Pure Python, no third-party deps.
"""
import re
import sys
import tempfile
import os

# Processes whose AppLogger.init wiring is CLAIMED: process -> the MinisApp
# predicate whose branch must actually perform the init. The claim is verified
# against MinisApp.kt on every run — the map is a pointer, not evidence.
PROCESSES_WITH_LOGGING = {
    ":modelservice": "isModelServiceProcess",
}
ESCAPE_RE = re.compile(r"logging-ok\s*:")
MANIFEST = "src/android/app/src/main/AndroidManifest.xml"
MINISAPP = "src/android/app/src/main/java/com/rikkaminis/app/MinisApp.kt"

PROCESS_ATTR_RE = re.compile(r"""android:process\s*=\s*(?:"([^"]+)"|'([^']+)')""")
DEAD_COND_RE = re.compile(
    r"(?:\bif\s*\(\s*|\bwhile\s*\(\s*)"
    r"(?:false\b|!\s*true\b|0\s*==\s*1\b|true\s*\|\||false\s*&&)"
    r"|\bfalse\s*&&|\btrue\s*\|\|")


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def blank_comment(text):
    """Comments -> spaces, strings kept, offsets and newlines preserved."""
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
        else:
            if ch == "/" and nxt == "/":
                mode = "line"
                out.append("  ")
                i += 1
            elif ch == "/" and nxt == "*":
                mode = "block"
                out.append("  ")
                i += 1
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


def escape_window(text, tag_start):
    """The `<!-- … -->` block(s) that precede THIS element, and nothing else.

    Walking back through consecutive comments and stopping at any other
    character is what keeps a new `<service>` from borrowing its predecessor's
    `logging-ok:` note: the walk stops on the previous element's `>` and the
    window comes back empty.
    """
    chunks = []
    i = tag_start
    while True:
        j = i - 1
        while j >= 0 and text[j] in " \t\r\n":
            j -= 1
        if j >= 2 and text[j - 2:j + 1] == "-->":
            k = text.rfind("<!--", 0, j - 2)
            if k < 0:
                break
            chunks.append(text[k:j + 1])
            i = k
            continue
        break
    return "\n".join(reversed(chunks))


def scan_manifest(text):
    """[(process_value, line_no, escape_window_tail)] for every declaration.

    `escape_window_tail` = the comments that precede the enclosing start tag;
    an escape may also be written inside the tag text itself (kept for the
    fixture shape the older gate accepted).
    """
    out = []
    for m in PROCESS_ATTR_RE.finditer(text):
        value = m.group(1) if m.group(1) is not None else m.group(2)
        tag_start = text.rfind("<", 0, m.start())
        if tag_start < 0:
            continue
        tag_end = text.find(">", m.end())
        if tag_end < 0:
            tag_end = len(text)
        window = escape_window(text, tag_start) + "\n" + text[tag_start:tag_end]
        out.append((value, text.count("\n", 0, m.start()) + 1, window))
    return out


def wiring_problems(root, process, predicate):
    """Verify the claim in PROCESSES_WITH_LOGGING against MinisApp.kt."""
    text = read(os.path.join(root, MINISAPP))
    if text is None:
        return ["%s: claimed wired via %s(), but %s is missing — the wiring "
                "cannot be verified (fail closed)" % (process, predicate, MINISAPP)]
    code = blank_comment(text)
    m = re.search(r"if\s*\(\s*!?\s*" + re.escape(predicate) + r"\s*\(\s*\)\s*\)\s*\{", code)
    if not m:
        return ["%s: claimed wired via `if (%s()) { … }` in MinisApp.kt, but that "
                "branch was not found — anchor drift, the process would run with "
                "no logging decision" % (process, predicate)]
    brace = code.find("{", m.end() - 1)
    end = _brace_end(code, brace)
    if end < 0:
        return ["%s: `%s()` branch is unterminated — cannot judge AppLogger.init"
                % (process, predicate)]
    body, base = code[brace + 1:end], brace + 1
    calls = list(re.finditer(r"\bAppLogger\s*\.\s*init\s*\(", body))
    live = [c for c in calls if not dead_guarded(code, base + c.start())]
    if not live:
        why = ("its only AppLogger.init sits in dead code" if calls
               else "it never calls AppLogger.init")
        return ["%s: the `%s()` branch claims the log wiring but %s — this is the "
                "2026-09-18 worker-log incident (every line from this process is "
                "silently dropped)" % (process, predicate, why)]
    return []


def check(root):
    manifest = os.path.join(root, MANIFEST)
    text = read(manifest)
    if text is None:
        print("❌ No %s in this tree — the gate cannot see ANY process "
              "declaration; failing CLOSED" % MANIFEST)
        return 1
    declared = scan_manifest(text)
    if not declared:
        print("✅ No isolated process declarations — nothing to gate")
        return 0
    problems, labels = [], []
    for value, line_no, window in declared:
        if value in PROCESSES_WITH_LOGGING:
            found = wiring_problems(root, value, PROCESSES_WITH_LOGGING[value])
            if found:
                problems.extend(found)
                labels.append(value)
            continue
        if ESCAPE_RE.search(window):
            continue
        problems.append(
            "%s (line %d): no AppLogger.init and no `logging-ok:` justification "
            "attached to THIS declaration" % (value, line_no))
        labels.append(value)
    if problems:
        print(f"❌ Isolated process(es) without an AppLogger.init decision: "
              f"{sorted(set(labels))}")
        for p in problems:
            print("   " + p)
        print("   Either wire AppLogger.init in that process's Application.onCreate")
        print("   branch, or justify the silence with a `logging-ok: <reason>` comment")
        print("   directly above the element in AndroidManifest.xml.")
        return 1
    print(f"✅ All isolated processes have an AppLogger.init decision "
          f"({sorted(set(v for v, _l, _w in declared))})")
    return 0


def self_test():
    """Fixture-based: each re-introduction shape has its own arm."""
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        # Case 1: clean tree — the claimed wiring is real.
        d1 = _fixture(tmp, "a1", '  <service android:process=":modelservice" />')
        ok &= _case("clean: wired process passes", check(d1) == 0)

        # Case 2: new process without init and without justification = caught.
        d2 = _fixture(tmp, "a2", '  <service android:process=":newservice" />')
        ok &= _case("dirty: unwired process caught", check(d2) == 1)

        # Case 3: escape hatch — the comment block ABOVE the element.
        d3 = _fixture(tmp, "a3",
                      '  <!-- logging-ok: dormant, no requests -->\n'
                      '  <service android:process=":newservice" />')
        ok &= _case("escape: logging-ok above the tag passes", check(d3) == 0)

        # Case 4 (registry): the claimed wiring is dead on arrival.
        d4 = _fixture(tmp, "a4", '  <service android:process=":modelservice" />',
                      app_body="            if (false) AppLogger.init(this)\n")
        ok &= _case("registry: `if (false) AppLogger.init(this)` is caught",
                    check(d4) == 1)

        # Case 5 (anchor): a second element must not inherit the first one's
        # escape comment, however close it sits.
        d5 = _fixture(tmp, "a5",
                      '  <!-- logging-ok: dormant, no requests -->\n'
                      '  <service android:process=":toolservice" />\n'
                      '  <service android:process=":unloggedextra" />')
        ok &= _case("anchor: a new element cannot borrow the previous escape",
                    check(d5) == 1)

        # Case 6/7 (parser-death): valid XML the old single-quote-blind regex
        # never saw.
        d6 = _fixture(tmp, "a6", "  <service android:process=':unloggedfgs' />")
        ok &= _case("parser-death: single-quoted attribute is caught", check(d6) == 1)
        d7 = _fixture(tmp, "a7",
                      '  <service android:process = ":unloggedfgs" />')
        ok &= _case("parser-death: whitespace around `=` is caught", check(d7) == 1)

        # Case 8 (fail-closed): no manifest at all is a violation, not a pass.
        d8 = os.path.join(tmp, "a8")
        os.makedirs(os.path.join(d8, "src/android/app/src/main"))
        ok &= _case("fail-closed: a tree without a manifest is red", check(d8) == 1)
    return 0 if ok else 1


APP_DEFAULT = (
    "package com.rikkaminis.app\n\n"
    "class MinisApp {\n"
    "    fun onCreate() {\n"
    "        if (isModelServiceProcess()) {\n"
    "%s"
    "            return\n"
    "        }\n"
    "    }\n"
    "    private fun isModelServiceProcess(): Boolean = false\n"
    "    private fun isToolServiceProcess(): Boolean = false\n"
    "}\n"
)


def _fixture(tmp, name, process_xml, app_body=None):
    d = os.path.join(tmp, name)
    mdir = os.path.join(d, "src/android/app/src/main")
    os.makedirs(mdir)
    with open(os.path.join(mdir, "AndroidManifest.xml"), "w") as f:
        f.write("<manifest>\n%s\n</manifest>\n" % process_xml)
    app = os.path.join(d, "src/android/app/src/main/java/com/rikkaminis/app")
    os.makedirs(app)
    with open(os.path.join(app, "MinisApp.kt"), "w") as f:
        f.write(APP_DEFAULT % (app_body if app_body is not None
                               else "            AppLogger.init(this)\n"))
    return d


def _case(name, ok):
    print(f"  {'✅' if ok else '❌'} {name}")
    return ok


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    sys.exit(check(root))
