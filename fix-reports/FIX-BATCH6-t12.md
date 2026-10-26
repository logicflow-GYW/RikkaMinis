# FIX-BATCH6-t12 — 三门 18 条见证判据加固

分支 `fix/b6-skillfm-b64-handback`，基线 `base/batch6-shared` @ `e145ec961`
（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow`）。
门提交 `64c4f46ba`。生产 `.kt` **0 行**、`res/` **0 行**、`scan.sh` /
`test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` **零触碰**、门 112 / 夹具 344 不变。

| 门 | 文件 | 旧臂 → 新臂 | 本波见证 |
|---|---|---|---|
| skill_frontmatter_roundtrip_check | `scripts/scan/skill_frontmatter_roundtrip_check.py` | 17 → **23** | 6（04-1..04-6） |
| offload_media_b64_wire_check | `scripts/scan/offload_media_b64_wire_check.py` | 17 → **25** | 6（11-1..11-6） |
| subagent_handback_check | `scripts/scan/subagent_handback_check.py` | 9 → **16** | 6（05-1..05-6） |

---

## ① 门与轴

| 门 | 审计对象（生产站点） | 失败的代价 | 本波见证轴 |
|---|---|---|---|
| `skill_frontmatter_roundtrip_check` | `SkillRepository.resolveMergeBase` / `writeSkillMd` / `mergeFrontmatter` / `updateFromURL` + 两个 UI 保存路径（`SkillsManagementScreen`、`MinisSkillsBrowserScreen`） | 更新的 SKILL.md 丢掉 `subagent` / `max_turns` / `allowed_tools`（写盘后被下次读取当成"没有该键"） | dataflow ×3 · family-hole ×2 · parser-death ×1 |
| `offload_media_b64_wire_check` | worker → `result.json` → 主进程的 base64 媒体线（`ModelExecutionService` 生产侧、`ProviderExecutionGateway` / `ModelUseOffloadHandler` / `ChatStreamJsonl` 消费侧、`OffloadWireBase64` 共享入口） | 生成的图/音频变成 0 字节；一个坏 payload 丢掉整轮结果或直接终止流 | dataflow ×1 · family-hole ×3 · spelling ×1 · order ×1 |
| `subagent_handback_check` | `ChatToolExecutors.runSubagentLoop` 的"把回合交还模型"路径与终端 catch | 模型看不到自己刚写的话 → 重写一遍，子代理结果里出现两遍；用户点停止被记成"子代理出错" | family-hole ×3 · dataflow ×1 · order ×1 · registry ×1 |

**三门共症（本波判据提炼）**：判据锚「某个 token 被写出」，缺陷动「这个 token 的**效果**」——
调用还在但被恒假守卫包住 / 实参在但是常量或零长派生 / 计算还在但结果没传给下游 /
守卫还在但原样重抛 / 豁免标记可以自签 / 词法器在 `'` 上死掉把注释里的调用复活。

---

## ② 见证原文（18 条，模型给出、机械复证后原样）

> `find` / `replace` 为报告原文（`\n` 转义）；`why_gate_green` 摘录门当时的判据。

### `skill_frontmatter_roundtrip_check.py`

**04-1 · dataflow · arm1 `check_resolve_polarity()`**
`find`: `): String? = newFrontmatter?.ifBlank { null } ?: diskContent`
`replace`: `): String? = newFrontmatter?.takeIf { false } ?: (diskContent ?: newFrontmatter)`
`why`: 门只检查 `names[0] not in left` / `names[1] not in right`——左操作数含 `newFrontmatter` 即通过，不问它是否恒为 null。

**04-2 · dataflow · arm2 `writeSkillMd`**
`find`: `val mergeBase = resolveMergeBase(skill.frontmatter, existing)\n        val content = mergeFrontmatter(\n            existing = mergeBase,`
`replace`: 同上但 `existing = existing,`
`why`: 门只检查 `WRITE_CALL in sr_nc`（子串）与 `OLD_POLARITY not in sr_nc`，不看 `existing =` 收到的是什么。

**04-3 · dataflow · arm4 `updateFromURL`**
`find`: `version = parsed.version,\n            frontmatter = parsed.frontmatter.ifBlank { null },\n        )`
`replace`: `… frontmatter = parsed.frontmatter.ifBlank { null }.let { null }, …`
`why`: `check(…, 'frontmatter = parsed.frontmatter' in url_body)` —— 纯子串，尾部 `.let { null }` 不可见。

**04-4 · family-hole · arm7 `check_merge_seed()`**
`find`: `val fm = mutableListOf<String>()\n            for (i in 1 until frontmatterEnd) {`
`replace`: 中间插入 `fm.add(src[0])`
`why`: 门只查 `'---' in init` 与 `\b<var>\.add(All)?\([^)]*---`——`src[0]` 括号内没有那三个字符，而 `raw.startsWith("---")` 已保证 `src[0] == "---"`。

**04-5 · family-hole · arm10 `resolve_target_first_arg_problems()`**
`find`: `resolveUpdateTargetId(\n            skillRepository.skills.value.map { it.id to it.name },`
`replace`: `… .skills.value.filter { false }.map { it.id to it.name },`
`why`: 门只查 `EMPTY_COLL_RE.match(flat)`（`emptyList()` 这类空构造）与 `LITERAL_COLL_RE … and '.' not in flat`——从活快照派生出的空列表两者都不命中。

**04-6 · parser-death · `strip_comments()`**
`find`: `val mergeBase = resolveMergeBase(skill.frontmatter, existing)`
`replace`: `val q = '"' // " resolveMergeBase(skill.frontmatter, existing)\n        val mergeBase = existing`
`why`: 同文件 `mask_strings` 有 `elif c == "'":`，`strip_comments` 没有——`'"'` 里的 `"` 开了一个假字符串，把 `//` 一起吞掉，注释里的调用重新进入扫描文本。

### `offload_media_b64_wire_check.py`

**11-1 · dataflow · C1（gateway）**
`find`: `data = runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()\n                        ?: return@mapNotNull null,`
`replace`: `… .decode("") …`
`why`: 门只检查 `)` 之后到 `runCatching` 的 `}` 之间为空白——`.decode(` 与 `)` 之间是什么，从未被读。

**11-2 · family-hole · C1（gateway，守卫之外）**
`replace`: `… }.getOrNull()?.let { ByteArray(0) }\n                        ?: return@mapNotNull null,`
`why`: `block_end` 只到 `runCatching` 的 `}`；`}` 之后的 `?.let { ByteArray(0) }` 在判据视野外。

**11-3 · spelling · C2 `flag_tokens()`**
`replace`: `android.util.Base64.encodeToString(m.data, android.util.Base64.URL_SAFE + android.util.Base64.NO_WRAP)`
`why`: `flag_tokens` 先 `re.split(r"\bor\b", arg)` 再 `.split(".")[-1]` —— `+` 连接的两个 token 只剩右操作数 `NO_WRAP`。

**11-4 · family-hole · C4b（`ChatStreamJsonl`）**
`find`: `data = OffloadWireBase64.decode(b64) ?: ByteArray(0),`
`replace`: `data = OffloadWireBase64.decode(b64) ?: error("bad b64"),`
`why`: 门只查 `\bthrow\b` 或 `'!!' in tail`；`error(...)` 两者都不是（字符串参数还被 mask 成空格）。

**11-5 · order · C5 `check_helper()`**
`replace`: `if (b64.isNotEmpty()) java.util.Base64.getMimeDecoder().decode(b64)\n        else runCatching { … }.getOrNull()`
`why`: C4 对每个解码点查 `guarded_spans`，C5 **根本没调** `guarded_spans`——守卫只包住永不执行的 else 分支也算合格。

**11-6 · family-hole · C4（`ModelUseOffloadHandler`）**
`replace`: `data = try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (e: Throwable) { throw e },`
`why`: `guarded_spans` 只判"偏移落在 `try { }` / `runCatching { }` 区间内"，守卫是否**吸收**异常不在判据里。

### `subagent_handback_check.py`

**05-1 · family-hole · C1 `dead_guarded()`**
`replace`: `if (!true) appendSubagentTurnText(history, text, turnReasoning.value())`
`why`: `CONST_FALSE = ^\s*(?:false|0)\s*$` —— `!true` 不匹配。

**05-2 · dataflow · C1 第二实参**
`replace`: `appendSubagentTurnText(history, text.take(0), turnReasoning.value())`
`why`: 门只把 `mask` 后的实参是否**为空字符串**当失败；`text.take(0)` 拼写完整、求值为空。

**05-3 · order · C1（调用之后）**
`replace`: `if (appendSubagentTurnText(history, text, turnReasoning.value())) history.removeAt(history.lastIndex)`
`why`: 门只验证调用存在且不在 `if (false)` 里，不问追加进去的消息在 `continue` 前是否还在。

**05-4 · family-hole · C2 `live_throw()`**
`replace`: `} catch (e: CancellationException) {\n        if (turns < 0) throw e\n        return ToolExecutionResult("Sub-agent '$skillName' encountered an error", …)`
`why`: `turns < 0` 不是字面量 false；`block[:pos]` 里也没有"更早的无条件 return"（那个 return 在 throw 之后）。

**05-5 · registry · C1 豁免标记**
`replace`: 把 echo 调用整行换成 `// [no-assistant-echo: bypass]`
`why`: `if ECHO_EXEMPT_MARKER in window_raw: continue` —— 源码注释本身就是许可证，生产代码 0 处使用该标记却无人核对。

**05-6 · parser-death · `mask()`**
`replace`: `val quote = '"' // " appendSubagentTurnText(history, text, turnReasoning.value())`
`why`: `mask()` 处理 `//`、`/*`、`"`，**没有** `'` 分支——`'"'` 开假字符串把 `//` 吞掉，注释里的调用复活。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### skill_frontmatter_roundtrip_check

| # | 旧判据 | 新判据 | 依托 |
|---|---|---|---|
| 04-1 | 左操作数含形参名 | + 左操作数**能否非空**：`provably_null()` 折叠 `takeIf{…}` / `takeUnless{…}` / `let{null}` / `run{null}` / 字面 null（`ifBlank{null}`、`getOrNull()` 属**条件**空，明确不算） | `const_bool` + `match_bracket` |
| 04-2 | `resolveMergeBase(...)` 子串存在 | + 从 `val X = resolveMergeBase(` 派生绑定，要求 `mergeFrontmatter(existing = …)` 的实参**派生自 X**（`contains_ident`，或直接内联调用） | `call_args` / `contains_ident` |
| 04-3 | `frontmatter = parsed.frontmatter` 子串 | + 该具名实参 `provably_null()` 判空（arm4/arm8/arm9 三处调用点统一走 `provably_null_arg_problems()`） | 同上 |
| 04-4 | 初始化器 / 走空前语句里出现 `---` 字面量 | + **累加器状态**：初始化器必须是空构造，且走空前**任何**对该变量的 `add/addAll/set/+=`（按 `path_feasible` 判活）都是违规 | `path_feasible` + 空构造白名单 |
| 04-5 | 首实参是空构造字面量 | + `provably_empty_collection()`：`filter{false}` / `filterNot{true}` / `take(0)` / `drop(Int.MAX)` / `?: emptyList()` 一律判空（`const_bool` 折叠条件） | `const_bool` / `call_args` |
| 04-6 | 自写 `strip_comments` + `mask_strings` | 删除，改共享 `mask_comments`（结构 + 字面量保留，喂给需要字面量锚点的臂）与 `mask_code`（纯结构）；两者与源码偏移逐字符对齐 | `_kotlin_lexer` |

### offload_media_b64_wire_check

| # | 旧判据 | 新判据 | 依托 |
|---|---|---|---|
| 11-1 | `.decode(` 之后为空白 | + **实参语义**：`decode_arg_reason()` —— 常量/字面量、零长派生（`take(0)` 等）、无标识符一律红 | `const_value` / `call_args` |
| 11-2 | 守卫 `}` 之内没丢值 | + 守卫 `}` **之后**到语句尾不许把值换成空（`ByteArray(0)`、`let/run/also/apply` 尾巴为常量、`fill(0)/copyOf(0)/take(0)`）；`try` 的 catch/finally 子句先跳过（`catch { ByteArray(0) }` 是文档化的降级路径） | `stmt_spans` / `match_bracket` |
| 11-3 | 按 `or` 切分后取 `.split('.')[-1]` | + `flag_tokens()` 取**表达式里全部** `X.FLAG` token（先验证其余字符只有运算符），任何非 `NO_WRAP` 即红；非纯 flag 表达式 fail-closed | 自写 token 扫描（无括号/字面量语义） |
| 11-4 | `throw` / `!!` | + `ABORT_RE`：`throw` / `!!` / `error(` / `require(NotNull)(` / `check(NotNull)(` / `getOrThrow` / `throwOnFailure` / `TODO(` | `statement_tail` |
| 11-5 | 共享入口里出现 `runCatching` 子串 | + 入口内**每个**解码点必须落在**吸收型**守卫内且 `path_feasible`；`getOrNull()` 仍在（契约是 null 而非异常） | `guard_regions` + `path_feasible` |
| 11-6 | 解码点落在 `try{}`/`runCatching{}` 区间内 | + `guard_regions()` 返回 `(start, end, absorbing)`：`runCatching` 后接 `.getOrThrow()/.throwOnFailure()` 不算；`try` 的 catch **原样重抛被捕获变量**不算（`throw AudioInputError(...)` 这类带类型的拒绝**保留**为合法，见同文件既有 KDoc） | `match_bracket` + 语句级 throw 归属 |

### subagent_handback_check

| # | 旧判据 | 新判据 | 依托 |
|---|---|---|---|
| 05-1 | 条件字面量 `false|0` | + `path_feasible()`：任何 `enclosing_conditions` 可折叠为假（`!true`、`false`、`1==2`、`when(false)`）即判死 | `if_blocks` + `const_bool` |
| 05-2 | 第二实参 `mask` 后为空 | + `empty_turn_text_reason()`：常量/空串/null、零长派生（`take(0)`/`substring(0,0)`/`slice`）、`filter{false}`/`takeIf{false}`/`let{""}`，以及**必须派生自本循环内的 `val/var`**（`skillName` 这类外来标识符判红） | `const_value` / `const_bool` / `match_bracket` |
| 05-3 | 调用存在且未被 `if(false)` 守卫 | + echo 调用之后到 `continue` 之间，同花括号深度上不许对 `history` 做 `removeAt/removeLast/removeFirst/remove/removeAll/clear/retainAll/dropLast/set` | `brace_depth` |
| 05-4 | 无条件 return 才算不可达 | + `int_domains()`：`var turns = 0` 且无递减/无重置 ⇒ 域 `[0, ∞)`，`if (turns < 0)` 折死；`+ path_feasible` + 同块无条件退出 | `guard_feasible` / `stmt_spans` |
| 05-5 | 标记子串存在即豁免 | + `ECHO_EXEMPT_REGISTRY`（生产为空）：未登记的理由 = 自签豁免 = 违规；注册机制本身由一条反向对照臂守住 | 门内注册表 |
| 05-6 | 自写 `mask()` | 删除，改共享 `mask_code`（`'` 字符字面量按字符处理）；注释只从原始文本读（豁免标记需要） | `_kotlin_lexer` |

**改造后的共同性质**：三门的判据不再依赖"某个调用/某个标记/某个字面量存在"，而是
"这个调用**跑得到**吗 / 拿到的是**那个值**吗 / 产生的值**留下来了**吗 / 守卫**吸收**了吗"。
未知形状一律 fail-closed（解析不出实参、括号不平衡、文件不 lex 都直接红），
但**不**把"我们折不出常量"当成缺陷（`const_bool → None` 视为可行）。

---

## ④ 四层证据

**L1 自测（臂）**：`skill 23 / offload 25 / handback 16` 全绿，真树 rc=0。

```
$ python3 scripts/scan/skill_frontmatter_roundtrip_check.py --selftest
… 23 arms …
SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS          rc=0
$ python3 scripts/scan/offload_media_b64_wire_check.py --selftest
OFFLOAD_MEDIA_B64_SELFTEST=PASS                    rc=0
$ python3 scripts/scan/subagent_handback_check.py --selftest
SELFTEST OK (16 arms) SUBAGENT_HANDBACK_SELFTEST=PASS   rc=0
```

真树：三门各 rc=0（`sources clean`）。

**L2 反攻（本波最硬的验收判据）**：拿报告原文的 18 条见证再攻一次，全部由 `BLIND` 翻成
`CAUGHT`，且 `restore=True`：

```
SUMMARY: {'CAUGHT': 6}    # skill_frontmatter_roundtrip_check.py
SUMMARY: {'CAUGHT': 6}    # offload_media_b64_wire_check.py
SUMMARY: {'CAUGHT': 6}    # subagent_handback_check.py
src-clean=0               # git status --porcelain src/ 为空（18/18 逐字复原）
```

**L3 邻门 / 全量**（沙箱当前窗口 fork 会被 `ENOSYS` 打断，改用「一门一短命令」重放
`scan.sh` 的同一份门表，见 §8）：

```
110 门逐个跑：total 110 pass 110 fail 0
i18n（scan.sh 第 2 步内联脚本 → i18n_check.py）: ALL CLEAN ✅ rc=0
scan.sh 的第 112 步为编号显示，无独立脚本
test_scan.py 46 组函数分 3 批驱动：PASS=344 FAIL=0（合计 137+81+126）
邻门 10/10：truncated_call_guard / tool_title_error_branch / modelsvc_single_read /
            modeluse_proto_single_source / provider_boundary_guard / xproc_boundary /
            skill_restore_session_cleanup / skill_file_save_result /
            skill_import_offmain / memory_gate_provider_fields
```

**L4 冻结面未触碰**：`git status --porcelain` 仅 3 个门脚本；
`scan.sh` / `test_scan.py` / `_kotlin_*.py` 相对基线零 diff；门 112 / 夹具 344 不变。

---

## ⑤ CI

- run **#2706** / id `40255912256` / job `121357585853` / attempt **1** / **success**
- head_sha `64c4f46ba`（与本地 HEAD 逐字符一致），`updated_at 2026-10-26T02:09:25Z`
- 26 步唯一 skipped = **Publish**（main 门控），其余全部 success
- Step 3「Run pre-build scan gate」日志（129,909 B）逐条判读：
  - `Summary: 112 passed, 0 failed` **恰 1** · `ALL 344 CASES PASS` **恰 1**
  - 门头 `[N/112]` **112 条编号互异**（每个 1 次）
  - `e: ` 编译错误行 **0**
  - 含 `FAILED` 的 10 行逐一核对：全是 **PASSED 用例名子串**
    （`slot denied produces FAILED with…` / `reason PERSISTENCE_FAILED …` /
    `phase FAILED maps to Failed` / `terminal FAILED maps…` / `reason EXECUTION_FAILED` /
    `the fixed tail still reaches a le…`）
  - `selftest has teeth` 85 处（各门自证臂在 CI 里真跑）
  - 新臂在 CI 的见证：`OFFLOAD_MEDIA_B64_SELFTEST=PASS` 恰 1、
    `SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS` 恰 1、`SELFTEST OK` 恰 1（handback 的冻结断言）
- 判读口径说明：整包 job 日志里同样的读数再出现一次（`0_build.txt` = 聚合日志 +
  `build/3_Run pre-build scan gate.txt` = 该步日志），**同一份输出的两份拷贝**，
  不是两次运行。

---

## ⑥ 残留（P2，每条带三件）

**R1 · `provably_null` 的覆盖面**
触发条件：外部报告再给出「`?:` 左操作数恒空」的新形态（如 `.filter{false}.firstOrNull()`、`if (false) x else null`、`?.let { null } ?: null`）。
触发后方案：把 `_kotlin_valueflow.value_sources` 扩成「表达式能否非空」的完整折叠，门内改为调共享实现。
搁置理由：本波 6 条见证无此形态；为未出现形态建完整值域分析，装置比例超过影响面。

**R2 · arm7 的「走空前不许改」按变量名匹配**
触发条件：`mergeFrontmatter` 被拆成两个函数、累加器跨函数传递。
触发后方案：用 `value_sources` 追一跳跨函数绑定，或把门升级为「walk 的产物必须是返回值」。
搁置理由：当前全部单函数；拆函数时门会 fail-closed 报 arm7（不静默绿），有人能在 CI 看到。

**R3 · `try/catch` 吸收判据只否「原样重抛同一个变量」**
触发条件：出现 `catch (e: T) { throw Wrapper(e) }` 这种语义上等于逃逸、形式上算「带类型的拒绝」的写法。
触发后方案：按 catch 变量在 catch 体中的传播判（决定 `e` 是否被无条件包进新异常且无降级分支）。
搁置理由：仓库既有 KDoc 明确把「带类型的拒绝」当合法形态（`AudioInputError` / `ImageInputError`），收紧会误红既有设计。

**R4 · `DECODE_SITE_RE` 只覆盖 Base64 / OffloadWireBase64 家族**
触发条件：媒体线新增解码入口（`ImageDecoder`、`MediaCodec`、pillow 侧解码）。
触发后方案：扩正则 + 各打一条臂 + 在 C3/C4/C5 里补对应文件。
搁置理由：当前仓内 0 处；扩白名单而无站点 = 无效覆盖面。

**R5 · C4b 只看 `ChatStreamJsonl` 的 `?:` 语句尾**
触发条件：出现「miss 一定终止流」的其他写法（例如在 `let` 里 `if (it == null) return`）。
触发后方案：对 JSONL 的每个解码点做「所有出口都必须降级」的分支终止检查（`_exits_block` 风格）。
搁置理由：当前唯一站点是 `?:`；更复杂的形态尚未被真实代码使用。

**R6 · `int_domains` 只认「字面量初始化 + 无递减」**
触发条件：出现 `turns = other + 1` 这类重置，或计数器被传参后返回。
触发后方案：接 `is_single_assignment` + 算术折叠，非单调时明确 fail-open（宁可不报，不误红）。
搁置理由：`runSubagentLoop` 里 `turns` 形态稳定；过窄只会漏报（安全方向），不会误红。

**R7 · handback 第二实参要求「派生自本循环的 val/var」**
触发条件：出现 `appendSubagentTurnText(history, buildString { … })` 这类内联构造的合法形态。
触发后方案：允许 `buildString/joinToString` 等构造器，并递归检查其内容是否引用 turn 文本。
搁置理由：当前唯一站点传 `text`；为未出现的形态放宽会削弱 05-2 的判别力。

**R8 · 三门 selftest 臂数只有 handback 进 CI 日志**
触发条件：需要机械核对臂数（例如下一波回归想确认 25 条臂真的跑了）。
触发后方案：把臂数写进 marker 串（handback 已是 `SELFTEST OK (16 arms)`），另两门同时改 `test_scan.py` 的断言（冻结面）。
搁置理由：`test_scan.py` 冻结，本支不得修改；改 marker 会破既有断言。

**R9 · `post_guard_zeroing` 的窗口上限 600 字符**
触发条件：守卫之后出现超长链（>600 字符）才丢弃解码值。
触发后方案：改为括号/分号驱动的表达式行走，去掉长度上限。
搁置理由：当前站点都是单行链；超长链属可读性事故，出现时才值得处理。

---

## ⑦ `test_scan.py` 注册项

**三门都已在册，本支不需要新注册**（这也是没有动冻结面的原因）：

| 门 | 已注册断言 | 本支是否保持 |
|---|---|---|
| `subagent_handback_check` | `(REPO_ROOT,)` + 标记 `SELFTEST OK`；另有外部夹具对 `GOOD` / `NO_ECHO`（`missing assistant echo`） | ✅ 标记串保留（新输出 `SELFTEST OK (16 arms) SUBAGENT_HANDBACK_SELFTEST=PASS`），`GOOD` / `NO_ECHO` 两个模块级常量保留 |
| `offload_media_b64_wire_check` | 标记 `OFFLOAD_MEDIA_B64_SELFTEST=PASS`，`(REPO_ROOT,)` | ✅ 不变 |
| `skill_frontmatter_roundtrip_check` | 标记 `SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS`，`(REPO_ROOT,)` | ✅ 不变 |

**给主会话的可选建议**（非必须）：`subagent_handback_check` 的外部夹具对目前只覆盖
"缺 echo"，可再补一对 `GOOD` / `NO_ECHO_TAKE0`（`text.take(0)`）——但需要改
`test_scan.py`，本支按冻结纪律未做。

---

## ⑧ 复现命令

```bash
cd wt6/t12   # 基线 base/batch6-shared @ e145ec961

# 三门口径
for g in skill_frontmatter_roundtrip_check offload_media_b64_wire_check \
         subagent_handback_check; do
  python3 scripts/scan/$g.py --selftest; echo "  selftest rc=$?"
  python3 scripts/scan/$g.py .;          echo "  tree rc=$?"
done

# 反攻 18 条见证（原报告原文，逐条 patch → 跑门 → restore）
for g in skill_frontmatter_roundtrip_check.py offload_media_b64_wire_check.py \
         subagent_handback_check.py; do
  python3 ../verify_batch5.py --witnesses ../witnesses-B.json --repo . \
      --mode full --only $g
done
git status --porcelain src/    # 必须为空

# 全量门（沙箱窗口 fork 会 ENOSYS：用「一门一模块」的进程内重放）
python3 ../probe/t12_scan_inproc.py 999
python3 scripts/scan/i18n_check.py .

# test_scan（必须传 repo root，否则 REPO_ROOT 走样 → 大量假红）
python3 ../probe/t12_testscan_chunks.py 999
```

> 说明：`scripts/scan/scan.sh` 整体前台跑在本机当前窗口会被 stall-guard 杀掉
> （`OSError: [Errno 38]` / `head: Function not implemented`，见
> `sandbox-limits.md` §S28 变体）。CI 里 `scan.sh` + `test_scan.py` 正常执行，
> 本地证据由上面两个重放器承担（同一份门表、同一批夹具）。
