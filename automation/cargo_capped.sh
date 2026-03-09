#!/usr/bin/env bash
# Resource-capped cargo wrapper — mirrors gradle_capped.sh strategy.
# Runs cargo under nice/ionice with limited parallelism so builds
# cannot hang the machine (15 GB RAM, 8 CPUs, 0 swap).

set -euo pipefail

NICE_LEVEL="${GARLAND_CARGO_NICE:-15}"
MAX_JOBS="${GARLAND_CARGO_JOBS:-2}"

# CARGO_BUILD_JOBS overrides .cargo/config.toml [build] jobs,
# letting the env var win if someone sets GARLAND_CARGO_JOBS.
export CARGO_BUILD_JOBS="$MAX_JOBS"

cargo_cmd=(cargo "$@")

if command -v ionice >/dev/null 2>&1; then
  exec nice -n "$NICE_LEVEL" ionice -c 3 "${cargo_cmd[@]}"
fi

exec nice -n "$NICE_LEVEL" "${cargo_cmd[@]}"
