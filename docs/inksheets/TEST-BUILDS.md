# InkSheets test-build changes

Newest first. These notes describe changes made, not confirmation that every reported device issue is resolved. Performance and remaining toolbar reports are still open. Historical entries are changelogs; only the latest installers are retained on the test release.

## Next test build

- **Background reading respects desktop load.** On Windows and Linux, automatic reading and deeper rechecks pause when CPU use, available system memory, or the app's own memory headroom is tight. Already-started reading yields at its safe checkpoints and keeps completed pages. It resumes after sustained spare capacity, rather than repeatedly stopping and starting. Explicit Read commands still work.
- **Recent build history stays accessible.** Settings → Updates gains a Recent test-build changes link, including when no update is available. Test release notes now include this history instead of only the last commit's title.
- **Still under investigation:** Clean/Mask performance on devices, waveform dragging performance, and reports of old toolbar appearances. The approved dock and Play Together redesign remains upcoming.

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
