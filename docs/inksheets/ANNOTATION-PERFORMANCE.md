# Annotation performance test build

Exam Review (PHYS161/Exam 2) contains 2,426 marks and 127,141 points on one canvas.
Android's 2,400-entry geometry LRU was also used to answer visibility queries. A full
page traversal could therefore evict and rebuild paths even for invisible marks.

This build keeps the geometry budget unchanged. A shared 256-unit spatial grid stores
lightweight conservative bounds, returns intersecting marks in original paint order,
and retains eight recent viewport query results. Cross-cell strokes are deduplicated;
very large strokes use a separate overflow list. Text remains conservatively included
because estimated text bounds need not match measured glyphs. Rebuilds follow model
changes, including undo, remote updates and selection moves. Neither viewport changes
nor live pen samples rebuild the index. Both desktop and Android use this index before
requesting geometry. Original editable data, export and highlighter ordering survive.

Next optimization, after device measurements: retained committed-ink tiles with a
separate immediate live-stroke layer. This is deferred until multiply blending, zoom
resolution, eraser invalidation and cross-tile soft-edge seams can be validated.
Increasing geometry capacity is not part of this change.

Device acceptance: write on Exam Review zoomed into one region, jump between distant
and nearby regions, undo/redo, erase across grid boundaries, move/rotate selections,
zoom out to the whole canvas, and check highlighter/text/image appearance. Compare
frame time and geometry misses in Diagnostics. A fully visible dense canvas still
requires drawing all visible paths; this build primarily removes off-screen work.
