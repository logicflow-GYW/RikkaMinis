#!/usr/bin/env python3
"""
gateway_context_metadata_check — [gateway-context-1112] gate.

## Why this gate exists

A model's context window reached the app from ONE of three sources, and they
do not agree:

  1. the endpoint's own `/v1/models` row (`context_length`, `max_input_tokens`,
     `max_completion_tokens`, `top_provider.max_completion_tokens`,
     `display_name`, `supports_reasoning|tools|vision|audio`);
  2. the models.dev registry (keyed by the vendor's own model id);
  3. an id-shape heuristic (a guess, used only when 1 and 2 both missed).

The OpenAI-compatible parser read ONLY `id` / `name` / `architecture`, so
source 1 was never read at all: a gateway advertising a 960 000-token window
had that number discarded, the registry was consulted under an id shape it
does not use (`vendor/model`), and the app fell through to the heuristic's
128 000. The user saw 128K for a model the gateway was serving at 960K.

The precedence must be **endpoint > registry > heuristic**. Each arm below
pins one part of that chain against the shape that shipped before the fix.

Arms:
  A1  the OpenAI-compatible parser READS the endpoint's metadata — the
      context window, the output cap, the display name and the four
      `supports_*` flags all reach the constructed LLMModel;
  A2  the endpoint's value WINS over the registry's for the same field (the
      merge is `endpoint ?: registry`, not the other way round);
  A3  the registry lookup falls back to the id without its leading
      `vendor/` segment (a gateway row must be able to reach the registry);
  A4  there is ONE context-window heuristic — the detail screen's prefill
      helper delegates to `LLMModel.contextWindowTokens` instead of carrying
      a second table (two tables = "same model, two windows");
  A5  the prefilled heuristic guess is NOT persisted as an override — an
      override outranks every source, so pinning the guess would outrank
      the endpoint's own number forever.

Usage: python3 scripts/scan/gateway_context_metadata_check.py [root]
       python3 scripts/scan/gateway_context_metadata_check.py --root <root>
       python3 scripts/scan/gateway_context_metadata_check.py --selftest
Exit:  0 = clean, 1 = violation(s), 2 = usage / read-on-tracking (ROT).
"""
import argparse
import os
import re
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _kotlin_lexer import mask_comments  # noqa: E402

APP = "src/android/app/src/main/java/com/rikkaminis/app"

OPENAI_MODELS_REL = APP + "/provider/openai/OpenAIModelsApi.kt"
ENDPOINT_META_REL = APP + "/provider/openai/OpenAIModelEndpointMetadata.kt"
PRECEDENCE_REL = APP + "/provider/ModelMetadataPrecedence.kt"
MODELS_DEV_REL = APP + "/provider/ModelsDevApi.kt"
HEURISTIC_REL = APP + "/data/model/ModelContextWindowHeuristic.kt"
OVERRIDE_BUILDER_REL = APP + "/ui/settings/ModelEntryOverrideBuilder.kt"

SELFTEST_MARKER = "GATEWAY_CONTEXT_METADATA_SELFTEST=PASS"


def _read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def arm1_endpoint_fields_read(texts):
    """A1: the OpenAI-compatible parser reads the endpoint's metadata."""
    out = []
    meta = texts.get(ENDPOINT_META_REL)
    if meta is None:
        return ["A1 missing %s" % ENDPOINT_META_REL]
    code = mask_comments(meta)

    # The parser must know the context-window field names.
    for field, why in (
        ("context_length", "the OpenRouter / AI-Gateway window field is not read"),
        ("max_input_tokens", "the OpenAI-shaped window field is not read"),
    ):
        if field not in code:
            out.append("A1 endpoint metadata: %s — missing `%s`" % (why, field))

    # ... and the output cap, the display name and the capability flags.
    for field, why in (
        ("max_output_tokens", "the output cap is not read"),
        ("max_completion_tokens", "the OpenAI-shaped output cap is not read"),
        ("top_provider", "the OpenRouter nested output cap is not read"),
        ("display_name", "the gateway display name is not read"),
        ("supports_reasoning", "the reasoning flag is not read"),
        ("supports_tools", "the tools flag is not read"),
        ("supports_vision", "the vision flag is not read"),
        ("supports_audio", "the audio flag is not read"),
    ):
        if field not in code:
            out.append("A1 endpoint metadata: %s — missing `%s`" % (why, field))

    parser = texts.get(OPENAI_MODELS_REL)
    if parser is None:
        return out + ["A1 missing %s" % OPENAI_MODELS_REL]
    pcode = mask_comments(parser)

    if "parseOpenAIModelEndpointMetadata" not in pcode:
        out.append(
            "A1 OpenAIModelsApi: the endpoint metadata parser is not called — "
            "the endpoint's own numbers are still dropped"
        )
    # The parsed values must reach the constructed model.
    for field, why in (
        ("contextWindow = endpointMeta.contextWindow", "the window never reaches the model"),
        ("maxOutputTokens = endpointMeta.maxOutputTokens", "the output cap never reaches the model"),
        ("displayName = endpointMeta.displayName", "the display name never reaches the model"),
    ):
        if field not in pcode:
            out.append("A1 OpenAIModelsApi: %s — missing `%s`" % (why, field))

    # The reasoning flag: TRUE wins, FALSE must not. The local gateway
    # reports `supports_reasoning: false` for EVERY model (it writes the
    # boolean unconditionally from a catalogue that only some providers
    # populate), so treating a false as authoritative would switch the
    # thinking toggle off app-wide.
    if "resolveSupportsReasoning" not in pcode:
        out.append(
            "A1 OpenAIModelsApi: the reasoning flag is not resolved through "
            "`resolveSupportsReasoning` — a gateway that reports false for "
            "every model would switch the thinking toggle off app-wide"
        )

    return out


def arm2_endpoint_beats_registry(texts):
    """A2: the endpoint's value wins over the registry's."""
    out = []
    prec = texts.get(PRECEDENCE_REL)
    if prec is None:
        return ["A2 missing %s" % PRECEDENCE_REL]
    code = mask_comments(prec)

    # The merge must read "model first, registry second" for the two numeric
    # fields. The pre-fix form was `registry ?: model`.
    for field, why in (
        ("contextWindow = model.contextWindow ?: registryContextWindow",
         "the registry window outranks the endpoint's own"),
        ("maxOutputTokens = model.maxOutputTokens ?: registryMaxOutputTokens",
         "the registry output cap outranks the endpoint's own"),
    ):
        if field not in code:
            out.append("A2 precedence: %s — missing `%s`" % (why, field))

    # And the reverse form must be GONE (that is the defect).
    for field, why in (
        ("contextWindow = registryContextWindow ?: model.contextWindow",
         "the pre-fix order (registry wins) is back"),
        ("maxOutputTokens = registryMaxOutputTokens ?: model.maxOutputTokens",
         "the pre-fix order (registry wins) is back"),
    ):
        if field in code:
            out.append("A2 precedence: %s — found `%s`" % (why, field))

    return out


def arm3_registry_lookup_fallback(texts):
    """A3: the registry lookup falls back to the bare (vendor-stripped) id."""
    out = []
    prec = texts.get(PRECEDENCE_REL)
    dev = texts.get(MODELS_DEV_REL)
    if prec is None:
        return ["A3 missing %s" % PRECEDENCE_REL]
    if dev is None:
        return ["A3 missing %s" % MODELS_DEV_REL]

    pcode = mask_comments(prec)
    dcode = mask_comments(dev)

    # The pure helper must strip the leading segment.
    if "registryLookupId" not in pcode:
        out.append("A3 precedence: no `registryLookupId` helper — a `vendor/model` id can never reach the registry")
    elif "indexOf('/')" not in pcode:
        out.append("A3 precedence: `registryLookupId` does not split the id on `/`")

    # And ModelsDevApi must actually USE it (a helper nobody calls is dead code).
    if "registryLookupId" not in dcode:
        out.append(
            "A3 ModelsDevApi: the registry lookup does not use `registryLookupId` — "
            "a gateway row still misses the registry entirely"
        )

    return out


def arm4_single_heuristic(texts):
    """A4: one heuristic table, not two."""
    out = []
    heur = texts.get(HEURISTIC_REL)
    if heur is None:
        return ["A4 missing %s" % HEURISTIC_REL]

    code = mask_comments(heur)

    if "model.contextWindowTokens" not in code:
        out.append(
            "A4 heuristic: `inferContextWindowTokens` does not delegate to "
            "`LLMModel.contextWindowTokens` — the two tables can drift again"
        )

    # The old second table must be gone: no inline id-shape rules here.
    for token, why in (
        ("Regex(", "the id-shape pattern table is back in the helper"),
        ("gpt-4o", "an inline id-shape rule is back in the helper"),
        ("deepseek-", "an inline id-shape rule is back in the helper"),
    ):
        if token in code:
            out.append("A4 heuristic: %s — found `%s`" % (why, token))

    return out


def arm5_guess_not_pinned(texts):
    """A5: the prefilled guess must not be persisted as an override."""
    out = []
    builder = texts.get(OVERRIDE_BUILDER_REL)
    if builder is None:
        return ["A5 missing %s" % OVERRIDE_BUILDER_REL]
    code = mask_comments(builder)

    if "resolveContextWindowOverride" not in code:
        out.append(
            "A5 override builder: no `resolveContextWindowOverride` — the "
            "prefilled guess is written straight into the override and pins itself"
        )
        return out

    # The rule that keeps the guess out: the guess is compared and dropped.
    # The comparison must be a CONDITIONAL — a bare mention of the helper
    # (e.g. in a comment) would pass, and a mutation that returns the parsed
    # value unconditionally must fail.
    if "inferContextWindowTokens" not in code:
        out.append(
            "A5 override builder: the prefilled guess is not compared against the "
            "typed value — an untouched prefill is indistinguishable from an edit"
        )
    elif not re.search(r"if\s*\(\s*parsed\s*==\s*guess\s*\)\s*null\s+else\s+parsed", code):
        out.append(
            "A5 override builder: the guess is not DROPPED when it matches the "
            "typed value — the prefilled guess would be persisted as an override "
            "and would outrank the endpoint's own number forever"
        )

    # The raw write (pre-fix shape) must be gone.
    if "contextWindow = contextWindowText.trim().toIntOrNull()?.takeIf { it > 0 }" in code:
        out.append(
            "A5 override builder: the pre-fix raw write is back — the prefilled "
            "guess would be persisted and would outrank the endpoint forever"
        )

    return out


def arm6_reasoning_false_not_authoritative(texts):
    """A6: a `false` reasoning flag does not switch reasoning off.

    The local gateway writes `supports_reasoning: false` for EVERY model
    (it derives the boolean from a per-provider catalogue that only some
    providers populate, and writes it unconditionally). Treating that false
    as authoritative would switch the thinking toggle off app-wide — so the
    decision must be "true wins, false falls back to the id whitelist".
    """
    out = []
    meta = texts.get(ENDPOINT_META_REL)
    if meta is None:
        return ["A6 missing %s" % ENDPOINT_META_REL]
    code = mask_comments(meta)

    if "resolveSupportsReasoning" not in code:
        return ["A6: no `resolveSupportsReasoning` — the false-is-not-authoritative rule is gone"]

    # The rule itself: true wins, false falls through to the whitelist.
    if not re.search(
            r"endpointFlag\s*==\s*true\s*->\s*true", code):
        out.append(
            "A6 reasoning: the endpoint's `true` does not win — a gateway that "
            "knows the model reasons would be ignored"
        )
    if not re.search(
            r"knownReasoningFamily\s*->\s*true", code):
        out.append(
            "A6 reasoning: the id-family whitelist does not answer — a false "
            "from the gateway would switch the thinking toggle off for models "
            "that do reason"
        )

    return out


def check_tree(root):
    texts = {}
    for rel in (
        OPENAI_MODELS_REL,
        ENDPOINT_META_REL,
        PRECEDENCE_REL,
        MODELS_DEV_REL,
        HEURISTIC_REL,
        OVERRIDE_BUILDER_REL,
    ):
        src = _read(root, rel)
        if src is not None:
            texts[rel] = src

    problems = []
    for arm in (
        arm1_endpoint_fields_read,
        arm2_endpoint_beats_registry,
        arm3_registry_lookup_fallback,
        arm4_single_heuristic,
        arm5_guess_not_pinned,
        arm6_reasoning_false_not_authoritative,
    ):
        problems.extend(arm(texts))

    if problems:
        for p in problems:
            print("VIOLATION: %s" % p)
        return 1
    print("gateway_context_metadata_check: OK (6 arms, 0 violations)")
    return 0


# ── self-test: mutate the tree and prove each arm has teeth ────────────────

_MINIMAL = {
    ENDPOINT_META_REL: """
package com.rikkaminis.app.provider.openai

import org.json.JSONObject

internal fun parseOpenAIModelEndpointMetadata(obj: JSONObject): OpenAIModelEndpointMetadata {
    val contextWindow = firstPositiveInt(
        obj.optInt("context_length"),
        obj.optInt("max_input_tokens"),
        obj.optInt("context_window"),
        obj.optInt("maxInputTokens"),
    )
    val maxOutputTokens = firstPositiveInt(
        obj.optInt("max_output_tokens"),
        obj.optInt("max_completion_tokens"),
        obj.optJSONObject("top_provider")?.optInt("max_completion_tokens") ?: 0,
    )
    val displayName = obj.optString("name").takeIf { it.isNotBlank() }
        ?: obj.optString("display_name").takeIf { it.isNotBlank() }
    return OpenAIModelEndpointMetadata(contextWindow, maxOutputTokens, displayName,
        obj.optBooleanOrNull("supports_reasoning"),
        obj.optBooleanOrNull("supports_tools"),
        obj.optBooleanOrNull("supports_vision"),
        obj.optBooleanOrNull("supports_audio"))
}

private fun firstPositiveInt(vararg candidates: Int): Int? = candidates.firstOrNull { it > 0 }

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

internal fun resolveSupportsReasoning(
    endpointFlag: Boolean?,
    knownReasoningFamily: Boolean,
): Boolean? = when {
    endpointFlag == true -> true
    knownReasoningFamily -> true
    else -> null
}
""",
    OPENAI_MODELS_REL: """
package com.rikkaminis.app.provider.openai

object OpenAIModelsApi {
    fun parse(obj: JSONObject): String {
        val endpointMeta = parseOpenAIModelEndpointMetadata(obj)
        val knownReasoning = false
        return LLMModel(
            id = obj.getString("id"),
            displayName = endpointMeta.displayName ?: obj.getString("id"),
            provider = "Custom",
            contextWindow = endpointMeta.contextWindow,
            maxOutputTokens = endpointMeta.maxOutputTokens,
            supportsReasoning = resolveSupportsReasoning(
                endpointFlag = endpointMeta.supportsReasoning,
                knownReasoningFamily = knownReasoning,
            ),
        ).toString()
    }
}
""",
    PRECEDENCE_REL: """
package com.rikkaminis.app.provider

internal fun registryLookupId(modelId: String): String {
    val slash = modelId.indexOf('/')
    if (slash <= 0) return modelId
    return modelId.substring(slash + 1)
}

internal fun mergeRegistryRow(model: LLMModel, registryContextWindow: Int?, registryMaxOutputTokens: Int?): LLMModel =
    model.copy(
        contextWindow = model.contextWindow ?: registryContextWindow,
        maxOutputTokens = model.maxOutputTokens ?: registryMaxOutputTokens,
    )
""",
    MODELS_DEV_REL: """
package com.rikkaminis.app.provider

object ModelsDevApi {
    fun find(prov: Map<String, LLMModel>, modelId: String): LLMModel? {
        prov[modelId]?.let { return it }
        val bare = registryLookupId(modelId)
        return prov[bare]
    }
}
""",
    HEURISTIC_REL: """
package com.rikkaminis.app.data.model

internal fun inferContextWindowTokens(model: LLMModel): Int =
    model.contextWindowTokens
""",
    OVERRIDE_BUILDER_REL: """
package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.inferContextWindowTokens

internal fun resolveContextWindowOverride(
    text: String,
    baseModel: LLMModel,
    previous: ModelOverrides,
): Int? {
    val parsed = text.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    val previousValue = previous.contextWindow
    if (previousValue != null && parsed == previousValue) return parsed
    val baseValue = baseModel.contextWindow?.takeIf { it > 0 }
    if (baseValue != null) return if (parsed == baseValue) null else parsed
    val guess = inferContextWindowTokens(baseModel)
    return if (parsed == guess) null else parsed
}
""",
}


def _selftest():
    root = tempfile.mkdtemp(prefix="gwctx-gate-")
    try:
        for rel, content in _MINIMAL.items():
            path = os.path.join(root, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(content)

        if check_tree(root) != 0:
            print("SELFTEST: the clean fixture did NOT pass — the gate is broken")
            return 1

        mutations = [
            # A1: the parser stops reading the endpoint's fields.
            ("A1", ENDPOINT_META_REL,
             lambda s: s.replace('"context_length"', '"ctx_len"')),
            # A1: the parsed window never reaches the model.
            ("A1", OPENAI_MODELS_REL,
             lambda s: s.replace("contextWindow = endpointMeta.contextWindow,", "")),
            # A2: the registry outranks the endpoint again.
            ("A2", PRECEDENCE_REL,
             lambda s: s.replace(
                 "contextWindow = model.contextWindow ?: registryContextWindow",
                 "contextWindow = registryContextWindow ?: model.contextWindow")),
            # A3: the vendor-prefix fallback is gone.
            ("A3", PRECEDENCE_REL,
             lambda s: s.replace("val slash = modelId.indexOf('/')", "val slash = -1")),
            # A3: the helper exists but nobody calls it.
            ("A3", MODELS_DEV_REL,
             lambda s: s.replace("registryLookupId(modelId)", "modelId")),
            # A4: the second table is back.
            ("A4", HEURISTIC_REL,
             lambda s: s.replace(
                 "model.contextWindowTokens",
                 'if (model.contextWindow != null) model.contextWindow!! else 128_000')),
            # A5: the guess is persisted.
            ("A5", OVERRIDE_BUILDER_REL,
             lambda s: s.replace(
                 "return if (parsed == guess) null else parsed",
                 "return parsed")),
            # A6: the false flag becomes authoritative again.
            ("A6", ENDPOINT_META_REL,
             lambda s: s.replace(
                 "    knownReasoningFamily -> true\n",
                 "    endpointFlag == false -> false\n")),
        ]

        for expect_arm, rel, mutate in mutations:
            path = os.path.join(root, rel)
            with open(path, encoding="utf-8") as fh:
                original = fh.read()
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(mutate(original))
            rc = check_tree(root)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(original)
            if rc == 0:
                print("SELFTEST FAIL: the %s mutation was NOT caught" % expect_arm)
                return 1
            print("  ✅ %s mutation caught" % expect_arm)

        # Restore check: the tree must be clean again.
        if check_tree(root) != 0:
            print("SELFTEST: the tree did not come back clean")
            return 1

        print(SELFTEST_MARKER)
        return 0
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main(argv):
    ap = argparse.ArgumentParser(add_help=True)
    ap.add_argument("root", nargs="?")
    ap.add_argument("--root", dest="root_opt")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args(argv)

    if args.selftest:
        return _selftest()

    root = args.root_opt or args.root
    if not root:
        print(__doc__)
        return 2
    if not os.path.isdir(root):
        print("not a directory: %s" % root)
        return 2
    return check_tree(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
