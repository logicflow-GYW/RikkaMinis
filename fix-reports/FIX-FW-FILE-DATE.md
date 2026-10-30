# FIX-FW-FILE-DATE — 文件列表「未来时间戳显示成星期名」

分支 `fix/fw-file-date-future`　基线 main `039c9ab2`　工作树 `wt-fw-date`

## 1. 先证后修

### 1.1 代码现状（本会话在 main 上实测，`FileBrowserViewModel.kt:220-241`）

```kotlin
val formattedDate: String
    get() {
        if (modifiedMs <= 0L) return ""
        val date = Date(modifiedMs)
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { time = date }
        val sameDay = …YEAR + DAY_OF_YEAR 相同…
        if (sameDay) return timeFormatter.format(date)         // "h:mm a"
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (…yesterday…DAY_OF_YEAR…) return "Yesterday"
        val daysAgo = (now.timeInMillis - then.timeInMillis) / (24L * 60 * 60 * 1000)
        if (daysAgo in 0..6) return weekdayFormatter.format(date)   // "EEEE"
        return shortDateFormatter.format(date)                      // "MMM d, yyyy"
    }
```

**E1 成立**（任务书描述与源码逐字一致）：`modifiedMs` 在未来时跳过同/昨日，`daysAgo`
为负；Kotlin `Long` 除法**向零截断** ⇒ 未来 −1h、−23h 都得到 `0` ⇒ `in 0..6` 命中 ⇒
**星期名**。`FileBrowserScreen.kt:348` 消费 `item.formattedDate`（未改动）。

**E2 成立**：`daysAgo == 0` 在数学上不可能是「过去」（非同日、非昨日、且不足 24h
⇒ 只能来自未来）。窗口 `0..6` 里那个 `0` 就是缺陷入口。

**可达性**：`modifiedMs` 来自 `resolved.lastModified()`（`FileItem.from`），即宿主机文件
系统给的 mtime —— PRoot 沙箱、外部挂载（NFS/SMB/共享目录，mtime 来自**服务器**时钟）
都能让它落到本地时钟之前。不需要「用户手动改时间」这种极端前提。

### 1.2 证伪 / 修正

- 任务书说「未来 -1 小时 ⇒ daysAgo == 0 ⇒ 星期名」——成立，且**范围更大**：任何
  `0 < future − now < 24h` 都落进同一分支（截断后的商恒为 0），不只是 −1h。
- 任务书假定「既有测试覆盖这个 ViewModel」——`rg -l FileBrowserViewModel src/test/`
  只有两支**形状测试**（`SessionPathResolutionTest` 数 `resolvePathForSession(` 调用点、
  `SandboxPathGuardWiringTest` 读 `deleteItem()` 切片），**没有任何断言碰 `formattedDate`**
  ⇒ 本次是首个行为测试，不存在既有期望被改动。

## 2. 修法

### 2.1 抽出纯函数（可注入 `now` 与 `zone`）

```kotlin
internal enum class FileDateBucket { EMPTY, FUTURE, SAME_DAY, YESTERDAY, WITHIN_WEEK, OLDER }

internal fun fileDateBucket(
    modifiedMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault(),
): FileDateBucket {
    if (modifiedMs <= 0L) return FileDateBucket.EMPTY
    if (modifiedMs > nowMs) return FileDateBucket.FUTURE      // ← 显式早退，先于任何日期算术
    …sameDay / yesterday（同原判据，Calendar 显式设 timeInMillis）…
    val daysAgo = (nowMs - modifiedMs) / DAY_MS
    if (daysAgo in 1..6) return FileDateBucket.WITHIN_WEEK
    return FileDateBucket.OLDER
}
```

`formattedDate` 现在只是 `when (fileDateBucket(...))` 的格式化外壳（12 行）。

### 2.2 三个决定与理由

1. **窗口 `0..6` → `1..6`**：FUTURE 早退之后 `0` 已不可达（过去 + 非同日 + 非昨日 ⇒
   ≥ 1 天）。把 `0` 留在窗口里等于留一个「未来会以星期名显示」的后门；去掉后残留 0
   只可能落到 `OLDER`（绝对日期）——失败方向从「编造一个上星期四」变成「显示日期」。
   变异臂 A3 验证：**把 `0..6` 加回来、FUTURE 臂在，全套仍绿** ⇒ 这条是纵深防御，
   不是行为改变。
2. **未来显示 `"Just now"`**（不是绝对日期）：未来 mtime 的物理含义几乎总是「写它的
   时钟比我快」（PRoot 沙箱 / 挂载点服务器时钟 / 刚被漂移的 guest 写完），所以
   「这是刚出现的文件」才是诚实的读数。绝对日期反而会在文件**一秒钟前刚出现**时印出
   明天甚至明年的日期——正是本缺陷要消除的那种误读。与同日的 `h:mm a` 形态可区分；
   `now+1ms` 与「1 秒前」都显示 Just now 是刻意收敛（用户本就区分不了）。
3. **`"Yesterday"` 等英文硬编码保持原样**：本支不做 i18n（要动 7 个语言文件，与一行
   判据修复的装置比例失衡）⇒ 登记为残留 R2。

### 2.3 改动面

| 文件 | 变化 |
|---|---|
| `ui/sandbox/FileBrowserViewModel.kt` | +93/−14（新增 enum + `DAY_MS` + `fileDateBucket` + import `TimeZone`；`formattedDate` 改写成 6 臂格式化） |
| `ui/sandbox/FileDateBucketTest.kt`（新） | +195，11 例 |

`FileBrowserScreen.kt`（只消费 `formattedDate`）、`scripts/scan/**`、`scan.sh`、
`test_scan.py`、i18n **零触碰**。

## 3. 装置与验证

### 3.1 JVM 夹具（`shared/work/fixwave-1030c/probe-e/`）

`extract.py` 从**生产源文件逐字切片**（enum / `DAY_MS` / `fileDateBucket` /
`formattedDate` getter / 三个 formatter），每片断言仍是被测文件的子串；`harness.kt`
只重建 `FileItem` 的构造签名 + 粘贴该 getter（`@Immutable` 因 Compose 不在 JVM
classpath 上而省略）。⇒ 测试打的是**上线的那段文本**，不是手抄副本。
**11 例全绿**（`OK (11 tests)`，JUnit 4.13.2，kotlinc 2.1.0）。

边界表（全部字面量，`nowMs = 1778760000000` = 2026-05-14T12:00:00Z，zone 注入 UTC）：

| mtime | 期望 |
|---|---|
| `now+1ms` / `+1h` / `+23h` / `+400d` | `FUTURE` |
| 23:30 的 now + 2h（跨日） | `FUTURE` |
| `now−1ms` / `−12h` / `now` | `SAME_DAY` |
| `now−13h` / `−23h` / `−24h` / `−25h` | `YESTERDAY` |
| 跨年（2026-01-01T00:30 − 1h） | `YESTERDAY` |
| `now−2d` / `−6d` / `−(6d23h)` | `WITHIN_WEEK` |
| `now−7d` / `−8d` | `OLDER` |
| `0` / `−1` / `−1d` | `EMPTY` |

另有：未来偏移全表 `== FUTURE` 的扫荡断言、`formattedDate` 端到端 5 个未来偏移
`== "Just now"` 且不含任何英文星期名、以及 3 条源码形状判据（见 3.3）。

**时区**：纯函数带 `zone` 参数（默认 `TimeZone.getDefault()`），测试注入 UTC
——**不用 `TimeZone.setDefault`**，避免污染同 JVM 的其它测试。端到端那两条断言只看
FUTURE 臂（在任何时区都成立），因此不依赖默认时区。

### 3.2 变异臂 9/9 MATCH

夹具臂（改切片 → 跑全套，断言指定用例转红）：

| 臂 | 变异 | 结果 |
|---|---|---|
| A1 | **还原缺陷代码**：删 FUTURE 臂 + 窗口回 `0..6` | RED 5（含 4 条未来用例）+1 条附带红 |
| A2 | 只删 FUTURE 臂（`1..6` 留着） | RED 5 |
| A3 | 只把 `0..6` 加回来（FUTURE 臂在） | **GREEN（预期内：0 已不可达）** |
| A4 | 窗口放宽到 7 天 | RED「within the last week」 |
| A5 | FUTURE 显示绝对日期 | RED 2 |
| A6 | FUTURE 显示同日钟点 | RED 2 |

形状臂（`formattedDate` 的判据读的是**仓库源文件**，故必须改「退化树里的那份文件」
并以该树为 `user.dir` 跑 —— 改夹具无效）：

| 臂 | 变异 | 结果 |
|---|---|---|
| S1 | 换成 `git show HEAD:` 的**修前源文件** | RED（失委托） |
| S2 | 保留委托，但 getter 里用 `DAY_MS` 重算 `daysAgo` | RED（`DAY_MS` 判据有牙） |
| S3 | 保留委托，但长出 `in 0..6` 星期分支 | RED（`in 0..6` 判据有牙） |

**★ 首轮 A2/A7 两只「臂」判 ROT，都是装置问题不是代码问题**：
- A2：原断言写的是「未来不落 `WITHIN_WEEK`」，而 `1..6` 结构上已保证这点 ⇒ 断言**不可能红**
  = 装饰性断言。改成「任何未来偏移都必须 `== FUTURE`」后 A2 转红（改名
  `no future offset falls into a past bucket`）。
- A7：调的是形状判据，而形状判据读仓库文件、不读夹具 ⇒ 夹具变异对它隐形。正确装置是
  3.2 的**退化树**（S1-S3）。这一条与「源码形状门也是读者、符号 grep 抓不到」同族：
  **改夹具之前先问「这条判据读的是什么」**。

### 3.3 源码形状判据（长期契约）

`formattedDate` 的 getter 切片（到下一个 `/**` 或 `companion object` 为止）必须：
① 出现 `fileDateBucket(modifiedMs`；② 不出现 `DAY_MS` 或 `24L * 60`（截断算术不许回潮）；
③ 不出现 `in 0..6`。三条各有独立变异臂（S1/S2/S3）。

### 3.4 本地门禁

- `sh scripts/scan/scan.sh --jobs 3` → **113 passed, 0 failed**
- `python3 scripts/scan/test_scan.py --jobs 3`（后台 + 重定向）→ **ALL 349 CASES PASS**

## 4. CI

| 项 | 值 |
|---|---|
| run | `41016258010`（attempt 1，`workflow_dispatch`） |
| head_sha | `ac4886504ec49087110ada5cdbd7ca00102cea31` — 与本地 HEAD **逐字符一致** |
| 结果 | **success**，06:48:34Z → 07:08:51Z ≈ 20.3 min |
| 步骤 | 27 步：25 success + 2 skipped（`Move the rolling tag` / `Publish to Releases`，main 门控） |
| 日志 | 1,682,400 B，**NUL 0** |
| 门禁 | `Run pre-build scan gate` success，`Summary: 113 passed, 0 failed`；`ALL 349 CASES PASS` |
| 编译 | `^e: ` 0 行；真 ` FAILED$` 0 行 |
| 新测试 | `FileDateBucketTest` 11 例 **全 PASSED**（逐条见日志 06:59:45 段） |

绿后追加本报告提交，不再 dispatch（无验证面）；绿 sha 之后的分支 tip **只多这一份报告**。

## 5. 残留（带触发条件）

| # | 残留 | 触发条件 / 处理 |
|---|---|---|
| R1 | **其它列表页的时间显示是否同源**：`FileBrowserScreen` 是唯一消费 `formattedDate` 的地方，但其它列表（备份快照行、会话列表、共享文件夹注册表、日志列表）各自有日期渲染 | 出现「某列表把未来时间显示成星期名 / 日期不像刚生成」的报告 → 先 `rg -l 'EEEE' src/android` 定位同源实现，再考虑把 `fileDateBucket` 提为公共工具 |
| R2 | `"Yesterday"` / `"Just now"` 是硬编码英文，未本地化（本文件原本即如此） | 真机出现中文界面上夹英文的反馈 → 一并做 i18n，需动 7 个语言文件（那时装置比例才合理） |
| R3 | 未来判据用 `System.currentTimeMillis()` 的**单次读数**，getter 每次重组各读一次 —— 理论上同一行可能在两次重组间跨桶（例如 23:59:59.999 → 00:00:00） | 屏幕出现「时间突然跳变」的报告时再看；当前表现与修前一致，不是本支引入 |
| R4 | 距「昨天」判定依赖 `Calendar.DAY_OF_YEAR` + `YEAR`，跨 DST 的极端时区（如太平洋岛屿的日期跳变）未覆盖 | 出现真实报告再补；本地表用 UTC 与常规时区，覆盖不到政府历法跳变 |
| R5 | 真机未验（全部证据 = JVM 表 + 变异臂 + 形状判据 + CI 编译级） | 下次真机操作「往沙箱写文件后看列表时间」时顺手确认显示 `Just now` 而非星期名 |
| R6 | `daysAgo` 用固定 24h 差算「几天前」，逢 DST 切换日会偏 1 小时（可能把 6d23h 判成 7d→绝对日期） | 与修前同款行为（原代码同一算法），不是回归；若出现「刚好一周前的文件显示日期」的报告再改成日历日差 |
