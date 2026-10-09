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

## Phase 3 — fixes
(filled from triage)

## Phase 4 — re-test
- [ ] Re-run testers on changed areas (round 2)
- [ ] Full UiAudit (12 sizes), SheetsTouchTest, existing suites green

## Phase 5 — ship
- [ ] Update README (user-facing) for graduated features
- [ ] One `[build sheets]` push; confirm the release page has the new assets
- [ ] Summary for the user: before/after
