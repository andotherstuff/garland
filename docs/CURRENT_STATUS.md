# Garland Current Status

## Verified in this repo

- No-device alpha verification passes with `automation/verify_alpha_no_device.sh`
- Android unit tests pass with `./gradlew testDebugUnitTest`
- Android JVM coverage report generates with `./gradlew jacocoDebugUnitTestReport`
- Android JVM coverage summary prints with `python3 automation/report_android_unit_coverage.py`
- Android instrumentation sources compile with `./gradlew compileDebugAndroidTestKotlin`
- Debug APK builds with `./gradlew assembleDebug`
- Android lint passes with `./gradlew lintDebug`
- Rust core tests pass with `cargo test`

## Current product shape

- Native Android MVP is in place for identity load, upload prep, retry, restore, and local document browsing
- `DocumentsProvider` support exists for recent items, search, path lookup, write, delete, restore-on-read, image thumbnails, and wildcard MIME fallback naming
- Background sync and restore run through WorkManager with duplicate-job protection and retry classification
- Diagnostics are preserved in `MainActivity` summaries and a dedicated diagnostics screen with recent history and copyable reports
- Manifest validation now rejects duplicate or invalid server entries, and restore-side plan failures now store structured diagnostics

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
- `python3 automation/report_android_unit_coverage.py` -> 59% instruction, 50% branch, 56.6% line coverage
- `./gradlew compileDebugAndroidTestKotlin` -> pass
- `./gradlew assembleDebug` -> pass
- `./gradlew lintDebug` -> pass
- `cargo test` -> pass
- `adb devices` -> command works, but no emulator or device is currently attached
