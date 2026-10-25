# FIX-WIDE3-07 — UI 交互四门见证加固（ime / tool_title / chat_ui / memory_ui）

- 分支：`fix/meta-wide3-07-ui-interaction`
- 基线：main `6b6c918f`
- 改动面：**只改 4 个门脚本**（`scripts/scan/{ime_inset_guard,tool_title_error_branch_check,chat_ui_interaction_check,memory_ui_wiring_check}.py`）
  - 生产 `.kt` 零改动、`scan.sh` / `test_scan.py` 零触碰（冻结面）
- 门数 112 / 夹具 344 不变（无新建门、无新夹具）
- 外部见证反攻：**13 / 13 CAUGHT**（修复前同一补丁 rc=0，门瞎）

---

## ① 门与判据（改动前后一句话）

| 门 | 旧判据（锚「某物存在」） | 新判据（锚「效果达成」） |
|---|---|---|
| `ime_inset_guard` | 注释剥除只处理同行块注释；body 里出现 `.imePadding()` 即判已保护；`SettingsScaffold` 调用点只看参数名 `onBack` 是否出现 | 字面量感知扫描器（跨行块注释/字符串/raw string）；padding 必须挂在**真宿主**上（含该输入点的容器尾 lambda / 输入点自身 / 被复用的 hoisted `Modifier`），且不在字面量决定的守卫里；B01-SLOT 的后退箭头必须落在 `onBack` 臂内且是活的；B01-CALLS 逐站点判**参数值**，`onBack = null` 只对登记在册的顶层页豁免 |
| `tool_title_error_branch_check` | `line[i+1] == "//"` 让行注释从未被剥除；编码守卫只看「调用出现在写之前」；artifact 只看 `minis_url` 出现在 `!= null` 块内；标题只看 `toolTitle = NAME` 子串 | 行注释真正被剥除（`== "/"`）；守卫必须处在**能拒绝**的分支（条件非字面量决定 + 分支 `return`/`throw`）；`minis_url` 追加必须可达；`val toolTitle` 不得初始化为常量（含括号包裹与字面量） |
| `chat_ui_interaction_check` | `signature_args` 停在第一个 `)`；A2 用整函数子串；C1 用 `find("}")` 截断 + 只查 `inputText.isNotBlank()` 子串 | 参数表按括号**配平**切片；A2 要求 `if (cmd.isMcp)` 块内**活着地 return** 共享填充分支；C1 按 `||` 拆**操作数**判定（活的字段读取 + 附件极性正确 + 不得读 debounce 串 + 不得有字面量决定的支） |
| `memory_ui_wiring_check` | `brace_slice` 从签名找第一个 `{`（默认值里的 `run { }` 把切片截在签名内）；删除手柄/SOUL 刷新只看子串在不在 | 函数体从**参数表右括号之后**找；删除手柄与 `SoulStore.refreshCache(` 必须不在字面量决定的守卫里；body 必须**消费** `maxInjectLines` |

---

## ② 失效轴

| 轴 | 条数 | 门 |
|---|---|---|
| parser-death（解析器自身被改写打断） | 4 | ime（跨行块注释）、tool_title（行注释判断比错字符）、chat（参数切片/花括号截断）、memory（签名里的 `run { }`） |
| dataflow（值存在但不承重） | 7 | ime ×2（B01-SLOT 死守卫、B01-CALLS 丢弃 handler）、tool_title ×2（C-EDIT-ENC、C-ARTIFACT）、chat ×2（A2、C1）、memory ×2（A、D） |
| anchor（判据锚在拼写上） | 2 | ime（`Spacer(Modifier.imePadding())` 不是宿主）、tool_title（`val toolTitle = (NAME)` 绕过子串黑名单） |

一句话总结这一族的失效形态：**门判的是「这段文字还在不在」，缺陷是「这段文字还起不起作用」**。四条臂全部落在同一个补法上——把判据从 token 挪到**决定点**（`cond_truth()` / 尾链消费 / 实参值 / 参数表配平）。

---

## ③ 逐条见证（criterion / 轴 / 旧判据为何瞎 / 新判据形态）

### ime_inset_guard（4）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| 1 | IME-HOST | parser-death | `strip_comments` 逐行 `re.sub(r"/\*.*?\*/")`——只有**同行闭合**的块注释被剥掉。把 `.imePadding()` 用跨行 `/* … */` 注释掉（合法 Kotlin，也正是「临时禁用某修饰符」的写法），修饰符仍留在被判代码里 | 单字符扫描器：进入块注释状态直到 `*/`；字符串/字符字面量（含 `"""…"""`）内容一并抹白，`/*` 不再能从字符串里开出注释（顺带根治 `"*/*"` 吞代码的老问题）；行数严格不变（行号/函数跨度依赖它）；`ime-ok:`/`ime-unreachable:` 逃生注释不被自己剥掉 |
| 2 | IME-HOST | anchor | 只查 `IME_PADDING_RE.search(body)`——`if (false) { Spacer(Modifier.imePadding()) }` 里那个 0dp Spacer 也算「已消费 IME」 | `live_ime_padding()`：padding 必须挂在**真宿主**上——(a) 布局容器的实参表且该容器尾 lambda **包住**这个输入点；(b) 输入点自身的调用（叶子级 `BasicTextField` 编辑器真实形态）；(c) `val baseMod = Modifier…imePadding()` 且该变量被再次引用（`SettingsComponents` 真实形态）；且一律排除字面量决定的守卫内 |
| 3 | B01-SLOT | dataflow | `ONBACK_RENDER_RE` 在整个 slot 上匹配——`onBack != null -> if (false) IconButton(onClick = onBack) { … }` 保留全部拼写，一个像素都不渲染 | 先切出 `onBack != null ->` **这一段臂体**（到下一个 `else ->` / 另一条 `!= null ->` 为止），再要求臂体内存在**不在死守卫里**的 `IconButton(onClick = onBack)` |
| 4 | B01-CALLS | dataflow | `ONBACK_ARG_RE` 只证明「参数名出现」，值是不是 `null` 从不检查；11 个站点里 1 个传 null 仍 ≥ floor 9 | 逐站点判**实参值**：`onBack = null` 且该调用点所在作用域里有 `onBack:` 形参、又没有自带 `navigation` 槽 ⇒ 红。顶层页的合法 null 走**显式豁免清单**（`TOP_LEVEL_NULL_SITES`，9 条，key = 相对路径 + 包围函数），清单外默认拒绝；清单项失配报 ROT（要求重新推导而不是静默继承）；floor 仅在「真树规模」（≥ 11 个调用点）时兜底 |

### tool_title_error_branch_check（3）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| 5 | C-TITLE | parser-death + anchor | `line[i + 1] == "//"` —— 一个字符怎么可能等于两个字符，**行注释从未被剥除过**；于是 `// val toolTitle = resolveToolTitle(...)` 让 `"resolveToolTitle(" in src` 恒真，真声明换成 `val toolTitle = (NAME)` 就被括号绕过 `toolTitle = NAME` 子串黑名单 | `== "/"`（行注释真被剥）；新增声明级判据：`val toolTitle = <expr>` 的 expr 去掉外层括号后不得是字符串字面量或全大写常量标识符（`(NAME)` / `""` / `NAME` 全红），合法形态（`resolveToolTitle(...)` / `result.toolTitle` / 局部 `title`）保留 |
| 6 | C-EDIT-ENC | dataflow | 只查「守卫调用出现在写之前」——`if (false && isUnpairedSurrogateFailure(effectiveContent)) { return … }` 调用、消息、return 全在，写照样无守卫地落盘 | `guard_is_live()`：取**最内层**含该调用的 `if`，条件不得被字面量决定，且分支必须**终结**（`return`/`throw`）。守卫存在但死 ⇒ 单独一条「守卫无法拒绝」而不是笼统的「没有守卫」 |
| 7 | C-ARTIFACT | dataflow | 只查 `minis_url` 出现在 `if (persistPath != null) { … }` 块内——`if (false) { … }` 包一层，token 仍在块内，永久不执行 | 在块内剔除死守卫覆盖区后，要求至少一处 `minis_url` 追加**给 `output`**（可达且承重）；「在块内但不可达」有独立报错文案 |

### chat_ui_interaction_check（3）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| 8 | A1 | parser-death | `signature_args` 找到 `(` 后停在第一个 `)`——一旦某个参数带调用形默认值（`val tag: String = String()`），后面的字段全部掉出扫描区；前面的 `isSkill` 让「布尔字段为空」的 fail-closed 不触发 | `signature_args_balanced()`：括号配平后取整段；配平失败返回 None ⇒ fail-closed |
| 9 | A2 | dataflow | A2 用「整个 `executeSlashCommand` 有没有 `FILL_HELPER`」判——`isSkill` 臂里有同一个调用，于是 `if (cmd.isMcp) { if (false) return fillComposerWithSlashTitle(…) }` 全绿，MCP 点击落入 action 分发返回空串、把输入框内容冲掉 | 只取 `if (cmd.isMcp) { … }` 的块体，要求块内有**活着的** `return <FILL_HELPER>(…)`（语句头必须是 `return`，且在死守卫覆盖区之外） |
| 10 | C1 | dataflow | `inp_c.find("}", brace)` 在 `let { }` 上提前收尾（只判到半个表达式），而 `inputText.isNotBlank()` 子串检查看不见 `inputText.isNotEmpty()`；`false \|\| attachments.isEmpty()` 的极性翻转也无人判 | 用配平花括号取整段；再把谓词按顶层 `\|\|` 拆成**操作数**：不得出现 `inputText`（debounce 串）、不得有字面量决定的支、必须有活的 `inputFieldValue.text.isNotBlank()/isNotEmpty()`、必须把**非空**附件当可发送（`attachments.isNotEmpty()` / `.size > 0` / `!\|\|attachments.isEmpty()` 形态） |

### memory_ui_wiring_check（3）

| # | criterion | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| 11 | A (MemoryFileRow) | dataflow | `IconButton(onClick = onDelete)` 子串在 `if (false && onDelete != null)` 里同样命中——删除手柄一个像素不渲染，且整条确认链路随之不可达 | 兄弟行/本行文本内排除死守卫覆盖区后，必须存在可见的 `IconButton(` |
| 12 | D (SOUL refresh) | dataflow | `"SoulStore.refreshCache(" in scr[a:b]` ——`if (false && fileName == SOUL_FILE_NAME)` 保留调用，SOUL.md 保存后缓存永不刷新 | 在 IO 区内剔除死守卫后，要求 `SoulStore.refreshCache(` 落在活区 |
| 13 | E (dailyAutoItemFor) | parser-death | `brace_slice` 从签名往后找第一个 `{`——默认值写成 `maxInjectLines: Int = run { AgentRuntimeLimitsPrefs.memoryInjectLines() }` 时，切片在**签名内部**就结束，函数体从未被审理，body 里的 `minOf(lineCount, 200)` 硬编码无人发现 | 函数签名时，函数体从**参数表右括号之后**开始找；配平失败返回空串（fail-closed）；另外要求 body **消费** `maxInjectLines`（声明了却不用也算红） |

---

## ④ 修法（都是「加牙」而非「换判据」）

1. **注释/字面量剥离器重写**（ime / tool_title）：从逐行正则改成单字符状态机，跨行块注释、字符串（含 raw）、转义一律理解，行数不变。两个门各自的 parser-death 见证都在这一步根治；`"*/*"` 吞代码的历史坑同时消解。
2. **决策点原语**（四门各自一份，门脚本是独立单文件）：
   - `cond_truth(expr)`：按 `!` / `&&` / `||` / 括号递归求「字面量是否决定真假」，返回 True/False/None；
   - `dead_guard_spans(text)`：所有条件被字面量判死的 `if`/`while` 体区间（带花括号取块，无花括号取到行尾——覆盖 `if (false) doThing()` 形态）；
   - `inside_spans()` / `guard_is_live()`：位置是否落在死区、守卫能否拒绝（条件非死 + 分支终结）。
3. **配平切片**：`signature_args_balanced`（chat）、参数表右括号定位的函数体切片（memory）、`_match_paren`/尾 lambda 定位（ime）。全部 fail-closed（配平失败 = 红，不是放行）。
4. **实参值判定 + 显式豁免清单**（B01-CALLS）：判值而不是判名字；9 个顶层页的合法 `null` 用 `(相对路径, 包围函数)` 登记，清单外默认拒绝，清单失配报 ROT。
5. **每条新判据都固化成门内 `--selftest` 负向臂**（ime 从 0 臂新建 13 臂；其余三门各自追加），臂一律「见证补丁打上去 → 恰好该臂红」；`test_scan.py` 的既有夹具全部保持原判。

---

## ⑤ 四层证据

### L1 单门真树 + 门自证

```
$ python3 scripts/scan/ime_inset_guard.py .
✅ IME-inset guard: no unprotected text field in a bare-Scaffold host
✅ SettingsScaffold navigation guard: 11 call site(s) pass `onBack`, and the slot falls back to a back arrow
rc=0
$ python3 scripts/scan/ime_inset_guard.py --selftest
IME_INSET_GUARD_SELFTEST=PASS (13 arms)

$ python3 scripts/scan/tool_title_error_branch_check.py .
tool_title_error_branch_check OK (7 files)
$ python3 scripts/scan/tool_title_error_branch_check.py --selftest
TOOL_TITLE_ERROR_BRANCH_SELFTEST=PASS

$ python3 scripts/scan/chat_ui_interaction_check.py .
chat_ui_interaction_check: slash wiring + table toolbar + swipe parity — CHAT-UI-INTERACTION GATE OK
$ python3 scripts/scan/chat_ui_interaction_check.py --selftest
CHAT_UI_INTERACTION_SELFTEST=PASS (75 fixtures: clean + 74 violation/precision)

$ python3 scripts/scan/memory_ui_wiring_check.py .
memory_ui_wiring_check OK (…/MemoryManagementScreen.kt, …/SessionMemorySheet.kt)
$ python3 scripts/scan/memory_ui_wiring_check.py --selftest
MEMORY_UI_WIRING_SELFTEST=PASS
```

`test_scan.py` 既有夹具（`test_ime_inset`）：

```
━━━ ime_inset_guard ━━━
  ✅ fixed + protected + fieldless pass (exit 0)
  ✅ bare Scaffold + field, no imePadding is caught (exit 1)
  ✅ trailing-lambda `Scaffold {` counts as host (exit 1)
  ✅ "*/*" literal does not swallow later functions (exit 1)
  ✅ ime-ok escape hatch passes (exit 0)
  ✅ ime-unreachable escape hatch passes (exit 0)
```

### L2 外部见证反攻 13/13

反攻用的是**外部模型写的原始见证**（不是我自己构造的夹具）。原始 `reattack.py` 在本沙箱两次因 PRoot `ENOSYS` 死在 restore 上（把 `OnboardingModelSelectionScreen.kt` / `SettingsComponents.kt` / `ChatInputArea.kt` 留在打补丁状态），因此改用同语义、但**进程内跑门 + 内存恢复 + sha256 复核 + ENOSYS 重试**的 `probe/reattack07.py`：

```
$ python3 probe/reattack07.py --gates ime_inset_guard.py
ime_inset_guard.py   IME-HOST  CAUGHT  rc=1     # 跨行块注释
ime_inset_guard.py   IME-HOST  CAUGHT  rc=1     # 死守卫里的 Spacer
ime_inset_guard.py   B01-SLOT  CAUGHT  rc=1
ime_inset_guard.py   B01-CALLS CAUGHT  rc=1
CAUGHT=4   RESULT: ALL CAUGHT

$ python3 probe/reattack07.py --gates tool_title_error_branch_check.py
CAUGHT=3   RESULT: ALL CAUGHT
$ python3 probe/reattack07.py --gates chat_ui_interaction_check.py
CAUGHT=3   RESULT: ALL CAUGHT
$ python3 probe/reattack07.py --gates memory_ui_wiring_check.py
CAUGHT=3   RESULT: ALL CAUGHT
```

汇总（`/tmp/reattack-07.json`）：`13 条 → CAUGHT 13 / STILL_BLIND 0 / RESTORE_FAIL 0`，每条 `restored=True`。
**同一批补丁在修复前 rc=0（门瞎）**——这是本批最硬的判据：刀是外部写的，不是我构造的。

### L3 邻门回归（6 门 rc=0）

```
a11y_handler_contract_check      rc=0
overlay_show_hide_latch_check    rc=0
deeplink_settings_routes_check   rc=0
offload_host_path_check          rc=0
chat_menu_prefs_listener_check   rc=0
chat_session_id_binding_check    rc=0
```

### L4 全量读数

- **CI 是权威读数（已绿）**：`Summary: 112 passed, 0 failed` 恰一条、`ALL 344 CASES PASS` 恰一条、门头 112、` e: ` 0 —— 见 §⑥。
- 本地全量的经过（如实记录）：本次收口窗口内沙箱进入 PRoot 降级态（`Function not implemented` 命中 `execve` / `open()` / `/bin/sh: can't create /tmp/.minis_cwd_*`），裸 `scan.sh`、`gate_run.sh --full`、以及进程内逐门 sweep（`probe/full_run_07.py`）都被中途杀掉，最长一次跑到 `room_migration_check` 才死。按 `sandbox-limits.md` S28 的处理口径：**局部证据取逐门直跑，全量证据取分支 CI**。
- 已核：门 112 / 夹具 344 未变（无新建门、无改 `scan.sh`/`test_scan.py`）；4 门 + 6 邻门本地真树全绿；四门 `--selftest` 全 PASS。

---

## ⑥ CI（已绿，读数逐条核过）

- 分支：`fix/meta-wide3-07-ui-interaction`
- HEAD：`4e1f1f8ad76fde7cd5327b0d0f0744c1162ba556`
- dispatch：`2026-10-25T04:01:02Z`（`gh-actions-dispatch --workflow build-apk.yml --ref …`）
- **run #2649 / id `40160333332` → success（attempt 1，04:01:06Z → 04:18:45Z）**
- `head_sha = 4e1f1f8ad76fde7cd5327b0d0f0744c1162ba556` = 本地 `git rev-parse HEAD`（逐字符一致）
- 26 步：`Publish release` 缺席 / skipped（main 门控，分支上按设计跳过），其余全 success
- 日志（`0_build.txt` + `build/` 24 个步骤文件，1.5 MB）逐项核：

| 判据 | 实测 |
|---|---|
| `Summary: 112 passed, 0 failed`（带框线行） | **恰 1 条**（04:02:47Z） |
| `ALL 344 CASES PASS` | **恰 1 条**（04:04:44Z） |
| 门头 `━━━ [` 计数 | **112**（1..112 互异） |
| ` e: ` 编译错（Gradle + 单测两个日志） | **0 / 0** |
| `TOOL_TITLE_ERROR_BRANCH_SELFTEST=PASS` | 命中 1（test_scan 断言串仍在） |
| `MEMORY_UI_WIRING_SELFTEST=PASS` | 命中 1 |
| `CHAT_UI_INTERACTION_SELFTEST=PASS` | 命中 1（走 test_scan 的通用臂） |
| 单测步骤 | `15_Run unit tests` success，日志 14,445 行、无失败行 |

---

## ⑦ 残留（触发条件 / 触发后方案 / 搁置理由）

1. **死守卫判定的天花板**：`cond_truth` 只认 `true`/`false`/`!`/`&&`/`||`/括号。`if (1 > 2 && guard)`、`if (SCREAMING_CONST_FALSE && guard)`、`if (run { false } && guard)` 都不判。触发条件：出现以常量表达式或数值比较短路守卫的见证；方案：把常量折叠扩到 `Int`/`String` 字面量比较与全大写常量白名单读值；理由：任何更宽的求值都要值域/常量表，误伤风险与当批收益不匹配（本轮 4 条 parser-death 见证全部是显式 `false &&` 形态）。
2. **`if (true) { … }` 另一侧**：`if (true)` 现在被判「活」（对）但它包住的 `else` 永不可达，本门不管。触发条件：出现靠 `else` 分支藏代码的见证；方案：给 `dead_guard_spans` 增加「else 侧」区间；理由：本轮无此类见证，且加 else 侧会动到 tool_title 两个门现有的 `if (x != null) { } else { }` 判据形状。
3. **守卫的一跳别名**：`val bad = isUnpairedSurrogateFailure(c); if (bad) { return … }` 会被 C-EDIT-ENC 判红（fail-closed，方向安全但属误伤）。触发条件：真实代码改用别名形态；方案：允许「形参→局部绑定→条件」的一跳追踪；理由：当前 2 处守卫都是直接形态，先保持 fail-closed。
4. **B01-CALLS 豁免清单是手维护的**：9 条 `(相对路径, 包围函数)`。屏改名/搬家会报 ROT。触发条件：ROT 报警；方案：把顶层页标记移进源码（`// settings-top-level:` 注释，与 `ime-ok:` 同族）后由注释派生；理由：本批禁止改生产 `.kt`，用注释做豁免面需要 9 个文件各加一行，越出半径。
5. **`navigation = {}` 与 `onBack = X` 同时出现**：slot 会渲染空（`navigation()` 优先），现在不判。触发条件：出现该形态的见证；方案：调用点级判「`navigation` 为字面空 lambda 且同时给了 `onBack`」；理由：`ChatMenuSettingsScreen` 现存此形态，语义上像是「自己画导航」，需要人判是否缺陷，先登记。
6. **字符串模板里的代码**：剥离器把 `${…}` 一并抹白（只影响结构扫描，不影响行号）。触发条件：出现把守卫/修饰符写在字符串模板里的见证（不现实）；方案：模板内递归扫描；理由：投不了票。
7. **`imePadding` 的容器白名单**：`CONTAINER_CALLEES` 是枚举。新增自定义容器（`MySheetFrame(...)`）包输入点会判红。触发条件：新增宿主型 composable 时门红；方案：改成「尾 lambda 包住输入点 + 任意 callee」推导（去掉白名单）；理由：白名单外的 callee 目前不存在，去白名单会把 `Spacer(...)` 之类也放进来。
8. **`probe/reattack07.py` 与 `gate_loop_07b.sh` 未入库**（`probe/` 在 `.gitignore`，脚本在 `shared/work`）。触发条件：下批需要同形态反攻；方案：把「进程内跑门 + 内存恢复 + sha256 复核」并入 `reattack.py`（这是本批踩出来的实操教训：PRoot 下 `shutil.copy2` 恢复失败会把补丁留在生产文件里）；理由：本批 radius 只到门脚本。

---

## ⑧ 复现命令

```sh
# 单门真树
cd /var/minis/shared/work/meta-wide3/wt-07
python3 scripts/scan/ime_inset_guard.py .
python3 scripts/scan/tool_title_error_branch_check.py .
python3 scripts/scan/chat_ui_interaction_check.py .
python3 scripts/scan/memory_ui_wiring_check.py .

# 门自证（新臂）
python3 scripts/scan/ime_inset_guard.py --selftest
python3 scripts/scan/tool_title_error_branch_check.py --selftest
python3 scripts/scan/chat_ui_interaction_check.py --selftest
python3 scripts/scan/memory_ui_wiring_check.py --selftest

# 外部见证反攻（13 条；进程内跑门，恢复后 sha256 复核）
python3 probe/reattack07.py --json /tmp/reattack-07.json

# 邻门
for g in a11y_handler_contract_check overlay_show_hide_latch_check \
         deeplink_settings_routes_check offload_host_path_check \
         chat_menu_prefs_listener_check chat_session_id_binding_check; do
  python3 scripts/scan/$g.py . >/dev/null || echo "RED $g"
done

# 全量（沙箱健康时）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
python3 scripts/scan/test_scan.py
```

## 自伤记录（写进档案，避免下批重演）

1. **`git checkout -- .` 抹掉未提交的门改动**：为恢复被反攻脚本留在生产文件里的补丁，我用了 `git checkout -- .`，把当轮 ~2 小时的四门改动一起还原。恢复靠 `/tmp` 里留存的补丁脚本重放（全部可复现）+ `/tmp/ime_*_block.py` 三段拼接。教训：**回滚单文件就写单文件路径**；门改动应当先提交再跑反攻。
2. **`reattack.py` 在 PRoot 下会留下补丁**：`shutil.copy2` 恢复抛 ENOSYS，异常越过 `finally`，生产文件留在打补丁状态（本批 3 个文件）。已改为进程内跑门 + `finally` 写回 + sha256 复核。
3. **commit message 里的反引号被 shell 展开**：`git commit -m "… `line[i+1] == \"//\"` …"` 触发命令替换，写进历史的信息缺了关键片段。改用 `-F <file>` 提交信息。

## CI 读数

见 §⑥（run #2649 / `40160333332` / success / head_sha 三方一致 / 112-0 / ALL 344 / ` e: ` 0 / 唯一 skipped = Publish）。
