# Garland Current Status

## Verified in this repo

- Android unit tests pass with `./gradlew test`
- No-device alpha verification now has a repeatable entry point at `automation/verify_alpha_no_device.sh`
- Debug APK builds with `./gradlew assembleDebug`
- Rust core tests pass with `cargo test`

## Current product shape

- Native Android MVP is in place for identity load, upload prep, retry, restore, and local document browsing
- `DocumentsProvider` support exists for recent items, search, path lookup, write, delete, restore-on-read, and image thumbnails
- Background sync and restore run through WorkManager with duplicate-job protection and retry classification
- Diagnostics are preserved in `MainActivity` summaries and a dedicated diagnostics screen

## Open release gates

1. Connected Android instrumentation has not been run on an emulator or device in this environment
2. Provider and manifest edge cases still need on-device validation
3. Diagnostics may still need longer per-document history once alpha testing starts

## Recommended execution order

1. Stand up an Android target with `adb`
2. Run `./gradlew connectedDebugAndroidTest`
3. Finish provider and manifest hardening
4. Expand diagnostics history if testers need it
5. Work through `docs/ALPHA_RELEASE_CHECKLIST.md`

## Evidence snapshot

- `./gradlew test` -> pass
- `./gradlew compileDebugAndroidTestKotlin` -> pass
- `./gradlew assembleDebug` -> pass
- `cargo test` -> pass
- `adb devices` -> unavailable in this CLI environment (`adb: command not found`)
