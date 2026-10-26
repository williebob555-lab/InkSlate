# Workstation redesign validation

Approved scope: responsive home navigation and quick instrument switching; adaptive, reorderable music shelf; original annotation parity; grouped settings and part editing; safe setlist imports and missing-file handling.

Tests use isolated app data and libraries. Full-workspace tests generate a valid three-page PDF, install the shipping InkSheets flavor into AppRoot, open it through the real workspace, exercise the full annotation palette, connection panel and settings, and capture each scene at all twelve UiAudit dimensions. Intentionally bounded document-tab file names may ellipsize; action-label truncation is never exempted.

Issues found during implementation and corrected:

- Compact instrument and sidebar setlist labels needed two lines.
- The annotation strip's available height must subtract the measured music shelf.
- Removing duplicate music controls required filtering both fitting and rendering, not only the fitting list.
- Connection panels must fit above the shelf and render above its chrome.
- The shelf arrangement entry point must remain available when its Devices widget is hidden.
- Short landscape screens must retain access to all advanced score tools.
- Opening the original annotation palette on a short screen now compacts the shelf to direct rehearsal controls; a single Music tools action restores the full music workspace.
- Listen remains directly available for recording-based following even when score recognition is disabled.
- Folding the shelf preserves active playback stop/pause controls.
- The document header needed a compact Save as setlist action on narrow screens.
- Setlist removal is now a menu action rather than an exposed X; Undo preserves the entry and its position.

New regression suites: HomeRedesignTest, MusicShelfRedesignTest, PanelsRedesignTest, WorkstationAppAuditTest, MissingSetlistEntryTest, PlayAlongOffTest, SharingReliabilityTest, CompanionPartFollowTest, FolderRecoveryTest, plus the ViewportTest shelf-clearance check.

Physical Bluetooth, pedals, watch and a second Android device are not attached to this test environment. Protocol/model/fake-device regression tests cover their shared behavior; actual hardware behavior is not claimed as manually verified. Android compilation and packaging validate the shared code plus Android-specific integration.

Final local validation passed in 7 min 54 s:

`./gradlew :core:test :sheets-core:test :desktop:test :sheets-desktop:test :app:testInksheetsDebugUnitTest :app:testInkslateDebugUnitTest :app:assembleInksheetsDebug --no-parallel --max-workers=2 --console=plain`

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| core | 340 | 0 | 0 |
| sheets-core | 278 | 0 | 1 |
| desktop | 164 | 0 | 9 |
| sheets-desktop | 154 | 0 | 68 |
| Android InkSheets | 63 | 0 | 0 |
| Android InkSlate | 63 | 0 | 0 |

984 tests passed; 78 were skipped by existing environment/fixture gates. The real-PDF workspace audit produced 48 screenshots (four scenes at all twelve sizes). Home, shelf and editing/settings audits also pass all twelve sizes. Both Android flavors compile, the InkSheets APK assembles, and `git diff --check` passes. Hardware exclusions above still apply.

Release destination: https://github.com/williebob555-lab/InkSlate/releases/tag/inksheets-test . The final commit requests the InkSheets-only test workflow; publication and matching installer assets are verified after pushing.
