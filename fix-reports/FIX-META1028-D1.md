# FIX-META1028-D1：B07 数据层 P0 四条

分支 `fix/meta1028-d1-data-p0` · 基线 `bfb5b0e6` · 来源货架报告 B07（`meta-audit/reports/r2/Report__1__1.md` F-01..F-04）

> **本报告证据转写有损**：主会话机械核实，报告给出的行号对真树匹配率 12–52%，
> 且报告**编造过函数名**（`sha256Hex`，真码是 `hashJsonMirror`）。四条全部按语义
> 重新取证，不按行号核。下表「报告行号」一列只作溯源用。

## 1. 四条总览

| ID | 先证结论 | 修法 | 证据 |
|---|---|---|---|
| F-01 | **成立**（但成因与报告所述不同） | 把 `meta.json_sync_hash` 从「声明」改成「事实」：commit 后读回真值，不一致则修复单行 meta | 8 类断言 + 变异臂 M1/M2/M3 + 门 arm 10 复绿 |
| F-02 | **成立 ×2（缺一半）**：真码只有 `base_model_json` 有隔离，`overrides_json` / `member_entry_ids_json` 折叠即蒸发 | 冻结不可解码 blob（上报 id + persist 时回填存储原值）+ `removeInstance` 只删本次清空的组 | 变异臂 M4/M5/M6/M7 |
| F-03 | **成立**（且比报告更重：仓库 + 两处调用点**三层**吞异常） | `interruptedSessionIds()` 返回 `Set<String>?`，两个调用点用 `badgeReconcileInput` 遇 null 跳过 reconcile | 变异臂 M8 |
| F-04 | **成立**（备份路径 + 两个搜索/列表查询；`loadUserMessagesSince` 已死于重构，报告行号对不上） | 备份走 `loadMessagesTailTolerant`（逐行降级 + 计数）；`messagesUnreadable` 进 `chatTruncated`；preview 查询 `SUBSTR` 有界；search 降级为两阶段读 | 提示测试 3 条 + 门 R1 复绿 |

**未做**：F-04 的 WebDAV/本地轮转沿用处理（与 ConfigBackup 同源，超出本支半径）。

## 2. F-01 —— DB 与 JSON 镜像的仲裁令牌

### 2.1 先证

真码 `ProviderRepository.persistToDbAndMirror`（3275 行，非报告 389）：

- 先 `val mirrorHash = hashJsonMirror(mirrorStr)` —— **哈希取自「打算写入的字符串」**；
- 再 `providerDao.syncAllIncremental(...)`（包在 `runCatching` 里，这一层是 §1042-A-01 的既有修复）；
- 最后 `prefs.edit().putString("config", mirrorStr).commit()`，失败**只记日志**。

冷启动 `loadConfigSuspending` 的规则是 `liveHash != storedHash ⇒ 镜像更新 ⇒ 重新导入镜像`。

于是 `commit()` 返回 false（磁盘满 / prefs XML 损坏）时留下：

```
DB 行 = 新  ·  meta.json_sync_hash = hash(新镜像)  ·  磁盘上的镜像 = 旧字符串
```

冷启动算 `liveHash = hash(旧) != storedHash` ⇒ 判定「镜像更新」⇒ **把旧 JSON 回灌进 DB**。
用户最后一次保存的 provider / key / 模型组被永久回滚，日志还写着
`re-importing JSON mirror`（看起来像正常的降级恢复）。

**与报告的差异**：报告说根因是「写序（先 DB 后 prefs）」。写序确实是缺陷的一半，
但 `provider_import_id_roundtrip_check` **arm 10 把「mirror 写在 DB envelope 之外/之后」
当成事实钉住了**（判据原文：mirror 落在 DAO 的 `runCatching` 区域内 ⇒ "a DAO throw still
skips it"）。改写序 = 与既有门对撞，而门的立场本身是对的（DB 挂掉时 prefs 必须还能写）。
**真正可动的是哈希的取值时点**——令牌不必在 DB 写之前拍板。

### 2.2 修法

```
mirrorHash   = hashJsonMirror(mirrorStr)        // 声明：DB 写之前需要一个值
dbWrite      = runCatching { syncAllIncremental(..., meta = snapshot.meta) }   // 顺序不变
mirrorWritten = prefs.commit()                  // 顺序不变（arm 10 的要求）
truthfulHash = decideSyncHash(mirrorStr, prefs.getString("config", null))      // 事实
if (dbWrite.isSuccess && truthfulHash != mirrorHash) → upsertMeta / deleteMetaKey 修复单行
```

- `decideSyncHash(intended, live)`：哈希**读回来的值**。`live == null`（prefs 声称成功却读不出）
  时回落到 intended —— 这种 SharedPreferences 说谎的情形无法在此防御，交给冷启动的
  re-import 分支仲裁。
- 修复写是 **best-effort**（`runCatching`），失败时明确记一条 warning 点名剩余风险
  （"the next cold start may re-import the previous mirror over these rows"）。
- 新增 `ProviderConfigDao.deleteMetaKey(key)`：让「这个 key 不指向任何镜像」成为可表达的
  状态（删行而不是存空串，冷启动的 `dbHash == null` 分支天然走 re-import）。

三种结局：

| 结局 | 令牌 | 冷启动 |
|---|---|---|
| commit ok | hash(新) = 事实 | 匹配 → 读 DB（无需解码 ~800KB 镜像） |
| commit ok + DB 写失败 | hash(新)，DB 行旧 | 不匹配 → 重新导入 → **保存被恢复**（既有降级设计生效） |
| commit 被拒 | 修复为 hash(旧镜像)，DB 行旧 | 匹配 → 读 DB，**不再回滚**；保存确实丢失且被如实报告 |

### 2.3 证据

- `ProviderMirrorHashTest`（16 例）：FakeStore 模拟 prefs + DB 行 + hash，跑完整
  save → restart；`legacyPersistAndColdStart` 是**修前算法的逐字复刻**，断言它在同一
  夹具上确实回滚（1 次 re-import + 返回旧配置），新实现不回滚（0 次 re-import）。
- 变异臂 M1（哈希改回 intended）→ 3 条红，含 `aRejectedCommitDoesNotRollBackTheSave`；
  M2（prefs 读不出时回落 null）→ 1 条红；M3（仲裁反向）→ 4 条红。
- 既有 `ProviderPersistDegradedMirrorTest` 改为复刻新的日志分支（4/4 绿），
  该门 arm 10 在本树 `clean (13 arms)`。

## 3. F-02 —— 不可解码 blob 不得被写回成真相

### 3.1 先证

真码 `ProviderConfigMapping.toProviderConfigWithDiagnostics`：

- **只有 `base_model_json` 有隔离**：失败 → `quarantinedEntryRowIds.add(row.id)` +
  `return@mapNotNull null`，并由 DAO 的 `preserveEntryIds` 保住物理行。
- `overrides_json` 失败 → `.getOrNull() ?: ModelOverrides()`，**没有上报、没有保留**；
  下一次 `toSnapshot` 把它编码成 `null`（写入即永久抹掉用户的调参）。
- `member_entry_ids_json` 失败 → `mutableListOf()`，同样不上报；下一次保存写成 `"[]"`，
  **永久清空成员列表**。

第二个后果更狠：`removeInstance` 收尾扫描

```kotlin
val emptyGroupIds = config.modelGroups.filter { it.memberEntryIds.isEmpty() }.map { it.id }
```

扫的是「配置里所有空组」，不是「本次清空了哪些组」。于是 **解码失败 ⇒ 空列表 ⇒ 删组**
（连同 `defaultPrimary/SubGroupId` / voice / agent-loop 绑定一起清），而组行本身是真实
成员列表的唯一副本。

**与报告的差异**：报告说 `removeInstance` 在 `ConfigBackup` 的 import 路径删除模型组；
真码里 `removeInstance` 只被 UI 删除 provider 路径调用。危害方向一致（删组），链路不同。

### 3.2 修法

1. 两个折叠点各自 `LinkedHashSet` 上报：`unreadableOverridesEntryIds` /
   `unreadableMemberGroupIds`，随 `ProviderConfigLoad` 一起带出并落到
   `ProviderRepository` 的 `@Volatile` 字段（与 `quarantinedEntryRowIds` 同生命周期规则）。
2. `restoreFrozenBlobs(snapshot, storedEntries, storedGroups, frozenEntryIds, frozenGroupIds)`
   —— **纯函数**，在 persist 前把存储里的原 blob 逐字塞回（只在有冻结项时多一次行读）。
   行已消失（load 与 save 之间被删）时保持占位值，不凭空造 blob。
3. `removeInstance` 改用纯函数 `decideEmptiedGroupIds(groups, touchedGroupIds, unreadableGroupIds)`：
   只删**本次触碰过、现在为空、且不是解码失败占位**的组。

### 3.3 证据

`ProviderConfigFrozenBlobTest`（8 例）+ `ProviderRemoveInstanceEmptyGroupTest`（6 例，
含一条**对比臂**：修前的「filter 所有空组」在同一夹具上会删 3 个与本次删除无关的组）。

变异臂：M4（去掉回填）→ 1 红；M5（不上报 overrides）→ 2 红；M6（不上报 member）→ 2 红；
M7（退回全局空组谓词）→ 3 红。

## 4. F-03 —— 尾巴查询失败不得被当成「没有会话被打断」

真码 `ChatRepository.interruptedSessionIds()`（1419 行）：

```kotlin
val tails = runCatching { dao.lastMessageTailPerSession() }.getOrElse { emptyList() }
```

两个调用点（`MinisApp` 冷启动 / 前台恢复）又各包一层 `runCatching { … }.getOrElse { emptySet() }`。
`SessionBadgeStore.reconcileInterruptedSessions` 是**差集消费**：没被告知的 PAUSED 会话
一律摘徽标并持久化裁剪后的 map ⇒ 一次查询失败 = 全应用 PAUSED 徽标清空，且直到用户重新
打开每个会话才可能恢复。报告说「用 CursorWindow 一次读全表 `parts_json`」属实。

**修法**：`interruptedSessionIds(): Set<String>?`，失败 `return null` + 一条 warning；
新增纯函数 `badgeReconcileInput(interrupted, active)`（null 进 null 出），两个调用点
遇 null 跳过 reconcile（保留已持久化的徽标）。空集仍然是空集 —— 恢复中的会话照样摘徽标。

变异臂 M8（null 折叠成 emptySet）→ `unknownTailQueryKeepsTheBadges` 红。

## 5. F-04 —— 裸读不再静默丢弃

| 调用点 | 状态 | 处理 |
|---|---|---|
| `ConfigBackup` 打包 `messagesAt` | **裸读 + `getOrDefault(emptyList())`** | 改走 `ChatRepository.loadMessagesTailTolerant`：批量读失败 → 逐行读（`loadPageSlice`，复用既有漏斗）；读不出的行**计数**并写入 `chatTruncated.messagesUnreadable`，导入侧 `chatTruncationNotes` 增第四类提示（与「预算裁剪」「分片缺失」「不可解析行」分开） |
| `querySessionsMeta` 的 preview 子查询 | 整列 `parts_json`（每行上限 500KB，聚合无上限） | `SUBSTR(m2.parts_json, 1, 8192)`；文本部件在大的二进制部件之后时 preview 退化为缺席（可选字段），不再让整个 `list` 失败 |
| `searchMessages` | `limit*3` 整行，snippet 是载荷本身、不能截断 | 窗口失败降级为两阶段：先用 `'' AS parts_json` 取引用，再按 id 单行取；单行仍失败只损失这一条匹配 |
| `loadUserMessagesSince` | **真码里 0 个生产调用点**（只剩 6 个测试 fake 的 override），报告行号对不上 | 不动 |

## 6. 报告证据 vs 真码（差异表）

| 报告说法 | 真码 |
|---|---|
| 函数名 `sha256Hex` | `hashJsonMirror`（`ProviderRepositoryUtils`） |
| F-01 根因 = 写序 | 写序是一半；可动的是**哈希取值时点**（写序被 arm 10 钉住，且门的立场正确） |
| F-02 `removeInstance` 在 import 路径删组 | `removeInstance` 只被 UI 删除 provider 调用；链路不同，危害同 |
| F-03 归 `ChatRepository` 一处 | 实际**三层**吞异常（repository + 两个 MinisApp 调用点） |
| F-04 4 处裸查询绕 readSaf | `loadUserMessagesSince` 真码 0 调用点；真问题是 CursorWindow 而非「没有大小保护」 |
| 行号 | 全部对不上（文件行数：ProviderRepository 3275 / Mapping 422 / ChatRepository 1419 / ChatDao 500） |

## 7. 验证

- **JVM 窄编译**：`probe-d1/build.sh` —— 只编纯函数 + 真 `ProviderConfigMapping` + model 层 +
  4 个新测试类，单趟 kotlinc + `nice -n 10`（19 会话并行，禁 gradle）。`OK (33 tests)`。
- **变异 8/8**：每条臂只红钉它对应的用例，基线复跑 33/33 绿，无 `.armbak` 残留。
- **形状断言 16/16**（剥离行注释后判，避免注释里的修前形态误判）。
- **门**：`provider_import_id_roundtrip_check` clean (13 arms)、`parts_json_funnel_check`
  单调用点计数恢复 1/1/4（**中途踩坑**：降级路径直接调 `dao.loadMessagesPage` 触发 R1，
  改走 `loadPageSlice`）、`model_entry_override_parity_check` OK (7 arms)、
  `entry_composite_key_guard_check` OK (9 arms)、`dao_loadmessages_check` clean。
- **CI**：见 §8。

## 8. 残留（登记，未修）

| # | 内容 | 触发条件 |
|---|---|---|
| R1 | F-01 的修复写是 best-effort：Room 也不可用时令牌保持「声明」，冷启动仍可能回滚（已记 warning） | 修 = 把 mirror commit 挪到 DB 之前，需先与 `provider_import_id_roundtrip_check` arm 10 的立场裁决 |
| R2 | F-02 冻结期间用户对该 entry 的 overrides 编辑**不会被保存**（占位值被原 blob 覆盖） | 需要「用户显式改动过」标记才能区分 |
| R3 | F-03 徽标在查询失败期间保持陈旧值，无 UI 提示 | 需要产品决定「陈旧」是否可见 |
| R4 | `searchMessages` 降级路径多一轮查询（失败时才走） | 性能影响仅在窗口失败时出现 |
| R5 | F-04 WebDAV / 本地轮转未统一走有界读 | 与 ConfigBackup 同源，超出本支半径 |
