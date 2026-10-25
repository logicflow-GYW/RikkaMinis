# FIX-WIDE4-02 — katex_bridge_arity / budget_stop_break / liveness_static 三门见证加固

- 波次：WIDE4（外部对抗审计第二波，门见证 × 3 门 × 6 尝试）
- 分支：`fix/wide4-02-katex-budget-liveness`（工作树 `/var/minis/shared/work/meta-wide4/wt-02`）
- 基线：`base/wide4-shared` = `dbab7a3e`（`19e70dab` + 共享模块 `_kotlin_lexer.py` / `_kotlin_ast.py`）
- 施工提交：`c35d62a4`（仅 3 个门脚本，+919/−325；生产 `.kt` **0 行改动**）
- 报告：`fix-reports/FIX-WIDE4-02-katex-budget-liveness.md`（本文件）

## §1 门与轴

| 门 | 盲见证（修复前 rc=0） | 已挡（修复前即 rc=1） | 加固后 | 失效轴 |
|---|---|---|---|---|
| `katex_bridge_arity_check.py` | 4 | 2 | 6/6 CAUGHT 或 STILL_CAUGHT | family-hole ×3 · parser-death ×1 |
| `budget_stop_break_guard.py` | 4 | 2 | 6/6 | family-hole ×3 · parser-death ×1 |
| `liveness_static_guard.py` | 4 | 2 | 6/6 | family-hole ×3 · parser-death ×1 |

三门同族总病根：**判据锚「某拼写/某 token 存在」而非「该决定仍然生效」**，且自有解析器对 Kotlin/JS 次级语法（嵌套类、lambda 实参、raw string、模板插值、JS 正则字面量）一碰即死。本支把解析面统一收敛到共享原语 `_kotlin_lexer`（引号/原始字符串/模板/行注释/块注释感知的字节级掩码）与 `_kotlin_ast`（`const_bool` 布尔活性、`when_branches`、`split_top`、括号配平），三门判据改为「从解析结果派生决定」。

## §2 见证逐条

### katex_bridge_arity_check.py（JS↔Kotlin 桥 arity 对照）

| # | 轴 | 原补丁（要点） | 旧判据为何瞎 | 新判据 | 新臂 |
|---|---|---|---|---|---|
| 1 | family-hole | `onRendered` 首行插 `if (!false) return` | `_body_is_dead` 只认 `{\s*return` 与 `if (true)`，`if (!false)` 不在字面量表里 | 布尔活性求值：`const_bool` 折叠恒真/恒假条件，`if (!false) return` 的体判死 ⇒ 桥方法体不可达即红 | `w4-dead-guard` |
| 2 | family-hole | 注册点改空 lambda `JsBridge { _, _, _, _ -> }` | `_object_body` 见到 `JsBridge { … }` 去搜 `class JsBridge` 的**类体**，lambda 体被跳过 | 绑定到**实际注册对象**：解析 `addJavascriptInterface` 处 lambda 体本身；空 lambda = 无 `@JavascriptInterface` 方法 = 红 | `w4-callback` |
| 3 | family-hole | 真 `JsBridge` 挪进 `UnusedHolder` 嵌套类，同名死类顶替 | `\b(?:class\|object)\s+JsBridge\b` 全文搜名，不分嵌套层级 | 成员解析按**所在类深度 1** 认领；同名嵌套死类不能顶替注册对象；解析不出注册对象 fail-closed 红 | `w4-shadow` |
| 4 | parser-death | katex-render.html 里插 `var _re = /\//;` | `strip_comments` 只追踪 `"` `'`；JS 正则字面量 `/\//` 的 `//` 被当行注释，**真调用行被整行抹掉** | 掩码器升级为引号 + JS 正则字面量感知（`_kotlin_lexer` 语义用于 JS 文件）；被掩码吞掉的调用点视为「看不见」⇒ 计数面缺口 fail-closed | `w4-regex` |
| 5 | dataflow | （已挡）4 参回调早退 | 门输出 `KATEX BRIDGE ARITY: 1 mismatch(es)` | — 保持 | — |
| 6 | scope | （已挡）`JsBridge` 实例无 `onRendered` | 门输出 `2 mismatch(es)` | — 保持 | — |

自证臂 5 → 10（新增 `w4-base / w4-dead-guard / w4-callback / w4-shadow / w4-regex`，各臂先断言补丁前 rc=0、补丁后 rc=1 且归因到新判据）。

### budget_stop_break_guard.py（预算停止必须真的跳出回合循环）

| # | 轴 | 原补丁（要点） | 旧判据为何瞎 | 新判据 | 新臂 |
|---|---|---|---|---|---|
| 1 | family-hole | `break@turnLoop` 前插 `if (false) `，另加裸 `break` | `analyse` 取块内**第一个** `BREAK_RE` token 当决定性 break，不判守卫活性 | 布尔活性折叠：恒假守卫下的 labeled break 不算决定性；决定性 break = 第一个**活路径**上真正跳出回合循环的 break | `w4-dead-guard` |
| 2 | family-hole | 分支改成 `collectDone = true; continue`（根本没有 break） | 旧注释假设「无 break ⇒ 分支不主张结束回合」= **fail-open**：停机原因被赋值后继续跑后续 turn | 分支**赋值了停机原因**（`t7BudgetStopReason = …`）就必须以 break/跳出处收束；`continue` 落空即红 | `w4-fallthrough` |
| 3 | family-hole | 右值改 `= ("deadline_reached")` | `ASSIGN_RE` 硬性要求 `=` 后紧跟 `"`，带括号/常量引用的写法直接**退出候选集** | 赋值识别容忍括号包裹与常量引用（仍要求值面是停机原因字面量族），站点不丢 | `w4-assign` |
| 4 | parser-death | 插 `val _raw = """ " } """` | `strip_code_text` 只有单双引号状态，`"""` raw string 把引号状态机搅乱，分支体解析错位 | 字符串/注释掩码整体换 `_kotlin_lexer`（raw string + 模板感知），解析失败 fail-closed 红 | `w4-rawstring` |
| 5 | scope | （已挡）labeled break 换裸 break | 门输出 `break ends the branch open…` | — 保持 | — |
| 6 | order | （已挡）死循环包住 labeled break | 门输出 `break ends the branch open…` | — 保持 | — |

自证臂 10 → 14（新增 `w4-dead-guard / w4-fallthrough / w4-assign / w4-rawstring`）。

### liveness_static_guard.py（AppLogger 探针活性普查）

| # | 轴 | 原补丁（要点） | 旧判据为何瞎 | 新判据 | 新臂 |
|---|---|---|---|---|---|
| 1 | family-hole | `info` 体改 `when (false) { true -> log(…) }` | `DEAD_COND_RE` 只枚举 `if/while (false\|!true\|0==1\|true\|\|…)` 拼写，`when (false)` 完全不认识 | 死代码判定改从 `when_branches` + `const_bool` 派生：`when (false)` 的分支体是死的，唯一次发射落在死代码里 ⇒ 红 | `self/D3` |
| 2 | family-hole | 方法改签名（首参改名/换名 `trace`） | 普查正则钉死 `fun \w+\(\s*\w+: String, \w+` 一种拼写，改名即**对普查隐身** | 发现改**行为派生**：体内经 `log(...)` 发射 ⇒ 是日志方法；联合 `_log_shaped_params`（公共成员 + 前两参均 `String` 的签名形状）兜住空体日志方法 | `self/A2`、`self/A3` |
| 3 | family-hole | 多行注释探针 `// AppLogger.info (\n TAG,` | `COMMENT_PROBE_RE` 要求方法名紧跟 `(` 且同行有字符串字面量 | 注释探针检测改**引号感知掩码 + 多行扫描**：注释里的 `AppLogger.<level>(` + 非字面量首参也算「注释里的探针是谎言」 | `self/E2`、`self/E3` |
| 4 | parser-death | 插 `val _open = "${"/*"}"` + 空体 `fun trace` | `blank_comment` 在字符串态遇 `${` 后的 `"` 误回 None，紧接的 `/*` 开启假块注释，**后续声明整段蒸发** | 掩码器加模板插值态（`${…}` 内引号不改变字符串状态），`${"/*"}` 不再开假注释 | `self/L3` |
| 5 | dataflow | （已挡）`if (false) log(…)` | 门输出 `D: AppLogger.info emits nothing` | — 保持 | — |
| 6 | parser-death | （已挡）单行注释假探针 | 门输出 `E: … spells a probe call inside a comment` | — 保持 | — |

自证臂 8 → 12（新增 `self/A3 / self/D3 / self/E3 / self/L3`；A2/E2 为旧臂扩展断言）。

## §3 判据改动清单（函数级）

- **katex_bridge_arity_check.py**：`strip_comments` → `_kotlin_lexer.analyse` 掩码（JS 正则字面量感知）；`_body_is_dead` → 布尔活性（`const_bool`，`if (!false) return` 判死）；`_object_body` → `_decl_spans` 成员解析 + 注册点绑定（lambda 实参体、深度 1 成员、不可解析 fail-closed）；`--selftest` 补 5 臂。
- **budget_stop_break_guard.py**：`strip_code_text` → `_kotlin_lexer.analyse`（raw string/模板）；`ASSIGN_RE` → 赋值站点识别容忍 `= ("…")`；`analyse` → 恒假守卫折叠 + 「赋了停机原因必须收束循环」；selftest 10 → 14 夹具。
- **liveness_static_guard.py**：`blank_comment` → 模板插值感知；`DEAD_COND_RE` 死代码判定 → `when_branches`/`const_bool` 派生；`applogger_log_methods` → 发射派生 + `_log_shaped_params` 签名形状联合判据；`commented_probe_problems` → 引号感知 + 多行 + 非字面量首参；selftest 8 → 12 臂。

生产代码、`scan.sh`、`test_scan.py`、既有冻结标记串：**零触碰**（三门标记串原样保留，只加臂）。

## §4 四层证据

1. **本门**：真树 `python3 scripts/scan/<门>.py .` 三门 rc=0；`--selftest`（liveness 为 `--self-test`）全臂带牙：katex 10 臂 / budget 14 夹具 / liveness 12 臂，标记串见 §7。
2. **反攻复验**（`probe-02/reattack.py`，in-process 驱动）：18/18 = **12 CAUGHT（修复前 rc=0 → 修复后 rc=1）+ 6 STILL_CAUGHT（已挡见证保持 rc=1）**，`restored=True` 18/18，`bad=0`（`probe-02/reattack.json`）。
3. **邻门 10 道 rc=0**（只读跑）：`browser_tabs_lifecycle_check` `webapp_asset_url_cleanup_check` `file_provider_paths_coverage_check` `loop_counter_reset_guard` `truncated_call_guard_check` `stale_closure_guard` `stream_flow_dispatch_guard` `log_url_redaction_guard` `claim_release_guard` `tool_result_image_branch_check`。
4. **全量**：`bash scripts/scan/scan.sh` → **112 passed, 0 failed**；`python3 scripts/scan/test_scan.py` → **ALL 344 CASES PASS**。

沙箱注记：12 支并发窗口内 `waitpid`/`stat` 随机 `OSError: [Errno 38]`（S28），`bash scan.sh`（每门一子进程）两次死在 16/112 附近；换**进程内驱动**（`probe-02/sweep.py` 初版、`reattack.py` 终版）后不再 fork，反攻 18/18 一次跑完；权威读数仍以 scan.sh 全量 + CI 为准。反攻驱动内置开工自愈（`git status src` 脏即 `checkout --`）与 IO 重试，防「杀在 apply 与 restore 之间留下变异树」复发。

## §5 CI

- 分支 push + 手动 dispatch（`build-apk.yml`，非 main 分支不自动触发）。
- **run `40183453003` / job `121139172135`，attempt 1，conclusion `success`**，`head_sha = c35d62a43efc6667231edd3494bc4ca96a5ee7b0`，三方（本地 HEAD / `branches` API / `run.head_sha`）逐字符一致。
- 日志判读（job 日志 1,485,718 B，`probe-02/job-121139172135.log`）：`Summary: 112 passed, 0 failed`（框线）恰 1 · 门头 112 个编号互异（[1/112]…[112/112]）· `ALL 344 CASES PASS` 恰 1 · ` e: ` **0** · 含 `FAILED` 的 6 行逐条核对全为 PASSED 用例名子串（`FAILED without PASSED = 0`）· 26 步 `not_ok=[]`、唯一 skipped = `Publish to Releases`（main 门控）。
- 门自证标记串在 CI 日志中 **0 命中**（`KATEX_BRIDGE_ARITY_SELFTEST` / `BUDGET_STOP_BREAK_SELFTEST` / liveness 收尾行均未出现）——test_scan 只按 rc 断言，不把门 stdout 打进日志（M6-R9 同族）。即：本支三门的新臂**在 CI 侧无见证**，须由主会话并集收口时按 §7 在 `test_scan.py` 补标记断言，否则臂数变化无人看守。
- 报告回填后 `git diff --name-only c35d62a4..HEAD -- . ':(exclude)fix-reports'` = 0 行（源码树与 CI 绿树等价）。

## §6 残留（触发条件 / 触发后方案 / 搁置理由）

1. **katex 跨文件桥接可能误红（fail-closed 方向，安全）**：注册对象与回调体分居两文件、经中间变量传递的桥，解析不出即红。触发：CI 或后续审计报出「合法跨文件桥被本门红」。方案：把 `_decl_spans` 成员解析升为跨文件两跳。搁置理由：本仓 `addJavascriptInterface` 全部同文件就近注册，现实触发面为空。
2. **budget 停机分支经 `return` 出嵌套 lambda 的形态未建模**：`return@lambda` 不结束回合循环，但也不带 `break`。触发：外部见证给出「赋停机原因 + `return@…` 收束」的变体。方案：`analyse` 的收束判定加 labeled return 识别。搁置理由：现判据对该形态 fail-closed 红（误报方向），无静默放行。
3. **liveness 公共 `(String, String)` 非日志方法会进普查**：`_log_shaped_params` 按签名形状兜底，`describe(category, message)` 类助手若无发射会被普查点名（误报方向）。触发：CI 真树出现此类红。方案：形状判据追加「体内无 `log(` 且无发射」白名单豁免需附 KDoc 标记。搁置理由：本仓 `AppLogger` 成员恰好全部是日志方法，真树 rc=0。

## §7 注册项（主会话并集收口用）

| 门脚本 | selftest 标记串（逐字） | 臂数 |
|---|---|---|
| `katex_bridge_arity_check.py` | `KATEX_BRIDGE_ARITY_SELFTEST=PASS` | 10 |
| `budget_stop_break_guard.py` | `BUDGET_STOP_BREAK_SELFTEST=PASS (14 fixtures)` | 14 |
| `liveness_static_guard.py` | `✅ self-test: clean tree passes; A/B/C/D + renamed-parameter/dead-sink/commented-key arms all caught` | 12 |

三门均为**既有门**（test_scan 已注册），无新门注册需求；标记串一律未改，只增臂。

## §8 复现步骤

```sh
cd /var/minis/shared/work/meta-wide4/wt-02
python3 scripts/scan/katex_bridge_arity_check.py .            # rc=0
python3 scripts/scan/katex_bridge_arity_check.py --selftest   # KATEX_BRIDGE_ARITY_SELFTEST=PASS
python3 scripts/scan/budget_stop_break_guard.py .             # rc=0
python3 scripts/scan/budget_stop_break_guard.py --selftest    # BUDGET_STOP_BREAK_SELFTEST=PASS (14 fixtures)
python3 scripts/scan/liveness_static_guard.py .               # rc=0
python3 scripts/scan/liveness_static_guard.py --self-test     # 12 臂全绿
python3 -u ../probe-02/reattack.py                            # 18/18, restored=True（会先自愈 src/）
bash scripts/scan/scan.sh                                     # 112 passed, 0 failed
python3 scripts/scan/test_scan.py                             # ALL 344 CASES PASS
```

反攻输入：`/tmp/w4-02-witnesses.json`（从任务书 §门.尝试 的 ```json 块机械提取，18 条）；逐条 apply → 跑门 → restore → sha256 复核。
