# FIX-WIDE4-04：三门判据从「拼写存在」改到「效果达成」

- 分支：`fix/wide4-04-skillfile-loopstat-provider`（基于 `base/wide4-shared` = `19e70dab` + 两个共享模块）
- 工作树：`/var/minis/shared/work/meta-wide4/wt-04`
- 改动面：**只改本支 3 个门脚本**（+986 / −415），生产 `.kt` **0 行**，`scan.sh` / `test_scan.py` **零触碰**，门数仍 **112**、夹具仍 **344**
- 见证：**19 条**（13 条 FALSIFIED + 6 条原 CAUGHT），复攻 **19/19 CAUGHT**，基线对照 **13 盲 / 6 已抓**（与报告逐条一致）

---

## §1 门与轴

| 门脚本 | 见证（新抓 / 已抓） | 轴分布 |
|---|---|---|
| `scripts/scan/skill_file_save_result_check.py` | 6（4 / 2） | family-hole 2 · dataflow 1 · parser-death 1 ／ 已抓均为 dataflow |
| `scripts/scan/loop_tool_status_transition_check.py` | 7（5 / 2） | family-hole 2 · dataflow 2 · parser-death 1 ／ 已抓 anchor 2 |
| `scripts/scan/provider_boundary_guard.py` | 6（4 / 2） | family-hole 2 · dataflow 1 · parser-death 1 ／ 已抓 spelling 1 + anchor 1 |

合计新抓 **13**（family-hole 6 / dataflow 4 / parser-death 3），已抓保持 **6**。

三条轴的含义（本支实际打到的形态）：

- **family-hole**：同一族里只认一种拼写。`onBack()` vs `onBack.invoke()`；`|| true` vs `|| (1 == 1)`；`p.sendMessage(` vs 裸 `sendMessage(`；豁免按**变量名**而不是按**绑定身份**。
- **dataflow**：调用了但结果不决定任何东西。`writeSkillFile(…) || true`（布尔被丢弃）、`if (current == PENDING || true)`（条件短路）、`if (x == cue) current else current`（两臂同值 = 没有清 cue）。
- **parser-death**：门自己的解析器被杀。`"${"}"}"` 泄漏 `}` 提前闭合死块；`""" " """` 里的孤立引号把中间的真违规抹白。

---

## §2 每条见证：原补丁 → 旧判据为何瞎 → 新判据 → 新臂

### 2.1 `skill_file_save_result_check`

| # | 见证 | 原补丁 | 旧判据为何瞎 | 新判据 | 新臂 |
|---|---|---|---|---|---|
| 1 | `skill-arm2-or-true` | `writeSkillFile(skillId, relativePath, contentToSave) \|\| true` | arm2 只要求「IO 块里出现 writeSkillFile」+「块尾不是字面量常量」；`\|\| true` 让整条表达式仍有调用，块值却是常量 | `_fold_bool` 按 Kotlin 优先级折叠整条表达式，`_block_last` 取块值、`_leaf_is_write` 要求它**就是** write 的返回值（`env` 传递局部别名） | `SAVE_W4_OR_TRUE` |
| 2 | `skill-arm3-invoke` | 在 `if (ok)` 之前插 `onBack.invoke()` | `EXIT_CALL` 只写字面 `onBack()`；`invoke()` 形态漏检，且「`isEditing = false` 全屏只出现一次」把它顶过去了 | `EXIT_CALL` 家族 = `onBack()` ∪ `onBack.invoke()`（`_norm` 归一化 receiver 与 invoke 两种拼写），并要求**每个** pop 都落在 live `if (ok)` 块内 | `SAVE_W4_INVOKE_POP` |
| 3 | `skill-arm6c-const-compare` | `enabled = isLoaded \|\| (1 == 1)` | `_literal_decides` 只比字面 `true`/`false`，`(1 == 1)` 不算常量 | `_fold_bool` 折叠任意常量比较；`_live_gate_flag` 还要求该 flag 在同一个 `LaunchedEffect` 内被**真的翻成 true** | `SAVE_W4_CONST_COMPARE`、`SAVE_W4_FLAG_NEVER_FLIPPED` |
| 4 | `skill-mask-template-brace` | `if (false) { val _s = "${"}"}"; onBack() }` | 门自带 `strip_comments` 不感知字符串模板内层引号，模板里的 `}` 把 `if (false)` 块提前闭合 ⇒ 死块里的 `onBack()` 读作 live | 判据改为共享 `_kotlin_lexer.analyse()` 的投影（模板嵌套引号感知），块区间由词法层给出 | `SAVE_W4_TEMPLATE_BRACE` |
| 5 | `skill-arm2-tail-true(caught)` | 块尾追一条 `true` 语句 | wide3-08 已抓 | 保持 | `SAVE_W1_TAIL_TRUE` |
| 6 | `skill-arm6c-or-true(caught)` | `enabled = isLoaded \|\| true` | wide3-08 已抓 | 保持 | `SAVE_W2_OR_TRUE` |

门内还新增一条**允许形态**绿夹具 `SAVE_W4_OK_SAVED`：`val saved = writeSkillFile(…); …; saved`（合法的局部中转）必须保持绿 —— 加牙不能误伤正当写法。

### 2.2 `loop_tool_status_transition_check`

| # | 见证 | 原补丁 | 旧判据为何瞎 | 新判据 | 新臂 |
|---|---|---|---|---|---|
| 1 | `loop-t1-not-true` | `if (!true) withContext(Dispatchers.Main) { …publish… }` | T1 只认 `if (false)` 字面量，`!true` 不折叠 | `_fold_bool`（`!` > `&&` > `\|\|`）+ `when_contexts`：`when (false)`、`when (true)` 与前缀 `!` 一律建模为 dead/live 上下文 | `w4_t1_not_true`、`w4_t1_when_false` |
| 2 | `loop-t3-not-true` | `if (!true && cueBlock.toolStatus != PENDING) continue` | T3 只查「PENDING 比较还在」 | DECIDE 判定：折叠后条件必须由 PENDING 比较**决定**；`cue_guard_polarity` 另判极性（`== PENDING → continue` 也不成立） | `w4_t3_not_true`、`w4_t3_polarity` |
| 3 | `loop-t5-or-true` | `if (current == PENDING \|\| true) RUNNING else null` | T5 只要求「`statusAtDispatch` 里有 if」 | `policy_if` 解析策略表达式：必须有一处 **live** 的 PENDING 比较，且两臂分别是 `RUNNING` / `null` | `w4_t5_or_true`、`w4_t5_both_running` |
| 4 | `loop-t6-both-current` | `if (current == queueCue) current else current` | T6 只要求「有 if」 | `split_else_top` + `branch_value`：匹配臂必须**清空** cue（`""`），两臂取值相同即红 | `w4_t6_both_current`、`w4_t6_inverted` |
| 5 | `loop-mask-close-template` | `if (false) { val _close = "${"}"}"; withContext{…}; val _open = "${"{"}" }` | 模板里的 `}` 提前闭合死块 ⇒ 死 publish 读作 live，同时真判据位置被挤走 | 共享 lexer 投影（块区间从词法层取），死块整体屏蔽 | `w4_t1_template_brace` |
| 6 | `loop-t1-false-when(caught)` | `if (false) publish()` | 已抓 | 保持 | `critical_push_dead_guard` |
| 7 | `loop-t5-unconditional(caught)` | `fun statusAtDispatch(current) = RUNNING` | 已抓 | 保持 | `status_at_dispatch_unconditional` |

### 2.3 `provider_boundary_guard`

| # | 见证 | 原补丁 | 旧判据为何瞎 | 新判据 | 新臂（13 fixtures 之一） |
|---|---|---|---|---|---|
| 1 | `prov-implicit-receiver` | `.also { it.run { sendMessage(…) } }`（裸调用） | `CALL_RE` 要求 `receiver.method(` | 隐式接收者：在被**绑定到 provider** 的作用域内，裸入口调用同样红（`_enclosing_function` + `_provider_bound`） | `implicit_receiver` |
| 2 | `prov-vm-bound-to-provider` | `val vm = ProviderFactory.create(…); vm.sendMessage(…)` | `EXEMPT_RECEIVERS` 按**名字**豁免（`vm` 在白名单里就放行） | 豁免按**绑定身份**：`_provider_bound` 认「名字被赋值为 provider 实例 / 来自 provider 创建模板」，名字豁免只对真正绑到 ViewModel 的接收者成立 | `vm_bound_to_provider` |
| 3 | `prov-viewmodel-stream` | `.also { viewModel -> viewModel.streamMessage(…) }` | 名字 `viewModel` 直接豁免，不看入口 | `_exemption` 集中判定：豁免只覆盖 ViewModel 层入口（`ProviderExecutionGateway` 显式白名单），对 provider 入口（`sendMessage` / `streamMessage` / `rawPassthroughRequest`）任何接收者都红 | `viewmodel_stream` |
| 4 | `prov-raw-string-quote` | 前后各一条 `""" " """`，中间夹 `p.sendMessage(…)` | `strip_noise` 的逐字符状态机不认 `"""`，孤立 `"` 把状态翻进字符串 ⇒ 中间真违规整段抹白 | 共享 lexer 的 `strip_noise`（raw string 感知） | `raw_string_quote` |
| 5 | `prov-receiver-p(caught)` | `p.sendMessage(…)` | wide3 已抓 | 保持 | `receiver_p` |
| 6 | `prov-comment-exempt(caught)` | `.streamMessage(…)` 链式跨行 | wide3 已抓 | 保持 | `chain_break` |

---

## §3 判据改动清单（函数名级）

**门 A**：新增/重写 `_fold_bool`、`_norm`、`_leaf_is_write`、`_block_last`、`_value_from_write(expr, env)`、`_flip_after_read`、`_live_gate_flag`、`_unguarded_pop`、`enclosing_io_block`；`EXIT_CALL` 家族扩展；`strip_comments` 的调用点下沉到 `run_checks` / `run_offmain_checks`，统一走共享 lexer 投影；`main()` 支持 `--selftest`。

**门 B**：新增 `_fold_bool`、`when_contexts`、`enclosing(ifs, whens, idx)`、`cue_guard_polarity`、`split_else_top`、`branch_value`、`policy_if`、`short_body`；重写 `fun_body`、`norm_atom`、`split_top`；**删除**自带的 `blank_comments` / `mask_code` / `line_of` 副本，改用 `_kotlin_lexer`（区域锚点仍是注释偏移，`analyse()` 保证偏移不变）。

**门 C**：新增 `_receiver_chain`、`_dot_before`、`_enclosing_function`、`_provider_bound`、`_exemption`、`_views`、`WORKER_RE`、`PROVIDER_BIND_TEMPLATES`；`receiver_before` 改为链式取接收者；`strip_noise` 改用共享 lexer（raw string 感知）。`--selftest` 从 8 fixtures 扩到 **13**。

---

## §4 四层证据

**L1 门自身（真树）**：三门 `python3 scripts/scan/<gate>.py .` 均 rc=0；`--selftest` 全 PASS（A：11 violation + 6 offmain + 允许形态绿；B：24 fixtures；C：13 fixtures）。

**L1 全量**：`sh scripts/scan/scan.sh` → **`Summary: 112 passed, 0 failed`**（112 门头全部出现，`probe/scan-full.log`）；`python3 scripts/scan/test_scan.py` → **`ALL 344 CASES PASS`**。冻结面（`scan.sh` / `test_scan.py`）零触碰，门数/夹具数不变。

**L2 复攻（本支最硬证据）**：

```
python3 probe/reattack.py --root . --pristine /var/minis/shared/work/meta-wide4/basetree-04 --only <gate>
```

- **修复后 19/19 CAUGHT**：`probe/after-skill.json`(6) · `after-loop.json`(7) · `after-prov.json`(6)，全部 `rc=1`、`needle=True`（触发的是**该见证对应**的判据文本，不是旁证）、`restore=True`（sha256 与打补丁前逐字节一致）、`problems=[]`。
- **基线对照 13 盲 / 6 已抓**：`probe/before-*.json`（`--root /var/minis/shared/work/meta-wide4/basetree-04`，即未修复的门 + 同一份源码）—— 与外部报告的 13 FALSIFIED / 6 CAUGHT 逐条一致，说明见证本身不是「换个门也能抓」的伪证。
- **锚点机械核对**：复攻驱动对每条 `find` 断言 `text.count(find) == 1`，不唯一即硬错（防锚点漂移把见证变成空操作）。

**L3 邻门**：15/15 rc=0 —— `backup_remap_rollup` · `compact_decision_gate` · `compact_entry_pin_migration` · `compact_pin_truth` · `dao_loadmessages` · `filepreview_print_bounded` · `ime_inset_guard` · `loop_persist_slice` · `router_lock` · `skill_frontmatter_roundtrip` · `skill_import_offmain` · `subagent_handback` · `sysinfo_state_thread` · `truncated_call_guard` · `turn_tail_finalize_order`。三门之间无交叉 import（`grep` 零命中），不存在「改 A 门影响 B 门」的传导面。

**L4 CI**：见 §5。

**★ 本支自伤一条（已闭环，形态可复用）**：首次全量跑出 `111 passed, 1 failed`，红的是 `parts_json_funnel_check`，报的路径是 `probe/base-tree/src/.../ChatRepository.kt` —— 我把「未修复门的基线树」解包到了**被扫描的仓库内部**（`probe/base-tree`），于是所有走真目录的门都把这份源码副本当成生产文件审。修法 = 基线树移出仓库（`/var/minis/shared/work/meta-wide4/basetree-04`）后重跑 → 112/0。**规则：探针/基线物料（工作树副本、zip 解包、对照臂）不得放在被扫描树内任何目录**，`probe/` 进 `.gitignore` 不等于安全。

---

## §5 CI run 号与判据行

（推送并 dispatch 后回填：run id / job id / attempt / head_sha / 112-0 计数 / 门头 112 互异 / `ALL 344` / ` e: ` 0 / 唯一 skipped = Publish。）

---

## §6 残留（每条带触发条件 / 触发后方案 / 搁置理由）

- **R1 门 A 的「值来自 write」只接受两种拼写**（块尾直挂 / 局部变量间接；`SAVE_W4_OK_SAVED` 锁住后者）。
  - 触发条件：真出现合法写法 `writeSkillFile(…).let { it }` / `run { this }` 而被判红。
  - 触发后方案：`_value_from_write` 增加中转白名单 —— 仅当 `let/run/also` 的 lambda 体就是 `it`/`this` 时穿透。
  - 搁置理由：当前树无此形态；提前放宽会削弱 fail-closed（同族第 3 次复发的入口正是「先给一个看起来无害的白名单」）。
- **R2 门 A 的 flip-after-read 只认同一个 `LaunchedEffect` 内的 `isLoaded = true` 直写。**
  - 触发条件：把读/写拆成两个 effect，或改用 `derivedStateOf`。
  - 触发后方案：把检测扩成「同一状态变量的任意写点在读点之后、且值为常量 `true`」。
  - 搁置理由：当前单点实现，改动会引入跨 effect 顺序误判风险。
- **R3 门 B 的 T3 极性判据只覆盖 `continue` 形态的守卫。**
  - 触发条件：循环内改用 `run { return@forEach }` / `takeIf` 早退。
  - 触发后方案：把「retire」家族扩到标签 return 与 `takeIf` 后缀。
  - 搁置理由：现状单点，无第二形态证据；fail-closed 会先红，不会静默放行。
- **R4 门 B 的 T5/T6 只解析顶层 `if` 表达式。**
  - 触发条件：策略改写成 `when` 表达式或 `?:` 链。
  - 触发后方案：`policy_if` 增加 `when` 分支与 elvis 建模（复用 `when_contexts`）。
  - 搁置理由：当前形态稳定；等地基变动再动，避免判据膨胀。
- **R5 门 C 的隐式接收者只覆盖「provider 绑定作用域内的裸调用」。**
  - 触发条件：出现 `with(provider) { sendMessage(…) }` / `provider.apply { … }` 的合法写法。
  - 触发后方案：把 `with/apply/run` 的接收者纳入作用域建模（与 `_provider_bound` 复用同一套绑定判定）。
  - 搁置理由：本仓零此类写法。
- **R6 门 C 的 raw string 感知依赖共享 lexer。**
  - 触发条件：`_kotlin_lexer.py` 的 `"""` 处理变更。
  - 触发后方案：本支 fixture 已进 C 门 selftest，lexer 变更会被自动回归（无需额外动作）。
  - 搁置理由：已是自动回归，登记只为留痕。

---

## §7 注册项（主会话并集收口用，逐字）

| 门脚本 | selftest 标记串（逐字） | 臂数 |
|---|---|---|
| `scripts/scan/skill_file_save_result_check.py` | `SKILL_FILE_SAVE_RESULT_SELFTEST=PASS` | 17（11 violation + 6 offmain）+ 1 允许形态绿 |
| `scripts/scan/loop_tool_status_transition_check.py` | `LOOP_TOOL_STATUS_SELFTEST=PASS` | 24 fixtures |
| `scripts/scan/provider_boundary_guard.py` | `PROVIDER_BOUNDARY_SELFTEST=PASS (13 fixtures)` | 13 |

说明：

- 三条标记**均未改动**（沿用 `test_scan.py` 既有注册：`loop_tool_status_transition_check` @ 2759、`skill_file_save_result_check` @ 2809，`provider_boundary_guard` 走 641/652 的 `run_scanner` 外部夹具 + 2431 注册），本支只**增加臂**、不动标记串 —— 若主会话需要把新臂纳入 CI 可见性，按既有格式 append 即可，无需重编号。
- 本支**未新建门**，`scan.sh` 的 `[N/112]` 编号无需变动；门 112 / 夹具 344 均不变。

---

## §8 复现步骤

```sh
cd /var/minis/shared/work/meta-wide4/wt-04

# L1 门自身
python3 scripts/scan/skill_file_save_result_check.py .          # rc=0
python3 scripts/scan/loop_tool_status_transition_check.py .     # rc=0
python3 scripts/scan/provider_boundary_guard.py .               # rc=0
for g in skill_file_save_result_check loop_tool_status_transition_check provider_boundary_guard; do
  python3 scripts/scan/$g.py --selftest | tail -1
done

# L2 复攻（修复后）
python3 probe/reattack.py --root . --pristine /var/minis/shared/work/meta-wide4/basetree-04 \
    --only skill_file_save_result_check.py --out probe/after-skill.json
python3 probe/reattack.py --root . --pristine /var/minis/shared/work/meta-wide4/basetree-04 \
    --only loop_tool_status_transition_check.py --out probe/after-loop.json
python3 probe/reattack.py --root . --pristine /var/minis/shared/work/meta-wide4/basetree-04 \
    --only provider_boundary_guard.py --out probe/after-prov.json

# L2 基线对照（未修复的门 + 同一份源码）
python3 probe/reattack.py --root /var/minis/shared/work/meta-wide4/basetree-04 \
    --only <gate> --out probe/before-<gate>.json

# L1 全量 + 夹具
sh scripts/scan/scan.sh            # Summary: 112 passed, 0 failed
python3 scripts/scan/test_scan.py  # ALL 344 CASES PASS
```

基线树生成：`git archive HEAD | tar -x -C /var/minis/shared/work/meta-wide4/basetree-04`（**必须放在仓库之外**，见 §4 自伤条）。

装置说明（`.gitignore` 已覆盖 `probe/`，不入库）：

- `probe/witnesses.py` — 19 条见证表（`find`/`replace`/期望 rc/轴/理由）。
- `probe/reattack.py` — 复攻驱动：锚点唯一性断言 → 打补丁 → 跑门 → 还原 → sha256 复核；每次读写带重试，还原失败从 pristine 归档兜底（本沙箱并发高峰会随机 `ENOSYS`，半还原的工作树是最脏的失败态）。
- `probe/run_gates.py` / `probe/run_scan.py` — 邻门与全量门的带重试驱动。
