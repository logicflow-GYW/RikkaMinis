#!/usr/bin/env python3
"""
trace_eval_check.py — Agent trace 回放评估（golden 断言门禁）

消费已落盘的 schema 2.0 JSONL trace（workspace/.traces/agent-*.jsonl），
对每条 golden 用例断言：
  - tool_call 序列（tool 名字列表，顺序敏感）
  - terminal_state（Succeeded/Failed/Cancelled/Interrupted）
  - 可选：terminal_reason / tool_result 全 success / 禁止出现的 tool

用法：
  python3 scripts/scan/trace_eval_check.py <repo_root>

golden 用例放在 tests/traces/golden/*.json：
  {
    "trace": "<相对 repo 的 .jsonl 路径 或 绝对路径>",
    "expect": {
      "tools": ["shell_execute", "file_read"],   # 可选：按序出现的 tool 名
      "terminal_state": "Succeeded",             # 可选
      "terminal_reason": "completed_normally",   # 可选
      "all_tools_succeed": true,                 # 可选：true=全成功 / false=至少一次失败
      "some_tool_failed": true,                  # 可选：至少一个 tool_result 失败
      "forbidden_tools": ["browser_use"]         # 可选：不允许出现的 tool
    }
  }

`some_tool_failed` 是故障 golden 的必需断言：守卫/拒绝类修复的证据就是"这次调用失败了"。
`all_tools_succeed` 是**双向**断言：`true` 要求全成功，`false` 要求至少一次失败（见下）。

也支持自包含模式（golden 文件里直接带 "trace_lines" 数组），用于
门禁自测——CI 无真实设备 trace 时用合成 trace 验证评估器本身。

退出码：0 = 全部通过；1 = 有失败（或找不到任何用例）。

─────────────────────────────────────────────────────────────────────────────
批五见证（5/6 盲）→ 判据改造（详见 fix-reports/FIX-BATCH5-t03.md）

  05-1 `tool_id: ""` 两侧都为空 + 结果张冠李戴（`file_read` 调用配 `shell_execute`
       结果）：旧代码 `if not any(ids): return` 把「id 被抹成空串」当成「手写的
       无 id 夹具」，整段配对检查被跳过 ⇒ 无 id 的 trace 改按**顺序**配对并校验
       工具名 + 轮次。
  05-2 截断轮里"写成功"、最终轮写失败：旧代码只要"曾经成功过一次"就绿 ⇒ ①声明
       terminal_state == Succeeded 的 golden，其每个工具的**末次**结果必须是成功
       （失败后必须重发并恢复）；②截断轮（stop_reason == length/max_tokens）里
       报成功的 tool_result 本身就是红（半截参数写盘）。
  05-3 `turn: null` 让轮次比对短路 ⇒ 配对的两个事件都必须带整数 turn。
  05-4 把 file_write 挪进 `stop_reason: length` 的截断轮，最终轮什么都没做 ⇒
       `tools` 序列只从**有效轮次**（非截断）派生。
  05-5 `all_tools_succeed: false` 曾经是空操作（falsy）⇒ 该键现在是双向断言。
  05-6（原本已抓）失败结果的 output 必须 strip 后非空 —— 保持。

Fail-closed 口径：golden 里出现无法解析的行、无法归类的 `success`、`turn` 缺失
都是违规；宁可变红也不静默跳过。
"""
import json
import os
import sys

VALID_TERMINAL = {"Succeeded", "Failed", "Cancelled", "Interrupted"}

# A turn whose end reports one of these stopped mid-generation: the tool calls
# it issued carry possibly-truncated arguments, so nothing they "succeeded" at
# can be counted as a landed effect (witnesses 05-2 / 05-4).
TRUNCATED_STOP_REASONS = {"length", "max_tokens"}


def load_jsonl(path):
    """读一个 .jsonl 文件为事件列表。坏行跳过并计数（trace 是旁路证据，
    局部写坏不应让整个评估崩掉，但会在结果里报 warning）。"""
    events, bad = [], 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                bad += 1
    return events, bad


def _tool_id(event):
    """The identity a tool_call/tool_result pair is keyed on, or None."""
    tid = event.get("tool_id")
    return tid if isinstance(tid, str) and tid.strip() else None


def _turn_of(event):
    """The turn number, or None when it is missing/null/non-integer."""
    t = event.get("turn")
    return t if isinstance(t, int) and not isinstance(t, bool) else None


def truncated_turns(events):
    """Turns that ended mid-generation (`stop_reason` length / max_tokens)."""
    out = set()
    for e in events:
        if e.get("type") != "turn_end":
            continue
        reason = e.get("stop_reason")
        if reason is None:
            reason = e.get("finish_reason")
        if not isinstance(reason, str):
            continue
        if reason.strip().lower() in TRUNCATED_STOP_REASONS:
            t = _turn_of(e)
            if t is not None:
                out.add(t)
    return out


def _check_turns(label, call, result, failures):
    """Both sides of a pair must carry an integer turn (witness 05-3)."""
    ct, rt = _turn_of(call), _turn_of(result)
    if rt is None:
        failures.append(
            f"{label}: tool_result has no integer `turn` — a result that names no "
            "turn cannot be paired with the turn that issued the call"
        )
    elif ct is None:
        failures.append(
            f"{label}: tool_call has no integer `turn` — the call cannot be paired "
            "with the turn its result belongs to"
        )
    elif ct != rt:
        failures.append(
            f"{label}: turn mismatch — call turn {ct!r}, result turn {rt!r}"
        )


def check_event_integrity(tool_calls, tool_results, terminal_state):
    """Pair every tool_call with ITS tool_result; refuse to guess.

    A golden's evidence is the *link* between a call and its result — which tool
    ran, and whether it was refused. `evaluate` used to build the two lists
    independently, so a result attributed to another tool (or a call that never
    got a result at all) still read as "all tools succeeded".

    Identity is `tool_id`, which schema 2.0 writes on both events. A trace that
    carries ids on ONE side only is a violation (that is what stops the pairing
    check from being dodged by deleting the field). A trace with **no ids at
    all** — hand-written inline fixtures, or a recorder that blanked them — is
    paired by ORDER inside the trace and must still agree on tool name and turn:
    witness 05-1 blanked both ids and swapped the result's tool, and the old
    `if not any(ids): return` skipped the whole check.
    """
    failures = []

    call_ids = [_tool_id(e) for e in tool_calls]
    result_ids = [_tool_id(e) for e in tool_results]
    has_ids = any(call_ids + result_ids)

    if not has_ids:
        # Order pairing. Extra events are tolerated (hand-written fixtures carry
        # provenance events without ids); every aligned pair must still match.
        for idx, (call, result) in enumerate(zip(tool_calls, tool_results), 1):
            if call.get("tool") != result.get("tool"):
                failures.append(
                    f"tool_result #{idx} reports tool {result.get('tool', '?')!r} "
                    f"but tool_call #{idx} ran {call.get('tool', '?')!r} — an id-less "
                    "trace is paired by order, so this result belongs to another call"
                )
            _check_turns("pair #%d (%r)" % (idx, call.get("tool") or "?"),
                         call, result, failures)
        return failures

    for e, tid in zip(tool_calls, call_ids):
        if tid is None:
            failures.append(
                "tool_call without `tool_id` on a trace that uses ids — the "
                "call/result pairing cannot be checked (schema 2.0 writes it)"
            )
    for e, tid in zip(tool_results, result_ids):
        if tid is None:
            failures.append(
                "tool_result without `tool_id` on a trace that uses ids — a result "
                "that names no call cannot be attributed to one"
            )

    by_call, by_result = {}, {}
    for e, tid in zip(tool_calls, call_ids):
        if tid is None:
            continue
        if tid in by_call:
            failures.append(f"duplicate tool_call for tool_id {tid!r} (one call, one result)")
        by_call[tid] = e
    for e, tid in zip(tool_results, result_ids):
        if tid is None:
            continue
        if tid in by_result:
            failures.append(
                f"duplicate tool_result for tool_id {tid!r} (at most one result per call)"
            )
        by_result[tid] = e

    for tid, res in by_result.items():
        call = by_call.get(tid)
        if call is None:
            failures.append(
                f"tool_result for tool_id {tid!r} has no matching tool_call — the "
                f"result claims tool {res.get('tool', '?')!r} that was never dispatched"
            )
            continue
        if call.get("tool") != res.get("tool"):
            failures.append(
                f"tool_result tool name mismatch for tool_id {tid!r}: the call ran "
                f"{call.get('tool', '?')!r}, the result reports {res.get('tool', '?')!r}"
            )
        _check_turns("tool_id %r" % (tid,), call, res, failures)

    # A dispatched call must be answered — except when the run itself was
    # cancelled/interrupted mid-dispatch, which is exactly the trace of an
    # unfinished call. Anything else is a hole in the evidence.
    unanswered_ok = terminal_state in ("Cancelled", "Interrupted")
    for tid, call in by_call.items():
        if tid not in by_result and not unanswered_ok:
            failures.append(
                f"tool_call for tool_id {tid!r} (tool {call.get('tool', '?')!r}) has no "
                f"tool_result — a run that finished {terminal_state or 'unknown'} must "
                f"answer every call it dispatched"
            )

    return failures


def _pairs_by_tool(tool_results):
    """tool name → its LAST tool_result (the state the run left behind)."""
    last = {}
    for r in tool_results:
        last[r.get("tool", "?")] = r
    return last


def evaluate(events, expect):
    """对一条 golden 的 expect 做断言。返回 (failures, warnings) 列表。"""
    failures, warnings = [], []

    tool_calls = [e for e in events if e.get("type") == "tool_call"]
    tool_results = [e for e in events if e.get("type") == "tool_result"]
    trace_ends = [e for e in events if e.get("type") == "trace_end"]

    failures.extend(
        check_event_integrity(
            tool_calls,
            tool_results,
            trace_ends[-1].get("terminal_state", "") if trace_ends else "",
        )
    )

    # `success` is a boolean on every schema-2.0 tool_result. A null/missing one
    # used to sail through `not r.get("success")` and `all(...)` alike: both are
    # falsy-based, so the two opposite assertions (`all_tools_succeed` and
    # `some_tool_failed`) could be satisfied or dodged by a *missing field*, and
    # a fault golden stopped distinguishing "the guard refused" from "the
    # recorder dropped the verdict".
    for idx, r in enumerate(tool_results, 1):
        if not isinstance(r.get("success"), bool):
            failures.append(
                f"tool_result #{idx} (tool {r.get('tool', '?')!r}, turn "
                f"{r.get('turn')!r}) has no boolean `success` (got "
                f"{r.get('success')!r}) — a refusal is recorded as false, not null"
            )

    if not trace_ends:
        failures.append("no trace_end event (run never finalized)")
    if len(trace_ends) > 1:
        # schema 契约：terminal 事件每 run 至多一条
        failures.append(f"{len(trace_ends)} trace_end events (contract: at most 1)")

    # ── truncated turns (05-2 / 05-4) ──
    # A turn that stopped mid-generation (`length` / `max_tokens`) can only have
    # issued tool calls with partial arguments. Counting such a call as a landed
    # effect is how a truncated half-write became "the golden repaired the file".
    trunc = truncated_turns(events)
    if trunc:
        for idx, r in enumerate(tool_results, 1):
            if r.get("success") is True and _turn_of(r) in trunc:
                failures.append(
                    f"tool_result #{idx} (tool {r.get('tool', '?')!r}, turn "
                    f"{r.get('turn')}) reports success inside a TRUNCATED turn "
                    f"(stop_reason length/max_tokens) — the arguments it ran on were "
                    "cut mid-generation, so this is not a landed effect"
                )

    # ── tool 序列（只取有效轮次）──
    if "tools" in expect:
        actual = [e.get("tool", "?") for e in tool_calls
                  if _turn_of(e) is None or _turn_of(e) not in trunc]
        expected = expect["tools"]
        if actual != expected:
            failures.append(
                f"tool sequence mismatch: expected {expected}, got {actual}"
                + (f" (calls in truncated turns are excluded: {trunc})" if trunc else "")
            )

    # ── forbidden tools ──
    if expect.get("forbidden_tools"):
        actual = {e.get("tool", "?") for e in tool_calls}
        hit = actual & set(expect["forbidden_tools"])
        if hit:
            failures.append(f"forbidden tools called: {sorted(hit)}")

    # ── terminal state / reason ──
    if expect.get("terminal_state"):
        if not trace_ends:
            failures.append("cannot check terminal_state: no trace_end")
        else:
            ts = trace_ends[-1].get("terminal_state", "")
            if ts != expect["terminal_state"]:
                failures.append(
                    f"terminal_state mismatch: expected {expect['terminal_state']!r}, got {ts!r}"
                )
            if ts and ts not in VALID_TERMINAL:
                warnings.append(f"unknown terminal_state {ts!r} (not in {sorted(VALID_TERMINAL)})")

    if expect.get("terminal_reason"):
        if not trace_ends:
            failures.append("cannot check terminal_reason: no trace_end")
        else:
            tr = trace_ends[-1].get("terminal_reason", "")
            if tr != expect["terminal_reason"]:
                failures.append(
                    f"terminal_reason mismatch: expected {expect['terminal_reason']!r}, got {tr!r}"
                )

    # ── a run the golden calls Succeeded must not end with a failed tool ──
    # Witness 05-2: turn 0 "wrote 150 bytes" (success), turn 1 the real write
    # failed — every per-field assertion was still satisfied because they look
    # at ANY result, not at the state the run left behind. A failure is allowed
    # only if it was later re-issued successfully (that is what the offload
    # golden documents), which is exactly "the LAST result of each tool".
    if expect.get("terminal_state") == "Succeeded":
        for tool, r in sorted(_pairs_by_tool(tool_results).items()):
            if r.get("success") is False:
                failures.append(
                    f"terminal_state Succeeded but tool {tool!r} is left in a FAILED "
                    f"state (latest result, turn {r.get('turn')!r}): "
                    f"{str(r.get('output', ''))[:120]}"
                )

    # ── all tools succeed (both polarities, 05-5) ──
    if "all_tools_succeed" in expect:
        flag = expect["all_tools_succeed"]
        if not isinstance(flag, bool):
            failures.append(
                f"all_tools_succeed must be a boolean (got {flag!r}) — a truthy/falsy "
                "stand-in silently disables one of the two directions"
            )
        elif flag:
            if tool_calls and not tool_results:
                failures.append("tool_calls present but no tool_result events")
            for r in tool_results:
                if r.get("success") is not True:
                    failures.append(
                        f"tool {r.get('tool', '?')} (turn {r.get('turn')}) failed: "
                        f"{str(r.get('output', ''))[:120]}"
                    )
        else:
            # `false` used to be a no-op (falsy ⇒ the whole block was skipped),
            # so a fault golden could keep its "at least one tool failed" claim
            # while every result succeeded (witness 05-5).
            if not tool_results:
                failures.append("all_tools_succeed=false: no tool_result events at all")
            elif not any(r.get("success") is False for r in tool_results):
                failures.append(
                    "all_tools_succeed=false: every tool_result succeeded — the "
                    "reverse contract (at least one tool failed) is not satisfied"
                )

    # ── at least one tool failed ──
    if expect.get("some_tool_failed"):
        # The failure half of a fault golden.
        if not tool_results:
            failures.append("some_tool_failed: no tool_result events at all")
        elif not any(r.get("success") is False for r in tool_results):
            failures.append("some_tool_failed: every tool_result succeeded (a refusal was expected)")
        else:
            refused = [r for r in tool_results if r.get("success") is False]
            if not any(isinstance(r.get("output"), str) and r.get("output").strip()
                       for r in refused):
                failures.append(
                    "some_tool_failed: the failed tool_result carries no diagnostic "
                    "output — a guard refusal must say why, otherwise 'refused by "
                    "the guard' and 'crashed' are indistinguishable evidence"
                )

    return failures, warnings


def resolve_trace(case, repo_root):
    """golden → trace 路径。自包含模式（trace_lines）返回 None。"""
    if "trace_lines" in case:
        return None
    p = case.get("trace", "")
    if not p:
        raise ValueError("golden case has neither 'trace' nor 'trace_lines'")
    if not os.path.isabs(p):
        p = os.path.join(repo_root, p)
    return p


def main():
    if "--selftest" in sys.argv:
        return selftest()
    repo_root = sys.argv[1] if len(sys.argv) > 1 else "."
    golden_dir = os.path.join(repo_root, "tests", "traces", "golden")

    if not os.path.isdir(golden_dir):
        print("❌ trace_eval: no golden dir at tests/traces/golden/ — nothing to gate")
        return 1

    cases = sorted(f for f in os.listdir(golden_dir) if f.endswith(".json"))
    if not cases:
        print("❌ trace_eval: golden dir exists but has no *.json cases")
        return 1

    passed, failed = 0, 0
    for name in cases:
        path = os.path.join(golden_dir, name)
        try:
            with open(path, "r", encoding="utf-8") as f:
                case = json.load(f)
        except (json.JSONDecodeError, OSError) as ex:
            print(f"  ❌ {name}: unreadable golden ({ex})")
            failed += 1
            continue

        expect = case.get("expect", {})
        try:
            trace_path = resolve_trace(case, repo_root)
        except ValueError as ex:
            print(f"  ❌ {name}: {ex}")
            failed += 1
            continue

        if trace_path is None:
            events, bad_lines = [], 0
            bad_msgs = []
            for raw in case["trace_lines"]:
                if not raw.strip():
                    continue
                try:
                    events.append(json.loads(raw))
                except json.JSONDecodeError as ex:
                    bad_lines += 1
                    bad_msgs.append(str(ex))
            src = "inline"
        else:
            if not os.path.exists(trace_path):
                print(f"  ❌ {name}: trace file missing: {trace_path}")
                failed += 1
                continue
            events, bad_lines = load_jsonl(trace_path)
            bad_msgs = []
            src = os.path.relpath(trace_path, repo_root)

        failures, warnings = evaluate(events, expect)
        if bad_lines:
            # A golden trace is a CHECKED-IN fixture, not a live device log: a
            # line that does not parse means the recorder broke the line-delimited
            # JSON contract (or a truncated/forbidden event was hidden). Skipping
            # it used to downgrade the failure to a warning and let the corrupted
            # fixture gate green. Keep `load_jsonl`'s tolerance for callers that
            # reuse it on device logs — the gate itself fails closed.
            detail = f" (first: {bad_msgs[0]})" if bad_msgs else ""
            failures.append(
                f"{bad_lines} malformed JSONL line(s) in the golden trace{detail} — "
                "every line of a checked-in trace must parse (schema 2.0)"
            )

        if failures:
            failed += 1
            print(f"  ❌ {name} [{src}]")
            for msg in failures:
                print(f"       - {msg}")
        else:
            passed += 1
            suffix = f" (+{len(warnings)} warning)" if warnings else ""
            print(f"  ✅ {name} [{src}]{suffix}")
        for w in warnings:
            print(f"       ⚠️  {w}")

    print(f"\ntrace_eval: {passed} passed, {failed} failed, {len(cases)} total")
    return 1 if failed else 0


def selftest():
    """Negative arms for every witness, plus the positive shapes.

    Each arm is the *witness patch* in miniature: if the criterion is ever
    relaxed back to "the call exists", these flip green and the gate says so
    instead of shipping a blind spot.
    """
    import tempfile

    results = []

    def arm(name, ok, detail=""):
        results.append((name, ok, detail))

    base_calls = [
        {"type": "tool_call", "turn": 1, "tool_id": "t1", "tool": "file_write"},
        {"type": "tool_call", "turn": 1, "tool_id": "t2", "tool": "file_write"},
    ]
    base_results = [
        {"type": "tool_result", "turn": 1, "tool_id": "t1", "tool": "file_write",
         "success": False, "output": "Error: refused by the guard"},
        {"type": "tool_result", "turn": 1, "tool_id": "t2", "tool": "file_write",
         "success": True, "output": "Wrote 3714 bytes"},
    ]
    end = [{"type": "trace_end", "terminal_state": "Succeeded",
            "terminal_reason": "completed_normally"}]
    expect = {"tools": ["file_write", "file_write"], "terminal_state": "Succeeded",
              "some_tool_failed": True}

    def run(events, exp):
        fails, _warns = evaluate(events, exp)
        return fails

    # ── positive: the intended pairing stays green ──
    fails = run(base_calls + base_results + end, expect)
    arm("paired call/result stays green", not fails, "; ".join(fails))

    # ── witness 1 (dataflow): the result is attributed to another tool ──
    wrong_tool = [dict(r) for r in base_results]
    wrong_tool[1]["tool"] = "unrelated_tool"
    fails = run(base_calls + wrong_tool + end, expect)
    arm("witness: tool_result attributed to another tool goes red",
        any("tool name mismatch" in f for f in fails), "; ".join(fails))

    # ── witness 1b (dataflow): a dispatched call never got a result ──
    fails = run(base_calls + base_results[:1] + end, expect)
    arm("witness: missing tool_result for a dispatched call goes red",
        any("has no tool_result" in f for f in fails), "; ".join(fails))

    # ── witness 1c (dataflow): an orphan result is not silently ignored ──
    orphan = base_results + [{"type": "tool_result", "turn": 1, "tool_id": "t9",
                              "tool": "shell_execute", "success": True, "output": "x"}]
    fails = run(base_calls + orphan + end, expect)
    arm("witness: orphan tool_result (forbidden tool smuggled in) goes red",
        any("no matching tool_call" in f for f in fails), "; ".join(fails))

    # ── cancelled runs may legitimately end mid-dispatch ──
    fails = run(base_calls + base_results[:1] +
                [{"type": "trace_end", "terminal_state": "Cancelled"}],
                {"tools": ["file_write", "file_write"], "terminal_state": "Cancelled"})
    arm("cancelled run may leave a call unanswered", not fails, "; ".join(fails))

    # ── witness 2 (dataflow): success|null dodges the falsy assertion ──
    nulled = [dict(r) for r in base_results]
    nulled[0]["success"] = None
    fails = run(base_calls + nulled + end, expect)
    arm("witness: `success: null` goes red instead of reading as 'failed'",
        any("boolean `success`" in f for f in fails), "; ".join(fails))

    nulled2 = [dict(r) for r in base_results]
    nulled2[0].pop("success")
    fails = run(base_calls + nulled2 + end, {"some_tool_failed": True})
    arm("witness: a missing `success` field no longer satisfies some_tool_failed",
        any("boolean `success`" in f for f in fails), "; ".join(fails))

    # ── witness 2b (dataflow): a refusal must say why ──
    mute = [dict(r) for r in base_results]
    mute[0]["output"] = ""
    fails = run(base_calls + mute + end, expect)
    arm("witness: a failure with no diagnostic output goes red",
        any("no diagnostic output" in f for f in fails), "; ".join(fails))

    # ── witness 3 (parser-death): a malformed JSONL line must not be a warning ──
    tmp = tempfile.mkdtemp(prefix="trace_eval_selftest_")
    try:
        def write_case(dirname, jsonl_text, expect_obj):
            gdir = os.path.join(tmp, dirname, "tests", "traces", "golden")
            os.makedirs(os.path.join(gdir, "traces"))
            with open(os.path.join(gdir, "traces", "t.jsonl"), "w", encoding="utf-8") as f:
                f.write(jsonl_text)
            with open(os.path.join(gdir, "case.json"), "w", encoding="utf-8") as f:
                json.dump({"trace": "tests/traces/golden/traces/t.jsonl",
                           "expect": expect_obj}, f)
            return os.path.join(tmp, dirname)

        good_lines = "".join(json.dumps(e) + "\n" for e in base_calls + base_results + end)

        root = write_case("clean", good_lines, expect)
        old_argv = sys.argv
        sys.argv = ["trace_eval_check.py", root]
        try:
            rc_clean = main()
        finally:
            sys.argv = old_argv
        arm("clean fixture tree stays rc=0", rc_clean == 0, f"rc={rc_clean}")

        broken = good_lines + '{"type":"tool_call","turn":0,"tool":"shell_execute","args":"TRUNCATED_JSON\n'
        root = write_case("truncated", broken, expect)
        old_argv = sys.argv
        sys.argv = ["trace_eval_check.py", root]
        try:
            rc_bad = main()
        finally:
            sys.argv = old_argv
        arm("witness: truncated JSONL line goes red (was a warning)",
            rc_bad == 1, f"rc={rc_bad}")
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    # ── batch5 witness 05-1 (family-hole): ids blanked on both sides ──
    idless_calls = [{"type": "tool_call", "turn": 0, "tool_id": "", "tool": "file_read"}]
    idless_results = [{"type": "tool_result", "turn": 1, "tool_id": "",
                       "tool": "shell_execute", "success": True, "output": "ok"}]
    fails = run(idless_calls + idless_results + end, {})
    arm("witness: blanked tool_ids no longer skip the pairing check (05-1)",
        any("paired by order" in f for f in fails)
        and any("turn mismatch" in f for f in fails), "; ".join(fails))

    ok_idless = run([{"type": "tool_call", "turn": 0, "tool_id": "", "tool": "file_read"},
                     {"type": "tool_result", "turn": 0, "tool_id": "",
                      "tool": "file_read", "success": True, "output": "ok"},
                     {"type": "trace_end", "terminal_state": "Succeeded"}],
                    {"tools": ["file_read"], "terminal_state": "Succeeded"})
    arm("id-less trace with consistent name/turn stays green", not ok_idless,
        "; ".join(ok_idless))

    # ── batch5 witness 05-3 (family-hole): `turn: null` on the result ──
    nulled_turn = [dict(r) for r in base_results]
    nulled_turn[1]["turn"] = None
    fails = run(base_calls + nulled_turn + end, expect)
    arm("witness: `turn: null` on a paired result goes red (05-3)",
        any("has no integer `turn`" in f for f in fails), "; ".join(fails))

    # ── batch5 witness 05-2 (dataflow): success in the truncated turn, the
    # real write fails last ──
    w5_2_events = [
        {"type": "turn_start", "turn": 0},
        {"type": "tool_call", "turn": 0, "tool_id": "t0", "tool": "file_write"},
        {"type": "tool_result", "turn": 0, "tool_id": "t0", "tool": "file_write",
         "success": True, "output": "Wrote 150 bytes"},
        {"type": "turn_end", "turn": 0, "stop_reason": "tool_calls"},
        {"type": "turn_start", "turn": 1},
        {"type": "tool_call", "turn": 1, "tool_id": "t1", "tool": "file_write"},
        {"type": "tool_result", "turn": 1, "tool_id": "t1", "tool": "file_write",
         "success": False, "output": "Error: disk full"},
        {"type": "turn_end", "turn": 1, "stop_reason": "stop"},
    ] + end
    fails = run(w5_2_events, {"tools": ["file_write", "file_write"],
                              "terminal_state": "Succeeded", "some_tool_failed": True})
    arm("witness: a Succeeded golden whose LAST write failed goes red (05-2)",
        any("left in a FAILED state" in f for f in fails), "; ".join(fails))

    # ...and the documented recovery shape (refused, then re-issued OK) is green.
    fails = run(base_calls + base_results + end, expect)
    arm("refused-then-recovered shape stays green (failure before success)",
        not fails, "; ".join(fails))

    # ── batch5 witness 05-4 (completeness): the write only happens in the
    # truncated turn ──
    w5_4_events = [
        {"type": "turn_start", "turn": 0},
        {"type": "tool_call", "turn": 0, "tool_id": "t0", "tool": "file_write"},
        {"type": "tool_result", "turn": 0, "tool_id": "t0", "tool": "file_write",
         "success": True, "output": "Wrote 26 bytes"},
        {"type": "turn_end", "turn": 0, "stop_reason": "length"},
        {"type": "turn_start", "turn": 1},
        {"type": "turn_end", "turn": 1, "stop_reason": "stop"},
    ] + end
    fails = run(w5_4_events, {"tools": ["file_write"], "terminal_state": "Succeeded",
                              "all_tools_succeed": True})
    arm("witness: a call in a truncated turn does not count as the sequence (05-4)",
        any("tool sequence mismatch" in f for f in fails), "; ".join(fails))
    arm("witness: success reported from a truncated turn goes red (05-2/05-4)",
        any("TRUNCATED turn" in f for f in fails), "; ".join(fails))

    # A truncated turn with NO tool events (the real golden's shape) stays green.
    trunc_only = [
        {"type": "turn_start", "turn": 0},
        {"type": "turn_end", "turn": 0, "stop_reason": "length"},
        {"type": "turn_start", "turn": 1},
        {"type": "tool_call", "turn": 1, "tool_id": "t1", "tool": "file_write"},
        {"type": "tool_result", "turn": 1, "tool_id": "t1", "tool": "file_write",
         "success": True, "output": "Wrote 3714 bytes"},
        {"type": "turn_end", "turn": 1, "stop_reason": "stop"},
    ] + end
    fails = run(trunc_only, {"tools": ["file_write"], "terminal_state": "Succeeded",
                             "all_tools_succeed": True})
    arm("a truncated turn with no tool events stays green", not fails, "; ".join(fails))

    # ── batch5 witness 05-5 (family-hole): `all_tools_succeed: false` ──
    fails = run(base_calls + [
        {"type": "tool_result", "turn": 1, "tool_id": "t1", "tool": "file_write",
         "success": True, "output": "Wrote 3714 bytes"},
        {"type": "tool_result", "turn": 1, "tool_id": "t2", "tool": "file_write",
         "success": True, "output": "Wrote 3714 bytes"},
    ] + [{"type": "trace_end", "terminal_state": "Failed"}],
        {"tools": ["file_write", "file_write"], "terminal_state": "Failed",
         "all_tools_succeed": False})
    arm("witness: `all_tools_succeed: false` asserts at least one failure (05-5)",
        any("reverse contract" in f for f in fails), "; ".join(fails))

    fails = run(base_calls + base_results +
                [{"type": "trace_end", "terminal_state": "Failed"}],
                {"all_tools_succeed": False, "terminal_state": "Failed"})
    arm("`all_tools_succeed: false` satisfied by a failure stays green",
        not fails, "; ".join(fails))

    ok = True
    for name, passed, detail in results:
        print(f"  selftest {name}: {'ok' if passed else 'FAIL'}")
        if not passed:
            ok = False
            if detail:
                print(f"      - {detail}")
    print(f"TRACE_EVAL_SELFTEST={'PASS' if ok else 'FAIL'} ({len(results)} arms)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
