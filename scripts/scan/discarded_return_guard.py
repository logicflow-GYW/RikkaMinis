#!/usr/bin/env python3
"""Discarded-return guard — RikkaMinis CI scan gate.

## Why this gate exists

Some APIs in this repo are immutable-by-copy: the call returns a NEW object and
the receiver is untouched (ProviderHealthTracker.recordFirstToken is the one that
already bit). A call written in STATEMENT position therefore compiles, returns a
value nobody reads, and silently does nothing — the observer runs, the probe
fires, the log line looks right, and the state never changes.

2026-09-26: `ProviderHealthTraceListener.responseHeadersStart` called
`ProviderHealthTracker.recordFirstToken(it, ms())` as a statement. TTFB was
never recorded, so `budget(realHistory, routeDefault = 30s)` stayed at the
default forever and every route was judged against 30 s. Every gate was green:
it compiled, the four-way sync was complete, the i18n keys matched. A probe
comparing "attempts=1 p50=null recordedTtfbs=[]" against the control run
"recordedTtfbs=[8000]" is what proved it — and no static gate asks that question.

## What it flags

A statement-position call to a function in [WATCHLIST] — the call starts the line
after indentation, optionally through a receiver (`x.`, `x?.`) or a qualified
path. Skipped (they consume the value or are not calls):

  * declarations (`fun recordFirstToken(...)`) and comment lines;
  * assignments / returns / argument positions (the pattern does not match);
  * a call that is the body of a value-consuming lambda — `let`, `run`, `with`,
    `map`, `mapNotNull`, `flatMap`, `fold` — **but only when that lambda's own
    value is used** (assigned, returned, or in argument position). `also` /
    `apply` / `forEach` are never in that list: they return the receiver (or
    Unit), so a value dropped inside them is still dropped. A statement-position
    `x?.let { recordFirstToken(…) }` is flagged: the `let`'s value is dropped,
    which is exactly the shape that shipped (an earlier revision of this gate
    exempted it and could not see its own counter-example — the reverse-control
    run against the pre-fix source is what caught that).

Exempt a line inline with `record-ok: <reason>` (the repo's `debug-ok:`
convention). Adding a watchlist entry is the maintenance path when another
value-returning recorder is discovered.

Usage:
  python3 scripts/scan/discarded_return_guard.py <repo_root>
  python3 scripts/scan/discarded_return_guard.py --self-test
"""

import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import _kotlin_ast as kast                                              # noqa: E402
from _kotlin_lexer import KotlinLexError                                # noqa: E402
from _kotlin_valueflow import (                                         # noqa: E402
    call_args,
    code_view,
    contains_ident,
    expr_end,
    literal_view,
    value_sources,
)

SRC_SUBDIR = "src/android/app/src/main/java"

# [R3-t11] Arm tally for the registration marker the main session greps for.
# Every assertion a --selftest run makes is counted once, so the printed count
# cannot drift away from the arms that actually ran.
_ARM_COUNT = [0]

# name -> why the returned value must be consumed
WATCHLIST = {
    "recordFirstToken": (
        "returns a COPY carrying the TTFB; the tracker is immutable-by-copy, so a "
        "statement-position call records nothing (2026-09-26 TTFB wire-up defect)"
    ),
    "copySharedStream": (
        "returns the staging OUTCOME (Staged / Failed); a statement-position call "
        "drops the only signal that a file was written, so the caller goes back to "
        "handing the composer a filename for a file that was never created "
        "(§1044-A08 ghost attachment)"
    ),
}

EXEMPT = "record-ok:"
# Watchlist names that are legitimately called as bare top-level functions
# (no receiver) — for those the optional-receiver pattern must still match a
# statement-position call.
BARE_OK = {"copySharedStream"}
_CALL = re.compile(
    r"^\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*\??\.?\s*%s\s*\("
)
_DECL = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public |private |internal |protected |override |suspend "
    r"|inline |operator |abstract |open )*fun\s"
)
_COMMENT = re.compile(r"^\s*(?://|\*|/\*)")
_CONSUMING_LAMBDA = re.compile(
    r"\b(?:let|run|with|map|mapNotNull|flatMap|fold|sumOf)\s*\{\s*$"
)


def _pattern(name):
    if name in BARE_OK:
        # The receiver is optional for this name: the extracted core is called
        # as a top-level function, and a statement-position call of a
        # top-level function is exactly the shape that must be caught.
        return re.compile(
            r"^\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*%s\s*\(" % re.escape(name)
        )
    return re.compile(_CALL.pattern % re.escape(name))


def _enclosing_open(lines, i):
    """Innermost enclosing block opener above line i (nearest `... {` line)."""
    for j in range(i - 1, -1, -1):
        stripped = lines[j].strip()
        if not stripped or stripped.startswith(("//", "*", "/*")):
            continue
        if stripped.endswith("{"):
            return j, lines[j]
        return -1, ""
    return -1, ""


def _consumes_lambda_value(open_line):
    """True only when the lambda's own value is used.

    `x?.let { … }` written as a STATEMENT drops the lambda's value, so a
    watchlisted call inside it is dropped too — that is the exact shape of the
    2026-09-26 defect, and an earlier version of this gate exempted it and thus
    could not see its own counter-example. A consuming lambda only counts when
    its open line sits in expression position: assigned, returned, or inside an
    argument list.
    """
    if not _CONSUMING_LAMBDA.search(open_line):
        return False
    head = open_line.split("//")[0]
    if "=" in head:
        return True
    if head.strip().startswith("return "):
        return True
    return head.count("(") > head.count(")")


def scan(root):
    violations = []
    base = os.path.join(root, SRC_SUBDIR)
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8", errors="replace") as fh:
                src = fh.read()
            lines = src.split("\n")
            for i, line in enumerate(lines):
                if _DECL.match(line) or _COMMENT.match(line):
                    continue
                _oi, _oline = _enclosing_open(lines, i)
                if _consumes_lambda_value(_oline):
                    continue
                for fn, why in WATCHLIST.items():
                    if not _pattern(fn).match(line):
                        continue
                    if EXEMPT in line or (i > 0 and EXEMPT in lines[i - 1]):
                        continue
                    violations.append((rel, i + 1, line.strip()[:80], fn, why))
            violations.extend(_scan_bound_locals(rel, src))
    return violations

# ─────────────────────────────────────────────────────────────────────────
# [R3-t11 W-D] 绑定型丢弃：`val ignored = <watchlisted call>(…)`
#
# The line-anchored scanner above only sees a call written in STATEMENT
# position. Bind it to a local and it disappears: the declaration makes the
# value look consumed, and nothing looks at whether that local is ever read.
# That is the exact regression shape the third-round witness used —
#
#     val ignoredAttempt =
#         ProviderHealthTracker.recordFirstToken(it, ms())
#
# — which keeps every name the gate greps for, compiles, and records nothing:
# `finish()` still sees the `begin()` attempt with `firstTokenMs == null`, so
# recordedTtfbs()/ttfbP50/P95 stay empty forever.
#
# The criterion asks the dataflow question instead of the spelling one: the
# local a watchlisted result is bound to must be CONSUMED — read somewhere that
# is not a log line (a log line cannot carry the value into state), and not
# disposed of by a `when` subject it is destructured on. A `when (val x = …)`
# subject is consumed by its own branches, which is why that shape stays green.
# ─────────────────────────────────────────────────────────────────────────

_BOUND_DECL = re.compile(
    r"(?m)^[ \t]*(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=\n]+)?=(?!=)"
)
_LOG_STMT = re.compile(
    r"^[ \t]*(?:AppLogger\.|Log\.[a-z]|android\.util\.Log\.|println\b|print\b|"
    r"System\.(?:out|err)\b|logger\b|debugLog\b)"
)
_WHEN_SUBJECT_BEFORE = re.compile(r"when\s*\(\s*$")

def _call_in(expr, fn):
    """fn(…) appears anywhere in [expr], as a call (receiver optional)."""
    return re.search(
        r"(?<![\w`])(?:[A-Za-z_][A-Za-z0-9_]*\s*\??\.\s*)*" + re.escape(fn) + r"\s*\(", expr
    ) is not None

def _stmt_head_at(text, pos):
    """Statement head text for the statement containing [pos] (literal view)."""
    line_start = text.rfind("\n", 0, pos) + 1
    return text[line_start:pos]

def _scan_bound_locals(rel, src):
    """Watchlisted results bound to a local that is never consumed."""
    out = []
    try:
        code = code_only(src)
        lits = _literal_only(src)
    except KotlinLexError as exc:
        return [(rel, 0, "", "watchlist", "Kotlin 解析失败（fail-closed）: %s" % exc)]
    for m in _BOUND_DECL.finditer(code):
        local = m.group(1)
        value_end = expr_end(code, m.end())
        value = code[m.end():value_end]
        if not value.strip():
            continue
        hit = next((fn for fn in WATCHLIST if _call_in(value, fn)), None)
        if hit is None:
            continue
        # `record-ok:` on the declaration line (or the line above) exempts it.
        line_no = code.count("\n", 0, m.start()) + 1
        raw_lines = src.split("\n")
        if (EXEMPT in raw_lines[line_no - 1]
                or (line_no > 1 and EXEMPT in raw_lines[line_no - 2])):
            continue
        # A `when (val x = …)` subject is consumed by the branches themselves.
        # The `when (` may sit on the previous line (`return when (` + newline +
        # indented `val x = …`), so look back over whitespace, not just the head
        # of this one line.
        if _WHEN_SUBJECT_BEFORE.search(code[:m.start()].rstrip()):
            continue
        reads = [r for r in re.finditer(
            r"(?<![\w`])" + re.escape(local) + r"(?![\w`])", lits) if r.start() >= value_end]
        consumed = [r for r in reads
                    if not _LOG_STMT.match(_stmt_head_at(lits, r.start()))]
        if consumed:
            continue
        snippet = value.strip().split("\n")[0][:80]
        if reads:
            why = ("%s: 返回值被绑定到 `val %s`，但该局部**只被日志读取**——日志不会把值"
                   "写回状态，效果等同于丢弃（W-D）" % (hit, local))
        else:
            why = ("%s: 返回值被绑定到 `val %s`，但该局部**从未被读取**——死绑定等同于"
                   "语句位置丢弃（W-D）" % (hit, local))
        out.append((rel, line_no, snippet, hit, why))
    return out


# ─────────────────────────────────────────────────────────────────────────
# [B-04 deep-audit-1037] queue-drain section
#
# 2026-10 audit: `drainQueuedPrompts` discarded `chatRepository.appendMessage`'s
# returned `MessageEntity` — the merged row got a DB id but the UI bubble, the
# agentHistory entry, and the compaction anchors never learned it. The append
# is a "value MUST be consumed" API by construction; the generic watchlist
# above can't see it (appendMessage is legitimately discarded nowhere, but a
# watchlist entry keyed on the name alone would flag the OTHER six call sites
# that store-and-use it — this section asks the drain-specific questions).
#
#   D1  the drain's appendMessage call must be bound (`val userEntity = …`)
#       and its id must flow into the agentHistory entry (dbMessageId = …);
#   D2  the empty-content guard must exist BEFORE the persist in BOTH queue
#       consumers (drain + inject) — persisting an empty user row is a
#       deterministic provider 400;
#   D3  the UI must converge with the DB: placeholders removed + exactly ONE
#       user bubble carrying the persisted id (N rows vs 1 DB row made the
#       retry/edit cutoff counts disagree).
# ─────────────────────────────────────────────────────────────────────────

QI_FILE = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt"

def code_only(text):
    """结构通道：注释 + 字符串/字符字面量内部抹成空格，**长度与源码严格相等**。

    [R3-t11] 改走共享词法器 [_kotlin_lexer.mask_code]（与全仓门同一状态机）：
    自写版本在嵌套块注释、单引号字符字面量、三引号原始字符串、`${...}` 嵌套
    插值上系统性失明（parser-death 轴），而本门的判据全部依赖括号/花括号配平
    与切片位置。解析失败抛 [KotlinLexError]，调用方必须 fail-closed 报违规，
    **不许**回退成原文扫描。
    """
    return code_view(text)

def _literal_only(text):
    """字面量通道：只剥注释、保留字符串内容（读 `role = "user"` 这类字面量用）。

    与 [code_only] 同样等长，因此位置可以互相回投。
    """
    return literal_view(text)

def extract_block(lines, sig_re):
    """Brace-matched block for the first line matching sig_re.

    The body brace is the first `{` at paren level 0 AFTER the parameter list
    closes — lambda defaults inside the parameter list sit at paren level 1
    and are skipped. Returns (start, end) inclusive indices or (-1, -1).
    """
    for i, ln in enumerate(lines):
        if not sig_re.search(ln):
            continue
        paren = 0
        body_line = -1
        j = i
        while j < len(lines) and body_line < 0:
            k = 0
            while k < len(lines[j]):
                c = lines[j][k]
                if c == "(":
                    paren += 1
                elif c == ")":
                    paren -= 1
                elif c == "{" and paren <= 0:
                    body_line = j
                    break
                k += 1
            j += 1
        if body_line < 0:
            return i, len(lines) - 1
        depth = 0
        for j2 in range(body_line, len(lines)):
            depth += lines[j2].count("{") - lines[j2].count("}")
            if depth <= 0:
                return i, j2
        return i, len(lines) - 1
    return -1, -1

def _brace_block(text, open_idx):
    """Text of the `{...}` block whose '{' is at [open_idx] (None if unterminated)."""
    depth = 0
    for i in range(open_idx, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_idx:i + 1]
    return None


def _arrow_arms(block):
    """[(header, body)] of a `when { … }` block, split at top-level `->`.

    A `when` arm's decision is the text on its LEFT of the arrow and its value
    is everything up to the next arrow, so nesting and multi-line bodies are
    preserved without a Kotlin parser."""
    depth = 0
    arrows = []
    for i, ch in enumerate(block):
        if ch in "{([":
            depth += 1
        elif ch in "})]":
            depth -= 1
        elif ch == "-" and i + 1 < len(block) and block[i + 1] == ">" and depth <= 1:
            arrows.append(i)
    out = []
    for idx, a in enumerate(arrows):
        prev_end = arrows[idx - 1] + 2 if idx else 1
        header = block[prev_end:a].strip()
        body_end = arrows[idx + 1] if idx + 1 < len(arrows) else len(block) - 1
        out.append((header, block[a + 2:body_end]))
    return out


QUEUE_CONSUMERS = ("drainQueuedPrompts", "injectQueuedPromptsAsNewTurn")

def _named_arg(span, name):
    """Value text of the named argument `name = …` inside a call span.

    [R3-t11] `dbMessageId = null` and `id = userEntity.id` are ARGUMENTS, so a
    substring test on the whole call cannot tell one spelling from the other.
    Reading the argument's own value text is what makes the criteria ask about
    the value that is handed over, not about a name that appears nearby. Pass
    the LITERAL view (`role = "user"`) or the code view (numbers, calls).
    """
    if "(" not in span:
        return None
    open_pos = span.index("(")
    close = kast.match_bracket(span, open_pos)
    if close < 0:
        return None
    inner = span[open_pos + 1:close]
    for a, b in kast.split_top(inner, (",",)):
        seg = inner[a:b]
        m = re.match(r"\s*" + re.escape(name) + r"\s*=(?!=)", seg)
        if m:
            return seg[m.end():].strip()
    return None

def _call_spans(text, name):
    """[(open_paren_pos, end_exclusive)] for every `name(` call, or None if a
    call cannot be bracket-matched (fail-closed: the caller must red)."""
    out = []
    for m in re.finditer(r"(?<![\w`])" + re.escape(name) + r"\s*\(", text):
        close = kast.match_bracket(text, m.end() - 1)
        if close < 0:
            return None
        out.append((m.end() - 1, close + 1))
    return out

def _persist_binding(fbody):
    """('bound', name) / ('discarded', None) / ('absent', None)."""
    m = re.search(r"val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*chatRepository\.appendMessage\s*\(", fbody)
    if m:
        return "bound", m.group(1)
    if re.search(r"chatRepository\.appendMessage\s*\(", fbody):
        return "discarded", None
    return "absent", None

def _flow_has_persisted_id(expr, bind):
    """Does [expr] evaluate to (or carry) `<bind>.id` rather than a literal?

    `dbMessageId = null` / `id = "queued_msg_1"` fail the constant test; a value
    that reaches the argument through an elvis / `if` / `let` tail passes only
    if one of its real sources is the persisted row's id.
    """
    if expr is None:
        return False
    if kast.const_value(expr) is not None:
        return False
    pat = r"(?<![\w`.])" + re.escape(bind) + r"\s*\.\s*id\b"
    return any(re.search(pat, src or "") for src in value_sources(expr))

def _appended_bubbles(fbody, lbody):
    """[(id_expr, role_expr)] for bubbles that actually reach `_messages.value`.

    Two shapes ship side by side: an inline `+ ChatMessage(…)` (the drain) and a
    hoisted `val queuedUserMsg = ChatMessage(…)` that the append then adds (the
    inject path). A criterion that only knew the inline shape would pass the
    second consumer no matter what the bubble carried, so the local-binding hop
    is resolved here. Returns None when a construct cannot be bracket-matched.
    """
    hoisted = {}
    for m in re.finditer(r"val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*ChatMessage\s*\(", fbody):
        open_pos = m.end() - 1
        close = kast.match_bracket(fbody, open_pos)
        if close < 0:
            return None
        hoisted[m.group(1)] = (_named_arg(lbody[open_pos:close + 1], "id"),
                               _named_arg(lbody[open_pos:close + 1], "role"))
    bubbles = []
    for m in re.finditer(r"_messages\.value\s*=\s*_messages\.value\s*\+", fbody):
        stop = expr_end(fbody, m.end())
        rhs = fbody[m.end():stop]
        spans = _call_spans(rhs, "ChatMessage")
        if spans is None:
            return None
        for open_pos, close in spans:
            abs_open = m.end() + open_pos
            bubbles.append((_named_arg(lbody[abs_open:m.end() + close], "id"),
                            _named_arg(lbody[abs_open:m.end() + close], "role")))
        for name, bubble in hoisted.items():
            # Count EVERY mention: an append that adds the same bubble twice is
            # two rows on screen for one persisted row (the duplicate the drain
            # fix removed), so one mention must not stand in for the count.
            n = len(re.findall(r"(?<![\w`.])" + re.escape(name) + r"(?![\w`])", rhs))
            bubbles.extend([bubble] * n)
    return bubbles

def scan_queue_drain(root):
    findings = []
    path = os.path.join(root, QI_FILE)
    if not os.path.exists(path):
        return findings
    src = open(path, encoding="utf-8", errors="replace").read()
    try:
        code = code_only(src)
        lits = _literal_only(src)
    except KotlinLexError as exc:
        return ["ChatQueueInterruption.kt:?  [B-04] Kotlin 解析失败（fail-closed）: %s" % exc]
    lines = code.split("\n")
    lit_lines = lits.split("\n")

    def body(sig_re):
        i0, i1 = extract_block(lines, sig_re)
        if i0 < 0:
            return None
        return (i0, i1, "\n".join(lines[i0:i1 + 1]), "\n".join(lit_lines[i0:i1 + 1]))

    bodies = {
        "drainQueuedPrompts": body(re.compile(r"suspend fun ChatViewModel\.drainQueuedPrompts\s*\(")),
        "injectQueuedPromptsAsNewTurn": body(
            re.compile(r"suspend fun ChatViewModel\.injectQueuedPromptsAsNewTurn\s*\(")),
    }

    # D1: bind + the id must FLOW into the agentHistory entry — graded on BOTH
    # queue consumers. [R3-t11] It used to be anchored on `drainQueuedPrompts`
    # alone, so the twin consumer could hand the loop a `dbMessageId = null`
    # turn. That twin is where a message queued during a long TOOL CALL lands
    # (the loop-end drain is the no-tool path), i.e. the common one.
    for label in QUEUE_CONSUMERS:
        fn = bodies[label]
        if fn is None:
            findings.append(
                "ChatQueueInterruption.kt:?  [B-04 D1] %s body NOT FOUND (fail-closed)" % label
            )
            continue
        i0, _i1, fbody, lfbody = fn
        kind, bind = _persist_binding(fbody)
        if kind == "absent":
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D1] %s lost the appendMessage persist "
                "entirely — the queued turn never reaches the DB" % (i0 + 1, label)
            )
            continue
        if kind == "discarded":
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D1] %s discards appendMessage's MessageEntity "
                "again — the merged row's DB id never reaches the UI bubble / agentHistory / "
                "compaction anchors" % (i0 + 1, label)
            )
            continue
        msgs = _call_spans(fbody, "LLMMessage")
        if msgs is None:
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D1] %s: LLMMessage( … ) unbalanced — "
                "fail-closed" % (i0 + 1, label)
            )
            continue
        user_msgs = []
        for open_pos, close in msgs:
            role = _named_arg(lfbody[open_pos:close], "role")
            if role and "Role.USER" in role:
                user_msgs.append((open_pos, close))
        if not user_msgs:
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D1] %s no longer appends a "
                "LLMMessage(role = USER) to agentHistory — the queued turn never reaches the "
                "loop" % (i0 + 1, label)
            )
            continue
        for open_pos, close in user_msgs:
            expr = _named_arg(lfbody[open_pos:close], "dbMessageId")
            if not _flow_has_persisted_id(expr, bind):
                findings.append(
                    "ChatQueueInterruption.kt:%d  [B-04 D1] %s: the bound %s.id does not flow into "
                    "the agentHistory entry (dbMessageId = %s) — the compaction anchors skip this "
                    "turn" % (i0 + 1, label, bind, (expr or "<missing>")[:40])
                )

    # D2: empty-content guard before persist, both consumers
    for label in QUEUE_CONSUMERS:
        fn = bodies[label]
        if fn is None:
            findings.append(
                "ChatQueueInterruption.kt:?  [B-04 D2] %s body NOT FOUND (fail-closed)" % label
            )
            continue
        i0, i1, fbody, _l = fn
        guard = re.search(r"if\s*\(\s*combinedParts\.isEmpty\(\)\s*\)", fbody)
        persist = re.search(r"chatRepository\.appendMessage\s*\(", fbody)
        if not guard or not persist:
            missing = "the combinedParts.isEmpty() guard" if not guard else "the appendMessage persist"
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D2] %s lost %s — an empty user row "
                "persisted is a deterministic provider 400" % (i0 + 1, label, missing)
            )
        elif guard.start() > persist.start():
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D2] %s persists BEFORE the "
                "combinedParts.isEmpty() guard — the guard must precede the persist" % (i0 + 1, label)
            )
        else:
            # [wide-1024 dataflow] The guard must TERMINATE: `if (empty) { log }`
            # keeps the guard, the order and the clean-up shape while the empty
            # user row falls through to appendMessage (a deterministic 400).
            after = fbody[guard.end():]
            stripped = after.lstrip()
            if stripped.startswith("{"):
                blk = _brace_block(after, after.index("{"))
            else:
                blk = after.split("\n", 1)[0]
            if blk is None or not re.search(r"\b(return|throw)\b", blk):
                findings.append(
                    "ChatQueueInterruption.kt:%d  [B-04 D2] %s's combinedParts.isEmpty() guard "
                    "does not TERMINATE — control falls through to appendMessage, so an empty "
                    "user row is persisted and every provider answers 400" % (i0 + 1, label)
                )

    # D3: placeholders removed + exactly ONE user bubble carrying the persisted
    # id — graded on BOTH queue consumers (same seam as D1: the inject path
    # appends its own bubble, so a kept placeholder duplicates the row on screen
    # and a bubble without the persisted id resolves a phantom row on retry).
    for label in QUEUE_CONSUMERS:
        fn = bodies[label]
        if fn is None:
            findings.append(
                "ChatQueueInterruption.kt:?  [B-04 D3] %s body NOT FOUND (fail-closed)" % label
            )
            continue
        i0, _i1, fbody, lfbody = fn
        if not re.search(r"removeQueuedPlaceholders\s*\(", fbody):
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D3] %s no longer drops the placeholder "
                "bubbles through the single funnel — the screen keeps N rows where the DB has 1 "
                "and the retry/edit cutoff counts disagree" % (i0 + 1, label)
            )
        kind, bind = _persist_binding(fbody)
        if kind != "bound":
            continue
        bubbles = _appended_bubbles(fbody, lfbody)
        if bubbles is None:
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D3] %s: ChatMessage( … ) unbalanced — "
                "fail-closed" % (i0 + 1, label)
            )
            continue
        user_bubbles = [b for b in bubbles if b[1] and '"user"' in b[1]]
        if not user_bubbles:
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D3] %s does not append the merged user "
                "bubble — the persisted turn vanishes from the screen" % (i0 + 1, label)
            )
            continue
        for id_expr, _role in user_bubbles:
            if not _flow_has_persisted_id(id_expr, bind):
                findings.append(
                    "ChatQueueInterruption.kt:%d  [B-04 D3] %s: the merged bubble does not carry "
                    "the persisted id (id = %s) — retry/edit resolve a phantom row"
                    % (i0 + 1, label, (id_expr or "<missing>")[:40])
                )
        if len(user_bubbles) > 1:
            findings.append(
                "ChatQueueInterruption.kt:%d  [B-04 D3] %s appends %d user bubbles for ONE "
                "persisted row — the placeholder rows must be REMOVED, not kept beside the merged "
                "bubble" % (i0 + 1, label, len(user_bubbles))
            )
    return findings

# ─────────────────────────────────────────────────────────────────────────
# [§1042-A-07 deep-audit-1042] staged-bytes ledger section
#
# A-07: `ShareReceiverActivity.debitStagedBytes` clamped a DISCARDED copy —
# `totalStagedBytes.addAndGet(-bytes).coerceAtLeast(0L)` written as a
# statement. The counter went negative on a double debit and the cumulative
# 500 MB cap under-counted.
#
# Why NOT a generic WATCHLIST entry for coerce*: measured on the real tree
# (2026-10-22, `probe/inject_watchlist.py`), the three names hit 15 sites and
# ALL 15 are legitimate — parameter-list continuation lines, single-expression
# function tails (`= x.coerceIn(...)`), assignment continuations, `.let` tails.
# Worse, the generic statement-position regex cannot even SEE the defect's own
# shape (the receiver is an infix call result, not a simple identifier), so it
# would have been 15 false positives and zero teeth. The specialized criteria
# below anchor the DECISION POINTS instead:
#
#   S1  debitStagedBytes must write the clamped balance back THROUGH the
#       ledger (updateAndGet/set on a receiver named `stagedBytes`), never
#       `.coerce*` on a discarded copy;
#   S2  the AttachText branch must guard the write through the pure
#       admission core (`admitAttachText`) AND credit the ledger after it —
#       a bare `writeText` with no guard is the pre-fix shape;
#   S3  every reference to the counter goes through the ledger — a bare
#       `AtomicLong` named totalStagedBytes in this file means someone
#       re-inlined the raw counter (fail-closed).
# ─────────────────────────────────────────────────────────────────────────

SHARE_FILE = "share/ShareReceiverActivity.kt"
LEDGER_FILE = "share/StagedBytesLedger.kt"

def _when_subject_block(text, when_at):
    """The `{…}` body of the `when (...)` that starts at [when_at]."""
    i = text.find("(", when_at)
    if i < 0:
        return None
    depth = 0
    for k in range(i, len(text)):
        if text[k] == "(":
            depth += 1
        elif text[k] == ")":
            depth -= 1
            if depth == 0:
                j = k + 1
                while j < len(text) and text[j] in " \t\n":
                    j += 1
                if j < len(text) and text[j] == "{":
                    return _brace_block(text, j)
                return None
    return None


def _last_value_expr(body):
    """The value the arm hands back (last non-brace, non-log line)."""
    for raw in reversed(body.split("\n")):
        t = raw.strip().rstrip(",;")
        if not t or t in ("}", ")", "{", "else"):
            continue
        if re.search(r"AppLogger\.|Log\.|MemorySpikeRecorder\.", t):
            continue
        return t
    return ""


def _copy_outcome_gaps(text):
    """[wide-1024 dataflow] The copy OUTCOME must decide the return value.

    G3 already holds that `val outcome = copySharedStream(…)` exists. The
    witnessed shape keeps that binding, keeps the `is SharedStreamCopy.Failed`
    label, keeps the log line — and returns the staged FILENAME from the
    failure arm (`null` → `name`), which is the ghost attachment in one token:
    the caller builds a PendingShare.Item for a file that was never created."""
    gaps = []
    m = re.search(r"val\s+(\w+)\s*=\s*copySharedStream\s*\(", text)
    if m is None:
        # No binding at all is G3's own criterion (and the A08-7 selftest arm):
        # a second finding here would double-count that shape.
        return []
    when_at = None
    for cand in re.finditer(r"\bwhen\b", text):
        if cand.start() < m.start() and m.start() - cand.start() < 400:
            when_at = cand.start()
    block = _when_subject_block(text, when_at) if when_at is not None else None
    if block is None:
        return ["the copySharedStream outcome is bound but never branched on "
                "(fail-closed) — nothing distinguishes 'staged' from 'staged nothing'"]
    arms = _arrow_arms(block)
    failed = [(h, b) for h, b in arms if re.search(r"\bFailed\b", h)]
    if not failed:
        return ["the copy outcome `when` no longer has a Failed arm (fail-closed)"]
    staged_vals = {
        _last_value_expr(b) for h, b in arms if re.search(r"\bStaged\b", h)
    }
    for _h, body in failed:
        if not re.search(r"\bnull\b|\breturn\b|\bthrow\b", body):
            val = _last_value_expr(body) or body.strip()[:30]
            hint = " (the same value the Staged arm returns)" if val in staged_vals else ""
            gaps.append(
                "the copy Failed arm yields `%s` instead of failing%s — the caller "
                "receives a filename for a file the copy never created (ghost "
                "attachment)" % (val, hint)
            )
    return gaps


def _admission_gate_gaps(branch):
    """[wide-1024 dataflow] Is the admission DECISION consumed?

    The pre-fix shape the ledger criteria pin is "write with no budget guard".
    The witnessed shape is subtler: the guard is still CALLED and its result is
    still BOUND (`val admission = admitAttachText(…)`), but nothing reads it —
    the write/credit lines were lifted out of the `when` and the branch guard
    deleted, so an over-cap share is written and charged to disk anyway. Every
    substring and ordering criterion stays satisfied.

    Returns a list of findings (empty = the decision is respected)."""
    gaps = []
    adm = re.search(r"val\s+(\w+)\s*=\s*admitAttachText\s*\(", branch)
    if adm is None:
        return gaps                      # the "no guard at all" arm owns this
    var = adm.group(1)
    uses = re.findall(r"\b" + re.escape(var) + r"\b", branch)
    if len(uses) < 2:
        return [
            "the AttachText budget decision is BOUND and never consumed "
            "(`val %s = admitAttachText(…)` then nothing reads it) — an over-cap "
            "share is written and charged to the staging budget anyway" % var
        ]
    gate = re.search(r"when\s*\(\s*" + re.escape(var) + r"\s*\)\s*\{", branch)
    if gate is not None:
        block = _brace_block(branch, gate.end() - 1)
        if block is None:
            return ["the AttachText admission `when` block is unterminated (fail-closed)"]
        arms = _arrow_arms(block)
        if not arms:
            return ["the AttachText admission `when` has no readable arms (fail-closed)"]
        writes = [s for h, s in arms if re.search(r"writeText\s*\(|stagedBytes\.credit", s)]
        admits = [s for h, s in arms if "Admitted" in h]
        if not admits:
            return ["the AttachText admission `when` no longer has an Admitted arm "
                    "(fail-closed) — cannot tell which branch is allowed to write"]
        if not re.search(r"writeText\s*\(|stagedBytes\.credit", admits[0]):
            return ["the AttachText write/credit sits OUTSIDE the Admitted arm — the "
                    "budget decision is made and then bypassed"]
        for h, s in arms:
            if "Admitted" in h:
                continue
            if re.search(r"writeText\s*\(|stagedBytes\.credit", s):
                return ["the AttachText %s arm writes/credits anyway — a rejected share "
                        "still lands on disk and against the cap" % h.strip()[:40]]
        del writes
        return gaps
    cond = re.search(
        r"if\s*\(([^\n]*" + re.escape(var) + r"[^\n]*)\)\s*\{", branch)
    if cond is not None:
        block = _brace_block(branch, cond.end() - 1) or ""
        if "Admitted" in cond.group(1):
            if not re.search(r"writeText\s*\(|stagedBytes\.credit", block):
                return ["the AttachText write is not inside the `%s` branch"
                        % cond.group(1).strip()[:60]]
        elif re.search(r"writeText\s*\(|stagedBytes\.credit", block):
            return ["the AttachText write sits inside a branch that is NOT the Admitted "
                    "arm (`%s`)" % cond.group(1).strip()[:60]]
        return gaps
    return [
        "the AttachText budget decision `%s` is never used as a branch condition — "
        "the guards exist and the write ignores them" % var
    ]


def _attach_credit_gaps(branch, line):
    """[R3-t11 S2d] The AMOUNT credited must be the amount that was admitted.

    The branch used to be graded on three names and their ORDER, so
    `stagedBytes.credit(0L)` — or any literal, or any other binding — kept the
    ledger at zero forever while the criteria stayed green. `admitAttachText`
    then always saw `current() == 0`, so `RejectedCumulative` (the 500 MB cap)
    stopped bounding text attachments entirely. Grade the value: the credited
    expression must share a value source with the admitted byte count, and a
    literal constant cannot (nothing was measured).
    """
    gaps = []
    am = re.search(r"admitAttachText\s*\(", branch)
    cm = re.search(r"stagedBytes\.credit\s*\(", branch)
    if not am or not cm:
        return gaps
    aargs = call_args(branch, branch.index("(", am.start()))
    cargs = call_args(branch, branch.index("(", cm.start()))
    if not aargs or not cargs:
        return ["%s:%d  [A-07 S2] admitAttachText(…) / credit(…) 的实参无法配平（fail-closed）"
                % (SHARE_FILE, line)]
    admitted = branch[aargs[0][0]:aargs[0][1]].strip()
    credited = branch[cargs[0][0]:cargs[0][1]].strip()
    if kast.const_value(credited) is not None:
        gaps.append(
            "%s:%d  [A-07 S2] the AttachText branch credits a CONSTANT (`credit(%s)`) instead of "
            "the admitted byte count — the ledger stays at whatever the literal says, so "
            "`admitAttachText` reads a current() that never grows and the cumulative cap stops "
            "bounding text shares" % (SHARE_FILE, line, credited)
        )
        return gaps
    src_credited = set(x.strip() for x in value_sources(credited))
    src_admitted = set(x.strip() for x in value_sources(admitted))
    if not (src_credited & src_admitted):
        gaps.append(
            "%s:%d  [A-07 S2] the AttachText branch credits `%s` but admitted `%s` — the amount "
            "written back to the ledger is not the amount that was bounded"
            % (SHARE_FILE, line, credited[:40], admitted[:40])
        )
    return gaps

def _ledger_credit_lands(lcode):
    """[R3-t11 S2e] `credit(bytes)` must actually add its parameter."""
    m = re.search(r"fun\s+credit\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*:", lcode)
    if not m:
        return False, "`fun credit(<bytes>: …)` 签名不见了（fail-closed，重锚此判据）"
    param = m.group(1)
    open_brace = lcode.find("{", m.end())
    if open_brace < 0:
        return False, "`credit(%s)` 没有块体（fail-closed）" % param
    blk = _brace_block(lcode, open_brace)
    if blk is None:
        return False, "`credit(%s)` 块体不配平（fail-closed）" % param
    for am in re.finditer(r"addAndGet\s*\(", blk):
        args = call_args(blk, am.end() - 1)
        if not args:
            continue
        arg = blk[args[0][0]:args[0][1]].strip()
        if kast.const_value(arg) is not None:
            continue
        if any(contains_ident(src, param) for src in value_sources(arg)):
            return True, ""
    return False, ("`credit(%s)` 没有把实参加进账本（addAndGet 的入参不含 %s）——记账恒为零，"
                   "累计上限对文本附件失效" % (param, param))

def scan_staged_bytes(root):
    findings = []
    base = os.path.join(root, SRC_SUBDIR, "com", "rikkaminis", "app")
    share_path = os.path.join(base, *SHARE_FILE.split("/"))
    ledger_path = os.path.join(base, *LEDGER_FILE.split("/"))
    if not os.path.isfile(share_path):
        if os.path.isdir(os.path.dirname(share_path)):
            findings.append("%s:?  [A-07 S1] ShareReceiverActivity.kt missing while share/ exists — the staged-bytes criteria lost their anchor (fail-closed)" % SHARE_FILE)
        return findings
    code = code_only(open(share_path, encoding="utf-8", errors="replace").read())
    lines = code.split("\n")

    def body(sig_re):
        i0, i1 = extract_block(lines, sig_re)
        if i0 < 0:
            return None
        return i0, i1, "\n".join(lines[i0:i1 + 1])

    # S1: the debit must write the clamp back through the ledger.
    debit = body(re.compile(r"fun\s+debitStagedBytes\s*\("))
    if debit is None:
        findings.append("%s:?  [A-07 S1] debitStagedBytes body NOT FOUND (fail-closed)" % SHARE_FILE)
    else:
        i0, i1, dbody = debit
        if re.search(r"\.coerce(?:AtLeast|AtMost|In)\s*\(", dbody):
            findings.append(
                "%s:%d  [A-07 S1] debitStagedBytes applies a clamp inside its body — the "
                "2026 defect clamped a DISCARDED copy (`addAndGet(-b).coerceAtLeast(0)` as a "
                "statement); the clamped write-back must live in StagedBytesLedger.debit" % (SHARE_FILE, i0 + 1)
            )
        if not re.search(r"stagedBytes\.debit\s*\(", dbody):
            findings.append(
                "%s:%d  [A-07 S1] debitStagedBytes does not route through the ledger "
                "(`stagedBytes.debit(…)`) — a raw AtomicLong update here can re-ship the "
                "discarded-clamp defect" % (SHARE_FILE, i0 + 1)
            )

    # S3: the raw counter must not be re-inlined in this file.
    if re.search(r"\btotalStagedBytes\b", code):
        for i, ln in enumerate(lines):
            if re.search(r"\btotalStagedBytes\b", ln):
                findings.append(
                    "%s:%d  [A-07 S3] bare `totalStagedBytes` AtomicLong re-inlined — every "
                    "access must go through StagedBytesLedger (fail-closed)" % (SHARE_FILE, i + 1)
                )
                break

    # S2: AttachText — guard + credit around the writeText.
    send = body(re.compile(r"fun\s+handleSingleSend\s*\("))
    if send is None:
        findings.append("%s:?  [A-07 S2] handleSingleSend body NOT FOUND (fail-closed)" % SHARE_FILE)
    else:
        i0, i1, sbody = send
        m = re.search(r"is\s+SingleSendDecision\.AttachText\s*->\s*\{", sbody)
        if not m:
            findings.append(
                "%s:%d  [A-07 S2] handleSingleSend lost the AttachText branch — the text-"
                "staging path moved; re-anchor this criterion" % (SHARE_FILE, i0 + 1)
            )
        else:
            # Brace-match from the branch's own `{` — `code_only` blanked the
            # strings/comments, so braces here are real. Slicing on the next
            # `is … ->` line would break the moment that arm is reordered.
            brace = m.end() - 1
            depth = 0
            end = len(sbody) - 1
            for j in range(brace, len(sbody)):
                if sbody[j] == "{":
                    depth += 1
                elif sbody[j] == "}":
                    depth -= 1
                    if depth == 0:
                        end = j
                        break
            branch = sbody[brace:end + 1]
            for gap in _admission_gate_gaps(branch):
                findings.append("%s:%d  [A-07 S2] %s" % (SHARE_FILE, i0 + 1, gap))
            write = re.search(r"writeText\s*\(", branch)
            admit = re.search(r"admitAttachText\s*\(", branch)
            credit = re.search(r"stagedBytes\.credit\s*\(", branch)
            if not write:
                findings.append(
                    "%s:%d  [A-07 S2] the AttachText branch no longer writes the text itself "
                    "(no `writeText`) — the staging path moved; re-anchor this criterion"
                    % (SHARE_FILE, i0 + 1)
                )
            else:
                if not admit:
                    findings.append(
                        "%s:%d  [A-07 S2] the AttachText branch writes text with NO budget guard — "
                        "long EXTRA_TEXT shares bypassed MAX_FILE_BYTES / MAX_TOTAL_BYTES and the "
                        "500 MB cap (the pre-fix shape)" % (SHARE_FILE, i0 + 1)
                    )
                if not re.search(r"attachTextByteCount\s*\(", branch):
                    findings.append(
                        "%s:%d  [A-07 S2] the AttachText branch does not measure the text through "
                        "attachTextByteCount() — the budget counts UTF-8 BYTES, and an inline char "
                        "count under-charges every non-ASCII share" % (SHARE_FILE, i0 + 1)
                    )
                elif admit.start() > write.start():
                    findings.append(
                        "%s:%d  [A-07 S2] the AttachText budget guard runs AFTER the write — a "
                        "post-mortem check cannot keep the forbidden bytes off disk"
                        % (SHARE_FILE, i0 + 1)
                    )
                if not credit:
                    findings.append(
                        "%s:%d  [A-07 S2] the AttachText branch writes text but never credits the "
                        "staged-bytes ledger — the budget under-counts and admits past the cap"
                        % (SHARE_FILE, i0 + 1)
                    )
                elif credit.start() < write.start():
                    findings.append(
                        "%s:%d  [A-07 S2] the AttachText branch credits the ledger BEFORE the "
                        "write — a failed write would leave phantom bytes counted against the cap"
                        % (SHARE_FILE, i0 + 1)
                    )
                if credit and admit:
                    findings.extend(_attach_credit_gaps(branch, i0 + 1))

    # S2b: the admission core itself must exist with the strict > shape.
    if not os.path.isfile(ledger_path):
        findings.append("%s:?  [A-07 S2] StagedBytesLedger.kt missing while the share file "
                        "routes through it (fail-closed)" % LEDGER_FILE)
    else:
        lcode = code_only(open(ledger_path, encoding="utf-8", errors="replace").read())
        if not re.search(r"textBytes\s*>\s*maxFileBytes", lcode) or \
           not re.search(r"currentTotalBytes\s*\+\s*textBytes\s*>\s*maxTotalBytes", lcode):
            findings.append(
                "%s:?  [A-07 S2] admitAttachText lost the strict-`>` guard shape — the "
                "per-file / cumulative caps no longer bound text attachments" % LEDGER_FILE
            )
        # Two accepted write-back idioms — the DECISION is "the clamped balance is
        # written back", not one spelling (same idiom as the fastio NOFOLLOW
        # criterion, which accepts both explicit and flag-based guards):
        #   A: updateAndGet { (it - bytes).coerceAtLeast(0L) }   (atomic lambda)
        #   B: set(total.addAndGet(-bytes).coerceAtLeast(0L))     (one-line set)
        idiom_a = re.search(r"updateAndGet\s*\{[^{}]*coerceAtLeast\s*\(\s*0", lcode)
        idiom_b = re.search(r"\.set\s*\([^()]*addAndGet\s*\(\s*-[^()]*\)[^()]*coerceAtLeast\s*\(\s*0", lcode)
        lands, why_not = _ledger_credit_lands(lcode)
        if not lands:
            findings.append("%s:?  [A-07 S2] %s" % (LEDGER_FILE, why_not))
        if not (idiom_a or idiom_b):
            findings.append(
                "%s:?  [A-07 S1] StagedBytesLedger.debit lost the clamp-inside-the-write "
                "(accepted: `updateAndGet { … coerceAtLeast(0L) }` or "
                "`set(total.addAndGet(-bytes).coerceAtLeast(0L))`) — the negative-balance "
                "hole is back" % LEDGER_FILE
            )
    return findings

# ─────────────────────────────────────────────────────────────────────────
# A-08 — the share inbound stream copy (deep-audit-1044)
#
# Neither the watchlist nor the staged-bytes criteria above could see this one:
# the call site was syntactically fine and the value WAS consumed — the helper
# simply returned a staged filename for a file it never created.
#
# `ContentResolver.openInputStream(Uri)` is allowed to return null WITHOUT
# throwing (a remote provider refuses the read, or its process was reaped). The
# pre-fix copy read
#
#     contentResolver.openInputStream(uri)?.use { input ->
#         dest.outputStream().use { output -> … }
#     }
#     name                        ← returned unconditionally
#
# so a null stream skipped the whole `use` block, the destination file was never
# created, and the caller still built a `PendingShare.Item` for it: a ghost
# attachment that reached the composer and never took the "Could not read shared
# file" branch.
#
# The decision pinned here is the ORDER, not the spelling: inside whichever
# function performs the copy, the stream must be null-checked and abandoned
# BEFORE the destination file is created/truncated. Accepted early exits:
#   `?: return …` / `?: throw …`           (elvis — PhotosOffloadHandler shape)
#   `if (x == null) { …; return … }`       (guard clause)
# Delegating the copy to a helper (the §1044 ship shape: copySharedStream) is
# equally fine — every function that BOTH acquires a stream and creates the
# destination must obey the rule, so an inlined regression is caught as well.
# ─────────────────────────────────────────────────────────────────────────

_STREAM_SIG = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public |private |internal |protected |override |suspend |inline "
    r"|operator |abstract |open )*fun\s+(?:<[^>]*>\s*)?"
    r"(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Za-z_][A-Za-z0-9_]*)\s*\("
)
_STREAM_SRC = re.compile(r"openInputStream\s*\(")
_STREAM_PROVIDER_CALL = re.compile(r"(?<![A-Za-z0-9_])openStream\s*\(\s*\)")
# The destination is opened either directly (`dest.outputStream()`) or through
# the injected factory the pure core takes (`openDest()`).
_DEST_CREATE = re.compile(r"outputStream\s*\(|\bopenDest\s*\(\s*\)")
_STREAM_TOKEN = r"(?:openInputStream|openStream|input|source|stream)"
# Elvis early-exit, attached to a stream-ish expression within one short span.
_GUARD_ELVIS = re.compile(_STREAM_TOKEN + r"[\s\S]{0,120}?\?\:\s*(?:return|throw)\b")
# Guard clause: `if (… == null …) { … return … }` where the condition mentions a
# stream-ish name (so an unrelated `if (uri == null)` above it cannot satisfy
# this criterion from a distance).
_GUARD_IF_NULL = re.compile(
    r"if\s*\([^\n]*" + _STREAM_TOKEN + r"[^\n]*==\s*null[^\n]*\)[\s\S]{0,300}?\breturn\b"
)


def _function_bodies(lines):
    """(name, start, end, body) for every brace-bodied `fun` in [lines].

    Expression-bodied functions (`… = expr`) are skipped: they cannot carry the
    create-then-copy shape this criterion judges. Nested declarations are not
    descended into — the enclosing body already contains them textually.
    """
    bodies = []
    i = 0
    n = len(lines)
    while i < n:
        m = _STREAM_SIG.match(lines[i])
        if not m:
            i += 1
            continue
        paren = 0
        j = i
        body_start = -1
        while j < n:
            if j > i and _STREAM_SIG.match(lines[j]):
                break
            found = False
            for ch in lines[j]:
                if ch == "(":
                    paren += 1
                elif ch == ")":
                    paren -= 1
                elif ch == "{" and paren <= 0:
                    found = True
                    break
            if found:
                body_start = j
                break
            j += 1
        if body_start < 0:
            # [deep-audit-1047 B-01] `j` is the NEXT declaration (expression-bodied
            # functions have no body brace): skipping it used to drop the function
            # right after an `= expr` one, so its body was never attributed.
            i = j
            continue
        depth = 0
        end = n - 1
        for j2 in range(body_start, n):
            depth += lines[j2].count("{") - lines[j2].count("}")
            if depth <= 0:
                end = j2
                break
        bodies.append((m.group(1), i, end, "\n".join(lines[i:end + 1])))
        i = end + 1
    return bodies


def _own_acquisition(body):
    """Position of the stream ACQUISITION inside [body], or None.

    An acquisition is an `openInputStream(` / `openStream()` call that is not
    merely passed as a lambda to somebody else. The distinction matters: the
    §1044 ship shape has the Activity hand `openStream = { resolver
    .openInputStream(uri) }` to the core — the Activity does not acquire the
    stream, the core does, and the core is where the null guard must live.

    A lambda is recognised by an `=` whose right-hand side opens a brace
    (`x = { … }`); a plain `val x = call()` is NOT a lambda. Brace depth is
    tracked with a stack so a call inside a lambda body is attributed to the
    lambda, not to the enclosing function.
    """
    stack = []          # one entry per open brace: True = lambda body
    i = 0
    n = len(body)
    while i < n:
        ch = body[i]
        if ch == "=":
            j = i + 1
            while j < n and body[j] in " \t\n":
                j += 1
            if j < n and body[j] == "{":
                stack.append(True)
                i = j + 1
                continue
        elif ch == "{":
            stack.append(False)
        elif ch == "}":
            if stack:
                stack.pop()
        elif ch == "o":
            m = _ACQUIRE_RE.match(body, i)
            if m:
                if not any(stack):     # not inside any lambda body
                    return m.start()
                i = m.end()
                continue
        i += 1
    return None


_ACQUIRE_RE = re.compile(
    r"openInputStream\s*\(|(?<![A-Za-z0-9_])openStream\s*\(\s*\)"
)


def _guard_before(body, acquisition, dest):
    """Position of the null early-exit that follows [acquisition], or None.

    Two accepted spellings, both anchored AFTER the acquisition and BEFORE the
    destination is opened:
      * elvis      — `val input = openStream() ?: return null`
      * guard      — `if (input == null) { … return null }`
    """
    window = body[acquisition:dest.start()] if dest.start() > acquisition else ""
    if dest.start() <= acquisition:
        # The destination is opened BEFORE the stream is even acquired — the
        # 0-byte-ghost order. Report it as an ORDER violation, not as a
        # missing guard (the guard may well exist, just too late).
        return dest.start() + 1
    cands = []
    m = re.search(r"\?\:\s*(?:return|throw)\b", window)
    if m:
        cands.append(acquisition + m.end())
    m = re.search(
        r"if\s*\([^\n]*(?:==\s*null|is\s+null)[^\n]*\)\s*\{?[\s\S]{0,300}?\breturn\b",
        window,
    )
    if m:
        cands.append(acquisition + m.end())
    if not cands:
        return None
    return min(cands)


def scan_share_stream_guard(root):
    findings = []
    base = os.path.join(root, SRC_SUBDIR, "com", "rikkaminis", "app")
    share_dir = os.path.join(base, "share")
    share_path = os.path.join(base, *SHARE_FILE.split("/"))
    if not os.path.isdir(share_dir):
        return findings
    if not os.path.isfile(share_path):
        findings.append(
            "%s:?  [A-08 G1] ShareReceiverActivity.kt missing while share/ exists — "
            "the null-stream criteria lost their anchor (fail-closed)" % SHARE_FILE
        )
        return findings

    # The copy may be inlined in the Activity or delegated to a helper in the
    # same package (§1044 ship shape: SharedStreamCopy.kt) — judge every file.
    files = []
    for name in sorted(os.listdir(share_dir)):
        if not name.endswith(".kt"):
            continue
        path = os.path.join(share_dir, name)
        rel = os.path.relpath(path, root)
        text = code_only(open(path, encoding="utf-8", errors="replace").read())
        files.append((rel, text, text.split("\n")))

    package_code = "\n".join(text for _rel, text, _lines in files)

    # G2 — the inbound staging path must still acquire a ContentResolver stream.
    # Without this anchor a "fix" that quietly deletes the staging path would
    # satisfy G1 vacuously.
    if not _STREAM_SRC.search(package_code):
        findings.append(
            "%s:?  [A-08 G2] no `openInputStream(` left in the share package — the inbound "
            "stream-staging path moved or was removed; re-anchor this criterion "
            "(fail-closed)" % SHARE_FILE
        )
        return findings

    # G3 — the copy outcome must be consumed. A statement-position call drops the
    # only signal that a file was written, which is the ghost-attachment shape
    # again; the generic watchlist cannot see a bare top-level call on its own.
    if "fun copySharedStream(" in package_code:
        bound = False
        for _rel, _text, lines in files:
            for ln in lines:
                m = re.search(r"copySharedStream\s*\(", ln)
                if not m:
                    continue
                if re.search(r"\bval\s+[A-Za-z_][A-Za-z0-9_]*\s*=\s*", ln[:m.start()]):
                    bound = True
        if not bound:
            findings.append(
                "%s:?  [A-08 G3] the copySharedStream outcome is never bound — a "
                "statement-position call throws away the only signal that a file was "
                "written, and the caller is back to handing out a filename for a file "
                "that may not exist" % SHARE_FILE
            )
        for gap in _copy_outcome_gaps(package_code):
            findings.append("%s:?  [A-08 G3] %s" % (SHARE_FILE, gap))

    # G1 — guard before the destination file is created.
    #
    # The two shapes that must both satisfy the rule:
    #   (a) a function that OPENS the stream itself (`openInputStream(` in its
    #       own body) and also opens the destination;
    #   (b) the pure core `copySharedStream`, which receives the stream through
    #       the `openStream` factory and opens the destination through
    #       `openDest`.
    # A function that merely INJECTS the stream (e.g. `openStream = {
    # contentResolver.openInputStream(uri) }`) is neither: it does not acquire
    # the stream, it hands the acquisition to the core — which is where the
    # guard now lives. Judging it as if it acquired the stream would flag the
    # §1044 ship shape itself, so the criterion tracks WHERE the acquisition
    # happens instead of counting occurrences.
    saw_dest = False
    for rel, _text, lines in files:
        for name, start, _end, body in _function_bodies(lines):
            dest = _DEST_CREATE.search(body)
            if not dest:
                continue
            saw_dest = True
            # The acquisition must be in THIS function's own body, not inside a
            # lambda the function passes to somebody else.
            acquires = _own_acquisition(body)
            if acquires is None:
                continue          # only injects the stream — the core guards it
            guard = _guard_before(body, acquires, dest)
            if guard is None:
                findings.append(
                    "%s:%d  [A-08 G1] %s() creates the destination file from a stream with NO "
                    "null early-exit — `openInputStream` returns null without throwing when a "
                    "remote provider refuses, so the copy stages nothing and the caller still "
                    "gets a filename (ghost attachment)" % (rel, start + 1, name)
                )
                continue
            if guard > dest.start():
                findings.append(
                    "%s:%d  [A-08 G1] %s() null-checks the stream AFTER creating the destination "
                    "file — the file is created/truncated before the guard can bail out, which is "
                    "the 0-byte-ghost shape; check first, create second"
                    % (rel, start + 1, name)
                )
    if not saw_dest:
        findings.append(
            "%s:?  [A-08 G1] no `outputStream(` in the share package — the staging write path "
            "moved; re-anchor this criterion (fail-closed)" % SHARE_FILE
        )

    # G4 — the copy must have a SUCCESS GATE: the caller may only report a
    # staged file when the core says it staged one. This is the criterion that
    # catches the ghost attachment even if somebody re-inlines the copy in a
    # shape G1 cannot see: a function that returns a filename without ever
    # asking whether the copy succeeded.
    # G4 — the copy must have a SUCCESS GATE. A function that RETURNS A
    # FILENAME (not an outcome) and acquires the stream itself must either
    # gate on the copy result or return null when the stream was absent. The
    # pure core is exempt: it RETURNS the outcome, which IS the gate.
    for rel, _text, lines in files:
        for name, start, _end, body in _function_bodies(lines):
            if _own_acquisition(body) is None:
                continue          # only injects the stream — the core gates it
            if _returns_outcome(body):
                continue          # the outcome IS the success gate
            gated = (
                re.search(r"when\s*\(", body)
                or re.search(r"\bis\s+[A-Za-z_][A-Za-z0-9_.]*\s*->", body)
                or re.search(r"if\s*\([^\n]*==\s*null[^\n]*\)\s*\{?[^\n]*return\s+null", body)
            )
            if not gated:
                findings.append(
                    "%s:%d  [A-08 G4] %s() opens a content stream and returns a filename with NO "
                    "success gate — when the provider hands back no stream the copy stages "
                    "nothing, yet the caller still gets a name for a file that was never created "
                    "(ghost attachment)" % (rel, start + 1, name)
                )
    return findings


def _returns_outcome(body):
    """True when the function RETURNS the copy outcome (its type is a sealed
    outcome, not a filename). Such a function IS the success gate."""
    return bool(re.search(r"Copy\b|SharedStreamCopy|Outcome\b", body))


FIXTURE = "src/android/app/src/main/java/com/rikkaminis/app/network/Trace.kt"
_Q = "com.rikkaminis.app.diagnostics.ProviderHealthTracker"
FIXTURE_LINES = [
    # 1 caught — the defect that shipped: bare statement call
    "            %s.recordFirstToken(it, ms())" % _Q,
    # 2 pass — consumed by assignment
    "            val updated = %s.recordFirstToken(it, ms())" % _Q,
    # 3 pass — consumed by return
    "            return %s.recordFirstToken(it, ms())" % _Q,
    # 4 caught — safe-call receiver, still dropped
    "        tracker?.recordFirstToken(it, ms())",
    # 5 pass — inline exemption
    "        tracker.recordFirstToken(it, ms()) // record-ok: fixture, unused on purpose",
    # 6 pass — argument position
    "        println(providerHealthTracker.recordFirstToken(it, ms()))",
    # 7 pass — declaration, not a call (false positive seen on the merged tree)
    "    fun recordFirstToken(attempt: Attempt, ttfbMs: Long): Attempt =",
    # 8 pass — body of a consuming lambda (false positive seen on the merged tree)
    "            attempt = attempt?.let {",
    "                %s.recordFirstToken(it, ms())" % _Q,
    "            }",
    # 9 caught — `also` returns the receiver, so the value is still dropped
    "            attempt?.also {",
    "                %s.recordFirstToken(it, ms())" % _Q,
    "            }",
    # 10 caught — the shape that actually shipped: a statement-position `let`,
    #             whose own value is dropped, so the call inside is dropped too
    "        attempt?.let {",
    "            %s.recordFirstToken(it, ms())" % _Q,
    "        }",
    # 11 pass — lambda in argument position: the value flows into the call
    "        consume(list.map {",
    "            %s.recordFirstToken(it, ms())" % _Q,
    "        })",
    # [R3-t11 W-D] the read that makes line 2 a CONSUMPTION rather than a dead
    # binding: a bound result must be read somewhere that is not a log line.
    "        attempt = updated",
]
EXPECTED_HITS = {1, 4, 12, 15}


def self_test():
    tmp = tempfile.mkdtemp(prefix="retguard-")
    path = os.path.join(tmp, FIXTURE)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(FIXTURE_LINES) + "\n")
    got = {line for _rel, line, _t, _f, _w in scan(tmp)}
    # Every fixture line asserts one verdict (caught here, or legitimately green).
    _ARM_COUNT[0] += len(FIXTURE_LINES)
    if got != EXPECTED_HITS:
        print("SELF-TEST FAILED")
        print("  expected:", sorted(EXPECTED_HITS))
        print("  got     :", sorted(got))
        return 1
    print(
        "✅ self-test: catches the dropped recordFirstToken (bare + safe-call + "
        "inside `also`), passes assigned / returned / argument / declaration / "
        "consuming-lambda / exempt forms, and catches the R3-t11 W-D dead / "
        "log-only binding shapes"
    )
    return 0


# ─────────────────────────────────────────────────────────────────────────
# [B-04 deep-audit-1037] drain self-test: one breaker per criterion, expected
# literals hand-written from the rule table above (never derived from a run).
# ─────────────────────────────────────────────────────────────────────────

DRAIN_CLEAN = """package x
class LLMProvider
class ChatViewModel
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
): String? {
    val queuedIds = queued.map { it.id }.toSet()
    val combinedParts = mutableListOf<String>()
    if (combinedParts.isEmpty()) {
        return null
    }
    val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
    ensureTrailingRoleAlternativeBeforeUserAppend()
    agentHistory.add(LLMMessage(
        role = LLMMessage.Role.USER,
        content = userText,
        contentParts = combinedParts,
        dbMessageId = userEntity.id,
    ))
    withContext(Dispatchers.Main) {
        removeQueuedPlaceholders(queuedIds)
        _messages.value = _messages.value + ChatMessage(
            id = userEntity.id,
            role = "user",
        )
    }
    return null
}
internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    sid: String,
) {
    val combinedParts = mutableListOf<String>()
    if (combinedParts.isEmpty()) {
        return
    }
    val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
    agentHistory.add(LLMMessage(
        role = LLMMessage.Role.USER,
        content = userText,
        contentParts = combinedParts,
        dbMessageId = userEntity.id,
    ))
    withContext(Dispatchers.Main) {
        removeQueuedPlaceholders(queuedIds)
        val queuedUserMsg = ChatMessage(
            id = userEntity.id,
            role = "user",
        )
        val nextAssistantMsg = ChatMessage(
            id = newAssistantId,
            role = "assistant",
        )
        _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
    }
}
"""

def drain_self_test():
    import tempfile
    import shutil
    rel = QI_FILE

    def run(files):
        tmp = tempfile.mkdtemp(prefix="drainguard-")
        try:
            for r, text in files.items():
                p = os.path.join(tmp, r)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(text)
            return scan_queue_drain(tmp)
        finally:
            shutil.rmtree(tmp)

    clean = {rel: DRAIN_CLEAN}
    results = []
    got = run(clean)
    results.append(("clean fixture exits clean", got == [], got))

    # D1a: statement-position append (the pre-fix shape) — reds D1 binding only.
    n1 = {rel: DRAIN_CLEAN.replace(
        "    val userEntity = chatRepository.appendMessage(sid, \"user\", userPartsJson)",
        "    chatRepository.appendMessage(sid, \"user\", userPartsJson)", 1,
    )}
    got = run(n1)
    results.append((
        "D1a: statement-position append reds D1 binding only",
        len(got) == 1 and "[B-04 D1]" in got[0] and "discards" in got[0],
        got,
    ))

    # D1b: dbMessageId dropped from the agentHistory entry — reds D1 flow only.
    n2 = {rel: DRAIN_CLEAN.replace("        dbMessageId = userEntity.id,\n", "", 1)}
    got = run(n2)
    results.append((
        "D1b: missing dbMessageId reds D1 flow only",
        len(got) == 1 and "[B-04 D1]" in got[0] and "agentHistory" in got[0],
        got,
    ))

    # D2: the empty-content guard removed from the drain — reds D2 (drain) only.
    n3 = {rel: DRAIN_CLEAN.replace(
        "    if (combinedParts.isEmpty()) {\n        return null\n    }\n", "", 1,
    )}
    got = run(n3)
    results.append((
        "D2: drain empty-content guard removed reds D2 only",
        len(got) == 1 and "[B-04 D2]" in got[0],
        got,
    ))

    # [wide-1024 dataflow] D2b: the guard survives, its TERMINATION does not —
    # the empty user row falls through to appendMessage (a deterministic 400)
    # while presence + order criteria stay satisfied.
    n3b = {rel: DRAIN_CLEAN.replace(
        "    if (combinedParts.isEmpty()) {\n        return null\n    }\n",
        "    if (combinedParts.isEmpty()) {\n        log(\"empty\")\n    }\n", 1,
    )}
    got = run(n3b)
    results.append((
        "D2b: guard without a return reds D2 only",
        len(got) == 1 and "[B-04 D2]" in got[0] and "does not TERMINATE" in got[0],
        got,
    ))

    # D3a: placeholders kept (funnel bypassed) — reds D3 funnel only.
    n4 = {rel: DRAIN_CLEAN.replace("        removeQueuedPlaceholders(queuedIds)\n", "", 1)}
    got = run(n4)
    results.append((
        "D3a: funnel bypass reds D3 only",
        len(got) == 1 and "[B-04 D3]" in got[0] and "single funnel" in got[0],
        got,
    ))

    # D3b: merged bubble not appended — reds D3 bubble only.
    n5 = {rel: DRAIN_CLEAN.replace(
        "        _messages.value = _messages.value + ChatMessage(\n            id = userEntity.id,\n            role = \"user\",\n        )\n",
        "",
    )}
    got = run(n5)
    results.append((
        "D3b: missing merged bubble reds D3 only",
        len(got) == 1 and "[B-04 D3]" in got[0] and "vanishes from the screen" in got[0],
        got,
    ))

    # ── [R3-t11 见证 #133] twin-consumer arms: the SAME criteria must hold on
    # `injectQueuedPromptsAsNewTurn`. Before this round D1/D3 were anchored on
    # the drain alone, so a `dbMessageId = null` in the mid-loop inject path
    # (where a message queued during a long TOOL CALL actually lands) kept the
    # gate green — the dual-gate witness's seam.
    # D1c: inject hands the loop a null dbMessageId.
    n6 = {rel: DRAIN_CLEAN.replace(
        "        dbMessageId = userEntity.id,\n    ))\n    withContext(Dispatchers.Main) {\n"
        "        removeQueuedPlaceholders(queuedIds)\n        val queuedUserMsg",
        "        dbMessageId = null,\n    ))\n    withContext(Dispatchers.Main) {\n"
        "        removeQueuedPlaceholders(queuedIds)\n        val queuedUserMsg",
    )}
    got = run(n6)
    results.append((
        "D1c: inject dbMessageId = null reds D1 (inject only)",
        len(got) == 1 and "[B-04 D1]" in got[0] and "injectQueuedPromptsAsNewTurn" in got[0]
        and "does not flow" in got[0],
        got,
    ))

    # D1d: inject hands over a literal id instead of the persisted one.
    n7 = {rel: DRAIN_CLEAN.replace(
        "        dbMessageId = userEntity.id,\n    ))\n    withContext(Dispatchers.Main) {\n"
        "        removeQueuedPlaceholders(queuedIds)\n        val queuedUserMsg",
        "        dbMessageId = \"queued_msg_1\",\n    ))\n    withContext(Dispatchers.Main) {\n"
        "        removeQueuedPlaceholders(queuedIds)\n        val queuedUserMsg",
    )}
    got = run(n7)
    results.append((
        "D1d: inject literal dbMessageId reds D1 (inject only)",
        len(got) == 1 and "[B-04 D1]" in got[0] and "injectQueuedPromptsAsNewTurn" in got[0],
        got,
    ))

    # D3c: inject stops dropping the placeholders — N rows on screen, 1 in the DB.
    n8 = {rel: DRAIN_CLEAN.replace(
        "    withContext(Dispatchers.Main) {\n        removeQueuedPlaceholders(queuedIds)\n"
        "        val queuedUserMsg",
        "    withContext(Dispatchers.Main) {\n        val queuedUserMsg",
    )}
    got = run(n8)
    results.append((
        "D3c: inject funnel bypass reds D3 (inject only)",
        len(got) == 1 and "[B-04 D3]" in got[0] and "injectQueuedPromptsAsNewTurn" in got[0]
        and "single funnel" in got[0],
        got,
    ))

    # D3d: inject's merged bubble does not carry the persisted id.
    n9 = {rel: DRAIN_CLEAN.replace(
        "        val queuedUserMsg = ChatMessage(\n            id = userEntity.id,",
        "        val queuedUserMsg = ChatMessage(\n            id = \"assistant_x\",",
    )}
    got = run(n9)
    results.append((
        "D3d: inject bubble without the persisted id reds D3 (inject only)",
        len(got) == 1 and "[B-04 D3]" in got[0] and "injectQueuedPromptsAsNewTurn" in got[0]
        and "phantom row" in got[0],
        got,
    ))

    # D3e: the persisted row's bubble is appended twice (a kept duplicate).
    n10 = {rel: DRAIN_CLEAN.replace(
        "        _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg",
        "        _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg + queuedUserMsg",
    )}
    got = run(n10)
    results.append((
        "D3e: inject double user bubble reds D3 (inject only)",
        len(got) == 1 and "[B-04 D3]" in got[0] and "appends 2 user" in got[0],
        got,
    ))

    _ARM_COUNT[0] += len(results)
    failed = [name for name, ok, got in results if not ok]
    if failed:
        print("DRAIN SELF-TEST FAILED (%d breaker(s))" % len(failed))
        for name, ok, got in results:
            if not ok:
                print("  ✗ " + name)
                for f in got:
                    print("      " + f)
        return 1
    print(
        "✅ drain self-test has teeth: D1 (bind+flow, BOTH consumers) / D2 (empty "
        "guard, both consumers) / D3 (funnel + merged bubble + persisted id, BOTH "
        "consumers) each flip red on exactly their own breaker; clean fixture green"
    )
    return 0

# ─────────────────────────────────────────────────────────────────────────
# [§1042-A-07] staged-bytes self-test — one breaker per criterion, expected
# literal findings hand-written from the rule table (never derived from a run).
# ─────────────────────────────────────────────────────────────────────────

STAGED_LEDGER_CLEAN = """package com.rikkaminis.app.share

import java.util.concurrent.atomic.AtomicLong

internal class StagedBytesLedger(initial: Long = 0L) {
    private val total = AtomicLong(initial)

    fun current(): Long = total.get()

    fun credit(bytes: Long) {
        if (bytes > 0L) total.addAndGet(bytes)
    }

    fun debit(bytes: Long) {
        total.updateAndGet { (it - bytes).coerceAtLeast(0L) }
    }

    fun reset() {
        total.set(0L)
    }
}

internal fun attachTextByteCount(text: String): Long =
    text.toByteArray(Charsets.UTF_8).size.toLong()

internal sealed class AttachTextAdmission {
    object RejectedPerFile : AttachTextAdmission()
    object RejectedCumulative : AttachTextAdmission()
    object Admitted : AttachTextAdmission()
}

internal fun admitAttachText(
    textBytes: Long,
    currentTotalBytes: Long,
    maxFileBytes: Long,
    maxTotalBytes: Long,
): AttachTextAdmission = when {
    textBytes > maxFileBytes -> AttachTextAdmission.RejectedPerFile
    currentTotalBytes + textBytes > maxTotalBytes -> AttachTextAdmission.RejectedCumulative
    else -> AttachTextAdmission.Admitted
}
"""

def _staged_activity(branch, debit_body=None, extra=""):
    """Minimal ShareReceiverActivity slice exercising the A-07 criteria."""
    db = debit_body or "            stagedBytes.debit(bytes)\n"
    return (
        "package com.rikkaminis.app.share\n"
        "\n"
        "class ShareReceiverActivity {\n"
        "    companion object {\n"
        "        private val stagedBytes = StagedBytesLedger()\n"
        "\n"
        "        fun resetStagedBytes() {\n"
        "            stagedBytes.reset()\n"
        "        }\n"
        "\n"
        "        fun debitStagedBytes(bytes: Long) {\n"
        + db +
        "        }\n"
        "    }\n"
        "\n"
        "    private fun handleSingleSend(intent: Intent, out: MutableList<Item>) {\n"
        "        when (val d = decide()) {\n"
        "            is SingleSendDecision.InlineText -> out += Item(d.text)\n"
        + branch +
        "            is SingleSendDecision.Stream -> copy()\n"
        "            SingleSendDecision.Drop -> Unit\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private fun discardStagedAttachments(items: List<Item>) {\n"
        "        for (item in items) {\n"
        "            debitStagedBytes(item.len())\n"
        "        }\n"
        "    }\n"
        + extra +
        "}\n"
    )

STAGED_ATTACH_CLEAN = """            is SingleSendDecision.AttachText -> {
                val textBytes = attachTextByteCount(d.text)
                val admission = admitAttachText(
                    textBytes,
                    stagedBytes.current(),
                    MAX_FILE_BYTES,
                    MAX_TOTAL_BYTES,
                )
                when (admission) {
                    AttachTextAdmission.RejectedPerFile -> log(textBytes)
                    AttachTextAdmission.RejectedCumulative -> log(textBytes)
                    AttachTextAdmission.Admitted -> {
                        File(dir(), name()).writeText(d.text, Charsets.UTF_8)
                        stagedBytes.credit(textBytes)
                        out += Item(name())
                    }
                }
            }
"""

def staged_self_test():
    import tempfile
    import shutil

    rel_a = "share/ShareReceiverActivity.kt"
    rel_l = "share/StagedBytesLedger.kt"

    def run(files):
        tmp = tempfile.mkdtemp(prefix="stagedguard-")
        try:
            for r, text in files.items():
                p = os.path.join(
                    tmp, "src/android/app/src/main/java/com/rikkaminis/app", r
                )
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(text)
            return scan_staged_bytes(tmp)
        finally:
            shutil.rmtree(tmp)

    def clean_tree(activity=None, ledger=None):
        return {
            rel_a: activity if activity is not None else _staged_activity(STAGED_ATTACH_CLEAN),
            rel_l: ledger if ledger is not None else STAGED_LEDGER_CLEAN,
        }

    results = []

    # 0: clean fixture → no findings.
    got = run(clean_tree())
    results.append(("clean fixture exits clean", got == [], got))

    # S1a: the pre-fix shape — debitStagedBytes clamps a discarded copy.
    n1 = clean_tree(activity=_staged_activity(
        STAGED_ATTACH_CLEAN,
        debit_body="            totalStagedBytes.addAndGet(-bytes).coerceAtLeast(0L)\n",
        extra="        private val totalStagedBytes = java.util.concurrent.atomic.AtomicLong(0)\n",
    ))
    got = run(n1)
    ok = (len(got) == 3 and
          sum(1 for f in got if "[A-07 S1]" in f and "DISCARDED copy" in f) == 1 and
          sum(1 for f in got if "[A-07 S1]" in f and "route through the ledger" in f) == 1 and
          sum(1 for f in got if "[A-07 S3]" in f) == 1)
    results.append(("S1a: discarded-clamp shape reds S1 (clamp + funnel) and S3", ok, got))

    # S1b: routes to a raw AtomicLong instead of the ledger (no clamp in body).
    n2 = clean_tree(activity=_staged_activity(
        STAGED_ATTACH_CLEAN,
        debit_body="            totalStagedBytes.addAndGet(-bytes)\n",
        extra="        private val totalStagedBytes = java.util.concurrent.atomic.AtomicLong(0)\n",
    ))
    got = run(n2)
    ok = (len(got) == 2 and
          sum(1 for f in got if "[A-07 S1]" in f and "route through the ledger" in f) == 1 and
          sum(1 for f in got if "[A-07 S3]" in f) == 1)
    results.append(("S1b: raw-counter funnel reds S1 funnel + S3", ok, got))

    # S1c: the ledger's write-back replaced by a bare statement clamp.
    bad_ledger = STAGED_LEDGER_CLEAN.replace(
        "        total.updateAndGet { (it - bytes).coerceAtLeast(0L) }\n",
        "        total.addAndGet(-bytes).coerceAtLeast(0L)\n",
    )
    got = run(clean_tree(ledger=bad_ledger))
    ok = (len(got) == 1 and "[A-07 S1]" in got[0] and "clamp-inside-the-write" in got[0])
    results.append(("S1c: ledger statement-clamp reds S1 write-back only", ok, got))

    # S2a: the pre-fix AttachText branch — bare write, no guard, no credit.
    n3 = clean_tree(activity=_staged_activity(
        """            is SingleSendDecision.AttachText -> {
                val name = name()
                File(dir(), name).writeText(d.text, Charsets.UTF_8)
                out += Item(name)
            }
""",
    ))
    got = run(n3)
    ok = (len(got) == 3 and
          sum(1 for f in got if "[A-07 S2]" in f and "NO budget guard" in f) == 1 and
          sum(1 for f in got if "[A-07 S2]" in f and "never credits" in f) == 1 and
          sum(1 for f in got if "[A-07 S2]" in f and "attachTextByteCount()" in f) == 1)
    results.append(("S2a: unguarded AttachText reds the S2 family only (guard/credit/bytes)", ok, got))

    # S2b: guard runs AFTER the write (post-mortem) — reds the order finding.
    n4 = clean_tree(activity=_staged_activity(
        """            is SingleSendDecision.AttachText -> {
                val textBytes = attachTextByteCount(d.text)
                File(dir(), name()).writeText(d.text, Charsets.UTF_8)
                val admission = admitAttachText(textBytes, stagedBytes.current(), MAX_FILE_BYTES, MAX_TOTAL_BYTES)
                stagedBytes.credit(textBytes)
                out += Item(name())
            }
""",
    ))
    got = run(n4)
    # [wide-1024] This fixture now breaks TWO criteria at once — the guard runs
    # after the write AND its result is never consumed — so both findings are
    # required. The second one is the witnessed shape (`val admission = …` kept,
    # `when` deleted) that used to pass the whole section.
    ok = (len(got) == 2
          and any("[A-07 S2]" in f and "AFTER the write" in f for f in got)
          and any("[A-07 S2]" in f and "BOUND and never consumed" in f for f in got))
    results.append(("S2b: guard after the write + unconsumed decision red both order and "
                    "consumption criteria", ok, got))

    # [wide-1024 witness] the exact shape the external auditor used: the guards
    # are called, the result is bound, and the write/credit lines are lifted out
    # of the `when` so an over-cap share lands on disk anyway.
    n4b = clean_tree(activity=_staged_activity(
        STAGED_ATTACH_CLEAN.split("                when (admission) {")[0]
        + "                File(dir(), name()).writeText(d.text, Charsets.UTF_8)\n"
        + "                stagedBytes.credit(textBytes)\n"
        + "                out += Item(name())\n"
        + "            }\n",
    ))
    got = run(n4b)
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "BOUND and never consumed" in got[0])
    results.append(("S2c: bound-but-unconsumed admission decision reds only the consumption "
                    "criterion", ok, got))

    # S2e: char count instead of the UTF-8 byte funnel — reds byteCount only.
    n7 = clean_tree(activity=_staged_activity(
        STAGED_ATTACH_CLEAN.replace(
            "val textBytes = attachTextByteCount(d.text)",
            "val textBytes = d.text.length.toLong()",
        ),
    ))
    got = run(n7)
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "attachTextByteCount()" in got[0])
    results.append(("S2e: char-count drift reds the byte-funnel finding only", ok, got))

    # S2c: credit BEFORE the write — reds the phantom-bytes finding only.
    n5 = clean_tree(activity=_staged_activity(
        STAGED_ATTACH_CLEAN.replace(
            "                        File(dir(), name()).writeText(d.text, Charsets.UTF_8)\n"
            "                        stagedBytes.credit(textBytes)\n",
            "                        stagedBytes.credit(textBytes)\n"
            "                        File(dir(), name()).writeText(d.text, Charsets.UTF_8)\n",
        ),
    ))
    got = run(n5)
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "BEFORE the write" in got[0])
    results.append(("S2c: credit before the write reds the phantom-bytes finding only", ok, got))

    # ── [R3-t11 见证 #134] the AMOUNT credited, not just the call's presence.
    # S2f: a literal amount — the ledger never grows, so the cumulative cap
    #      reads current() == 0 forever.
    n5b = clean_tree(activity=_staged_activity(
        STAGED_ATTACH_CLEAN.replace(
            "stagedBytes.credit(textBytes)", "stagedBytes.credit(0L)",
        ),
    ))
    got = run(n5b)
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "CONSTANT" in got[0])
    results.append(("S2f: credit(0L) literal amount reds the credit-amount finding only", ok, got))

    # S2g: credits a DIFFERENT binding than the one that was admitted.
    n5c = clean_tree(activity=_staged_activity(
        STAGED_ATTACH_CLEAN.replace(
            "stagedBytes.credit(textBytes)", "stagedBytes.credit(charsOnly)",
        ),
    ))
    got = run(n5c)
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "credits `charsOnly`" in got[0])
    results.append(("S2g: crediting a different binding reds the credit-amount finding only",
                    ok, got))

    # S2h: the ledger discards the amount it was handed.
    bad_ledger3 = STAGED_LEDGER_CLEAN.replace(
        "    fun credit(bytes: Long) {\n        if (bytes > 0L) total.addAndGet(bytes)\n    }",
        "    fun credit(bytes: Long) {\n        if (bytes > 0L) total.addAndGet(0L)\n    }",
    )
    got = run(clean_tree(ledger=bad_ledger3))
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "记账恒为零" in got[0])
    results.append(("S2h: ledger credit that swaps in a literal reds the ledger-credit finding "
                    "only", ok, got))

    # S2d: the admission core lost the strict > shape (>= boundary drift).
    bad_ledger2 = STAGED_LEDGER_CLEAN.replace(
        "    textBytes > maxFileBytes -> AttachTextAdmission.RejectedPerFile",
        "    textBytes >= maxFileBytes -> AttachTextAdmission.RejectedPerFile",
    )
    got = run(clean_tree(ledger=bad_ledger2))
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "strict-`>` guard" in got[0])
    results.append(("S2d: >= boundary drift reds the admission-shape finding only", ok, got))

    # ROT: the ledger file vanished while the activity routes through it.
    got = run({rel_a: _staged_activity(STAGED_ATTACH_CLEAN)})
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "missing while the share file" in got[0])
    results.append(("ROT: missing ledger file is a fail-closed finding", ok, got))

    # ROT: the AttachText branch disappeared from handleSingleSend.
    n6 = clean_tree(activity=_staged_activity(
        """            is SingleSendDecision.AttachText -> {
                out += Item(stageText(d.text))
            }
""",
    ))
    got = run(n6)
    ok = (len(got) == 1 and "[A-07 S2]" in got[0] and "no longer writes the text itself" in got[0])
    results.append(("ROT: delegated-away writeText is a re-anchor finding", ok, got))

    _ARM_COUNT[0] += len(results)
    failed = [name for name, ok, got in results if not ok]
    if failed:
        print("STAGED-BYTES SELF-TEST FAILED (%d breaker(s))" % len(failed))
        for name, ok, got in results:
            if not ok:
                print("  ✗ " + name)
                for f in got:
                    print("      " + f)
        return 1
    print(
        "✅ staged-bytes self-test has teeth: S1 (discarded clamp / raw-counter "
        "funnel / ledger write-back), S2 (unguarded write / guard-after-write / "
        "credit-before-write / boundary drift), S3 (re-inlined counter) and the "
        "ROT arms each flip red on exactly their own breaker; clean fixture green"
    )
    return 0


def _share_stream_activity(core):
    """Minimal ShareReceiverActivity slice exercising the A-08 criteria."""
    return (
        "package com.rikkaminis.app.share\n"
        "\n"
        "internal sealed class Copy {\n"
        "    object Staged : Copy()\n"
        "    object Failed : Copy()\n"
        "}\n"
        "\n"
        "class ShareReceiverActivity {\n"
        "    private fun copyUriToStaging(uri: Uri): String? {\n"
        "        val dest = File(name())\n"
        "        return when (val outcome = copySharedStream({ resolver.openInputStream(uri) }, dest)) {\n"
        "            is Copy.Staged -> name()\n"
        "            is Copy.Failed -> null\n"
        "        }\n"
        "    }\n"
        "}\n"
        "\n"
        "internal fun copySharedStream(openStream: () -> InputStream?, openDest: () -> OutputStream): Copy {\n"
        "    return try {\n"
        + core +
        "    } catch (e: Exception) {\n"
        "        Copy.Failed\n"
        "    }\n"
        "}\n"
    )


# The §1044 ship shape: guard, then create the destination, then copy.
STREAM_CORE_CLEAN = (
    "        val input = openStream()\n"
    "            ?: return Copy.Failed\n"
    "        input.use { source ->\n"
    "            openDest().use { output -> source.copyTo(output) }\n"
    "        }\n"
    "        Copy.Staged\n"
)


def share_stream_self_test():
    import shutil
    import tempfile

    rel = os.path.join("share", "ShareReceiverActivity.kt")

    def run(text, extra=None):
        tmp = tempfile.mkdtemp(prefix="sharestream-")
        try:
            pkg = os.path.join(tmp, SRC_SUBDIR, "com", "rikkaminis", "app", "share")
            os.makedirs(pkg, exist_ok=True)
            with open(os.path.join(pkg, "ShareReceiverActivity.kt"), "w", encoding="utf-8") as fh:
                fh.write(text)
            for fname, body in (extra or {}).items():
                with open(os.path.join(pkg, fname), "w", encoding="utf-8") as fh:
                    fh.write(body)
            return scan_share_stream_guard(tmp)
        finally:
            shutil.rmtree(tmp)

    results = []

    # A08-0: the shipped shape (guard → create → copy) is clean.
    got = run(_share_stream_activity(STREAM_CORE_CLEAN))
    results.append(("A08-0: guard-before-create fixture exits clean", got == [], got))

    # A08-1: the pre-fix shape — no null check at all, `use` on the nullable.
    got = run(_share_stream_activity(
        STREAM_CORE_CLEAN.replace("            ?: return Copy.Failed\n", "")
    ))
    ok = (len(got) == 1 and "[A-08 G1]" in got[0] and "NO null early-exit" in got[0]
          and "copySharedStream()" in got[0])
    results.append(("A08-1: missing null guard reds G1 (no early exit)", ok, got))

    # A08-2: guard present but AFTER the destination is created — the 0-byte ghost.
    got = run(_share_stream_activity(
        "        val output = openDest()\n"
        "        val input = openStream()\n"
        "            ?: return Copy.Failed\n"
        "        output.use { sink -> input.use { src -> src.copyTo(sink) } }\n"
        "        Copy.Staged\n"
    ))
    ok = (len(got) == 1 and "[A-08 G1]" in got[0] and "AFTER creating the destination" in got[0])
    results.append(("A08-2: guard after create reds G1 (order)", ok, got))

    # A08-3: the other accepted spelling — a guard clause instead of elvis.
    #        Must stay green: the criterion anchors the decision, not the idiom.
    got = run(_share_stream_activity(
        "        val input = openStream()\n"
        "        if (input == null) {\n"
        "            AppLogger.warning(TAG, \"openInputStream returned null\")\n"
        "            return Copy.Failed\n"
        "        }\n"
        "        input.use { source ->\n"
        "            openDest().use { output -> source.copyTo(output) }\n"
        "        }\n"
        "        Copy.Staged\n"
    ))
    results.append(("A08-3: guard-clause spelling stays green", got == [], got))

    # A08-4: the destination creation disappeared — fail closed, re-anchor.
    got = run(_share_stream_activity(
        STREAM_CORE_CLEAN.replace(
            "            openDest().use { output -> source.copyTo(output) }\n",
            "            source.copyTo(sink)\n",
        )
    ))
    ok = (len(got) == 1 and "[A-08 G1]" in got[0] and "no `outputStream(`" in got[0])
    results.append(("A08-4: vanished write path is a fail-closed finding", ok, got))

    # A08-5: the resolver stream disappeared — the G2 anchor is gone.
    got = run(_share_stream_activity(STREAM_CORE_CLEAN).replace(
        "resolver.openInputStream(uri)", "resolver.stream(uri)"
    ))
    ok = (len(got) == 1 and "[A-08 G2]" in got[0] and "no `openInputStream(`" in got[0])
    results.append(("A08-5: vanished resolver stream is a fail-closed finding", ok, got))

    # A08-6: the extracted outcome must be consumed — a statement-position call
    #        drops the only signal that a file was written.
    tmp = tempfile.mkdtemp(prefix="sharestream-wl-")
    try:
        p = os.path.join(tmp, SRC_SUBDIR, "com", "rikkaminis", "app", rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(
                "class ShareReceiverActivity {\n"
                "    fun stage() {\n"
                "        copySharedStream(openStream = { null }, dest = f)\n"
                "    }\n"
                "}\n"
            )
        got = scan(tmp)
        ok = len(got) == 1 and got[0][3] == "copySharedStream"
        # [R3-t11 W-D] Control: a bound outcome that IS consumed (a `when`
        # subject) stays green — binding alone is not the defect, a binding
        # nobody reads is.
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(
                "class ShareReceiverActivity {\n"
                "    fun stage() {\n"
                "        when (val outcome = copySharedStream(openStream = { null }, dest = f)) {\n"
                "            is Copy.Staged -> use(outcome)\n"
                "            else -> Unit\n"
                "        }\n"
                "    }\n"
                "}\n"
            )
        control = scan(tmp)
        ok = ok and control == []
        # [R3-t11 W-D] Witness arm: the bound outcome is never read — the exact
        # regression shape the third-round dual-gate witness used. Every name
        # the gate greps for survives; the value is still dropped.
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(
                "class ShareReceiverActivity {\n"
                "    fun stage() {\n"
                "        val ignoredOutcome = copySharedStream(openStream = { null }, dest = f)\n"
                "    }\n"
                "}\n"
            )
        dead = scan(tmp)
        ok = ok and (len(dead) == 1 and "从未被读取" in dead[0][4])
        # [R3-t11 W-D] A log-only read cannot carry the value into state either.
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(
                "class ShareReceiverActivity {\n"
                "    fun stage() {\n"
                "        val ignoredOutcome = copySharedStream(openStream = { null }, dest = f)\n"
                "        AppLogger.warning(TAG, \"outcome=\" + ignoredOutcome)\n"
                "    }\n"
                "}\n"
            )
        logged = scan(tmp)
        ok = ok and (len(logged) == 1 and "只被日志读取" in logged[0][4])
    finally:
        shutil.rmtree(tmp)
    results.append(("A08-6: discarded copySharedStream outcome is watchlisted (consumed control "
                    "green, W-D dead/log-only binding caught)", ok, got + dead + logged))

    # A08-7: same regression seen by the section itself — the outcome is never
    #        bound, so nothing distinguishes "staged" from "staged nothing".
    #        The Activity acquires the stream ITSELF here (the inlined-regression
    #        shape), so the success gate (G4) is what must catch it.
    got = run(
        "package com.rikkaminis.app.share\n"
        "\n"
        "class ShareReceiverActivity {\n"
        "    private fun copyUriToStaging(uri: Uri): String? {\n"
        "        val dest = File(name())\n"
        "        copySharedStream({ resolver.openInputStream(uri) }, dest)\n"
        "        return name()\n"
        "    }\n"
        "}\n"
        "\n"
        "internal fun copySharedStream(openStream: () -> InputStream?, openDest: () -> OutputStream): Copy {\n"
        + STREAM_CORE_CLEAN +
        "}\n"
    )
    ok = (len(got) == 2 and
          sum(1 for f in got if "[A-08 G3]" in f and "never bound" in f) == 1 and
          sum(1 for f in got if "[A-08 G4]" in f) == 1)
    results.append(("A08-7: unbound copy outcome reds G3 (+G4: no success gate)", ok, got))

    # [wide-1024 witness] G3b: the outcome IS bound, the Failed arm is there and
    # still logged — it now RETURNS the staged filename, so a copy that staged
    # nothing hands the caller a name for a file that does not exist.
    got = run(
        _share_stream_activity(STREAM_CORE_CLEAN)
        .replace("            is Copy.Failed -> null\n",
                 "            is Copy.Failed -> name()\n")
    )
    ok = (len(got) == 1 and "[A-08 G3]" in got[0]
          and "instead of failing" in got[0] and "ghost attachment" in got[0])
    results.append(("A08-6b: a Failed arm that yields the staged name reds G3", ok, got))

    # A08-10: the inlined regression — the function acquires the stream itself
    #         and returns a filename with no success gate (G4).
    got = run(
        "package com.rikkaminis.app.share\n"
        "\n"
        "class ShareReceiverActivity {\n"
        "    private fun copyUriToStaging(uri: Uri): String? {\n"
        "        val dest = File(name())\n"
        "        val input = resolver.openInputStream(uri)\n"
        "        input?.use { source ->\n"
        "            openDest().use { output -> source.copyTo(output) }\n"
        "        }\n"
        "        return name()\n"
        "    }\n"
        "}\n"
    )
    ok = (len(got) == 2 and
          sum(1 for f in got if "[A-08 G4]" in f and "NO success gate" in f) == 1 and
          sum(1 for f in got if "[A-08 G1]" in f) == 1)
    results.append(("A08-10: self-acquiring copy with no success gate reds G4 (+G1)", ok, got))

    # A08-8/A08-9: the shipped layout — the core lives in its own file. The
    # criterion must follow the copy across files (it judges the function that
    # both opens the stream and creates the file, wherever that function sits).
    thin = (
        "package com.rikkaminis.app.share\n"
        "\n"
        "class ShareReceiverActivity {\n"
        "    private fun copyUriToStaging(uri: Uri): String? {\n"
        "        val dest = File(name())\n"
        "        return when (val outcome = copySharedStream({ resolver.openInputStream(uri) }, dest)) {\n"
        "            is Copy.Staged -> name()\n"
        "            is Copy.Failed -> null\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    core_file = (
        "package com.rikkaminis.app.share\n"
        "\n"
        "internal fun copySharedStream(openStream: () -> InputStream?, openDest: () -> OutputStream): Copy {\n"
        "    return try {\n"
        + STREAM_CORE_CLEAN +
        "    } catch (e: Exception) {\n"
        "        Copy.Failed\n"
        "    }\n"
        "}\n"
    )
    got = run(thin, extra={"SharedStreamCopy.kt": core_file})
    results.append(("A08-8: split-file shipped layout exits clean", got == [], got))

    got = run(thin, extra={"SharedStreamCopy.kt": core_file.replace("            ?: return Copy.Failed\n", "")})
    ok = (len(got) == 1 and "[A-08 G1]" in got[0] and "SharedStreamCopy.kt" in got[0]
          and "NO null early-exit" in got[0])
    results.append(("A08-9: unguarded core in a separate file reds G1 on that file", ok, got))

    _ARM_COUNT[0] += len(results)
    failed = [name for name, ok, _got in results if not ok]
    if failed:
        print("SHARE-STREAM SELF-TEST FAILED (%d breaker(s))" % len(failed))
        for name, ok, got in results:
            if not ok:
                print("  ✗ " + name)
                for f in got:
                    print("      " + f)
        return 1
    print(
        "✅ share-stream self-test has teeth: A-08 G1 (missing null guard / guard after "
        "create / vanished write path) and G2 (vanished resolver stream) each flip red on "
        "exactly their own breaker, the guard-clause spelling stays green, and a discarded "
        "copySharedStream outcome is caught by the watchlist"
    )
    return 0


# ─────────────────────────────────────────────────────────────────────────
# B-01 — the binary-write verdict (deep-audit-1047)
#
# One family, two channels, and BOTH of them fail WITHOUT throwing:
#
#  * `ContentResolver.openOutputStream(uri)` returns NULL when the provider
#    refuses the write or its process died. `stream?.use { … }` then skips the
#    write entirely and execution falls through to "publish the row" /
#    "hand the URI to another app" / "start the share chooser".
#  * `Bitmap.compress(...)` returns FALSE when the encoder wrote nothing. In
#    statement position (`file.outputStream().use { bmp.compress(…) }`) that
#    verdict is dropped by construction: `use` returns it and nobody reads it.
#
# Measured on the shipped tree (deep-audit-1047 B-01): a failed gallery save
# published an `IS_PENDING=0` MediaStore row for a 0-byte file and toasted
# "saved to album", and the clipboard / share / table-image paths pasted a
# FileProvider URI pointing at a 0-byte png.
#
# The criteria are derived from the DESTINATION, not from a site list:
#
#   B1  when the compressed bytes LEAVE the app or get PUBLISHED (the enclosing
#       function reaches `FileProvider.getUriForFile(` / `MediaStore.` /
#       `contentResolver.openOutputStream(`), the write must route its verdict
#       through `saveOutcome(` — the Android-free decision JVM tests pin.
#       Compression into an in-memory `ByteArrayOutputStream` (model image
#       budget, screenshot ring, debug dumps) is deliberately OUT of scope:
#       nothing is published and the caller's own length check sees the empty
#       array.
#   B2  every `openOutputStream(` site must exit on null (`?: throw|return|
#       error`, or a `requireNotNull` on the value) instead of swallowing it
#       with `?.use`. This one is tree-wide: the sites already shipping the
#       elvis form are green, so the criterion is cheap to keep honest.
# ─────────────────────────────────────────────────────────────────────────

_BIN_COMPRESS = re.compile(r"\.compress\s*\(")
# Tokens that mean "these bytes are handed to somebody outside this process".
_BIN_OUTBOUND = (
    "FileProvider.getUriForFile(",
    "MediaStore.",
    "contentResolver.openOutputStream(",
)
_BIN_OPEN_OUT = re.compile(r"openOutputStream\s*\(")
_BIN_ELVIS_EXIT = re.compile(r"\?\:\s*(?:throw|return|error|require)\b")
_BIN_REQUIRE_NON_NULL = re.compile(r"requireNotNull\s*\(")
_BIN_ROUTING = "saveOutcome("

def _innermost_body(bodies, line_no):
    """Innermost `fun` body whose [start, end] covers [line_no] (or None)."""
    best = None
    for name, start, end, body in bodies:
        if start <= line_no <= end and (best is None or start > best[1]):
            best = (name, start, end, body)
    return best

def scan_binary_write_verdict(root):
    """(violations, sites) — B-01 criteria. Findings are pre-formatted
    `file:line  [Bx] reason` strings, same contract as the sections above."""
    violations = []
    sites = 0
    base = os.path.join(root, SRC_SUBDIR)
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8", errors="replace") as fh:
                lines = fh.read().split("\n")
            bodies = _function_bodies(lines)
            for i, line in enumerate(lines):
                if _COMMENT.match(line):
                    continue
                for _m in _BIN_COMPRESS.finditer(line):
                    holder = _innermost_body(bodies, i)
                    # No body found (e.g. a call inside an object literal the
                    # declaration scan does not descend into): judge the LOCAL
                    # window, never the whole file — a file that mentions
                    # MediaStore somewhere must not make an unrelated in-memory
                    # encode look like an outbound write.
                    body = holder[3] if holder else "\n".join(lines[max(0, i - 20):i + 20])
                    if not any(tok in body for tok in _BIN_OUTBOUND):
                        continue
                    sites += 1
                    if _BIN_ROUTING in body:
                        continue
                    where = ("%s()" % holder[0]) if holder else "the file top level"
                    violations.append(
                        "%s:%d  [B1] %s compresses bytes that leave the app (FileProvider / "
                        "MediaStore / SAF) but never reads the verdict — `compress` returns "
                        "false without throwing, so route it through saveOutcome(…)"
                        % (rel, i + 1, where)
                    )
                found = _BIN_OPEN_OUT.search(line)
                if not found:
                    continue
                if "//" in line[:found.start()]:
                    continue
                sites += 1
                window = "\n".join(lines[i:i + 40])
                if _BIN_ELVIS_EXIT.search(window) or _BIN_REQUIRE_NON_NULL.search(window):
                    continue
                if "?." in line[found.start():]:
                    violations.append(
                        "%s:%d  [B2] openOutputStream(…) is chained with `?.` and the null "
                        "stream is swallowed — the write is skipped and the caller still "
                        "reports success" % (rel, i + 1)
                    )
    return violations, sites

# ── B-01 self-test: one breaker per criterion, plus the destination predicate ──

_BIN_FIXED = (
    "package probe\n"
    "\n"
    "internal fun copyPng(ctx: Context, bmp: Bitmap, cacheDir: File) {\n"
    "    val file = File(cacheDir, \"x.png\")\n"
    "    val encoded = file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }\n"
    "    if (!saveOutcome(streamOpened = true, compressed = encoded)) {\n"
    "        file.delete()\n"
    "        return\n"
    "    }\n"
    "    val uri = FileProvider.getUriForFile(ctx, \"a.b\", file)\n"
    "    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException(\"null\")\n"
    "}\n"
)

_BIN_PRE = (
    "package probe\n"
    "\n"
    "internal fun copyPng(ctx: Context, bmp: Bitmap, cacheDir: File) {\n"
    "    val file = File(cacheDir, \"x.png\")\n"
    "    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }\n"
    "    val uri = FileProvider.getUriForFile(ctx, \"a.b\", file)\n"
    "    ctx.contentResolver.openOutputStream(uri)?.use { out -> out.write(1) }\n"
    "}\n"
)

_BIN_IN_MEMORY = (
    "package probe\n"
    "\n"
    "internal fun encodeForModel(bmp: Bitmap): ByteArray {\n"
    "    val baos = ByteArrayOutputStream()\n"
    "    bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

_BIN_OPEN_ONLY = _BIN_FIXED.replace(
    "    val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException(\"null\")\n",
    "    ctx.contentResolver.openOutputStream(uri)?.use { out -> out.write(1) }\n",
)

def _bin_fixture(src):
    root = tempfile.mkdtemp(prefix="binwrite_")
    rel = os.path.join(SRC_SUBDIR, "probe", "PngWrite.kt")
    full = os.path.join(root, rel)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as fh:
        fh.write(src)
    return root

def binary_write_self_test():
    """Each arm must flip exactly the criterion built for it."""
    failures = []

    def run(src):
        root = _bin_fixture(src)
        try:
            return scan_binary_write_verdict(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def arm(name, src, expect, forbid=()):
        _ARM_COUNT[0] += 1
        v, _sites = run(src)
        blob = " ".join(v)
        ok = all(tok in blob for tok in expect) and not any(tok in blob for tok in forbid)
        if not ok:
            failures.append("B-01 %s: want %s / forbid %s, got %s"
                            % (name, list(expect), list(forbid), v))
        else:
            print("[ok] B-01 %s" % name)

    arm("the routed, null-checked write passes", _BIN_FIXED, ())
    arm("the statement-position verdict and the swallowed null stream are both caught",
        _BIN_PRE, ("[B1]", "[B2]"))
    arm("an in-memory ByteArrayOutputStream destination is out of scope",
        _BIN_IN_MEMORY, (), ("[B1]", "[B2]"))
    arm("a routed verdict with a swallowed null stream trips only [B2]",
        _BIN_OPEN_ONLY, ("[B2]",), ("[B1]",))

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    print("✅ binary-write self-test has teeth: B1 (verdict not routed through saveOutcome) "
          "and B2 (null openOutputStream swallowed) each flip red on exactly their own "
          "breaker, and the in-memory destination class stays green")
    return 0

# ─────────────────────────────────────────────────────────────────────────
# B-02 — the encode verdict family (deep-audit-1048 A-02 / B-02)
#
# `Bitmap.compress(...)` answers FALSE when the encoder could not write the
# pixels, and it does NOT throw. 1047-B01 pinned the subset whose destination
# leaves the app (see `_BIN_OUTBOUND`: FileProvider / MediaStore / SAF
# openOutputStream). The rest of the family stayed invisible, because B1 asks
# "is the destination outbound?" and these destinations are a temp file, a
# `ByteArrayOutputStream`, or a helper that writes a file and returns its PATH:
#
#   * offload tool output (`photos export`, `ui screenshot`) answering success
#     with `size_bytes: 0` / an empty base64 image;
#   * `read_image` handing the model a 0-byte "image/jpeg";
#   * `resizeJpeg` / `resizeImageBytes` returning an EMPTY ByteArray instead of
#     the `null` their own KDoc promises;
#   * browser screenshots publishing a path to a 0-byte file.
#
# The criteria are derived from the call's POSITION and the helper's SIGNATURE,
# not from a site list:
#
#   C-B2  every `.compress(` call must have its verdict consumed — the call has
#         to sit in expression position (assigned / returned / gated by `if`,
#         `!`, `&&`, `?:` / passed as an argument), or be the tail of a
#         value-returning lambda (`let`/`run`/`with`/`map…`/`sumOf`/`use`) whose
#         OWN value is consumed. `also`/`apply`/`forEach` deliberately do not
#         count: they hand back the receiver, so a value dropped inside them is
#         still dropped.
#   C-B3  a non-Unit helper that compresses must be able to REPORT a failed
#         encode to its caller: nullable return with a null exit, a `throw`, or
#         one of the repo's failure results (`saveOutcome(`, `return err(`,
#         `error(`, `success = false`). Derived from the signature + the body.
#
# Escape hatch: `record-ok: <reason>` on the call line (or the line above),
# same convention as the watchlist section. A deferred defect is a REASON too —
# write down what it is and which backlog entry owns it.
# ─────────────────────────────────────────────────────────────────────────

_ENC_COMPRESS = re.compile(r"\.compress\s*\(")
# Receiver chain immediately before the call (`bmp`, `this.bitmap`, `a.b?.c`).
_ENC_RECEIVER = re.compile(
    r"(?:[A-Za-z_][A-Za-z0-9_]*\s*(?:\?\.|\.)\s*)*[A-Za-z_][A-Za-z0-9_]*\s*$"
)
# `let|run|with|map…|sumOf|use {` — value-returning scopes (`use` returns the
# lambda's value, so a verdict assigned through it IS consumed).
_ENC_LAMBDA_OPEN = re.compile(
    r"\b(?:let|run|with|map|mapNotNull|flatMap|fold|sumOf|use)\s*\{"
)
# Failure channels a helper may use to tell its caller "the encode failed".
_ENC_FAILURE_PATTERNS = (
    re.compile(r"\bthrow\s"),
    re.compile(r"\breturn\s+null\b"),
    re.compile(r"\b(?:else|\?:)\s*null\b"),
    re.compile(r"(?<![.\w])saveOutcome\s*\("),
    re.compile(r"\breturn\s+err\s*\("),
    # Kotlin's own `error(…)` is a throw; `AppLogger.error(` is not (the `(?<![.\w])`
    # keeps a logging call from satisfying the criterion).
    re.compile(r"(?<![.\w])error\s*\("),
    re.compile(r"\bsuccess\s*=\s*false\b"),
)
_ENC_SCAN_LINES = 40  # a multi-line argument list longer than this is skipped


def _enc_call_span(code_lines, i, col):
    """(end_line, close_col) of the `(` that starts at `col` on line `i`.

    `col` points at the `(`. Returns None when the call does not close inside
    [_ENC_SCAN_LINES] lines — a failure of the parser, not of the code.
    """
    depth = 0
    for j in range(i, min(len(code_lines), i + _ENC_SCAN_LINES)):
        line = code_lines[j]
        k = col if j == i else 0
        while k < len(line):
            ch = line[k]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return j, k
            k += 1
    return None


def _enc_enclosing_open(code_lines, i):
    """Nearest block opener above [i] — `… {` or a lambda arrow `… ->`.

    The `->` form matters: `val encoded = file.outputStream().use { out ->` opens
    a value-returning scope on the same line as the arrow, and the old
    `{`-suffix-only walk treated the call under it as a bare statement.
    """
    for j in range(i - 1, -1, -1):
        s = code_lines[j].strip()
        if not s:
            continue
        if s.endswith("{") or s.endswith("->"):
            return code_lines[j]
        return ""
    return ""


def _enc_exempt(raw_lines, i, lookback=6):
    """`record-ok:` on this line, or in the contiguous comment block above it."""
    if EXEMPT in raw_lines[i]:
        return True
    for j in range(i - 1, max(-1, i - 1 - lookback), -1):
        s = raw_lines[j].strip()
        if not s:
            continue
        if not s.startswith(("//", "/*", "*")):
            return False
        if EXEMPT in raw_lines[j]:
            return True
    return False


def _enc_head_consumes(head):
    """True when the text before the receiver chain reads the call's value."""
    h = head.rstrip()
    if not h:
        return False
    if h.endswith("=") and not h.endswith(("==", "!=", ">=", "<=")):
        return True
    if h.endswith(("(", ",", "!", "&&", "||", "?:")):
        return True
    if re.search(r"\b(?:return|throw)\s*$", h):
        return True
    return False


def _enc_lambda_consumes(open_line):
    """True when a value-returning scope's own value is used on [open_line]."""
    if not _ENC_LAMBDA_OPEN.search(open_line):
        return False
    head = open_line.split("{")[0]
    if "=" in head and not head.rstrip().endswith(("==", "!=", ">=", "<=")):
        return True
    if re.search(r"\breturn\s", head):
        return True
    # argument position: `foo(bar.use { … })`
    return head.count("(") > head.count(")")


def _enc_dropped(code_lines, i, m):
    """True when the `.compress(` call at [i]:[m.start()] drops its verdict."""
    span = _enc_call_span(code_lines, i, m.end() - 1)
    if span is None:
        return False  # parser limit, not a verdict drop
    end_i, close_col = span
    post = code_lines[end_i][close_col + 1:].strip()
    if post and not post.startswith("}"):
        # `) ?: x`, `) && y`, `).also { … }` — something downstream reads it.
        return False
    head = _ENC_RECEIVER.sub("", code_lines[i][:m.start()])
    if _enc_head_consumes(head):
        return False
    if "{" in head:
        return not _enc_lambda_consumes(head)
    open_line = _enc_enclosing_open(code_lines, i)
    if open_line and _enc_lambda_consumes(open_line):
        return False
    return True


def _enc_return_type(body):
    """Declared return type of the function whose text is [body], or None."""
    depth = 0
    sig = []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "{" and depth <= 0:
            break
        sig.append(ch)
    flat = " ".join("".join(sig).split())
    m = re.search(r"\)\s*:\s*(.+)$", flat)
    if not m:
        return None
    return m.group(1).strip()


def scan_encode_verdict(root):
    """(violations, sites) — B-02 criteria (C-B2 position / C-B3 reportability).

    Findings use the same pre-formatted `file:line  [Bx] reason` contract as the
    sections above.
    """
    violations = []
    sites = 0
    base = os.path.join(root, SRC_SUBDIR)
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8", errors="replace") as fh:
                raw = fh.read().split("\n")
            code = code_only("\n".join(raw)).split("\n")
            bodies = _function_bodies(code)

            def exempt(li):
                return _enc_exempt(raw, li)

            for i, line in enumerate(code):
                for m in _ENC_COMPRESS.finditer(line):
                    sites += 1
                    if exempt(i):
                        continue
                    if not _enc_dropped(code, i, m):
                        continue
                    holder = _innermost_body(bodies, i)
                    where = ("%s()" % holder[0]) if holder else "the file top level"
                    violations.append(
                        "%s:%d  [B2] %s drops the `compress` verdict — the encoder answers "
                        "false WITHOUT throwing, so this path keeps reporting success (a "
                        "0-byte file / an empty image payload). Bind it and route the "
                        "decision through saveOutcome(streamOpened = true, compressed = …), "
                        "or add `record-ok: <reason>`" % (rel, i + 1, where)
                    )

            for fname, start, end, body in bodies:
                if ".compress" not in body:
                    continue
                rtype = _enc_return_type(body)
                if rtype is None or rtype == "Unit":
                    continue
                if not any(
                    _ENC_COMPRESS.search(code[li])
                    and not exempt(li)
                    for li in range(start, end + 1)
                ):
                    continue
                if any(p.search(body) for p in _ENC_FAILURE_PATTERNS):
                    continue
                violations.append(
                    "%s:%d  [B3] %s() compresses and returns %s, but nothing in the body "
                    "can report a failed encode (no null exit / throw / failure result) — "
                    "the caller cannot tell a real image from an encoder that wrote "
                    "nothing" % (rel, start + 1, fname, rtype)
                )
    return violations, sites

# ── C-B4: the "keep the original bytes" exit must test the verdict NEGATED ──
#
# [W-1027 W-06] C-B2/C-B3 above answer "is the compress() verdict bound and
# reportable". They do NOT answer "does the guard around the keep-the-original
# exit fire on FAILURE". `ImageBudget.compressBytes` reads
#
#     if (!saveOutcome(streamOpened = true, compressed = encoded)) { …; return input }
#
# i.e. "encoder wrote nothing → keep the caller's original bytes". Drop the `!`
# and the same line still binds the verdict (C-B2 green) and still mentions
# saveOutcome (C-B3 green), but the branch now fires on SUCCESS: every encoded
# image is thrown away and the 4-step JPEG ladder returns un-compressed input
# forever (5 MB originals ride the wire, or the whole image is elided) — the
# 413 shape `9a212e873` removed.
#
# The criterion is scoped to functions that can HAND BACK a ByteArray parameter
# (`return input`, `else input`, `?: input`), because that is the only place a
# polarity flip has a consequence a gate can name. Anything the parser cannot
# read is reported (fail-closed), never defaulted to green.
_B4_EXIT = re.compile(r"(?:\breturn\s+|\?\:\s+|\belse\s+)([A-Za-z_][A-Za-z0-9_]*)\b")
_B4_BYTE_PARAMS = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*ByteArray\b")
_B4_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_B4_BINDING = r"\bval\s+%s\s*(?::\s*Boolean\s*)?=\s*([^\n]*)"

def _b4_bound_from_outcome(body, name):
    """True when `val <name> … = <expr mentioning saveOutcome>` exists in [body]."""
    pat = re.compile(_B4_BINDING % re.escape(name))
    return any("saveOutcome" in m.group(1) for m in pat.finditer(body))

def _b4_polarity(cond, body):
    """`True` = guard fires on SUCCESS, `False` = on FAILURE, None = unknown.

    `"none"` means the condition does not derive from the encode verdict at all
    (a decode/empty-input guard), which is out of this criterion's scope.
    """
    c = cond.strip()
    if re.search(r"&&|\|\|", c):
        outcome_derived = "saveOutcome" in c or any(
            _b4_bound_from_outcome(body, w) for w in _B4_IDENT.findall(c)
        )
        return None if outcome_derived else "none"
    if "saveOutcome" in c:
        # Single-term guard: a `!` on the call (bare or parenthesised) is the
        # negation; there is nothing else to negate in a one-term condition.
        return False if "!" in c else True
    for w in _B4_IDENT.findall(c):
        if _b4_bound_from_outcome(body, w):
            neg = re.search(r"!\s*\(?\s*%s\b" % re.escape(w), c)
            return False if neg else True
    return "none"

def _b4_header(code, start, li):
    """(line index, stripped text) of the indentation-enclosing header above [li]."""
    indent = len(code[li]) - len(code[li].lstrip())
    for j in range(li - 1, start - 1, -1):
        s = code[j].rstrip()
        if not s.strip():
            continue
        ind = len(s) - len(s.lstrip())
        if ind < indent:
            return j, s.strip()
    return -1, None

def _b4_guard(code, start, li, prefix):
    """(condition text, branch) governing an exit on [li], or (None, None).

    [prefix] is the text before the exit token on the same line, so the inline
    shapes `if (!saveOutcome(…)) return input` and `val b = if (ok) a else input`
    resolve without any block walk. When the line carries no `if`, the enclosing
    header is found by indentation (multi-line blocks).
    """
    k = None
    for m in re.finditer(r"\bif\b", prefix):
        k = m.end()
    if k is not None:
        rest = prefix[k:].lstrip()
        if rest.startswith("("):
            depth = 0
            for idx, ch in enumerate(rest):
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        tail = rest[idx + 1:]
                        branch = "else" if re.search(r"\belse\b", tail) else "then"
                        return rest[1:idx], branch
            return None, None
    j, head = _b4_header(code, start, li)
    if head is None:
        return None, None
    if re.match(r"\}?\s*else\b", head):
        # Else branch: its `if` is a SIBLING at the same indentation, so the
        # strictly-smaller-indent walk above would skip past it.
        indent = len(code[j]) - len(code[j].lstrip())
        for j2 in range(j - 1, start - 1, -1):
            s2 = code[j2]
            if not s2.strip():
                continue
            ind2 = len(s2) - len(s2.lstrip())
            if ind2 <= indent and re.search(r"\bif\s*\(", s2):
                return _b4_cond_text(s2.strip()), "else"
        return None, None
    if re.search(r"\bif\s*\(", head):
        return _b4_cond_text(head), "then"
    return None, None

def _b4_cond_text(header):
    """The parenthesised condition of an `if (…)` header (inline-safe)."""
    k = header.find("if")
    rest = header[k + 2:].lstrip()
    if not rest.startswith("("):
        return None
    depth = 0
    for idx, ch in enumerate(rest):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return rest[1:idx]
    return None

def scan_failure_polarity(root):
    """(violations, sites) — C-B4: polarity of the keep-the-original guard."""
    violations = []
    sites = 0
    base = os.path.join(root, SRC_SUBDIR)
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in sorted(filenames):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            with open(path, encoding="utf-8", errors="replace") as fh:
                raw = fh.read().split("\n")
            code = code_only("\n".join(raw)).split("\n")
            for fname, start, end, body in _function_bodies(code):
                if "saveOutcome" not in body:
                    continue
                params = set(_B4_BYTE_PARAMS.findall(body.split("{")[0]))
                if not params:
                    continue
                seen = set()
                for li in range(start, end + 1):
                    for m in _B4_EXIT.finditer(code[li]):
                        if m.group(1) not in params:
                            continue
                        if _enc_exempt(raw, li):
                            continue
                        sites += 1
                        cond, branch = _b4_guard(code, start, li, code[li][:m.start()])
                        if cond is None:
                            continue  # not verdict-governed (decode/empty-input guard)
                        pol = _b4_polarity(cond, body)
                        if pol == "none":
                            continue
                        key = (li, m.group(1))
                        if key in seen:
                            continue
                        if pol is None:
                            seen.add(key)
                            violations.append(
                                "%s:%d  [B4] %s() exits with the original bytes (%s) behind a "
                                "guard over saveOutcome(...) whose polarity this gate cannot read "
                                "(`%s`) — rewrite it as `if (!saveOutcome(...))` or add "
                                "`record-ok: <reason>`" % (rel, li + 1, fname, m.group(1), cond.strip())
                            )
                            continue
                        failure_branch = "then" if pol is False else "else"
                        if branch == failure_branch:
                            continue
                        seen.add(key)
                        violations.append(
                            "%s:%d  [B4] %s() keeps the ORIGINAL bytes (%s) on the SUCCESS path "
                            "— `saveOutcome(...)` answers false only when the encoder wrote "
                            "nothing, so the guard must be negated (`if (!saveOutcome(...))`). "
                            "As written every successful encode is discarded and the caller gets "
                            "un-compressed input" % (rel, li + 1, fname, m.group(1))
                        )
    return violations, sites

# ── B-02 self-test: every arm flips exactly its own criterion ─────────────

_ENC_PROBE_PKG = "package probe\n\n"


def _enc_fixture(src):
    root = tempfile.mkdtemp(prefix="encoverdict_")
    rel = os.path.join(SRC_SUBDIR, "probe", "Encode.kt")
    full = os.path.join(root, rel)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as fh:
        fh.write(src)
    return root

# The 1047-B01 shipped shape: verdict bound through `use`, decision routed.
_ENC_COMPLIANT = (
    _ENC_PROBE_PKG +
    "internal fun savePng(bmp: Bitmap, file: File): Boolean {\n"
    "    val encoded = file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }\n"
    "    return saveOutcome(streamOpened = true, compressed = encoded)\n"
    "}\n"
)

# Unit helper, statement position, nothing reads the verdict.
_ENC_DROPPED = (
    _ENC_PROBE_PKG +
    "internal fun upload(bmp: Bitmap, out: OutputStream) {\n"
    "    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)\n"
    "}\n"
)

# Verdict bound but the helper still cannot say "failed".
_ENC_BOUND_ONLY = (
    _ENC_PROBE_PKG +
    "internal fun encode(bmp: Bitmap): ByteArray {\n"
    "    val out = ByteArrayOutputStream()\n"
    "    val encoded = bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)\n"
    "    return out.toByteArray()\n"
    "}\n"
)

# Nullable + conditional null: both criteria satisfied.
_ENC_NULLABLE = (
    _ENC_PROBE_PKG +
    "internal fun encodeOrNull(bmp: Bitmap): ByteArray? {\n"
    "    val out = ByteArrayOutputStream()\n"
    "    val encoded = bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)\n"
    "    return if (encoded) out.toByteArray() else null\n"
    "}\n"
)

# Throw on the verdict (the offload-handler shape).
_ENC_THROWING = (
    _ENC_PROBE_PKG +
    "internal fun writeOrThrow(bmp: Bitmap, out: OutputStream): Long {\n"
    "    val encoded = bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)\n"
    "    if (!encoded) throw IOException(\"encoder wrote no bytes\")\n"
    "    return 1L\n"
    "}\n"
)

# Deferred site: the escape hatch must keep it green.
_ENC_RECORD_OK = (
    _ENC_PROBE_PKG +
    "internal fun debugDump(bmp: Bitmap, out: OutputStream) {\n"
    "    // record-ok: probe fixture — deferred, see the backlog entry\n"
    "    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)\n"
    "}\n"
)


def encode_verdict_self_test():
    failures = []

    def run(src):
        root = _enc_fixture(src)
        try:
            return scan_encode_verdict(root)
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def arm(name, src, expect, forbid=()):
        _ARM_COUNT[0] += 1
        v, _sites = run(src)
        blob = " ".join(v)
        ok = all(tok in blob for tok in expect) and not any(tok in blob for tok in forbid)
        if not ok:
            failures.append("B-02 %s: want %s / forbid %s, got %s"
                            % (name, list(expect), list(forbid), v))
        else:
            print("[ok] B-02 %s" % name)

    arm("the routed, `use`-bound verdict passes", _ENC_COMPLIANT, ())
    arm("a statement-position verdict trips only [B2]", _ENC_DROPPED,
        ("[B2]",), ("[B3]",))
    arm("a bound-but-unreportable helper trips only [B3]", _ENC_BOUND_ONLY,
        ("[B3]",), ("[B2]",))
    arm("nullable + `else null` passes", _ENC_NULLABLE, ())
    arm("throwing on the verdict passes", _ENC_THROWING, ())
    arm("`record-ok:` keeps a deferred site green", _ENC_RECORD_OK, ())

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    print("✅ encode-verdict self-test has teeth: C-B2 (statement-position compress) and "
          "C-B3 (non-Unit helper that cannot report a failed encode) each flip red on "
          "exactly their own breaker; the `use`-bound, nullable, throwing and "
          "`record-ok:` shapes stay green")
    return 0

# ── C-B4 self-test: the keep-the-original guard must fire on FAILURE ──────

# The shipped shape: negated verdict, original bytes only on an encode failure.
_B4_CLEAN = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray, quality: Int): ByteArray {\n"
    "    val baos = ByteArrayOutputStream()\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)\n"
    "    if (!saveOutcome(streamOpened = true, compressed = encoded)) {\n"
    "        log(\"encoder wrote no bytes\")\n"
    "        return input\n"
    "    }\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

# [W-1027 W-06] The witness patch: same binding, same saveOutcome(, one `!`
# removed. C-B2 and C-B3 stay green — only C-B4 can see it.
_B4_INVERTED = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray, quality: Int): ByteArray {\n"
    "    val baos = ByteArrayOutputStream()\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)\n"
    "    if (saveOutcome(streamOpened = true, compressed = encoded)) {\n"
    "        log(\"encoder wrote no bytes\")\n"
    "        return input\n"
    "    }\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

# One-line short-circuit form of the clean shape.
_B4_INLINE_CLEAN = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray): ByteArray {\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)\n"
    "    if (!saveOutcome(streamOpened = true, compressed = encoded)) return input\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

# Verdict parked in a local, then tested without the negation.
_B4_BOUND_POSITIVE = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray): ByteArray {\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)\n"
    "    val ok = saveOutcome(streamOpened = true, compressed = encoded)\n"
    "    if (ok) {\n"
    "        log(\"keep original\")\n"
    "        return input\n"
    "    }\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

# Same, but negated — the local-variable form of the clean shape.
_B4_BOUND_NEGATED = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray): ByteArray {\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)\n"
    "    val ok = saveOutcome(streamOpened = true, compressed = encoded)\n"
    "    if (!ok) return input\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

# Inverted through the else branch: original bytes on the SUCCESS path.
_B4_ELSE_INVERTED = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray): ByteArray {\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)\n"
    "    if (!saveOutcome(streamOpened = true, compressed = encoded)) {\n"
    "        log(\"failed\")\n"
    "    } else {\n"
    "        return input\n"
    "    }\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

# Compound guard: outcome-derived but the polarity is not readable → fail closed.
_B4_COMPOUND = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray, retry: Boolean): ByteArray {\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)\n"
    "    if (saveOutcome(streamOpened = true, compressed = encoded) && retry) {\n"
    "        return input\n"
    "    }\n"
    "    return baos.toByteArray()\n"
    "}\n"
)

# A decode/empty-input guard is out of scope: it never mentions the verdict.
_B4_UNRELATED_GUARD = (
    _ENC_PROBE_PKG +
    "internal fun compressBytes(input: ByteArray): ByteArray {\n"
    "    if (input.isEmpty()) return input\n"
    "    val encoded = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)\n"
    "    if (!saveOutcome(streamOpened = true, compressed = encoded)) return input\n"
    "}\n"
)

def failure_polarity_self_test():
    """Every arm below flips exactly C-B4 (never cascades into B2/B3)."""
    failures = []

    def run(src):
        root = _enc_fixture(src)
        try:
            violations, _sites = scan_failure_polarity(root)
            return violations
        finally:
            shutil.rmtree(root, ignore_errors=True)

    def arm(name, src, expect, forbid=()):
        _ARM_COUNT[0] += 1
        blob = " ".join(run(src))
        ok = all(tok in blob for tok in expect) and not any(tok in blob for tok in forbid)
        if not ok:
            failures.append("C-B4 %s: want %s / forbid %s, got %s"
                            % (name, list(expect), list(forbid), blob))
        else:
            print("[ok] C-B4 %s" % name)

    arm("negated verdict guarding `return input` passes", _B4_CLEAN, (), ("[B4]",))
    arm("the witness patch (polarity inverted) trips [B4]", _B4_INVERTED,
        ("[B4]", "SUCCESS path"), ())
    arm("the inline `if (!saveOutcome(...)) return input` passes", _B4_INLINE_CLEAN,
        (), ("[B4]",))
    arm("a positive guard through a local boolean trips [B4]", _B4_BOUND_POSITIVE,
        ("[B4]", "SUCCESS path"), ())
    arm("the same local boolean negated passes", _B4_BOUND_NEGATED, (), ("[B4]",))
    arm("original bytes on the `else` (success) branch trips [B4]", _B4_ELSE_INVERTED,
        ("[B4]", "SUCCESS path"), ())
    arm("an unreadable (compound) outcome guard fails closed", _B4_COMPOUND,
        ("[B4]", "cannot read"), ())
    arm("a non-verdict guard (empty input) is out of scope", _B4_UNRELATED_GUARD,
        (), ("[B4]",))

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    print("✅ failure-polarity self-test has teeth: C-B4 (the keep-the-original "
          "exit must hang off a NEGATED saveOutcome) flips red on the inverted "
          "`if`, the inverted local and the inverted else branch, fails closed on "
          "an unreadable compound guard, and stays green on the negated, inline, "
          "local-negated and non-verdict shapes")
    return 0

def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--self-test":
        rc = self_test()
        rc2 = drain_self_test()
        rc3 = staged_self_test()
        rc4 = share_stream_self_test()
        rc5 = binary_write_self_test()
        rc6 = encode_verdict_self_test()
        # [W-1027 W-06] C-B4 arms (counted into the same registration marker).
        rc7 = failure_polarity_self_test()
        # [R3-t11] Registration marker: a stable tail the main session asserts on
        # (a gate gutted to `sys.exit(0)` cannot print it with a plausible count).
        if (rc or rc2 or rc3 or rc4 or rc5 or rc6 or rc7):
            return 1
        print("DISCARDED_RETURN_GUARD_SELFTEST=PASS (%d arms)" % _ARM_COUNT[0])
        return 0
    violations = scan(args[0])
    # [B-04 deep-audit-1037] queue-drain persistence section. Its findings are
    # pre-formatted `file:line  [criterion] reason` strings, so they are
    # reported in their own block — the watchlist tuple format above is left
    # exactly as it was.
    drain_violations = scan_queue_drain(args[0])
    # [§1042-A-07 deep-audit-1042] staged-bytes ledger section. Same pre-formatted
    # file:line [criterion] reason contract as the drain section above.
    staged_violations = scan_staged_bytes(args[0])
    # [§1044-A08 deep-audit-1044] share inbound stream section: null stream must
    # not reach the composer as a ghost attachment. Same pre-formatted
    # `file:line [criterion] reason` contract as the sections above.
    stream_violations = scan_share_stream_guard(args[0])
    # [deep-audit-1047 B-01] binary-write verdict section (compress /
    # openOutputStream). Same pre-formatted `file:line [criterion] reason`
    # contract as the sections above.
    binary_violations, _binary_sites = scan_binary_write_verdict(args[0])
    # [deep-audit-1048 A-02 / B-02] encode-verdict section (compress outside the
    # outbound destinations). Same pre-formatted `file:line [criterion] reason`
    # contract as the sections above.
    encode_violations, _encode_sites = scan_encode_verdict(args[0])
    # [W-1027 W-06] C-B4: polarity of the keep-the-original guard around
    # saveOutcome(…) — the `!` that decides which branch is the FAILURE one.
    polarity_violations, _polarity_sites = scan_failure_polarity(args[0])
    if violations:
        print("❌ 丢弃了必须消费的返回值（看似接线，实则没生效）：")
        for rel, line, snippet, fn, why in violations:
            print("  %s:%d" % (rel, line))
            print("      %s" % snippet)
            print("      %s: %s" % (fn, why))
            print("      修法: 把返回值接下去（val x = ... 后写入/追加），或在行内加 `record-ok: <理由>`。")
        print("")
        print("  共 %d 处。" % len(violations))
    if drain_violations:
        print("❌ 队列排空段：drain 的持久化不变量被打破（B-04）：")
        for msg in drain_violations:
            print("  %s" % msg)
        print("")
        print("  共 %d 处。" % len(drain_violations))
    if staged_violations:
        print("❌ 分享暂存字节账本段：计数器钳制/守卫不变量被打破（A-07）：")
        for msg in staged_violations:
            print("  %s" % msg)
        print("")
        print("  共 %d 处。" % len(staged_violations))
    if stream_violations:
        print("❌ 分享入站流段：null 流会被当成已暂存文件（A-08）：")
        for msg in stream_violations:
            print("  %s" % msg)
        print("")
        print("  共 %d 处。" % len(stream_violations))
    if binary_violations:
        print("❌ 二进制写入判定段：compress/openOutputStream 的失败结果被丢弃（B-01）：")
        for msg in binary_violations:
            print("  %s" % msg)
        print("")
        print("  共 %d 处。" % len(binary_violations))
    if encode_violations:
        print("❌ 编码判定段：compress 的 false 结果没有到达任何失败出口（B-02）：")
        for msg in encode_violations:
            print("  %s" % msg)
        print("")
        print("  共 %d 处。" % len(encode_violations))
    if polarity_violations:
        print("❌ 失败极性段：保留原始字节的出口挂在了 saveOutcome 的 SUCCESS 分支（B-04）：")
        for msg in polarity_violations:
            print("  %s" % msg)
        print("")
        print("  共 %d 处。" % len(polarity_violations))
    if (violations or drain_violations or staged_violations or stream_violations
            or binary_violations or encode_violations or polarity_violations):
        return 1
    print(
        "✅ discarded-return guard: no watchlisted return value is dropped "
        "(%d watched function(s))" % len(WATCHLIST)
    )
    print("✅ discarded-return guard: queue-drain persistence section green")
    print("✅ discarded-return guard: share staged-bytes accounting section green")
    print("✅ discarded-return guard: share inbound stream section green (null stream never staged)")
    print("✅ discarded-return guard: binary-write verdict section green (compress verdict routed, "
          "no swallowed openOutputStream)")
    print("✅ discarded-return guard: encode verdict section green (every compress verdict reaches "
          "a failure exit)")
    print("✅ discarded-return guard: failure-polarity section green (every keep-the-original "
          "exit hangs off a NEGATED saveOutcome)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
