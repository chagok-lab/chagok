#!/usr/bin/env bash
# Pleos Connect 에뮬레이터를 부팅해 release APK 를 설치·실행하고, Vehicle SDK 연결과 화면 동작을 확인합니다.
# (GitHub Actions 에서 사용. 실패해도 0 으로 끝나며 결과는 $RUNNER_TEMP/pleos/result.txt 에 남깁니다.)
set -u
OUT="${RUNNER_TEMP:-/tmp}/pleos"
mkdir -p "$OUT"
LOG="$OUT/run.log"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
PKG=com.chagok.app
APK="$(ls apk/*.apk | head -1)"
UI="$OUT/ui.xml"
T0=$(date +%s)

log() { echo "[pleos $(date +%H:%M:%S) +$(( $(date +%s) - T0 ))s] $*" | tee -a "$LOG"; }
result() { echo "$1=$2" >> "$OUT/result.txt"; log "RESULT $1=$2"; }
run() { log "\$ $*"; "$@" 2>&1 | tee -a "$LOG" | tail -n "${TAIL:-12}"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>>"$LOG" || log "screencap failed: $1"; }
dump() {
  rm -f "$UI"
  adb shell uiautomator dump /sdcard/ui.xml >>"$LOG" 2>&1
  adb exec-out cat /sdcard/ui.xml > "$UI" 2>>"$LOG"
  if [ -n "${1:-}" ]; then
    cp "$UI" "$OUT/ui_$1.xml" 2>/dev/null
    python3 scripts/ci/ui.py "$UI" texts > "$OUT/texts_$1.txt" 2>/dev/null || true
  fi
}
has() { python3 scripts/ci/ui.py "$UI" has "$1"; }
tap() {
  local xy
  xy=$(python3 scripts/ci/ui.py "$UI" tap "$1")
  if [ -n "$xy" ]; then log "tap '$1' at $xy"; adb shell input tap $xy; return 0; fi
  log "tap target not found: $1"; return 1
}
wait_text() {
  local end=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    dump
    [ "$(has "$1")" = yes ] && return 0
    sleep 1
  done
  return 1
}
snap() { dump "$1"; shot "$1"; log "screen $1: $(tr '\n' ' ' < "$OUT/texts_$1.txt" 2>/dev/null | cut -c1-600)"; }
wait_boot() {
  local i b
  for i in $(seq 1 "$1"); do
    b=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$b" = 1 ] && return 0
    kill -0 "$EMU_PID" 2>/dev/null || { log "emulator process ended"; return 1; }
    sleep 5
  done
  return 1
}

if [ ! -f "$HOME/.android/avd/pleos.avd/config.ini" ]; then
  log "AVD 가 없어 실행을 건너뜀"; result booted skipped; exit 0
fi

# Pleos Connect 이미지는 user 빌드라 adb 인증이 필요합니다. 에뮬레이터가 부팅 때 넣어 주는 공개키와
# adb 서버의 키가 같도록, 에뮬레이터를 켜기 전에 adb 키를 먼저 만들어 둡니다.
adb start-server >>"$LOG" 2>&1
ls -la "$HOME/.android/adbkey" "$HOME/.android/adbkey.pub" 2>&1 | tee -a "$LOG"

log "emulator 시작"
nohup "$SDK/emulator/emulator" -avd pleos -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim \
  -camera-back none -camera-front none -memory 4096 -cores 4 -no-metrics > "$OUT/emulator.log" 2>&1 &
EMU_PID=$!
timeout 180 adb wait-for-device || log "adb wait-for-device timeout: $(adb devices | tr '\n' ' ')"
if adb devices | grep -q unauthorized; then
  result booted "no(adb unauthorized)"
  tail -n 20 "$OUT/emulator.log" | cut -c1-300 | tee -a "$LOG"
  kill "$EMU_PID" 2>/dev/null
  exit 0
fi
if wait_boot 120; then result booted yes; else result booted no; fi
grep -E "androidboot.qemu.skin|Setting display|emulator version" "$OUT/emulator.log" | cut -c1-200 | tee -a "$LOG"
if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]; then
  adb logcat -d -v time 2>/dev/null | tail -n 400 > "$OUT/logcat_boot_tail.txt"
  shot 00_not_booted
  kill "$EMU_PID" 2>/dev/null
  exit 0
fi
result boot_seconds "$(( $(date +%s) - T0 ))"
sleep 20

log "== 기기 정보"
adb shell getprop > "$OUT/getprop.txt" 2>&1
for k in ro.build.version.release ro.build.version.sdk ro.build.display.id ro.build.flavor ro.build.type ro.product.model ro.product.name ro.product.manufacturer ro.build.fingerprint ro.build.characteristics; do
  log "$k=$(adb shell getprop $k | tr -d '\r')"
done
run adb shell wm size
run adb shell wm density
adb shell dumpsys display > "$OUT/dumpsys_display.txt" 2>&1
grep -E "mDisplayId=|DisplayDeviceInfo\{" "$OUT/dumpsys_display.txt" | cut -c1-400 | head -20 | tee -a "$LOG"
run adb shell am get-current-user
adb shell pm list packages 2>/dev/null | tr -d '\r' | sort > "$OUT/packages.txt"
log "packages: $(wc -l < "$OUT/packages.txt")"
grep -i -E "pleos|umos|42dot|navi|vehicle" "$OUT/packages.txt" | head -60 | tee -a "$LOG"
adb shell pm list permissions -f 2>/dev/null | tr -d '\r' > "$OUT/permissions_all.txt"
grep -n -i -A6 "pleos.car.permission" "$OUT/permissions_all.txt" > "$OUT/permissions_pleos.txt" || true
log "pleos permissions defined: $(grep -c 'permission:pleos' "$OUT/permissions_all.txt")"
shot 00_home; dump 00_home

log "== 앱 설치"
adb logcat -c || true
if adb install -r -g "$APK" 2>&1 | tee -a "$LOG" | grep -q Success; then
  result install "yes(-g)"
elif adb install -r "$APK" 2>&1 | tee -a "$LOG" | grep -q Success; then
  result install yes
else
  result install no
fi
for p in CAR_DRIVE CAR_MILEAGE CAR_DOORS CAR_INFO CAR_ENERGY NAVI_ROUTE NAVI_ROUTE_SEARCH; do
  log "grant $p: $(adb shell pm grant $PKG pleos.car.permission.$p 2>&1 | tr -d '\r' | cut -c1-160)"
done
adb shell dumpsys package $PKG > "$OUT/dumpsys_package_app.txt" 2>&1
grep -E "pleos.car.permission" "$OUT/dumpsys_package_app.txt" | sort -u | head -30 | tee -a "$LOG"

log "== 앱 실행"
adb shell am start -W -n "$PKG/.MainActivity" 2>&1 | tee -a "$LOG"
sleep 10
result alive_after_launch "$(adb shell pidof $PKG >/dev/null 2>&1 && echo yes || echo no)"
adb shell dumpsys activity activities 2>/dev/null | grep -E "topResumedActivity|mResumedActivity|ResumedActivity" | head -5 | tee -a "$LOG"
snap 01_launch
result launch_has_app_title "$(has '운행기록부')"
result launch_has_crn_notice "$(has 'CRN')"
result launch_waiting_signal "$(has '신호 대기')"
result launch_sdk_connected "$(has 'Vehicle SDK 연결됨')"
sleep 8; snap 02_after_18s
result no_signal_button "$(has '시뮬레이터로 시연하기')"

log "== 앱 시작 로그"
APP_PID=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
adb logcat -d -v time --pid="$APP_PID" 2>/dev/null | head -n 500 > "$OUT/logcat_app_start.txt"
adb logcat -d -v time 2>/dev/null | grep -i "chagok" | grep -v -E "broadcastPropertyCallback|collect event|compare to value|latestMap" | head -n 300 > "$OUT/logcat_service_app.txt"
log "app start log lines: $(wc -l < "$OUT/logcat_app_start.txt"), service lines: $(wc -l < "$OUT/logcat_service_app.txt")"

log "== Virtual Console 확인"
adb shell "ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null" > "$OUT/ports.txt" 2>&1
grep -E "LISTEN|:80" "$OUT/ports.txt" | head -30 | tee -a "$LOG"
for p in ai.pleos.connectwebconsole ai.pleos.hardware.vehicle.emulator ai.pleos.playground.service.vehicle; do
  adb shell dumpsys package "$p" 2>/dev/null | head -n 260 > "$OUT/pkg_$p.txt"
done
adb forward tcp:8088 tcp:8088 2>&1 | tee -a "$LOG"
timeout 180 python3 scripts/ci/vc_fetch.py http://127.0.0.1:8088/ "$OUT/vc" 2>&1 | tail -n 80 | tee -a "$LOG"
result virtual_console_http "$( [ -s "$OUT/vc/index.html" ] && echo yes || echo no )"

log "== Pleos Connect 에뮬레이터에서 앱 내 시뮬레이터로 운행 3건"
( adb shell screenrecord --time-limit 170 --bit-rate 8000000 /sdcard/pleos_demo.mp4 >>"$LOG" 2>&1 ) &
REC_T0=$(date +%s.%N)
ev() { python3 -c "import time;print('%.1f\t%s' % (time.time() - $REC_T0, '$1'))" >> "$OUT/events.tsv"; log "EVENT $1"; }
trip() {   # trip <번호> <누를 용도>
  dump; tap '다음 운행 재생'; ev "trip$1_start"
  sleep 4; shot "trip$1_driving"
  if wait_text '용도를 확인' 40; then
    ev "trip$1_confirm"; shot "trip$1_confirm"; cp "$UI" "$OUT/ui_trip$1_confirm.xml"
    result "confirm_card_$1" yes
    sleep 2
    tap "$2"; ev "trip$1_purpose"
  else
    result "confirm_card_$1" no; shot "trip$1_no_confirm"
  fi
  sleep 6
}
sleep 2; ev real_signal_view; shot 03_pleos_signal
dump
xy=$(python3 scripts/ci/ui.py "$UI" tap_checkable)
if [ -n "$xy" ]; then log "simulator switch at $xy"; adb shell input tap $xy; ev simulator_on; fi
sleep 3; snap 04_simulator_idle
result simulator_ready "$(has '다음 운행 재생')"
trip 1 '출퇴근'
trip 2 '일반업무'
trip 3 '비업무'
snap 08_report; ev report
result report_has_ratio "$(has '업무사용비율')"
result report_three_trips "$(has '오늘 운행 3건')"
sleep 3
if tap '서식 내보내기'; then
  ev export
  sleep 3; snap 09_export
  result export_dialog "$(has '서식을 저장했습니다')"
  sleep 3
  tap '닫기'; ev export_closed; sleep 2
fi
ev end
adb shell pkill -INT screenrecord >>"$LOG" 2>&1 || true
sleep 4
adb pull /sdcard/pleos_demo.mp4 "$OUT/pleos_demo.mp4" >>"$LOG" 2>&1 || true
adb exec-out sh -c "cat /sdcard/Android/data/$PKG/files/*.csv" > "$OUT/export.csv" 2>>"$LOG" || true
result export_csv_bytes "$(stat -c %s "$OUT/export.csv" 2>/dev/null || echo 0)"

log "== 로그"
adb logcat -d -v time > "$OUT/logcat_all.txt" 2>/dev/null
grep -n "FATAL EXCEPTION" -A 40 "$OUT/logcat_all.txt" > "$OUT/crash.txt" || true
result fatal_exceptions_any_process "$(grep -c 'FATAL EXCEPTION' "$OUT/logcat_all.txt")"
result fatal_exceptions_app "$(grep -A2 'FATAL EXCEPTION' "$OUT/logcat_all.txt" | grep -c "Process: $PKG")"
APP_PID=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
grep -iE "chagok|pleos|CRN|vsomeip|VehicleSdk|ai\.pleos|AndroidRuntime|CarPropertyService.*(denied|permission)" "$OUT/logcat_all.txt" | tail -n 1500 > "$OUT/logcat_app.txt" || true
[ -n "$APP_PID" ] && grep -E " $APP_PID\)| $APP_PID " "$OUT/logcat_all.txt" | tail -n 600 > "$OUT/logcat_pid.txt" || true
tail -n 1500 "$OUT/logcat_all.txt" > "$OUT/logcat_tail.txt"; rm -f "$OUT/logcat_all.txt"
result alive_at_end "$(adb shell pidof $PKG >/dev/null 2>&1 && echo yes || echo no)"
adb emu kill >/dev/null 2>&1 || true
log "done"
exit 0
