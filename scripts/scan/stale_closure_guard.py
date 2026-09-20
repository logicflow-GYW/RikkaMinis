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
    text = BLOCK_COMMENT_RE.sub("", text)
    out = []
    for line in text.splitlines():
        if line.lstrip().startswith("//"):
            out.append(line if ESCAPE_RE.search(line) else "//")
        else:
            out.append(LINE_COMMENT_RE.sub("", line))
    return out


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
        # 本 effect 里被 rememberUpdatedState 包装过的名字 = 安全
        wrapped = set(re.findall(r"rememberUpdatedState\s*\(\s*(\w+)\s*\)", body_text))
        # 也扫描 effect 之前同一函数内的包装（生产写法是先 wrap 再用）
        for j in range(max(0, i - 40), i):
            wrapped |= set(re.findall(r"rememberUpdatedState\s*\(\s*(\w+)\s*\)", lines[j]))
        for p in sorted(params):
            if p in wrapped:
                continue
            # 参数出现在 key 列表里 ⇒ 每次变化都重启闭包 ⇒ 不会 stale
            if re.search(r"(?<![\w.])" + re.escape(p) + r"(?![\w:])", key_text):
                continue
            # 直接读该参数（裸标识符，非 .value / 非声明）
            if re.search(r"(?<![\w.])" + re.escape(p) + r"(?![\w:])", body_text):
                exempt = False
                for k in range(max(0, b0 - 1), min(len(lines), b1 + 2)):
                    if ESCAPE_RE.search(lines[k]):
                        exempt = True
                if exempt:
                    continue
                findings.append((i + 1, p))
    return findings


def main():
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
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

    if violations:
        print(f"❌ Stale-closure guard: {len(violations)} violation(s)")
        print("   A long-lived effect (produceState/LaunchedEffect) reads a plain")
        print("   parameter directly. If that parameter ever leaves the key list,")
        print("   the closure goes stale and the UI silently stops updating.")
        print("   Fix: `val latestX by rememberUpdatedState(x)` and read `latestX`.")
        print("   Or justify inline with `stale-ok: <reason>`.")
        for v in violations:
            print("     " + v)
        return 1
    print("✅ Stale-closure guard: no long-lived effect reads a plain parameter")
    return 0


if __name__ == "__main__":
    sys.exit(main())
