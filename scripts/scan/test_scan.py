#!/usr/bin/env python3
"""
Scan self-test — verify the scanners themselves catch what they claim to catch.

Each scanner gets three checks:
  1. CLEAN fixture  → must exit 0
  2. DIRTY fixture  → must exit 1 (the violation class it was built for)
  3. REAL repo tree → must exit 0 (scanners are green in CI today)

Why: a scanner that silently regresses (a regex stops matching after a
refactor, a path moves) fails OPEN — CI stays green while the exact bug class
the scanner was built for (GH#68, P0-pinned-providers, enum crash-on-read…)
walks back in. These fixtures are the ground truth: known-bad trees are
asserted to be caught, so a broken scanner fails LOUD here instead of failing
silent in every future build.

Usage: python3 scripts/scan/test_scan.py [repo_root]
Exit:  0 = all scanners pass all cases, 1 = any failure
"""
import base64
import contextlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def _parse_argv(argv):
    """Split argv into (--jobs value, --single name, positional roots).

    Positional semantics are unchanged (first positional arg = repo root, the
    form gate_run.sh and the docs use); `--jobs N` selects the worker-pool size
    and `--single NAME` is the child mode of that pool. Neither flag may be
    mistaken for the repo root, which is why this parses instead of indexing.
    Unknown flags are left in place so old positional callers behave as before.
    """
    jobs, single, roots = 1, None, []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--jobs" and i + 1 < len(argv):
            try:
                jobs = max(1, int(argv[i + 1]))
            except ValueError:
                sys.exit("test_scan.py: --jobs needs an integer, got %r" % argv[i + 1])
            i += 2
            continue
        if a.startswith("--jobs="):
            try:
                jobs = max(1, int(a.split("=", 1)[1]))
            except ValueError:
                sys.exit("test_scan.py: --jobs needs an integer, got %r" % a)
            i += 1
            continue
        if a == "--single" and i + 1 < len(argv):
            single = argv[i + 1]
            i += 2
            continue
        roots.append(a)
        i += 1
    return jobs, single, roots


JOBS, SINGLE, _ROOTS = _parse_argv(sys.argv[1:])
REPO_ROOT = os.path.abspath(
    _ROOTS[0] if _ROOTS else os.path.join(SCRIPT_DIR, "..", "..")
)

# Relative roots the scanners expect inside a repo tree.
KOTLIN_APP = "src/android/app/src/main/java"
KOTLIN_PKG = os.path.join(KOTLIN_APP, "com/rikkaminis/app")
RES_VALUES = "src/android/app/src/main/res/values"

PASS = 0
FAIL = 0


def run_scanner(script, root, *extra):
    """Run one scanner against root; return (exit_code, combined_output)."""
    proc = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, script), root, *extra],
        capture_output=True,
        text=True,
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def make_tree(files):
    """files: {relative_path: content} → tempdir repo root."""
    root = tempfile.mkdtemp(prefix="scan-selftest-")
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
    return root


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  ✅ {name}")
    else:
        FAIL += 1
        print(f"  ❌ {name}")
        if detail:
            print(f"     {detail}")


# ─────────────────────────────────────────────────────────────────────────────
# Fixtures
# ─────────────────────────────────────────────────────────────────────────────

FW_MODEL = """package com.rikkaminis.app.data.model

data class ProviderInstance(
    val id: String,
    val label: String,
    val apiKey: String,
)
"""

FW_ENTITY = """package com.rikkaminis.app.data.db

data class ProviderInstanceEntity(
    val id: String,
    val label: String,
    val apiKey: String,
)
"""

FW_MAPPING = """package com.rikkaminis.app.data.db

fun ProviderConfig.toSnapshot(): ProviderConfigSnapshot {
    val instanceRows = instances.mapIndexed { idx, inst ->
        ProviderInstanceEntity(
            id = inst.id,
            label = inst.label,
            apiKey = inst.apiKey,
        )
    }
    return ProviderConfigSnapshot(instanceRows)
}

fun ProviderConfigSnapshot.toProviderConfig(): ProviderConfig {
    val instances = this.instances.map { row ->
        ProviderInstance(
            id = row.id,
            label = row.label,
            apiKey = row.apiKey,
        )
    }.toMutableList()
    return ProviderConfig(instances = instances)
}
"""

# Minimal stubs for the files the scanner reads unconditionally for the
# (inert in this fixture) ModelGroup group.
FW_GROUP_ENTITY_STUB = (
    "package com.rikkaminis.app.data.db\n"
    "// ModelGroup intentionally absent — exercises the parse-error-continue path.\n"
)


def four_way_fixture(model_extra="", entity_extra="", snap_extra="", config_extra=""):
    """Build a four-way-sync fixture with optional per-layer extra fields."""
    model = FW_MODEL.replace("    val apiKey: String,\n", "    val apiKey: String,\n" + model_extra)
    entity = FW_ENTITY.replace(
        "    val apiKey: String,\n", "    val apiKey: String,\n" + entity_extra
    )
    snap_args = "            apiKey = inst.apiKey,\n" + snap_extra
    config_args = "            apiKey = row.apiKey,\n" + config_extra
    mapping = FW_MAPPING.replace("            apiKey = inst.apiKey,\n", snap_args)
    mapping = mapping.replace("            apiKey = row.apiKey,\n", config_args)
    return {
        os.path.join(KOTLIN_PKG, "data/model/ProviderConfig.kt"): model,
        os.path.join(KOTLIN_PKG, "data/db/ProviderInstanceEntity.kt"): entity,
        os.path.join(KOTLIN_PKG, "data/db/ProviderModelGroupEntity.kt"): FW_GROUP_ENTITY_STUB,
        os.path.join(KOTLIN_PKG, "data/db/ProviderConfigMapping.kt"): mapping,
    }


# ── v2 derivation fixtures: families beyond the v1 hardcode ───────────────

def derived_widget_fixture(model_extra=""):
    """A family v1 never knew: WidgetEntity ↔ Widget — derivation must pick
    it up from data/db/ alone (no SYNC_GROUPS entry, no alias pin)."""
    model = (
        "package com.rikkaminis.app.data.model\n"
        "data class Widget(\n"
        "    val id: String,\n"
        "    val name: String,\n"
        + model_extra +
        ")\n"
    )
    entity = (
        "package com.rikkaminis.app.data.db\n"
        "data class WidgetEntity(\n"
        "    val id: String,\n"
        "    val name: String,\n"
        ")\n"
    )
    mapping = (
        "package com.rikkaminis.app.data.db\n"
        "import com.rikkaminis.app.data.model.Widget\n"
        "fun toSnapshot(w: Widget): WidgetEntity =\n"
        "    WidgetEntity(\n"
        "        id = w.id,\n"
        "        name = w.name,\n"
        "    )\n"
        "fun toProviderConfig(e: WidgetEntity): Widget =\n"
        "    Widget(\n"
        "        id = e.id,\n"
        "        name = e.name,\n"
        "    )\n"
    )
    return {
        os.path.join(KOTLIN_PKG, "data/model/Widget.kt"): model,
        os.path.join(KOTLIN_PKG, "data/db/WidgetEntity.kt"): entity,
        os.path.join(KOTLIN_PKG, "data/db/WidgetMapping.kt"): mapping,
    }


def derived_alias_fixture():
    """ModelEntry family: model/ctor arg names differ from entity columns by
    the real FIELD_ALIASES (uuid→id, baseModel→baseModelJson,
    overrides→overridesJson) — alias normalization must keep it GREEN."""
    model = (
        "package com.rikkaminis.app.data.model\n"
        "data class ModelEntry(\n"
        "    val uuid: String,\n"
        "    val baseModel: String,\n"
        "    val overrides: Int,\n"
        "    val isCustom: Boolean,\n"
        ")\n"
    )
    entity = (
        "package com.rikkaminis.app.data.db\n"
        "data class ProviderModelEntryEntity(\n"
        "    val id: String,\n"
        "    val baseModelJson: String,\n"
        "    val overridesJson: Int,\n"
        "    val isCustom: Boolean,\n"
        ")\n"
    )
    mapping = (
        "package com.rikkaminis.app.data.db\n"
        "fun toSnapshot(e: ModelEntry): ProviderModelEntryEntity =\n"
        "    ProviderModelEntryEntity(\n"
        "        id = e.uuid,\n"
        "        baseModelJson = e.baseModel,\n"
        "        overridesJson = e.overrides,\n"
        "        isCustom = e.isCustom,\n"
        "    )\n"
        "fun toProviderConfig(e: ProviderModelEntryEntity): ModelEntry =\n"
        "    ModelEntry(\n"
        "        uuid = e.id,\n"
        "        baseModel = e.baseModelJson,\n"
        "        overrides = e.overridesJson,\n"
        "        isCustom = e.isCustom,\n"
        "    )\n"
    )
    return {
        os.path.join(KOTLIN_PKG, "data/model/ModelEntry.kt"): model,
        os.path.join(KOTLIN_PKG, "data/db/ProviderModelEntryEntity.kt"): entity,
        os.path.join(KOTLIN_PKG, "data/db/ProviderModelEntryMapping.kt"): mapping,
    }


def derived_orphan_fixture():
    """An entity whose model class doesn't exist under KOTLIN — must land in
    UNRESOLVED (visible) and NOT fail the run."""
    orphan = (
        "package com.rikkaminis.app.data.db\n"
        "data class OrphanEntity(\n"
        "    val id: String,\n"
        ")\n"
    )
    return {os.path.join(KOTLIN_PKG, "data/db/OrphanEntity.kt"): orphan}


def derived_pinned_fixture():
    """ThinkingRule family: kind/scope/wireFormat/reasoningEcho have no 1:1
    entity column by design — KNOWN_MISMATCH pins must print WARNING lines
    and keep the run GREEN (exit 0)."""
    model = (
        "package com.rikkaminis.app.provider.thinking\n"
        "data class ThinkingRule(\n"
        "    val id: String,\n"
        "    val label: String,\n"
        "    val kind: String,\n"
        "    val scope: String,\n"
        "    val wireFormat: String,\n"
        "    val reasoningEcho: String,\n"
        ")\n"
    )
    entity = (
        "package com.rikkaminis.app.data.db\n"
        "data class ProviderThinkingRuleEntity(\n"
        "    val id: String,\n"
        "    val label: String,\n"
        "    val scopeKind: String,\n"
        "    val scopePattern: String,\n"
        "    val wireFormatJson: String,\n"
        "    val reasoningEchoJson: String,\n"
        "    val sortOrder: Int,\n"
        ")\n"
    )
    mapping = (
        "package com.rikkaminis.app.provider.thinking\n"
        "fun toEntity(r: ThinkingRule): ProviderThinkingRuleEntity =\n"
        "    ProviderThinkingRuleEntity(\n"
        "        id = r.id,\n"
        "        label = r.label,\n"
        "        scopeKind = r.kind,\n"
        "        scopePattern = r.scope,\n"
        "        wireFormatJson = encodeWireFormat(r.wireFormat),\n"
        "        reasoningEchoJson = encodeEcho(r.reasoningEcho),\n"
        "        sortOrder = 0,\n"
        "    )\n"
        "fun toRule(e: ProviderThinkingRuleEntity): ThinkingRule =\n"
        "    ThinkingRule(\n"
        "        id = e.id,\n"
        "        label = e.label,\n"
        "        kind = e.scopeKind,\n"
        "        scope = e.scopePattern,\n"
        "        wireFormat = decodeWireFormat(e.wireFormatJson),\n"
        "        reasoningEcho = decodeEcho(e.reasoningEchoJson),\n"
        "    )\n"
    )
    return {
        os.path.join(KOTLIN_PKG, "provider/thinking/ThinkingRule.kt"): model,
        os.path.join(KOTLIN_PKG, "data/db/ProviderThinkingRuleEntity.kt"): entity,
        os.path.join(KOTLIN_PKG, "provider/thinking/ThinkingRuleCoding.kt"): mapping,
    }


I18N_CLEAN_KT = (
    "package com.rikkaminis.app\n"
    "val title = R.string.app_name\n"
)

I18N_DIRTY_KT = (
    "package com.rikkaminis.app\n"
    "val title = R.string.app_name\n"
    "val orphan = R.string.never_defined_key\n"
)

I18N_STRINGS = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    "<resources>\n"
    '    <string name="app_name">Minis</string>\n'
    "</resources>\n"
)


def enum_fixture(dirty=False):
    guarded = (
        "package com.rikkaminis.app\n"
        "fun a(s: String) {\n"
        "    val x = runCatching { Foo.valueOf(s) }.getOrNull()\n"
        "}\n"
        "fun b(s: String) {\n"
        "    try {\n"
        "        val y = Bar.valueOf(s)\n"
        "    } catch (e: IllegalArgumentException) {\n"
        "        return\n"
        "    }\n"
        "}\n"
    )
    bare = (
        "package com.rikkaminis.app\n"
        "fun c(s: String) {\n"
        "    val z = Baz.valueOf(s)\n"
        "}\n" if dirty else ""
    )
    return {
        os.path.join(KOTLIN_PKG, "EnumUsers.kt"): guarded + bare,
    }


def boundary_fixture(dirty=False):
    worker = (
        "package com.rikkaminis.app.sandbox.offload\n"
        "class ModelExecutionService {\n"
        "    fun run() {\n"
        "        provider.streamMessage(request) {}\n"
        "        provider.sendMessage(request) {}\n"
        "    }\n"
        "}\n"
    )
    caller = (
        "package com.rikkaminis.app.ui.chat\n"
        "class ChatViewModel {\n"
        "    fun send() {\n"
        "        viewModel.sendMessage(msg)\n"
        "        // provider.sendMessage(commented) — comments are skipped\n"
        + ("        provider.sendMessage(userMessage)\n" if dirty else "")
        + "    }\n"
        "}\n"
    )
    return {
        os.path.join(KOTLIN_PKG, "sandbox/offload/ModelExecutionService.kt"): worker,
        os.path.join(KOTLIN_PKG, "ui/chat/ChatViewModel.kt"): caller,
    }


def legacy_fixture(dirty=False, escaped=False):
    """The 2026-09-13 incident class: a live file reaching into the dead
    pre-aggregate pipeline (cold-open prewarm's always-empty source list)."""
    legacy = (
        "package com.rikkaminis.app.ui.chat.legacy\n"
        "internal class StableChatRowLedger {\n"
        "    fun record() {}\n"
        "}\n"
    )
    body = "        val ledger: Any? = null\n"
    if dirty:
        body = "        StableChatRowLedger().record()\n"
    elif escaped:
        body = (
            "        // legacy-ok: single-point source function for both pipelines\n"
            "        StableChatRowLedger().record()\n"
            "\n"
            "        val unrelated = 1\n"
        )
    consumer = (
        "package com.rikkaminis.app.ui.chat\n"
        "class ChatViewModel {\n"
        "    fun send() {\n"
        "        // StableChatRowLedger mentioned in a comment — comments are skipped\n"
        + body
        + "    }\n"
        "}\n"
    )
    return {
        os.path.join(KOTLIN_PKG, "ui/chat/legacy/StableChatRowLedger.kt"): legacy,
        os.path.join(KOTLIN_PKG, "ui/chat/ChatViewModel.kt"): consumer,
    }


# ─────────────────────────────────────────────────────────────────────────────
# Cases
# ─────────────────────────────────────────────────────────────────────────────

def test_four_way():
    print("━━━ four_way_sync_check ━━━")
    # Clean — all four layers agree.
    root = make_tree(four_way_fixture())
    code, out = run_scanner("four_way_sync_check.py", root)
    check("clean fixture exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty A — model field missing in Entity (the GH#68 class: saved nowhere).
    root = make_tree(four_way_fixture(model_extra="    val pinned: Boolean = false,\n"))
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "model-extra-field caught (exit 1, 'Entity missing')",
        code == 1 and "Entity missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty B — Entity field not passed in toSnapshot (lost on every save).
    root = make_tree(four_way_fixture(entity_extra="    val pinned: Int = 0,\n"))
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "toSnapshot-gap caught (exit 1, 'toSnapshot missing')",
        code == 1 and "toSnapshot missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty C — model field not restored in toProviderConfig (lost on load).
    root = make_tree(
        four_way_fixture(
            model_extra="    val pinned: Boolean = false,\n",
            entity_extra="    val pinned: Int = 0,\n",
            snap_extra="            pinned = if (inst.pinned) 1 else 0,\n",
        )
    )
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "toProviderConfig-gap caught (exit 1, 'toProviderConfig missing')",
        code == 1 and "toProviderConfig missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_four_way_derived():
    print("━━━ four_way_sync_check v2 (derived families) ━━━")

    # (a) Non-hardcoded family with a single-side extra field → must be RED.
    root = make_tree(derived_widget_fixture(model_extra="    val pinned: Boolean = false,\n"))
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "derived non-hardcoded family caught (exit 1, 'Entity missing')",
        code == 1 and "Entity missing" in out and "Widget" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # (b) Alias normalization: model/ctor arg names differ from entity
    # columns by the real FIELD_ALIASES → must NOT be flagged (GREEN).
    root = make_tree(derived_alias_fixture())
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "alias-normalized derived family green (exit 0)",
        code == 0 and "ModelEntry — four-way sync check" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # (c) Entity with no model class → UNRESOLVED (visible), exit 0.
    root = make_tree(derived_orphan_fixture())
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "orphan entity → UNRESOLVED + exit 0",
        code == 0 and "UNRESOLVED" in out and "OrphanEntity" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # (d) KNOWN_MISMATCH pin: pinned fields → WARNING lines + exit 0.
    root = make_tree(derived_pinned_fixture())
    code, out = run_scanner("four_way_sync_check.py", root)
    warns = out.count("KNOWN-MISMATCH (pinned, by design)")
    check(
        "KNOWN_MISMATCH pin → WARNING lines + exit 0",
        code == 0 and warns >= 4 and "ThinkingRule — four-way sync check" in out,
        f"exit={code} warns={warns}\n{out}",
    )
    shutil.rmtree(root)


# ── v2.1 entity-self fixtures: positional branch + defaulted regex ────────
# [review G2] The positional branch reads defaulted fields via
# extract_defaulted_fields; a bare `[^=]*` ran across lines and swallowed the
# NEXT field's `=`, mis-flagging the previous field ('key' instead of 'value').

POSITIONAL_CTRL = (
    'package com.rikkaminis.app.data.db\n'
    'data class ProviderConfigMetaEntity(\n'
    '    val key: String,\n'
    '    val value: String,\n'
    ')\n'
)
POSITIONAL_DEFT = (
    'package com.rikkaminis.app.data.db\n'
    'data class ProviderConfigMetaEntity(\n'
    '    val key: String,\n'
    '    val value: String = "x",\n'
    ')\n'
)
POSITIONAL_CALL = (
    'package com.rikkaminis.app.data.repository\n'
    'import com.rikkaminis.app.data.db.ProviderConfigMetaEntity\n'
    'fun w() = ProviderConfigMetaEntity("k")\n'
)


def test_four_way_entity_self():
    print("━━━ four_way_sync_check v2.1 (entity-self positional branch) ━━━")

    # (a) Positional entity, no defaults → GREEN.
    root = make_tree({
        os.path.join(KOTLIN_PKG, "data/db/ProviderConfigMetaEntity.kt"): POSITIONAL_CTRL,
        os.path.join(KOTLIN_PKG, "data/repository/W.kt"): POSITIONAL_CALL,
    })
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "entity-self positional no-default green (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # (b) Positional entity with a defaulted field → RED naming the defaulted
    # column ('value'; the old cross-line regex mis-flagged 'key').
    root = make_tree({
        os.path.join(KOTLIN_PKG, "data/db/ProviderConfigMetaEntity.kt"): POSITIONAL_DEFT,
        os.path.join(KOTLIN_PKG, "data/repository/W.kt"): POSITIONAL_CALL,
    })
    code, out = run_scanner("four_way_sync_check.py", root)
    check(
        "entity-self defaulted field caught (exit 1, names 'value')",
        code == 1 and "['value']" in out and "['key']" not in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_i18n():
    print("━━━ i18n_check ━━━")
    files = {
        os.path.join(RES_VALUES, "strings.xml"): I18N_STRINGS,
        os.path.join(KOTLIN_PKG, "Ui.kt"): I18N_CLEAN_KT,
    }
    root = make_tree(files)
    code, out = run_scanner("i18n_check.py", root)
    check("clean fixture exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    files[os.path.join(KOTLIN_PKG, "Ui.kt")] = I18N_DIRTY_KT
    root = make_tree(files)
    code, out = run_scanner("i18n_check.py", root)
    check(
        "orphan R.string key caught (exit 1)",
        code == 1 and "never_defined_key" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Missing translation is WARNING-only (legacy locales) — must not fail.
    files[os.path.join(KOTLIN_PKG, "Ui.kt")] = I18N_CLEAN_KT
    files[os.path.join(os.path.dirname(RES_VALUES), "values-de", "strings.xml")] = (
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    )
    root = make_tree(files)
    code, out = run_scanner("i18n_check.py", root)
    check(
        "missing translation stays warning-only (exit 0)",
        code == 0 and ("WARNING" in out or "warn" in out.lower()),
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Split resource files: Android allows several strings*.xml per res folder
    # and this repo uses one (values/strings_db_guard.xml) so the
    # database-downgrade guidance strings do not have to touch the shared
    # strings.xml. A scanner that only reads strings.xml calls those keys
    # orphans and fails the build for resources that exist.
    files[os.path.join(KOTLIN_PKG, "Ui.kt")] = (
        "package com.rikkaminis.app\n"
        "val title = R.string.app_name\n"
        "val hint = R.string.newer_db_title\n"
    )
    files[os.path.join(RES_VALUES, "strings_db_guard.xml")] = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <string name="newer_db_title">Your data is from a newer version</string>\n'
        "</resources>\n"
    )
    root = make_tree(files)
    code, out = run_scanner("i18n_check.py", root)
    check(
        "key defined in a split strings file is NOT an orphan (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_enum_parse():
    print("━━━ enum_parse_safety_check ━━━")
    root = make_tree(enum_fixture(dirty=False))
    code, out = run_scanner("enum_parse_safety_check.py", root)
    check(
        "guarded valueOf (runCatching + multi-line try) exits 0",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    root = make_tree(enum_fixture(dirty=True))
    code, out = run_scanner("enum_parse_safety_check.py", root)
    check(
        "bare valueOf caught (exit 1)",
        code == 1 and "Baz.valueOf" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_boundary():
    print("━━━ provider_boundary_guard ━━━")
    root = make_tree(boundary_fixture(dirty=False))
    code, out = run_scanner("provider_boundary_guard.py", root)
    check(
        "worker calls + comment lines are legal (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    root = make_tree(boundary_fixture(dirty=True))
    code, out = run_scanner("provider_boundary_guard.py", root)
    check(
        "app-process provider.sendMessage caught (exit 1)",
        code == 1 and "ChatViewModel.kt" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_legacy():
    print("━━━ legacy_pipeline_guard ━━━")
    # Clean — the dead symbol is only mentioned in a comment.
    root = make_tree(legacy_fixture())
    code, out = run_scanner("legacy_pipeline_guard.py", root)
    check(
        "comment-only mention is legal (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty — a live file consumes the runtime-dead symbol (fails silently in
    # production; must fail loudly here).
    root = make_tree(legacy_fixture(dirty=True))
    code, out = run_scanner("legacy_pipeline_guard.py", root)
    check(
        "live caller of legacy symbol caught (exit 1)",
        code == 1 and "ChatViewModel.kt" in out and "StableChatRowLedger" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Escape hatch — `legacy-ok:` justifies the following block (and the
    # justification must survive comment stripping; a blank line ends it).
    root = make_tree(legacy_fixture(escaped=True))
    code, out = run_scanner("legacy_pipeline_guard.py", root)
    check(
        "legacy-ok escape hatch exempts the block (exit 0)",
        code == 0,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_debug_leak():
    print("━━━ debug_leak_guard (source mode) ━━━")
    debug_cls = (
        "package com.rikkaminis.app.debug\n"
        "class DebugServer\n"
    )
    # Clean — the debug class is allow-listed (MinisApp) / comment-only.
    clean = {
        os.path.join(KOTLIN_PKG, "debug/DebugServer.kt"): debug_cls,
        os.path.join(KOTLIN_PKG, "MinisApp.kt"):
            "if (BuildConfig.DEBUG) { com.rikkaminis.app.debug.DebugServer(this).start() }\n",
        os.path.join(KOTLIN_PKG, "Other.kt"):
            "// mentions com.rikkaminis.app.debug.DebugServer in a comment only\n",
    }
    root = make_tree(clean)
    code, out = run_scanner("debug_leak_guard.py", root)
    check("allow-listed call site + comment-only mention is legal (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty — a NEW unaudited file touches debug code (the M1 failure class:
    # ships the token-free loopback debug server into release with no other
    # gate noticing).
    dirty = dict(clean)
    dirty[os.path.join(KOTLIN_PKG, "SomeNewFile.kt")] = (
        "val x = com.rikkaminis.app.debug.DebugServer()\n"
    )
    root = make_tree(dirty)
    code, out = run_scanner("debug_leak_guard.py", root)
    check(
        "unaudited debug-package call site caught (exit 1)",
        code == 1 and "SomeNewFile.kt" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Escape hatch — `debug-ok:` justifies the reference.
    escaped = dict(clean)
    escaped[os.path.join(KOTLIN_PKG, "SomeNewFile.kt")] = (
        "// debug-ok: audited single-point entry, guarded at the caller\n"
        "val x = com.rikkaminis.app.debug.DebugServer()\n"
    )
    root = make_tree(escaped)
    code, out = run_scanner("debug_leak_guard.py", root)
    check("debug-ok escape hatch exempts the reference (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

def test_room_migration():
    print("━━━ room_migration_check ━━━")
    code, out = run_scanner("room_migration_check.py", "--self-test")
    check(
        "self-test fixtures pass (unwired + non-contiguous caught, clean passes)",
        code == 0,
        f"exit={code}\n{out}",
    )

def test_process_logging():
    print("━━━ process_logging_gate ━━━")
    code, out = run_scanner("process_logging_gate.py", "--self-test")
    check(
        "self-test fixtures pass (unwired process caught, wired/escaped pass)",
        code == 0,
        f"exit={code}\n{out}",
    )
    # Real-tree shape: a process declared with the escape comment ABOVE the
    # tag (XML forbids comments inside a start tag) must still pass.
    root = make_tree({
        "src/android/app/src/main/AndroidManifest.xml": (
            "<manifest>\n"
            "  <!-- logging-ok: dormant, no request path -->\n"
            '  <service android:process=":sleepyservice" />\n'
            "</manifest>\n"
        ),
    })
    code, out = run_scanner("process_logging_gate.py", root)
    check("escape comment above the tag passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)


def test_trace_eval():
    print("━━━ trace_eval_check ━━━")
    # The evaluator gates every golden in tests/traces/golden/, including the
    # fault goldens — a silently broken assertion there would let a removed
    # guard ship green. Fixtures below are the ground truth for its checks.

    clean_case = {"tools": ["file_write"], "terminal_state": "Succeeded", "all_tools_succeed": True}
    clean_lines = [
        {"type": "trace_start"},
        {"type": "turn_start", "turn": 0},
        {"type": "tool_call", "turn": 0, "tool": "file_write"},
        {"type": "tool_result", "turn": 0, "tool": "file_write", "success": True, "output": "ok"},
        {"type": "trace_end", "terminal_state": "Succeeded", "terminal_reason": "completed_normally"},
    ]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps({"trace": "tests/traces/golden/traces/t.jsonl", "expect": clean_case}),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in clean_lines),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check("clean golden exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty A — tool sequence drift (the truncated-call regression shape:
    # the refused call executed after all, so the sequence grew by one).
    lines = clean_lines[:2] + [
        {"type": "tool_call", "turn": 0, "tool": "file_write"},
        {"type": "tool_result", "turn": 0, "tool": "file_write", "success": False, "output": "invalid JSON"},
    ] + clean_lines[2:]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps({"trace": "tests/traces/golden/traces/t.jsonl", "expect": clean_case}),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in lines),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "tool-sequence drift caught (exit 1)",
        code == 1 and "tool sequence mismatch" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty B — some_tool_failed not honoured: every result succeeded, i.e. the
    # offload guard was removed and the stub payload got written.
    all_ok = clean_lines
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps(
            {"trace": "tests/traces/golden/traces/t.jsonl", "expect": {"some_tool_failed": True}}
        ),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in all_ok),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "some_tool_failed with all-succeeded results caught (exit 1)",
        code == 1 and "every tool_result succeeded" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # ...and the positive direction of the same assertion.
    with_failure = clean_lines[:-1] + [
        {"type": "tool_result", "turn": 0, "tool": "file_write", "success": False, "output": "refused"},
    ] + clean_lines[-1:]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps(
            {"trace": "tests/traces/golden/traces/t.jsonl", "expect": {"some_tool_failed": True}}
        ),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in with_failure),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check("some_tool_failed satisfied by a failed result (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty C — run never finalized.
    no_end = [l for l in clean_lines if l["type"] != "trace_end"]
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps({"trace": "tests/traces/golden/traces/t.jsonl", "expect": clean_case}),
        "tests/traces/golden/traces/t.jsonl": "".join(json.dumps(l) + "\n" for l in no_end),
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "missing trace_end caught (exit 1)",
        code == 1 and "no trace_end event" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Dirty D — golden pointing at a trace that does not exist (a renamed or
    # never-committed .jsonl must fail loudly, not silently skip).
    root = make_tree({
        "tests/traces/golden/case.json": json.dumps(
            {"trace": "tests/traces/golden/traces/missing.jsonl", "expect": clean_case}
        )
    })
    code, out = run_scanner("trace_eval_check.py", root)
    check(
        "missing trace file caught (exit 1)",
        code == 1 and "trace file missing" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)


def test_extra_days_container():
    print("━━━ extra_days_container_guard ━━━")
    # Ground truth: the guard must accept arrayListOf and reject BOTH wrong
    # containers -- int[] and Kotlin's listOf() (java.util.Arrays$ArrayList).
    # The defect it catches is silent: the framework turns the ClassCastException
    # into a null and the alarm replays as a one-shot.
    good = make_tree({
        "src/MinisApp.kt": (
            "class M {\n"
            "    private fun daysForRepeatMode(r: String): ArrayList<Int>? =\n"
            "        when (r) { \"DAILY\" -> arrayListOf(1, 2, 3, 4, 5, 6, 7); else -> null }\n"
            "    fun f() { daysForRepeatMode(\"DAILY\")?.let {\n"
            "        putExtra(android.provider.AlarmClock.EXTRA_DAYS, it) } }\n"
            "}\n"
        ),
        "src/Handler.kt": (
            "class H {\n"
            "    fun g(m: Int) { when (m) {\n"
            "        1 -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2))\n"
            "        else -> putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2))\n"
            "    } }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("extra_days_container_guard.py", good)
    check("ArrayList writers pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    for label, ret, value in (
        ("int[]", "IntArray?", "intArrayOf(1, 2, 3, 4, 5, 6, 7)"),
        ("listOf", "List<Int>?", "listOf(1, 2, 3, 4, 5, 6, 7)"),
    ):
        bad = make_tree({
            "src/MinisApp.kt": (
                "class M {\n"
                f"    private fun daysForRepeatMode(r: String): {ret} =\n"
                f"        when (r) {{ \"DAILY\" -> {value}; else -> null }}\n"
                "    fun f() { daysForRepeatMode(\"DAILY\")?.let {\n"
                "        putExtra(android.provider.AlarmClock.EXTRA_DAYS, it) } }\n"
                "}\n"
            ),
            "src/Handler.kt": (
                "class H {\n"
                "    fun g() { putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2)) }\n"
                "}\n"
            ),
        })
        code, out = run_scanner("extra_days_container_guard.py", bad)
        check(f"{label} writer is caught (exit 1)", code == 1, f"exit={code}\n{out}")
        shutil.rmtree(bad)

def test_stale_closure():
    print("━━━ stale_closure_guard ━━━")
    # Ground truth: the guard must accept rememberUpdatedState and reject a
    # long-lived produceState whose closure reads a plain parameter while that
    # parameter is NOT in the key list. The defect it catches is silent: the
    # producer is never restarted, `snapshotFlow { plainParam }` observes
    # nothing, and the UI freezes at the first composed frame (2026-09-20).
    good = make_tree({
        KOTLIN_PKG + "/ui/Good.kt": (
            "@Composable\n"
            "private fun Good(content: String, isStreaming: Boolean) {\n"
            "    val latestContent by rememberUpdatedState(content)\n"
            "    val d by produceState(initialValue = content, isStreaming) {\n"
            "        snapshotFlow { latestContent }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
        # key 里含 content ⇒ 每次变化都重启闭包 ⇒ 安全
        KOTLIN_PKG + "/ui/Keyed.kt": (
            "@Composable\n"
            "private fun Keyed(content: String) {\n"
            "    val d by produceState(initialValue = content, content) {\n"
            "        snapshotFlow { content }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stale_closure_guard.py", good)
    check("rememberUpdatedState + keyed param pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    bad = make_tree({
        KOTLIN_PKG + "/ui/Bad.kt": (
            "@Composable\n"
            "private fun Bad(content: String, isStreaming: Boolean) {\n"
            "    val d by produceState(initialValue = content, isStreaming) {\n"
            "        snapshotFlow { content }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stale_closure_guard.py", bad)
    check("plain param without rememberUpdatedState is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(bad)

    # 逃生口：带 stale-ok 说明的必须放行
    escaped = make_tree({
        KOTLIN_PKG + "/ui/Esc.kt": (
            "@Composable\n"
            "private fun Esc(content: String) {\n"
            "    // stale-ok: content is immutable for the lifetime of this effect\n"
            "    val d by produceState(initialValue = content) {\n"
            "        snapshotFlow { content }.collect { value = it }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stale_closure_guard.py", escaped)
    check("stale-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(escaped)

def test_ime_inset():
    print("━━━ ime_inset_guard ━━━")
    # Ground truth: a text field hosted by a bare Scaffold with no imePadding is
    # invisible behind the keyboard (edge-to-edge + adjustResize = the window is
    # NOT resized for the IME). The guard must flag that, accept the fixed shape,
    # and honour both escape hatches. Two false negatives found while building
    # this guard are pinned here so they cannot regress:
    #   - a trailing-lambda `Scaffold { }` (no parens) must count as a host;
    #   - a `"*/*"` string literal must not swallow the rest of the file.
    good = make_tree({
        KOTLIN_PKG + "/ui/Good.kt": (
            "@Composable\n"
            "private fun Good() {\n"
            "    Scaffold { padding ->\n"
            "        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {\n"
            "            OutlinedTextField(value = \"\", onValueChange = {})\n"
            "        }\n"
            "    }\n"
            "}\n"
        ),
        # 保护宿主（SettingsScaffold）⇒ 不在判据内
        KOTLIN_PKG + "/ui/Protected.kt": (
            "@Composable\n"
            "private fun Protected() {\n"
            "    SettingsScaffold(title = \"x\") {\n"
            "        OutlinedTextField(value = \"\", onValueChange = {})\n"
            "    }\n"
            "}\n"
        ),
        # 没有文本输入点的裸 Scaffold ⇒ 不该报
        KOTLIN_PKG + "/ui/NoField.kt": (
            "@Composable\n"
            "private fun NoField() {\n"
            "    Scaffold { padding -> Text(\"hi\") }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", good)
    check("fixed + protected + fieldless pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    bad = make_tree({
        KOTLIN_PKG + "/ui/Bad.kt": (
            "@Composable\n"
            "private fun Bad() {\n"
            "    Scaffold { padding ->\n"
            "        Column(Modifier.fillMaxSize().padding(padding)) {\n"
            "            OutlinedTextField(value = \"\", onValueChange = {})\n"
            "        }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", bad)
    check("bare Scaffold + field, no imePadding is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(bad)

    # 假阴性回归 1：`Scaffold {` 尾随 lambda 形态必须被识别为宿主
    trailing = make_tree({
        KOTLIN_PKG + "/ui/Trailing.kt": (
            "@Composable\n"
            "private fun Trailing() {\n"
            "    Scaffold { padding ->\n"
            "        OutlinedTextField(value = \"\", onValueChange = {})\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", trailing)
    check("trailing-lambda `Scaffold {` counts as host (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(trailing)

    # 假阴性回归 2：文件中的 "*/*" 字符串不得吞掉后续代码
    star = make_tree({
        KOTLIN_PKG + "/ui/Star.kt": (
            "@Composable\n"
            "private fun Star() {\n"
            "    val launcher = remember { \"\" }\n"
            "    Button(onClick = { launcher.launch(\"*/*\") }) { Text(\"pick\") }\n"
            "}\n"
            "\n"
            "@Composable\n"
            "private fun Later() {\n"
            "    Scaffold { padding ->\n"
            "        OutlinedTextField(value = \"\", onValueChange = {})\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", star)
    check("\"*/*\" literal does not swallow later functions (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(star)

    # 逃生口 1：ime-ok
    ok_esc = make_tree({
        KOTLIN_PKG + "/ui/OkEsc.kt": (
            "@Composable\n"
            "private fun OkEsc() {\n"
            "    Scaffold { padding ->\n"
            "        AlertDialog(onDismissRequest = {}) {\n"
            "            // ime-ok: independent Window, platform pans to the field\n"
            "            OutlinedTextField(value = \"\", onValueChange = {})\n"
            "        }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", ok_esc)
    check("ime-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(ok_esc)

    # 逃生口 2：ime-unreachable
    un_esc = make_tree({
        KOTLIN_PKG + "/ui/UnEsc.kt": (
            "@Composable\n"
            "private fun UnEsc() {\n"
            "    Scaffold { padding -> Text(\"list\") }\n"
            "    // ime-unreachable: the field lives in the sheet branch below\n"
            "    Sheet { OutlinedTextField(value = \"\", onValueChange = {}) }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("ime_inset_guard.py", un_esc)
    check("ime-unreachable escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(un_esc)

def test_stream_flow_dispatch():
    print("━━━ stream_flow_dispatch_guard ━━━")
    # Ground truth: a callbackFlow whose body blocks must carry a flowOn, or the
    # producer runs on the COLLECTOR's dispatcher and starves every timer on it
    # — including the flow's own watchdogs. The 2026-09-20 hang: the
    # :modelservice collector is `runBlocking` (no dispatcher), so the TTFB
    # watchdog, the first-data watchdog and the worker's outer timeout all
    # became unreachable at once and a 1.6MB request sat 272s with no byte back.
    good = make_tree({
        KOTLIN_PKG + "/provider/Good.kt": (
            "fun goodStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))\n"
            "    val call = client.newCall(req)\n"
            "    val resp = call.execute()\n"
            "    while (reader.readLine() != null) { }\n"
            "    awaitClose { call.cancel() }\n"
            "}.flowOn(Dispatchers.IO)\n"
        ),
        # 链上还有别的 operator，flowOn 不在第一个位置 —— 也算放行
        KOTLIN_PKG + "/provider/Chained.kt": (
            "fun chainedStream(): Flow<String> = channelFlow<Int> {\n"
            "    Thread.sleep(1)\n"
            "}.flowOn(Dispatchers.IO).map { it.toString() }\n"
        ),
        # 体内没有阻塞调用 ⇒ 不归本门管
        KOTLIN_PKG + "/provider/NonBlocking.kt": (
            "fun pureStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    trySend(1)\n"
            "    awaitClose { }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", good)
    check("dispatched / non-blocking flows pass (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(good)

    # 负例：体内阻塞且链上无 flowOn ⇒ 必须翻红
    bad = make_tree({
        KOTLIN_PKG + "/provider/Bad.kt": (
            "fun badStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    val call = client.newCall(req)\n"
            "    val resp = call.execute()\n"
            "    while (reader.readLine() != null) { }\n"
            "    awaitClose { call.cancel() }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", bad)
    check("blocking cold flow without flowOn is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(bad)

    # flowOn(Dispatchers.Main) 只是把阻塞搬到 UI 线程，同样要翻红
    main_disp = make_tree({
        KOTLIN_PKG + "/provider/MainDisp.kt": (
            "fun mainStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    val resp = call.execute()\n"
            "    awaitClose { }\n"
            "}.flowOn(Dispatchers.Main)\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", main_disp)
    check("flowOn(Dispatchers.Main) is still a violation (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(main_disp)

    # ★ 回归守卫：注释里的花括号/关键字不得干扰作用域配平。
    # 首版判据在这里翻车 —— 我加的解释性注释里写了 `// runBlocking { ... }`，
    # 行注释未掩码 ⇒ 花括号计数失衡 ⇒ match_brace 冲过真正的 } 一路吞掉文件后半段，
    # 反向对照时多报 11 处无关命中（含注释自身）。这个 fixture 把该 bug 钉死。
    comment_braces = make_tree({
        KOTLIN_PKG + "/provider/CommentBrace.kt": (
            "fun commented(): Flow<Int> = callbackFlow<Int> {\n"
            "    // the collector's `runBlocking { ... }` has no dispatcher\n"
            "    // see https://example.com/docs/flow {not a brace}\n"
            "    val resp = call.execute()\n"
            "    awaitClose { }\n"
            "}.flowOn(Dispatchers.IO)\n"
            "\n"
            "fun unrelated(): Int {\n"
            "    val s = \"https://example.com/a//b\"\n"
            "    return 1\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", comment_braces)
    check("comment/string braces do not break scoping (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(comment_braces)

    # 逃生口：带 stream-ok 说明的必须放行
    escaped = make_tree({
        KOTLIN_PKG + "/provider/Esc.kt": (
            "fun escStream(): Flow<Int> = callbackFlow<Int> {\n"
            "    // stream-ok: body runs on a dedicated single-thread executor\n"
            "    val resp = call.execute()\n"
            "    awaitClose { }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("stream_flow_dispatch_guard.py", escaped)
    check("stream-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(escaped)


def test_prefs_listener_holder():
    print("━━━ prefs_listener_holder_guard ━━━")
    tmp = tempfile.mkdtemp()
    good = os.path.join(tmp, "good")
    os.makedirs(good)

    # Positive: named field holder (the F-240 shape).
    with open(os.path.join(good, "Good.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
class Good(private val prefs: SharedPreferences) {
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    init { prefs.registerOnSharedPreferenceChangeListener(prefsListener) }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", good)
    check("named field holder passes (exit 0)", code == 0, f"exit={code}\n{out}")

    # Negative A: the exact hazard -- trailing-lambda call (NO parentheses).
    # This is the shape that shipped in ConfigBuiltins.kt:152, and the shape an
    # earlier version of this guard silently skipped.
    bad = os.path.join(tmp, "bad")
    os.makedirs(bad)
    with open(os.path.join(bad, "Bad.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Bad {
    fun reg(prefs: SharedPreferences) {
        prefs.registerOnSharedPreferenceChangeListener { _, _ -> refresh() }
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", bad)
    check("trailing-lambda inline hazard fails (exit != 0)", code != 0, f"exit={code}\n{out}")
    check("hazard is reported as INLINE", "INLINE" in out, out[:400])

    # Negative B: parenthesised anonymous-listener argument, also unheld.
    bad2 = os.path.join(tmp, "bad2")
    os.makedirs(bad2)
    with open(os.path.join(bad2, "Bad2.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Bad2 {
    fun reg(prefs: SharedPreferences) {
        prefs.registerOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() })
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", bad2)
    check("parenthesised anonymous listener fails (exit != 0)", code != 0, f"exit={code}\n{out}")

    # Negative C: named local that is never stored anywhere -- looks held to a
    # naive reader, but nothing keeps it alive past the frame.
    bad3 = os.path.join(tmp, "bad3")
    os.makedirs(bad3)
    with open(os.path.join(bad3, "Bad3.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Bad3 {
    fun reg(prefs: SharedPreferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", bad3)
    check("unheld named local fails (exit != 0)", code != 0, f"exit={code}\n{out}")

    # Positive: named local that IS stored into a field.
    good2 = os.path.join(tmp, "good2")
    os.makedirs(good2)
    with open(os.path.join(good2, "Good2.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Good2 {
    @Volatile private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    fun reg(prefs: SharedPreferences) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", good2)
    check("local stored into a field passes (exit 0)", code == 0, f"exit={code}\n{out}")

    # Escape hatch.
    esc = os.path.join(tmp, "esc")
    os.makedirs(esc)
    with open(os.path.join(esc, "Esc.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
object Esc {
    fun reg(prefs: SharedPreferences) {
        // prefs-listener-ok: held by the caller for the screen's lifetime
        prefs.registerOnSharedPreferenceChangeListener { _, _ -> refresh() }
    }
    private fun refresh() {}
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", esc)
    check("prefs-listener-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")

    # Regression fixtures for the scope-classification bug found 2026-09-21.
    # The guard fused `@Composable` + `fun` across the newline into one token,
    # `\bfun\b` stopped matching, and the function body was classified as a
    # class -- so every local `val listener` inside it looked like a field and
    # reported `held`. Four real sites (ChatScreen, AppearanceScreen,
    # ChatMenuSettingsScreen, MemoryManagementScreen) got that false pass, and
    # deleting their actual holder still passed. These fixtures are the ground
    # truth: both shapes MUST fail when unheld.
    for label, sig in (
        ("annotated multi-line signature",
         "@Composable\nfun Screen(\n    prefs: SharedPreferences,\n    context: Context,\n) {"),
        ("annotated single-line signature",
         "@Composable\nfun Screen(prefs: SharedPreferences, context: Context) {"),
        ("unannotated multi-line signature",
         "fun reg(\n    prefs: SharedPreferences,\n) {"),
    ):
        d = os.path.join(tmp, "shape_" + label.replace(" ", "_"))
        os.makedirs(d)
        with open(os.path.join(d, "Shape.kt"), "w") as f:
            f.write(f"""
import android.content.SharedPreferences
object Shape {{
    {sig}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener {{ _, _ -> refresh() }}
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }}
    private fun refresh() {{}}
}}
""")
        code, out = run_scanner("prefs_listener_holder_guard.py", d)
        check(f"unheld local with {label} fails (exit != 0)", code != 0, f"exit={code}\n{out}")

    # And the DisposableEffect shape these four sites actually use MUST pass --
    # otherwise the fixtures above could be satisfied by a guard that just
    # reports everything as unheld.
    disp = os.path.join(tmp, "dispose_shape")
    os.makedirs(disp)
    with open(os.path.join(disp, "Disp.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
@Composable
fun rememberThing(
    context: Context,
) {
    val prefs = ChatMenuPrefs.prefs(context)
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", disp)
    check("onDispose-held listener in annotated multi-line fn passes (exit 0)",
          code == 0, f"exit={code}\n{out}")

    # Mutation: remove the onDispose holder -> must now FAIL. This is the exact
    # edit that used to stay green.
    mut = os.path.join(tmp, "dispose_mutated")
    os.makedirs(mut)
    with open(os.path.join(mut, "Mut.kt"), "w") as f:
        f.write("""
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
@Composable
fun rememberThing(
    context: Context,
) {
    val prefs = ChatMenuPrefs.prefs(context)
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
}
""")
    code, out = run_scanner("prefs_listener_holder_guard.py", mut)
    check("removing the onDispose holder fails (exit != 0)", code != 0, f"exit={code}\n{out}")
    shutil.rmtree(tmp)

def test_claim_release():
    print("━━━ claim_release_guard ━━━")
    # Ground truth = the actual 2026-10-12 regression shape: a claim that
    # flips the flag BEFORE publishing the marker its release gate compares.
    # Check B must catch the pre-flip order and accept the post-flip order.
    base = KOTLIN_PKG + "/ui/chat/"
    claim_good = make_tree({
        base + "Good.kt": (
            "package com.rikkaminis.app.ui.chat\n"
            "class Good {\n"
            "    var streamingClaimToken: Any = Any()\n"
            "    var streamJob: Job? = null\n"
            "    fun send() {\n"
            "        streamingClaimToken = Any()\n"
            "        _isStreaming.value = true\n"
            "        val claimToken = streamingClaimToken\n"
            "        streamJob = launch {\n"
            "            try { loop() } finally {\n"
            "                if (claimToken === streamingClaimToken) { _isStreaming.value = false }\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "    fun sendSecond() {\n"
            "        streamingClaimToken = Any()\n"
            "        _isStreaming.value = true\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("claim_release_guard.py", claim_good)
    check("claim after mint passes strict mode (exit 0)", code == 0,
          f"exit={code}\n{out}")
    shutil.rmtree(claim_good)

    claim_bad = make_tree({
        base + "Bad.kt": (
            "package com.rikkaminis.app.ui.chat\n"
            "class Bad {\n"
            "    var streamingClaimToken: Any = Any()\n"
            "    fun send() {\n"
            "        _isStreaming.value = true\n"
            "        streamingClaimToken = Any()\n"
            "        val claimToken = streamingClaimToken\n"
            "        launch {} // uses claimToken so the family pairs up\n"
            "        if (claimToken === streamingClaimToken) { _isStreaming.value = false }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("claim_release_guard.py", claim_bad)
    check("pre-flip claim (the 10-12 shape) is caught (exit 1)", code == 1,
          f"exit={code}\n{out}")
    check("finding names the after-claim mint", "AFTER the claim" in out, out)
    shutil.rmtree(claim_bad)

    escaped_tree = make_tree({
        base + "Esc.kt": (
            "package com.rikkaminis.app.ui.chat\n"
            "class Esc {\n"
            "    var streamingClaimToken: Any = Any()\n"
            "    fun send() {\n"
            "        // claim-ok: pre-existing turn, marker minted by caller\n"
            "        _isStreaming.value = true\n"
            "        streamingClaimToken = Any()\n"
            "        val claimToken = streamingClaimToken\n"
            "        launch {}\n"
            "        if (claimToken === streamingClaimToken) { _isStreaming.value = false }\n"
            "    }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("claim_release_guard.py", escaped_tree)
    check("claim-ok escape hatch passes (exit 0)", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(escaped_tree)

    # Check A: ungated sibling next to a gated one must be reported.
    sibling_bad = make_tree({
        base + "Sib.kt": (
            "package com.rikkaminis.app.ui.chat\n"
            "class Sib {\n"
            "    var streamEpoch = 0\n"
            "    var curEpoch = 0\n"
            "    fun a() { if (streamEpoch === curEpoch) { _isStreaming.value = false } }\n"
            "    fun b() { _isStreaming.value = false }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("claim_release_guard.py", sibling_bad)
    check("ungated sibling release is caught (exit 1)", code == 1,
          f"exit={code}\n{out}")
    shutil.rmtree(sibling_bad)

    # Take-then-clear hand-off must NOT be reported.
    handoff_ok = make_tree({
        base + "Hand.kt": (
            "package com.rikkaminis.app.ui.chat\n"
            "class Hand {\n"
            "    var pendingContinuation: Any? = null\n"
            "    var gateEpoch = 0\n"
            "    var curGate = 0\n"
            "    fun respond() {\n"
            "        val cont = pendingContinuation ?: return\n"
            "        pendingContinuation = null\n"
            "        _pendingRequest.value = null\n"
            "    }\n"
            "    fun timedOut() { if (gateEpoch === curGate) { pendingContinuation = null } }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("claim_release_guard.py", handoff_ok)
    check("atomic hand-off clears pass strict mode (exit 0)", code == 0,
          f"exit={code}\n{out}")
    shutil.rmtree(handoff_ok)

    # Report mode must never gate: same tree, exit 0.
    report_tree = make_tree({
        base + "Rep.kt": (
            "package com.rikkaminis.app.ui.chat\n"
            "class Rep {\n"
            "    var streamEpoch = 0\n"
            "    var curEpoch = 0\n"
            "    fun a() { if (streamEpoch === curEpoch) { _isStreaming.value = false } }\n"
            "    fun b() { _isStreaming.value = false }\n"
            "}\n"
        ),
    })
    code, out = run_scanner("claim_release_guard.py", report_tree, "--report")
    check("--report prints findings but exits 0", code == 0 and "REPORT-ONLY" in out,
          f"exit={code}\n{out}")
    shutil.rmtree(report_tree)


def test_liveness_static():
    print("━━━ liveness_static_guard ━━━")
    # Self-test fixtures (clean + the A/B/C violation classes).
    code, out = run_scanner("liveness_static_guard.py", "--self-test")
    check(
        "self-test fixtures pass (clean tree passes; A/B/C all caught)",
        code == 0,
        f"exit={code}\n{out}",
    )
    # Real-repo coverage comes from the test_real_repo() scanner list.


def loop_reset_fixture(shape):
    """Minimal repo tree for loop_counter_reset_guard.

    Ground truth = the 2026-10-15 audit2 BUG-01 shape: a numeric counter reset
    whose comment block claims tool-call semantics, placed at the brace depth
    of the `if (toolCalls.isEmpty())` header instead of inside a tool-call
    branch.
    """
    rel = KOTLIN_PKG + "/ui/chat/Loop.kt"
    head = (
        "package com.rikkaminis.app.ui.chat\n"
        "class LoopState { var lengthWallContinues = 0 }\n"
        "class Loop {\n"
        "    fun turn(loopState: LoopState, toolCalls: List<String>) {\n"
    )
    guard = (
        "        if (toolCalls.isEmpty()) {\n"
        "            break\n"
        "        }\n"
    )
    claim = (
        "        // [feat/hermes-tier1] A tool-call turn clears the length-wall\n"
        "        // continuation budget: the model recovered and is doing new work.\n"
    )
    reset = "        loopState.lengthWallContinues = 0\n"
    if shape == "good":
        body = (
            guard
            + "        if (toolCalls.isNotEmpty()) {\n"
            + claim + reset
            + "        }\n"
        )
    elif shape == "bad":
        # The regression: same depth as the isEmpty() header, guarded by nothing.
        body = guard + claim + reset
    elif shape == "no_claim":
        # A reset with no tool-call claim is out of this guard's remit.
        body = guard + "        loopState.lengthWallContinues = 0\n"
    else:
        raise AssertionError(shape)
    text = head + body + "    }\n}\n"
    line_no = text[: text.index(reset)].count("\n") + 1
    return {rel: text}, line_no


def test_loop_counter_reset():
    print("━━━ loop_counter_reset_guard ━━━")
    files, line_no = loop_reset_fixture("good")
    root = make_tree(files)
    code, out = run_scanner("loop_counter_reset_guard.py", root)
    check("reset inside a toolCalls.isNotEmpty() guard passes (exit 0)",
          code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    files, line_no = loop_reset_fixture("bad")
    root = make_tree(files)
    code, out = run_scanner("loop_counter_reset_guard.py", root)
    check("reset at the isEmpty() header depth is caught (exit 1)", code == 1,
          f"exit={code}\n{out}")
    check(f"finding names the exact file:line ({line_no})",
          f"Loop.kt:{line_no}" in out, out)
    check("finding explains the shared depth", "same depth as" in out, out)
    shutil.rmtree(root)

    files, _ = loop_reset_fixture("no_claim")
    root = make_tree(files)
    code, out = run_scanner("loop_counter_reset_guard.py", root)
    check("reset without a tool-call claim is out of remit (exit 0)",
          code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)


def budget_stop_fixture(shape):
    """Minimal repo tree for budget_stop_break_guard.

    Ground truth = the audit3 F-02 shape (2026-10-15): the agent loop's
    execution-budget gates sit inside the turn body's retry `while`, and their
    bare `break` left only the retry loop — the turn then fell through to the
    success tail (route Success for the refused member, empty assistant entry,
    burned turns against a FINALIZING reducer).
    """
    rel = KOTLIN_PKG + "/ui/chat/Loop.kt"
    head = (
        "package com.rikkaminis.app.ui.chat\n"
        "class Loop {\n"
        "    fun run(maxTurns: Int) {\n"
    )
    tail = "        }\n    }\n}\n"
    if shape == "good":
        outer, inner = "        turnLoop@ for (turn in 0 until maxTurns) {\n", "            while (!done) {\n"
        gate = (
            "                if (!budget.consumeAttempt()) {\n"
            '                    traceObserver.t7BudgetStopReason = "provider_attempt_limit"\n'
            "                    break@turnLoop\n"
            "                }\n"
        )
        closer, marker = "            }\n", "break@turnLoop"
    elif shape == "bad_bare_break":
        # The regression: same shape, but the break leaves only the while.
        outer, inner = "        turnLoop@ for (turn in 0 until maxTurns) {\n", "            while (!done) {\n"
        gate = (
            "                if (!budget.consumeAttempt()) {\n"
            '                    traceObserver.t7BudgetStopReason = "provider_attempt_limit"\n'
            "                    break\n"
            "                }\n"
        )
        closer, marker = "            }\n", "break"
    elif shape == "bad_unlabeled_loop":
        # Same, and the enclosing turn loop carries no label: nothing to break to.
        outer, inner = "        for (turn in 0 until maxTurns) {\n", "            while (!done) {\n"
        gate = (
            "                if (!budget.consumeAttempt()) {\n"
            '                    traceObserver.t7BudgetStopReason = "provider_attempt_limit"\n'
            "                    break\n"
            "                }\n"
        )
        closer, marker = "            }\n", "break"
    elif shape == "shallow_ok":
        # Turn-entry gate (no inner loop): a bare break already leaves the run —
        # this is the production deadline / turn_limit shape.
        outer, inner = "        turnLoop@ for (turn in 0 until maxTurns) {\n", ""
        gate = (
            "            if (budget.isExpired()) {\n"
            '                traceObserver.t7BudgetStopReason = "deadline_reached"\n'
            "                break\n"
            "            }\n"
        )
        closer, marker = "", "break"
    elif shape == "return_only":
        # The branch returns an advisory error instead of ending the run
        # (production: ChatPromptAndTools' tool_call_limit) — out of remit.
        outer, inner = "        turnLoop@ for (turn in 0 until maxTurns) {\n", "            while (!done) {\n"
        gate = (
            "                if (!budget.consumeToolCall()) {\n"
            '                    traceObserver.t7BudgetStopReason = "tool_call_limit"\n'
            '                    return "budget exhausted"\n'
            "                }\n"
        )
        closer, marker = "            }\n", "return"
    else:
        raise AssertionError(shape)
    text = head + outer + inner + gate + closer + tail
    line_no = text[: text.index(marker)].count("\n") + 1
    return {rel: text}, line_no


def test_budget_stop_break():
    print("━━━ budget_stop_break_guard ━━━")
    root = make_tree(budget_stop_fixture("good")[0])
    code, out = run_scanner("budget_stop_break_guard.py", root)
    check("break@turnLoop out of a nested budget gate passes (exit 0)",
          code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    files, line_no = budget_stop_fixture("bad_bare_break")
    root = make_tree(files)
    code, out = run_scanner("budget_stop_break_guard.py", root)
    check("bare break out of a nested budget gate is caught (exit 1)", code == 1,
          f"exit={code}\n{out}")
    check(f"finding names the exact file:line ({line_no})",
          f"Loop.kt:{line_no}" in out, out)
    check("finding names the required label", "break@turnLoop" in out, out)
    shutil.rmtree(root)

    files, line_no = budget_stop_fixture("bad_unlabeled_loop")
    root = make_tree(files)
    code, out = run_scanner("budget_stop_break_guard.py", root)
    check("nested break with an unlabeled enclosing loop is caught (exit 1)",
          code == 1, f"exit={code}\n{out}")
    check("finding explains the missing label",
          "carries no label" in out, out)
    shutil.rmtree(root)

    root = make_tree(budget_stop_fixture("shallow_ok")[0])
    code, out = run_scanner("budget_stop_break_guard.py", root)
    check("turn-entry gate at for-body depth may use a bare break (exit 0)",
          code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(budget_stop_fixture("return_only")[0])
    code, out = run_scanner("budget_stop_break_guard.py", root)
    check("a budget branch that returns instead of breaking is out of remit (exit 0)",
          code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)


def test_katex_bridge_arity():
    """The asset HTML and its Kotlin hosts must agree on JS-bridge arity."""
    print("━━━ katex_bridge_arity_check ━━━")

    def fixture(kotlin_params, html_args="w, h, e, t"):
        return {
            "src/android/app/src/main/assets/katex/katex-render.html": (
                "<script>\n"
                "  function renderMath(latex, displayMode, fontSize, isDark, token) {\n"
                "    AndroidBridge.onRendered(%s);\n"
                "    // prose apostrophes must not break the scanner: the pool's cache\n"
                "  }\n"
                "</script>\n" % html_args
            ),
            "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/KaTeXView.kt": (
                "package com.rikkaminis.app.ui.markdown\n"
                "class V {\n"
                "    private fun f(wv: Any) {\n"
                "        wv.addJavascriptInterface(object {\n"
                "            @JavascriptInterface\n"
                "            fun onRendered(%s) { }\n"
                "        }, \"AndroidBridge\")\n"
                "    }\n"
                "}\n" % kotlin_params
            ),
        }

    root = make_tree(fixture("width: Int, height: Int, error: String, token: Long"))
    code, out = run_scanner("katex_bridge_arity_check.py", root)
    check("4-arg call site against a 4-arg declaration passes (exit 0)", code == 0, f"exit={code}\n{out}")
    check("table reports the call site arity", "onRendered(4 args)" in out, out)
    check("table reports the host declaration", "[OK]" in out and "[4]" in out, out)
    shutil.rmtree(root)

    # The exact audit3 BUG-05 shape: the sibling host was updated, this one was not.
    root = make_tree(fixture("width: Int, height: Int, error: String"))
    code, out = run_scanner("katex_bridge_arity_check.py", root)
    check("3-arg declaration under a 4-arg call site is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    check("finding names the host file and the declared arity", "KaTeXView.kt" in out and "[3]" in out, out)
    shutil.rmtree(root)

    files = fixture("width: Int, height: Int, error: String")
    files["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/Pool.kt"] = (
        "package com.rikkaminis.app.ui.chat\n"
        "class P {\n"
        "    fun f(wv: Any) {\n"
        "        wv.addJavascriptInterface(JsBridge { w, h, e, t -> }, \"AndroidBridge\")\n"
        "    }\n"
        "    private class JsBridge(private val cb: (Int, Int, String, Long) -> Unit) {\n"
        "        @JavascriptInterface\n"
        "        fun onRendered(width: Int, height: Int, error: String, token: Long) { }\n"
        "    }\n"
        "}\n"
    )
    root = make_tree(files)
    code, out = run_scanner("katex_bridge_arity_check.py", root)
    check("a matching sibling host does not mask a stale one (exit 1)", code == 1, f"exit={code}\n{out}")
    check("both hosts are checked per file", "Pool.kt" in out and "KaTeXView.kt" in out, out)
    shutil.rmtree(root)

    rot = fixture("width: Int, height: Int, error: String, token: Long)")
    rot["src/android/app/src/main/assets/katex/katex-render.html"] = "<script>var x = 1;</script>\n"
    root = make_tree(rot)
    code, out = run_scanner("katex_bridge_arity_check.py", root)
    check("a page with no call site trips the rot guard (exit 1)", code == 1, f"exit={code}\n{out}")
    check("rot guard says the contract is unchecked", "ROT" in out, out)
    shutil.rmtree(root)


    # A host whose registration cannot be parsed must fail loudly instead of
    # dropping out of the host set. Found by this guard's own mutation control:
    # a mangled registration made KaTeXView.kt skip silently, the sibling host
    # still supplied the interface name, and the guard went green on a tree
    # whose declaration no longer matched the page.
    files = fixture("width: Int, height: Int, error: String, token: Long)")
    files["src/android/app/src/main/java/com/rikkaminis/app/ui/chat/Variable.kt"] = (
        "package com.rikkaminis.app.ui.chat\n"
        "class Q {\n"
        "    fun f(wv: Any, name: String) {\n"
        "        wv.addJavascriptInterface(object { }, name)\n"
        "    }\n"
        "}\n"
    )
    root = make_tree(files)
    code, out = run_scanner("katex_bridge_arity_check.py", root)
    check("an unparseable registration fails loudly (exit 1)", code == 1, f"exit={code}\n{out}")
    check("rot guard names the offending file", "Variable.kt" in out and "ROT" in out, out)
    shutil.rmtree(root)


GEMINI_DIR = "src/android/app/src/main/java/com/rikkaminis/app/provider/gemini"
VOICE_REL = ("src/android/app/src/main/java/com/rikkaminis/app/provider/voice/"
             "VoiceProviderVendors.kt")
MUTATION_REL = "src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderMutationMethods.kt"

# Cross-file criteria (4/5) of gemini_base_version_check only speak when both targets are
# present. They are written here so the fixture exercises the FULL path, not the [PARTIAL]
# fallback -- otherwise the two criteria would never be covered by the fixture suite and a
# future rename of either target would degrade the gate silently.
GEMINI_PROBE_ARMS_OK = (
    '        ProviderType.anthropic -> if (baseURL.endsWith("/v1")) "$baseURL/models" '
    'else "$baseURL/v1/models"\n'
    '        ProviderType.gemini -> "${GeminiBasePath.normalize(baseURL)}/models?key=" '
    '+ (key ?: "")\n'
    '        ProviderType.openAI -> if (baseURL.endsWith("/v1")) "$baseURL/models" '
    'else "$baseURL/v1/models"\n'
)


def gemini_voice_fixture(fn_body=None):
    """VoiceProviderVendors.kt with a decoy sibling + GeminiVoiceProvider."""
    gemini = fn_body if fn_body is not None else (
        "        val base = GeminiBasePath.normalize(effectiveBaseURL())\n"
        '        return "$base/models/$model:generateContent?key="\n'
    )
    return (
        "package com.rikkaminis.app.provider.voice\n"
        "\n"
        "class OtherVoiceProvider(providerId: String, baseURL: String, apiKey: String?) {\n"
        "    override fun buildVoiceOutputRequest(request: String): String {\n"
        "        return composedUrlString(voiceOutputEndpointPath())\n"
        "    }\n"
        "}\n"
        "\n"
        "class GeminiVoiceProvider(providerId: String, baseURL: String, apiKey: String?) {\n"
        "    override fun buildVoiceOutputRequest(request: String): String {\n"
        + gemini
        + "    }\n"
        "}\n"
    )


def gemini_mutation_fixture(arms=None):
    """ProviderMutationMethods.kt: a decoy per-type `when` + the probe builder."""
    body = arms if arms is not None else GEMINI_PROBE_ARMS_OK
    return (
        "package com.rikkaminis.app.debug\n"
        "\n"
        "internal object ProviderMutationMethods {\n"
        "    private fun fallback(type: ProviderType): String = when (type) {\n"
        '        ProviderType.anthropic -> "https://api.anthropic.com"\n'
        '        ProviderType.gemini -> "https://generativelanguage.googleapis.com"\n'
        '        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"\n'
        "    }\n"
        "}\n"
        "\n"
        "internal fun providerProbeUrl(type: ProviderType, baseURL: String, key: String?): String =\n"
        "    when (type) {\n"
        + body
        + "    }\n"
    )


def gemini_fixture(provider_normalizer=None, shared_normalizer=None, sibling_normalizer=None,
                   extra_bypass=False, voice_fn=None, probe_arms=None):
    """Build a three-file gemini tree mirroring the live shape."""
    provider_body = provider_normalizer or (
        "    private fun normalizedBasePath(): String = GeminiBasePath.normalize(basePath)\n"
    )
    shared_body = shared_normalizer or (
        "    fun normalize(raw: String?): String {\n"
        "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return DEFAULT_BASE\n"
        "        var stripped = base\n"
        "        while (true) {\n"
        "            val next = stripped.removeSuffix(\"/v1beta\").removeSuffix(\"/v1\")\n"
        "            if (next == stripped) break\n"
        "            stripped = next\n"
        "        }\n"
        "        return \"$stripped/v1beta\"\n"
        "    }\n"
    )
    sibling_body = sibling_normalizer or (
        "    private fun normalizeBase(baseUrl: String?): String = "
        "GeminiBasePath.normalize(baseUrl)\n"
    )
    bypass = (
        "    fun extra() {\n"
        "        val url = \"${basePath}/models/gemini-2.5-flash:generateContent\"\n"
        "    }\n"
        if extra_bypass else ""
    )
    return {
        GEMINI_DIR + "/GeminiProvider.kt": (
            "package com.rikkaminis.app.provider.gemini\n"
            "class GeminiProvider(private val basePath: String = GeminiBasePath.DEFAULT_BASE) {\n"
            + provider_body
            + "    fun sendMessage() {\n"
            "        val url = \"${normalizedBasePath()}/models/gemini-2.5-flash:generateContent\"\n"
            "    }\n"
            "    fun streamMessage() {\n"
            "        val url = \"${normalizedBasePath()}/models/gemini-2.5-flash:streamGenerateContent\"\n"
            "    }\n"
            + bypass
            + "}\n"
        ),
        GEMINI_DIR + "/GeminiBasePath.kt": (
            "package com.rikkaminis.app.provider.gemini\n"
            "internal object GeminiBasePath {\n"
            "    const val DEFAULT_BASE = \"https://generativelanguage.googleapis.com/v1beta\"\n"
            + shared_body
            + "}\n"
        ),
        GEMINI_DIR + "/GeminiModelsApi.kt": (
            "package com.rikkaminis.app.provider.gemini\n"
            "internal object GeminiModelsApi {\n"
            "    private const val DEFAULT_BASE = \"https://generativelanguage.googleapis.com/v1beta\"\n"
            + sibling_body
            + "}\n"
        ),
        VOICE_REL: gemini_voice_fixture(voice_fn),
        MUTATION_REL: gemini_mutation_fixture(probe_arms),
    }


def test_gemini_base_version():
    """The Gemini base must end in exactly one /v1beta — per file, both call sites."""
    print("━━━ gemini_base_version_check ━━━")

    root = make_tree(gemini_fixture())
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("all three normalizers present passes (exit 0)", code == 0, f"exit={code}\n{out}")
    check("delegation is reported as the intended shape", "delegates to GeminiBasePath.normalize" in out, out)
    check("both call sites are counted", "routes 2 call site(s)" in out, out)
    shutil.rmtree(root)

    # The live pre-fix shape: strips the version segment, never restores one.
    root = make_tree(gemini_fixture(provider_normalizer=(
        "    private fun normalizedBasePath(): String {\n"
        "        var p = basePath\n"
        "        while (p.endsWith(\"/\")) p = p.dropLast(1)\n"
        "        return p.removeSuffix(\"/v1beta\").removeSuffix(\"/v1\")\n"
        "    }\n"
    )))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("strip-without-restore is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    check("finding names GeminiProvider.kt", "GeminiProvider.kt" in out, out)
    shutil.rmtree(root)

    # Anti-mask: the provider delegates, so the shared file has to carry the restore.
    root = make_tree(gemini_fixture(shared_normalizer=(
        "    fun normalize(raw: String?): String {\n"
        "        val base = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return DEFAULT_BASE\n"
        "        var stripped = base\n"
        "        while (true) {\n"
        "            val next = stripped.removeSuffix(\"/v1beta\").removeSuffix(\"/v1\")\n"
        "            if (next == stripped) break\n"
        "            stripped = next\n"
        "        }\n"
        "        return stripped\n"
        "    }\n"
    )))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("delegation does not launder a broken shared implementation (exit 1)", code == 1, f"exit={code}\n{out}")
    check("finding names GeminiBasePath.kt", "GeminiBasePath.kt" in out, out)
    shutil.rmtree(root)

    # Per-file, not union: the sibling normalizer is the one the list path uses.
    root = make_tree(gemini_fixture(sibling_normalizer=(
        "    fun normalizeBase(baseUrl: String?): String {\n"
        "        val raw = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return DEFAULT_BASE\n"
        "        return raw.removeSuffix(\"/v1beta\").removeSuffix(\"/v1\")\n"
        "    }\n"
    )))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("a broken sibling normalizer is caught on its own (exit 1)", code == 1, f"exit={code}\n{out}")
    check("finding names GeminiModelsApi.kt", "GeminiModelsApi.kt" in out, out)
    shutil.rmtree(root)

    # Rot guard: a renamed normalizer must fail loudly, not read as clean.
    root = make_tree(gemini_fixture(provider_normalizer=(
        "    private fun normalizedBase(): String = GeminiBasePath.normalize(basePath)\n"
    )))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("a renamed normalizer is a hard fail, not a skip (exit 1)", code == 1, f"exit={code}\n{out}")
    check("rot message says the function was not found", "not found" in out, out)
    shutil.rmtree(root)

    # Wiring: one call site that skips the normalizer is the exact comeback shape.
    root = make_tree(gemini_fixture(extra_bypass=True))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("a call site built from the raw basePath is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    check("finding mentions the raw basePath", "raw basePath" in out, out)
    shutil.rmtree(root)

    # Criteria 4/5 (deep-audit-1024 ISSUE-02): the three sibling sites must delegate.
    # The fixture now ships both cross-file targets, so the gate runs FULL -- assert that
    # explicitly, otherwise a future rename silently drops back to the [PARTIAL] fallback.
    root = make_tree(gemini_fixture())
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("cross-file criteria run FULL on a complete tree", code == 0 and "[FULL]" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Voice site carrying its own inline copy instead of delegating.
    root = make_tree(gemini_fixture(voice_fn=(
        "        val base = effectiveBaseURL()\n"
        "        val fixed = if (base.contains(\"/v1beta\")) base else \"$base/v1beta\"\n"
        '        return "$fixed/models/$model:generateContent?key="\n'
    )))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("voice site copy is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    check("finding names GeminiVoiceProvider", "GeminiVoiceProvider" in out, out)
    shutil.rmtree(root)

    # Probe arm: gemini concatenates a literal version segment (the pre-fix 404 shape).
    root = make_tree(gemini_fixture(probe_arms=(
        GEMINI_PROBE_ARMS_OK.splitlines(True)[0]
        + '        ProviderType.gemini -> "$baseURL/v1beta/models?key=" + (key ?: "")\n'
        + GEMINI_PROBE_ARMS_OK.splitlines(True)[2]
    )))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("gemini probe arm literal segment is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Probe arm: anthropic appends /v1 with no guard (the asymmetry that made 404 reachable).
    root = make_tree(gemini_fixture(probe_arms=(
        '        ProviderType.anthropic -> "$baseURL/v1/models"\n'
        + "".join(GEMINI_PROBE_ARMS_OK.splitlines(True)[1:])
    )))
    code, out = run_scanner("gemini_base_version_check.py", root)
    check("unguarded anthropic probe arm is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(root)


def ro_write_guard_fixture(guarded=True):
    """Minimal tree for readonly_mount_write_guard: three handlers that write a
    host file at a caller-supplied path parameter, optionally WITHOUT the guard
    — the exact FIX-C 2b shape (`shapeRawPassthrough` / `writeRunOutput` had no
    read-only check while `tools/FileWriteTool` did).

    Three sites, not one: the scanner's rot guard refuses to report a clean bill
    of health for a tree where it found almost nothing to check, so a fixture
    with a single site would exercise the rot path instead of the rule.
    """
    def guard(param):
        return (
            f"        if ({param} != null && PRootKernel.isLinuxPathUnderReadOnlyMount({param})) {{\n"
            '            return 2\n'
            "        }\n"
            if guarded else ""
        )

    def handler(tag, param):
        return (
            "package com.rikkaminis.app.sandbox.offload\n"
            "\n"
            "import java.io.File\n"
            "\n"
            f"class Handler{tag} {{\n"
            f"    fun run{tag}({param}: String?): Int {{\n"
            + guard(param) +
            f"        val hostFile = File({param} ?: return 0)\n"
            '        hostFile.writeText("body")\n'
            "        return 0\n"
            "    }\n"
            "}\n"
        )

    proot = (
        "package com.rikkaminis.app.sandbox\n"
        "\n"
        "object PRootKernel {\n"
        "    fun isLinuxPathUnderReadOnlyMount(linuxPath: String): Boolean = false\n"
        "}\n"
    )
    files = {
        os.path.join(KOTLIN_PKG, "sandbox/offload/HandlerA.kt"): handler("A", "outputPath"),
        os.path.join(KOTLIN_PKG, "sandbox/offload/HandlerB.kt"): handler("B", "destPath"),
        os.path.join(KOTLIN_PKG, "sandbox/offload/HandlerC.kt"): handler("C", "linuxPath"),
        os.path.join(KOTLIN_PKG, "sandbox/PRootKernel.kt"): proot,
    }
    for i in range(24):
        files[os.path.join(KOTLIN_PKG, f"sandbox/Filler{i}.kt")] = (
            f"package com.rikkaminis.app.sandbox\n\nclass Filler{i}\n"
        )
    return files


def test_readonly_mount_write_guard():
    print("━━━ readonly_mount_write_guard ━━━")

    root = make_tree(ro_write_guard_fixture(guarded=True))
    code, out = run_scanner("readonly_mount_write_guard.py", root)
    check("a guarded --output write site passes (exit 0)", code == 0, f"exit={code}\n{out}")
    check("the scan reports how many sites it actually checked", "write site(s) guarded" in out, out)
    shutil.rmtree(root)

    root = make_tree(ro_write_guard_fixture(guarded=False))
    code, out = run_scanner("readonly_mount_write_guard.py", root)
    check("an unguarded --output write site is caught (exit 1)", code == 1, f"exit={code}\n{out}")
    check("the finding names the function and the missing call",
          "runA(" in out and "isLinuxPathUnderReadOnlyMount" in out, out)
def exec_gate_fixture(shape="good"):
    """Minimal repo tree for exec_gate_release_check.

    The fixture is the criterion written out: two interruptible wrappers, the
    rollback helper, the dispatch wrapper and the calibrated settle. Each dirty
    shape breaks exactly one of those, so a gate that stops seeing that shape
    fails here instead of failing silently in CI.
    """
    TARGET = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt"

    heavy_wrapper = (
        "internal suspend fun awaitHeavyGatePermitInterruptibly(\n"
        "    heavyGate: Semaphore,\n"
        "    timeoutMs: Long,\n"
        "): Boolean = runInterruptible(Dispatchers.IO) {\n"
        "    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n"
        "}\n"
    )
    if shape == "no_interruptible":
        # 4b regression: the wait is blocking again, so a cancelled coroutine
        # stays parked for the whole timeout.
        heavy_wrapper = heavy_wrapper.replace(
            "): Boolean = runInterruptible(Dispatchers.IO) {\n"
            "    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n"
            "}\n",
            "): Boolean = heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)\n",
        )

    rollback_body = (
        "    return try {\n"
        "        globalConcurrency.tryAcquire(1, TimeUnit.SECONDS)\n"
        "    } catch (t: Throwable) {\n"
        "        if (heavyGateHeld) heavyGate.release()\n"
        "        throw t\n"
        "    }\n"
    )
    if shape == "no_rollback":
        # 4a regression: an interrupt while waiting for the global slot now
        # escapes holding the one heavy permit.
        rollback_body = rollback_body.replace(
            "        if (heavyGateHeld) heavyGate.release()\n", "")

    dispatch_body = (
        "        when (recycleDispatchMode(isOnMainThread = "
        "Looper.myLooper() == Looper.getMainLooper())) {\n"
        "            RecycleDispatch.DEFERRED -> "
        "recoveryScope.launch { postRecycleMemoryRecoveryNow() }\n"
        "            RecycleDispatch.INLINE -> postRecycleMemoryRecoveryNow()\n"
        "        }\n"
    )
    if shape == "no_dispatch":
        # 4c regression: back to running the GC loop inline on the caller thread.
        dispatch_body = "        postRecycleMemoryRecoveryNow()\n"

    settle = "        Thread.sleep(120)\n"
    if shape == "no_settle":
        settle = "        Thread.sleep(1)\n"

    extra_acquire = ""
    if shape == "bare_acquire":
        # A raw blocking acquire reappearing in the command path.
        extra_acquire = (
            "\n    private fun execute(globalConcurrency: Semaphore) {\n"
            "        globalConcurrency.tryAcquire(60, TimeUnit.SECONDS)\n"
            "    }\n"
        )

    command_path = (
        "\n    private suspend fun executeCommand(\n"
        "        globalConcurrency: Semaphore,\n"
        "        heavyGate: Semaphore,\n"
        "    ) {\n"
        "        val heavyHeld = awaitHeavyGatePermitInterruptibly(heavyGate, 1000L)\n"
        "        awaitGlobalShellSlotInterruptibly(globalConcurrency, heavyGate, heavyHeld)\n"
        "    }\n"
    )
    if shape == "unwired":
        # G3b regression: the command path goes back to the raw helper, leaving
        # the wrapper behind as dead code that still passes every JVM arm —
        # invisible in a diff, only countable.
        command_path = command_path.replace(
            "        awaitGlobalShellSlotInterruptibly(globalConcurrency, heavyGate, heavyHeld)\n",
            "        internalAcquireGlobalShellSlot(globalConcurrency, heavyGate, heavyHeld)\n",
        )

    content = (
        "package com.rikkaminis.app.sandbox\n"
        "\n"
        "import java.util.concurrent.Semaphore\n"
        "import java.util.concurrent.TimeUnit\n"
        "\n"
        + heavy_wrapper +
        "\n"
        "internal suspend fun awaitGlobalShellSlotInterruptibly(\n"
        "    globalConcurrency: Semaphore,\n"
        "    heavyGate: Semaphore,\n"
        "    heavyGateHeld: Boolean,\n"
        "): Boolean = runInterruptible(Dispatchers.IO) {\n"
        "    internalAcquireGlobalShellSlot(globalConcurrency, heavyGate, heavyGateHeld)\n"
        "}\n"
        "\n"
        "internal fun internalAcquireGlobalShellSlot(\n"
        "    globalConcurrency: Semaphore,\n"
        "    heavyGate: Semaphore,\n"
        "    heavyGateHeld: Boolean,\n"
        "): Boolean {\n"
        + rollback_body +
        "}\n"
        "\n"
        "internal enum class RecycleDispatch { INLINE, DEFERRED }\n"
        "\n"
        "internal fun recycleDispatchMode(isOnMainThread: Boolean): RecycleDispatch =\n"
        "    if (isOnMainThread) RecycleDispatch.DEFERRED else RecycleDispatch.INLINE\n"
        "\n"
        "object ExecutionCoordinator {\n"
        + extra_acquire
        + command_path +
        "\n    private fun postRecycleMemoryRecovery() {\n"
        + dispatch_body +
        "    }\n"
        "\n    private fun postRecycleMemoryRecoveryNow() {\n"
        + settle +
        "    }\n"
        "}\n"
    )
    if shape == "missing_file":
        return {}
    return {TARGET: content}


def test_exec_gate():
    print("━━━ exec_gate_release_check ━━━")

    root = make_tree(exec_gate_fixture("good"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("clean tree exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(exec_gate_fixture("bare_acquire"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("raw blocking acquire caught",
          code == 1 and "raw blocking acquire" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(exec_gate_fixture("no_interruptible"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("unwrapped heavy wait caught",
          code == 1 and "not interruptible" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(exec_gate_fixture("no_rollback"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("missing heavy-permit rollback caught",
          code == 1 and "rollback missing" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(exec_gate_fixture("no_dispatch"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("inline main-thread recovery caught",
          code == 1 and "main-thread recovery dispatch missing" in out,
          f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(exec_gate_fixture("no_settle"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("changed settle caught",
          code == 1 and "calibrated settle changed" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(exec_gate_fixture("unwired"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("unwired command path caught (wrapper left as dead code)",
          code == 1 and "wrapper wiring changed" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Rot guard: no target file at all must FAIL, not pass vacuously.
    root = make_tree(exec_gate_fixture("missing_file"))
    code, out = run_scanner("exec_gate_release_check.py", root)
    check("missing target file fails instead of passing silently",
          code == 1 and "anchor lost" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)


def store_release_fixture(shape):
    """Minimal repo tree for scheduled_store_release_guard.

    Ground truth = the deep-audit-1016 B-7c shape (fixed 2026-10-16): the store
    cleared the persisted `running` marker but not the runner's in-process
    single-flight state, so a deleted task held the lock until the reaper fired.
    The fix is a notification call — which must sit OUTSIDE the store lock
    (nesting it inverts the lock order against the runner's checkMutex).
    """
    rel = KOTLIN_PKG + "/scheduled/ScheduledTasksStore.kt"
    head = (
        "package com.rikkaminis.app.scheduled\n"
        "object ScheduledTasksStore {\n"
        "    private val lock = Mutex()\n"
    )
    release = "        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = setOf(taskId))\n"
    release_all = "        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = null)\n"
    if shape == "missing_call":
        release = ""
    if shape == "nested":
        # The regression shape: called from inside the store's mutation block.
        release = "            ScheduledTaskRunner.onTasksRemoved(removedTaskIds = setOf(taskId))\n"
        body_delete = (
            "    suspend fun deleteTask(context: Any, taskId: String): Any {\n"
            "        return mutate(context) { file ->\n"
            "            file.copy(tasks = file.tasks.filterNot { it.id == taskId })\n"
            + release
            + "        }\n"
            "    }\n"
        )
    else:
        body_delete = (
            "    suspend fun deleteTask(context: Any, taskId: String): Any {\n"
            "        val next = mutate(context) { file ->\n"
            "            file.copy(tasks = file.tasks.filterNot { it.id == taskId })\n"
            "        }\n"
            + release
            + "        return next\n"
            "    }\n"
        )
    body_import = (
        "    suspend fun importDocument(context: Any, doc: Any) {\n"
        "        lock.withLock {\n"
        "            writeLocked(context, doc.copy(running = null))\n"
        "        }\n"
        + release_all
        + "    }\n"
    )
    if shape == "rot":
        # Nothing that looks like a task removal: the gate must fail loudly
        # instead of passing vacuously.
        body_delete = (
            "    suspend fun setGlobalEnabled(context: Any, enabled: Boolean): Any {\n"
            "        return mutate(context) { it.copy(globalEnabled = enabled) }\n"
            "    }\n"
        )
        body_import = (
            "    private fun writeLocked(context: Any, file: Any) {\n"
            "        println(file)\n"
            "    }\n"
        )
    text = head + body_delete + body_import + "}\n"
    return {rel: text}

def test_scheduled_store_release():
    print("━━━ scheduled_store_release_guard ━━━")
    root = make_tree(store_release_fixture("good"))
    code, out = run_scanner("scheduled_store_release_guard.py", root)
    check("both removal paths releasing the runner passes (exit 0)", code == 0,
          f"exit={code}\n{out}")
    check("report names both guarded functions",
          "deleteTask" in out and "importDocument" in out, out)
    shutil.rmtree(root)

    root = make_tree(store_release_fixture("missing_call"))
    code, out = run_scanner("scheduled_store_release_guard.py", root)
    check("a removal path with no release call is caught (exit 1)", code == 1,
          f"exit={code}\n{out}")
    check("finding names the offending function",
          "[deleteTask]" in out and "never calls" in out, out)
    shutil.rmtree(root)

    root = make_tree(store_release_fixture("nested"))
    code, out = run_scanner("scheduled_store_release_guard.py", root)
    check("a release call nested in the store lock is caught (exit 1)", code == 1,
          f"exit={code}\n{out}")
    check("finding reports the depth and the lock-order rule",
          "depth 2" in out and "LOCK ORDER" in out, out)
    shutil.rmtree(root)

    root = make_tree(store_release_fixture("rot"))
    code, out = run_scanner("scheduled_store_release_guard.py", root)
    check("a store with no detectable removal path trips the rot guard (exit 1)",
          code == 1, f"exit={code}\n{out}")
    check("rot guard says the criterion is unchecked", "ROT" in out, out)
    shutil.rmtree(root)

DA_AUTO_APPROVE_GATES = [
    ("auto_approve_bypass_check", "AUTO_APPROVE_BYPASS_SELFTEST: PASS", (), (REPO_ROOT,)),
]


def test_auto_approve_gates():
    print("━━━ auto-approve gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA_AUTO_APPROVE_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])


PRIMITIVE_SELFTESTS = [
    # U1 收口注册（2026-10-27）：只断言标记前缀，不 pin 臂数——臂数是自指脆弱断言（加减臂就要改门）
    ("_kotlin_lexer", "KOTLIN_LEXER_SELFTEST=PASS"),
    ("_kotlin_ast", "KOTLIN_AST_SELFTEST=PASS"),
    ("_kotlin_valueflow", "KOTLIN_VALUEFLOW_SELFTEST=PASS"),
]

def test_primitive_selftests():
    print("━━━ shared Kotlin primitives (selftest teeth; arm counts not pinned) ━━━")
    for script, marker in PRIMITIVE_SELFTESTS:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py"), "--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])

def test_real_repo():
    print("━━━ real repo tree (must be clean) ━━━")
    for script in (
        "four_way_sync_check.py",
        "i18n_check.py",
        "enum_parse_safety_check.py",
        "provider_boundary_guard.py",
        "legacy_pipeline_guard.py",
        "trace_eval_check.py",
        "extra_days_container_guard.py",
        "stale_closure_guard.py",
        "ime_inset_guard.py",
        "stream_flow_dispatch_guard.py",
        "prefs_listener_holder_guard.py",
        "thinking_wiring_guard.py",
        "liveness_static_guard.py",
        "claim_release_guard.py",
        "loop_counter_reset_guard.py",
        "budget_stop_break_guard.py",
        "katex_bridge_arity_check.py",
        "readonly_mount_write_guard.py",
        "exec_gate_release_check.py",
        "scheduled_store_release_guard.py",
    ):
        code, out = run_scanner(script, REPO_ROOT)
        check(f"{script} on real repo exits 0", code == 0, f"exit={code}\n{out[:2000]}")


def xproc_fixture(model_body=None, dispatcher_puts=None, comment_comma=False):
    """Minimal repo tree for xproc_boundary_check."""
    B = "src/android/app/src/main/java/com/rikkaminis/app/"
    if model_body is None:
        model_body = (
            "    val id: String,\n"
            "    val maxOutputTokens: Int? = null,\n"
        )
    if comment_comma:
        # The real shape that broke an earlier version: a comma inside a comment
        # split the field list mid-way and dropped every later field.
        model_body = (
            "    val id: String,\n"
            "    // Some relay gateways, however, do not honour this.\n"
            "    val maxOutputTokens: Int? = null,\n"
        )
    if dispatcher_puts is None:
        dispatcher_puts = (
            '        put("model_id", model.id)\n'
            '        model.maxOutputTokens?.let { put("max_output_tokens", it) }\n'
        )
    # ProviderInstance is always part of the boundary; write its key so the
    # fixture is internally consistent (otherwise the guard correctly reports it).
    fixed_puts = '        put("instance_id", instance.id)\n'
    return {
        B + "data/model/LLMModel.kt":
            "package com.rikkaminis.app.data.model\n\n"
            "data class LLMModel(\n" + model_body + ")\n",
        B + "data/model/ProviderConfig.kt":
            "package com.rikkaminis.app.data.model\n\n"
            "data class ProviderInstance(\n    val id: String,\n)\n",
        B + "sandbox/offload/ModelExecutionDispatcher.kt":
            "package com.rikkaminis.app.sandbox.offload\n\n"
            "class ModelExecutionDispatcher {\n"
            "    fun build(model: LLMModel, instance: ProviderInstance) {\n"
            "        val bundle = Bundle()\n"
            "        bundle.apply {\n" + fixed_puts + dispatcher_puts + "        }\n"
            "    }\n}\n",
        B + "sandbox/offload/ModelExecutionService.kt":
            "package com.rikkaminis.app.sandbox.offload\n\n"
            "class ModelExecutionService {\n"
            '    fun read(b: Bundle) { val id = b.getString("model_id")\n'
            '        val m = b.getInt("max_output_tokens")\n'
            '        val i = b.getString("instance_id") }\n}\n',
    }


def test_xproc_boundary():
    print("━━━ xproc_boundary_check ━━━")
    # Clean — every boundary field is written by the dispatcher.
    root = make_tree(xproc_fixture())
    code, out = run_scanner("xproc_boundary_check.py", root)
    check("clean fixture exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Dirty — the exact P0 shape: maxOutputTokens never written.
    root = make_tree(xproc_fixture(dispatcher_puts='        put("model_id", model.id)\n'))
    code, out = run_scanner("xproc_boundary_check.py", root)
    check(
        "unwritten field caught (exit 1, MISSING maxOutputTokens)",
        code == 1 and "maxOutputTokens" in out and "MISSING" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Regression: a comma inside a comment must NOT truncate the field list.
    root = make_tree(xproc_fixture(comment_comma=True,
                                   dispatcher_puts='        put("model_id", model.id)\n'))
    code, out = run_scanner("xproc_boundary_check.py", root)
    check(
        "comment comma does not truncate field list (maxOutputTokens still seen)",
        code == 1 and "maxOutputTokens" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # Bare put() inside apply{} must be recognised (no leading dot).
    root = make_tree(xproc_fixture())
    code, out = run_scanner("xproc_boundary_check.py", root)
    check("bare put() inside apply{} recognised", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)


def thinking_wiring_fixture(requested_arg="requestedThinkingLevelState",
                            requested_binding="val requestedThinkingLevelState by viewModel.requestedThinkingLevel.collectAsState()",
                            current_arg="viewModel.effectiveThinkingLevel",
                            clamp_arg="requested", tap_arg="requested",
                            escape=False):
    """Minimal tree for thinking_wiring_guard: one picker call + its body."""
    pkg = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/"
    esc = " // thinking-wiring-ok: fixture" if escape else ""
    caller = (
        "package com.rikkaminis.app.ui.chat\n"
        "class ChatInputArea {\n"
        "    fun row(viewModel: ChatViewModel) {\n"
        f"        {requested_binding}\n"
        "        ThinkingLevelPicker(\n"
        f"            current = {current_arg},\n"
        f"            requested = {requested_arg},{esc}\n"
        "            availableLevels = viewModel.availableThinkingLevels,\n"
        "            onSelect = { },\n"
        "        )\n"
        "    }\n"
        "}\n"
    )
    body = (
        "package com.rikkaminis.app.ui.chat\n"
        "internal fun ThinkingLevelPicker(\n"
        "    current: ThinkingLevel,\n"
        "    requested: ThinkingLevel,\n"
        "    availableLevels: List<ThinkingLevel>,\n"
        "    onSelect: (ThinkingLevel) -> Unit,\n"
        ") {\n"
        "    val maxAvailable = availableLevels.lastOrNull { it != ThinkingLevel.OFF }\n"
        f"    val isClamped = maxAvailable != null && isCappedBy({clamp_arg}, maxAvailable)\n"
        "    availableLevels.forEach { level ->\n"
        f"        onSelect(thinkingTapTarget(level, {tap_arg}))\n"
        "    }\n"
        "}\n"
    )
    return {pkg + "ChatInputArea.kt": caller, pkg + "ChatComposerWidgets.kt": body}

def test_thinking_wiring():
    print("━━━ thinking_wiring_guard ━━━")
    # Clean.
    root = make_tree(thinking_wiring_fixture())
    code, out = run_scanner("thinking_wiring_guard.py", root)
    check("clean fixture exits 0", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # The real 2026-09-22 defect: `requested` fed the effective level. The
    # caller's local is renamed so a spelling-based check would miss it —
    # provenance must follow the `val` binding.
    root = make_tree(thinking_wiring_fixture(
        requested_arg="effectiveThinkingLevelState",
        requested_binding="val effectiveThinkingLevelState by viewModel.thinkingLevel.collectAsState()",
    ))
    code, out = run_scanner("thinking_wiring_guard.py", root)
    check(
        "requested <- effective flow caught through a rename",
        code == 1 and "EFFECTIVE level" in out,
        f"exit={code}\n{out}",
    )
    shutil.rmtree(root)

    # `current` collapsed to the raw choice.
    root = make_tree(thinking_wiring_fixture(current_arg="requestedThinkingLevelState"))
    code, out = run_scanner("thinking_wiring_guard.py", root)
    check("current <- raw choice caught", code == 1 and "current" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Clamp predicate and tap rule must both key off `requested`.
    root = make_tree(thinking_wiring_fixture(clamp_arg="current"))
    code, out = run_scanner("thinking_wiring_guard.py", root)
    check("isCappedBy(current, ...) caught", code == 1 and "isCappedBy" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    root = make_tree(thinking_wiring_fixture(tap_arg="current"))
    code, out = run_scanner("thinking_wiring_guard.py", root)
    check("tap keyed off current caught", code == 1 and "thinkingTapTarget" in out, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Escape hatch clears it.
    root = make_tree(thinking_wiring_fixture(
        requested_arg="effectiveThinkingLevelState",
        requested_binding="val effectiveThinkingLevelState by viewModel.thinkingLevel.collectAsState()",
        escape=True,
    ))
    code, out = run_scanner("thinking_wiring_guard.py", root)
    check("escape hatch clears the finding", code == 0, f"exit={code}\n{out}")
    shutil.rmtree(root)

    # Rot guard: a tree with no picker call must FAIL, not silently pass.
    root = make_tree({"src/android/app/src/main/java/com/rikkaminis/app/Empty.kt":
                      "package com.rikkaminis.app\n"})
    code, out = run_scanner("thinking_wiring_guard.py", root)
    check("missing picker call fails instead of passing silently", code == 1, f"exit={code}\n{out}")
    shutil.rmtree(root)

# ─────────────────────────────────────────────────────────────────────────────
# deep-audit-1017 gates (FIX-A..G, registered at union-tree wrap-up)
# Each new gate ships its own --selftest with negative arms (pre-fix shapes
# asserted red) — asserted here with a marker so a stubbed gate cannot pass
# silently; the real-tree check pins the union tree the way CI runs it.
# ─────────────────────────────────────────────────────────────────────────────

DA1017_GATES = [
    # (script without .py, selftest marker that proves negative arms ran, real-tree argv)
    ("tool_result_image_branch_check", "missing provider package is a hard failure", (REPO_ROOT,)),
    ("turn_tail_finalize_order", "SELFTEST PASS", (REPO_ROOT,)),
    ("subagent_handback_check", "SELFTEST OK", (REPO_ROOT,)),
    ("calendar_events_nullable_predicate_check", "ALL ARMS MATCH", ("--root", REPO_ROOT)),
    ("shizuku_args_check", "gate flips red on every criterion", (REPO_ROOT,)),
    ("marker_boundary_check", "SELFTEST=PASS", (REPO_ROOT,)),
    ("a11y_handler_contract_check", "selftest passed", (REPO_ROOT,)),
]

def test_da1017_gates():
    print("━━━ deep-audit-1017 gates (selftest teeth + real tree) ━━━")
    for script, marker, real_args in DA1017_GATES:
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, script + ".py"), "--selftest"],
            capture_output=True, text=True,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        check(f"{script} selftest has teeth ({marker})", proc.returncode == 0 and marker in out,
              out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check(f"{script} real tree green", rc == 0, out[-280:])

def test_da1017_external_fixtures():
    """External clean/dirty pairs for the two gates that ship module-level fixture
    constants: import the gate, materialize its GOOD/PRE-fix fixture into a temp
    tree, and drive the gate as a subprocess — independent of the gate's own
    selftest wiring (a gate stubbed to exit 0 still fails here)."""
    print("━━━ deep-audit-1017 external fixture pairs ━━━")
    import importlib.util
    for script, clean_const, dirty_const, dirty_name in (
        ("marker_boundary_check", "OK_FIXTURE", "PRE_FIX_FIXTURE", "pre-fix boundary hit"),
        ("subagent_handback_check", "GOOD", "NO_ECHO", "missing assistant echo"),
    ):
        spec = importlib.util.spec_from_file_location(
            script, os.path.join(SCRIPT_DIR, script + ".py"))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        clean_root = make_tree({mod.TARGET: getattr(mod, clean_const)})
        rc, out = run_scanner(script + ".py", clean_root)
        check(f"{script} external clean fixture green", rc == 0, out[-280:])
        dirty_root = make_tree({mod.TARGET: getattr(mod, dirty_const)})
        rc, out = run_scanner(script + ".py", dirty_root)
        check(f"{script} external {dirty_name} fixture red", rc == 1, out[-280:])

# ─────────────────────────────────────────────────────────────────────────────
# deep-audit-1016 gate (FIX-E / ISSUE-07, registered at branch wrap-up)
# Same pattern as DA1017: the gate ships its own --selftest with negative
# arms; asserted here via its unique closing marker so a stubbed gate cannot
# pass silently, plus the real-tree check pinned the way CI runs it.
# ─────────────────────────────────────────────────────────────────────────────

DA1016_GATES = [
    # (script without .py, selftest closing marker, real-tree argv)
    ("tts_voice_and_sanitizer_check", "TTS-VOICE GATE ALL ARMS MATCH", ("--root", REPO_ROOT)),
    ("loop_persist_slice_check", 'SELFTEST=PASS loop_persist_slice_check (6 violating fixtures red, compliant fixture green)', (REPO_ROOT,)),
    ("offload_host_path_check", 'SELFTEST=PASS (7 fixtures: 4 violations caught, 3 clean trees green)', (REPO_ROOT,)),
    ("browser_tab_exec_serial_check", 'SELFTEST=PASS (browser_tab_exec_serial: 3 fixtures)', (REPO_ROOT,)),
    ("downloads_atomic_update_check", 'SELFTEST=PASS (downloads_atomic_update: 4 fixtures)', (REPO_ROOT,)),
    ("compact_decision_gate_check", 'COMPACT_DECISION_GATE_SELFTEST=PASS', (REPO_ROOT,)),
    ("schedule_date_source_check", 'SELFTEST=PASS (schedule-date-source: 2 files x 2 arms, teeth on every criterion)', (REPO_ROOT,)),
    ("sysinfo_state_thread_check", 'SYSINFO-STATE SELFTEST=PASS', (REPO_ROOT,)),
    ("rss_probe_lock_scope_check", 'SELFTEST=PASS (5 fixtures)', (REPO_ROOT,)),
]

def test_da1016_gates():
    print("━━━ deep-audit-1016 gates (selftest teeth + real tree) ━━━")
    for script, marker, real_args in DA1016_GATES:
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, script + ".py"), "--selftest"],
            capture_output=True, text=True,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        check(f"{script} selftest has teeth ({marker})", proc.returncode == 0 and marker in out,
              out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check(f"{script} real tree green", rc == 0, out[-280:])


# ─────────────────────────────────────────────────────────────────────────────
# deep-audit-1018 gates (FIX-A..H, registered at branch wrap-up)
# Same pattern as DA1017/DA1016: each gate ships its own --selftest whose
# negative arms assert the pre-fix shape red. The marker assertion is what
# makes a stubbed gate (sys.exit(0)) fail here — verified by gutting
# dao_loadmessages_check.py during wrap-up: without the marker check it stayed
# green, which is exactly the failure mode this list exists to block.
# ─────────────────────────────────────────────────────────────────────────────

DA1018_GATES = [
    # (script without .py, selftest closing marker, real-tree argv)
    ("truncated_call_guard_check", "TRUNCATED_CALL_GUARD_SELFTEST=PASS", (REPO_ROOT,)),
    ("outcome_known_chain_check", "OUTCOME_KNOWN_CHAIN_SELFTEST=PASS", ("--root", REPO_ROOT)),
    ("compact_pin_truth_check", "COMPACT_PIN_TRUTH_SELFTEST=PASS", (REPO_ROOT,)),
    ("dao_loadmessages_check", "DAO_LOADMESSAGES_SELFTEST=PASS", ("--root", REPO_ROOT)),
    ("backup_remap_rollup_check", "BACKUP_REMAP_ROLLUP_SELFTEST=PASS", (REPO_ROOT,)),
    ("router_lock_check", "ROUTER_LOCK_SELFTEST=PASS", ("--root", REPO_ROOT)),
    ("modelsvc_single_read_check", "MODELSVC_SINGLE_READ_SELFTEST=PASS", (REPO_ROOT,)),
]

def test_da1018_gates():
    print("━━━ deep-audit-1018 gates (selftest teeth + real tree) ━━━")
    for script, marker, real_args in DA1018_GATES:
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, script + ".py"), "--selftest"],
            capture_output=True, text=True,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        check(f"{script} selftest has teeth ({marker})", proc.returncode == 0 and marker in out,
              out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check(f"{script} real tree green", rc == 0, out[-280:])


# deep-audit-1019 gates: same two-layer teeth as DA1016/1017/1018 — the gate's
# own --selftest must print its unique closing marker (a stubbed gate that only
# calls sys.exit(0) cannot print it), and the gate must pass on the real tree.
DA1019_GATES = [
    # (script without .py, selftest closing marker, selftest extra argv, real-tree argv)
    ("modeluse_proto_single_source_check", "MODELUSE_PROTO_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("loop_tool_status_transition_check", "LOOP_TOOL_STATUS_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("fastio_tar_bounds_check", "FASTIO_TAR_BOUNDS_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("provider_ua_parity_check", "PROVIDER_UA_PARITY_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("session_provider_enabled_check", "SESSION_PROVIDER_ENABLED_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("parts_json_funnel_check", "PARTSJSON_FUNNEL_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("katex_ready_race_check", "KATEX_READY_RACE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("browser_destroy_wakeup_check", "BROWSER_DESTROY_WAKEUP_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("offload_cli_args_check", "OFFLOAD_CLI_ARGS_SELFTEST=PASS", (REPO_ROOT,), (REPO_ROOT,)),
]

DA1020_GATES = [
    ("provider_models_cache_wiring_check", "PROVIDER_MODELS_CACHE_WIRING_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("webview_holder_url_source_check", "WEBVIEW_HOLDER_URL_SOURCE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("voice_cancel_guard_check", "VOICE_CANCEL_GUARD_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("deeplink_settings_routes_check", "DEEPLINK_SETTINGS_ROUTES_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("memory_gate_provider_fields_check", "MEMORY_GATE_PROVIDER_FIELDS_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("filepreview_print_bounded_check", "FILEPREVIEW_PRINT_BOUNDED_SELFTEST=PASS", (), (REPO_ROOT,)),
]


def test_da1019_gates():
    print("━━━ deep-audit-1019 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1019_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])


def test_da1020_gates():
    print("━━━ deep-audit-1020 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1020_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])



DA1021_GATES = [
    ("skill_restore_session_cleanup_check", "SKILL_RESTORE_SESSION_CLEANUP_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("provider_import_id_roundtrip_check", "PROVIDER_IMPORT_ID_ROUNDTRIP_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("mirror_category_route_check", "MIRROR_CATEGORY_ROUTE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("chat_menu_prefs_listener_check", "CHAT_MENU_PREFS_LISTENER_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("skill_file_save_result_check", "SKILL_FILE_SAVE_RESULT_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("webapp_asset_url_cleanup_check", "WEBAPP_ASSET_URL_CLEANUP_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("speech_active_engine_check", "SPEECH_ACTIVE_ENGINE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("open_url_broker_scheme_check", "OPEN_URL_BROKER_SCHEME_SELFTEST=PASS", (), (REPO_ROOT,)),
]


def test_da1021_gates():
    print("━━━ deep-audit-1021 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1021_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])



DA1022_GATES = [
    ("offload_media_b64_wire_check", "OFFLOAD_MEDIA_B64_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("session_sheet_id_binding_check", "SESSION_SHEET_ID_BINDING_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("mediaplayer_release_guard", "MEDIAPLAYER_RELEASE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("browser_history_snapshot_check", "BROWSER_HISTORY_SNAPSHOT_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("openminis_compat_field_parity_check", "OPENMINIS_COMPAT_FIELD_PARITY_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("entry_id_rename_integrity_check", "ENTRY_ID_RENAME_INTEGRITY_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("mirror_vm_thread_check", "MIRROR_VM_THREAD_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("browser_cookie_ui_refresh_check", "BROWSER_COOKIE_UI_REFRESH_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("tool_title_error_branch_check", "TOOL_TITLE_ERROR_BRANCH_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("share_dir_prune_check", "SHARE_DIR_PRUNE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("file_preview_url_encode_check", "FILE_PREVIEW_URL_ENCODE_SELFTEST=PASS", (), (REPO_ROOT,)),
]

def test_da1022_gates():
    print("━━━ deep-audit-1022 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1022_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])

DA1023_GATES = [
    ("file_provider_paths_coverage_check", "FILE_PROVIDER_PATHS_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("model_entry_override_parity_check", "MODEL_ENTRY_OVERRIDE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("compact_entry_pin_migration_check", "COMPACT_ENTRY_PIN_MIGRATION_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("entry_composite_key_guard_check", "ENTRY_COMPOSITE_KEY_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("collection_copy_on_mutate_check", "COLLECTION_COPY_ON_MUTATE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("skill_frontmatter_roundtrip_check", "SKILL_FRONTMATTER_ROUNDTRIP_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("skill_import_offmain_check", "SKILL_IMPORT_OFFMAIN_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("chat_session_id_binding_check", "CHAT_SESSION_ID_BINDING_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("memory_ui_wiring_check", "MEMORY_UI_WIRING_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("file_uri_parse_guard_check", "FILE_URI_PARSE_GUARD_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("backup_streaming_export_check", "BACKUP_STREAMING_EXPORT_SELFTEST=PASS", (), (REPO_ROOT,)),
]

def test_da1023_gates():
    print("━━━ deep-audit-1023 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1023_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])

DA1024_GATES = [
    ("mcp_oauth_lifecycle_check", "MCP_OAUTH_LIFECYCLE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("thinking_rule_editor_preserve_check", "THINKING_RULE_EDITOR_PRESERVE_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("offload_permission_reset_sync_check", "OFFLOAD_PERMISSION_RESET_SYNC_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("offload_relative_path_unify_check", "OFFLOAD_RELATIVE_PATH_UNIFY_SELFTEST=PASS", (), (REPO_ROOT,)),
]


def test_da1024_gates():
    print("━━━ deep-audit-1024 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1024_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])


DA1026_ARCH_GATES = [
    ("provider_write_rules_check", "PROVIDER_WRITE_RULES_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("provider_type_registry_check", "PROVIDER_TYPE_REGISTRY_SELFTEST=PASS", (), (REPO_ROOT,)),
]

def test_da1026_arch_gates():
    print("━━━ deep-audit-1026 ARCH gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1026_ARCH_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])


DA1026_GATES = [
    ("models_provider_field_check", "MODELS_PROVIDER_FIELD_SELFTEST=PASS", (), (REPO_ROOT,)),
]

def test_da1026_gates():
    print("━━━ deep-audit-1026 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1026_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])


DA1025_GATES = [
    ("browser_tabs_lifecycle_check", "BROWSER_TABS_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("envvar_config_group_check", "ENVVAR_GROUP_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("provider_remember_key_check", "PROVIDER_REMEMBER_SELFTEST=PASS", (), (REPO_ROOT,)),
    ("provider_rpc_symmetry_check", "PROVIDER_RPC_SELFTEST=PASS", (), (REPO_ROOT,)),
]


def test_da1025_gates():
    print("━━━ deep-audit-1025 gates (selftest teeth + real tree) ━━━")
    for script, marker, st_extra, real_args in DA1025_GATES:
        argv = [sys.executable, os.path.join(SCRIPT_DIR, script + ".py")] + list(st_extra) + ["--selftest"]
        proc = subprocess.run(argv, capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        check("%s selftest has teeth (%s)" % (script, marker),
              proc.returncode == 0 and marker in out, out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check("%s real tree green" % script, rc == 0, out[-280:])


def test_trace_step_tag():
    print("━━━ trace_step_tag_check ━━━")
    # The tagger reads AgentTraceRecorder's event/field names and the golden
    # traces. A rename on either side does not raise -- it just silently labels
    # 0 calls and reports "no write action", which reads like a normal result.
    # These fixtures are the ground truth for each of the three faces.
    script = "trace_step_tag_check.py"

    # teeth: the three faces must each flip red on their own bad input
    proc = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, script), "--selftest"],
        capture_output=True, text=True,
    )
    out = (proc.stdout or "") + (proc.stderr or "")
    check("tag checker selftest has teeth (TRACE_STEP_TAG_SELFTEST=PASS)",
          proc.returncode == 0 and "TRACE_STEP_TAG_SELFTEST=PASS" in out, out[-280:])

    # real tree green
    rc, out = run_scanner(script, REPO_ROOT)
    check("tag checker real tree green", rc == 0, out[-280:])

    # discrimination: empty golden -> end-to-end face must fail the whole gate
    # (a gate that stays green on a trace with 0 tool calls would never notice
    #  the Kotlin side stopping writing them)
    kt = ("src/android/app/src/main/java/com/rikkaminis/app/tools/"
          "AgentTraceRecorder.kt")
    root = make_tree({
        kt: ('const val TYPE_TOOL_CALL = "tool_call"\n'
             'const val TYPE_TOOL_RESULT = "tool_result"\n'
             'const val TYPE_TURN_START = "turn_start"\n'
             'const val TYPE_TURN_END = "turn_end"\n'
             'put("tool", x)\nput("args", x)\nput("tool_id", x)\nput("success", x)\n'),
        "tests/traces/golden/traces/empty.jsonl": '{"type":"trace_start"}\n',
    })
    rc, out = run_scanner(script, root)
    check("empty golden caught (exit 1)", rc == 1 and "0 次 tool_call" in out,
          f"exit={rc}\n{out[-280:]}")
    shutil.rmtree(root)

    # discrimination: renamed Kotlin field -> wiring face must fail the gate
    root = make_tree({
        kt: ('const val TYPE_TOOL_CALL = "tool_call"\n'
             'const val TYPE_TOOL_RESULT = "tool_result"\n'
             'const val TYPE_TURN_START = "turn_start"\n'
             'const val TYPE_TURN_END = "turn_end"\n'
             'put("tool", x)\nput("arguments", x)\nput("tool_id", x)\nput("success", x)\n'),
        "tests/traces/golden/traces/t.jsonl":
            '{"type":"tool_call","tool":"file_read","args":"{}","tool_id":"a"}\n',
    })
    rc, out = run_scanner(script, root)
    check("renamed Kotlin field caught (exit 1)",
          rc == 1 and 'put("args")' in out, f"exit={rc}\n{out[-280:]}")
    shutil.rmtree(root)


def test_dv16_chat_ui_interaction():
    print("━━━ chat_ui_interaction_check (dv16 FIX-4) ━━━")
    # Three defect families pinned by one gate (B-01 unread isMcp descriptor,
    # B-02 table copy actions missing from the main-chat toolbar + bare
    # messageId registry key, B-06 tautological swipe eligibility re-derived
    # inside an already-guarded branch). The fixtures below are the ground
    # truth for the load-bearing criteria: each bad tree must flip the gate
    # red, and the real tree must stay green.
    script = "chat_ui_interaction_check.py"

    proc = subprocess.run(
        [sys.executable, os.path.join(SCRIPT_DIR, script), "--selftest"],
        capture_output=True, text=True,
    )
    selftest_out = (proc.stdout or "") + (proc.stderr or "")
    check("chat UI gate selftest has teeth (CHAT_UI_INTERACTION_SELFTEST=PASS)",
          proc.returncode == 0 and "CHAT_UI_INTERACTION_SELFTEST=PASS" in selftest_out,
          selftest_out[-320:])

    rc, out = run_scanner(script, REPO_ROOT)
    check("chat UI gate on real repo exits 0", rc == 0, out[-320:])

    models = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatModels.kt",
              "package com.rikkaminis.app.ui.chat\n"
              "data class SlashCommand(\n"
              "    val id: String,\n"
              "    val isSkill: Boolean = false,\n"
              "    val isMcp: Boolean = false,\n"
              ")\n")
    token = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatSlashTokenExt.kt",
             "package com.rikkaminis.app.ui.chat\n"
             "fun ChatViewModel.executeSlashCommand(cmd: SlashCommand): String {\n"
             "    if (cmd.isSkill) { resetSlashMenuState(); return fillComposerWithSlashTitle(cmd, saved) }\n"
             "    if (cmd.isMcp) { resetSlashMenuState(); return fillComposerWithSlashTitle(cmd, saved) }\n"
             "    return \"\"\n"
             "}\n")
    inp = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt",
           "package com.rikkaminis.app.ui.chat\n"
           "@Composable\n"
           "fun ChatInputArea() {\n"
           "    val swipeThresholdPxState = rememberUpdatedState(\n"
           "        with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() },\n"
           "    )\n"
           "    val canSwipeSendNow: () -> Boolean = {\n"
           "        inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()\n"
           "    }\n"
           "    val isInstalledRow = cmd.isSkill || cmd.isMcp\n"
           "    Box(modifier = Modifier.pointerInput(Unit) {\n"
           "        awaitEachGesture {\n"
           "            var totalDy = 0f\n"
           "            if (totalDy < 0) {\n"
           "                if (canSwipeSendNow()) {\n"
           "                    sendSwipeProgress = (-totalDy / swipeThresholdPxState.value).coerceIn(0f, 1f)\n"
           "                }\n"
           "            }\n"
           "            val swipedUp = claimed && totalDy < 0\n"
           "            if (swipedUp && canSwipeSendNow()) {\n"
           "                performSendOrEnqueue()\n"
           "                sendSwipeProgress = 0f\n"
           "            } else if (swipedUp && !canSwipeSendNow() && !inputFocused) {\n"
           "                sendSwipeProgress = 0f\n"
           "            }\n"
           "        }\n"
           "    }) { Column { } }\n"
           "}\n")
    gesture = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MinisTextKitGesture.kt",
               "package com.rikkaminis.app.ui.chat\n"
               "@Composable\n"
               "fun MinisSelectionToolbarHost(controller: SelectionController) {\n"
               "    Row {\n"
               "        val tableActions = controller.selectionTableActions()\n"
               "    }\n"
               "}\n")
    selection = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MinisTextKitSelection.kt",
                 "package com.rikkaminis.app.ui.chat\n"
                 "class SelectionController {\n"
                 "    class TableActions(val copyTableMarkdown: () -> Unit)\n"
                 "    private val tableActionsByMessage =\n"
                 "        androidx.compose.runtime.mutableStateMapOf<\n"
                 "            String, androidx.compose.runtime.snapshots.SnapshotStateMap<Int, TableActions>\n"
                 "        >()\n"
                 "    fun rememberTableActions(messageId: String, tableIndex: Int, actions: TableActions) {\n"
                 "        tableActionsByMessage.getOrPut(messageId) {\n"
                 "            androidx.compose.runtime.mutableStateMapOf()\n"
                 "        }[tableIndex] = actions\n"
                 "    }\n"
                 "    fun forgetTableActions(messageId: String, tableIndex: Int) { }\n"
                 "    fun selectionTableActions(): TableActions? {\n"
                 "        val msgId = singleMessageId() ?: return null\n"
                 "        return tableActionsByMessage[msgId]?.get(0)\n"
                 "    }\n"
                 "}\n")
    stream = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt",
              "package com.rikkaminis.app.ui.chat\n"
              "@Composable\n"
              "private fun RenderTable(block: MdBlock.Table) {\n"
              "    val messageId = LocalShardId.current?.messageId\n"
              "    val tableSlotAllocator = LocalShardSubIndexAllocator.current\n"
              "    val tableIndex = remember { tableSlotAllocator?.next() ?: 0 }\n"
              "    selectionController.rememberTableActions(messageId, tableIndex, actions)\n"
              "}\n")
    mdtoolbar = ("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MinisMarkdownTextToolbar.kt",
                 "package com.rikkaminis.app.ui.chat\n"
                 "@Composable\n"
                 "fun MinisMarkdownTextToolbarHost(toolbar: Toolbar) {\n"
                 "    val tableActions = toolbar.selectionController?.selectionTableActions()\n"
                 "}\n")

    def tree(**over):
        files = dict([models, token, inp, gesture, selection, stream, mdtoolbar])
        files.update(over)
        return make_tree(files)

    # B-01: an MCP row tap must not fall through to the action dispatch
    root = tree(**{token[0]: token[1].replace(
        "    if (cmd.isMcp) { resetSlashMenuState(); return fillComposerWithSlashTitle(cmd, saved) }\n", "")})
    rc, out = run_scanner(script, root)
    check("missing isMcp branch caught (exit 1)", rc == 1 and "[A2]" in out, f"exit={rc}\n{out[-320:]}")
    shutil.rmtree(root)

    # B-02a: the main-chat toolbar must be wired to the table actions
    root = tree(**{gesture[0]: gesture[1].replace(
        "        val tableActions = controller.selectionTableActions()\n", "")})
    rc, out = run_scanner(script, root)
    check("main-chat table wiring dropped caught (exit 1)",
          rc == 1 and "[B1]" in out, f"exit={rc}\n{out[-320:]}")
    shutil.rmtree(root)

    # B-02b: the registry key must carry the table dimension
    root = tree(**{selection[0]: selection[1].replace(
        "fun rememberTableActions(messageId: String, tableIndex: Int, actions: TableActions)",
        "fun rememberTableActions(messageId: String, actions: TableActions)")})
    rc, out = run_scanner(script, root)
    check("bare-messageId table key caught (exit 1)",
          rc == 1 and "[B3]" in out, f"exit={rc}\n{out[-320:]}")
    shutil.rmtree(root)

    # B-06: the tautological eligibility re-derivation inside its own guard
    root = tree(**{inp[0]: inp[1].replace(
        "            if (swipedUp && canSwipeSendNow()) {\n",
        "            if (swipedUp && canSwipeSendNow()) {\n"
        "                val canSendNow = canSwipeSendNow() || attachments.isNotEmpty()\n")})
    rc, out = run_scanner(script, root)
    check("nested dead-branch shape caught (exit 1)",
          rc == 1 and "[C3]" in out, f"exit={rc}\n{out[-320:]}")
    shutil.rmtree(root)

    # B-06b: a frozen threshold capture (no rememberUpdatedState) must be caught
    root = tree(**{inp[0]: inp[1].replace(
        "    val swipeThresholdPxState = rememberUpdatedState(\n"
        "        with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() },\n"
        "    )\n",
        "    val swipeThresholdPxState = with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() }\n")})
    rc, out = run_scanner(script, root)
    check("frozen swipe threshold caught (exit 1)",
          rc == 1 and "[C4]" in out, f"exit={rc}\n{out[-320:]}")
    shutil.rmtree(root)



# ─────────────────────────────────────────────────────────────────────────────
# deep-audit-1046 gates (S-03 / S-06, registered at union wrap-up)
# Same pattern as DA1016: every gate ships its own --selftest with negative
# arms; asserted through its unique closing marker so a stubbed gate cannot
# pass silently, plus the real-tree run pinned the way CI invokes it.
# ─────────────────────────────────────────────────────────────────────────────

DA1046_GATES = [
    # (script without .py, selftest closing marker, real-tree argv)
    ("anthropic_thinking_budget_check", "ANTHROPIC_THINKING_BUDGET_SELFTEST=PASS", (REPO_ROOT,)),
    ("overlay_show_hide_latch_check", "OVERLAY_SHOW_HIDE_LATCH_SELFTEST=PASS", (REPO_ROOT,)),
]


def test_da1046_gates():
    print("━━━ deep-audit-1046 gates (selftest teeth + real tree) ━━━")
    for script, marker, real_args in DA1046_GATES:
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, script + ".py"), "--selftest"],
            capture_output=True, text=True,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        check(f"{script} selftest has teeth ({marker})", proc.returncode == 0 and marker in out,
              out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check(f"{script} real tree green", rc == 0, out[-280:])


# ─────────────────────────────────────────────────────────────────────────────
# deep-audit-1047 gate registration (union wrap-up): discarded_return_guard was
# extended with the B-01 binary-write criteria (compress/openOutputStream
# verdicts) but had never been registered here, so its --self-test arms were
# never witnessed in CI. Registered at the union so a stubbed criterion cannot
# pass silently; note the hyphenated flag (this gate spells it --self-test).
# (script without .py, selftest flag, closing marker, real-tree argv)
# ─────────────────────────────────────────────────────────────────────────────

DV27_GATES = [
    ("discarded_return_guard", "--self-test", "binary-write self-test has teeth", (REPO_ROOT,)),
]

def test_dv27_gates():
    print("━━━ deep-audit-1047 gates (selftest teeth + real tree) ━━━")
    for script, selftest_flag, marker, real_args in DV27_GATES:
        proc = subprocess.run(
            [sys.executable, os.path.join(SCRIPT_DIR, script + ".py"), selftest_flag],
            capture_output=True, text=True,
        )
        out = (proc.stdout or "") + (proc.stderr or "")
        check(f"{script} selftest has teeth ({marker})", proc.returncode == 0 and marker in out,
              out[-280:])
        rc, out = run_scanner(script + ".py", *real_args)
        check(f"{script} real tree green", rc == 0, out[-280:])

# ─────────────────────────────────────────────────────────────────────────────
# Fixture-run scheduling (2026-10-27, FIX-1027-SHARDED-FIXTURES)
#
# The suite was 5.25 min of a 7.18 min cheap-gate run and ~79% of the scan step
# inside the full build: 344 fixtures, each a handful of tiny temp trees plus a
# scanner subprocess, run one after another. The work is process-bound, so it
# parallelises almost linearly — but only if the split is by *cost*, and cost
# per fixture is not knowable up front (it drifts every time a gate gains an
# arm). A static shard table would rot on the first added criterion, so the
# split is a worker pool: `--jobs N` hands one test at a time to the next free
# worker. Balance is automatic and nothing is pinned.
#
# Architecture (same blood as scan_parallel.py): each test function runs in its
# own child interpreter (`test_scan.py --single NAME`), coordinated by J parent
# threads. Deliberately NOT multiprocessing.Pool — pool semaphores are not
# available in the PRoot sandbox, and the runner has to work identically in
# CI, locally, and under proot. Isolation per child also means a hard-killed
# test (OOM reaper) cannot poison module state for its neighbours; the parent
# sees a missing result trailer and fails closed.
#
# Determinism is preserved on purpose: results are replayed in TEST_ORDER, so
# the log of a parallel run is the log of a serial run (same lines, same order,
# same summary). CI's judgment greps for `✅ ALL <n> CASES PASS` and per-gate
# selftest markers; both survive. TEST_ORDER is the single source of truth for
# what runs — serial and parallel share it, so there is no second list to rot.
# ─────────────────────────────────────────────────────────────────────────────

TEST_ORDER = (
    "test_four_way",
    "test_four_way_derived",
    "test_four_way_entity_self",
    "test_i18n",
    "test_enum_parse",
    "test_boundary",
    "test_legacy",
    "test_debug_leak",
    "test_room_migration",
    "test_process_logging",
    "test_trace_eval",
    "test_extra_days_container",
    "test_stale_closure",
    "test_ime_inset",
    "test_stream_flow_dispatch",
    "test_prefs_listener_holder",
    "test_xproc_boundary",
    "test_thinking_wiring",
    "test_liveness_static",
    "test_claim_release",
    "test_loop_counter_reset",
    "test_budget_stop_break",
    "test_katex_bridge_arity",
    "test_gemini_base_version",
    "test_readonly_mount_write_guard",
    "test_exec_gate",
    "test_scheduled_store_release",
    "test_da1017_gates",
    "test_da1016_gates",
    "test_da1046_gates",
    "test_dv27_gates",
    "test_da1018_gates",
    "test_da1019_gates",
    "test_da1020_gates",
    "test_da1021_gates",
    "test_da1022_gates",
    "test_da1023_gates",
    "test_da1024_gates",
    "test_da1025_gates",
    "test_da1026_gates",
    "test_auto_approve_gates",
    "test_primitive_selftests",
    "test_da1026_arch_gates",
    "test_trace_step_tag",
    "test_dv16_chat_ui_interaction",
    "test_da1017_external_fixtures",
    "test_real_repo",
)

RESULT_TAG = "@@TEST_RESULT@@"


def _resolve(name):
    return getattr(sys.modules[__name__], name)


def _run_single(name):
    """Child mode: run ONE test in a fresh interpreter.

    Prints the test's captured output verbatim (so a parent replay reads like
    the serial run), then a machine-readable trailer carrying the pass/fail
    deltas. Exit 0 whenever a trailer was produced — the parent judges by the
    trailer, not the exit code; a child that dies hard (killed, OOM) produces
    no trailer, which the parent treats as a failure.
    """
    if name not in TEST_ORDER:
        print("test_scan.py: unknown test %r" % name)
        return 2
    pass_before, fail_before = PASS, FAIL
    buf = io.StringIO()
    err = ""
    with contextlib.redirect_stdout(buf):
        try:
            _resolve(name)()
        except BaseException as exc:  # a raising fixture is a failure, never a skip
            err = "%s: %s" % (type(exc).__name__, exc)
    out = buf.getvalue()
    sys.stdout.write(out)
    if out and not out.endswith("\n"):
        sys.stdout.write("\n")
    trailer = "%s %s %d %d" % (RESULT_TAG, name, PASS - pass_before, FAIL - fail_before)
    if err:
        trailer += " err:" + base64.b64encode(err.encode("utf-8")).decode("ascii")
    print(trailer)
    return 0


def _child_argv(name, root_args):
    return [sys.executable, os.path.abspath(__file__), "--single", name] + list(root_args)


def _run_parallel(jobs, root_args):
    """Run TEST_ORDER across `jobs` child interpreters; replay in order.

    Progress goes to stderr (flushed per unit) so the caller sees liveness
    even though stdout is replayed in full only at the end — that replay is
    what keeps a parallel log byte-identical to a serial one.
    """
    global PASS, FAIL
    print("(fixtures: %d workers; results replayed in declaration order)" % jobs,
          file=sys.stderr, flush=True)
    results = {}
    lock = threading.Lock()
    queue = list(TEST_ORDER)
    done_count = [0]

    def worker():
        while True:
            with lock:
                if not queue:
                    return
                name = queue.pop(0)
            try:
                proc = subprocess.run(
                    _child_argv(name, root_args),
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                )
                text = proc.stdout.decode("utf-8", "replace")
            except OSError as exc:
                results[name] = (None, None, "scheduler: %s: %s" % (type(exc).__name__, exc))
                continue
            trailer = None
            for line in reversed(text.splitlines()):
                if line.startswith(RESULT_TAG + " "):
                    trailer = line
                    break
            if trailer is None:
                # Hard death (killed / OOM / interpreter crash): fail closed.
                results[name] = (None, None,
                                 "no result trailer (exit %s, tail: %r)"
                                 % (proc.returncode, text[-160:]))
                continue
            parts = trailer.split(" ")
            passed, failed = int(parts[2]), int(parts[3])
            err = ""
            if len(parts) > 4 and parts[4].startswith("err:"):
                err = base64.b64decode(parts[4][4:]).decode("utf-8", "replace")
            body = text[: text.rindex(trailer)]
            results[name] = (body, (passed, failed), err)
            with lock:
                done_count[0] += 1
                print("(fixtures: %d/%d %s)" % (done_count[0], len(TEST_ORDER), name),
                      file=sys.stderr, flush=True)

    threads = [threading.Thread(target=worker) for _ in range(jobs)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    missing = [n for n in TEST_ORDER if n not in results]
    for name in TEST_ORDER:
        body, deltas, err = results.get(name, (None, None, "scheduler: no result"))
        if body:
            sys.stdout.write(body)
            if not body.endswith("\n"):
                sys.stdout.write("\n")
        if deltas is not None:
            PASS += deltas[0]
            FAIL += deltas[1]
        if err:
            FAIL += 1
            print("  ❌ %s: %s" % (name, err))
    if missing:
        FAIL += len(missing)
        for name in missing:
            print("  ❌ %s: no result (worker lost)" % name)


def main():
    print("╔══════════════════════════════════════════════════╗")
    print("║  Scan Self-Test (fixture ground truth)          ║")
    print("╚══════════════════════════════════════════════════╝")
    if JOBS > 1:
        _run_parallel(JOBS, _ROOTS)
    else:
        for name in TEST_ORDER:
            _resolve(name)()
    print("")
    print(f"{'❌ FAILURES: ' + str(FAIL) if FAIL else '✅ ALL ' + str(PASS) + ' CASES PASS'}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    if SINGLE:
        sys.exit(_run_single(SINGLE))
    sys.exit(main())
