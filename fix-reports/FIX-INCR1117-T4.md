# FIX-INCR1117-T4 — rootfs 重置遇不可读非空目录（C-F01）

## 1 覆盖 ID 与元信息

| 项 | 值 |
|---|---|
| 来源报告 | `/var/minis/shared/work/meta-audit/reports/incr1117/Incr_1117_C_Report.md` **C-F01** |
| 判级 | P1（恢复路径静默失败：重置"看起来成功"，旧内容整棵残留） |
| worktree | `/var/minis/shared/work/incr1117/wt4` |
| 分支 | `fix/rootfs-reset-unreadable-1117` |
| 基线 | `949566ac`（main：`Merge fix/spawn-agent-as-real-session-1117`） |
| 提交 | `0d6399b4`（分支尖端，单提交） |
| 改动量 | 2 文件 **+178 / −10**（生产 `RootfsResetFiles.kt` +123/−10；测试 `RootfsResetFilesTest.kt` +65） |
| 禁碰项 | `scripts/scan/**` 零命中、`PRootKernel.kt` / `RootfsManager.kt` / 其它 sandbox 文件零命中；未跑全量 scan、未跑 gradle |
| 停止节点 | 分支 CI 绿（不合 main） |

## 2 先证结论：成立（真机口径同源，非仅代码阅读）

**报告所述机制逐条核实，全部成立**，并用可执行探针在「非 root JVM + 真实 POSIX 权限位」下复现了终态：

1. `walkFileTree` 打开目录需要 `r`（列目录），进入目录需要 `x`。`chmod 000`（或 `0200`）且目录非空 ⇒ 打开抛 `AccessDeniedException` ⇒ 进 `visitFileFailed`，**其内部条目 `visitFile` / `postVisitDirectory` 一次都不会被调用**。
2. `deleteRestoringOwnerWrite`（1116-CD F-04）只补 `u+w`：对**空目录**足够（unlink 只需要父目录可写）；对**非空目录** `Files.delete` 抛 `DirectoryNotEmptyException`，被 `runCatching` 吞掉，返回 Unit，调用点不看结果 ⇒ 无日志、无异常。
3. 结果就是 C-F01 的描述：`clearRootfsForReinstall` 正常返回，rootfs 里留下一整棵不可读子树，下一次解压"写过去"而不是"换掉"。

**可执行证据（修复前代码 `949566ac` 的 `RootfsResetFiles.kt`，以 `nobody` 身份跑 JVM）**：

```
setup: clamode = [OWNER_WRITE]                       ← 目录确实 0200（无 r、无 x）
after reset: noread.exists (no perms)         = true
after reset+chmod u+rx: noread entries        = nested,payload.txt
after reset+chmod u+rx: noread/nested.exists  = true
backing target kept                           = true
ordinary.conf.exists                          = false
RESULT=RESIDUE
```

（`after reset+chmod u+rx` 那几行是先把权限补回再 `exists()`：没有 `x` 时 `exists()` 一律 false，不补权限看到的"已删除"是假阴性——这本身就是这条 finding 难被察觉的原因。）

**同一探针换成修复后代码**：

```
after reset: noread.exists (no perms)         = false
after reset+chmod u+rx: noread entries        = null
backing target kept                           = true
rootfs.isDirectory                            = true
RESULT=CLEAN
```

## 3 修法（一句话）

`visitFileFailed` 命中**目录**时不再只补 `u+w`，而是补属主 `rwx` 后用**显式栈**深度优先删掉整棵子树（目录在子项之后删），命中**文件/符号链接**时维持原行为；每条失败都落到 `AppLogger.warning`，不再静默。

关键点：

- **保留的契约**：`visitFileFailed` 仍返回 `CONTINUE`（改 `TERMINATE` = 1116-CD 修掉的"一轮装不上"病）；`preserveBacking && file == backing` 直接跳过；递归删除只在失败子树内发生，`skipSubtree = backing` 双保险，`.l2s-backing` 永不进入。
- **不跟随符号链接**：`Files.isDirectory(path, NOFOLLOW_LINKS)` 让链接算普通条目；新增的 `restoreOwnerAccess` **拒绝 chmod 符号链接**——`File.setWritable` 会跟随链接，把 rootfs 外部的目标改权限（这一点被新测试钉住，见 §4 负控 2）。
- **深度优先用显式栈**：`ArrayList` 当栈 + `directories` 记录后序删除顺序，64 层深树已验证（探针），不依赖递归深度。
- **非致命但可见**：两次尝试仍删不掉 ⇒ `AppLogger.warning("RootfsReset", …)`；子树连列都列不出来 ⇒ 另一条 warning。安装不被阻塞（既有契约），但日志里能查到"哪一条留下了"。
- `deleteRestoringOwnerWrite` 返回值从 `Unit` 改为 `Boolean`，供递归删除的目录阶段使用；生产调用点语义不变（忽略返回值）。

## 4 证据

### 4.1 本地（沙箱，非 root，真实权限位）

| 证据 | 命令 | 结果 |
|---|---|---|
| 修复前终态 | `su nobody -s /bin/sh -c 'java … MainKt <rootfs>'` | `RESULT=RESIDUE`（子树整棵保留） |
| 修复后终态 | 同上，`-cp out-fix` | `RESULT=CLEAN`（子树删除，rootfs 根保留，backing 保留） |
| 深树（64 层嵌套） | `PrebuiltProbeKt <rootfs> … /noread/d/d/…/leaf.txt` | 整链删除，无栈溢出 |
| 完整测试类 | `java org.junit.runner.JUnitCore com.rikkaminis.app.sandbox.RootfsResetFilesTest`（`nobody` 身份） | `OK (8 tests)` |

生产文件本身不依赖 Android SDK（只有 `AppLogger` 需要），探针用 3 行 `AppLogger` 桩替换日志实现，其余逐字是仓库文件。

### 4.2 负控（用同一测试类跑**修复前**生产文件）

```
There were 2 failures:
1) unlinking a symlink in a read-only directory never chmods its target
   the link target's mode must be untouched expected:<[OWNER_READ]> but was:<[OWNER_READ, OWNER_WRITE]>
2) a non-empty unreadable directory is removed instead of being left behind
   the unreadable subtree must be gone
Tests run: 8,  Failures: 2
```

**只有新增的两条臂翻红，且红的理由正是修的东西**：①子树残留；②旧实现把 rootfs 外目标的权限从 `0444` 改成 `0644`（chmod 跟随链接）。6 条既有用例两棵树都绿 ⇒ 无回归。

### 4.3 CI（最终判据）

| 项 | 值 |
|---|---|
| run | **#3201 / `44004306822`** |
| head_sha | `0d6399b4c3f2ee03e11ccfb005332ddf14ded7b5`（与本地 HEAD 逐字符一致） |
| 结论 | **五 job 全 success**（`build-apk` / `scan-gate` / `lint` / `unit-tests` / `publish`，`publish` 受 main 门控故两步 skipped） |

逐条取证（`unit-tests` job `133775757777` 日志，1.57 MB）：

```
RootfsResetFilesTest > a non-empty unreadable directory is removed instead of being left behind PASSED
RootfsResetFilesTest > unlinking a symlink in a read-only directory never chmods its target PASSED
RootfsResetFilesTest > a read-only directory does not abort the reset PASSED
… 本类 8 条全部 PASSED
BUILD SUCCESSFUL in 5m 13s
```

- 全日志 `PASSED` 行 **9300** 条，以 ` FAILED` 结尾的行 **0** 条。
- 两条新用例是 `PASSED` 而**不是** `SKIPPED` ⇒ CI runner 非 root，`chmod` 真的生效，`visitFileFailed` 真的被调用 ⇒ **这是"修复前必红"的那条真实路径**，不是空转绿。
- `scan-gate`（job `133775757727`）：`Summary: 115 passed, 0 failed`；日志明写 `scripts/scan/** untouched in this push — skipping the fixture self-test` ⇒ 本批**没改门脚本**，夹具未跑（如实记录：本批的"CI 绿"不含夹具跑过的证据，也不需要——本批零门改动）。

新测试在本机（沙箱 root JVM）**跑不出差异**——root 忽略权限位，所以两条新用例都加了 `Assume.assumeFalse(locked.canRead())`：root 环境会**跳过**而不是假绿。CI（ubuntu-latest 非 root）才是这两条的真实证据面。

## 5 影响面与风险

- 影响面 = `clearRootfsForReinstall` 的唯一失败分支。正常路径（可读目录）行为**逐字未变**：仍由 `visitFile`/`postVisitDirectory` 删除，仍只补 `u+w`。
- 新增行为只发生在「目录打不开」这一种状态，这是重置路径上唯一会"装作成功"的场景。
- 风险点已用测试钉住：符号链接不跟随（根因是 chmod 语义）、backing 不进入（1116-CD 契约）、`CONTINUE` 不变（1116-CD 契约）。
- 残留的一档：目录连 `chmod` 都不生效（非本 app 属主 / 真只读挂载）时仍删不掉——按既有契约非致命，但现在**有日志**。这一档在 app 自建 rootfs 上不可达（app 拥有全部 inode），沙箱里也无法构造（PRoot 伪造属主，`chmod` 恒成功），故未建装置。

## 6 未做 / 登记

- 未改 `visitFileFailed` 的 `CONTINUE`——任务书硬约束，且 1116-CD 的教训是 `TERMINATE` 会让整轮装不上。
- 未碰 `PRootKernel.kt` / `RootfsManager.kt` / `scripts/scan/**`（任务书禁碰）。
- 未跑全量 scan / gradle（任务书禁跑）。本地只跑了本测试类 + 括号配平（两文件 `{}` / `()` 均配平）+ 探针。

## 7 真机待验（本轮未装，留给合并后的版本）

1. `chmod 000` 一个非空目录后触发"重置 rootfs"，目录应消失、安装成功（修复前是目录留下、安装也"成功"）。
2. 日志中不应出现 `reset could not remove`（正常场景）；若出现，说明有 `chmod` 都不生效的条目。
3. `.l2s-backing` 里的伪硬链接目标仍在（1116-CD 语义未破）。

## 8 复现与环境

```
# 探针（可重跑）
/var/minis/shared/work/incr1117/t4probe/
  src/RootfsResetFiles.kt      # 修复前版本（git show 949566ac:…）
  src/AppLoggerStub.kt         # 3 行桩
  src/Main.kt                  # chmod 000 非空目录 + 重置 + 终态断言
  src/PrebuiltProbe.kt         # 外部预置树（深树 / 预警路径）
  out-prefix / out-fix / out-tests / out-tests-prefix

# 单测（必须非 root 才有意义）
su nobody -s /bin/sh -c "java -cp out-tests:kotlin-stdlib:junit:hamcrest \
  org.junit.runner.JUnitCore com.rikkaminis.app.sandbox.RootfsResetFilesTest"
```

- 沙箱 `uid=0` 是 PRoot 伪造的；真实权限位检查仍生效（`chmod 000` 目录对 `nobody` 进程 `ls` 报 `Permission denied`，已核）。
- CI 端：`ubuntu-latest` runner 非 root，`chmod` 生效，`visitFileFailed` 真的被调用——这两条用例在 CI 上是有效装置。
