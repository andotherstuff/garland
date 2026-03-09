# Garland

Android MVP for Garland storage with a native Android UI, Kotlin `DocumentsProvider`,
and a Rust core for Garland-specific cryptography and packaging.

This is a vibed app and it has not been audited at all. For the protocol design,
see [garland-protocol](https://github.com/andotherstuff/garland-protocol).

## Scope

This repo intentionally targets the smallest Android MVP that can:

- import or create a Garland identity from a 12-word NIP-06 mnemonic
- let the user choose Nostr relays and Blossom servers
- upload files through a Garland-compatible replication pipeline
- handle multi-block Garland files
- restore locally tracked files from Garland shares
- expose uploaded files through Android's Storage Access Framework

## Current state

Latest release: **v0.0.3-alpha** (2026-03-09)

- Custom PBKDF2+HKDF identity derivation with `garland-v1` key hierarchy
- Commit chain snapshots, directory entry reading, head resolution with fork/cycle rejection
- Encrypted commit payloads protecting sync metadata at rest
- Blossom upload auth (kind 24242) with per-share blob auth key derivation
- Upload resume, retry hardening, partial replica handling
- Background sync crash recovery
- DocumentsProvider with recent, search, delete, write, restore-on-read, thumbnails
- WorkManager sync/restore with duplicate-job protection and failure classification
- Diagnostics screen with per-document history and copyable reports
- 27 Rust core tests, full Android unit test suite passing

See [TODO.md](TODO.md) for the roadmap and current work.

## Verification

Routine:

```bash
./automation/cargo_capped.sh test     # Rust (never bare cargo test — 0 swap machine)
./gradlew testDebugUnitTest           # Android unit tests
```

Release prep only:

```bash
./gradlew assembleDebug
./gradlew lintDebug
automation/verify_alpha_no_device.sh
```

## License

MIT
