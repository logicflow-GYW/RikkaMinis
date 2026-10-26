# FIX-BATCH7-t09 — `browser_history_snapshot_check` + `collection_copy_on_mutate_check`

分支 `fix/b7-browserhistory-collectioncopy` · 基线 `base/batch5-shared` @ `e145ec961`
（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow`） · 门提交 `ca52fb937`

---

## 1. 门与轴

| 门 | 见证数 | 修前 | 修后 | 轴（审计方归类） |
|---|---:|---|---|---|
| `scripts/scan/browser_history_snapshot_check.py` | 6 | 5 BLIND + 1 CAUGHT（对照） | **6/6 CAUGHT** | `dataflow`(2) · `family-hole`(2) · `completeness`(1) · `parser-death`(1) |
| `scripts/scan/collection_copy_on_mutate_check.py` | 6 | 6 BLIND | **6/6 CAUGHT** | `spelling`(1) · `order`(1) · `anchor`(2) · `dataflow`(1) · `parser-death`(1) |

两门同一族失效：**判据锚「某个 token 存在」，而缺陷挪动的是这个 token 的效果**（值是否仍派生自它、调用是否可达、返回的是不是它、锁区间外还在被谁引用）。修法统一为「结构 → 数据流 → 可达性」三层，底层全部走共享原语（硬约束 1）。

---

## 2. 见证原文（file / find → replace / 为什么门绿）

### 门 A：`browser_history_snapshot_check.py`

| # | 轴 | 目标文件 | 补丁形状 | 修前门为何绿 |
|---|---|---|---|---|
| 02-1 | dataflow | `browser/BrowserHistory.kt` | 在 `val snapshot = synchronized(entries){entries.toList()}` 后加 `val liveAlias = synchronized(entries) { entries }`，循环改 `for (entry in liveAlias)` | `live_entries_reads()` 先把每个 `synchronized(entries){…}` 区间整体涂白再搜 `\bentries\b`：锁内那次「把引用带出去」的读取被自己涂掉，锁外循环遍历的是别名，不含 `entries` 一词；A1/A2 双绿 |
| 02-2 | family-hole | 同上 | `update { it + 1 }` → `update { it + 0 }` | `IDENTITY_LAMBDA_RE = ^\{?\s*(?:id -> )?(id)\s*\}?$` 只认「整体就是一个标识符」，`it + 0` 匹配 None → 判为「产出新值」 |
| 02-3 | completeness | 同上 | 保留 `private fun bumpRevision()` 及其 `.update { it + 1 }`，删掉 `record()` 里的 `bumpRevision()` 调用 | 判据在整份文件里搜 `FLOW_WRITE_RE`，只要定义还在，`sites` 非空且值为 `{ it + 1 }`；从不检查调用点 |
| 02-4 | parser-death | 同上 | `val _q1 = '\"'; val unlocked = entries; val _q2 = '\"'` 包裹读取，循环改 `for (entry in unlocked)` | 自写 `strip_comments_and_strings()` 只有 line/block/str 三态，**没有单引号 char 字面量态**：读到 `'\"'` 里的 `"` 就当字符串开始，把两处之间的真代码整段涂白 |
| 02-5 | dataflow | `ui/browser/BrowserHistorySheet.kt` | `val todayLabel = stringResource(R.string.browser_history_today).let { \"To\" + \"day\" }` | `string_resource_bindings()` 用**前缀**正则 `val x = stringResource(R.string.k`，不看闭括号后是否还挂着 `.let{}`；B2 的字面量臂又只认完整 `\"Today\"` |
| 02-6 | family-hole（对照，已被抓） | `browser/BrowserHistory.kt` | `update { rev -> rev }` | 修前即红（`ident.group(1)==ident.group(2)`）；修后必须仍红 |

### 门 B：`collection_copy_on_mutate_check.py`

| # | 轴 | 目标文件 | 补丁形状 | 修前门为何绿 |
|---|---|---|---|---|
| 13-1 | spelling | `collections/GroupWriteRules.kt` | helper 里追加 `apply.invoke(g); return g`（后面留一句不可达的 `return working`） | `apply_args()` 用 `\bapply\s*\(` 找调用 → `.invoke(` 拼写零命中；`returns[-1]` 只看最后一个 return（= 不可达的 `return working`） |
| 13-2 | order | 同上 | `val _unused = g.memberEntryIds.toMutableList()` + `val working = g.copy()` | `if \"toMutableList()\" not in core_body` 只问「函数体里出现过没有」，不问它是否作为 `.copy(...)` 的实参 |
| 13-3 | anchor | `collections/GroupsCollection.kt` | `if (false) repo.updateGroup(mutatedGroupCopy(g, apply)); apply(g); repo.updateGroup(g)` | arm0 只看 `upd[0]`（死分支里的那个合规调用），且发现 `mutatedGroupCopy(` 后直接 return，函数体里真实的 `apply(g)` / `repo.updateGroup(g)` 一概不看 |
| 13-4 | dataflow | `collections/ProvidersCollection.kt` | `repo.updateInstance(apply(inst.let { return }))` | `call_is_whole()` 只判「以 `apply(` 开头、配平的 `)` 后为空串」，不判括号里面装的是什么；`return` 在 lambda 里是非局部返回，会在写入前退出 `mutate()` |
| 13-5 | anchor | `collections/ModelsCollection.kt` | `if (false) repo.updateEntry(apply(e)); repo.updateEntry(e)` | arm2 只看 `ups[0]`（死分支里的 `apply(e)`），`ups[1]`（`e`）不检查 |
| 13-6 | parser-death | `collections/GroupWriteRules.kt` | `val openMask = \"/*\"; apply(g); if (openMask.isNotEmpty()) return g; val closeMask = \"*/\"` | 自写 `strip_comments()` 没有字符串状态机：`\"/*\"` 直接开块注释，中间的 `apply(g); return g` 被整段抹掉 |

---

## 3. 判据改造（旧形态 → 新形态）

### 3.1 `browser_history_snapshot_check.py`

底座替换（硬约束 1）：
`strip_comments_and_strings` / `strip_comments_only` → `mask_code` / `mask_comments`；
`brace_span` / `paren_span` → `match_bracket`；`statement_tail` → `expr_end` + `rhs_text`；
`function_body` / `function_bodies` → 共享 `function_body`；`split_args` → `call_args`；
`producer_value_text` → `expr_end` / `match_bracket`。词法失败 `KotlinLexError` → A0 违规 + rc=1（fail-closed，不回退原文扫描）。

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| **A1**（02-1 / 02-4） | 涂白锁区间后搜 `\bentries\b`（锁内「泄漏引用」被自己涂掉；char 字面量可致盲） | `live_list_bindings()` 派生「绑到活列表的标识符集合」（`val x = entries`、`val x = synchronized(entries){ entries }`，可传递、4 轮定点；绑定处不算读），锁外任何一处引用即红。字符字面量交由共享词法器识别 |
| **A2** | `SNAPSHOT_RE`（不变） | 不变 |
| **B1p 值**（02-2 / 02-6） | `IDENTITY_LAMBDA_RE = ^\{?\s*(?:id -> )?(id)\}?$` | `_symbolic()` 符号折叠：lambda 形参作符号，其余子式常量折叠（`_symbolic` 覆盖 `+ - * /`、`plus/minus/times/div`），折回形参即红。`{ it }` / `{ rev -> rev }` / `{ it + 0 }` / `{ it * 1 }` / `{ it.plus(0) }` 全红，`{ it + 1 }` / `{ rev -> rev + 1 }` 绿 |
| **B1p 可达**（02-3，新增） | 无（只查定义存在） | `mutation_entry_points()` 从函数表派生「公开 + 变更 `entries`」的入口，要求每个入口在 `path_feasible` 的路径上调用某个 producer（或自身写 flow）。`if (1 == 2) bumpRevision()` 不算 |
| **B2 绑定**（02-5） | 前缀正则 `val x = stringResource(R.string.k` | `string_resource_bindings()` 返回 `(clean, overwritten)`：用 `value_sources` 解析初始化器，**每个**最终值来源都必须仍是同一个 `stringResource(R.string.<key>)`。`.also{}`（剥到接收者）绿，`.let{}/.run{}/?:` 红 |
| **B2 实参** | 顶层逗号手写切分 | `call_args()` + 逐标签判（字面量 / overwrite 绑定 / 未绑定 / 绑错 key） |

新增臂：12 条（9 条红臂 + 3 条 clean 控制臂：alias 只在锁内读、真实自增 `{ rev -> rev + 1 }`、`.also` 保留资源）。自测 30 → **42 arms**。

### 3.2 `collection_copy_on_mutate_check.py`

底座替换：`strip_comments` → `mask_code`；`extract_braced_fun` → `match_bracket`；`split_args` → `split_top`。词法失败 → 违规（fail-closed）。

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| **lambda 调用拼写**（13-1） | `\bapply\s*\(` | `invocation_res(name)`：`name(` / `name.invoke(` / `name?.invoke(` 同判 |
| **返回值**（13-1） | `returns[-1] != copy_var` | `returned_values()`：**所有** `return`（`return@` 除外）经 `value_sources` 解析后每个都必须是工作副本；不可达的 `return working` 不再为 `return g` 背书 |
| **副本新鲜度**（13-2） | `\"toMutableList()\" in core_body` | `FRESH_LIST_RE` 只作用于 `copy(...)` 的实参文本（`toMutableList()` / `mutableListOf(` / `ArrayList(` / `toList().toMutableList()`）；实参里没有即红 |
| **持久化调用集合**（13-3 / 13-5） | 只查 `ups[0]` | `update_call_args()` 只收 `path_feasible` 的调用，且**逐个**判；arm0 每个 persist 都必须是 `mutatedGroupCopy(...)`，arm1 每个都必须持久化工作副本，arm2 每个都必须恰是 `param(装载对象)` |
| **arm2 实参形态**（13-4） | `call_is_whole(arg, param)` = 前缀 + 尾空 | 增加 `live=`：括号内必须恰是装载对象标识符（`LOADER_RE` 从 `repo./entry(/instance(/group(` 派生），且括号内不得含裸 `return` |
| **arm0 旁路**（13-3） | 发现 `mutatedGroupCopy(` 后不再看别的 | 额外拒绝：委派体里若还直接调用 `param(...)`（对活对象），即红 |
| **词法器**（13-6） | 自写 `strip_comments`（无字符串态） | 共享 `mask_code`（`\"/*\"` 只是字符串） |

新增臂：9 条（6 条见证臂 + 3 条 clean 控制臂：`.also` 副作用的副本、条件分支里的合规 persist、死分支 persist 被忽略后活路径合规）。自测 13 → **22 arms**。

---

## 4. 四层证据

### L1 本门（真树 + 自测）

```
python3 scripts/scan/browser_history_snapshot_check.py .        -> rc=0
python3 scripts/scan/browser_history_snapshot_check.py --selftest
  -> BROWSER_HISTORY_SNAPSHOT_SELFTEST=PASS (42 arms)
python3 scripts/scan/collection_copy_on_mutate_check.py .       -> rc=0
python3 scripts/scan/collection_copy_on_mutate_check.py --selftest
  -> COLLECTION_COPY_ON_MUTATE_SELFTEST=PASS (22 arms)
python3 scripts/scan/_kotlin_lexer.py --selftest     -> PASS (20 arms)
python3 scripts/scan/_kotlin_ast.py --selftest       -> PASS (32 arms)
python3 scripts/scan/_kotlin_valueflow.py --selftest -> PASS (26 arms)
```

自测臂都做了「修前绿 / 修后红」的成对断言：9 条红臂在变异代码上恰好红在它自己的判据上，3 条 clean 控制臂在等价但合法的写法上仍绿（防把好写法一起判红）。

### L2 反攻（硬验收，用任务书指定驱动）

```
python3 verify_batch5.py --witnesses witnesses-A.json --repo . --mode full \
  --only browser_history_snapshot_check.py --out probe/t09-browser_history_snapshot_check.json
[1/6] 02-1 -> CAUGHT (rc=1, exact)   [4/6] 02-4 -> CAUGHT (rc=1, exact)
[2/6] 02-2 -> CAUGHT (rc=1, exact)   [5/6] 02-5 -> CAUGHT (rc=1, exact)
[3/6] 02-3 -> CAUGHT (rc=1, exact)   [6/6] 02-6 -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}

python3 verify_batch5.py --witnesses witnesses-B.json --repo . --mode full \
  --only collection_copy_on_mutate_check.py --out probe/t09-collection_copy_on_mutate_check.json
[1/6] 13-1 -> CAUGHT   [2/6] 13-2 -> CAUGHT   [3/6] 13-3 -> CAUGHT
[4/6] 13-4 -> CAUGHT   [5/6] 13-5 -> CAUGHT   [6/6] 13-6 -> CAUGHT
SUMMARY: {'CAUGHT': 6}
```

12/12 CAUGHT，`restored: true` ×12（sha256 校验），`git status --porcelain src/` 事后为空。

每条变红在**应当红的判据**上（取自 `probe/t09-*.json` 的 tail）：

| # | 命中判据 |
|---|---|
| 02-1 | `A1: save() reads the live \`entries\` list at offset 791 outside synchronized(entries)` |
| 02-2 | `B1p: the revision producer publishes the value it read ('{ it + 0 }')` |
| 02-3 | `B1p: \`record()\` mutates the live \`entries\` list but never advances the revision flow` |
| 02-4 | `A1: … at offset 640 outside synchronized(entries)` |
| 02-5 | `B2: label #1 is \`todayLabel\`, whose initializer reads R.string.browser_history_today but then REPLACES that value` |
| 02-6 | `B1p: … publishes the value it read ('{ rev -> rev }')`（对照臂仍被抓） |
| 13-1 | `\`mutatedGroupCopy\` hands the lambda the LIVE object (\`g\`) beside the working copy` |
| 13-2 | `copy does not re-materialise the mutable member inside \`.copy(... args)\`` |
| 13-3 | `delegated mutate must persist the helper's return … got \`g\`` |
| 13-4 | `functional mutate must persist the lambda's return … got \`apply(inst.let { return })\`` |
| 13-5 | `mutate lambda \`apply\` is never invoked on a reachable path` |
| 13-6 | `\`mutatedGroupCopy\` hands the lambda the LIVE object (\`g\`)` |

### L3 邻门（语义相邻 7 道，全 rc=0）

`browser_cookie_ui_refresh_check` · `browser_destroy_wakeup_check` · `browser_tab_exec_serial_check` · `browser_tabs_lifecycle_check` · `envvar_config_group_check` · `four_way_sync_check` · `provider_write_rules_check`

### L4 全量（沙箱实测）

```
bash scripts/scan/scan.sh
  -> ║  Summary: 112 passed, 0 failed        （一次跑通，未触发 S28）
```

`python3 scripts/scan/test_scan.py` 单进程整跑被 stall-guard 杀（父进程空转等子进程），按既有健康路径用分块驱动
（`probe/t09_testscan_chunks.py`，46 个 `test_*` 函数逐块跑，`sys.argv=["test_scan.py", ROOT]`）：

```
批次 1  12 fn  PASS=133 FAIL=4     批次 3  12 fn  PASS=72  FAIL=0
批次 2  12 fn  PASS=73  FAIL=0     批次 4  10 fn  PASS=62  FAIL=0
```

批次 1 的 4 条 FAIL 全部落在 `test_auto_approve_gates` / `test_da1016_gates` / `test_da1018_gates`
（异常为 `OSError: [Errno 38] Function not implemented`，来自 `subprocess.wait()`，同时伴随
`/bin/sh: can't create /tmp/.minis_cwd_…`）—— 沙箱 ENOSYS 抖动，非门红：三者单跑复绿
（`+2/+0`、`+18/+0`、`+14/+0`）。合计 **46/46 函数、344 pass / 0 fail**。

`git status --porcelain src/` = 空。

---

## 5. CI

| 项 | 值 |
|---|---|
| run | `40270484586` / **#2719** / job `121402039361` / attempt 1 |
| 结论 | **success**（2026-10-26T04:43:05Z → 05:05:24Z，22m19s） |
| head_sha | `ca52fb93755a723c27b77cfcf83a035d40cb99e2` = 本地 `git rev-parse HEAD` 逐字符一致 |
| 步骤 | 26 步，唯一非 success = step 23 **Publish: skipped**（`if: github.ref == 'refs/heads/main'` 门控，分支构建的预期行为） |
| 日志 | 1,486,343 B（0 NUL 字节）|

判读（`probe/t09-ci-job.log`，逐条机械统计）：

| 断言 | 实测 |
|---|---|
| `Summary: 112 passed, 0 failed` 恰一条 | **1**（:1019，带框线前缀 `║`） |
| `ALL 344 CASES PASS` 恰一条 | **1** |
| 门头 `[N/112]` 序号互异且覆盖 1..112 | 112 条 / 唯一 112 / 范围 (1,112) |
| 编译错误 ` e: ` | **0** |
| 含 `FAILED` 的行 | 6 条，**全部**是 PASSED 用例名的子串（`slot denied produces FAILED without calling provider` / `reason PERSISTENCE_FAILED maps to…` / `phase FAILED maps to Failed` / `terminal FAILED maps to Failed` / `reason EXECUTION_FAILED maps to…` / `the fixed tail still reaches a legal FAILED terminal`） |
| 两门在本支的真树见证 | `✅ browser_history_snapshot_check real tree green`（:1308）· `✅ collection_copy_on_mutate_check real tree green`（:1333）· 两门 `selftest has teeth` 各 1（:1307 / :1332）· scan.sh 步里 `collection_copy_on_mutate_check: OK (all mutate sites persist a copy)`（:857） |

**臂数不进 CI 日志**（既有形态，报告 §6 R9）：`test_scan` 的 `selftest has teeth` 行回显的是**注册标记串**
（`…_SELFTEST=PASS`），不是门的完整 stdout，所以 `(42 arms)` / `(22 arms)` 只在本支的 `--selftest` 输出里可见。
两门的新臂因此由本地 L1（自测成对断言）+ L2（12/12 反攻）承载，CI 侧只保留「自测带牙 + 真树绿」这两条。

报告提交与 CI 绿 sha 的差异：`git diff --name-only ca52fb937..HEAD -- . ':(exclude)fix-reports'` = **0**
（见分支 tip 的提交记录）。

---

## 6. 残留（每条带触发条件 / 触发方案 / 搁置理由）

**R1 · 活列表别名只跟到 `val/var` 绑定（browser A1）**
触发条件：有人把 `save()` 的读取改成 `val x = helper(entries)` 这类**跨函数**返回值。
触发方案：给 `live_list_bindings` 加「局部函数返回值摘要」（对 return 表达式跑 `value_sources` 并做 2 轮交叉），或引入跨函数值流原语。
搁置理由：跨函数值流是独立装置（成本 ≫ 单条 latent），且当前 `save()` 内没有这类 helper —— 属同族预防，不是本波见证。

**R2 · 恒等折叠只覆盖算术（browser B1p）**
触发条件：出现 `update { it or 0 }` / `{ it shl 0 }` / `{ it xor 0 }` / `{ it.coerceAtLeast(0) }`。
触发方案：给 `_IDENTITY_METHODS` / `_IDENTITY_NUM` 补映射（位运算需单独的整数折叠器：`const_value` 的数值是 float）。
搁置理由：StateFlow 的 counter 是 `Int` 且当前只有 `it + 1`；位运算恒等属同族预防，无见证。

**R3 · 可达性不判顺序（browser B1p）**
触发条件：有人把 `bumpRevision()` 挪到 `entries` 变更**之前**。
触发方案：加「最后一次变更之后必须还有一次可行 producer 调用」判据（`live_positions` 取位后比偏移）。
搁置理由：「先发信号后写数据」可能是刻意的（提前唤醒观察者）；判红需先确认契约方向，超出本波见证半径。

**R4 · 变更入口锚具名方法（browser B1p）**
触发条件：新增变更拼写（`entries.removeIf { }`、`entries.sortWith(…)` 已在集合内，`entries += x`、`entries.forEach { it.x = … }` 不在）。
触发方案：`ENTRIES_MUTATION_RE` 改为「`entries` 后跟任意 `.<名称>(`」并配合 `value_sources` 判写，或从 `live_list_bindings` 结果集合拼正则。
搁置理由：当前 store 的变更全走具名方法（`add`/`clear`/`removeAll`），扩集合属预测性加固。

**R5 · B2 分支形态会 fail-closed 红（browser B2）**
触发条件：出现 `val lbl = if (flag) stringResource(R.string.k) else fallback`（`value_sources` 对 if-else 两侧都返回）。
触发方案：允许「另一侧也是 stringResource / 另一个 `browser_history_*` 资源」，或把两侧都判为合法候选（保留"至少一个来源是资源"的反向判据）。
搁置理由：方向安全（误红会立刻被人看见并修，不会静默放行），且当前无此写法。

**R6 · 装载对象派生不出时放宽（collection arm2）**
触发条件：新增第三种装载拼写（既不是 `repo.<m>(`，也不是 `entry(`/`instance(`/`group(`）。
触发方案：`LOADER_RE` 改为「绑定右侧含 `?: throw ConfigError.UnknownPath`」派生（更贴语义）。
搁置理由：三处 collection 全部命中现有拼写；放宽方向是「少判一层实参」，不会误红，也不会让 13-4 类缺陷回绿（那类仍被 `return`/尾链判据拦住）。

**R7 · arm0 委派锚在 helper 名（collection arm0）**
触发条件：把 `mutatedGroupCopy` 改名或换成另一个等价 helper。
触发方案：从 collections 目录派生「入参 `(T, (T)->Unit)`、返回新对象的 pure helper」集合，用集合而非单个名字。
搁置理由：改名会让 `extract_helper_body` 返回 None → 门报 `definition not found` 红（fail-closed），不会静默放行。

**R8 · 表达式体 mutate 不在签名语法内（collection）**
触发条件：`fun mutate(...) = repo.updateEntry(apply(e))`（`{` 体改成 `=` 体）。
触发方案：`extract_mutate_bodies` 兼容 `=` 体（用共享 `function_body` 的 expr 形态）。
搁置理由：现有三处均为块体；改到表达式体会被「声明数 ≠ 分析数」当场 fail-closed 拦住。

**R9 · 臂数不进 CI 日志（两门）**
触发条件：主会话希望在 CI 里见证「臂被删掉」。
触发方案：`test_scan.py` 的注册标记改成带臂数后缀（本支已输出 `…=PASS (42 arms)` / `…=PASS (22 arms)`）；冻结面本支不动。
搁置理由：任务书 §1.2 冻结 `test_scan.py`，注册交主会话统一处理。

---

## 7. `test_scan.py` 注册项（交主会话）

两门**已在册**，标记串前缀不变，故本支**不需要**新增注册：

| 脚本 | 现注册标记（`test_scan.py`） | 本支实际输出 |
|---|---|---|
| `browser_history_snapshot_check` | `BROWSER_HISTORY_SNAPSHOT_SELFTEST=PASS` | `BROWSER_HISTORY_SNAPSHOT_SELFTEST=PASS (42 arms)` |
| `collection_copy_on_mutate_check` | `COLLECTION_COPY_ON_MUTATE_SELFTEST=PASS` | `COLLECTION_COPY_ON_MUTATE_SELFTEST=PASS (22 arms)` |

（断言是 `marker in out`，带后缀仍成立。若主会话要让**臂数**进 CI 日志，可把两行 marker 改成带 `(N arms)` 的完整串 —— 这是 R9 的选择项，不是必须。）

承载它们的夹具函数是 `test_da1022_gates`（本地分块跑：+22 pass / +0 fail，含两门 `selftest has teeth` + `real tree green`）。

---

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch7/wt7/t09

# 共享原语自证
python3 scripts/scan/_kotlin_lexer.py --selftest
python3 scripts/scan/_kotlin_ast.py --selftest
python3 scripts/scan/_kotlin_valueflow.py --selftest

# 两门自测 + 真树
python3 scripts/scan/browser_history_snapshot_check.py --selftest
python3 scripts/scan/browser_history_snapshot_check.py .
python3 scripts/scan/collection_copy_on_mutate_check.py --selftest
python3 scripts/scan/collection_copy_on_mutate_check.py .

# 反攻（12 条见证）
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-A.json \
  --repo . --mode full --only browser_history_snapshot_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch7/probe/t09-browser_history_snapshot_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-B.json \
  --repo . --mode full --only collection_copy_on_mutate_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch7/probe/t09-collection_copy_on_mutate_check.json

# 全量
bash scripts/scan/scan.sh
python3 /var/minis/shared/work/meta-audit-v5/batch7/probe/t09_testscan_chunks.py 12   # 分块跑 4 次

# CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
  --workflow build-apk.yml --ref fix/b7-browserhistory-collectioncopy
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs \
  --branch fix/b7-browserhistory-collectioncopy
```
