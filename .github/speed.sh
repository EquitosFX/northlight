#!/usr/bin/env bash
# Measures Skyfall on an emulator: how long a cold start takes, and how smoothly the menu moves,
# for the debug build (what was published before) and the release build (what's published now).
set -u
PKG=app.tvplayer
ACT=app.tvplayer/.MainActivity
adb wait-for-device
adb shell settings put global window_animation_scale 1
for variant in debug release; do
  apk="app/build/outputs/apk/$variant/app-$variant.apk"
  adb uninstall $PKG >/dev/null 2>&1
  echo "$variant: startup profile in the APK: $(unzip -l "$apk" | grep -c 'assets/dexopt/baseline.prof$')"
  adb install -r "$apk" >/dev/null || { echo "$variant: install failed"; continue; }
  echo "$variant: debuggable: $(adb shell dumpsys package $PKG | tr -d '\r' | grep -c 'DEBUGGABLE')"
  # Let Android compile the startup profile, as it does in the background on a real box.
  adb shell cmd package compile -f -m speed-profile $PKG >/dev/null 2>&1
  echo "$variant: compiled as: $(adb shell dumpsys package dexopt | tr -d '\r' | grep -A4 "\[$PKG\]" | grep -o 'status=[a-z-]*' | head -1)"
  adb logcat -c
  times=""
  for i in 1 2 3 4 5 6 7 8 9 10 11; do
    adb shell am force-stop $PKG
    sleep 2
    t=$(adb shell am start -W -n $ACT | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')
    # The first start includes one-off set-up; count the other ten.
    [ "$i" -gt 1 ] && times="$times $t"
    sleep 4
  done
  sorted=$(echo $times | tr ' ' '\n' | sort -n | tr '\n' ' ')
  median=$(echo $times | tr ' ' '\n' | sort -n | sed -n 5p)
  echo "$variant: cold starts (ms): $sorted  median $median"
  # Up and down the menu, as fast as a held button.
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in $(seq 1 20); do adb shell input keyevent 20; done
  for i in $(seq 1 20); do adb shell input keyevent 19; done
  sleep 2
  adb shell dumpsys gfxinfo $PKG | tr -d '\r' | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" | sed "s/^/$variant: /"
  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "$variant: CRASHED"
    adb logcat -d | grep -A20 "FATAL EXCEPTION" | head -40
  else
    echo "$variant: no crashes"
  fi
done
