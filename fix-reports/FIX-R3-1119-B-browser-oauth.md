# FIX-R3-1119-B — browser + OAuth 五条（F-03 / F-04 / F-10 / F-17 / F-18）

- 支：`fix/r3-browser-1119`（worktree `/var/minis/shared/work/r3full/browser`）
- 基线：`ecb03d92`
- 报告来源：`reports/fullscan1119r3/Rikkaminis_Fullscan_Report_Round3.md`，判级 `fullscan1119r3/TRIAGE-R3.md`
- **未合 main**（停止节点 = 分支 CI 绿 + head_sha 逐字符一致）

## 1 结论速览

五条全部**先证成立**，无一条证伪。

| ID | 级 | 先证 | 修法（一句话） | 证据 | 变异臂 |
|---|---|---|---|---|---|
| F-03 | P0 | 基线 `instanceFollowRedirects = true` + Cookie 无条件挂载 4/4 CONFIRMED | 关自动跟随、逐跳判同主机才带 Cookie、`https→http` 拒绝、跳数上限对每跳生效 | 12 例（含 4 条行为断言，JVM 抽取装置 10 例） | 6/6 RED |
| F-04 | P0 | 基线把 4 MiB 整体回传、无任何预算 4/4 CONFIRMED | `getReader()` 分块读 + 运行字节预算 16 MiB，超限 `cancel()` 并返回结构化错误（不截断） | 12 例（真模板 + node 起假流，含 sha256 比对） | 6/6 RED |
| F-10 | P0 | 基线 7 项清理断言全 FAIL | `remove` 与同名 `add` 都清 `<name>.json` / `.json.tmp` / `.secret`，且在写新配置**之前**；失败报错不静默 | 14 项文件系统断言 | 6/6 RED |
| F-17 | P1 | Android `optString` 语义差分探针（旧形态读 "null"，助手读 null） | 抽 `optStringOrNull()`，按 `isNull` 判定 | 13 例 + 4 例平台语义探针 | 6/6 RED |
| F-18 | P1 | 基线 host-only 校验放行两条不可达 URI（4/4 CONFIRMED） | 判据换成「与实际监听端点等价」：scheme=http + 回环 host + port=54546 | 8 例（含「检查早于开浏览器」接线断言） | 5/5 RED |

## 2 环境与基线

- worktree `/var/minis/shared/work/r3full/browser`，分支 `fix/r3-browser-1119`，基线 `ecb03d92`（漂移 0）
- 改动文件（11 个，全部在允许清单内）：
  - `browser/BrowserTabPool.kt`（+70/−9）
  - `browser/BrowserUseJS.kt`（+103/−27）
  - `assets/default_mount/.../minis-mcp-cli/main.py`（+61）
  - `mcp/oauth/MCPOAuthConfig.kt`、`MCPOAuthController.kt`、`MCPOAuthStore.kt`
  - 5 个测试类 + 本报告
- **禁碰文件零触碰**（脚本核对）：`BrowserUseManager.kt`、`scripts/scan/**`、`ui/**`、`data/**`、`chat/**`、`backup/**`、`sandbox/**`
- 提交（每条独立；F-17 两个提交见 §3.4）：
  1. `953e139b` F-03
  2. `ac56b944` F-04
  3. `4fa948e9` F-10
  4. `343f177e` F-17
  5. `ded5255a` F-18
  6. `6b2d3891` F-17 的门兼容调整（`mcp_oauth_lifecycle_check` C2 派生面）
- 装置全部在**工作树之外**：`/var/minis/shared/work/r3full/browser-jvm/`（`run-f03.sh` … `run-f18.sh`、`mutate-fXX.py`、`baseline_fXX.py`、`extract*.py`、`probe/`）
- 禁跑项均未跑：全量 `scan.sh` / 全量 `test_scan.py` / gradle。kotlinc 单文件、`nice -n 10`、同时一个 JVM

## 3 逐条（先证后修）

### F-03 [P0 · L=X 泄漏] `startUrlDownload` 挂 Cookie 后仍跟随跨站重定向 — 成立

**先证**（`baseline-f03.sh`，读 `ecb03d92` 的真实文本）：

```
baseline still turns auto-follow ON                        CONFIRMED
baseline attaches the cookie jar unconditionally           CONFIRMED
baseline has no https to http guard                        CONFIRMED
baseline hop budget only sees the manual chase             CONFIRMED
BASELINE-F03: 4/4 confirmed, 0 not
```

机制比报告更进一层：`instanceFollowRedirects = true` 时 `c.responseCode` 拿到的是**已自动跟随完**的终态，所以外层手动循环里的 `Location` 分支对同协议跳转根本不执行 —— 这既让 Cookie 跟着跳到了第三方主机，也让 `MAX_DOWNLOAD_REDIRECT_HOPS` 对这类跳转**完全失明**（报告只点了泄漏，没点预算失明）。

**修法**（`BrowserTabPool.kt`）：

- `c.instanceFollowRedirects = false` —— 每一跳都回到手动循环，预算重新有牙
- 新增纯函数 `downloadHopKeepsCredentials(sourceUrl, hopUrl)`：只有与**源主机**同 host 才挂 `CookieManager` 的那份 cookie（cookie 不按端口隔离，故只比 host；无法解析则一律不带）
- 新增纯函数 `downloadRedirectRefusal(fromUrl, nextUrl, hopsAfterThis, maxHops)`：`https → http` 降级直接拒绝，且跳数上限对**每一跳**生效
- 两个函数放在 companion 里为 `internal`，原因是测试要能在同模块调用（并说明「Authorization 头本路径从不设置，凭据面只有 Cookie」）

**必测四条**（repo 测试 12 例，其中 4 条是行为、8 条是接线）：

| 用例 | 期望 |
|---|---|
| 同主机带 Cookie | `downloadHopKeepsCredentials("https://site-a.example/dl", "https://site-a.example/f")` = true |
| 跨主机不带 | `("https://site-a.example/dl", "https://cdn-b.example/f")` = false |
| `https→http` 拒绝 | refusal 非 null 且文案含 `https` |
| 超跳数失败 | 第 6 跳非 null 含 `redirects`；第 5 跳 null（边界） |

接线臂额外钉住「**不该带的头确实没带**」：`c.setRequestProperty("Cookie", it)` 必须位于 `if (downloadHopKeepsCredentials(url, currentUrl)) {` **之后**，且文件内不存在 `instanceFollowRedirects = true`；`downloadRedirectRefusal(` 的判定必须早于 `currentUrl = nextUrl`。

### F-04 [P0 · Y 崩溃] `browser_use(fetch)` 全量读入 + 逐字节拼 Base64 无上限 — 成立

**先证**（`baseline_f04.py`，把基线 fetch 模板真的跑在 node 上）：

```
baseline reads the whole body at once                     CONFIRMED
baseline builds the base64 payload one byte at a time     CONFIRMED
baseline carries no byte budget                           CONFIRMED
baseline returns the whole 4 MiB body as a payload (no refusal) CONFIRMED
   {"hasError": false, "base64Len": 5592408, "size": 4194304, "declaredBytes": 4194304}
```

**修法**（`BrowserUseJS.kt`，仅此一个文件 —— `BrowserUseManager.kt` 在飞支独占，未触碰）：

- 新增 `const val MAX_FETCH_BYTES = 16 * 1024 * 1024`。取 16 MiB 的理由写进 KDoc：base64 后约 21.3 MiB 字符，仍在工程既有 24 MiB blob 预算（`BrowserUseManager.MAX_BLOB_DATA_URL_CHARS`）之下
- 预算随脚本下发：`const MAX_BYTES = $MAX_FETCH_BYTES;`
- `Content-Length` 只做**提前失败**（chunked 响应没有它）→ 权威判据是运行中的字节计数
- `resp.body.getReader()` 分块读，`total > MAX_BYTES` 时 `reader.cancel()` 并返回 `{error: 'response too large: …', tooLarge: true}` —— **不截断出半个文件**
- payload 用 32 KiB 切片 `String.fromCharCode.apply(...)` 拼，逐字节 `+=` 的二次复杂度消失
- 无 body（204/HEAD）仍返回空 payload（与 r3-B09 F-11 的「2xx 空成功」语义一致）；body 存在但不可流式 → 结构化错误而不是猜

**必测**（12 例，全部行为级：测试把**真实模板**渲染出来交给 node 跑）：

| 用例 | 断言 |
|---|---|
| 限内字节完全一致 | 100 000 字节 0x42 → 解出的 base64 与源字节 **sha256 相同** |
| 边界值 | 恰好 16 MiB → 成功、sha256 相同 |
| 超限明确错误 | `MAX+1`、带真实 Content-Length → `error` 含 `too large`、`tooLarge=true`、**无 base64**、`delivered == 0`（早失败） |
| 超限无 Content-Length | 64 MiB 分块 → 中途拒绝、`cancelled=true`、`delivered ≤ MAX + chunk`（证明没有把整体读进渲染进程） |
| 谎报 Content-Length | 声称 1024、实际 32 MiB → 仍被运行计数拦下 |
| 形状 | 无 `await resp.arrayBuffer()`；有 `resp.body.getReader()`；超限块内不含 `base64` |

### F-10 [P0 · L=X 泄漏] `minis-mcp-cli remove` / 同名 `add` 不清桥文件与 `.secret` — 成立

**先证**（`baseline_f10.py`；把基线 `main.py` 放进临时 CLI 包，跑**同一份** driver）：

基线失败的 7 项，与本条要修的面一一对应（其余 7 项仍 PASS，说明正常路径未被本修法影响）：

```
remove deletes the token bridge                            CONFIRMED-FAIL
remove deletes the half-written bridge                    CONFIRMED-FAIL
remove deletes the client-secret handoff                  CONFIRMED-FAIL
same-name add clears the previous token bridge            CONFIRMED-FAIL
an impossible cleanup exits non-zero                      CONFIRMED-FAIL
an impossible cleanup names the failure                   CONFIRMED-FAIL
the add does not land while its old credentials survive    CONFIRMED-FAIL
BASELINE-F10: 0 problem(s)
```

**修法**（`main.py`）：

- `_oauth_credential_files(name)` 列出三个路径：`oauth/<name>.json`、`oauth/<name>.json.tmp`、`oauth/<name>.secret`（`.json.tmp` 来自 `transport/http.py::_save_oauth_tokens` 的原子写，`.secret` 来自本 CLI 的 `add --oauth-client-secret`）
- `_clear_oauth_credentials(name, pretty)`：文件不存在 = 正常；存在但删不掉 = `_fail` 非零退出并打印全路径（静默失败等于把泄漏放回去）
- `cmd_add`：**在解析完 URL/命令之前**、也在写入新配置与 seed `.secret` 之前调用（顺序是硬要求：晚一步就会删掉刚写的 secret，M3 变异臂证明）
- `cmd_remove`：`remove_server` 之后调用（配置先落地，再清凭据；清理失败仍报错退出并保留明确文案）

**必测**（14 项，全是文件系统断言；driver 与 JUnit 共用一份源）：

- `add` 会 seed `.secret`；`add → remove` 后三个文件全不存在、配置项消失
- `add A(tok1) → 同名 add A(tok2)`：旧桥文件被清、新 URL 落盘（**不带 tok1**）
- 正常 `add` 不动别人的凭据（`gamma.json` 仍在）、自己也没有多余凭据
- 正常 OAuth `add` 仍正确 seed 自己的 `.secret`，且仍回 `{"added": …}`
- 删不掉的清理（用目录占位）→ 非零退出 + 文案含 `failed to clear OAuth credentials` + **配置不会落地**

### F-17 [P1] `optString` 未判 `isNull` ⇒ JSON `null` 变字面量 `"null"` — 成立（平台差异是本条的坑）

**先证**：这条在 JVM 上**复现不出来**。Maven 的 `org.json` 对 `JSONObject.NULL` 返回 fallback，Android 的 `JSONObject.optString(key, fallback)` 走 `JSON.toString(Object)` → `value.toString()` → `JSONObject.NULL.toString()` = `"null"`。所以普通 JVM 测试全绿而设备上写坏凭据。

处置 = `probe/F17AndroidSemanticsProbe.kt` 把 Android 的 coercion 复刻在 Maven jar 之上，做**真差分**：

```
PASSED the pre-fix shape turns a JSON null into the literal null     ← 旧形态读 "null"
PASSED the helper answers null for the same document
PASSED a null redirect_uri would have produced a URI with no host
PASSED the parsed config no longer carries the literal
```

**修法**：包级 `internal fun JSONObject.optStringOrNull(key, fallback = "")`，先判 `isNull`，再按老语义把空白当 null。**实改 7 处调用点 + 1 个新助手**：

| # | 文件 | 调用点 | 说明 |
|---|---|---|---|
| 1 | MCPOAuthConfig.kt | `mode` | JSON null 也曾变成 `"null"` 模式 |
| 2 | MCPOAuthConfig.kt | `scopes` | 报告点名 |
| 3 | MCPOAuthConfig.kt | `firstNonBlank` 内部（**第 6 处**） | 一处覆盖 `client_id` / `authorization_endpoint` / `token_endpoint` / `redirect_uri` 四个别名步进 |
| 4 | MCPOAuthController.kt:170 | token exchange → `StoredTokens.refreshToken` | 报告点名 |
| 5 | MCPOAuthController.kt:181 | token exchange → `BridgePayload.refreshToken` | 报告点名 |
| 6 | MCPOAuthStore.kt:141 | `tokens()` 读加密库 | 报告点名 |
| 7 | MCPOAuthStore.kt:336 | `readBridgeTokenSnapshot()` | 报告点名；**因门约束保留字面形状**，见下 |

第 7 处特例：`mcp_oauth_lifecycle_check` 的 C2 判据**从 `x.optString("<key>"` 这个字面形状派生快照字段名**，换成助手后门立刻红：

```
FAIL mcp_oauth_lifecycle_check
  [dv26-A2] C2 cannot derive the token / expiry field names from the bridge reader
```

本支禁碰 `scripts/scan/**`，所以按 B2-chat 的老教训处理 —— **字面读回调用点，但不回退语义**：

```kotlin
refreshToken = o.optString("refresh_token", "")
    .takeUnless { o.isNull("refresh_token") }
    ?.ifBlank { null },
```

门可派生，JSON null 仍进不来（`takeUnless(isNull)`）。第 7 处因此是**两个提交**（`343f177e` 主修 + `6b2d3891` 门兼容），而不是一条。

**必测**（13 例 + 4 例语义探针）：JSON null → null；缺失 → null；空白 → null；真值原样；`"nullify-me"` 不被误伤；`{"client_id": null, …}` 不再满足 `isConfigured`；全 null 的块判为「没有配置」；接线臂把旧形态字面量的出现次数钉成「Config 1 次（仅助手本体）/ Controller 0 / Store 0」，并额外钉桥读取那条链的**两个属性**（可派生 + 拒绝 JSON null）。

### F-18 [P1] 自定义 `redirectUri` 与实际监听端点不等价 — 成立

**先证**（`baseline_f18.py`，把基线 host-only 判定照抄一遍跑两条 URI）：

```
baseline validates the host only                     CONFIRMED
baseline silently defaults the port                  CONFIRMED
baseline has no scheme check                         CONFIRMED
baseline has no endpoint policy                      CONFIRMED
baseline accepted, listening on 54546                CONFIRMED  (no port -> browser goes to :80)
baseline accepted, listening on 54546                CONFIRMED  (TLS against a plain socket)
```

**修法**：

- 新增 companion 纯函数 `redirectEndpointError(redirect, listenPort = LOOPBACK_PORT)`：URI 可解析 → `scheme == http`（回调服务是明文 socket）→ `host ∈ {localhost, 127.0.0.1}` → `port == listenPort`；三段各自给出可读理由
- `authorize()` 先调它，非 null 立刻 `Result.Failed`，**早于** `CustomTabsIntent` 打开
- `port` 不再做 `URI(redirect).port ?: LOOPBACK_PORT` 的静默兜底 —— 校验已把端口钉死，监听端口就是常量

**必测**（8 例）：无端口 http 拒绝（文案含 54546）/ 带 54546 http 通过 / https 拒绝（含 http）/ 非回环 host 拒绝（含 loopback）/ 别的回环端口拒绝 / 非 URI 拒绝 / scheme+host 大小写不算策略 / **接线臂**：`redirectEndpointError(redirect)` 出现在 `CustomTabsIntent.Builder()` 之前，且旧的 `val redirectHost = runCatching` 已消失。

## 4 装置与变异臂

五个装置互相独立，工作树外，逐个 `run-fXX.sh`：

| 装置 | 编译面 | 结论 |
|---|---|---|
| `run-f03.sh` | `extract.py` 从真实 `BrowserTabPool.kt` 按花括号切出两个纯函数（切片长度校验） | 10 例全绿 |
| `run-f04.sh` | **真实** `BrowserUseJS.kt` + `BrowserAction.kt` + 真实测试类（JS 文件 Android-free），node 驱动 | 12 例全绿 |
| `run-f10.sh` | 真实测试类（python3 驱动真实 `main.py`） | 14 项断言全绿 |
| `run-f17.sh` | **真实** `MCPOAuthConfig.kt` + 真实测试类 + Android 语义探针 | 13 + 4 全绿 |
| `run-f18.sh` | `extract_f18.py` 从真实 `MCPOAuthController.kt` 切出策略 + 两个常量 | 8 例全绿 |

变异负控 **29 臂全部 RED**（快照 → 变异 → 跑 → `finally` 还原 → **还原后**才比 sha256）：

| 条 | 臂 | 翻红用例 |
|---|---|---|
| F-03 | M1 自动跟随打开 / M2 Cookie 无条件 / M3 拒绝被忽略 | 各 1（接线臂） |
| F-03 | M4 凭据每跳都带 | 2（跨主机 + 无法解析） |
| F-03 | M5 放行 https→http / M6 跳数 off-by-one | 各 1 |
| F-04 | M1 删运行预算 | 4 |
| F-04 | M2 删 Content-Length 早失败 / M6 删 `tooLarge` 标记 | 各 2 |
| F-04 | M3 边界 `>=` / M4 不 cancel | 各 3 |
| F-04 | M5 恢复逐字节拼接 | 1 |
| F-10 | M1 remove 不清 | 3 |
| F-10 | M2 add 不清 / M3 清理挪到写之后 / M4 吞掉失败 | 各 3–4 |
| F-10 | M5 不清 `.secret` / M6 不清 `.json.tmp` | 各 1 |
| F-17 | M1 助手不判 isNull | 1（**`a JSON null beats a non-blank fallback`**，全平台可见的那条） |
| F-17 | M2 scopes / M4 别名步进 / M3 tokens() / M6 桥读取 / M5 两处 token exchange | 1 / 1 / 2 / 2 / 2 |
| F-18 | M1 scheme / M3 host | 各 1 |
| F-18 | M2 port | 2 |
| F-18 | M4 不调用 / M5 算了不用 | 各 1（接线臂） |

两条装置纪律：
- 还原顺序：先 `finally` 还原再比 sha（写反会永远报 RESTORE-FAILED，本轮 F-03 一开始就踩过）
- 期望集要写明重叠：M1/M2/M3（F-03）各自只翻同一条接线用例是**设计如此**（三个性质落在同一函数体内），不是粗网

## 5 门与本地验证

**未跑**全量 `scan.sh` / `test_scan.py`（本支硬约束）。改为**逐门单跑**命中本次改动面的 8 道门：

| 门 | 结果 |
|---|---|
| `mcp_oauth_lifecycle_check` | 修 F-17 后 **FAIL**（C2 无法派生字段名）→ 按 §3.4 调整后 **PASS** |
| `browser_cookie_ui_refresh_check` | PASS（读 `BrowserTabPool.kt` 的那条判据未被本次改动破坏） |
| `downloads_atomic_update_check` | PASS（6 个原子写点、0 非原子写） |
| `deeplink_settings_routes_check` | PASS |
| `browser_destroy_wakeup_check` / `browser_history_snapshot_check` / `browser_tab_exec_serial_check` / `browser_tabs_lifecycle_check` | PASS |
| `discarded_return_guard` / `collection_copy_on_mutate_check` | PASS |

本地编译面盲区处置：两个**引用 Android-bound 类**的测试类（`BrowserDownloadRedirectPolicyTest`、`MCPOAuthRedirectEndpointTest`）无法在沙箱真编译，做了**替身形语法编译**（把 `BrowserTabPool.` / `MCPOAuthController.` 换成抽取出的同签名 probe 对象再 kotlinc）→ 两个类 0 error。真正的编译验证仍由 CI 承担。

## 6 残留（带触发条件）

| # | 残留 | 为什么本支不做 | 复活条件 |
|---|---|---|---|
| R1 | 宿主 `MCPRepository.load()` 不清扫**孤立**桥文件（只清配置里有的名字） | `data/repository/MCPRepository.kt` 在禁碰清单 | 出现「配置文件里没有、`oauth/` 里还有」的实测残留；或做备份导出审计 |
| R2 | `BrowserUseManager.fetch()` 解码前无二次长度校验 | 该文件在飞支独占 | F-05 收口后，在同一支里加 `raw.length` 上限（当前 JS 侧已封顶 16 MiB，风险已被上游挡住） |
| R3 | 已写坏的 `"refresh_token": "null"` 存量数据不会被就地修复 | 需要迁移/清洗，超出本条 | 有用户报「授权成功但刷新 invalid_grant」且桥文件确为字面 `"null"`；自愈路径是重新授权 |
| R4 | `cmd_remove` 先删配置再清凭据（清理失败时配置已消失） | 反过来会让「删不掉凭据」永久阻塞删服务器 | 若真出现清理失败导致残留，改成「先清后删 + 失败回滚」 |
| R5 | F-03 的 `$VAR`/命令替换产物不参与路径判定（`rm -rf $(echo …)` 一类） | 与本条无关（guard 支已登记） | 见 `backlog` §1119-C |
| R6 | F-05（`evaluateJavascript` 无超时）不在本支 | 任务书明确排除，等 C2 支收口 | C2 收口后另派 |

## 7 CI

**代码 tip**：`6b2d3891c51b32a5ecd158b82b9158f414a7bede`（本地 `git rev-parse HEAD` = `git ls-remote origin` 逐字符一致）

| 项 | 值 |
|---|---|
| run | **#3339** / id `44468573445`（`workflow_dispatch`，分支 `fix/r3-browser-1119`） |
| head_sha | `6b2d3891c51b32a5ecd158b82b9158f414a7bede`（与本地/远端逐字符一致） |
| jobs | scan-gate / unit-tests / build-apk / lint / publish **全 success** |
| publish | 两步 `skipped`（`Move the rolling tag android-latest to this commit`、`Publish to Releases`）= main 门控生效，分支不污染 release |
| unit-tests | **9,870 PASSED 行 / 0 真 FAILED**（4 行含 `FAILED` 字样的都是 PASSED 的用例名：`slot denied produces FAILED…` 等） |
| scan-gate | `115 passed, 0 failed`；日志另有 `scripts/scan/** untouched in this push — skipping the fixture self-test (~121s)` ⇒ **本支未动门，扫描夹具按机制跳过，这次绿不含夹具证据** |

**五个新测试类逐条核过（不是只看总和）**：

| 类 | CI 行 | PASSED | SKIPPED | FAILED |
|---|---|---|---|---|
| `BrowserDownloadRedirectPolicyTest` | 12 | 12 | 0 | 0 |
| `BrowserFetchByteBudgetTest` | 12 | 12 | 0 | 0 |
| `MinisMcpCliOAuthCredentialCleanupTest` | 1 | 1 | 0 | 0 |
| `MCPOAuthNullStringTest` | 13 | 13 | 0 | 0 |
| `MCPOAuthRedirectEndpointTest` | 8 | 8 | 0 | 0 |

F-04 的 **5 条 node 行为臂在 CI 上真的跑了**（不是 skip）：`BrowserFetchByteBudgetTest 12 PASSED / 0 SKIPPED`，且 `showStandardStreams = true` 让 F-10 的 14 行 `CHECK PASS …` 落进日志，逐项可见。

## 8 处置

- **停止节点**：分支 CI 绿 + `head_sha` 逐字符一致，**不合 main**
- 报告提交在绿之后，属纯文本（`fix-reports/**`）；提交后核 `git diff <绿sha>..HEAD` 只含本报告
- F-05 等 C2 支收口后再派；F-03/F-04/F-10/F-17/F-18 五条可进下一轮并集