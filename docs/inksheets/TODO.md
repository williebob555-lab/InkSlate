# InkSheets — TODO (the workstation)

## Current follow-up — 2026-10-28

### Latest user scope — full category bars, playback editing and automatic reading

Implement all of these while preserving annotation/Fix features; test small-screen controls rather than accepting slivers of content:

- [x] Music category submenus replace the compact shelf/Studio widgets with one expanded category bar in the same style. Use always-available category tabs in all orientations, with an explicit return to compact categories. Remove redundant three-dot category menus and the Score Tools duplicate. Avoid separate category popups.
- [x] Expanded Recording category: higher-resolution real waveform, tap to seek/start, draggable loop range with independently draggable left/right ends (Ableton-style interaction); preserve existing playback controls.
- [x] Read defaults to the whole piece. Background single-worker queue prioritizes other parts of the current piece, then unread library music while viewing. Pause/yield around writing/playback and preserve corrections. Idle deeper passes improve doubtful measures without undoing manual fixes.
- [x] Fix can split incorrectly merged measures and correct multi-measure-rest counts, preserving full read/playback coverage.
- [x] Metronome beats use decrement/increment rather than2/3/4/6chips; show a proper triplet subdivision glyph. Allow metronome off during score playback.
- [x] Footer: labelled Metronome beside Excerpt; page X/X opens Pages; remove quick Highlight and add Redo. Audit More and other menus to eliminate duplicate actions while retaining specialist access.
- [x] Pages supports page cropping, preserving annotations and reversibility/clear preview.
- [x] Pair recordings to excerpt bookmarks/page ranges within methodbooks, rather than only the whole song/book; retain whole-piece pairing.
- [x] Finish prior Mask notification and drawer owner/Back/minimum-form-height fixes, including small-screen/keyboard cases.

Latest scope is implemented in the 2026-10-28 test candidate. Combined regression and publication verification are in progress; see review/TEST-CANDIDATE-20261028.md. Setlist default instruments and removal of redundant PDF tab icons are also included.

User requires continuous implementation and tested GitHub builds, not status-only turns. Trumpet timbre polishing is explicitly deprioritized. Retain the dark option; the real tenor sax prototype was accepted. **Test.302 is published and verified** from `c73d559`, with matching APK/MSI/RPM/watch assets. Full local suite:1,105passed,78gated/skipped,zero failures; GitHub run40689166315alljobs passed.

- [x] Viewer editors use reserved extended bars; recording drawer closes when capture starts, leaving live stop/status controls.
- [x] Right/left Studio preference; single-row footer from 600dp; original Fix and full annotation palette retained.
- [x] Mask selection opens original brush palette. Cache completed mask paths and glyph geometry; preserve fill rules while copying native paths so erased holes stay erased.
- [x] Source head/rest/stem/beam/preamble anchors with correction fallback; real PDF position checks and comparison crops. Preserve native articulations/dynamics/hairpins by their original contours while their semantic owner remains unchanged.
- [x] Direct lead/follow shortcuts outside Studio scrolling; keep preferred message presets directly accessible.
- [x] Separate phrase slurs from ties; partial chord ties retain untied attacks. Accent emphasis settles into the phrase; staccato alone does not imply extra force.
- [x] Resource-backed recorded defaults with bounded decoding cache, user sample overrides, long sustain loops and source-layer level balancing. Current pack covers trumpet/open+straight mute, trombone, clarinet and tenor sax. Other instruments retain synthesized fallback; euphonium/sax-family coverage remains incomplete.
- [x] Combined regression, final layouts/crops, matching APK/MSI/RPM/watch publication and verification: test.302.
- [~] Continue next follow-up while test.302 is available: suppress redundant Mask notifications, scope drawer stacks to each window/state, preserve duplicate-title drawers, close the top drawer on Escape/Android Back and validate cramped text editing.
- [~] Guard Chester's small cue notes/full-bar rests and preservation of cue ink after Clean; avoid changing recognition rules based on a classical-only diagnostic that the production reader already handles correctly.

See `review/FOLLOWUP-20261028.md` for scope, evidence and known limits.

## Reliability and music overlays — approved 2026-10-26

**Latest direction 2026-10-27:** Approved HTML demos are the visual baseline, not merely inspiration for regrouped old buttons. Implement real metronome lights, recording waveform, device rows, page/navigation footer, and externally separated dock settings. Replace legacy music sidebars with adaptive Shelf/Studio/Auto constrained layouts; preserve original InkSlate annotation palette. Keep consistent landscape-style home actions in portrait, prominent search, color hierarchy, manual song/setlist colors and alphabetical grouping. Redo Play together, Details and Part assignments against demos; remove obsolete companion-file option in InkSheets. Add easier watch installation/update and excerpt bookmarks (range/title/reminder/notes), plus continue-last-setlist.

**Explicit correction:** Fix mode must remain exactly the original layout/behavior. Its unshipped redesign was discarded; BarCheck restored from HEAD. Ensure the shelf never reduces or covers original Fix. Clean must visibly toggle, unread pages clearly state NOT READ with unavailable actions disabled, and optional brush masking must reuse existing All tools brushes. Floating panels must not snap right on repeated drags.

User approved all six follow-up priorities and polished music overlays, including playback indicators. Preserve annotation parity and direct access; avoid covering notation. Root owns playback failure/recovery, listening confidence, shelf/status and on-page indicators; reliability agent owns synced-list merges; Android agent owns audio focus/headphones; panels agent owns correction editor. All changes use isolated test libraries. Serialize Gradle runs whenever shared dependency JARs change. Existing train outputs remain uncommitted.

- [x] Actionable recording failures, safe retry/locate/choose flows.
- [x] Recover interrupted takes and retain song association from recording start.
- [x] Merge independent recording/setlist edits across devices without restoring intentional removals.
- [x] Android audio interruptions and headphone safety.
- [x] Honest listening confidence and rehearsal-state tests.
- [-] Specialist correction editor redesign rejected by user; original restored. Test original Fix with the surrounding dock instead.
- [x] Polished, legible playback/listening/recording indicators that leave notation readable.
- [x] Added user scope: heartbeat-based remote/companion liveness without requiring commands; explicit connected/reconnecting/disconnected feedback.
- [x] Added user scope: standalone Bluetooth discovery/pair/connect/reconnect when the tablet has no Wi-Fi connection; preserve Wi-Fi operation and old-client compatibility.
- [x] Full regression, Android packaging, all twelve UI sizes; published and verified **1.2.2-test.300** (`b296f46`), all APK/MSI/RPM/watch assets match. See `review/WORKSTATION-20261027.md`.

## Reconstruction alignment and musical articulations — approved 2026-10-27

User requests a concrete explanation, then implementation while they may offer adjustments. Align reconstructed elements to original positions/scale rather than losing local geometry; improve recognition and playback of accents, slurs, ties and rearticulation (soft la, normal da, firm ta). Preserve original Fix and annotation workflows. Panels owns engraving/overlay mapping; reliability owns optional score geometry and recognition capture/classification; home owns interpretation/synth/sampler. Root owns integration, reading compatibility, QA and next test publication. Serialize Gradle; use silent/offline audio demos only, never automatic laptop playback.

- [~] Add source anchors for heads/chords, rests, stems/beams, preamble, accidentals/dots; retain safe edited-score/old-reading fallback.
- [~] Distinguish tie evidence from slurs; retain articulations/phrase attachments accurately across bars/staves.
- [~] Separate sustain, smooth transition, soft reattack, normal and firm attacks; preserve rhythm, accents and sample/synth parity.
- [ ] Quantitative alignment checks, actual before/after crops, offline listening comparisons, regressions, publish verified follow-up.

## Approved workstation redesign — 2026-10-25

User approved home sidebar/direct setlists and folders, immediate instrument switching, adaptive music shelf with bounded widget ordering/visibility, and consistent settings/part editing panels. Keep original InkSlate annotation parity and all existing capabilities. Main work: home agent (Home), panels agent (editing/settings), reliability agent (safe following/import/export), lead (shelf/page geometry/integration). Validate all 12 UiAudit sizes, desktop and Android builds, regression suites; publish one `[build sheets]` test build and verify installers. Existing untracked training outputs are unrelated and must stay uncommitted.

**Workstation status (2026-10-26): COMPLETE.** Published and verified **1.2.2-test.298**, built from `deab893`, at https://github.com/williebob555-lab/InkSlate/releases/tag/inksheets-test . Matching APK/MSI/RPM/watch ZIP are all uploaded; GitHub tests, signing, packaging and Windows installation smoke check succeeded. Local validation: 984 passed, 78 existing gated skips, zero failures; all 12 real-PDF workspace, home, shelf and assignment/settings checks pass. Details: `review/WORKSTATION-20261026.md`. The older progress entries below refer to the October 8 sweep.

Resume here after any interruption: read the workstation status above, then the open items of the current phase.
Mark items `[x]` when done (with the commit), `[~]` when in progress, `[-]` when dropped (say why).

**Now:** (2026-10-10 evening) Scan marks rebuilt (omr/ScanMarks.kt: dots from the ink near each head, slurs grown to their notes and joined, hairpin wedges, dynamics as whole shapes) - Copprash p3 staccato 39/39 (2 false), slurs 35/38, dynamics 21/22, hairpins 13/18; held-out p4 and dropped-staccato parts checked by eye. READER 13 -> 14 (older readings still show until a part is read again; fixes are kept by file id). Retrained marknet NOT shipped (worse on Copprash; kept in scratchpad mx/). MusicStripClickTest flake explained: ScoreTools.open left open by an earlier test - the test starts from closed now. Test build pushed with these.

**Earlier:** (2026-10-09) T3/T4 hit the rate limit mid-run — resume them. PlayAlong.kt written (uncommitted, compile next). Then W/L/R/P above.

**Was:** F3 mostly done (4ab8536). T1+T2 done; T3 (practice/listen) and T4 (remote/together) testing. Fixing F1 done (11d5e33), F2 partly, next F3 reading.

Trap: Python `open(..., 'w')` on Windows writes CRLF — use `newline=''`.

## The 2026-10-08 sweep (brief)

Go through all of InkSheets and test everything — every gesture, every platform we can drive
(Windows desktop for real; Android/watch through their shared code and tests) — for issues,
inconsistencies, performance snags, UI garbage and workflow disruptions. Priority: turn the
experimental features (Listen / tempo follow, Read the music, Play together over Bluetooth, watch
flicks) from development gimmicks into practical everyday tools; graduate them out of Experimental
only when beautifully incorporated. Borrow from Slate's new Home (folders you add, Move to…,
Recently deleted 30 days with Undo, right-click/two-finger menus, select several). No limits on
change size. **No build until the very end** — commit locally, one `[build sheets]` push at the end.


## User feedback 2026-10-09 (experimental features) — the bar: "shocked by the level of improvement", not minor QOL
### W Watch flicks (done: phone sends off on Home/background/screen-off; 20 s beat, 60 s quiet; one short tick per outage, gives up after 3; weak calibrations not put to use)
- [x] Watch app closes itself when: no part is open, the device screen turns off, or the app is closed on the device
- [x] No long/aggressive buzzing when it can't connect (normal short buzzes are liked)
### L Listen (mic follow) — user has no trust (in progress: page-wide start, raw-match confidence, one page/turn, catch-up jump, 2-min silence; PageTurnBench running)
- [ ] Must handle starting mid-page / anywhere; must show what it hears and where it thinks you are; honest when lost
### R Read the music (scanning)
- [x] Small progress bar at the side while a part is being read
- [x] One button replaces Clean / Clean up / Fix / Wrong: overlays bar colours — green sure, yellow less sure, red needs fixing
- [x] Tap any bar (even green) → Fix for that bar; choose close-after-fix or keep going (bulk)
- [x] Fix panel never moves on screen (may cover the music) — capture accurate and FAST; option buttons (None of these…) never move
- [x] Fix: Undo/back; ways to say what's wrong: bar number, grace notes, clef, key, time, "not a note"… (not just None of these)
- [~] Clean view overhaul: done - strokes weighted by the print's own line weight, hairpins one wedge clear of dynamics, paper under kept shapes. (the doubled text T5 saw was its harness's scale - the app draws it right: RedrawInAppTest redraw-in-app-clean.png). OPEN: slurs blunt-ended; doubtful bars stay print (by design: a guess drawn cleanly is worse)
- [x] "Replace the whole page" = an overlay on the existing page (undoable), never a new document/duplicate files
- [x] Readings must persist — user keeps re-scanning the same parts (investigate cache keys/sync)
- [x] Band playback: option to include/exclude own part; read the band's parts in parallel
### P Playback engine
- [~] (engine landed: Interpretation.kt; next: listen-test with user, timbre work) Expressive, planned performance: whole piece analysed before playing (phrasing, breath, momentum, dynamics shaping, articulation blend), human-sounding — a demo-quality interpretation, not a note machine

## Phase 0 — setup
- [x] Pull Slate's Home rebuild (df95372) — `library-ui/` shared source now exists
- [x] docs/inksheets: PREFERENCES, PERFORMANCE, TODO, REVIEW, TESTING, ARCHITECTURE
- [x] Baseline: `:sheets-desktop:testClasses` compiles (76 s warm)

## Phase 1 — test sweep (tester agents, 2 at a time, findings → REVIEW.md)
- [x] T1 Home & library (35 findings): scan, add music, import (bulk, MobileSheets, bundle), songs/parts/setlists/folders, bookmarks, search/sort, trash, library health, instruments & profiles
- [x] T2 Reading & playing (19 findings): open, page taps/swipes/wheel/keys/trackpad/touch, strip, tools, pen while fullscreen, tabs/split, setlist stepping, part switching, zoom/pan rules, half-page, night mode
- [ ] T3 Practice tools: metronome (count-in, click), tuner, recordings (play/seek/speed/record self), practice log, notes
- [ ] T4 Remote, controllers, POD Go picture, Play together (TCP loopback), messages/presets
- [ ] T5 Experimental: Listen + ListenGauge + tempo follow; Read the music (ReadMusicPanel, ScoreTools/MusicStrip, Fix/BarCheck, clean view, MIDI/PDF export); watch flicks (FakeWatch); Bluetooth broadcasts
- [ ] T6 Settings & every panel at all 12 UiAudit sizes; keyboard-only use; touch-only use

## Phase 2 — triage & design
- [ ] Triage REVIEW.md into fix lists below (severity, area)
- [ ] Design: how each experimental feature becomes an everyday tool (write the plan here first)

### Design notes (lead, gathering)
- Settings › Experimental is three switches each with a paragraph of prose (SheetsSettings.kt:191) — against "lean chrome / explain once".
- **Listen's taught turns are stored per recording (`AudioTrack.turnsMs`), not per part.** Bass part (3 pp) and trombone part (2 pp) of one song share one list → teaching one corrupts the other. Must key by part file (or by bar, once read).
- Recording playback never turns pages by itself even when turns are known — the obvious everyday win ("play along": the app knows exactly where its own recording is, no microphone needed). Mic-following is only for live playing.
- Candidate everyday uses: Read the music → "Go to bar 47" (director calls a bar at rehearsal), rehearsal letters, hear a passage, print for my instrument (bass reading tuba part); Bluetooth → automatic fallback when Wi-Fi blocks devices (no switch); watch → per-instrument, set up from the watch's own card.

## Phase 3 — fixes
(filled from triage; IDs refer to review/Tn.md)

### F1 Library data safety (lead)
- [x] T1-01 case-only rename loses song · T1-02 re-copied file with old date never re-added
- [x] T1-03 song editor Save writes stale parts snapshot (diff instead)
- [x] T1-05 restore with path clash deletes only copy · T1-09 trash manifest first
- [x] T1-06 Replace a part → old file to Trash first
- [x] T1-07 bundle re-import overwrites marked files + duplicate setlist; same-named parts in zip collide · T1-08 bulk import same name drops new files silently
- [x] T1-15 titles ending in an instrument word · T1-16 NFC/NFD · T1-17 hand-set title replaced
- [x] T1-18 0-byte/junk files added at once; "tried" never retried · T1-22 scans not serialised; prefs rewritten per part
- [-] T1-04 setlist/bookmark/audio lists LWW as one field → concurrent adds lost (deferred: needs op base stamps in the log format; only same-record edits on two devices while apart)
- [ ] T1-20 one new file = full sort (0.7 s @300 songs); purge every scan

### F2 Library UX (Slate-inspired)
- [x] Undo snackbar on every remove/delete (song, part, setlist, folder, set entry); Recently deleted reachable from Home, covering setlists/folders (T1-24/25/26)
- [x] One search (Songs tab; `matches()` everywhere, accents/apostrophes folded, parts/instruments/notes) (T1-10)
- [ ] "Needs attention" line replacing No tempo/No instrument/File missing chips + held-back removal (T1-11, T1-23); sort chips honest
- [x] Home top bar labelled; Add music findable (T1-12); right-click/two-finger menus on songs; Enter confirms name dialogs
- [ ] Select several (add to setlist / merge / remove); Move to… for setlists/folders; drag song onto setlist
- [x] Recording-only song (T1-13); dropped zip + files queued (T1-14); MS import cancel (T1-32, idempotence open); share zip out of synced folder (T1-33; 'only my parts' open)
- [x] Phone layouts: import review, incoming chips, add instrument (T1-27/28); walls of text in Experimental (T1-29 part); bookmark rows (T1-30); default setlist name + Enter (T1-31)
- [ ] Recently added strip after arrivals; check-now from Home (T1-21)

### F3 Reading & playing (T2) — editor code is shared with InkSlate: gate on musicView
- [x] T2-01 taps during the turn animation mis-aimed (translationX shifts pointer coords)
- [x] T2-05 half-page on a fitted page pushes it up / second press skips the song
- [x] T2-06 Space/Enter as next page; a second Escape must not close the song
- [x] T2-07 strip Eraser off leaves the stylus erasing
- [~] T2-08 (SheetDialog done 4ab8536; FloatingPanel swipe-down open) FloatingPanel/SheetDialog: X top-right + swipe-down close everywhere
- [x] T2-09 Part menu: All instruments · T2-11 set tabs' right-click Close · T2-17 rapid opposite steps
- [x] T2-10 page colour (Night/Sepia…) remembered and on the strip's More
- [~] (T2-13/14/19 done 4ab8536; T2-12 open) T2-12 duplicate Undo/Pen with All tools · T2-13 phone strip-left overlays the page · T2-14 strip folded on first open · T2-19 Highlighter on default strip
- [x] T2-15 song change near-black fade (~220 ms) → crossfade/short
- [ ] T2-02/03/04 desktop vs Android tap/flick/pan rules aligned
- [ ] T2-16 end of set says so

## Phase 4 — re-test
- [ ] Re-run testers on changed areas (round 2)
- [ ] Full UiAudit (12 sizes), SheetsTouchTest, existing suites green

## Phase 5 — ship
- [ ] Update README (user-facing) for graduated features
- [ ] One `[build sheets]` push; confirm the release page has the new assets
- [ ] Summary for the user: before/after
