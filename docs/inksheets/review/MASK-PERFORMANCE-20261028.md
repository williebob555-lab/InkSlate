# Clean/Mask live-gesture profile — 28 October 2026

## Measured issue

The existing 16 ms preview gate prevented repeated polygon construction in `CleanMasks.gesture`, but `ScoreTools.gesture` still advanced its overlay version and notified listeners for every rejected pointer sample. Accepted previews and completed strokes also notified twice: once in CleanMasks and once after ScoreTools advanced the overlay version.

The isolated production-pipeline baseline measured **121 overlay invalidations for one changed preview in a 120-event burst**, followed by **two invalidations for the final commit**. The burst took 5.1262 ms in that JVM run.

## Change and result

CleanMasks now returns whether it accepted a change and offers `notify=true` by default, preserving direct-call behavior. The ScoreTools mask branch passes `notify=false`, advances its overlay version only for accepted changes, then notifies once after the new version is available. Completed strokes always bypass the preview gate, including a one-point tap; no stroke geometry, eraser behavior, file payload, Undo history or original annotation tool settings changed.

The same production pipeline after the change measured **one invalidation for one changed preview**, **one for final commit**, and a 2.4884 ms burst. This is a count of avoided redundant overlay work and a JVM timing observation, not a device frame-rate claim.

## Long strokes and persistence

Both runs exercised 1,000- and 4,000-point ballpoint and calligraphy strokes through Perform.musicGesture. The generated contour counts stayed identical: ballpoint 215/941 and calligraphy 199/834. Four long strokes plus one short stroke produced the same 105,420-byte committed payload.

Baseline preview medians were 4.13–14.12 ms and durable commits 10.55–16.89 ms. The later run varied to 5.08–20.26 ms previews and 10.57–33.82 ms commits under JVM warmup and surrounding compilation. These observations do not establish a geometry or persistence speedup. No asynchronous-save or brush-shape change was justified or introduced in this pass.

Raw measurements remain in `sheets-desktop/build/mask-live-performance-before.txt` and `mask-live-performance-after.txt`. The earlier actual native path benchmark remains in `mask-native-performance.txt` (54.67× for historical-path reuse, with sampled pixel parity).

## Verification

The coordinated run passed all 22 selected desktop tests: CleanMaskTest (7), ReaderDrawerTest (1 covering 12 display sizes), ReaderPanelLifecycleTest (3), RecordingAndStatusTest (11). This includes real overlay pixel checks for brush/erase/Undo/rescaling, original brush-shape parity, committed-only reload, invalid-input cancellation, and the new notification-count assertions. Android InkSheets Kotlin compilation also passed. All libraries were temporary; no microphone/speaker hardware or real library writes were used.
