#!/usr/bin/env python3
"""Thinking-level wiring guard — RikkaMinis CI scan gate.

## Why this gate exists

2026-09-22, for real, on branch `fix/thinking-level-ui-truth` (ae5517cc). That
branch fixed four thinking-level defects by extracting ONE rule
(`effectiveThinkingLevel`) and deriving every consumer from it — a genuinely
good shape. Then it split the composer picker into two parameters:

    current   -> drives the capsule highlight
    requested -> drives the orange up-arrow ("your setting is capped here")

...and wired `requested` to `viewModel.thinkingLevel`, which the SAME branch had
just redefined to BE the effective level. So `requested == current`, the
`isCappedBy(requested, …)` test was trivially false, and the up-arrow — the one
affordance that explains "your Max is capped at High by this model" — became
unreachable. Reachable combinations went 15 -> 0 with no exception, no log line,
and no failing test.

The branch's own KDoc warned about this exact mistake ("It cannot be derived
from [current] … the cue would never appear"). Documentation is not a gate.

## Why the unit tests cannot catch it

`ThinkingLevelPicker` is `@Composable internal`. The Compose call site is not
compilable in the sandbox JVM harness, and the branch's 13 tests all exercise
the pure rule, not the wiring. A pure function can be perfectly correct while
its only caller feeds it the wrong argument. That gap is what this gate closes:
it reads the call site as TEXT and checks the argument's origin.

## What it checks

1. Call-site provenance — in every `ThinkingLevelPicker(` call, the `requested`
   argument must NOT be an effective-level expression (`viewModel.thinkingLevel`,
   `.effectiveThinkingLevel`, or `effectiveThinkingLevel`). It must be a raw
   source (`requestedThinkingLevel`, `_thinkingLevel`, …).
2. The `current` argument must be the effective one, so the two cannot be
   silently collapsed back into a single value.
3. Inside the picker body, the clamp predicate must take `requested`, and the
   tap rule must key off `requested` (not the highlight `isHighlighted`).

Escape hatch: `thinking-wiring-ok: <reason>` on the same or the previous line.

Usage: python3 scripts/scan/thinking_wiring_guard.py <repo_root>
Exit:  0 = clean, 1 = violation(s) found
"""
import os
import re
import sys

LINE_COMMENT_RE = re.compile(r"//.*$")
ESCAPE_RE = re.compile(r"thinking-wiring-ok\s*:")

# Expressions that denote the EFFECTIVE level (capability + ceiling folded in).
EFFECTIVE_EXPR_RE = re.compile(
    r"viewModel\.thinkingLevel\b|\.effectiveThinkingLevel\b|\beffectiveThinkingLevel\s*\("
)

# Local bindings, resolved by provenance rather than by name shape. A gate that
# only pattern-matches the argument's spelling is defeated by a rename — which
# is exactly how the first version of THIS gate went green on the real bug
# (the caller's local was named `effectiveThinkingLevelState`, so no pattern
# fired). Track what each `val` was bound FROM.
BINDING_RE = re.compile(
    r"\bval\s+([A-Za-z_]\w*)\s*(?::\s*[\w<>?., ]+)?\s*(?:=|by)\s*([^\n]+)"
)
EFFECTIVE_SOURCE_RE = re.compile(
    r"viewModel\.thinkingLevel\b|\beffectiveThinkingLevel\b"
)
RAW_SOURCE_RE = re.compile(
    r"viewModel\.requestedThinkingLevel\b|\b_?thinkingLevel\b"
)

PICKER_SIG = "internal fun ThinkingLevelPicker("


def strip_comments(text: str) -> str:
    """Drop // line comments so a commented-out line never satisfies a check."""
    out = []
    for line in text.splitlines():
        out.append(LINE_COMMENT_RE.sub("", line))
    return "\n".join(out)


def binding_map(src: str):
    """name -> the expression it was bound from (last assignment wins)."""
    m = {}
    for name, expr in BINDING_RE.findall(src):
        m[name] = expr.strip()
    return m


def resolve_provenance(expr: str, bindings, depth: int = 0) -> str:
    """Follow a local `val` chain down to its ultimate source expression.

    Without this the gate is a spelling check: rename the caller's local to
    `effectiveThinkingLevelState` and the argument looks innocent while still
    carrying the effective level (verified — the first version went green on
    the exact bug it exists to catch).
    """
    if depth > 8:
        return expr
    expr = expr.strip()
    if not re.fullmatch(r"[A-Za-z_]\w*", expr):
        return expr
    src = bindings.get(expr)
    if src is None:
        return expr
    return resolve_provenance(src, bindings, depth + 1)


def split_top_level_args(arglist: str):
    """Split a Kotlin argument list on top-level commas (depth-aware)."""
    args, depth, cur = [], 0, ""
    for ch in arglist:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        args.append(cur)
    return args


def find_call_args(src: str, call: str):
    """Yield the argument-list text of every `call(` in src, depth-aware."""
    idx = 0
    while True:
        i = src.find(call, idx)
        if i < 0:
            return
        j = i + len(call)
        depth = 1
        while j < len(src) and depth:
            if src[j] == "(":
                depth += 1
            elif src[j] == ")":
                depth -= 1
            j += 1
        yield i, src[i + len(call):j - 1]
        idx = j


def raw_call_span(raw: str, anchor: int, call: str) -> str:
    """The call's source span taken from the ORIGINAL text.

    `find_call_args` works on comment-stripped source, so its arglist length is
    shorter than the real text — using it to slice `raw` for an inline escape
    comment falls short of the marker. Walk the parens on `raw` instead.
    """
    i = raw.find(call, anchor)
    if i < 0:
        return ""
    j = i + len(call)
    depth = 1
    while j < len(raw) and depth:
        if raw[j] == "(":
            depth += 1
        elif raw[j] == ")":
            depth -= 1
        j += 1
    return raw[i:j]


def find_function_body(src: str, signature: str):
    """Return the body text of the function starting at `signature`."""
    i = src.find(signature)
    if i < 0:
        return None, None
    depth = 0
    started = False
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
            started = True
        elif src[j] == "}":
            depth -= 1
            if started and depth == 0:
                return src[i:j + 1], i
    return None, None


def main():
    if len(sys.argv) < 2:
        print("usage: thinking_wiring_guard.py <repo_root>")
        return 2
    root = sys.argv[1]
    kt_root = os.path.join(root, "src/android/app/src/main/java")
    violations = []
    checked_calls = 0

    for dirpath, _, filenames in os.walk(kt_root):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8") as f:
                raw = f.read()
            src = strip_comments(raw)
            bindings = binding_map(src)
            lines = raw.splitlines()

            def escaped(offset: int, span_text: str | None = None) -> bool:
                """`thinking-wiring-ok:` on this line, the one above, or anywhere
                inside the annotated call/function span."""
                if span_text and ESCAPE_RE.search(span_text):
                    return True
                ln = src[:offset].count("\n")
                for k in (ln, ln - 1):
                    if 0 <= k < len(lines) and ESCAPE_RE.search(lines[k]):
                        return True
                return False

            # ── 1 & 2: call-site provenance ──────────────────────────────
            for offset, arglist in find_call_args(src, "ThinkingLevelPicker("):
                # Skip the declaration itself (its line reads `fun ThinkingLevelPicker(`).
                line_start = src.rfind("\n", 0, offset) + 1
                if "fun ThinkingLevelPicker" in src[line_start:offset + 30]:
                    continue
                # Count BEFORE the escape check: the rot guard below asks "is the
                # picker still wired anywhere?", which an escape hatch must not
                # answer. (Counting after it made an escaped call look like a
                # deleted picker.)
                checked_calls += 1
                # The escape hatch may sit on the offending argument's own line,
                # which is well below the call's opening paren — so scan the whole
                # call span, taken from the ORIGINAL text (see raw_call_span).
                call_span = raw_call_span(raw, offset, "ThinkingLevelPicker(")
                if escaped(offset, call_span):
                    continue
                named = {}
                for a in split_top_level_args(arglist):
                    if "=" in a:
                        k, v = a.split("=", 1)
                        named[k.strip()] = v.strip()

                req = named.get("requested")
                cur = named.get("current")
                ln = src[:offset].count("\n") + 1
                # Resolve through local `val` bindings — see resolve_provenance.
                req_src = resolve_provenance(req, bindings) if req else ""
                cur_src = resolve_provenance(cur, bindings) if cur else ""
                if req is None:
                    violations.append(
                        f"{rel}:{ln}: ThinkingLevelPicker call has no `requested` argument"
                    )
                elif EFFECTIVE_SOURCE_RE.search(req_src):
                    violations.append(
                        f"{rel}:{ln}: `requested` resolves to the EFFECTIVE level "
                        f"({req!r} <- {req_src!r}). isCappedBy() then compares the "
                        "level against itself, so the orange up-arrow is unreachable "
                        "(15 -> 0 of 64 combos, silently). Feed the RAW choice "
                        "(viewModel.requestedThinkingLevel)."
                    )
                elif not RAW_SOURCE_RE.search(req_src):
                    violations.append(
                        f"{rel}:{ln}: `requested` ({req!r} <- {req_src!r}) does not "
                        "resolve to a raw stored thinking level; the up-arrow has no "
                        "honest source."
                    )
                if cur is not None and not EFFECTIVE_SOURCE_RE.search(cur_src):
                    violations.append(
                        f"{rel}:{ln}: `current` ({cur!r} <- {cur_src!r}) does not "
                        "resolve to the effective level; the highlight must track "
                        "what this turn actually sends."
                    )

            # ── 3: picker body predicates ────────────────────────────────
            if fn == "ChatComposerWidgets.kt":
                body, at = find_function_body(src, PICKER_SIG)
                if body is not None and not escaped(at):
                    m = re.search(r"isCappedBy\(\s*([A-Za-z_][\w]*)\s*,", body)
                    if not m:
                        violations.append(
                            f"{rel}: ThinkingLevelPicker no longer calls "
                            "isCappedBy(<requested>, …) — the clamp cue was removed "
                            "or rewritten; re-justify it or add thinking-wiring-ok."
                        )
                    elif m.group(1) != "requested":
                        violations.append(
                            f"{rel}: ThinkingLevelPicker passes `{m.group(1)}` to "
                            "isCappedBy, not `requested` — the cue goes dead when "
                            "that value is the effective level."
                        )
                    t = re.search(r"thinkingTapTarget\(\s*[A-Za-z_][\w]*\s*,\s*([A-Za-z_][\w]*)", body)
                    if not t:
                        violations.append(
                            f"{rel}: ThinkingLevelPicker no longer routes taps through "
                            "thinkingTapTarget(level, <requested>)."
                        )
                    elif t.group(1) != "requested":
                        violations.append(
                            f"{rel}: thinkingTapTarget is keyed off `{t.group(1)}`, not "
                            "`requested` — a tap on the capped ceiling capsule then "
                            "turns thinking OFF instead of selecting it."
                        )

    if checked_calls == 0:
        print("⚠️  no ThinkingLevelPicker call sites found — is the picker still there?")
        print("    (gate would silently pass; failing instead so it can't rot)")
        return 1

    if violations:
        print(f"❌ thinking-level wiring: {len(violations)} violation(s)")
        for v in violations:
            print(f"   • {v}")
        return 1
    print(f"✅ thinking-level wiring OK ({checked_calls} call site(s) checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
