#!/usr/bin/env python3
"""
Four-way sync check — Model ↔ Entity ↔ toSnapshot ↔ toProviderConfig field consistency.

Background: Adding fields to a data class and forgetting to update any of the
other three layers causes silent data loss (GH#68 image-endpoint, ProviderInstance.pinned).
Lint/compilation don't catch this. This script does.

Usage: python3 scripts/scan/four_way_sync_check.py <repo_root>
Exit code: 0 = clean, 1 = issues found
"""
import re, sys, os

KOTLIN = "src/android/app/src/main/java/com/openminis/app/"

SYNC_GROUPS = [
    {
        "name": "ProviderInstance",
        "model": "ProviderInstance",
        "entity": "ProviderInstanceEntity",
        "model_file": "data/model/ProviderConfig.kt",
        "entity_file": "data/db/ProviderInstanceEntity.kt",
        "mapping_file": "data/db/ProviderConfigMapping.kt",
    },
    {
        "name": "ModelGroup",
        "model": "ModelGroup",
        "entity": "ProviderModelGroupEntity",
        "model_file": "data/model/ProviderConfig.kt",
        "entity_file": "data/db/ProviderModelGroupEntity.kt",
        "mapping_file": "data/db/ProviderConfigMapping.kt",
    },
]

FIELD_ALIASES = {
    "ModelGroup": {
        "memberEntryIds": "memberEntryIdsJson",
    },
    "ProviderInstance": {
        "customHeaders": "customHeadersJson",
        "customBodyFields": "customBodyJson",
    },
}

def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()

def extract_data_class_fields(src, cls_name):
    m = re.search(r"data\s+class\s+" + re.escape(cls_name) + r"\s*\(", src)
    if not m:
        return None, "data class not found: " + cls_name
    start = m.end() - 1
    depth = 0
    i = start
    while i < len(src):
        c = src[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                break
        i += 1
    body = src[start + 1:i]
    fields = re.findall(r'\b(?:val|var)\s+(\w+)\s*:', body)
    return set(fields), None

def extract_ctor_named_args(src, ctor_name, within_func=None):
    if within_func:
        fname = r'fun\s+(?:\w+\.)?' + re.escape(within_func) + r'\s*\('
        fm = re.search(fname, src)
        if not fm:
            return None, "function not found: " + within_func
        ob = src.find('{', fm.start())
        depth = 0
        i = ob
        while i < len(src):
            c = src[i]
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        scope = src[ob:i + 1]
    else:
        scope = src
    args = set()
    for m in re.finditer(re.escape(ctor_name) + r'\s*\(', scope):
        start = m.end() - 1
        depth = 0
        i = start
        while i < len(scope):
            c = scope[i]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        call_body = scope[start + 1:i]
        named = re.findall(r'\b(\w+)\s*=\s*', call_body)
        args.update(named)
    return args, None

def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    problems = 0
    for g in SYNC_GROUPS:
        print(f"\n═══ {g['name']} — four-way sync check ═══")
        base = os.path.join(root, KOTLIN)
        model_src = read(os.path.join(base, g["model_file"]))
        entity_src = read(os.path.join(base, g["entity_file"]))
        mapping_src = read(os.path.join(base, g["mapping_file"]))
        model_fields, e1 = extract_data_class_fields(model_src, g["model"])
        entity_fields, e2 = extract_data_class_fields(entity_src, g["entity"])
        if e1 or e2:
            print(f"  !! Parse error: {e1 or e2}")
            continue
        snap_args, e3 = extract_ctor_named_args(mapping_src, g["entity"], "toSnapshot")
        config_args, e4 = extract_ctor_named_args(mapping_src, g["model"], "toProviderConfig")
        if e3 or e4:
            print(f"  !! Parse error: {e3 or e4}")
            continue
        print(f"  Model ({len(model_fields)}): {sorted(model_fields)}")
        print(f"  Entity ({len(entity_fields)}): {sorted(entity_fields)}")
        missing_entity = set()
        for f in model_fields:
            if f in entity_fields:
                continue
            alias = FIELD_ALIASES.get(g["name"], {}).get(f)
            if alias and alias in entity_fields:
                continue
            missing_entity.add(f)
        missing_snap = entity_fields - snap_args
        missing_config = model_fields - config_args
        LEGIT = {"sortOrder"}
        missing_entity -= LEGIT
        missing_config -= LEGIT
        if not missing_entity and not missing_snap and not missing_config:
            print(f"  ✅ All four layers consistent")
        else:
            if missing_entity:
                problems += 1
                print(f"  ❌ Model has but Entity missing (data lost on save): {sorted(missing_entity)}")
            if missing_snap:
                problems += 1
                print(f"  ❌ Entity has but toSnapshot missing (lost on every save): {sorted(missing_snap)}")
            if missing_config:
                problems += 1
                print(f"  ❌ Model has but toProviderConfig missing (lost on every load): {sorted(missing_config)}")
    print(f"\n{'='*60}")
    print(f"Done: {'PROBLEMS FOUND: ' + str(problems) if problems else 'ALL CLEAN ✅'}")
    return 1 if problems else 0

if __name__ == "__main__":
    sys.exit(main())