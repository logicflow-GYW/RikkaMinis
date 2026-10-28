# FIX-META1028-W1 —— 8 条「门盲」见证固化为可判红判据

- **分支** `fix/meta1028-w1-backup-gates`，基线 main `bfb5b0e6`
- **改动面**：3 个门脚本，`+1072 / −10`；**生产 `.kt` 0 行**，`scripts/scan/{scan.sh, test_scan.py, _kotlin_*}` **零触碰**
- **新增判据臂**：`scheduled_store_release_guard` 63→**72**（+9）· `backup_streaming_export_check` 36→**47**（+11）· `discarded_return_guard` 77→**93**（+16），共 **+36 臂**
- **结论**：8 条见证全部**在自己的新门上变红**（8/8），合法拼写仍绿（每条都有「合法拼写臂」），锚点漂移一律 fail-closed；真树三面 rc=0

---

## 1. 这批见证的共同形态（为什么上一版门看不见）

meta-audit-1028 的机械验证（`meta-audit-1028/witness-results.md`）判了 8 条 BLIND。逐条读下来，它们不是「门写错了」，而是门回答的问题比缺陷低一维：

| 门原本证明的事 | 缺陷实际所在 |
|---|---|
| `writeLocked(… copy(running = null))` 存在 | 这个写**在决策拒绝时仍然可达** |
| `resolveWebDavRestore(` 被调用 | 第二个实参是 `null` |
| `pruneAutoBackups(` 被调用 | `protectName = null` |
| `chatTruncationNotes(` / `conversionNotes(` 被调用 | 返回值**语句位丢弃** |
| `planImageElision(` / `tryEmit(plan)` 存在 | 返回的是入参不是裁剪结果 |
| 目的地表达式**含** sanitized 变量 | 同时含 RAW 变量（回退链让 RAW 生效） |
| `while (… start++ …)` 存在 | 循环**条件恒假**，永远不前进 |
| `buildLlmMessagesFromParsed(` 存在 | 第三个实参没交 |

一句话：**存在性判据挡不住"存在但无效"**。本支把每条改写成**可达性 / 来源 / 唯一性**判据，并给每条配上"合法拼写臂"（必须仍绿）与"锚点漂移臂"（必须 fail-closed），避免下一次重构用一句 re-point 之外的方式静默绕过。

---

## 2. 逐条修法

### W-B00-01（P0）· 拒绝的恢复仍然落盘 —— `scheduled_store_release_guard.py` C5

**现象（报告原话摘要）**：`ScheduledTasksStore.importDocument` 里 `decision.applied == false`（空备份 / 不支持的版本 / 全部行损坏）时仍然 `writeLocked(context, parsed.copy(running = null))`；通知文案说"已保留本机 N 个任务"，实际本机任务已清零。

**机制**：`ScheduledTasksRestorePlan.decide` 存在的**唯一理由**就是区分"备份说零条"与"保留本机"。决策被算出来、被打印、被写进 outcome，唯独不约束写盘。这是「决策被计算但未接线」的极端形态：**所有拼写都在**（`decide(`、`applied`、`copy(running = null)`、锁层级），所以 6 道读该文件的判据全绿。

**判据（双向可达性，不是 token）**：
- 每个 `writeLocked(` 调用，若实参含恢复标记 `copy(running = null)`：
  - **`applied == false` 可达 ⇒ 违规**（拒绝却写盘）
  - **`applied == true` 不可达 ⇒ 违规**（接受却永不落盘，反向缺陷）
- 布尔守卫原子替换后折叠：`_kotlin_ast.const_bool` 做代数（`false && x`、`!(false)`、`applied || !applied` 折叠为恒真）。
  为什么不用共享的 `path_feasible`：它只认整数域，布尔符号恒为 unknown，`if (decision.applied)` 会被误判成"未守卫"。
- **别名绑定**（`val go = decision.applied`）与**提前 return 拒绝**（`if (!applied) return …` 后裸写）都判绿——前者靠别名表，后者靠 `_refused_path_exits`（前面有 `applied==false` 时必然退出的 if 分支）。这两种是真实重构会写出来的形态，门必须认。
- 文件里**没有** `ScheduledTasksRestorePlan` ⇒ 判据不适用（`test_scan.py` 的最小夹具复用同一文件，且它没有决策概念）；文件里有 planner 但找不到 `decide(` ⇒ **fail-closed 违规**。

**见证变红**：`ScheduledTasksStore.kt:107` 报
`the restore document is written even when the decision REFUSED (applied == false is reachable at this writeLocked)`。

### W-B00-02（P1）· 无密钥备份恢复会把 WebDAV 密码清掉 —— `backup_streaming_export_check.py` arm9

**机制**：`resolveWebDavRestore(backup, existing)` 第三态（两边都没有密码）是"全新安装"分支，构造 `password = ""` 的 config；`WebDavConfigStore.save()` 把空密码当**显式清除**（`remove(KEY_PASSWORD)`）。传 `null` 顶替"本机现有配置" ⇒ 每次 `includeSecrets = false` 的自动备份（= 全部夜间备份）恢复后，本地 WebDAV 密码被抹掉，之后每一轮 401。

**判据**：实参个数 ≥2；第二实参不得出现 `null` 字面量；**并且**第二实参的来源必须包含调用方自己传进来的形参标识符（证明"本机配置流进来"，而不是在调用点凭空造）。重命名（`existingWebDavConfig` → `currentWebDav`）有专门一条臂证明仍绿——判据钉的是数据流不是拼写。

### W-B00-03（P1）· 残缺恢复显示"0 警告成功" —— `discarded_return_guard.py` C-W1

**机制**：`.minisbak` 丢分片 / JSONL 尾行坏 / 技能转换失败 / 语料预算裁剪，全部由 `chatTruncationNotes` 与 `conversionNotes` 算出，而 `ImportResult.skipped` 是恢复完成界面**唯一**的告警通道。语句位调用 = 返回值丢弃 ⇒ 一个 4 条消息缺 2 条的恢复，界面写"成功，0 条警告"，用户随即删掉原备份。

**判据**：在 `ConfigBackup.import` 内，两个 producer 的每次调用必须"被消费"：
- **语句位**（行首起无内容且前一个非空字符不是 `(`/`,`）⇒ 违规；
- 同行含 `skipped`、或外层调用头（`skipped.addAll`）含 `skipped`、或结果绑到变量而该变量后续进入 `skipped` ⇒ 通过；
- producer **整体消失**也报违规（告警通道死了，不能静默）。

实现踩到并修掉的一个坑：一开始用 `stmt_spans` 判定"语句位"，而 `stmt_spans` 按花括号深度切分——调用若在 `?.let { tr -> … }` 里，整段 let 是**一个** statement，于是同段里的 `skipped.addAll(conversionNotes(...))` 让缺陷看起来"已消费"（第一次变异验证因此假绿）。改成**行级 + 外层调用头**两级判定后变红。

### W-B00-04（P1）· 时钟回拨会把刚上传的备份删掉 —— `backup_streaming_export_check.py` arm10

**机制**：`pruneAutoBackups(cfg, keep, protectName)` 按**名字排序**删超出 `keep` 的文件，名字是时间戳。时钟回拨（NTP 校正 / 手动改 / 时区碰撞）可让本次刚 PUT 的文件排到 `keep` 之后 ⇒ 同一秒内 PUT 完立刻 DELETE，而 `KEY_LAST_RUN` 仍然记"今天已备份"。`protectName` 是唯一防线，`null` 等于 FIX-1028 / B-12 重开。

**判据**：每次 `pruneAutoBackups(` 必须带 `protectName`，值不含 `null`，且**必须是被保护地引用最近一次 `WebDavSync.backupAuto(` 的绑定变量**（`_bind_target` 从赋值语句反查，不硬编码 `remoteName`）。

### W-B00-10（P1）· portal 响应防火墙的跳空循环可以恒假 —— `backup_streaming_export_check.py` arm11

**机制**：`assertNotPortalBody` 靠首字节判 HTML（`<`）还是 JSON（`{`/`[`），BOM 分支与**前导空白跳过循环**让它能把 `"\n<!DOCTYPE html>"` / `"  <html>"` 也判出来。循环条件恒假 ⇒ `start` 停在 0 ⇒ 这类页面被判"不是 HTML" ⇒ PUT/DELETE 在 captive portal 上"成功"，GET 把登录页当备份存下来。该文件此前**没有任何门读过**；单测只喂无空白前缀的 JSON（两种实现给同一个答案），所以恒假循环完全隐形。

**判据**：函数体内必须存在**可达**的前导空白跳过：一个 `while`，其循环体同时含空白字节判定（`0x20/0x0A/0x0D/0x09/isWhitespace`）与索引步进，且条件不折叠为常量假、步进 `path_feasible`；或函数式等价（`dropWhile/indexOfFirst/indexOf/firstOrNull`）。锚点漂移 / 括号不配平 ⇒ fail-closed。

### W-B00-11（P1）· 图像预算算完没返回 —— `discarded_return_guard.py` C-W2

**机制**：`applyRequestImageBudget` 扫出超预算图、落盘、`planImageElision` 算出裁剪表、`tryEmit(plan)` 让 UI 说"旧图已折叠"、日志打 `dropped=X/Y` —— 然后 `return messages` 把**入参原表**交回。三家 provider 把原图全量编码进请求体 ⇒ 413 / 成本失控，而界面显示"已折叠"。

**判据**：`applyRequestImageBudget` 的 `planImageElision(` 绑定变量 `X` 必须出现在**其后每条 `return`** 的来源里；`X` 不存在（elision 被删）或算完不返回 ⇒ 违规。`elision` 之前的早退（`if (messages.isEmpty()) return messages`、`if (!plan.mutated) return messages`）不算。

### W-B00-12（P1）· 消毒过的时间戳被 RAW 名顶掉 —— `backup_streaming_export_check.py` arm3 升级

**机制**：`val stamp = sanitizeAutoBackupStamp(rawStamp) ?: return` 之后，目的地写成 `fileName(PREFIX, rawStamp.ifEmpty { stamp })`：`stamp` 满足"目的地由 sanitized 值构造"的**含有性**检查，而 `ifEmpty` 取的是 RAW——在 `stamp == null` 已被排除的世界里 RAW 恒非空 ⇒ **常量选 RAW**。remote 端可控制的文件名直接变成 `filesDir/backup-autos/` 下的写入路径。

**判据**：把 sanitizer 的**输入表达式**里的数据型标识符（排除调用名与全大写常量）收集为 `raw_atoms`；任一 `File(` 实参来源含 `raw_atoms` ⇒ 违规。旧检查（"必须含 sanitized 变量"）保留——两者一起才既防丢弃也防回退链。

### W-04（P1）· 会话重载批量路径不交降采样器 —— `discarded_return_guard.py` C-W3

**机制**：`buildLlmMessagesFromParsed(parsed, dir, resizeForInference)` 的第三参决定历史原图要不要降到 2000px。它**曾经**有默认值 `{ _, _ -> null }`（= 不降采样），于是漏传既不报错也不红门：单测自己注入 lambda（只测到它注入的那个），`loadSession` 批量恢复因此每轮重发数 MB 原图。

**判据（两侧都钉）**：声明侧 `resizeForInference` 形参必须存在且**不带默认值**（带默认 = 新调用点可静默退出）；调用侧每个 `buildLlmMessagesFromParsed(` 必须交（位置第三参或具名），且不得交常量 `{ _, _ -> null }` / `null`。

**诚实修正（相对报告）**：W-04 的见证补丁**在当前基线上已不编译** —— `[W-1027 W-04]` 早前就把默认值删了（`ChatStreamToolHelpers.kt` 里 KDoc 写着 "REQUIRED, no default"），所以"漏传第三参"现在是编译错误。本支的价值不在"抓这个补丁"（变异臂里它确实被抓住，因为门是纯文本判定），而在**把编译期偶然保护钉成结构判据**：将来若有人为了编译方便把默认值加回来（这正是原始缺陷的成因），声明侧判据立刻红。

---

## 3. 自测臂清单（+36）

**scheduled_store_release_guard（+9，C5 family，收尾打印 `SCHEDULED_RESTORE_SELFTEST=PASS`）**

| 臂 | 期望 |
|---|---|
| clean（只在 applied 时写） | 0 |
| **W-B00-01 恒真守卫**（`applied \|\| !applied`） | 1 · REFUSED |
| 完全无守卫 | 1 · REFUSED |
| 守卫取反（恰在拒绝时写） | 2 · REFUSED（两个方向同时坏） |
| 别名绑定 `val go = decision.applied` | 0（合法拼写） |
| 提前 return 拒绝 + 裸写 | 0（合法拼写） |
| 接受时也不可达（恢复永不落地） | 1 · never restored |
| planner 还在但 `decide(` 没了 | 1 · fail-closed |
| 该 store 没有恢复决策概念 | 0（不适用） |

**backup_streaming_export_check（+11 fixture 臂，arm9/10/11 + arm3 升级）**

| 臂 | 期望 |
|---|---|
| compliant restore（合并本机配置） | green |
| **renamed local var**（`currentWebDav`） | green（钉数据流不钉拼写） |
| **W-B00-02 `null`** | red · W-B00-02 |
| W-B00-02 实参缺失 | red · without the local config |
| **W-B00-04 `protectName = null`** | red · W-B00-04 |
| W-B00-04 无 `protectName` | red · without `protectName` |
| W-B00-04 保护了别的文件 | red · protects ` |
| **W-B00-10 恒假跳过循环** | red · DEAD-GUARDED |
| W-B00-10 跳过整段删除 | red · no reachable leading-whitespace skip |
| W-B00-10 函数式等价（`indexOfFirst`） | green |
| **W-B00-12 `ifEmpty` 回退到 RAW** | red · UNsanitized |

（arm1..arm8 的 36 条原有臂全部保留并通过；`COMPLIANT_AUTO` 夹具补上了 `protectName = remoteName` 的**正解形态**，`VIOLATION_AUTO_NO_PUSH` 的替换串随之更新。）

**discarded_return_guard（+16 臂，wiring section C-W1/C-W2/C-W3，`93 arms`）**

| 组 | 臂 |
|---|---|
| C-W1（6） | clean · **语句位丢弃** · 绑到局部但从不进 `skipped` · 绑到局部后 flush（合法） · producer 全删 · `import` 改名（锚点漂移 fail-closed） |
| C-W2（4） | clean · **返回入参原表** · elision 前早退（合法） · elision 被删 |
| C-W3（6） | clean · **漏传第三参** · 传 no-op lambda · 具名实参（合法） · 声明带默认值 · 缺文件（fail-closed） |

---

## 4. 见证验证记录（8/8 变红，文件已恢复并逐字节校验）

| 见证 | 门 | rc | 是否恢复 |
|---|---|---|---|
| W-B00-01 | scheduled_store_release_guard | 1 | RESTORED |
| W-B00-02 | backup_streaming_export_check | 1 | RESTORED |
| W-B00-03 | discarded_return_guard | 1 | RESTORED |
| W-B00-04 | backup_streaming_export_check | 1 | RESTORED |
| W-B00-10 | backup_streaming_export_check | 1 | RESTORED |
| W-B00-11 | discarded_return_guard | 1 | RESTORED |
| W-B00-12 | backup_streaming_export_check | 1 | RESTORED |
| W-04 | discarded_return_guard | 1 | RESTORED |

脚本：`/tmp/mutate_w1.py`（逐条 patch → 跑门 → 断言 rc≠0 → 还原 → sha256 比对）。
过程中 W-B00-03 第一次**假绿**（`stmt_spans` 的花括号深度问题，见 §2），修判据后复跑变红——这条本身是"变异验证必须真跑"的又一次实证。

---

## 5. 真树与邻门读数

- 三改正门真树：`scheduled_store_release_guard` rc=0（打印 `C5 … 1 restore write(s), none reachable on the applied == false path`）· `backup_streaming_export_check` rc=0（`arm1..arm11 green`）· `discarded_return_guard` rc=0（wiring section green）。
- `git status --short` 只有 3 个门脚本，**没有**生产 `.kt` 改动。
- 邻门 `--selftest`（8 道）：`backup_remap_rollup_check` / `openminis_compat_field_parity_check` / `parts_json_funnel_check` / `tool_result_image_branch_check` / `filepreview_print_bounded_check` / `chat_ui_interaction_check` / `storage_budget_check` / `scheduled_store_release_guard` —— **8/8 rc=0**。
- `test_scan.py::test_scheduled_store_release`（8 条断言）全 ✅ —— 说明 C5 的「无 planner ⇒ 不适用」逃逸没有破坏原有最小夹具。

---

## 6. 残留（三件套：位置 / 触发条件 / 为什么暂不做）

- **R1 · arm9 只证明"本机配置流进来"，不证明它是最新的。** 位置：`backup_streaming_export_check.check_arm9`。触发：恢复路径改成在 `ConfigBackup` 内重建 config。暂不做：需要跨函数值流（谁 load、何时 load），超出本支半径，且现在的写法只有一处调用点。
- **R2 · arm11 只钉 `assertNotPortalBody` 的前导空白跳过。** 位置：`check_arm11`。触发：出现**第二个**响应防火墙（新的 WebDAV 动词或新的协议客户端）。暂不做：本支只覆盖已被见证证伪的那一处；第二条防火墙应带自己的锚点。
- **R3 · C-W3 只钉 `buildLlmMessagesFromParsed` 一个入口**，`buildSingleLlmMessage` 的默认值路径未钉。位置：`scan_inference_resizer_wiring`。触发：单消息路径被新调用点复用（历史消息编辑 / 转发）。暂不做：当前唯一调用方在批量路径内，且该函数无默认值。
- **R4 · W-B00-04 只钉了远端轮转**；本地轮转（`AutoBackupLocalStore` 的 `rotate`）没有同款 `protect`（= 审计报告里的"母型 2"）。位置：`backup/AutoBackupLocalStore.kt`。触发：任何一次本地 rotate 撞上时钟回拨。暂不做：本支只做门，本地轮转是否该有 protect 是产品语义决定（本地保留 N 份是明确意图），需先拍板。
- **R5 · backup 门三条新臂的消息不带行号**（以调用点描述代替）。位置：`check_arm9/10/11`。触发：需要精确到行定位时。暂不做：`run_fixture` 断言按消息 token 钉住，加行号需重算 `run_fixture` 的比较口径，收益是 grep 少一步。
- **R6 · 真机行为未验**（沙箱无 Android 运行时）：JSONL 分片缺失的实际导入提示、clock rollback 下的远端删除、captive portal 上的 PUT/DELETE，均只有源码级判据与云端编译/单测/门证据。

---

## 7. 未做（明确不在本支）

- 其它 W-B00 见证（05/06/07/08/09）分属他支；本支只做登记给我的 8 条。
- 任何生产代码修复：本支按任务书只动门。
- `scan.sh` / `test_scan.py` / `_kotlin_*` 一律未碰 —— 新增判据全部走门内 selftest，避免与并行支撞公共资源。

---

## 8. 复现命令

```
# 三改正门
python3 scripts/scan/scheduled_store_release_guard.py .
python3 scripts/scan/backup_streaming_export_check.py .
python3 scripts/scan/discarded_return_guard.py .

# 自测（含全部新臂）
python3 scripts/scan/scheduled_store_release_guard.py --selftest   # 72 ✅ / SCHEDULED_RESTORE_SELFTEST=PASS
python3 scripts/scan/backup_streaming_export_check.py --selftest   # 47 臂 / BACKUP_STREAMING_EXPORT_SELFTEST=PASS
python3 scripts/scan/discarded_return_guard.py --self-test         # 93 arms / DISCARDED_RETURN_GUARD_SELFTEST=PASS

# 8 条见证的变异矩阵（变红 → 还原 → sha256 比对）
python3 /tmp/mutate_w1.py
```

---

## 9. ROT / fail-closed 一览（防止判据被"删掉就当过"）

| 判据 | 锚点缺失时的行为 |
|---|---|
| C5 | 文件里有 planner 但找不到 `decide(` ⇒ **违规**；文件里没有 planner ⇒ 不适用（明确打印） |
| arm9 | `resolveWebDavRestore(` 消失 ⇒ 违规（提示 re-point，不许删） |
| arm10 | `pruneAutoBackups(` 消失 ⇒ 违规（远端轮转若无界增长也是缺陷） |
| arm11 | 函数 / 块体 / 括号任一不成立 ⇒ 违规 |
| C-W1 | `import` 函数找不到 ⇒ 违规；producer 消失 ⇒ 违规 |
| C-W2 | 函数找不到 / `planImageElision(` 消失 ⇒ 违规 |
| C-W3 | 两个文件缺一 ⇒ 违规；声明找不到 ⇒ 违规 |

以上均已在自测臂里固定（每条至少一条 fail-closed 臂或"不适用"臂）。
