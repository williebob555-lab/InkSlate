# Workstation redesign: reliability checks

Scope: companion automatic page following, recording page-turn learning, shared-setlist import/export. Uses isolated temporary libraries, fake audio, and protocol/model tests; no writes to the real library and no physical device claims.

## Findings and changes

- RQ-01: Automatic following used a shared song ID to imply shared page numbering. The leader now publishes an optional exact part ID, announces the part actually displayed, and shared-library devices follow pages only for that part. Legacy/independent libraries compare instrument and chair, never song alone.
- RQ-02: Turning recording page turns off left manual learning enabled. Off now suppresses timing writes as well as automatic turns.
- RQ-03: Bundle imports matched a title alone. Matching now requires imported file provenance, identifying composer/arranger metadata, or an empty placeholder. Ambiguous same-title pieces remain separate and are protected from automatic merging.
- RQ-04: A repeat bundle import replaced active annotated PDFs. Differing files now receive stable content-derived edition names. The local annotated file stays first and unchanged; importing the same clean edition again is idempotent.
- RQ-05: Shared-setlist export silently omitted missing files and removed song records. Export now reports all missing items before touching any existing output package.
- RQ-06: Importing several songs' page ranges from one shared PDF reused a file-only part ID, transferring the earlier ranges to the last imported song. Ranged-part identity now includes the file, song, page range and instrument; repeat import and automatic scanning retain every range.
- RQ-07: Shared-setlist manifests omitted explicit chairs, alternate instruments and labels. Optional manifest fields now preserve these assignments and exported filenames include the chair (for example, Trombone 2). Older bundles remain readable.

## Regression coverage

- `SharingReliabilityTest`: arranger conflict, metadata-free title ambiguity, marked-copy preservation, repeat-import idempotence, missing recording/PDF failure, removed-song failure, previous package preservation.
- `CompanionPartFollowTest`: shared song/different instruments, exact unnamed scan identity, different editions on same instrument, legacy instrument/chair matching, protocol compatibility.
- `PlayAlongOffTest`: fake player browsing while Off preserves learned times; On permits learning again.
- Existing `SetlistBundleTest`: plain ZIP format/order, ordinary sharing, empty placeholders, guessed numbered ZIP import.

## Execution results

- Targeted sharing/follow-policy suites passed, then the folder recovery and existing Library tests passed.
- Full model suites: `sheets-core` 276 tests (0 failures/errors, 1 skipped), `core` 340 tests (0 failures/errors/skips).
- Existing desktop gesture/recognition/remote regression command: `MusicStripClickTest`, `OlderReadingTest`, `RemoteScreensTest`, `SheetsTouchTest` — 12 tests, 0 failures/errors/skips, 1 min 4 s. This exercised a copied real scan, old-reading migration, actual finger input, loopback remote set selection, grid editing, touchpad, count-in and macro commands.
- Screenshots from the legacy rerun were inspected: real music with expanded score tools and clean view, remote phone grid editor, and host pairing on a narrow phone. No new visual issue in those scenes.
- Integrated phone-landscape full-annotation screenshot exposed excessive crowding despite geometric audit passing. Reported to the lead, who compacted the shelf while the original full annotation palette is open; that later change requires the final integrated regression run.
- Read-only production review found that recording-based Listen became inaccessible when recognition was off (and on short landscapes). The lead restored the standalone action; `ListenAccessTest` covers recording available with recognition disabled in both phone orientations.
- Final targeted bundle suites, including shared-PDF ranges and complete part-assignment preservation, passed in 8 seconds. The final combined desktop rerun must run with core JARs stable; refreshing a dependent JAR while a desktop worker was running caused class-loading failures in that in-flight run, not a product failure.

Physical Bluetooth, pedals, watch hardware, and a second device remain outside this automated coverage.
