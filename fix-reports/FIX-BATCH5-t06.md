# FIX-BATCH5-t06 — `chat_ui_interaction_check` 五条见证补牙（A1 / A2 / C1 / D-IO / C-CLIP-1）

- **分支**：`fix/b5-chatui`（基线 `base/batch5-shared` @ `e145ec961` = main `6ac77c6b2` + 共享值流原语）
- **门**：`scripts/scan/chat_ui_interaction_check.py`（3,409 → 3,381 行）
- **生产代码**：**0 行**改动（`git diff --name-only e145ec961..HEAD -- src/ androidApp/ deps/` 为空）
- **自测臂**：75 → **86**（+11：9 条见证回归臂 + 2 条精度臂）
- **冻结面**：`scripts/scan/scan.sh` / `scripts/scan/test_scan.py` **零触碰**（注册项已存在，见 §7）

---

## 1. 门与轴

`chat_ui_interaction_check` 是 chat 输入区/斜杠菜单/表格工具栏/滑动发送的**跨文件交互门**，把
`ChatModels.kt`（描述符）、`ChatSlashTokenExt.kt`（token → composer）、`ChatInputArea.kt`（手势/
输入区）、`MinisTextMarkdownToolbar.kt`（剪贴板还原）等 7 个生产文件拼成一条链路，逐环节问
「上一环的字段/返回值有没有被下一环真的消费」。

本批外部见证打中的是 **dataflow / family-hole / parser-death / anchor 四轴的交界**：

| 轴 | 门 | 被证伪的判据形态 |
|---|---|---|
| parser-death | A1 | 字段清单只取数据类**主构造器参数**，类体/扩展属性就读不到 |
| family-hole | A2 | 死分支只认字面量 `false` 一种拼写（常量折叠缺失） |
| family-hole | C1 | 谓词里的字面量操作数只认字面量，`&& (1 == 2)` 漏 |
| anchor | D-IO | 「IO 跳板出现过」≠「调用在跳板块里」（前置子串匹配） |
| family-hole | C-CLIP-1 | 归还动作只查**拼写存在**，不查它是活路径还是死分支 |
| family-hole (对照) | A6 | `if (false) return fill(…)` —— 上一轮已补，本轮保持 CAUGHT |

## 2. 见证原文与旧判据为何瞎

六条见证均由 `verify_batch5.py --only chat_ui_interaction_check.py` 机械复证（`apply → 跑门 →
restore → sha256`），`restored=True` 6/6。

### G12-A1（parser-death / 实锤失明）

```python
find: "    val isMcp: Boolean = false,\n)"
replace: "    val isMcp: Boolean = false,\n) {\n    val isUnreadBodyFlag: Boolean = false\n}"
file: src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatModels.kt
```
报告原话（G12-A1，节选）：「…在 **Kotlin 类成员声明语法位置维度（主构造 `(...)` vs 类体 `{ ... }`）** 上偏移，
在 `SubAgentProgressItem(...)` 后面加上类体 `{ val isUnreadBodyFlag: Boolean = false }`，每个实例同样拥有
完整的 `isUnreadBodyFlag` 属性与 getter，同属『暴露未读状态字段』家族。」「门为何绿：…只用括号配平提取了
`data class …(` 到匹配 `)` 之间的主构造参数列表，完全没有继续读取紧随 `)` 之后的 `{ ... }` 类体块。」

> 命名注记：报告把判据称作 `A1 (SubAgentProgressItem unread field ban)`，而补丁锚点
> （`val isMcp: Boolean = false,\n)`）落在真树的 `data class SlashCommand` 上——`SubAgentProgressItem`
> 在本树 `grep -r` 命中 0 次（`ChatModels` 里没有这个类）。判据为 `A1`、缺陷家族为「字段面只取主构造器」，
> 因此修复按**类无关**的形态做（任何类的构造器/类体/扩展属性同一表面），不钉类名。

**旧判据为何瞎**：`bool_fields = re.findall(r"val\s+(\w+)\s*:\s*Boolean", cmd_params)`，`cmd_params` 是
构造器括号内的文本；类体（`) { … }`）与扩展属性根本不在取值域里。判据锚「构造器里有没有」，而
缺陷的载体是「这个类对外暴露的 boolean 有没有人读」。

### G12-A2（family-hole / 实锤失明）

```python
find: "    if (cmd.isMcp) {\n        resetSlashMenuState()\n        return fillComposerWithSlashTitle(cmd, saved)\n    }\n"
replace: "    if (cmd.isMcp) {\n        resetSlashMenuState()\n        if (1 == 2) {\n            return fillComposerWithSlashTitle(cmd, saved)\n        }\n    }\n"
file: src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatSlashTokenExt.kt
```
MCP 命令点击后**不填 composer 就落进动作分发**（原始缺陷 B-01 的复发形态），只是这次守卫写成
`1 == 2` 而不是 `false`。

**旧判据为何瞎**：死分支判定 `cond_truth()` 是**自写常量折叠白名单**，只认 `false` / `!true` /
`true && false` / `and`·`or` 字面组合；`1 == 2` 返回 `None`（未知），`inside_spans` 判不到，于是
「return fill 存在」成立。判据锚「有没有一个 return fill 的语句」，缺陷是「那个 return 在不可达分支里」。

### G12-A3（family-hole / 实锤失明）

```python
find: "        inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()\n"
replace: "        inputFieldValue.text.isNotBlank() && (1 == 2) || attachments.isNotEmpty() && (1 == 2)\n"
file: src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt
```
滑动发送谓词被写死成 false（永远不能滑发，静默退化）。

**旧判据为何瞎**：C1 找「`||` 操作数里有字面量」，用的是同一个 `cond_truth(x) is not None`；顶层
操作数是 `A && (1 == 2)`，`&&` 两侧 `None`，整体判不出常量 → 不报。同时两个正则
（`text.isNotBlank()` / `attachments.isNotEmpty()`）都命中，`field_ok`/`attach_ok` 全绿。

### G12-A4（anchor / 实锤失明）

```python
find: "            withContext(Dispatchers.IO) {\n                value = SlashMenuPayload(\n                    commands = viewModel.filteredSlashCommands(),\n                    loaded = true,\n                )\n            }\n"
replace: "            if (false) withContext(Dispatchers.IO) { }\n            value = SlashMenuPayload(\n                commands = viewModel.filteredSlashCommands(),\n                loaded = true,\n            )\n"
file: src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt
```
查询爆发（`filteredSlashCommands()`）回到 Main 线程。

**旧判据为何瞎**：`elif IO_HOP not in body.split(SLASH_MEMO_CALL)[0]` —— 只看「调用之前出现过
`withContext(Dispatchers.IO)` 这段文本」。空块、死分支里的块、甚至注释里（此门用 `mask()` 后不会）
都能满足。判据锚「跳板被提到」，缺陷是「调用有没有在跳板块里跑」。

### G12-A5（family-hole / 实锤失明）

```python
find: "                ClipboardRestoreAction.CLEAR_PLATFORM ->\n                    cm.clearPrimaryClip()\n                ClipboardRestoreAction.CLEAR_EMPTY_STRING ->\n                    cm.setPrimaryClip(ClipData.newPlainText(\"\", \"\"))\n"
replace: 同上两条 case 的 body 各包一层 `if (false)`
file: src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MinisMarkdownTextToolbar.kt
```
用户剪贴板本来是空的 → 借用后**永远留下我们的文本**（原始缺陷 A5 的复发）。

**旧判据为何瞎**：`CLIP_CLEAR_RE.search(block)` —— 拼写存在即通过，`if (false)` 里的
`cm.clearPrimaryClip()` 照样命中。判据锚「归还调用写没写」，缺陷是「它跑不跑」。

### G12-A6（对照臂 / 上一轮已 CAUGHT）

`if (false) return fillComposerWithSlashTitle(cmd, saved)`（无花括号形态）——上一轮补的
`dead_guard_spans` 抓得住 `false` 字面量。本轮必须**保持 CAUGHT**，它是「补牙没有把旧牙拔掉」的对照。

## 3. 判据改造（旧 → 新）

**统一动作**：本门自写的词法器 / 常量折叠 / 死分支白名单 / 括号扫描器**全部退役**，改走
`_kotlin_lexer` / `_kotlin_ast` / `_kotlin_valueflow`。同一门的所有臂现在走**同一个守卫判定**
（`path_feasible`），不再出现「一臂调一臂不调」。

| 判据 | 旧 | 新 | 对应见证 |
|---|---|---|---|
| A1 | `re.findall("val X: Boolean", 构造器参数)` | 字段面 = **构造器参数 ∪ 类体成员 ∪ `val SlashCommand.x: Boolean` 扩展属性**；构造器/类体括号配平失败 → fail-closed 红 | G12-A1 |
| A2 | `cond_truth()` 字面量白名单（自写折叠） | `path_feasible(block, pos)`（共享 `enclosing_conditions` + `guard_feasible`）：`false` / `!true` / `1 == 2` / 常量折叠的 `when` 一律不可达 | G12-A2 / A6 |
| C1 | 同上白名单逐操作数判常量 | 每个顶层 `\|\|` 操作数走 `const_bool`（共享折叠：短路语义 + 比较运算符） | G12-A3 |
| D-IO | `IO_HOP not in body.split(CALL)[0]`（前置子串） | `withContext(Dispatchers.IO)` 的**体区间**（`match_bracket`）必须**包含**调用，且跳板与调用都 `path_feasible`；无活跳板 / 调用在块外 → 红 | G12-A4 |
| C-CLIP-1 | `RE.search(block)`（拼写存在） | `live_positions(block, RE)`（只收**活路径**上的匹配）；`when` 分支分组与 `if/else` 对改用 `if_blocks` + 共享括号模型 | G12-A5 |
| 词法 | 自写 `strip_comments` / `strip_strings`（逐字符状态机） | `mask_comments` / `mask_code`（共享），**词法失败 → fail-closed 红**（不再静默当作干净） | wide4 parser-death 同族 |
| 括号/切分 | `_balanced_paren` / `_matching_brace_end` / `_split_top` / `declaration_slice` 自写扫描 | `match_bracket` / `split_top` / 共享括号模型派生的 `decl_slice` | — |
| 包围条件 | `_guard_before_brace` 反向 token 扫描、`_enclosing_condition_chain` 手写链 | `if_blocks` 查找 + `enclosing_conditions` | — |
| when 分支 | 手写 `when (...) {` + `->` 正则分段 | 共享 `if_blocks` + `_when_body_spans`/`_arm_spans`（顶层 `->` 切臂，多行臂也保住 body） | — |

**新增的 11 条自测臂**（每条都要求「只有这条判据翻红」，且配 clean 对照防「见什么都红」）：

1. `A1 class-body member boolean unread` → `["A1:isUnreadBodyFlag"]`
2. `A1 extension property unread` → `["A1:isUnreadExt"]`
3. `A1 read class-body member stays clean` → 无违规（精度）
4. `A2 constant-false comparison guard`（`1 == 2`）→ `["A2"]`
5. `A2 negated-literal guard`（`!true`）→ `["A2"]`
6. `C1 arithmetic absorption deletes the decision` → `["C1"]`
7. `C1 always-true absorption sends empty messages`（`|| (1 == 1)`）→ `["C1"]`
8. `D-IO empty hop block next to the call` → `["D-IO"]`
9. `D-IO hop behind a constant-false guard` → `["D-IO"]`
10. `C-CLIP-1 dead-guarded restore actions` → `["C-CLIP-1"]`
11. `C-CLIP-1 live restore stays clean`（`if (atLeastQ) cm.clearPrimaryClip()`）→ 无违规（精度）

## 4. 四层验证

**L1 门本体（真树 + 自测）**
- `python3 scripts/scan/chat_ui_interaction_check.py .` → `CHAT-UI-INTERACTION GATE OK`（rc=0，0.8s）
- `--selftest` → `CHAT_UI_INTERACTION_SELFTEST=PASS (86 fixtures: clean + 85 violation/precision)`
- `python3 -m pyflakes` → 仅 1 条**既有**未使用局部变量 `send_preds`（非本轮引入，见 §6 R5）

**L2 见证反攻（最硬的一条：不信报告，拿原见证再攻一次）**

`verify_batch5.py --witnesses witnesses-A.json --repo wt/t06 --mode full --only chat_ui_interaction_check.py`

| 见证 | 轴 | 判决 | 命中判据 | restore |
|---|---|---|---|---|
| G12-A1 | parser-death | **CAUGHT** | `[A1:isUnreadBodyFlag] … 0 read points …（constructor / class body / extension property 同一表面）` | True |
| G12-A2 | family-hole | **CAUGHT** | `[A2] … does not RETURN the shared fill helper … on its live path` | True |
| G12-A3 | family-hole | **CAUGHT** | `[C1] … contains a literal-decided operand (… && (1 == 2), …)` | True |
| G12-A4 | anchor | **CAUGHT** | `[D-IO] … with no live withContext(Dispatchers.IO) block at all` | True |
| G12-A5 | family-hole | **CAUGHT** | `[C-CLIP-1] … has no live 'make it empty again' action` | True |
| G12-A6 | family-hole（对照） | **CAUGHT** | `[A2] …`（与 A2 同判据，保持翻红） | True |

5 条原 `FALSIFIED` 全部转 `CAUGHT`；对照臂保持 CAUGHT（`STILL_BLIND = 0`）。

**L3 邻门（10 门，全部 rc=0）**：`chat_menu_prefs_listener` / `chat_session_id_binding` /
`compact_decision_gate` / `entry_id_rename_integrity` / `filepreview_print_bounded` /
`deeplink_settings_routes` / `a11y_handler_contract` / `tool_result_image_branch` /
`memory_ui_wiring` / `thinking_rule_editor_preserve`。

**L4 全量门（逐门一台，避并发窗口）**：`scripts/scan/*_check.py` + `*_guard.py` +
`process_logging_gate.py` + `turn_tail_finalize_order.py` 共 **111 个门文件全绿**
（`calendar_events_nullable_predicate` 与 `tts_voice_and_sanitizer` 以其 `--root .` 形态运行）。
`scan.sh` 在并发窗口内两次死在 `[16/112]`（沙箱 ENOSYS，registry S28），故逐门跑；门文件覆盖
`scan.sh` 名单的 110/110 无遗漏。

**L5 夹具套件**：`python3 scripts/scan/test_scan.py` → 见 §5（CI job 日志为准）。

## 5. CI

**run `40208261139` / job `121213772352` / attempt 1 / conclusion `success`**
`head_sha = 55fbb9cd3caddd7a1d43d1ee6ebd1ea84517c0ba`（= 本地报告提交 HEAD，逐字符一致；源码树见下）

逐条判读（日志 1,485,948 B / 17,850 行，`probe` 脚本机械核对，不靠肉眼）：

| 判据 | 读数 |
|---|---|
| **带框线** `Summary: 112 passed, 0 failed` | **恰 1 条**（其余 7 条 `Summary: N passed` 是子套件：28/5/14/36/15/25/10） |
| `ALL 344 CASES PASS` | **恰 1 条** ← 夹具套件 344/344 全绿（本批 L5 的权威读数） |
| Kotlin 编译错误 `^ e: ` | **0** |
| 门头 `[N/112]` | 112 条，**编号互异**（112 个唯一） |
| 含 `FAILED` 的 6 行 | **全是 PASSED 用例名的子串**，逐条确认：`slot denied produces FAILED without calling provider` / `reason PERSISTENCE_FAILED maps to persistence_failed` / `phase FAILED maps to Failed` / `terminal FAILED maps to Failed` / `reason EXECUTION_FAILED maps to all_fallbacks_exhausted` / `the fixed tail still reaches a legal FAILED terminal` —— 无一条是真失败 |
| 26 步结论 | **唯一 `skipped` = `Publish to Releases`**（main 门控，符合预期），其余 25 步 success |
| 本门在 CI 内的读数 | `chat_ui_interaction_check: … CHAT-UI-INTERACTION GATE OK`（真树 exit 0）；`✅ chat UI gate selftest has teeth (CHAT_UI_INTERACTION_SELFTEST=PASS)`；`✅ chat UI gate on real repo exits 0` |

> **L5 本地读数说明**：分支 CI 的 `ALL 344 CASES PASS` 是夹具套件的权威证据。本机施工窗口内
> `test_scan.py` 三次被杀（并发窗口 ENOSYS / 内存压力：同机另有 t01/t04/t05/t10/t11 五个会话在跑各自
> 的全量门，load average 峰值 17.8），本地跑到 79 个用例（0 失败）后让位给 CI —— 不是门红，是沙箱症状
> （registry S28）。同理 `scan.sh` 两次死在 `[16/112]`，故 L4 用**逐门一台**的方式取证（111 个门文件全绿）。

**源码树等价性**（报告提交在 CI 绿之后）：`git diff --name-only 55fbb9cd3..HEAD -- . ':(exclude)fix-reports'`
→ **空**（本轮只有 `fix-reports/FIX-BATCH5-t06.md` 一节）。

## 6. 残留（本支不修，逐条带三件）

- **R1｜A1 读点面只覆盖 `ChatSlashTokenExt` + `ChatInputArea`**
  - 现状：`bool_fields ∪ body_fields ∪ ext_fields` 的读点统计只在两份 consumer 文本里做。若新字段
    只在 `ChatScreen.kt` 读，会**假红**（方向安全，不静默放行）。
  - 触发条件：第一次出现「字段确实有读点、但只在第三份文件里」的假红。
  - 触发后方案：从发布树派生 consumer 集合（`grep -rl "SlashCommand" src/`）替代写死两份。
  - 搁置理由：与 §3 五条见证无关（半径纪律）；扩 consumer 面会改变判据作用域，需独立见证。

- **R2｜D3 守卫仍是 1600 字符窗口匹配**
  - 现状：`SLASH_GUARD_WINDOW = 1600` 的 `SLASH_GUARD_RE` 前置窗口判定未被本批见证打，未动。
  - 触发条件：出现「窗口内命中但守卫不在包围链上」（如 `if (showSlashMenu && false)`）的见证。
  - 触发后方案：改 `enclosing_conditions(body, pos)` 判 `showSlashMenu` 在包围链上（等价改造已有先例）。
  - 搁置理由：本轮见证是 D-IO 的**包含关系**，不是 D3 的守卫位置；一并改会扩大半径且缺反攻臂。

- **R3｜`return run { fill(…) }` / `return if (c) fill(…) else …` 形态判红（fail-closed）**
  - 现状：A2 要求 `return` 处于语句头（`^\s*return\s*$`），间接返回形态判红。
  - 触发条件：生产代码真的改成间接返回形态（当前真树是直接 return）。
  - 触发后方案：接共享 `returns_exiting` + `tail_expr`，按表达式判定返回值为 fill 调用。
  - 搁置理由：方向安全（红而非绿），且当前真树不触发。

- **R4｜`check_tree` 失败时统一报 `INPUT_REL`**
  - 现状：返回值恒为 `src/.../ChatInputArea.kt`，A1（ChatModels）/C-CLIP-1（MarkdownToolbar）的
    违规也挂在该文件名下（判据 ID 正确，文件标签误导）。属既有行为，本批未改。
  - 触发条件：有人按「文件名」排障被误导一次。
  - 触发后方案：`problems` 元素携带所属文件，`check_tree` 返回第一条的所属文件。
  - 搁置理由：纯排障显示问题，不影响判决（exit code 与判据 ID 都对）。

- **R5｜`send_preds` 未使用局部变量（既有死码）**
  - 触发条件：下次动该函数时顺路删除。
  - 触发后方案：删除或接入判据。
  - 搁置理由：与见证无关，删它会动到 `[b3-t05]` 段落语义。

- **R6｜共享模块缺口：`_kotlin_ast.when_branches` 无法处理多行臂**
  - 现状：它按顶层换行切分，`LABEL ->\n body` 形态的臂 body 掉到下一段（实测：本次 C-CLIP-1
    的 `when` 臂全部拿到空 body）。本支用局部 `_when_body_spans` + `_arm_spans`（基于共享
    `match_bracket` 的顶层 `->` 切臂）绕过。
  - 触发条件：第二个门需要按 when 臂取 body。
  - 触发后方案：在 `_kotlin_ast` 增 `when_arms(text)`（`when` 体区间 + 顶层 `->` 切臂），两门共用。
  - 搁置理由：共享模块由主会话拥有（任务书 §1.2 禁改），本支不得私改；缺口已用最薄方式绕过且
    绕过实现本身只依赖共享 `match_bracket`。

## 7. `test_scan` 注册（主会话用）

- 注册项**已存在**：`scripts/scan/test_scan.py:3022`
  `check("chat UI gate selftest has teeth (CHAT_UI_INTERACTION_SELFTEST=PASS)", proc.returncode == 0 and "CHAT_UI_INTERACTION_SELFTEST=PASS" in selftest_out, …)`。
  本支只改了臂数与判据，标记行未变 → **无需新增注册**，夹具套件计数量不变。
- 建议（可选，主会话决定）：把断言收紧到带臂数
  `"CHAT_UI_INTERACTION_SELFTEST=PASS (86 fixtures"`，使「悄悄删臂」这类退化也能被抓到（当前只断言
  `=PASS`，删到 1 个臂仍绿）。这条属**装置增强**而非本批见证，故不擅自改冻结面。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t06

# L1 门本体
python3 scripts/scan/chat_ui_interaction_check.py .
python3 scripts/scan/chat_ui_interaction_check.py --selftest
python3 -m pyflakes scripts/scan/chat_ui_interaction_check.py

# L2 六条见证反攻（5 翻红 + 1 对照）
cd .. && python3 verify_batch5.py --witnesses witnesses-A.json --repo wt/t06 \
      --mode full --only chat_ui_interaction_check.py --out probe/t06-post2.json
python3 -c "import json;[print(r['attempt'][:24], r['verdict'], r['restored']) for r in json.load(open('probe/t06-post2.json'))]"

# L3 邻门
cd wt/t06 && for g in chat_menu_prefs_listener chat_session_id_binding compact_decision_gate \
  entry_id_rename_integrity filepreview_print_bounded deeplink_settings_routes \
  a11y_handler_contract tool_result_image_branch memory_ui_wiring thinking_rule_editor_preserve; do
  python3 scripts/scan/${g}_check.py . >/dev/null 2>&1; echo "$g rc=$?"; done

# L4 全量门（逐门，避并发窗口；两个门要 --root 形态）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD" --chunks 8 --retries 4

# L5 夹具套件
sh /var/minis/shared/tools/gate_run.sh --exec --repo "$PWD" -- 'python3 scripts/scan/test_scan.py'
```

---

## 附：施工过程中踩到的两个沙箱症状（不属门）

1. `scan.sh` 在并发窗口内两次死在 `[16/112]`（`Function not implemented`，registry S28）→ 改逐门跑。
2. `test_scan.py` 三次被杀（并发窗口 + 同机 5 个会话并行跑全量，load 峰值 17.8；本地到 79 用例 0 失败）
   → 夹具套件由 CI 提供权威读数（`ALL 344 CASES PASS`）。
