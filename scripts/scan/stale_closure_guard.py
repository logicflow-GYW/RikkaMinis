#!/usr/bin/env python3
"""Stale-closure guard — RikkaMinis CI scan gate.

## Why this gate exists

2026-09-20, for real: FIX-4 (7d603596) fixed a broken throttle in
`StreamingMarkdownTextBody` by dropping `content` from the `produceState` key
list. The throttle started working — and the streaming text froze at whatever
was on screen when the row first composed. The user saw the first two
characters of an answer and nothing else, until they left the session and came
back.

The mechanism is a Compose footgun with three parts:

  1. `produceState`'s keys ARE `LaunchedEffect` keys (`remember(key)`), so a
     key change cancels and restarts the producer coroutine.
  2. When the key does NOT change, the producer closure is NOT rebuilt — it
     keeps whatever it captured when it was created.
  3. `snapshotFlow { x }` re-emits only for values it can OBSERVE through the
     snapshot system. Reading a plain parameter (`content: String`) registers
     no dependency, so the flow emits exactly ONCE.

So: a long-lived effect (produceState / LaunchedEffect / rememberCoroutineScope
launch) whose body reads a plain parameter that the CALLER updates every
recomposition — without `rememberUpdatedState` — silently goes stale. Nothing
crashes, no test fails, and the UI just stops updating.

The fix is always the same: route the changing value through
`rememberUpdatedState` and read the returned State inside the effect.

## What this gate flags

For every `produceState(...)` call in production sources, take the parameter
names of the enclosing function. If the producer body reads one of them
directly (not via `rememberUpdatedState`, not via a `State`-typed source, not
via `.value`), the value can go stale when that parameter is removed from the
key list. `rememberUpdatedState(x)` on the same name clears the finding.

Escape hatch: `stale-ok: <reason>` on the same or the previous line.

Usage: python3 scripts/scan/stale_closure_guard.py <repo_root>
Exit:  0 = clean, 1 = violation(s) found
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    function_body,
    match_bracket,
    split_top as _shared_split_top,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args as _shared_call_args,
    code_view as _shared_code_view,
    contains_ident,
    decl_sites,
    expr_end,
    path_feasible,
)
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.S)
ESCAPE_RE = re.compile(r"stale-ok\s*:")
LINE_COMMENT_RE = re.compile(r"//.*$")

# 只检查这些「长生命周期 effect」构造器
EFFECT_RE = re.compile(r"\bproduceState\s*(?:<[^>]*>)?\s*\(")

# 生产源码根（相对 repo root）
SRC_ROOTS = [
    "src/android/app/src/main/java",
]

# 已知安全的调用点：effect 内部不读任何会变的普通参数。
# 每条都必须写明为什么安全。
ALLOWED = {
    # 读的是 State 支撑的值或常量，与参数变化无关
    "com/rikkaminis/app/ui/chat/ChatMiscViews.kt": "reads block.id/tabPool/lifecycleOwner, all stable keys",
}


def strip_comments(text):
    r"""Blank comments AND the CONTENTS of string/char literals, preserving
    offsets and newlines.

    [wide3-04] The old version ran `BLOCK_COMMENT_RE = /\*.*?\*/` over the raw
    text, blind to string literals: one `"/*"` anywhere above a produceState
    opened a "comment" that swallowed everything up to the next `"*/"` — the
    whole producer body became a blank line and the gate reported nothing to
    judge. `code_only` (used by the B-03/B-05 sections) already got this right;
    the P1 section was still on the old stripper.

    Lines carrying the `stale-ok:` escape hatch are kept verbatim, because the
    hatch itself lives inside a comment.
    """
    lines = code_only(text).split("\n")
    for i, raw in enumerate(text.split("\n")):
        if ESCAPE_RE.search(raw) and i < len(lines):
            lines[i] = raw
    return lines


def enclosing_params(lines, idx):
    """向上找最近的函数签名，返回 (参数名集合, 签名起始行)。"""
    for i in range(idx, max(-1, idx - 120), -1):
        line = lines[i]
        if re.search(r"\bfun\s+\w+\s*[(<]", line) or re.search(r"^\s*(private |internal |public )?\bfun\b", line):
            # 收集签名（可能跨行）直到匹配的 ')'
            sig = []
            depth = 0
            started = False
            for j in range(i, min(len(lines), i + 60)):
                sig.append(lines[j])
                depth += lines[j].count("(") - lines[j].count(")")
                if "(" in lines[j]:
                    started = True
                if started and depth <= 0:
                    break
            blob = " ".join(sig)
            params = set()
            for m in re.finditer(r"(\w+)\s*:\s*[A-Za-z_][\w<>,.?\s]*", blob):
                params.add(m.group(1))
            return params, i
    return set(), -1


def effect_body(lines, start_idx):
    """取出 produceState 调用的 lambda 体与 key 实参文本。

    produceState(initialValue = X, key1, key2, ...) { block }
      - 第一个实参 initialValue 不是 key
      - 最后一个实参是 block（从第一个 '{' 开始）
    只有中间的 key 实参能阻止闭包 stale。返回 (body行, 行区间, key文本)。
    """
    depth = 0
    begun = False
    body = []
    key_parts = []
    # 收集调用头：从 produceState( 之后到第一个 '{' 之前
    for j in range(start_idx, min(len(lines), start_idx + 200)):
        line = lines[j]
        body.append(line)
        if not begun:
            key_parts.append(line)
        depth += line.count("{") - line.count("}")
        depth += line.count("(") - line.count(")")
        if "{" in line:
            begun = True
        if begun and depth <= 0:
            return body, (start_idx, j), split_key_args("\n".join(key_parts))
    return body, (start_idx, min(len(lines), start_idx + 200)), split_key_args("\n".join(key_parts))


def split_key_args(head: str) -> str:
    """从调用头里剥掉第一个实参（initialValue），只保留 key 实参。

    head 形如：`val x by produceState(initialValue = content, isStreaming) {`
    """
    open_idx = head.find("(")
    if open_idx < 0:
        return head
    inner = head[open_idx + 1:]
    # 按顶层逗号切分
    parts = []
    depth = 0
    cur = []
    for ch in inner:
        if ch in "([<":
            depth += 1
        elif ch in ")]>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        parts.append("".join(cur))
    # 去掉 block 起始（含 '{'）之后的残余
    cleaned = []
    for p in parts:
        if "{" in p:
            p = p[: p.index("{")]
        p = p.strip()
        if p:
            cleaned.append(p)
    # 第一个是 initialValue，不是 key
    return ", ".join(cleaned[1:]) if len(cleaned) > 1 else ""


def scan_file(path, rel):
    raw = open(path, encoding="utf-8", errors="replace").read()
    lines = strip_comments(raw)
    findings = []
    for i, line in enumerate(lines):
        if not EFFECT_RE.search(line):
            continue
        params, _sig = enclosing_params(lines, i)
        if not params:
            continue
        body, (b0, b1), key_text = effect_body(lines, i)
        body_text = "\n".join(body)

        # [wide3-04] Only the EFFECT LAMBDA is a read zone. The call head's
        # `initialValue = <param>` and the key list are not reads of the
        # captured closure — counting them is why the old code had to skip
        # every parameter that had a `rememberUpdatedState` wrapper somewhere
        # nearby, which in turn is why wrapping `content` and then reading the
        # RAW `content` inside the body sailed through.
        lam = read_zone(body_text)
        if lam is None:
            # Fail closed: a producer body that cannot be located is exactly
            # what a regression looks like.
            findings.append((i + 1, "<body>"))
            continue

        for p in sorted(params):
            # 参数出现在 key 列表里 ⇒ 每次变化都重启闭包 ⇒ 不会 stale
            if re.search(r"(?<![\w.])" + re.escape(p) + r"(?![\w:])", key_text):
                continue
            # The wrapper call itself is not a read of the captured value.
            zone = re.sub(r"rememberUpdatedState\s*\(\s*" + re.escape(p) + r"\s*\)",
                          "", lam)
            # 直接读该参数（裸标识符，非 .value / 非声明）
            if re.search(r"(?<![\w.])" + re.escape(p) + r"(?![\w:])", zone):
                exempt = False
                for k in range(max(0, b0 - 1), min(len(lines), b1 + 2)):
                    if ESCAPE_RE.search(lines[k]):
                        exempt = True
                if exempt:
                    continue
                findings.append((i + 1, p))
    return findings


def read_zone(body_text):
    """The trailing-lambda body of a produceState call, or None.

    The lambda opens at the first `{` that sits at paren depth 0 — a `{` inside
    the argument list (a lambda default, a `buildString { }` in a key) is not
    the producer body.
    """
    dp = 0
    for k, ch in enumerate(body_text):
        if ch == "(":
            dp += 1
        elif ch == ")":
            dp -= 1
        elif ch == "{" and dp <= 0:
            return body_text[k:]
    return None


# ─────────────────────────────────────────────────────────────────────────
# [B-03 deep-audit-1037] bottom-verdict section
#
# 2026-10 audit: the DragInteraction.Stop handler in ChatScreen.kt compared
# the viewport-TOP index (`firstVisibleItemIndex`) against `total - 2` — a
# reversed formula left over from the reverseLayout era. On a forward list
# with a multi-row viewport it read FALSE at the true bottom (streaming
# stopped following after a drag) and TRUE when a tall last item's top was
# at the viewport top (a reader got yanked back). The same audit found
# `isNearBottom`'s `remember(listState, nearBottomThresholdPx)` captured the
# threshold but never consumed it in the body — the scrollNearBottomDp knob
# was dead on the fallback arm.
#
# Three criteria (self-test carries one breaker each):
#   S1  the DragStop branch must not reference the reversed-era
#       viewport-top formula family (firstVisibleItemIndex /
#       firstVisibleItemScrollOffset);
#   S2  `nearBottomThresholdPx` must be consumed inside the remember body
#       whose key list carries it (captured-but-unused = this gate's family);
#   S3  the bottom verdict has ONE implementation (`isListAtBottom`) defined
#       once and called from both consumers.
# ─────────────────────────────────────────────────────────────────────────

SCREEN_FILE = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"


def match_paren(text, open_idx):
    """Index of the ')' matching the '(' at `open_idx`, or -1.

    [R3-t10] Thin alias over the shared `_kotlin_ast.match_bracket` — the
    self-written counter is gone. `text` must be a masked view.
    """
    return match_bracket(text, open_idx)

def split_top(text, sep=","):
    """Top-level split of `text` on `sep`.

    [R3-t10] Thin alias over the shared `_kotlin_ast.split_top`. The old copy
    was quote-aware because it also ran on raw text; every caller here passes a
    masked view (`code_only` / `code_view`), where literal interiors are already
    blanks, so bracket awareness is the whole requirement.
    """
    return _shared_split_top(text, (sep,))

def call_args(text, name):
    """[(args_list, start)] for every `name(...)` call in `text`."""
    out = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\s*\(", text):
        po = m.end() - 1
        spans = _shared_call_args(text, po)
        if not spans:
            out.append((None, m.start()))
            continue
        out.append(([text[a:b].strip() for a, b in spans], m.start()))
    return out

def threshold_param_index(lines):
    """Index of the `isListAtBottom` parameter that carries the threshold, or
    None when the declaration cannot be read (the caller then falls back to
    "some argument must be derived from the knob")."""
    for i, ln in enumerate(lines):
        m = re.search(r"fun\s+isListAtBottom\s*\(", ln)
        if not m:
            continue
        blob = "\n".join(lines[i:min(len(lines), i + 12)])
        po = blob.find("(")
        pc = match_paren(blob, po)
        if pc < 0:
            return None
        inner = blob[po + 1:pc]
        params = [inner[a:b].strip() for a, b in split_top(inner, ",")]
        params = [p for p in params if p]
        for idx, p in enumerate(params):
            if re.search(r"threshold", p, re.I):
                return idx
        return None
    return None

def code_only(text):
    """Blank comments and string-literal contents (offsets and newlines kept).

    [R3-t10] Thin alias over the shared lexer (`_kotlin_valueflow.code_view`
    -> `_kotlin_lexer.mask_code`). The self-written char walker is gone: it
    was one more copy of the masking logic every gate needs, and it disagreed
    with the shared one on template interpolation (`"${f("/*")}"`). Braces /
    quotes inside literals are blanked by the shared implementation too, so
    brace matching and identifier scans only ever see real code.
    """
    return _shared_code_view(text)

def extract_block(lines, sig_re):
    """Brace-matched block starting at the first line matching sig_re.

    The body brace is the first `{` at paren level 0 AFTER the signature's
    parameter list closes — lambda defaults inside the parameter list
    (`onCopy: () -> Unit = {}`) sit at paren level 1 and are skipped, which a
    naive "first brace opens the body" walk gets wrong.

    Returns (start, end) inclusive line indices, or (-1, -1).
    """
    for i, ln in enumerate(lines):
        if not sig_re.search(ln):
            continue
        paren = 0
        body_line = -1
        j = i
        while j < len(lines) and body_line < 0:
            line = lines[j]
            k = 0
            while k < len(line):
                c = line[k]
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

STOP_BRANCH_RE = re.compile(
    r"is\s+androidx\.compose\.foundation\.interaction\.DragInteraction\.Stop\s*->"
)
REVERSED_FORMULA_RE = re.compile(
    r"firstVisibleItemIndex|firstVisibleItemScrollOffset"
)

def scan_bottom_verdict(root):
    """[B-03 deep-audit-1037] S1/S2/S3. Skipped when ChatScreen.kt is absent
    (synthetic fixture trees); fail-closed once the file exists."""
    findings = []
    path = os.path.join(root, SCREEN_FILE)
    if not os.path.exists(path):
        return findings
    raw = open(path, encoding="utf-8", errors="replace").read()
    code = code_only(raw)
    lines = code.split("\n")

    # S1: DragStop branch free of the reversed-era formula
    s0, s1 = extract_block(lines, STOP_BRANCH_RE)
    if s0 < 0:
        findings.append(
            "ChatScreen.kt:?  [B-03 S1] DragInteraction.Stop branch NOT FOUND (fail-closed)"
        )
    else:
        branch = "\n".join(lines[s0:s1 + 1])
        if REVERSED_FORMULA_RE.search(branch):
            findings.append(
                "ChatScreen.kt:%d  [B-03 S1] reversed-era viewport-top formula "
                "(firstVisibleItemIndex/offset) back in the DragStop branch — it reads "
                "FALSE at the true bottom and TRUE when a tall last item's top is at "
                "the viewport top" % (s0 + 1)
            )

    # S2: the threshold is consumed INSIDE its remember body by the bottom
    # verdict — a mention is not a consumption.
    rem_re = re.compile(r"remember\s*\([^)]*nearBottomThresholdPx[^)]*\)")
    r_idx = next((i for i, ln in enumerate(lines) if rem_re.search(ln)), -1)
    if r_idx < 0:
        findings.append(
            "ChatScreen.kt:?  [B-03 S2] remember(...nearBottomThresholdPx...) NOT FOUND (fail-closed)"
        )
    else:
        b0, b1 = extract_block(lines, rem_re)
        region = "\n".join(lines[r_idx:b1 + 1]) if b0 >= 0 else lines[r_idx]
        n_hits = len(re.findall(r"nearBottomThresholdPx", region))
        # 1 hit = the key list only (captured but never consumed)
        if n_hits < 2:
            findings.append(
                "ChatScreen.kt:%d  [B-03 S2] nearBottomThresholdPx captured in the "
                "remember key list but never consumed in the body — the "
                "scrollNearBottomDp knob is dead (this gate's own family)" % (r_idx + 1)
            )
        else:
            # [wide3-04] …and the consumption must reach the verdict ARGUMENT.
            # The old criterion counted mentions, so one dead
            # `if (false) nearBottomThresholdPx.toInt()` line kept the count
            # above 2 while the real call passed a hard-coded `0`.
            idx = threshold_param_index(lines)
            calls = call_args(region, "isListAtBottom")
            if not calls:
                findings.append(
                    "ChatScreen.kt:%d  [B-03 S2] the remember body never calls the shared "
                    "bottom verdict — the knob cannot reach it" % (r_idx + 1)
                )
            for args, _at in calls:
                if args is None:
                    findings.append(
                        "ChatScreen.kt:%d  [B-03 S2] isListAtBottom call cannot be parsed "
                        "(fail-closed)" % (r_idx + 1)
                    )
                    continue
                if idx is not None and idx < len(args):
                    picked = args[idx]
                else:
                    picked = " ".join(args)
                if "nearBottomThresholdPx" not in picked:
                    findings.append(
                        "ChatScreen.kt:%d  [B-03 S2] isListAtBottom is called with '%s' "
                        "instead of a value derived from nearBottomThresholdPx — the "
                        "scrollNearBottomDp knob does not reach the verdict"
                        % (r_idx + 1, picked.strip() or "<empty>")
                    )

    # S3: one bottom implementation, two consumers
    defs = [i for i, ln in enumerate(lines) if re.search(r"fun\s+isListAtBottom\s*\(", ln)]
    total_calls = sum(ln.count("isListAtBottom(") for ln in lines)
    if len(defs) != 1:
        findings.append(
            "ChatScreen.kt:?  [B-03 S3] isListAtBottom defined %d time(s) — the bottom "
            "verdict must have exactly one implementation" % len(defs)
        )
    elif total_calls - 1 < 2:
        findings.append(
            "ChatScreen.kt:?  [B-03 S3] isListAtBottom called %d time(s) — both "
            "consumers (isNearBottom + DragStop) must route through the shared "
            "verdict" % (total_calls - 1)
        )

    # ── S4 [R3-t10] the verdict's OWN body consumes the threshold ──────────
    # S2 judges the call argument; the round-3 seam was one hop deeper: the
    # argument arrives, and the pure function drops it
    # (`return distance >= 0 && distance <= 0`). Both gates of the pair were
    # green because neither looked inside the callee.
    findings += _s4_verdict_body(code)

    return findings


def _local_aliases(body, name):
    """Names bound in `body` to an expression that reads `name` (one hop).

    [R3-t10] Tolerance, not a hole: `val limit = thresholdPx` / `val boundary =
    cutoffSortOrder` are legitimate rewrites of the same value flow. A criterion
    that only accepts the bare identifier reds them — the false-positive class
    the round-3 report caught in the L4 criterion one gate over. The hop is
    one only: aliasing an alias is not followed.
    """
    out = set()
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+?)?=(?!=)", body):
        rhs = body[m.end():expr_end(body, m.end())]
        if contains_ident(rhs, name):
            out.add(m.group(1))
    return out


def _s4_verdict_body(code):
    """[R3-t10 S4] isListAtBottom's returned value must depend on the threshold.

    Derived, not spelled: the parameter is the one whose name mentions
    `threshold` (falling back to the last parameter, the documented order), and
    the judgement is "a parameter cannot change the answer" — either the
    returned expression never reads it, or the returned boolean is a constant
    once folded (`... || true` / `... && false`).
    """
    findings = []
    fb = function_body(code, "isListAtBottom")
    if fb is None:
        # S3 already fails closed when the definition is missing.
        return findings
    b_start, b_end, kind = fb
    body = code[b_start:b_end]
    m = re.search(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]*\.)?isListAtBottom\s*\(", code)
    if not m:
        return ["ChatScreen.kt:?  [B-03 S4] isListAtBottom signature NOT FOUND (fail-closed)"]
    p_open = m.end() - 1
    p_close = match_bracket(code, p_open)
    if p_close < 0:
        return ["ChatScreen.kt:?  [B-03 S4] isListAtBottom parameter list unparseable (fail-closed)"]
    params = [code[p_open + 1:p_close][a:b].strip()
              for a, b in _shared_split_top(code[p_open + 1:p_close], (",",))]
    params = [p for p in params if p]
    if not params:
        return ["ChatScreen.kt:?  [B-03 S4] isListAtBottom has no parameters (fail-closed)"]
    th = next((p.split(":")[0].strip() for p in params if "threshold" in p.lower()), None)
    if th is None:
        th = params[-1].split(":")[0].strip()
    line = code[:b_start].count("\n") + 1

    if kind == "expr":
        rets = [body.strip()]
    else:
        rets = [body[r.start():expr_end(body, r.end())].strip()
                for r in re.finditer(r"\breturn\b(?!@)", body)]
    rets = [r for r in rets if r]
    if not rets:
        findings.append(
            "ChatScreen.kt:%d  [B-03 S4] isListAtBottom has no return value to judge "
            "(fail-closed)" % line)
        return findings
    readers = {th} | _local_aliases(body, th)
    reading = [r for r in rets if any(contains_ident(r, nm) for nm in readers)]
    if not reading:
        findings.append(
            "ChatScreen.kt:%d  [B-03 S4] isListAtBottom never reads its `%s` parameter "
            "in the value it returns — the scrollNearBottomDp knob is dead one hop "
            "below the call the S2 criterion already checks" % (line, th))
        return findings
    if all(const_bool(r) is not None for r in reading):
        findings.append(
            "ChatScreen.kt:%d  [B-03 S4] isListAtBottom folds to a constant (%r) even "
            "though it names `%s` — the threshold is absorbed and cannot change the "
            "verdict" % (line, reading[0][:70], th))
    return findings

# ─────────────────────────────────────────────────────────────────────────
# [B-05 deep-audit-1037] visible-content section
#
# Five entry points ask "does this user row show anything?"; each carried its
# own copy (images-only / content-only / text-parts-only) so a pure
# non-image attachment message vanished on reload, could not be retried, and
# shifted the retry/edit cutoff. The fix routes all five through ONE shared
# predicate. Three criteria:
#   V1  the predicate has exactly one definition (not five copies);
#   V2  each of the five call sites references the shared predicate
#       (per-file fail-closed when the file exists);
#   V3  the queued withdraw button is no longer gated by the caption
#       (it sits outside the content-only block).
# ─────────────────────────────────────────────────────────────────────────

VC_ROOT = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat"
VC_DEF_FILE = VC_ROOT + "/ChatTranscriptRebuild.kt"
VC_FILES = {
    "transcript": VC_ROOT + "/ChatTranscriptRebuild.kt",
    "viewmodel": VC_ROOT + "/ChatViewModel.kt",
    "queue": VC_ROOT + "/ChatQueueInterruption.kt",
    "bubble": VC_ROOT + "/ChatUserMessageUI.kt",
}

def scan_visible_content(root):
    findings = []
    def_path = os.path.join(root, VC_DEF_FILE)
    code_cache = {}

    def load(rel):
        rel = VC_FILES.get(rel, rel)
        if rel not in code_cache:
            p = os.path.join(root, rel)
            if not os.path.exists(p):
                code_cache[rel] = None
            else:
                code_cache[rel] = code_only(open(p, encoding="utf-8", errors="replace").read())
        return code_cache[rel]

    # V1: exactly one definition of each shared predicate (anchor file present)
    if os.path.exists(def_path):
        def_lines = load("transcript").split("\n")
        for pred in ("userRowHasVisibleContent", "userPartsJsonHasVisibleContent"):
            n = len([1 for ln in def_lines if re.search(r"fun\s+" + pred + r"\s*\(", ln)])
            if n != 1:
                findings.append(
                    "ChatTranscriptRebuild.kt:?  [B-05 V1] %s defined %d time(s) — the "
                    "visible-content verdict must have exactly one implementation" % (pred, n)
                )

    # V2: five call sites reference the shared predicate
    t_code = load("transcript")
    if t_code is not None:
        t_lines = t_code.split("\n")
        n_ref = sum(ln.count("userRowHasVisibleContent(") for ln in t_lines)
        n_def = len([1 for ln in t_lines if re.search(r"fun\s+userRowHasVisibleContent\s*\(", ln)])
        if n_ref - n_def < 1:
            findings.append(
                "ChatTranscriptRebuild.kt:?  [B-05 V2] transcript skip guard no longer calls "
                "userRowHasVisibleContent — pure-attachment rows vanish on reload"
            )
    vm_code = load("viewmodel")
    if vm_code is not None:
        i0, i1 = extract_block(vm_code.split("\n"), re.compile(r"fun\s+retryFromMessage\s*\("))
        if i0 < 0:
            findings.append(
                "ChatViewModel.kt:?  [B-05 V2] retryFromMessage body NOT FOUND (fail-closed)"
            )
        else:
            body = "\n".join(vm_code.split("\n")[i0:i1 + 1])
            if "userRowHasVisibleContent(" not in body:
                findings.append(
                    "ChatViewModel.kt:%d  [B-05 V2] retryFromMessage guard no longer calls "
                    "userRowHasVisibleContent — pure-attachment messages can't be retried" % (i0 + 1)
                )
            if "userPartsJsonHasVisibleContent(" not in body:
                findings.append(
                    "ChatViewModel.kt:%d  [B-05 V2] retry DB-walk no longer calls "
                    "userPartsJsonHasVisibleContent — the cutoff drifts from the edit path" % (i0 + 1)
                )
    q_code = load("queue")
    if q_code is not None:
        i0, i1 = extract_block(q_code.split("\n"), re.compile(r"fun\s+ChatViewModel\.truncateBeforeEdit\s*\("))
        if i0 < 0:
            findings.append(
                "ChatQueueInterruption.kt:?  [B-05 V2] truncateBeforeEdit body NOT FOUND (fail-closed)"
            )
        else:
            body = "\n".join(q_code.split("\n")[i0:i1 + 1])
            if "userPartsJsonHasVisibleContent(" not in body:
                findings.append(
                    "ChatQueueInterruption.kt:%d  [B-05 V2] edit DB-walk no longer calls "
                    "userPartsJsonHasVisibleContent — the cutoff drifts from the retry path" % (i0 + 1)
                )
    b_code = load("bubble")
    if b_code is not None:
        lines = b_code.split("\n")
        i0, i1 = extract_block(lines, re.compile(r"fun\s+UserMessageBubble\s*\("))
        if i0 < 0:
            findings.append(
                "ChatUserMessageUI.kt:?  [B-05 V2] UserMessageBubble body NOT FOUND (fail-closed)"
            )
        else:
            body = "\n".join(lines[i0:i1 + 1])
            if "userRowHasVisibleContent(" not in body:
                findings.append(
                    "ChatUserMessageUI.kt:%d  [B-05 V2] bubble row gating no longer calls "
                    "userRowHasVisibleContent — pure-attachment queued messages lose the "
                    "withdraw target" % (i0 + 1)
                )
            # V3: the withdraw button sits outside the caption-only block
            w_idx = next(
                (k for k in range(i0, i1 + 1) if "chat_withdraw_queued" in lines[k]),
                -1,
            )
            if w_idx < 0:
                findings.append(
                    "ChatUserMessageUI.kt:%d  [B-05 V3] queued withdraw button NOT FOUND "
                    "(fail-closed)" % (i0 + 1)
                )
            else:
                # (a) the button's own guard must not re-introduce the caption gate
                cond_idx = -1
                for k in range(w_idx, i0 - 1, -1):
                    if re.search(r"\bif\s*\(", lines[k]):
                        cond_idx = k
                        break
                if cond_idx >= 0:
                    cond = "\n".join(lines[cond_idx:w_idx + 1])
                    if "content.isNotBlank(" in cond:
                        findings.append(
                            "ChatUserMessageUI.kt:%d  [B-05 V3] the queued withdraw button is "
                            "gated by the caption again — a pure-attachment queued message "
                            "loses its withdraw target" % (cond_idx + 1)
                        )
                # (b) it must not be nested inside the caption-only text block
                cap_idx = next(
                    (k for k in range(i0, i1 + 1) if re.search(r"if\s*\(\s*message\.content\.isNotBlank\(\)\s*\)", lines[k])),
                    -1,
                )
                if cap_idx < 0:
                    findings.append(
                        "ChatUserMessageUI.kt:%d  [B-05 V3] caption-only text-bubble guard NOT "
                        "FOUND (fail-closed — an empty caption rect would render again)" % (i0 + 1)
                    )
                else:
                    c0, c1 = extract_block(lines, re.compile(r"if\s*\(\s*message\.content\.isNotBlank\(\)\s*\)"))
                    block = "\n".join(lines[c0:c1 + 1])
                    if "chat_withdraw_queued" in block:
                        findings.append(
                            "ChatUserMessageUI.kt:%d  [B-05 V3] the queued withdraw button is "
                            "nested inside the caption-only block — a pure-attachment queued "
                            "message loses its withdraw target" % (c0 + 1)
                        )
    return findings


# ─────────────────────────────────────────────────────────────────────────
# [R3-t10] V4 / V5 — the cutoff's ARITHMETIC is the delete boundary
#
# `deleteMessagesAfter(sid, cutoff)` deletes `sort_order >= cutoff`, so the two
# walks encode opposite intents and the KDoc says so:
#   * edit  (truncateBeforeEdit)  — replaces the turn ⇒ boundary = row.sortOrder
#     (the edited row itself goes);
#   * retry (retryFromMessage)    — preserves the turn  ⇒ boundary = row.sortOrder + 1.
# V2 checks the row predicate (`shared row predicate`), which is why both gates
# of the pair stayed green when the +1 moved from one walk to the other: the
# predicate is intact, the offset is the part nobody judged. Neither walk is
# judged by spelling either — a lookup walks back from the delete call, and a
# missing/unreadable boundary fails closed.
# ─────────────────────────────────────────────────────────────────────────

CUTOFF_NAME = "cutoffSortOrder"
CUTOFF_SITES = (
    # (file, function, +1 required?, criterion id, human name)
    ("queue", "truncateBeforeEdit", False, "V4", "edit"),
    ("viewmodel", "retryFromMessage", True, "V5", "retry"),
)
SORT_ORDER_RE = re.compile(r"\w+(?:\.\w+)*\.sortOrder")


def scan_cutoff_offsets(root):
    findings = []
    for key, fn, plus_one, cid, label in CUTOFF_SITES:
        rel = VC_FILES[key]
        path = os.path.join(root, rel)
        if not os.path.exists(path):
            continue
        base = os.path.basename(rel)
        try:
            code = _shared_code_view(open(path, encoding="utf-8", errors="replace").read())
        except KotlinLexError as exc:
            findings.append(
                "%s:?  [B-05 %s] KotlinLexError (%s) — the %s cutoff arithmetic is "
                "unchecked (fail-closed)" % (base, cid, exc, label))
            continue
        fb = function_body(code, fn)
        if fb is None:
            # V2 already fails closed on a missing body; do not double-report.
            continue
        b_start, b_end, _kind = fb
        body = code[b_start:b_end]
        assigns = [pos for pos, kind in decl_sites(body, CUTOFF_NAME) if kind == "assign"]
        if not assigns:
            findings.append(
                "%s:%d  [B-05 %s] no `%s` assignment in the %s walk (fail-closed — the "
                "delete boundary cannot be tracked; intent: %s)"
                % (base, code[:b_start].count("\n") + 1, cid, CUTOFF_NAME, label,
                   "sortOrder + 1" if plus_one else "sortOrder"))
            continue
        for pos in assigns:
            eq = body.find("=", pos)
            expr = " ".join(body[eq + 1:expr_end(body, eq + 1)].split())
            line = code[:b_start + pos].count("\n") + 1
            if not path_feasible(body, pos):
                findings.append(
                    "%s:%d  [B-05 %s] the %s cutoff is assigned only on an unreachable "
                    "path (`if (false)` / contradictory guards) — the boundary keeps its "
                    "initial value" % (base, line, cid, label))
                continue
            ok_shape = (re.fullmatch(r"\w+(?:\.\w+)*\.sortOrder\s*\+\s*1", expr) is not None
                        if plus_one
                        else SORT_ORDER_RE.fullmatch(expr) is not None)
            if not ok_shape:
                findings.append(
                    "%s:%d  [B-05 %s] the %s cutoff is `%s = %s` — `deleteMessagesAfter` "
                    "removes `sort_order >= %s`, so the %s walk must use %s"
                    % (base, line, cid, label, CUTOFF_NAME, expr[:70], CUTOFF_NAME, label,
                       "`<row>.sortOrder + 1` (keep the retried turn)" if plus_one
                       else "exactly `<row>.sortOrder` (drop the edited turn)"))
                continue
            if not _cutoff_reaches_delete(body):
                findings.append(
                    "%s:%d  [B-05 %s] the %s cutoff never reaches `deleteMessagesAfter(` "
                    "(fail-closed — the boundary is computed and then not consumed)"
                    % (base, line, cid, label))
    return findings


def _cutoff_reaches_delete(body):
    """True when some deleteMessagesAfter(..) call consumes the cutoff variable.

    One-hop locals are accepted (`val boundary = cutoffSortOrder`), because the
    judgement is "the boundary is consumed", not "the name is spelled".
    """
    names = {CUTOFF_NAME} | _local_aliases(body, CUTOFF_NAME)
    for m in re.finditer(r"\bdeleteMessagesAfter\s*\(", body):
        spans = _shared_call_args(body, m.end() - 1)
        if not spans:
            continue
        for a, b in spans:
            if any(contains_ident(body[a:b], nm) for nm in names):
                return True
    return False


# ─────────────────────────────────────────────────────────────────────────
# self-test: one breaker per criterion, expected literals hand-written from
# the rule table above (never derived from a run).
# ─────────────────────────────────────────────────────────────────────────

def self_test():
    import tempfile
    import shutil
    KOTLIN_PKG = "src/android/app/src/main/java/com/rikkaminis/app"
    results = []

    def run_scan(files):
        tmp = tempfile.mkdtemp(prefix="scguard-")
        try:
            for rel, text in files.items():
                p = os.path.join(tmp, rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(text)
            return (scan_bottom_verdict(tmp) + scan_visible_content(tmp)
                    + scan_cutoff_offsets(tmp))
        finally:
            shutil.rmtree(tmp)

    # Clean fixture: the post-fix shape. S1 branch without the formula,
    # S2 threshold consumed, S3 one def + two calls, V1 one def each,
    # V2 all five call sites, V3 withdraw outside the caption block.
    clean_screen = (
        "package x\n"
        "class LazyListState { val layoutInfo = 0 }\n"
        "internal fun isListAtBottom(a: Int, b: Int, c: Int, t: Int): Boolean {\n"
        "    val distance = c - (a + b)\n"
        "    return distance >= 0 && distance <= t\n"
        "}\n"
        "fun Screen(listState: LazyListState) {\n"
        "    val nearBottomThresholdPx = 32f\n"
        "    val isNearBottom = remember(listState, nearBottomThresholdPx) {\n"
        "        derivedStateOf {\n"
        "            val last = 1\n"
        "            isListAtBottom(last, 2, 3, nearBottomThresholdPx.toInt())\n"
        "        }\n"
        "    }\n"
        "    LaunchedEffect(listState) {\n"
        "        listState.interactionSource.interactions.collect { interaction ->\n"
        "            when (interaction) {\n"
        "                is androidx.compose.foundation.interaction.DragInteraction.Start -> {}\n"
        "                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {\n"
        "                    val info = listState.layoutInfo\n"
        "                    val sentinel = info.visibleItemsInfo.firstOrNull { it.index == info.totalItemsCount - 1 }\n"
        "                    val last = info.visibleItemsInfo.maxByOrNull { it.index }\n"
        "                    val stoppedAtBottom = sentinel != null ||\n"
        "                        (last != null && isListAtBottom(last.offset, last.size, info.viewportEndOffset, nearBottomThresholdPx.toInt()))\n"
        "                }\n"
        "                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {}\n"
        "                else -> {}\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    clean_transcript = (
        "package x\n"
        "internal fun userRowHasVisibleContent(c: String, i: Collection<*>, a: Collection<*>): Boolean = true\n"
        "internal fun userPartsJsonHasVisibleContent(partsJson: String): Boolean = true\n"
        "fun build() {\n"
        "    if (entity.role == \"user\" && !userRowHasVisibleContent(text, imgs, atts)) return\n"
        "}\n"
    )
    clean_vm = (
        "package x\n"
        "fun retryFromMessage(messageId: String) {\n"
        "    if (!userRowHasVisibleContent(content, imgs, atts)) return\n"
        "    val hasText = userPartsJsonHasVisibleContent(entity.partsJson)\n"
        "    var cutoffSortOrder = -1\n"
        "    for (entity in messages) {\n"
        "        if (entity.role == \"user\") {\n"
        "            if (hasText) {\n"
        "                if (entity.id == messageId) {\n"
        "                    cutoffSortOrder = entity.sortOrder + 1\n"
        "                    break\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "    if (cutoffSortOrder >= 0) chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)\n"
        "}\n"
    )
    clean_queue = (
        "package x\n"
        "fun ChatViewModel.truncateBeforeEdit(messageId: String) {\n"
        "    val hasText = userPartsJsonHasVisibleContent(entity.partsJson)\n"
        "    var cutoffSortOrder = -1\n"
        "    for (entity in messages) {\n"
        "        if (entity.role == \"user\") {\n"
        "            if (hasText) {\n"
        "                if (entity.id == messageId) {\n"
        "                    cutoffSortOrder = entity.sortOrder\n"
        "                    break\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "    chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)\n"
        "}\n"
    )
    clean_bubble = (
        "package x\n"
        "fun UserMessageBubble(message: ChatMessage) {\n"
        "    if (userRowHasVisibleContent(message.content, message.imageUris, message.attachmentUris)) {\n"
        "        Row {\n"
        "            if (message.content.isNotBlank()) {\n"
        "                Text(message.content)\n"
        "            }\n"
        "            if (isQueued && onWithdraw != null) {\n"
        "                IconButton(onClick = onWithdraw) {\n"
        "                    Icon(contentDescription = stringResource(R.string.chat_withdraw_queued))\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    clean = {
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt": clean_screen,
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatTranscriptRebuild.kt": clean_transcript,
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt": clean_vm,
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt": clean_queue,
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatUserMessageUI.kt": clean_bubble,
    }
    got = run_scan(clean)
    results.append(("clean fixture exits clean", got == [], got))

    # N1 (S1): the reversed formula back in the DragStop branch — reds S1 only
    # (S2/S3 stay satisfied).
    n1 = dict(clean)
    n1["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"] = clean_screen.replace(
        "                    val info = listState.layoutInfo\n",
        "                    val stoppedIdx = listState.firstVisibleItemIndex\n"
        "                    val stoppedTotal = listState.layoutInfo.totalItemsCount\n"
        "                    val oldVerdict = stoppedIdx >= stoppedTotal - 2\n"
        "                    val info = listState.layoutInfo\n",
    )
    got = run_scan(n1)
    results.append((
        "N1: reversed formula reds S1 only",
        len(got) == 1 and "[B-03 S1]" in got[0],
        got,
    ))

    # N2 (S2): threshold captured but never consumed — reds S2 only.
    n2 = dict(clean)
    n2["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"] = clean_screen.replace(
        "            isListAtBottom(last, 2, 3, nearBottomThresholdPx.toInt())\n",
        "            isListAtBottom(last, 2, 3, 32)\n",
    )
    got = run_scan(n2)
    results.append((
        "N2: captured-but-unconsumed threshold reds S2 only",
        len(got) == 1 and "[B-03 S2]" in got[0],
        got,
    ))

    # N3 (S3): only one consumer routes through the shared verdict — reds S3 only.
    n3 = dict(clean)
    n3["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"] = clean_screen.replace(
        "                        (last != null && isListAtBottom(last.offset, last.size, info.viewportEndOffset, nearBottomThresholdPx.toInt()))\n",
        "                        (last != null && last.offset + last.size <= info.viewportEndOffset)\n",
    )
    got = run_scan(n3)
    results.append((
        "N3: second caller not routed through the shared verdict reds S3 only",
        len(got) == 1 and "[B-03 S3]" in got[0],
        got,
    ))

    # N4 (V3): withdraw button gated by the caption again — reds V3 only.
    n4 = dict(clean)
    n4["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatUserMessageUI.kt"] = clean_bubble.replace(
        "            if (isQueued && onWithdraw != null) {",
        "            if (message.content.isNotBlank() && isQueued && onWithdraw != null) {",
    )
    got = run_scan(n4)
    results.append((
        "N4: withdraw button re-gated by the caption reds V3 only",
        len(got) == 1 and "[B-05 V3]" in got[0],
        got,
    ))

    # N5 (V2): the transcript call site reverts to the single condition —
    # reds that site's V2 criterion only.
    n5 = dict(clean)
    n5["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatTranscriptRebuild.kt"] = clean_transcript.replace(
        "!userRowHasVisibleContent(text, imgs, atts)) return",
        "text.isBlank() && imgs.isEmpty()) return",
    )
    got = run_scan(n5)
    results.append((
        "N5: transcript call site reverted reds its V2 criterion only",
        len(got) == 1 and "[B-05 V2]" in got[0] and "transcript" in got[0],
        got,
    ))

    # N6 (V1): a duplicate predicate definition — reds V1 only.
    n6 = dict(clean)
    n6["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatTranscriptRebuild.kt"] = clean_transcript.replace(
        "internal fun userRowHasVisibleContent(c: String, i: Collection<*>, a: Collection<*>): Boolean = true\n",
        "internal fun userRowHasVisibleContent(c: String, i: Collection<*>, a: Collection<*>): Boolean = true\n"
        "internal fun userRowHasVisibleContent(c: String, i: Collection<*>, a: Collection<*>): Boolean = false\n",
    )
    got = run_scan(n6)
    results.append((
        "N6: duplicate predicate definition reds V1 only",
        len(got) == 1 and "[B-05 V1]" in got[0],
        got,
    ))

    def run_effect(files):
        """Scan with the P1 producer scanner (run_scan only covers B-03/B-05)."""
        tmp = tempfile.mkdtemp(prefix="scguard-")
        try:
            for rel, text in files.items():
                p = os.path.join(tmp, rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(text)
            out = []
            for sr in SRC_ROOTS:
                base_root = os.path.join(tmp, sr)
                for base, _dirs, fs in os.walk(base_root):
                    for fn in sorted(fs):
                        if not fn.endswith(".kt"):
                            continue
                        path = os.path.join(base, fn)
                        rel = os.path.relpath(path, base_root).replace(os.sep, "/")
                        for line_no, pname in scan_file(path, rel):
                            out.append("%s:%d %s" % (rel, line_no, pname))
            return out
        finally:
            shutil.rmtree(tmp)

    good = {KOTLIN_PKG + "/ui/Good.kt": (
        "@Composable\n"
        "private fun Good(content: String, isStreaming: Boolean) {\n"
        "    val latestContent by rememberUpdatedState(content)\n"
        "    val d by produceState(initialValue = content, isStreaming) {\n"
        "        snapshotFlow { latestContent }.collect { value = it }\n"
        "    }\n"
        "}\n")}
    got = run_effect(good)
    results.append(("P1 control: wrapped value read through the wrapper stays green",
                    got == [], got))

    # P1 witness (dataflow): the wrapper exists, the closure reads the RAW param
    # anyway — the 2026-09-20 freeze, reproduced verbatim.
    stale = {KOTLIN_PKG + "/ui/Stale.kt": (
        "@Composable\n"
        "private fun Stale(content: String, isStreaming: Boolean) {\n"
        "    val latestContent by rememberUpdatedState(content)\n"
        "    val d by produceState(initialValue = content, isStreaming) {\n"
        "        snapshotFlow { content }.collect { value = it }\n"
        "    }\n"
        "}\n")}
    got = run_effect(stale)
    results.append(("P1 witness: wrapper present + raw param read in the body reds",
                    len(got) == 1 and got[0].endswith(" content"), got))

    # P1 witness (parser-death): a `"/*"` string literal above the producer used
    # to open a comment that swallowed the whole body.
    masked = {KOTLIN_PKG + "/ui/Masked.kt": (
        "@Composable\n"
        "private fun Masked(content: String, isStreaming: Boolean) {\n"
        "    val openMask = \"/*\"\n"
        "    val d by produceState(initialValue = content, isStreaming) {\n"
        "        snapshotFlow { content }.collect { value = it }\n"
        "    }\n"
        "    val closeMask = \"*/\"\n"
        "}\n")}
    got = run_effect(masked)
    results.append(("P1 witness: a \"/*\" literal no longer blanks the producer body",
                    len(got) == 1 and got[0].endswith(" content"), got))

    # S2 witness (dataflow): a dead mention keeps the old hit-count happy while
    # the verdict is fed a hard-coded constant.
    n7 = dict(clean)
    n7["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"] = clean_screen.replace(
        "            isListAtBottom(last, 2, 3, nearBottomThresholdPx.toInt())\n",
        "            if (false) nearBottomThresholdPx.toInt()\n"
        "            isListAtBottom(last, 2, 3, 0)\n",
    )
    got = run_scan(n7)
    results.append((
        "N7: dead threshold mention + constant argument reds S2 only",
        len(got) == 1 and "[B-03 S2]" in got[0],
        got,
    ))

    # ── [R3-t10] round-3 seam witnesses ────────────────────────────────────
    # N8 (V4): the edit walk offsets by +1 — the pre-edit row survives
    # `deleteMessagesAfter(sortOrder >= ...)`. V2 stays green: the row
    # predicate is untouched, only the arithmetic moved.
    n8 = dict(clean)
    n8["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt"] = clean_queue.replace(
        "                    cutoffSortOrder = entity.sortOrder\n",
        "                    cutoffSortOrder = entity.sortOrder + 1\n",
    )
    got = run_scan(n8)
    results.append((
        "N8: edit cutoff offset by +1 reds V4 only",
        len(got) == 1 and "[B-05 V4]" in got[0],
        got,
    ))

    # N9 (V5): the retry walk drops its +1 — the user's own turn is deleted.
    n9 = dict(clean)
    n9["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"] = clean_vm.replace(
        "                    cutoffSortOrder = entity.sortOrder + 1\n",
        "                    cutoffSortOrder = entity.sortOrder\n",
    )
    got = run_scan(n9)
    results.append((
        "N9: retry cutoff without +1 reds V5 only",
        len(got) == 1 and "[B-05 V5]" in got[0],
        got,
    ))

    # N10 (S4): the verdict's own body stops reading the threshold — the call
    # argument is still the knob (S2 green), the value is a hardcoded 0.
    n10 = dict(clean)
    n10["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"] = clean_screen.replace(
        "    val distance = c - (a + b)\n"
        "    return distance >= 0 && distance <= t\n",
        "    return c >= 0 && c <= 0\n",
    )
    got = run_scan(n10)
    results.append((
        "N10: verdict body that ignores the threshold reds S4 only",
        len(got) == 1 and "[B-03 S4]" in got[0],
        got,
    ))

    # N11 (S4, order axis): the body names the threshold but absorbs it.
    n11 = dict(clean)
    n11["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"] = clean_screen.replace(
        "    return distance >= 0 && distance <= t\n",
        "    return distance >= 0 && distance <= t || true\n",
    )
    got = run_scan(n11)
    results.append((
        "N11: absorbed threshold (`|| true`) reds S4 only",
        len(got) == 1 and "[B-03 S4]" in got[0] and "constant" in got[0],
        got,
    ))

    # N12 (V5, path axis): the cutoff is stamped on a dead branch only.
    n12 = dict(clean)
    n12["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"] = clean_vm.replace(
        "                    cutoffSortOrder = entity.sortOrder + 1\n",
        "                    if (false) cutoffSortOrder = entity.sortOrder + 1\n")
    got = run_scan(n12)
    results.append((
        "N12: cutoff stamped on an unreachable branch reds V5 only",
        len(got) == 1 and "[B-05 V5]" in got[0] and "unreachable" in got[0],
        got,
    ))

    # Tolerant arms: the same walks/dots rewritten legitimately stay green, so
    # the new criteria cannot pass by forbidding every rewrite.
    tolerant = []
    t1 = dict(clean)
    t1["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"] = clean_vm.replace(
        "entity.sortOrder + 1", "row.sortOrder + 1").replace("for (entity in messages)", "for (row in messages)")
    tolerant.append(("renamed loop variable in the retry walk", run_scan(t1)))
    t2 = dict(clean)
    t2["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"] = clean_screen.replace(
        "    return distance >= 0 && distance <= t\n",
        "    val limit = t\n    return distance >= 0 && distance <= limit\n",
    )
    tolerant.append(("threshold read through a local in the verdict body", run_scan(t2)))
    t3 = dict(clean)
    t3["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt"] = clean_queue.replace(
        "    chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)\n",
        "    val boundary = cutoffSortOrder\n"
        "    chatRepository.deleteMessagesAfter(sid, boundary)\n")
    got = run_scan(t3)
    tolerant.append(("boundary handed to the delete call through a local", got))
    for name, got in tolerant:
        results.append(("T: " + name + " stays green", got == [], got))

    failed = [name for name, ok, got in results if not ok]
    if failed:
        print("SELF-TEST FAILED (%d breaker(s))" % len(failed))
        for name, ok, got in results:
            if not ok:
                print("  ✗ " + name)
                for f in got:
                    print("      " + f)
        return 1
    print(
        "✅ self-test has teeth: bottom-verdict S1/S2/S3/S4 and visible-content "
        "V1/V2/V3/V4/V5 each flip red on exactly their own breaker; clean + "
        "tolerant fixtures green"
    )
    print("STALE_CLOSURE_SELFTEST=PASS (%d arms)" % len(results))
    return 0

def main():
    args = sys.argv[1:]
    if args and args[0] in ("--selftest", "--self-test"):
        return self_test()
    if args and args[0] == "--root" and len(args) > 1:
        args = args[1:]
    if args and args[0].startswith("--"):
        print("usage: stale_closure_guard.py [ROOT | --root ROOT] | --selftest")
        return 2
    root = os.path.abspath(args[0] if args else ".")
    violations = []
    for sr in SRC_ROOTS:
        for base, _dirs, files in os.walk(os.path.join(root, sr)):
            for fn in sorted(files):
                if not fn.endswith(".kt"):
                    continue
                path = os.path.join(base, fn)
                rel = os.path.relpath(path, os.path.join(root, sr)).replace(os.sep, "/")
                if rel in ALLOWED:
                    continue
                for line_no, pname in scan_file(path, rel):
                    violations.append(f"{rel}:{line_no}  reads plain param `{pname}`")

    # [B-03 deep-audit-1037] bottom-verdict section + [B-05] visible-content
    violations += scan_bottom_verdict(root)
    violations += scan_visible_content(root)
    violations += scan_cutoff_offsets(root)

    if violations:
        print(f"❌ Stale-closure guard: {len(violations)} violation(s)")
        print("   A long-lived effect reads a plain parameter / a bottom verdict /")
        print("   a visible-content contract drifted from its single source.")
        print("   Fix: `val latestX by rememberUpdatedState(x)` and read `latestX`,")
        print("   or restore the shared helper the section names.")
        for v in violations:
            print("     " + v)
        return 1
    print("✅ Stale-closure guard: no long-lived effect reads a plain parameter")
    print("✅ Stale-closure guard: bottom verdict + visible-content sections green")
    print("✅ Stale-closure guard: edit/retry cutoff arithmetic green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
