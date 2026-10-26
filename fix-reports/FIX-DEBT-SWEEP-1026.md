# FIX-DEBT-SWEEP-1026 — 清三笔已登记债务（#2 / #3 / #5）

| 项 | 值 |
|---|---|
| 分支 | `fix/debt-sweep-1026` |
| 基线 | main `627f23f2eda15a8964d50ab7dcfe62b0fb88b177`（origin/main 一致） |
| worktree | `/var/minis/shared/work/debt-1026/wt/sweep` |
| 改动面 | 4 个生产 `.kt` + 3 个门脚本的豁免表（+ 两个 SELFTEST 探针表，见 §5） |
| 生产代码 | `WebPreviewShortcut.kt` / `ChatPromptAndTools.kt` / `WeatherManager.kt` / `WeatherOffloadHandler.kt` |
| 冻结面 | `scan.sh` / `test_scan.py` / 共享原语 `_kotlin_*` 零触碰；门 112 / 夹具 344 不变 |
| 停止节点 | 分支 CI 绿（不减不合并 main） |

三笔的共同性质：**都是门脚本 allowlist / exemption 里的条目，条目 reason 自己写着「修好后必须同步删除」**。改生产代码与删条目必须同支，否则门红（ROT / exact 判据）。

---

## 1. 结论速览

| # | 债务 | 生产改动 | 门豁免 | 独立证据 |
|---|---|---|---|---|
| #2 | `WebPreviewShortcut` 用 `Uri.parse(url).path` 截断 `file://` 路径 | 改走 `decodeUriPath(url.removePrefix("file://"))` | `file_uri_parse_guard_check.ALLOWLIST` → `{}` | kotlinc 实跑：`report#1.html` 旧得到 `/var/minis/browser/report`，新得到完整名（PROBE2=PASS） |
| #3 | 图片附件裸 `readBytes()` 无上限 | 改走 `readSafBytes(..., MAX_RAW_IMAGE_ATTACHMENT_BYTES = 64 MiB)`，`TooLarge`/`Failed` 各自一行日志 + `continue` | `filepreview_print_bounded_check.SAF_EXEMPTIONS` → `{}` | 真实 helper（仓库源码编译）跑 8 条断言：恰好 cap 接受、超 1 字节拒绝（null 而非截断）、声明 65 MiB 预拒（PROBE3=PASS） |
| #5 | weather legacy 路径返回人类文本（第二出口）+ WMO 表双份 | legacy 改走 `fetchWeatherJson` → `reportBlock(24h/7d)` → `formatBody`；`fetchWeather`/`formatWeatherResponse` 删除；表改 `internal` 复用 | `offload_cli_args_check.PAYLOAD_DELEGATE_EXEMPT` → `{}` | 跨包可见性实跑：`sandbox.offload` 位置以外的文件调用 `WeatherManager.weatherCodeToDescription` 5/5 表项一致（PROBE5=PASS） |

**豁免删除不是顺手清理**：把旧豁免表重新注回同一棵（已修好的）树，三门各自当场变红 —— 见 §5 的反证表。

---

## 2. #2 WebPreviewShortcut `file://` 路径截断（backlog §1023-H）

### 2.1 修前证据

`WebPreviewShortcut.kt:50`

```kotlin
val absPath = Uri.parse(url).path.orEmpty()
```

`url` 由上游裸拼 `"file://" + absolutePath` 产生，`Uri.parse(...).path` 会重新套用通用 URI 分隔符语义：

| url | 旧 `.path` | 修后 `decodeUriPath(...)` |
|---|---|---|
| `file:///var/minis/browser/report#1.html` | `/var/minis/browser/report`（**fragment 截断**） | `/var/minis/browser/report#1.html` |
| `file:///var/minis/browser/v1?draft.html` | `/var/minis/browser/v1`（**query 截断**） | `/var/minis/browser/v1?draft.html` |
| `file:///var/minis/browser/snake.html` | 同上 | 同上（无变化） |
| `file:///var/minis/browser/a%20b.html` | `/var/minis/browser/a b.html` | 同上（`%XX` 语义不变） |
| `file:///var/minis/browser/c++_primer.html` | 原样（`+` 不转空格） | 同上（无回归） |

截断后深链指向不存在的路径 → 快捷方式静默失效（只在 `/var/minis/logs` 留一行 warning）。触发面：`WebPreviewBottomSheet.kt:216` 已保证 `holder.currentUrl.startsWith("file://")`，且 `WEBAPP_PIN_ENTRY_ENABLED = false`（`WebPreviewBottomSheet.kt:89`）⇒ 当前 **latent**，P2。

### 2.2 修法

与同族两处正解完全同形（`StreamingMarkdownText.kt:1802`、`WebPreviewBottomSheet.kt:487`）：

```kotlin
import com.rikkaminis.app.ui.chat.decodeUriPath
...
val absPath = decodeUriPath(url.removePrefix("file://"))
```

`Uri` import 保留（同文件仍用 `Uri.Builder` 构造深链）。`Uri.parse(url).path` 在该文件归零。

### 2.3 修后证据

- 实测：`PROBE2=PASS`（5 条 url，`#`/`?` 两条旧值截断、新值完整；`%20` 与 `+` 两条新旧一致 = 无回归）。
- 门：`file_uri_parse_guard_check` 真树 `13 passed, 0 failed`，`GATE=GREEN`；`--selftest` `FILE_URI_PARSE_GUARD_SELFTEST=PASS (25 fixtures)`（含 F4b 深链构造专项仍绿 —— 改后 `absPath.removePrefix("/var/minis")` 链路未动）。
- 反证：把旧 `ALLOWLIST` 注回同一棵树 → `F4 allowlist exact for WebPreviewShortcut.kt` 红（"allowlisted for 1 occurrence(s) but found 0 — fix it and REMOVE the allowlist entry"）。

---

## 3. #3 图片附件裸 `readBytes()`（backlog B-07）

### 3.1 修前证据

`ChatPromptAndTools.kt:1636`（`ChatViewModel.prepareUserAttachments` 图片分支）

```kotlin
val rawBytes = try {
    context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
} catch (e: Exception) { ... null } ?: continue
```

无字节上限；紧邻的非图片分支（`:1713` 起）早已改流式 `copyTo`，`:1697` 注释自认「400MB 文件 `readBytes()` 会导致 Pixel 4a OOM」。门读数：`[B-07 exempt] app/ui/chat/ChatPromptAndTools.kt — chat image attachment read with no cap`（SAF 决策 10 条）。

### 3.2 修法

复用 settings 侧现成有界入口（`ui/settings/SafImportRead.kt`，sealed 结果）：

```kotlin
val rawBytes = when (
    val read = readSafBytes(
        context.contentResolver,
        attachment.uri,
        MAX_RAW_IMAGE_ATTACHMENT_BYTES,
    )
) {
    is SafRead.Ok -> read.bytes
    SafRead.TooLarge -> { Log.w(ChatViewModel.TAG, "… over the $MAX…-byte cap; skipped"); continue }
    SafRead.Failed -> { Log.w(ChatViewModel.TAG, "image read failed for …; skipped"); continue }
}
```

- `private const val MAX_RAW_IMAGE_ATTACHMENT_BYTES = 64L * 1024 * 1024`（文件顶层，带理由 KDoc：48MP RAW 远低于其五分之一；只拦"伪装成图片的巨型文件"）。
- 日志沿用本文件惯例 `Log.w(ChatViewModel.TAG, ...)`（非 `AppLogger`）；`TooLarge` 与 `Failed` **各留一行**，异常路径没有变静默。
- 语义：超限 → `continue`（跳过该附件，与修正前的异常路径一致），**不截断**。

**`readSafBytes` 的 cap 实现细节（读源码 + 实测双重确认，非猜测）**：

1. `querySafSize(resolver, uri)` 读 `OpenableColumns.SIZE`，`declaredSizeExceeds(declared, cap)` 在**读到第一个字节之前**就拒（provider 报大即拒）；
2. 流式 `readAtMost(input, cap)`：每次只请求 `cap - total + 1` 字节，用**探测多读 1 字节**判超限 —— 因此恰好 `cap` 字节的文件被完整接受（若用 `while (total < cap)` 会被静默截断），超 1 字节返回 `null`；
3. provider 不报 size（`null`）时不预拒，但流式上界仍生效 —— 双保险，不依赖 provider 诚实。

### 3.3 修后证据

- `PROBE3=PASS`（8 条断言，全部跑**仓库源码编译出来的** helper，不是复刻件）：15B under → 15；16B AT cap → 16；17B → `null`；空流 → 0；1 MiB 图 under 64 MiB → 接受；声明 65 MiB → 预拒 `true`；声明 63 MiB → `false`；声明未知 → `false`（不误伤）。
- 门：`filepreview_print_bounded_check` 真树 `rc=0`，SAF 段 `9 decision(s) checked` / `2 read site(s) checked`，图片分支的 `B-07 exempt` 行**消失**（`ChatPromptAndTools.kt` 只剩非图片分支的 `[B-07 bounded] … streaming copy`）；`--selftest` 全绿（含 15 条 `SAF …` 臂，其中 `stale exemption`、`handler/helper` 各臂仍按原判据红）。
- 反证：把旧 `SAF_EXEMPTIONS` 注回同树 → ROT：`app/ui/chat/ChatPromptAndTools.kt: exemption 'openInputStream(attachment.uri)?.use { it.readBytes()' no longer matches any site — a stale whitelist would silently cover the next hole`。

---

## 4. #5 Weather 双路径收敛（backlog 1045-A07）

### 4.1 修前证据

- `WeatherOffloadHandler.handleLegacy` → `WeatherManager.fetchWeather()` → `formatWeatherResponse()`（`WeatherManager.kt:77` private，**全仓仅被 fetchWeather 调用**）返回人类可读拼装文本，走 `formatBody(text)` —— 与五个 subcommand 的 JSON 信封是**两个出口**。
- `weatherCodeToDescription` 双份：`WeatherManager.kt:163`（private）+ `WeatherOffloadHandler.kt:247`（private，注释自认 "Duplicated from WeatherManager"）。
- 门读数：`PAYLOAD_DELEGATE_EXEMPT` 唯一一条，reason 自认 "straightforward follow-up"。
- 文本格式依赖排查（动手前）：`grep -rn "android-weather" src/` → 仅 `MinisApp.kt:672`（注册）、`OffloadHandlerCatalog.kt:46`（名字表）、`ChatPromptAndTools.kt:1373`（工具描述 "Open-Meteo forecast (current + hourly + daily)"，**不承诺文本格式**）；`strings.xml` / assets prompt 0 命中；`src/test` 下对 legacy 文本输出 0 命中（`OffloadArgsValueBindingTest` 只测位置参数解析，`SensitiveCommandPolicyTest` 只测脱敏）。

### 4.2 修法

1. `WeatherManager.weatherCodeToDescription` → `internal`（同模块跨包可见），Handler 内 3 处调用点改 `WeatherManager.weatherCodeToDescription(...)`，删除 Handler 的重复副本。
2. `handleLegacy` 改走同一出口：
   ```kotlin
   val json = try { WeatherManager.fetchWeatherJson(lat, lon) } catch (e: Throwable) { AppLogger.warning(...); null }
       ?: return NativeOffloadResult(1, augmentedError(WeatherManager.lastError, args))
   val data = reportBlock(json, LEGACY_MAX_HOURS, LEGACY_MAX_DAYS, lat, lon)
   return NativeOffloadResult(0, OffloadOutput.formatBody(data.toString(2), args) + "\n")
   ```
   `LEGACY_MAX_HOURS = 24` / `LEGACY_MAX_DAYS = 7`（命名常量 + 理由：legacy 形态没有 `--hours/--days`，取 subcommand 同默认值 ⇒ legacy body 就是 `report` 的默认 body）。
3. 删 `fetchWeather` + `formatWeatherResponse`（死代码，零调用方 —— `grep -rn "fetchWeather\b\|formatWeatherResponse" src/` 只剩两行历史说明注释）。
4. `PAYLOAD_DELEGATE_EXEMPT` → `{}`。
5. **HELP 与 KDoc 同步**：legacy 行由 `Plain-text combined report` 改为 `Combined report, JSON body (same as \`report\`)`；类 KDoc 的 "Backwards compatibility" 段写明命令形态不变、body 变 JSON、文本渲染器与重复表已删除。命令形态（`<lat> <lon>`、错误码、`--lng` 别名）零改动。

### 4.3 修后证据

- 门：`offload_cli_args_check` 真树 `rc=0`，`… cross-file payload delegates registered (Z1 derived members, Z2 no String delegate in a payload file, Z3 exemptions still needed, Z4 derivation alive)` 全绿 —— 即删表后**派生仍非空**（Z4 未失效）、该 handler 不再把别的文件的句子当 payload。
- `--selftest`：`OFFLOAD_CLI_ARGS_SELFTEST=PASS`（`BATCH6_T01_ARMS=11` / `R3_T14_ARMS=14`），Z1–Z4 的 5 条臂仍各自按原判据红。
- 反证：把旧 `PAYLOAD_DELEGATE_EXEMPT` 注回同树 → `Z3: exemption no longer needed`（`WeatherOffloadHandler.kt`）。
- `PROBE5=PASS`：`WeatherManager` 与探针分处两个包（`offload` / `probe`，handler 在第三个包 `sandbox.offload`）→ `internal` 可见性成立；表内容 5/5（0→Clear sky / 3→Overcast / 63→Moderate rain / 95→Thunderstorm / 1234→Unknown (code 1234)）与删除副本前的字符串一致。

**行为变化声明**：legacy `android-weather <lat> <lon>` 的输出从人类文本 → **JSON**（`data` 字段内容 = `report` 默认切片：current + hourly 24 + daily 7 + alerts + location）。模型侧只是拿到更结构化的同一批数据；已在 `src/` 全量确认无文本格式依赖（§4.1）。

---

## 5. 门豁免删除前后读数（含反证）

| 门 | 判据 | 修前 | 修后（真树） | 反证：旧豁免注回 |
|---|---|---|---|---|
| `file_uri_parse_guard_check` | F4 allowlist exact | `Summary: 14 passed, 0 failed`；`F4 allowlist exact for WebPreviewShortcut.kt` 通过并打印 "exempted occurrence: …" | `Summary: 13 passed, 0 failed`；该判据行**消失**（表空，F4 只对未豁免站点报红） | `F4 allowlist exact for WebPreviewShortcut.kt` **红**："allowlisted for 1 occurrence(s) but found 0 — fix it and REMOVE the allowlist entry" |
| `filepreview_print_bounded_check` | B-07 SAF sweep | `[B-07 exempt] app/ui/chat/ChatPromptAndTools.kt — chat image attachment read with no cap…`；`10 decision(s)` | 该 exempt 行消失，仅余 `[B-07 bounded] … streaming copy`；`9 decision(s)` | ROT **红**："exemption … no longer matches any site — a stale whitelist would silently cover the next hole" |
| `offload_cli_args_check` | Z1–Z4 payload delegates | `PAYLOAD_DELEGATE_EXEMPT` 含 `WeatherOffloadHandler.kt`（Z3 通过 = 豁免仍需要） | Z1–Z4 全绿，表空 | `Z3: exemption no longer needed` **红** |

**为什么两门要留一个 SELFTEST 探针表**：ROT / exact 判据的**自测臂**（"条目还在但站点没了 → 必须红"）需要一条活条目才有主体。生产表清空后，把这条主体移到 `SELFTEST_ALLOWLIST` / `SELFTEST_SAF_EXEMPTIONS`，并用可选参数注入（`analyse(..., allowlist=None)` / `check_saf_imports(root, exemptions=None)`，默认取生产表）。**真树运行永不读探针表** —— 反证段用的正是把探针表传给真树分析，得到的红就是"生产表若不清空会长什么样"。

---

## 6. 验证记录（本地层级）

| 层 | 手段 | 结果 |
|---|---|---|
| L1 门自测 | `--selftest` ×3 | `FILE_URI_PARSE_GUARD_SELFTEST=PASS (25 fixtures)` / `FILEPREVIEW_PRINT_BOUNDED_SELFTEST=PASS`（含 15 条 SAF 臂）/ `OFFLOAD_CLI_ARGS_SELFTEST=PASS` |
| L1' 真树 | 三门 `python3 <gate>.py .` | all `rc=0`（13 / SAF 8 行 PASS / 单行 PASS） |
| L3 邻门抽查 | `file_preview_url_encode_check` / `offload_host_path_check` / `offload_media_b64_wire_check` / `share_dir_prune_check` / `webapp_asset_url_cleanup_check`（后加：引用 `WebPreviewShortcut` 的 arm8b） | 5/5 `rc=0` |
| L3' 同文件相关门 | `memory_gate_provider_fields_check` / `outcome_known_chain_check` / `session_provider_enabled_check` / `deeplink_settings_routes_check` | 4/4 `rc=0` |
| L2 独立探针 | kotlinc 2.0.21 实编译真源码 + JUnit-less main | `PROBE2=PASS` / `PROBE3=PASS` / `PROBE5=PASS`（装置 `/var/minis/workspace/debt1026-probe/`） |
| 豁免反证 | 同树重注旧表跑三门判据 | 三门各自变红（§5 表） |
| 静态 | `grep '<<<<<<<'` 4 文件 0 命中；括号/import 配平目检；`fetchWeather\b\|formatWeatherResponse` 仅剩历史说明注释 | ✅ |
| 本地**未跑** | 全量 `scan.sh` / `test_scan.py` | 按纪律交给分支 CI |
| L4 | 分支 CI（dispatch + head_sha 核对 + 日志判读） | 见 §8 |

---

## 7. 残留（各带三件：触发条件 / 触发后方案 / 搁置理由）

**R1 — `%XX` 与 ChatScreen 上游裸拼 `file://`**
- 触发条件：`WEBAPP_PIN_ENTRY_ENABLED` 打开（或任何新的"从 file:// 深链打开预览"入口出现），且文件名含 `#`/`?`/`%`。
- 触发后方案：上游构造侧统一用 `ui/sandbox/FilePreviewScreen.kt:1758` 的 `fileUrlFor` + `encodeFileUrlPath`（先编码再拼），消费侧继续 `decodeUriPath`（当前已对称）；`ChatScreen.kt:1711/1775` 两处裸拼同批改。
- 搁置理由：本支改动面是消费侧（`WebPreviewShortcut`），构造侧是上游同族、跨两支半径；且入口当前 `false` ⇒ latent。登记进 backlog §1023-H。

**R2 — `WeatherManager` / `MediaPlayerManager` 等仍在用 `android.util.Log`**
- 触发条件：需要把 offload 侧日志纳入统一结构化/脱敏通道（例如排障要按 session 过滤 offload 日志）。
- 触发后方案：`offload/` + `sandbox/offload/` 的 `android.util.Log` 批量转 `AppLogger`（TAG 保留），并在对应门里加"offload 包禁止 android.util.Log"判据。
- 搁置理由：与本批三笔无关；范围不小（**纠正一处说法**：不是「全包唯一」，`offload/` 除 `WeatherManager` 还有 `MediaPlayerManager`，`sandbox/offload/` 另有 11+ 文件在用 `android.util.Log`）。

**R3 — `SafRead.Failed` 不再携带异常消息**
- 触发条件：真机出现"图片附件读不出来"且 `SafBoundedRead` TAG 的日志不足以定位（例如需要 provider 侧异常原文而不只是 `read failed for <uri>: <msg>`）。
- 触发后方案：把 `Failed` 改成 `Failed(reason: String?)`（sealed 契约变更，调用点 3 处 + 单测 2 处），或在聊天侧读 `SafBoundedRead` 日志补齐。
- 搁置理由：`readSafBytes` 内部已 `Log.w(TAG, "read failed for $uri: ${e.message}")`，信息没有丢，只是换到另一 TAG；改 sealed 契约属半径外扩张。**P2 登记**。

**R4 — `WeatherManager.lastError` 仍是 `@Volatile` 单槽**
- 触发条件：同一进程内并发发起两次 weather（两个会话同时调），错误信息可能互相覆盖。
- 触发后方案：`fetchWeatherJson` 返回 `Result<JSONObject>` 或把错误随返回携带（消除共享可变槽）。
- 搁置理由：并发 weather 调用的实际概率极低（工具调用串行在会话内、跨会话才有），且本次收敛未加重该缺陷（legacy 路径现在与 subcommand 同源，两者原本就这样）。**P2 登记**。

---

## 8. CI 与未验证项

- **L4 分支 CI**：`build-apk.yml` 手动 dispatch（`on: push` 只绑 main），`--expect <commit>` 核对 head_sha 逐字符一致，日志判读口径按惯例（`Summary: 112 passed, 0 failed` 恰一条 / `ALL 344 CASES PASS` 恰一条 / 门头 112 互异 / `^ e: ` 0 / 含 `FAILED` 的行逐条确认是 PASSED 用例名）。结果回填见文末「CI 读数」段。
- **未验证项（诚实声明）**：
  1. **真机行为未验证**（沙箱无 Android 运行时）：legacy `android-weather <lat> <lon>` 的 JSON 输出、图片附件超限被拒的 toast/日志、快捷方式深链含 `#` 的 pin → 点击 → 打开全链路，都需要真机确认。CI 只保证编译 + 单测 + 112 门。
  2. `readSafBytes` 在真实 ContentProvider 上的 `query(OpenableColumns.SIZE)` 行为（某些 provider 抛异常/返回 -1）沿用的是 settings 侧既有实现与既有单测，本支未新增 provider 形态覆盖。
  3. `decodeUriPath` 对 `%XX` 的解码语义与 `Uri.parse(...).path` 一致这一点用 `java.net.URI` 语义类比取证（Android `Uri` 同规则），未在真机 `Uri` 上跑同一组输入。

---

## CI 读数

（待分支 CI 结束后回填：run id / #编号 / attempt / head_sha / job / 关键日志行）
