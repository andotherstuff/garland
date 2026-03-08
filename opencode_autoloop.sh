#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/vibe/garland"
SESSIONS="${1:-20}"
LOG_DIR="$ROOT/.autoloop-logs"
mkdir -p "$LOG_DIR"
MODEL="openai/gpt-5.4"

PROMPT=$(cat <<'EOF'
You are working in the Garland repo.

Goal:
- Continue Garland according to README.md and NEXT_WAVE.md.
- Make one focused improvement that moves the next unchecked roadmap item forward.
- If a roadmap item is blocked, add tests, diagnostics, or polish that clearly reduces that gap.
- Keep the change surgical, shippable, and verified before stopping.

Rules:
- Read README.md and NEXT_WAVE.md first.
- Do not rewrite major architecture.
- Do not commit, push, or change git config.
- Respect existing worktree changes.
- Update NEXT_WAVE.md only when a todo item is actually complete.
- If you touch Rust core code, rebuild JNI libs with:
  ANDROID_NDK_HOME="/home/vibe/Android/ndk/27.2.12479018" cargo ndk -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release
- Verify every round with the smallest correct set of commands:
  - use `./automation/gradle_capped.sh ...` for every Gradle invocation so builds run at low priority with a single worker
  - run `./automation/gradle_capped.sh testDebugUnitTest` for Kotlin/Android code changes
  - run `./automation/gradle_capped.sh compileDebugAndroidTestKotlin` when Android test-source compile coverage is enough
  - run `./automation/gradle_capped.sh assembleDebug` only when Android app code, manifest, resources, or Gradle config changed
  - run cargo test when Rust code changed
- Fix any failures before ending the round.

Output format at the end:
ROUND_STATUS: <done or blocked>
SUMMARY: <one line>
FILES: <comma-separated paths>
VERIFY: <commands run and result>
NEXT: <one line>
EOF
)

for i in $(seq 1 "$SESSIONS"); do
  LOG_FILE="$LOG_DIR/session-$(printf "%02d" "$i").log"
  echo "=== Garland auto session $i/$SESSIONS ==="
  if [ "$i" -eq 1 ]; then
    CMD=(opencode run --dir "$ROOT" -m "$MODEL" "$PROMPT")
  else
    CMD=(opencode run --dir "$ROOT" -m "$MODEL" --continue "$PROMPT")
  fi
  if ! "${CMD[@]}" | tee "$LOG_FILE"; then
    echo "Session $i failed. See $LOG_FILE"
    exit 1
  fi
done

echo "Completed $SESSIONS sessions. Logs: $LOG_DIR"
