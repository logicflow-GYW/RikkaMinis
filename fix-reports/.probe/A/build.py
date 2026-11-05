#!/usr/bin/env python3
"""Mechanically extract production decision slices from OpenAIProvider.kt and
assemble the shadow probe. No decision is hand-copied: each slice is copied
verbatim from the real file and dedented only."""
import subprocess, pathlib

BASE = pathlib.Path("/var/minis/shared/work/meta-audit-1105b/openai-responses/src/android/app/src/main/java/com/rikkaminis/app")
SRC = BASE / "provider/openai/OpenAIProvider.kt"
TPL = pathlib.Path("/tmp/probeA2/ProbeA.template.kt")
TGT = pathlib.Path("/tmp/probeA2/src")

lines = SRC.read_text().splitlines()

def idx(anchor, start=0):
    for i in range(start, len(lines)):
        if anchor in lines[i]:
            return i
    raise SystemExit("anchor not found: %r" % anchor)

def dedent(block):
    block = list(block)
    while block and not block[0].strip(): block.pop(0)
    while block and not block[-1].strip(): block.pop()
    ind = min((len(l) - len(l.lstrip()) for l in block if l.strip()), default=0)
    return "\n".join(l[ind:] if l.strip() else "" for l in block)

def slice_lines(a_anchor, b_anchor):
    a = idx(a_anchor); b = idx(b_anchor, a)
    return dedent(lines[a:b])

def _strip(l):
    out, i, in_s = [], 0, False
    while i < len(l):
        c = l[i]
        if in_s:
            if c == "\\": i += 2; continue
            if c == '"': in_s = False
            i += 1; continue
        if c == '"': in_s = True; i += 1; continue
        if c == "/" and i + 1 < len(l) and l[i + 1] == "/": break
        out.append(c); i += 1
    return "".join(out)

def slice_block(a_anchor):
    a = idx(a_anchor); depth = 0; seen = False
    for j in range(a, len(lines)):
        s = _strip(lines[j])
        depth += s.count("{") - s.count("}")
        if "{" in s: seen = True
        if seen and depth <= 0:
            return dedent(lines[a:j + 1])
    raise SystemExit("unbalanced from %r" % a_anchor)

def optional(anchor, fn):
    """Slice that is expected NOT to exist before the fix; empty string if absent."""
    if not any(anchor in l for l in lines):
        return ""
    return fn()

ACC_FILE = BASE / "provider/openai/OpenAIResponsesToolAccumulator.kt"

def acc_slice(a_anchor, b_anchor):
    """Slice from the dedicated accumulator file (the definitions live there)."""
    acc_lines = ACC_FILE.read_text().splitlines()
    def aidx(anchor):
        for i, l in enumerate(acc_lines):
            if anchor in l:
                return i
        raise SystemExit("acc anchor not found: %r" % anchor)
    a = aidx(a_anchor)
    if b_anchor is None:
        b = len(acc_lines)
    else:
        b = aidx(b_anchor)
        if b < a:
            b = len(acc_lines)
    block = acc_lines[a:b]
    while block and not block[-1].strip():
        block.pop()
    return "\n".join(block)


parts = {
    "@@A1@@": slice_lines('type == "response.output_item.added" -> {', 'type == "response.failed" -> {'),
    "@@A2@@": slice_lines('type == "response.failed" -> {', 'type == "response.output_text.done" -> {'),
    "@@A3@@": slice_lines('val hasRcKey = d.has("reasoning_content")', '// Text content (with think-tag extraction'),
    "@@A4@@": slice_block('if (payload == "[DONE]") {'),
    "@@A5@@": slice_lines('// Emit ToolCallComplete for all accumulated tool calls', '// Final usage summary'),
    "@@DECL_TOOLACC@@": slice_lines('data class ToolCallAccumulator(', 'val toolCallAccumulators ='),
    "@@DECL_RESPACC@@": acc_slice('internal data class ResponsesToolCallAccumulator(', 'internal fun resolveResponsesAccumulator('),
    "@@HELP_ACC@@": acc_slice('internal fun resolveResponsesAccumulator(', None),
    "@@HELP_IDS@@": slice_lines('private fun combineResponsesAPIIds(', 'private fun splitResponsesAPIIds('),
    "@@HELP_MAP@@": slice_block('private fun mapHttpError('),
    "@@HELP_PARSE_CHAT@@": slice_block('private fun parseChatCompletionsUsage('),
    "@@HELP_PARSE_RESP@@": slice_block('private fun parseResponsesAPIUsage('),
    "@@HELP_THINKRESULT@@": slice_lines('private data class ThinkExtractResult(', 'private class ThinkTagState {'),
    "@@HELP_THINKSTATE@@": slice_block('private class ThinkTagState {'),
    "@@HELP_EXTRACT@@": slice_block('private fun extractThinkTags('),
    "@@HELP_FLUSH@@": slice_block('private fun flushThinkTags('),
    "@@HELP_DELTATEXT@@": slice_block('internal fun chatCompletionsDeltaText('),
    "@@HELP_DELTATHINK@@": slice_block('internal fun chatCompletionsDeltaThinkingBlocks('),
}

tpl = TPL.read_text()
for k, v in parts.items():
    if k not in tpl:
        raise SystemExit("template missing marker " + k)
    tpl = tpl.replace(k, v)
for k in parts:
    if k in tpl:
        raise SystemExit("marker left unreplaced: " + k)

TGT.mkdir(parents=True, exist_ok=True)
(TGT / "ProbeA.kt").write_text(tpl)

copies = {
    BASE / "provider/JsonExt.kt": "JsonExt.kt",
    BASE / "provider/HttpErrorExtraction.kt": "HttpErrorExtraction.kt",
    BASE / "provider/ToolInputLogStep.kt": "ToolInputLogStep.kt",
    BASE / "provider/openai/OpenAiStreamSignals.kt": "OpenAiStreamSignals.kt",
    BASE / "provider/openai/ThinkTagScanning.kt": "ThinkTagScanning.kt",
    BASE / "data/model/LLMError.kt": "LLMError.kt",
}
for s, name in copies.items():
    subprocess.run(["cp", str(s), str(TGT / name)], check=True)
print("assembled %d lines; empty-optional=%s" % (
    len(tpl.splitlines()),
    [k for k, v in parts.items() if not v]))
