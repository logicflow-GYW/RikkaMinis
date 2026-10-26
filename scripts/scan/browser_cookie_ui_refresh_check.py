#!/usr/bin/env python3
"""[fix/da1022-G] browser_cookie_ui_refresh_check

Issue-07 gate: BrowserSettingsSheet cookie section must
  1. never call the callback-less `removeAllCookies(null)` (no flush, no refresh);
  2. use the callback form `removeAllCookies { ... }` with a `flush()` inside;
  3. cover BOTH http and https variants in the per-domain delete;
  4. carry a refresh signal (cookieRefreshTick) wired into remember keys and
     bumped at the per-domain and clear-all actions.

[deep-audit-1039 B-07] Family 5 (B1–B2) — the domain list's refresh signal must
be the STORE's, not the sheet's. The cookie domains are HISTORY domains
(`historyStore.uniqueDomains()`), so the list changes whenever the history
changes — including changes this panel causes indirectly: switching UA (or
Viewport) here reloads the active tab, `BrowserUseManager.onPageFinished`
records the visit and `historyStore.revision` advances. Keying the list on the
panel-local `cookieRefreshTick` alone left it on the pre-reload snapshot until
the sheet was closed and reopened (the [da1022-G] fix wired the tick for
cookie deletes, which is a different mutation surface). Same shape and same fix
as `BrowserHistorySheet` ([dv15-B3]): subscribe to `historyStore.revision` and
feed it into the `allDomains` remember keys. The criterion binds the DECISION
POINT (the memoized domain read is invalidated by the store's counter), not one
spelling of the key list.

  B1  the sheet subscribes to `historyStore.revision` via collectAsState.
  B2  the `allDomains` remember key list includes a value backed by that flow
      (derived-window check, same technique as V2/V6 — a key that is not fed
      by the store's flow satisfies "has a key" while never reflecting a
      record()).

[da1029-E] The same sheet also guards the viewport / session-override pair, so
the second criteria family below was added here rather than as a new gate (a new
gate would have to be registered in scan.sh, which is frozen during this batch;
the name stays `cookie_ui_refresh` because renaming it would break the
registration — this docstring is the compensating note). The invariant is that
the panel shows the viewport the pool actually resolves:
  V1  the sheet subscribes to BOTH viewport sources — the global pair
      (`customViewportWidth/Height`) and the session override
      (`sessionViewportWidth/Height`, what an agent `set_viewport` writes). The
      pre-fix sheet subscribed to the global pair only, so a session override
      rendered as "Using UA default <session size>" with empty fields and the
      Default radio selected.
  V2  the width/height text states must be keyed on a value DERIVED from the
      session source (`resolvedViewportSize()`'s priority is session > global >
      UA default). Keying them on the global pair alone is exactly the pre-fix
      defect; keying them on anything not derived from the session flow (e.g. a
      nonce) would satisfy "has a key" while still never reflecting it.
  V3  `showCustomViewportEditor` must be keyed too — un-keyed it froze at the
      first frame, so the editor stayed hidden/interactive against the pool's
      real state.
  V4  each of the four viewport actions must clear the session override
      (`resetSessionViewport()`) BEFORE writing the global value: a session
      override shadows the global one, so without the clear the action (and in
      particular "Default") was a visible no-op.
  V5  regression anchor: `cookieFilterText` must stay UN-keyed. It is a pure
      local search box; giving it a key (e.g. the refresh tick) would reset the
      user's filter text on every recomposition — this arm is what proves the
      family has discrimination rather than "any change is green".
  V6  [da1030-B] the UA radio selection (`selectedProfile`) must be seeded from
      the pool's `currentUserAgentProfile` flow and keyed on it. The pre-fix
      sheet snapshotted the `user_agent_profile` prefs key once (un-keyed
      remember), so an agent `set_user_agent` — which now routes through the
      pool — left the panel rendering the old radio while the tabs had moved on.
  V7  [da1030-B] the pool's `when (input.action)` must route
      `BrowserAction.SET_USER_AGENT` through an explicit branch, and that
      handler must replay the resolved viewport (`applyViewportToAllTabs()`).
      Pre-fix the action fell into the per-tab `else`: one tab got the new UA,
      `BrowserUseManager.setUserAgent` reset that tab's layout to the profile
      default, and `applyViewport()` (no-arg) replayed only the profile's own
      size — so a session/global custom viewport was clobbered and the other
      tabs kept the old profile.

  V9  [dv29-B4 B-04] dropping the session viewport must reach the tabs: a
      clear that only zeroes `_sessionViewportWidth/Height` leaves every
      surviving tab's `lastAppliedViewport` on the previous session's size,
      which `navigate` / `minis://` injection / full-page capture replay
      forever. Criteria: replay after the last write, ONE non-suspend
      collection point that both zeroes and replays, every session re-point
      reaching it, and no writer gated on `hasOverride` (the 0 × 0 direction
      is the leak).

Families 1-4 are judged by [check] (cookie); B1–B2 by [check_domain_refresh],
V1–V6 by [check_viewport] and V7 by [check_pool_ua] — the latter is the first
criterion here that reads a second file (`BrowserTabPool.kt`). Each family
carries its own fixtures; all run on the real tree, not on samples.

Code anchors only — comments are stripped before judgment, so a KDoc that
mentions `removeAllCookies(null)` does not trip the gate.

[R3-t03] Third-round witness attack (dual-gate seams + one symmetry axis):
  * W82 every `BrowserUseManager(` construction site must inject the pool-level
    UA: the `setUserAgent(` on that manager has to derive from the very profile
    expression the manager was constructed with, and the custom-UA argument
    must not be a constant (witness: `UserAgentProfile.MOBILE_CHROME, null` —
    the user's typed UA never reaches a new/restored tab);
  * W83 the replay must have an EFFECT: `applyViewportToAllTabs` has to loop the
    live tabs and, inside that loop, reachably apply AND reload each one
    (witness: loop kept, both calls deleted — five entry points still "call"
    the replay and nothing changes on screen);
  * W84 a per-domain delete must carry an expiry instruction in the cookie
    literal itself (`Max-Age ≤ 0` or a past `Expires`). Witness:
    `"$it=1; Path=/"` — the call, the flush and the refresh tick are all still
    there, and the cookie is never expired, so the domain stays listed.

Usage: python3 browser_cookie_ui_refresh_check.py <repo_root>
       python3 browser_cookie_ui_refresh_check.py --root <repo_root>
       python3 browser_cookie_ui_refresh_check.py --selftest
Exit 0 = pass, 1 = violation, 2 = usage error.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_ast import const_value, match_bracket
from _kotlin_valueflow import (call_args, contains_ident, expr_end, path_feasible,
                               value_sources)

TARGET = "src/android/app/src/main/java/com/rikkaminis/app/ui/browser/BrowserSettingsSheet.kt"
POOL_TARGET = "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
MARKER = "BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS"


def strip_line_comments(src: str) -> str:
    """Comment-only masking used by the cookie family (strings PRESERVED).

    [wide3-06 F4] This used to be a hand-written `//`-only masker; it now
    delegates to the shared lexer's comment view — string literals survive (the
    `"https://$domain"` anchors need them), and a mention inside either comment
    style can neither satisfy nor trip a criterion.

    [batch5-t05] The hand-written masker is gone entirely: `mask_comments` is
    the single implementation, and it is length-preserving, so every position
    this gate computes maps straight back onto the source.
    """
    return mask_comments(src)


# ── shared-primitive helpers ────────────────────────────────────────────────
# [batch5-t05] Everything below is built on `_kotlin_lexer` / `_kotlin_ast` /
# `_kotlin_valueflow`: no hand-written brace counter, no literal whitelist.
# `mask_code` and `mask_comments` are both length-preserving, so positions are
# interchangeable between the two views (literals are read in the comment view,
# structure/reachability in the code view).


def _live_hits(code: str, pattern, start: int = 0, end: int = None) -> list:
    """Match starts of [pattern] inside [start, end) that sit on a REACHABLE path.

    A statement inside a statically-dead branch is not a statement. All the
    spellings that used to slip past the local whitelist — `if (false)`,
    `if (!true)`, `if (1 == 2)`, `false && guard` — fold to "unreachable" in
    `guard_feasible`, so the five `family-hole` witnesses of this batch all land
    here instead of each needing its own regex.
    """
    if isinstance(pattern, str):
        pattern = re.compile(pattern)
    stop = len(code) if end is None else min(end, len(code))
    return [
        m.start()
        for m in pattern.finditer(code, start, stop)
        if path_feasible(code, m.start())
    ]


def _live_pos(code: str, needle: str, start: int = 0) -> int:
    """First REACHABLE occurrence of the literal [needle] at/after [start]."""
    hits = _live_hits(code, re.escape(needle), start)
    return hits[0] if hits else -1


def _live_any(code: str, needle: str, start: int = 0, end: int = None) -> bool:
    return bool(_live_hits(code, re.escape(needle), start, end))


def _inside_guard_body(text: str, guard_start: int, write_pos: int) -> bool:
    """Is [write_pos] inside the body of the `if (...)` starting at [guard_start]?

    Body = the brace block following the condition, or — for a braceless
    `if (c) stmt` — the rest of that line. Position alone (`guard < write`) is
    not enough: `if (c) Unit` followed by an unconditional write satisfies it
    while the write escapes the branch entirely (witness V8). The same holds for
    the complementary `else` branch: a write there runs exactly when the guard is
    FALSE, which is what witness `G07-A3` does.
    """
    open_idx = text.find("(", guard_start)
    close = match_bracket(text, open_idx) if open_idx >= 0 else -1
    if close < 0:
        return False
    j = close + 1
    while j < len(text) and text[j] in " \t":
        j += 1
    if j < len(text) and text[j] == "{":
        end_b = match_bracket(text, j)
        return end_b > 0 and j <= write_pos <= end_b
    eol = text.find("\n", j)
    end = len(text) if eol < 0 else eol
    return j <= write_pos < end


def _brace_ranges(src: str):
    """Every balanced `{ … }` span as (start, end) — the shared matcher, one pass."""
    out = []
    i = 0
    while True:
        j = src.find("{", i)
        if j < 0:
            return out
        k = match_bracket(src, j)
        if k < 0:
            return out
        out.append((j, k + 1))
        i = j + 1


def _smallest_block_with(src: str, pos: int, needles):
    """Smallest brace block containing [pos] that also contains every needle.

    -> (start, end); (-1, -1) when no block qualifies (the caller reports
    fail-closed rather than assuming green).
    """
    for a, b in sorted(
        (r for r in _brace_ranges(src) if r[0] < pos < r[1]),
        key=lambda r: r[1] - r[0],
    ):
        body = src[a:b]
        if all(n in body for n in needles):
            return (a, b)
    return (-1, -1)


def _block_from(src: str, marker: str):
    """Span of the balanced `{ … }` that follows [marker] -> (start, end).

    Replaces the old line-based brace counter: a `{`/`}` inside a string or a
    regex literal used to move the depth and truncate (or over-extend) the
    slice. `marker` may itself end in `{` (e.g. `onApply = {`).
    """
    at = src.find(marker)
    if at < 0:
        return (-1, -1)
    j = at + marker.rfind("{")
    if j < at or j >= len(src) or src[j] != "{":
        j = src.find("{", at)
    if j < 0:
        return (-1, -1)
    k = match_bracket(src, j)
    return (-1, -1) if k < 0 else (j, k + 1)


def slice_from_marker(src: str, marker: str) -> str:
    """The balanced brace block after [marker] (empty string when absent)."""
    a, b = _block_from(src, marker)
    return "" if a < 0 else src[a:b]


def _decl_span(code: str, ident: str):
    """`val|var ident … = <expr>` 的初始化表达式区间 -> (start, end)。

    `by remember(…)` 这类委托声明没有 `=`，退回整条声明语句；两者都取不到时
    返回 (-1, -1)，调用方 fail-closed。
    """
    m = re.search(r"^[ \t]*(?:val|var)\s+" + re.escape(ident) + r"\b", code, re.M)
    if not m:
        return (-1, -1)
    line_end = code.find("\n", m.end())
    if line_end < 0:
        line_end = len(code)
    eq = re.search(r"(?<![=!<>])=(?!=)", code[m.end():line_end])
    if not eq:
        win = _statement_window(code, ident)
        at = code.find(win) if win else -1
        return (-1, -1) if at < 0 else (at, at + len(win))
    start = m.end() + eq.end()
    return (start, expr_end(code, start))


def _derives_live(code: str, ident: str, needle: str) -> bool:
    """[ident] 的声明是否由 [needle] 派生，**且该派生点在可达路径上**。

    [batch5-t05 G07-A1] `when { false && sessionViewportActive ->
    sessionWidthFromPool }` still *contains* `sessionWidthFromPool`, and the old
    criterion was a substring test on the declaration window — so a permanently
    false prefix on the branch that supplies the session value kept it green
    while the panel fell back to the global/UA width forever. Existence is not
    evaluation: the occurrence has to be reachable.
    """
    a, b = _decl_span(code, ident)
    if a < 0:
        return False
    return _live_any(code, needle, a, b)


SESSION_SOURCES = {
    "viewportWidthText": "sessionWidthFromPool",
    "viewportHeightText": "sessionHeightFromPool",
}
VIEWPORT_ACTIONS = ("onSelectDefault", "onSelectCustom", "onApply", "onPreset")
RESET_CALL = "resetSessionViewport()"
GLOBAL_WRITE = "setGlobalViewport("
FROZEN_FILTER = "cookieFilterText"
UA_POOL_FLOW = "currentUserAgentProfile"
REPLAY_CALL = "applyViewportToAllTabs()"

# ── [dv29-B4 B-04] V9: dropping the session viewport must reach the tabs ─────
REPLAY_APPLY = "applyViewportToAllTabs("
# A `fun` header at class-member indentation (4 spaces); visibility and
# `suspend` prefixes, generic parameter lists and extension receivers allowed.
FUN_HEADER = re.compile(
    r"^ {4}(?:(?:private|internal|public|protected)\s+)?(?:suspend\s+)?"
    r"fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\("
)
VALUE_WRITE = re.compile(r"_sessionViewport(?:Width|Height)\s*\.\s*value\s*=")
ZERO_WRITE = re.compile(r"_sessionViewport(?:Width|Height)\s*\.\s*value\s*=\s*0\b")
REPOINT = re.compile(r"this\.sessionId\s*=\s*sessionId")


def _statement_window(code, ident):
    """The declaration statement of [ident]: its `val` line plus any following
    lines indented deeper than that line (a wrapped expression).

    Stopping at the first line whose indentation is <= the declaration's is what
    makes the V2 criterion mean something: a fixed "next N lines" window let an
    unrelated neighbouring `val` (the session subscription itself) satisfy
    "derived from the session flow" — which is a false negative, exactly the
    shape V2 exists to catch.
    """
    m = re.search(r"^([ \t]*)val\s+" + re.escape(ident) + r"\b", code, re.M)
    if not m:
        return None
    indent = len(m.group(1).replace("\t", "    "))
    lines = code[m.start():].split("\n")
    out = [lines[0]]
    for line in lines[1:]:
        if not line.strip():
            break
        if len(line) - len(line.lstrip()) <= indent:
            break
        out.append(line)
    return "\n".join(out)


def check_viewport(source: str) -> list:
    """[da1029-E] V1–V5 — the panel must show the viewport the pool resolves."""
    try:
        code = mask_code(source)
    except KotlinLexError as exc:
        return ["viewport: lexical analysis failed (%s) — refusing to fall back to "
                "raw text (fail-closed)" % exc]
    errs = []

    for flow, other in (("sessionViewportWidth", "sessionViewportHeight"),
                        ("sessionViewportHeight", "sessionViewportWidth")):
        if not _live_hits(code, r"\.%s\s*\.\s*collectAsState\s*\(\s*\)" % flow):
            errs.append(
                "does not subscribe to the %s flow — a session override (what an "
                "agent set_viewport writes) stays invisible to this panel" % flow
            )

    for state, session_flow in SESSION_SOURCES.items():
        m = re.search(r"var\s+%s\s+by\s+remember\s*\(([^)]*)\)" % re.escape(state), code)
        if not m:
            errs.append(
                "`var %s by remember {` has no key list at all — it freezes on the "
                "values seen at the first composition" % state
            )
            continue
        keys = [k.strip() for k in m.group(1).split(",") if k.strip()]
        if not keys:
            errs.append("`%s` has an empty remember key list" % state)
            continue
        derived = False
        for key in keys:
            if not re.fullmatch(r"[A-Za-z_]\w*", key):
                continue
            if _derives_live(code, key, session_flow):
                derived = True
        if not derived:
            errs.append(
                "`%s` is keyed on %s, none of which is derived from `%s` on a "
                "reachable path — the state cannot follow a session-override change "
                "(V2)" % (state, keys, session_flow)
            )

    m = re.search(r"var\s+showCustomViewportEditor\s+by\s+remember\s*\(([^)]*)\)", code)
    if not m or not m.group(1).strip():
        errs.append(
            "`var showCustomViewportEditor by remember {` is un-keyed — the "
            "editor freezes at the first frame instead of following the pool"
        )

    for action in VIEWPORT_ACTIONS:
        block = slice_from_marker(code, action + " = {")
        if not block:
            errs.append(
                "no `%s = {` block — a viewport action the sheet pins is gone "
                "(ROT: update this gate)" % action
            )
            continue
        reset_at = _live_pos(block, RESET_CALL)
        if reset_at < 0:
            errs.append(
                "`%s` never calls `%s` on a reachable path (`if (false)`, `!true`, "
                "`1 == 2` — the whole statically-dead family does not count) — a "
                "session override shadows the global value it writes, so the action "
                "is a no-op for those tabs (V4)"
                % (action, RESET_CALL)
            )
            continue
        write_at = _live_pos(block, GLOBAL_WRITE)
        if write_at < 0:
            errs.append(
                "`%s` clears the session override but never writes a global "
                "value — the clear alone leaves every tab at the UA default"
                % action
            )
            continue
        if reset_at > write_at:
            errs.append(
                "`%s` writes the global viewport before clearing the session "
                "override — the stale override wins the resolve (V4)" % action
            )

    if re.search(r"var\s+%s\s+by\s+remember\s*\(" % FROZEN_FILTER, code):
        errs.append(
            "`%s` gained a remember key — it is a pure local search box, so a key "
            "(the refresh tick, the pool state) resets the user's typed filter "
            "text on recomposition (V5 regression anchor)" % FROZEN_FILTER
        )
    elif not re.search(r"var\s+%s\s+by\s+remember\s*\{" % FROZEN_FILTER, code):
        errs.append(
            "`%s` is gone or no longer a plain un-keyed remember — the V5 "
            "regression anchor lost its subject (ROT: re-point it)" % FROZEN_FILTER
        )

    # ── V6 [da1030-B] the UA radio must follow the pool, not a prefs snapshot ──
    if not _live_hits(code, r"\.%s\s*\.\s*collectAsState\s*\(\s*\)" % UA_POOL_FLOW):
        errs.append(
            "does not subscribe to `%s` — an agent `set_user_agent` (which now "
            "routes through the pool) leaves this panel on the old radio (V6)"
            % UA_POOL_FLOW
        )
    m = re.search(r"var\s+selectedProfile\s+by\s+remember\s*\(([^)]*)\)", code)
    if not m:
        errs.append(
            "`var selectedProfile by remember {` has no key list at all — it froze "
            "on the prefs snapshot read at the first composition, so the radio kept "
            "showing the profile the sheet was opened with (V6)"
        )
    else:
        keys = [k.strip() for k in m.group(1).split(",") if k.strip()]
        backed = False
        for key in keys:
            if not re.fullmatch(r"[A-Za-z_]\w*", key):
                continue
            if _derives_live(code, key, UA_POOL_FLOW):
                backed = True
        if not backed:
            errs.append(
                "`selectedProfile` is keyed on %s, none of which is fed by the "
                "pool's `%s` flow — a key must re-seed the selection from the pool "
                "(V6)" % (keys, UA_POOL_FLOW)
            )

    return errs


def _pool_members(code: str):
    """Members of the pool class, derived from the file.

    A member is a `fun` header line at class-member indentation (4 spaces) and
    everything up to the next such header. Line-based on purpose: brace pairing
    breaks on expression bodies and on lambdas, and the pool has both (COMMON
    §4). `code` must already be masked, so comments cannot create or hide a
    member.
    """
    lines = code.split("\n")
    starts = [i for i, l in enumerate(lines) if FUN_HEADER.match(l)]
    members = []
    for idx, st in enumerate(starts):
        end = starts[idx + 1] if idx + 1 < len(starts) else len(lines)
        header = lines[st]
        members.append({
            "header": header,
            "name": FUN_HEADER.match(header).group(1),
            "body": "\n".join(lines[st:end]),
            "suspend": "suspend fun " in header,
        })
    return members


def _calls(body: str, name: str) -> bool:
    """Is there a REACHABLE call of [name] in [body]?

    [batch5-t05] A call wrapped in a statically-dead branch (`if (false)
    replay()`) is not a call — that is witness `G07-A4`, where the write path
    kept every token and dropped the effect.
    """
    return bool(_live_hits(body, r"\b" + re.escape(name) + r"\s*\("))


def _replay_bearing(members):
    """Names of members that re-apply the resolved viewport: the direct ones,
    plus the one hop that only delegates to one of them."""
    direct = {m["name"] for m in members if REPLAY_APPLY in m["body"]}
    indirect = {
        m["name"] for m in members
        if m["name"] not in direct and any(_calls(m["body"], n) for n in direct)
    }
    return direct | indirect


def _replay_positions(body: str, replaying) -> list:
    """Positions of REACHABLE replay calls (dead-branch calls are skipped)."""
    out = []
    for n in replaying:
        out.extend(_live_hits(body, r"\b" + re.escape(n) + r"\s*\("))
    return out


def _reaches(body_start, members, targets, max_depth):
    """Breadth-first walk over the pool's own call graph, depth-bounded."""
    by_name = {m["name"]: m for m in members}
    frontier = {body_start}
    seen = {body_start}
    for _ in range(max_depth):
        nxt = set()
        for n in frontier:
            body = by_name[n]["body"] if n in by_name else ""
            if any(t != n and _calls(body, t) for t in targets):
                return True
            for m in members:
                if m["name"] not in seen and _calls(body, m["name"]):
                    nxt.add(m["name"])
        seen |= nxt
        if not nxt:
            return False
        frontier = nxt
    return False


def check_pool_viewport_reset(pool_src: str) -> list:
    """[dv29-B4 B-04] V9 — dropping the session viewport must reach the tabs.

    `_sessionViewportWidth/Height` are the pool's *intent*; each tab's effective
    size lives in `BrowserUseManager.lastAppliedViewport`, which every later
    `navigate`, `minis://` injection and full-page capture replays. The pre-fix
    code wrote 0 into the flows on three paths (session hand-off, restore with no
    state file, restore of a session without an override) and never called
    `applyViewportToAllTabs()`, so the previous session's size stayed applied to
    the surviving WebViews.

    Criteria, all derived from the tree (no member-name list):

      R1  every member that writes a session viewport flow must replay AFTER its
          last write (in its own body, or by calling a member that does);
      R2  exactly one NON-suspend member may zero the flows — that is the
          collection point, and it must be private and replay (a plain function
          cannot await the replay, so a fourth path that zeroes on its own is
          the defect this pins);
      R3  every member that re-points the pool (`this.sessionId = sessionId`)
          must reach the clearing member through its own call graph;
      R4  no writer may condition on `hasOverride`: the 0 × 0 direction is the
          leak.
    """
    try:
        code = mask_code(pool_src)
    except KotlinLexError as exc:
        return ["pool-viewport-reset: lexical analysis failed (%s) — fail-closed" % exc]
    errs = []
    members = _pool_members(code)
    if not members:
        return [
            "no class members found in %s — the derivation anchor drifted "
            "(ROT: update this gate)" % POOL_TARGET
        ]
    replaying = _replay_bearing(members)
    if not replaying:
        return [
            "no member calls `%s` — the viewport replay this criterion anchors on "
            "is gone (ROT: update this gate)" % REPLAY_APPLY
        ]

    writers = [m for m in members if VALUE_WRITE.search(m["body"])]
    if not writers:
        errs.append(
            "no member writes `_sessionViewportWidth/Height.value` — this criterion "
            "lost its subject (ROT: re-point it)"
        )
    for m in writers:
        last_write = max(v.start() for v in VALUE_WRITE.finditer(m["body"]))
        last_replay = max(_replay_positions(m["body"], replaying) or [-1])
        if last_replay < last_write:
            errs.append(
                "`%s` writes a session viewport but never replays it afterwards on a "
                "reachable path — the surviving tabs keep the previous size in "
                "`lastAppliedViewport`" % m["header"].strip()
            )
        if "hasOverride" in m["body"]:
            errs.append(
                "`%s` conditions the session viewport on `hasOverride` — the 0 × 0 "
                "(no override) direction is exactly the leak (R4)" % m["name"]
            )

    clearers = [m for m in members if ZERO_WRITE.search(m["body"]) and not m["suspend"]]
    if len(clearers) != 1:
        errs.append(
            "expected exactly one non-suspend member to zero the session viewport, "
            "found %d (%s) — the clear must stay a single collection point so a fourth "
            "path cannot drop the flows without replaying (R2)"
            % (len(clearers), ", ".join(m["name"] for m in clearers) or "none")
        )
    else:
        point = clearers[0]
        if "private fun " not in point["header"]:
            errs.append(
                "the collection point `%s` is not private — it is the pool's own "
                "invariant" % point["name"]
            )
        for flow in ("_sessionViewportWidth.value = 0", "_sessionViewportHeight.value = 0"):
            if flow not in point["body"]:
                errs.append(
                    "the collection point `%s` does not `%s` — both flows must drop "
                    "together" % (point["name"], flow)
                )
        if not _replay_positions(point["body"], replaying):
            errs.append(
                "the collection point `%s` zeroes the flows without replaying them on a "
                "reachable path (`if (false) replay()` — the whole statically-dead "
                "family) — that is the pre-fix shape with a new name" % point["name"]
            )

    targets = {m["name"] for m in clearers}
    repointers = [m for m in members if REPOINT.search(m["body"])]
    if not repointers:
        errs.append(
            "no member re-points the pool (`this.sessionId = sessionId`) — this "
            "criterion lost its subject (ROT: re-point it)"
        )
    elif targets:
        for m in repointers:
            if not _reaches(m["name"], members, targets, 3):
                errs.append(
                    "`%s` re-points the pool without reaching the viewport clear — the "
                    "incoming session inherits the previous session's size (R3)"
                    % m["name"]
                )
    return errs


def check_custom_ua_single_source(pool_src: str, sheet_src: str) -> list:
    """[dv23-B4 B-05] V8 — the stored custom UA string has ONE source.

    The defect: `setUserAgentFromUI(profile, customUA)` assigned
    `customUserAgentString = customUA` unconditionally, so tapping a
    non-CUSTOM preset (which passes `null`) CLEARED the string the user had
    typed; a later `set_user_agent --user-agent custom` then fell back to the
    platform UA. The sheet's own field is the real source (it persists
    `custom_user_agent` on every keystroke), so the pool must resolve through
    that key and must not treat "no opinion" as "clear".

    Anchored on the DECISION (a guarded write, and a resolution through the
    single source), not on spelling.
    """
    try:
        code = mask_comments(pool_src)
    except KotlinLexError as exc:
        return ["custom-ua: lexer failed (%s) — fail-closed" % exc]
    errs = []

    setter = slice_from_marker(code, "fun setUserAgentFromUI(")
    if not setter:
        errs.append(
            "no `fun setUserAgentFromUI(` in %s — the sheet's apply path this "
            "criterion pins is gone (ROT: update this gate)" % POOL_TARGET
        )
        return errs

    guard = setter.find("if (customUA != null)")
    # [batch5-t05 G07-A3] EVERY write to the stored UA is judged, not just the
    # first one: the old criterion took `setter.find(...)` (one hit) and asked
    # "is there a guard before it, and is that hit inside the guard's body".
    # Keeping the guarded write untouched and appending the complementary
    # branch — `else { customUserAgentString = null }` — therefore stayed green
    # although that branch runs exactly when `customUA == null`, i.e. on every
    # non-CUSTOM preset tap (the very wipe V8 exists to prevent). A post-block
    # overwrite (`settings.userAgentString = ""`) is the same shape.
    writes = list(re.finditer(
        r"\bcustomUserAgentString\s*=(?!=)"
        r"|\bsettings\s*\.\s*userAgentString\s*=(?!=)",
        setter,
    ))
    if guard < 0:
        errs.append(
            "`setUserAgentFromUI` assigns the stored custom UA unconditionally — "
            "a preset tap (which carries no custom string) clears the string the "
            "user typed, and a later `set_user_agent --user-agent custom` falls "
            "back to the platform UA (V8)"
        )
    elif not writes:
        errs.append(
            "`setUserAgentFromUI` no longer stores the custom UA at all (V8)"
        )
    else:
        for hit in writes:
            pos = hit.start()
            rhs = setter[hit.end():expr_end(setter, hit.end())]
            lhs = re.sub(r"\s+", "", hit.group(0)[:-1])
            if pos < guard:
                errs.append(
                    "`setUserAgentFromUI` writes the stored custom UA BEFORE the "
                    "`customUA != null` guard — the guard does not protect the "
                    "write (V8)"
                )
            elif not _inside_guard_body(setter, guard, pos):
                # [wide3-06 V8] `if (customUA != null) Unit` + an unconditional
                # write keeps `guard < write` true while the write escapes the
                # guard's body; [batch5-t05] so does the complementary `else`
                # branch — both run when `customUA == null`.
                errs.append(
                    "`setUserAgentFromUI` writes the stored custom UA OUTSIDE the "
                    "`customUA != null` branch (`%s` — an `else` / post-block write "
                    "runs exactly when the guard is false), so a preset tap "
                    "(customUA = null) still clears the string the user typed (V8)"
                    % lhs
                )
            elif not path_feasible(setter, pos):
                errs.append(
                    "`setUserAgentFromUI` stores the custom UA inside a "
                    "statically-dead branch (`%s`) — the write never runs, so the "
                    "typed string is lost on apply (V8)" % lhs
                )
            elif "customUA" not in rhs:
                errs.append(
                    "`setUserAgentFromUI` writes `%s` from `%s` inside the guard — "
                    "not from `customUA`, so the guarded write stores a value the "
                    "caller never supplied (V8)" % (lhs, re.sub(r"\s+", " ",
                                                                 rhs.strip())[:40])
                )

    if "storedCustomUserAgent()" not in code:
        errs.append(
            "the pool has no single-source resolution for the stored custom UA — "
            "`customUserAgentString` is a private cache that can lag the sheet's "
            "last keystroke (V8)"
        )

    handler = slice_from_marker(code, "fun handleSetUserAgent(")
    if not handler:
        errs.append(
            "no `fun handleSetUserAgent(` in %s (V8)" % POOL_TARGET
        )
    elif "storedCustomUserAgent()" not in handler:
        errs.append(
            "`handleSetUserAgent` does not resolve CUSTOM through the single "
            "source — it reads the private cache, which may predate the sheet's "
            "last keystroke (V8)"
        )

    try:
        sheet = mask_comments(sheet_src)
    except KotlinLexError as exc:
        return ["custom-ua: lexer failed on the sheet (%s) — fail-closed" % exc]
    if "stageCustomUserAgent(" not in sheet:
        errs.append(
            "the settings sheet's UA field does not feed the pool's copy — the "
            "pool's cache stays stale until Apply, while the sheet persists the "
            "string on every keystroke (V8)"
        )
    return errs


def check_pool_ua(source: str) -> list:
    """[da1030-B] V7 — `set_user_agent` must be a pool-level action.

    Two halves, each with a fixture: the branch must exist explicitly (falling
    into the per-tab `else` is the defect) and its handler must replay the
    resolved viewport (`BrowserUseManager.setUserAgent` resets the tab layout to
    the new profile's default; without the replay a session/global custom
    viewport is silently clobbered).
    """
    try:
        code = mask_code(source)
    except KotlinLexError as exc:
        return ["pool-ua: lexical analysis failed (%s) — fail-closed" % exc]
    errs = []

    block = slice_from_marker(code, "when (input.action)")
    if not block:
        errs.append(
            "no `when (input.action)` block in %s — the pool dispatch this "
            "criterion pins is gone (ROT: update this gate)" % POOL_TARGET
        )
        return errs

    # Reachable branch: a branch that is present but sits behind a statically
    # dead guard dispatches nothing, which is the same outcome as no branch.
    live, handler = None, None
    for hit in re.finditer(
            r"BrowserAction\.SET_USER_AGENT\s*->\s*([A-Za-z_]\w*)\s*\(", block):
        if path_feasible(block, hit.start()):
            live, handler = hit, hit.group(1)
            break
    if live is None:
        errs.append(
            "`when (input.action)` has no explicit `BrowserAction.SET_USER_AGENT ->` "
            "branch on a reachable path — the action falls into the per-tab `else`, "
            "so it hits ONE tab, skips the viewport replay and leaves the "
            "panel/other tabs on the old profile (V7)"
        )
        return errs

    body = slice_from_marker(code, "fun %s(" % handler)
    if not body:
        errs.append(
            "`BrowserAction.SET_USER_AGENT ->` calls `%s(` but no such function body "
            "exists in %s (V7)" % (handler, POOL_TARGET)
        )
    elif _live_pos(body, REPLAY_CALL) < 0:
        errs.append(
            "`%s` never calls `%s` on a reachable path — `setUserAgent` resets each "
            "tab to the new profile's default viewport, so a session/global custom "
            "override is clobbered (V7)" % (handler, REPLAY_CALL)
        )

    # ── [R3-t03 W83] 调用存在 ≠ 重放发生 ─────────────────────────────────
    # 由独立的 check_replay_effect() 负责（它的判据要看整个函数的循环体，
    # 而本函数的夹具是切片；混在一起会让「合规切片」因为函数体不在切片里
    # 而被误红）。
    return errs


def check_replay_effect(pool_src: str) -> list:
    """[R3-t03 W83] 五个入口「调用了」重放，不等于重放真的驱动了每个标签页。

    V7 只判 handler 里出现了 `applyViewportToAllTabs()`。把该函数体的
    `for (tab in _tabs.value) { … }` 掏空（循环保留、两个调用删掉），五个入口
    仍然全部「调用」了它 —— 体验上是「改了视口设置，所有已开标签页的排版与
    window.innerWidth 一点没变」。
    """
    try:
        code = mask_code(pool_src)
    except KotlinLexError as exc:
        return ["replay-effect: lexer failed (%s) — fail-closed" % exc]
    return _replay_effect(code)



def _replay_effect(code: str) -> list:
    """[R3-t03 W83] `applyViewportToAllTabs` 必须真的驱动每一个活跃标签页。"""
    target = REPLAY_CALL[:-2]                       # `applyViewportToAllTabs`
    member = None
    for m in _pool_members(code):
        if m["name"] == target:
            member = m
            break
    if member is None:
        return ["the viewport replay `%s(…)` has no body in %s — five entry points "
                "call a function that does not exist (fail-closed)"
                % (target, POOL_TARGET)]
    body = member["body"]
    loops = []
    for m in re.finditer(r"\bfor\s*\(([^)]*)\)\s*\{", body):
        close = match_bracket(body, m.end() - 1)
        if close > 0:
            var = m.group(1).split(" in ", 1)[0].strip()
            iterable = m.group(1).split(" in ", 1)[1].strip() if " in " in m.group(1) else ""
            loops.append((var, iterable, m.end() - 1, close))
    if not loops:
        return ["`%s` no longer iterates the live tabs — the replay has no loop, so no "
                "tab's layout is ever re-applied (W83)" % target]
    out = []
    for var, iterable, lo, hi in loops:
        if not iterable:
            out.append("`%s`'s loop header supplies no collection — fail-closed" % target)
            continue
        inner = body[lo:hi]
        # 循环变量的哪些名字指向它（`val manager = tab.manager` 是正当重构）
        holders = {var} | set(re.findall(
            r"(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]*)?=\s*%s\s*\." % re.escape(var),
            inner))
        applies = []
        for name in sorted(holders):
            applies.extend(_live_hits(inner, r"\b%s\s*\.\s*" % re.escape(name)))
        applied = [p for p in applies
                   if re.search(r"\bapplyViewport\s*\(", inner[p:p + 240])]
        reloads = [p for p in applies
                   if re.search(r"\breload\w*\s*\(", inner[p:p + 240])]
        if not applied:
            out.append(
                "`%s` loops over the tabs but never calls `%s.….applyViewport(` inside "
                "the loop — every live tab keeps the old layout (W83)" % (target, var))
        if not reloads:
            out.append(
                "`%s` never reloads `%s` inside the loop — a resize alone does not "
                "refresh `window.innerWidth/Height` (WebView snapshots the CSS viewport "
                "at load time), so the new size never reaches the page (W83)"
                % (target, var))
    return out


MANAGER_CTOR = re.compile(r"\bBrowserUseManager\s*\(")


def _member_body(code: str, pos: int):
    """覆盖 [pos] 的那个成员（`_pool_members` 派生）-> (member, start) 或 (None, -1)。

    返回 start 是该成员体在 [code] 里的起点，调用方用它把全局位置换算成
    成员体内的局部位置。
    """
    offset = 0
    for m in _pool_members(code):
        start = code.find(m["body"], offset)
        if start < 0:
            continue
        end = start + len(m["body"])
        offset = end
        if start <= pos < end:
            return m, start
    return None, -1


def check_tab_ua_injection(pool_src: str) -> list:
    """[R3-t03 W82] 新建 / 恢复的标签页必须继承**池级**的 User-Agent。

    V8 判的是「自定义 UA 有一个单一来源」，而建标签页时的注入点谁都不管：
    把 `createTab` 里 `manager.setUserAgent(userAgentProfile, customUserAgentString)`
    改成写死的 `UserAgentProfile.MOBILE_CHROME, null`，用户开了自定义 UA，每
    新开一个标签页 / 每次会话恢复重建标签，网页收到的又变回默认移动 Chrome。

    判据从树派生：每个 `BrowserUseManager(…)` 构造点 → 取它绑定的变量名和
    构造时用的 profile 表达式 → 该变量随后的 `setUserAgent(` 必须由同一个
    profile 派生，且自定义 UA 实参不能是常量 null。
    """
    try:
        code = mask_code(pool_src)
    except KotlinLexError as exc:
        return ["tab-ua: lexer failed (%s) — fail-closed" % exc]
    errs = []
    ctors = list(MANAGER_CTOR.finditer(code))
    if not ctors:
        errs.append(
            "no `BrowserUseManager(` construction exists in %s — the tab-creation "
            "surface this criterion pins is gone (ROT: re-pin it)" % POOL_TARGET)
        return errs
    for ctor in ctors:
        line = code.count("\n", 0, ctor.start()) + 1
        window, member_start = _member_body(code, ctor.start())
        if window is None:
            errs.append("the `BrowserUseManager(` at line %d is not inside a member — "
                        "fail-closed" % line)
            continue
        body = window["body"]
        local = ctor.start() - member_start
        args = call_args(code, ctor.end() - 1)
        if len(args) < 2:
            errs.append("a `BrowserUseManager(` construction has no profile argument "
                        "(fail-closed)")
            continue
        profile = code[args[1][0]:args[1][1]].strip()
        name = _ctor_variable(body, local)
        if not name:
            errs.append("the `BrowserUseManager(` at line %d is not bound to a variable "
                        "(fail-closed)" % line)
            continue
        calls = list(re.finditer(r"\b%s\s*\.\s*setUserAgent\s*\(" % re.escape(name), body))
        if not calls:
            errs.append(
                "`%s` is constructed but never given the pool's User-Agent "
                "(`%s.setUserAgent(…)`) — a new/restored tab silently runs on the "
                "webview default instead of the pool's profile (W82)" % (name, name))
            continue
        for call in calls:
            if not _live_hits(body, r"\b%s\s*\.\s*setUserAgent\s*\(" % re.escape(name),
                              call.start(), call.end()):
                errs.append(
                    "`%s.setUserAgent(…)` sits on a statically-dead path — the tab "
                    "keeps whatever UA the webview was born with (W82)" % name)
                continue
            ua_args = call_args(body, call.end() - 1)
            if not ua_args:
                errs.append("`%s.setUserAgent(` has no arguments (fail-closed)" % name)
                continue
            got_profile = body[ua_args[0][0]:ua_args[0][1]].strip()
            if not _derives_from(got_profile, profile, body):
                errs.append(
                    "`%s.setUserAgent(%s, …)` does not derive from the pool's own "
                    "profile `%s` (the value the manager was constructed with) — every "
                    "new or restored tab drops the user's chosen UA (W82)"
                    % (name, got_profile[:40], profile[:40]))
            if len(ua_args) > 1:
                custom = body[ua_args[1][0]:ua_args[1][1]].strip()
                if const_value(custom) is not None:
                    errs.append(
                        "`%s.setUserAgent(…, %s)` passes a constant as the custom UA — "
                        "a CUSTOM profile can never reach the tab, so the user's typed "
                        "string is ignored (W82)" % (name, custom[:40]))
    return errs


def _ctor_variable(body: str, ctor_pos: int) -> str:
    """`val NAME = BrowserUseManager(` 的 NAME（取不到返回 ''）。"""
    m = re.search(r"([A-Za-z_]\w*)\s*=\s*$", body[max(0, ctor_pos - 200):ctor_pos])
    return m.group(1) if m else ""


def _derives_from(expr: str, source: str, body: str = "") -> bool:
    """`expr` 的求值是否来自 `source`（裸常量/写死的枚举成员不算）。

    [body] 用来跟一跳局部别名（`val profileForTab = userAgentProfile`）——
    别名是正当重构，判据必须跟到那一跳，否则「加一行中间变量」就会误红。
    """
    e = expr.strip()
    if const_value(e) is not None:
        return False
    if const_value(source) is not None:
        return False                      # 池级 profile 自己是常量 → 无从派生
    base = source.split(".")[-1]
    for _ in range(3):
        if any(contains_ident(s, base) for s in value_sources(e)):
            return True
        if not re.fullmatch(r"[A-Za-z_]\w*", e) or not body:
            return False
        m = re.search(r"(?:val|var)\s+%s\s*(?::[^=\n]*)?=\s*" % re.escape(e), body)
        if not m:
            return False
        nxt = body[m.end():expr_end(body, m.end())].strip()
        if not nxt or nxt == e:
            return False
        e = nxt
        if const_value(e) is not None:
            return False
    return False



def _remember_key_list(code: str, decl_marker: str):
    """Ordered key tokens of the `remember(...)` call that follows `decl_marker`
    (e.g. "val hasCookies = remember"). Paren-matched, so a wrapped key list
    cannot truncate the way a first-`)` scan would. None when the declaration
    is absent (caller reports fail-closed)."""
    idx = code.find(decl_marker)
    if idx < 0:
        return None
    op = code.find("(", idx + len(decl_marker))
    if op < 0:
        return None
    depth = 0
    i = op
    while i < len(code):
        c = code[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    if depth != 0:
        return None
    return [k.strip() for k in code[op + 1:i].split(",") if k.strip()]


def _revision_backed(code: str, key: str) -> bool:
    """True when `key` is an identifier whose declaration subscribes to the
    history store's revision flow (`val <key> by ...revision.collectAsState()`).

    Binds B2 to the DECISION POINT — the key must be fed by the store's counter,
    not merely exist. A key on a nonce or an unrelated state has no declaration
    carrying `.revision.collectAsState` and fails this probe."""
    if not re.fullmatch(r"[A-Za-z_]\w*", key):
        return False
    return re.search(
        r"val\s+%s\s+by\s+[\w.]*revision\s*\.\s*collectAsState" % re.escape(key), code
    ) is not None


def check_domain_refresh(source: str) -> list:
    """[deep-audit-1039 B-07] B1–B2 — the domain list must follow the store.

    Pre-fix shape (the reason this family exists): `allDomains` was keyed on
    `(hasCookies, cookieRefreshTick)` — a panel-local counter that never moves
    when the history store changes, so switching UA/Viewport in the sheet
    reloaded the tab, `record()` planted the new domain, and the list kept
    showing the pre-reload snapshot until the sheet was closed and reopened.
    """
    try:
        code = strip_line_comments(source)
    except KotlinLexError as exc:
        return ["domain-refresh: lexical analysis failed (%s) — fail-closed" % exc]
    errs = []

    if not _live_hits(code, r"\.revision\s*\.\s*collectAsState\s*\(\s*\)"):
        errs.append(
            "does not subscribe to `historyStore.revision` — the domain list is "
            "keyed on panel-local state only, so a reload-driven record() (UA / "
            "viewport switch) leaves it on the pre-reload snapshot (B1)"
        )
        return errs

    for decl_marker, label in (
        ("val hasCookies = remember", "hasCookies"),
        ("val allDomains = remember", "allDomains"),
    ):
        keys = _remember_key_list(code, decl_marker)
        if keys is None:
            errs.append(
                "`%s` remember declaration not found — the domain-refresh anchor "
                "lost its subject (ROT: re-point it)" % label
            )
            continue
        if "cookieRefreshTick" not in keys:
            errs.append(
                "`%s` remember keys %s dropped `cookieRefreshTick` — the "
                "panel-local signal still owns cookie deletes / clear-all and "
                "must stay wired (family 4 regression)" % (label, keys)
            )
            continue
        if not any(_revision_backed(code, k) for k in keys):
            errs.append(
                "`%s` is keyed on %s, none of which is fed by "
                "`historyStore.revision` — the read cannot follow a reload-driven "
                "record() (B2)" % (label, keys)
            )

    return errs


def check(source: str) -> list:
    """Families 1–4 — the cookie section persists and refreshes itself.

    [batch5-t05 G07-A5] Every "the call is there" test here is now a
    reachability test. The witness wrapped the per-domain `cm.flush()` +
    `cookieRefreshTick++` in `if (false) { … }`: both tokens survived every
    substring test while the delete neither persisted nor reset the list —
    exactly the pre-fix defect with a five-character prefix. `flush()` inside a
    dead branch is not a flush.
    """
    try:
        code = mask_code(source)
        lit = mask_comments(source)
    except KotlinLexError as exc:
        return ["cookie family: lexical analysis failed (%s) — fail-closed" % exc]
    errs = []

    # 1. callback-less removeAllCookies(null) must be gone
    if _live_any(code, "removeAllCookies(null)"):
        errs.append("callback-less removeAllCookies(null) still present")

    # 2. callback form with a reachable flush() inside
    cb_start, cb_end = _block_from(code, "removeAllCookies {")
    if cb_start < 0:
        errs.append("no callback form removeAllCookies { ... } found")
    elif not _live_hits(code, r"\bflush\s*\(\s*\)", cb_start, cb_end):
        errs.append("removeAllCookies callback does not flush()")

    # 3. per-domain delete covers both schemes
    #    Anchored on the DECLARATION's initializer (both branches of the
    #    if/else), not on a line-counted brace slice: the old slice happened to
    #    include the else arm only because `} else {` closed and reopened the
    #    depth on one line.
    cand_start, cand_end = _decl_span(code, "candidateUrls")
    if cand_start < 0:
        errs.append("per-domain delete has no candidateUrls declaration")
    elif not path_feasible(code, cand_start):
        errs.append(
            "the candidateUrls enumeration sits on a statically-dead path — the "
            "per-domain delete never runs")
    else:
        cand = lit[cand_start:cand_end]
        if '"https://$domain"' not in cand:
            errs.append("per-domain delete misses the https://$domain variant")
        if '"http://$domain"' not in cand:
            errs.append("per-domain delete misses the http://$domain variant")

    # 4. refresh signal wired
    #    [deep-audit-1039 B-07] re-anchored: the literal `remember(cookieRefreshTick)`
    #    pinned the PRE-fix key list, which the B-07 fix must extend with the
    #    store's revision value. The decision is "the panel-local tick is still a
    #    remember key of the cookie-section derivations", judged via the same
    #    paren-matched key lists family 5 uses; the store-side half is B1/B2.
    if "cookieRefreshTick" not in code:
        errs.append("no cookieRefreshTick refresh signal")
    else:
        for decl_marker, label in (
            ("val hasCookies = remember", "hasCookies"),
            ("val allDomains = remember", "allDomains"),
        ):
            keys = _remember_key_list(code, decl_marker)
            if keys is None:
                errs.append("`%s` remember declaration not found (family 4)" % label)
            elif "cookieRefreshTick" not in keys:
                errs.append(
                    "`%s` remember keys %s lack cookieRefreshTick" % (label, keys)
                )
        bumps = len(_live_hits(code, r"cookieRefreshTick\+\+"))
        if bumps < 2:
            errs.append(f"cookieRefreshTick++ bumped {bumps} times on a reachable path, "
                        f"expected >= 2 (per-domain + clear-all)")

    # 4b. [wide3-06 F4] the per-domain delete must persist AND refresh itself.
    #     The global bump count above let a sibling site stand in for the
    #     per-domain one, a `/* … */` wrapper kept the count while the delete
    #     stopped persisting, and [batch5-t05] an `if (false)` wrapper did the
    #     same — the user deletes a domain, the list keeps showing it and the
    #     expired cookies are served again on the next load.
    deletes = _live_hits(code, r"\bsetCookie\s*\(")
    if not deletes:
        errs.append(
            "per-domain delete no longer expires cookies (no reachable setCookie( "
            "call) — deleting a domain cannot remove its cookies at all (F4)")
    for pos in deletes:
        block_start, block_end = _smallest_block_with(code, pos, ("candidateUrls",))
        if block_start < 0:
            errs.append(
                "the cookie delete no longer sits in a per-domain action block that "
                "enumerates candidateUrls (fail-closed: the delete site was found but "
                "its block cannot be delimited)")
            continue
        if not _live_hits(code, r"\bflush\s*\(\s*\)", block_start, block_end):
            errs.append(
                "the per-domain delete never flush()es inside its own action block on a "
                "reachable path (`if (false) cm.flush()` — the whole statically-dead "
                "family) — the expiries stay in memory and the next page load serves "
                "the deleted cookies again (F4)")
        if not _live_hits(code, r"cookieRefreshTick\+\+", block_start, block_end):
            errs.append(
                "the per-domain delete never bumps cookieRefreshTick inside its own action "
                "block on a reachable path — hasCookies/allDomains keep their pre-delete "
                "values, so the removed domain stays on the list (F4)")
        # ── [R3-t03 W84] 「调了 setCookie」不等于「Cookie 被删掉」──────────
        # 判据此前只数 setCookie( 的出现与周边 flush/tick。把
        # `"$it=; Max-Age=0; Path=/"` 改成 `"$it=1; Path=/"`（丢掉过期指令）
        # 后，调用、flush、tick 全在，UI 也会重组 —— 但 CookieManager 里那条
        # Cookie 从未过期，域名原封不动留在列表里。判据必须落到**字面量里的
        # 过期语义**：动作块内的每个 setCookie 都要带 Max-Age ≤ 0 或过去的
        # Expires。
        m2 = SET_COOKIE.match(code, pos)
        if m2 is None:
            continue
        value_arg = _cookie_value_arg(lit, m2.end() - 1)
        if value_arg is None:
            errs.append(
                "the per-domain `setCookie(` call has no value argument — it cannot "
                "carry an expiry instruction (fail-closed)")
        elif not _expiry_instruction(value_arg):
            errs.append(
                "the per-domain `setCookie(url, %s)` carries no expiry instruction "
                "(Max-Age ≤ 0 or a past Expires) — it writes/refreshes the cookie "
                "instead of deleting it, so the domain and its cookies stay in the "
                "list and are served again on the next load (W84)"
                % value_arg.strip()[:60])

    return errs


SET_COOKIE = re.compile(r"\bsetCookie\s*\(")


def _cookie_value_arg(view: str, open_paren: int):
    """`setCookie(` 的第 2 个实参（引号感知的顶层逗号切分），或 None。

    不能用 `call_args`：它在**注释视图**上按顶层逗号切，而 `Expires=Thu, 01
    Jan 1970` 里的逗号在字符串内部 —— 切完只剩 `Expires=Thu`，合法的过期
    写法会被误判成「没有过期指令」。
    """
    close = match_bracket(view, open_paren)
    if close < 0:
        return None
    text = view[open_paren + 1:close]
    parts, depth, quote, start, i = [], 0, None, 0, 0
    while i < len(text):
        c = text[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in "\"'":
            quote = c
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append(text[start:i])
            start = i + 1
        i += 1
    parts.append(text[start:])
    return parts[1].strip() if len(parts) > 1 else None


def _expiry_instruction(value: str) -> bool:
    """Cookie 串里是否带「让它过期」的指令。

    `Max-Age` 的数值 ≤ 0，或 `Expires=` 的年份 ≤ 1970（删除 cookie 的两种
    标准写法）。只认字面量：拼进变量的过期指令无法从树里判定 → 视为没有
    （fail-closed，让门红而不是静默放行）。
    """
    low = value.lower()
    m = re.search(r"max-age\s*=\s*([+-]?\d+)", low)
    if m:
        try:
            return int(m.group(1)) <= 0
        except ValueError:
            return False
    m = re.search(r"expires\s*=\s*([^;\"]+)", low)
    if m:
        ym = re.search(r"\b(19\d\d|20\d\d)\b", m.group(1))
        return bool(ym) and int(ym.group(1)) <= 1970
    return False


def selftest() -> int:
    # [deep-audit-1039 B-07] the compliant cookie fixture is the POST-fix shape:
    # the panel-local tick stays a key AND the derivations are keyed on a value
    # fed by historyStore.revision (subscription + key use).
    compliant = """
            val cookieManager = remember { CookieManager.getInstance() }
            val historyStore = remember { BrowserHistoryStore.getInstance(context) }
            val historyRevision by historyStore.revision.collectAsState()
            val hasCookies = remember(cookieRefreshTick, historyRevision) { cookieManager.hasCookies() }
            val allDomains = remember(hasCookies, cookieRefreshTick, historyRevision) {
                historyStore.uniqueDomains()
            }
            val cm = CookieManager.getInstance()
            cm.removeAllCookies {
                cm.flush()
                cookieRefreshTick++
            }
            showClearConfirm = false
            cookieRefreshTick++
    """
    good_delete = """
            IconButton(onClick = {
                val cm = CookieManager.getInstance()
                val candidateUrls = if (domain.startsWith("http")) {
                    listOf(domain)
                } else {
                    listOf("https://$domain", "http://$domain")
                }
                for (url in candidateUrls) { names.forEach { cm.setCookie(url, "$it=; Max-Age=0; Path=/") } }
                cm.flush()
                cookieRefreshTick++
            })
    """
    bad_nocallback = "CookieManager.getInstance().removeAllCookies(null)"
    bad_one_variant = """
            val url = if (domain.startsWith("http")) domain else "https://$domain"
            val names = cm.getCookie(url).orEmpty().split(';')
            names.forEach { cm.setCookie(url, "$it=; Max-Age=0; Path=/") }
            cm.flush()
    """
    bad_noflush = """
            cm.removeAllCookies { cookieRefreshTick++ }
    """
    bad_notick = """
            cm.removeAllCookies { cm.flush() }
            cm.flush()
    """

    # ── [da1029-E] viewport / session-override fixtures ──────────────────────
    good_vp = """
    val customWidthFromPool by tabPool.customViewportWidth.collectAsState()
    val customHeightFromPool by tabPool.customViewportHeight.collectAsState()
    val sessionWidthFromPool by tabPool.sessionViewportWidth.collectAsState()
    val sessionHeightFromPool by tabPool.sessionViewportHeight.collectAsState()
    val sessionViewportActive = sessionWidthFromPool > 0 && sessionHeightFromPool > 0
    val effectiveWidthFromPool =
        if (sessionViewportActive) sessionWidthFromPool else customWidthFromPool
    val effectiveHeightFromPool =
        if (sessionViewportActive) sessionHeightFromPool else customHeightFromPool
    val poolProfile by tabPool.currentUserAgentProfile.collectAsState()
    var selectedProfile by remember(poolProfile) {
        mutableStateOf(poolProfile)
    }
    var viewportWidthText by remember(effectiveWidthFromPool) {
        mutableStateOf(if (effectiveWidthFromPool > 0) effectiveWidthFromPool.toString() else "")
    }
    var viewportHeightText by remember(effectiveHeightFromPool) {
        mutableStateOf(if (effectiveHeightFromPool > 0) effectiveHeightFromPool.toString() else "")
    }
    var viewportMode by remember(isCustomViewportActive) { mutableStateOf(ViewportMode.DEFAULT) }
    var showCustomViewportEditor by remember(isCustomViewportActive) {
        mutableStateOf(isCustomViewportActive)
    }
    var cookieFilterText by remember { mutableStateOf("") }
    ViewportSection(
        onSelectDefault = {
            coroutineScope.launch {
                tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(0, 0)
            }
        },
        onSelectCustom = {
            coroutineScope.launch {
                tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(1280, 800)
            }
        },
        onApply = {
            coroutineScope.launch {
                tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(1440, 900)
            }
        },
        onPreset = { w, h ->
            coroutineScope.launch {
                tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(w, h)
            }
        },
    )
    """

    good_vp_commented = """
    // the pool resolves session > global > UA default; subscribe to both so an
    // agent set_viewport is visible here (see sessionViewportWidth.collectAsState)
    // and clear the override with resetSessionViewport() before setGlobalViewport(
    """ + good_vp

    vp_arms = [
        ("V0 compliant viewport wiring", good_vp_commented, []),
        ("V1 session height not subscribed",
         good_vp.replace("    val sessionHeightFromPool by tabPool.sessionViewportHeight.collectAsState()\n", ""),
         ["does not subscribe"]),
        ("V2 width keyed on the global pair",
         good_vp.replace("remember(effectiveWidthFromPool)", "remember(customWidthFromPool)"),
         ["none of which is derived"]),
        ("V2 height keyed on a nonce",
         good_vp.replace("remember(effectiveHeightFromPool)", "remember(vpNonce)") + "\n    val vpNonce = 0\n",
         ["none of which is derived"]),
        ("V3 editor un-keyed",
         good_vp.replace("var showCustomViewportEditor by remember(isCustomViewportActive) {",
                         "var showCustomViewportEditor by remember {"),
         ["is un-keyed"]),
        ("V4 action without the reset",
         good_vp.replace("""        onApply = {
            coroutineScope.launch {
                tabPool.resetSessionViewport()
""", """        onApply = {
            coroutineScope.launch {
"""),
         ["never calls `resetSessionViewport()`"]),
        ("V4 reset after the global write",
         good_vp.replace("""            coroutineScope.launch {
                tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(w, h)""",
                         """            coroutineScope.launch {
                tabPool.setGlobalViewport(w, h)
                tabPool.resetSessionViewport()"""),
         ["before clearing"]),
        ("V4 reset wrapped in a statically-dead if",
         good_vp.replace("""            coroutineScope.launch {
                tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(w, h)""",
                         """            coroutineScope.launch {
                if (false) tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(w, h)"""),
         ["never calls `resetSessionViewport()`"]),
        # [batch5-t05 G07-A2] same family, different spelling: the old whitelist
        # only folded the literal `false`, so `!true` walked straight through.
        ("V4 reset wrapped in a statically-dead if (`!true`)",
         good_vp.replace("""            coroutineScope.launch {
                tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(w, h)""",
                         """            coroutineScope.launch {
                if (!true) tabPool.resetSessionViewport()
                tabPool.setGlobalViewport(w, h)"""),
         ["never calls `resetSessionViewport()`"]),
        # [batch5-t05 G07-A1] the branch that supplies the session value is the
        # decision V2 anchors on; a constant-false conjunction keeps every token
        # and deletes the branch.
        ("V2 session branch dead-prefixed by `false &&`",
         good_vp.replace(
             "if (sessionViewportActive) sessionWidthFromPool else customWidthFromPool",
             "if (false && sessionViewportActive) sessionWidthFromPool else customWidthFromPool"),
         ["none of which is derived"]),
        # [batch5-t05] same family, arithmetic spelling this time.
        ("V2 session branch dead-prefixed by `1 == 2 &&`",
         good_vp.replace(
             "if (sessionViewportActive) sessionHeightFromPool else customHeightFromPool",
             "if ((1 == 2) && sessionViewportActive) sessionHeightFromPool else customHeightFromPool"),
         ["none of which is derived"]),
        ("V5 filter box keyed",
         good_vp.replace("var cookieFilterText by remember {",
                         "var cookieFilterText by remember(cookieRefreshTick) {"),
         ["gained a remember key"]),
        ("V6 UA radio un-keyed (prefs snapshot)",
         good_vp.replace("var selectedProfile by remember(poolProfile) {",
                         "var selectedProfile by remember {"),
         ["no key list at all"]),
        ("V6 UA radio keyed on a nonce",
         good_vp.replace("var selectedProfile by remember(poolProfile) {",
                         "var selectedProfile by remember(vpNonce) {"),
         ["none of which is fed by"]),
        ("V6 UA pool flow not subscribed",
         good_vp.replace("    val poolProfile by tabPool.currentUserAgentProfile.collectAsState()\n", ""),
         ["does not subscribe to `currentUserAgentProfile`"]),
    ]

    # ── [dv29-B4 B-04] V9 fixtures: dropping the session viewport must reach
    #    the tabs. The compliant slice is the POST-fix shape; each dirty arm
    #    changes exactly one decision, so a breaker cannot be satisfied by
    #    another criterion. ──
    good_reset = """
    fun setSession(sessionId: String) {
        this.sessionId = sessionId
        loadSavedState()
    }

    fun bindSessionContextOnly(sessionId: String) {
        this.sessionId = sessionId
        if (decision.resetViewport) {
            clearSessionViewportOverride()
        }
    }

    private fun clearSessionViewportOverride() {
        _sessionViewportWidth.value = 0
        _sessionViewportHeight.value = 0
        replayViewportToLiveTabs()
    }

    private fun replayViewportToLiveTabs() {
        if (isDisposed) return
        if (_tabs.value.isEmpty()) return
        evictionScope.launch { applyViewportToAllTabs() }
    }

    private suspend fun applyViewportToAllTabs() {
        val (w, h) = resolvedViewportSize()
    }

    suspend fun setSessionViewport(width: Int, height: Int) {
        _sessionViewportWidth.value = width.coerceAtLeast(0)
        _sessionViewportHeight.value = height.coerceAtLeast(0)
        applyViewportToAllTabs()
    }

    suspend fun resetSessionViewport() {
        _sessionViewportWidth.value = 0
        _sessionViewportHeight.value = 0
        applyViewportToAllTabs()
    }

    private fun loadSavedState() {
        if (!file.exists()) {
            clearSessionViewportOverride()
            return
        }
        val restoredViewport = BrowserSessionBinding.viewportForLoadedSession(w, h)
        _sessionViewportWidth.value = restoredViewport.width
        _sessionViewportHeight.value = restoredViewport.height
        replayViewportToLiveTabs()
    }
    """
    reset_arms = [
        ("V9 compliant reset wiring", good_reset, []),
        ("V9 collection point zeroes without replaying",
         good_reset.replace("""        _sessionViewportHeight.value = 0
        replayViewportToLiveTabs()
""", """        _sessionViewportHeight.value = 0
"""),
         ["zeroes the flows without replaying"]),
        ("V9 fourth path zeroes the flows itself",
         good_reset.replace("""    fun bindSessionContextOnly(sessionId: String) {""",
                            """    fun forgetViewport() {
        _sessionViewportWidth.value = 0
    }

    fun bindSessionContextOnly(sessionId: String) {"""),
         ["expected exactly one non-suspend member"]),
        ("V9 binding stops clearing the override",
         good_reset.replace("""        if (decision.resetViewport) {
            clearSessionViewportOverride()
        }
""", """        if (decision.resetViewport) {
        }
"""),
         ["re-points the pool without reaching the viewport clear"]),
        ("V9 restore stops replaying",
         good_reset.replace("""        _sessionViewportHeight.value = restoredViewport.height
        replayViewportToLiveTabs()
""", """        _sessionViewportHeight.value = restoredViewport.height
"""),
         ["writes a session viewport but never replays"]),
        ("V9 restore gates the replay on hasOverride",
         good_reset.replace("""        _sessionViewportHeight.value = restoredViewport.height
        replayViewportToLiveTabs()
""", """        _sessionViewportHeight.value = restoredViewport.height
        if (restoredViewport.hasOverride) {
            replayViewportToLiveTabs()
        }
"""),
         ["conditions the session viewport on `hasOverride`"]),
        ("V9 the replay is gone entirely",
         good_reset.replace("applyViewportToAllTabs(", "applyViewportNowhere("),
         ["no member calls `applyViewportToAllTabs(`"]),
        # [batch5-t05 G07-A4] the same `if (false)` family, one member over: V4
        # already judged the sheet's reset call on reachability, but V9's replay
        # probe was a bare substring, so wrapping the replay kept every token.
        ("V9 the collection point's replay is dead-wrapped",
         good_reset.replace("""        _sessionViewportHeight.value = 0
        replayViewportToLiveTabs()
    }

    private fun replayViewportToLiveTabs() {""",
                            """        _sessionViewportHeight.value = 0
        if (false) replayViewportToLiveTabs()
    }

    private fun replayViewportToLiveTabs() {"""),
         ["without replaying"]),
    ]

    # ── [da1030-B] pool-side fixtures: set_user_agent must be pool-level (V7) ──
    good_pool = """
        val result = when (input.action) {
            BrowserAction.NEW_TAB -> newTab(input.url)
            BrowserAction.SET_VIEWPORT -> handleSetViewport(input)
            BrowserAction.SET_USER_AGENT -> handleSetUserAgent(input)
            else -> {
                val serialTabId = input.tabId
                if (serialTabId != null) executeSerialized(serialTabId, input) else execute(input)
            }
        }
    }

    private suspend fun handleSetUserAgent(input: BrowserActionInput): BrowserActionResult = withContext(Dispatchers.Main) {
        val newProfile = input.userAgent
            ?: return@withContext BrowserActionResult.error("set_user_agent requires --user-agent")
        val customUA = if (newProfile == UserAgentProfile.CUSTOM) customUserAgentString else null
        userAgentProfile = newProfile
        for (tab in _tabs.value) {
            tab.manager.setUserAgent(newProfile, customUA)
        }
        applyViewportToAllTabs()
        val (w, h) = resolvedViewportSize()
        BrowserActionResult(text = "Switched to ${newProfile.value} (${w}x$h)")
    }
    """
    pool_arms = [
        ("V7 compliant pool routing", good_pool, []),
        ("V7 branch missing (falls into the per-tab else)",
         good_pool.replace("            BrowserAction.SET_USER_AGENT -> handleSetUserAgent(input)\n", ""),
         ["no explicit `BrowserAction.SET_USER_AGENT ->` branch"]),
        ("V7 handler skips the viewport replay",
         good_pool.replace("        applyViewportToAllTabs()\n", ""),
         ["never calls `applyViewportToAllTabs()`"]),
        ("V7 dispatch block gone",
         good_pool.replace("when (input.action)", "when (input.mode)"),
         ["no `when (input.action)` block"]),
    ]

    # ── [dv23-B4 B-05] V8 arms: the stored custom UA has ONE source ───────
    # Each dirty fixture differs from the compliant shape by exactly one
    # decision, so a breaker cannot be satisfied by another criterion.
    good_ua_pool = """
        fun setUserAgentFromUI(profile: UserAgentProfile, customUA: String? = null) {
            userAgentProfile = profile
            if (customUA != null) {
                customUserAgentString = customUA.ifEmpty { null }
            }
            val effectiveCustomUA =
                if (profile == UserAgentProfile.CUSTOM) storedCustomUserAgent() else null
            for (tab in _tabs.value) {
                tab.manager.setUserAgent(profile, effectiveCustomUA)
            }
        }

        private fun storedCustomUserAgent(): String? =
            customUserAgentString?.ifEmpty { null }
                ?: context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                    .getString("custom_user_agent", null)?.ifEmpty { null }

        private suspend fun handleSetUserAgent(input: BrowserActionInput): BrowserActionResult = withContext(Dispatchers.Main) {
            val newProfile = input.userAgent
                ?: return@withContext BrowserActionResult.error("set_user_agent requires --user-agent")
            val customUA = if (newProfile == UserAgentProfile.CUSTOM) storedCustomUserAgent() else null
            userAgentProfile = newProfile
        }
    """
    good_ua_sheet = """
            OutlinedTextField(
                value = customUA,
                onValueChange = {
                    customUA = it
                    prefs.edit().putString("custom_user_agent", it).apply()
                    tabPool.stageCustomUserAgent(it)
                },
            )
    """
    ua_arms = [
        ("V8 compliant single source", good_ua_pool, good_ua_sheet, []),
        ("V8 preset tap clears the stored UA",
         good_ua_pool.replace(
             "            if (customUA != null) {\n"
             "                customUserAgentString = customUA.ifEmpty { null }\n"
             "            }\n",
             "            customUserAgentString = customUA\n"),
         good_ua_sheet,
         ["assigns the stored custom UA unconditionally"]),
        ("V8 no single-source resolution",
         good_ua_pool.replace("storedCustomUserAgent()", "customUserAgentString"),
         good_ua_sheet,
         ["no single-source resolution"]),
        ("V8 handler reads the private cache",
         good_ua_pool.replace(
             "val customUA = if (newProfile == UserAgentProfile.CUSTOM) storedCustomUserAgent() else null",
             "val customUA = if (newProfile == UserAgentProfile.CUSTOM) customUserAgentString else null"),
         good_ua_sheet,
         ["does not resolve CUSTOM through the single source"]),
        ("V8 sheet does not feed the pool's copy",
         good_ua_pool,
         good_ua_sheet.replace("                    tabPool.stageCustomUserAgent(it)\n", ""),
         ["does not feed the pool's copy"]),
        # [batch5-t05 G07-A3] the guarded write is untouched; the defect lives in
        # the complementary branch, which the one-hit `find()` never looked at.
        ("V8 complementary else branch wipes the stored UA",
         good_ua_pool.replace(
             "            if (customUA != null) {\n"
             "                customUserAgentString = customUA.ifEmpty { null }\n"
             "            }\n",
             "            if (customUA != null) {\n"
             "                customUserAgentString = customUA.ifEmpty { null }\n"
             "            } else {\n"
             "                customUserAgentString = null\n"
             "            }\n"),
         good_ua_sheet,
         ["OUTSIDE the"]),
        ("V8 post-block write wipes the UA the guard just stored",
         good_ua_pool.replace(
             "            if (customUA != null) {\n"
             "                customUserAgentString = customUA.ifEmpty { null }\n"
             "            }\n",
             "            if (customUA != null) {\n"
             "                customUserAgentString = customUA.ifEmpty { null }\n"
             "            }\n"
             "            settings.userAgentString = \"\"\n"),
         good_ua_sheet,
         ["OUTSIDE the"]),
        ("V8 guarded write stores a value the caller did not supply",
         good_ua_pool.replace(
             "                customUserAgentString = customUA.ifEmpty { null }\n",
             "                customUserAgentString = null\n"),
         good_ua_sheet,
         ["never supplied"]),
        ("V8 guard reduced to a no-op statement beside an unconditional write",
         good_ua_pool.replace(
             "            if (customUA != null) {\n"
             "                customUserAgentString = customUA.ifEmpty { null }\n"
             "            }\n",
             "            if (customUA != null) Unit\n"
             "            customUserAgentString = customUA?.ifEmpty { null }\n"),
         good_ua_sheet,
         ["OUTSIDE the"]),
    ]
    # the comment trap: a KDoc mentioning removeAllCookies(null) must NOT trip
    good_with_comment = """
            // [fix/da1022-G] removeAllCookies(null) left no flush and no refresh:
            // use the callback form to persist + bump the tick.
    """ + compliant + good_delete

    # [wide3-06 F4] one LIVE bump (clear-all) + one bump hidden in a block
    # comment: the pre-fix masker counted both and reported 2 bumps.
    block_commented_delete = """
            IconButton(onClick = {
                val cm = CookieManager.getInstance()
                val candidateUrls = if (domain.startsWith("http")) {
                    listOf(domain)
                } else {
                    listOf("https://$domain", "http://$domain")
                }
                for (url in candidateUrls) { names.forEach { cm.setCookie(url, "$it=; Max-Age=0; Path=/") } }
                /* cm.flush()
                cookieRefreshTick++ */
            })
            val hasCookies = remember(cookieRefreshTick, historyRevision) { cm.hasCookies() }
            val allDomains = remember(hasCookies, cookieRefreshTick, historyRevision) {
                uniqueDomains()
            }
            cm.removeAllCookies {
                cm.flush()
                cookieRefreshTick++
            }
    """

    # [batch5-t05 G07-A5] the per-domain flush + bump survive every substring
    # test inside `if (false) { … }` — the delete neither persists nor resets
    # the list, which is the pre-fix defect with a five-character prefix.
    dead_wrapped_delete = compliant + good_delete.replace(
        """                cm.flush()
                cookieRefreshTick++
            })""",
        """                if (false) {
                    cm.flush()
                    cookieRefreshTick++
                }
            })""")

    # ── [deep-audit-1039 B-07] domain-refresh (B1–B2) fixtures ───────────────
    # Only-red-its-own-criterion: each dirty fixture below differs from the
    # compliant shape by exactly one decision.
    good_domain = """
            val cookieManager = remember { CookieManager.getInstance() }
            val historyStore = remember { BrowserHistoryStore.getInstance(context) }
            val historyRevision by historyStore.revision.collectAsState()
            val hasCookies = remember(cookieRefreshTick, historyRevision) { cookieManager.hasCookies() }
            val allDomains = remember(hasCookies, cookieRefreshTick, historyRevision) {
                historyStore.uniqueDomains()
            }
    """
    domain_arms = [
        ("B1+B2 compliant (post-fix shape)", good_domain, []),
        ("B1 no revision subscription (pre-fix shape)",
         good_domain.replace("            val historyRevision by historyStore.revision.collectAsState()\n", ""),
         ["does not subscribe to `historyStore.revision`"]),
        ("precision: the receiver spelling of the subscription is not pinned",
         good_domain.replace("historyStore.revision.collectAsState()",
                             "store.revision.collectAsState()"),
         []),
        ("B2 allDomains not keyed on the revision-backed value",
         good_domain.replace("remember(hasCookies, cookieRefreshTick, historyRevision)",
                             "remember(hasCookies, cookieRefreshTick)"),
         ["none of which is fed by"]),
        ("B2 allDomains keyed on a nonce instead",
         good_domain.replace("remember(hasCookies, cookieRefreshTick, historyRevision)",
                             "remember(hasCookies, cookieRefreshTick, domainNonce)"),
         ["none of which is fed by"]),
        ("B2 hasCookies keeps the stale key list",
         good_domain.replace("val hasCookies = remember(cookieRefreshTick, historyRevision)",
                             "val hasCookies = remember(cookieRefreshTick)"),
         ["none of which is fed by"]),
        ("family-4 precision: tick dropped from allDomains",
         good_domain.replace("remember(hasCookies, cookieRefreshTick, historyRevision)",
                             "remember(hasCookies, historyRevision)"),
         ["dropped `cookieRefreshTick`"]),
        ("B1 precision: a comment mentioning revision is not a subscription",
         good_domain.replace("            val historyRevision by historyStore.revision.collectAsState()\n",
                             "            // val historyRevision by historyStore.revision.collectAsState()\n"
                             "            val historyRevision = 0\n"),
         ["does not subscribe to `historyStore.revision`"]),
    ]

    # ── [R3-t03 W82] tab creation must inherit the pool-level UA ───────────
    good_tab_ua = """
    private fun createTab(tabs: MutableList<Tab>, url: String? = null): Tab? {
        val webView = WebView(context)
        val manager = BrowserUseManager(webView, userAgentProfile)
        manager.minisSessionId = sessionId
        if (userAgentProfile == UserAgentProfile.CUSTOM && !customUserAgentString.isNullOrEmpty()) {
            manager.setUserAgent(userAgentProfile, customUserAgentString)
        }
        val (vpW, vpH) = resolvedViewportSize()
        manager.applyViewport(vpW, vpH)
        val tab = Tab(id = nextTabId++, manager = manager)
        tabs.add(tab)
        return tab
    }
    """
    tab_ua_arms = [
        ("W82 compliant tab UA inheritance", good_tab_ua, []),
        # 见证原文：池级 profile 与自定义串双双写死
        ("W82 witness: hardcoded profile + null custom UA",
         good_tab_ua.replace("manager.setUserAgent(userAgentProfile, customUserAgentString)",
                             "manager.setUserAgent(UserAgentProfile.MOBILE_CHROME, null)"),
         ["does not derive from the pool's own profile",
          "passes a constant as the custom UA"]),
        ("W82 profile argument is a hardcoded preset",
         good_tab_ua.replace("manager.setUserAgent(userAgentProfile, customUserAgentString)",
                             "manager.setUserAgent(UserAgentProfile.DESKTOP_CHROME, customUserAgentString)"),
         ["does not derive from the pool's own profile"]),
        ("W82 custom UA argument is a constant",
         good_tab_ua.replace("manager.setUserAgent(userAgentProfile, customUserAgentString)",
                             "manager.setUserAgent(userAgentProfile, \"\")"),
         ["passes a constant as the custom UA"]),
        ("W82 the injection is gone entirely",
         good_tab_ua.replace(
             "        if (userAgentProfile == UserAgentProfile.CUSTOM && !customUserAgentString.isNullOrEmpty()) {\n"
             "            manager.setUserAgent(userAgentProfile, customUserAgentString)\n"
             "        }\n", ""),
         ["never given the pool's User-Agent"]),
        ("W82 the injection is dead-wrapped",
         good_tab_ua.replace(
             "        if (userAgentProfile == UserAgentProfile.CUSTOM",
             "        if (false) if (userAgentProfile == UserAgentProfile.CUSTOM"),
         ["statically-dead path"]),
        # clean：一跳局部别名是正当重构，判据要跟到别名的那一跳
        ("clean: pool UA reaches the manager through a local alias",
         good_tab_ua.replace(
             "        val manager = BrowserUseManager(webView, userAgentProfile)",
             "        val manager = BrowserUseManager(webView, userAgentProfile)\n"
             "        val profileForTab = userAgentProfile\n"
             "        val customForTab = customUserAgentString").replace(
             "manager.setUserAgent(userAgentProfile, customUserAgentString)",
             "manager.setUserAgent(profileForTab, customForTab)"),
         []),
    ]

    # ── [R3-t03 W83] the replay must actually drive every live tab ─────────
    good_replay = """
    private suspend fun applyViewportToAllTabs() {
        val (w, h) = resolvedViewportSize()
        withContext(Dispatchers.Main) {
            for (tab in _tabs.value) {
                tab.manager.applyViewport(w, h)
                tab.manager.reloadAndWait()
            }
        }
    }
    """
    replay_arms = [
        ("W83 compliant replay drives every tab", good_replay, []),
        # 见证原文：循环留下、效果删掉
        ("W83 witness: loop body emptied",
         good_replay.replace("""            for (tab in _tabs.value) {
                tab.manager.applyViewport(w, h)
                tab.manager.reloadAndWait()
            }
""", """            for (tab in _tabs.value) {
                // emptied by the witness
            }
"""),
         ["never calls `tab.….applyViewport(`", "never reloads `tab`"]),
        ("W83 apply without the reload",
         good_replay.replace("                tab.manager.reloadAndWait()\n", ""),
         ["never reloads `tab`"]),
        ("W83 reload without the apply",
         good_replay.replace("                tab.manager.applyViewport(w, h)\n", ""),
         ["never calls `tab.….applyViewport(`"]),
        ("W83 the loop is gone",
         good_replay.replace("""            for (tab in _tabs.value) {
                tab.manager.applyViewport(w, h)
                tab.manager.reloadAndWait()
            }
""", ""), ["no longer iterates the live tabs"]),
        ("W83 the apply is dead-wrapped",
         good_replay.replace("                tab.manager.applyViewport(w, h)",
                             "                if (false) tab.manager.applyViewport(w, h)"),
         ["never calls `tab.….applyViewport(`"]),
        # clean：循环里先 apply 再 await 的等价写法（用局部 manager）
        ("clean: per-tab manager bound to a local", good_replay.replace(
            "                tab.manager.applyViewport(w, h)\n"
            "                tab.manager.reloadAndWait()",
            "                val manager = tab.manager\n"
            "                manager.applyViewport(w, h)\n"
            "                manager.reloadAndWait()"), []),
    ]

    arms = [
        ("compliant", good_with_comment, []),
        ("V1 callback-less removeAllCookies(null)", bad_nocallback, 1),
        ("V2 single scheme variant", bad_one_variant, 1),
        ("V3 no flush in callback", bad_noflush, 1),
        ("V4 no refresh tick", bad_notick, 1),
        # [wide3-06 F4] the per-domain flush + bump sit inside a `/* … */`
        # block: the line-only masker still counted them, so `count`
        # (`bumps >= 2`) stayed satisfied while the delete stopped persisting.
        ("F4 per-domain flush+bump hidden in a block comment",
         block_commented_delete, 1),
        # [batch5-t05 G07-A5] statically-dead wrapper, same outcome.
        ("F4 per-domain flush+bump dead-wrapped by `if (false)`",
         good_with_comment.replace(good_delete, dead_wrapped_delete[len(compliant):]), 1),
        # [R3-t03 W84] 调用在、flush 在、tick 在，但过期指令没了 ——
        # CookieManager 里那条 Cookie 从未失效，域名原封不动留在列表里。
        ("W84 per-domain delete loses the expiry instruction",
         good_with_comment.replace('"$it=; Max-Age=0; Path=/"', '"$it=1; Path=/"'), 1),
        ("W84 per-domain delete only refreshes the value",
         good_with_comment.replace('"$it=; Max-Age=0; Path=/"',
                                   '"$it=; Max-Age=3600; Path=/"'), 1),
        # clean：删除 cookie 的另一种标准写法（过去的 Expires）必须仍绿
        ("clean: expiry spelled as a past Expires",
         good_with_comment.replace('"$it=; Max-Age=0; Path=/"',
                                   '"$it=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"'),
         []),
    ]
    failures = 0
    arms_run = 0
    for name, sample, expect in arms:
        arms_run += 1
        errs = check(sample)
        if expect == [] and errs:
            print(f"  FAIL {name}: expected green, got {errs}")
            failures += 1
        elif expect != [] and not errs:
            print(f"  FAIL {name}: expected red, got green")
            failures += 1
        else:
            print(f"  OK {name}: {errs if errs else 'green'}")

    for name, sample, needles in vp_arms:
        arms_run += 1
        errs = check_viewport(sample)
        if not needles:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
            continue
        hit = [n for n in needles if not any(n in e for e in errs)]
        if hit or not errs:
            print(f"  FAIL {name}: expected {needles}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")
    for name, sample, needles in pool_arms:
        arms_run += 1
        errs = check_pool_ua(sample)
        if not needles:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
            continue
        hit = [n for n in needles if not any(n in e for e in errs)]
        if hit or not errs:
            print(f"  FAIL {name}: expected {needles}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")
    for name, pool_sample, sheet_sample, needles in ua_arms:
        arms_run += 1
        errs = check_custom_ua_single_source(pool_sample, sheet_sample)
        if not needles:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
            continue
        hit = [n for n in needles if not any(n in e for e in errs)]
        if hit or not errs:
            print(f"  FAIL {name}: expected {needles}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")
    for name, sample, needles in reset_arms:
        arms_run += 1
        errs = check_pool_viewport_reset(sample)
        if not needles:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
            continue
        hit = [n for n in needles if not any(n in e for e in errs)]
        if hit or not errs:
            print(f"  FAIL {name}: expected {needles}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")
    for name, sample, needles in domain_arms:
        arms_run += 1
        errs = check_domain_refresh(sample)
        if not needles:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
            continue
        hit = [n for n in needles if not any(n in e for e in errs)]
        if hit or not errs:
            print(f"  FAIL {name}: expected {needles}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")
    # ── [R3-t03] 第三轮两条 dual-gate 缝 ───────────────────────────────────
    for name, sample, needles in tab_ua_arms:
        arms_run += 1
        errs = check_tab_ua_injection(sample)
        if not needles:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
            continue
        hit = [n for n in needles if not any(n in e for e in errs)]
        if hit or not errs:
            print(f"  FAIL {name}: expected {needles}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")
    for name, sample, needles in replay_arms:
        arms_run += 1
        errs = check_replay_effect(sample)
        if not needles:
            if errs:
                print(f"  FAIL {name}: expected green, got {errs}")
                failures += 1
            else:
                print(f"  OK {name}: green")
            continue
        hit = [n for n in needles if not any(n in e for e in errs)]
        if hit or not errs:
            print(f"  FAIL {name}: expected {needles}, got {errs}")
            failures += 1
        else:
            print(f"  OK {name}: {errs}")
    if failures:
        print(f"SELFTEST FAILED ({failures} arms)")
        return 1
    print(f"{MARKER} ({arms_run} arms)")
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
    import os

    def read_target(rel):
        path = os.path.join(root, rel)
        try:
            return open(path).read()
        except OSError as e:
            print(f"cannot read {path}: {e}", file=sys.stderr)
            return None

    src = read_target(TARGET)
    if src is None:
        return 2
    pool_src = read_target(POOL_TARGET)
    if pool_src is None:
        return 2
    errs = check(src)
    vp_errs = check_viewport(src)
    pool_errs = check_pool_ua(pool_src)
    domain_errs = check_domain_refresh(src)
    reset_errs = check_pool_viewport_reset(pool_src)
    # [R3-t03 W82] tab creation must inherit the pool-level UA.
    tab_ua_errs = check_tab_ua_injection(pool_src)
    # [R3-t03 W83] the replay must actually drive every live tab.
    replay_errs = check_replay_effect(pool_src)
    # [dv23-B4 B-05] V8 — the stored custom UA has one source (pool + sheet).
    ua_errs = check_custom_ua_single_source(pool_src, src)
    if (errs or vp_errs or pool_errs or domain_errs or ua_errs or reset_errs
            or tab_ua_errs or replay_errs):
        print("browser_cookie_ui_refresh_check RED on %s (+ %s):" % (TARGET, POOL_TARGET))
        for e in errs:
            print(f"  - {e}")
        for e in vp_errs:
            print(f"  - [viewport] {e}")
        for e in pool_errs:
            print(f"  - [pool] {e}")
        for e in domain_errs:
            print(f"  - [domain-refresh] {e}")
        for e in ua_errs:
            print(f"  - [custom-ua] {e}")
        for e in reset_errs:
            print(f"  - [pool-viewport-reset] {e}")
        for e in tab_ua_errs:
            print(f"  - [tab-ua] {e}")
        for e in replay_errs:
            print(f"  - [replay-effect] {e}")
        return 1
    print(f"browser_cookie_ui_refresh_check OK ({TARGET}; cookie refresh + "
          f"domain-refresh/store-revision + viewport/session-override + UA-pool "
          f"wiring + custom-UA single source + pool session-viewport reset + tab-UA "
          f"inheritance + viewport-replay effect + per-domain cookie expiry; "
          f"{POOL_TARGET} checked too)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
