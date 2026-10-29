# FIX-R5-C1 —— 压缩边界契约密集支（R5-C 五条）

- **分支** `fix/r5-c1-compact-pipeline`（基线 main `f3def6ce`）
- **来源** meta-audit-1029 R5 修后复扫批，报告 `reports/R5-C.md` 条目 C-C-01 / C-F-01 / C-F-02 / C-C-03①② / C-C-04③
- **触碰面** 生产 3 文件（`CompactPin.kt` / `CompactOrchestration.kt` / `ChatSessionLifecycle.kt`，+243/−46）+ 测试 4 文件（`CompactPinBudgetTest.kt` 按 C-C-01 契约改写 + 新增 3 类）。`scripts/scan/**`、`scan.sh`、`test_scan.py` 零触碰；门 113 / 夹具 349 不变；i18n 零改动（C-F-01 可观测性走日志，无新文案）。
- **取证纪律** 全部按语义在本树重定位（`rg` 函数定义/调用点），不按报告行号；报告引用的 `:674` 空守卫实为 `:285`（`compactAll` 内），`anchorByCreatedAt` KDoc 所述 iOS 镜像（AIChatViewModel+Compaction.swift:125）属实但本 port 的无限定形式被 F-02 证伪。

---

## §1 修复台账（每条一行）

| 条目 | 先证结论 | 修法 | 证据 |
|---|---|---|---|
| **C-C-01** fit 丢最早钉 | 成立，且是**结构性**的：extract 无条件补入使块必然可能超预算一条，fit 逆序 walk 只豁免最新一条 ⇒ 最早一条是第一个被 break 的对象；单轮（carried 空）即可触发，多轮每轮复发 | fit 分配改「两条豁免 + 其余从新到旧装填」；硬顶不变 | ARM probe1 旧码红 / 新码绿；CompactPinEarliestPinTest 4 例 |
| **C-F-01** 空摘要守卫失效 | 成立：守卫判 `summary.isEmpty()`，但钉住块在其内部拼接之后才返回 ⇒ 折叠区有 ≥1 条用户消息时守卫永不触发 ⇒ 无正文标记落库（insertCompactMarker）+ 整段折叠区被裁 | 判正文不判拼好的串：`summaryHasBody`（stripPinnedSection 后非空白） | `a block only summary has no body`（块-only→false / 正文+块→true） |
| **C-F-02** 自愈向外扩张 | 成立且比报告更狠：不是偶发，是**凡压缩留有先前尾部必触发**——marker.createdAt 是压缩执行时刻，保留尾全部早于它 ⇒ 「最后一条早于 marker」= 保留尾最新一条 = 摘要从未覆盖的轮次 | 自愈候选钉在 `compactedCount-1`，只能向内收窄；不可证时拒绝（走 insertIdx=0/无灰显既有分支） | ARM probe3 旧码返回 m4（真锚点 m2）/ 新码 m2；拒绝路径 3 例 |
| **C-C-03①** sanitize→normalize 次序 | 成立：两修复互喂——sanitize 剥头部孤儿结果暴露 ASSISTANT 头 → normalize 剥它 → 其后 tool_result 成新孤儿 ⇒ 切片头又是孤儿，严格网关 400 | `repairSliceShape` 到定点（sanitize→normalize 循环至签名稳定，上限 4 轮触顶记日志），3 个调用点统一 | `a peel that orphans a tool_result is repaired by the next pass`（旧次序终态 = 孤儿头） |
| **C-C-03②** merge 丢附件 | 成立：`a.copy()` 继承 a 的 imageParts/audioParts、静默丢 b 的——两字段不是 contentParts，多模态载荷不进 parts 通道 | 按时间序拼接双方 imageParts/audioParts；不得发明附件 | ARM probe2 旧码只留 1 图 / 新码 2 图 |
| **C-C-04③** 清标记不复位灰显 | 成立：清了 `_cachedLatestMarker`/`_compactSummary`，但 `_messages` 的半透明气泡（isCompactedHistory）与分隔卡（详情单仍提供「撤销压缩」→ 点了答 sysmsg_revert_nothing）还在 | 清标记路径同步 `neutralizeCompactArtifacts`（剥分隔卡 + 清全部淡出标志），无条件、镜像缓存写 | 源码形状 + 既有 neutralizeCompactArtifacts 语义（ChatModels 纯函数已有测试） |

**测试改写声明（C-C-01）**：`CompactPinBudgetTest` 原 3 条（`over budget ... drops the oldest carried ones` / `header survives a partial carried trim` 的 c1/c2 期望 / 八轮 `r7` 断言）**编码的正是被证伪的分配规则**，按新契约改写期望值（全字面量）；`a newest entry larger than the whole budget`（超大条目独占预算）与 `a fully dropped carried payload gets the header back` 两条在新契约下**原样通过、未改**。

---

## §2 契约（原契约 / 被破坏方式 / 新契约）

### C-C-01 —— 钉住块预算分配
- **原契约**：`extractPinnedUserMessages` 承诺「最近 N 条 + 最早 1 条无条件」（本文件头部衰减实验：round-1 陈述的 ground truth 在 round-2 召回 0/10，后位同话题轮覆盖它——这就是钉存在的唯一理由）；`fitPinnedPayload` 承诺总预算 ≤ `max(tokenLimit, 最新一条)`（跨轮不膨胀）。
- **被破坏方式**：extract 把最早一条**补回**列表（可能超预算一条），fit 的逆序 walk 只带一个 `keptAny` 豁免（最新条），最早条 = walk 第一个越限即 break 的牺牲品。「契约 A 的兑现物在契约 B 的分配器里被判为增长垃圾」——单轮即可触发（extract 返回 `[early, m1, m2]`、fit 丢 early），多轮则每轮复发。
- **新契约**：fit 分配 = **两条豁免 + 其余从新到旧装填**：①最新条永远保留（用户最后的真实粘贴，丢它 = 删本轮输入）；②最旧条只要还能与①同框就保留（原始任务陈述；extract 的「最近 N + 最早 1」从此在两个函数里都成立）；③中间条从新到旧装填（增长才是被丢的）。最旧条豁免的是**裁剪**不是**硬顶**：与①同框放不下则让位（超大条目独占预算的既有规则不变），硬顶仍为 `max(tokenLimit, 最新一条)`——r3-b11 的防膨胀上限一分未让。

### C-F-01 —— 空摘要守卫
- **原契约**：compactAll 的空摘要分支语义 = 「LLM 没有产出任何摘要正文 ⇒ 什么都不折叠、不落标记、不裁历史」（[fix/compact-silent-abort-cards-1016] 的静默豁免与 !silent 卡片在此分支上）。
- **被破坏方式**：守卫读 `summary.isEmpty()`，而 `summary` 是 `generateCompactSummaryWithSplitting` 的返回值——钉住块在其**内部**经 `appendPinnedSection` 拼好才返回。折叠区只要有 1 条用户消息，块就使字符串非空 ⇒ 守卫永不触发 ⇒ 空摘要标记落库、`effectiveAgentHistory` 把整段折叠区换成对用户原话的逐字引用（纯损失）。
- **新契约**：判**正文**不判拼好的串：`summaryHasBody(summary)` = `stripPinnedSection` 后非空白。块-only ⇒ false（不折叠、不落标记）；正文+块 ⇒ true。分支行为（!silent 卡片 / 静默跳过）不变；可观测性补进日志（该分支此前零痕迹，卡片曾是唯一线索）。

### C-F-02 —— createdAt 自愈加界
- **原契约**：Phase2.5 自愈 = 标记锚点 id 失联（被删 / 恢复备份换 id）时按 createdAt 找回「被压缩区间的最后一条」，保证折叠区切分、灰显与摘要注入不失效（iOS 同款镜像）。
- **被破坏方式**：marker.createdAt = **压缩执行时刻**；压缩时已存在的每一条保留尾（预算锚点路径留的尾、compactBefore 选定边界之后的消息、anchor 之后才插行的任何情形）createdAt 都早于它 ⇒ 「最后一条早于 marker.createdAt」永远落在保留尾的最新一条上，把摘要从未覆盖的轮次折掉。自愈只会破坏——且**必然**越界，不是偶发。
- **先证边界信息**：marker 可用信号只有 `compactedCount`（本轮折叠块长，`toCompact.size`，恒 ≥1）；`historyDbIds` 语义 = loaded agentHistory 仍表示的 id 全集（无边界信息）；`rawMessages` 按 sort_order 升序（loadMessages ORDER BY）。无累计折叠数（需查全部历史标记，未做）。
- **新契约**：候选 = 预 marker 候选中第 `compactedCount-1` 个。该下标只能落在真锚点上或更早（第二轮起块不从 0 开始 / rawMessages 可能含 LLM 历史过滤掉的行，都把下标推早）⇒ **自愈只能向内收窄**（代价 = 重发已摘要文本，花 token），**不能向外扩张**（丢未摘要轮次，丢数据）。拒绝情形（走既有 insertIdx=0 / 无灰显分支）：`compactedCount ≤ 0`；候选数 < compactedCount 且 marker 之后仍有落盘行（幸存者不再可证是折叠块前缀）；候选为空。

### C-C-03① —— 切片修复到定点
- **原契约**：切片修复 = sanitize（剥孤儿 tool_result / 为失配 tool_use 注入占位）+ normalize（剥头部非 USER / 合并同角色）各一次、固定次序，产出可直接发给 provider 的合法形状。
- **被破坏方式**：两修复**互相制造**对方要修的形状。sanitize 剥头部孤儿结果 ⇒ 暴露 ASSISTANT 头 ⇒ normalize 剥它 ⇒ 紧随其后的 tool_result 成新孤儿（其 tool_use 已被剥走）⇒ 切片头又是孤儿 tool_result ⇒ 下一请求 400。一次 sanitize+normalize 不足，且次序怎么排都一样。
- **新契约**：`repairSliceShape` = 跑到**定点**：sanitize → normalize → 重复，直到一整轮形状签名不变。终止性：每轮只删消息 / 删 parts / 合并（唯一加法——占位注入——幂等，且其 tool_use 被剥后自行消失），消息数与 parts 数自第二轮起单调不增；`MAX_SLICE_REPAIR_PASSES=4` 是保险丝，触顶记日志不静默。签名 = (role, tool 块 id, content 空) 序列——恰是两修复能改变的东西。三个调用点（effectiveAgentHistory v2 / v1→v2 / 兜底）统一走单一入口。

### C-C-03② —— 同角色合并保留多模态
- **原契约**：同角色相邻合并 = 双方全部载荷保留（文本 lift 成 Text part；附件是用户资产）。
- **被破坏方式**：`imageParts`/`audioParts` 是 LLMMessage 自有字段（不是 contentParts），`a.copy(content=…, contentParts=…)` 继承 a 的、静默丢 b 的。摘要注入头与首个保留消息合并 / 剥除后两同角色轮合并时，用户图片或音频从请求里消失——无错误、无日志、无痕迹。
- **新契约**：双方 imageParts/audioParts 按时间序拼接（旧在前）；不得发明不存在的附件。

### C-C-04③ —— 清标记同步复位屏幕（按约束只做灰度复位半边）
- **原契约**：清标记 = 「这次折叠已撤销」的完整陈述；屏幕不应再有折叠的可见痕迹（回退路径 revertCompact 经 reloadSessionFromDb 重算，live 路径靠 graying 状态）。
- **被破坏方式**：只清 `_cachedLatestMarker`/`_compactSummary`，`_messages` 的两个可见碎片残留：半透明气泡（`isCompactedHistory` → ChatScreen grayedMap）与压缩分隔卡（详情单仍提供「撤销压缩」——点了 revertCompact 读到 null 缓存 ⇒ sysmsg_revert_nothing）。灰显 + 失效菜单。
- **新契约**：清标记路径同步 `neutralizeCompactArtifacts`（剥分隔卡行 + 清全部淡出标志——与静默自动压缩路径同一理由：不再有任何 marker 描述当前转录）。**无条件**，镜像上面的缓存写：内存真相是下一拍被读取的东西，屏幕不得与缓存不一致。DB 删除失败重试等其他边按任务约束不动（见残留 R4）。

---

## §3 验证证据（三源取二）

1. **JVM harness**（`/var/minis/shared/work/r5-1029/c1-probe/`，真源逐字切片 + 存根：android.util.Log / androidx.room 注解 / ContextCompactor.estimateTokens / ChatViewModel.TAG / LLMUsage；提取器 `extract_slice.py` 按声明名+括号配平）：**修复树 56/56 OK**（7 测试类，含既有回归 CompactPinTest / CompactPinDedupTest / CompactSliceRepairTest）。
2. **变异臂对拍**（同一批测试字节、两套生产切片：`f3def6ce` 旧码 vs 修复树）：旧码**恰 3 处失败** = probe1 fit 丢最早 / probe2 merge 丢右项图片 / probe3 heal 返回 m4（真锚点 m2）；**32 条既有回归在旧码全绿**（证明旧测试本就有效 ⇒ 契约改写有据，不是把红改绿）；新码同批全绿。
3. **门（非全量）**：涉及本支文件的三道各跑一次 = `compact_pin_truth_check` / `compact_entry_pin_migration_check` / `compact_decision_gate_check` **3/3 PASS**（后者对空摘要分支的变异臂判据文本未动，门绿即证）。
4. CI：分支 dispatch run（见交付回报），绿 = 停止节点。

## §4 残留（登记，不带触发条件的不列）

- **R1** fit 的最旧条豁免受硬顶约束：最旧条单独超预算且最新条也大时让位给最新（KDoc 已声明 + 硬顶表测试钉住）。彻底保 earliest 需把硬顶放宽到 oldest+newest，**未做**——r3-b11 防膨胀契约优先。触发条件：真机出现「原始陈述确实超大被丢」的实际案例。
- **R2** 自愈收窄在第二轮以后可能比真锚点早很多（重发已摘要文本 = token 成本）。精确化需累计折叠数（查全部历史标记）。触发条件：真机日志出现 heal 后 context 显著膨胀。
- **R3** `repairSliceShape` 触顶 4 轮只记日志。触发条件：日志真出现 "did not stabilise"。
- **R4** `dropCompactMarkerIfRewound` DB 删除失败时内存已清、下次 load 复活标记（既有行为）。触发条件：按任务约束④本轮只做灰度复位半边；如需闭合走 backlog。
- **R5** 真机未验：全部证据 = JVM 级 + 门级 + 源码形状。触发条件：下次真机压缩/重试操作顺带核四点（气泡透明度恢复、撤销入口消失、长会话首条陈述在上下文、恢复备份后无越界折叠）。

## §5 这改变什么用户行为

长会话压缩后模型仍能看到最初的任务陈述（原先每轮超预算折叠都在丢它）；LLM 返回空摘要（内容过滤/拒答/thinking-only）时原文不再被「无正文标记」静默替换；恢复备份/删消息后再进会话，摘要不再吞掉它从未覆盖的保留尾；带图/带音频的消息经切片合并后附件不再消失；重试/编辑清标记后气泡立即恢复不透明、失效的「撤销压缩」入口消失。
