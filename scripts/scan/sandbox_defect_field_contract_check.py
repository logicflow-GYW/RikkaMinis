#!/usr/bin/env python3
"""CI scan gate — the sandbox defect-registry FIELD LINE parser keeps its
documented width contract (meta-audit-1028 W-11).

`sandbox-limits.md` is authored by the user / agent, in Markdown. The registry
therefore promises, in its own KDoc, that a field line is accepted in three
spellings:

    - 症状：…        (plain, full-width colon)
    - **症状**：…    (bold, full-width)
    - **症状**: …    (bold, half-width — the Markdown habit)

The pattern that implements the promise is one character class wide:
`\\s*[:：]\\s*`. Narrowing it to `：` (or to `:`), or making the separator
optional, keeps every test green — the unit tests only ever feed the
full-width bold form — while every entry the author wrote with the other
width silently stops matching: its `seedGroups` come back empty and its
`recovery` null, so the recall that exists for the moment of failure never
fires. Nothing in the compiler sees a regex literal's coverage.

What this gate judges (value, not spelling):

  A. the pattern is DERIVED, not named: the `Regex(…)` literal in the registry
     object whose source mentions a field name (症状 / 健康路径 / 救不动) is the
     one under test; a second, dead copy cannot satisfy the gate because of D;
  B. CONTRACT vs KDoc — every field line the KDoc/comment above the literal
     shows as a supported spelling must actually match it, with the field name
     in group 1 and a non-empty value in group 2;
  C. EQUIVALENCE CLASS — both colon widths must parse for all three fields, in
     both plain and bold markup (the audited witness lost exactly this);
  D. SEPARATOR REQUIRED — `- 症状 x` (no separator) must NOT match. Loosening
     `[:：]` to `[:：]?` "fixes" the width while silently merging the value into
     the field name; a parser that accepts anything is not a wider contract;
  E. WIRING — the literal must still be consulted from `parse(…)`, so a rename
     that leaves the old pattern on the page is a red (dead regex).

Usage: python3 sandbox_defect_field_contract_check.py [ROOT]  (ROOT = repo root)
       python3 sandbox_defect_field_contract_check.py --root ROOT
       python3 sandbox_defect_field_contract_check.py --selftest
Exit:  0 = gate green, 1 = violations (or selftest failures), 2 = usage.
"""
import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_comments  # noqa: E402

APP = "src/android/app/src/main/java/com/rikkaminis/app"
TOOLS_DIR = APP + "/tools"
REGISTRY_FILE = "SandboxDefectRegistry.kt"

FIELD_NAMES = ("症状", "健康路径", "救不动")
# Sample value used when turning a documented `…` into a concrete line.
VALUE = "PLACEHOLDER"

REGEX_LIT = re.compile(r"(?P<kind>private\s+|internal\s+|public\s+)?val\s+(?P<name>\w+)\s*=\s*"
                       r"Regex\(\s*(?P<q>\"\"\"|\")(?P<pat>.*?)(?P=q)\s*\)", re.S)
KDOC_SAMPLE = re.compile(r"`([^`\n]*)`")
FIELD_WORD = re.compile(r"(%s)" % "|".join(FIELD_NAMES))
# a field NAME must be followed by optional bold markup and a separator
NAME_TAIL = re.compile(r"^\s*(?:\*\*)?\s*(.*)$", re.S)


def read(rel, root):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def _line_of(text, pos):
    return text.count("\n", 0, pos) + 1


def find_registry(root):
    """(rel, source) of the registry object — derived walk, fail-closed."""
    base = os.path.join(root, TOOLS_DIR)
    if not os.path.isdir(base):
        return None, None
    for name in sorted(os.listdir(base)):
        if not name.endswith(".kt"):
            continue
        src = read(TOOLS_DIR + "/" + name, root)
        if src and "object SandboxDefectRegistry" in src:
            return TOOLS_DIR + "/" + name, src
    return None, None


def _doc_block(src, decl_pos):
    """The KDoc/comment block that sits immediately above [decl_pos], or ''."""
    head = src[:decl_pos].rstrip()
    if not head.endswith("*/"):
        return ""
    start = head.rfind("/**")
    if start < 0:
        start = head.rfind("/*")
    return head[start:] if start >= 0 else ""


def _translate(pattern):
    """Kotlin raw-string regex → python. Only the escapes Python rejects."""
    p = pattern.replace("\\-", "-")
    p = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), p)
    return p


def _compile(pattern):
    try:
        return re.compile(_translate(pattern)), None
    except re.error as exc:
        return None, "pattern does not compile (%s)" % exc


def _field_pattern(src):
    """(decl_pos, name, pattern) of the field-line regex, derived by content."""
    for m in REGEX_LIT.finditer(mask_comments(src)):
        if any(f in m.group("pat") for f in FIELD_NAMES):
            return m.start(), m.group("name"), m.group("pat")
    return None, None, None


def _documented_forms(doc):
    """Field-line spellings the KDoc shows, normalised to concrete lines."""
    forms = []
    for m in KDOC_SAMPLE.finditer(doc):
        sample = m.group(1)
        if "-" not in sample or not FIELD_WORD.search(sample):
            continue
        line = sample.replace("…", VALUE).strip()
        if not re.search(r"[:：]", line):
            continue
        forms.append(line)
    return forms


def _field_of(line):
    m = FIELD_WORD.search(line)
    return m.group(1) if m else None


def _canonical_lines():
    """The width/markup matrix the KDoc promises, for all three fields."""
    out = []
    for field in FIELD_NAMES:
        out.append(("- %s：%s" % (field, VALUE), "plain+full"))
        out.append(("- %s: %s" % (field, VALUE), "plain+half"))
        out.append(("- **%s**：%s" % (field, VALUE), "bold+full"))
        out.append(("- **%s**: %s" % (field, VALUE), "bold+half"))
    return out


def analyse(src):
    """Findings for one registry source (list of strings; [] = clean)."""
    decl_pos, name, pattern = _field_pattern(src)
    if decl_pos is None:
        return ["FIELD_RE: no `Regex(…)` literal in the registry mentions a field name "
                "(%s) — the field-line parser is gone, so every entry parses with no "
                "fields (gate ROT)" % " / ".join(FIELD_NAMES)]
    line = _line_of(src, decl_pos)
    compiled, err = _compile(pattern)
    if compiled is None:
        return ["FIELD_RE(%d): %s — fail closed" % (line, err)]
    out = []

    def matches(sample):
        m = compiled.match(sample)
        if m is None:
            return False
        return bool(m.group(1).strip()) and bool(m.group(2).strip())

    # B — the documented spellings must parse.
    doc = _doc_block(src, decl_pos)
    forms = _documented_forms(doc)
    if not forms:
        out.append("FIELD_RE(%d): the KDoc above the pattern documents no field-line "
                   "spelling — the width contract cannot be judged (fail closed)" % line)
    for form in forms:
        if not matches(form):
            out.append("FIELD_RE(%d): the documented spelling `%s` is REJECTED by the "
                       "pattern — the parser is narrower than its own contract, and every "
                       "registry entry written that way loses its seed/recovery silently"
                       % (line, form))

    # C — both widths, both markups, all three fields.
    for sample, label in _canonical_lines():
        if not matches(sample):
            out.append("FIELD_RE(%d): `%s` (%s) does not parse — the separator must accept "
                       "both colon widths for `%s`"
                       % (line, sample, label, _field_of(sample)))

    # D — a missing separator must not be absorbed into the field name.
    for field in FIELD_NAMES:
        sample = "- %s %s" % (field, VALUE)
        if compiled.match(sample):
            out.append("FIELD_RE(%d): `%s` (no separator) parses — the separator was made "
                       "optional, so the value is swallowed by the field name"
                       % (line, sample))

    # E — the literal must still be consulted by the parser.
    parse = re.search(r"\bfun\s+parse\s*\(", mask_comments(src))
    if parse is None:
        out.append("FIELD_RE(%d): `fun parse(` no longer exists — the pattern has no "
                   "consumer (gate ROT)" % line)
    else:
        close = src.find("{", parse.end())
        body = src[close:close + 4000] if close >= 0 else ""
        if not re.search(r"\b%s\b" % re.escape(name), body):
            out.append("FIELD_RE(%d): `%s` is never consulted from `parse(` — a live copy "
                       "elsewhere cannot cover for a dead pattern" % (line, name))
    return out


def run(root, quiet=False):
    rel, src = find_registry(root)
    if rel is None:
        if not quiet:
            print("FAIL: no `object SandboxDefectRegistry` under %s — the registry was moved "
              "or renamed, and the field-line contract is unwatched (fail closed)" % TOOLS_DIR)
        return 1
    try:
        findings = analyse(src)
    except KotlinLexError as exc:
        print("FAIL: %s is not lexable (%s) — fail closed" % (rel, exc))
        return 1
    if findings:
        print("sandbox_defect_registry_field_check RED (%s):" % rel)
        for f in findings:
            print("  - %s" % f)
        return 1
    print("sandbox_defect_registry_field_check OK (field-line width contract of %s holds: "
          "both colon widths × bold/plain × all three fields, separator required, pattern "
          "wired into parse())" % rel)
    return 0


# ── selftest fixtures ────────────────────────────────────────────────────────
GOOD = '''package p

object SandboxDefectRegistry {
    /**
     * Field line, tolerant of bold markup and of both colon widths:
     * `- 症状：…` / `- **症状**：…` / `- **症状**: …`.
     */
    private val FIELD_RE = Regex("""^-\\s*(?:\\*\\*)?(症状|健康路径|救不动)(?:\\*\\*)?\\s*[:：]\\s*(.*)$""")

    fun parse(text: String): List<Defect> {
        val out = mutableListOf<Defect>()
        for (line in text.lineSequence()) {
            val field = FIELD_RE.find(line)
            if (field != null) {
                out += Defect(field.groupValues[1], field.groupValues[2].trim())
            }
        }
        return out
    }
}
'''

# W-11's witness: the half-width colon is dropped from the class.
WITNESS_HALF_GONE = GOOD.replace(r"[:：]", "：")
# The mirror-image defect: only the half-width survives (an ASCII-minded rewrite).
WITNESS_FULL_GONE = GOOD.replace(r"[:：]", ":")
# "wider" but wrong: the separator becomes optional, so the value is absorbed.
WITNESS_OPTIONAL = GOOD.replace(r"\s*[:：]\s*", r"\s*[:：]?\s*")
# Dead pattern: the parser stopped consulting it (a copy elsewhere is not enough).
WITNESS_DEAD = GOOD.replace("    private val FIELD_RE = Regex(",
                            "    private val FIELD_RE_OLD = Regex(").replace(
    "FIELD_RE.find(line)", "Regex(\"\"\"^-(.*)$\"\"\").find(line)")
# tolerant: the same width class written as a non-capturing alternation.
TOLERANT_ALTERNATION = GOOD.replace(r"[:：]", "(?::|：)")
# tolerant: extra spacing / bold markup variants do not change the width class.
TOLERANT_TIGHT = GOOD.replace(r"\s*[:：]\s*", r"[:：]\s*")


def selftest():
    failures = []
    arms = [
        ("compliant registry is green", GOOD, None, True),
        ("W-11 witness (half-width colon dropped) is red", WITNESS_HALF_GONE,
         "the documented spelling", False),
        ("W-11b full-width colon dropped is red", WITNESS_FULL_GONE,
         "does not parse", False),
        ("W-11c optional separator is red", WITNESS_OPTIONAL,
         "no separator", False),
        ("W-11d dead pattern (parse no longer uses it) is red", WITNESS_DEAD,
         "never consulted", False),
        ("the width class as an alternation is green", TOLERANT_ALTERNATION, None, True),
        ("a tighter whitespace class is green", TOLERANT_TIGHT, None, True),
    ]
    for label, src, needle, clean in arms:
        findings = analyse(src)
        if clean:
            if findings:
                failures.append(label)
                print("  [FAIL] %s: %s" % (label, findings[0][:100]))
            else:
                print("  [OK]   %s" % label)
        elif not findings:
            failures.append(label)
            print("  [FAIL] %s — stayed green" % label)
        elif needle and not any(needle in f for f in findings):
            failures.append(label)
            print("  [FAIL] %s — red for the wrong reason: %s" % (label, findings))
        else:
            print("  [OK]   %s — %s" % (label, findings[0][:90]))

    # ROT: a registry file that is gone must fail loudly, never read as clean.
    with tempfile.TemporaryDirectory() as tmp:
        rc = run(tmp, quiet=True)
        if rc == 0:
            failures.append("missing registry reported clean")
            print("  [FAIL] ROT control: a missing registry was reported clean")
        else:
            print("  [OK]   ROT control: a missing registry is a red")

    if failures:
        print("SELFTEST FAIL (%d)" % len(failures))
        return 1
    print("SANDBOX_DEFECT_FIELD_CONTRACT_SELFTEST=PASS (%d fixtures)" % len(arms))
    return 0


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("root", nargs="?", default=None)
    ap.add_argument("--root", dest="root_opt", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    root = args.root_opt or args.root
    if not root:
        ap.print_usage(sys.stderr)
        return 2
    if not os.path.isdir(root):
        print("root not found: %s" % root, file=sys.stderr)
        return 2
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
