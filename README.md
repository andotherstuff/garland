# Garland

Android MVP for Garland storage with a native Android UI, Kotlin `DocumentsProvider`,
and a Rust core for Garland-specific cryptography and packaging.

## Scope

This repo intentionally targets the smallest Android MVP that can:

- import or create a Garland identity from a 12-word NIP-06 mnemonic
- let the user choose Nostr relays and Blossom servers
- upload files through a Garland-compatible replication pipeline
- restore locally tracked files from Garland shares
- expose uploaded files through Android's Storage Access Framework

## Current MVP

- native Android screen for identity, upload prep, upload retry, remote restore, and local document selection
- local Garland document store with upload status tracking
- `DocumentsProvider` integration with recent document and search support
- Rust core for identity derivation, single-block write planning, and single-block recovery

## License

MIT
