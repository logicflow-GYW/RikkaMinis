---
name: semantic-memory
description: HF 语义记忆系统 — 用自然语言搜索历史经验（不依赖关键词）。基于 HF Dataset + embeddings（HF Inference / CF Workers AI 双后端）实现跨会话「真正回忆」。当要按含义/语义而非关键词找历史经验时触发。
version: 1.4.0
---
# Semantic Memory Skill

## 这是什么
RikkaMinis 应用的外部语义记忆系统。基于 HF Dataset（存储）+ 嵌入后端（检索），实现「用自然语言搜记忆，不依赖关键词匹配」。

**嵌入后端（2026-10-10 起双后端）**：
| 后端 | 模型 | 维度 | 状态 |
|---|---|---|---|
| HF Inference | paraphrase-multilingual-MiniLM-L12-v2 | 384 | ❌ 免费额度失效（全模型 402 Payment Required） |
| **CF Workers AI** | `@cf/baai/bge-m3` | **1024** | ✅ 现行默认（HF 失败自动回退） |

后端与维度**锁死在索引里**（`backend` 字段）：`auto`（默认）先试 HF、首个批次失败即整体切 CF；
`SEMANTIC_EMBED_BACKEND=hf|cf` 可强制。**换后端必须全量重建** —— 384 与 1024 维混在一个索引里
`cosine()` 的 `zip` 会静默截断到 384 维算，相似度全错且不报错。

## 何时触发
- **新会话启动时**：自动运行 `python3 /var/minis/skills/semantic-memory/semantic_memory.py search "<当前任务关键词>"` 获取相关经验
- **做技术决策前**：查之前有没有类似问题、同类 bug、已踩过的坑
- **遇到 bug 时**：搜历史中是否有相同的根因
- **会话结束 / 发现重要经验时**：运行 `build` 将新经验向量化上传

## 触发条件关键词
记忆 语义搜索 HF 经验 之前做过 有没有类似的 历史 经验教训 会话 上下文

## 工具
脚本在 `/var/minis/skills/semantic-memory/semantic_memory.py`

### build — 重建索引
```bash
python3 /var/minis/skills/semantic-memory/semantic_memory.py build --incremental   # ★ 日常用这个
python3 /var/minis/skills/semantic-memory/semantic_memory.py build                 # 全量（仅换模型/索引损坏时）
```
从 /var/minis/memory/ 下所有 daily logs 提取经验 → HF Inference 向量化 → 上传 HF Dataset → 保存本地向量索引。

**增量模式**（`-i`/`--incremental`）：按 `(source, title)` 复用旧向量，只嵌入新增/内容变更的条目。
实测 1239 条语料通常只有 100-200 条变化 → **11s vs 全量 ~90s**。复用判据是 content 逐字相同
（不用 mtime —— 日报会被 memory_write 反复追加，时间戳不可靠）。

**批量嵌入**：HF 后端 16 条/批（单条 ~7.8s → ~0.22s，往返开销远大于计算）；CF 后端 32 条/批。
批大小与步长必须同步推进（`while` 游标，**不能用 `range(0,total,batch)`** —— range 的步长
在创建时固定，后端回退改 batch 会让窗口重叠、条目重复嵌入；chunk 也必须在重试循环内重切，
否则按新步长推进会整段跳过条目）。

### search — 语义搜索（混合评分）
```bash
python3 /var/minis/skills/semantic-memory/semantic_memory.py search "<自然语言查询>"
```
混合评分：语义 cos（基分，不被时间衰减侵蚀）+ 内容覆盖增益 + 标题命中（≤0.45）+ 反向包含（+0.2）+ 新近度加性助推（≤0.1）。输出同时打印**混合分**与**真实 cos**。

### compare — A/B 对比新旧评分
```bash
python3 /var/minis/skills/semantic-memory/semantic_memory.py compare "<查询>"
```
并排输出混合评分 vs 纯语义（×时间衰减）两套 top-5 + 每条分数构成（cos 基分/助推拆解）。用于验证评分改动效果。

### status — 查看状态
```bash
python3 /var/minis/skills/semantic-memory/semantic_memory.py status
```

## 资源
- Dataset（存储）: `HF_USER_NAME/rikkaminis-memory` (private)
- 嵌入（现行）: CF Workers AI `@cf/baai/bge-m3`，1024 维
- 嵌入（停用）: HF Inference `paraphrase-multilingual-MiniLM-L12-v2`，384 维
- 本地索引: `skills/semantic-memory/vector_index.pkl`（重建后约 18MB；**不进仓库、不打包**，rootfs 重建后需 `build` 重建）
- 实测检索质量（bge-m3，中文查询）：top1 真实 cos 0.62~0.78，较 384 维时代更锐利

## 与本地 memory_get 的关系
- `memory_get`：关键词精确匹配，适合查具体术语（如 "GITHUB_TOKEN"、"build-apk.yml"）
- semantic search：语义模糊匹配，适合查「感觉」——"之前有没有类似的事"、"滚动跳相关的东西"
- **两者互补，不是替代**。先用 semantic search 找方向，再用 memory_get 精确定位

## 兄弟装置：MCP 知识图谱（别混）
本地还有第二套记忆装置，与本 skill **互补但独立**：
- **`memory` MCP 服务器**（`@modelcontextprotocol/server-memory`）：JSONL 知识图谱，实体 + 关系。
  调用：`minis-mcp-cli call memory <tool> --input '{...}'`（9 工具：create_entities / create_relations /
  add_observations / delete_* / read_graph / search_nodes / open_nodes）
- **本 skill = 非结构化经验**（日报条目，自然语言语义检索）
- **KG = 结构化事实**（谁是什么、谁依赖谁、账号/工具/基础设施的关系网）
- 两者是**不同的问题**：搜「之前有没有类似的卡顿排查」→ 语义记忆；问「rikka-ci-bridge 属于哪个账号、
  和哪个仓库有关系」→ 知识图谱。
- **KG 的备份/恢复**：`python3 /var/minis/shared/knowledge-graph-backup/kg_backup.py`（默认恢复，
  `--backup` 备份）。live 文件在 npx 缓存内，**rootfs 重建会丢**，备份在 shared 层才跨重建。
- ⚠️ **KG 目前没有自动同步器**（09-06 的 `sync_kg.py` 随 rootfs 丢失、未重建）⇒ **新事实要手工灌**。
  更新约定：新增实体/观察后**务必**跑一次 `kg_backup.py --backup`，否则下次 rootfs 重建会丢。

## Agent 使用约定
- 每个会话启动时：用当前任务的 2-3 个核心关键词做一次 semantic search
- 不要等用户要求才查——主动查，主动引用历史经验
- **判断相关性看 cos 列（> 0.35 视为相关），混合分只用于排序；两者背离时以 cos 为准**
  （混合分含关键词加分封顶 +0.65，cos=0 的条目也能靠标题命中越过 0.35 —— 单看混合分会把关键词噪音误判为相关）
- 发现重要新经验时：用 memory_write 写入 daily log（本地），然后在本会话结束时提醒用户运行 build