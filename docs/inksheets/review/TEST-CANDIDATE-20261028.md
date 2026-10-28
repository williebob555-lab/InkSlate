InkSheets test candidate — 2026-10-28

This batch includes category pages inside the music bar, zoomable recording waveforms and draggable loop bounds, excerpt/page-range recording pairing, automatic queued background reading, structural measure splitting and multi-bar rest correction, reversible page crops, setlist default instruments, adjustable metronome beat counts and live playback click muting, and the requested footer/tab cleanup.

The setlist instrument control sits between the set title and Start/+Songs. A differing instrument prompts Switch/Keep current; declining stays dismissed for the session.

Regression scope includes shared InkSlate crop/viewport code, Android compilation, twelve responsive recording/category layouts, correction persistence, background scheduling, waveform gestures, excerpt serialization, setlist sync, and mask notification behavior. Full-suite results and published build identity will be appended after verification.

Remaining review: physical-device evaluation, especially Android keyboard-resized forms, crop gestures with existing ink, background-reading battery/thermal behavior, and the subjective balance of expanded bars versus visible music. Idle recognition passes preserve manual corrections and store alternatives for doubtful measures; they do not silently replace the base reading.
