# FIX-META1028-E4 — offload 工具族五条（meta-audit-1028 波 4）

- **分支**：`fix/meta1028-e4-offload-tools`
- **基线**：main `bfb5b0e6`
- **门提交**：见 §7
- **来源**：B05 报告 F-09/F-10/F-11/F-12 + B04 报告 F-11

---

## 1. 先证纪律

任务书明写「报告转写有损 → 语义先证，证伪登记」。实际核验结果：**报告的行号/函数名转写确实有损（B05 给的 `ShizukuOffloadHandler.kt:689` 是 `display set` 段落的行号，但那条函数体在 :520 附近；B04 给的 `NativeOffload.kt:147,190,386-397` 三处只有 :386-397 落在回复路径上）**，但**五条缺陷的语义骨架全部真码成立**。逐条证据见 §2。

没有一条是「按报告行号照抄」修出来的 —— 每条都是读真码 → 找真正的失效面 → 再定修法。

**证伪/降级的三条**（登记，未修）：

| 项 | 报告声称 | 真码实况 | 处理 |
|---|---|---|---|
| F-10「walk 不提前停」 | `find` 应在满预算后停止遍历 | `find` 的 KDoc 明写「Totals are counted over the whole tree even when the match budget stops collection」，且 `FastioSearchTest` 有断言钉住 `matched == 6L`。**停止遍历 = 破坏已声明且已测试的契约** | 只对齐空预算（limit<=0）一支；满预算不停，KDoc 写明为什么 |
| F-11「预算语义不一致」 | 只说「每命令 vs 每路径」 | 真码里 tar 早已是每命令一次，du/find/grep 是每路径一份 —— 方向对，但根因是**同一个 flag 在两个子命令里花掉两次**，不是"文档说错了" | 按每命令修，并把口径写进 help |
| B04 F-11「账本漏记」 | 只说「登记与删除同源」 | 真码的漏记只有一处（writeText 在 rotate 之前），但另一半（**进程死亡后无人回收**）是账本设计的固有边界，报告没提 | 两条都修 |

---

## 2. 五条逐条

### 2.1 F-09 `display set` 参数语义 —— 真，三形态

**真码**（`ShizukuOffloadHandler.kt:520` 附近，修前）：

```kotlin
private fun displaySet(args: OffloadArgs): NativeOffloadResult {
    var ok = true
    val results = JSONObject()
    args.getInt("density")?.let { ... }
    val w = args.getInt("width"); val h = args.getInt("height")
    if (w != null && h != null) { ... }
    return if (ok) okEnvelope(results, args) else errEnvelope(...)
}
```

三种静默成功（都实测确认）：

1. `display set`（无选项）→ `results` 空、`ok` 仍 true → 返回 `{"ok":true,"data":{}}`。**"你的覆盖成功了"而什么都没跑。**
2. `--density abc` → `getInt()` 返回 null，与"没给"不可区分 → 该调用被跳过，仍报成功。`--density 420.5` 同。
3. `--width 1080`（无 height）→ `wm size` 要 `WxH` 一个实参 → 半对参数被丢掉，仍报成功。

**为什么坏**：调用方随后会重读分辨率。一个「报成功但没生效」比「拒绝」更坏 —— 拒绝至少让人知道没改。

**修法**：决策抽成 `planDisplaySet()`（新文件 `DisplaySetPlan.kt`，纯函数、无 Android 类型，所以整份契约能在 JVM 测）。范围下限：`--width/height` 100..10000 px，`--density` 72..1200 dpi。half-size、非数字、空集、裸选项全部走既有 `errEnvelope("INVALID_ARGS", …)`。**参数名与输出格式零变化**（仍是 `{"ok":true,"data":{"density":true}}`），只有原先静默成功的那些形态现在返回退出码非 0。

**裸选项形态**（第四种）：`--density` 后面没东西时，共享 parser 把它归档为 BOOLEAN（下一个 token 不以 `-` 开头才当值）。所以走 `args.unknownFlags()` 读，而不是 `hasFlag("density")` —— 后者不仅是死分支，还要求 `density` 加入 `NO_VALUE_FLAGS`，那会把每个合法 `--density 420` 的值吃掉。

### 2.2 F-12 `minis-sessions-cli` 未知参数 —— 真，且比报告更宽

**真码**（修前 `handle()`）：`val sub = args.positional.firstOrNull() ?: "list"` 之后直接 `return try { when (sub) … }`。**没有任何 `unknownOption` 检查** —— `FastioOffloadHandler` 有，sessions 没有。

**实测两种落点**（这是关键，也是我第一版判据写错的地方）：

- `list --kewords python` → `kewords` 未声明为布尔、下一个 token 不以 `-` 开头 → 被归档为 **VALUE**：`values["kewords"]="python"`。`unknownFlags()` **根本看不见它**。
- `list --full`（messages 专用）/ 裸 `-z` / `list --start --end 2026-01-01` → 归档为 **FLAG**。

所以守卫必须读**两个通道**。只钉 `unknownFlags` 会让最常见那个 typo 通过 —— 那正是缺陷本身。为此给 `OffloadArgs` 加了 `unknownValueKeys()`（与 `unknownFlags` 对称）。

**顺序也有讲究**：`--start --end 2026-01-01` 里 `--start` 被归档为 flag，而 `start` 是已知名 —— 若先查 unknown-flag，会报「unknown option --start」。这句话**技术上成立但把人带偏**（他会去改选项名，而真正的问题是缺值）。所以**裸值检查在前**。

**修法**：`SessionsOptionGuard.kt`（新，纯文件）+ `handle()` 在 dispatch 之前拒绝（exit 2 + HELP_TEXT）。选项表是唯一判据源，新增选项必须声明在它被读的地方。`send` 同时接受 `--id`（因为 `cmdSend` 有 `args.get("session") ?: args.get("id")` 回退 —— 拒绝 `--id` 会打断那个回退存在的理由）。

### 2.3 F-10 `--limit` 空预算 —— 真（一支），报告的另一半是错的

**真码**：`grepTree` 有 `if (limit <= 0) { out.matchesCapped = true; return }`，`findTree` 没有。调用方 `FastioOffloadHandler` 把 `remaining`（共享预算）传给每个 root，所以**第二个及以后的 root 会拿到 0**，而 `find` 仍整棵走一遍。

**报告没说的约束**：`find` 的 KDoc 明写「Totals are counted over the whole tree」，且 `FastioSearchTest` 钉着 `matched == 6L`。所以**不能在满预算时停** —— 那是把成本上限换成少报的计数，方向反了。

**修法**：只对齐空预算那支（`limit <= 0` 立即 `return` 且置 capped —— 此时调用方要的是 0 条命中，任何 totals 都不可能被读到）。KDoc 把「为什么不停在 cap」写成显式理由，防止下一个人"顺手对齐"。

### 2.4 F-11 `--max-entries` 预算口径 —— 真，方向对但根因更具体

**真码**：`du`/`find`/`grep` 三处都是 `val maxEntries = … ; for (path) { walk(…, maxEntries, …) }` —— **同一个数发给每个 root**。`du a b c d e` 买到 5×2,000,000 的遍历。而 `tar` 早已是每命令花一次（`tarEntryBudget(args)` 只调一次，传给 `tarCreate`）。

**根因**不是"文档说错了"，是**同一个 flag 在不同子命令里花掉的次数不同**。所以修法是「每命令一份、按实际访问量递减」，`DEFAULT_MAX_ENTRIES` 的 KDoc 本来就是这么写的（"one entry budget for the whole command, not per path"）—— 修的是实现回到文档，不是改文档。

### 2.5 B04 F-11 offload 临时文件账本 —— 真，两条 + 一条隐藏的

**真码**（修前 `NativeOffload.kt`）：

```kotlin
tmpHost.writeText(result.output)     // ← 唯一会抛的一步
tmpLedger.rotate(tmpHost)            // ← 抛了就到不了这里
```

**(a) 写失败即泄漏**：写是唯一会抛的步骤（rootfs tmpfs ENOSPC / 线程中断），而写在登记之前 —— 抛了，文件留在盘上，账本从没听说过它。**这正是账本被写出来要堵的泄漏，从它唯一没覆盖的那条路走进来了。**

修：`writeResponseFile()` **先登记再写**，失败时 `reclaim()`（删 + 出账）。调用方 `deliverResponseFile()` 返回 `Result`，`handleClient` 用 `getOrElse` 分支 —— 「交付不了」不能再掉进一个带着不存在路径的成功回复。

**(b) 进程死亡后无人回收**（报告没提）：ring 在内存里，silent kill / OOM / debug 崩溃之后，每个 `.native-offload-*` 留在 rootfs `/tmp`（那是 tmpfs = RAM）里，没有任何东西会再回收它。修：`start()` 里 `sweepStaleTmpFiles(dir)` 扫上一代的孤儿。此时**不可能还有人在读** —— 会去 `cat` 它们的 proot 是随旧进程一起死的那个。只碰本前缀，guest 自己的 `/tmp` 内容不是附带伤害。

**(c) 隐藏的容量缺口**（在 (a)(b) 背后）：账本 `DEFAULT_CAPACITY = 4` 假设 worker 池是旧的固定 2，而池现在来自 `ConcurrencyPrefs.maxConcurrentSessions()`（**1..16**）。16 个并发时，一个 reply 在 **2 个**更新的请求之后就被驱逐，而**还有 14 个 reply 在飞、没被 cat** —— 就是账本存在的理由被一个它从不看的旋钮静默恢复了。

修：`capacityFor(workers) = 2n + 2`（16 → 34），`start()` 里 `resize()`。退化输入（≤1）取下限而不是抛 —— 忘了 clamp 的调用方不该把 offload server 拖垮。

---

## 3. 装置

**JVM 探针** `/var/minis/shared/work/meta-audit-1028/probe-e4/`（树外，不进仓库）：

- `run.sh` —— 编译 **真**生产文件（OffloadTmpFileLedger / OffloadArgs / OffloadOutput / OffloadPageSize / DisplaySetPlan / SessionsOptionGuard / FastioSearch）+ **真**测试文件，Android 类型用桩。
- `slice.py` —— 把 `deliverResponseFile` / `sweepStaleTmpFiles` / `EntryBudget` / `charge` 从**无法离机编译**的 `NativeOffload.kt` / `FastioOffloadHandler.kt` 里**逐字切出**（声明名 + 括号配平），所以测的是真码不是复刻件。锚点唯一性是硬断言（匹配 ≠1 次即 fail-closed），KDoc 上溯有边界（否则会把前一个成员整段吞进来 —— 这条踩了三次才修对：`data class` 没有 body 花括号，扫下去会吃掉下一个函数）。
- `mutate.py` —— 15 个变异臂，一臂一进程（`nice -n 10`，同时刻一个 JVM）。

**78 例 JVM 单测**（5 个类），期望值全是字面量，不从被测常量推导：

| 类 | 例 | 钉什么 |
|---|---|---|
| `DisplaySetPlanTest` | 26 | 三形态拒绝消息字面量、边界值、裸选项、以及 handler 必须真的 `planDisplaySet(args)` 且不再读 `args.getInt("density")` |
| `SessionsOptionGuardTest` | 17 | 两个通道、顺序、表覆盖 help 里每个选项、以及 `handle()` 里守卫在 dispatch 之前且不调用任何 `cmd*(` |
| `FastioBudgetScopeTest` | 11 | 空预算两个引擎一致 + 正预算 totals 仍全量 + 三个 handler 的预算变量必须"声明→传参→递减" + 两条负控（旧的每 root 形状、只声明不递减） |
| `OffloadTmpLedgerClosureTest` | 17 | 写失败回收、写成功不出账、并发不重复删、清扫只碰本前缀、`capacityFor` 与 16 worker 负控、start() 三件事都在 |
| `OffloadTmpFileLedgerTest`（既有） | 7 | 未改动，确认既有契约没被破坏 |

**形状断言（前/后两态）**：三个 wiring 断言在**修前**的树上跑会红（守卫不存在 / `tmpHost.writeText` 还在 / `resize` 没有），修后绿。变异臂是更硬的那一版：15 臂每条只红钉它自己那几个用例，其余保持绿。

---

## 4. 变异负控（15/15）

| 臂 | 变异 | 红钉的用例 |
|---|---|---|
| M1 | 空 `display set` 恢复成功 | 空集 + 无关选项 |
| M2 | 半对 size 恢复成功（改 `||` → `&&`） | width/height 单给 + 带 density 仍拒绝 |
| M3 | `DISPLAY_MIN_DENSITY` 放宽到 0 | density 0 被拒 |
| M4 | 非数字 density 改回"跳过" | 非数字 + 小数 |
| M5 | 关掉 `unknownValueKeys` 通道 | typo（正是缺陷主干） |
| M6 | 关掉裸值通道 | `--start` 裸 + `--ids` 裸 |
| M7 | 守卫不接进 `handle()` | 三条 wiring |
| M8 | 删掉 findTree 的空预算早退 | 三个空预算用例 |
| M9/M10/M11 | du/find/grep 退回每 root 预算 / 只声明不递减 | 各自那一条 |
| M12 | 先写后登记 | 写失败回收 |
| M13 | 失败不 reclaim | 写失败 + deliverResponseFile |
| M14 | 清扫去掉前缀守卫 | 清扫只碰本前缀 |
| M15 | `resize` 回退成固定 DEFAULT_CAPACITY | start() 三件事 |

`MUTATION-SUMMARY: 15/15`。每臂结束后从字节备份恢复，sha256 前后比对（脚本内自校验，不匹配即打印 `RESTORE MISMATCH`）。

**踩坑（值得记）**：
- M2 初版把变异体写成**不合法 Kotlin**（`val wRaw = rawWidth ?: ""; if …`），编译失败表现为"MISS"（0 条红）—— 变异体必须合法，否则恒"绿"。
- M8/M11 的锚点在两个函数里各出现一次 → `ANCHOR COUNT 2`。变异臂锚点必须**在目标函数体内**解析，或带上目标独有的上下文行。
- 一次臂被沙箱并发打断，留下 `.armbak`（变异体还在树里）→ 下一次"基线"会假红。**脚本启动先恢复 stale `*.armbak` 再谈基线**（这条 GLOBAL 记过，本轮又撞上）。

---

## 5. 门

禁跑全量（任务书 + 负载纪律），只跑自家面的门，全 rc=0：

```
shizuku_args_check                 rc=0
offload_cli_args_check             rc=0
offload_relative_path_unify_check  rc=0
offload_host_path_check            rc=0
fastio_tar_bounds_check            rc=0
rss_probe_lock_scope_check         rc=0
debug_leak_guard                   rc=0
modelsvc_single_read_check         rc=0
readonly_mount_write_guard         rc=0   ← 第一轮 CI 红的就是它，见 §7
```

`scripts/scan/**`、`scan.sh`、`test_scan.py` **零触碰**。

---

## 6. 残留（登记，未修）

| ID | 项 | 触发条件 | 触发后方案 |
|---|---|---|---|
| R1 | `find` 满预算仍全量遍历（成本上限只在空预算生效）。`--limit 3` 扫 10 万条树仍走完 | 有人在超大树上用 `find --limit` 且报慢 | 给 `find` 加 `truncated` 标志后才能在 cap 处停（**不能先停** —— totals 是交付物，见 §2.3） |
| R2 | `SessionsOptions` 表是手抄的，新增选项漏声明 → 该选项被静默拒绝 | 有人加 sessions 选项且忘了进表 | 加一个门：help 的每个选项必须在表里（本支测试里已有软版本，见 `every documented option is in the table`） |
| R3 | `sweepStaleTmpFiles` 只扫 `start()` 一次；长驻进程里**本代**的孤儿（cat 失败/连接中断）仍靠 ring 驱逐 | 出现 rootfs /tmp 涨到 MB 级且非本代文件 | 周期性清扫，或按 mtime 阈值 |
| R4 | 交付失败现在是抛异常 → worker 关 socket，guest 侧表现为硬失败（与修前一致）。**没有把"工具失败"和"没法交付"区分开**（协议只认路径，编不出一句人话回复） | 有人报 offload 偶发"什么都没有"且日志里有 `could not write the response tmpfile` | 协议加一个 inline 回复通道（MAGIC_RSP + 长度 + 字节），或至少给一个专用 exit code |
| R5 | `capacityFor` 的 `2n+2` 是拍的，没有实测依据（只证了"16 worker 下 4 不够"） | 出现 worker 数 > 16 或 offload 并发实测 | 按实测的"reply→cat"窗口订 |
| R6 | 真机未验（沙箱无 Android 运行时；`wm` / Shizuku / tmpfs 都不可达）。CI 只保证编译 + 单测 + 门 | 合并 main 后 | 真机跑 `android-shizuku-cli display set --density 420` / `minis-sessions-cli list --kewords x` 各一次 |

---

## 7. 收口

**第一轮 CI（run `40702022868`）failure** —— 红的是我自己写的代码：

```
FAILED CHECKS:
  [26/113] Read-only-mount write guard (rc=1)
    ✗ sandbox/NativeOffload.kt:470 — `.writeText` belongs to no parsed function body
    ✗ sandbox/OffloadTmpFileLedger.kt:176 — 同
```

两处都是我注入的默认写函数 `writeFn: (File, String) -> Unit = { f, s -> f.writeText(s) }`。**参数列表里的 lambda 不在任何函数体内**，而那道门的判据是「每个 write 调用必须能归属到一个解析出来的声明」，归属不了的视为**洞**（fail-closed），不是「不是站点」。门是对的 —— 一个它定位不了的写，正是它必须拒绝放行的东西。

修法：默认值改成具名函数引用 `::writeFileText`（`OffloadTmpFileLedger.kt` 里一行 `file.writeText(text)` 的真函数），写调用就落在解析器跟得到的声明里。行为、可注入性、签名都不变。提交 `60b1880c`。

**★ 可复用**：给 offload 侧注入"写"的时候，**默认实现不要用参数列表里的内联 lambda** —— 那会让 write 调用落在所有函数体之外，触发 readonly_mount_write_guard 的 fail-closed 判据。抽成具名函数（`::name`）即可。

- 门提交：`60b1880c`（`58b44c21` 是首轮）
- push：`fix/meta1028-e4-offload-tools`
- CI：见 §8

报告提交后 `git diff --name-only <门sha>..HEAD -- . ':(exclude)fix-reports'` = **空**（回填不含非报告改动）。

---

## 8. CI 判读

**绿 run `40705027789`（#2849）**，结论 `success`，14:21:00Z → 14:44:03Z（23m03s）。

- `head_sha` = `60b1880c4e6a3e14ab230cd7d3905d8ce8190157`，与本地 `git rev-parse HEAD` **逐字符一致**（防"绿的是别人分支"的并发假绿）。
- 日志 1.5 MB 级，NUL 0 字节。
- 门：`║ Summary: 113 passed, 0 failed`（**恰好 1 条**）；分片 25/44/30/16/19/18 是 scan 并行池的分段输出，**别误读成门数变了**。
- 夹具：`✅ ALL 349 CASES PASS`（恰好 1 条）。
- 6 条含 `FAILED` 的行逐条确认**全是 PASSED 用例名的子串**（判读陷阱第 N 次复核）。
- 8 条 `error:` 全是 workflow 脚本里 `echo "::error::…"` 的**源码文本**，不是运行期错误。
- 唯一的非 success 步 = 滚动 tag + Publish（均 main 门控 skip，分支上正常）。

### 两轮 CI

| 轮 | run | 结果 | 说明 |
|---|---|---|---|
| 1 | `40702022868` | failure | `[26/113] readonly_mount_write_guard` 红 —— 我自己的默认写函数（§7） |
| 2 | `40705027789` | **success** 23m03s | 具名化后绿 |

### ★ 可复用判据（下次别再撞）

给 offload 侧注入"写"的时候，**默认实现不要写成参数列表里的内联 lambda**（`writeFn: (File, String) -> Unit = { f, s -> f.writeText(s) }`）。参数列表里的 lambda 不在任何函数体内，`readonly_mount_write_guard` 的判据是"每个 write 调用必须能归属到一个解析出来的声明"，归属不了即视为**洞**（fail-closed）—— 它是对的：一个定位不了的写，正是它必须拒绝放行的。抽成具名函数用 `::name` 引用即过。
