# FIX-FULLSCAN-1119-A-backup — 凭据脱敏等价类 / 恢复不毁密钥 / 三处 null 三态

- **分支**：`fix/backup-secrets-1119`
- **基线**：`ecb03d92`（main）
- **范围**：`fullscan1119` 报告 F-01 / F-02 / F-19 / F-20 / F-23（五条，含 P0 ×2）
- **改动面**：`backup/**` 与其测试，零触碰禁碰清单
- **合并状态**：**未合 main**（CI 绿 + head_sha 逐字符一致 = 停止节点）

---

## 1 先证结论

| ID | 判级 | 先证结论 | 取证方式 |
|---|---|---|---|
| F-01 | P0 | **成立** | `ConfigBackup.kt` Stage 7 只有 `mcpRepo.importJSON(srv.toString())`，循环体内无任何 `includesSecrets` / `hadSecrets` 分支；`hadSecrets` 唯一读点在 `:2207`（结果构造）与 `:2224`（日志），从不参与保护。`MCPRepository.importJSON`（`MCPRepository.kt:583`）按 id last-write-wins 整条替换并 `save()` 落盘 `servers.json` |
| F-02 | P0 | **成立** | 旧 `SECRET_ARG_RE` 用 `matches`（全匹配）且模式为 `^--(…)\s*=\S.*` ⇒ `--token abc` 不匹配；`redactUrlQuery` 只在 `url.contains("?")` 时调用 ⇒ `https://user:pass@host/` 完全跳过 |
| F-19 | P1 | **成立** | `OpenMinisBackupCompat.kt:894` 旧代码 `if (model.has("supportsReasoning")) mo.put(..., model.optBoolean(...))`；`has()` 对显式 JSON null 返回 true，`optBoolean` 折成 `false`。同函数内 `declaresNoEffortTiers` / `costTier` 已是 `has && !isNull` 惯例，本行是漏网的同族成员 |
| F-20 | P1 | **成立** | `importChatSections` 三处同为 `if (x.has(k)) … else null`；`ChatSessionEntity.pinnedAt: Long?`（`null=未 pin`）、`thinkingOverride: String?`、`MessageEntity.reasoningContent: String?`，显式 null 分别落成 `0L` / `""` / `""`（后者经 `capReasoningContentForRow("")` 返回 `""`） |
| F-23 | P2 | **成立** | `parseMultistatus` 的 `"response" ->` 分支重置了 href/displayName/…/propstatOk，**唯独不重置 `statusOk`**；`:616` 只在 `<status>` 处赋值 ⇒ 前一条 2xx 之后，无 `<status>` 的新 response 继承 `true`。原注释「No reset to `true` here」描述的是另一个变量，与代码行为相反 |

`TRIAGE.md` 的判级未被推翻。

---

## 2 修法

### F-02（导出脱敏，等分类而非拼写）

规则移入新的 **Android-free** `McpCredentialFields`，`ConfigBackup.stripMcpSecretValues` 只做委托。

- **args 判据 = flag 名归一化后的集合成员**：`--api-key` / `--api_key` / `--apikey` 归一化成同一个 `apikey`；凭据集是 `{token, apikey, secret, password, accesstoken}`。`--tokenize` 归一化成 `tokenize`（不在集内）⇒ 不误伤；`--port 8080` / `--help` 同理。
- **抹除范围是等价类**：`--k=v` → `--k=`；`--k v` → `--k` + `""`；`--k v w`（值跨到下一个 `--flag` 之前）→ 整段抹空（旧实现只抹第一个 token 会漏掉 `w`）。
- **url 两个载体都处理**：userinfo → `https://[redacted]@host/path`（保留 host 与路径，仍可连）；敏感 query → `?key=[redacted:N]`。**无 `?` 也走 userinfo 分支**，这是旧实现的主漏洞。

### F-01（导入合并）

`configBackup` Stage 7 在 `importJSON` **之前**判断 `root.optBoolean("includesSecrets", false)`：

- `false`（含每日自动备份，`AutoBackupManager` 固定不带密钥）→ 找本机同 id server，`exportServerJSON` 出 live 值，`McpCredentialFields.mergeRedactedMcpServerPayload` 就地回填 headers/env 空值、args 凭据位、redacted url。
- `true` → **完全不合并**，维持原样覆盖（反向对照，见 `M10` 变异臂）。
- 本机无同 id server ⇒ 不合并（没有可保护的值，行为与修复前一致）。
- 非凭据字段（command / 非敏感 args / note / enabled / oauth / timeout）一律取备份值 —— 恢复仍然是恢复。

`MCPRepository` 一行未动（`importJSON` 的其它调用面不受影响），保护发生在调用前，符合任务书要求。

### F-19 / F-20 / F-23

- F-19：加 `&& !model.isNull("supportsReasoning")`，与同函数既有三态惯例对齐；缺键/显式 null 都不写键 ⇒ 下游 `LLMModel.supportsReasoning: Boolean?` 保持 null（"未声明"）。
- F-20：抽成 Android-free `ChatRestoreFields`（`optLongOrNullIfPresent` / `optStringOrNullIfPresent` + `readSessionScalars` / `readReasoningContent`），`importChatSections` 改为经它读。各字段按自己的 null 语义处理，未一刀切：`pinnedAt` 的 null=未 pin、`thinkingOverride`/`reasoningContent` 的 null=无覆盖。
- F-23：新 `<response>` 开头 `statusOk = false`，注释同步改写成「每个 response 从失败开始，由它自己的 `<status>` 武装」——代码与注释同批提交。

---

## 3 证据

### 3.1 本地 JVM 装置（ksandbox，kotlinc 2.1.0 + JUnit 4.13.2）

| 装置 | 文件 | 用例数 |
|---|---|---|
| `jvm/run.sh` | `McpCredentialFieldsTest` + `ChatRestoreFieldsTest` | **19 OK** |
| `jvm/runwebdav.sh` | `WebDavResponseStatusResetTest`（MockWebServer，真 `list()` 解析路径） | **4 OK** |
| `jvm/runcompat.sh` | `CompatSupportsReasoningNullTest`（真 `OpenMinisBackupCompat.convert`，手搓 zip 包） | **3 OK** |
| `jvm/runwiring.sh` | `BackupCredentialWiringTest`（ConfigBackup 接线形状） | **3 OK** |

合计 **29 条全绿**。F-01/F-02/F-20/F-23 是**行为级**（跑真生产文件）；F-19 也走真 `convert()`；只有 Android-bound 的 `ConfigBackup` 接线用源码形状钉（`functionBody` 花括号配对，不是裸 `contains` 全文）。

`ConfigBackup.kt` 本身无法在沙箱编译（`android.util.Log` + 仓库依赖），另加**形状编译探针** `jvm/probe/Stage7Shape.kt`：把 Stage 7 的 local 查找 / export / 守卫合并调用，以及 `importChatSections` 的 scalars 赋值与 `?.let { cap(it) }` 原样复刻到最小桩上编译通过 —— 证明这两处表达式的类型形状成立，但**不声称**替代 CI 的生产编译。

### 3.2 变异负控（`jvm/mutate.py`，快照 + finally + sha256 还原，禁 `git checkout --`）

| 臂 | 变异 | 结果 |
|---|---|---|
| M1_no_reset | 删 `statusOk = false` | RED-PRECISE：2 条（200 后无状态 / 请求目录无状态） |
| M2_reset_true | 改成 `statusOk = true` | RED-PRECISE：3 条（含「无状态在 200 之前」对照） |
| M3_equals_only | 退回只处理 `--k=v` | RED-PRECISE：`spaceSeparatedFormIsBlanked` |
| M4_substring_flag | 凭据判据改成前缀匹配 | RED-PRECISE：`benignFlagsAreUntouched`（`--tokenize` 被误伤） |
| M5_query_only | url 退回只在有 `?` 时处理 | RED-PRECISE：userinfo + query 两条 |
| M6_no_merge | 合并函数直接 `return false` | RED-PRECISE：headers / env+args 两条 |
| M7_no_placeholder_rule | 占位符判据只认空串 | RED-PRECISE：`placeholderRuleMatchesOnlyBlankOrMarkerValues` |
| M8_compat_null_folds | 退回 `has()` 单条件 | RED-PRECISE：`explicitJsonNullStaysUndeclared` |
| M9_stage7_no_merge_call | 删 Stage 7 合并调用 | RED-PRECISE：`stage7MergesLiveCredentialsIntoASecretsFreePayload` |
| M10_stage7_ungated_merge | 守卫改 `if (true)` | RED-PRECISE：同上（含「不得早于守卫」判据） |
| M11_chat_raw_session_read | 退回裸 `has()` 读 pinnedAt/thinkingOverride | RED-PRECISE：`importChatSectionsUsesTheTriStateReads` |
| M12_chat_raw_reasoning_read | 退回裸 `has()` 读 reasoningContent | RED-PRECISE：同上 |

**12/12 RED-PRECISE**，每臂只翻对应集合，无一条多翻或少翻；每臂结束后 sha256 与快照逐字节一致。

### 3.3 相关门（窄跑四道，未跑全量 `scan.sh` / `test_scan.py`）

```
parts_json_funnel_check: PASS
mcp_oauth_lifecycle_check: PASS (… mcp servers.json redacted on a credentials-free export;
      the server list Stage 7 merged is never re-copied over by the artifact stage …)
backup_remap_rollup_check: OK (compact pin remapped, binding remapped, rollup packed + guarded,
      group run-config restored on same-name merge)
openminis-compat-parity: OK (53 keys pinned)
```

`mcp_oauth_lifecycle_check` 的 C7（脱敏单源）/ C8（归档面）/ Stage 9 不覆盖 Stage 7 合并三条臂均绿 —— 说明委托没破坏「一条规则两个面」。

---

## 4 改变什么用户行为

- **F-01**：从「每日自动备份恢复一次，配过 MCP 凭据的服务器静默失效」→「恢复不再损坏凭据」。
- **F-02**：从「自动备份 / WebDAV 远端长期保存 `--token abc` 与 `https://user:pass@host` 明文」→「备份不再泄漏凭据」。
- **F-19/F-20**：从「显式 null 被恢复成『明确关闭 / epoch / 空串』」→「null 保持未声明」。
- **F-23**：从「前一条 2xx 之后，无 `<status>` 的条目被当成功（含请求目录 ⇒ 空文件夹假象）」→「每条 response 独立 fail-closed」。

---

## 5 未做 / 边界

- **`args` 一个字面量的多值形态**：`--token abc def` 在 JSON 里若是**一个**字符串（`"--token abc def"`），现在整段抹成 `--token=`；若是**三个**元素，抹成 `--token` + `""` + `""`。两种都覆盖，语义差异只在「值是否本来就是一个 token」。恢复侧 `restoreSecretArgs` 会把被抹段替换成**本机的一个**值（多余的空白槽丢弃），因为「空 argv 项」不等于「同一条命令」。
- **`pinnedAt` 的 `0L` 仍是值**：显式写 0 的备份恢复成 0（不是 null）。这是刻意保留的反向语义（`ChatRestoreFieldsTest.zeroAndEmptyStringAreValuesNotAbsences` 钉住），否则「epoch 0 = 未 pin」这类约定会被吞。
- **F-01 只保护已存在的同名 server**：新 server 照旧导入脱敏 entry（用户需填一次）。若将来要实现「新建也留空但标红」，属另一条产品决策。
- **Stage 9 原始 `servers.json` 拷贝**未改动：`serverListMergedInStage7` 守着，Stage 7 跑过就跳过，`mcp_oauth_lifecycle_check` 已证。

---

## 6 CI

- **run**：#3325 / `44454170091` @ `fix/backup-secrets-1119`
- **head_sha**：`e72a73098a146fb3a6d784542c0c7a8a38666ad1` —— 与本地 `git rev-parse HEAD` 及远端 `ls-remote` 逐字符一致
- **结论**：五 job 全 `success`（scan-gate / unit-tests / lint / build-apk / publish）
- **unit-tests**：**9,853 PASSED 行 / 0 真 FAILED**。本次新增的 29 条逐条 PASSED（CI 日志原文）：
  - `BackupCredentialWiringTest`：3（含 `stage7MergesLiveCredentialsIntoASecretsFreePayload`、`importChatSectionsUsesTheTriStateReads`）
  - `ChatRestoreFieldsTest`：7（含 `sessionScalarsFoldAbsentAndNullTogetherButKeepRealValues`）
  - `CompatSupportsReasoningNullTest`：3（含 `explicitJsonNullStaysUndeclared`）
  - `McpCredentialFieldsTest`：12（含 `spaceSeparatedFormIsBlanked`、`userinfoPasswordIsRedactedAndHostSurvives`）
  - `WebDavResponseStatusResetTest`：4（含 `aStatuslessResponseAfterA200IsNotListed`）
- **scan-gate**：`115 passed, 0 failed`；日志含 `scripts/scan/** untouched in this push — skipping the fixture self-test` —— 本支未动门，该绿**不含**夹具自检证据（按机制跳过）
- **publish job**：`success`，但 tag / release 两步受 main 门控，分支构建不产出可装 APK

**停止节点**：分支 CI 绿 + head_sha 一致 ⇒ 已完成。**未合 main。**

最终 tip（报告提交）复核：**#3328 / `44457207860` @ `f6f8007e9cec34044172f232176fcfcbf1b8e184`**，五 job 全 success，
unit-tests **9,853 PASSED / 0 真 FAILED**，新用例逐条 PASSED。三源 SHA 一致：本地 HEAD = 远端 `ls-remote` = CI `head_sha`。
报告提交与代码提交之间的**非报告差异为空**（`git diff --stat HEAD~1 HEAD -- . ':!fix-reports'` 无输出）。

