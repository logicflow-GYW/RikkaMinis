#!/usr/bin/python3
"""evals 检查器 —— skill 触发条件的静态 + 行为验证（backlog §22b，marketingskills 范式）。

范式: 每个 skill 一个 evals.json（3-5 条），prompt 用真实口语，第一条断言固定是
      「触发检查」——把「触发是否真的发生、是否误触发」也当可验证项。
      静态模式查格式与触发词覆盖；run 模式用 minis-model-use 真跑一次「该不该触发」，
      这是唯一能验证触发鲁棒性的手段（它是行为，不是字面量）。

用法:
  check_evals.py static [<skill dir> ...] [--strict]    默认扫 /var/minis/skills
  check_evals.py run <skill> [--model <m>] [--max N] [--dry]
                             [--interval <s>] [--retry-wait <s>]
  check_evals.py --self-test                             夹具自测（离线，含反向对照）
退出码: 0 通过 / 1 存在错误 / 2 用法或输入错 / 3 run 模式有 UNKNOWN 且无 FAIL

run 模式的三态口径（2026-09-26 修）:
  PASS / FAIL  = 拿到了可判读的输出，这是关于「触发面」的证据。
  UNKNOWN      = 没拿到可判读的输出（无输出文件 / 限流 429 / 空文本）——
                 这是关于「调用」的证据，不是触发面失败。
  旧口径把「调用失败」直接记 FAIL：2026-09-25 批量跑 evals 打爆网关 rpm，
  3 分钟内 25 次 429 全被写成「触发面 FAIL」，污染了 evals 数据。现在调用
  级失败单独计数，并用退出码 3 表示「结果不完整」，绝不与 PASS 混同。
  调用间隔默认 5s（--interval，0 = 关闭）且限流后 15s 退避重试一次。
"""
import io, json, os, sys, time

DEF_SKILLS = '/var/minis/skills'
DEF_MODEL = '1d9b6f6c-2cee-4ea1-8868-3081aca5b284/deepseek-v4-flash'  # entry_id 前缀为 provider 实例 UUID，实例重建即失效；失效时用 --model 覆盖
TRIGGER_HINTS = ('触发', 'triger', 'trigger')
DEF_INTERVAL_S = 5.0
DEF_RETRY_WAIT_S = 15.0
CALL_ATTEMPTS = 2
# 网关把「被限流」写成 HTTP 429 / rate_limit / insufficient_quota 等形态，
# 统一嗅探；只在「没有可用文本」时才用它判定（有正常文本时一律不信这些字样，
# 避免正常回答里出现 "429" 被误判成限流）。
RATE_LIMIT_HINTS = ('429', 'rate_limit', 'ratelimit', 'too many requests',
                    'insufficient_quota', 'endpointtpmexceeded', 'rpm limit')
# 调用入口。留成模块级常量是为了让 --self-test 能指向夹具脚本：
# PRoot 的 native-offload 层按「名字」拦住真的 minis-model-use，PATH 前缀无效，
# 只有绝对路径能绕开。ponytail: 为可测性留的缝 | 天花板: 只用于测试替身，
# 生产路径永远是原名 | 升级触发: 若将来调用入口改成内嵌 SDK，这里一并删掉。
EXEC_BIN = 'minis-model-use'


def read(p):
    f = io.open(p, encoding='utf-8')
    try:
        return f.read()
    finally:
        f.close()


def frontmatter(text):
    """取 SKILL.md frontmatter 的键值（简版：单行键）。"""
    out = {}
    if not text.startswith('---'):
        return out
    end = text.find('\n---', 4)
    if end < 0:
        return out
    for ln in text[4:end].split('\n'):
        i = ln.find(':')
        if i > 0:
            out[ln[:i].strip()] = ln[i + 1:].strip()
    return out


def desc_tokens(desc):
    """抽 description 里「」包裹的触发词（1<长度<=12，去重）。"""
    out, cur, inb = [], '', False
    for ch in desc:
        if ch == '「':
            inb, cur = True, ''
        elif ch == '」':
            if inb and 1 < len(cur) <= 12 and cur not in out:
                out.append(cur)
            inb = False
        elif inb:
            cur += ch
    return out


def token_hits(tok, text):
    """description 触发词是否被 prompt 覆盖。含占位符 N（如「拆任务给 N 个会话」）时
    按 'N' 切片段、要求各片段都出现（顺序无关）。"""
    parts = [p.strip() for p in tok.split('N')]
    parts = [p for p in parts if len(p) >= 2]
    if not parts:
        return tok.lower() in text
    for p in parts:
        if p.lower() not in text:
            return False
    return True


def skill_dirs(paths):
    """返回 [(name, dir)]，只含带 SKILL.md 的目录。"""
    out = []
    for it in paths:
        if os.path.isdir(it):
            if os.path.isfile(os.path.join(it, 'SKILL.md')):
                out.append((os.path.basename(it) if hasattr(os.path, 'basename') else it, it))
            else:
                for root, dirs, files in os.walk(it):
                    if 'SKILL.md' in files:
                        out.append((os.path.basename(root) or root, root))
    return sorted(out)


def check_skill(name, d, strict):
    """静态检查一个 skill。返回 (errs, werns, covered)。"""
    errs, werns = [], []
    ep = os.path.join(d, 'evals.json')
    if not os.path.isfile(ep):
        if strict:
            errs.append('缺 evals.json（--strict 下算错）')
        return errs, werns, False
    try:
        obj = json.loads(read(ep))
    except Exception as e:
        errs.append('evals.json 无法解析: %s' % e)
        return errs, werns, True
    for k in ('skill', 'version', 'evals'):
        if k not in obj:
            errs.append('缺字段: %s' % k)
    if 'skill' in obj and obj['skill'] != name:
        werns.append('skill 名与目录不一致: %s vs %s' % (obj.get('skill'), name))
    evs = obj.get('evals', [])
    if len(evs) < 3:
        werns.append('条目 %s 条（约定 3-5）' % len(evs))
    ids, prompts = [], []
    for i, e in enumerate(evs):
        tag = 'eval#%s' % e.get('id', i + 1)
        for k in ('id', 'prompt', 'assertions', 'expect_triger'):
            if k not in e:
                errs.append('%s 缺字段 %s' % (tag, k))
        if 'id' in e:
            if e['id'] in ids:
                errs.append('%s id 重复' % tag)
            ids.append(e['id'])
        ass = e.get('assertions', [])
        if len(ass) < 2:
            errs.append('%s 断言 %s 条（至少 2 条：触发检查 + 关键动作）' % (tag, len(ass)))
        if ass and not any(k in ass[0].lower() for k in TRIGGER_HINTS):
            errs.append('%s 第一条断言不是触发检查: %s' % (tag, ass[0][:40]))
        if 'expect_triger' in e and type(e['expect_triger']) != bool:
            errs.append('%s expect_triger 不是布尔' % tag)
        if 'prompt' in e:
            prompts.append(e['prompt'])
    fm = frontmatter(read(os.path.join(d, 'SKILL.md')))
    toks = desc_tokens(fm.get('description', ''))
    pos = [e.get('prompt', '') for e in evs if e.get('expect_triger') is True]
    j = '\n'.join(pos).lower()
    missing = [t for t in toks if not token_hits(t, j)]
    if toks and missing:
        werns.append('description 触发词未出现在任何应触发的 prompt: %s' % ', '.join(missing[:6]))
    if toks and pos:
        naked = [p for p in pos if not any(token_hits(t, p.lower()) for t in toks)]
        if not naked:
            werns.append('所有应触发的 prompt 都含触发词 → 缺「口语脱轨」例（鲁棒性没被测）')
    if evs and not any(e.get('expect_triger') == False for e in evs):
        werns.append('无负例（expect_triger=false）→ 未验证「不该触发时不误触发」')
    return errs, werns, True


def build_request(name, desc, prompt, max_tokens):
    """构造行为版请求（纯函数，可离线测）。"""
    sysmsg = ('你可以使用以下 skill（元数据在上下文里，只有 name + description）：\n'
              '- ' + name + ': ' + desc + '\n\n'
              '用户下一句会说一件事。若它应当触发上面这个 skill 的正文加载，答 TRIGGER；'
              '否则答 NO_TRIGGER。只输出一个词，不要解释。')
    return {'messages': [{'role': 'system', 'content': sysmsg},
                         {'role': 'user', 'content': prompt}],
            'max_tokens': max_tokens, 'temperature': 0}


def parse_verdict(text):
    """从模型输出取判定：True=触发 / False=不触发 / None=无法判读。"""
    t = (text or '').upper()
    if 'NO_TRIGGER' in t or 'NOT_TRIGGER' in t or 'NO TRIGGER' in t:
        return False
    if 'TRIGGER' in t:
        return True
    return None


def looks_rate_limited(raw):
    """输出体是否像「被网关限流/拒绝」（纯函数，可离线断言）。

    只在「没有可用文本」时才有判定意义——调用方必须先确认拿不到文本，
    否则正常回答里出现 "429" 会被误判。
    """
    t = (raw or '').lower()
    return any(h in t for h in RATE_LIMIT_HINTS)


def extract_call_error(errf):
    """从被保留的调用输出里提取一条人类可读的失败原因（纯函数，可离线断言）。

    动机（2026-10-03 实证）：模型配置更名后默认模型批量调用失败，肉眼只看到
    'no output file'，真正原因（model_not_found）被 `> /dev/null` 吞掉，白烧一轮。
    """
    try:
        raw = read(errf)
    except Exception:
        return ''
    for ln in (raw or '').splitlines():
        ln = ln.strip()
        if not ln or ln.startswith('proot info'):
            continue
        try:
            jo = json.loads(ln)
            if type(jo) == dict and 'error' in jo:
                return str(jo.get('error'))[:80]
        except Exception:
            pass
    for ln in reversed((raw or '').splitlines()):
        ln = ln.strip()
        if ln and not ln.startswith('proot info'):
            return ln[:80]
    return ''


def call_model(model, req, tag, retry_wait=DEF_RETRY_WAIT_S, attempts=CALL_ATTEMPTS):
    """真调一次模型，返回 (status, text, detail)。

    status: 'OK' = 拿到可判读文本；'UNKNOWN' = 没拿到（无输出文件 / 限流 / 空文本）。
    detail: 人类可读的原因，进表格（如 'no output file' / 'rate_limited'）。

    限流（或任何没有文本的失败）退避 `retry_wait` 秒后重试一次——旧实现是
    顺序无间隔硬打，批量跑会打爆网关 rpm 且把 429 记成 FAIL。
    """
    d = '/tmp/evals-run'
    try:
        os.mkdir(d)
    except Exception:
        pass
    inp = os.path.join(d, tag + '.in.json')
    out = os.path.join(d, tag + '.out.json')
    w = io.open(inp, 'w', encoding='utf-8')
    w.write(json.dumps(req))
    w.close()
    errf = os.path.join(d, tag + '.err')
    cmd = ('"' + EXEC_BIN + '" run --model "' + model + '" --input ' + inp
           + ' --output ' + out + ' > ' + errf + ' 2>&1')
    detail = 'no output file'
    for attempt in range(max(1, attempts)):
        if attempt:
            time.sleep(retry_wait)
        # 必须先删旧输出：调用失败（模型不可用/网络错）时不会生成新文件，
        # 残留的上一次结果会被 parse_verdict 读成 PASS —— 把「调用失败」报成「通过」。
        # 2026-09-21 实测踩中：1-5 读的是三天前的 out.json，全判 PASS。
        try:
            os.remove(out)
        except Exception:
            pass
        os.system(cmd)
        if not os.path.isfile(out):
            why = extract_call_error(errf)
            detail = 'no output file' if not why else 'no output file: ' + why
            continue
        raw = read(out)
        obj = None
        try:
            obj = json.loads(raw)
        except Exception:
            obj = None
        text = None
        if type(obj) == dict:
            if 'text' in obj:
                text = obj['text']
            elif 'output' in obj:
                text = obj['output']
        elif obj is None:
            text = raw
        if not (text or '').strip():
            detail = 'rate_limited' if looks_rate_limited(raw) else 'empty output'
            continue
        return 'OK', text, 'ok'
    return 'UNKNOWN', None, detail


def cmd_static(paths, strict):
    dirs = skill_dirs(paths)
    if not dirs:
        print('Error: no skill dir with SKILL.md under:', paths)
        return 2
    n_ok, n_err, n_cover = 0, 0, 0
    print('## evals 静态检查')
    print('')
    print('| skill | 状态 | 覆盖 | 说明 |')
    print('|---|---|---|---|')
    for name, d in dirs:
        errs, werns, covered = check_skill(name, d, strict)
        if covered:
            n_cover += 1
        if errs:
            n_err += 1
        else:
            n_ok += 1
        st = 'OK' if not errs else '**ERR %s**' % len(errs)
        notes = '; '.join(errs + [('warn: ' + w) for w in werns])
        print('| %s | %s | %s | %s |' % (name, st, 'yes' if covered else 'no', notes.replace('|', '\\|')))
    print('')
    print('计: %s skill / 覆盖 evals %s / 错误 %s' % (len(dirs), n_cover, n_err))
    return 1 if n_err else 0


def cmd_run(args):
    skill = None
    model = DEF_MODEL
    maxn = 0
    dry = False
    interval = DEF_INTERVAL_S
    retry_wait = DEF_RETRY_WAIT_S
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--model' and i + 1 < len(args):
            model = args[i + 1]
            i += 2
            continue
        if a == '--max' and i + 1 < len(args):
            maxn = int(args[i + 1])
            i += 2
            continue
        if a == '--interval' and i + 1 < len(args):
            interval = float(args[i + 1])
            i += 2
            continue
        if a == '--retry-wait' and i + 1 < len(args):
            retry_wait = float(args[i + 1])
            i += 2
            continue
        if a == '--dry':
            dry = True
            i += 1
            continue
        if skill is None:
            skill = a
        i += 1
    if skill is None:
        print('Error: run 需要 <skill 名或目录>')
        return 2
    d = skill
    if not os.path.isdir(d):
        d = os.path.join(DEF_SKILLS, skill)
    if not os.path.isdir(d):
        print('Error: no such skill dir:', skill)
        return 2
    name = skill
    fm = frontmatter(read(os.path.join(d, 'SKILL.md')))
    desc = fm.get('description', '')
    try:
        obj = json.loads(read(os.path.join(d, 'evals.json')))
    except Exception as e:
        print('Error: cannot load evals.json:', e)
        return 2
    evs = obj.get('evals', [])
    if maxn > 0:
        evs = evs[:maxn]
    print('## evals 行为版（模型: %s，间隔 %ss，限流退避 %ss）' % (model, interval, retry_wait))
    print('')
    print('| eval | 期望 | 实得 | 判定 |')
    print('|---|---|---|---|')
    n_fail, n_done, n_unknown = 0, 0, 0
    for idx, e in enumerate(evs):
        tag = '%s-%s' % (name, e.get('id', '?'))
        req = build_request(name, desc, e.get('prompt', ''), 16)
        if dry:
            print('| %s | %s | (dry) | - |' % (tag, e.get('expect_triger')))
            continue
        # 节流：条目之间留出间隔，避免批量跑把网关 rpm 打爆（2026-09-25 事故）。
        if idx > 0 and interval > 0:
            time.sleep(interval)
        status, txt, detail = call_model(model, req, tag, retry_wait)
        exp = e.get('expect_triger')
        n_done += 1
        if status != 'OK':
            # 调用级失败：没有关于触发面的证据，不能记 FAIL（假红会污染数据）。
            n_unknown += 1
            print('| %s | %s | UNKNOWN (%s) | ~ 未判定 |' % (tag, exp, detail))
            continue
        v = parse_verdict(txt)
        if v is None:
            # 拿到了文本但读不出判定（模型跑题/乱答）——这是判读失败，
            # 不属于「调用没拿到证据」，保持 FAIL 语义以保住检测力。
            n_fail += 1
            print('| %s | %s | (unparseable) %s | **FAIL** |'
                  % (tag, exp, str(txt)[:40].replace('|', '\\|')))
            continue
        okj = 'PASS' if v == exp else '**FAIL**'
        if v != exp:
            n_fail += 1
        print('| %s | %s | %s | %s |' % (tag, exp, v, okj))
    print('')
    print('计: 跑 %s 条 / 失败 %s 条 / 未判定 %s 条' % (n_done, n_fail, n_unknown))
    if dry:
        return 0
    if n_fail:
        return 1
    if n_unknown:
        print('!! 结果不完整：%s 条 UNKNOWN（调用级失败，不是触发面失败）——'
              '不要把它当作 PASS 记录，重跑一次再下结论。' % n_unknown)
        return 3
    return 0


def self_test():
    base = '/tmp/evals-selftest'
    d = os.path.join(base, 'fixture-skill')
    try:
        os.mkdir(base)
    except Exception:
        pass
    try:
        os.mkdir(d)
    except Exception:
        pass
    fm = ('---\n'
          'name: fixture-skill\n'
          'description: 夹具 skill。当用户说「派发任务」「分派」时触发。核心规则：拆完写文件。\n'
          'version: 1.0.0\n'
          '---\n# 夹具\n')
    evs = {
        'skill': 'fixture-skill', 'version': '1.0.0',
        'evals': [
            {'id': 1, 'prompt': '把这件事分派出去，分给三个会话',
             'expect_triger': True,
             'assertions': ['触发检查：含「派发/分派」类意图即触发', '任务文件写在共享目录']},
            {'id': 2, 'prompt': '这些任务我想让几个会话同时做，别串行干',
             'expect_triger': True,
             'assertions': ['触发检查：口语化表述也触发（不含触发词）', '按冲突矩阵拆']},
            {'id': 3, 'prompt': '帮我把这个 Kotlin 报错修一下',
             'expect_triger': False,
             'assertions': ['触发检查：不该触发（这是编译问题，不是派发）', '不生成任务文件']},
        ],
    }
    w = io.open(os.path.join(d, 'SKILL.md'), 'w', encoding='utf-8')
    w.write(fm)
    w.close()
    w = io.open(os.path.join(d, 'evals.json'), 'w', encoding='utf-8')
    w.write(json.dumps(evs))
    w.close()

    fails = []
    errs, werns, covered = check_skill('fixture-skill', d, True)
    print('[self-test] 合格夹具: errs=%s werns=%s covered=%s（期望 0 / ≥0 / True）'
          % (len(errs), len(werns), covered))
    if errs or not covered:
        fails.append('合格夹具被误报错: %s' % errs)

    # 反向对照：破坏两处（第一条断言去掉触发检查 + 删 expect_triger）
    bad = json.loads(json.dumps(evs))
    bad['evals'][0]['assertions'][0] = '任务文件写在共享目录'
    del bad['evals'][1]['expect_triger']
    w = io.open(os.path.join(d, 'evals.json'), 'w', encoding='utf-8')
    w.write(json.dumps(bad))
    w.close()
    errs2, werns2, _ = check_skill('fixture-skill', d, True)
    print('[reverse] 破坏两处后 errs=%s（期望 ≥2）: %s' % (len(errs2), errs2))
    if len(errs2) < 2:
        fails.append('反向对照失败：破坏后未被报错')

    # 纯函数测试
    req = build_request('s', '描述文本', '用户话', 16)
    if len(req['messages']) != 2 or 's: 描述文本' not in req['messages'][0]['content']:
        fails.append('build_request 形态错')
    for txt, exp in (('TRIGGER', True), ('NO_TRIGGER', False), ('text: NO_TRIGGER。', False),
                     ('bla', None), ('No trigger here', False)):
        got = parse_verdict(txt)
        if got != exp:
            fails.append('parse_verdict(%r) = %r 期望 %r' % (txt, got, exp))
    print('[self-test] 纯函数: build_request / parse_verdict 已断言')
    if not token_hits('拆任务给 N 个会话', '帮我拆任务给 3 个会话试试'):
        fails.append('token_hits 占位符归一化失败')
    if token_hits('拆任务给 N 个会话', '帮我拆点东西'):
        fails.append('token_hits 误中')
    print('[self-test] 纯函数: token_hits 已断言')

    # 限流嗅探（纯函数）
    for raw, exp in (('{"error":{"code":429}}', True),
                     ('inference exceeds tpm/rpm limit', True),
                     ('insufficient_quota', True),
                     ('{"text":"TRIGGER"}', False),
                     ('', False)):
        if looks_rate_limited(raw) != exp:
            fails.append('looks_rate_limited(%r) = %r 期望 %r'
                         % (raw, looks_rate_limited(raw), exp))
    print('[self-test] 纯函数: looks_rate_limited 已断言')

    # 行为夹具：假的 minis-model-use，精确复现「调用级失败」的三种形态。
    # 这是本次修复的核心——UNKNOWN 必须与 FAIL 分开，且限流要退避重试一次。
    binp = os.path.join(base, 'bin')
    try:
        os.mkdir(binp)
    except Exception:
        pass
    # 名字必须避开 'minis-model-use'：PRoot 的 offload 层按文件名 hook，连绝对
    # 路径都会被它接管（实测 exit=2 + 不写输出文件），夹具跑不起来。
    fake = os.path.join(binp, 'model-runner-fixture')
    w = io.open(fake, 'w', encoding='utf-8')
    w.write('#!/bin/sh\n'
            'out=""; prev=""\n'
            'for a in "$@"; do\n'
            '  if [ "$prev" = "--output" ]; then out="$a"; fi\n'
            '  prev="$a"\n'
            'done\n'
            'echo x >> "$COUNT_FILE"\n'
            'case "$MODE" in\n'
            '  ok) printf \'{"text":"TRIGGER"}\' > "$out" ;;\n'
            '  silent) : ;;\n'
            '  rate) printf \'{"error":{"code":429,"message":"rate_limit exceeded"}}\' > "$out" ;;\n'
            'esac\n')
    w.close()
    os.chmod(fake, 0o755)
    global EXEC_BIN
    oldbin = EXEC_BIN
    EXEC_BIN = fake
    cnt = os.path.join(base, 'calls.txt')
    os.environ['COUNT_FILE'] = cnt
    req2 = build_request('s', 'd', 'p', 16)
    try:
        for mode, exp_status, exp_detail in (('ok', 'OK', 'ok'),
                                             ('silent', 'UNKNOWN', 'no output file'),
                                             ('rate', 'UNKNOWN', 'rate_limited')):
            os.environ['MODE'] = mode
            wc = io.open(cnt, 'w')
            wc.write('')
            wc.close()
            st, txt, detail = call_model('fixture-model', req2, 'selftest-' + mode,
                                         retry_wait=0.05)
            calls = len(read(cnt).split())
            print('[self-test] 夹具 %-6s -> status=%s detail=%s calls=%s'
                  % (mode, st, detail, calls))
            if (st, detail) != (exp_status, exp_detail):
                fails.append('夹具 %s: 得 (%s, %s) 期望 (%s, %s)'
                             % (mode, st, detail, exp_status, exp_detail))
            # 期望值写死字面量 2，绝不从 CALL_ATTEMPTS 推导：夹具若读被测常量，
            # 把常量改成 1（= 去掉重试）时期望值跟着变成 1，变异就永远不被抓
            # —— 2026-09-26 实测踩中（变异 C1 伪装成 PASS）。
            exp_calls = 2 if mode != 'ok' else 1
            if calls != exp_calls:
                fails.append('夹具 %s: 调用 %s 次 期望 %s 次（应退避重试）'
                             % (mode, calls, exp_calls))
    finally:
        EXEC_BIN = oldbin
        os.environ.pop('MODE', None)
    print('[self-test] 调用级失败与 FAIL 分离 + 限流退避已断言')

    if fails:
        for f in fails:
            print('FAIL:', f)
        return 1
    print('PASS: 静态检查两态正确 + 反向对照 + 纯函数断言通过')
    return 0


def _args():
    a = sys.argv
    if len(a) > 0 and a[0].lower().endswith('.py'):
        return a[1:]
    return a


def main(argv):
    if len(argv) < 1:
        print(__doc__)
        return 2
    cmd = argv[0]
    args = argv[1:]
    if cmd == '--self-test':
        return self_test()
    if cmd == 'static':
        strict = '--strict' in args
        paths = [a for a in args if a != '--strict']
        return cmd_static(paths if paths else [DEF_SKILLS], strict)
    if cmd == 'run':
        return cmd_run(args)
    print('Error: unknown subcommand:', cmd)
    print(__doc__)
    return 2


sys.exit(main(_args()))
