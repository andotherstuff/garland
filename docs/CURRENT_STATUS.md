# Garland Current Status

## Verified in this repo

- release target is `v0.0.2-alpha`
- No-device alpha verification passes with `automation/verify_alpha_no_device.sh`
- Android unit tests pass with `./gradlew testDebugUnitTest`
- Android JVM coverage report generates with `./gradlew jacocoDebugUnitTestReport`
- Android JVM coverage summary prints with `python3 automation/report_android_unit_coverage.py`
- Android instrumentation sources compile with `./gradlew compileDebugAndroidTestKotlin`
- Debug APK builds with `./gradlew assembleDebug`
- Signed release APK builds with `./gradlew assembleRelease` when Garland release signing is configured
- `automation/release_alpha.sh v0.0.2-alpha` now covers the signed alpha release path from a clean worktree aligned with `origin/main`
- Android lint passes with `./gradlew lintDebug`
- Rust core tests pass with `cargo test`

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

## Open release gates

1. Connected Android instrumentation has not been run on an emulator or device in this environment
2. Provider and diagnostics flows still need real-device validation through the Android document picker and connected tests
3. Manual alpha checks in `docs/ALPHA_RELEASE_CHECKLIST.md` are still open

## Recommended execution order

1. Stand up an Android target with `adb`
2. Run `./gradlew connectedDebugAndroidTest`
3. Run the manual picker and diagnostics checks from `docs/ALPHA_RELEASE_CHECKLIST.md`
4. Work through `docs/ALPHA_RELEASE_CHECKLIST.md`

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
- `adb devices` -> command works, but no emulator or device is currently attached
