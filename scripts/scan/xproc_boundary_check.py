#!/usr/bin/env python3
"""Cross-process boundary completeness guard.

Background: models are handed to the `:modelservice` worker over an IPC boundary.
The dispatcher serialises a model by writing named keys into a bundle; the worker
reconstructs the model by reading those same keys. Adding a field to the model
without adding it to BOTH sides means the worker silently falls back to the
field's default value -- no exception, no log, just wrong behaviour.

This is a repeat offender. Live example (2026-09-22, P0): `LLMModel.maxOutputTokens`
was never written by the dispatcher, so the worker fell back to its 16384 default
and clamped 96.3% of real requests to a smaller reply budget than the user chose.

Note the sibling guard `four_way_sync_check.py` covers Model<->Entity<->snapshot
for ProviderInstance/ModelGroup only -- it does NOT cover this IPC hop, and it does
not cover LLMModel at all. Hence this gate.

Judgement:
  * every field of a boundary model must appear as a dispatcher put() key
    (or be explicitly listed as BENIGN with a reason)
  * every dispatcher key must be consumed on the worker side

## Judgement, second wave (2026-10-25, wide3/10) — "presence" is not "effect"

Four shapes used to pass while re-introducing the P0. Each is now a criterion,
and each has a negative arm in `--selftest`:

  1. `if (false) model.maxOutputTokens?.let { put("max_output_tokens", it) }`
     kept the literal, killed the write. Keys are now collected from **live**
     call sites only (`dead_guarded()`: constant-false condition on the same
     statement, or in an enclosing `{` block).
  2. `put("max_output_tokens", 0)` still spelled the key. A live site must now
     also be **derived**: the value expression is not a bare literal, and the
     field name appears in the expression that produces it (`model.x`, or `it`
     bound by `model.x?.let/let`). Otherwise the write carries no user value.
  3. A `)` inside a comment (`// close paren in comment: )`) truncated the
     constructor list, so every field after it dropped out of the scan. The
     comment is now blanked **before** the paren walk (same length, same
     offsets), and an empty/unparseable constructor list is a violation rather
     than an empty field list ("parser death" must fail closed, never pass).
  4. `maxOutputTokens = null` plus a comment mentioning the key passed the
     reverse check, because it searched the raw worker text. The worker source is
     now comment-blanked and the key must appear in a **read call**
     (`get*`/`opt*`/`has`/`is*`), and — when the worker constructs the model —
     **every** construction site must carry the key (the worker reconstructs
     LLMModel twice: streaming and non-streaming; one silently-degraded path is
     the defect).
"""
import re
import sys
import os
import tempfile

REPO = sys.argv[1] if len(sys.argv) > 1 else "."

# Models that cross the :modelservice IPC boundary.
BOUNDARY_MODELS = [
    {
        "name": "LLMModel",
        "model_path": "src/android/app/src/main/java/com/rikkaminis/app/data/model/LLMModel.kt",
    },
    {
        "name": "ProviderInstance",
        "model_path": "src/android/app/src/main/java/com/rikkaminis/app/data/model/ProviderConfig.kt",
    },
]

# Fields whose dispatcher key is NOT the plain snake_case of the field name.
# (Only needed for acronyms / renamed keys; everything else falls back to
# snake_case so that a NEWLY added field is still caught automatically.)
KEY_ALIASES = {
    "ProviderInstance": {
        "id": "instance_id",
        "label": "instance_label",
        "customBaseURL": "base_url",
        "appendV1Suffix": "append_v1",
        "customUserAgent": "user_agent",
        "useResponsesAPI": "use_responses_api",
        "imageEndpointMode": "image_endpoint_mode",
        "imageEndpointResolved": "image_endpoint_resolved",
        "azureMode": "azure_mode",
    },
    "LLMModel": {
        "id": "model_id",
        "displayName": "model_display_name",
        "provider": "model_provider",
    },
}

DISPATCHER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionDispatcher.kt"
WORKER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"

# Fields deliberately NOT carried across the boundary.
# Each entry MUST have a reason -- an unexplained entry is how the next P0 hides.
BENIGN = {
    "ProviderInstance": {
        "credentials": (
            "credential METADATA never crosses the boundary: the worker reads "
            "its secret from EncryptedPrefs by slot name, so the labels/notes "
            "stored here are host-side presentation data only. The multi-key "
            "feature that wrote this field was removed; the field survives "
            "only because its column is part of schema version 11."
        ),
        "isEnabled": "worker never reads it; gating happens on the host side",
        "createdAt": "presentation-only ordering metadata",
        "pinned": "worker hard-codes pinned=false; pinning is a host-side UI concept",
    },
}

SELFTEST_MARKER = "XPROC-BOUNDARY SELFTEST=PASS"


def read(path):
    full = os.path.join(REPO, path)
    try:
        return open(full, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


# ── lexical helpers ───────────────────────────────────────────────────────────
# Two views of the same source, both SAME LENGTH as the input so every offset
# (and therefore every line number) stays valid:
#   strings=False → comments blanked, string literals intact (for key literals)
#   strings=True  → comments AND literal contents blanked (for structure:
#                   parens/braces/commas inside a literal must not count)
def blank(text, *, strings):
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
            if ch == "\\":
                out.append("  ")
                i += 1
            elif ch == '"':
                mode = None
                out.append('"')
            else:
                out.append((" ", ch)[not strings] if ch != "\n" else "\n")
        elif mode == "raw":
            if text[i:i + 3] == '"""':
                mode = None
                out.append('"""')
                i += 2
            else:
                out.append((" ", ch)[not strings] if ch != "\n" else "\n")
        elif mode == "chr":
            if ch == "\\":
                out.append("  ")
                i += 1
            elif ch == "'":
                mode = None
                out.append("'")
            else:
                out.append((" ", ch)[not strings] if ch != "\n" else "\n")
        else:
            if ch == "/" and nxt == "/":
                mode = "line"
                out.append("  ")
                i += 1
            elif ch == "/" and nxt == "*":
                mode = "block"
                out.append("  ")
                i += 1
            elif text[i:i + 3] == '"""':
                mode = "raw"
                out.append('"""')
                i += 2
            elif ch == '"':
                mode = "str"
                out.append('"')
            elif ch == "'":
                mode = "chr"
                out.append("'")
            else:
                out.append(ch)
        i += 1
    return "".join(out)


DEAD_RE = re.compile(
    r"(?:\bif\s*\(\s*|\bwhile\s*\(\s*)"
    r"(?:false\b|!\s*true\b|0\s*==\s*1\b|true\s*\|\||false\s*&&)"
    r"|\bfalse\s*&&|\btrue\s*\|\|"
)


def stmt_start(code, off):
    """Offset just after the last statement boundary before `off`.

    A newline at bracket depth 0 is a boundary: Kotlin rarely uses semicolons,
    so without it the scan walks back into the previous statement and a guard
    from a different statement would be attributed to this one.
    """
    depth = 0
    i = off - 1
    while i >= 0:
        c = code[i]
        if c in ")]":
            depth += 1
        elif c in "([":
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and c in "\n;{}":
            return i + 1
        i -= 1
    return i + 1


def dead_guarded(code, off):
    """True when a constant-false condition governs `off`.

    Covers both `if (false) call(...)` (same statement) and a constant-false
    block that encloses the call (`if (false) { ... }`, `model.x?.let { ... }`
    hanging off a `false &&` expression). Structural: the whole point is that
    `if (false) <real code>` keeps every literal the old criteria looked for.
    """
    if DEAD_RE.search(code[stmt_start(code, off):off]):
        return True
    depth = 0
    i = off
    while i > 0:
        i -= 1
        c = code[i]
        if c in "})":
            depth += 1
        elif c in "({":
            if depth == 0:
                if c == "{" and DEAD_RE.search(code[stmt_start(code, i):i]):
                    return True
            else:
                depth -= 1
    return False


def paren_end(code, open_idx):
    """Index of the `)` matching code[open_idx] == '(' (or None)."""
    depth = 0
    for j in range(open_idx, len(code)):
        c = code[j]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return j
    return None


def split_first_arg(argtext):
    depth = 0
    for i, c in enumerate(argtext):
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            return argtext[:i], argtext[i + 1:]
    return argtext, ""


def extract_data_class_fields(src, cls_name):
    """Return the ctor parameter names of `data class <cls_name>(...)`."""
    st = blank(src, strings=True)
    m = re.search(r"data\s+class\s+" + re.escape(cls_name) + r"\s*\(", st)
    if not m:
        return None, "data class not found: " + cls_name
    i = m.end() - 1
    j = paren_end(st, i)
    if j is None:
        return None, "unbalanced parentheses in " + cls_name
    # The comment is blanked BEFORE the walk above (both views are same-length,
    # so a `)` inside a comment can no longer truncate the parameter list).
    # Top-level params only: split on commas at depth 0 (structural view, so a
    # comma inside a default string literal does not split the declaration, and
    # a comment between two params cannot merge them into one segment).
    body = st[i + 1:j]
    fields, depth, cur = [], 0, ""
    for ch in body:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            fields.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        fields.append(cur)
    out = []
    for f in fields:
        f = f.strip()
        if not f:
            continue
        nm = re.match(r"(?:@\w+(?:\([^)]*\))?\s+)*(?:val|var)\s+(\w+)", f)
        if nm:
            out.append(nm.group(1))
    if not out:
        return None, "no constructor parameters parsed in " + cls_name
    return out, None


PUT_RE = re.compile(
    r'(?:\.|\b)put(?:String|Int|Long|Boolean|Float|Double|StringArrayList)?\s*\(\s*"([^"]+)"')
LITERAL_RE = re.compile(
    r'^(?:-?\d[\d_]*[lLfFdD]?|0x[0-9a-fA-F]+|true|false|null|"([^"\\]|\\.)*"|\'([^\'\\]|\\.)\')$')


def put_sites(ks, st):
    """Every named put() call, with its value expression and liveness.

    `ks` keeps string literals (keys live there); `st` blanks them (paren
    matching must not be fooled by a literal). Both share offsets.
    """
    out = []
    for m in PUT_RE.finditer(ks):
        op = ks.find("(", m.start())
        end = paren_end(st, op)
        if end is None:
            continue  # unbalanced call: cannot judge it; other arms still run
        args = ks[op + 1:end]
        _k, val = split_first_arg(args)
        out.append({
            "key": m.group(1),
            "value": val.strip(),
            "off": m.start(),
            "end": end,
            "dead": dead_guarded(st, m.start()),
        })
    return out


def derives_field(site, field, ks):
    """Does this live put() carry a value derived from `field`?

    `put("k", 0)` and `put("k", "literal")` spell the key but carry no user
    value; `model.maxOutputTokens?.let { put("k", it) }` derives through the
    `it` bound by the receiver expression. The window is the statement that
    produces the call (bounded, comment-blanked), so a field name only counts
    when it is really this call's source.
    """
    if not site["value"] or LITERAL_RE.match(site["value"]):
        return False
    start = max(0, site["off"] - 320)
    window = ks[start:site["end"]]
    return re.search(r"\b%s\b" % re.escape(field), window) is not None


def key_read_sites(ks, st):
    """Keys that are *read* (not merely mentioned) in live worker code.

    The read shapes in this repo: `req.optString("k", default)`,
    `req.has("k")`, `getString("role")`, and the helper form
    `getString(obj, "k")`. A key that only appears in a comment, or in a
    `maxOutputTokens = null` replacement, is not consumed.
    """
    out = set()
    read_callee = re.compile(r"\b(?:get|opt|has|is)[A-Za-z]*$")
    for m in re.finditer(r'"([^"\n]+)"', ks):
        key = m.group(1)
        j = m.start() - 1
        while j >= 0 and ks[j] in " \t\n":
            j -= 1
        if j < 0:
            continue
        ok = False
        if ks[j] == "(":
            name = re.search(r"[\w.]+$", ks[max(0, j - 60):j])
            ok = bool(name and read_callee.search(name.group(0)))
        elif ks[j] == ",":
            # helper form: callee(<receiver>, "key") — find the call's open paren
            depth = 0
            k = j
            while k >= 0:
                if ks[k] in ")]":
                    depth += 1
                elif ks[k] in "([":
                    if depth == 0:
                        break
                    depth -= 1
                k -= 1
            if k > 0:
                name = re.search(r"[\w.]+$", ks[max(0, k - 60):k])
                ok = bool(name and read_callee.search(name.group(0)))
        if ok and not dead_guarded(st, m.start()):
            out.add(key)
    return out


def construction_spans(st, name):
    """Argument spans of every `Name(...)` construction in the worker."""
    spans = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", st):
        head = st[max(0, m.start() - 40):m.start()]
        if re.search(r"\b(?:data\s+)?class\s+$", head):
            continue  # a declaration, not a construction
        op = st.find("(", m.start())
        end = paren_end(st, op)
        if end is None:
            continue
        spans.append((op, end))
    return spans


def to_snake(name):
    """camelCase / PascalCase -> snake_case (matches the dispatcher's key style)."""
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def evaluate(root):
    """(problems, report_lines) for the tree at `root`."""
    problems, lines = [], []

    def read_at(path):
        try:
            return open(os.path.join(root, path), encoding="utf-8",
                        errors="replace").read()
        except OSError:
            return None

    dsrc = read_at(DISPATCHER)
    wsrc = read_at(WORKER)
    if dsrc is None:
        return ["cannot read " + DISPATCHER], lines
    if wsrc is None:
        # Anchor drift: the worker moved or was renamed. The reverse direction
        # cannot be judged at all, and "cannot judge" must never read as clean.
        return ["cannot read " + WORKER + " (worker moved? reverse check cannot run)"], lines

    dks = blank(dsrc, strings=False)
    dst = blank(dsrc, strings=True)
    wks = blank(wsrc, strings=False)
    wst = blank(wsrc, strings=True)

    sites = put_sites(dks, dst)
    if not sites:
        return ["dispatcher writes no named put() keys — parser death, not a clean tree"], lines
    live_keys = {s["key"] for s in sites if not s["dead"]}
    model_keys = {}
    for g in BOUNDARY_MODELS:
        name = g["name"]
        src = read_at(g["model_path"])
        if src is None:
            problems.append("cannot read %s" % g["model_path"])
            continue
        fields, err = extract_data_class_fields(src, name)
        if err:
            problems.append("%s: %s" % (name, err))
            continue
        model_keys[name] = set()

        benign = BENIGN.get(name, {})
        alias = KEY_ALIASES.get(name, {})
        missing = []
        for f in fields:
            want = alias.get(f, to_snake(f))
            if f in benign:
                continue
            cand = [s for s in sites if s["key"] == want]
            live = [s for s in cand if not s["dead"]]
            if not live:
                why = ("dispatcher never writes %r (only in dead code)" % want
                       if cand else "dispatcher never writes %r" % want)
                missing.append((f, why))
                continue
            model_keys[name].add(want)
            if not any(derives_field(s, f, dks) for s in live):
                missing.append((f, "dispatcher writes %r but the value carries no "
                                   "`%s` (constant / unrelated expression)" % (want, f)))

        status = "OK" if not missing else "GAP"
        lines.append("%-18s %2d fields  ->  %s" % (name, len(fields), status))
        for f, why in missing:
            problems.append("%s.%s" % (name, f))
            lines.append("    MISSING  %s.%s  (%s)" % (name, f, why))
        for f, why in sorted(benign.items()):
            if f in fields:
                lines.append("    benign   %s.%s  (%s)" % (name, f, why))

    # Reverse direction: keys written but never consumed on the worker side.
    read_keys = key_read_sites(wks, wst)
    orphan = sorted(k for k in live_keys if k not in read_keys)
    if orphan:
        lines.append("\nDispatcher writes %d key(s) the worker never reads:" % len(orphan))
        for k in orphan[:20]:
            lines.append("    ORPHAN   %s" % k)
        problems.extend("orphan:" + k for k in orphan)

    # Per-construction completeness: the worker rebuilds each boundary model at
    # more than one site (streaming + non-streaming). A key missing from ONE
    # site is a silently degraded path, and the global text search cannot see
    # it because the other site mentions the key.
    for name, keys in model_keys.items():
        spans = construction_spans(wst, name)
        wanted = sorted(keys & live_keys)
        if not spans or not wanted:
            continue
        for (a, b) in spans:
            span = wks[a:b]
            gone = [k for k in wanted if ('"%s"' % k) not in span]
            if gone:
                line = wst.count("\n", 0, a) + 1
                lines.append("    SITE     %s(...) at line %d drops %s"
                             % (name, line, ", ".join(gone[:6])))
                problems.extend("%s site@%d drops %s" % (name, line, k) for k in gone)
                break
    return problems, lines


def main():
    problems, lines = evaluate(REPO)
    for ln in lines:
        print(ln)
    print("")
    if problems:
        print("== %d cross-process boundary problem(s) ==" % len(problems))
        print("")
        print("A field on a boundary model that the dispatcher never writes will")
        print("silently fall back to its default inside the worker. Fix by adding it")
        print("to ModelExecutionDispatcher, or list it under BENIGN with a reason.")
        return 1
    print("== cross-process boundary OK ==")
    return 0


# ── self-test: every negative arm is one of the four re-introduction shapes ───
MODEL = (
    "package com.rikkaminis.app.data.model\n\n"
    "data class LLMModel(\n"
    "    val id: String,\n"
    "    val maxOutputTokens: Int? = null,\n"
    ")\n"
)
PROVIDER = (
    "package com.rikkaminis.app.data.model\n\n"
    "data class ProviderInstance(\n"
    "    val id: String,\n"
    ")\n"
)
DISPATCHER_HEAD = (
    "package com.rikkaminis.app.sandbox.offload\n\n"
    "class ModelExecutionDispatcher {\n"
    "    fun build(model: LLMModel, instance: ProviderInstance) {\n"
    "        val bundle = Bundle()\n"
    "        bundle.apply {\n"
    "            put(\"instance_id\", instance.id)\n"
    "            put(\"model_id\", model.id)\n"
)
DISPATCHER_TAIL = "        }\n    }\n}\n"
WORKER_HEAD = (
    "package com.rikkaminis.app.sandbox.offload\n\n"
    "class ModelExecutionService {\n"
    "    fun read(b: Bundle) {\n"
    "        val i = b.getString(\"instance_id\")\n"
    "        val m = com.rikkaminis.app.data.model.LLMModel(\n"
    "            id = b.getString(\"model_id\"),\n"
)
WORKER_TAIL = "            maxOutputTokens = b.getInt(\"max_output_tokens\"),\n        )\n    }\n}\n"


def _tree(files):
    root = tempfile.mkdtemp(prefix="xproc-selftest-")
    for rel, text in files.items():
        p = os.path.join(root, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "w", encoding="utf-8").write(text)
    return root


B = "src/android/app/src/main/java/com/rikkaminis/app/"


def _fixture(model=MODEL, puts="", worker_tail=WORKER_TAIL, worker_extra=""):
    return {
        B + "data/model/LLMModel.kt": model,
        B + "data/model/ProviderConfig.kt": PROVIDER,
        B + "sandbox/offload/ModelExecutionDispatcher.kt":
            DISPATCHER_HEAD + puts + DISPATCHER_TAIL,
        B + "sandbox/offload/ModelExecutionService.kt":
            WORKER_HEAD + worker_tail + worker_extra + "}\n",
    }


def selftest():
    import shutil
    failures = []

    def arm(name, files, want_rc, needle=None):
        root = _tree(files)
        try:
            problems, lines = evaluate(root)
            rc = 0 if not problems else 1
            haystack = problems + lines
            ok = rc == want_rc and (needle is None
                                    or any(needle in h for h in haystack))
            print("  %s %s" % ("✅" if ok else "❌", name))
            if not ok:
                failures.append("%s (rc=%d want=%d problems=%s panels=%s)"
                                % (name, rc, want_rc, problems[:4], lines[-3:]))
        finally:
            shutil.rmtree(root, ignore_errors=True)

    live_put = '            model.maxOutputTokens?.let { put("max_output_tokens", it) }\n'
    # 1. control: the real shape passes.
    arm("clean: derived live put() passes", _fixture(puts=live_put), 0)

    # 2. dataflow: `if (false)` keeps the literal, kills the write.
    arm("dead branch: `if (false) … put(\"max_output_tokens\", it)` is red",
        _fixture(puts='            if (false) model.maxOutputTokens?.let { put("max_output_tokens", it) }\n'),
        1, "maxOutputTokens")

    # 3. dataflow: the key is written, the value is a constant.
    arm("constant value: `put(\"max_output_tokens\", 0)` is red",
        _fixture(puts='            put("max_output_tokens", 0)\n'), 1, "maxOutputTokens")

    # 4. parser-death: a `)` inside a comment must not truncate the ctor list.
    truncated = (
        "package com.rikkaminis.app.data.model\n\n"
        "data class LLMModel(\n"
        "    val id: String,\n"
        "    val maxOutputTokens: Int? = null, // close paren in comment: )\n"
        "    val unbridgedNewField: Int? = null,\n"
        ")\n"
    )
    arm("parser-death: `)` inside a comment cannot hide later fields",
        _fixture(model=truncated, puts=live_put), 1, "unbridgedNewField")

    # 5. anchor: worker replaces the read with `null` + a comment naming the key.
    broken_tail = (
        "            maxOutputTokens = null, // \"max_output_tokens\" ignored here\n"
    )
    arm("worker: `= null` plus a comment mentioning the key is red",
        _fixture(puts=live_put, worker_tail=broken_tail), 1, "orphan:max_output_tokens")

    # 6. completeness: a SECOND construction site that drops the key.
    second = (
        "    fun read2(b: Bundle) {\n"
        "        val m = com.rikkaminis.app.data.model.LLMModel(\n"
        "            id = b.getString(\"model_id\"),\n"
        "            maxOutputTokens = null,\n"
        "        )\n"
        "    }\n"
    )
    arm("multi-site: one of two ctor sites dropping the key is red",
        _fixture(puts=live_put, worker_extra=second), 1, "SITE")

    # 7. fail-closed: a missing worker file is a violation, not a clean tree.
    files = _fixture(puts=live_put)
    del files[B + "sandbox/offload/ModelExecutionService.kt"]
    arm("fail-closed: missing worker file is red", files, 1, "cannot read")

    if failures:
        for f in failures:
            print("FAIL  " + f)
        print("XPROC-BOUNDARY SELFTEST=FAIL")
        return 1
    print(SELFTEST_MARKER)
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
