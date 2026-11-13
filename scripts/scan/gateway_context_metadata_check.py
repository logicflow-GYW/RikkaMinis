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
DETAIL_SCREEN_REL = APP + "/ui/settings/ModelEntryDetailScreen.kt"

SELFTEST_MARKER = "GATEWAY_CONTEXT_METADATA_SELFTEST=PASS"

# [INCR-1113 F-06] Endpoint metadata fields that are deliberately parsed but
# NOT consumed yet, with the reason. A field that is read and then dropped is
# exactly the defect A1 now exists to catch, so every exemption must be
# written down here instead of being silently tolerated.
CONSUMPTION_EXEMPT = {
    "supportsTools":
        "LLMModel has no tools-capability field; the flag is parsed for diagnostics "
        "only until the data model grows one",
}


def _read(root, rel):
    path = os.path.join(root, rel)
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _function_body(code, header):
    """The brace-balanced body of the declaration starting at [header].

    [code] must already be comment-masked. Returns None when the header or its
    opening brace is missing.
    """
    i = code.find(header)
    if i < 0:
        return None
    brace = code.find("{", i)
    if brace < 0:
        return None
    depth = 0
    for j in range(brace, len(code)):
        ch = code[j]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return code[brace:j + 1]
    return None


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

    # [INCR-1113 F-06] Parsing a capability flag is not using it. The
    # `supports_*` flags were parsed into the holder and then dropped: a
    # gateway that declares `supports_vision: true` without publishing
    # `architecture.input_modalities` left the model text-only, because
    # nothing read the flag back out. Each flag must therefore appear as a
    # CONSUMER (`endpointMeta.<flag>`), not merely as a JSON key.
    for prop in ("supportsVision", "supportsAudio"):
        if prop in CONSUMPTION_EXEMPT:
            continue
        if not re.search(r"endpointMeta\s*\.\s*%s\b" % prop, pcode):
            out.append(
                "A1 OpenAIModelsApi: `%s` is parsed but never consumed — a gateway "
                "that declares the capability without publishing the modality list "
                "leaves the model without it (add a consumer, or list the field in "
                "CONSUMPTION_EXEMPT with a reason)" % prop
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

    # The pure helper must strip EVERY leading segment, not just the first: a
    # relay in front of a gateway stacks them
    # (`openrouter/anthropic/claude-sonnet-4`), and one strip per lookup left
    # `anthropic/claude-sonnet-4`, which is not a registry key either.
    helper = _function_body(pcode, "fun registryLookupId(")
    if helper is None:
        out.append("A3 precedence: no `registryLookupId` helper — a `vendor/model` id can never reach the registry")
    else:
        if "indexOf('/')" not in helper:
            out.append("A3 precedence: `registryLookupId` does not split the id on `/`")
        elif not re.search(r"while\s*\(", helper):
            out.append(
                "A3 precedence: `registryLookupId` strips only ONE segment — a "
                "doubly-prefixed id (`openrouter/anthropic/<model>`) still misses "
                "the registry (the peel must loop)"
            )

    # And ModelsDevApi must actually USE it (a helper nobody calls is dead code),
    # and use its RESULT as the lookup key rather than merely mentioning it.
    if "registryLookupId" not in dcode:
        out.append(
            "A3 ModelsDevApi: the registry lookup does not use `registryLookupId` — "
            "a gateway row still misses the registry entirely"
        )
    else:
        finder = _function_body(dcode, "fun findDevModel(")
        if finder is None:
            out.append("A3 ModelsDevApi: no `findDevModel` — the registry lookup moved or is gone")
        else:
            bindings = re.findall(r"\bval\s+(\w+)\s*=\s*registryLookupId\s*\(", finder)
            used = [b for b in bindings if re.search(r"prov\s*\.\s*models\s*\[\s*%s\s*\]" % b, finder)]
            if not used:
                out.append(
                    "A3 ModelsDevApi: `registryLookupId`'s result is never used as a "
                    "registry key — the fallback is computed and dropped"
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

    # [INCR-1113 F-06] …and the helper must actually be WIRED: a rule nobody
    # calls protects nothing. The screen that owns the context-window field has
    # to reach it through `buildModelEntryOverrides` (which passes it on), not
    # write the override itself.
    detail = texts.get(DETAIL_SCREEN_REL)
    if detail is None:
        out.append("A5 missing %s" % DETAIL_SCREEN_REL)
    else:
        dcode = mask_comments(detail)
        if "buildModelEntryOverrides" not in dcode:
            out.append(
                "A5 detail screen: the context-window field no longer goes through "
                "`buildModelEntryOverrides` — the guess-dropping rule is bypassed"
            )
        elif "resolveContextWindowOverride" in dcode:
            out.append(
                "A5 detail screen: `resolveContextWindowOverride` is called directly — "
                "the override must be built through the single builder"
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

    # [review1112-p1-3] NEGATIVE arm: a branch that makes `false` authoritative
    # must not exist ANYWHERE in the decision.
    #
    # The two assertions above only require the true-branch and the whitelist
    # branch to be PRESENT; appending one extra arm
    #
    #     endpointFlag == false -> false
    #
    # keeps both of them intact and turns the rule off for every model whose
    # gateway reports `false` (i.e. all of them on the local AI-Gateway). That
    # is the realistic regression shape: nobody deletes the `true` branch (the
    # code would look obviously wrong), they add what looks like a reasonable
    # "respect the endpoint's declaration" arm.
    #
    # Measured on the real tree: adding exactly that line left this gate green
    # (6 arms, 0 violations), because the old self-test mutated by REPLACING
    # the whitelist branch instead of appending a false branch.
    for pattern, why in (
        (r"endpointFlag\s*==\s*false\s*->\s*false",
         "the endpoint's `false` is authoritative again — every model served "
         "through a gateway that reports false would lose its thinking toggle"),
        (r"endpointFlag\s*==\s*false\s*->\s*true",
         "a `false` flag now ADMITS reasoning — the flag is being inverted"),
        (r"endpointFlag\s*\?:\s*false",
         "the endpoint's flag is folded in as `?: false`, which turns an "
         "unreported (null) flag into a hard false"),
    ):
        if re.search(pattern, code):
            out.append("A6 reasoning: %s — found `%s`" % (why, pattern))

    # The whitelist must still be REACHED: the call site has to pass the
    # id-family answer in. Hard-wiring `knownReasoningFamily = false` there
    # (or dropping the argument) leaves the decision function itself correct
    # and still switches the toggle off for every gateway model — the same
    # outcome, one layer out. Checked on the parser file because that is where
    # the call lives.
    parser = texts.get(OPENAI_MODELS_REL)
    if parser is None:
        out.append("A6 missing %s" % OPENAI_MODELS_REL)
    else:
        pcode = mask_comments(parser)
        if "resolveSupportsReasoning" in pcode:
            m = re.search(r"knownReasoningFamily\s*=\s*([\w.]+)", pcode)
            if not m:
                out.append(
                    "A6 reasoning: the call site no longer passes the "
                    "id-family answer to `resolveSupportsReasoning`"
                )
            else:
                token = m.group(1)
                if token == "false":
                    out.append(
                        "A6 reasoning: the call site hard-wires "
                        "`knownReasoningFamily = false` — the whitelist can never "
                        "answer, so the endpoint's false wins for every model"
                    )
                elif re.fullmatch(r"[a-z_]\w*", token):
                    # [INCR-1113 F-06] The forwarded value must itself be a
                    # DECISION, not a constant. `val knownReasoning = false`
                    # followed by `knownReasoningFamily = knownReasoning` is the
                    # same defect one local variable away, and the pre-fix gate
                    # could not see it: it only matched the literal.
                    assign = re.search(
                        r"\bval\s+%s\s*=\s*(.+)$" % re.escape(token),
                        pcode, re.M,
                    )
                    if assign is None:
                        out.append(
                            "A6 reasoning: `%s` is passed as the id-family answer but "
                            "has no `val` binding in the parser — the whitelist answer "
                            "is unverifiable here" % token
                        )
                    elif re.fullmatch(r"(?:false|true)\s*", assign.group(1)):
                        out.append(
                            "A6 reasoning: `%s` is a hard-coded constant (`val %s = %s`) — "
                            "the whitelist can never answer, so the endpoint's false "
                            "wins for every model"
                            % (token, token, assign.group(1).strip())
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
        DETAIL_SCREEN_REL,
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

internal fun mergeDeclaredModalities(
    declared: List<String>?,
    declaresVision: Boolean?,
    declaresAudio: Boolean?,
): List<String>? {
    val add = ArrayList<String>(2)
    if (declaresVision == true) add.add("image")
    if (declaresAudio == true) add.add("audio")
    if (add.isEmpty()) return declared
    val base = declared ?: listOf("text")
    return base + add.filter { it !in base }
}
""",
    OPENAI_MODELS_REL: """
package com.rikkaminis.app.provider.openai

object OpenAIModelsApi {
    fun parse(obj: JSONObject): String {
        val endpointMeta = parseOpenAIModelEndpointMetadata(obj)
        val id = obj.getString("id")
        val idLower = id.lowercase()
        val knownReasoning = idLower.startsWith("gpt-5") ||
            idLower.startsWith("o1") ||
            idLower.contains("codex")
        val inputModalities = mergeDeclaredModalities(
            declared = obj.optJSONArray("input_modalities")?.toStringList(),
            declaresVision = endpointMeta.supportsVision,
            declaresAudio = endpointMeta.supportsAudio,
        )
        return LLMModel(
            id = id,
            displayName = endpointMeta.displayName ?: id,
            provider = "Custom",
            contextWindow = endpointMeta.contextWindow,
            maxOutputTokens = endpointMeta.maxOutputTokens,
            inputModalities = inputModalities,
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
    var id = modelId
    var steps = 0
    while (steps++ < MAX_REGISTRY_ID_SEGMENTS) {
        val slash = id.indexOf('/')
        if (slash <= 0 || slash == id.length - 1) return id
        id = id.substring(slash + 1)
    }
    return id
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
    private fun findDevModel(prov: ProviderEntry, modelId: String): ModelDevEntry? {
        prov.models[modelId]?.let { return it }
        val bare = registryLookupId(modelId)
        if (bare == modelId) return null
        return prov.models[bare]
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

internal fun buildModelEntryOverrides(
    baseModel: LLMModel,
    previous: ModelOverrides,
    contextWindowText: String,
): ModelOverrides = previous.copy(
    contextWindow = resolveContextWindowOverride(contextWindowText, baseModel, previous),
)

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
    DETAIL_SCREEN_REL: """
package com.rikkaminis.app.ui.settings

internal fun save(baseModel: LLMModel, overrides: ModelOverrides, text: String): ModelOverrides =
    buildModelEntryOverrides(baseModel = baseModel, previous = overrides, contextWindowText = text)
""",
}


def _selftest():
    """Mutate the fixture tree and prove every arm has teeth.

    [INCR-1113 F-06] Each mutation declares the arm it must trip, and the
    failure output is checked for that arm's name: a mutation that trips a
    DIFFERENT arm is not evidence that the arm under test works. The pre-fix
    self-test only asserted `rc != 0`, so a mutation that stopped reaching its
    own arm still "passed" as long as some other arm complained.
    """
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
            # A1: a capability flag is parsed but never consumed — the exact
            # shape the gate was blind to (it only checked that the JSON key
            # appears in the parser).
            ("A1", OPENAI_MODELS_REL,
             lambda s: s.replace(
                 "declaresVision = endpointMeta.supportsVision,",
                 "declaresVision = null,")),
            # A2: the registry outranks the endpoint again.
            ("A2", PRECEDENCE_REL,
             lambda s: s.replace(
                 "contextWindow = model.contextWindow ?: registryContextWindow",
                 "contextWindow = registryContextWindow ?: model.contextWindow")),
            # A3: the vendor-prefix fallback is gone.
            ("A3", PRECEDENCE_REL,
             lambda s: s.replace("val slash = id.indexOf('/')", "val slash = -1")),
            # A3: the peel stops after ONE segment again (a doubly-prefixed id
            # then misses the registry).
            ("A3", PRECEDENCE_REL,
             lambda s: s.replace(
                 """    var id = modelId
    var steps = 0
    while (steps++ < MAX_REGISTRY_ID_SEGMENTS) {
        val slash = id.indexOf('/')
        if (slash <= 0 || slash == id.length - 1) return id
        id = id.substring(slash + 1)
    }
    return id""",
                 """    val slash = modelId.indexOf('/')
    if (slash <= 0 || slash == modelId.length - 1) return modelId
    return modelId.substring(slash + 1)""")),
            # A3: the helper exists but nobody calls it.
            ("A3", MODELS_DEV_REL,
             lambda s: s.replace("registryLookupId(modelId)", "modelId")),
            # A3: the helper is called and its result dropped on the floor.
            ("A3", MODELS_DEV_REL,
             lambda s: s.replace(
                 "        val bare = registryLookupId(modelId)\n"
                 "        if (bare == modelId) return null\n"
                 "        return prov.models[bare]",
                 "        registryLookupId(modelId)\n"
                 "        return null")),
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
            # A5: the rule survives in the builder but the screen bypasses it.
            ("A5", DETAIL_SCREEN_REL,
             lambda s: s.replace(
                 "buildModelEntryOverrides(baseModel = baseModel, previous = overrides, "
                 "contextWindowText = text)",
                 "overrides.copy(contextWindow = text.trim().toIntOrNull())")),
            # A6: the false flag becomes authoritative again — in the shape a
            # real regression takes: the existing arms stay, a new
            # `endpointFlag == false -> false` arm is APPENDED in front of the
            # whitelist. (The old mutation REPLACED the whitelist arm, which is
            # a shape nobody writes and which the presence-only assertions
            # caught for the wrong reason — see arm6's negative block.)
            ("A6", ENDPOINT_META_REL,
             lambda s: s.replace(
                 "    endpointFlag == true -> true\n",
                 "    endpointFlag == true -> true\n"
                 "    endpointFlag == false -> false\n")),
            # A6: same defect one layer out — the call site stops passing the
            # id-family whitelist, so the endpoint's false wins by default.
            ("A6", OPENAI_MODELS_REL,
             lambda s: s.replace(
                 "knownReasoningFamily = knownReasoning,",
                 "knownReasoningFamily = false,")),
            # A6: …and the same defect hidden behind a local constant, which
            # the pre-fix gate could not see (it only matched the literal).
            ("A6", OPENAI_MODELS_REL,
             lambda s: s.replace(
                 """        val knownReasoning = idLower.startsWith("gpt-5") ||
            idLower.startsWith("o1") ||
            idLower.contains("codex")""",
                 "        val knownReasoning = false")),
        ]

        for expect_arm, rel, mutate in mutations:
            path = os.path.join(root, rel)
            with open(path, encoding="utf-8") as fh:
                original = fh.read()
            mutated = mutate(original)
            if mutated == original:
                print(
                    "SELFTEST FAIL: the %s mutation did not change %s — the fixture "
                    "drifted away from the mutation anchor" % (expect_arm, rel)
                )
                return 1
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(mutated)
            rc, output = _run_check(root)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(original)
            if rc == 0:
                print("SELFTEST FAIL: the %s mutation was NOT caught" % expect_arm)
                return 1
            if expect_arm + " " not in output and expect_arm + ":" not in output:
                print(
                    "SELFTEST FAIL: the %s mutation tripped a different arm — the "
                    "arm under test has no teeth.\n%s" % (expect_arm, output)
                )
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


def _run_check(root):
    """[check_tree] with its violation lines captured, for arm-name assertions."""
    import io
    from contextlib import redirect_stdout

    buf = io.StringIO()
    with redirect_stdout(buf):
        rc = check_tree(root)
    return rc, buf.getvalue()


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
