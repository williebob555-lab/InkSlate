# InkSlate

An annotation app for homework: open PDFs and images, draw on them with a stylus, and save either
a copy or over the original. Android and Windows, built to sideload onto your own devices, with a
sync model that survives editing the same file on a tablet and a laptop.

---

## Building

Everything needed is already installed on this machine.

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` (~29 MB, debug-signed so it
sideloads without extra steps).

**One requirement:** Gradle must run on Android Studio's bundled JDK. From Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

Android Studio sets this itself, so opening the project there and pressing Run needs nothing.

### Installing on a tablet

With USB debugging on and the device plugged in:

```bash
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK across and tap it. On first launch the app asks for **All files access** — this
is a Settings screen, not a normal permission prompt.

### Toolchain notes

| | |
|---|---|
| JDK | 25 (Android Studio's bundled JBR — no other JDK on this machine) |
| Gradle | 9.7.1 (wrapper is checked in) |
| AGP | 9.4.0 — has **built-in Kotlin**; adding `kotlin-android` is a hard error |
| Kotlin | 2.4.10 |
| compileSdk | 37.1, minSdk 26 |
| SDK path | `%LOCALAPPDATA%\Android\Sdk` |

The Android SDK that Visual Studio installs under `Program Files (x86)` is **read-only**, so
Gradle cannot install missing components into it. `local.properties` deliberately points
somewhere writable instead. Visual Studio itself is not used and cannot open this project.

---

## How saving works

Two different things are written, and keeping them separate is the core design decision.

### The sidecar — `homework.pdf.inkdoc`

Written constantly as you draw, next to the file it annotates. JSON. Holds every stroke with its
brush, pressure profile, colour and z-order.

The original PDF is never touched by autosave. That means:

- Syncthing only ships a small text file, not a whole PDF, on every change.
- Strokes stay editable forever, on any device.
- Two devices that edited offline can be **merged** instead of one silently winning.

### The export — the file you hand in

Written only when you press Save. Either:

- **Editable annotations** — each object becomes a real PDF annotation carrying an appearance
  stream. Still selectable in Acrobat, and renders correctly in Chrome, Canvas and anything else,
  because the appearance stream is always written rather than left for the viewer to synthesise.
- **Flattened** — drawn into the page content. Permanent, and the safest option for a submission
  portal that strips annotations.

Global default, overridable per file.

---

## Cross-device editing

Stroke IDs are `<deviceTag>-<counter>`, where the device tag is random per installation. Two
devices drawing offline therefore cannot mint colliding IDs — without this, a Syncthing merge
would destroy work silently.

Merging is a 2P-Set with last-writer-wins per object:

- Concurrent additions from both devices are unioned.
- Deletions leave **tombstones**, so a sync from a device that had not seen the delete cannot
  resurrect erased strokes.
- An object edited on both sides keeps the copy with the newer `updatedUtc`.
- The merge is commutative — it does not matter which device runs it, or in what order Syncthing
  delivered the files.

When Syncthing cannot reconcile a file it renames one copy to `*.sync-conflict-*`. Those are
found, merged in, and deleted automatically when the document is opened, because left alone they
are invisible lost work.

This logic is covered by unit tests (`app/src/test/`) — it is the part of the app whose failures
would be silent and only noticed days later.

---

## Safety

Overwriting the original is the one action here that can destroy something the app did not
create, so:

- Backups are on by default. The original is snapshotted into `.inkslate-backups/` first, keeping
  the last 20 versions, which doubles as version history.
- **If the backup fails, the overwrite is refused** rather than proceeding quietly.
- All file writes go through a temp-file-then-rename, so a crash mid-write cannot leave a
  truncated file where the assignment used to be.
- Copy naming never returns a name that already exists.

---

## Features

**Drawing**
- 8 brushes with distinct pressure curves and character - ballpoint, gel, fountain, pencil
  (soft graphite edge), marker, brush, highlighter, calligraphy (chisel nib, width follows
  direction)
- Stylus pressure with a per-profile curve editor; velocity tapering as the fallback for finger
- Adjustable input smoothing
- Palm rejection: once a stylus is on the glass, skin contacts are ignored
- Separate pen and finger profiles, switched automatically by whichever touches the screen
- Two-finger pan and zoom that never leaves a mark, with momentum scrolling
- Shape recognition: rough circles, lines and boxes become clean ones
- Ruler with protractor readout that ink snaps to

**Objects**
- Lines, arrows, rectangles, ellipses, tables with editable cells
- Text boxes with font, size, colour, alignment, wrapping, background and border
- Maths symbol palette, ~120 characters placed as real text
- Stamps: axes, number line, grid, unit circle, triangle, brace, music staff
- Region capture: box a textbook figure and drop it in as a movable object
- Marquee and tap selection, move, resize, rotate; cut, copy and paste across documents

**Reading**
- Vertical, horizontal, grid, two-page spread, or single-page layouts
- Table of contents read from the PDF, bookmarks (synced), go-to-page, resume where you left off
- Full-text search with results streaming in as pages are scanned
- Highlighter that snaps to the document's text lines
- Night, sepia, greyscale and high-contrast reading modes
- Automatic margin cropping

**Files**
- Browses storage directly, thumbnails showing your ink already on them
- Blank documents: ruled, grid, dot, graph, Cornell, music, isometric, up to whiteboard size
- Export the whole document or a page range; version history with restore
- Open-with from Gmail, Canvas, Drive

**Diagnostics**
- Live frame times, stroke counts, cache hit rate and memory, in Settings
- Activity log of opens, saves, exports, merges and render failures
- Crash reports readable in the app

## Layout

```
core/         Shared by both builds: document format and merge, stroke outlines, paper geometry
app/          Android
  ink/          Stroke model, brushes, the drawing surface, shared rasteriser, clipboard
  pdf/          PDF and image rendering, PDF export, blank document generation
  data/         Sidecar format and merge, save preferences, file browsing, device identity
  ui/           Compose screens: browser, editor, settings
desktop/      Windows: the same screens in Compose for Desktop, against full Apache PDFBox
```

`StrokeRasteriser` is the single renderer used by the editor, thumbnails and image export. Three
subtly different renderers is how "it looked different when I exported it" bugs are made.

---

## Not built yet

- **The Windows editor's tools.** Selection, shapes, text, stamps, the ruler and the reading
  modes are Android-only so far - see [The Windows build](#the-windows-build).
- **Infinite canvas.** Pages are fixed-size; true Whiteboard-style panning needs the page model
  to become unbounded.
- **Word documents.** Android has no usable `.docx` renderer, so this needs either a conversion
  step or server-side rendering.
- OCR for scanned PDFs (which have no text layer, so search and text snapping do nothing there),
  and audio notes.

---

## The Windows build

The same application, not a companion to it: the same four screens, the same palette, and `:core`
shared between them so the two cannot disagree about what a document contains. A file synced from
the tablet opens on the laptop with its handwriting already on it and nothing else to copy across.

```bash
./gradlew :desktop:run
```

`./gradlew :desktop:packageMsi` builds an installer. Both need the same `JAVA_HOME` as the APK.

What is there now: the home screen and its folders, recents and starred items, the file browser,
new blank documents in every ruling the tablet offers, a pen, a highlighter, an eraser, undo,
saving into the document, flattened export, and the update check.

What is not, and where the two builds visibly differ:

| | |
|---|---|
| Editor tools | Pen, highlighter and eraser only. Selection, shapes, text, tables, stamps, the ruler and the reading modes have not been brought across. |
| Infinite canvas | Not in the desktop editor, so "New" does not offer a canvas that grows. Offering paper that claims to grow and then does not is worse than not offering it. |
| Colour picker | The paper and ruling swatches are the tablet's lists, but the "+" that opens a full picker is not there yet. |
| Item actions | Reached by right-click rather than a long press, which is what a mouse expects. Same sheet, same actions. |
| Pressure | A mouse has none and desktop pens report it inconsistently through the JVM, so width falls back to speed - the same fallback the tablet uses for finger input. |

The palette in `desktop/Theme.kt` is a deliberate copy of the Android one rather than an
approximation, and it is the one file that has to be kept in step by hand. A colour that is nearly
right is worse than one that is obviously different: it reads as a rendering fault rather than a
design.

What must never be written twice is anything that decides what a document *is* or what a mark
*looks like*. `InkDocument`, `StrokeOutline` and `PaperPattern` all live in `:core` for that
reason - two implementations of "graph paper" or "a tapered stroke" line up on the day they are
written and drift apart afterwards.

---

## Releases and updating

The app is on GitHub at `https://github.com/williebob555-lab/InkSlate`, and updates itself from
there. **Settings → Updates → Check for updates** asks the GitHub releases API what the newest
published version is, downloads the APK, and hands it to Android's installer.

The version comparison and the download live in `core/UpdateCheck.kt`, deliberately, so the
Windows build can use exactly the same code and only change which file it picks out of a release.

### Cutting a release

Version numbers come from the git tag and nowhere else — the build reads `INKSLATE_VERSION_NAME`
and `INKSLATE_VERSION_CODE` from the workflow, so the APK, the release page and the in-app
updater cannot disagree.

```bash
git tag v1.1.0 && git push origin v1.1.0
```

That builds a signed APK, attempts a Windows MSI, and publishes both to a new release.

### The signing key (one-time setup, required)

Android refuses to update an app when the new APK is signed with a different key — it says
"App not installed" and stops. A debug key is generated fresh per machine and per CI run, so
every release must be signed with one key that never changes. **If this keystore is ever lost,
no future build can update an existing install; it has to be uninstalled and reinstalled.**

Create it once and keep a backup somewhere safe. `keytool` is not on the PATH on this machine -
it ships inside Android Studio's bundled JDK, which is the same JDK Gradle builds with:

```bash
"/c/Program Files/Android/Android Studio/jbr/bin/keytool.exe" -genkeypair -v   -keystore "$HOME/inkslate.jks" -storetype PKCS12   -keyalg RSA -keysize 4096 -validity 10000 -alias inkslate   -dname "CN=InkSlate, O=InkSlate, C=US"   -storepass YOUR_PASSWORD -keypass YOUR_PASSWORD
```

Kept outside the project directory deliberately, so it cannot be committed by accident even
though `*.jks` is git-ignored. PKCS12 keystores use one password for both the store and the key,
so `KEYSTORE_PASSWORD` and `KEY_PASSWORD` below are the same value.

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 inkslate.jks` (the whole file, one line) |
| `KEYSTORE_PASSWORD` | the store password chosen above |
| `KEY_ALIAS` | `inkslate` |
| `KEY_PASSWORD` | the key password chosen above |

To build a signed release locally, put the same values in a `keystore.properties` at the project
root (it is git-ignored):

```properties
storeFile=C:/path/to/inkslate.jks
storePassword=...
keyAlias=inkslate
keyPassword=...
```

Without a keystore, `assembleRelease` still works but signs with the debug key and prints a
warning saying the APK cannot update anything.
