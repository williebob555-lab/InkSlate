# InkSheets test-build changes

Newest first. These notes describe changes made, not confirmation that every reported device issue is resolved. Performance and remaining toolbar reports are still open. Historical entries are changelogs; only the latest installers are retained on the test release.

## Next test build

- **Export selected instruments as a real multi-track MIDI.** The score tools can now build an
  ensemble file from complete readings already cached on the device, with one named track per
  selected instrument and a shared conductor track. Unread parts are reported and omitted; the
  export never starts recognition or waits on every library part in the normal reading path.
- **Multi-part scores now have a lightweight semantic foundation.** Parts may own multiple staves,
  easy/advanced alternatives are explicit, source measures map to durable canonical bars, and
  source-file/video provenance stays separate from the notation. Existing single-part caches are
  adapted rather than migrated.
- **Stage and microphone placement is an opt-in score tool.** The top-down editor starts from
  automatic concert-band seating and a stereo cardioid pair. Performers and microphones can be
  dragged; gain, pan, pickup pattern, mute, and solo can be adjusted. Scenes use isolated,
  versioned per-song sidecars and a corrupt sidecar falls back to the preset. Stage audio machinery
  still does not initialize during ordinary sheet reading.
- **Band playback can use only the instruments you choose.** Score tools now has a Band instruments
  picker scoped to the current song. Unselected parts are not read or synthesized when Band
  accompaniment starts; changing songs returns to all available parts.
- **Video and full-score work has bounded core seams, not reader overhead.** Optional video jobs
  can enforce frame/byte/page budgets, select sharp scene changes, merge nearby frames to remove
  temporary obstructions, and retain time provenance. Detected staff geometry can be grouped into
  reviewable score systems. Decoders, URL resolvers, staff detection, and review UI are not loaded
  or invoked by normal single-part reading.

- **Music reading no longer exhausts a 512 MB Android heap.** The page thresholding pass now uses rolling row-sized totals instead of two 64-bit buffers as large as the whole page, and it reuses the reader's existing greyscale pixels. Android also gives each recognition raster a live heap-relative pixel ceiling, pauses automatic reading when that safe budget disappears, bounds decoded-score and Fix-picture caches, and clears them on system memory pressure. The replacement thresholding is pixel-for-pixel equivalent in tests; the reported Galaxy Tab crash still needs confirmation on the device.

- **Android memory use is bounded during performance tools.** Crash reports from build 313 showed `OutOfMemoryError`: page rasters and several independent image/tool caches could together consume more than a tablet heap. InkSheets now gives resident pages a smaller heap-relative budget, caps Mask's notation raster at about 8 MB, recycles evicted Android bitmaps and falls back to vector drawing if that optional raster cannot be allocated.
- **Follow-up after device crashes in 314.** The first bound was applied after a whole read-ahead batch, and every prefetched page was mistakenly protected as if it were visible. Eviction now happens after each page and protects only pages actually on screen. Background setlist tabs no longer each retain a full-page warm raster, released surfaces explicitly recycle page/turn images, whole-page music renders against the screen's shorter side, and the disk page cache will not make an oversized second pixel copy. Thumbnail and embedded-image caches now scale with the device heap and clear on Android memory pressure.

- **One PDF can now supply every part.** Automatic instrument reading detects titled instrument pages inside a combined parts PDF and turns them into inclusive multi-page ranges that still share the original file. Existing bookmarks, recording excerpt pairings and page turns are remapped to the new ranges. The 142-page “Give Us This Day” pack is covered as 34 parts, including Piccolo pages 1–4, Euphonium 102–105 and Piano 116–124.

- **Bookmarking from the reader is direct again.** The footer button marks or unmarks the page immediately instead of opening the heavier excerpt editor.
- **Bookmark removal no longer traps navigation.** A removed bookmark still fades while the Bookmarks tab remains open, but leaving the tab commits the removal immediately instead of waiting for the animation.

- **Opening tools preserves the view.** Opening the annotation bar, full tools, shelf or side rail no longer recentres the page. Closing either tool surface still fits the page as before.

- **Clean stays off until explicitly requested.** Old Clean data without a saved visibility choice is hidden on open, so it cannot appear unexpectedly during a performance.

- **Expanded tools stay on the dock's edge.** Opening a category replaces the shelf or the selected left/right rail, with the music fitted beside it. Switching categories keeps the same layout; Score gets a small extra allowance when needed.
- **Denser Score tools.** Less padding, a compact read-status line and more columns on wide shelves leave more actual tools visible. Playback sound and tempo-ramp settings open within the dock, with Back returning to the category.
- **Recording excerpt menus stay in the dock.** Choosing a pairing or entering a page range no longer requires a separate popup over the score.
- **Recording opens on playback, not setup.** The selected recording's transport, waveform and loop handles lead the expanded view. Record remains a direct microphone action; the recording list and detailed settings replace that same dock page when opened.
- **Play Together starts with your role.** Lead/Follow choices, relevant rehearsal shortcuts and connected-device heartbeat feedback replace the crowded landing page. Connection administration and remote settings remain available as inline detail pages.
- **Mask drawing reuses unchanged music.** Static notation/retained print and mask history are cached separately from the moving brush. Native masked drawing can reuse a bounded raster of the unchanged notation at the current display scale; device responsiveness still needs verification.

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
