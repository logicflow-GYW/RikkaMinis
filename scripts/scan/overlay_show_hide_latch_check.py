#!/usr/bin/env python3
"""deep-audit-1046 S-06 (P1) — floating-overlay show/hide intent latch gate.

Pins the fix from branch fix/dv26-c6-overlay-show-hide-latch.

The defect: `ToolOverlayController.show()` publishes the capsule from a
POSTED Runnable, so `isShown` — flipped only once that Runnable actually
reaches `attach()` — is false for at least one main-thread queue hop. All
three hide sites used to be gated on it (`if (isShown) hide()`), so any
state flip landing inside that hop ("app came to the foreground" / "camera
suppress" / "toggle off" / "task finished") was silently dropped, and the
still-queued attach then painted the capsule over the chat or the camera
preview with no later state frame to clear it.

The fix is an INTENT LATCH that is written synchronously at both entry
points and re-read inside the posted show block. This gate pins four
independent shapes, all DERIVED (never anchored to a member name):

  C1  `show()` entry assigns <intent> = true BEFORE queueing the post, and
      `hide()` entry assigns <intent> = false BEFORE queueing the post.
      (the intent field itself is derived: the Boolean field assigned true
      in show() and false in hide())
  C2  the Runnable show() posts re-checks that intent field and early-returns
      BEFORE it can call attach() — the "guard moved after attach" shape is
      red because by then the view is already on screen.
  C3  no hide() call site is gated on the REAL attach-state field (derived:
      the Boolean field whose `= true` assignment lives inside attach()),
      because that field lags the intent by a queue hop.
  C4  the intent field and the real attach-state field are two DISTINCT
      fields — collapsing them back into one is the original bug.

Deliberately NOT pinned (so a rename or a re-ordering of unrelated members
does not turn this red): member names, line numbers, the shape of the guard
call (`shouldRunShowPost(desired)` today), the `lingerJob` / completion
linger semantics in AgentForegroundService.

[BATCH7-t10 hardening, 2026-10-26] Six witness patches re-checked: every one
kept the ANALYSED SPELLING while destroying the EFFECT, and every one was
GREEN here before this branch. The judgements are now anchored on values and
reachability instead of on substrings:

  14-1 `desiredShown = 1 == 1` (and `this.desiredShown = true`) re-latches the
       intent — the write is constant-FOLDED (`const_bool`), not matched
       against the literals `true`/`false`, so any spelling of "true" is seen.
  14-2 `&& 1 == 2` decides the posted guard — every operand is folded, not
       only the bare `true`/`false`/`!true` spellings.
  14-3 `shouldRunShowPost(desiredShown.let { true })` — the guard's ARGUMENT
       no longer evaluates to the latch: a value wrapper whose tail ignores
       its receiver is red.
  14-4 `if (…) { Unit }` — the tail must actually ABORT on a reachable path
       (`return`/`throw`/`return@label`), a block named `{ … }` is not enough.
  14-5 `val shouldHide = controller.isShown; if (shouldHide) hide()` — gate
       detection resolves local aliases (two hops) instead of only looking for
       the field name in the condition text.
  14-6 `controller.takeIf { it.isShown }?.let {\n it.hide()\n}` — a guard may
       be the RECEIVER of the block the hide sits in (any enclosing block's
       header, any line), not only `if (…)` / same-line prefixes.

All lexing/brace work goes through the shared primitives (_kotlin_lexer /
_kotlin_ast / _kotlin_valueflow); the hand-written stripper and brace counter
that used to live here are gone.

Usage: python3 overlay_show_hide_latch_check.py [--root ROOT] [ROOT]
       python3 overlay_show_hide_latch_check.py --selftest
Exit:  0 = gate green, 1 = violations / selftest fail, 2 = usage error.
"""
import os
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool, function_body, if_blocks, match_bracket, split_top, strip_parens,
    tail_expr,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args as vf_call_args, contains_ident, expr_end, path_feasible,
    value_sources,
)

CONTROLLER = "src/android/app/src/main/java/com/rikkaminis/app/service/ToolOverlayController.kt"
SERVICE = "src/android/app/src/main/java/com/rikkaminis/app/service/AgentForegroundService.kt"

SELFTEST_MARKER = "OVERLAY_SHOW_HIDE_LATCH_SELFTEST=PASS"

CRIT_C1 = "C1 show()/hide() publish the intent latch synchronously, before queueing the post"
CRIT_C2 = "C2 the posted show Runnable re-checks the intent latch before attach()"
CRIT_C3 = "C3 no hide() call site is gated on the lagging real attach-state field"
CRIT_C4 = "C4 intent latch and real attach-state are two distinct fields"

IDENT_RE = re.compile(r"[A-Za-z_]\w*")


def line_of(text, pos):
    return text.count("\n", 0, pos) + 1


# ── shared-primitive structure helpers ─────────────────────────────────────

def class_body(text, name):
    """Inner text of `class <name> … { … }` (shared match_bracket)."""
    m = re.search(r"\bclass\s+" + re.escape(name) + r"\b", text)
    if not m:
        return None, 0
    brace = text.find("{", m.end())
    if brace < 0:
        return None, 0
    close = match_bracket(text, brace)
    if close < 0:
        return None, 0
    return text[brace + 1:close], brace + 1


def fun_body(text, name):
    """Inner text of `fun <name>(…) { … }` (or its `= expr` tail)."""
    fb = function_body(text, name)
    if fb is None:
        return None
    return text[fb[0]:fb[1]]


def brace_blocks(text):
    """Every brace block as (open, close), located by the shared matcher."""
    out = []
    for m in re.finditer(r"\{", text):
        close = match_bracket(text, m.start())
        if close > 0:
            out.append((m.start(), close))
    return out


def assignments(text, name):
    """[(pos, rhs, folded)] for every `[this.]<name> = <expr>` write.

    [14-1] the VALUE is constant-folded — `desiredShown = 1 == 1` is a write of
    `true`, which the old literal regex could not see."""
    out = []
    pat = re.compile(r"(?<![\w.])(?:this\s*\.\s*)?"
                     + re.escape(name) + r"\s*=(?!=)")
    for m in pat.finditer(text):
        e = expr_end(text, m.end())
        rhs = text[m.end():e].strip()
        out.append((m.start(), rhs, const_bool(rhs)))
    return out


def bool_fields(class_text):
    return re.findall(r"\bvar\s+(\w+)\s*:\s*Boolean\b", class_text)


def constant_decides(cond):
    """True when a constant operand decides [cond]'s value.

    [14-2] `x && 1 == 2` is as dead as `x && false`; every operand is folded,
    not just the bare-literal spellings."""
    e = strip_parens(cond.strip())
    if not e:
        return False
    for op in ("||", "&&"):
        parts = split_top(e, (op,))
        if len(parts) > 1:
            vals = []
            for a, b in parts:
                sub = e[a:b]
                if constant_decides(sub):
                    return True
                vals.append(const_bool(sub))
            return (any(v is True for v in vals) if op == "||"
                    else any(v is False for v in vals))
    return const_bool(e) is not None


def scope_tail_carries_receiver(block_text):
    """Does a `{ [param ->] body }` lambda produce a value derived from its
    receiver? A tail naming neither the param nor `it` does not."""
    inner = block_text[1:-1] if block_text.startswith("{") else block_text
    m = re.match(r"\s*([A-Za-z_]\w*)\s*->", inner)
    param, body = (m.group(1), inner[m.end():]) if m else (None, inner)
    tail = tail_expr(body) or body.strip()
    if not tail:
        return False
    if param:
        return param != "_" and contains_ident(tail, param)
    return contains_ident(tail, "it")


def wrapper_ignores_receiver(cond, intent):
    """[14-3] `intent.let { true }` still mentions the latch and still feeds the
    guard — but the value the guard receives is a constant."""
    for m in re.finditer(r"\.\s*(let|run)\s*\{", cond):
        close = match_bracket(cond, m.end() - 1)
        if close < 0:
            continue
        receiver = cond[max(0, m.start() - 80):m.start()]
        if not contains_ident(receiver, intent):
            continue
        if not scope_tail_carries_receiver(cond[m.end() - 1:close + 1]):
            return True
    return False


def guard_arg_constant(cond, intent):
    """[14-3, paren form] the guard call's argument folds to a constant."""
    for m in re.finditer(r"([A-Za-z_]\w*)\s*\(", cond):
        p = m.end() - 1
        for a, b in vf_call_args(cond, p):
            arg = cond[a:b]
            if not contains_ident(arg, intent):
                continue
            srcs = value_sources(arg)
            if srcs and all(const_bool(s) is not None for s in srcs):
                return True
    return False


def tail_aborts(then_text):
    """[14-4] the guard's tail must ABORT on a reachable path: a block that
    merely exists (`{ Unit }`) cannot cancel the queued attach."""
    body = then_text.strip()
    if body.startswith("{"):
        body = body[1:]
    if body.endswith("}"):
        body = body[:-1]
    for m in re.finditer(r"\b(return|throw)\b", body):
        if path_feasible(body, m.start()):
            return True
    return False


GUARD_CALL_RE = re.compile(r"\.\s*(takeIf|takeUnless|filter|filterNot)\s*[({]")


def expand_aliases(text, cond, pos, hops=2):
    """[14-5] `val shouldHide = controller.isShown; if (shouldHide) hide()`
    gates on the real field through one local hop."""
    cur = cond
    seen = set()
    for _ in range(hops):
        grew = False
        for ident in [i for i in IDENT_RE.findall(cur) if i not in seen]:
            seen.add(ident)
            for m in re.finditer(r"\b(?:val|var)\s+" + re.escape(ident) + r"\s*=\s*", text):
                if m.start() > pos:
                    continue
                e = expr_end(text, m.end())
                rhs = text[m.end():e].strip()
                if rhs and len(rhs) < 200:
                    cur = cur + " || " + rhs
                    grew = True
        if not grew:
            break
    return cur


def hide_gates(text, blocks):
    """(line, governing conditions) for every hide() CALL site."""
    out = []
    for m in re.finditer(r"\bhide\s*\(\s*\)", text):
        pre = text[max(0, m.start() - 30):m.start()]
        if re.search(r"\bfun\s*$", pre):
            continue  # the declaration itself
        pos = m.start()
        conds = []
        for cond, t0, t1, e0, e1 in if_blocks(text):
            if t0 <= pos < t1:
                conds.append(cond)
            elif e0 is not None and e0 <= pos < e1:
                conds.append("! (" + cond + ")")
        # [14-6] a guard may be the RECEIVER of the block the hide sits in.
        enclosing = [b for b in blocks if b[0] < pos < b[1]]
        for gm in GUARD_CALL_RE.finditer(text):
            open_at = gm.end() - 1
            close = match_bracket(text, open_at)
            if close < 0:
                continue
            body = text[open_at + 1:close]
            if open_at < pos < close:
                conds.append(body)
                continue
            for b_open, _b_close in enclosing:
                if b_open - 220 <= gm.start() < b_open:
                    conds.append(body)
                    break
        conds = [expand_aliases(text, c, pos) for c in conds]
        out.append((line_of(text, pos), conds))
    return out


# --------------------------------------------------------------------------
# analysis
# --------------------------------------------------------------------------
def analyse(ctrl, svc):
    """ctrl / svc = code views. Returns (criterion, ok, detail)."""
    out = []

    cls, _cls_at = class_body(ctrl, "ToolOverlayController")
    if cls is None:
        return [(CRIT_C1, False, "class ToolOverlayController not found"),
                (CRIT_C2, False, "class ToolOverlayController not found"),
                (CRIT_C3, False, "class ToolOverlayController not found"),
                (CRIT_C4, False, "class ToolOverlayController not found")]
    fields = bool_fields(cls)
    show_f = fun_body(cls, "show")
    hide_f = fun_body(cls, "hide")
    attach_f = fun_body(cls, "attach")

    # ---- derive the two roles -------------------------------------------
    intent = None
    intent_show_at = intent_hide_at = -1
    if show_f is not None and hide_f is not None:
        for f in fields:
            s = [a for a in assignments(show_f, f) if a[2] is True]
            h = [a for a in assignments(hide_f, f) if a[2] is False]
            if s and h:
                intent, intent_show_at, intent_hide_at = f, s[0][0], h[0][0]
                break

    real = None
    if attach_f is not None:
        for f in fields:
            if any(a[2] is True for a in assignments(attach_f, f)):
                real = f
                break

    # ---- C1: intent written at BOTH entries, before the post -----------
    if show_f is None or hide_f is None:
        out.append((CRIT_C1, False, "ToolOverlayController.show()/hide() not found"))
    elif intent is None:
        out.append((
            CRIT_C1, False,
            "no Boolean field is assigned true in show() and false in hide() "
            "— the intent is not being published",
        ))
    else:
        bad = []
        show_writes = assignments(show_f, intent)
        hide_writes = assignments(hide_f, intent)
        for label, body, writes in (("show()", show_f, show_writes),
                                    ("hide()", hide_f, hide_writes)):
            post = re.search(r"\.post\s*[({]", body)
            first = min(a[0] for a in writes) if writes else -1
            if post and first > post.start():
                bad.append("%s writes %s only AFTER queueing the post" % (label, intent))
        # [14-1] the entry must PUBLISH the intent and keep it: a second write
        # of the opposite value silently undoes the publication while every
        # position/substring criterion stays satisfied. The VALUE is folded, so
        # `= 1 == 1` / `this.x = true` are the same defect as `= true`.
        for pos, rhs, folded in show_writes:
            if folded is False:
                bad.append("show() re-latches %s = false (`%s`) — the intent is dropped "
                           "again before the post runs" % (intent, rhs[:40]))
            elif folded is None:
                bad.append("show() writes %s = `%s`, which cannot be proven to be the "
                           "published `true` (fail-closed)" % (intent, rhs[:40]))
        for pos, rhs, folded in hide_writes:
            if folded is True:
                bad.append("hide() re-latches %s = true (`%s`) — the hide intent is "
                           "overwritten and a queued attach survives" % (intent, rhs[:40]))
            elif folded is None:
                bad.append("hide() writes %s = `%s`, which cannot be proven to be the "
                           "published `false` (fail-closed)" % (intent, rhs[:40]))
        if bad:
            out.append((CRIT_C1, False, "; ".join(bad)))
        else:
            out.append((CRIT_C1, True, "intent field = %s" % intent))

    # ---- C2: posted show Runnable re-checks it before attach() ---------
    if show_f is None:
        out.append((CRIT_C2, False, "show() not found"))
    elif intent is None:
        out.append((CRIT_C2, False, "no intent field derived (see C1)"))
    else:
        body = show_f
        post = re.search(r"\.post\s*\{", body)
        if not post:
            out.append((CRIT_C2, False, "show() posts no Runnable (shape changed)"))
        else:
            open_at = post.end() - 1
            close = match_bracket(body, open_at)
            runnable = body[open_at + 1:close] if close > 0 else ""
            guard = None
            for cond, t0, t1, _e0, _e1 in if_blocks(runnable):
                if not contains_ident(cond, intent) and not wrapper_ignores_receiver(
                        cond, intent) and not guard_arg_constant(cond, intent):
                    continue
                if tail_aborts(runnable[t0:t1]):
                    guard = (cond, t0, t1)
                    break
                guard = guard or (cond, t0, t1)
            attach_at = runnable.find("attach(")
            if guard is None:
                out.append((
                    CRIT_C2, False,
                    "the posted show Runnable never checks %s — a hide inside "
                    "the queue hop cannot cancel the attach" % intent,
                ))
            else:
                cond, guard_at, guard_end = guard
                if constant_decides(cond):
                    out.append((
                        CRIT_C2, False,
                        "the posted show Runnable's %s guard is decided by a constant "
                        "(`%s`) — the early return can never fire, so a hide inside the "
                        "queue hop cannot cancel the attach" % (intent, cond.strip()[:70]),
                    ))
                elif wrapper_ignores_receiver(cond, intent) or guard_arg_constant(cond, intent):
                    out.append((
                        CRIT_C2, False,
                        "the posted show Runnable's guard receives `%s`, which no longer "
                        "evaluates to %s — the latch is read and then replaced by a "
                        "constant, so the early return cannot fire" % (cond.strip()[:70], intent),
                    ))
                elif not tail_aborts(runnable[guard_at:guard_end]):
                    out.append((
                        CRIT_C2, False,
                        "the posted show Runnable's %s guard does not abort (`%s` returns "
                        "Unit) — the queued attach still runs" % (intent, cond.strip()[:70]),
                    ))
                elif attach_at >= 0 and guard_at < close and attach_at < guard_at:
                    out.append((
                        CRIT_C2, False,
                        "the %s guard sits AFTER attach() in the posted Runnable "
                        "(the view is already on screen)" % intent,
                    ))
                else:
                    out.append((CRIT_C2, True, "guard on %s precedes attach()" % intent))

    # ---- C3: no hide() call site gated on the real attach-state --------
    viol = []
    if real is None:
        viol.append("could not derive the real attach-state field (no `= true` inside attach())")
    else:
        for path, src in ((CONTROLLER, ctrl), (SERVICE, svc)):
            for line_no, conds in hide_gates(src, brace_blocks(src)):
                hits = [c for c in conds if contains_ident(c, real)]
                if hits:
                    viol.append(
                        "%s:%d hide() gated on `%s` — `%s` lags the intent by a queue "
                        "hop, so this drops the hide"
                        % (path.rsplit("/", 1)[-1], line_no, hits[0].strip()[:70], real))
        if viol:
            out.append((CRIT_C3, False, "; ".join(viol)))
        else:
            out.append((CRIT_C3, True, "no hide() gated on %s" % real))

    # ---- C4: intent and real state must be distinct --------------------
    if intent is None or real is None:
        out.append((
            CRIT_C4, False,
            "both fields must exist and differ (intent=%r, real=%r)" % (intent, real),
        ))
    elif intent == real:
        out.append((
            CRIT_C4, False,
            "`%s` doubles as the intent latch — an async flag cannot also be "
            "the synchronous intent (this is the original defect)" % real,
        ))
    else:
        out.append((CRIT_C4, True, "intent=%s / real=%s" % (intent, real)))

    return out


def scan(root):
    """Returns (violations, summary_lines)."""
    ctrl_p, svc_p = root / CONTROLLER, root / SERVICE
    missing = [str(p) for p in (ctrl_p, svc_p) if not p.is_file()]
    if missing:
        return ["missing source file(s): %s" % ", ".join(missing)], []
    sources = {}
    for p in (ctrl_p, svc_p):
        try:
            sources[p] = mask_code(p.read_text(encoding="utf-8"))
        except KotlinLexError as e:
            return ["fail-closed: the shared lexer cannot analyse %s (%s)" % (p.name, e)], []
    results = analyse(sources[ctrl_p], sources[svc_p])
    violations = []
    summary = []
    for crit, ok, detail in results:
        if ok:
            summary.append("  OK   %s [%s]" % (crit, detail))
        else:
            violations.append("%s: %s" % (crit, detail))
    return violations, summary


# --------------------------------------------------------------------------
# selftest — every arm is a byte-level mutation of the REAL tree
# --------------------------------------------------------------------------
def _mutate(src, old, new, tag):
    if src.count(old) != 1:
        raise SystemExit(
            "[selftest %s] anchor must appear exactly once, found %d: %r — the gate's "
            "arms drifted from production; fix the gate" % (tag, src.count(old), old[:70])
        )
    return src.replace(old, new)


SHOW_POST_TAIL = """        mainHandler.post {
            if (!shouldRunShowPost(desiredShown)) return@post
            try {
"""
SHOW_LATCH = "        desiredShown = true\n" + SHOW_POST_TAIL
HIDE_HEAD = "        desiredShown = false\n"
SVC_GATE1 = "            controller.hide()\n            return\n"
SVC_IDLE = "        controller.hide()\n    }\n"
CTRL_NOPERM = "            hide()\n            return\n"
GUARD_LINE = "            if (!shouldRunShowPost(desiredShown)) return@post\n"


def selftest():
    root = Path(__file__).resolve().parents[2]
    ctrl = (root / CONTROLLER).read_text(encoding="utf-8")
    svc = (root / SERVICE).read_text(encoding="utf-8")
    tmp = Path(tempfile.mkdtemp(prefix="ovl-latch-"))
    stats = {"arms": 0}

    def arm(name, ctrl_src, svc_src, expect):
        """expect = criteria that must be red; every other criterion must stay
        green (so "exactly one arm, one criterion" is enforced, not narrated)."""
        d = tmp / name
        for rel, src in ((CONTROLLER, ctrl_src), (SERVICE, svc_src)):
            p = d / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(src, encoding="utf-8")
        stats["arms"] += 1
        viol, _ = scan(d)
        reds = sorted({v.split(":")[0].split(" ")[0] for v in viol})
        ok = reds == sorted(expect)
        print("[selftest %s] expect red %s -> got %s: %s"
              % (name, expect, reds, "OK" if ok else "FAIL"))
        for v in viol:
            print("    " + v)
        return ok

    good = True

    # arm 0: the real tree is green
    stats["arms"] += 1
    viol, summary = scan(root)
    if viol:
        print("[selftest ok-arm] EXPECTED green, got: %r" % (viol,))
        good = False
    else:
        print("[selftest ok-arm] real tree parses clean: OK")
    for line in summary:
        print("    " + line.strip())

    # arm 1: intent published only AFTER the post is queued, in show()
    late_show = _mutate(ctrl, SHOW_LATCH, SHOW_POST_TAIL, "c1")
    # re-add the latch as the last statement of show() instead of its entry
    late_show = _mutate(
        late_show,
        '                Log.w(TAG, "show failed: ${e.message}", e)\n'
        "            }\n        }\n    }\n\n    fun hide()",
        '                Log.w(TAG, "show failed: ${e.message}", e)\n'
        "            }\n        }\n        desiredShown = true\n    }\n\n    fun hide()",
        "c1b",
    )
    good &= arm("c1-latch-after-post", late_show, svc, ["C1"])

    # arm 2: hide() clears the latch only inside the posted body
    moved = _mutate(ctrl, HIDE_HEAD, "", "c2").replace(
        "        mainHandler.post {\n            val v = view ?: return@post",
        "        mainHandler.post {\n            desiredShown = false\n            val v = view ?: return@post",
        1,
    )
    good &= arm("c1-hide-late", moved, svc, ["C1"])

    # arm 3: the posted show Runnable drops its intent guard
    good &= arm("c2-no-guard", _mutate(ctrl, SHOW_POST_TAIL,
                                       "        mainHandler.post {\n            try {\n", "c3"),
                svc, ["C2"])

    # arm 4: guard moved behind attach()
    after = _mutate(
        ctrl,
        SHOW_POST_TAIL + "                pendingSessionId = targetSessionId\n"
        "                if (view == null) {\n"
        '                    Log.d(TAG, "show() attach() — view was null")\n'
        "                    attach()\n"
        "                }\n",
        "        mainHandler.post {\n            try {\n"
        "                pendingSessionId = targetSessionId\n"
        "                if (view == null) {\n"
        '                    Log.d(TAG, "show() attach() — view was null")\n'
        "                    attach()\n"
        "                }\n"
        "                if (!shouldRunShowPost(desiredShown)) return@post\n",
        "c4",
    )
    good &= arm("c2-guard-after-attach", after, svc, ["C2"])

    # arm 5/6/7: each of the three call sites re-gated on the real state
    good &= arm("c3-controller-noperm",
                _mutate(ctrl, CTRL_NOPERM,
                        "            if (isShown) hide()\n            return\n", "c5"),
                svc, ["C3"])
    good &= arm("c3-service-gate1", ctrl, _mutate(
        svc, SVC_GATE1,
        "            if (controller.isShown) controller.hide()\n            return\n", "c6"), ["C3"])
    good &= arm("c3-service-idle", ctrl, _mutate(
        svc, SVC_IDLE,
        "        if (controller.isShown) controller.hide()\n    }\n", "c7"), ["C3"])

    # arm 8: collapse the two fields into one (the original defect shape)
    merged = _mutate(ctrl, "    @Volatile\n    private var desiredShown: Boolean = false\n", "", "c8")
    merged = merged.replace("desiredShown", "isShown")
    good &= arm("c4-merged-fields", merged, svc, ["C4"])

    # ── wide-1024 witness arms (each was green before that branch) ──────────
    # arm 10: hide() publishes `false` then immediately re-latches `true` —
    #         position/substring criteria stay satisfied, the intent is gone.
    good &= arm("c1-hide-relatch-true", _mutate(
        ctrl, HIDE_HEAD, "        desiredShown = false\n        desiredShown = true\n", "c9"),
        svc, ["C1"])

    # arm 11: the posted guard mentions the latch and still returns early, but
    #         a literal `&& false` decides it.
    good &= arm("c2-guard-literal-false", _mutate(
        ctrl, GUARD_LINE,
        "            if (!shouldRunShowPost(desiredShown) && false) return@post\n", "c10"),
        svc, ["C2"])

    # arm 12: the canonical Kotlin BLOCK form of the gated hide — the old
    #         line-prefix scan could not see `) {` + a body line.
    good &= arm("c3-service-braced-gate", ctrl, _mutate(
        svc, SVC_GATE1,
        "            if (controller.isShown) {\n"
        "                controller.hide()\n"
        "            }\n            return\n", "c11"), ["C3"])

    # ── [BATCH7-t10] witness arms ───────────────────────────────────────────
    # 14-1: the re-latch is spelled as an expression — the literal regex saw
    #       `false` (published) and nothing else.
    good &= arm("c1-hide-relatch-expr", _mutate(
        ctrl, HIDE_HEAD, "        desiredShown = false\n        desiredShown = 1 == 1\n", "t1"),
        svc, ["C1"])

    # 14-1b: `this.` prefix on the re-latch.
    good &= arm("c1-hide-relatch-this", _mutate(
        ctrl, HIDE_HEAD, "        desiredShown = false\n        this.desiredShown = true\n", "t1b"),
        svc, ["C1"])

    # 14-2: a folded comparison decides the guard.
    good &= arm("c2-guard-literal-expr", _mutate(
        ctrl, GUARD_LINE,
        "            if (!shouldRunShowPost(desiredShown) && 1 == 2) return@post\n", "t2"),
        svc, ["C2"])

    # 14-3: the guard receives a constant instead of the latch.
    good &= arm("c2-guard-arg-const", _mutate(
        ctrl, GUARD_LINE,
        "            if (!shouldRunShowPost(desiredShown.let { true })) return@post\n", "t3"),
        svc, ["C2"])

    # 14-4: the guard's tail exists but does not abort.
    good &= arm("c2-guard-body-noop", _mutate(
        ctrl, GUARD_LINE,
        "            if (!shouldRunShowPost(desiredShown)) { Unit }\n", "t4"),
        svc, ["C2"])

    # 14-5: the gate is one local alias away from the real field.
    good &= arm("c3-service-alias", ctrl, _mutate(
        svc, SVC_GATE1,
        "            val shouldHide = controller.isShown\n"
        "            if (shouldHide) controller.hide()\n            return\n", "t5"), ["C3"])

    # 14-6: the gate is the RECEIVER of the block the hide sits in, across a
    #       line break — no `if (…)` and no same-line prefix.
    good &= arm("c3-service-receiver-gate", ctrl, _mutate(
        svc, SVC_GATE1,
        "            controller.takeIf { it.isShown }?.let {\n"
        "                it.hide()\n"
        "            }\n            return\n", "t6"), ["C3"])

    # arm 9: missing file must be red, never silently green
    stats["arms"] += 1
    d = tmp / "missing"
    p = d / CONTROLLER
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(ctrl, encoding="utf-8")
    viol, _ = scan(d)
    ok = bool(viol) and "missing" in viol[0]
    print("[selftest missing-file] expect missing-file violation -> %s" % ("OK" if ok else "FAIL"))
    good &= ok

    # fail-closed on a lexer failure
    stats["arms"] += 1
    d = tmp / "lexfail"
    for rel, src in ((CONTROLLER, 'class X { val s = "unterminated\n'), (SERVICE, svc)):
        p = d / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(src, encoding="utf-8")
    viol, _ = scan(d)
    ok = bool(viol) and "fail-closed" in viol[0]
    print("[selftest lexer-fail-closed] expect fail-closed violation -> %s"
          % ("OK" if ok else "FAIL"))
    good &= ok

    if good:
        print(SELFTEST_MARKER)
        print("OVERLAY_SHOW_HIDE_LATCH_SELFTEST_ARMS=%d" % stats["arms"])
        return 0
    print("OVERLAY_SHOW_HIDE_LATCH_SELFTEST=FAIL")
    return 1


def main(argv):
    root = None
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--selftest":
            return selftest()
        if a == "--root":
            i += 1
            if i >= len(argv):
                print("usage: --root needs a value", file=sys.stderr)
                return 2
            root = Path(argv[i])
        elif not a.startswith("--"):
            root = Path(a)
        i += 1
    if root is None:
        root = Path.cwd()
    if not root.is_dir():
        print("repo root not found: %s" % root, file=sys.stderr)
        return 2

    violations, _ = scan(root)
    if violations:
        print("[overlay-show-hide-latch] VIOLATIONS FOUND:")
        for v in violations:
            print("  FAIL: %s" % v)
        print(
            "Fix: publish the overlay INTENT at the show()/hide() entries (before the "
            "post), re-check it inside the posted show Runnable before attach(), keep "
            "hide() ungated (it is idempotent), and never let one field be both the "
            "synchronous intent and the asynchronous attach state."
        )
        return 1
    print(
        "[overlay-show-hide-latch] OK: 4 criteria clean — show/hide publish an intent "
        "latch synchronously, the posted attach re-checks it, all hide() sites are "
        "ungated, and intent != attach state"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
