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

## Alpha Blockers

1. End-to-end Android verification
   - run connected instrumentation for provider flow and diagnostics flow on an emulator or device
   - add a repeatable local fake Blossom/relay harness for upload, sync, restore, and retry coverage

2. Diagnostics UX
   - move inline document diagnostics into a dedicated diagnostics view or screen
   - make per-server and per-relay failures easier for testers to scan and export

3. Provider and file handling polish
   - exercise thumbnail behavior and provider contracts on-device
   - add better MIME-aware handling beyond the current image-thumbnail path

4. Packaging and manifest validation
   - add stronger manifest validation for block ordering and completeness
   - verify malformed plans fail cleanly across upload and restore paths

## Next Wave Priorities

1. Local fake network harness
   - fake Blossom upload/download endpoints
   - fake relay acceptance, rejection, timeout, and malformed endpoint cases
   - wire the harness into Android instrumentation

2. Diagnostics screen
   - promote the current inline diagnostics into a full tester-facing inspection flow
   - include per-document history for upload, relay publish, sync, and restore

3. Provider polish
   - broaden MIME-aware behavior beyond images
   - verify tree/document contract edges on real devices

## Suggested Build Order

1. Local fake Blossom and relay harness
2. Connected-device instrumentation runs
3. Diagnostics screen and tester polish
4. Manifest validation hardening
5. Alpha release checklist

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
- [ ] Add a local fake Blossom/relay harness for end-to-end verification
- [ ] Add a dedicated diagnostics screen for alpha testers
