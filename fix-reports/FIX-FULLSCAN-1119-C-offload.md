# FIX-FULLSCAN-1119-C-offload

**支**：`fix/offload-bounds-1119` · worktree `/var/minis/shared/work/r3d1119f/offload` · 基线 `ecb03d92`
**范围**：F-03 / F-09 / F-14 / F-15 / F-16 / F-22（fullscan-1119 C2 包，6 条）
**未合 main。**

---

## 1 结论

六条全部成立，全部修完；无一证伪。

| ID | 原级 | 先证结论 | 修法 |
|---|---|---|---|
| **F-03** | P0 | 成立（读码，主会话已实锤）`ShizukuBackend.runProcess` 两条管道 `forEachLine { append }` 进裸 `StringBuilder`，零上限 | 换 `BoundedStreamCapture`（字节记账）+ 截断标记折进 stderr + `AppLogger.warning` |
| **F-09** | P1 | 成立，且比报告说的更糟：**窗口内**超长行连 `Log.w` 都没有（`Lines.oversizedLines` 无人读取） | 行上限由协议 inline 预算派生（256 KiB → Base64(512 KiB)≈683 KiB）+ 两条超长分支统一 `reportDroppedFrame`（`Log.e` + `AppLogger` + 摘要 + 0-chunk 时抛错） |
| **F-14** | P1 | 成立，**两种形态都复现**（见 §2） | `applyPax` 改为按**字节**解析原始 payload；`readText` 拆出 `readRaw` |
| **F-15** | P1 | 成立：`hostFile.readBytes()` 无上限 | 走 `BoundedFileRead`（上限 = 协议 `MAX_FILE_BYTES`），超限抛 `ImageInputError`（明确失败，不截半张图） |
| **F-16** | P1 | 成立：池拒绝时 `handleNewWindow` 提前 return，`onCreateWindow` 仍 `return onNewWindow != null` = `true` 且不发 `sendToTarget` | 先取 transport → 调 handler → `PopupWindowPolicy.decide(hasHandler, transport.webView != null)`；拒绝路径 `declinePopup`（null transport + `sendToTarget`）并 `return false` |
| **F-22** | P2 | 成立：`interceptMinisURL` 用 `localFile.readBytes()` 无上限，且 `ensureMetaViewport` 在「已有 viewport」时还多一次 `String→ByteArray` 往返 | 预览前查 `length()` 超限返回可读 413 页；已有 viewport 时直接透传原始字节（`MinisPreviewPolicy.declaresViewport` 字节级判定） |

---

## 2 F-14 先证（任务书要求：写不出复现就登记不修）

用 Python `tarfile` 造**真实 PAX 归档**（`format=PAX_FORMAT`，中文名 139 字节 > ustar 100 字节 → 必走 `path=` 记录），再用本模块 `TarReader` 解回：

```
修复前   member[0] type=0 name=[?????????????????????????????????????????????.txt]   ← 名字全丢，回落到截断的 ustar name
修复后   member[0] type=0 name=[中文文件名中文文件名…×9.txt]                          ← 完整
```

手工构造第二形态（`path=中文.txt` 后紧跟 `mtime=` 记录）：

```
修复前   name=[中文.txt\n20 m]      ← 值把下一条记录吞了（串行 / 错位）
修复后   name=[中文.txt]
```

**根因断言**：`applyPax` 收到的是**解码后的 String**，而 PAX 记录长度前缀 `LEN` 是**字节**数。ASCII 下两者相等，中文下一个 139 字节的记录只占 55 个 char，于是 `cursor + length > text.length` 直接 break（丢名）；短中文名时 `length` 又越过记录尾（吞下一条）。

---

## 3 三条铁律逐条对照

| 铁律 | F-03 | F-09 | F-14 | F-15 | F-16 | F-22 |
|---|---|---|---|---|---|---|
| ① 有边界 | 每流 `shellOutputKb*1024` 字节，单行上限 `maxBytes` char | 单行 ≤ Base64(512 KiB)≈683 KiB，严格 < 1 MiB 读窗口 | PAX payload ≤ `MAX_LONG_NAME_BYTES` 1 MiB（既有） | ≤ `MAX_FILE_BYTES` 64 MiB（既有） | 不新增缓冲；契约本身自洽 | ≤ `MAX_FILE_BYTES` |
| ② 超界可观测 | stderr 追加标记行（含实际上限 + 丢弃字节数）+ `AppLogger.warning` | `Log.e` + `AppLogger.warning` + 首 48 字节摘要（能看出是 `"t":"media"` 还是 `tu_done`）；未产出任何 chunk 时直接抛 `ModelStreamErrorException` | 不适用（错位即错误解析，修复后不再发生；`OffloadBoundsTest` 逐例覆盖） | `ImageInputError` → 网络层 `error` 字段（**明确失败**，不截半张图） | `Log.w` 说明拒绝原因 + 显式 null transport（WebView 得以释放弹窗） | 413 + `text/plain` 可读页（含大小与上限），不是空白页 |
| ③ 正常路径不变 | 未截断时 `text()` 与旧 `forEachLine+append('\n')` 逐字节相同；`clippedResult` 无标记时走原 `trimEnd('\n')` 分支，**不做 `trim()`** | 小于上限的行完全走原路径 | ASCII 名、`path=` 后无记录、`linkpath=` 均照旧 | 限内文件字节不变（`OffloadBoundsTest` 逐字节比对） | 接受路径行为不变（`transport.webView != null` → `true`） | 限内 HTML 输出不变；viewport 已存在时输出**本来就该是**原始字节 |

**复用既有常量，未新建第二份数值**：
- F-03 → `AgentRuntimeLimitsPrefs.shellOutputKb()`（`PersistentShell` / `ExecutionCoordinator` 用的同一旋钮，`ConfigBuiltins` 里已是公开配置项）
- F-09 → `PayloadSizePolicy.base64Size(MAX_INLINE_BINARY_BYTES)`（协议自己的 inline 预算）
- F-15 / F-22 → `PayloadSizePolicy.MAX_FILE_BYTES`（协议自己的单文件上限，原注释就写着 "callers should not fail closed rather than read it into memory"）

**一个刻意的非选择**：F-09 的行上限**没有**取 `MAX_POLL_READ_BYTES`（1 MiB 窗口）本身。`BoundedLineReader` 的"无换行且 `got > maxLineBytes`"判据在两者相等时**永不成立**，超窗口行会永久停在 `Partial`。上限必须严格小于窗口；Base64(512 KiB) ≈ 683 KiB 留下 ~349 KiB 余量。此点由 `OffloadBoundsTest.the stream line ceiling admits a legal max-size media frame` + 变异臂 M20b 双向钉住。

---

## 4 文件改动

| 文件 | 性质 | 说明 |
|---|---|---|
| `offload/ShizukuBackend.kt` | 改 | F-03：`BoundedStreamCapture` ×2、`drainBounded`、`clippedResult`（三条返回路径统一） |
| `offload/BoundedStreamCapture.kt` | 新增 | F-03 的纯 JVM 有界缓冲（字节记账、CR/LF/CRLF 语义与 `readLine` 一致、marker） |
| `offload/BoundedFileRead.kt` | 新增 | F-15 / F-22 共用的有界读（`Ok` / `TooLarge` / `Failed` 三态，size 检查做两次防"边读边长"） |
| `offload/MinisPreviewPolicy.kt` | 新增 | F-22 的字节级 viewport 判定 + 预览体积判定 |
| `offload/PopupWindowPolicy.kt` | 新增 | F-16 的返回值/消息自洽决策 |
| `offload/StreamOversizePolicy.kt` | 新增 | F-09 的「未产出→失败 / 已产出→记录并继续」决策 + 摘要 |
| `sandbox/offload/ChatStreamOffloadHandler.kt` | 改 | F-09：行上限派生、两条超长分支统一 `reportDroppedFrame`、`readFrameHead`（只读 48 字节） |
| `sandbox/offload/FastioTar.kt` | 改 | F-14：`applyPax(ByteArray)` + `readRaw` + `parsePaxLength`/`indexOfByte` |
| `sandbox/offload/ModelUseOffloadHandler.kt` | 改 | F-15：走 `BoundedFileRead`，超限抛 `ImageInputError` |
| `browser/BrowserUseManager.kt` | 改 | F-22：预览体积检查 + 可读 413 + 透传原始字节；F-16：`onCreateWindow` 契约 + `declinePopup` |
| `test/.../offload/OffloadBoundsTest.kt` | 新增 | 21 条行为断言（纯逻辑） |
| `test/.../offload/OffloadBoundsWiringTest.kt` | 新增 | 9 条接线形状断言（Android 类无法单测，钉"边界真的装到了该装的位置"） |

`git diff --stat`：5 文件 +323 / −48，另 5 个新生产文件 + 2 个新测试文件。
**禁碰项零命中**：`ChatViewModel.kt` / `scripts/scan/**` / `backup/**` / `ui/chat/**` / `provider/**` / `tools/FileReadTool.kt` 均未触碰。

**越界自查（一次真的越界并被回退）**：初期为把行上限放在 `PayloadSizePolicy`、把默认值改在 `BoundedLineReader`，改过这两个文件；核对任务书文件集后 `git checkout --` 回退，把派生上限与策略全部收进允许的 `ChatStreamOffloadHandler.kt` + 新增 `offload/*.kt`。

---

## 5 验证

### 5.1 本地 JVM 装置（`shared/work/r3d1119f/offload-jvm/`）

源码经**符号链接**接入（`src/*.kt` 指向 worktree 真文件，不复制），因此测的就是要提交的代码。

```
OK (30 tests)   RESULT: PASS
```

覆盖：F-03 六条（小输出逐字节 / CR 与 CRLF 语义 / 触顶停增 / 单条巨行 / CJK 字节记账 / 多批 CJK 不漂移）、F-14 四条（长中文名 / 不吞下条 / 2·3·4 字节混合 / ASCII 回归）、F-15 四条（限内 / 恰好等于上限 / 超一字节明确失败 / 默认上限就是协议常量）、F-22 三条（体积判定 / 字节级 viewport / 中英混排）、F-16 两条（三态决策 / 拒绝原因可区分）、F-09 四条（合法帧可容纳 / 严格小于窗口 / 双分支语义 / 摘要识别 `media` 且受限）、接线九条。

### 5.2 变异负控（`mutate.py`，21 臂）

| 臂 | 变异 | 结果 |
|---|---|---|
| M1 | F-03 去掉上限（buffer 无限增长） | RED |
| M2 | F-03 上限在但静默（marker 恒 null） | RED |
| M3 | F-03 预算按 char 记账 | RED |
| M4 | F-03 CR 不再算行终止符 | RED |
| M5 | F-14 退回「解码成 String 再按字节游标」（**修复前原样**） | RED |
| M6 | F-14 值保留尾部换行 | RED |
| M7 | F-15 两道闸都拆（= 原 `readBytes()`） | RED |
| M8 | F-15 边界比较差一（`>` → `>=`） | RED |
| M9 | F-22 预览上限恒真 | RED |
| M10 | F-22 viewport 只认双引号 | RED |
| M11 | F-16 没拿到 WebView 也判接受（**原泄漏形态**） | RED |
| M12 | F-09 已产出也判失败 | RED |
| M13 | F-09 消息丢掉字节事实 | RED |
| M14 | F-09 摘要不限长 | RED |
| M15 | F-03 捕获换回裸 `StringBuilder`（**原 bug 形态**） | RED |
| M16 | F-09 窗口内超长行重新被忽略 | RED |
| M17 | F-15 换回无界 `readBytes()` | RED |
| M18 | F-16 换回 `return onNewWindow != null` | RED |
| M19 | F-22 拦截器上限删掉 | RED |
| M20b | F-09 行上限抬到整个读窗口（超长永不触发） | RED |
| M20 | F-14 改用 `readText().toByteArray()` | RED |

**21/21 RED-PRECISE**，基线 `GREEN(expected)`，每臂跑完 `RESTORE: OK`（快照 + 逐字节 sha256 校验，不用 `git checkout`）。

三次变异过程本身暴露的问题（已修）：
1. **M5 首版无效**：旧逻辑跑完新逻辑又覆盖，臂恒绿；改成 `return` 早退才真的复现修复前行为 —— 变异臂「跑得到」不等于「真的变了行为」。
2. **M3 首版 GREEN(BLIND)**：原测试只喂一次 CJK，`byteSafePrefixLength` 已按字节截断，char 记账的漂移看不出来。补了「多批 CJK，保留字节数必须 ≤ 上限」才转红 —— 这是本轮唯一一次真实的覆盖缺口，被变异抓出来了。
3. **M20b 暴露设计风险**：初版把行上限设成 `MAX_POLL_READ_BYTES`，建臂时才发现那会让"超长检测"永不触发、超长行永久 `Partial`。改回 `base64Size(MAX_INLINE_BINARY_BYTES)` 并加断言。
4. 另有一次**变异烧进源码**：变异脚本被 stall-guard 打断后 `val n = head.size` 留在 `StreamOversizePolicy.kt` 里，下一次基线跑直接红。发现并还原，之后所有臂都走 `finally` + sha256 校验。

### 5.3 未在沙箱验证的部分（如实登记）

- **Android 类未编译**：沙箱无 Android SDK，`ShizukuBackend` / `ChatStreamOffloadHandler` / `ModelUseOffloadHandler` / `BrowserUseManager` 无法 `kotlinc` 编译。已做手工括号/字面量配平检查（去注释去字符串后 `{}`/`()` 全为 0），真正的编译由 CI 见证。
- **未跑真实大输出命令**：按纪律要求，不在沙箱里跑 `du -sb` / `find | xargs` 之类（沙箱在 app 进程组，OOM 会杀 app）。上限由纯函数与变异臂钉住。
- `scan.sh` / `test_scan.py` / gradle 全量均未运行（纪律要求）。

---

## 6 范围外发现（登记，不在本支修）

1. **`ModelUseOffloadHandler.readLinuxPath`（`--input` / `--system-file`）仍是无界 `readText()`**（同文件 `:1084`）。同族问题，但不在 F-15 的点名范围内（F-15 指 `resolveImageUrl`），且它是文本而非图片。**建议下一轮并入 F-15 同修**，`BoundedFileRead.readStream` 已经可以直接复用。
2. **`BrowserTabPool.handleNewWindow` 的两条拒绝路径仍只 `return`**（`isDisposed` / `MAX_TABS`）。本支在 `BrowserUseManager` 侧已经让契约自洽（拒绝→发 null transport + 返回 false），弹窗不会泄漏；但**原因区分**（标签满 / 池销毁）目前靠池侧 `Log.w` 的文字，正确做法是让池把原因回传（例如 `onNewWindow: (Message) -> String?`）。`BrowserTabPool.kt` 不在本支文件集，登记待派。
3. **`ChatStreamJsonl.encode` 的 media 帧没有体积闸**：生产侧仍可能把一张 >512 KiB 的图编码成超长行。本支把消费侧的"静默丢弃"改成"可观测失败"，但根治应在生产侧走 `PayloadSizePolicy.mustSpill` → 文件引用。`ChatStreamJsonl.kt` 不在本支文件集。
4. F-09 的"0-chunk 时抛错"依赖上层 `ChatStreamErrorPolicy` 把 null kind 归为 FATAL。若上层将来改为按 kind 重试，这条需要重新评估（现行为与既有 error-line 路径一致，未改变上层语义）。

---

## 7 提交与证据

| 提交 | 内容 |
|---|---|
| `ef85da07` | 六条修复 + 两套测试（代码） |
| `401b6878` | 测试名更正（名字断言与断言体相反） |

**run #3336 / `44460708921` @ `401b6878eb122482faaea43539770c3b0d54c076`**（= 本地 HEAD = 远端 tip，三方逐字符一致）
五 job 全 success：`build-apk` / `unit-tests` / `scan-gate` / `lint` / `publish`。
- unit-tests：**9854 条 PASSED**，`OffloadBounds*` 新测试 **30 条逐条 PASSED**（含改名后的 `stream reader ceiling is derived from the inline budget and stays below the poll window`）；日志里 4 行含 `FAILED` 字样全是 PASSED 的用例名，零真失败。
- scan-gate：`Summary: 115 passed, 0 failed`，且日志明写 `scripts/scan/** untouched in this push — skipping the fixture self-test` —— 本支未动门，此次绿**不含夹具证据**（按机制跳过）。
- publish 两步：分支构建受 main 门控，`publish` job 在本 workflow 里为 success（无 release 影响）。

**中途一次 lint 假红（已排除）**：run #3334 的 lint 失败原因是 `Could not find hamcrest-integration-1.3.jar (org.hamcrest:hamcrest-integration:1.3)` —— Maven 依赖下载失败，非本支代码问题；同一 sha 重跑 #3336 的 lint 为 success。

本报告为绿之后的独立提交，非报告差异为空（`git diff 401b6878..HEAD -- src/` 为空）。

---

## 8 复核入口

```sh
# 装置（工作树之外，不会被 git add -A 扫走）
sh /var/minis/shared/work/r3d1119f/offload-jvm/run.sh          # 期望 OK (30 tests)
python3 /var/minis/shared/work/r3d1119f/offload-jvm/mutate.py  # 21 臂；ARM=N 单跑
# F-14 复现探针
java -cp out:…  ProbeKt pax.tar    # /var/minis/shared/work/r3d1119f/probe-f14/
```
