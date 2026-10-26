# FIX-BATCH7-t06 — `fastio_tar_bounds_check` + `marker_boundary_check` 判据加固

- 分支：`fix/b7-fastiotar-marker`
- 基线：`base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow.py`）
- 门提交：`9101923ab1eeda79a490500faa824f23446c4289`（改动面 = 2 个门脚本，+972 / −481）
- 改动面之外零触碰：生产 `.kt` **0 行**、`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` 全未动，门数 112 / 夹具 344 不变。

---

## 1. 门与轴

12 条见证（A 组报告 §09 / §14），其中 10 条实锤失明、2 条为对照臂（修前已 CAUGHT，修后必须保持 CAUGHT）。

| 见证 | 门 | 轴 | 修前 |
|---|---|---|---|
| 09-1 | `fastio_tar_bounds_check.py` | `family-hole` | BLIND_CONFIRMED |
| 09-2 | `fastio_tar_bounds_check.py` | `family-hole` | BLIND_CONFIRMED |
| 09-3 | `fastio_tar_bounds_check.py` | `dataflow` | BLIND_CONFIRMED |
| 09-4 | `fastio_tar_bounds_check.py` | `dataflow` | BLIND_CONFIRMED |
| 09-5 | `fastio_tar_bounds_check.py` | `family-hole` | BLIND_CONFIRMED |
| 09-6 | `fastio_tar_bounds_check.py` | `family-hole` | CAUGHT（对照臂） |
| 14-1 | `marker_boundary_check.py` | `dataflow` | BLIND_CONFIRMED |
| 14-2 | `marker_boundary_check.py` | `family-hole` | BLIND_CONFIRMED |
| 14-3 | `marker_boundary_check.py` | `dataflow` | BLIND_CONFIRMED |
| 14-4 | `marker_boundary_check.py` | `spelling` | BLIND_CONFIRMED |
| 14-5 | `marker_boundary_check.py` | `parser-death` | BLIND_CONFIRMED |
| 14-6 | `marker_boundary_check.py` | `family-hole` | CAUGHT（对照臂） |

**两门的共症**（与 batch5 同族、但都落在「值 / 活 / 消费」而非结构）：判据锚的是**某个拼写或某个 token 的存在**，缺陷动的是**这个 token 的效果**——

- 09-1 合规行保留、追加一行 `== NAME_LENGTH`：`<=` 形状的正则看不见 `==`；
- 09-2 token、位置、`refuseLinkedArchive` 调用全留着，条件被 `false &&` 短路；
- 09-3 `size - written` 子串仍在，被 `* 0L` 乘成 0；
- 09-4 `.sortedBy` 仍在 `writeTree` 体内，但结果被 `.also` 丢弃；
- 09-5 死字面量从左挪到右；
- 14-1 声明前缀在，值被 `.let { 0 }` 换掉；
- 14-2 cap 名字与比较算子同框，左操作数换成常量；
- 14-3 有界分支存在，扣下的切片换成 `""`；
- 14-4 `hit = !false`（等价于 true）绕过 `!= "true"` 的字面比较；
- 14-5 缩进对齐的 `}` 让 `text.find("\n}")` 提前截断函数体。

---

## 2. 见证原文（file / find / replace / why the gate stayed green）

### 09-1 `family-hole` — 追加一条原始上限
- file：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioTar.kt`
- find：`    if (utf8Length(name) <= NAME_LENGTH - 1) return "" to name`
- replace：上一行原样保留 + `    if (utf8Length(name) == NAME_LENGTH) return "" to name`
- 旧门为何绿：`check_tar()` 先验 `SPLIT_NAME_OK` 子串存在（仍在），再用 `STALE_CEILING_RE = utf8Length\((?:name|prefix|rest)\) <= (?:NAME_LENGTH|PREFIX_LENGTH)(?!\s*-\s*1)` 扫行；新行用的是 `==`，正则不匹配。
- 真实后果：100 字节名被判"能装"，`putText` 只写 99 字节，`.py` → `.p`，多字节字符被切半，写入路径无任何报警。

### 09-2 `family-hole` — 符号链接拒绝被短路
- file：`.../offload/FastioOffloadHandler.kt`
- find：`if (Files.isSymbolicLink(archivePath)) { return refuseLinkedArchive(entry, "write", startedNs, args) }`
- replace：`if (false && Files.isSymbolicLink(archivePath)) { … }`
- 旧门为何绿：`archive_open_guard_shape()` 只做 `if "Files.isSymbolicLink(" in body: return "refuse"`，从不看这个 `if` 的条件是否可行。

### 09-3 `dataflow` — 补齐量乘 0
- file：`.../offload/FastioTar.kt`（`writeRegular` 的 copy 失败 catch）
- find：`writeZeros(sink, size - written)`
- replace：`writeZeros(sink, (size - written) * 0L)`
- 旧门为何绿：`re.search(r"\bsize\s*-\s*written\b", catch_seg)` 只找子串，词边界完好。
- 真实后果：header 已声明的 size 与实际落盘字节不符，后续成员全部被当作 payload 读。

### 09-4 `dataflow` — 排序结果被丢弃
- file：`.../offload/FastioTar.kt`（`writeTree` 的 listing）
- find：`stream.toList().sortedBy { it.fileName.toString() }`
- replace：`stream.toList().also { it.sortedBy { p -> p.fileName.toString() } }`
- 旧门为何绿：`if not SORT_RE.search(tree)` —— 只要函数体里出现 `.sortedBy {` 就算数，从不问排序结果是否成为 `children` 的值。
- 真实后果：成员顺序回到目录流顺序（`FastioTarTest` 的 order flake 直接回来）。

### 09-5 `family-hole` — 死字面量挪到右侧
- file：`.../offload/FastioSearch.kt`（`copyTree` 的 visitFile）
- find：`if (!attrs.isRegularFile) { out.filesSkippedSpecial++ ; return FileVisitResult.CONTINUE }`
- replace：`if (!attrs.isRegularFile && false) { … }`
- 旧门为何绿：`SPECIAL_DEAD_GUARD_RE = \bfalse\s*&&|\btrue\s*\|\|` 只匹配**左置**字面量。
- 真实后果：无 writer 的 FIFO 越过 guard 到达 `open(2)`，永久阻塞一个 offload worker 线程。

### 09-6 `family-hole` — 对照臂（左置 `false &&`）
- replace：`if (false && !attrs.isRegularFile) { … }`；修前已被 `SPECIAL_DEAD_GUARD_RE` 抓住，修后由常量折叠抓住（同一判据路径）。

### 14-1 `dataflow` — 解析值被 `.let { 0 }` 换掉
- file：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/PersistentShell.kt`（`internalScanMarker`）
- find：`val exitCode = internalParseMinisExitCode(combined.substring(idx), marker)`
- replace：同上 + `.let { 0 }`
- 旧门为何绿：声明正则 `^[ \t]*(?:val|var)\s+exitCode\s*=\s*internalParseMinisExitCode` 匹配到 `(` 就停，闭合括号之后的内容从未被看。

### 14-2 `family-hole` — 上限比较的左操作数换成常量
- find：`if (pending.length <= MAX_PENDING_MARKER_CHARS) {`
- replace：`if (0 <= MAX_PENDING_MARKER_CHARS) {`
- 旧门为何绿：旧 C2 只验三件事——无 `||`、不是 `CONST_TRUE_GUARD`、`(<=|>=|<|>)\s*MAX_PENDING_MARKER_CHARS` 能匹配；`0 <= CAP` 三条全过。
- 真实后果：上限恒真 → 真 marker 被切短的前缀永远扣着不吐（命令永远不完成）；反之 `>=` 会把短的真 marker 直接冲掉。

### 14-3 `dataflow` — 扣下的窗口被丢掉
- find：有界分支里的 `return MarkerScanResult(hit = false, …, keptTail = pending)`
- replace：`keptTail = ""`
- 旧门为何绿：① 站点遍历第一句是 `if hit_expr != "true": continue`，`hit = false` 的站点整个被跳过；② 旧 C2 只看条件表达式，从不看花括号体。

### 14-4 `spelling` — `hit = !false`
- find：`val pending = combined.substring(idx)`（在 `pending` 之前插入一行）
- replace：插入 `if (1 == 1) return MarkerScanResult(hit = !false, beforeMarker = …, exitCode = -1, keptTail = "")`
- 旧门为何绿：`if hit_expr != "true": continue`——只有字面 `true` 算命中；`HIT = re.compile(r"hit\s*=\s*true")` 同样不匹配。

### 14-5 `parser-death` — 缩进对齐的 `}` 截断函数体
- find：有界分支的整块 `if (pending.length <= CAP) { return MarkerScanResult(…) }`
- replace：把该 `if` 改成 `&& false`，其右花括号顶格写成 `\n}`，其后追加一行裸 `return MarkerScanResult(hit = true, …, exitCode = -1, …)`
- 旧门为何绿：`function_span()` 用 `text.find("\n}", m.start())` 找函数结束，第一个顶格 `}` 就截断；脚本上方虽有配平花括号的 `brace_block()`，`function_span` 没用它。
- 真实后果：注入的裸 return 落在门视野之外——一个无守卫、退出码 -1 的"命中"可以随意加。

### 14-6 `family-hole` — 对照臂（`|| pending.isNotEmpty()`）
- 旧门用 `if "||" in cond` 而非匹配字面 `|| true`，修前即 CAUGHT；修后由同一判据路径（析取）抓住。

---

## 3. 判据改造（旧形态 → 新形态，逐条）

### 3.1 `marker_boundary_check.py`（自测臂 12 → 21）

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| C0 hit 的守卫（14-1） | 声明正则匹配到 `(`；`exit_expr` 与守卫变量比对 | 命中站点必须是「活」的（`path_feasible`），其 innermost 覆盖 `>= 0` 守卫的变量必须**由本函数的`val` 声明**、且初始化表达式**整条就是** `internalParseMinisExitCode(…)`（`match_bracket` 闭合括号必须落在表达式末尾）；`var` 一律红（可在解析与命中之间被改写）；`exitCode` 实参必须等于该变量 |
| hit 的识别（14-4/14-3） | `hit_expr != "true"` 直接跳过 | 用 `const_bool` 折叠：**只有能折叠成 false 的才不是命中**；位置参数第一项同样按 hit 处理（`call_args` + 命名/位置解析） |
| C1 顺序 | 正则找 `>= 0` 守卫位置 | 只认「解析值守卫」，第一个活命中必须在其 then 分支之后 |
| C2 上限（14-2） | 名字 + 比较算子同框、无 `||` | 解析比较：cap 必须与 `<window>.length` 比较、方向必须是真上界（`<=`/`<`，镜像 `>=`/`>`）、`guard_feasible(cond)` 必须为真（`&& false` 折叠即红）、`||` 一律红、窗口变量必须绑定到 `combined.substring(…)` |
| C2 分支（14-3） | 只证明有界分支存在 | 分支里必须有 `MarkerScanResult`，其 `hit` 必须可折叠为 false，`keptTail` 不得是可折叠的空字面量，且必须派生自该窗口变量（或 `substring(`） |
| 函数体（14-5） | `text.find("\n}")` | 共享 `function_body()`（括号配平 + 字符串/注释掩码）+ `kind == "block"` 校验 |
| 词法/结构 | 自写 `mask` / `_brace_block` / `_paren_inner` / `_guards` / `_split_args` / `function_span` | 全删，改用 `_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`；`KotlinLexError` → 直接判违规（fail-closed） |

### 3.2 `fastio_tar_bounds_check.py`（自测臂：计数式 → 54 条带专属文案断言）

| 判据 | 旧形态 | 新形态 |
|---|---|---|
| 09-1 ustar 天花板 | `SPLIT_NAME_OK` 子串 + `STALE_CEILING_RE`（`<=` 才扫） | 解析 `splitUstarName` 体内**每一个** `utf8Length(<half>)` 比较：左操作数必须来自 `call_args`（`name`/`prefix`/`rest`），算子 ∈ `<` / `<=`，右操作数按算术判定必须表达 `FIELD - 1`（等价 `< FIELD` 也接受）；`== FIELD` / `<= FIELD` / `>=` 全红。函数或站点缺失 → fail-closed 红 |
| 09-2 归档符号链接 | `"Files.isSymbolicLink(" in body` | 对每个 `isSymbolicLink` 出现点解析其 `if` 条件：`guard_feasible(cond)` 必须为真（`false &&` / `1==2` 全折）、`path_feasible` 必须为真、被守卫语句必须 `return`/`throw`；否则逐条给"短路 / 死分支 / 看见却继续走"的专属文案 |
| 09-3 短载荷补齐 | `\bsize\s*-\s*written\b` 子串 | `call_args` 取补齐实参，规范化（剥 `.toLong()`/`.toInt()`/括号/空白）后必须**等于** `size-written` |
| 09-4 成员顺序 | `SORT_RE.search(tree)` | 先把 listing 的值（`val <x> = …newDirectoryStream…`）取出来，排序位置落在丢弃式 lambda（`also`/`apply`/`onEach`/`forEach`）区间内即判"排序结果被丢弃"；无排序 → 单独文案 |
| 09-5 特殊文件守卫 | 左置字面量正则 | `guard_cond_at` 解析条件（覆盖 token 在 `when` 标签、`if` 条件、被守卫体三种形态）→ `const_bool` 非 None 即"决定不了任何事"、`guard_feasible` 假即"永不成立"；另加 `path_feasible` 判死分支 |
| 切片/词法 | 自写 `strip_kotlin_comments` / `_close_paren` / `_close_brace` / `_enclosing_guard_cond` / `tar_fun_body`（`"\nprivate fun "` 硬切） | 全删，改 `code_view` + `function_body` + `match_bracket` + `expr_end` + `call_args` + `split_top`；catch 块改用 `match_bracket` 定界 |

---

## 4. 四层证据

### L1 本门（真树 + 自测）

```
$ python3 scripts/scan/marker_boundary_check.py --selftest
SELFTEST=PASS (21 arms)
MARKER_BOUNDARY_SELFTEST=PASS (21 arms)
$ python3 scripts/scan/marker_boundary_check.py .
✅ marker-boundary guard: `internalScanMarker` requires a parsed exit code before a hit, pending window capped by MAX_PENDING_MARKER_CHARS

$ python3 scripts/scan/fastio_tar_bounds_check.py --selftest
FASTIO_TAR_SELFTEST_ARMS=54
FASTIO_TAR_BOUNDS_SELFTEST=PASS (54 arms)
$ python3 scripts/scan/fastio_tar_bounds_check.py .
GATE=GREEN fastio_tar_bounds
```

判据"真在跑"的旁证（真树逐判据取中间值，确认不是空扫）：

```
ceilings:     [(9, 'name', '<=', 'NAME_LENGTH - 1'), (281, 'prefix', '<=', 'PREFIX_LENGTH - 1'), (324, 'rest', '<=', 'NAME_LENGTH - 1')]
listing init: ' try { Files.newDirectoryStream(dir).use { stream -> stream.toList().sortedBy { … } } } catch (e: Exception'
sorts:        ([88], [88], [])        # 排序位置 88，丢弃区间空 ⇒ 值存活
check_* 六项  全部 []（tar/handler/archive/regular/order/toplevel/special）
```

新增/改写的臂（每条都断言**专属文案**，撞错判据即算失败）：

- marker：`pre-fix 形状`、`缺 cap 声明`、`cap 未使用`、`cap 超界`、`W1 常量守卫`、`W2 上限析取`、`W3 位置参数 hit`、`14-1 .let{0}` / `coerceAtLeast(0)` / `?: 0`、`14-2 常量左操作数`、`14-2b 反向上限`、`14-2c 上限绑到非 pending 值`、`14-3 丢掉扣下切片`、`14-4 hit = !false`、`14-5 顶格 `}` 截断`；clean 臂：镜像守卫拼写、`keptTail = combined.substring(idx)`、多行解析调用、镜像 cap 拼写。
- tar：`09-1 合规行保留 + 追加原始上限`（并且断言**恰一条**违规 —— 证明上一行的合规写法仍被接受）、`09-1b 反向上限`、clean 臂 `09-1c `< FIELD` 等价拼写`、`09-2 短路拒绝`（恰一条）、`09-2b 看见链接不 return`、`09-3 乘 0 补齐`、`09-4 排序被 also 丢弃`、`09-5 右置死字面量`（`expect_only`，其余两个引擎必须同时保持干净）。

### L2 反攻（硬验收）—— 12/12 CAUGHT

驱动：`verify_batch5.py --mode full`（官方驱动，未自造跑法）。

```
$ python3 verify_batch5.py --witnesses witnesses-A.json --repo wt7/t06 --mode full --only marker_boundary_check.py --out probe/t06-post-marker.json
[1/6] 14-1 -> CAUGHT (rc=1, exact)   [2/6] 14-2 -> CAUGHT   [3/6] 14-3 -> CAUGHT
[4/6] 14-4 -> CAUGHT (rc=1, exact)   [5/6] 14-5 -> CAUGHT   [6/6] 14-6 -> CAUGHT
SUMMARY: {'CAUGHT': 6}

$ python3 verify_batch5.py … --only fastio_tar_bounds_check.py --out probe/t06-post-fastio.json
[1/6] 09-1 -> CAUGHT (rc=1, exact) … [6/6] 09-6 -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}
```

修前基线（同一驱动、同一棵树、未改判据）：`{'BLIND_CONFIRMED': 5, 'CAUGHT': 1}` × 2 门 ⇒ `probe/t06-pre-marker.json` / `probe/t06-pre-fastio.json`。

**逐条归因**（`probe/t06_attribution.py`：一次只打一条补丁、跑该门、抓文案、还原并 sha256 校验）：

```
09-1..09-6  CAUGHT rc=1 restored=True
14-1..14-6  CAUGHT rc=1 restored=True
witnesses: 12  CAUGHT: 12  restored: 12
```

每条红在**它自己那条判据**上（文案摘录）：

| 见证 | 触发文案 |
|---|---|
| 09-1 | `stale raw-field ceiling: utf8Length(name) == NAME_LENGTH is not the STORED size (NAME_LENGTH - 1 …)` |
| 09-2 | `the symlink refusal is short-circuited (false && Files.isSymbolicLink(archivePath) folds to false) — the guard keeps the token and the refuse call and decides nothing, so a link at the archive path is followed anyway` |
| 09-3 | `the copy-failure refill must restore the UNDELIVERED payload — the fill amount ((size - written) * 0L) is not size - written` |
| 09-4 | `writeTree sorts the entries and then DISCARDS the sorted list — a sort inside .also { it.sortedBy … } … hands back the unsorted receiver` |
| 09-5 | `the regular-file guard is short-circuited (!attrs.isRegularFile && false folds to False)` |
| 09-6 | 同上（左置形态） |
| 14-1 | ``exitCode` is not the parsed marker exit code: `exitCode` chains onto `internalParseMinisExitCode(…)` (`.let { 0 }` / `.coerceAtLeast(0)` / `?: 0`)` + `the hit hands exitCode = '-1' to MarkerScanResult while the guard validated 'exitCode'` |
| 14-2 | `the MAX_PENDING_MARKER_CHARS bound compares 0 — the LEFT operand must be the pending window's length (pending.length <= MAX_PENDING_MARKER_CHARS)` |
| 14-3 | `keptTail = '' — the withheld window is dropped, so the split marker line never completes in the next chunk` |
| 14-4 | `is not the parsed marker exit code` + `the hit hands exitCode = '-1' …` |
| 14-5 | `the MAX_PENDING_MARKER_CHARS bound is dead (pending.length <= MAX_PENDING_MARKER_CHARS && false constant-folds to false)` + 注入裸 return 的 `is not the parsed marker exit code` |
| 14-6 | `the MAX_PENDING_MARKER_CHARS bound is weakened by a disjunction (`||`)` |

### L3 邻门（逐门独立进程，`probe/t06_gate_loop.py`）

语义相邻清单（两门读的是 `PersistentShell.kt` / `FastioTar.kt` / `FastioOffloadHandler.kt` / `FastioSearch.kt`）：

`offload_host_path_check`、`fastio_args_check`、`tool_result_image_branch_check`、`offload_cli_args_check`、`offload_media_b64_wire_check`、`process_logging_gate`、`pty_*`/`shell_*` 相关门、`parts_json_funnel_check`、`browser_*`（无交集）。

实测结果：**109 个 `*_check.py` / `*_guard.py` 逐门独立进程 109/109 rc=0**，外加 `i18n_check.py`（`Done: ALL CLEAN ✅`）、`process_logging_gate.py`（`✅ All isolated processes have an AppLogger.init decision`）、`turn_tail_finalize_order.py`（`PASS …`）⇒ **112/112 绿**，零红门。

### L4 全量（沙箱逐门健康路径 + CI 为准）

- `scan.sh` 全量：沙箱内被 `OSError: [Errno 38] Function not implemented` 打死（S28，本轮再次复现：一次在 70/109 处、一次连 `/tmp` 都不可用、`nobody_testscan` 起不来）。按既定健康路径改为**逐门独立进程**跑完 109 个 + 3 个非 `_check` 后缀步骤 ⇒ 112/112（见 L3）。
- `test_scan.py` 全量单进程：被 stall-guard 打死（同一 S28 窗口）。改用**逐测试函数分块驱动**（`probe/t06_testscan.py`，JSONL 断点续跑）：46 个测试函数 **340 pass / 0 fail**，其中 4 条首轮失败（`test_auto_approve_gates` 1、`test_da1016_gates` 1、`test_da1018_gates` 2，均为 ENOSYS 驱动的子进程失败）**逐函数复跑后全绿**（2/2、18/0、14/0）⇒ 合计 **344 pass / 0 fail**，与仓内金标 `ALL 344 CASES PASS` 一致。
- 两门在 `test_scan.py` 里的冻结断言单独复核全绿：`test_da1017_gates`（marker 的 `SELFTEST=PASS` + 真树）、`test_da1017_external_fixtures`（`OK_FIXTURE` 落盘必绿 / `PRE_FIX_FIXTURE` 必红，走导入的 `check_source`）、`test_da1019_gates`（fastio_tar 的 `FASTIO_TAR_BOUNDS_SELFTEST=PASS` + 真树）。
- `git status --porcelain src/` = 空（12 条反攻补丁全部还原，sha256 逐条校验）。

---

## 5. CI（run / job / head_sha + 日志判读，全量读数的权威来源）

- run `40266676742` / **#2714** / job `121390496868` / attempt **1** / event `workflow_dispatch`
- **conclusion = success**；`head_sha = 9101923ab1eeda79a490500faa824f23446c4289`，与本地门提交（`git rev-parse HEAD`）**逐字符一致**
- 时间：`2026-10-26T03:58:32Z` → `04:21:17Z`（≈22m45s）
- 26 个 step，唯一非 success = **step 23 `Publish to Releases` skipped**（main 门控，符合预期）
- job 日志 `1,487,190 B`，裸 NUL = **0**（字节级核过）

日志逐条判读：

| 读数 | 结果 |
|---|---|
| `Summary: 112 passed, 0 failed` | 恰 **1** 次（带框线权威行） |
| 其它 `Summary: N passed` | 7 条，全是子套件（5 / 10 / 14 / 15 / 25 / 28 / 36 passed），无 failed |
| `ALL 344 CASES PASS` | 恰 **1** 次 |
| `[N/112]` 门头 | **112** 个互异编号（无重复、无缺口） |
| `^ *e: `（Kotlin 编译错误） | **0** |
| 含 `FAILED` 的行 | 6 条，逐条确认**全是 PASSED 用例名的子串**（`slot denied produces FAILED without calling provider PASSED`、`reason PERSISTENCE_FAILED maps to …`、`phase FAILED maps to Failed PASSED`、`terminal FAILED maps to Failed PASSED`、`reason EXECUTION_FAILED maps to …`、`the fixed tail still reaches a legal FAILED terminal PASSED`）⇒ 无真实失败 |
| `GATE=GREEN fastio_tar_bounds` | 恰 1 次；`marker-boundary guard` 绿行 1 次 ⇒ 两门在 CI 全量里真跑且绿 |

两门在 `test_scan.py` 里的在册见证（CI 日志逐行）：

```
✅ marker_boundary_check selftest has teeth (SELFTEST=PASS)
✅ marker_boundary_check real tree green
✅ marker_boundary_check external clean fixture green
✅ marker_boundary_check external pre-fix boundary hit fixture red
✅ fastio_tar_bounds_check selftest has teeth (FASTIO_TAR_BOUNDS_SELFTEST=PASS)
✅ fastio_tar_bounds_check real tree green
```

**诚实记录（对应 §6 R9）**：带臂数的自证串（`MARKER_BOUNDARY_SELFTEST=PASS (21 arms)`、`FASTIO_TAR_SELFTEST_ARMS=54`、`FASTIO_TAR_BOUNDS_SELFTEST=PASS (54 arms)`）在 CI 日志里 **0 命中**——门自证的 stdout 不进 CI 日志，`test_scan` 只断言冻结的 marker 子串。所以 21 / 54 条新臂在 CI 侧的见证形式是「selftest 退出码 0 且输出含标记」，臂数本身只由本报告与 `--selftest` 输出承载；主会话若要让臂数在 CI 可见，按 §7 追加注册行。

---

## 6. 残留（R1..R9，每条带「触发条件 / 触发后方案 / 搁置理由」三件）

**R1 marker C0 只认两种守卫拼写。**
- 触发条件：有人把 `if (exitCode >= 0)` 改写成 `if (exitCode > -1)` / `if (exitCode != -1)`。
- 触发后方案：`guard_var()` 增加「整数区间下界 >= 0」的折叠分支（用 `guard_feasible` 的区间求交，不用正则）。
- 搁置理由：这是 fail-closed 方向（改写成等价形态会判红，不会静默放行）；真出现时改一行判据。

**R2 marker 的窗口变量必须是直接绑定。**
- 触发条件：`val pending = …` 改成 `val pending = tail + text` 再 `pending.substring(…)`，或先把切片算进另一个局部再比较。
- 触发后方案：`_window_var` 改用 `value_sources` 追两跳。
- 搁置理由：一跳动 provenance 已覆盖当前实现与合理重构；两跳会把判据的误红面扩大而收益为零。

**R3 marker C2 不接受与其它条件复合的上限。**
- 触发条件：`if (pending.length <= CAP && markerFound)`。
- 触发后方案：按 `split_top(cond, ("&&",))` 逐项找那个 cap 比较，其余项只需 `guard_feasible`。
- 搁置理由：当前实现是单一比较；复合条件判红是安全方向，且真要改属于"刻意编辑本门"。

**R4 marker 位置参数按固定下标。**
- 触发条件：`MarkerScanResult` 增删字段导致 `hit`/`exitCode`/`keptTail` 的位置变化。
- 触发后方案：从 `MarkerScanResult` 的数据类声明里读字段顺序（`function_body` + 构造参数表）。
- 搁置理由：该类是内部数据类，字段改名/换序是同时要改门与测试的破坏性改动；当前命名参数写法已覆盖。

**R5 tar 天花板只判「比较在 `utf8Length(…)` 调用点」。**
- 触发条件：先 `val n = utf8Length(name)` 再 `if (n <= NAME_LENGTH)`。
- 触发后方案：对 `utf8Length(` 的结果绑定做一跳别名追踪。
- 搁置理由：该写法当前会命中"without comparing it to the stored ceiling"而判红（fail-closed），不是静默放行。

**R6 tar 补齐量只认精确 `size - written`。**
- 触发条件：`val missing = size - written; writeZeros(sink, missing)` 或 `size.minus(written)`。
- 触发后方案：`is_undelivered` 先按 `value_sources` 解析一跳别名，再规范化比较。
- 搁置理由：判红方向安全；一跳追踪的收益要与"误红合法重写"的风险权衡，留待真出现过一次再说。

**R7 tar 丢弃式作用域名单固定四个。**
- 触发条件：出现新的"运行但不返回值"的作用域扩展（自定义 `also` 语义函数）。
- 触发后方案：名单改为从 `peel_discarding` 的 `DISCARD_SCOPES` 派生（共享层单一事实源）。
- 搁置理由：共享层当前就用这四个；本门自行扩名单会造成两处不一致（违反共享层单一事实源）。

**R8 tar 的 listing 只取第一个含 `newDirectoryStream` 的局部。**
- 触发条件：`writeTree` 里先出现另一个目录列举（`Files.list` / 第二个 stream）。
- 触发后方案：改成"所有 listing 初始化各判一次，全部必须保留排序值"。
- 搁置理由：当前只有一个 listing；多 listing 时判据会在错误的初始化上给出绿灯，但那种重构同时会改 `SORT_RE` 的语义边界，属于刻意编辑。

**R9 两门的自测臂数不进 CI 日志。**
- 触发条件：主会话在 `test_scan.py` 注册时只保留 `SELFTEST=PASS` 标记。
- 触发后方案：按 §7 的标记串（带臂数）注册，且不要用"包含关系"凑数（16 含 6 那种）。
- 搁置理由：门自证 stdout 不进 CI 日志是既有机制（多批已记录），臂数只能靠 `test_scan` 断言或报告承载。

---

## 7. `test_scan.py` 注册项（交主会话统一注册，本支未改 `test_scan.py`）

现状（冻结面，已存在，勿动）：

```python
("marker_boundary_check", "SELFTEST=PASS", (REPO_ROOT,)),
("fastio_tar_bounds_check", "FASTIO_TAR_BOUNDS_SELFTEST=PASS", (), (REPO_ROOT,)),
("marker_boundary_check", "OK_FIXTURE", "PRE_FIX_FIXTURE", "pre-fix boundary hit"),   # 外部夹具对
```

建议**追加**的带臂数断言（两个门都会打印，且都是稳定串）：

```python
# marker：SELFTEST=PASS 与带臂数标记各一
("marker_boundary_check", "MARKER_BOUNDARY_SELFTEST=PASS (21 arms)", (REPO_ROOT,)),
# fastio_tar：臂数单独一行，便于只改数字不动语义
("fastio_tar_bounds_check", "FASTIO_TAR_SELFTEST_ARMS=54", (), (REPO_ROOT,)),
```

- marker 门的旧标记 `SELFTEST=PASS` 与外部夹具名（`OK_FIXTURE` / `PRE_FIX_FIXTURE` / `TARGET`）**原样保留**，本支未改其语义：`OK_FIXTURE` 仍是完整可编译源、`PRE_FIX_FIXTURE` 仍必须 rc=1。
- `fastio_tar` 原标记 `FASTIO_TAR_BOUNDS_SELFTEST=PASS` 保留，行尾追加了 `(54 arms)`（仍在断言子串内，不会破冻结断言）。

---

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch7/wt7/t06

# L1 本门
python3 scripts/scan/marker_boundary_check.py --selftest ; python3 scripts/scan/marker_boundary_check.py .
python3 scripts/scan/fastio_tar_bounds_check.py --selftest ; python3 scripts/scan/fastio_tar_bounds_check.py .

# L2 反攻（官方驱动）
B=/var/minis/shared/work/meta-audit-v5/batch7
python3 $B/verify_batch5.py --witnesses $B/witnesses-A.json --repo . --mode full \
    --only marker_boundary_check.py --out $B/probe/t06-post-marker.json
python3 $B/verify_batch5.py --witnesses $B/witnesses-A.json --repo . --mode full \
    --only fastio_tar_bounds_check.py --out $B/probe/t06-post-fastio.json
# 逐条归因（文案 + 还原校验）
python3 $B/probe/t06_attribution.py

# L3 全部门（逐门独立进程，沙箱健康路径）
python3 $B/probe/t06_gate_loop.py

# L4 test_scan（分块驱动，断点续跑）
python3 $B/probe/t06_testscan.py 12     # 反复调用直到 46 个函数跑完

# 变异还原检查
git status --porcelain src/
```
