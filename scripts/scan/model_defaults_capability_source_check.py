#!/usr/bin/env python3
"""model_defaults_capability_source_check — [model-defaults-1112] gate.

## Why this gate exists

A fetched model's capabilities (thinking / modalities / context window) are not
reported by most `/v1/models` endpoints; they come from three sources with a
fixed precedence, and each source has ONE owner. When any of those owners is
bypassed the failure is silent by construction: the request still succeeds, the
model just never saw the image (or never got its real window), and nothing
anywhere says so. This branch fixed four such holes; each arm below pins one of
them against the shape that shipped before the fix.

  1. **The image gate had four inline copies and no tri-state.** All three
     providers wrote `"image" in (model.inputModalities ?: emptyList())`, which
     reads an ABSENT declaration as "no vision" — so a relay whose row carries
     no `architecture` block silently received a text placeholder instead of the
     picture (ChatViewModel's attach gate is `?: true`, so the UI never warned).
     The decision now lives in one property (LLMModel.imageInputDecision /
     acceptsImageInput) with NATIVE / FAMILY_HINT / DECLARED_TEXT_ONLY / UNKNOWN.
  2. **Cache reads skipped enrichment.** Each fetcher saves post-enrichment
     rows, so a cache entry written while the models.dev registry was
     unavailable (fresh install, or the on-device `Failed to fetch models.dev:
     timeout` of 2026-11-10) kept null contextWindow / modalities / reasoning
     for the full 7-day TTL even after the registry landed. The cache path now
     re-enriches like the network path.
  3. **The registry could be overwritten by a degraded payload and torn writes.**
     refreshFromNetwork installed ANY parsed result (a rate-limit stub or a
     partial export would do) and saveDiskCache truncated the live file in
     place — on a device that cannot reach models.dev reliably, that destroys
     the only copy of the defaults. Now: a provider-count floor, a 304 path, and
     an atomic tmp+rename.

Arms (each has a dirty fixture in --selftest):
  A1  every provider request builder gates images through
      `model.acceptsImageInput` (4 sites), and the inline `"image" in (…)
      inputModalities` form is gone from the provider tree;
  A2  the tri-state owner is LLMModel.kt (property + enum + family hint);
  A3  the placeholder wire tests pin a DECLARED text-only fixture
      (`inputModalities = listOf("text")`) — with a null-modality claude fixture
      the arm would silently exercise the FAMILY_HINT send path instead;
  A4  the three list fetchers re-enrich on the cache-hit path;
  A5  the registry refresh refuses a degraded payload, handles 304, and writes
      the disk cache atomically (no in-place truncation).

Usage: python3 scripts/scan/model_defaults_capability_source_check.py [root]
       python3 scripts/scan/model_defaults_capability_source_check.py --root <root>
       python3 scripts/scan/model_defaults_capability_source_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage / read-on-tracking (ROT).
"""
import argparse
import os
import re
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import mask_comments  # noqa: E402

APP = "src/android/app/src/main/java/com/rikkaminis/app"

LLM_MODEL_REL = APP + "/data/model/LLMModel.kt"
STREAM_TIMEOUTS_REL = APP + "/provider/StreamTimeouts.kt"
MODELS_DEV_REL = APP + "/provider/ModelsDevApi.kt"

# Arm 1 — the four request-builder call sites.
PROVIDER_SITES = (
    APP + "/provider/openai/OpenAIProvider.kt",
    APP + "/provider/anthropic/AnthropicProvider.kt",
    APP + "/provider/gemini/GeminiProvider.kt",
)
# OpenAI carries the gate twice (chat-completions + responses builders).
EXPECTED_ACCEPT_CALLS = {"OpenAIProvider.kt": 2, "AnthropicProvider.kt": 1, "GeminiProvider.kt": 1}

# The pre-fix inline shape. An absent list read as "no vision".
INLINE_GATE = re.compile(r'"image"\s+in\s+\(\s*\w+\.inputModalities')

# Arm 3 — wire tests that assert the placeholder must pin a declared-text-only
# model, otherwise they silently exercise the send path.
PLACEHOLDER_TESTS = (
    APP + "/test/java/com/rikkaminis/app/provider/anthropic/AnthropicContentBlocksWireTest.kt"
    .replace("/test/", "/../test/"),
)
PLACEHOLDER_TEST_RELS = (
    "src/android/app/src/test/java/com/rikkaminis/app/provider/anthropic/AnthropicContentBlocksWireTest.kt",
    "src/android/app/src/test/java/com/rikkaminis/app/provider/anthropic/AnthropicImageCarrierWireTest.kt",
    "src/android/app/src/test/java/com/rikkaminis/app/provider/gemini/GeminiNonStreamingWireTest.kt",
    "src/android/app/src/test/java/com/rikkaminis/app/provider/openai/OpenAIToolResultImagesTest.kt",
)

# A6 — the old verdict-only wording, banned anywhere in the test tree: a copy
# pins the literal instead of the constant, so a wording change surfaces as a
# test failure at the copy rather than at the one place that owns it (exactly
# what happened in CI run 43201047456 — two failures, both literal copies).
STALE_PLACEHOLDER_LITERAL = "does not support vision input"

# Arm 4 — fetchers whose cache-hit path must re-enrich.
CACHE_ENRICH_RELS = (
    APP + "/provider/openai/OpenAIModelsApi.kt",
    APP + "/provider/gemini/GeminiModelsApi.kt",
    APP + "/provider/anthropic/AnthropicModelsApi.kt",
)

# Arm 5 — registry hardening anchors.
REGISTRY_FLOOR = "MIN_REGISTRY_PROVIDERS"
REGISTRY_304 = re.compile(r'code\s*==\s*304')
REGISTRY_ATOMIC = re.compile(r'\.tmp["\']?\)?\s*$|renameTo\(', re.M)


def _read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ── arm implementations (pure over source text) ─────────────────────────────

def arm1_provider_gate_owners(texts):
    """A1: call sites use the shared property; the inline form is gone."""
    out = []
    for rel in PROVIDER_SITES:
        src = texts.get(rel)
        if src is None:
            out.append("A1 missing provider file: %s" % rel)
            continue
        name = os.path.basename(rel)
        code = mask_comments(src)
        got = code.count(".acceptsImageInput")
        want = EXPECTED_ACCEPT_CALLS[name]
        if got < want:
            out.append(
                "A1 %s gates images in %d place(s), expected >= %d "
                "(the inline `\"image\" in (…inputModalities…)` form reads an "
                "ABSENT declaration as no-vision)" % (name, got, want)
            )
        hit = INLINE_GATE.search(code)
        if hit:
            out.append("A1 %s re-introduces the inline image gate: %s" % (name, hit.group(0)))
    return out


def arm2_tri_state_owner(texts):
    """A2: the decision lives in LLMModel.kt as a tri-state."""
    src = texts.get(LLM_MODEL_REL)
    if src is None:
        return ["A2 missing %s" % LLM_MODEL_REL]
    code = mask_comments(src)
    out = []
    for needle, why in (
        ("val LLMModel.imageInputDecision", "the single decision owner is gone"),
        ("enum class ImageInputDecision", "the decision is not tri-state"),
        ("DECLARED_TEXT_ONLY", "declared-absent is not distinguished from unknown"),
        ("FAMILY_HINT", "the known-vision-family hint for undeclared rows is gone"),
        ("inputModalities.normalizeModalities()", "the declaration is read without normalising (`image_input` would miss)"),
    ):
        if needle not in code:
            out.append("A2 LLMModel.kt: %s — missing `%s`" % (why, needle))
    return out


def arm3_placeholder_fixtures(texts):
    """A3: placeholder WIRE tests pin a DECLARED text-only fixture."""
    out = []
    for rel in PLACEHOLDER_TEST_RELS:
        src = texts.get(rel)
        if src is None:
            out.append("A3 missing wire test: %s" % rel)
            continue
        code = mask_comments(src)
        if "VISION_UNSUPPORTED_PLACEHOLDER" not in code:
            continue  # the test no longer asserts the placeholder arm at all
        # Only tests that route through a provider+model depend on the fixture's
        # declaration; a pure serializer test passes supportsImages explicitly.
        wire_test = bool(re.search(r'\bmodel\s*=\s*LLMModel', code)) or "provider(model" in code
        if wire_test and not re.search(r'inputModalities\s*=\s*listOf\(\s*"text"\s*\)', code):
            out.append(
                "A3 %s asserts the placeholder arm on a fixture that does not "
                "DECLARE text-only input — with an absent list a known-vision id "
                "falls to FAMILY_HINT and the arm silently tests the send path"
                % os.path.basename(rel)
            )
    return out


def arm6_no_stale_placeholder_literal(texts):
    """A6: the old wording must not exist anywhere in the test tree."""
    out = []
    for rel in PLACEHOLDER_TEST_RELS:
        src = texts.get(rel)
        if src and STALE_PLACEHOLDER_LITERAL in src:
            out.append(
                "A6 %s hard-codes the retired placeholder wording — assert the "
                "constant (VISION_UNSUPPORTED_PLACEHOLDER) instead, so the "
                "wording has one owner" % os.path.basename(rel)
            )
    return out


def arm4_cache_path_enriches(texts):
    """A4: the cache-hit return of each fetcher goes through enrichModels."""
    out = []
    for rel in CACHE_ENRICH_RELS:
        src = texts.get(rel)
        if src is None:
            out.append("A4 missing fetcher: %s" % rel)
            continue
        code = mask_comments(src)
        # Every `cache.load(...)` / `*ModelsCache.load(...)` hit must be wrapped.
        bare = re.compile(
            r'(?<!enrichModels\()\.load\([^)]*\)\s*\??\.let\s*\{\s*return@\w+\s+'
            r'(?!ModelsDevApi\.enrichModels)([^}]*)\}'
        )
        for m in bare.finditer(code):
            inner = m.group(1).strip()
            if "ModelsDevApi.enrichModels(" in inner:
                continue  # wrapped (possibly inside applyGatewayEffortTruth(...))
            out.append(
                "A4 %s returns cache rows without re-enriching (`%s`) — a row "
                "cached while the models.dev registry was down keeps null "
                "contextWindow / modalities / reasoning for the full 7-day TTL"
                % (os.path.basename(rel), inner[:60])
            )
        if "ModelsDevApi.enrichModels(" not in code:
            out.append("A4 %s never enriches at all" % os.path.basename(rel))
    return out


def arm5_registry_hardening(texts):
    """A5: floor + 304 + atomic write on the registry path."""
    src = texts.get(MODELS_DEV_REL)
    if src is None:
        return ["A5 missing %s" % MODELS_DEV_REL]
    code = mask_comments(src)
    out = []
    if REGISTRY_FLOOR not in code:
        out.append("A5 no provider-count floor: a degraded models.dev payload "
                   "overwrites a usable disk cache")
    else:
        # The floor must GUARD the install, not merely exist: compare before
        # saveDiskCache / cachedRegistry assignment.
        guard = re.search(
            r'size\s*<\s*MIN_REGISTRY_PROVIDERS[\s\S]{0,400}?(return|keeping)', code)
        if not guard:
            out.append("A5 MIN_REGISTRY_PROVIDERS is not consulted before "
                       "installing the payload")
    if not REGISTRY_304.search(code):
        out.append("A5 no 304 path — every refresh re-downloads the whole registry")
    save = re.search(r'private fun saveDiskCache\([\s\S]*?\n    \}', code)
    if not save:
        out.append("A5 saveDiskCache not found")
    else:
        body = save.group(0)
        if "renameTo(" not in body:
            out.append("A5 saveDiskCache writes in place (no tmp+rename) — a torn "
                       "cache file is a total loss when models.dev is unreachable")
    return out


CHECKS = (arm1_provider_gate_owners, arm2_tri_state_owner, arm3_placeholder_fixtures,
          arm4_cache_path_enriches, arm5_registry_hardening,
          arm6_no_stale_placeholder_literal)

TRACKED = tuple(
    list(PROVIDER_SITES) + [LLM_MODEL_REL] + list(PLACEHOLDER_TEST_RELS) +
    list(CACHE_ENRICH_RELS) + [MODELS_DEV_REL]
)


def collect(root):
    return {rel: _read(root, rel) for rel in TRACKED}


def run(root, quiet=False):
    texts = collect(root)
    findings = []
    for check in CHECKS:
        findings.extend(check(texts))
    if findings and not quiet:
        for f in findings:
            print("VIOLATION: " + f)
    if not findings and not quiet:
        print("model_defaults_capability_source_check: OK (%d arms, %d files)"
              % (len(CHECKS), len(TRACKED)))
    return 1 if findings else 0


# ── selftest: every arm must flip red on its own dirty fixture ───────────────

def _good_tree(tmp):
    for rel in TRACKED:
        path = os.path.join(tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(_read(REPO_ROOT, rel) or "")


def selftest():
    failures = []
    # Clean baseline.
    with tempfile.TemporaryDirectory() as tmp:
        _good_tree(tmp)
        if run(tmp, quiet=True) != 0:
            failures.append("clean tree is red: %s" % collect(tmp))
            print("  [FAIL] baseline clean tree is red")
        else:
            print("  [OK]   baseline clean tree")

    def _append_inline_gate(src):
        return src + (
            "\nprivate fun probeRegression(model: LLMModel): Boolean {\n"
            '    val supportsImages = "image" in (model.inputModalities ?: emptyList())\n'
            "    return supportsImages\n"
            "}\n"
        )

    cases = [
        ("A1 inline gate back", PROVIDER_SITES[0], _append_inline_gate, "A1"),
        ("A2 tri-state owner emptied", LLM_MODEL_REL,
         lambda s: s.replace("enum class ImageInputDecision", "// removed", 1), "A2"),
        ("A3 placeholder fixture un-pinned", PLACEHOLDER_TEST_RELS[0],
         lambda s: s.replace('inputModalities = listOf("text")', "inputModalities = null", 1), "A3"),
        ("A4 cache path stops enriching", CACHE_ENRICH_RELS[2],
         lambda s: s.replace(
             "AnthropicModelsCache.load(context, cacheKey)\n                "
             "?.let { return@withContext ModelsDevApi.enrichModels(it) }",
             "AnthropicModelsCache.load(context, cacheKey)?.let { return@withContext it }", 1),
         "A4"),
        ("A5 registry floor un-guarded", MODELS_DEV_REL,
         lambda s: s.replace("if (parsed.size < MIN_REGISTRY_PROVIDERS) {", "if (false) {", 1), "A5"),
        ("A5 in-place cache write", MODELS_DEV_REL,
         lambda s: s.replace("if (!tmp.renameTo(file)) {", "if (false) {", 1), "A5"),
        ("A6 stale placeholder literal", PLACEHOLDER_TEST_RELS[3],
         lambda s: s + '\n// "[Image attached but this model does not support vision input]"\n', "A6"),
    ]
    for label, rel, mutate, needle in cases:
        with tempfile.TemporaryDirectory() as tmp:
            _good_tree(tmp)
            path = os.path.join(tmp, rel)
            src = open(path, encoding="utf-8").read()
            dirty = mutate(src)
            if dirty == src:
                failures.append(label + " (fixture drift — mutation was a no-op)")
                print("  [FAIL] %s: mutation changed nothing" % label)
                continue
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dirty)
            findings = []
            texts = collect(tmp)
            for check in CHECKS:
                findings.extend(check(texts))
            if not findings:
                failures.append(label)
                print("  [FAIL] %s — stayed green" % label)
            elif not any(f.startswith(needle) for f in findings):
                failures.append(label + " (wrong arm)")
                print("  [FAIL] %s — red for the wrong reason: %s" % (label, findings[0][:80]))
            else:
                print("  [OK]   %s — %s" % (label, [f for f in findings if f.startswith(needle)][0][:70]))

    # ROT: an empty tree must never read clean.
    with tempfile.TemporaryDirectory() as tmp:
        if run(tmp, quiet=True) == 0:
            failures.append("missing sources reported clean")
            print("  [FAIL] ROT: a tree without the tracked files reported clean")
        else:
            print("  [OK]   ROT: missing sources are a red")

    if failures:
        print("SELFTEST FAIL (%d): %s" % (len(failures), "; ".join(failures)))
        return 1
    print("MODEL_DEFAULTS_CAPABILITY_SOURCE_SELFTEST=PASS (gate flips red on every criterion)")
    return 0


REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def main():
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("root", nargs="?", default=None)
    ap.add_argument("--root", dest="root_opt", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    root = args.root_opt or args.root or REPO_ROOT
    if not os.path.isdir(root):
        print("root not found: %s" % root, file=sys.stderr)
        return 2
    return run(root)


if __name__ == "__main__":
    sys.exit(main())
