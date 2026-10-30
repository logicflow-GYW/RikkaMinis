# FIX-FW-OAUTH-UTF8 — OAuth 回调页字节长度 + error 空值卡死

- **分支** `fix/fw-oauth-utf8`（基线 main `039c9ab2`，代码提交 `a419f5c9`）
- **改动面**：生产 1 文件 `+103/−15`（`mcp/oauth/OAuthCallbackServer.kt`）；测试 `+213/−1`（新增 `OAuthCallbackResponseTest.kt` 136 行 + `OAuthCallbackServerFdTest.kt` 77/1）
- **门禁**：`scan.sh --jobs 3` = **113 passed, 0 failed**（改前基线同样 113/0）；`test_scan.py --jobs 3` = **ALL 349 CASES PASS**（改前基线 349/349）
- **停止节点**：分支 CI 绿，未合 main。

---

## 1. 三条任务的结论

| 任务 | 结论 | 一句话 |
|---|---|---|
| D1 UTF-16 长度 / 缺 charset | **成立** | `Content-Length: ${html.length}` 是代码单元数，写的是 UTF-8 字节；且无 `charset`。 |
| D2 `error=` 空值卡死 5 分钟 | **成立**（机制与任务书描述一致，逐字复核过） | 两个判据分裂：终止用 `!error.isNullOrBlank()`，渲染用 `error != null` ⇒ 空值渲染了失败页却两个出口都不走。 |
| D3 绕开路径核对 | **已核对，未修**（下一节） | 四条分支全覆盖；`code`+`error` 同时出现时 **code 胜**。 |

**可达性诚实交代**：本机 6 天主日志里 `authorization denied by the server` 出现 **0 次**（`grep` 过 `minis-2026-10-*.log`），即拒绝分支在现网**从未被触达**，D1 的非 ASCII 触发条件（provider 返回中文 `error`）更是在这 0 次之上再叠一层。⇒ **两条都属于「路径成立、现网未触发」**；修它们的理由是失效形态是「用户干等 5 分钟且页面已经报了失败」，而不是日志里有实证。这一条写进残留 R1。

---

## 2. D3 绕开路径核对（只读，不改）

`OAuthCallbackServer.start()` 的 accept 循环里，一条 `/callback?...` 请求能走到的分支只有三处：`denied → stop()`、`code != null → onCode + stop()`、以及「都不满足 → 什么都不做，继续 accept」。逐组合：

| `code` | `error` | 页面 | 是否终止 | `onExternalCancel` | 备注 |
|---|---|---|---|---|---|
| 有 | 无 | success | ✔ `onCode` → `stop()` | 先置 null（`[sweep1014-D5b]` 契约，一字未动） | 正常授权 |
| 无 | 有（非空） | 拒绝页 + 原因 | ✔ `stop()` | 保持武装 ⇒ 调用方拿 `Cancelled` | 修前即正确 |
| 无 | 有（空串 `?error=`） | **修前**：拒绝页（`reported: .`）；**修后**：拒绝页（无原因措辞） | **修前：不终止（bug）** / 修后：✔ | 修前永不 fire ⇒ 5 分钟超时；修后 fire | **D2 本体** |
| 无 | 无 | success（`/favicon.ico`、浏览器预连接、用户手开回调 URL、只有 `state=`） | ✘ 不终止 | 不动 | **刻意保留**：单线程 accept 必须继续服务真正的回调；这类请求渲染成 success 只是给手开 URL 的人看的，不影响 OAuth |
| 有 | 有 | success | ✔ `onCode(code)` | 先置 null | **code 胜**：`denied=false`，走成功分支；RFC 6749 §4.1.2.1 不应出现，行为未改（任务书要求「别改」） |

附带确认的两条边界（写进残留，未改）：

- `?code=`（code 存在但为空串）：`code != null` ⇒ 走成功分支 `onCode("", state)`，下游换 token 会失败并报 provider 错误（不是「没有 code」的清晰提示）。**R2**。
- `?code=x&error=y` 时页面对 `error` 只字不提、也不打日志。**R3**。

---

## 3. 修法（两处，都在 `OAuthCallbackServer.kt`）

### D1 — 一个构造器同时拥有字节和长度

```kotlin
internal fun callbackHttpResponse(html: String): ByteArray {
    val body = html.toByteArray(Charsets.UTF_8)
    val head = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/html; charset=utf-8\r\n" +
        "Content-Length: ${body.size}\r\n" +
        "Connection: close\r\n\r\n"
    return head.toByteArray(Charsets.UTF_8) + body
}
```

结构性要点：**字节只产一次**，头部里的数字从同一个数组派生，不存在「第二次编码与声明不一致」的可能（原缺陷正是两次独立计算）。handler 里那行三合一后只剩
`socket.getOutputStream().write(callbackHttpResponse(callbackPageHtml(code, error)))`。

### D2 — 一个判据同时拥有页面、日志、终止

```kotlin
internal fun callbackDenied(code: String?, error: String?): Boolean = code == null && error != null
```

- handler 的 `denied` = 页面分支的 key = 日志的条件 = `stop()` 的条件（原先三处两个判据）。
- `parseCallbackQuery` 的语义保证了「`error` 出现但为空 ⇒ `params["error"] == ""`，未出现 ⇒ `null`」，所以**用可空性、不用 `isBlank()`** 就是「参数出现即终止」。
- 空值页面换兜底措辞：`The authorization was refused by the server, which gave no reason.`（**不再渲染** `The authorization server reported: .`）。
- 日志区分两件事：`error=<empty>` vs `error=access_denied`。
- **终止仍走 `stop()`**（不抛异常），`[sweep1014-D5b]`「成功路径先置 null 再 stop」一字未动。

页面构造抽成 `callbackPageHtml(code, error)` 且 **keyed on `callbackDenied`** —— 页面与终止不可能再分裂，这正是原 bug 的形态。

---

## 4. 装置

### 4.1 JVM 真源 harness（`shared/work/fixwave-1030c/probe-d/`）

- `src/OAuthCallbackServer.kt` 与仓库文件 **md5 逐字节一致**（`57d1914b…`），测试文件同样抄真源，只补一个 `android.util.Log` 桩（`AndroidLogStub.kt`，15 行）。
- 编译 + 跑 JUnit：**26/26 OK**（四个类：新增 8 例 + 既有 FdTest 6 例 + HtmlEscape + QueryDecode）。
  - **真起 ServerSocket**：仓库已有先例（`OAuthCallbackServerFdTest` 本来就在真 socket 上跑），所以两条新 wire 用例沿用同一套 helper（`awaitConnect`/`request`，新增 `rawRequest` 保留原始字节）。
  - 判据是**线级**的：解析响应头里的 `Content-Length`，与 `\r\n\r\n` 之后实际字节数**逐字节比对**；`assert 197 != 193` 那半句专门钉住「代码单元数」这个旧错误。

### 4.2 判据全部字面量（手算，不从实现推导）

| 断言 | 字面量 | 手算依据 |
|---|---|---|
| 小页 `<p>拒绝</p>` | 13 字节 / 9 字符 | 7 ASCII + 2×3 |
| 中文拒绝页（`error=拒绝`） | 197 字节 | 191 ASCII + 6 |
| 空 `error` 拒绝页 | 221 字节 | 全 ASCII |
| success 页 | 118 字节 | 全 ASCII |

### 4.3 变异臂（每条判据配 1 条，5/5 RED-AS-EXPECTED）

| 臂 | 变异 | 结果 |
|---|---|---|
| M1 | `${body.size}` → `${html.length}` | 3 红（两条字节断言 + 1 条 wire） |
| M2 | `denied` 退回 `!error.isNullOrBlank()` | 1 红（wire：`error=` 不终止） |
| M3 | 去掉 `charset=utf-8` | 2 红 |
| M4 | 空值走「有洞」句子（`if (shown.isEmpty())` → `if (false)`） | 3 红 |
| M5 | 页面退回旧的 blank 判据（页面/终止分裂） | 4 红（含 `page and predicate agree`） |

> M1/M3 只打红「含中文」的三条，ASCII 页的字节断言**照样绿**——这是有意的：ASCII 下 char 数 == 字节数，那些断言本来就不该有牙。判据与缺陷同形，不是随便挑的测试。

---

## 5. CI 判读

- run **41017470845**，`fix/fw-oauth-utf8`，attempt 1，**conclusion = success**，`head_sha = a419f5c945266f99b8b04d5631087f92d89c5609` 与本地 HEAD **逐字符一致**（`git rev-parse HEAD`）；07:00:58Z → 07:15:54Z ≈ **14.9 min**。
- 27 步：Publish / rolling tag 因非 main 被 skip，其余全 success（`0_build.txt` 无 `Publish` 步骤段落）。
- job 日志 1,681,704 B / **NUL 0** / 20,897 行：
  - `Summary: 113 passed, 0 failed`（前置扫描门）；`✅ ALL 349 CASES PASS`（夹具）；
  - `^e: ` **0**（无编译错）；真 ` FAILED$` **0**；
  - 单测分片 `Summary: N passed, 0 failed` × 6（25/16/44/19/30/18）；
  - **本支用例逐条 PASSED**：`OAuthCallbackResponseTest` 8/8、`OAuthCallbackServerFdTest` 6/6（含新增 wire 两条与既有四条）。

> 判据口径复认：`fix-reports/` 提交在绿之后追加，判据不是 sha 相等，而是 `git diff --name-only <绿sha>..HEAD -- . ':(exclude)fix-reports'` 为空。


---

## 6. 残留（带触发条件）

| # | 残留 | 等级 | 触发条件（何时该回头修） |
|---|---|---|---|
| R1 | 现网 0 次触达拒绝分支：D1/D2 的修复没有真机/线上证据，全部证据是 JVM 线级 + 源码级 | 信息 | 真机出现「回调页残缺/乱码」或「点了授权后干等 5 分钟」的报告 |
| R2 | `?code=`（空串 code）会进成功分支 `onCode("")`，下游换 token 报 provider 错误而不是「没有 code」 | P2 | provider 发过一次 `?code=`（从未观测），或用户反馈「换 token 报 400 但页面说授权完成」 |
| R3 | `code`+`error` 同时出现时 code 胜、`error` 不落日志也不上页面 | P3 | 日志里出现同一请求同时带两个参数 |
| R4 | 拒绝页只用 `error`，`error_description`（RFC 6749 §4.1.2.1 里真正的人类可读原因）被忽略 | P2 | 用户反馈「页面没说原因」而 provider 实际发了 `error_description` |
| R5 | 回调页无 `Cache-Control: no-store` | P3 | 出现「重试后仍看到上一次的拒绝页」 |
| R6 | OPTIONS 预检响应仍用 `pre.toByteArray()`（默认字符集）且无 charset | P3 已核**非缺陷** | 只有 `Access-Control-Allow-Origin` 回显 origin，且只回显白名单 `https://auth.x.ai`，其余一律 `"null"`，全 ASCII ⇒ 不存在字节/字符差 |
| R7 | 未做端到端真机验证（需要真实 provider 的 OAuth 流程） | 信息 | 下次真机走一次 MCP OAuth 回调 |

`scripts/scan/**`、`scan.sh`、`test_scan.py`、`MCPOAuthController`、`onExternalCancel` 语义：**零改动**。
