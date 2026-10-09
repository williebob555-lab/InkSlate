# InkSheets — how to test it (and the tester agents' brief)

## Driving the real app from tests (Windows desktop)

The desktop tests run **the app exactly as it ships**: `installInkSheets { platform -> … }`
(`sheets-desktop/src/main/kotlin/com/inksheets/desktop/Main.kt`) installs InkSheets into InkSlate's
`AppRoot`, then `runDesktopComposeUiTest(width, height) { setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } } }`.
Point the library at a temp folder with `it.setPref("sheets_library", dir)`.

- Model the setup on `SheetsTouchTest`, `SheetsScreensTest`, `UiAuditAppTest`, `RemoteScreensTest`,
  `WatchFlicksTest` (FakeWatch), `ListenTest`, `ReadMusicTest`, `ScoreToolsShots`.
- Clock: `mainClock.autoAdvance = false`, then advance in steps (`settle()` helper in SheetsTouchTest).
- Input: `performMouseInput`, `performTouchInput`, `performKeyInput`, semantics actions; for Windows
  touch the way Windows delivers it, `com.inkslate.desktop.SimulatedTouch` (see SheetsTouchTest).
- Pictures: `onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()` → PNG. **Look at them**
  (the Read tool shows images). A test that only asserts can miss what a player would see.
- What the app thinks: `sheets()` (the `SheetsState`), e.g. `pageShown`, open tabs, library.
- `UiAudit.check(test, size, scene, w, h)` finds overlaps and controls cut by the edge at the 12
  `UiAudit.SIZES`; run audits with `-Dinksheets.uiaudit=1`.
- Every `-Dinksheets.*` passed to Gradle reaches the tests.

Commands (Git Bash; JDK must be set):
```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :sheets-desktop:test --tests '*YourTest*' -Dinksheets.something=1
./gradlew :sheets-core:test
```
`:sheets-desktop:testClasses` alone takes ~75 s warm. Test heap is 5 GB: **never run more than
2 Gradle test jobs at once on this laptop.**

## Data rules

- Real library (read only!): `C:/Users/willi/Music/Sheet Music/InkSheets` (~270 songs, ~1300
  parts, mostly scans; `MobileSheets/` and `Imported/PEP BAND/…` subfolders). Copy what you need into
  a folder under `build/` and point the app there. Never write into the real library.
- App data is sandboxed for tests (`build/test-appdata`); never run the installed app's paths.
- Microphone, Bluetooth, USB, the watch and a second device are not available to tests: use the
  fakes and loopback (FakeWatch, loopback sockets, recordings fed as audio, simulated MIDI) and say
  in findings what could only be checked through simulation.

## Platforms

Windows desktop is the real target we can drive. Android (phone/tablet) and the watch share most
code (`sheets-ui` is compiled into both); test their layouts at the phone/tablet UiAudit sizes and
their touch rules with SimulatedTouch, and read the Android-only code
(`app/src/inksheets/java/com/inksheets/android/`) for platform-specific mistakes (main-thread
network, lifecycle/recreation, permissions, back button). Linux/Fedora: read for paths/packaging only.

## Tester agent brief

You are a demanding tester of one area of InkSheets, a sheet-music reader for a musician who plays
trombone, euphonium and bass in pep band and wind ensemble, often on stage with hands busy. Read
`docs/inksheets/PREFERENCES.md` first — the user's rules are the spec — then the relevant part of
`ARCHITECTURE.md`. Work in rounds, and write each round's findings as you go:

1. **As intended.** Do what a player does with this area, start to finish, the obvious way.
2. **Odd combinations.** Mix it with other features: during a setlist, while a recording plays,
   while leading Play together, at phone size, with the strip on the left, after an import,
   with an empty library, with 300 songs.
3. **Not as intended.** Wrong order, double taps, cancel halfway, rapid repeats, very long names,
   unicode, missing/renamed/deleted files underneath, two devices editing the same thing.
4. **Break it.** Corrupt inputs, races, huge inputs, resizing mid-action, killing mid-write.

For each round, drive the real app (tests as above), **look at screenshots**, and check for:
bugs, crashes, data loss; inconsistent behaviour between similar places; anything that breaks a
PREFERENCES rule; slowness (time it); UI garbage (overlaps, crops, jargon, walls of text,
duplicate controls, dead ends, a panel without X/swipe-down); workflow friction (too many taps,
hidden features, confusing names). Also note **what would make it genuinely useful** — the user wants
experimental ideas turned into everyday tools.

Output: write findings to `C:\PDF Draw App\docs\inksheets\review\<your-id>.md` (absolute path —
the main checkout, even if you work in a worktree) in the format at the top of `REVIEW.md`.
Prefix IDs with your id (T1-01…). Keep the test files you wrote (they become regression tests);
list them at the end. **Do not change app code** — report; the lead fixes. Don't commit.
Spend effort where the bugs are: if an area is solid after a real try, say so in one line and move on.
