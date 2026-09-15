# RikkaMinis 开发历史档案

本目录是 RikkaMinis **应用开发**过程的日志合并导出，从本地 memory 每日日志
（`/var/minis/memory/`）按时间正序聚合而成，用于开源归档。

- `rikkaminis-dev-history.md` — 完整开发日志（raw dump，按天分组、时间正序）
- `rikkaminis-dev-history-INDEX.md` — 按天索引

## 说明

- 覆盖范围：2026-08-03 ～ 2026-09-15（44 天，995 条）。
- 已剔除与本应用开发无关的条目，按轴分组：其他仓库（rikkahub / BiliRoamingX /
  OmniBot 等第三方调研）、CF 代理与优选、平台账号与 agent 工具链、元讨论与
  认知类笔记、生态调查报告（各有独立文件）。
  上游 OpenMinis 不算第三方——它是本应用的直接上游，fork 差异记录属于本应用历史。
- 已脱敏：邮箱（含索引截断产生的半截邮箱）、各类 API 密钥/token 字面量、
  Cloudflare 账户 ID 与 KV 命名空间 ID、UUID、个人域名、内网与代理地址、
  手机号、账号标识、疑似密码等均替换为占位符
  （`[EMAIL]` / `***CF_ACCOUNT_ID***` / `***DOMAIN***` / `***PROXY_ADDR***` /
  `***USER***` / `***PASSWORD***` / `***API_KEY***` 等）。
  commit hash 与 sha256 保留原样——它们本就是公开 git 历史。
- 由 `skills/dev-history-sync/` 的脚本生成与脱敏，可重复执行（幂等）。
  脱敏对主文件与 `-INDEX.md` 同规则处理，并在脱敏后按盘上实际内容重算头部的
  字符/行数统计。脱敏脚本的校验探针与替换规则相互独立，避免"规则失效同时
  导致检测失效"的自证循环。
