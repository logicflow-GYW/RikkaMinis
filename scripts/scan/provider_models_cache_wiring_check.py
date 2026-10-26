#!/usr/bin/env python3
"""deep-audit-1020 ISSUE-01 (P1) — provider models-cache wiring gate.

Pins the contract fixed by branch fix/da1020-provider-models-cache: the sole
production call site `ProviderRepository.refreshModels` ->
`ModelListProviderRegistry.fetchModels(...)` must forward the application
`Context`, the Registry must keep the `context` parameter and forward it to
every fetcher, the *ModelsApi adapters must keep passing
`context = context`, and the disk-cache classes must keep their
load / save / invalidate surface (the three things that were 100% inert while
the context was dropped: 7-day disk cache read, 401/403 invalidate, save).

Every *ModelsApi short-circuits on `context != null`
(OpenAIModelsApi:95/166, AnthropicModelsApi:42/117/175, GeminiModelsApi:75/
113/154, OpenRouterModelsApi:39/58/73), so a dropped argument does not fail
anywhere — it silently disables the whole cache chain.

[batch7 / B7-t07] Six external witnesses walked through the previous revision
(5 blind, 1 caught). All four families below were "the token is still spelled,
the value is not the caller's":

  dataflow   a same-named LOCAL (`val context = null`) shadows the parameter
             or the constructor property at any hop of the 4-step chain
             (Repo -> Registry -> Adapter -> Cache). The previous revision only
             checked the Registry hop, so hoisting the shadow one hop up or
             down the chain was free.
  parser     the old `strip_comments()` was two non-string-aware regexes, so
             `val _c1 = "/*"` swallowed every line up to `"*/"` and hid the
             shadow entirely.
  family-hole a constant guard is not a `true`/`false` literal: `if (1 == 1)
             return null`, `if (0 == 1) runCatching { …delete() }`, `if (!false)
             return` are all the same knife. Reachability is now decided by
             constant folding + enclosing conditions, for every arm of the gate.

All structure work goes through the shared primitives (`_kotlin_lexer` /
`_kotlin_ast` / `_kotlin_valueflow`): no hand-rolled comment stripper, no bare
brace counting, no `true|false` literal whitelist.

Usage: python3 provider_models_cache_wiring_check.py [ROOT]   (ROOT = repo root)
       python3 provider_models_cache_wiring_check.py --root ROOT
       python3 provider_models_cache_wiring_check.py --selftest
Exit:  0 = gate green, 1 = violations found (or selftest failures).
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from _kotlin_lexer import KotlinLexError
from _kotlin_ast import (
    const_bool,
    enclosing_conditions,
    function_body,
    match_bracket,
    split_top,
    stmt_spans,
    strip_parens,
)
from _kotlin_valueflow import (
    brace_depth,
    code_view,
    decl_sites,
    path_feasible,
)

REPO_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt"
REGISTRY_REL = "src/android/app/src/main/java/com/rikkaminis/app/data/repository/ModelListProviderRegistry.kt"
ADAPTERS_REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/ModelListProviderAdapters.kt"
CACHE_REL = "src/android/app/src/main/java/com/rikkaminis/app/provider/ProviderModelsCache.kt"

REPO_ANCHOR = "ModelListProviderRegistry.fetchModels("
ADAPTER_ANCHOR_RE = re.compile(r"\b\w+ModelsApi\.fetchModels\s*\(")
CONTEXT_TOKEN = "context"

READ_CALLS = ("readText(", "readBytes(", "readString(", "decodeFromString", "inputStream(")
WRITE_CALLS = ("writeText(", "writeBytes(", "outputStream(", "renameTo(")
DELETE_CALLS = (".delete(",)

# ---------------------------------------------------------------------------
# Structure helpers (shared primitives only)

FUN_HEAD_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")


def view(src):
    """code_view, or None when the file cannot be lexed (caller fails closed)."""
    try:
        return code_view(src)
    except KotlinLexError:
        return None


def fun_spans(code):
    """[(name, body_start, body_end)] for every BLOCK-bodied `fun NAME(`.

    Expression bodies are skipped: none of the functions this gate judges are
    expression-bodied, and a span we cannot delimit must not become a span we
    guess at.
    """
    out = []
    for m in FUN_HEAD_RE.finditer(code):
        p_open = m.end() - 1
        p_close = match_bracket(code, p_open)
        if p_close < 0:
            continue
        i = p_close + 1
        while i < len(code) and code[i] not in "={":
            if code[i] == "\n":
                break
            i += 1
        if i >= len(code) or code[i] != "{":
            continue
        close = match_bracket(code, i)
        if close > 0:
            out.append((m.group(1), i + 1, close))
    return out


def enclosing_fun(code, pos):
    """Innermost block-bodied function containing `pos`, or None."""
    best = None
    for name, start, end in fun_spans(code):
        if start <= pos < end:
            if best is None or start >= best[1]:
                best = (name, start, end)
    return best


def call_arg_spans(code, anchor, start=0):
    """[(start, end)] of the FIRST `anchor` call's arguments, or ([], -1).

    `anchor` must end with `(`. Parenthesis-balanced via `match_bracket`; the
    argument split is `split_top`, so nested calls / lambdas stay intact.
    """
    i = code.find(anchor, start)
    if i < 0:
        return [], -1
    open_paren = i + len(anchor) - 1
    close = match_bracket(code, open_paren)
    if close < 0:
        return [], -1
    parts = split_top(code[open_paren + 1:close], (",",))
    base = open_paren + 1
    return [(base + a, base + b) for a, b in parts], open_paren


def named_args(code, spans):
    """{name: value_text} for the named arguments among `spans`."""
    out = {}
    for start, end in spans:
        arg = code[start:end].strip()
        m = re.match(r"^([A-Za-z_]\w*)\s*=(?!=)\s*(.*)$", arg, re.S)
        if m:
            out[m.group(1)] = m.group(2).strip()
    return out


def _norm(expr):
    return re.sub(r"\s+", "", strip_parens(expr))


def is_bare_context(expr):
    """The expression IS the `context` identifier (nothing chained onto it)."""
    return _norm(expr) in (CONTEXT_TOKEN, CONTEXT_TOKEN + "!!")


def local_shadow(code, pos, name=CONTEXT_TOKEN):
    """A same-named local declared in the enclosing function before `pos`.

    Returns the declaration position, or None. `decl_sites` distinguishes
    `val/var name =` from a plain assignment, so `context = other` (a mutation
    of the property, which is a different defect) is not reported here.
    """
    span = enclosing_fun(code, pos)
    if span is None:
        return None
    _name, body_start, body_end = span
    body = code[body_start:body_end]
    for decl_pos, kind in decl_sites(body, name):
        if kind in ("decl", "shadow") and body_start + decl_pos <= pos:
            return body_start + decl_pos
    return None


def guaranteed_exit_before(body, limit):
    """A `return`/`throw` in body[:limit] that ALWAYS runs.

    "Always" = every enclosing condition folds to a constant True (`if (1 == 1)
    return`, `if (!false) return`, `while (true)` is *not* handled on purpose:
    its body is not guaranteed). A feasible-but-not-constant condition
    (`if (!file.isFile) return null`) is an ordinary guard and stays legal.
    """
    spans = stmt_spans(body)
    for m in re.finditer(r"\b(?:return|throw)\b(@\s*[A-Za-z_]\w*)?", body[:limit]):
        if m.group(1):
            continue                      # `return@run` only leaves the lambda
        pos = m.start()
        if brace_depth(body, pos) != 0:
            continue                      # inside a nested block/lambda
        # The exit must END before the action: `return runCatching { …readText() }`
        # *contains* its action, it does not precede it.
        stmt_end = next((b for a, b in spans if a <= pos < b), None)
        if stmt_end is None or stmt_end > limit:
            continue
        conds = enclosing_conditions(body, pos)
        if all(const_bool(cond) is True for _kind, cond in conds):
            return True
    return False


def action_positions(body, patterns):
    """{(token, pos)} of matches, plus the reachable subset."""
    all_pos, live_pos = [], []
    for pat in patterns:
        for m in re.finditer(re.escape(pat), body):
            all_pos.append(m.start())
            if path_feasible(body, m.start()):
                live_pos.append(m.start())
    return all_pos, live_pos


def dead_body_reason(body, patterns, verb):
    """Why the body cannot perform its action, or None when it does.

    [batch7] The judgement moved off "the call token exists somewhere in the
    body" onto "the call actually runs": (a) at least one match must sit on a
    reachable path — a constant-false guard wrapping the call (`if (0 == 1)
    runCatching { …delete() }`) kills it; (b) no always-taken exit may sit in
    front of it (`if (1 == 1) return null` between `keyFile(` and the read).
    """
    if body is None:
        return "function body not found (fail-closed)"
    all_pos, live_pos = action_positions(body, patterns)
    if not all_pos:
        return "the body never %ss anything (`%s` absent)" % (verb, "` / `".join(patterns))
    if not live_pos:
        return ("the `%s` call only appears inside a branch that can never run "
                "(a constant-condition guard / dead else)" % patterns[0])
    first = min(live_pos)
    if guaranteed_exit_before(body, first):
        return ("a constant-condition guard makes the body dead before `%s` "
                "(`if (1 == 1) return` / `if (!false) return`)" % patterns[0])
    return None


def analyse(repo, registry, adapters, cache):
    """Returns list of (criterion, ok, detail, file, line). ok=True = held."""
    res = []

    # ── 1. the sole production call site must forward the Context ──
    clean = view(repo)
    if clean is None:
        res.append(("ProviderRepository has exactly one ModelListProviderRegistry.fetchModels call site",
                    False, "unparseable Kotlin (fail-closed)", REPO_REL, 0))
        return res
    count = clean.count(REPO_ANCHOR)
    res.append((
        "ProviderRepository has exactly one ModelListProviderRegistry.fetchModels call site",
        count == 1,
        "found %d" % count,
        REPO_REL,
        clean.count("\n", 0, clean.find(REPO_ANCHOR)) + 1 if count else 0,
    ))
    if count == 1:
        args, open_paren = call_arg_spans(clean, REPO_ANCHOR)
        line = clean.count("\n", 0, open_paren) + 1
        last = clean[args[-1][0]:args[-1][1]].strip() if args else None
        shadow = local_shadow(clean, open_paren)
        ok = bool(args) and is_bare_context(last) and shadow is None
        if not args:
            detail = "argument list unparseable"
        elif not is_bare_context(last):
            detail = "last arg `%s` is not the `context` property" % last
        elif shadow is not None:
            detail = ("a local `%s` declared at line %d shadows the Context the "
                      "call site must forward (the call forwards null, every "
                      "*ModelsApi short-circuits and the whole disk cache dies)"
                      % (" ".join(clean[shadow:shadow + 40].split("=")[0].split()),
                         clean.count("\n", 0, shadow) + 1))
        else:
            detail = "args=%s" % (args and [clean[a:b].strip() for a, b in args])
        res.append((
            "the production call site forwards `context` as its last argument",
            ok,
            detail,
            REPO_REL,
            line,
        ))

    # ── 2. the Registry must keep the context parameter and forward it ──
    clean = view(registry)
    if clean is None:
        res.append(("Registry fetchModels keeps a `context: Context?` parameter",
                    False, "unparseable Kotlin (fail-closed)", REGISTRY_REL, 0))
        return res
    sig_ok = re.search(r"\b" + CONTEXT_TOKEN + r":\s*android\.content\.Context\??\b",
                       clean) is not None
    res.append((
        "Registry fetchModels keeps a `context: Context?` parameter",
        sig_ok,
        "parameter declaration %s" % ("present" if sig_ok else "MISSING"),
        REGISTRY_REL,
        clean.count("\n", 0, clean.find(CONTEXT_TOKEN + ":")) + 1
        if CONTEXT_TOKEN + ":" in clean else 0,
    ))
    # [wide-1024 / batch7] The forwarder must hand over the PARAMETER, not a
    # same-named local. `val context: Context? = null` as the first statement
    # keeps every `context` spelling in place and forwards null to all five
    # fetchers, which every *ModelsApi turns into "no cache" — deep-audit-1020
    # ISSUE-01 verbatim. Judged on the function BODY (shared `function_body`),
    # so a `"/*"` string can no longer swallow the declaration.
    span = function_body(clean, "fetchModels")
    reg_fn = clean[span[0]:span[1]] if span else None
    shadow = None
    if reg_fn is not None:
        for pos, kind in decl_sites(reg_fn, CONTEXT_TOKEN):
            if kind in ("decl", "shadow"):
                shadow = reg_fn[pos:pos + 48].split("=")[0].strip()
                break
    res.append((
        "Registry fetchModels forwards the caller's `context` parameter "
        "(nothing shadows it)",
        shadow is None and reg_fn is not None,
        "shadowing declaration `%s`" % shadow if shadow else
        ("body not found (fail-closed)" if reg_fn is None else "no shadowing local"),
        REGISTRY_REL,
        0,
    ))
    fwd = 0
    fwd_sites = 0
    pos = 0
    while True:
        args, open_paren = call_arg_spans(clean, "provider.fetchModels(", pos)
        if not args:
            break
        pos = open_paren + 1
        fwd_sites += 1
        if is_bare_context(clean[args[-1][0]:args[-1][1]]):
            fwd += 1
    res.append((
        "Registry forwards `context` to every provider.fetchModels call",
        fwd_sites > 0 and fwd == fwd_sites,
        "%d/%d call sites forward it" % (fwd, fwd_sites),
        REGISTRY_REL,
        0,
    ))

    # ── 3. every adapter call site must pass `context = context` (or be the
    #       known no-cache XAI site with no argument list) ──
    clean = view(adapters)
    if clean is None:
        res.append(("every caching adapter passes `context = context` to its ModelsApi",
                    False, "unparseable Kotlin (fail-closed)", ADAPTERS_REL, 0))
        return res
    passing, silent, viol = 0, 0, []
    for m in ADAPTER_ANCHOR_RE.finditer(clean):
        args, open_paren = call_arg_spans(clean, m.group(0), m.start())
        if open_paren < 0:
            viol.append((m.group(0), "unparseable argument list"))
            continue
        if not args:
            silent += 1  # XAI-style no-cache fetcher: accepted
            continue
        names = named_args(clean, args)
        shadow = local_shadow(clean, open_paren)
        if CONTEXT_TOKEN not in names:
            viol.append((m.group(0), "args=%s (no `context = context`)"
                         % [clean[a:b].strip() for a, b in args]))
        elif not is_bare_context(names[CONTEXT_TOKEN]):
            viol.append((m.group(0), "passes `context = %s` — not the parameter"
                         % names[CONTEXT_TOKEN]))
        elif shadow is not None:
            viol.append((m.group(0),
                         "a local `val %s` shadows the parameter at line %d — "
                         "`context = context` now forwards null"
                         % (CONTEXT_TOKEN, clean.count("\n", 0, shadow) + 1)))
        else:
            passing += 1
    res.append((
        "every caching adapter passes `context = context` to its ModelsApi",
        not viol,
        "; ".join("%s %s" % v for v in viol) if viol else
        "%d passing, %d no-cache (XAI)" % (passing, silent),
        ADAPTERS_REL,
        0,
    ))
    res.append((
        "at least one caching adapter per family forwards the context",
        passing >= 4,
        "%d passing sites" % passing,
        ADAPTERS_REL,
        0,
    ))

    # ── 4. the disk-cache classes must keep their surface ──
    cache_code = view(cache)
    if cache_code is None:
        res.append(("cache dir is built from `context.cacheDir`",
                    False, "unparseable Kotlin (fail-closed)", CACHE_REL, 0))
        return res
    for needle, crit in (
        ("fun load(", "ProviderModelsCache.load reachable"),
        ("fun save(", "ProviderModelsCache.save reachable"),
        ("fun invalidate(", "ProviderModelsCache.invalidate reachable"),
        ("fun cacheDir(context", "cache dir is context-scoped (cacheDir(context...))"),
    ):
        i = cache_code.find(needle)
        res.append((
            crit,
            i >= 0,
            needle,
            CACHE_REL,
            cache_code.count("\n", 0, i) + 1 if i >= 0 else 0,
        ))

    # ── 4b. …and the three cache operations must actually DO their job ──
    # [wide-1024] The docstring's point is that load / save / invalidate were
    # "100% inert"; a body whose first line is `if (true) return` keeps all
    # three names, all three call sites and the whole wiring green while the
    # chain is inert again — 7-day cache never read, 401/403 never swept.
    # [batch7] …and so does an inert `if (1 == 1) return null` after `keyFile(`,
    # and an `if (0 == 1)` wrapping the delete.
    for fn, crit, calls, verb in (
        ("load", "ProviderModelsCache.load actually reads the cached file",
         READ_CALLS, "read"),
        ("save", "ProviderModelsCache.save actually writes the cache file",
         WRITE_CALLS, "write"),
        ("invalidate", "ProviderModelsCache.invalidate actually deletes the cache file",
         DELETE_CALLS, "delete"),
    ):
        span = function_body(cache_code, fn)
        body = cache_code[span[0]:span[1]] if span else None
        reason = dead_body_reason(body, calls, verb)
        if reason is None and "keyFile(" + CONTEXT_TOKEN + "," not in body:
            reason = ("the cache file is not keyed under the caller's context "
                      "(`keyFile(context, …)` absent)")
        res.append((
            crit,
            reason is None,
            reason or "%s path reached" % verb,
            CACHE_REL,
            0,
        ))

    span = function_body(cache_code, "cacheDir")
    cachedir = cache_code[span[0]:span[1]] if span else None
    res.append((
        "cache dir is built from `context.cacheDir`",
        cachedir is not None and "context.cacheDir" in cachedir,
        "declaration `%s`" % ("not found (fail-closed)" if cachedir is None else
                              cachedir.strip()[:70]),
        CACHE_REL,
        0,
    ))

    return res


COMPLIANT_REPO = '''package probe

class Repo(private val context: android.content.Context) {
    suspend fun refreshModels(instance: Any, apiKey: String?, forceRefresh: Boolean) {
        val models = try {
            ModelListProviderRegistry.fetchModels(instance, apiKey, false, forceRefresh, context)
        } catch (e: Exception) {
            emptyList<Any>()
        }
    }
}
'''

COMPLIANT_REGISTRY = '''package probe

object ModelListProviderRegistry {
    suspend fun fetchModels(
        instance: Any,
        apiKey: String?,
        thirdParty: Boolean,
        forceRefresh: Boolean = false,
        context: android.content.Context? = null,
    ): List<Any> {
        if (apiKey == null) return provider.fetchModels(null, instance, thirdParty, forceRefresh, context)
        return provider.fetchModels(apiKey, instance, thirdParty, forceRefresh, context)
    }
}
'''

COMPLIANT_ADAPTERS = '''package probe

object Adapters {
    fun a() = AnthropicModelsApi.fetchModels(x, y, z, f, context = context)
    fun b() = GeminiModelsApi.fetchModels(x, y, z, f, context = context)
    fun c() = OpenAIModelsApi.fetchModels(x, y, z, f, context = context)
    fun d() = OpenAIModelsApi.fetchModels(x2, y2, z2, f2, context = context)
    fun e() = OpenRouterModelsApi.fetchModels(x, y, z, f, context = context)
    fun f() = XAIModelsApi.fetchModels()
}
'''

COMPLIANT_CACHE = '''package probe

class ProviderModelsCache(private val namespace: String) {
    private fun cacheDir(context: android.content.Context) = java.io.File(context.cacheDir, "models-cache/$namespace")

    private fun keyFile(context: android.content.Context, key: String) = java.io.File(cacheDir(context), "$key.json")

    fun load(context: android.content.Context, key: String): List<Any>? {
        val file = keyFile(context, key)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun save(context: android.content.Context, key: String, models: List<Any>) {
        keyFile(context, key).writeText(models.toString())
    }

    fun invalidate(context: android.content.Context, key: String) {
        runCatching { keyFile(context, key).delete() }
    }
}
'''


def fixture_result():
    return analyse(COMPLIANT_REPO, COMPLIANT_REGISTRY, COMPLIANT_ADAPTERS, COMPLIANT_CACHE)


BREAKERS = {}


def _breaker(idx, target_file, pairs, target_criterion):
    BREAKERS[idx] = {"file": target_file, "pairs": pairs, "target": target_criterion}


# B1: the production call site drops the Context again (pre-fix shape)
_breaker(1, "repo", [("forceRefresh, context)", "forceRefresh)")],
         "the production call site forwards `context` as its last argument")
# B2: an adapter silently passes null instead of the context
_breaker(2, "adapters", [("context = context)", "context = null)")],
         "every caching adapter passes `context = context` to its ModelsApi")
# B3: the cache loses its invalidate surface (defeats the 401/403 path)
_breaker(3, "cache", [("    fun invalidate(context: android.content.Context, key: String) {\n        runCatching { keyFile(context, key).delete() }\n    }\n", "")],
         "ProviderModelsCache.invalidate reachable")
# B4: the cache dir stops being context-scoped
_breaker(4, "cache", [('private fun cacheDir(context: android.content.Context) = java.io.File(context.cacheDir, "models-cache/$namespace")',
                       'private fun cacheDir() = java.io.File("/tmp", "models-cache/$namespace")')],
         "cache dir is context-scoped (cacheDir(context...))")
# B5: the Registry loses its context parameter (whole chain collapses)
_breaker(5, "registry", [("        context: android.content.Context? = null,\n", "")],
         "Registry fetchModels keeps a `context: Context?` parameter")

# ── wide-1024 witnesses: the exact knives that used to walk through ──────────
# W1 (dataflow): a same-named local shadows the forwarding parameter — every
# `context` spelling survives, the value forwarded does not.
_breaker(6, "registry", [(
    "    ): List<Any> {\n",
    "    ): List<Any> {\n        val context: android.content.Context? = null\n")],
         "Registry fetchModels forwards the caller's `context` parameter "
         "(nothing shadows it)")
# W2 (dataflow): load keeps its name and its call site, and always misses.
_breaker(7, "cache", [(
    "    fun load(context: android.content.Context, key: String): List<Any>? {\n",
    "    fun load(context: android.content.Context, key: String): List<Any>? {\n        if (true) return null\n")],
         "ProviderModelsCache.load actually reads the cached file")
# W3 (dataflow): invalidate keeps its name and deletes nothing.
_breaker(8, "cache", [(
    "    fun invalidate(context: android.content.Context, key: String) {\n",
    "    fun invalidate(context: android.content.Context, key: String) {\n        if (true) return\n")],
         "ProviderModelsCache.invalidate actually deletes the cache file")

# ── batch7 witnesses (§3 of the task book) ──────────────────────────────────
# 08-1 (dataflow): the shadow moves one hop UP the chain — the call site keeps
# its `context` spelling and forwards the local.
_breaker(9, "repo", [(
    "            ModelListProviderRegistry.fetchModels(",
    "            val context: android.content.Context? = null\n"
    "            ModelListProviderRegistry.fetchModels(")],
         "the production call site forwards `context` as its last argument")
# 08-2 (dataflow): the shadow moves one hop DOWN the chain — the adapter keeps
# the `context = context` spelling and forwards the local.
_breaker(10, "adapters", [(
    "    fun c() = OpenAIModelsApi.fetchModels(",
    "    fun c() { val context: Any? = null; OpenAIModelsApi.fetchModels(x, y, z, f, context = context) }\n"
    "    fun _c() = OpenAIModelsApi.fetchModels(")],
         "every caching adapter passes `context = context` to its ModelsApi")
# 08-3 (parser-death): `"/*"` … `"*/"` swallows the shadow for a
# non-string-aware comment stripper.
_breaker(11, "registry", [(
    "        if (apiKey == null) return",
    '        val _c1 = "/*"; val context: android.content.Context? = null; val _c2 = "*/"\n'
    "        if (apiKey == null) return")],
         "Registry fetchModels forwards the caller's `context` parameter "
         "(nothing shadows it)")
# 08-4 (family-hole): an always-true guard spelled with an arithmetic identity,
# placed AFTER the `keyFile(` anchor so a "return before the anchor" scan misses
# it.
_breaker(12, "cache", [(
    "        val file = keyFile(context, key)\n        if (!file.isFile) return null\n",
    "        val file = keyFile(context, key)\n        if (1 == 1) return null\n"
    "        if (!file.isFile) return null\n")],
         "ProviderModelsCache.load actually reads the cached file")
# 08-5 (family-hole): the delete is not returned-around, it is wrapped in an
# always-false branch — no `return`, no `true`/`false` literal.
_breaker(13, "cache", [(
    "        runCatching { keyFile(context, key).delete() }\n",
    "        if (0 == 1) runCatching { keyFile(context, key).delete() }\n")],
         "ProviderModelsCache.invalidate actually deletes the cache file")


def selftest():
    print("── selftest: compliant fixture must be green, each breaker must go red ──")
    failures = []
    base = fixture_result()
    reds0 = [c for c, ok, _, _, _ in base if not ok]
    if reds0:
        failures.append("compliant fixture is not green: %s" % reds0)
        print("  [FAIL] compliant fixture red on: %s" % reds0)
    else:
        print("  [OK] compliant fixture green on all %d criteria" % len(base))

    for idx, spec in sorted(BREAKERS.items()):
        src = {"repo": COMPLIANT_REPO, "registry": COMPLIANT_REGISTRY,
               "adapters": COMPLIANT_ADAPTERS, "cache": COMPLIANT_CACHE}[spec["file"]]
        mutated = src
        missing = [old for old, _ in spec["pairs"] if old not in mutated]
        if missing:
            failures.append("breaker %d cannot apply (fixture text moved): %s" % (idx, missing))
            print("  [FAIL] breaker %d: anchor not found in fixture" % idx)
            continue
        for old, new in spec["pairs"]:
            mutated = mutated.replace(old, new, 1)
        res = analyse(
            mutated if spec["file"] == "repo" else COMPLIANT_REPO,
            mutated if spec["file"] == "registry" else COMPLIANT_REGISTRY,
            mutated if spec["file"] == "adapters" else COMPLIANT_ADAPTERS,
            mutated if spec["file"] == "cache" else COMPLIANT_CACHE,
        )
        red = [c for c, ok, _, _, _ in res if not ok]
        if spec["target"] not in red:
            failures.append("breaker %d did not turn %r red (red=%s)" % (idx, spec["target"], red))
            print("  [FAIL] breaker %d -> %r stayed green (red=%s)" % (idx, spec["target"], red))
        else:
            detail = dict((c, d) for c, ok, d, _, _ in res if not ok)[spec["target"]]
            extra = "" if len(red) == 1 else " (+%d side)" % (len(red) - 1)
            print("  [OK] breaker %d -> red on %r%s — %s" % (idx, spec["target"], extra, detail[:90]))
    return failures


def main():
    if "--selftest" in sys.argv:
        fails = selftest()
        if fails:
            print("SELFTEST FAIL (%d)" % len(fails))
            return 1
        print("PROVIDER_MODELS_CACHE_WIRING_SELFTEST=PASS (%d fixtures)" % (1 + len(BREAKERS)))
        return 0

    root = None
    args = list(sys.argv[1:])
    if "--root" in args:
        root = args[args.index("--root") + 1]
    elif args:
        root = args[0]
    root = root or "."

    files = {}
    for rel in (REPO_REL, REGISTRY_REL, ADAPTERS_REL, CACHE_REL):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            print("FAIL %s not found under root %s" % (rel, root))
            return 1
        files[rel] = open(path, encoding="utf-8").read()

    results = analyse(files[REPO_REL], files[REGISTRY_REL], files[ADAPTERS_REL], files[CACHE_REL])
    failed = [(c, d, f, l) for c, ok, d, f, l in results if not ok]
    for c, ok, d, f, l in results:
        if ok:
            print("  [OK] %s" % c)
        else:
            print("  [FAIL] %s:%s %s — %s" % (os.path.basename(f), l, c, d))

    total = len(results)
    print("Summary: %d passed, %d failed" % (total - len(failed), len(failed)))
    if failed:
        print("provider models-cache wiring gate: RED — the fix contract is violated")
        return 1
    print("GATE=GREEN provider_models_cache_wiring")
    return 0


if __name__ == "__main__":
    sys.exit(main())
