# Garland Alpha Release Checklist

This checklist freezes the command path and evidence needed for alpha sign-off.

Release target: `v0.0.2-alpha`

## 1. No-device verification

- [x] Run `automation/verify_alpha_no_device.sh`
- [x] Confirm the fake Blossom/relay harness covers upload, relay publish, and restore fallback paths in JVM tests
- [x] Confirm `./gradlew jacocoDebugUnitTestReport` generates the Android JVM coverage report
- [x] Confirm `python3 automation/report_android_unit_coverage.py` prints the current Android JVM coverage summary
- [x] Confirm Android instrumentation sources compile with the fake harness via `./gradlew compileDebugAndroidTestKotlin`
- [x] Confirm restore-side malformed manifest failures now surface structured diagnostics in JVM tests
- [x] Confirm diagnostics reports now include recent per-document history and copyable export text in JVM tests
- [x] Confirm wildcard non-image MIME fallback naming is covered in JVM and Android test sources
- [x] Confirm `./gradlew lintDebug` passes for the Android static quality gate

Evidence to capture:

- `cargo test`
- `./gradlew testDebugUnitTest`
- `./gradlew jacocoDebugUnitTestReport`
- `python3 automation/report_android_unit_coverage.py`
- `./gradlew compileDebugAndroidTestKotlin`
- `./gradlew assembleDebug`
- `./gradlew lintDebug`

## 2. GitHub test-release verification

- [ ] Publish a GitHub test release from the candidate commit
- [ ] Install the published APK on a real Android device
- [ ] Record pass or failure details for provider flow, pending sync worker flow, restore worker flow, and diagnostics flow

Evidence to capture:

- GitHub release URL
- APK version/build identifier used for testing
- linked test report or copied failure output for any blocking case

## 3. Manual alpha checks

- [ ] Upload a small text file and confirm relay publish status reaches the diagnostics screen
- [ ] Retry a failed upload and confirm diagnostics remain readable after the status transition, including recent history
- [ ] Restore a shared document through the provider path and confirm the file opens correctly
- [ ] Verify image thumbnail behavior from the Android document picker
- [ ] Verify MIME handling for at least one wildcard or non-image file from the document picker

Evidence to capture:

- screenshots from the diagnostics screen
- copied diagnostics report text
- note of the device model / Android version used for testing
- short notes for provider open, recent, search, and delete checks

## 4. Ship gate

- [x] All no-device verification commands pass on the release candidate commit
- [ ] GitHub test-release smoke testing passes or any failures are accepted and documented
- [ ] Manual alpha checks are complete
- [ ] `README.md`, `docs/CURRENT_STATUS.md`, and `NEXT_WAVE.md` match the release state
