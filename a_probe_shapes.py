"""Replicate every source-shape assertion (R5C3 wiring test + the two updated
StopCleanupPlanTest pins) against the real tree, so a third red round is caught
locally instead of on CI."""
import re

B = "src/android/app/src/main/java/com/rikkaminis/app/"
T = "src/android/app/src/test/java/com/rikkaminis/app/"

def rd(p):
    return open(p, encoding="utf-8").read()

vm = rd(B + "ui/chat/ChatViewModel.kt")
queue = rd(B + "ui/chat/ChatQueueInterruption.kt")
retry = rd(B + "ui/chat/ChatRetryOps.kt")
routing = rd(B + "ui/chat/ChatModelRouting.kt")
prompt = rd(B + "ui/chat/ChatPromptAndTools.kt")
plan = rd(B + "ui/chat/StopCleanupPlan.kt")
detector = rd(B + "agent/ToolLoopDetector.kt")

fails = []

def chk(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond:
        fails.append(name)

# C-C-05
probe = vm.find("val editingSend = _editingMessageId.value != null")
chk("editingSend probe", probe > 0)
chk("gate1 guarded", "if (!editingSend && !checkContextBeforeSend())" in vm)
c = vm.find("if (!editingSend) maybeTriggerAutoCompact()")
a = vm.find("if (!editingSend) awaitAutoCompactIfNeeded()")
chk("gate2 after probe", c > probe)
chk("gate3 after probe", a > probe)
stash = vm.find("pendingExhaustedText = trimmed")
win = vm[max(0, stash - 700):stash]
chk("stash clears edit id", "_editingMessageId.value = null" in win)
chk("stash clears preEditAttach", "_preEditAttachments.value = null" in win)

# C-C-04①
h = vm.find("internal suspend fun ChatViewModel.dropCompactMarkerIfAnchoredOnRow(")
chk("helper defined", h > 0)
body = vm[h:h + 900]
chk("helper guard", "if (!compactMarkerAnchoredOnRow(_cachedLatestMarker, truncatedRowId)) return" in body)
chk("helper deletes", "dao.deleteCompactMarkers(" in body)
call = vm.find("dropCompactMarkerIfAnchoredOnRow(sid, row.id)")
chk("helper called before def", 0 < call < h)

# C-C-04②
for nm, src in (("ChatRetryOps", retry), ("ChatModelRouting", routing)):
    rb = src.find("rollbackIncompleteTurn()")
    mk = src.find("dropCompactMarkerIfRewound(")
    pr = src.find("resetContextPressureState()")
    chk(f"{nm} rollback", rb > 0)
    chk(f"{nm} marker after rollback", mk > rb)
    chk(f"{nm} pressure after rollback", pr > rb)

# C-C-06 write side
w = vm.find("val reminder = CONTINUATION_REMINDER_TEXT")
chk("reminder write", w > 0)
slot = vm.rfind("SessionConcurrencyManager.acquireSlot(activeSessionId)", 0, w)
chk("write after slot", 0 < slot < w)
claim = vm.find("resume streamJob aborted after slot (claim revoked")
chk("claim check between", slot < claim < w)
chk("literal only in plan", "The user stopped the previous response" not in vm and "The user stopped the previous response" in plan)

# C-C-06 cleanup side
chk("tail probe", "isContinuationReminderTail(it.role, it.content)" in queue)
chk("plan honoured", "if (stopPlan.dropContinuationReminder) dropContinuationReminderTail()" in queue)
chk("remover exists", "internal fun ChatViewModel.dropContinuationReminderTail()" in queue)
chk("no-partial-delete guard", "is not the DB tail — kept (no partial delete)" in queue)
# updated pins
chk("planner call 3-arg", "val stopPlan = planStopCleanup(last.toolBlocks, historyEndsWithAssistant, reminderTail)" in queue)
case1 = queue.find("if (hadInflightTools) {")
planner = queue.find("val stopPlan = planStopCleanup(last.toolBlocks, historyEndsWithAssistant, reminderTail)")
chk("case1 before planner", 0 < case1 < planner)
chk("arms intact", "if (stopPlan.dropPlaceholder) {" in queue and "val partialText = stopPlan.persistText" in queue)
chk("kept-bubble resume", "if (stopPlan.canResume) _canResume.value = true" in queue)

# C-C-07① (rewritten)
gate = prompt.find("if (!config.isSubagent) {")
rec = prompt.find("runCatching { skillRepository?.recordSkillUse(skill.id) }")
chk("kind gate", gate > 0)
chk("usage after kind gate", rec > gate)
lam = prompt.find("executeSubTool = { name, subArgs ->")
exec_ = prompt.find("executeSubagentTool(name, subArgs", lam)
chk("lambda found", lam > 0 and exec_ > lam)
seg = prompt[lam:exec_]
chk("deadline in lambda", "run deadline reached — sub-agent tool call refused" in seg)
chk("tool_calls consume in lambda", "t7ConsumeAndTrace(AgentTraceRecorder.DIMENSION_TOOL_CALLS)" in seg)
chk("dispatch-level throw in lambda", "throw SubagentBudgetExhaustedException" in seg)

# C-C-07②
pred = prompt.find("fun titleFailureIsFinal(): Boolean")
nul = prompt.find("if (provider == null) {")
chk("predicate before null branch", 0 < pred < nul)
win2 = prompt[nul:nul + 1600]
chk("fallback gated", "if (!titleFailureIsFinal()) {" in win2)
chk("gated fallback body", 'applyFallbackTitleFromFirstMessage("no provider available")' in win2)

# C-C-09 wording (parser contract)
chk("new wording present", "unknown tool '" in detector and "attempted" in detector)
m = re.search(r"unknown tool '\$toolName' attempted", detector)
chk("check() message regex-visible", m is not None)

# collector replica (ShellBudgetStopSymmetryTest)
lines = prompt.split("\n")
gates2 = []
for i, line in enumerate(lines):
    mm = re.search(r"DIMENSION_([A-Z_]+)", line)
    if not mm or "t7ConsumeAndTrace" not in line:
        continue
    depth = 0
    body = []
    j = i
    while j < len(lines):
        l = lines[j]
        body.append(l)
        depth += l.count("{") - l.count("}")
        if j > i and depth <= 0:
            break
        j += 1
    text = "\n".join(body)
    if "SubagentBudgetExhaustedException" in text:
        continue
    gates2.append((mm.group(1).lower(), "t7BudgetStopReason = " in text,
                   "t7Reduce(AgentRunEvent.ProcessInterrupted(" in text))
chk("run-level gates == 2", len(gates2) == 2)
chk("dims == shell/tool", sorted(g[0] for g in gates2) == ["shell_commands", "tool_calls"])
chk("all terminal", all(g[1] and g[2] for g in gates2))

print("\nFAILS:", fails or "none")
raise SystemExit(1 if fails else 0)
