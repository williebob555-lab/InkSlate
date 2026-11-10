# InkSheets test-build changes

Newest first. These notes describe changes made, not confirmation that every reported device issue is resolved. Performance and remaining toolbar reports are still open. Historical entries are changelogs; only the latest installers are retained on the test release.

## Next test build

- **Dense annotations:** Android and desktop now use location chunks and recent-view lookups before requesting stroke geometry. Off-screen visibility checks no longer evict expensive shapes. Cache capacity is unchanged. This targets the threshold found in PHYS161 Exam Review; fully zoomed-out canvas performance still needs device measurement.

- **The reader workspace is now organized by task instead of by whichever controls existed first.**
  The peer tabs are Pages, Part, Playback, Reading, Annotate, Tempo & tuner, Recording and
  Connections. Pages owns page navigation and the page list; Part clearly separates the displayed
  PDF part from the all-song instrument; Playback owns transport, live tempo, loop, band-part
  selection and the mixer; Reading groups Recognition separately from Cleaned score & mask.
  The old floating page/part island and ambiguous corner buttons are gone.
- **Quick tabs and full tools are distinct layouts.** Compact tabs use full-size scrollable touch
  targets and retain the selected rightmost tab in portrait. A full-tools workspace has a 48 dp
  back target, a labeled Switch tab menu and its own close button instead of squeezing every tab
  into a tiny horizontal strip. Reader options and Tab layout now live in a neutral Settings tab,
  which is always last and cannot be hidden; there is no permanent settings stamp outside the tabs.
- **The dock is resizable without spending a toolbar row on resizing.** The labeled Resize bar is
  gone. A 12 dp inner-edge grip drags vertically in portrait and horizontally in Studio/landscape;
  its 48 dp interaction target remains easy to catch. Landscape tabs grow to use spare height.
  Pulling decisively toward closed dismisses the dock and explicitly resets the viewport; ordinary
  Fold preserves pan and zoom. The stored height and width are bounded so the PDF retains space.
- **Tempo is directly adjustable.** Dragging BPM left/right makes coarse five-BPM changes; dragging
  up/down makes fine one-BPM changes. Playback also has explicit −5, −1, +1 and +5 controls, while
  the Timing tab identifies both the metronome and tuner instead of hiding an unexplained tuner
  icon inside the metronome.
- **Band playback now asks before reading missing parts.** Play first checks every selected part's
  saved reading off the interface thread. If any part is incomplete, InkSheets offers exactly two
  choices: Cancel or Read the missing parts. Checking and reading each have a determinate
  part-by-part progress dialog and can be stopped; completed parts are retained. Recognition runs
  one file at a time so a concert-band folder cannot multiply page buffers into a 512 MB heap
  crash.
- **Dock opening respects both default fit and a deliberate camera.** If the PDF is still at its
  default pan/zoom, opening the dock refits the printed page into the remaining viewport and closing
  restores the default fit. Once the user pans or zooms, opening, resizing and folding the dock
  preserve that camera exactly. Closing the tab still discards its camera.
- **Arrange tabs no longer wastes scroll space on static chrome.** Its title, Reset and Done move
  with the workspace content instead of permanently covering part of the list.
- **Compact stylus quick actions are available in both InkSlate and InkSheets.** Settings → Stylus
  can assign the first barrel button to a 164 dp wheel at the pen tip. The same five slots provide
  Pen, Highlighter, Eraser, Colour and Size; Colour reuses those slots for swatches, and dragging
  Size outward/inward changes width without a slider. The wheel disappears after the choice.
  Pen and highlighter now retain independent colours, widths and opacity.
- **Optional scribble-to-erase is guarded against accidental deletion.** A gesture must reverse
  direction repeatedly, traverse the same span several times and actually cross existing ink
  before it is treated as erasing. An uncertain scratch in empty space remains ordinary ink.
- **InkSlate library navigation is more direct.** Folders can be pinned from their menu and appear
  in the Pinned row on Home. Pressing + while a document is open starts the file browser in that
  active document's folder instead of returning to the generic Home menu.
- **Large-song opening avoids a wrong first render and UI-thread PDF destruction.** The remembered
  or requested page is selected before the first preview is rasterised, eliminating the throwaway
  page-1 render seen in Cowboys. The preview uses a lightweight native image layer, and
  `PdfRenderer` destruction runs on an idempotent low-priority close queue instead of blocking
  Compose disposal for 150+ ms.
- **Band balance is perceptual, adjustable and still held to fixed headroom.** Piccolo and upper
  winds are reduced relative to low voices; trombone, euphonium, baritone and tuba receive enough
  energy to survive masking in a full band; percussion is reduced again. The trombone fallback's
  narrow nasal resonance is broadened and weakened. Score tools and Playback → More now include a
  saved per-part 0–200% mixer whose changes apply during playback.
- **Validation:** the complete core, score/audio core, InkSlate Android, InkSheets Android,
  desktop and InkSheets desktop gate passes. The desktop run completed 284 tests with 69
  machine-library fixtures skipped; focused layout coverage includes both phone orientations,
  tablets, laptops, shelf/Studio modes, resize persistence, immediate rightmost-tab selection,
  exact view preservation and the pull-to-close reset gesture. The redesign adds no page or image
  cache and keeps the existing bounded reading and rendering memory limits.

## 1.2.2-test.326 — printed-page priority and UBand stall removal

- **The printed PDF is now the first opening result.** InkSheets opens and rasterises a bounded
  1,024-pixel whole-page preview before it parses embedded handwriting or walks the PDF's
  annotations. That bitmap is shown during the remaining load and handed directly to the live
  page cache instead of being rendered twice. On every later turn, a new page gets one frame of
  original sheet music before handwriting and music-reading overlays are added, so an overlay can
  no longer hold the printed page blank.
- **Large UBand parts no longer search correction storage from the UI thread.** The build 325
  trace caught `ScoreTools.carryIn/fixesOf` spending 24.7 seconds enumerating edit folders and a
  second path spending 19.5 seconds rereading a whole PDF to find its correction identity.
  Corrections are now loaded once on a low-priority worker into a bounded RAM snapshot; page
  drawing, Fix, Clean and cross-part checks consult RAM only. Writes take effect in RAM
  immediately and are serialized to synced storage off the button thread.
- **PDF identity uses constant memory.** Finding the first PDF revision now streams through a
  small buffer instead of `readBytes()`-allocating the entire concert-band part. Correction
  snapshots retain at most four documents and evict older large paint-mask payloads above a 6 MB
  shared budget, preserving the current part without recreating the build 318 memory pressure.
- **The remaining PDF renderer locks are off the UI thread.** Page-count checks use the immutable
  count captured at open, imported-ink page dimensions run on I/O, and the page manager reuses
  already measured dimensions. Diagnostic timestamps and file appends also moved off the UI;
  the former five-second cold `TimeZone/SimpleDateFormat` initialization cannot block a turn.
- **Full-band playback has static headroom and stronger low brass.** A fixed pitched-section energy
  budget replaces continuous reliance on the final limiter. Tuba/euphonium/baritone receive 1.5×
  relative weight, trombones 1.35× and other bass voices 1.18× without increasing total band
  energy; percussion's separate transient budget is reduced again. Diagnostics now report section
  gains and warn when the limiter still runs on 60% or more of blocks.
- **Validation:** Android compilation and memory-budget tests, 20-part headroom/percussion tests,
  correction-cache/constant-identity tests and the isolated CPU-saturation render-ahead test pass.
  The broad run covered 459 tests with one skipped and one render-ahead timing failure while
  Gradle was concurrently compiling; that same stress test passed when run alone.

## 1.2.2-test.325 — score-cache stalls and percussion balance

- **Page turns no longer wait on score-cache storage.** The build 324 stall stacks found a
  2.9-second directory enumeration and 172 ms score-JSON decode running from `ScoreTools` on the
  main thread. Screen code now performs a memory-only lookup while two low-priority workers load
  and refresh the displayed score. Large-band preparation likewise opens and decodes parts on its
  worker pool, then hands one prepared map to playback instead of reopening each part from a click.
- **PDF page count no longer contends with rasterization.** An open document captures its immutable
  count once on the opening I/O thread. The 151 ms `PdfPageSource.getPageCount` lock in the Tyrell
  trace is therefore removed from `syncPage` and every other turn-time caller.
- **Band percussion has separate gain staging.** Drum parts no longer reduce the pitched band's
  normalization level merely by existing. Their high-crest transients receive a much smaller
  independent budget, a continuous 1.5 ms strike edge and per-hit soft saturation, preventing
  snare/cymbal output from presenting as full-scale pops. A dense-band regression requires the
  pitched section to remain above two simultaneous drum parts without clipping.
- **Diagnostics distinguish asynchronous cache cost from a UI freeze.** Score-cache loads and
  whole-band preparation record their file/part counts, elapsed time and that the work was off the
  UI thread.

## 1.2.2-test.324 — field playback and long-page stalls

- **Dense full-band playback now starts inside a guaranteed realtime budget.** Ensembles of 7-12
  parts synthesize internally at 24 kHz and larger groups at 16 kHz, then stream through a
  no-allocation linear adapter to the 48 kHz Android output. Large groups begin dry with four
  harmonics instead of spending their opening seconds repeatedly degrading while the queue
  starves. The producer yields to the interface once it has 300 ms in reserve, not while dry.
- **Printed multi-measure rests get one continuous playback pass.** Ensemble alignment still
  expands the hidden bars for timing, but the visible progress rail crosses the one printed rest
  once instead of resetting for every hidden bar and appearing to loop.
- **Long music pages are cheaper to turn.** Landscape music rasters now match the actual fitted
  page width (about 1,250 px on the affected tablet) rather than 1,600 px. Zoom still asks the PDF
  for a sharp region. The blank-page check now examines every raster row, preventing thin staves
  from being missed and the entire page being rendered a second time in print mode.
- **Large arrangements no longer make every part urgent work.** Only the PDF in front receives
  current-piece background-reading priority; other UBand parts remain ordinary idle library work
  until Band playback explicitly requests them. Recognition waits eight seconds after display or
  input activity before resuming.
- **Repeated part/song opens skip reparsing unchanged plain PDFs.** A no-ink result is cached by
  path and file fingerprint, while a Syncthing or external change invalidates it immediately. The
  Cowboys field trace showed 7.8 seconds in document open and only 15 ms in rendering.
- **A main-thread stall sampler now records the blocking stack.** Turn diagnostics can identify
  the code holding the interface during a 150 ms+ freeze rather than only reporting the late
  frame after it has recovered.

## 1.2.2-test.323 — long-book and ensemble CPU bounds

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
