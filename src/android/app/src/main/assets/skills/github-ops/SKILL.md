---
name: github-ops
description: Trigger on ANY Git/GitHub op, workflow, or 'how to use GitHub' — clone/branch/commit/push/pull/merge/rebase/tag, issues/labels/releases/actions/PRs/forks, CI, delete/restore. Not pure browsing.
version: 1.0.2
---
## Objectives

Codify GitHub/Git "basic operations" and common collaboration workflows into:

1) A clear command quick reference (explanation + when to use)
2) Executable one-click scripts (to avoid repetitive manual steps)
3) Common outputs that are "numbered" whenever possible, so the user can reply with a number to choose an item directly, such as from a repository list

## Security and Constraints (Must Be Followed)

| No. | Rule | Reason |
|---:|---|---|
| 1 | **Do not output tokens**: No command may echo or print `$GITHUB_TOKEN` to stdout | Prevent leaks |
| 2 | **Require a second confirmation for dangerous operations**: deleting branches, emptying directories, forced overwrites, force pushes, and direct pushes to main | These operations are hard to reverse |
| 3 | Use "branch + PR" collaboration by default; only "push directly to main" when the user explicitly requests it | Reduce the risk of damaging the main branch |
| 4 | Run `git status` before push/pull | Prevent accidental commits or overwrites |

## How to Run

- **Script entry point**: `sh /var/minis/skills/github-ops/scripts/gh_sync.sh <command> [options]`

> The script runs in the "current Git repository directory" and must be run inside a repository.

## Quick Reference for Basic Git/GitHub Operations (Explanation + Corresponding Script)

| No. | Category | Operation | One-sentence explanation | Common command | Script support |
|---:|---|---|---|---|---|
| 1 | Initialize | init | Turn the current directory into a Git repository | `git init` | `init` |
| 2 | Get code | clone | Download a repository from a remote to local | `git clone <url>` | `clone` |
| 3 | Remote | remote | Manage remotes such as origin/upstream | `git remote -v/add/set-url/remove` | `remotes/add-remote/add-upstream/set-remote-url/remove-remote` |
| 4 | Branch | branch | View, create, or delete branches | `git branch -a/-d/-D` | `branches/create-branch/delete-branches` |
| 5 | Switch | checkout/switch | Switch to a branch | `git switch <b>` | `checkout` |
| 6 | View changes | status/diff/log | View working tree changes, diffs, or history | `git status` `git diff` `git log` | `status/diff/log` |
| 7 | Stage | add | Add changes to the staging area | `git add -A` | `add` |
| 8 | Commit | commit | Package the staging area into a commit | `git commit -m "..."` | `commit` |
| 9 | Sync | fetch/pull/push | Fetch, merge, or push commits | `git fetch` `git pull` `git push` | `fetch/pull/push/push-main` |

> ⚠️ **分支 CI 必须手动 dispatch（易忘，已复发 3+ 次）**：`build-apk.yml` 的 `on: push` 限定 `branches: [main]`——**非 main 分支的 push 永远不会触发 CI**。推完分支后 CI 查不到 run 不是 webhook 延迟，是根本没触发。标准动作：`sh gh_sync.sh gh-actions-dispatch --repo logicflow-GYW/RikkaMinis --workflow build-apk.yml --ref <branch>`（Publish 步骤有 main 门控，不会污染 release 资产）。`gh_sync.sh push --branch <非main>` 现在会在输出里自动带上这条提醒。
|10| Merge | merge/rebase | Merge branch history | `git merge` `git rebase` | `merge/rebase` (use with caution) |
|11| Stash | stash | Temporarily set aside uncommitted changes | `git stash` | `stash` |
|12| Tag | tag | Add a version tag to a commit | `git tag` | `tag` |
|13| Submodule | submodule | Manage subrepository dependencies | `git submodule` | `submodule` |
|15| GitHub platform | issues/labels/milestones/releases/actions | Manage platform objects through the GitHub API | (API) | `gh-issues-list` and others (see below) |

> Note: The script is intended to "turn common basic operations into reusable commands." For complex rebases or conflicts, using an interactive terminal is still recommended.

## GitHub Platform Operations (API)

> Unified requirement: env `GITHUB_TOKEN` is required.

| No. | command | Purpose |
|---:|---|---|
| 1 | `gh-issues-list --repo <owner/repo> [--state open|closed|all]` | List issues |
| 2 | `gh-issue-create --repo <owner/repo> --title <t> [--body <b>]` | Create an issue |
| 3 | `gh-issue-close --repo <owner/repo> --number <n>` | Close an issue |
| 4 | `gh-labels-list --repo <owner/repo>` | List labels |
| 5 | `gh-label-create --repo <owner/repo> --name <n> [--color <rrggbb>] [--description <d>]` | Create a label |
| 6 | `gh-milestones-list --repo <owner/repo> [--state open|closed|all]` | List milestones |
| 7 | `gh-milestone-create --repo <owner/repo> --title <t> [--description <d>] [--due <YYYY-MM-DD>]` | Create a milestone |
| 8 | `gh-releases-list --repo <owner/repo>` | List releases |
| 9 | `gh-release-create --repo <owner/repo> --tag <vX.Y.Z> --name <n> [--body <b>] [--draft true|false] [--prerelease true|false]` | Create a release |
|10| `gh-actions-list --repo <owner/repo>` | List workflows |
|11| `gh-actions-dispatch --repo <owner/repo> --workflow <id_or_file> [--ref <branch>] [--inputs <json>]` | Manually trigger workflow_dispatch |
|12| `gh_ci_wait.sh --repo <owner/repo> --workflow <id_or_file> --ref <branch> [--expect <sha>] [--timeout <s>] [--poll <s>] [--no-dispatch] [--job-detail]` | **Dispatch + wait for the run + verify head_sha + report verdict in one shot** — replaces the manual dispatch→wait→find→check-head→poll→conclude dance. |

## CI waits: use gh_ci_wait.sh (not hand-rolled loops)

Prefer the single-purpose script `scripts/gh_ci_wait.sh` over pasting fresh curl/python polling each time. It encodes the hard-won RikkaMinis CI pitfalls:

- **One call does everything**: optionally dispatches, then waits for the run, then verifies `head_sha`, then prints a one-line Markdown verdict and exits 0/1/2/3.
- **`--expect <sha>` = anti false-green guard**: only accepts a run whose `head_sha` equals your pushed commit, and rejects a wrong-head completed run as "CI is a liar — do NOT trust it". This is the exact `head_sha` verification the methodology mandates.
- **Picks the newest matching run** on the branch (handles the API list-cache-lag / multiple-runs-per-branch cases), and drops runs that don't match `--expect`.
- **`--no-dispatch`** to attach to an already-triggered run (e.g. after a `main` push release build).

```sh
# dispatch build-apk.yml on a branch, wait, verify head, report, exit 0/1
sh /var/minis/skills/github-ops/scripts/gh_ci_wait.sh \
  --repo logicflow-GYW/RikkaMinis --workflow build-apk.yml --ref fix/branch \
  --expect <full-commit-sha> --timeout 900 --poll 60 --job-detail
```

Exit codes: `0` all-clear; `1` any step failed; `2` timed out with no verdict; `3` wrong-head when `--expect` given (phantom/false-green). Anything non-zero means **do not trust the green; investigate before merging**.

## Long waits: ≥180 s of silence gets your command killed (stall-guard)

The shell tool runs behind a **silence watchdog**: a command that produces no
stdout/stderr for ~180 s is SIGINT'd (exit 130, logged as
`stall:sigint idle=180xxxms targets=N`). This is not about how long the command
runs — it is about how long it stays *quiet*. Two real casualties (2026-09-25,
both legitimate waits doing nothing wrong):

| command | outcome |
|---|---|
| `gh_ci_wait.sh --timeout 900` (quiet until the run finished) | `stall:sigint idle=180394ms targets=3` after 643 s |
| `sleep 240; curl …` (intentional 240 s wait) | `stall:sigint idle=180913ms targets=1` at 180 s |

Rules:

- **Waiting is the `delay` parameter's job, not `sleep`'s.** `shell_execute(delay=N)`
  blocks the agent flow *without* holding a shell process, so the watchdog never
  sees it. Then run the status check in the next call.
- **Polling loops must stay under the threshold**: keep each wait slice **<180 s**
  and emit a heartbeat line per slice (`echo "… waiting $i/6"`), or the loop dies
  mid-flight and the failure looks like a CI/API problem rather than a watchdog kill.
- **Never pipe a long waiter through a filter**: `gh_ci_wait.sh … 2>&1 | tail -5`
  buffers everything, so the script is mute to the watchdog even though it is
  working. Let it write directly and read the output at the end.
- When a wait *is* killed, the evidence is in `/var/minis/logs/memspike-<date>.log`
  (`[stall:sigint] session=… idle=… targets=…`) — check that before blaming the network.
- Upgrade trigger: a third recurrence after this rule is documented means the
  discipline isn't enough and the shell tool itself needs to warn on long `sleep`s.

## Script Command List (gh_sync.sh)

| No. | command | Purpose |
|---:|---|---|
| 2 | `clone --url <url> [--dir <path>]` | Clone a repository to a specified directory |
| 3 | `remotes` | Show current remotes |
| 4 | `add-remote --name <n> --url <url>` | Add a remote |
| 5 | `set-remote-url --name <n> --url <url>` | Change a remote URL |
| 6 | `remove-remote --name <n>` | Remove a remote |
| 7 | `add-upstream --upstream <owner/repo>` | Add the upstream remote |
| 8 | `status` | `git status --porcelain` + brief tips |
| 9 | `diff [--staged]` | View differences |
|10| `log [--n <k>]` | View recent commits |
|11| `branches` | List local and remote branches |
|12| `create-branch --name <b> [--from <ref>]` | Create a branch |
|13| `checkout --name <b>` | Switch branches |
|14| `delete-branches --keep <branch>` | Delete local/remote branches except the branch specified by `keep` |
|15| `add --path <p>` | `git add` |
|16| `commit --message <m>` | `git commit` |
|17| `fetch [--remote <n>]` | Fetch remote updates |
|18| `pull [--remote <n>] [--branch <b>]` | Pull and merge |
|19| `push [--remote <n>] [--branch <b>]` | Push |
|20| `push-main` | Push the current main branch to origin (using a token, non-interactive) |
|21| `empty-dir --dir <path>` | Empty a directory in the repository while preserving the directory (using .gitkeep) |
|22| `restore-dir --src <path> --dst <path>` | Restore to a repository directory by overwriting it with a local directory (deletes the contents of `dst` first) |
|23| `pr --upstream <owner/repo> --head <owner:branch> --base <branch> --title <t> --body <b>` | Create a PR through the GitHub API |
|24| `gh-issues-list ...` and others | GitHub platform object operations (issues/labels/milestones/releases/actions) |

## Typical Workflows (Examples)

### 1) Push directly to main: empty directory -> restore directory -> push (the workflow you just used)

```bash
sh /var/minis/skills/github-ops/scripts/gh_sync.sh empty-dir --dir self-improving-agent
sh /var/minis/skills/github-ops/scripts/gh_sync.sh restore-dir --src /var/minis/skills/self-improving-agent --dst self-improving-agent
sh /var/minis/skills/github-ops/scripts/gh_sync.sh commit --message "restore(self-improving-agent): sync from local"
sh /var/minis/skills/github-ops/scripts/gh_sync.sh push-main
```

### 4) Replace only the contents of a target file in the repository (preserve the original path and filename), then commit and push

Applicable scenarios: The user asks to "replace only the content," "replace only the worker inside," or "preserve the original repository filename/path." The intent is: **only overwrite the target file contents, without adding the source filename to the repository and without changing the target path in the repository**.

Recommended execution order:

```bash
# 1. Pull/refresh the repository
repo_dir=/var/minis/workspace/<repo>
if [ -d "$repo_dir/.git" ]; then
  cd "$repo_dir" && git fetch --all --prune && git reset --hard origin/main
else
  gh repo clone <owner/repo> "$repo_dir"
fi

# 2. Content-only overwrite: overwrite the target file in the repository with the source file contents
cp <source_file> "$repo_dir/<target_path>"

# 3. If the Git identity is not configured, prefer reusing the GitHub display name and GitHub noreply email
cd "$repo_dir"
git config user.name '<GitHubDisplay Name>'
git config user.email '<login>@users.noreply.github.com'

# 4. Commit and push directly to main (only when the user explicitly requests commit/push)
git add <target_path>
git commit -m 'replace <target_path> content'
git push origin main
```

Key points:
- When the user says "replace content only," always interpret it as: **preserve the original file path and filename in the repository, and overwrite only the content**.
- Do not put the source filename directly into the repository; the target should still be the original file in the repository, such as `worker.js`.
- If the target path is known, overwrite that path directly; if it is unknown, first locate the target file in the repository and then replace it.
- If `Author identity unknown` is reported before committing, you can write the following in the current repository:
  - `git config user.name '<GitHubDisplay Name>'`
  - `git config user.email '<login>@users.noreply.github.com'`
- By default, run `git fetch` + `git reset --hard origin/main` first to avoid accidentally committing on an old working tree.
- If the user has already explicitly requested "commit" or "push," proceed directly without asking for confirmation again.

### 5) Delete all branches except main (local + remote)

```bash
sh /var/minis/skills/github-ops/scripts/gh_sync.sh delete-branches --keep main
```

### 6) Do not create a branch; open a PR directly from fork:main to upstream:main

```bash
sh /var/minis/skills/github-ops/scripts/gh_sync.sh pr \
  --upstream OpenMinis/MinisSkills \
  --head GH_USER_NAME:main \
  --base main \
  --title "sync: ..." \
  --body "..."
```

- **Repository list output must use a Markdown table** and include a `Number` column so the user can reply with a number to choose directly.
- Suggested fields: `Number | Repository(owner/repo) | Visibility | Fork | Default branch | Last updated | URL`
- Interaction suggestion: After the table, prompt: "Reply with the number to continue (clone/pull/commit and push/content-only replacement, etc.)."

> Note: If the user needs more fields (description, language, stars), add `--json ...` to extend the output.

| No. | Symptom | Handling |
|---:|---|---|
| 1 | push reports `could not read Username` | Requires env `GITHUB_TOKEN`; the script will use `GIT_ASKPASS` for non-interactive authentication |
| 2 | API 401/403 | Insufficient token permissions (repo/public_repo) or token expired |
| 3 | `not inside a git repo` | First `cd` to the repository directory (or use `clone`/`init`) |