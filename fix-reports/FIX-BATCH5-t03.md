# FIX-BATCH5-t03 — `enum_parse_safety_check.py` + `trace_eval_check.py` 门见证收口

支线：`fix/b5-enum-trace`（工作树 `wt/t03`）· 基线 `base/batch5-shared` @ `e145ec961`
（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow.py`）· 生产 `.kt` 改动 **0 行** ·
门数 112 / `test_scan` 夹具 344 **不变**（未新建门，未碰 `scan.sh` / `test_scan.py` / 共享模块）。

## ① 门与轴

| 门 | 见证 | 修前 | 修后 | 盲轴 |
|---|---|---|---|---|
| `scripts/scan/enum_parse_safety_check.py` | 6（5 盲 + 1 对照） | 5 BLIND / 1 CAUGHT | **6 CAUGHT** | dataflow ×2、spelling、parser-death、family-hole |
| `scripts/scan/trace_eval_check.py` | 6（5 盲 + 1 对照） | 5 BLIND / 1 CAUGHT | **6 CAUGHT** | family-hole ×3、dataflow、completeness |

两条门同一族病因：**判据锚在「某个词存在」而不是「那个语义达成了」**——
enum 门锚 `catch` 关键字与 `.valueOf(` 的**第一跳**后缀，trace 门锚 `tool_id` 字段的
**非空**与 `all_tools_succeed` 的**真值性**。见证分别从「语义被细化」（重抛、异常类型、
截断轮、三值 null）与「拼写/宿主语法」（`enumValueOf`、`${…}` 模板）两个方向击穿。

## ② 见证原文（12 条）

> 补丁的 `find` / `replace` 逐字取自 `witnesses-A.json`（组 A 报告）；下列对超长 JSONL
> 片段做**截断标注**，未标注者为逐字原文。`为什么门绿` = 修前判据的具体破口。

### enum_parse_safety_check

**G03-A1（03-1 / dataflow / `try_has_catch`）— 实锤失明**
- 文件：`src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt`
- find：`val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }`
- replace：`val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (e: Exception) { throw e }`
- 为什么门绿：判据只要求 `try` 之后紧跟 `catch (…) { … }` 且块非空，**从不看 catch 体**；
  `throw e` 把捕获到的 `IllegalArgumentException` 原样抛回，语义上与裸调等价。
- 反攻实测：`ProviderRepository.kt:2682` 红线。

**G03-A2（03-2 / dataflow / `runCatching_result_consumed`）— 实锤失明**
- 文件：`src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt`
- find：`else runCatching { ThinkingLevel.valueOf(raw) }.getOrDefault(ThinkingLevel.OFF)`
- replace：`else runCatching { ThinkingLevel.valueOf(raw) }.onFailure { }.getOrThrow()`
- 为什么门绿：只取 `runCatching {…}` 右花括号后的**第一跳**（`.onFailure`），
  第一跳在安全名单里就放行，后面接 `.getOrThrow()` 把 Result 重新引爆照样绿。
- 反攻实测：`ConfigBuiltins.kt:603` 红线。

**G03-A3（03-3 / spelling / `runCatching_result_consumed`）— 实锤失明**
- 文件：`src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt`
- find：`else runCatching { ThinkingLevel.valueOf(raw) }.getOrDefault(ThinkingLevel.OFF)`
- replace：`else enumValueOf<ThinkingLevel>(raw)`
- 为什么门绿：站点正则只有 `\b([A-Z][\w.]*)\.valueOf\s*\(`，
  stdlib 泛型拼写 `enumValueOf<T>(raw)` 没有 `.valueOf(` token，门连挂载点都扫不到。
- 反攻实测：`ConfigBuiltins.kt:603` 红线。

**G03-A4（03-4 / parser-death / `try_has_catch`）— 实锤失明**
- 文件：`src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt`
- find：`val credentialType = try { ProviderCredential.valueOf(dict.optString("credentialType", "apiKey")) } catch (_: Exception) { ProviderCredential.apiKey }`
- replace：`val credentialType = "${ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))}".let { try { ProviderCredential.valueOf(it) } catch (_: Exception) { ProviderCredential.apiKey } }`
- 为什么门绿：自写 `_strip_comments_and_strings` 把双引号内**全部**字符（含 `${…}`
  插值里的可执行代码）抹成空格，插值里的 `.valueOf(raw)` 对扫描器不存在。
- 反攻实测：`ProviderRepository.kt:2685` 红线。

**G03-A5（03-5 / family-hole / `try_has_catch`）— 实锤失明**
- 文件：`src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt`
- find：`val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return emptyList() }`
- replace：`… } catch (_: kotlinx.coroutines.CancellationException) { return emptyList() }`
- 为什么门绿：只做 `catch` 关键字 + 括号/花括号配平，**不解析 catch 类型**；
  `CancellationException` 接不住 `IllegalArgumentException`，异常照样穿透。
- 反攻实测：`ProviderRepository.kt:2789` 红线。

**G03-A6（03-6 / dataflow / 对照，原本已抓）** — `runCatching { … }.getOrThrow()` 紧邻形态。
- 修后仍红：`ConfigBuiltins.kt:603`（链上含 `getOrThrow` ⇒ 失守）。

### trace_eval_check

**G05-A1（05-1 / family-hole / `event_integrity_pairing`）— 实锤失明**
- 文件：`tests/traces/golden/traces/truncated-tool-call-refused.jsonl`（整段 turn-1 事件块替换）
- replace 要点：`tool_call` 的 `tool_id` 改空串 `""` 且 `turn` 改 `0`；
  配对 `tool_result` 的 `tool_id` 也改空串、工具名改 `shell_execute`。
- 为什么门绿：`has_ids = any(bool(e.get("tool_id")) …)` 在两侧都是空串时为 False，
  函数直接 `return []` —— 「id 被抹成空串」被当成「手写无 id 夹具」，配对检查整体跳过，
  于是 `file_read` 调用配 `shell_execute` 结果也无人校验。
- 反攻实测（三条独立红线）：
  `tool_result #1 reports tool 'shell_execute' but tool_call #1 ran 'file_write' — an id-less trace is paired by order`；
  `pair #1 ('file_write'): turn mismatch — call turn 0, result turn 1`；
  `tool sequence mismatch: expected ['file_write'], got []`（该 call 落在截断轮）。

**G05-A2（05-2 / dataflow / `some_tool_failed_with_output`）— 实锤失明**
- 文件：`tests/traces/golden/traces/offloaded-payload-refused.jsonl`
- replace 要点：turn 0 的 `file_write` 结果由「拒绝 stub」改成 `Wrote 150 bytes` + `success: true`；
  turn 1 的真正落盘改成 `Error: disk full` + `success: false`。
- 为什么门绿：每个字段判据都只看「**存在**满足条件的记录」——`some_tool_failed` 见到
  turn-1 的失败就满足，工具序列仍是 `[file_write, file_write]`，`all_tools_succeed` 未声明，
  于是「先写成功、最终写失败」的轨迹判绿。
- 反攻实测：`terminal_state Succeeded but tool 'file_write' is left in a FAILED state (latest result, turn 1): Error: disk full`。

**G05-A3（05-3 / family-hole / `event_integrity_pairing`）— 实锤失明**
- 文件：`tests/traces/golden/traces/truncated-tool-call-refused.jsonl`
- replace：配对 `tool_result` 的 `"turn":1` → `"turn":null`。
- 为什么门绿：`if c_turn is not None and r_turn is not None and c_turn != r_turn` 三值短路 ——
  `turn: null` 既不报"缺 turn"，也不报轮次错位。
- 反攻实测：`tool_id 'call_golden_trunc_r1': tool_result has no integer \`turn\` — …`。

**G05-A4（05-4 / completeness / `tools_sequence_and_forbidden`）— 实锤失明**
- 文件：`tests/traces/golden/traces/truncated-tool-call-refused.jsonl`
- replace 要点：把 `file_write` 的 call/result 挪进 `stop_reason: length` 的 turn 0
  （内容为半截参数），turn 1 只剩 `turn_start` / `turn_end`。
- 为什么门绿：`called_tools` 把**所有轮次**的 `tool_call` 平铺，完全不关联
  `turns[i].stop_reason`，被丢弃/截断轮里的工具调用照样抵充序列指标。
- 反攻实测：`tool sequence mismatch: expected ['file_write'], got [] (calls in truncated turns are excluded: {0})`
  以及 `reports success inside a TRUNCATED turn (stop_reason length/max_tokens)`。

**G05-A5（05-5 / family-hole / `all_tools_succeed`）— 实锤失明**
- 文件：`tests/traces/golden/selftest-failed-run.json`
- replace：`tool_result` 的 `"success":false` → `"success":true`、`output:"exit code 1"` → `"exit code 0"`。
- 为什么门绿：`if expected.get("all_tools_succeed") is True:` —— 只有 `true` 有语义，
  `false`（falsy）整块跳过，于是"至少一次失败"这个反向契约从未被执行。
- 反攻实测：`all_tools_succeed=false: every tool_result succeeded — the reverse contract … is not satisfied`。

**G05-A6（05-6 / family-hole / 对照，原本已抓）** — `output` 改 `"   "`。
- 修后仍红：`some_tool_failed: the failed tool_result carries no diagnostic output`（`.strip()` 判据保留）。

## ③ 判据改造（旧形态 → 新形态）

### enum_parse_safety_check（+449 / −142 行）

| # | 旧形态 | 新形态 | 对治 |
|---|---|---|---|
| 1 | 自写 `mask_code()` 状态机（把 `${…}` 一并抹平） | 删掉自写词法器，改用 `_kotlin_lexer.analyse()`；在 code 视图上把每处 `${…}` 的**内层代码**按片回贴（内层字符串/模板仍由 `analyse()` 掩蔽，严格等长） | 03-4 |
| 2 | `parse_blocks()` + 正则分类块 | `enclosing_blocks()`：从站点向后走深度，用 `_kotlin_ast.match_bracket` 取每个外层 `{` 的配对区间；配对失败 ⇒ 违规 | 统一结构口径 |
| 3 | `_try_has_catch`：`try` 之后见到 `catch` 即算保护 | `try_protects()`：逐个 catch 子句解析 `(var: Type)`；**类型**必须能接住 `IllegalArgumentException`（`IllegalArgumentException` / `RuntimeException` / `Exception` / `Throwable`，未知类型 fail-closed）；**块体**末句以 `throw` 抛出**同一个变量** ⇒ 该子句不算保护；多子句任一满足即保护；`finally` 跳过 | 03-1、03-5 |
| 4 | `_result_consumed`：只看右花括号后第一跳 | `result_consumed()`：`result_chain()` 走完整 `.name(…)` / `?.name {…}` 链（跳过实参表、尾随 lambda、泛型实参）；链上出现 `getOrThrow` / `throwOnFailure` 任意位置 ⇒ 失守；否则链上出现任一处理型接收者 ⇒ 保护 | 03-2 |
| 5 | 站点正则只有 `X.valueOf(` | 增加 `enumValueOf<T>(raw)`（stdlib 同义拼写，字节码等价）；两族站点合并去重 | 03-3 |
| 6 | 括号不配平才 fail-closed | 增加：`analyse()` 失败、模板未闭合、`_skip_template` 不可用、站点外层括号无法配对 ⇒ 全部按违规（fail-closed），**不回退原文扫描** | parser-death 口径 |
| 7 | 7 条 `--selftest` 臂 | **17 条**（11 红 + 6 绿），新增 6 条对治臂 + 5 条"收紧不能误伤"臂（异常翻译、`runCatching { enumValueOf<T>(name) }.getOrDefault(d)`、消费者后接 `?.let`、散文提及 `Foo.valueOf(`） | 防回归 + 防误伤 |

### trace_eval_check（+266 / −35 行）

| # | 旧形态 | 新形态 | 对治 |
|---|---|---|---|
| 1 | `if not any(ids): return []`（整段配对检查被跳过） | 无 id 的 trace 改**按顺序配对**（对齐前 min(n,m) 对），仍校验工具名 + 轮次；有一侧带 id、另一侧不带 ⇒ 仍是违规 | 05-1 |
| 2 | 轮次比对 `c_turn is not None and r_turn is not None and …` | `_check_turns()`：配对两侧都必须是**整数** `turn`（`None` / 非整数 ⇒ 违规），再比相等 | 05-3 |
| 3 | `tools` 序列 = 全部 `tool_call` | 只取**有效轮次**的 `tool_call`（`turn_end.stop_reason` / `finish_reason ∈ {length, max_tokens}` 的轮次剔除），错误信息带上被剔除的轮次集合 | 05-4 |
| 4 | 截断轮无任何检查 | 截断轮里 `success: true` 的 `tool_result` 直接红（参数是半截生成物，不是落地效果） | 05-2 / 05-4 |
| 5 | `if expected.get("all_tools_succeed") is True:` | `if "all_tools_succeed" in expect:` 双向：`true` ⇒ 全成功；`false` ⇒ 至少一次失败；非布尔 ⇒ 违规 | 05-5 |
| 6 | 无"终态"概念 | golden 声明 `terminal_state == "Succeeded"` 时，每个工具的**末次** `tool_result` 必须是成功（失败必须被重发并恢复） | 05-2 |
| 7 | 10 条 `--selftest` 臂 | **20 条**（14 红 + 6 绿）：新增 05-1 / 05-2 / 05-3 / 05-4 / 05-5 各 1-2 条对治臂 + 4 条正向臂（无 id 但名字/轮次一致、截断轮无工具事件、失败后恢复、`all_tools_succeed:false` 被失败满足） | 防回归 + 防误伤 |

两门均**未引入新常量白名单式豁免**：enum 门的例外只有"能接住 IAE 的类型"与
"catch 体不是原样重抛"，trace 门的例外只有 schema 本身声明的截断原因词。

## ④ 四层证据

### L1 本门（真树 + 自测）

```
$ python3 scripts/scan/enum_parse_safety_check.py --selftest
ENUM_PARSE_SELFTEST=PASS (17 fixtures, 0 mismatched)
$ python3 scripts/scan/enum_parse_safety_check.py .
✅ All enum parses are properly guarded          # rc=0

$ python3 scripts/scan/trace_eval_check.py --selftest
TRACE_EVAL_SELFTEST=PASS (20 arms)
$ python3 scripts/scan/trace_eval_check.py .
  ✅ offloaded-payload-refused.json / selftest-failed-run.json / selftest-normal-run.json / truncated-tool-call-refused.json
trace_eval: 4 passed, 0 failed, 4 total          # rc=0
```

共享原语自测先跑：`KOTLIN_LEXER_SELFTEST=PASS (20 arms)` / `KOTLIN_AST_SELFTEST=PASS (32 arms)` /
`KOTLIN_VALUEFLOW_SELFTEST=PASS (26 arms)`。

### L2 反攻（硬验收，用 `verify_batch5.py --mode full`）

```
# 修前（基线树 wt/t03 未改动时）
enum_parse_safety_check.py : SUMMARY: {'BLIND_CONFIRMED': 5, 'CAUGHT': 1}
trace_eval_check.py        : SUMMARY: {'BLIND_CONFIRMED': 5, 'CAUGHT': 1}

# 修后
python3 verify_batch5.py --witnesses witnesses-A.json --repo wt/t03 --mode full \
        --only enum_parse_safety_check.py --out probe/t03-enum_parse_safety_check.json
  [1/6] G03-A1 -> CAUGHT (rc=1)   [2/6] G03-A2 -> CAUGHT (rc=1)   [3/6] G03-A3 -> CAUGHT (rc=1)
  [4/6] G03-A4 -> CAUGHT (rc=1)   [5/6] G03-A5 -> CAUGHT (rc=1)   [6/6] G03-A6 -> CAUGHT (rc=1)
  SUMMARY: {'CAUGHT': 6}

python3 verify_batch5.py --witnesses witnesses-A.json --repo wt/t03 --mode full \
        --only trace_eval_check.py --out probe/t03-trace_eval_check.json
  [1/6] G05-A1 -> CAUGHT (rc=1)   [2/6] G05-A2 -> CAUGHT (rc=1)   [3/6] G05-A3 -> CAUGHT (rc=1)
  [4/6] G05-A4 -> CAUGHT (rc=1)   [5/6] G05-A5 -> CAUGHT (rc=1)   [6/6] G05-A6 -> CAUGHT (rc=1)
  SUMMARY: {'CAUGHT': 6}
```

**还原校验**：12 条记录 `restored: true`（驱动逐条 sha256 比对），
`git status --porcelain` 只剩两门本体（无 `src/` 残留、无 `tests/` 残留）。

各条红线的报错文本（逐条核对"是否红在应当红的那条判据上"）：

| 见证 | 红线报错（摘要） |
|---|---|
| G03-A1 | `ProviderRepository.kt:2682 … catch (e: Exception) { throw e }` |
| G03-A2 | `ConfigBuiltins.kt:603 … .onFailure { }.getOrThrow()` |
| G03-A3 | `ConfigBuiltins.kt:603 … enumValueOf<ThinkingLevel>(raw)` |
| G03-A4 | `ProviderRepository.kt:2685 … "${ProviderCredential.valueOf(…)}"` |
| G03-A5 | `ProviderRepository.kt:2789 … catch (_: CancellationException)` |
| G03-A6 | `ConfigBuiltins.kt:603 … .getOrThrow()`（对照，保持） |
| G05-A1 | `tool_result #1 reports tool 'shell_execute' but tool_call #1 ran 'file_write'` + `turn mismatch` + `tool sequence mismatch` |
| G05-A2 | `terminal_state Succeeded but tool 'file_write' is left in a FAILED state (latest result, turn 1)` |
| G05-A3 | `tool_id 'call_golden_trunc_r1': tool_result has no integer turn` |
| G05-A4 | `tool sequence mismatch: expected ['file_write'], got [] (calls in truncated turns are excluded: {0})` + `success inside a TRUNCATED turn` |
| G05-A5 | `all_tools_succeed=false: every tool_result succeeded` |
| G05-A6 | `some_tool_failed: … carries no diagnostic output`（对照，保持） |

### L3 邻门 + 冻结夹具

邻门 8/8 `rc=0`（语义相邻：枚举注册 / provider 边界 / thinking 接线 / 四处同步 /
compaction 真值 / 输入法 inset / model-use 协议 / KaTeX 桥）：
`provider_type_registry_check` · `provider_boundary_guard` · `thinking_wiring_guard` ·
`four_way_sync_check` · `compact_pin_truth_check` · `ime_inset_guard` ·
`modeluse_proto_single_source_check` · `katex_bridge_arity_check`。

`test_scan.py` 里与本支相关的**冻结夹具**（不改文件直接调用两函数）：
```
$ python3 -c "import sys; sys.path.insert(0,'scripts/scan'); import test_scan as t; t.test_enum_parse(); t.test_trace_eval()"
  ✅ guarded valueOf (runCatching + multi-line try) exits 0
  ✅ bare valueOf caught (exit 1)
  ✅ clean golden exits 0
  ✅ tool-sequence drift caught (exit 1)
  ✅ some_tool_failed with all-succeeded results caught (exit 1)
  ✅ some_tool_failed satisfied by a failed result (exit 0)
  ✅ missing trace_end caught (exit 1)
  ✅ missing trace file caught (exit 1)
```

### L4 全量（沙箱口径 + CI 口径）

- 逐门直跑（`scan.sh` 在 13 支并行窗口内被 ENOSYS/S28 打死，按 `sandbox-limits.md`
  的健康路径改成每门独立进程循环）：**112 门 rc=0**（109 个 `*_check.py`/`*_guard.py`
  + `i18n_check` + `process_logging_gate` + `turn_tail_finalize_order`）。
  记录：`probe/t03-gates.txt`（112 行，全部 `0 <gate>`）。
- 本窗口内 `bash scripts/scan/scan.sh` 两次（16/112 处、以及 22 门后）、
  `python3 scripts/scan/test_scan.py` 三次（`subprocess.wait` 与 `makedirs('/')` 上）
  全部崩在 `OSError: [Errno 38] Function not implemented` —— 已知沙箱症状
  （`sandbox-limits.md` S28 及 2026-10-18 变体、`gate_run.sh` 的设计动因），非门红；
  三次 `test_scan` 的日志停在 `four_way_sync_check` / `makedirs` 上，无一例 `❌` 夹具。
- **因此全量 `scan.sh` + `ALL 344 CASES PASS` 的证据以分支 CI 日志为准**（CI 跑的
  就是这两条命令）：`║ Summary: 112 passed, 0 failed` 恰 1 条 + `✅ ALL 344 CASES PASS`
  恰 1 条 + 门头 112 互异 + ` e: ` 0，见 §⑤。

## ⑤ CI（真读数，非占位）

| 项 | 值 |
|---|---|
| 分支 | `fix/b5-enum-trace` |
| 门提交（= CI head_sha） | `e7a6e0d17fd10e10b125d0e64dde8f0d303db291` |
| run | **#2684** / run id `40207003289`（`workflow_dispatch`，`attempt 1`，`completed / success`） |
| job | `121209987397`（`build`，26 步：`not_ok = []`，唯一 `skipped = Publish to Releases`＝main 门控） |
| 起止 | 2026-10-25T15:27:15Z → 15:43:44Z（≈16.5 min） |
| job 日志 | `probe/job-121209987397.log`（**1,482,095 B** / 17,854 行） |

三方 sha 一致性：本地 `git rev-parse HEAD` = `e7a6e0d17…` · run API `head_sha` = `e7a6e0d17…` ·
`branches` ref 指针同 sha（push 后未再推送源码改动）——逐字符一致。

日志逐条读数（`probe/job-121209987397.log`）：

| 判据 | 读数 | 结论 |
|---|---|---|
| `║  Summary: 112 passed, 0 failed` | **恰 1 条**（15:28:58Z） | ✅ 112 门全绿 |
| 子套件 `Summary: N passed, 0 failed` | 7 条（28 / 5 / 14 / 36 / 15 / 25 / 10） | ✅ 全部 0 failed，是 scan.sh 的分组小结非门总数 |
| `✅ ALL 344 CASES PASS` | **恰 1 条**（15:31:24Z） | ✅ `test_scan.py` 344 夹具全过（本地三次均被沙箱 ENOSYS 打断，此处为权威证据） |
| 门头 `[N/112]` | 112 条，编号 1..112 **互异**、总数一致 | ✅ 无门被跳过/重复 |
| ` e: `（kotlinc 错误标记） | **0** | ✅ 编译/测试无错误行 |
| 含 `FAILED` 的 6 行 | 全是**PASSED 用例名的子串**（`… produces FAILED without calling provider PASSED` 等） | ✅ 无真失败 |
| `trace_eval: 4 passed, 0 failed, 4 total` | 1 条 | ✅ 四条 golden 在 CI 树上真跑 |
| `✅ enum_parse_safety_check.py on real repo exits 0` | 1 条 | ✅ 本门在 CI 树上真跑 |
| `ENUM_PARSE_SELFTEST` / `TRACE_EVAL_SELFTEST` | **0 命中** | ⚠️ 见 R9：门的 `--selftest` stdout 不进 CI 日志，需主会话按 §⑦ 注册标记断言 |

报告提交在门提交之后（只新增 `fix-reports/FIX-BATCH5-t03.md`），源码树与 CI 绿 sha 等价校验：
`git diff --name-only e7a6e0d17..HEAD -- . ':(exclude)fix-reports'` = **0 行**。

## ⑥ 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

- **R1｜catch 把异常"翻译"成新异常仍算保护**（`catch (e: Exception) { throw IllegalStateException(e) }`）。
  触发条件：下一波见证以「catch 里 new 一个新异常但调用方并不处理」的形态出现，或真树出现"转译后没人接"的回归。
  触发后方案：给 catch 体加"新异常类型必须在调用方有 catch 站点"的跨文件引用判据，或引入显式标记注释（`// strict-enum-parse:`）让豁免可审计。
  搁置理由：本仓有两处**文档化的严格转译**（`ThinkingLevelWire.kt:35-40`、`ModelExecutionService.kt:1861-1864` 的 `safeEnum` → `UnknownEnumValueException`，专为跨版本 worker 重试设计），机械判"任何 throw 都红"会直接误伤既有设计；判"调用方是否处理"需要跨文件调用图，超出本门范围。

- **R2｜空 catch 体（`catch (_: Exception) {}`）算保护**。
  触发条件：出现"吞掉解析失败 ⇒ 静默丢配置/静默降级"的实际回归。
  触发后方案：把空体改红，要求至少一个默认值表达式或一条日志调用。
  搁置理由：本门职责是"不崩栈"，静默吞是另一族（liveness/logging 门已有独立判据）；当前真树 0 个空 catch 站点。

- **R3｜未知 catch 类型一律按"接不住"处理（fail-closed）**。
  触发条件：真树出现 `catch (e: MyException)` 且 `MyException` 继承 `IllegalArgumentException` 之类。
  触发后方案：建异常继承白名单（扫 `class X : <IAE 超类>()` 派生），把已知子类并入 `IAE_SUPERTYPES`。
  搁置理由：当前真树的 catch 类型只有 `Exception` 与 `IllegalArgumentException` 两种，为 0 站点的形态建装置属装置比例失衡。

- **R4｜无 id 的 trace 容忍多余事件**（顺序配对只覆盖前 `min(n, m)` 对）。
  触发条件：有 recorder 真的丢 `tool_id`（那时"文件型 trace 必须有 id"才成立）。
  触发后方案：给 `evaluate()` 传入 provenance（`trace` 文件 vs 内联 `trace_lines`），文件型强制 id；同时由主会话改 `test_scan.py` 的冻结夹具。
  搁置理由：`test_scan.py` 现有夹具 `some_tool_failed satisfied by a failed result` 本身就是 1 call / 2 results 且要求 `rc=0`，加计数校验会当场破冻结面（本支不许改 `test_scan.py`）。

- **R5｜`some_tool_failed` 的诊断串只要求 `strip()` 后非空**（`"."` 之类也算诊断）。
  触发条件：下一波见证用单标点/无语义 output。
  触发后方案：要求 output 里出现原因词或达到最小长度（≥12 字符），或两条都要求。
  搁置理由：词表判据易误伤（真树 4 个 golden 的诊断串全是长句），且 05-6 是**对照臂**（原本已抓），扩判据不在本支 radius 内。

- **R6｜截断轮只认 `stop_reason` / `finish_reason ∈ {length, max_tokens}`**。
  触发条件：trace schema 换字段名或新增截断值（如 `max_length` / `content_filter`）。
  触发后方案：把新值并入 `TRUNCATED_STOP_REASONS`，并把 schema 版本号钉进门的 docstring / 判据。
  搁置理由：当前 schema 2.0 只有 `stop_reason`（`stop` / `tool_calls` / `length`）与 `finish_reason` 两种字段名，已双读。

- **R7｜"Succeeded 不留失败末态"只在 golden 显式声明 `terminal_state` 时生效**。
  触发条件：某 golden 省略 `terminal_state` 且正好是 05-2 形态。
  触发后方案：未声明时改用 trace **实际**的 `terminal_state` 判定。
  搁置理由：用实际值会把"golden 想断言的契约"与"trace 报告的读数"混为一谈（wide3 波已有把 expect 与 actual 混淆而误判的先例），宁可保守。

- **R8｜模板回贴依赖共享词法器的私有 `_skip_template`**。
  触发条件：共享模块改名/删除该函数，或下次重建 rootfs 后共享层被替换成不含它的版本。
  触发后方案：请主会话在 `_kotlin_lexer` 公开 `template_spans(src)`（返回 `[(open, close)]`），本门改调公开 API。
  搁置理由：任务书硬约束禁止本支修改共享模块；`getattr` 取不到时本门**fail-closed**（所有文件报"无法解析"），不会静默放行。

- **R9｜两门自证 stdout 不进 CI 日志**（与 M6 同族）。
  触发条件：主会话并集收口时忘记在 `test_scan.py` 注册标记断言。
  触发后方案：按 §⑦ 的两行注册项加入 `DA_AUTO_APPROVE_GATES`（或等价表），使 17 + 20 条臂在 CI 里有人见证。
  搁置理由：任务书明令本支不碰 `test_scan.py`（冻结面），注册统一由主会话做。

## ⑦ `test_scan.py` 注册项（交主会话统一注册）

```python
# 标记串：门 --selftest 的稳定收尾行
("enum_parse_safety_check", "ENUM_PARSE_SELFTEST=PASS", (REPO_ROOT,))   # 17 arms
("trace_eval_check",        "TRACE_EVAL_SELFTEST=PASS", (REPO_ROOT,))   # 20 arms
```

（两门的 `--selftest` 收尾行原文：`ENUM_PARSE_SELFTEST=PASS (17 fixtures, 0 mismatched)`、
`TRACE_EVAL_SELFTEST=PASS (20 arms)`；真树参数均为位置参数 `<repo_root>`，无需 `--root`。）

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t03

# 0) 共享原语自测
python3 scripts/scan/_kotlin_lexer.py --selftest     # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest       # 32 arms
python3 scripts/scan/_kotlin_valueflow.py            # 26 arms

# 1) 本门
python3 scripts/scan/enum_parse_safety_check.py --selftest
python3 scripts/scan/enum_parse_safety_check.py .
python3 scripts/scan/trace_eval_check.py --selftest
python3 scripts/scan/trace_eval_check.py .

# 2) 反攻（12 条见证必须全部 CAUGHT，restored 全 True）
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json \
  --repo . --mode full --only enum_parse_safety_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t03-enum_parse_safety_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json \
  --repo . --mode full --only trace_eval_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t03-trace_eval_check.json

# 3) 邻门
for g in provider_type_registry_check provider_boundary_guard thinking_wiring_guard \
         four_way_sync_check compact_pin_truth_check ime_inset_guard \
         modeluse_proto_single_source_check katex_bridge_arity_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1 || echo "FAIL $g"
done

# 4) 全量（沙箱窗口内逐门直跑；CI 侧跑 scan.sh + test_scan.py）
GATE_WAIT=600 sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"

# 5) CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --workflow build-apk.yml --ref fix/b5-enum-trace
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/b5-enum-trace
```
