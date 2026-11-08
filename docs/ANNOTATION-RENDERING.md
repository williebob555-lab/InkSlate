# InkSlate Android rendered annotation chunks

The first spatial-index build removed off-screen path construction. This follow-up
addresses zoomed-out views where most strokes are visible.

- Original strokes remain the only editable and exportable data. Tiles are disposable.
- A per-view bitmap cache holds completed ink in 512-point world-space chunks at
  power-of-two resolutions. Visible resolution is reduced if necessary to keep the
  working set within 70% of a heap-dependent 4–24 MiB budget. Remaining space retains
  recently visited areas and zoom levels. The 2,400-entry geometry cache is unchanged.
- One low-priority worker prepares at most three outstanding chunks. It has its own
  renderer and does not retain geometry or touch the live pen renderer's mutable paints.
- Missing chunks near pen/hover position are prepared first. Existing tiles transform
  immediately during camera movement; resolution replacement waits until zoom settles.
  Live strokes and editing overlays are drawn immediately above the completed layer.
- Chunk eligibility compares ordered immutable stroke references and reading treatment,
  not just timestamps. A changed chunk falls back to current vectors while rebuilding;
  stale worker output is never displayed for a different set of strokes. Unaffected
  chunks survive model changes.
- Every chunk rasterizes whole intersecting strokes, with a pixel gutter and conservative
  spatial bounds. Display clipping assigns each core region to exactly one tile.
  Cross-boundary strokes remain single objects. Partial erasing uses the spatial index
  across the entire eraser swath; group selection and undo still operate on original IDs.
- Ordered source-over and multiply runs are rendered separately so highlighters and
  pencil preserve interaction with paper and earlier ink. Images (which can acquire
  pixels without a model edit), more than eight alternating blend runs, Android below
  API 29, very large views, and unavailable raster allocations use direct vector drawing.
- Evicted images are released to GC rather than recycled while a render thread could
  still reference them. Detaching a view releases its cache and stops queued work.

Native graphics tests cover blended cross-boundary strokes, bounded antialiasing changes,
seam continuity, camera reuse without geometry replay, stale-tile rejection, deletion,
partial erasing across an edge, and large-selection delete/undo/redo. Device acceptance:
Exam Review at whole-canvas zoom, fast panning, pinch zoom, long strokes, highlighter,
pencil, reading modes, erase/move/rotate across chunks, and reopening the document.
Cold unexplored areas may initially draw vectors until their tiles are ready. This
implementation targets Android; desktop continues to use the spatial-index renderer.
