# FIX-META1028-D3 — 沙箱 rootfs 重建路径六条 P0（F-01..F-06）

- 分支：`fix/meta1028-d3-sandbox-p0`，基线 main `bfb5b0e6`
- 任务书：`/var/minis/shared/work/meta-audit-1028/fix-tasks/TASK-D3.md`
- 来源报告：`/var/minis/shared/work/meta-audit/reports/r2/Report__2__1.md`（R2 货架 B04，六条 F-01..F-06）
- 停止节点：**分支 CI 绿**（到点即结束，不合并 main）
- 装置：`/var/minis/shared/work/meta-audit-1028/probe-d3/`（树外，不进仓库）

---

## 1. 摘要

六条全部落在**同一个抽象面上**：rootfs 目录被删除重建之后，「谁该重新生效」这件事没有出口。
区别只在失效的形态：

| 条 | 失效形态 | 一句话 |
|---|---|---|
| F-01 | **缺一次写入** | 重建后的树里没有 `/bin/bash`，因为只有 repair 路径会装离线三件套 |
| F-02 | **缺一条通知** | 重建发生了，但进程里没有任何人知道，所以没人重放启动期的副作用 |
| F-03 | **快照被降级** | 重建把 db 打回出厂内容，下一次 dump 拿它覆盖了好的快照，再下一次 restore 「成功」并删掉待恢复清单 |
| F-04 | **持锁时移除锁** | stop 的语义恰好是「有个命令正持着锁」，却在这里把锁对象换掉 |
| F-05 | **stat 而非 lstat** | 问「这个条目多久没动」，却读了「它指向的文件多久没动」 |
| F-06 | **贪婪截断 + 漏检** | `${arg##*[t]}` 截到的是路径里最后一个 `t`，不是选项字母；`mv` 的 `-tDIR` 整个被跳过 |

共同点值得单独记一笔：**六条里没有一条是「代码写错了算法」**——每条在孤立看时都自洽，
失效全部来自「这个东西的生命周期」和「谁负责维护它」之间的错配。这也是为什么它们能同时
活到被审计报告点出来。

---

## 2. 逐条：先证 → 修法 → 装置

### F-01 离线三件套在唯一的重建入口缺失 —— **真，已修**

**先证。** `assets/alpine-minirootfs.tar` 共 521 条，grep 无 `bin/bash` / `libreadline` /
`libncurses`；`apk-offline/` 下正好三个包（`bash-5.2.37-r0`、`ncurses-6.5_p20241006-r3`、
`readline-8.2.13-r0`）。`excludeOfflinePackages()` 的 KDoc 自己写着「installOfflinePackages
(autoRepair Stage 2.6) re-installs them on every rebuild」——**这就是缺口本身**：它把
「每次重建」归给了一个只覆盖 repair 的调用点。rg 全仓确认 `installOfflinePackages()` 的调用点
当时只有 Stage 2.6 一处；而 `RootfsManager.reset()` 是手动重置与 Stage 3 共同的唯一收口。

**修法。** 在 `installIfNeeded()` 里，`restoreApkWorld()` **之前**调用
`installOfflinePackages()`：这样依赖 bash 的用户包在同一次 pass 里解析成功，而不是失败后
落进 retry 队列。失败只 `Log.w`，不中断——树仍可用（ash），且下一次 boot 的
`verifyIntegrity → autoRepair` 会重试 Stage 2.6。

**行为变化。** 重建多一次从 bundle APK 的安装 pass。无网络、无版本漂移（三件套是固定版本）。

### F-02 重建不通知 —— **真，已修**

**先证。** `BootOnceGate` 只有 `run()` 一个转移，`isDone` 是单向的；rg 确认写入点只有
`bootCriticalSection` 一处，无第二条复位边。于是 `RootfsManager.reset()` 删掉目录后，
`PRootKernel.isBooted` 仍然为 true，`ExecutionCoordinator.execute` 的自动 boot 分支、
`TerminalSession.start` 的重入保护、以及三个副作用（stub / overlay / mounts）全部被跳过。

**修法（半径最小化）。** `RootfsManager` 是沙箱最底层，不可能反向依赖 `PRootKernel` /
`ExecutionCoordinator`（两个方向都是包循环）。所以只加一个 nullable suspend 钩子
`afterRootfsReinstalled`，由 `reset()` 在 `installIfNeeded()` 落地**之后**发布；`PRootKernel`
在 `bootCriticalSection` **开头**（拿到 RootfsManager 之后、第一次 `installIfNeeded()` 之前）
接线。钩子只做一件事：复位 gate。

**为什么只复位 gate 就够了。** `isBooted` 是所有入口共同查询的唯一事实
（`ExecutionCoordinator.execute` 自动 boot、`TerminalSession.start`、`ShellExecutor`），
所以让它失效 = 让所有依赖方通过**同一个**知道该怎么重建的代码路径（`bootCriticalSection`）
自我修复，而不是新写一份「wipe 之后要重放什么」的清单（那必然漂移）。

**shell / PTY 不在这里杀。** 两个 owner 在自己的下一次入口处处理，那里才能与正在跑的命令
串行化：`ExecutionCoordinator` 在自动 boot 前丢弃旧树里孵出的 shell；`TerminalSession.start`
重启仍指向旧树的 PTY。

**重入。** wipe 来自 `bootCriticalSection` 内部时（Stage 3），钩子运行在该 body 已持有 gate mutex
的上下文中。`invalidate` 是纯 volatile 写、不取锁 ⇒ 无自死锁；且 body 的尾部（stub /
overlay / mounts）在 reset **之后**才执行，所以此处复位既安全也必要。

### F-03 快照被降级为出厂内容 —— **真，已修**

**先证。** 三步状态机：restore 离线失败 → 待恢复清单仍持有用户包 → 下一次 `dumpApkWorld()`
（boot 的 post-retry dump，或**下一次** reset 的 pre-wipe dump）只读 apk db，而此时 db 已回到
出厂内容 → 覆盖了好的快照。再下一次 restore 对着出厂清单「成功」并删掉待恢复清单。
**丢失变为永久，而每一条日志都是 OK。**

**修法。** 新增纯函数 `mergeApkWorldPending(installed, pending)`：db 的条目优先（它的版本才是
实际安装的），结果 = db 顺序在前，其后是仅存在于待恢复清单中的名字（按清单顺序）。
`dumpApkWorld()` 现在写 `formatApkWorld(merged)`。纯函数（无 IO）⇒ JVM 可 pin 整张矩阵。

### F-04 持锁期间移除 mutex —— **真，已修**

**先证。** `[fix/audit-s4l2]` 那行的理由是「sessionDidTerminate 也移除 mutex」——而该前提
早已被 `[fix/audit-s4h4]` 撤回（s4h4 记录的是：在**持锁期间**移除 map entry，并发的
`mutexes.getOrPut` 会为同一 session 铸出第二个 Mutex，与仍在 `withLock` 里的命令并行）。
s4l2 的前提「这条路径从不持锁」**恰好说反了**：stop 的全部意义就是打断一个**正在运行**的
命令，而那个命令的协程此刻就在 `mutex.withLock` 里面。

受害者是**下一条命令**：拿到全新的 mutex → 立即进入 → 创建新的 PersistentShell → 而旧协程的
尾巴（`sessionDidTerminate` → `shells.remove(id)?.stop()`）把它杀在半路（`exitCode = -1`、
`outcomeKnown = false`）。

**修法。** 删掉 `stopCurrentCommand` 里的 `mutexes.remove(sessionId)` 和 legacy 分支里的
`mutexes.clear()`，并更新 s4h4 注释：现在**没有任何路径**修剪这个 map。
代价：每个用过的 session id 留一个小 Mutex —— s4h4 已经接受的同一笔生命周期交易，
也是唯一能让「一个 session id 一个活 Mutex 对象」为真的做法。

**为什么引用计数救不了。** `if (!mutex.isLocked) remove` 的检查和移除之间，依然在与一个
**已经拿到引用**的新来者竞争。

### F-05 清扫器跟随符号链接 —— **真，已修**

**先证。** `File.lastModified()` 是 `stat`（跟随链接）：一个刚建的悬空链接读到 `0`
（= 「肯定超龄」），一个指向老文件的链接继承目标的 mtime ⇒ 60 秒后即被回收
（build 的 `/tmp/lock -> …`，或任何脚本依赖的 `ln -s`）。`deleteRecursively()` 更糟：
它用 `listFiles()`/`isDirectory` 遍历，两者都跟随链接 ⇒ 一个含
`out -> /var/minis/workspace` 的超龄 `/tmp/build` 会删掉**目标的内容**。
顶层的 `symlink` 检查只保护条目本身，不保护嵌套在超龄目录里的任何东西。

**修法。** 年龄门改用 `internalGuestTmpEntryMtimeMs()`（`NOFOLLOW_LINKS` 的
`readAttributes`，即 `lstat`）；活动探测取 lstat 与 stat 的**最大值**（任一方向都比修前更保守，
而这是这个探测唯一安全的方向——它的答案意味着「别动它」）；删除改用
`walkFileTree`（不传 `FOLLOW_LINKS` 即不跟随），嵌套链接在 `visitFile` 里作为普通文件被
`deleteIfExists` 精确 unlink。

### F-06 只读挂载守卫的贪婪截断 + `mv` 漏检 —— **真，已修**

**先证。** 两处绕过：

1. `-[!-]*t?*) tdest="${arg##*[t]}"` 用的是**贪婪前缀剥离**：`##*[t]` 删到整个实参里
   **最后一个** `t`，而不是选项字母。而每个挂载路径里 `t` 至少出现两次
   （`/var/minis/moun**t**s/…` 加大多数名字）⇒ `cp -rt/var/minis/mounts/vault f`
   得到 `tdest=""`（或 `s/vault` 这类碎片）——而下一行的 `[ -z "$tdest" ] ||`
   对空值**短路放行**了这次写入。位置参数尾巴也被跳过，所以没有别的判据拦住它。
2. `mv` 完全没有 flag-value 解析（它不是 LAST_ARG 命令）：`-tDIR` /
   `--target-directory=DIR` 以 `-` 开头，被旧的 `-*) continue` 跳过 ⇒ 只检查了源。

**修法。** 短选项簇改为**逐字符**解析（这三个命令里只有 `t` 取值），于是值就是紧跟那个 `t`
的东西、别的都动不了它；缺值则**拒绝**而非放行。`mv` 享有与 cp/ln **相同**的 flag-value
解析，同时保留「每个位置参数都检查」的规则——两条互补，不是重复。

**为什么这个 bug 能活下来。** `MountGuardScriptTest` 是字符串 pin：它断言 codegen 里含有
「我们**以为**写对了」的形状。那个 `assertTrue(s.contains("-[!-]*t?*) … \${arg##*[t]}"))`
被有 bug 的那一行满足了——因为写断言的人和写实现的人持有同一个错误信念。

---

## 3. 装置

**为什么要有装置。** 生产文件需要 Android SDK，本地无法编译；而禁跑全量 scan/test_scan/gradle。
所以装置只替换**编译器的 classpath**，不替换任何代码：

- `Slice.kt` = 从被编辑的生产文件里**逐字切片**（有 marker 断言保证存在）
- 测试文件 = 仓库里**真实的**那几个（`MountGuardScriptTest` / `GuestTmpSweepPlanTest` /
  `ApkWorldSnapshotTest` / `PRootKernelBootGateTest` …）
- classpath 换成本地 jar

**新增测试。**

- `MountGuardScriptExecTest`（224 行，22 例）——用真 shell **跑**生成的包装脚本，断言判决。
  两处替换都在被测逻辑之外：`cfg` 重定向到临时文件（机器上没有
  `/var/minis/.mount-readonly-prefixes`，而**列表缺失会让每次检查都通过**，那会让每条
  「拒绝」臂断言的东西反过来）；委托目标指向真 busybox（有则）或 `true`。argv 选择与
  `check_prefix` 逐字执行。无 POSIX shell 时 skip 而非 fail。
- `SandboxRootfsRebuildShapeTest`（216 行，8 例）——对活文件的形状探针，注释行被抹掉（这些文件的
  散文正在讨论被 pin 的形状本身，不抹会允许注释满足探针）。
- `GuestTmpSweepPlanTest` +111 行（符号链接夹具）、`ApkWorldSnapshotTest` +90 行（合并矩阵 + 序列化
  round-trip）、`PRootKernelBootGateTest` +55 行（复位边）、`MountGuardScriptTest` 改写（去掉对
  bug 行的 pin）。

**结果：105 例全绿。**

## 4. 变异：14 臂，每臂只红钉它的用例

| 臂 | 还原的决策 | 红的用例 |
|---|---|---|
| A1 | `installIfNeeded` 不装离线三件套 | 离线三件套 ×2 |
| A2 | `bootCriticalSection` 不接线钩子 | boot 接线 |
| A3 | `reset()` 不发布重建 | reset 发布重建 |
| A4 | 处理函数不复位 gate | boot 接线 |
| A5 | `invalidate()` 无复位边 | invalidate 复位 |
| A6 | dump 写原始 db 结果 | dump 合并待恢复清单 |
| A7 | `stopCurrentCommand` 移除 mutex | 无路径移除 session mutex |
| A8 | 年龄门用 `lastModified()` | 清扫器读 lstat |
| A9 | 用 `deleteRecursively()` | 清扫器读 lstat（删除支） |
| A10 | `lstat` 助手退回 `getLastModifiedTime` | 悬空 / 新鲜链接 ×3 |
| A11 | 删除助手退回 `deleteRecursively()` | 不跟随嵌套链接 ×2 |
| A12 | 删掉 `--*)` 臂 | 含字母 t 的长选项 ×2 |
| A13 | `mv` 丢掉源检查 | mv 出只读挂载（3 断言，含真执行） |
| A14 | 删掉缺值拒绝 | 无目录的 `-t` ×2 |

**14/14 红，且每臂只红它自己钉的用例。** 恢复用 sha256 逐字节核对。

### 自伤两条（记下来，下次起手就避开）

1. **变异臂初版原地改工作树**，`finally` 的恢复腿被 `OSError [Errno 38] Function not
   implemented` 打死（S28：并发会话共用 rootfs 时的已知缺陷）⇒ 文件留在被变异状态。
   改法：变异落进 `armtree/` 副本，工作树只被读，前后 sha256 断言。
   **会弄脏树的装置比没有装置更糟。**
2. **一行代码改了两处（`.kt` + 测试断言）却只改了 `.kt`** ⇒ 编译期才发现测试还在 pin 旧形状。
   改法：动共享形状时，同一次编辑里把两侧一起改。

---

## 5. 验证边界（诚实交代）

- **没有真跑** `reset()` / `autoRepair()` / `/tmp` 清扫。任务书明令禁止，且当前 shell 就跑在这个
  rootfs 上。所有结论来自读码 + JVM 纯函数 + 形状探针。
- **探针编译的是切片，不是生产文件。** 生产文件的编译验证是 CI 的职责（含 113 道门 + 349 条夹具）。
- **F-01 的「bash 不在出厂 tar」** 是对 bundle 资产的静态核实，不是运行时观察。
- **F-02/F-06 的真机行为未验。** 沙箱无 Android 运行时。

---

## 6. 残留（登记，不修）

| ID | 残留 | 为什么现在不修 |
|---|---|---|
| R1 | `mv` 的 `-t` 与位置参数**同时**出现时，两条规则都会检查（重复但无害） | 保守方向；要精确需要区分「源列表结束」与「目标开始」，得不偿失 |
| R2 | F-05 的活动探测对**深目录**仍只探前两层 | 与既有契约一致；加深会增加每次清扫成本 |
| R3 | F-01 失败路径只 `Log.w`，没有上报 UI | 下次 boot 会重试；改 UI 需要 product 决策 |
| R4 | 互斥 map 永不修剪，长生命周期进程里随 session id 数量线性增长 | 与 s4h4 同一笔交易；要修需引入引用计数 + 退役协议 |
| R5 | F-03 的合并以**名字**为键，同名不同版本无法区分 | apk 语义里同名互斥；非问题 |
| R6 | 新增测试的行号断言会随文件编辑漂移 | 形状探针固有；已有 `codeOnly()` 抹注释缓解 |

---

## 7. 给主会话的指针

- 分支：`fix/meta1028-d3-sandbox-p0`，绿 sha 见 CI（head_sha 与本地 HEAD 逐字符一致）
- 报告：本文件
- 装置：`/var/minis/shared/work/meta-audit-1028/probe-d3/`（`build.py` 基线 / `arm_run.py <臂>` 单臂）
- **未并入 backlog**：本批 6 条残留写在本文件 §6（三件套齐），未抢写 backlog（11 会话并行，
  避免并发写冲突），交主会话收口。
