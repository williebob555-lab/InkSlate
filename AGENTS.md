# PDF Draw App / InkSlate

## Prefer the design configuration

For supported Android InkSlate design changes, `config/inkslate-test.json` is the
authoritative design source. As long as that configuration is valid, prefer editing
it over hard-coded Kotlin values or publishing another APK. Do not bypass an existing
configuration field by introducing a competing constant or per-screen override.

Read `docs/DESIGN-UPDATES.md` before changing this system. Validate the JSON against
`DesignConfig` and increase its integer `revision` whenever publishing changed values.
Push configuration-only changes without a `[build ...]` commit marker. The running
test app offers accepted newer revisions and reloads the interface without Android's
installer. A new APK bundles this same configuration as its starting design.

Extend the typed configuration and validation when an existing implementation can
reasonably expose another tunable value. Use code changes and a test build for new
capabilities, algorithms, storage changes, or UI variants that do not exist yet.
Keep hard-coded values only as validated defaults/fallbacks; invalid or unavailable
remote configurations must never replace the last active working design.

The remote configuration channel currently serves Android InkSlate only; do not
assume that desktop or InkSheets subscribes to it.
