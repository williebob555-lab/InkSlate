# Sampler instruments (DecentSampler / SFZ) — spec

The user's sampler app (`C:\Sampler App`, exporter `src/export/decentsampler.ts`, `sfz.ts`) exports an
instrument as a folder: `<name>.dspreset` (XML) or `<name>.sfz`, plus `samples/*.wav`.

## .dspreset as written by that app
- `<DecentSampler><groups volume="xdB" seqMode="round_robin|random">`
- `<group name attack decay sustain release volume="xdB" pan seqPosition trigger="release" tags silencedByTags>`
  - round robin: one group per slot, `seqPosition` on the group
  - articulations: `tags="art_<id>"`, switched by `<midi><note note=K>` keyswitch bindings (AMP_VOLUME 1/0 per tag)
  - `trigger="release"`: release samples (play at note-off)
- `<sample path="samples/x.wav" rootNote loNote hiNote loVel hiVel start end loopEnabled loopStart loopEnd loopCrossfade volume="xdB" pan tuning(semitones)>`

## What InkSheets does with it
- Settings → Sounds: "Add an instrument…" picks a .dspreset/.sfz (or its folder/zip); copied into the
  library's `.inksheets/instruments/<name>/` so it syncs; assign to instrument names (Euphonium, Trumpet…)
  replacing the built-in synth voice for that part, in solo and band playback.
- A `SampledVoice` in sheets-core implements the same Tone interface the synth voices do: zone chosen by
  key+velocity, resampled to pitch (linear or better), loop with crossfade, group ADSR, round robin,
  volume/pan/tuning, release-trigger groups. Legato under a slur: crossfade (~30–60 ms) into the next
  note's zone instead of a fresh attack when no legato articulation exists; when an articulation tagged
  legato/staccato/accent exists, use it for the matching notes.
- Streams from memory (WAVs decoded once, 16/24-bit PCM, mono/stereo), cheap enough for the 8-part band
  under the render-ahead budget.
- Tests: parse a fixture preset (write one in the test), zone selection, loop seamlessness (no
  discontinuity at loop point), legato crossfade without dip, render speed.
