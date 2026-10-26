# FIX-PRIMITIVE-ELSE-NEGATION — `enclosing_conditions` else 取反优先级缺陷根治

| 项 | 值 |
|---|---|
| 分支 | `fix/primitive-else-negation` |
| 基线 | main `627f23f2e`（R3 + P1 合并后 main） |
| 门提交 | `b51cd03cf22715159970f7c9a86f6d4340ddbc90` |
| 报告提交 | 见 §5 回填 |
| 改动面 | `scripts/scan/_kotlin_ast.py`（实现 + 2 臂）、`scripts/scan/_kotlin_valueflow.py`（3 臂）；生产 `.kt` **0 行** |
| 停止节点 | 分支 CI 绿（head_sha 逐字符一致）= 结束，**未合并 main** |

## 1. 缺陷与影响面

`scripts/scan/_kotlin_ast.py` 的 `enclosing_conditions()` 在 else 分支返回
**裸 `!` 前缀**（`'!' + cond`）。复合条件上 Kotlin 的 `!` 只作用于紧邻的
操作数，于是取反后的字符串语义与源码的「else 分支」完全不同：

| 条件 | 真实语义 | 取反后字符串 | 实测 `path_feasible` | 后果 |
|---|---|---|---|---|
| `if (a == null \|\| true)` 的 else | **死**分支 | `!a == null \|\| true` ＝ `(!a) == null \|\| true` 恒真 | `True` | **误红**方向（死代码里的站点被当成活站点） |
| `if (x == null && false)` 的 else | **活**分支 | `!x == null && false` ＝ `(!x) == null && false` 恒假 | `False` | **静默放行**方向（活代码里的站点被当成死站点，门直接放过） |

影响面 = 89 个消费门（`grep -l "enclosing_conditions\|path_feasible\|_kotlin_valueflow" scripts/scan/*.py`，
排除 `_kotlin*`）。两个门此前因此写了**本地 workaround**（各自注释自认）：

- `scripts/scan/mirror_category_route_check.py:612-632` — 自建 `reachable()`，用 `f"!({cond})"`；
- `scripts/scan/browser_destroy_wakeup_check.py:175-186` — 同款自建 `reachable()`。

冻结期（第五/六/七批门禁施工）已解除，本轮在原语层根治，消费门零改动。

## 2. 修法（change-ladder：停在第 6 级「能一行」）

一行实现修复 + KDoc 写清契约（`else` 侧返回括号化取反，消费方无需本地补丁）：

```python
-            out.append(('if', '!' + cond))
+            out.append(('if', '!(' + cond + ')'))
```

不新增函数、不改消费门、不加配置项。`const_value()` 开头即 `strip_parens`，
所以 `!(a == null || true)` 先剥括号再走 `!` 分支，短路语义（`|| true` 恒真、
`&& false` 恒假）保持不变 —— 修复不依赖 `const_bool` 的任何改动。

## 3. 自测臂（期望值写死字面量，不从被测实现推导）

| 模块 | 臂 | 断言 |
|---|---|---|
| `_kotlin_ast` 32 → **34** | `enclosing_else_compound_parenthesised` | `if (a == null \|\| true) … else <pos>` 的 enclosing 恰为 `['!(a == null || true)']` |
| | `enclosing_else_simple_parenthesised` | 简单条件 else 也返回 `['!(a == null)']`（形态固定，不是「只有复合才加括号」） |
| `_kotlin_valueflow` 26 → **29** | `path_feasible_else_dead_or_true` | 死 else 端到端 `is False` |
| | `path_feasible_else_live_and_false` | 活 else 端到端 `is True` |
| | `path_feasible_else_simple_not_regressed` | 简单条件 else `is True`（不回归） |

`_kotlin_ast` 的臂走 `enclosing_conditions` 的**文本形态**，`_kotlin_valueflow` 的
臂走 `path_feasible` 的**判定效果** —— 两层各自独立，文本层改对而效果没改对不会
一起绿（本轮变异负控正好验证了这一点：三个 valueflow 臂里两个红、简单条件那个
本就该绿）。

## 4. 四层验证证据

### L1 自测（固定树）

```
$ nice python3 scripts/scan/_kotlin_ast.py --selftest
KOTLIN_AST_SELFTEST=PASS (34 arms)          rc=0
$ nice python3 scripts/scan/_kotlin_valueflow.py --selftest
KOTLIN_VALUEFLOW_SELFTEST=PASS (29 arms)    rc=0
```

### L1b 变异负控（还原成裸 `!` → 新臂必须红）

还原方式：**字节备份 + sha256 对拍**（`probe/t1_mutate.py`，禁 `git checkout --`）。

```
--- sha before mutation ---
122cf25d43d004013ea78f4d89c700a8bca4caf839ab4a701f256fbeea6e77e7  scripts/scan/_kotlin_ast.py
mutated: sha_before=122cf25d… sha_after=4f65c549e4e559f93e287a090b66d373455a25ba0de0a9382176d5e43d7323d8
--- MUTANT: ast ---
  FAIL enclosing_else_compound_parenthesised
  FAIL enclosing_else_simple_parenthesised
KOTLIN_AST_SELFTEST: 2 failure(s) / 34 arms        ast_rc=1
--- MUTANT: valueflow ---
SELFTEST FAIL: path_feasible_else_dead_or_true
SELFTEST FAIL: path_feasible_else_live_and_false     vf_rc=1
--- RESTORED ---
restored: sha_backup=122cf25d… sha_now=122cf25d… equal=True
KOTLIN_AST_SELFTEST=PASS (34 arms)  ast_rc=0
KOTLIN_VALUEFLOW_SELFTEST=PASS (29 arms) vf_rc=0
```

判读：变异体下**新臂恰好在自己的判据上红**（AST 两条形态臂 + valueflow 两条方向臂），
`path_feasible_else_simple_not_regressed` 保持绿 —— 简单条件两种写法都对，它本就
不该被这条变异打红，红的是「复合条件两方向」。恢复后 sha256 与变异前逐字符一致。

### L2 行为实证（三行 print，直调 `path_feasible`）

```
dead else  (a == null || true)    enclosing=[('if', '!(a == null || true)')]  path_feasible=False
live else  (x == null && false)   enclosing=[('if', '!(x == null && false)')] path_feasible=True
simple else (a == null)           enclosing=[('if', '!(a == null)')]          path_feasible=True
dead then  (a == null || true)    enclosing=[('if', 'a == null || true')]     path_feasible=True
live then  (x == null && false)   enclosing=[('if', 'x == null && false')]    path_feasible=False
```

（修复前同样五行：第一行 `True`、第二行 `False` —— 即缺陷的两个方向；then 侧
修复前后一致，说明本次只动 else 侧。）探针：`probe/t1_repro.py`。

### L3 邻门抽查（自测 + 真树）

| 门 | `--selftest` | 真树 rc |
|---|---|---|
| `mirror_category_route_check` | rc=0 `MIRROR_CATEGORY_ROUTE_SELFTEST=PASS (24 arms)` | 0 `MIRROR_CATEGORY_ROUTE_CHECK=PASS` |
| `browser_destroy_wakeup_check` | rc=0 `BROWSER_DESTROY_WAKEUP_SELFTEST=PASS (68 arms)` | 0 `GATE=GREEN browser_destroy_wakeup` |
| `browser_tabs_lifecycle_check` | rc=0 `BROWSER_TABS_RISK_NOTICE_AND_REMAP_SELFTEST=PASS` | 0 `browser_tabs_lifecycle_check OK` |
| `compact_decision_gate_check` | rc=0 `COMPACT_DECISION_GATE_SELFTEST=PASS` | 0 `✅ compact-decision gate …` |
| `offload_host_path_check` | rc=0 `M6_TAINT_SELFTEST=PASS (10 fixtures …)` | 0 `22 handler file(s) + 2 intent dispatcher(s) scanned, 0 violations` |
| `chat_ui_interaction_check` | rc=0 `CHAT_UI_INTERACTION_SELFTEST=PASS (86 fixtures …)` | 0 `CHAT-UI-INTERACTION GATE OK` |

**补充：负文本面双跑对拍**（本缺陷的第二风险面）。判据不是「grep 到 `'!'` 就算」——
先把 14 个**对 `!` 前缀做文本处理**的消费门（`browser_tabs_lifecycle` / `chat_menu_prefs_listener` /
`compact_decision_gate` / `deeplink_settings_routes` / `envvar_config_group` / `fastio_tar_bounds` /
`filepreview_print_bounded` / `loop_persist_slice` / `offload_cli_args` / `session_provider_enabled` /
`skill_file_save_result` / `skill_import_offmain` / `skill_restore_session_cleanup` /
`tool_result_image_branch`）在固定树与变异体下**各跑一遍 `--selftest`**，逐门 diff：

```
IDENTICAL  ×14     （全部 rc=0；fixed 与 mutant 输出逐字节相同）
```

判读：这 14 门既没有「因括号化而变红」（不误伤），也没有「靠缺陷才绿」（不存在
依赖裸 `!` 的既有判据）。脚本 `probe/negtext_sweep.sh`，日志 `probe/negtext/`。

### L4 分支 CI

```
$ sh /var/minis/skills/github-ops/scripts/gh_ci_wait.sh --repo logicflow-GYW/RikkaMinis \
    --workflow build-apk.yml --ref fix/primitive-else-negation \
    --expect b51cd03cf22715159970f7c9a86f6d4340ddbc90 --timeout 1800 --poll 60 --job-detail
...
```

（`gh_ci_wait.sh` 在 900s 处被工具壳打断，CI 本身正常跑完；结论改用 CI bridge +
Actions API 逐项核：`status=completed` / `conclusion=success`。）

## 5. CI 读数（回填）

| 项 | 读数 |
|---|---|
| run | **#2754** / id `40343477296` / https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40343477296 |
| job | `121644087290` / attempt **1** / `success` |
| head_sha | `b51cd03cf22715159970f7c9a86f6d4340ddbc90` —— 与本地 `git rev-parse HEAD` **逐字符一致** |
| 分支 | `fix/primitive-else-negation` |
| 步骤 | 26 步，唯一非 success = **Publish to Releases skipped**（main 门控，符合预期） |
| 时间 | 14:50:51Z → 15:10:55Z（20m04s） |
| 日志 | 1,492,269 B · **NUL 0** |

日志逐项读数（`probe/ci/job-121644087290.log`，脚本判读）：

```
Summary: 112 passed, 0 failed   → 恰 1 条（权威形态带框线前缀 ║，另 6 条 25/16/44/19/30/14 是子套件小 Summary）
ALL 344 CASES PASS              → 恰 1 条
^ e: （Kotlin 错误行）            → 0
含 FAILED 的行                   → 6，逐条确认全是 PASSED 用例名子串（无独立 FAILED）
门头 [N/112]                    → 112 条，编号互异 1..112
Function not implemented        → 0（本 runner 未出现 S28）
```

源码树与绿 sha 的等价性（报告提交后必核）：
`git diff --name-only b51cd03cf..HEAD -- . ':(exclude)fix-reports'` = **0 行**。

**已知缺口**：两个原语的 `--selftest` **不在 CI 见证面内**（`test_scan.py` 只跑各门，
`KOTLIN_AST_SELFTEST` / `KOTLIN_VALUEFLOW_SELFTEST` 在 CI 日志里 0 命中 —— 与既往
「门自证 stdout 不进 CI 日志」同族）。34/29 臂的见证在本报告的 L1/L1b（本地实跑 +
变异负控），CI 见证的是 112 门 + 344 夹具两侧不回归。要补也简单（在 `test_scan.py`
注册两条原语 selftest），但本轮 `test_scan.py` 是冻结面（任务书 §5），见 §6 注册项。


## 6. `test_scan.py` 注册项（交主会话，本支未改冻结文件）

| 项 | 内容 |
|---|---|
| 缺什么 | 两个原语的 `--selftest`（`_kotlin_ast` 34 臂 / `_kotlin_valueflow` 29 臂）不在 CI 见证面内 |
| 证据 | CI 日志 `KOTLIN_AST_SELFTEST` / `KOTLIN_VALUEFLOW_SELFTEST` **各 0 命中**（`test_scan.py` 只拉起各门脚本） |
| 建议注册 | 在 `test_scan.py` 加两条：跑 `python3 scripts/scan/_kotlin_ast.py --selftest` / `_kotlin_valueflow.py --selftest`，断言输出含 `KOTLIN_AST_SELFTEST=PASS` / `KOTLIN_VALUEFLOW_SELFTEST=PASS` 且 rc=0。**故意不断言臂数**（`(34 arms)` 一变就得同步改断言 = 自指脆弱断言） |
| 为什么本支不改 | 任务书 §5 明令 `test_scan.py` 冻结（并发批只见一个 owner 改注册文件），注册归并集收口的主会话 |

## 7. 残留（登记不修，各带三件）+ 未验证边界

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `mirror_category_route_check.reachable()` / `browser_destroy_wakeup_check.reachable()` 的**本地 workaround**（各自复制了 `if_blocks` + `guard_feasible(f"!({cond})")` 的判定） | 下一次因共享原语变更需要改这两处之一；或两者行为与本原语出现分歧 | 顺路收敛为委托 `_kotlin_valueflow.path_feasible`，删掉本地 `reachable()` 与对应 KDoc 的「shared primitive gap」段；收敛前先确认两门的现有臂在新路径上全绿 | 半径纪律（任务书 §2.3）：它们正确且自带臂，本轮只做原语层根治，不顺手扩大改动面 |
| R2 | `filepreview_print_bounded_check._reachable()` 的几何折叠（`const_bool(cond)` + 分支区间自己判死） | 该门下一次改判据，或 `path_feasible` 支持 branch-extent 语义 | 同上评估委托 | 它弃用 `path_feasible` 的动机（R3-t02 §6 登记）就是本缺陷；缺陷已修，但它的本地折叠在语义上等价且更严，本轮不动 |
| R3 | 本地未跑全量 `scan.sh` / `test_scan.py` | 分支 CI 已跑全套（`build-apk.yml:47,51`）；沙箱 S28 风暴 + 并发负载纪律 | 收口期由主会话在并集树上跑 `--full` | 施工期本地全量对分支零新增证据（skill §6.8） |

未验证边界：

- `enclosing_conditions` 的 `when` 分支未涉及取反，本轮未动；
- 真实树上唯一一处字面量复合条件（`ui/chat/ChatAssistantMessageUI.kt:819` 的
  `BuildConfig.DEBUG && false` kill-switch）落在 **then** 侧，本轮前后判定一致；
- 括号化的文本形态变化（`!a` → `!(a)`）对仓外消费方不可见（无外部调用者）。
