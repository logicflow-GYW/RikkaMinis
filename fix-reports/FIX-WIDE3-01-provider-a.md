# FIX-WIDE3-01 · provider-a：4 道 provider 门判据加固（14 条见证固化为负向臂）

- 分支：`fix/meta-wide3-01-provider-a`
- 基线：`6b6c918fae6c338e3091d0d3870af3cad9e2b881`（main）
- 范围：**只改 4 个门脚本**，生产 `.kt` 零改动、`scan.sh` / `test_scan.py` 零触碰、不新增门
- 见证：14 条（外部对抗审计产出，主会话机械复证 14/14 成立）→ 加固后反攻 **14/14 CAUGHT**

| 门 | 见证数 | 加固前后 rc（打见证补丁） |
|---|---|---|
| `anthropic_thinking_budget_check` | 4 | 0（瞎）→ 1（抓住） |
| `gemini_base_version_check` | 4 | 0（瞎）→ 1（抓住） |
| `model_entry_override_parity_check` | 3 | 0（瞎）→ 1（抓住） |
| `tool_result_image_branch_check` | 3 | 0（瞎）→ 1（抓住） |

---

## ① 门与判据

### 1. `anthropic_thinking_budget_check`（C1 / C2 / C3 / C-OAI-1 / C-OAI-2）

原判据（三条都在问「某物存在」）：

- C2：`budget_tokens` 写入点的**外围 `if` 条件文本里**同时出现 `budgetTokens > 0` 与 `budgetTokens < <ident>` 两个 token。
- C3：`has("thinking")` 所在函数里出现 `"disabled"` 的比较或 `"disabled" ->` 分支。
- C-OAI-2：存在条件含 `< 2`、块体含 `= 0` 的 `if`，且其外围**brace-bodied `if`** 不含 `maxTokens > 0`。

新判据（三条都改成「这个决定真的生效」）：

- **C2**：守卫必须**活着**——条件里裸 `true`/`false` 作为 `&&`/`||` 操作数即判红（`a && (true || b)` 保留全部锚点、删掉决定）；`enclosing_ifs` → `enclosing_conditions`（新增 `when { … -> }` 臂 与 braceless `if (x) if (y) {…}` 链）。
- **C3**：`"disabled"` 分支**不得宣布 thinking beta**（`disabled_path_adds_beta`：解析 `"disabled" ->` 的臂体，或 `if (type == "disabled") {…}` 的块体，出现 `.add(` / `interleaved-thinking` / `effort-` 即红）。
- **C-OAI-2**：三段——(a) 守卫条件不得被布尔字面量短路；(b) **末次写赢**（守卫块内对同一变量的最后一次赋值必须是 `0`，堵 `budget = 0; budget = 4096`）；(c) 可达性用 `enclosing_conditions`（含 `when` 臂 + braceless if 链）。

### 2. `gemini_base_version_check`（C1 / C2 / C3 / C5）

- **C1/C2（normalizer）**：原来 `analyse(body)` 只问 body 里**有没有** `removeSuffix("/v1beta")` / `removeSuffix("/v1")` 和「剥掉 strip 调用后是否还残留 `/v1beta`」。
  新增第 4 个 piece —— **返回值本身必须带回版本段**（`returned_value_restores`）：对每个 depth-0 `return`（表达式体函数则整个 body 就是返回值）取**顶层链**（`.also { … }` 的 lambda 体从链上剥掉，因为 `also` 返回 receiver），要求链上出现「以 `/v1beta` 结尾的字面量 / `+ "/v1beta"`」，或裸标识符一跳解析到同样的赋值/常量。
  另加 `DEAD_CODE_RE`：`if (false)` / `while (false)` 出现在 normalizer body 即红。
- **C3（调用点）**：原来数 `normalizedBasePath()` 出现次数 ≥ 2，并匹配字面量 `"${basePath}/models/"`。
  新增 `check_url_routing`：从**字符串字面量**派生每个 `:generateContent` / `:streamGenerateContent` URL，取 `/models/` 前那个插值作为 base，要求它是 `normalizedBasePath()` 内联调用，或同函数内绑到它的局部；`$basePath` 与 `${basePath}` 两种拼写同等判红。sites < 2 时 fail-closed。
- **C5（probe arm）**：`endsWith("/v1")` 必须落在**活的** `if` 条件里（`_live_guard_testing`）。

### 3. `model_entry_override_parity_check`（Arm 1 / 2 / 4）

- **Arm 1（Save 块）**：`save_block` 的右边界原来**停在** `val updated = if (entry.isCustom) {`，把真正被持久化的两个 `entry.copy(...)` 关在审查区之外。现在块延伸到该值表达式结束（then + else 臂）并穿过紧随的 `updateEntry(...)`；新增 `check_save_persists_derived`：每个 `entry.copy(...` 必须 `overrides = newOverrides`，且 `updateEntry(<id>)` 消费的必须是那个被绑定的值。
- **Arm 2（builder）**：新增 `check_builder_derives` —— 函数体内任何布尔字面量守卫（`if (true) return previous`）即红，且**最后一次 `return` 必须是 `previous.copy(...)` 的值**。
- **Arm 4b（funnel）**：`"ModelsDevApi.effectiveModel(" in funnel` → `_funnel_returns_effective`：表达式体函数**返回的就是**该调用（不是字面量门控的 `if (false) … else entry.model`），块体必须 `return … ModelsDevApi.effectiveModel(`。
- 顺带修 `code_only`：原来只剥 `//`，`/* … */` 注释掉的 `previous.copy(...)` 仍算存在 → 现在块注释也惰化（字面量感知）。

### 4. `tool_result_image_branch_check`（C2 / C5-C6 / C-IMG-1）

- **C2（ToolResult 分支）**：`IMAGE_FIELD in block` 之外，新增 `dead_guard_in(block, "imageData")` —— 只要 `imageData` 出现在被布尔字面量短路的 `if` 条件里（`if (false && part.imageData != null …)`）即红：这是「拼写保留、决定删除」，图像路径变成死码。
- **C5/C6（payload predicate）**：三个媒体臂的 `alive` 检查之外，新增谓词 body 的**活性**判据——body 里出现布尔字面量操作数即红（`(false && (audioParts… || imageParts… || …))`）。
- **C-IMG-1（carrier 派生）**：`predicate_carrier_arms` 原来用 `find("\n\n")` 当函数体右边界 → 空行后的载体臂直接消失。新增 `payload_predicate_body`：先配平形参括号，再按 `=` / `{` 取体，表达式用 `_expr_span`（换行只在「上一个非空字符不是续行运算符」时才结束语句）。

---

## ② 失效轴

| 轴 | 条数 | 具体形态 |
|---|---|---|
| dataflow | 9 | 守卫被 `true \|\|` / `false &&` 短路（C2、C-OAI-2、C5、Arm1、Arm2、Arm4、C2-branch、C5/C6）；`"disabled" -> betaFlags.add(...)`（比较存在、动作反向）；返回值被换成裸参数（C2 gemini） |
| parser-death | 4 | `when { … -> }` 臂包裹（C-OAI-2）；braceless `if (x) if (y)` 链；表达式体函数只取单行（gemini C1）；`\n\n` 当函数体边界（C-IMG-1） |
| spelling | 1 | Kotlin `"$basePath/models/"` 与 `"${basePath}/models/"` 等价，字面量判据只认后者（gemini C3） |

一句话总结：**9/14 是「锚点还在、决定没了」**——上一波（wide-1024）修掉的「存在性判据」在布尔短路这一形态上整体复发；这一波把 `literal_decides` / `enclosing_conditions` / `returned_value_restores` 三类**决定点判据**写进门里。

---

## ③ 逐条见证（旧判据为何瞎 → 新判据形态）

| # | 门 / 见证 | 轴 | 旧判据为何绿 | 新判据 |
|---|---|---|---|---|
| 1 | anthropic C2 | dataflow | `gt`/`lt` 两个 token 都仍在 `a && (true \|\| b)` 里 | 条件过滤掉布尔字面量短路后再判两侧守卫（`live` 为空即红） |
| 2 | anthropic C3 | dataflow | 只查 `"disabled"` 是否被比较，不看臂体动作 | `disabled_path_adds_beta`：disabled 臂/分支不得 `.add(` beta |
| 3 | anthropic C-OAI-2 | dataflow | 只查外层是否 `maxTokens > 0`，不看条件是否被 `false &&` 短路 | `literal_decides(cond)` + 末次写赢 |
| 4 | anthropic C-OAI-2 | parser-death | `enclosing_ifs` 只认 `if (`，`when { … -> }` 与 braceless 链不可见 | `enclosing_conditions`：when 臂 + braceless if 链 |
| 5 | gemini C2 | dataflow | `restores_version` 只问 residue 里有没有 `/v1beta`（死局部也算） | `returned_value_restores`：返回值顶层链必须以 `/v1beta` 结尾 |
| 6 | gemini C3 | spelling | 调次数 + `"${basePath}/models/"` 字面量 | 从字面量派生 base 插值，要求它解析到 `normalizedBasePath()`（两种拼写同等判红） |
| 7 | gemini C5 | dataflow | `'endsWith("/v1")' in arm` | `_live_guard_testing`：必须有活的 `if` 条件测试它 |
| 8 | gemini C1 | parser-death | 表达式体只取 `=` 后**单行**，`.also { while (false) {…} }` 四项指标全真 | 表达式跨行取全 + 返回值链判据 + `while (false)` 死码判据 |
| 9 | model_entry Arm 1 | dataflow | 切片在 `val updated = if (…) {` 处截断，`entry.copy` 不在审查区 | 块延伸到 persist 调用 + 每个 copy 必须 `overrides = newOverrides` |
| 10 | model_entry Arm 2 | dataflow | 5 个锚点全是子串存在性检查 | 布尔字面量守卫即红 + 末次 return 必须是 `previous.copy(...)` |
| 11 | model_entry Arm 4 | dataflow | 只查 `"ModelsDevApi.effectiveModel(" in funnel` | `_funnel_returns_effective`：返回的必须是该调用 |
| 12 | tool_result C2 | dataflow | `imageData in block`（连注释都没剥） | `dead_guard_in`：被字面量短路的 image 条件即红 |
| 13 | tool_result C5/C6 | dataflow | 三个臂 token 都在（`(false && (… ))` 也满足） | 谓词 body 布尔活性判据 |
| 14 | tool_result C-IMG-1 | parser-death | 函数体用 `find("\n\n")` 切，空行后载体消失 | `payload_predicate_body`：括号配平 + 续行感知的表达式边界 |

---

## ④ 修法（代码级）

全部改动落在 4 个门脚本内（`git diff --stat`：4 files, +1135 / −102），零生产代码：

1. **共享判据原语**（每门各自实现，互不依赖——门之间不能互相 import）：
   - `literal_decides(cond)` / `BOOL_OP_LITERAL`：裸 `true`/`false` 作为 `&&`/`||` 操作数。
   - `enclosing_conditions(text, pos)`（anthropic）：brace-bodied `if` + `when { … -> }` 臂 + braceless `if (x) if (y) {…}`。
   - `returned_value_restores(body, src)`（gemini）：`_top_level_returns` + `_chain_only`（剥 `.also { }` lambda 体）+ 一跳标识符解析。
   - `check_url_routing(src)`（gemini）：`_string_literals` + `BASE_INTERP_RE`（`${expr}/models/` / `$ident/models/`）+ `enclosing_function`。
   - `check_save_persists_derived`（model_entry）：对 `entry.copy(` 做**实参级**判定（括号配平取整），不接受 `startswith` 前缀。
   - `dead_guard_in` / `payload_predicate_body`（tool_result）。

2. **解析器本体修复**（parser-death 三处）：
   - gemini `strip_comments` 改成**字面量感知**（原来会把 `"https://…"` 从 `//` 处吃掉半行，`DEFAULT_BASE` 的 `/v1beta` 因此不可验证）；`function_body` 用 `expr_span` 取表达式体（跨行、跨空行）。
   - tool_result `predicate_carrier_arms` 改用 `payload_predicate_body`（`\n\n` 边界删除）。
   - model_entry `save_block` 右边界改为**结构派生**（值表达式 + persist 调用），不再写死 marker 的前缀。

3. **fixture 形态**：14 条见证逐一固化——anthropic +6 臂（含 4 条 C-OAI-2），gemini 15 → 19 臂，model_entry 15 → 18 例，tool_result 17 → 20 项。每条臂都断言「恰好只有该判据红」，并保留 test_scan 冻结的收尾标记：
   - `ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS`
   - `MODEL_ENTRY_OVERRIDE_SELFTEST=PASS`
   - `missing provider package is a hard failure, not a green skip`
   - gemini 收尾行 `GEMINI_BASE_VERSION_SELFTEST=PASS (19 arms)`

---

## ⑤ 四层证据

### L1 本门真树绿 + 自证臂全过

```
$ for g in anthropic_thinking_budget_check gemini_base_version_check \
           model_entry_override_parity_check tool_result_image_branch_check; do
     python3 scripts/scan/$g.py . >/dev/null; echo "$g rc=$?"; done
anthropic_thinking_budget_check rc=0
gemini_base_version_check rc=0
model_entry_override_parity_check rc=0
tool_result_image_branch_check rc=0

$ python3 scripts/scan/anthropic_thinking_budget_check.py --selftest
ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS           # 9 个 C1/C2/C3 夹具 + 12 个 C-OAI 夹具
$ python3 scripts/scan/gemini_base_version_check.py --selftest | tail -1
GEMINI_BASE_VERSION_SELFTEST=PASS (19 arms)
$ python3 scripts/scan/model_entry_override_parity_check.py --selftest | tail -1
MODEL_ENTRY_OVERRIDE_SELFTEST=PASS                # 18 例
$ python3 scripts/scan/tool_result_image_branch_check.py --selftest | grep -c '\[OK\]'
20
```

真树输出同时确认「判据没有误红合法形态」——例如 gemini 门打印
`restore /v1beta in the returned value=True`、`GeminiProvider routes 2 models URL(s) through the normalizer`。

### L2 外部见证反攻（14/14 CAUGHT）

```
$ python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" --gates \
    anthropic_thinking_budget_check.py,gemini_base_version_check.py,\
model_entry_override_parity_check.py,tool_result_image_branch_check.py --json /tmp/reattack-01.json
anthropic_thinking_budget_check.py  C2      CAUGHT rc=1  dataflow
anthropic_thinking_budget_check.py  C3      CAUGHT rc=1  dataflow
anthropic_thinking_budget_check.py  C-OAI-2 CAUGHT rc=1  dataflow
anthropic_thinking_budget_check.py  C-OAI-2 CAUGHT rc=1  parser-death
gemini_base_version_check.py        C2      CAUGHT rc=1  dataflow
gemini_base_version_check.py        C3      CAUGHT rc=1  spelling
gemini_base_version_check.py        C5      CAUGHT rc=1  dataflow
gemini_base_version_check.py        C1      CAUGHT rc=1  parser-death
model_entry_override_parity_check.py Arm 1  CAUGHT rc=1  dataflow
model_entry_override_parity_check.py Arm 2  CAUGHT rc=1  dataflow
model_entry_override_parity_check.py Arm 4  CAUGHT rc=1  dataflow
tool_result_image_branch_check.py   C2      CAUGHT rc=1  dataflow
tool_result_image_branch_check.py   C5 / C6 CAUGHT rc=1  dataflow
tool_result_image_branch_check.py   C-IMG-1 CAUGHT rc=1  parser-death
CAUGHT=14
RESULT: ALL CAUGHT
```

反攻前同一批见证在本支基线上是 `rc=0`（主会话已机械复证 14/14 打得上、门全瞎）；打补丁 → 现在 rc=1，且**恢复后真树仍 rc=0**（`reattack.py` 逐条 restore 并校验）。

### L3 邻门 rc=0（同族门没被牵连）

```
file_provider_paths_coverage_check rc=0   provider_boundary_guard rc=0
provider_import_id_roundtrip_check rc=0   provider_models_cache_wiring_check rc=0
provider_rpc_symmetry_check rc=0          provider_type_registry_check rc=0
thinking_wiring_guard rc=0                thinking_rule_editor_preserve_check rc=0
models_provider_field_check rc=0          memory_gate_provider_fields_check rc=0
```

### L4 全量

- **111 道 python 门：全绿**。经 `gate_run.sh --full`（持锁）跑到 96/111 时被沙箱 `ENOSYS` 中断（见 §7 R4），逐块复核方式补齐剩余 15 道：
  `subagent_handback` / `sysinfo_state_thread` / `thinking_wiring_guard` / `tool_title_error_branch` / `trace_eval` / `trace_step_tag` / `truncated_call_guard` / `tts_voice_and_sanitizer`（只吃 `--root`）/ `turn_tail_finalize_order` / `voice_cancel_guard` / `webapp_asset_url_cleanup` / `webview_holder_url_source` / `xproc_boundary` / `thinking_rule_editor_preserve` / `tool_result_image_branch` —— 全部 `rc=0`，无一红。
- **`test_scan.py`（344 用例）**：本地两轮均被沙箱 `OSError: [Errno 38] Function not implemented` 打断（12 路并行会话争用，见 §7 R4），**未拿到本地 ALL 344 读数**；冻结面未改动（`git diff --name-only` 只含 4 个门脚本），该套件由分支 CI 权威执行，CI 日志中的 `ALL … CASES PASS` 为准（见 §6）。

---

## ⑥ CI run

（推送后回填：run id / head_sha / 结论 / 日志判据）

---

## ⑦ 残留三件套

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `literal_decides` 只认裸 `true`/`false` 作 `&&`/`\|\|` 操作数；`if (1 == 1 && guard)`、`if (list.isNotEmpty() \|\| guard)` 之类「恒真但非字面量」的短路仍能过 | 有人用非字面量的恒真条件包裹守卫（如 `if (true.also{} && …)`、`if (Boolean.TRUE && …)`） | 扩成「条件里出现对参数的零引用 + 常量谓词」的判定（需要轻量表达式求值），或在 CI 侧加变异测试 | 当前 14 条见证全是字面量形态；扩判定会把「合法但看起来恒真」的重构误红，收益未验证 |
| R2 | `dead_guard_in` / `literal_decides` 只看 `&&`/`\|\|`；`when (true) { … }`、`if (x == x)` 形态未覆盖 | 出现以 `when(true)` 包裹 image 编码 / payload 谓词的写法 | 把 `when` 常量主体并入 `dead_guard_in`（tool_result）与 `enclosing_conditions`（anthropic） | 见证未覆盖该形态；`when(true)` 在 Kotlin 里几乎只出现在生成代码中 |
| R3 | gemini `returned_value_restores` 的标识符解析是**一跳**（`val/var/const val` 同文件），变量经两次中间赋值再返回（`val a = strip(x); val b = a; return b`）会误红 | 有人把 normalizer 拆成多步局部变量再返回 | 改成本文件赋值图的 BFS（深度 ≤3，`_assignments` 已在手，加一层递归即可） | 现网形态是单步；误红方向安全（红不是绿），且会逼人显式改门 |
| R4 | 沙箱 S28：12 路会话并行时 `waitpid`/`mkdir`/`flock` 随机 `ENOSYS`，导致 `gate_run.sh --full` 与 `test_scan.py` 中途断 | 同机 ≥8 个并行 agent 同时跑门 | 收口期错峰（等并行支收工）或串行小批量；CI 是权威读数 | 这是环境缺陷不是门缺陷，改动门脚本无法规避；已在 `/var/minis/shared/sandbox-limits.md` 登记（S28） |

---

## ⑧ 复现命令

```sh
cd /var/minis/shared/work/meta-wide3/wt-01      # 分支 fix/meta-wide3-01-provider-a @ 6b6c918f + 本支提交
for g in anthropic_thinking_budget_check gemini_base_version_check \
         model_entry_override_parity_check tool_result_image_branch_check; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?"
  python3 scripts/scan/$g.py --selftest ; echo "selftest rc=$?"
done

python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD" --gates \
  anthropic_thinking_budget_check.py,gemini_base_version_check.py,\
model_entry_override_parity_check.py,tool_result_image_branch_check.py

# 全量（持锁；ENOSYS 时重试）
sh /var/minis/shared/tools/gate_run.sh --full --repo "$PWD"
python3 scripts/scan/test_scan.py

# 分支 CI（非 main 分支必须手动 dispatch）
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch \
  --ref fix/meta-wide3-01-provider-a
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs \
  --branch fix/meta-wide3-01-provider-a
```
