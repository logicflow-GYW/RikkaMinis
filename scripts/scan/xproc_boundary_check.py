#!/usr/bin/env python3
"""Cross-process boundary completeness guard.

Background: models are handed to the `:modelservice` worker over an IPC boundary.
The dispatcher serialises a model by writing named keys into a bundle; the worker
reconstructs the model by reading those same keys. Adding a field to the model
without adding it to BOTH sides means the worker silently falls back to the
field's default value -- no exception, no log, just wrong behaviour.

This is a repeat offender. Live example (2026-09-22, P0): `LLMModel.maxOutputTokens`
was never written by the dispatcher, so the worker fell back to its 16384 default
and clamped 96.3% of real requests to a smaller reply budget than the user chose.

Note the sibling guard `four_way_sync_check.py` covers Model<->Entity<->snapshot
for ProviderInstance/ModelGroup only -- it does NOT cover this IPC hop, and it does
not cover LLMModel at all. Hence this gate.

Judgement:
  * every field of a boundary model must appear as a dispatcher put() key
    (or be explicitly listed as BENIGN with a reason)
  * every dispatcher key must be consumed on the worker side
"""
import re
import sys
import os

REPO = sys.argv[1] if len(sys.argv) > 1 else "."

# Models that cross the :modelservice IPC boundary.
BOUNDARY_MODELS = [
    {
        "name": "LLMModel",
        "model_path": "src/android/app/src/main/java/com/rikkaminis/app/data/model/LLMModel.kt",
    },
    {
        "name": "ProviderInstance",
        "model_path": "src/android/app/src/main/java/com/rikkaminis/app/data/model/ProviderConfig.kt",
    },
]

# Fields whose dispatcher key is NOT the plain snake_case of the field name.
# (Only needed for acronyms / renamed keys; everything else falls back to
# snake_case so that a NEWLY added field is still caught automatically.)
KEY_ALIASES = {
    "ProviderInstance": {
        "id": "instance_id",
        "label": "instance_label",
        "customBaseURL": "base_url",
        "appendV1Suffix": "append_v1",
        "customUserAgent": "user_agent",
        "useResponsesAPI": "use_responses_api",
        "imageEndpointMode": "image_endpoint_mode",
        "imageEndpointResolved": "image_endpoint_resolved",
        "azureMode": "azure_mode",
    },
    "LLMModel": {
        "id": "model_id",
        "displayName": "model_display_name",
        "provider": "model_provider",
    },
}

DISPATCHER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionDispatcher.kt"
WORKER = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"

# Fields deliberately NOT carried across the boundary.
# Each entry MUST have a reason -- an unexplained entry is how the next P0 hides.
BENIGN = {
    "ProviderInstance": {
        "credentials": (
            "credential METADATA never crosses the boundary: the worker reads "
            "its secret from EncryptedPrefs by slot name, so the labels/notes "
            "stored here are host-side presentation data only. The multi-key "
            "feature that wrote this field was removed; the field survives "
            "only because its column is part of schema version 11."
        ),
        "isEnabled": "worker never reads it; gating happens on the host side",
        "createdAt": "presentation-only ordering metadata",
        "pinned": "worker hard-codes pinned=false; pinning is a host-side UI concept",
    },
}


def read(path):
    full = os.path.join(REPO, path)
    try:
        return open(full, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


def strip_comments(s):
    """Remove // and /* */ comments.

    Required: comment prose contains commas, which would otherwise split a field
    declaration mid-way. Live example -- ProviderInstance.customUserAgent is
    preceded by "Some relay gateways, however, ..." and was silently dropped
    from the field list (8 fields instead of 14).
    """
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def extract_data_class_fields(src, cls_name):
    """Return the ctor parameter names of `data class <cls_name>(...)`."""
    m = re.search(r"data\s+class\s+" + re.escape(cls_name) + r"\s*\(", src)
    if not m:
        return None, "data class not found: " + cls_name
    # Walk to the matching close paren.
    i = m.end() - 1
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "(":
            depth += 1
        elif src[j] == ")":
            depth -= 1
            if depth == 0:
                body = src[i + 1:j]
                break
    else:
        return None, "unbalanced parentheses in " + cls_name
    body = strip_comments(body)
    # Top-level params only: split on commas at depth 0.
    fields, depth, cur = [], 0, ""
    for ch in body:
        if ch in "(<[":
            depth += 1
        elif ch in ")>]":
            depth -= 1
        if ch == "," and depth == 0:
            fields.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        fields.append(cur)
    out = []
    for f in fields:
        f = f.strip()
        if not f or f.startswith("//"):
            continue
        nm = re.match(r"(?:val|var)\s+(\w+)", f)
        if nm:
            out.append(nm.group(1))
    return out, None


def extract_put_keys(src):
    """Named keys written into the bundle.

    The dispatcher writes them inside `bundle.apply { put("k", v) }`, so the call
    is BARE (no leading dot) as often as it is qualified.
    """
    return set(re.findall(
        r'(?:\.|\b)put(?:String|Int|Long|Boolean|Float|Double|StringArrayList)?\s*\(\s*"([^"]+)"',
        src))


def to_snake(name):
    """camelCase / PascalCase -> snake_case (matches the dispatcher's key style)."""
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


def main():
    problems = []
    for g in BOUNDARY_MODELS:
        name = g["name"]
        src = read(g["model_path"])
        if src is None:
            print("FAIL: cannot read %s" % g["model_path"])
            return 1
        fields, err = extract_data_class_fields(src, name)
        if err:
            print("FAIL: %s: %s" % (name, err))
            return 1

        dsrc = read(DISPATCHER)
        if dsrc is None:
            print("FAIL: cannot read %s" % DISPATCHER)
            return 1
        keys = extract_put_keys(dsrc)

        benign = BENIGN.get(name, {})
        alias = KEY_ALIASES.get(name, {})
        missing = []
        for f in fields:
            want = alias.get(f, to_snake(f))
            if want in keys or f in benign:
                continue
            missing.append(f)

        status = "OK" if not missing else "GAP"
        print("%-18s %2d fields  ->  %s" % (name, len(fields), status))
        if missing:
            for f in missing:
                problems.append("%s.%s" % (name, f))
                print("    MISSING  %s.%s  (dispatcher never writes %r)"
                      % (name, f, alias.get(f, to_snake(f))))
        for f, why in sorted(benign.items()):
            if f in fields:
                print("    benign   %s.%s  (%s)" % (name, f, why))

    # Reverse direction: keys written but never mentioned on the worker side.
    dsrc = read(DISPATCHER) or ""
    wsrc = read(WORKER) or ""
    keys = extract_put_keys(dsrc)
    orphan = sorted(k for k in keys if k not in wsrc)
    if orphan:
        print("\nDispatcher writes %d key(s) the worker never mentions:" % len(orphan))
        for k in orphan[:20]:
            print("    ORPHAN   %s" % k)
        problems.extend("orphan:" + k for k in orphan)

    print("")
    if problems:
        print("== %d cross-process boundary problem(s) ==" % len(problems))
        print("")
        print("A field on a boundary model that the dispatcher never writes will")
        print("silently fall back to its default inside the worker. Fix by adding it")
        print("to ModelExecutionDispatcher, or list it under BENIGN with a reason.")
        return 1
    print("== cross-process boundary OK ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
