#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/vibe/garland"
PARENT="/home/vibe/garland-worktrees"
ROUNDS="${1:-4}"
LOOPS="${2:-3}"

if [ "$LOOPS" -lt 2 ] || [ "$LOOPS" -gt 3 ]; then
  echo "loop count must be 2 or 3"
  exit 1
fi

WORKLOADS=(
  "background:automation/loop-workloads/background-execution.md"
  "provider:automation/loop-workloads/provider-flow.md"
  "diagnostics:automation/loop-workloads/diagnostics-release.md"
)

mkdir -p "$PARENT"

PIDS=()
WORKTREE_PATHS=()

for entry in "${WORKLOADS[@]:0:$LOOPS}"; do
  name="${entry%%:*}"
  workload_rel="${entry#*:}"
  branch="loop/$name"
  worktree="$PARENT/$name"
  log_file="$ROOT/.autoloop-logs/${name}-runner.log"

  rm -rf "$worktree"
  git -C "$ROOT" worktree add -b "$branch" "$worktree" HEAD
  rm -rf "$worktree/automation"
  cp -R "$ROOT/automation" "$worktree/automation"
  chmod +x "$worktree/automation/opencode_scoped_loop.sh"
  (
    cd "$worktree"
    ./automation/opencode_scoped_loop.sh "$worktree" "$workload_rel" "$ROUNDS" "$name"
  ) > "$log_file" 2>&1 &

  PIDS+=("$!")
  WORKTREE_PATHS+=("$worktree")
done

failed=0
for pid in "${PIDS[@]}"; do
  if ! wait "$pid"; then
    failed=1
  fi
done

printf 'Worktrees:\n'
for path in "${WORKTREE_PATHS[@]}"; do
  printf -- '- %s\n' "$path"
done

if [ "$failed" -ne 0 ]; then
  echo "One or more loops failed. Check .autoloop-logs/*-runner.log"
  exit 1
fi

echo "All loops completed. Runner logs are in $ROOT/.autoloop-logs"
