#!/usr/bin/env bash
# Why did the launcher not come up? One command, four answers.
#
# Written for the head-unit case: no Play Store, often no `adb logcat` habit, and a ROM whose real
# API level is not the one its "About phone" screen shows. Everything here is read-only.
#
#   bash scripts/diagnose.sh [package]
#
# Exit codes: 0 = device answered everything, 2 = no device visible.
set -u
PKG="${1:-com.arena.carlauncher}"
ADB="${ADB:-adb}"

have() { command -v "$ADB" >/dev/null 2>&1; }

if ! have; then
  echo "adb not found. Set ADB=/path/to/adb or install platform-tools."
  exit 2
fi
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "no device (adb get-state failed). Is USB debugging on, and is the unit reachable?"
  echo "     over Wi-Fi:  adb tcpip 5555 && adb connect <ip>:5555"
  exit 2
fi

echo "== 0. what the device actually is (the Settings app often lies about this) =="
"$ADB" shell sh -c 'getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.hardware'

echo
echo "== 1. is the APK installed, enabled, and not in the stopped state? =="
"$ADB" shell "pm list packages -3 | grep ${PKG} || echo 'NOT INSTALLED'"
"$ADB" shell "dumpsys package ${PKG} | grep -E 'versionName|versionCode|minSdk|targetSdk|flags|enabled|stopped' | head -12"

echo
echo "== 2. does the ROM accept it as a home screen at all? =="
"$ADB" shell "cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN" | head -12
echo "-- current default for HOME (if it is not this app, press HOME and pick it):"
"$ADB" shell "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN" 2>/dev/null | tail -1

echo
echo "== 3. the crash, if there was one =="
"$ADB" logcat -d -b crash -t 200 2>/dev/null | grep -A 40 -E "${PKG}|AndroidRuntime" | tail -60
echo "-- AndroidRuntime from the main buffer:"
"$ADB" logcat -d -t 600 2>/dev/null | grep -E "FATAL|AndroidRuntime|${PKG}" | tail -30
echo "-- what the launcher itself recorded (safe mode, hub failures):"
"$ADB" logcat -d -t 600 -s CrashLog:W CrashLog:E 2>/dev/null | tail -20

echo
echo "== 4. the launcher's own crash file (debug builds only — run-as needs android:debuggable) =="
"$ADB" shell "run-as ${PKG} cat files/crash.log" 2>/dev/null | tail -40 \
  || echo "run-as unavailable (release build or a locked ROM). The same text is in Settings → Debug → «گزارش کرش»."

echo
echo "== 5. does it start when *we* ask, ignoring the home assignment? =="
echo "   (read-only check; run this one by hand so you can watch the screen)"
echo "     ${ADB} shell am start -n ${PKG}/.home.HomeActivity"
echo "     ${ADB} logcat -c && ${ADB} shell am force-stop ${PKG} && ${ADB} shell am start -W -n ${PKG}/.home.HomeActivity"
