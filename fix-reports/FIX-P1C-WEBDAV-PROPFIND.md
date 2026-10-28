# FIX-P1C-WEBDAV-PROPFIND — PROPFIND 按 body 判定，不再把 200+HTML 读成「服务器上没有备份」

**结论一句话**：`WebDavClient.propfind()` 现在必须以 body 是不是 WebDAV multistatus 文档为准，不是以状态码为准；非 DAV 的 2xx 响应抛 `WebDavNonDavResponse`（不是 `WebDavException`，所以永远不会被「404 ⇒ 目录不存在 ⇒ 空列表」这条宽容路径吃掉），`testConnection()` 走同一条判据，于是「连接正常」和「服务器上没有备份」这组自相矛盾的读数不会再同时出现。

**分支**：`fix/p1-webdav-propf`（基线 main `e6f2e4be`）· **代码提交**：`418728c5`
**改动面**：`backup/WebDavClient.kt` +102/−3、`backup/WebDavClientTest.kt` +122（8 例新增），共 2 文件 +224/−3，**无其他文件触碰**（未动 `WebDavSync`、未动 `ConfigBackup`、未加门、未加 UI）。

---

## 1. 缺陷与根因（已回树核实，行号是 `e6f2e4be` 之后本分支的树）

世界 W5 = captive portal / 反向代理错误页 / SSO 登录页。链路每一环都在树上指得出来：

| 环节 | 事实（修前） |
|---|---|
| 状态码放行 | `propfind()` 只判 `if (code != 207 && !response.isSuccessful) throw`——200 + HTML 完全合法通过 |
| 解析坍缩 | `parsePropfind()` 扫不到 `<response>` 节点 ⇒ 返回 `emptyList()`，与「合法的空目录」**同一个返回值** |
| 调用点照单全收 | `list()` → `WebDavSync.listBackupFiles()` 的 `catch (WebDavException)` 只吞 404，空列表正常返回 |
| UI 读数 | 远程备份列表 = 空 ⇒「服务器上没有备份」；同一条路径上 `testConnection()`（depth 0）看到 207/200 ⇒「连接成功」 |

用户在 W5 里得到的是**两个都错、且都自信**的结论：网络正常、备份全没了。真正的真相是「我们根本没和 WebDAV 服务器说上话，看到的是中间那台设备/门户的登录页」。

**为什么这不是「罕见的网络异常」**：公共 Wi-Fi、酒店、机场、公司门户、以及任何返回自定义错误页的反向代理，都会把 `POST/GET/PROPFIND` 一律回 200 + HTML。这是 W5 世界的常态，不是边角。

**这不是 Content-Type 能修的**：大量 WebDAV 服务器（含自建 NAS）不发 content-type 或发错，判据必须落在 body 本身，而不是一个同样不可信的头部。

---

## 2. 修法（判据下沉到一次解析缝，调用点零改动）

### 2.1 `parsePropfind()` 变成三值语义（`WebDavClient.kt:380`）

```kotlin
private fun parsePropfind(xml: String, requestUrl: HttpUrl): List<WebDavResourceInfo>?
```
- 返回 **`null`** = 「这不是一个 WebDAV 文档」（登录页 / 错误页 / 截断的 XML / 压根没有元素）
- 返回 **`emptyList()`** = 「**是**合法 multistatus，只是零个 `<response>`」= 真·空目录

这个区分只在这里做一次，比在每个调用点重判稳——任何未来新增的 PROPFIND 调用点自动继承。

**三个信号，全部不使用 Content-Type**（`parseMultistatus`，`:394`）：

| 信号 | 判据 | 挡的是什么 |
|---|---|---|
| 1（快路径） | body 是否含 `multistatus`（大小写不敏感） | 登录页通常一次都不提这个词，XML 机制根本不启动 |
| 2（根元素） | **实际解析出来的文档根**必须是 `multistatus`（忽略命名空间前缀） | 页面正文里恰好出现 "multistatus" 字样的（代理把自己的拒绝理由写在页面里） |
| 3（配对完整） | 遍历结束时元素配对必须归零（`openElements != 0 ⇒ null`，`:489`），且必须出现过根元素（`:492`） | 截断 / 半截响应——**kxml2 对「文档中途断掉」是宽容的：它不抛异常，直接报 END_DOCUMENT**，因此「完整性」只能数出来，不能假设 |

任何解析异常（`XmlPullParserException` / `IOException` / 其他）在 `parsePropfind` 内被收敛成 `null`（`:386-393`），调用点只面对一种类型。

### 2.2 `propfind()`：status 只管「这不是 2xx 之外」，body 才是权威（`:207`）

```kotlin
return parsePropfind(xml, url) ?: throw WebDavNonDavResponse(
    if (code == 207) { "Server returned an unreadable WebDAV response (HTTP 207): the body is not a multistatus document." }
    else { "Server returned a non-WebDAV response (HTTP $code) — possibly a Wi-Fi login portal or a proxy page." },
    code,
)
```

200 + 合法 multistatus 仍然接受（有服务器就是这么回的）；207 + 非 multistatus 单独一句文案（说清「状态码对、文档不对」）。

### 2.3 新异常类型 `WebDavNonDavResponse`（`:65`）

**故意不继承 `WebDavException`**，这是本修法的关键设计点，不是风格选择：
- `WebDavException.statusCode` 是**会被翻译成语义**的（`listBackupFiles` / `listAutoBackupEntries` / `ensureCollectionExists` 里三处 `if (e.statusCode == 404) emptyList()/false`）。登录页如果长这个类型，就永远有被某处宽容分支吃掉的风险。
- 继承 `IOException` ⇒ 现有错误映射 `webDavErrorMessage` 的 `t is IOException` 分支命中，用户看到的是**我们写的那句人话**（`"Network error: Server returned a non-WebDAV response (HTTP 200) — possibly a Wi-Fi login portal or a proxy page."`），而不是 `"Server error (HTTP 200)"` 这种自相矛盾的读数。
- `statusCode` 保留给诊断（日志/测试断言），不参与控制流。

### 2.4 `testConnection()` 同判据（`:352`）

它只是调 `propfind(depth = 0)` 并容忍 404；新异常**穿透**（catch 只抓 `WebDavException`），所以连接测试与备份列表现在对同一条链给出同一个结论。KDoc 里把这条写成了契约，防止以后有人「顺手把它宽容掉」。

---

## 3. 装置（P1 配比：最小复现，不建新门）

`WebDavClientTest.kt`（MockWebServer，**不起服务器、不起进程**）新增 8 例，全部断言行为而非实现：

| # | 用例 | 断言 |
|---|---|---|
| 1 | `list throws on 200 plus an html login page instead of an empty list` | 抛 `WebDavNonDavResponse`；`statusCode == 200`；文案含 `non-WebDAV response` / `HTTP 200` / `portal` |
| 2 | `testConnection fails when the server answers with an html page` | 抛同一异常（**这一条是本缺陷价值的一半**：修前它报「连接成功」） |
| 3 | `an html page that merely mentions multistatus is still refused` | body 里**有** `multistatus` 字样也必须拒（只靠信号 2 才可能过） |
| 4 | `a 207 whose body is not a multistatus document is refused with its own wording` | 抛，`statusCode == 207`，文案含 `HTTP 207` |
| 5 | `truncated multistatus xml is refused rather than read as an empty folder` | 根是 multistatus 但文档中途断掉 ⇒ 抛（信号 3） |
| 6 | `list returns an empty list for a genuine multistatus with zero responses` | **不抛**，返回 0 条（这就是「合法空目录」，最容易被修法误伤的一臂） |
| 7 | `list still returns the single file of a one-entry multistatus` | 仍是 1 条、名字与时间正确（既有行为零回归） |
| 8 | `listBackupFiles propagates the non-WebDAV failure instead of an empty list` | 用户可见症状那一层：`WebDavSync` 不再把它变成空列表 |

**负控（变异，四组，脚本 `probe/make_mutations.py` + `probe/run_suite.sh`，生产文件在 scratch 副本上改，工作树零污染）**：

| 变异 | 内容 | 结果 |
|---|---|---|
| M1 | `parsePropfind(...) ?: throw` 改回 `?: emptyList()`（= 修复前的行为） | **6 红**（用例 1/2/3/4/5/8 全红，6、7 绿） |
| M2 | 删信号 2（根元素判定） | **2 红**（3、4） |
| M3 | 删信号 3（配对完整判定） | **1 红**（5） |
| M4 | 删信号 1（子串快路径） | **0 红 = 全绿**（诚实记录：它是性能优化，不承担判定；见 §6 R3） |

---

## 4. 本地读数（原文）

```
COMPILE OK [base]
OK (31 tests)                     # 23 例既有 + 8 例新增，JUnit 4.13.2
```

- 装置口径：`kotlinc` 直接编译**生产源文件**（`WebDavClient.kt` + `WebDavSync.kt`）+ 真测试文件，不切片、不复刻；依赖 `okhttp 4.12.0` / `okio-jvm 3.6.0` / `mockwebserver 4.12.0` / `kxml2 2.3.0`（与 `build.gradle.kts:330/390/399` 同版本）。
- 静态门：`bash scripts/scan/scan.sh --jobs 3` → `Summary: 113 passed, 0 failed`。
- 夹具：`python3 scripts/scan/test_scan.py --jobs 4` 在本沙箱**不可用**（`/tmp/.minis_cwd_*` 创建返回 `Errno 38 Function not implemented`，47 组全部 `scheduler: OSError`）；按既有健康路径逐组独立解释器跑（`probe/test_scan_loop.sh`，可续跑）：`GROUPS=48 CASES_PASS=349 CASES_FAIL=0`。

## 5. 云端读数

| 项 | 读数 |
|---|---|
| run | **#2798**（`40612755880`，job `122568022914`，attempt 1），**success** |
| 时长 | 17m15s（`2026-10-28T01:04:06Z` → `01:21:21Z`） |
| head_sha | `418728c5ad25996a2b9eedf0f2494c8110d744be`，与本地 HEAD **逐字符一致** |
| 步骤 | 24 步，唯一非 success = `23 Move the rolling tag android-latest` / `24 Publish to Releases`（均 main 门控 skipped） |
| 日志体量 | 1,483,633 B，NUL 字节 0（逐条可读） |
| 静态门 | `Summary: 113 passed, 0 failed` 恰 1 次 |
| 夹具 | `ALL 349 CASES PASS` 恰 1 次；`Errno 38` 0 次（云端用 `--jobs 4` 正常） |
| Kotlin 编译 | `^ e: ` 0 行 |
| 判读陷阱复核 | 含 `FAILED` 的行 6 条，**逐条确认全是 `… PASSED` 用例名子串**（`RealAgentAdapterSkeletonTest > slot denied produces FAILED… PASSED` 等），无真失败 |
| 本次测试 | `WebDavClientTest > … PASSED` **31 条**（含 8 条新增，逐名在册）——真 Android SDK classpath 下全绿，非仅沙箱 JVM |

## 6. 残留（三件套：症状 / 触发条件 / 升级触发）

**R1（P1-latent）`put()` / `get()` / `delete()` 没有同类判据。**
- 症状：W5 世界里点「立即备份」→ PUT 到 captive portal 收到 200 + HTML ⇒ `response.isSuccessful` 为真 ⇒ **提示「备份成功」，服务器上没有文件**；下载/删除同型（`get` 会把登录页 HTML 当备份正文交给 `ConfigBackup.import`）。
- 触发条件：上传/下载/删除路径撞同一个 captive portal / 反代错误页（与本次完全同世界）。
- 升级触发：用户报告「提示备份成功但服务器上没有」或「恢复报 JSON 解析错」，或下一次动这三个方法时（届时复用 §2.1 的 seam 即可，不新建判据）。
- 为什么本次不做：任务书 `不要做` 明确排除（「不改 `WebDavClient` 的其他方法（PUT/DELETE/下载）」），且 PUT 的修复需要先定「非 2xx 之外的 body 嗅探会不会误伤合法 200 上传」——那是另一个判据设计，不该塞进本支。

**R2（P2）`parsePropfind` 把所有 `Exception` 收敛成 `null`（`:386`）。**
- 症状：未来若有人把这里改成流式/读磁盘/加 I/O，异常会被静默翻译成「非 WebDAV 响应」，错误信息会指错方向。
- 触发条件：该函数体内出现 I/O 或依赖外部状态的调用。
- 升级触发：`parsePropfind` 签名或体积变化（加参数、> 120 行、出现 `File`/`InputStream`）。

**R3（P3，记录性说明）信号 1（`multistatus` 子串快路径）无测试锚点——M4 变异全绿。**
- 症状：若有人把它当成判定信号并依赖它（例如删掉信号 2 后靠它挡），会得到「HTML 里带 multistatus 字样即被当成空目录」的洞。
- 触发条件：信号 2 被改动/移除。
- 升级触发：`parseMultistatus` 的根元素判定被触碰时，同时补一条「信号 1 单独不足以判定」的断言。

**R4（P2-latent）响应体全量读入内存**：`response.body?.string()` 对 captive portal 的 MB 级 HTML 没有上限。触发条件 = 门户返回超大页面；升级触发：出现该路径的 `OutOfMemoryError` 或 body 读取进入热路径。

**其他同族但不动的（明确判断为「正确」而非残留）**：`mkcol()` 只认 201/405 ⇒ 若门户对 MKCOL 回 200 会被判失败（**fail-closed 方向，不产生假成功**），保持现状；`WebDavSync` 三处 `statusCode == 404 ⇒ 空列表` 语义正确（新类型穿透，不被吞）。

## 7. 未验证 / 诚实边界

- **没有真机、没有真实 captive portal、没有真实 WebDAV 服务器**：所有非 DAV 响应都是 MockWebServer 里构造的固定 body；真实门户页的形态（gzip、chunked、BOM、`<meta http-equiv>` 跳转）未逐一采样本支只保证「非 multistatus 文档一律拒」这个方向成立，不保证文案对每一种门户都贴切。
- **UI 文案语言**：消息体是英文，与本文件既有异常文案一致（`Refusing to send credentials over plain HTTP…` 同款），经 `webdav_err_network`（`Network error: %1$s`）呈现——`res/values` 只有一套语言，没有引入硬编码中文字符串。
- **信号 3 依赖 kxml2 的行为**：本装置在 `kxml2 2.3.0`（= Gradle 测试依赖）上验证；Android 运行时用的是平台 `org.xmlpull`（`android.util.Xml` 血统），对「文档中途结束」同样是宽容的（`XmlPullParser` 契约里 `next()` 到 EOF 报 `END_DOCUMENT`），但**未在真机上跑过该分支**。

## 8. 可复用手法

1. **「宽容类型」是缺陷载体，不是便利**：`WebDavException.statusCode == 404 ⇒ 空列表` 这条宽容是**正确**的（目录未建），但正是因为存在，任何「非 DAV 响应」都必须走**另一个类型**——判断依据是调用点会不会把它翻译成业务语义，而不是「异常长得像不像」。
2. **解析器的宽容必须被计数补上**：kxml2 对截断文档不抛异常（本次实测），所以「解析成功但零条目」与「解析失败」之间还差一层「文档是否完整」。凡是用 `XmlPullParser` 判定「没有数据」的地方，都要问一句「我是数出来的，还是它没告诉我」。
3. **穷举三个候选信号并各自做变异**：快路径（子串）+ 结构（根元素）+ 完整性（配对）三者分开写、分开删，才看得出哪一个真的在承担判定（M4 全绿就是这条方法的产出——否则会把一个性能优化误记成安全判据）。
4. **同一路径上的两个读数必须同源**：本缺陷一半的价值在「列表说没备份 / 连接测试说正常」这对矛盾。修这类问题的判据是「同一条链上的所有观测点是否共用同一个判定缝」，而不是「每个观测点各自看起来对不对」。
5. **沙箱夹具跑法**：`test_scan.py --jobs 4` 在本沙箱 100% `Errno 38`；`--single` 逐组 + 后台 nohup + 进度行写文件是可续跑的健康路径。**注意 `| tail` 会让工具侧 180s 看不到输出而被 stall-guard 杀掉**——长跑要么后台 + 轮询，要么每步 flush 到文件。
