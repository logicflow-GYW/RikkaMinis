# RikkaMinis 开发历史档案

本目录是 RikkaMinis 开发过程的**日志合并导出**，从本地 memory 每日日志
（`/var/minis/memory/`）按时间正序聚合而成，用于开源归档。

- `rikkaminis-dev-history.md` — 完整开发日志（raw dump，按天分组、时间正序）
- `rikkaminis-dev-history-INDEX.md` — 按天索引

## 说明

- 覆盖范围：2026-08-03 ～ 2026-09-04（33 天，749 条）。
- 已剔除与 RikkaMinis 开发无关的条目（其他仓库、元讨论等）。
- 已脱敏：邮箱、API 密钥、Cloudflare 账户 ID、个人域名、代理地址、
  HF 命名空间、疑似密码等均替换为占位符（`[EMAIL]` / `***CF_ACCOUNT_ID***`
  / `***DOMAIN***` / `***PROXY_ADDR***` / `***USER***` / `***PASSWORD***`）。
- 由 `skills/dev-history-sync/` 的脚本生成与脱敏，可重复执行。
  **注意：脱敏只作用于主文件，`-INDEX.md` 需单独同规则处理**（摘要截断可能
  产生半截邮箱，需一并检查）。
