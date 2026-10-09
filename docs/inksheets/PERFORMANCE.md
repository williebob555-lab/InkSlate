# InkSheets — performance-sensitive areas

The user feels every hitch at rehearsal. Rules first, then the known hot spots and how to measure.

## Rules

- **Cost must not scale with the library or with annotations** on hot paths: per row, per page, per
  turn, per scan tick. Cache per `Library.version` or index; never rebuild the library to answer one
  question. ("those were completely stupid issues… any more of those lying around?")
- Gate background work on what it actually depends on (a no-op change must not trigger a sort,
  a re-scan, or a re-read of every name).
- Measure before and after; ship the number. Small percentage wins are not answers to scaling bugs.
- Desktop UI freezes: jcmd-sample the running app's UI thread
  (`"C:/Program Files/Android/Android Studio/jbr/bin/jcmd" <pid> Thread.print`, every ~100 ms).
  Android: TurnClock logs `[turn] ... shown Nms` and `Screen froze Nms`.
- Never do network I/O on the Android main thread (it throws; JVM tests won't catch it).

## Hot paths and their guards

| Path | What went wrong before | Guard / measure |
|---|---|---|
| Song change in a set | `step()` rebuilt the whole set; markOpened bumped library version → full sort | `LibraryCostTest -Dinksheets.lib=…`; TurnClock |
| Library reads (`songs`, `song(id)`, setlists, profiles) | rebuilt from all records per call | cached per version |
| Folder scan (every ~12 s) | no-op scan re-read every name (500 ms) | pauses while importing; must be O(changes) |
| Desktop page draw | mutable skia bitmap copied each frame (17 ms vs 0.15 ms) | `PdfSource` bitmaps `.frozen()`; `FrameCostTest` |
| Page render | 244–1033 ms render vs 38–115 ms cached | rendered pages cached; next songs pre-rendered at shown size |
| Startup (desktop) | ~10 s mostly background jobs | library opens off UI thread (`openLater`); fonts prewarmed; ~4 s base |
| Music reading | phone ≤ 10 s/page | `Workers` pool (roomy ≥ 6 cores + 384 MB); `NetSpeed`; result byte-identical (ReadingBenchmark) |
| Listen follower | must stay cheap while audio runs | 8.5 ms work per s of music (`PageTurnBench`) |
| Readings on disk | 374 KB/page | 141 KB/page (outline varint deltas) |

## Machine limits

Core Ultra 7 256V (8 cores), 16 GB RAM, Intel Arc 140V (no CUDA). Tests use up to 5 GB heaps:
**at most 2 heavy Gradle jobs at once**, and never during GPU training.
