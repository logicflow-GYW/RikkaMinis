# FIX-WIDE4-06 — 定时任务存储门：五条失效轴从「拼写存在」改到「效果达成」

- **基线**：main `19e70dab`（wide3 并集后的 main）+ wide4 共享原语提交 `dbab7a3e`
- **分支**：`fix/wide4-06-sched-store`
- **门**：`scheduled_store_release_guard`（唯一）
- **生产代码改动**：**0 行**（只改判据 + 自证臂）
- **冻结面**：`scripts/scan/scan.sh` / `scripts/scan/test_scan.py` **零触碰**
- **门数 / 夹具数**：**112 / 344 不变**（无新建门）
- **改动面**：1 文件 `+387 / −75`（`scripts/scan/scheduled_store_release_guard.py`）

## 1. 结论

外部对抗审计对这道门给出 6 条「能过门但把缺陷放回来」的最小补丁，
**5 条被证伪（门瞎）+ 1 条门本来就能抓住（CAUGHT_VERIFIED）**。加固后：

| 指标 | 加固前 | 加固后 |
|---|---|---|
| 原见证逐条反攻（apply → 跑门 → restore） | **1/6 CAUGHT**（5 条盲） | **7/7 CAUGHT**（含 1 条自疑臂） |
| 门自证臂（`--selftest`） | 50 | **63**（+13，含 2 条正向控制） |
| 真树（`19e70dab` + `dbab7a3e`）本门 rc | 0 | **0**（无 verdict 翻转） |
| 邻门（7 道）rc | 0 | **0** |
| 生产 `.kt` diff | — | **0 行** |

五条失效轴的共同形态：**判据锚「某个 token / 某个名字出现过」，而不是锚「那个决定
真的被执行了」**。逐轴：

| 轴 | 见证（一句话） | 旧判据为什么瞎 |
|---|---|---|
| dataflow / 集合 | `setOf(taskId).filter { false }.toSet()` | 只比对「实参里的标识符 ∩ 函数体里的标识符」，`taskId` 两边都在 |
| dataflow / 计数器 | `_revision.value = _revision.value + 0L` | `=` 右侧只要**提到** backing 字段就整体豁免 |
| spelling / 常量折叠 | `if (!true && !allowInsert) return file` | 本地 folder 没有一元 `!`，死分支折不出来 |
| scope / 结果被丢弃 | `mutate(…){…}.also { if (false) mergeUpsertTask(…) }` | 只查 `MERGE_CALL in body`，`.also` 的返回值是**接收者** |
| **parser-death** | `val _open = '"'` 夹住 `copy(enabled = task.enabled)` | 门自己手写的 masker 不认识字符字面量，两个 `'"'` 之间被当成一个字符串整段抹白 |

## 2. 环境与装置

- 工作树 `wt-06`（`git worktree`），全程 `nice`。
- 反攻器 `probe/reattack.py`：**在单进程内** import 门模块并调 `main()`
  （`sys.argv` 打补丁 + `redirect_stdout`），逐条 apply → 跑 → **restore + sha256 比对**。
  这一层刻意不用 `subprocess`：本波 12 支并行期间 `waitpid` 会随机返回
  `OSError: [Errno 38]`（沙箱 S28），驱动会死在半途并把工作树留在变异态。
- 全量驱动 `probe/run_all_gates.py`（从 `scan.sh` 解析门清单、逐门重试 ENOSYS）
  与 `probe/chunk.sh`（shell 分块编排）。
- 见证臂命名一律 `w4-` 前缀；冻结的自证收尾标记串
  （`SCHEDULED_MERGE_SELFTEST=PASS` 等 4 条）**一字未改**。

## 3. 逐条改动

### 3.1 手写 masker → 共享词法器（parser-death 轴，本门最贵的一处）

`strip_code()` 原本是本门**私有的** Kotlin 词法器：只认识 `//`、`/* */`、`"""`、`"`。
见证用 `val _open = '"'` 与 `val _close = '"'` 包住 `merged.copy(enabled = task.enabled)`，
两个字符字面量被当成**一个字符串**，中间整段代码被抹白，于是 `copy_arg_names()`
看到的是一次「只拷规格字段」的干净 copy —— 门报绿，而 run state 已经被编辑器快照覆盖。

改成委托共享模块 `_kotlin_lexer.analyse()`：

- 字符字面量、嵌套块注释、raw string、`${…}` 模板、反引号标识符全部由一处实现；
- **解析失败返回 `None`**，12 个调用点全部改成 fail-closed
  （`_unlexable()` 出一条违规，不再对被抹白的文本下结论）。

> 纪律：门自己再写一遍正则剥注释 = 直接违规。这一条在 wide3 的 katex 门上已经付过学费，
> 本门是同一族的第二例。

### 3.2 `check_release_argument` — 释放集合必须「带着被移除的 id 走到运行时」（dataflow）

旧判据：实参里出现的标识符 ∩ 函数体里的标识符，非空即通过。于是
`setOf(taskId).filter { false }.toSet()` 保留 `taskId` 的**拼写**、交出的却是空集合，
`ScheduledTaskRunner` 永不释放单飞锁 —— 被删掉的那个任务继续占着 gate 约 30 分钟。

新增两道正向约束（都在 idens 判定**之前**）：

1. **清空链**：`.filter / .filterNot / .takeIf / .takeUnless / .take / .drop* / .minus /
   .subtract / .intersect / .distinct* / .map / .mapNotNull / .let / .run / .also /
   .apply / .ifEmpty / .orEmpty / .remove* / .retainAll` 一律红（`toSet()` / `toList()` /
   `sorted*` 不在名单：它们不会丢元素）。
2. **常量决定的 inline `if`**：`if (false) setOf(taskId) else emptySet()` —— 条件能折叠即红，
   因为死掉的那条腿不会被求值。

同时把「空构造器」正则扩到带类型参数（`setOf<Any>()`）。**正向控制**保留：
`(beforeIds - afterIds).toSet()` 仍然绿（真实代码里的 id 差集写法）。

### 3.3 `bump_stalls` — 变更计数器必须读旧值、且步长非零（dataflow）

旧的 `=` 分支有三段兜底，其中两段是洞：

```python
if "_%s" % backing in rhs:  return None     # 只要 RHS 提到 _revision 就豁免
if re.search(r"\bnextRevision\s*\(", rhs): return None
```

于是 `_revision.value = _revision.value + 0L`（以及
`if (false) _revision.value + 1L else _revision.value`）都算「有 bump」。
`StateFlow` 是 `distinctUntilChanged`，值不变 = 屏幕永远不刷新 —— 编辑器打开期间
落库的变化全部看不见。

改为**正向白名单**：RHS 必须二选一

- `nextRevision(…)` 调用，且**当前值 `_x.value` 被作为实参喂进去**（只出现 `nextRevision(` 不算）；
- 或 `_x.value ± N`（`N` 必须是**非零**字面量，`+ 0L` / `- 0L` 单独报「never changes the value」）。

其余一律走到「不派生自当前值」的兜底（例如 `if` 表达式把唯一步长放进死腿）。

### 3.4 `fold_bool` — 先切逻辑运算、后折一元 `!`（spelling + 死分支）

`if (!true && !allowInsert) return file` 里 `allowInsert`、`return file` 都在分支内，
旧 folder 只会折 `true/false/&&/||/(…)`，遇 `!` 返回 `None` ⇒ 死分支不被抹白 ⇒
`idx < 0` 分支被判定「guard 在、但什么都不做」→ 实际是通过。

叶子委托共享 `_kotlin_ast.const_bool`（支持 `!true` / `0 == 1` / `1 == 1`），
但**顶层 `&&` / `||` 的切分留在本门**：共享模块先试前导 `!` 再切运算符，
`!true && !allowInsert` 会被读成 `!(true && !allowInsert)`（右操作数优先级），
而 Kotlin 里 `!` 比 `&&` 结合得紧。→ **已登记为共享模块缺陷**（§7 R1）。

### 3.5 `prune_dead_branches` — 死 `if` 要连**头**一起抹（否则吃掉后续语句）

原实现只抹死分支、留着 `if (literal-false)` 头。下一轮扫描把**紧随其后的语句**
当成同一个 `if` 的死体再抹一遍 —— 一个死 guard 会一路吃光函数余下的所有语句。
加固 3.4 让更多分支可折之后，这个洞立刻显形：C4 的见证会以
「no function appends `file.tasks + task`」这种**误导性**消息报红（虽然仍是红）。

现在一次抹「`if` 头 + 死分支（+ `else` 关键字）」，活腿原样留下；同一构造不会被重复处理。

### 3.6 `scan_merge_semantics` — merge 调用必须**返回值**，不能挂在接收者 lambda 尾

旧判据 `if MERGE_CALL not in body` 只查拼写。见证把
`mutate(…){ file -> mergeUpsertTask(file, task) }` 换成
`mutate(…){ file -> file.copy(tasks = file.tasks.map { task }) }.also { if (false) mergeUpsertTask(…) }`：
`mergeUpsertTask` 字样还在，返回值却是 `.also` 的**接收者**，保存把整个记录（含 run state）
覆盖掉。

两处新增：

1. **死分支先剪**（3.5）：`if (false)` 里的调用不再算路由；
2. **接收者 lambda 判定**（`_result_discarded`）：从调用点向外找**最内层** `{`，
   取它前面的调用名；若该名在 `DISCARD_LAMBDAS`（`also/let/apply/run/map/filter/forEach/
   onEach/takeIf/takeUnless/ifEmpty/…`）且前面是 `.`，则该调用的结果被丢弃 → 不算路由。

红时的措辞统一带 `no longer routes through ScheduledTaskTransitions.mergeUpsertTask`，
并把「只存在于接收者 lambda / 死分支里」这一实际形态写出来（原来是「调用没了」，现在是两种形态分开说）。

## 4. 验证

### L1 本门（真树 + 自证）

- 真树 `python3 scripts/scan/scheduled_store_release_guard.py .` → **rc=0**，8 行 OK 全在。
- `--selftest` → **63 臂全绿**，4 个冻结标记串全部照旧
  （`SCHEDULED_MERGE_SELFTEST=PASS` / `_CANSAVE_` / `_EDITGUARD_` / `_REVISION_`）。
- 新增 13 臂（`w4-` 前缀）：

| 臂 | 期望 | 轴 |
|---|---|---|
| `w4-release/chained filter empties the removal set` | 1 | dataflow |
| `w4-release/.takeIf(false) empties the removal set` | 1 | dataflow |
| `w4-release/literal-decided inline if picks the empty arm` | 1 | dataflow |
| `w4-release/id difference of two documents` | **0** | 正向控制 |
| `w4-store/merge call swallowed by a live .also tail` | 1 | scope |
| `w4-store/merge call kept only behind a dead if` | 1 | scope + 死码 |
| `w4-store/store the lexer cannot parse is refused` | 1 | **fail-closed** |
| `w4-lex/char literal no longer hides the spliced copy(enabled=…)` | 1 | parser-death |
| `w4-merge/insert guard !true &&` | 1 | spelling |
| `w4-merge/insert guard !false \|\|` | 1 | spelling |
| `w4-merge/insert guard 0 == 1 &&` | 1 | spelling |
| `w4-revision/+ 0L never moves the ticker` | 1 | dataflow |
| `w4-revision/dead arm carries the only step` | 1 | dataflow |

### L2 见证反攻（6 条原见证 + 1 条自疑臂）

`python3 probe/reattack.py` → **7/7 CAUGHT，restore sha256 全 True，CONTROL rc=0**：

| 见证 | 修复前 | 修复后 | 首条命中 |
|---|---|---|---|
| `scheduled-PRESENCE` filtered set | 盲 | **CAUGHT** | `ScheduledTasksStore.kt:144 [deleteTask] passes setOf(taskId).filter { false }.toSet() …` |
| `scheduled-C1` `+ 0L` | 盲 | **CAUGHT** | `Store.kt:232 … never changes the value` |
| `scheduled-C4` `!true &&` | 盲 | **CAUGHT** | `ScheduledTaskPolicy.kt:220 … inserts an unknown id without consulting its allow-insert parameter` |
| `scheduled-S1` merge→`.also`（死 if） | 盲 | **CAUGHT** | `Store.kt:119 [upsertTask] no longer routes through …mergeUpsertTask` |
| `scheduled-S1` char literal | 盲 | **CAUGHT** | `mergeUpsertTask copies \`enabled\` from the caller's record` |
| `scheduled-S1` default-param lambda（原本 CAUGHT） | CAUGHT | **CAUGHT**（不回退） | `Store.kt:73 [importDocument] drops tasks … depth 2 …` |
| **自疑臂**：`.also` 尾（**没有** `if (false)`，只靠 lambda 语义） | — | **CAUGHT** | 同上（证明不是靠死码检测混过去的） |

自疑臂是本轮有意加的：`if (false)` 那一版同时命中了死分支剪枝，
不能证明「接收者 lambda」这条判据本身有效；去掉 `if (false)` 仍红，才算锚住了语义。

### L3 邻门（同片区，防「修一个门红另一片」）

`schedule_date_source_check` / `deeplink_settings_routes_check` / `dao_loadmessages_check` /
`offload_cli_args_check` / `router_lock_check` / `browser_destroy_wakeup_check` /
`stream_flow_dispatch_guard` → **7/7 rc=0**。

### L4 全量与 CI

- **CI ＝权威读数**（见 §5）。本波 12 支并行期间沙箱进入 ENOSYS 风暴（S28）：
  `sh scripts/scan/scan.sh` 在 `[5/112]` 处
  `can't create /dev/null: Function not implemented` 而中止、
  逐门驱动 `probe/run_all_gates.py` 在 `subprocess.wait` 收到 `OSError: [Errno 38]`、
  分块 shell 跑到第 19 门后连续 `ENV-EXHAUSTED`。
  **如实登记：本地未能跑满 112 门**，改由 CI 的单进程全量复跑见证（§5）。
  已跑到的部分：前 18 门 rc=0（`four_way_sync_check` … `discarded_return_guard`）。

## 5. CI

- run：**#2663 / id `40179046723` / job `121125934315`** — attempt 1，**success**，created 2026-10-25T09:02:58Z
- head_sha：`4e1084b70d091d4b93f92786def9df9c63a619c9`，与本地 HEAD / API run.head_sha 三方逐字符一致（CI 绿树 = 本地代码提交，报告回填提交之外无其他差异）
- 步骤：26 步，唯一非 success = `23 Publish to Releases skipped`（main 门控，分支构建正常跳过）
- 日志判读（job log 1,485,140 B / 17,845 行，`tr -d '\000'` 清洗后）：
  - `Summary: 112 passed, 0 failed` **恰 1 条**
  - `ALL 344 CASES PASS` **恰 1 条**
  - ` e: `（Kotlin 编译错误标记）**0 条**
  - 门头 `━━━ [N/112]` 去重 **112 个互异**
  - 含 `FAILED` 的 6 行全部是 PASSED 用例名子串（`slot denied produces FAILED without calling provider PASSED` / `phase FAILED maps to Failed PASSED` 等），非真失败
- 回填纪律：本节回填只改本报告文件；`git diff --name-only 4e1084b7..HEAD -- . ':(exclude)fix-reports'` = 0（源码树与 CI 绿树等价）

## 6. 沙箱 / 环境观测（不记成门红）

1. **S28（ENOSYS 风暴）**：12 支并行窗口内，`subprocess.wait` / `cp` / `open` / `grep` /
   `mkdir` / 甚至 `>/dev/null` 都可能返回 `Function not implemented`。
   健康路径 = ①门用**单进程**内嵌调用（本报告 §2 的反攻器）；
   ②全量交 CI；③分块 shell 只做编排、每门自带重试。
2. **`set -e` 的 scan.sh 在风暴里会把「环境断了」报成「门红了」**——
   读 CI 日志时只认 `Summary: 112 passed, 0 failed` 这一行，别按退出码回溯。

## 7. 残留 / 注册项

**R1（共享模块缺陷，需主会话修，本支不能用）**：`scripts/scan/_kotlin_ast.py` 的
`const_value()` 先处理前导 `!`、后切顶层 `&&`/`||`，于是
`!true && !allowInsert` 被折成 `!(true && !allowInsert)`（右操作数优先级），
`!false || x` 同理。Kotlin 里 `!` 比 `&&` 结合得紧。
本门用「先切 `&&`/`||`、叶子再交给 `const_bool`」绕过；其它门若直接用
`const_bool()` 判多操作数布尔表达式，会拿到 `None`（安全但漏判）。
- **触发条件**：下一次有门用 `const_bool` 判含 `!` 的复合条件；
- **触发后方案**：把 `split_top` 提到 `!` 之前，或给 `const_value` 加优先级层；
- **搁置理由**：共享模块在本波是冻结面，改它会影响已收口的 wide4 其它支。

**R2（CI 不跑本门自证）**：`test_scan.py` 里没有 `SCHEDULED_*_SELFTEST` 的注册条目，
所以这 63 条臂在 CI 日志里**无人见证**（`grep -c SCHEDULED scripts/scan/test_scan.py` = 0）。
本支冻结 `test_scan.py`，需主会话在并集收口时补一行注册（4 个标记串任取其一即可覆盖整门）：

```python
("scheduled_store_release_guard", "SCHEDULED_MERGE_SELFTEST=PASS", (REPO_ROOT,)),
```
- **触发条件**：wide4 并集收口；
- **触发后方案**：加进任一 `DA*_GATES` 列表（结构见 `test_scan.py:2646` 附近）；
- **搁置理由**：`test_scan.py` 是本波全部 12 支的公共冻结面，支线各改必撞车。

**R3（判据仍可能被绕的形态，方向安全）**：
`if (false) setOf(taskId) else setOf(taskId)`（两腿都带 id）现在报红（常量条件即红），
虽然实际无害 —— 属**刻意 fail-closed**，不改。
`_result_discarded` 只认「最内层 lambda 头是被 `.` 调用的已知丢弃函数」，
自定义包装如 `myTap(x) { mergeUpsertTask(…) }` 不会判红（会绿）。
- **触发条件**：仓库里出现把 merge 包进自定义尾随 lambda 的写法；
- **触发后方案**：把该包装名加入 `DISCARD_LAMBDAS`，或改成「返回值必须是尾表达式」的通用判定；
- **搁置理由**：当前无此写法，通用判定在 Kotlin 上误伤面大（`run {}` / `with(x){}` 都是合法尾表达式）。

## 8. 交付边界

- 停在分支 **`fix/wide4-06-sched-store`**，未合并 main（合并起手权在用户/主会话）。
- `backlog.md` / `credibility.md` **本支不写**（任务书明示由主会话统一登记）。
- `probe/`（反攻器、全量驱动、日志）**不入库**（`.gitignore:141`）。
- 本波对生产代码、`scan.sh`、`test_scan.py` 均为**零改动**。
