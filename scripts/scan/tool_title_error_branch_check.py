#!/usr/bin/env python3
"""[fix/da1022-G] tool_title_error_branch_check — extended [fix/da1025-G]

Original gate: the five file/memory tools must resolve `tool_title` BEFORE
the try block (FileWriteTool pattern) and every error branch must report the
resolved title — no `toolTitle = NAME` hardcode in catch arms, no silently
dropped third parameter.

[fix/da1025-G] extension (ISSUE-07): the same contract now covers the
browser_use executor and the conversation_history executor:
  ChatToolExecutors.kt : resolveToolTitle BEFORE the parse early-return;
                         both error shapes (`Invalid browser_use input`
                         early return + generic catch) carry toolTitle;
                         the try-scoped inline copy is gone
  ConversationHistoryTool.kt : resolveToolTitle with a TOOL-NAME fallback —
                         `getOrDefault("")` and the single-arg
                         `optString("tool_title")` are both forbidden
  FileWriteTool.kt     : mode tightened inline_or_helper -> helper
                         (single-sourced onto resolveToolTitle)

Code anchors only — comments are stripped before judgment. Anchor matching
is "contains" (not startswith) so `?: return ToolExecutionResult("Error: …")`
early-return lines are checked too.

[deep-audit-1048 B-03] the artifact-persistence contract of the browser_use
executor. `artifactWriter` returns the HOST path it staged the bytes at, or
null when the write failed. Two call sites (screenshot + fetch) must consume
that verdict: a call whose result is not assigned, or whose `minis_url` line
is appended outside the `!= null` guard, ships a URL for a file that does not
exist — the agent then fails at the NEXT read, pointing at the wrong layer.

Usage: python3 tool_title_error_branch_check.py <repo_root>
       python3 tool_title_error_branch_check.py --root <repo_root>
       python3 tool_title_error_branch_check.py --selftest
Exit 0 = pass, 1 = violation, 2 = usage error.
"""
import os
import re
import shutil
import sys
import tempfile

TOOL_DIR = "src/android/app/src/main/java/com/rikkaminis/app/tools"
UI_CHAT_DIR = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat"
MARKER = "TOOL_TITLE_ERROR_BRANCH_SELFTEST=PASS"

# file -> (dir, error-line anchor (None = no anchor loop), resolution kind)
SPEC = {
    "FileWriteTool.kt": (TOOL_DIR, 'ToolExecutionResult("Error writing file:', "helper"),
    "FileReadTool.kt": (TOOL_DIR, 'ToolExecutionResult("Error reading file:', "helper"),
    "FileEditTool.kt": (TOOL_DIR, 'ToolExecutionResult("Error editing file:', "helper"),
    "ReadImageTool.kt": (TOOL_DIR, 'ToolExecutionResult("Error reading image:', "helper"),
    "MemoryTools.kt": (TOOL_DIR, 'ToolResult("Error:', "helper"),
    # [fix/da1025-G] browser_use executor — both error shapes must carry title
    "ChatToolExecutors.kt": (UI_CHAT_DIR, 'ToolExecutionResult("Error:', "helper"),
    # [fix/da1025-G] conversation_history — fallback must be the tool name
    "ConversationHistoryTool.kt": (TOOL_DIR, None, "helper_fallback"),
}


def strip_line_comments(src: str) -> str:
    out_lines = []
    for line in src.split("\n"):
        in_str = False
        i = 0
        cut = len(line)
        while i < len(line):
            c = line[i]
            if c == "\\" and in_str:
                i += 2
                continue
            if c == '"':
                in_str = not in_str
            elif c == "/" and not in_str and i + 1 < len(line) and line[i + 1] == "/":
                cut = i
                break
            i += 1
        out_lines.append(line[:cut])
    return "\n".join(out_lines)


def check_file(source: str, fname: str) -> list:
    src = strip_line_comments(source)
    _, anchor, kind = SPEC[fname]
    errs = []

    if kind == "helper":
        # resolution must exist before the try (code anchors only)
        if "resolveToolTitle(" not in src:
            errs.append("no resolveToolTitle( call found")
        if 'optString("tool_title"' in src:
            errs.append('try-scoped optString("tool_title") still present')
    elif kind == "helper_fallback":
        # [fix/da1025-G] the fallback must be the tool NAME, never ""
        if "resolveToolTitle(" not in src:
            errs.append("no resolveToolTitle( call found")
        if 'optString("tool_title")' in src:
            errs.append('single-arg optString("tool_title") (implicit "" fallback) still present')
        if '.getOrDefault("")' in src:
            errs.append('empty-string fallback .getOrDefault("") still present')
    # (no other kinds; FileWriteTool's inline_or_helper was tightened to helper)

    # every error branch carrying `anchor` must pass the resolved title
    if anchor is not None:
        for line in src.split("\n"):
            t = line.strip()
            if anchor not in t:
                continue
            if "toolTitle = toolTitle" not in t and ", toolTitle)" not in t:
                errs.append(f"error branch does not pass the resolved title: {t}")

    # forbidden hardcoded/empty title in error branches
    if "toolTitle = NAME" in src:
        errs.append("hardcoded toolTitle = NAME still present in an error branch")
    if 'toolTitle = ""' in src:
        errs.append('empty toolTitle still present in an error branch')
    # [wide3-07 C-TITLE] ...and the same rule on the DECLARATION, whatever
    # spelling wraps the constant in. The external witness commented the
    # resolver out and wrote `val toolTitle = (NAME)`: the parenthesized form
    # walks past the `toolTitle = NAME` substring blacklist, and with the
    # comment stripper blind to `//` the resolver call itself stayed visible.
    # Scoped to the declaration on purpose — later `toolTitle =` assignments in
    # this file carry legitimately different sources (`result.toolTitle`,
    # `MEMORY_DISABLED_TOOL_TITLE`, a local `title`), and judging those would
    # flag the corrected code.
    for m in re.finditer(r"\bval\s+toolTitle\s*=\s*([^\n]+)", src):
        core = m.group(1).strip()
        while core.startswith("(") and core.endswith(")"):
            core = core[1:-1].strip()
        if re.fullmatch(r'"[^"]*"', core) or re.fullmatch(r"[A-Z][A-Z0-9_]*", core):
            errs.append(
                "`val toolTitle` is initialized to the constant `%s` instead of the "
                "resolved title — the model's `tool_title` argument is ignored and the "
                "UI card / offload summary falls back to a bare tool name" % core)
    return errs


# ══════════════════════════════════════════════════════════════════════
# decision-point primitives ([wide3-07] hardening)
#
# A guard's SPELLING is not its EFFECT. Both external witnesses of this wave
# kept the call and removed the decision:
#   C-EDIT-ENC  `if (false && isUnpairedSurrogateFailure(content)) { return … }`
#               — the guard call, the message constant and the return all stay
#               in the text, and the write proceeds unguarded.
#   C-ARTIFACT  `if (false) { … minis_url … }` inside the `!= null` block —
#               the append stays inside the guard it is supposed to sit in.
# The primitives below let a criterion ask "can this branch actually decide?"
# instead of "does this token appear?".
# ══════════════════════════════════════════════════════════════════════

def _match_paren(text, open_idx):
    depth = 0
    for i in range(open_idx, len(text)):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
    return None


def _split_top(expr, op):
    parts, depth, last, i = [], 0, 0, 0
    while i < len(expr):
        c = expr[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and expr.startswith(op, i):
            parts.append(expr[last:i])
            last = i + len(op)
            i = last
            continue
        i += 1
    parts.append(expr[last:])
    return parts


def cond_truth(expr):
    """True/False when a LITERAL operand decides the condition, else None."""
    s = expr.strip()
    while s.startswith("(") and s.endswith(")") and _match_paren(s, 0) == len(s) - 1:
        s = s[1:-1].strip()
    if s == "true":
        return True
    if s == "false":
        return False
    if s.startswith("!"):
        inner = cond_truth(s[1:])
        return None if inner is None else (not inner)
    ors = _split_top(s, "||")
    if len(ors) > 1:
        vals = [cond_truth(x) for x in ors]
        if any(v is True for v in vals):
            return True
        if vals and all(v is False for v in vals):
            return False
        return None
    ands = _split_top(s, "&&")
    if len(ands) > 1:
        vals = [cond_truth(x) for x in ands]
        if any(v is False for v in vals):
            return False
        if vals and all(v is True for v in vals):
            return True
        return None
    return None


def dead_guard_spans(text):
    """[(start, end)] of bodies whose controlling condition is literal-FALSE."""
    spans = []
    for m in re.finditer(r"\b(?:if|while)\s*\(", text):
        op = m.end() - 1
        cl = _match_paren(text, op)
        if cl is None or cond_truth(text[op + 1:cl]) is not False:
            continue
        k = cl + 1
        while k < len(text) and text[k] in " \t":
            k += 1
        if k < len(text) and text[k] == "{":
            e = _brace_end(text, k)
            if e is not None:
                spans.append((k, e))
        else:
            eol = text.find("\n", k)
            spans.append((k, eol if eol >= 0 else len(text)))
    return spans


def inside_spans(spans, pos):
    return any(a <= pos <= b for a, b in spans)


def guard_is_live(body, call_pos):
    """True when the guard call at `call_pos` sits in an `if` condition that can
    reject: not literal-decided, and the branch terminates (`return`/`throw`).

    Picks the INNERMOST enclosing `if` — an outer `if (x != null) { if (false &&
    guard(x)) … }` must be judged on the inner condition, not the outer one."""
    best = None
    for m in re.finditer(r"\bif\s*\(", body):
        op = m.end() - 1
        cl = _match_paren(body, op)
        if cl is None or not (op <= call_pos <= cl):
            continue
        if best is None or m.start() > best[0]:
            best = (m.start(), op, cl)
    if best is None:
        return False
    _s, op, cl = best
    if cond_truth(body[op + 1:cl]) is False:
        return False
    k = cl + 1
    while k < len(body) and body[k] in " \t":
        k += 1
    if k < len(body) and body[k] == "{":
        e = _brace_end(body, k)
        branch = body[k:e + 1] if e is not None else ""
    else:
        eol = body.find("\n", k)
        branch = body[k:eol if eol >= 0 else len(body)]
    return re.search(r"\b(return|throw)\b", branch) is not None


# ══════════════════════════════════════════════════════════════════════
# [deep-audit-1047 / A-08] write-shaped tool guards
#
# Two judgments, both DERIVED from the tree (never a hand-listed line):
#
#   C-EDIT-ENC   every disk-write call of a write-shaped tool must be
#                preceded by a UTF-8 validity guard that rejects unpaired
#                surrogates. The guard may live in the tool or in a helper
#                (resolved through the tools package), so a shared helper
#                counts — but the guard must be applied to the SAME content
#                that is written.
#   C-EDIT-BYTES the byte count a write tool reports must come from the
#                written FILE's length, not from a String length/count
#                (UTF-16 code units under-report CJK by up to 3x).
# ══════════════════════════════════════════════════════════════════════

WRITE_TOOLS = ("FileWriteTool.kt", "FileEditTool.kt")

# Disk-write call shapes: (target file, content expression).
WRITE_CALL_RES = (
    re.compile(r"AtomicFileWrite\s*\.\s*write\s*\(\s*([^,\n]+),([^)\n]+)\)"),
    re.compile(r"\b([A-Za-z_]\w*)\s*\.\s*(?:writeText|appendText|writeBytes)\s*\(\s*([^)\n]+)\)"),
)


def strip_kotlin_comments(src: str) -> str:
    """Blank out line AND block comments, preserving line structure.

    Block comments matter here: a KDoc that merely MENTIONS
    `CodingErrorAction.REPORT` or `file.length()` would otherwise satisfy
    the judgment. Line structure is preserved so line-based function
    splitting still works.
    """
    out = []
    i = 0
    n = len(src)
    in_block = False
    while i < n:
        c = src[i]
        if in_block:
            if c == "*" and i + 1 < n and src[i + 1] == "/":
                in_block = False
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            in_block = True
            out.append("  ")
            i += 2
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


FUN_DECL_RE = re.compile(
    r"^\s{0,8}(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+|protected\s+|open\s+|"
    r"override\s+|suspend\s+|inline\s+|final\s+|abstract\s+)*fun\s+([A-Za-z_]\w*)")


def split_functions(src: str):
    """[(name, body)] for every function declaration in `src`.

    Line based rather than brace matched: an expression-bodied function
    (`= try { ... }`) has no opening brace on its signature line, and a
    brace matcher would truncate it at the wrong `}`.
    """
    lines = src.split("\n")
    starts = [i for i, line in enumerate(lines) if FUN_DECL_RE.match(line)]
    out = []
    for si in starts:
        nxt = [s for s in starts if s > si]
        end = nxt[0] if nxt else len(lines)
        name = FUN_DECL_RE.match(lines[si]).group(1)
        out.append((name, "\n".join(lines[si:end])))
    return out


def build_function_table(sources):
    """{name: [(path, body)]} for every function declaration in `sources`.

    `sources` is a list of (path, code) pairs. The encoding guard may
    legitimately live in a shared helper file next to the tool
    (UnencodableContentGuard.kt), so the helper table spans the whole
    package rather than one file.
    """
    table = {}
    for path, code in sources:
        for name, body in split_functions(strip_kotlin_comments(code)):
            table.setdefault(name, []).append((path, body))
    return table


def collect_repo_sources(root: str):
    """[(path, code)] for every .kt under the tools package."""
    out = []
    tools_dir = os.path.join(root, TOOL_DIR)
    for dirpath, dirnames, filenames in os.walk(tools_dir):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                out.append((path, open(path).read()))
            except OSError:
                continue
    return out


# An unpaired surrogate is a MALFORMED input, not an unmappable character:
# the encoder must be told to REPORT on malformed input. Merely finding the
# token `CodingErrorAction.REPORT` anywhere in the body is too loose — a body
# that REPORTS only on unmappable characters while REPLACING malformed ones
# still substitutes '?' for the very surrogates this guard exists to reject,
# and the string check would happily accept it.
MALFORMED_REPORT_RE = re.compile(
    r"onMalformedInput\s*\(\s*CodingErrorAction\s*\.\s*REPORT\s*\)")

def encoding_guard_names(table):
    """Names whose body REJECTS unpaired surrogates.

    Derived from the encoder configuration (`CodingErrorAction.REPORT`), not
    from the name — a helper called `checkText` that actually does the check
    counts, and a helper that merely mentions surrogates in a comment does
    not (comments are stripped). One hop of delegation is followed, so a
    guard that delegates to another helper is still found.
    """
    out = {n for n, entries in table.items()
           if any(MALFORMED_REPORT_RE.search(body) for _p, body in entries)}
    for _ in range(2):
        for n, entries in table.items():
            if n in out:
                continue
            for _p, body in entries:
                if any(re.search(r"(?<![\w.])" + re.escape(g) + r"\s*\(", body)
                       for g in out):
                    out.add(n)
                    break
    return out


def _write_calls(body: str):
    """[(target, content, char_index)] for every disk-write call in `body`."""
    out = []
    for rx in WRITE_CALL_RES:
        for m in rx.finditer(body):
            target, content = m.group(1).strip(), m.group(2).strip()
            out.append((target, content, m.start()))
    return out


def _string_literals_with(body: str, needle: str):
    """Every double-quoted literal in `body` that contains `needle`."""
    out = []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', body):
        if needle in m.group(1):
            out.append(m.group(1))
    return out


def _reported_size_exprs(body: str, targets):
    """[(expr, receiver)] for every size expression the body REPORTS.

    Only expressions that actually reach a user-visible message count: a
    message literal mentioning `bytes` is the anchor, and the identifiers it
    interpolates are resolved back to their `val` definitions. Size
    expressions that are computed but never reported (a post-write
    consistency probe, for instance) are not the judgment's business.
    """
    val_defs = {}
    for m in re.finditer(
            r"val\s+([A-Za-z_]\w*)\s*(?::[^=]+)?=\s*([A-Za-z_]\w*)\s*\.\s*(length|count)\(\)",
            body):
        val_defs[m.group(1)] = (m.group(2), m.group(3))

    exprs = []
    for lit in _string_literals_with(body, "bytes"):
        # ${...} form
        for m in re.finditer(r"\$\{([^{}]*)\}", lit):
            frag = m.group(1).strip()
            direct = re.match(r"^([A-Za-z_]\w*)\s*\.\s*(length|count)\(\)$", frag)
            if direct:
                exprs.append((frag, direct.group(1), direct.group(2)))
                continue
            vm = re.match(r"^([A-Za-z_]\w*)$", frag)
            if vm and vm.group(1) in val_defs:
                recv, kind = val_defs[vm.group(1)]
                exprs.append((f"{vm.group(1)} = {recv}.{kind}()", recv, kind))
        # $name form
        for m in re.finditer(r"\$([A-Za-z_]\w*)", lit):
            nm = m.group(1)
            if nm in val_defs:
                recv, kind = val_defs[nm]
                exprs.append((f"{nm} = {recv}.{kind}()", recv, kind))
    return exprs


def check_write_guard_source(src: str, fname: str, guard_names) -> list:
    """C-EDIT-ENC — every disk write must be guarded, and guarded correctly."""
    errs = []
    code = strip_kotlin_comments(src)
    total = 0
    for fname_fun, body in split_functions(code):
        writes = _write_calls(body)
        if not writes:
            continue
        total += len(writes)
        for target, content, idx in writes:
            guard_ok = False
            guard_dead = False
            for g in sorted(guard_names):
                if g == fname_fun:
                    continue  # a function is not its own guard
                for m in re.finditer(r"(?<![\w.])" + re.escape(g) + r"\s*\(([^)]*)\)", body):
                    if m.start() > idx:
                        break
                    if content and content in m.group(1):
                        if guard_is_live(body, m.start()):
                            guard_ok = True
                            break
                        guard_dead = True
                if guard_ok:
                    break
            if not guard_ok:
                if guard_dead:
                    errs.append(
                        f"{fname}: [C-EDIT-ENC] the guard applied to `{content}` cannot "
                        "reject: its `if` condition is literal-decided (`false && …`) or its "
                        "branch does not terminate (`return`/`throw`) — the write proceeds "
                        "with unpaired surrogates and the encoder substitutes them")
                else:
                    errs.append(
                        f"{fname}: [C-EDIT-ENC] the write of `{content}` is not preceded "
                        "by a UTF-8 validity guard (CodingErrorAction.REPORT) applied to "
                        "that same content — unpaired surrogates are silently "
                        "substituted with '?' by the encoder and the file lands corrupted")
    if total == 0:
        # fail closed: a write-shaped tool with no recognizable write call
        # means the derivation broke, not that the tool is safe
        errs.append(
            f"{fname}: [C-EDIT-ENC] no disk-write call derived from the tree "
            "(cannot verify the encoding guard)")
    # de-duplicate (the same write shape can match two call forms)
    seen, uniq = set(), []
    for e in errs:
        if e in seen:
            continue
        seen.add(e)
        uniq.append(e)
    return uniq


def check_byte_unit_source(src: str, fname: str) -> list:
    """C-EDIT-BYTES — the reported byte count must be the FILE's length."""
    errs = []
    code = strip_kotlin_comments(src)
    for _fname_fun, body in split_functions(code):
        writes = _write_calls(body)
        if not writes:
            continue
        targets = {t for t, _c, _i in writes}
        exprs = _reported_size_exprs(body, targets)
        if not exprs:
            # the tool reports no size at all — not this judgment's business
            continue
        for expr, recv, kind in exprs:
            if kind == "count":
                errs.append(
                    f"{fname}: [C-EDIT-BYTES] the reported size uses `{expr}` — "
                    "`.count()` is a character count, never a byte count")
                continue
            if recv not in targets:
                errs.append(
                    f"{fname}: [C-EDIT-BYTES] the reported size comes from "
                    f"`{expr}` — it must be the length of the written file "
                    f"({', '.join(sorted(targets))}), not a String length "
                    "(UTF-16 code units under-report CJK by up to 3x)")
    seen, uniq = set(), []
    for e in errs:
        if e in seen:
            continue
        seen.add(e)
        uniq.append(e)
    return uniq


def check_write_guards(root: str) -> list:
    """C-EDIT-ENC + C-EDIT-BYTES over the write-shaped tools."""
    errs = []
    sources = collect_repo_sources(root)
    table = build_function_table(sources)
    guard_names = encoding_guard_names(table)
    for fname in WRITE_TOOLS:
        path = os.path.join(root, TOOL_DIR, fname)
        try:
            src = open(path).read()
        except OSError as e:
            errs.append(f"cannot read {fname}: {e}")
            continue
        errs.extend(check_write_guard_source(src, fname, guard_names))
        errs.extend(check_byte_unit_source(src, fname))
    return errs


ARTIFACT_CALL_RE = re.compile(r"\bartifactWriter\s*\(")
ARTIFACT_ASSIGN_RE = re.compile(r"^\s*val\s+(\w+)\s*=\s*artifactWriter\s*\(")

def _brace_end(text, open_idx):
    depth = 0
    for j in range(open_idx, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return j
    return None

def check_artifact_writer_src(src: str) -> list:
    """[deep-audit-1048 B-03] every artifactWriter verdict must be consumed.

    Decision points, not spellings: the call must be assigned to a local, that
    local must be tested against null, and the `minis_url` append for the same
    artifact must live INSIDE that guard (so a failed write cannot advertise a
    URL). Fail-closed when the executor has no such call at all — the subject
    moved."""
    code = strip_kotlin_comments(src)
    problems = []
    sites = 0
    for m in ARTIFACT_CALL_RE.finditer(code):
        sites += 1
        line_start = code.rfind("\n", 0, m.start()) + 1
        line_end = code.find("\n", m.end())
        line = code[line_start: line_end if line_end >= 0 else len(code)]
        assign = ARTIFACT_ASSIGN_RE.match(line)
        if not assign:
            problems.append("artifactWriter() result discarded — a failed write still "
                            "reports success to the agent: %s" % line.strip())
            continue
        name = assign.group(1)
        rest = code[m.end():]
        guard = re.search(r"if\s*\(\s*%s\s*!=\s*null\s*\)\s*\{" % re.escape(name), rest)
        if not guard:
            problems.append("artifactWriter() verdict `%s` is never tested against null, so "
                            "the minis_url line is emitted unconditionally" % name)
            continue
        end = _brace_end(rest, guard.end() - 1)
        block = rest[guard.start(): end if end is not None else len(rest)]
        if "minis_url" not in block and "linuxPathToMinisURL" not in block:
            problems.append("artifactWriter() `%s`: the minis_url append sits OUTSIDE the "
                            "`%s != null` guard" % (name, name))
        else:
            # Inside the guard is not the same as reachable: a literal-decided
            # wrapper (`if (false) { … minis_url … }`) keeps the token inside the
            # guard block and never runs (external witness C-ARTIFACT).
            dead = dead_guard_spans(block)
            live = []
            for m in re.finditer(r"minis_url", block):
                if inside_spans(dead, m.start()):
                    continue
                ls = block.rfind("\n", 0, m.start()) + 1
                le = block.find("\n", m.start())
                line = block[ls: le if le >= 0 else len(block)]
                if "output" in line:
                    live.append(line)
            if not live:
                problems.append(
                    "artifactWriter() `%s`: the minis_url append cannot run — it sits inside "
                    "a literal-decided guard (`if (false) …`) or no longer assigns to "
                    "`output`, so a persisted artifact is never advertised to the agent"
                    % name)
    if sites == 0:
        problems.append("ChatToolExecutors.kt: no artifactWriter( call found — the artifact "
                        "persistence moved, so this criterion saw nothing to check")
    return problems

def check_artifact_writer(root: str) -> list:
    path = os.path.join(root, UI_CHAT_DIR, "ChatToolExecutors.kt")
    try:
        src = open(path).read()
    except OSError as e:
        return ["ChatToolExecutors.kt: cannot read (%s)" % e]
    return ["ChatToolExecutors.kt: " + p for p in check_artifact_writer_src(src)]

def check(root: str) -> list:
    errs = []
    for fname, (fdir, _, _) in SPEC.items():
        path = os.path.join(root, fdir, fname)
        try:
            src = open(path).read()
        except OSError as e:
            errs.append(f"cannot read {fname}: {e}")
            continue
        for e in check_file(src, fname):
            errs.append(f"{fname}: {e}")
    # [deep-audit-1047 / A-08] write-shaped tool guards (encoding + byte unit)
    errs.extend(check_write_guards(root))
    # [deep-audit-1048 / B-03] artifact persistence verdict must be consumed
    errs.extend(check_artifact_writer(root))
    # MemoryTools: both functions must resolve the title
    path = os.path.join(root, TOOL_DIR, "MemoryTools.kt")
    try:
        src = strip_line_comments(open(path).read())
        n = src.count("resolveToolTitle(")
        if n < 2:
            errs.append(f"MemoryTools.kt: resolveToolTitle( found {n} times, expected 2 (write + get)")
    except OSError:
        pass
    return errs


def selftest() -> int:
    good_exec = """
        fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
            val toolTitle = resolveToolTitle(argsJson, NAME)
            return try {
                val args = JSONObject(argsJson)
                ToolExecutionResult("ok", true, toolTitle = toolTitle)
            } catch (e: Exception) {
                ToolExecutionResult("Error reading file: ${e.message}", false, toolTitle = toolTitle)
            }
        }
    """
    bad_hardcode = good_exec.replace(
        'ToolExecutionResult("Error reading file: ${e.message}", false, toolTitle = toolTitle)',
        'ToolExecutionResult("Error reading file: ${e.message}", false, toolTitle = NAME)',
    )
    bad_dropped = good_exec.replace(
        'ToolExecutionResult("Error reading file: ${e.message}", false, toolTitle = toolTitle)',
        'ToolExecutionResult("Error reading image: ${e.message}", false)',
    )
    bad_tryscoped = """
        fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
            return try {
                val args = JSONObject(argsJson)
                val toolTitle = args.optString("tool_title", NAME)
                ToolExecutionResult("ok", true, toolTitle = toolTitle)
            } catch (e: Exception) {
                ToolExecutionResult("Error editing file: ${e.message}", false, toolTitle = NAME)
            }
        }
    """
    good_memory = """
        fun executeMemoryWrite(inputJson: String, repository: MemoryRepository): ToolResult {
            val toolTitle = resolveToolTitle(inputJson, "memory_write")
            return try {
                ToolResult("ok", true, toolTitle)
            } catch (e: Exception) {
                ToolResult("Error: ${e.message}", false, toolTitle)
            }
        }
        fun executeMemoryGet(inputJson: String, repository: MemoryRepository): ToolResult {
            val toolTitle = resolveToolTitle(inputJson, "memory_get")
            return try {
                ToolResult("ok", true, toolTitle)
            } catch (e: Exception) {
                ToolResult("Error: ${e.message}", false, toolTitle)
            }
        }
    """
    bad_memory = good_memory.replace(
        'ToolResult("Error: ${e.message}", false, toolTitle)',
        'ToolResult("Error: ${e.message}", false)',
    )

    # ── [fix/da1025-G] browser_use executor (ChatToolExecutors.kt) ──────
    good_browser = """
        val toolTitle = resolveToolTitle(argsJson, "browser_use")
        val parsed = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser_use input", false, toolTitle = toolTitle)
        return try {
            val result = tabPool.execute(input)
            ToolExecutionResult(output = result.text, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)
        }
    """
    # V5: early-return loses the title — exactly the error-branch criterion fires
    bad_browser_early = good_browser.replace(
        'ToolExecutionResult("Error: Invalid browser_use input", false, toolTitle = toolTitle)',
        'ToolExecutionResult("Error: Invalid browser_use input", false)',
    )
    # V6: catch arm loses the title
    bad_browser_catch = good_browser.replace(
        'ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle)',
        'ToolExecutionResult("Error: ${e.message}", false)',
    )
    # V7: try-scoped inline copy (the pre-fix shape) — resolution + optString both fire
    bad_browser_inline = """
        val parsed = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser_use input", false)
        return try {
            val result = tabPool.execute(input)
            val toolTitle = try {
                JSONObject(argsJson).optString("tool_title", "browser_use")
            } catch (_: Exception) { "browser_use" }
            ToolExecutionResult(output = result.text, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false)
        }
    """

    # ── [fix/da1025-G] conversation_history (ConversationHistoryTool.kt) ─
    good_convhist = """
        val toolTitle = resolveToolTitle(argsJson, ConversationHistoryContract.NAME)
        return when (val request = parseConversationHistoryRequest(argsJson)) {
            is ConversationHistoryRequest.Invalid -> ToolExecutionResult(
                output = "Error: " + request.message,
                success = false,
                toolTitle = toolTitle,
            )
            else -> ToolExecutionResult(output = "ok", success = true, toolTitle = toolTitle)
        }
    """
    # V8: empty-string fallback via getOrDefault("") — exactly that criterion
    bad_convhist_empty = """
        val toolTitle = resolveToolTitle(argsJson, ConversationHistoryContract.NAME)
        val legacy = runCatching { JSONObject(argsJson).optString("tool_title", "conversation_history") }.getOrDefault("")
        return when (val request = parseConversationHistoryRequest(argsJson)) {
            is ConversationHistoryRequest.Invalid -> ToolExecutionResult(
                output = "Error: " + request.message,
                success = false,
                toolTitle = legacy,
            )
            else -> ToolExecutionResult(output = "ok", success = true, toolTitle = legacy)
        }
    """
    # V9: single-arg optString (implicit "" fallback) — exactly that criterion
    bad_convhist_singlearm = """
        val toolTitle = resolveToolTitle(argsJson, ConversationHistoryContract.NAME)
        val legacy = args.optString("tool_title")
        return ToolExecutionResult(output = "ok", success = true, toolTitle = legacy)
    """
    # V10: the full pre-fix shape — no resolveToolTitle, single-arg + empty fallback
    bad_convhist_prefix = """
        val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title") }.getOrDefault("")
        return ToolExecutionResult(output = "ok", success = true, toolTitle = toolTitle)
    """

    # ── [fix/da1025-G] FileWriteTool tightened to helper (single-source) ─
    good_filewrite = """
        fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
            val toolTitle = resolveToolTitle(argsJson, NAME)
            return try {
                val args = JSONObject(argsJson)
                ToolExecutionResult("ok", true, toolTitle = toolTitle)
            } catch (e: Exception) {
                ToolExecutionResult("Error writing file: ${e.message}", false, toolTitle = toolTitle)
            }
        }
    """
    # [wide3-07 C-TITLE] the resolver is COMMENTED OUT and the title is a
    # parenthesized constant — the two bugs at once: the comment stripper never
    # stripped `//` (so the resolver call stayed visible) and the blacklist only
    # knew the bare `toolTitle = NAME` spelling.
    bad_title_paren = good_exec.replace(
        "        val toolTitle = resolveToolTitle(argsJson, NAME)",
        '        // val toolTitle = resolveToolTitle(argsJson, NAME)\n'
        '        val toolTitle = (NAME)',
    )

    # V11: the inline runCatching copy (non-single-source) — red under helper mode
    bad_filewrite_inline = good_filewrite.replace(
        "val toolTitle = resolveToolTitle(argsJson, NAME)",
        'val toolTitle = runCatching { JSONObject(argsJson).optString("tool_title", NAME) }\n'
        "            .getOrDefault(NAME)",
    )


    # ── [deep-audit-1047 / A-08] write-tool guard fixtures ────────────────
    # The judged surface is DERIVED from the tool sources: the disk-write
    # calls, the guard applied to them, and the size expression the tool
    # REPORTS. The fixtures below are minimally-shaped stand-ins whose only
    # job is to make one arm flip.

    GOOD_EDIT = """
package com.rikkaminis.app.tools

import java.io.File

class FileEditTool {
    fun execute(argsJson: String, path: String, content: String): ToolExecutionResult {
        val file = File(path)
        val newContent = content.replace("a", "b")
        if (isUnpairedSurrogateFailure(newContent)) {
            return ToolExecutionResult(UNENCODABLE_CONTENT_MESSAGE, false, toolTitle = toolTitle)
        }
        AtomicFileWrite.write(file, newContent)
        val bytes = file.length()
        return ToolExecutionResult("Edited $path ($bytes bytes)", true, toolTitle = toolTitle)
    }
}
"""

    # [wide3-07 C-EDIT-ENC] the guard call, the message and the return all stay;
    # only the DECISION is short-circuited away.
    DEAD_GUARD_EDIT = GOOD_EDIT.replace(
        "if (isUnpairedSurrogateFailure(newContent)) {",
        "if (false && isUnpairedSurrogateFailure(newContent)) {")

    # The guard is gone — the pre-fix file_edit shape.
    NO_ENC_EDIT = GOOD_EDIT.replace(
        """        if (isUnpairedSurrogateFailure(newContent)) {
            return ToolExecutionResult(UNENCODABLE_CONTENT_MESSAGE, false, toolTitle = toolTitle)
        }
""", "")

    # The guard survives but is applied to a DIFFERENT string than the one
    # written — the content that reaches disk is still unguarded.
    WRONG_CONTENT_EDIT = GOOD_EDIT.replace(
        "isUnpairedSurrogateFailure(newContent)", "isUnpairedSurrogateFailure(content)")

    # The reported size comes from the string, not the file.
    STRING_LENGTH_EDIT = GOOD_EDIT.replace(
        "val bytes = file.length()", "val bytes = newContent.length()")

    # A character count is never a byte count.
    CHAR_COUNT_EDIT = GOOD_EDIT.replace(
        "val bytes = file.length()", "val bytes = newContent.count()")

    # The guard is delegated to a helper the file calls — must stay GREEN.
    HELPER_EDIT = """
package com.rikkaminis.app.tools

import java.io.File

class FileEditTool {
    fun execute(argsJson: String, path: String, content: String): ToolExecutionResult {
        val file = File(path)
        val newContent = content.replace("a", "b")
        if (!isEncodableUtf8(newContent)) {
            return ToolExecutionResult("Error: unencodable", false, toolTitle = toolTitle)
        }
        AtomicFileWrite.write(file, newContent)
        val bytes = file.length()
        return ToolExecutionResult("Edited $path ($bytes bytes)", true, toolTitle = toolTitle)
    }
}
"""

    # A KDoc that merely NAMES the encoder configuration must not satisfy the
    # judgment (comments are stripped before judging).
    COMMENT_ONLY_EDIT = GOOD_EDIT.replace(
        "        if (isUnpairedSurrogateFailure(newContent)) {",
        "        // see CodingErrorAction.REPORT below\n"
        "        if (false) {")

    def write_arms():
        """Grade the [A-08] arms on an isolated fixture tree.

        Returns the number of failed arms — this selftest counts failures in
        `failures`, not `bad` (that is the parts_json gate's variable), so the
        arm runner must not assume a `nonlocal` binding that does not exist.
        """
        failed = 0

        def arm(name, edit_body, helper_body, want_enc, want_bytes):
            tmp = tempfile.mkdtemp(prefix="enc-arm-")
            try:
                tdir = os.path.join(tmp, TOOL_DIR)
                os.makedirs(tdir, exist_ok=True)
                with open(os.path.join(tdir, "FileEditTool.kt"), "w") as f:
                    f.write(edit_body)
                # The sibling write tool must call the SAME guard the helper
                # provides — otherwise this arm would fail for a reason that
                # has nothing to do with the judgment under test.
                gname = ("isEncodableUtf8" if helper_body is not None
                         and "isEncodableUtf8" in helper_body
                         else "isUnpairedSurrogateFailure")
                with open(os.path.join(tdir, "FileWriteTool.kt"), "w") as f:
                    f.write("""
package com.rikkaminis.app.tools

import java.io.File

class FileWriteTool {
    fun execute(argsJson: String, path: String, content: String): ToolExecutionResult {
        val file = File(path)
        if (GUARDNAME(content)) {
            return ToolExecutionResult(UNENCODABLE_CONTENT_MESSAGE, false, toolTitle = toolTitle)
        }
        AtomicFileWrite.write(file, content)
        val bytes = file.length()
        return ToolExecutionResult("Wrote to $path ($bytes bytes)", true, toolTitle = toolTitle)
    }
}
""".replace("GUARDNAME", gname))
                if helper_body is not None:
                    with open(os.path.join(tdir, "UnencodableContentGuard.kt"), "w") as f:
                        f.write(helper_body)
                errs = check_write_guards(tmp)
            finally:
                shutil.rmtree(tmp, ignore_errors=True)
            enc = [e for e in errs if "C-EDIT-ENC" in e]
            byt = [e for e in errs if "C-EDIT-BYTES" in e]
            ok = True
            if want_enc == "FAIL" and not enc:
                print(f"  SELFTEST {name}: FAIL (expected C-EDIT-ENC to fire, got none)")
                ok = False
            if want_enc == "PASS" and enc:
                print(f"  SELFTEST {name}: FAIL (expected C-EDIT-ENC green, got {enc[0][:120]})")
                ok = False
            if want_bytes == "FAIL" and not byt:
                print(f"  SELFTEST {name}: FAIL (expected C-EDIT-BYTES to fire, got none)")
                ok = False
            if want_bytes == "PASS" and byt:
                print(f"  SELFTEST {name}: FAIL (expected C-EDIT-BYTES green, got {byt[0][:120]})")
                ok = False
            if ok:
                print(f"  SELFTEST {name}: OK (enc={'red' if enc else 'green'}, bytes={'red' if byt else 'green'})")
            else:
                failed += 1

        # --- the arms themselves (hand-written expectations, never derived
        # from the run's own output) ---
        # A compliant tree: both judgments green.
        arm("a08-compliant", GOOD_EDIT, GUARD_HELPER, "PASS", "PASS")
        # [wide3-07] Teeth 0: the guard is dead (`false &&`) -> only C-EDIT-ENC
        # fires, and it must say the guard cannot reject.
        arm("a08-dead-guard", DEAD_GUARD_EDIT, GUARD_HELPER, "FAIL", "PASS")
        # Teeth 1: the encoding guard is deleted -> only C-EDIT-ENC fires.
        arm("a08-guard-removed", NO_ENC_EDIT, GUARD_HELPER, "FAIL", "PASS")
        # Teeth 2: the guard survives but guards a DIFFERENT string than the
        # one written -> C-EDIT-ENC must still fire.
        arm("a08-guard-wrong-content", WRONG_CONTENT_EDIT, GUARD_HELPER, "FAIL", "PASS")
        # Teeth 3: the reported size comes from the String -> only
        # C-EDIT-BYTES fires.
        arm("a08-string-length", STRING_LENGTH_EDIT, GUARD_HELPER, "PASS", "FAIL")
        # Teeth 4: a character count is never a byte count.
        arm("a08-char-count", CHAR_COUNT_EDIT, GUARD_HELPER, "PASS", "FAIL")
        # Precision: the guard delegated to a differently-NAMED helper that
        # still configures the encoder -> stays green (the criterion keys on
        # the encoder configuration, not on a memorized function name).
        arm("a08-helper-delegation", HELPER_EDIT, GUARD_HELPER_ALT, "PASS", "PASS")
        # Precision: a KDoc that merely NAMES the encoder configuration must
        # not satisfy the judgment (comments are stripped before judging).
        arm("a08-comment-only", COMMENT_ONLY_EDIT, GUARD_HELPER, "FAIL", "PASS")
        return failed

    GUARD_HELPER = """
package com.rikkaminis.app.tools

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal fun isUnpairedSurrogateFailure(content: String): Boolean =
    try {
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(content))
        false
    } catch (e: CharacterCodingException) {
        true
    }

internal const val UNENCODABLE_CONTENT_MESSAGE = "Error: unpaired surrogates"
"""

    # A second helper name that also does the check — the criterion must key on
    # the encoder configuration, not on a memorized function name.
    GUARD_HELPER_ALT = GUARD_HELPER.replace("isUnpairedSurrogateFailure", "isEncodableUtf8")

    arms = [
        ("compliant exec", "FileReadTool.kt", good_exec, 0),
        ("V1 catch hardcode NAME", "FileReadTool.kt", bad_hardcode, 2),
        ("V2 error branch drops toolTitle", "ReadImageTool.kt", bad_dropped, 1),
        ("V3 try-scoped val + hardcode", "FileEditTool.kt", bad_tryscoped, 4),
        ("compliant memory", "MemoryTools.kt", good_memory, 0),
        ("V4 memory catch drops toolTitle", "MemoryTools.kt", bad_memory, 2),
        ("compliant browser_use", "ChatToolExecutors.kt", good_browser, 0),
        ("V5 browser early-return drops title", "ChatToolExecutors.kt", bad_browser_early, 1),
        ("V6 browser catch drops title", "ChatToolExecutors.kt", bad_browser_catch, 1),
        ("V7 browser try-scoped inline copy", "ChatToolExecutors.kt", bad_browser_inline, 4),
        ("compliant conversation_history", "ConversationHistoryTool.kt", good_convhist, 0),
        ("V8 convhist empty getOrDefault", "ConversationHistoryTool.kt", bad_convhist_empty, 1),
        ("V9 convhist single-arg optString", "ConversationHistoryTool.kt", bad_convhist_singlearm, 1),
        ("V10 convhist full pre-fix shape", "ConversationHistoryTool.kt", bad_convhist_prefix, 3),
        ("compliant file_write (helper)", "FileWriteTool.kt", good_filewrite, 0),
        ("V11 file_write inline copy (non-single-source)", "FileWriteTool.kt", bad_filewrite_inline, 2),
        # [wide3-07] C-TITLE witness: `// val toolTitle = resolveToolTitle(…)`
        # + `val toolTitle = (NAME)` -> the missing resolver AND the constant.
        ("V12 commented resolver + parenthesized NAME", "FileReadTool.kt", bad_title_paren, 2),
    ]
    # ── [deep-audit-1048 B-03] artifactWriter verdict ─────────────────────
    GOOD_ARTIFACT = "\n".join([
        '        if (base64 != null) {',
        '            val filename = "screenshot_1.jpg"',
        '            val persistPath = artifactWriter(filename, raw)',
        '            if (persistPath != null) {',
        '                linuxPathToMinisURL("/var/minis/browser/$filename")?.let {',
        '                    output = "$output\\nminis_url: $it"',
        '                }',
        '            }',
        '        }',
        '        if (fetchData != null && fetchName != null) {',
        '            val fetchPersistPath = artifactWriter(fetchName, fetchData)',
        '            if (fetchPersistPath != null) {',
        '                linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {',
        '                    output = "$output\\nminis_url: $it"',
        '                }',
        '            } else {',
        '                output = "$output\\n(fetched file not persisted: $fetchName)"',
        '            }',
        '        }',
        '    ',
    ])
    # B-03-V1: the pre-fix fetch arm — result discarded, URL still appended.
    ARTIFACT_DISCARDED = GOOD_ARTIFACT.replace(
        '            val fetchPersistPath = artifactWriter(fetchName, fetchData)\n'
        '            if (fetchPersistPath != null) {\n'
        '                linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {\n'
        '                    output = "$output\\nminis_url: $it"\n'
        '                }\n'
        '            } else {\n'
        '                output = "$output\\n(fetched file not persisted: $fetchName)"\n'
        '            }\n',
        '            artifactWriter(fetchName, fetchData)\n'
        '            linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {\n'
        '                output = "$output\\nminis_url: $it"\n'
        '            }\n')
    # B-03-V2: assigned + guarded, but the URL line escaped the guard.
    ARTIFACT_URL_OUTSIDE = GOOD_ARTIFACT.replace(
        '            if (fetchPersistPath != null) {\n'
        '                linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {\n'
        '                    output = "$output\\nminis_url: $it"\n'
        '                }\n'
        '            } else {\n',
        '            if (fetchPersistPath != null) {\n'
        '                output = "$output\\n(persisted)"\n'
        '            }\n'
        '            linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {\n'
        '                output = "$output\\nminis_url: $it"\n'
        '            }\n'
        '            if (false) {\n')
    # [wide3-07 C-ARTIFACT] the append stays INSIDE the `!= null` guard but is
    # wrapped in a literal-decided block — the token is where the old check
    # looked, the code never runs.
    ARTIFACT_DEAD_URL = GOOD_ARTIFACT.replace(
        '            if (fetchPersistPath != null) {\n'
        '                linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {\n'
        '                    output = "$output\\nminis_url: $it"\n'
        '                }\n',
        '            if (fetchPersistPath != null) {\n'
        '                if (false) {\n'
        '                    linuxPathToMinisURL("/var/minis/browser/$fetchName")?.let {\n'
        '                        output = "$output\\nminis_url: $it"\n'
        '                    }\n'
        '                }\n')
    # B-03-V3: the verdict is never tested at all.
    ARTIFACT_UNTESTED = GOOD_ARTIFACT.replace(
        '            if (fetchPersistPath != null) {\n',
        '            if (true) {\n')

    for arm_name, sample, want in (
        ("b03-compliant", GOOD_ARTIFACT, 0),
        ("b03-discarded verdict", ARTIFACT_DISCARDED, 1),
        ("b03-url outside the guard", ARTIFACT_URL_OUTSIDE, 1),
        ("b03-verdict never tested", ARTIFACT_UNTESTED, 1),
        ("b03-dead-guarded url append", ARTIFACT_DEAD_URL, 1),
    ):
        got = check_artifact_writer_src(sample)
        if len(got) == want:
            print(f"  OK {arm_name}: {got if got else 'green'}")
        else:
            print(f"  FAIL {arm_name}: want {want} problem(s), got {len(got)}: {got}")
            failures += 1

    failures = write_arms()

    for name, fname, sample, expect in arms:
        errs = [f"{fname}: {e}" for e in check_file(sample, fname)]
        if expect == 0 and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        elif expect != 0 and len(errs) != expect:
            print(f"  FAIL {name}: expected {expect} error(s), got {len(errs)}: {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs if errs else 'green'}")
    if failures:
        print(f"SELFTEST FAILED ({failures} arms)")
        return 1
    print(MARKER)
    return 0


def main() -> int:
    args = [a for a in sys.argv[1:]]
    if "--selftest" in args:
        return selftest()
    root = None
    if "--root" in args:
        i = args.index("--root")
        if i + 1 >= len(args):
            print("usage: --root <repo_root>", file=sys.stderr)
            return 2
        root = args[i + 1]
    elif len(args) == 1:
        root = args[0]
    else:
        print("usage: <repo_root> | --root <repo_root> | --selftest", file=sys.stderr)
        return 2
    errs = check(root)
    if errs:
        print("tool_title_error_branch_check RED:")
        for e in errs:
            print(f"  - {e}")
        return 1
    print("tool_title_error_branch_check OK (7 files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
