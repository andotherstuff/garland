#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-v0.0.3-alpha}"
VERSION="${TAG#v}"
RELEASE_HOME="${GARLAND_RELEASE_HOME:-$HOME/.config/garland/release}"
SIGNING_PROPERTIES="${GARLAND_RELEASE_PROPERTIES:-$RELEASE_HOME/signing.properties}"
KEYSTORE_PATH="${GARLAND_RELEASE_KEYSTORE:-$RELEASE_HOME/garland-release.jks}"
KEY_ALIAS="${GARLAND_RELEASE_ALIAS:-garland-release}"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/27.2.12479018}"
BUILD_TOOLS_DIR="${ANDROID_BUILD_TOOLS:-$SDK_DIR/build-tools/35.0.0}"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
APK_NAME="garland-${TAG}.apk"
APK_RELEASE_PATH="$ROOT_DIR/app/build/outputs/apk/release/$APK_NAME"
SHA_PATH="$ROOT_DIR/app/build/outputs/apk/release/${APK_NAME}.sha256"
RELEASE_NOTES_PATH="$ROOT_DIR/docs/RELEASE_NOTES_${TAG}.md"

fail() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

ensure_local_properties() {
  if [[ ! -f "$ROOT_DIR/local.properties" ]]; then
    printf 'sdk.dir=%s\n' "$SDK_DIR" > "$ROOT_DIR/local.properties"
  fi
}

ensure_clean_tree() {
  git -C "$ROOT_DIR" diff --quiet || fail "git worktree has unstaged changes"
  git -C "$ROOT_DIR" diff --cached --quiet || fail "git worktree has staged changes"
}

ensure_latest_main() {
  git -C "$ROOT_DIR" fetch origin
  local head_sha origin_sha
  head_sha="$(git -C "$ROOT_DIR" rev-parse HEAD)"
  origin_sha="$(git -C "$ROOT_DIR" rev-parse origin/main)"
  [[ "$head_sha" == "$origin_sha" ]] || fail "HEAD is not aligned with origin/main"
}

random_secret() {
  python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(36))
PY
}

ensure_signing_material() {
  mkdir -p "$RELEASE_HOME"
  chmod 700 "$RELEASE_HOME"

  if [[ ! -f "$SIGNING_PROPERTIES" ]]; then
    local store_password key_password
    store_password="$(random_secret)"
    key_password="$(random_secret)"
    cat > "$SIGNING_PROPERTIES" <<EOF
storeFile=$KEYSTORE_PATH
storePassword=$store_password
keyAlias=$KEY_ALIAS
keyPassword=$key_password
EOF
    chmod 600 "$SIGNING_PROPERTIES"
  fi

  # shellcheck disable=SC1090
  source "$SIGNING_PROPERTIES"

  if [[ ! -f "$storeFile" ]]; then
    keytool -genkeypair \
      -storetype JKS \
      -keystore "$storeFile" \
      -storepass "$storePassword" \
      -alias "$keyAlias" \
      -keypass "$keyPassword" \
      -keyalg RSA \
      -keysize 4096 \
      -validity 9125 \
      -dname "CN=Garland Release,O=andotherstuff,C=US"
    chmod 600 "$storeFile"
  fi
}

build_release_artifacts() {
  ANDROID_NDK_HOME="$NDK_DIR" nice -n 15 cargo ndk -j "${GARLAND_CARGO_JOBS:-2}" -t arm64-v8a -t x86_64 -o app/src/main/jniLibs build --release
  ./automation/verify_alpha_no_device.sh
  ./automation/gradle_capped.sh assembleRelease
}

verify_release_apk() {
  [[ -f "$APK_PATH" ]] || fail "release APK not found at $APK_PATH"
  "$BUILD_TOOLS_DIR/apksigner" verify --print-certs "$APK_PATH"
  cp "$APK_PATH" "$APK_RELEASE_PATH"
  sha256sum "$APK_RELEASE_PATH" > "$SHA_PATH"
}

create_or_update_release() {
  if gh release view "$TAG" >/dev/null 2>&1; then
    gh release upload "$TAG" "$APK_RELEASE_PATH" "$SHA_PATH" --clobber
  else
    gh release create "$TAG" \
      "$APK_RELEASE_PATH" \
      "$SHA_PATH" \
      --prerelease \
      --title "Garland $TAG" \
      --notes-file "$RELEASE_NOTES_PATH"
  fi
}

main() {
  require_command cargo
  require_command gh
  require_command git
  require_command keytool
  require_command python3
  require_command sha256sum

  [[ -x "$BUILD_TOOLS_DIR/apksigner" ]] || fail "apksigner not found at $BUILD_TOOLS_DIR/apksigner"
  [[ -d "$NDK_DIR" ]] || fail "Android NDK not found at $NDK_DIR"
  [[ -f "$RELEASE_NOTES_PATH" ]] || fail "release notes file missing: $RELEASE_NOTES_PATH"

  cd "$ROOT_DIR"
  ensure_clean_tree
  ensure_latest_main
  ensure_local_properties
  ensure_signing_material
  build_release_artifacts
  verify_release_apk
  create_or_update_release

  printf 'release complete: %s\n' "$TAG"
  printf 'signed apk: %s\n' "$APK_RELEASE_PATH"
  printf 'sha256: %s\n' "$SHA_PATH"
  printf 'signing properties: %s\n' "$SIGNING_PROPERTIES"
}

main "$@"
