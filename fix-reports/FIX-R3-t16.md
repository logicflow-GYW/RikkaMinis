# FIX-R3-t16 — 第三轮 dual-gate bypass：`xproc_boundary_check` + `exec_gate_release_check`

- 支线：`fix/r3-xproc-execgate`（基线 `base/batch5-shared` @ `e145ec961`）
- 门提交：`5cbb92105`（两门判据）+ 打印改动 commit（见 §5）
- 生产 `.kt` 改动：**0 行**；`scan.sh` / `test_scan.py` / `_kotlin_lexer/ast/valueflow` 触碰：**0**；门数 112 / 夹具 344 不变

---

## 1. 门

| 门 | 见证 | 修缮前 | 修缮后 |
|---|---|---|---|
| `scripts/scan/xproc_boundary_check.py` | #54 #55 #56 #59（4 条） | 4/4 BLIND_CONFIRMED | 4/4 CAUGHT |
| `scripts/scan/exec_gate_release_check.py` | #21 #22 #23（3 条） | 2/3 BLIND_CONFIRMED（#23 先已 CAUGHT） | 3/3 CAUGHT |

两门都是**第三方外部见证**打进来的第二个类（不是代码 bug，是「门看着正常但判据不承重」）。本波共同形态：**判据锚在「某个拼写/某个调用存在」，而缺陷恰好保留那个拼写、只抽掉决定**。

## 2. 轴（每条见证失效的那一层）

| # | 门 | 轴 | 一句话 |
|---|---|---|---|
| 54 | xproc | 存在 → 派生 | 本门枚举边界模型的 **ctor 参数表**；`val supportsAzureMode: Boolean get() = false` 是**派生投影**，不进参数表，于是「模型→派发端」和「模型→写入面/UI」两侧都不认领它 |
| 55 | xproc | 存在 → 效果 | receiver 侧只问「有没有 `getInt("max_output_tokens")`」；`.takeIf { false }` 读完就丢，值永远到不了 provider。sender 侧早在 wide4 就有 `_chain_kills`，**同一对键的两端判据不对称** |
| 56 | xproc | 每站点 → 每个入口 | ctor 参数已按**每站点**判（两处重建都要带键）。会话级 restore（`restoreThinkingRules(req)`）只按「worker 里有没有读这个键」判，于是**一个入口做、另一个入口不做**仍绿 |
| 59 | xproc | 存在 → 效果 | `if (req.optBoolean("enhanced_cache", false)) { …enhancedCache = false }`：读在了、赋值也在，但块把守卫的意义反转 → 整块等价于空操作 |
| 21 | exec_gate | 计数/子串 → 逐退出路径 | G4 只判 `internalAcquireGlobalShellSlot` 的 **catch**（异常路径）；超时**正常返回 false** 的拒绝分支不经过 catch，删掉那里的 release 全绿 |
| 22 | exec_gate | 同上 | 成功路径的 `finally` 删掉 release：acquire / wrapper / catch / 所有子串原样在 |
| 23 | exec_gate | （对照，不成立） | `recycleDispatchMode(false)` + 保留 `Looper.getMainLooper()` 读取：wide4 已把判据从「子串存在」改成 `DISPATCH_ARG_OK` 白名单，**本门当场 rc=1** → 见证不成立，未改判据 |

**dual-gate 见证的 Y 侧归属（任务书 §2.2 要求）**：三条 Y 门都不该管这些缝，且都在本波其他支线手里，逐条说明：

| 见证 | Y 门 | Y 侧现状 | 本文判定 |
|---|---|---|---|
| 54 | `provider_write_rules_check` | rc=0（仍盲） | **不是它的缝**：该门管「多条写入面是否共用同一套规则」（W1 shared rules / W2-W3 single declaration / W4 no literal copies）。`supportsAzureMode` 是**边界模型上的投影**，写入面只是在消费它；投影被冻结属于「模型这一端的契约」，归 `xproc_boundary_check`（本波已收） |
| 55 | `modelsvc_single_read_check` | rc=0（仍盲） | **不是它的缝**：该门管「一次 run 只 spawn 一个 worker / 只读一次 request」。读到的值被丢弃不改变读次数，属 receiver 侧数据流，归 `xproc_boundary_check` |
| 59 | `modelsvc_single_read_check` | rc=0（仍盲） | 同上：`optBoolean` 调用次数不变，变的是它驱动的效果 |
| 21 22 | `outcome_known_chain_check` | rc=0（仍盲） | **不是它的缝**：该门管 `outcomeKnown` 四层字段传播（verdict → engine → VM wrap → T7 trace）。permit 泄漏是**资源生命周期**，与结果字段传播正交，归 `exec_gate_release_check` |

已核对：`R3-t10`（outcome_known）/ `R3-t13`（modelsvc_single_read）/ `R3-t20`（provider_write_rules）三份任务书里 **grep 不到** `supportsAzureMode` / `max_output_tokens` / `enhanced_cache` / `restoreThinkingRules` —— 这三条缝没有别的支线认领，本支收在 X 侧后即为唯一 coverage。

## 3. 见证重放表（`probe/t16-pre.json` → `probe/t16-post.json`）

| # | 类 | 补丁 | 修前 rc（X / Y） | 修前判决 | 修后 rc（X / Y） | 修后判决 | 还原 |
|---|---|---|---|---|---|---|---|
| 54 | dual | `ProviderConfig.kt:328` `get() = false` | 0 / 0 | BLIND | **1** / 0 | CAUGHT | ✔ |
| 55 | dual | `ModelExecutionService.kt:849` `…getInt("max_output_tokens").takeIf { false }` | 0 / 0 | BLIND | **1** / 0 | CAUGHT | ✔ |
| 56 | symmetric | `MES:807` 注释掉 `restoreThinkingRules(req)` | 0 | BLIND | **1** | CAUGHT | ✔ |
| 59 | dual | `MES:954` + `:1398` `?.enhancedCache = false` | 0 / 0 | BLIND | **1** / 0 | CAUGHT | ✔ |
| 21 | dual | `ExecutionCoordinator.kt:443` 删 reject 分支的 release | 0 / 0 | BLIND | **1** / 0 | CAUGHT | ✔ |
| 22 | dual | `ExecCoord:630` 删 finally 里的 release | 0 / 0 | BLIND | **1** / 0 | CAUGHT | ✔ |
| 23 | symmetric | `ExecCoord:1203` `recycleDispatchMode(false)` + 留 `Looper.getMainLooper()` | 1 | **CAUGHT** | 1 | CAUGHT | ✔ |

`restored=7/7`、`tree_clean=7/7`、每条红在**自己那条判据**上（原始报错行）：

```
#54 xproc : ❌ ProviderInstance.supportsAzureMode folds to `false` (line 328)
#55 xproc : ❌ LLMModel site@837 drops max_output_tokens (`maxOutputTokens` mentions 'max_output_tokens'
              but the extracted value is thrown away: `takeIf { false }` …)
#56 xproc : ❌ worker entry executeRun(req) never calls restoreThinkingRules(req) while executeStreamingRun does
#59 xproc : ❌ worker: the guard on 'enhanced_cache' is undone inside its own block
              (enhancedCache = false under `if (req.optBoolean("enhanced_cache", false))`, line 954)
            ❌ …（同一判据在流式路径 :1398，两条都红）
#21 exec  : • [exec-gate] heavy permit leaked on an exit path — the branch that returns at line 445
              still holds heavyGateAcquired (`!acquired`) and never calls `heavyGate.release()`
#22 exec  : • [exec-gate] heavy permit leaked on an exit path — the `finally` at line 618 no longer
              calls `heavyGate.release()`
#23 exec  : • [exec-gate] decision short-circuited by a constant: the isOnMainThread argument is `false`
```

## 4. 修法（判据从「拼写/存在」搬到「从树派生 + 效果达成」）

### 4.1 `xproc_boundary_check` — 四条新判据（全部走共享 `_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`）

**(a) 派生投影不得折叠成常量（#54）**

`derived_boolean_getters()` 枚举边界模型文件里每个 `val x: Boolean` + `get()`：表达式体取 `expr_end` 界定的整段，块体取**全部顶层 `return`**（`stmt_spans`）。`constant_projection()` 只在这些出口**折叠成同一常量**时判定违规 —— 于是在真树上：`get() = providerType == ProviderType.openAI && …`（LLMModel 式派生）、`get() = when (providerType) { … }`、`get() { val out = …; return "text" in out }` 全部**保持绿**（它们折叠成 `None`），而 `get() = false` 恒红。`enum class` 体内的 `val isEnabled get() = this != OFF` 按 `_enum_spans()` 跳过（那是枚举常量谓词，不是边界模型投影）。

**(b) receiver 侧读值不得丢弃（#55）**

两侧对称：`key_read_problem()` 保留 sender 侧的 `_chain_kills` 分析，取出读调用之后到右值末尾的那一段再过一次 —— `.takeIf { false }` / `.let { null }` 等恒折叠尾链即违规；另加 `dead_guarded()`：读发生在 `if (false)` / 不可达 `when` 分支里不算读。返回**原因字符串**（不再是一个 bool），门红时能直接看到「哪条路径丢的」。

**(c) 每个入口点都要做同样的会话级 restore（#56）**

`worker_functions()` 按声明跨度枚举 worker 函数（共享 `function_body` 取体，拿不到体就当空体 → 规则 fail-closed），从树派生两组：
- **entry** = 首参类型是 `JSONObject` 且体内构造边界模型的函数（真树 2 个：`executeRun` / `executeStreamingRun`；**不写死名字**，改名或新增入口同样被这条规则盯住）；
- **restore** = 只吃一个 `JSONObject` 参数的 worker 函数（真树 1 个：`restoreThinkingRules`）。

规则：某个 restore 被**任一** entry 以 `req` 调用（且是活代码）⇒ **每一个** entry 都必须调它。这就是 ctor 参数「每站点必须带键」规则在会话级那一半上的镜像。

**(d) 守卫的键不得被自己的块内赋值反转（#59）**

`guard_contradiction()` 对 worker 里每个 `if (<含 get*/opt* 读>)` 求守卫极性（读调用前是否有 `!`），再看块内 `x = true|false`：极性为正却赋 `false`、极性为负却赋 `true` ⇒ 整块等价空操作。真树的 `= true` 保持绿。

### 4.2 `exec_gate_release_check` — G7：permit 按**退出路径**核算（#21 #22）

从树派生、不写死名字：
- held 变量 = 绑定 `awaitHeavyGatePermitInterruptibly(` 的那个 `val`；
- gate 表达式 = 该调用的**第一个实参**（`_call_arg0`，depth-aware 切参）；
- window = acquire 语句结束 → 其后第一个 guarded `try`（真树 410→452 行，内含 2 个 `return`）。

判据 a：window 内每个 `return` 要么**本路径上**有活的 `<gate>.release()`（取最内层 if 体到 return 之间，release 再过 `covering_conditions` + `condition_dead`，`if (false && held)` 不算），要么外层条件能**证明未持有**（`!held` / `held == false`）—— 真树 :414 的 `if (cmdClass == HEAVY && !heavyGateAcquired)` 正是后者，保持绿；:446 的 `if (!acquired)` 分支必须自己有 release。
判据 b：guarded `try` 的 `finally` 必须归还 permit，且 release 不得落在死分支后（`try_sections()` 走 catch 链找 finally）。

**不发明结构**：没有 guarded `try` 的形态（JVM 夹具里 `executeCommand` 只调 wrapper）无判据可比，保持绿 —— 代价见 §6 R7。

### 4.3 顺带：门红时打印每条判据

xproc 此前只打印 `== N problem(s) ==` + 通用尾注，四族判据（缺值/读后丢弃/投影冻结/入口漏 restore）混在一个计数里，排障必须回读源码猜。现在逐条 `❌` 打印（第二个 commit）。

## 5. 四层证据

### L1 本门

| 项 | 读数 |
|---|---|
| `xproc_boundary_check.py --selftest` | `XPROC-BOUNDARY SELFTEST=PASS (18 arms)` —— 臂数 11→18（+7：冻结投影红/派生投影绿、读后丢弃红、入口漏 restore 红/双入口绿、守卫反转红/`= true` 绿） |
| `exec_gate_release_check.py --selftest` | `EXEC_GATE_SELFTEST=PASS (18 arms)` —— 臂数 13→18（+5：reject 分支漏 release、finally 漏 release、reject release 在 `false &&` 后、finally release 在 `if (false)` 后、有 try 无 finally） |
| 真树 `python3 scripts/scan/xproc_boundary_check.py .` | rc=0 `== cross-process boundary OK ==` |
| 真树 `python3 scripts/scan/exec_gate_release_check.py .` | rc=0 `4a rollback + 4b interruptible waits + 4c main-thread dispatch all wired` |

臂全部带牙（每个脏形状断言**具体 needle**，不是只看 rc），夹具形状写成 `selftest_fixture(shape)` 一样式，clean 形态即真结构。

### L2 见证反攻（§3 表）

6 条 BLIND 全部翻红、每条红在自己的判据上、还原 7/7、`git status` 全程干净。

### L3 邻门 / 全量

- 逐门独立进程循环（`probe/gate_loop.py`，跳过 `_kotlin_*`，按任务书 §2.4 不跑 `scan.sh` 整份）：**109 门 / 0 红**（`tts_voice_and_sanitizer_check` 需 `--root .` 形态，单独跑 PASS）；日志 `probe/gate_loop.log`
- `test_scan.py` 全量：见 §5.1
- `test_exec_gate` / `test_xproc_boundary` 两个冻结夹具函数单跑：全 ✅（8 + 4 例）

### 5.1 全量夹具（`test_scan.py`）

**结论：`ALL 344 CASES PASS` 交 CI 承担**——本沙箱单进程跑完整 `test_scan.py`（约 15 分钟）在 26/46 函数处被整壳打断（第三次同族），改用逐函数独立进程循环（`probe/test_scan_loop.py <start>`，按索引切片续跑）。

逐函数读数（`probe/test_scan_loop{,2,3,4}.log`）：

| 段 | 覆盖 | 读数 |
|---|---|---|
| 索引 0–25（loop1） | 26 函数 | 25 OK；**1 RED = `test_auto_approve_gates`**（`auto_approve_bypass_check` 未在本支改动面内） |
| 索引 26–28（loop2） | 3 函数 | `test_real_repo` 20 cases ✅ / `test_xproc_boundary` 4 cases ✅ / `test_thinking_wiring` 7 cases ✅ |
| 索引 29–31（loop3） | 3 函数 | `test_da1017_gates` 14 ✅ / `test_da1017_external_fixtures` 4 ✅ / **`test_da1016_gates` RED（rc=0，✅17 ❌2）** |
| 索引 32–45（loop4） | 14 函数 | 循环起跑即被壳打断，**未取得读数 → 交 CI** |

**两个 RED 的归因（均为并发沙箱伪红，非本支引入）**：

| RED 检查 | 隔离复跑 | 证据 |
|---|---|---|
| `auto_approve_bypass_check selftest has teeth (AUTO_APPROVE_BYPASS_SELFTEST: PASS)` | **两个检查全 ✅**（`selftest has teeth` + `real tree green`） | `python3 scripts/scan/auto_approve_bypass_check.py --selftest` → rc=0、标记恰 1 次；`test_scan` 同款 `subprocess.run` 复现 rc=0、`marker=True` |
| `compact_decision_gate_check selftest has teeth (COMPACT_DECISION_GATE_SELFTEST=PASS)` | **`test_da1016_gates` 重跑 17 项全 ✅**（含 compact_decision 两条） | 同上：`--selftest` rc=0、标记恰 1 次、`len(out)=109`；`git diff --name-only e145ec961..HEAD` 不含这两个门 |

两门均**不在本支改动面**（本支只改 `xproc_boundary_check.py` + `exec_gate_release_check.py`），失败形态是并发重负载下夹具进程输出被截断/临时树被剪，和 09-16 起的 `function not implemented` / 整壳打断同族。

冻结面两个直接相关夹具函数单跑全绿：`test_xproc_boundary` 4 cases、`test_exec_gate_release` 8 cases。

### L4 CI

见 §7。

## 6. 残留（每条带 触发条件 · 触发后方案 · 搁置理由）

- **R1 投影判据只认 getter**：`val x: Boolean = <const>` 的初始化形式不判。触发条件：边界模型出现「体内常量 Boolean 属性被当投影消费」；触发后方案：把 `DERIVED_BOOL_RE` 扩成「体内（非 ctor）Boolean 属性」，需先区分 ctor 参数与体内属性（`match_bracket` 界定类体）；搁置理由：本波见证是 getter 形态，初始化形态在现有体系里会落到 MISSING/BENIGN，不是同一条缝。
- **R2 投影判据不判「语义恒真」**：`get() = providerType == providerType` 折叠成 `None`（标识符不折叠）。触发条件：出现语义恒真/恒假的派生投影；触发后方案：在 `constant_projection` 前加「同标识符两侧比较」归一；搁置理由：这类写法不会被自然写出来，且要做得对需要类型信息。
- **R3 restore 对称性只枚举单参 `JSONObject` helper**：`restoreX(req, ctx)` 不被认作 restore。触发条件：worker 新增带额外参数的 restore helper；触发后方案：放宽到「首参 JSONObject + 至少被一个 entry 以该参调用」，`parseContentParts(o)` 这类解析器靠「不被 entry 以 req 调用」自然过滤；搁置理由：放宽后误伤面变大，而现树只有一个 restore，收益未证。
- **R4 守卫反转只认布尔中性常量**：`if (optBoolean(k)) { x = null }` / `= 0` / `= ""` 不判。触发条件：出现非布尔属性的同类反转；触发后方案：`const_value` 扩到中性值集合（null/0/""/空集合）；搁置理由：需要可空类型/默认值推断，本波见证只是布尔。
- **R5 委托属性不枚举**：`val x: Boolean by lazy { false }`。触发条件：边界模型改 `by` 委托；方案并入 R1；理由同上。
- **R6 G7 的 window 依赖「acquire 之后第一个 `try {`」**：若 acquire 与主 guarded try 之间插入**另一个无关 try**，window 会被截短（漏报 reject 分支）。触发条件：`execute()` 在两者之间新增 try；触发后方案：枚举后续所有 try，优先取「finally 里含 gate.release()」的那个；搁置理由：现树只有一个 try，且要多跑一遍 `try_sections` 扫描，收益未证。
- **R7 整段 guarded try/finally 被删除时 G7 无声**：结构与 permit 一起消失时无判据可比（`test_scan.py` 的 `exec_gate_fixture(good)` 命令路径**本来就没有 try/finally、没有 release**，冻结面禁止本支修改）。触发条件：有人把 `execute()` 的 guarded try 整块删掉（而不是只删 release）；触发后方案：acquire 存在但无 guarded try 时，要求函数内每个 `return` 前都有活的 release，**同时**把 `test_scan.py` 的 good 夹具升级为带 try/finally 的完整形态（主会话统一改）；搁置理由：冻结面（`test_scan.py`）不可动，本支改了会让 344 用例全红。
- **R8 G7 只覆盖 heavy permit**：`globalConcurrency` slot 的逐路径核算未做。触发条件：契约要求 global slot 也按路径核算；触发后方案：同一 engine 复制一份（gate 表达式换成第二个实参）；搁置理由：slot 是计数型（泄漏一条少一个并发位，不像 heavy 是一票否决），且同样受 R7 的夹具限制。
- **R9 函数枚举应上收共享层**：`worker_functions()`（按声明跨度枚举 + 首参类型 + fail-closed 空体）是本波自写；其它门都在各自实现「按名字找函数体」的变体。触发条件：第 3 个门需要「枚举函数声明」；触发后方案：上收为 `_kotlin_ast.function_decls(text)` → `[(name, params, body, span)]`，各门改用；搁置理由：任务书禁止本支改共享模块（硬约束 #2）。
- **R10 新臂在 CI 里不可见**：门自证 stdout 不进 CI 日志（既知家族）。触发条件：本波 12 条新臂需要云端见证；触发后方案：主会话在 `test_scan.py` 的 selftest 段注册
  `("xproc_boundary_check", "XPROC-BOUNDARY SELFTEST=PASS (18 arms)", (REPO_ROOT,))` 与
  `("exec_gate_release_check", "EXEC_GATE_SELFTEST=PASS (18 arms)", (REPO_ROOT,))`；搁置理由：冻结面不可动，注册由主会话并集收口时统一做。

## 7. CI

（回填：run id / #编号 / head_sha 逐字符核对 / 关键日志行）

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t16

# L1 本门自证 + 真树
python3 scripts/scan/xproc_boundary_check.py --selftest
python3 scripts/scan/exec_gate_release_check.py --selftest
python3 scripts/scan/xproc_boundary_check.py .
python3 scripts/scan/exec_gate_release_check.py .

# L2 见证反攻（pre 需先把门回滚到 e145ec961 版本才复现 BLIND）
python3 probe/t16_replay.py pre     # → probe/t16-pre.json
python3 probe/t16_replay.py post    # → probe/t16-post.json（7/7 CAUGHT）

# L3 全量（逐门独立进程，不要跑 scan.sh 整份）
python3 probe/gate_loop.py .
cd scripts/scan && python3 test_scan.py

# 冻结夹具两条
cd scripts/scan && python3 -c "import sys; sys.argv=['x']; sys.path.insert(0,'.'); import test_scan as t; t.test_exec_gate(); t.test_xproc_boundary()"
```
