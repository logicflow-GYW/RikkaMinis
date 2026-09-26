#!/usr/bin/env python3
"""Whole-URL-into-a-log guard — RikkaMinis CI scan gate.

## Why this gate exists

`AppLogger` is on by default, mirrors to logcat unconditionally and writes to
filesDir/logs. Several providers put the API key in the URL QUERY (Gemini's
`?key=...`; any OpenAI-compatible relay whose base URL is user-supplied), so the
WHOLE request URL is a credential-bearing string — precisely why
`debug/LLMRequestLog.redactURL` exists.

2026-09-26: an absorb pack added a network trace listener whose `callStart`
logged `url=${call.request().url}`. Every existing gate was green: it compiled,
the i18n key sets matched, the four-way sync was complete, the legacy-pipeline
guard had nothing to say. None of them asked "can a secret reach an output
surface through this NEW line" — that is the wiring question, and this gate asks
it for the one shape that has already bitten once.

## What it flags

Inside a log-call statement (`AppLogger.<lvl>(`, `Log.<lvl>(`, `Timber.<lvl>(`,
`println(`), an interpolation carrying the WHOLE url value:

  - a bare `$ident` whose name mentions url — Kotlin expands `"$url.host"` to
    `url.toString() + ".host"`, so the bare form leaks the whole value no matter
    what literal text follows;
  - `${expr}` mentioning url, unless it ends in a safe accessor (host, hostname,
    port, scheme, path, encodedPath, authority) or is wrapped by a redactor.

Audited existing sites are pinned in [ALLOWED] as (relative path, snippet
prefix); `--seed` prints entries for the current tree, which is how the list is
maintained. A line may also be exempted inline with `url-ok: <reason>` (the
repo's `debug-ok:` convention).

Usage:
  python3 scripts/scan/log_url_redaction_guard.py <repo_root>
  python3 scripts/scan/log_url_redaction_guard.py --seed <repo_root>
  python3 scripts/scan/log_url_redaction_guard.py --self-test
"""

import os
import re
import sys
import tempfile

SRC_SUBDIR = "src/android/app/src/main/java"

# Credential-bearing URLs only flow through these subtrees: the provider request
# paths, the shared OkHttp tracing layer, the offload gateway, and the repository
# that assembles base URLs. Page / preview / Custom-Tab URLs live in ui/, webapp/
# and browser/ and are user-facing by design — scanning them yields 30+ findings
# that all amount to "a page URL is not a secret", which is how a gate turns into
# noise and then gets deleted.
SCAN_DIRS = (
    "com/rikkaminis/app/network",
    "com/rikkaminis/app/provider",
    "com/rikkaminis/app/sandbox/offload",
    "com/rikkaminis/app/data/repository",
)

LOG_CALL = re.compile(r"\b(?:AppLogger|Log|Timber)\.[A-Za-z]+\s*\(|\bprintln\s*\(")
DYN = re.compile(r"\$\{([^{}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")
SAFE_TAIL = re.compile(
    r"\.(host|hostname|port|scheme|path|encodedPath|authority)\s*$", re.IGNORECASE
)
REDACTOR = re.compile(r"redact|sanitiz|scrub", re.IGNORECASE)
NARROW_NAME = re.compile(r"(host|hostname|port|scheme|path|authority)$", re.IGNORECASE)
MAX_SPAN_LINES = 12

# ── audited call sites. Removing an entry re-arms the gate for that line;
# editing the line makes the entry stale (re-audit, then re-seed with --seed).
ALLOWED = {
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/ModelsDevApi.kt', 'Log.d(TAG, "No models.dev match for base URL: $forBaseURL")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OkHttpNetTraceListener.kt', 'com.rikkaminis.app.logging.AppLogger.info('),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt', 'com.rikkaminis.app.logging.AppLogger.info('),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt', 'com.rikkaminis.app.logging.AppLogger.info('),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt', 'com.rikkaminis.app.logging.AppLogger.info('),
    ('src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt', 'com.rikkaminis.app.logging.AppLogger.warning('),
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt', 'Log.i(TAG, "[ModelUseRoute] raw-passthrough done status=${result.statu'),
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt', 'Log.d(TAG, "startActivity url=\'$url\' action=${intent.action} resolved='),
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt', 'Log.w(TAG, "no handler for \'$url\'")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OpenOffloadHandler.kt', 'Log.w(TAG, "parseUri failed for \'$url\': ${e.message}")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/ProviderRepository.kt', 'android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} t'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] HTTP ${resp.code} non-retryable for $url")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] HTTP ${resp.code} on $url (attempt ${attempt + '),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "[siblings] ${e.javaClass.simpleName}: ${e.message} for $ur'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "httpGetString failed $url: ${e.message}")'),
    ('src/android/app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt', 'Log.w(TAG, "httpGetBytes failed $url: ${e.message}")'),
}

# ── inline exemption marker, same shape as the repo's `debug-ok:`
EXEMPT = "url-ok:"


def _strip_span(lines, start):
    """Return (span_text, end_index) for the call statement starting at `start`."""
    depth = 0
    in_str = False
    escaped = False
    out = []
    for i in range(start, min(start + MAX_SPAN_LINES, len(lines))):
        text = lines[i]
        out.append(text)
        for ch in text:
            if escaped:
                escaped = False
                continue
            if ch == "\\":
                escaped = True
                continue
            if ch == '"':
                in_str = not in_str
                continue
            if in_str:
                continue
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
        if depth <= 0:
            return "\n".join(out), i
    return "\n".join(out), min(start + MAX_SPAN_LINES, len(lines)) - 1


def _offenders(span):
    """Interpolations in `span` that carry the whole url value."""
    found = []
    for braced, bare in DYN.findall(span):
        if braced:
            expr = braced.strip()
            if "url" not in expr.lower():
                continue
            if REDACTOR.search(expr) or SAFE_TAIL.search(expr):
                continue
            found.append("${%s}" % expr)
        else:
            if "url" not in bare.lower() or NARROW_NAME.search(bare):
                continue
            found.append("$%s" % bare)
    return found


def scan(root):
    violations = []
    for scan_dir in SCAN_DIRS:
        base = os.path.join(root, SRC_SUBDIR, scan_dir)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in sorted(filenames):
                if not name.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, name)
                rel = os.path.relpath(path, root)
                with open(path, encoding="utf-8", errors="replace") as fh:
                    lines = fh.read().split("\n")
                i = 0
                while i < len(lines):
                    if not LOG_CALL.search(lines[i]):
                        i += 1
                        continue
                    span, end = _strip_span(lines, i)
                    context = "\n".join(lines[max(0, i - 2) : end + 1])
                    if EXEMPT not in context:
                        bad = _offenders(span)
                        if bad:
                            snippet = lines[i].strip()[:70]
                            if (rel, snippet) not in ALLOWED:
                                violations.append(
                                    (rel, i + 1, snippet, bad, span.strip()[:200])
                                )
                    i = end + 1
    return violations


def self_test():
    fixture = "src/android/app/src/main/java/com/rikkaminis/app/network/Trace.kt"
    lines = [
        # 1  pre-fix A-1 shape: whole URL into the log — must be caught
        "        AppLogger.info(",
        "            tag,",
        '            "[${callTag(call)}] +${ms()}ms callStart url=${call.request().url}"',
        "        )",
        # 5  post-fix A-1 shape: redactor present — must pass
        "        AppLogger.info(",
        "            tag,",
        '            "callStart url=${com.rikkaminis.app.debug.LLMRequestLog.redactURL(call.request().url.toString())}"',
        "        )",
        # 9  safe accessors — must pass
        '        AppLogger.warning(TAG, "host=${url.host} port=${url.port}")',
        # 10 bare $url.host leaks the whole value (Kotlin semantics) — must be caught
        '        Log.w(TAG, "reason=$failure for $url.host")',
        # 11 inline exemption — must pass
        '        AppLogger.info(TAG, "opened $url")  // url-ok: user-facing page',
        # 12 a non-log statement mentioning a url — must pass
        "        val hit = url.matches(regex)",
    ]
    tmp = tempfile.mkdtemp(prefix="urlguard-")
    path = os.path.join(tmp, fixture)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    got = {(rel, line) for rel, line, _s, _b, _sp in scan(tmp)}
    want = {(fixture, 1), (fixture, 10)}
    if got != want:
        print("SELF-TEST FAILED")
        print("  expected:", sorted(want))
        print("  got     :", sorted(got))
        return 1
    print(
        "✅ self-test: catches the pre-fix whole-URL line + the bare-$url leak, "
        "passes redacted / safe-accessor / exempt / non-log forms"
    )
    return 0


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--self-test":
        return self_test()
    do_seed = args[0] == "--seed"
    if do_seed:
        args = args[1:]
    if not args:
        print(__doc__)
        return 2
    violations = scan(args[0])
    if do_seed:
        print("# --- generated by --seed: paste into ALLOWED ---")
        for rel, _line, snippet, _bad, _span in violations:
            print("    (%r, %r)," % (rel, snippet))
        return 0
    if violations:
        print("❌ 日志里出现整条 URL（可能带密钥）：")
        for rel, line, snippet, bad, _span in violations:
            print("  %s:%d" % (rel, line))
            print("      %s" % snippet)
            print("      泄漏形态: %s" % ", ".join(bad))
            print("      修法: 用 LLMRequestLog.redactURL(...) 包一层，或只打 host/path；")
            print("            确认与凭据无关时在行内加 `url-ok: <理由>`。")
        print("")
        print("  共 %d 处；已审计站点跑 --seed 后并入 ALLOWED。" % len(violations))
        return 1
    print(
        "✅ log url guard: no whole-URL interpolation reaches a log "
        "(audited sites: %d)" % len(ALLOWED)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
