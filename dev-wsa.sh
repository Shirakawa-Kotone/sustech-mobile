#!/usr/bin/env bash
# WSA dev loop for sustech-mobile. Usage: bash dev-wsa.sh [run|log|reinstall|reset]
set -e
export JAVA_HOME="D:/dumix/Applications/Android/jdk-17"
export ANDROID_HOME="D:/dumix/Applications/Android/sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
SERIAL=127.0.0.1:58526
APK=app/build/outputs/apk/debug/app-debug.apk
cd "$(dirname "$0")"

wakeup() { "$ADB" connect $SERIAL >/dev/null 2>&1 || true; sleep 2; "$ADB" devices | grep -q "$SERIAL.device"; }

case "${1:-run}" in
  run)     wakeup && ./gradlew assembleDebug --console=plain -q && "$ADB" -s $SERIAL install -r "$APK" >/dev/null && "$ADB" -s $SERIAL shell am start -n edu.sustech.mobile/.ui.LoginActivity ;;
  log)     wakeup && "$ADB" -s $SERIAL logcat --pid=$("$ADB" -s $SERIAL shell pidof -s edu.sustech.mobile | tr -d '\r') ${2:--v} ;;
  logall)  wakeup && "$ADB" -s $SERIAL logcat ${2:-} ;;
  crash)   wakeup && "$ADB" -s $SERIAL logcat -b crash -d ;;
  reinstall) "$ADB" -s $SERIAL install -r -d "$APK" >/dev/null && echo reinstalled ;;
  reset)   "$ADB" -s $SERIAL uninstall edu.sustech.mobile && "$ADB" -s $SERIAL install "$APK" >/dev/null && echo fresh-installed ;;
esac
