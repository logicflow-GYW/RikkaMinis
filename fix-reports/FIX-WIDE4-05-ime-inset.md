# FIX-WIDE4-05 — IME-inset 门判据加固（见证全数反攻）

- **基线**：`dbab7a3e`（`base/wide4-shared` = `19e70dab` + 两个共享 Kotlin 原语模块）
- **分支**：`fix/wide4-05-ime-inset`
- **改动面**：`scripts/scan/ime_inset_guard.py` 单文件（+423 / −156），**生产 `.kt` 0 行改动**、`scan.sh` / `test_scan.py` 冻结面 0 触碰、门总数 112 / 夹具 344 不变
- **见证**：外部对抗审计本门 6 条（5 `FALSIFIED` + 1 `CAUGHT`）→ 修复后 **5/5 全部翻红、CAUGHT 保持红**
- **自证臂**：13 → **21**

## §1 门与轴

| 门 | 见证数 | 盲轴 | 症状（一句话） | 结果 |
|---|---|---|---|---|
| `ime_inset_guard.py` | 6（5 F + 1 C） | family-hole ×4 · parser-death ×1 · 语义（CAUGHT）×1 | 判据锚「某个字符串存在」，而不是「宿主最终拿到了什么」 | 全部反攻 rc=1 |

四条 family-hole 的共同形态：**保留拼写、删掉决定**。补丁都只改一个变量/一个表达式，门看到的仍是它认识的字面量。

## §2 每条见证：原补丁 → 旧判据为何瞎 → 新判据 → 新臂

### 2.1 `hoisted_modifier_name / live_ime_padding`（family-hole）

- **补丁**（`MemoryManagementScreen.kt`，`MemoryFileEditScreen`）：把 `Column(modifier = Modifier…imePadding())` 里的 `.imePadding()` 删掉，改成在 `Column(` 前插 `val unusedImeMod = Modifier.imePadding()` + `val _ignore = unusedImeMod`。
- **旧判据为何瞎**：`hoisted_modifier_name()` 认出 `val <name> = Modifier…` 后，只统计 `len(re.findall(r"\b%s\b" % name, body)) >= 2`——**第二次词法出现**（赋值给没人用的 `_ignore`）就算「已消费」。宿主拿到的 `Modifier` 一个 padding 都没有。
- **新判据**：新增 `modifier_slot_hosts()` 派生「能真正改变这个字段尺寸的 `modifier =` 实参槽」= 字段自己的调用，或**尾随块包含该字段的布局容器**；`hoisted_reaches_host()` 要求变量的引用**落在某个槽的值表达式里**且不在死守卫内。
- **新臂**：`w4 hoisted-but-unattached modifier is caught`（ime=1/1）。

### 2.2 `audit_scaffold_callsites`（B01-CALLS，family-hole）

- **补丁**（`ChatTuningScreen.kt:79`）：`SettingsScaffold(title = …, onBack = onBack, …)` → `onBack = {},`。
- **旧判据为何瞎**：`ONBACK_ARG_RE` 只在**值为 `null`/`false`** 时才判红（`ONBACK_NULL_RE`）；`onBack = {}` 既非 null 又非缺失，于是计进「11 个站点传了 onBack」——**箭头渲染出来、点了什么都不发生**，比不渲染更坏。
- **新判据**：`onback_value_kind()` 把值分类（`absent` / `null` / `false` / `empty-lambda` / `value`）；对声明了 `onBack:` 参数、又没给 `navigation` 槽的站点，`{}`（空 lambda）与**任何不引用在作用域 handler 的表达式**一律判红。实参改用 `arg_value_by_key()` 括号配平解析（旧正则 `[^,\n)]+` 会在 lambda 内第一个 `)` 处截断）。顺带：`fun SettingsScaffold(` 声明本身不再被当成调用点。
- **新臂**：`w4 onBack = {} discards handler is caught`（needle = `discards in-scope `onBack` handler`）+ `w4 onBack = { onBack() } stays green`。

### 2.3 `check_scaffold_navigation`（B01-SLOT，family-hole）

- **补丁**（`SettingsComponents.kt` 导航槽）：`onBack != null -> IconButton(onClick = onBack)` → `onClick = onBack.takeIf { false } ?: {}`。
- **旧判据为何瞎**：`ONBACK_RENDER_RE = IconButton\([\s\S]{0,200}?onClick\s*=\s*onBack\b` 是**子串锚**。`takeIf { false }` 恒为 null，`?: {}` 兜底成空 lambda——字面量 `onClick = onBack` 还在，回溯箭头永远不会被点。
- **新判据**：`_onclick_values()` 括号配平取出 `onClick` 的**完整值表达式**，要求剥掉外层括号后**精确等于** `onBack` 或 `{ onBack() }`（`ONBACK_ONCLICK_OK`），禁止任何尾链；死守卫用新的 `dead_guard_spans`（含 `when (false)`）判定。
- **新臂**：`w4 onClick = onBack.takeIf{false} ?: {} is caught` + `w4 `when (false)` back arrow is caught`。

### 2.4 `live_ime_padding / enclosing_call`（family-hole）

- **补丁**（`SkillsManagementScreen.kt:1498`）：`DialogTextField(modifier = Modifier…imePadding()…)` → `…let { if (1 == 2) it.imePadding() else it }…`，并把条件写成 `1 == 2`。
- **旧判据为何瞎**：两处。① `enclosing_call()` 只维护圆括号深度，**直接穿过 `.let {` 的花括号**把 padding 归属给外层 `DialogTextField`；② `cond_truth()` 只认字面量 `true`/`false`/`!`/`&&`/`||`，`1 == 2` 解析不出来 ⇒ 死分支活着。`.let` 返回的是 lambda 的值，`1 == 2` 时返回未加 padding 的 `it`——链上没有 padding。
- **新判据**：`enclosing_call()` 增加 `brace` 深度：**先遇到未闭合 `{` 就返回「在 lambda 体内，不能归属外层调用」**；`cond_truth()` 委托 `_kotlin_ast.const_bool`（覆盖常量比较）+ `x == x` 身份折叠。
- **新臂**：`w4 padding in a discarded `.let` result is caught`（ime=1/1）。

### 2.5 `strip_comments`（parser-death）

- **补丁**（`SkillsManagementScreen.kt:1498`）：`.imePadding(),` → `/* outer /* inner */ .imePadding() */`。
- **旧判据为何瞎**：手写剥离器用布尔 `in_block`。Kotlin 允许**嵌套块注释**，第一个 `*/` 就把扫描器放回「代码」态，仍被注释掉的 `.imePadding()` 被当成保护。同族构造（`'"'` 字符字面量、三引号原始字符串、`${…}` 模板、反引号标识符）在同一波击穿了另外几道门。
- **新判据**：整段换成共享投影 `_kotlin_lexer.analyse()`（`views["code"]`），**不再自己写词法器**；带 `ime-ok:` / `ime-unreachable:` 的行注释照旧恢复（逃生口不能被自己的词法器抹掉）；**解析失败 fail-closed**：`strip_comments()` 返回 `None`，`collect()` 直接报 `cannot lex the file (…)`，绝不在残缺视图上判绿。
- **新臂**：`w4 nested block comment blinds padding`（ime=1/1）。

### 2.6 `cond_truth / dead_guard_spans`（CAUGHT，保持带牙）

- **补丁**：`if (false && isEditing) { Box(Modifier.imePadding()) {} }`。旧判据已能折叠（`_split_top` + `&&`），故 `CAUGHT`。
- **本轮加固**：`cond_truth` 换共享 `const_bool` 后仍覆盖该类；`dead_guard_spans` 扩展 `while (false)` 与 `when (<字面量>)`——**新增臂** `w4 `false && x` dead guard stays caught`（ime=1/1），确保下一轮不会因为换实现而失守。

## §3 判据改动清单（函数名级）

| 函数 | 改动 |
|---|---|
| `strip_comments` | 手写扫描器 → `_kotlin_lexer.analyse()` 投影；返回 `(lines, None)` / `(None, reason)`；逃生口行注释按注释视图恢复 |
| `project`（新增） | 文件 → 代码视图，无法词法时返回 `(None, reason)` |
| `cond_truth` | 委托 `_kotlin_ast.const_bool`（`!` `&&` `\|\|` 括号 + 常量比较）+ `x == x` / `x != x` 身份折叠 |
| `dead_guard_spans` | 改用 `if_blocks` / `when_branches` 派生：`if` 两支 + `while (false)` + `when (false)` + 字面量分支条件 |
| `_split_args` / `_named_arg` / `arg_value_by_key`（新增） | 括号配平的实参切分与具名实参取值（替代 `ONBACK_ARG_RE` / `ONBACK_NULL_RE`） |
| `enclosing_call` | 增加花括号深度：在 lambda 体内 ⇒ 不归属外层调用（`('', -1, None)`） |
| `modifier_slot_hosts`（新增） | 派生「能改该字段尺寸的 `modifier =` 实参槽」 |
| `hoisted_reaches_host`（新增） | 变量必须**被槽读取**，不是二次出现；且引用不在死守卫内 |
| `live_ime_padding` | 三条来源：字段调用实参 / 容器尾随块包裹的实参 / 被槽读取的提升变量；全部排除死分支与 lambda 丢弃 |
| `onback_value_kind` / `_handler_params` / `_onclick_values`（新增） | `onBack` / `onClick` 的**值语义**分类与配平取值 |
| `check_scaffold_navigation` | `onClick` 值必须精确为 `onBack` 或 `{ onBack() }`；`IconButton(` 用 `match_paren` 配平 |
| `audit_scaffold_callsites` | 值语义判定（`{}` / 不引用 handler 一律红）；跳过 `fun SettingsScaffold(` 声明；`absent` 与 `null` 同判 |
| `scan_file` / `collect` | `scan_file(text, rel)` 接代码视图；无法词法的文件报违规（fail-closed） |
| `ime_inset_guard.py` 头部 | 新增「Anchor discipline (wide4)」段（判据锚在效果上，泄漏口逐条写明） |

## §4 四层证据

1. **本门**
   - 真树：`python3 scripts/scan/ime_inset_guard.py .` → `rc=0`，两条判据行 `✅ IME-inset guard: no unprotected text field…` / `✅ SettingsScaffold navigation guard: 11 call site(s) pass onBack…`（与加固前同一个 11，未靠放松计数过关）
   - `--selftest` → `IME_INSET_GUARD_SELFTEST=PASS (21 arms)`（13 → 21，8 条新臂，每条见证至少一臂）
2. **反攻复验**（`probe/wt05_reattack.py`，逐条 apply → 跑门 → 还原 → sha256 复核）

   | 判据 | 修复前 | 修复后 | 还原 |
   |---|---|---|---|
   | `hoisted_modifier_name / live_ime_padding` | rc=0 | **rc=1**（`MemoryManagementScreen.kt:552 in MemoryFileEditScreen()`） | sha 一致 |
   | `audit_scaffold_callsites (B01-CALLS)` | rc=0 | **rc=1**（`ChatTuningScreen.kt:86 discards in-scope onBack handler (passes {} …)`） | sha 一致 |
   | `check_scaffold_navigation (B01-SLOT)` | rc=0 | **rc=1**（`no LIVE IconButton(onClick = onBack)`） | sha 一致 |
   | `live_ime_padding / enclosing_call` | rc=0 | **rc=1**（`SkillsManagementScreen.kt:1498 in SkillFileViewerScreen()`） | sha 一致 |
   | `strip_comments` | rc=0 | **rc=1**（同上） | sha 一致 |
   | `cond_truth / dead_guard_spans`（CAUGHT） | rc=1 | rc=1（保持） | sha 一致 |

   `FALSIFIED 全部翻红: True` / `CAUGHT 保持红: True` / `restore 全部 sha 一致: True` / 还原后真树复跑 `rc=0`。
3. **邻门**（只读，8 道 `rc=0`）：`overlay_show_hide_latch_check` · `browser_tabs_lifecycle_check` · `deeplink_settings_routes_check` · `file_provider_paths_coverage_check` · `skill_import_offmain_check` · `backup_remap_rollup_check` · `downloads_atomic_update_check` · `katex_bridge_arity_check`。
4. **全量**
   - `bash scripts/scan/scan.sh` → **`║  Summary: 112 passed, 0 failed`**，`❌` 0 条，`Function not implemented` 0 次（一次跑通）。
   - `test_scan.py` → **344 cases / 0 failed**（46 个用例函数逐个通过：`probe/cases.json` 累计 `46 funcs 344 cases 0 failed`；中途 S28 ENOSYS 用逐用例驱动重试，见 §8）。
   - 说明：并发高峰窗口里 `scan.sh` 的单进程长跑先后被打断 4 次（`OSError: [Errno 38]`，甚至在 `open()` 上），最后一次在沙箱平稳后一次跑通 112/0；打断不记为门红（S28 已登记为已知沙箱缺陷）。

## §5 CI

（推送后回填）run 号 / head_sha / `Summary: 112 passed, 0 failed` / `ALL 344 CASES PASS` / ` e: ` 0。

## §6 残留（每条带触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `modifier =` 槽只认「容器尾随块包含字段」这一种宿主形态；容器用**具名 `content = <lambda 变量>`** 传内容（如 `Column(modifier = baseMod, content = content)`）时槽派生不出，提升变量会被判红 | 真机/真树出现「字段在 `content =` 变量里 + padding 走提升变量」的组合（当前 0 例；`SettingsScaffold` 自身走 PROTECTING 豁免） | 在 `modifier_slot_hosts` 里补「容器实参中的 lambda 形参名」追踪：解析 `content = { … }` 与 `content = <ident>` 的函数参数绑定，把字段位置映射进 lambda 体 | 方向安全（fail-closed 红，不静默放行）；当前无触发样本，按修复门属 P2 |
| R2 | `onBack = {}` 判红不看豁免表：已登记 `TOP_LEVEL_NULL_SITES` 的顶层页若改成传空 lambda 也会红 | 有顶层页确实需要「不可见的 back 槽」——正确做法是 `onBack = null` + 登记 | 在 `TOP_LEVEL_NULL_SITES` 基础上加 `{}` 的显式豁免位（需理由注释）；或把该站点改成 `null` | 空 lambda 与 `null` 行为不同（前者渲染死箭头），判红是想要的方向；无样本 |
| R3 | `onBack = <非 handler 表达式>` 一律红（例如 `onBack = otherScreenCallback`） | 某站点把「作用域内的 onBack」换成另一个同签名回调，且那是有意为之 | 放宽为「值必须是某个**参数**（任意 `() -> Unit` 形参名）」，而不是只认 `onBack\w*` | 当前真树 0 例；放宽会削弱「必须接线到本屏 handler」的判据 |
| R4 | 逃生口行注释若写在**块注释内**（`/* ime-ok: … */`）仍会被恢复成逃生口 | 有人刻意在块注释里藏豁免 | 用 `views` 差分还原块注释区间，只恢复真行注释 | 与「明文写一条 `ime-ok` 就能豁免」等价，不构成新绕过面；块注释定位需要额外词法状态，成本 > 收益 |
| R5 | `when (<非字面量>)` 的 `true` 分支活性未判（`when (flag) { true -> … }`，`flag` 未知） | 有人把守卫改写成 `when (flag) { true -> 保护; else -> }` | 视 `when` 主体类型为布尔时把主体当 `if` 同义处理（需要类型信息，纯静态判不了 ⇒ 只能 fail-closed 红） | 与 `if (flag)` 同族且更罕见；fail-closed 会误伤合法写法 |

## §7 注册项（主会话并集收口据此注册，逐字）

| 门脚本 | selftest 标记串（`--selftest` 收尾行，逐字） | 臂数 |
|---|---|---|
| `ime_inset_guard.py` | `IME_INSET_GUARD_SELFTEST=PASS (21 arms)` | 21 |

注册行（`test_scan.py` 的 `DA####_GATES` 列表形态）：

```python
("ime_inset_guard.py", "IME_INSET_GUARD_SELFTEST=PASS", (REPO_ROOT,)),
```

- **冻结标记串**：本门此前**没有**注册过 selftest 标记（`test_scan.py` 对本门只有 6 条外部夹具、无标记断言），因此**新增**该标记即可，无需改动既有串。
- 本支**未**在 `test_scan.py` 加臂（冻结面）：21 臂目前只由 `--selftest` 暴露，CI 日志里看不到 ⇒ 主会话并集收口时必须加上面这一行，否则新臂在 CI 无人见证。
- `credibility.md` 建议：本门由 **C** 升 **A**（外部见证 5/5 反攻被抓住 + 6 条冻结夹具 + 21 自证臂）。

## §8 复现步骤

```bash
cd /var/minis/shared/work/meta-wide4/wt-05
# 1. 本门 + 自证臂
python3 scripts/scan/ime_inset_guard.py .            # rc=0
python3 scripts/scan/ime_inset_guard.py --selftest   # IME_INSET_GUARD_SELFTEST=PASS (21 arms)

# 2. 反攻复验（逐条 apply → 跑门 → 还原 → sha256）
python3 -u probe/wt05_reattack.py                    # 见 probe/reattack.json / probe/reattack.log

# 3. 邻门
for g in overlay_show_hide_latch_check browser_tabs_lifecycle_check deeplink_settings_routes_check \
         file_provider_paths_coverage_check skill_import_offmain_check backup_remap_rollup_check \
         downloads_atomic_update_check katex_bridge_arity_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1; echo "$g rc=$?"
done

# 4. 全量
bash scripts/scan/scan.sh                            # ║  Summary: 112 passed, 0 failed
python3 scripts/scan/test_scan.py                    # ✅ ALL 344 CASES PASS
#    （若遇 S28 `OSError: [Errno 38]`：分块重跑）
python3 -u probe/run_gates.py                        # 110 道 python 门逐个跑，断点续跑
python3 -u probe/run_rootform.py                     # 5 道 --root 形态门
python3 -u probe/run_cases.py                        # 46 个用例函数逐个跑，断点续跑
```

装置说明：`probe/` 已被 `.gitignore:141` 覆盖，未入库（`wt05_reattack.py` / `run_gates.py` / `run_rootform.py` / `run_cases.py` + 三份 JSON / 日志）。
