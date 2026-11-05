import pathlib
f = pathlib.Path("/var/minis/shared/work/meta-audit-1105b/openai-responses/src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt")
s = f.read_text()
old = "if (callId.isNotEmpty() && name.isNotEmpty()) {"
new = "if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {"
assert old in s
f.write_text(s.replace(old, new, 1))
