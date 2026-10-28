# FIX-1028-ENVVAR-INDEX — envvar 索引完整性（B-3）：半截索引 = 脱敏静默失效

- 分支：`fix/p1028-envvar-index-integrity`
- 基线：main `12bdcebb`
- 代码提交：`215e10df`（本报告另起一提交）
- CI：run [40639424995](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40639424995) / job `122653838799` / **attempt 1 success** / 05:41:09→05:58:25Z（17m16s）/ `head_sha 215e10dface7…`
- 缺陷来源：`scenario-experiment-1028/REPORT-B.md` **B-3**

---

## 1. 先证（回真树读到的原文，行号以本支基线 `12bdcebb` 为准）

### 1.1 非原子写

`src/android/app/src/main/java/com/rikkaminis/app/data/repository/EnvVarRepository.kt:169`（修前）：

```kotlin
    private fun saveMetadata() {
        try {
            ...
            metadataFile.writeText(array.toString())      // :169
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }
```

`writeText` 就地截断再写，写入窗口内进程被杀就留下**半截 JSON**。这不是理论风险：同仓 `tools/AtomicFileWrite.kt` 的 KDoc 自己记着「23 silent kills in one evening, 2026-09-15」，而 `SoulStore` / `AutoBackupLocalStore` 都已经改用原子写——本处是漏网的一处。

### 1.2 读失败被折叠成「没有」

`:175-194`（修前）：

```kotlin
    private fun loadMetadata() {
        try {
            if (!metadataFile.exists()) return                    // 首次启动：合法
            val array = JSONArray(metadataFile.readText())
            ...
            _entries.value = entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata: ${e.message}")    // :192 只记日志
        }
    }
```

解析抛异常后 `_entries` 停在 `emptyList()`，**与「首次启动」不可区分**。四个消费方同时按「没有变量」行事：

| 消费方 | 位置（修前） | 后果 |
|---|---|---|
| 沙箱 env | `sandbox/ExecutionCoordinator.kt:676` `envVarRepository?.allAsDict() ?: emptyMap()` | 沙箱里一个环境变量都没有 |
| 长驻 shell | `sandbox/TerminalSession.kt:582` 同款 | 同上 |
| 设置列表 | `ui/settings/EnvironmentVariablesScreen.kt` 读 `entries` | 列表空白 |
| **脱敏** | `data/EnvVarRedactor.kt:82` `repo.allAsDict().values.filter { … }` | **`values` 为空 ⇒ 直接 return 原文**：隐私模式在设置里仍显示「开」，但明文照样进 prompt / 日志 |
| prompt 注入 | `ui/chat/ChatPromptAndTools.kt:1219` `repo.allAsDict()` | 注入空表 |

脱敏那条是凭据泄漏面，也是本条判 P1 而非 P2 的理由：**「开着但不起作用」比「关着」更危险**，用户不会去检查它。

---

## 2. 修法逐条

### ① 原子写（`saveMetadata`）

```kotlin
AtomicFileWrite.write(metadataFile, array.toString())
```

用仓库既有先例（`SoulStore.saveTo`、`AutoBackupLocalStore.writeAtomically` 同族）：写同目录临时文件 → rename 覆盖 → 文件系统拒绝临时文件时回退直写。`AtomicFileWrite` 是 `internal`，同模块可见，不改任何公开签名。

### ② 三态判定，不再折叠（`loadMetadata` + 新 `EnvVarMetadataCodec`）

把「解码」抽成**纯函数** `internal object EnvVarMetadataCodec.decode(text: String?)`，四态：

| 输入 | 结果 | 语义 |
|---|---|---|
| `null`（文件不存在） | `Missing` | 首次启动，空表是真相 |
| 合法数组 | `Decoded(entries, totalRows, damagedRows=0)` | 有效 |
| 合法数组但有坏行（非对象 / 无 key） | `Decoded(…, damagedRows>0)` | **部分**，好行保留、坏行计数 |
| 非数组 / 截断 / 0 字节 | `Corrupt(reason)` | **未知**（不是「没有」） |

`loadMetadata` 对后两种统一做三件事：

1. `AppLogger.warning` 记原因（`env-var index unusable: …`）；
2. `quarantineIndex()` 把**原始字节**另存 `env-vars.json.corrupt`（照 `SoulStore` 覆盖前备份的先例；first-wins，因为后来的副本可能已是「丢索引之后写出来的退化文件」）；
3. 抬高公开只读标志 `@Volatile var metadataUnreadable: Boolean = false; private set`（**不改任何既有方法签名**，`backup/ConfigBackup.kt` 的调用面零变化）。

`metadataUnreadable` **只由一次成功加载清零**：覆盖写修不好它，因为丢掉的 key 名不可能靠写文件重建。

### ③ 额外一步：从加密值把 key 名恢复回来

这是任务书没要求、但我认为该做的一步，理由是这场事故的**不对称性**：索引文件只存 **key 名**，值在 `EncryptedSharedPreferences` 里以同一批名字为键。所以半截索引丢的是名字，不是数据——名字可以从值存储重建：

```kotlin
encryptedPrefs.all.keys.filter { it.isNotEmpty() && it !in known }.sorted()
```

只在**受损加载**时执行（干净加载无从恢复，也不会引入新行为）。恢复出的条目没有 `note` / `group` / `createdAt`（那些只存在于丢失的文件里）。

没有这一步，「三态」只能告诉用户「我不知道」，用户看到的是空列表 + 一堆孤儿密文；有这一步，常见情形（只有索引被截断）下变量列表直接回来。顺带堵住一个同族坑：恢复出的 key 会参与 `isDuplicateKey`，用户重新添加同名变量时会被挡住，而不是把真值覆盖成占位值。

### ④ 脱敏 fail-closed（硬约束，未砍）

`EnvVarRedactor.redactIfEnabled` 在 `repo.metadataUnreadable` 时走降级路径：

```kotlin
val values = repo.allAsDict().values.filter { it.isNotEmpty() }   // 恢复了多少用多少
return redactDegradedWithReminder(output, values)
```

降级路径 = **值匹配 ∪ 形态匹配**（不是只做形态）：恢复出来的值仍是精确串，比形态更准；两遍都跑，`hits` 相加。

形态匹配 `redactUnindexed`（纯函数，无 Android 依赖）两条线：

- **`name = value` / `name: value`**：名字按 `_`/`-`/`.` 与 camelCase 分段，任一段落在 `KEY / TOKEN / SECRET / PASSWORD / PASSWD / PWD / CREDENTIAL / AUTH / SIGNATURE / COOKIE` 即为密钥名；另加子串判据（`PASSWORD`/`PASSWD`/`SECRET`/`TOKEN`/`CREDENTIAL`/`AUTH`/`APIKEY`）覆盖 `Authorization`、`oauth` 这类无分界形态。值支持一层引号（JSON / shell），`Bearer <credential>` 这类方案词会把后一个 token 一起遮。
- **裸 token 前缀**：`sk-`、`sk_`、`github_pat_`、`ghp_/gho_/ghu_/ghs_/ghr_`、`xox[bpars]-`、`AKIA`、`ASIA`、`AIza`、`hf_`、`rnd_`、`eyJ`，且要求词边界（`task-1234567890` 里的 `sk-` 不算）。

三条设计约束：

- **不用正则**：与 `redact` 的 KDoc 同一条理由（输出是攻击者影响面，禁 ReDoS），全部是线性单次扫描。
- **长度守恒**：`mask` 本就保长度，跨两遍索引不失效。
- **提示词换一条**：降级时附 `UNINDEXED_SYSTEM_REMINDER`（明说「索引读不出、这是按形态兜底、不匹配形态的密钥不会被遮」），而不是复用 `SYSTEM_REMINDER`（那句会被读成「这段输出已经安全了」）。

---

## 3. 装置与变异读数

### 3.1 仓库内 JVM 测试（进 CI）

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `data/repository/EnvVarMetadataCodecTest.kt`（新） | 10 | 四态判定的纯函数面：`null`→Missing、`[]`、全字段往返、缺省默认值、截断→Corrupt、空文件→Corrupt、对象非数组→Corrupt、坏行保留好行、无 key 行算损坏、未知字段容忍 |
| `data/EnvVarRedactorTest.kt`（+14） | 30 | 形态兜底：shell 赋值、JSON 引号值、`Authorization: Bearer`、camelCase、`monkey`/`hockey` 不误遮、短值不遮、裸 token、词内 `sk-` 不遮、干净输出零改、多 span 计数、长度守恒、降级提示词、降级仍做值匹配 |

### 3.2 文件级探针（不入库，需 Android 桩）

`/var/minis/shared/work/p1028-fix/probe-envvar/`（`run.sh <label> [prod-root]`）编译**真实**的 `EnvVarRepository.kt` / `EnvVarRedactor.kt` / `EnvVarPrivacyStore.kt` / `AtomicFileWrite.kt`，桩掉 `android.content.Context` / `SharedPreferences` / `android.util.Log` / `AppLogger` / `EncryptedPrefsFactory`。12 条文件级用例：

- 三态：缺文件（空表、flag=false、无 quarantine）/ 合法（全行、flag=false）/ 截断（flag=true、quarantine 字节逐字相等、日志有 `env-var index unusable`）/ 0 字节 / 部分损坏（好行保留 + flag=true）
- 恢复：写两个变量 → 把索引截半 → 新实例 **key 名回来了、值仍可取、note 为空**（note 只在丢失的文件里）+ 日志有 `recovered 2 env-var key(s)`
- 写路径：**inode 变化 + 无 `.atomic-*.tmp` 残留**（rename 装最终名，就地写不可能换 inode）；保存后新实例往返（note/group/值都在）
- **撕裂读对照臂**：预置 2000 行 → 读者线程死循环 `readText()` + `JSONArray()` 解析 → 期间 200 次 `add()`（每次全量重写 ≈200 KB）→ 断言读者观测到的「不是完整 JSON」次数为 0
- 后果链端到端：损坏索引 + 隐私模式开 → `redactIfEnabled("export API_KEY=cf-plaint3xt-value")` **遮住**且带降级提示；**对照臂**：同样代码路径、同样输出、索引只是「空数组」→ 原样通过、hits=0（证明遮的是损坏态而不是探针里别的东西）

`52 tests, OK`（10 + 30 + 12）。

### 3.3 变异负控（复刻修复前代码形状，不是近似）

`make_mutations.py` 生成四臂，各自只替换对应的一段，跑同一套 52 个用例：

| 臂 | 变异（= 修复前形状） | 红 | 其余 |
|---|---|---|---|
| **M1** | `loadMetadata` 整段还原成修前的 `try { … } catch { Log.e }` | **6**：截断/0 字节/部分损坏/恢复/损坏后写/端到端泄漏 | 46 绿 |
| **M2** | `AtomicFileWrite.write(…)` → `metadataFile.writeText(…)` | **2**：inode 未变化、**并发读者观测到撕裂** | 50 绿 |
| **M3** | 删掉 `redactIfEnabled` 的降级分支（= 修前调用点） | **1**：端到端泄漏（明文原样返回） | 51 绿 |
| **M4** | 去掉「从加密值恢复 key 名」的赋值 | **1**：恢复用例 | 51 绿 |

要点：每臂红的都是**目标用例**，不是整套红（不是「编译不过所以全红」）；M2 的撕裂读用例**对就地写真的抓到了**（说明这个对照臂有牙，不是永远绿）。

### 3.4 本地门与夹具

- `sh scripts/scan/scan.sh --jobs 3` → **113 passed, 0 failed**
- `python3 scripts/scan/test_scan.py --single test_real_repo` → `20 0`
- 全量夹具（沙箱整跑会被 stall-guard 打断，改用逐函数独立进程循环 `probe-envvar/test_scan_loop.sh`）→ **47 组 / 329 用例全过**，加 `test_real_repo` 的 20 = **349**（与基线一致）
- 专门复核过一支会被本改动影响的臂：`skill_import_offmain_check` **arm9**（「`EnvVarRepository` 里触盘的方法在 `EnvironmentVariablesScreen` 必须离开主线程」）在原树仍 OK，`--selftest` = `SKILL_IMPORT_OFFMAIN_SELFTEST=PASS`。它的「触盘」判据是靠 `add/update/delete` 体内的 `.edit()` 派生的，不依赖 `saveMetadata` 里的 `writeText(`，所以换成原子写**没有把这条臂掏空**（这一条我特意核过：改被判文件的结构时，最容易发生的是门变松而不是变红）。

### 3.5 CI

| 项 | 读数 |
|---|---|
| run / job | `40639424995` / `122653838799`，attempt 1 |
| 结论 | **success**，05:41:09→05:58:25Z（17m16s） |
| `head_sha` | `215e10dface7b3b51783d14b5cf5dae1a9cc18c0`（与本地 HEAD 逐字符一致） |
| 步骤 | 27 步，除 2 个 main 门控 skip（rolling tag / Publish）全 success；`Run unit tests (full suite)` 与 `Build APK with Gradle` 均 success |
| 日志 | 1,492,410 B / NUL 0 |
| 门 | `Summary: 113 passed, 0 failed` ×1；`[113/113]` ×1 |
| 夹具 | `ALL 349 CASES PASS` ×1 |
| 编译错 | `^ e: ` **0** |
| 含 `FAILED` 的 6 行 | 逐条核过，**全是 PASSED 用例名子串**（如 `terminal FAILED maps to Failed PASSED`） |
| 本支新用例 | `EnvVarMetadataCodecTest` 10 条全 PASSED；`EnvVarRedactorTest` 30 条全 PASSED；既有 `EnvVarGroupRoundTripTest`(15) 全 PASSED（metadata 往返的 group/note 未被 codec 改坏） |

---

## 4. 诚实边界

1. **真机未验证**。沙箱无 Android 运行时，探针用的是内存桩。三处只在真机成立（或可能不成立）：
   - `EncryptedSharedPreferences.getAll()` 的真实行为（AndroidX 支持，但我没在设备上跑过；探针里是 `HashMap`）；
   - 损坏态下 `copyTo` 在构造期（主线程）的耗时——索引是几 KB 的 JSON，判断可忽略，但没有实测；
   - 真 SIGKILL 命中写入窗口的概率与形态——用撕裂读对照臂代替（见 3.2）。
2. **撕裂读用例有竞态**。对修复后的代码它不可能假红（rename 保证读者看到旧或新，不存在第三种），但对 M2 变异是**概率检测**：本轮 1/1 抓到，不能保证每次都抓到。它刻意只放在探针（不入库），不放进 CI 套件，避免又造一个 `SoulStoreOverwriteBackupTest` 式的 flaky。
3. **我扩了任务书的边界**（两处，都在报告里点名）：
   - 「从加密值恢复 key 名」不是任务书要求的，是我判断这场事故的核心不对称性后加的（§2③）；
   - 降级路径做成「值匹配 ∪ 形态匹配」而不是纯形态，因为恢复一部分 key 之后只用形态反而**弱于**修复前能做的值匹配。
4. **`metadataUnreadable` 在进程内是黏性的**（写不清零）。代价是：损坏那一次启动的整段生命里，日志/输出里形如 `XXX_KEY=…` 的内容都会被遮（安全方向，非功能方向）。收益是不做「假装修好了」的判断。
5. **形态兜底是启发式**：名字里不含任何 hint、也没有已知 token 前缀的密钥（例如自定义 `X-Hash: <hex>`）仍会漏。这是「不知道值是什么」的物理上限，只能靠②的恢复把已知值补回来。

---

## 5. 新登记残留（每条带触发条件与方案）

| # | 残留 | 触发条件（何时该动手） | 方案 |
|---|---|---|---|
| R1 | **UI 无降级提示**（`metadataUnreadable` 只有日志和脱敏在用；任务书禁区：不动 UI，且 `EnvVarsCollection` 由另一支独占） | 用户报「变量列表看着正常、但某些值没被遮」或「变量少了几条」 | `EnvironmentVariablesScreen` 顶部加一条横幅读 `metadataUnreadable`（现在是公开只读字段，无需改签名） |
| R2 | 恢复出的条目**丢 `note`/`group`/`createdAt`**，且**只覆盖「有值」的键**（有键无值的壳条目仍丢） | 用户报「`.corrupt` 存在但注解/分组丢了」，或报「某个空值变量不见了」 | 启动时若 `.corrupt` 存在，解析它按 key 合并回 note/group（`.corrupt` 是原样字节，可解析到坏行之前） |
| R3 | `saveMetadata` 在损坏态**不阻断**（写会把「缺键」状态固化）；缓解是 `.corrupt` 保留了原始字节 | 用户报「改名后旧变量彻底消失，`.corrupt` 也看不懂」 | 损坏态首次写前，把 `.corrupt` 里能解析的行与内存合并后再写（等价于「写前先恢复」） |
| R4 | `.corrupt` 副本**first-wins、无轮转、无清理** | 多次损坏导致最新证据被旧副本挡住，或有人报存储占用 | 带时间戳命名 + 保留最近 2 份 |
| R5 | `metadataUnreadable` **进程内黏性**（成功写不清） | 用户报「普通 `SOMETHING_KEY=…` 一直被遮、影响调试」 | 若 `.corrupt` 的所有可解析行都能在内存里找到对应 key，清标志（需要「完整」的可证判据，别用「写过一次」当判据） |
| R6 | 形态兜底**漏无 hint 名字 + 无已知前缀的密钥** | 真机出现「已知的泄漏但形态规则没命中」 | 把该形态补进 `SECRET_NAME_PARTS` / `TOKEN_PREFIXES`，并在 `EnvVarRedactorTest` 加一条（当前的规则表就是数据，加词不改逻辑） |
| R7 | 本支**未改** `ExecutionCoordinator` / `ChatPromptAndTools` 的消费面（它们读到的就是恢复后的 `entries`，实际已改善） | 需要在「索引未知」时给沙箱/ prompt 显式提示 | 属于 R1 的同类（提示面），一并做 |

---

## 6. 复现方式

```sh
# 仓库内单测（CI 同款，需 Android Gradle）：
./gradlew :app:testDebugUnitTest --tests 'com.rikkaminis.app.data.*EnvVar*'

# 文件级探针 + 变异臂（沙箱内，无需 Gradle/设备）：
cd /var/minis/shared/work/p1028-fix/probe-envvar
sh run.sh prod                     # 52 tests, OK
python3 make_mutations.py
for a in M1 M2 M3 M4; do sh run.sh $a "$PWD/mut/$a"; done   # 期望 6/2/1/1 红
```

探针目录不入库（需要 Android 桩），变异生成脚本与本次全部日志在 `probe-envvar/out/<label>/`。
