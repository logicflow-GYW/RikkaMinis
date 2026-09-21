#!/usr/bin/env python3
"""Guard: every SharedPreferences change listener must be strongly held.

Why this exists (F-134): AOSP `SharedPreferencesImpl` stores listeners in

    private final WeakHashMap<OnSharedPreferenceChangeListener, Object> mListeners;

whose *value* is a static sentinel (`CONTENT`) that does NOT reference the
key. A listener created as a bare lambda **argument** therefore has no strong
reference anywhere: the registering frame returns, the GC collects the
listener, and the callback silently stops firing. It is never re-registered.

Nothing about that failure is observable — the write lands in prefs, the
registration call already returned success, and no exception is thrown. The
only symptom is "I set the knob and it kept using the old value", which is
indistinguishable from the value never having been read. That is a
silent-failure shape, so it gets a mechanical guard.

This is not hypothetical: ConfigBuiltins registered a bare lambda and
`minis-config set runtime.shellOutputKb 512` returned ok:true while every
reader kept serving the primed default (128 KB) until process restart.

Shape accepted: the listener is bound to a name (`val listener = ...`, or a
property) that is held by something process- or screen-scoped, and THAT name
is what gets registered. The guard checks the argument is an identifier, and
that the identifier is either a field/property of an enclosing class/object
(process- or instance-scoped) or a local that is also stored into a field.

Escape hatch: `prefs-listener-ok: <reason>` on the same or the previous line.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

REGISTER_RE = re.compile(r"\.registerOnSharedPreferenceChangeListener\b")
ESCAPE_RE = re.compile(r"prefs-listener-ok\s*:")

# A lambda / anonymous listener passed inline as the argument. Must cover BOTH
# call shapes, because the hazardous one is the trailing-lambda form:
#     prefs.registerOnSharedPreferenceChangeListener { _, _ -> ... }   (no parens)
#     prefs.registerOnSharedPreferenceChangeListener(Listener { _, _ -> })
# An earlier version of this guard required `\(` and therefore silently skipped
# the trailing-lambda form -- i.e. it reported OK on the exact line it exists to
# catch. Verified by running it against the pre-fix tree (must FAIL there).
INLINE_RE = re.compile(
    r"\.registerOnSharedPreferenceChangeListener\s*(?:\(\s*)?"
    r"(?:\{|[A-Za-z_][\w.]*\s*\{)"
)

problems = []
sites = []


def _strip_comments_and_strings(text: str) -> str:
    """Blank out // comments, /* */ blocks and string literals (brace-safe)."""
    out = []
    i, n = 0, len(text)
    state = None  # None | "line" | "block" | "str" | "raw"
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if state is None:
            if two == "//":
                state = "line"; out.append("  "); i += 2; continue
            if two == "/*":
                state = "block"; out.append("  "); i += 2; continue
            if c == '"':
                state = "str"; out.append(" "); i += 1; continue
            out.append(c); i += 1; continue
        if state == "line":
            if c == "\n":
                state = None; out.append("\n")
            else:
                out.append(" ")
            i += 1; continue
        if state == "block":
            if two == "*/":
                state = None; out.append("  "); i += 2; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
        if state == "str":
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == '"':
                state = None; out.append(" "); i += 1; continue
            out.append("\n" if c == "\n" else " "); i += 1; continue
    return "".join(out)


def field_declarations(text: str) -> set:
    """Names declared as class/object-level properties (NOT locals inside a fun).

    Needs a scope stack: a `val` inside a function body is a local, and a local
    that is never stored elsewhere is exactly as collectable as an inline lambda
    -- that is the second hazard this guard must catch. An earlier version
    treated every `val`/`var` as a field, so `val l = ...; register(l)` passed.

    Two separate buffers, because the two jobs need different lifetimes:

      * `pending` resets at every line break and feeds the declaration regex --
        a `val`/`var` declaration never spans lines in this codebase.
      * `head` resets only at a top-level `{`/`}`/`;` and classifies the scope a
        `{` opens. It MUST span lines, and it must receive a separator at each
        line break.

    Both of those `head` requirements are load-bearing, and both were violated:

      1. `head` used to reset at every newline. A multi-line parameter list puts
         the `{` on its own line, so it saw only `) ` and classified the body as
         a class.
      2. Even with (1) fixed, `head` was concatenated without a separator, so an
         annotation on its own line fused with the keyword: `@Composable\nfun`
         became `@Composablefun`, `\bfun\b` could not match, and the body was
         classified as a class again.

    Either way, every local `val listener` inside such a function was recorded as
    a *field*, so the guard reported `held` for four real sites (ChatScreen,
    AppearanceScreen, ChatMenuSettingsScreen, MemoryManagementScreen) whose only
    holder is a sibling `onDispose` -- and deleting that `onDispose` still
    passed. `paren` is tracked for the same reason: a brace nested in parentheses
    is a lambda argument (e.g. a default `= {}`), not a new statement head.
    Regression fixtures live in `test_scan.py`.
    """
    clean = _strip_comments_and_strings(text)
    fields = set()
    stack = []          # each entry: "fun" | "class" | "other"
    pending = ""        # since the last line break -- for declaration detection
    head = ""           # since the last TOP-LEVEL brace/semicolon -- scope classification
    paren = 0           # () nesting depth, so a default lambda `= {}` can't reset head
    decl_re = re.compile(r"(?:^|[;{}])\s*(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+|"
                         r"protected\s+|open\s+|override\s+|lateinit\s+|const\s+|@\w+\s+)*"
                         r"(val|var)\s+(\w+)")

    for ch in clean:
        if ch == "\n":
            pending = ""
            # `head` spans lines, so it needs a separator here. Without one the
            # annotation and the keyword fuse into a single token --
            # `@Composable\nfun` became `@Composablefun`, `\bfun\b` could not
            # match, and the function body was classified as a class. That is
            # what mislabelled the four real `DisposableEffect` sites.
            head += " "
            continue
        if ch == "(" or ch == ")":
            paren += 1 if ch == "(" else -1
            if paren < 0:
                paren = 0
            pending += ch
            head += ch
            continue
        if ch == "{":
            if re.search(r"\bfun\b", head):
                stack.append("fun")
            elif re.search(r"\b(class|object|interface)\b", head):
                stack.append("class")
            else:
                stack.append("other")
            pending = ""
            # A brace nested inside parentheses is a lambda argument (e.g. a
            # parameter default `= {}`), NOT a new statement head -- clearing
            # `head` there would erase the `fun` of a multi-line signature and
            # misclassify the whole function body as a class.
            if paren == 0:
                head = ""
            continue
        if ch == "}":
            if stack:
                stack.pop()
            pending = ""
            if paren == 0:
                head = ""
            continue
        if ch == ";":
            pending = ""
            if paren == 0:
                head = ""
            continue
        pending += ch
        head += ch
        # A declaration only counts as a field when no function body encloses it.
        m = decl_re.search(pending)
        if m and "fun" not in stack:
            fields.add(m.group(2))
    return fields


def ref_count(text: str, ident: str) -> int:
    """Occurrences of the identifier as a standalone token."""
    return len(re.findall(rf"\b{re.escape(ident)}\b", _strip_comments_and_strings(text)))


def stored_into_something(text: str, ident: str) -> bool:
    """`x = ident` / `val y = ident` -- the local escapes into another holder."""
    clean = _strip_comments_and_strings(text)
    return bool(re.search(rf"=\s*{re.escape(ident)}\s*(?:\n|$|\))", clean))


def inside_on_dispose(text: str, ident: str) -> bool:
    """True when `ident` is captured by an `onDispose { ... }` block.

    The Compose shape is a legitimate strong holder:

        DisposableEffect(prefs) {
            val listener = OnSharedPreferenceChangeListener { _, _ -> ... }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    `onDispose`'s lambda captures `listener`, and the effect framework holds
    that lambda until disposal -- so the listener stays reachable for exactly
    as long as it is registered.
    """
    clean = _strip_comments_and_strings(text)
    for m in re.finditer(r"\bonDispose\s*\{", clean):
        depth, i, n = 1, m.end(), len(clean)
        while i < n and depth:
            if clean[i] == "{":
                depth += 1
            elif clean[i] == "}":
                depth -= 1
            i += 1
        body = clean[m.end():i]
        if re.search(rf"\b{re.escape(ident)}\b", body):
            return True
    return False


for path in sorted(ROOT.rglob("*.kt")):
    sp = str(path)
    if "/build/" in sp or "/test/" in sp:
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    if "registerOnSharedPreferenceChangeListener" not in text:
        continue
    lines = text.split("\n")
    rel = path.relative_to(ROOT)
    fields = field_declarations(text)

    for i, ln in enumerate(lines):
        stripped = ln.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        if not REGISTER_RE.search(ln):
            continue
        # `override fun registerOnSharedPreferenceChangeListener(...) {}` is the
        # no-op stub in EncryptedPrefsFactory, not a call site.
        if re.search(r"\bfun\s+registerOnSharedPreferenceChangeListener\b", ln):
            continue
        # `unregisterOnSharedPreferenceChangeListener` contains the same suffix.
        if re.search(r"\bunregisterOnSharedPreferenceChangeListener\b", ln):
            continue

        ctx = "\n".join(lines[max(0, i - 2):i + 1])
        if ESCAPE_RE.search(ctx):
            sites.append((str(rel), i + 1, "escaped"))
            continue

        # Inline lambda argument?
        if INLINE_RE.search(ln):
            problems.append(
                f"{rel}:{i+1} registers an inline lambda with no strong holder -- "
                f"SharedPreferencesImpl holds listeners in a WeakHashMap, so the GC "
                f"collects it and the callback silently stops firing"
            )
            sites.append((str(rel), i + 1, "INLINE (hazard)"))
            continue

        # Otherwise: expect an identifier argument, and require it be held.
        m = re.search(r"\.registerOnSharedPreferenceChangeListener\s*\(\s*([\w.]+)", ln)
        if not m:
            problems.append(f"{rel}:{i+1} could not parse the listener argument")
            sites.append((str(rel), i + 1, "unparsed"))
            continue
        ident = m.group(1).split(".")[-1]
        if ident in fields:
            sites.append((str(rel), i + 1, f"held ({ident})"))
        elif ref_count(text, ident) >= 2 and stored_into_something(text, ident):
            # Declared as a local, but also assigned into another holder.
            sites.append((str(rel), i + 1, f"held via assignment ({ident})"))
        elif inside_on_dispose(text, ident):
            # Compose: the onDispose lambda captures it for the effect's life.
            sites.append((str(rel), i + 1, f"held via onDispose ({ident})"))
        else:
            problems.append(
                f"{rel}:{i+1} registers `{ident}` but nothing keeps it alive past "
                f"the registering frame (no field holds it) -- the GC collects it "
                f"and the callback silently stops firing"
            )
            sites.append((str(rel), i + 1, f"UNHELD ({ident})"))

print("SharedPreferences change-listener registrations:")
for rel, line, kind in sites:
    print(f"  {rel}:{line}  {kind}")

if not sites:
    problems.append(
        "found no registerOnSharedPreferenceChangeListener call sites at all -- "
        "the call may have been renamed; re-point this guard if so"
    )

if problems:
    print("\nFAIL: a prefs change listener may be garbage-collected")
    for p in problems:
        print(f"  - {p}")
    print("\n  Fix: bind the listener to a name and keep it in a field, e.g.")
    print("       private val prefsListener = OnSharedPreferenceChangeListener { _, _ -> ... }")
    print("       prefs.registerOnSharedPreferenceChangeListener(prefsListener)")
    print("  Or justify inline with `prefs-listener-ok: <reason>`.")
    sys.exit(1)

print(f"\nOK: all {len(sites)} prefs listener registration(s) strongly held")
