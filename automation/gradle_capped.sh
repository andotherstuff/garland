#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAX_WORKERS="${GARLAND_GRADLE_MAX_WORKERS:-1}"
NICE_LEVEL="${GARLAND_GRADLE_NICE:-15}"
ACTIVE_CPUS="${GARLAND_GRADLE_ACTIVE_CPUS:-$MAX_WORKERS}"
ANDROID_HOME_DEFAULT="/home/vibe/Android"

cd "$ROOT_DIR"

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME_DEFAULT" ]; then
  export ANDROID_HOME="$ANDROID_HOME_DEFAULT"
fi

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

gradle_cmd=(./gradlew --no-daemon "--max-workers=${MAX_WORKERS}" "$@")

export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false -Dorg.gradle.parallel=false -Dorg.gradle.priority=low -Dorg.gradle.workers.max=${MAX_WORKERS} -Dkotlin.compiler.execution.strategy=in-process"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:ActiveProcessorCount=${ACTIVE_CPUS}"

if command -v ionice >/dev/null 2>&1; then
  exec nice -n "$NICE_LEVEL" ionice -c 3 "${gradle_cmd[@]}"
fi

exec nice -n "$NICE_LEVEL" "${gradle_cmd[@]}"
