#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_RUNNER=(./automation/gradle_capped.sh)

run_step() {
  local label="$1"
  shift
  printf '\n==> %s\n' "$label"
  "$@"
}

cd "$ROOT_DIR"

run_step "Rust core tests" cargo test
run_step "Android unit tests" "${GRADLE_RUNNER[@]}" testDebugUnitTest
run_step "Android unit test coverage" "${GRADLE_RUNNER[@]}" jacocoDebugUnitTestReport
run_step "Android unit coverage summary" python3 automation/report_android_unit_coverage.py
run_step "Android instrumentation compile" "${GRADLE_RUNNER[@]}" compileDebugAndroidTestKotlin
run_step "Debug APK build" "${GRADLE_RUNNER[@]}" assembleDebug
run_step "Android lint" "${GRADLE_RUNNER[@]}" lintDebug

printf '\nNo-device alpha verification passed.\n'
printf 'Remaining release gates: GitHub test-release smoke testing and manual device sign-off.\n'
