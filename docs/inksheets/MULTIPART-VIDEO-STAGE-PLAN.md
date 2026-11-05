# InkSheets semantic scores, transcription, playback, and stage plan

## Outcome

InkSheets will read and work with music that contains one line, alternatives, duets, ensembles,
and full scores. The same semantic score will drive instrument selection, playback, MIDI,
MusicXML, generated sheet music, video transcription, and a visual stage/microphone mixer.

The ordinary PDF reader remains the primary product and the fast path. Opening, drawing,
annotating, turning, or reading a normal single-part PDF must not initialize ensemble playback,
video decoding, spatial audio, or score-generation machinery.

## Product principles

1. **A score is musical truth; a stage is a performance setup.** Notes, parts, alternatives, and
   alignment belong to the score document. Player positions, microphones, room, and mix belong to
   a separate reusable stage scene.
2. **A staff is not necessarily an instrument.** One piano part can own two staves; a duet owns two
   parts; easy and difficult lines are mutually exclusive alternatives.
3. **One semantic result, many sources.** Separate PDFs, a full-score PDF, images reconstructed
   from video, and MusicXML should converge on the same score-document model.
4. **Uncertainty is visible and correctable.** OMR and alignment guesses carry confidence and
   provenance. Ambiguous staff grouping, instrument labels, and bar alignment are reviewed rather
   than silently accepted.
5. **Playback quality is tested as a product.** Correct notes are necessary but not sufficient.
   Timing, articulation, dynamics, balance, stereo image, room, continuity, and latency receive
   repeatable fixtures and listening comparisons.
6. **Optional work stays optional.** Heavy codecs, URL resolvers, advanced room rendering, and
   generated-score UI live behind narrow interfaces and are loaded only when used.

## Target architecture

```text
Sources
  single PDF/image    separate part files    full score    video/URL    MusicXML
          \                 |                    |             |           /
           +----------------+--------------------+-------------+----------+
                                    |
                         semantic ScoreDocument
                    parts -> staves -> measures -> events
                         alternatives + bar alignment
                                    |
           +------------------------+--------------------------+
           |                        |                          |
      notation views          performance planner          exporters
   full/selected/extracted     tempo + expression       MIDI/MusicXML/PDF
                                    |
                          part/stem audio renderer
                                    |
                      simple mix or optional StageScene
                                    |
                      stereo output / WAV / isolated stems
```

### Core score model

- Preserve the existing `Score` serialization and single-part APIs.
- Add a `ScoreDocument` containing stable playable-part IDs and a canonical written-bar timeline.
- A playable part owns one or more existing `Score` streams and metadata: instrument, chair,
  display label, notated-to-sounding transposition, and provenance.
- Alternatives share a group ID and have labels such as `Easy` and `Advanced`; normal playback
  selects exactly one alternative per group unless comparison is explicitly requested.
- Alignment maps source measures to canonical bars and records confidence. Measure number alone is
  an initial hint, not the permanent identity.
- Existing single scores and existing `Song.parts` adapt into a document without rewriting caches.

### Playback architecture

Split playback into three layers:

1. `PerformancePlan`: deterministic note intent, tempo map, dynamics, articulation, humanization
   seed, and per-part events.
2. Part renderers: one independently gainable stream per playable part or section.
3. Output/mix: the current lightweight mono mix, a stereo direct mix, or an optional stage scene.

The first migration keeps the mono API working while introducing stereo frame buffers beside it.
Only after desktop and Android output paths, render-ahead, pause/resume, metronome, and recording
tests pass should stereo become the default ensemble path.

### Stage scene

`StageScene` is stored separately from the score and references playable-part IDs. It contains:

- podium/listener and room dimensions;
- performer or section position, facing, count, spread, gain, mute, and solo;
- microphone position, height, direction, polar pattern, gain, and output bus;
- optional per-song overrides over reusable concert-band, orchestra, jazz, and chamber presets.

The first renderer uses cheap deterministic DSP: equal-power pan, clamped distance attenuation,
small propagation delay, microphone polar response, section spread, and stereo reverb sends.
Convolution/HRTF and measured room responses are later desktop-quality options, never startup
dependencies.

### Video transcription

Video ingestion is an optional job pipeline:

1. Resolve a local file or permitted URL to a seekable cached media source.
2. Sample frames using scene changes and motion, not a fixed high frame rate.
3. Stabilize, deskew, score sharpness, and reject duplicates.
4. Use temporal merging to remove hands, playback cursors, highlights, and compression noise.
5. Reconstruct fixed pages, camera pans, or scrolling staff systems from overlapping frames.
6. Feed normalized page/system images through the existing OMR reader.
7. Merge repeated/overlapping measures into `ScoreDocument`, retaining frame/time provenance.
8. Optionally use the audio track to suggest tempo and validate alignment; it is not ground truth.

Performance-only audio transcription is a separate assisted-draft feature. Monophonic material can
precede polyphonic ensemble transcription; the UI must communicate the different confidence level.

Media decoders and URL-specific resolution do not belong in `sheets-core`. Core owns job models,
frame/page assembly algorithms, cancellation, and progress. Platform modules own decoding and
network access.

There are two intentionally different URL/video uses:

- **Reference mode** controls an official browser-hosted player and stores editable score-to-time
  anchors. It does not download media and does not claim sample-accurate seeking.
- **Transcription mode** requires frames from a local or otherwise permitted, resolvable media
  source. URL resolution is optional, capability-reported, and never a prerequisite for normal
  reading or for the future Arrangement Studio recording loop.

### Full-score OMR

- Detect systems from repeated staff geometry, aligned barlines, vertical gaps, braces/brackets,
  and text labels.
- Maintain independent clef/key/time carry for every staff lane rather than incrementing one
  single-part counter through all staves.
- Group several staves into one playable part where a brace or review choice says so.
- Reuse `InstrumentReader` for labels, but retain printed text and allow manual correction.
- Provide a score-setup review: system boundaries, part/staff grouping, instrument/chair,
  transposition mode, and alternative groups.
- Fall back to the unchanged single-part reader whenever multi-system evidence is absent.

## Future consumer: Arrangement Studio

The design in `ARRANGEMENT-STUDIO.md` is a future workspace built on these foundations, not part
of the current implementation program. Current APIs should leave it a clean path without adding
DAW, browser, project-matrix, job, or take-management behavior to ordinary sheet music.

The foundations deliberately useful to it are:

- stable playable-part IDs and explicit instrument/chair metadata;
- canonical, editable bar ranges and score/reference provenance;
- selected-range audition and reliable tempo/position reporting;
- video/reference timestamps that remain distinct from score positions;
- controller actions that can eventually wait for acknowledged external operations;
- platform-neutral interfaces with optional desktop implementations.

Arrangement Studio remains separate in both responsibility and lifecycle:

- Ableton or another DAW owns recording, take lanes, editing, plug-ins, mixing, and export.
- InkSheets may later own the recording plan and references to takes, stored as separate log record
  kinds rather than a large field on `Song` or `ScoreDocument`.
- A DAW bridge, browser reference page, localhost server, matrix UI, and arrangement persistence
  must not initialize until the workspace is explicitly opened.
- Stage playback is an internal audition/rendering facility. Its microphones and room do not become
  DAW track bindings, and Arrangement Studio does not turn InkSheets into a DAW.
- The future workspace should depend on score/playback contracts; those contracts must never
  depend back on arrangement packages.

If separate modules are introduced, the intended dependency direction is:

```text
sheet reader/UI -> score and playback contracts <- optional arrangement core
                                                <- optional stage UI/renderer
                                                <- optional media import

optional arrangement desktop -> DAW bridge + browser reference
```

No Arrangement Studio models, bridge protocol, browser integration, matrix UI, or persistent job
state are implemented in the phases below unless that project is explicitly started later.

## Delivery program

### Phase 0: baselines and performance gates

- Capture startup, library open, first page, page turn, annotation, and ordinary single-part read
  benchmarks on desktop and representative Android hardware.
- Add playback fixtures: solo wind, repeated tonguing, legato, brass chord, percussion, piano,
  duet, and full band. Store deterministic renders and musical-event expectations.
- Add objective checks for discontinuities, clipping, DC, timing, channel balance, note tails,
  pause/resume continuity, and render-ahead cursor agreement.
- Establish human A/B listening notes for tone, attacks, dynamics, balance, and room. Waveform tests
  must not be presented as proof that a render sounds good.

Gate: later phases may not regress the normal-reader budgets beyond agreed noise thresholds.

### Phase 1: semantic document and exports

- Add backward-compatible multi-part/alternative model and single-score adapter.
- Adapt existing separate `Song.parts` readings into documents at runtime.
- Add type-1 MIDI with conductor and named instrument tracks, including percussion channels.
- Define MusicXML import/export boundaries; implement export after the model stabilizes.
- Add selection APIs for one part, a section, alternatives, or the full ensemble.

Deliverable: existing separately scanned parts can export and play as selected ensembles without
changing ordinary PDF behavior.

### Phase 2: playback-quality foundation

- Make `PerformancePlan` reusable by solo, ensemble, MIDI, and offline rendering.
- Preserve part identity through planning instead of flattening all tones immediately.
- Improve articulation transitions, repeated-note separation, sampled velocity selection,
  dynamic curves, balance, and percussion treatment against the playback fixtures.
- Add stereo buffers/output while keeping a mono compatibility adapter.
- Add offline stereo WAV and isolated-part stem rendering.
- Rework room processing into stereo direct/early/late paths with bounded CPU and no real-time
  allocation.

Deliverable: a clearly improved direct ensemble mix before spatial staging is introduced.

### Phase 3: stage and microphone playback

- Add stage scene persistence, presets, and automatic instrument/chair placement.
- Build a top-down editor with drag, rotate, section expansion, snap, keyboard controls, and an
  accessible property inspector.
- Add microphone creation, pattern visualization, aim, gain, stereo routing, mute/solo, and meters.
- Apply edits live while a short passage loops.
- Export stereo mix and per-part/per-microphone stems.

Deliverable: moving a performer or microphone produces an immediate, repeatable audible change.

### Phase 4: full-score reading and selection

- Add system/staff detection and per-lane musical carry.
- Add score-setup review and saved corrections.
- Align parallel measures and expose confidence conflicts.
- Drive selected-part playback, full-ensemble playback, and stage population from one full-score
  source.

Deliverable: duo, grand-staff, alternative-line, and representative concert-score fixtures split
correctly with human correction available.

### Phase 5: video-to-score

- Implement platform media-source interfaces and local-file decoding first.
- Add fixed-page/page-turn extraction, then camera-pan stitching, then scrolling-score assembly.
- Add deduplication, temporal obstruction removal, provenance, cancellation, resumable cache, and
  review previews.
- Add URL ingestion as a resolver adapter with clear availability/permission failures.
- Feed reconstructed pages into full-score setup and normal correction flows.

Deliverable: a visible-notation video yields an editable score document and MusicXML/MIDI/PDF
outputs without keeping the video decoder active during normal use.

### Phase 6: notation generation and interchange

- Extend engraving to align measure widths across staves and lay out multi-staff systems.
- Generate full score, selected section, and extracted instrument parts.
- Round-trip a supported MusicXML subset with explicit warnings for unsupported notation.
- Preserve source scans/video frames as provenance alongside generated notation.

## Lightweight and reliability requirements

- No video or network dependency on the normal app startup classpath where platform packaging can
  isolate it; otherwise no class initialization until import is chosen.
- No stage UI state, microphone DSP, or ensemble cache allocation while showing a normal part.
- Single-part `Score` caches remain readable; migrations are adapters or versioned sidecars.
- Background media/OMR work is cancellable, bounded, resumable, and lower priority than page draw,
  annotation, foreground reading, and audio callbacks.
- Audio callbacks allocate nothing, block on no worker, and perform no file/network access.
- Large videos use bounded frame caches and content-addressed intermediate pages.
- Feature state is saved separately enough that a corrupt stage or video job cannot prevent a PDF
  from opening.
- Every phase includes unit tests, integration tests, and at least one real-device/manual check
  before becoming the default path.

## Initial parallel implementation slices

These slices are intentionally additive and independent:

1. Multi-part score document, alternatives, selection semantics, and compatibility adapter.
2. Type-1 multi-track MIDI export with conductor/instrument tracks.
3. Serializable stage scene plus tested, allocation-free spatial coefficient calculations.

They establish the contracts needed for later playback, UI, OMR, and media work without changing
the ordinary reader or sound output yet.

## Implementation checkpoint

The first additive foundation is now implemented behind explicit ensemble/stage actions:

- `ScoreDocument` adapts existing single scores and already-read library parts, models multi-staff
  parts and alternatives, retains source provenance, and gives measures editable canonical-bar
  alignment.
- The performance planner keeps part identities and uses canonical alignment while producing
  independent tone plans.
- MIDI type 1 export writes a conductor track and named, transposed instrument tracks, including
  multi-staff parts and the percussion channel. The UI can select already-read library parts for
  ensemble export without starting background recognition.
- The optional stereo direct mixer, serializable stage scene, deterministic direct-path routing,
  mute/solo behavior, and automatic concert-band seating are covered by signal and routing tests.

No video decoder, full-score detector, stage editor, Arrangement Studio behavior, or automatic
ensemble initialization has been added. Those remain later phases and do not participate in the
ordinary reader lifecycle.
