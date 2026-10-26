# FIX-R3-t08 · parts_json_funnel_check + skill_restore_session_cleanup_check（第三轮 dual-gate 收口）

- 分支：`fix/r3-partsjs-skillrestore`（基线 `main` @ `f0f14d46d`）
- 门提交：`1c3f9c497`（2 个门脚本，+693/−13）
- 生产 `.kt` 改动：**0 行**；`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` **零触碰**
- 门数 112 / `test_scan` 夹具 344 **不变**（无新增门）

## 1. 门（本次动的两门与它们承担的判据）

| 门 | 本次改动的判据 | 判据原来信什么 | 现在信什么 |
|---|---|---|---|
| `parts_json_funnel_check` | [R3] 逐行降级、[R5] appendMessage 写列、[R7] debug 读面 | 「某个 DAO 调用不在函数里」/「写的那一列走了漏斗」/「三个 serial 名各有分支」 | 代理行**真的回到分页结果**；**两列各走各的漏斗**；serial 名**从 `@SerialName` 派生**且每支都要交棒 |
| `skill_restore_session_cleanup_check` | Arm 5 幸存者过滤、Arm 2b 原地更新分支、Arm 9b `persisted` 标志 | hook 被调用过且实参不是常量 / `return add(preferredId=…)` 带 id / 声明 + `finally` 回写存在 | 过滤的**成员关系极性**；分支**可达且真的替换**；标志**在成功路径上真的翻 true 且晚于落库** |

## 2. 轴（每条见证属于哪个失效轴）

| 见证 | 轴 | 一句话 |
|---|---|---|
| #115 03-1 | dataflow（写入侧改道） | `partsJson = capped` 改成 `partsJson = partsJson`：漏斗就在上一行，被算出来的截断值没人用 |
| #116 03-2 | dataflow（下游过滤） | 代理行造出来又按自己的 id 前缀过滤掉：`out.size < total` 不变量重新破 |
| #117 03-3 | spelling/白名单（名单缺项） | 手写名单只列 toolUse / toolResult / mediaRef，`"text" -> Unit` 不在检查面 |
| #118 04-1 | 谓词极性（对称轴） | `id in survivors` → `!in`：跳过的是被删的，通知的是活着的 |
| #119 04-2 | 常量折叠（守卫恒假） | `false && …` 保留整段拼写，replace 分支永不进入，`add()` 拒绝重复 id 返 null |
| #120 04-3 | 赋值常量（对称轴） | `persisted = true` → `false`：落库成功仍按失败回写，下个 New Chat 带出旧草稿 |

## 3. 见证重放表

`probe/t08-pre.json` / `probe/t08-post.json`，补丁 `probe/t08-w1{15..20}.patch`，重放器 `probe/t08_replay.py`。

| 见证 | kind | gates_involved | pre（原判据） | post（修后） | 缝归属 |
|---|---|---|---|---|---|
| #115 | dual-gate | parts_json **rc=0** / chat_session_id_binding **rc=0** | BLIND_CONFIRMED | owner **rc=1**（[R5]）· chat_session_id_binding rc=0 | parts_json_funnel_check |
| #116 | dual-gate | parts_json **rc=0** / skill_restore **rc=0** | BLIND_CONFIRMED | owner **rc=1**（[R3]）· skill_restore rc=0 | parts_json_funnel_check |
| #117 | symmetric | parts_json **rc=0** | BLIND_CONFIRMED | **rc=1**（[R7]） | parts_json_funnel_check |
| #118 | dual-gate | skill_restore **rc=0** / parts_json **rc=0** | BLIND_CONFIRMED | owner **rc=1**（Arm 5）· parts_json rc=0 | skill_restore_session_cleanup_check |
| #119 | dual-gate | skill_restore **rc=0** / log_url_redaction_guard **rc=0** | BLIND_CONFIRMED | owner **rc=1**（Arm 2b）· log_url_redaction_guard rc=0 | skill_restore_session_cleanup_check |
| #120 | symmetric | skill_restore **rc=0** | BLIND_CONFIRMED | **rc=1**（Arm 9b） | skill_restore_session_cleanup_check |

- 6/6 全部 `BLIND_CONFIRMED`（无 `CAUGHT`、无 `NOT_REPRODUCIBLE`、无假见证）；补丁锚点命中数各 1，与任务书 §3 的 patch 逐字一致。
- 还原：每次 `git checkout` 后 sha256 与原始逐字节相同（重放器内置断言），`git status --porcelain` 全程只显示两个门脚本。
- 修后 6/6 全部被抓住，且**每条红在自己那条判据上**（`probe/t08-post.json` 的 `out` 字段贴了门的原话，见 §4.2 引文）。

### 3.1 dual-gate 的「两门都红」判定

四个 dual-gate 见证里，**只有两条的另一半在本支管辖范围内**，另两条的另一半是冻结面（任务书 §1.2 明令不许改），因此按任务书 §2.2 的替代条款处理：

| 见证 | 另一半门 | 判定 |
|---|---|---|
| #115 | `chat_session_id_binding_check` | **该门不该管这条缝**：它管的是 sessionId 绑定与 `SQLiteConstraintException` 重试块（判据 19），补丁不动 sessionId；`partsJson` 这一列的内容完整性归 `parts_json_funnel_check` [R5]。且该门是冻结面（§1.2），本次不许改。**由 `parts_json_funnel_check` 管**。 |
| #116 | `skill_restore_session_cleanup_check` | **该门不该管这条缝**：`loadPageRowByRow` 的分页降级不在它的守卫声明（技能恢复 id 透传 / 会话删除级联 / 草稿晋升）之内。**由 `parts_json_funnel_check` [R3] 管**。 |
| #118 | `parts_json_funnel_check` | **该门不该管这条缝**：`deleteEmptySessions` 的幸存者过滤与 parts_json 的读写漏斗无关。**由 `skill_restore_session_cleanup_check` Arm 5 管**。 |
| #119 | `log_url_redaction_guard` | **该门不该管这条缝**：它管日志里 URL 全值外泄；技能导入分支的可达性与日志无关，且它是冻结面。**由 `skill_restore_session_cleanup_check` Arm 2b 管**。 |

攻击面结论：四个 dual-gate 见证**全部无法再让两门同时绿**——归属门各自 rc=1，攻击不成立。未让另一半也红是刻意的：把一个"内容完整性"判据塞进 sessionId 门、把"分页降级"塞进会话清理门，属于跨域装置膨胀（与「装置比例」纪律冲突），按任务书条款登记而不是硬塞。

## 4. 修法

### 4.1 `parts_json_funnel_check`

**[R3] 降级路径必须交回它造出来的代理行**（新函数 `row_by_row_proxy_violations`，判据从「否定式」变「值流」）：

1. `oversizedProxyRow(` 必须存在，且**至少一处 `path_feasible`**（放进死分支就红）；
2. 代理值必须**绑定到局部**（`val row = try { … } catch { oversizedProxyRow(…) }` 的 rhs 区间覆盖代理站点）；
3. 累加器**从树派生**（`ArrayList|MutableList|mutableListOf|arrayListOf` 的绑定名），不接受硬编码 `result`；
4. 累加器站点（`.add(` / `+=`）的实参必须解析到该局部，**含 `?.let { add(it) }` 转手**；
5. 从 add 到落地之间，`enclosing_conditions` 里**不得出现按行自身字段的过滤**（`row.id` / `row?.x`），`?:` 的另一臂不得是 `continue/return` 之类非代理值，`takeIf/takeUnless/filter/filterNot` 作用在代理行上一律红；
6. 派生不出累加器 → 红（ROT，不许 fail-open）。

**[R5] `appendMessage` 的两列同判**：`funnel_bypasses(append_body, append_lit, "partsJson", "capPartsJsonForRow")` 与既有的 `reasoningContent` 合并。`partsJson = partsJson` 的 rhs 经 `value_sources` → `local_sources` 解析成函数参数，不在 `NULLISH`、不含漏斗 ⇒ 命中「raw use of partsJson outside capPartsJsonForRow(...)」。

**[R7] serial 名集合从模型派生**（新函数 `derive_part_serial_names`）：读 `data/model/ContentPart.kt` 的 `@SerialName("…")`，真树得到 4 个（`text` / `mediaRef` / `toolUse` / `toolResult`）；文件不存在（selftest 的极简夹具）才回退到内置元组，**存在但解析不出 → fail-closed 红**。新增一个 Part 类型当天就要求 RPC 覆盖面（selftest 臂 `r7-new-part-not-dispatched` 用 `thinking` 第 5 类型验证）。

### 4.2 `skill_restore_session_cleanup_check`

**Arm 5 幸存者过滤极性**（新函数 `check_survivor_polarity` + `_membership`）：

- 幸存者集合**从数据流派生**：`dao.deleteEmptySessions(` 之后、rhs 含 `dao.` 的那个 `val` 绑定；
- 候选 id 从 `for (x in …)` 或 hook 实参派生；
- `_membership` 求值四种形态：`x in C` / `x !in C` / `C.contains(x)` / `C.any|none { it == x }`（含外层 `!` 翻转），**求不出 → None = 不算保护（fail-closed）**；
- 两种合法保护：包裹（`if (x !in C) { … hook … }`）或早退守卫（`if (x in C) continue|return`），且必须与 hook **同在一个循环体内**（`for` 区间判定；无 `for` 才退化到 brace 深度）；
- 早退守卫求值为**假**（`!in`）⇒ 明确报「polarity is inverted」并红。

**Arm 2b 原地更新分支可达且真的替换**（新函数 `check_skill_replace_branch`）：

- 分支识别**按结构**：`add(` 之前、条件里出现 id 且含 `any|contains|firstOrNull|find` 的 `if`；
- `guard_feasible(cond)` 必须为真——`const_bool` 折叠 `false && …` ⇒ 恒假 ⇒ 红；
- 分支体必须**有动作**（`_skills.value =` / `db.`/`dao.` 调用 / `writeSkillMd(` / `replace(` / 任一赋值）**且返回非 null**（`const_value` 判 null 字面量）。

**Arm 9b `persisted` 标志**（新函数 `check_promote_persisted`）：

- 标志从 `var X = false`（`: Boolean = false` 也认）派生，并要求 `if (!X)` 回写分支存在（否则这条判据不适用，交给既有 Arm）；
- 声明后的每次写都要过 `const_bool`：**折叠成 false ⇒ 红**（成功路径按失败处理）；
- 必须至少有一次 true，且**不得早于持久化写**（`DURABLE_WRITES` 的最早位置）。

### 4.3 修后反攻的红在哪（原文引文）

- #115：`[R5] FAIL appendMessage binds a payload without the ceiling funnel — line 31: raw use of partsJson outside capPartsJsonForRow(...)`
- #116：`[R3] FAIL the guard row != null && !row.id.startsWith(" ") filters the proxy row by its own fields …`
- #117：`[R7] FAIL messageToJson matches "text" but never hands the part on (arm body is Unit / dead …)`
- #118：`deleteEmptySessions skips the sessions that are NOT survivors (if (id !in survivors) continue|return) — the polarity is inverted …`
- #119：`importFromContent's replace-existing guard … is constant-false — the branch can never run …`
- #120：`promoteDraftIfNeeded writes persisted = false on the promotion path — the durable write lands, finally still treats it as failed …`

### 4.4 新增 selftest 臂

| 门 | 臂 | 断言 |
|---|---|---|
| parts_json | `r3-proxy-row-kept` / `r3-proxy-row-filtered-out` / `r3-proxy-row-elvis-dropped` | 干净形态 [R3] PASS；按 id 前缀过滤 / `?: continue` 两形态 [R3] FAIL |
| parts_json | `r5-partsjson-through-funnel` / `r5-partsjson-bypasses-funnel` | `partsJson = capped` PASS；`partsJson = partsJson` FAIL |
| parts_json | `r7-text-branch-dropped` / `r7-derived-set-live` / `r7-new-part-not-dispatched` | 派生集合下 `"text" -> Unit` FAIL；干净形态 PASS；模型新增 `thinking` 而 RPC 未覆盖 ⇒ FAIL（派生有效的正面证据） |
| skill_restore | `survivor-polarity fixture (04-1)` | `!in` 翻转 ⇒ 恰好 1 条违规，且是「polarity is inverted」 |
| skill_restore | `replace-unreachable fixture (04-2)` | `false &&` ⇒ 「constant-false」违规 |
| skill_restore | `flag-not-flipped fixture (04-3)` | `persisted = false` ⇒ 恰好 1 条，且是「writes persisted = false」 |

臂数：`parts_json_funnel_check` **48 arms**（`PARTSJSON_FUNNEL_SELFTEST=PASS (48 arms)`，臂数从运行期统计而非手写常量）、`skill_restore_session_cleanup_check` **52 arms**（原 49，+3）。两门均只增未减。

## 5. 四层证据

1. **本门**：真树 `parts_json_funnel_check: PASS`（rc=0）、`PASS skill_restore_session_cleanup_check (id threaded, cleanup closed)`（rc=0）；`--selftest` 两门 rc=0，臂数如上（含新臂，逐条 OK）。
2. **邻门**：`chat_session_id_binding_check` / `log_url_redaction_guard` / `dao_loadmessages_check` / `entry_id_rename_integrity_check` / `compact_decision_gate_check` / `debug_leak_guard` 全部 rc=0。
3. **全量（逐门独立进程，不用 `scan.sh`）**：`probe/t08_gate_loop.py`，`probe/t08-gates.json` → **109/109 rc=0**（107 道位置参数形态 + `calendar_events_nullable_predicate_check` / `tts_voice_and_sanitizer_check` 两道 `--root .` 形态），失败列表为空。`test_scan.py` 未在沙箱跑（见下），**全量夹具证据交 CI 承担**。
   - 沙箱事实（S28，本会话第 N 次复现）：18:03–18:35 期间三次调用被环境杀（`OSError: [Errno 38] Function not implemented` 落在 `subprocess` / `/usr/lib/python3.12` / 写文件上，exit −1），后台 `nohup` 循环在工具调用返回后即被回收；健康路径 = **前台分块**（每次 ≤20 门）+ 每门独立进程，最终四块跑完 109 门全绿。中途被杀的两次留下半成品补丁，已 `git checkout` 还原并用 sha256 核对。
4. **CI**：见 §6。

## 6. CI

- run **#2731** / id `40309439887` / attempt **1** / conclusion **success**（2026-10-26T10:42:03Z → 11:07:29Z，约 25 min）
- `head_sha` = `1c3f9c4972aea5801937b760288e8410b8927229`，与本支门提交**逐字符一致**（`git rev-parse HEAD` 同值）
- job 日志 1,488,792 B / 17,878 行（下载拆分为 25 个 step 文件后拼接），关键读数逐条核对：

| 判据 | 读数 |
|---|---|
| `Summary: 112 passed, 0 failed` | **恰 1 条** |
| `ALL 344 CASES PASS` | **恰 1 条**（夹具数未变） |
| `^ e: `（Kotlin 编译错误） | **0 条** |
| 门头 `[N/112]` | **112 个编号互异**（1..112 全覆盖） |
| 含 `FAILED` 的 6 行 | 全部是 `… FAILED … PASSED` 形式的用例名子串，非失败 |
| 执行的 step | 1..22 全部执行（Publish 为 step 23，`if: ref == main` ⇒ 分支上不执行，符合预期） |
| 本支两门的自证 | `selftest has teeth (PARTSJSON_FUNNEL_SELFTEST=PASS …)` / `(SKILL_RESTORE_SESSION_CLEANUP_SELFTEST=PASS)` 各 **1 条**，两门 `real tree green` 各 **1 条** ⇒ 48 / 52 臂在 CI 里真跑并在真树上绿 |

即：**分支 CI 绿 = 确定性停止节点已到达**。未合并 main（起手权在主会话）。


## 7. 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_membership` 只认 `in` / `!in` / `contains` / `any|none { it == x }` 四形态 | 幸存者过滤改成 `survivors.indexOf(id) >= 0` / `filter { }` / 集合差集 | 在 `_membership` 增加 indexOf、filter 形态（求值仍返回 True/False/None） | 当前仓库只有 `in` 一种写法；宽扫会给未知形态增加误红面，而 fail-closed 已保证不漏（未知 = 报未受保护） |
| R2 | 代理行的 λ 转手只认 `?.let` / `?.also` + `it` | 分页代码改成 `row?.run { result.add(this) }` 或命名参数 lambda | 把 `match_bracket` 的区间判定扩到 run / 命名 lambda，并按参数名映射 | 现实现无该形态；误红方向安全（会报「no add receives the proxy row」逼人重看） |
| R3 | 累加器派生只认 `ArrayList|MutableList|mutableListOf|arrayListOf` | `loadPageRowByRow` 改写为 `buildList { … }` | 在 acc 派生里加 `buildList`/`buildList(…){` 分支，`+=` 站点改判 `add(` | 现实现是 `ArrayList(limit)`；buildList 形态下 add 站点语义不同，需要真实样本验证再上 |
| R4 | [R7] 派生集合含**全部** `@SerialName`，没有 opt-out | 新增一个**故意不暴露给 debug RPC** 的 Part 类型 | 在 `ContentPart.kt` 侧加显式 opt-out 注解（如 `@RpcHidden`），`derive_part_serial_names` 跳过带注解的条目 | 现在 4 个 Part 全量暴露给 RPC；先有需求再引入逃生口，避免"注解一加，门就瞎" |
| R5 | [R5] 的 rhs 切分对具名参数按顶层逗号取第一段 | `MessageEntity(...)` 改成位置参数构造 | 按 `MessageEntity` 构造器的参数顺序做位置→名字映射 | 仓库统一具名参数；位置参数下 fail-closed 会红（不会静默放行） |
| R6 | `check_promote_persisted` 只认 `var X = false` 声明 | 标志改成 `var persisted by mutableStateOf(false)` | 增加委托属性形态（`by mutableStateOf(false)`）的声明识别 | 现写法是普通 `var`；且 `if (!persisted)` 的回写存在性由既有 Arm 兜底，退化风险有限 |
| R7 | Arm 2b 的「有动作」判定较宽（任一赋值 / db 调用 / 写函数即算） | 出现「写到了别处」的假替换 | 用 `value_sources` 追 `UPDATE skills … WHERE id = ?` 的行目标与绑定，要求与 `id` 绑定同源 | 门的职责是「分支可达 + 返回非空 + 不是空操作」；写对哪一行由 DB 层与单测覆盖，门不必重复实现 SQL 语义 |
| R8 | `same_loop` 在无 `for` 语句时退化为 brace 深度比较 | `deleteEmptySessions` 改成 `candidates.forEach { id -> … }` | 按 forEach 的 lambda 区间建模（`match_bracket` 已够用） | 现实现是 `for (id in candidates)`；退化路径只会让保护判定更严（fail-closed） |
| R9 | [R3] 要求代理构造、绑定、累加都在 `loadPageRowByRow` 体内 | 把这套逻辑抽到 helper（`loadPageRowByRow` 只剩一行调用） | 允许一跳同文件委托（与既有 [R11] 的「一跳委托」口径一致） | 现实现全在一个函数体内；提前实现委托追踪会在没有第二种形态时凭空长代码 |

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t08

# 1) 见证重放（pre 在原判据上跑，post 在修后跑；自带还原 + sha256 断言）
git checkout f0f14d46d -- scripts/scan/parts_json_funnel_check.py \
    scripts/scan/skill_restore_session_cleanup_check.py   # 只在需要重跑 pre 时执行
python3 probe/t08_replay.py pre
python3 probe/t08_replay.py post          # 用修后的门
git checkout HEAD -- scripts/scan/parts_json_funnel_check.py \
    scripts/scan/skill_restore_session_cleanup_check.py

# 2) selftest（臂数在收尾标记里）
python3 scripts/scan/parts_json_funnel_check.py . --selftest | tail -1
python3 scripts/scan/skill_restore_session_cleanup_check.py --selftest | tail -2

# 3) 真树 + 邻门
for g in parts_json_funnel_check skill_restore_session_cleanup_check \
         chat_session_id_binding_check log_url_redaction_guard \
         dao_loadmessages_check entry_id_rename_integrity_check \
         compact_decision_gate_check debug_leak_guard; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?"
done

# 4) 全量（分块，每块 ≤20 门；不要跑 scan.sh：沙箱 Errno 38）
python3 probe/t08_gate_loop.py 0 20
python3 probe/t08_gate_loop.py 20 40      # …直到 109
python3 scripts/scan/calendar_events_nullable_predicate_check.py --root .
python3 scripts/scan/tts_voice_and_sanitizer_check.py --root .
```

## 9. 交付物

- `scripts/scan/parts_json_funnel_check.py`（+362/−13）
- `scripts/scan/skill_restore_session_cleanup_check.py`（+344）
- `fix-reports/FIX-R3-t08.md`（本文件）
- 探针（不入库）：`probe/t08_replay.py`、`probe/t08_patches.py`、`probe/t08_gate_loop.py`、`probe/t08-pre.json`、`probe/t08-post.json`、`probe/t08-w1{15..20}.patch`、`probe/t08-gates.json`
