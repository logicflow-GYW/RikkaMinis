#!/usr/bin/env python3
"""Guard: MediaPlayerManager.releaseSession must release on EVERY path, and
the session-map mutations must not race the terminalize callbacks.

Why this exists (da1022-C): releaseSession wrapped stop() + release() in one
try. stop() throws IllegalStateException once the player has left the
playable states — which is exactly the state the onError terminalize runs
in — so the throw skipped release() and every error path leaked the native
MediaPlayer (codec + AudioTrack handles) until GC. Same-family fix already
existed in InlineMediaPlayer.onDispose (two separate try blocks). On top of
that, play() released the previous session OUTSIDE the lock the callbacks
take (double-release race) and stop() did release-then-remove unlocked (a
concurrent play() re-registering the same id could have its fresh session
removed while its player is still live = unreferenced leak).

Arms (judged on comment/string-stripped code; anchors are code shapes):
  1. releaseSession: `session.player.stop()` and `session.player.release()`
     sit in DIFFERENT try blocks (both guarded, never the same one).
  2. Session.state is `@Volatile` (cross-thread state writes from the
     binder/playback callbacks otherwise have no happens-before edge).
  3. play(): the pre-release `sessions[sessionId]?.let { ... releaseSession
     ... }` sits INSIDE a synchronized(lock) block.
  4. stop(): `sessions.remove(sessionId)` sits INSIDE a synchronized(lock)
     block (atomic with the release against a racing play()).
  5. (da1027-F) `ui/media/InlineMediaPlayer.kt`, when that file is present:
     every `AndroidView(...)` call must carry an `onRelease` that calls
     `stopPlayback()` (5a), the block that hoists the view into a state
     (`videoView = this`) must also clear that reference in its `onRelease`
     (5b), and every dismissal call site `onDismiss()` must sit in a block
     that calls `stopPlayback()` -- i.e. dismissing is a terminating path,
     not a bare `pause()` (5c). Anchors are fail-closed: 0 AndroidView
     blocks / 0 onDismiss() sites => RED. A mini-tree fixture that lacks the
     file is skipped (drift detector, not a target).
  6. (da1033-B, same file) The audio transport calls must not be able to
     throw out of a click callback, and the share path must not fabricate a
     cross-process `file://` URI:
     6a. every `mediaPlayer.seekTo(` / `mediaPlayer.pause(` / `mediaPlayer.start(`
     sits inside a `try {` block -- the native player can already be in the
     Error state while the frame still renders Stop (the isPlaying/error
     writes land through the main-thread callback queue, so the click can be
     handled first), and an escaping IllegalStateException crashes the app.
     6b. `shareMediaFile`: no `Uri.fromFile(` anywhere in the function body
     (a `file://` URI in a cross-process ACTION_SEND raises
     FileUriExposedException), the failure path logs AND tells the user via a
     Toast reusing an existing `*share_failed_toast` string, and that failure
     branch `return`s instead of letting the unresolved uri reach
     `Intent.putExtra(EXTRA_STREAM, ...)`. `Uri.fromFile` in the in-process
     VideoView paths is legitimate and must NOT be flagged -- the criterion is
     scoped to the function body, not the file.
     Anchors are fail-closed: 0 transport call sites / no `shareMediaFile`
     => RED. `onDispose`'s stop()/release() are covered by arm 1's
     two-separate-try-blocks rule and are deliberately not re-checked here.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

TARGET_REL = "src/android/app/src/main/java/com/rikkaminis/app/offload/MediaPlayerManager.kt"

INLINE_PLAYER_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/media/InlineMediaPlayer.kt"
)

MARKER = "MEDIAPLAYER_RELEASE_SELFTEST=PASS"


def strip_comments_and_strings(text: str) -> str:
    """Blank out // comments, /* */ blocks and string literals (brace-safe)."""
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
                state = None; out.append(" "); i += 1; continue
            out.append(" "); i += 1; continue
    return "".join(out)


def brace_span(text: str, open_idx: int) -> tuple:
    """(start, end_exclusive) of the block opened at open_idx."""
    depth, i, n = 1, open_idx + 1, len(text)
    while i < n and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return open_idx, i


def matching_paren(text: str, open_idx: int) -> int:
    """Index of the `)` closing text[open_idx], or -1."""
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1

def function_body(text: str, decl_pattern: str):
    """Stripped body (incl. braces) of the first function matching the
    declaration pattern, or '' when not found.

    The body starts AFTER the parameter list, so it is located by first finding
    the `)` that closes the declaration's `(` and only then looking for the `{`.
    Taking the first `{` after the name (the pre-wide3 shape) put the scan inside
    a DEFAULT-VALUE lambda: a wide3 witness added
    `guardStub: () -> Unit = { … }` to `shareMediaFile`'s parameter list and the
    gate then inspected that lambda while the real body — the `Uri.fromFile(file)`
    and the `try` it was meant to judge — fell outside the scan.
    """
    m = re.search(decl_pattern, text)
    if not m:
        return ""
    lp = text.find("(", m.start())
    if lp < 0:
        return ""
    rp = matching_paren(text, lp)
    if rp < 0:
        return ""
    brace = text.find("{", rp + 1)
    if brace < 0:
        return ""
    start, end = brace_span(text, brace)
    return text[start:end]


def try_blocks(body: str):
    """All (start, end) spans of `try {` blocks inside body."""
    spans = []
    for m in re.finditer(r"\btry\s*\{", body):
        spans.append(brace_span(body, body.find("{", m.start())))
    return spans


def enclosing_try(body: str, idx: int):
    """Span of the smallest try block containing idx, or None."""
    best = None
    for s, e in try_blocks(body):
        if s < idx < e and (best is None or (e - s) < (best[1] - best[0])):
            best = (s, e)
    return best


def synchronized_lock_spans(body: str):
    """All (start, end) spans of `synchronized(lock) { ... }` in body."""
    spans = []
    for m in re.finditer(r"synchronized\s*\(\s*lock\s*\)\s*\{", body):
        spans.append(brace_span(body, body.find("{", m.start())))
    return spans


def inside_any(spans, idx: int) -> bool:
    return any(s < idx < e for s, e in spans)


# ── [R3-t04] 第三轮（dual-gate bypass）缝判据的公用原语 ──────────────────────
# 三条见证都是「中间那一步没人判」：
#   11-1 FileProvider uri 解析出来了，但没挂到 Intent 上（分享面板照开、
#        既有 flag/判定/反馈判据全绿，接收方拿到空 ACTION_SEND）。
#   11-2 rememberMdMediaFile 解析出来的 file 被丢掉，Coil 拿裸 url 去取
#        （缝还挂着、request 还 build，只有数据源换了）。
#   11-3 pause() 把 state 设成它刚要求过的那个成员（幂等空转）：暂停后 UI
#        仍显示播放中、resume 拒绝、播放器保持暂停。
def _enclosing_fun_span(text: str, idx: int):
    """(start, end) of the innermost `fun …` block containing idx, or None."""
    best = None
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?([A-Za-z_]\w*)\s*\(", text):
        if m.start() > idx:
            break
        rp = matching_paren(text, m.end() - 1)
        if rp < 0:
            continue
        brace = text.find("{", rp + 1)
        if brace < 0:
            continue
        s, e = brace_span(text, brace)
        if s <= idx < e:
            best = (s, e)
    return best


def _bound_name(body: str, idx: int):
    """`val X = <含 idx 的表达式>` 里的 X；内联形态返回 None。

    只要求「最后一条声明与 idx 之间的换行都是续行」——`val uri = try {` 这种
    值表达式带块、调用在块内的形态也要认得出来。
    """
    decls = [(m.start(), m.group(1)) for m in
             re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=(?!=)", body)
             if m.start() < idx]
    if not decls:
        return None
    start, name = decls[-1]
    seg = body[start:idx]
    newlines = [m.start() for m in re.finditer(r"\n", seg)]
    for p in newlines[:-1]:
        if not re.search(r"(=|->|\btry|\belse|\bdo|\(|,|\{|\+|-|\*|/|%|\?|:|&|\|)\s*$",
                         seg[:p].rstrip()):
            return None
    return name


def _in_call_args(text: str, pos: int) -> bool:
    depth = 0
    i = pos - 1
    while i >= 0:
        ch = text[i]
        if ch in ")]":
            depth += 1
        elif ch in "([":
            if depth == 0:
                return ch == "("
            depth -= 1
        elif depth == 0 and ch in ";}":
            return False
        elif depth == 0 and ch == "\n":
            # 续行（行尾是 `,` / `(` / 运算符）才算同一条语句 —— 否则上一行的
            # 括号会被误当成这一行的实参表。
            j = i - 1
            while j >= 0 and text[j] in " \t\r":
                j -= 1
            if j < 0 or text[j] not in ",(+-*/%?:&|=<>!":
                return False
        i -= 1
    return False


def _handed_on(body: str, name: str) -> bool:
    """这个值有没有离开本函数：作为调用实参 / 被 return / 当尾值输出。"""
    n = re.escape(name)
    decl = re.search(r"\b(?:val|var)\s+%s\s*(?::[^=\n]+)?=(?!=)" % n, body)
    start = decl.end() if decl else 0
    for m in re.finditer(r"(?<![\w.])%s\b" % n, body[start:]):
        if _in_call_args(body, start + m.start()):
            return True
    return bool(re.search(r"\breturn\s+%s\b" % n, body)
                or re.search(r"(?m)^\s*%s\s*$" % n, body))


def check_share_handoff(clean):
    """Arm 11 [R3-t04 11-1]: 解析出来的 FileProvider uri 必须真的交出去。

    `getUriForFile` 成功只说明 uri 能构造；分享面板里另一个进程能否拿到文件，
    取决于它有没有被放进 EXTRA_STREAM / clipData / data，或者被交给启动器。
    把 putExtra 删掉之后，arm5/arm6 的每一条判据（onRelease / onDismiss /
    ExternalIntent.share 的失败反馈）都还是绿的。
    """
    problems = []
    if clean is None:
        return problems
    sites = list(re.finditer(r"(?:[\w.]*\.)?FileProvider\s*\.\s*getUriForFile\s*\(", clean))
    if not sites:
        return ["arm11: found 0 `FileProvider.getUriForFile(` sites in "
                "InlineMediaPlayer.kt -- the share hand-off moved, fail-closed (R3-t04)"]
    for m in sites:
        span = _enclosing_fun_span(clean, m.start())
        body = clean[span[0]:span[1]] if span else clean
        rel_idx = m.start() - (span[0] if span else 0)
        name = _bound_name(body, rel_idx)
        if name is None:
            continue                  # 内联形态：解析即挂载，由构造保证
        if _handed_on(body, name):
            continue
        problems.append(
            "arm11: the FileProvider uri `%s` (offset %d) is resolved but never handed "
            "on -- no EXTRA_STREAM / clipData / setData and no launcher argument, so the "
            "share sheet opens with an empty ACTION_SEND while every flag/verdict guard "
            "around it still reports success (R3-t04)" % (name, m.start()))
    return problems


def check_player_state_machine(clean: str):
    """Arm 9 [R3-t04 11-3]: 播放状态机必须是**真的转换**，不是幂等空转。

    三条判据全部从树里派生（成员名不写死）：
      S1 先查状态再改状态的命令，改出来的成员不能等于它刚要求的成员 ——
         `pause` 里 `state != PLAYING → state = PLAYING` 是空操作：UI 仍显示
         播放中、`resume` 拒绝（它要求 PAUSED）、播放器保持暂停。
      S2 `pause` 落到的成员必须是 `resume` 要求的成员，反之亦然（互为逆）。
      S3 上报的 `state = PlayerState.X.name` 必须是真正赋给 session 的那个成员
         （否则 CLI 的输出与真实状态不一致）。
    """
    problems = []
    em = re.search(r"enum\s+class\s+PlayerState\s*\{([^}]*)\}", clean)
    members = set(re.findall(r"[A-Za-z_]\w*", em.group(1))) if em else set()
    if not members:
        return ["arm9: cannot derive the PlayerState members -- the state-machine "
                "criterion refuses to judge, fail-closed (R3-t04)"]
    cmds = {}
    for name in ("pause", "resume"):
        body = function_body(clean, r"\bfun\s+%s\s*\(" % name)
        if not body:
            return ["arm9: `fun %s(...)` is gone -- the state-machine criterion lost its "
                    "anchor, fail-closed (R3-t04)" % name]
        guard = re.search(r"session\s*\.\s*state\s*!=\s*PlayerState\s*\.\s*([A-Za-z_]\w*)",
                          body)
        assigned = re.findall(
            r"session\s*\.\s*state\s*=(?!=)\s*PlayerState\s*\.\s*([A-Za-z_]\w*)", body)
        reported = re.findall(
            r"\bstate\s*=\s*PlayerState\s*\.\s*([A-Za-z_]\w*)\s*\.\s*name", body)
        for ref in ([guard.group(1)] if guard else []) + assigned + reported:
            if ref not in members:
                return ["arm9: %s() references PlayerState.%s, which the enum does not "
                        "declare -- fail-closed (R3-t04)" % (name, ref)]
        if not guard or not assigned:
            return ["arm9: %s() no longer both guards on and assigns session.state -- "
                    "the transition cannot be judged, fail-closed (R3-t04)" % name]
        cmds[name] = dict(guard=guard.group(1), assigned=assigned, reported=reported)

    for name, rec in cmds.items():
        if rec["assigned"][-1] == rec["guard"]:
            problems.append(
                "arm9: %s() requires state == %s and then assigns state = %s -- an "
                "idempotent no-op instead of a transition, so the reported state never "
                "changes (pause after pause, resume refuses, playback stays put) "
                "(R3-t04)" % (name, rec["guard"], rec["assigned"][-1]))
        if rec["reported"] and rec["reported"][-1] != rec["assigned"][-1]:
            problems.append(
                "arm9: %s() assigns state = %s but reports state = %s -- the CLI output "
                "and the session disagree (R3-t04)"
                % (name, rec["assigned"][-1], rec["reported"][-1]))
    if cmds["pause"]["assigned"][-1] != cmds["resume"]["guard"]:
        problems.append(
            "arm9: pause() lands on %s while resume() requires %s -- the pair is not "
            "mutually inverse, so one of the two always refuses (R3-t04)"
            % (cmds["pause"]["assigned"][-1], cmds["resume"]["guard"]))
    if cmds["resume"]["assigned"][-1] != cmds["pause"]["guard"]:
        problems.append(
            "arm9: resume() lands on %s while pause() requires %s -- the pair is not "
            "mutually inverse (R3-t04)"
            % (cmds["resume"]["assigned"][-1], cmds["pause"]["guard"]))
    return problems


def check_arms(clean: str):
    """Four arms on the comment-stripped file text. Returns problem list."""
    problems = []

    rel_body = function_body(clean, r"private\s+fun\s+releaseSession\s*\(")
    if not rel_body:
        return ["could not locate `private fun releaseSession(...)` in "
                + TARGET_REL + " -- the function may have been renamed; re-point this guard"]

    # arm 1: stop() and release() in different try blocks
    stop_call = re.search(r"session\.player\s*\.\s*stop\s*\(\s*\)", rel_body)
    release_call = re.search(r"session\.player\s*\.\s*release\s*\(\s*\)", rel_body)
    if not stop_call or not release_call:
        problems.append(
            "arm1: releaseSession no longer calls both session.player.stop() and "
            "session.player.release() -- re-point this guard (release() must be "
            "called on every path)")
    else:
        stop_try = enclosing_try(rel_body, stop_call.start())
        release_try = enclosing_try(rel_body, release_call.start())
        if stop_try is None:
            problems.append(
                "arm1: session.player.stop() is not inside a try block -- a throwing "
                "stop() would propagate out of releaseSession and skip release()")
        if release_try is None:
            problems.append(
                "arm1: session.player.release() is not inside a try block -- release "
                "must be guarded so terminalize paths never die mid-release")
        if stop_try is not None and release_try is not None:
            if stop_try == release_try:
                problems.append(
                    "arm1: stop() and release() share ONE try block -- stop() throws "
                    "IllegalStateException once the player left the playable states "
                    "(the onError terminalize), which skips release() and leaks the "
                    "native codec (InlineMediaPlayer.onDispose is the fixed shape)")
            elif stop_try[0] < release_call.start() < stop_try[1]:
                # wide3: nesting `try { release() }` INSIDE the try that guards
                # stop() keeps "two different try blocks" true while restoring the
                # exact defect -- stop() throws, the outer catch swallows it, the
                # sibling-inner release never runs. Judge reachability, not
                # distinctness of spans.
                problems.append(
                    "arm1: release() is nested INSIDE the try block that calls stop() "
                    "-- a throwing stop() (the onError terminalize runs in exactly that "
                    "state) skips it and leaks the native codec; the two blocks must be "
                    "SIBLINGS so release() runs on the stop()-threw path too")

    # arm 2: @Volatile on Session.state
    if not re.search(r"@Volatile\s+var\s+state\s*:\s*PlayerState", clean):
        problems.append(
            "arm2: Session.state is not `@Volatile` -- callback threads write state "
            "(onCompletion/onError) while caller threads read it; without a "
            "happens-before edge a reader can hand a released player to pause()/stop()")

    # arm 3: play() pre-release under the lock
    play_body = function_body(clean, r"fun\s+play\s*\(")
    if not play_body:
        problems.append(
            "arm3: could not locate `fun play(...)` -- the pre-release may have moved; "
            "re-point this guard")
    else:
        m = re.search(r"sessions\s*\[\s*sessionId\s*\]\s*\?\.", play_body)
        locks = synchronized_lock_spans(play_body)
        release_sites = [mm.start() for mm in
                         re.finditer(r"releaseSession\s*\(\s*\w+\s*\)", play_body)]
        if not release_sites:
            problems.append(
                "arm3: play() no longer pre-releases the existing session via "
                "releaseSession(existing) -- if the shape changed, re-point this guard")
        else:
            if m and not inside_any(locks, m.start()):
                problems.append(
                    "arm3: play() reads/releases the previous session OUTSIDE "
                    "synchronized(lock) -- the terminalize callbacks release the same "
                    "instance under this lock; unlocked this is a double-release race")
            # wide3: the REPLACEMENT call site is what races, not the read. The
            # witness moved `releaseSession(existing)` out of the lock while leaving
            # the map read inside it, so checking the read's position said green
            # for a release the callback thread can hit at the same instant.
            for idx in release_sites:
                if inside_any(locks, idx):
                    continue
                # ...unless the instance left the map UNDER the lock before it: a
                # `put`/`remove` of the same key is what makes the outside release
                # safe (the callback no longer has a reachable owner).
                detached = False
                for ls, le in locks:
                    if ls > idx:
                        continue
                    span = play_body[ls:le]
                    if re.search(r"sessions\s*\.\s*(?:remove|put)\s*\(", span):
                        detached = True
                        break
                if not detached:
                    problems.append(
                        "arm3: play() calls releaseSession(...) OUTSIDE "
                        "synchronized(lock) without first removing/replacing the map "
                        "entry under that lock -- the terminalize callbacks release the "
                        "same Session instance under the lock, so this is the "
                        "double-release race (da1022-C)")

    # arm 4: stop() remove under the lock
    stop_body = function_body(clean, r"fun\s+stop\s*\(")
    if not stop_body:
        problems.append(
            "arm4: could not locate `fun stop(...)` -- re-point this guard")
    else:
        m = re.search(r"sessions\s*\.\s*remove\s*\(\s*sessionId\s*\)", stop_body)
        if not m:
            problems.append(
                "arm4: stop() no longer removes via sessions.remove(sessionId) -- "
                "if the shape changed, re-point this guard")
        elif not inside_any(synchronized_lock_spans(stop_body), m.start()):
            problems.append(
                "arm4: sessions.remove(sessionId) in stop() is OUTSIDE synchronized(lock) "
                "-- release-then-remove unlocked races a concurrent play() that "
                "re-registers the id: the fresh session gets dropped from the map "
                "while its player is still live (unreferenced leak)")

    return problems


def android_view_blocks(clean: str):
    """(start, end) spans of every `AndroidView(` argument list (paren-matched)."""
    spans = []
    n = len(clean)
    for m in re.finditer(r"\bAndroidView\s*\(", clean):
        open_idx = clean.find("(", m.start())
        depth, i = 1, open_idx + 1
        while i < n and depth:
            if clean[i] == "(":
                depth += 1
            elif clean[i] == ")":
                depth -= 1
            i += 1
        spans.append((open_idx, i))
    return spans


def enclosing_block(clean: str, idx: int):
    """Smallest brace span containing idx, or None. Strings/comments must
    already be stripped so brace_span's counting is exact."""
    best = None
    for pos, c in enumerate(clean):
        if c != "{":
            continue
        start, end = brace_span(clean, pos)
        if start < idx < end and (best is None or (end - start) < (best[1] - best[0])):
            best = (start, end)
    return best


def check_inline_player(clean):
    """Arm 5 (da1027-F). `clean` = comment/string-stripped InlineMediaPlayer.kt,
    or None when the file is absent (mini-tree fixture -> skip)."""
    problems = []
    if clean is None:
        return problems

    blocks = android_view_blocks(clean)
    if not blocks:
        problems.append(
            "arm5a: found 0 `AndroidView(` call sites in InlineMediaPlayer.kt -- the "
            "anchor is fail-closed; re-point this guard (both the inline player and "
            "the fullscreen player are hosted by AndroidView)")
    for start, end in blocks:
        block = clean[start:end]
        if not re.search(r"\bonRelease\s*=", block):
            problems.append(
                "arm5a: AndroidView at offset %d has no `onRelease =` -- the view is "
                "never released when it leaves composition, so the MediaPlayer and "
                "its hardware codec/AudioTrack handles stay registered until GC"
                % start)
        elif not re.search(r"\bstopPlayback\s*\(", block):
            problems.append(
                "arm5a: AndroidView at offset %d has an `onRelease` that never calls "
                "`stopPlayback()` -- release must stop the track, not just hide the "
                "controller window" % start)
        if re.search(r"\bvideoView\s*=\s*this\b", block):
            # The fullscreen player hoists the view into state that the position
            # poll and the play/pause button read back.
            if not re.search(r"\bvideoView\s*=\s*null\b", block):
                problems.append(
                    "arm5b: AndroidView at offset %d hoists `videoView = this` but its "
                    "`onRelease` never clears the reference (`videoView = null`) -- the "
                    "released view stays reachable and the position poll keeps touching "
                    "a stopped player" % start)

    sites = [m.start() for m in re.finditer(r"\bonDismiss\s*\(\s*\)", clean)]
    if not sites:
        problems.append(
            "arm5c: found 0 `onDismiss()` call sites in InlineMediaPlayer.kt -- the "
            "anchor is fail-closed; re-point this guard (every dismissal branch must "
            "terminate the player)")
    for idx in sites:
        span = enclosing_block(clean, idx)
        if span is None or not re.search(r"\bstopPlayback\s*\(", clean[span[0]:span[1]]):
            problems.append(
                "arm5c: the block containing `onDismiss()` at offset %d does not call "
                "`stopPlayback()` -- dismissing the fullscreen player is a terminating "
                "path, not a pause (the back-key branch reaches the same release only "
                "through onRelease, so a bare pause() there leaks the native player "
                "and lets audio outlive the dialog)" % idx)
    return problems


def catch_body_after(text: str, try_end: int):
    """Body (braces included) of the `catch (...) { ... }` that immediately
    follows the try block ending at `try_end`, or None when the shape is not
    a try/catch at all. `text` must already be comment/string-stripped."""
    tail = text[try_end:]
    m = re.search(r"^\s*catch\s*(?:\([^)]*\))?\s*\{", tail)
    if not m:
        return None
    brace = tail.find("{", m.start())
    start, end = brace_span(tail, brace)
    return tail[start:end]


def check_inline_player_guards(clean):
    """Arm 6 (da1033-B). `clean` = comment/string-stripped
    InlineMediaPlayer.kt, or None when the file is absent (mini tree ->
    skip). Judged on the same stripped view as arm 5, so a commented-out
    call never counts."""
    problems = []
    if clean is None:
        return problems

    # ── arm 6a: audio transport calls are unreachable from the caller's
    # exception surface ────────────────────────────────────────────────────
    calls = list(re.finditer(
        r"\bmediaPlayer\s*\.\s*(seekTo|pause|start)\s*\(", clean))
    if not calls:
        problems.append(
            "arm6a: found 0 `mediaPlayer.seekTo(` / `.pause()` / `.start()` call "
            "sites in InlineMediaPlayer.kt -- the anchor is fail-closed; re-point "
            "this guard (the audio transport controls live in InlineAudioPlayer)")
    for m in calls:
        if enclosing_try(clean, m.start()) is None:
            problems.append(
                "arm6a: `mediaPlayer.%s(` at offset %d is not inside a try block -- "
                "the player can already be in the Error state while the frame still "
                "renders the control, and the IllegalStateException then escapes the "
                "click callback and crashes the app" % (m.group(1), m.start()))

    # ── arm 6b: the share path never hands a fabricated file:// URI to
    # another process, and its failure branch is visible + terminating ─────
    share_body = function_body(clean, r"private\s+fun\s+shareMediaFile\s*\(")
    if not share_body:
        problems.append(
            "arm6b: could not locate `private fun shareMediaFile(...)` in "
            "InlineMediaPlayer.kt -- re-point this guard")
        return problems

    fp = re.search(r"getUriForFile\s*\(", share_body)
    if fp is None:
        problems.append(
            "arm6b: shareMediaFile no longer resolves the share uri through "
            "FileProvider.getUriForFile -- re-point this guard")
    if re.search(r"\bUri\s*\.\s*fromFile\s*\(", share_body):
        problems.append(
            "arm6b: shareMediaFile builds a `Uri.fromFile(...)` -- a plain file:// "
            "uri inside ACTION_SEND crosses the process boundary and raises "
            "FileUriExposedException (API 24+), so the share key silently does "
            "nothing; an undeclared path must be reported, not faked as a uri")
    if not re.search(r"\bAppLogger\s*\.\s*warning\s*\(", share_body):
        problems.append(
            "arm6b: the FileProvider failure path in shareMediaFile logs nothing -- "
            "an undeclared path then leaves no trace anywhere")
    if not re.search(r"\bToast\s*\.\s*makeText\s*\(", share_body):
        problems.append(
            "arm6b: the FileProvider failure path in shareMediaFile never tells the "
            "user -- the share key must surface the refusal instead of doing nothing")
    if not re.search(r"R\s*\.\s*string\s*\.\s*\w*share_failed_toast", share_body):
        problems.append(
            "arm6b: the share failure path does not reuse an existing "
            "`*share_failed_toast` string resource -- adding a new per-language "
            "entry for this is not the shape the rest of the share call sites use")
    if fp is not None:
        tspan = enclosing_try(share_body, fp.start())
        if tspan is None:
            problems.append(
                "arm6b: getUriForFile( is not inside a try block -- an undeclared "
                "path throws IllegalArgumentException out of shareMediaFile")
        else:
            cbody = catch_body_after(share_body, tspan[1])
            if cbody is None or not re.search(r"\breturn\b", cbody):
                problems.append(
                    "arm6b: the catch of the FileProvider lookup does not `return` -- "
                    "the failure path falls through and still puts the unresolved uri "
                    "into ACTION_SEND (EXTRA_STREAM), so the failure is never "
                    "terminating")
    return problems


# ── Arm 7: the fullscreen video window contract ([dv17-B08]) ──────────────
#
# The fullscreen video Dialog must own both halves of the immersive contract
# its three siblings already implement (ImageGalleryViewer, the WebPreview
# fullscreen screen, the WebPreview sheet):
#   7a  the Dialog's own window calls
#       addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) -- without the flag the
#       transparent status/navigation bar colors next to it are ignored and
#       the system paints its own opaque grey band over the video;
#   7b  a host-Activity-window DisposableEffect is installed BEFORE the
#       Dialog(, hiding the HOST window's system bars for the player's
#       lifetime and restoring them (bars + icon appearance) on dispose --
#       the dialog-window effect alone cannot cover the window the system
#       falls back to while the overlay covers the screen.
# Decision points, not spellings: 7a is bound to the CALL FORM
# `<target>.addFlags(<args containing the flag constant>)`, so a comment or a
# bare constant reference cannot satisfy it; 7b is bound to the effect's
# TARGET (`(view.context as? Activity)?.window`, not findDialogWindow) and to
# its onDispose restore, so the effect's key choice and formatting stay free.
# Anchors are fail-closed: a file without `MinisFullscreenVideoPlayer(` or
# without `FullscreenVideoContent(` cannot be judged => RED (re-point).
# A mini-tree fixture without the file is skipped (drift detector, not a
# target).

_BAR_FLAG = "FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS"
_FULLSCREEN_PLAYER_DECL = r"\bfun\s+MinisFullscreenVideoPlayer\s*\("
_FULLSCREEN_CONTENT_DECL = r"\bprivate\s+fun\s+FullscreenVideoContent\s*\("


def _call_args(text: str, open_idx: int) -> str:
    """Argument text of the call whose `(` sits at open_idx."""
    depth, i, n = 1, open_idx + 1, len(text)
    while i < n and depth:
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
        i += 1
    return text[open_idx + 1:i - 1]


def _addflags_calls(text: str):
    """[(start, args)] for every `<target>.addFlags(...)` call form."""
    out = []
    for m in re.finditer(r"\.\s*addFlags\s*\(", text):
        open_idx = text.find("(", m.start())
        out.append((m.start(), _call_args(text, open_idx)))
    return out


def _disposable_effects(text: str):
    """[(start, end, body, args)] for every `DisposableEffect(...) { ... }`."""
    out = []
    for m in re.finditer(r"\bDisposableEffect\s*\(", text):
        open_idx = text.find("(", m.start())
        args = _call_args(text, open_idx)
        close = open_idx + len(args) + 2
        brace = text.find("{", close)
        if brace < 0:
            continue
        start, end = brace_span(text, brace)
        out.append((m.start(), end, text[start:end], args))
    return out


def _host_window_effect_effects(text: str):
    """The DisposableEffects that target the HOST activity window (`as? Activity`
    + `.window`), as opposed to the Dialog's own window (findDialogWindow)."""
    out = []
    for start, end, body, args in _disposable_effects(text):
        hay = args + " " + body
        if "findDialogWindow(" in hay:
            continue
        if not re.search(r"as\s*\?\s*(?:android\.app\.)?Activity\b", hay):
            continue
        if not re.search(r"\.window\b", hay):
            continue
        out.append((start, end, body))
    return out


def check_fullscreen_video_window(clean):
    """Arm 7. Returns problem list."""
    if clean is None:
        return []
    problems = []
    m = re.search(_FULLSCREEN_PLAYER_DECL, clean)
    if not m:
        problems.append(
            "arm7: no `fun MinisFullscreenVideoPlayer(` in this file -- the fullscreen "
            "video window contract has no anchor, re-point this guard")
        return problems
    brace = clean.find("{", m.end())
    if brace < 0:
        problems.append(
            "arm7: `MinisFullscreenVideoPlayer` has no body -- re-point this guard")
        return problems
    p_start, p_end = brace_span(clean, brace)
    player_body = clean[p_start:p_end]

    # 7b: a host-window effect, installed before the Dialog.
    dialog_at = player_body.find("Dialog(")
    if dialog_at < 0:
        problems.append(
            "arm7: `MinisFullscreenVideoPlayer` no longer opens a Dialog -- re-point "
            "this guard")
    else:
        host = [e for e in _host_window_effect_effects(player_body) if e[0] < dialog_at]
        if not host:
            problems.append(
                "arm7: MinisFullscreenVideoPlayer has no host-Activity-window "
                "DisposableEffect before its Dialog( -- ImageGalleryViewer / "
                "WebPreviewFullscreenScreen / WebPreviewBottomSheet all save+hide the "
                "HOST window's system bars for the lifetime of the overlay and restore "
                "them on dispose; without it a swipe-revealed transient bar paints the "
                "host's default chrome and the host is left in whatever immersive state "
                "the overlay set")
        else:
            body = host[0][2]
            dispose_at = body.rfind("onDispose")
            if dispose_at < 0:
                problems.append(
                    "arm7: the host-window effect of MinisFullscreenVideoPlayer has no "
                    "onDispose -- nothing restores the host's system bars when the "
                    "player closes")
            else:
                dispose = body[dispose_at:]
                if "controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())" \
                        not in dispose:
                    problems.append(
                        "arm7: the host-window effect's onDispose does not show the "
                        "host's system bars again")
                if "isAppearanceLightStatusBars" not in dispose or \
                        "isAppearanceLightNavigationBars" not in dispose:
                    problems.append(
                        "arm7: the host-window effect's onDispose does not restore the "
                        "host's status/navigation icon appearance")

    # 7a: the Dialog window declares the draw-behind flag (call form bound).
    spans = [player_body]
    content = function_body(clean, _FULLSCREEN_CONTENT_DECL)
    if content:
        spans.append(content)
    else:
        problems.append(
            "arm7: `FullscreenVideoContent` is gone -- the Dialog window config has no "
            "anchor, re-point this guard")
    flag_ok = any(_BAR_FLAG in args
                  for span in spans
                  for _start, args in _addflags_calls(span))
    if not flag_ok:
        problems.append(
            "arm7: the fullscreen video Dialog window never calls "
            "addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) -- without the flag the "
            "transparent status/navigation bar colors next to it are ignored and the "
            "system paints its own opaque grey band over the video")
    return problems


def run_fullscreen_video_window_selftest() -> bool:
    """Arm 7 fixtures (dv17-B08). Each dirty case must go red AND the expected
    sub-arm must be the one that fires."""
    HOST_EFFECT = (
        "    val hostView = LocalView.current\n"
        "    DisposableEffect(hostView) {\n"
        "        val window = (hostView.context as? android.app.Activity)?.window\n"
        "        if (window == null) {\n"
        "            onDispose { }\n"
        "        } else {\n"
        "            val controller = androidx.core.view.WindowInsetsControllerCompat(window, hostView)\n"
        "            val previousLightStatusBars = controller.isAppearanceLightStatusBars\n"
        "            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars\n"
        "            controller.isAppearanceLightStatusBars = false\n"
        "            controller.isAppearanceLightNavigationBars = false\n"
        "            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())\n"
        "            onDispose {\n"
        "                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())\n"
        "                controller.isAppearanceLightStatusBars = previousLightStatusBars\n"
        "                controller.isAppearanceLightNavigationBars = previousLightNavigationBars\n"
        "            }\n"
        "        }\n"
        "    }\n")
    DIALOG_FLAG = (
        "                window.setBackgroundDrawable(ColorDrawable(Color.BLACK))\n"
        "                window.addFlags(android.view.WindowManager.LayoutParams."
        "FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)\n"
        "                window.statusBarColor = Color.TRANSPARENT\n"
        "                window.navigationBarColor = Color.TRANSPARENT\n")

    COMPLIANT = (
        "@Composable\n"
        "fun MinisFullscreenVideoPlayer(file: File, onDismiss: () -> Unit) {\n"
        + HOST_EFFECT +
        "    Dialog(\n"
        "        onDismissRequest = onDismiss,\n"
        "        properties = DialogProperties(usePlatformDefaultWidth = false),\n"
        "    ) {\n"
        "        FullscreenVideoContent(file = file, onDismiss = onDismiss)\n"
        "    }\n"
        "}\n"
        "\n"
        "@Composable\n"
        "private fun FullscreenVideoContent(file: File, onDismiss: () -> Unit) {\n"
        "    DisposableEffect(view) {\n"
        "        val window = findDialogWindow(view)\n"
        "        if (window != null) {\n"
        + DIALOG_FLAG +
        "            val controller = WindowInsetsControllerCompat(window, window.decorView)\n"
        "            controller.hide(WindowInsetsCompat.Type.systemBars())\n"
        "        }\n"
        "        onDispose {\n"
        "            val controller = WindowInsetsControllerCompat(window, window.decorView)\n"
        "            controller.show(WindowInsetsCompat.Type.systemBars())\n"
        "        }\n"
        "    }\n"
        "}\n")

    M7A = COMPLIANT.replace(DIALOG_FLAG,
                            "                window.setBackgroundDrawable(ColorDrawable(Color.BLACK))\n"
                            "                window.statusBarColor = Color.TRANSPARENT\n"
                            "                window.navigationBarColor = Color.TRANSPARENT\n")
    M7B = COMPLIANT.replace(HOST_EFFECT, "")
    # the effect is host-shaped but installed INSIDE the Dialog content, i.e.
    # after `Dialog(` -- the bars must be hidden when the dialog opens, not
    # after
    M7C = COMPLIANT.replace(HOST_EFFECT, "").replace(
        "        FullscreenVideoContent(file = file, onDismiss = onDismiss)\n",
        "        FullscreenVideoContent(file = file, onDismiss = onDismiss)\n" + HOST_EFFECT)
    # wrong target: the effect drives the DIALOG window, not the host window
    M7D = COMPLIANT.replace(
        "        val window = (hostView.context as? android.app.Activity)?.window\n",
        "        val window = findDialogWindow(hostView)\n")
    # empty real onDispose: the bars are never handed back
    M7E = COMPLIANT.replace(
        "                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())\n"
        "                controller.isAppearanceLightStatusBars = previousLightStatusBars\n"
        "                controller.isAppearanceLightNavigationBars = previousLightNavigationBars\n",
        "")
    # wrong flag: addFlags present, but not the draw-behind one
    M7F = COMPLIANT.replace(
        "window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)",
        "window.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)")

    # precision: comments may mention the flag / the effect in prose
    P7 = COMPLIANT.replace(
        "        properties = DialogProperties(usePlatformDefaultWidth = false),\n",
        "        // legacy shape lacked addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)\n"
        "        properties = DialogProperties(usePlatformDefaultWidth = false),\n")

    # A mutation that silently stopped applying would read as a false alarm;
    # name the drift instead.
    ok = True
    for label, mut in (("M7A", M7A), ("M7B", M7B), ("M7C", M7C), ("M7D", M7D),
                       ("M7E", M7E), ("M7F", M7F), ("P7", P7)):
        if mut == COMPLIANT:
            print(f"  selftest arm7 {label}: mutation did not apply "
                  f"(fixture drifted) -- FAIL")
            ok = False

    cases = [
        ("arm7 compliant", COMPLIANT, None),
        ("arm7a M7A flag dropped", M7A, "arm7: the fullscreen video Dialog window"),
        ("arm7b M7B host effect gone", M7B, "arm7: MinisFullscreenVideoPlayer has no host-Activity-window"),
        ("arm7b M7C effect moved after the Dialog", M7C, "arm7: MinisFullscreenVideoPlayer has no host-Activity-window"),
        ("arm7b M7D effect retargeted to the dialog window", M7D, "arm7: MinisFullscreenVideoPlayer has no host-Activity-window"),
        ("arm7b M7E dispose restores nothing", M7E, "arm7: the host-window effect's onDispose"),
        ("arm7a M7F addFlags with the wrong flag", M7F, "arm7: the fullscreen video Dialog window"),
        ("arm7 precision P7 comment mentions the flag", P7, None),
    ]
    for name, src, want in cases:
        problems = check_fullscreen_video_window(strip_comments_and_strings(src))
        red = len(problems) > 0
        if want is None:
            mark = "ok" if not red else "FAIL"
            print(f"  selftest {name}: {'RED' if red else 'GREEN'} "
                  f"(expect GREEN) {mark}")
            if red:
                ok = False
        else:
            fired = any(p.startswith(want) for p in problems)
            mark = "ok" if (red and fired) else "FAIL"
            print(f"  selftest {name}: {'RED' if red else 'GREEN'} "
                  f"(expect RED via {want.split(':')[0]}) {mark}")
            if not (red and fired):
                ok = False
        for p in problems:
            print(f"      - {p}")

    # mini-tree fixture: InlineMediaPlayer.kt absent -> judgement skipped
    if check_fullscreen_video_window(None):
        print("  selftest arm7 mini tree (no InlineMediaPlayer.kt): expected "
              "GREEN (skipped), got RED -- FAIL")
        ok = False
    else:
        print("  selftest arm7 mini tree (no InlineMediaPlayer.kt): GREEN (skipped) ok")
    # [dv17-B08] own marker line -- the pinned MEDIAPLAYER_RELEASE_SELFTEST=PASS
    # above it now covers arms 5+6+7; this line proves arm 7 ran and went green.
    print("FULLSCREEN_WINDOW_SELFTEST=" + ("PASS" if ok else "FAIL"))
    return ok


def run_selftest() -> bool:
    COMPLIANT = '''
object MediaPlayerManager {
    private data class Session(
        val player: MediaPlayer,
        val filePath: String,
        val mediaType: String,
        @Volatile var state: PlayerState = PlayerState.IDLE,
    )
    fun play(sessionId: String, filePath: String): String {
        synchronized(lock) {
            sessions[sessionId]?.let { existing ->
                releaseSession(existing)
            }
        }
        return "x"
    }
    fun stop(sessionId: String): String {
        val removed = synchronized(lock) { sessions.remove(sessionId) }
            ?: return "Error: no session"
        releaseSession(removed)
        return "x"
    }
    private fun releaseSession(session: Session) {
        try {
            if (session.state == PlayerState.PLAYING) {
                session.player.stop()
            }
        } catch (e: Exception) { }
        try {
            session.player.release()
            session.state = PlayerState.STOPPED
        } catch (e: Exception) { }
    }
}
'''
    M1 = COMPLIANT.replace(
        """        try {
            if (session.state == PlayerState.PLAYING) {
                session.player.stop()
            }
        } catch (e: Exception) { }
        try {
            session.player.release()
            session.state = PlayerState.STOPPED
        } catch (e: Exception) { }""",
        """        try {
            if (session.state == PlayerState.PLAYING) {
                session.player.stop()
            }
            session.player.release()
            session.state = PlayerState.STOPPED
        } catch (e: Exception) { }""")
    M2 = COMPLIANT.replace("@Volatile var state", "var state")
    M3 = COMPLIANT.replace(
        """        synchronized(lock) {
            sessions[sessionId]?.let { existing ->
                releaseSession(existing)
            }
        }""",
        """        sessions[sessionId]?.let { existing ->
            releaseSession(existing)
        }""")
    M4 = COMPLIANT.replace(
        "val removed = synchronized(lock) { sessions.remove(sessionId) }\n"
        "            ?: return \"Error: no session\"",
        "val session = sessions[sessionId] ?: return \"Error: no session\"\n"
        "        releaseSession(session)\n"
        "        sessions.remove(sessionId)")

    # ── wide3 witnesses, one per falsified axis ──────────────────────────
    # arm1 (anchor): the release try is nested INSIDE the stop try, so "two
    # different try blocks" stays true while a throwing stop() skips release().
    M5N = COMPLIANT.replace(
        """        try {
            if (session.state == PlayerState.PLAYING) {
                session.player.stop()
            }
        } catch (e: Exception) { }
        try {
            session.player.release()
            session.state = PlayerState.STOPPED
        } catch (e: Exception) { }""",
        """        try {
            if (session.state == PlayerState.PLAYING) {
                session.player.stop()
            }
            try {
                session.player.release()
                session.state = PlayerState.STOPPED
            } catch (e: Exception) { }
        } catch (e: Exception) { }""")
    # arm3 (dataflow): the map read stays under the lock, the RELEASE does not.
    M6N = COMPLIANT.replace(
        """        synchronized(lock) {
            sessions[sessionId]?.let { existing ->
                releaseSession(existing)
            }
        }""",
        """        val existing = synchronized(lock) { sessions[sessionId]?.let { it } }
        if (existing != null) releaseSession(existing)""")
    if M5N == COMPLIANT or M6N == COMPLIANT:
        drift = True
    else:
        drift = False

    cases = [
        ("compliant", COMPLIANT, 0),
        ("M1 stop+release same try", M1, 1),
        ("M2 state not volatile", M2, 1),
        ("M3 play pre-release unlocked", M3, 1),
        ("M4 stop remove unlocked", M4, 1),
        ("M5n release try nested inside the stop try (wide3 arm1)", M5N, 1),
        ("M6n release moved out of the lock, read left inside (wide3 arm3)", M6N, 1),
    ]
    ok = True
    if drift:
        print("  selftest wide3 arm1/arm3: mutation did not apply (fixture "
              "drifted) -- FAIL")
        ok = False
    for name, src, want_red in cases:
        problems = check_arms(strip_comments_and_strings(src))
        red = len(problems) > 0
        status = "RED" if red else "GREEN"
        expected = "RED" if want_red else "GREEN"
        mark = "ok" if red == bool(want_red) else "FAIL"
        if mark == "FAIL":
            ok = False
        print(f"  selftest {name}: {status} (expect {expected}) {mark}")
        for p in problems:
            print(f"      - {p}")

    # shape edge: releaseSession gone -> explicit red, not a silent pass
    problems = check_arms(strip_comments_and_strings("object MediaPlayerManager {}"))
    if not problems:
        print("  selftest no-releaseSession: expected explicit red, got GREEN -- FAIL")
        ok = False
    else:
        print("  selftest no-releaseSession: RED (expect RED) ok")

    ok = run_inline_player_selftest() and ok
    ok = run_inline_player_guards_selftest() and ok
    ok = run_fullscreen_video_window_selftest() and ok
    ok = run_media_host_keys_selftest() and ok
    ok = run_seam_selftest() and ok
    return ok


def run_inline_player_selftest() -> bool:
    """Arm 5 fixtures (da1027-F). Each dirty case must go red AND the expected
    sub-arm must be the one that fires."""
    COMPLIANT = '''
@Composable
fun InlineVideoPlayer(filePath: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(Uri.parse(filePath))
                }
            },
            onRelease = { view ->
                mediaController.hide()
                view.stopPlayback()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun MinisFullscreenVideoPlayer(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        FullscreenVideoContent(file = file, onDismiss = onDismiss)
    }
}

@Composable
private fun FullscreenVideoContent(file: File, onDismiss: () -> Unit) {
    var videoView by remember { mutableStateOf<android.widget.VideoView?>(null) }
    Box(
        modifier = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = {
                    if (dragOffsetPx > dismissThresholdPx * 0.5f) {
                        try { videoView?.stopPlayback() } catch (_: Throwable) {}
                        onDismiss()
                    } else {
                        dragOffsetPx = 0f
                    }
                },
            )
        },
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(Uri.fromFile(file))
                    videoView = this
                }
            },
            onRelease = { view ->
                try { view.stopPlayback() } catch (_: Throwable) {}
                if (videoView === view) videoView = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        CircleControlButton(
            icon = Icons.Default.Close,
            onClick = {
                try { videoView?.stopPlayback() } catch (_: Throwable) {}
                onDismiss()
            },
        )
    }
}
'''
    FULLSCREEN_ONRELEASE = '''            onRelease = { view ->
                try { view.stopPlayback() } catch (_: Throwable) {}
                if (videoView === view) videoView = null
            },
'''
    GESTURE_STOP = ('                        try { videoView?.stopPlayback() } '
                    'catch (_: Throwable) {}\n                        onDismiss()')
    CLOSE_STOP = ('                try { videoView?.stopPlayback() } '
                  'catch (_: Throwable) {}\n                onDismiss()')

    M5 = COMPLIANT.replace(FULLSCREEN_ONRELEASE, "")
    M6 = COMPLIANT.replace(
        "                if (videoView === view) videoView = null\n",
        "                view.setBackgroundColor(0)\n")
    M7 = COMPLIANT.replace(GESTURE_STOP, GESTURE_STOP.replace("stopPlayback", "pause"))
    M8 = COMPLIANT.replace(CLOSE_STOP, CLOSE_STOP.replace("stopPlayback", "pause"))
    M9 = COMPLIANT.replace("androidx.compose.ui.viewinterop.AndroidView(", "VideoSurface(")
    M10 = COMPLIANT.replace("            onDismiss()\n", "")
    M11 = COMPLIANT.replace("view ->", "view ->").replace(
        "                view.stopPlayback()\n", "")

    cases = [
        ("arm5 compliant", COMPLIANT, None),
        ("arm5a M5 no onRelease on fullscreen block", M5, "arm5a"),
        ("arm5b M6 onRelease does not clear the hoisted ref", M6, "arm5b"),
        ("arm5c M7 gesture back to pause()", M7, "arm5c"),
        ("arm5c M8 close button back to pause()", M8, "arm5c"),
        ("arm5a M9 no AndroidView at all (fail-closed anchor)", M9, "arm5a"),
        ("arm5c M10 no onDismiss() site (fail-closed anchor)", M10, "arm5c"),
        ("arm5a M11 inline onRelease stops releasing", M11, "arm5a"),
    ]
    ok = True
    for name, src, want_key in cases:
        problems = check_inline_player(strip_comments_and_strings(src))
        red = len(problems) > 0
        if want_key is None:
            mark = "ok" if not red else "FAIL"
            print(f"  selftest {name}: {'RED' if red else 'GREEN'} "
                  f"(expect GREEN) {mark}")
            if red:
                ok = False
        else:
            fired = any(p.startswith(want_key) for p in problems)
            mark = "ok" if (red and fired) else "FAIL"
            print(f"  selftest {name}: {'RED' if red else 'GREEN'} "
                  f"(expect RED via {want_key}) {mark}")
            if not (red and fired):
                ok = False
        for p in problems:
            print(f"      - {p}")

    # mini-tree fixture: InlineMediaPlayer.kt absent -> judgement skipped
    if check_inline_player(None):
        print("  selftest arm5 mini tree (no InlineMediaPlayer.kt): expected "
              "GREEN (skipped), got RED -- FAIL")
        ok = False
    else:
        print("  selftest arm5 mini tree (no InlineMediaPlayer.kt): GREEN (skipped) ok")
    return ok


def run_inline_player_guards_selftest() -> bool:
    """Arm 6 fixtures (da1033-B). Each dirty case must go red through the
    expected sub-arm, the precision case must stay green (an in-process
    `Uri.fromFile` in the VideoView path is NOT the defect), and every
    mutation is checked for actually applying (a drifting fixture must fail
    loudly instead of passing as "expected red")."""
    COMPLIANT = '''
fun InlineAudioPlayer(filePath: String) {
    var isPlaying by remember { mutableStateOf(false) }
    val mediaPlayer = remember(filePath) { MediaPlayer() }
    if (isPlaying) {
        IconButton(
            onClick = {
                if (!prepared) return@IconButton
                try {
                    mediaPlayer.seekTo(0)
                    mediaPlayer.pause()
                    isPlaying = false
                } catch (e: Exception) {
                    isPlaying = false
                    error = e.message ?: "Playback failed"
                }
            },
        ) { }
    }
}

private fun inlineVideo(filePath: String) {
    AndroidView(
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                setVideoURI(Uri.fromFile(File(filePath)))
            }
        },
    )
}

private fun shareMediaFile(context: android.content.Context, file: File, mime: String) {
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (_: Throwable) {
        AppLogger.warning("InlineMediaPlayer", "share: path not covered by FileProvider: $file")
        Toast.makeText(
            context,
            context.getString(R.string.file_share_failed_toast, file.name),
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply { type = mime }
    try { context.startActivity(Intent.createChooser(intent, file.name)) } catch (_: Throwable) {}
}
'''
    STOP_GUARDED = (
        "                try {\n"
        "                    mediaPlayer.seekTo(0)\n"
        "                    mediaPlayer.pause()\n"
        "                    isPlaying = false\n"
        "                } catch (e: Exception) {\n"
        "                    isPlaying = false\n"
        "                    error = e.message ?: \"Playback failed\"\n"
        "                }\n")
    STOP_BARE = (
        "                mediaPlayer.seekTo(0)\n"
        "                mediaPlayer.pause()\n"
        "                isPlaying = false\n")

    M6a = COMPLIANT.replace(STOP_GUARDED, STOP_BARE)
    M6c = COMPLIANT.replace(STOP_GUARDED, "                pendingPlay = true\n")
    M6b = COMPLIANT.replace("        return\n    }\n",
                            "        val fake = Uri.fromFile(file)\n        return\n    }\n")
    M6b2 = COMPLIANT.replace("        return\n    }\n", "    }\n")
    M6b3 = COMPLIANT.replace(
        "        Toast.makeText(\n"
        "            context,\n"
        "            context.getString(R.string.file_share_failed_toast, file.name),\n"
        "            Toast.LENGTH_SHORT,\n"
        "        ).show()\n", "")
    M6b4 = COMPLIANT.replace(
        '        AppLogger.warning("InlineMediaPlayer", "share: path not covered by '
        'FileProvider: $file")\n', "")
    M6b5 = COMPLIANT.replace("R.string.file_share_failed_toast", "R.string.common_share")
    M6d = COMPLIANT.replace(
        "private fun shareMediaFile(context: android.content.Context, file: File, "
        "mime: String) {", "private fun shareSomethingElse(mime: String) {")
    # wide3 arm6 (parser-death): a DEFAULT-VALUE lambda in the parameter list.
    # `function_body` used to take the first `{` after the name, so it inspected
    # the lambda and never saw the real body's `Uri.fromFile(file)`.
    M6e = COMPLIANT.replace(
        "private fun shareMediaFile(context: android.content.Context, file: File, "
        "mime: String) {\n"
        "    val authority = context.packageName + \".fileprovider\"\n"
        "    val uri = try {",
        "private fun shareMediaFile(\n"
        "    context: android.content.Context,\n"
        "    file: File,\n"
        "    mime: String,\n"
        "    guardStub: () -> Unit = {\n"
        "        try { androidx.core.content.FileProvider.getUriForFile(context, \"\", file) }"
        " catch (_: Throwable) {\n"
        "            AppLogger.warning(\"InlineMediaPlayer\", \"\")\n"
        "            Toast.makeText(context, R.string.file_share_failed_toast, "
        "Toast.LENGTH_SHORT)\n"
        "            return\n"
        "        }\n"
        "    },\n"
        ") {\n"
        "    val uri = Uri.fromFile(file)\n"
        "    if (false) try {")
    # precision: comments may mention the very calls that are forbidden
    P2 = COMPLIANT.replace(
        "                if (!prepared) return@IconButton\n",
        "                // legacy shape was mediaPlayer.pause() ... Uri.fromFile(file)\n"
        "                if (!prepared) return@IconButton\n")

    # A mutation that silently stopped applying would read as a false alarm;
    # name the drift instead.
    ok = True
    for label, mut in (("M6a", M6a), ("M6b", M6b), ("M6b2", M6b2), ("M6b3", M6b3),
                       ("M6b4", M6b4), ("M6b5", M6b5), ("M6c", M6c), ("M6d", M6d),
                       ("M6e", M6e), ("P2", P2)):
        if mut == COMPLIANT:
            print(f"  selftest arm6 {label}: mutation did not apply "
                  f"(fixture drifted) -- FAIL")
            ok = False

    cases = [
        ("arm6 compliant", COMPLIANT, None),
        ("arm6a M6a stop transport left unguarded", M6a, "arm6a"),
        ("arm6b M6b fromFile fallback restored", M6b, "arm6b"),
        ("arm6b M6b2 catch does not return", M6b2, "arm6b"),
        ("arm6b M6b3 failure path never toasts", M6b3, "arm6b"),
        ("arm6b M6b4 failure path never logs", M6b4, "arm6b"),
        ("arm6b M6b5 toast drops the shared string resource", M6b5, "arm6b"),
        ("arm6 precision P2 comments mention the calls", P2, None),
        ("arm6a M6c no transport call site (fail-closed anchor)", M6c, "arm6a"),
        ("arm6b M6d shareMediaFile gone (fail-closed anchor)", M6d, "arm6b"),
        ("arm6b M6e default-value lambda hides the body (wide3)", M6e, "arm6b"),
    ]
    for name, src, want_key in cases:
        problems = check_inline_player_guards(strip_comments_and_strings(src))
        red = len(problems) > 0
        if want_key is None:
            mark = "ok" if not red else "FAIL"
            print(f"  selftest {name}: {'RED' if red else 'GREEN'} "
                  f"(expect GREEN) {mark}")
            if red:
                ok = False
        else:
            fired = any(p.startswith(want_key) for p in problems)
            mark = "ok" if (red and fired) else "FAIL"
            print(f"  selftest {name}: {'RED' if red else 'GREEN'} "
                  f"(expect RED via {want_key}) {mark}")
            if not (red and fired):
                ok = False
        for p in problems:
            print(f"      - {p}")

    # mini-tree fixture: InlineMediaPlayer.kt absent -> judgement skipped
    if check_inline_player_guards(None):
        print("  selftest arm6 mini tree (no InlineMediaPlayer.kt): expected "
              "GREEN (skipped), got RED -- FAIL")
        ok = False
    else:
        print("  selftest arm6 mini tree (no InlineMediaPlayer.kt): GREEN (skipped) ok")
    return ok


# ─── Arm 8 (dv19-B02 / dv19-B06): media hosts keyed on their data source ────
# A native media host (an AndroidView that renders audio/video, or the viewer of
# a markdown media block) must re-mount on the DATA SOURCE it renders, and must
# not open or resolve that source during composition. All four hosts of this
# family carried an unkeyed view or unkeyed playback state, and two markdown
# renderers still ran setDataSource()/resolveMediaFile() inside remember {} --
# MediaPlayer.setDataSource opens the fd and parses the container header, and the
# markdown resolver walks the session tree: both are main-thread IO in
# composition.

_STREAM_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt"
)
_MARKDOWN_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/MarkdownText.kt"
)
_CHAT_UI_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatAssistantMessageUI.kt"
)

# [dv21-B04 B-06] The two non-markdown image surfaces that hand a model to Coil.
# They are NOT media hosts (no AndroidView/MediaPlayer), so they are not in
# _VIEW_HOSTS -- but they carry the same decode-ceiling question as arm8f, and
# one of them used to build its own ImageLoader (thread leak + missing fetcher
# / mtime keyer, i.e. `minis://` attachments failed to Copy/Share/Save).
_FULLSCREEN_IMG_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/components/FullscreenImageViewer.kt"
)
_GALLERY_IMG_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/ui/components/ImageGalleryViewer.kt"
)
_IMAGE_VIEWER_RELS = (_FULLSCREEN_IMG_REL, _GALLERY_IMG_REL)

# What a key must carry to identify a media source: the path/uri a card was
# handed, the resolved host File, or a named derivation of one.
_SOURCE_HINT = re.compile(r"\bfilePath\b|\bfile\b|absolutePath|SourceKey\b")

# (file, declaration, label) — hosts whose native view must be re-keyed.
_VIEW_HOSTS = (
    (INLINE_PLAYER_REL, r"fun\s+InlineVideoPlayer\s*\(", "InlineVideoPlayer"),
    (INLINE_PLAYER_REL, r"private\s+fun\s+FullscreenVideoContent\s*\(",
     "FullscreenVideoContent"),
)

# (file, declaration, label) — hosts whose playback state must be re-keyed.
_STATE_HOSTS = (
    (INLINE_PLAYER_REL, r"fun\s+InlineVideoPlayer\s*\(", "InlineVideoPlayer"),
    (INLINE_PLAYER_REL, r"fun\s+InlineAudioPlayer\s*\(", "InlineAudioPlayer"),
    (INLINE_PLAYER_REL, r"private\s+fun\s+FullscreenVideoContent\s*\(",
     "FullscreenVideoContent"),
    (_STREAM_REL, r"fun\s+RenderMdAudio\s*\(", "RenderMdAudio"),
    (_MARKDOWN_REL, r"fun\s+MinisAudioBlock\s*\(", "MinisAudioBlock"),
)


def enclosing_key(clean: str, idx: int):
    """(args, start, end) of the smallest `key(<args>) { … }` block containing idx."""
    best = None
    n = len(clean)
    for m in re.finditer(r"\bkey\s*\(", clean):
        open_idx = clean.find("(", m.start())
        depth, i = 1, open_idx + 1
        while i < n and depth:
            if clean[i] == "(":
                depth += 1
            elif clean[i] == ")":
                depth -= 1
            i += 1
        if depth:
            continue
        args = clean[open_idx + 1:i - 1]
        brace = clean.find("{", i)
        if brace < 0:
            continue
        start, end = brace_span(clean, brace)
        if start < idx < end and (best is None or (end - start) < (best[2] - best[1])):
            best = (args, start, end)
    return best


def arg_lambda_body(text: str, call_idx: int, arg: str):
    """Body of the `<arg> = { … }` lambda passed to the call at call_idx."""
    open_idx = text.find("(", call_idx)
    if open_idx < 0:
        return None
    depth, i, n = 1, open_idx + 1, len(text)
    while i < n and depth:
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
        i += 1
    args = text[open_idx:i]
    m = re.search(r"\b%s\s*=\s*\{" % re.escape(arg), args)
    if not m:
        return None
    start, end = brace_span(text, open_idx + m.end() - 1)
    return text[start:end]


def remember_spans(clean: str):
    """Char spans of every `remember …{ … }` trailing-lambda block."""
    spans = []
    n = len(clean)
    for m in re.finditer(r"\bremember\b", clean):
        i = m.end()
        while i < n and clean[i].isspace():
            i += 1
        if i < n and clean[i] == "(":
            depth = 0
            while i < n:
                if clean[i] == "(":
                    depth += 1
                elif clean[i] == ")":
                    depth -= 1
                    if depth == 0:
                        i += 1
                        break
                i += 1
            while i < n and clean[i].isspace():
                i += 1
        if i < n and clean[i] == "{":
            spans.append(brace_span(clean, i))
    return spans


def io_block_spans(clean: str):
    """Char spans of every `withContext(Dispatchers.IO) { … }` block."""
    spans = []
    for m in re.finditer(r"withContext\s*\(\s*Dispatchers\.IO\s*\)\s*\{", clean):
        spans.append(brace_span(clean, clean.find("{", m.end() - 1)))
    return spans


def unkeyed_delegates(body: str):
    """(unkeyed names, total) for every `var x by remember …` state carrier."""
    bad = []
    total = 0
    for m in re.finditer(r"\bvar\s+(\w+)\s+by\s+remember\b", body):
        total += 1
        i = m.end()
        while i < len(body) and body[i].isspace():
            i += 1
        if not (i < len(body) and body[i] == "("):
            bad.append(m.group(1))
    return bad, total


def check_media_host_keys(sources):
    """Arm 8 (dv19-B02/B06). `sources` maps a file path to its
    comment/string-stripped text, or None when the file is absent (mini tree ->
    that sub-arm is skipped)."""
    problems = []

    # 8a: every media host that renders a view is re-mounted on its data source.
    for rel, decl, label in _VIEW_HOSTS:
        clean = sources.get(rel)
        if clean is None:
            continue
        body = function_body(clean, decl)
        if not body:
            continue
        views = [m.start() for m in re.finditer(r"\bAndroidView\s*\(", body)]
        for v in views:
            key = enclosing_key(body, v)
            if key is None:
                problems.append(
                    "arm8a: the AndroidView of %s is NOT wrapped in key(...) -- the "
                    "factory runs once, so a reused composition slot keeps rendering "
                    "(and, for video, keeps PLAYING) the previous data source while "
                    "the state around it has already reset; re-point this guard if the "
                    "host moved" % label)
            elif not _SOURCE_HINT.search(key[0]):
                problems.append(
                    "arm8a: the key(...) around the AndroidView of %s does not carry "
                    "the data source (args: %r) -- a key derived from anything else "
                    "never changes when the media does" % (label, key[0].strip()))

    # 8b: no playback state survives a data-source change.
    for rel, decl, label in _STATE_HOSTS:
        clean = sources.get(rel)
        if clean is None:
            continue
        body = function_body(clean, decl)
        if not body:
            problems.append(
                "arm8b: `%s` is gone -- the playback-state key contract lost its "
                "anchor, re-point this guard" % label)
            continue
        bad, total = unkeyed_delegates(body)
        if not total:
            problems.append(
                "arm8b: `%s` declares no `var ... by remember` state at all -- "
                "fail-closed anchor, re-point this guard" % label)
        elif bad:
            problems.append(
                "arm8b: %s keeps %s without a data-source key -- a reused host then "
                "shows the previous file's play state / position until its own "
                "callback lands" % (label, ", ".join(sorted(set(bad)))))

    # 8c: no media source is opened or resolved during composition.
    for rel in (_STREAM_REL, _MARKDOWN_REL):
        clean = sources.get(rel)
        if clean is None:
            continue
        name = rel.rsplit("/", 1)[-1]
        spans = remember_spans(clean)
        for m in re.finditer(
                r"\b(setDataSource|resolveMediaFile|resolveMdMediaFile)\s*\(", clean):
            if inside_any(spans, m.start()):
                problems.append(
                    "arm8c: %s calls %s(...) inside remember{} -- setDataSource opens "
                    "the file descriptor and parses the container header and the "
                    "markdown resolver walks the session directory: both are "
                    "main-thread IO in the composition phase" % (name, m.group(1)))

    # 8d: the resolver runs on IO, through the one shared seam.
    for rel in (_STREAM_REL, _MARKDOWN_REL):
        clean = sources.get(rel)
        if clean is None:
            continue
        name = rel.rsplit("/", 1)[-1]
        seam_calls = [m for m in re.finditer(r"\brememberMdMediaFile\s*\(", clean)
                      if not re.search(r"fun\s+$", clean[:m.start()])]
        if not seam_calls:
            problems.append(
                "arm8d: %s no longer resolves through the shared rememberMdMediaFile "
                "seam (definition aside, no call site) -- a second, synchronous "
                "resolver per renderer is how this family drifted apart" % name)
        io = io_block_spans(clean)
        spans = remember_spans(clean)
        for m in re.finditer(r"\bresolve(?:Md)?MediaFile\s*\(", clean):
            if re.search(r"fun\s+$", clean[:m.start()]):
                continue  # the declaration itself, not a call site
            if inside_any(spans, m.start()):
                continue  # already reported by arm8c
            if not inside_any(io, m.start()):
                problems.append(
                    "arm8d: resolve*MediaFile(...) in %s is not inside "
                    "withContext(Dispatchers.IO) -- the session-directory walk then "
                    "runs on the composition thread" % name)

    # 8e: the native full-content viewer follows its content instead of being
    # rebuilt around it (or frozen on it).
    clean = sources.get(_CHAT_UI_REL)
    if clean is not None:
        body = function_body(clean, r"private\s+fun\s+ThinkingFullContentDialog\s*\(")
        if not body:
            problems.append(
                "arm8e: `ThinkingFullContentDialog` is gone -- the full-content "
                "viewer contract lost its anchor, re-point this guard")
        else:
            views = [m.start() for m in re.finditer(r"\bAndroidView\s*\(", body)]
            if not views:
                problems.append(
                    "arm8e: ThinkingFullContentDialog hosts no AndroidView -- "
                    "re-point this guard")
            for v in views:
                key = enclosing_key(body, v)
                if key is not None and re.search(r"\bcontent\b", key[0]):
                    problems.append(
                        "arm8e: the thinking viewer's AndroidView is keyed on its "
                        "content -- the node holds >100k chars, so rebuilding it on "
                        "every appended token throws away the reader's scroll offset "
                        "and selection; sync through update{} instead")
                upd = arg_lambda_body(body, v, "update")
                if upd is None:
                    problems.append(
                        "arm8e: the thinking viewer's AndroidView has no update{} "
                        "block -- the factory runs once, so a dialog opened while the "
                        "block was still streaming (or after a theme flip) keeps the "
                        "text and colour it was born with")
                else:
                    if "content" not in upd:
                        problems.append(
                            "arm8e: the update{} block never re-applies `content` -- "
                            "the viewer stays frozen on its first snapshot")
                    if "textColor" not in upd:
                        problems.append(
                            "arm8e: the update{} block never re-applies the text "
                            "colour -- a theme flip leaves the viewer on the old one")
    # 8d (block-level, dv20-B05): the file-level check above only sees "at
    # least one call in the file", so a SECOND media block could re-introduce
    # its own resolver and the gate stays green -- exactly the blind spot the
    # image block lived in (MinisImageBlock had no seam while its video/audio
    # siblings mounted it). Every media block in the markdown renderer must
    # mount the seam itself.
    clean = sources.get(_MARKDOWN_REL)
    if clean is not None:
        name = _MARKDOWN_REL.rsplit("/", 1)[-1]
        block_decls = list(re.finditer(
            r"fun\s+(\w+)\s*\(\s*block\s*:\s*[\w.]*\.(?:Image|Video|Audio)\s*\)", clean))
        if not block_decls:
            problems.append(
                "arm8d: no media block declarations found in %s -- the "
                "block-level seam anchor moved, re-point this guard" % name)
        for m in block_decls:
            fname = m.group(1)
            body = function_body(clean, r"fun\s+%s\s*\(" % re.escape(fname))
            if body and not re.search(r"\brememberMdMediaFile\s*\(", body):
                problems.append(
                    "arm8d: %s in %s renders its media without the shared "
                    "rememberMdMediaFile seam -- the block resolves its own "
                    "path (or none) while its media siblings mount the seam, "
                    "so the same markdown renders differently per block type"
                    % (fname, name))

    # 8f (dv20-B05): every Coil image request in a markdown renderer is
    # decode-capped through the shared limitDisplaySize() ceiling, and the
    # image composable is handed the built request -- not the raw url/file.
    # The bare `model = block.url` shape routes through the AsyncImage fetcher
    # with no size hint: the vertically-scrollable column leaves Coil's height
    # constraint unbounded, so a tall image decodes at intrinsic size and can
    # exceed the RecordingCanvas draw ceiling (DisplayBitmapLimits documents
    # the crash).
    def _paren_args(text, idx):
        p = text.find("(", idx)
        if p < 0:
            return None
        depth = 0
        for j in range(p, min(len(text), p + 4000)):
            if text[j] == "(":
                depth += 1
            elif text[j] == ")":
                depth -= 1
                if depth == 0:
                    return text[p + 1:j]
        return None

    for rel in (_STREAM_REL, _MARKDOWN_REL) + _IMAGE_VIEWER_RELS:
        clean = sources.get(rel)
        if clean is None:
            continue
        name = rel.rsplit("/", 1)[-1]
        builders = list(re.finditer(r"ImageRequest\s*\.\s*Builder\s*\(", clean))
        images = list(re.finditer(r"\b(?:Subcompose)?AsyncImage\s*\(", clean))
        if images and not builders:
            problems.append(
                "arm8f: %s hands Coil a model with no ImageRequest construction "
                "in the file -- the decode-size ceiling anchor moved, re-point "
                "this guard" % name)
        for b in builders:
            nxt = re.search(r"\.build\s*\(", clean[b.start():b.start() + 800])
            if nxt is None:
                problems.append(
                    "arm8f: an ImageRequest in %s is never .build()-finished "
                    "-- re-point this guard" % name)
                continue
            chain = clean[b.start():b.start() + nxt.end()]
            if "limitDisplaySize" not in chain:
                problems.append(
                    "arm8f: an ImageRequest in %s is built without the shared "
                    "limitDisplaySize() ceiling -- the vertically-scrollable "
                    "column leaves Coil's height constraint unbounded, so a "
                    "tall image decodes at intrinsic size and can exceed the "
                    "RecordingCanvas draw ceiling" % name)
        for i in images:
            args = _paren_args(clean, i.start())
            if args is None:
                continue
            mm = re.search(r"\bmodel\s*=\s*([^,\n]+)", args)
            if mm is None:
                continue
            expr = mm.group(1).strip()
            if re.search(r"\burl\b|block\.url", expr) or expr == "file":
                problems.append(
                    "arm8f: an image composable in %s hands Coil the raw media "
                    "(%s) instead of the built ImageRequest -- the "
                    "session-scoped resolver and the decode ceiling are both "
                    "bypassed" % (name, expr))
            elif re.match(r"^\w+$", expr):
                decl = re.search(r"val\s+%s\b" % re.escape(expr), clean)
                if decl is None or "ImageRequest.Builder" not in clean[decl.start():decl.start() + 240]:
                    problems.append(
                        "arm8f: the model handed to Coil in %s (%s) has no "
                        "ImageRequest construction behind it -- the decode-size "
                        "ceiling is unchecked for this source" % (name, expr))

    # 8f (image viewers, dv21-B04 B-06): the loader must be THE process
    # singleton and the request must be ceiling-capped. `ImageLoader(context)`
    # here built a private loader with its own executor (never shut down, one
    # thread leak per Copy/Share/Save) and none of the components
    # `MinisApp.newImageLoader()` installs -- so `minis://` models had no
    # fetcher and an in-place rewritten file had no mtime keyer.
    for rel in _IMAGE_VIEWER_RELS:
        clean = sources.get(rel)
        if clean is None:
            continue
        name = rel.rsplit("/", 1)[-1]
        anchors = list(re.finditer(r"ImageRequest\s*\.\s*Builder\s*\(", clean)) + \
            list(re.finditer(r"\b(?:Subcompose)?AsyncImage\s*\(", clean))
        if not anchors:
            problems.append(
                "arm8f: no image-request anchor left in %s -- re-point this "
                "guard" % name)
            continue
        for m in re.finditer(r"(?<!Coil\.)\bImageLoader\s*\(", clean):
            problems.append(
                "arm8f: %s builds its own ImageLoader -- that loader registers "
                "none of the components MinisApp.newImageLoader() installs "
                "(minis:// fetcher, mtime keyer), and its executor is never "
                "shut down; resolve the process singleton through "
                "Coil.imageLoader( instead" % name)

    # 8g [R3-t04 11-2]: 解析出来的 file 必须真的进 ImageRequest 的 .data(…) ——
    # 「解析出来」和「用上」是两件事：`file ?: block.url` 改成 `block.url` 后，
    # 会话作用域的解析结果被丢掉、Coil 拿裸 url 去取，而 arm8d（缝挂着）与
    # arm8f（model= 是 build 出来的 request、有 size 天花板）都还是绿的。
    for rel in (_MARKDOWN_REL, _STREAM_REL):
        clean = sources.get(rel)
        if clean is None:
            continue
        name = rel.rsplit("/", 1)[-1]
        for m in re.finditer(r"\b([A-Za-z_]\w*)\s*=\s*rememberMdMediaFile\s*\(", clean):
            var = m.group(1)
            span = _enclosing_fun_span(clean, m.start())
            body = clean[span[0]:span[1]] if span else clean
            builders = list(re.finditer(r"ImageRequest\s*\.\s*Builder\s*\(", body))
            if not builders:
                continue          # 视频/音频块走 setDataSource(file.path)，不属本判据
            data_args = []
            for b in builders:
                nxt = re.search(r"\.build\s*\(", body[b.start():])
                chain = body[b.start(): b.start() + (nxt.end() if nxt else 1200)]
                for d in re.finditer(r"\.\s*data\s*\(", chain):
                    args = _paren_args(chain, d.start())
                    if args is not None:
                        data_args.append(args)
            if not data_args:
                problems.append(
                    "arm8g: %s resolves `%s` through rememberMdMediaFile but the "
                    "ImageRequest in that function never calls .data(...) -- the "
                    "session-scoped file is dropped while the seam call and the built "
                    "request both still look right" % (name, var))
            elif not any(re.search(r"(?<![\w.])%s\b" % re.escape(var), a) for a in data_args):
                problems.append(
                    "arm8g: the ImageRequest in %s does not take the resolved file `%s` "
                    "as its .data(...) source -- the resolver result is discarded and the "
                    "raw url (no session path, no mtime key) is fetched instead"
                    % (name, var))
    return problems


def run_seam_selftest() -> bool:
    """[R3-t04] 第三轮（dual-gate）缝臂：11-1 uri 交出 / 11-3 状态机转换。

    11-2（arm8g）的臂放在 run_media_host_keys_selftest 里，跟着那棵媒体树一起判
    —— 它只在「同一函数既有 rememberMdMediaFile 又有 ImageRequest」时生效。
    """
    ok = True
    MANAGER = '''
object MediaPlayerManager {
    enum class PlayerState { IDLE, PLAYING, PAUSED, STOPPED }
    private data class Session(val player: MediaPlayer, @Volatile var state: PlayerState)

    fun pause(sessionId: String): PlayerOutcome {
        val session = sessions[sessionId]
            ?: return PlayerOutcome.failure("no_session", "no session")
        if (session.state != PlayerState.PLAYING) {
            return PlayerOutcome.failure("not_playing", "not playing")
        }
        return try {
            session.player.pause()
            session.state = PlayerState.PAUSED
            PlayerOutcome.ok(state = PlayerState.PAUSED.name)
        } catch (e: Exception) {
            PlayerOutcome.failure("player_failed", e.message ?: "unknown")
        }
    }

    fun resume(sessionId: String): PlayerOutcome {
        val session = sessions[sessionId]
            ?: return PlayerOutcome.failure("no_session", "no session")
        if (session.state != PlayerState.PAUSED) {
            return PlayerOutcome.failure("not_paused", "not paused")
        }
        return try {
            session.player.start()
            session.state = PlayerState.PLAYING
            PlayerOutcome.ok(state = PlayerState.PLAYING.name)
        } catch (e: Exception) {
            PlayerOutcome.failure("player_failed", e.message ?: "unknown")
        }
    }
}
'''
    INLINE = '''
private fun shareMediaFile(context: android.content.Context, file: File, mime: String) {
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (_: Throwable) {
        Toast.makeText(context, "failed", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    com.rikkaminis.app.util.ExternalIntent.share(context, intent, file.name) { t ->
        Toast.makeText(context, "failed", Toast.LENGTH_SHORT).show()
    }
}
'''
    # 11-3 pause() 变成幂等空转（把 state 设成它刚要求过的那个成员）
    pause_noop = MANAGER.replace(
        "            session.state = PlayerState.PAUSED",
        "            session.state = PlayerState.PLAYING")
    # 11-3 上报的状态与真正赋给 session 的不一致
    pause_lies = MANAGER.replace(
        "PlayerOutcome.ok(state = PlayerState.PAUSED.name)",
        "PlayerOutcome.ok(state = PlayerState.PLAYING.name)")
    # 11-3 互为逆被破坏：resume 要求它自己刚设的那个成员
    resume_wrong_guard = MANAGER.replace(
        "if (session.state != PlayerState.PAUSED) {",
        "if (session.state != PlayerState.PLAYING) {")
    for name, sample, want in (
        ("r3-state compliant (pause PLAYING→PAUSED, resume PAUSED→PLAYING)", MANAGER, None),
        ("r3-state pause is an idempotent no-op (11-3)", pause_noop, "idempotent"),
        ("r3-state pause reports the wrong member", pause_lies, "reports state"),
        ("r3-state resume guards its own target member", resume_wrong_guard,
         "not mutually inverse"),
    ):
        got = check_player_state_machine(sample)
        if want is None:
            mark = "ok" if not got else "FAIL"
            print("  selftest %s: %s (expect GREEN) %s"
                  % (name, "RED" if got else "GREEN", mark))
            if got:
                ok = False
        else:
            hit = any(want in p for p in got) and all(p.startswith("arm9:") for p in got)
            print("  selftest %s: %s (expect arm9 / %r) %s"
                  % (name, "RED" if got else "GREEN", want, "ok" if hit else "FAIL"))
            if not hit:
                ok = False
        for p in got:
            print("      - " + p)

    # 11-1：uri 解析出来却不交给任何人
    detached = INLINE.replace("        putExtra(Intent.EXTRA_STREAM, uri)\n", "")
    # 精确度：换一种载体（setDataAndType）也必须算交出去了
    other_carrier = INLINE.replace(
        "        putExtra(Intent.EXTRA_STREAM, uri)",
        "        setDataAndType(uri, mime)")
    for name, sample, want in (
        ("r3-share compliant (EXTRA_STREAM)", INLINE, None),
        ("r3-share precision (setDataAndType carrier)", other_carrier, None),
        ("r3-share uri detached (11-1)", detached, "never handed on"),
    ):
        got = check_share_handoff(sample)
        if want is None:
            mark = "ok" if not got else "FAIL"
            print("  selftest %s: %s (expect GREEN) %s"
                  % (name, "RED" if got else "GREEN", mark))
            if got:
                ok = False
        else:
            hit = any(want in p for p in got) and all(p.startswith("arm11:") for p in got)
            print("  selftest %s: %s (expect arm11 / %r) %s"
                  % (name, "RED" if got else "GREEN", want, "ok" if hit else "FAIL"))
            if not hit:
                ok = False
        for p in got:
            print("      - " + p)

    # 形态边界：整份文件里没有 FileProvider 站点 = 显式红（不是静默通过）
    if not check_share_handoff("private fun nothing() { }"):
        print("  selftest r3-share fail-closed (no getUriForFile): expected RED -- FAIL")
        ok = False
    else:
        print("  selftest r3-share fail-closed (no getUriForFile): RED ok")
    print("MEDIAPLAYER_SEAM_SELFTEST=" + ("PASS" if ok else "FAIL")
          + " (3 seams: 11-1 uri handed on / 11-2 arm8g resolved file is the data "
            "source / 11-3 pause↔resume are mutually inverse; 7 arms + 1 fail-closed)")
    return ok

def run_media_host_keys_selftest() -> bool:
    """Arm 8 fixtures (dv19-B02/B06). Each dirty case must go red through
    EXACTLY the expected sub-arm (a fixture that also trips a neighbour means
    the arms are not separable), the precision case must stay green (comments
    that mention the forbidden shapes are not the defect), and every fixture is
    checked against the un-anchored shape it is meant to model."""
    COMPLIANT_PLAYER = '''
@Composable
fun InlineVideoPlayer(filePath: String) {
    val mediaController = remember(filePath) { android.widget.MediaController(ctx) }
    var videoAspectRatio by remember(filePath) { mutableStateOf<Float?>(null) }
    Surface {
        key(inlineMediaSourceKey(filePath)) {
            AndroidView(
                factory = { ctx -> android.widget.VideoView(ctx) },
                onRelease = { view -> view.stopPlayback() },
            )
        }
    }
}

@Composable
fun InlineAudioPlayer(filePath: String) {
    var isPlaying by remember(filePath) { mutableStateOf(false) }
    var progress by remember(filePath) { mutableFloatStateOf(0f) }
    var duration by remember(filePath) { mutableStateOf("0:00") }
    var position by remember(filePath) { mutableStateOf("0:00") }
    var error by remember(filePath) { mutableStateOf<String?>(null) }
    var prepared by remember(filePath) { mutableStateOf(false) }
    var pendingPlay by remember(filePath) { mutableStateOf(false) }
}

@Composable
private fun FullscreenVideoContent(file: File, onDismiss: () -> Unit) {
    val fileKey = resolvedMediaSourceKey(file)
    var videoView by remember(fileKey) { mutableStateOf<android.widget.VideoView?>(null) }
    var isPlaying by remember(fileKey) { mutableStateOf(true) }
    var durationMs by remember(fileKey) { mutableStateOf(0) }
    var positionMs by remember(fileKey) { mutableStateOf(0) }
    var scrubbing by remember(fileKey) { mutableStateOf(false) }
    var scrubTarget by remember(fileKey) { mutableFloatStateOf(0f) }
    var showControls by remember(fileKey) { mutableStateOf(true) }
    var dragOffsetPx by remember(fileKey) { mutableFloatStateOf(0f) }
    Box {
        key(resolvedMediaSourceKey(file)) {
            AndroidView(factory = { ctx -> android.widget.VideoView(ctx) })
        }
    }
}
'''
    COMPLIANT_STREAM = '''
@Composable
internal fun rememberMdMediaFile(url: String, sessionId: String?): File? {
    return produceState<File?>(initialValue = null, url, sessionId) {
        value = withContext(Dispatchers.IO) { resolveMdMediaFile(context, url, sessionId) }
    }.value
}

internal fun resolveMdMediaFile(context: Context, url: String, sessionId: String?): File? {
    return File(url)
}

@Composable
fun RenderMdVideo(block: MdBlock.Video) {
    val file = rememberMdMediaFile(block.url, sessionId)
    AndroidView(factory = { ctx -> android.widget.VideoView(ctx) })
}

@Composable
fun RenderMdAudio(block: MdBlock.Audio) {
    val file = rememberMdMediaFile(block.url, sessionId)
    val player = remember(resolvedMediaSourceKey(file)) { MediaPlayer() }
    var prepared by remember(resolvedMediaSourceKey(file)) { mutableStateOf(false) }
    var isPlaying by remember(resolvedMediaSourceKey(file)) { mutableStateOf(false) }
    var positionMs by remember(resolvedMediaSourceKey(file)) { mutableStateOf(0) }
    LaunchedEffect(player, resolvedMediaSourceKey(file)) {
        prepared = withContext(Dispatchers.IO) {
            player.setDataSource(file!!.absolutePath)
            true
        }
    }
}

@Composable
fun RenderMdImage(block: MdBlock.Image) {
    val file = rememberMdMediaFile(block.url, sessionId)
    val imageRequest = remember(file, block.url) {
        ImageRequest.Builder(context)
            .data(file ?: block.url)
            .limitDisplaySize()
            .build()
    }
    SubcomposeAsyncImage(model = imageRequest)
}
'''
    COMPLIANT_MARKDOWN = '''
@Composable
fun MinisVideoBlock(block: MdBlock.Video) {
    val file = rememberMdMediaFile(block.url, sessionId)
    AndroidView(factory = { ctx -> android.widget.VideoView(ctx) })
}

@Composable
fun MinisAudioBlock(block: MdBlock.Audio) {
    val file = rememberMdMediaFile(block.url, sessionId)
    val player = remember(resolvedMediaSourceKey(file)) { MediaPlayer() }
    var prepared by remember(resolvedMediaSourceKey(file)) { mutableStateOf(false) }
    var isPlaying by remember(resolvedMediaSourceKey(file)) { mutableStateOf(false) }
    var positionMs by remember(resolvedMediaSourceKey(file)) { mutableStateOf(0) }
    LaunchedEffect(player, resolvedMediaSourceKey(file)) {
        prepared = withContext(Dispatchers.IO) {
            player.setDataSource(file!!.absolutePath)
            true
        }
    }
}

@Composable
fun MinisImageBlock(block: MdBlock.Image) {
    val context = LocalContext.current
    val sessionId = LocalMarkdownSessionId.current
    val file = rememberMdMediaFile(block.url, sessionId)
    val imageRequest = remember(file, block.url) {
        ImageRequest.Builder(context)
            .data(file ?: block.url)
            .limitDisplaySize()
            .build()
    }
    AsyncImage(model = imageRequest)
}
'''
    COMPLIANT_CHAT = '''
@Composable
private fun ThinkingFullContentDialog(content: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        AndroidView(
            factory = { ctx -> android.widget.ScrollView(ctx) },
            update = { scrollView ->
                val textView = scrollView.getChildAt(0) as? android.widget.TextView
                if (textView != null) {
                    textView.setTextColor(textColor.toArgb())
                    if (shouldSyncFullContentText(textView.text, content)) {
                        textView.text = content
                    }
                }
            },
        )
    }
}
'''

    # [dv21-B04 B-06] the two non-markdown image surfaces
    COMPLIANT_FULLSCREEN = '''
package com.rikkaminis.app.ui.components

internal suspend fun loadBitmap(context: Context, model: Any): Bitmap? {
    val loader = Coil.imageLoader(context.applicationContext)
    val req = ImageRequest.Builder(context)
        .data(model)
        .allowHardware(false)
        .limitDisplaySize()
        .build()
    val result = loader.execute(req)
    return (result as? SuccessResult)?.drawable?.toBitmap()
}
'''
    COMPLIANT_GALLERY = '''
package com.rikkaminis.app.ui.components

@Composable
private fun GalleryPage(item: GalleryItem) {
    val context = LocalContext.current
    val imageRequest = remember(item.model) {
        ImageRequest.Builder(context)
            .data(item.model)
            .limitDisplaySize()
            .build()
    }
    Box {
        AsyncImage(model = imageRequest, contentDescription = null)
    }
}
'''
    # the pre-fix shapes, verbatim
    M8f4 = COMPLIANT_GALLERY.replace("            .limitDisplaySize()\n", "")
    M8f5 = COMPLIANT_FULLSCREEN.replace(
        "Coil.imageLoader(context.applicationContext)",
        "ImageLoader(context.applicationContext)")
    M8f6 = COMPLIANT_GALLERY.replace(
        "AsyncImage(model = imageRequest", "AsyncImage(model = item.url")
    M8f7 = COMPLIANT_FULLSCREEN.replace("        .limitDisplaySize()\n", "")
    P3 = COMPLIANT_GALLERY.replace(
        "    val imageRequest = remember(item.model) {",
        "    // NEVER: model = item.url -- the decode ceiling disappears\n"
        "    // NEVER: ImageLoader(context.applicationContext) -- private loader\n"
        "    val imageRequest = remember(item.model) {")

    def tree(player=COMPLIANT_PLAYER, stream=COMPLIANT_STREAM,
             markdown=COMPLIANT_MARKDOWN, chat=COMPLIANT_CHAT,
             fullscreen=COMPLIANT_FULLSCREEN, gallery=COMPLIANT_GALLERY,
             missing=()):
        src = {
            INLINE_PLAYER_REL: player,
            _STREAM_REL: stream,
            _MARKDOWN_REL: markdown,
            _CHAT_UI_REL: chat,
            _FULLSCREEN_IMG_REL: fullscreen,
            _GALLERY_IMG_REL: gallery,
        }
        for rel in missing:
            src[rel] = None
        return src

    # 8a: the inline video host loses its key / keys on something else.
    M8a1 = COMPLIANT_PLAYER.replace(
        """        key(inlineMediaSourceKey(filePath)) {
            AndroidView(
                factory = { ctx -> android.widget.VideoView(ctx) },
                onRelease = { view -> view.stopPlayback() },
            )
        }""",
        """        AndroidView(
            factory = { ctx -> android.widget.VideoView(ctx) },
            onRelease = { view -> view.stopPlayback() },
        )""")
    M8a2 = COMPLIANT_PLAYER.replace(
        "key(inlineMediaSourceKey(filePath)) {", "key(mediaController) {")
    # the fullscreen host loses its key too
    M8a3 = COMPLIANT_PLAYER.replace(
        """        key(resolvedMediaSourceKey(file)) {
            AndroidView(factory = { ctx -> android.widget.VideoView(ctx) })
        }""",
        """        AndroidView(factory = { ctx -> android.widget.VideoView(ctx) })""")

    # 8b: one playback state loses its key (the view stays keyed).
    M8b1 = COMPLIANT_PLAYER.replace(
        "    var positionMs by remember(fileKey)",
        "    var positionMs by remember")
    M8b2 = COMPLIANT_PLAYER.replace(
        "    var isPlaying by remember(filePath)",
        "    var isPlaying by remember")
    # fail-closed anchor: the audio host disappears
    M8b3 = COMPLIANT_PLAYER.replace("fun InlineAudioPlayer(filePath: String) {",
                                    "fun inlineAudioPlayerGone(filePath: String) {")

    # 8c: a media source is opened / resolved inside remember {}.
    M8c1 = COMPLIANT_STREAM.replace(
        "    val player = remember(resolvedMediaSourceKey(file)) { MediaPlayer() }",
        "    val player = remember(resolvedMediaSourceKey(file)) {\n"
        "        MediaPlayer().apply { setDataSource(file!!.absolutePath) }\n"
        "    }")
    M8c2 = COMPLIANT_MARKDOWN.replace(
        "    val file = rememberMdMediaFile(block.url, sessionId)\n"
        "    val player = remember(resolvedMediaSourceKey(file)) { MediaPlayer() }",
        "    val file = remember(block.url, sessionId) {\n"
        "        resolveMediaFile(context, block.url, sessionId)\n"
        "    }\n"
        "    val player = remember(resolvedMediaSourceKey(file)) { MediaPlayer() }")

    # 8d: the resolver leaves the IO dispatcher / the shared seam disappears.
    M8d1 = COMPLIANT_STREAM.replace(
        "    val file = rememberMdMediaFile(block.url, sessionId)\n"
        "    val player = remember(resolvedMediaSourceKey(file)) { MediaPlayer() }",
        "    val file = resolveMdMediaFile(context, block.url, sessionId)\n"
        "    val player = remember(resolvedMediaSourceKey(file)) { MediaPlayer() }")
    M8d2 = COMPLIANT_MARKDOWN.replace(
        "val file = rememberMdMediaFile(block.url, sessionId)",
        "val file = File(block.url)")

    # 8e: the full-content viewer stops following its content.
    M8e1 = COMPLIANT_CHAT.replace(
        """            update = { scrollView ->
                val textView = scrollView.getChildAt(0) as? android.widget.TextView
                if (textView != null) {
                    textView.setTextColor(textColor.toArgb())
                    if (shouldSyncFullContentText(textView.text, content)) {
                        textView.text = content
                    }
                }
            },
""", "")
    M8e2 = COMPLIANT_CHAT.replace(
        """        AndroidView(
            factory = { ctx -> android.widget.ScrollView(ctx) },""",
        """        key(content) {
        AndroidView(
            factory = { ctx -> android.widget.ScrollView(ctx) },""")
    M8e3 = COMPLIANT_CHAT.replace(
        "                    textView.setTextColor(textColor.toArgb())\n", "")

    # precision: comments mention the forbidden shapes, the code is compliant.
    P1 = COMPLIANT_PLAYER.replace(
        "    val mediaController = remember(filePath) { "
        "android.widget.MediaController(ctx) }",
        "    // key(inlineMediaSourceKey(filePath)) is what re-mounts this host\n"
        "    // NEVER: setDataSource(file.absolutePath) inside remember { }\n"
        "    val mediaController = remember(filePath) { "
        "android.widget.MediaController(ctx) }")

    # dv20-B05 breakers: the image block (the third media block) loses the
    # seam / the ceiling / hands Coil the raw media
    M8d3 = COMPLIANT_MARKDOWN.replace(
        "    val file = rememberMdMediaFile(block.url, sessionId)\n"
        "    val imageRequest = remember(file, block.url) {",
        "    val imageRequest = remember(file, block.url) {")
    M8d4 = COMPLIANT_MARKDOWN.replace(
        "    AsyncImage(model = imageRequest)\n}",
        "    AsyncImage(model = imageRequest)\n}\n\n"
        "@Composable\n"
        "fun MinisGifBlock(block: MdBlock.Image) {\n"
        "    val imageRequest = remember(block.url) {\n"
        "        ImageRequest.Builder(context)\n"
        "            .data(block.url)\n"
        "            .limitDisplaySize()\n"
        "            .build()\n"
        "    }\n"
        "    AsyncImage(model = imageRequest)\n"
        "}")
    M8f1 = COMPLIANT_MARKDOWN.replace("            .limitDisplaySize()\n", "")
    M8f2 = COMPLIANT_MARKDOWN.replace(
        "AsyncImage(model = imageRequest)", "AsyncImage(model = block.url)")
    M8f3 = COMPLIANT_STREAM.replace("            .limitDisplaySize()\n", "")
    # 11-2：解析出来的 file 被丢掉，Coil 拿裸 url
    M8g1 = COMPLIANT_MARKDOWN.replace(
        ".data(file ?: block.url)", ".data(block.url)")
    # 精确度：绑定改名不影响判据（判据不认名字，认「谁被消费」）
    M8g_renamed = COMPLIANT_MARKDOWN.replace(
        "    val file = rememberMdMediaFile(block.url, sessionId)\n"
        "    val imageRequest = remember(file, block.url) {\n"
        "        ImageRequest.Builder(context)\n"
        "            .data(file ?: block.url)",
        "    val resolved = rememberMdMediaFile(block.url, sessionId)\n"
        "    val imageRequest = remember(resolved, block.url) {\n"
        "        ImageRequest.Builder(context)\n"
        "            .data(resolved ?: block.url)")
    assert M8g_renamed != COMPLIANT_MARKDOWN
    assert M8g1 != COMPLIANT_MARKDOWN
    P2 = COMPLIANT_MARKDOWN.replace(
        "fun MinisImageBlock(block: MdBlock.Image) {",
        "// NEVER: model = block.url with no ImageRequest -- decode ceiling gone\n"
        "fun MinisImageBlock(block: MdBlock.Image) {")

    cases = [
        ("arm8 compliant tree", tree(), None),
        ("arm8d M8d3 image block loses the seam (dv20-B05 baseline)", tree(markdown=M8d3), "arm8d"),
        ("arm8d M8d4 a second media block re-introduces its own resolver", tree(markdown=M8d4), "arm8d"),
        ("arm8f M8f1 image request loses the display ceiling", tree(markdown=M8f1), "arm8f"),
        ("arm8f M8f2 image block hands Coil the raw media", tree(markdown=M8f2), "arm8f"),
        ("arm8f M8f3 stream image branch loses the ceiling", tree(stream=M8f3), "arm8f"),
        ("arm8 precision P2 markdown comments mention the shapes", tree(markdown=P2), None),
        ("arm8f M8f4 gallery page loses the ceiling (dv21-B04 baseline)", tree(gallery=M8f4), "arm8f"),
        ("arm8f M8f5 fullscreen viewer builds its own ImageLoader (dv21 baseline)", tree(fullscreen=M8f5), "arm8f"),
        ("arm8f M8f6 gallery page hands Coil the raw media", tree(gallery=M8f6), "arm8f"),
        ("arm8f M8f7 fullscreen request loses the ceiling", tree(fullscreen=M8f7), "arm8f"),
        ("arm8 precision P3 gallery comments mention the shapes", tree(gallery=P3), None),
        ("arm8a M8a1 inline video host has no key", tree(player=M8a1), "arm8a"),
        ("arm8a M8a2 key does not carry the data source", tree(player=M8a2), "arm8a"),
        ("arm8a M8a3 fullscreen host has no key", tree(player=M8a3), "arm8a"),
        ("arm8b M8b1 fullscreen positionMs loses the key", tree(player=M8b1), "arm8b"),
        ("arm8b M8b2 audio isPlaying loses the file key", tree(player=M8b2), "arm8b"),
        ("arm8b M8b3 audio host gone (fail-closed anchor)", tree(player=M8b3), "arm8b"),
        ("arm8c M8c1 setDataSource back inside remember{}", tree(stream=M8c1), "arm8c"),
        ("arm8c M8c2 resolver back inside remember{}", tree(markdown=M8c2), ["arm8c", "arm8d"]),
        ("arm8d M8d1 resolver off the IO dispatcher", tree(stream=M8d1), "arm8d"),
        ("arm8d M8d2 shared resolver seam gone", tree(markdown=M8d2), "arm8d"),
        ("arm8e M8e1 update{} block removed", tree(chat=M8e1), "arm8e"),
        ("arm8e M8e2 viewer keyed on its content", tree(chat=M8e2), "arm8e"),
        ("arm8e M8e3 update{} stops syncing the colour", tree(chat=M8e3), "arm8e"),
        ("arm8g M8g1 the resolved file is dropped from .data() (11-2)",
         tree(markdown=M8g1), "arm8g"),
        ("arm8 precision P1 comments mention the shapes", tree(player=P1), None),
        ("arm8g precision P4 the resolved file is renamed, still consumed",
         tree(markdown=M8g_renamed), None),
    ]
    ok = True
    for name, src, want_arm in cases:
        problems = check_media_host_keys(
            {rel: (strip_comments_and_strings(text) if text is not None else None)
             for rel, text in src.items()})
        arms = {p.split(":", 1)[0] for p in problems}
        if want_arm is None:
            mark = "ok" if not problems else "FAIL"
            print(f"  selftest {name}: {'RED' if problems else 'GREEN'} "
                  f"(expect GREEN) {mark}")
            if problems:
                ok = False
        else:
            want = {want_arm} if isinstance(want_arm, str) else set(want_arm or ())
            mark = "ok" if arms == want else "FAIL"
            print(f"  selftest {name}: arms={sorted(arms) or ['GREEN']} "
                  f"(expect exactly {want_arm}) {mark}")
            if arms != want:
                ok = False
        for p in problems:
            print(f"      - {p}")

    # mini tree: the media-host files are absent -> judgement skipped
    if check_media_host_keys(tree(missing=(INLINE_PLAYER_REL, _STREAM_REL,
                                           _MARKDOWN_REL, _CHAT_UI_REL,
                                           _FULLSCREEN_IMG_REL, _GALLERY_IMG_REL))):
        print("  selftest arm8 mini tree (no media-host files): expected GREEN "
              "(skipped), got RED -- FAIL")
        ok = False
    else:
        print("  selftest arm8 mini tree (no media-host files): GREEN (skipped) ok")
    return ok


def main() -> int:
    if "--selftest" in sys.argv:
        print("mediaplayer_release_guard selftest:")
        if not run_selftest():
            print("FAIL: selftest")
            return 1
        print(MARKER)
        return 0

    target = ROOT / TARGET_REL
    if not target.exists():
        print(f"FAIL: missing {TARGET_REL}")
        return 1
    problems = check_arms(strip_comments_and_strings(
        target.read_text(encoding="utf-8", errors="replace")))
    inline = ROOT / INLINE_PLAYER_REL
    inline_clean = (strip_comments_and_strings(
        inline.read_text(encoding="utf-8", errors="replace"))
        if inline.exists() else None)
    problems += check_inline_player(inline_clean)
    problems += check_inline_player_guards(inline_clean)
    problems += check_share_handoff(inline_clean)
    problems += check_player_state_machine(strip_comments_and_strings(
        target.read_text(encoding="utf-8", errors="replace")))
    problems += check_fullscreen_video_window(inline_clean)
    media_sources = {}
    for rel in (INLINE_PLAYER_REL, _STREAM_REL, _MARKDOWN_REL,
                _CHAT_UI_REL) + _IMAGE_VIEWER_RELS:
        path = ROOT / rel
        media_sources[rel] = (strip_comments_and_strings(
            path.read_text(encoding="utf-8", errors="replace"))
            if path.exists() else None)
    problems += check_media_host_keys(media_sources)
    if problems:
        print("FAIL: MediaPlayerManager release/lock gaps")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("OK: MediaPlayerManager releases on every path (stop/release in "
          "separate try blocks), Session.state is @Volatile, and the "
          "play()/stop() session-map mutations sit inside synchronized(lock); "
          "every AndroidView in InlineMediaPlayer.kt releases via "
          "onRelease/stopPlayback and every onDismiss() branch terminates the "
          "player; the audio transport calls are try-guarded and the share "
          "path reports an undeclared path instead of faking a file:// uri; the "
          "fullscreen video Dialog installs a host-window DisposableEffect "
          "before opening and passes FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS to its "
          "own window; every media host is wrapped in key(<its data source>) so "
          "a reused composition slot re-mounts instead of playing the previous "
          "file, every playback state in those hosts is remembered on that same "
          "source, no renderer resolves or opens a media source inside "
          "remember{}, and the full-content thinking viewer follows its content "
          "through update{} (without keying the node on content)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
