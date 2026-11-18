# FIX-2D-1119-8 — 门自身：拒绝对话里的命令错误 + 三道新门判据失效

分支 `fix/gate-truth-1119` · 基线 `b82b2963` · worktree `/var/minis/shared/work/r1119/gates`

## 1 范围

只碰任务书允许的五个文件 + 本报告，**零 `src/android` 改动、零 `strings.xml` 改动**：

| 文件 | 改动 |
|---|---|
| `scripts/scan/scan_parallel.py` | `LOCAL_REFUSAL` 两条替代命令 + 同族注释块 + docstring 退出码 3 |
| `scripts/scan/test_scan.py` | F-01 四条字面量断言、F-04 新夹具测试 `test_gate_body_external_fixtures`（注册进 `TEST_ORDER`） |
| `scripts/scan/skill_frontmatter_roundtrip_check.py` | F-02 续接符按长度拆集 + 新干净形状夹具 |
| `scripts/scan/subagent_dispatch_check.py` | F-03 `_guard_mentions` → `_guard_controls` + 两条空守卫夹具 |
| `scripts/scan/truncated_call_guard_check.py` | 无代码改动（其判据本身成立，只补了外部夹具覆盖） |

改动量：4 文件 **+298 / −20**。

**这改变什么用户行为？** 开发者本地被全量门拒绝后，照提示敲的两条命令以前**一条会误跑全量 52 组、一条直接崩**；现在两条都实跑通过。另：三道新门被掏空成 `return 0` 时，`test_scan.py` 以前全绿，现在必红。

## 2 逐条先证

### F-01 · 成立（两条命令都错，均已实测）

| 命令 | 实测结果 |
|---|---|
| `python3 scripts/scan/test_scan.py <case_name>` | `_parse_argv(['test_da1017_external_fixtures'])` → `(1, None, ['test_da1017_external_fixtures'])`，即 `SINGLE=None`、该名字被当成 **repo root**，`TEST_ORDER` 全量 52 组照跑 |
| `python3 scripts/scan/skill_frontmatter_roundtrip_check.py --root .` | `FileNotFoundError: '--root/src/android/.../SkillRepository.kt'`，rc=1（该门读 `sys.argv[1]` 当仓库根） |

**同族复发（本次新发现）**：`scan_parallel.py:74` 的注释块（`LOCAL_REFUSAL` 的出处）写着**同样两条错命令**，只修文案会留下下一次照抄的源头。

### F-02 · 成立（实测复现）

探针 `/tmp/probe_f02.py`（修复前）：

```
body_end  = 114
body      = 'reportError(result.reason)'      ← 应在 '.also { return }' 之后
terminal  = False                             ← 终止分支被读成 drop-through
```

根因确认：`BRANCH_STARTS_WITH_CONT` 混含单字符（`.` `+` `-` `*` `/` `,`）与双字符（`?:` `&&` `||` `->`），而判定写的是 `text[j:j + 2] in …` —— 2 字符切片永不等于 1 字符成员。**后果是假红**（正确代码被判违规），不是假绿。

### F-03 · 成立（实测复现）

探针 `/tmp/probe_f03.py`（修复前）：

```
bypass _guard_mentions = True   ← 空块 if 满足判定
bypass check_pin_object = [... pin-window-missing ...]   ← 换个形状才红，原形状全绿
```

`if (depth == 1) { }` 后无条件 `pin(sessionId)` —— 旧判定只问「前面某个 `if (…)` 的条件文本里出现过 `depth` 吗」，是拼写检查不是守卫检查。**后果是假绿**：每次嵌套窗口都重复 pin。

### F-04 · 成立（实测复现）

三道门的判据都在 `main()` 的扫描路径里，而 `test_scan.py` 对它们只断言 `--selftest` 标记 + 真实树 `rc == 0`。把 `main()` 掏成 `print("OK"); return 0`（保留 `--selftest` 分支）后：`test_scan.py` 全绿、门彻底失效。本次新增的 `test_gate_body_external_fixtures` 用子进程驱动临时树，掏空即红（见 §5 变异 M5/M6/M7）。

## 3 修法

**F-01**：文案改为

```
  python3 scripts/scan/test_scan.py --single <case_name>   # one fixture
  python3 scripts/scan/<gate>.py                            # one gate (its own flags)
```

**两条命令已实跑验证**：

```
$ python3 scripts/scan/test_scan.py --single test_scan_parallel_local_refusal
...
@@TEST_RESULT@@ test_scan_parallel_local_refusal 15 0        rc=0

$ python3 scripts/scan/subagent_dispatch_check.py
OK: sub-agent dispatch wiring holds (5 call-site contracts + 8 pin-window decisions)   rc=0
$ python3 scripts/scan/skill_frontmatter_roundtrip_check.py
OK .../SkillRepository.kt + .../SkillsManagementScreen.kt + .../MinisSkillsBrowserScreen.kt (23 arms)   rc=0
$ python3 scripts/scan/truncated_call_guard_check.py
✅ truncated-call gate: …   rc=0
```

同族：注释块同步改对，并加两条**全文件**断言（`test_scan.py`），两种错形态在 `scan_parallel.py` 任意位置出现即红。

**F-02**：按长度拆成 `BRANCH_STARTS_WITH_CONT_SINGLE` / `_DOUBLE`，判定改

```python
cont_next = (text[j] in BRANCH_STARTS_WITH_CONT_SINGLE
             or text[j:j + 2] in BRANCH_STARTS_WITH_CONT_DOUBLE)
```

并**删掉**合并元组 —— 那个混合集合本身就是这次错误的诱因，留着就是给下一个人挖坑。新增干净形状夹具 `GOOD_SB_CHAINED_CONT`（单字符 `.` 续接的链式终止分支），注册进 selftest 的「合法形状必须绿」组。

**F-03**：`_guard_mentions` → `_guard_controls`，语义从「前面提过这个变量」升级为「目标调用落在该 `if` 的受控区内」：

| 形状 | 判定 |
|---|---|
| `if (c) { … 目标 … }` | 目标在块体内 → True |
| `if (c) { … return/throw }` + 块后目标 | 块体有顶层终止 → True |
| `if (c) return` + 语句后目标 | 单语句早退 → True |
| `if (c) { }` + 块后目标 | 空块 → **False**（本次修的绕过） |
| 目标在 `if` 之前 | False |

比较算子仍不锚定（`!= 0` / `> 0` / `== 0` 都是同一决定）。新增两条夹具 `PIN_OBJ_EMPTY_GUARD_PIN` / `PIN_OBJ_EMPTY_GUARD_UNPIN`。

**F-04**：新增 `test_gate_body_external_fixtures`，注册进 `TEST_ORDER`（`test_da1017_external_fixtures` 之后）。三组「干净树绿 + 脏树红」子进程夹具：

| 门 | 干净树 | 脏树 |
|---|---|---|
| `subagent_dispatch_check` | `GOOD` + `GOOD_PIN` → rc=0 | `PIN_OBJ_EMPTY_GUARD_PIN` → rc=1 |
| `skill_frontmatter_roundtrip_check` | `GOOD_SR/SM/SB` → rc=0 | `DIRTY_SB_FAILURE_FALLTHROUGH` → rc=1 |
| `truncated_call_guard_check` | 真实树八文件 → rc=0 | `C1_CONDITION` → `if (false) return null` → rc=1 |

第三个门的夹具从真实树复制（它判八个真实文件的跨文件判据），脏化前有 `mutated != original` 断言，锚点漂移会红而不是静默等值。

## 4 证据

**本地单夹具**（`--single`，逐条 PASSED）：

| 测试 | 结果 |
|---|---|
| `test_scan_parallel_local_refusal` | 15 passed / 0 failed |
| `test_gate_body_external_fixtures` | 7 passed / 0 failed |
| `test_da1017_external_fixtures` | 2 passed / 0 failed |
| `test_da1017_gates` | 14 passed / 0 failed |
| `test_da1018_gates` | 14 passed / 0 failed |
| `test_da1023_gates` | 22 passed / 0 failed |
| `test_scan_parallel_block_numbering` | 3 passed / 0 failed |

**门自身 selftest**：`subagent_dispatch_check` → `SELFTEST OK (22 arms)`（原 20，新增 2）；`skill_frontmatter_roundtrip_check` → `SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS`（干净形状 2 → 3）；`truncated_call_guard_check` → 无代码改动，真实树 rc=0。

**未跑全量**：本地 `scan.sh` 拒绝（rc=3）是预期行为，本支**未**跑全量、未加 `--allow-local`；全量证据取自 CI `scan-gate` job。

**CI run #3291 / `44370448122` @ `e918ccb5e51e1e8cea67ad0539c5872b6b0a6d47`**：五 job 全 `success`（lint / build-apk / unit-tests / scan-gate / publish），`head_sha` 与本地 `git rev-parse HEAD`、远端 `git ls-remote` **逐字符一致**。publish 的 `Move the rolling tag` / `Publish to Releases` 两步 `skipped`（main 门控，符合预期）。

**CI 取证**（代码态 `e918ccb5`，本报告描述的全部改动）：

| run | sha | 内容 | 五 job |
|---|---|---|---|
| #3291 / `44370448122` | `e918ccb5` | **代码态** | 全 success |
| #3296 / `44373775290` | `e293e644` | 代码态 + 报告初版 | 全 success |
| #3298 / `44376188937` | `f2569eda` | 代码态 + 报告补记 | 全 success |
| #3301 / `44379358831` | `8e1cadca` | 代码态 + 报告 run 表 | 全 success |

每个 run 的 `head_sha` 与各自当时的本地 `git rev-parse HEAD` / 远端 `git ls-remote` **逐字符一致**。`git diff e918ccb5..HEAD` 恒为 `fix-reports/FIX-2D-1119-8-gates.md` 一个文件 —— **报告之外差异为空**，所以 #3291 之后每个 run 跑的都是同一份代码，判据不变。本报告此后若只做文字修订，`git diff e918ccb5..HEAD` 仍是这一个文件，代码态判据（#3291）继续有效。

`#3301` 的实测取证：`scan-gate` `✅ ALL 376 CASES PASS` / `Summary: 115 passed, 0 failed`，六条新断言（`empty-guard pin tree red` / `fall-through tree red` / `blind-verdict tree red` / `carries --single` / `no wrong fixture command` / `no blanket --root .`）各 1 次命中；`unit-tests` 9750 PASSED / 0 真 FAILED；`lint` / `build-apk` success；publish 的 tag / release 两步 `skipped`。

- **scan-gate**（本支的判据就在这里）：`✅ ALL 376 CASES PASS`，摘要 `Summary: 115 passed, 0 failed`。新增断言在 CI 日志里逐条命中：`subagent_dispatch_check external empty-guard pin tree red` / `skill_frontmatter_roundtrip_check external fall-through tree red` / `truncated_call_guard_check external blind-verdict tree red` / `the refusal's fixture command carries --single` / `no wrong fixture command survives anywhere in the driver` / `no blanket --root . command survives anywhere in the driver`，各 1 次；`test_gate_body_external_fixtures` 与 `test_scan_parallel_local_refusal` 在并行夹具列表中各出现 1 次（`fixtures: 48/53`、`50/53`）。
- **unit-tests**：**9750 PASSED 行 / 0 真 FAILED**（6 条含 `FAILED` 字样的行全是 PASSED 的用例名，如 `slot denied produces FAILED without calling provider PASSED`）。
- **lint / build-apk**：success。

## 5 变异负控

装置 `/var/minis/shared/work/r1119/gates-mutate/{mutate.py,precision.py}`，在工作树**之外**（不会被 `git add -A` 扫走）。快照 + `finally` 还原 + sha256 前后比对，7 臂：

| 臂 | 变异 | 结果 | 精度 |
|---|---|---|---|
| M1 | `LOCAL_REFUSAL` 夹具命令去掉 `--single` | RED | 精确 2 红（该行 + 全文件网） |
| M2 | `LOCAL_REFUSAL` 门命令加回 `--root .` | RED | 精确 2 红（同上） |
| M3 | 续接判定退回 2 字符切片 | RED | selftest 干净形状 WRONG-RED（1 条） |
| M4 | 守卫判定退化成 mention 扫描 | RED | `PIN_OBJ_EMPTY_GUARD_PIN fixture is green` |
| M5 | `subagent_dispatch_check.main()` 掏空 | RED | 精确 1 红（empty-guard 脏树） |
| M6 | `skill_frontmatter_roundtrip_check.main()` 掏空 | RED | 精确 1 红（fall-through 脏树） |
| M7 | `truncated_call_guard_check.main()` 掏空 | RED | 精确 1 红（blind-verdict 脏树） |

`RESTORE: OK (sha256 identical)` / `BASELINE-AFTER-RESTORE: OK` / `PRECISION: ALL PRECISE`。

**M1/M2 的 2 红是文档化的预期重叠**：整文件断言是 F-01 同族网，它必然与行级断言同时翻红；不是粗网。

**M3 第一次跑时 BLIND**（记一笔）：变异臂当时写的是 `in BRANCH_STARTS_WITH_CONT`，而我在修复里已删掉这个合并常量 → 变异把代码改成一个 `NameError`，门崩在别处。修法不是放宽断言，是把变异臂改成引用两个拆开的集合（真·旧行为）。**变异臂本身也会被重构打破 —— 它引用的是实现符号。**

## 6 未修与取舍

| 项 | 判级 | 处理 |
|---|---|---|
| F-05 入口防吞断言仍不覆盖 `if __name__` / CLI 路径 | P2 | 未修，登记 |
| F-06 `truncated_call_guard` 残留过时描述 | P2 | 未修，登记 |
| F-07 `flag_shadowed` 不捕获普通赋值 | P2 | 未修，登记 |
| F-08 `CI="false"` 被当真值 | P2 | 只补 docstring 退出码 3 说明（任务书允许），判定逻辑未动 |

**F-08 的残留风险（写清）**：`local_run_refused` 判 `environ.get("CI")` 真值性，`CI="false"` 会被当 CI ⇒ 本地误放行全量。这条是 P2 但**风险方向是「闸失效」而非「误拦」**，建议下一轮升级为读 `GITHUB_ACTIONS == "true"` 或显式白名单；本轮按判级不动。

**另**：`test_gate_body_external_fixtures` 的第三组夹具从真实树读八个文件，因此该测试需要真实仓库根（`--single` 默认给的就是它）；传入临时目录会得到一条具名失败而不是 traceback。

## 7 文件清单

```
scripts/scan/scan_parallel.py                     +13 −2
scripts/scan/skill_frontmatter_roundtrip_check.py +33 −5
scripts/scan/subagent_dispatch_check.py           +132 −7
scripts/scan/test_scan.py                         +126 −0
fix-reports/FIX-2D-1119-8-gates.md                （本文件）
```

提交序列：`4744719d`（四缺陷本体）· `92649912`（F-01 同族：注释块 + 全文件网）· `e918ccb5`（删合并常量 + 夹具缺源文件守卫）。

## 8 复现

```sh
# 本地（都不跑全量）
python3 scripts/scan/test_scan.py --single test_scan_parallel_local_refusal
python3 scripts/scan/test_scan.py --single test_gate_body_external_fixtures
python3 scripts/scan/subagent_dispatch_check.py --selftest
python3 scripts/scan/skill_frontmatter_roundtrip_check.py --selftest
python3 scripts/scan/subagent_dispatch_check.py
python3 scripts/scan/skill_frontmatter_roundtrip_check.py
python3 scripts/scan/truncated_call_guard_check.py

# 变异负控（工作树之外）
python3 /var/minis/shared/work/r1119/gates-mutate/mutate.py
python3 /var/minis/shared/work/r1119/gates-mutate/precision.py
```
