# Selection and stamp actions

The contextual bar stays above the drawing tools. Android and desktop compile the same
`library-ui/.../editor/ContextActionBar.kt`, so InkSlate and InkSheets share its visual behavior.
Android's selection bar spans the complete toolbar width, above the pen controls and layer mixer.
It does not compete with the mixer for horizontal space. Its height is included in the toolbar's
existing 55% screen budget.

## Layout

- A small Selected / Place repeatedly label and an object name establish the current context.
  A graph made from many strokes is reported as one object, not dozens of selections.
- Duplicate, Delete, and Done remain in a fixed action lane. On narrow screens the first two
  use a labeled vertical layout; labels are not abbreviated.
- At widths below 760 dp, properties occupy a second, horizontally scrollable lane. At wider
  widths they share the first row. Individual controls retain their touch targets.
- Properties use quiet surface-colored rounded controls; the Done action has a distinct tonal
  background. Delete uses the error color, without turning the whole bar into a warning.
- Edit settings / Edit text and Crop appear only when applicable. Copy, Cut, and picture export
  live in More. Picture selections have no ink color or line-weight controls.
- The shapes chooser steps aside while a selection exists. Choosing a stamp replaces the chooser
  with its appearance controls and an explicit repeated-placement state. Shapes reopens the
  chooser; they do not stack into several contextual bars.

## Editing behavior

Selection color and weight come from the objects, independently of the drawing pen. Mixed values
are identified explicitly. Color offers the standard palette and a six- or eight-digit hex value.
Weight uses the existing shared width ladder; one slider release commits one undoable change.
These edits and Copy leave Select once active until Done or the next outside pen contact.

Whole stamps are rebuilt from updated options, preserving the distinction between their primary
lines, fine lines, and labels. Their stored options remain consistent with the result. Rebuilding
a stamp preserves layer ownership. Handwriting weight changes preserve pressure variation.

On Android, Layer offers separate Move and Copy actions. Copy creates independent IDs and stamp
groups at the same position; the result stays selected. Move transfers only the channel being
edited. Legacy shared annotations retain their untouched channel memberships. Transfer reveals the
destination, restores a zero destination fader, and preserves the global recording channel and all
other visibility/fader settings. Filter targeting continues to apply normally to new pen input.
Desktop does not yet have the Android layer mixer, so its bar does not offer layer transfer.

## Verification

- Core tests: object summaries, mixed properties, pressure preservation, stamp settings and layer
  preservation, legacy shared-channel transfer.
- Android integration: selected appearance, pen independence, selection continuity, exact undo/redo,
  independent layer copies and shared-channel moves.
- Desktop Compose: contextual property actions, distinguish Copy from Move, fixed action geometry,
  and actual toolbar renders for handwriting/stamps, pictures, and mixed objects at phone, tablet,
  and desktop widths in light and dark themes, including increased text size.
- Images are generated in `build/context-bar-review/` under the test working directory for visual inspection. Pixel output
  must be inspected as well as the geometry assertions; compilation alone is not visual approval.

These changes require application builds. The remote Android design channel cannot add the new
component or edit operations. Existing wheel, mixer, and theme configuration remains authoritative.

## Local verification result

Verified 2026-11-14: 79 related core tests, 27 Android integration/layout tests, and 4 desktop
Compose tests passed (110 total). The render test produced 104 scenes: four contexts, 13 screen
sizes (the twelve InkSheets audit sizes plus a 320 dp phone), and both actual app themes.
Representative phone, enlarged-text tablet, landscape, and wide layouts were visually inspected.
Fixed action labels are checked against their visible line extents, allowing only one pixel of
fractional text-layout rounding; actual clipping and ellipsis fail the check.

Both desktop apps and both Android flavors compiled, and `:app:assembleInkslateDebug` succeeded.
Gradle's forked test workers cannot connect over loopback in this environment, so the compiled
tests were run directly through JUnit with the exported runtime classpaths and isolated test data.
No physical-tablet smoke test or test-channel publication was performed.
