---
name: semantic-memory
description: HF 语义记忆系统 — 用自然语言搜索历史经验（不依赖关键词）。基于 HF Dataset + embeddings（HF Inference / CF Workers AI 双后端）实现跨会话「真正回忆」。当要按含义/语义而非关键词找历史经验时触发。
version: 1.4.1
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

## 已退役的兄弟装置：MCP 知识图谱（2026-10-10 退役，别再调用）

曾经有过第二套记忆装置——`@modelcontextprotocol/server-memory` 的 JSONL 知识图谱（实体 + 关系），
与本 skill 互补：语义记忆管**非结构化经验**，图谱管**结构化事实**（谁是什么、谁依赖谁）。

**2026-10-10 用户拍板退役**，原因（详见归档 README 与 backlog §81）：
- 09-28 实锤它**无持久化**（每次调用 spawn 新 session，create 后 read=0）；修法把
  `MEMORY_FILE_PATH` 写进 config，但 **daemon 不把 env 传给 STDIO 子进程**（app 侧缺口），从未修好。
- 随后的 fallback 是"直写 shared 文件"，但那两个脚本**自身仍调用 `minis-mcp-cli call memory`**
  → 接口已不在注册表，它们也跑不动。
- 退役时数据 21 实体 / 24 关系（停在 09-21），live 与备份一致故无丢失。

**现在该问结构化事实时**：用 `memory_get`（关键词）或读 `GLOBAL.md`（账号/基础设施/环境章节）——
实体关系网与 GLOBAL 的账号地图本来就有重叠。**不要再尝试 `minis-mcp-cli call memory`**（返回 NOT_FOUND）。

**退役资产**：`/var/minis/shared/archive/retired-knowledge-graph-2026-10/`（数据 + 脚本 + 退役说明）。
复活须先修 app 侧 env 透传缺口，触发条件见 `backlog.md` §81。

## Agent 使用约定
- 每个会话启动时：用当前任务的 2-3 个核心关键词做一次 semantic search
- 不要等用户要求才查——主动查，主动引用历史经验
- **判断相关性看 cos 列（> 0.35 视为相关），混合分只用于排序；两者背离时以 cos 为准**
  （混合分含关键词加分封顶 +0.65，cos=0 的条目也能靠标题命中越过 0.35 —— 单看混合分会把关键词噪音误判为相关）
- 发现重要新经验时：用 memory_write 写入 daily log（本地），然后在本会话结束时提醒用户运行 build