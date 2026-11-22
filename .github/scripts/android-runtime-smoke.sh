#!/usr/bin/env bash
set -euo pipefail

# Read the real package identifier from the APK, never assume a test suffix.
APK="android/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="$("${ANDROID_HOME}/build-tools/35.0.0/aapt" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
test -n "$PACKAGE" || { echo "::error::Cannot determine APK package"; exit 1; }
ACTIVITY="fr.denizot.dictee.MainActivity"

cleanup() {
  adb logcat -d -v time > android-smoke-logcat.txt || true
  adb shell dumpsys activity activities > android-smoke-activity.txt || true
  adb shell screencap -p /sdcard/Download/dictee-launch.png || true
  adb pull /sdcard/Download/dictee-launch.png android-smoke-screenshot.png || true
}
trap cleanup EXIT

echo "Installing the APK: $APK"
test -s "$APK"
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PACKAGE/$ACTIVITY"
sleep 7

echo "Checking activity process"
adb logcat -d -v time > android-smoke-logcat.txt
if grep -A 35 'FATAL EXCEPTION' android-smoke-logcat.txt | grep -q 'fr.denizot.dictee'; then
  echo "::error::Dictée universelle crashed on Android API 34"
  grep -A 35 -B 4 'FATAL EXCEPTION' android-smoke-logcat.txt | tail -100
  exit 1
fi
if ! adb shell pidof "$PACKAGE" | grep -Eq '[0-9]'; then
  echo "::error::Dictée universelle did not remain running after launch"
  exit 1
fi
echo "PASS: MainActivity running after seven seconds on Android API 34"
