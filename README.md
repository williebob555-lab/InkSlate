# InkSlate

An Android annotation app for homework: open PDFs and images, draw on them with a stylus, and
save either a copy or over the original. Built to sideload onto your own devices, with a sync
model that survives editing the same file on a tablet and a laptop.

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
ink/          Stroke model, brushes, the drawing surface, shared rasteriser, clipboard
pdf/          PDF and image rendering, PDF export, blank document generation
data/         Sidecar format and merge, save preferences, file browsing, device identity
ui/           Compose screens: browser, editor, settings
```

`StrokeRasteriser` is the single renderer used by the editor, thumbnails and image export. Three
subtly different renderers is how "it looked different when I exported it" bugs are made.

---

## Not built yet

- **Windows version.** The `.inkdoc` format is platform-neutral and ready for it.
- **Infinite canvas.** Pages are fixed-size; true Whiteboard-style panning needs the page model
  to become unbounded.
- **Word documents.** Android has no usable `.docx` renderer, so this needs either a conversion
  step or server-side rendering.
- OCR for scanned PDFs (which have no text layer, so search and text snapping do nothing there),
  and audio notes.
