#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/vibe/garland"
RUN_ID="${RUN_ID:-loop-$(date +%Y%m%d-%H%M%S)}"
PARENT="/home/vibe/garland-worktrees/$RUN_ID"
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
mkdir -p "$ROOT/.autoloop-logs"

PIDS=()
WORKTREE_PATHS=()

for entry in "${WORKLOADS[@]:0:$LOOPS}"; do
  name="${entry%%:*}"
  workload_rel="${entry#*:}"
  branch="loop/$RUN_ID/$name"
  worktree="$PARENT/$name"
  log_file="$ROOT/.autoloop-logs/${name}-${RUN_ID}.log"

  git -C "$ROOT" worktree add -b "$branch" "$worktree" HEAD
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
printf 'Run ID: %s\n' "$RUN_ID"

if [ "$failed" -ne 0 ]; then
  echo "One or more loops failed. Check .autoloop-logs/*-runner.log"
  exit 1
fi

echo "All loops completed. Runner logs are in $ROOT/.autoloop-logs"
