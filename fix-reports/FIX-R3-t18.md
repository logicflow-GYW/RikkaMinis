# FIX-R3-t18 — 门见证第三轮收口：`backup_remap_rollup_check` / `mcp_oauth_lifecycle_check` / `backup_streaming_export_check`

- 分支：`fix/r3-backup-mcp` · 基线 `main` @ `f0f14d46d`
- 施工支线：R3-t18（任务书 `round3b/fix-tasks/R3-t18.md`）
- 结论：**9 / 9 条见证全部机械重放为 `BLIND_CONFIRMED`；修后 9 / 9 全部 `CAUGHT`**（rc=1，且红在本支新收紧的那条判据上），restore 9/9 sha256 一致，`src/` 全程零残留。
- 改动面：**只改 3 个门脚本**（+928 / −43）；生产 `.kt` 0 行改动；`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` 零触碰；门数 112 / 夹具 344 不变。

---

## 1. 门（本次施工范围）

| # | 门脚本 | 修的判据 | 见证 |
|---|---|---|---|
| A | `scripts/scan/backup_remap_rollup_check.py` | `check_voice_group_remap`（派生化）、`check_defaults_writer_lands_value`（新）、`check_stage3_remap_consumed`（新）；顺带把自写 `_balanced` / `_match_paren` / `_unparen` / `_lit_value` 换成共享原语 | #6 #7 #8 |
| B | `scripts/scan/mcp_oauth_lifecycle_check.py` | `check_export_secret_strip`（新）、`_reconcile_payload_problem`（新，挂在 `check_repo`）、`check_form_oauth_persisted`（新） | #27 #28 #29 |
| C | `scripts/scan/backup_streaming_export_check.py` | `check_arm7`（新，导出 sink → 落地 → 上传链）、`check_arm8`（新，自动备份远端推送） | #9 #10 #11 |

三条 dual-gate 见证的对门（`auto_approve_bypass_check` / `skill_import_offmain_check` / `provider_remember_key_check`）**不属本支施工面**，它们在对应补丁下仍 rc=0，理由与归属见 §4.4。

---

## 2. 轴（失效轴分布）

| 轴 | 见证 | 旧判据为什么瞎 |
|---|---|---|
| `dataflow`（效果未达成） | #6 #9 #10 | 调用/写都在，只是**用错的那一份值**：Stage 3 把 `decoded` 交给 writer；WebDAV 上传的是 `ByteArray(0)`；本地导出调用被常量短路 |
| `symmetric`（同族枚举不完整） | #8 #11 | 判据靠手写名单：`remapDefaultsIds` 只断言了 voice 两条，同块内的 `subGroup` 被删也不红；`runLocked` 只查本地写出与轮转，不查远端推送那一句 |
| `anchor` / 「存在即绿」 | #7 #27 #28 #29 | 只断言**拼写存在**：字段 path + reader 存在、消毒函数体存在、`reconcileForServer` 被调用、`MCPServerConfig` 被构造 —— 至于「写进去的是不是那个值 / 那一句会不会跑 / 传的是不是那个 server / oauth 参数在不在」，没有任何一条判据问过 |

四条轴的共同解法与前几批同源：**把判据从「名字/拼写出现」搬到「从树派生的决定点 + 效果真的发生」**。

---

## 3. 见证重放表（§2.1 pre-fix / §2.3 post-fix）

补丁全部按档案散文重构为等价的最小编辑（`probe/t18-w<k>.patch`），逐条跑 `gates_involved` 的每一道门，改完即还原并核 sha256。

| # | kind | 目标文件 | 等价补丁 | pre-fix rc（本门/对门） | 判决 | post-fix rc（本门/对门） | 修后判决 |
|---|---|---|---|---|---|---|---|
| 6 | dual-gate | `ConfigBackup.kt:1239` | `field.write(value)/validate(value)` → `(decoded)` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT |
| 7 | dual-gate | `ConfigBuiltins.kt:1812` | `repo.voiceInputGroupId = s` → `= null` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT |
| 8 | symmetric | `ConfigBackup.kt:2137` | 删 `"defaults.subGroup",` 路由臂 | 0 | **BLIND_CONFIRMED** | **1** | CAUGHT |
| 27 | dual-gate | `ConfigBackup.kt:672` | `if (!includeSecrets)` → `if (!includeSecrets && false)` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT |
| 28 | dual-gate | `MCPRepository.kt:432` | `oauth = server.oauth` → `oauth = null` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT |
| 29 | dual-gate | `MCPIntegrationsScreen.kt:808` | `oauth = currentOAuthConfig()` → `oauth = null` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT |
| 9 | dual-gate | `BackupSettingsScreen.kt:939` | `payload = tempFile.readBytes()` → `ByteArray(0)` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT |
| 10 | dual-gate | `BackupSettingsScreen.kt:461` | `readFailures = exportToWriter(…)` → `if (false) readFailures = exportToWriter(…)` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | CAUGHT |
| 11 | symmetric | `AutoBackupManager.kt:207` | 删 `WebDavSync.backupAuto(cfg, local)` | 0 | **BLIND_CONFIRMED** | **1** | CAUGHT |

- `restored`: 9/9 `true`（每条补丁后 `git checkout` 还原，sha256 与原始逐字节一致；批量跑中途被沙箱 `Errno 38` 打断过两次，残留文件均已 `git checkout --` 回滚，最终 `git status` 只含 3 个门脚本）。
- 主会话自动复证标记为 `NEEDS_MANUAL` 的 4 条（#6 #8 #10 #11）本支**已手工重放并全部确认为真实失明**，不是「打不上」。
- **#10 的自伤修正**：第一版等价编辑写成 `readFailures = if (false) 0 else exportToWriter(…)`——这在 Kotlin 里仍会执行 else 分支，**不构成缺陷**（门当时红在 `_branch_extent` 把同行 `0 else …` 整段当 then 分支的解析副作用上）。已改为真短路 `if (false) readFailures = exportToWriter(…)` 重放，判决与证据才是可信的。

### 3.1 修后红在哪一条判据上（`probe/t18-post.json` 原文摘录）

```
[6]  ConfigBackup.kt:1239: Stage 3 keeps `val value = remapDefaultsIds(…)` but hands `decoded` to `field.valu…
[7]  ConfigBuiltins.kt:1796: "defaults.voiceInputGroup" writer validates `s` and then stores something else…
[8]  ConfigBackup.kt:2131: remapDefaultsIds does not route "defaults.subGroup" to groupIdMap — …
[27] ConfigBackup.kt: the `stripExportedMcpSecrets(` call in buildSections is DEAD-GUARDED (a constant condition can never hold)…
[28] MCPRepository.kt:428: `MCPOAuthStore.reconcileForServer(` — `oauth = null` is a literal — reconcileForServer decides…
[29] MCPIntegrationsScreen.kt: `oauth = null` — a literal, so the form's OAuth Client ID / endpoints / redirect URI never reach…
[9]  arm7: the WebDAV upload's payload is a CONSTANT (`ByteArray(0)`) — …
[10] arm7: line 459: ConfigBackup.exportToWriter( sits behind a constant condition (DEAD-GUARDED) — …
[11] arm8: AutoBackupManager.runLocked never calls WebDavSync.backupAuto( — …
```

---

## 4. 修法

### 4.1 `backup_remap_rollup_check`（#6 #7 #8）

**共同病因**：判据靠手写路径名单 + 只看绑定的 reader 半边。修法是**从注册表派生整个 group-id 绑定族**，再逐条端到端判「值真的落地」。

1. **派生 family**（新 `group_id_defaults_paths`）：
   `ClosureField(` 注册块里 `path = "defaults.*"` 且 writer 体含 `modelGroups.none { it.id ==`（即语义上就是 group id）→ 全部收齐。真树派生结果为
   `defaults.primaryGroup / defaults.subGroup / defaults.voiceInputGroup / defaults.voiceOutputGroup`（`defaults.agentLoopGroups` 的 writer 不校验 modelGroups，不属该族）。
2. **`check_voice_group_remap(src, src_builtins)`**（#8）：路径集合改为派生集合，每条仍走原有的 `_routes_to_map`（「live value 必须恰是 `groupIdMap`」）。删掉同块内任意一条臂即红。
3. **`check_defaults_writer_lands_value`**（#7，新）：对族内每个字段，
   - writer 必须绑定被校验值 `val <s> = (v as? ConfigValue.Str)?.value`（派生，不写死变量名）；
   - 必须存在 `repo.<prop> = <expr>`，其 `value_sources` **全部**含 `<s>`（即真的把校验过的值存下去）；
   - 该 `<prop>` 必须与 reader 读的是同一个属性（防「读 A 写 B」）；
   - 该写入不得落在 `s.isEmpty()` 的 then 分支里。
4. **`check_stage3_remap_consumed`**（#6，新）：`remapDefaultsIds(` 的结果必须绑定到局部变量 V，且 `field.valueSchema.validate(…)` / `field.write(…)` 的实参 `value_sources` **全部**含 V —— 把 `decoded` 递进去即红。
5. **硬约束 #1 的落地**：`_balanced` / `_match_paren` → 共享 `match_bracket`；`_unparen` → `strip_parens`；`_lit_value`（只认 `true`/`false` 的白名单）→ 共享 `const_bool`（认 `1 == 2` / `&& false` 折叠）；删掉零调用的 `_split_top`。括号与常量折叠从此在仓内只有一份实现。

### 4.2 `mcp_oauth_lifecycle_check`（#27 #28 #29）

1. **`check_export_secret_strip`**（#27，新）：`buildSections` 体内唯一的 `stripExportedMcpSecrets(` 调用必须
   （a）`path_feasible`（`&& false` / `if (false)` 一律红）；
   （b）守卫条件含 `!includeSecrets`（无守卫 → 显式带密钥导出会被误脱敏，同样是缺陷）；
   （c）脱敏的是**那个被 `mcpServers.put(<ident>)` 写出的对象**（`obj.copy()` 这类「脱敏副本被丢掉」也红）。
2. **`_reconcile_payload_problem`**（#28，新，挂在 `check_repo` 的每个 `reconcileForServer(` 调用点）：`server` / `oauth` / `serverUrl` 必须都在，且后两者必须**派生自本次要保存的那个 server**（标识符从 `server = <expr>` 的首段取），常量/`null` 一律红 —— 因为 `oauth = null` 会被 `reconcileForServer` 读成「用户取消了 OAuth 配置」并立刻 `purge()`。
3. **`check_form_oauth_persisted`**（#29，新）：`buildServerFromForm()` 里带 `url =` 实参的 `MCPServerConfig(` 必须同时带 `oauth = …`，且其 `value_sources` 必须追溯到 `currentOAuthConfig()`。缺参数、`= null`、或换成别的变量都红；找不到 url 分支 → anchor drift 红。

### 4.3 `backup_streaming_export_check`（#9 #10 #11）

1. **`check_arm7`**（#9 #10，新）：判据从**写入口（sink）**派生，而不是从调用拼写：
   - 屏幕里每个 `openOutputStream(` / `FileOutputStream(` 必须有一条真实的 `…OutputStream(v)?.use { out -> OutputStreamWriter(out, …).use { w -> exportToWriter(… writer = w) } }` 链 —— 链断 = 目标文件被打开却没人写入（#10b）；
   - 该 `exportToWriter` 调用必须 `path_feasible`（`if (false)` 短路 = 0 字节 + 成功提示，#10a）；
   - 任何 `WebDavSync.backup(` 上传的 `payload` 不得是常量，且必须真的从 `exportToWriter` 写出的那个文件读（`readBytes` / `inputStream` / `readAllBytes` …，#9）。
2. **`check_arm8`**（#11，新）：`AutoBackupManager.runLocked` 必须存在**可达**的 `WebDavSync.backupAuto(`，且第二个实参的 `value_sources` 指向 `exportToWriter` 写出的那个 `local` 文件 —— 删掉推送即红（`KEY_LAST_RUN` 仍会被写，远端永远收不到）。
3. 全部走共享 `function_body` / `match_bracket` / `call_args` / `value_sources` / `path_feasible` / `const_value`，无新增自写词法器。

### 4.4 dual-gate 的另一半：这三道门不该管这些缝

任务书 §2.2 要求修完让两门都红，**或**明确说明归属。本支属后者：

| 见证 | 对门 | 为什么不该由它管 | 谁管了 |
|---|---|---|---|
| #7 | `auto_approve_bypass_check` | 它判定「ConfigBuiltins 不含 auto-approve 旁路」；`ClosureField.writer` 的落库值是备份恢复面的问题 | 本支 `check_defaults_writer_lands_value` |
| #28 #29 | `skill_import_offmain_check` | 它判定「磁盘 I/O 不在主线程」；`reconcileForServer` 的实参取值与该屏幕的 I/O 调度是两件事 | 本支 `_reconcile_payload_problem` / `check_form_oauth_persisted` |
| #9 #10 | `provider_remember_key_check` | 它判定 provider label 单一来源；备份 payload 的真值流不在它的面 | 本支 `arm7` |

对门在这些补丁下保持 rc=0 是**正确的**：它们的判据面没有被这些缺陷触及。

---

## 5. 四层证据

### L1 本门（真树 + `--selftest` 带牙）

| 门 | 真树 | selftest | 臂/夹具变化 |
|---|---|---|---|
| `backup_remap_rollup_check` | `rc=0` | `58 arms matched (pristine green + 57 targeted mutations)` · `BACKUP_REMAP_ROLLUP_SELFTEST=PASS` | 55 → **58**（+w6/w7/w8 三条，均断言「恰好红在自己那条判据上」） |
| `mcp_oauth_lifecycle_check` | `rc=0` | `MCP_OAUTH_LIFECYCLE_SELFTEST=PASS` · 新增行 `selftest r3-t18 arms (…) -> 1/1/1/1 + 1/1 + 1/1/1 violation(s)` | fixtures 断言 +**9**（export-strip dead/absent/copy/ungated + reconcile oauth-null/url-literal + form null/dropped/detached），另加 1 条 compliant clean 臂 |
| `backup_streaming_export_check` | `rc=0` | `BACKUP_STREAMING_EXPORT_SELFTEST=PASS` | `run_fixture(` 27 → **34**（+7：`upload-constant-payload` / `local-export-dead-guard` / `local-export-gone` / `auto-no-push` / `auto-push-other-file` + `compliant-upload-streamed` clean 臂；`contains=` 钉住命中哪一条判据） |

`COMPLIANT_SCREEN` 与 `COMPLIANT_AUTO` 已同步扩展为真实形态（SAF 导出链 + WebDAV 上传段 + `cfg != null` 推送段），否则新臂会「靠 fixture 缺段」空转通过。

### L2 反攻（原见证翻红）

`probe/t18-pre.json`：9/9 `BLIND_CONFIRMED`，`restored` 9/9 `true`。
`probe/t18-post.json`：9/9 `CAUGHT`（本门 rc=1，报错行见 §3.1），`restored` 9/9 `true`。

### L3 邻门 / 全量（逐门独立进程，不用 `scan.sh`）

- 本支三门 + 语义相邻门（`auto_approve_bypass_check` / `skill_import_offmain_check` / `provider_remember_key_check` / `config_*` 相关）：全部 `rc=0`。
- **全量 111 门 `rc=0`**（109 个 `*_check|*_guard` + `process_logging_gate` + `turn_tail_finalize_order`），用 `probe/t18_gate_loop.py` 分批跑（每批 14–20 门，逐门独立子进程，`calendar_events_nullable_predicate_check` / `tts_voice_and_sanitizer_check` 走 `--root .` 形态）。**无失败门**。
- `scripts/scan/test_scan.py`（344 夹具）：本地三次尝试全部被沙箱 `OSError: [Errno 38] Function not implemented` / stall-guard 打死（与 scan.sh 同族缺陷，非门红）⇒ **`ALL 344 CASES PASS` 交 CI 承担**（CI job 会跑 `scan.sh` + `test_scan.py`），见 §6。

### L4 CI

见 §6（run id / head_sha / 关键日志行在合并前回填）。

---

## 6. CI

| 项 | 值 |
|---|---|
| run | **#2741** / id `40314208838` / job `121542611983` / attempt 1 |
| 触发 | `workflow_dispatch` on `fix/r3-backup-mcp`（分支 push 不触发，`build-apk.yml` 只绑 main） |
| 结论 | **success**（11:19:29Z → 11:42:39Z，23m10s） |
| head_sha | `9c17f2f72b445ef3469ad10772ddf2ad58b194f8` —— 与本地 `git rev-parse HEAD` **逐字符一致** |
| 26 步 | 唯一非 success = step 23 `Publish to Releases` **skipped**（`if: ref == refs/heads/main` 门控，分支构建的预期行为） |
| 日志 | 1,486,880 B |

日志关键行（各自 **恰 1** 次，`grep -c`）：

```
Summary: 112 passed, 0 failed          → 1
ALL 344 CASES PASS                     → 1
^ e: (Kotlin 编译错误)                  → 0
BACKUP_REMAP_ROLLUP_SELFTEST=PASS       → 1
MCP_OAUTH_LIFECYCLE_SELFTEST=PASS       → 1
BACKUP_STREAMING_EXPORT_SELFTEST=PASS   → 1
门头 [N/112] 互异编号                    → 112
```

本支三门在 CI 里的见证行（各 1 次，⇒ 新判据与新臂在 CI 上真跑）：

```
backup_remap_rollup_check: OK (compact pin remapped, binding remapped, rollup packed + guarded, group run-config restored on same-name merge)
backup_streaming_export_check: OK (arm1 + arm2 + arm3 + arm4 + arm5 + arm6 + arm7 + arm8 green)
PASS mcp_oauth_lifecycle_check (… ; 17 form field state(s) keyed on the entry)
  ✅ backup_remap_rollup_check selftest has teeth (BACKUP_REMAP_ROLLUP_SELFTEST=PASS)
  ✅ backup_remap_rollup_check real tree green
  ✅ backup_streaming_export_check selftest has teeth (BACKUP_STREAMING_EXPORT_SELFTEST=PASS)
  ✅ backup_streaming_export_check real tree green
  ✅ mcp_oauth_lifecycle_check selftest has teeth (MCP_OAUTH_LIFECYCLE_SELFTEST=PASS)
  ✅ mcp_oauth_lifecycle_check real tree green
```

`FAILED` 共 6 行，逐条确认**全部是用例名的子串**（`… produces FAILED without calling provider PASSED` 等），无一条是失败用例。

**因此 `ALL 344 CASES PASS` 也有 CI 侧见证**——本地 `test_scan.py` 被沙箱 `Errno 38` 打死的问题（见 §5 L3）不影响结论。

**停止节点**：分支 CI 绿 = 本轮开发到此结束，未合并 main（合并起手权在主会话 / 用户）。


---

## 7. 残留（P2，各自带触发条件 / 触发后方案 / 搁置理由）

> 全部为 **fail-closed** 方向（解析不出即报红，不会静默放行），因此不构成本轮的门失效；登记是为了把「重锚点」写清楚，避免下次有人对着一条无法解释的红去删臂。

**R1 · `check_export_secret_strip` 只认 `mcpServers.put(<ident>)` 直写形态**
- 触发条件：`buildSections` 的 MCP 段重构成「先收集到 list / `JSONArray().put(…)` 链式」，或 `put` 的实参变成表达式（如 `put(obj.apply { … })`）。
- 触发后方案：把「谁被写进 mcpServers」改成派生末次写（`decl_sites` + `last_write`），或用 `value_sources` 解析 `put` 的实参再看是否含被脱敏对象。
- 搁置理由：当前形态唯一且稳定；改法是纯增判据，等真的重构时一起做，否则是为一条未触发路径建装置（修复门纪律：装置与影响面脱钩）。

**R2 · `arm7` 的 writer 链假定 `OutputStreamWriter(…).use { w -> … }`**
- 触发条件：屏幕导出改用 `bufferedWriter()` / `PrintWriter` / `sink(…)` 等其它 writer 包装（`AutoBackupManager` 用的就是 `bufferedWriter()`，但那属 arm1 的面）。
- 触发后方案：把 writer 派生抽成「sink → 任意 writer 构造 → exportToWriter」的多形态解析（`OutputStreamWriter|bufferedWriter|PrintWriter`）。
- 搁置理由：屏幕两处导出都是 `OutputStreamWriter`；扩形态属判据扩容而非缺陷修复。

**R3 · `_file_sinks` 在整文件范围收集 File sink**
- 触发条件：屏幕新增第二个 `val x = File(…)` 且 `x.bufferedWriter()` / `FileOutputStream(x)`，且它不是导出目标。
- 触发后方案：把 sink 集合收窄到「与本次 `exportToWriter` 写入链同一函数体」的范围。
- 搁置理由：集合变大只会**放宽** payload 判据（不误伤），当前文件只有一个导出文件目标。

**R4 · `check_defaults_writer_lands_value` 依赖 `val <s> = (v as? ConfigValue.Str)?.value` 形态**
- 触发条件：`ClosureField` 的 writer 重构为 `when (v) { is ConfigValue.Str -> … }` 形态。
- 触发后方案：派生「被校验值」时同时接受 `when`-分支绑定（`when_branches` + `value_sources`）。
- 搁置理由：四个 group-id 字段全部是同一种 writer 模板；换成 when 形态是一次性重写，届时按红重锚即可（红消息已写明「fail-closed / re-anchor」）。

**R5 · `check_stage3_remap_consumed` 只看第一个 `remapDefaultsIds(` / 第一个 `field.write(` **
- 触发条件：`import()` 里出现第二个字段循环（第二个 `field.write(` 调用点）。
- 触发后方案：改成「每个 `field.write(` 都必须消费最近的 remap 绑定」的逐站点判据。
- 搁置理由：Stage 3 只有一个字段循环；逐站点化需要先把「remap 绑定 ↔ write 站点」的作用域归属建模，成本 > 当前收益。

**R6 · `_reconcile_payload_problem` 的 server 标识符从 `server = <expr>` 首段派生**
- 触发条件：`reconcileOAuth` 改成先取 `val id = server.id` 再传 `server = id` / `oauth = srv.oauth`（两侧变量名不再同源）。
- 触发后方案：从 `persistsAsUrlTransport(<x>)` 的实参派生 server 标识符（那条实参必然是 MCPServerConfig 实例）。
- 搁置理由：当前三个实参同源于 `server`；改判据需两条派生路径取交集，等真实重构再动。

---

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t18        # 分支 fix/r3-backup-mcp

# —— pre-fix 复证（9 条见证逐条重放，打补丁→跑门→还原→核 sha256）——
python3 probe/t18_replay.py pre                     # → 9/9 BLIND_CONFIRMED
# —— 修后反攻（本门必须 rc=1，且红在新判据上）——
python3 probe/t18_replay.py post --only=6,7,8,9,10,11
python3 probe/t18_replay.py post --only=27,28,29    # → 9/9 CAUGHT

# —— 本门（真树 + 带牙自证）——
python3 scripts/scan/backup_remap_rollup_check.py .      ; echo $?   # 0
python3 scripts/scan/backup_remap_rollup_check.py --selftest       # 58 arms / PASS
python3 scripts/scan/mcp_oauth_lifecycle_check.py .      ; echo $?   # 0
python3 scripts/scan/mcp_oauth_lifecycle_check.py --selftest       # PASS
python3 scripts/scan/backup_streaming_export_check.py .  ; echo $?   # 0
python3 scripts/scan/backup_streaming_export_check.py --selftest   # 34 fixtures / PASS

# —— 全量（逐门独立进程；scan.sh 整份在沙箱里会被 Errno 38 打死）——
python3 probe/t18_gate_loop.py --from=1 --to=20     # 分批，每批 14~20 门
# …… --from=21 --to=40 / 41-60 / 61-80 / 81-95 / 96-112
python3 scripts/scan/process_logging_gate.py .
python3 scripts/scan/turn_tail_finalize_order.py .
```

产物：

| 文件 | 内容 |
|---|---|
| `probe/t18-pre.json` | 9 条 pre-fix 判决 + 每门 rc + restore |
| `probe/t18-post.json` | 9 条 post-fix 判决 + 报错行 + restore |
| `probe/t18-w{6,7,8,27,28,29,9,10,11}.patch` | 每条见证的等价最小编辑（`git diff` 原样） |
| `probe/t18_replay.py` | 重放器（`pre` / `post` / `--only=` 分段） |
| `probe/t18_gate_loop.py` | 逐门独立进程全量跑（含 `--root` 形态门、分批参数） |
