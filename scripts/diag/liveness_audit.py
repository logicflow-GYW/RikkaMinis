#!/usr/bin/env python3
"""Liveness audit — which declared log probes actually appear in a log window?

    python3 scripts/diag/liveness_audit.py --src <main/java> --logs <log dir>

Background: the app writes TWO independent channels into files/logs/*.log

  1. direct   `[HH:MM:SS.mmm] [LEVEL] [category] msg`   <- AppLogger.* (tag Minis.*)
  2. captured `[LOGCAT] MM-DD HH:MM:SS.mmm L/Tag(pid): msg` <- every other tag

Scanning only one channel reports a live ruler as dead (OffloadRssProbe lives
in channel 2 only).  This script scans BOTH and diffs the union against the
probes declared in the Kotlin sources.

Reading the output:
  * hit > 0                -> alive.
  * hit > 0 + PENDING...   -> a pending-install probe just landed in a build:
                              delete its RARE entry (see below).
  * hit == 0 + "rare/..."  -> declared on a path that needs a scenario which
                              did not occur in this window (see RARE below).
  * hit == 0 otherwise     -> candidate dead ruler: either the branch is
                              unreachable, or BuildConfig.DEBUG-gated.

Also beware: the agent's own tool output is captured into the same file
(`[LOGCAT] ... ToolChain[VM]`), so never count keywords by hand — this script
only counts channel-format lines, which the echo cannot forge.

Exit status is 0 unless --strict is given, in which case a *new* dead ruler
(not listed in RARE) fails the run.
"""
import argparse
import glob
import os
import re
import sys
from collections import Counter

# Declared-but-not-yet-observed probes with a KNOWN reason. Keeping this list
# here is what stops the next audit from re-litigating them.
#
# Entries prefixed "pending-install:" describe probes wired in a build the
# device has not installed yet. They stay quiet until a hit appears; once one
# does, the report says to PRUNE the entry — a RARE line that outlives its
# reason is a dead ruler holding a permission slip, exactly what this audit
# exists to catch.
RARE = {
    # (kind, name): reason
    ("step", "loadSession.skipped"): "only fires in crash-loop safe mode",
    ("step", "coldPrewarm.done"): "cold-open prewarm only (see census maxRows)",
    ("cat", "Thinking"): "provider-specific (Gemini) branch",
    ("cat", "MemoryPressureGate"): "only logs on a SOFT/HARD level transition",
    ("cat", "ChatVMRouting"): "load-balance rotation; LB groups disabled",
    ("cat", "StreamRender"): "live-tail parse; covered by RenderCensus",
    ("step", "coldParse.offmain"): "frozen cache miss; covered by RenderCensus",
    # ("cat", "RenderCensus") pruned 2026-09-22: the pending-install entry was
    # wired in fix/liveness-followups-0914 and produced its FIRST hits in the
    # 09-22 window (hit=12) — the audit itself flagged "prune its RARE entry"
    # and this is that prune. This is the documented lifecycle working end to
    # end: wire → first hit → audit says prune → entry removed.
    ("step", "buildFlatChatItems.progress"): "full rebuild of a 100+ message session",
}
# Removed 2026-09-22: ("step", "buildFlatChatItems.ledgerReseed") — the probe
# no longer exists anywhere in main source (the ledger reseed path was deleted
# with the flat-row pipeline). A RARE entry that outlives its probe is a dead
# ruler holding a permission slip; liveness_static_guard now fails on exactly
# this shape, so a stale entry can never quietly rot here again.


# Module-level so liveness_static_guard can inject a drifted pattern in its
# DIRTY-C self-test (proving the completeness check actually fires).
DECLARED_CAT_PAT = re.compile(r"AppLogger\.(?:info|warning|error|debug)\(\s*([^,]+?)\s*,")


def declared(src):
    const, cats, tags, steps, events = {}, {}, {}, {}, {}
    files = [os.path.join(r, n) for r, _, ns in os.walk(src) for n in ns if n.endswith(".kt")]
    for p in files:
        t = open(p, errors="replace").read()
        for m in re.finditer(r'\b(?:const )?val\s+([A-Za-z_]\w*)\s*(?::\s*String)?\s*=\s*"([^"\n]{1,60})"', t):
            const.setdefault(m.group(1), m.group(2))

    def res(e):
        e = e.strip()
        if e.startswith('"'):
            return e[1:e.index('"', 1)]
        k = e.split(".")[-1]
        return const.get(k, "<%s>" % k)

    def where(p, t, i):
        return "%s:%d" % (os.path.basename(p), t[:i].count("\n") + 1)

    for p in files:
        t = open(p, errors="replace").read()
        # `debug` MUST be enumerated: every AppLogger.debug call sinks to the
        # debug-<date>.log channel (see observed()), so a debug probe missing
        # from this list is invisible to the census BY CONSTRUCTION. The
        # liveness_static_guard CI gate fails if a public AppLogger log method
        # is added without extending this alternation.
        for m in DECLARED_CAT_PAT.finditer(t):
            cats.setdefault(res(m.group(1)), []).append(where(p, t, m.start()))
        for m in re.finditer(r"\bLog\.[diwev]\(\s*([^,]+?)\s*,", t):
            tags.setdefault(res(m.group(1)), []).append(where(p, t, m.start()))
        for m in re.finditer(r'PerfLongCtx\.(?:step|end)\(\s*[^,]+,\s*"([^"]+)"', t):
            steps.setdefault(m.group(1), []).append(where(p, t, m.start()))
        for m in re.finditer(r'onEvent\(\s*"([^"]+)"', t):
            events.setdefault(m.group(1), []).append(where(p, t, m.start()))
    return cats, tags, steps, events


def observed(logs):
    cats, tags, steps, events = Counter(), Counter(), Counter(), Counter()
    # Two lessons from the 09-21 evidence pipeline (D2/D3), applied here:
    #  - the debug channel (`debug-*.log`) holds EVERY DEBUG line the app
    #    produces — skipping it permanently hides the muted categories;
    #  - rotated files (`*.log.1`) exist whenever a daily file crosses its
    #    size cap — a `*.log`-only glob reads "0 hits" on exactly the days
    #    with the most activity.
    main_logs = []
    for pat in ("minis-*.log", "minis-*.log.*", "debug-*.log", "debug-*.log.*"):
        main_logs.extend(glob.glob(os.path.join(logs, pat)))
    for f in sorted(set(main_logs)):
        for l in open(f, errors="replace"):
            if l.startswith("[LOGCAT]"):
                m = re.match(r"\[LOGCAT\] \d\d-\d\d [\d:.]+ \w/([^(\s]+)\(", l)
                if m:
                    tags[m.group(1)] += 1
            else:
                m = re.match(r"\[[\d:.]+\] \[[A-Z]+\] \[([^\]]+)\]", l)
                if m:
                    cats[m.group(1)] += 1
                m2 = re.search(r"step=([\w.]+)", l)
                if m2:
                    steps[m2.group(1)] += 1
    for f in sorted(set(glob.glob(os.path.join(logs, "memspike-*.log")) +
                        glob.glob(os.path.join(logs, "memspike-*.log.*")))):
        for l in open(f, errors="replace"):
            # MemorySpikeRecorder writes a THIRD shape: `HH:MM:SS.mmm [event] k=v ...`
            # (the bracketed name is the event, not a category).
            m = re.match(r"[\d:.]+\s+\[([^\]]+)\]", l)
            if m:
                events[m.group(1)] += 1
    return cats, tags, steps, events


def report(kind, decl, obs, dead):
    print("\n### %s: declared vs observed" % kind)
    for k in sorted(decl, key=lambda x: -obs.get(x, 0)):
        hit = obs.get(k, 0)
        note = ""
        if hit == 0:
            if "$" in k or "{" in k:
                note = "interpolated name; matched by prefix in the log"
            else:
                reason = RARE.get((kind_key(kind), k))
                if reason:
                    note = "rare: %s" % reason
                else:
                    note = "DEAD? %s" % decl[k][0]
                    dead.append((kind_key(kind), k, decl[k][0]))
        else:
            reason = RARE.get((kind_key(kind), k))
            if reason and reason.startswith("pending-install:"):
                note = "PENDING-INSTALL probe now alive — prune its RARE entry"
        print("  %-32s hit=%-7d %s" % (k, hit, note))
    print("  declared=%d observed=%d" % (len(decl), sum(1 for k in decl if obs.get(k, 0))))


def kind_key(kind):
    return {"AppLogger category": "cat", "Log tag (logcat)": "tag",
            "PerfLongCtx step": "step", "onEvent": "event"}[kind]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="src/android/app/src/main/java")
    ap.add_argument("--logs", default="/var/minis/logs")
    ap.add_argument("--strict", action="store_true")
    a = ap.parse_args()
    cats, tags, steps, events = declared(a.src)
    ocats, otags, osteps, oevents = observed(a.logs)
    print("declared: cat=%d tag=%d step=%d event=%d" % (len(cats), len(tags), len(steps), len(events)))
    print("observed: cat=%d tag=%d step=%d event=%d" % (len(ocats), len(otags), len(osteps), len(oevents)))
    dead = []
    report("PerfLongCtx step", steps, osteps, dead)
    report("AppLogger category", cats, ocats, dead)
    report("Log tag (logcat)", tags, otags, dead)
    report("onEvent", events, oevents, dead)
    print("\n== %d unexplained silent ruler(s)" % len(dead))
    return 1 if (dead and a.strict) else 0


if __name__ == "__main__":
    sys.exit(main())
