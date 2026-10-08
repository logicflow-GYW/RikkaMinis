---
name: cloudflare-fullright-ops
description: Cloudflare 小号全权限操作（CF_ACCOUNT_ID 的 Account）。当用户提到 CF/Cloudflare 小号、Workers/R2/DNS/Zone、cfat token，或要查询/操作 Cloudflare 账号资源（列 Workers、查 DNS、R2 桶、账号设置）时触发。调用方式 curl api.cloudflare.com，账号与 token 约定见正文。
version: 1.0.2
---
# Cloudflare 满权限小号操作

## 账号背景

- Account ID: `CF_ACCOUNT_ID`（CF_ACCOUNT_EMAIL's Account，standard 计划，废弃级小号）
- token: 环境变量 `CF_API_TOKEN`（API token，全权限）。**禁止回显 token 值**

## 调用方式（curl，token 从环境变量读，不回显）
```sh
CF_TOKEN="$CF_API_TOKEN"
curl -s -H "Authorization: Bearer $CF_TOKEN" \
  "https://api.cloudflare.com/client/v4/accounts/CF_ACCOUNT_ID/workers/scripts"
```

## 已验证 / 未验证状态

- ✅ 列 Workers scripts（curl 通）
- ✅ token 鉴权有效（R2 报业务错误 10042 而非鉴权错误）
- ❌ R2 未激活：需用户在 dashboard 手动激活（API 返回 10042 "Please enable R2 through the Cloudflare Dashboard"）
- ❌ R2 S3 端点（*.r2.cloudflarestorage.com）被当前设备网络 SNI 过滤，rclone 配置已就绪但需换网络/代理才能用

## 已部署 Workers（现成资源，别重复造）

- **`rikka-ci-bridge`** — GitHub Actions webhook → CF KV → 公开状态端点。**查 CI 状态优先用它，零 token 开销**：
  - `GET https://rikka-ci-bridge.logos7313.workers.dev/status/<branch>` → `{"name","branch","status","conclusion","html_url","at"}`
  - `GET https://rikka-ci-bridge.logos7313.workers.dev/` → 根探活
- **`rikka-bulletin`** — 边缘计算/公网入口（用途待确认）

## 常用操作模板（curl 路径）

统一前缀：`API="https://api.cloudflare.com/client/v4"`，请求头 `-H "Authorization: Bearer $CF_API_TOKEN"`

| 操作 | curl 路径 |
|---|---|
| 列 Workers | `$API/accounts/{accountId}/workers/scripts` |
| 查单个 Worker | `$API/accounts/{accountId}/workers/scripts/<name>` |
| 列 Zone | `$API/zones?account.id={accountId}` |
| 列 R2 桶 | `$API/accounts/{accountId}/r2/buckets`（预期报 10042） |
| 查账号信息 | `$API/accounts/{accountId}` |
| Workers KV 命名空间 | `$API/accounts/{accountId}/storage/kv/namespaces` |

## 安全纪律

1. 任何命令不得回显 `cfat_` token 值
2. token 只从环境变量读取，禁止写进记忆/日志/脚本文件
3. 实验性创建的资源（worker/zone/dns 记录）用后即删

## 相关资源

- rclone R2 remote 已配置: `/root/.config/rclone/rclone.conf`（AccessKey/Secret/endpoint，chmod 600）
- 文档查询: https://developers.cloudflare.com/api/（OpenAPI 规格，curl 直查）
- 记忆参考: 2026-08-05 "Cloudflare 小号接入 Minis MCP — 已完成"（历史记录，MCP 已移除，现直接用 curl）