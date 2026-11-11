#!/usr/bin/env bash
# 에뮬레이터에 release APK 를 설치·실행하고 화면을 저장합니다 (GitHub Actions 의 Emulator smoke test 에서 사용).
# 차량 신호가 없는 일반 Android 기기이므로 앱 안의 시뮬레이터로 운행 3건을 재현합니다.
# 실패해도 0 으로 끝나며, 결과는 $RUNNER_TEMP/smoke/result.txt 에 남깁니다.
set -u
OUT="${RUNNER_TEMP:-/tmp}/smoke"
mkdir -p "$OUT"
LOG="$OUT/smoke.log"
PKG=com.chagok.app
APK="$(ls apk/*.apk | head -1)"
UI="$OUT/ui.xml"
REC_T0=0

log() { echo "[smoke $(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
ev() {   # 녹화 시작 기준 경과 시간과 함께 사건을 기록 (영상 자막 위치 계산용)
  local now; now=$(date +%s.%N)
  python3 -c "print('%.1f\t%s' % ($now - $REC_T0, '$1'))" >> "$OUT/events.tsv"
  log "EVENT $1"
}
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>>"$LOG" || log "screencap failed: $1"; }
dump() {
  rm -f "$UI"
  adb shell uiautomator dump /sdcard/ui.xml >>"$LOG" 2>&1
  adb exec-out cat /sdcard/ui.xml > "$UI" 2>>"$LOG"
  [ -n "${1:-}" ] && cp "$UI" "$OUT/ui_$1.xml" 2>/dev/null
}
has() { python3 scripts/ci/ui.py "$UI" has "$1"; }
tap() {
  local xy
  xy=$(python3 scripts/ci/ui.py "$UI" tap "$1")
  if [ -n "$xy" ]; then log "tap '$1' at $xy"; adb shell input tap $xy; return 0; fi
  log "tap target not found: $1"; return 1
}
wait_text() {   # wait_text <text> <seconds>
  local end=$(( $(date +%s) + $2 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    dump
    [ "$(has "$1")" = yes ] && return 0
    sleep 1
  done
  return 1
}
alive() { if adb shell pidof "$PKG" >/dev/null 2>&1; then echo yes; else echo no; fi; }
result() { echo "$1=$2" >> "$OUT/result.txt"; log "RESULT $1=$2"; }

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

log "device: android $(adb shell getprop ro.build.version.release) sdk $(adb shell getprop ro.build.version.sdk) | $(adb shell wm size) | $(adb shell wm density)"
log "apk: $APK ($(stat -c %s "$APK") bytes, sha256 $(sha256sum "$APK" | cut -c1-16))"
adb logcat -c || true

log "install"
adb install -r "$APK" 2>&1 | tee -a "$LOG"
adb shell am start -W -n "$PKG/.MainActivity" 2>&1 | tee -a "$LOG"
sleep 3
result alive_after_launch "$(alive)"

# 화면 녹화 시작 (실패해도 계속 진행)
REC_T0=$(date +%s.%N)
( adb shell screenrecord --time-limit 170 --bit-rate 6000000 --size 1280x800 /sdcard/chagok_demo.mp4 >>"$LOG" 2>&1 ) &
sleep 2
ev launch
shot 01_launch

# 차량 신호가 없는 기기: 5초 뒤 나타나는 '시뮬레이터로 시연하기' 버튼을 누름
if wait_text '시뮬레이터로 시연하기' 20; then
  cp "$UI" "$OUT/ui_01_no_signal.xml"; shot 02_no_signal; ev no_signal
  result no_signal_button yes
  sleep 2
  tap '시뮬레이터로 시연하기'; ev simulator_on
else
  result no_signal_button no
  xy=$(python3 scripts/ci/ui.py "$UI" tap_checkable)
  [ -n "$xy" ] && adb shell input tap $xy
fi
sleep 3; dump 03_idle; shot 03_idle
result simulator_ready "$(has '다음 운행 재생')"

trip 1 '출퇴근'
trip 2 '일반업무'
trip 3 '비업무'

dump 08_report; shot 08_report; ev report
result report_has_ratio "$(has '업무사용비율')"
sleep 3

# ---- 내보내기
if tap '서식 내보내기'; then
  ev export
  sleep 3; dump 09_export; shot 09_export
  result export_dialog "$(has '서식을 저장했습니다')"
  sleep 3
  tap '닫기'; ev export_closed; sleep 2
fi
adb exec-out sh -c "cat /sdcard/Android/data/$PKG/files/*.csv" > "$OUT/export.csv" 2>>"$LOG" || true
result export_csv_bytes "$(stat -c %s "$OUT/export.csv" 2>/dev/null || echo 0)"

# ---- 녹화 종료
ev end
adb shell pkill -INT screenrecord >>"$LOG" 2>&1 || true
sleep 4
adb pull /sdcard/chagok_demo.mp4 "$OUT/chagok_demo.mp4" >>"$LOG" 2>&1 || true

# ---- 앱을 껐다 켜도 기록이 남는지
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >>"$LOG" 2>&1
sleep 9
dump 10_relaunch; shot 10_relaunch
result alive_after_relaunch "$(alive)"
result records_kept_after_relaunch "$(has '오늘 운행 3건')"

adb logcat -d -v time > "$OUT/logcat_all.txt" 2>/dev/null
grep -n "FATAL EXCEPTION" -A 40 "$OUT/logcat_all.txt" > "$OUT/crash.txt" || true
grep -iE "chagok|pleos|AndroidRuntime" "$OUT/logcat_all.txt" | tail -n 800 > "$OUT/logcat_app.txt" || true
result fatal_exceptions "$(grep -c 'FATAL EXCEPTION' "$OUT/logcat_all.txt")"
tail -n 2000 "$OUT/logcat_all.txt" > "$OUT/logcat_tail.txt"; rm -f "$OUT/logcat_all.txt"
python3 scripts/ci/ui.py "$OUT/ui_10_relaunch.xml" texts > "$OUT/texts_10_relaunch.txt" 2>/dev/null || true
python3 scripts/ci/ui.py "$OUT/ui_08_report.xml" texts > "$OUT/texts_08_report.txt" 2>/dev/null || true
log "done"
exit 0
