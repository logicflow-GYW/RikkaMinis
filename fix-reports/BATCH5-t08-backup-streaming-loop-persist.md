# BATCH5-t08 — `backup_streaming_export_check` / `loop_persist_slice_check` 六见证加固

## 0. 基线与交付边界

- **基线**：`e145ec961`（= main `6ac77c6b2` + 第五批共享原语 `scripts/scan/_kotlin_valueflow.py`，26 臂自测 / 1308 个 `.kt` 零失败）。
- **分支**：`fix/b5-backup-loop`（本波并行支之一，文件集与其他支互斥）。
- **输入**：`batch5/witnesses-B.json` 中 `backup_streaming_export_check.py` 6 条（01-1…01-6）+ `loop_persist_slice_check.py` 6 条（11-1…11-6），**12/12 `FALSIFIED`**。基线机械复证：`probe/t08-prefix-backup.json` / `probe/t08-prefix-loop.json` 均 `{'BLIND_CONFIRMED': 6}`。
- **改动面**：**只改这两个门脚本**（`+1015 / −681`）+ 本报告。
  - 生产 `.kt`：**0 行**（`git diff --name-only` 只有 2 个 `scripts/scan/*.py` + 报告）。
  - `scan.sh` / `test_scan.py`：**零触碰**（冻结面，门注册留给主会话）。
  - `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py`：零改动（共享原语只读使用）。
  - 门数 **112 不变**、新建门 **0**；两门 `--selftest` 臂：backup 19 → **27**，loop 10 → **24**（+ 21 violating / 3 clean）。
- **纪律**：改动阶梯停在第 2 级（复用共享原语，未新建第三套词法器）；两条门内自写的 `strip_comments` / `brace_block_span` / `fun_span` / `paren_block` / `dead_guarded` / `code_lines` 派生逻辑全部删除，改用 `_kotlin_lexer` + `_kotlin_ast` + `_kotlin_valueflow`。

## 1. 逐条：旧判据为什么是瞎的（12 条，全部机械复现过）

### 1.1 `backup_streaming_export_check.py`

| 见证 | 轴 | 旧判据（基线行号） | 为什么绿 | 缺陷怎么被放回来 |
|---|---|---|---|---|
| 01-1 | parser-death | `runLocked` 体由 `fun_span`+`brace_block_span`（L162–196）切，`strip_comments`（L116–127）保留字符串内容 | 花括号计数对引号无感知：`Log.w(TAG, "… rotateLocalAutoBackups(app) } …")` 里的 `}` 让 `depth` 提前归零 → runLocked 的扫描窗口在 `Log.w` 处截断 | 截断点之后（仍在真实函数体内）调用被禁的 `ConfigBackup.export(` 不再被看见；被"找到"的轮转调用其实是日志字符串里的文本 → 整段备份文档被重新装配成 String（OOM 形状）且本地轮转完全不执行 |
| 01-2 | family-hole | `DEAD_COND_PAT`（L158–159）硬编码 5 个字面量死条件，`dead_guarded`（L198）用它判死分支 | `if (1 == 2) rotateLocalAutoBackups(app)` 不匹配那 5 项 | 轮转调用文本仍在、每夜自动备份照跑，但 `filesDir/backup-autos` 永不裁剪 → 无界增长到写满设备（正是 deep-audit-1027 B-04 关闭的缺陷） |
| 01-3 | anchor | `check_arm2`（L295–298）只做 `"fun sanitizeAutoBackupStamp(" not in sync_code` | 只看声明，从不读函数体 | sanitizer 保留签名、体内 `if (trimmed.isNotEmpty()) return trimmed` 直通 → 远端 `displayName` 的 `/`、`\`、`..` 全部放行，路径穿越/覆写重新成立（deep-audit-1031 A-07） |
| 01-4 | dataflow | `check_arm3`（L344–368）对 `File(…)` 括号文本做 `\bstamp\b` 正则命中 | 文本里出现 `stamp` 就算"用了消毒结果" | `File(dir, (PREFIX + rawStamp + ".json").also { stamp })`：`.also` 丢弃 lambda 值、返回接收者，真正被求值的是**原始远端名** |
| 01-5 | dataflow | `check_arm4`（L376–385）判"`listLocal(` 那一行是否含 `Dispatchers.IO`"子串 | 同行出现即算已切到 IO | `listLocal(context).also { Dispatchers.IO }` —— discarding lambda，什么都没调度，目录遍历仍在主线程 |
| 01-6 | order | `check_arm5`（L394–413）数 `webDavUploadPending = false` 出现 ≥3 次 | 计数满足即绿 | `= false; = true; showSecretWarning = true` —— **块内末次写赢**，清位被立刻覆盖；随后确认"本地导出"时读到 stale `true`，含密钥的备份被上传到 WebDAV |

### 1.2 `loop_persist_slice_check.py`

| 见证 | 轴 | 旧判据（基线行号） | 为什么绿 | 缺陷怎么被放回来 |
|---|---|---|---|---|
| 11-1 | parser-death | `SLICE_CALL`（L46）逐行匹配 `buildTurnParts(loopState.allToolBlocks, X,` | 调用一旦换行，没有任何单行能匹配；另两处单行调用仍让 `sites` 非空 | `buildTurnParts(\n allToolBlocks, turnStartBlockIndex, toolInputMap)` 完全隐形 → 每轮重新切片，续写前段永不落库（ISSUE-01 原缺陷） |
| 11-2 | family-hole | R4（L346）`ADVANCE in guard` 子串；R9（L409–413）只看外层是否**存在**某个 `x != null` | 推进语句文本仍在守卫块内；`if (1 == 2)` 不在任何死分支枚举里 | 守卫内的推进是死代码 → 索引永不前进 → 下一次 persist 重复发送上一轮已落库的块 |
| 11-3 | family-hole | R10（L435–444）**逐条**调用 `feasible(cond, domains)` | 每层守卫单独看都可满足 | `if (persistedRowId != null) { … if (persistedRowId == null) { backfill } }` 两层自相矛盾 → 回填永不执行，压缩失去 DB 锚点 |
| 11-4 | family-hole | R6（L375–378）取 `clear()` 后 5 行窗口 + `RESET in window` | `= 0` 文本还在窗口内；R8 只看右值是 `"0"` | `if (1 == 2) loopState.unpersistedBlockStart = 0` → 队列中断时列表清空、索引仍指向上一气泡 |
| 11-5 | parser-death | R7（L381）在**原始文本**上 `FIELD not in state` | 注释里的声明也满足子串 | `// var unpersistedBlockStart: Int = 0` + `var unpersistedBlockStart: Int` 配 `get() = 0` / `set(_) {}` → 声明文本一致、所有写入被吞掉，引擎一行不改即可编译 |
| 11-6 | spelling | `ASSIGN_INDEX`（L98）锚 `loopState\.unpersistedBlockStart` | 接收者拼写一变就看不见 | `(loopState).unpersistedBlockStart = turnStartBlockIndex`（或 `loopState.apply { unpersistedBlockStart = … }`）→ 索引被污染成每轮起点 |

**共同病因**：判据锚在**拼写/存在/单行文本**，而缺陷发生在**结构、可达性、值流、末次写**维度。两门同族，故同批修。

## 2. 改法（每条一句可复核）

结构层全部来自共享原语：`code_view` / `mask_comments`（`_kotlin_lexer`）、`function_body` / `match_bracket` / `if_blocks` / `strip_parens`、`call_args` / `value_sources` / `peel_discarding` / `expr_end` / `path_feasible` / `guard_feasible`（`_kotlin_valueflow`）。**词法失败 = fail-closed 红**。

### `backup_streaming_export_check.py`

1. **arm1（01-1 + 01-2）**：`function_body(code_view(src), "runLocked")` 取函数体（**严格花括号匹配、字符串/注释已置空、偏移不变**），禁调检查 `ConfigBackup.export\s*\(` 在**整个函数体**内执行；轮转调用必须**存在且 `path_feasible`**（共享常量折叠 + Kotlin 优先级 + `when` 分支）。
2. **arm2（01-3）**：读 `sanitizeAutoBackupStamp` 的**函数体**（结构视图定位、字面量视图读内容），要求 ①存在**可达**的 `return null`（拒绝路径）；②体内实际出现对 `/`、`\`、`..` 的拒绝字面量。声明还在、拒绝没了 → 红。
3. **arm3（01-4）**：目的地判定改成**值流**——`File(…)` 的每个实参经 `value_sources` 解析，`peel_discarding` 会把 `.also/.apply` 剥到接收者，因此 `(raw + …).also { stamp }` 解析出的源里没有 `stamp` → 红；同时保留"结果被绑定"与"null 被拒绝"两条。
4. **arm4（01-5）**：先从 `withContext(` 的**实参**判定 `Dispatchers.IO`，再取其**尾随 lambda 区间**，要求每个 `listLocal(` 站点**落在区间内**；`Dispatchers.IO` 只出现在同行/丢弃 lambda 里 → 红。
5. **arm5（01-6）**：清位必须 ①`path_feasible`（可达），②在其**所在块**内是**末次写**；`= false` 后跟 `= true` → 红（同块只保留最后一次写）。
6. **arm6（无见证，仅按纪律换原语）**：`onFetch` 区域、`finally` 区域改由 `match_bracket` 定位（顺手删掉自写 `_braced_region` / `paren_block`），判据不变（认领/释放末次写/refresh 活性）。
7. 顺带删掉门内自写 `strip_comments`、`brace_block_span`、`fun_span`、`_paren_block`、`DEAD_COND_PAT`、`dead_guarded`。

### `loop_persist_slice_check.py`

1. **R1 / R2（11-1）**：调用点由 `call_sites()` 全文定位（`call_args` 括号配平 + 深度 0 逗号切分），实参经 `squash()`（去空白 + 剥外层括号）比对；**实参个数不足或首参不是 `loopState.allToolBlocks` 时 fail-closed 红**（而不是静默跳过）。
2. **R3**：`turnVisibleBlocks` 的右值从 `expr_end` 取整段，按 `loopState.allToolBlocks.drop(X)` 形态解析，`X` 必须仍是每轮索引。
3. **R4 / R5（11-2）**：用 `path_feasible` 判推进语句可达，并要求守卫块内**至少一条可达**的推进；`if (1 == 2)` 下的推进 → 红。
4. **R6（11-4）**：`clear()` 所在块内经 `_writes_in` 找 `= 0`，且必须**可达**；死守卫下的重置 → 红。
5. **R7（11-5）**：在 `code_view` 上匹配 `var unpersistedBlockStart: Int = 0`（注释不算），并**拒绝紧随其后的自定义 `get(` / `set(`**（访问器可钉住/吞掉写入）。
6. **R8 / R9（11-6）**：写入点按**属性名**找，接收者单独解析——`loopState.` / `(loopState).` / `this.loopState.` 归一为 `loopState`；`loopState.apply{} / run{} / also{} / let{} / with(loopState){}` 视为隐式接收者；**解析不出 = fail-closed 红**。右值只允许 `0` 或 `loopState.allToolBlocks.size`，`+=`/`-=` 红；推进还必须在**可达**的 `x != null` 成功守卫内。
7. **R10（11-3）**：新增 `conds_feasible()` —— 把全部外层守卫**联合求交**（null-ness 布尔 + 整数区间，`!` 取反后翻转算子，`||` 保守跳过），自相矛盾的栈（`rowId != null` 与 `rowId == null`；`i >= 0` 与 `i < 0`）→ 红；另加"回填右值必须是守卫所测的那个 id"。

## 3. 截面

| 项 | 基线 | 现在 |
|---|---|---|
| 门脚本 | 112 | **112**（0 新建） |
| `test_scan.py` 用例 | 344 | **344**（未触碰） |
| `backup_streaming_export_check --selftest` | 19 fixture | **27 fixture** |
| `loop_persist_slice_check --selftest` | 10 fixture | **24 臂**（21 violating + 3 clean） |
| 生产 `.kt` 改动 | — | **0 行** |

新增的 11 条臂全部来自本批 12 条见证的形状（01-1…01-6、11-1…11-6，其中 11-2/11-3 各留两条同族变体：死常量推进 + `>=0`/`<0` 区间矛盾；11-6 留 `(loopState)` 与 `apply{}` 两种拼写），另补 3 条 clean 臂守住"合法换行 / `with(loopState)`"不被误伤。

## 4. 本地验证

**L1 门自身**
- `backup_streaming_export_check --selftest` → `BACKUP_STREAMING_EXPORT_SELFTEST=PASS`（27/27 fixture 按**自己的**判据翻红；每条 dirty fixture 用 `contains=` 钉住是哪一条判据响的）。
- `loop_persist_slice_check --selftest` → `LOOP_PERSIST_SLICE_SELFTEST=PASS (21 violating + 3 clean arms)`。
- 两门真树：`OK (arm1…arm6 green)` / `PASS loop_persist_slice_check (…)`，rc=0。

**L2 反攻（拿原始见证再攻一次）** —— `verify_batch5.py`，find 必须恰好命中 1 次、restore 后 sha256 逐字节比对：

| 门 | 基线 | 现在 |
|---|---|---|
| `backup_streaming_export_check.py` | `{'BLIND_CONFIRMED': 6}` | **`{'CAUGHT': 6}`**（`probe/t08-post-backup.json`） |
| `loop_persist_slice_check.py` | `{'BLIND_CONFIRMED': 6}` | **`{'CAUGHT': 6}`**（`probe/t08-post-loop.json`） |

12/12 全部 `rc=1, exact`，restore 12/12 一致，`git status --porcelain src/` 事后为空（无残留变异）。

**L3 邻门回归**（同文件读者，9 门）：`backup_remap_rollup_check` / `ime_inset_guard` / `loop_tool_status_transition_check` / `provider_remember_key_check` / `readonly_mount_write_guard` / `sysinfo_state_thread_check` / `truncated_call_guard_check` / `turn_tail_finalize_order` / `compact_pin_truth_check` → **9/9 OK**。

**L4 全量**：`sh scripts/scan/scan.sh` 在本沙箱同样遇到 **S28 并发窗口的 ENOSYS 风暴**（`python3: Function not implemented` ×288，门根本没执行，不是门红），三次重试均未跑完 ⇒ 全量 112/0 与 `test_scan` 344 的权威读数以**本支 CI** 为准（CI 日志按既有口径逐条核对：`Summary: 112 passed, 0 failed` 恰一条、门头 112 互异、`ALL 344` 恰一条、`e:` 0、含 `FAILED` 的行逐条确认是 PASSED 用例名子串）。

## 5. CI（分支 `fix/b5-backup-loop`）

- **run `40203536128`** · job `121199557903` · **attempt 1** · `conclusion=success` · `head_sha=fcf9fef29f260009a5e44aa6734e0ffd9a3c1d3a`（与本地 `git rev-parse HEAD` **逐字符一致**）。
- 26 步：唯一 `skipped` = `Publish to Releases`（main 门控）；`Run pre-build scan gate` / `Run unit tests` 等 25 步全 `success`。
- 日志 1,486,035 B 逐条核（`probe/t08-ci.log`，判读 `probe/t08-ci.json`）：
  - 带框线 `Summary: 112 passed, 0 failed` **恰一条**（其余 8 条 `Summary:` 是子套件 28/5/14/36/15/25/10 与 `0 violation(s) found`）；
  - 门头编号 `[N/112]` **112 个互异**；
  - `ALL 344` **恰一条**；` e: ` **0**；
  - 含 `FAILED` 的 6 行逐条确认全是 **PASSED 用例名子串**（`… produces FAILED without calling provider PASSED` 等）。
- 本支两门在 CI 里的读数：
  - `✅ backup_streaming_export_check selftest has teeth (BACKUP_STREAMING_EXPORT_SELFTEST=PASS)` + `real tree green`；
  - `✅ loop_persist_slice_check selftest has teeth (SELFTEST=PASS loop_persist_slice_check (6 violating fixtures red, compliant fixture green))` + `real tree green`；
  - 门的实际树扫描行也进了日志：`backup_streaming_export_check: OK (arm1 + arm2 + arm3 + arm4 + arm5 + arm6 green)`。
- **源码树 = CI 绿树**：报告回填是一个**只动 `fix-reports/`** 的后续提交，`git diff --name-only fcf9fef29..HEAD -- . ':(exclude)fix-reports'` = **0 行**。
- **门自证臂数不进 CI 日志**（既有已知缺口）：`--selftest` 的逐臂输出被 `test_scan` 捕获后只断言标记，不回显；本波新增臂的**计数**因此靠本报告 + 门内 `--selftest` 输出承载，注册建议见 §6 R1/R2。

## 6. 残留与注册项（交给主会话收口）

1. **R1（注册）**：`"loop_persist_slice_check"` 在 `test_scan.py:2695` 的标记断言是**冻结文件里写死的旧句子** `SELFTEST=PASS loop_persist_slice_check (6 violating fixtures red, compliant fixture green)`。本支**保留该句原样**（`--selftest` 输出里带 `[legacy marker: …]` 说明它是"原六条夹具"的标记，仍真），并新增第二行 `LOOP_PERSIST_SLICE_SELFTEST=PASS (21 violating + 3 clean arms)`。**建议主会话把注册改指向新标记**，旧句子随后可删。
2. **R2（注册）**：`backup_streaming_export_check` 的标记不变（`BACKUP_STREAMING_EXPORT_SELFTEST=PASS`），无需改注册；但**无独立负向夹具断言**（其臂只在门内 `--selftest` 里），如需外部见证请按 `test_da1017_external_fixtures` 模式补一对 clean/dirty（`VIOLATION_ROTATION_CONST_COMPARE` 与 `COMPLIANT_AUTO`）。
3. **R3（已知边界，不修）**：arm3 的"目的地必须由消毒值派生"只要求**块内某个** `File(…)` 用到它。若有人把消毒值用于 `File(dir, …)` 之外的另一个无用文件、真正写入仍用原始名，本门不报（`writeText` 的目标未被追踪）。触发条件：once `.kt` 侧出现多目的地写备份文件；触发后方案：把 `local.writeText(` 的接收者纳入 `value_sources` 追踪。
4. **R4（已知边界，不修）**：`conds_feasible` 对 `||` 与无法解析的条件一律"视为可满足"（宁漏不误红）。触发条件：出现 `if (a == null || b == null)` 型矛盾见证；触发后方案：接 disjunction 分支求交。
5. **R5（已知边界，不修）**：arm5 的"同块末次写"以**花括号块**为单位，不建模 `return`/`throw` 提前退出；跨块的"设置 A 块、清 B 块"仍靠 ≥3 清位计数兜底。触发条件：出现跨块覆盖见证。
6. **R6（文档）**：本支已在两门 docstring 里写入 batch-5 六条见证的病因（`parser-death`/`family-hole`/`anchor`/`dataflow`/`order` 五轴），`scan.sh` 的门编号与说明文字由主会话统一更新。
7. **L4 环境缺陷**：S28（沙箱并发窗口下 fork/exec 随机 ENOSYS）本轮**第三次**复现（同 wide4-02 记录），已在本报告记录；门本身未受影响，CI 侧无此问题。

## 7. 向后兼容（老臂一条没丢）

- `backup_streaming_export_check`：原 19 条 fixture **全部保留且仍按原名翻红**，仅两处消息措辞随判据重写（`violation-fetch-raw-path` 期望片段由 `never read` 改为 `is decorative`；`violation-sanitizer-*` 相关两条按新判据断言"体内拒绝缺失"）。合规夹具的 sanitizer 由 `= raw`（本身已不满足"必须拒绝"）换成真实 reducer —— 老夹具原本就是**假合规**，改后才真正合规。
- `loop_persist_slice_check`：原 10 条 fixture 全部保留（`BAD_NO_ADVANCE` 的刀口从"无工具终态的推进"改为"工具路径的推进"，覆盖 R5 而不是重复覆盖 R4），新增 14 条。
- 两门对外契约（CLI 形态、退出码、门名、真树判定）**零变化**：`python3 scripts/scan/<gate>.py <root>` 与 `--selftest` 语义不变。
