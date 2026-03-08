# Garland Alpha Release Checklist

This checklist freezes the command path and evidence needed for alpha sign-off.

## 1. No-device verification

- [x] Run `automation/verify_alpha_no_device.sh`
- [x] Confirm the fake Blossom/relay harness covers upload, relay publish, and restore fallback paths in JVM tests
- [x] Confirm Android instrumentation sources compile with the fake harness via `./gradlew compileDebugAndroidTestKotlin`

Evidence to capture:

- `cargo test`
- `./gradlew testDebugUnitTest`
- `./gradlew compileDebugAndroidTestKotlin`
- `./gradlew assembleDebug`

## 2. Connected-device verification

- [ ] Confirm `adb devices` shows a usable emulator or device
- [ ] Run `./gradlew connectedDebugAndroidTest`
- [ ] Record pass or failure details for provider flow, pending sync worker flow, restore worker flow, and diagnostics flow

Evidence to capture:

- `adb devices`
- `./gradlew connectedDebugAndroidTest`
- linked test report or copied failure output for any blocking case

## 3. Manual alpha checks

- [ ] Upload a small text file and confirm relay publish status reaches the diagnostics screen
- [ ] Retry a failed upload and confirm diagnostics remain readable after the status transition
- [ ] Restore a shared document through the provider path and confirm the file opens correctly
- [ ] Verify image thumbnail behavior from the Android document picker
- [ ] Verify MIME handling for at least one non-image file from the document picker

Evidence to capture:

- screenshots from the diagnostics screen
- note of the device build fingerprint
- short notes for provider open, recent, search, and delete checks

## 4. Ship gate

- [ ] All no-device verification commands pass on the release candidate commit
- [ ] Connected-device instrumentation passes or any failures are accepted and documented
- [ ] Manual alpha checks are complete
- [ ] `README.md`, `docs/CURRENT_STATUS.md`, and `NEXT_WAVE.md` match the release state
