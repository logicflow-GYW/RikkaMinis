# Scroll-Follow Simplification (Stage E)

> Brance: `fix/scroll-follow-simplify` — RikkaHub-style simple explicit follow.
> Precondition: AGGREGATE_MESSAGE_ITEMS = true (stages C/D merged into main).

## Why this is safe now

With `AGGREGATE_MESSAGE_ITEMS = true` the flatten collect emits **one item per
ChatMessage** (`buildAggregateChatItems` → `UserBubble` / `AssistantMessageItem`,
keys `user:<id>` / `msg:<id>`), and the collector **early-returns** inside the
aggregate branch:

```kotlin
if (AGGREGATE_MESSAGE_ITEMS) {
    flatItems = withContext(Dispatchers.Default) { buildAggregateChatItems(merged) }
    return@collect   // ← exits BEFORE prevRowKeys prefix check & followReducer(StreamRowsChanged)
}
```

The whole fragment-churn the old guard stack was built to damp is structural:
a single assistant message was previously flattened into many rows
(header + thinking + tool-run-group + N markdown blocks each keyed by
`mdslot:`/`blockIndex`), so every streamed token re-split the markdown and
could change the live-tail key set. Now one message = one stable key, and the
live row recomposes in place (cheap-equals `message ===` fast path).

## Which patches lose their reason for existing (and deletion risk)

| Patch | Location | State under aggregate | Deletion risk |
|---|---|---|---|
| `prevRowKeys` prefix check (verifies append-only published-key invariant) | ~L3226 | **Never executes** — the aggregate `return@collect` precedes it (L3050). Even if it ran, aggregate keys are `msg:<id>`/`user:<id>`, stable, and never regress while streaming. It was already "telemetry only" (logs `ScrollInvariant`, never scrolls). | **Low** — it only logs; nothing reads `prevRowKeys` to gate behavior. Safe to drop or reduce to a debug log note. |
| `followReducer(StreamRowsChanged)` from the data collector | ~L3240 | **Never executes** under aggregate (same early-return). So `STREAM_PROGRESS` bottom requests were effectively never raised from the data path in the current main — follow-on-stream already relies on native bottom anchoring + the `forceScrollToBottom` edge. | **Medium** — this dispatch is the *intent* for follow-on-progress; SIMPLE_FOLLOW replaces it with the direct `isStreaming && isAtBottom` effect, so removing it is safe *because* the new effect covers it. |
| anchor-guard deadzone / re-pin loop | already REMOVED in Commit D (`[forward-stable] Anchor-guard REMOVED` comment) | n/a — predecessor to the current reducer protocol. Nothing to delete. | n/a |
| `stickToBottom` reverseLayout-era triple patch | replaced by the pure `followState`/`followReducer` protocol (Commit D) | The old boolean flag is gone; the reducer + consumer are inert under SIMPLE_FOLLOW (consumer gated, reducer never drives). | **Low** — with SIMPLE_FOLLOW on we gate the consumer; the reducer code can remain as recorded state (unused) or be trimmed. Since it now only records state and never scrolls, keeping it is harmless; removing it is cleanly replaceable by the single effect. |
| `isFarFromBottom` / `avgItemSize` / `itemSizeByIndex` estimators (scroll-button positioning) | L1140-1310 | These power the floating scroll *buttons*, not follow. A message now = one item, so item-height variance is smaller and the historical "one message → N indices" inflation that made the average misfire is gone. | **Keep** — not a follow patch; drives the up/down FAB visibility. Do not delete here. |
| `isNearBottom` / `isBottomSentinelVisible` / `safeBottomScrollIndex` | L1133 / L353 / L369 | **Behavior contract.** Sentinel-based "at bottom" is the ground-truth test. | **Must keep** — SIMPLE_FOLLOW's effect uses them. |

## What is preserved (behavior contract, not patch)

- **`isAtBottom`** (`isBottomSentinelVisible` + `isNearBottom`) — the only
  ground-truth "user is at the bottom" signal. The new effect scrolls only
  when the sentinel is in view.
- **`safeBottomScrollIndex`** — negative-safe scroll target; guards the cold
  open / empty-layoutInfo cases (the `requestScrollToItem(-1)` crash guard).
- **`wasScrolledIntoHistory`** (send handler) — snapshot the viewport BEFORE
  `sendMessage`, so a bottom-anchored sender isn't misread as in-history, and
  a history reader is never yanked by a send.

All three are retained in the SIMPLE_FOLLOW path.

## Deleted / neutralized (SIMPLE_FOLLOW = true)

1. **`prevRowKeys` prefix check + flatten-collector `followReducer(StreamRowsChanged)`** —
   removed. They were both **unreachable** under AGGREGATE_MESSAGE_ITEMS (the
   aggregate branch early-returns before them), and under SIMPLE_FOLLOW the
   dedicated rikkahub effect is the streaming auto-follow driver — no
   data-revision poke needed. Also removed the now-dead `prevRowKeys` var.
2. **The guard stack's purpose** — gone structurally: message-level keys
   (`msg:<id>` / `user:<id>`) are stable, so nothing re-splits the live-tail key
   set mid-stream. There is no fragment-churn left for `prevRowKeys`-style
   invariant guards or re-pin loops to damp.

## Retained (and why)

- **The follow reducer + consumer remain** — but only serve **explicit user
  intents** now (InitialOpen / Send / FabDown / Resume / Retry / the
  `forceScrollToBottom` edge). With the data-collector `StreamRowsChanged`
  removed, the consumer's sole job is: while FOLLOWING, if the sentinel has
  scrolled out, scroll to it. DETACHED history readers are never yanked (the
  consumer gates on `isFollowing`, and the SIMPLE_FOLLOW effect only fires when
  the sentinel is already visible).
- **`isAtBottom`** (`isBottomSentinelVisible` / `isNearBottom`) — ground-truth.
- **`safeBottomScrollIndex`** — negative-safe scroll target.
- **`wasScrolledIntoHistory`** — send-time history-reader guard.

## Remaining risk (documented, accepted)

- SIMPLE_FOLLOW's effect scrolls once per visible-set change while streaming at
  the bottom. `requestScrollToItem(sentinel)` to the current position is a no-op
  for anchoring but does enter the gesture pipeline — the same cost rikkahub
  pays, bounded by the 80ms `sample()` throttle of the data collector upstream,
  not per-token.
- If a mid-aggregate item grows very tall (a single assistant message with a
  giant code block), a bottom-anchored viewer's sentinel may briefly leave the
  viewport and the effect nudges it back — the intended follow behavior.

## Cold-open landing (`fix/open-row-first-frame-final`)

Symptom (user-reported 2026-09-13): opening a long history rests "one screen
short" — the transcript sits a few thousand px above the end and needs one
swipe to reach the composer — and some opens visibly jump to the bottom a
moment after the first frame.

Root-cause chain (device forensics, `/data/local/tmp/rc2.log`):

1. Every markdown body starts at `blocks = emptyList()` and fills in from an
   off-main parse, so a frozen row's FIRST layout is 0px tall where its text
   belongs and the row grows a frame — or, under a busy default dispatcher,
   seconds — later. Measured on the newest row: 1,065→5,255px, 13,350→25,995px.
2. The `INITIAL_OPEN` snap (`scrollToItem(1_000_000)`, clamped to the bottom)
   resolves against that first, short layout. The later growth appends below
   the viewport *without moving it*, so the open rests short by exactly the
   growth (one open's `open-catchup roll` went firstOff 3,074 → 4,545px).
3. The parallel viewport prewarm meant to prevent this had been **dead since
   `AGGREGATE_MESSAGE_ITEMS` flipped**: its source extraction was
   `(item as? FlatChatItem.AssistantMarkdownBlock)?.rawText`, but the aggregate
   generator emits `AssistantMessageItem`, so `raws` was always empty. No
   `coldPrewarm.done` line exists in any device log and every `coldOpen
   summary` reports `prewarmMs=-1`.

Fix — three coordinated pieces:

| Piece | Where | What |
|---|---|---|
| Warm source selection | `ChatColdOpenPrewarm.kt` (new) | `markdownSourcesForRow` handles the aggregate row type (and the legacy ones); `collectColdOpenPrewarmSources` walks newest-first under a source cap + character budget |
| Await-before-publish warm | `ChatScreen.kt`, aggregate cold build | block-parse + cache the NEWEST row's fragments inside the row-build job, before `flatItems` is published, so the snap sees the final height (bounded: 48k chars / 32 fragments) |
| Synchronous cache seed | `StreamingMarkdownTextBody` | the first frame seeds `blocks` from the parse cache (lock-only peek) so a warm body renders final height immediately; the frozen branch now deposits its parse as well (close/reopen HITs) |

The `[fix/history-open-catchup-guard]` bounded catch-up remains as the safety
net for height sources that stay asynchronous (math/webview, image decode,
deferred sub-composition). Window 1.2s → 2.5s, rolls 3 → 4; the expected roll
count with the fix in place is **zero**.

Verification:
- `ColdOpenPrewarmSourcesTest` (11 JVM tests) pins the extraction contract and
  the regression itself (`old inline extraction found nothing in an aggregate
  row list`).
- Device probes: `coldOpen.newestRowWarm srcs=… chars=… warmMs=…`,
  `[Perf][OpenRowWarm] … warm=1|0` per frozen body, and `open-catchup done
  rolls=N` — N must be 0 on a normal open.

## Verification

- CI green, `ChatFollowControllerTest` green (the pure reducer is unchanged;
  the follow-state mutations live in ChatScreen and are unit-verified via the
  reducer tests + a live scroll regression).
- `git status` changes are confined to `ChatScreen.kt` (scroll area) +
  `ChatFollowController.kt` (untouched) + `docs/scroll-follow-simplification.md`.
