# FIX-BATCH6-t01 — `offload_cli_args_check.py`（第六批门见证收口 · 支 01）

- 工作树：`/var/minis/shared/work/meta-audit-v5/batch6/wt6/t01`
- 分支：`fix/b6-offloadcli`，基线 `base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`（= main `6ac77c6b2` + 共享值流原语 `scripts/scan/_kotlin_valueflow.py`）
- 门提交：`fdc80caf9d875c3ba3d607460fec92e52648776a`（`scripts/scan/offload_cli_args_check.py` +719/−306）
- 报告提交：见 §5 末尾（`fix-reports/FIX-BATCH6-t01.md`）
- 改动面：**只有这一个门脚本 + 本报告**。生产 `.kt` 0 行；`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` 0 行；门数 112、`test_scan` 夹具 344 不变；未新建门。

---

## ① 门与轴

本门共 6 条见证，全部 `FALSIFIED`（实锤失明），0 条对照臂。

| # | 判据 | 轴 | 缺陷一句话 |
|---|---|---|---|
| A06-1 | `FastioSearch S1`（`_dead_condition`） | `family-hole` | 死条件白名单只认裸 `false`，`(1 == 2)` / `!true` 让它复活 |
| A06-2 | `SessionsSend S2`（同一个 `_dead_condition`） | `family-hole` | `(0 == 1) && !sendSucceeded(status)` ⇒ 发送失败走成功路径 exit 0 |
| A06-3 | `Clipboard L2`（`_is_sdk_q_decision`） | `family-hole` | 谓词**出现过**就算数，`&& false` / `!(…)` / 局部绑定恒假化全放行 ⇒ API 29+ 平台清空分支不可达 |
| A06-4 | `Payload N1`（`payload_findings`） | `dataflow` | `formatBody(` 后不是 `"` 就 `continue`；同文件 `val body = "prose"` 落在 N1/N2/Z2 三不管真空带 ⇒ 非 JSON 文本直接透传给下游 `jq` |
| A06-5 | `PageSize PG3`（`check_offload_page_size`） | `family-hole` | 只问"构造过 `Invalid` 吗"，不看消费它的 `return` 是否被 `if (false)` 短路 ⇒ 5 个分页 CLI 同时静默回默认值 |
| A06-6 | `PageSize PG2 & PG4` | `spelling` | PG2 要求 `getInt(` **紧跟**字面量且只看第 1 实参；PG4 只要函数体里**出现过** `NativeOffloadResult(2` ⇒ 变量中转 + 死拒绝 = 绕过共享分页器 |

**共同病因（与第五批 24 门同族）**：判据锚「某物存在」，缺陷动的是这个**存在物的效果**。所以修法统一推到位置 / 值流 / 可达性。

---

## ② 见证原文（6 条，逐字）

### A06-1（`family-hole`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioSearch.kt`
- find：
```kotlin
    if (limit <= 0) {
        out.matchesCapped = true
        return
    }
```
- replace：
```kotlin
    if (limit <= 0 && (1 == 2)) {
        out.matchesCapped = true
        return
    }
```
- 门为何绿：`_dead_condition` 是行首锚 `^false` / `^!\s*true` + `c.split("&&")` 后比裸 `"false"`。切分得到 `["limit <= 0 ", " (1 == 2)"]`，没有一项等于 `"false"` ⇒ 活条件。
- 自疑（审计方原话）：`if (limit <= 0 && !true)` 同样漏，因为 `^!\s*true` 锚在**字符串开头**，不在 `&&` 切分后检查。

### A06-2（`family-hole`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SessionsOffloadHandler.kt`
- find：`        if (!sendSucceeded(status)) {`
- replace：`        if ((0 == 1) && !sendSucceeded(status)) {`
- 门为何绿：同一个 `_dead_condition`；`(0 == 1)` 不是裸字面量，S2 判「活守卫」，随后 S1–S8 全过（分支体里的 `return NativeOffloadResult(sendExitFor(status), …)` 还在）。
- 自疑：同族缺口在同门被多个子判据（S1 / S2）复用会放大。

### A06-3（`family-hole`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ClipboardOffloadHandler.kt`
- find：`        when (clearAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {`
- replace：
```kotlin
        val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && false
        when (clearAction(isQ)) {
```
- 门为何绿：`_is_sdk_q_decision` 对标识符只做 `re.search(r"(?:val|var)\s+isQ\b[^\n]*SDK_INT\s*>=\s*…Q")`——无 `$` 锚、不看 `&& false`。
- 自疑：`val isQ = !(Build.VERSION...)` 同样满足该正则并反转语义。

### A06-4（`dataflow`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ContactsOffloadHandler.kt`
- find：
```kotlin
                val body = JSONObject().put("error", "not_found")
                    .put("id", id)
                    .put("message", "No contact with id=$id.")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
```
- replace：
```kotlin
                val body = "android-contacts: no contact with id=$id"
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
```
- 门为何绿：`payload_findings` 的 L769 注释就是 `variable / expression / helper call → accepted`。`body` 是变量 ⇒ N1 跳过；它不是函数调用 ⇒ N2 不看；`val body = "..."` 无跨文件函数调用 ⇒ Z2 不看。
- 自疑：N1 放行一切变量、只在 Z2 补了「跨文件函数调用」，**忘了同文件 `val x = "prose"`**。

### A06-5（`family-hole`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OffloadPageSize.kt`
- find：
```kotlin
                when (val parsed = checked(raw, surface, "--$name")) {
                    is Invalid -> return parsed
                    is Ok -> if (winner == null) winner = parsed
                }
```
- replace：
```kotlin
                when (val parsed = checked(raw, surface, "--$name")) {
                    is Invalid -> if (false) return parsed
                    is Ok -> if (winner == null) winner = parsed
                }
```
- 门为何绿：PG3 只查 `toIntOrNull` / `< 1` / `.max` / `hasFlag` / `Ok(default)` 出现次数与 `Invalid(` 的相对偏移，不查 `is Invalid ->` 分支是否无条件 `return`。
- 自疑：一处 10 字符改动同时瘫痪 5 个注册表面的分页校验，PG0–PG5 全绿。

### A06-6（`spelling`）

- 文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/CalendarOffloadHandler.kt`
- find：
```kotlin
        val limit = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.CALENDAR)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid ->
                return NativeOffloadResult(2, "android-calendar list: ${size.message}\n")
        }
```
- replace：
```kotlin
        val _unusedSize = when (val size = PageSizeOutcome.resolve(args, OffloadPageSize.CALENDAR)) {
            is PageSizeOutcome.Ok -> size.value
            is PageSizeOutcome.Invalid ->
                if (false) return NativeOffloadResult(2, "android-calendar list: ${size.message}\n") else 50
        }
        val limitKey = "limit"
        val limit = args.getInt(limitKey) ?: 50
```
- 门为何绿：PG2 的正则要求 `getInt(` 后**紧跟** `"limit"`，对 `getInt(limitKey)` 失明；PG4 只 `re.search(refusal_re, fn_body)`，不判可达性与归属。
- 自疑：直接 `args.getInt("day", "limit")`（键在第 2 实参）也放行。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### 0. 装置层（先做，全部判据受益）

| 项 | 旧 | 新 |
|---|---|---|
| 注释剥离 | `code_only`：`re.sub(r"/\*.*?\*/")` + 每行 `split("//")`（嵌套注释提前收尾、`"video/*"` 开假注释、`'"'` 吞后文；且**不保长**，位置不可回投） | `mask_comments`（共享 `literal_view`）：状态机处理嵌套块注释 / 原始字符串 / 字符字面量 / `${}` 插值，长度严格不变 |
| 结构分析 | 各处裸花括号计数 / 自写 `_match_brace` / `_expr_body` / `_balanced` / `_paren_close` | `code_view`（共享 `mask_code`）+ `match_bracket` / `function_body` / `if_blocks` / `call_args` / `expr_end` |
| 解析失败 | 隐式"跳过这一处" | `_fail_closed` 装饰器兜住全部 14 个 `check_*`：`KotlinLexError` ⇒ 一条 `LEX` 违规（`fail-closed`，不回退原文扫描）；`main()` 外层再兜一层 |
| 双通道 | 单一 `code_only` 文本兼任字面量与结构 | `code_only()`（字面量通道，读 `"status"` / `ClipData.newPlainText("", "")` / `handlerFile = "…"`）+ `code_view()`（结构通道，括号/分支/函数体）；两视图**严格等长**，位置可互换，切片按需回投 |

删除的自写解析器：`_pcs_strip`（改为 `mask_comments`）、`_pcs_match`（改为 `match_bracket`）、`_brace_block` / `_paren_close`（内部改共享）、`_dead_condition` 白名单（改 `const_bool`）、`_match_brace` / `_expr_body` / `_send_helper_body` / `_fn_bodies_with_sigs` 的实现（改 `function_body` / `_fn_spans`）。

### 1. A06-1 / A06-2 — 死条件（`S1` / `S2` 共用）

```python
# 旧
def _dead_condition(cond: str) -> bool:
    c = cond.strip()
    if re.match(r"^false\b", c) or re.match(r"^!\s*true\b", c):
        return True
    return any(part.strip() == "false" for part in c.split("&&"))

# 新
def _dead_condition(cond: str) -> bool:
    return const_bool(cond) is False
```

`const_bool` 严格按 Kotlin 优先级（`!` > 比较 > `&&` > `||`）+ 短路语义 + 括号/常量比较折叠 ⇒ `(1 == 2)`、`!true`、`(0 == 1) && X`、`X && !true`、`when(false)` 全部折成 `False`。同一个函数被两个臂共用，一处修好两条见证。

同时 `_if_site(body, pattern)` 的**结构**换成共享 `if_blocks`（brace / braceless 两种形态），`text` 改传结构视图 ⇒ 字符串里的括号不再让分支读错。

### 2. A06-3 — SDK-Q 决策（`L2`）

```python
# 旧：出现过就算数
if SDK_Q_PREDICATE_RE.search(arg): return True
if re.fullmatch(r"\w+", arg): return re.search(val|var + name + ".*SDK_INT >= …Q", code)

# 新：值语义
e = strip_parens(arg.strip())
if SDK_Q_PREDICATE_RE.fullmatch(e): return True          # 被求值的整个表达式就是谓词
if not re.fullmatch(r"[A-Za-z_]\w*", e): return False    # 一元/二元运算一律不算
last = last_write(struct, e)                             # 跟随一次赋值
return bool(SDK_Q_PREDICATE_RE.fullmatch(strip_parens(last[1].strip())))
```

判据锚「`clearAction` 收到的**布尔**是不是那个谓词」，而不是「这段文本提到过谓词」。多任何一元/二元运算、任何恒假拼接都判红（fail-closed）。

### 3. A06-4 — 载荷字面量（`N1` 增加绑定解析）

新增三个原语（都在门内，底层全走共享）：

- `_fun_spans(struct)`：每个 `fun` 自己的体区间（`match_bracket` + `expr_end`）；
- `_payload_binding(struct, name, pos)`：`pos` 之前、**同一函数体内**最近一次 `val name =` 的右值（用"最近一次"而不是"第一次"，否则同文件多个 `val body` 会互相掩盖）；
- `_payload_sources(expr, lit, struct, pos, depth)`：剥丢弃型作用域（`value_sources`）+ 展开局部 val（≤3 跳）⇒ 得到「这个位置真正求值的是什么」。

判据：
```python
for expr in _payload_sources(code[a:b], code, struct, a):
    lit = _lone_literal(expr)            # 必须**只有一个**字面量（拼接/运算不算）
    if lit is None or _is_json_literal(lit): continue
    findings.append(("N1", "bare non-JSON payload via a local binding", …))
```
精度边界：只判「流进 `formatBody` 首实参的值」；绑到变量但从未进 `formatBody` 的散文不报（§④ 有专门的 clean 臂）。

### 4. A06-5 — 拒绝必须被消费（`PG3` 新臂）

```python
for cond, arm_start, arm_end in _invalid_arms(rstruct):        # 提到 Invalid 的 when 臂
    live = any(path_feasible(rstruct, arm_start + m.start())
               for m in re.finditer(r"\b(?:return|throw)\b(?!@)", rstruct[arm_start:arm_end]))
    if not live: findings.append(("PG3", "the refused page size is not acted on", …))
```
配套 `_when_arms(struct, open_brace)`：按**顶层 `->`** 切臂、臂体到下一个臂的条件行首 ⇒ 多行臂也完整（共享 `when_branches` 按换行切，`LABEL ->\n body` 会给空体，batch5-t06 §6 R6 的已知缺口；本门不复用）。

`if (false) return parsed` ⇒ `path_feasible=False` ⇒ 红。

### 5. A06-6 — 分页键的值 & 拒绝的归属（`PG2` / `PG4`）

**PG2**：判据从"拼写"搬到"被读取的**键值**"。

```python
# 旧：三条写死字面量的正则，且只匹配第 1 实参
re.compile(r'\bgetInt\s*\(\s*"(?:limit|max)"')
re.compile(r'\bget\s*\(\s*"(?:limit|max)"\s*\)[^\n]*?toIntOrNull')
re.compile(r'(?:limit|max)[^\n]{0,40}?\.coerce(?:In|AtLeast)\s*\(\s*1')

# 新：解析每个实参求值出的字符串，与门自己**派生**出的名字集比对
for m in re.finditer(r"\b(getInt|get)\s*\(", struct):
    keys = [v for a, b in call_args(struct, m.end()-1) for v in _page_key_values(lit[a:b], lit, struct, a)]
    hit = [k for k in keys if k in page_names]      # page_names 来自 PageSizeSurface.names
```
`page_names` 由 `_page_declared_surfaces` 从 `names = listOf("limit", "max")` **派生**（不是门里写死）。于是 `getInt(limitKey)`、`getInt("day", "limit")`、任何实参位置都判得到；`get(key)` 只在同一行有 `toIntOrNull` 时算分页读取（保持旧精度）。

**PG4**：判据从"函数体里出现过 exit 2"搬到"**这次**解析的拒绝被它自己消费"。

```python
def _resolve_consumption(struct, pos):
    # 承载 pos 处 resolve 调用的 when 里，Invalid 臂：
    #   'exit' = 在可达路径上 return/throw（用法错误）
    #   'face' = 构造 Invalid/Refused 值交回调用方（由其调用方负责 exit 2）
    #   None   = 没消费（含"臂体被恒假守卫包住"与"根本没有 Invalid 臂"）
```
每个 `resolve` 调用点单独判定；不再允许同一函数里**无关的** exit 2（calendar `doList` 的日期区间拒绝就是这个"替人盖章"的角色）替分页拒绝作证。`face` 分支保留原来的"调用方必须回答"检查。

**顺带**（同一条判据的底座）：`_fn_bodies_with_sigs` 的实现换成共享原语，`covered != count` 的"调用落在函数外"仍 fail-closed。

---

## ④ 四层证据

### L1 本门

```
$ python3 scripts/scan/offload_cli_args_check.py .
offload_cli_args_check: OK (25 rules, 9 payload files, …)            rc=0

$ python3 scripts/scan/offload_cli_args_check.py --selftest
BATCH6_T01_ARMS=11 checks passed
OFFLOAD_CLI_ARGS_SELFTEST=PASS
```
新增 11 条臂（全部手写期望集合，不从运行结果反推）：

| 臂 | 变异 | 期望 |
|---|---|---|
| batch6: fastio budget guard folds false via `(1 == 2)` | `if (limit <= 0 && (1 == 2))` | `S1` |
| batch6: fastio budget guard folds false via `!true` | `if (limit <= 0 && !true)` | `S1` |
| batch6: the send success-set decision folds false via `(0 == 1)` | `if ((0 == 1) && !sendSucceeded(status))` | `S2` |
| batch6: clipboard's SDK-Q predicate is negated in place | `clearAction(!(… >= …Q))` | `L2` |
| batch6: clipboard's SDK-Q predicate is bound to an always-false local | `val isQ = … && false` + `clearAction(isQ)` | `L2` |
| batch6: the payload literal is bound to a local first | `val body = "android-contacts: …"` | `N1` |
| batch6: the page-size `Invalid` arm folds its refusal behind `if (false)` | `is Invalid -> if (false) return parsed` | `PG3` |
| batch6: the page-size key sits in another argument position | `args.getInt("day", "limit")` | `PG2` |
| batch6: the page-size key is bound to a local val first | `val limitKey = "limit"; getInt(limitKey)` | `PG2` |
| batch6: the page-size refusal dies while an unrelated exit 2 stays in the function | `if (false) return NativeOffloadResult(2, …) else 50` | `PG4` |
| **精度臂**：a prose local that never reaches formatBody | 同文件 `val note = "no contacts matched"`（不进 `formatBody`） | 空集 |

> 夹具合法化：`GREEN_CONTACTS` 里三处真实换行落在 Kotlin 字符串内部（`"…missing <query>\n"`、`+ "\n"`），旧正则词法器容忍、共享词法器判 `unterminated`。按 Kotlin 语义改成 `\n` 转义（值不变，仍是非 JSON 的纯文本）。唯一一条"未闭合字面量"臂的期望从 `{"N1"}` 收敛到 `{"LEX"}`（整份源 fail-closed，语义比"这一处跳过"更强）。

### L2 反攻（硬验收，官方驱动）

```
$ python3 verify_batch5.py --witnesses witnesses-A.json --repo wt6/t01 \
    --mode full --only offload_cli_args_check.py --out probe/t01-reattack-A.json
[1/6] offload_cli_args_check.py :: 尝试 A06-1（FALSIFIED · family-hole） -> CAUGHT (rc=1, exact)
[2/6] offload_cli_args_check.py :: 尝试 A06-2（FALSIFIED · family-hole） -> CAUGHT (rc=1, exact)
[3/6] offload_cli_args_check.py :: 尝试 A06-3（FALSIFIED · family-hole） -> CAUGHT (rc=1, exact)
[4/6] offload_cli_args_check.py :: 尝试 A06-4（FALSIFIED · dataflow）    -> CAUGHT (rc=1, exact)
[5/6] offload_cli_args_check.py :: 尝试 A06-5（FALSIFIED · family-hole） -> CAUGHT (rc=1, exact)
[6/6] offload_cli_args_check.py :: 尝试 A06-6（FALSIFIED · spelling）    -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}
restored: 6/6 true
```
每条都**红在自己该红的那条判据上**（`exact`），不是"随便红了"：
```
✗ S1: dead → the non-positive-budget guard is constant-folded false (`limit <= 0 && (1 == 2)`)
✗ S2: dead → the success-set decision is constant-folded false (`(0 == 1) && !sendSucceeded(status)`)
✗ L2: unreachable → clearAction() is fed `isQ` instead of `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`
✗ N1: bare non-JSON payload via a local binding → …: "android-contacts: no contact with id=$id"
✗ PG3: the refused page size is not acted on → arm `is Invalid` must terminate on a live path
✗ PG2: … reads its page size raw: `getInt(limit)`;  ✗ PG4: `doList` does not act on the page-size refusal
```

### L3 邻门（9 道）

`offload_host_path_check` · `offload_permission_reset_sync_check` · `offload_relative_path_unify_check` · `offload_media_b64_wire_check` · `fastio_tar_bounds_check` · `session_provider_enabled_check` · `session_sheet_id_binding_check` · `skill_restore_session_cleanup_check` · `chat_session_id_binding_check` —— **9/9 rc=0**。

### L4 全量

- `bash scripts/scan/scan.sh`：见 §5（本机并发窗口内被沙箱 ENOSYS 打断过，改用逐门驱动 `probe/scan_all.py` 续跑；结果见 §5）。
- `python3 scripts/scan/test_scan.py`：见 §5。
- `git status --porcelain src/`：全程为空（反攻脚本 6/6 `restored=true`）。

---

## ⑤ CI

| 项 | 值 |
|---|---|
| run | **#2696** / id `40231270498` / job `121283318289` / attempt **1** |
| 结论 | **success** |
| head_sha | `fdc80caf9d875c3ba3d607460fec92e52648776a` —— 与本地门提交**逐字符一致** |
| 步骤 | 26 步，唯一 `skipped` = `Publish to Releases`（main 门控，符合预期） |
| job 日志 | `1,486,422 B` |

日志判读（逐条核过，不靠"绿了就当过"）：

| 判据 | 期望 | 实测 |
|---|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 条（带框线） | **1**（L1019 `║  Summary: 112 passed, 0 failed`） |
| `ALL 344 CASES PASS` | 恰 1 条 | **1**（L1415） |
| 门头 `[N/112]` | 112 条互异 | **112 distinct** |
| ` e: `（Kotlin 编译错） | 0 | **0** |
| 含 `FAILED` 的行 | 全是 PASSED 用例名子串 | **6/6 全是**（`slot denied produces FAILED without calling provider PASSED`、`reason PERSISTENCE_FAILED maps to …` 等） |
| 本门自证在 CI 有见证 | 是 | **是**（L1268 `✅ offload_cli_args_check selftest has teeth (OFFLOAD_CLI_ARGS_SELFTEST=PASS)`、L1269 `✅ real tree green`） |
| 本门真树判据 | PASS | **L701 `offload_cli_args_check: PASS (…, batch6 witnesses folded …)`** |

**源码树 = CI 绿树**：门提交之后只有本报告的提交，`git diff --name-only fdc80caf9..HEAD -- . ':(exclude)fix-reports'` = **0 行**（报告回填提交后复核，见文末）。

全量本地证据（与 CI 同一棵树）：

- `probe/scan_all.py`（逐门子进程 + rc 台账 + 断点续跑，替代被沙箱 ENOSYS 打断的 `bash scan.sh`）：**112 门，0 failed**（台账 `/tmp/t01-scanall.done` 112 行、112 个唯一门号，`awk '$2!=0'` 空）。
- `python3 scripts/scan/test_scan.py`：**`✅ ALL 344 CASES PASS`**。
- `git status --porcelain src/`：全程为空（6 条反攻的 patch 都已 `restored`）。

> 沙箱侧两次环境失败（非门红，已按 load 纪律换路径）：`bash scripts/scan/scan.sh` 在 `[92/112]` 被整壳打断（无 Traceback、无 Errno，同机 12 个会话并行跑全量），`probe/scan_all.py` 首轮在 `[14/112]` 同样被打断 —— 两次都是 **进程被外部杀掉**，不是判据失败；恢复办法就是台账断点续跑。

---

## ⑥ 残留（每条带**触发条件 / 触发后方案 / 搁置理由**三件，供主会话直接落 backlog）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由（现级） |
|---|---|---|---|---|
| **R1** | `_pcs_*` 系列（`_pcs_first_arg` / `_pcs_init` / `_pcs_bindings` / `_pcs_calls` / `_pcs_value_text`）仍是自写表达式切片，未换 `call_args` / `expr_end`（`_pcs_strip` / `_pcs_match` 已换共享） | Z1–Z4 出现「读错边界」类见证 | 逐个换 `call_args(struct, paren)` + `expr_end`，Z 族补 4 条臂 | 本波 6 条见证全在 N1 / S1 / S2 / L2 / PG2 / PG3 / PG4，Z 族无见证；按半径纪律不动未触发路径（P2） |
| **R2** | `_payload_sources` 只跟随「同文件 + 同函数体内 + 最近一次 `val`」，跨文件常量、成员属性、`run{}/apply{}` 作用域一律保守放行 | 出现「跨文件常量被直接当载荷」的见证 | 把 `_payload_binding` 扩成「函数体 → 类体 → 同文件顶层」三级作用域，仍 ≤3 跳 | 跨文件形态已被 Z2 覆盖一半；再扩会与 `_pcs_delegates` 重叠，装置比例过高（P2） |
| **R3** | `_invalid_arms` 只判 `when` 臂内的 `return/throw`；`?:`, `require(x){}`, `check()` 等非 when 形态的分页拒绝不判 | 分页拒绝被改写成 `?:` 形态的见证 | 把 `_resolve_consumption` 扩成「when 臂 ∪ elvis 右侧 ∪ require-lambda」三形态 | 当前 5 个注册表面全是 `when` 形态；扩形态需要新装置（P2） |
| **R4** | PG2 的 `page_names` 只从 `names = listOf("…")` 字面量派生；若某 surface 改用变量/拼接，名字集为空 ⇒ PG2 退化成空转（不报，但也不误报） | surface 声明改成非字面量 | 派生失败时落一条 `PG2` 违规（fail-closed），或在门内断言「每个 surface 的 names 必须非空」 | 加这条 fail-closed 会在真树上误红（Photos 的两 surface 名字集与 handler 里实际字符串不同源），本波只做到"派生"（P2） |
| **R5** | `_page_raw_reads` 的 clamp 臂仍是 `(?:limit\|max)` 拼写锚（legacy 保留），未改成派生名字集 | clamp 类见证（如 `args.getInt(limitKey).coerceIn(1,100)`） | 用同一套 `_page_key_values` 解析 clamp 左侧表达式 | 本波见证只打到 `getInt(` 通道；clamp 通道无见证（P2） |
| **R6** | 臂数只在本地可见：门自证 stdout 不进 CI 日志，`BATCH6_T01_ARMS=11` 在 CI 里查不到（只有 test_scan 的 `OFFLOAD_CLI_ARGS_SELFTEST=PASS` 一行） | 出现「臂被删而收尾标记仍在」的静默洞 | §7 的细粒度注册（把 `BATCH6_T01_ARMS=11` 也纳入断言） | test_scan.py 是冻结面，本支不得改（任务书 §3.2）；注册由主会话统一做（P1，成本≈0） |

**同族提醒（给主会话）**：本门与 batch5 的 24 门**同族**——`_dead_condition` 这种「字面量白名单死分支判据」在仓库里可能还有其它门在用。建议并集时 grep `split("&&")` / `== "false"` / `^!\s*true` 三个特征，命中即登记（不在本支半径内）。

---

## ⑦ `test_scan.py` 注册项（本支**未改**冻结面，只提建议）

本门**已在册**，CI 日志实证两行都在（L1268 / L1269）：

```
✅ offload_cli_args_check selftest has teeth (OFFLOAD_CLI_ARGS_SELFTEST=PASS)
✅ offload_cli_args_check real tree green
```

建议主会话在收口提交里追加两条细粒度断言（防「臂被删而收尾标记仍在」）：

```python
("offload_cli_args_check", "BATCH6_T01_ARMS=11", (), (REPO_ROOT,)),
("offload_cli_args_check", "batch6 witnesses folded", (), (REPO_ROOT,)),   # 真树 PASS 行里的短语
```

（第二条直接钉在 §⑤ 里那条 `PASS (…, batch6 witnesses folded …)` 上，等于钉住"新臂参与的是真树判定"这件事。）

---

## ⑧ 复现

```bash
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t01      # 分支 fix/b6-offloadcli @ fdc80caf9

# L1 本门
python3 scripts/scan/offload_cli_args_check.py .            # rc=0，PASS 行含 "batch6 witnesses folded"
python3 scripts/scan/offload_cli_args_check.py --selftest   # BATCH6_T01_ARMS=11 + OFFLOAD_CLI_ARGS_SELFTEST=PASS

# L2 反攻（6 条见证必须逐条 CAUGHT，且 exact）
cd .. && python3 verify_batch5.py --witnesses witnesses-A.json --repo wt6/t01 \
    --mode full --only offload_cli_args_check.py --out probe/t01-reattack-A.json

# L3 邻门（9 道）
cd wt6/t01 && for g in offload_host_path_check offload_permission_reset_sync_check \
  offload_relative_path_unify_check offload_media_b64_wire_check fastio_tar_bounds_check \
  session_provider_enabled_check session_sheet_id_binding_check \
  skill_restore_session_cleanup_check chat_session_id_binding_check; do
  python3 scripts/scan/$g.py . >/dev/null; echo "$g rc=$?"; done

# L4 全量（并发窗口内逐门续跑；正常机器上直接 bash scripts/scan/scan.sh）
python3 probe/scan_all.py --out probe/t01-scan-all.log --ledger /tmp/t01-scanall.done
python3 scripts/scan/test_scan.py                          # ALL 344 CASES PASS
```

**停止节点**：分支 CI 绿 = 本轮结束。**未合并 main**（起手权在用户）。
