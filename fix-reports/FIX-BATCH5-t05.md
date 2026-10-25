# FIX-BATCH5-t05 — 门见证收口（browser_cookie_ui_refresh_check / anthropic_thinking_budget_check）

基线：`e145ec961`（= main `6ac77c6b2` + `scripts/scan/_kotlin_valueflow.py`）
分支：`fix/b5-cookieui-budget`
改动面：**只有两个门脚本**（`git diff --stat` = 2 files, +724/−593）。生产 `.kt` 0 行、`scan.sh` / `test_scan.py` / 三个 `_kotlin_*.py` 零触碰。

---

## ① 门与轴

| 门 | 见证 | 轴 | 失明判据 |
|---|---|---|---|
| `browser_cookie_ui_refresh_check.py` | `G07-A1` | dataflow | V2（session 视口宽度派生） |
| 同上 | `G07-A2` | family-hole | V4（`resetSessionViewport` 调用） |
| 同上 | `G07-A3` | family-hole | V8（`customUA != null` 守卫） |
| 同上 | `G07-A4` | family-hole | V9-R1（清除覆盖后回放存活标签） |
| 同上 | `G07-A5` | family-hole | F4（单域名删 Cookie 的 flush + tick） |
| 同上 | `G07-A6` | anchor | V8 对照臂（**已抓，必须仍抓**） |
| `anthropic_thinking_budget_check.py` | `G09-A1` | dataflow | C1（max_tokens 与 thinkingBudget 同值） |
| 同上 | `G09-A2` | family-hole | C2（`budgetTokens < capped` 上界守卫） |
| 同上 | `G09-A3` | spelling | C3（`"disabled"` 路径不得带 interleaved-thinking） |
| 同上 | `G09-A4` | dataflow | C-OAI-1（OpenAI 侧 `safeMaxTokens`） |
| 同上 | `G09-A5` | family-hole | C-OAI-2（`maxTokens < 2` 守卫） |

两组共 10 条失明 + 2 条对照臂（`G07-A6` 之外，`G09` 侧原有 CAUGHT 臂亦保持）。

**贯穿本支的统一结论**：这些门此前判的是「锚点是否还在源码里」，不是「锚点的效果是否达成」。五类轴其实是同一件事的五个切面——可达性（`family-hole`）、绑定身份（`dataflow`）、常量拼写（`spelling`）、写入点覆盖（`dataflow`）、字面量拼写（`spelling`）。所以修法也不是五套正则，而是给所有「必须存在」换成共享层的「必须可达 / 必须绑定 / 必须决定」。

---

## ② 见证原文（file / find / replace / why_gate_green）

### G07-A1（dataflow）— `sessionViewportActive` 分支左值加合取前缀

- file: `src/android/app/src/main/java/com/rikkaminis/app/ui/browser/BrowserSettingsSheet.kt`
- find:
```
    val effectiveWidthFromPool = when {
        sessionViewportActive -> sessionWidthFromPool
```
- replace:
```
    val effectiveWidthFromPool = when {
        false && sessionViewportActive -> sessionWidthFromPool
```
- why_gate_green：V2 对 `val effectiveWidthFromPool` 的声明窗口做**子串**判定（`session_flow in window`），`sessionWidthFromPool` 仍在窗口里；分支永不成立这件事没判。
- 回归效果：会话级桌面视口永远不会被采用，落回全局/默认宽度。

### G07-A2（family-hole）— 恒假条件换成 `!true`

- file: 同上
- find: `                tabPool.resetSessionViewport()`
- replace: `                if (!true) tabPool.resetSessionViewport()`
- why_gate_green：`_cond_dead_text` 只把 `true` / `false` 字面量当判定算子，`!true` 不在白名单里。
- 回归效果：关闭/重置会话时视口不重置，上一会话的覆盖泄漏进下一会话。

### G07-A3（family-hole）— 守卫的互补 `else` 分支

- file: `src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt`
- find:
```
            if (customUA != null) {
                customUserAgentString = customUA.ifEmpty { null }
            }
```
- replace:
```
            if (customUA != null) {
                customUserAgentString = customUA.ifEmpty { null }
            } else {
                customUserAgentString = null
            }
```
- why_gate_green：V8 用 `setter.find("customUserAgentString = customUA")` 取**一个**写入点，再判「守卫在它前面 ∧ 它落在守卫体内」；`else` 分支的写入是第二个命中，从来没被枚举。
- 回归效果：非 CUSTOM 预设点选（`customUA == null`）清掉用户手打的 UA。
- 自疑（审计方原话）：「块内末尾紧跟一行覆写」同族——一并覆盖（判据改成「守卫体内**每一个**写入都必须由 `customUA` 派生」）。

### G07-A4（family-hole）— V9-R1 的回放调用被 `if (false)` 包裹

- file: 同上
- find: `        replayViewportToLiveTabs()`
- replace: `        if (false) replayViewportToLiveTabs()`
- why_gate_green：V4 已经用 `_live_pos` 判可达性，但 V9-R1 用的是纯子串 `"replayViewportToLiveTabs()" in body` —— 同一门里两条臂用了两套判定口径。
- 回归效果：清除会话覆盖后存活标签不回放，旧宽度留到手动重建页面。

### G07-A5（family-hole）— 单域名删 Cookie 的 flush + tick 被 `if (false)` 包裹

- file: `src/android/app/src/main/java/com/rikkaminis/app/ui/browser/BrowserSettingsSheet.kt`
- find:
```
                cm.flush()
                cookieRefreshTick++
            })
```
- replace:
```
                if (false) {
                    cm.flush()
                    cookieRefreshTick++
                }
            })
```
- why_gate_green：F4 判「块内同时出现 `cm.flush()` 与 `cookieRefreshTick++` 子串」，不判它们是否可达。
- 回归效果：删除单个域名的 Cookie 既不落盘也不刷新列表，被删域名仍显示。

### G09-A1（dataflow）— 同名 `var` 重新绑定

- file: `src/android/app/src/main/java/com/rikkaminis/app/provider/anthropic/AnthropicProvider.kt`
- find: `                val budgetTokens = thinkingBudget(cappedMaxTokens, thinkingLevel)`
- replace:
```
                var cappedMaxTokens = maxTokens
                val budgetTokens = thinkingBudget(cappedMaxTokens, thinkingLevel)
```
- why_gate_green：C1 只比较两个实参的**文本**（`norm(arg) != norm(max_expr)`）；重新绑定后两处拼写仍一致。
- 回归效果：预算读原始请求值、线上值读钳制值，`budget_tokens ≥ max_tokens` → 服务端 400。

### G09-A2（family-hole）— 恒真析取用算术拼写

- file: 同上
- find: `                if (budgetTokens > 0 && budgetTokens < cappedMaxTokens) {`
- replace: `                if (budgetTokens > 0 && (1 == 1 || budgetTokens < cappedMaxTokens)) {`
- why_gate_green：C2 的「短路」白名单只认 `true` / `false` 字面量，`1 == 1` 不在其中。
- 回归效果：上界检查被吞没，`budget_tokens` 可等于/超过 `max_tokens` → 400。

### G09-A3（spelling）— beta 常量拼成两段字面量

- file: 同上
- find: `                "disabled" -> Unit`
- replace: `                "disabled" -> betaFlags.plusAssign("interleaved-" + "thinking-2025-05-14")`
- why_gate_green：C3 在 `"disabled"` 分支体里搜连续子串 `interleaved-thinking`；编译器折叠后才相等的两段字面量不含该子串。
- 回归效果：用户关掉扩展思考，请求头仍带 `anthropic-beta: interleaved-thinking-2025-05-14`。
- 自疑（审计方原话）：`"${"interleaved"}-thinking-2025-05-14"` 模板插值同样能穿——一并覆盖。

### G09-A4（dataflow）— OpenAI 侧词法作用域遮蔽

- file: `src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt`
- find: `            echoPolicy = injectThinkingParams(body, thinkingLevel, safeMaxTokens)`
- replace:
```
            val safeMaxTokens = maxTokens
            echoPolicy = injectThinkingParams(body, thinkingLevel, safeMaxTokens)
```
- why_gate_green：C-OAI-1 验证外层存在合法的 `val safeMaxTokens = …` 且两处引用的文本一致；遮蔽发生在 `if (!isMistral)` 块内，两个读者落在**不同块**里，文本一致而绑定不同。
- 回归效果：注入器拿原始值算预算，线上 `max_tokens` 仍是被钳制的值 → 两读者不一致。

### G09-A5（family-hole）— `< 2` 守卫前置恒假合取

- file: `src/android/app/src/main/java/com/rikkaminis/app/provider/thinking/ThinkingRuleResolver.kt`
- find: `        if (ctx.maxTokens < 2) {`
- replace: `        if ((1 == 2) && ctx.maxTokens < 2) {`
- why_gate_green：C-OAI-2 的短路判定同样只认裸字面量 `false`。
- 回归效果：非正 `maxTokens` 不再把预算归零，而 provider 侧把线上值下限钳到 1 → 原始预算超过 `max_tokens`。

### 对照臂（必须仍被抓）

`G07-A6`（anchor）：`if (customUA != null) { settings.userAgentString = customUA }` → `if (customUA != null) Unit; settings.userAgentString = customUA`。本支改造后仍 `CAUGHT`（`_inside_guard_body` 用共享括号配平判体内区间，`Unit` 之后的行不在体内）。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### 3.0 先删自写词法器（硬约束 1）

| 删除 | 替身 |
|---|---|
| `strip_comments` / `strip_comments_only`（手写、只认 `//`、不认模板） | `_kotlin_lexer.mask_comments` |
| 自写 `mask_code` | `_kotlin_lexer.mask_code` |
| `_match_paren` / `_brace_end` / `brace_body` / `paren_body` / `_balanced` | `_kotlin_ast.match_bracket` |
| `_brace_ranges` / `_smallest_block_with` / `brace_slice`（行式大括号计数） | `match_bracket` 驱动的区间派生 |
| `split_top_level_args` / `find_calls` 的括号扫描 | `_kotlin_valueflow.call_args` |
| `enclosing_ifs` / `enclosing_when_arms` / `enclosing_conditions`（只认 `if (...) {`） | `_kotlin_ast.enclosing_conditions`（含无括号 if 链、`when` 臂、`else` 取反） |
| `literal_decides` / `BOOL_OP_LITERAL`（`true`/`false` 白名单） | `guard_feasible` + `const_bool` + 新增 `required_atoms` |
| `skip_string` / `skip_template` | 两个共享视图（长度严格相等，位置可互换） |

两个视图（`mask_code` / `mask_comments`）长度严格相等，所以「按代码结构取位、按字面量取值」可以在同一套偏移上混用。

### 3.1 五条判据的形态变化

**V1/V2/V6/B1（browser）**：`re.search(...)` → `_live_hits(...)`（可达性）；V2/V6 的「派生」从 `session_flow in _statement_window(code, key)` → `_derives_live(code, key, flow)`：沿 `val key = <expr>` 取初始化表达式区间（`by remember(...)` 这类委托退回整条声明），要求 needle **出现在可达路径上**。

**V4 / V9-R1 / V9-R2 / V7（browser）**：`_cond_dead_text` 白名单 → `path_feasible`；`_calls` / `_replay_positions` 全部走 `_live_hits`，同门所有臂统一口径。

**V8（browser）**：单点 `find()` → **枚举全部写入点**：`customUserAgentString\s*=(?!=)` 与 `settings.userAgentString\s*=(?!=)` 两族逐个判四件事——在守卫之前 / 不在守卫体内（`else`、块后覆写）/ 在死分支 / 右值不由 `customUA` 派生。

**F4（browser）**：`"flush()" in block` → `_live_hits(code, r"\bflush\s*\(\s*\)", block)`；全局 `cookieRefreshTick++` 计数也改成只数可达的；`candidateUrls` 的两种 scheme 从行式切片改成 `_decl_span`（初始化表达式区间，含 if/else 两臂）。

**C1（anthropic）**：文本相等 → 文本相等 **且** `is_single_assignment(body, token)`：同一个标识符在函数内只允许一个绑定（重新声明 / 遮蔽 / 重赋值一律红）。

**C2（anthropic）**：`literal_decides` → `guard_feasible(所有闭包条件合取)` + `required_atoms`：`A && (恒真析取)` 里被吞没的原子不再「必需」，判据因此看到「比较写了但不决定」。

**C3（anthropic）**：`"interleaved-thinking" in body` → `fold_strings(body)` 之后再搜：把 `"a" + "b"` 与 `"${"a"}b"` 折叠成 `"ab"`；同时把 `.add(` 扩成 `\.(?:add|plusAssign|addAll)\s*\(`。

**C-OAI-1（anthropic）**：与 C1 同款单绑定约束（写出点与注入点可能落在不同块，文本一致不代表绑定一致）。

**C-OAI-2（anthropic）**：`literal_decides(inner)` → `guard_feasible(inner)` **且** `required_atoms(inner)` 里必须含 `< 2`；另把 `if_blocks` 换成共享实现（无括号 if 链、`when` 臂都覆盖）。

### 3.2 fail-closed

两个门的所有入口都在 `mask_code` / `mask_comments` 外包 `try/except KotlinLexError`，词法失败 → 直接报违规（不回退原文扫描）。区间派生失败（`_smallest_block_with` 返 `-1`、`_decl_span` 返 `-1`）→ 报违规。

### 3.3 自证臂

| 门 | 标记 | 臂数 |
|---|---|---|
| `browser_cookie_ui_refresh_check.py` | `BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS (51 arms)` | 51 |
| `anthropic_thinking_budget_check.py` | `ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS (29 arms)` | 29 |

本支新增臂（每条见证一个红臂 + 精度绿臂）：

- browser：`V2 session branch dead-prefixed by false &&`、`V2 session branch dead-prefixed by 1 == 2 &&`、`V4 reset wrapped in a statically-dead if (!true)`、`V8 complementary else branch wipes the stored UA`、`V8 post-block write wipes the UA the guard just stored`、`V8 guarded write stores a value the caller did not supply`、`V9 the collection point's replay is dead-wrapped`、`F4 per-domain flush+bump dead-wrapped by if (false)`。
- anthropic：`budget identifier re-bound to the raw value`、`guard kept but short-circuited by 1 == 1 ||`、`disabled arm builds the beta token by concatenation`、`disabled arm builds the beta token by interpolation`、`injector block re-binds the wire value to the raw one`、`resolver guard short-circuited by (1 == 2) &&`；精度绿臂：`concatenated beta on a non-disabled arm`、`bounds split across nested guards`、`an unrelated tautology does not swallow the < 2 guard`。

---

## ④ 四层证据

**L1 — 门自身**

```
$ python3 scripts/scan/browser_cookie_ui_refresh_check.py .        # rc=0
$ python3 scripts/scan/anthropic_thinking_budget_check.py .        # rc=0
$ python3 scripts/scan/browser_cookie_ui_refresh_check.py --selftest
BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS (51 arms)
$ python3 scripts/scan/anthropic_thinking_budget_check.py --selftest
ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS (29 arms)
```

耗时（真树）：browser 0.72 s、anthropic 0.48 s；自测 0.35 s / 0.27 s。

**L2 — 共享原语自证**（未改动，只做验收）

```
KOTLIN_LEXER_SELFTEST=PASS (20 arms)
KOTLIN_AST_SELFTEST=PASS (32 arms)
KOTLIN_VALUEFLOW_SELFTEST=PASS (26 arms)
```

**L3 — 邻门与全量**

- 邻门 8/8 rc=0：`session_provider_enabled` / `file_preview_url_encode` / `katex_ready_race` / `modeluse_proto_single_source` / `offload_media_b64_wire` / `openminis_compat_field_parity` / `deeplink_settings_routes` / `filepreview_print_bounded`。
- 全量：`scan.sh` 名单 110 门 + `i18n_check.py` = **111/111 rc=0**（逐门单独进程驱动，见 §8；沙箱并发窗口下 `bash scan.sh` 会在 16/112 处被 `OSError: [Errno 38]` 打断，任务书 §5 已预告）。
- 夹具：`test_scan.py` 46 个测试函数中，**28 个已在沙箱内跑通（145 ✅ / 0 ❌）**；剩余 18 个（`test_da1016/1017/1018/1019/… gates`、`test_real_repo` 等）**依赖 `subprocess.run`**，在当前 13 支并行的沙箱里被 `waitpid → OSError 38` 打断（不是断言失败：单独复跑 `test_da1017_gates` 得 0 ❌）。这部分的权威读数取自 CI（见 §5），本地不做二次归因。

**L4 — 反攻（本支最硬证据）**

`verify_batch5.py --mode full --only <gate>`（补丁 → 跑门 → 还原 → sha256 校验）：

| 门 | FALSIFIED → | CAUGHT 保持 | 打不上 | restore |
|---|---|---|---|---|
| `browser_cookie_ui_refresh_check.py` | 5 → **5 CAUGHT** | 1 → 1 CAUGHT | 0 | 6/6 `True` |
| `anthropic_thinking_budget_check.py` | 5 → **5 CAUGHT** | 1 → 1 CAUGHT | 0 | 6/6 `True` |

合计 **12/12 全部翻红**（原 10 条盲见证全部 `GATE_CAUGHT_WITNESS`，2 条原有对照臂仍 `CAUGHT`），`git status --porcelain src/` 为空（无变异残留）。

结果文件：`probe/t05-browser_cookie_ui_refresh_check.json`、`probe/t05-anthropic_thinking_budget_check.json`。

---

## ⑤ CI

| 项 | 值 |
|---|---|
| run | `40208987826`（`#2690` Build RikkaMinis Android APK） |
| job | `121215959831`（`build`） |
| attempt | **1** |
| conclusion | **success** |
| head_sha | `3f2676f982ec0c9dbe5127bfb5699218c782d64d` = 本地 `git rev-parse HEAD` **逐字符一致** |
| 日志 | `probe/t05/ci-40208987826.log`，**1,486,235 B** |
| 步骤 | 26 步，**非 success 仅 `Publish to Releases`（分支门控 skipped，`grep -c` = 0 次出现）** |

日志逐条判读：

| 判据 | 读数 |
|---|---|
| `Summary: 112 passed, 0 failed` | 带框线形态（`║  Summary: …`）恰 **1** 条，line 1019 @ `15:53:59Z` |
| `ALL 344 CASES PASS` | 恰 **1** 条，`✅` @ `15:56:19Z` |
| 门头 `[N/112]` 互异 | **112** 个不重复编号 |
| ` e: `（Kotlin 编译错误行） | **0** |
| 含 `FAILED` 的 6 行 | 逐条核对**全是 PASSED 用例名子串**（`slot denied produces FAILED without calling provider` / `reason PERSISTENCE_FAILED maps to persistence_failed` / `phase FAILED maps to Failed` / `terminal FAILED maps to Failed` / `reason EXECUTION_FAILED maps to all_fallbacks_exhausted` / `the fixed tail still reaches a legal FAILED terminal`）—— **零游离失败** |

结论：**分支 CI 绿 = 本支的确定性停止节点**。`test_scan.py` 的权威读数取自此处的 `ALL 344 CASES PASS`（本地沙箱因 13 支并行 + `waitpid → OSError 38` 只跑完 28/46 个夹具函数，见 §4 L3）。

---

## ⑥ 残留

**R1 — `required_atoms` 缺一个共享原语位**（共享资源需求，交主会话）
- 现状：两个门各自实现一份 `required_atoms`（约 30 行，逻辑相同）。
- 触发条件：第三道门也需要「某比较是否决定分支」这一判定。
- 触发后方案：把它提到 `_kotlin_ast`（`required_atoms(cond)` + `fold_constants(expr)`），三门统一 import。
- 搁置理由：任务书冻结共享模块；两份实现目前完全一致、共 60 行，仍在可接受半径内。

**R2 — `fold_strings` 只折叠编译期常量**
- 现状：`"a" + CONST_VAL`、`buildString {}`、`String.format` 拼出的 beta 常量仍不可见。
- 触发条件：出现用常量变量拼 beta 名的真实写法（当前仓内零处）。
- 触发后方案：在 `_kotlin_ast` 加「局部常量表」再折叠一次（需要跨语句数据流）。
- 搁置理由：跨语句常量传播是另一类装置（属 `value_sources` 范畴），本支半径内不建。

**R3 — V2 的派生只判「可达」，不判「优先级」**
- 现状：`when { global -> …; session -> … }`（省略 session 优先）仍绿——只要 session 分支可达。
- 触发条件：真出现「会话覆盖应压过全局」的顺序倒置。
- 触发后方案：断言**第一个**能产出非零值的可达分支必须是 session 那支。
- 搁置理由：无见证、且现有判据已挡住「删掉/改反了」两类；加顺序判据会误伤把 session 判断搬进 `if` 链的等价重构（需要先有该重构的真实需求）。

**R4 — `_smallest_block_with` 的复杂度是 O(块数 × 文件长度)**
- 现状：真树 0.72 s（60 KB 文件、~1000 个 `{`）。
- 触发条件：目标文件涨到 300 KB 以上，或 CI 上该门超过 5 s。
- 触发后方案：一次扫描建 `{ → }` 映射表后二分，不改判据语义。
- 搁置理由：当前耗时远低于 CI 预算；过早优化会让区间派生逻辑变复杂。

**R5 — `is_single_assignment` 只看「是否唯一」，不看「唯一的那次是否被钳制」**
- 现状：把唯一的 `val cappedMaxTokens = clampOutboundMaxTokens(...)` 换成 `val cappedMaxTokens = maxTokens`（两读者仍同绑定）不会被 C1 抓。
- 触发条件：出现「同绑定但都没钳制」的写法。
- 触发后方案：要求唯一绑定来自一个「有钳制语义的调用」（需要命名钳制函数，容易误伤）。
- 搁置理由：该形态属 `G09-A1` 的自疑延伸但不在见证集内；且它会让判据锚在函数名上（本批反复证明这是脆弱锚）。

**R6 — F4 只判 `setCookie(` 的块内 flush/tick**
- 现状：把删除改成「先 `getCookie` 过滤再统一 `flush` 一次」（合法重构）可能因块内不再有 tick 而红。
- 触发条件：真出现这种重构时按报告调判据。
- 触发后方案：把「块内」放宽成「同函数内且在删除调用之后、且可达」。
- 搁置理由：无真实重构需求；放宽会削弱「每个删除动作自持久化」的本地性保证。

---

## ⑦ `test_scan.py` 注册项（交主会话统一注册，本支未改冻结面）

两个门在两处已有注册（`test_scan.py` 现有条目断言的就是标记前缀，新增 `(N arms)` 不破坏它们）：

| 门 | 脚本 | 标记串（断言用） | 臂数 |
|---|---|---|---|
| `browser_cookie_ui_refresh_check.py` | `browser_cookie_ui_refresh_check.py` | `BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS (51 arms)` | 51 |
| `anthropic_thinking_budget_check.py` | `anthropic_thinking_budget_check.py` | `ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS (29 arms)` | 29 |

建议主会话在 `test_scan.py` 里把断言从 `...=PASS` 收紧为 `...=PASS (51 arms)` / `...=PASS (29 arms)`——这样臂被删也会红。

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t05

# 共享原语自证
python3 scripts/scan/_kotlin_lexer.py --selftest          # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest            # 32 arms
python3 scripts/scan/_kotlin_valueflow.py                 # 26 arms

# 门自证 + 真树
python3 scripts/scan/browser_cookie_ui_refresh_check.py --selftest
python3 scripts/scan/browser_cookie_ui_refresh_check.py .
python3 scripts/scan/anthropic_thinking_budget_check.py --selftest
python3 scripts/scan/anthropic_thinking_budget_check.py .

# 反攻（补丁→跑门→还原→sha256）
python3 ../../verify_batch5.py --witnesses ../../witnesses-A.json --repo . --mode full \
    --only browser_cookie_ui_refresh_check.py --out ../../probe/t05-browser_cookie_ui_refresh_check.json
python3 ../../verify_batch5.py --witnesses ../../witnesses-A.json --repo . --mode full \
    --only anthropic_thinking_budget_check.py --out ../../probe/t05-anthropic_thinking_budget_check.json

# 全量（沙箱并发窗口下用逐门驱动，见 probe/t05/run_inproc.py：111/111 rc=0）
python3 ../../probe/t05/run_inproc.py 0 200
```

`probe/t05/` 下产物：`gates.jsonl`（111 门逐门 rc）、`fixtures.jsonl`（夹具函数级结果）、两次反攻 JSON、`run_inproc.py` / `run_fixtures.py` 驱动。
