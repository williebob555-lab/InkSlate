# Multipart playback stabilization review

Reviewed: 2026-11-05

## Bottom line

The new score, stage, video, and export work contains useful foundations, but only a subset was
connected to the live reader. The largest user-visible defect was not subjective audio quality:
the legacy live ensemble player still aligned parts by a fresh bar-number lookup for every bar,
and ordinary playback treated every part in a combined PDF as one continuous score. The app could
therefore play or display the wrong part even though the newer semantic score model was correct.

This pass stabilizes the existing live path without making the normal reader construct a
`ScoreDocument`, stage, video pipeline, or stereo graph. It is a bridge to the planned performance
engine, not the final engine.

## Findings and action

| Area | Finding | Action in this pass | Remaining work |
|---|---|---|---|
| Part ownership | A combined band book played past the current part into the next instrument and exposed cache-only bar numbers such as 26069. | Scope playback and bar navigation to `Transcriber.partOf`; retain cache offsets internally and show local bar numbers. | Move part ownership out of UI offsets and into the semantic score timeline. |
| Ensemble synchronization | `EnsemblePlayer` selected each other part's measure with `firstOrNull` by printed number. Duplicated/misread numbers silently selected wrong music. | Added monotonic written-sequence alignment with shared repeat order and explicit silent slots for multimeasure rests. | Persist and review confidence-backed canonical alignments; do not infer every session. |
| Page turns | The playback watcher automatically returned to its page after a manual page flip. | Manual page navigation suspends automatic following; a visible Follow score action resumes it. Automatic turns get a bounded settle window. | Add two-page/cue-page modes. |
| Scheduling | `Synth` removed every note from index zero of an `ArrayList`, shifting the entire remaining score for every note (quadratic work). | Replaced front removal with a cursor and coarse compaction; live playback now reports buffer recoveries. | Add render-load telemetry and adaptive quality tiers. |
| Render ahead | Playback opened the device with an empty render-ahead queue. Dense first blocks could underrun immediately. | Added real prefill; ensemble playback uses a deeper bounded queue and exposes buffered time/underruns. | Tune automatically from measured device latency and render load. |
| Percussion | Generic Percussion parts mostly became a drum kit or piano; MIDI could put pitched percussion on channel 10. | Added named pitched percussion, orchestral single-instrument mappings and synthesis, plus correct MIDI program/channel/key behavior. | Add rolls/tremolo interpretation, damp/choke semantics, cymbal techniques, multi-percussion changes, and quality samples. |
| Stereo/stage | Stereo output and stage coefficients exist, but live score playback is still mono and the stage editor is not audible. | No hidden hookup was added in this stabilization pass. | Introduce a part-bus mixer, then make `StageScene` an optional consumer of those buses. |
| Video import | Core job/frame-selection/merge contracts exist, but there is no platform decoder or URL resolver. | No decoder was added. | Local-file decoder first, reconstruction preview/review, then capability-reported URL adapters. |
| Full scores | Geometry groups supplied staff lanes, but the normal reader does not detect lanes or build playable parts from them. | No change to the single-part reader. | Staff-lane detector, score-setup review, per-lane carry, then canonical alignment. |

## Required next engine shape

The live path should converge on one immutable `PerformancePlan` containing canonical bar
occurrences, tempo, part events, percussion technique, and page provenance. A bounded real-time
engine should consume independent part buses from that plan. The ordinary solo reader may use one
bus and a mono adapter; ensemble/stage playback may opt into multiple stereo buses. Page display,
MIDI, offline stems, and the future Arrangement Studio should read the same canonical occurrence
IDs instead of recomputing bar positions independently.

Arrangement Studio remains a future consumer. These fixes improve the contracts it will need
(stable part scope, truthful bar position, repeat-aware shared transport), but add no DAW bridge,
project matrix, browser player, job state, or take management to the reader.

## Acceptance gates before calling multipart playback complete

1. Two, four, and full-band fixtures remain on the same canonical bar through pickups, repeats,
   endings, multimeasure rests, missing pages, and one deliberately corrupted measure number.
2. Manual page turns never seek or restart audio and never snap back unless Follow score is enabled.
3. A 30-minute dense ensemble has zero callback allocation, bounded memory, and reported underruns.
4. Pitched and unpitched orchestral percussion have correct routing and a documented fallback when
   the exact instrument or technique is unknown.
5. Live ensemble playback, MIDI export, offline stems, and page position use the same plan.
6. Startup, normal page turns, annotation, and single-part reading stay within the pre-ensemble
   performance budget.
