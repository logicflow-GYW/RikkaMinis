#!/usr/bin/env python3
"""Source-only seam checks; NOT a Compose/visual/performance test."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
main = root / 'src/android/app/src/main/java/com/rikkaminis/app/ui'
md = (main / 'chat/StreamingMarkdownText.kt').read_text()
ind = (main / 'chat/ChatIndicators.kt').read_text()
decor = (main / 'components/DecorativeAnimation.kt').read_text()
body = md[md.index('private fun MdText('):md.index('/**\n * Streaming-friendly markdown renderer.')]
checks = {
    'ordinary holder, generation policy receives actual Compose input/paragraph/size':
        'val layoutResultHolder = remember { arrayOfNulls<' in body
        and 'var layoutResult by' not in body
        and 'previous?.layoutInput, result.layoutInput' in body
        and 'previous?.multiParagraph, result.multiParagraph' in body
        and 'previous?.size, result.size' in body,
    'both composition caches keyed by generation':
        'remember(shardId, layoutGen, text)' in body
        and re.search(r'val inlineCodeRects = remember\(\s*text,\s*layoutGen,', body) is not None,
    'tap and selection draw read current non-state layout':
        'layoutResultHolder[0] ?: return@detectTapGestures' in body
        and 'val result0 = layoutResultHolder[0]' in body,
    'inline-code bounds and per-glyph geometry preserved':
        'ann.start.coerceIn(0, maxOffset)' in md
        and 'ann.end.coerceIn(0, maxOffset)' in md
        and 'result.getBoundingBox(offset)' in md
        and 'for (rect in inlineCodeRects)' in body,
    'all three dots use layer-only shared clock, no per-frame layout':
        ind.count('val tick = rememberDecorativeTick()') == 3
        and ind.count('Modifier.graphicsLayer {') + ind.count('.graphicsLayer {') == 5
        and 'rememberInfiniteTransition' not in ind
        and 'Modifier.offset(' not in ind
        and 'padding(top = (-offset)' not in ind,
    'typing footprint, queue, network, live Soul name remain':
        all(s in ind for s in ['TYPING_BAND_HEIGHT = 36.dp', 'chat_queued_indicator',
                               'chat_waiting_network', 'SoulStore.cachedMetadata.collectAsState()']),
    'lifecycle paired acquire, observer removal, dispose and semantics':
        all(s in decor for s in ['DecorativeClockLease(DecorativeClock::acquire, DecorativeClock::release)',
                                 'currentState.isAtLeast(Lifecycle.State.STARTED)',
                                 'removeObserver(observer)', 'lease.dispose()', '.progressSemantics()']),
    'one frame source; layer reads do not change real scroll clock':
        decor.count('withFrameNanos {') == 1
        and 'if (decorativeFrameDue(last, now))' in decor
        and 'job?.cancel()' in decor
        and 'newSingleThreadContext' not in decor,
}
for name, ok in checks.items():
    print(('PASS ' if ok else 'FAIL ') + name)
raise SystemExit(0 if all(checks.values()) else 1)
