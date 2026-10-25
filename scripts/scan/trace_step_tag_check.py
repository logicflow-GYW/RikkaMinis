#!/usr/bin/env python3
"""
trace_step_tag_check.py — agent 轨迹步骤标注器（分析） + 门禁（CI）

## 这个工具回答什么

「这个 run 到底做了什么」——把已落盘的 trace JSONL 里的每一次工具调用归到
一个语义标签，从而机械回答**「这次 run 有没有做过验证动作」**。此前这个问题
只能靠人读日志。

标签体系取自 bytedance/trae-agent 的 Lakeview（`utils/lake_view.py` 的
`WRITE_TEST / VERIFY_TEST / EXAMINE_CODE / WRITE_FIX / VERIFY_FIX / REPORT /
THINK / OUTLIER`），但打分器换成了**确定性规则**：trae 用 LLM 每步打分，是因为
它手上只有自然语言正文；本仓库的 trace 里有 tool 名 + 完整 args + success +
duration_ms，标签可以直接从结构判定（零调用成本、可复现、可变异验证）。

## 两种用法

    python3 scripts/scan/trace_step_tag_check.py <repo_root>       # 门禁（CI）
    python3 scripts/scan/trace_step_tag_check.py --trace <f.jsonl> # 分析报告
    python3 scripts/scan/trace_step_tag_check.py --selftest        # 牙齿夹具

## 门禁的三面判据（缺一面都会静默失效）

1. **schema 接线** —— 标注器读的是 `AgentTraceRecorder.kt` 写出的字段
   （`type` / `tool` / `args` / `tool_id` / `success`）。若某次重构改了事件名或
   字段名而没人同步这里，标注器不会报错，它只是**安静地数出 0 次调用**，
   然后给出一个看起来很正常的「本次 run 无写动作」结论。所以门必须钉住
   Kotlin 侧的常量与 `put("<字段名>")` 调用仍然在场。
   同一面还钉住**构行的锁归属**（`[da1029-C]`）：`write()` 的行构建必须在
   `synchronized(writeLock)` 内，与 `writeTerminalOnce()` 对称。构行读
   `activeRunId` / `activeSessionId` 并采样 `clock()`，而 `beginRun` 在同一把锁
   内成对更新这两个字段 —— 构行在锁外时，一行可能把 A 的 `run_id` 和 B 的
   `session_id` 拼在一起，且它的 `ts` 会早于并发线程落盘的行（行序与 ts 倒挂）。
2. **真实 golden trace 端到端** —— 用 `tests/traces/golden/traces/*.jsonl`
   （真实事件形状，不是合成夹具）跑一遍，断言每个 `tool_call` 都被标注到、
   标签全在词表内、无解析失败行。
3. **分类契约** —— 一张逐条手写的 `(命令 → 期望标签)` 夹具表，含三条已知的
   误判形状（见下）。期望值是**字面量**，不从被测规则推导——常量改了期望跟着变
   的话，变异会伪装成 PASS。

## 三条已知的误判形状（不是猜测，是实测差分出来的）

| 形状 | 错误做法 | 为什么错 |
|---|---|---|
| 文件名含 `check` 的**读**命令 | `wc -l scripts/scan/trace_eval_check.py` 判成 VERIFY | 判据落在「字符串包含」而不是「动词做了什么」→ 必须先取首动词 |
| `2>/dev/null` | `echo x 2>/dev/null && grep y` 判成 WRITE | stderr 重定向不是产物写入。naive 正则把 `2>` 也算写入，实测让 WRITE 虚高一倍（18 vs 8），**污染下游的验证覆盖率指标** |
| 复合命令只取首个动词 | `cd x && echo … && grep …` 只看 `cd`/`echo` | agent 的 shell 命令绝大多数是 `&&`/`|` 复合串，必须按段取动词再取最信息量的那类 |

## 边界（判据不写清就会狼来了）

- 只对**有写动作的研发类 run** 有意义。一次调研会话里 20 次「写」可能全是
  一次性探索脚本 —— 起手就用同一个指标报警，这个门第三天就会被关掉。
  所以 `--trace` 的裁定会明确区分「无写动作（不适用）」。
- **「跑自写脚本」是定义边界**：`python3 my_probe.py` 无法从脚本名推断用途，
  本工具按「非判据脚本的解释器运行 = EXAMINE」处理。若将来要把它当验证证据，
  必须先扩充词表并明确语义，而不是靠改期望值让夹具变绿。
"""
import glob
import json
import os
import re
import sys

# [wide4-09] 共享 Kotlin 词法/语法原语（12 支门共用的单一实现）。
# 本门上一轮手写了两套词法器（`strip_literals` / `mask_comments`），在 Kotlin
# 原生词法上系统性失明：嵌套块注释 `/* a /* b */ c */` 遇第一个 `*/` 就退出、
# 单引号字符 `'"'` 被当成字符串起始吞掉后面整段真代码。外部见证各一击穿。
# 现在词法一律走共享模块；解析失败**绝不**静默返回原文，由调用方 fail-closed。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import analyse as _lex_analyse  # noqa: E402
from _kotlin_ast import (  # noqa: E402
    const_bool as _const_bool,
    match_bracket as _match_bracket,
    split_top as _ast_split_top,
    if_blocks as _if_blocks,
    when_branches as _when_branches,
)

# ── 标签词表 ──────────────────────────────────────────────────────────────
TAGS = {
    "EXAMINE": "读/搜/看 —— 探索代码库或环境",
    "WRITE": "改产物 —— 写文件、改配置、装包、删文件",
    "VERIFY": "跑判据 —— 测试、门禁扫描、编译、哈希对拍、selftest",
    "VCS": "版本控制 —— git / gh 操作",
    "NET": "取外部信息 —— 抓网页、下文件、调外部 API",
    "DELEGATE": "派发 —— 子代理 / 跨会话",
    "OTHER": "其它",
}

# 同一条链上出现多类动作时的优先级：判据 > 版本控制 > 取外部 > 写 > 读。
# 顺序即语义：一条命令跑了测试，那这次调用的价值就是"验证过了"，而不是
# 顺带碰过的文件；同理 clone 比 mkdir 更能说明这次调用在干什么。
PRIORITY = ["VERIFY", "VCS", "NET", "WRITE", "EXAMINE"]

# 命令**首动词** → 类别。判据落在动词上，避免"文件名里含 check 就判 VERIFY"。
TEST_VERBS = {"pytest", "gradlew", "gradle", "junit", "scan.sh", "test_scan.py",
              "sha256sum", "shasum", "md5sum", "kotlinc", "cmp", "tox", "ctest"}
VCS_VERBS = {"git", "gh", "gh_sync.sh", "gh_fullright.sh"}
NET_VERBS = {"curl", "wget", "nc", "openssl"}
WRITE_VERBS = {"mkdir", "mv", "cp", "rm", "chmod", "ln", "touch", "unzip", "zip",
               "tar", "gzip", "apk", "pip", "pip3", "npm", "install"}
READ_VERBS = {"ls", "grep", "rg", "find", "wc", "head", "tail", "file", "which",
              "du", "stat", "tree", "jq", "readlink", "df", "env", "date", "pwd",
              "sort", "uniq", "cut", "diff", "cat", "sed", "awk", "tr", "echo",
              "printf"}
# 这几类"既可能读也可能写"，要再看重定向/`-i` 才能定性。
AMBIGUOUS = {"cat", "sed", "awk", "tr", "echo", "printf", "sort", "uniq", "cut"}
INTERPRETERS = {"python3", "python", "sh", "bash", "zsh", "perl", "ruby", "node"}

# 工具名 → 类别（决定性映射，先于文本规则）。`shell_execute` 不在此表：
# 它的类别必须由命令文本判定。
TOOL_TAGS = {
    "file_read": "EXAMINE",
    "read_image": "EXAMINE",
    "memory_get": "EXAMINE",
    "conversation_history": "EXAMINE",
    "minis_fastio": "EXAMINE",
    "mcp_call": "OTHER",
    "file_write": "WRITE",
    "file_edit": "WRITE",
    "memory_write": "WRITE",
    "memory_rollup": "WRITE",
    "browser_use": "NET",
    "minis_sessions_cli": "DELEGATE",
    "spawn_agent": "DELEGATE",
}

_PREFIX_STRIPPERS = [
    r"timeout\s+\S+\s+",
    r"nice\s+",
    r"cd\s+\S+\s*&&\s*",
    r"[A-Za-z_][A-Za-z0-9_]*=\S*\s+",
    r"nohup\s+",
    r"!?\s*",
]

_VERDICT_SCRIPT = re.compile(
    r"[\w/\-]*(check|test|verify|selftest|scan|probe|mutat|gate)[\w/\-]*\.(py|sh)\b")


def first_verb(cmd):
    """取命令片段的**首个可执行动词**（剥掉 `timeout N` / `cd X &&` / `VAR=val` 等前缀）。"""
    s = (cmd or "").strip()
    for pre in _PREFIX_STRIPPERS:
        s = re.sub(rf"^\s*{pre}", "", s)
    s = s.lstrip("({")  # 子 shell / 分组：`(git clone …)`、`{ echo …; }` 的首动词仍是 git/echo
    head = re.split(r"[;&|]{1,2}|\n", s)[0].strip()
    m = re.match(r"([\w./+\-]+)", head)
    return (m.group(1) if m else "").split("/")[-1]


def parse_args(args_raw):
    """→ (args dict 或空 dict, 尽力还原的命令文本)。

    **长命令的 args 是坏的 JSON**，这不是理论风险：`AgentTraceRecorder` 把 args
    截到 `ARGS_MAX_LENGTH = 500`（见 `put("args", sanitize(argsJson, 500))`），
    超过 500 字符的命令会被截在字符串中间 → `json.loads` 必然失败。
    此时若直接放弃，**恰恰是最长、最复杂的那些命令会一律落到 OTHER**，
    而它们正是最需要被标注的调用。所以按 JSON 前缀尽力抽取。
    """
    try:
        parsed = json.loads(args_raw or "{}")
    except (json.JSONDecodeError, TypeError):
        parsed = None
    if isinstance(parsed, dict):
        return parsed, str(parsed.get("command", "")) or str(parsed.get("cmd", ""))

    m = re.search(r'"(?:command|cmd)"\s*:\s*"(.*)', args_raw or "", re.S)
    if not m:
        return {}, ""
    frag = m.group(1)
    frag = re.sub(r"\\u[0-9a-fA-F]{4}", " ", frag)   # 截断处的半个转义也别让它污染动词
    for esc, lit in (("\\/", "/"), ('\\"', '"'), ("\\n", "\n"), ("\\t", " ")):
        frag = frag.replace(esc, lit)
    return {}, frag


def has_real_redirect(seg):
    """真写入重定向。排除 `2>` / `2>&1`（只重定向 stderr）与 `>/dev/null`。

    这条是实测出来的：naive 版本把 `2>/dev/null` 当写入，让 WRITE 虚高一倍，
    而 WRITE 虚高会直接污染下游「验证覆盖率」的分子/分母。
    """
    for m in re.finditer(r"(?<![0-9])>>?\s*(\S+)", seg):
        if m.group(1) != "/dev/null":
            return True
    return False


def classify_segment(seg):
    """把一个命令片段归到某一个类别；无法判定返回 None。"""
    v = first_verb(seg)
    if not v:
        return None
    if v in TEST_VERBS:
        return "VERIFY"
    if v in VCS_VERBS:
        return "VCS"
    if v in NET_VERBS:
        return "NET"
    if v in INTERPRETERS:
        # 解释器跑的是哪个脚本，决定这次调用在干什么
        if re.search(r"(^|[\s/])(gh_sync|gh_fullright)\.sh\b", seg):
            return "VCS"
        if _VERDICT_SCRIPT.search(seg):
            return "VERIFY"
        # 跑判据脚本 = 验证；跑一次性脚本 = 探索（用途不可从名字推断，见模块 docstring 边界）
        return "WRITE" if has_real_redirect(seg) else "EXAMINE"
    if v in AMBIGUOUS:
        if re.search(r"sed\s+-i", seg) or has_real_redirect(seg) or re.search(r"\|\s*tee\s", seg):
            return "WRITE"
        return "EXAMINE"
    if v in WRITE_VERBS:
        return "WRITE"
    if v in READ_VERBS:
        return "EXAMINE"
    return None


def tag_call(tool, args_raw):
    """给一次工具调用打标签。`args_raw` 是 trace 里的原始 args 字符串。"""
    t = (tool or "").strip()
    a, cmd = parse_args(args_raw)

    if t not in ("shell_execute", "bash", "terminal"):
        if t in TOOL_TAGS:
            base = TOOL_TAGS[t]
            if base == "EXAMINE":
                # 读类工具也可能藏着外部抓取（如 browser_use 的 url）
                blob = " ".join(str(a.get(k, "")) for k in ("url", "query", "pattern"))
                if re.search(r"https?://", blob):
                    return "NET"
            return base
        return "OTHER"

    if not cmd:
        return "OTHER"

    found = set()
    for seg in re.split(r"&&|\|\||;|\||\n", cmd):
        c = classify_segment(seg.strip())
        if c:
            found.add(c)
    for cls in PRIORITY:
        if cls in found:
            return cls
    return "OTHER"


# ── trace 读取与分析 ─────────────────────────────────────────────────────
TRACE_REQUIRED_FIELDS = ["tool", "args", "tool_id", "success"]
TRACE_REQUIRED_TYPES = ["tool_call", "tool_result", "turn_start", "turn_end"]


def load_events(path):
    """读 JSONL；坏行跳过并计数（trace 是旁路证据，局部写坏不该让整个分析崩掉）。"""
    events, bad = [], 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            if isinstance(obj, dict):
                events.append(obj)
            else:
                bad += 1
    return events, bad


def analyze(events):
    """把事件流还原成 (turns, 标注序列, 按标签计数, 按标签失败计数)。"""
    calls, order, results = {}, [], []
    for e in events:
        ty = e.get("type")
        if ty == "tool_call":
            calls[str(e.get("tool_id"))] = e
            order.append(str(e.get("tool_id")))
        elif ty == "tool_result":
            results.append(e)

    seq = []
    for tid in order:
        c = calls.get(tid, {})
        raw = c.get("args") or ""
        seq.append({
            "tool_id": tid,
            "turn": c.get("turn"),
            "tool": c.get("tool"),
            "tag": tag_call(c.get("tool"), raw),
            "args": raw[:180],
        })

    counts, fails = {}, {}
    for s in seq:
        counts[s["tag"]] = counts.get(s["tag"], 0) + 1
    for r in results:
        c = calls.get(str(r.get("tool_id")), {})
        if not r.get("success", True):
            t = tag_call(c.get("tool"), c.get("args") or "")
            fails[t] = fails.get(t, 0) + 1

    turns = sum(1 for e in events if e.get("type") == "turn_end")
    return {"turns": turns, "calls": len(seq), "counts": counts, "fails": fails, "seq": seq}


def verdict(counts):
    """验证覆盖率裁定。scope 说明见模块 docstring 的"边界"一节。"""
    ver, wri = counts.get("VERIFY", 0), counts.get("WRITE", 0)
    if wri == 0:
        return "无写动作 —— 纯调研/问答 run，验证覆盖率不适用。"
    if ver == 0:
        return "★ 有 %d 次写动作但 0 次验证 —— 产出未经任何判据检验。" % wri
    return "写 %d 次 / 验证 %d 次 = 每 %.2f 次写有 1 次验证。" % (wri, ver, wri / ver)


def report(path):
    events, bad = load_events(path)
    a = analyze(events)
    print("trace: %s" % path)
    print("事件 %d 条（坏行 %d）· %d 轮 · %d 次工具调用"
          % (len(events), bad, a["turns"], a["calls"]))
    print("-" * 68)
    total = a["calls"] or 1
    for tag in list(TAGS) + ["OTHER"]:
        n = a["counts"].get(tag, 0)
        if not n:
            continue
        f = a["fails"].get(tag, 0)
        print("%-9s %3d  %-30s %3d%%  %s%s"
              % (tag, n, "#" * max(1, round(n * 30 / total)), n * 100 // total,
                 TAGS.get(tag, ""), ("  失败 %d" % f) if f else ""))
    print("-" * 68)
    print("裁定：" + verdict(a["counts"]))
    return 0


# ── 门禁 ────────────────────────────────────────────────────────────────
# 逐条手写的分类契约。期望值是字面量 —— 不许从 tag_call 推导。
# 覆盖：三类基础动作 × 三条已知误判形状 × 边界形态（无重定向 / 链式 / 解释器）。
CONTRACT = [
    # 读
    ("shell_execute", '{"command":"ls -la /tmp"}', "EXAMINE"),
    ("shell_execute", '{"command":"grep -rn \\"foo\\" src/"}', "EXAMINE"),
    ("shell_execute", '{"command":"cd /x && grep -rn foo src/"}', "EXAMINE"),
    ("shell_execute", '{"command":"sed -n \'1,20p\' a.kt"}', "EXAMINE"),
    ("shell_execute", '{"command":"find src -name \'*.kt\' | xargs wc -l"}', "EXAMINE"),
    # 形状①：文件名含 check 的**读**命令 —— 判据必须落在动词上
    ("shell_execute", '{"command":"cd /r && wc -l scripts/scan/trace_eval_check.py"}', "EXAMINE"),
    ("shell_execute", '{"command":"cat scripts/scan/test_scan.py"}', "EXAMINE"),
    # 形状②：`2>/dev/null` 不是写入；naive 正则在此让 WRITE 虚高一倍
    ("shell_execute", '{"command":"echo \'=== x ===\' 2>/dev/null && grep -n y z.kt"}', "EXAMINE"),
    ("shell_execute", '{"command":"du -sh . 2>/dev/null"}', "EXAMINE"),
    ("shell_execute", '{"command":"minis-model-use list 2>&1 | head -60"}', "EXAMINE"),
    # 形状② 的另一半：`2>&1` 也不是写入。naive 正则在此匹配到 `>&1`（第 0 组 = `&1`），
    # 与 /dev/null 排除无关，所以只有 lookbehind 能挡 —— 单独一臂钉住它。
    ("shell_execute", '{"command":"echo \'=== x ===\' 2>&1 | head -5"}', "EXAMINE"),
    # 形状② 的第三种写法：**裸** `> /dev/null`（无 fd 数字，lookbehind 挡不住）。
    # 仓库约定里的后台服务写法就是 `cmd > /dev/null 2>&1 &`，这是真实高频形状。
    ("shell_execute", '{"command":"echo done > /dev/null && grep -n x y.kt"}', "EXAMINE"),
    # 形状③：链式命令 —— 全链的类别由最信息量的那类决定，不是首动词
    ("shell_execute", '{"command":"cd /w && mkdir -p a && cd a && git clone --depth 1 u r"}', "VCS"),
    ("shell_execute", '{"command":"cd /w && rm -f o.txt && python3 gate_check.py"}', "VERIFY"),
    ("shell_execute", '{"command":"curl -s http://x/api | head -5"}', "NET"),
    # 写
    ("shell_execute", '{"command":"echo hi > /tmp/x"}', "WRITE"),
    ("shell_execute", '{"command":"sed -i \'s/a/b/\' f.kt"}', "WRITE"),
    ("shell_execute", '{"command":"mkdir -p /var/minis/shared/work/x"}', "WRITE"),
    ("shell_execute", '{"command":"cat > /var/minis/workspace/f.py"}', "WRITE"),
    ("shell_execute", '{"command":"git apply p.diff"}', "VCS"),
    # 验证
    ("shell_execute", '{"command":"./gradlew testReleaseUnitTest"}', "VERIFY"),
    ("shell_execute", '{"command":"pytest -q tests/"}', "VERIFY"),
    ("shell_execute", '{"command":"sh scripts/scan/scan.sh"}', "VERIFY"),
    ("shell_execute", '{"command":"sha256sum -c sums.txt"}', "VERIFY"),
    ("shell_execute", '{"command":"cd /r && python3 scripts/scan/x_check.py --selftest"}', "VERIFY"),
    # VCS / NET
    ("shell_execute", '{"command":"git log --oneline -3"}', "VCS"),
    ("shell_execute", '{"command":"sh gh_sync.sh push --branch b"}', "VCS"),
    ("shell_execute", '{"command":"wget -q https://x/y.zip"}', "NET"),
    # 边界：非 shell 工具走决定性映射
    ("file_read", '{"path":"/tmp/a.kt"}', "EXAMINE"),
    ("file_write", '{"path":"/tmp/a.kt","content":"x"}', "WRITE"),
    ("spawn_agent", '{"query":"do it"}', "DELEGATE"),
    ("browser_use", '{"action":"navigate","url":"https://x.com"}', "NET"),
    # 边界：无命令的 shell 调用不得崩
    ("shell_execute", "{}", "OTHER"),
    # 边界：子 shell / 分组前缀 —— `(git …)` 的首动词仍是 git，不是 '('
    ("shell_execute",
     '{"command":"cd /a && mkdir -p w && (git clone --depth 1 u r 2>&1 | tail -5) && du -sh r"}',
     "VCS"),
    # 形状④：args 被 AgentTraceRecorder 截到 500 字符 → **坏 JSON**。
    # 长命令恰恰最需要被标注，不能因为解析失败就一律落到 OTHER。
    ("shell_execute",
     '{"command":"cd \\/a && echo \\"=== x ===\\" && grep -rn \\"StepKind\\\\|stepType\\" src\\/tools\\/*.kt src',
     "EXAMINE"),
]


def face_contract():
    """面 3：分类契约逐条比对。"""
    bad = []
    for tool, args, expect in CONTRACT:
        got = tag_call(tool, args)
        if got != expect:
            bad.append("    %s %s -> 期望 %s 实得 %s" % (tool, args[:64], expect, got))
    if bad:
        print("  ❌ 分类契约失配 %d 条：" % len(bad))
        for b in bad:
            print(b)
        return False
    print("  ✅ 分类契约 %d 条全对" % len(CONTRACT))
    return True


def face_golden(root):
    """面 2：真实 golden trace 端到端。标签数必须与 tool_call 数一一对上。"""
    traces = sorted(glob.glob(os.path.join(
        root, "tests", "traces", "golden", "traces", "*.jsonl")))
    if not traces:
        print("  ❌ 找不到 tests/traces/golden/traces/*.jsonl —— 端到端面无法取证")
        return False
    ok = True
    for path in traces:
        events, bad = load_events(path)
        a = analyze(events)
        declared = sum(1 for e in events if e.get("type") == "tool_call")
        problems = []
        if bad:
            problems.append("坏行 %d" % bad)
        if declared == 0:
            problems.append("0 次 tool_call（真实 golden 不该为空）")
        if a["calls"] != declared:
            problems.append("标注 %d != 声明 %d" % (a["calls"], declared))
        unknown = [t for t in a["counts"] if t not in TAGS and t != "OTHER"]
        if unknown:
            problems.append("标签越界 %s" % unknown)
        if problems:
            print("  ❌ %s：%s" % (os.path.basename(path), "; ".join(problems)))
            ok = False
        else:
            print("  ✅ %s：%d 次调用 -> %s"
                  % (os.path.basename(path), a["calls"],
                     ", ".join("%s=%d" % (k, a["counts"][k]) for k in sorted(a["counts"]))))
    return ok


def brace_block_end(code, open_idx):
    """Index just past the '}' matching the '{' at `open_idx`, or -1."""
    depth = 0
    for i in range(open_idx, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return -1


def match_paren(code, open_idx):
    """Index of the ')' matching the '(' at `open_idx`, or -1."""
    depth = 0
    for i in range(open_idx, len(code)):
        if code[i] == "(":
            depth += 1
        elif code[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


# [wide3-04] Text between a parameter list's `)` and the body `{` may only be
# whitespace + an optional return type. Anything else means the body was not
# located and every judgement below would slice the wrong text.
_RET_TYPE_ONLY = re.compile(r"^\s*(:\s*[\w<>,\[\]\s.?:]+)?\s*$")


def body_brace(code, decl):
    """(decl_idx, body_open_brace) for a `private fun name(` declaration.

    The body brace is the first `{` AFTER the parameter list closes. The old
    version took the first `{` after the declaration name, which a default
    parameter value swallows:
        private fun write(t: String, block: J.() -> Unit = { sync { build(...) } })
    then sliced the DEFAULT LAMBDA as the function body — and a body whose lock
    was removed still looked locked. Returns (-1, -1) when the declaration is
    missing and (decl_idx, -1) when its body cannot be located (caller reports
    ROT rather than passing).
    """
    start = code.find(decl)
    if start < 0:
        return -1, -1
    po = code.find("(", start)
    if po < 0:
        return start, -1
    pc = match_paren(code, po)
    if pc < 0:
        return start, -1
    ob = code.find("{", pc)
    if ob < 0 or not _RET_TYPE_ONLY.match(code[pc + 1:ob]):
        return start, -1
    return start, ob


def split_top(text, sep):
    """[(start, end)] of `text` split on top-level `sep`, quote/brace aware."""
    spans, depth, start, i, n = [], 0, 0, 0, len(text)
    quote = ""
    while i < n:
        c = text[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = ""
            i += 1
            continue
        if c in "\"'":
            quote = c
            i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == sep and depth == 0:
            spans.append((start, i))
            start = i + 1
        i += 1
    spans.append((start, n))
    return spans


def _const_val(cond):
    """条件能否折成字面量：True / False / None（未知）。

    [wide4-09 family-hole] 上一轮只认裸 `true` / `false` / `0` / `1`：
      `if (!true) put("args", …)`   —— 逻辑非没处理，provably_false 返回 False
      `if (1 == 2) put("args", …)`  —— 常量相等比较没处理，同上
    两条都让字段的写出路径整段消失，门却判绿。现在用共享常量折叠
    （`_kotlin_ast.const_bool`，覆盖 `!X` / `X && Y` / `X || Y`（短路语义）/ `A == B`）。
    """
    return _const_bool(cond)


def provably_false(cond):
    """Is this `if` condition decided by a literal BEFORE the real guard runs?

    `if (false && guard)` and `if (false)` are dead bodies.  Only the DEAD
    direction is reported here - a `put(field)` that can never execute is a
    missing field.  A condition that only folds to TRUE (`if (true) …`) is not
    reported by this function; the lock-scope face judges that shape itself
    (see `_early_return_before`).
    """
    return _const_bool(cond) is False


def if_bodies(code):
    """[(cond, then_start, then_end, else_start, else_end)] —— 共享 AST 的薄包。

    保留这个名字给下游（以及 selftest）用；真正的结构解析在 `_kotlin_ast`：
    它覆盖 brace-bodied / braceless / else 臂三种形态，手写版漏了 else 臂。
    """
    return _if_blocks(code)


def covering_conditions(code, idx):
    """覆盖 `idx` 的**分支条件**（else 臂取反）。

    与 `dead_guards_at` 同源，但不管条件是否恒假 —— 用来判「这个 `return`
    是不是有条件的」（无覆盖条件 = 无条件早退）。
    """
    out = []
    for cond, t0, t1, e0, e1 in _if_blocks(code):
        if t0 <= idx < t1:
            out.append(cond.strip())
        elif e0 is not None and e0 <= idx < e1:
            out.append("!(" + cond.strip() + ")")
    for subject, cond, b0, b1 in _when_branches(code):
        if b0 <= idx < b1:
            out.append("when (%s) %s" % (subject.strip(), cond.strip()))
    return out


def dead_guards_at(code, idx):
    """Conditions of the enclosing branches that provably never run at `idx`.

    [wide4-09] 结构由共享 AST 派生（`if` 的 then/else 臂 + `when` 臂），恒假由
    共享常量折叠判定。上一轮只扫自己手写的「单语句 if」，于是
    `when (false) { true -> put("args", …) }` 与 `if (1 == 2) { … }` 全漏。
    """
    dead = []
    for cond, t0, t1, e0, e1 in _if_blocks(code):
        if t0 <= idx < t1:
            if _const_bool(cond) is False:
                dead.append(cond.strip())
        elif e0 is not None and e0 <= idx < e1:
            if _const_bool(cond) is True:
                dead.append("!(" + cond.strip() + ")")
    for subject, cond, b0, b1 in _when_branches(code):
        if b0 <= idx < b1:
            sv = _const_bool(subject) if subject.strip() else None
            cv = _const_bool(cond)
            # 常量 subject 与常量臂标签不相等 -> 该臂永不执行
            if sv is not None and cv is not None and sv != cv:
                dead.append("%s ->" % cond.strip())
    return dead


def mask_comments(src):
    """注释抹空格、字符串原样（字段名活在字符串里）。

    [wide4-09 parser-death] 手写版遇第一个 `*/` 就退出块注释，于是
    `/* outer /* inner */ put("args", …) */` 里被 Kotlin 当作注释丢弃的字段
    在掩码视图里成了活代码 —— 存在性/可达性/非常量三项全通过。
    现在委托共享词法器（嵌套深度计数 + 字符字面量 + 三引号 + 模板插值）。
    解析失败返回 None，调用方必须 fail-closed 报违规，不许静默返回原文。
    """
    ok, _reason, views = _lex_analyse(src)
    return views["comments"] if ok else None


def is_constant_expr(text):
    """True when an expression contains no identifier/member reference at all —
    i.e. the value cannot depend on the data it is supposed to describe.

    [wide4-09] 这条判据**单独用不够**：`sanitize("", ARGS_MAX_LENGTH)` 把数据源
    换成空串常量、外面再包一层纯函数，仍然「有标识符」（函数名 + 常量名）。
    真正的判据是 `_value_failure`：值必须派生自形参/局部量。
    """
    t = re.sub(r'"""(?:.|\n)*?"""', " ", text)
    t = re.sub(r'"[^"\n]*"', " ", t)
    t = re.sub(r"'[^'\n]*'", " ", t)
    t = re.sub(r"\b(?:true|false|null)\b", " ", t)
    t = re.sub(r"\b\d[\w.]*\b", " ", t)
    t = re.sub(r"[\s,=()]", "", t)
    return t == ""


# ── [wide4-09] 字段值必须派生自输入（把「非常量」升级成「派生自形参」）──────
FUN_DECL_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")
_UPPER_CONST_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")
_NOT_DATA = ("true", "false", "null", "this")


def _fun_spans(code):
    """[(name, params_text, body_start, body_end)] —— 从发布树派生，不写死函数名。

    体起点从参数表右括号之后派生（默认参数 lambda 不会冒充函数体），
    表达式体 `fun f() = …` 也纳入（到深度 0 的换行为止）。
    """
    out = []
    for m in FUN_DECL_RE.finditer(code):
        po = m.end() - 1
        pc = _match_bracket(code, po)
        if pc < 0:
            continue
        k = pc + 1
        while k < len(code) and code[k] in " \t\r\n":
            k += 1
        while k < len(code) and code[k] not in "={":
            if code[k] == "\n":
                break
            k += 1
        if k < len(code) and code[k] == "{":
            be = _match_bracket(code, k)
            if be < 0:
                continue
            out.append((m.group(1), code[po + 1:pc], k + 1, be))
        elif k < len(code) and code[k] == "=":
            end = k + 1
            depth = 0
            while end < len(code):
                ch = code[end]
                if ch in "([{":
                    depth += 1
                elif ch in ")]}":
                    if depth == 0:
                        break
                    depth -= 1
                elif ch == "\n" and depth == 0:
                    break
                end += 1
            out.append((m.group(1), code[po + 1:pc], k + 1, end))
    return out


def _param_names(params_text):
    """形参名（`@Ann vararg name: Type = default` -> name）。"""
    names = []
    for a, b in _ast_split_top(params_text, (",",)):
        seg = params_text[a:b].strip()
        if not seg:
            continue
        mm = re.match(r"(?:@\w+\s+)*(?:(?:vararg|noinline|crossinline)\s+)?"
                      r"([A-Za-z_]\w*)\s*[:=]", seg)
        if mm:
            names.append(mm.group(1))
    return names


def _enclosing_fun(code, idx):
    """最内层包含 `idx` 的函数 span（顶层夹具没有函数 -> None）。"""
    best = None
    for span in _fun_spans(code):
        if span[2] <= idx < span[3]:
            if best is None or (span[3] - span[2]) < (best[3] - best[2]):
                best = span
    return best


def _value_failure(value, code, put_idx):
    """`put(field, value)` 的值为什么不能描述事件（None = 合格）。

    [wide4-09 family-hole] 上一轮只问「表达式里还有没有标识符」，于是把数据源
    换成空串常量、外面包一层纯函数（`sanitize("", ARGS_MAX_LENGTH)`）仍然算
    「非常量」，4 个必填字段全部可以这样被掏空而门判绿。
    新判据（归一化等价，不列黑名单）：值里**必须有一个引用指向形参或局部量**
    —— 全大写常量名、函数/方法调用名、true/false/null/this 都不算数据。
    """
    if is_constant_expr(value):
        return "的值是常量（%s）—— 包含不了任何事件数据" % (value.strip() or "<空>")
    residual = []
    for m in re.finditer(r"[A-Za-z_]\w*", value):
        name = m.group(0)
        if name in _NOT_DATA:
            continue
        if _UPPER_CONST_RE.match(name):            # ARGS_MAX_LENGTH 之类
            continue
        if re.match(r"\s*\(", value[m.end():]):   # 函数/方法名
            continue
        residual.append(name)
    if not residual:
        return ("的值里除了函数调用与常量名没有任何数据引用（%s）—— "
                "数据源被替换成了常量" % value.strip())
    fn = _enclosing_fun(code, put_idx)
    if fn is None:
        return None                                 # 顶层夹具：无形参可判，退回旧判据
    params = set(_param_names(fn[1]))
    locals_ = set(re.findall(r"\b(?:val|var)\s+([A-Za-z_]\w*)", code[fn[2]:fn[3]]))
    if not any(n in params or n in locals_ for n in residual):
        return ("的值不派生自任何形参/局部量（引用了 %s；%s 的形参是 %s）"
                % (", ".join(sorted(set(residual))), fn[0],
                   ", ".join(sorted(params)) or "<无>"))
    return None


# 面 1b 的锚点（[da1029-C / I09]）
WRITE_DECL = "private fun write("
TERMINAL_DECL = "private fun writeTerminalOnce("
BEGIN_RUN_DECL = "fun beginRun("
RUN_CONTEXT_FIELDS = ("activeRunId", "activeSessionId", "terminalWritten")
LOCK_CALL = "synchronized(writeLock)"
BUILD_LINE_CALL = "buildLine("


# ── [wide4-09] 锁的**身份**与锁内语句的**可达性** ─────────────────────────
WRITE_LOCK_DECL_RE = re.compile(
    r"\b(?:private\s+|internal\s+|protected\s+)?val\s+writeLock\b([^\n]*)")


def _lock_field_problems(code):
    """writeLock 必须是**稳定实例字段**（wide4-09 尝试 3，family-hole）。

    `private val writeLock: Any get() = Any()` 保留了每一处
    `synchronized(writeLock)` 的拼写，但每次访问都在堆上分配一个全新 monitor
    —— 三个写者各自锁在不同的锁上，全类互斥当场退化为无锁。上一轮判据只问
    「调用点落在锁块内」，所以这行声明一改，六条子判据全绿。
    """
    hits = list(WRITE_LOCK_DECL_RE.finditer(code))
    if not hits:
        return ["AgentTraceRecorder.kt: writeLock is not a stable instance field — "
                "no `val writeLock = Any()` declaration found (ROT — update this gate)"]
    problems = []
    for m in hits:
        tail = m.group(1)
        if re.search(r"\bget\s*\(", tail):
            problems.append(
                "AgentTraceRecorder.kt: writeLock is not a stable instance field — the "
                "declaration has a getter (`get() = Any()`), so every synchronized(writeLock) "
                "locks a NEWLY allocated monitor and the writers are not mutually exclusive "
                "at all (wide4-09 尝试 3)")
            continue
        if not re.search(r"=\s*(?:Any|Object)\s*\(\s*\)", tail):
            problems.append(
                "AgentTraceRecorder.kt: writeLock is not a stable instance field — expected "
                "`val writeLock = Any()`, got `%s` (wide4-09 尝试 3)" % tail.strip())
    return problems


def _early_return_before(code, lo, hi, target):
    """`[lo, hi)` 内、`target` 之前是否存在**必然执行**的 `return`。

    返回描述串，没有则 None。`if (true) return` 或裸 `return` 让 target 及其后
    全部语句不可达 —— beginRun 的三行 run 上下文赋值与 trace_start 落盘一起变成
    死代码，而 `lock_open < w < lock_end` 依旧成立（wide4-09 尝试 4 self_doubt）。
    带标签的 `return@label` 退出的是 lambda，不算。
    """
    stop = min(target, hi)
    for m in re.finditer(r"\breturn\b", code[lo:stop]):
        if code[lo + m.end():lo + m.end() + 1] == "@":
            continue
        pos = lo + m.start()
        conds = covering_conditions(code, pos)
        if not conds:
            return "unconditional `return` (body offset %d)" % pos
        if any(_const_bool(c) is True for c in conds):
            return "`return` under a constant-true condition (%s)" % conds[0].strip()
    return None


def face_lock_scope(src):
    """面 1b [da1029-C / I09]：构行必须在 writeLock 内 —— 与 writeTerminalOnce 对称。

    `buildLine` 读非 @Volatile 的 activeRunId / activeSessionId 并采样 clock()，
    而 `beginRun` 是在同一把锁内**成对**更新这两个字段的。构行放在锁外时：
    本行可能读到半更新的 run 上下文（run_id 来自 A、session_id 来自 B），且
    采样到的 `ts` 会早于并发线程落盘的行 —— 落盘行序与 ts 倒挂。

    判据落在**锁归属**上（不是 buildLine 的调用形态），所以把构行内联进
    synchronized 块、或先赋给局部变量，都算通过。
    """
    problems = []
    # [wide4-09 parser-death] 共享词法器（嵌套块注释 / 字符字面量 / 三引号 /
    # 模板插值）；`'"'` 在手写版里会把后面整段真代码当字符串抹掉。
    ok, reason, views = _lex_analyse(src)
    if not ok:
        return ["AgentTraceRecorder.kt: cannot lex (%s) — the gate refuses to brace-match "
                "write() on a broken lexer view (fail-closed, wide4-09)" % reason]
    code = views["code"]
    problems.extend(_lock_field_problems(code))
    for decl in (WRITE_DECL, TERMINAL_DECL):
        start, open_brace = body_brace(code, decl)
        if start < 0:
            problems.append("AgentTraceRecorder.kt: '%s' not found (ROT — update this gate)" % decl)
            continue
        if open_brace < 0:
            problems.append("AgentTraceRecorder.kt: %s body does not open after its "
                            "parameter list (ROT — update this gate)" % decl)
            continue
        end = brace_block_end(code, open_brace)
        if end < 0:
            problems.append("AgentTraceRecorder.kt: %s body braces do not balance "
                            "(ROT — update this gate)" % decl)
            continue
        body = code[open_brace:end + 1]
        name = decl[len("private fun "):-1]
        lock_at = body.find(LOCK_CALL)
        if lock_at < 0:
            problems.append("AgentTraceRecorder.kt: %s has no '%s' around the line build — "
                            "the run context it reads is written under that lock by beginRun"
                            % (name, LOCK_CALL))
            continue
        lock_open = body.find("{", lock_at)
        lock_end = brace_block_end(body, lock_open) if lock_open >= 0 else -1
        if lock_end < 0:
            problems.append("AgentTraceRecorder.kt: %s's synchronized block braces do not "
                            "balance (ROT — update this gate)" % name)
            continue
        builds = [m.start() for m in re.finditer(re.escape(BUILD_LINE_CALL), body)]
        if not builds:
            problems.append("AgentTraceRecorder.kt: %s never calls '%s' — the write path "
                            "cannot be serialized (ROT — update this gate)"
                            % (name, BUILD_LINE_CALL))
            continue
        for p in builds:
            if not (lock_open < p < lock_end):
                problems.append("AgentTraceRecorder.kt: '%s' at body offset %d of %s is outside "
                                "'%s' — a line built while the lock is free can pair run_id from "
                                "one run with session_id from the next, and its ts can precede "
                                "lines appended by a concurrent writer"
                                % (BUILD_LINE_CALL, p, name, LOCK_CALL))
                continue
            dead = dead_guards_at(body, p)
            if dead:
                problems.append("AgentTraceRecorder.kt: %s builds a line inside a dead branch "
                                "(%s) — the call sits in the lock block, but it never runs "
                                "(wide4-09 family-hole)" % (name, (dead[0] or "?").strip()))
            early = _early_return_before(body, lock_open, lock_end, p)
            if early:
                problems.append("AgentTraceRecorder.kt: %s returns before '%s' (%s) — the line "
                                "is never built although the call sits in the lock block "
                                "(wide4-09 family-hole)" % (name, BUILD_LINE_CALL, early))

    # [wide3-04] The WRITE side of the same contract. The criteria above judge
    # the readers only, so dropping the lock around `beginRun`'s three-field
    # update left every one of them green: the readers still hold the lock, but
    # what they read is no longer updated atomically. A concurrent writer can
    # then build a line from run A's id plus run B's session id, or clear
    # `terminalWritten` between the check and the append of the terminal event.
    start, open_brace = body_brace(code, BEGIN_RUN_DECL)
    if start < 0:
        problems.append("AgentTraceRecorder.kt: '%s' not found (ROT — update this gate)"
                        % BEGIN_RUN_DECL)
    elif open_brace < 0:
        problems.append("AgentTraceRecorder.kt: beginRun's body does not open after its "
                        "parameter list (ROT — update this gate)")
    else:
        end = brace_block_end(code, open_brace)
        if end < 0:
            problems.append("AgentTraceRecorder.kt: beginRun's body braces do not balance "
                            "(ROT — update this gate)")
        else:
            body = code[open_brace:end + 1]
            lock_at = body.find(LOCK_CALL)
            if lock_at < 0:
                problems.append("AgentTraceRecorder.kt: beginRun updates the run context with "
                                "no '%s' — the readers hold the lock but the fields they read "
                                "are no longer updated atomically (a line can pair run_id from "
                                "one run with session_id from the next)" % LOCK_CALL)
            else:
                lock_open = body.find("{", lock_at)
                lock_end = brace_block_end(body, lock_open) if lock_open >= 0 else -1
                if lock_end < 0:
                    problems.append("AgentTraceRecorder.kt: beginRun's synchronized block "
                                    "braces do not balance (ROT — update this gate)")
                else:
                    for fld in RUN_CONTEXT_FIELDS:
                        writes = [m.start() for m in
                                  re.finditer(r"\b%s\s*=(?!=)" % fld, body)]
                        if not writes:
                            problems.append("AgentTraceRecorder.kt: beginRun no longer assigns "
                                            "'%s' — the run context the trace line carries is "
                                            "not reset per run (ROT — update this gate)" % fld)
                        for w in writes:
                            dead = dead_guards_at(body, w)
                            if dead:
                                problems.append(
                                    "AgentTraceRecorder.kt: beginRun assigns '%s' inside a dead "
                                    "branch (%s) — the field is never updated: every run keeps "
                                    "the previous run's context and `terminalWritten` stays "
                                    "true, so trace_end is never written (wide4-09 尝试 4)"
                                    % (fld, (dead[0] or "?").strip()))
                                continue
                            if not (lock_open < w < lock_end):
                                problems.append(
                                    "AgentTraceRecorder.kt: beginRun assigns '%s' outside "
                                    "'%s' — a concurrent write() can observe a half-updated "
                                    "run context (run_id from one run, session_id from the "
                                    "next) and a ts that precedes lines already appended"
                                    % (fld, LOCK_CALL))
                                continue
                            early = _early_return_before(body, lock_open, lock_end, w)
                            if early:
                                problems.append(
                                    "AgentTraceRecorder.kt: beginRun assigns '%s' after %s — "
                                    "the write never executes, so the run context is never "
                                    "updated (wide4-09 尝试 4)" % (fld, early))
    return problems


def face_schema(root):
    """面 1：schema 接线 —— Kotlin 侧仍写出标注器依赖的事件名与字段名 + 构行在锁内。"""
    kt = os.path.join(root, "src", "android", "app", "src", "main", "java",
                      "com", "rikkaminis", "app", "tools", "AgentTraceRecorder.kt")
    if not os.path.exists(kt):
        print("  ❌ 找不到 AgentTraceRecorder.kt —— 接线面无法取证")
        return False
    src = open(kt, "r", encoding="utf-8", errors="replace").read()
    # [wide3-04] Comments are masked (prose must never satisfy a criterion) but
    # string literals are kept — the field NAMES live inside them.
    # [wide4-09] The mask comes from the shared lexer; an unparsable file is a
    # FAIL, never "judge the raw text anyway" (that is how parser-death works).
    code = mask_comments(src)
    if code is None:
        print("  ❌ AgentTraceRecorder.kt 无法解析（引号/块注释未闭合）—— 门拒绝在残缺词法上作判断"
              "（fail-closed，wide4-09 parser-death）")
        return False
    missing = []
    for ty in TRACE_REQUIRED_TYPES:
        if ('"%s"' % ty) not in code:
            missing.append("事件类型 %s" % ty)
    for fld in TRACE_REQUIRED_FIELDS:
        needle = 'put("%s"' % fld
        hits = [m.start() for m in re.finditer(re.escape(needle), code)]
        if not hits:
            missing.append("字段 put(\"%s\")" % fld)
            continue
        # [wide3-04] The field must be written on a reachable path AND carry a
        # derived value. `if (false) put("args", …)` keeps the literal in the
        # file while every tool_call loses its args; `put("args", "")` keeps the
        # path while the analyser can no longer tell VERIFY_TEST from
        # WRITE_FIX. Both used to be invisible to the substring check.
        # [wide4-09] Reachability now also covers `if (!true)` / `if (1 == 2)` /
        # `when (false) { … }`, and "derived value" now means "derived from a
        # parameter or local", not merely "contains some identifier".
        live = []
        for h in hits:
            dead = dead_guards_at(code, h)
            if dead:
                missing.append("字段 put(\"%s\")只出现在恒不执行的 if 分支里（%s）—— 字段永远写不出来"
                               % (fld, (dead[0] or "?").strip()))
                continue
            live.append(h)
        for h in live:
            po = code.find("(", h)
            pc = match_paren(code, po) if po >= 0 else -1
            if pc < 0:
                missing.append("字段 put(\"%s\") 的实参表无法解析（fail-closed）" % fld)
                continue
            inner = code[po + 1:pc]
            args = [inner[a:b] for a, b in split_top(inner, ",")]
            value = args[1] if len(args) > 1 else ""
            why = _value_failure(value, code, h)
            if why:
                missing.append("字段 put(\"%s\")%s" % (fld, why))
    if missing:
        print("  ❌ 标注器依赖的 trace 契约在 Kotlin 侧缺失：%s" % ", ".join(missing))
        print("     （标注器不会报错，只会安静地数出 0 次调用 —— 本面就是拦这个）")
        return False
    print("  ✅ AgentTraceRecorder.kt 仍写出 %d 个事件类型 + %d 个字段（均可达且值非常量）"
          % (len(TRACE_REQUIRED_TYPES), len(TRACE_REQUIRED_FIELDS)))
    lock_problems = face_lock_scope(src)
    if lock_problems:
        for p in lock_problems:
            print("  ❌ " + p)
        return False
    print("  ✅ write()/writeTerminalOnce() 的行构建都在 %s 内；beginRun 的三字段更新也在同一把锁内"
          % LOCK_CALL)
    return True


def gate(root):
    print("trace_step_tag_check — 标注器三面判据")
    results = [face_schema(root), face_golden(root), face_contract()]
    if all(results):
        print("TRACE_STEP_TAG_CHECK: OK")
        return 0
    print("TRACE_STEP_TAG_CHECK: FAIL")
    return 1


# ── selftest：证明三面各自有牙 ───────────────────────────────────────────
def selftest():
    """三面各造一个坏输入，断言对应面**当场变红**且互不连坐。

    没有这一段，"门被掏空成 print(OK)" 与"门真的在判"在输出上无法区分。
    """
    global CONTRACT
    import contextlib
    import io
    import shutil
    import tempfile

    fails = []

    def quiet(fn, *a):
        """吞掉被测面的输出（它按设计会打印红色诊断），只取返回值。"""
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            return fn(*a)

    # 牙 1：Kotlin 侧少一个字段 -> 面 1 必须红
    root = tempfile.mkdtemp(prefix="tst-tag-")
    kt_dir = os.path.join(root, "src", "android", "app", "src", "main", "java",
                          "com", "rikkaminis", "app", "tools")
    os.makedirs(kt_dir)
    with open(os.path.join(kt_dir, "AgentTraceRecorder.kt"), "w") as fh:
        fh.write('const val TYPE_TOOL_CALL = "tool_call"\n'
                 'const val TYPE_TOOL_RESULT = "tool_result"\n'
                 'const val TYPE_TURN_START = "turn_start"\n'
                 'const val TYPE_TURN_END = "turn_end"\n'
                 'put("tool", x)\nput("tool_id", x)\nput("success", x)\n')
    # 注意：此处**故意**不写 put("args", …) —— 少一个字段就是面 1 的坏输入
    if quiet(face_schema, root):
        fails.append("牙1：Kotlin 缺 put(\"args\") 时面 1 竟然还是绿的")
    elif quiet(gate, root) == 0:
        fails.append("牙1：门整体在坏 Kotlin 上返回 0")
    shutil.rmtree(root, ignore_errors=True)

    # 牙 2：golden trace 被换成空 -> 面 2 必须红
    root = tempfile.mkdtemp(prefix="tst-tag-")
    gdir = os.path.join(root, "tests", "traces", "golden", "traces")
    os.makedirs(gdir)
    with open(os.path.join(gdir, "empty.jsonl"), "w") as fh:
        fh.write('{"type":"trace_start"}\n')
    if quiet(face_golden, root):
        fails.append("牙2：只有 trace_start 的 golden 竟然通过了面 2（0 次调用未被拦）")
    shutil.rmtree(root, ignore_errors=True)

    # 牙 3：把契约里一条期望值翻反 -> 面 3 必须红（证明契约真的在比对）
    keep = list(CONTRACT)
    CONTRACT = keep + [("shell_execute", '{"command":"pytest -q"}', "EXAMINE")]
    if quiet(face_contract):
        fails.append("牙3：期望值被翻反后契约仍然全对 —— 契约没在比对")
    CONTRACT = keep

    # 精度对照：契约在**正确**期望下必须绿（防"全红也算有牙"）
    if not quiet(face_contract):
        fails.append("精度对照：正确期望下契约竟然红了")

    # 牙 4 [da1029-C / I09]：把 write() 的 buildLine 挪到 writeLock 外 -> 接线面红
    # [wide3-04] 夹具补齐 beginRun（写端判据）与 write 的默认参数形态（体定位判据）。
    def out_of(fn, *a):
        """同 `quiet`，但要拿到诊断原文（新判据的牙必须断言**是哪一条**响了）。"""
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            ok = fn(*a)
        return ok, buf.getvalue()

    FIELD_LINE = {
        "live": 'put("args", x)\n',
        # [wide4-09] 恒假分支的归一化等价形态（上一轮只挡裸 `if (false)`）
        "dead_false": 'if (false) put("args", x)\n',
        "dead_not_true": 'if (!true) put("args", x)\n',
        "dead_num_eq": 'if (1 == 2) put("args", x)\n',
        "dead_when": 'when (false) { true -> put("args", x) }\n',
        # 常量值 → 常量包在纯函数里（数据源被换成空串）
        "constant": 'put("args", "")\n',
        "wrapped_constant": 'put("args", sanitize("", ARGS_MAX_LENGTH))\n',
        # parser-death：嵌套块注释里被 Kotlin 丢弃的字段
        "nested_comment": '/* outer /* inner */ put("args", x) */\n',
    }

    def _kt(lock_scope_write, begin_locked=True, default_param=False,
            field_shape="live", lock_field="stable", begin_shape="plain",
            prefix=None):
        if prefix is None:
            prefix = ('const val TYPE_TOOL_CALL = "tool_call"\n'
                      'const val TYPE_TOOL_RESULT = "tool_result"\n'
                      'const val TYPE_TURN_START = "turn_start"\n'
                      'const val TYPE_TURN_END = "turn_end"\n'
                      + ("    private val writeLock: Any get() = Any()\n"
                         if lock_field == "getter"
                         else "    private val writeLock = Any()\n")
                      + 'put("tool", x)\n'
                      + FIELD_LINE[field_shape]
                      + 'put("tool_id", x)\nput("success", x)\n')
        lock_open = '        synchronized(writeLock) {\n'
        append = '            runCatching { appendLine(line) }\n'
        close = '        }\n    }\n'
        build = '        val line = buildLine(type, block)\n'
        if default_param:
            # The lock lives inside a DEFAULT PARAMETER lambda; the real body
            # builds the line outside it. "First `{` after the name" slices the
            # default lambda and calls this locked.
            sig = ('    private fun write(\n'
                   '        type: String,\n'
                   '        block: JSONObject.() -> Unit = { synchronized(writeLock) '
                   '{ buildLine(type, {}) } },\n'
                   '    ) {\n')
            write_fn = sig + build + lock_open + append + close
        else:
            sig = '    private fun write(type: String, block: JSONObject.() -> Unit) {\n'
            write_fn = (sig + lock_open + build + append + close
                        if lock_scope_write else sig + build + lock_open + append + close)
        terminal = ('    private fun writeTerminalOnce(block: JSONObject.() -> Unit) {\n'
                    + lock_open + '            val line = buildLine(TYPE_TRACE_END, block)\n'
                    + append + close)
        begin_body = ('            activeRunId = runId\n'
                      '            activeSessionId = sessionId\n'
                      '            terminalWritten = false\n')
        if begin_shape == "dead_branch":
            begin_body = ('            if (false) {\n'
                          + "".join("    " + ln + "\n" for ln in begin_body.splitlines())
                          + '            }\n')
        elif begin_shape == "char_literal":
            # 精度对照：字符字面量 `'"'` 不再让扫描器吞掉后面整段真代码
            begin_body = "            val _q = '\"'\n" + begin_body
        inner = begin_body
        if begin_shape == "early_return":
            inner = '            if (true) return\n' + begin_body
        if begin_locked:
            begin = ('    fun beginRun(runId: String, sessionId: String) {\n'
                     + lock_open + inner + '        }\n    }\n')
        else:
            begin = ('    fun beginRun(runId: String, sessionId: String) {\n'
                     + begin_body + '    }\n')
        return prefix + write_fn + terminal + begin

    root = tempfile.mkdtemp(prefix="tst-tag-")
    kt_dir = os.path.join(root, "src", "android", "app", "src", "main", "java",
                          "com", "rikkaminis", "app", "tools")
    os.makedirs(kt_dir)
    path = os.path.join(kt_dir, "AgentTraceRecorder.kt")

    def write_kt(text):
        with open(path, "w") as fh:
            fh.write(text)

    write_kt(_kt(lock_scope_write=False))
    if quiet(face_schema, root):
        fails.append("牙4：buildLine 被挪到 writeLock 外时，接线面竟然还是绿的")
    write_kt(_kt(lock_scope_write=True))
    if not quiet(face_schema, root):
        fails.append("牙4 精度对照：构行在锁内时接线面竟然红了")

    # 牙 5 [wide3-04]：写端 —— beginRun 的三字段更新失去 writeLock -> 接线面红
    write_kt(_kt(lock_scope_write=True, begin_locked=False))
    if quiet(face_schema, root):
        fails.append("牙5：beginRun 无锁更新 run 上下文时，接线面竟然还是绿的")
    write_kt(_kt(lock_scope_write=True))
    if not quiet(face_schema, root):
        fails.append("牙5 精度对照：beginRun 在锁内时接线面竟然红了")

    # 牙 6 [wide3-04]：默认参数里的 lambda 有锁、真函数体在锁外 -> 接线面红
    write_kt(_kt(lock_scope_write=True, default_param=True))
    if quiet(face_schema, root):
        fails.append("牙6：默认参数 lambda 里的锁被当成函数体时，接线面竟然还是绿的")

    # 牙 7 / 牙 8 [wide3-04]：字段存在但写不出来 —— 恒假分支 / 常量值 -> 接线面红
    write_kt(_kt(lock_scope_write=True, field_shape="dead_false"))
    if quiet(face_schema, root):
        fails.append("牙7：put(\"args\") 只活在 if (false) 里时，接线面竟然还是绿的")
    write_kt(_kt(lock_scope_write=True, field_shape="constant"))
    if quiet(face_schema, root):
        fails.append("牙8：put(\"args\", \"\") 写入常量时，接线面竟然还是绿的")

    # ── wide4-09 新增臂：每条对应一条被外部见证击穿的轴 ────────────────────
    # 牙 9（family-hole，逻辑非）：`if (!true)` 与 `if (1 == 2)` 都是恒假
    for shape, label in (("dead_not_true", "!true"), ("dead_num_eq", "1 == 2")):
        write_kt(_kt(lock_scope_write=True, field_shape=shape))
        ok, out = out_of(face_schema, root)
        if ok or "只出现在恒不执行的 if 分支里" not in out:
            fails.append("w4牙9(%s)：恒假条件（%s）里的字段没被拦（ok=%s）" % (label, shape, ok))

    # 牙 10（family-hole，when 臂）：`when (false) { true -> … }` 永远不执行
    write_kt(_kt(lock_scope_write=True, field_shape="dead_when"))
    ok, out = out_of(face_schema, root)
    if ok or "只出现在恒不执行的 if 分支里" not in out:
        fails.append("w4牙10：`when (false) { true -> put(\"args\") }` 没被拦")

    # 牙 11（family-hole，数据源被常量顶替）：`sanitize("", ARGS_MAX_LENGTH)`
    write_kt(_kt(lock_scope_write=True, field_shape="wrapped_constant"))
    ok, out = out_of(face_schema, root)
    if ok or '字段 put("args")' not in out:
        fails.append("w4牙11：把数据源换成空串常量再包一层纯函数后，字段值判据没响")

    # 牙 11b 精度对照：值真的派生自形参时必须绿
    param_prefix = ('const val TYPE_TOOL_CALL = "tool_call"\n'
                    'const val TYPE_TOOL_RESULT = "tool_result"\n'
                    'const val TYPE_TURN_START = "turn_start"\n'
                    'const val TYPE_TURN_END = "turn_end"\n'
                    '    private val writeLock = Any()\n'
                    'fun toolCall(turn: Int, toolId: String, name: String, argsJson: String) {\n'
                    '    put("tool", name)\n'
                    '    put("args", sanitize(argsJson, ARGS_MAX_LENGTH))\n'
                    '    put("tool_id", toolId)\n'
                    '    put("success", turn > 0)\n'
                    '}\n')
    write_kt(_kt(lock_scope_write=True, prefix=param_prefix))
    ok, out = out_of(face_schema, root)
    if not ok:
        fails.append("w4牙11b 精度对照：值派生自形参时接线面竟然红了：%s" % out[-300:])

    # 牙 12（family-hole，锁的身份）：`get() = Any()` 每次分配新 monitor
    write_kt(_kt(lock_scope_write=True, lock_field="getter"))
    ok, out = out_of(face_schema, root)
    if ok or "writeLock is not a stable instance field" not in out:
        fails.append("w4牙12：writeLock 被换成 `get() = Any()`（每次新锁）时没被拦")

    # 牙 13（family-hole，锁内死分支）：beginRun 三行赋值包进 `if (false)`
    write_kt(_kt(lock_scope_write=True, begin_shape="dead_branch"))
    ok, out = out_of(face_schema, root)
    if ok or "inside a dead branch" not in out:
        fails.append("w4牙13：beginRun 的三行赋值被包进 if (false) 后没被拦")

    # 牙 14（family-hole，恒真早退）：`if (true) return` 让赋值与 trace_start 不可达
    write_kt(_kt(lock_scope_write=True, begin_shape="early_return"))
    ok, out = out_of(face_schema, root)
    if ok or "returns before" not in out and "after `return` under" not in out:
        fails.append("w4牙14：beginRun 锁内的 `if (true) return` 没被拦：%s" % out[-300:])

    # 牙 14b 精度对照：锁内出现字符字面量 `'"'` 但赋值都在锁内且可达 -> 绿
    write_kt(_kt(lock_scope_write=True, begin_shape="char_literal"))
    if not quiet(face_schema, root):
        fails.append("w4牙14b 精度对照：锁内字符字面量让接线面误红")

    # 牙 15（parser-death，嵌套块注释）：注释里的 put 不算写出
    write_kt(_kt(lock_scope_write=True, field_shape="nested_comment"))
    ok, out = out_of(face_schema, root)
    if ok or 'put("args")' not in out:
        fails.append("w4牙15：嵌套块注释里的 put(\"args\") 被当成活代码")
    shutil.rmtree(root, ignore_errors=True)

    if fails:
        for f in fails:
            print("  ❌ " + f)
        print("TRACE_STEP_TAG_SELFTEST=FAIL")
        return 1
    print("  牙1 Kotlin 缺字段 -> 接线面红 / 牙2 空 golden -> 端到端面红 / "
          "牙3 期望翻反 -> 契约面红 / 牙4 buildLine 出锁 -> 接线面红 / "
          "牙5 beginRun 无锁 -> 接线面红 / 牙6 默认参数 lambda 冒充函数体 -> 接线面红 / "
          "牙7 恒假分支里的字段 -> 接线面红 / 牙8 常量字段值 -> 接线面红 / "
          "w4牙9 !true 与 1==2 -> 接线面红 / w4牙10 when(false) 臂 -> 接线面红 / "
          "w4牙11 sanitize(\"\") 掏空数据源 -> 接线面红（牙11b 派生值精度对照绿）/ "
          "w4牙12 writeLock get()=Any() -> 接线面红 / w4牙13 beginRun 赋值进死分支 -> 接线面红 / "
          "w4牙14 锁内 if(true) return -> 接线面红（牙14b 锁内字符字面量精度对照绿）/ "
          "w4牙15 嵌套块注释里的字段 -> 接线面红；各面互不连坐，正确输入下全绿")
    print("TRACE_STEP_TAG_SELFTEST=PASS")
    return 0


def main(argv):
    if "--selftest" in argv:
        return selftest()
    if "--trace" in argv:
        i = argv.index("--trace")
        if i + 1 >= len(argv):
            print("--trace 需要一个 JSONL 路径")
            return 2
        return report(argv[i + 1])
    root = None
    for a in argv[1:]:
        if not a.startswith("-"):
            root = a
            break
    if root is None:
        root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    if not os.path.isdir(root):
        print("repo root 不存在：%s" % root)
        return 2
    return gate(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
