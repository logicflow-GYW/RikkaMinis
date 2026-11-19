# FIX-R2-1119-B — sandbox/offload 七条

## 1 结论速览

按任务书顺序完成 F-14 → F-03 → F-07 → F-08 → F-15 → F-16 → F-13，七条都有独立实现提交、回归测试和变异负控。分支保留，不合 main。

| ID | 先证 | 最小修法 | 证据与阴性对照 |
|---|---|---|---|
| F-14 | 原截图默认 getExternalFilesDir；帮助也写死外部路径 | 会话截图写 app 内部 session attachments，返回可供同会话 shell 使用的 guest 路径；无会话用私有 cache 并明确 inline 取回；初始化清理旧外部目录并清理过期私有截图 | 3 条回归；旧目录 no-follow 清理保留链接外目标；2 个变异臂 RED；原 inline 分支不变 |
| F-03 | 先造 6 GiB 声明的稀疏 ustar：实际分配仅 16,384 B；基线引擎验证接受；从未解压该归档 | 两遍引擎复用 PayloadSizePolicy.MAX_FILE_BYTES（67,108,864 B）；声明预算 + reader 实际 copy/skip/metadata 计数；硬链接落盘复制计费；超界给出 byte budget 错误 | 新 10 条 + 既有 39 条通过；3 个变异臂 RED；等于边界接受、超 1 B 拒绝；正常 hello 内容不变 |
| F-07 | 原脚本只匹配 mount 本身/子项，不匹配祖先 | rm/rmdir/mv 的所选目标增加祖先和根目录拒绝；保留 cp/ln 等读/创建行为 | 新 4 条 shell 行为测试 + 原 11 条；1 个变异臂 RED；可写兄弟深层路径放行，locked mount 内深层路径仍拒绝 |
| F-08 | 原三入口不共锁，extractTar 块读取无取消检查 | singleton manager 的同一 Mutex 串行 install/reset/repair/targeted restore；Locked 实现内部直调；header、payload、skip 循环接 checkActive，生产调用传 coroutine ensureActive | 新 3 条回归；本地实际 wrapper 并发 2 条通过；3 个变异臂 RED；取消在第二次 payload read 后退出、未写完整条目 |
| F-15 | reader append 与 5 处 toString 快照无共同同步 | callback 独立 outputLock 同步 append/snapshot/truncated 读取；输出私有，5 处改 snapshotOutput | 接线 1 条 + 本地 10,000 次快照压力/Unicode 2 条；2 个变异臂 RED；正常内容逐字相同 |
| F-16 | passthrough 和 standard 两处 dispatch 省略 apiKey | 两处显式 apiKey = WorkerKeyHandoff.keyFor(context, instance.id) | 两处 wiring 各 1 条 + key 优先于 stale prefs 1 条；2 个变异臂 RED；不改 worker 协议/回退 |
| F-13 | 原 filesDir/cacheDir 统计使用 walkTopDown | 默认不 FOLLOW_LINKS 的 walkFileTree；复用 fastio entry 上限和 SkillTreeGuard.MAX_DEPTH；partial/visited/errors 暴露超界或读取失败 | 4 条（链接环/正常大小/entry/depth）；3 个变异臂 RED；正常目录 10 B 保持 10 B |

## 2 环境与基线

- worktree：`/var/minis/shared/work/r2rest/sandbox`
- 分支：`fix/r2-sandbox-1119`
- 基线：`8771018e`
- 代码绿节点：`e34e10078f24804d8dddd6048aefeb83d6cd2f1f`
- 生产改动限定于任务书允许的 8 个文件；其余为 sandbox/offload 对应测试。`scripts/scan/**`、provider、backup、ui、data、browser 无文件改动。
- 本地不跑全量，使用单个 JVM 顺序执行；最终定向入口 `sandbox-jvm/run_target.py` 的编译和运行均 nice -n 10。
- 最小改动阶梯：F-14/F-03 复用既有 session 路径、TTL 和 payload ceiling；F-07 复用原 wrapper；F-08/F-15 采用 Mutex/synchronized；F-13 采用标准 NIO walkFileTree；未加依赖或配置开关。

## 3 逐条先证与实现

### F-14：私有截图与可读取路径

原 `uiScreenshot` 写共享外部存储，返回 host path。直接改成私有 cache 会导致 PRoot 无法读取 host cache 路径，因此将 request.sessionId 传入 uiSub/uiScreenshot，优先写 `/var/minis/attachments/a11y_screenshots` 的当前会话内部宿主位置，并把 guest 路径返回模型。无会话分支明确 host cache 不暴露给 shell，应使用 --inline。

Handler 初始化时删除旧 external screenshot tree（NIO 不跟链接），并复用现有 24h `pruneShareDir` 清理 cache 和保留会话的截图目录。会话删除本身回收 attachments。未新增 TTL 数值。帮助文本同步改为私有 session/cache。`android.graphics.Path` 与 NIO Path 冲突用 `NioPath` import alias 消除。

实现提交：`9cc14764685c3961fc7e54b259071dbf08ffd02f`。

### F-03：先造 archive，后做预算

复现文件：`/var/minis/shared/work/r2rest/sandbox-jvm/f03-declared-6g-sparse.tar`。逻辑长度 6,442,452,480 B，实际分配 16,384 B，header 声明 payload 6,442,450,944 B。基线 `8771018e` FastioTar 真 JVM 探针接受该归档（PRE 1/1）；未调用提取，也未生成一个真实 6 GiB 输出文件。

任务书举例 10 GiB，但引擎不接受 base-256/PAX size override；plain ustar 11 位八进制字段最大 8 GiB−1，直接写 10 GiB 会构造坏头。故独立构造器校验 octal 宽度，采用合法 6 GiB 声明；POST 的小 stream 在 header 后拒绝任何 payload read，确保预算拒绝来自头部而非读到 EOF。

两遍引擎默认都引用既有 `PayloadSizePolicy.MAX_FILE_BYTES`，没有新数值常量或第二份 CLI cap。所有 member payload（包括跳过类型、GNU/PAX 元数据）受 reader 累计限制；regular 文件和 hardlink materialisation 受 extraction/validation 的同一预算。copyData/skipData/readRaw 计真实读取，copy 在 sink.write 前收费。hardlink 的 0 payload 不再意味着 0 磁盘费用。错误通过既有 unsafe_archive/errors 输出形态返回。

这是新增边界：超过现有 64 MiB ceiling 的整个 archive 会被拒绝，正常范围内的格式/内容保持不变；未宣称任意大小合法大归档不受影响。

实现提交：`eafe8bb271260db00e1d8a44316377dfa298dc44`。

### F-07：祖先拒绝与任务书阴性对照澄清

匹配方向增加 `prefix` 位于 `resolved/…`；根 `/` 单独处理，且只对 destructive rm/rmdir/mv 开启。原 mount exact/descendant 拒绝保留，不扩大到普通可写兄弟。

任务书“挂载点内部深层文件必须放行”若指 locked mount 内文件，会直接取消既有只读保护，故不按该字面执行：**locked mount 的子文件继续拒绝**；阴性对照使用 writable mount 兄弟目录/普通 workspace 深层文件，必须放行。测试 delegate 永远替换为 echo，即使变异删除守卫也不运行真实 rm/mv。

实现提交：`95867eb2552097e53c306a40fbad115cde4bbfec`。

### F-08：不可重入锁与取消

installIfNeeded/reset/autoRepair/restoreCriticalFromAssets 公有入口共用 manager 的一把 lifecycleMutex。内部 reset→install、repair→targeted restore/reset 走 Locked 实现，避免不可重入死锁。现有 before/after hooks 不改变，未拓宽到 package installation 的其他入口。

取消回调在 header、regular payload、skipFully/readFully 循环逐块调用；生产 asset 解压三个分支传入 currentCoroutineContext()::ensureActive。targeted restore 的 CancellationException 原样抛，不转换成 false。

既有 SysInfo 门只追踪非 private member 的传递调用；为保持其真实派生能力，在不碰扫描器的条件下将 Locked 实现标为 internal，并在声明注明“caller MUST hold lifecycleMutex”。全仓调用点检查仅 RootfsManager 本文件调用这些实现。不是通过塞无效写操作或注释骗门。

实现提交：`987f2718b51d7836465c0f8f32059feb4d89e950`；门/旧测试兼容提交 `ee2d41a4`。

### F-15：输出快照

append 和 snapshot 共用 outputLock，StringBuilder 改私有，EOF/completion/stall/timeout/stop 五个调用点统一取快照。completeOnce 的原有 CAS 不改，避免重新引入多次 resume。字节预算仍为既有 IncrementalUtf8Budget，只有共享访问加锁。

实现提交：`3a63490dd0499c443aa5a554703dcf7accc49a8b`；旧 timeout 形状断言适配提交 `00634ffd`。

### F-16：两条派发边

仅两处 dispatch 调用增加显式 WorkerKeyHandoff.keyFor 参数。key 不写日志/文件，不重新解析 request JSON，不修改 provider/worker 文件。正常 keyless/OAuth 保留现有 fallback。

实现提交：`408c449bbe7db516de194cb5b7f555947a102026`；原透传 boundary 形状断言适配提交 `e34e1007`。

### F-13：不跟链接与 partial 可观测

NIO 默认 no-follow 遍历，普通文件累加 attrs.size，目录链接既不递归也不重复计目标。entry ceiling 引用既有 `FastioOffloadHandler.DEFAULT_MAX_ENTRIES`（只将该常量的 private 改 internal，无第二份数值）；depth 引用 `SkillTreeGuard.MAX_DEPTH`（只读引用，未修改 data）。达到 depth/entry 或读取失败时输出 `app_data_partial` / `app_cache_partial` 等额外字段；正常输出原字段与格式不变。

实现提交：`e28898579453d6c7cf331c2292ac72baa99d4b5d`。

## 4 装置与变异臂

装置均位于 worktree 外 `/var/minis/shared/work/r2rest/sandbox-jvm/`，避免报告/测试脚本混入生产提交。`mutate_all.py` 对实际生产文件做单点替换，finally 恢复原始 bytes，逐字节 hash 核对；编译错误不计 RED。最终 `mutations.json` 为 16/16 RED、0 compile-error、0 blind。

| 修复 | 变异臂 |
|---|---|
| F-14 | 去掉会话身份；去掉旧外部截图清理（2） |
| F-03 | 删除 validation charge；删除 extraction charge；删除 copy 实际计数（3） |
| F-07 | 删除祖先匹配（1） |
| F-08 | reset wrapper 不加锁；payload 不查取消；skip 不查取消（3） |
| F-15 | append 不加锁；snapshot 不加锁（2） |
| F-16 | 透传不带 key；标准不带 key（2） |
| F-13 | 开启 FOLLOW_LINKS；去 entry cap；去 depth partial 信号（3） |

负控自身修正：最初 F-03 删除 extraction declared charge 的臂仍绿，是 reader 前置预算替它拒绝了大 entry。新增 hardlink 复制超界用例后，该臂可在 reader payload 未超限、但第二个落盘副本超界的形态下独立观察到，现为 RED。没有把“另一道守卫仍挡住”伪记为本臂通过。

## 5 本地定向验证

最终七组顺序执行：3 + 49 + 15 + 3 + 1 + 3 + 4 = **78** 条通过；旧 L2S/rebuild/timeout/透传 boundary 兼容用例 **22** 条通过。额外 harness：实际生成 wrapper 的双 reset/取消释放锁 **2** 条、实际 callback 的 10,000 快照/Unicode **2** 条通过。

- tar 既有正常格式测试 39/39（symlink、GNU/PAX、long UTF-8 name、hardlink、occupied-target 等）。
- mount 既有 shell 测试 11/11；新增 ancestor/root 只跑 harmless delegate。
- 定向 SysInfo 门通过，未在设备跑全量 scan 或 Android/Gradle 全量构建。
- git diff --check 通过；禁止目录差异清单为空；工作树在报告前干净。
- 首轮 CI 揭示的 5 个旧断言逐项更新其真实目标，不放宽为 always-pass；本地相关 22 条重新通过。

## 6 范围与未实测项

- 未在真机安装本分支 APK，未拍摄任何真实银行/验证码屏幕；F-14 私有目录/路径返回为 JVM+接线证据，Android end-to-end 尚未验证。
- RootfsManager 真实 Android reset 不在设备执行；本地串行证据是生产 wrapper 的提取件+确定性临界段，取消证据是生产 extractTar。APK/Gradle 编译由 CI 作证。
- F-16 未在真机并发流式期间轮换真实凭据；证据为两条 dispatch wiring + 既有 key handoff 的选择语义，不声称真实 stale_key_cache 日志已消失。
- F-07 wrapper 的 lexical/best-effort 原边界保持；未冒充操作系统级只读挂载。
- 大归档一直未提取。稀疏 fixture 逻辑很大但只占 16 KiB；避免将它打包成普通非 sparse artifact。

## 7 CI 证据

代码 SHA：`e34e10078f24804d8dddd6048aefeb83d6cd2f1f`。`build-apk.yml` workflow_dispatch：run **44506021754**（编号 **3351**），head_sha 与本地/远端 tip 逐字符一致。

- unit-tests：**10,225 PASSED，0 真 FAILED**。
- 七个新增测试类：3 / 10 / 4 / 3 / 1 / 3 / 4，合计 **28 PASSED，0 FAILED，0 SKIPPED**。
- 旧关键类：FastioTar 39、MountGuardScriptExec 11、L2sBackingDirWiring 3、SandboxRootfsRebuildShape 7、TrackedShellDecisionShape 11、ModelUsePassthrough 17 均 PASSED。
- scan-gate：**115 passed, 0 failed**；日志明确 `scripts/scan/** untouched in this push — skipping the fixture self-test`，此次绿不含“扫描器夹具跑过”证据。
- lint / build-apk / publish 五 job 全 success；publish 的 rolling-tag 和 release 两步因非 main 明确 skipped，未污染 release。
- 首轮 44503755208（3350）编译和 APK 绿，单测/scan 红；原因与修正已在各条和第 5 节说明。不是依赖/网络环境红。

## 8 处置

已到“代码绿 + 报告以外差异为零”的停止节点：报告单独提交，报告提交之后**不再 dispatch CI**。main 未合并、未推送、未清理他人 worktree 或分支。

复跑定向证据：`python3 /var/minis/shared/work/r2rest/sandbox-jvm/verify_final.py`；变异证据：同目录 `mutations.json` / `Fxx-Mx.log`；CI 原始 JSON/日志：同目录 `ci/`。
