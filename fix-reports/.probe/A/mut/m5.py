import pathlib
f = pathlib.Path("/var/minis/shared/work/meta-audit-1105b/openai-responses/src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt")
s = f.read_text()
anchor = "for (thinkingText in chatCompletionsDeltaThinkingBlocks(d)) {"
i = s.index(anchor)
j = s.index("sawReasoningField = true", i)
f.write_text(s[:j] + "/*MUT*/" + s[j+len("sawReasoningField = true"):])
