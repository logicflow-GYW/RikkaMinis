# scripts/diag — 诊断存活度自查

## 为什么有这个东西

应用里有大量"尺子"（日志埋点、计数器、breadcrumb）。它们的失效方式**不是报错，而是静默**：

- 探针在活路径上，但阈值永远不达标 → 一行都不打；
- 探针所在分支已经不可达（被新管线取代）→ 一行都不打；
- 探针挂在 `BuildConfig.DEBUG` 下 → release 一行都不打。

三种情况在日志里长得**一模一样**。结果就是：你**无法区分"没触发"和"没有这把尺子"**——讨论优化收益时依赖的度量可能根本不存在。2026-09 的一次审计就是这样发现两条关键尺子（流式渲染 tick、点击→首帧锚点）在活代码上恒为 0。

## 怎么用

```bash
python3 scripts/diag/liveness_audit.py \
    --src src/android/app/src/main/java \
    --logs /path/to/logs        # 里面放 minis-*.log / memspike-*.log
```

输出四张表（PerfLongCtx step / AppLogger 类别 / logcat tag / onEvent），每行是"代码声明的探针 → 窗口内实测次数"。`hit=0` 且没有 `rare:` 注解的，就是候选死尺子。

加 `--strict` 时，只要出现**未登记在案的**静默探针就以非 0 退出，可以直接接进定时任务。

## 两个必须知道的坑

1. **日志有两个通道，必须取并集。**
   - `[时间] [LEVEL] [category] msg` ← `AppLogger.*`（logcat tag 是 `Minis.*`，**不会**再进通道②，避免重复）
   - `[LOGCAT] MM-DD 时间 L/Tag(pid): msg` ← 其他所有 tag
   只扫通道①会把 `OffloadRssProbe` 这种只走通道②的探针判成死的（真实发生过）。
2. **不要手工 grep 计数**：agent 自己的工具输出也会被写进同一份日志（`[LOGCAT] ... ToolChain[VM]`），关键词计数会被命令行文本污染。本脚本只统计符合通道格式的行，所以不会。

## `RARE` 清单

脚本顶部的 `RARE` 登记"已确认合理静默"的探针及原因（例如只在崩溃安全模式触发、只在 LB 轮转时打印、当前功能已关闭）。**新增一条前必须有证据**，它存在的意义就是让下一次审计不用重新论证一遍。

## 与 `RenderCensus` 的关系

`com.rikkaminis.app.diagnostics.RenderPathCensus` 是运行时的对应物：它在应用内按分支计数，**每个分支第一次被走到时必打一行**，之后每 50 个事件打一行窗口汇总（窗口行里带着从未走到的分支的 0 值）。于是"某个渲染分支两天没进过"变成一条可读的结论，而不是一段沉默。
