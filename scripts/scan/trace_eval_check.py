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
      "all_tools_succeed": true,                 # 可选：所有 tool_result success
      "some_tool_failed": true,                  # 可选：至少一个 tool_result 失败
      "forbidden_tools": ["browser_use"]         # 可选：不允许出现的 tool
    }
  }

`some_tool_failed` 是故障 golden 的必需断言：守卫/拒绝类修复的证据就是"这次调用失败了"，
而 `all_tools_succeed: false` 不成立（falsy = 该断言根本没跑），没有它故障类用例只能断言
工具序列，守卫被摘掉也照样绿。

也支持自包含模式（golden 文件里直接带 "trace_lines" 数组），用于
门禁自测——CI 无真实设备 trace 时用合成 trace 验证评估器本身。

退出码：0 = 全部通过；1 = 有失败（或找不到任何用例）。
"""
import json
import os
import sys

VALID_TERMINAL = {"Succeeded", "Failed", "Cancelled", "Interrupted"}


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


def check_event_integrity(tool_calls, tool_results, terminal_state):
    """Pair every tool_call with ITS tool_result; refuse to guess.

    A golden's evidence is the *link* between a call and its result — which tool
    ran, and whether it was refused. `evaluate` used to build the two lists
    independently, so a result attributed to another tool (or a call that never
    got a result at all) still read as "all tools succeeded": the assertions ran
    on two unrelated piles of events.

    Identity is `tool_id`, which schema 2.0 writes on both events. A trace whose
    events carry no ids at all (hand-written inline fixtures) is paired by
    nothing and keeps only the per-field checks; a trace that uses ids on ONE
    side must use them on both — that fail-closed rule is what stops the pairing
    check from being dodged by deleting the field.
    """
    failures = []

    call_ids = [_tool_id(e) for e in tool_calls]
    result_ids = [_tool_id(e) for e in tool_results]
    if not any(call_ids + result_ids):
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
        if call.get("turn") is not None and res.get("turn") is not None \
                and call.get("turn") != res.get("turn"):
            failures.append(
                f"tool_result turn mismatch for tool_id {tid!r}: call turn "
                f"{call.get('turn')!r}, result turn {res.get('turn')!r}"
            )

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

    # ── tool 序列 ──
    if "tools" in expect:
        actual = [e.get("tool", "?") for e in tool_calls]
        expected = expect["tools"]
        if actual != expected:
            failures.append(f"tool sequence mismatch: expected {expected}, got {actual}")

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

    # ── all tools succeed ──
    if expect.get("all_tools_succeed"):
        if tool_calls and not tool_results:
            failures.append("tool_calls present but no tool_result events")
        for r in tool_results:
            if r.get("success") is not True:
                failures.append(
                    f"tool {r.get('tool', '?')} (turn {r.get('turn')}) failed: "
                    f"{str(r.get('output', ''))[:120]}"
                )

    # ── at least one tool failed ──
    if expect.get("some_tool_failed"):
        # The failure half of a fault golden. `all_tools_succeed: false` looks
        # like the natural spelling but silently disables the check (falsy), so
        # fault cases that need "the guard refused" would assert only the tool
        # sequence — green even with the guard removed.
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
    """Negative arms for the three wide3 witnesses, plus the positive shapes.

    Each arm is the *witness patch* in miniature: if the new criterion is ever
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
