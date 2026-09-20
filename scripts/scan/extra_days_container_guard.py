#!/usr/bin/env python3
"""Guard: every `AlarmClock.EXTRA_DAYS` writer must pass a java.util.ArrayList.

Why this exists: `EXTRA_DAYS` is specified as `ArrayList<Integer>`, and
`BaseBundle.getArrayList` resolves the value with `ArrayList.class.cast(...)`.
Anything else — an `int[]`, or Kotlin's `listOf()` which returns
`java.util.Arrays$ArrayList` — makes the cast throw, the exception is caught
inside the framework, a typeWarning is logged and **null** is returned to the
clock app. The alarm then silently replays as a one-shot: no crash, no test
failure, no log the user would ever see. That is a silent-failure shape, so it
gets a mechanical guard rather than a code-review note.

The sibling-writer problem this catches: the repo has TWO ACTION_SET_ALARM
writers (MinisApp.migrateGhostAlarms and sandbox/offload/AlarmOffloadHandler)
and they drifted apart once already.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

# Files that put a value under EXTRA_DAYS, and how they do it.
problems = []
writers = []

for path in sorted(ROOT.rglob("*.kt")):
    if "/build/" in str(path) or "/test/" in str(path):
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    if "EXTRA_DAYS" not in text:
        continue
    lines = text.split("\n")
    for i, ln in enumerate(lines):
        if "EXTRA_DAYS" not in ln or ln.strip().startswith("//") or ln.strip().startswith("*"):
            continue
        # Look at a window spanning a couple of lines either side: the value
        # expression may sit above the putExtra call (`fn(x)?.let { putExtra(k, it) }`).
        window = "\n".join(lines[max(0, i - 3):i + 4])
        rel = path.relative_to(ROOT)
        if re.search(r"arrayListOf\s*\(", window):
            writers.append((str(rel), i + 1, "ArrayList"))
        elif re.search(r"intArrayOf\s*\(", window):
            writers.append((str(rel), i + 1, "IntArray"))
            problems.append(f"{rel}:{i+1} passes intArrayOf (parcels as int[])")
        elif re.search(r"\blistOf\s*\(", window):
            writers.append((str(rel), i + 1, "Arrays$ArrayList"))
            problems.append(f"{rel}:{i+1} passes listOf() (java.util.Arrays$ArrayList)")
        else:
            # Value is an indirection (typically `?.let { putExtra(..., it) }`).
            # Trace it back to the producing function's declared return type in
            # this same file -- otherwise the guard would silently bless any
            # shape it does not recognise, which is the failure mode it exists
            # to prevent.
            m = re.search(r"\b(\w+)\s*\([^)]*\)\s*\?\.\s*(?:let|also)\s*\{", window)
            traced = None
            if m:
                fn = m.group(1)
                fm = re.search(
                    rf"fun\s+{re.escape(fn)}\s*\([^)]*\)\s*:\s*([\w<>?., ]+)", text
                )
                if fm:
                    ret = fm.group(1).strip()
                    if "ArrayList" in ret:
                        traced = "ArrayList"
                    elif "IntArray" in ret:
                        traced = "IntArray"
                        problems.append(
                            f"{rel}:{i+1} value traced to {fn}() -> {ret} "
                            f"(parcels as int[], not ArrayList<Integer>)"
                        )
                    elif re.search(r"\bList\b", ret):
                        traced = f"{ret} (not java.util.ArrayList)"
                        problems.append(
                            f"{rel}:{i+1} value traced to {fn}() -> {ret} "
                            f"-- a List is not necessarily java.util.ArrayList "
                            f"(listOf() returns Arrays$ArrayList)"
                        )
            if traced:
                writers.append((str(rel), i + 1, f"{traced} via {m.group(1)}()"))
            else:
                writers.append((str(rel), i + 1, "unknown"))
                problems.append(
                    f"{rel}:{i+1} could not determine the container for "
                    f"EXTRA_DAYS -- guard cannot verify it"
                )

print("EXTRA_DAYS writers found:")
for rel, line, kind in writers:
    print(f"  {rel}:{line}  container={kind}")

if len(writers) < 2:
    problems.append(
        f"expected at least 2 EXTRA_DAYS writers (MinisApp + AlarmOffloadHandler), "
        f"found {len(writers)} -- a writer may have been removed or renamed; "
        f"re-point this guard if so"
    )

if problems:
    print("\nFAIL: EXTRA_DAYS container type is not ArrayList<Integer>")
    for p in problems:
        print(f"  - {p}")
    sys.exit(1)

print("\nOK: every EXTRA_DAYS writer passes a java.util.ArrayList")
