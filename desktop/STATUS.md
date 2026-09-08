# Desktop app — resumed

Runs. Opens documents, draws, saves, exports. Built and launched successfully as an uber jar.

## Working

- **`:core` sharing.** The document model, merge rules, the payload container (`InkPayload`) and
  the image carriers (`ImageInkCarrier`) are shared source with Android. A document annotated on
  the tablet opens here with its handwriting on it, because both builds read and write the same
  bytes from the same code.
- **Handwriting inside the document.** `DesktopEmbedder` mirrors the Android `InkEmbedder`: PDF
  embedded-file attachment, staged beside the original and read back before the swap. Only the
  PDF half is written twice, because the two platforms use different PDFBox builds.
- **Legacy companions.** `.inkdoc` files are still read and merged when found, but never deleted
  from here — the tablet may be mid-sync.
- **Working copy.** Autosaves to `%LOCALAPPDATA%/InkSlate/working` every 20s; the document itself
  is only written on an explicit save, same rule as Android.
- Pen, highlighter, eraser; colour and width; undo/redo; zoom; flattened PDF export.
- Ctrl+S save, Ctrl+O open, Ctrl+Z / Ctrl+Y undo and redo.
- The Android palette, copied value for value into `Theme.kt`.

## Not yet matching the Android UI

The user's stated expectation is that this looks identical to the Android app. It does not yet.
What exists is the correct *behaviour* behind a plain toolbar; what is missing is the Android
app's actual interface:

- the home screen (starred, recents, folder tiles with previews, search, breadcrumb, long press)
- the file browser
- the editor's tool palette — brushes, shapes, tables, stamps, symbols, ruler, page layouts
- navigation sheet, page search, version history, settings

### How to get there

Two honest options, with different costs:

1. **Hand-matched.** Rebuild those screens in this module against the shared theme. Fast to start,
   but the two will drift, and every future change has to be made twice.
2. **Shared UI source.** The prize is that `ToolBar.kt` (792 lines, the whole tool palette) and
   `Dialogs.kt` contain **no Android-specific code at all**. What blocks sharing them today:
   - they import `com.inkslate.ink.Tool`, `InputMode` and `ToolSnapshot`, which are pure data and
     belong in `:core`;
   - `ToolBar.kt` also imports `StrokeRasteriser`, which is `android.graphics.Canvas` and is used
     for the brush previews. That needs an interface with a renderer per platform.

   Moving those three types down and abstracting the one renderer would make the largest and most
   visible part of the editor genuinely shared, with no drift.

Option 2 is the better answer and is not far off; option 1 is what to fall back to if the
brush-preview abstraction proves awkward.

The canvas itself cannot be shared either way: Android's `DrawingView` is a custom `android.view.View`
of about 2,000 lines. Matching its *behaviour* on desktop (page layouts, culling, detail tiles,
selection, ruler) is the largest remaining piece of work in this module.

## Packaging

`packageUberJarForCurrentOS` works and is what is currently built:

    desktop/build/compose/jars/InkSlate-windows-x64-1.0.0.jar

`packageMsi` / `packageExe` need `jpackage`, which is **not** present — Android Studio's bundled
JBR ships `jlink` but not `jpackage`, and no other JDK is installed. Installing a full JDK 17+
would enable a proper Windows installer.

Run it with:

    java -jar "InkSlate-windows-x64-1.0.0.jar"
