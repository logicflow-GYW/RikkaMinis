# FIX-PASTE-FOLD-NOISE — 粘贴折叠：去掉输入框里的标记 + id 不再单调递增

分支 `fix/paste-fold-ux`（基于 main `be4ad67e`，即 `feat/paste-fold-marker` 合入后）。

## 用户报告（真机，2026-10-31）

> 「[Pasted#1]，[Pasted#2]，第1次出现的标签是第1个，删掉之后再次放进去会出现第2个，还有就是这个标签本身就不该出现的吧，在我看来又多余，又碍眼。」

两条诉求：

1. **标记不该出现在输入框里**——一个用户读不出、也不该读的字面串，占着光标位。
2. **编号不该单调递增**——「删掉 #1 再粘一次变 #2」看起来像 app 在给用户的粘贴次数记账。

## 根因

首期实现里 `[Pasted#N]` 是唯一的引用点：文本里没有标记 ⇒ chip 不显示、发送时也无从还原。于是

- 任何一次长粘贴都往文本里写一个字面标记，**包括清空输入框后粘贴**（最常见场景：贴一段材料，再打一句「分析一下」）。此时文本框只剩 `[Pasted#1]`，标记除了占位没有任何信息量。
- id 分配是 `nextPasteId++`，只增不减，理由是「回收 id 会让残留的旧标记展开成新内容」。这个顾虑对**屏幕上还存在的字面标记**成立，对「已删除 chip 且标记已同删」的情形不成立——那种情况 id 其实完全空闲。

## 改动

### ① 空框粘贴 → 标记不进文本（`PastedText.inlineMarker`）

条目分两种形态：

| 形态 | 何时产生 | 文本里有什么 | 发送时 |
|---|---|---|---|
| inline（`inlineMarker = true`） | 粘贴落在**已有文本**中间 | `[Pasted#N]` 记住位置 | 原位展开 |
| detached（`inlineMarker = false`） | 粘贴时输入框**为空** | 什么都没有，框保持空 | 正文（用户后打的字）在前，块按 id 序以空行分隔附加在后 |

- `foldLongPasteIfNeeded(old, new, stash: (String, Boolean) -> String?)`：`old.text.isEmpty()` ⇒ `inlineMarker = false`，stash 返回 null，插入段整段离开文本，caret 回 0。
- `appendUnlinkedPastes(text, buffer)`（纯函数）：detached 块按 id（= 粘贴顺序）附加，正文为空时不加前导空行。
- chip 行过滤：`!it.inlineMarker || inputFieldValue.text.contains(it.placeholder)` —— detached 条目常显（与附件 chip 同寿命），inline 条目仍跟随标记。
- 首期 `fail-closed` 守卫不变：恢复草稿里展不开的标记仍禁发（detached 条目没有标记，恢复后自然消失，见「取舍」）。

### ② id 从最小空闲值分配（`nextFreePasteId`）

- `used = buffer 里的 id ∪ 屏幕上字面标记的 id ∪ 恢复草稿的孤儿 id`，取最小未用正整数。
- 删掉 #1（chip 与标记同删）后再粘贴 ⇒ 又是 #1。
- 恢复草稿的孤儿标记仍占着它的 id ⇒ 新粘贴不会接管它（否则旧标记会展开成新内容）。

## 取舍（刻意，登记）

1. **detached 块的顺序是固定规则**：正文在前、块在后。没有位置可记时只能定一个确定规则；「指令 + 材料」也是消息带附件时的既有形状。代价：若用户先在空框粘贴 A、打字、再在有字时粘贴 B，发送结果是「字 B 全文 \n\n A 全文」而非「A 字 B」。混合场景罕见，登记不修。
2. **detached 块的进程死亡窗口**：buffer 是内存态（首期拍板不持久化），标记型条目死亡后由禁发守卫兜住（用户看得见）；detached 条目没有标记，死亡后静默消失。窗口 = 一次未发送的草稿，换取的是「日常粘贴不再有噪声字面串」。绕法是二期 buffer 持久化（触发四处同步），未做。
3. **非空框粘贴仍显示标记**：那是用户主动在指定位置插入，标记是位置的唯一载体；删掉它位置就丢了。

## 验证

| 面 | 证据 |
|---|---|
| 纯逻辑（JVM，真源逐字切片 + stub 顶 `TextRange`/`TextFieldValue`） | **34/34 通过**（新增：空框不写标记、非空框仍写、detached 追加顺序、空正文无前导分隔、无 detached 不改文本、id 最小空闲复用、屏幕上标记 id 预留） |
| 变异臂 | `allowInlineMarker = oldText.isNotEmpty()` → `true` ⇒ 「空框粘贴不写标记」当场红（34 run / 1 failure） |
| 形状锚点（读源码） | **11/11 通过**（expand 消费判定、chip 过滤条件、id 预留两处均更新到新形态） |
| 本地门 | `scripts/scan/scan.sh` **113 passed, 0 failed** |
| 分支 CI | run `41208830092` **success** @ `b8adf823`（head_sha 与本地逐字符一致；113/0、`ALL 349 CASES PASS`、真 FAILED 0、`e:` 0、Paste 相关 90 条 PASSED） |

## 变更面

5 文件 +311/−72：`PastedTextBuffer.kt`（+131/−）、`ChatViewModel.kt`（+92/−）、`ChatInputArea.kt`（+22/−）、两个测试文件。

未触碰：`ChatScreen.kt`、`scripts/scan/**`、i18n（无新文案）。
