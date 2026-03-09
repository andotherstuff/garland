# Garland v0.0.4-alpha

Fourth alpha release of the Garland Android MVP.

## What changed

### Recovery release
- Restores a working first-run Garland path after the broken `v0.0.3-alpha` cut.
- Add visible identity generation in-app instead of requiring manual mnemonic entry only.
- Move note creation onto a foreground upload path so failures are shown immediately instead of silently disappearing into background work.

### Upload and auth hardening
- Encrypted share uploads always use `application/octet-stream`.
- Successful Blossom uploads now require a valid Blob Descriptor response before Garland treats a share as uploaded.
- Upload retries now honor Blossom `Retry-After` hints for `429` and `503` responses.
- Blossom upload auth events are scoped more tightly with `server` and `size` tags while keeping the per-share derived auth key model.

### No-device verification improvements
- Add a host-side integration harness that uses the real Rust prepare path, then runs the Kotlin upload pipeline against fake Blossom and relay servers.
- Add focused tests for invalid upload success bodies, MIME enforcement, retry/backoff behavior, and auth-event tagging.

### UI and packaging
- Simplify the main note flow toward identity -> new note -> notes list -> selected note action.
- Make selected-note actions visible on the main screen.
- Switch the app window background to true black for OLED displays.
- Restore the launcher icon polish and updated visual language from the recent UI work on `main`.

### Protocol and storage foundation
- Add the inode module with spec-aligned file inode structure.
- Add Reed-Solomon erasure coding and erasure-coded write/restore integration paths.
- Persist commit encryption nonce tags for interoperability.

## Repo-side verification

- `./automation/cargo_capped.sh test` — 56 Rust tests pass
- `./automation/verify_alpha_no_device.sh` — full no-device release verification passes
- `./gradlew testDebugUnitTest` — Android JVM unit tests pass
- `./gradlew compileDebugAndroidTestKotlin` — instrumentation tests compile
- `./gradlew assembleDebug` and `./gradlew assembleRelease` — APK packaging paths build successfully during release verification
- `./gradlew lintDebug` — lint passes

## Release workflow

- run `automation/release_alpha.sh v0.0.4-alpha` from a clean worktree aligned with latest `origin/main`
- the script rebuilds JNI libraries, runs the no-device verification path, builds a signed release APK, verifies the APK signature, and creates or updates the GitHub prerelease with attached artifacts

## Deferred validation accepted for this cut

- Ships on the verified no-device path without a pre-publish `adb` target
- Runtime behavior on physical devices is still validated after publish rather than before cut

## Tag

- `v0.0.4-alpha`
