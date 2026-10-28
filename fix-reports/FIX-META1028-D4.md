# FIX-META1028-D4 — 设置屏 P0 四条（F-01..F-04）

- 分支：`fix/meta1028-d4-settings-p0`（工作树 `/var/minis/shared/work/meta-audit-1028/wt-d4-settings-p0`）
- 基线：main `bfb5b0e6`
- 来源：货架报告 B06（`meta-audit/reports/r2/Report__3__1.md`）F-01..F-04
- 改动面：13 个既有文件（+397/−68）+ 5 个新文件（1 生产 / 4 测试）；`scripts/scan/**`、`scan.sh`、`test_scan.py` **零触碰**

## 0. 回报（任务书 §5 格式：先证 → 修法 → 证据；CI run id）

- **F-01** 先证：`previewJson` 在组合体实参位调用，内部两次 `setCustomRules("__preview__", …)`、`_customRulesRevision += 2`/帧、父屏订阅重启 Room 查询——全实锤（修正如：无 `finally`，异常路径会永久留幽灵规则）。修法：resolver 新增只读入口 `applyDraftRules`，预览改走它，缓存/revision 零接触，生产 `apply` 零变化。证据：`PreviewProbeTest` 8 例真 resolver 对拍（渲染等值 + revision 增量 2→0 + 抛异常后无幽灵规则）+ 6 例形状 + 22 例既有 `ThinkingRuleEditorPreserveTest` 全绿。
- **F-02** 先证：WebDAV/快照/SAF 三处「先建载荷后抢门」成立；`.minisbak` Plain 先放门再取门成立；**报告两处证伪/补全**——「已写却被报 busy」实为任何写之前 return（浪费的是下载字节）；原代码拒绝持门的原因是自碰撞（文件内有 `[refactor-backup-gate]` 注释），修法必须带交接。修法：唯一占用点 `claimRestorePayload()` + `preClaimed` 交接 + 四入口「取门→建载荷→同一 Main 帧交接」+ 快照行 onClick 门禁 + `remoteLoading` 兼两用一并收敛。证据：`RestorePayloadBusyGateTest` 10 例 + 半径断言（true 7→8、false 11→11，每处注释写明）+ 4 变异臂全红。
- **F-03** 先证：`canSave` 不看加载态 + `update()` 第 132 行无条件写空串覆盖真密钥——成立。修法：`secretLoad` 三态（PENDING/LOADED/UNREADABLE）+ `canSaveWithSecretField` 门 + 点击回调 fail-closed + 不可读显式提示。证据：`SettingsWriteGatesTest` 11 例真值表 + `SettingsSecretWiringTest` 8 例 + 2 变异臂全红；键入值被落地真值覆盖（不丢数据，[da1028-C] 刻意 key 设计）登记 R4 不动。
- **F-04** 先证：Save/Authorize **两条**写路径同缺陷（报告只提 Save，已补全）；`ifBlank { null }` 映射成「删除」成立；报告问 da1023 FIX-D `reconcileForServer` 是否已覆盖——**证伪**（它读存储当前值，继承表单写坏的值，不是守卫）。修法：`oauthSecretEdited` 意图位 + `decideClientSecretWrite` 三向决策（LEAVE_UNTOUCHED/SET/CLEAR），两按钮共用并加就绪门。证据：`SettingsSecretWiringTest` 8 例 + 2 变异臂全红 + 15 道相关门改前改后全绿。
- **CI run id**：`40702497006`（第二轮；第一轮 `40699319582` 失败于既有半径断言，见文末 CI 段）。

---

## 1. 结论摘要

四条全部**先证成立**（含三处证伪与两处修正，见 §7），四条全部修复，四条各有独立测试装置：

| ID | 缺陷（一句话） | 修法 | 装置 |
|---|---|---|---|
| F-01 | 规则编辑器的实时预览借用「发布」通道：`previewJson` 在**组合体**里调 `setCustomRules("__preview__", …)` 两次/帧 ⇒ 进程级缓存被写 + `customRulesRevision` 每帧 +2 ⇒ 父屏 Room 查询风暴 | resolver 新增**只读求值入口** `applyDraftRules(body, ctx, draftRules)`；预览改走它，缓存与 revision 零接触 | `PreviewProbeTest`（8 例，真 resolver 编译运行，修前/修后对拍）+ `ThinkingRulePreviewNoPublishTest`（6 例形状） |
| F-02 | 恢复入口「先建载荷、后抢门」：WebDAV 下载、快照 `readText()`、SAF 解码三处，70MB 级窗口无互斥 | 抽出唯一占用点 `claimRestorePayload()` + `preClaimed` 交接；四个入口全部「取门 → 建载荷 → 同一 Main 帧交接」 | `RestorePayloadBusyGateTest`（10 例形状）+ `SettingsWriteGatesTest`（`canStartRestorePayload` 真值表） |
| F-03 | 环境变量编辑弹窗在 Keystore 异步读期间允许保存 ⇒ `update()` 无条件写空串覆盖已存密钥 | 三态加载（PENDING/LOADED/UNREADABLE）+ `canSaveWithSecretField` 门 + 点击回调 fail-closed + 不可读时显式提示 | `SettingsWriteGatesTest`（11 例）+ `SettingsSecretWiringTest`（8 例） |
| F-04 | MCP OAuth client secret 同形态；且 `ifBlank { null }` 把「未加载的空」映射成「删除」；Save 与 Authorize **两条**写路径都有 | 三态加载 + `editedByUser` 意图位 + `decideClientSecretWrite` 三值决策（LEAVE_UNTOUCHED/SET/CLEAR）；两个按钮都加就绪门 | 同上 |

本地读数：**新测试 35 例全绿**；既有邻居 45 例（含 `ThinkingRuleEditorPreserveTest` 22 例）全绿；**11 个变异臂 11/11 变红**；15 道相关门全绿（改前改后各跑一轮，基线同为绿）。

---

## 2. F-01 先证

**报告声称**：`previewJson(buildRule())` 在组合体内求值；内部两次 `setCustomRules("__preview__", …)`；`_customRulesRevision += 2`/帧；父屏订阅该流 ⇒ 每帧重组 + Room 查询风暴。

**实码核对（全部成立，两处修正）**：

1. `ThinkingRuleEditor.kt:287` `text = previewJson(buildRule())` —— **确在组合体实参位**，每次重组都求值。
2. `previewJson` 内 `setCustomRules(previewInstance, listOf(rule))` + `setCustomRules(previewInstance, emptyList())` —— **两次写缓存**，`setCustomRules` 每次都 `_customRulesRevision.value += 1`（`ThinkingRuleResolver.kt:170/177`）。
3. `ThinkingRulesSection.kt` 订阅 `customRulesRevision.collectAsState()` 作为 `LaunchedEffect(instance.id, reloadKey, rulesRevision)` 的 key ⇒ 每帧重启 + Room 查询。
4. **修正 A**：报告说清理在 `finally`；实码**没有 `finally`**，清理语句就在 `apply` 之后的 try 体内 ⇒ `apply` 抛异常时 `__preview__` 会**永久留在缓存里**（原缺陷比报告描述多一条泄漏路径）。
5. **修正 B**：报告建议 `remember`/`derivedStateOf` 方案；本轮**只做只读求值入口**（任务书优先后者）。理由：`remember` 方案要给 11 个字段逐个列 key，漏一个就是「预览不刷新」的静默缺陷（本仓已有多次 key 漂移教训），而根因是「预览借用了发布通道」，去掉通道即可，per-frame 重算本身是廉价纯函数。

## 3. F-01 修法

`ThinkingRuleResolver`：

```kotlin
fun applyDraftRules(body, ctx, draftRules: List<ThinkingRule>): ThinkingResolveTrace =
    applyResolved(body, ctx, draftRules)          // 只读：不碰 customRulesCache / revision

fun apply(body, ctx) = applyResolved(body, ctx, customRulesFor(ctx.instanceId))  // 生产路径不变

private fun applyResolved(body, ctx, customRules) { … resolveWinner(ctx, customRules) … }
private fun resolveWinner(ctx, customRules) = (customRules + builtInRules(ctx)).firstOrNull { … }
```

- `apply()` 签名与行为**零变化**（22 例 `ThinkingRuleEditorPreserveTest` 里 4 例直接跑真解析，全绿）。
- **revision 语义未改**：`setCustomRules` 仍每次 `+= 1`，父屏刷新照旧（`publishingStillBumpsTheRevision` 钉住，且判据落在**函数体内**——四站自增里的任何一处被删都要红）。
- `ThinkingRuleEditor.previewJson` 改调 `applyDraftRules`，缓存与 revision 零接触；抛异常路径也再无残留。

**证据（修前/修后对拍，`probe-d4/src/PreviewProbe.kt` 逐字转录两种形态，编译真 resolver）**：

| 读数 | 修前 | 修后 |
|---|---|---|
| 10 个 fixture 渲染 JSON | 完全一致 | 完全一致（等值证明） |
| revision 增量/次 | **2** | **0**（连跑 10 次仍 0） |
| 抛异常后 `customRulesFor("__preview__")` | **1 条幽灵规则** | 0（从不发布） |
| 真实例 `inst-A` 的规则 | — | 预览不扰动 |

## 4. F-02 先证

报告声称四点，核对结果：

| 声称 | 实码 | 判定 |
|---|---|---|
| WebDAV 恢复下载窗口未持 `operationBusy` | `onRestore` 里 `if (!operationBusy)` 只是**点击时**判断，下载期间不持门；文件里还留着 `[refactor-backup-gate]` 注释**明确拒绝**持门 | **成立**，但报告漏了原因（自碰撞） |
| 本地快照恢复同形态 | `snapshotRestoreTarget` 弹窗 `file.readText()`（数十 MB）后调 `restoreWithSnapshot`；两处快照行 `onClick` **完全没有门禁** | **成立** |
| 并发可触发第二个恢复 | `remoteLoading` **一个布尔兼两用**（列表刷新 + 恢复下载），`openRemoteList` 的 `finally` 无条件清零 ⇒ 下载中重开面板即可再点恢复 | **成立**（新发现，报告未提） |
| 「恢复在落盘前被 `backup_err_busy` 吞掉（用户看到失败但实际已写）」 | `restoreWithSnapshot` 的 busy 分支是 `errorMessage + return`，**在任何写之前** | **证伪**：什么都没写；被浪费的是**已下载的字节** |
| `.minisbak` Plain 分支先 `operationBusy = false` 再调 restore | 真码如此（释放→重新检查→重新取，中间有真实窗口） | 成立（顺路修） |

## 5. F-02 修法

- 新增唯一占用点 `claimRestorePayload()`（走自由函数 `canStartRestorePayload(operationBusy, minisbakConverting)`）+ 唯一释放点 `releaseRestorePayload()`。
- `restoreWithSnapshot` 增加 `preClaimed`：已持门者交接进来时跳过重复检查（**消除自碰撞**，这正是原注释拒绝持门的原因），`finally` 释放逻辑不变。
- 四个入口全部改为「取门 → 建载荷 → **同一 Main 帧**交接（`handedOff` 标记）→ 失败/未交接才释放」：
  1. 手动 WebDAV 恢复（`WebDavSync.restore`）
  2. 自动备份 WebDAV 恢复（`WebDavSync.restoreAuto`）
  3. 快照确认弹窗（`file.readText()`）与「一键回滚」（`snap.readText()`）
  4. SAF 导入（`String(bytes, …)` 解码；解码失败释放门再抛，避免门卡死）
- 两处快照行 `onClick` 补 `if (operationBusy || minisbakConverting) null else …`（与兄弟行一致）。
- `.minisbak` Plain 分支改为交接（`restoreWithSnapshot(sniff.json, true)`），不再「先放门再取门」。

**收益**：恢复窗口内不可能再起第二个载荷；下载完成即必然被接受（不再出现「付了流量、回一句 busy」）；释放侧站点数**净增 0**（helper +1，Plain 分支 −1），见 `BackupFetchBusyGateTest` 的半径断言（true 7→8，false 11→11，注释写清每一处）。

## 6. F-03 / F-04 先证 + 修法

### F-03（环境变量编辑弹窗）

- `produceState` 在 IO 读 `envVarRepository.getValue(entry.key)`，`initialValue = prefillValue`（编辑态为 `""`）。
- `canSave = isValid && !isDuplicate && keyText.isNotBlank()` —— **不看加载态**。
- `EnvVarRepository.update()` 第 132 行 `encryptedPrefs.putString(newKey, sanitizeValue(newValue))` **无条件写** ⇒ 空串覆盖真密钥，条目还在、值已空，下游全部鉴权失败。
- 结论：**成立**。附带发现（登记不修）：`valueText` 以 `currentSecretValue` 为 key（`[da1028-C]` 刻意设计，为让外部写入可见），因此**加载落地前键入的内容会被真值覆盖**——不丢数据（写回的是真值），仅体验；保留语义不动。

修法：`secretLoad` 三态 + `produceState` 两分支发布状态（成功 LOADED，异常 UNREADABLE —— 原代码让异常逃逸，等价于「空值 + 可保存」）+ `canSaveWithSecretField` + 点击回调 `if (secretLoad != LOADED) return@MinisTextButton` + PENDING 显示 `common_loading` 占位 + UNREADABLE 显示错误文案。

### F-04（MCP OAuth client secret）

- 异步读 `importPendingClientSecret ?: clientSecret`，`oauthClientSecret` 初值 `""`；Save/Authorize **都没有加载门**。
- 两条写路径都是 `secretValue.trim().ifBlank { null }` ⇒ `null` 在 `MCPOAuthStore.setClientSecret` 里语义是**删除** ⇒ 加载未落地时按 Save **删掉已存 client secret**。
- **报告问「da1023 FIX-D 的 `reconcileForServer` 是否已覆盖」→ 证伪**：`reconcileForServer` 读的是 `clientSecret(context, server)`（即存储的**当前值**），它解决的是「OAuth 不可用时清凭据」，天然继承表单刚写坏的值，**不构成守卫**。

修法：`oauthSecretLoad` + `oauthSecretEdited`（唯一表达「改动意图」的位）+ `decideClientSecretWrite(oauthConfigured, state, editedByUser, text) → LEAVE_UNTOUCHED / SET / CLEAR`：
- `oauthConfigured == false` → CLEAR（保留 `[fix/audit0917-b8]` 契约：非 OAuth 服务器不留凭据）；
- 未 LOADED 或用户没动过 → **LEAVE_UNTOUCHED**（一个字节都不写）；
- 动过：非空 SET、空 CLEAR（**清空是显式意图**）。
Save 与 Authorize 共用同一决策；两个按钮 `enabled = !oauthBusy && oauthSecretReady`。

---

## 7. 证据差异表（报告转写 vs 真码）

| 报告条目 | 转写内容 | 真码 | 处置 |
|---|---|---|---|
| F-01 | 清理在 `finally` 中 | 无 `finally`，清理在 try 体内 | **修正**：异常路径会永久留幽灵规则；新入口无此路径 |
| F-01 | 建议 `remember`/`derivedStateOf` | — | **换方案**：改用只读求值入口（漏 key 风险 vs 去根因） |
| F-02 | 恢复「已写却被报 busy」 | busy 分支在任何写之前 return | **证伪**：浪费的是下载字节，不是落盘 |
| F-02 | 未提「为何原代码拒绝持门」 | 文件内有 `[refactor-backup-gate]` 注释：持门会让 restore 的检查自碰撞 | **补全**：因此修法必须带 `preClaimed` 交接，单加锁会直接锁死功能 |
| F-02 | 未提 `remoteLoading` 兼两用 | `openRemoteList.finally` 无条件清零 ⇒ 重开面板可二次恢复 | **新增发现**，一并修（改由 `operationBusy` 承担互斥） |
| F-03 | 「快速点保存写空串」 | 成立；`update()` 无条件写 | 采纳；另发现键入内容会被落地真值覆盖（体验，不丢数据）→ 登记 |
| F-04 | 「核 da1023 FIX-D 是否已覆盖」 | `reconcileForServer` 读的是存储当前值，覆盖不了 | **证伪**：不是守卫 |
| F-04 | 只提 Save | Authorize 是第二条写路径，同缺陷 | **补全**：两处同修 |

---

## 8. 验证与残留

### 8.1 测试（本地 JVM，单进程，逐类运行）

| 类 | 例数 |
|---|---|
| `SettingsWriteGatesTest`（新，纯逻辑） | 11 |
| `ThinkingRulePreviewNoPublishTest`（新，形状） | 6 |
| `RestorePayloadBusyGateTest`（新，形状） | 10 |
| `SettingsSecretWiringTest`（新，形状） | 8 |
| `PreviewProbeTest`（探针，真 resolver 对拍） | 8 |
| `BackupFetchBusyGateTest`（既有，半径计数 7→8） | 全绿 |
| `McpScreenAndSheetsOffMainTest` / `EnvironmentVariablesOffMainTest` / `ThinkingRuleResolverRevisionTest` | 全绿 |
| `ThinkingRuleEditorPreserveTest`（既有 22 例） | 22 全绿 |

合计本地 80 例（一次 JVM）+ 22 例（编辑器，独立进程）。

### 8.2 变异臂（11/11 变红，`probe-d4/arm.py`，一臂一进程）

`F01-PREVIEW-REPUBLISH` · `F01-NO-DRAFT-ENTRY` · `F01-REVISION-NOT-BUMPED` · `F02-MANUAL-NO-CLAIM` · `F02-SNAPSHOT-NO-CLAIM` · `F02-NO-HANDOFF` · `F02-ROWS-ALWAYS-ENABLED` · `F03-SAVE-NOT-GATED` · `F03-SAVE-NO-GUARD` · `F04-NO-EDITED-INTENT` · `F04-BUTTON-NOT-GATED`

三个臂第一轮**假绿**，都是判据不够锐（已改判据、非改代码）：①`revision` 判据用裸 `contains`（四站自增，删一站仍绿）→ 改为**函数体内**判定；②`claimRestorePayload` 判据窗口过大（helper 定义本身满足了「之前出现过」）→ 收紧为同 handler 内 1200 字符窗；③按钮门用裸 `contains`（两个按钮同一个前缀）→ 改为**计数 2**。

### 8.3 静态门（改前/改后各一轮，15 道全绿）

`sysinfo_state_thread_check` `readonly_mount_write_guard` `thinking_wiring_guard` `skill_import_offmain_check` `provider_import_id_roundtrip_check` `thinking_rule_editor_preserve_check` `mcp_oauth_lifecycle_check` `deeplink_settings_routes_check` `ime_inset_guard` `provider_remember_key_check` `filepreview_print_bounded_check` `backup_streaming_export_check` `share_dir_prune_check` `anthropic_thinking_budget_check` `i18n_check`

> 施工中撞到两次门挡住真改动，都是门在正常工作、改了实现而非门：
> - `ime_inset_guard` B01-CALLS：把 `restoreWithSnapshot` 写成**嵌套 `fun`** 会让本屏的 `SettingsScaffold(` 归属漂移到那个 fun（门按「最近的 `fun`」定 owner）⇒ 退回 lambda 形态，注释里写明理由。
> - `skill_import_offmain_check` B-04：seed effect 里必须保留 `val sid = editServer?.id` 这种字面绑定（门用它区分「重置」与「从存储播种」）⇒ 不用 `val srv = editServer` 转一手。

### 8.4 残留（交主会话决定是否入 backlog）

- **R1（F-01）** 预览仍每帧重算（廉价纯函数）。若日后要 `remember`，必须把 11 个字段全列进 key——漏一个是静默「预览不刷新」。
- **R2（F-02）** `restoreWithSnapshot` 内部「创建快照失败 → `operationBusy = false` 后 return」的释放是**手写站点**（既有 `[FIX-P1A]` 契约，未动）。新增的 `releaseRestorePayload()` 与该站并存，日后若再加释放路径，应一律走 helper。
- **R3（F-02）** 载荷互斥只覆盖 UI 层四个入口；`AutoBackupManager` 的夜间自动备份不在 `operationBusy` 体系内（既有设计，本次半径外）。
- **R4（F-03）** 编辑态下「加载落地前键入的值」会被真值覆盖（`[da1028-C]` 的 key 设计使然）：不丢数据、只影响体验，**刻意不动**。
- **R5（F-03/F-04）** `UNREADABLE` 是终态：表单只能关掉重开。若能重试读取，应把重试按钮加进提示区（需新的 i18n 文案 7 份）。
- **R6（F-04）** `remember(editServer)`（整条记录）作为 key 时，列表刷新会重置 secret 与 `oauthSecretEdited`——因重置即回 `PENDING`（保存被禁），**fail-closed 安全**，只是重读一次。要消除需键到 `editServer?.id`，被既有形状断言 `oauthSecretSeed_isNotKeyedOnTheIdAlone` 明确禁止（该断言针对 `LaunchedEffect`，但同族意图一致）。
- **R7（全局）** 真机未验：本轮全部证据是编译级（CI）+ JVM 级（真 resolver / 纯逻辑 / 源码形状），沙箱无 Android 运行时。需真机确认的观感项：预览对话框打开时不卡顿、备份页恢复下载期间按钮全灰、环境变量秘密不可读时提示语、MCP 保存后 client secret 未被清空。

## 9. 装置与复现

`/var/minis/shared/work/meta-audit-1028/probe-d4/`

| 文件 | 作用 |
|---|---|
| `run_preview.sh` | 编译真 resolver + 桩（`ThinkingLevel`/`ProviderThinkingRuleEntity`）+ 修前修后两种 previewJson 转录，跑 `PreviewProbeTest` |
| `run_gates.sh` | 纯逻辑门真值表 |
| `run_shape.sh` / `run_restore_shape.sh` / `run_secret_shape.sh` | 三组源码形状断言 |
| `run_all.sh` / `run_preserve.sh` | 一次 JVM 跑全部新测试 + 邻居 / 既有 22 例 |
| `mutate.py`（`arm.py` 软链） | 11 个变异臂，一臂一进程，跑前先复原 `.armbak`（防上次被杀的残留污染基线） |

## 10. CI 判读

- **Run 1 `40699319582`（dispatch 13:45:10Z）→ failure @ 8.5 min**：唯一失败用例 = 既有 `MCPOAuthLifecycleTest > the form fields stay keyed on the entry so a reopen re-seeds them`（`:app:testReleaseUnitTest` FAILED，BUILD FAILED in 4m 41s；本地 80+22 例全绿仍漏）。根因：该测试钉住编辑表单 `remember(editServer)` 恰 17 次（[U2] keyed-field 半径守卫），F-04 新增 3 个 keyed 状态（oauthSecretLoad / oauthSecretEdited / oauthAuthorizedSeed）→ 20。三个状态语义上**就该** keyed on entry（reopen 重置 = fail-closed：PENDING 禁保存、意图清零、seed 重算），属**延续契约**而非破坏 → 更新计数 17→20 + 注释写明缘由（提交 `7b23be03`）；python 机械复刻断言 = 20 PASS；全仓 grep 确认无其它 `remember(editServer)` 钉（tests + `scripts/scan/`）。
- **教训（本地漏跑的机制）**：MCP 的既有邻居测试不在 `ui/settings/` 包而在 `mcp/oauth/`（`MCPOAuthLifecycleTest`）——按包列邻居 = 漏；引用检查要按**文件名全 test 树扫**（`rg -l <文件名> src/test/`），本轮 CI 抓到的正是这个洞。
- **Run 2 `40702497006`（dispatch 14:05:15Z）→ success @ 14:26:37Z（21.4 min）**：head_sha `7b23be03c5d15a58db90ff82687c71ecf5031f84` 与本地 HEAD 逐字符一致；27 步 0 非 success（skipped = rolling tag + Publish，main 门控）；日志 1,509,043 B / NUL 0；`Summary: 113 passed, 0 failed` 恰 1；`ALL 349 CASES PASS` 恰 1；门头 `[n/113]` 编号 distinct 113；`^ e: ` 0；FAILED 行（排除 PASSED 子串）0 条。

**停止节点达成：分支 CI 绿（head_sha 逐字符一致），合并起手权在用户。**
