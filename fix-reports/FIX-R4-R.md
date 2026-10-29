# FIX-R4-R —— B12 支：debug RPC 面五条

- **分支**：`fix/r4-b12-rpc`
- **基线**：main `2103729f`
- **提交**：`83429223`（五条主体）→ `653b79ee`（F-02 按门 C16 归位）→ `c0d3f0d6`（F-10 头部边界）→ `e65a8724`（F-10 活性守卫）
- **改动面**：3 个生产文件 `+283 / −45`（`DebugRPCHandler.kt` / `HeadlessChatRunner.kt` / `ProviderMutationMethods.kt`），测试 0，`scripts/scan/**` **零触碰**
- **判读口径**：本报告的证据分三级并逐条标注 —— **读码级**（真码锚点，可指认行）> **门级**（`scripts/scan/*` rc=0，机械）> **运行时级**（JVM 探针 / 真机）。本支**没有运行时级证据**，原因见 §8。

---

## 1. 结论摘要

| 条目 | 报告判级 | 先证结论 | 是否修 | 一行修法 |
|---|---|---|---|---|
| F-01 僵尸 ChatViewModel | P1 | **成立**（可达路径 1 条：`chat.session.cancel`） | ✅ | 取 VM 前先读行；cancel 的建实例兜底只在行存在时才走，优先 `peekViewModel` |
| F-02 组成员列表失真 | P1 | **成立，且比报告重**（三条失真，其一可稳定崩溃） | ✅ | 两写入面收敛为一个结构 helper（字符串/非空/原位去重/未知即 `-32602`），有效性谓词按门 C16 留在各面 |
| F-05 探测误报 | P2 | **成立** | ✅ | 2xx 之外还要「响应体是 models 列表」才 `ok=true`，否则 `invalid_value` |
| F-10 分页切断 UTF-8 | P2 | **成立 ×2**（尾部切字符 + 文本判定两向错） | ✅ | 页尾回退到字符边界并让 `bytesRead` 反映实际推进；文本判定改严格 UTF-8 解码 |
| F-11 枚举与 label 静默 | P2 | **成立 ×2**（label 无 trim/无校验；未知枚举塌缩默认臂） | ✅ | label 先 trim 再拒空；`parseStrategy`/`parseFallback` 未知值 `-32602` |

未修 0 条，证伪 0 条。**五条全部成立**，其中 F-02、F-10、F-11 各含多处，任务书只点名了其中一处（逐条见 §2）。

---

## 2. 先证：报告说法 → 真码 → 判定

> 任务书原文（`meta-audit-1029/r4/fix-tasks/FIX-R4-R.md`）在本次基础设施事故中随 `shared/work` 被删，本节「报告说法」按执行时的阅读笔记**重述**而非逐字引用；可复核的部分是真码锚点（行号对 `2103729f`）。

### F-01 `chat.session.cancel` / `.compact` 会物化行-less 的常驻 VM —— **成立**

报告说「僵尸 VM 让后续发送以为用户正在看着该会话」。真码链路三步全对：

1. `ChatViewModelStore.ownerFor(sessionId)`（`ui/chat/ChatViewModelStore.kt:72-80`）——`stores.getOrPut(key) { ViewModelStore() }`，**分配即入表**，永不回收；
2. `viewModelForCancel`（基线 `HeadlessChatRunner.kt:1038`）先 `providers[…]` 再 `ownerFor` + `ViewModelProvider` 取实例 ⇒ VM 被构造并进入上一步的 store；
3. `requireSendableSession`（`HeadlessChatRunner.kt:113-121`）在「行不存在」之后**第二道判据就是 store 里的 resident VM**：`peekViewModel(sessionId) != null` ⇒ 直接 `return`（不报错）。

于是对一个**已删除**或拼错的 sessionId 调一次 `chat.session.cancel`，此后 `chat.session.send` 对该 id 永不再回答 `-32602`。报告没说准的地方是**可达面**：

- `chat.session.cancel`（`debug/ChatMutationMethods.kt:246-255`）**不读行**，直接进 `HeadlessChatRunner.cancel` ⇒ 唯一真实入口；
- `chat.session.compactBefore`（同文件 `:302`）与 `chat.session.revertCompact`（`:392-396`）**都先 `dao.getSession(sessionId) ?: throw -32602`** ⇒ 这两个面在基线就挡住了，runner 内的门是纵深防御（内部调用方也受保护），不是修 bug。

### F-02 组成员列表：静默丢、重复双写、非数组清空 —— **成立，比报告重**

基线两处（`ProviderMutationMethods.kt` 的 `groupsCreate` / `groupsUpdate`）是同一段手写循环：

```kotlin
if (id.isNotEmpty() && SystemVoiceEntries.isKnownGroupMemberId(id, knownEntryIds)) members.add(id)
```

失真三条，报告只点了第一条的一半：

1. **未知 id 静默消失**（`continue` 语义）⇒ 用户传了 5 个成员，落库 3 个，返回 200 无提示；
2. **重复 id 被双写**（没有去重）⇒ 落库 `["a","a"]`；`ui/settings/ModelGroupDetailScreen.kt:313` 是 `itemsIndexed(memberIds, key = { _, id -> id })`，**重复 key 抛 `IllegalArgumentException: Key "a" was already used`** —— 一次 RPC 换用户每次进组详情页都崩，这是本条最硬的部分，报告未提；
3. **`memberEntryIds` 非数组读成空表**（`optJSONArray(…) ?: JSONArray()`）⇒ `groupsUpdate` 把成员列表**清空**并返回 200。`memberEntryIds: "abc"` / `{}` 都命中。

对照面的判据就在同一个仓库里：`config/collections/GroupWriteRules.kt:59-68` `parseMemberEntryIds` —— 非字符串 `TypeMismatch`、空串 `InvalidValue`、**重复原位去重**。RPC 面是唯一没跟上的写入口。

### F-05 `provider.instances.test` 只看状态码 —— **成立**

基线 `ProviderMutationMethods.kt:325-351`：`val ok = resp.isSuccessful`，`error` 恒为 `JSONObject.NULL`。而探测 URL 恒为 `/models` 端点（`providerProbeUrl`，`:891-903`）⇒ 该端点的 2xx 语义是「这是模型清单」。实际打脸场景都在用户手上很常见：`customBaseURL` 填成首页 / 反代登录页 / captive portal，一律 200 + HTML；某些网关 200 + `{"status":"running"}`。两种都被判「可达」，用户只有在对话中途才撞见真相。

### F-10 `debug.readFile` 分页 —— **成立 ×2**

1. **页边界切 UTF-8 字符**：`offset`/`limit` 是**字节**窗口（`DebugRPCHandler.kt:1340` `readFileSegment`），`content` 直接 `String(bytes, Charsets.UTF_8)`（`:422`），而 `bytesRead = bytes.size`（`:428`）是调用方推进依据（同文件 `:1307` 的 KDoc 明写「caller returns bytesRead from what it actually…」）。中文日志每 1000 字节的翻页都会把一个 3 字节字符切成两半 ⇒ 每页末尾静默多出一个 U+FFFD，且**不可恢复**（下一字节从此错位）。
2. **文本判定两向都错**：`bytes.all { it in 0x09..0x0D || it in 0x20..0x7E || it and 0xFF > 0x7F }` —— 任何 ≥0x80 的字节都算文本（**二进制文件被当文本**，出一堆 U+FFFD），而 ESC(0x1B) 不在白名单（**带颜色的终端日志被判二进制**，返回 base64）。

### F-11 枚举与 label 的静默宽容 —— **成立 ×2**

1. **label**：`instancesCreate` 只 `.ifEmpty`（`:62`）—— 纯空白串 `"   "` 落库，Settings 渲染无名卡片；`instancesUpdate`（`:217`）**连 `ifEmpty` 都没有**，`{label: ""}` 直接写空。两面对 label 的处理还不如同文件的 `customBaseURL`（`isBlank` 判空）严格。
2. **枚举**：`parseStrategy` / `parseFallback` 的 `else ->` 塌缩到默认臂 ⇒ `{strategy:"random"}` / `{fallbackStrategy:"never"}` 返回 200 且**跑另一套策略**。这**不是**兼容兜底：同文件 `parseImageEndpointMode`（`:880-889`）对未知值就 `-32602` 并回显值域，写入集合层 `GroupsCollection.strategyField`/`fallbackStrategyField`（`:184-215`）也按值域拒绝 ⇒ 静默臂是 RPC 独有的宽容。

---

## 3. 修法（逐条，含形状约束）

### F-01（`HeadlessChatRunner.kt`）

- `cancel`：Main 帧内先 `withContext(IO) { dao.getSession(sessionId) != null }`，把结果作为 `persistedSession` 传给 `viewModelForCancel`；
- `viewModelForCancel`：顺序改为 **cached provider** → **`ChatViewModelStore.peekViewModel`（非破坏性，draft key `__new__<uuid>` 靠它）** → `if (!persistedSession) return null` → 才 `ownerFor` + `ViewModelProvider`；
- `compact` / `revertCompact`：进 VM 前补 `requireSendableSession(app(context), sessionId)`（纵深防御；两面 RPC 层已读行）。

关键约束：**不许把 `peekViewModel` 换成 `ownerFor`**——`ownerFor` 是「分配」，`peek` 是「读」，前者正是本条的病灶。

### F-02（`ProviderMutationMethods.kt`）

新增 `readGroupMemberIds(params, isKnownEntryId)`：

- `has`/`isNull` 缺席或 null ⇒ `emptyList()`（保留「null = 无成员」的既有语义）；
- 非数组 ⇒ `-32602 memberEntryIds must be an array of entry ids`；
- 元素非字符串 / 空串 ⇒ `-32602`（带下标）；
- `!isKnownEntryId(id)` ⇒ `-32602 Unknown model entry id: <id>`；
- 重复**原位去重**（保序，不丢信息）；
- 两个写入面都改为调它，**有效性谓词由各面以 lambda 传入**：`{ id -> SystemVoiceEntries.isKnownGroupMemberId(id, knownEntryIds) }`。

最后一点是**门逼出来的**（见 §4 的 C16）：`provider_rpc_symmetry_check` 的 C16 判据逐字要求 `groupsCreate` / `groupsUpdate` 的函数体内出现 `SystemVoiceEntries.isKnownGroupMemberId`（防止「从 `modelEntries` 自行推导」的第五次复发）。第一版我把判定整体搬进 helper —— 语义上更「一份」，但两个面里没了该字面，**C16 判红两条**。正解不是改门（`scripts/scan/**` 禁改），而是「结构共享、谓词留在面」。

### F-05（`ProviderMutationMethods.kt`）

`probePayloadIsModelsList(body)` 纯函数 + 探测面改判：

- `answered = resp.isSuccessful` 保留，`ok = answered && probePayloadIsModelsList(body)`；
- 判据：trim 掉 `\n\r\t空格\uFEFF` 后必须以 `{`/`[` 开头；`{data:[…]}` / `{models:[…]}` 为列表；顶层数组要求**每个元素是对象**；空体、HTML、无关 JSON 一律 false；
- 失败形状：HTTP 非 2xx ⇒ `ok:false` + `error:null`（**保持原契约**，`httpStatus` 已载原因）；HTTP 2xx 但体不是列表 ⇒ `ok:false` + `error:{code:"invalid_value", message:"HTTP 200 but the response is not a models list: <前 200 字>"}`；
- `httpStatus`/`latencyMs`/`reachableModelCount` 键全部保留（`providerProbeUrl` 未改）。

### F-10（`DebugRPCHandler.kt`）

- `utf8CompletePrefixLength(bytes)`：从尾部回退最多 3 个续字节找首字节，按序列长度决定「完整」与否；
- `looksLikeUtf8Text(bytes)`：NUL ⇒ 二进制；否则 `CodingErrorAction.REPORT` 严格解码，**从首个字符边界起解**（自定义 `offset` 落在字符中间时，前导续字节是「被跳过的字符残片」，不是二进制证据）；
- 调用点：`textEnd = utf8CompletePrefixLength(raw)`，**截短到 0 时退回整页**（`bytesRead` 是调用方推进量，回 0 会死循环 —— `limit` 小于单字符时才会命中，但那是无限循环，不能留）；文本走截短后的 `textCandidate`，二进制仍走未截短的 `raw`；`bytesRead = bytes.size` 自动反映实际推进，翻页不丢字节。
- 副作用（刻意）：严格解码后，含单个坏字节的文本页会整页转 base64（基线是 U+FFFD 文本），见 R4。

### F-11（`ProviderMutationMethods.kt`）

- `instancesCreate`：`optString("label","").trim().ifEmpty { -32602 }`；
- `instancesUpdate`：`has("label")` 时同样 trim + 拒空，否则 `current.label`（并抽成 `label` 局部量，让 `copy` 只有一处改动点）；
- `parseStrategy` / `parseFallback`：`when (val raw = s.trim())` 逐个列全值域，`else -> throw RPCException(-32602, "<key> must be a | b | c (got '<raw>')")`；两者由 `private` 松到 `internal` 以便（未来的）JVM 用例直接断言。

---

## 4. 验证

### 4.1 门（机械，`python3 scripts/scan/<gate>.py .`，cwd = 仓库根）

| 门 | rc | 读尾 |
|---|---|---|
| `provider_rpc_symmetry_check` | 0 | ALL GREEN（C16 group member predicate single-source） |
| `chat_session_id_binding_check` | 0 | all criteria satisfied |
| `parts_json_funnel_check` | 0 | PASS |
| `provider_write_rules_check` | 0 | ALL GREEN（W1 共享规则） |
| `models_provider_field_check` | 0 | OK（6 arms clean） |
| `entry_id_rename_integrity_check` | 0 | OK（4 arms, 0 violations） |
| `entry_composite_key_guard_check` | 0 | OK（10 arms clean） |
| `collection_copy_on_mutate_check` | 0 | OK（all mutate sites persist） |
| `session_provider_enabled_check` | 0 | GATE=GREEN |
| `gemini_base_version_check` | 0 | all normalizers restore `/v1beta` |
| `dao_loadmessages_check` | 0 | clean（0 direct `dao.loadMessages(` in prod） |
| `debug_leak_guard` | 0 | debug read path is minSdk-26 safe |
| `readonly_mount_write_guard` | 0 | OK |
| `auto_approve_bypass_check` | 0 | OK（all 5 criteria hold） |
| `offload_host_path_check` | 0 | 22 handler + 2 dispatcher |

**14 道相关门 rc=0**（未跑全量 scan，按告警要求）。首轮曾出现 `provider_rpc_symmetry_check` **rc=1（C16 两条）**，那是本支第一版 F-02 修法引入的形状回退，已按 §3 归位；另有四道门首轮 `rc=1/rc=2` 是 shared 目录被删导致的「文件找不到」，环境恢复后复跑全绿（不是代码问题，见 §8）。

### 4.2 静态自查

- 三个改动文件去掉注释/字符串后括号配平：`HeadlessChatRunner` / `ProviderMutationMethods` 0/0；`DebugRPCHandler` 读数 `(2, 2)` —— **基线同值**，是本脚本不处理 `${…}`/字符字面量的已知假阳性，非真实失衡。
- 新增符号的存在性逐一对齐：`ChatViewModelStore.peekViewModel`（`:102`）、`requireSendableSession`（`:113`）、`SystemVoiceEntries.isKnownGroupMemberId`（既有）、`RPCException`/`JSONArray` 既有 import、`Charsets.UTF_8`/`ByteBuffer`/`CodingErrorAction`（后两者本支新增 import）。

### 4.3 **未做**的验证（诚实清单）

- **无 JVM 探针**：`kotlinc` 与 `json-20231013.jar` 都随 `/var/minis/shared/work` 被删，重建装置（重新下载 80 MB 编译器）按告警要求**没做**。⇒ `probePayloadIsModelsList` / `utf8CompletePrefixLength` / `looksLikeUtf8Text` / `parseStrategy` 这些纯函数**没有可执行断言**，只有读码级推理。
- **无真机**：五条都落在 debug RPC 面，需要 `debug.*` 入口与端点；本轮未跑。
- **无测试文件**：本支 0 个新测试（与 R3 各支不同）。这是权衡后的取舍：`chat/*` 与 `ui/*` 侧的 Kotlin 测试都要 Robolectric/Android 类路径，沙箱内不具备；纯函数测试则依赖 4.3 第一点的编译器。

---

## 5. CI

| 项 | 读数 |
|---|---|
| workflow | `build-apk.yml`（`workflow_dispatch`, `ref=fix/r4-b12-rpc`） |
| run id | 见下方「CI 回填」 |
| head_sha | 见下方「CI 回填」（应为 `e65a8724`，逐字符核对） |

**CI 回填**：dispatch 后回填本表（结论 / 耗时 / 27 步非 success 计数 / `Summary: N passed, 0 failed` / `ALL … CASES PASS` / `^e: ` 计数 / `head_sha` 逐字符比对）。

---

## 6. 残留与登记（每条带触发条件 + 复活方案）

| # | 残留 | 触发条件 | 复活方案 |
|---|---|---|---|
| R1 | F-01 `cancel` 的行读使「进程重建后的 draft 会话」`cancel` 返回 `false`（此时 store 里也没有 VM，本来无可取消） | 跨进程重启后对 `__new__<uuid>` 草稿调 `chat.session.cancel` | 判据放宽为「行存在 **或** store 有 resident」——但那就回到本条的病灶，需先给 draft 一个显式注册表 |
| R2 | F-02 从「静默丢」改为 `-32602`（fail-closed 行为变化） | 外部脚本/自动化依赖「传了不认识 id 也算成功」 | 保留错误码、加 `strictMemberIds:false` 兼容开关（默认 true） |
| R3 | F-05 判据是白名单形状：`200 + {"object":"list"}`（无 data）或无包装的 `{"id":…}` 单条都判失败 | 某中转的 `/models` 返回非标准形状 | 在 `probePayloadIsModelsList` 里扩形状（唯一改动点，纯函数可测） |
| R4 | F-10 严格解码 ⇒ 含单个坏字节的文本页整页转 base64（基线是 U+FFFD） | 真机上读到坏字节日志 | 改成「坏字节比例阈值」判据，或对已知文本扩展名（`.log/.txt/.md`）直接强制 text |
| R5 | F-10 只保证头/尾落在字符边界；页内中间若有坏字节，仍会整页判二进制 | 同上 | 同 R4 |
| R6 | 五条全链路**真机未验** | 需要真机时 | 用 `debug.readFile` 对中文日志按 `limit=1000` 翻页两次比对；`provider.instances.test` 指向首页 URL；`groups.update` 传重复 id 后进组详情页 |
| R7 | 本报告的「报告说法」是笔记重述（任务书原文随事故丢失） | 需要逐字比对时 | 主会话若留有任务书副本，按原文校对 §2 |

---

## 7. 改动面

```
src/android/app/src/main/java/com/rikkaminis/app/debug/DebugRPCHandler.kt          +80 −5
src/android/app/src/main/java/com/rikkaminis/app/debug/HeadlessChatRunner.kt       +38 −2
src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderMutationMethods.kt +165 −38
```

禁改域**全部零触碰**（`git diff --name-only 2103729f HEAD` 只含上面三个文件）：`scripts/scan/**`、`scan.sh`、`test_scan.py`、`src/**/ui/**`、`backup/**`、`sandbox/**`、`strings.xml`。

提交粒度：`83429223`（五条主体，含 F-02 首版）→ `653b79ee`（F-02 按 C16 归位）→ `c0d3f0d6`（F-10 头部边界）→ `e65a8724`（F-10 活性守卫）。后三笔都是**自查发现的自身缺陷**，不是任务书条目。

---

## 8. 执行记录与判读纪律（事故相关，必读）

1. **任务书与装置随事故丢失**：`/var/minis/shared/work` 在 08:10 与 08:1x 两次被清空，任务书（`FIX-R4-R.md` / `FIX-R4-COMMON.md`）、`jvm-libs`、`kotlin-home`、原仓库工作副本、以及首次建好的 worktree 全部消失。本报告**按告警要求不重建装置**，八节结构按 R3 各支报告的既有模板重建（概览 / 先证 / 修法 / 验证 / CI / 残留 / 改动面 / 执行记录），**不是**逐字照抄 COMMON §3。
2. **工作副本迁移**：改为 `/var/minis/workspace/b12r/`（会话私有，未再写 `/var/minis/shared`）。原 worktree 的 15 处编辑用 `patch_r4r.py`（exact-match + assert，任一处 anchor 计数 ≠ 1 即中止）在干净 clone 上重放，逐条 `ok` 后才继续；这比「重新手改一遍」少一次漂移机会。
3. **代码安全网**：首笔提交后立刻 push（`83429223` 上远端），此后每完成一条即 commit + push；worktree 另 tar 到 `/var/minis/mounts/背景包/_shared/r4-rescue/fix-r4-b12-rpc.tar.gz`。
4. **门是唯一可靠的自动判据**（本支实证）：`provider_rpc_symmetry_check` C16 抓到了我「更优雅但破形状」的重构。教训与 R3 各支一致 —— **改形状敏感面之前先想它被哪道门的函数体判据钉住**；本轮的正确动作是改修法（谓词留在面），不是改门。
5. **自查抓到的两处自身缺陷**（`c0d3f0d6` / `e65a8724`）：严格解码必须以字符边界起解（自定义 `offset` 场景）；截短不得把推进量压到 0（`limit` < 单字符会死循环）。两条都是「修一个 bug 引入的另一类 bug」，靠写完自己读一遍 diff 抓到，没有装置可依赖 —— 这是本支最薄的一环。
