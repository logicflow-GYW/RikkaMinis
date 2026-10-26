# FIX-BATCH5-t10 · 两条门判据改值流驱动（offload_host_path / entry_composite_key）

- **波次**：META-B5 第五批（门见证第二轮收口），支线 **t10**
- **基线**：`e145ec961`（= main `6ac77c6b2` + 共享原语 `_kotlin_valueflow`）；分支 `fix/b5-offhost-entrykey`
- **本支改动面**：`scripts/scan/offload_host_path_check.py`、`scripts/scan/entry_composite_key_guard_check.py` 两文件（+954/−230）
- **零改动面**：生产 `.kt` 0 行、`scan.sh` / `test_scan.py` / `_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py` / 其他门脚本 0 触碰

## 1. 门与轴

B 侧报告对 24 门给出 72 条盲见证，其中本支负责 2 门共 **12 条**：

| 门 | 见证数 | 轴分布 | 判据面 |
|---|---:|---|---|
| `offload_host_path_check.py` | 6 | dataflow 5 · family-hole 1 | R1 裸 `File(<args>)`、R2 `Intent.parseUri` 消毒、R3 `play` 会话隔离、R4 Shizuku 命令操作数、R5 debug RPC 会话、R6 debug exec scope |
| `entry_composite_key_guard_check.py` | 6 | dataflow 4 · family-hole 2 | Arm1 `addEntry` 统一守卫、Arm2 `decideEntryAdd` 纯函数、Arm3 `ModelsCollection.add` 规范 id、Arm4 `modelsAdd` RPC 响应、Arm5 `modelsUpdate` fail-closed、Arm7 `check_resolver` 前缀判定 |

**共同病因（与 wide4 同族，第 N 次复发）**：判据锚「某标识符/某调用**存在**」，而不是「该计算**结果被消费**」。12 条见证全部是同一手法——**保留旧判据需要的每一个子串/锚点，只把值流或控制流掐断**：

- 把 resolver 调用 `glue` 进 `.also {}` / `.apply {}`（接收者仍是裸值，调用文本还在）；
- 把消毒/守卫/throw 塞进 `if (false) …`、`filter { false && … }`、`when (false) { … }` 这类恒假分支；
- 把会话 id / 路径 / scope 先算出来再**丢弃**；
- 把操作数从裸标识符换成表达式（`path.trim()`）绕过 `cmd\.add\(([A-Za-z_]\w*)\)`；
- 把 helper 掏空，只留签名与锚点子串。

## 2. 见证原文（file / find / replace / why_gate_green）

> 本节由 `probe/gen_report_sec2.py` 从 `witnesses-B.json` 机器生成，逐字对齐台账，不手抄。

### 2.1 `offload_host_path` — 尝试 03-1
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：R1 / R7 (`scan_bare_file` + `scan_photos_import_session_path`)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
        val src = PRootKernel.resolvePathForSession(request.sessionId, guestPath, context)
            ?: return importPathError(
                args, rawPath, "no_file",
                "No such file inside the sandbox: '$guestPath' is not reachable through any " +
                    "mounted tree. Check the path (relative paths resolve against the shell " +
                    "cwd) and that the file was written by this session.",
                guestPath,
            )
```

- **replace**：

```kotlin
        val src = File(rawPath.also { PRootKernel.resolvePathForSession(request.sessionId, guestPath, context) })
```

- **why_gate_green（门为什么瞎）**：1) 在 R1（`scan_bare_file` L683-687）中，`_file_call_args` 提取出 `File(...)` 的参数文本 `arg_s = "rawPath.also { PRootKernel.resolvePathForSession(request.sessionId, guestPath, context) }"`，`RESOLVER_CALL_RE.search(arg_s)` 命中 `PRootKernel.resolvePathForSession(`，触发 `continue` 直接跳过污染检查；2) 在 R7（L832-841）中，`re.search(r"\bval\s+(\w+)\s*=[^\n]*resolvePathForSession\s*\(", body)` 命中 `val src = File(rawPath.also { PRootKernel.resolvePathForSession(`，把 `src` 当作已解析变量，随后 `src.exists()` / `src.inputStream()` 全部合法通过。

- **why_defect_back（缺陷为什么回来了）**：沙箱内的 Agent 执行 `android-photos import --path /data/data/com.rikkaminis.app/shared_prefs/secret.xml`（或宿主任意私有数据库路径） → `PhotosOffloadHandler.cmdImport` 执行 `File(rawPath.also { ... })` → `.also` 丢弃 `resolvePathForSession` 返回的 `null` 并返回原始宿主路径 `rawPath` → 应用以自身 UID 权限直接打开宿主私有文件并导出到系统公共相册（MediaStore），造成完整的沙箱逃逸与任意私有文件窃取。

### 2.2 `offload_host_path` — 尝试 03-2
- **轴**：`family-hole` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：R2 (`scan_parse_uri` / `_parse_uri_hardening_missing`)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
                    IntentSanitizer.sanitize(this, context)
```

- **replace**：

```kotlin
                    if (false) IntentSanitizer.sanitize(this, context)
```

- **why_gate_green（门为什么瞎）**：`_parse_uri_hardening_missing`（L887-888）执行 `if re.search(r"\bIntentSanitizer\s*\.\s*sanitize\s*\(", window): return []`。它只做窗口内正则匹配，没有任何死代码分支检查，看到 `if (false) IntentSanitizer.sanitize(this, context)` 即返回空违规列表。

- **why_defect_back（缺陷为什么回来了）**：沙箱内或网页诱导触发 `android-open "intent://...#Intent;component=com.rikkaminis.app/.InternalActivity;i.flags=3;end"` → `OpenOffloadHandler.openUrl` 调用 `Intent.parseUri` 后跳过 `IntentSanitizer.sanitize` → 未清除 `component`、`selector`、`clipData` 与 `FLAG_GRANT_READ_URI_PERMISSION`，直接以应用自身身份启动未导出 Activity 或向外部应用泄露私有 FileProvider URI 读取权限。

### 2.3 `offload_host_path` — 尝试 03-3
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：R3 (`scan_player_session_path`)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PlayerOffloadHandler.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
                    val resolved = resolveMaybeRelative(path, request.cwd)
                    MediaPlayerManager.play(id, resolved, chatSessionId = request.sessionId, context = context)
```

- **replace**：

```kotlin
                    val resolved = resolveMaybeRelative(path, request.cwd)
                    MediaPlayerManager.play(id, path, chatSessionId = null.also { request.sessionId }, context = context)
```

- **why_gate_green（门为什么瞎）**：`scan_player_session_path`（L266-276）对 `PlayerOffloadHandler.kt` 仅执行两条子串检查：`if "request.sessionId" not in p_code:` 与 `if "resolveMaybeRelative(" not in p_code:`。补丁保留了这两个子串，门完全不检查 `MediaPlayerManager.play(...)` 的实参。

- **why_defect_back（缺陷为什么回来了）**：Agent 在会话 B 的沙箱工作目录 `/var/minis/workspace` 下执行 `android-player play --file note.mp3` 或 `/var/minis/workspace/note.mp3` → `MediaPlayerManager.play` 收到未拼接 `cwd` 的 `path` 且 `chatSessionId = null` → 1) 相对路径 `note.mp3` 直接解析失败报错；2) 绝对路径 `/var/minis/workspace/note.mp3` 因 `chatSessionId == null` 走全局 `resolveHostPath` 扫描所有会话目录，播放出会话 A 的同名私密录音文件。

### 2.4 `offload_host_path` — 尝试 03-4
- **轴**：`spelling` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：R4 (`scan_shizuku_paths` / `_command_operands`)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ShizukuOffloadHandler.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
        val cmd = mutableListOf("ls")
        if (args.hasFlag("long", "l")) cmd.add("-la") else cmd.add("-1")
        if (args.hasFlag("recursive", "r")) cmd.add("-R")
        cmd.add(host)
```

- **replace**：

```kotlin
        val cmd = mutableListOf("ls")
        if (args.hasFlag("long", "l")) cmd.add("-la") else cmd.add("-1")
        if (args.hasFlag("recursive", "r")) cmd.add("-R")
        cmd.add(path.trim())
```

- **why_gate_green（门为什么瞎）**：`_command_operands`（L337）使用 `re.finditer(r"\bcmd\.add\(\s*([A-Za-z_]\w*)\s*\)", body)` 提取操作数。当参数写成 `path.trim()` 时，括号内含有 `.trim()`，不匹配 `[A-Za-z_]\w*`。结果 `fileLs` 的 `operands` 列表只剩下 `["-la", "-1", "-R"]`（全以 `-` 开头被 L339 过滤），`operands` 为空（但 `filePullPush` 等其他函数非空故不触发 `checked == 0`），`bad` 为空，门判绿！

- **why_defect_back（缺陷为什么回来了）**：Agent 执行 `android-shizuku-cli file ls /var/minis/workspace` → `fileLs` 将未映射的客体路径 `/var/minis/workspace`（通过 `path.trim()`）直接传给宿主 ADB/root 权限的 Shizuku `ls` 进程 → 由于宿主根目录下不存在 `/var/minis/workspace`，命令报错 `No such file or directory`，沙箱内文件列表功能瘫痪。

### 2.5 `offload_host_path` — 尝试 03-5
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：R5 (`scan_debug_session_path`)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/debug/DebugRPCHandler.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
    private fun handleReadFile(params: JSONObject): JSONObject {
        val path = params.optString("path")
        if (path.isEmpty()) throw RPCException(-32602, "Invalid params: 'path' is required")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        // [dv16-A03] Session-scoped resolution — same shape as handleLS. A
        // session hit never falls back to the global resolver, so a `read`
        // from one session cannot pick up another session's same-named file.
        val sessionId = params.optString("sessionId", "").ifBlank { null }
        val hostFile = PRootKernel.resolvePathForSession(sessionId, path, context)
```

- **replace**：

```kotlin
    private fun handleReadFile(params: JSONObject): JSONObject {
        val path = params.optString("path")
        if (path.isEmpty()) throw RPCException(-32602, "Invalid params: 'path' is required")
        if (path.contains("..")) throw RPCException(-32602, "Invalid path: '..' not allowed")

        // [dv16-A03] Session-scoped resolution — same shape as handleLS. A
        // session hit never falls back to the global resolver, so a `read`
        // from one session cannot pick up another session's same-named file.
        val sessionId = params.optString("sessionId", "").ifBlank { null }
        val hostFile = PRootKernel.resolvePathForSession(null, path, context)
```

- **why_gate_green（门为什么瞎）**：`scan_debug_session_path`（L399-410）在 `handleReadFile` 的函数体中仅检查：1) `"resolveHostPath(" in body`（无）；2) `"resolvePathForSession(" not in body`（有）；3) `'optString("sessionId"' not in body`（有）。它完全不检查 `resolvePathForSession(` 的第一个参数是不是 `sessionId`。

- **why_defect_back（缺陷为什么回来了）**：会话 B 中的 Agent 调用 `minis-debug-cli read-file /var/minis/workspace/notes.txt`（期望读取会话 B 自己的文件，若不存在则报错） → `handleReadFile` 把 `null` 作为 `sessionId` 传给 `PRootKernel.resolvePathForSession(null, path, context)` → 触发全局 `resolveHostPath` 跨所有 `minis-sessions/<id>/` 搜索同名文件 → 把会话 A 的私密 `/var/minis/workspace/notes.txt` 内容返回给会话 B。

### 2.6 `offload_host_path` — 尝试 03-6
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：R6 (`scan_debug_exec_scope`)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/DebugOffloadHandler.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
        execScopeParams(sub, request.sessionId, request.cwd).forEach { (key, value) ->
            rpc.params.put(key, value)
        }
```

- **replace**：

```kotlin
        execScopeParams(sub, request.sessionId, request.cwd)
```

- **why_gate_green（门为什么瞎）**：`scan_debug_exec_scope`（L535-547）对 `handle` 函数体只检查 `"execScopeParams(" not in handle` 与 `"request.sessionId" not in handle`。删掉 `.forEach { ... rpc.params.put(key, value) }` 后，`execScopeParams(sub, request.sessionId, request.cwd)` 依然存在于 `handle` 中，门判绿。

- **why_defect_back（缺陷为什么回来了）**：沙箱内执行 `minis-debug-cli shizuku.exec ...` 或 `modelUse.exec` → `DebugOffloadHandler.handle` 计算出包含 `sessionId` 和 `cwd` 的 Map 后直接丢弃，未写入 `rpc.params` → 下游 `DebugRPCHandler.handleShizukuExec` / `handleModelUseExec` 收到的 `sessionId` 与 `cwd` 全为空，导致所有相对路径解析失败且会话隔离失效。

### 2.7 `entry_composite_key_guard` — 尝试 08-1
- **轴**：`family-hole` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：Arm 1 (`addEntry` unified guard)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
        if (!decision.allowed) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] addEntry skipped duplicate: " +
                    "${entry.providerInstanceId}/${entry.baseModel.id} already exists as ${decision.persistedId}",
            )
            return@synchronized decision.persistedId
        }
```

- **replace**：

```kotlin
        if (false && !decision.allowed) {
            return@synchronized decision.persistedId
        }
```

- **why_gate_green（门为什么瞎）**：`check_anchors`（L248-263）与 `check_absent`（L266-277）对 `fun addEntry(` 只做纯子串检查：`"decideEntryAdd(" in code`、`"decision.persistedId" in code`、`"?: compositeEntryKey(" in code` 且 `"!entry.isCustom" not in code`。`if (false && !decision.allowed)` 保留了全部 3 个锚点，门判绿。

- **why_defect_back（缺陷为什么回来了）**：用户在「添加自定义模型」页面（或多次并发刷新）添加了与现有条目具有相同 `(providerInstanceId, baseModel.id)` 的模型 → `addEntry` 虽然算出了 `decision.allowed == false`，但跳过早退分支继续执行 `config.modelEntries.add(entry)` 与 `saveConfig(config)` → 两条记录在持久化边界被归一化为同一个复合主键 `<instanceId>/<modelId>`，打开模型列表时 Compose `LazyColumn(key = { it.id })` 立即抛出 `IllegalArgumentException: Key was already used` 导致应用崩溃。

### 2.8 `entry_composite_key_guard` — 尝试 08-2
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：Arm 2 (`decideEntryAdd` pure helper)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
internal fun decideEntryAdd(
    providerInstanceId: String,
    modelId: String,
    existing: List<Pair<String, String>>,
): EntryAddDecision {
    val newKey = "$providerInstanceId/$modelId"
    val hit = existing.firstOrNull { it.second == newKey }
    return if (hit != null) EntryAddDecision(false, hit.first) else EntryAddDecision(true, newKey)
}
```

- **replace**：

```kotlin
internal fun decideEntryAdd(
    providerInstanceId: String,
    modelId: String,
    existing: List<Pair<String, String>>,
): EntryAddDecision {
    return EntryAddDecision(true, "$providerInstanceId/$modelId")
}
```

- **why_gate_green（门为什么瞎）**：Arm 2（L514-515）调用 `check_anchors(srcs["pr"], rels["pr"], None, "2", ARM2_ANCHORS, ...)`，只检查文件里是否存在子串 `"internal fun decideEntryAdd("`，完全不读取该函数的函数体。

- **why_defect_back（缺陷为什么回来了）**：任何通过 `addEntry` 添加重复 `(providerInstanceId, baseModel.id)` 的调用 → `decideEntryAdd` 恒返回 `allowed = true` → 重复条目被写入 `modelEntries` 并引发 `LazyColumn` 重复键崩溃及重启后条目静默蒸发。

### 2.9 `entry_composite_key_guard` — 尝试 08-3
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：Arm 3 (`ModelsCollection.add` canonical id return)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
        return repo.addEntry(entry)
```

- **replace**：

```kotlin
        if (false) return repo.addEntry(entry)
        repo.addEntry(entry)
        return entry.id
```

- **why_gate_green（门为什么瞎）**：Arm 3（L78-81, L516-517）只检查 `override fun add(` 的 `code_only(body)` 中包含子串 `"return repo.addEntry("` 和 `"ConfigError.AlreadyExists"`。`if (false) return repo.addEntry(entry)` 含有该子串，门直接判绿。

- **why_defect_back（缺陷为什么回来了）**：Agent 执行 `minis-config add models '{"instance_id":"...","model_id":"gpt-5.5"}'` → `ModelsCollection.add` 将模型持久化（落盘 ID 被归一化为 `<instanceId>/gpt-5.5`），但返回给 CLI 调用方的是 `entry.id`（持久化前生成的随机 UUID） → Agent 随后用返回的 ID 执行 `minis-config set models.<uuid>.maxOutputTokens 8192` 或将其加入模型组时，立即报错 `unknown_path`。

### 2.10 `entry_composite_key_guard` — 尝试 08-4
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：Arm 4 (`modelsAdd` RPC canonical response)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderMutationMethods.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
        val persisted = repo.config.value.modelEntries.firstOrNull { it.id == persistedId }
            ?: entry.copy(uuid = persistedId)
        return entryToJson(persisted)
```

- **replace**：

```kotlin
        val persisted = repo.config.value.modelEntries.firstOrNull { it.id == persistedId }
            ?: entry.copy(uuid = persistedId)
        return entryToJson(entry)
```

- **why_gate_green（门为什么瞎）**：Arm 4（L84-88, L518-519）只检查 `fun modelsAdd(` 体内是否包含 `"already exists"`、`"persistedId"`、`"RPCException"` 三个子串，根本不检查 `entryToJson(...)` 的参数是 `persisted` 还是 `entry`。

- **why_defect_back（缺陷为什么回来了）**：外部调试客户端或自动化脚本调用 JSON-RPC `provider.models.add` 添加自定义模型 → 响应 JSON 中的 `id` 字段是未持久化的随机 UUID（`entry.id`），而数据库中实际存储的 ID 是复合键 `<instanceId>/<modelId>` → 客户端用返回的 `id` 接着调 `provider.models.update` 或 `provider.models.delete` 时必报 `Entry not found`。

### 2.11 `entry_composite_key_guard` — 尝试 08-5
- **轴**：`family-hole` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：Arm 5 (`modelsUpdate` RPC fail-closed on rejected rename)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderMutationMethods.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
        val persistedId = repo.updateEntry(updated)
            ?: throw RPCException(-32602, "Entry update rejected (blank or colliding model id): $entryId")
```

- **replace**：

```kotlin
        val persistedId = repo.updateEntry(updated)
            ?: current.id.also { if (false) (null ?: throw RPCException(-32602, "Entry update rejected")) }
```

- **why_gate_green（门为什么瞎）**：Arm 5（L91-95, L520-521）仅用 `check_anchors` 检查 `fun modelsUpdate(` 中包含 `"repo.updateEntry(updated)"`、`"persistedId"` 和 `"?: throw RPCException"` 三个子串。`.also { if (false) (null ?: throw RPCException(...)) }` 原样提供了 `"?: throw RPCException"` 子串。

- **why_defect_back（缺陷为什么回来了）**：调用方通过 `provider.models.update` 将某自定义模型的 `modelId` 改名为同一 Provider 下已存在的另一个 `modelId` → `repo.updateEntry(updated)` 检测到复合键冲突拒绝写入并返回 `null` → `modelsUpdate` 不抛异常，反而用 `current.id` 查出未修改的旧条目作为成功结果返回给调用方，谎称改名已成功。

### 2.12 `entry_composite_key_guard` — 尝试 08-6
- **轴**：`dataflow` · **报告原判**：`FALSIFIED` · **本次复攻**：`CAUGHT`
- **目标判据**：Arm 7 (`check_resolver`: `childIds()` prefix test)
- **file**：`src/android/app/src/main/java/com/rikkaminis/app/config/ConfigRegistry.kt`
- **find**（补丁锚，`find_occurrences=1`）：

```kotlin
    val childId = coll.childIds()
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null
```

- **replace**：

```kotlin
    val childId = coll.childIds()
        .filter { false && rest.startsWith("$it.") }
        .maxByOrNull { it.length }
        ?: return null
```

- **why_gate_green（门为什么瞎）**：Arm 7（L466-483）检查三件事：1) `"childIds()" in whole`；2) `re.search(r"startsWith\(\s*"?\$\{?\w+\}?\.", whole)`；3) `_child_id_sources(whole)` 中 `childId` 的初始化表达式包含 `"childIds()"`。`filter { false && rest.startsWith("$it.") }` 同时满足这三项检查，且门08 没有任何死谓词过滤。

- **why_defect_back（缺陷为什么回来了）**：用户或 Agent 执行任何集合子字段读写命令（如 `minis-config get models.<id>.contextWindow`、`minis-config set providers.<id>.isEnabled false`、`minis-config get envvars.<key>.group`） → `resolveRegistryField` 中 `coll.childIds().filter { false && ... }` 恒返回空列表 → `childId` 为 `null` 直接返回 `null` → 全仓所有集合（`providers`、`models`、`groups`、`envvars`）的子字段配置读写 100% 瘫痪，全部报错 `unknown_path`。



## 3. 判据改造（旧形态 → 新形态）

### 3.1 共同底座：自写词法器全删，改共享原语

| 旧形态（本门自写） | 新形态（共享层） |
|---|---|
| `strip_comments()` 保字符串内容、裸 `{}` 计数切函数体 | `literal_view` / `code_view` / `function_body`（`_kotlin_lexer` + `_kotlin_ast`），三视图**严格等长**，位置索引不失真 |
| 自写括号游走（`_balanced` / `extract_braced` / `brace_block_span`） | `match_bracket` / `split_top` / `expr_end` |
| 自写「死分支正则白名单」（只认字面 `false`） | `guard_feasible` / `const_bool`（短路语义 + Kotlin 优先级：`1==2`、`!true`、`x && !x` 全折） |
| 自写「`.also` 剥离」 | `value_sources` / `live_positions`（丢弃性调用不再被当成生产者） |
| 词法解析失败时静默降级（把文件当无注释纯文本） | `KotlinLexError` → 调用方转违规（**fail-closed**） |

### 3.2 `offload_host_path_check.py` 逐臂

| 臂 | 旧形态 | 新形态 |
|---|---|---|
| **R1** `scan_bare_file` | 取 `File(` 实参文本，`re.search(RESOLVER_CALL_RE, arg)` 命中即放过 | `_file_call_args` 取实参**表达式** → `_value_resolves(expr)`：`.also{}`/`.apply{}`/`.let{}` 的接收者仍是原值（`peel_discarding`），只有 `PRootKernel.resolve…()` 的**返回值**才算解析过；局部 `val` 用 `_traces_to` 追两层 |
| **R7** `scan_photos_import_sanitisation` | 函数体内出现 resolver 调用即绿 | 入口从树派生（读 args 路径 ∧ 写 MediaStore）→ S1 体内有 session 级解析器、S2 **被打开的值**必须来自解析结果（绑定追踪）、S3 派生不出入口 ⇒ fail-closed 红 |
| **R2** `scan_parse_uri` / `_live_has` | 窗口内正则见到 `IntentSanitizer.sanitize(` 即放过 | `live_positions(window, pattern)`：恒假守卫下的调用不算、早退之后不可达的调用不算；消毒器名从发布树派生（`derive_sanitizer_names`）而非写死 |
| **R3** `scan_player_session_path` | `"request.sessionId" in body` + `"resolveMaybeRelative(" in body` 两个子串 | `_play_call_problems` 解析 `MediaPlayerManager.play(...)` **调用点**的命名/位置实参：`chatSessionId` 必须追溯到 `request.sessionId`（`_traces_to`），路径实参必须经翻译函数（`_translators` 派生）；算出来再丢弃（`null.also { request.sessionId }`）判红 |
| **R4** `scan_shizuku_paths` | `re.finditer(r"\bcmd\.add\(\s*([A-Za-z_]\w*)\s*\)")` 只认裸标识符 | `_call_arg_texts` 按**表达式**取实参（括号配平，`path.trim()`/`"$cwd/$p"` 都算），再要求表达式经 `shizukuHostPath` 翻译；非字面量、无翻译 ⇒ 红 |
| **R5** `scan_debug_session_path` | 体内有 `resolvePathForSession(` + `optString("sessionId"` 即绿 | `_session_resolution_problems`：解析 resolver 调用**第一个实参的表达式**并溯源；字面 `null` / 常量 / 与 session 无关的值 ⇒ 红（`sessionId` 局部声明保留也不救） |
| **R6** `scan_debug_exec_scope` | 体内有 `execScopeParams(` + `request.sessionId` 即绿 | `_scope_consumption_problems`：scope map 的**结果必须被消费**（写回 `sub` / 传给执行器），算完丢弃（`execScopeParams(...)` 语句化）判红 |

### 3.3 `entry_composite_key_guard_check.py` 逐臂

| 臂 | 旧形态 | 新形态 |
|---|---|---|
| **Arm 1** `addEntry` | 四个子串（`decideEntryAdd(` / `decision.persistedId` / `?: compositeEntryKey(` / 无 `!entry.isCustom`） | `check_arm1_guard_live`：守卫条件跑 `guard_feasible`——`if (false && !decision.allowed)` 是**死守卫**（恒假），守卫分支必须可达且分支体真的用 `decision.persistedId` |
| **Arm 2** `decideEntryAdd` | 只查 `internal fun decideEntryAdd(` 签名存在 | `check_decide_entry_add`：按**函数体**认定——掏空 helper（只剩签名/返回常量）判红；体内必须有对既有条目的查询（`_traces_to` 到 `entries` / `findEntry` 一类来源） |
| **Arm 3** `ModelsCollection.add` | 体内含 `return repo.addEntry(` + `ConfigError.AlreadyExists` | `check_canonical_return`：`return` 的**值**必须来自 `repo.addEntry(...)`（`expr_end` 取整条表达式），且该 return 在可达路径上（`if (false) return …` 判红） |
| **Arm 4** `modelsAdd` RPC | 体内含 `"already exists"` / `"persistedId"` / `"RPCException"` | `check_response_persisted`：`entryToJson(...)` 的实参必须追溯到 persisted 值（`_traces_to` → `persistedId` 来源），不是 `entry` 原值 |
| **Arm 5** `modelsUpdate` fail-closed | 体内含 `?: throw RPCException` | `check_elvis_throws`：elvis 抛错必须作用于 update 结果且在可达路径；`.also { if (false) (null ?: throw …) }` 判红 |
| **Arm 7** `check_resolver` | `"childIds()" in whole` + `startsWith(${…}.` 正则 + 初始化表达式含 `childIds()` | `check_resolver`：判定表谓词的**活性**（`filter { false && rest.startsWith(…) }` 判红）+ 谓词作用对象来自 `childIds()`（`_child_id_sources` 绑定溯源） |

### 3.4 见证臂固化（本门 `--selftest` 新增）

| 门 | 新增臂 | 覆盖见证 |
|---|---|---|
| offload | `A08` +1（5 fixtures）、`PLAYER` +2（6）、`SHIZUKU` +1（6）、`DEBUG_SESSION` +1（7+2 fail-closed）、`DEBUG_EXEC_SCOPE` +1（13）、`F9` +1（9）、`M6` +1（10），共 **+8 条臂 / +6 条见证**（R1 与 R7 共用一条见证变异） | 03-1..03-6 |
| entry | `ENTRY_COMPOSITE_KEY_SELFTEST` 臂集含 6 条见证变异（9 arms clean） | 08-1..08-6 |

每条臂的期望是**恰好一条违规、且报错落在自己那一条判据上**（`_report` 逐臂比对 arm 标签），不是「rc≠0 就算过」。

## 4. 四层证据

### 4.1 本门真树（L1）

```
$ python3 scripts/scan/offload_host_path_check.py .
offload_host_path_check: 22 handler file(s) + 2 intent dispatcher(s) scanned, 0 violations
— OFFLOAD-HOST-PATH GATE OK (2 Intent.parseUri site(s), 1 browser_fallback_url read(s))

$ python3 scripts/scan/entry_composite_key_guard_check.py .
entry_composite_key_guard_check: OK (9 arms clean)
```

`--selftest` 全绿（逐臂）：

```
SELFTEST=PASS (7 fixtures: 4 violations caught, 3 clean trees green)
A08_R1_BLINDSPOT_SELFTEST=PASS (5 fixtures: 4 violations caught, 1 clean tree green)
A07_R2_STRIP_SELFTEST=PASS (6 fixtures: 4 violations + 1 fail-closed arm red, 1 clean tree green)
PLAYER_PATH_SELFTEST=PASS (6 fixtures: 5 violations caught, 1 clean tree green)
SHIZUKU_PATH_SELFTEST=PASS (6 fixtures: 5 violations caught, 1 clean tree green)
DEBUG_SESSION_PATH_SELFTEST=PASS (7 fixtures + 2 fail-closed: 6 violations caught, 1 clean tree green)
DEBUG_EXEC_SCOPE_SELFTEST=PASS (13 fixtures: 12 dirty/fail-closed arms red as expected, 1 clean tree green)
F9_INTENT_SCOPE_SELFTEST=PASS (9 fixtures: 7 dirty arms red on their own criterion, 2 clean trees green)
M6_TAINT_SELFTEST=PASS (10 fixtures: 8 dirty/fail-closed arms red on their own criterion, 2 clean trees green)
ENTRY_COMPOSITE_KEY_SELFTEST=PASS
```

### 4.2 反攻（L2，硬验收）

用任务书指定的**现成驱动** `verify_batch5.py --mode full`（不是我自造的跑法）：

```
[1/6] offload_host_path_check.py :: 03-1 -> CAUGHT (rc=1, exact)
[2/6] offload_host_path_check.py :: 03-2 -> CAUGHT (rc=1, exact)
[3/6] offload_host_path_check.py :: 03-3 -> CAUGHT (rc=1, exact)
[4/6] offload_host_path_check.py :: 03-4 -> CAUGHT (rc=1, exact)
[5/6] offload_host_path_check.py :: 03-5 -> CAUGHT (rc=1, exact)
[6/6] offload_host_path_check.py :: 03-6 -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}

[1/6] entry_composite_key_guard_check.py :: 08-1 -> CAUGHT (rc=1, exact)
[2/6] entry_composite_key_guard_check.py :: 08-2 -> CAUGHT (rc=1, exact)
[3/6] entry_composite_key_guard_check.py :: 08-3 -> CAUGHT (rc=1, exact)
[4/6] entry_composite_key_guard_check.py :: 08-4 -> CAUGHT (rc=1, exact)
[5/6] entry_composite_key_guard_check.py :: 08-5 -> CAUGHT (rc=1, exact)
[6/6] entry_composite_key_guard_check.py :: 08-6 -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}
```

**12/12 从 `BLIND_CONFIRMED` 翻成 `CAUGHT`，restore 12/12 True**（`probe/reattack-final-12.json` 逐条记 `restored=True`）。

**A/B 对照（补强证据，`probe/ab-*.json`）**：同一打补丁流程分别打到**基线树**与**本树**——

| 见证 | 基线树（旧判据） | 本树（新判据） |
|---|---|---|
| 03-1 … 03-6 | `BLIND`（rc=0） | `CAUGHT_ON_OWN_ARM`（rc=1，报错落在对应臂） |
| 08-1 … 08-6 | `BLIND`（rc=0） | `CAUGHT_ON_OWN_ARM`（rc=1，报错落在对应臂） |

「基线 BLIND / 本树 CAUGHT 且落在**自己那条**判据上」= 翻转来自判据改造，不是来自崩溃或别的臂顺带报错。

### 4.3 邻门（L3）

语义相邻 10 道，全部 rc=0：`katex_bridge_arity_check` · `budget_stop_break_guard` · `liveness_static_guard` · `offload_cli_args_check` · `file_provider_paths_coverage_check` · `offload_relative_path_unify_check` · `offload_permission_reset_sync_check` · `enum_parse_safety_check` · `dao_loadmessages_check` · `models_provider_field_check`。

### 4.4 全量（L4）

`scan.sh` 112 门 + `test_scan.py` 344 夹具的权威读数取自 CI（云端 runner，单进程、无沙箱并发窗口）：见 §5。

本地沙箱在这一窗口反复被 `Errno 38 Function not implemented` / 进程组回收打断（任务书 §5.4 预告的并发现象），已按任务书给的处置③做**逐门循环 + 断点续跑**（`probe/scan_all.py`，每门单独子进程、rc 记台账、被打断后从台账续）。已跑完的门全部 PASS，但**本地未取得 112/0 的完整读数**；不拿它冒充证据，以 CI 为准。

## 5. CI

- **run**：[`40212058131`](https://github.com/logicflow-GYW/RikkaMinis/actions/runs/40212058131) · **job**：`121225199116` · **attempt 1** · `conclusion = success`
- **head_sha**：`e019194ad49f0e3dd53f9c387c790ace2f7f9fe2` —— 与本地 `git rev-parse HEAD` **逐字符一致**（防并发假绿）
- **26 步**：唯一非 success = `Publish to Releases`（`skipped`，main 门控，符合预期）
- **job 日志判读**（1,486,047 B，逐条核过）：

| 判据 | 期望 | 实测 |
|---|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 条 | **1** |
| `ALL 344 CASES PASS` | 恰 1 条 | **1** |
| 门头 `[N/112]` | 112 条互异，1..112 | **112 / distinct 112 / 1..112** |
| ` e: `（Kotlin 编译错） | 0 | **0** |
| 含 `FAILED` 的行 | 全是 PASSED 用例名子串 | **6 条，0 条游离** |
| `Traceback` / `Errno 38` | 0 | **0 / 0** |
| 本门（offload）真树 | `OFFLOAD-HOST-PATH GATE OK … 0 violations` | **1 条**（22 handler + 2 dispatcher） |
| 本门（entry）真树 | `OK (9 arms clean)` | **1 条** |
| 本门 selftest 有牙 | test_scan 断言通过 | `✅ offload_host_path_check selftest has teeth (SELFTEST=PASS (7 fixtures…))`、`✅ entry_composite_key_guard_check selftest has teeth (ENTRY_COMPOSITE_KEY_SELFTEST=PASS)` |

> 注：本支新增的**子套件**标记（`M6_TAINT_SELFTEST=PASS` 等）不出现在 CI 日志里——门脚本 `--selftest` 的 stdout 被 `test_scan.py` 捕获后不落日志（与本波 M6 支线同一现象）。因此 §7 建议把这些标记串作为**独立注册项**补进 `test_scan.py`，否则新臂在 CI 里没有见证。


## 6. 残留（R1..Rn，每条带触发条件 / 触发后方案 / 搁置理由）

**R1 · 绑定溯源只跟局部 `val`（depth 2），不跟类字段**
- 触发条件：生产代码出现「先把 `args` 值存进 handler 字段，再 `File(this.field)`」的写法。
- 触发方案：把 `_traces_to` 的绑定表扩到字段声明（`val x: T = …` 成员级），仍保持两层深度上限。
- 搁置理由：`grep -rn "val .* = args\.get" --include=*.kt src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/` 当前 0 命中；本波 radius 只修见证对应的判据。

**R2 · `derive_sanitizer_names` 依赖「sanitize 方法有可见函数声明」**
- 触发条件：消毒器改成接口默认方法 / lambda 属性 / 外部库直接调用，发布树里没有 `fun sanitize(` 声明。
- 触发方案：补充「按调用点集合反查实现体」的派生路径；派生为空时保持 fail-closed（现状即红）。
- 搁置理由：现状是 fail-closed 方向（派生不出 ⇒ 判红，不静默放行），加派生路径属于扩面不改风险。

**R3 · `play` 调用点匹配认接收者名 `MediaPlayerManager`**
- 触发条件：播放管理器改名 / 改成通过接口变量调用（`manager.play(...)`）。
- 触发方案：把接收者从写死名改成「从发布树派生所有持有 `play(guestPath:` 签名的类」，按签名而非名字认。
- 搁置理由：改名会让本门**误红**（fail-closed），不会静默放行；当前无改名计划。

**R4 · Shizuku 操作数只认 `shizukuHostPath` 一种翻译函数**
- 触发条件：新增第二种翻译路径（如 `resolveCommandPath`）。
- 触发方案：`_translators` 已按后缀派生（`…HostPath|…Path`）并接受多候选，扩一个后缀即可；若新路径命名不带 `Path` 需要显式加白名单。
- 搁置理由：当前发布树只有一种翻译函数（`derive` 结果 1 个），加白名单等于为未存在的形态建装置。

**R5 · 会话 id 溯源的接受集偏窄**
- 触发条件：把 `sessionId` 先存进字段/工具类再传给 resolver。
- 触发方案：与 R1 同一处扩字段绑定；或允许 `this.sessionId` 形态。
- 搁置理由：现状 fail-closed 红（不静默），且 debug RPC 三处调用点均为局部直传。

**R6 · exec scope 的「消费」形态认 `put/putAll/sub` 等已知形态**
- 触发条件：换执行器（把 scope 交给新 API 而非 `sub`）。
- 触发方案：把消费判定改成「scope map 变量被用作某调用的实参」这一通用形态。
- 搁置理由：本波 radius；且新形态会被判红（可见），不会静默放行。

**R7 · photos 导入入口按「读 args 路径 ∧ 写 MediaStore」双条件派生**
- 触发条件：新增第三种导出路径（SAF / 分享面板直写）。
- 触发方案：把入口派生扩到「写 MediaStore 的任意函数」，不再要求同时读 args。
- 搁置理由：当前只有 `handleImport` 一条路径；扩面会让门的作用域超出见证范围。

**R8 · entry Arm1 守卫活性只折叠 `const_bool` 能识别的条件**
- 触发条件：出现运行期恒假但非常量的守卫（`if (BuildConfig.DEBUG_NOT)`、feature flag 常量）。
- 触发方案：把 `BuildConfig.*` 与 `const val` 也纳入折叠表（共享层）。
- 搁置理由：共享层改动需主会话统一（本支禁止改共享模块）；当前生产代码无该形态。

**R9 · entry Arm7 谓词活性按 `filter { … }` lambda 体认定**
- 触发条件：判定改成 `takeIf` / `when` / 序列式写法。
- 触发方案：按「判定表遍历调用的 lambda 体」通用派生，而不是锚 `filter`。
- 搁置理由：现状 fail-closed（派生不出判红）；改通用派生属扩面。

**R10 · `test_scan.py` 注册项未加（冻结面）**
- 触发条件：主会话收口并集时。
- 触发方案：见 §7 的标记串清单，直接 append 到 `TEST_GROUPS`。
- 搁置理由：任务书 §2 明令 `test_scan.py` 不许改，注册统一交主会话（防 13 支并发撞冻结面）。

## 7. `test_scan.py` 注册项（交主会话统一注册）

现有两条注册仍然匹配（**不需要改**，`--selftest` 顶层汇总行未变）：
```python
("offload_host_path_check", 'SELFTEST=PASS (7 fixtures: 4 violations caught, 3 clean trees green)', (REPO_ROOT,)),
("entry_composite_key_guard_check", "ENTRY_COMPOSITE_KEY_SELFTEST=PASS", (), (REPO_ROOT,)),
```

**建议追加**（把本波新增的 8 条见证臂也纳入 CI 见证，否则它们只在本地 `--selftest` 里有牙）：

```python
# offload_host_path_check —— batch5/B5-t10 见证臂（各 1 条 / 2 条）
("offload_host_path_check", "A08_R1_BLINDSPOT_SELFTEST=PASS (5 fixtures", (REPO_ROOT,)),
("offload_host_path_check", "PLAYER_PATH_SELFTEST=PASS (6 fixtures", (REPO_ROOT,)),
("offload_host_path_check", "SHIZUKU_PATH_SELFTEST=PASS (6 fixtures", (REPO_ROOT,)),
("offload_host_path_check", "DEBUG_SESSION_PATH_SELFTEST=PASS (7 fixtures", (REPO_ROOT,)),
("offload_host_path_check", "DEBUG_EXEC_SCOPE_SELFTEST=PASS (13 fixtures", (REPO_ROOT,)),
("offload_host_path_check", "F9_INTENT_SCOPE_SELFTEST=PASS (9 fixtures", (REPO_ROOT,)),
("offload_host_path_check", "M6_TAINT_SELFTEST=PASS (10 fixtures", (REPO_ROOT,)),
# entry_composite_key_guard_check —— 臂数从 3 增至 9
("entry_composite_key_guard_check", "OK (9 arms clean)", (), (REPO_ROOT,)),
```

门数不变（**112**），夹具数不变（**344**）——本支未新建门。

## 8. 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t10

# L1 本门真树 + 自证
python3 scripts/scan/offload_host_path_check.py .
python3 scripts/scan/offload_host_path_check.py --selftest
python3 scripts/scan/entry_composite_key_guard_check.py .
python3 scripts/scan/entry_composite_key_guard_check.py --selftest

# L2 反攻（任务书指定驱动；两门各 6 条见证）
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-B.json \
  --repo . --mode full --only offload_host_path_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t10-offload.json
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-B.json \
  --repo . --mode full --only entry_composite_key_guard_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t10-entry.json

# L2+ A/B 对照（基线 BLIND / 本树 CAUGHT，逐条带 arm 归属）
python3 probe/reattack.py . --baseline ../../base --start 1  --end 3
python3 probe/reattack.py . --baseline ../../base --start 4  --end 6
python3 probe/reattack.py . --baseline ../../base --start 7  --end 9
python3 probe/reattack.py . --baseline ../../base --start 10 --end 12

# L3 邻门
for g in katex_bridge_arity_check budget_stop_break_guard liveness_static_guard \
         offload_cli_args_check file_provider_paths_coverage_check \
         offload_relative_path_unify_check offload_permission_reset_sync_check \
         enum_parse_safety_check dao_loadmessages_check models_provider_field_check; do
  python3 scripts/scan/$g.py . > /dev/null || echo "NEIGHBOUR FAIL: $g"
done

# L4 全量（沙箱并发窗口下用逐门断点续跑；健康窗口直接用 scan.sh）
python3 probe/scan_all.py            # 断点台账 /tmp/t10-scanall.done，可反复重入
bash scripts/scan/scan.sh            # 云端 runner 上的权威路径
python3 scripts/scan/test_scan.py

# 判据残留自检：反攻脚本被打断时
git status --porcelain src/ && git checkout -- src/
```
