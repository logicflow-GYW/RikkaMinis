import pathlib
f = pathlib.Path("/var/minis/shared/work/meta-audit-1105b/openai-responses/src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt")
s = f.read_text()
old = 'val argsStr = if (authoritative.isNotEmpty()) authoritative else acc.args.toString()'
new = 'val argsStr = item.optString("arguments", acc.args.toString())'
assert old in s
f.write_text(s.replace(old, new))
