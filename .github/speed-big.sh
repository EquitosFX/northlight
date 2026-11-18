#!/usr/bin/env bash
# With a big playlist added (the way a person would, through Skyfall's own restore page): how long
# each screen takes to open (Skyfall's own timings) and how smoothly it moves.
set -u
PKG=app.tvplayer
ACT=app.tvplayer/.MainActivity
key() { for k in "$@"; do adb shell input keyevent "$k"; done; }
repeat_key() { for i in $(seq 1 "$2"); do adb shell input keyevent "$1"; done; }
# Taps the middle of the first thing on screen showing exactly this text.
tap_text() {
  timeout 60 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b want=$1
  b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1\"" | grep -o 'bounds="[^"]*"' | head -1 | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $b
  if [ $# -lt 4 ]; then echo "couldn't find \"$want\" on screen"; return 1; fi
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
python3 .github/bigplaylist.py big
(cd big && python3 -m http.server 8000 >/dev/null 2>&1 &)
sleep 2
mkdir -p big/img
posters=(app/src/test/resources/demo/*.png); for i in $(seq 0 49); do cp "${posters[$((i % ${#posters[@]}))]}" big/img/m$i.png; done

for variant in after; do
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r "$variant.apk" >/dev/null || { echo "$variant: install failed"; continue; }
  adb shell cmd package compile -f -m speed-profile $PKG >/dev/null 2>&1
  timeout 60 adb shell am start -W -n $ACT >/dev/null
  sleep 6
  # Settings › Backup and restore, by tapping their names on screen.
  tap_text "Settings"
  sleep 3
  tap_text "Backup and restore"
  sleep 4
  echo "$variant: reading the restore page"
  timeout 60 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  address=$(adb shell cat /sdcard/ui.xml | grep -o 'http://[0-9.]*:8686/[a-z0-9]*' | head -1)
  code=${address##*/}
  if [ -z "$code" ]; then
    echo "$variant: couldn't find the restore page"
    adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -40
    continue
  fi
  adb forward tcp:8686 tcp:8686 >/dev/null
  echo "$variant: restore: $(curl -s --max-time 120 -X POST --data-binary @big/backup.json "http://127.0.0.1:8686/$code/restore" | head -c 200)"
  # Let it load the playlist and the guide.
  sleep 150

  journey() {
    local name=$1; shift
    adb shell am force-stop $PKG
    sleep 2
    adb logcat -c
    adb shell am start -W -n $ACT >/dev/null
    sleep 5
    adb shell dumpsys gfxinfo $PKG reset >/dev/null
    "$@"
    sleep 3
    adb shell dumpsys gfxinfo $PKG | tr -d '\r' | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" | tr -s ' ' | sed "s/^/$variant: $name: /"
    adb logcat -d -s SkyfallTiming:I | tr -d '\r' | grep -o 'SkyfallTiming: .*' | sed "s/^SkyfallTiming: /$variant: $name: opened: /"
  }
  guide() { key 20; sleep 4; key 22 23; sleep 2; repeat_key 20 30; repeat_key 22 8; repeat_key 21 8; repeat_key 19 15; }
  films() { repeat_key 20 3; sleep 5; key 22; sleep 1; key 22; repeat_key 20 25; repeat_key 22 6; }
  series() { repeat_key 20 4; sleep 5; key 22; sleep 1; key 22; repeat_key 20 25; key 23; sleep 5; key 4; }
  home() { key 22; repeat_key 20 6; repeat_key 22 6; }
  journey "TV guide" guide
  journey "Films" films
  journey "Series" series
  journey "Home" home
  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    echo "$variant: CRASHED"; adb logcat -d | grep -A25 "FATAL EXCEPTION" | head -40
  fi
done
