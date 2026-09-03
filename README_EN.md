# RikkaMinis — Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20arm64-brightgreen.svg)](#install)
[![Build](https://github.com/logicflow-GYW/RikkaMinis/actions/workflows/build-apk.yml/badge.svg)](https://github.com/logicflow-GYW/RikkaMinis/actions/workflows/build-apk.yml)

[简体中文](README.md) · **English**

**Your private, on-device AI agent.**

RikkaMinis is a personal **Android-only** build that hybridizes two projects:
the engine and codebase come from [OpenMinis](https://github.com/OpenMinis/OpenMinis),
while the UI and interaction logic are inspired by
[RikkaHub](https://github.com/rikkahub/rikkahub) — including the chat history
drawer, minimal top bar, message-list layout, and interaction behaviors such as
message-list follow and input-bar focus.

It builds a working APK on GitHub Actions and publishes it automatically.

The OpenMinis core brings leading models — Claude, GPT, Gemini and more — into
a native mobile experience, and gives them a real computer to work with: a full
Linux shell running on your device, browser automation, extensible skills,
persistent memory, and deep system integration.

> **This is a personal fork primarily for self-use.** I fix what I need and
> welcome others to use it, but don't expect active support or feature requests.
> Feel free to fork your own.

---

## Install

**→ [Download the latest APK](https://github.com/logicflow-GYW/RikkaMinis/releases/tag/android-latest)**

Every push to `main` with **code changes** (`src/android/**`,
`src/shared/**`, `deps/**` or workflow files) builds a release APK and
republishes it to that link — pure doc changes (README / licenses) do
not trigger a build. So the URL always points at the latest build.
Requirements:

- **arm64-v8a** device (any modern phone), **Android 8.0+**
- Enable "install from unknown sources" when your device prompts

Builds are signed with a fixed key, so a new APK installs **over** the previous
one — your data and settings are preserved.

```
SHA-256  FC:0C:40:0D:B7:7E:C1:81:A3:35:18:C2:E8:13:6A:AE
         1A:3F:6C:79:4A:1A:A7:9F:DB:67:63:8F:C6:B1:61:13
```

Verify a download with `python3 scripts/apk_cert_sha256.py <apk>`.

---

## What this fork changes

This started as a build-only fork, but it now also carries a small set of
Android-specific product changes that are not present upstream.

### App changes

- **Complete local backup and restore.** Settings → Storage → Backup & Restore
  exports a portable JSON file and imports it on another installation. It
  covers provider/model configuration and groups, optional API keys,
  environment variables, app/agent/chat defaults, Soul, complete skills
  (SKILL.md plus bundled scripts, references and assets), persistent memory,
  MCP server configuration, and chat history (text-only, last 90 days by
  default, window adjustable in Backup settings).
- **WebDAV remote backup.** Settings → Storage → Backup & Restore also lets
  you push backups to any WebDAV server (Nextcloud, 坚果云, Synology, …) —
  configure once and sync your backups across devices, with upload, list,
  restore and delete for remote copies. Backup/restore runs on an app-scoped
  persistent scope (it completes even if you leave the settings screen),
  gives you a system tray notification when a long task finishes, and is
  guarded against accidental double-firing.
- **Multi-device auto-sync with conflict merge.** The auto-sync toggle in
  Settings → Storage → Backup & Restore ties several devices to the same
  WebDAV backup; `SyncMerge` reconciles edits with Lamport-style per-object
  version folding, so two devices used on the same day no longer overwrite
  each other — sibling-device edits win, deletions propagate as tombstones,
  and both devices converge to the same document. The sync scope covers
  config / providers / groups / env vars and `GLOBAL.md` only: daily logs are
  per-device audit copies and `MEMORY-ROLLUP.md` is distilled from daily logs,
  so syncing those whole files would clobber one device's copy.
- **Honest exclusions.** Chat history is carried as text only: media
  (images/videos) and attached files are dropped, and only the last N days of
  activity are included (0–365, default 90; 0 disables chat history). 
  Mounted-folder permissions cannot be transferred between Android devices,
  and MCP OAuth client secrets/tokens are never exported — OAuth-backed MCP
  servers must be re-authorized after restore.
- **Chat UI refinements.** Message links can focus and highlight a specific
  message; navigation titles are left-aligned; the active model selector lives
  in the composer; attachment and command actions are arranged more compactly.
- **Left-swipe chat history drawer.** The chat screen opens a slide-out
  conversation list from the left edge (or via the hamburger button), so you
  can switch between past conversations — or start a new one — without leaving
  the current chat. The drawer mirrors the session list: same grouping,
  category icons and relative timestamps, current chat highlighted, and
  long-press to delete a conversation.
- **UX polish.** Entering the app no longer pops the keyboard — the composer
  only focuses when you tap it. Tool-result thumbnail previews in the
  composer are off by default (toggle in Settings → Appearance). The chat
  "…" menu exports the current conversation (JSON or plain text, listed
  between Slash Commands and Token Usage) and no longer lists Clear Chat,
  which duplicated New Chat and could strand an empty ghost conversation.
  Settings and its top-level sub-pages (Appearance, Backup, Env Vars, Logs,
  MCP, Memory, Providers, Skills, Soul, Storage, Usage) drop the redundant
  top-bar back arrow — system back gesture / bottom nav handle returning;
  edit, wizard and permission-flow screens keep theirs.
- **Simpler composer.** The dedicated voice-chat shortcut and its inline UI
  have been removed. Android's agent-facing speech tools are unaffected.
- **Termux-powered terminal.** The in-app terminal swapped its hand-rolled
  ANSI emulator (~2,200 lines) for Termux's `terminal-view` 0.118.0 engine
  (JitPack dependency): PTY lifecycle, ANSI/CSI/OSC parsing, TUI
  compatibility, keyboard and text-selection handling all come from the
  upstream engine. Output is truncated at two layers (128 KB at the
  PersistentShell level + 50 KB at the terminal sanitizer) so bulk output
  cannot flood the UI.
- **Pin favorite providers.** Frequently used providers can be pinned into a
  "Favorites" section at the top of the provider list, toggled from the row's
  trailing menu.
- **Memory page management improvements.** The memory page file list supports
  "show more" expand/collapse.
- **Settings consistency fixes.** Restored preferences refresh the live
  settings UI, and previously disconnected/missing settings keys are now
  registered and included in backups.
- **Sub-agent dispatch is OFF by default.** The agent can spawn independent
  sub-agents via the `spawn_agent` tool and dispatch work to other chat
  sessions via `minis-sessions-cli send` — but that is a side-effectful
  capability (opens new sessions, burns tokens, runs long-lived work), so it
  is disabled by default behind a switch in Settings → Agent Runtime
  ("Sub-agent dispatch"). When OFF, `spawn_agent` never enters the tool
  schema and `minis-sessions-cli send` answers with an explicit rejection;
  when ON, both work, and the sub-agent's own tool set is filtered so it
  cannot spawn again (recursion is structurally impossible).
- **Long-conversation auto-summary compaction.** When a conversation is
  nearing its context cap mid-answer, the agent loop first tries to fold the
  oldest turns into a `<context-summary>` via the context compactor (instead
  of the old behaviour of hard-dropping the oldest turns and inserting a
  jarring "trimmed N messages" line mid-stream); hard trimming stays only as
  the last-resort hard cap. The system prompt also clarifies per-session vs
  cross-session storage semantics: `workspace/attachments/offloads/browser`
  are private per session (physically under `minis-sessions/<sid>/`), while
  only `shared/memory/skills/mcp-servers/mounts` are shared across sessions.
- **Bundled platform integrations (GitHub / Cloudflare / Hugging Face).**
  Full detail in [Built-in platform integrations](#built-in-platform-integrationsgithub--cloudflare--hugging-face).
  In short: three platform skills (semantic memory, GitHub automation, and
  Cloudflare ops) ship inside the APK. When the system prompt is assembled
  the app works out each platform's current capability tier from which API
  tokens (`HF_TOKEN` / `GITHUB_TOKEN` / `CF_API_TOKEN`) you have configured,
  and injects an "Integrations" table into the system prompt — the agent
  knows up front what each platform can and cannot do, no trial-and-error
  guessing.

**Collaboration pattern.** This fork is itself developed through a
human–AI closed loop: you (decide/verify) + AI agent (execute/iterate) +
external platforms (compile/release/persist), cycling until convergence.
See [docs/DEVELOPMENT_LIFECYCLE.md](docs/DEVELOPMENT_LIFECYCLE.md).

### Build and release changes

- **proot is built from source.** The sandbox engine comes from the
  `deps/proot` submodule + `deps/build_proot.sh` + vendored `deps/talloc`,
  compiled with NDK r28 in CI. The Alpine rootfs (`alpine-minirootfs.tar`,
  8.5 MB) is committed as a prebuilt asset and unpacked at runtime by
  `RootfsManager` — the proot binary itself is not committed, fully
  reproducible.
- **Other native libs stay vendored.** `libpty_bridge.so`,
  `libminis_crash_handler.so` and `libjieba_jni.so` are committed as-is.
- **Backup tests run in CI.** The full JVM unit-test suite — backup/restore
  (ConfigBackupPayloadTest and friends), terminal sanitization, provider
  adapters, LLM error handling and more — runs before the APK build, and any
  failure aborts the build (no silent scoping).
- **Pre-build static scan gate.** Every CI build runs `scripts/scan/scan.sh`
  before Gradle: a four-way field-sync check (data-class fields must be synced
  across Model → Entity → toSnapshot → toProviderConfig, or fields silently
  evaporate), an i18n orphan-key check, an enum-parse safety check (no bare
  `valueOf`), and a provider process-boundary guard (the app process must
  never call provider network entry points directly — only `:modelservice`
  owns them). Any hard failure aborts the build.
- **iOS sources removed.** `src/ios/` is gone; this tree is Android only.
- **Automatic releases.** Successful builds publish the APK to the
  `android-latest` release.
- **Platform skills shipped in assets.** `semantic-memory`,
  `github-ops`, `cloudflare-fullright-ops` and `skill-creator`
  (with their scripts) are bundled under `app/src/main/assets/skills/`,
  so a fresh install has them ready with zero manual setup.
- **Integration status injected into the system prompt.** Each bundled
  skill carries a `requirements.json` listing the env vars it needs. At
  runtime the app derives a per-platform capability tier from which vars
  are configured, emits a `[IntegrationStatus]` log line (declared= vs
  found=) for troubleshooting, and injects an "Integrations" table into
  the system prompt.


**Why build proot from source?** The sandbox engine `libproot.so` needs to be
built with upstream's Android 10+ W^X bypass patches. Building it through
AGP's CMake block produces a binary that compiles fine and then fails at
runtime with `execve("/bin/sh"): Permission denied` — the terminal never
opens. This fork therefore builds it with `deps/build_proot.sh` (the
upstream-supported path — same source, same NDK toolchain the official
binary is built with) instead of CMake. `externalNativeBuild` stays disabled
so AGP never overwrites the vendored pty_bridge / crash_handler / jieba
libraries with unpatched CI-built copies.

**Trade-off:** edits under `src/android/app/src/main/cpp/` are not compiled —
only `deps/proot` is built, via `build_proot.sh`. Changing the other native
code means restoring the CMake block and installing the NDK in CI. Kotlin, UI,
prompts and model integrations are unaffected — build normally.

---

## Built-in platform integrations (GitHub / Cloudflare / Hugging Face)

> **This section is the most important — and easiest to miss — structural
> change in this fork relative to upstream.** It turns a "standalone on-device
> agent" into a "one brain, three platforms" shape: the agent not only runs a
> shell / browser on your phone, it can also operate three external platforms
> directly. If you build from source and don't know this layer exists, you'll
> be confused by the extra "Integrations" table that shows up in the system
> prompt.

### In one sentence

RikkaMinis bundles three **platform skills** — each encapsulates the common
operations of one external platform and declares the env vars it depends on in
its own `requirements.json`. When the system prompt is assembled the app reads
the tokens you've configured, derives a per-platform capability tier
(zero-config / read-only / full), and **injects the result into the system
prompt**, so the agent knows exactly which platforms it can touch right now —
no guessing from trial-and-error.

### What each platform handles

| Platform | Skill | Capabilities | Token needed | Min / full tier |
|---|---|---|---|---|
| **GitHub** | `github-ops` | Push code, trigger CI, manage issues / labels / releases / PRs, query status | `GITHUB_TOKEN` | Tier 1 read-only · Tier 2 full |
| **Cloudflare** | `cloudflare-fullright-ops` | List / deploy Workers, manage KV / R2, query Zones / DNS | `CF_API_TOKEN` | Tier 1 read-only · Tier 2 full |
| **Hugging Face** | `semantic-memory` | Semantic search of past experience, read/write HF Datasets, cross-device persistent memory | `HF_TOKEN` | Tier 0 zero-config search · Tier 2 full read/write |

Configuration entry point: **Settings → Environments** (or `minis-config
envvars`). All three tokens are standard personal API tokens created in each
platform's dashboard. **Note: token values are stored locally on-device (read
at tier-computation time); they never appear in any log, and by default they
are not included in backup exports — unless you explicitly check "include
secrets" in Backup.**

### How the capability tier is computed (`buildIntegrationStatus`)

Each skill ships a `requirements.json` naming the env vars it needs (e.g.
GitHub needs `GITHUB_TOKEN`). At runtime these are matched against the app's
environment-variable store, with the following rules:

- **Tier 0 — zero-config**: public capability that needs no token (e.g.
  semantic memory's public search).
- **Tier 1 — read-only**: declares env vars and at least one is **partially**
  configured (can read public data / limited operations).
- **Tier 2 — full**: declares env vars and **all** are configured (full
  read/write / deploy).

The current tier is surfaced two ways:

1. As a **`[IntegrationStatus]` log line** (`logcat | grep IntegrationStatus`),
   annotated with `declared=` and `found=` environment-variable sets — when
   you hit a "thought I configured it but it says not configured" mismatch,
   you can see in one line which variable wasn't read, instead of guessing.
2. As an **"Integrations" table** in the system prompt (a standalone
   `## 内置集成` block right after the skills list and before MCP servers —
   the heading is hardcoded Chinese in the codebase), which the agent uses
   to decide how to do its work. A platform with no configured token is
   labelled "🔒 Needs config" — never falsely labelled "⚡ zero-config
   usable". Better to refuse than to mislead the agent into operating it.

### How the three skills are upgraded

Each skill lives under `src/android/app/src/main/assets/skills/<skill>/`
(SKILL.md + requirements.json + scripts). Edit the files → commit → CI
repackages → the new APK carries the new skill version. For local development,
overwriting `/var/minis/skills/<skill>/` takes effect immediately (the app
prefers `/var/minis/skills`, falling back to the bundled copy only when absent).

### Privacy note

Platform skills fire requests against their platforms **only when you
explicitly ask the agent to** (i.e. you say in chat "check that GitHub issue
for me"). The app never calls the platforms in the background on its own.
Tokens are only used for authenticating those explicit requests.

---

## What it does

| | |
|---|---|
| **Bring your own model** | Claude, GPT, Gemini and other providers, via your own API keys or account sign-in. |
| **A real Linux shell** | A sandboxed Alpine Linux environment runs on-device — the agent can install packages, run scripts, and work with real files. |
| **Device integration** | Calendar, Contacts, Clipboard, Location, Media, Alarms, Notifications and more, exposed to the agent as tools. |
| **Browser automation** | The agent can browse and interact with the web on your behalf. |
| **Skills & memory** | Extensible skills plus persistent memory across sessions. Complete skill bundles and memory files are included in local backups. |
| **Platform integrations** | Built-in GitHub / Cloudflare / Hugging Face platform skills; available capability per platform is derived dynamically from the tokens you configure (see previous section). |
| **Local backup & restore** | Export configuration, credentials (optional), skills, memory, MCP servers and chat history (text, last N days) to one portable JSON file. |
| **Workspaces** | Organise work into separate contexts, addressable via `minis://workspace/`. Workspace, attachment, offload and browser directories are **per-session private** (`minis-sessions/<sid>/`); only `shared/`, `memory/`, `skills/`, `mcp-servers/` and mounted folders are shared across sessions. |
| **Native offloads** | Heavy or platform-specific work is handed to native code instead of the sandbox. |

**→ [OpenMinis/MinisSkills](https://github.com/OpenMinis/MinisSkills)** — ready-made
skills. Skills built for Claude, Codex, OpenClaw or Hermes Agent generally run
in Minis as-is.

**→ [OpenMinis/AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** — a
curated collection of use cases and workflows.

---

## Building locally

```sh
git clone --recurse-submodules https://github.com/logicflow-GYW/RikkaMinis.git
cd RikkaMinis/src/android
../../deps/build_proot.sh        # build the proot sandbox engine from source
./gradlew assembleRelease
```

Needs **JDK 17**, the Android SDK (compileSdk 36) and **NDK r28** — the latter
for `deps/build_proot.sh`, which compiles the proot sandbox engine from the
`deps/proot` submodule (the other native libs are vendored in the tree). The APK
lands in `app/build/outputs/apk/release/`.

Local builds are signed with your own `~/.android/debug.keystore`, so they will
not install over a CI build. To match CI, put the same keystore there.

See [BUILDING.md](BUILDING.md) for toolchain details and troubleshooting.

---

## Keeping up with upstream

Upstream is a one-way mirror that does not accept pull requests, and this fork
has diverged in a handful of files. Syncing is possible but has an order of
operations — in particular, the vendored pty_bridge / crash_handler / jieba
libraries must be refreshed whenever upstream's Kotlin changes, or the app
breaks at runtime. proot is **not** vendored any more: it is built from source
in CI via `deps/build_proot.sh`, so the only thing to refresh for it is the
`deps/proot` submodule when upstream bumps it.

```sh
git fetch upstream
git rebase upstream/main               # not merge
./scripts/sync_official_binaries.sh    # refresh the vendored pty_bridge/crash_handler/jieba libs
```

**→ See [docs/SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md)** for the full
procedure, the list of files that conflict, and how to recover from a bad sync.

---

## Privacy

This fork adds no tracking, and upstream ships none. Specifically:

- **No analytics or telemetry SDK.** No Firebase, Crashlytics, Sentry, or
  similar.
- **Crash reports stay on the device.** ACRA is included but with `acra-core`
  only — no network sender is configured. Reports are written to local files and
  surfaced in the app's log screen.
- **No device identifiers are collected.** No IMEI, no advertising ID.
- **The debug server is not in release builds.** A local JSON-RPC server on
  `127.0.0.1:5321` exists for development, gated behind `BuildConfig.DEBUG` and
  compiled out of the release APK published here.

Network traffic goes to the model providers you configure, using your own API
keys, plus the endpoints you explicitly ask the agent to visit. Local backup
files never leave the device unless you share or copy them yourself. If you
choose "include secrets", the JSON contains API keys and environment-variable
values in recoverable form; store that file like a password. MCP OAuth tokens
and client secrets are excluded even from secret-inclusive backups.

The app requests broad permissions (storage, contacts, calendar, microphone,
location, accessibility) because they back agent tools. They are requested at
the point of use — the agent can only use what you grant.

---

## Repository layout

```
src/android/      Android app (Kotlin / Compose)
  app/src/main/jniLibs/arm64-v8a/   Native libs (jieba, pty bridge, crash handler);
                                    libproot.so is a CI build artifact, not vendored
  app/src/main/assets/              Alpine minirootfs + bundled platform skills (skills/)
src/shared/       Assets shared with upstream's iOS tree (bashism rules)
deps/             proot source (submodule) + build_proot.sh (NDK r28 build)
docs/             Sync procedure and interface specifications
scripts/          Binary sync and developer tooling
```

---

## Acknowledgements

RikkaMinis stands on a great deal of open-source work — full inventory in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). This fork is derived from
**[OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)** and builds its
sandbox binaries from source: the `deps/proot` submodule (OpenMinis' PRoot
fork, including its native-offload and W^X extensions) is compiled on every CI
run via `deps/build_proot.sh` with NDK r28. No prebuilt sandbox binaries are
committed to this repository.

**The sandbox** — [PRoot](https://github.com/termux/proot) (GPLv2), user-space
chroot for the Android sandbox, via [OpenMinis' fork](https://github.com/OpenMinis/proot);
**[talloc](https://talloc.samba.org)** (LGPLv3+) underpins it;
**[Alpine Linux](https://alpinelinux.org)** — the minirootfs the sandbox boots.

**Text & rendering** — [cppjieba](https://github.com/yanyiwu/cppjieba) (MIT),
[KaTeX](https://katex.org) (MIT).

**Terminal** — [Termux](https://github.com/termux/termux-app)'s `terminal-view`
0.118.0 (JitPack, `com.termux.termux-app:terminal-view`) provides the
in-app terminal's ANSI/CSI/OSC parsing and TUI rendering engine.

**Interaction reference** — [RikkaHub](https://github.com/rikkahub/rikkahub)
(AGPL-3.0), an Android multi-LLM client whose design informed RikkaMinis' chat
UI and interaction logic (conceptual inspiration, not code reuse).

**Android on-device AI agent references** — the following projects informed
RikkaMinis' agent runtime, automation, and system-integration capabilities
(conceptual inspiration, not code reuse):

- **[OmniBot](https://github.com/omnimind-ai/OmniBot)** — tool concurrency,
  run folding, auto-compaction, memory rollup, sub-agent system
- **[肉包 Roubao](https://github.com/Turbo1123/roubao)** — macro scripts,
  execution tracing
- **[AppAgent](https://github.com/TencentQQGYLab/AppAgent)**
- **[MobileAgent-Android](https://github.com/GiggleWang/MobileAgent-Android)**
- **[mobAgent](https://github.com/sudharsanacernitro/mobAgent)**
- **[anthroid](https://github.com/k-l-lambda/anthroid)**
- **[OpenPhone](https://github.com/HKUDS/OpenPhone)**
- **[MobileAgent](https://github.com/X-PLUG/MobileAgent)**
- **[Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)**
- **[OpenGUI](https://github.com/Core-Mate/OpenGUI)**
- **[mobilerun](https://github.com/droidrun/mobilerun)**
- **[locanara](https://github.com/hyodotdev/locanara)**
- **[deliteAI](https://github.com/NimbleEdge/deliteAI)**

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack),
[OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/),
[kotlinx](https://github.com/Kotlin) serialization & coroutines,
[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer),
[Reorderable](https://github.com/Calvin-LL/Reorderable), [ACRA](https://github.com/ACRA/acra)
(all Apache-2.0), and [Shizuku](https://github.com/RikkaApps/Shizuku-API) (MIT).

---

## License

RikkaMinis is licensed under the **[GNU General Public License v3.0](LICENSE)**.

The app links GPL-licensed components — [PRoot](https://github.com/OpenMinis/proot)
(GPLv2) — so the combined work is distributed under GPLv3. Bundled third-party
licenses are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

## Upstream

For the original project, the iOS app, issues and community:

**→ [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)** ·
[openminis.app](https://openminis.app) ·
[Telegram](https://t.me/+2NzhOJuzRyI1YmM1)

For general app bugs, check whether they also occur in the official upstream
build. Upstream issues belong at OpenMinis/OpenMinis; problems with this fork's
build, APK, backup/restore flow, or Android UI changes belong in this repository.
