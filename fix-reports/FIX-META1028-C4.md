# FIX-META1028-C4：选区文档序 + 复制切片起点（B04 F-02 high / F-09 medium）

- 分支：`fix/meta1028-c4-selection-md`
- 基线：`bfb5b0e6`（main，1028B 三支合并后）
- 改动面：2 文件 —— `ui/chat/MinisTextKitSelection.kt` +288/−79、
  `test/.../ui/chat/MinisSelectionTableTest.kt` +285/−0
- `scripts/scan/**` / `scan.sh` / `test_scan.py` **一行未动**
- 门 113 / 夹具 349 基线不变（`chat_ui_interaction_check.py` 真树 rc=0、`--selftest` 86/86 PASS）

**这改变什么用户行为？** ①长消息里从某段往下拖选，起点滚出屏幕后，中间的蓝色高亮、两个手柄、浮动工具栏
不再整片消失；②在表格里选中一行中间的两列，只高亮/复制这两列（以前整行四列全被吞进去，顺序还随注册表迭代）；
③把表格跨表头拖到数据行复制，得到的是单元格文字（`Status  Count  ok  3`），不是 `| --- |` 的原始 Markdown；
④从表格/段落往下拖到代码块复制，结果从选中的那一行开始，不再从这条回复的第一句开始。

---

## 1. 先证（两条各自回真树核实，不采信报告行号）

### ① F-02 —— 排序键在生产环境 100% 为 null

原文（`MinisTextKitSelection.kt:1136`，改动前）：

```kotlin
private fun shardOrderKey(id: TextShardId): Int? {
    val s = id.shardId
    val lastColon = s.lastIndexOf(':')
    if (lastColon < 0) return null
    return s.substring(lastColon + 1).toIntOrNull()
}
```

`rg -n "TextShardId\(" src/android/app/src/main/java` 得到**全部 4 个构造点**，逐个核 id 形态：

| 构造点 | baseShardId | 经 `ShardSubIndexScope` 后 | 旧键 |
|---|---|---|---|
| `ChatScreen.kt:3928`（mdblock 主路径） | `"mdblock:<parent>:<idx>"` | `"mdblock:p:3#2"` | `"3#2".toIntOrNull()` = **null** |
| `ChatScreen.kt:4029`（thinking 块） | `"legacy"`，**不含 `:`** | `"legacy#4"` | `lastIndexOf(':') < 0` → **null** |
| `ChatAssistantMessageUI.kt:317`（聚合路径） | `block.id`，**不含 `:`** | `"<blockId>#4"` | `lastIndexOf(':') < 0` → **null** |
| `ChatAssistantMessageUI.kt:367`（legacy 回退） | `"legacy-text"` | `"legacy-text#4"` | 同上 → **null** |
| `StreamingMarkdownText.kt:2216`（表格单元格） | `"tbl:<i>"` | `"tbl:0#1"` | `"0#1".toIntOrNull()` = **null** |
| 同上，无 allocator 时 | `"tbl:<i>"` | `"tbl:0"` | `"0".toInt()` = 0 —— 唯一非 null，且**把表序号当成块序号** |

即：**生产环境里唯一能解析出键的，是把「表序号」误当「块序号」的那一种**。`shardOrderKey` 的
`if (ka != null && kb != null)` 分支实际从未对真实分片成立过，`orderedEndpoints` / `isShardBetween`
全部落到 `positionInWindow().y` 比较。两个后果都能在代码里指认：

- `orderedEndpoints` 的 `shards[a.shard] ?: return null` —— 端点一被 LazyColumn 回收（unregister），
  整个排序返回 null，`handleAnchor` → null（手柄消失）、`selectionWindowRect` → null（工具栏掉到 fallback）、
  `spliceNonShardSpan` → null（Markdown 复制退化成 walk）。
- `isShardBetween` 同样 `shards[a] ?: return false`，加上 `ym in minOf(ya,yb)..maxOf(ya,yb)` ——
  同一表格行的所有单元格**共用一个 y**，区间判定对整行恒为 true。

**实测对照**（`probe-c4/ControllerBehaviourTest`，真 `SelectionController`）：改动前语义下
「端点 unregister 后 `orderedEndpoints` 仍非 null」为红（M3 变异臂即此），「同一行第 1/4 列不在 col2..col3 区间内」为红（M2）。

### ② F-09 —— 表格被当成「非分片块」，锚点从 0 开始找

原文（`sliceCrossesNonShardBlock`）：

```kotlin
val tableDelimiter = Regex("""(?m)^\s*\|?\s*:?-{3,}.*\|""")
return tableDelimiter.containsMatchIn(slice)
```

核实：`StreamingMarkdownText.kt:2200` `val shardIdForTable = LocalShardId.current`、`:2216` 把
`LocalShardId` 覆写成 `"tbl:<i>"`、`:2309` 用 `LocalShardId provides (tableScopedShardId ?: shardIdForTable)`
包住单元格渲染 —— **每个单元格都注册了 shard**。所以「表格没有分片」这个前提是错的：
`|---|` 判定只会在「选区跨了表格」时触发，而它触发后做的是**丢掉精确的 walk、改交原始 Markdown**。
用户框选表头 + 一行数据优先得到 `| Status | Count |`，而不是 `Status` / `Count`。

`md.indexOf(headAnchor)` 更直接：`messageMarkdownCache` 里是**整条消息**的 markdown，
表头文字（如 `Status`）在前文正文里出现时，`indexOf` 命中的是那段前文 → 切片从消息第一句开始。

**实测对照**：`ControllerBehaviourTest` 的两条 copy 用例在改动前分别红在
`copied.contains("|")` 与 `copied.contains("oddly")`（见 §3.2 M4 / M5）。

---

## 2. 修法

### 2.1 F-02：键改成 `(space, block, sub)` 三元 + 位置改 `(y, x)`

```kotlin
internal data class ShardOrderKey(val space: String, val block: Int?, val sub: Int)

internal fun shardOrderKey(shardId: String): ShardOrderKey? {
    val hash = shardId.indexOf(SHARD_SUB_INDEX_SEPARATOR)          // '#' 先剥离
    val core = if (hash >= 0) shardId.substring(0, hash) else shardId
    if (core.startsWith(TABLE_SHARD_SCOPE_PREFIX)) return null     // 表格：几何序
    val sub = ...substring(hash + 1).toIntOrNull()                 // "3#2" → 2
    val lastColon = core.lastIndexOf(':')
    val block = ...core.substring(lastColon + 1).toIntOrNull()      // "mdblock:12:3" → 3
    if (block == null && sub == null) return null
    val space = if (block != null) core.substring(0, lastColon) else core
    return ShardOrderKey(space, block, sub)
}

internal fun compareShardOrder(a: ShardOrderKey, b: ShardOrderKey): Int? {
    if (a.space != b.space) return null                             // 跨空间：不知道，不是「相等」
    val byBlock = if (a.block != null && b.block != null) a.block.compareTo(b.block) else 0
    return if (byBlock != 0) byBlock else a.sub.compareTo(b.sub)
}
```

**`space` 是这次的核心判断，报告没提。** 两个 `mdblock` 分片的 id 都叫 `mdblock:<parent>:<idx>`，
而 `idx` 是 `AppendOnlyMarkdownSegmenter` 在**各自流式文本块内**从 0 计数的
（`StableChatRowLedger.kt:344` `blockIndex = slot.ordinal`，`AppendOnlyMarkdownSegmenter` 的 KDoc 写明
"for a single streaming text block"）—— 所以一条带工具调用的回复里，`mdblock:A:0` 与 `mdblock:B:0`
是两个完全不同的位置。旧代码 `substring(lastColon+1)` 抹掉了 parent，只留 `0`，这就是它「有键时也会错」的原因；
`tbl:<i>` 同理（每个片段内的表序号都从 0 起）。`space` 让「不同 parent 的同序号」变成
**不可比**（返回 null → 交回几何），而不是「相等」。宁可退到几何，不可给错序：
一个错的 `orderedEndpoints` 会让 walk 提前 break，复制出被截断的半句话。

表格单元格**不产生键**（返回 null）。理由：它们本来就共用一个 y 且 x 严格递增，
几何序 `(y, x)` 是精确的；而 `tbl:<i>` 在跨片段时会重号，键反而引入歧义。
F-02② 要的「同 y 不同 x」由几何层解决，不需要键。

几何层改成二元组：

```kotlin
internal data class ShardOrderPosition(val y: Float, val x: Float) : Comparable<ShardOrderPosition> {
    override fun compareTo(other: ShardOrderPosition): Int =
        if (y != other.y) y.compareTo(other.y) else x.compareTo(other.x)
}
```

`registeredShardsInOrder()` 同步改成 `compareBy<TextShard> { it.positionInWindow().y }.thenBy { ...x }` ——
漏了这处，复制出来的顺序仍是「同一行内按注册表迭代序」，而那是 Compose 的 snapshot map 迭代顺序。

**「端点回收 ≠ 选区不存在」** 是任务书点名的核心语义，单独一层：

```kotlin
private val lastKnownPosition = HashMap<TextShardId, ShardOrderPosition>()

fun unregister(id: TextShardId) {
    val shard = shards[id]
    if (shard != null) {
        val p = shard.positionInWindow()
        if (p.x != 0f || p.y != 0f) lastKnownPosition[id] = ShardOrderPosition(p.y, p.x)
    }
    shards.remove(id)
}

internal fun windowPositionOf(id: TextShardId): ShardOrderPosition? =
    shards[id]?.let { ShardOrderPosition(it.positionInWindow().y, it.positionInWindow().x) }
        ?: lastKnownPosition[id]
```

两个细节：①**必须在 `shards.remove` 之前**读位置，之后读的是已 detach 的坐标节点（恒 `Offset.Zero`，
会被误读成「屏幕左上角」并让整个序反转）；②`Offset.Zero` 一律跳过 —— 那是 detach 的信号，不是真实坐标
（`handleAnchor` 早就因为这个理由返回 null）。缓存随 `clearSelection()` 清空，生命周期不外溢到下一次选区。

`isShardBetween` 与 `orderedEndpoints` 都改成「键优先、位置兜底」的纯函数委托：

```kotlin
internal fun isBetweenEndpoints(orderA, orderB, orderMiddle, posA, posB, posMiddle): Boolean {
    if (orderA != null && orderB != null && orderMiddle != null) {
        val cmpAB = compareShardOrder(orderA, orderB)     // 不可比 → null
        val cmpAM = ...; val cmpBM = ...
        if (cmpAB != null && cmpAB != 0 && cmpAM != null && cmpBM != null) {
            return if (cmpAB < 0) cmpAM <= 0 && cmpBM >= 0 else cmpAM >= 0 && cmpBM <= 0
        }
    }
    if (posA == null || posB == null || posMiddle == null) return false
    return isPositionBetween(posA, posB, posMiddle)       // (y, x) 联合
}
```

`cmpAB == 0`（两个不同分片落在同一槽位）也退回几何：键此时不携带任何顺序信息。
**抽出纯函数是为了可测** —— 真 `SelectionController` 要 `TextLayoutResult`，JVM 上建不出来；
`composerCommitCaret()` 是同一先例。

### 2.2 F-09：去掉表格分支，锚点从前序分片偏移起

```kotlin
private fun sliceCrossesNonShardBlock(slice: String): Boolean {
    if (slice.contains("```") || slice.contains("~~~")) return true
    if (slice.contains("$$")) return true
    return slice.contains("\\[")            // 原 tableDelimiter 分支删除
}
```

**补 `\[` 的理由不是报告说的那样。** 报告写「块级公式无分片」，但真正的理由是
`RenderMathDisplay`（`StreamingMarkdownText.kt:1648`）渲染的是
`KatexWebViewPool.render()` 出来的 **Bitmap + Image** —— 一个图片节点，连 `Text` 都不是，自然没有 shard。
`$$` 覆盖不了它（`MdBlock.MathDisplay` 的源码写法是 `\[...\]`），所以选区跨过一条块级公式时，
walk 会**静默吞掉**那条公式。这是与代码块同类的漏内容，不是格式噪声。

```kotlin
val searchFrom = rawSearchFloor(first, documentRegistry).coerceIn(0, md.length)
val startIdx = md.indexOf(headAnchor, searchFrom)
```

`rawSearchFloor` 取「同消息、文档序严格在 `first` 之前的全部分片」的 `plainText` 长度之和。
**这是下界不是估算**：任何分片的 markdown 原文长度 ≥ 它渲染后的纯文本长度
（`## T` → `T`、`| a |` → `a`、`**b**` → `b`、`\*` → `*`），所以和不会超过真正的起点 ——
最坏情况是退化成原来的 `indexOf(anchor)`，绝不会把真锚点跳过去（跳过去会返回 null → 保留精确 walk，也是安全的）。

取长度和而不是取「最后一个前序分片的结束偏移」，是因为分片之间隔着空行（`\n\n`）等非分片字符，
长度和天然是保守的。跨消息 / 键不可解析时 `rawSearchFloor` 返回 0，行为与改动前一致。

---

## 3. 装置与读数

装置目录 `/var/minis/shared/work/meta-audit-1028/probe-c4/`（可复现，**不在仓库内**）：

| 文件 | 用途 |
|---|---|
| `stubs/*.kt` | Compose runtime / geometry / text / layout / drawscope 的 JVM stub（12 个类，零行为），+ JUnit 的 `Test`/`Assert`/`Assume` + `RunTests` 反射 runner |
| `ControllerBehaviourTest.kt` | 6 例，驱动**真实** `SelectionController` + 真实 `TextShard` |
| `mutate.py` | 5 条变异臂：把某一处判据改回改动前的形状，重编译真源码 + 两套测试，报「谁红了」 |

被测代码**零复刻**：`MinisTextKitSelection.kt` 原文件直接进 `kotlinc`，测试类也是仓库里那一份。

### 3.1 两套测试（同一份仓库源码）

| 类 | 位置 | 例数 | 结果 |
|---|---|---|---|
| `MinisSelectionTableTest` | **仓库** `app/src/test/...`（CI 跑的就是它） | 16（原 6 + 新 10） | 16 passed / 0 failed |
| `ControllerBehaviourTest` | 仅探针（需 `TextLayoutResult`，仓库单测建不出） | 6 | 6 passed / 0 failed |

仓库侧的 10 例分两类：
- **纯函数字面量**（7 例）：`shardOrderKey` 对 `"mdblock:12:3#2"` / `"legacy-text#4"` / `"3#0"` / `"tbl:0#1"` / `"3"` 的返回；
  `compareShardOrder` 的同空间/跨空间；`isPositionBetween` 同 y 不同 x；`endpointComesFirst` 的位置缺失；
  `isBetweenEndpoints` 的回收端点 + 行内分列。**期望值全部写字面量**，有一个还专门钉住旧算术
  `assertNull("0#1".toIntOrNull())` —— 谁把 `#` 剥离删掉，这条先红。
- **形状断言 + 3 条负控**（3 例）：确认 `orderedEndpoints` 接了 `endpointComesFirst`/`windowPositionOf`、
  `unregister` 在 `shards.remove` **之前**捕获、`registeredShardsInOrder` 有 `thenBy`、
  `sliceCrossesNonShardBlock` 里没有 `tableDelimiter`、`spliceNonShardSpan` 用 `rawSearchFloor`。
  三条负控分别把「全局锚点」「表格分支」「缓存」改回去，断言探针**必须**变红 —— 否则探针就没牙。

### 3.2 变异臂（最终树上跑，每条只红钉它的那几例）

| 臂 | 改动 | 红 |
|---|---|---|
| M0 control | 无 | 0 / 22 全绿 |
| M1 old-key | `shardOrderKey` 恒返回 null（= 改动前的生产状态） | 仓库 `shardOrderKey …`；探针 `copying across a code fence …`（键没了 → 下界 0 → 又从前文起） |
| M2 y-only-geometry | `isPositionBetween` 退回 `ym in minOf(ya,yb)..maxOf(ya,yb)` | 仓库 `same table row is ordered by x`、`isBetweenEndpoints …`；探针 `only the selected columns …` |
| M3 no-last-known | `unregister` 不捕获 + `windowPositionOf` 恒 null | 仓库 `… wired to those cores`；探针 `ordered endpoints survive an endpoint being recycled` |
| M4 table-delimiter | 恢复 `Regex("""(?m)^\s*\|?\s*:?-{3,}.*\|""")` | 仓库 `… wired to those cores`；探针 `copying across a table header …` |
| M5 global-anchor | `md.indexOf(headAnchor)`（去掉 `searchFrom`） | 仓库 `… wired to those cores`；探针 `copying across a code fence …` |

M2 值得单说：**只有探针那条红**。仓库侧的几何用例调的是 `isPositionBetween` 本身，
所以「行内分列」在纯函数层被钉住；而探针组走的是真控制器 + 真 y/x lambda，证明接线也对。

### 3.3 形状断言前后两态

仓库测试里的形状探针在**改动前的树上**必然全红（`spliceNonShardSpan` 没有 `rawSearchFloor`、
`sliceCrossesNonShardBlock` 有 `tableDelimiter`、`unregister` 没有 `lastKnownPosition`），
三条负控是把这三点逐条恢复后重跑同一套断言 —— 每条都确认变红，即「探针确实在测这三个形状」。

---

## 4. 本地门

```
python3 scripts/scan/chat_ui_interaction_check.py .              → CHAT-UI-INTERACTION GATE OK
python3 scripts/scan/chat_ui_interaction_check.py --selftest     → PASS (86 fixtures)
python3 /var/minis/skills/change-ladder/scripts/scan_debt.py code <file> → 0 标记 / 0 no-trigger
```

`shardOrderKey` / `sliceCrossesNonShardBlock` / `orderedEndpoints` / `registeredShardsInOrder`
在本仓**没有被任何门或夹具按符号钉住**（`rg -ln` 全 `scripts/` 只命中 `chat_ui_interaction_check.py` 的
文件路径与 `test_scan.py` 的路径常量），所以本支不动门。全量 `scan.sh` / `test_scan.py` 按任务书禁跑。

---

## 5. CI

`build-apk.yml` @ `fix/meta1028-c4-selection-md`，run **`40682612218`**（#2830，dispatch 于 11:47:02Z）
→ **success**，20.0 min（11:47:02Z→12:07:04Z）。`head_sha = 825fbc5b8396229991578ee34ae2c61be571a1ca`
与本地 `git rev-parse HEAD` **逐字符一致**。27 步里唯一非 success = 「Move the rolling tag」+「Publish to Releases」
（均 main 门控 skip，分支上正常）。

日志 1,506,260 B / NUL 0，逐条核：

| 判据 | 读数 |
|---|---|
| `Summary: 113 passed, 0 failed` | 1 次 |
| 门块编号 `[N/113]` | 113 个互异，`[113/113]` 在 |
| `ALL 349 CASES PASS` | 1 次 |
| `^e: `（Kotlin 编译错） | 0 |
| 含 `FAILED` 的行 | 6 条，逐条确认全是 **PASSED 用例名的子串**（`... produces FAILED without calling provider PASSED` 等） |
| `MinisSelectionTableTest` 用例 | **16 条全部 PASSED**（含 10 条新增） |

---

## 6. 残留（三件套齐，交主会话并入 backlog）

| # | 事项 | P 级 | 触发条件 | 触发后方案 |
|---|---|---|---|---|
| R1 | `handleAnchor` 仍在端点未注册时返回 null（手柄本来就画不出来，需要真实 bounding box） | P2 | 有用户报「拖到屏幕外手柄该跟手」 | 手柄位置改用 `lastKnownPosition` + 估算行高，需真机调参 |
| R2 | `shardOrderKey` 的 `space` 只到 parent 粒度：同一 parent 内如果分片 id 重号（当前不会发生，`ordinal` 单调） | P2 | 分片器改成「按内容重新编号」 | 把 `ordinal` 换成「已消费 markdown 前缀长度」，键改成 `(space, markdownOffset)` |
| R3 | `rawSearchFloor` 的「markdown ≥ 渲染文本」假设，遇到自定义渲染（如把 `:emoji:` 展开成 emoji 字符串）会低估 | P2 | 出现「下界偏小导致仍从前文起」的可复现报告 | 改为「逐分片定位 + 校验切片长度 ≈ walk 长度」，代价是 O(n) 次 indexOf |
| R4 | `lastKnownPosition` 是普通 `HashMap`，靠 `clearSelection()` 回收；如果宿主永不 clear（当前 UI 一定会 clear） | P2 | 内存里出现无界增长（`memspike` 日志可见） | 加 LRU 上限或按 messageId 分桶，退订时整桶删 |
| R5 | 表格单元格不产生键（几何序），超大表格滚出屏幕后端点重入可能顺序抖动 | P2 | 真机报「大表格拖选高亮跳」 | 表格 shardId 里带上父片段 id，使 `tbl:<parent>:<i>` 可跨片段比较 |

---

## 7. CI 结果

同 §5。**停止节点已达：分支 CI 绿 = 本轮结束，不合并 main。**

回填后需再核一次「源码树与绿 sha 的差异 = 0」：本报告是绿 sha `825fbc5b` 之后唯一新增的提交，
`git diff --name-only 825fbc5b..HEAD -- . ":(exclude)fix-reports"` 必须为空。

---

## 8. 这次值得留下的两条

1. **「有键时也会错」比「没有键」更难发现。** 旧代码唯一能解析出非 null 键的形态，是把表序号当块序号 ——
   也就是说，如果哪天有人只补 `#` 剥离而不补空间隔离，测试会全绿、真机在「一条回复里两段文字各带一张表」
   时按下错序。**键必须自带空间标识**，跨空间一律返回「不可比」而不是「相等」。
2. **「下界」比「估算」好用。** 锚点搜索的起点不需要精确 —— 只要保证不越过真起点，
   最坏退化成原行为；而精确插值一旦估过就会跳空（返回 null，丢掉代码块恢复）。
   凡是「猜一个位置然后剪枝」的场合，优先选方向确定的那个界。
