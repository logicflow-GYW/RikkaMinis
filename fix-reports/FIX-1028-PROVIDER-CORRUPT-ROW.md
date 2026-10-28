# FIX-1028-PROVIDER-CORRUPT-ROW — 坏行被下一次保存固化删除（B-1）

- 分支：`fix/p1028-provider-corrupt-row`
- 基线：`main` @ `12bdcebb`
- 缺陷来源：`scenario-experiment-1028/REPORT-B.md` §2 **B-1**（多副本仲裁 W9）
- 报告提交：本次修复代码提交 `7011e6e0`

---

## §1 先证（回真树核实，逐字）

### 1.1 加载侧：解码失败 → `mapNotNull` 丢掉，只留一条 warning

`src/android/app/src/main/java/com/rikkaminis/app/data/db/ProviderConfigMapping.kt:263-277`（基线）

```kotlin
    val entries = this.entries.mapNotNull { row ->
        // [audit-0917] Guard the two JSON blob decodes the same way the enum
        // parses above are guarded. A malformed baseModelJson/overridesJson
        // (truncated write, hand-edited mirror, forward-compat field) threw out
        // of the whole toProviderConfig, which failed the entire DB load — one
        // bad row wiped every provider and group from the UI. Skip the bad row
        // and keep the rest.
        val baseModel = runCatching {
            jsonForBlobs.decodeFromString(LLMModel.serializer(), row.baseModelJson)
        }.onFailure {
            AppLogger.warning(
                TAG,
                "skipping model entry ${row.id}: baseModelJson undecodable (${it.message})",
            )
        }.getOrNull() ?: return@mapNotNull null
```

注释与报告结论一致：`audit-0917` 的意图是「一行坏不要炸整棵树」，这个保护是**对的**，也确实修掉了「一个坏行让所有 provider 消失」。

### 1.2 保存侧：删除集合 = 「我读出来的 id」，于是坏行**物理消失**

`ProviderConfigDao.kt:97-104`（基线，`syncAllIncremental`）

```kotlin
        if (entries.isEmpty()) {
            clearEntries()
        } else {
            upsertEntries(entries)
            deleteEntriesNotIn(entries.map { it.id })
        }
```

`NOT IN (entries.map { it.id })` 把「本次读出来的集合」直接当成「应保留的集合」。坏行不在 `entries` 里（1.1 已把它丢掉），于是**下一次任何 provider 写操作**（改名 / 拖拽排序 / `/models` 刷新）都会：

1. `entries.map { id }` 不含坏行 → `deleteEntriesNotIn` 物理删掉该行；
2. 同时把 JSON mirror 覆写成同样「少一行」的形状（`persistToDbAndMirror` 无条件写 mirror）。

两条副本同时消失，原始字节不可恢复。

### 1.3 为什么 mirror 救不回来

mirror 是**同一个 `ProviderConfig` 的序列化结果**，而该行在加载时就已经不在 config 里了。所以 mirror 从来就没有这个模型的副本——这也是报告 B-1「坏副本冒充好副本 + 导入后副本分叉并被固化」的实质：**这不是「两份副本不一致」，是「只有一份，而它正被降级读覆盖」**。

### 1.4 触发路径（为什么这是 P1 而不是理论问题）

- 写坏方式不必是硬件损坏：`base_model_json` 半写（进程在写事务中被杀——本机 `launch-beacon` 记录过一夜 23 次 silent kill）、未来版本写入本版解码器拒绝的字段（`coerceInputValues` 只兜枚举，不兜结构变化）、手改 mirror 回灌。
- 一旦发生，**不需要用户做错任何事**：打开设置改个 provider 名字即完成永久删除，而且 UI 全程正常（模型只是「不见了」，看起来像用户自己删的）。

---

## §2 修法（分层：谁能区分「没有」与「读不到」）

核心判断：**只有映射层知道某行是「对端没有」还是「读不出来」**。所以 id 在映射层产生、显式交给 DAO，**不在 DAO 里做任何推断**（任务书禁区）。

| 层 | 改动 |
|---|---|
| `ProviderConfigMapping.kt` | 新增 `data class ProviderConfigLoad(config, quarantinedEntryRowIds)` + `toProviderConfigWithDiagnostics()`；`toProviderConfig()` 变成一行 wrapper（4 个既有调用点零改动）。解码失败分支从 `.getOrNull() ?: return@mapNotNull null` 拆成「记 id + return」。 |
| `ProviderConfigMapping.kt` | 新增纯函数 `entryIdsToKeep(decoded, quarantined) = (decoded.map{id} + quarantined).distinct()`——把「应保留集合」的唯一构造点收成一处。 |
| `ProviderConfigDao.kt` | `syncAllIncremental(..., preserveEntryIds: Set<String> = emptySet())`；keep 集合改用 `entryIdsToKeep`。**并堵住第二个门**：`entries.isEmpty()` 时不再无条件 `clearEntries()`——当「唯一幸存的只有隔离行」时，那是同一次清空换了个门。 |
| `ProviderRepository.kt` | `@Volatile quarantinedEntryRowIds: Set<String>`，加载时记录（进程生命周期内保留：行在能读它的版本出现前一直读不出来），保存时经 `preserveEntryIds` 传给 DAO；加载时一条聚合 warning 点名「N 行无法解码，已保留」。 |

**行为变化**（只有一条）：坏行的 DB 行不再被无关写操作删除；显示行为完全不变（坏行仍不进内存 config、不进 UI）。

### §2.1 任务书「修法 3」要求的其他 sync 面，逐条核实结果

| 面 | 结论 | 依据 |
|---|---|---|
| `deleteGroupsNotIn(groups.map{id})` | **不需要改**：组行不会因解码失败被丢（`toProviderConfig` 里 `groups` 用 `map` 不是 `mapNotNull`），keep 集合天然完整 | 映射层 `val groups = this.groups.map { row -> … }`；测试用例 3 断言 `lastDeleteGroupsKeep == ["g1"]` |
| 组对坏行的成员引用 | **不丢**：`memberEntryIdsJson` 与 entries 投影无关，坏行的复合 id 原样留在组里；保存时经 idMap 未命中即原样透传 → 写回后引用仍在 | 测试用例 3(a)(c)：保存后 `memberEntryIdsJson = ["i1/m-good","i1/m-bad"]` |
| agent-loop pin（`loopIds` clear+upsert） | **不丢**：`agentLoopModelEntryIds` 直接从 `provider_agent_loop_ids` 的 `target_id` 读出，**不经过 entries 投影过滤** | 测试用例 3(b)(c)：保存后该 row 的 `targetId == "i1/m-bad"` |
| 各 prune 家族（`replaceEntries` / 语音模板） | **不会误伤**：`prunedEntryIds = existingEntryIds - survivingEntryIds`，而 `existing` 是在内存 config 上取的——坏行本就不在其中，进不了 prune 集合 | 代码阅读（`ProviderRepository.kt` 的 `prunedEntryIds` / `staleEntryIds` 两处） |

> 这一栏是「检查」而不是「顺手扩大修改面」：三处结论都是**保持现状即正确**，只有 entries 的删除集合是真的失守。

---

## §3 装置与读数（P1 级：最小复现 + 变异，不建新门）

### 3.1 JVM 装置（编译**真实生产文件**，不是复刻件）

`/var/minis/shared/work/p1028-fix/probe/`：

- `build.sh`：单次 kotlinc 调用编译**真实**的 `ProviderConfigMapping.kt`、`ProviderConfigDao.kt`（含 `@Transaction` 默认方法体）、5 个 entity、`ProviderConfig.kt` / `LLMModel.kt` / `ProviderCredentialMeta.kt`，加上 `-Xplugin=kotlin-serialization-compiler-plugin`；只有 `androidx.room` 注解、`androidx.compose.runtime.Stable`、`AppLogger` 是桩。
- 关键收益：**`syncAllIncremental` 的真实默认方法体在 JVM 上跑起来了**（`@Transaction` 只是注解）。`FakeProviderConfigDao` 实现 DAO 接口，record `deleteEntriesNotIn(keep)`，所以「keep 集合装配」不是被复刻、而是被真跑。
- 结果：`OK (6 tests)`（新测试）+ `OK (9 tests)`（新 6 + 既有 `ProviderConfigMappingRoundTripTest` 3，回归绿）。

### 3.2 六个用例

| # | 用例 | 钉什么 |
|---|---|---|
| 1 | `corrupt entry row is skipped from the config and reported by id` | 显示侧仍跳过（不给 UI 半点对象）+ 诊断侧 `quarantinedEntryRowIds == {"i1/m-bad"}` |
| 2 | `sync keeps the quarantined row in the entry delete set` | `deleteEntriesNotIn(keep)` 的 keep 含坏行 id；坏行**不被 upsert**（不让降级形状写回） |
| 3 | `group reference to a quarantined entry survives the save` | 组引用 / loop pin / 组行 keep 集合 / 条目 keep 集合四面 |
| 4 | `fully decodable snapshot reports no quarantine and an unchanged delete set` | 健康库：隔离集为空，keep 与旧行为**逐元素一致**（防回归） |
| 5 | `all rows corrupt still never takes the clearEntries shortcut` | 全部坏行时不得走 `clearEntries()`（同一清空的第二扇门） |
| 6 | `entryIdsToKeep appends quarantined ids without duplicating decoded ones` | 纯函数语义（含「陈旧隔离 id 无害」） |

### 3.3 变异负控（4 臂，逐臂重建 + 逐臂复原）

`probe/mutate.py`，每臂改一行生产代码 → 重编译 → 跑测试 → 记录「哪几条红」；结束后 sha256 校验源码逐字符复原（`restore check: BYTE-IDENTICAL`）。

| 臂 | 变异 | 预期红 | 实际红 | 判定 |
|---|---|---|---|---|
| **M1** | keep 集合改回 `entryIdsToKeep(entries, emptySet())`（= 修复前形状） | 2, 3, 5 | 2, 3, 5 | MATCH |
| **M2** | 去掉隔离记录（`mapNotNull` 保持） | 1, 2, 3, 5 | 1, 2, 3, 5 | MATCH |
| **M3** | 恢复 `if (entries.isEmpty()) clearEntries()` 短路 | 5 | 5 | MATCH |
| **M4** | `entryIdsToKeep` 用隔离集**替换**而非并入解码集 | 2, 3, 4, 6 | 2, 3, 4, 6 | MATCH |

- 每一臂都**至少红一条、且只有预期的那几条红**——装置不是「永远红」（M1 让 4、1、6 保持绿就是证明：它不是靠写作方式凑红），也不是「永远绿」。
- M2 只红 1/2/3/5 而 4/6 保持绿，说明「记录」与「集合装配」是两个可独立证伪的半边。

### 3.4 静态门（本地）

- `sh scripts/scan/scan.sh --jobs 3` → **113 passed, 0 failed**（5m24s）
- `python3 scripts/scan/test_scan.py --single test_real_repo` → **@@TEST_RESULT@@ test_real_repo 20 0**（3m23s）

未新建门：本缺陷是「删除集合装配」的一次性语义错误，装置比例按 P1 走（最小复现 + 变异），不建扫描门。

---

## §4 CI

- workflow run **#2807**（`build-apk.yml` @ `fix/p1028-provider-corrupt-row`），run id `40637113030`，job id `122646517056`
- head_sha `7011e6e0220ece85ec786adb6804ee93eb68e699` —— 与本地 HEAD **逐字符一致**
- **conclusion = success**，05:17:21Z → 05:34:25Z（**17m04s**）
- 27 步中 **0 个非 success**；唯一 skipped 的两步 = `Move the rolling tag android-latest` / `Publish to Releases`（**main 门控**，分支上必 skip，属预期）
- 日志 1,490,164 B / NUL 0，逐条核：
  - `Summary: 113 passed, 0 failed` **恰 1 次**；门头 `[N/113]` 编号 **113 个互异**（1..113 齐）
  - `ALL 349 CASES PASS` **恰 1 次**
  - `ProviderConfigCorruptRowQuarantineTest` **6 行，全 PASSED**（= 本地 6 例在真 gradle + 真 Room 注解下同样成立，静默地把「JVM 桩下能跑」升级成「生产编译面能跑」）
  - `e: `（Kotlin 编译错误）**0 条**
  - 6 条含 `FAILED` 的行逐条确认**全部是 PASSED 用例名的子串**（用例名里带 FAILED 字样，第 N 次踩同一个判读陷阱）
- 报告提交后核对：`git diff --name-only 7011e6e0..HEAD -- . ':(exclude)fix-reports'` = 空

> 状态：**分支 CI 绿 = 确定性停止节点**。未合并 main（合并起手权在用户）。

---

## §5 诚实边界（这次没证明什么）

1. **`ProviderRepository` 的接线没有运行时测试。** `ProviderRepository` 需要 Android `Context`，JVM 不可构造。三行接线（字段 / 加载记录 / 传参）由 ①代码审阅 ②CI 编译 ③映射层与 DAO 的真跑测试 三段夹住，但「仓库层把两者接上了」本身没有可执行证据。若要补，得先有 ProviderRepository 的可注入 DAO/prefs，属于更大的装置——不在本支半径内。
2. **只在 JVM 上验证了逻辑，没有真机验证。** 沙箱无 Android 运行时；CI 保证编译 + 单测 + 113 门，不保证「线上真出现坏行时行为如描述」。
3. **`overridesJson` 解码失败不在本次保护范围**（见 §6-R1）。
4. **组的 `memberEntryIdsJson` 解码失败不在本次保护范围**（见 §6-R2）。
5. 「坏行保留」的上界是**它的 instance 行还活着**：`provider_model_entries` 对 `provider_instances` 有 `ON DELETE CASCADE`，删掉 provider 时坏行跟着走。这是有意的——实例都没了，留着行也没有归属。

---

## §6 残留（按修复门纪律登记，带触发条件）

### R1（P2）`overridesJson` 解码失败 → 默认值覆盖真值

- 现状：`overridesJson` 解不出来时降级为 `ModelOverrides()`（`audit-0917` 的同一处），行本身可解码 → 下次保存**用默认值覆写该 blob**。用户自定义的 displayName / contextWindow / maxThinkingLevel 等永久丢失。
- 为什么这次不修：要保住它就得对该行**跳过 upsert**，而这会连带冻结该条目的所有后续编辑（用户改了这个模型也不再生效）——是个产品权衡，不是一行能拍的事。半径纪律：本支只修触发路径（行被删除）。
- 触发条件：出现「模型还在、但我的自定义显示名/参数被重置」的用户报告，或做 provider 诊断入口时一并设计。
- 方案候选：把 `overridesJson` 解码失败也计入隔离集，但语义改为「保留 blob、跳过该行 upsert」，且**只在内存里的 overrides 仍是默认值时才跳过**（用户一改就接管）。

### R2（P2）组 `memberEntryIdsJson` 解码失败 → 成员表被写成 `[]`

- 现状：组行**不会**被删（这次已核实），但空成员列表会被下一次保存写回 `member_entry_ids_json`，成员关系永久丢失（条目还在，可以手工重建，但是静默的）。
- 触发条件：与 R1 同一族；实际出现「分组突然空了」的报告。
- 方案：保存时对「本次解码失败且内存里仍为空」的组回写原始 blob（需要 DAO 一条 `UPDATE ... SET member_entry_ids_json=?`，同样必须由映射层显式给出 id+blob）。

### R3（P2，设计权衡）坏行「永不消失」，且用户看不见

- 现状：坏行被保留在 DB 里，但**不进 UI、不进 mirror、也没有任何用户可见出口**。如果损坏是永久的（比如某次迁移写坏后本版永远解不出），这行会永久占位，且用户只知道「我的某个模型没了」。
- 这是本次有意的取舍：**「不丢」优先于「干净」**——丢是单向的，占位是可回收的。
- 触发条件：①有用户报告「模型少了但恢复备份也找不回」；②provider.db 体积异常且无法解释。届时方案 = 设置 → 日志/诊断加一行「N 行 provider 数据无法解码（已保留）」+ 一个显式「清除这些行」按钮（把「回收」变成用户动作，而不是无关写操作的副作用）。
- 在此之前，唯一的观测面是加载时那条聚合 warning（`[ProviderStore] N provider model entries undecodable — rows preserved…`）。
