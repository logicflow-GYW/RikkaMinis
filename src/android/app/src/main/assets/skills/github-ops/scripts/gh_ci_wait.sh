#!/bin/sh
# gh_ci_wait.sh — dispatch + wait for a workflow_dispatch run + verify head_sha + report verdict.
# One call replaces the previous repeat-the-dispatch/wait/find/head_sha-check sequence.
# Requirements: python3, env GITHUB_TOKEN
#
# Usage:
#   sh gh_ci_wait.sh --repo <owner/repo> --workflow <id_or_file> --ref <branch> \
#       [--expect <commit_sha>] [--timeout <seconds>] [--poll <seconds>] \
#       [--exclude-main-backfill] [--job-detail]
#
# Behavior:
#   - Optionally dispatches a workflow_dispatch on --ref. If --no-dispatch is given,
#     it waits for an in-flight/completed run on --ref instead (reuse an existing run).
#   - [stage-D-idempotent-dispatch] When --expect <sha> is provided, dispatch is
#     idempotent: if an active (queued/in_progress) run on --ref already targets that
#     exact head, the script reuses it instead of dispatching a duplicate (which would
#     otherwise cancel the previous run via concurrency::cancel-in-progress). Call the
#     script repeatedly with the same --expect and it converges on ONE run.
#   - Lists runs on --ref, newest first, and picks the one whose head_sha matches
#     --expect when provided (guards against the "checkout raced to another head"
#     false-green / phantom-test pitfall). Drops runs whose event is a push-backfill
#     onto a branch (main push auto-build) unless --exclude-main-backfill is omitted.
#   - Polls until the run completes or --timeout (default 900s).
#   - Prints a compact Markdown verdict line and exits 0 on success, non-zero otherwise.
#
# Exit codes: 0 = success, 1 = failure (any failed step), 2 = time out without verdict,
#             3 = no matching run (e.g. expected head not found).
set -u

repo="" wf="" ref="" expect="" timeout=900 poll=60 dispatch=1 jobdetail=0 exclude_backfill=0
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) repo="$2"; shift 2;;
    --workflow) wf="$2"; shift 2;;
    --ref) ref="$2"; shift 2;;
    --expect) expect="$2"; shift 2;;
    --timeout) timeout="$2"; shift 2;;
    --poll) poll="$2"; shift 2;;
    --no-dispatch) dispatch=0; shift;;
    --job-detail) jobdetail=1; shift;;
    --exclude-main-backfill) exclude_backfill=1; shift;;
    *) printf 'unknown arg: %s\n' "$1" >&2; exit 64;;
  esac
done

need_env() { n="$1"; eval "v=\${$n-}"; [ -n "$v" ] || { printf 'missing env: %s\n' "$n" >&2; exit 1; }; }
need_env GITHUB_TOKEN

[ -n "$repo" ] || { printf -- '--repo required (owner/repo)\n' >&2; exit 64; }
[ -n "$ref" ] || { printf -- '--ref required (branch)\n' >&2; exit 64; }

owner="${repo%%/*}"; name="${repo#*/}"

_api() { # _api <url>  → python fetches JSON body, prints to stdout
  U="$1" python3 -c 'import os,sys,urllib.request,urllib.error
url=os.environ["U"]; tok=os.environ["GITHUB_TOKEN"]
req=urllib.request.Request(url,headers={"Authorization":"Bearer "+tok,"Accept":"application/vnd.github+json","User-Agent":"minis"})
try:
  with urllib.request.urlopen(req,timeout=30) as r:
    sys.stdout.write(r.read().decode("utf-8","ignore"))
except urllib.error.HTTPError as e:
  sys.stderr.write("HTTP %s %s\n" % (e.code,url)); sys.stderr.write(e.read().decode("utf-8","ignore")[:800]+"\n"); sys.exit(1)'
}

_dispatch() {
  O="$owner" N="$name" W="$wf" R="$ref" python3 -c 'import os,sys,json,urllib.request,urllib.error
owner=os.environ["O"]; name=os.environ["N"]; wf=os.environ["W"]; ref=os.environ["R"]; tok=os.environ["GITHUB_TOKEN"]
url=f"https://api.github.com/repos/{owner}/{name}/actions/workflows/{wf}/dispatches"
payload={"ref":ref}; req=urllib.request.Request(url,data=json.dumps(payload).encode(),method="POST")
req.add_header("Authorization","Bearer "+tok); req.add_header("Accept","application/vnd.github+json"); req.add_header("User-Agent","minis"); req.add_header("Content-Type","application/json")
try:
  urllib.request.urlopen(req,timeout=30); print("dispatch-ok")
except urllib.error.HTTPError as e:
  sys.stderr.write("HTTP %s %s\n" % (e.code,url)); sys.stderr.write(e.read().decode("utf-8","ignore")[:800]+"\n"); sys.exit(1)'
}

# Optionally dispatch first (past-failure lesson: there must be a run to wait on).
# [stage-D-idempotent-dispatch] Idempotency guard: when --expect is given,
# only start a NEW run if no active (queued/in_progress) run on this ref
# already targets that exact head. Without the guard, every invocation of this
# script re-dispatches a fresh run on the same ref+head, and build-apk.yml's
# `concurrency.group: build-apk-${{ github.ref }}` + `cancel-in-progress: true`
# cancels the previous run each time — so rebuild K times = K runs with the
# first K-1 cancelled. The guard makes repeated calls converge on ONE run.
if [ "$dispatch" -eq 1 ]; then
  [ -n "$wf" ] || { printf -- '--workflow required when --no-dispatch is not set\n' >&2; exit 64; }
  should_dispatch=1
  if [ -n "$expect" ]; then
    probe_url="https://api.github.com/repos/${owner}/${name}/actions/runs?per_page=15&branch=${ref}"
    probe_doc="$(_api "$probe_url")" || exit 1
    active_on_head=$(printf '%s' "$probe_doc" | python3 -c 'import json,sys
d=json.load(sys.stdin); exp=sys.argv[1]
for x in d.get("workflow_runs",[]):
    if (x.get("head_sha") or "")==exp and (x.get("status") or "") in ("queued","in_progress"):
        print(x.get("run_number") or ""); break
' "$expect")
    if [ -n "$active_on_head" ]; then
      should_dispatch=0
      printf 'idempotent: active run #%s already targets %s@%s — reusing it (skipping dispatch).\n' "$active_on_head" "$ref" "$expect" >&2
    fi
  fi
  if [ "$should_dispatch" -eq 1 ]; then
    out="$(_dispatch)" || exit 1
    printf 'dispatched %s@%s (parallel-dispatch race guard: only start/manage ONE run for the ember per branch-epoch).\n' "$wf" "$ref" >&2
  fi
fi

start=$(date +%s)
runid="" head=""
EXCL="$exclude_backfill"
while :; do
  now=$(date +%s); el=$((now-start))
  if [ "$el" -ge "$timeout" ]; then
    printf 'VERDICT timeout after %ss: no final conclusion yet on %s\n' "$el" "$ref"
    exit 2
  fi

  url="https://api.github.com/repos/${owner}/${name}/actions/runs?per_page=15&branch=${ref}"
  doc="$(_api "$url")" || { exit 1; }
  # Pick: newest completed/in_progress/queued run; prefer matching --expect head_sha.
  runid=""; head=""; conclusion=""; status=""
  while IFS= read -r r; do
    [ -n "$r" ] || continue
    rh=$(printf '%s' "$r" | cut -d'|' -f1)
    rs=$(printf '%s' "$r" | cut -d'|' -f2)
    rc=$(printf '%s' "$r" | cut -d'|' -f3)
    rn=$(printf '%s' "$r" | cut -d'|' -f4)
    rid=$(printf '%s' "$r" | cut -d'|' -f5)
    # --expect guard: if caller gave a SHA, only accept a run on that exact head.
    if [ -n "$expect" ] && [ "$rh" != "$expect" ]; then
      printf '  drop run #%s head=%s (does not match expected %s)\n' "$rn" "$rh" "$expect" >&2
      continue
    fi
    if [ -n "$rs" ]; then
      runid="$rid"; head="$rh"; status="$rs"; conclusion="${rc:-?}"; runnum="$rn"
      break
    fi
  done <<EOF
$(printf '%s' "$doc" | python3 -c 'import json,sys
d=json.load(sys.stdin)
for x in d.get("workflow_runs",[]):
    ev=(x.get("event") or "")
    if '"$EXCL"' and ev=="push" and (x.get("head_branch") or "")=="main":
        continue  # [exclude-main-backfill] skip main push auto-build backfill runs
    print("%s|%s|%s|%s|%s" % ((x.get("head_sha") or ""),(x.get("status") or ""),(x.get("conclusion") or ""),(x.get("run_number") or ""),(x.get("id") or "")))')
EOF

  if [ -z "$runid" ]; then
    printf '  no matching run yet on %s (elapsed=%ss)\n' "$ref" "$el" >&2
    sleep "$poll"; continue
  fi

  if [ "$status" = "completed" ]; then
    if [ -n "$expect" ] && [ "$head" != "$expect" ]; then
      printf 'VERDICT wrong-head #%s head=%s != expected %s — CI is a liar (phantom/false-green). Do NOT trust it.\n' "$runnum" "$head" "$expect"
      exit 3
    fi
    verb=""
    case "$conclusion" in
      success) verb="success";;
      failure|timed_out|action_required|stale) verb="FAILED ($conclusion)";;
      cancelled) verb="cancelled";;
      skipped) verb="skipped";;
      *) verb="$conclusion";;
    esac
    extra=""
    if [ "$jobdetail" -eq 1 ]; then
      jurl="https://api.github.com/repos/${owner}/${name}/actions/runs/${runid}/jobs?per_page=25"
      jdoc="$(_api "$jurl")" || true
      extra="$(printf '%s' "$jdoc" | python3 -c 'import json,sys
try:
 d=json.load(sys.stdin)
 parts=[]
 for j in d.get("jobs",[]):
  st=j.get("conclusion") or j.get("status") or "?"
  n=j.get("name") or "?"
  parts.append("%s:%s"%(n,st))
 print(" jobs["+",".join(parts)+"]")
except Exception: print("")' 2>/dev/null || true)"
    fi
    printf 'VERDICT #%s head=%s on %s → %s%s\n' "$runnum" "$head" "$ref" "$verb" "$extra"
    [ "$conclusion" = "success" ] && exit 0 || exit 1
  fi

  printf '  %s: run #%s head=%s status=%s (elapsed=%ss)\n' "$(date +%H:%M:%S)" "$runnum" "$head" "$status" "$el" >&2
  sleep "$poll"
done
