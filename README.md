# Garland

Android MVP for Garland storage with a native Android UI, Kotlin `DocumentsProvider`,
and a Rust core for Garland-specific cryptography and packaging.

This is a vibed app and it has not been audited at all. For the protocol design,
see [garland-protocol](https://github.com/andotherstuff/garland-protocol).

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
- transient upload and download requests now retry in-place before Garland gives up on a document
- background retry classification now stops malformed upload responses and missing-share restores from looping forever
- manifest validation now rejects duplicate or invalid server entries across upload and restore paths
- restore now prefers server-returned retrieval URLs and explains downloaded-share mismatches before crypto recovery
- provider MIME fallback naming now covers wildcard non-image creates such as `text/*` and `application/*`
- Rust core for identity derivation, multi-block write planning, and block recovery

## Verified Status

- release target is `v0.0.2-alpha`
- `automation/verify_alpha_no_device.sh` passes and freezes the repo-side alpha sign-off path for release candidates
- `automation/verify_alpha_no_device.sh` passes and freezes the repo-side alpha sign-off path for release candidates
- `./gradlew testDebugUnitTest` passes for the Android unit-test suite
- `./gradlew jacocoDebugUnitTestReport` generates the Android JVM coverage report
- `python3 automation/report_android_unit_coverage.py` prints the current Android JVM coverage summary
- `./gradlew compileDebugAndroidTestKotlin` passes for the Android instrumentation source compile gate
- `./gradlew assembleDebug` builds the debug APK successfully when we do an explicit release/build validation pass
- `./gradlew assembleRelease` builds the signed release APK when Garland release signing is configured
- `automation/release_alpha.sh v0.0.2-alpha` rebuilds JNI libs, verifies the repo, and publishes the signed alpha release
- `./gradlew lintDebug` passes for the Android static quality gate
- `cargo test` passes for the Rust core
- GitHub test-release validation and manual device smoke testing after publish remain the only open alpha-release gates

## Verification Policy

- For routine development, prefer the smallest correct verification command instead of building the whole app.
- Default to targeted checks like `./gradlew testDebugUnitTest`, focused Gradle test targets, `./gradlew compileDebugAndroidTestKotlin`, and `cargo test`.
- Avoid running `./gradlew assembleDebug` on every change; Garland app builds can drag the server to a crawl.
- Save `./gradlew assembleDebug` and `automation/verify_alpha_no_device.sh` for release prep, packaging/build-system changes, JNI integration validation, manifest/resource changes that need APK verification, or suspected build-only regressions.

## Alpha Release Gaps

- publish a GitHub test release and smoke-test it on a real Android device
- finish the release sign-off items in `docs/ALPHA_RELEASE_CHECKLIST.md`

## License

MIT
