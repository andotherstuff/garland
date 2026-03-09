# Garland TODO

**This is the single canonical task file for Garland.** All agents must update this file instead of creating new task, status, or checklist documents. If you are an agent and you are about to create a new TODO, STATUS, CHECKLIST, or NEXT_WAVE file: stop and update this file instead.

Current target: `v0.0.4-alpha`

Status markers: `[ ]` pending · `[-]` in progress · `[x]` done

---

## Shipped

### v0.0.3-alpha (2026-03-09)

- Custom PBKDF2+HKDF identity derivation (`garland-v1` key hierarchy)
- Commit chain snapshots with directory entry reading and head resolution
- Encrypted commit payloads
- Per-share blob auth key derivation and Blossom kind 24242 upload auth
- Upload resume from saved share targets, retry hardening, partial replica handling
- Background sync crash recovery (sync-running requeue on next wake)
- Simpler UI (PR #1)
- Capped Cargo/Gradle parallelism for low-memory builds
- JNI .so libraries rebuilt with all 8 native bridge functions

### v0.0.2-alpha (2026-03-08)

- Signed release workflow via `automation/release_alpha.sh`
- `rust-nostr` for NIP-06 identity derivation and event signing

### v0.0.1-alpha (2026-03-08)

- Android MVP: identity import, upload, retry, restore, local document browsing
- DocumentsProvider with recent, search, delete, write, restore-on-read, thumbnails
- WorkManager sync/restore with duplicate-job protection and failure classification
- Diagnostics screen with per-document history and copyable reports
- Manifest validation, wildcard MIME fallback naming
- Fake Blossom/relay test harness

---

## v0.0.4-alpha goals

Close the gap between the MVP replication model and the v0.1 protocol spec. The MVP currently copies the same encrypted block to N servers (simple replication). The spec calls for Reed-Solomon erasure coding, proper inode structures, and encrypted metadata objects. This release also needs to clean up accumulated doc sprawl and keep the test suite tight.

---

## Protocol alignment (Rust core)

### Erasure coding layer

- [x] Add Reed-Solomon GF(2^8) encode/decode in Rust core (k-of-n, systematic, field poly 0x11D)
- [x] Ensure block size B is divisible by k (pad to next multiple of k if needed)
- [x] Add `prepare_erasure_coded_upload` in `crypto.rs` (encrypt then RS-encode into n distinct shares)
- [x] Add erasure-coded write planner (`erasure_write.rs`) with inode generation and multi-block support
- [x] Add erasure-coded restore path (`erasure_read.rs`) with k-of-n reconstruction and hash verification
- [x] Add RS round-trip tests: encode → drop shares → reconstruct → verify original
- [x] End-to-end write → restore round-trip test through new erasure pipeline (single and multi-block)
- [ ] Wire erasure write/read into `mvp_write.rs` or replace it entirely (optional — new modules can coexist)

### Encrypted metadata objects (inodes)

- [x] Define inode JSON structure (type, file_id, blocks, size, mime_type) matching v0.1 spec §7
- [x] Encrypt inode with `metadata_key` + random 12-byte nonce (per spec finding 1.1)
- [x] Inode encrypt/decrypt round-trip with nonce stored in parent reference
- [x] Inode builders for both MVP replication and erasure-coded uploads
- [x] File key derived from inode `file_id` (per spec §7), not document_id
- [x] Wrong-key rejection test

### Commit content encryption

- [x] Commit encryption already uses random nonce (nonce || ciphertext || tag)
- [x] Store nonce in a `nonce` tag on the commit event (plaintext, not secret)
- [x] New format: content = `ciphertext || tag`, nonce externalized to event tag
- [x] Backward-compatible decryption: reads nonce from tag if present, falls back to embedded nonce
- [x] All existing commit chain tests pass after nonce migration (head resolution, fork detection, cyclic graphs, directory readback)

### Block encryption hardening

- [ ] Switch from ChaCha20 + zero nonce to ChaCha20 + random nonce per block (defense-in-depth)
- [ ] Store block nonce in inode block reference
- [ ] Consider ChaCha20-Poly1305 (AEAD) for tamper detection before full decryption
- [ ] Update encrypt_block / decrypt_block and all callers

---

## Android integration

### Alpha recovery UX

- [x] Add native mnemonic generation and expose it through JNI
- [x] Add a visible generate/import identity flow in the settings UI
- [x] Fix the prepare-write JSON contract to include `display_name` and `mime_type`
- [x] Upload new notes in the foreground from compose with visible prepare/upload errors
- [-] Simplify the main screen by hiding advanced actions until the basic note flow is proven (notes list carries the selected-note summary, the hero now drops branding/dashboard chrome, and the top copy reads identity-first; keep trimming toward a cleaner identity + new note + notes list hierarchy)
- [x] Add a no-device host-side integration harness for real Rust prepare -> fake Blossom upload -> fake relay publish
- [x] Make selected-note upload/delete actions visible on the main screen and route them through explicit action/state presenters
- [x] Require successful Blossom uploads to return a valid Blob Descriptor before treating the share as uploaded
- [x] Honor Blossom `Retry-After` backoff hints for retryable 429/503 upload responses
- [ ] Run a real-device smoke test: generate identity -> create note -> upload note

### Wire RS shares through upload/restore

- [ ] Update `GarlandUploadPlanDecoder` to handle RS share descriptors (share_id_hex per share, not per block copy)
- [ ] Update `GarlandDownloadExecutor` restore path to fetch k-of-n and reconstruct
- [ ] Update upload plan JSON contract between Rust and Kotlin
- [ ] Add Android unit tests for RS-based upload plans and restore flows

### UX polish

- [x] Replace the launcher icon with an adaptive black flower mark so homescreen masks no longer add a white halo or extra whitespace

### Inode-aware document model

- [ ] Update `LocalDocumentRecord` to store inode metadata (file_id, block count, nonce)
- [ ] Update provider write flow to produce inode + encrypted blocks instead of raw block copies
- [ ] Update provider read flow to decrypt inode, then fetch and decrypt blocks

---

## Test coverage

- [x] RS encode/decode unit tests in Rust (11 tests in `erasure.rs`)
- [x] RS integration tests: prepare_erasure_coded_upload round-trip (2 tests in `lib.rs`)
- [x] Inode encrypt/decrypt round-trip tests in Rust (6 tests in `inode.rs`)
- [x] Commit nonce tag generation and parsing tests (verified by existing commit chain tests)
- [x] Erasure write planner tests (4 tests in `erasure_write.rs`)
- [x] Erasure restore path tests (4 tests in `erasure_read.rs`)
- [x] End-to-end write → upload → restore round-trip through new inode model
- [ ] Android upload executor tests with RS share plans
- [ ] Android restore executor tests with RS reconstruction

56 total Rust tests passing.

---

## Docs and cleanup

- [x] Consolidate RELEASE_TODO, CURRENT_STATUS, ALPHA_RELEASE_CHECKLIST, NEXT_WAVE into this file
- [ ] Update README.md verified status section after each milestone
- [ ] Keep release notes in `docs/RELEASE_NOTES_v0.0.4-alpha.md` (create at release time, not before)

---

## Deferred (not blocking v0.0.4-alpha)

These items are real work but not part of this release:

- Real-device validation (no Android device available on this VPS)
- Encrypted `prev` tag in commit events (spec finding 4.4 — low priority)
- Separate storage identifier from passphrase (spec finding 4.3)
- k=1 replication hash correlation mitigation (spec finding 4.1)
- Garbage collection (spec §13)
- Multi-user access control
- Pack files / inline content for small files (spec finding 5.1)

---

## Verification commands

Routine iteration:

```bash
./automation/cargo_capped.sh test          # Rust core (54 tests)
./gradlew testDebugUnitTest                # Android unit tests
```

Release prep only:

```bash
./gradlew assembleDebug                    # full APK build
./gradlew lintDebug                        # static analysis
./gradlew jacocoDebugUnitTestReport        # JVM coverage report
python3 automation/report_android_unit_coverage.py  # coverage summary
./gradlew compileDebugAndroidTestKotlin    # instrumentation compile gate
automation/verify_alpha_no_device.sh       # full no-device release gate
```

**Never run bare `cargo build` or `cargo test`** — always use `./automation/cargo_capped.sh`. The machine has 0 swap; uncapped Cargo builds hang it.

---

## Release workflow

```bash
# from a fresh worktree aligned with origin/main:
automation/release_alpha.sh v0.0.4-alpha
```

The script rebuilds JNI libraries, runs verification, builds a signed APK, and publishes a GitHub prerelease.
