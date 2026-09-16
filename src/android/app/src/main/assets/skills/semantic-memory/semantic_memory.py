#!/usr/bin/env python3
"""
RikkaMinis 语义记忆引擎
基于 HF Dataset + HF Inference (embeddings) 实现语义搜索

用法:
  python3 semantic_memory.py build     # 从 daily logs 提取→向量化→上传
  python3 semantic_memory.py search "<query>"  # 语义搜索
  python3 semantic_memory.py status    # 显示状态
"""

import os, json, re, pickle, math, sys, argparse
from pathlib import Path
from huggingface_hub import HfApi, InferenceClient

# ─── 配置 ───
EMBED_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
MEMORY_DIR = Path("/var/minis/memory")
SKILL_DIR = Path(__file__).resolve().parent  # 脚本所在目录（skills/semantic-memory）
WORK_DIR = SKILL_DIR
INDEX_FILE = WORK_DIR / "vector_index.pkl"

api = HfApi()
HF_USER = None  # 模块加载时不做任何网络调用（hf_username() 惰性获取）
DATASET = None  # 由 hf_username() 惰性初始化


def hf_username():
    """惰性获取 HF 用户名（需要联网）；失败时返回 None。"""
    global HF_USER, DATASET
    if HF_USER is None:
        try:
            HF_USER = api.whoami()["name"]
            DATASET = f"{HF_USER}/rikkaminis-memory"
        except Exception as e:
            print(f"⚠️  无法连接 Hugging Face（{e.__class__.__name__}）", file=sys.stderr)
            return None
    return HF_USER

# ─── 工具函数 ───

def cosine(a, b):
    dot = sum(x*y for x,y in zip(a,b))
    na = math.sqrt(sum(x*x for x in a))
    nb = math.sqrt(sum(y*y for y in b))
    return dot/(na*nb) if na*nb else 0


def extract_entries(glob_pattern="2026-*.md"):
    """从 daily logs 提取经验条目（按 ## 标题分割）"""
    skip_patterns = [
        "These are memories saved by you",
        "These are memories",
        "The following are memories",
        "auto-injected from daily",
        "These are",
    ]
    entries = []
    for md_file in sorted(MEMORY_DIR.glob(glob_pattern), reverse=True):
        text = md_file.read_text()
        sections = re.split(r'\n(?=## )', text)
        for sec in sections:
            sec = sec.strip()
            if not sec or len(sec) < 50:
                continue
            if any(sec.startswith(p) or p in sec[:200] for p in skip_patterns):
                continue
            title_match = re.match(r'^##\s+(.+)', sec)
            title = title_match.group(1).strip() if title_match else sec[:80]
            entries.append({
                "title": title,
                "content": sec,
                "source": str(md_file.name),
                "date": str(md_file.name).replace(".md", ""),  # YYYY-MM-DD（时间衰减用）
                "chars": len(sec),
            })
    return entries


def embed_texts(entries, client=None):
    """对条目列表做向量化，原地添加 embedding 字段"""
    if client is None:
        client = InferenceClient()
    total = len(entries)
    for i, e in enumerate(entries):
        text = (e["title"] + "\n" + e["content"])[:512]
        try:
            vec = client.feature_extraction(text=text, model=EMBED_MODEL)
            if hasattr(vec, "tolist"):
                vec = vec.tolist()
            e["embedding"] = vec
        except Exception as ex:
            print(f"  ⚠️ 向量化失败 [{i+1}/{total}]: {ex}")
            e["embedding"] = None
        if (i+1) % 20 == 0:
            print(f"  向量化进度: {i+1}/{total}")
    # 过滤失败条目
    return [e for e in entries if e.get("embedding") is not None]


def upload_to_hf(entries):
    """上传经验到 HF Dataset（需联网；未登录/断网时提示后跳过）"""
    user = hf_username()
    if not user:
        print("⚠️  跳过上传：无法连接 Hugging Face（本地索引仍会保存）")
        return None
    global DATASET
    api.create_repo(repo_id=DATASET, repo_type="dataset", private=True, exist_ok=True)
    import tempfile
    tmp = Path(tempfile.mkdtemp()) / "memory.jsonl"
    with open(tmp, "w") as f:
        for e in entries:
            rec = {k: v for k, v in e.items() if k != "embedding"}
            rec["embed_sig"] = [round(e["embedding"][i], 4) for i in range(min(8, len(e["embedding"])))]
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    api.upload_file(
        path_or_fileobj=str(tmp),
        path_in_repo="memory.jsonl",
        repo_id=DATASET,
        repo_type="dataset",
    )
    return DATASET


def recency_weight(date_str, now=None):
    """时间衰减权重：exp(-lambda * age_days)。与 Kotlin 侧
    MemoryRepository.memoryRecencyWeight 对齐（lambda=0.04，当天=1.0，
    30 天前≈0.30）。无日期/格式错/未来日期 → 1.0（不惩罚）。"""
    import datetime
    LAMBDA = 0.04
    if not date_str:
        return 1.0
    try:
        d = datetime.date.fromisoformat(date_str)
    except (ValueError, TypeError):
        return 1.0
    if now is None:
        now = datetime.date.today()
    age = (now - d).days
    if age <= 0:
        return 1.0
    return math.exp(-LAMBDA * age)

def tokenize(text):
    """查询/条目文本分词：英文/数字整词 + 中文 bigram（无 jieba 依赖，
    对覆盖率统计够用）。[feat/hybrid-scoring]"""
    text = text.lower()
    tokens = set(re.findall(r"[a-z0-9_]{2,}", text))
    cjk = re.findall(r"[\u4e00-\u9fff]", text)
    for i in range(len(cjk) - 1):
        tokens.add(cjk[i] + cjk[i + 1])
    return tokens

RECENCY_BOOST = 0.1  # 时间衰减的最大加分（加性，见 hybrid_score 说明）


def hybrid_score(query, vec, e, today):
    """混合评分（吸收 Operit memory_candidate_scoring 公式，简化版）：

    S = S_sem + S_cov + S_title_kw + S_rev + S_recency

    - S_sem = cosine（**不再被时间衰减乘性侵蚀**，见下方「2026-09-16 修正」）
    - S_cov = cosine × 0.6 × (查询 token 在条目内容中的覆盖率)
      —— Operit 覆盖率增益 λ=1+0.6·c/F：内容覆盖查询越多，越可能是"正面回答"
    - S_title_kw = 标题命中加分（每命中一个查询 token +0.15，封顶 0.45）
      —— 对应 Operit S_kw 排名衰减项；标题命中 = 条目主题与查询同域的强信号
    - S_rev = 查询文本直接包含条目标题（截 20 字）→ +0.2
      —— 对应 Operit S_rev 反向包含：用户措辞里嵌着条目标题，几乎必然相关
    - S_recency = RECENCY_BOOST × recency_weight（0~0.1，**加性助推**）

    ── 2026-09-16 修正（recency 从「乘性侵蚀」改为「加性助推」）──

    原式 `S_sem = cosine × recency_weight` 让时间衰减**乘性地**削掉语义信号，
    造成两个可实测的失效（1070 条语料，24 查询实测）：

    1. **老条目够不到阈值**：cosine ≤ 1，故 30 天前的条目（rw=0.301）语义项
       单独过 0.35 需 cos=1.16 —— 数学上不可能。本语料 42% 的条目 >30 天。
    2. **返回结果被系统性推向新近**：top-5 年龄中位 3 天，而语料基线 27 天。

    修正后实测（同一语料/查询）：top-5 年龄中位 3→23 天（回到基线附近），
    top-5 真实 cos 中位 0.403→0.446，其中 cos<0.35 的假阳性比例 30%→12%；
    手工标注 recall@5 9→9（17 条标注，差异在噪声内）。
    时间衰减仍保留为**有界加分**（≤0.1），只做"同样相关时偏好新近"，不再能压倒语义。

    图传播项 S_graph 未吸收（需先有记忆关系图，后置）。"""
    cos = cosine(vec, e["embedding"])
    sem = cos  # 语义基分 = 原始 cosine，不被衰减侵蚀

    q_tokens = tokenize(query)
    title_l = e["title"].lower()
    content_l = e["content"][:4000].lower()
    if q_tokens:
        covered = sum(1 for t in q_tokens if t in content_l)
        cov = cos * 0.6 * (covered / len(q_tokens))
        title_hits = sum(1 for t in q_tokens if t in title_l)
        s_title = min(0.15 * title_hits, 0.45)
    else:
        cov, s_title = 0.0, 0.0

    title_head = title_l[:20]
    s_rev = 0.2 if title_head and title_head in query.lower() else 0.0
    s_recency = RECENCY_BOOST * recency_weight(e.get("date"), today)

    return sem + cov + s_title + s_rev + s_recency, cos, sem

def search(query, top_k=5, client=None, compare=False):
    """混合评分搜索（语义 × 时间衰减 × 关键词覆盖/标题命中）。
    compare=True 时同时输出旧评分（纯 cosine×recency）做 A/B 对比。"""
    if not INDEX_FILE.exists():
        print("❌ 索引文件不存在，请先运行 build")
        return []
    if client is None:
        client = InferenceClient()
    idx = pickle.loads(INDEX_FILE.read_bytes())
    entries = idx["entries"]

    vec = client.feature_extraction(text=query[:512], model=EMBED_MODEL)
    if hasattr(vec, "tolist"):
        vec = vec.tolist()

    import datetime
    today = datetime.date.today()
    scored = []
    for i, e in enumerate(entries):
        s, sim, sem = hybrid_score(query, vec, e, today)
        scored.append((s, sim, i, e))
    scored.sort(key=lambda t: (t[0], -t[2]), reverse=True)
    results = [(s, _sim, e) for s, _sim, _i, e in scored[:top_k]]

    if compare:
        legacy = sorted(scored, key=lambda t: (t[1], -t[2]), reverse=True)
        legacy_top = [e for _s, _sim, _i, e in legacy[:top_k]]
        return results, legacy_top, [(s, sim) for s, sim, _i, e in scored[:top_k]]
    return results


def print_search_results(results):
    """格式化打印搜索结果。

    同时打印混合分与真实 cosine：SKILL.md 的「相似度 > 0.35 视为相关」
    只能作用在 cos 上 —— 混合分含关键词加分（封顶 +0.65），cos=0 的条目
    也能越过 0.35，单看混合分会把关键词噪音误判为相关。"""
    if not results:
        print("  无结果")
        return
    for rank, (score, cos, e) in enumerate(results, 1):
        title = e["title"][:80]
        src = e.get("source", "?")
        date = e.get("date", "")
        print(f"  {rank}. [{src}{('|' + date) if date else ''}] (混合 {score:.3f} / cos {cos:.3f}) {title}")


# ─── 子命令 ───

def cmd_build():
    print("📦 从 daily logs 提取经验...")
    entries = extract_entries()
    print(f"  提取 {len(entries)} 条")

    print("🧠 向量化（HF Inference）...")
    client = InferenceClient()
    entries = embed_texts(entries, client)
    print(f"  成功: {len(entries)} 条")

    print(f"☁️  上传到 HF Dataset: {DATASET}")
    uploaded = upload_to_hf(entries)
    if uploaded:
        print(f"  ✅ 已上传")

    # 保存本地索引
    idx_data = {"entries": entries, "model": EMBED_MODEL, "count": len(entries)}
    INDEX_FILE.write_bytes(pickle.dumps(idx_data))
    print(f"  📍 本地索引: {INDEX_FILE} ({INDEX_FILE.stat().st_size} bytes)")
    if uploaded:
        print(f"\n📍 HF: https://huggingface.co/datasets/{DATASET}")


def cmd_search(query, compare=False):
    if compare:
        print(f'🔍 语义搜索 A/B 对比: "{query}"\n')
        results, legacy_top, parts = search(query, compare=True)
        print("── 混合评分（新：语义×覆盖增益 + 标题命中 + 反向包含）──")
        print_search_results(results)
        print("\n── 纯语义×时间衰减（旧）──")
        for rank, e in enumerate(legacy_top, 1):
            print(f"  {rank}. [{e.get('source','?')}|{e.get('date','')}] {e['title'][:80]}")
        print("\n── top-5 分数构成（混合 | 纯语义 cos 基分）──")
        for (s, cos), (_score, _c, e) in zip(parts, results):
            delta = s - cos
            print(f"  {s:.3f} | cos 基分 {cos:.3f} + 助推 {delta:+.3f}  {e['title'][:60]}")
        return
    print(f'🔍 语义搜索: "{query}"\n')
    results = search(query)
    print_search_results(results)


def cmd_status():
    if INDEX_FILE.exists():
        idx = pickle.loads(INDEX_FILE.read_bytes())
        print(f"索引条目: {idx['count']}")
        print(f"嵌入模型: {idx['model']}")
        print(f"索引文件: {INDEX_FILE} ({INDEX_FILE.stat().st_size} bytes)")
    else:
        print("❌ 索引不存在")
    user = hf_username()
    if user:
        print(f"HF Dataset: https://huggingface.co/datasets/{DATASET}")
    else:
        print("HF Dataset: （离线，无法获取）")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="RikkaMinis 语义记忆引擎")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("build", help="提取→向量化→上传")
    sub.add_parser("search").add_argument("query", help="搜索查询")
    compare_parser = sub.add_parser("compare", help="A/B 对比：混合评分 vs 纯语义")
    compare_parser.add_argument("query", help="搜索查询")
    sub.add_parser("status", help="显示状态")

    args = parser.parse_args()
    if args.cmd == "build":
        cmd_build()
    elif args.cmd == "search":
        cmd_search(args.query)
    elif args.cmd == "compare":
        cmd_search(args.query, compare=True)
    elif args.cmd == "status":
        cmd_status()
    else:
        parser.print_help()
