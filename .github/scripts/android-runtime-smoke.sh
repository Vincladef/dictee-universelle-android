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

# Regression: reproduce the user's state, with cloud sync already enabled on launch.
# Android 14 requires ACCESS_NETWORK_STATE for network-constrained JobScheduler jobs.
# The previous production APK crashed here after enabling sync.
echo "Testing startup with synchronization enabled"
adb shell am force-stop "$PACKAGE"
adb shell run-as "$PACKAGE" mkdir -p shared_prefs
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' \
  '<map><boolean name="enabled" value="true" />' \
  '<string name="token">synthetic_local_regression_token_12345678</string></map>' |
  adb shell run-as "$PACKAGE" sh -c 'cat > shared_prefs/dictee-private-sync.xml'
adb logcat -c
adb shell am start -W -n "$PACKAGE/$ACTIVITY"
sleep 7
adb logcat -d -v time > android-smoke-logcat.txt
if grep -A 45 'FATAL EXCEPTION' android-smoke-logcat.txt | grep -q 'fr.denizot.dictee'; then
  echo "::error::Android activity crashed with synchronization enabled"
  grep -A 45 -B 4 'FATAL EXCEPTION' android-smoke-logcat.txt | tail -100
  exit 1
fi
if ! adb shell pidof "$PACKAGE" | grep -Eq '[0-9]'; then
  echo "::error::Android activity did not survive startup after synchronization enabled"
  exit 1
fi
echo "PASS: sync-enabled Android 14 startup survives the scheduler call"
