# FIX-WEBDAV-METHOD-JUDGEMENT：PUT/GET/DELETE 按 body 判定（W5 假成功的另一半）

- 日期：2026-10-28
- 基线：main `12bdcebb`
- 分支：`fix/webdav-method-judgement`
- 代码提交：`ba28eb79`
- 分支 CI：run `40630682850` attempt 2 **success**（head_sha `ba28eb79cd0f55510241c7694f3532a0f0e6b022`，与本地逐字符一致）
- 来源：backlog「P1-latent WebDAV 上传/下载/删除仍按状态码判定」；触发条件「下次动这三个方法」= 本轮 scenario-experiment-1028 收口时达成

## 1. 缺陷（W5：状态码对内容不对）

10-27 的 PROPFIND 修复只把 `propfind()` 的判据从状态码改成「body 是不是 multistatus 文档」，`put()` / `get()` / `delete()` 仍是 `if (response.isSuccessful)`。同一世界（captive portal / 反代错误页 / SSO 登录页以 2xx + HTML 应答）下：

| 方法 | 旧行为 | 用户看到 |
|---|---|---|
| `put()` | 200 + 登录页 ⇒ 返回成功 | 「备份已上传」，服务器上没有文件 |
| `get()` | 200 + 登录页 ⇒ 把 HTML 交给 `ConfigBackup.import` | 恢复时报 **JSON 解析错**（指向错误的方向） |
| `delete()` | 200 + 登录页 ⇒ 返回成功 | 「已删除」，文件还在 |

## 2. 修法：同一道缝、同一判据、保守到只认「开头是 HTML」

`WebDavClient.assertNotPortalBody(code, body, what)`（新增私有 helper）：

1. 空 body 直接放行（写入/删除的合法成功通常无 body）。
2. 跳过 UTF-8 BOM + 前导空白，取前 256 字节；
3. 只有**开头**是 `<!doctype` 或 `<html` 才判非 DAV 响应 ⇒ `WebDavNonDavResponse`（IOException，经既有 `webDavErrorMessage` 的 IOException 分支显示人话）。

三个刻意选择：
- **不做全文子串匹配**：备份正文里可以出现 `"<html>"`（用户聊天内容），全文匹配会拒掉合法备份。判据只看开头。
- **不判 `<?xml`**：合法 DELETE 可以回 207 + multistatus（XML），把真 DAV 服务器判成门户页是更坏的错误。
- **不在 client 层校验 JSON**：那是 importer 的职责；这里只拒「一定不是我们要的东西」。

`get()` 额外拒绝 **200 + 空 body**：真实备份文档永不 0 字节，空应答说明服务器没有真正提供文件（旧行为是把空字节交给 importer，报误导性 JSON 错）。

`put()` 的两处成功路径（首次 / 建目录后重试）都判——portal 对两次尝试的回答相同，只判一处会留洞。

## 3. 装置

**JVM harness**（复用 `p1-fix-1027/probe/run_suite.sh`，直编生产源文件 `WebDavClient.kt` + `WebDavSync.kt` + 真测试）：`OK (40 tests)`（原 31 + 新 9）。

新增 9 例：
1. put 200 + 登录页 ⇒ NonDav（文案含 Upload/portal）
2. put 重试路径 200 + 登录页 ⇒ NonDav（钉第二处判据）
3. put 201 空 body / 200 + JSON body ⇒ 放行
4. get 200 + 登录页 ⇒ NonDav
5. get 200 + 空 body ⇒ NonDav（文案含 empty body）
6. get 正文内含 `"<html>"` ⇒ **原样返回**（误伤反证）
7. get BOM + 空白 + JSON ⇒ 原样返回
8. delete 200 + 登录页 ⇒ NonDav
9. delete 204 / 200 空 body ⇒ 放行

**变异负控 4 组**（`scenario-experiment-1028/mut.py`），全部只红预期臂：

| 变异 | 红 |
|---|---|
| M1 判据整体失效（直接 return） | 4 例（put×2 / get / delete），get-empty 仍绿（独立判据）|
| M2 判据过宽（任意位置 `contains("<html")`）| 1 例：正文含 html 的合法备份被拒（证明判据不是「永远红」）|
| M3 只删重试路径判据 | 1 例：重试路径门户页 |
| M4 删空 body 判据 | 1 例：get 空 body |

**装置抓到自己的真 bug**：`String(bytes, offset, length)` 首次写成了绝对终点，BOM 用例直接 `StringIndexOutOfBoundsException` 红——这正是「测试要真跑」的价值。

**本地质量门**：`scan.sh --jobs 3` = `113 passed, 0 failed`；`test_scan.py --single test_real_repo` = `20 0`。

## 4. CI 读数（attempt 2）

- 27 步唯一非 success = 滚动 tag + Publish（main 门控 skip）
- `Summary: 113 passed, 0 failed` ×1、`ALL 349 CASES PASS` ×1
- 6 条含 `FAILED` 的行逐条核过，**全是 PASSED 用例名子串**（判读陷阱）
- `SoulStoreOverwriteBackupTest > a concurrent reader never sees a truncated SOUL md PASSED`

## 5. attempt 1 失败归因（与本改动无关，已实证）

attempt 1 失败在 `SoulStoreOverwriteBackupTest > a concurrent reader never sees a truncated SOUL md`：
`reader saw 65598 bytes that are not any complete version (old=65598)`。

- 本支 diff 仅 `WebDavClient.kt` + `WebDavClientTest.kt`，与 SoulStore 零交集。
- 同日 main run 上同一用例 PASSED；同代码 attempt 2 PASSED ⇒ flaky。
- 机制：该用例用 `LinkedHashSet<String> allowed` 跨线程（写线程 `add`、读线程 `text !in allowed`），JDK 集合非线程安全，`HashMap.get` 在并发 resize 期间可能 miss 已存在键。本地实验（`/tmp/Race.java`，500 次试验）实测 **5 次对已存在键 `contains` 返回 false（1%）**，与失败信息「长度相同但不在集合里」吻合（`version-1..9` 序列化后同为 65598 B）。
- 已登记 backlog（P2 装置缺陷，触发条件 = 第二次假红 / 同形态再出现）。

## 6. 登记残留

- **R1（P3）**：`delete()` 收到合法 207 multistatus 且其中条目全是失败状态时仍读作成功（207 是 2xx）。看进 multistatus 需要状态级解析，当前 parser 不做；单文件 DELETE 极少回 207，按装置比例不建第二个解析器。
- **R2（P3）**：`get()` 只拒空 body 与 HTML 开头；纯空白 body / XML 错误页仍会落到 importer 报 JSON 错。判据边界即「一定不是备份」，不扩张。
- **R3（P3）**：`put()` 判据只看 body，不做「上传后 PROPFIND 确认落盘」——那会把每次上传都加上一次往返，而 portal 的 HTML body 已经是充分信号。

## 7. 交叉证据

C 支（本条）与同批 A/B 两支无重叠：A 支（W8 格式漂移）与 B 支（W9 多副本仲裁）都**主动排除**了 WebDavClient（A 支方法反馈第 3 点明说「远端列表/远端包是下一个该看的缝」）。本支正好补上这半边，两边的 W5 判据现在共用同一类型 `WebDavNonDavResponse`。
