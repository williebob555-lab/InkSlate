# library-ui

Home - the documents, the folders, moving and filing them - as shared source rather than a Gradle
module, the same arrangement as `sheets-ui`.

The Android app (`:app`, every flavor) and the desktop app (`:desktop`) each add this folder to
their own sources and compile it against their own Compose. The two expose the same
`androidx.compose.*` API, so one copy of the screen serves both and the tablet and the laptop
cannot drift apart.

Keep to API both have: foundation, material3 and runtime. Whatever differs - the files
themselves, thumbnails, the back button, scrollbars - comes in through `LibraryBackend` and
`LibraryPlatform`.
