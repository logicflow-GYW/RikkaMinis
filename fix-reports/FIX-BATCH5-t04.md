# FIX-BATCH5-t04 — `sysinfo_state_thread_check.py` + `tool_title_error_branch_check.py`

分支：`fix/b5-sysinfo-title`　基线：`base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`
（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow.py`）

改动面：**2 个门脚本**，`834 insertions(+) / 656 deletions(-)`；生产 `.kt` 0 行、
`scan.sh` / `test_scan.py` / 三个共享模块 0 行、未新建门（112 / 344 不变）。

---

## 1. 门与轴

| 门 | 见证 | 实锤失明 | 对照臂（须保持被抓） |
|---|---|---|---|
| `sysinfo_state_thread_check.py` | 6 | 5（06-1..06-5） | 1（06-6） |
| `tool_title_error_branch_check.py` | 6 | 5（10-1..10-5） | 1（10-6） |

轴分布：`family-hole` 5 · `parser-death` 4 · `spelling` 1 · `order` 1 · `dataflow` 1。

**本波两门的共同症**：判据锚「某个东西存在」（`synchronized(` 存在 / `resolveToolTitle`
存在 / `blocks.toList()` 存在 / `invalidateStorageSnapshot(` 存在 / 报告里出现 `bytes`），
而缺陷改变的是**这个存在物的效果**——监视器对象是新的、调用结果落在死变量、拷贝发生在清空
之后、调用在恒假分支里、被度量的不是写盘文件。所以修法统一是「把判据从**拼写/存在**推到
**位置/值流/可达性**」。

---

## 2. 见证原文（file / find / replace / 门为何绿）

### 06-1 `G06-A1`（family-hole，C publish-under-lock）
`ChatViewModel.kt`：`synchronized(lock)` → `synchronized(Any())`（`flush`）。
门绿因为只验证「`publishLocked` 被某个 `synchronized(...)` 包住」，不看括号里是什么。

### 06-2 `G06-A2`（order，D no-raw-handout）
`ChatViewModel.kt`：把 `current = null; blocks.clear()` 整段移到
`val drained = Window(...)` **之前**（两行代码一字不改，只换顺序）。
门绿因为「`Window` 的参数里有 `blocks.toList()`」和「函数体里有 `blocks.clear()`」是
两个独立断言，没有比较两者的先后。

### 06-3 `G06-A3`（family-hole，E convergence）
`ChatViewModel.kt`：`sysInfoCoalescer.append(...)` 包进 `if (1 == 2) { … }`。
门绿因为死分支判定是词法白名单（`false` / `0 == 1` / `1 == 0`），`1 == 2` 不在册。

### 06-4 `G06-A4`（parser-death，F off-main-state）
`ImageGalleryViewer.kt`：`withContext(NonCancellable + Dispatchers.Main.immediate) { sharing = false }`
→ `run { sharing = false }`。
门绿因为状态赋值正则带行首锚 `^\s*(name)\s*=`，行首变成 `run {` 即失配。

### 06-5 `G06-A5`（family-hole，C-INV delete-invalidation）
`FileBrowserViewModel.kt`：`invalidateStorageSnapshot()` → `if (false) invalidateStorageSnapshot()`。
门绿因为 C-INV 只用 `body.find(...)` 判「调用存在」，漏了本门其它臂早就在用的死分支判定
（同门臂间能力不对称）。

### 06-6 `G06-A6`（dataflow，D，对照臂）
`blocks = blocks.toList(),` → `blocks = blocks.also { blocks.toList() }`。**修前被抓，修后仍被抓。**

### 10-1 `G10-A1`（spelling，C-TITLE）
`FileWriteTool.kt`：`val toolTitle = resolveToolTitle(argsJson, NAME)` →
`val _unusedTitle = resolveToolTitle(argsJson, NAME)` + `val toolTitle = (NAME).toString()`。
门绿因为两条检查各自成立：resolver 调用「存在」；`toolTitle =` 右侧不是裸 `NAME`
（是 `(NAME).toString()`，括号剥离后仍是 `NAME).toString(`，字面量/常量正则都不中）。

### 10-2 `G10-A2`（family-hole，C-EDIT-ENC）
`FileEditTool.kt`：`if (isUnpairedSurrogateFailure(newContent))` → `if ((1 == 2) && …)`。
门绿同 06-3：`cond_truth` 只认 `false` 字面量。

### 10-3 `G10-A3`（parser-death，C-EDIT-ENC）
守卫体 → `{ run { return@run } }`。门绿因为「分支内出现 `return|throw` 即算终止」——
`return@run` 命中 `\breturn\b`，但只退出 lambda。

### 10-4 `G10-A4`（parser-death，C-EDIT-BYTES）
`val bytes = file.length()` → `val bytes = newContent.length`（属性读，无括号），
同时把真的 `toByteArray(Charsets.UTF_8).size` 留在死变量上。
门绿因为尺寸识别只认 `x.length()` / `x.count()` 两种带括号拼写，且只问「参数里有没有
`toByteArray(...).size` 子串」。

### 10-5 `G10-A5`（family-hole，C-ARTIFACT）
`ChatToolExecutors.kt`：在已通过的 `if (persistPath != null)` 块内，再给
`minis_url` 那一行套 `if (1 == 2) { … }`。门绿因为只判外层守卫合法 + 块内「含 `minis_url`」，
不递归子条件。

### 10-6 `G10-A6`（family-hole，C-EDIT-ENC，对照臂）
`if (false && isUnpairedSurrogateFailure(newContent))`。**修前被抓，修后仍被抓**（`a08-dead-guard`）。

---

## 3. 判据改造（旧形态 → 新形态）

### 3.1 共享原语接入（硬约束 1）

| 门 | 删除的自写实现 | 改用 |
|---|---|---|
| sysinfo | `blank_comments`（自写词法：注释/字符串/字符字面量扫描） | `_kotlin_valueflow.code_view`（= `mask_code`），失败 → `GateParseError` → **fail-closed 报违规** |
| sysinfo | `_paren_end` / `_brace_end` / `_block_end` | `_kotlin_ast.match_bracket` 薄别名 |
| sysinfo | `_split_args` / `_named_arg` | `_kotlin_ast.split_top` + `_named_arg_span` |
| sysinfo | `fun_spans` / `fun_body_span` / `brace_span`（自写花括号计数） | `_kotlin_ast.function_body` 派生的 `member_table` |
| sysinfo | `DEAD_COND_RE` + `dead_guarded`（死分支正则白名单） | `_kotlin_valueflow.path_feasible` + `const_bool`（`is_dead`） |
| sysinfo | `_is_copy_of_blocks` 里的 `.also/.apply` 自写剥离 | `_kotlin_valueflow.value_sources` / `peel_discarding` |
| tool_title | `strip_line_comments` / `strip_kotlin_comments`（自写两套） | `_kotlin_valueflow.literal_view`（= `mask_comments`），失败 fail-closed |
| tool_title | `_match_paren` / `_split_top` / `_brace_end` | `match_bracket` / `split_top` |
| tool_title | `cond_truth`（自写常量折叠，只认 `false`） | `_kotlin_ast.const_bool`（按 Kotlin 优先级折叠算术/比较/短路） |
| tool_title | `dead_guard_spans` + `inside_spans`（自写死分支 span 表） | `_kotlin_valueflow.path_feasible` |
| tool_title | `split_functions`（行式函数体切分） | `_kotlin_ast.function_body` |

### 3.2 逐条判据（旧 → 新）

**06-1 → C publish-under-lock**
- 旧：`synchronized\s*\([^)]+\)` 存在即算「在锁内」。
- 新：`live_sync_bodies(code, lock_names)` **只登记监视器稳定的块**；`stable_monitors(expr, lock_names)`
  要求表达式是裸字段名且属于类内 `private val x = Any()`（`class_lock_names` 从类体派生，不写死 `lock`）；
  类内无此字段时退化为「必须是裸字段名，拒绝任何构造调用」。另加一条直接违规：块内监视器不是类锁 →
  `[C publish-under-lock] line N locks \`Any()\`, not the class lock …`（06-1 同时打出这条与
  「publish outside the lock」，因为该块也不再算 live sync）。

**06-2 → D no-raw-handout（顺序维度）**
- 旧：两个独立断言（参数含 `toList()` / 体内含 `clear()`）。
- 新：`window_ctor_args` 返回 `(args_text, 参数起点偏移)`；`_order_problems` 取 copy 的绝对偏移，
  在同一**成员体**内找 `blocks` 的破坏性变异（`clear` / `remove*` / `retainAll` / 裸重赋值），
  任何变异早于 copy → 违规。命名参数被排除（`innermost_bracket_at(...) != "("`），否则
  `blocks = blocks.toList(),` 自己会被当成重赋值。

**06-3 → E convergence（死分支）**
- 旧：`DEAD_COND_RE` 词法白名单。
- 新：`is_dead(code, pos)` = `not path_feasible(code, pos)`（`if` / `when` 包围条件经 `const_bool`
  折叠：`1 == 2`、`!true`、`false &&`、`when (false)` 全中）+ 显式折叠 `while (false)` 块
  （`const_bool` 判条件 + `match_bracket` 定区间）。收敛调用只在「活命中」里才算数。

**06-4 → F off-main-state（位置维度）**
- 旧：行首锚 `^\s*(name)\s*=`。
- 新：按**偏移**扫 `<state>(\s*\.\s*value)?\s*(=|\+=|-=)`；用 `innermost_bracket_at` 区分
  语句位置（`{`）与实参位置（`(`，命名参数）；RHS 为 `withContext(...)` 时不报（写落在调用方线程）；
  `// offmain-state-ok:` 逃逸保留。`run { sharing = false }`、`; sharing = false`、
  `if (true) sharing = false` 现在都在射程内。

**06-5 → C-INV（活性）**
- 旧：`body.find(INVAL_CALL)`。
- 新：活命中（`is_dead` 过滤）；有子串但全在死分支 → 专用文案「sits in dead code — it never runs」；
  失败路径（`if (!deleted) { … }`）/ catch 臂的错位判据保留（用的仍是活命中）。

**06-6 → D（值流，对照臂保持）**
- 旧：`.also(` 正则 + 特例。新：`value_sources(val)`（剥 also/apply/onEach/forEach、取
  let/run 尾表达式、elvis/if-else 两侧）后必须仍是 `blocks.toList()`。

**10-1 → C-TITLE（绑定）**
- 旧：`val toolTitle = <常量或字面量>` 才算违规。
- 新：`val toolTitle = <value>` 必须**绑定** resolver（`value_sources` 后含 `resolveToolTitle(`）；
  否则判「是否只由常量派生」`_is_constant_title`：表达式的「来源标识符」（排除 `.成员` 与 `f(` 被调者）
  全为 `[A-Z][A-Z0-9_]*` 常量/字面量 → 违规，并跟**一跳**局部 `val`（`val toolTitle = titleConst`）。
  带参数解析的形态（`args.optString(...)`、`runCatching { JSONObject(argsJson)… }`）不是常量派生，
  保留原有计数（V3/V7/V10/V11 臂计数不变）。

**10-2 / 10-3 → C-EDIT-ENC（守卫活性）**
- 旧：`cond_truth(cond) is False` + 分支里 `re.search(r"\b(return|throw)\b")`。
- 新：`guard_is_live` = 最内层包围 `if`（`_if_conditions`，`match_bracket` 取条件区间）
  + `const_bool(cond) is not False` + `_branch_exits`：
  `throw` 任意深度算终止；裸 `return` 必须在**分支自身深度**退出函数（`returns_exiting(..., fn_depth=brace_depth(分支内)+1)`），
  `return@run` / `return@let` 不算。

**10-4 → C-EDIT-BYTES（报价值解析）**
- 旧：只认 `x.length()` / `x.count()` 两种拼写 + `toByteArray(...).size` 子串。
- 新：`_reported_size_exprs` 从「消息字面量含 `bytes`」出发，取它的插值（`${…}` 与 `$name` 两形态），
  标识符回溯到**全部** `val/var` 右值（`_size_defs` + `expr_end`），用 `_measure` 识别四种拼写
  `length()` / `.length` / `.count()` / `.size`，取被测表达式的**根标识符**与写盘目标比较；
  `toByteArray(...)` 链豁免（真字节数）。

**10-5 → C-ARTIFACT（可达性）**
- 旧：`dead_guard_spans` span 表 + `inside_spans`。
- 新：`live_url_appends(block)` 用 `path_feasible` 判每处 `minis_url` 是否可达（外层守卫 + 任意嵌套
  恒假条件），仍要求同一行含 `output` 赋值。

### 3.3 新增 `--selftest` 臂（每条固化一条，变异即红）

sysinfo（35 → **40** 臂）：`a per-call monitor is not the class lock` · `draining before the copy is red` ·
`a dead coalescer call decided by arithmetic routes nowhere` · `a collapsed coroutine hop still writes off-main` ·
`a delete whose invalidation sits in dead code is red`。
（守卫**逃逸**面：`an explicit Main dispatcher is not off-main` / `offmain-state-ok` / `compliant fixture is green`
等 clean 臂全部保持绿。）

tool_title（30 → **35** 臂）：`a08-string-length-property` · `a08-dead-guard-arithmetic` ·
`a08-guard-lambda-return` · `V13 resolver into a dead val + toString constant` ·
`b03-dead-guarded url append (arithmetic)`。
（clean/精确面保持：`a08-compliant`、`a08-helper-delegation`、`a08-string-length`、`a08-char-count`、
`b03-compliant` 等全绿。）

说明：新臂都带**对照**——`a08-dead-guard`（`false &&`，修前就被抓）与
`a08-dead-guard-arithmetic`（`(1 == 2) &&`）并排，判据若退回词法白名单，前者绿后者红立刻暴露。

---

## 4. 四层证据

### L1 本门（真树 + 自测）

```
python3 scripts/scan/sysinfo_state_thread_check.py .            → rc=0
python3 scripts/scan/sysinfo_state_thread_check.py --selftest   → SYSINFO-STATE SELFTEST=PASS（40 臂）
python3 scripts/scan/tool_title_error_branch_check.py .         → rc=0
python3 scripts/scan/tool_title_error_branch_check.py --selftest→ TOOL_TITLE_ERROR_BRANCH_SELFTEST=PASS（35 臂）
```

### L2 反攻（官方驱动，硬验收）

```
verify_batch5.py --witnesses witnesses-A.json --repo . --mode full --only <gate> --out <json>
```

| 门 | 结果 |
|---|---|
| `sysinfo_state_thread_check.py` | **6/6 CAUGHT（rc=1, exact）** —— 06-1..06-5 由 BLIND 翻红，06-6 保持 |
| `tool_title_error_branch_check.py` | **6/6 CAUGHT（rc=1, exact）** —— 10-1..10-5 由 BLIND 翻红，10-6 保持 |

`find_hits = 1` ×12、`restored = true` ×12（原文件 sha256 逐字节还原）。
输出：`probe/t04-sysinfo_state_thread_check.json`、`probe/t04-tool_title_error_branch_check.json`。

**报错指向核对**（每条红都落在它自己的判据上，不是「顺带红」）：

| 见证 | 门输出的判据行 |
|---|---|
| 06-1 | `[C publish-under-lock] line 4707 locks \`Any()\`, not the class lock …` + `line 4708 calls publish outside the lock` |
| 06-2 | `[D no-raw-handout] line 4721 drains \`blocks\` BEFORE the window copies it …` |
| 06-3 | `[E convergence] appendSystemInfo does not route through a coalescer instance — only in dead code …` |
| 06-4 | `[F off-main-state] ImageGalleryViewer.kt:334 assigns Compose state \`sharing\` on a background dispatcher` |
| 06-5 | `[C-INV] FileBrowserViewModel.kt:410 (deleteItem) … the cached-scan invalidation sits in dead code …` |
| 06-6 | `[D no-raw-handout] window built with a live list (copy result discarded by \`.also { … }\`)` |
| 10-1 | `\`val toolTitle\` is built from constants only (\`(NAME).toString()\`) …` |
| 10-2 | `[C-EDIT-ENC] the guard applied to \`newContent\` cannot reject: its \`if\` condition is constant-decided …` |
| 10-3 | `[C-EDIT-ENC] … or its branch does not leave the function (\`run { return@run }\` …)` |
| 10-4 | `[C-EDIT-BYTES] the reported size comes from \`bytes = newContent.length\` …` |
| 10-5 | `artifactWriter() \`persistPath\`: the minis_url append cannot run — it sits on an infeasible path …` |
| 10-6 | `[C-EDIT-ENC] …` (对照臂，修前后一致) |

### L3 邻门（10 道，全 rc=0）

`file_provider_paths_coverage` · `offload_host_path_check` · `browser_tabs_lifecycle` ·
`memory_ui_wiring` · `tool_result_image_branch` · `offload_media_b64_wire` · `i18n` ·
`session_provider_enabled` · `provider_rpc_symmetry` · `chat_ui_interaction_check`

（本门改动只碰两个门脚本，邻门用来证明共享原语的使用方式没有把别人的判据带红。）

### L4 全量

- **`bash scripts/scan/scan.sh`**：沙箱并发窗口在 16/112 处被杀（`Errno 38`），按任务书 §5-4 的
  fallback 改为**逐门循环 + 断点续跑**（`probe/t04_scan_batch.sh`，进度文件
  `probe/t04-scan-progress.txt`）：**110/110 个门脚本 rc=0**（`tts_voice_and_sanitizer_check` 与
  `calendar_events_nullable_predicate_check` 需 `--root .` 形态，单独跑均 rc=0）。
- **`python3 scripts/scan/test_scan.py`**：单进程执行到 `exec_gate_release_check` 被 SIGKILL
  （沙箱回收，非夹具失败），改按 `main()` 的 46 个测试组分 5 段跑（`probe/t04_testscan_half.py`，
  每段继承真实 `REPO_ROOT`）：**94 + 119 + 107 + 4 + 20 = 344 cases，0 failures**。
- 反攻残留：`git status --porcelain src/` → **空**。

---

## 5. CI

- workflow run：**#2694**（run id `40213973019`），job `121231001816`，`attempt 1`，**conclusion = success**
- `head_sha = 9f815f791edf1adfafb262653b1ee452774c5fb9`，与本地 `git rev-parse HEAD` **逐字符一致**
- 26 个步骤：25 success、**唯一 skipped = `Publish to Releases`**（main 门控，分支构建预期跳过）
- 日志（1,486,044 B / 17,851 行）读数：
  - `║  Summary: 112 passed, 0 failed` —— **恰 1 条**（带框线前缀）；其余带 `Summary:` 的行均为子套件
    读数（5 / 10 / 14 / 15 / 25 / 28 / 36 passed 与 `0 violation(s) found`）
  - `ALL 344 CASES PASS` —— **恰 1 条**
  - 门头 `[N/112]` —— 112 条，编号 **112 个互异**
  - ` e: `（Kotlin 编译错误行）—— **0**
  - 含 `FAILED` 的行 6 条：逐条确认全部是 **PASSED 用例名的子串**
    （`… produces FAILED without calling provider PASSED`、`… reason PERSISTENCE_FAILED … PASSED`、
    `phase FAILED maps…`、`terminal FAILED maps…`、`reason EXECUTION_FAILED …`、
    `… legal FAILED terminal PASSED`）
  - 本支两门的自证标记在 CI 日志各出现 1 次：`SYSINFO-STATE SELFTEST=PASS`、
    `TOOL_TITLE_ERROR_BRANCH_SELFTEST=PASS`（证明新增的 5+5 条臂在 CI 里真的跑了，不是只在本机）

**分支 CI 绿 = 确定性停止节点。未合并 main。**
（报告提交在 CI 绿之后，源码树与 CI 绿树等价：
`git diff --name-only 9f815f79 HEAD -- . ':(exclude)fix-reports'` → 0 行。）

---

## 6. 残留（每条带触发条件 / 触发后方案 / 搁置理由）

- **R1 常量标题两跳以上不跟**
  `_is_constant_title` 跟一跳局部 `val`（`val toolTitle = titleConst`）。两跳
  （`val a = NAME; val b = a; val toolTitle = b`）不跟。
  触发条件：出现「两跳以上常量标题」的外部见证。
  触发后方案：深度上限 3 → 5，或改用 `decl_sites` + 遮蔽/重赋值检查（`is_single_assignment`）在文件级建常量图。
  搁置理由：无见证；一跳已覆盖最自然的写法，再深一层就是主动构造的绕过，且成本从「解析」升为「建图」。

- **R2 `do { … } while (false)` 未折叠**
  `is_dead` 折叠 `while (false)` 的**块体**，但 `do-while` 先执行后判断，语义上不是死代码——刻意不覆盖。
  触发条件：出现把调用藏进 `do { } while (false)` 且判据因此放过的见证。
  触发后方案：显式识别 `do` 块（它不是死代码，需要在「活性」判据里当作活路径处理，而不是死）。
  搁置理由：语义不同，误折叠会让门变瞎（把活代码判死）。

- **R3 monitor 判据的退化分支**
  类内**没有**任何 `private val x = Any()` 字段时，`stable_monitors` 退化为「任意裸字段名可接受」，
  此时 `synchronized(otherField)` 锁错位不会被抓。
  触发条件：被审类改成别的锁构造方式（如显式传 monitor 参数 / 用 `ReentrantLock`）后出现该形态见证。
  触发后方案：从类内全部 `private val` 派生候选集合并要求「监视器集合与状态集合相交」，或引入
  `ReentrantLock` 形态的派生。
  搁置理由：今天唯一的被审类有 `private val lock = Any()`，退化分支不可达；硬收紧会在类改锁构造时误红。

- **R4 `_order_problems` 的成员归属取首个包含 span**
  若 copy 站点位于嵌套 lambda/匿名对象里，`members` 里第一个包含它的成员可能比真实归属更外层
  （`member_table` 不记录嵌套对象）。此时「同一成员体内」的偏移比较范围偏大。
  触发条件：出现把 `blocks.clear()` 移进独立 helper / 匿名对象的见证。
  触发后方案：用最小包含 span（按 `start` 降序取第一个包含点），或对 helper 做调用图一跳。
  搁置理由：现存两个见证都在 `publishLocked` 体内；跨函数顺序在语义上也不该由本判据裁定（那属于「拷贝结果被外部清空」的另一个判据）。

- **R5 破坏性变异只认直接接收者**
  `DESTRUCTIVE` 认 `blocks.clear()` / `remove*` / `retainAll` / 裸重赋值；`blocks.subList(0, n).clear()`
  这类间接破坏不认。
  触发条件：同族见证（判据 D 的 order 面）。
  触发后方案：解析链式接收者（取第一个 `.` 之前的根，与 copy 根比对）。
  搁置理由：仓内无此形态；加了要在 `innermost_bracket_at` 之外再引一层表达式解析，收益与成本不匹配。

---

## 7. `test_scan.py` 注册项（交主会话）

两门**都已在册**，标记串未改动，臂数变化不需要改注册表：

| 注册项 | 位置 | 标记 | 臂数 |
|---|---|---|---|
| `("sysinfo_state_thread_check", 'SYSINFO-STATE SELFTEST=PASS', (REPO_ROOT,))` | `test_scan.py:2701` | 未变 | 35 → 40 |
| `("tool_title_error_branch_check", "TOOL_TITLE_ERROR_BRANCH_SELFTEST=PASS", (), (REPO_ROOT,))` | `test_scan.py:2838` | 未变 | 30 → 35 |

**结论：本支不需要主会话新增/修改任何注册项。** 夹具总数 344 不变（本支未新建门）。

---

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t04

# L1 本门
python3 scripts/scan/sysinfo_state_thread_check.py . ; echo rc=$?
python3 scripts/scan/sysinfo_state_thread_check.py --selftest | tail -1
python3 scripts/scan/tool_title_error_branch_check.py . ; echo rc=$?
python3 scripts/scan/tool_title_error_branch_check.py --selftest | tail -1

# L2 反攻（12 条见证全部应 CAUGHT，restored=true）
git checkout -- src/
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json --repo . --mode full \
  --only sysinfo_state_thread_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t04-sysinfo_state_thread_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-A.json --repo . --mode full \
  --only tool_title_error_branch_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t04-tool_title_error_branch_check.json

# L3 邻门
for g in file_provider_paths_coverage offload_host_path_check browser_tabs_lifecycle \
         memory_ui_wiring tool_result_image_branch offload_media_b64_wire i18n \
         session_provider_enabled provider_rpc_symmetry chat_ui_interaction_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1; echo "$g rc=$?"
done

# L4 全量（沙箱并发窗口下的分段跑法）
sh /var/minis/shared/work/meta-audit-v5/batch5/probe/t04_scan_batch.sh   # 反复执行直到 110/110
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t04_testscan_half.py 0 22     # 94
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t04_testscan_half.py 22 33    # 119
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t04_testscan_half.py 33 44    # 107
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t04_testscan_half.py 44 45    # 4
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t04_testscan_half.py 45 46    # 20
git status --porcelain src/    # 必须为空
```
