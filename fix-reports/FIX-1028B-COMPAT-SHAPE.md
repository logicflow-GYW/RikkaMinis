# FIX-1028B-COMPAT-SHAPE — W8 族 compat 侧：「读到了但形状不对」的第三态

## 1 身份链

| 项 | 值 |
|---|---|
| 基线 | `47c4bf4b`（worktree `wt-b3`，`git log --oneline -1` 实测） |
| 分支 | `fix/1028b-compat-shape-notes` |
| 代码提交 | `7ad108cc`（生产 `OpenMinisBackupCompat.kt` +382/−42、测试 `OpenMinisBackupCompatTest.kt` +184/−2） |
| 改动面 | **2 文件**：`backup/OpenMinisBackupCompat.kt` + 其测试文件。`scripts/scan/**` **零触碰**（任务书禁止），`backup/ConfigBackup.kt` **零触碰**（支 1 独占），门总数 **113 不变**、夹具 **349 不变** |
| 本地门 | `sh scripts/scan/scan.sh --jobs 3` = **113 passed, 0 failed**；`python3 scripts/scan/test_scan.py --single test_real_repo` = **20 / 0** |
| 仓库单测（本地 JVM 真跑） | `OpenMinisBackupCompatTest` **36 tests OK**（原 28 + 新增 8） |
| 分支 CI | _待填_ |

## 2 缺陷与先证

### 2.1 机制

本层对「读不到」已经建了三套墙（`JsonlShardRead.missingShards` / `ImportResult.skipped` / `MinisBakException`），**对「读到了但形状不对」一套都没有**。org.json 的 `optJSONArray(k) == null` 同时表示两种完全不同的世界：

- 对端**没有**这个 section（正常的旧包 / 未启用的分类）⇒ 空数组是正确结果；
- 对端**有**这个 section，但类型换了（上游格式换代）⇒ 空数组是数据丢失。

原实现 13 处全部按前者处理。共同后果形状：**计数正常、界面正常、数据已不对**。

### 2.2 基线探针读数（改前 → 改后，同一夹具、同一探针）

| 用例 | 改前（`baseline.kt`） | 改后（`current.kt`） |
|---|---|---|
| `parts` 为 JSON 字符串 | `partsJson = "[]"`，无 note ⇒ 历史成空壳 | `partsJson` 还原为原数组，note：`parts：2 条记录以 JSON 字符串形态出现，已解析还原` |
| `parts` 为对象 | `partsJson = "[]"`，无 note | `"[]"` + note：`期望 array，实际 object，2 条消息按空 parts 导入` |
| `sortOrder` 缺键 | 写出 `"sortOrder": 0`（消息与思考规则两处），无 note | **不写该键** + note：`缺 "sortOrder"：已省略该键，交由导入端的下标兜底` |
| `instances` 为对象 | provider 0 个，无 note | provider 0 个 + note：`期望 array，实际 object`；`envVars` 等其它 section 不受影响 |
| `modelGroups` 为对象 | groups 0 个，provider 仍在，无 note | groups 0 + note，provider 仍在（其它 section 不被污染） |
| `env_vars.json` 的 `entries` 为对象 | envVars 0 条，无 note | 0 条 + note：`entries：期望 array，实际 object` |
| `secrets.providers` 为对象 | provider 仍在但凭据全丢，无 note | 同上 + note（凭据丢失现在可见） |
| 记录 `d` 不是对象（消息） | 该条消息**消失**，无 note | 该条消失 + note：`MessageV2 的 "d"：期望 object，实际 string` |
| `files.index` 分片（`-0002`） | 只读到第 1 片 ⇒ skills 1 个（第二个技能整体缺失） | skills **2 个**，且不产生任何 note（正常形态） |

复现命令：`sh /var/minis/shared/work/batch-1028b/probe-b3/run.sh all`（两份实现各自编译，跑同一组 10 个模式）。

`sortOrder` 这一条要单独说明为什么是"缺陷"而不是"默认值"：下游**本来就为缺键准备了下标兜底** —— `ConfigBackup.kt:3163` `sortOrder = m.optInt("sortOrder", i)`、`ProviderRepository.restoreThinkingRules` 的 `o.optInt("sortOrder", i)`。转换层无条件写 `0` 把这条兜底彻底废掉：整批消息/规则拿到同一个排序值，顺序塌成"谁先被插入谁靠前"。

## 3 三态设计

一个 helper（`ShapeNotes`，一次转换一个实例）+ 四个读取器：

| 读取器 | 语义 | 与旧行为的关系 |
|---|---|---|
| `array(source, key, where, fallback)` | 缺失 / 显式 null ⇒ fallback；数组 ⇒ 原数组；其它 ⇒ fallback + note | 与 `optJSONArray(k) ?: fallback` 在前两态**逐字节一致** |
| `arrayOrNull(...)` | 同上，但缺失也返回 null | 与 `optJSONArray(k)?.let { … }` 一致 |
| `objOrNull(...)` | 对象 / 缺失 / 其它（+note） | 与 `optJSONObject(k)` 前两态一致 |
| `sortOrder(...)` | 数字 / 缺键（不写）/ 其它（不写 + note） | 唯一**有意**改变正常路径的地方见 §7-2 |
| `partsJsonFor(...)` | 数组 / 字符串（可解析 ⇒ 还原）/ 其它（空 + note） | 数组路径与改前逐字节一致 |

**折叠**：同一站点可以命中上千次（每条消息都可能缺 `sortOrder`），逐条产出会把 `conversionNotes` 灌成日志 ⇒ `ShapeNotes` 只计数，`flush()` 时每个站点产出**一行**（≥2 次时附「共 N 处」）。

**出口**：只用既有通道 —— `convert()` 末尾 `shapes.flush(notes)`，随后既有的 `if (notes.isNotEmpty()) doc.put("conversionNotes", …)`（`ConfigBackup.kt:1884` 会 addAll 进 `ImportResult.skipped`）。**没有新键、没有新 UI、`ConfigBackup.kt` 一行未改。**

## 4 逐站点清单

### 4.1 本支已处理（15 处）

| # | 站点 | 工单 | 处理 |
|---|---|---|---|
| 1 | `convertChats` 消息 `parts` | A-7 | 三态 + **字符串还原**（能解析成 JSON 数组就还原内容，不只报空） |
| 2 | `convertThinkingRules` 的 `sortOrder` | A-20 | 缺键不写 / 类型错不写 + note |
| 3 | `convertChats` 消息的 `sortOrder` | A-20 | 同上 |
| 4 | `provider_config.instances` | A-1 | `array` + note |
| 5 | `provider_config.modelEntries` | A-1 | `array` + note |
| 6 | `secrets.providers` | A-1 | `arrayOrNull` + note |
| 7 | `model.reasoningEffortValues` | A-1 | `arrayOrNull` + note |
| 8 | `model.inputModalities` / `outputModalities` | A-1 | `arrayOrNull` + note（两处） |
| 9 | `provider_config.modelGroups` | A-9 | `arrayOrNull` + note |
| 10 | `group.memberEntryIds` | A-9 | `array` + note |
| 11 | `data/env_vars.json` 顶层（数组解析失败 / 非对象 / `entries` 类型错） | A-11 | 三种分开报 + note |
| 12 | `secrets.envVars` | A-11 | `arrayOrNull` + note |
| 13 | `data/mcp_servers.json` 顶层 与 `mcpServers` | — | 两种分开报 + note |
| 14 | 记录级 `d` 不是对象（sessions / messages / thinking rules） | — | `objOrNull` + note（原来 `?: continue` 静默丢整条记录） |
| 15 | `files.index` 分片读取 + 索引缺失（有 blob 无索引） | A-13 | 改走 `readJsonlShards`（单片包行为不变）+ 缺索引 note |

另有两处同族补齐（同一次改动顺带，成本≈0）：
- `data/memory/<rel>` 读不出来（目录里看得见、`readMember` 返回 null）→ note（A-12 的本文件侧）；
- 索引里有 sha、`blobs/` 里没有内容 → 每个技能一条 note（"该技能不完整"）。

### 4.2 剩余站点（**明确不修**，登记见 §8）

| # | 站点 | 为什么不修 |
|---|---|---|
| R-1 | **元素级**第三态：`instances.optJSONObject(i)`、`entries.optJSONObject(j)`、`groups.optJSONObject(i)`、`secrets.providers/envVars` 元素、`mcpServers` 元素、`e.optJSONObject("model")`、`e.optJSONObject("overrides")` | 形状是"单条记录坏了"而不是"section 换代"。要报就得按父 section 折叠计数（现有 helper 已能承载），但本支的任务面是 section 级；触发证据不足（无任何上游换代迹象） |
| R-2 | 时间字段 `isoToMillis(x) ?: 0L`（sessions `createdAt/updatedAt`、messages `createdAt`） | 落 0 的后果是"纪元零点"而不是丢数据；`isoToMillis` 已经接受四种 ISO 形态 + 裸 Long，剩余触发面是"时间戳被写成对象"这种极窄形状 |
| R-3 | `d.optJSONObject("session") ?: d` 包装层 | `session` 非对象时回退到平坦读，字段为空但会话仍导入（标题空）。自己就是一条 note 的候选，但要先合成"字段级空 vs 缺失"判据 |
| R-4 | `manifest.encryption` / `enc.kdf` 形状 | 非对象时的表现是 **fail-loud**（`kdf` 缺失直接抛 `MinisBakException`；`encryption` 非对象会在首个成员解密处抛）——不是静默路径 |
| R-5 | `files.index` 条目的 `sha256` 为空 ⇒ 跳过该文件 | 与"索引有 sha 但 blob 缺失"同族，但触发点在索引字段本身；顺路成本不≈0（要改索引过滤循环的结构） |
| R-6 | `ConfigBackup` 侧的对应消费点（A-12 / A-15） | 任务书划给后续，不在本支文件面 |

## 5 装置

### 5.1 探针（JVM 真编译真跑，`/var/minis/shared/work/batch-1028b/probe-b3/`）

- `baseline.kt`（`git show 47c4bf4b:…` 原文）与 `current.kt`（工作树）**分别编译**成两个 jar，跑同一份 `Probe.kt`（10 个模式）；每个模式自打印 PASS/FAIL 行并以退出码收敛。
- 夹具按真实包形态构造：STORED zip、两级 blob（`blobs/<sha[:2]>/<sha>`）、`files.index.jsonl` 分片、`data/env_vars.json` 顶层数组、`session` 包裹层。
- 归一化：`exportedAt`（`now()`）与技能 ZIP 内的 DOS 时间戳（取自时钟，非代码推导）在比对前归一化 —— **只归一化这两项，其余逐字节比对**。

### 5.2 对照臂（本支最重要的不变量）

正常包（全类别 + 2 条消息 + 2 个技能，**无任何形状异常**）在改前/改后输出：

```
72d8672d71c9beb5c2879abadb6a62383affe85dae29f6479db818d53cd04d0c  build/normal-baseline.json
72d8672d71c9beb5c2879abadb6a62383affe85dae29f6479db818d53cd04d0c  build/normal-current.json
```

`cmp` 逐字节相同。即：**第三态零命中 ⇒ 输出文档与升级前完全一样**（这也回答了"第三态会不会误报正常包"——正常包根本不进第三态分支）。

### 5.3 变异负控（5 臂，全部 `MATCH`）

`python3 mut.py` 把每处第三态逐个退回修复前的形状（不是"删掉 note"，而是**还原成改动前的真实拼写**），断言：目标用例变红 **且** 对照臂仍全绿。

| 臂 | 变异 | 目标用例读数 | 对照臂 |
|---|---|---|---|
| m1-parts | `partsJsonFor(...)` → `(d.optJSONArray("parts") ?: JSONArray()).toString()` | parts-string 2 红（含 `recovered :: []`）· parts-object 1 红 | `normal` 全绿 |
| m2-sortorder | 两处 → `optInt("sortOrder", 0)` | sortorder-missing **3 红**（键存在且为 0 ×2 + 无 note） | 全绿 |
| m3-array | `arrayOrNull` 的第三态不记 note | instances / groups / envvars / secrets **4 个模式各 1 红** | 全绿 |
| m4-index | `readJsonlShards("files.index")` → 单片 `readMember` | skills-sharded `2-skills :: n=1` | 全绿 |
| m5-obj | `objOrNull` 的第三态不记 note | msg-d-string 1 红 | 全绿 |

每臂**只红钉住它的那几条**（例如 m3 不影响 parts/sortOrder 用例），说明装置不是"永远红"的常量断言。

### 5.4 仓库单测（进 CI 的持久装置，8 条新增）

`OpenMinisBackupCompatTest` 新增：parts 字符串还原 / parts 类型错 + 定位文案 / `sortOrder` 缺键不写（消息 + 规则）/ `instances` 换代（且不污染其它 section）/ `modelGroups` 换代 / `env_vars.entries` 换代 / `files.index` 分片读全 / `d` 非对象留 note。本地 `kotlinc` + JUnit **36 tests OK**（`ConfigBackup` 用只含 `conversionNotes` 的桩，因真身需要 Android/Room；CI 跑真身）。

## 6 CI

| 项 | 值 |
|---|---|
| run | `40660117200`（`workflow_dispatch`，attempt 1） |
| job | `122720761273` `build` — **success** |
| head_sha | `7ad108ccfc12a9e17b031a3cb37ee94e77bbfb1d`，与本地 `git rev-parse HEAD` **逐字符一致** |
| 时长 | 08:54:06Z → 09:08:25Z（14m19s；run 08:54:03Z → 完成 09:08:26Z） |
| 步骤 | 27 步：**25 success + 2 skipped**（rolling tag / Publish —— 均 `if: ref == refs/heads/main` 门控，分支上skip = 预期） |
| 日志 | 1,501,815 B / **NUL 0** |

日志判读（逐条 grep，不是整体感觉）：

| 判据 | 读数 |
|---|---|
| `Summary: 113 passed, 0 failed` | **×1** |
| `ALL 349 CASES PASS` | **×1** |
| Kotlin 编译错误 ` e: ` | **0** |
| 测试 `FAILED` 行（`> … FAILED$`） | **0** |
| `OpenMinisBackupCompatTest` | **36 PASSED / 0 FAILED**（原 28 + 新增 8，逐条点名确认在 CI 真跑：parts 字符串还原 / parts 类型错 / `sortOrder` 缺键 / `instances` 换代 / `modelGroups` 换代 / `env_vars.entries` 换代 / `files.index` 分片 / `d` 非对象） |


## 7 诚实边界

1. **真机未验**。全部证据来自 JVM 探针（真编译真跑生产文件）、仓库单测、云端编译 + 单测。没有真机上的 `.minisbak` 导入实验（沙箱无 Android 运行时）。
2. **本支有一处有意改变正常路径的输出**：`sortOrder` 缺键时不再写 `0`。对"每条都带 sortOrder"的包无影响（对照臂 sha 相同），对"上游不写该键"的包会**去掉** `"sortOrder":0` 并让它落到导入端的下标兜底 —— 这正是 A-20 要的修法，但它确实是行为变化，不是纯增量。
3. **第三态误报的排除方式**（任务书点名要求）：① 对照臂（正常包逐字节一致）；② 只有 `has(key)` 且类型不符才进第三态（不看集合内容、不看字段值）；③ 显式 `JSONObject.NULL` 与缺失一律算"缺失"（不报）；④ 缺失路径返回的仍是原来的 fallback 值，第三态只**加一条 note**、不改变返回值形态。
4. **`files.index` 是否真的会被分片，本仓库无法证实**（上游闭源）。实现按"若分片则读全 / 若单文件则行为不变"处理；代价是流末尾多 2 次 `File.isFile` stat。基线探针证明单片包读出来完全一致。
5. **两条路径没有红/绿臂**：`memory` 读不出来（要造"遍历与读取之间文件消失"，不稳定）与"技能缺 blob"（可造，但本支未加夹具）。这两处只有代码路径 + 阅读证据。
6. **`conversionNotes` 没有条数上限**。本支按站点折叠（最多几十行），但"每技能一条缺失 blob note"这一族在极端包上会线性增长。
7. 门/夹具数不变（113 / 349），`openminis_compat_field_parity_check` 仍 **OK (53 keys pinned)** —— 本支没有改任何被 pin 的键名。

## 8 残留登记（三件套：触发条件 + 触发后方案 + 搁置理由）

| # | 条目 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R-1 | 元素级第三态（单条 provider/model/group/env-var/mcp 是标量而非对象） | 出现一份真实 `.minisbak` 里某元素不是对象（用户报告"少了一个 provider"且包内该元素为字符串），或上游 manifest 版本号变化 | 复用 `ShapeNotes.wrong` 但 `where` 换成"父 section 的第 N 项"，按父 section 折叠计数 | 无触发证据；形状远比 section 换代罕见 |
| R-2 | 时间字段形状不对落 `?: 0L` | 用户报告导入后会话/消息时间显示 1970，或上游把时间戳改成对象/嵌套 | 复用 `sortOrder` 的三态形态加 `millis(...)` 读取器（缺失也 note，因为 0 会显示成 1970） | 后果是"时间错"而非"数据丢"，且 `isoToMillis` 面已很宽 |
| R-3 | `session` 包装层非对象的回退读 | 上游把 `d.session` 改成非对象形态 | 回退时补一条 note（"包装层形状变了，按平坦字段读"） | 现在仍能导入（只是字段空），属"降级可用" |
| R-4 | `manifest.encryption/kdf` 形状 | 加密包导入时报"缺少 kdf 参数"但用户确信包完整 | 在 `deriveKeys` 前判 `enc` 是否对象，非对象时给出更准的错误文案 | 现状 fail-loud，不是静默 |
| R-5 | 索引条目 `sha256` 为空而被跳过 | 用户报告某技能文件整体缺失且包里有该路径索引 | 在索引过滤循环里对"有 path 无 sha"折叠计数，复用同一 note 通道 | 需要改过滤循环结构，顺路成本不≈0 |
