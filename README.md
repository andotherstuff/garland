# Garland

Android MVP for Garland storage with a native Android UI, Kotlin `DocumentsProvider`,
and a Rust core for Garland-specific cryptography and packaging.

## Scope

This repo intentionally targets the smallest Android MVP that can:

- import or create a Garland identity from a 12-word NIP-06 mnemonic
- let the user choose Nostr relays and Blossom servers
- upload files through a Garland-compatible replication pipeline
- handle multi-block Garland files
- restore locally tracked files from Garland shares
- expose uploaded files through Android's Storage Access Framework

## Current MVP

- native Android screen for identity, upload prep, upload retry, remote restore, and local document selection
- dedicated diagnostics screen for tester-facing per-document upload and relay triage
- multi-block upload planning and multi-block restore support
- local Garland document store with upload status tracking
- `DocumentsProvider` integration with recent document, search, path lookup, write, delete, restore-on-read, and image thumbnail support
- WorkManager-backed background sync and restore with duplicate-job protection and retry classification for permanent vs transient failures
- per-document upload and relay diagnostics preserved across queued and running status transitions
- Rust core for identity derivation, multi-block write planning, and block recovery

## Verified Status

- `./gradlew test` passes for the Android unit-test suite
- `automation/verify_alpha_no_device.sh` now runs the repeatable no-device alpha verification path
- `./gradlew assembleDebug` builds the debug APK successfully
- `cargo test` passes for the Rust core
- connected Android instrumentation is still not verified in-repo and remains an alpha-release gate

## Alpha Release Gaps

- run the Android instrumentation suite on a connected emulator or device as part of release verification
- expand diagnostics beyond the current latest-result view if alpha testing needs longer per-document history
- finish the connected-device and manual sign-off items in `docs/ALPHA_RELEASE_CHECKLIST.md`

## License

MIT
