# FIX-FILEURL-CONTRACT — `file://` 契约收口（方案 B：跨界编码 / 内部原始路径）

| 项 | 值 |
|---|---|
| 分支 | `fix/fileurl-contract` |
| 基点 | `fix/debt-sweep-1026` @ `8d00395af`（= main `627f23f2e` + T2 三笔修复 + 报告），本支是 T2 的延续 |
| 报告文件 | `fix-reports/FIX-FILEURL-CONTRACT.md` |
| 真机可验 | 否（沙箱无 Android）→ 静态一致性 + JVM 切片探针 + 分支 CI |
| 停止节点 | 分支 CI 绿 = 结束，不合 main |

---

## 1. 问题

T2 已把两个**消费端**（`WebPreviewBottomSheet.openExternalFromSheet`、`WebPreviewShortcut.pin`）
改为 `decodeUriPath(url.removePrefix("file://"))`，并把 `file_uri_parse_guard` 的
`Uri.parse(...).path` 豁免表清空。但**构造端**仍有三处裸拼
（`ChatScreen:1711/1775`、`WebAppActivity:671`），于是留下一个窄回归：

> 文件名/目录名含**字面 `%XX`** 十六进制对时（`50%23.html`、`v1%41.html`），
> 消费端的 `decodeUriPath` 会把它当成转义序列误解码（`50%23.html` → `50#.html`）。
> T2 之前用 `Uri.parse().path` 的路径不会有这个问题——两条契约被拼接在了一起。

同时 `ui/sandbox/FilePreviewScreen.kt` 已有正解先例（`fileUrlFor` + `encodeFileUrlPath`，
`backlog §1021-V` 真机验证过 `#` 截断），仓里等于**两套 `file://` 契约并存且无门守护**。

## 2. 契约（本支核心产出）

> **跨界编码，内部解码。**
>
> 一条 `file://` 字符串的**下一个消费者**决定它的形态：
>
> * 下一个消费者是**系统组件**（`WebView.loadUrl` / `WebViewAssetLoader` 的 path segment /
>   `Intent` data / `FileProvider` / 跨 Activity）→ 必须**百分号编码**（`encodeFileUrlPath`）。
> * 下一个消费者是**本仓的路径语义**（`File(…)`、`exists()`、`parentFile`、`listFiles`、
>   路径比较）→ 必须**解码回原始路径**（`decodeUriPath`）。
> * 只做**前缀判定**（`startsWith("file://")`）的站点与形态无关，不改。

编码器/解码器是**已存在的两个纯函数**，本支不新增实现：

| 函数 | 位置 | 语义 |
|---|---|---|
| `encodeFileUrlPath(path)` | `ui/sandbox/FilePreviewScreen.kt`（`file-url-path-encode` 切片区，JVM-pure） | RFC 3986 unreserved + `/` 原样，其余 UTF-8 字节 → `%XX`；`%` 本身转义（`50%off` → `50%25off`），**对已编码输入不幂等**（这正是让字面 `%XX` 名字能往返的原因） |
| `decodeUriPath(input)` | `ui/chat/UriPathDecode.kt`（JVM-pure） | 只解 `%XX`，`+` 是字面量，坏转义原样保留、永不抛 |

往返性质：`decodeUriPath(encodeFileUrlPath(p)) == p`（`FilePreviewUrlEncodeTest` 已钉）。

**为什么方向是「构造端编码」而不是「消费端不编码」**：仓里附件/媒体管线**早就是编码形态**——
`ChatPromptAndTools:1725/1781`、`ChatTranscriptRebuild:207/209`、`ChatViewModel:3034`、
`StreamingMarkdownText:1859`、`MarkdownText:783`、`InlineMediaPlayer:394/662` 全部用
`Uri.fromFile(file)`，平台编码器输出的就是 URL 形态；而它们的消费端
（`resolveMdMediaFile`、`ChatLinkResolver`）已经在 `decodeUriPath`。即：**编码契约是既有的多数派，
裸拼的五处是离群点**。本支把离群点拉齐，而不是把多数派改回去。

## 3. 全量普查表（21 个代码站点 + 2 个非字面族）

命令：`grep -rn "file://" src/android/app/src/main/java/ --include="*.kt"`（79 行命中，剔除纯注释后 21 行代码）。

### 3.1 `file://` 字面量站点

| # | 站点 | 角色 | 下一消费者 | 契约判定 | 本支动作 |
|---|---|---|---|---|---|
| 1 | `ui/chat/ChatScreen.kt:1711` | 构造端 | holder → asset loader（系统）+ `currentUrl` 被 sheet/pin 解码 | **违反**（裸拼） | 改 `fileUrlFor(file)` |
| 2 | `ui/chat/ChatScreen.kt:1775` | 构造端（深链路径） | 同上 | **违反**（裸拼） | 改 `fileUrlFor(file)` |
| 3 | `webapp/WebAppActivity.kt:671` | 构造端（瞬时） | `toAssetLoaderUrl` | **违反**（裸拼） | 改 `toAssetLoaderUrl(fileUrlFor(file))` |
| 4 | `ui/preview/WebViewHolder.kt:84` | 消费端 | `File(…)` → `InternalStoragePathHandler` root | **违反**（`File` 拿到编码路径） | 加 `decodeUriPath` |
| 5 | `ui/preview/WebViewHolder.kt:514` `toAssetLoaderUrl` | 消费端→跨界 | asset loader path segment | **违反**（编码输入被二次编码：`%` → `%25`） | 先解码段名再 `encodePathSegment` |
| 6 | `ui/preview/WebViewHolder.kt:545` `fromAssetLoaderUrl` | 构造端（发布） | `currentUrl` → sheet 解码 / pin 解码 / ChatScreen 相等比较 | **违反**（发布原始路径，与入参编码形态不等） | 返回值 `encodeFileUrlPath` |
| 7 | `ui/preview/WebPreviewBottomSheet.kt:487` | 消费端 | `FileProvider` + `File` | ✅ 合规（解码） | 不改（冻结） |
| 8 | `ui/preview/WebPreviewBottomSheet.kt:473` | 前缀判定 | — | ✅ 形态无关 | 不改（冻结） |
| 9 | `ui/preview/WebPreviewBottomSheet.kt:216` | 前缀判定（pin 入口 gate） | — | ✅ 形态无关 | 不改（冻结） |
| 10 | `ui/preview/WebPreviewShortcut.kt:60` | 消费端 | 沙箱资源路径 + FileProvider | ✅ 合规（T2 解码） | 不改（冻结） |
| 11 | `ui/preview/WebPreviewShortcut.kt:34` | 前缀判定 | — | ✅ 形态无关 | 不改（冻结） |
| 12 | `ui/chat/StreamingMarkdownText.kt:1801-1802` | 消费端 | `File` | ✅ 合规（解码） | 不改 |
| 13 | `ui/chat/ChatLinkResolver.kt:125` | 消费端 | `File` / PRoot 解析 | ✅ 合规（解码） | 不改 |
| 14 | `ui/preview/WebViewHolder.kt:83` | 前缀判定（root 分支） | — | ✅ 形态无关 | 不改 |
| 15 | `ui/preview/WebViewHolder.kt:497` | 常量 `FILE_URL_PREFIX` | — | ✅ 单源常量 | 不改 |
| 16 | `ui/sandbox/FilePreviewScreen.kt:1758` `fileUrlFor` | 构造端（**正解先例**） | `WebView.loadUrl` | ✅ 合规 | 放宽可见性 `private`→`internal`（作为跨界 URL 的唯一构造器复用，见 §4.4） |
| 17 | `ui/chat/KatexWebViewPool.kt:45` | 构造端（常量） | `loadUrl` | ✅ 无路径成分（`file:///android_asset/…`） | 不改 |
| 18 | `ui/markdown/KaTeXView.kt:409` | 构造端（常量） | `loadUrl` | ✅ 同上 | 不改 |
| 19 | `sandbox/offload/ModelUseOffloadHandler.kt:1235` | 消费端（**外来**输入） | `File` 路径语义 | ⚠️ 歧义：模型/agent 直接写 `file:///host/path`，仓内**无构造端** | 不改，登记 §7-R2 |
| 20 | `sandbox/offload/ModelUseOffloadHandler.kt:171/173` | 前缀判定（`--output` / `--input` 规范化） | — | ⚠️ 同上（外来） | 不改，登记 §7-R2 |
| 21 | `sandbox/offload/ModelUseOffloadHandler.kt:1239` | 错误消息文案 | — | ✅ 文案 | 不改 |

### 3.2 非字面族（形态已合规，本支只作证据）

* **`Uri.fromFile(…)` 构造族（10 处）**：`ChatPromptAndTools:1725/1781`、
  `ChatTranscriptRebuild:207/209`、`ChatViewModel:3034`、`StreamingMarkdownText:1859`、
  `MarkdownText:783`、`InlineMediaPlayer:394/662`、`ChatMutationMethods:504`。
  平台编码器 → 输出 URL 形态 → 消费端 `decodeUriPath` 正好互补。
  **这是「跨界编码」不是本支发明的方向的直接证据。**
* **`Uri.parse(...).path` 消费族 = 0 处**：`file_uri_parse_guard_check` F4 的 exemption 表已空
  （T2 清掉最后一条 `WebPreviewShortcut`），本支不新增。

### 3.3 同一路径：构造 → 每个消费端（链路表）

路径示例 `我的 报告%23.html`（CJK + 空格 + **字面 `%23`**，最坏样本），文件真实绝对路径
`/var/minis/browser/我的 报告%23.html`：

| 阶段 | 形态 | 站点 |
|---|---|---|
| 构造 | `file:///var/minis/browser/%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A%2523.html` | `ChatScreen.openHtmlPreview` → `fileUrlFor` |
| → holder 入参 | 同上（编码） | `WebViewHolder(initialUrl)` |
| → asset loader root | `/var/minis/browser`（解码后的 `parentFile`） | `init`: `File(decodeUriPath(…)).parentFile` |
| → 载入 WebView | `https://appassets.androidplatform.net/%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A%2523.html` | `toAssetLoaderUrl`（解码段名 → 段编码器） |
| ← WebView 上报 | `https://appassets.androidplatform.net/%E6%88%91…%2523.html` | `onPageStarted` |
| 发布 `currentUrl` | `file:///var/minis/browser/%E6%88%91…%2523.html`（**与入参逐字节相同**） | `fromAssetLoaderUrl` → `encodeFileUrlPath` |
| → 打开外部 | `FileProvider` + `/var/minis/browser/我的 报告%23.html` | `openExternalFromSheet`：`decodeUriPath` |
| → pin 到桌面 | resourcePath = `/browser/我的 报告%23.html`（原始路径；`minis://` 的段编码由 `Uri.Builder` 承担，见 §7-R3） | `WebPreviewShortcut.pin`：`decodeUriPath` |
| → holder 复用比较 | 编码 vs 编码 → **相等** | `ChatScreen:1713` `existing.currentUrl != url` |

编码/解码/原样逐行标注；`%` 的往返（`%23` 字面 → `%2523` → `%23`）是这张表的关键一格。

## 4. 改动（逐站点前后）

8 文件 +450/−40。生产行为文件只有 4 个（表 A），其余是同一契约的装置（表 B）。

### 4.1 表 A — 生产代码（4 文件）

| 站点 | 前 | 后 | 为什么 |
|---|---|---|---|
| `ChatScreen.kt:1711` `openHtmlPreview` | `val url = "file://${file.absolutePath}"` | `val url = com.rikkaminis.app.ui.sandbox.fileUrlFor(file)` | 构造端统一走单源编码器；这条路是 WebView 载入的入口，也是 `currentUrl` 比较的基准字符串 |
| `ChatScreen.kt:1775` pin 深链路径 | 同上 | 同上 | 同族第二处（深链重放后再进 holder） |
| `WebAppActivity.kt:671` | `toAssetLoaderUrl("file://" + file.absolutePath)` | `toAssetLoaderUrl(fileUrlFor(file))` | 瞬时 URL 也按契约编码：`toAssetLoaderUrl` 现在**消费**编码形态，裸拼会让段名里的字面 `%XX` 被误解码 |
| `WebViewHolder.()init:84` | `File(initialUrl.removePrefix("file://")).parentFile` | `File(decodeUriPath(initialUrl.removePrefix("file://"))).parentFile` | 入参已是编码 URL，`File(…)` 是路径语义 → 先解码。不改则 loader root 变成一个不存在的 `%E6%88%91…` 目录，本地预览全 404 |
| `WebViewHolder.toAssetLoaderUrl:516` | `encodePathSegment(name)`（name 直接取编码段 → **二次编码** `%`→`%25`） | `encodePathSegment(decodeUriPath(<段名>))` | 段名先解码再让 loader 段编码器编码；这是双向映射对称的前提 |
| `WebViewHolder.fromAssetLoaderUrl:553` | `return FILE_URL_PREFIX + resolved`（发布原始路径） | `return FILE_URL_PREFIX + encodeFileUrlPath(resolved)` | 发布形态必须等于入参形态，否则 ①ChatScreen 复用比较**永远不等**（每次点同一链接都重建 WebView）②下游解码端拿到原始路径会毁掉字面 `%XX` 名 |
| `FilePreviewScreen.kt:fileUrlFor` | `private fun fileUrlFor` | `internal fun fileUrlFor` + 契约 KDoc | 不复制第三份构造逻辑：把已存在的正解构造器开放给 chat/webapp 复用。**行为零变化**（函数体一字未改，仍是 `"file://" + encodeFileUrlPath(...)`） |

### 4.2 表 B — 装置（同一契约的判据与测试）

| 文件 | 改动 | 理由 |
|---|---|---|
| `scripts/scan/file_uri_parse_guard_check.py` | +3 判据（F6b / F6c / F7）、扫描面并入 `webapp/**`、+7 自测臂（25→32 fixtures）；`FIXED_HOLDER`/`BREAKER_HOLDER_ROOT` 夹具随契约更新 | 见 §4.3：同族第 3 次复发，按纪律在**既有门**加判据（不新建脚本） |
| `ui/preview/FileUriDecodeRoundTripTest.kt` | 3 条往返臂改断言**编码形态**往返；新增 3 臂（发布字节等同、字面 `%XX` 存活 + 旧形态反证、未编码输入容错） | 旧断言把「发布原始路径」当成正确行为，正是要改掉的契约 |
| `ui/preview/AssetLoaderUrlMappingTest.kt` | `encodedNameRoundTrips` / `escapedPlusDecodes` 改到编码形态（`a%2Bb.html`、CJK 编码 URL） | 同上；`+` 在编码器里是保留字符 → 往返后是 `%2B`，断言同步 |
| `ui/preview/WebAppAssetUrlTest.kt` | 往返臂改用 `fileUrlFor(html)`；源码钉从 `toAssetLoaderUrl("file://" + file.absolutePath)` 改为 `toAssetLoaderUrl(fileUrlFor(file))` | 源码钉必须跟着契约走，否则它钉住的正是被证伪的形态 |

### 4.3 门判据（为什么加、加在哪、为什么不新建）

**同一族 3 次复发**（判据锚「消费者」不锚「构造端」，每次都只修一半）：

1. `§1021-V`（2026-10-17 真机）：文件名含 `#` → WebView 把后面当 fragment → `ERR_FILE_NOT_FOUND`。修的是 `FilePreviewScreen` 的构造端（`fileUrlFor`），但 `ChatScreen`/`WebAppActivity` 仍裸拼。
2. `§1023-H`（T2 之前）：`Uri.parse(url).path` 在 `#`/`?` 处截断。修的是 3 个消费端 + 清豁免表，构造端仍未动。
3. `fix/debt-sweep-1026`（T2）：`WebPreviewShortcut.decodeUriPath` 上线后，字面 `%XX` 名（`50%23.html`）被双重解码 → 指到不存在的兄弟文件。**根因即「两套契约拼接」**，本支修的正是它。

⇒ 达到纪律门槛（≥3），且**不新建脚本**，在既有的 `file_uri_parse_guard_check`（`file://` 契约的归属门）加三条：

| 判据 | 抓什么 | 见证（注入后红，其余全绿） |
|---|---|---|
| **F6b** 「asset root 先解码再 `File(…)`」 | 编码 URL 直接喂 `File(…)` → root 指向不存在的 `%E6…` 目录（所有本地预览 404，而 F6 只查前缀剥离仍绿） | `BREAKER_HOLDER_NO_DECODE`：剥前缀保留、去掉 `decodeUriPath` → 恰好 F6b 红 |
| **F6c** 「`fromAssetLoaderUrl` 发布编码形态」 | 改回 `FILE_URL_PREFIX + resolved` → `currentUrl` 与入参不等（da1020-D 形态：每次重复点击重建 holder） | `BREAKER_HOLDER_PUBLISH_RAW` → 恰好 F6c 红 |
| **F7** 「ui/** + webapp/** 内不得把原始路径拼进 `file://`」 | 三种拼法：`"file://" + x`、`"file://${x}"`、`"file://$x"`（`$x` 允许一跳绑定解析到编码器结果） | 3 个 RED 臂（concat / 模板 / `$ident`）+ 2 个 GREEN 精度臂（编码器直接作操作数 / 一跳绑定到编码器） |

**精度设计（避免变粗的判据误伤正当代码）**：
* F6b 只在「前缀剥离存在」时开火 —— 「没剥前缀」是 F6 自己的见证，一条变异只该红一条判据。
* F6c 只判「发布型 return」（`return null` 的早退不算）。
* F7 走**字面视图**（注释已抹白），所以 KDoc 里对 `"file://" + absolutePath` 的引用不会误报；`"file:///android_asset/…"` 常量（无拼接/插值）不受影响。
* `FILE_URL_PREFIX + …` 形式的常量拼接不在 F7 射程内（登记 §7-R4）——它只出现在 holder 内，由 F6c 钉住。
* 扫描面并入 `webapp/**`（`WebAppActivity` 正是三个裸拼站点之一）；F4/F5 顺带获得同一覆盖面，实测 `webapp/` 对 F4（`Uri.parse(...).path`）与 F5（媒体扩展名表）均为 0 命中。

**为什么不把 F7 加进 `webview_holder_url_source_check`**：那条门拥有 holder 的双向映射（W1/W3/W8），但它没有 `file://` 构造端的独立视图；而且它的夹具全是「一个函数 + 一行变异」形态，塞进跨目录 sweep 会让 11→13 的判据计数和一堆 `reds == [...]` 精确臂一起变。归属门（`file_uri_parse_guard`）已经有 F4 的跨目录 sweep 骨架，扩展成本最低。

### 4.4 刻意不做的事

* **不给 `toAssetLoaderUrl` 加第二套容错分支**：它对未编码输入（不含字面 `%XX`）仍能正确映射，靠的是「解码是幂等的」这一性质，不是特判。§7-R6 记录了它不覆盖的那一档。
* **不改 `ModelUseOffloadHandler` 的 `file://` 处理**（§3.1 #19-20）：那是**外来输入**（模型/agent 直接写 `file:///host/path`），仓内无构造端；加解码会让「文件名里本来就有 `%XX`」的裸路径反向外泄，收益为负。§7-R2。
* **不碰 `WebPreviewBottomSheet.kt` / `WebPreviewShortcut.kt`**（T2 冻结面）：两个消费端已经解码，本支的发布形态变化对它们透明（pin 拿到的原始路径与 T2 之前逐字节一致）。

## 5. CI 读数（分支绿 = 本支停止节点）

| 项 | 值 |
|---|---|
| run | `40355524920` / **#2757** / attempt 1 / `workflow_dispatch` |
| 结论 | **success**（`2026-10-26T16:04:21Z → 16:27:40Z`，23m18s） |
| head_sha | `621602d432c94b59985a060cb986aea0f9d92683` —— 与本地 HEAD 逐字符一致 |
| job | `121686829254` build，26 步，**唯一 skipped = Publish**（main 门控，分支构建按设计不发布） |
| 日志 | 1,491,836 B / NUL 0 / `^ e: ` **0** |
| 门线 | 门头 `[N/112]` **共 112 条、编号 1..112 互异、门名 112 个互异** |
| 门汇总 | `Summary: 112 passed, 0 failed` **恰 1 条** |
| 夹具 | `ALL 344 CASES PASS` **恰 1 条** |
| 新判据的 CI 见证 | `━━━ [95/112] H/ISSUE-08 file uri parse guard ━━━` 下逐条 `[OK]`：`F6b … File(decodeUriPath(initialUrl.removePrefix("file://")))`、`F6c … currentUrl = FILE_URL_PREFIX + encodeFileUrlPath(resolved)`、`F7 … 1 encoded construction site(s), no raw concatenation`；`GATE=GREEN file_uri_parse_guard` |
| 自测见证 | `✅ file_uri_parse_guard_check selftest has teeth (FILE_URI_PARSE_GUARD_SELFTEST=PASS)` 恰 1 + `✅ file_uri_parse_guard_check real tree green` ⇒ **32 条新/旧臂在 CI 里真的跑了**（不是只在我本地） |
| 含 `FAILED` 的 6 行 | 逐条核对：全是 `PASSED` 用例名子串（判读陷阱第 N 次复核），无真实失败 |

## 6. 验证

沙箱无 Android 运行时，所以「真机行为」一栏不可达（§7-R5）；下面的五层证据全部可复现。

### L1 门自测（夹具面）

```
$ python3 scripts/scan/file_uri_parse_guard_check.py --selftest
FILE_URI_PARSE_GUARD_SELFTEST=PASS (32 fixtures)      # 25 → 32，+7 臂
```
每条新臂断言的是**「恰好红在它自己的判据上」**（`reds == [<单条判据名>]`），而不是「有红」——
撞错判据的臂等于假臂：

| 臂 | 注入 | 期望 |
|---|---|---|
| `fileurl-no-decode-root` | 剥前缀保留、去掉 `decodeUriPath` | 恰好 F6b 红 |
| `fileurl-publish-raw` | `FILE_URL_PREFIX + resolved` | 恰好 F6c 红 |
| `fileurl-raw-concat` | `"file://" + file.absolutePath` | 恰好 F7 红 |
| `fileurl-raw-template` | `"file://${file.absolutePath}"` | 恰好 F7 红 |
| `fileurl-raw-dollar` | `"file://$path"` | 恰好 F7 红 |
| `fileurl-encoded-dollar` | `val encoded = encodeFileUrlPath(...)` + `"file://$encoded"` | 全绿（精度） |
| `fileurl-encoded-operand` | `"file://" + encodeFileUrlPath(...)` | 全绿（精度） |

### L2 真树

```
$ python3 scripts/scan/file_uri_parse_guard_check.py .
Summary: 16 passed, 0 failed
swept 230 .kt file(s) under …/ui + …/webapp
GATE=GREEN file_uri_parse_guard
```

### L3 邻门与全量门（本地，真树）

* 三邻门「自测 + 真树」全绿：`webview_holder_url_source_check`（W1/W3/W8 与两个映射函数直接相关）、
  `file_preview_url_encode_check`（`fileUrlFor` 的可见性/形态）、`webapp_asset_url_cleanup_check`（`toAssetLoaderUrl` 接线）。
* 其余门（分 4 片跑，逐门独立进程 —— 沙箱整壳会打断长跑）：
  **110/110 rc=0，0 red**（`/tmp/u4-gates.txt`）。

### L4 变异矩阵（判据有效性，不是「门绿」）

对**已修好的同一棵树**把三处旧形态注回去：

| 变异 | 判据读数 | 还原 |
|---|---|---|
| M1 `ChatScreen` 裸拼注回 | `Summary: 15 passed, 1 failed` — F7（`ChatScreen.kt builds a file:// URL by concatenating a raw path`） | sha256 前=后 ✅ |
| M2 root 不解码 | `15 passed, 1 failed` — F6b（`the asset root is built from the ENCODED URL`） | sha256 前=后 ✅ |
| M3 发布会裸路径 | `15 passed, 1 failed` — F6c（`fromAssetLoaderUrl publishes 'FILE_URL_PREFIX + resolved'`） | sha256 前=后 ✅ |

一条变异只红一条判据 ⇒ 判据边界互不重叠，红读数能直接指认缺陷种类。

### L5 JVM 切片探针（行为面，真源码非复刻）

`probe/u4_build_probe.py` 从三个文件**逐字节切出**被测代码（`file-url-path-encode` 区 + `fileUrlFor`
声明行 + `UriPathDecode.kt` 全文 + `asset-loader-url-mapping` 区），kotlinc 编译后跑 **54 条断言**：

```
$ java -jar probe.jar
PROBE PASS (54 assertions)
```

覆盖：编解码互逆（8 类文件名：纯 ASCII / 空格 / CJK / `#` / `?` / 字面 `%` / 字面 `%23` / `%41`）·
root 解码 · **一次编码不变式**（`toAssetLoaderUrl` 对编码/未编码输入同结果）·
**发布字节等同不变式**（`currentUrl == fileUrlFor(file)`，六种名字逐个断言）· 全链末端 == 真实路径 ·
字面 `%XX` 名存活 · **旧形态反证**（`decodeUriPath(raw) != raw`，即见证）· `#`/`?` 仍先切后解 ·
四条 fail-closed（非 file URL / 尾斜杠 / 未知 origin / `../` 穿越）。

**探针自身的负控**：把 `toAssetLoaderUrl` 的 `decodeUriPath` 摘掉 → `PROBE FAIL (6/54)`
（asset 映射 3 条 + 发布等同不变式 3 条），还原后复绿 ⇒ 探针不是自说自话。

### L6 分支 CI

见 §5。

## 7. 残留（每条带触发条件 / 触发后方案 / 搁置理由）

**R1 — `WebPreviewBottomSheet` 的标题/副标题回退路径可能显示编码后的路径**
* 触发条件：`android.net.Uri.parse("file://…").host` 对空 authority 返回 **null**（而非空串），
  于是 `displayHost ?: holder.currentUrl` 落到编码形态 —— 文件名含空格/CJK 时副标题会显示 `%E6%88%91…`。
* 触发后方案：在 sheet 侧对显示值做 `decodeUriPath`（只用于显示，不回写 `currentUrl`）。
* 搁置理由：该文件是 T2 冻结面（本支不得触碰）；且现有代码路径下 host 为空串的概率更高（此时显示值本就是空），
  影响面是一个次要副标题，非功能面。**P2**。

**R2 — `ModelUseOffloadHandler` 的 `file://` 处理没有解码（§3.1 #19-20）**
* 触发条件：模型/agent 在 `minis-model-use --input file:///host/path` 里传入**已编码**的路径，
  且该文件名本身含空格/CJK。
* 触发后方案：按本契约在 `resolveMaybeRelative` 的 `file://` 分支加 `decodeUriPath`，并同步该 handler 的
  KDoc「输入是 URL 形态」。
* 搁置理由：这是**外来输入**，仓内无构造端；现在加解码会让「文件名里本来就有 `%XX`」的裸路径反向外泄
  （用户手写的裸路径是主流写法）。等有真实调用方按契约编码再改。**P2**。

**R3 — `minis://` 深链段的编码/解码未端到端验证（T2 遗产）**
* 触发条件：pin 到桌面的文件名含字面 `%XX`（`50%23.html`）。
* 触发后方案：在 `WebPreviewShortcut` → `DeepLinkCoordinator` → `ChatScreen.pending.resourcePath`
  这条链上做一次真机/单测往返（`Uri.Builder` 编一次，解析侧解一次）。
* 搁置理由：`WebPreviewShortcut` 是冻结面；且本支**没有改变** pin 收到的原始路径（解码后与 T2 之前逐字节一致），
  所以风险不新增。真机不可用 + 冻结面 = 登记不修。**P2**。

**R4 — `FILE_URL_PREFIX + <raw>` 形式不在 F7 射程内**
* 触发条件：未来有人在 holder 之外用 `FILE_URL_PREFIX` 常量拼原始路径。
* 触发后方案：F7 增加「`FILE_URL_PREFIX` 的每个非编码器操作数」判据（需先把它变成可导入的公开常量或按名扫描）。
* 搁置理由：该常量目前只有 holder 内一处 `FILE_URL_PREFIX + encodeFileUrlPath(...)`，已由 **F6c** 钉住；
  为一条不存在的写法加判据 = 装置过度。**P2**。

**R5 — 真机行为未验证（沙箱无 Android）**
* 未验：①含 `#`/`?`/空格/CJK/字面 `%XX` 的 HTML 在预览里正常载入；②同一链接重复点击复用 holder（不重建）；
  ③「用其他应用打开」得到的文件名与磁盘一致；④pin 后重放能打开。
* 静态等价已证（L5 的字节等同不变式 + L2/L4 的判据），但 `InternalStoragePathHandler` 的真实路径解析
  只能真机确认。**P1 触发条件**：用户报告本地 HTML 预览打不开/文件名乱码时，先按本表逐条核对。

**R6 — 非契约输入（未编码且含字面 `%2F`）的段切分是 best-effort**
* 触发条件：某调用方绕过 `fileUrlFor` 传原始路径，且文件名含 `%2F` 形态。
* 触发后方案：无需处理——同族调用方已被 F7 全部收编（构造端只剩编码器）。
* 搁置理由：这是「不合契约的输入」，在 F7 生效后不可达。**P3**。

## 8. 结论

1. **契约从「口头共识」变成「两侧都有判据」**：`file://` URL 只有两种合法形态，构造端由 **F7**
   （禁止裸拼，含三种拼法）+ **F6c**（发布必须是编码形态）守，消费端由既有的解码判据守。
   本支之前，这族缺陷每次只修一半，因为**没有任何判据看着构造端**。
2. **缺陷面收敛到零**：普查 21 个代码站点 + 2 个非字面族，违反契约的 3 处构造端、3 处 holder 边界
   全部改到位；未改的 12 处逐条给出了「已合规 / 形态无关 / 外来输入」的理由（§3.1、§4.4）。
3. **证据不是「CI 绿」**：判据有效性由变异矩阵证明（一条变异恰好红一条判据，字节级还原），
   行为面由真源码切片的 JVM 探针证明（54 断言，含旧形态反证与负控），
   三者互不依赖：门判文本形态、探针判运行效果、CI 判全仓不回归。
4. **未验证面写清**：真机载入 / holder 复用 / FileProvider 文件名 / pin 重放（§7-R5）——沙箱无
   Android 运行时，静态等价不能替代。这是本支唯一的能力边界，其余缺口都是零散的 P2 登记。

**停止节点达成**：分支 CI 绿 @ `621602d43`，生产树与绿 sha 的差异 = 0（仅报告回填）。
按纪律**不合并 main**，起手权在用户。
