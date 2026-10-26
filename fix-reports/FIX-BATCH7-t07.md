# FIX-BATCH7-t07 — session_sheet_id_binding / provider_models_cache_wiring 两门判据加固

**支线** `fix/b7-sheetsession-provmodels`（工作树 `batch7/wt7/t07`，基线 `base/batch5-shared` @ `e145ec961`）
**门提交** `56617187e` — 2 个门脚本，**生产 `.kt` 0 行**，`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰，门 112 / 夹具 344 不变。
**判决**：修前 12 条见证 = **11 BLIND + 1 CAUGHT**（08-6 是报告自带对照臂）→ 修后 **12/12 CAUGHT**，restore 12/12。

---

## ① 门与轴

| 门 | 面 | 见证 | 轴 |
|---|---|---|---|
| `provider_models_cache_wiring_check.py`（scan #61，A/ISSUE-01） | 四跳缓存链 Repo → Registry → *ModelsApi → 磁盘缓存，任一跳丢 `Context` 整条链静默失效 | 08-1..08-6（6 条，5 BLIND + 1 CAUGHT） | dataflow 2 · parser-death 1 · family-hole 3 |
| `session_sheet_id_binding_check.py`（scan #76 / #93，B/ISSUE-02 + F/ISSUE-06） | 会话面板 / 编辑表拿到的 sessionId、每会话种子读写、编辑态镜像 | 12-1..12-6（6 条，全 BLIND） | dataflow 3 · family-hole 2 · spelling 1 |

两门共症（与前几批同族、这次落在**取值/可达**而非结构）：

> 判据 grep 的那个 token 全都还在，它**贡献的值**或**所在路径的可达性**没了。

---

## ② 见证原文（file / find / replace / why_gate_green）

### 08-1 `dataflow` — 遮蔽上移一跳（C1 生产调用点）
- file `data/repository/ProviderRepository.kt`
- find：
```
                ModelListProviderRegistry.fetchModels(instance, apiKey, isThirdParty, forceRefresh, context)
```
- replace：
```
                val context: android.content.Context? = null
                ModelListProviderRegistry.fetchModels(instance, apiKey, isThirdParty, forceRefresh, context)
```
- why_gate_green：wide-1024 只给 **C2（Registry）** 加了 `shadow` 检查，C1 仍只判 `args[-1] == "context"`，`ProviderRepository.refreshModels` 体内的 `val context = null` 完全没被看。

### 08-2 `dataflow` — 遮蔽下移一跳（C3 适配器）
- file `provider/ModelListProviderAdapters.kt`
- find / replace：在 `val baseURL = instance.effectiveBaseURL` 之后插入 `val context: Context? = null`
- why_gate_green：C3 只看括号实参列表里有没有字面量 `context = context`，不看各 `override suspend fun fetchModels` 体内是否有同名局部。

### 08-3 `parser-death` — 字符串里的 `/*` 吞掉遮蔽行
- file `data/repository/ModelListProviderRegistry.kt`
- replace：`val _c1 = "/*"; val context: android.content.Context? = null; val _c2 = "*/"` 插到 `val provider = synchronized(…)` 之前
- why_gate_green：旧 `strip_comments()` 是两条非字符串感知正则（`/\*.*?\*/` + `//[^\n]*`），从 `"/*` 到 `"*/` 之间整段被删空，`shadow` 检查拿到的函数体里根本没有那一行。

### 08-4 `family-hole` — 恒真守卫不是 `true` 字面量，且在锚点之后（C4b load）
- file `provider/ProviderModelsCache.kt`
- replace：在 `val file = keyFile(context, cacheKey)` 之后插 `if (1 == 1) return null`
- why_gate_green：旧 `dead_body_reason` ①只认 `DEAD_GUARD_RE`（`true|false` 关键字）②只检查 `keyFile(` **之前**有无 `return`。两把尺子都不覆盖 `if (1 == 1)` 且位于锚点之后。

### 08-5 `family-hole` — 动作被包进恒假分支，没有 `return` 也没有字面量（C4b invalidate）
- file `provider/ProviderModelsCache.kt`
- replace：`if (0 == 1) runCatching { keyFile(context, cacheKey).delete() }`
- why_gate_green：`if (0 == 1)` 既不含 `true/false` 也不含 `return/throw`，`.delete()` 仍在函数体内 ⇒ 判绿。

### 08-6 `family-hole` — 报告自带对照臂（已被旧门抓住）
- replace：`save()` 首行插 `if (!false) return`；旧 `DEAD_GUARD_RE` 的可选 `!?` 命中 ⇒ CAUGHT。修后仍必须 CAUGHT（回归臂）。

### 12-1 `dataflow` — 调用点参数被重写（G1）
- file `ui/chat/ChatScreen.kt`
- find：`sessionId = viewModel.currentSessionId,` → replace：`sessionId = viewModel.currentSessionId.let { "" },`
- why_gate_green：`BLOCK_SPECS` 只判 `re.search(r"sessionId\s*=\s*viewModel\.currentSessionId", block)` 为真 + `sessionId = sessionId` 为假；加 `.let { "" }` 两条都满足。

### 12-2 `family-hole` — 活 id 列表被同一条管道清空（G2）
- file `ui/chat/SessionSkillsSheet.kt`
- find：`val liveIds = skills.map { it.id }` → replace：`… .take(0)`
- why_gate_green：`_judge_merge_live_ids` 在 `value` 是标识符时只查「声明存在」+「块内出现 ≥2 次」，不看右侧表达式是否被 `.take(0)` 清空。

### 12-3 `dataflow` — 种子读取保留单词换掉值（G2）
- file `ui/chat/SessionSkillsSheet.kt`
- replace：`.associateWith { skillRepository.isEnabledForSession(it, sessionId.let { "__new__" }) }`
- why_gate_green：`_judge_seed_read_args` 只判 `\bsessionId\b` 出现在实参文本里。

### 12-4 `spelling` — 同一次写入的另一种拼写（G2）
- file `ui/chat/SessionMcpsSheet.kt`
- replace：在 `mergeSessionOverrides(…)` 之后追加 `for ((k, v) in seeds) overrides.put(k, v)`
- why_gate_green：`SEED_WRITE_TMPL` 只认下标写入 `map[…] =`；`put/putAll` 时 `writes` 为空而 `helper_ok` 为真 ⇒ 完全放行。

### 12-5 `dataflow` — 镜像形状对、值被抹（G3）
- file `ui/sessions/SessionsShared.kt`
- replace：`liveSession.title?.let { title = it }` → `{ title = "" }`；`selectedCategory = liveSession.category` → `… ?.takeIf { false }`
- why_gate_green：旧分支 1 只要求赋值右侧「含 source 子串」（`takeIf { false }` 仍含 `liveSession.category`）；分支 2 只要求 `source?.let { st = … }` 的形状，从不检查右侧是不是 `it`。

### 12-6 `family-hole` — 实参显式传成形参默认值（G3 编辑表）
- file `ui/chat/ChatScreen.kt`
- replace：`onRegenerate = { viewModel.regenerateSessionTitle() },` → `onRegenerate = {},`
- why_gate_green：`EDIT_SHEET_SPECS` 只判参数名 `onRegenerate\s*=` 是否存在，不看 `=` 后面的 lambda 体是否为空 / 是否真的调用。

---

## ③ 判据改造（旧形态 → 新形态）

### provider_models_cache_wiring_check.py

| # | 旧判据 | 新判据 |
|---|---|---|
| C1/C3 | `args[-1] == "context"` / 实参里有字面量 `context = context` | 值 + 作用域双判：实参必须是裸 `context`（`strip_parens`/`_norm`，`value_sources` 不分裂），且**最近的 `fun` 体内**不得有同名 `val/var` 声明（`decl_sites`，区分声明与赋值）；遮蔽的**位置与行号**进报错文案 |
| C2 | `re.search(r"\bval\s+context\b", reg_fn)`，`reg_fn` 来自自写 `strip_comments` 后的花括号计数 | 同一个 `shadow` 判据，但函数体来自共享 `function_body`，视图来自 `code_view`（注释 + 字符串/字符字面量按位抹白，长度不变）⇒ `val _c1 = "/*"` 只是空格 |
| C4b | `keyFile(` 存在 + `true/false` 字面量白名单 + 「`keyFile(` 之前有无 return」 | `dead_body_reason(body, patterns, verb)`：①动作位（read/write/delete 的调用）必须**可达**（`path_feasible`，恒假条件包裹即不可达）②动作之前不得有**必执行**的早退（`return/throw` 位于深度 0 且**整条语句结束在动作之前**，其 `enclosing_conditions` 全部常量折叠为 True） |
| C4b | 空体 / 名字还在即可 | 保留「三操作必须真做」的语义：load 真读、save 真写、invalidate 真删，且都必须在 `keyFile(context, …)` 之下 |

关键细节（写进代码注释）：`return runCatching { file.readText() }` **包含**自己的动作，不能算「动作之前的早退」——第一版按「return 关键字位置 < 动作位置」判，把合规夹具当场判红。

### session_sheet_id_binding_check.py

| # | 旧判据 | 新判据 |
|---|---|---|
| 12-1 | 参数名 + `viewModel.currentSessionId` 子串 | `_derives_only_from(value, "viewModel.currentSessionId")`：`value_sources` 逐来源比对，`!!`/`?`/括号合法，**任何字面量来源一律红**（`.let { "" }`、`?: "__new__"`、`takeIf { false }`） |
| 12-2 | 标识符声明存在 + 出现 ≥2 次 | 新增 `_provably_empty()`：空字面量、`.take(0)`/`.takeLast(0)`/`.drop(Int.MAX_VALUE)`、`.filter { false }`/`.filterNot { true }`（谓词 `const_bool` 折叠）⇒ 判「管道把列表清空了」，而不是「变量还在」 |
| 12-3 | `\bsessionId\b` 出现在实参文本 | 同一 `_derives_only_from(value, SESSION_ID_TOKEN)`：实参必须**就是**本表的 `sessionId` |
| 12-4 | 只有 `map[…] =` | `WRITE_SPELLINGS` 覆盖 `map[…] =` / `.put/.putAll/.set/.getOrPut/.compute/.computeIfAbsent/.merge(` / `+=` `-=`；守卫判定从「自写窗口 + 反向 `if` 条件匹配」改为 `enclosing_conditions`（带/不带花括号、嵌套、`else` 反转统一） |
| 12-5 | 赋值右侧含 source 子串 / `let` 形状 | `_assignments` + `_derives_from`：右侧的**每个**可能取值要么是 source（含 `?`/`!!` 归一），要么是**非字面量**；`source?.let { st = X }` 的 X 必须派生自 `it`/source；`takeIf`/`takeUnless` 谓词用 `const_bool` 折叠（`{ false }` ⇒ 值恒为 null） |
| 12-6 | `onRegenerate\s*=` 存在 | 参数值判定：lambda 体必须**调用了某个东西**（`{}`/`{ Unit }` = 形参默认值 = 按钮空转）；另加同族 `liveSession = session`（同样是默认的过期快照） |

**共享原语 adoption（本波硬约束第 1 条）**：两门删掉的自写件——

- `strip_comments()`（非字符串感知两行正则）→ `code_view()`
- `extract_call_block` / `_match_forward` / `extract_block_fun_body` / `_enclosing_brace`（自写平衡括号/区间扫描）→ `match_bracket` / `function_body` / `call_args` + `split_top`
- `_attached_if_condition`（反向抓最近的 `if (…)`）→ `enclosing_conditions`
- `SEED_WRITE_TMPL`（单一拼写白名单）→ `WRITE_SPELLINGS` 家族
- `DEAD_GUARD_RE`（`true|false` 字面量白名单）→ `const_bool` 常量折叠 + `path_feasible`

残留的自写件只剩「业务正则」（哪几个字段要镜像、哪些调用算 seed read），无词法/括号/条件结构解析。

---

## ④ 四层证据

### L1 门自身 + 邻门
```
$ python3 scripts/scan/provider_models_cache_wiring_check.py .   → GATE=GREEN（15 criteria）
$ python3 scripts/scan/session_sheet_id_binding_check.py .       → OK（4 call blocks + LocalMarkdownSessionId + 两表 seeding + 编辑态）
$ --selftest：provider 9 fixtures → 14；session 32 arms → 41 arms（含 4 条 clean 反臂）
```
邻门 10 道逐条 rc=0：`session_provider_enabled_check` / `model_entry_override_parity_check` /
`provider_remember_key_check` / `models_provider_field_check` / `chat_ui_interaction_check` /
`collection_copy_on_mutate_check` / `compact_entry_pin_migration_check` /
`entry_id_rename_integrity_check` / `provider_rpc_symmetry_check` / `parts_json_funnel_check`。

全量门：`scan.sh` 在本沙箱窗口被 ENOSYS 打死（S28，见下「环境」），改走**逐门独立进程**循环
（`probe/t07_loop_gates.py`，从 `scan.sh` 机械抽出 110 条门命令，一门一进程 + 6 次退避重试）：
**110/110 rc=0**（第一批 40 绿 + 12 个 spawn 失败单点补跑全绿 + 断点续跑 58 绿）。

### L2 反攻（原见证逐字重放，最终树上重跑）
```
$ python3 verify_batch5.py --witnesses witnesses-B.json --repo wt7/t07 --mode full \
    --only session_sheet_id_binding_check.py --out probe/t07-final-session.json
SUMMARY: {'CAUGHT': 6}          （restore 6/6 True，src/ 事后 git status 空）

$ python3 verify_batch5.py --witnesses witnesses-A.json --repo wt7/t07 --mode full \
    --only provider_models_cache_wiring_check.py --out probe/t07-final-provider.json
SUMMARY: {'CAUGHT': 6}          （restore 6/6 True，src/ 事后 git status 空）
```
每条红在**自己那条判据**上（逐条打印，非「凑一个红」）：

| 见证 | 红的判据 | 命中文案摘要 |
|---|---|---|
| 08-1 | C1 生产调用点 | `a local context declared at line N shadows the Context…` |
| 08-2 | C3 适配器 | `a local val context shadows the parameter at line N` |
| 08-3 | C2 Registry | 同上（`function_body` + `code_view` 后遮蔽行可见） |
| 08-4 | C4b load | `a constant-condition guard makes the body dead before readText(` |
| 08-5 | C4b invalidate | `the .delete( call only appears inside a branch that can never run` |
| 08-6 | C4b save | `…dead before writeText(` |
| 12-1 | G1 调用点取值 | `passes sessionId = viewModel.currentSessionId.let { "" }` |
| 12-2 | G2 活 id 列表 | `liveIds is computed … and then emptied (liveIds = skills.map{…}.take(0))` |
| 12-3 | G2 种子读取 | `calls isEnabledForSession(…, sessionId.let { "" })`（view 遮蔽字面量内容） |
| 12-4 | G2 未守卫写入 | `unguarded seeding write map.put(...)` |
| 12-5 | G3 编辑态镜像 | 2 条：`title` 与 `selectedCategory` 两条镜像各一条 |
| 12-6 | G3 编辑表参数 | `passes onRegenerate = {} — the lambda body never calls anything` |

### L3 变异/反臂（证明新判据不是「一律判红」）
`--selftest` 内置 4 条 clean 反臂，与 violating 臂同门对照：
`liveIds = skills.map { it.id }.filter { it.isNotBlank() }`（同管道、非清空）·
`selectedCategory = liveSession.category?.takeIf { it != null }`（谓词可行）·
`sessionId = viewModel.currentSessionId!!`（同值不同拼写）·
`onRegenerate = { viewModel.regenerateSessionTitle() }`（真接线）。
provider 门 13 个 breaker 各带 `target` 断言：**红错判据 = FAIL**（不允许「撞了别的判据」冒充命中）。

### L4 CI
见 ⑤（以 CI 日志为准，本地全量读数只作辅证）。

### 本波自伤（4 条，均已闭环，形态可复用）
1. **早退的「位置」必须按语句结束算**：`return runCatching { …readText() }` 包含自己的动作，按关键字位置判 ⇒ 合规夹具当场判红。改「整条语句结束在动作之前」。
2. **具名参数解析要先 strip**：多行实参首字符是换行，`^([A-Za-z_]\w*)\s*=` 直接不命中 ⇒ 所有 `context = context` 判成「没有该实参」。
3. **`value_sources` 的接收者带安全调用标记**：`x?.takeIf { … }` 返回 `x?`，与 `x` 不等 ⇒ 合规的可行谓词被判红。归一时把尾部 `?`/`!` 视为不改变值。
4. **装置 cwd 造成的 3 条假失败**：`test_scan` 子进程继承启动目录，我在 `batch7/` 而非仓库根跑，三门 selftest 用 `.` 解析路径找不到源文件 ⇒ 3 条「❌」纯属装置。**规则：门自测的默认 root 是 cwd，装置必须在仓库根启动。**

---

## ⑤ CI

- **run** `40266467021` / **#2713** / job `121389861485` / attempt 1 / **conclusion = success**（workflow_dispatch，`build-apk.yml`，03:55:54Z 起）
- **head_sha** `56617187eff5…` 与本地 HEAD `56617187e` **逐字符一致**（本地 HEAD = API `run.head_sha`）
- 26 步，唯一非 success = **step 23 `Publish to Releases` skipped**（`if: ref == main` 门控），其余全 success
- Step 3「Run pre-build scan gate」日志（判读逐条核过，日志 1,482,125 B）：
  - `Summary: 112 passed, 0 failed` 恰 **1** 次
  - `ALL 344 CASES PASS` 恰 **1** 次
  - 门头 `[N/112]` 共 **112** 条，**编号互异 112 / 名称互异 112**，总分母全为 112
  - `^ e: `（编译错误）**0** 条
  - 含 `FAILED` 的行 6 条，**逐条确认全是 PASSED 用例名子串**（`… produces FAILED without calling provider PASSED` 等）
  - 两门标记各恰 1 次：`PROVIDER_MODELS_CACHE_WIRING_SELFTEST=PASS`、`SESSION_SHEET_ID_BINDING_SELFTEST=PASS`（⇒ 新臂在 CI 里真跑且真绿）；`selftest has teeth` 85 处
- 源码树与 CI 绿树等价：CI 之后仅追加本报告（见 ⑦ 之后提交），`git diff --name-only 56617187e..HEAD -- . ':(exclude)fix-reports'` = **0**

---

## ⑥ 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

**R1（provider）`local_shadow` 只看最近的 `fun` 体**
触发条件：把 `val context = null` 提到类/对象属性层（`private val context: Context? = null`）或某个外层 lambda 里。
触发后方案：作用域扩到 class body + 形参表 + 外层 lambda 链，并用 `value_sources` 反查被转发标识符的绑定来源。
搁置理由：类级同名属性会同时改变功能语义（不是「同一处写错」），且现有四跳里 `context` 都是构造器属性/形参（正解），未出现该写法。

**R2（provider）`fun_spans` 要求 `)` 与 `{` 同一行**
触发条件：有人把返回类型换行（`): List<Any>\n{`）。
触发后方案：改共享 `_kotlin_ast.function_body` 支持跨行，本门自动受益。
搁置理由：共享层本支禁止改（任务书硬约束）；耗尽方向是 fail-closed（报「body not found」而不是静默放行）。

**R3（provider）`dead_body_reason` 不建模 `when`/`for`/`while(true)` 可达性**
触发条件：动作被 `when (0) { 1 -> … }` 或 `while (false) { … }` 包裹。
触发后方案：`path_feasible` 已建模 `when` 分支条件，补「常量 subject 折叠」即可；`while(true)` 不算死体（体是会执行的），只有 `while(false)` 才算。
搁置理由：仓库无此写法；当前方向安全（漏红不出假绿）。

**R4（provider）`_provably_empty` 同类（无，见 R8）／常量标识符不折叠**
触发条件：`.take(LIMIT)` 里 `LIMIT` 是 `const val 0`。
触发后方案：对 `take/drop` 实参先做标识符 → 常量绑定折叠（一跳 val/const val）。
搁置理由：未出现；漏红方向。

**R5（session）`_write_sites` 看不见别名写入**
触发条件：`val m = overrides; m[k] = v` 或 `with(overrides) { put(k, v) }`。
触发后方案：一跳 val 绑定解析 + `with/apply` 接收者归一（共享 `call_args` 已能定位接收者）。
搁置理由：仓库无此写法；属「写法家族继续扩张」的长尾，按需再补。

**R6（session）takeIf 谓词只能常量折叠**
触发条件：`x?.takeIf { it == "" }`（恒假但不可静态判定）。
触发后方案：不可静态判定；最多支持「与已知类型冲突」的谓词（如 `Int` 上 `it < 0`）。
搁置理由：静态不可判，硬判会误伤合法谓词——误红 > 漏红的代价在此处不成立（这是业务逻辑，不是结构判据）。

**R7（session）12-6 的 `liveSession = session` 属扩展同族**
触发条件：无（已实现）。记录在案：该判据超出 §3 见证清单，但与本条同形（「显式传成形参默认值」），一行成本，已有 clean 反臂守「非默认表达式不误伤」。
搁置理由：不改；如主会话认为越界，回退仅需删除 4 行。

**R8（两门）臂数不进 CI 日志**
触发条件：需要从 CI 日志直接读「多少条 arm」。
触发后方案：`test_scan.py` 注册项把标记串改成带臂数的形态（`…SELFTEST=PASS (14 fixtures)`），即 ⑦ 的建议行。
搁置理由：`test_scan.py` 本支冻结（硬约束第 3 条）；标记本体已在 CI 出现，臂数由本报告承载（与 B6-t04 R7 / B6-t09 R7 同形态）。

**R9（session）报错文案里的字面量内容被抹**
触发条件：排障时想看到 `.let { "__new__" }` 里的真实字符串。
触发后方案：消息改用 `literal_view` 重取原文，或从原文切片渲染。
搁置理由：仅影响可读性，不影响判决。

**R10（session）`_is_value` 把尾部 `?`/`!` 视为不改变值**
触发条件：需要区分 `x` 与 `x!!` 的场合（例如想禁掉非空断言）。
触发后方案：只在 `takeIf/takeUnless` 接收者位置 strip，其余位置严格比对。
搁置理由：值语义相同；严格化会误伤 `takeIf` 合法形状（本波自伤 3）。

---

## ⑦ `test_scan.py` 注册项（交主会话统一注册，本支不改冻结文件）

现有注册行**无需改动**（标记串前缀未变，`test_scan` 用子串断言）：

```
("provider_models_cache_wiring_check", "PROVIDER_MODELS_CACHE_WIRING_SELFTEST=PASS", (), (REPO_ROOT,)),
("session_sheet_id_binding_check", "SESSION_SHEET_ID_BINDING_SELFTEST=PASS", (), (REPO_ROOT,)),
```

若希望**臂数**也进 CI 日志（R8），把两条标记串分别替换为：

```
"PROVIDER_MODELS_CACHE_WIRING_SELFTEST=PASS (14 fixtures)"     # 1 compliant + 13 breakers
"SESSION_SHEET_ID_BINDING_SELFTEST=PASS (41 arms)"              # 含 4 条 clean 反臂
```

两门 `--selftest` 的真实输出（臂数变化时同步改注册行）：

| 门 | 基线 | 本支 |
|---|---|---|
| `provider_models_cache_wiring_check` | `…SELFTEST=PASS (9 fixtures)` | `…SELFTEST=PASS (14 fixtures)` |
| `session_sheet_id_binding_check` | `…SELFTEST=PASS`（32 条断言行） | `…SELFTEST=PASS`（41 条断言行） |

---

## ⑧ 复现命令

```bash
cd <repo>            # 必须仓库根：门自测的默认 root 是 cwd（自伤 4）

# 门自身 + 自测
python3 scripts/scan/provider_models_cache_wiring_check.py .
python3 scripts/scan/provider_models_cache_wiring_check.py --selftest
python3 scripts/scan/session_sheet_id_binding_check.py .
python3 scripts/scan/session_sheet_id_binding_check.py --selftest

# 原见证逐字反攻（应当 6/6 CAUGHT，restore 全 True）
cd /var/minis/shared/work/meta-audit-v5/batch7
python3 verify_batch5.py --witnesses witnesses-A.json --repo wt7/t07 --mode full \
    --only provider_models_cache_wiring_check.py --out probe/t07-final-provider.json
python3 verify_batch5.py --witnesses witnesses-B.json --repo wt7/t07 --mode full \
    --only session_sheet_id_binding_check.py --out probe/t07-final-session.json

# 全量门（沙箱 ENOSYS 窗口下的健康路径：一门一进程 + 退避）
python3 probe/t07_loop_gates.py /var/minis/shared/work/meta-audit-v5/batch7/wt7/t07
# 全量夹具（沙箱里整跑会被杀：逐函数驱动）
cd <repo> && python3 probe/t07_testscan_runner.py . 0 12    # 依次 0..12 / 12..24 / 24..34 / 34..46
```

**环境说明（S28 复发，非门红）**：本窗口 `bash scripts/scan/scan.sh`、`test_scan.py` 整跑、乃至 `tail`/`fork` 都会
`OSError: [Errno 38] Function not implemented` 被整壳打死；健康路径即上面的「逐门独立进程 + 退避重试」与
「逐 test 函数 SIGALRM 驱动」。本地读数 110/110 + 344/344，最终以 ⑤ 的 CI 日志为准。
