# FIX-LOOP1030-R3 — 压缩效果口径：从「折叠事件」改到「投影净收益」

**分支** `fix/compact-effect-metric`｜**基线** origin/main `569e77a3`

## 1. 症状与证据

10-29 全天 42 次自动压缩，其中 **13 次（31%）只折叠 1 条消息**（中位 174 token），
却照样：写一次摘要（~4.2s 往返、~323 输出 token）、`folded=true`、**当轮硬 trim 被
关闭**。那一轮请求在历史之外还多背一份 ~1190 字的摘要——净效果是**变长**，而所有
读数都显示"压缩成功"。

代码事实（`ui/chat/ChatContextWindowExt.kt`）：

```kotlin
val folded = _cachedLatestMarker?.lastCompactedMessageId != markerBefore ||
    _compactSummary.value != summaryBefore
```

`marker`/`summary` 变化只能证明**压缩事件落地**，不能证明**上下文变短**；而调用方
`AgentLoopEngine` 正是拿这个布尔决定是否跳过当轮硬 trim。可用的投影读数其实一直
都在（`ContextCompactor.estimateTokens` / `estimateTailTokens`），只是从来没人把
压缩前后放在一起比。

## 2. 根因

用「事件发生」代替「结果达标」。布尔判据只看压缩有没有落地；没有收益阈值，
于是"折叠 1 条消息 + 写 1200 字摘要"与"折叠 40 条消息"等价。

## 3. 修法

- 新增纯函数 `conversation/CompactEffect.kt`：
  - `projectedTokens(history, summary, anchorDbId)` = 摘要 token + 锚点后尾部 token
    （与 `effectiveAgentHistory` 的 summary+tail 投影同口径，锚点不可解析 → 全量）；
  - `verdict(compacted, beforeTokens, afterTokens, minGainTokens = 512)` →
    `NOTHING_FOLDED` / `FOLDED_NO_GAIN` / `FOLDED_AND_SHRANK`。
  - `MIN_GAIN_TOKENS = 512`：10-29 无效压缩中位收益 174 的 ~3 倍，保证那批样本
    全部落进 `FOLDED_NO_GAIN`；同时远小于摘要自身成本。
- 接线（`maybeAutoCompactInLoop`）：
  - `markerAfter`/`summaryAfter` 取代原先的内联比较；
  - `beforeProjected` 用压缩前快照（`summaryBefore`/`markerBefore`），
    `afterProjected` 用 live 值；
  - `val folded = verdict == FOLDED_AND_SHRANK` —— 只有真变短才放行硬 trim 的跳过。
- 日志三分（保留既有可 grep 串）：`folded nothing`（逐字不变）/
  `compact folded but the context did not shrink (before=… after=… gain=… min=…)` /
  成功行带同样的三个数字；rescue 的 `rescue=failed` 区分"没折叠"与"折叠了但没变短"。

## 4. 验证

| 面 | 读数 |
|---|---|
| 纯函数（逐字复制 `CompactEffect.kt` 进探针） | `CompactEffectTest` **9/9 PASS** |
| 接线形状（读 `ChatContextWindowExt.kt`） | `CompactEffectWiringTest` **4/4 PASS** |
| 变异臂 | **4/4 RED-AS-EXPECTED**：A1 `MIN_GAIN 512→100`→2 红；A2 投影漏摘要→3 红；A3 verdict 忽略 compacted→1 红；A4 接线回退布尔→2 红 |
| 本地门 | `scan.sh` **113 passed, 0 failed** |
| 本地夹具 | `test_scan.py` **ALL 349 CASES PASS** |
| CI | 见 §4.1 |

探针发现的两个真缺陷（都是探针先抓到、CI 之前就修掉的）：

1. `CompactEffect.kt` 漏 `import com.rikkaminis.app.data.model.LLMMessage` —— 编译级。
2. 端到端用例的 `before` 锚点写错（首次压缩时 marker 为 null，`before` 是全量），
   期望值与实现不符——说明"字面量期望"确实能反过来校准测试作者的假设。

### 4.1 CI

- **run `40987317395`**（`fix/compact-effect-metric`）**success 17 min 39 s**（01:41:22Z → 01:59:01Z），attempt 1，`workflow_dispatch`。
- `head_sha 73111f7f987c3db4a737342bc6622c8ed5794dd4` 与本地 HEAD **逐字符一致**（SHA-MATCH）。
- 27 步 = **25 success + 2 skipped**（`Move the rolling tag android-latest` / `Publish to Releases`，均 main 门控，分支上必 skip）。
- job 日志 3,351,872 B / **NUL 0**：`Summary: 113 passed, 0 failed` ×2、`ALL 349 CASES PASS` ×2、`^e: file` **0**、真 ` FAILED$` **0**。
- 本支用例两遍全 PASSED：`CompactEffectTest` **9 例**（含 `the 10-29 net-loss shape ends in FOLDED_NO_GAIN`、`a compact that saves 174 tokens does not unlock the trim skip`、`one token short of the minimum stays closed`）、`CompactEffectWiringTest` **4 例**（含负控 `the boolean-only decision is gone`）。
- **首轮红（诚实记录）**：`40985475007` failure @2.6 min，`e: file … ChatContextWindowExt.kt:415:9 Conflicting declarations: val verdict … :294:9` —— 同函数里 `policy.check(...)` 的结果已经叫 `verdict`，我的新局部变量重名。修 = 改名 `compactEffect`（`val folded` / `FOLDED_NO_GAIN` / rescue `outcome` 三处引用与接线测试的期望字符串同步改）。**教训：往长函数里插声明前，先扫该函数体已有的 `val/var` 名（`re.finditer(r'^\s*(val|var)\s+(\w+)')` 一行就能出重名表），别等 CI 20 分钟后告诉你。**

## 5. 残留（带触发条件）

- **R1 `MIN_GAIN_TOKENS = 512` 是拍的**（中位 174 的 ~3 倍）。｜触发条件：真机累计
  ≥30 次 `FOLDED_NO_GAIN` 行后｜方案：按 `gain` 分布取 P50/P75 重新定阈，并把
  "被拦下的压缩里有多少条确实是 1 条消息"作为二阶验证。
- **R2 投影估算与真实 tokenizer 有偏差**：`estimateTokens` 是 CJK 1 字 1 token +
  ASCII chars/4，摘要侧不含工具 schema 与消息包装。｜触发条件：出现"判为变短但
  下一轮 provider 报 too large"｜方案：把 `ContextEstimator.estimateRequestTokens`
  的完整读数接进来（含 system/tools），代价是每次压缩多算一遍 schema。
- **R3 真实收益未闭环**：压缩是否真的缓解了压力，要看**下一轮** provider usage /
  硬 trim 触发次数。｜触发条件：下一批审计读 `[AutoCompactLoop] compact shrank`
  与 `[HardTrim]` 行｜方案：把 `gain` 与下一轮 `prompt_tokens` 差值做 join。
- **R4 真机未验**：全部证据 = JVM 级（真源）+ 形状 + CI 编译级。
- **R5 `FOLDED_NO_GAIN` 时仍写摘要**：本支只关掉 trim 跳过，不去回滚已写入的摘要
  （回滚要动持久化与投影，超出本条半径）。｜触发条件：日志显示同一段历史反复
  产生 `FOLDED_NO_GAIN`｜方案：压缩前先估"折叠 N 条的预期收益"，低于阈值直接不
  发起压缩（省掉 4.2s 往返）。
