# FIX-BATCH5-t09 — `file_provider_paths_coverage` + `offload_relative_path_unify` 判据加固

- **支线**：`fix/b5-fileprov-offrel`
- **基线**：`e145ec961`（= main `6ac77c6b2` + 共享值流/控制流原语 `scripts/scan/_kotlin_valueflow.py`）
- **改动面**：仅 2 个门脚本 —— `scripts/scan/file_provider_paths_coverage_check.py`、`scripts/scan/offload_relative_path_unify_check.py`
- **零触碰**：生产 `.kt` 0 行、`scripts/scan/scan.sh` 0 行、`scripts/scan/test_scan.py` 0 行、共享模块（`_kotlin_lexer.py` / `_kotlin_ast.py` / `_kotlin_valueflow.py`）0 行、其他门脚本 0 行
- **见证**：12 条（`02-1..02-6` / `09-1..09-6`），加固前 12/12 `BLIND_CONFIRMED`，加固后 12/12 `CAUGHT`（rc=1，报错指向被点名判据），`restored=True` ×12

## ① 门与轴

| 门 | 见证 | 轴 | 一句话症 |
|---|---|---|---|
| `file_provider_paths_coverage_check.py` | 02-1 | `scope` | `context.filesDir.resolve("media-unregistered")` 不是 `File(`，目录根派生器看不见它 |
| | 02-2 | `dataflow` | 空的 `withContext(Dispatchers.IO) {}` 保留子串，真实 `mkdirs`/`writeText` 在 `run {}` 里回主线程 |
| | 02-3 | `dataflow` | `prune(dir, 0L)` —— 调用在、`.delete()` 在，cutoff 恒负 ⇒ prune 永久 no-op |
| | 02-4 | `order` | 前置死代码 `if (false) f.writeText("")` 让 `find()` 取到"写在前"，真实写已挪到 `getUriForFile` 之后 |
| | 02-5 | `dataflow` | prune 在 IO 块内结束，紧随的最多 100 MB `inputStream().copyTo()` 落在块外 |
| | 02-6 | `parser-death` | 块内字符串 `"{"` 让裸花括号计数多算一层，IO 区一直吞到 `cleanSharedFiles` 之后 |
| `offload_relative_path_unify_check.py` | 09-1 | `dataflow` | `resolveMaybeRelative(linuxPath, null)` —— 调用、映射、返回头都在，只是没有 cwd 可拼 |
| | 09-2 | `dataflow` | `raw.also { resolveMaybeRelative(raw, request.cwd) }` —— 名字在体内，`--output` 拿回裸相对路径 |
| | 09-3 | `dataflow` | `resolveMaybeRelative(url, null)` —— 同 09-1，图像链 |
| | 09-4 | `dataflow` | `cmdTranscribe(args, sid, cwd = "")` —— 参数**名**叫 cwd，值是空串 |
| | 09-5 | `family-hole` | `if (1 == 2) return NativeOffloadResult(2, …)` —— 死分支白名单只认 `false`/`!true`/`x && false` |
| | 09-6 | `dataflow` | `resolvePathForSession(null, raw.trim(), context)` —— 三个 call 字面量都在，会话与 join 结果都没进去 |

轴分布：`dataflow` 9 · `scope` 1 · `order` 1 · `parser-death` 1 · `family-hole` 1。共同病因一句话：**判据锚在"某个名字/某个调用还在文件里"，而不是"这个值真的流到了使用点、这段代码真的会执行"**。

## ② 见证原文（file / find / replace / 门为何绿）

基线 `e145ec961`，逐条机械复证后原文如下（`why_gate_green` 为报告原文，已逐条回真树核对）。

### 尝试 02-1（`G02-A1` · 目标判据：`C10 / C11 (`dir_root_sites` + `MediaStore` check)` · 轴：`scope` · 结论：`FALSIFIED`）
- 判据：`C10 / C11 (`dir_root_sites` + `MediaStore` check)`（轴 `scope`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/data/storage/MediaStore.kt`（find 命中 1 次）
- 形状：在 `MediaStore.kt` 中保留一个无副作用的 `val legacyMediaDir: File = File(context.filesDir, "media").also { }` 满足 C1/C2/C11 正向锚点，而将实际使用的 `val mediaBaseDir: File` 改为 `context.filesDir.resolve("media-unregistered").also { legacyMediaDir }`；与 `selftest` 中把 `File(context.filesDir, "media")` 改成 `File(context.filesDir, "media-unregistered")` 不同，本形状在构造器拼写维度改用 `File.resolve(...)` 扩展函数，绕过了 `FILE_CTOR_RE` 的 `File(context.filesDir, ...)` 唯一语法假设，仍属未声明 FileProvider 目录构造家族。
- 门为何绿：`FILE_CTOR_RE`（L98-99）仅匹配 `r"\b(?:java\.io\.)?File\s*\(\s*(?:\w+\.)?(filesDir|cacheDir)\s*,"`。`context.filesDir.resolve("media-unregistered")` 不以 `File(` 开头，因此 `dir_root_sites`（L169）完全看不见这个新目录；同时 `legacyMediaDir` 提供了 `("files-path", "media")` 站点，使 C1、C2、C10、C11 全部判绿。
- 补丁 find：
```kotlin
    val mediaBaseDir: File = File(context.filesDir, "media")
```
- 补丁 replace：
```kotlin
    val legacyMediaDir: File = File(context.filesDir, "media").also { }
    val mediaBaseDir: File = context.filesDir.resolve("media-unregistered").also { legacyMediaDir }
```

### 尝试 02-2（`G02-A2` · 目标判据：`C3 (`writeLargeContentFile` Dispatchers.IO check)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`C3 (`writeLargeContentFile` Dispatchers.IO check)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/LargeContentGuard.kt`（find 命中 1 次）
- 形状：在 `writeLargeContentFile` 开头放一个空块 `withContext(Dispatchers.IO) {}`，把后续创建目录和 `f.writeText(content)` 放在普通 `run { ... }` 中于调用方（主线程）同步执行；与 `selftest` 中直接删掉 `withContext(Dispatchers.IO)` 不同，本形状在作用域包裹维度保留了 `withContext(Dispatchers.IO)` 子串但将其变为空块，仍属主线程同步写入大文件家族。
- 门为何绿：C3（L446-448）只执行 `if "withContext(Dispatchers.IO)" not in body: errors.append(...)`，根本没有使用同文件 L328 定义的 `io_regions(body)` 去验证 `mkdirs()`、`pruneExpiredLargeMessageFiles` 和 `writeText` 是否位于 `withContext(Dispatchers.IO)` 的花括号内部。
- 补丁 find：
```kotlin
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "large-messages").apply { mkdirs() }
```
- 补丁 replace：
```kotlin
    withContext(Dispatchers.IO) {}
    val file = run {
        val dir = File(context.cacheDir, "large-messages").apply { mkdirs() }
```

### 尝试 02-3（`G02-A3` · 目标判据：`C4 (`pruneExpiredLargeMessageFiles` call and body check)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`C4 (`pruneExpiredLargeMessageFiles` call and body check)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/LargeContentGuard.kt`（find 命中 1 次）
- 形状：将 `writeLargeContentFile` 中调用 `pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis())` 的第二个参数替换为常量 `0L`；与 `selftest` 中删掉 `pruneExpiredLargeMessageFiles(` 调用或删掉 `.delete()` 不同，本形状在参数数据流维度保留了调用与函数体，但传入 `nowMs = 0L` 使过期阈值恒为负数，仍属缓存清理失效家族。
- 门为何绿：C4（L450-454）只检查 `"pruneExpiredLargeMessageFiles(" not in body` 以及 `prune_body` 内是否包含 `".lastModified()"` 和 `".delete()"`，完全不检查调用 `pruneExpiredLargeMessageFiles` 时传入的当前时间戳参数是不是正数或 `System.currentTimeMillis()`。
- 补丁 find：
```kotlin
        runCatching { pruneExpiredLargeMessageFiles(dir, System.currentTimeMillis()) }
```
- 补丁 replace：
```kotlin
        runCatching { pruneExpiredLargeMessageFiles(dir, 0L) }
```

### 尝试 02-4（`G02-A4` · 目标判据：`C5 (`writeText` before `FileProvider.getUriForFile` order check)` · 轴：`order` · 结论：`FALSIFIED`）
- 判据：`C5 (`writeText` before `FileProvider.getUriForFile` order check)`（轴 `order`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/LargeContentGuard.kt`（find 命中 1 次）
- 形状：在 `FileProvider.getUriForFile` 之前放一句死代码 `if (false) f.writeText("")`，而把真正写入内容的 `file.writeText(content)` 移到 `getUriForFile` 之后；与 `selftest` 中直接把唯一的 `f.writeText(content)` 移到 `getUriForFile` 之后不同，本形状在首次匹配锚点（`body.find`）维度用死代码抢占 `wt_pos < uri_pos`，仍属先取 URI 后写文件的竞态家族。
- 门为何绿：C5（L456-463）使用 `wt_pos = body.find("writeText(")` 与 `uri_pos = body.find("FileProvider.getUriForFile(")` 比较 `wt_pos > uri_pos`。`str.find` 只返回第一个匹配位置（即 `if (false) f.writeText("")`），满足 `wt_pos < uri_pos`，且 C5 完全没有死分支过滤，也未检查 `getUriForFile` 时目标文件是否已存在且写入完毕。
- 补丁 find：
```kotlin
        val f = File(dir, "msg-$safeKey-${System.currentTimeMillis()}.txt")
        f.writeText(content)
        f
    }
    val authority = context.packageName + ".fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
```
- 补丁 replace：
```kotlin
        val f = File(dir, "msg-$safeKey-${System.currentTimeMillis()}.txt")
        if (false) f.writeText("")
        f
    }
    val authority = context.packageName + ".fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    file.writeText(content)
```

### 尝试 02-5（`G02-A5` · 目标判据：`C8 (`addAttachmentFromStagedShare` IO region check)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`C8 (`addAttachmentFromStagedShare` IO region check)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt`（find 命中 1 次）
- 形状：让 `withContext(Dispatchers.IO)` 在执行完 `pruneExpiredShareInboundFiles` 后立即结束并返回 `durableDir`，将紧随其后的 `file.inputStream().use { ... copyTo(out) }`（最高 100 MB 流式拷贝）留在 `withContext(Dispatchers.IO)` 块外的调用线程（Main）执行；与 `selftest` 中把整个 `withContext(Dispatchers.IO)` 删掉不同，本形状在块覆盖范围维度只把 `prune` 留在 IO 块内而把真正的 100MB 拷贝移出 IO 块，仍属主线程大文件 I/O 家族。
- 门为何绿：C8（L514-519）只检查 `any("pruneExpiredShareInboundFiles(" in reg for _, _, reg in io_regions(vm_body))`，完全不检查 `addAttachmentFromStagedShare` 中真正耗时的 `inputStream()` / `outputStream()` / `copyTo(` 流拷贝是否也在 `io_regions(vm_body)` 内。
- 补丁 find：
```kotlin
            runCatching { pruneExpiredShareInboundFiles(durableDir, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "share_inbound prune failed: ${it.message}") }
            val f = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
```
- 补丁 replace：
```kotlin
            runCatching { pruneExpiredShareInboundFiles(durableDir, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "share_inbound prune failed: ${it.message}") }
            durableDir
        }
        val durable = run {
            val f = java.io.File(durable, "${java.util.UUID.randomUUID()}-${file.name}")
```

### 尝试 02-6（`G02-A6` · 目标判据：`C9 (`ChatScreen.kt` `cleanSharedFiles` inside `withContext(Dispatchers.IO)` check)` · 轴：`parser-death` · 结论：`FALSIFIED`）
- 判据：`C9 (`ChatScreen.kt` `cleanSharedFiles` inside `withContext(Dispatchers.IO)` check)`（轴 `parser-death`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt`（find 命中 1 次）
- 形状：在 `LaunchedEffect(pendingShare)` 开头的第一个 `withContext(Dispatchers.IO)` 块内加一个含单左花括号的字符串 `"{".let { ... }`，然后去掉末尾 `cleanSharedFiles(context)` 外层的 `withContext(Dispatchers.IO)` 并在其后补 `.also { "}" }`；与 `selftest` 中直接去掉末尾 `withContext(Dispatchers.IO)` 不同，本形状在解析器维度利用 `brace_body`（L315-325）不跳过字符串字面量的缺陷，让第一个 IO 块的跨度吞并整个 `LaunchedEffect` 尾部的裸调用，仍属主线程同步清理文件家族。
- 门为何绿：`strip_comments`（L220-275）保留字符串字面量，而 `brace_body`（L315-325）只做裸字符计数 `if code[k] == '{': depth += 1` / `elif code[k] == '}': depth -= 1`。第一个 `withContext(Dispatchers.IO)` 里的 `"{"` 使 `depth` 多加 1，导致 `brace_body` 在该块真实的 `}` 处 `depth == 1` 不闭合，一直向下吞并到 `cleanSharedFiles(context).also { "}" }` 的 `"}"` 才归零。于是 `io_regions(cs)` 认为 `cleanSharedFiles` 位于第一个 `withContext(Dispatchers.IO)` 内部，C9 判绿。
- 补丁 find：
```kotlin
        val sharedDir = withContext(Dispatchers.IO) {
            com.rikkaminis.app.share.SharedShareStore.sharedFileDirectory(context)
        }
        for (item in pending.items) {
            when (item.kind) {
                com.rikkaminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    // [dv24/B-05] Route the append as an intent: the composer owns
                    // its live text. Resolving it here read `viewModel.inputText` —
                    // the 150 ms-debounced snapshot — so text dictated or pasted
                    // inside that window was overwritten by the stale string, and
                    // an equal write was dropped by the StateFlow altogether.
                    viewModel.requestComposerCommand(ComposerCommand.AppendText(item.value, separator = "\n", trimExistingEnd = false, trailingSpace = needsTrailingSpace))
                }
                com.rikkaminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    // Suspend: copies the staged bytes on IO, adds the attachment on Main.
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        viewModel.markShareInjected()
        withContext(Dispatchers.IO) {
            com.rikkaminis.app.share.SharedShareStore.cleanSharedFiles(context)
        }
```
- 补丁 replace：
```kotlin
        val sharedDir = withContext(Dispatchers.IO) {
            "{".let { com.rikkaminis.app.share.SharedShareStore.sharedFileDirectory(context) }
        }
        for (item in pending.items) {
            when (item.kind) {
                com.rikkaminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    viewModel.requestComposerCommand(ComposerCommand.AppendText(item.value, separator = "\n", trimExistingEnd = false, trailingSpace = needsTrailingSpace))
                }
                com.rikkaminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        viewModel.markShareInjected()
        com.rikkaminis.app.share.SharedShareStore.cleanSharedFiles(context).also { "}" }
```

### 尝试 09-1（`G09-A1` · 目标判据：`C1 (`ModelUseOffloadHandler.readLinuxPath`)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`C1 (`ModelUseOffloadHandler.readLinuxPath`)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt`（find 命中 1 次）
- 形状：在 `readLinuxPath` 中将 `val resolved = resolveMaybeRelative(linuxPath, cwd)` 改为 `val resolved = resolveMaybeRelative(linuxPath, null)`；与 `--selftest` 已挡的 `C1: readLinuxPath resolves the path and then maps the raw argument`（把 `linuxPath` 传给 `sessionAwareHostFile`）不同，本形状在 `resolveMaybeRelative` 的第二个实参维度传入 `null` 而非 `cwd`（门在 S1 修了该形状但在 C1 漏修），仍属相对路径 `--input` 无法结合 `cwd` 解析家族。
- 门为何绿：`check_source` 的 C1（L426-447）检查了 `CALL in body("fun readLinuxPath(")` 以及 `resolved` 变量被传入 `sessionAwareHostFile(resolved, sessionId)`，但完全没有检查 `resolveMaybeRelative(...)` 的第二个参数是不是 `cwd`（而 `check_speech_source` 的 S1 在 L487-490 专门检查了 `re.search(r"\bcwd\b", cwd_arg)`）。
- 补丁 find：
```kotlin
    private fun readLinuxPath(linuxPath: String, sessionId: String?, cwd: String?): String? {
        val resolved = resolveMaybeRelative(linuxPath, cwd)
```
- 补丁 replace：
```kotlin
    private fun readLinuxPath(linuxPath: String, sessionId: String?, cwd: String?): String? {
        val resolved = resolveMaybeRelative(linuxPath, null)
```

### 尝试 09-2（`G09-A2` · 目标判据：`C2 (`ModelUseOffloadHandler.cmdRun` `--output` normalisation)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`C2 (`ModelUseOffloadHandler.cmdRun` `--output` normalisation)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt`（find 命中 1 次）
- 形状：在 `cmdRun` 中将 `if (raw.startsWith("file://")) raw else resolveMaybeRelative(raw, request.cwd)` 替换为 `raw.also { resolveMaybeRelative(raw, request.cwd) }`；与 `--selftest` 已挡的 `C2: --output not normalised`（删掉 `resolveMaybeRelative` 调用）不同，本形状在返回值消费维度保留了调用但用 `raw.also { ... }` 丢弃拼接结果并返回原始相对路径 `raw`，仍属相对 `--output` 报错 `invalid_output_path` 家族。
- 门为何绿：C2（L448-451）仅执行一行子串检查：`if CALL not in body("fun cmdRun("): problems.append(...)`。只要 `cmdRun` 函数体内出现 `"resolveMaybeRelative("` 子串，无论返回值是否赋给 `outputPath`，C2 一律判绿。
- 补丁 find：
```kotlin
        val outputPath = args.get("output")?.let { raw ->
            if (raw.startsWith("file://")) raw else resolveMaybeRelative(raw, request.cwd)
        }
```
- 补丁 replace：
```kotlin
        val outputPath = args.get("output")?.let { raw ->
            raw.also { resolveMaybeRelative(raw, request.cwd) }
        }
```

### 尝试 09-3（`G09-A3` · 目标判据：`C3 (`ModelUseOffloadHandler.resolveImageUrl`)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`C3 (`ModelUseOffloadHandler.resolveImageUrl`)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt`（find 命中 1 次）
- 形状：在 `resolveImageUrl` 中将 `resolveMaybeRelative(url, cwd)` 改为 `resolveMaybeRelative(url, null)`；与 `--selftest` 已挡的 `C3: resolveImageUrl has no cwd join`（删掉 `resolveMaybeRelative` 直接 `throw ImageInputError`）不同，本形状在实参数据流维度保留了 `resolveMaybeRelative` 调用但将 `cwd` 替换为 `null`，仍属相对图片路径抛出 `ImageInputError` 家族。
- 门为何绿：C3（L452-454）仅执行 `if CALL not in body("fun resolveImageUrl("): problems.append(...)`，既不检查 `cwd` 是否传入 `resolveMaybeRelative`，也不检查 `parseMessages` -> `parseMessageArray` -> `resolveImageUrl` 调用链是否传递了 `cwd`。
- 补丁 find：
```kotlin
            else -> resolveMaybeRelative(url, cwd).takeIf { it.startsWith("/") }
```
- 补丁 replace：
```kotlin
            else -> resolveMaybeRelative(url, null).takeIf { it.startsWith("/") }
```

### 尝试 09-4（`G09-A4` · 目标判据：`S2 (`SpeechOffloadHandler` `cmdTranscribe` call chain carries `cwd`)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`S2 (`SpeechOffloadHandler` `cmdTranscribe` call chain carries `cwd`)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SpeechOffloadHandler.kt`（find 命中 1 次）
- 形状：在 `SpeechOffloadHandler.handle` 中将 `cmdTranscribe(args, request.sessionId, request.cwd)` 改为命名参数空串 `cmdTranscribe(args, request.sessionId, cwd = "")`；与 `--selftest` 已挡的 `S2: the Speech dispatch drops request.cwd`（写 `cmdTranscribe(args, request.sessionId)`）不同，本形状在命名参数名与实参值混淆维度利用 `cwd = ""` 的参数名满足了 `"cwd" in args` 检查，而实际传入的 `cwd` 值为空串 `""`，仍属 `handle` 丢弃 `request.cwd` 家族。
- 门为何绿：S2（L509-515）通过 `CHAIN_RE.findall(strip_strings(code))` 提取每个 `cmdTranscribe(...)` 的括号内容 `args`。由于 `strip_strings` 只清空引号内部的内容（变成 `cwd = ""`），S2 执行 `if "cwd" not in args:` 时，命名参数左侧的 `cwd` 直接匹配成功！
- 补丁 find：
```kotlin
                "transcribe", "listen" -> cmdTranscribe(args, request.sessionId, request.cwd)
```
- 补丁 replace：
```kotlin
                "transcribe", "listen" -> cmdTranscribe(args, request.sessionId, cwd = "")
```

### 尝试 09-5（`G09-A5` · 目标判据：`S4 (`SpeechOffloadHandler.cmdTranscribe` non-mic source refusal before mic gate)` · 轴：`family-hole` · 结论：`FALSIFIED`）
- 判据：`S4 (`SpeechOffloadHandler.cmdTranscribe` non-mic source refusal before mic gate)`（轴 `family-hole`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SpeechOffloadHandler.kt`（find 命中 1 次）
- 形状：在 `cmdTranscribe` 的非麦克风 `--source` 分支末尾将 `return NativeOffloadResult(2, ...)` 改为 `if (1 == 2) return NativeOffloadResult(2, ...)`；与 `--selftest` 已挡的 `S4: the refusal return is wrapped in if (false) (dead code)` 不同，本形状在常量假条件枚举维度用 `if (1 == 2)` 绕过 `_dead_condition`（只认 `false`、`!true`、`&& false`），仍属指定音频文件时静默开启用户真实麦克风窃听的隐私漏洞家族。
- 门为何绿：`_dead_condition`（L374-380）定义为：`if re.match(r"^false\b", c) or re.match(r"^!\s*true\b", c): return True; return any(part.strip() == "false" for part in c.split("&&"))`。对于 `c = "1 == 2"`，`_dead_condition` 返回 `False`，`_dead_spans(cmd_body)` 为空，S4 判绿。
- 补丁 find：
```kotlin
            return NativeOffloadResult(2, OffloadOutput.formatBody(body, args) + "\n")
```
- 补丁 replace：
```kotlin
            if (1 == 2) return NativeOffloadResult(2, OffloadOutput.formatBody(body, args) + "\n")
```

### 尝试 09-6（`G09-A6` · 目标判据：`Z1 / Z2 / Z3 (`ShizukuOffloadHandler.shizukuHostPath`)` · 轴：`dataflow` · 结论：`FALSIFIED`）
- 判据：`Z1 / Z2 / Z3 (`ShizukuOffloadHandler.shizukuHostPath`)`（轴 `dataflow`，报告结论 `FALSIFIED`，本次机械复证 `CAUGHT`）
- 目标文件：`src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ShizukuOffloadHandler.kt`（find 命中 1 次）
- 形状：在 `ShizukuOffloadHandler.shizukuHostPath` 中保留 `val guestPath = resolveMaybeRelative(raw.trim(), request.cwd)`，但将下一行改为 `PRootKernel.resolvePathForSession(null, raw.trim(), context)?.absolutePath`；与 `--selftest` 已挡的 `Z2: shizukuHostPath returns the guest spelling, not the mapped host path`（写 `raw.trim().also { resolvePathForSession(...) }`）不同，本形状在下游调用实参数据流维度保留了 `return PRootKernel.resolvePathForSession(...)` 作为返回头，却在调用实参中丢弃了 `guestPath` 和 `request.sessionId`（门在 S1 查了 `joined in tr_args` 但在 Z1–Z3 漏查），仍属相对路径失效与跨会话逃逸家族。
- 门为何绿：`check_shizuku_source`（L584-624）检查：1) `resolveMaybeRelative(` 在函数体内（Z1）；2) `request.cwd` 在函数体内（Z2）；3) `resolvePathForSession(` 在函数体内（Z3）；4) 返回表达式以 `try { PRootKernel.resolvePathForSession(` 开头（`_returned_from_translation` L365-367）。它完全没有像 S1（L499-506）那样调用 `_join_symbol` 检查 `guestPath` 是否被传进 `resolvePathForSession(...)`！
- 补丁 find：
```kotlin
    private fun shizukuHostPath(raw: String, request: NativeOffloadRequest): String? {
        val guestPath = resolveMaybeRelative(raw.trim(), request.cwd)
        return try {
            PRootKernel.resolvePathForSession(request.sessionId, guestPath, context)?.absolutePath
```
- 补丁 replace：
```kotlin
    private fun shizukuHostPath(raw: String, request: NativeOffloadRequest): String? {
        val guestPath = resolveMaybeRelative(raw.trim(), request.cwd)
        return try {
            PRootKernel.resolvePathForSession(null, raw.trim(), context)?.absolutePath
```

## ③ 判据改造（旧形态 → 新形态，逐条）

原则：**结构问题统一走共享投影**（`mask_code` 结构 / `literal_view` 读字面量 / `match_bracket` 配平 / `function_body` 取体），**取值问题统一走共享值流**（`value_sources` / `peel_discarding` / `path_feasible` / `guard_feasible` / `last_write` / `split_top`）。两个视图**严格等长**，所以"结构在 mask 视图上定位、字面量在 literal 视图同偏移读取"是安全的。

### `file_provider_paths_coverage_check.py`

| 判据 | 旧形态（见证为何绿） | 新形态（见证为何红） |
|---|---|---|
| C3 | `if "withContext(Dispatchers.IO)" not in body` —— 只要函数体里有这个子串就绿（02-2 放空块） | `_regions_in(io_regions(code), body)` 必须非空 **且** `mkdirs(` / `pruneExpiredLargeMessageFiles(` / `writeText(` 的每个**活的**匹配点都落在 region 内；`io_regions` 用共享 `match_bracket` 在 mask 视图上配对（02-6 的字符串 `"{"` 不再移动边界） |
| C4 | 调用子串 + `.lastModified()`/`.delete()` 子串 + TTL 字面量 | 追加：prune 的第二实参交给 `const_value` 折叠，**是数值常量就红**（`0L` ⇒ cutoff 恒负 ⇒ 永不失效）；prune-before-write 改用 `_live_in` 的活偏移比较；`.lastModified()/.delete()` 按 `function_body` 逐体认定 |
| C5 | `body.find("writeText(")` vs `body.find("getUriForFile(")`（只取首个匹配，无死分支过滤） | `_live_in(code, body, …)` 取**可达**写点（`path_feasible` 折 `if (false)` 与 `if (1 == 2)`），断言 `max(活写点) < min(活 URI 点)`；无活写点即红 |
| C8 | 只查 `pruneExpiredShareInboundFiles(` 在 `io_regions(vm_body)` 内 | 追加：`inputStream(`/`outputStream(`/`copyTo(` 的**每个活点**必须在 region 内，且必须存在活的 `copyTo(`（02-5 的拷贝外移 ⇒ 红） |
| C9 | `brace_body` 裸字符计数取 IO 区（字符串里的 `{` 多算一层，把 `cleanSharedFiles` 吞进区内） | 共享 `match_bracket` + mask 视图取 region；`cleanSharedFiles(` 用 `_live_in` 定位后断言 in-region |
| C10 | 只认 `File(context.filesDir, "…")` 构造器拼写 | 追加 `context.filesDir.resolve("…")` 扩展拼写（`DIR_RESOLVE_RE`，字面量仍支持 `"a" + "b"` 常量折叠；非字面量 fail-closed） |

同时删除自写 `strip_comments` / `fn_body` / `brace_body` / `_close_paren`（全部换共享实现）。

### `offload_relative_path_unify_check.py`

| 判据 | 旧形态（见证为何绿） | 新形态（见证为何红） |
|---|---|---|
| C1 | `CALL in body("fun readLinuxPath(")` + `resolved` 进 `sessionAwareHostFile` | 追加：join 的**第二实参必须是 cwd 值**（`_reads_ident`：词边界 + 一跳 local 派生），`null`/`""` ⇒ 红；映射实参判定改 `contains_word`（词边界，非子串） |
| C2 | `if CALL not in body("fun cmdRun(")` —— 体内出现子串即绿 | 取 `val outputPath = <expr>` 的 expr，交 `value_sources` 判定**求值来源**必须含 `resolveMaybeRelative`；`.also {}` 经 `peel_discarding` 剥掉后只剩 `raw` ⇒ 红；找不到绑定 fail-closed |
| C3 | `CALL in body("fun resolveImageUrl(")` | 同 C1：join 第二实参的 cwd 值判定 |
| S2 | `CHAIN_RE` + `strip_strings` 后 `"cwd" in args`（`cwd = ""` 的**参数名**命中） | 共享 `split_top`/`match_bracket` 切实参 + 命名参数解包（`name = value`），只判**值**侧；`cwd = ""` ⇒ 值侧无 cwd ⇒ 红 |
| S4 | `_dead_condition` 白名单（`^false` / `^!true` / `x && false`）+ 自写 `_dead_spans` | `path_feasible(code, pos)`（= `guard_feasible` 常量折叠）：`if (1 == 2)`、`if (!true)`、`if (x && !x)` 一律死；判「存在活拒绝 return」+「最后一个活 return 不是 0」 |
| Z1 | `resolveMaybeRelative(` 在函数体内 | 追加：映射实参必须携带 join 的**绑定符号**（`contains_word`） |
| Z2 | `request.cwd` 在函数体内 + 返回表达式以 `try { PRootKernel.resolvePathForSession(` 开头 | 追加：映射首实参必须携带 `sessionId` 值 + 返回表达式（`_return_exprs`，共享 `expr_end`）的 `value_sources` 必须含 `resolvePathForSession`（`.also{}` 透传 ⇒ 红） |
| Z3 | `resolvePathForSession(` 存在性（保留） | 不变 |
| Z4 | `startsWith("/")` / `"$cwd` 判定（保留） | 判定面改到 literal 视图同偏移（mask 视图会抹掉字符串内容） |

删除的自写设施：`strip_comments` / `strip_strings` / `declaration_slice` / `_bracket_close` / `_call_args` / `_returns` / `_returned_from_translation` / `_dead_condition` / `_dead_spans` / `_join_symbol`（→ `_bound_to_code_result`）/ `CHAIN_RE`。

## ④ 四层证据

**L1 本门（真树 + 自测臂）**

```text
$ python3 scripts/scan/file_provider_paths_coverage_check.py .
Summary: 0 violation(s) found                                    # rc=0
$ python3 scripts/scan/file_provider_paths_coverage_check.py --selftest
FILE_PROVIDER_PATHS_SELFTEST=PASS (36 arms)                      # 原 30 臂 + 6 条新臂（每条见证一条）

$ python3 scripts/scan/offload_relative_path_unify_check.py .
offload_relative_path_unify_check: … OFFLOAD-RELATIVE-PATH-UNIFY GATE OK   # rc=0
$ python3 scripts/scan/offload_relative_path_unify_check.py --selftest
OFFLOAD_RELATIVE_PATH_UNIFY_SELFTEST=PASS (24 fixtures: 22 violation shapes caught, 2 clean shapes green)
# 原 18 (16 dirty + 2 clean) + 6 条新臂，逐条打印 [PASS]
```

新增臂（每条对应一条见证，命名即症）：

| 门 | 新臂 |
|---|---|
| file_provider | `C10: median dir root spelled as context.filesDir.resolve(…)`（02-1 形态）· `C3: disk work sits next to an EMPTY IO block`（02-2）· `C4: prune is fed a constant clock`（02-3）· `C5: dead write planted before getUriForFile`（02-4）· `C8: the stream copy drifts outside the IO region`（02-5）· `C9: a "{" inside the IO block`（02-6） |
| offload | `C1: the join is fed null instead of the cwd parameter`（09-1）· `C2: --output keeps the helper name and returns the raw spelling`（09-2）· `C3: the image join is fed null`（09-3）· `S2: the named argument cwd = "" passes no cwd at all`（09-4）· `S4: the refusal sits behind if (1 == 2)`（09-5）· `Z1Z2: the mapping is handed null session + the raw spelling`（09-6） |

**L2 反攻（硬验收）**——用任务书指定的驱动，逐门两条命令：

```text
$ verify_batch5.py --witnesses witnesses-B.json --repo . --mode full --only file_provider_paths_coverage_check.py …
[1/6] 02-1 → CAUGHT (rc=1, exact)   [4/6] 02-4 → CAUGHT (rc=1, exact)
[2/6] 02-2 → CAUGHT (rc=1, exact)   [5/6] 02-5 → CAUGHT (rc=1, exact)
[3/6] 02-3 → CAUGHT (rc=1, exact)   [6/6] 02-6 → CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}       # restored=True ×6

$ verify_batch5.py … --only offload_relative_path_unify_check.py …
[1/6] 09-1 → CAUGHT (rc=1, exact)   [4/6] 09-4 → CAUGHT (rc=1, exact)
[2/6] 09-2 → CAUGHT (rc=1, exact)   [5/6] 09-5 → CAUGHT (rc=1, exact)
[3/6] 09-3 → CAUGHT (rc=1, exact)   [6/6] 09-6 → CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}       # restored=True ×6
```

12/12 `CAUGHT`、0 仍 `BLIND`、`restored` 12/12 True。

报错**指向被点名的判据**（全量重打取证，日志 `probe/t09_full_reattack.log`，12 条均 `rc=1`，`git status --porcelain src/` 事后为空）：

```text
02-1 C10 [MediaStore.kt] directory `filesDir/media-unregistered` is created (mediaBaseDir = context.filesDir[,.resolve](…"media-unregistered")) but no <files-path> root declares it …
02-2 C3 writeText() in exportLargeContentToFile runs OUTSIDE `withContext(Dispatchers.IO)` — a call merely sitting next to an (empty) IO block still blocks the UI thread
02-3 C4 the orphan prune is fed a constant clock `0L` — the cutoff becomes permanently negative, so no file is ever expired …
02-4 C5 getUriForFile precedes the live writeText in the export — the chooser may get a URI for a file that is not written yet …
02-5 C8 the share_inbound stream copy (inputStream / outputStream / copyTo) runs OUTSIDE `withContext(Dispatchers.IO)` — up to 100 MB on the UI thread right after the prune returned
02-6 C9 SharedShareStore.cleanSharedFiles is called outside `withContext(Dispatchers.IO)` in the ChatScreen share drain — listFiles() + per-file delete() on the UI thread
09-1 C1 readLinuxPath() feeds the shared join `null` instead of the cwd parameter — a relative --input is resolved against nothing
09-2 C2 cmdRun() normalises --output through something that is not resolveMaybeRelative() (`raw.also { resolveMaybeRelative(raw, request.cwd) }`) …
09-3 C3 resolveImageUrl() feeds the shared join `null` instead of the cwd parameter — a relative image_url is resolved against nothing
09-4 S2 cmdTranscribe(...) drops cwd from its arguments: cmdTranscribe(args, request.sessionId, cwd = "")
09-5 S4 the explicit non-mic source branch's refusal return is dead code (a constant-false guard) — a missing or unsupported --source falls straight through to the microphone
09-6 Z1 shizukuHostPath() joins the path into `guestPath` and hands something else to resolvePathForSession (null, raw.trim(), context) …
        Z2 shizukuHostPath() hands `null` as the session to resolvePathForSession — an absolute path then escapes the session scoping
```

**L3 邻门（9 道，全 rc=0）**

```text
offload_host_path_check            OK   (22 handler + 2 intent dispatcher, 0 violations)
offload_cli_args_check             OK   (contacts dispatch parsed, config --file cwd-aware)
offload_media_b64_wire_check       OK   (2 media encode sites NO_WRAP, consumer MIME decoder)
speech_active_engine_check         OK   (activeEngine binding wired)
shizuku_args_check                 OK   (argv parsing + call sites verified)
file_uri_parse_guard_check         GATE=GREEN
share_dir_prune_check              OK   (arm1..arm6 green)
downloads_atomic_update_check      OK   (5 update{} sites, 0 non-atomic writes)
a11y_handler_contract_check        OK   (prompt slot: identity + lock)
```

**L4 全量**

- `bash scripts/scan/scan.sh` 在并发窗口内被沙箱打断（S28 / da1027 已知现象：`scan.sh` 每门一子进程，16/112 处死掉、`OSError: [Errno 38] Function not implemented`），按任务书 §5 处置 ② 改**逐门循环 + 断点续跑**（`/tmp/t09_scan_all.py`，每门独立 `python3 scripts/scan/<g>.py .`，rc 落 `/tmp/t09_scan_results.txt`）：
  - **110/110 门脚本全绿，0 FAIL**（含 `file_provider_paths_coverage_check.py` 与 `offload_relative_path_unify_check.py`）；
  - `scan.sh` 的另 2 步不是门脚本、按原样单独跑也全绿：step 2 内联 `i18n consistency check` → `✅ No orphan keys (1582 refs, 1591 defs)` rc=0；step 7 `scripts/native/crash_fields_host_test.sh` → `ALL PASS` rc=0。合计 **112/112**。
- `python3 scripts/scan/test_scan.py` 全量 344 例在本机连续 4 次被 ENOSYS 风暴打断（`subprocess._try_wait` → `Errno 38`），按 da1027 纪律「施工期本地只跑本门 + 邻门，全量证据用分支 CI 日志」，改为**本门所属两组用例**直跑（等价于 test_scan 里注册的那两条断言）：
  ```text
  $ python3 -c "import test_scan as t; t.test_da1023_gates(); t.test_da1024_gates()"
    ✅ file_provider_paths_coverage_check selftest has teeth (FILE_PROVIDER_PATHS_SELFTEST=PASS)
    ✅ file_provider_paths_coverage_check real tree green
    ✅ offload_relative_path_unify_check selftest has teeth (OFFLOAD_RELATIVE_PATH_UNIFY_SELFTEST=PASS)
    ✅ offload_relative_path_unify_check real tree green
  RESULT PASS=30 FAIL=0
  ```
  全量 `ALL 344 CASES PASS` 由分支 CI 提供（见 ⑤）。
- `git status --porcelain src/` 在全部反攻之后为**空**（无变异残留）。

## ⑤ CI

- **分支** `fix/b5-fileprov-offrel` · **源码提交** `402b2833d`（`c514d49df` 判据改造 + `402b2833d` 6 条新臂）
- **run** `2686` / id `40207727426` / job `121212231967` / **attempt 1** / **`conclusion: success`**（15:59:01Z）
- **`run.head_sha` = `402b2833d`，与本地 `git rev-parse HEAD` 逐字符一致；本地 HEAD 与 `branches` API 的 `commit.sha` 也一致（三方同 sha）**
- 26 步：**唯一 skipped = `Publish to Releases`**（main 门控，符合预期），`not_ok = []`
- 日志 1,486,529 B 逐条核（`probe/t09-ci.log`）：
  - `Summary: 112 passed, 0 failed` **恰 1 条**（带框线前缀；其余小 Summary 为子套件）；
  - `ALL 344 CASES PASS` **恰 1 条**；
  - 门头 `━━━ [N/112] ━━━` 的编号 **112 个互异**（无跳号/重号）；
  - 编译错误行 `^ e: ` = **0**；
  - 含 `FAILED` 的行共 **6** 条 → 逐条确认**全部是 PASSED 用例名子串**（`……produces FAILED without calling provider PASSED` / `reason PERSISTENCE_FAILED maps … PASSED` / `phase FAILED maps … PASSED` / `terminal FAILED maps … PASSED` / `reason EXECUTION_FAILED maps … PASSED` / `the fixed tail still reaches a legal FAILED terminal PASSED`），**游离失败 0**。

### 收口时的 sha 关系（报告提交在源码绿之后）

本报告的提交发生在 CI 绿之后（报告需要引用上表读数）。按既往波次的口径，以「源码树与绿 sha 逐字节等价」替代「HEAD 恰等于绿 sha」，判据是一行可复核命令：

```bash
git diff --name-only 402b2833d..HEAD -- . ':(exclude)fix-reports'   # 必须输出 0 行
```

实测输出为空 ⇒ CI 绿的那棵树 = 分支 HEAD 的源码树，差异仅 `fix-reports/FIX-BATCH5-t09.md`。

## ⑥ 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

- **R1 目录根的两种拼写已覆盖，字符串模板仍未覆盖。** `dir_root_sites` 认 `File(context.filesDir, "x")` 与 `context.filesDir.resolve("x")`，但 `File("${context.filesDir}/media")` 这种模板拼接与 `context.filesDir.toPath().resolve("x")` 都不认。
  - 触发条件：仓库里出现这两种拼法且目录未声明（当前 `grep -rn '${context.filesDir}'` = 0 处）。
  - 触发后方案：`_root_matches` 增加「模板首段是 `context.filesDir`」与 `toPath().resolve(` 两形态（literal 视图上读模板文本，非字面量 fail-closed）。
  - 搁置理由：本波 6 条见证未涉及，且实测仓内 0 处；按半径纪律不扩面。
- **R2 S4 仍锚「分支内出现字面量 `"error"`」。** 把 payload 抽成 helper（`return notSupported(...)`）会假红。
  - 触发条件：有人重构 `cmdTranscribe` 的非 mic 分支。
  - 触发后方案：把 helper 名纳入判据，或改成「分支内任一带非零码的 return 且其体引用错误 payload」。
  - 搁置理由：现状无此重构；假红方向安全（fail-closed，不会被用来放行缺陷）。
- **R3 cwd 的「一跳 local 派生」解析只支持一跳。** `val d = request.cwd; val e = d; f(e)` 会假红。
  - 触发条件：出现两跳及以上别名链（当前 0 处）。
  - 触发后方案：`_reads_ident` 的 `last_write` 递归到 N=2 跳并加环检测。
  - 搁置理由：假红方向安全，且单跳已覆盖真实写法。
- **R4 C11 的「活」仍是启发式**（名字在文件内出现 ≥2 次，沿用原判据）。
  - 触发条件：出现「死别名 + 另一处无关同名出现」的组合。
  - 触发后方案：改成「名字出现在写路径/IO 区内」，用 `_live_in` 派生。
  - 搁置理由：02-1 的绕过已被 C10 独立抓住（实测），C11 只是二层；P2 加固。
- **R5 `_bound_to_code_result` 用一条 `val X = call(` 正则。** 解构声明、带注解、多行类型标注会漏绑 ⇒ 该条断言跳过（方向是**放行**，即 fail-open）。
  - 触发条件：`readLinuxPath` / `resolveSourcePath` / `shizukuHostPath` 的绑定写法改变。
  - 触发后方案：改用 `last_write` 派生绑定（返回 `(pos, rhs, is_decl)`），或绑定取不到时 fail-closed 报违反。
  - 搁置理由：三处现写法稳定且被 C1/S1/Z1 的其他断言交叉覆盖；升级触发 = 任一函数被重构。
- **R6 全量 `test_scan.py` 本机未跑完**（ENOSYS 风暴），改用「本门两组用例 + 分支 CI 全量」取证。
  - 触发条件：分支 CI 未提供 `ALL 344 CASES PASS`。
  - 触发后方案：空档窗口重跑全量，或用 `/var/minis/shared/tools/gate_run.sh --full`（全局 flock）独占跑。
  - 搁置理由：CI 每轮都跑 `scan.sh` + `test_scan.py`（`build-apk.yml:47,51`），本地重复跑无新增证据。

## ⑦ `test_scan.py` 注册项（交主会话统一注册）

两门**已在** `scripts/scan/test_scan.py` 注册，本支未改该文件；标记串保持不变、臂数变大，故**无需新增注册项**，只需在并集收口时确认：

| 分组 | 注册行 | 脚本 | 标记串 | 臂数 |
|---|---|---|---|---|
| `DA1023_GATES` | `test_scan.py:2855` | `file_provider_paths_coverage_check` | `FILE_PROVIDER_PATHS_SELFTEST=PASS` | 36（原 30） |
| `DA1024_GATES` | `test_scan.py:2883` | `offload_relative_path_unify_check` | `OFFLOAD_RELATIVE_PATH_UNIFY_SELFTEST=PASS` | 24 fixtures（原 18） |

两条断言（`selftest has teeth` / `real tree green`）本支均已实跑通过（见 ④ L4）。

## ⑧ 复现命令

```bash
cd /var/minis/shared/work/meta-audit-v5/batch5/wt/t09          # 基线 e145ec961

# L1 本门
python3 scripts/scan/file_provider_paths_coverage_check.py .
python3 scripts/scan/file_provider_paths_coverage_check.py --selftest
python3 scripts/scan/offload_relative_path_unify_check.py .
python3 scripts/scan/offload_relative_path_unify_check.py --selftest

# L2 反攻（12 条，逐门两条命令）
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-B.json --repo . --mode full \
  --only file_provider_paths_coverage_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t09-file_provider_paths_coverage_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch5/verify_batch5.py \
  --witnesses /var/minis/shared/work/meta-audit-v5/batch5/witnesses-B.json --repo . --mode full \
  --only offload_relative_path_unify_check.py \
  --out /var/minis/shared/work/meta-audit-v5/batch5/probe/t09-offload_relative_path_unify_check.json

# 全量重打取证（打印每条完整报错）
python3 /var/minis/shared/work/meta-audit-v5/batch5/probe/t09_full_reattack.py

# L3 邻门
for g in offload_host_path_check offload_cli_args_check offload_media_b64_wire_check \
         speech_active_engine_check shizuku_args_check file_uri_parse_guard_check \
         share_dir_prune_check downloads_atomic_update_check a11y_handler_contract_check; do
  python3 scripts/scan/$g.py . >/dev/null 2>&1 || echo "FAIL $g"; done

# L4 全量（逐门 + 断点续跑；scan.sh 在并发窗口会被沙箱打断）
python3 /tmp/t09_scan_all.py 240        # 每门独立进程，rc 落 /tmp/t09_scan_results.txt
python3 scripts/scan/test_scan.py       # 或空档窗口/ gate_run.sh --full
git status --porcelain src/             # 必须为空
```
