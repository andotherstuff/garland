# Garland Next Wave

This file tracks the next integration wave after the current Garland MVP.

## Now Shipped

- identity import from a 12-word seed
- manual write prep, upload, relay publish, retry, and bulk pending sync
- multi-block write planning and multi-block restore
- local document browser in the app
- remote restore from Garland shares
- provider-backed recent, search, delete, write, and restore-on-read behavior
- provider path lookup, root capability flags, stricter invalid-request handling, and image thumbnail support
- WorkManager-backed sync and restore with unique work lanes and permanent-vs-transient retry rules
- preserved upload/relay diagnostics across queued, running, restore, and retry status changes
- transient upload and download requests now retry before Garland records a document-level failure
- missing-share restores and malformed upload descriptors now stop background retry loops with clearer operator-facing messages

## Current Status

- Android unit tests pass with `./gradlew testDebugUnitTest`
- Android JVM coverage report generates with `./gradlew jacocoDebugUnitTestReport`
- Android JVM coverage summary prints with `python3 automation/report_android_unit_coverage.py`
- Rust core tests pass with `cargo test`
- Android instrumentation sources compile with `./gradlew compileDebugAndroidTestKotlin`
- debug build passes with `./gradlew assembleDebug`
- Android lint passes with `./gradlew lintDebug`
- no-device alpha verification now has a repeatable path with `automation/verify_alpha_no_device.sh`
- fake Blossom/relay harness coverage now exists in JVM tests and Android test sources
- connected instrumentation and manual device checks are still pending because they have not been run on an emulator or device
- `MainActivity` keeps a compact diagnostics summary and now opens a dedicated diagnostics screen for fuller per-document triage
- the diagnostics screen now keeps recent per-document sync history and can copy a tester-facing report
- manifest validation now rejects duplicate or invalid server entries and restore-side plan failures now surface structured diagnostics
- provider MIME fallback naming now covers wildcard non-image creates such as `text/*` and `application/*`
- configured relays and Blossom servers are now trimmed, deduplicated, and defaulted before upload or relay work starts
- upload retry follow-through now preserves retryable network failures for the next worker pass without touching the diagnostics UI surface

## Alpha Blockers

1. End-to-end Android verification
    - run connected instrumentation for provider flow, worker flow, and diagnostics flow on an emulator or device

2. Provider and file handling polish
    - exercise thumbnail behavior and provider contracts on-device
    - verify wildcard and non-image MIME handling on a real picker flow

3. Diagnostics UX
    - collect tester feedback on whether the new copyable report and recent-history view are enough on-device

4. Packaging and manifest validation
    - verify the hardened malformed-plan cases against real device and worker flows

## Next Wave Priorities

1. Local fake network harness
    - fake Blossom upload/download endpoints are covered in JVM and Android test sources
    - fake relay acceptance, rejection, timeout, and malformed endpoint cases are available through the harness
    - keep the harness aligned with any new worker or diagnostics coverage

2. Connected-device verification pass
    - bring up an emulator or device path that can run `connectedDebugAndroidTest`
    - execute provider, worker, and diagnostics instrumentation against the current MVP before ship sign-off

3. Diagnostics screen follow-through
    - keep the dedicated diagnostics view aligned with tester feedback
    - validate whether the new recent-history and copy-report path is enough for alpha sign-off

4. Provider polish
    - confirm wildcard and non-image MIME handling on a real device
    - verify tree/document contract edges on real devices

## Suggested Build Order

1. Local fake Blossom and relay harness
2. Connected-device instrumentation runs
3. Alpha release checklist
4. Diagnostics screen and tester polish
5. Manifest validation hardening

## Todo

- [x] Design multi-block manifest contract
- [x] Add Rust tests for multi-block write/recover
- [x] Expose multi-block JNI APIs
- [x] Update Android executors for block iteration
- [x] Add WorkManager-based pending sync
- [x] Add instrumentation tests for the provider flow
- [x] Add per-document diagnostics UI
- [x] Prevent duplicate sync/restore jobs and classify permanent worker failures
- [ ] Run connected Android instrumentation on an emulator or device
- [x] Add a local fake Blossom/relay harness for end-to-end verification
- [x] Add a dedicated diagnostics screen for alpha testers
- [x] Write an alpha release checklist
- [x] Harden upload/download retry handling and operator diagnostics without UI churn

## Clean Next Steps

1. Get a repeatable Android test target running
    - install or connect an emulator/device path that exposes `adb`
    - run `./gradlew connectedDebugAndroidTest`
    - capture failures as either harness gaps or product bugs

2. Extend diagnostics follow-through
    - keep the current summary in `MainActivity`
    - collect on-device feedback on the new dedicated history and copy-report flow

3. Harden provider and manifest edges
    - verify wildcard MIME handling beyond image thumbnails on-device
    - verify malformed or incomplete multi-block manifests fail cleanly on-device

4. Work the alpha release checklist
    - run `automation/verify_alpha_no_device.sh`
    - capture `./gradlew jacocoDebugUnitTestReport`
    - capture `python3 automation/report_android_unit_coverage.py`
    - finish the connected-device and manual sign-off items in `docs/ALPHA_RELEASE_CHECKLIST.md`
