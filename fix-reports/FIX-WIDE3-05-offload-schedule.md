# FIX-WIDE3-05 — offload / scheduled / alarm 四门见证加固

- 分支：`fix/meta-wide3-05-offload-schedule`
- 基线：`6b6c918fae6c338e3091d0d3870af3cad9e2b881`（main，wide-1024 第二波收口后）
- 改动面：4 个门脚本，**生产代码 0 行改动**，`scan.sh` / `test_scan.py`（冻结面）0 触碰
- 反攻结果：**13 / 13 CAUGHT**（修复前全部 `STILL_BLIND`）

---

## ① 门与判据

| 门 | 判据（加固后） | 攻击面 |
|---|---|---|
| `claim_release_guard.py` | ①归属比较必须**决定**分支（常量折叠后非常量）②自绑定守卫覆盖全部 scope function（`let/also/run/apply/takeIf/with`）③`fun start` / `onSessionFinished` 的函数体按**参数表**定位，staleness 分支只要**settle 共享态**即算 bail-out（不再要求拼写 `return`） | Termux 会话抢占 / 释放路径 |
| `offload_permission_reset_sync_check.py` | ①`clear*` 必须在**活代码**里（`if (false)` 包裹不算）②`bumpRevision` 必须真的**移动计数器**（自赋值/常量不算）③`setLevel` / `resetAll` 的函数体按参数表定位（默认 lambda 实参不是函数体） | Offload 权限重置的内存/持久层同步 |
| `scheduled_store_release_guard.py` | ①`onTasksRemoved(...)` 的**实参**必须携带被删 id（`emptySet()` 不算，`null` = release-all 显式放行）②revision bump 必须**消费** `nextRevision(...)` 的结果 ③插入守卫的 `allowInsert` 必须真的**决定**分支（`false &&` / `\|\| true` 都不算）④函数体按参数表定位 | 定时任务存储的释放与合并语义 |
| `extra_days_container_guard.py` | ①按 `putExtra(…)` **实参**判容器类型（窗口内任何 `arrayListOf(` 都不算证据）②值必须是**原样**的被追踪对象，链式转换（`toIntArray()` / `toList().toTypedArray()`）会改变类型 ③期望写入者按**文件**派生（每个构造 `ACTION_SET_ALARM` 的文件都必须有自己的写入者） | 闹钟重复周期（`EXTRA_DAYS`）静默丢失 |

---

## ② 失效轴

| 轴 | 本支命中 | 形态 |
|---|---|---|
| `dataflow` | 8 条 | 守卫/赋值/调用**存在**，但它的结果没有决定任何东西（`true \|\| guard`、`if (false) { … }`、自赋值、实参被换成空集、返回值被丢弃） |
| `parser-death` | 3 条 | 门自己的解析器把**默认 lambda 实参**当成函数体（三个门同一个 bug：`find("{", 声明匹配终点)`） |
| `anchor` | 1 条 | 判据锚在 ±N 行**窗口**里的任意子串，而不是被判定对象的那个表达式 |
| `completeness` | 1 条 | 「至少 N 个写入者」按**调用数**计数，跨文件时一个文件全删也撑得住 |

一句话概括：**门判的是「拼写」和「附近有什么」，不是「这个决定真的发生了」。**

---

## ③ 逐条见证（13 条）

### `claim_release_guard`（3 条）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|
| C1 | `onSessionFinished` 的世代/状态结算 | dataflow | `is_ownership_cond()` 只在条件里**找**比较运算符：`if (true \|\| termuxSession === changedSession)` 含 `===` 且两侧都是非字面量 → 判成归属守卫，结算被计入「有守卫」 | `fold_bool()` 常量折叠（`\|\|` 先、`&&` 后，自然优先序）——条件折成常量即「比较不决定分支」→ 不是守卫，里面的结算立刻变成**无守卫结算** |
| C2 | `?.let { s -> if (x === s) }` 自绑定守卫 | spelling | `SELF_LET_RE` 只认 `?.let`。换成 `?.also`（同一对象、同一语义）后自绑定 span 不再被记录，`s` 被当成外部参数 → 假身份测试被读成真守卫 | scope function 全集 `let/also/run/apply/takeIf/takeUnless/with`；`run/apply/with` 的绑定名取 `this` 而不是 `it` |
| C3 | `fun start(...)` 的抢占分支 | parser-death | `joined.find("{", m.end())` 取**声明名之后第一个 `{`**。加了默认 lambda 参数（`onReady: () -> Unit = {}`）后，这个 `{` 属于默认值 → 切出来的「函数体」是 2 字符的 `{}`，真函数体从未被读；且旧判据要求分支里有 `return` 拼写 | 新增 `match_paren()` 逐字走参数表，再在括号之后取函数体；bail-out 定义改为「staleness 条件 + **settle 了共享态**」，不再看 `return` 拼写（不 return 反而更糟：结算完还继续跑） |

### `offload_permission_reset_sync_check`（3 条）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|
| C1 | `resetAll()` 必须清掉会话级缓存 | dataflow | 判据在 `expand()` 出来的闭包里找 `sessionGrants.clear(` / `sessionDenials.clear(` 子串。`if (false) { clear…; bumpRevision() }` 把两条 clear 全保留在文本里、执行上全删 | `prune_dead_branches()`：先按常量折叠把**永不执行**的分支整段置空（保长度、保行号），再做存在性判定 |
| C0 | `bumpRevision()` 必须推进 revision | dataflow | `"_revision.value =" in text` —— `_revision.value = _revision.value` 完全命中。`MutableStateFlow` 是 distinctUntilChanged，写同值不发射，设置页永远显示重置前的等级 | `bumps_revision()` 改成「必须从旧值派生」：`update { it + 1 }` / `++` / `+= n`（n≠0）/ RHS 含 `_revision` / `nextRevision(...)`；自赋值与常量赋值一律红 |
| C2 | `setLevel` / `resetAll` 的体必须触及会话缓存 | parser-death | `body_of()` 取 `fun setLevel(` 之后第一个 `{` = 默认 lambda 实参 `onUnused: () -> Unit = { clearToolSessionState(...); bumpRevision() }` → 释放判据被一个从不会被执行到的默认值满足，真体（调用已删）从未被看 | `_body_span_of()` 先配平参数表括号，再取其后的 `{`；表达式体返回 `(None, None)`，由调用方按「找不到体 = 红」处理 |

### `scheduled_store_release_guard`（4 条）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|
| PRESENCE | `ScheduledTaskRunner.onTasksRemoved(...)` 必须真的释放 | dataflow | 判据是「调用出现在函数体深度 1」。`onTasksRemoved(removedTaskIds = emptySet())` 完全命中原判据，但空集合意味着「一个都没删」——`ScheduledTaskReleasePlan` 保留 `runningTaskId`，被删任务的运行态一直占着单飞闸直到 reap 期限 | 新增 `check_release_argument()`：解析调用的**实参**；`null` 是显式 release-all（放行）；`emptySet()/emptyList()/setOf()/listOf()` 空构造 → 红；其余必须携带一个**本函数体内产生过**的标识符（id 必须从删除逻辑流到释放调用） |
| C1 | revision 递增必须推进 | dataflow | 两条独立的**存在性**检查：函数体里有 `_revision.value =` 行，且有携带 `wrote` 的 `nextRevision(...)` 调用。`val nextRev = nextRevision(...)` 算完就丢 + `_revision.value = _revision.value` 两者皆满足 | `bump_stalls()`：赋值右侧必须是旧值的函数（含 `_revision`）、或 `nextRevision(...)` 直接结果、或**绑定到** `nextRevision(...)` 的局部变量；自赋值 / 常量 / `update { 5 }` 全红 |
| C4 | 未知名插入必须由 `allowInsert` 决定 | dataflow | `ALLOW_RE` / `REFUSE_RE` 在 `idx < 0` 分支里找子串。`if (false && !allowInsert) return file` 两句都在，决定被删 | `prune_dead_branches()` 先清死分支 → 参数与拒绝语句都消失 → 判「参数什么都没守卫」；镜像形态 `!allowInsert \|\| true`（恒真）单独判红（参数永不生效，插入永远被拒） |
| S1 | `upsertTask` 必须走 `mergeUpsertTask` | parser-death | `function_spans()` 取 `fun upsertTask(` 之后第一个 `{` = 默认 lambda 参数 `merger = { f -> mergeUpsertTask(f, task, allowInsert) }`。真体 `file.copy(tasks = file.tasks.map { … })`（整条记录覆盖）从未被读，`mergeUpsertTask(` 由默认值提供 | 先配平参数表；块体取括号后的 `{`，表达式体（`= mutate(context) { … }`）从 `=` 起按 `_expr_end()` 取到表达式结尾 —— 三个删除/写入函数（`upsertTask` / `deleteTask` / `importDocument`）的切片逐一验证过 |

### `extra_days_container_guard`（3 条）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|---|
| C1 | `EXTRA_DAYS` 写入必须传 `ArrayList<Integer>` | anchor | 判据是 `EXTRA_DAYS` 所在行 **±3 行窗口**里的 `if arrayListOf(...) elif intArrayOf(...)`：窗口里任何一处 `arrayListOf(`（一行死别名 `val unused = arrayListOf(1)`，甚至相邻 `RepeatMode.WEEKDAYS` 分支的同名调用）都抢先命中 `if`，`elif` 的 `intArrayOf` 检查被完全跳过 | 真解析 `putExtra(…)` 调用（括号配平、逗号切分），只取**第二个实参**；按该表达式自身的形状判定（`arrayListOf` / `ArrayList` = OK，`intArrayOf` / `listOf` / `toTypedArray` / `toIntArray` = 红，其它一律 fail-closed） |
| C3 | 间接写入（`daysForRepeatMode(m)?.let { putExtra(k, it) }`） | dataflow | 只读**生产者声明**的返回类型（`ArrayList<Int>?` 命中即放行），完全不看实参到底传了什么。`it.toIntArray()` / `it.toList().toTypedArray()` 一律绿 | 实参必须**就是**被追踪的那个值：`it` 走 `lambda_receiver()`（平衡回溯取 `?.let/also` 的接收者，不再用贪婪正则吞掉前面的语句块）→ 生产函数声明返回类型；局部 `val` 追一跳；出现任何成员链/派生集合表达式 → 红 |
| C4 | 两个写入文件都要有自己的写入者 | completeness | `if len(writers) < 2` 数的是**调用数**。`AlarmOffloadHandler` 两个分支就凑够 2，把 `MinisApp` 的写入点整段删光仍然绿 | 期望的写入者**按文件派生**：凡代码里构造 `ACTION_SET_ALARM` intent 的文件，都必须有至少一个已验证的 `EXTRA_DAYS` 写入者；另有「≥2 个不同文件」的总底线兜底 |

---

## ④ 修法（按阶梯）

停在第 2 级（代码库里已有）：四门都是既有门脚本的判据升级，**没有新增门**、没有新增依赖、没有新配置项。

- 新增的解析原语全部是**门内部**的小函数（`mask_comments` / `split_args` / `match_paren` / `_body_span` / `fold_bool` / `prune_dead_branches` / `_backward_expr`），按仓内既有门的写法就地实现，不进共享库（避免把门之间的耦合变成新的一处「改一处漏一处」）。
- **不动生产代码**：13 条见证全都是「门的判据太松」，没有一条需要改 `.kt`。
- **不动冻结面**：`scan.sh` / `test_scan.py` 一字未改（注册新臂留给主会话并集阶段）。
- 每条新判据都配了**负向臂**（见 ⑤），并把「精确性」也钉住：`claim` 加了 `false || guard` 仍绿的精确臂、`extra_days` 加了「干净文件不得被牵连」的断言。

---

## ⑤ 四层证据

### L1 真树绿 + 自证臂

```sh
cd /var/minis/shared/work/meta-wide3/wt-05
for g in claim_release_guard offload_permission_reset_sync_check \
         scheduled_store_release_guard extra_days_container_guard; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?"
done
```

```
✅ Claim/release guard: no ungated release, self-bound gate, unguarded settlement or unminted claim found      rc=0
✅ Offload permission reset stays in sync with the renderer                                                rc=0
OK (C4 insert is opt-in): mergeUpsertTask's unknown-id branch is guarded by its allow-insert parameter …     rc=0
OK: 2 removal function(s) release the runner (importDocument, deleteTask) at top level.                     rc=0
OK: every EXTRA_DAYS writer passes a java.util.ArrayList                                                    rc=0
```

自证臂（`--selftest`，含本支新增的负向臂）：

| 门 | 臂数 | 新增臂 | 收尾行 |
|---|---|---|---|
| `claim_release_guard` | 40 fixtures（30 红 / 10 绿） | `W3-C1 true \|\|` / `W3-C1 precision false \|\|`（绿）/ `W3-C2 ?.also` / `W3-C3 默认 lambda 参数` / `W3-C3 无 return 结算` | `SELFTEST=PASS (40 fixtures: 30 breakers red, compliant/good arms green)`（原标记保留） |
| `offload_permission_reset_sync_check` | 16 臂 | `W3-C1 if(false) 包住 clear` / `W3-C1b if(false && …) 包住 per-tool remove` / `W3-C0 自赋值` / `W3-C0b 常量` / `W3-C2 setLevel 默认 lambda` / `W3-C2b resetAll 默认 lambda` | `OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS (16 fixtures)`（**一字不差保留**，见 §6 冻结要求） |
| `scheduled_store_release_guard` | 21 用例 + 5 release 臂 | `revision/自赋值` / `revision/常量` / `revision/update 忽略 it` / `revision/从旧值递增`（绿）/ `merge/if(false &&)` / `merge/\|\| true` / `release/emptySet` / `release/emptyList` / `release/常量集合` / `release/局部 id 集`（绿）/ `release/null release-all`（绿） | `SCHEDULED_STORE_RELEASE_SELFTEST=PASS` + `SCHEDULED_REVISION_SELFTEST=PASS`（原两行保留） |
| `extra_days_container_guard` | 11 fixtures | `C1 死别名` / `C1 窗口错位` / `C3 toIntArray` / `C3 toList().toTypedArray()` / `C4 单文件清零` / `listOf` / `生产者返回 IntArray` / `未知值` / `无写入者` + 2 条 clean | `EXTRA_DAYS_SELFTEST=PASS (11 fixtures)` |

### L2 外部见证反攻（最硬证据）

```sh
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates claim_release_guard.py,offload_permission_reset_sync_check.py,\
scheduled_store_release_guard.py,extra_days_container_guard.py --json /tmp/reattack-05.json
```

修复前（同一脚本、同一基线）：

```
claim_release_guard.py              C1/C2/C3   STILL_BLIND   rc=0
offload_permission_reset_sync_...   C1/C0/C2   STILL_BLIND   rc=0
scheduled_store_release_guard.py    PRESENCE/C1/C4/S1  STILL_BLIND  rc=0
extra_days_container_guard.py       C1/C3/C4   STILL_BLIND   rc=0
→ CAUGHT=0  STILL_BLIND=13
```

修复后：

```
claim_release_guard.py              C1  CAUGHT rc=1   dataflow
claim_release_guard.py              C2  CAUGHT rc=1   spelling
claim_release_guard.py              C3  CAUGHT rc=1   parser-death
offload_permission_reset_sync_check.py  C1  CAUGHT rc=1   dataflow
offload_permission_reset_sync_check.py  C0  CAUGHT rc=1   dataflow
offload_permission_reset_sync_check.py  C2  CAUGHT rc=1   parser-death
scheduled_store_release_guard.py    PRESENCE CAUGHT rc=1  dataflow
scheduled_store_release_guard.py    C1  CAUGHT rc=1   dataflow
scheduled_store_release_guard.py    C4  CAUGHT rc=1   dataflow
scheduled_store_release_guard.py    S1  CAUGHT rc=1   parser-death
extra_days_container_guard.py       C1  CAUGHT rc=1   anchor
extra_days_container_guard.py       C3  CAUGHT rc=1   dataflow
extra_days_container_guard.py       C4  CAUGHT rc=1   completeness
→ CAUGHT=13  STILL_BLIND=0  → RESULT: ALL CAUGHT
```

刀是外部模型写的（`meta-autdit-v5/reports/report-A.md`），不是我们写的 ⇒ 独立性成立；`reattack.py` 每条都 patch → 跑门 → restore → sha256 比对（`restore=True`）。

### L3 邻门

```
offload_cli_args_check                rc=0
offload_host_path_check               rc=0
offload_media_b64_wire_check          rc=0
offload_relative_path_unify_check     rc=0
schedule_date_source_check            rc=0
```

### L4 全量

```sh
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"   # 持锁 + ENOSYS 自愈
python3 scripts/scan/test_scan.py
```

见 §⑥ 的最终读数（并发施工窗口期读数附后）。

---

## ⑥ CI

| 项 | 值 |
|---|---|
| run id | 见下方回填 |
| conclusion | 见下方回填 |
| head_sha | 必须与本地 `git rev-parse HEAD` 逐字符一致 |

---

## ⑦ 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

1. **`claim_release_guard` — `is_ownership_cond` 仍不折叠 `!(…)` 形式的常量条件**
   触发条件：出现 `if (!(true || x === y))` 这类取反包裹的常量条件时。
   方案：`fold_bool` 增加 `!` 一元处理（对子表达式取反后继续折叠）。
   搁置理由：当前形态不在任何见证里，且 `!` 作用在归属比较上通常反而更严格（fail-closed 方向安全，不会静默放行）。

2. **`extra_days_container_guard` — 值表达式经辅助函数二次加工时不追第二跳**
   触发条件：有人写 `putExtra(EXTRA_DAYS, sanitize(daysForRepeatMode(m)))`。
   方案：`classify_value` 对未知单参调用追一跳调用点，读取该函数形参声明类型/返回类型。
   搁置理由：真实树只有 `arrayListOf(...)` 与 `?.let { it }` 两种形态；多追一跳会把「无法判定」这一类判据的复杂度翻倍，而当前 fail-closed 已经覆盖（未知 → 红）。

3. **`scheduled_store_release_guard` — `_expr_end` 依赖 4 空格缩进识别声明边界**
   触发条件：有人把 `ScheduledTasksStore` 的成员函数改成非 4 空格缩进（或整体重排版）。
   方案：把 `DECL_TAIL_RE` 的判定换成「行首缩进 ≤ 上一声明的缩进」的通用推导。
   搁置理由：本仓 Kotlin 统一 4 空格缩进（`FUN_RE` 本身就锚了这一事实），改造收益低；且误判方向是切片变长（多读一点文本），不会静默放行。

4. **`offload_permission_reset_sync_check` — `prune_dead_branches` 只处理 `if`，不处理 `when` 的常量分支**
   触发条件：出现 `when (false) { … }` 或 `when (x) { true -> … }` 这类常量分支承载 clear 调用。
   方案：给 `when` 加同样的空白化处理。
   搁置理由：`when` 的常量分支在本仓从未出现；`if (false)` 是本支见证的实际形态。

---

## ⑧ 复现

```sh
cd /var/minis/shared/work/meta-wide3/wt-05

# 真树 + 自证
for g in claim_release_guard offload_permission_reset_sync_check \
         scheduled_store_release_guard extra_days_container_guard; do
  python3 scripts/scan/$g.py . || echo "RED: $g"
  python3 scripts/scan/$g.py --selftest | tail -1
done

# 13 条外部见证反攻（rc=0 = 全部 CAUGHT）
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" \
  --gates claim_release_guard.py,offload_permission_reset_sync_check.py,\
scheduled_store_release_guard.py,extra_days_container_guard.py \
  --json /tmp/reattack-05.json

# 全量（持锁运行器，别裸跑 scan.sh）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
python3 scripts/scan/test_scan.py
```
