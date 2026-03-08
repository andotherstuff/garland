# Garland v0.0.2-alpha

Second alpha release of the Garland Android MVP.

## Included in this release

- Rust Nostr handling now uses `rust-nostr` for NIP-06 identity derivation and event signing
- Android release workflow now supports reproducible signed APK builds through `automation/release_alpha.sh`
- release signing material is generated locally outside the repo and loaded through `GARLAND_RELEASE_PROPERTIES`
- signed release builds can now be verified with the Android SDK `apksigner` tool before publishing
- repo docs now track `v0.0.2-alpha` as the active alpha target

## Repo-side verification

- `automation/verify_alpha_no_device.sh`
- `cargo test`
- `./gradlew testDebugUnitTest`
- `./gradlew jacocoDebugUnitTestReport`
- `python3 automation/report_android_unit_coverage.py`
- `./gradlew compileDebugAndroidTestKotlin`
- `./gradlew assembleDebug`
- `./gradlew assembleRelease`
- `./gradlew lintDebug`

## Release workflow

- run `automation/release_alpha.sh v0.0.2-alpha` from a fresh clean worktree aligned with latest `origin/main`
- the script rebuilds JNI libraries, runs the no-device alpha verification path, builds a signed release APK, verifies the APK signature, and creates or updates the GitHub prerelease with attached artifacts

## Deferred validation accepted for this cut

- `v0.0.2-alpha` ships on the clean no-device verification path without requiring a pre-publish `adb` target
- connected-device instrumentation and manual picker/provider/diagnostics checks are deferred until after publish
- post-release follow-through still needs to capture real-device evidence and feed any fallout back into `docs/RELEASE_TODO.md`

## Deferred post-release follow-through

- `./gradlew connectedDebugAndroidTest` still needs an emulator or device
- manual device checks in `docs/ALPHA_RELEASE_CHECKLIST.md` are still open and are not pre-publish blockers for this cut

## Tag

- `v0.0.2-alpha`
