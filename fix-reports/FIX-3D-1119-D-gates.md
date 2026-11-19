# FIX-3D-1119-D — 门与夹具自身 7 条（`test_scan` 解析、三条门的判据、掩码器）

分支 `fix/gate-truth-1119c` · 基线 `ecb03d92` · worktree `/var/minis/shared/work/r3d1119/gates`
覆盖：**6 F-01 / F-02 / F-03 / F-04 / F-05 / F-06、2 F-02**

## 1 范围

只碰任务书允许的五个文件（其中四个改动 + 本报告），**零 `scan.sh` / `scan_parallel.py` / 生产 Kotlin 改动**：

| 文件 | 改动 |
|---|---|
| `scripts/scan/test_scan.py` | F-01 解析 fail-closed、F-02 三条子进程臂、F-03 三条脏夹具 |
| `scripts/scan/subagent_dispatch_check.py` | F-04 条件终止判据、F-05 边界语义 + 无花括号单语句 |
| `scripts/scan/skill_frontmatter_roundtrip_check.py` | F-06 终止 = 控制流事实（吞异常 / 条件返回 / 条件块） |
| `.../ui/chat/ComposerClearResidueWiringTest.kt` | 2 F-02 掩码器三类词法 + 自测 |
| `fix-reports/FIX-3D-1119-D-gates.md` | 本文件 |

改动量：4 文件 **+829 / −20**（本报告不计）。

**这改变什么用户行为？** 两处，都是开发者面：
1. 本地被全量门拒绝后，照提示敲 `--single=NAME` 或把用例名当位置参数 —— 以前会**静默跑起整套夹具**（PRoot 下正是那道闸要阻止的事，而且是闸自己的提示词引导的），现在四种错形态全部**在跑任何夹具之前**非零退出并打印用法。
2. 三条门的判据以前各有可证伪的失明点（恒真条件判绿、条件 `return` 当无条件早退、`runCatching { throw }` 当终止），现在都能被抓到；wiring test 的掩码器以前对三类 Kotlin 词法失明（视图错 = 锚点错 = 整套断言在测空气），现在正确。

## 2 逐条先证（修之前实测，不是转述）

| ID | 实测（修前） | 性质 |
|---|---|---|
| **6 F-01** | `_parse_argv(["--single=test_four_way"])` → `single=None` + `roots=["--single=test_four_way"]`；`["--single"]` 同；`["test_four_way"]` 同 | 静默全量 |
| **6 F-02** | 该测试只对 `scan_parallel.py` 文本做字面量断言，从不启动 `--single`，也不验退出码 3 | 文本锁，非行为锁 |
| **6 F-03** | 三组外部夹具各只脏一个维度：`subagent` 只脏 `PIN_OBJECT`（不脏 `TARGET`）、`skill_frontmatter` 只脏 `SB_REL`（不脏 `SR_REL`）、`truncated` 只脏 `POLICY`（不脏 `ENGINE`） | 掏空一半 `main()` 仍绿 |
| **6 F-04** | `_block_has_top_level_terminal(" if (false) return@withLock true ")` → **True** | 条件终止当早退 |
| **6 F-05** | `if (depth >= 1) { pin(…) }` → 判绿（漏报）；单行 `if (…) pin(…)` → 判红（误报） | 两个方向都错 |
| **6 F-06** | `SB_TERMINAL_RE.search("runCatching { throw … }")` 命中 ⇒ 判绿 | 词 ≠ 控制流 |
| **2 F-02** | 掩码器探针（`r3d1119/probeMask.kt`）：嵌套块注释留下 `CODE_IN_COMMENT */`；`val c = '"'` 后整行注释**未被屏蔽**；`"""a""""` 后注释**未被屏蔽** | 视图错 ⇒ 整套断言测错文本 |

复现探针留在 `/var/minis/shared/work/r3d1119/probeMask.kt`（只编纯函数，无 Android 依赖）。

## 3 修法

### F-01 `_parse_argv` / 位置参数 fail-closed

```python
if a.startswith("--single="):          # 新增：等号形式
    single = a.split("=", 1)[1]
    ...
if a == "--single":                    # 新增：孤立 flag 直接拒绝
    sys.exit("test_scan.py: --single needs a test name, got a flag with no value (usage: %s)" % USAGE)
if a.startswith("-"):                  # 新增：未知 flag 不是路径
    sys.exit("test_scan.py: unknown flag %r (usage: %s)" % (a, USAGE))
...
if single is not None and (single == "" or single.startswith("-")):
    sys.exit(...)                      # `--single=` / `--single=--jobs` 也拒
```

模块级再补一条：位置参数不是目录即拒绝（`REPO_ROOT` 以前接受任意字符串，`test_scan.py <case_name>` 会把用例名当仓库根跑全量）。

**实测四形态**（修复后）：

| argv | rc | 输出 |
|---|---|---|
| `--single` | 1 | `--single needs a test name, got a flag with no value` |
| `--single=` | 1 | `--single needs a test name, got ''` |
| `test_primitive_selftests` | 1 | `positional argument … is not a directory — the first positional argument is the REPO ROOT` |
| `--bogus-flag` | 1 | `unknown flag '--bogus-flag'` |
| `--single=test_four_way` | 0 | 只跑该用例 |
| `--single test_four_way` | 0 | 只跑该用例 |

### F-02 三条子进程臂（注册在 `test_scan_parallel_local_refusal`）

1. `_arm_single_runs_exactly_one_case()`：两种形态各跑一次真实子进程，断言 stdout 含**该用例**的 `@@TEST_RESULT@@ <name>` 且**不含任何其它用例**的；带 120s 超时（坏实现不会把沙箱拖死），env 显式清空 `CI` / `GITHUB_ACTIONS` / `GITHUB_WORKFLOW`。
2. `_arm_bad_arguments_fail_closed()`：四种错形态各跑一次，断言 **rc ≠ 0 且输出里没有 `━━━` 或 `@@TEST_RESULT@@`** —— 即「在跑任何夹具之前」就拒绝。只断言 rc 不够：一个先跑完再报错的实现同样 rc≠0。
3. `_arm_driver_refuses_with_exit_3()`：无 CI 变量跑 `scan_parallel.py`，断言 **rc == 3** 且输出含 `refusing to run`。退出码 3 此前只写在模块 docstring 里，没有任何断言。

### F-03 每条门补一个「换掉另一个决定」的脏维度

| 门 | 新增脏夹具 | 证明的调用仍在 `main()` 里 |
|---|---|---|
| `subagent_dispatch_check` | `DIRTY["NO_CALL"]`（脏 `TARGET`，干净 pin object） | `check_source(...)` |
| `skill_frontmatter_roundtrip_check` | `DIRTY_SR_POLARITY`（脏 `SR_REL`，干净 SB） | `SkillRepository.kt` 的扫描 |
| `truncated_call_guard_check` | `C2_ENGINE_CALL` → `…, false)`（脏 `ENGINE`，干净 policy） | engine 调用点的 C2 判据 |

两条新夹具各带 `mutated != original` 断言（锚点漂移会红，不会静默等值）。既有断言一条未削弱，只加臂。

### F-04 终止必须是「无条件」

新增 `_statement_is_conditional(body, idx)`：从终止处向前走到语句起点（跳过配对的条件括号），若语句以 `if (` / `when (` / `while (` / `for (` 开头即条件性。`_block_has_top_level_terminal` 改为 `depth == 0 and not _statement_is_conditional(...)`。

带花括号的 `if (c) { … return … }` 在回走时撞到自己的 `{` 就停，所以块内终止仍判无条件 —— 不误伤。

### F-05 边界语义 + 无花括号单语句

新增 `_var_lower_bound`（从 `val depth = … ?: 1` 读下界）与 `_condition_guards_the_boundary`：

* **接受**同一决定的所有拼写：`depth == 1`、`depth <= 1`、`depth < 2`、`1 == depth`、`1 >= depth`、`2 > depth`，以及否定形式 `left != 0`。
* **拒绝**在计数器自身值域上恒真的比较：`depth >= 1`、`depth > 0`（下界为 1）、`left >= 0`（下界为 0）。
* 条件里没有对变量的数值比较（布尔别名 `val isFirst = depth == 1`）**仍然接受** —— 门不声称能看穿别名，拒绝它只是拿一个盲点换一个误红。

上一轮 KDoc「刻意不锚比较运算符」的理由**保留并更新**：不锚**拼写**，锚**语义**（能不能为假）。

无花括号单语句补一条分支：`if (c) pin(…)` 时目标落在该语句内即受控（以前只接受 `return` / `throw` 在此位置，把合法写法判成 `pin-open-every-window`）。

### F-06 终止 = 控制流事实

`SB_TERMINAL_RE` 只用来**定位候选**，判定交给块结构：

* `_swallowing_spans`：`runCatching { … }` 与 `try { … }` 的 **try 块**内部（`catch` / `finally` 块不算 —— 那里的 `throw` 真会逃逸）。
* `_statement_is_conditional`：无花括号 `if (c) return` 不是终止。
* `_enclosing_if_header` + `_if_has_terminating_else`：`if (c) { return }` 且**没有 else** 不是终止；`if/else` 两臂都终止才算（`else if` 递归）。
* 递归深度上限 8，超限 fail-closed（判「非终止」）—— 门断言的是「这里会退出」，保守方向正确。

### 2 F-02 掩码器三类词法

| 形状 | 旧行为 | 新行为 |
|---|---|---|
| `/* a /* b */ c */` | 停在第一个终止符，`c */` 留在视图里当代码 | `blockCommentEnd` 计深度，配对到最外层 |
| `val c = '"'` | 当成字符串起始，吞掉后面的真注释 | `skipCharLiteral`：单行、认转义；无配对则退回普通字符 |
| `"""a""""` | 取前三个引号，留下一个裸 `"` 开假字符串 | `skipRawString`：连续引号 run ≥ 3 时整段消费 |

新增测试 `the comment masker survives nested comments, char literals and quote runs`：三形状各断言**长度不变 + 换行数不变 + 注释文本消失 + 注释后的真代码仍在 + `val after` 偏移不变**。用换行数而不是只查子串，是因为丢换行会让本类每个锚点静默偏移。

## 4 证据

### 本地窄验证（全部单门 / `--single`，无全量）

| 项 | 结果 |
|---|---|
| `subagent_dispatch_check.py --selftest` | `SELFTEST OK (25 arms)`（原 22，+3 脏臂；另 2 条合法形态见证） |
| `subagent_dispatch_check.py .`（真实树） | rc=0 |
| `skill_frontmatter_roundtrip_check.py --selftest` | `SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS`（干净形状 3 → 5，脏臂 24 → 28） |
| `skill_frontmatter_roundtrip_check.py .` | rc=0 |
| `truncated_call_guard_check.py --selftest` / `.` | PASS / rc=0（本支未改它） |
| `test_scan.py --single test_gate_body_external_fixtures` | **11 passed / 0 failed**（原 7） |
| `test_scan.py --single test_scan_parallel_local_refusal` | **22 passed / 0 failed**（原 15） |
| `test_scan.py --single test_da1017_gates` / `test_da1018_gates` / `test_da1023_gates` | 14 / 14 / 22 passed，0 failed |
| `test_scan.py --single test_real_repo` | 20 passed / 0 failed |
| `ComposerClearResidueWiringTest`（JVM，kotlinc + JUnit4） | **OK (14 tests)**（原 13） |

**未跑全量**：本支全程只用单门与 `--single`；全量证据取自 CI `scan-gate`。

### 变异负控（14 臂，`ALL PRECISE`）

装置 `/var/minis/shared/work/r3d1119/gates-mutate/mutate.py`（在 worktree **之外**，不会被 `git add -A` 扫走）。每臂先快照全部四文件字节，`finally` 还原并比对 sha256。

| 臂 | 变异 | 期望翻红的臂 | 结果 |
|---|---|---|---|
| M1 | 删掉 `--single=` 分支 | `--single`（equals form） | RED-PRECISE |
| M2 | 位置参数不做目录校验 | `positional slot` | RED-PRECISE |
| M3 | 删掉 `--single` 子进程臂 + 等号分支 | 该臂消失 ⇒ 测试反而绿 | RED-PRECISE（判据=臂消失） |
| M4 | `_block_has_top_level_terminal` 去掉条件判断 | `PIN_OBJ_CONDITIONAL_RETURN_UNPIN` 变绿 | RED-PRECISE |
| M5 | `_condition_guards_the_boundary` 不生效 | 两条 TAUTOLOGY 臂变绿 | RED-PRECISE（精确 2 条） |
| M6 | 无花括号单语句分支失效 | `single-statement` 合法形态变红 | RED-PRECISE（精确 1 条） |
| M7 | 去掉吞异常 span 排除 | `runCatching` / `try` 两条变绿 | RED-PRECISE（精确 2 条） |
| M8 | 去掉条件返回排除 | `brace-less if` 变绿 | RED-PRECISE（精确 1 条） |
| M9 | `main()` 删掉 `check_source(...)` | `dispatch-call tree red` | RED-PRECISE（精确 1 条） |
| M10 | `main()` 改跑内存夹具（不读真实 SR） | `polarity tree red` | RED-PRECISE（精确 1 条） |
| M11 | `blockCommentEnd` 退回第一个终止符 | 嵌套注释那条 | RED-PRECISE |
| M12 | 字符字面量分支失效 | 字符字面量那条 | RED-PRECISE |
| M13 | `skipRawString` 取前三个引号 | 四引号那条 | RED-PRECISE |
| M14 | 条件块判定失效 | `brace-ful if` 变绿 | RED-PRECISE（精确 1 条） |

`restore: OK (sha256 identical)` / `PRECISION: ALL PRECISE (14/14 arms)`。

**每个「精确 N 条」都是逐条核过的**：M5/M7 的 2 红是同一判据的两个方向（open / close、runCatching / try），不是粗网；M3 的判据是「臂被删 ⇒ 坏实现仍绿」，所以它在变异下必须绿 —— 这条臂本身就是「文本锁 ≠ 行为锁」的见证。

## 5 CI

**run #3316 / `44443686299` @ `65c4c42dc500da3ffb2c78c25f8fe305f3f11364`（代码态 + 本报告）**

| job | 结论 |
|---|---|
| `scan-gate` | **success** |
| `unit-tests` | **success** |
| `lint` | success（第 2 次尝试，见下） |
| `build-apk` | success |
| `publish` | success（`Move the rolling tag` / `Publish to Releases` 两步 **skipped**，main 门控符合预期） |

`head_sha` 与本地 `git rev-parse HEAD`、远端 `git ls-remote origin refs/heads/fix/gate-truth-1119c` **逐字符一致**（`65c4c42d…`）。

### 夹具数：376 → **387**（+11）

```
✅ ALL 387 CASES PASS
Summary: 115 passed, 0 failed          ← 门数不变（本支未增删门）
```

`scripts/scan/**` 本支有改动，所以 `build-apk` 的夹具自检**没有被跳过**：日志里是
`##[notice]scanners moved in this push (or the diff was unreadable) — running the fixture self-test`
（不是 `untouched in this push — skipping`），即 387 是真跑出来的。

**+11 的构成**（本地逐条核对，与 CI 日志一致）：

| 用例 | 前 | 后 |
|---|---:|---:|
| `test_gate_body_external_fixtures` | 7 | 11 |
| `test_scan_parallel_local_refusal` | 15 | 22 |

新增断言在 CI 日志里逐条命中（各 1 次）：`subagent_dispatch_check external dispatch-call tree red`、`skill_frontmatter_roundtrip_check external polarity tree red`、`truncated_call_guard_check external engine-flag tree red`、`` `--single` (space form) runs exactly the named fixture ``、`` `--single` (equals form) … ``、四条 `… is refused before any fixture runs`、`the driver refuses outside CI with exit 3`。

### unit-tests

**9825 PASSED 行 / 0 真 FAILED**（用 `^\S*Z\s+\S+ > .* FAILED$` 逐行匹配，零命中；含 `FAILED` 字样的行全是 PASSED 的用例名，如 `reason PERSISTENCE_FAILED maps to persistence_failed PASSED`）。新用例 `ComposerClearResidueWiringTest > the comment masker survives nested comments, char literals and quote runs PASSED` 在列。

### 第一次跑红过一次：**不是本支的问题**

首跑 `lint` job 在 `:app:generateDebugAndroidTestLintModel` 失败：

```
Could not find hamcrest-integration-1.3.jar (org.hamcrest:hamcrest-integration:1.3)
Could not find hamcrest-library-1.3.jar (org.hamcrest:hamcrest-library:1.3)
Could not find javawriter-2.1.1.jar (com.squareup:javawriter:2.1.1)
Searched in the following locations:
    https://repo.maven.apache.org/maven2/…
```

判据：本支**零 gradle / 零依赖声明改动**（`git diff ecb03d92 --stat` 只有四个文件，全在 `scripts/scan/**` 与一个测试类）；三个坐标当时在 Maven Central 直接 404，事后用 `curl -o /dev/null -w '%{http_code}'` 复核同一 URL **均 200**。结论是上游仓库瞬时不可用，与本支无关。`rerun-failed-jobs` 重跑后五个 job 全绿。

## 6 未修与取舍

| 项 | 判级 | 处理 |
|---|---|---|
| 6 F-07 `local_run_refused` 把 `CI="false"` 当真值 | P2 | **任务书明确排除**（已登记 backlog G8），未动 `scan_parallel.py` |
| `test_scan.py` 的 `--jobs` 等号形式未覆盖子进程臂 | P3 | 现有字面量断言已覆盖解析；行为影响是「并行度不对」而非「跑错集合」，未加臂 |
| `_condition_guards_the_boundary` 看不穿布尔别名 | 已知边界 | 文档化在 KDoc：拒绝它会把一个盲点换成一个误红 |
| `_enclosing_if_header` 只看 160 字符窗口内的 header | 已知边界 | 窗口不够时判「非条件块」= 保守方向（可能误红），不是假绿 |
| `runCatching` 的 **receiver 形式**（`x.runCatching { … }`）同样被排除 | 设计 | 该形式同样吞异常，排除是正确的 |

**一个需要下游知道的行为变化**：`test_scan.py` 现在**拒绝未知 flag**。仓库内没有其它调用点（已 grep `scripts/` / `docs/` / `.github/`），CI 三处调用都是 `--jobs 4`，不受影响；但外部脚本若传过自定义 flag 会立刻失败 —— 这正是 fail-closed 想要的。

## 7 文件清单与提交

```
scripts/scan/test_scan.py                                    +185 −2
scripts/scan/subagent_dispatch_check.py                      +211 −7
scripts/scan/skill_frontmatter_roundtrip_check.py            +273 −11
src/.../ui/chat/ComposerClearResidueWiringTest.kt            +180 −10
fix-reports/FIX-3D-1119-D-gates.md                           （本文件）
```

提交序列：
- `8685ddc1` 六条本体（F-01/02/03/04/05/06 + 2 F-02）
- `f5a05071` 6 F-06 补「带花括号条件块无 else」形状

## 8 复现

```sh
cd /var/minis/shared/work/r3d1119/gates

# 三门自测 + 真实树（单进程，nice，不跑全量）
python3 scripts/scan/subagent_dispatch_check.py --selftest
python3 scripts/scan/skill_frontmatter_roundtrip_check.py --selftest
python3 scripts/scan/truncated_call_guard_check.py --selftest
for g in subagent_dispatch_check skill_frontmatter_roundtrip_check truncated_call_guard_check; do
  python3 scripts/scan/$g.py .
done

# 三条窄夹具
python3 scripts/scan/test_scan.py --single test_gate_body_external_fixtures
python3 scripts/scan/test_scan.py --single test_scan_parallel_local_refusal
python3 scripts/scan/test_scan.py --single test_real_repo

# 四种错形态必须 fail-closed
for a in "--single" "--single=" "test_primitive_selftests" "--bogus"; do
  python3 scripts/scan/test_scan.py "$a"; echo "rc=$?"
done

# wiring test（JVM）
kotlinc -no-stdlib -cp $LIBS/kotlin-stdlib-2.1.0.jar:$LIBS/junit-4.13.2.jar \
  src/android/app/src/test/java/com/rikkaminis/app/ui/chat/ComposerClearResidueWiringTest.kt -d /tmp/wt
java -cp /tmp/wt:$LIBS/kotlin-stdlib-2.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar \
  org.junit.runner.JUnitCore com.rikkaminis.app.ui.chat.ComposerClearResidueWiringTest

# 变异负控（14 臂，工作树之外）
python3 /var/minis/shared/work/r3d1119/gates-mutate/mutate.py
```
