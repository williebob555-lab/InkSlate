# InkSheets — TODO (the workstation)

Resume here after any interruption: read the **Now** line, then the open items of the current phase.
Mark items `[x]` when done (with the commit), `[~]` when in progress, `[-]` when dropped (say why).

**Now:** (2026-10-09 evening) ONE heavy job at a time (user's laptop overloaded by 4 parallel Gradle runs): agents run `--max-workers=2`, targeted `--tests` only, never alongside each other. Running: playback-engine agent (pops/hiss, legato, ties, line breaks, accents, phrasing, xruns, Copprasch articulations/dynamics). Queued in order: (1) page-render starvation (worktree agent-ab3a18340232f1520 has a partial RenderGate fix + ScreenFirstTest, uncommitted), (2) Copprasch reader marks diagnosis (agent-a0faf07fb4100dc90 has MarksDiagnostic.kt), (3) DecentSampler/SFZ sampler support, (4) Listen locking. Lead (not agents) places UI; taste calls go to the user as options. User picked: reading tile "Reading 42%" + Stop; short screens keep names in small print, More only last resort. Uncompiled in main: those two + strips never scroll.

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
