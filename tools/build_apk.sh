#!/usr/bin/env bash
# Build the debug APK with the toolchain this machine actually has.
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

GRADLE="${GRADLE:-$HOME/.gradle-dist/gradle-8.7/bin/gradle}"
if [[ ! -x "$GRADLE" ]]; then
  GRADLE=./gradlew
fi

"$GRADLE" --console=plain "${1:-assembleDebug}"

APK=app/build/outputs/apk/debug/app-debug.apk
if [[ -f "$APK" ]]; then
  echo
  echo "APK: $(pwd)/$APK"
  ls -lh "$APK"
  "$ANDROID_HOME/build-tools/34.0.0/aapt2" dump badging "$APK" | head -3 || true
fi
