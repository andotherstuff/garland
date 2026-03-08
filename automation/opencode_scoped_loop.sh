#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <worktree-dir> <workload-file> [rounds] [loop-name]"
  exit 1
fi

ROOT="$1"
WORKLOAD_FILE="$2"
ROUNDS="${3:-5}"
LOOP_NAME="${4:-$(basename "$WORKLOAD_FILE" .md)}"
MODEL="${MODEL:-openai/gpt-5.4}"
LOG_DIR="$ROOT/.autoloop-logs/$LOOP_NAME"
STATE_FILE="$ROOT/.autoloop-state-$LOOP_NAME.md"
ANDROID_HOME_DEFAULT="/home/vibe/Android"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_HOME_DEFAULT}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

mkdir -p "$LOG_DIR"

to_rel_path() {
  python3 - <<'PY' "$1" "$2"
import os, sys
target = sys.argv[1]
root = os.path.abspath(sys.argv[2])
if not os.path.isabs(target):
    target = os.path.join(root, target)
print(os.path.relpath(os.path.abspath(target), root))
PY
}

WORKLOAD_REL="$(to_rel_path "$WORKLOAD_FILE" "$ROOT")"
STATE_REL="$(to_rel_path "$STATE_FILE" "$ROOT")"

if [ ! -f "$STATE_FILE" ]; then
  cat > "$STATE_FILE" <<EOF
# Loop State: $LOOP_NAME

## Completed

## Rejected Directions

## Open Risks

## Next Best Step

EOF
fi

build_prompt() {
  cat <<EOF
You are working in the Garland repo inside an isolated git worktree.

Loop name: $LOOP_NAME
Scoped workload file: $WORKLOAD_REL
Persistent loop state: $STATE_REL

Mission:
- Read README.md, NEXT_WAVE.md, $WORKLOAD_REL, and $STATE_REL first.
- Stay strictly inside this workload's scope.
- Make exactly one focused improvement this round.
- After finishing, update $STATE_REL with:
  - what was completed
  - what directions were rejected and why
  - open risks still remaining
  - the next best step for the next round

Anti-drift rules:
- Do not work outside this workload unless a tiny dependency is required.
- If you notice a tempting side quest, record it under Rejected Directions instead of doing it.
- Do not commit, push, or change git config.
- Do not touch release metadata or GitHub state.
- Update NEXT_WAVE.md only if a todo item is actually complete and the evidence exists.
- When Gradle needs the Android SDK, use the existing environment variables ANDROID_HOME and ANDROID_SDK_ROOT already set in the shell.
- The worktree already contains the repo automation files. Do not copy or duplicate the automation directory.
- Prefer repo-local commands that work without extra shell setup.
- If Android commands need adb or emulator, they should work from PATH in this shell.

Verification rules:
- Run the smallest correct verification commands for the files you changed.
- If tests fail, fix them before ending the round.
- Prefer empirical verification over code inspection.
- Use `./automation/gradle_capped.sh ...` for every Gradle command so builds stay on the repo's low-priority single-worker path.
- If a capped Gradle run still fails because of daemon/process instability, retry the same verification once with `./automation/gradle_capped.sh ...` before declaring failure.
- Before running `connectedDebugAndroidTest`, check whether a device is available with `adb devices`.
- If no device is connected, do not fail the round for that alone; run compile-time Android test verification instead and record the device block clearly.

Output format at the end:
ROUND_STATUS: <done or blocked>
SUMMARY: <one line>
FILES: <comma-separated paths>
VERIFY: <commands run and result>
LEARNED: <one line about what the loop state learned>
NEXT: <one line>
EOF
}

PROMPT="$(build_prompt)"

for i in $(seq 1 "$ROUNDS"); do
  LOG_FILE="$LOG_DIR/session-$(printf "%02d" "$i").log"
  echo "=== $LOOP_NAME session $i/$ROUNDS ==="
  CMD=(opencode run --dir "$ROOT" -m "$MODEL" "$PROMPT")
  if ! "${CMD[@]}" | tee "$LOG_FILE"; then
    echo "$LOOP_NAME session $i failed. See $LOG_FILE"
    exit 1
  fi
done

echo "Completed $ROUNDS scoped sessions for $LOOP_NAME. Logs: $LOG_DIR"
