#!/usr/bin/env python3
"""
Enum parse safety check — find enum parses that are not actually protected.

Persisted enum values (from DB/snapshot/JSON) must NEVER be parsed in a way
that lets an unknown name crash the caller, because adding a new enum value to
the Kotlin source will make any stale persisted data throw
IllegalArgumentException on the next read.

History of the criteria (each wave was beaten by a witness that the previous
criterion could not see):

  * wide3 (3/3 gates falsified) — a keyword allow-list (`runCatching|getOrNull|
    decoded|…` anywhere on the line) exempted a bare call the moment a local was
    NAMED `decoded`; a same-line shape test matched a closed `try { }` on the
    left and an unrelated `try {} catch` on the right; the multi-line test
    counted raw braces, so `try { } catch (e: Exception) {` looked like an open
    try and a call inside the catch body counted as protected.
  * batch5 (5/6 witnesses blind) — the criteria still anchored on SPELLING and
    on the FIRST hop:
      03-1 `try { X.valueOf(raw) } catch (e: Exception) { throw e }` — a catch
           clause that only rethrows is syntactically a handler but forwards
           the very exception the gate exists to stop.
      03-2 `runCatching { X.valueOf(raw) }.onFailure { }.getOrThrow()` — only
           the hop right after the block was inspected, so an observing hop in
           front of `.getOrThrow()` hid the rethrow.
      03-3 `enumValueOf<X>(raw)` — the stdlib spelling of `X.valueOf(raw)`
           (identical bytecode, identical IAE) carries no `.valueOf(` token.
      03-4 `"${X.valueOf(raw)}".let { try { … } catch { … } }` — string-template
           interpolation is EXECUTABLE code; a masker that blanks the whole
           literal makes the parse invisible.
      03-5 `catch (_: kotlinx.coroutines.CancellationException)` — the clause
           type was never checked, so a type that cannot catch IAE counted.

Criteria (all structural, all on the shared lexer/AST primitives):

  A parse site is protected only when one of its ENCLOSING blocks is

    * a `try { … }` with at least one effective `catch` clause — a clause whose
      type can catch IllegalArgumentException (`IllegalArgumentException` /
      `RuntimeException` / `Exception` / `Throwable`) and whose body is not a
      bare rethrow of the caught exception (`throw e`), or
    * a `runCatching { … }` whose Result chain contains a handling receiver
      (`getOrNull` / `getOrElse` / `getOrDefault` / `fold` / `onFailure` /
      `exceptionOrNull` / `recover` / `recoverCatching` / `isSuccess` /
      `isFailure`) and never contains `.getOrThrow()` / `.throwOnFailure()`
      anywhere in the chain (`getOrThrow` rethrows, i.e. the caller inherits
      the unhandled IllegalArgumentException this gate exists to prevent).

Everything else is a violation. Fail-closed: a tree with no .kt file under the
main source set, a file the shared lexer cannot parse, a file whose braces do
not balance, an unterminated `${…}` template, or a catch clause that cannot be
classified (`catch` followed by something that is not `(…) {…}`) is a violation
too — the scanner must never report "clean" because it could not parse.

Usage: python3 scripts/scan/enum_parse_safety_check.py <repo_root>
       python3 scripts/scan/enum_parse_safety_check.py --selftest
Exit code: 0 = clean, 1 = issues found
"""
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, analyse  # noqa: E402
from _kotlin_ast import match_bracket, stmt_spans, strip_parens  # noqa: E402
import _kotlin_lexer as _lexer  # noqa: E402

MARKER = "ENUM_PARSE_SELFTEST=PASS"

# ── parse sites ──────────────────────────────────────────────────────────────
# `X.valueOf(raw)` and the stdlib spelling `enumValueOf<X>(raw)` (witness 03-3:
# same bytecode, same IllegalArgumentException, no `.valueOf(` token at all).
PARSE_CALL_RES = (
    re.compile(r"\.\s*valueOf\s*\("),
    re.compile(r"(?<![\w.])enumValueOf\s*<[^<>{}()]*>\s*\("),
)

# Result receivers that actually HANDLE a failure (or make it observable).
CONSUMERS = frozenset({
    "getOrNull", "getOrElse", "getOrDefault", "fold", "onFailure",
    "exceptionOrNull", "recover", "recoverCatching", "isSuccess", "isFailure",
})

# Receivers that re-throw the failure: anywhere in the chain = not protected.
RETHROWING = frozenset({"getOrThrow", "throwOnFailure"})

# A catch clause can only protect the parse if its type is a supertype of
# IllegalArgumentException (03-5: catching CancellationException sees nothing).
IAE_SUPERTYPES = frozenset({
    "IllegalArgumentException", "RuntimeException", "Exception", "Throwable",
})


# ─────────────────────────────────────────────────────────────────────────────
# structural view
# ─────────────────────────────────────────────────────────────────────────────

def _skip_template(src, brace_pos):
    """Delegate to the shared lexer's own template skipper (no local lexer)."""
    fn = getattr(_lexer, "_skip_template", None)
    if fn is None:                       # pragma: no cover - shared module drifted
        return -1
    return fn(src, brace_pos)


def code_view(src):
    """Code view, but with the code inside `${…}` templates left VISIBLE.

    Witness 03-4: `"${X.valueOf(raw)}"` evaluates the interpolation. The plain
    code view masks the whole literal (correct for brace matching, blind for
    this gate), so every `${…}` body is pasted back from a fresh `analyse()`
    of that body — nested strings/templates inside it stay masked, and every
    splice is length-preserving so positions still map onto the source.
    """
    ok, reason, views = analyse(src)
    if not ok:
        raise KotlinLexError(reason or "unparseable source")
    code = views["code"]
    out = list(code)
    lit = views["comments"]              # comments stripped, literals intact
    i = 0
    while True:
        j = lit.find("${", i)
        if j < 0:
            break
        i = j + 2
        if j >= len(code) or code[j] != " ":
            continue                     # `$` is real code, not a template
        end = _skip_template(src, j + 1)
        if end < 0 or end > len(src) or end <= j + 2:
            raise KotlinLexError("unterminated string template at offset %d" % j)
        inner_start, inner_end = j + 2, end - 1
        ok2, reason2, sub = analyse(src[inner_start:inner_end])
        if not ok2:
            raise KotlinLexError(reason2 or "unparseable template body")
        for k, ch in enumerate(sub["code"]):
            if ch != " ":
                out[inner_start + k] = ch
    return "".join(out)


def braces_balanced(code):
    depth = 0
    for ch in code:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth < 0:
                return False
    return depth == 0


_HEAD_TAIL = 200
HEAD_TRY = re.compile(r"\btry\s*$")
HEAD_RUNCATCHING = re.compile(r"\brunCatching\s*$")
HEAD_CATCH = re.compile(r"\bcatch\b\s*(?:\([^()]*\))?\s*$")


def block_kind(code, open_pos):
    """Classify the block a `{` opens from the source text right before it."""
    tail = code[max(0, open_pos - _HEAD_TAIL):open_pos]
    if HEAD_RUNCATCHING.search(tail):
        return "runCatching"
    if HEAD_TRY.search(tail):
        return "try"
    if HEAD_CATCH.search(tail):
        return "catch"
    return "other"


def enclosing_blocks(code, pos):
    """Every `{` that encloses pos: [(open, close)], innermost first.

    Returns None when a brace cannot be matched — the caller fails closed
    instead of scanning a truncated body.
    """
    depth = 0
    opens = []
    i = pos - 1
    while i >= 0:
        ch = code[i]
        if ch == "}":
            depth += 1
        elif ch == "{":
            if depth == 0:
                opens.append(i)
            else:
                depth -= 1
        i -= 1
    out = []
    for o in opens:
        close = match_bracket(code, o)
        if close < 0:
            return None
        out.append((o, close))
    return out


# ─────────────────────────────────────────────────────────────────────────────
# try / catch
# ─────────────────────────────────────────────────────────────────────────────

def _skip_ws(code, i):
    while i < len(code) and code[i] in " \t\r\n":
        i += 1
    return i


def _clause_after(code, i, kw):
    """`kw (…) {…}` / `kw {…}` → (param, body_start, body_end, next_i) or None."""
    j = _skip_ws(code, i)
    if not code.startswith(kw, j):
        return None
    j += len(kw)
    j = _skip_ws(code, j)
    param = None
    if j < len(code) and code[j] == "(":
        close = match_bracket(code, j)
        if close < 0:
            return None
        param = code[j + 1:close]
        j = _skip_ws(code, close + 1)
    if j >= len(code) or code[j] != "{":
        return None
    body_end = match_bracket(code, j)
    if body_end < 0:
        return None
    return param, j + 1, body_end, body_end + 1


def catch_type(param):
    """Simple name of the caught type (`java.lang.Exception` → `Exception`)."""
    if not param or ":" not in param:
        return None
    t = param.split(":", 1)[1].replace("`", "").strip()
    t = t.split("<")[0].strip()
    return t.split(".")[-1].strip() or None


def _rethrows_same(body, var):
    """Does the clause end by throwing the caught exception back out?

    03-1: `catch (e: Exception) { throw e }` adds no fallback — the caller
    sees exactly the IllegalArgumentException a bare `valueOf` would raise.
    """
    spans = stmt_spans(body)
    if not spans:
        return False
    tail = body[spans[-1][0]:spans[-1][1]].strip()
    if not tail.startswith("throw"):
        return False
    arg = strip_parens(tail[len("throw"):].strip())
    return bool(var) and arg == var


def try_protects(code, open_pos, close_pos):
    """Is there a `catch` clause that actually catches the parse failure?"""
    i = close_pos + 1
    while True:
        clause = _clause_after(code, i, "catch")
        if clause is not None:
            param, b_start, b_end, i = clause
            var = (param.split(":", 1)[0] if param else "").replace("`", "").strip()
            if catch_type(param) in IAE_SUPERTYPES and \
                    not _rethrows_same(code[b_start:b_end], var):
                return True
            continue
        clause = _clause_after(code, i, "finally")
        if clause is not None:
            _p, _s, _e, i = clause
            continue
        return False


# ─────────────────────────────────────────────────────────────────────────────
# runCatching Result chain
# ─────────────────────────────────────────────────────────────────────────────

_HOP = re.compile(r"\s*(?:\?\.|\.)\s*([A-Za-z_]\w*)")


def _skip_generic(code, i):
    """i points at `<`; return the index after its matching `>`, else -1."""
    depth = 0
    j = i
    while j < len(code):
        ch = code[j]
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
            if depth == 0:
                return j + 1
        elif ch in ";{}()":
            return -1
        j += 1
    return -1


def result_chain(code, close_pos):
    """The receiver chain right after a `runCatching { … }` block.

    Returns (hops, parsed). `parsed` False means the chain could not be read
    (unbalanced argument list) and the caller must fail closed.
    """
    hops = []
    i = close_pos + 1
    n = len(code)
    while True:
        m = _HOP.match(code, i)
        if not m:
            return hops, True
        hops.append(m.group(1))
        i = m.end()
        while True:
            j = _skip_ws(code, i)
            if j < n and code[j] in "({":
                k = match_bracket(code, j)
                if k < 0:
                    return hops, False
                i = k + 1
                continue
            if j < n and code[j] == "<":
                k = _skip_generic(code, j)
                if k < 0:
                    break
                i = k
                continue
            break
        if len(hops) > 32:               # defensive: do not walk a whole file
            return hops, False


def result_consumed(code, close_pos):
    """Do the hops HANDLE the failure, or do they rethrow it (03-2)?"""
    hops, parsed = result_chain(code, close_pos)
    if not parsed:
        return False
    if any(h in RETHROWING for h in hops):
        return False
    return any(h in CONSUMERS for h in hops)


def protects(code, open_pos, close_pos):
    kind = block_kind(code, open_pos)
    if kind == "try":
        return try_protects(code, open_pos, close_pos)
    if kind == "runCatching":
        return result_consumed(code, close_pos)
    return False


# ─────────────────────────────────────────────────────────────────────────────
# scan
# ─────────────────────────────────────────────────────────────────────────────

def find_violations(path, root):
    rel = os.path.relpath(path, root)
    with open(path, encoding="utf-8", errors="replace") as fh:
        src = fh.read()
    try:
        code = code_view(src)
    except KotlinLexError as exc:
        return ["  %s  <unparseable: %s — fail-closed>" % (rel, exc)]
    if not braces_balanced(code):
        return ["  %s  <unbalanced braces — the scanner cannot see this file "
                "(fail-closed)>" % rel]

    lines = src.splitlines()
    problems = []
    seen = set()
    for rx in PARSE_CALL_RES:
        for m in rx.finditer(code):
            at = m.start()
            if at in seen:
                continue
            seen.add(at)
            blocks = enclosing_blocks(code, at)
            if blocks is None:
                problems.append("  %s  <cannot match the braces around offset "
                                "%d — fail-closed>" % (rel, at))
                continue
            if any(protects(code, o, e) for o, e in blocks):
                continue
            line_no = code.count("\n", 0, at) + 1
            src_line = lines[line_no - 1].strip() if line_no <= len(lines) else ""
            problems.append("  %s:%d  %s" % (rel, line_no, src_line))
    return problems


def scan(root):
    src_dir = os.path.join(root, "src/android/app/src/main")
    problems = []
    scanned = 0
    for dirpath, _, filenames in os.walk(src_dir):
        for fn in sorted(filenames):
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
        print("❌ Enum parses that are not protected (bare/ineffective guard):")
        for p in problems:
            print(p)
        return 1
    print("✅ All enum parses are properly guarded")
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

# ── batch5 witnesses ──

# 03-1 (dataflow): the catch clause only rethrows what it caught.
BARE_CATCH_RETHROWS = (
    "package p\n"
    "fun g(s: String): Foo? {\n"
    "    val v = try { Foo.valueOf(s) } catch (e: Exception) { throw e }\n"
    "    return v\n"
    "}\n"
)

# 03-2 (dataflow): an observing hop in front of `.getOrThrow()`.
BARE_RUNCATCHING_ONFAILURE_GETORTHROW = (
    "package p\n"
    "fun h(s: String): Foo {\n"
    "    return runCatching { Foo.valueOf(s) }.onFailure { }.getOrThrow()\n"
    "}\n"
)

# 03-3 (spelling): the stdlib spelling of `Foo.valueOf(s)`.
BARE_ENUM_VALUE_OF = (
    "package p\n"
    "fun i(s: String): Foo {\n"
    "    return enumValueOf<Foo>(s)\n"
    "}\n"
)

# 03-4 (parser-death): the parse is executable code inside a string template.
BARE_INSIDE_STRING_TEMPLATE = (
    "package p\n"
    "fun j(s: String): String {\n"
    '    return "${Foo.valueOf(s)}".let { try { it } catch (_: Exception) { "" } }\n'
    "}\n"
)

# 03-5 (family-hole): a catch type that cannot catch IllegalArgumentException.
BARE_CATCH_WRONG_TYPE = (
    "package p\n"
    "fun k(s: String): Foo {\n"
    "    return try { Foo.valueOf(s) } catch (_: kotlinx.coroutines.CancellationException) { Foo.A }\n"
    "}\n"
)

# control (was already caught, must stay caught): immediate rethrow receiver.
BARE_RUNCATCHING_GETORTHROW = (
    "package p\n"
    "fun l(s: String): Foo {\n"
    "    return runCatching { Foo.valueOf(s) }.getOrThrow()\n"
    "}\n"
)

# ── green arms that a sloppy tightening would break ──

# The repo's documented strict-translation contract: catch swaps the exception
# type (callers handle it). Only `throw <the caught value>` is a reject.
GREEN_CATCH_TRANSLATES = (
    "package p\n"
    "fun m(s: String): Foo {\n"
    "    return try {\n"
    "        Foo.valueOf(s)\n"
    "    } catch (_: IllegalArgumentException) {\n"
    '        throw UnknownEnum("Foo", s)\n'
    "    }\n"
    "}\n"
)

# The stdlib spelling behind a real runCatching handler.
GREEN_ENUM_VALUE_OF_GUARDED = (
    "package p\n"
    "fun <T : Enum<T>> n(name: String, default: T): T =\n"
    "    runCatching { enumValueOf<T>(name) }.getOrDefault(default)\n"
)

# A handling receiver followed by a chain on its value.
GREEN_CONSUMER_THEN_LET = (
    "package p\n"
    "fun o(s: String): Foo? =\n"
    "    runCatching { Foo.valueOf(s) }.getOrNull()?.let { it }\n"
)

# Prose that merely mentions the API must not be a parse site.
GREEN_PROSE_MENTION = (
    "package p\n"
    "fun p(s: String): String = \"never parse with Foo.valueOf(s) here\" + s\n"
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
        ("catch that only rethrows `throw e` (03-1 dataflow)",
         BARE_CATCH_RETHROWS, 1),
        ("`onFailure { }.getOrThrow()` — the rethrow is two hops out (03-2 dataflow)",
         BARE_RUNCATCHING_ONFAILURE_GETORTHROW, 1),
        ("bare `enumValueOf<T>(raw)` (03-3 spelling)",
         BARE_ENUM_VALUE_OF, 1),
        ("bare parse inside a `${…}` template (03-4 parser-death)",
         BARE_INSIDE_STRING_TEMPLATE, 1),
        ("catch of an unrelated exception type (03-5 family-hole)",
         BARE_CATCH_WRONG_TYPE, 1),
        ("`runCatching { }.getOrThrow()` stays caught (control)",
         BARE_RUNCATCHING_GETORTHROW, 1),
        ("catch that translates the exception (not a bare rethrow) stays green",
         GREEN_CATCH_TRANSLATES, 0),
        ("`runCatching { enumValueOf<T>(name) }.getOrDefault(d)` stays green",
         GREEN_ENUM_VALUE_OF_GUARDED, 0),
        ("consumer followed by `?.let { }` stays green", GREEN_CONSUMER_THEN_LET, 0),
        ("prose mentioning `Foo.valueOf(s)` stays green", GREEN_PROSE_MENTION, 0),
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
