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

── [meta-audit wide3, group A] why the criteria were rewritten ──────────────
Three witnesses showed the previous implementation judged the wrong thing:

  C1 (anchor)   it classified a writer from ANY `arrayListOf(`/`intArrayOf(`
                occurrence inside a +-3 LINE WINDOW around the key, with an
                `if … elif …` chain. A dead `val unused = arrayListOf(1)`
                sitting one line above an `intArrayOf` writer, or simply the
                neighbouring `RepeatMode.WEEKDAYS` branch's `arrayListOf(`,
                made the wrong container invisible. A window is not an
                argument.
  C3 (dataflow) for the indirect shape (`daysForRepeatMode(m)?.let { putExtra(
                k, it) }`) it only checked the PRODUCER's declared return type,
                so `putExtra(k, it.toIntArray())` — the value converted after
                tracing — stayed green.
  C4 (completeness) the "at least 2 writers" floor counted CALLS, not FILES:
                deleting every writer in one of the two files left the other
                file's two branches holding the count up.

The rewrite therefore (a) parses the real `putExtra(…)` call and takes its
SECOND argument, (b) classifies that expression's own shape and follows one-hop
bindings (lambda receiver, local `val`, producer call, parameter type), (c)
fails closed on anything it cannot resolve, and (d) derives the expected writer
FILES from the tree (every file that builds an ACTION_SET_ALARM intent must
have at least one verified writer).
"""
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import analyse  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions,
    when_branches,
)

# --------------------------------------------------------------------------
# Lexing: the shared Kotlin lexer is the single source of truth
# --------------------------------------------------------------------------
# [wide4 parser-death rule] This gate used to carry its own masker. A gate
# that re-implements Kotlin lexical rules is a gate that loses them: nested
# block comments, char literals holding a quote, triple-quoted raw strings
# and `${...}` templates each blinded a different criterion. Every source is
# now lexed by `_kotlin_lexer.analyse()` and an unparseable file is a
# VIOLATION, never a silent skip.


def code_view(text):
    """(code_view, error). Comments AND literal contents blanked, same length."""
    ok, reason, views = analyse(text)
    if not ok:
        return None, reason
    return views['code'], None


# --------------------------------------------------------------------------
# Expression helpers
# --------------------------------------------------------------------------
CALL_HEAD_RE = re.compile(r"^([A-Za-z_][\w.]*|<this>)\s*\(")
IDENT_RE = re.compile(r"^[A-Za-z_]\w*$")
ALIAS_TAIL_OK = re.compile(r"\?\s*$")

WRONG_CONTAINERS = {
    "intArrayOf": "IntArray (parcels as int[])",
    "listOf": "java.util.Arrays$ArrayList (not java.util.ArrayList)",
    "mutableListOf": "kotlin.collections.MutableList (not java.util.ArrayList)",
    "arrayOf": "Array (parcels as Object[])",
    "buildList": "java.util.List (not java.util.ArrayList)",
    "toTypedArray": "Array (not java.util.ArrayList)",
    "toIntArray": "IntArray (parcels as int[])",
    "toList": "kotlin.collections.List (not java.util.ArrayList)",
    "toSet": "Set (not java.util.ArrayList)",
}


def split_args(text, open_paren):
    """Top-level argument expressions of the call whose `(` is at open_paren.

    `text` MUST be the lexer's `code` view (comments and literal contents
    blanked, delimiters kept). [wide4 / split_args] The hand-written string
    tracker this replaced only knew a single `"`, so a triple-quoted raw string
    holding `)` and `,` in the KEY argument
    (`EXTRA_DAYS.also { val s = <triple-quoted string with ) and ,> }`) closed
    the call early and the guard judged `arrayListOf(1)` while the real second
    argument was an int[]. Blanking literal contents makes that shape
    unrepresentable.

    Returns (list_of_arg_texts, index_of_closing_paren) or (None, None) when
    the parenthesis never closes — the caller treats that as a violation.
    """
    depth = 1  # the caller's own `(` is already open
    args = []
    cur = []
    i = open_paren + 1
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
            cur.append(ch)
        elif ch in ")]}":
            depth -= 1
            if depth == 0 and ch == ")":
                args.append("".join(cur))
                return args, i
            cur.append(ch)
        elif ch == "," and depth == 1:
            args.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    return None, None


def strip_trailing(expr):
    return " ".join(expr.split()).strip()


def call_spans_whole(expr, name):
    """True when `expr` is exactly a call to `name(…)` (nothing wrapped)."""
    m = CALL_HEAD_RE.match(expr)
    if not m or m.group(1).split(".")[-1] != name:
        return False
    open_paren = expr.index("(", m.start(1))
    depth = 0
    for k in range(open_paren, len(expr)):
        if expr[k] == "(":
            depth += 1
        elif expr[k] == ")":
            depth -= 1
            if depth == 0:
                return strip_trailing(expr[k + 1:]) in ("", "?")
    return False


# --------------------------------------------------------------------------
# Container classification
# --------------------------------------------------------------------------


def classify_type(ret_type, base):
    """Judge a declared Kotlin type string. Returns (kind, why_wrong|None)."""
    t = " ".join(ret_type.split())
    if re.search(r"\bArrayList\b", t):
        return "ArrayList", None
    if re.search(r"\bIntArray\b", t):
        return "IntArray", f"IntArray (parcels as int[]) — declared `{t}`"
    if re.search(r"\bList\b|\bSet\b|\bCollection\b|\bArray\b|\bIterable\b", t):
        return "other", (f"`{t}` is not java.util.ArrayList "
                         f"(listOf() returns Arrays$ArrayList)")
    return "unknown", f"cannot read a container type out of `{t}`"


def declared_return(masked, fn):
    m = re.search(r"\bfun\s+" + re.escape(fn) + r"\s*\(", masked)
    if not m:
        return None
    open_paren = masked.index("(", m.end() - 1)
    depth = 0
    k = open_paren
    while k < len(masked):
        if masked[k] == "(":
            depth += 1
        elif masked[k] == ")":
            depth -= 1
            if depth == 0:
                break
        k += 1
    else:
        return None
    tail = masked[k + 1:k + 400]
    gm = re.match(r"\s*:\s*([\w<>?,.\s]+?)\s*(?:\{|=|where\b|$)", tail)
    return gm.group(1).strip() if gm else None


def _backward_expr(text, end):
    """The trailing balanced expression that ends at `end` (whitespace-trimmed).

    Reads `daysForRepeatMode(repeatMode)`, `holder.days()`, `days` — never more
    than one call chain. A greedy regex here used to swallow the whole
    preceding statement block (every `putExtra(` above the `?.let`), which made
    the producer untraceable and the value unverifiable.
    """
    i = end
    while i > 0 and text[i - 1] in " \t\n\r":
        i -= 1
    j = i
    while j > 0:
        ch = text[j - 1]
        if ch.isalnum() or ch in "._":
            j -= 1
            continue
        if ch == ")":
            depth = 0
            k = j
            while k > 0:
                k -= 1
                if text[k] == ")":
                    depth += 1
                elif text[k] == "(":
                    depth -= 1
                    if depth == 0:
                        break
            else:
                return None
            if depth != 0:
                return None
            j = k          # the `(`; the callee name is consumed next round
            continue
        break
    expr = text[j:i].strip()
    return expr or None


SCOPE_IT_RE = re.compile(
    r"\??\s*\.\s*(?:let|also|takeIf|takeUnless)\s*(?:\([^()]*\))?\s*$")


def lambda_receiver(masked, value_off):
    """Receiver of the scope function that binds a bare `it` at `value_off`.

    [wide4 / lambda_receiver] The old version used `seg.rfind("{")` — the last
    `{` in the file, closed or not. An empty nested lambda one line above
    (`daysForRepeatMode(r)?.let {}`) therefore made `it` resolve to the WRONG
    producer and an int[] value stayed green. The scan now walks backwards with
    brace depth and stops at the first UNCLOSED `{`; it also accepts the
    non-null `x.let {` spelling (the old pattern required a literal `?`).

    Only `let`/`also`/`takeIf`/`takeUnless` bind `it`. `apply`/`run`/`with`
    rebind the receiver `this`, so an `it` inside them comes from somewhere
    else — returning None there keeps the caller fail-closed.
    """
    i = value_off - 1
    depth = 0
    while i >= 0:
        c = masked[i]
        if c == "}":
            depth += 1
        elif c == "{":
            if depth:
                depth -= 1
            else:
                head = masked[:i]
                m = SCOPE_IT_RE.search(head)
                if not m:
                    return None
                return _backward_expr(head, m.start())
        i -= 1
    return None


def _expr_end(text, start):
    """End of the expression starting at `start` (depth-0 newline/semicolon)."""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif ch in "\n;" and depth == 0:
            break
        i += 1
    return i


def find_local_binding(masked, name, before):
    """The binding of `name` that actually WINS at `before`.

    [wide4 / find_local_binding] `val|var <name> = <expr>` alone is only the
    DECLARATION. `var days: Serializable = arrayListOf(1)` followed by
    `days = intArrayOf(1, 2, 3)` hands an int[] to the bundle while a
    declaration-only reading still classifies `arrayListOf(1)` — the guard
    blessed exactly the value the JVM never passes. Last write wins;
    compound assignment (`+=`) and re-assignment included.

    Returns (rhs_text, rhs_offset, is_decl, op) or None.
    """
    pat = re.compile(r"(?:\b(?:val|var)\s+)?\b" + re.escape(name) +
                     r"\s*(?::[^=\n]+?)?\s*(\+=|-=|=)(?!=)")
    last = None
    for m in pat.finditer(masked, 0, before):
        is_decl = re.match(r"\b(?:val|var)\s", masked[m.start():m.end()]) is not None
        rhs_start = m.end()
        end = _expr_end(masked, rhs_start)
        last = (masked[rhs_start:end].strip(), rhs_start, is_decl, m.group(1))
    return last


def find_param_type(masked, name):
    """`name: Type` inside any parameter list (crude, file-local)."""
    for m in re.finditer(r"[,(]\s*" + re.escape(name) + r"\s*:\s*"
                         r"([\w<>?,.\s]+?)\s*(?:,|\)|=)", masked):
        return m.group(1).strip()
    for m in re.finditer(r"^\s*" + re.escape(name) + r"\s*:\s*"
                         r"([\w<>?,.\s]+?)\s*(?:,|\)|=)", masked, re.M):
        return m.group(1).strip()
    return None


def classify_value(masked, expr, value_off, depth_left=3, base=""):
    """(kind, detail, problem|None) for the value expression of EXTRA_DAYS.

    kind ∈ {"ArrayList", "wrong", "unknown"}. `problem` is the violation text.
    Every branch that cannot be resolved returns "unknown" with a violation —
    fail-closed, never a silent pass.
    """
    v = strip_trailing(expr)
    if not v:
        return "unknown", "", ("empty value expression for EXTRA_DAYS — the "
                               "guard cannot verify it")
    head = CALL_HEAD_RE.match(v)
    name = head.group(1).split(".")[-1] if head else None

    # -- recognised correct constructors -------------------------------
    if name in ("arrayListOf", "ArrayList") and call_spans_whole(v, name):
        return "ArrayList", f"{name}()", None
    # ` arrayListOf(...).apply{}`/`also{}` keep the receiver identity: allowed
    # only when the wrapper returns the same object — flag anything else.
    if name in ("arrayListOf", "ArrayList") and not call_spans_whole(v, name):
        return "unknown", v, (f"value `{v}` wraps `{name}()` in an expression "
                              f"the guard cannot type — resolve it to a bare "
                              f"arrayListOf(...) or a typed local")

    # -- recognised wrong containers -----------------------------------
    for wrong, why in WRONG_CONTAINERS.items():
        if name == wrong or re.search(r"\.\s*" + wrong + r"\s*\(", v):
            return "wrong", v, f"value `{v}` produces {why}"
    if re.search(r"\b(?:toCollection|map|filter|distinct|sorted|reversed|"
                 r"plus|minus|union)\b\s*[({]", v) and name not in ("arrayListOf", "ArrayList"):
        return "unknown", v, (f"value `{v}` is a derived collection expression; "
                              f"the guard cannot prove it is a java.util.ArrayList")

    # -- bare identifier / `it`: trace one hop --------------------------
    if IDENT_RE.match(v):
        if v == "this":
            return "ArrayList", "receiver", None
        traced = None
        how = None
        if v == "it":
            recv = lambda_receiver(masked, value_off)
            if recv:
                traced, how = recv, f"?.let/also receiver `{recv}`"
        trace_off = value_off
        if traced is None:
            local = find_local_binding(masked, v, value_off)
            if local is not None:
                rhs, rhs_off, _is_decl, op = local
                if op != "=":
                    return ("wrong", rhs,
                            f"value `{v}` is folded onto `{rhs}` with `{op}` — the "
                            f"result is not verifiable as java.util.ArrayList")
                traced, how, trace_off = rhs, f"local binding `{v} = {strip_trailing(rhs)}`", rhs_off
        if traced is None:
            ptype = find_param_type(masked, v)
            if ptype:
                kind, why = classify_type(ptype, v)
                if kind == "ArrayList":
                    return "ArrayList", f"parameter {v}: {ptype}", None
                return ("wrong" if kind != "unknown" else "unknown", v,
                        (f"value `{v}` is declared `{ptype}` — {why}"
                         if why else None))
            return "unknown", v, (f"could not determine the container for "
                                  f"EXTRA_DAYS (bare `{v}` with no resolvable "
                                  f"binding) — guard cannot verify it")
        if depth_left <= 0:
            return "unknown", v, f"binding chain for `{v}` too deep to resolve"
        # The traced thing may itself be a call or a typed value.
        call = CALL_HEAD_RE.match(strip_trailing(traced))
        if call and call_spans_whole(strip_trailing(traced),
                                     call.group(1).split(".")[-1]):
            cname = call.group(1).split(".")[-1]
            if cname in ("arrayListOf", "ArrayList"):
                return "ArrayList", f"{cname}()", None
            if cname in WRONG_CONTAINERS:
                return "wrong", traced, (f"value `{v}` traces to `{cname}()` — "
                                         f"{WRONG_CONTAINERS[cname]}")
            ret = declared_return(masked, cname)
            if ret is None:
                return "unknown", traced, (f"value `{v}` traces to `{cname}()` "
                                           f"but the guard cannot read its "
                                           f"declared return type")
            kind, why = classify_type(ret, cname)
            if kind == "ArrayList":
                return "ArrayList", f"{cname}() -> {ret}", None
            return ("wrong" if kind != "unknown" else "unknown", traced,
                    f"value `{v}` traces to {how} -> {cname}() : {ret} — {why}")
        kind_hint = classify_type(strip_trailing(traced), v)
        if kind_hint[0] == "unknown":
            return classify_value(masked, traced, trace_off, depth_left - 1,
                                  base)
        if kind_hint[0] == "ArrayList":
            return "ArrayList", f"{how}", None
        return "wrong", traced, f"value `{v}` traces to {how} — {kind_hint[1]}"

    # -- member chain on a known producer (`x.toList()`, `it.toIntArray()`) --
    m = re.match(r"^([A-Za-z_]\w*)\s*\??\s*\.", v)
    if m:
        return ("wrong", v,
                f"value `{v}` is a chain applied to `{m.group(1)}` — the "
                f"container type of the result is not verifiable as "
                f"java.util.ArrayList")

    # -- indexed access / other shapes ----------------------------------
    return "unknown", v, (f"could not determine the container for EXTRA_DAYS "
                          f"(value `{v}`) — guard cannot verify it")


# --------------------------------------------------------------------------
# Writer discovery
# --------------------------------------------------------------------------
KEY_RE = re.compile(r"\bEXTRA_DAYS\b")
PUTE_CALL_RE = re.compile(r"\bputExtra\w*\s*\(")
ALARM_INTENT_RE = re.compile(r"\bACTION_SET_ALARM\b")


def find_writers(masked):
    """Every `putExtra(…EXTRA_DAYS…, <value>)` site.

    Returns triples (offset_of_call, offset_of_value, value_text_or_None,
    error_or_None). The value TEXT is sliced from the parsed argument list —
    re-finding the call by offset afterwards is how a ±N-char window crept
    back in.
    """
    sites = []
    covered = []           # (start, end) of every arg0 we parsed
    for m in PUTE_CALL_RE.finditer(masked):
        open_paren = m.end() - 1
        args, close = split_args(masked, open_paren)
        if args is None:
            sites.append((m.start(), None, None, "unclosed putExtra( call"))
            continue
        arg0_off = open_paren + 1
        if args and KEY_RE.search(args[0]):
            covered.append((arg0_off, arg0_off + len(args[0])))
            if len(args) < 2:
                sites.append((m.start(), None, None,
                              "putExtra(EXTRA_DAYS) with no value argument"))
                continue
            value_off = arg0_off + len(args[0]) + 1
            sites.append((m.start(), value_off, args[1], None))
        for i, a in enumerate(args):
            if i and KEY_RE.search(a):
                sites.append((m.start(), None, None,
                              "EXTRA_DAYS appears outside the KEY argument of "
                              "putExtra — the guard cannot verify what value "
                              "lands in the bundle"))
    # Any EXTRA_DAYS mention in code that no parsed call covered is a writer
    # the guard refuses to bless (registry rule: unknown sites default red).
    for km in KEY_RE.finditer(masked):
        if any(s <= km.start() < e for s, e in covered):
            continue
        sites.append((km.start(), None, None,
                      "EXTRA_DAYS referenced outside a putExtra key position "
                      "— the guard cannot verify it"))
    return sites


def when_arm_dead(subject, cond):
    """`when (false) { true -> … }` / `when (1) { 2 -> … }` — an arm that can
    never be selected, so anything inside it never runs."""
    cond = cond.strip()
    if not cond or cond.startswith("else"):
        return False
    subj = const_value(subject) if subject.strip() else None
    if subj is None:
        return False
    for arm in cond.split(","):
        v = const_value(arm)
        if v is None or v == subj:
            return False
    return True


def dead_site(code, off):
    """True when a constant-false condition governs the write at `off`.

    [wide4 / run_checks] "Is there a putExtra(EXTRA_DAYS, …) in this file" is
    not "does the intent carry the repeat days": `if (false) putExtra(…)` keeps
    every token the old criterion looked for while the alarm silently degrades
    to a one-shot. Covers `if (false)`, `false && …`, `if (!true)`, `if (1 == 2)`
    and the `when (false) { true -> … }` arm family.
    """
    for kind, cond in enclosing_conditions(code, off):
        if kind == "if" and const_bool(cond) is False:
            return True
    for subject, cond, b_start, b_end in when_branches(code):
        if b_start <= off < b_end and when_arm_dead(subject, cond):
            return True
    return False


def check_file(masked, rel):
    """(writer_descriptions, problems) for one source file."""
    problems = []
    found = []
    for off, value_off, expr, err in find_writers(masked):
        line = masked[:off].count("\n") + 1
        if err:
            problems.append(f"{rel}:{line} {err}")
            found.append((line, "error"))
            continue
        if dead_site(masked, off):
            problems.append(
                f"{rel}:{line} the EXTRA_DAYS write sits behind a constant-false "
                f"guard (dead branch) — the intent never carries the repeat days, "
                f"so the alarm replays as a one-shot")
            found.append((line, "dead"))
            continue
        kind, detail, problem = classify_value(masked, expr, value_off,
                                               base=rel)
        if problem:
            problems.append(f"{rel}:{line} passes {detail or expr!r} — {problem}")
        found.append((line, detail if kind == "ArrayList"
                      else f"NOT-ArrayList({kind})"))
    return found, problems


def run_checks(root):
    """Return (writers, problems). Empty tree always yields a problem."""
    writers = []
    problems = []
    kt_files = []
    for path in sorted(Path(root).rglob("*.kt")):
        sp = str(path)
        if "/build/" in sp or "/test/" in sp:
            continue
        kt_files.append(path)
    alarm_files = []
    per_file_writers = {}
    for path in kt_files:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:  # unreadable = fail closed, never skip
            problems.append(f"{path} could not be read ({exc})")
            continue
        rel = str(path.relative_to(root))
        masked, lex_err = code_view(text)
        if masked is None:
            problems.append(
                f"{rel}: the guard cannot lex this Kotlin source ({lex_err}) — "
                f"refusing to judge a file whose structure it cannot read "
                f"(fail closed)")
            continue
        if ALARM_INTENT_RE.search(masked):
            alarm_files.append(rel)
        if "EXTRA_DAYS" not in masked:
            continue
        found, probs = check_file(masked, rel)
        problems.extend(probs)
        writers.extend((rel, ln, kind) for ln, kind in found)
        per_file_writers.setdefault(rel, []).extend(found)

    # C4 (completeness): the floor is per FILE, derived from the tree — every
    # file that builds an ACTION_SET_ALARM intent has to own a verified
    # EXTRA_DAYS writer. Counting calls instead let one file's two branches
    # hold the total up while the other file deleted every writer.
    for rel in alarm_files:
        if not per_file_writers.get(rel):
            problems.append(
                f"{rel} builds an ACTION_SET_ALARM intent but has NO "
                f"EXTRA_DAYS writer — a repeating alarm it starts would "
                f"silently replay as a one-shot (every writer must set the "
                f"repeat days, or the file must be re-pointed in this guard)")
    distinct = {rel for rel, _ln, _k in writers}
    if len(distinct) < 2:
        problems.append(
            f"expected EXTRA_DAYS writers in at least 2 files (MinisApp + "
            f"AlarmOffloadHandler), found {len(distinct)} — a writer file may "
            f"have been removed or renamed; re-point this guard if so"
            + ("" if alarm_files else
               " [no ACTION_SET_ALARM intent found either, so the per-file "
               "floor could not be derived — ROT: re-point this guard]"))
    return writers, problems


def _mk_tree(files, root):
    for rel, text in files.items():
        p = Path(root) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")


SELFTEST_FILES = {
    # ---- clean shapes -------------------------------------------------
    "clean_handler": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    private fun daysForRepeatMode(r: String): ArrayList<Int>? =\n"
            '        when (r) { "DAILY" -> arrayListOf(1, 2); else -> null }\n'
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            '        daysForRepeatMode("DAILY")?.let {\n'
            "            putExtra(AlarmClock.EXTRA_DAYS, it)\n"
            "        } } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(m: Int) { val i = Intent(ACTION_SET_ALARM).apply { when (m) {\n"
            "        1 -> putExtra(AlarmClock.EXTRA_DAYS,\n"
            "            arrayListOf(\n"
            "                Calendar.SUNDAY, Calendar.MONDAY,\n"
            "            ),\n"
            "        )\n"
            "        2 -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(2, 3))\n"
            "        else -> Unit\n"
            "    } } }\n"
            "}\n"),
    },
    "clean_local": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        val days = arrayListOf(1, 2, 3)\n"
            "        putExtra(AlarmClock.EXTRA_DAYS, days)\n"
            "    } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    # ---- witnesses ----------------------------------------------------
    "c1_dead_alias": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        val unused = arrayListOf(1)\n"
            "        putExtra(AlarmClock.EXTRA_DAYS, intArrayOf(1, 2))\n"
            "    } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "c1_window_shift": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f(m: Int) { val i = Intent(ACTION_SET_ALARM).apply { when (m) {\n"
            "        1 -> putExtra(AlarmClock.EXTRA_DAYS, intArrayOf(1, 2))\n"
            "        2 -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(2, 3))\n"
            "        else -> Unit\n"
            "    } } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "c3_to_intarray": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    private fun daysForRepeatMode(r: String): ArrayList<Int>? =\n"
            "        arrayListOf(1, 2)\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        daysForRepeatMode(r)?.let {\n"
            "            putExtra(AlarmClock.EXTRA_DAYS, it.toIntArray())\n"
            "        } } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "c3_to_list_typed": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    private fun daysForRepeatMode(r: String): ArrayList<Int>? =\n"
            "        arrayListOf(1, 2)\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        daysForRepeatMode(r)?.let {\n"
            "            putExtra(AlarmClock.EXTRA_DAYS, it.toList().toTypedArray())\n"
            "        } } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "c4_one_file_dropped": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        // removed ghost alarm EXTRA_DAYS writer\n"
            "    } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(m: Int) { val i = Intent(ACTION_SET_ALARM).apply { when (m) {\n"
            "        1 -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2))\n"
            "        2 -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(2, 3))\n"
            "        else -> Unit\n"
            "    } } }\n"
            "}\n"),
    },
    "wrong_listof": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        putExtra(AlarmClock.EXTRA_DAYS, listOf(1, 2))\n"
            "    } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "producer_intarray": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    private fun daysForRepeatMode(r: String): IntArray? = null\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        daysForRepeatMode(r)?.let {\n"
            "            putExtra(AlarmClock.EXTRA_DAYS, it)\n"
            "        } } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "unknown_value": {
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM).apply {\n"
            "        putExtra(AlarmClock.EXTRA_DAYS, mysteryDays)\n"
            "    } }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    # ---- wide4 witnesses (each one flipped this gate green) -----------
    "w4-var-reassign": {
        # find_local_binding: the declaration says arrayListOf, the LIVE value
        # (last write) is intArrayOf. Declaration-only reading blessed it.
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f() {\n"
            "        var days: java.io.Serializable = arrayListOf(1)\n"
            "        days = intArrayOf(1, 2, 3)\n"
            "        putExtra(android.provider.AlarmClock.EXTRA_DAYS, days)\n"
            "    }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "w4-closed-inner-lambda": {
        # lambda_receiver: an EMPTY nested lambda above makes `rfind("{")`
        # resolve `it` to daysForRepeatMode; the real binding is the int[] one.
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f(r: String) {\n"
            "        intArrayOf(1, 2, 3).let {\n"
            "            daysForRepeatMode(r)?.let {}\n"
            "            putExtra(android.provider.AlarmClock.EXTRA_DAYS, it)\n"
            "        }\n"
            "    }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "w4-raw-string-in-key": {
        # split_args: a raw string in the KEY argument carries `)` and `,`;
        # a single-quote-only tracker closes the call early and judges the
        # decoy arrayListOf(1) instead of the real intArrayOf second argument.
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f(i: Intent) { i.putExtra(\n"
            "        AlarmClock.EXTRA_DAYS.also { val s = \"\"\" \" ), arrayListOf(1)) \"\"\" },\n"
            "        intArrayOf(2, 3, 4, 5, 6),\n"
            "    ) }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "w4-dead-writer": {
        # run_checks: the writer exists but never runs.
        "src/a/MinisApp.kt": (
            "class M {\n"
            "    fun f(r: String) {\n"
            "        daysForRepeatMode(r)?.let {\n"
            "            if (false) putExtra(android.provider.AlarmClock.EXTRA_DAYS, it)\n"
            "        }\n"
            "    }\n"
            "}\n"),
        "src/a/Handler.kt": (
            "class H {\n"
            "    fun g(i: Intent) { i.putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1)) }\n"
            "}\n"),
    },
    "no_writer_at_all": {
        "src/a/Only.kt": (
            "class O {\n"
            "    fun f() { val i = Intent(ACTION_SET_ALARM) }\n"
            "}\n"),
    },
}


def selftest():
    import shutil
    import tempfile

    ARMS = [
        ("clean_handler", 0, []),
        ("clean_local", 0, []),
        ("c1_dead_alias", 1, ["intArrayOf", "IntArray"]),
        ("c1_window_shift", 1, ["intArrayOf"]),
        ("c3_to_intarray", 1, ["toIntArray"]),
        ("c3_to_list_typed", 1, ["toTypedArray"]),
        ("c4_one_file_dropped", 1, ["NO", "EXTRA_DAYS writer"]),
        ("wrong_listof", 1, ["listOf", "Arrays$ArrayList"]),
        ("producer_intarray", 1, ["IntArray"]),
        ("unknown_value", 1, ["could not determine"]),
        ("no_writer_at_all", 1, ["ACTION_SET_ALARM intent but has NO"]),
        # ---- wide4 witnesses: each must flip red on ITS OWN criterion ----
        ("w4-var-reassign", 1, ["IntArray (parcels as int[])"]),
        ("w4-closed-inner-lambda", 1, ["IntArray (parcels as int[])"]),
        ("w4-raw-string-in-key", 1, ["IntArray (parcels as int[])"]),
        ("w4-dead-writer", 1, ["dead branch", "constant-false"]),
    ]
    failures = []
    for key, want_rc, needles in ARMS:
        root = tempfile.mkdtemp(prefix="extra_days_st_")
        try:
            _mk_tree(SELFTEST_FILES[key], root)
            writers, problems = run_checks(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)
        rc = 1 if problems else 0
        out = "\n".join(problems)
        bad = []
        if rc != want_rc:
            bad.append(f"rc={rc} want={want_rc} problems={problems!r}")
        for nd in needles:
            if nd not in out:
                bad.append(f"missing needle {nd!r} in {out!r}")
        if want_rc == 1 and key in ("c1_dead_alias", "c1_window_shift",
                                    "c3_to_intarray", "c3_to_list_typed"):
            # the wrong container must be named by the writer itself, and the
            # compliant file must NOT be reported (precision of the new arm)
            if any("Handler.kt" in p for p in problems):
                bad.append("precision: clean file reported")
        if bad:
            failures.append(f"{key}: {'; '.join(bad)}")
            print(f"  [FAIL] {key}: {'; '.join(bad)}")
        else:
            print(f"  [OK] {key}")
    if failures:
        print(f"EXTRA_DAYS_SELFTEST=FAIL ({len(failures)} arm(s))")
        for f in failures:
            print("  -", f)
        return 1
    print(f"EXTRA_DAYS_SELFTEST=PASS ({len(ARMS)} fixtures)")
    return 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if "--selftest" in sys.argv:
        return selftest()
    root = Path(args[0] if args else ".").resolve()
    writers, problems = run_checks(root)

    print("EXTRA_DAYS writers found:")
    for rel, line, kind in writers:
        print(f"  {rel}:{line}  container={kind}")

    if problems:
        print("\nFAIL: EXTRA_DAYS container type is not ArrayList<Integer>")
        for p in problems:
            print(f"  - {p}")
        return 1

    print("\nOK: every EXTRA_DAYS writer passes a java.util.ArrayList")
    return 0


if __name__ == "__main__":
    sys.exit(main())
