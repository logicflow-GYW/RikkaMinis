#!/usr/bin/env python3
"""deep-audit-1018 ISSUE-10 (P1) — router health lock + slot-promotion resume gate.

Pins the two contracts introduced by branch fix/da1018-router-locks:

1. GroupRouter.health — the map lives on a process-wide singleton
   (ChatViewModel companion holds the only instance; every session, every
   sub-agent dispatch shares it). Pre-fix it was a bare LinkedHashMap touched
   from Main (clearHealth / isUsable / select) AND IO coroutines
   (AgentLoopEngine.recordResult on stream failure) with zero locking —
   concurrent structural modification loses updates or throws CME, and the
   lost/stale health state then drives cooldown/circuit-break decisions.
   Contract: EVERY `health` reference (reads, writes, iteration) must sit
   inside a `synchronized(healthLock)` critical section. FIFO eviction of the
   LinkedHashMap is why the fix is a lock rather than a ConcurrentHashMap.

2. SessionConcurrencyManager — "resume outside the lock" must be real.
   Pre-fix, promote() was called from @Synchronized releaseSlot() /
   releasePromotedAfterCancel(): promote's own inner block exited, but the
   outer monitor was still held (re-entrant), so the `toResume?.resume(Unit)`
   ran on a lock-holding thread — with a Dispatchers.Unconfined waiter the
   whole continuation body ran before the releaser could let go.
   Contract: zero `@Synchronized` methods in this file (all critical sections
   are explicit `synchronized(this)` spans), and every `.resume(` call sits
   OUTSIDE every such span.

Deliberate strictness: `@Synchronized` anywhere in the file is a violation —
the file is the concurrency core; keeping every critical section a visible
`synchronized(this)` span is part of the audited design, not an accident.

Usage: python3 router_lock_check.py [ROOT]   (ROOT = repo root, default .)
       python3 router_lock_check.py --selftest
Exit:  0 = gate green, 1 = violations found (or selftest failures).
"""
import os
import re
import sys

# [B7-t13] 全部结构分析改走共享原语（自写的 code_only / 括号扫描已删除）。
# 词法/语法/值流只有一份实现，见 scripts/scan/_kotlin_{lexer,ast,valueflow}.py。
from _kotlin_lexer import KotlinLexError, mask_code
from _kotlin_ast import match_bracket, strip_parens
from _kotlin_valueflow import code_view, expr_end, value_sources

ROUTER = "src/android/app/src/main/java/com/rikkaminis/app/data/routing/GroupRouter.kt"
SCM = "src/android/app/src/main/java/com/rikkaminis/app/service/SessionConcurrencyManager.kt"

HEALTH_REF = re.compile(r"\bhealth\b")
RESUME_CALL = re.compile(r"\.resume\(")
MIN_HEALTH_REFS = 6
MIN_SCM_SPANS = 2
MIN_RESUME_SITES = 2


def code_only(src):
    """结构视图 —— 共享词法器（注释 + 字符串/字符字面量内部抹成空格，长度与
    原文严格相等，位置可直接回投）。[B7-t13] 这里原先是一份自写实现：字符串
    里的 `}` / `{` 会让所有 span 与括号算术失真，正是 parser-death 轴的入口。
    `KotlinLexError` 由调用方按 fail-closed 报红（不许回退原文扫描）。
    """
    return code_view(src)


def blank_health_decl(code):
    """Blank the `private val health = mutableMapOf<…>()` field declaration —
    a declaration is not an access; everything else must live in a span."""
    m = re.search(r"\bprivate\s+val\s+health\s*=[^\n]*", code)
    if not m:
        return code
    e = code.find("\n", m.end())
    e = len(code) if e < 0 else e
    return code[: m.start()] + " " * (e - m.start()) + code[e:]


def synchronized_spans(code):
    """[(lock_expr, open_off, close_off)] for every `synchronized (…) { … }`.

    [B7-t13] 括号配平改用共享 `match_bracket`（自写深度计数遇到字符串里的
    括号就错位；code 视图已抹平字面量，配平失败的一律丢弃 = 该 span 不存在，
    依赖它的判据自然 fail-closed）。
    """
    spans = []
    for m in re.finditer(r"\bsynchronized\s*\(", code):
        p_close = match_bracket(code, m.end() - 1)
        if p_close < 0:
            continue
        lock_expr = code[m.end(): p_close].strip()
        j = p_close + 1
        while j < len(code) and code[j] in " \t\r\n":
            j += 1
        if j >= len(code) or code[j] != "{":
            continue
        b_close = match_bracket(code, j)
        if b_close < 0:
            continue
        spans.append((lock_expr, j, b_close))
    return spans


def _line(code, off):
    return code.count("\n", 0, off) + 1


# [meta-audit-1024 / M5] SPELLING axis: WHERE the monitor object comes from.
# `\bval\s+healthLock\b` only proves the NAME exists. A custom accessor —
# `private val healthLock get() = Any()` — satisfies it while handing every
# `synchronized(healthLock)` a BRAND-NEW monitor: two callers lock two
# different objects, nothing is serialized, and every access is still "inside
# a synchronized(healthLock) span" because the textual span check cannot see
# the identity. That is the ISSUE-10/1018 defect restored with the gate green.
# The criterion therefore pins the property SHAPE: a backing-field initializer,
# evaluated once, one instance locked thereafter. A `by` delegate is rejected
# too — fail-closed, identity is not statically confirmable.
HEALTHLOCK_IDENTITY_CRIT = (
    "healthLock is a stable monitor (backing-field initializer, not a per-access getter)"
)


ACCESSOR_HEAD_RE = re.compile(r"^(?:(?:private|internal|protected|public|final|inline|override)\s+)*(?:get|set)\b")
ANN_LINE_RE = re.compile(r"^@")


def healthlock_identity(code):
    """(ok, detail, offset) — is `healthLock` one stable object?

    [B7-t13] SPELLING → 结构：判据原本只看声明行 + 「紧邻的下一非空行」，
    于是
        private val healthLock = Any()
            @JvmName("getHealthLockMonitor")
            get() = Any()
    把访问器推到第 3 行就整段躲出视野（注解行占住了第 2 行，而它不匹配
    `(get|set|by)`，窗口当场关闭）。现在按**属性的完整声明跨度**取：声明行
    之后连续吞掉注解行与访问器行，直到出现真正的下一项声明为止 —— 窗口由
    结构决定，不由行数决定。没有初始化器、`by` 委托、任何自定义访问器一律
    红（fail-closed：monitor 身份静态不可确认）。
    """
    m = re.search(r"\bval\s+healthLock\b", code)
    if not m:
        return (False, "no `val healthLock` declaration", 0)
    lines = code[m.end():].split("\n")
    first = lines[0]
    consumed = [first]
    for line in lines[1:6]:
        stripped = line.strip()
        if stripped and (ANN_LINE_RE.match(stripped) or ACCESSOR_HEAD_RE.match(stripped)):
            consumed.append(line)
            continue
        break
    joined = "\n".join(consumed)
    if re.search(r"\bget\s*\(", joined):
        return (False,
                "custom getter (`get() = …`, %d declaration line(s) inspected) — every read "
                "builds a NEW monitor, so `synchronized(healthLock)` serializes nothing"
                % len(consumed), m.start())
    if re.search(r"\bset\s*\(", joined):
        return (False,
                "custom setter on the monitor property — identity is not statically "
                "confirmable (fail-closed)", m.start())
    if re.search(r"\bby\b", joined):
        return (False,
                "delegated property (`by …`) — monitor identity is not statically "
                "confirmable (fail-closed)", m.start())
    if "=" not in joined:
        return (False,
                "declaration carries no initializer — cannot confirm a stable "
                "backing field (fail-closed)", m.start())
    return (True, "backing field, one instance for the object's lifetime", m.start())


HEALTH_MONITOR_CRIT = (
    "healthLock is the monitor BY VALUE (no per-call rewrite in the lock expression)"
)


def _is_health_monitor(expr):
    """锁表达式**真正求值**的是不是 healthLock 本身。

    `synchronized(healthLock)` → ['healthLock'] → 是。
    `synchronized(healthLock.let { Any() })` → 共享 value_sources 取 let 的
    lambda 尾表达式 → ['Any()'] → 不是（每次加锁都在一把新锁上）。
    `synchronized(otherLock)` → 不是（但不属于本判据，由 span 归属判据处理）。
    """
    for src in value_sources(expr):
        s = strip_parens(src.strip())
        s = re.sub(r"^(?:this(?:@[A-Za-z_]\w*)?)\s*\.\s*", "", s)
        s = re.sub(r"^@[A-Za-z_]\w*\s*\.\s*", "", s)
        if s.strip() != "healthLock":
            return False
    return True


def analyse_router(code):
    res = []
    declared = re.search(r"\bval\s+healthLock\b", code)
    res.append((
        "healthLock declared",
        declared is not None,
        "private val healthLock = Any()" if declared else "no `val healthLock` in code view",
        _line(code, declared.start()) if declared else 0,
    ))
    ok_id, detail_id, off_id = healthlock_identity(code)
    res.append((
        HEALTHLOCK_IDENTITY_CRIT,
        ok_id,
        detail_id,
        _line(code, off_id) if off_id else 0,
    ))
    spans = synchronized_spans(code)
    # [B7-t13] DATAFLOW: the span only counts as a health critical section if its
    # monitor expression EVALUATES to healthLock. `"healthLock" in expr` accepted
    # `synchronized(healthLock.let { Any() })` — the token is there, the lock is
    # a brand-new object, so the section protects nothing.
    health_spans = [(a, b) for (expr, a, b) in spans
                    if "healthLock" in expr and _is_health_monitor(expr)]
    rewritten = [(_line(code, a), expr) for (expr, a, b) in spans
                 if "healthLock" in expr and not _is_health_monitor(expr)]
    res.append((
        HEALTH_MONITOR_CRIT,
        not rewritten,
        "every synthesized lock expression evaluates to healthLock" if not rewritten
        else "%d lock expression(s) rewrite healthLock away at line(s) %s (e.g. %r)"
             % (len(rewritten), ", ".join(str(l) for l, _ in rewritten[:4]), rewritten[0][1]),
        rewritten[0][0] if rewritten else 0,
    ))
    refs = list(HEALTH_REF.finditer(blank_health_decl(code)))
    res.append((
        "health access surface present (>= %d points)" % MIN_HEALTH_REFS,
        len(refs) >= MIN_HEALTH_REFS,
        "%d health reference(s) in code view" % len(refs),
        0,
    ))
    outside = [m.start() for m in refs if not any(a <= m.start() <= b for (a, b) in health_spans)]
    res.append((
        "every health reference inside a synchronized(healthLock) span",
        not outside,
        ("all %d reference(s) protected" % len(refs)) if not outside
        else "%d unprotected at line(s) %s" % (len(outside), ", ".join(str(_line(code, o)) for o in outside[:8])),
        _line(code, outside[0]) if outside else 0,
    ))
    res.append((
        "synchronized(healthLock) spans exist (>= 1)",
        len(health_spans) >= 1,
        "%d span(s)" % len(health_spans),
        0,
    ))
    return res


SYNCH_ANNOTATION_RE = (
    # [B7-t13] SPELLING: `@kotlin.jvm.Synchronized` is the same annotation by
    # bytecode, but `@Synchronized\b` never matched it — the qualified form split
    # `@` and the name apart. Accept any qualification chain between them.
    re.compile(r"@\s*(?:[A-Za-z_]\w*\s*\.\s*)*Synchronized\b")
)

FUN_DECL_RE = re.compile(r"\bfun\s+([A-Za-z_]\w*)\s*\(")


def fun_regions(code):
    """[{name,start,body_start,end}] for every `fun` (block and expression body).

    Uses the shared bracket matcher / expression boundary; the previous
    `fun_span` took the FIRST `{` after the signature, which for an expression
    body (`= synchronized(lock) { … }`) is the lock block itself — anything
    judged on "the body" then silently judged on the critical section.
    """
    out = []
    for m in FUN_DECL_RE.finditer(code):
        p_close = match_bracket(code, m.end() - 1)
        if p_close < 0:
            continue
        i = p_close + 1
        while i < len(code) and code[i] not in "={":
            if code[i] == "\n":
                i = -1
                break
            i += 1
        if i < 0 or i >= len(code):
            continue
        end = match_bracket(code, i) if code[i] == "{" else expr_end(code, i + 1)
        if end < 0:
            continue
        out.append({"name": m.group(1), "start": m.start(),
                    "body_start": m.start(), "end": end,
                    "expr_body": code[i] == "="})
    return out


def _call_sites(code, name):
    """调用点（排除 `fun name(` 的声明本身）：[(pos)]。"""
    out = []
    for m in re.finditer(r"(?<![\w.`])" + re.escape(name) + r"\s*\(", code):
        before = code[max(0, m.start() - 12):m.start()]
        if re.search(r"\bfun\s+$", before):
            continue
        out.append(m.start())
    return out


def resumer_names(code, hops=2):
    """函数名集合：函数体里出现 `.resume(`（或经 `hops` 跳调用到这种函数）。

    [B7-t13] SCOPE 轴：判据原本只做**词法位置**包含 —— `.resume(` 的偏移量
    在不在某个 `synchronized` 文本区间里。`synchronized(this) { finishPromotion(p) }`
    里 `.resume(` 在辅助函数体内，词法上永远不在块内，于是"锁外 resume"这条
    契约被一个字都不用改地绕开（这正是 ISSUE-10 原始 bug 的同构重现）。现在
    判的是**调用关系**：持锁调用一个（传递地）会 resume 的函数 = 违规。
    """
    regions = fun_regions(code)
    names = set()
    for r in regions:
        if RESUME_CALL.search(code[r["body_start"]:r["end"]]):
            names.add(r["name"])
    for _ in range(hops):
        for r in regions:
            body = code[r["body_start"]:r["end"]]
            if any(_call_sites(body, n) for n in names):
                names.add(r["name"])
    return names


def analyse_scm(code):
    res = []
    synch = [m.start() for m in SYNCH_ANNOTATION_RE.finditer(code)]
    res.append((
        "no @Synchronized methods in SessionConcurrencyManager",
        not synch,
        "0 occurrences" if not synch
        else "%d at line(s) %s" % (len(synch), ", ".join(str(_line(code, o)) for o in synch)),
        _line(code, synch[0]) if synch else 0,
    ))
    spans = synchronized_spans(code)
    res.append((
        "explicit critical sections exist (>= %d spans)" % MIN_SCM_SPANS,
        len(spans) >= MIN_SCM_SPANS,
        "%d span(s)" % len(spans),
        0,
    ))
    resumes = list(RESUME_CALL.finditer(code))
    res.append((
        "waiter wake-up sites exist (>= %d)" % MIN_RESUME_SITES,
        len(resumes) >= MIN_RESUME_SITES,
        "%d resume site(s)" % len(resumes),
        0,
    ))
    inside = [m.start() for m in resumes if any(a <= m.start() <= b for (_, a, b) in spans)]
    res.append((
        "resume happens outside every critical section",
        not inside,
        ("all %d resume site(s) outside the lock" % len(resumes)) if not inside
        else "%d inside a span at line(s) %s" % (len(inside), ", ".join(str(_line(code, o)) for o in inside)),
        _line(code, inside[0]) if inside else 0,
    ))
    # [B7-t13] SCOPE 轴：持锁调用"会 resume 的辅助函数"同样是持锁 resume。
    annotated = []
    for r in fun_regions(code):
        head = code[max(0, r["start"] - 160):r["start"]]
        if SYNCH_ANNOTATION_RE.search(head):
            annotated.append((r["body_start"], r["end"]))
    resumers = resumer_names(code)
    locked_calls = []
    for name in sorted(resumers):
        for pos in _call_sites(code, name):
            if any(a <= pos <= b for (_expr, a, b) in spans):
                locked_calls.append((name, pos, "inside a synchronized span"))
            elif any(s <= pos <= e for (s, e) in annotated):
                locked_calls.append((name, pos, "inside an @Synchronized method"))
    res.append((
        "no lock-holding call reaches a resume site (helper indirection included)",
        not locked_calls,
        ("%d resumer(s) (%s), none called under a lock"
         % (len(resumers), ", ".join(sorted(resumers)) or "-")) if not locked_calls
        else "%d lock-holding call(s): %s" % (
            len(locked_calls),
            "; ".join("%s() at line %d %s" % (n, _line(code, p), why)
                      for n, p, why in locked_calls[:4])),
        _line(code, locked_calls[0][1]) if locked_calls else 0,
    ))
    return res


STORE = "src/android/app/src/main/java/com/rikkaminis/app/offload/ScheduledNotificationStore.kt"
STORE_LOCK = re.compile(r"\bval\s+lock\b\s*=\s*Any\(\)")
STORE_GUARD = re.compile(r"\bsynchronized\s*\(\s*lock\s*\)")


def block_span(code, open_off):
    """(open, close) of the brace block opened at `open_off` — shared matcher."""
    close = match_bracket(code, open_off)
    return None if close < 0 else (open_off, close)


def companion_span(code):
    m = re.search(r"\bcompanion\s+object\b[^{]*\{", code)
    if not m:
        return None
    return block_span(code, m.end() - 1)


def fun_span(code, name):
    """(start, end) of `fun NAME(…)` including its body (block or `= …` body).

    [B7-t13] 原实现取签名后**第一个** `{`：对表达式体 `fun get() = synchronized(lock) { … }`
    那个 `{` 就是临界区本身，函数体被截成锁块，任何"体内是否有锁"的判据
    都只能看到锁、看不到锁外。现在用共享 fun_regions（表达式体按表达式
    边界收尾）。
    """
    for r in fun_regions(code):
        if r["name"] == name:
            return (r["start"], r["end"])
    return None


LOOP_RE = re.compile(r"\b(?:while|for)\s*\(")


def _is_lock_monitor(expr):
    """锁表达式求值结果是 `lock` 本身吗（`lock.let { Any() }` 不是）。"""
    for src in value_sources(expr):
        if strip_parens(src.strip()) != "lock":
            return False
    return True


def _loop_bodies(text):
    """函数体里每个 while/for 的循环块文本（找不到块体返回单语句区间）。"""
    out = []
    for m in LOOP_RE.finditer(text):
        close = match_bracket(text, m.end() - 1)
        if close < 0:
            continue
        j = close + 1
        while j < len(text) and text[j] in " \t\r\n":
            j += 1
        if j < len(text) and text[j] == "{":
            end = match_bracket(text, j)
            if end > 0:
                out.append(text[j + 1:end])
                continue
        out.append(text[j:text.find("\n", j) if text.find("\n", j) > 0 else len(text)])
    return out


def analyse_store(code):
    """deep-audit-1028 ISSUE-01 — the scheduled-notification store keeps ONE
    process-wide monitor and holds it on the read path too.

    `lock` was an instance field and `get`/`loadAll` read outside any monitor:
    two live instances (NotificationOffloadHandler + ScheduledNotificationReceiver)
    each held their own lock, so a reader could observe the pre-`putString`
    string and a removed (already fired) alarm id came back on the next write.
    Contract: `lock` is declared inside the companion object, and both read
    entry points hold `synchronized(lock)` in their own body.

    [dv15-A3] Same-id ledger semantics: add() is an idempotent upsert (a
    re-schedule must replace its previous row — AlarmManager FLAG_UPDATE_CURRENT
    already replaced the alarm) and remove() clears EVERY row with the id (the
    receiver's fire path and cancel --id both want the whole id gone; a
    first-match `return` leaves the duplicates behind as ghost rows). Both
    entry points must route through the single helper removeAllById — bound to
    the decision, not the writing: removeIf/replaceAll count as equivalent
    inline forms, and the helper body must not return from inside its scan
    loop.
    """
    res = []
    comp = companion_span(code)
    res.append((
        "store: companion object block present",
        comp is not None,
        "companion object block found" if comp else "no `companion object { … }` block",
        _line(code, comp[0]) if comp else 0,
    ))
    lock = STORE_LOCK.search(code)
    inside = bool(comp and lock and comp[0] < lock.start() < comp[1])
    res.append((
        "store: `lock` lives in the companion object (process-wide monitor)",
        bool(lock) and inside,
        ("`val lock = Any()` at line %d, inside the companion object" % _line(code, lock.start())) if inside
        else ("no `val lock = Any()` declaration" if not lock
              else "`val lock = Any()` at line %d is OUTSIDE the companion object"
                   % _line(code, lock.start())),
        _line(code, lock.start()) if lock else 0,
    ))
    for fname in ("get", "loadAll"):
        sp = fun_span(code, fname)
        body = code[sp[0]:sp[1]] if sp else ""
        # [B7-t13] ORDER 轴：判据原本只问"函数体里出现过 synchronized(lock)"，
        # 空块 `synchronized(lock) { Unit }` 也满足 —— 把受保护的读取搬到锁外，
        # 门依然全绿。现在两件事都要有：①体内存在**非空**的 `synchronized(lock)`
        # 块；②体内每一处状态读取（`prefs.` / `loadAll(` 调用）都落在这样的块里。
        real_spans = []
        for expr, a, b in synchronized_spans(body):
            if not _is_lock_monitor(expr):
                continue
            if strip_parens(body[a + 1:b].strip()) in ("", "Unit"):
                continue  # 空块 / 名义锁块：保护不到任何东西
            real_spans.append((a, b))
        loose_reads = []
        for m in re.finditer(r"prefs\s*\.|(?<![\w.])loadAll\s*\(", body):
            if re.search(r"\bfun\s+$", body[max(0, m.start() - 12):m.start()]):
                continue  # `fun loadAll(` 的声明本身不是读取
            if not any(a <= m.start() <= b for (a, b) in real_spans):
                loose_reads.append(m.start())
        guarded = bool(real_spans) and not loose_reads
        if guarded:
            detail = "the read path runs inside a non-empty `synchronized(lock)` block (%d span(s))" % len(real_spans)
        elif not sp:
            detail = "`fun %s(` not found (anchor drift)" % fname
        elif not synchronized_spans(body):
            detail = "body has no `synchronized(lock)`"
        elif not real_spans:
            detail = ("`synchronized(lock)` is only a nominal/empty block (`{ Unit }`) — "
                      "the protected read sits outside it")
        else:
            detail = ("%d read(s) sit OUTSIDE the lock block at body offset(s) %s — an empty/"
                      "nominal lock block does not protect them"
                      % (len(loose_reads), ", ".join(str(o) for o in loose_reads[:4])))
        res.append((
            "store: %s() body holds synchronized(lock)" % fname,
            guarded,
            detail,
            _line(code, sp[0]) if sp else 0,
        ))

    # [dv15-A3] Same-id ledger semantics (see the docstring above). Anchored on
    # the add()/remove() declaration bodies and the single helper definition.
    idem_accept = re.compile(r"\b(removeAllById|removeIf|replaceAll)\s*\(")
    add_sp = fun_span(code, "add")
    rem_sp = fun_span(code, "remove")
    help_sp = fun_span(code, "removeAllById")
    add_ok = bool(add_sp) and bool(idem_accept.search(code[add_sp[0]:add_sp[1]]))
    rem_ok = bool(rem_sp) and bool(idem_accept.search(code[rem_sp[0]:rem_sp[1]]))
    help_body = code[help_sp[0]:help_sp[1]] if help_sp else ""
    n_returns = len(re.findall(r"\breturn\b", help_body))
    # [B7-t13] FAMILY-HOLE：判据原本只数 `return` 的个数（"体内恰好一个 return"
    # = 扫完整个列表才返回）。把 `return true` 换成循环里的 `break`，关键字计数
    # 一个不变，函数却退化成 removeFirstMatchById —— 幽灵行回来了。现在直接禁
    # 循环体内的 `break`（提前退出扫描 = 没清干净）。
    loop_breaks = [seg for seg in _loop_bodies(help_body) if re.search(r"\bbreak\b", seg)]
    help_ok = (bool(help_sp) and ".remove(" in help_body
               and n_returns == 1 and not loop_breaks)
    idem_ok = add_ok and rem_ok and help_ok
    if idem_ok:
        idem_detail = ("add()/remove() both route same-id handling through the "
                       "single helper; helper clears every match (single return)")
    elif not help_sp:
        idem_detail = "single-source helper removeAllById( not found (anchor drift)"
    else:
        missing = []
        if not add_sp:
            missing.append("fun add( not found")
        elif not add_ok:
            missing.append("add() does not route same-id dedup through the single helper (bare put?)")
        if not rem_sp:
            missing.append("fun remove( not found")
        elif not rem_ok:
            missing.append("remove() does not route same-id cleanup through the single helper (first-match-only?)")
        if help_sp and (n_returns != 1 or ".remove(" not in help_body):
            missing.append("removeAllById body must clear EVERY match (no early return inside the scan loop)")
        if help_sp and loop_breaks:
            missing.append("removeAllById breaks out of its scan loop — the remaining same-id rows stay behind "
                           "(a `break` is a first-match exit that keeps the `return` count at one)")
        idem_detail = "; ".join(missing)
    res.append((
        "store: same-id ledger semantics (add replaces, remove clears all, one helper)",
        idem_ok,
        idem_detail,
        _line(code, help_sp[0]) if help_sp else (_line(code, add_sp[0]) if add_sp else 0),
    ))
    return res


COMPLIANT_ROUTER = '''package com.rikkaminis.app.data.routing

class CompliantRouter {
    private val healthLock = Any()
    private val health = mutableMapOf<String, Int>()

    fun record(entryId: String, failed: Boolean) {
        synchronized(healthLock) {
            val current = health[entryId]
            health[entryId] = (current ?: 0) + 1
            if (health.size > 3) {
                val eldest = health.keys.firstOrNull() ?: return
                health.remove(eldest)
            }
        }
    }

    fun clearHealth() = synchronized(healthLock) { health.clear() }
    fun isUsable(entryId: String): Boolean = synchronized(healthLock) { health[entryId] != null }
    fun healthOf(entryId: String): Int? = synchronized(healthLock) { health[entryId] }
}
'''

VACUOUS_ROUTER = '''package com.rikkaminis.app.data.routing

class VacuousRouter {
    private val healthLock = Any()
}
'''

ROUTER_BREAKERS = {
    1: (
        [("fun isUsable(entryId: String): Boolean = synchronized(healthLock) { health[entryId] != null }",
          "fun peek(entryId: String): Int? = health[entryId]\n    fun isUsable(entryId: String): Boolean = synchronized(healthLock) { health[entryId] != null }")],
        "every health reference inside a synchronized(healthLock) span",
    ),
    2: (
        [("fun clearHealth() = synchronized(healthLock) { health.clear() }",
          "fun clearHealth() = synchronized(otherLock) { health.clear() }")],
        "every health reference inside a synchronized(healthLock) span",
    ),
    3: (
        [("synchronized(healthLock) {\n            val current = health[entryId]\n            health[entryId] = (current ?: 0) + 1\n            if (health.size > 3) {\n                val eldest = health.keys.firstOrNull() ?: return\n                health.remove(eldest)\n            }\n        }",
          "{\n            val current = health[entryId]\n            health[entryId] = (current ?: 0) + 1\n            if (health.size > 3) {\n                val eldest = health.keys.firstOrNull() ?: return\n                health.remove(eldest)\n            }\n        }")],
        "every health reference inside a synchronized(healthLock) span",
    ),
    4: (
        [("fun clearHealth() = synchronized(healthLock) { health.clear() }",
          "fun clearHealth() = synchronized(healthLock) { health.clear() }\n    fun snapshot(): Map<String, Int> = health")],
        "every health reference inside a synchronized(healthLock) span",
    ),
    # [meta-audit-1024 / M5] the name survives, the identity does not: every
    # read mints a fresh monitor, so all four call sites lock different objects.
    5: (
        [("private val healthLock = Any()", "private val healthLock get() = Any()")],
        HEALTHLOCK_IDENTITY_CRIT,
    ),
    # [B7-t13] 03-1: the accessor hides behind an annotation line — the window
    # that only looked at "declaration line + next non-empty line" was shut by
    # the annotation, so `get() = Any()` on the third line was never read.
    7: (
        [("private val healthLock = Any()",
          "private val healthLock = Any()\n        @JvmName(\"getHealthLockMonitor\")\n        get() = Any()")],
        HEALTHLOCK_IDENTITY_CRIT,
    ),
    # [B7-t13] 03-2: the token is in the expression, the monitor is a new object.
    8: (
        [("fun clearHealth() = synchronized(healthLock) { health.clear() }",
          "fun clearHealth() = synchronized(healthLock.let { Any() }) { health.clear() }")],
        HEALTH_MONITOR_CRIT,
    ),
    # delegated property: identity is not statically confirmable (fail-closed)
    6: (
        [("private val healthLock = Any()", "private val healthLock by lazy { Any() }")],
        HEALTHLOCK_IDENTITY_CRIT,
    ),
}

COMPLIANT_SCM = '''package com.rikkaminis.app.service

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation

class CompliantManager {
    private class Promotion(val happened: Boolean, val toResume: CancellableContinuation<Unit>?)

    fun releaseSlot(runId: String) {
        val promotion = synchronized(this) { promoteLocked(runId) }
        finishPromotion(promotion)
    }

    fun releasePromotedAfterCancel(runId: String) {
        val promotion = synchronized(this) { promoteLocked(runId) }
        finishPromotion(promotion)
    }

    private fun promoteLocked(runId: String): Promotion = Promotion(true, null)

    private fun finishPromotion(promotion: Promotion) {
        promotion.toResume?.resume(Unit)
    }

    suspend fun acquireSlot(runId: String) {
        val resumeNow = synchronized(this) { null as CancellableContinuation<Unit>? }
        resumeNow?.resume(Unit)
    }
}
'''

SCM_BREAKERS = {
    1: (
        [("fun releaseSlot(runId: String) {\n        val promotion = synchronized(this) { promoteLocked(runId) }\n        finishPromotion(promotion)\n    }",
          "fun releaseSlot(runId: String) {\n        synchronized(this) {\n            val promotion = promoteLocked(runId)\n            promotion.toResume?.resume(Unit)\n        }\n    }")],
        "resume happens outside every critical section",
    ),
    2: (
        [("fun releaseSlot(runId: String) {", "@Synchronized\n    fun releaseSlot(runId: String) {")],
        "no @Synchronized methods in SessionConcurrencyManager",
    ),
    3: (
        [("promotion.toResume?.resume(Unit)", "promotion.toResume"),
         ("resumeNow?.resume(Unit)", "checkNotNull(resumeNow)")],
        "waiter wake-up sites exist (>= 2)",
    ),
    # [B7-t13] 03-3: the resume stays inside a helper, but the helper is CALLED
    # under the lock — the lexical span check could not see it.
    5: (
        [("fun releaseSlot(runId: String) {\n        val promotion = synchronized(this) { promoteLocked(runId) }\n        finishPromotion(promotion)\n    }",
          "fun releaseSlot(runId: String) {\n        val promotion = synchronized(this) { promoteLocked(runId) }\n        synchronized(this) { finishPromotion(promotion) }\n    }")],
        "no lock-holding call reaches a resume site (helper indirection included)",
    ),
    # [B7-t13] 03-4: the fully qualified annotation is the same annotation.
    6: (
        [("fun releaseSlot(runId: String) {", "@kotlin.jvm.Synchronized\n    fun releaseSlot(runId: String) {")],
        "no @Synchronized methods in SessionConcurrencyManager",
    ),
    4: (
        [("val promotion = synchronized(this) { promoteLocked(runId) }", "val promotion = promoteLocked(runId)"),
         ("val promotion = synchronized(this) { promoteLocked(runId) }", "val promotion = promoteLocked(runId)"),
         ("val resumeNow = synchronized(this) { null as CancellableContinuation<Unit>? }", "val resumeNow = null as CancellableContinuation<Unit>?")],
        "explicit critical sections exist (>= 2 spans)",
    ),
}


COMPLIANT_STORE = '''package com.rikkaminis.app.offload

class ScheduledNotificationStore {
    fun add(id: String) {
        synchronized(lock) {
            removeAllById(rawList, id)
            rawList.put(id)
        }
    }

    fun remove(id: String): Boolean {
        synchronized(lock) {
            if (removeAllById(rawList, id)) {
                return true
            }
            return false
        }
    }

    fun get(id: String): String? = synchronized(lock) {
        loadAll()
        if (raw == id) id else null
    }

    fun loadAll(): String = synchronized(lock) {
        raw ?: ""
    }

    companion object {
        private val lock = Any()
        private var raw: String? = null
        private var rawList: org.json.JSONArray = org.json.JSONArray()
    }
}

internal fun removeAllById(list: org.json.JSONArray, id: String): Boolean {
    var removed = false
    var i = list.length() - 1
    while (i >= 0) {
        if (list.getJSONObject(i).optString("id") == id) {
            list.remove(i)
            removed = true
        }
        i--
    }
    return removed
}
'''

STORE_BREAKERS = {
    "instance-lock": (
        [(("    companion object {\n        private val lock = Any()\n"),
          ("    private val lock = Any()\n\n    companion object {\n"))],
        "store: `lock` lives in the companion object (process-wide monitor)",
    ),
    "get-unguarded": (
        [("    fun get(id: String): String? = synchronized(lock) {\n        loadAll()\n        if (raw == id) id else null\n    }",
          "    fun get(id: String): String? {\n        loadAll()\n        return if (raw == id) id else null\n    }")],
        "store: get() body holds synchronized(lock)",
    ),
    "load-all-unguarded": (
        [("    fun loadAll(): String = synchronized(lock) {\n        raw ?: \"\"\n    }",
          "    fun loadAll(): String = raw ?: \"\"")],
        "store: loadAll() body holds synchronized(lock)",
    ),
    "load-all-renamed": (
        [("    fun loadAll(): String = synchronized(lock) {", "    fun readAll(): String = synchronized(lock) {")],
        "store: loadAll() body holds synchronized(lock)",
    ),
    "add-no-dedupe": (
        [("            removeAllById(rawList, id)\n            rawList.put(id)",
          "            rawList.put(id)")],
        "store: same-id ledger semantics (add replaces, remove clears all, one helper)",
    ),
    "remove-first-only": (
        [("            if (removeAllById(rawList, id)) {\n                return true\n            }\n            return false",
          "            for (i in 0 until rawList.length()) {\n                if (rawList.getJSONObject(i).optString(\"id\") == id) {\n                    rawList.remove(i)\n                    return true\n                }\n            }\n            return false")],
        "store: same-id ledger semantics (add replaces, remove clears all, one helper)",
    ),
    # [B7-t13] 03-5: the lock block survives, the protected read leaves it.
    "get-nominal-lock": (
        [("    fun get(id: String): String? = synchronized(lock) {\n        loadAll()\n        if (raw == id) id else null\n    }",
          "    fun get(id: String): String? {\n        synchronized(lock) { Unit }\n        loadAll()\n        return if (raw == id) id else null\n    }")],
        "store: get() body holds synchronized(lock)",
    ),
    # [B7-t13] 03-6: `break` is a first-match exit that keeps the return count.
    "helper-loop-break": (
        [("            list.remove(i)\n            removed = true\n        }",
          "            list.remove(i)\n            removed = true\n            break\n        }")],
        "store: same-id ledger semantics (add replaces, remove clears all, one helper)",
    ),
    "helper-first-match-return": (
        [("        if (list.getJSONObject(i).optString(\"id\") == id) {\n            list.remove(i)\n            removed = true\n        }",
          "        if (list.getJSONObject(i).optString(\"id\") == id) {\n            list.remove(i)\n            return true\n        }")],
        "store: same-id ledger semantics (add replaces, remove clears all, one helper)",
    ),
}


def selftest():
    print("-- selftest: compliant fixtures green; every breaker red on its target --")
    failures = []
    fixtures = 0

    base_r = analyse_router(code_only(COMPLIANT_ROUTER))
    reds = [c for c, ok, _, _ in base_r if not ok]
    fixtures += 1
    if reds:
        failures.append("compliant router fixture red on: %s" % reds)
        print("  [FAIL] compliant router fixture red on: %s" % reds)
    else:
        print("  [OK] compliant router fixture green on all %d criteria" % len(base_r))

    base_s = analyse_scm(code_only(COMPLIANT_SCM))
    reds = [c for c, ok, _, _ in base_s if not ok]
    fixtures += 1
    if reds:
        failures.append("compliant scm fixture red on: %s" % reds)
        print("  [FAIL] compliant scm fixture red on: %s" % reds)
    else:
        print("  [OK] compliant scm fixture green on all %d criteria" % len(base_s))

    vac = analyse_router(code_only(VACUOUS_ROUTER))
    reds = [c for c, ok, _, _ in vac if not ok]
    fixtures += 1
    if "health access surface present (>= %d points)" % MIN_HEALTH_REFS not in reds:
        failures.append("vacuous router fixture did not trip the anti-vacuous sanity")
        print("  [FAIL] vacuous router fixture stayed green (red=%s)" % reds)
    else:
        print("  [OK] vacuous router fixture -> red on %r (+%d side)" % (
            "health access surface present (>= %d points)" % MIN_HEALTH_REFS, len(reds) - 1))

    for idx, (pairs, target) in ROUTER_BREAKERS.items():
        mutated = COMPLIANT_ROUTER
        missing = [old for old, _ in pairs if old not in mutated]
        fixtures += 1
        if missing:
            failures.append("router breaker %d cannot apply (fixture anchor moved)" % idx)
            print("  [FAIL] router breaker %d: anchor not found in fixture" % idx)
            continue
        for old, new in pairs:
            mutated = mutated.replace(old, new, 1)
        reds = [c for c, ok, _, _ in analyse_router(code_only(mutated)) if not ok]
        if target not in reds:
            failures.append("router breaker %d did not turn %r red (red=%s)" % (idx, target, reds))
            print("  [FAIL] router breaker %d -> %r stayed green (red=%s)" % (idx, target, reds))
        else:
            print("  [OK] router breaker %d -> red on %r (+%d side)" % (idx, target, len(reds) - 1))

    for idx, (pairs, target) in SCM_BREAKERS.items():
        mutated = COMPLIANT_SCM
        missing = [old for old, _ in pairs if old not in mutated]
        fixtures += 1
        if missing:
            failures.append("scm breaker %d cannot apply (fixture anchor moved)" % idx)
            print("  [FAIL] scm breaker %d: anchor not found in fixture" % idx)
            continue
        for old, new in pairs:
            mutated = mutated.replace(old, new, 1)
        reds = [c for c, ok, _, _ in analyse_scm(code_only(mutated)) if not ok]
        if target not in reds:
            failures.append("scm breaker %d did not turn %r red (red=%s)" % (idx, target, reds))
            print("  [FAIL] scm breaker %d -> %r stayed green (red=%s)" % (idx, target, reds))
        else:
            print("  [OK] scm breaker %d -> red on %r (+%d side)" % (idx, target, len(reds) - 1))

    base_st = analyse_store(code_only(COMPLIANT_STORE))
    reds = [c for c, ok, _, _ in base_st if not ok]
    fixtures += 1
    if reds:
        failures.append("compliant store fixture red on: %s" % reds)
        print("  [FAIL] compliant store fixture red on: %s" % reds)
    else:
        print("  [OK] compliant store fixture green on all %d criteria" % len(base_st))

    for name, (pairs, target) in STORE_BREAKERS.items():
        mutated = COMPLIANT_STORE
        missing = [old for old, _ in pairs if old not in mutated]
        fixtures += 1
        if missing:
            failures.append("store breaker %s cannot apply (fixture anchor moved)" % name)
            print("  [FAIL] store breaker %s: anchor not found in fixture" % name)
            continue
        for old, new in pairs:
            mutated = mutated.replace(old, new, 1)
        reds = [c for c, ok, _, _ in analyse_store(code_only(mutated)) if not ok]
        if target not in reds:
            failures.append("store breaker %s did not turn %r red (red=%s)" % (name, target, reds))
            print("  [FAIL] store breaker %s -> %r stayed green (red=%s)" % (name, target, reds))
        else:
            print("  [OK] store breaker %s -> red on %r (+%d side)" % (name, target, len(reds) - 1))

    return failures, fixtures


def main():
    if "--selftest" in sys.argv:
        fails, fixtures = selftest()
        if fails:
            print("SELFTEST FAIL (%d)" % len(fails))
            return 1
        print("STORE_LOCK_CRITERIA=PASS (%d store criteria, %d breakers)"
              % (len(analyse_store(code_only(COMPLIANT_STORE))), len(STORE_BREAKERS)))
        print("ROUTER_LOCK_SELFTEST=PASS (%d fixtures)" % fixtures)
        return 0

    root = "."
    args = sys.argv[1:]
    if "--root" in args:
        root = args[args.index("--root") + 1]
    elif args:
        root = args[0]

    results = []
    for rel, analyser in ((ROUTER, analyse_router), (SCM, analyse_scm), (STORE, analyse_store)):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            print("FAIL %s not found under root %s" % (rel, root))
            return 1
        try:
            code = code_only(open(path, encoding="utf-8").read())
        except KotlinLexError as exc:
            print("FAIL %s: kotlin lex error (%s) — fail-closed" % (rel, exc))
            return 1
        print("== enforced: %s" % rel)
        for crit, ok, detail, line in analyser(code):
            results.append((crit, ok, detail, line, rel))

    failed = [(c, d, l, r) for c, ok, d, l, r in results if not ok]
    for c, ok, d, l, r in results:
        if ok:
            print("  [OK] %s" % c)
        else:
            print("  [FAIL] %s:%s %s — %s" % (os.path.basename(r), l, c, d))

    print("Summary: %d passed, %d failed" % (len(results) - len(failed), len(failed)))
    if failed:
        print("router-lock gate: RED — the fix contract is violated")
        return 1
    print("GATE=GREEN router_lock")
    return 0


if __name__ == "__main__":
    sys.exit(main())
