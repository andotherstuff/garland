#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_ARGS=(--max-workers=2)

run_step() {
  local label="$1"
  shift
  printf '\n==> %s\n' "$label"
  "$@"
}

cd "$ROOT_DIR"

run_step "Rust core tests" cargo test
run_step "Android Gradle verification" ./gradlew "${GRADLE_ARGS[@]}" jacocoDebugUnitTestReport compileDebugAndroidTestKotlin assembleDebug lintDebug
run_step "Android unit coverage summary" python3 automation/report_android_unit_coverage.py

printf '\nNo-device alpha verification passed.\n'
printf 'Remaining release gates: GitHub test-release smoke testing and manual device sign-off.\n'
