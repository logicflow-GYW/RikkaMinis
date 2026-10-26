# FIX-R3-t21 — deeplink_settings_routes + open_url_broker_scheme 两门判据加固

> 第三轮（dual-gate bypass）收口批支线。分支 `fix/r3-deeplink-openurl`，基线 main `f0f14d46d`。
> 改动面：仅 2 个门脚本，+1204/−14（`deeplink_settings_routes_check.py` +767/−13，`open_url_broker_scheme_check.py` +437/−1）。
> 生产 `.kt` 0 行；`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰；门 112 / 夹具 344 不变。

## 1. 门

| 门 | 锚面 | 改动 |
|---|---|---|
| `scripts/scan/deeplink_settings_routes_check.py` | 深链 dispatch 面：`MainActivity.kt` 热启动 `handleDeepLink` / `AppNavigation.kt` 冷启动 `when (initialDeepLink)` / `ChatScreen.kt` 消费面 | +767/−13，新增 4 组 effect 判据函数 + 17 条自测臂（34→51） |
| `scripts/scan/open_url_broker_scheme_check.py` | open-url broker 面：`MainActivity`/`AppNavigation` 深链臂 + `OpenOffloadHandler.kt` | +437/−1，新增 U9/U9b/U10/U10b/U11/U11b 六条判据（17→23） + 49 条自测臂（51→100） |

## 2. 轴

| 见证 | kind | 失效轴 | 旧判据为何瞎 |
|---|---|---|---|
| #18/#19（热/冷启动 env-var create 写入丢弃） | dual-gate | dataflow | 只锚「导航发生」/「分支存在」，`setPendingEnvVarCreate` 被删仍绿（payload 静默蒸发） |
| #20（ChatScreen 消费 preview 但不建 holder） | dual-gate | 效果达成 | 只锚「consume 调用存在」，WebViewHolder 不建、fullscreen=false 仍绿 |
| #57（相机权限极性反转） | dual-gate | 效果达成 | 只锚「granted 检查存在」，`if (!granted) launchCamera()` 极性反转仍绿 |
| #33（热启动 preview publish 丢弃） | dual-gate | dataflow | 同 #18 族：publish 调用被删仍绿 |
| #34（冷启动 OpenSession 导航停尸 `if (false)`） | dual-gate | 恒假守卫 | 只锚 `safeNavigate` 调用存在，调用被死守卫短路仍绿 |
| #35（纯 URL else 臂回退裸 `Intent.parseUri(url, 0)`） | symmetric | 单门内对称失衡 | then 臂有 isIntentUri 分流 + 净化，else 臂对称面无判据 |
| #58（冷启动 NewChat 草稿 minting 硬编码 + 导航停尸） | dual-gate | dataflow + 恒假守卫 | 只锚 `nextDraftId = { … }` lambda 存在，值被换成常量仍绿 |

## 3. 见证重放表（pre-fix 反攻）

**重放方式**：`probe/t21_mut.py` 持有 9 个字节级变体（#58 原报告补丁与现树字面量不再匹配 → 拆成等价变体 **w58b**（draft id 硬编码 `"__new__orphan"`）/ **w58c**（`seedHtmlPreview` 注入 lambda 改 no-op））。每个变体 apply → 跑门 → restore，一次一个；`t21_pre.sh` 全程在 **stash 干净基线** 上执行（两门改动先 `git stash push` 再跑，杜绝混合态——先前一次教训：混合态日志 deeplink 已修、open_url 仍旧，不能当反攻证据）。

**pre-fix 判决（`probe/t21-pre.json`）**：**17/17 (witness, gate) 对全 BLIND（rc=0），9/9 见证 BLIND_CONFIRMED**。含 w18–w58c 全部九个变体 × 各自 `gates_involved` 门对（旧 open_url 17 criteria green × 8、旧 deeplink PASS × 7、file_provider 0 violation、chat_session_id_binding all satisfied）。

**post-fix 判决（`probe/t21-post.json`）**：**9/9 CAUGHT，红在自家新判据上**：

| 变体 | 门 | rc | 红在 |
|---|---|---|---|
| w18 | deeplink | **1** | C12：热启动 CreateEnvironmentVariable 臂丢 `setPendingEnvVarCreate` |
| w18 | open_url | 0 | 归属说明：env-var payload 送达是 deeplink 判据面（见 §4 open_url 分工） |
| w19 | deeplink | **1** | C12：冷启动同款 |
| w20 | deeplink | **1** | C14：pendingHtmlPreview 被消费但 WebViewHolder 不建 + fullscreen=false |
| w57 | deeplink | **1** | C15：相机极性反转（`launchCamera` 落在 !granted 路径且无 launcher 臂） |
| w33 | open_url | **1** | U9：热启动 OpenHtmlPreview 臂开聊天但不 publish pending preview |
| w33 | deeplink | **1** | C12 同款（真 dual-gate 双红） |
| w34 | open_url | **1** | U10b：冷启动 OpenSession 臂的导航调用落在恒假守卫后（不可达） |
| w34 | deeplink | **1** | C13 同款（真 dual-gate 双红） |
| w35 | open_url | **1** | U11/U11b：纯 URL 臂回退 `Intent.parseUri(url, 0)` 未净化（双判据齐红） |
| w58b | deeplink | **1** | C16：`nextDraftId = { "__new__orphan" }` 非来自 ComposerDraftStore |
| w58c | deeplink | **1** | C16：注入 `seedHtmlPreview` lambda 变 no-op 不送达 |

restore 后 `git status --porcelain src/` 逐变体 = 0（`probe/t21-post.log` 9 条 GIT-STATUS 0）。逐变体补丁固化在 `probe/t21-w{18,19,20,33,34,35,57,58b,58c}.patch`。

## 4. 修法（判据从「存在」改到「效果达成」，全部走共享原语）

**deeplink_settings_routes_check**（四组，`main()` 接线 :1391-1394）：
- `judge_dispatch_effects(sources)`（C12）：热/冷启动每个 `DeepLinkAction` 分支体内，`setPendingEnvVarCreate` / `setPendingHtmlPreview` 必须**可达调用**（`path_feasible` 恒假折叠 + `call_args` 实参核对），导航不得是分支唯一效果。
- `judge_html_preview_delivery(chat, rel)`（C14）：`ChatScreen` 消费 `pendingHtmlPreview` 后必须**真建** `WebViewHolder` 且 `htmlPreviewFullscreen = true`（消费 ≠ 显示）。
- `judge_camera_permission_polarity(chat, rel)`（C15）：`launchCamera()` 必须落在 granted==true 路径、`cameraPermissionLauncher.launch(…)` 必须落在 !granted 路径（极性判据，不是「检查存在」）。
- `judge_cold_start_draft_wiring(nav, cold)`（C16）：`nextDraftId` lambda 的**值来源**必须真解析到 `ComposerDraftStore.nextDraftId(context)`（`value_sources`）；注入的 `seedHtmlPreview` lambda 必须真送达 `setPendingHtmlPreview`。
- 新增 17 条臂（T21-01..T21-18 含 1 条 clean），全部「变异臂恰红 + 真树绿」。

**open_url_broker_scheme_check**（三条判据函数，`main()` 接线 :1729-1745）：
- `analyse_preview_arm`（U9/U9b）：`MainActivity` OpenHtmlPreview 臂必须 publish（`setPendingHtmlPreview` 可达）且 payload 三实参齐（sessionId/resourcePath/title）。
- `analyse_nav_arms`（U10/U10b）：每个 nav 臂的导航调用必须**可达**（`path_feasible` 折叠恒假守卫，`if (false)` 即红）；cases：MainActivity=OpenSession/OpenTerminal/OpenHtmlPreview/CreateEnvironmentVariable，AppNavigation=OpenSession/OpenTerminal/CreateEnvironmentVariable。
- `analyse_intent_symmetry`（U11/U11b）：`OpenOffloadHandler` 每个 `Intent.parseUri(…)` 站点必须 scheme 规范化 + flag 守卫 + sanitizer 送达；U11b 断言 then/else 两臂**对称**（纯 URL 臂回退 parseUri ⇒ U11、U11b 双红）。
- 新增 49 条臂，同族带牙；`KotlinLexError` fail-closed。

**dual-gate 分工声明（任务书 §2.2 允许项）**：w18/w19/w20/w57/w58b/w58c 的第二门 rc=0 属**分工而非漏检**——env-var payload、preview 显示、相机极性、草稿 minting 的效果面归 deeplink；open_url 判据面 = preview publish / nav 可达 / parseUri 对称。无重复覆盖（第三轮 spec §3）。

## 5. 四层证据

1. **本门**：真树双 rc=0；`--selftest`：`DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS (51 arms)`、`OPEN_URL_BROKER_SCHEME_SELFTEST=PASS (100 arms)`（收尾标记稳定，供 test_scan 冻结断言）。
2. **反攻复证**：pre 17/17 BLIND（stash 干净基线）→ post 9/9 CAUGHT，逐变体红在自家判据，restore 全 True；JSON：`probe/t21-pre.json` / `probe/t21-post.json`。
3. **邻门**：8 个语义相邻门 rc=0（`log_url_redaction_guard` / `offload_cli_args_check` / `offload_host_path_check` / `session_provider_enabled_check` / `provider_write_rules_check` / `webview_holder_url_source_check` / `file_uri_parse_guard_check` / `mirror_category_route_check`）+ 反攻内 `file_provider_paths_coverage_check` / `chat_session_id_binding_check` rc=0。
4. **全量**：逐门循环两次被沙箱 ENOSYS 风暴打断（S28 变体：第一次 `>/dev/null` 重定向间歇失败产生 90 假红、第二次命令替换形态跑到 30/111 全绿后死于 `four_way_sync_check` 的 `makedirs('/')` 沙箱交互）——按既定纪律**降级：全量证据由分支 CI 承担**（本仓 CI 跑 scan.sh + test_scan.py）。test_scan.py 本地后台尝试在 exec_gate_release 段停滞 40+ 分钟（同窗口 ENOSYS）→ 已终止；CI 侧 `ALL 344 CASES PASS` 恰一条为权威全量读数。
5. **CI**：run `40314183278` **success**，head_sha 与本地 HEAD 逐字符一致（见 §6）。

## 6. CI（已回填）

- **run** `40314183278` / **#2740** / job `121542522653` / attempt 1 **success**（workflow_dispatch 2026-10-26T11:19:16Z，本地 HEAD `460b421e5753a36c381f7b96d773ff3534bebfd0` 与 CI head_sha **逐字符一致**）
- 日志 `probe/t21-ci-job.log`（1,487,558 B / NUL 0）逐条核：
  - `Summary: 112 passed, 0 failed` 恰 **1** 条
  - `ALL 344 CASES PASS` 恰 **1** 条
  - 门头 `[N/112]` 互异 = **112** 个
  - `^ e: ` = **0** 条（Kotlin 编译零错误）
  - 6 条含 FAILED 的行逐条确认全是 PASSED 用例名子串（RealAgentAdapterSkeletonTest slot-denied / ChatAgentTraceObserverSchemaTest ×4 / TerminalErrorSurfacedTest legal-FAILED-terminal）
  - 两门 selftest 标记各 1（⇒ 51 臂 / 100 臂在 CI 真跑）
- **停止节点达成：分支 CI 绿，未合并 main（起手权在用户）**

## 7. 残留（P2，各带三件）

- **R1** deeplink C12/C13 的 DeepLinkAction case 覆盖列表为显式枚举：新增 DeepLinkAction case 而不扩判据 → 静默盲区。触发条件：生产代码新增 DeepLinkAction sealed 成员。触发后方案：judge_dispatch_effects 加 fail-closed 未知 case 检查。搁置理由：sealed 枚举封闭，新增是显式动作必同步改 dispatch when。
- **R2** open_url U10b NAV cases 不含 OpenHtmlPreview/NewChat（冷启动 preview 送达归 deeplink C16）：若冷启动 preview 送达改走 nav 调用形态则 open_url 不判。触发条件：改冷启动 preview 送达机制。触发后方案：U10b cases 扩条目并与 deeplink 协调覆盖面。搁置理由：当前送达走注入 lambda，形态稳定。
- **R3** `_NAV_CALL` 只匹配 `navController.safeNavigate(`/`navigate(` 显式接收者：改用 `Navigation.findNavController(...).navigate(` 形态会漏。触发条件：树里出现该形态。触发后方案：扩接收者白名单。搁置理由：当前 dispatch 面全走 NavController 扩展函数。
- **R4** C15 只锚 `ChatScreen.kt` 单文件：拍照入口迁出即失明。触发条件：cameraPermissionLauncher 迁移。触发后方案：文件集扩成列表。搁置理由：当前唯一站点。
- **R5** #58 原报告补丁（AppNavigation:448-449 三编辑）与现树字面量不再匹配，已拆 w58b/w58c 两等价变体重放；其第三编辑（NewChat 臂 `if (false)` 导航停尸）未单独重放——与 w34 同形态同判据（U10b path_feasible）已覆盖。触发条件：需审计原补丁第三编辑独立有效性。触发后方案：按现树字面量重写第三变体。搁置理由：同形态已验证。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t21
# pre-fix（干净基线反攻，先 stash 两门改动）
git stash push -m r3t21-gates -- scripts/scan/deeplink_settings_routes_check.py scripts/scan/open_url_broker_scheme_check.py
sh probe/t21_pre.sh && git stash pop
# post-fix 反攻
sh probe/t21_post.sh
python3 probe/t21_jsonify.py     # 生成 probe/t21-pre.json / probe/t21-post.json
# 自测
python3 scripts/scan/deeplink_settings_routes_check.py --selftest   # …(51 arms)
python3 scripts/scan/open_url_broker_scheme_check.py --selftest     # …(100 arms)
```
