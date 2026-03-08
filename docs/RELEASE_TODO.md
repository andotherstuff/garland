# Garland Release Todo

This is the single canonical release task file for Garland agents.

Status markers:

- [ ] pending
- [-] in progress
- [x] done

Current target: `v0.0.2-alpha`

## Current read on repo state

- The release target on `origin/main` is already `v0.0.2-alpha`.
- `origin/main` already contains the signed-release workflow in `automation/release_alpha.sh`.
- Repo-side prerelease checks are already documented as passing on `origin/main`:
  - `automation/verify_alpha_no_device.sh`
  - `cargo test`
  - `./gradlew testDebugUnitTest`
  - `./gradlew jacocoDebugUnitTestReport`
  - `python3 automation/report_android_unit_coverage.py`
  - `./gradlew compileDebugAndroidTestKotlin`
  - `./gradlew assembleDebug`
  - `./gradlew assembleRelease`
  - `./gradlew lintDebug`
- Local release prerequisites are present in this environment:
  - `gh auth status` is healthy
  - Android SDK exists at `/home/vibe/Android`
  - Android NDK exists at `/home/vibe/Android/ndk/27.2.12479018`
  - release signing material exists at `/home/vibe/.config/garland/release/`
- The shared checkout at `/home/vibe/garland` is not releasable as-is because it is dirty and behind `origin/main`.
- Release work must run from fresh worktrees created from latest `origin/main`.

## Release policy for this cut

- This release can ship without a pre-release `adb` target.
- Connected-device instrumentation and manual picker/provider checks are deferred follow-through, not hard blockers.
- The release owner accepts that the first real device validation may happen after publish.
- The mandatory gate for this cut is a clean no-device release run from fresh `origin/main`, plus clear documentation of the deferred device risk.

## What is still blocking the release

1. The release docs and handoff need to say clearly that connected-device and manual device validation are deferred until after publish.
2. The final release must run from a fresh clean worktree aligned with `origin/main`.
3. After publish, someone still needs to do production or real-device validation and report back any fallout quickly.

## Non-negotiable workflow rules

- Do not do release work in the shared dirty checkout at `/home/vibe/garland`.
- Every agent uses its own git worktree based on latest `origin/main`.
- Use unique branch names and unique worktree directories per agent.
- Do not assume `.git` is a directory in the worktree; use `git -C <worktree>` or `git rev-parse --show-toplevel`.
- Do not publish the release until the deferred device-validation risk is explicitly documented.

## Standard worktree setup

Use this pattern for every agent task:

```bash
REPO=/home/vibe/garland
AGENT_NAME=<agent-name>
WORKTREE=/home/vibe/garland-worktrees/$AGENT_NAME
BRANCH=release/$AGENT_NAME

git -C "$REPO" fetch origin
git -C "$REPO" worktree add -b "$BRANCH" "$WORKTREE" origin/main
git -C "$WORKTREE" status --short --branch
```

If the branch or path already exists, pick a new unique `<agent-name>` instead of reusing a dirty worktree.

## Workstreams

### 1. Release captain

Goal: own the final release path and keep the checklist honest.

- [-] Create a fresh worktree from `origin/main`.
- [-] Confirm `git -C "$WORKTREE" rev-parse HEAD` matches `git -C "$WORKTREE" rev-parse origin/main` before final release.
- [ ] Confirm `docs/ALPHA_RELEASE_CHECKLIST.md`, `docs/CURRENT_STATUS.md`, `README.md`, `NEXT_WAVE.md`, and `docs/RELEASE_NOTES_v0.0.2-alpha.md` still match the actual release state.
- [ ] Confirm the deferred device-validation risk is documented before publishing.
- [ ] Run `automation/release_alpha.sh v0.0.2-alpha` from the clean release worktree once the no-device release gates are closed.
- [ ] Capture the final APK path, SHA-256 file path, and GitHub prerelease URL.

### 2. Deferred device validation owner

Goal: track the work that will happen after publish instead of pretending it happened before release.

- [ ] Create a dedicated worktree from `origin/main` only if device-side follow-up needs code or doc changes.
- [ ] After publish, bring up a usable emulator or connect a device if one becomes available.
- [ ] Run `./gradlew connectedDebugAndroidTest` when a target exists.
- [ ] Record whether provider flow, pending sync worker flow, restore worker flow, and diagnostics flow passed.
- [ ] If tests fail, file exact failing test names, stack traces, and whether the failure is harness-only or product behavior.
- [ ] Feed any results back into `docs/ALPHA_RELEASE_CHECKLIST.md` and `docs/CURRENT_STATUS.md`.

### 3. Post-release manual validation owner

Goal: do the real picker/provider/diagnostics checks after publish and treat them as release follow-through.

- [ ] Install the published build on a real device when one is available.
- [ ] Upload a small text file and confirm relay publish status reaches the diagnostics screen.
- [ ] Retry a failed upload and confirm diagnostics remain readable after the status transition, including recent history.
- [ ] Restore a shared document through the provider path and confirm the file opens correctly.
- [ ] Verify image thumbnail behavior from the Android document picker.
- [ ] Verify MIME handling for at least one wildcard or non-image file from the document picker.
- [ ] Capture screenshots, copied diagnostics report text, device build fingerprint, and short notes for provider open/recent/search/delete checks.
- [ ] Turn any failure into a fix-forward task immediately.

### 4. Fix-forward agent

Goal: engage if post-release validation or early production use finds a real bug.

- [ ] Create a dedicated fix worktree from latest `origin/main`.
- [ ] Reproduce the failing case with the smallest correct verification command.
- [ ] Land the smallest safe fix.
- [ ] Rerun the exact failing verification plus any nearby targeted tests.
- [ ] Hand the verified commit back to the release captain for merge onto fresh `main`.

### 5. Docs closer (`release-docs-closer-20260308`)

Goal: make the release docs reflect reality before and after publish.

- [x] Update `docs/ALPHA_RELEASE_CHECKLIST.md` to mark connected-device and manual checks as deferred, not secretly passed.
- [x] Update `docs/CURRENT_STATUS.md` so it says device validation is still pending but no longer blocks this release cut.
- [x] Update `README.md` and `NEXT_WAVE.md` if needed so the release story matches the production-first decision.
- [x] Update `docs/RELEASE_NOTES_v0.0.2-alpha.md` with the accepted limitation that device validation follows release.
- [x] Keep wording factual; do not claim device verification passed until evidence exists.

## Release order

1. Docs closer updates the tracked release docs so deferred device validation is explicit.
2. Release captain creates a brand-new clean worktree from latest `origin/main`.
3. Release captain confirms the release docs still match the intended no-device release stance.
4. Release captain runs `automation/release_alpha.sh v0.0.2-alpha`.
5. Post-release validation owner runs connected/manual device checks later and reports back.
6. Fix-forward agent handles any real failures found in step 5.

## Done means done

The release is ready only when all of the following are true:

- [x] Deferred device-validation risk is documented in the release docs
- [ ] Release docs match reality
- [ ] Final release run happens from a clean worktree aligned with latest `origin/main`
- [ ] `automation/release_alpha.sh v0.0.2-alpha` completes successfully
- [ ] Signed APK, SHA-256, and GitHub prerelease are all captured
- [ ] There is a named owner for post-release connected/manual validation
