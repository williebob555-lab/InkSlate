InkSheets test candidate — 2026-10-28

This batch includes category pages inside the music bar, zoomable recording waveforms and draggable loop bounds, excerpt/page-range recording pairing, automatic queued background reading, structural measure splitting and multi-bar rest correction, reversible page crops, setlist default instruments, adjustable metronome beat counts and live playback click muting, and the requested footer/tab cleanup.

The setlist instrument control sits between the set title and Start/+Songs. A differing instrument prompts Switch/Keep current; declining stays dismissed for the session.

Regression scope includes shared InkSlate crop/viewport code, Android compilation, twelve responsive recording/category layouts, correction persistence, background scheduling, waveform gestures, excerpt serialization, setlist sync, and mask notification behavior. Full-suite results and published build identity will be appended after verification.

Remaining review: physical-device evaluation, especially Android keyboard-resized forms, crop gestures with existing ink, background-reading battery/thermal behavior, and the subjective balance of expanded bars versus visible music. Idle recognition passes preserve manual corrections and store alternatives for doubtful measures; they do not silently replace the base reading.

Final local targeted run: release-final-validation.log succeeded (22s), covering RecordingAndStatus, MusicDockLifecycle/Layout, ReaderDrawer, MusicShelfRedesign, ReadingQueue, BackgroundReading, RecordingWaveform, FixStructureFlow, ReaderPanelLifecycle and CleanMask, plus InkSlate Android unit tests. Earlier combined checks passed all core/music-core/desktop regressions and InkSheets Android unit tests. BarCheck's real-library Fix flow passed after correcting background worker starvation.

Handoff: the attempted full local music UI run exposed legacy real-library PageReadTest/OlderReadingTest assumptions that need reconciliation with automatic whole-piece reading and expanded categories. Those depend on local music fixtures and are absent on CI. Do not claim the whole local suite passed: it was stopped to isolate failures. Remaining failures in waveform resolution, category-close navigation and recording score clearance were fixed and pass the final focused run. Re-run/update the real-library manual-reading tests against the new automatic scheduling semantics in the next session, retaining coverage for explicit per-page requests.

Publication candidate includes a recording-start close fix and exact footer height allowance so short landscape screens preserve music space. Earlier candidate runs303/304 are superseded; use the final successful release only.

Build305 blocker reproduced locally: WorkstationAppAuditTest expected a folded shelf to release the score viewport, but the expanded category remained mounted (310dp). Fixed centrally in SheetsState.stripCollapsed: folding clears expandedMusicCategory/musicCategoryPage. The real-PDF workspace audit now passes all12sizes; recording and rail lifecycle checks pass. Test306 source05d1fba contains this fix and song-keyed recording editor state, and is building. Playback pause/resume and continuous shared-output peak compression are the next batch, not part of306.

Test306 published successfully, all CI jobs green: https://github.com/williebob555-lab/InkSlate/actions/runs/40778902063 . Source05d1fba; APK89328341bytes SHA2560d85c369061ebfaea4178b4139280c430a8f449c89a5ddfefd4c08d94a87debe. All four APK/MSI/RPM/watchZIP assets match306.

Playback follow-up: retain RenderAhead queue and ScorePlayer/EnsemblePlayer across pause, including sustained voices, repeat/ramp progress and within-bar position. Pause silences only its source; a separate metronome continues. Bounded producer suspension prevents indefinite UI waits and overlapping renderers. Shared sound output now uses continuous soft peak compression; the previous above-full-scale tanh switch introduced a roughly0.24 waveform jump at the threshold. New tests verify exact sample continuity, repeated pauses, slow fill bounds, sustain continuity, second-source mixing and continuous/monotonic bounded peaks.

Validation: full sheets-core and sheets-desktop CI-equivalent suites passed in3m39s (isolated USERPROFILE; local music fixtures remain separate). Exact final source targeted pause/mix tests plus Android compilation passed afterward in8s. Logs: build/playback-full-regression.log and build/playback-final-source-validation.log. No live loudspeaker output used.
