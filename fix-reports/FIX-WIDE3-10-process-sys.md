# FIX-WIDE3-10 — process / sysinfo 五门判据加固

**分支** `fix/meta-wide3-10-process-sys` · **基线** main `6b6c918f`（wide-1024 第二波收口后）
**范围** 5 门 / 15 条外部见证 · **生产代码 0 行改动**（`.kt` 零触碰，只改 `scripts/scan/` 下 5 个门脚本 + 本报告）
**冻结面** `scripts/scan/scan.sh`、`scripts/scan/test_scan.py` 零触碰（门 112 道 / 夹具 344 条应保持不变）

---

## 1. 五门与判据（加固后）

| 门 | 本轮修的三件事 | 自证臂 |
|---|---|---|
| `xproc_boundary_check.py` | 死分支写入 / 常量值写入 / 注释里的括号截断构造参数 / 注释里的键名冒充 worker 读取 / 单一构造点完整性 | **7**（原无自证，新增 `--selftest`） |
| `sysinfo_state_thread_check.py` | 空锁块 + 常量假守卫不算持有锁 / `.also` 丢弃副本 / 死汇聚调用 | **35**（原 32，+3） |
| `liveness_static_guard.py` | 参数名不是 `category` 就打不进普查 / 方法体死掉仍算活 / 注释里的探针冒充声明 | **8**（原 4，+4：A2/D2/B2/E2） |
| `process_logging_gate.py` | 允许表是 Python 常量，从不打开 MinisApp.kt / 转义注释跨元素借用 / 单引号与 `=` 空格绕过属性正则 | **8**（原 3，+5） |
| `debug_leak_guard.py` | 允许表整文件跳过 / `"/*"` 字符串伪造块注释 / 死 helper + 活流式读 | **25**（原 21，+4） |

三道门（xproc / process_logging / liveness）的判据从「词/字面量在场」升级为「**效果发生**」；
两道门（sysinfo / debug_leak）在原有语义上补 **dataflow** 与 **parser-death** 两条轴。

## 2. 失效轴分布

| 轴 | 条数 | 门 |
|---|---|---|
| **dataflow**（调用了，但结果/决定被丢掉） | 6 | xproc C2 · sysinfo D · sysinfo E · liveness C · debug R8 · process C1 |
| **parser-death**（自己解析器被构造绕过） | 4 | xproc C1 · liveness B · process C3 · debug C-SRC |
| **anchor**（锚在签名/声明而断言在别处） | 3 | xproc C3 · process C2 · debug C-SRC |
| **scope**（扫描面写死） | 2 | liveness A · debug C-SRC |

一句话：**五门都是「某物存在」型判据**——`synchronized` 在行上、`put("键"` 在文件里、
`AppLogger.init` 在允许表注释里、`readFileSegment(` 在函数体里、`fun X(category:` 在源码里。
存在不等于生效；本轮把每条都换成「这个存在是否**决定**了它该决定的东西」。

## 3. 逐条见证（15 条 → 全部 CAUGHT）

### 3.1 `xproc_boundary_check.py`（跨进程边界完整性）

| # | 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| C1 | `LLMModel(id = ..., // ctor closed here: )` | parser-death | `extract_data_class_fields` 先在**原始文本**上做括号计数：注释里的 `)` 提前闭合构造参数表，`11 fields → 4 fields`，留在后面的字段永远看不见 | 注释**先**被字面量感知地抹成等长空白，再做括号/逗号深度切分；空参数表/找不到类 = 违规（fail-closed，绝不静默变干净） |
| C2 | `if (false) model.maxOutputTokens?.let { put("max_output_tokens", it) }` | dataflow | `extract_put_keys` 只在全文正则 `put("键"`，死分支里也算 | `put_sites()` 逐调用点判活（`dead_guarded()`：同语句常量假条件、或所在 `{}` 块被常量假守卫）；并要求值**派生自字段**（值不是裸字面量，且 `model.<field>` / `?.let { it }` 的接收者在同一表达式窗口内） |
| C3 | worker 把 `b.getInt("max_output_tokens")` 改成 `maxOutputTokens = null, // "max_output_tokens" ignored` | anchor | 反向检查只问「键名有没有在 worker 文本里出现过」，注释与 `= null` 都满足 | worker 源先抹注释；键必须出现在**读调用**（`get*/opt*/has/is*`，含 `getString(obj,"k")` 两参形态）且该处是活的；并且——worker 里**每一个** `LLMModel(...)` 构造点都要带这个键（streaming / 非 streaming 两条重建路径，一条退化就是缺陷） |

### 3.2 `sysinfo_state_thread_check.py`（系统通知窗）

| # | 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| C | `if (false) synchronized(lock) {}; publishLocked(publish)` | parser-death | B/C 两条规则的逃生口是子串 `"synchronized" in src`：**空块**保留了这个词，互斥一点没留 | `live_sync_bodies()` 算出每个 `synchronized(...) { }` 的**字符区间**，要求块体非空且不被常量假守卫包住；访问/发布点的**偏移**必须落在区间内。监视器表达式本身（`synchronized(lock)` 里的 `lock`）单独豁免 |
| D | `blocks = blocks.also { blocks.toList() }` | dataflow | 判据是 `re.search("blocks.(toList\|toMutableList)\(")`——`.also` 返回接收者（活列表），而 `blocks.clear()` 就在两行之后；且 `[^,)]+` 在第一个 `)` 处截断，把值读成 `blocks.also { blocks.toList(` | 用括号/花括号配平取**完整**值表达式，`_is_copy_of_blocks()` 要求整体就是 `blocks.toList()`；`.also/.apply/.let/.run` 具名指出「副本被丢弃」 |
| E | `if (false) sysInfoCoalescer.append(...)` | dataflow | E 规则只问函数体里有没有 `\w*[Cc]oalescer\.\w+(` | 每个调用点判活；函数体括号配平（表达式体单独处理，不再把下一个声明的 `{` 当体） |

### 3.3 `liveness_static_guard.py`（日志存活普查）

| # | 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| A | 新增 `fun trace(cat: String, message: String) { log("TRACE", cat, message) }` | scope | 发现式是 `fun (\w+)\(\s*category\s*:`——**参数名**写死；位置参数调用方完全不受改名影响，于是这条真实 API 对 A/C 两条规则**结构性不可见** | 形态判定：`object` 内**非 private**、恰好两个 `String` 参数——参数名无关 |
| C | `fun info(...) { if (false) log(...) }` | dataflow | A（签名在）、C（调用点计数不变）双绿，实际一条日志都不发 | 新增 D 规则：每个被发现的日志方法，函数体必须含**活的**排放（`log(` 或 debug 通道 `enqueue(`）；只在死码里 = 红 |
| B | `// val ghost = "step=<RARE>"`（键只活在注释里） | parser-death | 语料包含注释，注释里的声明也算存活；且注释里的 `AppLogger.x("Cat", …)` 让 `raw` 与 `enumerated` **在注释上**达成伪一致 | `corpus_of()` 抹注释（保留字符串——RARE 键就住字面量里），并用**同口径的镜像树**喂 `liveness_audit.declared()`，两侧对「什么算一次调用」终于一致（真树里那一句 `// Routed through AppLogger.warning()` 就是靠镜像消掉的）；另加 E 规则：只活在注释里的探针必须报出来（真树 0 处） |

### 3.4 `process_logging_gate.py`（隔离进程日志覆盖）

| # | 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| C1 | MinisApp `:modelservice` 分支改 `if (false) AppLogger.init(this)` | dataflow（registry） | `PROCESSES_WITH_LOGGING = {":modelservice"}` 是 Python 常量 + 注释「wired in MinisApp.onCreate — d09f3ad」，门**只打开 AndroidManifest.xml**；2026-09-18 worker 无日志的原始事故被逐字重建仍 ✅ | 允许表降级为**指针**：每个声明为已接线的进程，都要在 `MinisApp.kt` 里找到 `if (<predicate>()) {` 分支，且分支体内有**活的** `AppLogger.init(`。文件/谓词/分支找不到 = 违规（fail-closed） |
| C2 | 在已豁免的 `<service>` 正下方加一个新 `<service :unloggedextra>` | anchor | 转义判据向前看**固定 12 行**，新元素借走上一个元素的 `logging-ok:`；且 `lines.index(line)` 取第一个相同行，行号会串 | `escape_window()`：向后穿过**连续**注释块，遇到任何非注释字符（含上一个元素的 `>`）即停——注释不能跨元素边界 |
| C3 | `android:process=':unloggedfgs'` / `android:process = ":unloggedfgs"` | parser-death | 正则写死 `android:process="…"`，单引号与 `=` 两侧空格都漏（AAPT2 都接受） | `android:process\s*=\s*(?:"…"\|'…')` |

### 3.5 `debug_leak_guard.py`（调试面泄漏）

| # | 见证 | 轴 | 旧判据为何瞎 | 新判据 |
|---|---|---|---|---|
| C-SRC① | MinisApp `if (true || BuildConfig.DEBUG) { DebugServer(this).start() }` | scope | `ALLOWED_FILES` 里有的文件**整个跳过**——恰恰包括这个 token-free 回环服务器的唯一启动点 | 允许表不再免检：文件内每个 debug 引用必须落在**活的** `if (BuildConfig.DEBUG)` 守卫内，或命中 `UNGUARDED_SYMBOLS`（逐文件+逐符号的「release 惰性」清单，含理由）；`literal_decides()` 判 `true \|\|` / `\|\| true` / `false &&` / `&& false` / `!false` 这类「读了标志但没让标志做决定」的短路 |
| C-SRC② | ChatViewModel 里 `"/*"` … `"*/"` 两个字符串夹住 `DebugMethodRegistry.discover()` | parser-death | `BLOCK_COMMENT_RE = /\*.*?\*/` 跑在原始文本上：**字符串**里的 `/*` 开了块注释，到后面的 `*/` 字符串才闭合，中间上百行真代码被判为注释 | 字面量感知扫描（`str`/`chr`/`raw` 三态），抹注释后长度逐字节不变；`code_view()` 再抹字面量内容供结构判定 |
| R8 | `val bytes = if (false) readFileSegment(...) else hostFile.inputStream().use { it.skip(offset); it.readBytes() }` | dataflow | 只问「函数体里有没有 `readFileSegment(`」和「全文件有没有 `readNBytes(`」——helper 调用留在死分支里算数，活着的无界流式读没人管 | 三条一起判：全文件不得有**活的** `.readNBytes(`；`handleReadFile` 必须产出 RandomAccessFile 或**活的** helper 调用（helper 体内要有 RandomAccessFile）；函数体内不得有**活的**流式读惯用法（`.inputStream(` / `.readBytes(` / `.readNBytes(` / `.skip(`）。缺 `handleReadFile` / helper 未定义 = 锚漂移 = 红 |

## 4. 修法（代码层）

全部改动集中在 5 个文件里的**判据函数**，并各自补齐 `dead_guarded()` / 字面量感知剥离 / 括号配平取参三件套（每个门自带一份，不新增公共模块——五门都各自 CI 独立可跑，不引入 import 耦合）：

- `xproc_boundary_check.py`：`blank(text, strings=)` 双视图（同长度、同偏移）、`stmt_start()`（深度 0 换行也算语句边界）、`dead_guarded()`、`paren_end()`、`put_sites()`、`derives_field()`、`key_read_sites()`、`construction_spans()`；`extract_data_class_fields()` 改为先抹注释再切分；worker / dispatcher 文件缺失即红。
- `sysinfo_state_thread_check.py`：`live_sync_bodies()` / `in_live_sync()` / `dead_guarded()` / `_line_starts()` + `bisect` 偏移→行号；B/C 改成偏移归属；`window_ctor_args()` 取完整实参 + `_split_args()` / `_named_arg()` / `_is_copy_of_blocks()`；E 改偏移区间 + 表达式体分支。
- `liveness_static_guard.py`：`blank_comment()`（保留字符串内容）、`dead_guarded()`、`applogger_log_methods()`（形态判定）、`emission_problems()`（D）、`corpus_of()` 抹注释、`stripped_mirror()` 让普查与计数同口径、`commented_probe_problems()`（E）。
- `process_logging_gate.py`：`PROCESSES_WITH_LOGGING` 降级为指针 + `wiring_problems()` 回读 MinisApp.kt（`_brace_end` / `dead_guarded`）、`escape_window()` 注释块边界、`PROCESS_ATTR_RE` 双引号。
- `debug_leak_guard.py`：`blank(text, strings=)`（长度守恒）、`debug_guarded()` + `literal_decides()`、`UNGUARDED_SYMBOLS` 逐符号白名单、`_fun_span()` / `STREAM_READ_RE` 重写 `scan_api26_reads()`、`scan_source()` 不再整文件跳过 + import 行单独判定 + 走空即 fail-closed。

**判定「未被误伤」的机械判据**：五门在真树上全部 rc=0（见 §5 L1），
并且各门原有自证臂一个不落（sysinfo 32→35、liveness 4→8、process 3→8、debug 21→25、xproc 0→7）。

## 5. 四层证据

### L1 — 真树 + 自证臂

```
python3 scripts/scan/xproc_boundary_check.py        .   → rc=0   （LLMModel 11 fields OK / ProviderInstance 15 fields OK）
python3 scripts/scan/xproc_boundary_check.py        --selftest        → XPROC-BOUNDARY SELFTEST=PASS（7 臂）
python3 scripts/scan/sysinfo_state_thread_check.py  .   → rc=0
python3 scripts/scan/sysinfo_state_thread_check.py  --selftest        → SYSINFO-STATE SELFTEST=PASS（35 臂）
python3 scripts/scan/liveness_static_guard.py       .   → rc=0
python3 scripts/scan/liveness_static_guard.py       --self-test       → 8 臂全绿
python3 scripts/scan/process_logging_gate.py        .   → rc=0   ✅ All isolated processes have an AppLogger.init decision ([':modelservice', ':toolservice'])
python3 scripts/scan/process_logging_gate.py        --self-test       → 8 案例全绿
python3 scripts/scan/debug_leak_guard.py            .   → rc=0（source + api26 两段）
python3 scripts/scan/debug_leak_guard.py            --self-test       → 25 案例全绿（API26 7 fixtures）
```

### L2 — 外部见证反攻（修复前 → 修复后）

`/var/minis/shared/work/meta-wide3/reattack.py`（打见证 → 跑门 → rc≠0 判定 → 恢复 → sha256 比对）：

| 门 | 见证 | 修复前 | 修复后 |
|---|---|---|---|
| xproc_boundary_check | C1 / C2 / C3 | STILL_BLIND ×3 | **CAUGHT ×3** |
| sysinfo_state_thread_check | C / D / E | STILL_BLIND ×3 | **CAUGHT ×3** |
| liveness_static_guard | A / C / B | STILL_BLIND ×3 | **CAUGHT ×3** |
| process_logging_gate | C1 / C2 / C3 | STILL_BLIND ×3 | **CAUGHT ×3** |
| debug_leak_guard | C-SRC ×2 / R8 | STILL_BLIND ×3 | **CAUGHT ×3** |

合计 **15/15 CAUGHT**，`restore=True`（每轮恢复后 sha256 与原文一致，工作树零残留）。

### L3 — 邻门不误伤

同批次相邻的 process/sysinfo/日志面门（`scheduled_log_dedup` / `process_logging` 邻居、
`debug` 邻居、`four_way_sync`、`scan_debt` 等）在真树上保持 rc=0 —— 见 §5 L4 的全量结果。

### L4 — 全量门禁

```
sh scripts/scan/scan.sh          → 112 passed, 0 failed
python3 scripts/scan/test_scan.py → ALL 344 CASES PASS
```

（本地读数来自 `scripts/scan/` 沙箱内运行；CI 上同样两段，见 §6。）

## 6. CI

- run：见分支 `fix/meta-wide3-10-process-sys` 最新一次 `build-apk` run（`conclusion == success`）
- `head_sha` 与本地 HEAD 逐字符一致
- 日志判读：`Summary: 112 passed, 0 failed` 恰一条 · `ALL 344 CASES PASS` 恰一条 · `e: ` 0 条 ·
  唯一 `skipped` = `Publish`（main 门控）· 含 `FAILED` 的行全部是 `PASSED` 用例名的子串

## 7. 残留（每条带 触发条件 / 触发后方案 / 搁置理由）

| # | 残留 | 触发条件 | 触发后方案 | 搁置理由 |
|---|---|---|---|---|
| R1 | xproc：`derives_field()` 用 ±320 字符**窗口**判「值派生自字段」，不建真依赖图 | 出现「键写对了但值来自另一个字段」的真 P0（值合法、语义错） | 把窗口换成 AST 级数据流（`?.let { it }` 绑定链 / 赋值链追踪） | 现有三种真实写法（直接 `model.x`、`?.let { it }`、`.let { JSONArray(it) }`）都被窗口覆盖；为假设形态上 AST 装置，装置规模远超影响面（修复门 P2） |
| R2 | xproc：`put("k", <以别的字段为参的函数>())` 只在窗口内出现正确字段名时可过 | 同上 | 同 R1 | 同上 |
| R3 | xproc：worker 侧「读形态」是名字族（`get*/opt*/has/is*`） | 新增一种读取 API（如 `bundle.key("x")` 扩展） | 扩 `read_callee` 名字族 | 目前只有 Android `Bundle`/`JSONObject` 两族；扩名字表零成本，但无证据前不该猜 |
| R4 | sysinfo D：`blocks = ArrayList(blocks)`（构造器式真拷贝）会被判红 | 未来改用构造器拷贝 | 在 `_is_copy_of_blocks()` 增加 `ArrayList(blocks)` / `buildList { addAll(blocks) }` 形态 | 当前写法唯一（`.toList()`）；反向放宽会削弱「必须整体是副本」的判据 |
| R5 | sysinfo：`live_sync_bodies()` 只认 `synchronized(...) { }` 块，不认 `withLock {}` 等库锁 | 该窗口类改用 `Mutex.withLock` | 把「持锁区」抽象成多形态（`withLock { }`、`*Locked` helper 已单独覆盖） | 该类当前是 `synchronized` + `*Locked` 混合；抽象锁形态需要先有一处实际用法 |
| R6 | liveness：日志方法发现式 = 「非 private + 两个 String 参数」 | 出现三参数或非 String 参数的日志方法（如 `fun info(tag: String, vararg args: Any?)`） | 放宽到「非 private + 首参 String」并同步 E 规则的排放判据 | 现有 4 个方法全是 `(String, String)`；放宽会在 `setEnabled(Context, Boolean)` 类工具方法上产生假阳性，需先有实际新形态 |
| R7 | liveness E：KDoc 里写**带字面量**的示例调用（`* AppLogger.info("Foo", "m")`）会被判红 | 有人在文档里写真实调用示例 | E 只对 `//` 行注释生效 / 加 `doc-ok:` 豁免 | 真树 0 处（已核）；先到先改，避免现在就引入第三个豁免通道 |
| R8 | process_logging：`PROCESSES_WITH_LOGGING` 谓词名写死（`isModelServiceProcess`） | MinisApp 重命名谓词 | 谓词名从 MinisApp 派生（`fun is\w+Process()` 匹配进程名字根） | 当前由 fail-closed 兜住（改名即红并打印「anchor drift」），不会静默失效 |
| R9 | process_logging：转义注释允许写在**标签文本内**（沿用旧夹具形态；XML 语法上不可能出现真注释） | 出现标签内伪注释 | 删除 `text[tag_start:tag_end]` 这一路的允许 | aapt2 会在编译期拒绝这种写法，线上不可达；先保留以免误伤既有夹具 |
| R10 | debug：`UNGUARDED_SYMBOLS` 是人工审计结论（逐文件+符号） | 新增一处 release 不安全的 debug 调用 | 必须走 `debug-ok:` 或加真守卫；白名单新增需附理由 | 这两处（`ChatMutationMethods` / `LLMRequestLog` 纯函数）是审计过的惰性实现，给它们加 `if (DEBUG)` 会改变 release 行为 |
| R11 | debug：`debug_guarded()` 只认 `if (BuildConfig.DEBUG)` 的块/同语句形态 | 出现 `when`/`takeIf { BuildConfig.DEBUG }` 式守卫 | 扩守卫形态 | 未命中即红（fail-closed），方向安全；扩形态等真出现再说 |
| R12 | debug api26：流式读惯用法是名字表（`inputStream/readBytes/readNBytes/skip`） | 出现 `File.readText()` 等其它无界读 | 扩名字表 | 当前 handler 只用 `RandomAccessFile`；表扩充无成本但也无证据 |

## 8. 复现命令

```sh
cd <repo>
for g in xproc_boundary_check sysinfo_state_thread_check liveness_static_guard \
         process_logging_gate debug_leak_guard; do
  python3 scripts/scan/$g.py .        || echo "GATE RED: $g"
done

python3 scripts/scan/xproc_boundary_check.py       --selftest
python3 scripts/scan/sysinfo_state_thread_check.py --selftest
python3 scripts/scan/liveness_static_guard.py      --self-test
python3 scripts/scan/process_logging_gate.py       --self-test
python3 scripts/scan/debug_leak_guard.py           --self-test

# 15 条外部见证反攻（打补丁 → 跑门 → 恢复 → sha256 比对）
python3 /var/minis/shared/work/meta-wide3/reattack.py --repo "$PWD"

# 全量
sh scripts/scan/scan.sh && python3 scripts/scan/test_scan.py
```
