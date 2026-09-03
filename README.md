# RikkaMinis — Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20arm64-brightgreen.svg)](#install)
[![Build](https://github.com/logicflow-GYW/RikkaMinis/actions/workflows/build-apk.yml/badge.svg)](https://github.com/logicflow-GYW/RikkaMinis/actions/workflows/build-apk.yml)

**简体中文** · [English](README_EN.md)

**你的私有、端侧 AI 智能体。**

RikkaMinis 是一个个人专用的 **Android-only** 构建，杂交了两个项目：
引擎与代码库来自 [OpenMinis](https://github.com/OpenMinis/OpenMinis)，
UI 与交互逻辑则受 [RikkaHub](https://github.com/rikkahub/rikkahub) 启发——
包括聊天历史抽屉、极简顶栏、消息流布局，以及消息流跟随、输入栏聚焦这类交互行为。

它在 GitHub Actions 上构建可用的 APK 并自动发布。

OpenMinis 核心把领先的模型——Claude、GPT、Gemini 等——带进原生移动体验，
并给它们一台真正的计算机：设备上运行的完整 Linux Shell、浏览器自动化、
可扩展技能、持久记忆，以及深度的系统集成。

> **个人 fork，主要为自用。** 我修的是自己需要的东西，欢迎大家拿来用，
> 但请别期待活跃的支持或功能请求。要改自己的版本，欢迎随时 fork 一个。

---

## 安装

**→ [下载最新 APK](https://github.com/logicflow-GYW/RikkaMinis/releases/tag/android-latest)**

每次推送到 `main` 的**代码改动**（`src/android/**`、`src/shared/**`、`deps/**`
或 workflow 文件）都会构建一个发布版 APK 并重新发布到该链接——纯文档改动
（README / 许可证等）不触发构建。所以这个 URL 始终指向最新构建。要求：

- **arm64-v8a** 设备（任意现代手机），**Android 8.0+**
- 设备提示时允许"安装未知来源应用"

构建使用固定密钥签名，因此新 APK 会**覆盖**安装旧版本——你的数据和设置会被保留。

```
SHA-256  FC:0C:40:0D:B7:7E:C1:81:A3:35:18:C2:E8:13:6A:AE
         1A:3F:6C:79:4A:1A:A7:9F:DB:67:63:8F:C6:B1:61:13
```

用 `python3 scripts/apk_cert_sha256.py <apk>` 校验下载。

---

## 这个 fork 改了什么

起初这是一个纯构建 fork，但现在也携带了一小批上游没有的 Android 专属产品改动。

### 应用改动

- **完整的本地备份与恢复。** 设置 → 存储 → 备份与恢复 导出一个可移植的 JSON
  文件，可在另一台安装上导入。涵盖提供方/模型配置与分组、可选 API 密钥、
  环境变量、应用/智能体/聊天默认值、Soul、完整技能（SKILL.md 连同捆绑脚本、
  引用与资源）、持久记忆、MCP 服务器配置，以及聊天历史（纯文本，默认最近
  90 天，窗口可在备份设置中调整）。
- **WebDAV 远程备份。** 设置 → 存储 → 备份与恢复 还支持把备份推送到任意
  WebDAV 服务器（Nextcloud、坚果云、Synology、…）——一次配置后即可在
  多台设备间同步备份，随时上传、列出、恢复或删除远端备份。备份/恢复运行在
  应用级持久 scope（即使离开设置页也会完成），长任务结束时通过系统托盘
  通知反馈，并已防止在过程中被误触重复触发。
- **多设备自动同步（冲突合并）。** 设置 → 存储 → 备份与恢复 里的自动同步
  开关把多台设备挂到同一个 WebDAV 备份上，`SyncMerge` 用 Lamport 式逐对象
  版本折叠合并改动：两台设备同一天都在用时不再互相覆盖——兄弟设备改动胜出、
  删除以墓碑传播、两台收敛到同一文档。同步范围只含配置/提供方/分组/环境变量
  和 `GLOBAL.md`；每日日志是每设备审计副本、`MEMORY-ROLLUP.md` 是从每日日志
  蒸馏出来的，整文件同步会互相覆盖，故不纳入。
- **诚实的排除项。** 聊天历史仅以纯文本携带：媒体（图片/视频）和附件文件会被
  丢弃，只包含最近 N 天的活动（0–365，默认 90；0 表示禁用聊天历史）。
  挂载文件夹的授权无法在 Android 设备间迁移，MCP OAuth 客户端密钥/令牌
  从不导出——OAuth 认证的 MCP 服务器在恢复后必须重新授权。
- **聊天 UI 打磨。** 消息链接可以聚焦并高亮某条消息；导航标题左对齐；
  当前模型选择器位于输入栏内；附件与命令操作排布更紧凑。
- **左滑聊天历史抽屉。** 聊天界面从左侧边缘（或通过汉堡按钮）滑出会话列表，
  无需离开当前聊天即可切换历史会话——或开启新对话。抽屉与会话列表保持一致：
  相同的分组、分类图标与相对时间戳，当前会话高亮，长按可删除会话。
- **UX 打磨。** 进入应用不再自动弹出键盘——输入栏只在你点击时才聚焦。
  输入栏中的工具结果缩略预览默认关闭（在 设置 → 外观 中切换）。聊天的
  "…" 菜单可导出当前会话（JSON 或纯文本，位于 Slash Commands 与 Token Usage
  之间），且不再列出 Clear Chat——它与 New Chat 重复，还可能留下一个空的
  幽灵会话。设置及其顶层子页（外观、备份、环境变量、日志、MCP、记忆、
  提供方、技能、Soul、存储、用量）去掉了冗余的顶栏返回箭头——改用系统返回
  手势 / 底部导航处理；编辑、向导与权限流程页面保留返回箭头。
- **更简洁的输入栏。** 专用的语音聊天快捷入口及其内嵌 UI 已被移除。
  Android 面向智能体的语音工具不受影响。
- **Termux 驱动的终端。** 应用内终端从自研 ANSI 仿真器（约 2200 行）换成
  [Termux](https://github.com/termux/termux-app) 的 `terminal-view` 0.118.0
  引擎（JitPack 依赖）：PTY 生命周期、ANSI/CSI/OSC 解析、TUI 兼容性、
  键盘与文本选择全部交给上游引擎处理。输出经两层截断（PersistentShell
  层 128KB + 终端消毒层 50KB），防止海量输出刷爆界面。
- **提供商置顶。** 提供商列表支持将常用提供商固定到顶部的「常用」专区，行尾菜单一键设/取消常用。
- **记忆页管理改进。** 记忆页文件列表支持「查看更多」展开/收起。
- **设置一致性修复。** 恢复的偏好会刷新实时设置界面，此前缺失/断连的设置键
  现已注册并纳入备份。
- **子代理派发默认关闭。** 智能体可以通过 `spawn_agent` 工具派生独立子代理、
  或通过 `minis-sessions-cli send` 把工作派发给其他聊天会话——但这是有副作用
  的能力（会开新会话、消耗 token、跑长任务），默认关闭：设置 → Agent Runtime
  里的 "Sub-agent dispatch" 开关控制。关闭时 `spawn_agent` 完全不进入工具
  列表、`minis-sessions-cli send` 返回明确的拒绝错误；打开后可用，且子代理
  自身的工具集被过滤（不允许再 spawn，递归结构上不可能）。
- **长对话自动摘要压缩。** AI 回答过程中上下文接近上限时，先用上下文压缩器
  把最老的回合折叠成 `<context-summary>`（替代原先直接硬裁剪丢最老消息、
  并在回答中插入生硬的 "trimmed N messages" 压缩线的行为）；硬裁剪只作为
  最后的兜底硬上限。系统提示词也澄清了工作目录的会话隔离语义：
  `workspace/attachments/offloads/browser` 是每会话私有（物理位于
  `minis-sessions/<sid>/`），只有 `shared/memory/skills/mcp-servers/mounts`
  跨会话共享。
- **三大平台内置集成（GitHub / Cloudflare / Hugging Face）。** 完整能力见下文
  [内置平台集成](#内置平台集成github--cloudflare--hugging-face)。简单说：三个
  平台技能（语义记忆、GitHub 自动化、Cloudflare 运维）直接打进 APK，构建系统
  提示词时根据你是否配置了对应的 API token（`HF_TOKEN` / `GITHUB_TOKEN` /
  `CF_API_TOKEN`）动态计算每个平台的可用能力等级，并把「内置集成」表格注入
  系统提示词——智能体一上来就清楚知道每个平台能做什么、不能做什么，不用靠
  试错猜测。

**协作模式。** 这个 fork 本身是在人机协作闭环中用它自己开发的——你（决策/验证）
+ AI agent（执行/迭代）+ 外挂平台（编译/发布/存储），循环迭代。详见
[docs/DEVELOPMENT_LIFECYCLE.md](docs/DEVELOPMENT_LIFECYCLE.md)。

### 构建与发布改动

- **proot 从源码构建。** 沙箱引擎来自 `deps/proot` 子模块 + `deps/build_proot.sh`
  + vendored 的 `deps/talloc`，在 CI 中用 NDK r28 编译。Alpine rootfs
  （`alpine-minirootfs.tar`，8.5 MB）作为预置资产随仓库提交，运行时由
  `RootfsManager` 解包——proot 二进制本身不提交，完全可复现。
- **其他原生库保持 vendored。** `libpty_bridge.so`、`libminis_crash_handler.so`
  和 `libjieba_jni.so` 按原样提交。
- **单元测试在 CI 中运行。** 完整的 JVM 单元测试套件——含备份/恢复（ConfigBackupPayloadTest 等）、终端消毒、Provider 适配器、LLM 错误处理等全部测试——在 APK 构建之前执行，任何一条失败都会中止构建（无静默跳过）。
- **构建前静态扫描门禁。** 每次 CI 构建在 Gradle 之前先跑 `scripts/scan/scan.sh`：
  四处同步检查（数据类字段在 Model→Entity→toSnapshot→toProviderConfig 四层
  必须同步，缺一层字段会静默蒸发）、i18n 孤儿键检查、枚举解析安全检查
  （禁裸 `valueOf`）、provider 进程边界守护（app 进程不得直接调 provider 网络
  入口，只能由 `:modelservice` 持有）。任何一条硬失败都会中止构建。
- **iOS 源码已移除。** `src/ios/` 已删除；本树仅限 Android。
- **自动发布。** 成功构建会把 APK 发布到 `android-latest` release。
- **平台技能打进资产包。** `semantic-memory`、`github-ops`、
  `cloudflare-fullright-ops`、`skill-creator` 四个技能（含脚本）随 APK
  一起打包在 `assets/skills/`，安装即自带，无需手动安装。
- **集成状态动态注入 system prompt。** 每个内置技能带一份 `requirements.json`
  声明它需要的环境变量，运行时按「哪些配置了」推导能力等级，把结论作为
  `[IntegrationStatus]` 日志输出 + 「内置集成」表格注入系统提示词。


**为什么 proot 从源码构建？** 沙箱引擎 `libproot.so` 需要用上游的 Android 10+
W^X 绕过补丁构建。通过 AGP 的 CMake 块产出的二进制能编译通过，却在运行时以
`execve("/bin/sh"): Permission denied` 失败——终端永远打不开。因此本 fork 用
`deps/build_proot.sh`（上游支持的路径——与官方二进制相同的源码、相同的 NDK
工具链）而非 CMake 来构建它。`externalNativeBuild` 保持禁用，AGP 因此不会用
未打补丁的 CI 构建版覆盖 vendored 的 pty_bridge / crash_handler / jieba 库。

**权衡：** `src/android/app/src/main/cpp/` 下的改动不会被编译——只有
`deps/proot` 通过 `build_proot.sh` 构建。改动其他原生代码意味着要恢复 CMake
块并在 CI 中安装 NDK。Kotlin、UI、提示词与模型集成不受影响——正常构建。

---

## 内置平台集成（GitHub / Cloudflare / Hugging Face）

> **这一节是本 fork 相对上游最重要、也最容易忽略的结构性改动。**
> 它把一个"端侧单机智能体"扩展成了**一端脑三平台手**的形态——
> 智能体不只是在你手机上跑 shell / 浏览器，还能直接操作三个外部平台。
> 如果只是拿源码构建却不知道这层的存在，你会困惑"为什么 system prompt
> 里多了一张内置集成表格"。

### 一句话

RikkaMinis 打包了三个**平台技能**——每个技能封装一个外部平台的常用操作，
通过各自的 `requirements.json` 声明它依赖的环境变量。构建系统提示词时读取你
配置的 token，为每个平台算出一个当前能力等级（零配置 / 只读 / 完整），把结论
**动态注入 system prompt**，让智能体不用靠试错就知道自己此刻能碰哪些平台。

### 三平台各管什么

| 平台 | 技能 | 能做什么 | 需要的 token | 最低 / 完整等级 |
|---|---|---|---|---|
| **GitHub** | `github-ops` | 推送代码、触发 CI、管理 issue/label/release/PR、查状态 | `GITHUB_TOKEN` | Tier 1 只读 · Tier 2 完整 |
| **Cloudflare** | `cloudflare-fullright-ops` | 列/部署 Worker、管理 KV / R2、查 Zone / DNS | `CF_API_TOKEN` | Tier 1 只读 · Tier 2 完整 |
| **Hugging Face** | `semantic-memory` | 语义搜索历史经验、读写 HF Dataset、跨设备持久化记忆 | `HF_TOKEN` | Tier 0 零配置搜索 · Tier 2 完整读写 |

配置入口：**Settings → Environments**（或 `minis-config envvars`）。三个 token
都是标准的个人 API token，各自平台的 dashboard 里创建。**注意：token 直接存储在
本地（供判级时读取），不会出现在任何日志里，默认也不纳入备份导出——
除非你在备份里显式勾选"包含机密"。**

### 能力等级怎么算（`buildIntegrationStatus`）

每个技能自带的 `requirements.json` 声明它需要的环境变量（如 GitHub 需要
`GITHUB_TOKEN`）。运行时对照应用的环境变量存储，按以下规则推等级：

- **Tier 0 — 零配置**：不需要任何 token 就能干的公共能力（比如语义记忆的
  公开搜索）。
- **Tier 1 — 只读**：声明了 env 且**部分**配置了 token（能读公开数据或有限操作）。
- **Tier 2 — 完整**：声明了 env 且**全部**配置了 token（完整读写 / 部署）。

当前等级会：
1. 以 **`[IntegrationStatus]` 日志行**输出（`logcat | grep IntegrationStatus`），
   附带 `declared=` 和 `found=` 的环境变量清单——排查"以为配了却显示需配置"时
   一眼定位是哪个变量没被读到，不用瞎猜。
2. 以**「内置集成」表格**注入 system prompt（紧跟技能列表之后、MCP 服务器
   之前，独立的 `## 内置集成` 区块），智能体据此决定用哪种方式干活。没配置
   token 的平台会标成 "🔒 需配置"，绝不虚标为 "⚡ 零配置可用"——宁可让它
   什么都不干，也不能让它误导智能体去操作。

### 三个技能怎么升级

技能本体在仓库 `src/android/app/src/main/assets/skills/<skill>/` 下（SKILL.md
+ requirements.json + 脚本）。改动这些文件 → 提交 → CI 重新打包 → 新 APK 里
就是新版本技能。本地开发时也可以覆盖 `/var/minis/skills/<skill>/` 直接生效
（应用优先读 /var/minis/skills，有才回落到 assets 内置版）。

### 隐私注记

平台技能只在你**明确让智能体使用**对应平台时才发起请求（换句话说：你
在对话里让它"帮我查一下 GitHub issue"）。应用本身不会后台偷偷调用任何平台。
token 只用于这些显式请求的鉴权。

---

## 它能做什么

| | |
|---|---|
| **自带模型** | Claude、GPT、Gemini 及其他提供方，使用你自己的 API 密钥或账号登录。 |
| **真正的 Linux Shell** | 设备上运行沙箱化的 Alpine Linux 环境——智能体可以安装软件包、运行脚本、操作真实文件。 |
| **设备集成** | 日历、联系人、剪贴板、定位、媒体、闹钟、通知等，作为工具开放给智能体。 |
| **浏览器自动化** | 智能体可以代表你浏览并操作网页。 |
| **技能与记忆** | 可扩展技能 + 跨会话的持久记忆。完整技能包与记忆文件包含在本地备份中。 |
| **平台集成** | 内置 GitHub / Cloudflare / Hugging Face 三平台技能，按配置的 token 动态注入可用能力（详见上节）。 |
| **本地备份与恢复** | 把配置、凭据（可选）、技能、记忆、MCP 服务器与聊天历史（文本、最近 N 天）导出到一个可移植的 JSON 文件。 |
| **工作区** | 把工作组织到独立上下文中，通过 `minis://workspace/` 访问。工作区、附件、offload 与浏览器目录是**每会话私有**的（`minis-sessions/<sid>/`）；跨会话共享的只有 `shared/`、`memory/`、`skills/`、`mcp-servers/` 与挂载目录。 |
| **原生卸载（offload）** | 繁重或平台特定的工作交给原生代码而非沙箱处理。 |

**→ [OpenMinis/MinisSkills](https://github.com/OpenMinis/MinisSkills)** — 现成技能。
为 Claude、Codex、OpenClaw 或 Hermes Agent 构建的技能通常可以直接在 Minis 中运行。

**→ [OpenMinis/AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** — 精选的
用例与工作流合集。

---

## 本地构建

```sh
git clone --recurse-submodules https://github.com/logicflow-GYW/RikkaMinis.git
cd RikkaMinis/src/android
../../deps/build_proot.sh        # 从源码构建 proot 沙箱引擎
./gradlew assembleRelease
```

需要 **JDK 17**、Android SDK（compileSdk 36）和 **NDK r28**——后者用于
`deps/build_proot.sh`，它从 `deps/proot` 子模块编译 proot 沙箱引擎（其他原生库
已 vendored 在树中）。APK 输出在 `app/build/outputs/apk/release/`。

本地构建使用你自己的 `~/.android/debug.keystore` 签名，因此无法覆盖安装 CI
构建。要对齐 CI，请把相同的 keystore 放到那里。

工具链细节与排障见 [BUILDING.md](BUILDING.md)。

---

## 跟上上游

上游是单向镜像，不接受 pull request，而本 fork 在少数文件上已经分叉。
同步是可能的，但有操作顺序要求——尤其是 vendored 的 pty_bridge /
crash_handler / jieba 库必须在上游 Kotlin 改动时刷新，否则应用会在运行时崩溃。
proot **不再** vendored：它在 CI 中通过 `deps/build_proot.sh` 从源码构建，
所以对它来说唯一需要刷新的是上游升级时的 `deps/proot` 子模块。

```sh
git fetch upstream
git rebase upstream/main               # 不要 merge
./scripts/sync_official_binaries.sh    # 刷新 vendored 的 pty_bridge/crash_handler/jieba 库
```

**→ 完整流程、冲突文件清单以及从坏同步中恢复的方法见 [docs/SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md)**

---

## 隐私

本 fork 不添加任何追踪，上游也没有。具体来说：

- **无分析或遥测 SDK。** 没有 Firebase、Crashlytics、Sentry 或类似组件。
- **崩溃报告留在设备上。** 包含 ACRA 但仅 `acra-core`——未配置任何网络发送器。
  报告写入本地文件，并在应用的日志界面中展示。
- **不收集设备标识符。** 没有 IMEI，没有广告 ID。
- **发布构建中没有调试服务器。** 开发用的本地 JSON-RPC 服务器位于
  `127.0.0.1:5321`，由 `BuildConfig.DEBUG` 门控，并已从这里发布的 release APK
  中编译移除。

网络流量只流向你配置的模型提供方（使用你自己的 API 密钥），以及你明确让
智能体访问的端点。本地备份文件不会离开设备，除非你自己分享或复制。
如果你选择"包含机密"，JSON 会以可恢复形式包含 API 密钥和环境变量值；
请像保管密码一样保管该文件。即使包含机密的备份也排除 MCP OAuth 令牌与
客户端密钥。

应用请求较宽泛的权限（存储、联系人、日历、麦克风、定位、无障碍），因为它们
支撑智能体工具。这些权限在使用时按需请求——智能体只能使用你授予的能力。

---

## 仓库结构

```
src/android/      Android 应用（Kotlin / Compose）
  app/src/main/jniLibs/arm64-v8a/   原生库（jieba、pty bridge、crash handler）；
                                    libproot.so 是 CI 构建产物，非 vendored
  app/src/main/assets/              Alpine minirootfs + 内置平台技能（skills/）
src/shared/       与上游 iOS 树共享的资源（bashism 规则）
deps/             proot 源码（子模块）+ build_proot.sh（NDK r28 构建）
docs/             同步流程与接口规范
scripts/          二进制同步与开发者工具
```

---

## 致谢

RikkaMinis 建立在大量开源工作之上——完整清单见
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。本 fork 派生自
**[OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)**，并从头构建其
沙箱二进制：`deps/proot` 子模块（OpenMinis 的 PRoot fork，含其 native-offload
与 W^X 扩展）在每次 CI 运行中通过 `deps/build_proot.sh` 用 NDK r28 编译。
本仓库不提交任何预构建的沙箱二进制。

**沙箱** — [PRoot](https://github.com/termux/proot)（GPLv2），Android 沙箱的用户态
chroot，经由 [OpenMinis 的 fork](https://github.com/OpenMinis/proot)；
**[talloc](https://talloc.samba.org)**（LGPLv3+）是其底层；
**[Alpine Linux](https://alpinelinux.org)** — 沙箱启动所用的 minirootfs。

**文本与渲染** — [cppjieba](https://github.com/yanyiwu/cppjieba)（MIT）、
[KaTeX](https://katex.org)（MIT）。

**终端** — [Termux](https://github.com/termux/termux-app) 的 `terminal-view`
0.118.0（JitPack，`com.termux.termux-app:terminal-view`）提供应用内终端的
ANSI/CSI/OSC 解析与 TUI 渲染引擎。

**交互参考 — [RikkaHub](https://github.com/rikkahub/rikkahub)**（AGPL-3.0），Android
多 LLM 客户端，为 RikkaMinis 的聊天 UI 与交互逻辑提供设计灵感（借鉴灵感，
非代码复制）。

**Android 端侧 AI 智能体参考** — 以下项目为 RikkaMinis 的 agent 运行时、
自动化与系统集成能力提供了设计参考（借鉴思路，非代码复制）：

- **[OmniBot](https://github.com/omnimind-ai/OmniBot)** — 工具并发、回合折叠、
  自动压缩、记忆 rollup、子代理系统
- **[肉包 Roubao](https://github.com/Turbo1123/roubao)** — 宏脚本、执行追踪
- **[AppAgent](https://github.com/TencentQQGYLab/AppAgent)**
- **[MobileAgent-Android](https://github.com/GiggleWang/MobileAgent-Android)**
- **[mobAgent](https://github.com/sudharsanacernitro/mobAgent)**
- **[anthroid](https://github.com/k-l-lambda/anthroid)**
- **[OpenPhone](https://github.com/HKUDS/OpenPhone)**
- **[MobileAgent](https://github.com/X-PLUG/MobileAgent)**
- **[Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)**
- **[OpenGUI](https://github.com/Core-Mate/OpenGUI)**
- **[mobilerun](https://github.com/droidrun/mobilerun)**
- **[locanara](https://github.com/hyodotdev/locanara)**
- **[deliteAI](https://github.com/NimbleEdge/deliteAI)**

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack)、
[OkHttp](https://square.github.io/okhttp/)、[Coil](https://coil-kt.github.io/coil/)、
[kotlinx](https://github.com/Kotlin) 序列化与协程、
[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)、
[Reorderable](https://github.com/Calvin-LL/Reorderable)、[ACRA](https://github.com/ACRA/acra)
（均为 Apache-2.0），以及 [Shizuku](https://github.com/RikkaApps/Shizuku-API)（MIT）。

---

## 许可证

RikkaMinis 以 **[GNU General Public License v3.0](LICENSE)** 许可。

应用链接了 GPL 许可的组件——[PRoot](https://github.com/OpenMinis/proot)
（GPLv2）——因此合并后的作品以 GPLv3 分发。捆绑的第三方许可证列在
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

---

## 上游

原始项目、iOS 应用、issue 与社区：

**→ [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)** ·
[openminis.app](https://openminis.app) ·
[Telegram](https://t.me/+2NzhOJuzRyI1YmM1)

对于一般应用 bug，请检查官方上游构建是否也会出现。上游 issue 属于
OpenMinis/OpenMinis；本 fork 的构建、APK、备份/恢复流程或 Android UI 改动
的问题请提交到本仓库。
