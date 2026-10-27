# FIX-1027-KEYSTORE-DEGRADE — keystore 降级可见性 + 销毁前备份（F-1 / F-2）

分支 `fix/keystore-degrade-visibility` · 基线 main `85058847` · 代码提交 `79c0db5e`

## 1. 目标与缺陷（真缺陷，已核实）

`util/EncryptedPrefsFactory.kt` 的旧流程对每一次 `EncryptedSharedPreferences.create`
失败都给出同一套回答：删掉本 store 的 XML → 重建 → 删掉共享 Tink keyset + AndroidKeystore
alias → 重建 → 交出一个内存版 SharedPreferences。三个问题：

- **F-1（不可逆换不来什么）**：第一次失败**不区分瞬时与永久**。两个进程同时创建
  MasterKey、keystore 短暂忙，都会走 `deleteOwnPrefsFile` —— 用数据销毁去换一次
  本来就可能成功的重试。恢复动作比它要恢复的故障更有破坏性。
- **F-2（降级静默）**：内存版 `commit()` 恒 `true`。`ProviderRepository` /
  `EnvVarRepository` / `WebDavConfigStore` 都是 `by lazy` 缓存实例 ⇒ 同进程内读写
  「正常」，**冷启动后凭据全无且没有任何一处能让用户知道**。另有
  `data/DeviceIdentity.kt:67`、`sandbox/offload/ModelExecutionService.kt:935/1369`
  每次调用都新建 `safeCreate` 实例 ⇒ 降级时每次拿到不同的空 map。
- 顺带暴露的第三点（不单独修，随 F-2 一并消解）：`safeCreate` 每次调用都重建实例，
  并发首次创建时多个线程可能同时进入「删除 + 重建」路径。

## 2. 修法（三条，范围未扩大）

1. **删除之前先重试**：3 次尝试，退避 0 / 50 / 150 ms，全部失败才进入破坏性恢复。
   不做异常类型分类 —— keystore 的异常类型与 message 跨版本不稳定，分类逻辑本身
   会成为新的静默失效点；重试吃掉瞬时失败就够了。
2. **销毁前备份**：`deleteOwnPrefsFile` 与 `wipeSharedKeyset` 删除前，先把目标文件
   复制为 `<name>.recovered-<epochMillis>`；同目录（`shared_prefs/`），不新增目录；
   只保留最近 3 份。同毫秒多次备份追加 `-1` `-2` 序号，不互相覆盖。备份失败
   `Log.w` 后**继续**删除流程（不卡启动）。
3. **降级可见 + 实例复用**：
   - `isDegraded(fileName)` / `degradedStores()`；
   - 降级实例**按 fileName 复用**（同一 fileName 的多次 `safeCreate` 返回同一实例），
     修掉 `DeviceIdentity` / `ModelExecutionService` 每次拿到不同空 map 的问题；
   - 内存版 `commit()` 返回 **`false`**（SharedPreferences 契约里 false = 写盘失败，
     语义正确），`apply()` 委托 `commit()`，不抛异常；
   - 降级时 `Log.e`（不是 `w`），message 带 fileName + 「凭据不会持久化」。

**决策与 I/O 分离**：新增 `util/KeystoreRecoveryPlan.kt`（纯 Kotlin/JVM，零 Android
依赖，参考先例 `backup/EnvVarRestorePlan.kt`），承载
`KeystoreRecoveryAction`（`Retry(delayMs)` / `BackupThenDrop(target)` / `Fallback`）、
冻结脚本 `KeystoreRecoveryPlan.steps()`、执行器 `runKeystoreRecovery(attempt, io, sleep)`、
轮转备份 `KeystorePrefsBackup`（`java.io.File` 级）与降级登记表 `DegradeRegistry<T>`。
`EncryptedPrefsFactory` 只实现 `KeystoreRecoveryIo` 并执行脚本。

改动面（3 文件）：`util/EncryptedPrefsFactory.kt`（+134/−59）、新增
`util/KeystoreRecoveryPlan.kt`（292 行）、新增
`app/src/test/java/.../util/KeystoreRecoveryPlanTest.kt`（290 行）。**没有**改
`EncryptedSharedPreferences.create` 的用法、**没有**加 UI、**没有**加新门、
**没有**动 `DeviceIdentity` / `ModelExecutionService`（实例复用让它们无需改动）。
`commit()` 的返回值消费方核查：全仓只有 3 处 `commit()`，其中
`ProviderRepository:518` 的 `mirrorWritten` 读的是**明文** `provider_config` prefs
（不受影响）；`:2512` / `:2520` 的加密 prefs 写入**忽略**返回值 ⇒ 返回 false 不会
引入任何新的失败分支。

## 3. 装置（P0 配比：JVM 实验 + 变异负控 + CI，不建新门）

- **纯决策层（入库，随 CI 跑）**：`KeystoreRecoveryPlanTest`（14 臂）编译**真生产文件**
  `KeystoreRecoveryPlan.kt`，覆盖必测 1/2/3/4/5 + 6/7 + 8。
- **工厂层（沙箱 harness，不入库）**：`probe/` 下用 JVM 桩（`android.content.Context` /
  `SharedPreferences` 全成员表 / `android.util.Log` / `androidx.security.crypto.*`）
  单次 `kotlinc` 编译**真生产文件** `EncryptedPrefsFactory.kt`，4 臂：
  首次成功不销毁任何文件、瞬时失败被重试吃掉（第二次尝试成功 → 两个 XML 原样、
  0 个备份文件、`isDegraded=false`）、三次全失败（本 store XML 与共享 keyset 都被
  备份 + 删除、`isDegraded` 为真且 `degradedStores()` 含该名、第二次 `safeCreate` 返回 `===` 同一实例、
  `commit()==false`、日志里有 `E/…DEGRADED…` 与 `W/…backed up…`）、第 5 次尝试
  成功（两阶段各留 1 份备份且最终是加密实例）。
  桩按 skill §8 的镜像约定写：`SharedPreferences` 的**全接口成员表**都在桩里，
  生产文件若漂移则本地就编不过。

## 4. 验证

### 4.1 JVM 用例（沙箱实跑，真生产源码）

```
$ sh probe/run_harness.sh
JUnit version 4.13.2
..................
Time: 0.752
OK (18 tests)
```

（`KeystoreRecoveryPlanTest` 14 臂 + `KeystoreFactoryHarnessTest` 4 臂；
期望值全部写死字面量 —— 脚本序列、退避值 0/50/150、保留 3 份、`drops` 计数，
无一从被测常量推导。）

### 4.2 变异负控（3 组，全部还原后复绿 + sha256 逐字节）

| 组 | 变异 | 红臂 | 还原 |
|---|---|---|---|
| M1 | `RETRY_DELAYS_MS` → `listOf(0L)` | 7 红（含 `transientFailureIsAbsorbed…` 与工厂层同臂） | `OK (18 tests)`，两文件 sha256 与基线一致 |
| M2 | 脚本里删掉两个 `BackupThenDrop` | 6 红（`exhaustedRetriesWalkTheFullScript`、`backupAlwaysPrecedesTheDropItProtects`、`failedBackupStillDrops…`、工厂层两臂） | 同上 |
| M3 | 内存版 `commit()` → `true` | 2 红（`inMemoryStoreWritesInProcessButReportsNotPersisted` + 工厂层降级臂） | 同上 |

基线/还原后 sha256：`plan=a4f38777…`、`factory=2196caaa…`（`probe/mutate.sh` 每次
还原后重新计算并比对，不一致即 fail）。

### 4.3 静态门 113 / 夹具 349（分支树本地跑）

- `sh scripts/scan/scan.sh` → `Summary: 113 passed, 0 failed`（rc=0）。
- `python3 scripts/scan/test_scan.py --jobs 4` 在沙箱内跑到 13/48 组时进程被环境回收
  （S28 族：壳内长链子进程窗口），按 sandbox-limits 的健康路径改为**逐组独立进程**：
  `python3 scripts/scan/test_scan.py --single <name>` × 48 组，聚合
  `groups=48 cases_pass=349 cases_fail=0` —— 与基线 349 一致。

## 5. CI 读数

- run **2794**（id `40539936859`，workflow_dispatch，branch `fix/keystore-degrade-visibility`）
  **success**，attempt 1，16:20:27Z → 16:41:15Z（20m48s），job `122315280829`。
  <https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40539936859>
- `head_sha` = `79c0db5ec94fe5e160f37ab130c4de23c9d1338b`，与本地 HEAD **逐字符一致**。
- 步骤：1–22 全 success（含 `Run pre-build scan gate` / `Run unit tests (full suite)` /
  `Build APK with Gradle`），23/24（滚动 tag、Publish to Releases）= main 门控
  **skipped**，符合分支预期。
- 日志 1,480,801 B，NUL 字节 0：
  - `║  Summary: 113 passed, 0 failed` **恰 1**（另有其他门自报的 Summary 行
    25/16/44/19/30/18 passed 各 1，全部 `0 failed`）；
  - 门头 `[N/113]` 编号 **113 个互异**、各恰 1 次，末门 `[113/113]`；
  - `ALL 349 CASES PASS` **恰 1**；
  - ` e: ` 编译错误计数 **0**；
  - 6 条含 `FAILED` 的行逐条核对，全部是 **PASSED 用例名子串**
    （RealAgentAdapterSkeletonTest、ChatAgentTraceObserverSchemaTest ×4、
    TerminalErrorSurfacedTest）。
- 新增单测在 CI 真 Android SDK 下：`KeystoreRecoveryPlanTest` **14 臂全部 PASSED**
  （`testReleaseUnitTest`，`BUILD SUCCESSFUL in 5m 47s`）—— 顺带证明了
  `internal` 可见性、临时目录 File 操作与生产桩面在真编译面成立。


## 6. 残留（三件套）

- **R1 —— 第二阶段备份是取证级、不是恢复级**：`SharedKeyset` 的备份发生在
  **删 alias 之前**，alias 一删，这份 keyset 密文就再也解不开了（keystore 私钥不可导出，
  无从备份）。真正救数据的是「重试吃掉瞬时失败」+ 第一阶段（keyset 未动）的本 store 备份。
  触发条件：真机上确实出现「两次阶段都跑完、用户丢了凭据」的案例时，才值得谈
  「alias 保留一代 + 迁移」这类更重的方案。升级触发：真机日志出现
  `E/EncryptedPrefsFactory: DEGRADED` 且用户报「凭据全丢」。
- **R2 —— 可见性只到 API + 日志，没有 UI 消费者**：`isDegraded` / `degradedStores()`
  目前无调用方（任务书明确「不加 UI」）。触发条件：下次动 Settings / About 页面时
  加一行状态（连同备份文件提示）。升级触发：出现「用户报凭据丢失但我们无法在界面上
  印证降级」的第二例。
- **R3 —— 备份文件会长期驻留**：每 store 最多 3 份密文（含共享 keyset 3 份），
  没有到期清理（没有「备份已被用户确认无用」的信号可用，自动删反而制造新的静默丢失）。
  触发条件：备份占用的空间出现在存储统计异常里，或用户抱怨 `shared_prefs/` 里出现
  不明文件；那时加一个有上限的清理入口（只在 Settings 里显式触发）。

## 7. 故意不做的事（半径纪律）

- 不改 `EncryptedSharedPreferences.create` 的用法（不换 security-crypto 版本、不自管 keyset）。
- 不加 UI、不加新门（装置按 P0 配比：JVM + 变异 + CI）。
- 不动 `DeviceIdentity` / `ModelExecutionService`：实例复用在工厂层解决，调用点零改动。
- 不删旧备份、不迁移明文：内存版仍 fail-closed（绝不落明文）。
- 不对异常类型分类（见 §2 第 1 条）。

## 8. 交付状态

- 分支 `fix/keystore-degrade-visibility`，代码提交 `79c0db5ec94f`，基线 main `85058847`。
- 停止节点 = **分支 CI 绿**（不合并 main，合并起手权在用户）。
