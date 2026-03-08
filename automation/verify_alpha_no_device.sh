#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

run_step() {
  local label="$1"
  shift
  printf '\n==> %s\n' "$label"
  "$@"
}

cd "$ROOT_DIR"

run_step "Rust core tests" cargo test
run_step "Android unit tests" ./gradlew testDebugUnitTest
run_step "Android unit test coverage" ./gradlew jacocoDebugUnitTestReport
run_step "Android unit coverage summary" python3 automation/report_android_unit_coverage.py
run_step "Android instrumentation compile" ./gradlew compileDebugAndroidTestKotlin
run_step "Debug APK build" ./gradlew assembleDebug
run_step "Android lint" ./gradlew lintDebug

printf '\nNo-device alpha verification passed.\n'
printf 'Remaining release gates: connected Android instrumentation and manual device sign-off.\n'
