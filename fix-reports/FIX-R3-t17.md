# FIX-R3-t17 — 第三轮（dual-gate bypass）收口：`auto_approve_bypass_check` + `offload_permission_reset_sync_check` + `debug_leak_guard`

- 分支：`fix/r3-approve-perm-leak`，基线 `main` @ `f0f14d46d`
- 变动面：`scripts/scan/auto_approve_bypass_check.py`、`scripts/scan/offload_permission_reset_sync_check.py`、`scripts/scan/debug_leak_guard.py` + 本报告（`src/**` 生产 `.kt` **0 行**；`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰；门 112 / 夹具 344 不变）
- 结论：**3 门 / 9 条见证**，修前 **9/9 全部重放为 BLIND_CONFIRMED**，修后 **9/9 全部变红（rc=1，红在本支新判据上）**，`restored=True`（`git status --porcelain src/` 全程为空）。3 门真树 rc=0，自检臂 20 arms / 29 arms / 46 checks（7+4+16 夹具 + APK 模式）。

---

## 1. 门与轴

| 门 | 见证 | 失效轴（本支判定） | 新判据 |
|---|---|---|---|
| `auto_approve_bypass_check` | #3 | **consumer-gap**（overlay 被搬到决策消费点，policy 体内的 C3/C5 仍是绿的） | C6 |
| | #4 | **input-trust**（策略的输入 = `risk` 标签，无人判它是否被降级） | C7 |
| `offload_permission_reset_sync_check` | #30 | **write/read 键漂移**（各自完整，中间的"同一把键"无人判） | 5 |
| | #31 | **决定侧极性**（门只判"清空"，不判"判据朝向"） | 6 |
| | #32 | **写入侧对称缺口**（清空侧严查，Response 写入侧不查） | 7 |
| `debug_leak_guard` | #15 | **实参丢弃**（只判调用形状，不判 offset/limit 的取值来源） | api26 判据 6 |
| | #16 | **扫描面不对称**（DEX 侧标记清单 ≠ 源码侧扫描面） | 源码扫描 (4) |
| | #17 | **arm 盲区**（守卫只按"语句前缀提到 flag"判，`else` 支被当受保护） | `debug_guarded` 重写 |
| | #5 | **分发面黑洞**（`debug/` 整包被跳过，RPC 分发器自身的 `debug.*` 分支无人判） | `scan_debug_dispatch` |

**共同形态**（与前三批同源，但这一批全部落在"两门之间"）：每一条都不是"门里的正则写窄了"，而是**两份各自正确的判据之间、那个"谁都不认领"的谓词**。所以修法一律是"从树/数据流派生一个新的决定点"，不是补拼写。

## 2. 见证重放（修前 / 修后）

`probe/t17-pre.json`（修前）、`probe/t17-post.json`（修后）、补丁 `probe/t17-w0*.patch`、脚本 `probe/t17-replay.py`。

| # | kind | 门 | 修前 rc | 判决 | 修后 rc（同序） | 判决 |
|---|---|---|---|---|---|---|
| w03 | dual-gate | auto_approve / offload | 0 / 0 | **BLIND** | **1** / 0 | 实锤（owner=auto_approve C6） |
| w04 | dual-gate | auto_approve / backup_remap_rollup | 0 / 0 | **BLIND** | **1** / 0 | 实锤（owner=auto_approve C7） |
| w05 | dual-gate | auto_approve / debug_leak | 0 / 0 | **BLIND** | 0 / **1** | 实锤（owner=debug_leak 分发判据） |
| w15 | dual-gate | debug_leak / auto_approve | 0 / 0 | **BLIND** | **1** / 0 | 实锤（owner=debug_leak api26） |
| w16 | symmetric | debug_leak | 0 | **BLIND** | **1** | 实锤 |
| w17 | legacy-axis | debug_leak | 0 | **BLIND** | **1** | 实锤 |
| w30 | dual-gate | offload / auto_approve | 0 / 0 | **BLIND** | **1** / 0 | 实锤（owner=offload 判据 5） |
| w31 | dual-gate | offload / auto_approve | 0 / 0 | **BLIND** | **1** / 0 | 实锤（owner=offload 判据 6） |
| w32 | symmetric | offload | 0 | **BLIND** | **1** | 实锤 |

**9/9 BLIND_CONFIRMED，0 CAUGHT，0 NOT_REPRODUCIBLE**（与主会话 `auto-patch.json` 的 3 条 BLIND 一致，另 6 条散文补丁由本支手工构造：`w05` 删守卫块、`w16` 删 `if (BuildConfig.DEBUG)` 包裹、`w17` 改单行 `if … Unit else <call>`、`w15` 去 `coerceIn` + 传 `0L`）。每条补丁 `restored=True`（sha256 前后一致 + `git status --porcelain src/` 为空）。

**w04 的一个事实**：外部见证写的是 `risk = ConfigRisk.SAFE`，而 `ConfigRisk` 只有 `NORMAL/SENSITIVE/DESTRUCTIVE`（`ConfigSchema.kt:97`）——那个补丁本身不可编译，但它是**静态门**的合法见证（门不类型检查）。所以 C7 同时钉死两件事：真实可编译形态 `NORMAL` 降级 **且** 未知 risk 记号（fail-closed）。

## 3. 修法（逐条）

### 3.1 `auto_approve_bypass_check.py`

**C6 — 决策消费点必须是"解析出来的 level"，不能再包一层 overlay**

`checkPermission` 是 `getLevel()` 的唯一消费点，而本门 C3/C5 只锚 `getLevel` 体内、C4 只锚 registry 字面量、`offload_permission_reset_sync_check` 锚存储/刷新/清空——**两者之间那句 `val level = if (AutoApproveStore.isEnabled) BYPASS else getLevel(toolName)` 无人认领**。见证后果：普通「免确认模式」直接短路掉用户显式设的 `NOT_ALLOWED`，且绕开了 policy 里 `privilegedToolsEnabled` 这个子开关。

判据（三条同时成立）：
- (a) `checkPermission` 体内**不得出现** `AutoApproveStore`（overlay 的唯一合法家是 `OffloadPermissionLevelPolicy`）；
- (b) 绑定 `getLevel(...)` 的那个局部，其**每一条值来源**都必须是 `getLevel(...)` 调用（`value_flows_from(..., require_all=True)`）——`if (cond) BYPASS else getLevel()` 的 then 支没有 getLevel ⇒ 红；
- (c) 该局部必须被**可达、非常量**的 `if` 条件或 `when` 主语消费，且它解析出来之前不得有 `return`/`throw`。

**C7 — `risk` 标签是策略的输入，不能被静默降级**

`ConfigBridge.performWriteBatch` 把 `field.risk` 喂给 `ConfigAutoApprovePolicy.shouldSkip`；C4 只禁 `ConfigBuiltins` 里出现 `auto-approve` 字样，**风险等级本身没人判**。判据两条轨：
- **钉住轨**（单调，只防向下）：`PINNED_RISK`（13 个 SENSITIVE + 1 个 DESTRUCTIVE，2026-10-26 从树冻结）中的每个 path 必须仍注册、且等级不得低于钉住值；**未知 risk 记号**（如见证里的 `SAFE`）= 红；
- **派生轨**（覆盖将来）：任何注册项若 `writer` 里出现 `repo.<X>GroupId =`（改写"某个界面用哪个模型组"的路由指针），必须 ≥ SENSITIVE——不写死 path，写死语义。
- 工厂型注册（`fontScaleField(path = path, …)`，path 由调用方给）不参与钉住轨，只在 `main()` 打一行 `[note]`，登记为残留 R1。

### 3.2 `offload_permission_reset_sync_check.py`

**5 — 写进去的键必须就是读出来的键**

`setLevel` 写、`getLevel` 读、`resetAll` 删，三处各自都被判过（本门 1/2 与 `auto_approve` C3），**"是不是同一把键"没人判**。判据：从两个视图（code view 判结构、literal-kept view 读字面量）取出三处的 key **模板**，把插值归一（`"level_$toolName"` ≡ `"level_${tool.toolName}"`）后比较：写集必须等于读集，删集必须覆盖读集，且每个 key 必须含插值（常量 key = 所有工具共用一级 ⇒ 红）。**比的是"一致"，不是拼写**——三处一起改名仍然绿。

**6 — 会话判决的极性**

门原本只判 `sessionGrants/sessionDenials` 被**清空**（清空侧），不判 `checkPermission` 里 `if (toolName in grants) return true` 这个消费点（决定侧）。判据：`expand()` 之后对每个涉及 `grants`/`denials` 的 `if` 块，条件必须是正成员测试（`x in store` 或 `store.contains(x)`），then 支必须恰是 `return true`（grants）/ `return false`（denials）；两类测试都必须存在（缺失 = 决定被搬走，fail-closed）。

**7 — 弹窗答复按用户选的方向落地**

`Response.ALLOW_SESSION -> { grants.add(x); true }`、`Response.DENY_SESSION -> { denials.add(x); false }`。判据从 `when` 的**分支标签**（不是主语拼写）定位这两个臂，要求登记到正确的集合、不登记到相反的集合、尾表达式分别 `true`/`false`；缺臂 = 红（anchor drift）。

**硬约束兑现**：自写的 `_match_paren` / `_match_brace` / `_stmt_span` 三个状态机全部删除，改走 `_ast.match_bracket` / `_ast._branch_extent`（长度保持，下游偏移不变），模块头补 5/6/7 三条说明。

### 3.3 `debug_leak_guard.py`

**#17 — 守卫必须覆盖"引用所在的那个支"**

`debug_guarded` 原来只看"引用前缀里有没有 `if (BuildConfig.DEBUG)`"，于是 `if (BuildConfig.DEBUG) Unit else DebugServer(this).start()` 里的 **else 支（只在 release 跑）**被判成受保护。改为基于共享 `if_blocks` 的**定位判据**：
- 用 `_arm_of()` 枚举真正包住引用的每个 `if` 支；
- **then 支**：`guard_decides(cond)`（该条件在 release 为假）或常量假 ⇒ 该支在 release 死；
- **else 支**：`guard_release_true(cond)`（该条件在 release 为真，如 `!BuildConfig.DEBUG`）或常量真 ⇒ else 在 release 死；
- 与 flag 无关的外层支（如 `if (x) … else if (DEBUG) …`）是**中性**，不否决——决定性的那支可能更深；
- `_split_inline_else()` 补上 `if_blocks` 的已知缺口：单行 `if (c) A else B` 的 `else` 落在 `_branch_extent` 的 then 区间内（`_branch_extent` 只按深度 0 换行截断），把臂界重算。

`dead_guarded`（常量死支 + `when` 死臂）同源重写；`_paren_end`/`_brace_end` 两个自写配平器删除，改走 `_match_bracket`。

**#16 — 源码扫描面 = DEX 标记清单**

`APK_MARKERS` 明列 `DebugOffloadHandler` / `minis-debug` 等绝不许进 release，但**源码侧只扫 `com.rikkaminis.app.debug.` 包名**，同在 `src/main` 的 `sandbox/offload/DebugOffloadHandler` 是盲区。新增 `SOURCE_DEBUG_SYMBOLS`（`APK_MARKERS` 去掉裸端口 `5321`，源码里那个数字太容易撞），对每个生产文件做符号扫描，并沿用同一套 `report()`（审计白名单 / `debug-ok:` 逃生 / live 守卫）；同文件去重（`bad_lines`）避免与包名引用、import 引用重复报；符号自身文件（`DebugOffloadHandler.kt` 及其 HELP 文本）按"R8 与该符号一起消失"的理由跳过。

**#15 — 实参必须是调用方自己的 offset 与一个被钳住的 limit**

判据从"调用形状"扩到"实参取值"：`readFileSegment(file, offset, limit)` 的
- offset 实参不得是**常量**（`0L` = 分页永远读文件头）；
- limit 实参必须**在某处被钳住**（表达式本身、持有它的局部、或 helper 内部对自己的 `limit` 参数 `coerceIn`/`coerceAtMost`）；
- helper 的 `seek(...)` 必须最终用到**它的 offset 参数**（值链一跳，`val start = offset.coerceIn(...); seek(start)` 也认）。

**#5 — 分发器里"特权 `debug.*` 分支"必须在 release 不可达**

`scan_source` 按设计跳过 `debug/` 整包，于是 RPC 分发器自己的分支无人判：删掉 `debug.minisConfig.exec` 里的 `if (!BuildConfig.DEBUG) throw` 后，`handleMinisConfigExec`（`skipConfirmation = true` 的配置写）就变成 release 可执行，而两门全绿。新增 `scan_debug_dispatch()`：
- **钉住轨**：4 个自带 release 拒绝的 `debug.*` 方法（shizuku/modelUse/sessions/minisConfig `.exec`）丢了守卫 = 红（单调：新增方法不需要有守卫，已有的丢了是缺陷）；
- **派生轨**：任何分支的 handler（一跳）里出现 `skipConfirmation = true` 的配置写 ⇒ 必须有 release 拒绝，**与名字无关**（改名换姓也抓）；
- 一条都没有 = anchor drift 红。

## 4. 四层证据

1. **本门**：三支真树 `rc=0`（`auto_approve_bypass_check: OK` / `offload_permission_reset_sync_check OK` / 两行 ✅），`--selftest` 全臂带牙：
   - `AUTO_APPROVE_BYPASS_SELFTEST: PASS (20 arms, 18 violating)`（原 14 arms；新增 `checkperm_auto_approve_overlay`、`checkperm_level_discarded`、`risk_voice_input_downgraded`、`risk_unknown_token`、`risk_pinned_path_missing`、`risk_new_group_family_safe`，各期望恰 1 条违规）
   - `OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST_ARMS=29` + `OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS`（原 23 arms；新增 V10/V10b/V11/V11b/V12/V12b，含两条 clean 对照）
   - `DEBUG_SOURCE_SELFTEST=PASS (7 fixtures)` / `DEBUG_DISPATCH_SELFTEST=PASS (4 fixtures)` / `DEBUG_API26_SELFTEST=PASS (16 fixtures)`，全门 46 项 ✅（原 5 / 无 / 11）
2. **邻门 / 全量门**：逐门独立进程循环，**111 个不同门脚本 + `i18n_check` 全绿**（`probe/t17-fullscan.log`：101 门落盘 + 剩余 10 门直接跑，`RED` 0；`scan.sh` 的 112 是编号而非 112 个不同脚本，`liveness_audit.py` 只在注释里出现）。**不跑 `scan.sh` 整份**——本窗口沙箱会 `OSError: [Errno 38]`，见 R9。
3. **夹具全量**：`test_scan.py` 本地跑到第 156 行被沙箱杀死（`readonly_mount_write_guard` 之后，`❌` 0），**全量 344 条证据由 CI 承担**（§6：`ALL 344 CASES PASS` 恰 1）；本支三门在 `test_scan` 里的断言（`test_debug_leak` / `test_da1024_gates` 的 offload 行 / `test_auto_approve_gates` 的 auto_approve 行）已在沙箱内单独跑通前者两项、后一项由 CI 日志里两条 marker 各恰 1 佐证。
4. **CI**：见 §6（run `40316386231` / #2744 / success / head_sha 三方一致）。

## 5. 修后反攻（贴红行）

见 `probe/t17-post.json` 的 `red_lines`。摘录（每条都红在本支新判据上，不是撞别的判据）：

```
w03 auto_approve rc=1  C6: checkPermission consults AutoApproveStore (line 611) — the auto-approve
                        overlay must stay inside OffloadPermissionLevelPolicy, where the
                        privileged-tools sub-flag is honoured
w04 auto_approve rc=1  C7: `defaults.voiceInputGroup` declares `ConfigRisk.SAFE`, which is not a risk
                        level …; declares no readable risk (pinned minimum SENSITIVE)
w05 debug_leak   rc=1  …/DebugRPCHandler.kt:191 `debug.minisConfig.exec` is dispatchable in a RELEASE
                        build (it is a pinned release-guarded debug method; its handler writes config
                        with `skipConfirmation = true`) — no `if (!BuildConfig.DEBUG) throw` refuses the arm
w15 debug_leak   rc=1  …/DebugRPCHandler.kt:413 handleReadFile() reads from a CONSTANT offset `0L`
w16 debug_leak   rc=1  …/MinisApp.kt:710 audited file, but … "minis-debug" [debug-only symbol]
w17 debug_leak   rc=1  …/MinisApp.kt:1102 … DebugServer(this).start() [debug-package reference]
w30 offload      rc=1  setLevel() writes ['level_override_$'] but getLevel() reads ['level_$'] …
w31 offload      rc=1  checkPermission()'s grants test is INVERTED (`toolName !in grants`) …
w32 offload      rc=1  checkPermission()'s `Response.DENY_SESSION ->` arm never records the verdict in
                        `denials` and records the verdict in the OPPOSITE store `grants` …
```

### 5.1 dual 见证的归属矩阵（§2.3 要求）

一条缝一个 owner，不做双写（双写 = 同一条判据在两个门里各自腐烂）：

| # | 缝在哪 | owner | 为什么另一门不该管 |
|---|---|---|---|
| w03 | `checkPermission` 里的 overlay | `auto_approve` C6 | `offload` 判的是"改动能到达存储/缓存/UI"，不是"overlay 该住在 policy 里"；C6 的 (a)(b)(c) 已经是这条缝的完整谓词 |
| w04 | `risk` 标签 | `auto_approve` C7 | `backup_remap_rollup` 判的是备份 round-trip（注册/reader 是否还在），风险分级不在其合同内 |
| w05 | `debug.*` 分支的 release 守卫 | `debug_leak` `scan_debug_dispatch` | 写路径本身（`skipConfirmation = true`）是设计如此，`auto_approve` 的 policy 未被改动；缺陷是"debug 面泄漏到 release"，那是 `debug_leak` 的合同 |
| w15 | `handleReadFile` 的 offset/limit | `debug_leak` api26 判据 | 读偏移算术与 auto-approve 无关 |
| w30/w31 | 存储键 / 会话判决极性 | `offload` 5/6 | `auto_approve` 锚 `getLevel` 的**解析**与消费点**来源**；键名一致性与 `in`/`!in` 极性属会话缓存语义 |

## 6. CI

- 分支 `fix/r3-approve-perm-leak` → 门提交 **`2ccc8133a`**；分支 push 不触发 → 手动 `workflow_dispatch`。
- run **`40316386231`** / **#2744** / job `121549926145` / **attempt 1** / **`success`**（11:37:02Z → 12:02:10Z，25m08s）
- **`head_sha` = `2ccc8133a7b349480ccf61c5996b7805dec63b9f`，与本地 HEAD 逐字符一致**（防并发假绿）
- 26 步，唯一 `skipped` = `Publish to Releases`（main 门控，非 main 分支按设计跳过）
- 日志 `probe/t17-ci.log`：1,486,720 B / **NUL 0** / 17,853 行，逐条核：
  - `Summary: 112 passed, 0 failed` **恰 1**（另有 6 行 `Summary: N passed` 是子套件：25/16/37/15/25/10）
  - `✅ ALL 344 CASES PASS` **恰 1**
  - 门头 `[N/112]` **112 个互异**（1…112）
  - `^\s*e: ` **0 行**（Kotlin 编译零错误）
  - `❌` **0 行**
  - 6 行含 `FAILED` —— 逐条确认全是 **JUnit 用例名**（`…produces FAILED without calling provid…` / `…reason PERSISTENCE_FAILED…` / `…phase FAILED maps to Failed PASSED` 等），不是失败读数
  - 三门标记：`AUTO_APPROVE_BYPASS_SELFTEST: PASS` 恰 1、`OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS` 恰 1（即 `test_scan` 的「自检带牙 + 真树绿」两项断言过）
- **已知局限（存量问题，非本支引入）**：门自己的 `--selftest` 逐臂 stdout **不进 CI 日志**（`test_scan` 只断言 marker，不回显），所以 `OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST_ARMS=29` / `DEBUG_{SOURCE,DISPATCH,API26}_SELFTEST=PASS` 在日志里 0 命中——臂数只能由本报告承载（登记为 R10）。
- 停止节点：**分支 CI 绿**，未合并 main（全支含门判据 = 有验证面，合并起手权在用户）。

## 7. 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

**R1 · C7 工厂型注册逃出钉住轨。** `fontScaleField(path = path, …)` 的 path 来自调用方，钉住表无法匹配（`main()` 打 `[note]`，不红）。/
*触发条件*：出现一个供"模型路由/凭据"类字段使用的工厂（现仅字体缩放，`risk = NORMAL` 正确）。/
*触发后方案*：解析工厂调用点，把实参里的 path 字面量并进注册表，再跑钉住轨。/
*搁置理由*：当前零命中，且工厂内的派生轨（`*GroupId` 绑定必须 SENSITIVE）已生效。

**R2 · C7 钉住表是快照。** 一个**新增**的敏感字段若注册成 `NORMAL`，只要 writer 不碰 `*GroupId` 就过门。/
*触发条件*：新增字段改写模型路由/凭据指针，而不仅是组绑定。/
*触发后方案*：把"族"从 `*GroupId` 扩成由 `ProviderRepository` 的属性族派生（`*ModelId` / `*Credential*` / `agentLoop*`）。/
*搁置理由*：族分类需要一次设计拍板，且目录现状已被 13 条钉住覆盖。

**R3 · offload 判据 5 的插值归一不看变量身份。** `"level_$toolName"` 与 `"level_${otherId}"` 归一后相同。/
*触发条件*：写/读两处插值换成不同标识符。/
*触发后方案*：比较插值表达式的**角色**（必须是该函数的 tool 标识参数），或要求两侧变量名一致。/
*搁置理由*：见证形态是 key 前缀漂移；同函数内换变量名在评审里极显眼，且判据 6/7 会因行为错乱独立报红。

**R4 · offload 判据 6 只认直白成员测试。** 若决策被抽成 helper 且 helper 内不是 `x in grants` 直白形态，`expand()` 后仍会在外层 `if (allowed(x))` 上报"不是成员测试"（fail-closed 红）。/
*触发条件*：把 ASK_ONCE 决策重构进 helper。/
*触发后方案*：按"被调用函数体里的成员测试"递归判（`expand` 已有层级），或允许"helper 名 → 成员测试"一跳映射。/
*搁置理由*：当前形态是内联的；重构发生时红一次、由人改判据，比现在放宽更安全。

**R5 · offload 判据 7 只钉两个臂。** `Response` 枚举新增成员（如 `ALLOW_SESSION_FOR_TOOL`）不受判。/
*触发条件*：`Response` 增删成员。/
*触发后方案*：从枚举声明派生全部成员，要求"写 grants 的臂答案 true / 写 denials 的臂答案 false"。/
*搁置理由*：需先决定"未知臂"的默认方向（fail-closed 会误伤合法的 ONCE 类臂）。

**R6 · api26 钳制只认 `coerceIn`/`coerceAtMost`。** 换成 `minOf(limit, CAP)` 或 helper 钳制会被误红（偏移侧同理：`offset.coerceAtLeast` 走值链认，helper 内部钳制则不认）。/
*触发条件*：钳制写法改变。/
*触发后方案*：把可接受的钳制算子做成集合（`minOf`/`coerceAtMost`/`require(...)`+`takeIf`），并允许值链跳进函数体。/
*搁置理由*：现写法唯一；误红的代价是改一行判据，漏判的代价是 release OOM/错位读。

**R7 · `scan_debug_dispatch` 的派生轨只认 `skipConfirmation = true`。** 新增的 `debug.*.exec` 若走"起进程"（`ProcessBuilder`）而不是配置写，只能靠钉住轨（且钉住表里没有它）。/
*触发条件*：新增 `debug.*` 方法且其 handler 起进程/读凭据。/
*触发后方案*：派生族加 `ProcessBuilder(` / `Runtime.getRuntime` / `exec(`（一跳）。/
*搁置理由*：现有 4 个 exec 臂都在钉住表里；新增方法时评审会看到 `debug.` 前缀。

**R8 · 源码符号扫描排除裸端口 `5321`。** 端口数字在源码里撞车率高（`APK_MARKERS` 里保留它是对的——DEX 侧只扫 `string_data`）。/
*触发条件*：端口常量移出 `debug/` 包。/
*触发后方案*：改成扫符号名（`DEBUG_SERVER_PORT`）而不是数值。/
*搁置理由*：现在端口只在 `debug/DebugServer.kt` 里。

**R10（装置）· 门自证 stdout 不进 CI 日志。** 存量问题：`test_scan` 只断言 marker 存在，不回显 `--selftest` 的逐臂输出，所以新臂数（20 / 29 / 32）在 CI 里不可见，只能靠本报告与本地跑承载。/
*触发条件*：需要从 CI 侧证明某条新臂真的跑过（审计/回归时）。/
*触发后方案*：`test_scan` 增加一条"回显门 selftest 的最后一行"的步骤（冻结面改动，需主会话统一收口），或给门加 `--selftest --summary-only` 由 CI step 直接打印。/
*搁置理由*：属冻结面（`test_scan.py`）改动，本支按纪律不碰；已在报告登记，避免下次又"以为 CI 里有见证"。

**R9（环境）· 沙箱 Errno 38 与 shell 层截断。** 本轮多次遇到：`test_scan`/`scan.sh` 整份跑会被杀、`python3 … | tail` 形式的管道会被截断（`exit -1`）、`/tmp/.minis_cwd_*` 创建失败。健康路径：**重定向到文件再读**、逐门独立进程。§4 的全量证据即按此取得。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t17
# 修前/修后反攻（9 条见证，自动打补丁→跑涉及的门→还原→sha256 校验）
git checkout -- src/ && python3 probe/t17-replay.py pre    # → probe/t17-pre.json
git checkout -- src/ && python3 probe/t17-replay.py post   # → probe/t17-post.json
# 三门真树 + 自检
for g in auto_approve_bypass_check offload_permission_reset_sync_check debug_leak_guard; do
  python3 scripts/scan/$g.py . ; python3 scripts/scan/$g.py --selftest ; done
# 全门（逐门独立进程；不要跑 scan.sh 整份）
sh probe/run-all-gates.sh
# 夹具全量（若沙箱允许；否则交 CI）
python3 scripts/scan/test_scan.py | tail -3
```
