# Whiteboard and editor controls

## Infinite filters

Filter dragging, edge/corner resizing and two-finger transforms now distinguish unbounded
whiteboards from fixed pages. Infinite filters accept negative coordinates and positions/sizes
beyond the seed PDF's width and height. The existing minimum resize size remains. Fixed-page
filter constraints and local viewing/writing overrides are unchanged.

New infinite filters are centred in the current viewport and sized relative to its zoom, rather
than appearing back at the original PDF page's centre. Moving a filter does not move ink or grow
the saved canvas bounds.

## New document

Shape follows the name, before Size, Pages and paper styling. Fixed pages exposes size and page
count. Infinite whiteboard hides both, keeps a neutral paper sample, and uses a stable internal
PDF seed rather than the previous fixed-page choice. That seed places no boundary on the canvas.
Switching back to fixed pages retains the person's selected page size and count.

## Layer mixer

The shared `LayerMixerPanel` places visibility, Write and Filter vertically to the left of each
fader. Thin dividers separate the channels. No header/footer rows consume fader height. The
fader owns the entire available channel height, apart from its thumb/end margins.

Configuration revision 5 adds validated control width, minimum channel width and separator spacing.
The minimum width guarantees at least 24 dp for the fader after controls and internal spacing.
Text scaling expands both the controls and their reserved width; it never reduces fader travel.
On narrow screens the mixer takes its own row instead of squeezing or overlapping the pen controls.
`minimumPenWidthDp` tunes this breakpoint; tablets retain the side mixer.
Visibility, exclusive recording and filter actions remain independent. Faders expose progress
semantics as well as touch/pen gestures, and retain persistence when the gesture ends.

## Document menu and automatic saving

Android and desktop share a bounded `DocumentActionsMenu`. The root shows document name/save
status, Share/Export, Find/Pages, and categories for View & appearance, History and File options.
Categories replace the menu content rather than opening cascading popups. Back returns to the
root, and reopening starts at the top. Reading-style checkmarks have their own icon slot.

Persistent Save buttons and the duplicate desktop Save menu item are removed. Automatic writing
and its failure/recovery behavior remain; explicit exports, sharing, checkpoints, version history
and keyboard save handling are preserved. The Android tab budget no longer reserves the old Save
button's width.

## Verification

Core tests exercise infinite and bounded filter geometry, local layer targeting, and config
validation. Android tests exercise the real drawing view's dragging, corner resizing, two-finger
transforms, off-origin placement, existing layer erasing, selection behavior and chrome budgets.

Desktop tests cover form state/creation, menu navigation, fader geometry and endpoint interactions,
and the existing tab/split-document drawing workflow without a persistent Save control. Menu/form
layouts are rendered at all twelve workspace sizes in light and dark themes; mixer previews include
narrow and enlarged-text layouts. Images are in `build/workspace-controls-review/` under the test
working directory. Windows Java preferences are isolated with an in-memory test factory, because
`user.home` alone does not redirect their registry storage.

These changes require an app build. They have not been smoke-tested on the physical tablet.

Local verification on 2026-11-14: 81 related core tests, 37 Android tests and 6 desktop
Compose/workspace tests passed (124 total). Fixed and infinite forms and menu categories passed
all twelve workspace sizes in both themes. Both Android flavors and desktop apps compiled, and
`:app:assembleInkslateDebug` packaged successfully. The in-process JUnit runs used isolated test
storage and the in-memory Windows preferences factory. No test-channel publication was performed.