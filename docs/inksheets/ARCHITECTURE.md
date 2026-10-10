# InkSheets — architecture

Conventions: paths are relative to `C:\PDF Draw App`. `file:line` refs were checked against the current tree. Not every pref, file and class was read end to end (see "Not verified" at the end).

---
## 1. Module and source-set layout

InkSheets is InkSlate with a music library as its Home screen and a strip of action buttons over the open song. Nothing in the editor is forked. The InkSlate editor is customised through the `AppFlavor` hooks and the `Perform` singleton.

```
settings.gradle.kts   includes :core :app :desktop :sheets-core :sheets-desktop :watch
core/            com.inkslate.core   pure-Kotlin editor model. Perform.kt: PerformAction enum + Perform object.
desktop/         com.inkslate.desktop  Compose Desktop editor: AppFlavor, AppRoot, Workspace, EditorScreen,
                 DocumentCanvas, KeyBindings, SimulatedTouch, PenInput, AppDirs, DesktopPrefs
app/             Android editor: com.inkslate.AppFlavor, ink/DrawingView, ui/AppRoot, data/PedalKeys.
                 Flavors "inkslate" and "inksheets" (flavorDimensions "app"; applicationId com.inkslate / com.inksheets)
sheets-core/     com.inksheets.core (+ omr/, podgo/, watch/)  pure Kotlin, no Compose. Library model, sync log,
                 imports, companion/remote protocols, metronome/tuner DSP, OMR "read the music".
sheets-ui/       com.inksheets.ui   SHARED SOURCE (not a Gradle module). Every InkSheets screen and SheetsState.
library-ui/      com.inkslate.library  SHARED SOURCE. Slate's Home (LibraryHome, folder picker, trash, organise).
                 Used by :desktop and ALL :app flavors, not by InkSheets Home.
sheets-desktop/  com.inksheets.desktop  Desktop app. Depends on :desktop, :sheets-core, :core. Tests live here.
app/src/inksheets/java/com/inksheets/android/   Android platform pieces
app/src/inksheets/java/com/inkslate/FlavorSetup.kt   the flavor entry point (the `inkslate` flavor has its own stub)
watch/           Wear OS module (com.inksheets.watch): FlickService, PhoneMessages, WatchActivity
```

Sizes: sheets-core is about 12k lines. `omr/Recognizer.kt` alone is 4125 lines. sheets-ui is about 17k lines. The biggest files are `SheetsState.kt` (1335), `RemotePanel.kt` (1813), `ScoreTools.kt` (1121) and `CompanionPanel.kt` (985).

### How shared source works (no KMP)
- `sheets-ui/README.md` and `library-ui/README.md` say the same thing. Both Compose flavours expose the same `androidx.compose.*` API (Jetpack Compose on Android, Compose Multiplatform on desktop), so one copy of the screens compiles into both. Code must stick to foundation, material3 and runtime. Anything platform-specific goes through `SheetsPlatform` (or `LibraryBackend` for library-ui).
- Desktop: `sheets-desktop/build.gradle.kts` has `kotlin { sourceSets.main { kotlin.srcDir(rootProject.file("sheets-ui/src/main/kotlin")) } }`. `desktop/build.gradle.kts:19` adds `library-ui/src/main/kotlin` the same way.
- Android: `app/build.gradle.kts:62-69`. The `main` source set adds `library-ui/src/main/kotlin` (`kotlin.directories.add`). The `inksheets` source set adds `sheets-ui/src/main/kotlin`. `inksheetsImplementation(project(":sheets-core"))`, plus ML Kit text recognition, the ML Kit document scanner and play-services-wearable are inksheets-only (`app/build.gradle.kts:129-142`).
- Desktop packaging (`sheets-desktop/build.gradle.kts`):
  - mainClass `com.inksheets.desktop.MainKt`.
  - JVM args `-Dinkslate.appName=InkSheets` and `-Dinkslate.version=...`. `AppFlavor.name` reads `inkslate.appName`, which also names the settings folder (`%LOCALAPPDATA%\InkSheets`).
  - MSI, EXE and RPM formats, with its own `upgradeUuid` so it installs beside InkSlate.
  - jlink-trimmed runtime modules: `java.desktop, java.sql, java.logging, jdk.unsupported`.
  - Third-party libraries: pdfbox-jvm, sqlite-jdbc (MobileSheets db), mp3spi, vorbisspi, a local jaad AAC jar, JNA (Winsock/BlueZ Bluetooth), usb4java (POD Go).
  - `runSandbox` task: runs the app with its own `build/sandbox-run` home. Window title gets "(sandbox)" via `-Dinksheets.sandbox=1`.
- CI: `.github/workflows/build.yml` runs `:core:test :sheets-core:test :app:testInkslateDebugUnitTest`, `:desktop:test :sheets-desktop:test`, and a Linux `xvfb-run` pass. `release.yml` publishes on tags `inksheets-v*`: APK `app-inksheets-release.apk` and MSI.

---
## 2. Startup and the AppFlavor hooks

### The two AppFlavor objects
There are two parallel objects with the same shape. Desktop has `desktop/.../AppFlavor.kt`, Android has `app/src/main/java/com/inkslate/AppFlavor.kt`. Each is a bag of `var` hooks that InkSheets sets before the window or Activity opens. Android adds `onIncomingFiles` (share intents/URIs) and `onLink` (`inksheets://` links). Desktop adds `onFilesDropped`, `quit`/`minimise`, `windowed`, `moveToScreen`, `screens()`.

| Hook | Effect |
|---|---|
| `home(open, openSettings)` | Replaces Slate's Home tab with `SheetsHome`. Consumed at `desktop/AppRoot.kt:452`; Android at `app/.../ui/AppRoot.kt:502`. |
| `paneOverlay` (BoxScope) | `ActionStrip(state)` is drawn over the focused pane (`desktop/Workspace.kt:195`). |
| `fingerPans` | Finger pans or turns instead of drawing. The AppRoot default tool setup for touch is at `desktop/AppRoot.kt:327/352/363`. |
| `musicView` | A document opens as a music page: bars hidden, SINGLE layout, whole page fitted and centred, page-turn animation, song fade (`songShown`). Used across `EditorScreen.kt` (e.g. 180, 271, 1124, 1946, 1961-1999), `ToolBar`, `ShapeTray`, `Workspace.kt:447`. |
| `alwaysFullscreen` | Undecorated full-screen window even on Home (a "music stand"). `windowed`/`chooseWindowed` can override it. |
| `openSet(parts, focus)` / `closeSet` / `focusFile` / `swapTab` | Set by `AppRoot.kt:313-316`. Open a setlist as ordered tabs named by song, bring a file's tab to the front, swap one part for another in place. |
| `onHome` | Called when Home is pressed in the tab row. InkSheets maps it to `state.backToSetlist()`. |
| `onHomeShown(Boolean)` | Sets `state.homeInFront`. |
| `settingsSection` | Composable inserted at the top of Slate's Settings (`SheetsSettings`). |
| `onTabsMoved` / `onOpenTabs` / `onSaveTabs` | Keep the tab order in sync with the playing set, and "Save open tabs as setlist". |
| `pagesActions` | Extra actions in the Pages panel (`MusicPageActions`: make a part or new song from picked pages). Wired at `EditorScreen.kt:2405`. |
| `onFilesDropped` | Dropped files go to `state.offer(files)`. |
| `stripOnLeft`, `stripLaneDp`, `musicLaneDp`, `turnAnimation`, `edgeTaps`, `songShown` | `@Volatile` layout and behaviour knobs that `SheetsPlatform.setStripSide/setStripLane/setMusicLane/setTurnStyle/setEdgeTaps` write. The editor reads them. |

### Desktop startup
- `sheets-desktop/.../Main.kt:14` is `fun main() = runAs("InkSheets") { installInkSheets() }`. `runAs` is InkSlate's `desktop/Main.kt`, which creates the window.
- `installInkSheets(prepare)` (`Main.kt:21`) is the single place that wires the app. **Tests call it too, so tests run the shipping wiring.** It does this:
  1. Sets `AppFlavor.fingerPans/musicView/alwaysFullscreen = true`.
  2. Builds `val state by lazy { SheetsState(DesktopSheetsPlatform { f -> openFile?.invoke(f) }.also(prepare), openLater = true) }`. `openLater` reads the library on a background thread.
  3. Sets `home`, `paneOverlay`, `onHome`, `settingsSection`, `onTabsMoved`, `onOpenTabs`, `onHomeShown`, `onSaveTabs`, `onFilesDropped` and `pagesActions`.
  4. On Windows, `WindowsFileDialog` becomes `NativePickers.file/folder`.
  5. Returns `() -> SheetsState` for tests.
- The `open` callback given to `home()` is captured in `openFile`. `DesktopSheetsPlatform.openPart` calls it to open a part as a tab.
- `desktop/Main.kt:157` (`onKeyEvent`) is the window key handler: see section 7.

### Android startup
- `app/.../InkSlateApp.kt:24` calls `FlavorSetup.install(this)`. For the `inksheets` flavor this is `app/src/inksheets/java/com/inkslate/FlavorSetup.kt:61`.
- It sets the same hooks as desktop, plus Android-specific ones:
  - `DrawingView.fitWholePage = true`, `stripLaneDp = 64f`, `swipeTurns = true`.
  - `onIncomingFiles` copies Uris in on a "take-in" thread (`AndroidSheetsPlatform.copyIn`) and calls `state.offer`.
  - `onLink` handles `inksheets://remote?...` (`RemoteLink.parsePair` → `state.remote.connect`) and `inksheets://join?...` (`CompanionLink.parseJoin` → `companion.followLeader`). A link that arrives before the UI is up is parked in `pendingLink`.
- Android has one `SheetsState` per process (`stateFor(context)`). It was once one per Context, which caused double companion connections (see the comment at `FlavorSetup.kt:21-27`). It rebinds `platform.context` to the current Activity.
- The `inkslate` flavor's FlavorSetup is a stub with no InkSheets hooks.

### What SheetsState does at construction (`SheetsState.kt:18`, `init` around 340-380)
It pushes saved prefs to the platform (`setEdgeTaps`, `setTurnStyle`, `setStripSide`), then calls `controllers.start()`. It opens the last library (`K_LIBRARY`, directly or in the background). Then it **installs the `Perform` hooks** (all `@Volatile` globals in `core/Perform.kt`):
- `Perform.app` (`SheetsState.kt:346`) handles NEXT_SONG/PREVIOUS_SONG (`step(±1)`), METRONOME, TUNER, PLAY_AUDIO (`Recording.toggle`), RECORDINGS, PLAY_TOGETHER, SWITCH_PART and BOOKMARK.
- `Perform.centreTap` → `centreTap(bottom)`.
- `Perform.onPosition` → `pageShown`.
- `Perform.onPage` → Listener, `Transcriber.resume`, `currentPath`, `current` song, `followInSet`, `companion.pageTurned`.
- `Perform.importedInk` → MobileSheets markings.
- `ScoreTools.install(this)` → `Perform.pageMarks`/`musicGesture`/`musicTool`.

### Perform routing (`core/.../Perform.kt`)
`PerformAction` is an enum with 21 values: NEXT_PAGE, PREVIOUS_PAGE, HALF_PAGE_FORWARD, HALF_PAGE_BACK, FIRST_PAGE, LAST_PAGE, NEXT_SONG, PREVIOUS_SONG, METRONOME, TUNER, PLAY_AUDIO, RECORDINGS, PLAY_TOGETHER, SWITCH_PART, BOOKMARK, PEN, HIGHLIGHTER, ERASER, UNDO, REDO, FULLSCREEN. `Perform.run(action)` routes in this order:
1. `forWorkspace` (PEN, HIGHLIGHTER, ERASER, FULLSCREEN) → `Perform.workspace`.
2. `!forDocument` → `Perform.app` (set by SheetsState).
3. Document actions → `Perform.document` (the focused editor). If that returns false, NEXT_PAGE/HALF_PAGE_FORWARD fall through to `app(NEXT_SONG)`, and PREVIOUS_PAGE/HALF_PAGE_BACK to `app(PREVIOUS_SONG)`. So a pedal at the last page of a piece goes to the next song.

Other `Perform` slots: `jumpTo`, `onPage`, `onPosition`, `openPages`, `showHome`, `recentre`, `centreTap`, `inkOf/mergeInk/clearInk/importedInk` (ink access for companion ink sharing), `viewBy` (remote touchpad), `pageMarks`/`onMarksChanged`/`marksChanged` (music-tool overlay), `musicTool` + `musicGesture` (a music tool takes presses). `requestPage(path, page0)` / `takePage` request a page jump for a file. Constants: `CENTRE_TAP_BOTTOM = 0.25f` (`Perform.kt:187`), `TAP_SIDE_SHARE = 1/3` (`:190`).

---
## 3. State and platform

### SheetsState (`sheets-ui/.../SheetsState.kt`, one per app)
Observable fields (Compose `mutableStateOf`):
- Library and device state:
  - `root: File?`, `library: Library?`, `opening`
  - `version` (bumped on every change; screens read it to redraw)
  - `profiles`, `profileId`, `profile`
  - `oneOff` (an instrument picked for now only, via `chooseOneOff`)
- Playback and navigation:
  - `current: Song?` (the last song opened), `currentPath`, `pageShown: Pair<page, count>`
  - `playing: Pair<setlistId, index>?` (the running setlist), `homeInFront`
  - `homeTab` (0 Songs, 1 Setlists, 2 Bookmarks), `setlistFolder`, `setlistShown`
- Strip and layout:
  - `strip: List<PerformAction>`, `stripCollapsed`, `stripOnLeft`, `stripLabels`, `edgeTaps`, `turnStyle`
- Window flags: `tunerOpen`, `audioOpen`, `metronomeOpen`, `companionOpen`, `partPicker`, `readMusicOpen`, `notesFor`, `writingReminder`, `reminderShown`, `clearingMarks`, `pickingOneOff`, `savingTabs`.
- Sorts and settings: `bookmarkSort`, `setlistSort`, `entrySort`, `listenTurns`, `readMusic`, `presets` (leader's one-tap messages).
- Scan feedback: `lastScan`, `scanHistory`, `reassigning`, `reassigned`.

Sub-controllers owned by the state:
- `companion = Companion(this)` (`CompanionPanel.kt:35`, leader/follower/mesh)
- `remote = RemoteControl(this)` (`RemotePanel.kt:54`)
- `controllers = ControllerHub(this)` (`ControllerHub.kt`)
- `watch = WatchFlicks(this)`

Key function groups:
- Library lifecycle:
  - `open(folder)` / `openInBackground` / `adopt` (~690-725)
  - `startWatching` (`:727`): a daemon thread that calls `library.refresh()` every 2 s and `scanFolder()` every 5th tick (about 10 s). New files then trigger `readUnknownParts()` (OCR/text instrument read) and `readTempos()`.
  - `scanFolder(allowMassRemoval)` (`:897`), `scanner()` (`:885`)
  - `change { Library.() -> Unit }` (`:1110`): the one mutation entry point (runs the edit, bumps `version`).
  - `refresh()` (`:865`)
- Setlist and song flow:
  - `playSetlist(id, i)` (`:389`) builds ordered tab files through `partFor`/`partFile` and calls `platform.openSet`.
  - `step(by)` (`:581`) goes to the next or previous entry of `playing`. `backToSetlist`, `closeSetlist`, `stopPlaying`, `noteOpened`
  - `openTabs`/`tabsMoved` (`:499/:511`) reorder the set after a drag. `reorderSetlist`, `setlistFromFiles`
  - `partFor`/`partShown`/`pickPart`/`switchThisSong`/`switchAllSongs`/`clearThisSong` (`:781-830`) choose the part. `showAgain` + `followInSet` re-open on a part change.
- Per-song actions: `toggleBookmark`/`openBookmark`/`removeBookmark`/`setBookmarkColour`/`orderBookmarks`, `setNotes`, `setSongColor`/`setSetlistColor`/`setFolderColor`/`setEntryColor`.
- Library edits: `mergeSongs`, `movePart`, `splitPart`, `partFromPages`, `showPages`, `removeSong`/`removePart`/`restore`, `trash()`, `missingParts`/`removeMissing`/`relinkMoved`.
- Intake: `offer(files)` (`:1016`) → `IncomingDialog`. `takeIn(files, fates, setlistId)` (`:1033`).
- Other: `reassignInstruments()` (`:1129`, "Redo automatic assignment"), `importedMarksFor`, `devicesHeard()`, `fileOf`/`relative`/`songAt(path)`/`partFile` (relative↔absolute file mapping). Pref keys are the `K_*` constants at `:1287-1333`.
- `centreTap(bottom)` (`:170`): see section 7.

### SheetsPlatform (`sheets-ui/.../SheetsPlatform.kt`)
Required members (no default):
- `deviceId` (stable; names the per-device log file), `startFolder`
- `pref/setPref`
- `openPart(song, part, file)` (open in editor), `pageText(file, page)`
- `audioOut: AudioOut?`, `microphone: Microphone?`
- `onMain(block)` (UI thread), `deviceName` (shown to other tablets)

Optional (default no-op or null):
- Paging and OCR: `pageCount`, `recognise`/`canRecognise`, `peek(file): PagePeek?` (page images and `printed()` music-font info)
- MobileSheets: `openMobileSheets(db): MobileSheetsImport.Tables?`
- Layout and sets: `setEdgeTaps/setTurnStyle/setStripSide/setStripLane/setMusicLane`, `openSet/closeSet/focusSetTab/swapPart`
- Network and devices: `meshRadio(): MeshRadio?` (BLE), `remoteBluetooth: RemoteBluetooth?` (RFCOMM), `controllers: ControllerInput?` (MIDI, POD Go), `watch: WatchLink?`
- Audio: `audioPlayer(): AudioPlayer?` (speed, pitch, volume, A-B loop), `decodeAudio(file, onChunk)`
- Files and sharing: `share`, `shareImage`, `copyImage`, `readClipboard`, `pickFiles`, `scanPages` (camera to PDF, `canScanPages`), `scanQr` (`canScanQr`), `writePng`
- Folders and power: `localFolder` (device-local, never synced), `cacheFolder`, `downloadsFolder`, `holdNetwork`, `keepAwake`, `log`
- Window (desktop): `canQuit`, `canWindow`, `windowed`, `setWindowed`, `screens`, `moveToScreen`, `quit`, `minimise`

Related interfaces in the same file: `AudioPlayer`, `AudioOut`, `Microphone` (with `devices`/`device`/`inUse`), `PagePeek`, `MeshRadio`.

Implementations:
- **Desktop**: `DesktopSheetsPlatform.kt` (412 lines), alongside:
  - `JavaSoundPlayer`, `JavaSoundOut` and `JavaSoundMic` (inside DesktopSheetsPlatform)
  - `DesktopMidiInput` + `DesktopPodGoInput` combined in `AllControllers`
  - `DesktopRemoteBluetooth` (Winsock/BlueZ via JNA)
  - `PdfPrinted` (PDFBox music-font reader), `PageSpace`, `DesktopPages`, `TopOfPage` (top third of a page for OCR), `AudioFiles`
  - Prefs in `DesktopPrefs`, device id key `K_DEVICE` (`desktop-<uuid8>`), `localFolder = AppDirs.dir("library")`. Desktop Tesseract is optional (`canRecognise` = tesseract != null). `startFolder` prefers `~/Sync`, `~/Syncthing`, `~/Music`.
- **Android**: `AndroidSheetsPlatform.kt` (470 lines), plus `AndroidMidiInput`, `AndroidPodGoInput` (USB host), `AndroidRemoteBluetooth`, `BleMeshRadio`, `AndroidWatch` (Wear data layer), `AndroidPrinted`, `AudioDecode`, `MediaAudioPlayer`, `TopOfPage`. Prefs are SharedPreferences. `localFolder = filesDir/library`. OCR is ML Kit. Camera scan uses the ML Kit document scanner. QR and clipboard come from `com.inkslate.ui.QrBits`.

---
## 4. Library data model (sheets-core)

### On-disk layout (all inside the user's synced music folder `<library>`, normally Syncthing'd)
```
<library>/.inksheets/log/<deviceId>.jsonl   one append-only edit log per device (LibraryLog.kt:151)
<library>/.inksheets/trash/                 removed songs' files, KEEP_DAYS then purged (LibraryTrash.kt:32)
<library>/.inksheets/imported-marks.json    MobileSheets markings, page/fraction coords (MobileSheetsMarks.kt:160)
<library>/.inksheets/readings/              Transcriber cache of OMR scores (Transcriber.kt:40)
                                            (falls back to <platform.localFolder>/readings with no root)
<platform.localFolder>/...                  per-device, never synced: scan "memory" file (LibraryScan), watch/ (calibrations),
                                            readings fallback. Desktop: %LOCALAPPDATA%\InkSheets\library; Android: filesDir/library
                                            (default fallback ~/.inksheets-local)
```
Music files are the library: PDFs and pictures in the folder. Records reference them by a forward-slash path relative to `<library>`.

### Edit log (`LibraryLog.kt`)
- `Stamp(ms, n, device)` is a hybrid logical clock, ordered by (ms, n, device). `Clock.tick()` and `observe()` ensure a new edit always beats what it saw.
- `Op(at: Stamp, kind, id, field, value: JsonElement)`: a single field of a single record. Deletion is `field="_deleted"` (`Op.DELETED`), an edit like any other (last writer wins, so an edit after a delete resurrects).
- `LibraryState` holds records `Key(kind,id)` → field → `Value(at, value)`. It is order-independent (last-writer-wins per field) and keeps formerValues/recordsEverWith history for part homes.
- `LibraryLog(root, device)`: each device appends only to its own `.inksheets/log/<device>.jsonl`, so Syncthing never sees one file written by two devices (no conflict copies). `readNew()` follows each log incrementally and re-reads a file that shrank. `compact(state)` rewrites the own log atomically via a temp file and rename, and `Library.compactIfLarge` triggers it at 4 MB. Conflict and temp files are ignored (`sync-conflict`, leading `.`).
- Record kinds (`Library.kt:841-848`): `song`, `setlist`, `folder`, `practice`, `profile`, `instrument`, `part`, `device`.

### Library (`Library.kt`, 918 lines)
- `class Library(log, now)`: reads from memory caches (`built()` is cached on `version`). `refresh()` takes in new ops. Every mutation is an `Edit` (a stamped batch) appended to the log.
- Records (immutable views built from fields):
  - `Part(id, file, firstPage?, lastPage?, instrument?, source: InstrumentSource{UNKNOWN,FILE_NAME,TEXT,OCR,PERSON}, label, also[], chair?, dup, placed)`
  - `Song(id, title, composers, arrangers, artists, genres, tags, key, timeSignature, tempo, tempoMark, tempoRead, reminder, difficulty, notes, parts, duplicates, audio: List<AudioTrack>, bookmarks: List<Bookmark>, created, opened, color, apart)`
  - `AudioTrack(file, label, loop start/end, speed, pitch, turnsMs...)`
  - `Bookmark(label, part, page, at, color, rank)`
  - `Setlist(id, name, folderId, entries: List<SetlistEntry>, order, notes, date, color, created, opened)`
  - `SetlistEntry(id, songId, note, tempo, color)`: the same song can appear more than once
  - `Folder(id, name, parentId, order, color)` (nests; `saneFolders` guards cycles)
  - Practice: `addPractice(songId, day, seconds)` writes record `"$songId|$day|$device"` so each device owns its total. `practiceOf` sums over devices.
  - `InstrumentProfile` (the instruments you play and which parts each shows), `Instrument` (the library's own/taught instruments)
- Ids are deterministic so independent devices converge: `songIdFor(title)` = `"s-"+digest(titleKey)`, `partIdFor(relativePath)` = `"p-"+digest(path.lowercase)`. Setlists, folders and entries use random UUIDs. `titleKey`/`matchKey`/`sortKey` normalise titles.
- API: `songs`, `song(id)`, `songWithFile`, `ensureSong`, `addSong`/`editSong(SongEdit)`, `setParts`/`writePart`/`orderParts`/`deletePart`/`movePart`/`mergeSongs`/`migrateParts`, setlist and folder CRUD (`addSetlist`, `editSetlist(SetlistEdit)`, `addToSetlist`, `moveInSetlist`, `mergeSetlists`, `addFolder`, `moveFolder`, `deleteFolder`, `foldersIn`, `setlistsIn`, `pathTo`, `setlistsUnder`), `markOpened`/`markSetlistOpened`, `saveProfile`/`deleteProfile`, `saveInstrument`/`deleteInstrument`, `noteDevice`/`deviceNames` (friendly names shown in Settings "Changes received from"), `partHome`/`formerHomes`/`partsEverIn`/`partDeletedAt`/`restoreSong`/`restorePart`.

### Folder-is-the-library machinery
- **LibraryScan** (388 lines): keeps records in step with the folder. It is idempotent and convergent across devices.
  - New files → the song they belong to; moved files followed; deleted files delete their part.
  - A device deletes only what it watched disappear (via the scan "memory" file). Files it never saw are left alone.
  - A mass disappearance (unplugged drive, renamed folder) is held back (`Report.heldBack`) unless `allowMassRemoval`.
  - Files whose part was deleted after the file's last change are not re-added.
  - Ignores dot-folders, Syncthing `~syncthing~`, `.syncthing.`, `.sync-conflict-` and `.tmp`/`.part` files.
  - Content-hash helper for matching moved files.
- **LibrarySort** (344): the automatic sort after each scan.
  1. A folder of one piece's parts is one song (`ImportPlan.folderSong`).
  2. Songs sharing a file merge.
  3. A second copy of a part is kept but flagged `dup` (hidden).
  4. Emptied songs go, with their setlist entries redirected.
  Never moves a `placed` part or a song with `apart=true`.
- **LibraryTrash** (120): `remove` moves a song's files to `.inksheets/trash/` (so the scan cannot re-add them). `entries()` / `restore` / purge after `KEEP_DAYS`.
- **PartChoice**: which part to open for the instrument profile, with nearness fallbacks (bass guitar → tuba, euph → trombone). Hides songs whose parts are all far from your instrument. Unnamed parts are shown marked.
- **Instruments** / `InstrumentReader`: built-in instrument table (names, transposition); reads an instrument from printed words ("Euph. T.C." → "euphonium tc"). `defaultProfiles`, `byId`, `partName`. `TempoReader` reads tempo words and BPM from the first page text.
- **Imports**:
  - `ImportPlan` (425): turns a pile of files into songs and parts from names and printed text. Handles file-name shapes like "01. Liberty_Bell-Tbn1 (2).pdf".
  - `BulkImport` (280): a zip or download folder → copied into the music folder under the download's own name. Folders become setlists, track numbers give the order, and re-importing adds only what is new.
  - `MobileSheetsImport` (286): reads MobileSheets `mobilesheets.db` as `Tables` rows (the platform supplies the SQLite reader, `openMobileSheets`). Columns are looked up by name and importing twice adds nothing.
  - `MobileSheetsMarks` (179): markings as page-fraction coordinates in `.inksheets/imported-marks.json`. `ImportedInk` turns them into strokes stamped "beginning of time" so any local edit wins in a merge.
  - `MsbBackup` (150): unpacks MobileSheets `.msb` backups (big-endian format of the MSPro-Tools project, versions 3 to 6, streams files to disk).
  - `SetlistBundle` (204): export/import a setlist as a plain zip (numbered PDFs, `Setlist.txt`, `setlist.json` with details).

---
## 5. Screens and panels (sheets-ui, one line each)

Home / library
- `SheetsHome.kt` — Home root. Tabs Songs, Setlists, Bookmarks (the third only when any exist). Top menu: Change library, Play together, Remote, Open a shared setlist, Settings, Move to screen, Minimise, Quit. Instrument chooser (`InstrumentChooser`: All instruments / Select instrument... / Edit instruments...), `SongsPane`, per-song row menu (Look at it, Details and parts, Add to setlist, Colour everywhere, Put into another song). Sorts A-Z, Recently opened, Recently added, Composer. Launches `Welcome` when no library is chosen. Hosts metronome, tuner, Recordings, Remote screen and IncomingDialog when Home is in front (lines ~277-289).
- `SetlistsPane.kt` — setlists in nestable folders, a setlist's view, ordering, dates, sorting, merge, share.
- `Bookmarks.kt` — the Bookmarks tab (`BookmarksPane`, sort modes in `BookmarkSort`).
- `SongDialogs.kt` — `SongEditorDialog` (song details, per-part instrument, chair, "also for").
- `SongNotes.kt` — `NotesDialog` (a song's synced notes).
- `LibraryDialogs.kt` — `ColourDialog`, `PickSongDialog`, `AskName`, `ColourBar`, `MARK_COLOURS`.
- `ProfilesDialog.kt` — instruments you play (profiles) and `OneOffInstrumentDialog`.
- `InstrumentListDialog.kt` — every instrument and its printed names; also `ReassignRow` ("Redo automatic assignment").
- `ListSearch.kt` — the search box for long lists; `matches()`.
- `DragAnywhere.kt` — a whole-row drag modifier (mouse drags at once; finger or pen after a hold).
- `PageViewer.kt` — a quick look at a part's pages from Home (also `PartThumbnail`).
- `Notches.kt` — shape helpers for the notched tabs in the remote.

Importing and adding music
- `AddMusic.kt` — `AddMusicDialog` (download, existing folder scan, camera), `BulkImportDialog`, `ScanDialog`.
- `ImportDialog.kt` — `ImportDialog` and `FolderPickerDialog` (gather new folder files into songs).
- `ImportReview.kt` — `ImportReview` and `existingFor` (review groups).
- `IncomingDialog.kt` — what to do with a file shared or dropped in (zip opens bulk import).
- `MobileSheetsDialog.kt` — import wizard for the MobileSheets library and `.msb` backups.
- `ShareDialogs.kt` — `FilePickerDialog`, `ShareSetlistDialog`, `OpenSharedDialog`.
- `NativePickers.kt` — hook for the OS file and folder pickers (set on Windows in `Main.kt`).
- `ImportedInk.kt` — converts imported marks to strokes.
- `PageActions.kt` — `MusicPageActions` (part or new song from picked pages).

Reading (over the open song; mounted by `paneOverlay` = `ActionStrip`)
- `ActionStrip.kt` — the action-button column over the page. The overlay root (`ActionStrip` at line ~105) also mounts the floating windows (lines ~283-328): `NotePopup`, `IncomingDialog`, `ReminderDialogs`, `ReadMusicPanel`, `TunerDialog`, `MetronomeDialog`, `AudioDialog`, `MusicStrip`.
  - Contents: `PartMenu`, `StripMenu` (More: Switch part, Fit page, Next/Previous song, Tuner, Recordings, Play together, Notes, Reminder, Read the music, Clear markings, window/screen items, Customise buttons), `StripEditor` (reorder/add buttons; switches for tap-to-turn, left side, names under buttons, turn style), `StripButton`, `PlaybackColumn`, `PresetButton`, `shortName`/`iconOf`/`groupOf` per `PerformAction`. `DEFAULT_STRIP` is at `SheetsState.kt:1322`.
- `Overlays.kt` — shared-space negotiation between the side strips and floating windows (`Overlays.Opened`, `StripTab`).
- `FloatingPanel.kt` — movable draggable window used by tuner, metronome, read-the-music, dialogs.
- `PlaybackBar.kt` — transport bar along the page foot while score-audio plays (`PlaybackBar`).

Music tools
- `MusicTools.kt` — `SharedMetronome` (one metronome for the app), `MetronomeDialog`, `TunerDialog` and `TunerBody`.
- `Click.kt` — `Click` object: the metronome's single voice, count-in and click-under-recording. Prefs: count-in bars, click with recording, click with playback.
- `Sound.kt` — one shared audio output mixed from the click and the instrument.
- `Ears.kt` — shared microphone (open/close ref-counting, level, "deaf" detection).
- `AudioPanel.kt` — `Recording` (the one shared player), `SelfRecorder`, `AudioDialog` (recordings per song: speed, pitch, loop, record yourself).

Remote / controllers / POD Go
- `RemotePanel.kt` — `RemoteControl` (host: this device controlled by others; client: this device as another's remote with a button deck), `RemoteScreen`, `RemoteSetup`, `HostSection`.
- `RemotePages.kt` — `RemotePager` and `RemotePage` enum (CENTER Buttons, SET The set, TOOLS Tuner & click, READING Reading the music, RECORDING Recordings), a 2D pager with notched tabs.
- `ControllerHub.kt` — plugged-in controllers: learning, binding, `heard(e)` (any remote action runs from a MIDI or POD Go control).
- `ControllerSettings.kt` — settings UI for controllers (list, learn, add control).
- `PodGoPicture.kt` — a drawn POD Go with spots to map footswitches, pedal and knob (`PodGoDialog`).

Play together (leader and follower)
- `CompanionPanel.kt` (class `Companion`) — leader/follower state, `followLeader`, `pageTurned`, ink sharing, BLE mesh (`meshOn`, `meshStatus`), UDP leader scanner, QR (`QrImage`, `ScanCode`, `qrPicture`), `applyPendingInk`.
- `Broadcasts.kt` — leader messages: `SendNote`/`SendNoteDialog`, `NotePopup`, `UrgentNote` (big overlay, coloured).

Experimental
- `Listener.kt` — Listen: follows the song's recording or the read score by ear (`WindowFollower`/`ScoreFollower`) and turns the pages.
- `ListenGauge.kt` — the card under the Listen button: mic level, time-to-turn.
- `TempoFollow.kt` — metronome follows the band's tempo from the mic (`TempoTracker`).
- `Transcriber.kt` — reads a part's pages to a `Score` with the OMR `Recognizer`, caches in `.inksheets/readings/`, `resume` after interruption.
- `ReadMusicPanel.kt` — "The music, read": the doubtful bars next to a clean redraw, MIDI export. Includes `EngravedMeasure`/`drawMarks`.
- `ScoreTools.kt` — music tools on the part: clean overlay, clean-up pen, selecting and playing bars, tempo/loop, signature fixes. Installs `Perform.pageMarks`/`musicGesture`.
- `BarCheck.kt` — "Fix": steps through uncertain bars with three alternative readings.
- `MusicStrip.kt` — the music tools' own side strip in a lane opposite the action strip (needs Read the music on). Also hosts `BarCheck` and `PlaybackBar`.
- `WatchFlicks.kt` — watch link: messages, calibration sessions, flick to page turn (`WatchFlicks` class, null link on desktop).
- `WatchSettings.kt` — watch on/off, calibrations (named, per instrument), last flick.

Settings entry: `SheetsSettings.kt` (see 6).

---
## 6. Settings

### Where each setting is
Slate's own Settings screen hosts `SheetsSettings(state)` through `AppFlavor.settingsSection` at its top. Beyond `SheetsSettings.kt`, InkSheets prefs live in the strip editor, the strip More menu, the dialogs, and each controller class's own `K_*` constants. There is no single settings table.

`SheetsSettings.kt` structure (lines 31-301):
1. **Library** heading (`:34`): "Import from MobileSheets..." (`MobileSheetsDialog`), then `ControllerSettings(state)` (Pedals, switches and faders switch → `hub.turn`, last event, "POD Go: set up on its picture...", "Add a control...", list of bindings with remove), then `LibraryHealth` if a library is open.
2. **Library health** (`:68`):
   - `ReassignRow` ("Redo automatic assignment")
   - Counts, and "Changes received from" per device (from `devicesHeard`, warns if only this device is heard)
   - "Check the folder now" with the last scan summary
   - Held-back removal notice with a "Remove those N" button
   - Missing parts list with "Remove missing (N)"
   - Foldable Trash with Restore (kept `LibraryTrash.KEEP_DAYS`)
   - "Recent changes" history (`scanHistory`)
3. **Experimental** (`:191`), tucked at the end:

| Setting | Pref key | Effect |
|---|---|---|
| "Play together over Bluetooth as well" | `sheets_companion_bluetooth` (`companion.meshOn`) | Enables the BLE mesh radio so the leader's page and messages hop device to device (for eduroam-style blocked Wi-Fi). Android only (`meshRadio()` null elsewhere: shows "Not available"). Messages sent this way go to everyone. |
| "Listen and turn pages (experimental)" | `sheets_listen_turns` (`state.listenTurns`) | Adds a Listen button on the toolbar for songs with a recording. Turning it off stops `Listener` and `TempoFollow`. Also shows `MicrophoneChoice` (device list, Test meter via `Ears`) where the platform lists inputs. |
| "Read the music off the page (experimental)" | `sheets_read_music` (`state.readMusic`) | Enables "Read the music" in a part's More menu, `ReadMusicPanel`, `MusicStrip`, ScoreTools, BarCheck and MIDI export. With Listen too, page turns come from the music itself. |
| "Turn pages with a flick of the watch (experimental)" (`WatchSettings`) | `sheets_watch_on`, `_active`, `_model`, `_instruments` | Watch-flick page turns plus calibrations. Needs Android and a paired Wear OS watch. |

### Other prefs (not in SheetsSettings.kt)
All are stored via `platform.pref`. Desktop uses `DesktopPrefs` (AppDirs), Android uses SharedPreferences.
- Library, instrument and parts: `sheets_library` (last library folder), `sheets_profile`, `sheets_part_picks`, `sheets_ocr_tried`, `sheets_tempo_tried`
- Strip and page behaviour: `sheets_strip_2`, `sheets_strip_collapsed`, `sheets_strip_left`, `sheets_strip_labels`, `sheets_strip_returned_1`, `sheets_strip_added_bookmark`, `sheets_edge_taps` (default on), `sheets_turn_style` (slide/fade/none)
- Sorting: `sheets_bookmark_sort`, `sheets_setlist_sort`, `sheets_entry_sort`
- Messages: `sheets_message_presets` (leader's one-tap messages), `sheets_urgent_colour`
- Click: `sheets_count_in_bars`, `sheets_click_recording`, `sheets_click_playback`
- Companion: `sheets_companion_follow` (Follow mode), `_share_ink`, `_bluetooth`, `_last`, `_following_since`
- Remote: `sheets_remote_key`, `_hosting`, `_last`, `_deck`, `_grid`, `_id`, `_set_az`, `_set_columns`
- Controllers: `sheets_controller_bindings`, `sheets_controllers_on`, `sheets_controller_spots`, `sheets_controller_one_way`
- Slate editor settings it reuses: `finger_pans_applied`, `window/windowed`, UI scale, KeyBindings and InputBindings.

---
## 7. Gestures and input routing

### Pages in music view (`AppFlavor.musicView`)
- **Taps** (finger only; the stylus writes). The page is fitted, so the thirds apply:
  - Desktop: `desktop/DocumentCanvas.kt` ~611-632 (the `.pointerInput` before the stylus gesture). `share = if (fitted) Perform.TAP_SIDE_SHARE else EDGE_SHARE(0.18f)`.
    - left `share` → `turn(-1)`, right → `turn(1)`, **only if `AppFlavor.edgeTaps`** (per `edgeTaps` setting)
    - middle → `Perform.centreTap(bottom)` where `bottom` = y within the bottom 25% (`CENTRE_TAP_BOTTOM`)
    - Tap thresholds: `TAP_MS=300`, `TAP_SLOP_DP=12` (`DocumentCanvas.kt:1469-1471`)
  - Android: `app/.../ink/DrawingView.kt:3256` (touch up: `edgeTapTurns` and `centreTapped(event.y)`), and `:1956-1960` (fling path with `TAP_TURN_MS`). Both use `Perform.TAP_SIDE_SHARE` when the page is fitted and unzoomed, else `EDGE_TAP_SHARE`. `centreTapped` is at `:1981`. Taps do nothing if the finger has been given a pen or eraser from the strip (`configFor(TOUCH).tool == Tool.PAN` gate).
  - `edgeTaps` is a pref (default on) toggled in the strip editor ("Tap the page to turn it").
- **Swipes**: desktop `DocumentCanvas.kt:612-620` — a swipe is |dx| > 1.5·|dy|, not panned, and either quick (<600 ms and > 50 dp) or, on a fitted page, a drag longer than 15% of the width (`SWIPE_MIN_DP=50`, `SWIPE_MS=600`, `FITTED_SWIPE_SHARE=0.15`). The turn goes through `onSwipe`, wired in `EditorScreen.kt:1999` to `Perform.run(NEXT_PAGE/PREVIOUS_PAGE)` (musicView only). Android: `DrawingView.swipeTurns = true` (`:1149` requires fitWholePage && SINGLE && tools put away). With the tools away, a finger always turns pages.
- **Centre tap** → `SheetsState.centreTap` (`SheetsState.kt:170`). It never acts on Home (`homeInFront`).
  - Everything put away, bottom tap → show the tools (`FULLSCREEN` off) and uncollapse the strip.
  - Tools or strip up → collapse the strip, hide tools and `Perform.recentre()` (re-fit the page).
  - Only the strip up with a bottom tap → bring up the tools too.
  - Otherwise (a tap in the middle with everything away) → show the strip, page stays put.
- **Wheel** (`DocumentCanvas.kt:1339` `wheelLoop`): in music view with tools put away (`resting()`), a plain mouse wheel notch turns a page (`:1433`: notch down on, up back, with rate-limiting `wheelTurnedAt` so a spin turns one page). Ctrl+wheel zooms about the pointer (`:1426`). Shift+wheel pans sideways. Two-finger trackpad scroll gestures swipe-turn (`TRACKPAD_SWIPE_NOTCHES`, `REVERSE_AFTER_MS`) or pan, and ctrl+trackpad pinches zoom.
- **Mouse, pen and finger discrimination** (`desktop/InputSignal.kt`, `PenInput.kt`, `WindowsPointer`, `X11Pointer`): `InputSignal.deviceOf(change)` is the one place that names the device (PEN/FINGER/MOUSE). Windows hands everything over as mouse events, so `PenInput` reads the raw window messages; `PenInput.active/device/contacts/gesturing`. A pen never turns a page; a finger while the stylus is down is a palm and ignored (`DocumentCanvas.kt` ~660). With tools away the mouse turns pages too (`mouseTurns`). While `Perform.musicTool` is on, a press belongs to the music tool (`Perform.musicGesture`) and no page turn happens.

### Keys and pedals
- **Desktop**: `desktop/Main.kt:157` `onKeyEvent`:
  - Ctrl+Shift with +/-/0 → UI scale (`UiScale`).
  - Everything else: `KeyBindingStore.actionFor(KeyStroke(keyCode, ctrl, shift))` → `KeyAction`. Each `KeyAction` that has a `.perform` calls `Perform.run`. The rest (SAVE, UNDO, COPY, BACK, ...) go through `shortcuts.fire(...)`.
  - Defaults (`desktop/KeyBindings.kt:107-121`): NEXT_PAGE = PageDown, Right, Down; PREVIOUS_PAGE = PageUp, Left, Up; FIRST_PAGE Home; LAST_PAGE End; NEXT_SONG Ctrl+PageDown; PREVIOUS_SONG Ctrl+PageUp; BACK Escape. These match AirTurn and PageFlip pedals, which present as keyboards. A user binding takes the action's defaults out of play. Table is editable in Slate's Settings (`KeyBindingStore`, `InputBindingStore`).
- **Android**: `MainActivity.kt:57` `dispatchKeyEvent` → `PedalKeys.handle(context, event, typing)` (`app/.../data/PedalKeys.kt:66`). Default map (`:19-28`): PAGE_DOWN, DPAD_RIGHT/DOWN, MEDIA_NEXT → NEXT_PAGE; PAGE_UP, DPAD_LEFT/UP, MEDIA_PREVIOUS → PREVIOUS_PAGE; MOVE_HOME → FIRST_PAGE; MOVE_END → LAST_PAGE. The action fires on ACTION_DOWN with repeatCount 0 only, and not while typing. `PedalKeys.bind/reset/table` persist custom maps.
- **MIDI and POD Go controllers**: `ControllerHub.heard(e)` (`ControllerHub.kt:187`) → bindings → `RemoteButton` actions (the same vocabulary as a remote's deck: PerformAction names plus app actions). Input comes from `SheetsPlatform.controllers` (`AllControllers([DesktopMidiInput, DesktopPodGoInput])` / Android equivalents). `ControlListener` de-chatters.
- **Remote deck** (`RemotePanel.kt:~358-382`): an incoming remote button maps to `PerformAction.entries.firstOrNull { it.name == c.action }?.let { Perform.run(it) }` or an app-level action.
- **Watch**: `WatchFlicks` → page turn through `Perform.run(NEXT/PREVIOUS_PAGE)` (flick detection in `core/watch/Flicks.kt`).
- **Listen**: `Listener` calls `Perform.run(NEXT_PAGE)` (`Listener.kt:196`).
- **Strip buttons** all call `Perform.run(action)` (`ActionStrip.kt:200`), so strip, pedal, remote and keyboard share one path.
- **Page turning past the end**: `Perform.run` → `document` false → `app(NEXT_SONG)`. `SheetsState.step` returns false if there is no setlist playing.

---
## 8. Networking

All three transports are small plain-JSON-line (or binary) protocols in sheets-core, with UI in `CompanionPanel.kt` and `RemotePanel.kt`. Neither uses encryption beyond the remote pairing key.

| Feature | Transport | Ports / IDs | Code |
|---|---|---|---|
| Play together (leader/follower) | TCP, one JSON line per message | **47820** (`CompanionLink.PORT`, `Companion.kt:39`) | `CompanionLeader` (`:263`), `CompanionFollower` (`:471`) |
| Leader discovery | UDP broadcast to 255.255.255.255 | **47821** (`ANNOUNCE_PORT`, `:40`; announce `:312`) | `CompanionScanner` (`:632`, binds `:639`) |
| Remote (a second device controls this one) | TCP, one JSON line per message | **47822** (`RemoteLink.PORT`, `Remote.kt:223`) | `RemoteHost` (`:502`), `RemoteClient` (`:708`), `SocketPipe`/`RemotePipe` |
| Remote discovery | UDP broadcast | **47823** (`ANNOUNCE_PORT`, `:224`; announce `:563`, scanner binds `:937`) | `RemoteScanner` (`:933`) |
| Remote over Bluetooth (classic) | RFCOMM, service UUID `7a1c3e52-0d4b-4f6e-9b8a-5e2f1c6d4b21`, name "InkSheets remote", fallback channel 23 on Linux | n/a | `RemoteBluetooth` interface (`Remote.kt:476`). Android: `AndroidRemoteBluetooth`; desktop: `DesktopRemoteBluetooth` via JNA (Winsock on Windows, BlueZ on Linux). Pair link carries `bt=` address and `ch=` channel. |
| Play together over Bluetooth ("mesh") | BLE advertising, frames of at most 20 bytes read from scan-result service data, hops capped at 6 (`MeshFrames.MAX_HOPS`), notes in 10-byte pieces (max 15) | n/a | `MeshFrames.kt` (frame codec), `MeshRadio` interface, `BleMeshRadio` (Android only; `SERVICE` constant there). `Companion.meshOn` starts it. |
| Watch | Wear OS data layer, paths under `/inksheets/watch/` (ping, hello, model, calibrate, cue, samples, flick, turned) | n/a | `core/watch/WatchWire.kt`, `AndroidWatch`, the `watch` module |
| USB: POD Go | libusb via usb4java (desktop), Android USB host | n/a | `podgo/PodGoLink`, `PodGoEvents`, `DesktopPodGoInput`, `AndroidPodGoInput`. On Windows it needs the WinUSB driver (Zadig) because Line 6's driver holds the unit; this is logged once. |
| USB or BT MIDI | Java Sound (desktop) / Android MIDI | n/a | `DesktopMidiInput`, `AndroidMidiInput` |
| Library sync | None in-app. The synced folder (e.g. Syncthing) carries the per-device logs and files. | n/a | `LibraryLog`, `SheetsState.startWatching` |

Join and pair links (QR text):
- `inksheets://join?name=...&hosts=a,b&port=47820` (`CompanionLink.joinLink`/`parseJoin`; bare `host[:port]` is also accepted)
- `inksheets://remote?name=...&hosts=...&port=47822&key=K7Q2PX&bt=AA:BB:..&ch=` (`RemoteLink.pairLink`/`parsePair`). Pairing is a shared key in the code, and the client reconnects on its own afterwards.

Companion follow modes (`CompanionLink.Follow`): AUTO, SAME_PAGE, NEXT_PAGE (two-page spread), SONG_ONLY. Leader messages are `CompanionLink.Note` (text, instruments filter, urgent). Ink is shared only when the leader turns `shareInk` on.

---
## 9. Test harness

### What drives what
- Tests live in `sheets-desktop/src/test/kotlin/com/inksheets/desktop/` (about 83 files, 79 with `@Test`; many are tools, debug dumps and surveys). Core logic has its own JVM tests in `sheets-core/src/test/...`, including `omr/`. Compose tests use `runDesktopComposeUiTest` (`androidx.compose.ui.test`, `compose.desktop.uiTestJUnit4`) with `testImplementation("junit:junit:4.13.2")`.
- **Run the shipping app**: call `installInkSheets { platform -> platform.setPref("sheets_library", libFolder.absolutePath) }` (the `prepare` callback sees the platform before `SheetsState` exists). It returns `() -> SheetsState`. Then render the real editor:
  ```
  val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
  val home = AppFlavor.home!!
  AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }   // capture the "open a file" callback
  runDesktopComposeUiTest(w, h) { setContent { InkSlateTheme { AppRoot(...) } } ... }
  ```
  (see `SheetsTouchTest.kt`, `UiAuditAppTest.kt:53+`). Every `@After` resets the `AppFlavor.*` globals and `Perform.*`, because they are process-wide statics.
- **Fingers**: `desktop/SimulatedTouch.kt` fakes what Windows delivers (mouse events stamped as touch via `stamp()`, multi-finger contacts via `finger(id,x,y)` / `lift(id)`). `SimulatedTouch.on = true` sets `PenInput.simulated` and `PointerDiagnostics.windowAtForTests`. Test with `performMouseInput` on `onAllNodesWithText`.
- **UiAudit** (`UiAudit.kt`): a layout linter over the semantics tree. It finds overlapping named or clickable nodes (overlap of at least 20% of the smaller), clickables cut by the window edge, and hidden/clipped nodes. It supports `nested` (by-design overlaps) and `required` (names that must be visible), and photographs each scene to `build/uiaudit/<size>-<scene>.png`, appending findings to `build/uiaudit/report.txt`. `UiAudit.SIZES` has 12 `Triple(name, wDp, hDp)`: phone-small(360x640), phone-small-land, phone(390x844), phone-land, tablet7(600x960), tablet7-land, tablet10(800x1280), tablet10-land, tablet13(1024x1366), tablet13-land, laptop-short(1280x720), laptop(1600x1000). Run with `-Dinksheets.uiaudit=1 [-Dinksheets.uiaudit.sizes=phone,tablet10-land]`; `-Dinksheets.uiaudit.debug=<text>` prints bounds of matching nodes.
- **Isolated app data**: `sheets-desktop/build.gradle.kts` `tasks.withType<Test>` points `LOCALAPPDATA` and `XDG_DATA_HOME` at `build/test-appdata` and sets `user.home` to the same folder. `AppDirs` honours `LOCALAPPDATA` on any OS, and the folder is wiped before each run (`doFirst { deleteRecursively }`), so tests never touch the real settings, library or annotations. `maxHeapSize = 5g` (whole pages drawn large). `-Dinksheets.jfr=<file>` records a JFR profile.
- **System-property forwarding**: every `-Dinksheets.*` given to Gradle is copied into the test JVM (`sheets-desktop/build.gradle.kts` last block). Setting one also marks the task never up-to-date. `inksheets.msb` / `inksheets.msdb` also turn on stdout. Common ones:
  - `inksheets.shots=<dir>` (photograph; 29 uses, gates `*Shots` and many screen tests)
  - `inksheets.lib=<library folder>` (real-library tests)
  - `inksheets.uiaudit[.sizes]`
  - `inksheets.omr[.file/.page/.bar/.why/...]` (OMR accuracy and debug)
  - `inksheets.bench.*` (benchmarks)
  - `inksheets.turns.*` (Listen turn benchmark)
  - `inksheets.playback`, `inksheets.bluetooth=true`, `inksheets.podgo[.usb/.out]`, `inksheets.msb`, `inksheets.msdb`, `inksheets.fixes`, `inksheets.glyphs`, `inksheets.words*`
- Many tests read real music from `%USERPROFILE%\Music\Sheet Music\InkSheets` and `assumeTrue` the file exists, so they skip silently on CI and other machines. The `build/touch*` and `build/uiaudit*` folders are write targets.

### Main test classes by purpose (sheets-desktop)
- **App-level behaviour (real wiring)**: `SheetsTouchTest` (fingers: taps, flicks, pinch, tools out never marks), `SheetsScreensTest` (screens photographed, assertions), `RemoteScreensTest` (centre-tap strip, Bookmarks tab, recordings empty state, a remote driving another device), `BarCheckTest` (Fix flow), `PageReadTest` ("Page 3" read by tapping), `ReadMusicTest`, `RedrawInAppTest`, `ListenTest` (a recording played as a live room into a fake mic), `WatchFlicksTest` (+ `FakeWatch`), `SetlistSortTest`
- **UI audits (layout)**: `UiAudit` (library), `UiAuditAppTest`, `UiAuditControllersTest`, `UiAuditRemoteTest`, `UiAuditWatchTest`
- **Shots**: `ListenGaugeShots`, `MusicStripShots`, `ScoreToolsShots`, `PrintedMarksShots`, `RedrawShots`, `WindEnsembleShots`, `RealScreensTest` (real library at phone, tablet and laptop sizes)
- **Real-library checks**: `RealSortTest`, `RealScanTest`, `RealScanTruthTest`, `RealBackupTest`, `RealMarksTest`, `RealFilesSurvey`, `RealLibraryDump`, `LibraryCostTest`, `LibraryKindsSurvey`, `MobileSheetsDbTest`
- **Hardware and platform**: `BluetoothListenTest`, `PodGoCapture`, `PodGoUsbCheck`, `MicCheck`, `JavaSoundPlayerTest`, `AudioFormatsTest`
- **OMR / reading accuracy**: `OmrAnswerKeyTest` (+ `AnswerKey`), `OmrRealPagesTest`, `OmrScoreCrossTest`, `MarkReaderParityTest`, `StaffGapTest`, `CleanPrintTest`, `EngraverStyleTest`, `GlyphShapesTest`, `SynthLoadTest`, `RecogniseTest`, `PlaybackTimelineTest`, `BookPlaybackTest`, `ScoreFollowerRealTest`
- **Benchmarks**: `PageTurnBench`, `ReadingBenchmark`, `RestsAndRepeatsBench`, `NetSpeed`
- **Debug and export tools (not assertions)**: `*Export`, `*Survey`, `*Debug`, `*Tool`, `DigitMasksTool`, `FontGlyphsTool`, `GlyphOutlinesTool`, `KeyDump`, `PageCrop`, `PdfAtDebug`, `PdfBoxDebug`, `OmrBarDebug`, `OmrTraceDebug`, `WordSynth`
- **sheets-core unit tests** (run in CI, no Compose): `LibraryTest`, `LibraryScanTest`, `LibrarySortTest`, `CompanionTest`, `RemoteTest`, `RemoteLinkDropTest`, `ControllersTest`, `BulkImportTest`, `ImportPlanTest`, `MobileSheetsImportTest`, `MobileSheetsMarksTest`, `SetlistBundleTest`, `InstrumentReaderTest`, `TaughtInstrumentsTest`, `PepBandNamesTest`, `NamingCorpusTest`, `MeshFramesTest`, `MetronomeCountInTest`, `MusicToolsTest`, `TempoTrackerTest`, `TempoReaderTest`, `TimeStretchTest`, `WindowFollowerTest`, `podgo/PodGoLinkTest`, `watch/FlicksTest`, plus the `omr/*Test` set.

### Typical commands
```
./gradlew :sheets-core:test
./gradlew :sheets-desktop:test --tests '*SheetsTouchTest*'
./gradlew :sheets-desktop:test --tests '*UiAuditAppTest*' -Dinksheets.uiaudit=1 -Dinksheets.uiaudit.sizes=phone,tablet10-land
./gradlew :sheets-desktop:runSandbox         # hand-try the app in build/sandbox-run (music under Music/Sheet Music/InkSheets)
```

---
## Not verified or worth flagging
- I did not read the bodies of `Companion`/`RemoteControl`/`WatchFlicks` UI state machines, `ScoreTools`, `Listener` or the OMR package beyond header comments and signatures.
- The `inkslate` flavor's FlavorSetup stub exists but I did not read it.
- See also PREFERENCES.md, PERFORMANCE.md, TESTING.md in this folder.
- Desktop key defaults beyond the page and song keys (e.g. METRONOME, TUNER, PEN, etc.) may have defaults in `KeyBindings.kt` that I did not list. I only quoted the page, song, first/last and BACK ones.
- Settings pref-key list is gathered from `K_*` constants and may miss prefs that other classes (e.g. `ScoreTools`, `Listener`) store under different names.
---
## 10. Added in the 2026-10-08/09 sweep

- **Undo & Recently deleted** - `SheetsState.offerUndo/undoOffer` + `UndoBar` (RecentlyDeleted.kt), shown on Home. Every remove/delete offers Undo: `removeSong(s)`, `removePart`, `deleteSetlist`, `deleteFolder(withSetlists)`, `takeOut`. `RecentlyDeletedDialog` lists `LibraryTrash.entries()` (songs, parts, and `keepFile` copies of replaced files) plus `Library.deletedSetlists()`; reached from Home's menu and Settings > Library health.
- **LibraryTrash.keepFile(rel, title)** - an old copy kept before anything overwrites a file (Replace a part, bundle/bulk import of a changed file). Restore puts it back beside a newer file as "(restored)".
- **Library scan** - case-only renames followed; a file this device watched go and finds again comes back; 0-byte files wait; scans serialised (`SheetsState.scanLock`), trash purged hourly; `LibrarySort` reads sizes/ages from the walk.
- **Search** - `fold()`/`matches()` in ListSearch.kt (accents, apostrophes, any word order); Songs tab searches title, people, tags, notes, parts' instruments and files (cached per library version).
- **Home** - several songs chosen by a hold (`SongRow.onHold/chosen`), right-click/two-finger menu on rows, "N new in the last day", held-back removal card, `FolderChoiceDialog` (Move to... in SetlistsPane), `nothingToOpen` for recording-only songs, dropped zips queued (`nextOffered`).
- **Reading view** - page colour shared by all songs: `AppFlavor.readingMode` (desktop and Android) <- `SheetsPlatform.readingMode`, kept in pref `sheets_page_colour`, strip More > Page colour. Slide animation drawn (drawWithContent) not laid out, so taps land. `EditorScreen` HALF_PAGE_* stays within the page. Escape in music only toggles tools. Space/Shift+Space turn. `QuickTools.toggle` gives the pen back after Eraser off. Edge notice (`SheetsState.edgeNotice`) "End of the set".
- **PlayAlong.kt** - a song's recording turns its pages: `AudioTrack.partTurns` (per part id; `turnsFor`, `withTurn`), learned from turns made while it plays, followed by `tick()` every 100 ms from ActionStrip; hint in the playback column.
- **Listen** - `WindowFollower(startEndMs=, found, confidence)`: page-wide start window, confidence from raw match vs best elsewhere, refuses unsure jumps > 6 s; Listener turns one page when trusted, jumps straight to the right page when sure for 2 s, waits 2 min of silence. Knobs `-Dinksheets.turns.early/.reject/.rejectFrames/.sharp` for PageTurnBench (23/28).
- **Reading the music** - `Measure.certainty` (SURE/LOOK/FIX). ScoreTools `Tool.CHECK` colours bars; tap -> `fixBar`; `goOn` (Then: next red / close); `cleanWhole`; `back()` history; bar renumbering (`numbersOf`, applied after rests, before fixes); `BarChoices.FOCUS` gained Extra note / Missing note / Grace note. BarCheck.kt: docked fixed panel, `FixFoot` (fixed buttons + Wrong row), `BarNumber`.
- **Readings storage** - folder `r<READER>-<idOf>` where `idOf` = size+CRC of the PDF's first revision (marks are appended, so marking never forces a re-read); older readers' readings shown until re-read (`olderFolder`); legacy whole-file ids adopted. Player edits (fixes, rests, sigs, numbers, wrong, cleaned) in `.inksheets/readings/edits-<idOf>/<kind>-<device>.txt` (newest wins), not prefs. `Transcriber.progress` drives the edge progress bar; `readQuietly` reads band parts 3 at a time.
- **Playback** - `omr/Interpretation.kt` (analyse -> phrases, levels, articulation; tempoMap; tones) used by `Performance.play` and `EnsemblePlayer` (shared tempo map, balance, `guide` = "+ my part" via `ScoreTools.bandWithMe`). Synth: `Tone.endVelocity/legato`, loudness->brightness, delayed vibrato, breath at onset, `Room` reverb. `PlaybackDemo` (-Dinksheets.demo=N) renders WAVs.
- **Watch** - `SheetsPlatform.inForeground` (Android: activity lifecycle callbacks in FlavorSetup); WatchFlicks sends LISTEN off when not in use; beat 20 s, `WatchWire.QUIET_MS` 60 s; FlickService: one short tick per outage, gives up after 3 unanswered; weak calibrations not activated.
- **T3/T4 fixer work** - Ears fan-out (one mic, many listeners), WavWriter header refresh, mp3 streaming decode, PracticeLog.kt, tempo-follow octave fix; remote host line/connection caps, follower ids, message ids, urgent notes, "did nothing: reason" answers, sequence re-entrancy, MIDI running-status fix, Find on the remote's set page.

## 11. Added 2026-10-09/10 (after test.290)

- **Strips** - ActionStrip and MusicStrip are one column, never scrolling (a scrolling column swallowed taps). Short screens: names go small (`LocalSmallNames`), then the last buttons move into More (page turns, Undo, Show/hide tools always stay). Page count is plain text with a File button (`Perform.openPages`). Reading shows one tile (Reading, bar, %) and Stop (`Transcriber.stopAsked`: ends after the page in hand, removes the whole-read mark). Unread pages offer only Read and Page N.
- **Floating panels** (FloatingPanel.kt) - `PanelSpots.open` holds the open panels' rects; a new one opens beside them (`free`), never on top; clear of the strips' edge tabs when they step aside.
- **Fix** (BarCheck.kt) - a hand edit takes the whole screen (a `Popup` at window origin) when the room is short (`whole`: hand work under 620 dp, or anything under 360 dp); wide screens use the editor's four long rows (`BarEditor(flat=)`), `tiny` lowers it further.
- **Screen before reader** - `core/RenderGate`: screen draws (`display{}` inside the PDF sources) go before the reader's draws (`reading{}`), which wait 400 ms after a screen draw, 1 s after a page turn (`touch()`), 2.5 s after a file opens; one reader render at a time (2 on roomy desktop); Android reader pool = half the cores. `ScreenFirstTest`.
- **Carried fixes** - `omr/CarryFix.kt`: a fix kept in one part is carried to the same bar of the song's other parts when that bar was read the same as the original (concert pitch, whole-octave offset allowed, exact rhythm, matching clef/key/time after transposition, a neighbour bar agreeing). Stored in the receiving part's edits as kind `sheets_carried` (`CarriedFix`), layered under its own fixes in `ScoreTools.scoreOf` (`baseOf` / `asRead` / `scoreOf`). Quiet by the user's choice: the bar is simply SURE there; Fix's detail line says "Put right from <part>". `back()` takes copies back; `carryIn` brings siblings' fixes into a part read later.
- **Playback engine** (omr/Synth.kt, Interpretation.kt) - `Feel` holds the settled choices: `onset = 'D'` (buzz grows dark into its tone, soft low-passed air "t" under accent/marcato/staccato), slur feel per family (`Synth.Slur`: overlapping crossfade, dip, flat start), `expression`, `drift = 0`. Euphonium spectrum from the user's reference recordings: `Synth.Voicing.EUPHONIUM` (h1..h10 dB by register x loudness), `Patch.toneBlend` 0 = "Song for Ina" (dark) .. 1 = dry reference (default), `Synth.euphonium(blend)`. Vibrato planned per slurred line (`Interpretation.vibratoPlans` -> `Tone.vibrato`): straight start, even bloom, intensity line from level/pitch/crescendo/phrase high point, quickens ~14% over the first 2 s, eases at phrase ends; no randomness. Dynamics as an S-curve over 0.6-3 s; exponential releases; output soft-clipped to 0.97. `RenderAhead` renders on its own thread 200 ms ahead of the device (Sound.play(ahead = true)); device buffers 100 ms desktop / >=120 ms Android.
- **Listening tools** - `PlaybackWavSet`, `PlaybackWavTone`, `RealLibraryRender` (env-gated) render WAVs to `build/listen/roundN`; `tools/DecodeReference` decodes a reference mp3 (-Dinksheets.ref); the tone/vibrato measuring scripts are Python (numpy) kept outside the repo.
- **Sampled instruments** - `sheets-core/.../sampler/` (Wav, Presets for .dspreset/.sfz, SampledInstrument, SampledVoice, SamplerLibrary) and `sheets-ui/SampledInstruments.kt`: kept in `<library>/.inksheets/instruments/<name>/`, `assignments.properties` maps instrument id -> instrument; Settings > Instruments (add .dspreset/.sfz/zip, "Plays:" per instrument, remove); Sound menu "Yours" (`soundAs = "sampled:<name>"`). All playback patches come from `SampledInstruments.patchFor(state, id)` (seat ids like euphonium-bc fall back to the family). Loading is in the background.
- **Silent tests** - `OutLine` (sheets-desktop) is every sound-card line; under `-Dinksheets.silent=1` (set for all sheets-desktop tests) it plays nothing at the same pace.
