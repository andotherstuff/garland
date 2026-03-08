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
run_step "Android instrumentation compile" ./gradlew compileDebugAndroidTestKotlin
run_step "Debug APK build" ./gradlew assembleDebug

printf '\nNo-device alpha verification passed.\n'
