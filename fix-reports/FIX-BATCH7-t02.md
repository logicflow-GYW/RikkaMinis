# FIX-BATCH7-t02 — 门见证加固：`skill_restore_session_cleanup_check` + `speech_active_engine_check`

- **分支**：`fix/b7-skillrestore-speech`（基于 `base/batch5-shared` @ `e145ec961` = main `6ac77c6b2` + 共享原语 `_kotlin_valueflow`）
- **门提交**：`8b865532e`（2 文件，+1124 / −720，**生产 `.kt` 0 行**、`res/` 0 行、`scan.sh` / `test_scan.py` / 三个 `_kotlin_*` 共享模块零触碰，门 112 / 夹具 344 不变）
- **见证来源**：`witnesses-B.json`（08 面 `skill_restore_session_cleanup_check` ×6）、`witnesses-A.json`（12 面 `speech_active_engine_check` ×6）
- **停止节点**：分支 CI 绿（§5 / §8）。**未合并 main**（合并起手权在用户）。

---

## §1 缺陷与根因

两门是同一族失效的两个样本：**判据锚「某个拼写 / 某处存在」，而缺陷动的是「值、位置、可达性」**。逐条列 Before 形态（都是能过门的补丁）与旧判据为什么瞎。

### 1.1 `skill_restore_session_cleanup_check`（08 面）

| # | 见证（能过门的缺陷） | 旧判据为什么瞎 |
|---|---|---|
| 08-1 | `resolveImportId(preferredId?.take(0), slugify(name))` | 旧判据证明「这一行里有 `resolveImportId(` + 有 `preferredId` 标识符」，再挂一张五词黑名单（`null/false/!true/""/…`）。`?.take(0)` 两个锚点都在、黑名单也不命中，**但实参已经不是调用方的 id**（空串），`resolveImportId` 于是回落到 `slugify(name)` |
| 08-2 | `_skills.tryEmit(emptyList())` 写在 `synchronized(addLock)` **块外** | 旧判据只认 `_skills.value =` 这一种写法。`MutableStateFlow.tryEmit` 恒成功且落在 `.value` 上，**与 setter 完全等价**，换一个写法就把「发布在锁外」这件事整个藏起来（丢一次 use_count 自增 + 覆盖并发 `add()`） |
| 08-3 | `if (shouldDeleteSessionSkillOverride(globalEnabled, enabled) && false) { DELETE … }` | 旧判据查「函数里有没有这段 DELETE 语句 / 有没有引用共享决策 / 有没有读当前全局值」——三个锚点全在，**分支却永远不执行**：每个与全局默认一致的个人开关从此都攥下一行钉死的 override |
| 08-4 | `runCatching { onSessionDeleted?.invoke("") }` | 旧判据只证明「这个入口调用了 hook」。传常量 id，跨库清理照样跑，**跑在编造的 id 上**：被删会话的 skills/mcp override、PAUSED 徽标、offload 授权全部残留 |
| 08-5 | `_inputText.value = CharArray(0).concatToString()`（写在持久化之前） | 旧判据是一张「五种空串拼写」的形态表（`""` / `String()` / `buildString{}` / `CharArray(0).concatToString()` 中的若干）。见证用另一种构造方式求值出空串，**清空的时机仍在持久化之前**，于是草稿文本一个副本都不剩 |
| 08-6 | `listOf(RELEASE_VM, DELETE_ROW, CLEAR_BADGE).reversed()` | 旧判据从 `listOf(…)` 的**字面实参顺序**里取 `RELEASE_VM` / `DELETE_ROW` 下标比较。`.reversed()` 不改变字面顺序，**只改变运行时执行顺序**：真实执行是「先删行再释放 VM」 |

### 1.2 `speech_active_engine_check`（12 面）

| # | 见证 | 旧判据为什么瞎 |
|---|---|---|
| 12-1 | `activeEngine = engine` 紧跟 `activeEngine = null`，然后 `engine.start(…)` | 旧判据只查「startRecording 里存在一处非 null 的 `activeEngine = engine`」——**写发生过，值没留下**（最后一次写获胜） |
| 12-2 | `stopRecording` 在 elvis stop 之前插 `activeEngine = null` | 旧判据只查「elvis 形态存在」。位置没判：先清位再 stop，elvis 退化成 `currentEngine()` |
| 12-3 | `if (false && _state.value != RecognitionState.IDLE) { cancel/清位/复位 }` | 旧判据查「这个块里有 cancel + 有清位 + 有 IDLE 复位」——**全在，但块死**：录制中切引擎时旧引擎继续持有麦克风 |
| 12-4 | `selectLocale` 回到 `currentEngine()?.cancel()` | 旧判据写在 `stopRecording` 上，`selectLocale` 只查「有没有 cancel 调用」 |
| 12-5 | `cancelRecording`：`if (false) activeEngine?.cancel()` → `activeEngine = null` → elvis cancel | 旧判据 `find(".cancel()") < find("activeEngine = null")`：**首个** `.cancel()` 落在死分支里，顺序判据被一个死 token 骗过 |
| 12-6（对照） | `selectLocale` 把 elvis cancel 移到清位之后 | 旧判据 CAUGHT（唯一的对照臂）——说明这不是「全都瞎」，是「只盯住了两条路径」 |

---

## §2 修法

**阶梯第 6 级：给已有判据换锚点，不新增门、不新增机制。** 停在这一级的可复核句：**「停在第 6 级，因为两门都已有完整的臂结构与报错文案，缺陷全部出在『判据锚的是拼写而不是值/位置/可达性』这一层；共享层（lexer / AST / value-flow）已经提供了合适原语，本次只需把臂的判定换成共享原语上的效果判定。」**

底层词法/常量化/值流一律走共享层（硬约束 #1），本门自写的 `strip_literals` / `mask_keep_offsets` / `brace_block_end` / `balanced_index` / `block_body` / `block_functions` / `code_only` / `decl_slice` / `_a05_balanced` / `lines_of` / `split_top_args` **全部删除**，改由 `_kotlin_lexer.mask_code`、`_kotlin_ast.{function_body, if_blocks, match_bracket, split_top, strip_parens, const_value}`、`_kotlin_valueflow.{call_args, expr_end, path_feasible, guard_feasible, value_sources, peel_discarding, contains_ident, brace_depth}` 承担。

| 见证 | 新判据落点 |
|---|---|
| 08-1 | 实参必须是 **`preferredId` 的惰性包装**（括号 / `!!` / `as T` 之外不许有调用链）。谓词 `_inert_preferred_id()` 把表达式规约后**必须逐字符等于** `preferredId`；`.take(0)` / `.let{}` / `.substring(0,0)` / 任何字面量一律红（fail-closed） |
| 08-2 | `SKILLS_WRITE_RE` 覆盖 `_skills` 的四种写形态（`.value =` / `tryEmit(` / `compareAndSet(` / `emit(`），逐站点判是否落在 `synchronized(addLock)` 的 `match_bracket` 区间内 |
| 08-3 | `if_blocks()` 取「then 块里含 DELETE 语句」的分支 → 条件必须引用共享决策 + **`guard_feasible()`** 为真（`… && false`、`false && …`、`1 == 2` 全折叠） |
| 08-4 | `call_args()` 取实参 → 不得是**常量**（`const_value` 可折即红），且必须**派生自**该函数自己的参数或 `dao.` 派生的局部/循环变量（`_session_id_names`） |
| 08-5 | 判据从「五种拼写」改成「值是否还携带 `text`」：`value_sources()` + `peel_discarding()`（`also`/`apply` 剥接收者、`let`/`run` 看尾表达式、elvis/if-else 看两臂）+ 一跳局部 `val` 解析；只要某次 `_inputText.value = …` 的值**不再携带 `text`**，且位置早于第一处持久化写，即红 |
| 08-6 | `_plan_constant_order()` 取**运行时顺序**：`listOf(…)` 之后的 postfix 链逐个解析（`reversed`/`asReversed` 翻转，`toList` 等保序，**未知 postfix 直接 fail-closed 返回 None**）；分支效应用 `_branch_effects()` 从 `when` 各臂的调用推导，且**分支文本以上下文 `when` 块的闭括号为界**（否则 `finally` 尾巴上的 release/clear 会被最后一条臂继承，顺序判据当场变瞎） |
| 12-1 | 绑定必须**活到** `engine.start(…)`：绑定写点与其后**同块深度**的任何写（`brace_depth` 相等）视为覆盖；回调对象（深 2 层）与 catch（深 1 层）不算覆盖，因为它们不在同一执行路径的同一层 |
| 12-2 / 12-5 / 12-6 | 每个 teardown 函数的调用按 `_receiver_kind()` 分类（`bound` = 接收者落在 `activeEngine` 或 `(activeEngine ?: …)`；`reparse` = 落在裸 `currentEngine()`），并要求：至少一处 **live**（`path_feasible`）bound 调用、无 live reparse、且首处 bound 调用之前**没有可达的写**（`_order_violation`） |
| 12-3 | `if_blocks()` 找「then 块里含 `.cancel(`」的分支 → 条件 `guard_feasible()` 必须为真；块内仍要求 bound cancel / 清位 / IDLE 复位，且 cancel 先于清位 |
| 12-4 | `selectLocale` 走与 12-2 同一套（bound / reparse / 顺序），只是不要求清位 |

**被否决的方案**：

- **给 08-1 补全黑名单**（把 `?.take(0)` / `substring(0,0)` 加进正则）：黑名单永远差一条，且 08-5 同族的下一发就换了构造方式（`CharArray(0).concatToString()`）。换锚点（值是否仍等于 `preferredId`）是唯一闭合写法。
- **给 08-2 加「如果出现 tryEmit 就红」的特判**：那等于禁止一种合法写法。判据应该是「写在锁里还是锁外」，与写法无关。
- **用 `enclosing_conditions` 直接判 12-3**：共享层只建模 `if`/`when` 的包围条件，够用；但 arm 还需要「块内效果齐全」，所以走 `if_blocks` 取块再判，两者互补而非替代。
- **08-6 改成「禁止 `.reversed()`」**：同样是把判据绑在拼写上。解析 postfix 链并算出运行时顺序，未知 postfix fail-closed，才是可维护形态。

---

## §3 自测臂（见证固化）

**`speech_active_engine_check`**：`13 fixtures`（1 clean + 12 violating），`SPEECH_ACTIVE_ENGINE_SELFTEST_ARMS=12`（原 4 臂 → **12 臂**）。

新增 8 条（M1–M4 原有保留）：`W4` 绑定被同层写覆盖（exactly-1 断言）、`W5` stop 前清位、`W6` 死守卫、`W7` `selectLocale` 重解析、`W8` 死 cancel token（exactly-1 断言）。

**`skill_restore_session_cleanup_check`**：`SKILL_RESTORE_SESSION_CLEANUP_SELFTEST_ARMS=49`（`(臂名, 期望报错子串)` 去重计数，原约 40）。

新增 8 条：`08-1`（并断言**不**触发无关臂）、`08-2`（并断言**不**触发 setter-only 臂 —— 精确性）、`08-3`、`08-4`（exactly-1）、`08-5`（exactly-1）、`08-6` 反转计划（exactly-1）、`08-6` 对照臂（字面顺序手改仍红）、`C-DEL-2`（`finally` 尾巴被摘）。

**新装置（本轮补的空白）**：`08-6` 在加固前**没有任何自测夹具**（`check_drawer_delete_order` 只由 `run()` 调用），本次新增 `GOOD_DELETE_PLAN` / `GOOD_DRAWER_DELETE` / `BAD_DELETE_PLAN_REVERSED` / `BAD_DELETE_PLAN_SWAPPED` / `BAD_DRAWER_NO_FINALLY` 五个夹具，比较的基准是「运行时顺序」而非「字面顺序」。

两门的既有收尾标记原样保留（`test_scan.py` 冻结面里的断言仍然成立）：`SKILL_RESTORE_SESSION_CLEANUP_SELFTEST=PASS`、`SPEECH_ACTIVE_ENGINE_SELFTEST=PASS`。

---

## §4 本地读数（L1–L4）

| 层 | 内容 | 读数 |
|---|---|---|
| L1 | 共享原语自测 | `KOTLIN_LEXER_SELFTEST=PASS (20 arms)` / `KOTLIN_AST_SELFTEST=PASS (32 arms)` / `KOTLIN_VALUEFLOW_SELFTEST=PASS (26 arms)` |
| L1 | 本门自测 | speech `13 fixtures` 全绿（12/12 违规臂都被抓住）；skill `49 arms` 全绿（8 条新臂逐条 exactly/含子串断言） |
| L1 | 真树 | `speech … .` PASS rc=0；`skill … .` PASS rc=0（生产树未改，判据加固后仍绿 = 无误伤） |
| L2 | **反攻**（`verify_batch5.py --only`，机械复证） | **修前**：speech 5 BLIND + 1 CAUGHT；skill **6/6 BLIND**。**修后**：**12/12 CAUGHT**，`restore 12/12` 且 `git status src/` 为空 |
| L3 | **归因**（`probe/t02_attribution.py`，逐条打补丁 → 跑门 → 打印命中的判据 → sha256 还原） | 12/12 各**只**触发本见证对应的一条判据（08-4/08-5/08-6 为 exactly-1；12-x 各 1 条），无「被无关臂顺带抓住」的假红 |
| L4 | 全量门 | `bash scripts/scan/scan.sh` → **`Summary: 112 passed, 0 failed`**（4m58s，无 ENOSYS） |
| L4 | 夹具 | `test_scan.py` 46 个 `test_*` 分 4 段独立驱动（`probe/t02_testscan_chunks.py`）：137 + 73 + 72 + 62 = **344 cases / 0 failed** |
| L4 | 邻门 | 11/11 rc=0：`four_way_sync_check`、`i18n_check`、`process_logging_gate`、`marker_boundary_check`、`session_sheet_id_binding_check`、`chat_session_id_binding_check`、`collection_copy_on_mutate_check`、`voice_cancel_guard_check`、`auto_approve_bypass_check`、`overlay_show_hide_latch_check`、`router_lock_check` |

**沙箱处置记录**：`python3 scripts/scan/test_scan.py` 整跑仍会被 stall-guard 判「无输出且无 CPU 180s」杀掉（父进程空转等子进程）。健康路径 = 逐 `test_*` 函数驱动 + `sys.argv = ["test_scan.py", <repo root>]`（不传 root 会让每个夹具路径变成 `<cwd>/<函数名>`，出现 ~90 条假红）。本次无 ENOSYS 风暴，`scan.sh` 一次通过。

---

## §5 CI 读数

**run `40261718023` / `#2708` / job `121375325642` / attempt 1 / `success`**（workflow_dispatch，02:58:16Z → 03:17:27Z，19m11s）。

- **head_sha** `8b865532efbe4cca72d910f5d75ab80e6fcfe151` 与本地 HEAD 逐字符一致（`git rev-parse HEAD`）。
- **26 个步骤，唯一非 success = step 23 `Publish to Releases` skipped**（main 门控，符合预期）。
- job 日志 `1,482,267 B`、NUL 字节 0：
  - `Summary: 112 passed, 0 failed` 恰 **1** 处；
  - `ALL 344 CASES PASS` 恰 **1** 处；
  - 门头 `[N/112]` 共 **112** 条，序号 1..112 全互异、显示名全互异；
  - Kotlin 编译错误（`^ e: `）**0** 条；
  - 含 `FAILED` 的 **6** 行逐条核对，全部是 **PASSED 用例名子串**（`slot denied produces FAILED without calling provider`、`reason PERSISTENCE_FAILED maps to persistence_failed`、`phase FAILED maps to Failed`、`terminal FAILED maps to Failed`、`reason EXECUTION_FAILED maps to all_fallbacks_exhausted`、`the fixed tail still reaches a legal FAILED terminal`）；
  - `✅ skill_restore_session_cleanup_check selftest has teeth (SKILL_RESTORE_SESSION_CLEANUP_SELFTEST=PASS)` 与 `✅ speech_active_engine_check selftest has teeth (SPEECH_ACTIVE_ENGINE_SELFTEST=PASS)` 各 **1** 行 ⇒ **两门新臂在 CI 真树上真跑且零误伤**。

**CI 绿树与本地源码树等价性**：`git diff --name-only 8b865532e..HEAD -- . ':(exclude)fix-reports'` = **0 行**（本提交仅回填本报告）。

**CI 日志无法承载的部分（已登记 R8）**：`*_SELFTEST_ARMS=` 两个臂数标记在 CI 日志 **0 命中**——`test_scan.py` 只断言收尾标记 `…_SELFTEST=PASS`，门自证 stdout 不进 CI 日志。臂数（12 / 49）由本报告承载，注册行见 §7。

---

## §6 残留与负决策（均 P2，三件齐）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_inert_preferred_id` 只接受括号/`!!`/`as` 三种惰性包装；`preferredId.trim()`、`preferredId.orEmpty()` 这类**正当**归一化会判红 | 真树出现该写法且评审认定合法 | 把「值仍等于 preferredId」放宽为「值经纯字符串归一化仍可逆」白名单（`trim`/`orEmpty`/`takeIf{notBlank}` 需逐个论证是否破坏 id 语义） | 当前两处调用点都是裸 `preferredId`；先 fail-closed 比放过 `.take(0)` 重要 |
| R2 | `_receiver_kind` 用「语句边界 + 括号配平」的 200 字符窗口识别接收者；把 elvis 拆成多行（`(activeEngine ?: currentEngine())\n    ?.cancel()`）会判成 `other` → 红 | 有人把 elvis 链拆行 | 窗口改为「向前找配对括号直到深度 0 的语句起点」，或直接用共享层加一个 `receiver_expr()` | 现树全是单行形态；拆行时红在「no longer targets activeEngine」，方向安全（不会静默放行） |
| R3 | 12-1 的「同块深度」是支配关系的近似：绑定写在 `if (x) { … }` 内、`start(` 在外时不会判红 | 绑定被塞进条件块 | 引入条件否定集（`guard_feasible` 的补集）判「绑定是否在所有路径上支配调用」 | 现树绑定是函数顶层语句；近似方向安全（漏报而非误报），且该形态另有 arm1 兜底 |
| R4 | `_session_id_names` 只跟 3 轮局部派生；id 若经 `map { it.id }` 之类的 lambda 形参传递，`it` 不在允许集 → 红 | ChatRepository 的 hook 调用改写成集合变换形式 | 允许集加入「lambda 形参 + 由被允许集合派生的 `it`」 | 现树三条调用都是直接标识符；红在「not an id derived from the deleted row」，方向安全 |
| R5 | `SKILLS_WRITE_RE` 覆盖 `_skills` 的四种写形态；若哪天换成 `update {}` 或改类型为 `SharedFlow`（`emit` 是 suspend，语义不同） | 发布机制换实现 | 把「写形态」改为「对所有接收者为 `_skills` 的调用/赋值」统一枚举，再按锁区间判 | 现树只有 `value =`；其余三种是同族已知形态，已覆盖 08-2 的整类 |
| R6 | `_plan_constant_order` 只认 `listOf(…)`；改成 `arrayOf` / `when` 直接返回 / 从配置读序 → 返回 None → 门报 ROT 红 | 删表计划换构造 | 按构造类型扩解析（`arrayOf`/`mutableListOf` 与 `listOf` 同形，`when` 返回需按臂顺序合并） | fail-closed 是有意设计：顺序判据算不出顺序时必须红，不能猜 |
| R7 | 12-x 的 `n_clears >= 4` 与 08-x 的「≥3 锚点」这类**计数臂**本质上仍是「存在性」判据，只是作为兜底 | 有人合法减少清位点（例如把两处终态回调合并） | 把计数臂降级为「每个函数各至少一处」按函数枚举，不按总数 | 计数臂只做兜底，真正的判据已在上面逐条换过锚点；现在动它只会增加误伤面 |
| R8 | 两门的臂数标记（`SPEECH_ACTIVE_ENGINE_SELFTEST_ARMS=12` / `SKILL_RESTORE_SESSION_CLEANUP_SELFTEST_ARMS=49`）**未在 `test_scan.py` 注册**（`test_scan.py` 属冻结面） | 主会话并集收口时 | 在 `DA1016_GATES`/相应表里各加一行 `("<gate>", "<GATE>_SELFTEST_ARMS=", (), (REPO_ROOT,))` | 冻结面本支不改（硬约束 #3）；已有收尾标记仍被断言，臂数暂由本报告承载 |
| R9 | `check_record_skill_use_lock` 取函数体内**第一个** `synchronized(addLock)` 作为锁区间；若将来出现第二个锁块，判据只覆盖第一个 | 有人在 recordSkillUse 里加第二个临界区 | 枚举全部 `synchronized(addLock)` 区间，按「每个写点必须落在任一区间内」判 | 现树只有一个锁块；多锁块属结构性重写，届时该门要整体重审 |

---

## §7 共享层反馈与注册项

**共享层（本支未改，按硬约束 #2 只登记）**：

1. `match_bracket(text, pos)` 返回**闭括号下标**（不是开括号之后的第一位）。本轮 arm 里 `_plan_constant_order` 的 postfix 链行走依赖这一点，注释已写明。建议在 docstring 里显式声明下标语义（历史教训：`_following_lambda` 首版按名字猜语义，导致整族假红）。
2. `function_body(code, name)` 对同名重载取**首个**匹配。本支用 `body_of()` = 「decl 正则定位 + function_body 取体」组合规避；若将来同一文件出现同名重载（Kotlin 允许），共享层需要一个按偏移的版本。
3. `value_sources()` 不穿透 `CharArray(0).concatToString()` 这类**构造 + 转换**求值出空串的表达式。本支不需要它穿透——08-5 的判据是「值是否仍携带 `text`」，构造式不携带即脏；但如果将来要判「这个表达式求值出空串」，得靠 `const_value` 的构造折叠扩展。登记不修（无触发场景）。
4. `enclosing_conditions()` 不建模 lambda 作用域（`launch { … }` 里的位置不带外层条件）。本支 12-3 用 `if_blocks()` 直接判守卫可行性，绕开了这个缺口；08-3 同理。登记不修。

**并集收口时需要主会话做的两件事**：

1. 在 `test_scan.py` 注册两条臂数标记（见 R8）。
2. 合支时 `scripts/scan/scan.sh` 无新增门（门 112 不变），无需重编号。

---

## §8 停止节点

门提交 `8b865532e` → 分支 `fix/b7-skillrestore-speech` → 手动 dispatch `build-apk.yml` → **CI #2708 绿（head_sha 与门提交逐字符一致）= 本轮结束**。

**未合并 main**（合并起手权在用户）。
