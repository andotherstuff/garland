# Garland v0.0.3-alpha

Third alpha release of the Garland Android MVP.

## Breaking changes

- **New key derivation hierarchy**: Identity derivation now uses a custom PBKDF2+HKDF path (`garland-v1-salt`, `garland-v1-nsec`, `garland-v1:storage-scalar`) instead of raw NIP-06. Users of v0.0.2-alpha will derive a different Nostr identity from the same mnemonic. This change enables proper key separation between signing, encryption, metadata, and blob auth operations.

## What's new

### Commit chain foundation
- Commit chain snapshots record directory state at each sync point
- Directory entry reading from snapshot uploads
- Commit chain head resolution with fork and cycle rejection
- Encrypted commit payloads protect sync metadata at rest
- Event persistence verified across upload and sync paths

### Key hierarchy and auth
- Master key derives protocol-specific subkeys: file key, commit key, metadata key, blob auth key
- Blossom upload auth events (kind 24242) signed per-upload when a private key is available
- Metadata auth aligned with v0.1 key structure
- Per-share blob auth key derivation

### Upload hardening
- Retry logic improvements with share validation
- Partial replica upload handling
- Upload resume from saved share targets
- Reduced metadata leakage in upload payloads

### Background sync
- Sync-running state transitions prevent duplicate work
- Crash recovery: documents stuck in sync-running are requeued on next wake
- Pending document ID listing for selective sync

### UI and build
- Simpler UI (PR #1)
- Capped Cargo and Gradle build parallelism to prevent system hangs on low-memory machines
- JNI .so libraries rebuilt with all 8 native bridge functions

## Repo-side verification

- `./automation/cargo_capped.sh test` — 27 Rust tests pass
- `./gradlew testDebugUnitTest` — all Android unit tests pass
- `./gradlew assembleDebug` — debug APK builds with both arm64-v8a and x86_64 .so
- `./gradlew lintDebug` — no lint errors
- `./gradlew compileDebugAndroidTestKotlin` — instrumentation tests compile

## Release workflow

- run `automation/release_alpha.sh v0.0.3-alpha` from a fresh clean worktree aligned with latest `origin/main`
- the script rebuilds JNI libraries, runs the no-device alpha verification path, builds a signed release APK, verifies the APK signature, and creates or updates the GitHub prerelease with attached artifacts

## Deferred validation accepted for this cut

- Ships on the clean no-device verification path without requiring a pre-publish `adb` target
- Connected-device instrumentation and manual picker/provider/diagnostics checks are deferred until after publish
- No end-to-end upload/restore has been tested since the identity derivation change
- The new commit chain operations have not been exercised on a real device

## Tag

- `v0.0.3-alpha`
