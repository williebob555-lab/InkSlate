# sheets-ui

InkSheets' screens, as shared source rather than a Gradle module.

The Android app (its `inksheets` flavor) and the desktop app (`:sheets-desktop`) each add this
folder to their own sources and compile it against their own Compose - Jetpack Compose on the
tablet, Compose Multiplatform on the desktop. The two expose the same `androidx.compose.*` API, so
one copy of the screens serves both without a multiplatform build.

Keep to API both have: foundation, material3 and runtime, nothing platform-specific. Anything that
differs between a tablet and a laptop goes through `SheetsPlatform`.
