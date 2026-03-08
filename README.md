# Garland

Android MVP for Garland storage with a native Android UI, Kotlin `DocumentsProvider`,
and a Rust core for Garland-specific cryptography and packaging.

## Scope

This repo intentionally targets the smallest Android MVP that can:

- import or create a Garland identity from a 12-word NIP-06 mnemonic
- let the user choose Nostr relays and Blossom servers
- upload files through a Garland-compatible replication pipeline
- handle multi-block Garland files
- restore locally tracked files from Garland shares
- expose uploaded files through Android's Storage Access Framework

## Current MVP

- native Android screen for identity, upload prep, upload retry, remote restore, and local document selection
- dedicated diagnostics screen for tester-facing per-document upload and relay triage
- multi-block upload planning and multi-block restore support
- local Garland document store with upload status tracking
- `DocumentsProvider` integration with recent document, search, path lookup, write, delete, restore-on-read, and image thumbnail support
- Blossom uploads now sign kind `24242` authorization events with the loaded identity when a server requires auth
- WorkManager-backed background sync and restore with duplicate-job protection and retry classification for permanent vs transient failures
- restore jobs read the loaded identity from session state instead of persisting the private key in WorkManager input
- per-document upload and relay diagnostics preserved across queued and running status transitions
- dedicated diagnostics reports now include recent per-document history and a copyable tester report
- manifest validation now rejects duplicate or invalid server entries across upload and restore paths
- restore now prefers server-returned retrieval URLs and explains downloaded-share mismatches before crypto recovery
- provider MIME fallback naming now covers wildcard non-image creates such as `text/*` and `application/*`
- Rust core for identity derivation, multi-block write planning, and block recovery

## Verified Status

- release target is `v0.0.1-alpha`
- `automation/verify_alpha_no_device.sh` passes and freezes the repo-side alpha sign-off path
- `./gradlew testDebugUnitTest` passes for the Android unit-test suite
- `./gradlew jacocoDebugUnitTestReport` generates the Android JVM coverage report
- `python3 automation/report_android_unit_coverage.py` prints the current Android JVM coverage summary
- `./gradlew compileDebugAndroidTestKotlin` passes for the Android instrumentation source compile gate
- `./gradlew assembleDebug` builds the debug APK successfully
- `./gradlew lintDebug` passes for the Android static quality gate
- `cargo test` passes for the Rust core
- connected Android instrumentation and manual device checks remain the only open alpha-release gates

## Alpha Release Gaps

- run `./gradlew connectedDebugAndroidTest` on a connected emulator or device
- finish the manual sign-off items in `docs/ALPHA_RELEASE_CHECKLIST.md`

## License

MIT
