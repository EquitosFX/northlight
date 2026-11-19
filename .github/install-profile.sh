#!/usr/bin/env bash
# Installs Skyfall the way the in-app updater does, first as the APK alone and then with its startup
# profile (.dm) beside it, and shows how Android compiled it each time and how quickly it then opens.
set -u
PKG=app.tvplayer
ACT=app.tvplayer/.MainActivity
adb wait-for-device
cp app/build/outputs/apk/release/app-release.apk skyfall.apk
key() { for k in "$@"; do adb shell input keyevent "$k"; done; }
measure() {
  echo "$1: how Android compiled it:"
  adb shell dumpsys package dexopt | tr -d '\r' | grep -A3 "\[$PKG\]" | sed 's/^/    /'
  times=""
  for i in $(seq 1 7); do
    adb shell am force-stop $PKG
    sleep 2
    t=$(adb shell am start -W -n $ACT | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}')
    [ "$i" -gt 1 ] && times="$times $t"
    sleep 4
  done
  echo "$1: cold starts (ms): $(echo $times | tr ' ' '\n' | sort -n | tr '\n' ' ')"
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  for i in $(seq 1 20); do key 20; done
  for i in $(seq 1 20); do key 19; done
  sleep 2
  adb shell dumpsys gfxinfo $PKG | tr -d '\r' | grep -E "Total frames rendered|Janky frames|90th percentile|99th percentile" | sed "s/^/$1: menu: /"
}
adb root >/dev/null 2>&1; sleep 2; adb wait-for-device
echo "Profile inside the APK:"; unzip -l skyfall.apk | grep dexopt
# What the profile holds, as Android reads it: how many classes and methods, from Skyfall and from Compose.
profile_contents() {
  adb shell cmd package dump-profiles $PKG >/dev/null 2>&1
  f=$(adb shell ls /data/misc/profman/ 2>/dev/null | tr -d '\r' | grep "$PKG" | grep txt | head -1)
  [ -z "$f" ] && { echo "    (couldn't read the profile)"; return; }
  adb shell cat "/data/misc/profman/$f" > profile.txt
  echo "    profile: $(wc -l < profile.txt) lines; Skyfall: $(grep -c 'app/tvplayer' profile.txt); Compose: $(grep -c 'androidx/compose' profile.txt); Media3: $(grep -c 'androidx/media3' profile.txt)"
}
adb install -r skyfall.apk >/dev/null && measure "APK alone"
adb uninstall $PKG >/dev/null
# The profile for this Android version, as the updater picks it (folder 0: Android 12+, folder 1: 9-11).
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
n=$([ "$sdk" -ge 31 ] && echo 0 || echo 1)
echo "Android API $sdk: profile folder $n"
cp app/build/outputs/apk/release/baselineProfiles/$n/app-release.dm skyfall.dm
adb uninstall $PKG >/dev/null
adb logcat -c
adb install-multiple -r skyfall.apk skyfall.dm && measure "APK with profile"
adb logcat -d | grep -E "Dexopt result|version mismatch" | grep -E "tvplayer|mismatch" | sed 's/^.*Dexopt result/    Dexopt result/' | head -3
