# Garland Next Wave

This file tracks the next integration wave after the current Garland MVP.

## Now Shipped

- identity import from a 12-word seed
- manual write prep, upload, relay publish, retry, and bulk pending sync
- multi-block write planning and multi-block restore
- local document browser in the app
- remote restore from Garland shares
- provider-backed recent, search, delete, write, and restore-on-read behavior

## Next Wave Priorities

1. Multi-block files
   - split content into multiple Garland blocks
   - upload and restore every block
   - add manifest validation for block ordering and completeness

2. Relay and Blossom health
   - show per-relay and per-server success state in the UI
   - persist last failure reason per document
   - expose a relay/server diagnostics screen

3. Background execution
   - move sync and restore work into WorkManager jobs
   - survive app restarts during upload or restore
   - add retry backoff rules

4. Rich provider integration
   - open document metadata from search results
   - refresh provider views when sync state changes
   - add better MIME-aware document handling

5. End-to-end Android verification
   - add instrumentation coverage for document create, write, sync, restore, and read
   - run against a local fake Blossom/relay test harness

## Suggested Build Order

1. Multi-block Rust core and JNI bridge
2. Kotlin upload/download executors for multi-block manifests
3. Background sync jobs
4. Instrumentation test harness
5. Diagnostics and polish

## Todo

- [x] Design multi-block manifest contract
- [x] Add Rust tests for multi-block write/recover
- [x] Expose multi-block JNI APIs
- [x] Update Android executors for block iteration
- [x] Add WorkManager-based pending sync
- [ ] Add instrumentation tests for the provider flow
- [ ] Add per-document diagnostics UI
