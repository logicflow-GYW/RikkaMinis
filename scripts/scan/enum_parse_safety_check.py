#!/usr/bin/env python3
"""
Enum parse safety check — find bare .valueOf() calls that are NOT wrapped in try/catch.

Persisted enum values (from DB/snapshot/JSON) must NEVER use bare valueOf(),
because adding a new enum value to the Kotlin source will cause any stale
persisted data to crash with IllegalArgumentException on the next read.

Why the criteria are structural rather than textual (wide3 witness wave, 3/3
gates falsified):

  * a keyword allow-list (`runCatching|getOrNull|decoded|…` anywhere on the
    line) exempted a bare call the moment a local was NAMED `decoded` --
    `val decoded = ThinkingLevel.valueOf(it)` sailed through;
  * the same-line shape test only compared the text left of `.valueOf(` with
    `try {` and the text right of it with `} catch` -- `try { Unit } finally {}
    ; val v = X.valueOf(it); try { Unit } catch (_: Exception) {}` has both,
    with the call naked in the middle;
  * the multi-line test counted raw braces in the lines above, so a line that
    OPENS AND CLOSES a try and then opens a `catch` block (`try { } catch (e:
    Exception) {`) looked like an open try, and a call inside the catch body
    counted as protected.

A `.valueOf(` is accepted only when the block that encloses it (brace-matched,
string/comment masked) is

  * a `try { … }` whose statement really has a `catch` clause, or
  * a `runCatching { … }` whose Result is consumed by a handling receiver
    (`getOrNull` / `getOrElse` / `getOrDefault` / `fold` / `onFailure` /
    `exceptionOrNull` / `recover` / `recoverCatching` / `isSuccess` /
    `isFailure`).

Everything else is a violation. Fail-closed: a tree with no .kt file under the
main source set, or a file whose braces do not balance, is a violation too —
the scanner must never report "clean" because it could not parse.

Usage: python3 scripts/scan/enum_parse_safety_check.py <repo_root>
       python3 scripts/scan/enum_parse_safety_check.py --selftest
Exit code: 0 = clean, 1 = issues found
"""
import os
import re
import sys
import tempfile

MARKER = "ENUM_PARSE_SELFTEST=PASS"
VALUEOF = ".valueOf("

# Result receivers that actually HANDLE a failure (or make it observable).
# `getOrThrow` is deliberately absent: it rethrows, i.e. the caller inherits the
# unhandled IllegalArgumentException this gate exists to prevent.
SAFE_RESULT_RECEIVERS = (
    "getOrNull", "getOrElse", "getOrDefault", "fold", "onFailure",
    "exceptionOrNull", "recover", "recoverCatching", "isSuccess", "isFailure",
)


def mask_code(text):
    """Blank out //-comments, /* */ blocks, string and char literals.

    Length- and newline-preserving, so offsets still map to source positions.
    Braces inside literals (a brace inside a double-quoted string, a char
    literal, a raw triple-quoted string) must not reach the brace matcher;
    template expressions are balanced by construction.
    """
    out = []
    i, n = 0, len(text)
    state = None
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if state is None:
            if two == "//":
                state = "line"; out.append("  "); i += 2; continue
            if two == "/*":
                state = "block"; out.append("  "); i += 2; continue
            if text[i:i + 3] == '"""':
                state = "raw"; out.append("   "); i += 3; continue
            if c == '"':
                state = "str"; out.append(" "); i += 1; continue
            if c == "'":
                state = "char"; out.append(" "); i += 1; continue
            out.append(c); i += 1; continue
        if state == "line":
            out.append("\n" if c == "\n" else " ")
            if c == "\n":
                state = None
            i += 1; continue
        if state == "block":
            if two == "*/":
                state = None; out.append("  "); i += 2; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
        if state == "raw":
            if text[i:i + 3] == '"""':
                state = None; out.append("   "); i += 3; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
        if state == "str":
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == '"':
                state = None
            out.append("\n" if c == "\n" else " "); i += 1; continue
        if state == "char":
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == "'":
                state = None
            out.append(" "); i += 1; continue
    return "".join(out)


def block_kind(head):
    """Classify the block a `{` opens from the source text right before it."""
    tail = head[-160:]
    if re.search(r"\brunCatching\s*$", tail):
        return "runCatching"
    if re.search(r"\btry\s*$", tail):
        return "try"
    if re.search(r"\bcatch\b\s*(?:\([^()]*\))?\s*$", tail):
        return "catch"
    return "other"


def parse_blocks(clean):
    """[(kind, body_start, body_end)] for every balanced brace block.

    Returns (blocks, unbalanced) — `unbalanced` is True when the file's braces
    do not balance, which must fail closed rather than silently shrink the
    scan surface.
    """
    stack = []
    blocks = []
    for i, c in enumerate(clean):
        if c == "{":
            stack.append((block_kind(clean[max(0, i - 160):i]), i))
        elif c == "}":
            if not stack:
                return blocks, True
            kind, start = stack.pop()
            blocks.append((kind, start + 1, i))
    return blocks, bool(stack)


def _skip_ws(text, i):
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    return i


def _try_has_catch(clean, end):
    rest = clean[_skip_ws(clean, end + 1):]
    return bool(re.match(r"catch\b", rest))


def _result_consumed(clean, end):
    rest = clean[_skip_ws(clean, end + 1):]
    m = re.match(r"\.\s*(\w+)", rest)
    return bool(m) and m.group(1) in SAFE_RESULT_RECEIVERS


def guarded_spans(clean, blocks):
    """[(start, end)] of blocks that really protect an exception."""
    spans = []
    for kind, start, end in blocks:
        if kind == "try" and _try_has_catch(clean, end):
            spans.append((start, end))
        elif kind == "runCatching" and _result_consumed(clean, end):
            spans.append((start, end))
    return spans


def find_violations(path, root):
    text = open(path, encoding="utf-8", errors="replace").read()
    clean = mask_code(text)
    blocks, unbalanced = parse_blocks(clean)
    rel = os.path.relpath(path, root)
    if unbalanced:
        return ["  %s  <unbalanced braces — the scanner cannot see this file "
                "(fail-closed)>" % rel]
    spans = guarded_spans(clean, blocks)
    problems = []
    for m in re.finditer(re.escape(VALUEOF), clean):
        at = m.start()
        if any(s <= at <= e for s, e in spans):
            continue
        line_no = clean.count("\n", 0, at) + 1
        src_line = text.splitlines()[line_no - 1].strip() if text.splitlines() else ""
        problems.append("  %s:%d  %s" % (rel, line_no, src_line))
    return problems


def scan(root):
    src_dir = os.path.join(root, "src/android/app/src/main")
    problems = []
    scanned = 0
    for dirpath, _, filenames in os.walk(src_dir):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            scanned += 1
            problems.extend(find_violations(os.path.join(dirpath, fn), root))
    return problems, scanned


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems, scanned = scan(root)
    if scanned == 0:
        print("❌ no .kt file found under %s/src/android/app/src/main — the scan "
              "surface collapsed (fail-closed)" % root)
        return 1
    if problems:
        print("❌ Bare valueOf calls (not wrapped in try/catch):")
        for p in problems:
            print(p)
        return 1
    print("✅ All valueOf calls are properly guarded")
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# selftest — one miniature tree per criterion, each broken in exactly one way.
# ─────────────────────────────────────────────────────────────────────────────

GUARDED_RUNCATCHING = (
    "package p\n"
    "fun a(s: String) {\n"
    "    val x = runCatching { Foo.valueOf(s) }.getOrNull()\n"
    "}\n"
)

GUARDED_RUNCATCHING_MULTILINE = (
    "package p\n"
    "fun a(s: String) {\n"
    "    val x = runCatching { Foo.valueOf(s) }\n"
    "        .getOrElse { Foo.A }\n"
    "}\n"
)

GUARDED_TRY_MULTILINE = (
    "package p\n"
    "fun b(s: String): Foo {\n"
    "    return try {\n"
    "        Foo.valueOf(s)\n"
    "    } catch (_: IllegalArgumentException) {\n"
    "        Foo.A\n"
    "    }\n"
    "}\n"
)

# witness C1_keyword_exempt (spelling): the identifier allow-list.
BARE_VIA_DECODED_NAME = (
    "package p\n"
    "fun c(s: String): Foo? =\n"
    "    s.takeIf { it.isNotEmpty() }\n"
    "        ?.let { val decoded = Foo.valueOf(it); decoded }\n"
)

# witness C2_is_safe_line (anchor): closed try on the left, unrelated try/catch
# on the right, call naked in between.
BARE_BETWEEN_FAKE_TRY = (
    "package p\n"
    "fun d(s: String): Foo? =\n"
    "    run { try { Unit } finally {}; val v = Foo.valueOf(s); "
    "try { Unit } catch (_: Exception) {}; v }\n"
)

# witness C3_is_safe_across_lines (parser-death): the `try {}` closes on the same
# line and the OPEN brace belongs to the catch block.
BARE_INSIDE_CATCH = (
    "package p\n"
    "fun e(s: String): Foo {\n"
    "    try { } catch (_: Exception) {\n"
    "        return Foo.valueOf(s)\n"
    "    }\n"
    "    return Foo.A\n"
"}\n"
)

# runCatching whose Result is dropped: the failure is swallowed, not handled.
BARE_UNCONSUMED_RUNCATCHING = (
    "package p\n"
    "fun f(s: String) {\n"
    "    runCatching { Foo.valueOf(s) }\n"
    "}\n"
)


def _fixture(files):
    root = tempfile.mkdtemp(prefix="enum-selftest-")
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
    return root


def _selftest():
    import shutil

    src = "src/android/app/src/main/java/p/Enums.kt"
    cases = [
        ("runCatching + getOrNull (control, green)", GUARDED_RUNCATCHING, 0),
        ("runCatching + chained getOrElse on the next line (green)",
         GUARDED_RUNCATCHING_MULTILINE, 0),
        ("multi-line try/catch (green)", GUARDED_TRY_MULTILINE, 0),
        ("local named `decoded` no longer buys an exemption (spelling)",
         BARE_VIA_DECODED_NAME, 1),
        ("closed try + unrelated try/catch on the same line (anchor)",
         BARE_BETWEEN_FAKE_TRY, 1),
        ("call inside the catch block of a `try { } catch (…) {` line (parser-death)",
         BARE_INSIDE_CATCH, 1),
        ("runCatching whose Result is dropped (dataflow)",
         BARE_UNCONSUMED_RUNCATCHING, 1),
    ]
    passed = failed = 0
    for name, content, want in cases:
        root = _fixture({src: content})
        try:
            problems, scanned = scan(root)
            rc = 1 if (problems or scanned == 0) else 0
        finally:
            shutil.rmtree(root, ignore_errors=True)
        ok = rc == want
        print("  %s %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            failed += 1
            print("    expected rc=%s, got rc=%s; problems=%s" % (want, rc, problems))
        else:
            passed += 1
    print("\n%s (%d fixtures, %d mismatched)"
          % (MARKER if not failed else "ENUM_PARSE_SELFTEST=FAIL", passed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    if any(a == "--selftest" for a in sys.argv[1:]):
        sys.exit(_selftest())
    sys.exit(main())
