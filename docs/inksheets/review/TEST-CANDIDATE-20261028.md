InkSheets test candidate — 2026-10-28

This batch includes category pages inside the music bar, zoomable recording waveforms and draggable loop bounds, excerpt/page-range recording pairing, automatic queued background reading, structural measure splitting and multi-bar rest correction, reversible page crops, setlist default instruments, adjustable metronome beat counts and live playback click muting, and the requested footer/tab cleanup.

The setlist instrument control sits between the set title and Start/+Songs. A differing instrument prompts Switch/Keep current; declining stays dismissed for the session.

Regression scope includes shared InkSlate crop/viewport code, Android compilation, twelve responsive recording/category layouts, correction persistence, background scheduling, waveform gestures, excerpt serialization, setlist sync, and mask notification behavior. Full-suite results and published build identity will be appended after verification.

Remaining review: physical-device evaluation, especially Android keyboard-resized forms, crop gestures with existing ink, background-reading battery/thermal behavior, and the subjective balance of expanded bars versus visible music. Idle recognition passes preserve manual corrections and store alternatives for doubtful measures; they do not silently replace the base reading.

Final local targeted run: release-final-validation.log succeeded (22s), covering RecordingAndStatus, MusicDockLifecycle/Layout, ReaderDrawer, MusicShelfRedesign, ReadingQueue, BackgroundReading, RecordingWaveform, FixStructureFlow, ReaderPanelLifecycle and CleanMask, plus InkSlate Android unit tests. Earlier combined checks passed all core/music-core/desktop regressions and InkSheets Android unit tests. BarCheck's real-library Fix flow passed after correcting background worker starvation.

Handoff: the attempted full local music UI run exposed legacy real-library PageReadTest/OlderReadingTest assumptions that need reconciliation with automatic whole-piece reading and expanded categories. Those depend on local music fixtures and are absent on CI. Do not claim the whole local suite passed: it was stopped to isolate failures. Remaining failures in waveform resolution, category-close navigation and recording score clearance were fixed and pass the final focused run. Re-run/update the real-library manual-reading tests against the new automatic scheduling semantics in the next session, retaining coverage for explicit per-page requests.

Publication candidate includes a recording-start close fix and exact footer height allowance so short landscape screens preserve music space. Earlier candidate runs303/304 are superseded; use the final successful release only.
