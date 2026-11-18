#!/usr/bin/env bash
# Measures Skyfall on an emulator: how long a cold start takes, how smoothly the menu and screens move,
# and that nothing crashes while moving through every screen. Compares the build published now
# ("before") with this branch's release build ("after").
set -u
PKG=app.tvplayer
ACT=app.tvplayer/.MainActivity
adb wait-for-device
key() { for k in "$@"; do adb shell input keyevent "$k"; done; }
repeat_key() { for i in $(seq 1 "$2"); do adb shell input keyevent "$1"; done; }
curl -sL -o before.apk https://github.com/EquitosFX/northlight/releases/latest/download/northlight.apk
cp app/build/outputs/apk/release/app-release.apk after.apk
for variant in before after; do
  apk="$variant.apk"
  echo "$variant: size $(du -k "$apk" | cut -f1) KB, startup profile: $(unzip -l "$apk" | grep -c 'assets/dexopt/baseline.prof$')"
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r "$apk" >/dev/null || { echo "$variant: install failed"; continue; }
  # Let Android compile the startup profile, as it does in the background on a real box.
  adb shell cmd package compile -f -m speed-profile $PKG >/dev/null 2>&1
  adb logcat -c
  times=""
  for i in $(seq 1 11); do
    adb shell am force-stop $PKG
    sleep 2
    t=$(adb shell am start -W -n $ACT | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')
    [ "$i" -gt 1 ] && times="$times $t"
    sleep 4
  done
  sorted=$(echo $times | tr ' ' '\n' | sort -n | tr '\n' ' ')
  median=$(echo $times | tr ' ' '\n' | sort -n | sed -n 5p)
  echo "$variant: cold starts (ms): $sorted  median $median"
  # Up and down the menu as fast as a held button (each section opens behind it on the way).
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  repeat_key 20 20
  repeat_key 19 20
  sleep 2
  adb shell dumpsys gfxinfo $PKG | tr -d '\r' | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" | sed "s/^/$variant: menu: /"
  # Into each section and about a bit, then back to the menu: nothing should crash.
  for section in $(seq 1 10); do
    key 22 20 22 20 21 4
    sleep 1
    key 4 20
    sleep 1
  done
  # Settings: every pane.
  key 19
  sleep 1
  key 22
  for pane in $(seq 1 7); do key 20 23; sleep 1; done
  key 4 4
  sleep 2
  echo "$variant: memory: $(adb shell dumpsys meminfo $PKG | tr -d '\r' | grep -E 'TOTAL PSS|TOTAL:' | head -1 | tr -s ' ')"
  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "$variant: CRASHED"
    adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -50
  elif ! adb shell pidof $PKG >/dev/null; then
    echo "$variant: CLOSED (no longer running)"
    adb logcat -d | grep -iE "AndroidRuntime|$PKG" | tail -30
  else
    echo "$variant: no crashes"
  fi
done
