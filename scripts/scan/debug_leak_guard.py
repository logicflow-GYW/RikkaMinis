#!/usr/bin/env python3
"""Debug-code release boundary guard — RikkaMinis CI scan gate.

## Why this gate exists

`debug/` lives in `src/main` (NOT a `src/debug` source set), so the release
APK's hygiene depends on two links that are easy to silently break:

  1. every debug-package entry point must sit behind an `if (BuildConfig.DEBUG)`
     guard in the CALLING file, and
  2. R8 dead-code elimination must actually remove the classes
     (requires `isMinifyEnabled = true` and no `-keep` rule matching them).

Neither link is covered by any other gate. A future `-keep` rule, a minify
disable, or a NEW debug-package call site outside a guard would ship the
debug JSON-RPC server (127.0.0.1:5321, token-free loopback) into the release
APK with no error, warning or log line. This gate makes both loud.

Two modes:

  source <repo_root>   — every reference to `com.rikkaminis.app.debug.` outside
                         `debug/` must be in the allow-list below, or carry an
                         inline `debug-ok: <reason>` comment (same shape as
                         legacy_pipeline_guard.py). NOT a ban: the allow-list
                         files are the audited call sites. A NEW file touching
                         debug code is a build failure until it is audited.
  apk <path-to-apk>    — the release artifact must NOT contain any debug-only
                         marker string. This is the EXACT property that matters:
                         unzip the dex, grep, fail if present.

  --self-test          — fixture ground truth: the scanner must CATCH a dirty
                         fixture AND pass a clean one (a silently-regressed
                         scanner fails open; this makes it fail loud).

Usage:
  python3 scripts/scan/debug_leak_guard.py <repo_root>     # source mode
  python3 scripts/scan/debug_leak_guard.py apk <path-to-apk>
  python3 scripts/scan/debug_leak_guard.py --self-test

Exit: 0 = clean, 1 = violation(s) found.
"""

import os
import re
import struct
import sys
import zipfile

# [wide4/10] Lexing Kotlin is a solved problem in this repo: the same
# hand-rolled comment/string state machines were independently broken by the
# same witnesses in gate after gate (nested block comments, `'"'`, raw strings,
# `${...}` templates, backtick identifiers). This gate asks the shared lexer.
# A source it cannot lex is a VIOLATION, never a silent pass.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse as _lex_analyse  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool as _const_bool,
    match_bracket as _match_bracket,
    split_top as _split_top,
    stmt_spans as _stmt_spans,
    strip_parens as _strip_parens,
    when_branches as _when_branches,
)

# Debug-package target: fully-qualified references AND imports.
TARGET_RE = re.compile(r"com\.rikkaminis\.app\.debug\.")

# Files allowed to touch debug code (rel to src/android/app/src/main/java,
# posix). Each entry is an AUDITED call site; the list is the review surface.
ALLOWED_FILES = {
    "com/rikkaminis/app/MainActivity.kt",        # DebugRPCHandler.currentActivity
    "com/rikkaminis/app/MinisApp.kt",            # DebugServer start + minis-debug handler (BuildConfig.DEBUG guarded)
    "com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt",   # LLMRequestLog
    "com/rikkaminis/app/provider/openai/OpenAIProvider.kt",         # LLMRequestLog
    "com/rikkaminis/app/sandbox/offload/SessionsOffloadHandler.kt", # ChatMutationMethods
    # [verify-1015 BUG-03] Surfaced the moment the gate's argv wiring was
    # fixed (2026-10-15). OkHttpNetTraceListener is main-tree (subclassed by
    # ProviderHealthTraceListener) and calls LLMRequestLog.redactURL() so the
    # debug payload stays redacted — the same audited reason AnthropicProvider
    # and OpenAIProvider are listed above. Audited, not blanket-allowed: this
    # entry covers one call site, and any NEW file touching debug code still
    # fails the gate until it is audited.
    "com/rikkaminis/app/network/OkHttpNetTraceListener.kt",        # LLMRequestLog.redactURL
    # [fix/audit3-1015 F-03] The passthrough reply log redacts the endpoint URL
    # via LLMRequestLog.redactURL() — the same audited reason the three entries
    # above are listed. The call site is the pure string helper (no server, no
    # BuildConfig.DEBUG dependency); the URL is user-supplied (a relay base URL
    # can carry a key in its query), which is exactly what redactURL is for.
    "com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt", # LLMRequestLog.redactURL
}

# [audit-0916d] A `comment-only` entry used to sit here for ChatViewModel.kt.
# It was redundant: comment text is stripped by BLOCK_COMMENT_RE before the
# scan, so a mention inside `/* … */` never reaches TARGET_RE (verified by
# removing the entry — the source scan still exits 0). Kepping it weakens the
# gate instead: the whole 5k-line file became an unaudited surface. If a REAL
# ChatViewModel call site ever appears, the scan must fail and the site be
# audited — that is the point of the allow-list.

ESCAPE_RE = re.compile(r"debug-ok\s*:")
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)

# Audited call sites that are safe WITHOUT a BuildConfig.DEBUG guard because the
# referenced symbol is inert in release: pure static helpers (URL redaction, the
# debug capability shim that itself checks DEBUG). Everything else inside an
# allow-listed file must still sit behind a LIVE `if (BuildConfig.DEBUG)`.
# [wide3/10] The allow-list used to skip these files WHOLESALE — so
# `if (true || BuildConfig.DEBUG) { DebugServer(this).start() }` in MinisApp.kt
# (the very file the allow-list exists for) was never examined.
UNGUARDED_SYMBOLS = {
    "com/rikkaminis/app/sandbox/offload/SessionsOffloadHandler.kt": {"ChatMutationMethods"},
    "com/rikkaminis/app/network/OkHttpNetTraceListener.kt": {"LLMRequestLog"},
    "com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt": {"LLMRequestLog"},
}

# Marker strings that must be ABSENT from a release dex. Each is distinctive
# enough that a false positive is implausible: class names + the debug server
# port + a handler name only registered in debug builds.
APK_MARKERS = [
    "DebugServer",
    "DebugMethodRegistry",
    "DebugOffloadHandler",
    "DebugRPCHandler",
    "minis-debug",
    "5321",
]


def _views(text):
    """(literal-kept view, code view, reason) from the shared Kotlin lexer.

    `literal-kept` = comments blanked, string contents KEPT (a reference spelled
    inside a string is still a reference — that is how reflection builds one).
    `code` = comments AND literal contents blanked (structure: brace depth, guard
    nesting, statement boundaries).

    [wide4/10] The gate used to carry its own `blank()` state machine. It fell
    over on Kotlin's real lexical grammar: inside `/* outer /* inner */`, the
    first `*/` closed the comment, so a triple-quote on the same line opened a
    raw string and every real line after it — including an unbounded
    `inputStream().use { … }` read — was blanked into invisibility
    (parser-death witness). Both views are the same length as the input, so a
    match offset still indexes the original text.
    """
    ok, reason, views = _lex_analyse(text)
    if not ok:
        return None, None, reason
    return views["comments"], views["code"], None


# `com.rikkaminis.app.BuildConfig.DEBUG` is the same flag as `BuildConfig.DEBUG`
# (both spellings are in use in this tree); the package prefix is not a
# different guard.
_DEBUG_FLAG_RE = re.compile(r"^(?:[A-Za-z_]\w*\.)*BuildConfig\.DEBUG$")


def _is_debug_flag(piece):
    """`piece` is EXACTLY the build flag (optionally `== true`) and nothing else."""
    if _DEBUG_FLAG_RE.match(piece):
        return True
    if piece.endswith("==true"):
        return bool(_DEBUG_FLAG_RE.match(piece[:-6]))
    return False


def _squash(expr):
    """The expression with its outer parens and every whitespace run removed."""
    return re.sub(r"\s+", "", _strip_parens(expr))


def guard_decides(cond):
    """Does this `if` condition make its body DEAD in a release build?

    [wide4/10] The criterion is POSITIVE now — the flag must be the thing that
    decides. The old test was "mentions BuildConfig.DEBUG and contains no
    true/false literal", which accepted both of these:
      * `if (!BuildConfig.DEBUG)` — the guard INVERTED, i.e. the debug server
        starts in RELEASE builds only: the worst shape of the defect; and
      * `if (!BuildConfig.DEBUG || BuildConfig.DEBUG)` — a tautology, always on.
    Rules: no top-level `||` (a disjunct can satisfy the guard by itself); one
    top-level `&&` conjunct must be EXACTLY the flag (`BuildConfig.DEBUG` or
    `BuildConfig.DEBUG == true`); a conjunct that only MENTIONS the flag in
    another shape (`!DEBUG`, `DEBUG == false`, `f(DEBUG)`) is not evidence; a
    conjunct that folds to constant false kills the guard.
    """
    expr = _strip_parens(cond)
    if not expr:
        return False
    if len(_split_top(expr, ("||",))) > 1:
        return False
    decided = False
    for a, b in _split_top(expr, ("&&",)):
        piece = _squash(expr[a:b])
        if not piece:
            return False
        if _is_debug_flag(piece):
            decided = True
            continue
        if "BuildConfig.DEBUG" in piece:
            return False
        if _const_bool(piece) is False:
            return False
    return decided


def _dead_cond(cond):
    """A condition whose value is a compile-time constant FALSE.

    [wide4/10] The old `DEAD_COND_RE` was a spelling list (`false`, `!true`,
    `0 == 1`, `true ||`, `false &&`). `1 == 2` states the same fact in a
    different spelling and walked straight through. Death is a VALUE question,
    so ask the shared constant folder instead of enumerating spellings.
    """
    return _const_bool(cond) is False


def _in_dead_when_arm(code, pos):
    """Is `pos` inside a `when` arm a constant subject can never select?

    `when (false) { true -> A  else -> B }` runs B; A is dead code. A read
    parked in A while B reads the file another way is exactly how
    `readFileSegment(...)` stayed "present" in the handler while
    `Files.readAllBytes(...)` performed the unbounded read.
    """
    for subject, arm_cond, b_start, b_end in _when_branches(code):
        if not (b_start <= pos < b_end):
            continue
        subj = _const_bool(subject) if subject.strip() else None
        if subj is None:
            continue
        arm = arm_cond.strip()
        if arm in ("else", "") or arm.startswith("is ") or arm.startswith("in ") or "," in arm:
            continue            # else / type test / value list: never provably dead
        val = _const_bool(arm)
        if val is not None and val != subj:
            return True
    return False



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


def dead_guarded(code, off):
    """Is the call at `off` in code no build can reach?

    [wide4/10] Death is decided by the constant folder (`const_bool`) and by
    `when` arm selection, not by a spelling list — see `_dead_cond` /
    `_in_dead_when_arm`.
    """
    if any(_dead_cond(c) for c in _if_conditions(code[_stmt_start(code, off):off])):
        return True
    if _in_dead_when_arm(code, off):
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
                if ch == "{" and any(
                        _dead_cond(c)
                        for c in _if_conditions(code[_stmt_start(code, i):i])):
                    return True
            else:
                depth -= 1
    return False



def _if_conditions(head):
    """Conditions of every `if (...)` in `head`."""
    out = []
    for m in re.finditer(r"\bif\s*\(", head):
        end = _paren_end(head, m.end() - 1)
        if end > 0:
            out.append(head[m.end():end])
    return out


def debug_guarded(code, off):
    """Is the reference at `off` really behind `if (BuildConfig.DEBUG)`?

    Two shapes matter. (a) The guard must DECIDE — see `guard_decides`: the old
    "mentions the flag, no true/false literal" test accepted `if (true ||
    BuildConfig.DEBUG)` (ignored flag), `if (!BuildConfig.DEBUG)` (the guard
    inverted: release builds only) and `if (!DEBUG || DEBUG)` (tautology), so R8
    kept the class and release shipped the token-free loopback server.
    (b) The reference must be inside the guarded block (`if (BuildConfig.DEBUG)
    { … }`) or on the guarded statement (`if (BuildConfig.DEBUG) call()`).
    """
    if any(guard_decides(c) for c in _if_conditions(code[_stmt_start(code, off):off])):
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
                if ch == "{" and any(guard_decides(c) for c in
                                     _if_conditions(code[_stmt_start(code, i):i])):
                    return True
            else:
                depth -= 1
    return False


# [da1028-A] ISSUE-07 — API-33 stream helpers on a minSdk-26 app.
# The handler used to read a file slice with
# `inputStream().use { it.skip(offset); it.readNBytes(limit) }`:
# `InputStream.readNBytes(int)` is API 33+ (NoSuchMethodError below that) and
# `skip()` may under-skip, silently reading from the wrong offset. The fix is
# the shape `handleLogsRead` already used: RandomAccessFile.seek + a bounded
# read loop. The bounded loop was extracted to `readFileSegment` so it can be
# exercised by a JVM slice test (the task prescribes that extraction), so this
# gate pins the contract TRANSITIVELY: `handleReadFile` must either use
# RandomAccessFile itself or route through a helper that does.
DEBUG_RPC_REL = "src/android/app/src/main/java/com/rikkaminis/app/debug/DebugRPCHandler.kt"
API33_STREAM_RE = re.compile(r"\.readNBytes\s*\(")
READ_FILE_FUN = "handleReadFile"
SEGMENT_HELPER = "readFileSegment"


def _fun_span(code, name):
    """(body_start, body_end) offsets of `fun NAME(…) { … }`, or None."""
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
        return None  # expression body: no block to inspect
    end = _brace_end(code, brace)
    if end < 0:
        return None
    return (brace + 1, end)


# [wide4/10] Reads that cannot be bounded, or that ignore the requested
# offset. The old list (`inputStream|readBytes|readNBytes|skip`) was a spelling
# blacklist: `Files.readAllBytes`, `bufferedReader().readText()` and friends read
# the whole file while ignoring `offset`/`limit` and simply were not on it.
UNBOUNDED_READ_RE = re.compile(
    r"\.\s*(?:readBytes|readNBytes|readAllBytes|readText|readLines|forEachLine|"
    r"bufferedReader|reader|copyTo|inputStream|skip)\s*\(")

# The read statement's value: `val bytes = <value>` or `return <value>`.
_READ_STMT_PREFIX_RE = re.compile(
    r"^(?:return|(?:val|var)\s+[A-Za-z_]\w*(?:\s*:[^=\n]+?)?\s*=)$")


def _helper_calls(stmt):
    """[(start, close)] offsets of every `readFileSegment(…)` in the statement."""
    out = []
    for m in re.finditer(re.escape(SEGMENT_HELPER) + r"\s*\(", stmt):
        out.append((m.start(), _match_bracket(stmt, m.end() - 1)))
    return out


def read_source_verdict(stmt):
    """Is the statement's VALUE exactly one bounded helper call?

    [wide4/10] Two witnesses walked through the old criteria:
      * `readFileSegment(...).let { f.bufferedReader().readText().toByteArray() }`
        — helper called, its result dropped, unbounded read done in the chain;
      * `when (false) { true -> readFileSegment(...) else -> Files.readAllBytes(...) }`
        — helper present in the text, the real read elsewhere.
    "The call exists" is not the property. "The call IS the value" is.
    Returns (ok, why).
    """
    calls = _helper_calls(stmt)
    if not calls:
        return True, ""
    if len(calls) > 1:
        return False, "the bounded helper result is not the statement value (multiple calls)"
    start, close = calls[0]
    if close < 0:
        return False, "the bounded helper call does not close (the read cannot be proven)"
    prefix = stmt[:start].strip()
    suffix = stmt[close + 1:].strip()
    if not _READ_STMT_PREFIX_RE.match(prefix):
        return False, ("the bounded helper call is not the read statement's value "
                       "(`%s` before it)" % prefix[:60])
    if suffix:
        return False, "the bounded helper result is dropped by a trailing chain"
    return True, ""


def scan_api26_reads(root):
    """Violations for the minSdk-26 read contract in the debug RPC handler.

    Criteria (fail-closed; comments and literal contents blanked, so a doc
    mention never counts and a dead branch does not count either):
      1. no LIVE `.readNBytes(` anywhere in the file (API 33+ on minSdk 26);
      2. `handleReadFile` uses RandomAccessFile, or routes through a LIVE
         `readFileSegment(...)` whose body uses RandomAccessFile;
      3. no LIVE unbounded read inside `handleReadFile`
         (`readBytes|readAllBytes|readText|readLines|bufferedReader|reader|
         copyTo|inputStream|skip|…`);
      4. the helper call must BE the value of the read statement — a trailing
         `.let {}` / `.also {}` / `?:` chain, or a `when`/`if` wrapper with the
         call in a dead arm, is a violation;
      5. a missing / renamed `handleReadFile`, or a helper that is called but
         not defined here, is anchor drift and is itself a violation.

    [wide3/10] criterion 3 was added for `if (false) readFileSegment(...) else
    inputStream()…`; [wide4/10] criteria 3+4 exist because existence-based
    versions of them were defeated by `.let {}`, `when (false)` and
    `Files.readAllBytes`.

    A tree with no DebugRPCHandler.kt at all (the source scan also runs against
    synthetic fixtures) is SKIPPED, loudly — never a silent pass.
    """
    path = os.path.join(root, DEBUG_RPC_REL)
    if not os.path.isfile(path):
        print("  [skip] api26 reads: %s absent from this tree" % DEBUG_RPC_REL)
        return []
    raw = open(path, encoding="utf-8", errors="replace").read()
    raw_lines = raw.splitlines()
    _lit, code, reason = _views(raw)
    if code is None:
        return ["%s  cannot be lexed (%s) — the read contract is unverifiable on a "
                "half-parsed file (fail-closed)" % (DEBUG_RPC_REL, reason)]
    out = []
    for m in API33_STREAM_RE.finditer(code):
        if dead_guarded(code, m.start()):
            continue
        line = code.count("\n", 0, m.start()) + 1
        text = raw_lines[line - 1].strip()[:80] if line <= len(raw_lines) else ""
        out.append("%s:%d  API 33+ stream call on minSdk 26: %s" % (DEBUG_RPC_REL, line, text))
    span = _fun_span(code, READ_FILE_FUN)
    if span is None:
        out.append("%s: anchor drift — `fun %s(` not found" % (DEBUG_RPC_REL, READ_FILE_FUN))
        return out
    a, b = span
    body = code[a:b]
    body_line = code.count("\n", 0, a) + 1
    for m in UNBOUNDED_READ_RE.finditer(body):
        if dead_guarded(code, a + m.start()):
            continue
        line = code.count("\n", 0, a + m.start()) + 1
        out.append("%s:%d  %s() reads through a stream (unbounded read / "
                   "under-skipping) — use RandomAccessFile.seek + a bounded read"
                   % (DEBUG_RPC_REL, line, READ_FILE_FUN))
    if "RandomAccessFile" in body:
        return out
    live_helper = False
    for s_start, s_end in _stmt_spans(body):
        stmt = body[s_start:s_end]
        for start, _close in _helper_calls(stmt):
            if dead_guarded(code, a + s_start + start):
                continue
            live_helper = True
            ok_read, why = read_source_verdict(stmt)
            if ok_read:
                continue
            line = code.count("\n", 0, a + s_start + start) + 1
            out.append("%s:%d  %s() %s — the read must be exactly "
                       "`readFileSegment(file, offset, limit)`"
                       % (DEBUG_RPC_REL, line, READ_FILE_FUN, why))
    if live_helper:
        helper = _fun_span(code, SEGMENT_HELPER)
        helper_line = (code.count("\n", 0, helper[0]) + 1) if helper else body_line
        if helper is None:
            out.append("%s:%d  %s() is called but not defined in this file"
                       % (DEBUG_RPC_REL, body_line, SEGMENT_HELPER))
        elif "RandomAccessFile" not in code[helper[0]:helper[1]]:
            out.append("%s:%d  %s() does not use RandomAccessFile (bounded seek+read)"
                       % (DEBUG_RPC_REL, helper_line, SEGMENT_HELPER))
        return out
    out.append("%s:%d  %s() neither uses RandomAccessFile nor routes through %s()"
               % (DEBUG_RPC_REL, body_line, READ_FILE_FUN, SEGMENT_HELPER))
    return out



def _symbol_after(code, off):
    m = re.match(r"\s*(\w+)", code[off:])
    return m.group(1) if m else ""


DEBUG_PKG_DIR = "src/android/app/src/main/java/com/rikkaminis/app/debug"
DEBUG_IMPORT_RE = re.compile(
    r"(?m)^[ \t]*import[ \t]+com\.rikkaminis\.app\.debug\.(\*|[A-Za-z_]\w*)")
# The package prefix WITHOUT a trailing `.`: the head of a name built at
# runtime, e.g. `Class.forName("com.rikkaminis.app.debug" + ".DebugServer")`,
# which never spells the fully-qualified name TARGET_RE looks for.
TARGET_PREFIX_RE = re.compile(r"com\.rikkaminis\.app\.debug(?![.\w])")
_DEBUG_CLASS_RE = re.compile(
    r"(?m)^[ \t]*(?:(?:@[\w.]+(?:\((?:[^()]|\([^()]*\))*\))?|(?:public|internal|private|"
    r"protected|open|final|abstract|sealed|data|enum|value|annotation|inline|expect|actual|"
    r"companion)\b)\s+)*(?:class|object|interface)[ \t]+([A-Za-z_]\w*)")


def debug_package_symbols(root):
    """({name…}, present) for the debug package's top-level types.

    Needed for `import com.rikkaminis.app.debug.*`: the wildcard names nothing,
    so the gate has to know what it brings into scope or that import form becomes
    a blind spot for the whole package (wide4/10 scope witness).
    """
    d = os.path.join(root, DEBUG_PKG_DIR)
    names = set()
    if not os.path.isdir(d):
        return names, False
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".kt"):
            continue
        raw = open(os.path.join(d, fn), encoding="utf-8", errors="replace").read()
        _lit, struct, _reason = _views(raw)
        if struct is None:
            continue
        names.update(m.group(1) for m in _DEBUG_CLASS_RE.finditer(struct))
    return names, True


def scan_source(root):
    """Every debug-package reference outside `debug/` must be audited AND guarded.

    [wide3/10] allow-listed files are not skipped wholesale; every reference in
    them must sit behind a live `if (BuildConfig.DEBUG)` or be one of the
    audited inert symbols.
    [wide4/10] three more spellings of the same reference are now covered:
      * the guard must DECIDE (`guard_decides`), so `!BuildConfig.DEBUG` and
        tautologies no longer read as guards;
      * an imported symbol used UNQUALIFIED (`import …debug.DebugServer` then
        `DebugServer(this).start()`) is the same reference as the qualified one,
        and `debug.*` wildcards are resolved from the package itself;
      * a package prefix assembled at runtime (reflection) is caught by
        `TARGET_PREFIX_RE`.
    A file the shared lexer cannot parse is a violation: the gate refuses to
    judge a source whose string/comment structure it cannot resolve.
    """
    src = os.path.join(root, "src/android/app/src")
    debug_syms, debug_pkg_present = debug_package_symbols(root)
    violations = []
    scanned = 0
    seen = 0
    for base, _dirs, files in os.walk(src):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            seen += 1
            path = os.path.join(base, fn)
            rel = os.path.relpath(path, src).replace(os.sep, "/")
            posix_rel = os.path.relpath(
                path, os.path.join(src, "main/java")).replace(os.sep, "/")
            rel_slash = "/" + rel
            if "/test/" in rel_slash or "/androidTest/" in rel_slash or "/debug/" in posix_rel:
                continue  # the debug package itself + tests pin the behaviour
            scanned += 1
            raw = open(path, encoding="utf-8", errors="replace").read()
            lit, struct, reason = _views(raw)
            if lit is None:
                violations.append(
                    "%s  this gate cannot lex the file (%s) — it refuses to judge a "
                    "source whose string/comment structure it cannot resolve"
                    % (posix_rel, reason))
                continue
            raw_lines = raw.splitlines()
            lit_lines = lit.splitlines()
            struct_lines = struct.splitlines()
            allowed_syms = UNGUARDED_SYMBOLS.get(posix_rel, set())
            audited = posix_rel in ALLOWED_FILES

            def line_no_of(pos, view=None):
                return (lit if view is None else view).count("\n", 0, pos) + 1

            def exempt(line_no):
                cur = raw_lines[line_no - 1] if line_no <= len(raw_lines) else ""
                prev = raw_lines[line_no - 2] if line_no >= 2 else ""
                return bool(ESCAPE_RE.search(cur) or ESCAPE_RE.search(prev))

            def report(pos, sym, note):
                line_no = line_no_of(pos)
                if exempt(line_no):
                    return              # `debug-ok: <reason>` escape
                if sym and sym in allowed_syms:
                    return              # audited symbol that is inert in release
                if debug_guarded(struct, pos):
                    return
                line = lit_lines[line_no - 1] if line_no <= len(lit_lines) else ""
                if audited:
                    violations.append(
                        "%s:%d  audited file, but this reference is not behind a live "
                        "`if (BuildConfig.DEBUG)` (a constant-true `||`, an inverted "
                        "`!BuildConfig.DEBUG`, or a dropped flag disables the guard): "
                        "%s  [%s]"
                        % (posix_rel, line_no, line.strip()[:90], note))
                else:
                    violations.append("%s:%d  %s  [%s]"
                                      % (posix_rel, line_no, line.strip()[:100], note))

            # An import line carrying `debug-ok:` is a HUMAN audit of that symbol
            # for THIS file (same semantics as UNGUARDED_SYMBOLS, but derived from
            # the tree instead of a hardcoded table): the symbol is inert in
            # release, so its use sites here do not each need their own guard.
            # A debug import WITHOUT the escape still puts the symbol under the
            # guard requirement — that is the wide4/10 scope witness.
            for m in DEBUG_IMPORT_RE.finditer(lit):
                name = m.group(1)
                if name != "*" and exempt(line_no_of(m.start())):
                    allowed_syms = allowed_syms | {name}

            # (1) fully-qualified references
            for m in TARGET_RE.finditer(lit):
                line_no = line_no_of(m.start())
                line = lit_lines[line_no - 1] if line_no <= len(lit_lines) else ""
                if exempt(line_no):
                    continue
                if re.match(r"\s*import\b", line):
                    if audited:
                        continue        # an audited file may import a debug symbol
                    violations.append(
                        "%s:%d  unaudited debug-package import: %s"
                        % (posix_rel, line_no, line.strip()[:100]))
                    continue
                report(m.start(), _symbol_after(lit, m.end()), "debug-package reference")

            # (2) the package prefix assembled at runtime (reflection)
            for m in TARGET_PREFIX_RE.finditer(lit):
                report(m.start(), "", "debug-package prefix built at runtime (reflection)")

            # (3) imported symbols used unqualified
            imported = set()
            wildcard = False
            for m in DEBUG_IMPORT_RE.finditer(lit):
                if m.group(1) == "*":
                    wildcard = True
                else:
                    imported.add(m.group(1))
            if wildcard:
                if debug_pkg_present:
                    imported |= debug_syms
                else:
                    violations.append(
                        "%s  wildcard import of the debug package, but the package is not "
                        "in this tree — the gate cannot enumerate what the import brings "
                        "into scope (fail-closed)" % posix_rel)
            for name in sorted(imported):
                pat = re.compile(r"(?<![\w.$])" + re.escape(name) + r"\b")
                for m in pat.finditer(struct):
                    line_no = line_no_of(m.start(), struct)
                    line = struct_lines[line_no - 1] if line_no <= len(struct_lines) else ""
                    if re.match(r"\s*import\b", line):
                        continue        # the import itself, already judged above
                    report(m.start(), name,
                           "unqualified use of the imported debug symbol `%s`" % name)
    if seen == 0:
        violations.append(
            "fail-closed: no .kt file under src/android/app/src was scanned — the "
            "root is wrong or the source layout moved (a walk that sees nothing "
            "must never read as clean)")
    return violations



def dex_entries(names):
    """Entry names the guard reads as the artifact's dex code.

    Pure (takes a name list) so the selector AND the fail-closed verdict below
    are testable without building a zip. Matched by BASENAME so a nested
    `dex/classes.dex` still counts; a renamed or absent dex yields [] — and []
    is a VIOLATION, not a pass (see [apk_verdict]).
    """
    out = []
    for n in names:
        base = os.path.basename(n)
        if base == "classes.dex" or (base.startswith("classes") and base.endswith(".dex")):
            out.append(n)
    return out


def apk_verdict(present, dex_count):
    """Verdict for the apk mode: 0 = clean, 1 = violation.

    [audit-0916d] Fail CLOSED on zero dex entries. Without this, a layout
    change (dex renamed, or the artifact truncated) made the gate print
    "markers absent" exit 0 without having scanned a single byte — a gate that
    cannot fail is not a gate. Verified: a zip carrying no `classes*.dex` used
    to pass.
    """
    if dex_count == 0:
        return 1
    if present:
        return 1
    return 0


def _read_uleb128(data, off):
    """Read a uleb128 from a dex. Raises ValueError past the buffer end."""
    result = 0
    shift = 0
    while True:
        if off >= len(data):
            raise ValueError("uleb128 runs past end of buffer")
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7
        if shift > 28:
            raise ValueError("uleb128 too long")

def dex_strings(data):
    """Return the string contents of a dex's string_data section.

    Pure (takes bytes) so the string-table-only scan is testable without a
    real build.

    [2026-09-24] The guard used to scan the RAW dex bytes. A short marker can
    coincide with dalvik bytecode operands across instruction boundaries:
    the 2026-09-24 release CI false-positived on "5321" — two iput-object
    instructions whose field-index bytes spelled 5321 (offset 3161468, code
    section, verified NOT inside any string_data item; each branch built
    clean, the merged input deterministically produced the byte sequence).
    A real debug leak always surfaces as strings in string_data (class names
    survive only via keep rules, whose names live in the same table), so
    scanning ONLY string contents removes the raw-byte false-positive class
    entirely while still catching every real leak.
    """
    if len(data) < 64:
        raise ValueError("dex too small to parse")
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    if string_ids_off + 4 * string_ids_size > len(data):
        raise ValueError("string_ids table runs past end of buffer")
    out = []
    for i in range(string_ids_size):
        (item_off,) = struct.unpack_from("<I", data, string_ids_off + 4 * i)
        _utf16_len, pos = _read_uleb128(data, item_off)
        end = data.index(b"\x00", pos)  # MUTF-8 NUL is 0xC0 0x80, so 0x00 is safe
        out.append(data[pos:end].decode("utf-8", errors="replace"))
    return out

def scan_apk(apk_path):
    """Return (debug markers present, dex entries scanned) for the APK."""
    present = []
    dex_count = 0
    with zipfile.ZipFile(apk_path) as zf:
        for name in dex_entries(zf.namelist()):
            dex_count += 1
            data = zf.read(name)
            try:
                strings = dex_strings(data)
            except ValueError as e:
                # Fail CLOSED: an unparseable dex must not read as clean.
                present.append(f"{name}: UNPARSEABLE DEX ({e})")
                continue
            for s in strings:
                for marker in APK_MARKERS:
                    if marker in s:
                        present.append(f"{name}: {marker} (string: {s[:60]!r})")
    return present, dex_count


def self_test():
    import tempfile
    ok = True
    # --- source mode: dirty fixture must FAIL, clean must PASS ---
    base = os.path.join("src/android/app/src/main/java/com/rikkaminis/app")
    dirty = {
        f"{base}/SomeNewFile.kt": "val x = com.rikkaminis.app.debug.DebugServer()\n",
        f"{base}/debug/DebugServer.kt": "package com.rikkaminis.app.debug\nclass DebugServer\n",
    }
    root = tempfile.mkdtemp(prefix="debugleak-dirty-")
    for rel, content in dirty.items():
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w").write(content)
    v = scan_source(root)
    ok &= bool(v) and check(f"source mode CATCHES dirty fixture ({len(v)} violation)", len(v) > 0)

    root2 = tempfile.mkdtemp(prefix="debugleak-clean-")
    clean = {
        f"{base}/MinisApp.kt": "if (BuildConfig.DEBUG) { com.rikkaminis.app.debug.DebugServer(this).start() }\n",
        f"{base}/SomeOther.kt": "// mentions debug.DebugServer in a comment only\n",
        f"{base}/debug/DebugServer.kt": "package com.rikkaminis.app.debug\nclass DebugServer\n",
    }
    for rel, content in clean.items():
        p = os.path.join(root2, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w").write(content)
    v2 = scan_source(root2)
    ok &= check("source mode passes clean fixture (allow-listed + comment-only)", len(v2) == 0, str(v2))

    # --- [wide3/10] scope: an allow-listed file is no longer a blanket pass ---
    root4 = tempfile.mkdtemp(prefix="debugleak-scope-")
    for rel, content in {
        f"{base}/MinisApp.kt":
            "fun boot() {\n"
            "    if (true || BuildConfig.DEBUG) {\n"
            "        com.rikkaminis.app.debug.DebugServer(this).start()\n"
            "    }\n"
            "}\n",
        f"{base}/debug/DebugServer.kt": "package com.rikkaminis.app.debug\nclass DebugServer\n",
    }.items():
        pq = os.path.join(root4, rel)
        os.makedirs(os.path.dirname(pq), exist_ok=True)
        open(pq, "w").write(content)
    v4 = scan_source(root4)
    ok &= check("scope: `if (true || BuildConfig.DEBUG)` in an audited file is caught",
                len(v4) == 1 and "DebugServer" in v4[0], str(v4))

    # --- [wide3/10] parser-death: `"/*"` in a string must not open a comment ---
    root5 = tempfile.mkdtemp(prefix="debugleak-stringcomment-")
    pq = os.path.join(root5, base, "ui/chat/ChatViewModel.kt")
    os.makedirs(os.path.dirname(pq), exist_ok=True)
    open(pq, "w").write(
        "class ChatViewModel {\n"
        '    private val glob = "logs/*"\n'
        "    private val leak = com.rikkaminis.app.debug.DebugMethodRegistry.discover()\n"
        '    private val close = "*/"\n'
        "}\n")
    v5 = scan_source(root5)
    ok &= check("parser-death: a `\"/*\"` string cannot blank the code below it",
                len(v5) == 1 and "DebugMethodRegistry" in v5[0], str(v5))

    # --- [wide4/10] family-hole: the guard must DECIDE, not merely mention ---
    import shutil as _shutil

    def _w4_tree(files):
        d = tempfile.mkdtemp(prefix="debugleak-w4-")
        for r, c in files.items():
            pq = os.path.join(d, r)
            os.makedirs(os.path.dirname(pq), exist_ok=True)
            open(pq, "w").write(c)
        return d

    _w4_dbg = ("%s/debug/DebugServer.kt" % base,
               "package com.rikkaminis.app.debug\nclass DebugServer\n")
    w4_source_arms = [
        ("w4-inverted guard `if (!BuildConfig.DEBUG)` is caught (release-only server)",
         "%s/MinisApp.kt" % base,
         "fun boot() {\n    if (!BuildConfig.DEBUG) {\n"
         "        com.rikkaminis.app.debug.DebugServer(this).start()\n    }\n}\n",
         ["not behind a live"]),
        ("w4-tautology `if (!BuildConfig.DEBUG || BuildConfig.DEBUG)` is caught",
         "%s/MinisApp.kt" % base,
         "fun boot() {\n    if (!BuildConfig.DEBUG || BuildConfig.DEBUG) {\n"
         "        com.rikkaminis.app.debug.DebugServer(this).start()\n    }\n}\n",
         ["not behind a live"]),
        ("w4-imported symbol used unqualified is the same reference",
         "%s/MinisApp.kt" % base,
         "import com.rikkaminis.app.debug.DebugServer\n"
         "fun boot() {\n    DebugServer(this).start()\n}\n",
         ["not behind a live"]),
        ("w4-runtime-built package prefix (reflection) is caught",
         "%s/MinisApp.kt" % base,
         "fun boot() {\n    if (true) {\n"
         "        val cls = Class.forName(\"com.rikkaminis.app.debug\" + \".DebugServer\")\n"
         "    }\n}\n",
         ["reflection"]),
    ]
    w4_ok = True
    for label, rel, body, needles in w4_source_arms:
        d = _w4_tree({rel: body, _w4_dbg[0]: _w4_dbg[1]})
        got = scan_source(d)
        passed = len(got) == 1 and all(any(n in x for x in got) for n in needles)
        w4_ok &= check("source: %s" % label, passed, "got=%s" % got)
        _shutil.rmtree(d, ignore_errors=True)
    d = _w4_tree({
        "%s/MinisApp.kt" % base:
            "fun boot() {\n    if (com.rikkaminis.app.BuildConfig.DEBUG && flags) {\n"
            "        com.rikkaminis.app.debug.DebugServer(this).start()\n    }\n}\n",
        _w4_dbg[0]: _w4_dbg[1]})
    got = scan_source(d)
    w4_ok &= check(
        "source: a DECIDED guard (`DEBUG && flags`, package-qualified) stays clean",
        got == [], "got=%s" % got)
    _shutil.rmtree(d, ignore_errors=True)
    ok &= w4_ok
    print("   DEBUG_SOURCE_SELFTEST=%s (%d fixtures)"
          % ("PASS" if w4_ok else "FAIL", len(w4_source_arms) + 1))

    # --- [verify-1015 BUG-03] reverse controls for the two fail-open holes ---
    # (a) a test-tree file referencing debug code must NOT be reported as a
    #     main-tree violation (`rel` had no leading slash, so "/test/" missed).
    root3 = tempfile.mkdtemp(prefix="debugleak-testdir-")
    test_file = "src/android/app/src/test/java/com/rikkaminis/app/SomeTest.kt"
    p = os.path.join(root3, test_file)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write("import com.rikkaminis.app.debug.LLMRequestLog\n")
    v3 = scan_source(root3)
    ok &= check("source mode ignores test-tree references", len(v3) == 0, str(v3))

    # (b) the argv contract: `source <root>` (the documented form) and a bogus
    #     root must not read as "clean". A silently-empty walk was the whole
    #     bug — the gate printed ✅ and exited 0 for a nonexistent tree.
    ok &= check(
        "argv: `source <root>` resolves the root, not the mode word",
        _resolve_root(["source", root2]) == os.path.abspath(root2),
    )
    ok &= check(
        "argv: bare <root> still works",
        _resolve_root([root2]) == os.path.abspath(root2),
    )
    ok &= check("argv: missing tree is rejected", _resolve_root(["source"]) is None)

    # reverse control: the marker list itself must be non-trivial
    ok &= check("apk marker list non-empty", len(APK_MARKERS) >= 3)

    # --- apk mode: dex selector + fail-closed verdict (reverse control) ---
    # [audit-0916d] A zip with no `classes*.dex` used to print 'markers absent'
    # and exit 0 — the gate could not fail. These pin both halves.
    real_layout = ["classes.dex", "resours/foo.bin", "lib/aarch64/libx.so"]
    ok &= check(
        "apk selector finds the real layout's dex",
        dex_entries(real_layout) == ["classes.dex"],
        str(dex_entries(real_layout)),
    )
    ok &= check(
        "apk selector finds a nested dex",
        dex_entries(["dex/classes2.dex"]) == ["dex/classes2.dex"],
    )
    ok &= check(
        "apk selector ignores an artifact with no dex",
        dex_entries(["resours/foo.bin"]) == [],
    )
    ok &= check("apk verdict: ZERO dex entries is a VIOLATION (fail closed)", apk_verdict([], 0) == 1)
    ok &= check("apk verdict: markers present is a violation", apk_verdict(["classes.dex: DebugServer"], 1) == 1)
    ok &= check("apk verdict: clean dex passes", apk_verdict([], 1) == 0)

    # --- apk scan: string-table-only (2026-09-24 raw-byte false-positive regression) ---
    # The raw-byte scan flagged "5321" that was actually dalvik bytecode
    # operands (two iput-object field indices). These fixtures pin: bytes in
    # bytecode/metadata territory never count; a marker in string_data does.
    def _mk_dex(strings, junk=b""):
        """Minimal dex-shaped fixture: 64-byte header + string table + data."""
        n = len(strings)
        buf = bytearray(64 + 4 * n)
        struct.pack_into("<II", buf, 56, n, 64)
        data_off = 64 + 4 * n
        blob = b""
        for i, s in enumerate(strings):
            raw = s.encode("utf-8") + b"\x00"
            ln = len(s)
            uleb = bytes([ln]) if ln < 0x80 else bytes([0x80 | (ln & 0x7F), ln >> 7])
            struct.pack_into("<I", buf, 64 + 4 * i, data_off + len(blob))
            blob += uleb + raw
        return bytes(buf) + blob + junk

    import zipfile as _zf
    dex_clean = _mk_dex(
        ["Lcom/example/Foo;", "hello world"],
        junk=b"\x55\x53\x35\x33\x32\x31\xab\xcd" * 4,  # the 09-24 bytecode class
    )
    dex_dirty = _mk_dex(["Lcom/example/DebugServer;", "minis-debug"], junk=b"")
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tf:
        with _zf.ZipFile(tf, "w") as z:
            z.writestr("classes.dex", dex_clean)
            z.writestr("resours/foo.bin", b"junk")
        apk_clean_path = tf.name
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tf2:
        with _zf.ZipFile(tf2, "w") as z:
            z.writestr("classes.dex", dex_dirty)
        apk_dirty_path = tf2.name
    with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as tf3:
        with _zf.ZipFile(tf3, "w") as z:
            z.writestr("classes.dex", b"\x00" * 10)
        apk_broken_path = tf3.name
    present_c, cnt_c = scan_apk(apk_clean_path)
    ok &= check(
        "apk scan: marker bytes in bytecode (not string_data) are NOT flagged",
        present_c == [] and cnt_c == 1,
        str(present_c),
    )
    present_d, cnt_d = scan_apk(apk_dirty_path)
    ok &= check(
        "apk scan: a debug marker in string_data IS flagged",
        cnt_d == 1 and any("DebugServer" in p for p in present_d),
        str(present_d),
    )
    present_b, cnt_b = scan_apk(apk_broken_path)
    ok &= check(
        "apk scan: an unparseable dex fails CLOSED",
        cnt_b == 1 and any("UNPARSEABLE" in p for p in present_b),
        str(present_b),
    )
    for p in (apk_clean_path, apk_dirty_path, apk_broken_path):
        os.unlink(p)
    # --- [da1028-A] ISSUE-07: minSdk-26 read contract (API-33 stream helpers) ---
    # Ground truth for scan_api26_reads(): the comment-stripped live call is
    # caught; a comment-only mention is not (precision control); the fix's two
    # failure shapes (no RandomAccessFile anywhere in the path, and a called
    # helper that is not defined) each go red on their own criterion.
    import shutil

    api_rel = os.path.dirname(DEBUG_RPC_REL)

    def _api_tree(handler_text):
        d = tempfile.mkdtemp(prefix="debugleak-api26-")
        p = os.path.join(d, api_rel, "DebugRPCHandler.kt")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w").write(handler_text)
        return d

    ok_handler = (
        "package com.rikkaminis.app.debug\n"
        "\n"
        "import java.io.RandomAccessFile\n"
        "\n"
        "class DebugRPCHandler {\n"
        "    // [da1028-A] was `it.skip(offset); it.readNBytes(limit)`: readNBytes(int) is\n"
        "    // API 33+ while minSdk is 26, and skip() may under-skip.\n"
        "    private fun handleReadFile(params: org.json.JSONObject): ByteArray {\n"
        "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n"
        "    }\n"
        "\n"
        "    private fun readFileSegment(file: java.io.File, offset: Long, limit: Int): ByteArray {\n"
        "        return RandomAccessFile(file, \"r\").use { raf ->\n"
        "            raf.seek(offset)\n"
        "            ByteArray(0)\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    live_handler = ok_handler.replace(
        "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n",
        "        val input = java.io.File(\"x\").inputStream()\n"
        "        input.skip(0L)\n"
        "        return input.readNBytes(16)\n",
    )
    noraf_handler = ok_handler.replace(
        "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n",
        "        return java.io.File(\"x\").readBytes()\n",
    )
    missing_helper_handler = ok_handler.replace(
        "    private fun readFileSegment(file: java.io.File, offset: Long, limit: Int): ByteArray {\n"
        "        return RandomAccessFile(file, \"r\").use { raf ->\n"
        "            raf.seek(offset)\n"
        "            ByteArray(0)\n"
        "        }\n"
        "    }\n",
        "",
    )
    drifted_handler = ok_handler.replace("private fun handleReadFile(", "private fun readFileSection(")

    dead_else_handler = ok_handler.replace(
        "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n",
        "        val bytes = if (false) readFileSegment(java.io.File(\"x\"), 0L, 16)\n"
        "            else java.io.File(\"x\").inputStream().use { it.skip(0L); it.readBytes() }\n"
        "        return bytes\n")
    api_fixtures = [
        ("a dead helper call plus a live stream read is caught", dead_else_handler,
         ["reads through a stream"], False),
        ("comment-only readNBytes mention + helper with RandomAccessFile is clean", ok_handler, [], True),
        ("live `.readNBytes(` is caught", live_handler, ["API 33+"], False),
        ("handleReadFile with neither RandomAccessFile nor the helper is caught", noraf_handler, ["neither uses"], False),
        ("a called-but-undefined helper is caught", missing_helper_handler, ["not defined"], False),
        ("a renamed handleReadFile is anchor drift", drifted_handler, ["anchor drift"], False),
    ]
    api_fixtures += [
        ("w4-when(false) + Files.readAllBytes off the helper path is caught",
         ok_handler.replace(
             "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n",
             "        val bytes = when (false) { true -> "
             "readFileSegment(java.io.File(\"x\"), 0L, 16) else -> "
             "java.nio.file.Files.readAllBytes(java.io.File(\"x\").toPath()) }\n"
             "        return bytes\n"),
         ["handleReadFile"], False),
        ("w4-helper result dropped by a `.let` chain is caught",
         ok_handler.replace(
             "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n",
             "        return readFileSegment(java.io.File(\"x\"), 0L, 16).let { "
             "java.io.File(\"x\").bufferedReader().readText().toByteArray() }\n"),
         ["handleReadFile"], False),
        ("w4-nested block comment + raw string cannot hide a stream read",
         ok_handler.replace(
             "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n",
             "        return readFileSegment(java.io.File(\"x\"), 0L, 16).let {\n"
             "            /* outer /* inner */ val _s = \"\"\" */\n"
             "            java.io.File(\"x\").inputStream().use { s -> s.skip(0L); "
             "s.readBytes() }\n"
             "            /* outer2 /* inner2 */ \"\"\" */\n"
             "        }\n"),
         ["reads through a stream"], False),
        ("w4-the read statement's value is exactly the bounded helper (control)",
         ok_handler.replace(
             "        return readFileSegment(java.io.File(\"x\"), 0L, 16)\n",
             "        val bytes = readFileSegment(java.io.File(\"x\"), 0L, 16)\n"
             "        return bytes\n"),
         [], True),
    ]
    api_ok = True
    for label, text, needles, want_clean in api_fixtures:
        d = _api_tree(text)
        got = scan_api26_reads(d)
        clean = got == []
        if want_clean:
            passed = clean
        else:
            passed = (not clean) and all(any(n in x for x in got) for n in needles)
        api_ok &= check("api26: %s" % label, passed, "got=%s" % got)
        shutil.rmtree(d, ignore_errors=True)
    d_none = tempfile.mkdtemp(prefix="debugleak-api26-none-")
    api_ok &= check(
        "api26: a tree without the handler is skipped, never failed silently",
        scan_api26_reads(d_none) == [],
    )
    shutil.rmtree(d_none, ignore_errors=True)
    ok &= api_ok
    print("   DEBUG_API26_SELFTEST=%s (%d fixtures)" % ("PASS" if api_ok else "FAIL", len(api_fixtures) + 1))

    return 0 if ok else 1


def _is_apk_arg(x):
    return x.lower().endswith(".apk") or os.path.isfile(x)


PASS = 0
FAIL = 0


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✅ {name}")
        return True
    FAIL += 1
    print(f"  ❌ {name}" + (f"\n     {detail}" if detail else ""))
    return False


def _resolve_root(args):
    """[verify-1015 BUG-03] Resolve the repo root from CLI args, or None when
    the argv form cannot name a tree that actually contains the source.

    Accepts both the documented `source <repo_root>` form and a bare
    `<repo_root>`. Returns None — the caller must fail CLOSED — when the root
    is missing or has no `src/android/app/src`: the old code turned a wrong
    root into an empty walk that printed "clean" and exited 0, which is how
    the gate stayed blind in CI (scan.sh passed the mode word as the root).
    """
    if not args:
        return None
    if args[0] == "source":
        if len(args) < 2:
            return None
        root = os.path.abspath(args[1])
    else:
        root = os.path.abspath(args[0])
    if not os.path.isdir(os.path.join(root, "src/android/app/src")):
        return None
    return root


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--self-test":
        return self_test()
    if args[0] == "apk" or (len(args) == 1 and _is_apk_arg(args[0])):
        apk = args[1] if len(args) >= 2 else args[0]
        present, dex_count = scan_apk(apk)
        if apk_verdict(present, dex_count) == 0:
            print(f"✅ Debug-leak guard (apk): {len(APK_MARKERS)} debug markers absent from {dex_count} dex entries")
            return 0
        if dex_count == 0:
            print("❌ Debug-leak guard (apk): NO dex entries found in the artifact — nothing was scanned!")
            print("   The gate fails CLOSED: a renamed / nested dex, or a truncated artifact,")
            print("   must not read as 'markers absent'. Check the build output layout, then")
            print("   extend dex_entries() if the change is intentional.")
            return 1
        print("❌ Debug-leak guard (apk): DEBUG-ONLY code found in the release APK!")
        print("   A -keep rule, minify disable, or new BuildConfig.DEBUG-less entry")
        print("   shipped debug code. The debug JSON-RPC server (127.0.0.1:5321) is")
        print("   token-free — this must not reach users.")
        for x in present:
            print("     " + x)
        return 1
    # [verify-1015 BUG-03] Two failure modes lived here:
    #   1. the documented `source <repo_root>` form (see the module docstring)
    #      was not implemented, so `... debug_leak_guard.py source "$ROOT"` —
    #      exactly what scan.sh called — took the literal string "source" as
    #      the root, walked a nonexistent directory, and printed "no unaudited
    #      debug-package references" with exit 0. The gate could not fail
    #      (verified 2026-10-15 by running both forms: the correct root
    #      immediately surfaced a real main-tree reference).
    #   2. a missing source tree was indistinguishable from a clean tree.
    # Both are closed by accepting the mode word AND failing CLOSED on a root
    # that does not contain the source tree.
    if args[0] == "source":
        if len(args) < 2:
            print("usage: debug_leak_guard.py source <repo_root>")
            return 2
    root = _resolve_root(args)
    if root is None:
        print("❌ Debug-leak guard (source): no source tree for the given argument(s)")
        print(f"   argv={args!r} — pass the repo root, or `source <repo_root>`.")
        print("   The gate fails CLOSED: a wrong or missing root must not read as")
        print("   'nothing to scan'.")
        return 1
    v = scan_source(root)
    api = scan_api26_reads(root)
    if v or api:
        if v:
            print(f"❌ Debug-leak guard (source): {len(v)} unaudited debug-package reference(s)")
            print("   debug/ lives in src/main; release hygiene depends on R8 DCE +")
            print("   BuildConfig.DEBUG guards at every call site. New references must")
            print("   be audited and added to ALLOWED_FILES — or justified inline with")
            print("   `debug-ok: <reason>`.")
            for x in v:
                print("     " + x)
        if api:
            print(f"❌ Debug-leak guard (source): {len(api)} minSdk-26 read-contract violation(s)")
            print("   minSdk is 26; `InputStream.readNBytes(int)` is API 33+ (NoSuchMethodError")
            print("   below that) and `skip()` may under-skip (reads from the wrong offset).")
            print("   Use RandomAccessFile.seek + a bounded read loop — the shape")
            print("   handleLogsRead already used before this contract was pinned.")
            for x in api:
                print("     " + x)
        return 1
    print("✅ Debug-leak guard (source): no unaudited debug-package references")
    print("✅ Debug-leak guard (source): debug read path is minSdk-26 safe")
    return 0
if __name__ == "__main__":
    sys.exit(main())
