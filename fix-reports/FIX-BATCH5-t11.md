# FIX-BATCH5-t11 — mcp_oauth_lifecycle / envvar_config_group 两门 12 条见证效果化

基线：`e145ec961`（= `6ac77c6b2` + 共享原语 `_kotlin_valueflow`）。分支 `fix/b5-mcpoauth-envvar`。
本支只改这两道门 + 本报告：生产 `.kt` 0 行、`scripts/scan/scan.sh` / `test_scan.py` / `_kotlin_*` 零触碰。

---

## ① 门与轴

| 门 | 见证数 | 轴分布 | 共同病根 |
|---|---:|---|---|
| `mcp_oauth_lifecycle_check.py` | 6 | family-hole 2（S1、A1）· dataflow 3（C5a、dv26-A2、dv20-a1）· order 1（C5b） | 判据锚「某调用/某字面量出现过」，不判「它是否真的决定了结果」 |
| `envvar_config_group_check.py` | 6 | dataflow 4（C1、C2、C4、C5）· order 1（C8）· family-hole 1（C12） | 同上；另加「丢弃壳 / 子串命中 / 括号外续写」三类看不见 |

原树 12 条见证复证结果：**12/12 `BLIND_CONFIRMED`**（`probe/t11-base-mcp.json`、`probe/t11-base-envvar.json`）。改造后：**12/12 `CAUGHT`，restore 12/12 `True`**（见 §④）。

---

## ② 见证原文（file / find / replace / why_gate_green）

### mcp_oauth_lifecycle_check.py

**G04-A1 · S1（`check_store`：`ReconcileAction.PURGE -> purge`）· family-hole**

- file: `src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthStore.kt`
- find:
  ```kotlin
              ReconcileAction.PURGE -> {
                  purge(context, server)
  ```
- replace:
  ```kotlin
              ReconcileAction.PURGE -> {
                  if (false) purge(context, server)
  ```
- why_gate_green: `check_store`（L578-580）用 `r"ReconcileAction\.PURGE\s*->[\s\S]{0,200}?\bpurge\s*\("` 只问「`PURGE ->` 后 200 字符内出现过 `purge(`」。门内 L684 已有 `_if_guard_around` / `_is_dead_guard` 却只挂在 dv26-A2 上，S1–S3 未挂载 ⇒ `if (false) purge(...)` 判绿。
- 缺陷后果：删除已授权 OAuth 服务器 / 切 STDIO → `tokens::<id>`、`secret::<id>` 与明文 `/var/minis/mcp-servers/oauth/<id>.json` Bearer Token 永久残留。

**G04-A2 · C5a（`check_secret_handoff`：`clientSecret` 导入 pending secret）· dataflow**

- file: 同上
- find:
  ```kotlin
      fun clientSecret(context: Context, server: String): String? {
          importPendingClientSecret(context, server)?.let { return it }
          return prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }
      }
  ```
- replace:
  ```kotlin
      fun clientSecret(context: Context, server: String): String? {
          if (false) importPendingClientSecret(context, "")
          return prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }
      }
  ```
- why_gate_green: `_imports_pending_secret`（L468-476）只查 `"importPendingClientSecret(" in body`；不看死分支、不看实参是不是 `""`、不看返回值是否被消费。
- 缺陷后果：客户端密钥停在桥接文件里没迁入 → OAuth 换 token 缺 `client_secret` → 401。

**G04-A3 · C5b（`check_secret_handoff`：`clearTokens` 先迁入后删桥接）· order**

- file: 同上
- find:
  ```kotlin
          importPendingClientSecret(context, server)
          prefs(context).edit().remove(tokensKey(server)).apply()
          deleteBridgeFile(context, server)
  ```
- replace:
  ```kotlin
          if (false) importPendingClientSecret(context, server)
          prefs(context).edit().remove(tokensKey(server)).apply()
          deleteBridgeFile(context, server)
          importPendingClientSecret(context, server)
  ```
- why_gate_green: C5b（L538-540）取 `imp_at = _first_pos(ct_body, r"\bimportPendingClientSecret\s*\(")` 与 `del_at = _first_pos(ct_body, r"\bdeleteBridgeFile\s*\(")` 比大小；`_first_pos` 只返回**第一个**匹配（即死分支里的那个），`imp_at < del_at` ⇒ 判绿。
- 缺陷后果：登出时先删桥接文件 → 真正那次迁移读不到文件 → 用户配置的 `client_secret` 被永久抹掉，无法再登录。

**G04-A4 · dv26-A2 C1/C2（`check_reconcile_promotes_fresh_bridge`）· dataflow**

- file: 同上
- find:
  ```kotlin
                      BridgePayload(
                          accessToken = t.accessToken,
                          refreshToken = t.refreshToken,
  ```
- replace:
  ```kotlin
                      BridgePayload(
                          accessToken = stored.accessToken,
                          refreshToken = stored.refreshToken,
  ```
- why_gate_green: 该判据（L805-835）只查「调了 `promoteFromBridge`、不在死分支、返回值赋给 `t`」，从不看 `materializeBridgeFile` 的 `BridgePayload` 实参取自 `t.*` 还是 `stored.*`。
- 缺陷后果：沙箱侧刷新出的新 token 被旧 `stored.*` 覆写回桥接文件 → 沙箱 MCP 调用立刻因旧令牌失效中断。

**G04-A5 · da1034-A1 A1（`check_backup_scope`：`DENIED_DIRS` 守卫）· family-hole**

- file: `src/android/app/src/main/java/com/rikkaminis/app/backup/ArtifactBackupScope.kt`
- find:
  ```kotlin
                      if (child.name in DENIED_DIRS) continue
  ```
- replace:
  ```kotlin
                      if (false && child.name in DENIED_DIRS) continue
  ```
- why_gate_green: `check_backup_scope`（L1546）用 `r"if\s*\([^{};\n]*\bDENIED_DIRS\b[^{};\n]*\)\s*continue\b"`，`[^{};\n]*` 欣然接受 `false && child.name in `，且此处未挂死谓词检查。
- 缺陷后果：`oauth/` 子目录不被跳过 → 明文 `access_token` / `refresh_token` / `client_secret` 进备份 ZIP（`redactMcpArtifactEntry` 只脱敏 `mcpServers` 那一份）。

**G04-A6 · dv20-a1 R2（`check_artifact_redaction`：`redactMcpArtifactEntry`）· dataflow**

- file: `src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt`
- find:
  ```kotlin
              val obj = JSONObject(String(bytes, Charsets.UTF_8))
              stripExportedMcpSecrets(obj)
              obj.toString(2).toByteArray(Charsets.UTF_8)
  ```
- replace:
  ```kotlin
              val obj = JSONObject(String(bytes, Charsets.UTF_8))
              stripExportedMcpSecrets(obj)
              bytes
  ```
- why_gate_green: R2（L1944-1955）只查 `"stripExportedMcpSecrets(" in rbody` + catch 返回 `null`，不看 try 的返回表达式来自脱敏后的 `obj` 还是原始入参 `bytes`。
- 缺陷后果：「不含密钥导出」的备份里 `mcp/servers.json` 仍带全部明文密钥。

### envvar_config_group_check.py

**G10-A1 · C1（`check_fields_registration`）· dataflow**

- file: `src/android/app/src/main/java/com/rikkaminis/app/config/collections/EnvVarsCollection.kt`
- find:
  ```kotlin
          return listOf(
              createdAtField(forId),
              noteField(forId),
              groupField(forId),
  ```
- replace:
  ```kotlin
          if (true) return emptyList<ConfigField>().also { groupField(forId) }
          return listOf(
              createdAtField(forId),
              noteField(forId),
              groupField(forId),
  ```
- why_gate_green: `check_fields_registration`（L207-215）取 `fields(forId)` 块后只做子串判断 `if "groupField(forId)" not in body`；不看是否在**返回列表的元素位置**，也不看前面有没有早退 `return emptyList()`。
- 缺陷后果：`minis-config get/set envvars.<KEY>.group` 全部 `ConfigError.UnknownPath`。

**G10-A2 · C2（`check_group_path`）· dataflow**

- file: 同上
- find:
  ```kotlin
              reader = {
                  val e = repo.entries.value.firstOrNull { it.key == key }
                  if (e == null) ConfigValue.Null
                  else ConfigValue.Str(e.group)
              },
  ```
- replace:
  ```kotlin
              reader = {
                  val e = repo.entries.value.firstOrNull { it.key == key }
                  if (e == null) ConfigValue.Null
                  else ConfigValue.Null.also { ConfigValue.Str(e.group) }
              },
  ```
- why_gate_green: `check_group_path`（L273-289）只 `re.search(r"ConfigValue\.Str\(\s*[\w.]+\.group\s*\)", body)` + `"repo." in body`；没有剥 `.also { }` 这类**丢弃壳**，子串命中即绿。
- 缺陷后果：读面恒返回 `null`，设置的分组在 `minis-config get` 上不可见（write-only 回退）。

**G10-A3 · C4（`check_add_calls_context_limit_helper`）· dataflow**

- file: `src/android/app/src/main/java/com/rikkaminis/app/config/collections/GroupsCollection.kt`
- find:
  ```kotlin
          applyContextLimitWrite(g, contextLimit ?: 0)
  ```
- replace:
  ```kotlin
          applyContextLimitWrite(g, 0.also { contextLimit })
  ```
- why_gate_green: 该判据（L348-365）先派生变量名 `contextLimit`，再对 `applyContextLimitWrite(...)` 的实参文本做子串判断 `if "contextLimit" not in args`；`0.also { contextLimit }` 含该子串 ⇒ 判绿。
- 缺陷后果：`minis-config add groups '{"context_limit_tokens":64000}'` 建出的组丢掉 64K 窗口（写 0 且不记 `lastContextLimitTokens`）。

**G10-A4 · C5（`check_groups_summary_keys`）· dataflow**

- file: `src/android/app/src/main/java/com/rikkaminis/app/config/ConfigBuiltins.kt`
- find:
  ```kotlin
                                      "default_thinking_level" to (g.defaultThinkingLevel?.let {
                                          ConfigValue.Str(thinkingLevelToToken(it))
                                      } ?: ConfigValue.Null),
                                      "context_limit_tokens" to (g.contextLimitTokens?.let {
                                          ConfigValue.Int(it)
                                      } ?: ConfigValue.Null),
  ```
- replace: 两个键的值都换成 `ConfigValue.Null`
- why_gate_green: C5（L376-379）只查 `'"default_thinking_level" to' not in block` 与 `'"context_limit_tokens" to' not in block` —— 连 `to` 后面一个字符都不看。
- 缺陷后果：`minis-config get groups` 里这两个字段恒 `null`，又一次退化成 write-only。

**G10-A5 · C8（`check_backup_scope_envvars`）· order**

- file: `src/android/app/src/main/java/com/rikkaminis/app/backup/ConfigBackup.kt`
- find:
  ```kotlin
          "envvars",      // env-var privacy mode toggle
      )
  ```
- replace:
  ```kotlin
          "envvars",      // env-var privacy mode toggle
      ) - "envvars"
  ```
- why_gate_green: `backed_up_scopes_block`（L419-426）用 `extract_paren_block` 只取 `setOf(` 到配对 `)` 之间的文本，C8（L433）只查 `'"envvars"' in block`；`)` 之后的 `- "envvars"` 在返回范围之外，门看不见。
- 缺陷后果：`BACKED_UP_SCOPES` 实际不含 `envvars` → 导出跳过、导入报 `outside backup scope`，迁移设备静默丢隐私开关。

**G10-A6 · C12（`check_sentinel_remap`）· family-hole**

- file: 同 G10-A5
- find:
  ```kotlin
                          val mapped = entryIdMap[old]
                              ?: old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }
  ```
- replace:
  ```kotlin
                          val mapped = entryIdMap[old]
                              ?: old.takeIf { (1 == 2) && SystemVoiceEntries.isSystemEntryId(it) }
  ```
- why_gate_green: C12（L520-529）用 `DEAD_PREDICATE_RE` / `DEAD_TAUTOLOGY_RE` 白名单匹配谓词文本，白名单只认字面 `false` / `true`；`(1 == 2)` 不在白名单 ⇒ 判绿。
- 缺陷后果：恢复备份时真实映射被跳过 → 所有虚拟系统语音条目被算作「丢失」。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

先做两件结构性的事，再逐条改判据：

1. **删自写词法器与裸花括号计数**，改用共享原语：`mask_comments` / `mask_code`（`_kotlin_lexer`）、`const_value` / `const_bool` / `if_blocks` / `enclosing_conditions` / `statement_start` / `expr_end`（`_kotlin_ast`）、`live_positions` / `guard_feasible` / `path_feasible` / `call_args` / `value_sources` / `peel_discarding` / `brace_depth`（`_kotlin_valueflow`）。词法/括号/优先级/续行全部由共享层负责，解析异常 fail-closed（`KotlinLexError` → 红，不再「解析不到就算过」）。
2. **活/死站点一律派生**：「出现在源码里」不再算数，必须出现在 `live_positions(...)` 里（即可达路径上），且谓词要过 `guard_feasible`（常量折叠后仍可成立）。

| # | 判据 | 旧形态（为何瞎） | 新形态（判效果） | 新臂 |
|---|---|---|---|---|
| 1 | mcp S1 `PURGE -> purge` | 200 字符窗内出现 `purge(` 即绿 | `if_blocks` + `guard_feasible` + `path_feasible` 得出 PURGE 分支的活站点；死守卫/不可达路径 → 红 | `dead-purge` |
| 2 | mcp C5a `clientSecret` 导入 | `"importPendingClientSecret(" in body` | `_import_sites(require_value=True)`：只认活站点 + 实参必须是 `server`（空串/常量实参红）+ 返回值必须被消费（`_value_consumed`） | `dropped-import` + `empty-server` |
| 3 | mcp C5b 次序 | `_first_pos` 取第一个匹配比大小 | 次序只在**活站点**之间比较；早退/死分支里的那次不算（`live_positions` + `_ordering_direction`） | `import-order` |
| 4 | mcp dv26-A2 payload 来源 | 只查「调了 `promoteFromBridge` 且赋给 `t`」 | `_derive_token_fields(readers)` 从提升赋值派生 token 字段名 → `_payload_feeds_from_promotion(body, persisted, token_fields)` 要求 `BridgePayload` 每个 token 实参取自派生字段（`stored.*` 红） | `payload-superseded` |
| 5 | mcp A1 `DENIED_DIRS` 跳过 | 正则容忍 `false && …` | `_skip_sites(code, guard)`：条件过 `const_value`/`const_bool` 折叠，折叠为「永不成立」的守卫进 dead 集合 → 红；外层死分支让内层不可达同样算死 | `dead-deny-guard` |
| 6 | mcp dv20-a1 R2 脱敏返回 | 只要出现 `stripExportedMcpSecrets(` + catch 返回 null | `_redacted_return(body)`：try 的返回表达式必须派生自脱敏载体（`obj` 的 `toString`/序列化），返回原始入参 `bytes` 红 | `batch5-t11 redactor arm` |
| 7 | envvar C1 字段登记 | `"groupField(forId)" in body` | `_registers_field(expr, ident)`：必须是列表构造的**顶层元素**（`value_sources`/`call_args`），且 `_return_sites` + `_return_guard` 证明该 return 无条件（早退 `return emptyList()` / `if (true)` 常量挡板 → 红） | `FIX-29` |
| 8 | envvar C2 reader 返回值 | `ConfigValue.Str(x.group)` 子串命中 | `peel_discarding(...)` 剥掉 `.also` / `?.let` / `?:` 等丢弃壳，剩下真正承载返回值的表达式才判；`Null.also { Str(...) }` 红 | `FIX-30` |
| 9 | envvar C4 实参 | 实参文本含 `contextLimit` 子串 | `call_args` + `split_top` 取顶层实参，用 `value_sources` 判定该标识符是否**决定**了这个实参（`0.also { contextLimit }` 红） | `FIX-31` |
| 10 | envvar C5 汇总值 | 只要键名 + `to` 出现 | `_summary_carrier(block)` 派生载体 + `_entry_value(block, key)` 按表达式边界取值，要求该键的值是活载体（`ConfigValue.Str(<g>.…)/Int`）；`ConfigValue.Null` 字面红。注：条目分隔逗号已按顶层切，防「兄弟键带载体蒙混」 | `FIX-32` |
| 11 | envvar C8 备份作用域 | `'"envvars"' in setOf(...)` 括号内子串 | 整个**表达式**参与判定：`setOf(...) - "envvars"` / `- setOf("envvars")` 这类减法链解析后成员被移除 → 红 | `FIX-33` |
| 12 | envvar C12 哨兵谓词 | 白名单只认字面 `false`/`true` | 谓词走共享 `const_value`/`const_bool` 折叠：`(1 == 2)` / `!true && …` / `… || true` / `x || !x` 一律折叠成常量 → 红 | `FIX-34` |

---

## ④ 四层证据

**L1 真树 + 自证臂**

```
python3 scripts/scan/mcp_oauth_lifecycle_check.py .            -> rc=0
python3 scripts/scan/envvar_config_group_check.py .            -> rc=0
python3 scripts/scan/mcp_oauth_lifecycle_check.py --selftest   -> MCP_OAUTH_LIFECYCLE_SELFTEST=PASS
   selftest batch5-t11 redactor arm (returns the unstripped bytes) -> 1 violation(s)
   selftest batch5-t11 arms (dead-purge/dropped-import/empty-server/import-order/payload-superseded/dead-deny-guard)
     -> 1/1/1/1/1/1 violation(s)      # 6 组臂逐条恰好一条违规
   selftest dv26-A2 bridge-promotion arms (8 条) -> 1 x 8      # 既有臂保持
python3 scripts/scan/envvar_config_group_check.py --selftest   -> ENVVAR_GROUP_SELFTEST=PASS
   13 criteria green（原 7 条 + FIX-29..34 六条）
```
共享原语自证：`KOTLIN_LEXER_SELFTEST=PASS` 20 臂 / `KOTLIN_AST_SELFTEST=PASS` 32 臂 / `KOTLIN_VALUEFLOW_SELFTEST=PASS` 26 臂。

**L2 原见证反攻**（`verify_batch5.py --mode full`，改一处跑一次门再 restore）

```
基线树（e145ec961）：mcp 6/6 BLIND_CONFIRMED · envvar 6/6 BLIND_CONFIRMED   (12/12 瞎)
本支树（557c5b57f）：
  [1/6] G04-A1 S1   PURGE 死守卫          -> CAUGHT (rc=1, exact)
  [2/6] G04-A2 C5a  clientSecret 死导入    -> CAUGHT (rc=1, exact)
  [3/6] G04-A3 C5b  clearTokens 次序       -> CAUGHT (rc=1, exact)
  [4/6] G04-A4 dv26 payload 用旧 stored.*  -> CAUGHT (rc=1, exact)
  [5/6] G04-A5 A1   DENIED_DIRS 死谓词     -> CAUGHT (rc=1, exact)
  [6/6] G04-A6 R2   脱敏后返回原始 bytes   -> CAUGHT (rc=1, exact)
  SUMMARY: {'CAUGHT': 6}     restored 6/6 True
  [1/6] G10-A1 C1   早退空列表 + 死登记    -> CAUGHT (rc=1, exact)
  [2/6] G10-A2 C2   Null.also 丢弃壳       -> CAUGHT (rc=1, exact)
  [3/6] G10-A3 C4   0.also { contextLimit }-> CAUGHT (rc=1, exact)
  [4/6] G10-A4 C5   汇总值全 Null          -> CAUGHT (rc=1, exact)
  [5/6] G10-A5 C8   ) - "envvars"          -> CAUGHT (rc=1, exact)
  [6/6] G10-A6 C12  (1 == 2) && …          -> CAUGHT (rc=1, exact)
  SUMMARY: {'CAUGHT': 6}     restored 6/6 True
```
`restore` 逐条校验 `True`（`probe/t11-final-mcp.json` / `probe/t11-final-envvar.json`），主克隆零污染（改与复原都在 worktree 内）。

**L3 邻门 + 全量**

```
邻门（本地真树，逐门独立进程）:
  backup_streaming_export_check rc=0   four_way_sync_check rc=0    i18n_check rc=0
  offload_host_path_check rc=0         provider_remember_key_check rc=0
  provider_write_rules_check rc=0      mcp_oauth_lifecycle_check rc=0
  envvar_config_group_check rc=0
全量 110 门：以 §⑤ 的分支 CI 日志为准（`Summary: 112 passed, 0 failed`）。
  说明：本地整跑 scan.sh 在 13 支并发下不可行（卡在第 16 门；逐门循环实测 50s+/门、
        105 门需 ~1.5h，与 CI 抢 CPU），故本地只做邻门 + 两门本身，全量交给 CI 的同一棵树。
        `scan.sh` 的 110 次调用里 5 门（calendar_events / tts_voice / outcome_known /
        dao_loadmessages / router_lock）用 `--root` 形态，其余用位置参数。
```

**L4 `test_scan.py` 全量夹具**

```
46 个 test_* 全跑到：PASS=344  FAIL=0
分块逐函数（沙箱并发下整跑会被杀）：
  four_way/i18n/derive/entity/enum/boundary/legacy/debug 33 · 中段 79 · readonly+exec+scheduled 20
  auto_approve 2 · xproc/thinking/da1017 29 · da1016..1020 62 · da1021..1024 68
  da1025/1026/trace_step/dv16 25 · da1046/dv27 6 · real_repo 20     合计 344
注：分块器首版把 REPO_ROOT 传错（test_scan 从 sys.argv[1] 派生），导致 auto_approve 假红；
    修正 argv 后复跑转绿 —— 是装置问题，不是门的问题。
```

---

## ⑤ CI

run **#2693** / run id **40212936544** / job **121227878747** / attempt **1** / `conclusion = success`
head_sha `557c5b57fc9aea566c408c0d68b51ed3c7d18026` = 本地 `git rev-parse HEAD`（逐字符一致）
时间 2026-10-25T16:42:08Z → 17:00:47Z（18m39s）· 26 步，唯一非 success = `Publish to Releases` skipped（main 门控，符合预期）

日志 1,485,982 B 逐条核：

| 判据 | 实测 |
|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 条（L1019，带 `║ ` 框线前缀） |
| `ALL 344 CASES PASS` | 恰 1 条 |
| ` e: `（Kotlin 编译错误） | **0** |
| 含 `FAILED` 的行 | 6 条，逐条确认全是 `PASSED` 用例名子串（如 `slot denied produces FAILED without calling provider PASSED`） |
| 本支两门 | `━━━ [98/112] MCP OAuth lifecycle ━━━` → `PASS mcp_oauth_lifecycle_check (…17 form field state(s) keyed on the entry)`；`━━━ [103/112] envvar_config_group_check ━━━` → `envvar_config_group_check: OK (13 criteria green)` |
| 自证臂在 CI 里被见证 | `✅ mcp_oauth_lifecycle_check selftest has teeth (MCP_OAUTH_LIFECYCLE_SELFTEST=PASS)` + `real tree green`；`✅ envvar_config_group_check selftest has teeth (ENVVAR_GROUP_SELFTEST=PASS)` + `real tree green`（L1347-1359） |

结论：**分支 CI 绿 = 确定性停止节点**。不合并 main（起手权在用户/主会话）。

---

## ⑥ 残留

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | `_imports_pending_secret` 的委托跳数固定 `hops=1`（同文件内一跳） | 出现「`importPendingClientSecret` 再委托第二层」且第二层被遮蔽 | 把 `hops` 改成派生（沿同文件调用图 BFS 到固定点，带环检测） | 现实现只有一跳，无触发路径；提前支持等于给未知形态加装置（修复门 P2） |
| R2 | 数值谓词折叠覆盖 `==/!=/</>/<=/>=` 与 `&&`/`\|\|`/`!`；**算术恒等式**（`(1+1==2)`）不折 | 有人用算术恒等式做死谓词 | 在共享 `_kotlin_ast.const_value` 加算术折叠（同优先级表） | 共享层改动属主会话，本支禁改；且算术死谓词在仓内 0 例。**方向安全**：不折 = 视作活守卫 = 门可能漏判，故登记为待升级而非「已覆盖」 |
| R3 | `_summary_carrier` 依赖 `"name" to <x>.name` 形状派生载体 | 汇总块改名/改用 `mapOf` 之外的构造 | 载体派生改为「值来源集合 ⊆ 该块内所有 `<ident>.<field>`」的并集推断 | 当前 `ConfigBuiltins` 形状稳定；改法会抬高误报面，需真树验证 |
| R4 | C8 只解析 `setOf(...)` 的**减法链**（`- "x"` / `- setOf(...)`） | 出现 `+ "x"` 再 `- "x"` 或变量拼接 | 把作用域表达式交给共享 `value_sources` 做集合运算求值 | 现仓 0 例；且失败方向是红（可解析失败 fail-closed） |
| R5 | 逐臂计数（`... -> 1/1/1/1/1/1` 这类逐条读数）只在本地可见，CI 日志只有「marker + teeth + 真树绿」两行 | 有人在 CI 里想核对「哪条臂失效」 | 门自证时把每条臂的 PASS/FAIL 以 `::error::` 注解输出（GitHub Actions 会把注解提到日志顶部） | 现装置已能判「门被掏空」（marker 缺失即红），逐臂在 CI 的收益尚未到 P1；登记待升级 |
| R6 | 本地整跑 `scan.sh` 在 13 支并发下不可行（卡在第 16 门，逐门 50s+/门） | 需要离线全量复跑时 | 用 `scan.sh` 的 argv 清单串行逐门跑（脚本已给出，§⑧ 第 3 条），或等会话空闲窗 | 全量权威证据由 CI（同一棵树）提供；本地重复一遍只增加负载（GLOBAL「重活串行」纪律） |

---

## ⑦ `test_scan.py` 注册项

**无需新增**——两门在基线 `test_scan.py`（冻结面）里已注册，CI 已实证其生效：

```python
# scripts/scan/test_scan.py:2880
("mcp_oauth_lifecycle_check", "MCP_OAUTH_LIFECYCLE_SELFTEST=PASS", (), (REPO_ROOT,))
# scripts/scan/test_scan.py:2934
("envvar_config_group_check", "ENVVAR_GROUP_SELFTEST=PASS", (), (REPO_ROOT,))
```

CI 日志对应输出（run #2693）：

```
  ✅ mcp_oauth_lifecycle_check selftest has teeth (MCP_OAUTH_LIFECYCLE_SELFTEST=PASS)
  ✅ mcp_oauth_lifecycle_check real tree green
  ✅ envvar_config_group_check selftest has teeth (ENVVAR_GROUP_SELFTEST=PASS)
  ✅ envvar_config_group_check real tree green
```

注：注册项带的是「标记串」断言 —— 它证明臂存在且通过，不证明每条臂都恰好一条违规（逐条读数见 §④ L1）。

- 新臂数：**13**（mcp 7 = 6 组 + 脱敏单臂；envvar 6 = FIX-29..34）。既有臂：mcp dv26-A2 bridge-promotion 8 臂、envvar 原 7 criteria 均保持。
- 标记串取自两门 `--selftest` 的最后一行为准（上面 §④ L1 已引）。
- 若要升级到「逐臂可查」（R5）：把每臂结果以 `::error::` 注解输出，或在注册项旁加一条 `--selftest` 全文断言（断言 `1/1/1/1/1/1`），防「标记留下、臂被掏空」。

---

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t11
# 1) 两门真树 + 自证
python3 scripts/scan/mcp_oauth_lifecycle_check.py .            ; echo rc=$?
python3 scripts/scan/envvar_config_group_check.py .            ; echo rc=$?
python3 scripts/scan/mcp_oauth_lifecycle_check.py --selftest
python3 scripts/scan/envvar_config_group_check.py  --selftest
# 2) 原见证反攻（基线/本支各一遍）
cd /var/minis/shared/work/meta-audit-v5/batch5
python3 verify_batch5.py --witnesses witnesses-B.json --repo wt/t11 --mode full \
        --only mcp_oauth_lifecycle_check.py --out probe/t11-final-mcp.json
python3 verify_batch5.py --witnesses witnesses-B.json --repo wt/t11 --mode full \
        --only envvar_config_group_check.py --out probe/t11-final-envvar.json
# 3) 全量逐门（沙箱并发下用逐门循环替代整跑 scan.sh）
for g in scripts/scan/*_check.py scripts/scan/*_guard.py; do
  ( cd wt/t11 && python3 "$g" . >/dev/null 2>&1; echo "$g rc=$?" )
done
# 4) test_scan 全量夹具（整跑易被杀，用分块器逐函数）
python3 /tmp/chunk_test_scan.py            # 走 /tmp/t_names.txt 全部 46 个函数
```
