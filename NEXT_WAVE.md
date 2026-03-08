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
- debug build passes with `./gradlew assembleDebug` on explicit build-validation runs
- Android lint passes with `./gradlew lintDebug`
- no-device alpha verification now has a repeatable path with `automation/verify_alpha_no_device.sh` for release/sign-off runs
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
    - publish GitHub test releases and use those artifacts for real-device smoke testing

2. Provider and file handling polish
    - exercise thumbnail behavior and provider contracts from published test releases
    - verify wildcard and non-image MIME handling in a real picker flow from a published test release

3. Diagnostics UX
    - collect tester feedback on whether the new copyable report and recent-history view are enough in published test releases

4. Packaging and manifest validation
    - verify the hardened malformed-plan cases against published test releases and worker flows

## Next Wave Priorities

1. Local fake network harness
    - fake Blossom upload/download endpoints are covered in JVM and Android test sources
    - fake relay acceptance, rejection, timeout, and malformed endpoint cases are available through the harness
    - keep the harness aligned with any new worker or diagnostics coverage

2. GitHub test-release verification pass
    - publish a test release from GitHub
    - execute provider, worker, and diagnostics smoke tests from the published APK before ship sign-off

3. Diagnostics screen follow-through
    - keep the dedicated diagnostics view aligned with tester feedback
    - validate whether the new recent-history and copy-report path is enough for alpha sign-off

4. Transfer resilience follow-through
    - validate authenticated Blossom uploads against real servers that require `kind 24242` auth
    - verify persisted retrieval URLs on real servers and confirm no fallback mismatch cases remain
    - surface clearer distinctions between unauthorized, missing-share, malformed-response, and corrupted-share failures

5. Provider polish
    - confirm wildcard and non-image MIME handling on a real device using a published test release
    - verify tree/document contract edges on real devices using published test releases

## Suggested Build Order

1. Local fake Blossom and relay harness
2. GitHub test-release validation runs
3. Alpha release checklist
4. Diagnostics screen and tester polish
5. Manifest validation hardening

## Verification policy

- During normal development, prefer the smallest correct verification command and avoid building the full app by default.
- Reach for `./gradlew assembleDebug` or `automation/verify_alpha_no_device.sh` only when the work affects packaging, build plumbing, JNI integration, manifest/resources that need APK validation, or release readiness.

## Todo

- [x] Design multi-block manifest contract
- [x] Add Rust tests for multi-block write/recover
- [x] Expose multi-block JNI APIs
- [x] Update Android executors for block iteration
- [x] Add WorkManager-based pending sync
- [x] Add instrumentation tests for the provider flow
- [x] Add per-document diagnostics UI
- [x] Prevent duplicate sync/restore jobs and classify permanent worker failures
- [ ] Publish a GitHub test release and smoke-test it on a real Android device
- [x] Add a local fake Blossom/relay harness for end-to-end verification
- [x] Add a dedicated diagnostics screen for alpha testers
- [x] Write an alpha release checklist
- [x] Harden upload/download retry handling and operator diagnostics without UI churn
- [x] Align Garland block framing, random padding, and commit/file key primitives closer to v0.1
- [x] Align storage identity derivation and master-key hierarchy closer to v0.1
- [x] Align metadata encryption and per-blob auth helpers with the v0.1 key hierarchy
- [ ] Replace the MVP manifest/share model with spec-style share descriptors and erasure metadata

## Clean Next Steps

1. Stand up the published-release validation path
    - publish a GitHub test release from the latest verified commit
    - install that APK on a real Android device
    - capture failures as either product bugs or release-process gaps

2. Extend diagnostics follow-through
    - keep the current summary in `MainActivity`
    - collect real-device feedback from published test releases on the new dedicated history and copy-report flow

3. Align the remaining protocol foundations
    - align metadata encryption and per-blob auth helpers with the v0.1 key hierarchy
    - keep verification tight with Rust and Android unit suites after each protocol step

4. Validate live transport interop
    - test authenticated Blossom upload against at least one auth-required server
    - confirm server-returned retrieval URLs restore correctly end-to-end
    - capture any server-specific auth or retrieval quirks for operator docs

5. Harden provider and manifest edges
    - verify wildcard MIME handling beyond image thumbnails on a real device via a published test release
    - verify malformed or incomplete multi-block manifests fail cleanly on a real device via a published test release

6. Work the alpha release checklist
    - run `automation/verify_alpha_no_device.sh`
    - capture `./gradlew jacocoDebugUnitTestReport`
    - capture `python3 automation/report_android_unit_coverage.py`
    - finish the GitHub test-release and manual sign-off items in `docs/ALPHA_RELEASE_CHECKLIST.md`
