# InkSheets — the user's preferences and corrections

Read this before changing anything. Each rule is something the user asked for or corrected,
with the reason, so a borderline case can be judged. Newest process rules first.

## How work is done

- **Docs first.** Read the docs in this folder that cover the area before changing it; update them
  after. `TODO.md` is the workstation: plan there, tick off there, resume from there after an
  interruption (credit run-outs happen; the next session must be able to pick up cold).
- **Agents:** parallel agents are fine with an appropriate, cheaper model and moderate use (2–3 at
  once; this laptop has 16 GB and ~2 Gradle jobs at a time is the ceiling). Grunt work (sweeps,
  audits, mechanical fixes) goes to cheaper models; design decisions stay with the lead.
- **Testing** is done by a dedicated tester agent per area, in rounds: (1) use it as intended,
  (2) odd and interesting combinations, (3) use it not as intended, (4) try to break it. Findings
  go to `REVIEW.md`. Push the tester deeper when it is shallow; stop it when it wastes time.
- **Builds:** for the 2026-10-08 sweep, *no builds until the very end* (one clean before/after).
  Commit locally; push one `[build sheets]` commit at the end and confirm it published.
  Normally: every finished set of changes ships as one test build (`[build sheets]` on the last
  commit only); InkSheets stable releases are `inksheets-v*` tags.
- **Keep going.** When given the process, stop only when every task is done — never end a turn on a
  "still to do" list.
- **Diagnose with tests, not guesses.** For a field bug: make the code say why (never swallow
  exceptions), reproduce in the real order of events with a logging test, emulate platform rules the
  JVM lacks (Android main-thread network ban, Windows file locks). Then fix.
- **Measure, then say the number.** A speed fix ships with a before/after measurement.
- **Tests never touch the user's real app data** (`%LOCALAPPDATA%` is sandboxed to
  `build/test-appdata`). The real library at `C:/Users/willi/Music/Sheet Music/InkSheets` is only
  ever *read* (copy files out to a temp library to act on them).
- **Never poll api.github.com in loops** (shares the 60/h limit with the user's in-app updater);
  one watcher at most, prefer the releases HTML page.
- **Freedom:** 2026-10-08 the user said "surprise me — no limits" on the size of changes; they can
  ask for a reversal. Experimental features may *graduate* out of Settings › Experimental only when
  beautifully incorporated into the normal flow.

## Who uses it and how

- The user plays **trombone, baritone/euphonium and electric bass** — pep band, wind ensemble,
  rehearsals and gigs. Hands are busy: the phone remote, a foot (POD Go / pedals) and a watch on the
  **right** wrist are how the app is driven on stage.
- Their music is **mostly scans**; the library came from MobileSheets (one MS song per part), synced
  between laptop (Windows, later Fedora KDE, same pen/touch laptop), Galaxy Tab S9 FE, Pixel phone.
- A director may run Play together for a **50-person band of non-technical people**: it must be
  idiot-proof; the leader sees a count, never a flood of join pop-ups (joins go to the log).

## Reading and playing (the core feel — compared against MobileSheets: "smooth and precise")

- **Music is as big as possible and centred.** Open fullscreen, whole page fit. No annotation bar
  until summoned. Nothing is ever drawn over the music by the UI (indications go beside it).
- **Annotating mid-rehearsal is instant.** The pen writes immediately in fullscreen; pen /
  highlighter / eraser / undo are one tap away on the strip; no mode switch. A locked
  performance mode may exist only as an opt-in.
- **Page taps:** left third back, right third forward, middle third opens the strip (page not
  moved); middle again closes and refits. Bottom-middle opens All tools. A tiny wiggle never eats a
  tap. Swipe drags the page with the finger; one trackpad swipe = exactly one page.
- **With tools away, a sideways finger only turns pages** — never pans, at any zoom. Opening tools
  keeps pan/zoom; closing them refits.
- Strip Pen/Highlighter/Eraser are switches (on = finger/mouse gets the tool; off = turning pages).
  The stylus keeps its last tool and never turns pages unless chosen in All tools.
- The **strip lives down a side** (left/right option), folds to a bottom-corner button; buttons
  carry **short names** (icons alone were "ambiguous").
- **Next song in a set is preloaded**; song changes have no blank flash and no freeze; turning to a
  song starts at its first page; the page centre stays put across pages and songs.
- **No pop-ups while playing** ("synced changes", merges) — log them instead.
- A recording never stops on its own (leaving the page/song/Home keeps it playing); only closing
  that song's tab stops it; a stop button is always reachable.
- Tabs: X only on songs opened from the list; set tabs close with the set; Home closes all tabs;
  new-tab button beside Home; "Save as setlist" from open tabs.
- Windows: native file pickers; never Java exclusive fullscreen (borderless window instead);
  Windows touch must work in tablet mode.

## Library and file management

- **The synced folder is the library.** Scanned on start/resume/change; adds, merges and deletes
  happen by themselves. **No ghosts, no duplicates**, across devices on mixed versions. Ids are
  deterministic (song from title key, part from file) so devices agree independently. Delete only
  what this device saw then watched vanish; never on an empty/unmounted scan; ignore Syncthing temp
  and conflict files. Removed songs go to a synced Trash (30 days).
- **One automatic sort leaves zero strays**; a second sort changes nothing. Check on the real
  library with `RealSortTest`.
- **Parts visible without menus** — "submenus of submenus" felt detached. Song rows list parts
  inline; the strip's Part button is named for the part showing.
- Instruments: user-extendable list + aliases ("DL" = Drum Line), chairs (Trumpet 2), sister
  instruments share parts, nearest instrument fallback (bass guitar → tuba part). Instrument
  choice is per device. A one-off instrument is not added to the user's list.
- Declined: automatic tidy-folder renaming.
- Dialogs fit a phone; every list longer than ten has a search; drag-to-move needs a 0.25 s hold,
  no drag bars, the lifted row stays under the finger.

## UI rules

- **Lean chrome.** No permanent explanatory paragraphs; explain once (snackbar/first use), then the
  control's state is the reminder. No duplicate control sets; one way in per gesture.
- **Panels:** X top-right on every panel plus swipe-down on the header to close (`PanelTop`). When
  adding a panel, ask what else claims that edge; dock to the side where there is room.
- Window buttons toggle their window and are lit while it is open.
- **Every UI change passes the UiAudit at all 12 sizes** (phones, tablets, laptops, both ways up)
  before it ships. Panels fit or scroll. Landscape phone and laptop-short are where crops appear.
- The user asks for proactive UI improvement ideas.

## Automation (Listen, tempo follow, music reading, watch flicks)

- **Visible or untrusted.** Every automation shows (1) that it runs and what it hears, (2) where
  it thinks you are and how sure, (3) what it will do next and when, (4) why it stopped. Silent
  failure looks like "waiting" and kills trust. Prove it acts in the real app, not just unit tests.
- **A false action is worse than a missed one** (false page turn on stage, false tempo/dynamic).
- "If it's unreliable then it's not worth using." Music reading quality bar: professional
  engraving; clean view is a 1:1 redraw (nothing cut, lost or left over; beams and slant as
  printed) — "looks like Scratch" was the complaint.
- No aimless trial and error: hypothesis + expected effect, measured on held-out data and real
  scans (ReadingBenchmark, RealScanTruthTest, wind set, PageTurnBench).
- Reader must run on every device (phone ≤ 10 s/page); faster devices may go faster but produce the
  byte-identical result. No new ML runtime dependency without asking.

## Remote, controllers, watch

- The remote is the gig highlight. Every app control has a remote kind; buttons fully customisable
  (grid, label, colour, macros). New controls get a remote kind and work from a controller
  (`RemoteControl.performHere`).
- POD Go: talks USB, not MIDI. Never hard-map captured messages to switches; the user binds
  controls on the POD Go picture. A footswitch with nothing assigned on the unit sends nothing.
- Watch: single flick (not a twist), fast turns allowed, cued calibration per instrument; path is
  watch → phone → (remote) → tablet. Ships as a zip, never a bare apk.
- Remote/companion links must self-report (✓ / "not received") — the user is on eduroam, which
  blocks device-to-device traffic; Bluetooth is the fallback.
