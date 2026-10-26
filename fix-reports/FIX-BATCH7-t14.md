# FIX-BATCH7-t14 — `mirror_vm_thread_check.py` + `file_uri_parse_guard_check.py`

- 支线：`fix/b7-mirrorvm-fileuri`
- 基线：`base/batch5-shared` @ `e145ec961232c7898644346ff4bc0c3f70980369`
  （= main `6ac77c6b2` + 共享原语 `scripts/scan/_kotlin_valueflow.py`）
- 门提交：`57a86390a`
- 改动面：**2 个门脚本**（`mirror_vm_thread_check.py` +859/−?、`file_uri_parse_guard_check.py` +873/−?，合计 +1035/−697）
  —— **生产 `.kt` 0 行**、`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` 零触碰、门 112 / 夹具 344 不变。
- 见证：12 条（mirror 11-1…11-6，file_uri 01-1…01-6；其中 11-6 是本波前就被抓的对照臂，修完必须仍然被抓）。

---

## ① 门与轴

| 门 | 见证数 | 盲见证 | 轴分布 |
|---|---|---|---|
| `mirror_vm_thread_check.py` | 6 | 5（11-1…11-5） | `parser-death` ×1（11-1）· `dataflow` ×3（11-2/11-4/11-5）· `family-hole` ×1（11-3）· 对照 1（11-6，`family-hole`） |
| `file_uri_parse_guard_check.py` | 6 | 6（01-1…01-6） | `dataflow` ×2（01-1/01-2 兼 `spelling`）· `order` ×1（01-3）· `parser-death` ×1（01-4）· `family-hole` ×2（01-5/01-6） |

两门同一病灶，且是本项目已固化的那条：

> **判据锚「某个 token / 某条拼写出现在文件里」，而不是「这个 token 背后的决定真的发生了」。**

镜门三个面（`parser-death`/`dataflow`/`family-hole`）、文件门四个面（`dataflow`/`order`/`parser-death`/`family-hole`）各自留了同一条缝：

- 自写词法投影（`code_only()` 只认行首 `//`、`strip_comments()` 不认三引号裸串）→ 注释/字符串里的**死代码复活成"存活调用"**；
- 判据只查「名字在块里」（`"selectedMirrorId[MirrorCategory.ALPINE]" in snap`，连 `=` 都没有）→ 块内两句哑读 + 块外真写照样绿；
- 恒假/恒真守卫靠**单向正则白名单**（`&&\s*false` 有、`false\s*&&` 没有；`true\s*\|\|` 有、`\|\|\s*true` 没有）→ 交换左右操作数即穿透；
- 值被丢弃靠**形状黑名单**（只查 `.also`/`.apply`）→ 换 `.let { emptySet() }` 即穿透；只查「紧贴调用右括号后 80 字符」→ 截断动作下移到变量消费点即穿透；
- `[^()]*` 这类**非配平正则** → 嵌套一层 `Uri.parse(stripped.trim()).path` 就失配为 0 命中；
- 判据标题写「cut **before** decode」，实现只做 `in frm` 的**无序与运算** → 顺序反了也绿。

---

## ② 见证原文（file / find / replace / why_gate_green）

### mirror_vm_thread_check.py

**11-1 — `parser-death` — `arm2 autoDetectOnceIfNeeded calls applyMirror off the main thread`**

- file：`src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/MirrorSettingsScreen.kt`
- find：`            for (cat in toApply) applyMirror(context, cat)`
- replace：`            for (cat in toApply) Unit // applyMirror(context, cat)`
- why_gate_green：`code_only()` 逐行 `s = line.strip(); if s.startswith("//"): continue` —— 只删**行首**注释。行内尾随 `//` 被原样保留，`arm2` 在 `detect` 里 `find` 到 `applyMirror(`，`dead_context()` 又看不到恒假外层，判 PASS。真实循环体是 `Unit`：自动选中的镜像永不写入容器配置。

**11-2 — `dataflow` — `arm4 retryApkWorldWithMirrorFallback wraps the ALPINE writes in a mutable snapshot`**

- file：同上
- find：
  ```
              Snapshot.withMutableSnapshot {
                  selectedMirrorId[MirrorCategory.ALPINE] = next.id
                  useCustomMirror[MirrorCategory.ALPINE] = true
              }
  ```
- replace：
  ```
              Snapshot.withMutableSnapshot {
                  val _unusedA = selectedMirrorId[MirrorCategory.ALPINE]
                  val _unusedB = useCustomMirror[MirrorCategory.ALPINE]
              }
              selectedMirrorId[MirrorCategory.ALPINE] = next.id
              useCustomMirror[MirrorCategory.ALPINE] = true
  ```
- why_gate_green：arm1（`ensureLoaded`）有 `stray = re.search(...)` 检查块外裸写；60 行之隔的 arm4 只查 `"selectedMirrorId[MirrorCategory.ALPINE]" in snap` 与 `"useCustomMirror[MirrorCategory.ALPINE]" in snap` —— 两句**哑读**完全满足，块内没有写、块外写了两遍也绿。（wide-1024 只加固了 arm1，漏了同族 arm4。）后果：启动路径在 IO 线程非原子写两个 `SnapshotStateMap`，主线程可能读到「镜像已切换、`useCustomMirror` 仍为 false」的撕裂态。

**11-3 — `family-hole` — `arm7 settlement is ownership-checked before it writes`**

- file：同上
- find：`                if (autoDetectJob !== self) return@withContext`
- replace：`                if (false && autoDetectJob !== self) return@withContext`
- why_gate_green：`is_constant_false()` 依赖 `DEAD_IF_RE = r"\b(?:if|while)\s*\(\s*(?:false|true)\s*\)"`（要求 `false` 紧贴 `)`）与 `DEAD_GUARD_RE = r"&&\s*false\b|\|\|\s*true\b"`（要求常量在**右侧**）。`false && g` 两条都失配 ⇒ 判「守卫有效」。后果：被取消的旧测速协程照样结算，覆盖用户刚手选的镜像并落盘。

**11-4 — `dataflow` — `arm5 the auto-detect settlement preserves a user selection`**

- file：同上
- find：`                val alreadySelected = selectedMirrorId.keys.toSet()`
- replace：`                val alreadySelected = selectedMirrorId.keys.toSet().let { emptySet<MirrorCategory>() }`
- why_gate_green：`in_discarded_lambda()` 只认包裹读取的块头以 `.also`/`.apply` 结尾。本形状里 `selectedMirrorId.keys.toSet()` 在**顶层**，丢弃发生在它之后链式 `.let { emptySet() }` —— 返回值是 lambda 尾巴而不是接收者，黑名单里没有 `.let` ⇒ 判绿。后果：`alreadySelected` 恒空集，结算把所有类别覆盖为自动测速结果，用户手选丢失。

**11-5 — `dataflow` — `arm3 speed-test auto-select writes useCustomMirror + persists + applies`**

- file：同上
- find（节选，startAllTests 内）：
  ```
                          selectedMirrorId[cat] = fastest.mirror.id
                          useCustomMirror[cat] = true
                          persist(context, cat)
                          autoApplied += cat
                      }
                  }
                  for (cat in autoApplied) applyMirror(context, cat)
  ```
- replace：同上，删掉 `autoApplied += cat` 一行
- why_gate_green：arm3 只验证 `"useCustomMirror[cat] = true" in tail`、`"persist(context, cat)" in tail`、`"applyMirror(" in tail` 且不在 `main_blocks(tail)` 内 —— 从不问「被 `for` 迭代的那个集合有没有被填过」。门检查的三个字面量全部保留，唯一断开的是一行数据流桥梁：`for (cat in autoApplied)` 迭代 0 次，容器内 `/etc/apk/repositories` 与 pip/npm 配置完全没更新。

**11-6 — 对照（已抓，保持）— `family-hole`**

- find：`            for (cat in toApply) applyMirror(context, cat)`
- replace：`            for (cat in toApply.take(0)) { applyMirror(context, cat) }`
- 修前已 CAUGHT（`is_constant_false` 的 `.take\(0\)` 分支命中花括号块头）。**审计方自疑成立**：无花括号的单行写法 `for (cat in toApply.take(0)) applyMirror(...)` 因为 `enclosing_heads()` 只提取带 `{` 的块头而漏判 —— 本支把这条也补成臂（N6b），braceless 形态现在同样红。

### file_uri_parse_guard_check.py

**01-1 — `dataflow` — `F1a sheet file:// branch resolves via decodeUriPath(url.removePrefix("file://"))`**

- file：`src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewBottomSheet.kt`
- find：
  ```
              val raw = decodeUriPath(url.removePrefix("file://"))
              if (raw.isBlank()) return@runCatching null
              val file = java.io.File(raw)
  ```
- replace：末行 → `val file = java.io.File(raw.substringBefore('#').substringBefore('?'))`
- why_gate_green：`post_decode_poison()` 只看 `text[close + 1: close + 80]` 是否**紧贴**调用右括号后的链式方法。截断下移到局部变量 `raw` 的消费点后，右括号后是换行 + `if (raw.isBlank())` ⇒ 黑名单匹配 None，判绿。后果：`report#1.html` 被截成 `report`，`exists()` false，「在外部浏览器打开」静默无反应。

**01-2 — `spelling` / `dataflow` — `F2a … / F2b / F4`**

- file：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt`
- find：
  ```
          stripped.startsWith("file://") ->
              decodeUriPath(stripped.removePrefix("file://")).takeIf { it.isNotBlank() }?.let { File(it) }
  ```
- replace：`            decodeUriPath(stripped.removePrefix("file://")).let { Uri.parse(stripped.trim()).path }?.let { File(it) }`
- why_gate_green：双重盲区 —— ①`POST_DECODE_POISON_RE` 黑名单只有 `substringBefore/replace/split/trim` 等字符串方法，**没有作用域函数**，`.let { … }` 直接穿透；②`URI_PARSE_PATH_RE = r"Uri\.parse\s*\([^()]*\)\s*\.\s*path\b"` 里的 `[^()]*` 禁止圆括号嵌套，`Uri.parse(stripped.trim())` 失配 ⇒ F2b/F4 计数 0。后果：解码结果被丢弃，回到 `Uri.parse(...).path` 的截断语义，聊天流里的 `file://…clip#1.mp4` 裂图。

**01-3 — `order` — `F3 fromAssetLoaderUrl cuts #/? before decodeUriPath`**

- file：`src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebViewHolder.kt`
- find：
  ```
      val relative = decodeUriPath(
          assetUrl.removePrefix(ASSET_LOADER_PREFIX).substringBefore('#').substringBefore('?'),
      )
  ```
- replace：
  ```
      val relative = decodeUriPath(
          assetUrl.removePrefix(ASSET_LOADER_PREFIX),
      ).substringBefore('#').substringBefore('?')
  ```
- why_gate_green：F3 的实现是 `has_cut = ("substringBefore('#')" in frm) and ("substringBefore('?')" in frm)` 与 `has_decode = "decodeUriPath(" in frm` 的**无序与运算** —— 判据标题写着 `before`，代码没有任何偏移比较。后果：`%23` 解码出的合法 `#` 又被切掉，`resolveWithinRoot` 指向不存在的路径。

**01-4 — `parser-death` — `F1b no Uri.parse(...).path in WebPreviewBottomSheet.kt / F4 ui/** sweep`**

- file：`WebPreviewBottomSheet.kt`
- find：`            val raw = …` 后的 `if (raw.isBlank()) ...` + `val file = java.io.File(raw)` 三行
- replace：
  ```
              val raw = decodeUriPath(url.removePrefix("file://"))
              val _openRaw = """ " /* """
              val file = java.io.File(Uri.parse(url).path ?: return@runCatching null)
              val _closeRaw = """ */ """
  ```
- why_gate_green：自写 `strip_comments()` 不认识三引号裸串：`""" " /* """` 前四个引号把单/双引号状态开合回 None，随后 `/*` 在 `quote is None` 下开块注释，**下一行真实的 `Uri.parse(url).path` 被整段抹掉**，直到 `_closeRaw` 的 `*/` 才恢复。Kotlin 编译器把这两个裸串当普通常量，中间那行 100% 编译执行。

**01-5 — `family-hole` — `F5c '#' cut only inside an http(s) branch; local paths keep it`**

- file：`src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MarkdownBlockModel.kt`
- find：`    return if (noQuery.startsWith("http://") || noQuery.startsWith("https://")) {`
- replace：同上 + ` || true`
- why_gate_green：`DEAD_GUARD_RE = r"\bfalse\s*&&|\btrue\s*\|\|"` 只匹配常量在**左**的形态；`|| true` 在末尾 ⇒ None，`HTTP_TEST_RE` 又能匹配前面的 `startsWith("http://`。后果：`minis:///…/foo#China.mp4` 走 then 分支被截成 `…/foo`，扩展名丢失、加载 404。

**01-6 — `family-hole` — `F5d decoded minis:// path is absolute-guarded before use`**

- file：`src/android/app/src/main/java/com/rikkaminis/app/ui/MinisImageFetcher.kt`
- find：`        val linuxPath = if (decoded.startsWith("/")) decoded else "/var/minis/$decoded"`
- replace：`        val linuxPath = if (decoded.startsWith("/") && false) decoded else "/var/minis/$decoded"`
- why_gate_green：与 01-5 是**同一条正则的对称漏网**（右置 `&& false`）。`guarded` 计数只看 `'startsWith("/")'` 在 cond 里 + 没有左置 `false &&` ⇒ 判绿。后果：绝对路径被拼成 `/var/minis//var/minis/…`，图片 FileNotFound。

---

## ③ 判据改造（旧形态 → 新形态，逐条）

### 共同底座

两门都**删掉自写词法器/括号配平/死分支白名单**，改用共享三件套：

```python
from _kotlin_lexer import KotlinLexError, mask_code, mask_comments
from _kotlin_ast import const_bool, function_body, match_bracket, split_top, enclosing_conditions
from _kotlin_valueflow import code_view, literal_view, value_sources, contains_ident,
                              guard_feasible, path_feasible, scope_calls, call_args, expr_end
```

- 结构分析（花括号/圆括号/函数体/可达性）一律读 `code_view`（= `mask_code`：注释 + 字符串/字符字面量内部抹平、**等长**）；
- 需要读字面量内容（`'#'`、`"mp4"`、`"http://"`、`"/var/minis/$decoded"`）的地方读 `literal_view`（= `mask_comments`：只剥注释，字面量原样）——两者等长，位置可互投；
- `KotlinLexError` → 直接报违规（fail-closed），**不回退原文扫描**。

镜像门自写件删除清单：`code_only()` / `block_after()` / `fn_body()`（原 substring 版）/ `match_paren()` / `for_loops()` / `enclosing_heads()` / `is_constant_false()` / `dead_context()`（原实现）/ `in_discarded_lambda()` / `statement_expr()`。
文件门删除清单：`strip_comments()` / `_match_paren()` / `_balanced()` / `branch_slice()` 的 30 行窗口 / 以 `in frm` 判顺序的 F3 / `[^()]*` 正则。

### 逐条（旧 → 新）

| # | 旧判据形态 | 新判据形态 | 覆盖见证 |
|---|---|---|---|
| M-arm1/arm4 | `"<map>[<key>]" in snap`（两个名字出现即绿） | `snapshot_atomic()`：函数体内**每一条** `map[key] =` 都必须落在 `Snapshot.withMutableSnapshot { … }` 的块区间内，且事务必须覆盖指定的 `(map, key)` 写 —— 块内哑读 + 块外真写 = outside 违规 | 11-2、W11、V3、V4 |
| M-arm2 可达性 | `"applyMirror(" in detect` + 自写 `dead_context` 正则白名单 | `unreachable_reason()`：`enclosing_conditions` 取外层 if 条件 + `guard_feasible` 折叠；再扫 `for`/`while` 头，`iterable_dead()` 判 `.take(0)`/`.takeWhile{false}`/`emptyList()`/`while(false)`；**花括号与单语句两种循环体都覆盖** | 11-1、11-6、W12、N6/N6b |
| M-arm2/arm3 数据流 | 只查 `useCustomMirror[cat] = true` / `persist(context, cat)` / `applyMirror(` 三个字面量在 `tail` 里 | `apply_wiring_problem()`：找到「循环体里含 `applyMirror(`」的 `for (x in NAME)`，要求 `NAME` 在**该循环之前**被填过（`NAME +=` / `.add(` / `.addAll(` / `.addElement(`），否则报「iterates a list that is never filled」 | 11-5（+11-5 自述的 `toApply` 同族） |
| M-arm7 | `"autoDetectJob" in cond` + `DEAD_IF_RE` / `DEAD_GUARD_RE`（单向字面量白名单） | `match_bracket` 取条件 + `guard_feasible(cond) is False` 判恒假（`false && g`、`g && false` 同一红）；另加两条效果判据：守卫分支必须**真的退出**（`return`/`throw`）、守卫必须**先于**首次写 | 11-3、W13、V8、W14（活体拼写仍绿） |
| M-arm5 | `in_discarded_lambda()`：只查外层块头是否 `.also`/`.apply` | `prior_selection_value_problem()`：`value_sources()` 取真实求值来源，任一来源含 `selectedMirrorId` 才算「值被带走」；`.let { emptySet() }` 的来源是 `emptySet()` ⇒ 红。**只认顶层读取**（`_outside_braces()` 抹掉 lambda 内部，避免把 `val job = scope.launch { … }` 误判成 prior-selection 读）；`apply { addAll(keys) }` 的接收者重建形态保留为绿 | 11-4、W13 |
| M-slot | `@Volatile private var X` 正则 + `X?.cancel()` 子串 | `launch_slot()` 从 `X = …launch` / `val job = …launch; X = job` 派生槽位名，再按名字查 `@Volatile` 与两个入口的 `X?.cancel()`（名字派生而非写死拼写） | V6/V7/V10 |
| F-F1a/F2a | `decodeUriPath(...)` 右括号后 80 字符的黑名单 | `decode_flow_problem()`：①右括号后链式改写 → 红；②链上 `let/run/with` 的 lambda 不读接收者（`it`/命名参数/`this`）→ 红；③绑定局部在**消费点**被改写（`raw.substringBefore(…)`）→ 红 | 01-1、01-2（+W 期 `BREAKER_SHEET_POST_CUT`） |
| F-F1b/F2b/F4 | `r"Uri\.parse\s*\([^()]*\)\s*\.\s*path\b"` | `count_uri_parse_path()`：`match_bracket` 配平取实参、再看闭合括号后是否 `.?path`（`pathSegments` 由 `(?![\w])` 排除）；括号不配平计违规（fail-closed） | 01-2、01-4 |
| F-F3 | `("substringBefore('#')" in frm) and ("…('?')" in frm)` 无序 | `holder_cut_problem()`：`call_args()` 取 `decodeUriPath(` 的实参，两个 cut 必须在**实参内**；闭合括号后若还有改写 → 红（顺序即判据） | 01-3 |
| F-F5c/F5d | `DEAD_GUARD_RE` 左右单向 + `'startsWith("/")' in cond` | `_dead_guard(cond)` = `const_bool()` 折叠，`true ||` / `|| true` / `false &&` / `&& false` / `!true` / `1 == 2` 全部同一红；F5c 另要求条件真含 http(s) 测试，F5d 另要求条件真含 `startsWith("/")` | 01-5、01-6 |
| F-词法 | 自写 `strip_comments()` 状态机 | `mask_code` / `mask_comments`（三引号裸串、嵌套块注释、字符字面量、模板插值） | 01-4 |

自证臂：镜像门 **27 臂**（原 16 → 新增 N1/N1b/N2/N3/N4/N5/N6/N6b + 三条 precision N7/N8/N9），文件门 **21 fixtures**（原 13 → 新增 consumption-cut / md-let-parse / holder-post-cut / raw-string-comment / dead-guard 双侧 / fail-closed / stale-allowlist / precision 等）。

---

## ④ 四层证据

### L1 本门（真树 + 自证）

```
$ python3 scripts/scan/mirror_vm_thread_check.py .
MIRROR_VM_THREAD_CHECK=PASS                       rc=0
$ python3 scripts/scan/mirror_vm_thread_check.py --selftest
SELFTEST A (compliant): GREEN
SELFTEST V1 (drop useCustomMirror): RED as expected
… V2 V3 V4 V5 V5b V6 V7 V8 V9 V10 W11 W12 W13（全 RED as expected）
SELFTEST W14 (live guard spelled via isActive): GREEN
SELFTEST N1 (applyMirror only inside a trailing comment): RED as expected
SELFTEST N1b (applyMirror only inside a raw string): RED as expected
SELFTEST N2 (retryApkWorld writes outside the snapshot): RED as expected
SELFTEST N3 (ownership check `false && guard`): RED as expected
SELFTEST N4 (prior selection discarded by .let): RED as expected
SELFTEST N5 (autoApplied never filled): RED as expected
SELFTEST N6 (applyMirror behind for (x in y.take(0))): RED as expected
SELFTEST N6b (braceless .take(0) loop): RED as expected
SELFTEST N7 (prior selection rebuilt via apply { addAll }): GREEN
SELFTEST N8 (applied list filled with .add): GREEN
SELFTEST N9 (take(0) on an unrelated loop): GREEN
MIRROR_VM_THREAD_SELFTEST=PASS (27 arms)          rc=0

$ python3 scripts/scan/file_uri_parse_guard_check.py .
  [OK] F1a … [OK] F1b … [OK] F2a … [OK] F2b … [OK] F3 …
  [OK] F4 allowlist exact for WebPreviewShortcut.kt — exempted occurrence: …
  [OK] F5a … [OK] F5b … [OK] F5c … [OK] F5d … guarded=4 unguarded=none
Summary: 10 passed, 0 failed
swept 223 ui/** .kt file(s) under src/android/app/src/main/java/com/rikkaminis/app/ui
GATE=GREEN file_uri_parse_guard                     rc=0
$ python3 scripts/scan/file_uri_parse_guard_check.py --selftest
selftest: fixed-tree=GREEN, sheet-revert=RED, md-revert=RED, holder-cut-removed=RED,
new-offender=RED, stale-allowlist=RED, host-precision=GREEN, media-hash-cut=RED,
media-set-fork=RED, media-parser-ext=RED, media-chat-chain=RED, media-abs-guard=RED,
dead-guard-both-sides=RED, consumption-cut=RED, md-let-parse=RED, holder-post-cut=RED,
raw-string-comment=RED, fail-closed=RED
FILE_URI_PARSE_GUARD_SELFTEST=PASS (21 fixtures)    rc=0
```

自证臂的断言口径：**断到具体判据**（`crit(results, F2A) is False`），不是「有一坨红就算过」——撞错判据 = 假臂 = FAIL；precision 臂（N7/N8/N9/W14/allowlist）断言必须 GREEN，防「加牙」误伤正当重构。

### L2 反攻（硬验收）

```
$ python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
    --witnesses …/witnesses-A.json --repo . --mode full \
    --only mirror_vm_thread_check.py --out …/probe/t14-mirror_vm_thread_check.json
[1/6] 11-1 -> CAUGHT (rc=1, exact)   [4/6] 11-4 -> CAUGHT (rc=1, exact)
[2/6] 11-2 -> CAUGHT (rc=1, exact)   [5/6] 11-5 -> CAUGHT (rc=1, exact)
[3/6] 11-3 -> CAUGHT (rc=1, exact)   [6/6] 11-6 -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}    restored=True ×6

… --only file_uri_parse_guard_check.py …
[1/6] 01-1 -> CAUGHT (rc=1, exact)   [4/6] 01-4 -> CAUGHT (rc=1, exact)
[2/6] 01-2 -> CAUGHT (rc=1, exact)   [5/6] 01-5 -> CAUGHT (rc=1, exact)
[3/6] 01-3 -> CAUGHT (rc=1, exact)   [6/6] 01-6 -> CAUGHT (rc=1, exact)
SUMMARY: {'CAUGHT': 6}    restored=True ×6
```

**12/12 从 BLIND_CONFIRMED 翻成 CAUGHT**，`git status --porcelain src/` 全程为空（变异 12 次全部还原）。

逐条归属（每条红的文案落在自己那条判据上；同一见证命中第二条同族判据也算，因为两条各自独立成立）：

| 见证 | 门输出（摘要） |
|---|---|
| 11-1 | `autoDetectOnceIfNeeded does not call applyMirror` |
| 11-2 | retryApkWorldWithMirrorFallback **writes the state maps outside Snapshot.withMutableSnapshot**（`selectedMirrorId[MirrorCategory.ALPINE] =`） |
| 11-3 | ownership check **`if (false && autoDetectJob !== self)`** → *can never fire (constant-false conjunct)* |
| 11-4 | prior selection lost — **`alreadySelected`** reads selectedMirrorId 但携带的值是 **`emptySet<MirrorCategory>()`** |
| 11-5 | **`for (… in autoApplied) applyMirror(…)`** → *iterates a list that is never filled* |
| 11-6 | calls applyMirror **under a constant-false construct**（`for (cat in toApply.take(0))`） |
| 01-1 | F1a — **`raw`（解码结果）在读取处被 `.substringBefore…` 重写** |
| 01-2 | F2a — **解码值被送进 `.let { … }` 而 lambda 从不读接收者** + F2b `found 1` |
| 01-3 | F3 — **cut 必须在 `decodeUriPath(…)` 实参内**（missing `substringBefore('#')`, `substringBefore('?')`） |
| 01-4 | F1b `found 1 occurrence(s)` + F4 `found 1` |
| 01-5 | F5c — **dead guard**，*the guard folds to `true`* |
| 01-6 | F5d — MinisImageFetcher.kt **dead guard**（`decoded.startsWith("/") && false` → folds to `false`） |

### L3 邻门（10 道，全 rc=0）

```
mirror_category_route_check            -> rc=0  MIRROR_CATEGORY_ROUTE_CHECK=PASS
collection_copy_on_mutate_check        -> rc=0  OK (all mutate sites persist a copy)
router_lock_check                      -> rc=0  GATE=GREEN router_lock
overlay_show_hide_latch_check          -> rc=0  OK: 4 criteria clean
stale_closure_guard                    -> rc=0  无长寿命 effect 读裸参数
webview_holder_url_source_check        -> rc=0  GATE=GREEN webview_holder_url_source
file_preview_url_encode_check          -> rc=0  OK (arm1+arm2+arm3 green)
filepreview_print_bounded_check        -> rc=0  PASS (10 decision(s) checked)
webapp_asset_url_cleanup_check         -> rc=0  PASS all 15 arms
open_url_broker_scheme_check           -> rc=0  all 15 criteria green
```

### L4 全量

```
$ bash scripts/scan/scan.sh
║  Summary: 112 passed, 0 failed        rc=0        (112 门逐个出结果，无 ENOSYS 中断)
$ python3 scripts/scan/test_scan.py
  ✅ mirror_vm_thread_check selftest has teeth (MIRROR_VM_THREAD_SELFTEST=PASS)
  ✅ mirror_vm_thread_check real tree green
  ✅ file_uri_parse_guard_check selftest has teeth (FILE_URI_PARSE_GUARD_SELFTEST=PASS)
  ✅ file_uri_parse_guard_check real tree green
✅ ALL 344 CASES PASS                                  rc=0
$ git status --porcelain src/     # 空
```

本波沙箱表现（与前面几支不同，记录在案）：`scan.sh` 整跑 **112/112 一次通过**（~6 分钟，无 `Errno 38`），`test_scan.py` 整跑 **344/0 一次通过**（~10 分钟）。因此本支的 L4 读数来自本地实跑，不依赖 CI 兜底。

---

## ⑤ CI（分支 `fix/b7-mirrorvm-fileuri`，门提交 `57a86390a`）

- run **`40273300868`** / **#2722** / job `121410617807` / attempt **1** / **success**
  （`2026-10-26T05:16:07Z → 05:35:13Z`，18m06s；`workflow_dispatch`）
- `head_sha` = **`57a86390aa918ffd47f97c77fe68532b14599e00`** —— 与本地门提交**逐字符一致**（三方一致：本地 HEAD / run.head_sha / job.head_sha）
- 26 步：唯一非 success = **step 23 `Publish to Releases` skipped**（main 门控，预期）
- job 日志 **1,486,337 B / 裸 NUL 0**，逐条判读：

| 判读项 | 期望 | 实测 |
|---|---|---|
| `Summary: 112 passed, 0 failed` | 恰 1 | **1** |
| `ALL 344 CASES PASS` | 恰 1 | **1** |
| 门头 `[N/112]` | 112 且编号互异 | **112 / distinct 112** |
| Kotlin 编译错（`^\s*e: `） | 0 | **0** |
| 含 `FAILED` 的行 | 全是 `PASSED` 用例名的子串 | **6 行，逐条以 `PASSED` 结尾**（`…produces FAILED without calling provider PASSED` 等） |
| `MIRROR_VM_THREAD_SELFTEST=PASS` | 恰 1（⇒ 新臂在 CI 真跑） | **1** |
| `FILE_URI_PARSE_GUARD_SELFTEST=PASS` | 恰 1（⇒ 新臂在 CI 真跑） | **1** |
| `MIRROR_VM_THREAD_CHECK=PASS`（真树） | 恰 1 | **1** |
| `GATE=GREEN file_uri_parse_guard`（真树） | 恰 1 | **1** |

（另一口径旁证：`Summary: N passed, M failed` 的其它 7 条小 Summary 分别为 28/5/14/36/15/25/**10**/0 —— 其中 `10 passed, 0 failed` 正是 file_uri 门自己的判据计数：F1a/F1b/F2a/F2b/F3/F4(allowlist)/F5a–F5d。）

**停止节点达成**：分支 CI 绿 = 本支开发的确定性结束信号。按纪律**不合并 main**（合并起手权在用户），分支保留在远端。

## ⑥ 残留（P2，均带「触发条件 / 触发后方案 / 搁置理由」三件）

### mirror_vm_thread_check.py

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| M-R1 | `unreachable_reason()` 只建模 `if`/`when` 与 `for`/`while`；`do { } while (false)`、`run { }`/`also { }` 包住的调用不建模 | 结算代码被包进 `do { … } while (false)`（无此写法） | 在 `loops()` 里加 `do` 分支（`while` 在尾）+ 加 `call_blocks` 的 lambda 视为"可达" | 现有代码零处 `do` 循环；加了也无被证伪路径，装置比例不符 |
| M-R2 | `iterable_dead()` 只认 `.take(0)`/`.takeLast(0)`/`.takeWhile(0|false)`/空字面量/`while(false)`；`.filter { false }`、别名局部、`if (x) emptyList() else y` 不认 | 有人用 `.filter { false }` 关闭循环 | 复用 `_kotlin_valueflow` 的 `_provably_empty` 同族判据（B7-t07 已实现一版） | 属"更宽的恒假识别"，与本次 5 条见证无交集；单独扩会拉高误伤面（`.filter { it.x }` 不可折叠时必须保守） |
| M-R3 | 填充识别只覆盖 `NAME +=` / `.add(` / `.addAll(` / `.addElement(`；`NAME = listOf(...)`、`NAME.addAll(other.map{…})` 里的 `NAME` 若是属性（非局部）也照判 | 结算改成一次性赋值 `applied = sorted.filter {...}` | 把 append 判据换成「值流：循环迭代的标识符的 `value_sources` 必须含写块内的表达式」 | 现写法是 `mutableListOf` 累加；换成不可变赋值时门会红（fail-closed 方向），届时按红改判据即可 |
| M-R4 | `snapshot_atomic()` 只认**第一个** `Snapshot.withMutableSnapshot` 块；合法拆成两个事务会判「块外写」红 | 有人把两处写拆成两个事务 | 取全部 span，`outside` 判据改成「不在任一 span 内」 | 现状单事务；拆两个也是合法设计，但没发生前加分支等于给未见形态写代码 |
| M-R5 | `launch_slot()` 认 `X = …launch` 与 `val job = …launch; X = job`；`?.also { slot = it }`、`let { X = it }` 不认 | 改用 `scope.launch{}.also { autoDetectJob = it }` | 在 `launch_slot` 里加「`.also`/`.let` 体内对字段赋值」形态 | fail-closed 方向（不认则红），不会静默放过；writer 换写法时门会提示 |
| M-R6 | 所有权检查只认「条件里出现槽位名或 `isActive`」；`if (!stillOwner(self))` / `if (!ownerIs(self))` 不认 | 守卫抽成谓词函数 | 把谓词名加入候选集合，或沿一跳函数调用展开（照 `settlement_guard_ok` 的两跳模型） | 现写法就是内联比较；两跳展开会引入「函数体在别处」的可达性问题，需单独设计 |
| M-R7 | 「守卫必须退出」判据接受 then 分支里任意 `return`/`throw`，包括 `return@let` 这类只退出内层 lambda 的写法 | 守卫写成 `if (…) { someJob.let { return@let } }` | 复用 `_kotlin_valueflow.returns_exiting` 的"裸 return vs `return@label`"区分，并按 label 判断是否退到 `withContext` 外 | 现写法是裸 `return@withContext`；改成 `return@let` 属新形态，且当前 gate 已因「did not exit」以外的方式无洞 |
| M-R8 | `prior_selection_value_problem()` 只看 `val/var` 绑定；直接内联进调用实参的读取（`decideAutoApply(results, selectedMirrorId.keys.toSet().let{emptySet()})`）不判 | 有人把 `alreadySelected` 内联掉 | 把判据扩成「决策函数实参列表逐项检查 value_sources」 | 现写法有具名局部（可读性更好），内联概率低；扩判据要处理位置参数与具名参数两形态 |
| M-R9 | 共享层 `value_sources()` 不折叠 `if/else`（前几批已登记）：条件式 prior 选择 `if (x) keys else emptySet()` 整体含标识符 ⇒ 判绿（fail-open） | 把 `alreadySelected` 写成条件式 | 在共享层给 `value_sources` 加 if/else 分叉（B7-t07 R1 同项） | **共享层归主会话**，本支禁改（硬约束 #2）；单独在门内绕过会与 t07 冲突 |
| M-R10 | 自证臂数（27）不进 CI 日志：`test_scan.py` 只断言标记前缀 `MIRROR_VM_THREAD_SELFTEST=PASS` | 想看 CI 里的臂数 | §7 给了注册串（带 `(27 arms)`），主会话并集收口时替换即可 | 冻结面 `test_scan.py` 本支禁改，只能提出注册建议 |

### file_uri_parse_guard_check.py

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| F-R1 | `decode_flow_problem()` 要求函数内「带 `removePrefix("file://")` 实参的 `decodeUriPath(` 恰 1 处」；合法加第二处（fallback 分支）即红 | 新增第二条 `file://` 解析路径 | 改成「每处都要通过值保持检查」的循环，README 级说明 | fail-closed 方向；现在只有一处，加分支时门会红并提示 |
| F-R2 | 作用域丢弃只建模 `let`/`run`/`with`；`.map { Uri.parse(x).path }`、`.runCatching { … }.getOrNull()` 等不判 | 有人用 `.map`/`.runCatching` 在链上换值 | 把 `runCatching`/`let` 家族扩到 `map`/`flatMap`/`getOrElse`（需按"值是否仍派生自接收者"判） | 与 F2b 有重叠（`Uri.parse` 一律被禁），只有"换成非 Uri 的其它值"才漏；该形态未出现 |
| F-R3 | 消费点扫描只跟一跳局部（`val raw = …` → `raw` 的读），`val p = raw; File(p.trim())` 二跳不跟 | 有人把解码结果再绑一层 | 用 `decl_sites` + `value_sources` 做二跳追踪（`expr_end` 已有） | 一跳覆盖现写法；二跳引入别名链爆炸，收益不明 |
| F-R4 | 改写方法名是白名单（`substring*`/`replace`/`split`/`trim*`/`remove*`）；`URLDecoder.decode(...)`、`File(...).name`、`.toPath()` 不认 | 换一种截断/改写 API | 白名单改成「除少数**保持值**的方法外，任何链式字符串方法都保守判红」 | 白名单式误伤小；反向白名单会在 `.exists()`/`isFile` 这类常见链上误红一片 |
| F-R5 | F5d 只看 `"/var/minis/$decoded"` 所在**行**里的 `if (`；守卫写在上几行（`if (!decoded.startsWith("/")) { … }` 早退）→ 报「no readable guard」红 | 守卫改早退式 | 用 `enclosing_conditions()` 取代同行扫描（位置信息已具备） | fail-closed 方向；现写法是同行三元 |
| F-R6 | F5c 只识别 `substringBefore('#')` 这个拼写；`.split('#').first()`、`indexOf('#')` 不判 | 换一种去 fragment 的写法 | 加「任何以 `'#'` 为实参的字符串切分调用」族 | 现写法唯一；扩族需要判"切分结果是否被使用"，属值流分析 |
| F-R7 | F4 的 allowlist 记的是**次数**不是**站点**：同文件里新增另一个同族站点而旧站点被修掉（总数不变）不会红 | 在 `WebPreviewShortcut.kt` 里挪动/替换该站点 | allowlist 改存「站点指纹」（`function + 附近表达式` sha），逐站点比对 | 该文件不在本支修复半径内；指纹方案要写生成器，先登记 |
| F-R8 | F3 要求 cut 在实参里被拼成 `substringBefore('#')`/`substringBefore('?')`；改用具名常量（`substringBefore(HASH)`) 或 `takeWhile { it != '#' }` 即红 | 抽常量或换写法 | 常量化：取实参里所有 `substringBefore(<arg>)`，解析 `<arg>` 的常量值（`const_value` 已能折字符串吗？需扩） | fail-closed 方向（红而非漏），且抽常量属重构；扩判据需共享层支持字符串常量折叠（现 `const_value` 只折布尔/数值） |
| F-R9 | fail-closed 时只输出 F0 一条判据，F1–F5 不出现 ⇒ 判据计数从 10 变 1 | 任一被扫文件词法失败 | 保留 F0 之外仍逐条输出"未判定(fail-closed)"占位项 | 只在解析失败时发生（当前 0 次）；下游若按固定条数统计需注意 |
| F-R10 | 值保持判据接受"来源里出现 `decodeUriPath` token"——`File(other(decodeUriPath(x)))` 这类把解码值当**副作用参数**传走的形态仍判绿 | 把解码值传给另一个会改写它的函数 | 要求来源是"解码调用本身或其纯链"（`strip` 后必须 `^decodeUriPath\(` 起始或 `.let` 内 `it` 派生） | 现写法是纯链；该形态未出现，且强判会误伤 `File(decodeUriPath(x).ifBlank { … })` 这类合法写法 |

---

## ⑦ `test_scan.py` 注册项（交主会话并集收口）

**本支未改 `test_scan.py`**（冻结面），现有两条注册仍然通过（标记子串未变，本支实测 ✅）：

```python
    ("mirror_vm_thread_check", "MIRROR_VM_THREAD_SELFTEST=PASS", (), (REPO_ROOT,)),       # L2836
    ("file_uri_parse_guard_check", "FILE_URI_PARSE_GUARD_SELFTEST=PASS", (), (REPO_ROOT,)),  # L2864
```

若希望 **CI 见证新臂的臂数**（推荐，成本 = 改两个字符串），把标记串替换为带计数的形态：

```python
    ("mirror_vm_thread_check", "MIRROR_VM_THREAD_SELFTEST=PASS (27 arms)", (), (REPO_ROOT,)),
    ("file_uri_parse_guard_check", "FILE_URI_PARSE_GUARD_SELFTEST=PASS (21 fixtures)", (), (REPO_ROOT,)),
```

两门自测输出已按该形态打印（本次实测：`MIRROR_VM_THREAD_SELFTEST=PASS (27 arms)`、`FILE_URI_PARSE_GUARD_SELFTEST=PASS (21 fixtures)`）。注意：**加臂后要同步这两个数字**，否则 test_scan 会红——这是刻意的（臂数变化必须显式确认）。

---

## ⑧ 复现命令

```bash
cd <repo>            # = base/batch5-shared @ e145ec961 之上的 fix/b7-mirrorvm-fileuri

# L1 本门
python3 scripts/scan/mirror_vm_thread_check.py .                 # MIRROR_VM_THREAD_CHECK=PASS
python3 scripts/scan/mirror_vm_thread_check.py --selftest        # …SELFTEST=PASS (27 arms)
python3 scripts/scan/file_uri_parse_guard_check.py .             # Summary: 10 passed, 0 failed
python3 scripts/scan/file_uri_parse_guard_check.py --selftest    # …SELFTEST=PASS (21 fixtures)

# L2 反攻（12 条见证逐条翻红 + 自动还原）
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
    --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-A.json \
    --repo . --mode full --only mirror_vm_thread_check.py \
    --out …/probe/t14-mirror_vm_thread_check.json
python3 /var/minis/shared/work/meta-audit-v5/batch7/verify_batch5.py \
    --witnesses /var/minis/shared/work/meta-audit-v5/batch7/witnesses-A.json \
    --repo . --mode full --only file_uri_parse_guard_check.py \
    --out …/probe/t14-file_uri_parse_guard_check.json
git status --porcelain src/                                      # 必须为空

# L3 邻门
for g in mirror_category_route_check collection_copy_on_mutate_check router_lock_check \
         overlay_show_hide_latch_check stale_closure_guard webview_holder_url_source_check \
         file_preview_url_encode_check filepreview_print_bounded_check \
         webapp_asset_url_cleanup_check open_url_broker_scheme_check; do
  python3 scripts/scan/$g.py . ; echo "$g rc=$?" ; done

# L4 全量
bash scripts/scan/scan.sh            # Summary: 112 passed, 0 failed
python3 scripts/scan/test_scan.py    # ALL 344 CASES PASS

# 沙箱注意（本波未触发，仍记录）
#   - 整跑被 stall-guard / Errno 38 打死时的健康路径：逐门独立进程循环 + 逐 test 函数 SIGALRM 驱动
#   - 后台长跑必须 nohup … > log 2>&1 &（管道接 grep 会让工具看不到输出，被误判 stall）
```
