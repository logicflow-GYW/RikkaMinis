import pathlib, re
f = pathlib.Path("/var/minis/shared/work/meta-audit-1105b/openai-responses/src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt")
s = f.read_text()
i = s.index('type == "response.function_call_arguments.done" -> {')
j = s.index('type == "response.function_call_arguments.delta" -> {')
f.write_text(s[:i] + s[j:])
