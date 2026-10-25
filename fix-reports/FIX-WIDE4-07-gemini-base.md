# FIX-WIDE4-07 · gemini_base_version_check 判据加固

支：`fix/wide4-07-gemini-base`　基线：`19e70dab`（wide3 并集）+ 共享模块 `dbab7a3e`
门脚本：`scripts/scan/gemini_base_version_check.py`（唯一改动文件）
生产代码改动：**0 行**　`scan.sh` / `test_scan.py`：**0 字节**　门数 112 / 夹具 344：**不变**

## ① 门与轴

| 门 | 被证伪的判据 | 轴 | 见证数 | 修复后 |
|---|---|---|---|---|
| `gemini_base_version_check` | `returned_value_restores / _chain_only` | dataflow（值形态） | 1（+2 同族拼写自证） | CAUGHT |
| 同上 | `_live_guard_testing` | semantic（守卫活性） | 1（+1 分支形态） | CAUGHT |
| 同上 | `check_url_routing` | dataflow（绑定数据流） | 1（+1 同族拼写） | CAUGHT |
| 同上 | `function_body / SHARED_DELEGATE` | dataflow（委托结果消费） | 1 | CAUGHT |
| 同上 | `strip_comments / _top_level_returns` | parser-death（词法投影） | 1 | CAUGHT |

共性（与本波其余支一致）：五条判据全都锚「某物**存在**」——返回值里出现 `/v1beta` 字样、
条件里出现 `endsWith("/v1")`、绑定行里出现 `normalizedBasePath(`、函数体里出现
`GeminiBasePath.normalize`、注释剥除器能跑完。存在 ≠ 生效：**值被后续运算符改写、守卫被常量
短路、绑定走死分支、委托结果被 `.let` 丢弃、注释边界认错把真代码藏起来**，五种形态各自把
「存在」与「生效」掰开。补法统一为 **把判据从「文本出现过」改成「值从哪来、往哪去」**，
并对每种决策点做**活性求值**（常量折叠 / 分支形态 / 写点数 / 尾链算子）。

## ② 逐条见证：原补丁 → 旧判据为何瞎 → 新判据 → 新臂

### W1 `returned_value_restores / _chain_only`（dataflow）

原补丁（`GeminiBasePath.kt`）：

```kotlin
        return stripped.takeIf { true } ?: "$stripped/v1beta"
```

- **旧判据为何瞎**：`_chain_only` 只剔除 `{...}`，`?:` 两侧文本原样保留，于是 chain 末尾仍是
  `?: "$stripped/v1beta"` → `RESTORE_SUFFIX_RE = /v1beta"\s*$` 命中 → 判绿。运行时
  `stripped.takeIf { true }` 恒返回非空的 `stripped`（不含版本段），Elvis 右侧是死分支。
- **新判据**：`returned_value_restores(body, body_code, src)` 改为**正形态断言**——
  顶层 return 链必须是 `"$x/v1beta"` / `x + "/v1beta"` / 其常量值以 `/v1beta"` 结尾的标识符；
  且链中**不得**出现决策算子 `_DECIDER_RE = ?: | if | when | .let | .run | .with | .takeIf |
  .takeUnless`（`.also`/`.apply` 白名单：二者返回 receiver）。结构在 `code` 视图上判（字符串内容
  抹空，`"if"` 字样不算决策），值形态在字面量保留视图上判（同长对齐）。
  `_top_level_return_spans(code)` 取代旧的 `_top_level_returns(body)`：字符串里的
  `return`/括号不再参与配平；**没有顶层 return 时整段 body 当作返回值判**（丢 return 的
  块体不再被当成「无可检查」跳过）。
- **新臂**：`w4: a restore kept behind a dead Elvis right side is caught`

同族拼写（self_doubt 反打，各一条臂）：
`return if (1 == 1) stripped else "$stripped/v1beta"` → `w4: … constant if/else …`；
`return "$stripped/v1beta".let { stripped }` → `w4: … trailing .let …`。

### W2 `_live_guard_testing`（semantic）

原补丁（`ProviderMutationMethods.kt` 的 anthropic 探测臂）：

```kotlin
            if ((1 == 2) && baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
```

- **旧判据为何瞎**：旧实现用正则 `BOOL_OP_LITERAL` 找与 `&&`/`||` 相邻的 `true|false`
  字样；`(1 == 2)` 里没有任何 `true`/`false`，于是判活。且旧判据**只看条件**，两个分支各自
  拼出什么 URL 从不检查（self_doubt：把 then/else 对调同样放行）。
- **新判据**：`_live_guard_testing(arm, needle)` 两道都做——
  1. **常量折叠**：条件交给共享 `_kotlin_ast.const_bool()`（字面量 / `!X` / `X && Y` / `X || Y` /
     `A == B` / `A != B` / 括号），折叠出任何布尔值即「守卫不活」（`(1 == 2) &&`、`!true &&`、
     `0 == 1 &&` 全覆盖）；
  2. **分支形态**：`_if_parts` 用 `code` 视图求 `if (...)` 的括号跨度，再 `_split_else` 切
     顶层 `else`，两条臂各自 `_squash` 后必须精确等于 `"$baseURL/models"` / `"$baseURL/v1/models"`，
     条件以 `!` 开头则两臂对调。
- **新臂**：`w4: the /v1 guard short-circuited by a constant comparison is caught`、
  `w4: the /v1 guard with its branches swapped is caught`。

### W3 `check_url_routing`（dataflow）

原补丁（`GeminiProvider.sendMessage`）：

```kotlin
        val b = if (false) normalizedBasePath() else basePath
        val url = "$b/models/${model.id}:generateContent?key=$apiKey"
```

- **旧判据为何瞎**：绑定判据是单行正则 `val b = …[^\n]*normalizedBasePath\(`，只要**同一行**
  出现该子串就放行——死分支、`?:`、`.let { basePath }` 全部满足。
- **新判据**：`_binding_rhs(fn, name)` 用 `expr_span` 取**整个初始化式**（跨行），
  `_value_is_call(rhs, code_view(rhs), "normalizedBasePath")` 要求值就是该调用（前缀为空、
  闭合括号后无尾链、无决策算子），并要求**该名字在函数内只有一个写点**（二次赋值同样不过）。
  直插值 `"${normalizedBasePath()}/…"` 也补了决策算子判据。
- **新臂**：`w4: a models URL on a dead-branch normalizer binding is caught`、
  `w4: a models URL on a .let-discarded normalizer binding is caught`。

### W4 `function_body / SHARED_DELEGATE`（dataflow）

原补丁（`GeminiProvider.kt`）：

```kotlin
    private fun normalizedBasePath(): String = GeminiBasePath.normalize(basePath).let { basePath }
```

- **旧判据为何瞎**：`main()` 里 `if SHARED_DELEGATE in body: results[fname] = True; continue`
  ——**委托是免检通道**，`returned_value_restores` / `_chain_only` 根本没跑。
- **新判据**：委托不再免检。`_delegation_returns_value(body, body_code)` 对每条顶层 return 跑
  `_value_is_call(..., SHARED_DELEGATE)`：值必须是该调用的结果（闭合括号后只剩
  `.also`/`.apply` 这类 receiver-preserving 尾链）。同一判据推广到另外三处委托站点：
  兄弟 `GeminiModelsApi.normalizeBase`、语音 `GeminiVoiceProvider.buildVoiceOutputRequest`
  （`_voice_delegates`：委托结果必须**绑到名字且该名字被 URL 插值消费**）、gemini 探测臂
  （`_gemini_probe_delegates`：`${...}` 里必须是裸调用）。
- **新臂**：`w4: a delegating normalizer that returns the raw base is caught`

### W5 `strip_comments / _top_level_returns`（parser-death）

原补丁（`GeminiBasePath.kt`）：

```kotlin
        /* outer /* inner */ if (1 == 2) return "$stripped/v1beta"
        val _f = { // */
        return stripped
        /* outer2 /* inner2 */ } // */
```

- **旧判据为何瞎**：手写注释剥除器在**第一个** `*/` 退出，Kotlin 的块注释是可嵌套的 ⇒ 剩下的
  `return "$stripped/v1beta"` 被当成顶层 return，真正的 `return stripped` 落进 `{` 一层深度里，
  被判绿。
- **新判据**：注释投影整体换成共享 `_kotlin_lexer.analyse()`（嵌套块注释 / 字符字面量
  `'"'` / 三引号原始字符串 / `${...}` 模板），并**等长**返回三个视图；`code` 视图用于结构、
  字面量保留视图用于值形态。**解析失败 fail-closed**：`LexFailure` 在主循环、URL 判据、
  跨文件判据、语音判据四处被捕获并报违规（「unlexable source … refusing to judge」），
  不再「剥不出来就当没有」。
- **新臂**：`w4: a normalize body whose restore lives in a nested block comment is caught`、
  对照臂 `w4: a nested block comment that is only commentary stays green`（证明词法器不会
  把 `*/` 前后真代码吞掉）、`w4: an unlexable normalizer fails closed instead of being skipped`。

## ③ 判据改动清单（函数名级）

| 函数 | 动作 | 要点 |
|---|---|---|
| `strip_comments` | 重写 | 委托 `_kotlin_lexer.analyse()["comments"]`；解析失败抛 `LexFailure` |
| `code_view` | 新增 | 等长 `["code"]` 视图（注释 + 字符串内容抹空） |
| `LexFailure` | 新增 | fail-closed 信号，四处调用点捕获并计为违规 |
| `_top_level_returns` → `_top_level_return_spans` | 改写 | 返回**跨度**而非文本（两视图同长对齐）；code 视图判深度；无 return 时整段 body 参与判 |
| `returned_value_restores(body, body_code, src)` | 重写 | 正形态断言 + `_DECIDER_RE`（决策算子即违规）；常量早退要查其值真的带版本段 |
| `_delegation_returns_value` | 新增 | 委托结果必须是返回值本身 |
| `_peel_scope` / `_call_tail` / `_value_is_call` | 新增 | 「值就是这个调用」的统一判据（收尾链白名单 `.also`/`.apply`） |
| `_guard_conditions` → `_if_parts` / `_split_else` / `_squash` | 重写 | code 视图求括号跨度、文本视图取值；顶层 `else` 切分；字符串内空白不动 |
| `_live_guard_testing` | 重写 | `const_bool` 折叠 + 两臂 URL 形态（`(found, live, detail)`） |
| `check_url_routing` / `_binding_rhs` | 重写 | 整初始化式 + 单写点 + `_value_is_call` |
| `_gemini_probe_delegates` / `_voice_delegates` | 新增 | 委托结果被消费（插值 / 绑定后插值） |
| `check_cross_file_sites` | 加固 | 兄弟门与语音站点的委托判据接上；三处失效消息带具体值 |
| `main()` | 加固 | 委托分支走 `_delegation_returns_value`；四类词法失败 fail-closed |
| `selftest()` | 扩臂 | 19 → **30 臂**（新增 11 条 `w4-` 臂） |
| `_BASE_*` / `_PROVIDER_*` 夹具 | 新增 | 9 个 w4 夹具（含 1 条对照绿臂、1 条 unlexable 臂） |

**冻结面零触碰**：`scan.sh` / `test_scan.py` 一个字节没改；`test_scan.py` 对本门的 18 条断言
（`"delegates to GeminiBasePath.normalize"`、`"routes 2 call site(s)"`、`"raw basePath"`、
`"[FULL]"`、`"[PARTIAL]"`、`"not found"`、三个文件名、`GeminiVoiceProvider`、`providerProbe…`）
全部**逐字保留**，本门只加臂不加串。

## ④ 四层证据

**L1 本门（真树 + 自证臂 + 冻结夹具）**

- `python3 scripts/scan/gemini_base_version_check.py .` → **rc=0**，5 条判据行 + 4/5 跨文件 +
  语音 6 条全部 `[OK]`，收尾 `✅ gemini base version guard: …`。
- `--selftest` → `GEMINI_BASE_VERSION_SELFTEST=PASS (30 arms)`（19 旧臂全绿 + 11 新臂全绿）。
- `test_scan.py` 本门 18 条冻结断言 → 全绿（`GEMINI TEST OK`）。

**L2 反攻复验（把见证打回真树，要求 rc 翻转）**

`probe/reattack.py`（apply → 跑门 → restore → sha256 复核），结果 `probe/reattack.json`：

| # | 见证 | 修复前 rc | 修复后 rc | 判据命中 | restore |
|---|---|---|---|---|---|
| W1 | `takeIf{true} ?: "$stripped/v1beta"` | 0（BLIND） | **1** | restore /v1beta in the returned value | ✔ |
| W1b | `if (1 == 1) stripped else "…/v1beta"` | 0（BLIND） | **1** | 同上 | ✔ |
| W1c | `"$stripped/v1beta".let { stripped }` | 1 | 1 | 同上 | ✔ |
| W2 | `(1 == 2) && endsWith("/v1")` | 0（BLIND） | **1** | short-circuits | ✔ |
| W2b | then/else 对调 | 0（BLIND） | **1** | assemble the wrong URL | ✔ |
| W3 | `val b = if (false) normalizedBasePath() else basePath` | 0（BLIND） | **1** | instead of the normalized base | ✔ |
| W3b | `val b = normalizedBasePath().let { basePath }` | 0（BLIND） | **1** | 同上 | ✔ |
| W4 | `= GeminiBasePath.normalize(basePath).let { basePath }` | 0（BLIND） | **1** | does not restore /v1beta in the returned value | ✔ |
| W5 | 嵌套块注释藏 return | 0（BLIND） | **1** | does not restore /v1beta in the returned value | ✔ |
| C6 | `false &&`（上一轮已 CAUGHT，回归控制） | 1 | 1 | short-circuits | ✔ |

**10/10 CAUGHT，8/10 修复前为 BLIND**；每条 restore 后 sha256 与基线一致
（`probe/reattack-prefix.json` 存修复前读数，`probe/reattack.json` 存修复后读数）。

**L3 邻门（语义相邻 9 道，只读）**

`provider_type_registry_check` · `provider_ua_parity_check` · `provider_write_rules_check` ·
`thinking_wiring_guard` · `anthropic_thinking_budget_check` · `tts_voice_and_sanitizer_check`
（`--root .` 形态）· `voice_cancel_guard_check` · `model_entry_override_parity_check` ·
`provider_rpc_symmetry_check` → **9/9 rc=0**。

**L4 全量**

- 门：**112/112**。110 个 `if python3 scripts/scan/*.py` 块经 `probe/run_gates.sh` 逐个跑
  （`probe/gate-results.txt` 全 `0`），另两块按 scan.sh 原形态单独跑：
  `[2/112] i18n consistency`（内联脚本，`✅ No orphan keys (1582 refs, 1591 defs)`）与
  `[7/112] Native crash-field host test`（`scripts/native/crash_fields_host_test.sh` → `ALL PASS`）。
- 夹具：本门 18 条断言全绿；`test_scan.py` 本地整跑在 181 条用例后被沙箱 S28
  （`OSError: [Errno 38]`，`waitpid`）中断，**以 CI 的单进程全量复跑为权威读数**。

## ⑤ CI run

- 分支：`fix/wide4-07-gemini-base`
- 代码提交：`4fa606d7`（门 + 夹具 + 自证臂）；报告回填后 HEAD `fbfd6643`，
  `git diff --name-only 4fa606d7 HEAD -- . ':(exclude)fix-reports'` = **0 行**（源码树等价）。
- CI：`Build RikkaMinis Android APK` · run **40179534916** · job `121127409107` ·
  **attempt 1 success** · `head_sha 4fa606d7…5926` 与本地逐字符一致 · 26 步、
  唯一 skipped = `Publish to Releases`（main 门控）。
- 日志逐条核（1,481,892 B）：`Summary: 112 passed, 0 failed` **恰一条** ·
  `ALL 344 CASES PASS` **恰一条** · ` e: ` **0** 条 · 门头 `[N/112]` **112 个互异、无缺号** ·
  含 `FAILED` 的 **6 行全是 PASSED 用例名子串**（`produced FAILED without calling provider` 等）·
  本门段落 14 行 `[OK]` + `✅ gemini base version guard: all normalizers restore /v1beta,
  both call sites routed`，无 `[FAIL]`。

## ⑥ 残留（每条带触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_split_else` 只吃**无花括号**的 `if (c) A else B` 形态；brace-bodied 臂（`if (c) { "…models" } else { … }`）或写成 `when` 的版本选择会被判红 | 有人把探测臂改写成 brace-bodied / `when` 形态时门红 | 扩 `_split_else`：分支体先 `_chain_only` 再取块尾表达式；`when` 走 `_kotlin_ast.when_branches` | 方向安全（红而非静默绿），且当前探针形态就是单表达式臂；宽4-04 已用 `when` 覆盖过同族，不重复建装置 |
| R2 | `const_bool` 只折叠 `!/&&/\|\|/==/!=` 与字面量，`1 > 2 &&`、`"".isEmpty() &&` 这类恒假表达式仍判活 | 出现「关系运算符短路」的新见证 | 在 `_kotlin_ast.const_value` 里补 `<`/`>`/`<=`/`>=` 的数值折叠（共享模块改动需主会话统一做） | 共享模块属跨支公共资源，本支只读；且 `(1 == 2)` 已覆盖最自然的写法 |
| R3 | `RESTORE_CONCAT_RE` 只认 `x + "/v1beta"` 与单层调用形态；`buildString { append(x); append("/v1beta") }` 之类会判红 | 有人真的把恢复写成 `buildString`/`String.format` | 扩正形态清单（每加一种要在自证臂里加一条绿臂） | 当前无此写法；判红方向安全 |
| R4 | 决策算子黑名单是**闭集**（`?: if when .let .run .with .takeIf .takeUnless`）；`.use {}`、`.let` 之外的等价改写（如 `run {}` 前缀、`also` 内改可变 receiver）仍有理论缝 | 下一次同族见证用新算子打穿 | 同时收紧：把「正形态断言」扩到「整条表达式必须与 `"$x/v1beta"` 同构」（目前已做到），新增算子只影响诊断文案 | 已从「黑名单 + 后缀」翻成「白名单正形态」，黑名单只用来给失效原因分类，不再决定判绿 |
| R5 | 语音站点要求「委托结果绑到名字且该名字被 `$name` 插值消费」；若改成 `"${normalize(x)}/models…"` 直插值需另判 | 有人把 `val base = …` 内联进模板 | 在 `_voice_delegates` 里加「`${...}` 内是裸委托调用」分支（`_gemini_probe_delegates` 已有同款实现，可复用） | 当前生产代码是绑定形态；两条路径现在**至少一条**会红 |
| R6 | `LexFailure` 会让门在**任何**无法词法投影的文件上转红（含未来合法 Kotlin 写法） | 出现被词法器误判的合法源码 | 先修 `_kotlin_lexer`（共享模块，主会话协调），再复跑本门 | 这是刻意的 fail-closed；词法器当前 20 臂自证覆盖嵌套注释/字符字面量/原始字符串/模板 |

## ⑦ 注册项（主会话并集收口时逐字采用）

本门此前**未**注册 `--selftest` 标记串（`test_scan.py` 里既有的 selftest 注册表无本门条目），
本支**不动冻结面**，只登记需求：

| 门脚本 | selftest 标记串（`--selftest` 收尾行，逐字） | 臂数 |
|---|---|---|
| `gemini_base_version_check.py` | `GEMINI_BASE_VERSION_SELFTEST=PASS (30 arms)` | 30 |

即：在 `test_scan.py` 的 selftest 注册表追加
`("gemini_base_version_check", "GEMINI_BASE_VERSION_SELFTEST=PASS (30 arms)", (REPO_ROOT,))`。
注册后夹具总数将 344 → 345，属**并集阶段**的既有流程（本支不预置）。

## ⑧ 复现步骤

```sh
cd /var/minis/shared/work/meta-wide4/wt-07          # 分支 fix/wide4-07-gemini-base

# 1) 本门 + 自证臂
python3 scripts/scan/gemini_base_version_check.py .
python3 scripts/scan/gemini_base_version_check.py --selftest      # 30 arms

# 2) 反攻复验（每条见证打回真树 → 必须 rc=1 → 自动 restore + sha256 复核）
python3 -u probe/reattack.py                                      # 10/10 CAUGHT
#   probe/reattack.json        修复后读数（gate/criterion/rc/restored）
#   probe/reattack-prefix.json 修复前读数（8/10 BLIND_BEFORE）

# 3) 冻结夹具（本门 18 条断言）
cd scripts/scan && python3 -c "import sys; sys.argv=['x']; sys.path.insert(0,'.'); \
import test_scan as t; t.test_gemini_base_version(); print('GEMINI TEST OK')"

# 4) 全量门（scan.sh 在 S28 窗口里会被杀；用 shell 分块驱动复现同一判读）
cd ../../.. && grep -o 'scripts/scan/[a-z0-9_]*\.py' scripts/scan/scan.sh \
  | sed 's#scripts/scan/##' | awk '!seen[$0]++' > probe/gates.txt
sh probe/run_gates.sh 1 40 && sh probe/run_gates.sh 41 80 && sh probe/run_gates.sh 81 110
#   余下两块按 scan.sh 原形态跑：内联 i18n 脚本 与 scripts/native/crash_fields_host_test.sh
```

## 附：本支踩到的沙箱坑（供后续支复用）

1. **S28 并发窗口下 `subprocess.run` 的 `waitpid` 会抛 `OSError: [Errno 38]`**——python 驱动
   全量门必然半途而死，**shell 循环不受影响**（`probe/run_gates.sh` 分块跑通 110 道）。
2. **`scan.sh` 整跑会被杀在 ~5–18 道之间**（进程组被回收），日志残缺但 `probe/gate-results.txt`
   可续跑；判读以「逐门 rc」而非 scan.sh 的 Summary 行为准（CI 里 Summary 行仍是权威）。
3. **突变中途被杀会把工作树留在变异态**：本次 `git checkout -- src/` 救回一次；反攻脚本一律
   `finally: 写回原文` + 事后 `git status --porcelain` 复核。
4. `tts_voice_and_sanitizer_check.py` 只吃 `--root .`（位置参数返回 rc=2），批量跑门需回退。
