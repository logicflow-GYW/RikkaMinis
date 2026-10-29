# FIX-R5-C4 — 编程式跳转必须同时移动跟随态（不只是 catch-up 闸）

**分支** `fix/r5-c4-follow-state` @ `c9d6e29a`
**基线** main `f3def6ce`
**来源** R5 修后复扫批 · `reports/R5-C.md` C-08（PARTIAL）· 判级 `TRIAGE.md` C4 支
**改动面** 1 生产文件 +61 行（`ui/chat/ChatScreen.kt`）+ 1 新测试文件（`ProgrammaticJumpFollowTest.kt`，8 例）。`scripts/scan/**` / `scan.sh` / `test_scan.py` 零触碰 → 门 113 / 夹具 349 不变。

---

## 1. 目标

报告的 verdict：编程式跳转（聚焦跳转、回首页按钮）只置 `openCatchUpUserEngaged`，没有把 `followState` 从 FOLLOWING 切 DETACHED，于是

1. 流式期：`SIMPLE_FOLLOW` 收集器下一帧把视口拉回底部，跳转当场失效；
2. 空闲期：右下角回底按钮（`!followState.isFollowing` 才渲染）不出现，用户没有回到尾部的出路。

## 2. 先证（按语义，不按报告行号）

**2.1 合法转移表（`ui/chat/ChatFollowController.kt`，纯 reducer，325 行全读）**

| 事件 | mode | pendingBottomRequest |
|---|---|---|
| `InitialOpen` / `Send` / `Resume` / `Retry` / `FabDown` | → FOLLOWING | 各自一个请求 |
| `UserDragStart` | **不动** | 清空 |
| `UserDragEnd(atBottom)` | `atBottom ? FOLLOWING : DETACHED` | 清空 |
| `StreamRowsChanged` | 不动 | FOLLOWING 才置 `STREAM_PROGRESS` |
| `ImeViewportChanged` | 不动（刻意 no-op） | 不动 |

⇒ **`isFollowing` 变 false 的唯一入口是 `UserDragEnd(atBottom=false)`**。报告说「切 DETACHED」方向正确，且不需要新造状态。

**2.2 为什么两处跳转走不到那个入口**

`tracedScrollToItem`（`ChatScreen.kt:750` 命名 lambda）内部是 `listState.scrollToItem(idx, off)`。代码自己的注释（同一 lambda 上方）已经写明：编程式滚动会置 `isScrollInProgress = true` 但**不产生 DragInteraction**——「A gesture-only signal lets us distinguish …」。唯一 dispatching `UserDragEnd` 的地方是 `interactionSource.interactions.collect { it is DragInteraction.Stop }`。所以跳转后 mode 保持原值。**成立。**

**2.3 两条症状各自独立可构造**

- 流式：`SIMPLE_FOLLOW`（`:1385`）的 decline 列表按序为 `scroll-in-progress → not-streaming → user-dragging → not-following → empty-list → clamp-not-released → no-target`；FOLLOWING 时 `!followState.isFollowing` 不成立，而跳转后 `canScrollForward` 为真（内容在视口上方），`scrollIdx = safeBottomScrollIndex(total)` 非空 ⇒ `requestScrollToItem(bottom)`。触发它的是**跳转自身引发的 `visibleItemsInfo` 变更**，不是新数据 ⇒ 同帧/下一帧即拉回。**成立。**
- 空闲：FAB-DOWN 渲染条件 `!followState.isFollowing && contentOverflows.value && messages.isNotEmpty()`（`:4432`）⇒ 恒假。**成立。**

**2.4 覆盖面比报告宽（同一入口）**

`InputHistorySheet.onSelect`（`:4759` 附近）只置 `openCatchUpUserEngaged` + `pendingFocusId`，随后走的正是 FOCUS-MESSAGE 效果（`LaunchedEffect(pendingFocusId, flatItems)`）。**修在 FOCUS-MESSAGE 入口 = 一次覆盖搜索跳转、会话深链、历史面板跳转三个调用方**，不是三处各打一枪。

## 3. 修法（阶梯：停在「能一行」，但抽了一个纯判据）

**这改变什么用户行为？** 跳到旧消息后：流式期不再被一帧拉回底部；空闲期右下角出现「回到最新」按钮。

- 新增 `internal fun followEventForProgrammaticJump(targetIsNewestRow: Boolean): FollowEvent?`：
  - 目标是历史行 → `UserDragEnd(atBottom=false)`（DETACHED）；
  - 目标是**最新一行** → `null`（不动状态机）。⇒ DETACHED 读者跳到最新行**不会**被悄悄重新接管；只修「跳离底部后仍标 FOLLOWING」这一条。
- 两处入口（`FOCUS-MESSAGE`、`FAB-UP`）在 `tracedScrollToItem` **之前** dispatch（顺序是判据的一部分：收集器必须在跳转自身引发的 emission 上已经读到 DETACHED）。
- `openCatchUpUserEngaged = true` 保留不动：它挡的是 2.5 s 窗口内的 OPEN-CATCHUP 修正滚动，与跟随态是两道不同的闸，删任一道都会留缺口。
- `ponytail:` 三段已写在 KDoc 里：用「目标是否为最新一行」替代「跳转后哨兵可见」的真值（后者要等一次布局）；天花板 = 跳到最新行但该行高于一屏时视口停在行首仍留 FOLLOWING（与修复前一致，非回归）；升级触发 = 真机出现「跳最新行后被拉走」。

## 4. 验证

**4.1 规则层（真 reducer + 逐字切片，JVM 可跑）**

`ChatScreen.kt` 引 Compose，整文件不能 kotlinc；`extract_slice.py` 把 helper + KDoc **逐字**切出（断言两行、无 `import`/`@Composable`），与真 `ChatFollowController.kt` 一起编译：

| 断言 | 说明 |
|---|---|
| `jumpToHistory_endsDetached` | mode==DETACHED 且 isFollowing==false |
| `jumpToHistoryFromDetached_isIdempotent` | DETACHED 出发仍 DETACHED |
| `jumpToNewestRow_leavesStateMachineUntouched` | helper 返回 null |
| `jumpToHistory_clearsPendingBottomRequest` | Send 后的 pending request 被清（否则排队请求会在跳转后执行——同一症状的另一扇门） |
| `jumpToHistory_doesNotBumpRowRevision` | 不冒充数据修订 |

读数：**OK (8 tests)**。

**4.2 形状层（钉「两处入口都切了 follow 态」）**

- `bothJumpEntryPoints_dispatchTheJumpVerdict`：**缩进域**判据 —— 从 `tracedScrollToItem("FOCUS-MESSAGE")` / `("FAB-UP")` 的滚动行向上走到第一个缩进更浅的行（拥有该调用的 lambda），要求该窗口内出现 `followEventForProgrammaticJump(`（同款判据重写理由见 §5.2）；
- `verdictDispatch_precedesTheScrollAtBothEntries`：两处的 dispatch 位置严格早于 scroll；
- `helperDefinedOnce_inChatScreen`：helper 只定义一次（防两处各自复制一份）。

**4.3 变异臂（断言有牙，不是自指）**

第一组（针对实现是否真的切态）：

| 臂 | 变异 | 期望 | 读数 |
|---|---|---|---|
| A | 删掉两处 dispatch（= 基线形态） | 形状 2 条红 | `bothJumpEntryPoints_dispatchTheJumpVerdict`, `verdictDispatch_precedesTheScrollAtBothEntries` **红** ✓ |
| B | helper 恒 `null`（只置 engaged 不切态 = 报告描述的原始缺陷） | 规则 4 条红 | `jumpToHistory_endsDetached`, `...FromDetached_isIdempotent`, `...clearsPendingBottomRequest`, `...doesNotBumpRowRevision` **红** ✓ |
| C | helper 恒 `atBottom=true`（永不 DETACHED） | 规则 3 条红 | `jumpToHistory_endsDetached`, `...FromDetached_isIdempotent`, `...NewestRowUntouched` **红** ✓ |

第二组（针对判据重写 —— 见 §5 第一轮 CI 失败）：

| 臂 | 变异 | 期望 | 读数 |
|---|---|---|---|
| D | 删掉 FAB-UP 的 `openCatchUpUserEngaged = true` | 既有测试红 | `fabUpTapAndFocusJumpMarkTheUserEngagedBeforeScrollingAway` **红** ✓（**假锐版本此臂仍绿**，见 §5.2） |
| E | 在 FAB-UP 块内插入另一个 `tracedScrollToItem(` | 既有测试红 | 同上 **红** ✓ |
| F | 只删 FOCUS-MESSAGE 的 dispatch | 我的形状 2 条红 | `bothJumpEntryPoints...`, `verdictDispatch_precedes...` **红** ✓ |

每臂跑完 `git status` 干净（每条臂后 `git checkout --` 恢复）。窄 JVM 跑法：`ChatFollowController.kt` + 逐字切片 + 两个测试类，单 kotlinc / 单 JVM / `nice -n 10`，全程 ~40 s（无 gradle）。读数：**OK (18 tests)**（我的 8 + `OpenCatchUpGuardTest` 的 10）。

**4.4 窄门（只跑引用 ChatScreen.kt 的 14 道，未跑全量）**

`provider_remember_key_check` / `file_uri_parse_guard_check` / `legacy_pipeline_guard` / `prefs_listener_holder_guard` / `file_provider_paths_coverage_check` / `sysinfo_state_thread_check` / `share_dir_prune_check` / `stale_closure_guard` / `session_sheet_id_binding_check` / `chat_ui_interaction_check` / `deeplink_settings_routes_check` / `chat_session_id_binding_check` / `webview_holder_url_source_check` / `ime_inset_guard`
⇒ 改前 **14/14 绿**、改后 **14/14 绿**（`FAILED_GATES=0`）。`ime_inset_guard`（钉 ChatScreen 形状的那道）判据是文本输入点 + `Scaffold`/`imePadding`，本支未动该面 ⇒ 零影响，已实测。

## 5. CI

### 5.1 第一轮 `40854273585` @ `c9d6e29a` — **failure**

唯一失败 step：`Run unit tests (full suite)`；唯一失败用例：

```
OpenCatchUpGuardTest > fabUpTapAndFocusJumpMarkTheUserEngagedBeforeScrollingAway FAILED
    java.lang.AssertionError: engagement marking missing before tracedScrollToItem("FAB-UP"
```

被本支新增的 8 例（`ProgrammaticJumpFollowTest`）在 CI 日志里**逐条 PASSED**。

**根因不是实现错，是既有形状判据用固定字符窗口**：`OpenCatchUpGuardTest.assertMarkedBefore` 断言「marker 前 1200 字符内出现 `openCatchUpUserEngaged = true`」。本支在标记与滚动之间插入了必须的 follow-verdict dispatch（5 行注释 + 8 行代码），FAB-UP 处距离变成 **1224 字符 → 越窗 24 个字符**。契约本身（标记先于滚动、同一路径）仍然满足。

同族第 2 次：E5（2026-10-28）撞过 `provider_remember_key_check` 的 900 字符窗，越窗 4 个字符。已记规则 `LRN-20261029-OWTRN`：**形状判据禁用固定字符窗口，改用缩进域**。

### 5.2 判据重写（两次，第一次是假锐）

1. 第一版：「scroll 前最近一次标记 + 两者之间没有其它 `tracedScrollToItem(`」。跑变异臂 D（删掉 FAB-UP 的标记）—— **仍绿**。原因：更早的另一条路径（Resume banner 的标记，230 行之前）落进了「最近」位置，且中间恰好没有滚动。⇒ 假锐，丢弃。
2. 第二版（落地）：**缩进域** —— 从 scroll 行向上走到第一个缩进更浅的行（拥有该调用的 lambda），在这个窗口里要求标记存在、且无其它 `tracedScrollToItem(`。臂 D / 臂 E 双双转红，真树绿。

### 5.3 第二轮 `40857585328` @ `b7b40763` — **success**（18.3 min，attempt 1，10:26:30→10:44:47Z）

| 判据 | 读数 |
|---|---|
| `head_sha` | `b7b40763d926ce0770c45c9213c04e0826a50ca1` **逐字符一致**于本地 HEAD |
| 27 步 | 24 success + **2 skipped**（`Move the rolling tag android-latest`、`Publish to Releases` —— 均 main 门控）+ post 步，**0 非 success** |
| 门集 | `Summary: 113 passed, 0 failed` ×1；`[N/113]` 113 块、编号互异 113 ⇒ 门集合完整 |
| 夹具 | `ALL 349 CASES PASS` ×1（门/夹具数与基线一致，本支未碰 `scripts/scan/**`） |
| 单测分片 | 六条 `Summary: N passed, 0 failed`（25/16/44/19/30/…）全 0 failed |
| 本支新用例 | `ProgrammaticJumpFollowTest` **8 PASSED / 0 FAILED** |
| 相邻既有用例 | `OpenCatchUpGuardTest` **10 PASSED / 0 FAILED**（第一轮唯一失败项，判据重写后转绿） |
| 编译 | `^ e: ` **0** |
| 真失败 | ` FAILED$` 行 **0**；`error:` 8 行全为 workflow 脚本自身 `echo "::error::…"` 回显 |
| 日志 | 1,627,225 B，NUL **0** |

## 6. 残留与登记（同族顺带发现，只登记不修）

| # | 发现 | 判级 | 触发条件 |
|---|---|---|---|
| R1 | `RETRY-FROM-MSG` / `EDIT-MSG` / `RERUN-FROM-TOOLRUN` / `INLINE-RETRY-LAST` / `RERUN-FROM-AGG-TOOL` 五处 `tracedScrollToItem(bottom)` 是**滚向底部**的编程式滚动，同样不产生 `DragInteraction.Stop`：DETACHED 时点这些按钮，视口滚到底但 mode 仍 DETACHED ⇒ 之后流式不再跟随（用户需拖一下或点 FAB 才恢复）。方向与本支相反（跳离底部 vs 回到底部），但同属「编程式滚动不更新 follow 态」家族。 | P2 | 真机出现「点重试/编辑后输出不再自动跟随」反馈时，按 `followEventForProgrammaticJump` 的同款形态加 `UserDragEnd(atBottom=true)`（或复用 `FabDown`） |
| R2 | `:1311` 的 IME 变化补丁（`!isFollowing && isNearBottom → UserDragEnd(atBottom=true)`）是对 R1 的零星自愈：只在 IME 位移时生效，不做通用收敛。现有设计，不动。 | P3 | — |
| R3 | `LaunchedEffect(pendingFocusId, flatItems)` 内读的 `isStreaming` 是组合期捕获值（key 不含它）。只影响诊断日志字段，不参与状态转移（reducer 不看 isStreaming）。 | P3 | 需要精确归因流式期变体时再看 |
| R4 | 真机未验：旋转/深链进入 + 流式期跳转的组合只有编译级 + JVM 级 + 源码形状证据；MediaStore 无关，但 Compose 布局时序（`scrollToItem` 与 `visibleItemsInfo` emission 的先后）由 CI 编译保证不了。 | P1 观测 | 发版后真机复现「跳转后瞬间回底」或「空闲期无回底按钮」时报回本支 |

## 7. 半径

- 生产文件改动：**1 个**（`ChatScreen.kt`）。新增测试 1 个。无 i18n、无门脚本、无 manifest、无 gradle。
- **改既有测试 1 个**：`OpenCatchUpGuardTest.assertMarkedBefore`（辅助函数判据从「1200 字符窗」换成「缩进域」，语义更锐、覆盖面不减 —— 臂 D/E 验证）。改的理由与证据在 §5.1/§5.2；不改它 CI 红，改门判据而不是改实现是因为**契约（标记先于滚动、同一路径）依然满足**。
- 未新增状态、未新增事件枚举、未改 reducer（`ChatFollowController.kt` 零触碰）——只用了既有 `UserDragEnd`。
- `rg` 全 caller 检查：`followEventForProgrammaticJump` 仅 1 定义 + 2 调用点；`dispatchFollow` 调用点 8 → 10（+2，均为本支入口）。

## 8. 停止节点

分支 CI 绿 + `head_sha` 逐字符一致 = 停止。**不合 main**（合并起手权在用户）。
