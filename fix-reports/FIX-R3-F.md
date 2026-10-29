# FIX-R3-F — B11 压缩族（F-01 / F-02 / F-03）

- 分支：`fix/r3-b11-compact`（基线 `2103729f`）
- 绿 sha：`bccc7a78a6d3f799c9c2edf9911b1e094b6a960b`（CI run #2856 / `40740915829` success 21m30s）
- 来源：`reports/r3/B11-agent-tools-2026-10-29.md`（F-01 / F-02 / F-03）
- 结论：**三条全部成立**（报告机制两处需要修正，见 §2），全部 P1，全部修复。

---

## 1. 概览

| 条目 | 报告说法 | 先证结论 | 修法一句话 |
|---|---|---|---|
| F-01 丢历史摘要 | 二分时 `previousSummary` 退化为 null ⇒ 旧摘要丢失 | **成立**（机制对，后果比报告更重） | 首半区携带 `prevSummaryStripped`，次半区仍 null |
| F-02 marker 失败仍发布 | 落盘失败后仍发布内存态/置灰 | **成立**（`[audit-0917]` 契约被违背） | 落盘失败改为 throw 进 `compactAll` 自己的 catch |
| F-03 pin 无上限 | `appendPinnedSection` 只加不裁 | **成立**（单点无上限，跨轮累积） | 合并后总量按 8000 上限 newest-first 装填 |

改动面：生产 3 文件（`CompactOrchestration.kt` / `CompactPin.kt` / `ContextCompactor.kt`）+ 新测试 4 文件 + 既有测试 1 文件重锚。`scripts/scan/**`、`scan.sh`、`test_scan.py`、`agent/**`、`speech/**`、`tools/**` **零触碰**（门数 113 / 夹具 349 与基线一致，CI 实证）。

---

## 2. 报告命中率与逐条先证

报告可信度：**机制方向 3/3 对，细节 2 处需修正、1 处比报告更严重**。

### F-01（成立，但后果表述需换）

- 报告称「二分时把 `previousSummary` 退化为 null」。真码 `generateCompactSummaryWithSplitting` 中：
  - depth 0 用 `previousSummary` 拼 `conversationText`；
  - 命中 `ContextTooLarge` 后 `firstHalf` / `secondHalf` **都**传 `null`（`git show 2103729f:...` 可见），两个半区摘要只覆盖本轮消息，merge 只合并这两段 ⇒ 返回的字符串**替换**上一轮 marker 里的摘要。
- 修正 1：报告把后果写成"旧摘要丢失"，实际是**不可恢复的**——`compactAll` 随后落库新 marker，旧摘要文本已无任何副本（`_messages` 里从没有摘要文本）。
- 修正 2：报告只提"第 2 轮之后"，实际**任何**一轮只要触发二分就丢（第 1 轮 `previousSummary` 为 null，无损失；从第 2 轮起每轮都可能丢全部历史蒸馏）。

### F-02（成立，`[audit-0917]` 注释自相矛盾）

真码：

```kotlin
val markerSaved = try { dao.insertCompactMarker(marker); true }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Log.w(...); false }
if (!markerSaved) { AppLogger.warning(..., "compact marker not persisted; keeping the summary in memory only") }
_compactSummary.value = summary          // ← 无条件
_cachedLatestMarker = marker             // ← 无条件
```

而文件自身 `[audit-0917]` 注释写着 **“Only publish the in-memory compact state when the marker actually persisted.”** 后继句写的是 "the in-memory boundary is not advertised as durable"——两句互相矛盾，代码实现的是**第三件事**：照常发布 + 照常置灰 + 照常插分隔线 + 照常 `compactSucceeded = true`。

后果可指认三条：
1. 重载后 marker 不存在 ⇒ 界面回退成"未压缩"，用户以为压缩丢了（实际是**从来没保存**）；
2. 分隔线上的 Revert 入口必然失败（`deleteCompactMarker` → 0 行）⇒ `sysmsg_revert_failed_db`；
3. 本轮 `_cachedLatestMarker` 生效但 DB 无记录，下轮 auto-compact 会把新摘要叠在**不存在的**基线上。

### F-03（成立，报告低估了累积性）

真码 `extractPinnedUserMessages` 的 8000 预算只作用于**本轮新 pin**；`appendPinnedSection` 做的是 `carried + new` 直接拼接，**没有任何总量上限**。于是每压缩一轮最多再涨 8000 token，且这个块随每轮请求以 `<context-summary>` 的 pin 段发送 ⇒ 长会话可以靠 pin 段自己把会话推进 `PressureLevel.EXHAUSTED`。

---

## 3. 修法

### F-01（`CompactOrchestration.kt` + `ContextCompactor.kt`）

```kotlin
val summary1 = generateCompactSummaryWithSplitting(firstHalf, prevSummaryStripped, depth + 1)
val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)
```

- 首半区是**更旧**的材料，正是"应该压在旧摘要之上"的那一半；次半区保持 null，因为旧摘要已被首半区吸收，两边都传会在 merge 时**重复计入**。
- depth > 0 时 `prevSummaryStripped = previousSummary`，所以首半区再次二分仍能接力，不会二次截断。
- depth 0 的拼装抽到 `ContextCompactor.compactInputText(prev, transcript)`（同包对象成员，纯字符串、无 LLM/无 Android）：**两处拼装从此不可能再各写一份**。这是本条能"不再复发"的关键——F-01 的成因就是两处拼装漂移。

### F-02（`CompactOrchestration.kt`）

```kotlin
try {
    chatRepository.dao.insertCompactMarker(marker)
} catch (e: CancellationException) { throw e }
catch (e: Exception) {
    Log.w(ChatViewModel.TAG, "Failed to persist compact marker: ${e.message}")
    throw IllegalStateException("compact marker not persisted: ${...}", e)
}
```

throw 让失败落进 `compactAll` **既有**的 catch 分支，于是自动获得该文件里已有的全部失败语义：
- 不发布 `_compactSummary` / `_cachedLatestMarker`（不产生"内存已压缩、重载回退"的半状态）；
- 不置灰、不插分隔线（用户看不到"已压缩可撤销"的假象，`revertCompact` 无从失败）；
- `compactSucceeded` 保持 false ⇒ 不触发排队提示的 drain；
- 调用方回退到硬裁剪（`ChatContextWindowExt` 用 `folded == false` 判断，已核）；
- 手动 `/compact` 显示 `sysmsg_compact_failed` + 底层 DB 错误（复用既有 card，**不新增 i18n**）；auto-compact 继续静默（与该文件所有兄弟失败分支同一 `!silent` 口径）。

> 为什么不是"保留内存态但不置灰"（另一种可行修法）：那仍然制造"内存压缩、重载回退"的半状态，只是让 UI 不再撒谎；`[audit-0917]` 契约要求的是**不产生**这个状态。abort 把它变成和"摘要生成失败"完全同型的一次失败，装置与心智负担最小。

### F-03（`CompactPin.kt`）

新增两个 `internal` 纯函数：

- `splitCarriedInner(inner)` —— 把 carried 拆成 `header`（仅当首块等于 `PINNED_HEADER`）+ 条目列表（按 `"\n\n"` 切）。**切分只用于裁剪粒度**，不参与内容重建：保留的都是原文切片，不会重排/复制/改写，最坏情况是最旧的保留条目被截短。
- `fitPinnedPayload(carried, pinned, tokenLimit = PINNED_USER_MESSAGES_TOKEN_LIMIT)` —— 合并后总量超限时**从最新往回装填**（fresh 比任何 carried 都新），装不下就停。规则与 `extractPinnedUserMessages` 本轮内的口径一致（recency 优先，最老的 pin 就是被裁的增长）；单条超过整个预算时，仅当它是**最新**那条才保留（丢掉用户刚粘贴的内容等于废掉 pin 的意义），因此硬上限是 `max(预算, 最新一条)` 而不是恒等于预算。
- `appendPinnedSection` 在写块**之前**调用它；在预算内时**字节级透传**（`carriedInner`/`pinned` 原样返回），所以既有"多轮 carried 逐字保留"的测试一条都不用改。

### F-11（不动，已登记 P2）

`estimatedInput = length/4` 属既有 P2（backlog 已登记），本支按任务约束不碰。

---

## 4. 证据

### 4.1 真测试（不是复刻）

4 个新测试文件 + 24 条既有 pin 用例，**在 JVM 上跑真源码**（`CompactPin.kt` / `ContextCompactor.kt` 原文件编译，非切片）：

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `CompactPinBudgetTest` | 8 | 预算内透传 / newest-first 裁剪 / header 存活 / 整块被裁后 header 回补 / 单条超限豁免 / 8 轮累积上限 / 空与非法 limit |
| `ContextCompactorCompactInputTest` | 2 | 拼装字面量（无旧摘要 / 有旧摘要） |
| `CompactSplitCarryWiringTest` | 3 | 首半区携带旧摘要、次半区不双计、depth-0 走共享拼装 |
| `CompactMarkerPersistWiringTest` | 4 | 落盘失败必须 throw、旧 boolean 形态已消失、`_cachedLatestMarker` 在发布之后、手动压缩仍有失败 card |

本地：`41/41`（17 新 + 24 既有）通过。（提交信息里写的 "21 new cases" 是笔误，实际新增 **17** 条——CI 日志逐条可数：8+2+3+4。）

### 4.2 变异臂 8/8 MATCH（`probe-f3/arms.py`）

| 臂 | 变异 | 期望红（实得一致） |
|---|---|---|
| A1 cap-off | `fitPinnedPayload` 直接透传 | 5 条预算用例 |
| A2 oldest-first | 装填极性反转 | 5 条预算用例 |
| A3 framer-drops-prev | `compactInputText` 忽略旧摘要 | 拼装字面量用例 |
| A4 null-firsthalf | F-01 回退（首半区传 null） | 首半区形状用例 |
| A5 marker-boolean | F-02 回退（boolean + 警告） | 2 条 marker 形状用例 |
| A6 order-swap | `_cachedLatestMarker` 提到发布之前 | 顺序用例 |
| A7 equivalent | 仅加一行注释 | **0 红**（防锚点过敏） |
| A8 header-raw | header 决策读原始 carried 参数 | 整块被裁后 header 回补用例 |

A4/A5 同时充当"pre-fix 必红"的证据：把旧写法放回去，对应新测试立刻红。

### 4.3 门（逐门直跑，未跑全量 scan）

`compact_pin_truth_check` / `compact_decision_gate_check` / `compact_entry_pin_migration_check` / `dao_loadmessages_check` / `provider_boundary_guard` / `entry_composite_key_guard_check` / `backup_remap_rollup_check`：**7/7 rc=0**。

### 4.4 CI（run #2856）

- `head_sha = bccc7a78a6d3f799c9c2edf9911b1e094b6a960b`，与本地 HEAD 逐字符一致；
- 21m30s（17:59:37 → 18:21:07 UTC），27 步，唯一非 success = 2 skipped（rolling tag + Publish，main 门控）⇒ 分支未污染 release；
- 日志 1,577,884 B / **NUL 0**；
- 门：`Summary: 113 passed, 0 failed`；夹具：`ALL 349 CASES PASS`（与基线同数）；
- 单测：**`FAILED` 行 0**、`^e: ` 0、`SKIPPED` 4 条全为 Gradle task 级（`checkKotlinGradlePluginConfigurationErrors` ×3 + `preDebugAndroidTestBuild`）；
- 新 4 类逐条 PASSED（8 / 2 / 3 / 4），既有权重用例 24 条 PASSED，重锚后的 `CompactCancelOnStopTest` 10/10 PASSED。

---

## 5. 自伤与次生问题（如实记录）

1. **F-03 修法自带一个分支缺陷，被自己的测试抓住**：`appendPinnedSection` 原先用**原始参数** `carriedInner` 判断"要不要补 header"，改完之后当"整块 carried 被裁掉、只剩新 pin"时，块会失去 header 行（模型看到裸引用文本、不知道来源）。已改为按 `fitted.carriedInner` 判断，并补 `a fully dropped carried payload gets the header back` + A8 臂钉死。**规则：给上游值加一层"可能被裁剪"的处理后，所有下游 `isNullOrBlank()` 分支都要回头看是看原值还是看处理后的值。**
2. **改形状打穿了既有测试的锚点**：`CompactCancelOnStopTest` 用 `"val markerSaved = try {"` … `"if (!markerSaved)"` 界定 marker insert 区域，F-02 删掉 boolean 后锚点失效（`substringAfter` 找不到分隔符会返回整串，断言会以错误方式通过/失败）。已改为用**被守护调用 + 发布行**两点界定同一区域（`indexOf` + `substring`），意图不变、新增锚点为零。这是"改生产形状前必须 `rg -l` 全 test 树"纪律的又一次实证。
3. **测试装置自己先错了一次**：第一版"8 轮累积"用例把上一轮 summary 当 `appendPinnedSection` 的 base 传入，而生产的 carried 是显式第三参（`pinnedSectionInner(previousSummary)`）；跑出 `inner=4033`（每轮只剩当轮 pin）才暴露。**生产接线的调用形态必须照抄，不能凭"看起来等价"简化。**
4. **探针两坑**（已记入 `balance.py` 注释）：①括号配平必须把**字符串内容**也当不透明（`substringAfter("fun cancelStream() {")` 这类字面量会让朴素计数永久失衡）；②raw string `"""…\(…"""` 内的括号同理。首版两处都误报，先怀疑探针。

---

## 6. 残留（三件套）

| # | 残留 | 触发条件 | 为什么本支不做 |
|---|---|---|---|
| R1 | 单条超大消息豁免仍在：块硬上限是 `max(预算, 最新一条)` | 用户反复粘贴 >8000 token 的单条内容 | 截断用户刚粘贴的内容等于废掉 pin 的存在理由；要做需要产品决策（verbatim 保留多少） |
| R2 | carried 按 `"\n\n"` 切分做裁剪粒度 ⇒ 旧的多段消息可能被"从中间保留" | 预算压力 + 含空行的旧消息 | 存储格式里条目边界不可恢复；正解是改分隔符 + 迁移既有 summary，成本远大于收益 |
| R3 | 手动压缩失败 card 显示底层 DB 错误原文（`compact marker not persisted: …`） | 手动 `/compact` 撞上 DB 写失败 | 新增专用文案要动 7 个 locale；既有 `sysmsg_compact_failed` 已是用户可见面 |
| R4 | 真机未验 | — | 全部证据 = JVM 级 + 源码形状 + CI 编译级；该路径无真机可观测信号（DB 写失败才能触发） |
| R5 | F-01 无端到端 LLM 证据（递归依赖 `ChatViewModel`） | 有人重构递归而不动形状测试 | 证据梯级 = 纯函数（拼装字面量）+ 接线形状（首半区实参）；两者组合覆盖"旧摘要到达首半区"，比复刻整套递归更抗腐烂 |

> 本支文件面被限定在 `conversation/*.kt` + `src/test` + `fix-reports`，故**未改 `backlog.md`**；R1–R5 交由批次台账统一登记。

---

## 7. 与既有决策的关系

- **`fix-reports/dv29-FIX-A2.md` §4.3 第 3 条**曾判定"marker 落库失败时摘要已在内存生效仍判 Completed"合理，理由是该处置"已播报 marker 未持久化"。**该理由经核实不成立**：播报只是一行 `AppLogger.warning`，用户不可见，且内存态照常发布 + 照常置灰。dv29 那条判的是"状态上报口径"，不是"是否发布"，本支改的是后者，两者不冲突。
- **F-11（`length/4`）** 按约束不动，仍为 P2 已登记项。
- `CompactPin` 的 v0 契约（verbatim、单一 block、同消息去重）**未变**：`extractPinnedUserMessages` 一字未改，24 条既有用例全绿。

---

## 8. 复现清单

```bash
# 真测试（JVM，真源码）
cd probe-f3 && kotlinc -cp <stdlib:json:ser:junit:hamcrest> -Xplugin=<kotlin-serialization-plugin> \
  <wt>/src/android/app/src/main/java/com/rikkaminis/app/conversation/{CompactPin,ContextCompactor,ContextEstimator}.kt \
  <wt>/src/android/app/src/main/java/com/rikkaminis/app/data/{ContextPolicy.kt,model/*.kt} \
  <wt>/src/android/app/src/test/java/com/rikkaminis/app/conversation/{CompactPinBudgetTest,ContextCompactorCompactInputTest,CompactSplitCarryWiringTest,CompactMarkerPersistWiringTest}.kt \
  Runner.kt -d out
cd <wt>/src/android && java -cp <out:libs> RunnerKt <4 个测试类>

# 变异臂（8 个）
python3 probe-f3/arms.py            # → ARMS: 8/8 MATCH

# 交通门（逐门，不全量）
for g in compact_pin_truth_check compact_decision_gate_check compact_entry_pin_migration_check \
         dao_loadmessages_check provider_boundary_guard entry_composite_key_guard_check \
         backup_remap_rollup_check; do python3 scripts/scan/$g.py .; done
```

**文件面**：`conversation/CompactOrchestration.kt`(+56/−28) · `conversation/CompactPin.kt`(+102) · `conversation/ContextCompactor.kt`(+23) · 新测试 4 个 · `ui/chat/CompactCancelOnStopTest.kt`(重锚) · 本报告。
