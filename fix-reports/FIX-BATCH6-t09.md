# FIX-BATCH6-t09 — tool_result_image_branch / provider_write_rules 门见证收口

分支 `fix/b6-toolimg-provwrite` · 基线 `e145ec961`（= main `6ac77c6b2` + 第六批共享原语 `_kotlin_valueflow`）
门提交 `2b5cfe0ba` · 组 A 报告第 03 门与第 09 门 · **12 条见证（12 实锤失明，0 对照）全部翻红**

---

## §1 门与轴

| 门 | 行数（改前→改后） | 见证 | 轴分布 |
|---|---|---|---|
| `scripts/scan/tool_result_image_branch_check.py` | 1,278 → 1,590 | 6 | family-hole ×4（A03-1/2/3/5）· dataflow ×2（A03-4/6） |
| `scripts/scan/provider_write_rules_check.py` | 557 → 871 | 6 | dataflow ×3（A09-1/2/4）· anchor ×1（A09-3）· completeness ×2（A09-5/6） |

两门的共同病因：**判据锚「某个 token 被写出」，缺陷动「这个 token 的值/位置/可达性」**。修法一律推到值流 / 位置 / 可达性，底层词法与折叠全部改走共享层（硬约束 #1）。

改动面：**只改这两个门脚本**。生产 `.kt` 0 行、`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰、门 112 / 夹具 344 不变。

---

## §2 见证原文（全部已由主会话机械复证，原树 rc=0）

### 门 03 `tool_result_image_branch_check.py`

| # | 文件 | find → replace | 旧判据为何绿 |
|---|---|---|---|
| A03-1 | `AnthropicProvider.kt` | `if (part.imageData != null && part.imageMimeType != null) {` → `if ((1 == 2) && part.imageData != null && part.imageMimeType != null) {` | `dead_guard_in` 里的 `BOOL_OP_LITERAL` 只认 `true`/`false` 字面量，`(1 == 2)` 不折；且守卫条件里仍**含** `imageData`，`_if_conditions` 的「条件含 needle」分支直接判活 |
| A03-2 | `GeminiProvider.kt` | `part.imageData?.let { img ->` 后插 `if (true) return@let` | 判据只看「分支里有 `imageData` + 有没有短路字面量」，块体被提前 return 截断不在观测面内 |
| A03-3 | `MessagePayloadFilter.kt` | 三个媒体臂外面套 `((1 == 2) && (… \|\| … \|\| …))` | 同上：`BOOL_OP_LITERAL` 只认字面 `false`；`audioParts.isNotEmpty()` / `imageParts.isNotEmpty()` / `isLastUserTurn` 子串全在，Crit5 与 C-IMG-1 全绿 |
| A03-4 | `OpenAIProvider.kt` | `isLastUserTurn = index == rawLastUserIndex, turnImageCount = imageParts.size` → `= false, = 0` | 只判「站点里有 `hasWirePayload(` 这个调用」，不看实参是不是常量，也不看决策是否只由该调用给出 |
| A03-5 | `OpenAIProvider.kt` | `for (audio in msg.audioParts)` 前插 `if (msg.imageParts.isEmpty() && turnImages.isEmpty())`（无花括号体） | `innermost_if` 只认「带花括号的 if」；而 `ablk` 里同时出现图像与音频标记时**直接 continue**（「同块即合规」提前放行） |
| A03-6 | `OpenAIProvider.kt` | `for (audio in msg.audioParts)` → `val _droppedAudio = msg.audioParts` + `for (audio in emptyList<AudioPart>())` | C-IMG-1 是树级「任意文件有 `.carrier` 读」即通过，不判这次读是否可达/被消费；同一文件 Responses 路径的真读替它担保 |

### 门 09 `provider_write_rules_check.py`

| # | 文件 | find → replace | 旧判据为何绿 |
|---|---|---|---|
| A09-1 | `ProviderWriteRules.kt` | `= raw?.trim()?.takeIf { it.isNotEmpty() }` → `= raw.also { raw?.trim()?.takeIf { s -> s.isNotEmpty() } }` | W10 在**函数体文本**上跑 `TRIM_RE` / `COLLAPSE_RE`：两个 token 都还在（在被丢弃的 `.also` 作用域里），返回的却是 `raw` |
| A09-2 | `ProvidersCollection.kt` | `val newBase = normalizeCustomBaseURL(s)` → `val _ignoredNorm = normalizeCustomBaseURL(s)` + `val newBase = s` | W8 只问「这个 face 里有没有 `normalizeCustomBaseURL(`」，不追返回值是否流入 `copy(customBaseURL = …)` |
| A09-3 | `ProviderMutationMethods.kt` | `else normalizeCustomBaseURL(params.optString("customBaseURL", ""))` → 三行 `else params.optString(\n "customBaseURL", ""\n).ifEmpty { null }` | `inline_blank_on_base_url_lines` 按**行**判：锚点行没有 pattern、pattern 行没有锚点，2 行窗口也接不上 |
| A09-4 | `ProviderConfig.kt` | 把整条 `trim/trimEnd/takeIf` 链搬到死局部 `_unused`，`val raw = customBaseURL ?: return null`，后续用 `raw` | W9 对 getter **整段文本**做子串匹配：链还在文件里，只是不在 `return` 的值上 |
| A09-5 | `ProviderWriteRules.kt` | `normalizeCustomModelId` 体 → `raw` | 同文件姊妹函数漏检：上一轮只给 `normalizeCustomBaseURL` 补了函数体判据（W10），同构的 `normalizeCustomModelId` 从来没人验 |
| A09-6 | `ProviderWriteRules.kt` | `isCustomBaseURLWriteAllowed` 体 → `= true` | 只验声明存在（W1）与调用点存在（W7），从不验谓词体是否真的读了 `providerType.supportsCustomBaseURL` |

---

## §3 判据改造（旧形态 → 新形态，逐条）

### 3.1 自写词法器整族删除（硬约束 #1）

删除：`strip_comments_and_strings`、`_skip_string`、`_close_of`、`_expr_span`、`matching`、`if_blocks`（本门版）、`innermost_if`、`is_else_chained`、`preceding_sibling`、`blank_comments`、`blank_comments_and_strings`、`BOOL_OP_LITERAL` 字面量白名单、`inline_blank_on_base_url_lines` 的行窗口、`expression_body` 的「遇第一个配对括号就收」截断。

改走：`_kotlin_lexer.{mask_code,mask_comments}`（`code_view` / `literal_view`）、`_kotlin_ast.{match_bracket,split_top,strip_parens,const_bool,const_value,stmt_spans,if_blocks,enclosing_conditions,function_body}`、`_kotlin_valueflow.{value_sources,peel_discarding,guard_feasible,path_feasible,contains_ident,expr_end,call_args}`。词法/折叠/可达性只有一个实现；本门只保留自己的臂名、违规定位与文案。

### 3.2 门 03

| 见证 | 旧判据 | 新判据 |
|---|---|---|
| A03-1 | 条件文本含 needle ⇒ 活；死守卫只认 `true`/`false` 字面量 | `path_feasible(code, pos)` 逐个引用判活（`const_bool` 折 `(1 == 2)` / `!true` / `when(false)`，`guard_feasible` 折嵌套）；**外层 `if (false)` 包裹也判死**（逐引用判，不要求条件里含 needle） |
| A03-2 | 块里出现 `imageData` 即算消费 | 新增语句级可达性：先切 lambda 的 `params ->` 头（`_filter_lambda_body`），再按 `stmt_spans` 顺序判「首个无条件退出之后全部不可达」；`if (true) return@let` 经 `stmt_is_exit`（`const_bool` + 退出词）判为退出 |
| A03-3 | `BOOL_OP_LITERAL` 子串白名单 | 谓词体按**顶层 `\|\|` 臂**切分，逐臂 `chain_feasible`：常量折叠 + **同变量整数区间求交**（`turnImageCount > 0 && turnImageCount < 0` 同样判死）；单臂常假即点名它压住的媒体臂 |
| A03-4 | 站点里有 `hasWirePayload(` 即通过 | `predicate_call_problems`：①谓词调用必须**独占** filter 决策（尾语句剥掉 `return@label` 后必须恰为该调用，多一个 `&& m.content.isNotEmpty()` 也算违规）②实参不得常量折叠得值（`false` / `0`）③不得漏参（形参名从谓词声明派生） |
| A03-5 | 只判带花括号的 if + 「同块有图像标记即 continue」 | 每个 `input_audio` 写点遍历 `enclosing_conditions`（共享 `if_blocks` 支持**无花括号体**），若某条件在「本回合带图」时必假（`_infeasible_when_images`：每个顶层 `\|\|` 臂都含图像空断言）即红；另保留 else 兄弟互斥判定，但收窄为「两个分支各自只装一种载体」（`_exclusive_media_split`），避免把「整条序列化路径在一个分支里」误判 |
| A03-6 | 树级「有读就算消费」 | 判「读能不能上总线」：读点必须 `live_path`（可达 + 无前置退出）、不得是**死绑定**（`val x = msg.audioParts` 而 `x` 无人用）、不得是**常量空序列循环**（`for (x in emptyList<T>())` 体内还写媒体标记）；三者任一即「读了就扔」，不再替同文件别处的真读担保 |

### 3.3 门 09

| 见证 | 旧判据 | 新判据 |
|---|---|---|
| A09-1 | 函数体文本上有 `TRIM_RE` + `COLLAPSE_RE` | `effective_value(body)`：反复 `peel_discarding`（`also/apply/onEach/…` 剥到接收者）+ `_fold_const_if`（`if (true) A else B` 折到真正执行的分支），**再**在留下的值上判 trim + blank→null |
| A09-2 | face 里有 `normalizeCustomBaseURL(` 调用 | `_values_leaving(body)`：取该 face 每个 `customBaseURL = …` 写点与裸 `return …` 的值，逐个 `_derives_from_normalize`（直接含调用 / 一跳局部绑定 / blank-clear 常量）；helper 被调用但值是别的 ⇒ 红 |
| A09-3 | 逐行 + 2 行窗口 | 语句级（`stmt_spans`，先经 `_interior` 剥外层花括号，避免「整函数一条语句」）：同一语句里既有 `customBaseURL` 又有 inline blank 形态即红，跨几行都不影响 |
| A09-4 | getter 整段子串 | 取 getter 的每个 `return` 值：必须自身带完整链（`.trim()` + `trimEnd('/')` + blank→null），或引用了一个**带完整链**的局部；链躲在死局部上即红 |
| A09-5 | 无 | 新增 **W11**：`normalizeCustomModelId` 与 `normalizeCustomBaseURL` 同判据（值必须 trim + blank→null），姊妹函数不再漏 |
| A09-6 | 只验声明 + 调用点 | 新增 **W12**：谓词体必须①**读到每一个形参**（形参名从声明派生，不写死）②必须 consult `supportsCustomBaseURL`③必须保留 blank 清空臂④不得折成常量（`= true`、`… \|\| true` 都折） |

### 3.4 顺带修掉的共享层用法缺口（不改共享层，门内绕过并登记）

- `expression_body` / `expr_end` 会在配对括号处/行尾 `,` 处截断：本支新增本地 `_rhs_end`（深度 0 的 `,` / `)` / `}` / 非续行换行为界），`|| true`、`else current.customBaseURL,` 不再被切掉。
- `peel_discarding` 不剥 `takeIf`（它是取值作用域）——W10 正好要「值仍带 takeIf」才算 collapse，语义吻合，未绕。
- `shared_if_blocks` 不返回 `if_start`：本门用 `IF_KW.finditer` + `match_bracket` 对齐 `then_start` 反查，不做第二套括号解析。

---

## §4 四层证据

### L1 — 自测臂（真树绿 + 变异恰好红）

```
$ python3 scripts/scan/tool_result_image_branch_check.py --selftest
...
  [OK] [batch6] an outer `if (false)` wrapper that never names imageData is dead-code red
  [OK] [batch6] a folded `(1 == 2) &&` guard is dead-code red
  [OK] [batch6] `if (true) return@let` cutting the let block short is dead-code red
  [OK] [batch6] a live `?.let` with a real guard stays green
  [OK] [batch6] a folded `(1 == 2) &&` payload arm is red
  [OK] [batch6] a self-contradicting turn-image range is red
  [OK] [batch6] a delegating site with constant arguments is red
  [OK] [batch6] a delegating site with a dropped argument is red
  [OK] [batch6] a delegating site with an extra text-only conjunct is red
  [OK] [batch6] the unmodified delegating site stays green
  [OK] [batch6] audio behind an brace-less image-emptiness guard is C-IMG-2 red
  [OK] [batch6] audio behind an braced image-emptiness guard is C-IMG-2 red
  [OK] [batch6] a dead carrier binding / empty serialization loop is C-IMG-1 red
  [OK] [batch6] restoring the real loop clears both carrier arms
TOOL_RESULT_IMAGE_BRANCH_SELFTEST=PASS (34 arms)
```

```
$ python3 scripts/scan/provider_write_rules_check.py --selftest
PASS S20-witness-also-discards-normalization-W10   (rc=1 got=('w10',) exp=('w10',))
PASS S20b-witness-apply-discards-normalization-W10 (rc=1 got=('w10',) exp=('w10',))
PASS S21-witness-normalize-result-discarded-W8     (rc=1 got=('w8',)  exp=('w8',))
PASS S22-witness-three-line-inline-blank-W8        (rc=1 got=('w8',)  exp=('w8',))
PASS S23-witness-chain-on-dead-local-W9            (rc=1 got=('w9',)  exp=('w9',))
PASS S24-witness-sibling-modelid-body-emptied-W11  (rc=1 got=('w11',) exp=('w11',))
PASS S25-witness-policy-body-constant-true-W12     (rc=1 got=('w12',) exp=('w12',))
PASS S25b-witness-policy-constant-by-or-W12        (rc=1 got=('w12',) exp=('w12',))
PASS S25c-witness-policy-drops-capability-arm-W12  (rc=1 got=('w12',) exp=('w12',))
PASS S25d-witness-policy-ignores-providerType-W12  (rc=1 got=('w12',) exp=('w12',))
PROVIDER_WRITE_RULES_SELFTEST=PASS
PROVIDER_WRITE_RULES_SELFTEST_ARMS=30
```

臂数：门 03 由 20 → **34**（新增 14）· 门 09 由 20 → **30**（新增 10）。两门标准形态（真树）在各自自测里都有 clean 臂（门 03 `S15`/`S19` 语义由「restoring … 清空两臂」覆盖；门 09 `S15-fixed-shape-green` / `S19-fixed-shape-green-W10` 显式重跑）。

真树直跑：

```
$ python3 scripts/scan/tool_result_image_branch_check.py .   # rc=0 ✅ every provider serializer consumes ToolResult.imageData
$ python3 scripts/scan/provider_write_rules_check.py .        # rc=0 provider_write_rules: ALL GREEN (W1…W12)
```

共享层自测（未改动，仅引用）：`KOTLIN_LEXER_SELFTEST=PASS (20 arms)` · `KOTLIN_AST_SELFTEST=PASS (32 arms)` · `KOTLIN_VALUEFLOW_SELFTEST=PASS (26 arms)`

### L2 — 反攻（硬验收，12/12 exact）

驱动：`verify_batch5.py --witnesses witnesses-A.json --repo wt6/t09 --mode full --only <gate>`
（统一跑法，不自造；每条 = 打补丁 → 跑门 → 判 rc/判据 → restore → sha256 校验）

```
[1/6] tool_result_image_branch_check.py :: 尝试 A03-1（FALSIFIED · family-hole） -> CAUGHT (rc=1, exact)
[2/6] …A03-2 -> CAUGHT [3/6] …A03-3 -> CAUGHT [4/6] …A03-4 -> CAUGHT
[5/6] …A03-5 -> CAUGHT [6/6] …A03-6 -> CAUGHT
SUMMARY: {'CAUGHT': 6}                       → probe/t09-post-toolimg.json

[1/6] provider_write_rules_check.py :: 尝试 A09-1（FALSIFIED · dataflow）     -> CAUGHT (rc=1, exact)
[2/6] …A09-2 -> CAUGHT [3/6] …A09-3（anchor）-> CAUGHT [4/6] …A09-4 -> CAUGHT
[5/6] …A09-5（completeness）-> CAUGHT [6/6] …A09-6（completeness）-> CAUGHT
SUMMARY: {'CAUGHT': 6}                       → probe/t09-post-provwrite.json
```

**12/12 CAUGHT，0 BLIND，restore 12/12 True**；事后 `git status --porcelain src/` 为空（无一残留变异）。
（中途一次沙箱 ENOSYS 窗口把 A09-1 的补丁留在树上，已 `git checkout -- src/` 救回并重跑全 6 条，上面的数字是重跑结果。）

### L3 — 邻门抽检（8/8 rc=0）

`tool_result_image_branch_check` · `provider_write_rules_check` · `provider_rpc_symmetry_check` · `model_entry_override_parity_check` · `memory_gate_provider_fields_check` · `webapp_asset_url_cleanup_check` · `i18n_check` · `file_preview_url_encode_check` —— 全部 rc=0。

全量 `bash scripts/scan/scan.sh` 在本机连跑两次被沙箱打死（一次 `exit -1`，一次 stall-guard 180s / exit 125，属既有 S28 ENOSYS 窗口），全量读数以 CI 同一棵树为准（L4）。

### L4 — CI（权威读数）

见 §5。

---

## §5 CI

| 项 | 值 |
|---|---|
| run id | `40255404546` |
| run number | **#2704** |
| job id | `121356066967` |
| attempt | 1 |
| conclusion | **success** |
| head_sha | `2b5cfe0ba1` — 与本地 HEAD `2b5cfe0ba` 逐字符一致 |
| 时间 | 01:41:00Z → 01:55:28Z（14m28s） |
| 步骤 | 26 步，唯一 `skipped` = **Publish to Releases**（main 门控），其余全 success |
| 日志 | 1,482,493 B（`probe/ci/job-2704.log`） |

日志逐条判读：

| 判据 | 读数 |
|---|---|
| `Summary: 112 passed, 0 failed` | **恰 1** |
| `ALL 344 CASES PASS` | **恰 1** |
| `[112/112]`（门头收尾） | 恰 1，门头编号 1..112 互异 |
| `^ e: `（Kotlin 编译错误） | **0** |
| 含 `FAILED` 的行 | 6 条，**全部**是用例名子串（`… produces FAILED without calling…`、`phase FAILED maps to Failed PASSED`、`terminal FAILED maps to Failed PASSED`、`reason PERSISTENCE_FAILED…`、`reason EXECUTION_FAILED…`、`the fixed tail still reaches a legal FAILED terminal`），无一条是 `FAILED <用例>` |
| 门 03 自测见证 | `✅ tool_result_image_branch_check selftest has teeth (missing provider package is a hard failure)` — **1** |
| 门 09 自测见证 | `✅ provider_write_rules_check selftest has teeth (PROVIDER_WRITE_RULES_SELFTEST=PASS)` — **1** |

即：两门的新判据在 CI 的 Step 3（pre-build scan gate）里跑过 112 门全量，两门的自测臂在 CI 里有独立断言，全量夹具 344 例全绿。

**停止节点达成：分支 CI 绿。未合并 main。**

---

## §6 残留（R1..R9，均 P2）

| # | 缺口 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_inside_condition` 只把 `if` / `when(subject)` 的条件当「测试」，`while (…)` / `for (…; cond; …)` 的条件仍算消费 | 某见证把唯一 `imageData` 读挪进 `while` 条件 | 扩到 `while` / `do-while` / `for` 条件 span | provider 树里没有把图像读放循环条件的写法；方向是「少判死」（偏绿），非误红 |
| R2 | `stmt_is_exit` 只折 `if (<const true>) <exit>`，不折 `if (false) A else return@let` | 见证用恒假 if + 退出式 else 截断块体 | `const_bool` 为 False 时取 else span 再判退出 | 与 R1 同族、同方向；现有 12 条见证无一使用该形态 |
| R3 | `_fold_const_if` 按深度 0 的字面 `else` 切分，标识符含 `else`（如 `elsewhere`）会误切 | 常量 if 的 then 分支后紧跟 `elsewhere` 之类标识符 | 切分前加词边界校验 | 本仓无此标识符；误切方向是「判红」（噪声），不会静默放绿 |
| R4 | `_derives_from_normalize` 只跟 2 跳局部绑定 | 正当重构引入 `a → b → normalize(...)` 三级链 | 改为不动点迭代 | 目前最深一跳；方向是误红，可见即修 |
| R5 | `_is_media_carrier` 按名字含 `audio`/`image` 分类哪些 carrier 要判「读得是否被消费」 | 谓词新增语义化命名的媒体臂（如 `attachments.isNotEmpty()`） | 改为从写图像的块反推 carrier 集合 | 现有臂只有 `audioParts` / `imageParts`；命名是分类锚，不是判据锚 |
| R6 | 门 03 `_exclusive_media_split` 只在「两分支各自载纯」时判互斥，混合分支可能逃逸 | 见证在两个分支里都塞额外写入 | 改成逐 carrier 的 partition 分析 | 需先有一条实际逃逸的见证，避免无靶造装置 |
| R7 | 门 03 的稳定标记 `TOOL_RESULT_IMAGE_BRANCH_SELFTEST=PASS (34 arms)` **未**在 `test_scan.py` 注册（本支冻结该文件） | 主会话收口注册时 | 按 §7 加一行断言 | 硬约束 #2：`test_scan.py` 本支禁改；当前 CI 仍断言旧标记（同样有效） |
| R8 | 门 09 W12 锚 `supportsCustomBaseURL` 属性名（拼写锚） | 该能力属性改名/下沉到别处 | 从 `ProviderType` 声明派生能力字段 | 改名属架构变更，非静默漂移；现在误红可见 |
| R9 | `chain_feasible` 跳过合取项里的析取（保守） | 常量短路藏在 `(a \|\| b) && c` 里 | 做分配律展开后再判区间 | 方向偏绿；12 条见证无一使用 |

---

## §7 `test_scan.py` 注册项（交主会话统一注册，本支未改该文件）

现状（CI 已生效）：

```python
("tool_result_image_branch_check", "missing provider package is a hard failure", (REPO_ROOT,)),
("provider_write_rules_check", "PROVIDER_WRITE_RULES_SELFTEST=PASS", (), (REPO_ROOT,)),
```

建议新增/替换（臂数进断言，防「门被掏空成 sys.exit(0) 仍绿」）：

```python
# 门 03：换成带臂数的稳定标记（旧标记仍打印，可保留两条）
("tool_result_image_branch_check", "TOOL_RESULT_IMAGE_BRANCH_SELFTEST=PASS (34 arms)", (REPO_ROOT,)),
# 门 09：在既有标记后加一行臂数断言
("provider_write_rules_check", "PROVIDER_WRITE_RULES_SELFTEST_ARMS=30", (), (REPO_ROOT,)),
```

（门 09 的 `PROVIDER_WRITE_RULES_SELFTEST=PASS` 保持原样即可；新增的 `…_ARMS=30` 行是本支新打印的稳定行。）

---

## §8 复现命令

```sh
# 0) 环境（worktree / 分支）
cd /var/minis/shared/work/meta-audit-v5/batch6/wt6/t09      # 分支 fix/b6-toolimg-provwrite，基线 e145ec961

# 1) 共享原语自测（未改动）
python3 scripts/scan/_kotlin_lexer.py --selftest        # 20 arms
python3 scripts/scan/_kotlin_ast.py --selftest          # 32 arms
python3 scripts/scan/_kotlin_valueflow.py               # 26 arms

# 2) 两门：真树 + 自测
python3 scripts/scan/tool_result_image_branch_check.py .
python3 scripts/scan/tool_result_image_branch_check.py --selftest     # 34 arms
python3 scripts/scan/provider_write_rules_check.py .
python3 scripts/scan/provider_write_rules_check.py --selftest         # 30 arms

# 3) 反攻（12 条见证，必须 12/12 CAUGHT）
cd /var/minis/shared/work/meta-audit-v5/batch6
python3 verify_batch5.py --witnesses witnesses-A.json --repo wt6/t09 --mode full \
    --only tool_result_image_branch_check.py --out probe/t09-post-toolimg.json
python3 verify_batch5.py --witnesses witnesses-A.json --repo wt6/t09 --mode full \
    --only provider_write_rules_check.py     --out probe/t09-post-provwrite.json
cd wt6/t09 && git status --porcelain src/                # 必须为空

# 4) 邻门抽检
for g in provider_rpc_symmetry_check model_entry_override_parity_check \
         memory_gate_provider_fields_check webapp_asset_url_cleanup_check \
         i18n_check file_preview_url_encode_check; do
  python3 scripts/scan/$g.py . >/dev/null; echo "$g rc=$?"; done

# 5) 全量（本机沙箱 S28 窗口内不可行 → 交给 CI 同一棵树）
bash scripts/scan/scan.sh && python3 scripts/scan/test_scan.py   # CI Step 3 已跑：112/0 + ALL 344

# 6) CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
    --workflow build-apk.yml --ref fix/b6-toolimg-provwrite
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/b6-toolimg-provwrite
```

---

**一句话**：两门 12 条见证（门 03 六条 family-hole/dataflow，门 09 三条 dataflow + 一条 anchor + 两条 completeness）全部由「token 存在」改判为「值/位置/可达性达成」，反攻 **12/12 CAUGHT**（restore 12/12），自测 34 + 30 臂全绿，分支 CI **#2704 / run 40255404546 / head_sha 2b5cfe0ba1 success**（112/0 + ALL 344 + 两门自测标记均被断言），残留 9 条 P2；**停在分支，未合并 main**。
