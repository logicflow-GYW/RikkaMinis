# FIX-BATCH6-t08 — session_provider_enabled + memory_ui_wiring 两门判据搬到共享原语

分支 `fix/b6-sessionprov-memoryui` · 基线 `base/batch5-shared` @ `e145ec961`（= main `6ac77c6b2` + 共享 `_kotlin_valueflow`）
门提交 `2a237ae91`（2 文件 +837 / −640）· **生产 `.kt` 0 行** · `scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰 · 门 112 / 夹具 344 不变。

---

## §1 门与轴

| 门 | 扫描器 | 见证数 | 轴分布 | scan.sh 编号 |
|---|---|---|---|---|
| session_provider_enabled | `scripts/scan/session_provider_enabled_check.py` | 7（6 失明 + 1 原对照臂） | family-hole 5 · dataflow 1 · parser-death 1 | `[56/112] E/ISSUE-05 session provider enabled gate` |
| memory_ui_wiring | `scripts/scan/memory_ui_wiring_check.py` | 6（全失明） | family-hole 3 · order 1 · spelling 1 · parser-death 1 | `[94/112] G/ISSUE-07 memory ui wiring` |

**两门共症**（与 B5/B6 各批同族，本波形态更硬）：判据锚在「**某个 token 被写出来**」——子串存在、名字出现在外层 `if` 行、全文计数、`if (`/`while (` 扫描头；而缺陷动的正是「**这个 token 的效果**」：条件被常量折死、守卫体被掏空、实参换成不派生自来源的常量、选择器绕过漏斗、三引号把中间代码抹白。因此本波修法一律从「存在」推到 **可达性（path_feasible）× 蕴含（条件是否真约束）× 值流（value_sources/const_value）× 位置（bump 落在哪个区）**。

两门原先各自带一套**自写词法器**（`strip_comments` / `_strip_line_comments = line.split('//')[0]` / `brace_slice` 裸花括号计数 / `cond_truth` 常量白名单 / `_DISCARD_SCOPE_RE` 弃值作用域白名单）——本波**全部删除**，改走共享 `_kotlin_lexer.mask_code` + `mask_comments`（等长，位置可回投原文）+ `_kotlin_ast`（`match_bracket` / `function_body` / `if_blocks` / `const_bool` / `const_value` / `strip_parens`）+ `_kotlin_valueflow`（`code_view` / `path_feasible` / `value_sources` / `call_args` / `expr_end` / `contains_ident`）。解析失败一律 **fail-closed**（报违规，绝不回退原文扫描）。

---

## §2 见证原文（13 条）

### session_provider_enabled（witnesses-B，7 条）

**03-1 · family-hole · 判据 `check_source() A — _funnel_body enforces !instance.isEnabled`**
文件 `ChatSessionLifecycle.kt`
find：`    if (!instance.isEnabled) return SessionModelResolution.Unusable(UnusableModelReason.PROVIDER_DISABLED)`
replace：`    if (false && !instance.isEnabled) return …（同右）`
为何原门绿：`check_source` 对 funnel 体只做 `if FUNNEL_ENABLED not in funnel:` 与 `if FUNNEL_DISABLED_REASON not in funnel:` 两条**子串存在**断言；`DEAD_GUARD_RE` 只跑在 `site_segments`（三个调用点片段）上，**从不在 funnel 定义体上运行**。

**03-2 · family-hole · `check_factory_models() — B-03 _assigned_from & _peel_discarding`**
文件 `ChatSessionLifecycle.kt`
find：`    val effectiveModel = providerModelFor(entry)`（后接 currentModel / _modelName / _activeEntryId / _providerName / currentProvider 五写 + `ProviderFactory.create`）
replace：`    val effectiveModel = providerModelFor(entry).let { entry.model }`
为何原门绿：`_DISCARD_SCOPE_RE = re.compile(r'\.\s*(?:also|apply|onEach|forEach)\s*\{')` **不含 `let` / `run`**，于是 `_peel_discarding` 原样返回整串，其中仍含 `providerModelFor(` → `_assigned_from` 返回 True，模型来源「看似」解析得到。

**03-3 · family-hole · `check_source() C — _use_branch_encloses（loadSession Priority 2）`**
文件 `ChatSessionLifecycle.kt`
find：`                if (decision is SessionModelResolution.Use) {`
replace：`                if (decision is SessionModelResolution.Use || entry != null) {`
为何原门绿：`_use_branch_encloses(seg, pos)` 回溯外层块头，只要 `'if' in header and '{' in header and FUNNEL_USE in header` 就判「状态写入受保护」；**不看 `Use` 之后是否接了 `|| entry != null`**，`DEAD_GUARD_RE` 也只查 `if (true/false)` 开头。

**03-4 · family-hole · `check_group_selection() — C-SEL-1 _guarded_by_resolution`**
文件 `ChatModelRouting.kt`
find：`    val resolved = resolveProviderFromGroup(groupId)\n    if (resolved) {\n        _selectedGroupId.value = groupId`
replace：`    if (resolved || !resolved) {`（恒真式）
为何原门绿：`_guarded_by_resolution` 只 `re.search(r'\b%s\b' % name, cand)` 检查外层 `if` 行文本里是否出现解析结果变量名；`resolved || !resolved` 含 `resolved` → 直接 True。

**03-5 · dataflow · `check_group_selection() — C-SEL-2 _traces_to_active on lastEntryId`**
文件 `ChatModelRouting.kt`
find：`        val resolvedEntryId = plan.effectiveEntryId ?: return`
replace：`        val resolvedEntryId = plan.effectiveEntryId.let { entryId }`
为何原门绿：`_traces_to_active` 对右值调 `_peel_discarding`，`.let` 不在白名单 → 语句保留 `plan.effectiveEntryId.let { entryId }`，从中提出标识符 `plan` 递归追到 `planGroupSelect(... _activeEntryId.value ...)`，命中 `ACTIVE_ENTRY` → 判「id 仍来自活跃入口」，实际已被常量 `entryId` 顶替。

**03-6 · family-hole · `check_binding_writes() — C-SEL-4 RE_BARE_MEMBER_PICK（HeadlessChatRunner.kt）`**
文件 `debug/HeadlessChatRunner.kt`
find：`                val usableMember = firstUsableGroupEntry(\n                    members = members,\n                    instanceOf = { iid -> app.providerRepository.instance(iid) },\n                    apiKeyOf = { iid -> …loadApiKey(iid) },\n                ) ?: throw RPCException(…)`
replace：同左但把整个调用括成 `(firstUsableGroupEntry(…) ?: members.minByOrNull { 0 })`
为何原门绿：`_funnel_reachable` 只因「`firstUsableGroupEntry` 名字仍在函数体内」返回 True；`RE_BARE_MEMBER_PICK` 只匹配字面接收者 `memberEntryIds` 后接 `first/firstOrNull/last/lastOrNull/get/elementAt/[数字]`，对 **`members.minByOrNull { 0 }`**（乃至 `members.firstOrNull()`）完全失明。

**03-7 · parser-death · `_strip_line_comments() — line.split('//')[0]`（原报告即 CAUGHT，作对照臂）**
文件 `ChatSessionLifecycle.kt`
find：`    currentProvider = ProviderFactory.create(decision.instance, decision.apiKey, effectiveModel, context)\n    return true`
replace：`    val tag = "http://x"; currentProvider = ProviderFactory.create(…)\n    return true`
原判决：**CAUGHT** —— 字符串盲的 `//` 剥行确实把 `ProviderFactory.create(…)` 当注释吞了，但 `check_factory_models` 的下界计数 `MIN_FACTORY_CALLS = 8` 让它转成 ROT 违规（`only 7 … call site(s) … expected >= 8 (detection lost = ROT)`）。本支改造后该臂**仍须 CAUGHT**（见 §4）。

### memory_ui_wiring（witnesses-A，6 条）

**A04-1 · family-hole · CritA `MemoryFileRow dead_guard_spans / cond_truth`**
文件 `MemoryManagementScreen.kt`
find：`        if (onDelete != null) {\n            IconButton(onClick = onDelete) {`
replace：`        if (1 == 2 && onDelete != null) {`
为何原门绿：`cond_truth("1 == 2")` 与 `cond_truth("onDelete != null")` 都返回 `None`，合取分支的 `any(v is False)` 因此为 False → `dead_guard_spans` 返回 `[]` → 门报 OK。

**A04-2 · family-hole · CritD `SoulStore.refreshCache dead_guard_spans`**
find：`                                        if (fileName == SOUL_FILE_NAME) {\n                                            com.rikkaminis.app.agent.SoulStore.refreshCache(context)\n                                        }`
replace：改成 `when { false && fileName == SOUL_FILE_NAME -> …refreshCache(context) }`
为何原门绿：`dead_guard_spans` 的扫描头只有 `re.finditer(r"\b(?:if|while)\s*\(", text)`，**不扫 `when {`** → 死区表为空，refresh 被判 live。

**A04-3 · family-hole · CritB 删除确认块**
find：`                    if (name != null) {\n                        scope.launch {\n                            val refreshed = withContext(Dispatchers.IO) {\n                                memoryRepository.deleteFile(name)\n                                memoryRepository.listAllFiles()`
replace：`                    if (name != null && false) {`
为何原门绿：CritB 只断言 `confirm` 块内含子串 `scope.launch`、`withContext(Dispatchers.IO)`，以及 `deleteFile`/`listAllFiles` 落在 `io_slices` 内，**完全不对该块跑 `dead_guard_spans`**。

**A04-4 · order · CritC `autoItemsRefreshTick` 计数**
文件 `SessionMemorySheet.kt`
find：`                if (result is MemoryRepository.EntryMutationResult.Success) {\n                    autoItemsRefreshTick++\n                    mode = MemorySheetMode.List\n                }`
replace：成功分支内改成 `if (false) { autoItemsRefreshTick++; autoItemsRefreshTick++ }`
为何原门绿：判据是 `len(re.findall(r"autoItemsRefreshTick\+\+", sht))`，全文计数 `1 + 1 + 2 = 4 >= 3` 即过，**未过滤 `dead_guard_spans`**。

**A04-5 · spelling · CritE `dailyAutoItemFor` 注入上限**
find：`    val lineCount = content.lines().size\n    val injected = minOf(lineCount, maxInjectLines)\n    val detail = if (lineCount > maxInjectLines) "$injected/$lineCount lines injected" else "$lineCount lines (full)"`
replace：`    val _ignoredCap = maxInjectLines\n    val fixedCap = 100 + 100\n    val injected = minOf(lineCount, fixedCap)\n    val detail = if (lineCount > fixedCap) …`
为何原门绿：`if re.search(r"\b200\b", daily)` 抓不到 `100 + 100`；`body = daily[daily.find(")"):]` 又停在默认参数 `memoryInjectLines()` 的右括号上，而体内仍有 `maxInjectLines` 子串（在被弃的 `_ignoredCap` 里）→ 两条断言双双通过。

**A04-6 · parser-death · CritB `strip_comments` + `io_slices`**
find：`    LaunchedEffect(Unit) {\n        files = withContext(Dispatchers.IO) { memoryRepository.listAllFiles() }\n    }`
replace：`    LaunchedEffect(Unit) {\n        val raw = """"/*"""\n        files = memoryRepository.listAllFiles()\n        val endRaw = """*/"""\n        if (false) { withContext(Dispatchers.IO) { } }\n    }`
为何原门绿：自写 `strip_comments` 不支持三引号原始字符串——`""""/*"""` 的 4 个 `"` 让 `in_str` 走 `True→False→True→False`，随后 `/*` 触发块注释剥除，把直到 `endRaw` 里 `*/` 之间的 `files = memoryRepository.listAllFiles()` **整段抹白**；裸花括号计数也同时被 `"""` 内的括号打乱。

---

## §3 判据改造（旧形态 → 新形态，逐条）

词法层（两门共用，一次性根治）：

| 旧 | 新 |
|---|---|
| `strip_comments` / `_strip_line_comments = line.split('//')[0]` | 共享 `mask_code` + `mask_comments`，**长度与源码严格相等**，结构判断在视图上做、命中偏移回投原文报错（`_view` 失败 → fail-closed） |
| `brace_slice` / 手写花括号计数器 | `match_bracket` + `function_body`（块头、参数、lambda 区间不再被字符串/注释里的括号打乱） |
| `cond_truth` 常量白名单（只认 `false`/`true`/`0 == 1`） | `const_bool` 折叠（`!true`、`1 == 2`、`when (false)`、`false && X`、`true || X` 全折），任一合取臂折假即整条件不可达 |
| `dead_guard_spans` 只扫 `if (`/`while (` | `if_blocks` + 无主题 `when` 常量假分支进死区表，`_dead_spans` 与共享 `path_feasible` 求交，得「**可运行位置**」`live_positions` |
| `_DISCARD_SCOPE_RE` 弃值作用域白名单（also/apply/onEach/forEach） | 不再猜拼写：走 `value_sources` 逐来源求值 + `peel_discarding`，`.let{…}` / `.run{…}` 的**实参是否派生自被弃值**由 `contains_ident` 判 |

session_provider_enabled 逐臂：

1. **A（03-1）漏斗闸门**：`_funnel_span` 取 funnel 函数体 → `!instance.isEnabled` 的命中必须满足 `_live_terminating_guard`＝「**落在条件位置** + 守卫块内**真的退出**（`_exits_block`）」，且该条件再过可达性（`path_feasible` + 常量折叠）。`false && X` 折死 → 守卫不可达 → 违规。`_constant_use_guards` 同法扫体内其余恒假守卫。
2. **B（03-2）模型来源**：`_all_sources_enriched(expr)` 把 `providerModelFor(entry).let { entry.model }` 的每个来源单独求值——来源若**不派生自**解析出的模型（lambda 体里没有 `it`/接收者痕迹）即判「未穿过唯一来源」；`_enclosing_source` 用偏移回投确认站点。
3. **C（03-3）Use 分支**：判据换成 `_implies(cond, test)`——条件必须**蕴含** `decision is Use`。蕴含按顶层 `&&`/`||` 拆分后逐叶判（`_use_leaf` / `_neg_use_leaf` / `_positive_name_leaf`），析取臂 `entry != null` 不是 Use 叶 → 蕴含不成立 → 该「守卫」不算保护状态写入；同时接受 `_terminating_negative_guard`（`if (!Use) return` 这种等价写法）。
4. **C-SEL-1（03-4）组选择守卫**：外层 `if` 文本必须**蕴含解析结果**，恒真式 `resolved || !resolved` 被 `_implies` 判为「不约束」→ 违规；不再是「名字出现在 if 行」。
5. **C-SEL-2（03-5）lastEntryId 溯源**：`_traces_to_active(hops=3)` 改在共享值流上逐跳展开右值**来源本身**，`.let { entryId }` 的来源是常量替换（不引用被弃值）→ 链断 → 违规。
6. **C-SEL-4（03-6）裸成员挑选**：`_member_collections(body)` 从**派生接收者**枚举成员集合（不再写死 `memberEntryIds` 与六个方法名），`_positional_picks(body, receivers)` 判「无谓词或常量谓词的选择」——`minByOrNull { 0 }` 命中；`_funnel_reachable` 不再因「名字还在函数体里」判活，而要求该表达式**真的走漏斗**（括弧/`?:` 备选分支逐一判）。
7. **对照臂（03-7）parser-death**：结构分析既已离开字符串内部，`"http://x"` 不再截行；`MIN_FACTORY_CALLS` 下界（ROT）与 `_mask_dependent_sites`（掩码可疑时报「检测面丢失」而非静默放行）双层守住 → 该臂仍红。
8. 收尾标记保持 `SESSION_PROVIDER_ENABLED_SELFTEST=PASS (24 arms)`，臂数写进标记串（原注册断言只因子串 `…SELFTEST=PASS` 命中，改串不破注册）。

memory_ui_wiring 逐臂：

1. **CritA（A04-1）删除按钮活性**：`MemoryFileRow` 里 `IconButton(onClick = onDelete)` 必须落在 `live_positions`；`1 == 2 && …` 经 `const_bool` 折死 → 「有拼写、跑不起来」→ 违规。
2. **CritD（A04-2）SOUL 刷新**：写盘站点必须活；死区表新增**无主题 `when`** 的常量假分支（`when { false && X -> … }`）→ 刷新不可达 → 违规。
3. **CritB（A04-3）删除确认**：不再断言 `scope.launch` / `withContext` 子串存在，改要求 `deleteFile` + `listAllFiles` 落在**可达的** `withContext(Dispatchers.IO)` 区间（区间本身由 `match_bracket` 定界、再扣死区）→ `if (name != null && false)` 内整块不可达 → 违规。
4. **CritC（A04-4）刷新计数**：bump 位置逐个取偏移后过 `live` 过滤，**只数能运行的**（`2 < 3` 违规）——计数不再被死分支里的 `++` 顶满。
5. **CritE（A04-5）注入上限**：`_cap_operands` 从比较/`minOf` 的实参取上限操作数，沿 `value_sources` 一跳 + `const_value` 折叠：操作数折成常量（`100 + 100`）或只被弃值绑定（`_ignoredCap`）即判「上限没流进注入」；同时保留裸 `200` 字面量判据（`(?<![\w.])200(?![\w.])`），函数体定界改用 `function_body` 而非 `daily.find(")")`。
6. **CritB（A04-6）词法器死亡**：三引号原始字符串由共享 `mask_code` 正确吞掉，`"""` 内的 `{`/`/*` 不再抹白后续代码；读文件站点仍须在可达 IO 区（`files = memoryRepository.listAllFiles()` 在无 IO 包裹处 → 违规）。
7. 收尾标记 `MEMORY_UI_WIRING_SELFTEST=PASS`（23 臂，含 6 条新见证臂）原样保留。

---

## §4 四层证据

**L1 门自测（含牙）**
```
python3 scripts/scan/session_provider_enabled_check.py --selftest  → SESSION_PROVIDER_ENABLED_SELFTEST=PASS (24 arms)   rc=0
python3 scripts/scan/memory_ui_wiring_check.py        --selftest  → MEMORY_UI_WIRING_SELFTEST=PASS                      rc=0   （23 臂行，全 OK）
```

**L1b 真树**
```
python3 scripts/scan/session_provider_enabled_check.py .  → 8 ProviderFactory.create site(s) resolved through the enriched model
                                                             GATE=GREEN   rc=0
python3 scripts/scan/memory_ui_wiring_check.py .          → OK (MemoryManagementScreen.kt, SessionMemorySheet.kt)  rc=0
```

**L2 原见证逐条反攻（决定性验收）**：用官方驱动 `batch6/verify_batch5.py --mode full`，两门各跑一次（见证文件按门分流：`witnesses-A.json` 装 memory_ui 6 条、`witnesses-B.json` 装 session_provider 7 条）。

| 见证 | 原判决 | 本支判决 | gate_rc | restored | 红在哪 |
|---|---|---|---|---|---|
| 03-1 | FALSIFIED | **CAUGHT** | 1 | True | 2 violations（漏斗守卫不可达 + 站点） |
| 03-2 | FALSIFIED | **CAUGHT** | 1 | True | 1 violation（模型来源未穿过唯一来源） |
| 03-3 | FALSIFIED | **CAUGHT** | 1 | True | 2 violations（Use 蕴含不成立） |
| 03-4 | FALSIFIED | **CAUGHT** | 1 | True | 2 violations（恒真式不约束） |
| 03-5 | FALSIFIED | **CAUGHT** | 1 | True | 1 violation（lastEntryId 溯源断链） |
| 03-6 | FALSIFIED | **CAUGHT** | 1 | True | 1 violation（`minByOrNull { 0 }` 位置选择） |
| 03-7 | CAUGHT（对照） | **CAUGHT** | 1 | True | 1 violation（站点下界 ROT 守卫仍生效） |
| A04-1 | FALSIFIED | **CAUGHT** | 1 | True | `MemoryFileRow has no IconButton(onClick = onDelete) that can RUN` |
| A04-2 | FALSIFIED | **CAUGHT** | 1 | True | `the withContext(Dispatchers.IO) region that writes memoryRepositor…` |
| A04-3 | FALSIFIED | **CAUGHT** | 1 | True | `delete confirm has no reachable withContext(Dispatchers.IO)` |
| A04-4 | FALSIFIED | **CAUGHT** | 1 | True | `autoItemsRefreshTick is bumped 2 time(s) that can RUN, expected >= 3` |
| A04-5 | FALSIFIED | **CAUGHT** | 1 | True | `no cap operand derives from it — the injection cap is hard-coded` |
| A04-6 | FALSIFIED | **CAUGHT** | 1 | True | `listAllFiles( at offset 7051 runs outside any reachable …IO region` |

**13/13 CAUGHT，restore 13/13 True**（`git status --porcelain` 复攻前后均只列两个门脚本，`src/` 零残留）。明细：`batch6/probe/t08-session_provider_enabled_check.json`（7）、`batch6/probe/t08-memory_ui_wiring_check.json`（6）。

**L3 邻门回归**：本波沙箱处于 ENOSYS 窗口（见 L4 说明），按手册改逐门独立进程续跑，台账 `probe/t08_local_scan.json` = **10 entries / 0 red**（含两门本体与前后邻门），全量读数交给 CI。

**L4 全量**：CI 同一棵树跑 `scan.sh`（`Summary: 112 passed, 0 failed`）与 `test_scan.py`（`ALL 344 CASES PASS`），读数见 §5。本地 `bash scripts/scan/scan.sh` 整跑在 `[24/112]` 处被沙箱打死（`/usr/bin/python3: Function not implemented`，且 `budget stop break guard` 出现 195 条 `unreadable` 假违规）= 环境层失败而非门红，全量证据以 CI 为准。

---

## §5 CI

| 项 | 值 |
|---|---|
| workflow | `build-apk.yml`（手动 dispatch，分支不触发 push CI） |
| run | **#2701** · run_id `40254147183` · attempt 1 · **success**（先前提交信息里误写 #2699，以本行 run_number 为准） |
| job | `121352264125`（`build`）· 26 步 · 唯一 skipped = **Publish to Releases**（main 门控），non-success = 0 |
| head_sha | `2a237ae91828ded4ca37362f4229bd2d9ed7d1e1` 与本地 HEAD 逐字符一致 |
| 分支 | `fix/b6-sessionprov-memoryui` · 起于 `2026-10-26T01:25:30Z`，`01:47:46Z` 出结论 |
| 日志 | `probe/ci/job.log` 1,486,086 B / 17,850 行 |

日志逐条读数（`probe/judge_ci.py`）：`Summary: 112 passed, 0 failed` **恰 1 条**（`║` 框线行）· `ALL 344 CASES PASS` **恰 1 条** · `━ [N/112]` 门头 **112 条、编号 1–112 互异、名称互异**、`RED` 分隔头 **0 条** · ` e: `（kotlin 编译错）**0 条** · 含 `FAILED` 的 6 行全部是 PASSED 用例名子串（`slot denied produces FAILED without calling provider PASSED` / `phase FAILED maps to Failed PASSED` 等）· `SELFTEST=` 标记 76 处，其中本支两门各恰 1 行：
```
✅ session_provider_enabled_check selftest has teeth (SESSION_PROVIDER_ENABLED_SELFTEST=PASS)   [01:30:29Z]
✅ memory_ui_wiring_check        selftest has teeth (MEMORY_UI_WIRING_SELFTEST=PASS)            [01:32:39Z]
```
两门在 scan.sh 里的独立读数：`[56/112] E/ISSUE-05 session provider enabled gate`、`[94/112] G/ISSUE-07 memory ui wiring`。

**停止节点**：分支 CI 绿即收口，**未合并 main**（合入起手权在用户）。报告提交为纯 `fix-reports/` 文本，`git diff --name-only 2a237ae91..HEAD -- . ':(exclude)fix-reports'` = **0 行**。

---

## §6 残留（每条：触发条件 / 触发后方案 / 搁置理由）

- **R1 蕴含判定不跨函数**（`_implies` 只在同一条件表达式内拆 `&&`/`||` 叶）。触发条件：出现 `val ok = decision is Use` 后 `if (ok || junk)` 这类「先绑定再或」的见证。方案：把绑定名经 `value_sources` 代回条件再判蕴含。搁置理由：真树两门审的 4 个站点都是内联条件；扩了会引入一跳歧义，先等见证。
- **R2 成员集合派生只跟同函数体**（`_member_collections` 不收跨文件/扩展属性提供的成员列表）。触发条件：有人把 `members` 换成 `group.memberEntries()`（本文件外定义）。方案：接调用图传递闭包（同 dao 门漏斗做法）。搁置理由：半径纪律——真树该站点是本地 val，且改坏会拖慢判定。
- **R3 `path_feasible` 不建模循环计数**（`while (i < 0)` 这类需区间求交的形态仍按可达）。触发条件：出现「循环体恒不执行」类见证。方案：复用 B5-t08 的 `conds_feasible` 联合求交，回填共享层。搁置理由：本波 13 条无一属此形态；且属共享层缺口，按硬约束只登记不改。
- **R4 共享 `value_sources` 仍不认无花括号 `if (c) a else b`**（B6-t02 R1 同款）。触发条件：门审的站点里出现三元写法。方案：修在共享层（本支禁改）。搁置理由：本支 memory 门用 `_cap_operands` 局部绕过，未再触发。
- **R5 `_view` fail-closed 会让「新语法」当场全红**（`code_view` 解析失败即报违规）。触发条件：源码引入共享词法器未覆盖的构造（内联类新写法、`context()` 接收者等）。方案：先在共享层补词法，门侧不改。搁置理由：fail-closed 方向安全（宁可假红不静默绿），且本波两门审到的 5 个 `.kt`（ChatSessionLifecycle / ChatModelRouting / HeadlessChatRunner / MemoryManagementScreen / SessionMemorySheet）真树解析全部成功。
- **R6 门自证臂数不进 CI 日志**：CI 只断言标记子串，`24 arms` / `23 臂` 的逐臂 stdout 不回显（既知）。触发条件：需要 CI 侧看见臂数变化时。方案：test_scan 注册行改断言完整标记串（`…=PASS (24 arms)`）。搁置理由：`test_scan.py` 冻结面，交主会话统一改（见 §7）。

---

## §7 `test_scan.py` 注册项（交主会话）

两门**均已在 `scripts/scan/test_scan.py` 注册**，本支未动该文件，注册行**无需新增**：

| 行号 | 注册元组 | 状态 |
|---|---|---|
| L2762 | `("session_provider_enabled_check", "SESSION_PROVIDER_ENABLED_SELFTEST=PASS", (), (REPO_ROOT,))` | 标记串未变（新尾巴 ` (24 arms)` 不影响子串命中）——CI 已见 `selftest has teeth` |
| L2863 | `("memory_ui_wiring_check", "MEMORY_UI_WIRING_SELFTEST=PASS", (), (REPO_ROOT,))` | 标记串未变——CI 已见 `selftest has teeth` |

建议（可选，不在本支 radius 内）：把 L2762 的期望串升级为完整串 `SESSION_PROVIDER_ENABLED_SELFTEST=PASS (24 arms)`，这样臂数变化在 CI 里也有见证；memory 门同理（当前 23 臂无尾数标记，若加尾数须同步改串）。

---

## §8 复现命令

```sh
WT=/var/minis/shared/work/meta-audit-v5/batch6/wt6/t08
cd $WT

# L1 门自测 + 真树
python3 scripts/scan/session_provider_enabled_check.py --selftest   # → …SELFTEST=PASS (24 arms)
python3 scripts/scan/session_provider_enabled_check.py .            # → GATE=GREEN rc=0（8 sites）
python3 scripts/scan/memory_ui_wiring_check.py --selftest           # → MEMORY_UI_WIRING_SELFTEST=PASS
python3 scripts/scan/memory_ui_wiring_check.py .                    # → OK rc=0

# L2 逐条反攻（13 条 → 全 CAUGHT，restore 全 True）
B=/var/minis/shared/work/meta-audit-v5/batch6
python3 $B/verify_batch5.py --witnesses $B/witnesses-B.json --repo . --mode full \
  --only session_provider_enabled_check.py --out $B/probe/t08-session_provider_enabled_check.json
python3 $B/verify_batch5.py --witnesses $B/witnesses-A.json --repo . --mode full \
  --only memory_ui_wiring_check.py --out $B/probe/t08-memory_ui_wiring_check.json

# L3 邻门（沙箱 ENOSYS 窗口下的逐门跑法；台账 probe/t08_local_scan.json）
python3 -u probe/t08_gate_loop.py . --limit 24

# L4 全量 = CI 同一棵树（本地整跑会被 ENOSYS 打死）
bash scripts/scan/scan.sh            # CI: Summary: 112 passed, 0 failed
python3 scripts/scan/test_scan.py .  # CI: ALL 344 CASES PASS

# CI 判读
sh probe/fetch_ci.sh && python3 probe/judge_ci.py
```
