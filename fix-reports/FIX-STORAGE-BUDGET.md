# FIX-STORAGE-BUDGET — 存储预算回路收口（L0 根因 + L1 DECIDE + L6 生产者账单）

**分支** `feat/storage-budget-loop` @ `0909c9c0dba420998fd9bd9c4de9c602f03a0952`
**CI** run `40439916447` / job `121977537813` / attempt 1 / **success**（唯一 skipped = Publish，main 门控）
**基线** main `fd498ae8e`（2026-10-27 晨）

## 1. 为什么做（用户报障 → 实测根因）

Android 系统设置显示本 app「用户数据 14.45GB」，且持续增长。实测构成（2026-10-27）：

| 位置 | 体积 | 说明 |
|---|---|---|
| `files/alpine-rootfs` | 10.17 GiB | 沙箱根文件系统（app「用户数据」的 70%） |
| └ `/data/user/0/<pkg>/cache` | **3.26 GiB** | **17,213 个陈旧自测夹具目录**（mig-\* 8324、handback-selftest-\* 4107、scan-selftest-\* 2508…） |
| └ `/root/.gradle` | 3.46 GiB | 构建依赖缓存（无回收） |
| `minis-global/shared` | 0.61 GiB | 共享工作目录 |
| `logs` | 0.19 GiB | 有 200MB/15 天硬上限，正常 |
| 会话/媒体/DB | ≈3.4 GiB | 删会话即回收 |

**机制取证**：沙箱环境 `TMPDIR=/data/user/0/com.rikkaminis.app/cache`（宿主路径，PROOT_TMP_DIR 同源）。PRoot rootfs 内该路径是真实目录 ⇒ python `tempfile`/`mktemp`/`pip`/`gcc` 的所有临时写入都落进 `files/alpine-rootfs`，`/tmp` 60 分钟收割器永远看不到。**同机对照实验**：`TMPDIR` 现值下 `mkdtemp()` → `/data/user/0/…/cache/leak-proof-*`（落 rootfs）；`TMPDIR=/tmp` 下 → `/tmp/fix-proof-*`（tmpfs，被收割）。

## 2. 改动面（5 文件 + 1 测试，生产 .kt 3 处）

| 层 | 文件 | 改动 |
|---|---|---|
| **L0 根因** | `sandbox/PersistentShell.kt` | guest env 钉 `TMPDIR=/tmp`、`TMP=/tmp`（KDoc 12 行：为什么 PROOT_TMP_DIR 必须保持宿主路径而 TMPDIR 必须是 guest 路径） |
| **L1 采样** | `data/storage/StorageBudget.kt`（新） | 每日一次 IO 采样（复用 `RootfsUsageScanner` 扫 filesDir + 数据库目录，StatFs 取 free/total）；预算 = max(6 GiB, 5% × /data)；`shouldNotify` 冷却门（首越界即报 / 周报 / +25% 再报） |
| **L1 通知** | `notification/StorageBudgetNotifier.kt`（新） | 专属 channel `minis_storage_budget`，深链 `minis://settings/storage`；POST_NOTIFICATIONS 缺失 = 静默 no-op（采样照跑、提示词照喂） |
| **L6 账单** | `ui/chat/ChatPromptAndTools.kt` | `buildSystemPrompt` 的 Runtime context 尾巴追加一行缓存读数；超预算时额外点名 `minis-fastio rm -r` 廉价出口 |
| 接线 | `MinisApp.kt` | `applicationScope.launch { runDailyCheck }` |
| 资源 | `values/strings.xml` | 4 键（channel 名/描述 + 通知标题/正文）；i18n 门 rc=0 |

**负决策（backlog §2608 登记，不做）**：不给 rootfs 设硬配额（打断「agent 随便装包」核心体验，apk 包无法优雅降级）；不自动删会话（静默数据丢失风险）。

## 3. 判级依据（修复门）

P1：用户已实测撞到（14.45GB 触发路径可指认）；ENOSPC 全仓 0 处理，手机近满时会升 P0。装置比例：复用现成 `RootfsUsageScanner` + `SharedPreferences`，不引 WorkManager、不建新扫描器；两处硬编码常量均带理由注释。

## 4. 验证（三源取二）

1. **机制证据（独立于 CI）**：§1 的 TMPDIR 对照实验，本机实跑。
2. **JVM 单测（CI）**：`StorageBudgetTest` 11 条全 PASSED —— 阈值形态（6GiB 地板 vs 分区 5% 臂）、通知门（首越界/增长/周窗/预算内/回落）、提示词行（采样前 null / 超预算 OVER / 欠预算不喊）、单位格式化二进制边界。第 1 轮红 1 条（`gib/2` 期望 `0.5 GB`，实际 `512 MB`——格式化器设计如此），修正期望值后第 2 轮绿。
3. **CI 机械读数（run 40439916447，日志 1,467,384 B）**：`Summary: 112 passed, 0 failed` ×1 · `ALL 347 CASES PASS` ×1 · `^ e: ` 0 · `StorageBudgetTest >` 11 行 / `FAILED` 0 · 唯一 skipped = Publish · head_sha `0909c9c0d…` 与本地 HEAD 逐字符一致。

## 5. 残留与登记

- **R1** 夹具泄漏的保险网：即使 L0 修了 TMPDIR，任何写死宿主路径的工具仍可往 `<rootfs>/data/user/0/<pkg>/cache` 堆东西。可选方案 = 把该路径纳入 sweepGuestTmpDir 的年龄化清扫（触发条件：修复上线后该目录再次 >100MB）。
- **R2** 真机端到端未验（沙箱无 Android 运行时）：发布后应看到 ① logcat `StorageBudget: sample app=… shell=…`；② 超预算时一条通知；③ 会话提示词 Runtime context 尾巴多一行 `App storage: …`。
- **R3** gradle/npm 缓存无上限（本次手工清 3.70GB，属开发期垃圾，agent 行为纪律覆盖，不修代码）。
- **R4** 「存储管理页把 rootfs 叫『终端 Shell』而系统设置叫『用户数据』」的命名错位，L6 的提示词行与通知正文部分缓解；彻底对齐需改 StorageManagementScreen 文案（未做）。
