# FIX-META1028-C6：模型路由 2 条修复（F-05 / F-06）

来源：货架报告 B03 F-05 / F-06（`/var/minis/shared/work/meta-audit/reports/Report__3_.md`）。
分支 `fix/meta1028-c6-model-routing`，基线 main `bfb5b0e6`（报告基线 `47c4bf4b`）。

**这改变什么用户行为？**
- F-05：清空 `session.primaryModel`（或默认分组失效）之后，顶栏与模型选择弹窗不再显示上一个已失效分组的高亮；下一条用户消息不再按那个已解绑分组的成员轮转，而是按新解析出来的直达 entry 走。此前用户会看到「顶栏写着分组 B、实际在跑 entry X」，且下一次发送仍落在 B 的成员上。
- F-06：loadBalance 分组轮转时若 `ProviderFactory.create` 抛异常（坏的 key / 不可达的 base URL / 已下线的 provider 类型），这一次轮转**整体作废**、状态完全不动，顶栏仍显示原来那个真正在服务的成员，并在日志留下一行 WARNING。此前会变成「顶栏显示新成员、实际仍在用旧 provider 发请求」，且在无人干预的情况下可一直持续到会话结束。

## 1. 先证（各条回真树核实，不采信报告行号）

| ID | 先证动作 | 真树读数 |
|---|---|---|
| F-05 | `rg -n "applyNewChatDefaultModel\|applyDefaultModelFallback\|pendingEntryOverride" src/android/` | 该函数体（`ChatSessionLifecycle.kt:1296-1330`）在 `decideSessionModelResolution(...)` 返回 `SessionModelResolution.Use` 之后依次写 `currentModel` / `_modelName` / `_activeEntryId` / `_providerName`，然后 `currentProvider = ProviderFactory.create(...)`、 `return true` —— **全函数体 0 处 `_selectedGroupId` / `_selectedGroupName` / `pendingEntryOverride`**。三个调用点（`ChatSessionLifecycle.kt:343` 草稿分支、`:2822` 配置加载后重试、`ChatModelRouting.kt` 的 `applyDefaultModelFallback` 尾部）全在「分组没产出模型」时才到达 ⇒ 报告口径成立。 |
| F-06 | 读 `ChatModelRouting.kt:534-540` | 原文顺序 = `currentModel = effectiveModel` → `_modelName` → `_providerName` → `_activeEntryId` → `currentProvider = ProviderFactory.create(instance, apiKey, effectiveModel, context)`；`rotateForNewTurn` 内部**无 try/catch**。⇒ 唯一可抛的调用排在最后，四个显示字段已先行发布。|

**落点二选一 vs 都补 → 结论：只补 `applyNewChatDefaultModel`（真源）。**
`applyDefaultModelFallback`（`ChatModelRouting.kt:233-247`）自己只干两件事：默认分组能解析 ⇒ 写 `_selectedGroupId` 并 `return true`；否则 `return applyNewChatDefaultModel()`。它**从不写 `_activeEntryId` / `_modelName` 之外的状态，也从不改绑定模式**——所有「不经过分组的发布」都收敛在 `applyNewChatDefaultModel` 一个出口。在 fallback 里补清空 = 与唯一出口重复写同一组字段；而在唯一出口补 = 三个调用点（`343` / `2822` / fallback）全覆盖，且不会多出一条「分组解析成功时也在 fallback 里清一遍」的死代码。故**只补一处**。

## 2. 修法

**F-05（`ChatSessionLifecycle.kt`，`applyNewChatDefaultModel` 成功分支，+22 行注释 + 3 行写入）**
在 `SessionModelResolution.Use` 之后、发布 `currentProvider` 之前，按「反操作」逐字补：
```
_selectedGroupId.value = null
_selectedGroupName.value = ""
pendingEntryOverride = null
```
写成**字面量**而不是从某个 plan 值派生，是刻意的：清空是「受门控的发布」的逆操作，C-SEL-1 要求非字面量的分组写入必须能被证明「解析确实说了 yes」——而清空永远不该被这样证明。

**F-06（`ChatModelRouting.kt`，`rotateForNewTurn` 尾部，+43/−4 行）**
决策本身抽成纯函数（`ModelSelectionPublish.kt`）：

```kotlin
internal fun <P> planTurnRotation(
    current: SessionModelSelection,
    targetEntryId: String, targetModelId: String,
    targetModelName: String, targetProviderName: String,
    created: P?, createError: String?,
): TurnRotationPlan<P>
```
`applied=false` ⇒ `state === current`（失败路径逐字段不动）、`provider=null`、`reason` 非空；`applied=true` ⇒ 只接管四个**身份**字段（`modelId` / `modelName` / `providerName` / `activeEntryId`），分组字段从 `current` 带过来（轮转发生在分组内部，它恰恰不能退出分组模式——那是 F-05 那条发布路径的职责）。

调用点改成：先 `try { created = ProviderFactory.create(...) } catch { createError = ... }`，再 `planTurnRotation(...)`，`if (!plan.applied) { Log.w(...); return }`，最后一次性赋值（含 `currentProvider = plan.provider`）。

**半径纪律**：Backlog 2421 的「9 处 `ProviderFactory.create` 用于 metadata-only 构造」P2 项**未动**；`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰；无其他文件改动。

## 3. 装置与读数

新文件：`ModelSelectionPublish.kt`（纯 Kotlin，无 Android 类型，JVM 可编译）+ `ModelRoutingPublishTest.kt`（7 例）。探针目录 `/var/minis/shared/work/meta-audit-1028/probe-c6/`。

**7 例 × 分层**：值层 3 例直接打 `planTurnRotation` 的输入/输出（失败不动、产出空 ≠ 成功、成功只接管身份字段）；形状层 4 例按偏移读真源码的 `rotateForNewTurn` / `applyNewChatDefaultModel` 函数体（F-05 是「缺写入」，纯函数钉不住——缺写入意味着函数根本不会被调用——故用偏移 + 负控）。期望值全部写字面量，不从被测常量推导。

**本地读数**（`probe-c6/run.sh`，`nice -n 10`）：`COMPILE OK` + `OK (7 tests)`。

**变异臂 6 条（`probe-c6/mut.py`）**：
| 臂 | 变异 | 期望红 | 实测 |
|---|---|---|---|
| S1 | 轮转恢复成「先发布后 create」 | `rotationBuildsTheProviderBeforePublishingAnything` | MATCH |
| S2 | 三条清空全删（修前体） | `theNewChatDefaultLeavesGroupMode` | MATCH |
| S3 | 只删 `pendingEntryOverride` 清空 | `theNewChatDefaultLeavesGroupMode` | MATCH |
| S4 | 清空前移到决策之前（拒绝路径也清） | `theNewChatDefaultLeavesGroupMode` | MATCH |
| V1 | 失败分支照发目标状态 | `aFailedCreation…` + `aCreationThatYielded…` | MATCH |
| V2 | 成功分支顺手退出分组模式 | `aCreatedProviderTakesOverTheIdentity…` | MATCH |

形状臂的镜像树**带上全部被读文件**（routing + lifecycle + planner + test），保证红只来自目标变异，不是别的探针连带报红。

**相关门（单门跑，非全量）**：`session_provider_enabled_check` rc=0（8 sites 经富化模型）、`claim_release_guard` rc=0、`provider_rpc_symmetry_check` rc=0、`chat_session_id_binding_check` rc=0、`provider_boundary_guard` rc=0、`discarded_return_guard` rc=0。门脚本本身零改动。

## 4. CI

run `40679665546`（job `122786860079`，attempt 1），2026-10-28T11:24:08Z → 11:41:37Z（17m29s），**`conclusion: success`**。

- **head_sha `4c4ba377da692943923c45cfa8abef04c7168ec6`** 与本地 HEAD 逐字符一致（推送后无新提交）。
- 27 步唯一非 success = 两条 skipped（滚动 tag + Publish，main 门控）—— 分支上的预期形态。
- 日志 1,505,862 B / NUL 0：
  - `║  Summary: 113 passed, 0 failed` ×1（其余 6 条 Summary 是分段计数 25/16/44/19/30/18）；
  - `✅ ALL 349 CASES PASS` ×1（夹具 349 不变，本支未注册新门）；
  - `[n/113]` 门头计数 **113**，编号 1..113 互异；
  - ` e: `（kotlinc 编译错）**0**；
  - 含 `FAILED` 的 6 行**逐条确认全是 PASSED 用例名子串**（`…produces FAILED without calling provider PASSED` 等）。
- **`ModelRoutingPublishTest` 7 条全 PASSED**（逐条点名：aCreatedProviderTakesOverTheIdentityFieldsOnly / aFailedCreationPublishesNothing / theNegativeControlRejectsThePreFixRotation / theNegativeControlRejectsThePreFixNewChatPublish / aCreationThatYieldedNothingIsNotSuccess / rotationBuildsTheProviderBeforePublishingAnything / theNewChatDefaultLeavesGroupMode）。
- 停下：**停在分支，不合 main**（本支有 `.kt` 验证面，非纯文本例外；合并起手权在用户）。

## 5. 诚实边界

- **真机未验**：F-05 与 F-06 的用户可见后果（顶栏高亮、轮转落点）只在源码与 JVM 决策层验证，未在真机跑过；沙箱无 Android 运行时。
- **F-06 的失败路径「状态不动」仅覆盖本函数内的五个字段**；`persistBinding` 也在门控之后，故失败时不重写绑定 —— 与修前「状态已翻转但未落绑定」相比，收敛更强，但**未覆盖**调用方在 `rotateForNewTurn` 返回后又自行改状态的可能性（现有两个调用点 `sendMessage` / `drainQueuedPrompts` 都是取返回值后继续，无额外状态写）。
- **`catch (e: Exception)` 只吞 `ProviderFactory.create` 的异常**，不吞 `Error` / `Throwable`（与文件其余 `catch (_: Exception)` 一致）；`reason` 取 `e.message`，空则回退类名，不含凭据形状。
- **未触碰**：Backlog 2421 的 metadata-only 9 处；`ChatSessionLifecycle.kt` 的其它分支（只动 `applyNewChatDefaultModel` 内 `applyNewChatDefaultModel` 相关行）。
- **F-05 只保证「模式与显示一致」**，不保证用户偏好的模型被保留：清空绑定后仍按 last-used → newest-provider 的既定链解析，是设计意图（dv24-A05）。

## 6. 新登记残留（三件套齐全，待并入 backlog）

| ID | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| C6-R1 | F-06 的 try/catch 只包 `ProviderFactory.create`，若日后在其前后加入新的可抛调用（如额外校验），失败路径会再次出现部分发布 | 有人在 `rotateForNewTurn` 尾部新增调用 | 一并纳入 `planTurnRotation` 的前置计算或同 try 块，并补一条变异臂 | 当前尾部只有 create 一个可抛点；半径纪律 |
| C6-R2 | `planTurnRotation` 的 `current` 是调用方现场重建的快照（五个字段逐项抄），若 `SessionModelSelection` 新增字段而调用点漏抄，失败路径会静默丢字段 | 给 `SessionModelSelection` 增字段 | 加一条「字段数 + 全字段 round-trip」断言 | 现在字段集与写入面一一对应，抄漏会被 7 例中的逐字段断言抓到 |
| C6-R3 | F-05 未处理「清空绑定后用户希望能一键回到原分组」的需求，UI 无回滚通道 | 用户提该需求 | 在 SessionMemorySheet / 设置行加「恢复上次绑定」 | 属新功能，不是缺陷；登记不修 |
| C6-R4 | `applyDefaultModelFallback` 解析成功时只写 `_selectedGroupId`，不写 `_selectedGroupName`（顶栏名在 `resolveProviderFromGroup` 内部写），两侧分工靠调用顺序而非契约 | 有人改 `resolveProviderFromGroup` 的写入面 | 把分组名写入也提到 fallback 里，或加门钉住「两个字段必须成对」 | 现状自洽（`resolveProviderFromGroup:1229` 确实写了 name）；半径纪律 |
