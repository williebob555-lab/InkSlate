# InkSlate quick design updates

The Android InkSlate test build reads `config/inkslate-test.json` from `main` on this
repository. It checks on foreground/resume and every five minutes while resumed.
Settings → Updates → Design updates also has a manual check.

A valid active configuration takes precedence over hard-coded defaults. Prefer
editing this configuration for supported adjustments. The app's Design updates
section and root AGENTS.md record this rule for users and future coding sessions.

This channel is enabled for an installed `-test.` build or a device with Test builds
enabled. InkSheets and desktop do not subscribe to it.

## Publish a design change

1. Edit supported values in `config/inkslate-test.json`.
2. Increase `revision` to an integer greater than the last published design revision.
3. Write a short `summary` describing what the user is being offered.
4. Commit and push to `main` **without** a `[build ...]` marker.
5. On the tablet, accept **Apply and reload**, or use the manual check.

GitHub's raw-file CDN may take a few minutes to show a newly committed file.
The app does not request an APK or run the Android installer. Applying saves working
copies and reloads the theme and affected controls in
the existing workspace. Documents, cameras, tabs and undo history stay in memory.
Later dismisses the revision for the rest of this app session; manual checking can
offer it again. Restore this build's design removes the downloaded override.

The exact same JSON is bundled into APK assets. When a design is approved, the next
test build automatically bakes that file into its starting design. A cached revision
older than or equal to the bundled revision never overrides it. To undo an experiment
for all subscribers, publish its previous values under a **new, higher** revision.

## Supported fields

| Section | Editable design values |
| --- | --- |
| `chrome` | Tab minimum/maximum width, content inset, top gap, height, fullscreen shoulder proportion, selected tint |
| `mixer` | Visibility header height, action-row height, minimum desired fader height |
| `wheel` | Action size, main/activation/palette radii, palette-dot size, size-track start/length, color/submenu angles, palette colors |
| `scribble` | Drifting-loop recognizer enable, corridor ratio, traversal count, minimum span, travel ratio |
| `theme` | Light/dark accent and background colors |

Dimensions ending in `Dp` use density-independent pixels. Scribble `minSpan` is in
document coordinates. Colors accept `#RRGGBB` or `#AARRGGBB`. The existing personal
scribble sensitivity still applies; design tuning does not overwrite tool preferences
or layer/document contents.

Scribbling on an empty page stays ink. Erasing requires crossing editable ink on a
visible, nonzero-opacity annotation layer. Marks baked into the original PDF are not
editable ink. Long narrow loops and narrow hatch shading can be geometrically identical;
Undo restores an unintended erase.

## Validation and failure behavior

Only schema version 1 and known keys are accepted. Values must be finite and within
the bounds in `core/.../DesignConfig.kt`; dependent geometry must also fit. Files are
limited to 64 KiB. The URL is fixed to this repository's HTTPS raw-file endpoint;
redirects, downloaded code, scripts, arbitrary paths and arbitrary URLs are unsupported.
An offline or invalid response leaves the active design alone. Cached updates use
Android AtomicFile to recover interrupted writes, with the bundled design as fallback.

The workspace must be loaded safely and save working copies successfully before an
update applies. An update cannot restructure annotation storage, add actions, replace
recognition/rendering code or introduce a new UI component. Those changes need a build.

## Verification

Run `:core:test`, `:app:testInkslateDebugUnitTest`, and `:app:assembleInkslateDebug`.
The core suite validates the repository JSON; Android tests cover revision selection,
deferred updates, persistence/reset behavior, mixer height budgets and top-bar budgets.
Check the tablet in portrait/landscape and both fullscreen states before approving a
design, particularly after changing chrome geometry or recognition thresholds.
