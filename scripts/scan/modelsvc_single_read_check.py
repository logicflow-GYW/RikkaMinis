#!/usr/bin/env python3
r"""
Gate: FIX-H (deep-audit-1018 ISSUE-12a/b) — read the request body once, bound
the offload worker threads.

ISSUE-12a (ModelExecutionService.kt)
  The worker used to read `request.json` from disk THREE times — the queue-frame
  decision, the formal read and the ack-barrier decision — and parse it at each
  site, i.e. 2 redundant reads + 2 redundant parses of a body that can reach
  several MB (base64 input images). The rule: exactly ONE read of the body,
  routed through `CapturedRequestBody.capture`, with the captured/parsed body
  handed to both executors.

ISSUE-12b (NativeOffload.kt)
  The accept loop used to spawn one OS thread per accepted connection and let
  that thread park on the concurrency semaphore — a burst of N offloads created
  N threads (each ~1 MB native stack) while only `permits` made progress. The
  rule: the accept loop hands sockets to `BoundedWorkerPool` (no thread spawn in
  the loop, no `workerConcurrency.acquire()` anywhere in the file), and the pool
  spawns a worker only against the non-blocking `permits.tryAcquire()` bound and
  returns the permit when it drains empty.

[meta-audit batch7 B7-t03] The judgments used to be anchored on SPELLINGS —
`requestFile\s*\.\s*readText()`, `JSONObject(\s*requestFile`, a three-word
thread blacklist, the literal `pool.submit(client)` substring, `"if (spawn)"`
text, the `permits.release()` substring. Nine minimal patches kept every
spelling and deleted the effect (a different reader spelling, a capture whose
value is thrown away, `CompletableFuture.runAsync`, an `else` branch that
spawns anyway, `if (false) pool.submit(…)`, `if (false) permits.release()`).

  The judgments are therefore anchored on the DECISION POINT via the shared
  primitives: every content READ of the request body must sit inside the single
  `CapturedRequestBody.capture { … }` lambda (any receiver/constructor spelling
  counts — the read is located by what it touches, not by how it is spelled);
  the captured value must be the one the executors consume; `pool.submit` must
  be reachable (`path_feasible`); client handling must go through the pool; the
  worker spawn must be DOMINATED by the permit verdict; `permits.release()`
  must be reachable.

Usage: python3 scripts/scan/modelsvc_single_read_check.py [repo_root]
       python3 scripts/scan/modelsvc_single_read_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage/ROT
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError, mask_code  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool,
    const_value,
    enclosing_conditions,
    function_body,
    if_blocks,
    match_bracket,
    strip_parens,
)
from _kotlin_valueflow import (  # noqa: E402
    call_args,
    contains_ident,
    expr_end,
    guard_feasible,
    literal_view,
    path_feasible,
    value_sources,
)

SERVICE_REL = (
    "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"
)
OFFLOAD_REL = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/NativeOffload.kt"

CAPTURE_CALL = "CapturedRequestBody.capture { requestFile.readText() }"
SITE_PARSE = "JSONObject(requestFile.readText())"
EXEC_SIGS = (
    "private fun executeRun(req: JSONObject, dir: File): String",
    "private fun executeStreamingRun(req: JSONObject, dir: File)",
)
# The two consumers that make a capture meaningful: the queue-frame decision and
# the formal parse. A captured body that neither of them reads is a body that
# was read twice and used zero times.
CAPTURE_CONSUMERS = ("jsonOrThrow", "streamingFlagOrFalse")

POOL_SUBMIT = "pool.submit(client)"
OLD_ACQUIRE = "workerConcurrency.acquire()"
TRY_ACQUIRE = "permits.tryAcquire()"
POOL_DECL = "internal class BoundedWorkerPool"
CAP_CONST = "const val MAX_CONCURRENT_WORKERS = 2"
ACCEPT_LOOP = "runAcceptLoop"

# [B7-t03] Content reads, located by what they touch rather than by how the
# receiver is spelled. `requestFile.inputStream()…` (receiver), and
# `FileReader(requestFile)` / `Files.readAllBytes(requestFile.toPath())`
# (constructor argument) are the same act: pulling the body off disk.
READ_METHOD_RE = re.compile(
    r"\.\s*(?:readText|readLines|readBytes|inputStream|bufferedReader|useLines|"
    r"readChannel|copyTo|lines)\s*\("
)
READ_CTOR_RE = re.compile(
    r"\b(?:FileReader|FileInputStream|BufferedReader|Scanner|Files\s*\.\s*read\w*)\s*\("
)
CAPTURE_RE = re.compile(r"\bCapturedRequestBody\s*\.\s*capture\s*\{")
# Non-content touches of the request file (`exists()`, `delete()`, `length()`,
# `name`) are legitimate and must stay green.
META_CALL_RE = re.compile(
    r"\.\s*(?:exists|delete|deleteOnExit|isFile|canRead|length|getName|name|"
    r"absolutePath|canonicalPath|parent|toPath|toFile|toUri)\s*\("
)

# [B7-t03] Parallel-escaping primitives. The old detector whitelisted three
# spellings; `CompletableFuture.runAsync` carried the same defect past it.
PARALLEL_RE = re.compile(
    r"(?<![\w])(?:thread\s*[({]|Thread\s*\(|Thread\s*\.|Executors\b|"
    r"CompletableFuture\b|ForkJoinPool\b|GlobalScope\b|CoroutineScope\b|"
    r"(?:launch|async|runAsync)\s*[({]|newSingleThreadExecutor|newFixedThreadPool|"
    r"kotlin\s*\.\s*concurrent)"
)
BODY_READ_GUARD_RE = re.compile(r"\bif\s*\(\s*spawn\s*\)")




def _body_aliases(code):
    """Names bound to the request file (`val rf = requestFile`), 2 hops."""
    aliases = {"requestFile"}
    for _ in range(3):
        grew = False
        for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*=\s*(\w+)\s*(?:\n|;|$)", code):
            if m.group(2) in aliases and m.group(1) not in aliases:
                aliases.add(m.group(1))
                grew = True
        if not grew:
            break
    aliases.discard("requestFile")
    aliases = {"requestFile"} | aliases
    return aliases


def _names_in(text, names):
    return any(contains_ident(text, n) for n in names)


def _receiver_window(code, pos, back=90):
    """The receiver/argument window ending at `pos` (one statement at most)."""
    start = pos
    while start > 0 and code[start - 1] not in "\n;{}":
        start -= 1
    return code[max(start, pos - back):pos]


def body_read_sites(code):
    """[(pos, kind)] — every content read of the request body."""
    names = _body_aliases(code)
    out = []
    for m in READ_METHOD_RE.finditer(code):
        if META_CALL_RE.search(code[max(0, m.start() - 24):m.start() + 1]):
            continue
        window = _receiver_window(code, m.start())
        if _names_in(window, names):
            out.append((m.start(), "method"))
    for m in READ_CTOR_RE.finditer(code):
        spans = call_args(code, m.end() - 1)
        if not spans:
            # fail-closed: an unparseable read constructor is treated as a read
            out.append((m.start(), "ctor"))
            continue
        if any(_names_in(code[a:b], names) for a, b in spans):
            out.append((m.start(), "ctor"))
    return out, names


def capture_lambdas(code):
    """[(start, body_start, body_end)] for every `CapturedRequestBody.capture { … }`."""
    out = []
    for m in CAPTURE_RE.finditer(code):
        open_brace = m.end() - 1
        close = match_bracket(code, open_brace)
        if close > 0:
            out.append((m.start(), open_brace + 1, close))
    return out


def capture_binding(code, capture_start):
    """The local the capture result is bound to, or None.

    [R3 14 误伤] `val requestBody: CapturedRequestBody = capture { … }` 是 100%
    等价的正当写法（显式类型标注）；旧正则只认 `val x =`，把标注判成"结果未
    绑定"。类型标注是可选的，绑定的身份由名字决定。
    """
    line_start = code.rfind("\n", 0, capture_start) + 1
    head = code[line_start:capture_start]
    m = re.search(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+?)?=\s*$", head)
    return m.group(1) if m else None


def named_args(text, open_paren_pos):
    """`f(a = x, b = y)` → {name: (rhs_start, rhs_end)}。"""
    out = {}
    for a, b in call_args(text, open_paren_pos):
        m = re.match(r"\s*([A-Za-z_]\w*)\s*=(?!=)", text[a:b])
        if not m:
            continue
        rhs = a + m.end()
        out[m.group(1)] = (rhs, min(b, expr_end(text, rhs)))
    return out


def captured_body_methods(code):
    """{name: (body_start, body_end)} for the methods of `class CapturedRequestBody`."""
    m = re.search(r"\bclass\s+CapturedRequestBody\b", code)
    if not m:
        return None
    open_brace = code.find("{", m.end())
    if open_brace < 0:
        return None
    close = match_bracket(code, open_brace)
    if close < 0:
        return None
    klass = code[open_brace:close]
    out = {}
    for fm in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(", klass):
        fb = function_body(klass, fm.group(1))
        if fb is None:
            continue
        out.setdefault(fm.group(1), (open_brace + fb[0], open_brace + fb[1]))
    return out


def check_capture_body(code, lit):
    """[R3 14-1 symmetric seam] 包装类的方法体必须真的读捕获的 json。

    调用点齐了不代表值对：`fun streamingFlagOrFalse(): Boolean = false` 让队列
    状态帧守卫（L381）与流式 ACK 屏障守卫（L450）都拿到常量 false —— 流式请求
    结束后去等一个只写 `.stream.ack` 的客户端写 `.ack`，每次白等一个 ACK 超时。
    """
    v = []
    methods = captured_body_methods(code)
    if methods is None:
        v.append(
            "ModelExecutionService.kt: `class CapturedRequestBody` not found — the "
            "captured-body wrapper is gone or renamed (fail-closed: the consumers "
            "would be reading a body nobody captured)"
        )
        return v
    for name, needle, why in (
        (
            "streamingFlagOrFalse",
            r'optBoolean\s*\(\s*"streaming"',
            "the queue-frame guard and the streaming ACK barrier both read this "
            "flag; a constant makes a streaming run take the non-streaming "
            "`waitClientAck` path and burn a full ACK timeout per call",
        ),
        (
            "jsonOrThrow",
            r"json\s*\.\s*getOrThrow\s*\(",
            "the formal executor site consumes this parse; a constant hands the "
            "executor a body nobody read",
        ),
    ):
        if name not in methods:
            v.append(
                "ModelExecutionService.kt: CapturedRequestBody.%s(…) is gone — the "
                "consumer site has no derivation to read (fail-closed)" % name
            )
            continue
        a, b = methods[name]
        body = code[a:b]
        body_lit = lit[a:b]
        line = code[:a].count("\n") + 1
        if const_bool(body) is not None or const_value(strip_parens(body)) is not None:
            v.append(
                "ModelExecutionService.kt:%d: CapturedRequestBody.%s(…) returns a "
                "CONSTANT — %s" % (line, name, why)
            )
            continue
        if not contains_ident(body, "json"):
            v.append(
                "ModelExecutionService.kt:%d: CapturedRequestBody.%s(…) never touches "
                "the captured `json` — %s" % (line, name, why)
            )
            continue
        if not re.search(needle, body_lit):
            v.append(
                "ModelExecutionService.kt:%d: CapturedRequestBody.%s(…) no longer "
                "derives its answer from the captured body (`%s` missing) — %s"
                % (line, name, needle, why)
            )
    return v


def check_service(src):
    """ISSUE-12a: one read of the body, inside the single capture, consumed."""
    v = []
    try:
        code = mask_code(src)
        lit = literal_view(src)
    except KotlinLexError as exc:
        return ["ModelExecutionService.kt: cannot parse (fail-closed): %s" % exc]

    reads, names = body_read_sites(code)
    captures = capture_lambdas(code)

    # (1) exactly one capture, and it is the only place the body is read.
    if not captures:
        v.append(
            "ModelExecutionService.kt: the body read is not routed through "
            "'CapturedRequestBody.capture { … }' (no capture lambda found)"
        )
    elif len(captures) > 1:
        v.append(
            "ModelExecutionService.kt:%d: %d x CapturedRequestBody.capture — the "
            "body must be captured once per run (a second capture re-reads or "
            "re-parses it)"
            % (code[:captures[1][0]].count("\n") + 1, len(captures))
        )

    inside = [False] * len(reads)
    for i, (pos, _kind) in enumerate(reads):
        inside[i] = any(b_start <= pos < b_end for _s, b_start, b_end in captures)
    stray = [pos for i, (pos, _k) in enumerate(reads) if not inside[i]]
    if stray:
        v.append(
            "ModelExecutionService.kt:%s: %d read(s) of the request body OUTSIDE "
            "the capture lambda — whatever the spelling (receiver method, "
            "FileReader/FileInputStream argument, buffered chain), the body is "
            "read once, inside CapturedRequestBody"
            % (", ".join(str(code[:p].count("\n") + 1) for p in stray), len(stray))
        )
    for start, b_start, b_end in captures:
        if not any(b_start <= pos < b_end for pos, _k in reads):
            v.append(
                "ModelExecutionService.kt:%d: the capture lambda reads nothing "
                "(the captured body would be empty while every downstream parse "
                "throws)" % (code[:start].count("\n") + 1)
            )

    # (2) the captured value must be the value the executors read.
    for start, _bs, _be in captures:
        name = capture_binding(code, start)
        if name is None:
            v.append(
                "ModelExecutionService.kt:%d: the capture result is not bound to a "
                "local — nothing downstream can use the captured body"
                % (code[:start].count("\n") + 1)
            )
            continue
        if name.startswith("_"):
            v.append(
                "ModelExecutionService.kt:%d: the capture result is bound to the "
                "DELIBERATELY-DISCARDED local '%s' — the body is read and thrown away"
                % (code[:start].count("\n") + 1, name)
            )
        for consumer in CAPTURE_CONSUMERS:
            if not re.search(r"\b" + re.escape(name) + r"\s*\.\s*" + consumer + r"\s*\(", code):
                v.append(
                    "ModelExecutionService.kt: the captured local '%s' is never read "
                    "through '%s(…)' — the value that reaches the executors is not "
                    "the one that was captured" % (name, consumer)
                )

    # (3) legacy spellings keep their own voice (they are the pre-fix shapes).
    for n, line in enumerate(code.split("\n"), 1):
        if re.search(r"\brequestText\b", line):
            v.append(
                "ModelExecutionService.kt:%d: leftover per-site local "
                "'requestText' (re-read path is back)" % n
            )
    if SITE_PARSE in code:
        v.append(
            "ModelExecutionService.kt:%d: per-site parse '%s' is back"
            % (code.split("\n").index([l for l in code.split("\n") if SITE_PARSE in l][0]) + 1,
               SITE_PARSE)
        )
    for sig in EXEC_SIGS:
        if sig not in code:
            v.append(
                "ModelExecutionService.kt: executor does not take the parsed "
                "body — missing '%s'" % sig
            )
    v += check_capture_body(code, lit)
    return v


def check_offload(src):
    """ISSUE-12b: the accept loop delegates to a permit-bounded pool."""
    v = []
    try:
        code = mask_code(src)
    except KotlinLexError as exc:
        return ["NativeOffload.kt: cannot parse (fail-closed): %s" % exc]

    body = function_body(code, ACCEPT_LOOP)
    if body is None:
        return ["NativeOffload.kt: '%s' not found (ROT — update this gate)" % ACCEPT_LOOP]
    s, e, _kind = body
    loop = code[s:e]

    # (1) every submit must be reachable, and at least one must exist.
    submits = [m.start() for m in re.finditer(r"\bpool\s*\.\s*submit\s*\(", loop)]
    live_submits = [p for p in submits if path_feasible(loop, p)]
    if not submits:
        v.append(
            "NativeOffload.kt: runAcceptLoop does not hand clients to the "
            "bounded pool (missing '%s')" % POOL_SUBMIT
        )
    elif not live_submits:
        v.append(
            "NativeOffload.kt: every pool.submit(…) in runAcceptLoop is "
            "unreachable (dead guard) — accepted clients are neither served nor "
            "closed, the guest blocks on the socket forever"
        )

    # (2) no client may be handled outside the pool.
    for m in re.finditer(r"\bhandleClient\s*\(", loop):
        v.append(
            "NativeOffload.kt:%d: runAcceptLoop calls handleClient(…) directly — "
            "the concurrency cap (MAX_CONCURRENT_WORKERS) only exists inside the "
            "pool" % (code[:s + m.start()].count("\n") + 1)
        )

    # (3) no parallel-escaping primitive anywhere in the loop.
    for m in PARALLEL_RE.finditer(loop):
        v.append(
            "NativeOffload.kt:%d: runAcceptLoop spawns work outside the bounded "
            "pool (%r) — a burst of N connections creates N threads again"
            % (code[:s + m.start()].count("\n") + 1, loop[m.start():m.end()])
        )

    if OLD_ACQUIRE in code:
        v.append(
            "NativeOffload.kt:%d: '%s' is back (workers must not park as "
            "threads on the semaphore)"
            % (code[:code.index(OLD_ACQUIRE)].count("\n") + 1, OLD_ACQUIRE)
        )
    if POOL_DECL not in code:
        v.append("NativeOffload.kt: '%s' missing" % POOL_DECL)

    # (4) the permit verdict must be a real bound, not decoration.
    for m in re.finditer(r"permits\s*\.\s*tryAcquire\s*\(\s*\)", code):
        tail = code[m.end(): m.end() + 8].lstrip()
        if tail.startswith(("||", "&&", "?:", ".")):
            v.append(
                "NativeOffload.kt:%d: the 'tryAcquire' verdict is combined with "
                "another expression (%r) — the spawn bound becomes constant and "
                "every submit starts a worker" % (code[: m.start()].count("\n") + 1, tail[:6])
            )
            break
    if TRY_ACQUIRE not in code:
        v.append(
            "NativeOffload.kt: 'tryAcquire' bound missing — a submit could "
            "spawn an unbounded worker"
        )

    # (5) [B7-t03] the spawn must be DOMINATED by the permit verdict: every
    # spawnThread call site inside a branch whose condition derives from the
    # permit. An `else { spawnThread(…) }` keeps `if (spawn)` textually intact
    # while every permit-less submit spawns anyway.
    gate_spans = []
    # Which local carries the permit verdict? Derived from the binding, so a
    # guard whose condition merely LOOKS right (`if (spawn)` over a constant)
    # is not mistaken for a bound gate.
    permit_locals = set()
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*=(?!=)", code):
        rhs_start = m.end()
        rhs_end = expr_end(code, rhs_start)
        if re.search(r"tryAcquire|permits", code[rhs_start:rhs_end]):
            permit_locals.add(m.group(1))
    for cond, t_start, t_end, e_start, e_end in if_blocks(code):
        gated = (contains_ident(cond, "tryAcquire") or "permits" in cond
                 or any(contains_ident(cond, name) for name in permit_locals))
        if not gated:
            continue
        if const_bool(cond) is True:
            v.append(
                "NativeOffload.kt:%d: the worker-spawn guard is a constant true "
                "(%r) — the permit bound is decoration"
                % (code[:t_start].count("\n") + 1, cond.strip())
            )
            continue
        if not guard_feasible(cond):
            v.append(
                "NativeOffload.kt:%d: the worker-spawn guard is infeasible (%r) — "
                "no worker is ever started" % (code[:t_start].count("\n") + 1, cond.strip())
            )
            continue
        gate_spans.append((t_start, t_end))
        if e_start is not None:
            else_body = code[e_start:e_end]
            if re.search(r"\bspawnThread\s*\(", else_body):
                v.append(
                    "NativeOffload.kt:%d: the permit-denied `else` branch spawns a "
                    "worker anyway — tryAcquire()=false stops nothing, the thread "
                    "cap (MAX_CONCURRENT_WORKERS) is void"
                    % (code[:e_start].count("\n") + 1)
                )
    spawn_calls = [m.start() for m in re.finditer(r"\bspawnThread\s*\(", code)]
    if not spawn_calls:
        v.append(
            "NativeOffload.kt: no 'spawnThread(…)' call — the pool enqueues "
            "clients but never drains them"
        )
    if spawn_calls and not gate_spans:
        v.append(
            "NativeOffload.kt: no branch derives from the permit verdict — every "
            "spawnThread(…) call runs unguarded"
        )
    for pos in spawn_calls:
        if not any(a <= pos < b for a, b in gate_spans):
            v.append(
                "NativeOffload.kt:%d: 'spawnThread(…)' is called outside the "
                "permit-gated branch — a worker is started without holding a "
                "permit" % (code[:pos].count("\n") + 1)
            )

    # (6) [B7-t03] the permit must actually come back: release has to be live.
    releases = [m.start() for m in re.finditer(r"\bpermits\s*\.\s*release\s*\(\s*\)", code)]
    if not releases:
        v.append("NativeOffload.kt: permit is never released on worker exit")
    elif not any(path_feasible(code, p) for p in releases):
        v.append(
            "NativeOffload.kt:%d: 'permits.release()' never runs (dead guard) — "
            "the permit budget drains to zero and every later offload is queued "
            "behind a worker that never starts"
            % (code[:releases[0]].count("\n") + 1)
        )
    if CAP_CONST not in code:
        v.append("NativeOffload.kt: default cap changed (expected '%s')" % CAP_CONST)

    # (7) [R3 14-2 dual-gate seam] 装配段是两门之间的空白：accept loop 与各
    # `*OffloadHandler` 都合规，但 `handleClient` 把从 socket 帧解出的 cwd 与
    # `MINIS_CHAT_SESSION_ID` 换成常量，下游每一个 handler 都拿到 "/" 与 null。
    v += check_dispatch(code)

    # (8) [R3 14-3 symmetric seam] 信号量一侧查得很细（tryAcquire 绑 spawn、
    # spawn 被 permit 支配、release 可达），队列一侧从没查过入队：
    # `if (!acquired) pending.addLast(…)` 让"拿到了 permit"的那条路（也就是
    # 池未满时的几乎每一次提交）把 item 丢掉，新 worker 立刻 pollFirst()=null。
    v += check_enqueue(code)
    return v


def _first_param(code, fun_name):
    m = re.search(r"\bfun\s+" + re.escape(fun_name) + r"\s*\(", code)
    if not m:
        return None
    spans = call_args(code, m.end() - 1)
    if not spans:
        return None
    pm = re.match(r"\s*(?:vararg\s+)?([A-Za-z_]\w*)\s*:", code[spans[0][0]:spans[0][1]])
    return pm.group(1) if pm else None


def check_enqueue(code):
    """`submit` 必须无条件入队：permit 申请与队列是同一笔交易的两半。"""
    v = []
    if "pending" not in code:
        return v  # 夹具没有队列（旧 per-connection 形态由 (1)/(2) 负责）
    sub = function_body(code, "submit")
    if sub is None:
        return [
            "NativeOffload.kt: BoundedWorkerPool.submit(…) not found — the enqueue "
            "half of the bounded pool cannot be graded (fail-closed)"
        ]
    sa, sb = sub[0], sub[1]
    sbody = code[sa:sb]
    item = _first_param(code, "submit")
    permit_locals = set()
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*=(?!=)", sbody):
        rhs = sbody[m.end():expr_end(sbody, m.end())]
        if re.search(r"tryAcquire|permits", rhs):
            permit_locals.add(m.group(1))
    enq = [m.start() for m in re.finditer(
        r"\bpending\s*\.\s*(?:addLast|addFirst|add|offer|push)\s*\(", sbody)]
    if not enq:
        v.append(
            "NativeOffload.kt: BoundedWorkerPool.submit never enqueues — the accepted "
            "client is dropped on the floor (the worker polls an empty queue and "
            "releases the permit immediately)"
        )
        return v
    carried = 0
    for p in enq:
        line = code[:sa + p].count("\n") + 1
        if not path_feasible(sbody, p):
            v.append(
                "NativeOffload.kt:%d: the enqueue in submit is unreachable (dead guard) "
                "— the item is dropped" % line
            )
            continue
        gated = None
        for _kind, cond in enclosing_conditions(sbody, p):
            if ("permits" in cond or "tryAcquire" in cond
                    or any(contains_ident(cond, name) for name in permit_locals)):
                gated = cond
                break
        if gated is not None:
            v.append(
                "NativeOffload.kt:%d: pending.addLast(…) sits behind the permit verdict "
                "`if (%s)` — on the permit-granted path (an idle pool, i.e. almost every "
                "submit) the item is never queued, the freshly spawned worker polls null "
                "and releases the permit, and the client socket is never served nor "
                "closed (R3 14-3)" % (line, gated)
            )
            continue
        open_at = sbody.find("(", p)
        spans = call_args(sbody, open_at) if open_at > 0 else []
        arg = sbody[spans[0][0]:spans[0][1]] if spans else ""
        if item and not contains_ident(arg, item):
            v.append(
                "NativeOffload.kt:%d: the enqueue does not carry `%s` — the worker "
                "drains something other than the accepted item" % (line, item)
            )
            continue
        carried += 1
    if carried == 0 and not any("sits behind the permit" in x for x in v):
        v.append(
            "NativeOffload.kt: no unconditional enqueue survives in submit — every "
            "client handed to the pool is dropped"
        )
    return v


def check_dispatch(code):
    """`handleClient` 装配 NativeOffloadRequest 时必须转发帧里的 cwd / sessionId。"""
    v = []
    sites = []
    for m in re.finditer(r"\bNativeOffloadRequest\s*\(", code):
        # 跳过类型声明本身（`data class NativeOffloadRequest(…):`）
        if re.search(r"\bclass\s+$", code[max(0, m.start() - 40):m.start()]):
            continue
        sites.append(m.start())
    if not sites:
        if re.search(r"\bhandler\s*\.\s*handle\s*\(", code):
            v.append(
                "NativeOffload.kt: `handler.handle(…)` has no NativeOffloadRequest "
                "assembly — the frame-derived request cannot be graded (fail-closed)"
            )
        return v
    for pos in sites:
        open_at = code.find("(", pos)
        line = code[:pos].count("\n") + 1
        if open_at < 0 or match_bracket(code, open_at) < 0:
            v.append("NativeOffload.kt:%d: NativeOffloadRequest(…) could not be parsed "
                     "(fail-closed)" % line)
            continue
        na = named_args(code, open_at)
        for arg, ref, why in (
            ("cwd", "cwd", "every relative path resolves against `/`"),
            ("sessionId", "env",
             "session-scoped permissions / browsers lose their binding"),
        ):
            if arg not in na:
                v.append(
                    "NativeOffload.kt:%d: NativeOffloadRequest is built without `%s = …` "
                    "— %s (fail-closed)" % (line, arg, why)
                )
                continue
            a, b = na[arg]
            expr = strip_parens(code[a:b])
            if const_value(expr) is not None:
                v.append(
                    "NativeOffload.kt:%d: `%s = %s` is a constant — the frame-derived "
                    "value is discarded, so %s (R3 14-2)" % (line, arg, expr[:24], why)
                )
                continue
            if not any(contains_ident(s, ref) for s in value_sources(expr)):
                v.append(
                    "NativeOffload.kt:%d: `%s = %s` does not derive from `%s` — %s "
                    "(R3 14-2)" % (line, arg, expr[:32], ref, why)
                )
    return v


def read(root, rel):
    with open(os.path.join(root, rel), "r", encoding="utf-8") as fh:
        return fh.read()


def run(root, only=None):
    if not os.path.isdir(root):
        print("modelsvc_single_read_check: not a directory: %s" % root)
        return 2
    try:
        service = read(root, SERVICE_REL)
        offload = read(root, OFFLOAD_REL)
    except OSError as exc:
        print("modelsvc_single_read_check: cannot read sources: %s" % exc)
        return 2
    violations = []
    if only in (None, "service"):
        violations += check_service(service)
    if only in (None, "offload"):
        violations += check_offload(offload)
    if violations:
        print("FAIL modelsvc_single_read_check")
        for x in violations:
            print("  " + x)
        return 1
    print("PASS modelsvc_single_read_check (1 read per run, capped worker spawns)")
    return 0


# --------------------------------------------------------------------------
# selftest: every criterion gets its own violating fixture, plus compliant ones
# --------------------------------------------------------------------------

GOOD_SERVICE = """
internal class ModelExecutionService {
    fun handle(dir: File, requestFile: File) {
        val requestBody = CapturedRequestBody.capture { requestFile.readText() }
        val wantsQueueFrames = requestBody.streamingFlagOrFalse()
        try {
            val req = requestBody.jsonOrThrow()
            if (req.optBoolean("streaming", false)) executeStreamingRun(req, dir) else executeRun(req, dir)
        } catch (t: Throwable) {}
        val isStreaming = requestBody.streamingFlagOrFalse()
    }

    private fun executeRun(req: JSONObject, dir: File): String { return "" }

    private fun executeStreamingRun(req: JSONObject, dir: File) {}
}

internal class CapturedRequestBody private constructor(
    private val json: Result<JSONObject>,
) {
    fun streamingFlagOrFalse(): Boolean =
        json.map { it.optBoolean("streaming", false) }.getOrDefault(false)

    fun jsonOrThrow(): JSONObject = json.getOrThrow()

    companion object {
        fun capture(read: () -> String): CapturedRequestBody =
            CapturedRequestBody(runCatching { JSONObject(read()) })
    }
}
"""

BAD_SERVICE_SITE_READS = """
internal class ModelExecutionService {
    fun handle(dir: File, requestFile: File) {
        val wantsQueueFrames = runCatching {
            JSONObject(requestFile.readText()).optBoolean("streaming", false)
        }.getOrDefault(false)
        try {
            val requestText = requestFile.readText()
            val isStreaming = JSONObject(requestText).optBoolean("streaming", false)
            if (isStreaming) executeStreamingRun(requestText, dir) else executeRun(requestText, dir)
        } catch (t: Throwable) {}
        val isStreaming = runCatching {
            JSONObject(requestFile.readText()).optBoolean("streaming", false)
        }.getOrDefault(false)
    }

    private fun executeRun(requestJson: String, dir: File): String { return "" }

    private fun executeStreamingRun(requestJson: String, dir: File) {}
}
"""

BAD_SERVICE_NO_CAPTURE = GOOD_SERVICE.replace(
    "CapturedRequestBody.capture { requestFile.readText() }", "requestFile.readText()"
)

GOOD_OFFLOAD = """
object NativeOffloadServer {
    internal const val MAX_CONCURRENT_WORKERS = 2

    @Volatile
    private var workerConcurrency = java.util.concurrent.Semaphore(MAX_CONCURRENT_WORKERS, true)

    private fun start() {
        val pool = BoundedWorkerPool<LocalSocket>(
            permits = workerConcurrency,
            threadName = "native-offload-worker",
            spawnThread = { name, body -> body() },
            handler = { client, queueMs -> handleClient(client, queueMs) },
        )
        thread(name = "native-offload-accept", isDaemon = true) { runAcceptLoop(s, pool) }
    }

    private fun runAcceptLoop(s: LocalServerSocket, pool: BoundedWorkerPool<LocalSocket>) {
        while (true) {
            val client = s.accept()
            pool.submit(client)
        }
    }

    private fun handleClient(client: LocalSocket, queueMs: Long) {
        val cwd = "/var/minis/workspace"
        val env = mapOf("MINIS_CHAT_SESSION_ID" to "sess-1")
        handler.handle(
            NativeOffloadRequest(
                pid = 0,
                argv = listOf("minis-fastio"),
                env = env,
                cwd = cwd,
                sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },
            )
        )
    }
}

internal class BoundedWorkerPool<T>(
    private val permits: java.util.concurrent.Semaphore,
    private val threadName: String,
    private val spawnThread: (name: String, body: () -> Unit) -> Unit,
    private val handler: (item: T, queueMs: Long) -> Unit,
) {
    fun submit(item: T) {
        val spawn = synchronized(lock) {
            pending.addLast(item)
            permits.tryAcquire()
        }
        if (spawn) spawnThread(threadName) { drain() }
    }

    private fun drain() {
        val entry = synchronized(lock) {
            pending.pollFirst() ?: run {
                permits.release()
                null
            }
        }
    }
}
"""

# pre-fix shape: per-connection thread that parks on the semaphore
BAD_OFFLOAD_PARKED = """
object NativeOffloadServer {
    internal const val MAX_CONCURRENT_WORKERS = 2
    private var workerConcurrency = java.util.concurrent.Semaphore(MAX_CONCURRENT_WORKERS, true)

    private fun start() {
        acceptThread = thread(name = "native-offload-accept", isDaemon = true) {
            runAcceptLoop(s)
        }
    }

    private fun runAcceptLoop(s: LocalServerSocket) {
        while (true) {
            val client = s.accept()
            thread(name = "native-offload-worker", isDaemon = true) {
                workerConcurrency.acquire()
                try {
                    handleClient(client, 0L)
                } finally {
                    workerConcurrency.release()
                }
            }
        }
    }

    private fun handleClient(client: LocalSocket, queueMs: Long) {}
}

internal class BoundedWorkerPool<T>(private val permits: java.util.concurrent.Semaphore) {
    fun submit(item: T) {
        permits.tryAcquire()
    }
}
"""

BAD_OFFLOAD_NO_BOUND = GOOD_OFFLOAD.replace(
    "            permits.tryAcquire()\n", ""
).replace(
    "                permits.release()\n", ""
)

# ── batch7 B7-t03 witnesses: the decision point keeps its spelling, the effect dies ──
# 07-1: the second read arrives as a constructor argument, not a receiver
BAD_SERVICE_CTOR_READ = GOOD_SERVICE.replace(
    "            val req = requestBody.jsonOrThrow()",
    "            val req = JSONObject(java.io.FileReader(requestFile).use { it.readText() })",
)
# 07-1b: the body is re-pointed through a local alias
BAD_SERVICE_ALIAS_READ = GOOD_SERVICE.replace(
    "            val req = requestBody.jsonOrThrow()",
    "            val rf = requestFile\n"
    "            val req = JSONObject(rf.readText())",
)
# 07-2: the capture result is captured twice, used never
BAD_SERVICE_DISCARDED_CAPTURE = GOOD_SERVICE.replace(
    "        val requestBody = CapturedRequestBody.capture { requestFile.readText() }",
    "        val _unused = CapturedRequestBody.capture { requestFile.readText() }\n"
    '        val requestBody = CapturedRequestBody.capture { "" }',
)
# 07-2b: one capture, but the consumer reads a *different* value's parse
BAD_SERVICE_UNCONSUMED_CAPTURE = GOOD_SERVICE.replace(
    "        val requestBody = CapturedRequestBody.capture { requestFile.readText() }",
    "        val captured = CapturedRequestBody.capture { requestFile.readText() }\n"
    '        val requestBody = CapturedRequestBody.capture { "" }',
)
# 07-3: a JDK async primitive runs the client next to the pool
BAD_OFFLOAD_COMPLETABLE = GOOD_OFFLOAD.replace(
    "            pool.submit(client)",
    "            pool.submit(client)\n"
    "            java.util.concurrent.CompletableFuture.runAsync { handleClient(client, 0L) }",
)
# 07-4: the permit-denied branch spawns anyway
BAD_OFFLOAD_ELSE_SPAWN = GOOD_OFFLOAD.replace(
    "        if (spawn) spawnThread(threadName) { drain() }",
    "        if (spawn) {\n"
    "            spawnThread(threadName) { drain() }\n"
    "        } else {\n"
    "            spawnThread(threadName) { drain() }\n"
    "        }",
)
# 07-5: the release is kept, guarded by a constant false
BAD_OFFLOAD_DEAD_RELEASE = GOOD_OFFLOAD.replace(
    "                permits.release()",
    "                if (false) permits.release()",
)
# 07-6: the submit is kept, guarded by a constant false
BAD_OFFLOAD_DEAD_SUBMIT = GOOD_OFFLOAD.replace(
    "            pool.submit(client)",
    "            if (false) pool.submit(client)",
)
# pre-existing witnesses (wide-1024 12-misc-b)
BAD_SERVICE_REPOINT_READ = GOOD_SERVICE.replace(
    "            val req = requestBody.jsonOrThrow()",
    "            val req = JSONObject(requestFile.inputStream().bufferedReader()"
    ".use { it.readText() })",
)
BAD_OFFLOAD_SHORT_CIRCUIT = GOOD_OFFLOAD.replace(
    "            permits.tryAcquire()\n",
    "            permits.tryAcquire() || true\n",
)
BAD_OFFLOAD_BARE_THREAD = GOOD_OFFLOAD.replace(
    "            pool.submit(client)\n",
    "            pool.submit(client)\n"
    "            kotlin.concurrent.thread { handleClient(client, 0L) }\n",
)

# ══════════════════════════════════════════════════════════════════════════
# R3 第三轮（dual-gate / symmetric）见证臂
# ══════════════════════════════════════════════════════════════════════════

# 14-2 witness: 装配段把帧里的 cwd / sessionId 换成常量
BAD_DISPATCH_CONSTANTS = GOOD_OFFLOAD.replace(
    '                cwd = cwd,\n'
    '                sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },',
    '                cwd = "/",\n'
    '                sessionId = null,')
# 14-2b: sessionId 干脆不转发（fail-closed）
BAD_DISPATCH_DROPPED = GOOD_OFFLOAD.replace(
    '                sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },\n', '')
# 14-3 witness: 入队被 permit 判定包起来（拿到许可的那条路丢掉 item）
BAD_ENQUEUE_GATED = GOOD_OFFLOAD.replace(
    '            pending.addLast(item)\n'
    '            permits.tryAcquire()',
    '            val acquired = permits.tryAcquire()\n'
    '            if (!acquired) pending.addLast(item)\n'
    '            acquired')
# 14-1 witness: 包装类的方法体返回常量（调用点全在，值全错）
BAD_STREAMING_FLAG_CONSTANT = GOOD_SERVICE.replace(
    '    fun streamingFlagOrFalse(): Boolean =\n'
    '        json.map { it.optBoolean("streaming", false) }.getOrDefault(false)',
    '    fun streamingFlagOrFalse(): Boolean = false')
BAD_JSON_OR_THROW_CONSTANT = GOOD_SERVICE.replace(
    '    fun jsonOrThrow(): JSONObject = json.getOrThrow()',
    '    fun jsonOrThrow(): JSONObject = JSONObject()')
# 误伤回归：显式类型标注的捕获绑定是 100% 等价写法
GOOD_SERVICE_TYPED_BINDING = GOOD_SERVICE.replace(
    '        val requestBody = CapturedRequestBody.capture { requestFile.readText() }',
    '        val requestBody: CapturedRequestBody = '
    'CapturedRequestBody.capture { requestFile.readText() }')


def selftest():
    failures = []

    def expect_clean(name, v):
        if v:
            failures.append("%s must be clean, got %s" % (name, v))

    def expect_trip(name, v, needle):
        if not any(needle in item for item in v):
            failures.append("%s did not trip '%s' (got %s)" % (name, needle, v))

    good_service = check_service(GOOD_SERVICE)
    good_offload = check_offload(GOOD_OFFLOAD)
    expect_clean("compliant service fixture", good_service)
    expect_clean("compliant offload fixture", good_offload)

    bad_reads = check_service(BAD_SERVICE_SITE_READS)
    expect_trip("three-read fixture (ISSUE-12a)", bad_reads, "OUTSIDE the capture")
    expect_trip("three-read fixture (ISSUE-12a)", bad_reads, "requestText")
    expect_trip("three-read fixture (ISSUE-12a)", bad_reads, "executeRun(req: JSONObject")

    bad_nocapture = check_service(BAD_SERVICE_NO_CAPTURE)
    expect_trip("no-capture fixture", bad_nocapture, "not routed through")

    bad_parked = check_offload(BAD_OFFLOAD_PARKED)
    expect_trip("parked-thread fixture (ISSUE-12b)", bad_parked, "spawns work outside")
    expect_trip("parked-thread fixture (ISSUE-12b)", bad_parked, "workerConcurrency.acquire()")
    expect_trip("parked-thread fixture (ISSUE-12b)", bad_parked, "does not hand clients")

    bad_nobound = check_offload(BAD_OFFLOAD_NO_BOUND)
    expect_trip("unbounded-submit fixture", bad_nobound, "tryAcquire")
    expect_trip("unbounded-submit fixture", bad_nobound, "never released")
    expect_trip("unbounded-submit fixture", bad_nobound, "outside the permit-gated branch")

    repoint = check_service(BAD_SERVICE_REPOINT_READ)
    expect_trip("re-pointed second read", repoint, "OUTSIDE the capture")

    short = check_offload(BAD_OFFLOAD_SHORT_CIRCUIT)
    expect_trip("short-circuited permit", short, "combined with another expression")

    bare = check_offload(BAD_OFFLOAD_BARE_THREAD)
    expect_trip("bare-lambda thread", bare, "spawns work outside")
    expect_trip("bare-lambda thread", bare, "calls handleClient")

    # ── batch7 B7-t03: one arm per witness, each tripping its OWN criterion ──
    ctor = check_service(BAD_SERVICE_CTOR_READ)
    expect_trip("07-1 ctor-argument read", ctor, "OUTSIDE the capture")
    alias = check_service(BAD_SERVICE_ALIAS_READ)
    expect_trip("07-1b aliased read", alias, "OUTSIDE the capture")
    discarded = check_service(BAD_SERVICE_DISCARDED_CAPTURE)
    expect_trip("07-2 discarded capture", discarded, "captured once per run")
    expect_trip("07-2 discarded capture", discarded, "DELIBERATELY-DISCARDED")
    unconsumed = check_service(BAD_SERVICE_UNCONSUMED_CAPTURE)
    expect_trip("07-2b unconsumed capture", unconsumed, "captured once per run")
    expect_trip("07-2b unconsumed capture", unconsumed, "reads nothing")

    completable = check_offload(BAD_OFFLOAD_COMPLETABLE)
    expect_trip("07-3 CompletableFuture escape", completable, "spawns work outside")
    expect_trip("07-3 CompletableFuture escape", completable, "calls handleClient")
    else_spawn = check_offload(BAD_OFFLOAD_ELSE_SPAWN)
    expect_trip("07-4 else-branch spawn", else_spawn, "permit-denied `else`")
    dead_release = check_offload(BAD_OFFLOAD_DEAD_RELEASE)
    expect_trip("07-5 dead release", dead_release, "never runs")
    dead_submit = check_offload(BAD_OFFLOAD_DEAD_SUBMIT)
    expect_trip("07-6 dead submit", dead_submit, "unreachable")

    # ── R3 第三轮: dual-gate / symmetric 见证臂 ──────────────────────────
    typed = check_service(GOOD_SERVICE_TYPED_BINDING)
    expect_clean("14-1 clean arm: typed capture binding", typed)
    flag_const = check_service(BAD_STREAMING_FLAG_CONSTANT)
    expect_trip("14-1 constant streamingFlagOrFalse", flag_const, "returns a ")
    expect_trip("14-1 constant streamingFlagOrFalse", flag_const, "CONSTANT")
    json_const = check_service(BAD_JSON_OR_THROW_CONSTANT)
    expect_trip("14-1b constant jsonOrThrow", json_const, "never touches the captured `json`")
    dispatch_const = check_offload(BAD_DISPATCH_CONSTANTS)
    expect_trip("14-2 dispatch constants", dispatch_const, "is a constant")
    expect_trip("14-2 dispatch constants", dispatch_const, "sessionId = null")
    dispatch_drop = check_offload(BAD_DISPATCH_DROPPED)
    expect_trip("14-2b sessionId not forwarded", dispatch_drop, "without `sessionId")
    enq_gated = check_offload(BAD_ENQUEUE_GATED)
    expect_trip("14-3 enqueue behind permit", enq_gated, "sits behind the permit")

    arms = {
        "compliant service": good_service,
        "compliant offload": good_offload,
        "three-read service": bad_reads,
        "no-capture service": bad_nocapture,
        "parked-thread offload": bad_parked,
        "unbounded-submit": bad_nobound,
        "re-pointed read": repoint,
        "short-circuited permit": short,
        "bare-lambda thread": bare,
        "ctor-argument read": ctor,
        "aliased read": alias,
        "discarded capture": discarded,
        "unconsumed capture": unconsumed,
        "CompletableFuture escape": completable,
        "else-branch spawn": else_spawn,
        "dead release": dead_release,
        "dead submit": dead_submit,
        "typed capture binding": typed,
        "constant streaming flag": flag_const,
        "constant jsonOrThrow": json_const,
        "dispatch constants": dispatch_const,
        "dispatch sessionId dropped": dispatch_drop,
        "enqueue behind permit": enq_gated,
    }
    for label, verdict in arms.items():
        print("selftest %-26s -> %d violation(s)" % (label, len(verdict)))

    if failures:
        for f in failures:
            print("SELFTEST FAIL " + f)
        return 1
    print("MODELSVC_SINGLE_READ_SELFTEST=PASS (%d arms)" % (len(arms) + 5))
    return 0


def main():
    args = [a for a in sys.argv[1:] if a]
    if "--selftest" in args:
        return selftest()
    only = None
    for i, a in enumerate(args):
        if a == "--only" and i + 1 < len(args):
            only = args[i + 1]
    args = [a for a in args if not a.startswith("-") and a != only]
    root = args[0] if args else os.getcwd()
    return run(root, only)


if __name__ == "__main__":
    sys.exit(main())
