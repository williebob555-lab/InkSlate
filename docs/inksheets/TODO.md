# InkSheets — TODO (the workstation)

Resume here after any interruption: read the **Now** line, then the open items of the current phase.
Mark items `[x]` when done (with the commit), `[~]` when in progress, `[-]` when dropped (say why).

**Now:** Phase 1 — docs written; launching testers.

## The 2026-10-08 sweep (brief)

Go through all of InkSheets and test everything — every gesture, every platform we can drive
(Windows desktop for real; Android/watch through their shared code and tests) — for issues,
inconsistencies, performance snags, UI garbage and workflow disruptions. Priority: turn the
experimental features (Listen / tempo follow, Read the music, Play together over Bluetooth, watch
flicks) from development gimmicks into practical everyday tools; graduate them out of Experimental
only when beautifully incorporated. Borrow from Slate's new Home (folders you add, Move to…,
Recently deleted 30 days with Undo, right-click/two-finger menus, select several). No limits on
change size. **No build until the very end** — commit locally, one `[build sheets]` push at the end.

## Phase 0 — setup
- [x] Pull Slate's Home rebuild (df95372) — `library-ui/` shared source now exists
- [x] docs/inksheets: PREFERENCES, PERFORMANCE, TODO, REVIEW, TESTING, ARCHITECTURE
- [ ] Baseline: `:sheets-desktop:testClasses` compiles; note time

## Phase 1 — test sweep (tester agents, 2 at a time, findings → REVIEW.md)
- [ ] T1 Home & library: scan, add music, import (bulk, MobileSheets, bundle), songs/parts/setlists/folders, bookmarks, search/sort, trash, library health, instruments & profiles
- [ ] T2 Reading & playing: open, page taps/swipes/wheel/keys/trackpad/touch, strip, tools, pen while fullscreen, tabs/split, setlist stepping, part switching, zoom/pan rules, half-page, night mode
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
- [ ] T1-01 case-only rename loses song · T1-02 re-copied file with old date never re-added
- [ ] T1-03 song editor Save writes stale parts snapshot (diff instead)
- [ ] T1-05 restore with path clash deletes only copy · T1-09 trash manifest first
- [ ] T1-06 Replace a part → old file to Trash first
- [ ] T1-07 bundle re-import overwrites marked files + duplicate setlist; same-named parts in zip collide · T1-08 bulk import same name drops new files silently
- [ ] T1-15 titles ending in an instrument word · T1-16 NFC/NFD · T1-17 hand-set title replaced
- [ ] T1-18 0-byte/junk files added at once; "tried" never retried · T1-22 scans not serialised; prefs rewritten per part
- [ ] T1-04 setlist/bookmark/audio lists LWW as one field → concurrent adds lost
- [ ] T1-20 one new file = full sort (0.7 s @300 songs); purge every scan

### F2 Library UX (Slate-inspired)
- [ ] Undo snackbar on every remove/delete (song, part, setlist, folder, set entry); Recently deleted reachable from Home, covering setlists/folders (T1-24/25/26)
- [ ] One search (`matches()` everywhere, accents/apostrophes folded, parts/instruments/notes) (T1-10)
- [ ] "Needs attention" line replacing No tempo/No instrument/File missing chips + held-back removal (T1-11, T1-23); sort chips honest
- [ ] Home top bar labelled; Add music findable (T1-12); right-click/two-finger menus; Enter confirms dialogs
- [ ] Select several (add to setlist / merge / remove); Move to… for setlists/folders; drag song onto setlist
- [ ] Recording-only song (T1-13); dropped zip + files (T1-14); MS import cancel + idempotent (T1-32); share zip to temp + "only my parts" (T1-33)
- [ ] Phone layouts: import review, incoming chips, add instrument (T1-27/28); walls of text (T1-29); bookmark rows (T1-30); empty states/default setlist name (T1-31)
- [ ] Recently added strip after arrivals; check-now from Home (T1-21)

## Phase 4 — re-test
- [ ] Re-run testers on changed areas (round 2)
- [ ] Full UiAudit (12 sizes), SheetsTouchTest, existing suites green

## Phase 5 — ship
- [ ] Update README (user-facing) for graduated features
- [ ] One `[build sheets]` push; confirm the release page has the new assets
- [ ] Summary for the user: before/after
