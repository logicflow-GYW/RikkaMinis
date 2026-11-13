# FIX-R8-A — 配置写入 / 会话索引 / 路由 / 守卫（7 条）

## 1 覆盖 ID

| 项 | 值 |
|---|---|
| 来源报告 | `/var/minis/shared/work/meta-audit/reports/r8-gap1113/A-data-config.md` |
| 本支覆盖 | **A-F02 · A-F03 · A-F04 · A-F05 · A-F06 · A-F10 · A-F01**（7/7 全部动手，0 条证伪、0 条登记不修） |
| worktree | `/var/minis/shared/work/r8-1114/a-config-guard` |
| 分支 | `fix/r8-1114-a-config-guard` |
| 基线 | `c0391a2`（main：`Merge integration/incr1113`） |
| 提交序列 | `addf188` A-F02 · `c12afa8` A-F03 · `c7d3636` A-F04 · `5f91d2a` A-F05 · `aaa3992` A-F06 · `ad1bdcc` A-F10 · `2d492f6` A-F01 · `5fdb602` A-F05 测试补强 · `87a17d1` A-F02 注入面收口 · `3a03ed9` A-F02 同族（group 写路径） |
| 分支尖端 | `3a03ed9f8b3434e0fe22db9ac25e55db4ee0b7d3` |
| 改动量 | 19 文件 **+847 / −54**（生产 10 · 测试 8 · 门 1） |
| 禁碰项 | `scan.sh` / `scan_blocks.sh` / `test_scan.py` **零触碰**；B/C 支独占的 10 个生产文件 `git diff --name-only origin/main...HEAD` **零命中**；`scripts/scan/**` 只改 `auto_approve_bypass_check.py` |

## 2 逐条先证结论

### A-F02 · P1 · 改备注把 API Key 覆写成空串

**先证：成立，两个 null 来源都在基线树上确认。**

- 基线 `EnvVarsCollection.kt:114`：`val currentValue = repo.getValue(e.key) ?: ""`，紧接 `:119` `repo.update(e.id, e.key, currentValue, s, e.group)`。
- `EnvVarRepository.update`（基线 `:149`）无条件 `encryptedPrefs.edit().putString(normalizedKey, sanitizeValue(newValue)).apply()` ⇒ 传入 `""` 就是写入 `""`。
- null 来源 1（有元数据无值）：`getValue(key) = encryptedPrefs.getString(key, null)`（基线 `:181`），元数据文件与加密值是两个独立存储，`add()` 之外的任何路径都可能只有前者。
- null 来源 2（keystore 降级）：`EncryptedPrefsFactory:143` 在重建失败时返回 `InMemorySharedPreferences()`（空 store，`degraded = true`）⇒ 所有 `getString` 返回 null。`EnvVarRepository.kt:76-84` 的 KDoc 已写明 `metadataUnreadable` 场景下"不知道"≠"没有"，但写路径仍把"不知道"当空串写回。
- 用户可观察差异：在"密钥缺失"的条目上改备注，值从"缺失"变成 `FOO=""`，随后 `allAsDict()` 把它注入沙箱环境（空字符串 ≠ 未设置）。

### A-F03 · P1 · 切会话后 @ 文件列表串台

**先证：成立（两半都在）。**

- 基线 `:105` `if (sameSession && fresh && _entries.value.isNotEmpty()) return` —— 只有"同会话 + 未过期 + 非空"才早退，会话变化时走完整扫描，但**全程没有一处清 `_entries`**（`git grep -n "_entries.value"` 在基线只有 `publish` 的赋值一处）。
- `publish` 分三层（session / global / 每个 mount）各自发布，所以旧会话的条目会与新会话的前几层混在同一张表里，直到最后一层覆盖。
- 基线 `:193` `} finally { _isScanning.value = false }` —— 不看 token；`refreshIfNeeded` 每次都 `currentJob?.cancel()`，被取消的旧扫描的 `finally` 仍会执行，可能把新扫描刚设的 `true` 清成 `false`。
- 用户可观察差异：切会话后立刻打 `@`，菜单里出现上一个会话的 `/var/minis/workspace/xxx`（点进去路径不存在）。

### A-F04 · P1 · 分组路由绕过策略 / fallback 漏 index 0

**先证：成立（两半独立成立）。**

- 基线 `:71-72` `preferredEntryId != null -> usable.firstOrNull { it.id == preferredEntryId }?.id ?: usable.first().id` —— 首选不可用时直接 `usable.first()`，`when` 后续分支（cheapestFirst / loadBalance）**不可达**。
- 基线 `:175` `for (offset in 1 until members.size)`，`:176` `val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset` —— `currentIdx < 0` 时 `idx` 取值 `1..n-1`，`members[0]` **永远进不了** fallback 链。
- 用户可观察差异：①绑定的成员 401 之后，请求落到"列表里第一个"而不是"最便宜的"（cheapestFirst 组的费用与预期不符）；②首成员是唯一可用成员时，fallback 链为空 ⇒ 一次失败直接结束，不重试。

### A-F05 · P1 · 读屏命令被判成写 + 告警水位只增不减

**先证：成立（两半都在，且第二半的后果比报告描述的更具体）。**

- 基线 `ToolLoopScope.kt:141` `read = false,`（`shell_execute` 分支常量）—— 而 `ActionGuard.signature()` 对非 read 工具返回签名、对 read 工具返回 null（`ActionGuard.kt:215` `if (isRead(tool)) return null`）⇒ `android-a11y-cli ui dump` 连打 12 次会在第 12 次被 `check()` 拒绝（`LoopGuard.kt:90`）。同文件 KDoc 写"repeating `git log` is not a loop"，但 `git log` 根本不在 managed 集合里，真正被误伤的是 a11y 读命令。
- 基线 `LoopGuard.kt:105` `if (rung <= (warned[signal] ?: 0)) return` —— `warned` 只在 `reset()` 和 `Outcome.Effective`（NoEffect/Reread）时清，Repeat 没有任何下调路径。
- 具体后果（比报告更明确）：Repeat 的计数是**滑动窗口**（`countInWindow` 只数最近 `COUNT_WINDOW=16` 条），所以它会从 12 掉回 3。水位停在 12 之后，**每一次后续爬升都静默**，模型只在再次到达 12 时被硬停 —— 这正是用户说的"突然被拦"。
- 附带发现（未在报告里）：`warned` 是**按 signal** 而不是按 signature，所以目标 A 的水位会吞掉目标 B 的第一次提醒。

### A-F06 · P2→修 · `decideSyncHash` 与 KDoc 相悖

**先证：成立。**

- 基线 `ProviderRepositoryUtils.kt:122` `liveMirror == null -> hashJsonMirror(intendedMirror)`，而同函数 KDoc 第 108 行写 "return the hash to store, **or null when there is no mirror to name**"。
- 后果：`ProviderRepository.kt:697-700` 的 `if (truthfulHash == null) deleteMetaKey(...)` 分支**不可达**（`git grep` 全仓只有这一处 null 判定）。
- 语义后果：一个 commit 完全没落盘的保存，DB 里仍存着"命名了某个镜像"的哈希；下次冷启动 `decideConfigSource` 比较 `liveHash == stored` 时，两侧都可能为 null/不等，落进 re-import 分支。

### A-F10 · P2（安全）· 主开关关闭时 privileged 仍 BYPASS

**先证：成立。**

- 基线 `OffloadPermissionLevelPolicy.kt:40` `if (defaultLevel == NOT_ALLOWED && isPrivilegedTool && privilegedToolsEnabled)` —— 无 `autoApproveEnabled`。
- 同函数 `:36` 的 ASK_ONCE 分支有 `autoApproveEnabled` ⇒ 同层两个子开关规则不一致。
- 用户可观察差异：设置里关掉"自动批准"后，`a11y_cli`（读屏、驱动别的 app）与 `shizuku_cli`（特权 shell）仍被静默放行 —— 用户以为自己关掉了。

### A-F01 · P2 · app_guide 把 YAML frontmatter 喂给模型

**先证：成立。**

- 基线 `AppGuideTool.kt:70` `val body = readPackBody(context.assets, entry.id)`（读**原文**），`:79` `renderAppKnowledge(entry, body)`；`AppKnowledge.kt:213` `appendLine(body.trim())`。
- 同文件 `buildIndex`（`:100`）走 `parseAppKnowledgePack(id, text)`，`doc.body` 已剥 frontmatter（`AppKnowledge.kt:120` `body = doc.body`）。
- 两条路不一致：索引路径干净，命中路径把 `---` / `name:` / `aliases:` / `packages: [...]` 一起发给模型。

## 3 逐条修法

| ID | 生产改动 | 测试 | 门 |
|---|---|---|---|
| A-F02 | `EnvVarRepository` 新增 metadata-only `updateNote(id, note, group)`（只写 `env-vars.json`，不碰加密值）；`EnvVarsCollection` 的 note/group 两个 writer 都改成"读到值走 `update`，读不到走 `updateNote`" | `EnvVarCollectionNoteWriteTest`（新，2 例：note + group） | 邻门 `envvar_config_group_check` 16 判据绿 |
| A-F03 | `refreshIfNeeded` 在 `!sameSession` 时同步清空 `_entries`；`scan` 的 `finally` 加 `if (currentScanToken == token)` | `FileMentionIndexSessionSwitchTest`（新，1 例） | — |
| A-F04 | `select()` 的 preferred 命中即返回，否则**落到策略 `when`**；`fallbackOrder` 的 `firstOffset = if (currentIdx >= 0) 1 else 0` | `GroupRouterTest`（扩 2 例 + 改写 1 例） | 邻门 `router_lock_check` |
| A-F05 | `SHELL_READ_PREFIXES` + token 归一化匹配 → `read = managed && isReadOnlyShellCommand(cmd)`；`LoopGuard` 新增按 signature 的 `repeatWarned`，`climbRepeat` 允许水位下调并在计数跌出窗口时移除 | `ToolLoopScopeReadTest`（新，5 例）+ `LoopGuardRungResetTest`（新，6 例） | — |
| A-F06 | `liveMirror == null -> null`，KDoc 与实现一致 | `ProviderSyncHashContractTest`（新，4 例）+ 改写 `ProviderMirrorHashTest` 1 例 | 邻门 `provider_import_id_roundtrip_check` |
| A-F10 | privileged 分支加 `autoApproveEnabled &&` | `OffloadPermissionLevelPolicyTest`（扩 1 例 + 改写既有臂） | **本门 C8** |
| A-F01 | 命中路径改走 `parseAppKnowledgePack(id, raw).body`，未在 renderer 里加第二套剥离 | `AppKnowledgeTest`（扩 2 例，含源码形状臂 + 反向对照） | — |

### A-F10 门扩展（C8）

新增 `check_privileged_overlay(policy_code)`：`resolve()` 内每一条**活着的** `if` 只要读 `privilegedToolsEnabled`，就必须同时读 `autoApproveEnabled` 与 `isPrivilegedTool`；`resolve()` 里一条都没有 ⇒ fail-closed（静默删除 privileged 放行与"被绕过"不可区分）。

新增 2 条变异臂：`privileged_overlay_ignores_master`（删掉 `autoApproveEnabled &&`，即修复前形态）、`privileged_overlay_removed`（整段删掉）。自测臂数 20 → **22**，违规臂 18 → **20**。

**规格变更（报告要求写明）**：`OffloadPermissionLevelPolicyTest.privilegedLiftsToBypassOnlyWithItsOwnFlag` 原来断言 `(null, NOT_ALLOWED, privileged=true, master=false, sub=true) == BYPASS` —— **那条断言就是缺陷本身**。现改为 master=true，并新增 `privilegedFlagDoesNotOverrideAMasterSwitchThatIsOff` 钉住正确语义（master=false + sub=true ⇒ NOT_ALLOWED）。

## 4 证据（命令 + 实际输出）

### 4.1 本门（真树 + selftest）

```
$ python3 scripts/scan/auto_approve_bypass_check.py .
  [note] C7: 1 factory registration(s) carry their `path` from the caller …
AUTO_APPROVE_BYPASS_CHECK: OK (all 8 criteria hold)          rc=0

$ python3 scripts/scan/auto_approve_bypass_check.py --selftest
AUTO_APPROVE_BYPASS_SELFTEST privileged_overlay_ignores_master arm: OK (1 violation, red as designed)
AUTO_APPROVE_BYPASS_SELFTEST privileged_overlay_removed arm: OK (1 violation, red as designed)
AUTO_APPROVE_BYPASS_SELFTEST: PASS (22 arms, 20 violating)    rc=0
```

**C8 的反向对照（修复前判红）**：把基线 `c0391a2` 检出到独立工作树，用**新**门跑：

```
$ cd /tmp/r8a-base && python3 <本支>/scripts/scan/auto_approve_bypass_check.py .
AUTO_APPROVE_BYPASS_CHECK: RED
  - C8: the privileged overlay condition `defaultLevel == PermissionLevel.NOT_ALLOWED &&
    isPrivilegedTool && privilegedToolsEnabled` does not consult autoApproveEnabled — the
    master switch OFF still lifts a privileged tool to BYPASS (a11y_cli / shizuku_cli drive
    other apps and read the screen)
rc_on_baseline=1
```

只命中 C8 一条 ⇒ 门精确指向本批修的缺陷，不是泛红。

### 4.2 邻门（真树 + selftest，按 `test_scan.py` 的夹具口径）

| 门 | 真树 | selftest 标记 |
|---|---|---|
| `envvar_config_group_check` | `OK (16 criteria green)` | `ENVVAR_GROUP_SELFTEST=PASS` |
| `deeplink_settings_routes_check` | `DEEPLINK_SETTINGS_ROUTES_CHECK=PASS` | `DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS (51 arms)` |
| `router_lock_check` | `Summary: 16 passed, 0 failed` / `GATE=GREEN router_lock` | `ROUTER_LOCK_SELFTEST=PASS (27 fixtures)` |
| `provider_import_id_roundtrip_check` | `clean (13 arms)` | `PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=PASS` |

另：`test_scan.py` 里 `auto_approve_bypass_check` 自己的两个夹具用例（`selftest has teeth` + `real tree green`）单独跑过，`PASS 2 FAIL 0`。

### 4.3 JVM（8 个测试文件，全 PASSED）

装置：`/var/minis/shared/work/r8-1114/probe-a/`（真源码切片 + `javastub/` 的 `android.content.Context` / `ContextWrapper` / `SharedPreferences` / `android.util.Log` + Kotlin 桩 `AppLogger` / `EncryptedPrefsFactory` / `@Stable` / `OffloadPermissionManager.PermissionLevel`），kotlinc 2.1.0 + JUnit 4.13.2。

| 测试文件 | 结果 |
|---|---|
| `EnvVarCollectionNoteWriteTest`（新） | `OK (2 tests)` |
| `FileMentionIndexSessionSwitchTest`（新） | `OK (1 test)` |
| `ProviderSyncHashContractTest`（新） | `OK (4 tests)` |
| `GroupRouterTest`（扩） | `OK (47 tests)` |
| `ToolLoopScopeReadTest`（新） | `OK (5 tests)` |
| `LoopGuardRungResetTest`（新） | `OK (6 tests)` |
| `OffloadPermissionLevelPolicyTest`（扩） | `OK (7 tests)` |
| `AppKnowledgeTest`（扩） | `OK (10 tests)` |

**合计 82 例全绿。** 另跑既有相关套件做回归：`ToolLoopScopeTest` / `ToolLoopScopeReadSetTest` / `ToolLoopScopeWiringTest` / `FileMentionIndexSessionRootTest` / `GroupRouterConcurrencyTest` / `ProviderMirrorHashTest` + 上表 5 个 → **`OK (102 tests)`**。

### 4.4 变异负控（10 条，逐条变红）

装置 `probe-a/mutate.sh`：把修复前的形态写回真源码 → 跑对应探针 → 期望红 → 字节级还原（内存快照 `cp`，不用 `git checkout --`），前后各打印一次 `git status --porcelain`。

```
---- MUTATION f02-note-write-restores-empty-secret        RED  (EnvVarCollectionNoteWriteTest)
---- MUTATION f02-group-write-restores-empty-secret       RED  (EnvVarCollectionNoteWriteTest)
---- MUTATION f03-no-session-clear                        RED  (FileMentionIndexSessionSwitchTest)
---- MUTATION f04-preferred-short-circuits-strategy       RED  (GroupRouterTest)
---- MUTATION f04-fallback-starts-at-one                  RED  (GroupRouterTest ×2)
---- MUTATION f05-shell-reads-are-writes                  RED  (ToolLoopScopeReadTest ×2)
---- MUTATION f05-repeat-watermark-monotonic              RED  (LoopGuardRungResetTest)
---- MUTATION f06-null-mirror-hashes-intended             RED  (ProviderSyncHashContractTest + ProviderMirrorHashTest)
---- MUTATION f10-privileged-ignores-master               RED  (OffloadPermissionLevelPolicyTest ×2)
---- MUTATION f01-hit-path-uses-raw-text                  RED  (AppKnowledgeTest)
(worktree restored — git status --porcelain empty)
```

### 4.5 本轮最有价值的一条：变异抓到了测试自己的洞

第一版 `LoopGuardRungResetTest` 两条臂都只走**完全逐出**路径（计数 → 0），而 `f05-repeat-watermark-monotonic`（删掉 `repeatWarned[sig] = rung` 这条"部分回落"分支）**仍然全绿**。

补的臂让计数从 6 降到 **3**（签名仍在窗口内，走的是被删的那条分支）再爬回，断言提醒必须落在 rung 6。删掉该分支后的实际输出：

```
java.lang.AssertionError: the reminder must land on rung 6, not only at the hard stop
    expected:<6> but was:<12>
```

`12` 就是硬停 —— 正是用户报告的"突然被拦"，测试现在复现了它，而不只是描述它。

## 5 禁碰项核对

```
$ git diff --name-only origin/main...HEAD | grep -E "scan\.sh|scan_blocks\.sh|test_scan\.py|<B/C 支 10 个文件>"
no forbidden files touched
```

- `scripts/scan/**` 只改 `auto_approve_bypass_check.py`（+89 行）。
- 生产文件改动集 = 本支独占的 7 个 + `EnvVarRepository.kt`（A-F02 的 metadata-only 入口所在，属本支文件族，DISPATCH 的"独占"列未列它是因为原计划不含该文件；改动仅新增一个方法 + 私有构造 + `internal` 测试工厂，无跨支冲突面）。
- 工作树 `git status --porcelain` 干净；`git grep '<<<<<<<'` 在文本文件中零命中（只匹配到三个二进制资源）。

## 6 遗留（登记，不在本支动手）

| # | 项 | 判级 | 理由 / 触发条件 |
|---|---|---|---|
| R1 | **`ConfigBackup` 恢复路径同族**：`ConfigBackup.kt:1766-1770` 的 `envVarRepo.update(..., newValue = plan.value ?: localValue.orEmpty(), ...)` 在 `localValue == null` 且备份不含值（branch 3）时同样写空串 | P2 | 触发条件：一次不含密钥的备份（`includeSecrets=false`）恢复到一个"有元数据无值"的条目上。不在本支文件集（`backup/` 归别处），且要改它得先决定"恢复时值缺失"的语义（跳过？保留？），属独立决策。**触发后方案**：把该处改成 `if (plan.value == null && localValue == null) envVarRepo.updateNote(...) else update(...)` |
| R2 | **`android-shizuku-cli` 的只读子命令未纳入 `SHELL_READ_PREFIXES`** | P2 | 现表只覆盖 a11y。`shizuku-cli package list` / `status` / `display list` / `settings list` 等确为只读，但它们与 `exec`（任意特权命令）同处一个工具名，逐子命令列白名单的误判代价高于收益（漏列只是继续按写侧计数 = 现状，多列错一个就是绕过守卫）。**触发条件**：日志里出现 `shizuku-cli package list` 触发 Repeat 提醒/拒绝。 |
| R3 | **`warned` 的 `Failure` / `NoEffect` / `Reread` 仍按 signal 而非 signature** | P3 | 三者是**连续**计数（有明确的 reset 事件），不会像 Repeat 那样无 reset 地下滑，单调水位在该语义下是对的。刻意不改。 |
| R4 | **`SHELL_READ_PREFIXES` 用字符串前缀而非子命令表** | P3 | 与 `SHELL_GUARDED_PREFIXES` 同风格；a11y 的权威子命令表在 `AccessibilityOffloadHandler`（Android 依赖），从 guard 层引用它会把 `guard/` 拖进 Android 依赖。**升级触发**：a11y 新增只读子命令且有人报告被误判。 |
| R5 | **`FileMentionIndex` 会话切换清空是同步的 `_entries.value = emptyList()`** | P3 | 在调用线程（UI）写 StateFlow 是既有 `publish` 的写法（`withContext(Dispatchers.Main)`），此处是同步路径不切线程；已由测试覆盖"切会话瞬间旧条目消失"。 |

## 7 收尾自检

1. **停在第几级**：全部停在第 7 级（最小实现），只有 A-F02 例外——它停在"新增一个方法"而不是"给 `update` 加 nullable 参数"，因为后者会波及 4 个调用点（含 `ConfigBackup` 与 `EnvironmentVariablesScreen`），正是任务书禁止的方向。
2. **永不砍清单**：输入校验（F05 的 token 归一化 + 写侧保留）、安全（F02 的密钥不被空串覆盖 / F10 的主开关门控 / `withPrefs` 只 `internal`）、跨层完整性（F02 两个 writer 都改，见 R1 的登记）、终止路径（F03 的 token 守卫让取消路径不再误清状态）—— 均未砍。
3. **根因 vs 症状**：F02 修在仓库层（`updateNote`），不是给每个 writer 各补一次；F05 修在 `LoopGuard` 的通用 `climb` 路径（新增 `climbRepeat`），不是给 a11y 特判。
4. **四处同步**：本批无数据类字段新增，不适用。
5. **`ponytail:` 注释**：本批无刻意简化处（R2/R4 的"不列 shizuku"与"用字符串前缀"已在第 6 节登记并带触发条件，等价于债务尾巴）。
6. **diff 行数**：+847 / −54（生产 +143/−54，测试 +615，门 +89）。生产侧改动远小于测试与门，符合"装置比例 ∝ 影响面"。

## 8 真机未验证项（统一窗口）

- F02：需要真机上存在"有元数据无值"或 keystore 降级的条目才能端到端观察；JVM 已用 `FakeSharedPreferences` 精确复现两种 null 来源。
- F03：@ 菜单串台是 UI 行为，JVM 验证的是索引层的表内容；真机需要切会话后立刻打 `@`。
- F05：a11y 读命令的告警/熔断在真机上才走完整链路（`OffloadGate` → handler → guard），JVM 验证的是 guard 层的判定与梯子。
- F10：需在设置里关掉自动批准后尝试 `android-a11y-cli ui dump`（应提示权限而非直接执行）。

以上均属"沙箱无判据"的 Compose / Android 交互面，建议合并后随整批装包统一验一次。
