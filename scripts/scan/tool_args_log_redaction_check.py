#!/usr/bin/env python3
"""Tool-argument log redaction guard — RikkaMinis CI scan gate.

## Why this gate exists

`ToolCallComplete` carries the model's RAW tool arguments (`args`), which is the
single richest credential surface in the app: `shell_execute` with an `export`,
`file_write` of a `.env`, `curl -H 'Authorization: ...'`. Two providers and the
engine each log that JSON, truncated to 300 chars, and the truncation makes the
leak WORSE: a 300-char cut can slice a credential in half, and a half-token no
longer matches any redaction rule, so the fragment lands verbatim.

2026-11-23 (device verification of the tool-output-redaction release): a clean
synthetic canary placed ONLY inside a `file_write` `content` argument was found
in `/var/minis/logs/minis-*.log` as raw bytes, with zero placeholders on the
line. The release had wrapped the sibling `output=` expression with
`redactForDiagnostics` and left all four `args=` sites untouched — every other
gate was green, because they ask about shapes (keys match, mappings complete,
compiles) and this is a WIRING question: does the value handed to this log
statement pass through a redactor?

## What it flags

Every log call in the credential-bearing subtrees whose ARGUMENT list contains
an interpolation/concatenation that carries tool arguments, unless that
expression is wrapped by a redactor call. Recognised carriers:

  - `${args...}`, `${argsJson...}`, `${chunk.args...}`, `${acc.args...}`,
    `${toolInput...}`, `${inputBuffer...}` — a name mentioning args/input;
  - the same names reached through `.take(N)`, `.toString()`, `.length`, a
    plain `$name`, or a `+` concatenation.

An expression counts as SAFE when the interpolated text contains a redactor
call (`redactForDiagnostics` / `redactForModel` / `redactIfEnabled`) wrapping
it, or when the only member accessed is a COUNTER (`len`, `length`, `size`,
`count`) — a length is not a credential.

## Fail-closed

A log call whose parentheses never balance is REPORTED, not skipped: a
half-read argument list is exactly where a spliced argument would hide.

## Escape hatch

`args-log-ok: <reason>` on the line above the call.

Usage:
  python3 scripts/scan/tool_args_log_redaction_check.py <repo_root>
  python3 scripts/scan/tool_args_log_redaction_check.py --selftest
Exit: 0 = every args-bearing log call is redacted, 1 = violation (file:line printed)
"""

import os
import re
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from _kotlin_ast import match_bracket                       # noqa: E402
from _kotlin_lexer import KotlinLexError, mask_code         # noqa: E402

SRC_SUBDIR = "src/android/app/src/main/java"

# The credential-bearing subtrees. Tool arguments flow through the engine that
# streams them and the providers that parse them; nothing else logs raw args.
SCAN_DIRS = (
    "com/rikkaminis/app/ui/chat",
    "com/rikkaminis/app/provider",
    "com/rikkaminis/app/sandbox",
    "com/rikkaminis/app/tools",
)

LOG_CALL = re.compile(r"\b(?:AppLogger|Log|Timber)\.[A-Za-z]+\s*\(|\bprintln\s*\(")

# A name that *carries* tool arguments. Judged on camel tokens so `argsLen`
# (a counter) is separable from `argsJson` (the payload).
ARGS_TOKENS = frozenset(("args", "argsjson", "argjson", "arguments", "toolinput", "inputbuffer"))

REDACTOR = re.compile(r"redact|sanitiz|scrub", re.IGNORECASE)

# A member access that yields a COUNTER, not the payload. Covers both the
# property form (`.length`, `.size`, `.count`) and the call form (`.length()`,
# `.count()`), which `AnthropicProvider` uses for a field count.
COUNTER_TAIL = re.compile(r"\.(?:length|size|count)(?:\(\))?\s*$")

# `$name` / `${expr}` / `$name.member` interpolation inside a string literal.
INTERP = re.compile(r"\$\{([^{}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)")

IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
CAMEL = re.compile(r"[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+")

# A local binding whose right-hand side is a redactor call: `val safeArgs =
# ...redactForDiagnostics(x).text`. Judged by VALUE FLOW, not by the name —
# `safeArgs` still carries args tokens, so a name-based exemption would be a
# spelling rule that any rename defeats (the log_url_redaction_guard lesson,
# R3-t12). One hop is enough: these bindings are local and single-assignment
# in this codebase, and a re-assignment would show up as a second RHS.
SAFE_BINDING = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[^=\n]+)?=\s*([^\n]*)"
)

EXEMPT = "args-log-ok:"


def _safe_names(src):
    """Identifiers bound to a redactor result anywhere in this file."""
    out = set()
    for m in SAFE_BINDING.finditer(src):
        if REDACTOR.search(m.group(2)):
            out.add(m.group(1))
    return out


def _is_args_name(name):
    """True when an identifier names tool arguments (not a counter)."""
    tokens = [t.lower() for t in CAMEL.findall(name or "")]
    if not tokens:
        return False
    # `argsLen` / `argsCount` are counters, not payloads.
    if tokens[-1] in ("len", "length", "size", "count"):
        return False
    return any(t in ARGS_TOKENS for t in tokens)


def _expr_carries_args(expr, safe_names=()):
    """Does this interpolation expression read tool arguments?"""
    text = expr.strip()
    if not text:
        return False
    if COUNTER_TAIL.search(text):
        return False
    # `len=${argsJson.length}` — counter only.
    for m in IDENT.finditer(text):
        name = m.group(0)
        if not _is_args_name(name):
            continue
        # Value flow: a local bound to a redactor result is not the payload.
        if name in safe_names:
            continue
        return True
    return False


def _arg_text_is_redacted(text):
    """The whole expression is wrapped by a redactor call somewhere."""
    return bool(REDACTOR.search(text))


def _string_literal_body(masked, src, start):
    """Return (body_start, body_end, next_pos) for a string literal at `start`.

    `masked` blanks literal contents; the ORIGINAL `src` is used to read the
    interpolations back, because `mask_code` removes them too.
    """
    quote = src[start]
    i = start + 1
    while i < len(src):
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if c == quote:
            return start + 1, i, i + 1
        if c == "\n":
            # Unterminated single-line literal: treat to end of line.
            return start + 1, i, i
        i += 1
    return start + 1, len(src), len(src)


def scan_file(rel, src):
    """Return a list of (line, snippet) violations for one file."""
    try:
        masked = mask_code(src)
    except KotlinLexError:
        # Fail closed: a file the lexer cannot read is reported, never skipped.
        return [(1, "<unreadable: lexer error — cannot prove the log calls are safe>")]

    safe = _safe_names(src)
    out = []
    for m in LOG_CALL.finditer(masked):
        open_paren = masked.index("(", m.start())
        try:
            close = match_bracket(masked, open_paren)
        except Exception:
            line = masked.count("\n", 0, open_paren) + 1
            out.append((line, "<unbalanced log call — cannot prove its arguments are safe>"))
            continue
        # 1) Interpolations inside string literals.
        i = open_paren + 1
        while i < close:
            ch = src[i] if i < len(src) else ""
            if ch == '"' or ch == "'":
                b_start, b_end, nxt = _string_literal_body(masked, src, i)
                body = src[b_start:b_end]
                for im in INTERP.finditer(body):
                    expr = im.group(1) if im.group(1) is not None else im.group(2)
                    if _expr_carries_args(expr, safe) and not _arg_text_is_redacted(expr):
                        line = src.count("\n", 0, b_start + im.start()) + 1
                        out.append((line, body.strip()[:120]))
                i = nxt
                continue
            i += 1
        # 2) Bare code arguments — a concatenation or a plain argument that
        #    hands the payload straight to the log. `masked` has literal
        #    contents blanked, so every identifier here is real code.
        code_region = masked[open_paren + 1:close]
        for im in IDENT.finditer(code_region):
            name = im.group(0)
            if not _is_args_name(name) or name in safe:
                continue
            # The identifier must be a value read, not a property of the log
            # call itself (e.g. a tag name). Skip when immediately followed by
            # `(` — that is a function named like args, not a payload.
            after = code_region[im.end():im.end() + 1]
            if after == "(":
                continue
            pos = open_paren + 1 + im.start()
            window = src[max(0, pos - 200):pos + len(name) + 200]
            if REDACTOR.search(window):
                continue
            line = src.count("\n", 0, pos) + 1
            snippet = " ".join(src[max(0, pos - 40):pos + 60].split())
            out.append((line, snippet[:120]))
    return out


def _exempted(src, line):
    """`args-log-ok:` on this line or in the contiguous comment block above it.

    Scans upward through consecutive comment lines so a multi-line justification
    works — the marker is the LAST line of the block, directly above the call.
    """
    lines = src.split("\n")
    idx = line - 1                      # 1-based -> 0-based
    if 0 <= idx < len(lines) and EXEMPT in lines[idx]:
        return True
    j = idx - 1
    while j >= 0:
        text = lines[j].strip()
        if not (text.startswith("//") or text.startswith("/*") or text.startswith("*")):
            break
        if EXEMPT in lines[j]:
            return True
        j -= 1
    return False


def _iter_sources(root):
    base = os.path.join(root, SRC_SUBDIR)
    for d in SCAN_DIRS:
        walk = os.path.join(base, d)
        if not os.path.isdir(walk):
            continue
        for dirpath, _dirs, files in os.walk(walk):
            for f in files:
                if not f.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, f)
                yield path, os.path.relpath(path, root)


def run(root):
    findings = 0
    for path, rel in _iter_sources(root):
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                src = fh.read()
        except OSError as exc:
            print("UNREADABLE %s (%s) — cannot prove its log calls are safe" % (rel, exc))
            findings += 1
            continue
        for line, snippet in scan_file(rel, src):
            if _exempted(src, line):
                continue
            print("%s:%d  unredacted tool arguments in a log call: %s" % (rel, line, snippet))
            findings += 1
    if findings:
        print("")
        print("Tool arguments reach a log line without passing through a redactor.")
        print("Wrap the interpolated expression: "
              "args=${com.rikkaminis.app.data.EnvVarRedactor.redactForDiagnostics(x).text.take(N)}")
        print("Redact BEFORE the truncation — a cut can slice a credential in half.")
        print("If the line is genuinely safe, add `%s <reason>` above it." % EXEMPT)
        return 1
    print("OK: every tool-argument log call passes through a redactor")
    return 0


# ── self-test: the gate must CATCH a dirty fixture and PASS a clean one ──────

DIRTY = '''
class Fake {
    fun f(chunk: Chunk) {
        val argsJson = chunk.args.toString()
        android.util.Log.d("ToolChain[VM]", "ToolCallComplete name=${chunk.name} args=${argsJson.take(300)} len=${argsJson.length}")
    }
}
'''

DIRTY_CONCAT = '''
class Fake2 {
    fun f(args: String) {
        Log.d(TAG, "payload=" + args)
    }
}
'''

CLEAN = '''
class Fake3 {
    fun f(chunk: Chunk) {
        val argsJson = chunk.args.toString()
        val safeArgs = com.rikkaminis.app.data.EnvVarRedactor.redactForDiagnostics(argsJson).text
        android.util.Log.d("ToolChain[VM]", "ToolCallComplete name=${chunk.name} args=${safeArgs.take(300)} len=${argsJson.length}")
    }
}
'''

CLEAN_COUNTER = '''
class Fake4 {
    fun f(chunk: Chunk) {
        val argsJson = chunk.args.toString()
        Log.d(TAG, "len=${argsJson.length} size=${argsJson.size}")
    }
}
'''

EXEMPTED = '''
class Fake5 {
    fun f(chunk: Chunk) {
        // args-log-ok: arguments are a public schema, audited 2026-11-23
        Log.d(TAG, "args=${chunk.args}")
    }
}
'''


def selftest():
    failures = 0

    def check(name, cond, detail=""):
        nonlocal failures
        if cond:
            print("  PASS %s" % name)
        else:
            print("  FAIL %s %s" % (name, detail))
            failures += 1

    def hits(src):
        return scan_file("fixture.kt", src)

    check("dirty fixture is caught (take(300) on args)", len(hits(DIRTY)) >= 1,
          "got %d findings" % len(hits(DIRTY)))
    check("dirty concat fixture is caught", len(hits(DIRTY_CONCAT)) >= 1,
          "got %d findings" % len(hits(DIRTY_CONCAT)))
    check("clean redacted fixture passes", hits(CLEAN) == [], "got %r" % (hits(CLEAN),))
    check("counter-only fixture passes", hits(CLEAN_COUNTER) == [], "got %r" % (hits(CLEAN_COUNTER),))

    # The exemption marker is applied by run(), not scan_file(); prove the
    # marker itself is recognised so the escape hatch is not silently dead.
    check("inline exemption is recognised", _exempted(EXEMPTED, 4) or _exempted(EXEMPTED, 5),
          "marker not seen on the fixture line")

    # Negative control: the dirty fixture MUST become clean once the redactor is
    # inserted — otherwise the gate would be flagging the file, not the leak.
    fixed = DIRTY.replace("args=${argsJson.take(300)}",
                          "args=${com.rikkaminis.app.data.EnvVarRedactor.redactForDiagnostics(argsJson).text.take(300)}")
    check("dirty fixture becomes clean once redacted", hits(fixed) == [], "got %r" % (hits(fixed),))

    if failures:
        print("SELFTEST=FAIL (%d)" % failures)
        return 1
    print("SELFTEST=PASS")
    return 0


def main(argv):
    args = [a for a in argv[1:]]
    if "--selftest" in args:
        return selftest()
    root = args[0] if args else os.getcwd()
    return run(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
