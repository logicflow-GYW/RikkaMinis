# FIX-R2-1119-F — 符号链接穿越四条（F-01 / F-04 / F-05 / F-06）

**分支**：`fix/symlink-escape-1119`（worktree `shared/work/r3d1119r2/symlink`）
**基线**：`ecb03d92`
**来源报告**：`shared/work/meta-audit/reports/fullscan1119r2/Rikkaminis_Fullscan_Report_Round2.md`
**判级表**：`shared/work/meta-audit/fullscan1119r2/TRIAGE-R2.md`

---

## 1. 结论速览

| ID | 先证结论 | 修法 | 负面证据（阴性对照） |
|---|---|---|---|
| F-01 | 成立（读码 + 装置） | 入口 scheme/authority 白名单 + 绝对路径 canonical 前缀守卫（fail-closed） | 系统 picker 的 `content://…/document/primary:Download/x.pdf`、其他 app 的绝对路径 `content://…/sdcard/DCIM/photo.jpg` 仍放行 |
| F-04 | 成立（实测：链接进只读区后可删真文件） | `deleteItem` 先过 `canonicalDeleteRefusal`（规范化路径 + 只读区判定） | 可写工作区内删除仍放行；删链接本身按「链接所在目录」判，不被目标连累 |
| F-05 | 成立（`File(rawTarget)` 无界兜底） | 抽 `resolveHostSymlinkTarget`，两条腿都过 `boundedSymlinkTarget`；列表/条目/快速路径同bound | 指向可写根的宿主相对链接、guest 绝对链接映射到根内仍可进入、可列 |
| F-06 | 成立（`deleteRecursively()` 跟随链接） | 三处删除统一走 `deleteRecursivelyChecked`（规范化前缀 fail-closed + no-follow walker）；媒体遍历也改为不跟随 | 正常会话目录整棵被删除（全删干净）；live 会话的 tab 状态保住 |

**四条全部先证成立，零证伪。**

---

## 2. 环境与约束

- worktree `/var/minis/shared/work/r3d1119r2/symlink`，分支 `fix/symlink-escape-1119`，基线 `ecb03d92`。
- 改动文件严格限于 `share/ShareReceiverActivity.kt`、`ui/sandbox/FileBrowserViewModel.kt`、`data/storage/SessionFileStore.kt`，加测试与 `fix-reports/**`。`git diff --stat` 已核：无其它生产文件被动。
- 未跑 `scan.sh` / 全量 `test_scan.py` / gradle。本地验证用 `kotlinc` 单文件 + `nice -n 10`（见 §6）。
- 装置与变异脚本都在 worktree 之外（`shared/work/r3d1119r2/symlink-jvm/`），不会被 `git add -A` 扫走。

---

## 3. 逐条

### 3.1 F-01 —— 导出组件对任意 URI 调 `openInputStream`

**先证**：`AndroidManifest.xml:202-284` 声明 `exported="true"` 且 `scheme="file"` / `scheme="content"`；`handleSingleSend` / `handleMultipleSend` / `handleView` 三条路径全部汇入 `copyUriToStaging(uri, mime)`，该函数此前**没有**任何 scheme / 路径判定，直接 `contentResolver.openInputStream(uri)`。`openInputStream` 对 `file://` 走裸 `FileInputStream`，以本应用 uid 打开 → 第三方 app 传 `file:///data/user/0/<pkg>/databases/minis.db` 即可让本应用把自己的库复制进可分享的暂存目录。

**修法**（`ShareReceiverActivity.kt`）：

两条独立防线，都要有：

1. **scheme/authority 白名单**（`admitSharedUri` 前半）：
   - 无 scheme → `NoScheme`；
   - `file://` → `FileScheme`（**一律拒**）。这条足以封死 F-01 的机制：`file://` 的危险在 scheme 本身，任何路径都不安全——目标既可能是数据库，也可能是**另一个会话的 workspace**；而 API 24+ 的发送端已被 `FileUriExposedException` 禁止共享 `file://`，功能上没有损失。
   - 非 `content://` → `NoScheme`；
   - `content://` 无 authority → `NoAuthority`；
   - `content://<自己>.fileprovider` → `OwnFileProvider`。`FileProvider` 认「调用包 == 本包」，而这里 `openInputStream` 的调用者正是本进程，所以声明的每个 provider 根（`sessions/`、`global/`、`media/`、`logs/`…）都可被任意 app 按需读取。
2. **canonical 前缀守卫**（后半，fail-closed）：`content://` 的路径通常是 provider 内部形式（`document/primary:Download/x.pdf`），按宿主路径 canonicalize 会把**所有**合法分享都拒掉（装置里专门钉了这条阴性对照）。因此只对**绝对路径**（`path.startsWith("/")`，即「声称是宿主路径」）执行 `canonicalize` + `isPathUnder(canonical, appDataDir)`；`canonicalize` 返回 `null` → `UnresolvablePath`（**不许兜底放行**）。

**接线**：判定放在 `copyUriToStaging` 的最前面，**先于** `StagedInFlightFiles.begin(name)`，被拒时不留 claim、不建文件、不计数。

**测试**（`SharedUriAdmissionTest`，17 例）：`file://<dataDir>/databases/minis.db` 拒；任意 `file://` 拒（含大写 `FILE`）；自有 authority 拒；未知 scheme 拒；无 authority 拒；绝对 content 路径落在数据目录拒；`..` 拼写落在数据目录拒；不可解析拒；symlink 拼写落到数据目录拒；**系统 picker 的 provider 内部路径放行**、**其他 app 的 /sdcard 绝对路径放行**、无路径放行。

### 3.2 F-04 —— 走链接进只读区后按逻辑路径校验

**先证**：`deleteItem` 的三道守卫（`isLinuxPathUnderReadOnlyMount` / `isLinuxPathUnderReadOnlySharedDir` / `isLinuxPathMountPointOrAncestor`）全部吃 `linuxPathOf(item)`——由 `displayLinuxPrefix ?: linuxRootPath` + `relativePath` + `item.name` **拼出来的字符串**。而 `loadItems` 列出的是 `resolveListingHostDir` 解析后的**真实宿主目录**，子项 `item.file` 是**真文件**（`memory/SOUL.md`）。两边一个是拼写、一个是 inode，守卫答的是前一个问题。

**修法**：新增纯函数 `canonicalDeleteRefusal(target, isSymlink, allowedRoots, readOnlyRoots)`，在 `deleteItem` 最前面判定：

- **主体**：非链接用 `item.hostPath` 自身；**链接用它的父目录**——删除只 unlink 名字，目标是否只读与这次删除无关（否则「删一个指向只读区的链接」会被误拒，而那是唯一不可能泄漏的操作）。
- **规范化**：`canonicalFileOrNull` 用 `toPath().toRealPath()` 而非 `canonicalFile`——后者对**不存在**的路径也会给出一个合法答案，"不可解析"永远不发生。`toRealPath()` 要求每一段都存在并解析链接，失败即 `null`，正是守卫要问的问题。
- **判定顺序**：先 `OutsideSandbox`（不在任何根内 / 不可解析），再 `ReadOnlyZone`。`null` 是拒绝，不是放行。
- 原有的三道字符串守卫**保留**（`readonly_mount_write_guard.py` 与 `SandboxPathGuardWiringTest` 都锚在这对调用上，且它们覆盖 canonical 守卫看不到的 guest-only 情形）。

**只读根从哪来**（`readOnlyRoots()`）：`SharedFolderRegistry.readOnlyLinuxPaths`（**单一来源**）→ 经 PRoot 自己的 `bindMounts` 映射成宿主目录。没有第二份路径表。

**测试**（`SymlinkBoundaryTest`）：链接进只读区后删里面的文件 → `ReadOnlyZone`；可写根内删除 → 允许（阴性对照）；根外目标 → `OutsideSandbox`；不可解析 / null → `OutsideSandbox`；删链接本身按父目录判（可写 → 允许），但父目录在根外 → `OutsideSandbox`；只读区内的链接 → `ReadOnlyZone`。

### 3.3 F-05 —— 解析失败回退到无边界 `File(rawTarget)`

**先证**：`resolveGuestAbsoluteTarget` 末行 `return File(rawTarget).takeIf { it.exists() }`——映射失败就把链接文本当宿主路径用，零边界。配合相对目标 `File(parent, linkTarget)` 未归一，`ln -s ../../../../databases /var/minis/workspace/db` 与 `ln -s /data/user/0/<pkg>/shared_prefs p` 都能浏览、预览、分享、删除。

**修法**：

- 新增 `boundedSymlinkTarget(candidate, allowedRoots)`：`toRealPath()` 后必须在某个根内，否则 `null`。**空根列表 = 什么都不允许**（不是"不限制"）。
- `resolveHostSymlinkTarget(mapped, rawTarget, exists, roots)`：把原来两行兜底逻辑抽成纯函数，**两条腿都过 bound**。兜底本身保留——宿主侧写的链接名的是宿主路径，砍掉会让这类链接全部打不开（阴性对照钉住了它）；但答案必须落在浏览器自己的根内。
- `FileItem.from` 增加 `sandboxRoots` 参数：链接目标在三条腿上都过 bound（映射结果 / 相对解析 / `toRealPath()`）；被拒时**不返回链接本身**（`isDirectory` 会跟随链接，返回链接等于保留文件夹可点性，`navigateTo` 仍能进私有目录），而是返回一个 `isDirectory=false, size=0, hostPath=file` 的"未知"条目。
- `resolveListingHostDir` 增加 `sandboxRoots`：快速路径（`toRealPath()` **会跟随链接**）与循环重定向两条腿都 bound；被拒时返回 `refusedListing()`——一个**不存在**的哨兵名（父目录内的 `.minis-refused-listing`）。返回链接会让调用方 `listFiles()` 跟随并列出刚拒掉的树；返回父目录又会让面包屑与内容错位。
- 根集合（`sandboxRoots()`）**派生**不写死：浏览器自身的 `rootPath`、`filesDir/{minis-sessions,minis-global,media,share_extension}`、`PRootKernel.bindMounts` 的宿主侧、`RootfsManager.rootfsDir`、已挂载外部目录的 `resolvedHostPath`。空列表时各纯函数保守拒绝。

**测试**（`SymlinkBoundaryTest` 15 例）：私有目录链接不是可浏览目录；相对 `..` 逃逸不是；进入逃逸链接不列目标内容；guest 绝对链接映射到根外被拒（且回答不越根）；未映射的原始宿主路径兜底被 bound 住；**可写根内链接仍列仍进**；guest 链接映射到根内仍列；普通目录快速路径仍通；未映射且不存在的 raw 目标 → null。

### 3.4 F-06 —— 删会话用跟随链接的 `deleteRecursively()`

**先证**：`deleteSessionFiles`（`:294-297`）、`reclaimOrphans`（`:426`）、`deleteOrphanMediaLeaves`（`:615`）三处都用 `File.deleteRecursively()`；`File.listFiles()` 跟随目录链接，于是会话 workspace 里一个 `link -> /sdcard/DCIM` 就能在删会话时清空 DCIM（目标自身文件消失、目标目录留下 = 报告实测形状）。

**修法**：三处统一走 `deleteRecursivelyChecked(dir, root)`：

1. **规范化前缀，fail-closed，且在"不存在"早退之前**。`File(sessionsRoot, "../../victim")` 这个路径**不存在**，`exists()` 早早返回成功就绕过了全部检查——守卫必须能对"不存在的路径"说不。`canonicalFile` 同时解析链接并归一 `..`，因此**不需要**第二道 real-path 守卫（实测：单独移除 real-path 那道，92 条测试一条不红——它就是同一条检查的第二种拼法，故不留）。
2. **no-follow 删除**：复用已有的 `internalGuestTmpDeleteTreeNoFollow`（`sandbox/GuestTmpSweepPlan.kt`，游客临时目录清扫器与文件浏览器已在用），链接按链接 unlink，绝不进入目标。两道都要有：规范化守卫管不住"链接指向同一根内"的同族删除，no-follow 管不住拼错的路径。
3. `deleteOrphanMediaLeaves` 的**枚举**也改成不跟随（`isRealDirectory` = `Files.isDirectory(NOFOLLOW_LINKS)`）；`walkTopDown()` 会进链接，把外部树的子目录当成候选放进报告。这一处是纵深防御：即使进了外部树，第 1 条的 canonical 守卫也会拒绝删除（实测：单独回退枚举，测试一条不红——如实记录，不当成已证）。

**测试**（`SessionFileStoreSymlinkTest` 11 例，全部临时目录，不碰真实 `/var/minis/memory`）：会话内 `ln -s <外部目录> link` → 删会话后外部目录与文件俱在、会话目录消失；正常会话目录仍整棵删除（阴性对照）；孤儿回收同形状；孤儿回收仍能删正常孤儿树；媒体叶子内的链接保住目标；`../../victim` 抛出根外 → 拒且 victim 完好；不存在的会话仍报成功；tab 状态文件随机删除 / live 会话的 tab 保住；媒体日期目录里的链接不被走进。

---

## 4. 三铁律逐条对账

| 铁律 | F-01 | F-04 | F-05 | F-06 |
|---|---|---|---|---|
| canonicalize | `File(raw).canonicalPath`（注入，装置可控） | `toPath().toRealPath()` | `toPath().toRealPath()` | `canonicalFile`（解析链接 + 归一 `..`） |
| 前缀守卫 | `isPathUnder(canonical, appDataDir)`，整段比较 | `isUnderAnyRoot(canonical, allowedRoots)` + `readOnlyRoots` | `isUnderAnyRoot(canonical, roots)`，两条腿都过 | 整段前缀，`base.isNotEmpty()` 兜底 |
| fail-closed | `canonicalize == null` → `UnresolvablePath`；`appDataDir == null` → 不匹配任何根 | 不可解析 → `OutsideSandbox`；空根列表 → 全拒 | 不可解析 → `null`；空根列表 → 全拒 | `canonicalFile` 抛异常 → `false`；`base` 为空 → `false` |

---

## 5. 阴性对照（"把功能删了就绿"不算修好）

| 场景 | 断言 |
|---|---|
| 系统 picker 的真实分享 | `content://com.android.providers.downloads.documents/document/primary:Download/report.pdf` 放行 |
| 其他 app 的 /sdcard 绝对路径 | `content://com.other.app.provider` + `/sdcard/DCIM/photo.jpg` 放行 |
| 可写根内的合法链接 | `FileItem.from` 仍 `isDirectory=true`、`hostPath` 指向真实目录；`resolveListingHostDir` 仍能列出内容 |
| guest 绝对链接映射到根内 | 仍能列出 |
| 普通（非链接）目录 | 快速路径仍通 |
| 可写根内删除 | `canonicalDeleteRefusal` 返回 `null`（允许） |
| 正常会话目录 | `deleteSessionFiles` / `reclaimOrphans` 仍整棵删净 |
| live 会话的浏览器 tab 状态 | 孤儿清扫后仍在 |

---

## 6. 本地装置与证据

```
shared/work/r3d1119r2/symlink-jvm/
  extract.py    从三个生产文件抽纯片段（FileBrowserViewModel 的纯半段、ShareReceiverActivity 的准入核），
                SessionFileStore 整体编译（三个 Android 符号用小桩：Context / ComposerDraftStore / AppLogger）；
                no-follow walker 直接从 GuestTmpSweepPlan.kt 原样切出
  run.sh        kotlinc -no-stdlib + JUnit，nice -n 10
  mutate.py     12 条变异臂（快照 + finally 还原 + sha256 校验 + 逐臂独立进程）
```

**结果**：`OK (108 tests)` —— 新增 3 个测试类 43 例 + 既有 5 个测试类 65 例（`SymlinkEntryResolutionTest` / `SymlinkListingNavTest` / `FileDeletionNoFollowTest` / `SessionFileStoreTest` / `SandboxPathGuardWiringTest`）**零回归**。

**变异负控 12 臂全 RED**（每条修复至少一臂）：

| 臂 | 恢复的缺陷形状 | 结果 |
|---|---|---|
| F01-M1 | `file://` 重新放行 | RED（2 红） |
| F01-M2 | 自有 FileProvider 放行 | RED（1） |
| F01-M3 | 私有数据目录判定失效 | RED（3） |
| F01-M4 | 不可解析路径兜底放行 | RED（1） |
| F04-M1 | 不拒只读区 | RED（2） |
| F04-M2 | 不看沙箱边界 | RED（2） |
| F05-M1 | 列表快速路径无界 | RED（1） |
| F05-M2 | 列表循环分支无界 | RED（1） |
| F05-M3 | 条目目标无界 | RED（2） |
| F05-M4 | 无界 `File(rawTarget)` 兜底回归 | RED（1） |
| F06-M1 | 恢复 `deleteRecursively()`（跟随链接） | RED（2） |
| F06-M2 | 去掉规范化前缀守卫 | RED（2） |

**两条如实登记的"无牙"候选**（不是覆盖缺口，是冗余防线，已删或降级）：

1. **F-06 real-path 第二道守卫**：`canonicalFile` 本身已解析符号链接并归一 `..`，与 `toRealPath()` 判的是同一件事。实测（M3 臂）单独移除它 92 条测试一条不红 ⇒ **删除该代码**，注释里写明原因，不留"看起来更安全"的第二道。
2. **F-06 媒体枚举 no-follow**：单独回退成 `child.isDirectory`，测试一条不红——因为走进了外部树后，第 1 条的 canonical 守卫仍会拒绝删除。保留该改动（不走进别人的树是对的），但在本报告与本条注释里**如实标注为纵深防御**，不计入"已证修复"。

**本地单门**（`scripts/scan/*.py`，不跑 `scan.sh`/`test_scan.py`）：

```
readonly_mount_write_guard             rc=0
browser_tabs_lifecycle_check           rc=0
sysinfo_state_thread_check             rc=0
file_provider_paths_coverage_check     rc=0
discarded_return_guard                 rc=0
skill_restore_session_cleanup_check    rc=0
share_dir_prune_check                  rc=0
```

> 注：这些脚本用相对 `--root .` 会静默 0 文件（`ROT — the guard can no longer see what it is supposed to check`），必须传绝对路径；`readonly_mount_write_guard` 的 `EXTRA_GUARDS` 仍要求 `deleteItem` 内含 `isLinuxPathUnderReadOnlySharedDir(`——本支保留全部原有字符串守卫，未触发。

### 6.1 云端证据（停止节点）

- **run** `#3335` / `44459803760`，`head_sha = c1aa9d4fbf1ca5345c020cd7cdc75ee7172ffbcf`
- **三源逐字符一致**：本地 `git rev-parse HEAD` = 远端 `git ls-remote origin refs/heads/fix/symlink-escape-1119` = 该 run 的 `head_sha`
- **五个 job 全 success**：scan-gate / build-apk / lint / unit-tests / publish
- **unit-tests**：`9870` 条 `PASSED`，真 `FAILED` 0 条。新用例逐条 PASSED 已核：
  `SharedUriAdmissionTest` 13 条、`SymlinkBoundaryTest` 19 条、`SessionFileStoreSymlinkTest` 11 条、
  `SandboxPathGuardWiringTest` 新增 3 条（`the browser delete consults the canonical verdict before removing anything` /
  `the browser resolution arms all bound their answer` / `no session delete site bypasses the no-follow guarded helper`）
- **scan-gate**：`Summary: 115 passed, 0 failed`，日志含
  `scripts/scan/** untouched in this push — skipping the fixture self-test`（本支未动门 ⇒ 绿不含夹具证据，按机制跳过）
- **publish**：只跑了 checkout，tag / release 两步 **skipped**（main 门控）——分支绿不代表有可装 APK

**未合 main。** 报告本节的补提交只改 `fix-reports/*.md`，代码 tip 仍是 `c1aa9d4f`；`git diff c1aa9d4f..HEAD -- src/` 为空。


---

## 7. 已知残留（不在本支范围，已登记）

1. **`FileProvider` 根自身很宽**：`sessions/`、`global/`、`media/`、`logs/` 都在声明里，一旦别处（或未来的自分享流程）把自有 provider URI 送进本 Activity，第 1 条防线会拒绝；但 `FileProvider` **对外**的暴露面不变。是否收窄这些根属于独立决策（会改动对外分享功能），不在本支。
2. **`content://` 的绝对路径前缀守卫依赖 provider 暴露宿主路径**：多数 provider 给的是 `document/primary:…` 形式，这类 URI 只有 `authority` 一层边界（由 Android 的 URI 授权模型保证）。报告 §"修法方向"要求的"解析后的真实路径校验"对这类 URI 无法成立（拿不到真实路径），本支把能判的都判了。
3. **`MinisDocumentsProvider` 等其他 authority** 未列入拒绝集：它们是独立的导出面，`autoApprove` 语义不同，需单独审计。
4. **F-13（`dirSize` 跟随链接死循环）** 与本支同文件族但不同函数（`SessionFileStore.sizeOf` 已 NOFOLLOW，报告指的是 `DeviceOffloadHandler`），不在本支范围。

---

## 8. 变更清单

| 文件 | 变更 |
|---|---|
| `share/ShareReceiverActivity.kt` | `copyUriToStaging` 入口加 `admitSharedUri` 闸；文件末尾新增 `SharedUriRefusal` / `SharedUriAdmission` / `admitSharedUri` / `isPathUnder`（纯函数，+146 行） |
| `ui/sandbox/FileBrowserViewModel.kt` | 新增 `canonicalFileOrNull` / `isCanonicalUnder` / `isUnderAnyRoot` / `boundedSymlinkTarget` / `CanonicalDeleteRefusal` / `canonicalDeleteRefusal` / `resolveHostSymlinkTarget` / `nearestAllowedDir` / `refusedListing`；`FileItem.from` + `resolveListingHostDir` 加 `sandboxRoots`；`deleteItem` 前置规范化判定；VM 侧新增 `sandboxRoots()` / `readOnlyRoots()` |
| `data/storage/SessionFileStore.kt` | `deleteRecursivelyChecked(dir, root)` 加规范化前缀 + no-follow walker；三处调用点传根；媒体枚举改为不跟随 |
| 测试（新增） | `share/SharedUriAdmissionTest.kt`（17）、`ui/sandbox/SymlinkBoundaryTest.kt`（19）、`data/storage/SessionFileStoreSymlinkTest.kt`（11） |
| 测试（补充） | `sandbox/SandboxPathGuardWiringTest.kt` 新增 3 条形状断言（`deleteItem` 接线 / 三条解析腿 bound / 无裸 `deleteRecursively()`） |
