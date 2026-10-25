# FIX-WIDE3-02 · provider-b（4 门 12 见证加固）

- 分支：`fix/meta-wide3-02-provider-b`
- 基线：`6b6c918fae6c338e3091d0d3870af3cad9e2b881`
- 范围：`thinking_wiring_guard` / `thinking_rule_editor_preserve_check` / `provider_boundary_guard` / `budget_stop_break_guard`
- 生产代码改动：**0 行**（`.kt` 零触碰，`scan.sh` / `test_scan.py` 冻结面零触碰）
- 门数：112 → 112（不新增、不删除）

---

## 1. 门与判据

| 门 | 判据（加固后） | 本波动的轴 |
|---|---|---|
| `thinking_wiring_guard` | ①`requested` 的来源必须能**溯源到 raw 档位**，`current` 必须溯源到 **effective 档位**；②picker 体内 clamp / tap 必须键在 `requested`；③measured-gateway：provider 不得写字面量 host，`resolveTier` 的**末位兜底操作数**必须真的由 `host` 决定，每个 `*_HOSTS` 必须有配套 `*_FALLBACK_TIERS` | parser-death / dataflow / spelling |
| `thinking_rule_editor_preserve_check` | Arm1 保存走 `buildEditedThinkingRule(`；Arm2 必须 `seed.copy(…)` **且不得在参数表里点名 `id` / `reasoningEcho`**；Arm3 格式选择保留守卫；Arm4 **每一个可达** 的格式控件都把 `formatChanged` 的**末次写**置 true；Arm5 `from` 与 representable 列表不漂移 | dataflow / order / parser-death |
| `provider_boundary_guard` | `:app` 内任何 `<receiver>.sendMessage / .streamMessage / .rawPassthroughRequest / .generateImage(` 都是违规，**除非 receiver 是登记过的非 provider**（`viewModel` / `vm` / `ProviderExecutionGateway`）；receiver 不可读 = 违规（deny-by-default）；注释/字符串内容先抹平；整文件折叠匹配（跨行链可见） | spelling / anchor / parser-death / registry |
| `budget_stop_break_guard` | `t7BudgetStopReason = "<str>"` 之后同一 brace 块里**第一个决定性 `break`**，若被 ≥2 层循环包住，必须带**最外层循环的 label**；最外层无 label = 违规；分支内自包含的扫描循环跳过 | anchor / order / parser-death |

## 2. 失效轴（本支实测口径）

- **parser-death**：门自己的解析器被"合法 Kotlin 变体"打死，扫描面塌成空集后返回"0 违规"。
  本支命中 4 处：`val`-only 绑定表（`var` 绑定隐形）、单行捕获（跨行 `run { }` / 跨行链截断）、`[^{}]*` 回调体（含嵌套 `{}` 的回调整体消失）、单行循环头 / 单行 `break` 正则。
- **dataflow**：调用了、拼写对，但**结果没被消费**。命中 2 处：`seed.copy(` 里显式覆写要保护的两个字段；`?: run { val ignored = host; … }` 里 host 只被"提到"而没被读。
- **anchor**：判据锚在"某名字/某子串存在"上。`resolveTier` 只查末段 240 字符里有没有 `host` 字样。
- **order**：只判"出现过"不判"末次写赢"。`formatChanged = true` 之后再 `= false`。
- **spelling**：`"api.senseaudio" + ".cn"` 拼接、接收者不叫 `provider`、`if (true) break` 这类等价写法全漏。

## 3. 逐条见证（12/12 → 新判据）

### 3.1 `thinking_wiring_guard`（组 A）

| 见证 | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|
| C1 | parser-death | `BINDING_RE` 只匹配 `val`，`var _thinkingLevel = viewModel.thinkingLevel.value` 不进绑定表 ⇒ 溯源把它当裸名 `_thinkingLevel`，名字恰好命中 `RAW_SOURCE_RE`；且捕获是 `[^\n]+`，跨行 `run { … }` 绑定只读到 `run {` | `BINDING_RE` 接受 `val|var`；新增 `initializer_text()`：括号/花括号深度 + 深 0 换行只在下一行以 `?: . + …` 续写时才算同一表达式 ⇒ 多行绑定整段参与溯源 |
| C6 | dataflow | 取最后一个 `?:` 后 240 字符窗口，只判 `"host" not in tail`；`run { val ignored = host; SENSENOVA_FALLBACK_TIERS }` 命中 `host` ⇒ 放行，且窗口本身还会截断或吞进后续无关语句 | 用 `initializer_text()` 抓**末位操作数的完整表达式**；`host_is_consumed()` 判定：`host` 若只出现在 `val/var x = host` 这类未读绑定里 = 未被消费 ⇒ 违规；作为实参/条件/接收者出现才算读 |
| C4 | spelling | `f'"{host}"' in prov` 只认完整字面量；`"api.senseaudio" + ".cn"` 拼接后字面量不存在 | `folded_string_literals()`：相邻字符串字面量若仅由顶层 `+` 连接则折叠成一个值，再与 `*_HOSTS` 集合比对 —— 判的是"代码拿来比较的**值**"，不是"怎么打出来的" |

自证臂：11 → **15**（新增 `var` 洗白、跨行 `run{}` 洗白、`+` 拼接 host、`val ignored = host` 兜底四条）。

### 3.2 `thinking_rule_editor_preserve_check`（组 A）

| 见证 | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|
| Arm 2 | dataflow | 只检查 `"seed.copy(" in body` + 无 `ThinkingRule(` + `Kind.CUSTOM` 存在。`seed.copy(id = UUID…, reasoningEcho = null, …)` 依然"是 copy"，但把 copy 唯一的价值（保留对话框不 surface 的字段）又抹掉了 | `call_arg_lists(src, "seed.copy(")` 括号配平取参数字符串 → `split_args()` 顶层逗号切分 → `named_arg_keys()` 取命名参数 → 与 `COPY_FORBIDDEN_FIELDS = (id, reasoningEcho)` 求交，命中即违规；参数表读不出来 ⇒ fail-closed 报"无法读取参数表" |
| Arm 4 | order | 只判 `"formatChanged = true" not in pick`。`{ choice = c; formatChanged = true; formatChanged = false; … }` 保留 true 字样 ⇒ 绿，而运行时末次写是 false，用户的格式选择照样被丢 | `last_flag_write()`：同一回调内所有 `formatChanged = (true|false)` 按出现顺序取**最后一次**，必须为 `true`。同时施于下拉 `onClick` 与**每一个** per-format 控件回调（见证方自陈"此旁路同样适用于 5 个 onValueChange"一并封掉） |
| Arm 4 | parser-death | `HANDLER_RE = on…=\s*\{([^{}]*)\}`：任何体内含嵌套 `{}` 的回调**整体**从 `findall` 消失 ⇒ 既不被审计，也不计入数量；再用一行不可达的 `if (false) Switch(… onCheckedChange = { formatChanged = true })` 把计数补回 5 | `handlers_in()` 按 `on*Change = {` 头做**花括号配平**取体（嵌套 `{}` 完整保留）；`dead_guarded()` 把落在字面量假守卫下的回调剔除（不可达控件不能替可达控件凑数），剔除数与数量不足分别报违规 |

自证臂：7 → **10**（新增 copy 覆写、下拉末次写翻转、嵌套回调隐藏三条；原 7 条全部保持"恰好红在自己的 arm"）。

### 3.3 `provider_boundary_guard`（组 B）

| 见证 | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|
| C1 | spelling | `TARGETS` 前两项硬编码接收者名 `provider.sendMessage(` / `provider.streamMessage(`（为躲 `viewModel.sendMessage`）；`CompactOrchestration.sendVia(p: LLMProvider)` 的形参就叫 `p` ⇒ `p.sendMessage(` 天然绕过 | 匹配改为 `<receiver>.<method>(`：`CALL_RE` 只认方法名，接收者由 `receiver_before()` 反向扫描取名字链；`EXEMPT_RECEIVERS = {viewModel, vm, ProviderExecutionGateway}` 是**接收者白名单**（gateway 是唯一受制裁的 app→worker 入口），其余含未知接收者 = 违规（deny-by-default） |
| C2 | anchor | `is_comment()` 只跳整行注释，行尾 `//` 不剥；豁免用 `if any(x in line for x in EXEMPT_SUBSTRINGS): continue` —— 整行只要**任何位置**出现 `ProviderExecutionGateway.generateImage(` 就免死 | `strip_noise()` 抹平行注释 / 块注释 / 字符串与字符字面量**内容**（保留偏移与换行）；豁免只按**该调用的接收者**判定，不再看"这行里有没有别的字样" |
| C1 | parser-death | 逐行 `if pat[0] in line`：`provider\n    .sendMessage(` 两行都不含完整子串 | 整文件折叠匹配（偏移保留，注释已抹平）⇒ 跨行链、行内换行、`?.` / `!!` 全部可见 |

其它：新增 `--selftest`（本门此前没有）8 个夹具；扫描面为 0 个 `.kt` ⇒ 显式违规（fail-closed）；单文件读失败 ⇒ 记为违规（不可验证即红，CI 上不触发）。

### 3.4 `budget_stop_break_guard`（组 B）

| 见证 | 轴 | 旧判据为何瞎 | 新判据形态 |
|---|---|---|---|
| C2 | anchor | `BREAK_RE = ^\s*(break\|break@\w+)\s*$` 要求 `break` **独占整行**；而块内找不到 `break` 时执行 `continue`（"该分支不打算结束 run"）—— 于是 `if (true) break` / `x; break` 让门**主动放弃检查**。门 docstring 自己举的坏例子 `{ … ; break }` 恰好是它判不到的形态 | 改为 **token 扫描**：在候选块内按源码顺序找 `break(@label)?` 记号（任何位置），删掉"找不到就跳过"的 fail-open 分支；只有"块内确实没有任何 break（例如 return）"才出 remit |
| C3 | order | 只按行找第一个形如 `^\s*break…$` 的行 ⇒ `collectDone = true; break` 被无视，下一行**不可达的死代码** `break@turnLoop` 被当成真身 | 按源码顺序取**第一个决定性 `break` 记号**（后面的不可达 break 不参与判定），再算它被几层循环包住 |
| C3 | parser-death | `LOOP_RE = ^\s*(label@)?(while\|for)\s*\(.*\)\s*\{\s*$` 只认单行循环头；`while (\n !collectDone\n ) {` 让内层循环从 `loop_spans` 消失 ⇒ 嵌套数 1 ⇒ `len(enclosing) < 2` 放行 | 结构性解析：`LOOP_HEAD_RE` 定位 `while/for(`，`match_paren()` 配平取右括号，再 `match_brace()` 配平取循环体 —— 循环头跨任意行都成立；label 从头部捕获 |

另外：新增 `--selftest`（本门此前没有）10 个夹具，含"分支内自包含扫描循环不得被误判"这一条正控；fail-closed 三种：候选存在但解析不到任何循环、块尾定不出来（括号失衡）、扫描面为空。

## 4. 修法（怎么改的）

1. **锚决定点，不锚拼写**：所有"某物存在"判据换成"该物的结果被谁消费 / 谁在决定"——`host_is_consumed()`、`last_flag_write()`、`receiver_before()`、`第一个决定性 break`。
2. **解析器一律配平 + fail-closed**：`initializer_text()`、`call_arg_lists()`、`handlers_in()`、`match_paren()/match_brace()`；读不出来就报违规，不返回 0。
3. **注释/字符串先抹平再判**：`strip_noise()`（provider 门）与 `strip_code_text()`（budget 门）都保留偏移与换行，行号仍然对得回原文。
4. **删掉两处 fail-open**：budget 门的 `if break_line is None: continue` 与 provider 门的"行内出现豁免子串就整行跳过"。
5. **每条新判据都配一条负向臂**，臂内断言"恰好红在自己的判据上"，并保留原有 `--selftest` 收尾行（`THINKING_RULE_EDITOR_PRESERVE_SELFTEST=PASS` 一字未改）。

## 5. 四层证据

### L1 本门真树 + 自证臂

```
$ for g in thinking_wiring_guard thinking_rule_editor_preserve_check \
           provider_boundary_guard budget_stop_break_guard; do
    python3 scripts/scan/$g.py .; python3 scripts/scan/$g.py --selftest; done
thinking_wiring_guard real rc=0  :: ✅ thinking-level wiring OK (1 call site(s) checked; 2 measured-gateway effort families pinned)
    selftest rc=0 :: THINKING_EFFORT_SELFTEST=PASS (15 fixtures)
thinking_rule_editor_preserve_check real rc=0 :: thinking_rule_editor_preserve_check: OK (5 arms, 0 violations)
    selftest rc=0 :: THINKING_RULE_EDITOR_PRESERVE_SELFTEST=PASS
provider_boundary_guard real rc=0 :: ✅ ProviderBoundary: no provider network calls outside :modelservice
    selftest rc=0 :: PROVIDER_BOUNDARY_SELFTEST=PASS (8 fixtures)
budget_stop_break_guard real rc=0 :: ✅ budget stop break guard: 6 budget-stop assignment(s), every run-ending `break` targets the outermost loop
    selftest rc=0 :: BUDGET_STOP_BREAK_SELFTEST=PASS (10 fixtures)
```

### L2 外部见证反攻：12/12 CAUGHT，restore 全 True

修复前（原封未动的门，机械复证基线）：

```
thinking_wiring_guard.py    C1/C6/C4            STILL_BLIND rc=0
thinking_rule_editor_...    Arm2/Arm4/Arm4      STILL_BLIND rc=0
provider_boundary_guard.py  C1/C2/C1            STILL_BLIND rc=0
budget_stop_break_guard.py  C2/C3/C3            STILL_BLIND rc=0
STILL_BLIND=12
```

修复后（`probe/run_reattack.sh`，逐条 apply → 跑门 → 用快照恢复 → sha256 比对）：

```
thinking_wiring_guard.py | C1 | CAUGHT rc=1 | restore_ok=True
thinking_wiring_guard.py | C6 | CAUGHT rc=1 | restore_ok=True
thinking_wiring_guard.py | C4 | CAUGHT rc=1 | restore_ok=True
thinking_rule_editor_preserve_check.py | Arm 2 | CAUGHT rc=1 | restore_ok=True
thinking_rule_editor_preserve_check.py | Arm 4 | CAUGHT rc=1 | restore_ok=True
thinking_rule_editor_preserve_check.py | Arm 4 | CAUGHT rc=1 | restore_ok=True
provider_boundary_guard.py | C1 (spelling) | CAUGHT rc=1 | restore_ok=True
provider_boundary_guard.py | C2 (anchor) | CAUGHT rc=1 | restore_ok=True
provider_boundary_guard.py | C1 (parser-death) | CAUGHT rc=1 | restore_ok=True
budget_stop_break_guard.py | C2 (anchor) | CAUGHT rc=1 | restore_ok=True
budget_stop_break_guard.py | C3 (order) | CAUGHT rc=1 | restore_ok=True
budget_stop_break_guard.py | C3 (parser-death) | CAUGHT rc=1 | restore_ok=True
CAUGHT=12 / 12
```

前两门另用主会话共享脚本复核过一轮：`reattack.py --gates thinking_wiring_guard.py,thinking_rule_editor_preserve_check.py` → `CAUGHT=3 + CAUGHT=3`，`RESULT: ALL CAUGHT`。

装置说明（重要）：沙箱本轮处于 S28 家族窗口，`reattack.py` 的 `subprocess.run` waitpid / `shutil.copy2` 随机抛 `OSError: [Errno 38] Function not implemented`，且**驱动死在半途会把工作树留在变异态**。因此本支另建分层装置 `probe/wit.py`（只做字节级 apply/restore + 快照自愈，**不 spawn 子进程**）+ `probe/run_reattack.sh`（shell 层编排门的执行）。开工前先 `wit.py heal` 把任何残留变异态还原到快照，验证完再逐条 sha256 复核。

### L3 邻门回归（5 门全绿）

```
$ for g in anthropic_thinking_budget_check provider_write_rules_check \
           xproc_boundary_check loop_counter_reset_guard a11y_handler_contract_check; do
    sh /var/minis/shared/tools/gate_run.sh --gate scripts/scan/$g.py --repo "$PWD"; done
GATE_RUN ... ran=1 pass=1 fail=0 sandbox=0 verdict=GREEN   (×5)
```

### L4 全量（持锁运行器，避免 PRoot ENOSYS）

```
$ sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
GATE_RUN mode=full repo=.../wt-02 ran=112 pass=112 fail=0 sandbox=0 envretry=0 verdict=GREEN
$ python3 scripts/scan/test_scan.py
✅ ALL 344 CASES PASS
```

## 6. CI

- run：**`40163435471`** / job **`121079556556`** / attempt **1** / conclusion **success**
- `head_sha = 91228b9a92a5d365e9619623b2f3e991b2efb926`，与本支 `git rev-parse HEAD` **逐字符一致**
- 26 步，`skipped = ['Publish to Releases']`（main 门控），`failed = []`
- 日志判读（1.48 MB / 17,846 行，`probe/ci/job.log`）：

| 读数 | 期望 | 实测 |
|---|---|---|
| 带框线 `Summary: 112 passed, 0 failed` | 恰 1 条 | 1 条（其余 7 条为子套件 Summary：5/10/14/15/25/28/36） |
| `ALL 344 CASES PASS` | 恰 1 条 | 1 条 |
| 门头 `━━━ [N/112]` | 112 个互异 | 112 / 112 unique |
| ` e: `（Kotlin 编译错） | 0 | 0 |
| 含 `FAILED` 的行 | 全是 PASSED 用例名子串 | 6 条，全部 `… PASSED` |
| `Publish` 步骤 | 分支上必须 skipped | skipped |

- **本支四门都在 CI 里被跑到**：`provider_boundary_guard` 2 次 / `budget_stop_break_guard` 2 / `thinking_wiring_guard` 2 / `thinking_rule_editor_preserve_check` 3。
- ⚠️ **自证臂在 CI 日志里只有 1 个可见**：`THINKING_RULE_EDITOR_PRESERVE_SELFTEST` 1 次（test_scan 里有精确子串断言），而 `PROVIDER_BOUNDARY_SELFTEST` / `BUDGET_STOP_BREAK_SELFTEST` / `THINKING_EFFORT_SELFTEST` 各 **0** 次 —— 门自己的 `--selftest` stdout 不进 CI 日志，只有 `test_scan.py` 注册了标记才会被看见。`test_scan.py` 是本支冻结面（任务书 §2.1），注册留给主会话并集收口，见 §7 第 0 条（与 wide-1024 M6 的 R9 同族）。

## 7. 残留（三件套：触发条件 / 触发后方案 / 搁置理由）

0. **`test_scan.py` 未注册本支三个新自证标记 ⇒ CI 里看不见这些臂**
   触发条件：主会话并集收口时（本支任务书 §2.1 把 `test_scan.py` 列为冻结面，不得改）。
   触发后方案：在 `test_scan.py` 的对应 `DA*_GATES` 列表各加一行（格式 `(script, marker, (), (REPO_ROOT,))`）：
   ```python
   ("provider_boundary_guard",   "PROVIDER_BOUNDARY_SELFTEST=PASS",    (), (REPO_ROOT,)),
   ("budget_stop_break_guard",   "BUDGET_STOP_BREAK_SELFTEST=PASS",    (), (REPO_ROOT,)),
   ("thinking_wiring_guard",     "THINKING_EFFORT_SELFTEST=PASS",      (), (REPO_ROOT,)),
   ```
   （`provider_boundary_guard` / `budget_stop_break_guard` 本波才首次拥有 `--selftest`；`thinking_wiring_guard` 的标记名沿用旧打印串 `THINKING_EFFORT_SELFTEST=PASS`，臂数 11→15。）
   搁置理由：`test_scan.py` 是冻结面（11 支并行改同一文件必撞），且注册只影响"CI 日志里能不能看见自证臂"，不影响门的判定；注册缺失时这些臂仍由本地 `--selftest` 与 L2 反攻覆盖。

1. **`host.endsWith("senseaudio.cn")` 这类后缀匹配仍能建第二套分支**
   触发条件：出现第三个 measured gateway，或有人在 provider 里用 `endsWith` / `startsWith` / 正则匹配 host。
   触发后方案：在 `folded_string_literals()` 结果上再做"后缀/前缀是否等于某 host 的后缀"判定，并要求同函数体内出现 `isMeasuredHost(`。
   搁置理由：本波见证是 `+` 拼接（已封），suffix 形态未复证；直接扩会误伤合法 URL 常量（如 `"https://…/v1"` 里含 host），需要先有误伤清单。

2. **`seed.copy(…)` 只保护 `id` / `reasoningEcho` 两个字段**
   触发条件：`ThinkingRule` 增长出第三个"对话框不 surface 的字段"（例如 `createdAt` / `enabled`）。
   触发后方案：从 `ThinkingRule` 的 data class 声明派生字段全集，减去对话框代码里引用到的字段名，得到"必须保留"集合，替代硬编码两元组。
   搁置理由：需要同时解析 data class 与整个 dialog 的字段引用，装置规模远超本条缺陷的影响面（当前字段集就 6 个，两个是关键的）；且在 `ThinkingRule` 增字段时 `four_way_sync_check` 已经在旁边。

3. **`EXEMPT_RECEIVERS` 是非 provider 接收者的名字白名单**
   触发条件：新增一个合法的非 provider 接收者（例如 `chatViewModel.sendMessage(`、或某个 `CoroutineScope.sendMessage`）。
   触发后方案：改为从声明类型派生（`: ChatViewModel` / 返回 `ChatViewModel` 的调用点），名称表只做兜底。
   搁置理由：当前生产树只有 4 处 `sendMessage(` 调用点（`vm` / `viewModel` 各 2 处），派生类型装置的成本大于收益；deny-by-default 意味着新调用点会**在 CI 里红一次**而不是静默放行，风险方向是安全侧。

4. **函数引用式调用（`::sendMessage`）与反射调用不可见**
   触发条件：生产代码出现 `LLMProvider::sendMessage` 之类的方法引用并被调用。
   触发后方案：在 `CALL_RE` 之外加 `::(sendMessage|streamMessage|…)` 检测。
   搁置理由：suspend 方法引用需要 `suspend` 上下文才能调用，`ProviderBoundary` 运行期守卫仍在（本门是静态层）；而且文本门无法判定"这个引用有没有被调用"，加规则会产生大量假绿。

5. **`do { … } while (…)` 与无花括号循环体**
   触发条件：`AgentLoopEngine` 或其它路径出现 `do-while`，或循环体不写花括号。
   触发后方案：`loop_spans()` 增加 `do {` 形态；无花括号体按语句边界精确取尾。
   搁置理由：当前生产树 0 处；无花括号体的近似处理（取到行尾）只会影响那种写法出现后的判定精度。

6. **"分支内自包含循环"的豁免以「起止都在本候选块内」为判据**
   触发条件：把 run 循环本身写进某个预算分支内部（当前架构下不会发生）。
   触发后方案：豁免再加一条"该循环体内不含 provider 调用 / turn 推进"的形态判据。
   搁置理由：只靠位置判定的误放行需要同时满足"分支内起循环 + 该循环就是 run 循环"，与现存结构互斥。

7. **`ThinkingRuleEditor` 未来若出现第二条保存路径（draft 模型）**
   触发条件：对话框改为持有可变 draft 并就地提交，而不是 `buildRule()` 一次性构造。
   触发后方案：Arm 1 从"必须调用 `buildEditedThinkingRule(`"改为"每条提交路径都必须经过它"（按提交调用点派生）。
   搁置理由：当前只有一条保存路径，多路径判据无从取证。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-wide3/wt-02
# L1
for g in thinking_wiring_guard thinking_rule_editor_preserve_check \
         provider_boundary_guard budget_stop_break_guard; do
  python3 scripts/scan/$g.py .; echo "$g real rc=$?"
  python3 scripts/scan/$g.py --selftest; echo "$g selftest rc=$?"
done
# L2（12 条见证，逐条 apply→跑门→restore→sha256）
sh probe/run_reattack.sh                     # 全部 12 条
sh probe/run_reattack.sh budget_stop         # 只跑某门
# L3 邻门
for g in anthropic_thinking_budget_check provider_write_rules_check \
         xproc_boundary_check loop_counter_reset_guard a11y_handler_contract_check; do
  sh /var/minis/shared/tools/gate_run.sh --gate scripts/scan/$g.py --repo "$PWD"; done
# L4 全量
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
python3 scripts/scan/test_scan.py
```
