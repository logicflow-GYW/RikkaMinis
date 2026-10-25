#!/usr/bin/env python3
"""
i18n consistency check — detect orphan resource keys, missing translations and
resource wiring that Android itself never reports at build time.

Checks:
1. Orphan keys: referenced in code (R.string.xxx) but not defined in any
   values/strings*.xml → HARD FAIL (exit 1). These are real bugs: they fail
   at runtime when the resource lookup happens.
2. Missing translations: keys defined in values/ missing from a locale file
   → WARNING only (known legacy from upstream — de/ja/ko/ru/zh-rTW were never
   fully translated; hard-failing on them would block every build).
3. [deep-audit-1034 B-02] Locale registration ↔ shipped resources: every
   `<locale android:name="…"/>` in res/xml/locales_config.xml must resolve to
   a `values-*` folder that actually ships strings*.xml → HARD FAIL. This is
   what makes the system per-app language picker honest: `zh-TW` shipped 1568
   translated keys with no registration (unreachable), `fr` was registered with
   no `values-fr/` at all (the picker offered French, the app rendered English).
   Neither shows up in a Gradle build or in the app's own UI.
4. [deep-audit-1034 B-02] Language-change wiring: the `KEY_LANGUAGE` arm of
   AppearanceScreen's preference listener must apply the language through the
   shared `applyLanguageChange(…)` function, not merely update Compose state.
   A write from outside the screen (backup restore, `minis-config set
   appearance.language`, WebDAV restore) otherwise leaves the process in the
   old language until the user re-picks a row → HARD FAIL.

Criteria 3 and 4 only judge trees where their anchor file exists, but they are
fail-closed inside it: a locales_config.xml with no <locale> entry, or an
AppearanceScreen.kt with no `KEY_LANGUAGE ->` arm, is a ROT failure (exit 1),
never a silent skip.

Why the union over strings*.xml: Android has always allowed splitting
resources across several files in one res folder, and this repo uses it on
purpose — values/strings_db_guard.xml keeps the database-downgrade guidance
strings out of the shared strings.xml so that feature does not have to touch
it. A scanner that only reads values/strings.xml reports those keys as
orphans and fails the build for resources that exist.

Usage: python3 scripts/scan/i18n_check.py <repo_root>
       python3 scripts/scan/i18n_check.py --selftest
Exit code: 0 = clean, 1 = orphan keys found / wiring broken (or no strings file
at all)
"""
import os
import re
import sys
import tempfile

KEY_RE = re.compile(r'name="([a-z0-9_]+)"')
CODE_KEY_RE = re.compile(r'R\.string\.([a-z0-9_]+)')
STRINGS_GLOB = (lambda fn: fn.startswith("strings") and fn.endswith(".xml"))

RES_DIR = "src/android/app/src/main/res"
LOCALES_CONFIG = RES_DIR + "/xml/locales_config.xml"
APPEARANCE_SCREEN = ("src/android/app/src/main/java/com/rikkaminis/app/"
                     "ui/settings/AppearanceScreen.kt")
APPLY_FUNCTION = "applyLanguageChange("
LISTENER_ARM = "KEY_LANGUAGE ->"

SELFTEST_MARKER = "I18N_CHECK_SELFTEST=PASS"

_XML_COMMENT_RE = re.compile(r"<!--.*?-->", re.S)


def strip_xml_comments(text):
    """Blank XML comments, preserving line structure."""
    return _XML_COMMENT_RE.sub(lambda m: "\n" * m.group(0).count("\n"), text)


def mask_kt_comments(text):
    """Blank //-comments and /* */ blocks; newlines and offsets preserved.

    Line structure has to survive: the arm body is searched with offsets (a
    dead-guard span is compared against a call offset), and a commented-out call
    must not count as the call (wide3 witness C4 parser-death)."""
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
            if c == '"':
                state = "str"; out.append(" "); i += 1; continue
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
        if state == "str":
            if c == "\\":
                out.append("  "); i += 2; continue
            if c == '"':
                state = None
            out.append(" "); i += 1; continue
    return "".join(out)


def _paren_close(text, open_idx):
    """Index of the `)` matching the `(` at open_idx, or -1."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _brace_close(text, open_idx):
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def _condition_is_dead(cond):
    """True when the condition can only ever be false.

    The shapes that matter are the ones a reviewer skims past: `false`,
    `false && …`, `… && false`, `true || …`, `… || true`.
    """
    parts = [p.strip() for p in re.split(r"&&|\|\|", cond)]
    if cond.strip() == "false":
        return True
    if "&&" in cond and "false" in parts:
        return True
    if "||" in cond and "true" in parts:
        return True
    return False


def dead_guard_spans(clean):
    """[(start, end)] spans of code guarded by a literal-dead condition."""
    spans = []
    for m in re.finditer(r"\b(if|while)\s*\(", clean):
        open_idx = clean.index("(", m.start())
        close = _paren_close(clean, open_idx)
        if close < 0 or not _condition_is_dead(clean[open_idx + 1:close]):
            continue
        body = close + 1
        while body < len(clean) and clean[body] in " \t\r\n":
            body += 1
        if body < len(clean) and clean[body] == "{":
            spans.append((body, _brace_close(clean, body)))
        else:
            eol = clean.find("\n", body)
            spans.append((body, len(clean) - 1 if eol < 0 else eol))
    return spans


def keys_defined_in(folder):
    """Union of the keys defined by every strings*.xml in one res folder.

    Commented-out resources are NOT definitions: `<!-- <string name="x">…</string>
    -->` leaves the key referenced in code and undefined at runtime, which is the
    exact defect this phase exists to catch (wide3 witness C1 orphan-keys
    parser-death)."""
    keys = set()
    if not os.path.isdir(folder):
        return keys
    for fn in sorted(os.listdir(folder)):
        if not STRINGS_GLOB(fn):
            continue
        with open(os.path.join(folder, fn), encoding="utf-8") as f:
            keys.update(KEY_RE.findall(strip_xml_comments(f.read())))
    return keys


def defined_files(folder):
    return [fn for fn in sorted(os.listdir(folder)) if STRINGS_GLOB(fn)] \
        if os.path.isdir(folder) else []


def shipped_strings_files(res_dir, folder):
    """strings*.xml shipped by one values-* folder ([] when it does not ship any)."""
    path = os.path.join(res_dir, folder)
    return defined_files(path)


def locale_tag_to_folder(tag):
    """BCP-47 tag → the values-* folder Android resolves it to.

    `en` is the default folder because res/values/ IS the English set; anything
    else keeps its language and gains the region qualifier (`zh-TW` →
    `values-zh-rTW`), which is how this repo already names its folders.
    """
    parts = tag.split("-")
    if len(parts) == 1:
        return "values" if parts[0] == "en" else "values-" + parts[0]
    return "values-" + parts[0] + "-r" + "-r".join(parts[1:])


def declared_locales(root):
    """[(tag, line)] from res/xml/locales_config.xml; [] when the file is absent."""
    path = os.path.join(root, LOCALES_CONFIG)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        text = strip_xml_comments(f.read())
    out = []
    for line in text.split("\n"):
        if "<locale" not in line:
            continue
        m = re.search(r'android:name="([^"]+)"', line)
        if m:
            out.append(m.group(1))
    return out


def check_locale_registration(root):
    """Criterion 3. Returns the number of problems found."""
    tags = declared_locales(root)
    if tags is None:
        print(f"   (no {LOCALES_CONFIG} in this tree — locale registration not checked)")
        return 0
    if not tags:
        print(f"❌ {LOCALES_CONFIG} declares no <locale> entry at all — the "
              f"registration criterion cannot be evaluated (ROT: fail-closed)")
        return 1
    res_dir = os.path.join(root, RES_DIR)
    problems = 0
    for tag in tags:
        folder = locale_tag_to_folder(tag)
        if not shipped_strings_files(res_dir, folder):
            print(f"❌ locale '{tag}' is registered but {folder}/ ships no "
                  f"strings*.xml — the system per-app language picker will offer "
                  f"a language that renders as the default")
            problems += 1
    if not problems:
        print(f"✅ every registered locale ships strings behind it "
              f"({len(tags)}: {', '.join(tags)})")
    return problems


def listener_arm_body(path):
    """Source of the `KEY_LANGUAGE ->` arm, or None when the arm is gone.

    Ends at the first line that is back at (or above) the arm's own indent, or
    that closes it (`}` at the arm's indent). Both halves matter: a one-line arm
    (`KEY_LANGUAGE -> selectedLanguage = …`) never opens a block, so without the
    dedent rule its "body" would run on into the rest of the file and find the
    apply function's own declaration line — a silent false green.
    """
    with open(path, encoding="utf-8") as f:
        text = f.read()
    lines = text.split("\n")
    start = next((i for i, l in enumerate(lines) if LISTENER_ARM in l), None)
    if start is None:
        return None
    head = lines[start]
    arm_indent = len(head) - len(head.lstrip())
    indent = " " * arm_indent
    rest = head.split("->", 1)[1].strip() if "->" in head else ""
    if rest and rest != "{" and not rest.startswith("//"):
        # A one-line arm is the whole arm: without this, the dedent rule below
        # keeps reading the SIBLING arms, and a single-line
        # `KEY_LANGUAGE -> println(…)` inherits the next arm's apply call.
        return head
    body = [head]
    for line in lines[start + 1:]:
        if line.strip():
            cur = len(line) - len(line.lstrip())
            if cur < arm_indent or line.rstrip() == indent + "}":
                body.append(line)
                break
        body.append(line)
    return "\n".join(body)


def check_language_wiring(root):
    """Criterion 4. Returns the number of problems found."""
    path = os.path.join(root, APPEARANCE_SCREEN)
    if not os.path.exists(path):
        print(f"   (no {APPEARANCE_SCREEN} in this tree — listener wiring not checked)")
        return 0
    body = listener_arm_body(path)
    if body is None:
        print(f"❌ {APPEARANCE_SCREEN} has no `{LISTENER_ARM}` arm — nothing would "
              f"react to a stored language change (ROT: fail-closed)")
        return 1
    # The arm must REACH the shared function, not merely mention it: a call
    # behind `if (false)` moves nothing, and a commented-out call never runs
    # (wide3 witness C4 — dataflow and parser-death variants of the same arm).
    clean = mask_kt_comments(body)
    dead = dead_guard_spans(clean)
    mentions = [m.start() for m in re.finditer(re.escape(APPLY_FUNCTION), clean)]
    live = [at for at in mentions if not any(s <= at <= e for s, e in dead)]
    if not live:
        if mentions:
            print(f"❌ the `{LISTENER_ARM}` arm only reaches {APPLY_FUNCTION}…) from "
                  f"dead or commented-out code — an external write to the language "
                  f"preference would move the UI only, leaving the process in the "
                  f"previous language")
        else:
            print(f"❌ the `{LISTENER_ARM}` arm does not call {APPLY_FUNCTION}…) — an "
                  f"external write to the language preference would move the UI only, "
                  f"leaving the process in the previous language")
        return 1
    print("✅ the language listener applies the change through the shared function")
    return 0


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = 0

    # --- Phase 1: Orphan keys (code references but no definition) --- HARD FAIL
    values_dir = os.path.join(root, RES_DIR, "values")
    if not defined_files(values_dir):
        print("!! no strings*.xml found at", values_dir)
        return 1

    defined_keys = keys_defined_in(values_dir)
    files = defined_files(values_dir)
    print(f"   default locales read from: {', '.join(files)}")

    code_keys = set()
    src_dir = os.path.join(root, "src/android/app/src/main")
    for dirpath, _, filenames in os.walk(src_dir):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8") as f:
                code_keys.update(CODE_KEY_RE.findall(f.read()))

    orphans = code_keys - defined_keys
    if orphans:
        problems += 1
        print(f"❌ Orphan string keys (referenced in code but NOT in values/strings*.xml):")
        for k in sorted(orphans):
            print(f"   R.string.{k}")
    else:
        print(f"✅ No orphan string keys ({len(code_keys)} code refs, {len(defined_keys)} definitions)")

    # --- Phase 2: Missing translations --- WARNING ONLY
    res_dir = os.path.join(root, RES_DIR)
    locale_dirs = sorted(d for d in os.listdir(res_dir) if d.startswith("values-") and d != "values")
    for loc in locale_dirs:
        loc_folder = os.path.join(res_dir, loc)
        if not defined_files(loc_folder):
            continue  # no strings file in this locale dir at all (e.g. values-night)
        loc_keys = keys_defined_in(loc_folder)
        missing = (defined_keys - loc_keys) & code_keys
        if missing:
            print(f"⚠️  {loc}: {len(missing)} active keys missing translation (WARNING)")

    # --- Phase 3: locale registration ↔ shipped resources --- HARD FAIL
    problems += check_locale_registration(root)

    # --- Phase 4: language-change wiring --- HARD FAIL
    problems += check_language_wiring(root)

    print(f"\n{'=' * 60}")
    # Label changed from "ORPHAN KEYS FOUND" when criteria 3/4 were added: the
    # counter now spans all four phases, so naming only orphans under-reported
    # what tripped the gate. (Same wording as four_way_sync_check.py.)
    print(f"Done: {'PROBLEMS FOUND: ' + str(problems) if problems else 'ALL CLEAN ✅'}")
    return 1 if problems else 0


# ─────────────────────────────────────────────────────────────────────────────
# selftest — fixtures for criteria 3 / 4 (criteria 1 / 2 are covered by the
# external fixtures in scripts/scan/test_scan.py, which are frozen).
# ─────────────────────────────────────────────────────────────────────────────

STRINGS = ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
           '    <string name="app_name">Minis</string>\n</resources>\n')

CLEAN_KT = (
    "package com.rikkaminis.app\n"
    "val title = R.string.app_name\n"
)


def _appearance_screen(arm):
    return (
        "package com.rikkaminis.app.ui.settings\n\n"
        "private const val KEY_LANGUAGE = \"app_language\"\n"
        "internal fun applyLanguageChange(code: String) { println(code) }\n\n"
        "fun AppearanceScreen() {\n"
        "    val prefs = 1\n"
        "    val listener = { key: String ->\n"
        "        when (key) {\n"
        "            \"other\" -> println(key)\n"
        + arm +
        "        }\n"
        "    }\n"
        "}\n"
    )


ARM_WIRED = (
    "            KEY_LANGUAGE -> {\n"
    "                val code = \"\"\n"
    "                applyLanguageChange(code)\n"
    "            }\n"
)
ARM_STATE_ONLY = (
    "            KEY_LANGUAGE -> {\n"
    "                println(\"state only\")\n"
    "            }\n"
)
ARM_ONE_LINER = "            KEY_LANGUAGE -> println(\"state only\")\n"
# The apply call is present but unreachable / not code.
ARM_DEAD_GUARD = (
    "            KEY_LANGUAGE -> {\n"
    "                if (false) applyLanguageChange(code)\n"
    "            }\n"
)
ARM_COMMENTED = (
    "            KEY_LANGUAGE -> {\n"
    "                // applyLanguageChange(code)\n"
    "                println(\"commented out\")\n"
    "            }\n"
)
ARM_DEAD_THEN_LIVE = (
    "            KEY_LANGUAGE -> {\n"
    "                if (false) applyLanguageChange(\"x\")\n"
    "                applyLanguageChange(\"y\")\n"
    "            }\n"
)
COMMENTED_KEY = ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                 '    <!-- <string name="ghost_key">commented out</string> -->\n'
                 '</resources>\n')
GHOST_KT = "package com.rikkaminis.app\nval g = R.string.ghost_key\n"

ARM_ABSENT = "            \"theme\" -> println(\"theme\")\n"


def _locales_config(tags):
    body = "".join(f'    <locale android:name="{t}"/>\n' for t in tags)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">\n'
            + body + "</locale-config>\n")


def _fixture(files):
    root = tempfile.mkdtemp(prefix="i18n-selftest-")
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
    return root


def _selftest():
    import shutil
    import io
    from contextlib import redirect_stdout

    pass_count = 0
    fail_count = 0

    def run(name, files, expect_exit, expect_in=(), expect_not_in=()):
        nonlocal pass_count, fail_count
        root = _fixture(files)
        buf = io.StringIO()
        try:
            with redirect_stdout(buf):
                code = main_against(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)
        out = buf.getvalue()
        problems = []
        if code != expect_exit:
            problems.append(f"exit={code} expected={expect_exit}")
        for needle in expect_in:
            if needle not in out:
                problems.append(f"missing {needle!r}")
        for needle in expect_not_in:
            if needle in out:
                problems.append(f"unexpected {needle!r}")
        if problems:
            fail_count += 1
            print(f"  \u274c {name}")
            print(f"     {'; '.join(problems)}")
            print("     " + out.replace("\n", "\n     "))
        else:
            pass_count += 1
            print(f"  \u2705 {name}")

    base = {os.path.join(RES_DIR, "values", "strings.xml"): STRINGS,
            os.path.join("src/android/app/src/main/java/com/rikkaminis/app", "Ui.kt"): CLEAN_KT}
    wired = dict(base)
    wired[APPEARANCE_SCREEN] = _appearance_screen(ARM_WIRED)

    def with_locales(tags, extra=None):
        files = dict(wired)
        files[LOCALES_CONFIG] = _locales_config(tags)
        files[os.path.join(RES_DIR, "values-zh-rTW", "strings.xml")] = STRINGS
        if extra:
            files.update(extra)
        return files

    run("real shape: en + zh-TW registered, both shipped",
        with_locales(["en", "zh-TW"]), 0, expect_in=["ALL CLEAN", "every registered locale"])

    run("registered locale with no values-* folder is caught",
        with_locales(["en", "zh-TW", "fr"]), 1, expect_in=["'fr'", "values-fr"])

    run("registered locale whose folder exists but ships no strings*.xml is caught",
        with_locales(["en", "zh-TW", "de"], {
            os.path.join(RES_DIR, "values-de", "colors.xml"): "<resources/>\n"}),
        1, expect_in=["'de'", "values-de"])

    run("locales_config.xml with no <locale> trips the rot guard (fail-closed)",
        with_locales([]), 1, expect_in=["ROT"])

    run("missing locales_config.xml is a clean skip, not a failure",
        wired, 0, expect_in=["not checked"], expect_not_in=["ROT"])

    run("one-line KEY_LANGUAGE arm that only moves state is caught",
        dict(wired, **{APPEARANCE_SCREEN: _appearance_screen(ARM_ONE_LINER)}), 1,
        expect_in=["KEY_LANGUAGE", "external write"])

    run("KEY_LANGUAGE arm without the shared apply call is caught",
        dict(wired, **{APPEARANCE_SCREEN: _appearance_screen(ARM_STATE_ONLY)}), 1,
        expect_in=["external write"])

    run("AppearanceScreen without a KEY_LANGUAGE arm trips the rot guard (fail-closed)",
        dict(wired, **{APPEARANCE_SCREEN: _appearance_screen(ARM_ABSENT)}), 1,
        expect_in=["ROT"])

    run("KEY_LANGUAGE arm whose only apply call sits behind `if (false)` is caught",
        dict(wired, **{APPEARANCE_SCREEN: _appearance_screen(ARM_DEAD_GUARD)}), 1,
        expect_in=["dead or commented-out code"])

    run("commented-out apply call is not wiring",
        dict(wired, **{APPEARANCE_SCREEN: _appearance_screen(ARM_COMMENTED)}), 1,
        expect_in=["does not call"])

    run("a live apply call next to a dead one is still green (no over-red)",
        dict(wired, **{APPEARANCE_SCREEN: _appearance_screen(ARM_DEAD_THEN_LIVE)}), 0,
        expect_in=["applies the change"])

    run("a resource key that exists only inside an XML comment is still an orphan",
        dict(wired, **{
            os.path.join(RES_DIR, "values", "strings_extra.xml"): COMMENTED_KEY,
            os.path.join("src/android/app/src/main/java/com/rikkaminis/app", "Ghost.kt"): GHOST_KT,
        }), 1, expect_in=["ghost_key"])

    run("missing AppearanceScreen.kt is a clean skip, not a failure",
        base, 0, expect_in=["not checked"])

    print(f"\n{SELFTEST_MARKER if not fail_count else 'I18N_CHECK_SELFTEST=FAIL'} "
          f"({pass_count} fixtures, {fail_count} mismatched)")
    return 1 if fail_count else 0


def main_against(root):
    """main() for an explicit root — keeps --selftest from touching argv."""
    saved = sys.argv
    try:
        sys.argv = ["i18n_check.py", root]
        return main()
    finally:
        sys.argv = saved


if __name__ == "__main__":
    if any(a == "--selftest" for a in sys.argv[1:]):
        sys.exit(_selftest())
    sys.exit(main())
