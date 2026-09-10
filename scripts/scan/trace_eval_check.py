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
      "forbidden_tools": ["browser_use"]         # 可选：不允许出现的 tool
    }
  }

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


def evaluate(events, expect):
    """对一条 golden 的 expect 做断言。返回 (failures, warnings) 列表。"""
    failures, warnings = [], []

    tool_calls = [e for e in events if e.get("type") == "tool_call"]
    tool_results = [e for e in events if e.get("type") == "tool_result"]
    trace_ends = [e for e in events if e.get("type") == "trace_end"]

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
            if not r.get("success"):
                failures.append(
                    f"tool {r.get('tool', '?')} (turn {r.get('turn')}) failed: "
                    f"{str(r.get('output', ''))[:120]}"
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
            events = [json.loads(l) for l in case["trace_lines"] if l.strip()]
            bad_lines = 0
            src = "inline"
        else:
            if not os.path.exists(trace_path):
                print(f"  ❌ {name}: trace file missing: {trace_path}")
                failed += 1
                continue
            events, bad_lines = load_jsonl(trace_path)
            src = os.path.relpath(trace_path, repo_root)

        failures, warnings = evaluate(events, expect)
        if bad_lines:
            warnings.append(f"{bad_lines} malformed JSONL line(s) skipped")

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


if __name__ == "__main__":
    sys.exit(main())
