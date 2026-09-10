# 小号线（alt 构建线）

> 状态：**已自动化**。同步由小号仓库的
> [`.github/workflows/sync-fork-main.yml`](https://github.com/rikkaflow/RikkaMinis/blob/main/.github/workflows/sync-fork-main.yml)
> 每晚（或手动派发）执行。本文件说明这条线为什么存在、它只差在哪一处、
> 怎么验证它还是对的、以及同步失败时怎么手工恢复。

## 它是什么

`rikkaflow/RikkaMinis` 是本仓库的一条**镜像线**（下称小号线）。它不是开发分支：

- 全树 = 上游 `logicflow-GYW/RikkaMinis` main **+ 一处 delta**
- 那处 delta 只做一件事：构建时注入 `MINIS_APP_ID_OVERRIDE=com.rikkaminis.app.lab`

## 为什么它存在（两个目的，按重要性）

1. **同一台设备上与稳定版共存。** 换掉 applicationId 之后，小号线产出的 APK 与
   稳定版（`com.rikkaminis.app`）**互不覆盖**：各自的数据目录、子进程名、
   Shizuku provider、启动器标签都是独立的。这是当初把它做出来的原因——装上它，
   设备上就同时有两个可独立运行的 RikkaMinis，可以对照、可以拿来验证一份
   构建到底坏在哪。
2. **主号出问题时的救援线。** 主号那棵树处于"手术中"（合并出错、功能搬到一半、
   主线暂时起不来）时，小号线始终留着一份**能构建、能装、能跑**的副本：
   派发一次 workflow 就能拿到 APK。这条性质只在它**一直跟着主线走**时成立——
   所以同步是自动的，而不是"想起来再点一次"。

## delta 只有一处

小号线相对上游的全部差异 = 在 `.github/workflows/build-apk.yml` 的 stage 步骤里
多一行环境变量注入（applicationId 的 override 机制本身在上游
`build.gradle.kts`，见该文件第 41/48 行的注释）。

这个文件是**上游拥有的文件**，上游会持续改它，所以 delta 不以 merge 后的残留
hunk 形式存在，而是由小号仓库里的脚本**重新合成**：
`scripts/alt/apply_dual_appid_delta.py`

- 幂等：已经带 delta 的文件跑一遍字节不变
- 锚点是 version echo 那一行的**正则**，因此上游在该行后面追加文字不会破坏 delta
- 只有整行消失才失败——响亮地失败，因为 delta 悄悄掉了就意味着新 APK 会覆盖
  稳定版，而 CI 里没有任何东西会察觉

## 同步怎么跑

小号仓库的 `sync-fork-main.yml`（每晚 03:17 UTC，或从 Actions 手动派发）：

1. fetch 上游 main；若它已是 HEAD 的祖先 → 直接结束（幂等，不产空提交）
2. `git merge`；冲突**只在** `build-apk.yml` 时 → 取上游版本 +
   跑 delta 脚本重新合成 → 提交
3. 冲突出现在别的文件 → `git merge --abort` + **作业失败**：宁可红，
   也不要一次静默的坏合并（那会让救援线比陈旧更糟）
4. 合并后守卫：delta 的环境变量行与 appId 注解都必须存在，否则失败
5. `git push origin HEAD:main` → 触发小号线自己的 build-apk CI，
   产物带 `.lab` applicationId

> 为什么不用 GitHub 的 "Sync fork" 按钮：它只做 fast-forward，而这条线带着自己的
> commit，按钮会直接拒绝——这正是需要一个自己的 workflow 的原因。

Actions 不可用时的手工等价操作：

```bash
git clone https://github.com/rikkaflow/RikkaMinis.git && cd RikkaMinis
git fetch --no-tags https://github.com/logicflow-GYW/RikkaMinis.git main
git merge FETCH_HEAD            # 冲突只应出现在 build-apk.yml
git checkout FETCH_HEAD -- .github/workflows/build-apk.yml
python3 scripts/alt/apply_dual_appid_delta.py
git add -A && git commit -m "merge upstream main"
git push origin HEAD:main
```

## 怎么验证同步是对的

- **内容**：在小号仓库里 `git diff --stat <上游sha>..main` 只应出现
  `.github/workflows/build-apk.yml`、`scripts/alt/`、`sync-fork-main.yml`
- **构建日志**：小号线 CI 的 stage 步骤回显 `appId=com.rikkaminis.app.lab`
- **设备**：与稳定版并存——应用列表里两个入口，数据互不相干

## 非目标

- 小号线**不做开发**。功能与修复都在主号完成，再靠同步落到小号线。
- 历史上的实验工作（subagent、Ubuntu rootfs、provider gateway 等）归档在
  `lab-history-archive` 分支：那是快照，不再往前推进。
