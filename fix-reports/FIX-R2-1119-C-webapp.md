# FIX-R2-1119-C — WebApp 与模型进程（R2 F-17 / F-02 / F-12）

## 1 身份链

- 任务书：`/var/minis/shared/work/meta-audit/fullscan1119r2/TASK-R2C-webapp.md`；基线 `8771018e122205b7bf9db90e2a7ec83180616e3e`。
- Worktree：`/var/minis/shared/work/r2rest/webapp`；分支：`fix/r2-webapp-1119`；代码提交：`11404bab04b492cdacda095eee9b9b77a234260d`，直接父提交为上述基线。
- 停止条件：代码提交的分支 CI 成功，报告提交相对代码提交的非报告 diff 为空。保留分支，不合 `main`，报告提交后不 dispatch。

## 2 缺陷与先证

| ID | 判级 | 基线实树证据 |
|---|---|---|
| F-17 | P1 / B×Z | Manifest `:326–327` 将 `ModelExecutionService` 放在 `:modelservice`。`MinisApp.kt:251` 的 worker 分支在主进程 `networkMonitor.start(this)`（`:635`）之前返回；服务 `onCreate` 也未注册网络回调。三家 Provider 与 `ConnectionWarmer` 都使用进程本地的 `NetworkMonitor.sharedLLMConnectionPool`。主进程驱逐自己的池不能清理 worker 的连接，不论主进程的池是否为空。 |
| F-02 | P0 / D×Y | 安全模式（`MinisApp.kt:470`）和较新 DB（`:521`）在仓库赋值（`:592`）前返回。安全模式标记清掉以后，`isInitSkipped()` 仍记录本进程已跳过初始化，并非第三次独立早退。Manifest 的导出 WebApp 入口绕开 `MainActivity` 守卫，基线 `WebAppActivity.kt:178` 直接读 `lateinit`，未赋值时会抛异常。 |
| F-12 | P1 / C×Z | Manifest `:295–297` 声明 `singleInstance`；基线 `WebAppActivity.kt:169` 只在 `onCreate` 解析 ID，全类无 `onNewIntent`。系统复用 A 的实例投递 B 时，没有任何逻辑查 B 的记录或更新 WebView。 |

## 3 修法

- **F-17**：仅从 `MinisApp` 的 `:modelservice` 早退分支订阅该进程的默认网络。`onAvailable` / `onLost` 先 `evictAll()`，再调用 `ConnectionWarmer.onNetworkChanged()`；不启动完整 monitor，也不刷新沙箱 DNS。注册者与应用进程同寿命，不随请求/服务实例重复注册。保留主进程既有 monitor；注册 API 失败记录 warning，不阻断 worker 启动。
- **F-02**：冷、热入口共用加载入口。读仓库前复用 `ShareHostPolicy.mayUseAppRepositories` 的三态守卫；`runCatching` 仅圈住属性读取作为兜底，不吞掉挂起的 DAO 操作或取消。仓库不可用走现有 `renderSourceMissing(sourceSessionId = null)`；仓库有记录但文件丢失时仍把来源会话传给「在 Minis 中打开」。
- **F-12**：`onNewIntent` 先 `setIntent`，再进入相同加载路径；同一 ID 在加载中或已经打开时不再查询、加载，失败后允许重试。新 ID 取消旧查询，旧 IO 结果不能覆盖新页。成功时标题来自新记录，WebView 按 ID/文件重建，因而资源根、URL、历史不会沿用 A。换页与渲染器死亡共享按实例的幂等释放，原有全屏单一状态不变。

## 4 行为证据与负控

本地只编译定向 Kotlin/JVM 切片和测试，实得 `OK (16 tests)`。WebApp 运行真实入口、查询取消逻辑和 URL 编码器，宿主绘制由桩记录；worker 运行原启动分支、进程识别、注册/回调和完整 monitor 对照，平台注册和 DNS 写入由桩记录。OkHttp 连接池通过本地 MockWebServer 真正填入空闲连接，不是伪造计数。

| ID | 正向观测 | 负控（每臂分别编译、实际运行 JUnit） |
|---|---|---|
| F-17 | Manifest + 启动接线断言绑定 `:modelservice`；API 28+ 进程名臂、API 27 当前 PID 臂都注册 1 个 worker 回调 / 0 个完整回调。主进程为 0 个 worker 回调 / 1 个完整回调；仅主进程触发 DNS 写入桩。真实空闲连接每次 `1 → 0`，预热通知两次均看到池大小 `0`。 | 移到主进程（3 失败，worker 得到 0 注册）；删驱逐（1，池仍为 1）；删预热通知（1，通知列表为空）；在 worker 加完整 monitor（3，捕获多余注册）。 |
| F-02 | 三态分别有 `0` 次属性读取；意外未初始化返回 `null`；跳过初始化时渲染接收 `[null]`、0 个正常页；正常初始化返回原仓库。 | 删守卫（1，出现 3 次读取）；删兜底（1，`UninitializedPropertyAccessException`）；只留早退、删降级页（1，渲染记录为空）。 |
| F-12 | A→B 最后一页是 `https://appassets.androidplatform.net/My%20B.html`、`WebApp B`；重投 B 查询/加载仍各 1 次；先阻塞 A，再完成 B，最终只有 B 提交页面。 | 删暖启动转发（2，A 不切换且并发臂等不到 B）；删同 ID 去重（1，查 B 两次）；删取消（1，B 后又渲染 A）。 |

**10/10 负控按预期变红**。变异只写出树副本，从干净代码提交取源，工作树与 `HEAD` 无差异。另有两条 Android 仪器测试：模拟跳过初始化时断言降级控件真实可见；通过系统投递暖启动 Intent，检查 A/B 的 WebView URL、WebView/Activity 标题及重复点击后 JS 状态仍在。CI 仅编译，未把仪器测试计入本地通过数。

## 5 代码 CI

- 对 `11404bab04b492cdacda095eee9b9b77a234260d` 的 `build-apk.yml` 分支运行结论为 `success`，API `head_sha` 与本地及推送后的远端代码 SHA 逐字符一致。`build-apk`、`scan-gate`、`unit-tests`、`lint`、`publish` 五 job 均成功；滚动标签和 Release 发布步骤跳过。
- `unit-tests` 日志有两个 worker 用例、三个仓库守卫用例和四个既有全屏用例的 `PASSED` 行；`compileDebugAndroidTestKotlin` 成功。静态门日志实得 `Summary: 115 passed, 0 failed`、`[webapp-asset-url] PASS all 18 arms` 与隔离进程日志判据成功行；扫描脚本未动，云端按机制跳过扫描夹具自测。

## 6 残余与验证边界

- 未装包、不改本机代理或网络；未实测各机型真实 launcher 投递、Compose 渲染及 Wi-Fi/蜂窝/VPN 切换。当前 worker 进程证据来自 Manifest/启动接线和进程桩对照，不是设备 `dumpsys` 采集。
- `evictAll()` 只驱逐空闲连接，不主动中断在途请求；默认路由回调也不保证捕获系统未发出 `onAvailable` / `onLost` 的同一网络内部变化。未调整超时或主进程网络行为。

## 7 范围纪律与 F-02 同族回查

代码差异仅 2 个生产文件（`MinisApp.kt`、`webapp/WebAppActivity.kt`）与 4 个对应测试。未改 `scripts/scan/**`、`provider/**`、`sandbox/**`、`backup/**`、`ui/**`、`data/**`。实际服务文件在 `sandbox/offload`，未动；本地未跑全量，只执行定向 JVM 和两道相邻门。

代码提交后用无路径限定的 `git grep -nIw` 扫全仓两个字段：`webAppShortcutRepository` 28 行、`providerRepository` 517 行（包括文档、注释、测试和非 `MinisApp` 的同名变量；Android 生产 Kotlin 分别为 8 行、297 行）。再筛实读：

- `webAppShortcutRepository`：5 处生产属性读取。`WebAppActivity` 唯一读点已套守卫；`AddToHomeSheet` 有 3 处，只从正常聊天/文件浏览 Compose 页面进入；`ChatSessionEntryExt` 的草稿提升读取本来就在 `runCatching` 中。其余是声明、初始化和注释。
- `MinisApp.providerRepository`：`ShareReceiverActivity` 已守卫；`MainActivity` 在安全模式/跳过初始化/较新 DB 的早退之后才读。自动备份和计划任务、语音引擎、debug RPC 均由完成仓库初始化后的启动路径接入；其他同名字段/参数不是 `MinisApp` 的 `lateinit`。未发现另一个能在同样降级态启动并裸读这两个字段的入口；保留这些既有读点，不越界改动。

## 8 复现与停止判据

定向沙箱装置在本会话工作区 `/var/minis/workspace/r2c-webapp-test/`：`run.sh clean` 跑 16 例，`extract.py` 中的 10 个臂名可分别作为 `run.sh` 参数，预期 JUnit 退出 `1`（编译/抽取失败退出 `2`，不算红臂）。使用同一沙箱时用 `sh /var/minis/shared/tools/gate_run.sh --exec -- sh /var/minis/workspace/r2c-webapp-test/run.sh clean` 串行运行。仓库内的 `ModelServiceNetworkCallbackTest`、`WebAppRepositoryGateTest`、`WebAppFullscreenStateTest` 可在有 SDK 的环境用 `:app:testReleaseUnitTest --tests` 定向运行；设备端筛选 `com.rikkaminis.app.webapp.WebAppShortcutLaunchInstrumentedTest`。

报告提交后的等价性检查（应无输出，退出 0），不需要再次跑 CI：

```sh
git diff --exit-code 11404bab04b492cdacda095eee9b9b77a234260d..HEAD -- . ':(exclude)fix-reports'
```
