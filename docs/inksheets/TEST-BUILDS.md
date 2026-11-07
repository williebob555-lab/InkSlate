# InkSheets test-build changes

Newest first. These notes describe changes made, not confirmation that every reported device issue is resolved. Performance and remaining toolbar reports are still open. Historical entries are changelogs; only the latest installers are retained on the test release.

## Next test build

- **Long method books no longer make every animation frame visit every page.** In one-page music
  mode, a turn now repositions and draws only the page being left and the page being entered;
  hundreds of off-screen page rectangles are no longer allocated and checked each frame.
- **A cached score overlay is now consulted before reconstructing the corrected score.** This
  removes the 5-12 ms overlay lookup visible on Tyrell pages with zero marks, while retaining the
  separate playback cursor layer introduced in build 322.
- **Whole-band playback no longer outranks the interface.** Render-ahead synthesis yields between
  blocks at normal priority, while the urgent Android output thread and deep audio queue remain in
  place. Tempo presses publish immediately and multiple presses coalesce into one retime on the
  synthesis thread instead of rebuilding thousands of scheduled tones on the UI thread.
- **Dense ensembles have an adaptive realtime budget.** They start with the twelve most audible
  partials per voice; if a 10 ms synthesis block still takes 7 ms or more, detail steps down before
  the producer can monopolize a core or drain into catch-up pops. Notes, parts, rhythm, dynamics
  and instrument selection are unchanged, and every downgrade is written to diagnostics.
- **Band prefill no longer sleeps the interface.** Android output starts quietly while the 500 ms
  render-ahead queue fills on its producer thread; it does not consume the playback cursor or
  report a false underrun, and Play returns to the UI immediately.
- **Playback now belongs to the song that started it.** Going Home, changing songs or switching
  parts immediately cancels active playback and any band still being prepared; finishing a
  background part read can no longer resurrect the previous song. Document-open time is recorded
  separately from page rendering in diagnostics.

## 1.2.2-test.322 — playback, page-turn and memory performance

- **Playback no longer rebuilds the whole score overlay for every cursor or buffer update.** The
  stable notation is cached as a display list and the moving playback indicator is drawn as a
  separate tiny layer. A regression test runs 100 cursor frames without allowing another static
  overlay build.
- **Band playback has substantially more underrun protection.** Ensemble synthesis now holds up to
  1.2 seconds ahead and prefills 500 ms before starting; solo playback also gets a deeper default
  queue, and Android's output buffer is doubled. Both producer and Android-output underruns are
  recorded with buffered time and the slowest synth block so popping can be diagnosed directly.
- **512 MB tablets retain less speculative work.** Music page rasters are capped at 32 MB, hidden
  setlist editors are not preloaded on constrained heaps, and opening a music part no longer scans
  every PDF page for geometry. Page-render, overlay-build, heap and resident-page timings are added
  to turn diagnostics.
- **Validation:** focused overlay, render-ahead, playback-speed and Android memory suites pass
  locally. Diva-with-band and page-turn behavior still require confirmation on the affected tablet.

## 1.2.2-test.321 — multi-pass reading and interaction priority

- **Background reading can be turned off independently.** Settings → Read Music now has a
  Background reading switch. Manual Read and Fix actions remain available when it is off.
- **Automatic reading is lower-impact and more memory-bounded.** It waits after app input, yields
  during display work and playback, uses one recognition worker in the background, adapts raster
  size to current heap headroom, and clears/cancels rebuildable work after allocation failure rather
  than immediately retrying it. Page and decoded-score caches are smaller on constrained devices.
- **Multi-pass reading now records evidence instead of trusting green once.** Idle audits revisit
  every generated measure, including green ones, at several scales and crops. Dot, rest and
  multi-measure-rest specialists contribute separate evidence, and songs receive a confidence
  priority for later passes. These passes do not yet silently replace notation or recolour bars.
- **Fix distinguishes triplet failures.** “Triplet missing” offers readings that add a triplet;
  “Triplet invented” offers readings that remove one. The direction is retained in accepted-fix
  and skipped-bar training records.
- **Playback following is steadier.** An asynchronous automatic page turn is requested once rather
  than re-queued every 100 ms, and an isolated under-filled green measure in the middle of a passage
  no longer collapses the playback timeline and races the marker across the page.
- **Interaction work takes priority.** Page gestures, keys and buttons extend a quiet window before
  automatic recognition resumes; library planning no longer opens every measure audit while a song
  is being switched.

## 1.2.2-test.320 — playback diagnostics

- Added visible render-ahead buffer/underrun status to playback so an audio recovery can be told
  apart from a score-position error.
- Added a visible action to resume automatic score following after a deliberate manual page turn.
- This build includes all alignment changes from 319; it did not add another alignment algorithm.

## 1.2.2-test.319 — playback alignment and percussion

- Scoped solo playback and bar navigation to the current instrument inside a combined-parts PDF,
  keeping internal cache offsets out of the displayed bar number.
- Replaced cross-part first-match-by-number alignment with monotonic written-order alignment that
  follows shared repeats and leaves explicit silence for multi-measure-rest gaps.
- Manual page browsing suspends automatic playback following. Playback rendering now prefills a
  bounded queue and avoids quadratic note scheduling on long/dense scores.
- Added routing and synthesis for named orchestral and pitched percussion, including corrected MIDI
  channel/program behavior.

## 1.2.2-test.318 — multipart, stage and export foundations

- Added selected-instrument Band playback controls and multi-track MIDI export from already cached
  readings.
- Added the semantic multipart score/alignment model used by later work, plus bounded core pieces
  for full-score/video import. The normal reader did not yet construct that model automatically.
- Added an opt-in stage/microphone editor with versioned per-song scenes and stereo-output
  capability. Existing live score playback remained mono; the stage was not yet audible.

## 1.2.2-test.317 — reader heap bound

- Delivered the reader-heap changes whose 316 workflow failed: rolling threshold totals, reuse of
  greyscale pixels, a live heap-relative recognition raster ceiling, bounded decoded/Fix caches,
  and cancellation/cleanup on Android memory pressure.
- Moved the Android reader memory test to the InkSheets-specific test source set so the build could
  complete. Device confirmation was still required.

## 1.2.2-test.316 — not published successfully

- The workflow for this build failed. Its Android reader-heap changes were included in successful
  build 317 instead; there was no complete 316 installer to evaluate.

## 1.2.2-test.315 — Android page-raster OOM follow-up

- Evicted read-ahead pages after each page and protected only pages actually visible, instead of
  protecting every prefetched page until a whole batch ended.
- Removed full-page warm rasters from background setlist tabs, recycled released page/turn images,
  bounded whole-page music rendering and disk-cache copies, and scaled image caches with heap size.

## 1.2.2-test.314 — Android memory, combined PDFs and dock corrections

- Bounded resident pages and Mask notation rasters, recycled evicted Android bitmaps, and allowed
  optional masked rendering to fall back to vectors after allocation failure.
- Detected titled instrument ranges in a combined parts PDF and remapped bookmarks, recording
  excerpts and page turns to those shared-file ranges.
- Restored direct bookmarking, fixed bookmark-removal navigation, kept the page stable while tools
  open, made Clean opt-in on old data, and introduced the in-place denser music dock/category flow.

## 1.2.2-test.313 — desktop load and update history

- **Background reading respects desktop load.** On Windows and Linux, automatic reading and deeper rechecks pause when CPU use, available system memory, or the app's own memory headroom is tight. Already-started reading yields at its safe checkpoints and keeps completed pages. It resumes after sustained spare capacity, rather than repeatedly stopping and starting. Explicit Read commands still work.
- **Recent build history stays accessible.** Settings → Updates gains a Recent test-build changes link, including when no update is available. Test release notes now include this history instead of only the last commit's title.
- **Still under investigation in this build:** Clean/Mask performance on devices, waveform dragging performance, and reports of old toolbar appearances. The approved dock and Play Together redesign was not yet included.

## 1.2.2-test.312 — corrective follow-up

- **Restore the original Fix editor.** Removing the old music sidebar had also removed the only place the editor was mounted. Fix now opens independently of that sidebar; entering and closing it is tested through the viewer.
- **Keep slurred notes connected with tenuto/marcato.** Those markings could still override the slur and cause separate attacks. Inferred breathing points in long phrases could also interrupt written slurs. Recorded clarinet, tenor sax and trumpet playback now have regression coverage for these cases. Explicit breath and staccato markings retain their separation.
- **Read the other Band parts before playing them.** A partly read shared PDF previously counted as a complete cache for every part. Other parts could therefore be empty while the current part alone sounded. Readiness now checks each part's assigned pages and excludes empty readings from playable voices. This addresses that specific failure; broader Band development is deferred.
- **Make Clean respond after selecting an empty Mask.** Mask selection itself leaves the original page visible. An explicit press of Clean before any brush paint now enables whole-page Clean instead of doing nothing.
- **Restore instrument preloading** in the viewer after removal of the old sidebar, so assigned sample instruments can load before the first Play.
- **Validation:** viewer-entry Fix/Clean/Mask/Tuner/sound checks, partial Band-cache fixtures, recorded-instrument slur renders and Android compilation passed locally. Device performance is not considered verified by those tests.

## 1.2.2-test.311 — first corrective pass

- Added Tone, Woodblock, Rim click and Soft tick metronome sounds, an explicit Preview action, and saved selection across reopening. The selected voice is shared with score clicks and count-ins. **The sound options were confirmed working by the user.**
- Separated saved Clean visibility from mask-only display and scoped live brush previews to their document. Later testing found the empty-Mask-to-Clean transition still needed the fix in 312.
- Changed repeated pitches under a slur to sustain without a new attack. Later testing found tenuto/marcato and inferred phrase boundaries still needed the fixes in 312.
- Cached waveform geometry, enlarged immediate handle grabs, kept the zoom window stable after trimming, and throttled live loop previews. **The reported device lag remains open.**
- Made shelf Play respect Band selection. This did not address the incomplete-cache failure fixed in 312.
- Regenerated recognized articulations, dynamics, ties and slurs for Clean, and updated reading-cache versioning. Existing readings may need refreshing before newly recognized geometry appears.
- Removed old music sidebar mounts and restored the labelled Tuner footer shortcut. Sidebar removal inadvertently detached Fix, corrected in 312; remaining toolbar reports stay open.

## 1.2.2-test.309 — audible playback position

- Page following and the playback marker track audio actually consumed by the output device instead of music rendered ahead into its buffer.
- Natural playback completion waits for the final queued audio to finish, keeping the final notes and marker from stopping early.

## 1.2.2-test.308 — recording excerpts

- Scoped recording playback and automatic page turns to the recording's paired excerpt/page range, including studies inside methodbooks.
- Corrected the primary-instrument switch prompt for setlists.

## 1.2.2-test.307 — pause/resume and mixing

- Preserved queued audio when pausing and resuming so playback continues at the same position instead of reconstructing from a whole bar.
- Smoothed peak control when multiple voices play together to reduce abrupt clipping in mixed playback.

## 1.2.2-test.306 — category and recording state

- Folding the music shelf also closes its expanded category, rather than leaving the large category panel mounted.
- Scoped recording editor selection/drafts to the song so switching pieces does not reuse another song's editing state.
