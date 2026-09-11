# InkSlate

An annotation app for homework. Open a PDF or a picture, write on it with a stylus, and save
either a copy or over the original. There is an Android build and a Windows build, and they are
the same application: a worksheet annotated on the tablet opens on the laptop with the handwriting
already on it.

---

## Getting it

Everything is on the [Releases page](https://github.com/williebob555-lab/InkSlate/releases).

**Android** — download the `.apk` and open it. Android will ask once for permission to install
apps from your browser or files app; allow it and the install carries on. On first launch InkSlate
asks for **All files access** — that one is a Settings screen rather than a normal permission
prompt, and it is what lets the app open documents wherever you keep them instead of copying them
into its own folder.

**Windows** — download the `.msi` and run it.

### Updating

**Settings → Updates → Check for updates**, on either build. It downloads the new version and
hands it to the installer; nothing has to be uninstalled first.

**Settings → Updates → Test builds** switches to a faster channel that also sees the builds
published between releases. Those carry fixes earlier and are less proven; turning the switch back
off and taking the next stable release puts you back on the ordinary channel.

---

## What it does

**Drawing**
- Eight brushes with their own pressure curves and character — ballpoint, gel, fountain, pencil,
  marker, brush, highlighter, and calligraphy with a chisel nib whose width follows direction
- Stylus pressure with a curve you can tune, and velocity tapering as the fallback for a finger
  or a mouse
- Adjustable smoothing
- Palm rejection: once a stylus is on the glass, skin contacts are ignored
- Separate pen and finger settings, switched automatically by whichever touches the screen, and
  two more for a stylus barrel button held down
- Two-finger pan and zoom that never leaves a mark, with momentum scrolling
- Shape tidying: rough circles, lines and boxes become clean ones
- A ruler with a protractor readout that ink snaps to

**Objects**
- Lines, arrows, rectangles, ellipses, and tables with editable cells
- Text boxes with font, size, colour, alignment, wrapping, background and border
- A maths symbol palette, about 120 characters, placed as real text
- Stamps: axes, number line, grid, unit circle, triangle, brace, music staff and more
- Region capture: box a figure in the textbook and drop it in as a movable object
- Marquee and tap selection; move, resize, rotate; cut, copy and paste between documents

**Reading**
- Vertical, horizontal, grid, two-page spread or single-page layouts
- The PDF's own table of contents, your own bookmarks, go-to-page, and reopening where you left
- Full-text search, with results arriving as the pages are scanned
- A highlighter that snaps to the document's own lines of text
- Night, sepia, greyscale and high-contrast reading
- Automatic trimming of a scanned page's margins

**Files**
- Browses your storage directly, with thumbnails that already show your ink
- Blank documents: ruled, grid, dot, graph, Cornell, music, isometric, up to whiteboard size
- Canvas documents that grow as you write past an edge, and stay ordinary PDFs while doing it
- Export the whole document or a page range; version history, with restore
- Opens documents sent from Gmail, Canvas or Drive

---

## Where your handwriting is kept

**Inside the document.** Saving writes the marks into the PDF itself, as an attachment the app
reads back. That is what makes a file carry its annotations between devices with nothing else to
copy across, and what lets the same marks still be edited months later.

While a document is open the marks are *also* written to a scratch copy in the app's own storage,
every twenty seconds. The document itself is only written when you save, so that scratch copy is
what stands between a crash and a lost afternoon, and it is folded back in when you open the
document again.

When you save you choose what the marks become:

- **Editable annotations** — each object becomes a real PDF annotation. Still selectable in
  Acrobat, and drawn correctly in Chrome, Canvas and anything else.
- **Flattened** — drawn into the page itself. Permanent, and the safer option for a submission
  portal that strips annotations out.

That is a setting, and any single file can be given its own rules that override it.

### Not losing work

Overwriting the original is the one thing here that can destroy something the app did not create,
so:

- Backups are on by default. The original is copied into `.inkslate-backups/` beside it first,
  keeping the last twenty, which doubles as the version history you can restore from.
- **If the backup fails, the overwrite is refused** rather than going ahead quietly.
- Every write goes to a temporary file that is then renamed, so a crash mid-save cannot leave a
  half-written file where the assignment used to be.
- A copy is never given a name that is already taken.

---

## Using it on two devices

Put your documents in a folder that syncs — Syncthing, or anything else that copies files about —
and edit on either device.

Marks made on different devices are **merged**, not fought over. Each installation labels its own
marks, so two devices working offline cannot produce marks that collide. Rubbing something out is
remembered as a deletion rather than as an absence, so a sync from a device that had not seen it
cannot bring the mark back. If an object was changed on both sides, the newer edit wins. The
result does not depend on which device syncs first.

When a sync tool cannot reconcile a file it usually renames one copy to something like
`homework.sync-conflict-20260911.pdf`. Those are found and merged in when the document is opened,
because left alone they are lost work that nothing tells you about.

---

## Windows and Android

The same four screens and the same tools, with the differences a mouse and a keyboard make:

| | |
|---|---|
| The pointer | Drawing with pen, stylus, touch or mouse. Middle-mouse drag pans. The wheel zooms; hold Shift or Ctrl while scrolling to pan sideways or up and down. |
| Item actions | Reached by right-click rather than a long press. Same actions. |
| Pressure | A mouse has none, and desktop pens report it inconsistently, so width falls back to speed there — the same fallback the tablet uses for a finger. |
| Sharing | "Share" shows the saved file in Explorer, since Windows has no share sheet for a program like this to raise. |
| Text in exports | Written with the standard PDF fonts, so a character outside their range — a maths symbol pasted in, say — exports as a placeholder rather than failing the whole export. |

**Keyboard, on Windows:** Ctrl+S saves, Ctrl+Z and Ctrl+Y (or Ctrl+Shift+Z) undo and redo,
Ctrl+C/X/V and Delete act on the selection, Ctrl+A selects the page, Ctrl+= / Ctrl+- / Ctrl+0
zoom, Escape steps back, and Ctrl+W closes the document.

---

## If something goes wrong

**Settings → Diagnostics** holds an activity log of opens, saves, exports and merges, the frame
times and memory the drawing surface is using, and any crash reports. On Windows those are also
written to `%LOCALAPPDATA%\InkSlate`; on Android, to the app's folder under
`Android/data/com.inkslate/files`.

If a document opens without the handwriting you remember, do not save over it yet. Windows folds
the scratch copy back in by itself when it finds one; on Android, **Missing handwriting?** in the
editor's menu offers it back.

---

## Not there yet

- **Word documents.** Only PDFs and images open.
- **Scanned pages have no text layer**, so search and the snap-to-text highlighter do nothing on
  them until text recognition is added.
- Audio notes.
