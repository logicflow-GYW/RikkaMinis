# FIX-BATCH7-t03 · parts_json_funnel + modelsvc_single_read（12 条见证全翻红）

- **分支**：`fix/b7-t03-partsjson-modelsvc`
- **基线**：`e145ec961`（`e145ec961232c7898644346ff4bc0c3f70980369`，= `base/batch6-shared`）
- **工单**：`batch7/fix-tasks/B7-t03.md`（A-报告 §10 六条 / B-报告 §07 六条）
- **改动面**：**2 个门脚本**（`scripts/scan/parts_json_funnel_check.py`、`scripts/scan/modelsvc_single_read_check.py`），生产 `.kt` **0 行**，冻结面（`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py`）**0 触碰**，门 112 / 夹具 344 **不变**。
- **停止节点**：分支 CI 绿（§5 / §8）。**未合并 main**。

---

## §1 缺陷与根因

两门共用同一条失效轴：**判据锚在「某个拼写 / 某个东西存在」，而缺陷动的是「那个东西产生的效果」**。12 条见证里 11 条属此形态，1 条（10-6）是对照臂（修前已 CAUGHT，必须保持）。

### 1.1 `parts_json_funnel_check.py`（A-报告 §10）

| # | 判据 | 原实现锚的是什么 | 见证如何绕过 |
|---|---|---|---|
| 10-1 | `[R5]` companion 漏斗 | `extract_function(...) is not None` —— 函数存在即绿 | 漏斗保留完整签名 + 全部调用点，函数体改成 `= reasoningContent`；MB 级 reasoning 原样进 Room |
| 10-2 | `[R6]` 备份导入双列封顶 | 子串 `ChatRepository.capPartsJsonForRow(m.optString("partsJson"))` 在函数里 | 在封顶表达式后追加 `.let { m.optString("partsJson") }`：子串一字不改，落库的是原始值 |
| 10-3 | `[R7]` 序列名分类 | 名字**出现在** `messageToJson` 体内 | 分支保留、名字保留，臂体改为 `Unit`（匹配即丢弃） |
| 10-4 | `[R10]` debug 包直读 DAO | 正则 `.dao.<name>(` | DAO 先绑局部 `val rawDao = repo.dao`，再 `rawDao.lastMessageParts(...)` |
| 10-5 | `[R11]` 名字分发计数 | `\b\w+\s*\+=\s*[^=]`（只看「有 +=」） | `vid += 0 else img += 0` —— 结构完整的零增量 |
| 10-6 | `[R12]` 嵌套 mediaRef 读 | （对照臂，修前已 CAUGHT） | 用于验证加固未破坏既有判据 |

### 1.2 `modelsvc_single_read_check.py`（B-报告 §07）

| # | 判据 | 原实现锚的是什么 | 见证如何绕过 |
|---|---|---|---|
| 07-1 | 请求体只读一次 | `requestFile\s*\.\s*readText()` 等接收者拼写 | 第二次读改成构造器实参 `java.io.FileReader(requestFile).use { it.readText() }` |
| 07-2 | capture 结果被执行器消费 | `CapturedRequestBody.capture { requestFile.readText() }` 子串 | 先 capture 到 `_unused`，再把 `requestBody` capture 成 `{ "" }`：子串仍在，执行器拿到空体 |
| 07-3 | accept 循环不逃逸线程池 | 三词黑名单（`thread` / `Thread` / `Executors`） | `java.util.concurrent.CompletableFuture.runAsync { handleClient(...) }` |
| 07-4 | worker 派生受 permit 支配 | 文本 `if (spawn) spawnThread(` 存在 | 保留 `if (spawn)` 分支，**再加 `else { spawnThread(…) }`**：拒绝分支照样派生 |
| 07-5 | permit 归还 | 子串 `permits.release()` | `if (false) permits.release()` |
| 07-6 | 客户端交给池 | 子串 `pool.submit(client)` | `if (false) pool.submit(client)` |

**共同根因**：这些门把"我们写过这个决策"当成"这个决策生效"，于是**保留拼写、删除效果**的最小补丁全部通过。

---

## §2 修法

**阶梯第 1 级：不新增机制，把已有判据换成共享原语的语义判据**（仓内 `_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow` 已具备所需全部能力，本轮零新增共享 API）。

可复核句：**「停在第 1 级，因为共享三件套已提供 `function_body` / `when_branches` / `if_blocks` / `call_args` / `expr_end` / `value_sources` / `path_feasible` / `const_value`，本波 11 条破口的判据全都能由它们组合出，不需要新原语。」**

### 2.1 词法/结构层：自写件全部删除

| 旧自写件 | 换成 |
|---|---|
| `strip_kotlin_comments`（自写 string / raw string / `${…}` 状态机） | `_kotlin_lexer.mask_comments`（长度严格不变，位置可跨视图复用；lex 失败 → fail-closed） |
| `extract_function`（签名后找 `{` 括号配平） | `_kotlin_ast.function_body`（区分 `{ … }` 与 `= …`，表达式体不再吞下一个函数） |
| `_balanced_arg`（手数括号） | `match_bracket` |
| `brace_balanced_arm`（手数花括号） | `when_branches` + `match_bracket` |
| `functions_in_source` / `derive_all_functions` / `derive_parts_name_readers` / `count_type_dispatchers`（按行切声明块） | `iter_functions()` —— 共享 `FUN_RE` + `function_body` 按声明切片（同名重载各自成立） |

`modelsvc` 门同样改为 `mask_code` 单通道 + `call_args` / `expr_end` / `path_feasible`。

### 2.2 逐条判据新形态

| # | 新判据（判的是效果） |
|---|---|
| 10-1 | **漏斗体三事实**：(a) 字节上限常量必须出现在 `if` **条件位**；(b) 截断函数必须被调用且 `path_feasible`；(c) **每个 `return` 点**的值来源中必须有一个来自截断函数（只在 tail 上看会把"早退截断 + 兜底返回原值"这种正确形态判红 —— 本波实测踩到并修正） |
| 10-2 | 逐个 `<字段> = <rhs>` 解析**值来源**（elvis / if-else 两侧 / `let` 尾 / `also` 剥接收者），每个来源非空即必须含漏斗调用；具名参数的 rhs 边界用 `split_top` 在**顶层逗号**再切一次（`expr_end` 会把下一个参数卷进来） |
| 10-3 | 每个序列名的 `when` 臂必须**把循环变量交给一次可达调用**：`arm_hands_on` = `call_args` 实参含部件变量 ∧ `path_feasible`。`-> Unit`、`-> if (false) put(p)` 都是「分类在纸上、丢弃在事实上」 |
| 10-4 | 派生「绑定到 DAO 的局部名」（`val rawDao = repo.dao`，3 跳）+ 派生「ChatRepository 上有哪些 tolerant 包装」；任何 reader 调用的接收者是 DAO 别名 → 红；**没有包装的 reader** 任何调用都红（无漏斗可躲） |
| 10-5 | 累加必须**可能非零**且在**可达行**上：`+= <expr>` / `= x + <expr>` / `++`，`const_value` 折叠后 == 0 即不算；表达式边界在 `else` 处截断（否则 `0 else img += 0` 被当成未知表达式）；委派给同文件 helper 时递归一层 |
| 10-6 | 保留，改用 `contains_ident` + `path_feasible`（词边界不再被反引号/字符串破坏） |
| 07-1 | 读点按**它碰到的文件**定位，而不是按接收者拼写：接收者链命中（`requestFile.readText()`、别名 `rf.readText()`）+ 构造器实参命中（`FileReader(requestFile)`、`Files.readAllBytes(requestFile.toPath())`）；`exists()/delete()/length()/name` 这类元操作不算读；**每个读点必须落在唯一 capture lambda 内**且该 lambda 恰有 1 个读点 |
| 07-2 | capture 必须**恰一次**；结果必须绑定到非 `_` 局部；该局部必须被 `jsonOrThrow()` 与 `streamingFlagOrFalse()` 两者消费（丢一个 = 执行器拿到的不是捕获到的体） |
| 07-3 | accept 循环内：`pool.submit` 必须**可达**且至少一个；任何 `handleClient(` 直调即红（客户端必须经池）；并行原语族放宽到 `CompletableFuture` / `ForkJoinPool` / `CoroutineScope` / `launch|async|runAsync` / `newFixedThreadPool`（`(?<![\w])` 边界，`java.util.concurrent.CompletableFuture` 也能命中） |
| 07-4 | 派生「携带 permit 结论的局部」（`val spawn = … permits.tryAcquire()`，按 `expr_end` 取完整 rhs）；`if` 的**每个** `spawnThread(` 必须落在该守卫的 **then** 分支内；**`else` 分支出现 `spawnThread(` 直接红**；守卫恒真 / 不可达 / 无守卫 各自单独红 |
| 07-5 | `permits.release()` 必须存在**且至少一次可达**（`if (false)` 包裹 = 预算永久泄漏） |
| 07-6 | 由 07-3 的 `path_feasible` 覆盖（`if (false) pool.submit` = 无人可达的 submit） |

---

## §3 见证清单与反攻结果

官方驱动：`verify_batch5.py --mode full --only <gate>`（禁止自造跑法）。

| 门 | 见证 | 修前 | 修后 | 落在哪条判据 |
|---|---|---|---|---|
| parts_json | 10-1 | BLIND | **CAUGHT** | 漏斗体恒等 → 上限比较缺失 + 永不截断 + 返回值无截断来源 |
| parts_json | 10-2 | BLIND | **CAUGHT** | `.let` 折回 → `partsJson` 存放值来源不含漏斗 |
| parts_json | 10-3 | BLIND | **CAUGHT** | 臂体 `Unit` → 未把部件交给可达调用 |
| parts_json | 10-4 | BLIND | **CAUGHT** | DAO 别名 `rawDao` → `via 'rawDao'` |
| parts_json | 10-5 | BLIND | **CAUGHT** | `+= 0` → 增量折叠为零 |
| parts_json | 10-6（对照） | CAUGHT | **CAUGHT** | 未破坏（嵌套读被丢弃） |
| modelsvc | 07-1 | BLIND | **CAUGHT** | capture 外的构造器实参读 |
| modelsvc | 07-2 | BLIND | **CAUGHT** | capture 计数 2 + `_unused` 丢弃 + lambda 空体 |
| modelsvc | 07-3 | BLIND | **CAUGHT** | `CompletableFuture` 逃逸 + `handleClient` 直调 |
| modelsvc | 07-4 | BLIND | **CAUGHT** | permit 拒绝 `else` 分支派生 |
| modelsvc | 07-5 | BLIND | **CAUGHT** | release 不可达 |
| modelsvc | 07-6 | BLIND | **CAUGHT** | submit 不可达 |

**12/12 CAUGHT（rc=1, exact）**，`git status src/` 每次反攻后为 **0 行**（restore 全部成功）。
证据：`probe/t03-parts_json_funnel_check.json`、`probe/t03-modelsvc_single_read_check.json`。

每条都**红在自己那条判据上**（不是"任意红"），命中消息首行实录：

| # | 命中消息（截断） |
|---|---|
| 10-1 | `[R5] FAIL the companion ceiling funnel no longer applies its ceiling — capReasoningContentForRow: does not compare against MAX_MESSAGE_PARTS_JSON_BYTES; …` |
| 10-2 | `[R6] FAIL backup import stores an uncapped payload — partsJson line 53: \`partsJson = m.optString("partsJson")\` stores a value that never passed capPartsJsonForRow` |
| 10-3 | `[R7] FAIL messageToJson matches "mediaRef" but never hands the part on (arm body is Unit / dead / does not pass the part variable)` |
| 10-4 | `[R10] FAIL debug/ reads parts_json outside its tolerant funnel: ChatDebugMethods.kt:58 lastMessageParts( via 'rawDao'` |
| 10-5 | `[R11] FAIL 1 image/video parts_json faces dispatch on "mediaRef" and MOVE NO COUNTER` |
| 07-1 | `ModelExecutionService.kt:401: 1 read(s) of the request body OUTSIDE the capture lambda — whatever the spelling (receiver method, FileReader/FileInputStream argument…)` |
| 07-2 | `:381: 2 x CapturedRequestBody.capture — the body must be captured once per run` / `:380: bound to the DELIBERATELY-DISCARDED local '_unused'` |
| 07-3 | `NativeOffload.kt:280: runAcceptLoop spawns work outside the bounded pool ('CompletableFuture')` + `calls handleClient(…) directly` |
| 07-4 | `NativeOffload.kt:468: the permit-denied \`else\` branch spawns a worker anyway` |
| 07-5 | `NativeOffload.kt:481: 'permits.release()' never runs (dead guard)` |
| 07-6 | `NativeOffload.kt: every pool.submit(…) in runAcceptLoop is unreachable (dead guard)` |

---

## §4 四层验证

| 层 | 证据 |
|---|---|
| L1 门自证 | `parts_json_funnel_check.py --selftest` → `PARTSJSON_FUNNEL_SELFTEST=PASS`（**40 臂**，原 33 臂 + 本波 7 臂：r5-wired-funnels / r5-funnel-body-identity / r6-import-caps-both / r6-import-let-discards-cap / r7-arm-drops-part / r10-dao-alias-read / r11-counter-delta-zero）；`modelsvc_single_read_check.py --selftest` → `MODELSVC_SINGLE_READ_SELFTEST=PASS (22 arms)`（原 17 + 本波 5 臂）。两门真树 `rc=0`。 |
| L2 见证反攻 | 12/12 CAUGHT（§3），`restore` 全 True，`src/` 零残留。 |
| L3 邻门 + 全量 | 邻门 11 道 `rc=0`（`four_way_sync` / `offload_cli_args` / `offload_host_path` / `provider_type_registry` / `chat_session_id_binding` / `session_sheet_id_binding` / `file_provider_paths_coverage` / `backup_remap_rollup` / `backup_streaming_export` / `memory_gate_provider_fields` / `memory_ui_wiring`）。`bash scripts/scan/scan.sh` → **`Summary: 112 passed, 0 failed`**。`test_scan.py` 逐函数驱动 46 个函数：**45 个跑通、338 cases PASS / 0 FAIL**，唯一未跑完的 `test_da1018_gates` 卡在沙箱 `OSError [Errno 38] Function not implemented`（S28，5 次退避重试全失败，与本支改动无关；该函数覆盖的是 da1018 批的 7 个门）。完整 344 读数以 CI 为准。 |
| L4 CI | 见 §5。 |

---

## §5 CI 读数

- **run** `40265193577` / **#2711** / job `121385958406` / **attempt 1** / `completed success`
- **head_sha** `cc92f6e43640…` —— 与本地 HEAD `cc92f6e43` **逐字符一致**
- 26 步（23 实步 + 3 Post），**唯一 skipped = step 23 `Publish to Releases`**（`if: refs/heads/main` 门控，预期）
- job 日志 **1,486,225 B / 0 NUL**，逐条核：

| 读数 | 期望 | 实测 |
|---|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 | **1** |
| `ALL 344 CASES PASS` | 恰 1 | **1** |
| 门头 `[N/112]` | 112 条且编号互异 | **112 / 112 unique** |
| `^ e: `（Kotlin 编译错） | 0 | **0** |
| 含 `FAILED` 的行 | 全部是 PASSED 用例名子串 | **6 行，逐条以 `PASSED` 结尾** |
| `PARTSJSON_FUNNEL_SELFTEST=PASS` | ≥1 | **1** |
| `MODELSVC_SINGLE_READ_SELFTEST=PASS` | ≥1 | **1** |
| 时长 | ~22 min | 03:40:06Z → 04:02:54Z（22m48s） |

⇒ 本支的两门**在 CI 真树上没误伤**（112/0），且两门 `--selftest` 在 CI 里**真跑到**（标记各 1 行，被 `test_scan` 断言）。
臂数（40 / 22）不进 CI 日志（门自证 stdout 不被回显，§6 R6）。

**CI 后源码树一致性**：`git diff --name-only cc92f6e43..HEAD -- . ':(exclude)fix-reports'` = **0 行** → CI 绿的那棵树就是本支的代码树，后续提交纯报告。

---

## §6 残留（P2，均带三件）

- **R1 · `_nonzero_delta` 只折纯字面量**：`+= 0 + 0` / `+= (0)` 这类带运算的零增量仍视为非零（`const_value` 不折算术）。触发条件：出现以算式书写的零增量。方案：在 `_nonzero_delta` 里对 `+ - * /` 做一次常量折叠。搁置理由：本仓零增量只有字面量形态，算式形态不存在；fail-open 方向安全（漏判不是误判）。
- **R2 · R10 的 `wrapped` 只认同名 fun**：ChatRepository 若把包装方法改名（如 `messageCount` → `countMessages`），该 reader 会退化为「无包装」→ 任何调用都判红（fail-closed，方向安全但会吵）。触发条件：包装层改名。方案：按 DAO 调用点集合与 repo 覆盖集合做差集，而不是按名字存在性。搁置理由：改名本身会同时改调用点，误红会在 CI 立刻暴露。
- **R3 · R5 的 (c) 只看 `return` 点与 tail**：块体函数若用「局部 val + 末尾返回局部」形态（`val capped = if (…) buildTruncatedText(rc) else rc; return capped`），`return capped` 的来源不含截断函数 → 误红。触发条件：漏斗改成局部绑定形态。方案：把 `local_sources` 接进 (c)。搁置理由：当前漏斗是「早退 return 截断值 + 兜底 return 原值」，不存在该形态。
- **R4 · 07-1 的读点定位靠「读 API + 名窗口」**：若请求体被复制进 `ByteArray` 后由 `String(bytes)` 解码（不出现任何读 API），第二次读不可见。触发条件：出现「先 readBytes 到变量、再多处解码字符串」的写法。方案：把「持有 requestFile 字节的局部」也纳入读点派生。搁置理由：当前只有一处 `readText()`；该形态会先触发 07-2 的消费链断裂（capture 的 lambda 里没有读点 → 红）。
- **R5 · 07-4 的 permit 局部派生只认 `val X = … tryAcquire`**：若把 permit 结论包成函数（`if (pool.tryReserve())`），守卫不派生 → 报「无分支派生 permit」（fail-closed 红）。触发条件：抽出 permit 辅助函数。方案：把 `tryReserve` 类判定也纳入派生。搁置理由：当前是实现内联 `synchronized { … tryAcquire() }`；误红方向安全。
- **R6 · 门自证 stdout 不进 CI 日志**：`test_scan` 只断言 `PARTSJSON_FUNNEL_SELFTEST=PASS` / `MODELSVC_SINGLE_READ_SELFTEST=PASS` 两个裸标记，臂数（40 / 22）只在本地 selftest 输出里可见。触发条件：需要 CI 侧见证臂数。方案：见 §7（把带臂数的标记注册进 `test_scan`，需主会话在并集树上改冻结面）。

---

## §7 注册项（交主会话，本支不改冻结面）

两门**已在** `test_scan.py` 在册，标记串保持不变：

- `("parts_json_funnel_check", "PARTSJSON_FUNNEL_SELFTEST=PASS", (REPO_ROOT,))`
- `("modelsvc_single_read_check", "MODELSVC_SINGLE_READ_SELFTEST=PASS", (REPO_ROOT,))`

两个标记的子串语义未被破坏（本轮未改标记文本）。若要在 CI 侧见证臂数，建议另加：

```python
("parts_json_funnel_check", "PARTSJSON_FUNNEL_SELFTEST=PASS", (REPO_ROOT,)),
("parts_json_funnel_check", "PARTSJSON_ARMS=40", (REPO_ROOT,)),        # 需门新增该行
("modelsvc_single_read_check", "MODELSVC_SINGLE_READ_SELFTEST=PASS", (REPO_ROOT,)),
("modelsvc_single_read_check", "MODELSVC_SINGLE_READ_ARMS=22", (REPO_ROOT,)),  # 需门新增该行
```

本波**未**新增这两行（冻结面纪律），理由见 §6 R6。

---

## §8 停止节点

- 分支 `fix/b7-t03-partsjson-modelsvc` 推远端 → `gh-actions-dispatch` → **CI 绿 = 本轮开发结束**。
- **未合并 main**；合并起手权在用户/主会话。
- 合并前需由主会话补齐：§5 CI 读数、三项核查（diff 逐文件 / head_sha 逐字符 / 独立证据）。
