# FIX-R3-t05 — `chat_menu_prefs_listener_check.py` + `katex_ready_race_check.py`

分支 `fix/r3-menuprefs-katex`（基线 main `f0f14d46d`）。**生产 `.kt` 0 行改动**；`scan.sh` / `test_scan.py` / `_kotlin_*.py` / 其他门脚本零触碰；门数 112 / 夹具 344 不变。

## 1. 门与任务

| 门 | 行数（修后） | 新增判据 | 对应见证 |
|---|---|---|---|
| `chat_menu_prefs_listener_check.py` | 927 → 1211 | arm2b（投影对称）/ arm7b（闩锁后发布）/ arm8b（isEnabled 结果必须成为 State） | #97 dual-gate / #98 symmetric / #99 symmetric |
| `katex_ready_race_check.py` | 847 → 1144 | A5（渲染进程死亡信号必须到达 gate 且复位）/ A6（awaitReady 必须回答判决）/ A7（renderInternal 必须消费答案）/ B5（rename 必须写别名） | #100 dual-gate / #101 symmetric / #102 symmetric |

改动面 = 2 个门脚本（+580/−3）+ 本报告。**radius**：只修 §3 点名的 6 条见证所在判据；顺带发现登记 §7，未顺手改。

## 2. 失效轴（与前三批同源，本轮落在「缝」上）

| 轴 | 见证 | 旧判据为何瞎 |
|---|---|---|
| dual-gate（X/Y 门之间的空白） | #97 | arm8 只查 `AppLogger.isEnabled` **子串出现**；`loggingEnabled =` 那一半谁都不查（arm1-3 的 `result_consumed` 只用于 ChatMenuSettingsScreen） |
| dual-gate | #100 | A1 只钉 `onPageFinished → markPageFinished`；Y 门（browser_destroy_wakeup）钉的是 BrowserUseManager/KaTeXView 的 `onRendererGone`/`releaseLatch`；pool 的 `onRendererGone` 有没有把死亡告诉 gate，两门都不查 |
| symmetric（门内对称轴） | #98 | arm7 只查 `SoulStore.load` **之前**的早退；load **之后**的发布被 `if (!loaded)` 挡住完全在视野外 |
| symmetric | #99 | arm2 只查 `resolveOrder(prefs)` 被赋给 `menuOrder`（`result_consumed`）；首次渲染的 `.filter { defaultMenuVisible != false }` 投影在 listener 里被剥掉照样绿 |
| symmetric | #101 | A4 只钉 `KatexReadyGate.await` 内部；调用侧 `awaitReady()` 恒 `return true` 不在视野里（报告 self_doubt 还给出 `if (false && !awaitReady())` 变体，同样绿） |
| symmetric | #102 | B1–B4 只钉读路径（`resolveKey` / getter）；**写入侧** `rename()` 把 store 搬走却从不写 `aliases`，读路径全部照绿 |

## 3. 见证重放表（修前反攻）

全部 6 条在工作树上手工构造最小编辑（`probe/t05-w97..w102.patch`），跑 `gates_involved` 每一道门，然后 `git checkout` 还原并 sha256 校验：

| 见证 | kind | gates_involved | 落点 | 修前 verdict | rc |
|---|---|---|---|---|---|
| #97 | dual-gate | chat_menu_prefs / filepreview_print_bounded / share_dir_prune | LogManagementScreen.kt:112 listener 调 isEnabled 但不赋值 | **BLIND_CONFIRMED** | 0/0/0 |
| #98 | symmetric | chat_menu_prefs | SoulSettingsScreen.kt:115 发布块裹进 `if (!loaded)` | **BLIND_CONFIRMED**（主会话 NEEDS_MANUAL，本支手工重放补证） | 0 |
| #99 | symmetric | chat_menu_prefs | ChatMenuSettingsScreen.kt:151 listener 去掉 defaultMenuVisible 过滤 | **BLIND_CONFIRMED**（同上） | 0 |
| #100 | dual-gate | katex_ready_race / browser_destroy_wakeup | KatexWebViewPool.kt:223 注释掉 markRendererGone | **BLIND_CONFIRMED**（同上） | 0/0 |
| #101 | symmetric | katex_ready_race | KatexWebViewPool.kt:313 `return failure == null` → `return true` | **BLIND_CONFIRMED** | 0 |
| #102 | symmetric | katex_ready_race | ChatViewModelStore.kt:193 注释掉 `aliases[from] = to` | **BLIND_CONFIRMED**（同上） | 0 |

主会话预证 2 条（#97/#101）+ 本支手工重放 4 条 = **6/6 BLIND_CONFIRMED，0 条 CAUGHT（无见证不成立），0 条 NOT_REPRODUCIBLE**。机械数据：`probe/t05-pre.json`。

## 4. 修法（判据从「拼写存在」→「效果达成」，全部走共享原语）

**chat_menu_prefs_listener_check（+9 判据函数行，3 个新检查点）**
- **arm8b（#97）**：`_enabled_state()` 从 `var X by remember { mutableStateOf(AppLogger.isEnabled(…)) }` **派生**开关 State 名（不写死 `loggingEnabled`），要求 listener 体内 `isEnabled(` 的值经 `result_consumed`（复用既有值流判定，含一跳局部委托）成为该 State 的新值；声明缺失 → fail-closed 红。
- **arm7b（#98）**：`blocked_publications()` 把「闩锁」从树里派生（`_state_decls` 找 `var X by remember` 且在 effect 内被赋 `true`），将闩锁代入已知值后对每条状态赋值的 `enclosing_conditions` 折叠——条件折叠为假 = 该发布是死代码（重跑时闩锁已 true）。
- **arm2b（#99）**：`projection_mismatch()` 以初始 `remember { mutableStateOf(…) }` 里的投影链为权威副本（`_chain_suffix` 取 `read(…)` 闭合括号后的后缀，空白/尾逗号归一），listener 右值必须携带同一条链（`_suffix_through` 支持 `val r = read(…); target = r.filter {…}` 一跳委托）。初始读取无投影 → 无约束，不误伤。

**katex_ready_race_check（4 个新 CRIT）**
- **A5（#100）**：`onRenderProcessGone`/`onRendererGone` 必须在活路径上调用 `readyGate.markRendererGone(…)`，且 gate 的 `markRendererGone` 必须真的 `ready = false` + `completeExceptionally`（复位效果，不是拼写）；钩子不存在 = fail-closed 红。
- **A6（#101）**：`awaitReady()` 的返回表达式不得是常量（`return true` 红）、value_sources 必须含 `failure`、且不得出现 `failure != null` / `!failure` 极性反转。
- **A7（#101 的 self_doubt 闭合）**：`awaitReady()` 的调用点必须落在**可行**（`guard_feasible`，`if (false && …)` 红）且**会退出**（`returns_exiting`）的 `if (!awaitReady())` 守卫里；裸调用（答案丢弃）红。
- **B5（#102）**：`rename(from, to)`（参数名从树里 `_params` 派生，不写死）必须在活路径上写 `aliases[from] = <值来源含 to>`；不写 = 草稿 key 转正瞬间 resolve 到新建空 store。

共享原语使用：`code_view`/`mask_code`（词法）、`match_bracket`/`if_blocks`/`enclosing_conditions`/`stmt_spans`/`const_bool`/`strip_parens`（AST）、`value_sources`/`path_feasible`/`guard_feasible`/`returns_exiting`/`expr_end`/`contains_ident`（值流）；本支**没有**新增任何自写词法器/括号计数器。KotlinLexError → fail-closed 红。

## 5. 四层证据

1. **本门**：真树 `rc=0`（两门）；selftest 全臂带牙——chat 31 臂（26 + r3-97 / 97-control / 98 / 99 / 99-control 5 新臂；新臂恰红在自己的 arm8/arm7/arm2 上，对照臂绿）、katex 27 臂（原 20 条 `expect()` 臂 + A5 / A5b / A6 / A6b / A7 / A7b / B5 共 7 新臂；arm 0/C/D 三条非 expect 臂不变）。臂数只增不减，收尾标记 `CHAT_MENU_PREFS_LISTENER_SELFTEST=PASS` / `KATEX_READY_RACE_SELFTEST=PASS` 原样保留（`test_scan.py:2764/2808` 的冻结断言依赖它们），另加 `*_SELFTEST_ARMS=N` 行供主会话注册。
2. **邻门**：`filepreview_print_bounded_check` / `share_dir_prune_check` / `browser_destroy_wakeup_check` / `katex_bridge_arity_check` 全 rc=0（dual-gate 对侧门不受影响，见 §6 说明）。
3. **全量**：逐门独立进程循环（`probe/t05_gate_run.py`，断点续跑版）**109/109 rc=0**（沙箱本窗口并发会话拥挤，循环被杀 3 次、断点续跑完成；进度台账 `probe/t05-gate-done.json`）。`test_scan.py` 结果见 §6（后台跑法，沙箱可能杀——跑了就贴，跑不动交 CI）。
4. **CI**：分支 push + 手动 dispatch，读数回填 §6。

## 6. CI（回填区）

- run id：**待回填**
- head_sha 三方一致：待回填
- `Summary: 112 passed, 0 failed` 恰一条 / `ALL 344 CASES PASS` 恰一条 / `^ e: ` 0 / 门头 112 互异 / 唯一 skipped = Publish：待回填

**test_scan 本地读数**（后台跑法，完成即贴；被沙箱杀则注明交 CI）：
```
（待回填）
```

## 7. 残留（全部 P2 登记，未顺手改）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_suffix_through` 只解析一跳局部委托；两层以上（`val a = read(); val b = a; menuOrder = b.filter{}`）判红（fail-closed 误报方向安全） | 出现两层局部中转的合法重构被误红 | 上收 `value_sources` 的标识符绑定回溯为共享 API（B7-t13 已提出同一缺口：`_binding_rhs`） | 真树与自测臂数中不存在两层中转；判红方向安全 |
| R2 | `_chain_suffix` 只识别 `read(…)` 闭合后的直接后缀链；`buildString`/`map{}` 等包裹形态不覆盖 | listener 重读被包进集合构建器 | 扩后缀提取为「首个 `(` 之后全链」并按共享 scope_calls 剥皮 | 投影判据当前只对 3 个已知 read 生效，形态固定 |
| R3 | arm7b 只折叠「闩锁在 effect 内被赋 true」的形态；`if (loaded == false)` / `when(loaded)` 包裹未覆盖 | 出现 `== false` 或 when 形态的闩锁守卫 | 把 env 代入后交给 `guard_feasible`/`const_bool` 的完整折叠（`== false` 现已能折叠，when 分支需 when_branches） | 现仓无该形态；折叠层已覆盖 `!loaded` |
| R4 | A5 不校验 `markRendererGone` 在 synchronized(lock) 块内复位（A3/A4 那样的锁一致性） | markRendererGone 被改成锁外翻转 | 复刻 A3 的 `val d = synchronized(lock){…}` 抽取判据 | 单写者（主线程回调）场景，锁竞态窗口极窄 |
| R5 | B5 不校验 `SessionIdAliases.register` 与日志行的存在（生产 rename 还做两件外围事）；也不查 `stores[from]` 搬运 | rename 的搬运/注册语义变更 | 各加独立判据 | 本波只修「别名不写」这一条缝；外围是另一族缺陷 |
| R6 | 双门见证的对侧门（filepreview_print_bounded / share_dir_prune / browser_destroy_wakeup）不承担这些缝——修后它们仍 rc=0。这不是漏洞：三门的锚点面（print 分页/分享目录/wakeup latch）与缝的落点（listener 回调体/gate 复位）不相交，缝应由锚点在 listener/池侧的门管。归档为「谁都不查 → 已查」而非「两门都红」 | 对侧门扩判据覆盖这些文件时 | 在对侧门的判据里显式跳过本支已覆盖面，避免双报 | 门数不变约束 + radius 纪律 |

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t05
# 1. 修前重放（已跑，产物在 probe/）
python3 probe/t05_replay.py pre
# 2. 单门 + 自测
python3 scripts/scan/chat_menu_prefs_listener_check.py . ; python3 scripts/scan/chat_menu_prefs_listener_check.py --selftest
python3 scripts/scan/katex_ready_race_check.py .    ; python3 scripts/scan/katex_ready_race_check.py --selftest
# 3. 修后反攻（应全部 CAUGHT，红在 r3 新判据上）
python3 probe/t05_replay.py post
# 4. 全量（分段续跑；沙箱被杀后重跑同一命令自动续）
python3 probe/t05_gate_run.py            # 或 --max N 分段
# 5. 见证补丁文件
ls probe/t05-w*.patch
```
