# FIX-R3-t02 — 三门缝隙判据加固（第三轮 dual-gate bypass）

基线：`main` @ `f0f14d46d`　分支：`fix/r3-preview-asset`　工作树：`round3b/wt/t02`

改动面：**3 个门脚本**（+949 / −28），生产 `.kt` **0 行**、`scan.sh` / `test_scan.py` /
`_kotlin_{lexer,ast,valueflow}.py` 零触碰；门数 112 / `test_scan` 夹具 344 不变。

## 1. 门

| 门 | 职责（一句话） | 本次见证 |
|---|---|---|
| `file_preview_url_encode_check.py` | 预览/打印用的 `file://` URL 必须经 `encodeFileUrlPath`，且编码后的值必须真的到达 `loadUrl` | #70 #71 #72 |
| `filepreview_print_bounded_check.py` | 打印/分享的读取必须有界（`readCapped` 500 KB），且有界值必须到达消费点 | #70 #73 #74 #75 |
| `webapp_asset_url_cleanup_check.py` | 固定到主屏的 WebApp：暂存目录写入、`htmlPath` 归属、scope 分支委派 | #91 #92 #93 |

## 2. 轴

9 条见证同属一族——**判据锚「某拼写/某调用存在」，缺陷落在「值算出来但没到达消费点」上**。
按失效形态分四类（这是本次修法的直接依据）：

| 轴 | 见证 | 旧判据为何失明 |
|---|---|---|
| 可达性 / 顺序 | #70 #73 | 只查 `loadUrl(fileUrlFor(...))` 与 `printFile(...)` 字面存在；把它们放进 `&& false` / `\|\| true` 常量分支后，调用还在、效果归零 |
| 单次绑定 | #71 | 见 `val x = fileUrlFor(...)` 即绿；下一行 `x = x.replace(...)` 改写后，`loadUrl(x)` 收到的已不是编码值 |
| 值来源 / 消费 | #74 #75 #91 | 只查「有界读取被调用」；`EXTRA_TEXT` 与暂存文件写入用的是另一个值（`""` / 空写 / 丢掉的 `bytes`），cap 保护的是没人用的值 |
| 路径归属 / 对称臂 | #92 #93 | 只查 `htmlPath` / scope 分支存在；层次前缀丢了、分支返回 `null`，名字都在 |

## 3. 见证重放表

`pre` = 修前（`probe/t02-pre.json`），`post` = 修后（`probe/t02-post.json`）。全部 `restored=True`，
`git status src/` 全程为空。

| # | kind | 命中门 | pre rc | pre 判决 | post rc | post 判决 | 修后红在哪条判据 |
|---|---|---|---|---|---|---|---|
| 70 | dual-gate | url_encode / print_bounded | 0 / 0 | BLIND_CONFIRMED | 1 / 1 | **CAUGHT** | load site 落在常量折叠后不可达的分支（两门各一条） |
| 71 | dual-gate | url_encode / webview_holder / browser_destroy | 0 / 0 / 0 | BLIND_CONFIRMED | 1 / 0 / 0 | **CAUGHT** | `targetUrl` 绑定 `fileUrlFor(...)` 后又被重写 |
| 72 | symmetric | url_encode | 0 | BLIND_CONFIRMED | 1 | **CAUGHT** | 直通分支放行了 2 个 RFC 3986 之外的字节（`#` `?`） |
| 73 | dual-gate | print_bounded / url_encode | 0 / 0 | BLIND_CONFIRMED | 1 / 0 | **CAUGHT** | HTML/文本分流常量折成同一条路；`buildPrintHtml` 分支不可达 |
| 74 | dual-gate | print_bounded / share_prune | 0 / 0 | BLIND_CONFIRMED | 1 / 0 | **CAUGHT** | 降级 chooser 的 `EXTRA_TEXT` 不来自有界读取器 |
| 75 | dual-gate | print_bounded / asset_url | 0 / 0 | BLIND_CONFIRMED | 1 / 0 | **CAUGHT** | `readAtMost` 的结果绑到 `bytes` 后被丢弃，未进任何消费调用 |
| 91 | dual-gate | asset_url / share_prune | 0 / 0 | BLIND_CONFIRMED | 1 / 0 | **CAUGHT** | 暂存文件写入的是空内容 / `putExtra(EXTRA_STREAM, …)` 与暂存文件脱钩（arm15） |
| 92 | dual-gate | asset_url / print_bounded | 0 / 0 | BLIND_CONFIRMED | 1 / 0 | **CAUGHT** | `ChatAttachment` 分支的 `htmlPath` 不在暂存目录下（arm16） |
| 93 | symmetric | asset_url | 0 | BLIND_CONFIRMED | 1 | **CAUGHT** | `SCOPE_SESSION_ATTACHMENT` 分支不再委派给 `resolveSession`（arm17） |

合计：**9 条 BLIND_CONFIRMED → 9 条 CAUGHT**（实锤率 9/9，无假见证、无 NOT_REPRODUCIBLE）。

## 4. 修法

### 4.1 `file_preview_url_encode_check.py`（arm 数 17，标记 `FILE_PREVIEW_URL_ENCODE_SELFTEST_ARMS`）

三条见证对应三处判据，全部从「拼写存在」搬到「值真的到达」：

1. **加载点可达性**（#70）：定位每个 `loadUrl(...)` / `loadDataWithBaseURL(...)` 站点，`_enclosing_fn`
   取所在函数，再用 `enclosing_conditions` + `const_bool` 折 **全部** 外层条件——任一条折成常量即报红
   （"sits in a branch that can never run"），并单独报"loadUrl 不可达，URL 组装后被丢弃"。
2. **单次绑定**（#71）：`val targetUrl = fileUrlFor(...)` 之后若同名变量被**再次赋值或重声明**，红。
   值流按「load 站点实参的来源」判定，不看是否存在过编码调用。
3. **编码器字节集**（#72）：不再按 `safe` 分支的**字面清单**判，而是把直通分支实际放行的字符集
   （字符码比较、`in` 集合、区间谓词经 `_resolve_predicate` 折叠）与 RFC 3986 unreserved + `/` 求差集，
   差集非空即红并把具体字节打出来（`U+0023 '#'` 等）。读不出字节集的形态 **fail-closed 报红**。

### 4.2 `filepreview_print_bounded_check.py`

1. **打印路由**（#73）：`printFile(...)` 前面的 `isHtmlFile` 分流必须**真的分岔**——条件常量折叠后
   两臂相同即红；同时 `buildPrintHtml(...)` 的调用点必须在可达路径上（500 KB `readCapped` 不许是装饰）。
2. **打印页加载**（#70 的第二半）：打印 WebView 的 `loadUrl(...)` 同样做外层条件折叠，
   常量分支里的加载 = 打印空白页。
3. **有界值的来源与消费**（#74）：`Intent.EXTRA_TEXT` 的实参必须**派生自** `readLogShareText(...)`
   （`_origins` 追到求值来源，字面量 `""` 直接红）；`_consumed_as_value` 另判「有界读取的绑定名
   必须作为实参进入下游调用」，只绑定不使用 = 红（#75）。
4. **SAF 有界读取的消费**（同族顺带）：`SafImportRead` 的两处 cap 读取必须落到消费点。

### 4.3 `webapp_asset_url_cleanup_check.py`（arm 18 + 4 条 R3 夹具）

新增三条臂，各自只判一条缝，且互不连带（selftest 里逐条断言"只踩自己那条"）：

- **arm15 暂存文件与分享 payload**（#91）：`File(context.cacheDir, …/share/$sid)` 之类暂存目录必须被
  真实内容写入（`writeText("")` / 空写入红）；`putExtra(Intent.EXTRA_STREAM, uri)` 的 `uri` 必须由
  **暂存文件**（`FileProvider.getUriForFile(…, stagedFile)`）派生——从源文件直接取 URI 即红。
- **arm16 htmlPath 归属**（#92）：先从前缀模板（`…/attachments/$shortcutId`）派生出**暂存变量名**，
  再要求 `ChatAttachment` 分支内 `htmlPath` 求值后以 `"$shortcutId/"` 开头；只写裸文件名红。
  具名实参 `create(htmlPath = htmlPath)` 走局部解引用（`_local_rhs`），且实参在顶层级逗号处截断。
- **arm17 scope 分支委派**（#93）：`when (scope)` 的每个臂必须**委派**（调用现成 resolver），
  返回 `null` / 字面量即红；臂集合从树的 `when` 分支枚举派生，不写死名字。

## 5. 四层证据

### L1 本门（真树 + 自测）

| 门 | 真树 | selftest 标记 |
|---|---|---|
| url_encode | `OK (arm1 + arm2 + arm3 green)` rc=0 | `FILE_PREVIEW_URL_ENCODE_SELFTEST=PASS` / `..._ARMS=17` |
| print_bounded | `PASS` ×4 文件 + `PASS capped SAF reads reach their consumer` rc=0 | `FILEPREVIEW_PRINT_BOUNDED_SELFTEST=PASS` / `..._T03_SELFTEST=PASS (7 witness arms)` |
| asset_url | `PASS all 18 arms (… staged payload ×1, htmlPath prefix ×1, scope-arm delegation ×1)` rc=0 | `WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS` |

新增 R3 夹具 4 条（`r3-drop-extra-stream` / `r3-empty-staging` / `r3-htmlpath-bare-name` /
`r3-scope-arm-returns-null`），每条断言"只 trip 自己那条臂"：输出形如
`selftest ok case=r3-htmlpath-bare-name (arms tripped (False, True, False))`。

### L2 邻门

`webview_holder_url_source_check` · `share_dir_prune_check` · `browser_destroy_wakeup_check` ·
`file_provider_paths_coverage_check` · `open_url_broker_scheme_check` → 全部 **rc=0**
（这五门在本批见证里被 dual-gate 同时点名，必须确认没被新判据误伤）。

### L3 全量（逐门独立进程）

`probe/t02_loop_resume.py`：**109 门 → failed=0**（记录 `probe/t02-loop.jsonl`）。
`tts_voice_and_sanitizer_check.py` 只吃 `--root .` 形态，单独跑 **PASS**（脚本已内置该形态）。

> `scan.sh` 整份在沙箱里会被 S28（`OSError Errno 38`）打死（本会话实测 3 次：整份、后台、分批），
> 改用逐门独立进程后稳定。全量 + `test_scan` 的权威读数由分支 CI 承担（见表 §6）。

### L4 CI

见 §6。

## 6. CI

分支 `fix/r3-preview-asset`（`on: push` 只绑 main ⇒ 手动 dispatch）：

| 项 | 值 |
|---|---|
| run | **#2745** / id `40316453439` / attempt 1 / **success** |
| job | `121550152584` |
| head_sha | `e66acd2355fa75b724c65ca8b1ceecaa6680bb67`（与本地 HEAD 逐字符一致） |
| 步态 | 26 步，唯一非 success = step 23 `Publish to Releases` **skipped**（main 门控，符合预期） |
| 日志 | 1,486,978 B / NUL 0 |

逐条核过的读数：

- `Summary: 112 passed, 0 failed` —— **恰 1 次**
- `ALL 344 CASES PASS` —— **恰 1 次**（全量夹具在 CI 里真跑，补上本地 R5 缺口）
- `^\s*e: ` —— **0 行**
- 含 `FAILED` 的 6 行逐条确认全是 **PASSED 用例名的子串**
  （`RealAgentAdapterSkeletonTest > slot denied produces FAILED …`、
  `ChatAgentTraceObserverSchemaTest > phase FAILED maps to Failed PASSED` 等）
- 三个门的新自测标记各 **恰 1 次** 出现 ⇒ 新臂在 CI 里有见证：
  `FILE_PREVIEW_URL_ENCODE_SELFTEST`（17 arms）· `FILEPREVIEW_PRINT_BOUNDED_SELFTEST` ·
  `WEBAPP_ASSET_URL_CLEANUP_SELFTEST`（18 arms）

**分支 CI 绿 = 停止节点**，未合并 main。报告回填提交后源码树与绿 sha 等价：
`git diff --name-only e66acd235..HEAD -- . ':(exclude)fix-reports'` = 0 行。

## 7. 残留（每条带 触发条件 · 触发后方案 · 搁置理由）

- **R1 共享原语缺口：`path_feasible` 对 `if (!x || true)` 判 feasible=True。**
  触发条件：上游修 `_kotlin_valueflow.path_feasible` 的 `||` 折叠（目前只保守跳过）。
  触发后方案：删掉本门自写的 `_reachable`（几何折叠 if 条件）改调 `path_feasible`，并补一条
  `x || true` 的共享自测臂。
  搁置理由：共享层属主会话（任务书硬约束 2），本支只登记不改。
- **R2 门 C `check_sheet_arm16` 只审 `ChatAttachment` 分支窗口内的 `htmlPath`。**
  触发条件：生产新增第二个"把附件拷进暂存目录"的 `WebAppSource` 变体。
  触发后方案：把分支标签集合从 `when` 分支枚举派生（复用 arm17 的派生写法），对每个 staging 分支都判。
  搁置理由：当前只有一个 staging 路径，写死少一层推导；多分支时再泛化。
- **R3 门 A 的字节集判据只解析可折叠的比较/字符码形态。**
  触发条件：出现用 `when (c)` 或 8 位区间谓词写白名单的正当写法。
  触发后方案：在 `_resolve_predicate` 补 `when_branches` 枚举与开区间求交。
  搁置理由：现状全仓一处 `encodeFileUrlPath`，且不可解析形态 fail-closed（宁可误红不可放过）。
- **R4 门 C arm15 只认 `putExtra(Intent.EXTRA_STREAM, …)` 作为分享 sink。**
  触发条件：生产改用 `ClipData` / `IntentSender` 分享。
  触发后方案：`_hands_over` 的 sink 正则加 `clipData\s*=` 与 `setClipData(`。
  搁置理由：现状只有 `EXTRA_STREAM`。
- **R5 `test_scan.py` 本地未取到全量读数。**
  触发条件：需本地复核夹具时。
  触发后方案：`python3 scripts/scan/test_scan.py`（≥15 min，可能被 S28 杀，重试或分批）。
  搁置理由：CI 每次跑 `ALL 344` 已经是独立读数，本地重跑不增加证据。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t02
# 1) 三门真树 + 自测
for g in file_preview_url_encode_check filepreview_print_bounded_check webapp_asset_url_cleanup_check; do
  python3 scripts/scan/$g.py . ; python3 scripts/scan/$g.py --selftest ; done
# 2) 见证重放（修前 / 修后，自动还原并校验 sha256）
python3 probe/t02_replay.py pre  70 71 72 73 74 75 91 92 93
python3 probe/t02_replay.py post 70 71 72 73 74 75 91 92 93
# 3) 全量（逐门独立进程，可续跑）
python3 probe/t02_loop_resume.py
```
