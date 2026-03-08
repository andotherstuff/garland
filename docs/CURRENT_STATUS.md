# Garland Current Status

## Verified in this repo

- release target is `v0.0.2-alpha`
- No-device alpha verification passes with `automation/verify_alpha_no_device.sh` on release/sign-off passes
- Android unit tests pass with `./gradlew testDebugUnitTest`
- Android JVM coverage report generates with `./gradlew jacocoDebugUnitTestReport`
- Android JVM coverage summary prints with `python3 automation/report_android_unit_coverage.py`
- Android instrumentation sources compile with `./gradlew compileDebugAndroidTestKotlin`
- Debug APK builds with `./gradlew assembleDebug` when we run an explicit build validation pass
- Signed release APK builds with `./gradlew assembleRelease` when Garland release signing is configured
- `automation/release_alpha.sh v0.0.2-alpha` now covers the signed alpha release path from a clean worktree aligned with `origin/main`
- Android lint passes with `./gradlew lintDebug`
- Rust core tests pass with `cargo test`

## Verification default

- Routine iteration should use the smallest correct verification command, not a full app build by default.
- Prefer targeted JVM tests, focused Gradle test targets, Android test-source compile checks, and `cargo test`.
- Reserve `./gradlew assembleDebug` and `automation/verify_alpha_no_device.sh` for release prep, packaging/build-system work, JNI integration checks, manifest/resource validation, or suspected build-only failures.

## Current product shape

- Native Android MVP is in place for identity load, upload prep, retry, restore, and local document browsing
- `DocumentsProvider` support exists for recent items, search, path lookup, write, delete, restore-on-read, image thumbnails, and wildcard MIME fallback naming
- Background sync and restore run through WorkManager with duplicate-job protection and retry classification
- Restore jobs resolve identity from session state instead of storing the private key in WorkManager payloads
- Diagnostics are preserved in `MainActivity` summaries and a dedicated diagnostics screen with recent history and copyable reports
- Uploads now retry transient HTTP and network failures before recording a per-document failure
- Restores now retry transient download failures, count actual request attempts, and stop auto-retrying when shares are clearly missing or malformed
- Blossom uploads now sign user-key kind `24242` auth events when a server requires upload authorization
- Manifest validation now rejects duplicate or invalid server entries, and restore-side plan failures now store structured diagnostics
- Restore prefers server-returned retrieval URLs and now explains fetched-share mismatches before entering crypto recovery

## Deferred post-release validation

1. Local emulator/device instrumentation is intentionally not part of this VPS workflow and is not a pre-publish blocker for `v0.0.2-alpha`
2. Provider and diagnostics flows still need real-device validation through published GitHub test releases and the Android document picker
3. Release smoke-testing and manual alpha checks in `docs/ALPHA_RELEASE_CHECKLIST.md` remain open follow-through work after publish

## Recommended execution order

1. Run the final signed release from a fresh clean worktree aligned with latest `origin/main`
2. Publish the GitHub prerelease from that verified release worktree
3. Install the published build on a real Android device and run smoke tests from `docs/ALPHA_RELEASE_CHECKLIST.md`
4. Capture manual picker and diagnostics evidence from the published build and feed results back into `docs/RELEASE_TODO.md`

## Evidence snapshot

- `automation/verify_alpha_no_device.sh` -> pass
- `./gradlew testDebugUnitTest` -> pass
- `./gradlew jacocoDebugUnitTestReport` -> pass
- `python3 automation/report_android_unit_coverage.py` -> 59% instruction, 50% branch, 57.2% line coverage
- `./gradlew compileDebugAndroidTestKotlin` -> pass
- `./gradlew assembleDebug` -> pass
- `./gradlew assembleRelease` -> pass with local Garland signing material
- `./gradlew lintDebug` -> pass
- `cargo test` -> pass
- local emulator/device testing is intentionally out of scope on this VPS; real-device validation is deferred until after publish and happens from published GitHub test releases
