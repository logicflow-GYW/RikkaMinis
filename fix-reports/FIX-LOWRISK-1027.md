# FIX-LOWRISK-1027 — LOW 档收口支（tar 两条 + 回收报告口径 + 注释纠偏）

**分支**：`fix/lowrisk-drain-1027`
**基点**：`8b2e4d6c3`（= `chore/dead-code-sweep-1027` 的代码尖端，非 main：S3 支动了 243 个文件的 import）
**改动面**：9 文件 +500/−37 —— 生产 7 文件（其中 3 处纯注释）、测试 2 文件
**输入**：`shared/work/backlog-drain-1027/VERDICT-LIVE.md`（S2 33 段逐条取证）+ `TASK-C1-LOWRISK.md`（sha256 `d79e961b5a823c7e…` 已核对）

---

## 1. `Theme.kt` 的 iOS 引用（ZERO · 纯注释）

**原症状**：backlog §91 自列的「顺路修过期注释」欠账 —— `Theme.kt:17 // Teal accent matching iOS visual appearance`，而 iOS 树早已从仓库移除。原文点名的是第 17 行，但同一个句子在同一文件里还出现两次（`:58` 分组卡片底色、`:125` FAB 强调色），三处都在指向一个已不存在的来源。

**改法**：
- `:17` → `// Teal accent — fork brand color (kept after the iOS tree was removed)`（任务书给的措辞）
- `:58` → `// Neutral grouped-card surfaces (system-grouped background, the fork's grouped-card look).`
- `:125` → `// App-wide FAB accent color (warm beige — the fork's New Chat button accent).`

同文件同族三处一次改完（`fix-completeness`：只改一处正是「修 2 处漏第 3 处」的样本）。全仓另有 ~700 处 `iOS` 字样（ChatViewModel 69 / OpenAIProvider 51 …），**本支不碰**——那是另一族口径决策，不在半径内。

**证据**：`rg -n "iOS" Theme.kt` 只剩 `:16` 的那句历史说明（由任务书指定）；行为零变化（3 行注释）。**提交 `5d1caa550`**。

**残留**：全仓 iOS 措辞换代未做（超出本条半径，需单独口径决策）。

---

## 2. `§47 N-2` 假结清：把「零痕迹」收到它成立的地方（不许加 ensureActive）

**原症状**：backlog §47 N-2 记的是「提交尾段无挂起点」的理论边界，但四处的注释把它写成了绝对保证：
`CompactOrchestration.kt:263`、`ChatViewModel.kt:1289`（`compactJob` KDoc/注释块）、`:2883`（clearChat）、`:4089`（cancelStream）——「nothing is persisted before the commit block → a cancelled compact leaves zero trace / killed compact is always safe」。

**回本树取证（真实现状）**：`CompactOrchestration.kt` 的提交块是 `insertCompactMarker(marker)`（**suspend 的 DB 写**）→ `_compactSummary.value = summary` → `_cachedLatestMarker = marker` → `withContext(Dispatchers.Main)`。
- 两个内存写**之间**确实没有挂起点（原文那半句成立）；
- 但取消可以落在 `insertCompactMarker` 上：要么行没提交（= 真「零痕迹」），要么**行已提交而协程再没恢复** —— 此时 DB 有 marker 行、内存里没有 `_compactSummary` / `_cachedLatestMarker`，直到下一次会话加载。`ChatSessionLifecycle.kt:645-646`（loadSession）确实从 marker 行重建这两者，所以后果是「UI 追平」而非数据错乱。
- 因此诚实的说法是 **「比它折叠的文本留下更少、绝不会写两次」**，不是「零痕迹」。

**改法**（纯注释，**不加** ensureActive / isActive / yield）：
- `compactJob` 处给出完整边界说明 + 为什么**故意不加守卫**：该窗口内的写入是 finalize-only（一次 marker insert + 两次内存赋值，没有可撤销的东西），而守卫会在正常取消路径上把 `CancellationException` 从**唯一一个活已经干完的块**里抛出去 —— 用硬失败换一个本来只是「下次加载追平」的表现。
- 另三处短引用改成「对 transcript 安全 + 指向 `compactJob` 的边界说明」，不再复述绝对保证。

**证据**：`git diff` 四处的 `+/-` 行全部以 `//` 开头（`git diff base..HEAD -- CompactOrchestration.kt ChatViewModel.kt` 无非注释行）；`rg -n "zero trace|always safe" src/` 在 conversation/ + ui/chat/ 命中数 4→0。

**提交 `dd57c7eb9`**。

**残留**：N-2 的**行为**层面（真要让尾段也原子，就需要把 insert + 内存写包进一个不可取消的作用域）**未做**——那是行为改动，任务书明确不在本支权限内。登记建议：若将来出现「取消后 marker 落地而 UI 未折叠」的实测症状，再按 `NonCancellable` 收尾段讨论（当前零实测触发）。

---

## 3. `#6 F8` tar 自我入档（LOW · 有测试面）

**原症状**：`tar -cf src/a.tar src` 时，正在写的档会被 walk 到并塞进自己 —— 成员的 size 字段在拷贝前取下，拷贝又把它正在写入的字节一起读进来，size 与 payload 永久失配。实测（沙箱，真 busybox 1.37 对照）：
- busybox：`tar: src/bb.tar: file is the archive; skipping`（跳过）；
- 本引擎（修复前）：一个 200 KB 的树产出 **809,286 B** 的 member 列表，档里塞进了 `a.tar`(203,264 B) **和** `pre.tar`(406,016 B) 两个正在写的档，`tar -tvf` 仍能读（因为第二次的 size 恰好又被写大了），但语义已经完全错。

**改法**：
- `tarCreate(...)` 新增 `archivePath: Path? = null`（宿主路径；null = 调用方没有文件 sink，测试与未来调用方保持旧行为，不猜）。
- 新增 `isSelfArchive(candidate, archive)`：`Files.isSameFile` 比较真实文件（device/inode），所以**指向档的硬链接也算自己**，换个拼写（`./src/a.tar`、符号链接的父目录）也仍命中。
- 成员在 `writeTree` 的子项循环 + 根循环两处跳过；**在 `charge()` 之前**跳过（被跳的条目不构成成员，不该吃预算槽）。
- 根循环的自判定放在**符号链接分支之后**：链接以链接形式写入、从不读取，因此「指向档」≠「是档」。
- 计数 `skippedSelf` / `skippedSelfNames`（`MAX_REPORTED_SKIPS` 上限），JSON 新增 `skipped_self` + `skipped_self_names`，与 `skipped_special` 同形 —— **不许静默**。
- 文档四处同步：`tarCreate` KDoc 参数说明、`writeTree` 的「语义沿用」清单、`skippedSelf` 字段 KDoc、handler 的 USAGE 段。

**证据**：
- 新测试 `an archive written inside the packed tree does not contain itself`：`skippedSelf == 1`、`skippedSelfNames == [<prefix>/pack.tar]`、`files == 1`、解包后树里**没有** `pack.tar`。
- 对照臂 `an archive outside the packed tree keeps every member and counts no self skips`：`skippedSelf == 0`、`files == 1`、`dirs == 1` —— 没有这一臂，「跳过」就分不清「正确跳过」和「根本没接进 walk」。
- 沙箱互操作（真 busybox tar 1.37）：同一命令 `tar -cf src/a.tar src`，busybox 打印 `file is the archive; skipping`，本引擎 `skippedSelf=1 skippedSelfNames=[tmp/self/src/a.tar]`、`tar -tvf` 成员表里没有 `a.tar`；修复前引擎则把两个档都打进去了。
- 变异负控：把 `isSelfArchive` 调用改成 `false && …` → **恰好红 1 条**（本条的 self 测试），对照臂仍绿。

**提交 `d7102e82a`**（含「符号链接优先」的微调）。

**残留**：`fixtures` 之外的真实边界未测——档位于**符号链接父目录**下（`ln -s /real /link; tar -cf /link/src/a.tar /link/src`）；`isSameFile` 本身会解析父链，理论上成立，未造夹具。

---

## 4. `#6 F2` 硬链接成员 extract 后目标文件不存在（LOW · 数据丢失面）

**原症状**：extract 遇 `'1'`（LNKTYPE）只 `skippedHardlink++` 并把名字塞进 `skipped_names`。档里的**普通成员**照常解出，但**链接成员指向的那个路径永远不出现** —— 对一个真实 busybox 档（`ln a.txt b.txt; tar -cf t.tar a.txt b.txt`，成员类型实测 `'0'` + `'1'`）就是纯数据丢失：`tar xf` 会落下 `b.txt`，本引擎什么都不落，而 JSON 把这件事报成「跳过了一种不支持的类型」。

**改法**：
- `tarExtract` 维护 `extractedFiles: HashMap<归一化成员名, 实际写入的 Path>`（普通成员落盘后登记；按**归一化名**索引，`./src/a.txt` 与 `src/a.txt` 指同一成员）。
- LNKTYPE 分支改为：按 `linkName` 归一化后在表里找已解出的普通文件，`Files.copy` 到目标路径，`applyMetadata` 用**链接成员**的 mode/mtime；经过与普通成员**完全相同**的既有目标策略（无 `--force` 拒覆盖、目录永不替换、`--force` 才替换）。
- **为什么复制不建硬链接**：落点可能是 FAT/exFAT SD 卡（没有硬链接），复制是唯一总能落地的实现；KDoc 三处同步写清这一点（`'1'` 分支注释、`hardLinked` 字段、handler USAGE）。
- **目标不存在 = 档本身有缺陷**：记 `errors`（点名缺哪个 target）+ `skippedHardlink++`，**不再静默**，也绝不发明一个空文件。
- 计数：`hardLinked`（新）/ JSON `hard_links`；`files` 计两个（磁盘上真有两个文件），`bytes` **不**加倍（payload 已随普通成员计过一次）。

**证据**：
- `a hard link member is materialised by copying the unpacked target`：`errors` 空、`skippedHardlink == 0`、`hardLinked == 1`、`files == 2`、`bytes == 2`（只计一次）；`b.txt` 存在、是**普通文件**（非符号链接）、内容相同、`Files.isSameFile(a,b) == false`，改写 b 后 a 不变。
- `a hard link whose target was never unpacked is an error, not an empty file`：`skippedHardlink == 1`、`hardLinked == 0`、`errors.size == 1` 且错误文字里含 `nope.txt`、目标文件不存在。
- `a hard-linked tree round-trips as two independent files`：先在**一次性探针目录**里试 `Files.createLink` 并数目录条目——真文件系统（ext4/CI）得到两条目、模拟文件系统（PRoot 用 `.l2s.*` 侧文件模拟）得到三+条目；据此在正式夹具里造「真硬链接」或「同内容两份拷贝」，两种输入写进档里的**成员形状完全相同**（正是本引擎的性质），所以断言在每个文件系统上都**真跑**：`created.files == 2` / `bytes == 12`、解包后两文件内容一致、`Files.isSameFile(a,b) == false`、改写 b 后 a 不变。
- 沙箱互操作（**真 busybox 档**，非 craftHeader）：修复后 `files=2 hardLinked=1`，`a.txt`/`b.txt` 内容一致、inode 不同（4511541 / 4511549）；修复前同一档 `files=1 skippedHardlink=1`，`b.txt` 不在盘上。
- 变异负控：把 '1' 分支改回「只计数 + `continue`」→ **恰好红 1 条**（本条的正向测试；缺 target 那条红的判据不同，未受影响）。

**提交 `488179ddb`**。

**残留**：
1. **前向引用**（链接成员排在目标之前）按「档有缺陷」记 error，不做两遍解析/延迟落地——GNU 与 busybox 都把目标写在前面，为规避格式外的档而上重装置不值当。触发条件：实测遇到一次真实前向引用档。
2. **多级链**（`a` ← `b` ← `c`）：`b` 是复制出来的普通文件，`c` 指向 `b` 时同样能复制到内容 —— 语义正确（内容一致、互不共享），但磁盘上会有 N 份拷贝，与 `tar` 的硬链接语义（一份 inode）不同。这是「以复制实现」的既定代价，KDoc 已写明。
3. **`bytes` 语义**：复制落地的字节不计入 `bytes`（避免与普通成员重复计数）。若后续消费方把 `bytes` 当「腾出的磁盘量」，需重新定义。

---

## 5. `#18 R1` 回收报告漏 `browserTabs`（LOW · 口径完整性）

**原症状**：`reclaimOrphans()` 自 `da1025-D` 起会删 `browser_tabs/<sid>.json`，但 `ReclaimReport` 只有 session + media 五个字段，`totalBytes/totalDirs` 不含这一类 ⇒ 储存页「可释放空间」**少算一整类**：一个只开过浏览器的会话贡献 0 字节，而它点「释放」后确实被删。旧 KDoc 把这写成「刻意最小半径」，等于把口径缺口钉成了设计。

**改法**：
- `ReclaimReport` 增 `browserTabsDirs: Int` / `browserTabsBytes: Long`（沿用 `…Dirs` 家族命名；值统计的是**状态文件**个数，一个会话一个，KDoc 写明）；`totalDirs`/`totalBytes` 三项求和；KDoc 改成「三类 leftover 各是什么 + 加第四类必须同时改 `scanOrphans`」。
- `scanOrphans()` 折入 tab 半边；新增 public `scanOrphanBrowserTabFiles(live)`，让储存页仍然只走一次 media 遍历（它是在页面侧手工装配 `ReclaimReport` 的）。
- 储存页装配处补注释说明三类的口径（为什么不是直接调 `scanOrphans`）。
- **测量半边没有抽公共谓词**：`browser_tabs_lifecycle_check` 的 C3/C3b 把判据**内联**钉在 `deleteOrphanBrowserTabFiles` 体内（那条锚点正是阻止「极性翻转删光活会话 tab 状态」的东西），抽走会把门的锚点挪掉或让两半继续各说各话。于是改为**用测试钉住两半一致**（见下），并在两处 KDoc 互相点名。

**证据**：
- `scanOrphans counts orphan browser tab files and folds them into the totals`：dead 会话有 20 B 会话文件 + 45 B tab 状态 ⇒ `sessionDirs=1, sessionBytes=20, browserTabsDirs=1, browserTabsBytes=45, totalDirs=2, totalBytes=65`；且扫描后文件仍在（measure-only）。
- `the scan and the delete half of the reclaim agree on what is an orphan`：6 形夹具矩阵（`dead.json` / `live.json` / `dead-draft.json` / `not-a-session.json` / 无 `.json` 后缀 / **session 形状的目录**），先 scan 断言「2 项 / 14 B」，再在**同样的第二棵树**上 reclaim，断言存活集合恰为 `{live.json, not-a-session.json, <shapedDir>.json(目录), <无后缀>}` —— 测到的 = 删掉的。
- 既有 `reclaimOrphans deletes orphan browser tab files and keeps live ones` 的断言从「`mediaDirs == 0`（最小半径注释）」升级为 `browserTabsDirs == 1` + `browserTabsBytes == 45` + `totalDirs == 1` + `totalBytes == 45`，旧注释删除。
- 变异负控三条，各红且方向可读：`totalBytes` 去掉 tab 项 → 红 2 条；`scanOrphans` 把 tab 半边填 0 → 红 2 条；scan 半边极性翻成 `stem in live` → 红 3 条（含 agreement 臂）。
- 真树门：`python3 scripts/scan/browser_tabs_lifecycle_check.py --root .` → rc=0（C1-C3/C3b/C7/C8 全绿）。

**提交 `73c8614e4`**。

**残留 / 决策**：
1. **本地化文案未改**：banner 原文是「%1$d orphaned item(s) from deleted sessions — %2$s」，把 tab 状态文件算进去后**仍然准确**（它就是一个已删会话留下的 item）。为一句本来没写错的文案去动 8 份 `strings.xml`，收益为零、`i18n_check` 面变大 —— 口径说明落在代码注释里（`ReclaimReport` KDoc + 装配处注释）。若产品要更细的分项（例如「其中浏览器状态 N 项」）再单独立项。
2. `browserTabsDirs` 这个名字统计的是**文件**：改名会与 `sessionDirs`/`mediaDirs` 家族断裂，任务书也指定了这个名字，故保留 + KDoc 说明。
3. `bytes` 用 `file.length()`（状态文件是普通 json，无 symlink/硬链接语义），未走 `sizeOf` 的 NOFOLLOW/硬链接去重路径。

---

## 6. 未做（任务书硬约束 + 半径纪律）

| 项 | 理由 |
|---|---|
| 111 处 `user.dir` 警告（VERDICT #26/#27） | S2 明确不建议随批做：族比登记大 20 倍（107 文件）、需全族一致口径决策 |
| `build-apk.yml`（§2603/§2604） | 起手权在用户 |
| `scripts/scan/**` | 硬约束；本条反而**受门约束**（第 5 项为门的内联锚点让步） |
| 全量 `scan.sh` / `test_scan.py` | 沙箱并发期 ENOSYS 已知；本地只跑本支相关门与测试，全量证据交 CI |
| §47 N-2 的行为改动（`NonCancellable` 收尾段） | 行为改动，任务书明确排除；无实测触发 |

---

## 7. 验证汇总（本支自证）

| 层 | 内容 | 结果 |
|---|---|---|
| JVM 单测（tar） | `FastioTarTest` 全量 | **OK (35 tests)**（无 skip：硬链接臂用探针式夹具，两种文件系统上都会真跑） |
| JVM 单测（storage） | `SessionFileStoreTest` 全量 | **OK (20 tests)** |
| 变异负控（tar） | 关掉 self 跳过 / 关掉 LNKTYPE 复制 | 各恰好红自己那条（2 红 / 分别） |
| 变异负控（storage） | 总和不含 tab / scan 半边填 0 / 极性翻转 | 2 红 / 2 红 / 3 红 |
| 真工具互操作 | busybox `ln`+`tar -cf`（真 LNKTYPE 档）→ 本引擎 extract；对照修复前 | 修复后 2 文件（inode 不同）；修复前 1 文件 |
| 真工具对照（F8） | 同一命令下 busybox 的 `file is the archive; skipping` vs 本引擎 | 成员表均不含档本身 |
| 真树门 | `browser_tabs_lifecycle_check` / `fastio_tar_bounds_check` / `skill_restore_session_cleanup_check` / `readonly_mount_write_guard` | 4/4 rc=0 |
| 未验证 | 真机行为（tar 自我入档提示、硬链接解包后的可写性、储存页数字）；沙箱无 Android 运行时，上述判别由 CI 编译 + JVM 单测承担 | — |

## 8. CI 首轮失败与修正（诚实记录）

首轮 `run 40437295592`（head `3c99d0506`）：**预编译门禁 112 道全绿、编译通过**，失败在「Run unit tests (full suite)」的**一条本支新测试**上：

```
FastioTarTest > a real hard link in the tree survives the round trip as two independent files FAILED
java.lang.AssertionError: expected:<11> but was:<12>   (FastioTarTest.kt:382)
```

`"shared"` 是 6 B × 2 = 12，我写了 11 —— 而这条测试在沙箱里被 `Assume` 跳过（PRoot 模拟硬链接，目录多出 `.l2s.*` 条目），**本地一次也没真执行过**，算术错误直接送到 CI。

修正不是改数字，而是**取消 skip**：改成「探针目录先探真/模拟 → 正式夹具按探测结果造真硬链接或同内容两份拷贝」，两种输入的档内成员形状相同，断言在每个文件系统上都真跑（已本地验证 35/35 全执行、0 skip）。教训入账：**`Assume` 跳过 = 没测**，任何一行只在 CI 才跑的断言都要当作未验证对待。

## 9. 分支 CI 收口（第二轮 run，attempt 1）

**run `40439428585` / job `121975913449` / attempt 1 / success @ `5c46523ea76ff57d…`**（02:36:40→03:01:03Z，24m23s），head_sha 与本地 HEAD 逐字符一致。

日志 1,466,069 B / NUL 0，逐项核验：

| 判据 | 读数 |
|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 |
| `ALL 347 CASES PASS` | 恰 1（基点分支的 test_scan = 347，不是 main 线的 344） |
| `^ e: ` 编译错误 | 0 |
| `FAILED` 行 | 6 行，全部是 PASSED 用例名的子串（已知判读陷阱） |
| Publish 步骤 | skipped（分支门控，正确） |
| 本支 7 条新/改测试逐名 | 全部 PASSED（硬链接往返 / 自我入档 + 对照臂 / 复制落地 / 缺 target 记 error / scanOrphans 折入合计 / 两半一致 / 既有 reclaim 断言升级） |

首轮失败（§8）→ 修正 → 第二轮绿，**绿 sha 即修正后 sha**，无遗留红项。

**本地未跑全量 `scan.sh` / `test_scan.py`**：沙箱并发期 `OSError: Errno 38` 已知（`shared/sandbox-limits.md`），全量读数以本节 CI 日志为准。
