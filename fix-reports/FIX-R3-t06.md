# FIX-R3-t06 — mirror_category_route / mirror_vm_thread（第三轮 · dual-gate bypass）

基线 `main` @ `f0f14d46d`（第三轮收口批）· 分支 `fix/r3-mirror-route` · 工作树 `round3b/wt/t06`
改动面：**2 个门脚本**（`+449/−5`、`+333/−0`）+ 本报告。生产 `.kt` **0 行**，`scan.sh` / `test_scan.py` / `_kotlin_{lexer,ast,valueflow}.py` **零触碰**，门数 112 / 夹具 344 不变。

---

## 1. 门（本支的两道门）

| 门 | 现有判据（1–7） | 本轮新增（8–10） | 自证臂 |
|---|---|---|---|
| `scripts/scan/mirror_category_route_check.py` | A 生产路由链存在（AppNavigation 的 `onMirrorCategoryClick` + detail screen 定义/调用）· 1b 唯一路由落点（`fromKey` 且 `category =` 由 `cat.key` 解析）· 2 点击路由可达（`click_route` + `reachable`）· 3 `fromKey` 返回 `MirrorCategory?` + `firstOrNull` + 谓词可判别 · 4 路由 pattern 经 `seg()` 编码 · 04-* 守卫/重赋值/篡改 | **A5 行身份**：`MirrorCategoryRow(category = X, onClick = { … })` 的点击必须把 **X** 传下去（`value_sources` + 一跳绑定 + 接收者剥离）<br>**A6 详情页自读**：`MirrorCategoryDetailScreen` 体内每一处 `results[…]` / `results.get(…)` / `MirrorCatalog.*(…)` 都必须由它自己的 `category` 形参派生<br>**A3b 谓词语义**：`fromKey` 的谓词用枚举**自己声明的 key 字面量**逐对求值——每个 key 必须选中它自己那条、不许选中别的、未知 key 必须一条都不选中 | 16 → **24** |
| `scripts/scan/mirror_vm_thread_check.py` | 1 `ensureLoaded` 快照原子（IO 内读、Main 内写）· 2 `runAllTests` 在 Main 块外 `applyMirror(cat)` · 3 `startAllTests` 选中→persist→应用三件 + `apply_wiring_problem` · 4 `retryApkWorld` 快照 · 5 结算保留用户选择 · 6 cancel 性 · 7 结算前所有权校验 | **B8 自选路径族**：凡体内写 `useCustomMirror[…] = true` 的函数（**从树派生**，不写死名字）都必须 persist + 有一处可达、非 Main 的 `applyMirror` + 接线正确<br>**B9 点击入口分派**：`selectMirror`（行 onClick = UI 线程）的 rootfs 写必须在 `launch`/`withContext` 分派块内且可达（`builder_spans` 派生，非 dispatch 白名单）<br>**B10 效力落地**：`applyMirror` 体内必须有可达 `writeText(...)`，且其值经 ≤2 跳绑定/接收者剥离后派生自 `mirror`/`category`（常量不算） | 27 → **36** |

## 2. 轴（这批见证的攻击面）

| 见证 | 轴 | 一句话 |
|---|---|---|
| #103 | **dual-gate**（门间缝） | 两个门各自钉住了链的两端（`AppNavigation` 的解析 + detail 目标），**中间那一跳**（行 tap 把哪条 category 交给回调）无人管 |
| #104 | **dual-gate** | 路由/anchor 全部正确，但详情页**体内读谁**没人管——标题是 pip，列表是 alpine |
| #105 | symmetric（对称面） | 旧判据的正则把 `!=` 与 `==` 并列成合法的「key 比较」；极性反了反而被判绿 |
| #106 | **dual-gate** | 线程门的 2/3/5/7 号臂都按**函数名**点验；`selectMirror` 这条 UI 线程入口不在名单上，把 `launch{}` 拿掉谁都不红 |
| #107 | symmetric | `runTest`（单类测速）与 `startAllTests` 是**结构孪生**，旧臂只认识后者 |
| #108 | symmetric | 每个调用点都接线正确，**被调方**把 `writeText` 删掉（还照旧 log「Applied mirror」）无人管 |

## 3. 见证重放表

修前 = `probe/t06-pre.json`，修后 = `probe/t06-post.json`，补丁留档 `probe/t06-w<id>.patch`（6 份）。

| # | 轴 | 补丁（生产 .kt，重放后已还原） | 修前 rc | 判决 | 修后 rc（route / vm） | 修后红在哪条判据上 |
|---|---|---|---|---|---|---|
| 103 | dual-gate | `MirrorCategoryRow(category = category, onClick = { onNavigate(MirrorCategory.ALPINE) })` | route 0 / vm 0 | **BLIND_CONFIRMED** | **1** / 0 | 「the MirrorCategoryRow tap does not carry the row's own category」 |
| 104 | dual-gate | 详情页 `vm.results[ALPINE]` + `MirrorCatalog.mirrors(ALPINE)` | 0 / 0 | **BLIND_CONFIRMED** | **1** / 0 | 「MirrorCategoryDetailScreen reads `results[...]` with `MirrorCategory.ALPINE` instead of its category parameter」 |
| 105 | symmetric | `entries.firstOrNull { it.key != key }` | route 0 | **BLIND_CONFIRMED** | **1** / — | 「the predicate `it.key != key` REJECTS `ALPINE`'s own key "alpine"」 |
| 106 | dual-gate | `selectMirror`：删 `scope.launch { }`，直呼 `applyMirror` | vm 0 / route 0 | **BLIND_CONFIRMED** | 0 / **1** | 「selectMirror writes the container config inline (no launch/withContext dispatch)」 |
| 107 | symmetric | `runTest`：删 `for (cat in autoApplied) applyMirror(context, cat)` | vm 0 | **BLIND_CONFIRMED** | **1** / — | 「runTest auto-select never applies the mirror it selected (no applyMirror call)」 |
| 108 | symmetric | `applyMirror`：`configFile.writeText(content)` → 注释掉 | vm 0 | **BLIND_CONFIRMED** | **1** / — | 「applyMirror never writes the selected source to the config file」 |

**6/6 BLIND_CONFIRMED → 6/6 CAUGHT**，`restored 6/6 = True`（sha256 逐条比对），事后 `git status` 只剩两个门脚本（`src/` 零残留）。

### dual-gate 的三条：谁该管这条缝（任务书 §2.3 要求）

| 见证 | 归属 | 理由 |
|---|---|---|
| #103 | `mirror_category_route_check`（A5） | 这是**路由身份**问题：一条 category 从行到目标的传递。线程门没有、也不该有路由锚——给它加一条「行的 category 传下去了吗」就是与 A5 重复覆盖（spec §3 禁止：重复覆盖 ≠ 空白）。两门 rc = `1 / 0` 即按此归属成立。 |
| #104 | `mirror_category_route_check`（A6） | 同上：详情页读谁 = 路由终点的身份，不是线程/生命周期。 |
| #106 | `mirror_vm_thread_check`（B9） | 这是 **UI 线程 + rootfs IO** 问题（[T10-L7] 的同族）。路由门不判线程，给它加「selectMirror 是否分派」同样是重复覆盖。两门 rc = `0 / 1`。 |

## 4. 修法

### 4.1 `mirror_category_route_check` A5 — 行身份（#103）

旧：链的两端各钉一处（`AppNavigation` 的 `cat -> cat.key`；detail 端的 `fun` 存在且被调用），**中间的行构造没有任何判据**。
新（`row_tap_problems`，对全部生产 `.kt` 派生）：

1. 用 `call_args()` 取每个 `MirrorCategoryRow(` 的具名实参 `category =` 与 `onClick =`；缺任一即 fail-closed。
2. 行 category 的 `value_sources()` + **一跳本地绑定**（`val rowCategory = category` 也算携带）作为「行的身份集合」。
3. `onClick` lambda 内**每个调用的每个实参**都做同样的取值；至少有一处实参的身份集合与行身份**相交**才算携带。
4. 相交判定 = 等值 / `contains_ident()` 双向包含（`category` vs `category.key` 也认）。

于是 `onNavigate(MirrorCategory.ALPINE)`（常量）与 `onClick = { Unit }`（什么都不调用）都红；`onNavigate(category)`、`onNavigate(rowCategory)`（一跳绑定）都绿。

### 4.2 A6 — 详情页自读（#104）

旧：只判 `MirrorCategoryDetailScreen` 存在且被调用（anchor）。
新（`detail_body_problems`）：取函数体（`function_body`），枚举体内**每一条** category 作用域的读——`results[…]`、`results.get(…)`、`MirrorCatalog.<fn>(…)`——其 index/实参的取值必须命中该函数的 `MirrorCategory` 形参；零条读也红（「屏幕读不到本类别的数据」）。`MirrorCategory.ALPINE` 常量 → 红；`category` / `targetCategory`（一跳绑定）→ 绿。

### 4.3 A3b — `fromKey` 谓词语义（#105）

旧：正则白名单 `(?:==|!=|equals|contains|in)\s*[^;}]*\bkey\b`——**把 `!=` 当成合法比较**，只看「有没有拿 key 比」，不看比出什么。
新（`fromkey_polarity_problem` + `eval_predicate`）：

1. 从枚举源码读**声明的 key 字面量**（`ENTRY("key"` → `[(ALPINE,"alpine"),(PIP,"pip"),…]`），key 属性名从枚举头 `val <name>: String` 派生。
2. 谓词做三值求值（True/False/None=不可建模）：`&&`（任一 False 即 False）、`||`（任一 True 即 True）、`!`、`==`/`!=`、`.equals/.equalsIgnoreCase/.contains/.startsWith`、`in setOf/listOf`、`lowercase/uppercase/trim`；两侧取值只接受**枚举 key 访问、形参、字符串字面量**。
3. 三条判据：①每个 key 必须能选中它自己那条（否则 = 极性反了）；②任一条不许被别的 key 选中（否则 = 不具判别力）；③未知 key 一条都不许选中（fail-closed 契约）。

`it.key != key` 在第 ① 条当场红（`ALPINE` 自己的 key 选不中自己）；`it.key == key || true` 在第 ② 条红；`it.key == key && false` 在第 ① 条红；`it.key.equals(key, ignoreCase = true)` 全绿。不可建模的分支返回 None —— 不误伤，而是**由第 3 节既有 `predicate_fault` + 「谓词不可读 → fail-closed」兜住**。

### 4.4 B8 — 自选路径族（#107）

旧：`runAllTests` 必须调用 `startAllTests`，只对 `startAllTests` 体内点验 persist/应用/接线。
新（`auto_select_bodies`）：**从树派生**所有体内写 `useCustomMirror[…] = true` 的函数（`runAllTests` 的孪生 `startAllTests`、单类 `runTest`、`retryApkWorldWithMirrorFallback` 全在内），逐个跑同一套判据。删掉 `runTest` 的 apply 循环 → 「never applies the mirror it selected」；把循环塞进 `if (false)` → 「unreachable」；`autoApplied` 在循环之后才填 → 既有 `apply_wiring_problem` 红。

### 4.5 B9 — 点击入口分派（#106）

旧：线程面只判 `runAllTests`/`startAllTests`/`retryApkWorld`，`selectMirror`（行 onClick）从未被点验。
新（`builder_spans` + `io_thread_problem`）：从代码里派生**协程构建器尾随 lambda 的区间**（`launch`/`async`/`withContext`/`coroutineScope`，并区分 `Dispatchers.Main`），然后要求 `selectMirror` 里每一处 `applyMirror(`/`restoreOfficial(` 站点：①可达（`dead_context`）②不在 Main 块 / `withContext(Dispatchers.Main)` 里 ③落在某个分派块内——且**至少要有一处**（删掉也红）。`scope.launch { withContext(Dispatchers.IO) { … } }` 这种正当写法绿。

### 4.6 B10 — 被调方真的写（#108）

旧：只判调用点（2/3/5/7 号臂全部在调用侧），被调方把写删掉、照旧 log「Applied mirror」，两门全绿。
新（`apply_write_problem` + `derived_from`）：`applyMirror` 体内必须存在**可达**的 `writeText(...)`，且其第一个实参
① 不是纯字面量（`writeText("")` 不算）；
② 经 ≤2 跳**本地绑定** + 任意层**接收者剥离**（`content.trim()` / `payload[0]`）后派生自 `mirror` / `category`。

刻意做成**名字无关**：判据是「写出去的字节是否携带所选镜像」，不是「那个局部叫 `content`」——自证臂 `R6c`（重命名成 `payload`→`text` 两跳）专门守这条，`R6d`（写常量）守另一端。

## 5. 四层证据

### L1 — 两门自证 + 真树绿

```
$ python3 scripts/scan/mirror_category_route_check.py .        → MIRROR_CATEGORY_ROUTE_CHECK=PASS (rc=0)
$ python3 scripts/scan/mirror_category_route_check.py --selftest
  MIRROR_CATEGORY_ROUTE_SELFTEST=PASS (24 arms)      # 16 → 24，新增 R1/R1b/R1c/R2/R2b/R3/R3b/R3c
$ python3 scripts/scan/mirror_vm_thread_check.py .             → MIRROR_VM_THREAD_CHECK=PASS (rc=0)
$ python3 scripts/scan/mirror_vm_thread_check.py --selftest
  MIRROR_VM_THREAD_SELFTEST=PASS (36 arms)           # 27 → 36，新增 R4/R4b/R4c/R5/R5b/R6/R6b/R6c/R6d
```

新增臂里 3 条是**绿臂**（正当拼写不许误伤）：`R1c` 一跳绑定转发、`R6b` `content.trim()`、`R6c` 重命名两跳、`R4c` `withContext(Dispatchers.IO)` 分派。

### L2 — 见证反攻（见 §3）

6/6 BLIND → 6/6 CAUGHT，每条红在**自己那条判据**上（`probe/t06-post.json` 存了整段 stdout），`restored 6/6`。

### L3 — 全量门（逐门独立进程）

`probe/t06_gate_loop.py`（每门一个子进程 + ENOSYS 重试 3 次 + 0.3s 错开）：

```
gates with rc=0: 109 of 109      (scripts/scan/*_check.py + *_guard.py，跳过 _kotlin_*)
missing: []
另跑两门特例：process_logging_gate.py rc=0 · turn_tail_finalize_order.py rc=0
tts_voice_and_sanitizer_check.py / calendar_events_nullable_predicate_check.py 用 --root . 形态 rc=0
```

**沙箱事实（第 N 次同族，如实登记）**：`scan.sh` 整份、`gate_run.sh --full`、无重试的逐门循环各被 `OSError: [Errno 38] Function not implemented` 打死一次（三次尝试分别在 ~20、~23 门处整壳被杀，`/bin/sh: can't create /tmp/.minis_cwd_*` 同现）。健康路径 = 每批 ≤20 门 + 每门重试 + 错开启动（本次 8 批跑完 109/109）。14 条被标成 `SANDBOX` 的记录已全部重跑为 rc=0，`probe/t06-gates.jsonl` 里可逐条复核。

`test_scan.py`（ALL 344）本地后台跑：见 §6，若本地跑不通 / 被打断则**交 CI 承担**。

### L4 — CI

见 §6。

## 6. CI

（回填）

## 7. 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

- **R1（A5）行身份只判「同一表达式」，不判「每行不同」**：`MirrorCategory.entries.first()` 这类同一表达式传给所有行会绿。
  触发条件：渲染代码改成「所有行共用一个 category 求值」。
  触发后方案：把 `category =` 的实参与本行的循环变量/索引对齐（`for (x in entries)` 的 `x`），加「同一调用点内实参必须引用循环变量」判据。
  搁置理由：实测生产/夹具都只有一处行构造且直接传循环变量，加这条判据当前不改变任何 verdict，属装置过剩。
- **R2（A6）只覆盖三类 category 作用域读**：`results[…]` / `results.get(…)` / `MirrorCatalog.*(…)`。`vm.fastest(category)` 之类新 API 的读不在判据内。
  触发条件：详情页改用别的数据源 API（出现新的「按类别取数据」函数）。
  触发后方案：把 `speedFor(`/`fastestFor(` 一类命名收进 `detail_body_problems` 的读枚举，或改为「体内任何以 `category` 为实参的调用，其父接收者必须是 vm/catalog」。
  搁置理由：当前只有这两个数据源；扩判据没有可判红的样本，属预支。
- **R3（A3b）谓词求值只建模可归约的叶子**：`it.key == key.normalize()` 之类会得到 None（不判红也不判绿）。
  触发条件：`fromKey` 的谓词引入自定义归一函数。
  触发后方案：在 `_eval_leaf` 增加「未知函数一律 None → 由 `firstOrNull` 可读性 fail-closed 兜住」的显式注释 + 一条 `--selftest` 臂固化该契约；若真出现归一函数，改判「两侧必须同构地调用同一个归一函数」。
  搁置理由：未知形态静默为 None（不误伤），而「谓词不可读」已被既有 fail-closed 覆盖，风险只落在「读得出但归约不了」这一窄带。
- **R4（B9）分派识别只认协程构建器**：`Executor` / `HandlerThread` / `invokeOnCompletion` 等非协程分派不识别（会被判成「inline」→ 红，方向安全但不是误判为零）。
  触发条件：`selectMirror` 改用非协程线程池写 rootfs。
  触发后方案：`builder_spans` 增加 `executor.execute { … }` 一类形态；或改为「站点必须在 suspend fun 或被识别构建器内，否则红」并附白名单。
  搁置理由：本仓 rootfs 写全部走协程；非协程分派会**判红**（fail-safe），不会静默放行，故不急。
- **R5（B8）自选路径的派生锚是 `useCustomMirror[…] = true`**：若某路径绕过这个 map 直接写 prefs（`prefs.edit().putBoolean("useCustom_…")`），派生不到、不判。
  触发条件：新增/改写自选路径且不经 `useCustomMirror`。
  触发后方案：派生锚扩成「写 `useCustomMirror` ∨ 写含 `useCustom` 字样的 prefs 键」，并把 `persist(` 换成「任何持久化调用」。
  搁置理由：`persist()` 是唯一持久化入口（其它调用点都走它），现状派生集合已等于事实集合。
- **R6（共享层，交主会话，不改共享模块）**：`_kotlin_valueflow` 无「局部标识符绑定回溯」原语（`value_sources('content')` 只回 `['content']`）。本支在两门里各写了一份 `_ident_sources` / `_decl_rhs + derived_from`（后者含接收者剥离与跳数预算）。
  触发条件：第三支线也需要「绑定的右值」。
  触发后方案：上收为 `_kotlin_valueflow.binding_rhs(code, ident, depth=1)` 与 `derived_from(code, expr, roots, hops=2)`，两门删本地实现。
  搁置理由：任务书 §2.2 明令不许改共享模块；且本支持久价值在于判据形态，先留两份实现观察第三份是否出现（第 3 次 = 上收的触发点）。

## 8. 复现命令

```sh
cd /var/minis/shared/work/meta-audit-v5/round3b/wt/t06

# L1 自证 + 真树
python3 scripts/scan/mirror_category_route_check.py . ; echo rc=$?
python3 scripts/scan/mirror_category_route_check.py --selftest | tail -2
python3 scripts/scan/mirror_vm_thread_check.py . ; echo rc=$?
python3 scripts/scan/mirror_vm_thread_check.py --selftest | tail -2

# L2 见证重放（修前 / 修后）
python3 probe/t06_replay.py            # → probe/t06-pre.json
python3 probe/t06_replay.py --post     # → probe/t06-post.json（6/6 CAUGHT，restored 6/6）

# L3 全量门（逐门独立进程，幂等补跑）
python3 probe/t06_gate_loop.py --count 20     # 反复执行直到 "ran 0 gates"
python3 - <<'PY'
import json, glob, os
recs=[json.loads(l) for l in open("probe/t06-gates.jsonl")]
ok={r["gate"] for r in recs if r["rc"]==0}
alls={os.path.basename(p) for p in glob.glob("scripts/scan/*_check.py")+glob.glob("scripts/scan/*_guard.py") if not os.path.basename(p).startswith("_")}
print(len(ok), "of", len(alls), "missing:", sorted(alls-ok))
PY
python3 scripts/scan/process_logging_gate.py . ; echo rc=$?
python3 scripts/scan/turn_tail_finalize_order.py . ; echo rc=$?

# L4 CI
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-dispatch --branch fix/r3-mirror-route
sh /var/minis/skills/github-ops/scripts/gh_sync.sh gh-actions-runs --branch fix/r3-mirror-route
```
