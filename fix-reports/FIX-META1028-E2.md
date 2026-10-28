# FIX-META1028-E2 · 数据层五文件薄修（B07 F-07 / F-11 / F-12 / F-13 / F-14）

- 分支 `fix/meta1028-e2-data-misc`，基线 `bfb5b0e6`（main），工作树 `wt-e2-data-misc`。
- 改动面：**9 文件 +721 / −136**，其中生产 Kotlin 6 文件（含 3 个新文件）、Python CLI 1 文件、报告 1 文件。
- 五条各自独立提交，回滚粒度 = 一条一笔。
- 装置纪律：只跑自家窄探针（kotlinc 单文件 + JUnit + python 并发/形状探针），**未运行全量 `scan.sh` / `test_scan.py` / gradle**。

| commit | 条目 | 目标文件 |
|---|---|---|
| `157be8eb` | F-07 | `data/repository/MemoryRepository.kt` |
| `f1ce129a` | F-12 | `data/MountedFoldersStore.kt` |
| `5b97e624` | F-14 | `data/repository/ServersJsonIo.kt`（新）+ `MCPRepository.kt` + `utils/config.py` |
| `07684170` | F-11 | `data/SessionForkRollback.kt`（新）+ `SessionForkManager.kt` |
| `986037c2` | F-13 | `data/UpdateStaging.kt`（新）+ `UpdateChecker.kt` |

---

## 1 身份链

任务书 `TASK-E2.md`（B07 数据层波 4 薄修合支）→ 报告来源 B07 五条：
F-07 / F-11 / F-12 / F-13 / F-14。

**B07 原始报告在本机已不可得**：共享盘 `reports/r2/` 下无 B07 文件（`grep -rl "B07"` 与按日期 `find` 均空），任务书 §2 的表格是唯一载体，且全部证据行是转写来的。因此本支的取证口径是：

> 任务书表格的**机制描述**当作待验证假设，真码当唯一事实源。机制成立 → 修；机制不成立或危害不成立 → 登记不修并在 §2 记录证伪过程。

结论：**5 条中 5 条机制成立**，但其中 3 条的「危害面」与表格不一致（见 §2 与 §4 证据差异表）。

## 2 缺陷与先证

### F-07（P1）标记字面量多源 + 预算方向反了

真码两处独立事实：

1. 条目标记（`[P1-append]` 一族）在多处硬编码出现，`revokeEntry` / `replaceEntry` 的匹配与生成各写一遍 → 任一处漂移，撤销会静默失配。
2. 行预算截断取的是**旧端**：超限时保留下来的是一批旧条目，最新追加的条目被裁掉。而 append 语义下「最新」才是用户刚写下的内容。

⇒ 机制成立。修法：标记收敛为单一来源（纯函数生成 + 匹配同一常量）；预算改为**从最新往下填**，超限裁旧。窄探针 32 例。

### F-11（P1）分叉回滚只覆盖四阶段中的一段

真码：`duplicateSession` 的 `try` 只包住消息循环：

```
try { for (msg in messages) appendMessage(...) } catch (t) { deleteSession(new.id); throw }
```

四阶段真实归属：

| 阶段 | 位置 | 失败后谁清理 |
|---|---|---|
| 会话行 + 四个元数据写（title/category、binding、memory、thinking） | try **之外** | 靠 `deleteSession` 的行级删除**顺带**带走 —— 是「四者恰好都是该行的列」这个存储布局的巧合 |
| 消息循环 | try 之内 | catch 里 `deleteSession`（FK CASCADE） |
| compact markers | try **之外**（下方） | **无人** —— 约束/磁盘失败时行、消息、半个 markers 全部留下 |
| `copySessionArtifacts`（文件：`minis-sessions/<new>/…` + `media/<yyyy>/<MM>/<dd>/<new>`） | try **之外**（下方） | **无人** —— 无任何 CASCADE 触达文件系统 |

补充真码事实（修正报告的暗示）：`copySessionArtifacts` 内部**全程 `runCatching`，自身不抛**（失败只记 PARTIAL 日志），所以表格里「它抛了就漏」这条路径其实不通；真正裸奔的是 **marker 阶段** 与 **取消传播**（`CancellationException` 不经过 `runCatching(Throwable)` 的捕获点时会打断在其之前已写下的所有东西）。

⇒ 机制成立（「回滚只覆盖一段」为真），报告的具体危险路径需按上述改写。

修法：四阶段全部进同一个 `try`，回退交给新 `SessionForkRollback` 计划对象 —— 文件先删（显式递归删除，**按文件系统实际结果校验**，不信 `deleteRecursively()` 的 boolean）、再删行（FK 带消息与 markers）、最后重抛原 cause。阶段的拆分改成 `carryOverSessionMetadata` / `copyMessages` / `copyCompactMarkers` / `copySessionArtifacts` 四个具名函数，让 try 成为一段可读区域。

### F-12（P1）读不出 ≠ 没有挂载

真码：读盘失败（解析异常 / IO 异常）被折叠成空列表返回，随后任何一次保存都把这个空列表写回磁盘 → 用户配置被一次读失败永久清空，且日志里只会看到一次无关的解析警告。

⚠️ 该文件按 backlog 记录含**裸 NUL 字节**，普通 `grep` 会当二进制跳过；本轮实查 `MountedFoldersStore.kt` 无 NUL（用 `rg -a` 与字节计数双重确认），工具链不受影响。

⇒ 机制成立。修法：新增 `diskUnreadable` 语义（读失败 ≠ 零挂载），读失败时**保留内存值**并把坏文件改名隔离（`quarantineFileName` 纯函数，原始字节留在盘上可人工恢复），保存路径在 `diskUnreadable` 状态下拒绝用空表覆盖；顺带加 `Mutex` 串行化读改写。窄探针 6 例，修前臂红（`the unreadable bytes must survive the save somewhere in the store dir — expected:<1> but was:<0>`），修后绿。

### F-14（P1）两个写入方共用固定 `.tmp` 名

`servers.json` 有两个写入方 —— Kotlin 侧 `MCPRepository.save`、Python CLI 侧 `config.py:save_config` —— 都用 `<path>.tmp` 作为暂存名。两路并发（app 保存 + CLI 保存）时：A 写完 tmp，B 也写同一个 tmp，A rename，B rename 失败或把 A 的内容读成自己的 → **撕裂写**，最终 `servers.json` 可能是两半拼接或直接丢失。

⇒ 机制成立，且比报告更重（报告只说 Kotlin 侧；Python 侧同款，且两边并发才会互相踩踏）。

修法：新建 `ServersJsonIo.writeAtomic`（唯一临时名 + rename + 失败清理），Kotlin 侧改调它；Python 侧 `save_config` 改唯一暂存名 + `os.replace`。

并行探针（真并发，非模拟）：
- Kotlin：**对照 torn = 39/181，修后 torn = 0/215**
- Python：**baseline torn = 3 / observed 2939 / runs 80，修后 torn = 0 / observed 19818 / runs 80**（baseline 还实测复现了 `FileNotFoundError: …servers.json.tmp -> servers.json`）

### F-13（P1）`filesDir/updates/*.apk` 永久泄漏

真码：`download` 把 APK 落在 `filesDir/updates/`（**不是** cacheDir —— OS 会在安装中途驱逐 cache 文件，见 `T-android-update-resume-33637`），文件名按目标版本命名。**全应用无任何一处删除它**：安装成功不删（文件已交给系统安装器，可能仍在读）、下载失败不删、取消不删、`clearPending` 也不删。每装一个版本留下 ~15 MB，用户看不到也回收不了，随安装寿命**无界增长**。

⇒ 机制成立。修法：下次下载开头做一次清扫 ——

```
删除 *.apk（大小写不敏感）中不在 keep 且 mtime 早于 1 小时者
keep = 正在写的文件 + pending 记录指向的 APK
```

1 小时是**在飞窗口**：`PendingUpdateStore` 在安装 intent 发出那一刻就被清空，因此「系统安装器正在读的文件」与「废弃残留」在元数据上完全同形 —— 按版本比较（「比我正在下载的旧就删」）会把正在安装的包从安装器手里抽走。窗口取 1 小时，比该竞态宽一个量级，又短到目录里只留当前流程碰过的东西。**明确残留**：最近 1 小时内下载的文件会留到下一次下载，天花板是「每小时每次下载一份」而非「终身每版本一份」。

窄探针 12 例，含**对照臂**（修前流程转写：十次发布 → 盘上十个文件）与时钟回拨（mtime 在未来 → 年龄为负，必须读作"不算旧"）。调用点由 `shape_check.py` 单独钉（清扫没被调用的纯函数证明不了任何事）。

## 3 修法（阶梯落点）

| 条目 | 停在第几级 | 理由 |
|---|---|---|
| F-07 | 第 6 级「能一行」偏上一档 | 标记单源化 = 把两处字面量换成一个纯函数的一处定义；预算方向是 `takeLast`→`take` 语义反转，不新增抽象 |
| F-11 | 第 7 级最小实现 | 需要「阶段清单作为数据」，否则下次加阶段又会漏包；计划对象只描述 undo 顺序，不接管执行 |
| F-12 | 第 7 级最小实现 | `diskUnreadable` 是**状态**不是分支，必须有个字段承载；隔离文件名可纯函数化，故拆出来可测 |
| F-14 | 第 5 级「已装依赖」偏下 | 唯一临时名 + rename 是标准库能做的全部；两侧各写一份 helper（Kotlin / Python 无法共享代码） |
| F-13 | 第 7 级最小实现 | 清扫判据必须可纯函数测（列表 in / 名单 out），否则只能靠真机验 |

硬约束未被削减：输入校验（F-12 的坏文件隔离前先确认路径落在 store 目录内）、安全（F-14 保留 `EnvVarRedactor` 不动）、跨层完整性（F-11 的四个元数据字段逐字段搬运，未新增字段）、终止路径（F-11 的 `unwindFailedCopy` 保证异常/取消都走回退）。

## 4 证据差异表（报告 vs 真码）

| 条目 | 报告说法 | 真码事实 | 判定 |
|---|---|---|---|
| F-07 | 正则缺 `[^\S\n]*` 跨行误匹配 | 标记确实多源硬编码；跨行误匹配在当前 `Regex` 下未复现（组成式里无 `.`-跨行构造） | 机制**成立**，具体成因**部分不成立** |
| F-07 | 「仍正向删最旧条目」 | append 语义下删最旧本就该是正确方向，真缺陷是**截断取旧端**（新人被裁） | 描述**方向说反**，危害等价 |
| F-11 | 回滚漏包 + 「会话覆写漏拷字段」 | 漏包**成立**；「漏拷字段」**未复现**（四个元数据字段逐个搬运齐全） | 一半成立 |
| F-11 | 「`copySessionArtifacts` 抛则文件残留」 | 该函数**自身不抛**（全程 `runCatching`）；裸奔路径是 marker 阶段与取消传播 | 危险路径需改写 |
| F-12 | 读失败折叠为空并直写固化 | 完全成立 | 成立 |
| F-13 | 「安装/失败/取消路径都清理」 | 三条路径**都没清理**；另外 `clearPending` 也不清 | 成立 |
| F-13 | 暗示按版本清理 | 按版本清理**不安全**（安装器可能仍在读，见 §2） | 修法**改判** |
| F-14 | 无锁并发写固定 `.tmp` | 成立，且 **Python CLI 侧同款**（报告未提） | 成立且更重 |

## 5 验证四层

**L1 窄探针（本地，全部 `nice -n 10`，同一时刻只开一个 JVM）**

| 条目 | 装置 | 读数 |
|---|---|---|
| F-07 | `probe-e2/f07`（stubs + `MemoryAppendOrderTest`） | pre 红 → **post OK (32 tests)** |
| F-12 | `probe-e2/f12`（6 个 Android stub + `MountedFoldersStorePersistenceTest`） | pre 红（隔离文件缺失）→ **post OK (6 tests)** |
| F-14 | `probe-e2/f14`（Kotlin 并发探针 + Python 并发探针） | Kotlin torn 39/181 → **0/215**；Python torn 3/80 → **0/80**；`py_compile` OK |
| F-11 | `probe-e2/f11`（`SessionForkRollbackTest` 6 例 + `shape_check.py` 5 判据 × 6 变异臂） | **OK (6 tests)**；形状 5/5 GREEN，5 条变异全 RED |
| F-13 | `probe-e2/f13`（`UpdateStagingTest` 12 例 + `shape_check.py` 5 判据 × 5 变异臂） | **OK (12 tests)**；形状 5/5 GREEN，5 条变异全 RED |

装置设计要点（可复用）：

1. **对照臂 = 把修前算法逐字转写进测试**（F-14 的 `legacyFixedTempWrite_tears`、F-13 的 `legacyDownload`），先证明它在同一夹具上确实产生缺陷，再证明新实现不产生。夹具因此被证明「有牙」。
2. **缺写入类缺陷钉不住纯函数**：F-13 的「没人清扫」在纯函数上是不可见的（函数选得再对，没人调用就等于没修），所以拆成两层 —— JVM 测**选什么**，`shape_check.py` 测**有没有被调、keep 里有什么、失败会不会炸穿下载**。
3. **形状探针必须自带变异臂**：每条判据配一条「把缺陷重新塞回去」的变异体，仍绿即判 ROT（探针失效）。本轮 F-13 的形状探针就是靠这条抓到自己的 bug —— 首版 `fn_region` 把参数列表里的默认 lambda `{}` 当成函数体，5/5 假红；修好后 5/5 真绿。
4. **时钟回拨要进夹具**：F-13 的判据含 `now - mtime`，mtime 在未来会得负值。负值必须读作「不算旧」，否则一次系统对时就能把正在用的包删掉。

**L2 编译**：F-11 的 `SessionForkManager.kt` 用 6 个桩（实体 / 仓库 / DAO / SkillRepository / AppLogger / Context）真编译通过 —— 这是「改完还能编」的唯一本地证据（`data/` 层依赖 Room，沙箱无 Android SDK）。F-13 的 `UpdateChecker.kt` 未编译（依赖 OkHttp / Android / FileProvider 全链路），只经 L3 云端编译验证。

**L3 云端**：见 §8（CI run 与日志判读）。

**L4 真机**：**未验**（见 §7）。

## 6 残留（每条带触发条件 + 触发后方案）

| 编号 | 残留 | 触发条件 | 触发后方案 |
|---|---|---|---|
| R1 | F-13 清扫只在 `download` 开头跑；用户在装完之后再也不点更新，那份 APK 会一直留到下一次下载 | 有用户报「存储被更新包占满」 | 在 app 启动的 housekeeping 里也调 `UpdateStaging.prune`（keep 取当时的 pending） |
| R2 | F-13 的在飞窗口是 1 小时常量，未与安装器实际确认时长对齐 | 出现「安装失败，提示文件不存在」且发生在下载后 1 小时内 | 换成「pending 记录存在期间永不删」+ 安装确认回调里打标记（需要 PackageInstaller 回调） |
| R3 | F-11 的 `unwindFailedCopy` 里删除文件的失败只记日志，不重试 | 用户报「分叉失败后存储里多出一份会话目录」 | 加入 pending 清理队列，启动时重试（同 R1 的 housekeeping） |
| R4 | F-12 的 `diskUnreadable` 只在读失败时置位，恢复需要一次成功的读 | 坏文件被修好后用户仍看不到挂载项 | 在 `load()` 成功路径清位（当前实现已覆盖，但无测试钉住）→ 补一条 round-trip 用例 |
| R5 | F-07 的预算裁剪只保证「保留最新」，不保证上限的精确性（一次裁剪到限内而非逐条） | 有需求要求严格 N 条 | 改增量裁剪（性能换精确） |
| R6 | F-14 的 Python 侧唯一暂存名依赖 `os.getpid()`，跨主机挂载场景（同一目录被两台设备挂载）仍可能撞名 | 出现跨设备共享 rootfs 的用法 | 用 `uuid4` 替代 pid |

## 7 未验证边界

- **真机全链路**：分叉失败回滚（要真的制造一次 marker 写入失败）、更新包安装后再下载（要真装 APK）、挂载目录坏文件恢复（要真写坏一个 JSON）—— 沙箱无 Android 运行时，CI 只保证编译 + 单测 + 门。
- **`UpdateChecker.kt` 未经本地编译**：改动虽小（一次 `resumablePending` 提前绑定 + 一次 `buildSet`），但依赖面太大，只有云端编译能证。
- **F-11 的取消路径**：`CancellationException` 是否一定经过 `try` 的 catch —— 取决于调用方是否把 `duplicateSession` 放在可取消作用域内，本轮只按「`catch (t: Throwable)` 会捕获 `CancellationException` 并回退后重抛」这一语言事实推理，未做挂起注入测试。
- **F-14 的 Python 侧并发只在单机同进程族内验证**（80 轮 × 双写），跨进程调度差异未覆盖。

## 8 附装置路径

| 内容 | 路径 |
|---|---|
| 任务书 | `/var/minis/shared/work/meta-audit-1028/fix-tasks/TASK-E2.md` |
| 工作树 | `/var/minis/shared/work/meta-audit-1028/wt-e2-data-misc` |
| F-07 探针 | `probe-e2/f07/`（`run.sh pre|post`、`tests/MemoryAppendOrderTest.kt`） |
| F-12 探针 | `probe-e2/f12/`（6 stubs + `tests/MountedFoldersStorePersistenceTest.kt`） |
| F-14 探针 | `probe-e2/f14/`（`tests/ServersJsonIoTest.kt` 含对照臂、`cli_concurrency.py`） |
| F-11 探针 | `probe-e2/f11/`（`tests/*.kt`、`shape_check.py`、`compile/stubs/**`） |
| F-13 探针 | `probe-e2/f13/`（`tests/UpdateStagingTest.kt`、`shape_check.py`、`run.sh`） |
| JVM 依赖 | `/var/minis/shared/work/jvm-libs/`（junit 4.13.2 / hamcrest / kotlin-stdlib 2.1 / coroutines） |

---

## CI 判读

run `40699115184` / #2841 / attempt 1 / job `122853793891`

| 项 | 读数 |
|---|---|
| 结论 | **success** |
| head_sha | `986037c2260192b69f0e65cb5c4cc90611b9f197` —— 与本地 `git rev-parse HEAD` **逐字符一致** |
| 时长 | `13:43:50Z` → `14:05:08Z` = **21 min 18 s** |
| 步骤 | 编号 1–24（含 post 步）**唯一非 success = 23 滚动 tag + 24 Publish**，两者均为 `main` 门控 skip ⇒ 分支上 Publish 不执行，符合预期 |
| 日志 | 1,504,924 B / 解压后 1,500,648 字符 / **NUL 0** |
| 门 | `Summary: 113 passed, 0 failed` ×1；分段 Summary 25 / 16 / 44 / 19 / 30 / 18（并行池分片，不是门数变化）；门头 distinct **113**，编号 **1..113** 连续 |
| 夹具 | `ALL 349 CASES PASS` ×1 |
| Kotlin 编译错 | `^ e: ` **0** |
| 含 `FAILED` 的行 | 6 条，**逐条确认行尾为 `PASSED`**（用例名自身含 `FAILED` 字样：`slot denied produces FAILED without calling provider` / `reason PERSISTENCE_FAILED maps to …` 等） |

**门数 113 / 夹具 349 与基线 `bfb5b0e6` 一致** —— 本支未触碰 `scripts/scan/**`、`scan.sh`、`test_scan.py`，不新增门。

**诚实说明**：本支的五套窄探针全部放在仓库外的 `probe-e2/`，**没有进 CI 视野**（日志里搜不到 `UpdateStagingTest` / `SessionForkRollbackTest` 等类名，命中 0 是预期而非异常）。CI 对本支的证明面只有三件：整个源码树能编译（含两个新文件）、既有 349 夹具无回归、113 门无回归。探针那层证据是本地读数，见 §5。

## 停止节点

分支 `fix/meta1028-e2-data-misc` @ 绿 sha `986037c2` = **确定性停止节点**。未合并 main（合并起手权在用户）。
