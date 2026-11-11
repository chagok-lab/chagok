#!/usr/bin/env bash
# Pleos Connect 에뮬레이터 시스템 이미지를 내려받아 AVD(pleos)를 만듭니다. (GitHub Actions 에서 사용)
# 공식 안내: Android Studio > SDK Manager > SDK Update Sites 에 아래 XML 주소를 추가하는 절차를 명령줄로 옮긴 것입니다.
# 실패해도 0 으로 끝나며 결과는 $RUNNER_TEMP/pleos/result.txt 에 남깁니다.
set -u
OUT="${RUNNER_TEMP:-/tmp}/pleos"
mkdir -p "$OUT"
exec > >(tee -a "$OUT/setup.log") 2>&1
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
BIN="$SDK/cmdline-tools/latest/bin"
XML_URL="${PLEOS_XML_URL:-https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml}"
result() { echo "$1=$2" >> "$OUT/result.txt"; echo "RESULT $1=$2"; }

echo "== host"
df -h / | tail -1; nproc; free -g | sed -n 1,2p; ls -la /dev/kvm || true
ls "$SDK" || true

echo "== SDK 저장소 XML"
if curl -fsSL --retry 3 -o "$OUT/crp-sys-img.xml" "$XML_URL"; then
  result xml_download yes
else
  result xml_download no
  curl -sS -o /dev/null -D - "$XML_URL" | head -20
  exit 0
fi
head -c 5000 "$OUT/crp-sys-img.xml"; echo
python3 scripts/ci/pleos_repo.py repo "$OUT/crp-sys-img.xml" "$XML_URL" > "$OUT/repo.env"
cat "$OUT/repo.env"
# shellcheck disable=SC1091
source "$OUT/repo.env"
if [ -z "${PKG_PATH:-}" ]; then result package_found no; exit 0; fi
result package_found yes
IMG_DIR="$SDK/$(echo "$PKG_PATH" | tr ';' '/')"

echo "== 에뮬레이터·플랫폼 도구"
yes | "$BIN/sdkmanager" --licenses > /dev/null 2>&1 || true
yes | "$BIN/sdkmanager" --install "emulator" "platform-tools" "platforms;android-34" > "$OUT/sdk_base.log" 2>&1 || tail -20 "$OUT/sdk_base.log"
"$SDK/emulator/emulator" -version 2>&1 | head -3 || true

echo "== 시스템 이미지 설치 (1) sdkmanager + 사용자 저장소"
mkdir -p "$HOME/.android"
{
  echo "### User Sources for Android SDK Manager"
  echo "count=1"
  echo "src00=${XML_URL/:/\\:}"
  echo "disp00=Pleos Connect System Image"
  echo "enabled00=true"
} > "$HOME/.android/repositories.cfg"
cat "$HOME/.android/repositories.cfg"
"$BIN/sdkmanager" --list 2>/dev/null | grep -i -E "pleos|connect" | head -20 || true
INSTALLED=no
if yes | "$BIN/sdkmanager" --install "$PKG_PATH" > "$OUT/sdk_image.log" 2>&1; then
  ls "$IMG_DIR"/*.img > /dev/null 2>&1 && INSTALLED=sdkmanager
fi
tail -5 "$OUT/sdk_image.log" || true

if [ "$INSTALLED" = no ]; then
  echo "== 시스템 이미지 설치 (2) 직접 내려받기: $ZIP_URL"
  if curl -fSL --retry 3 -o "$RUNNER_TEMP/pleos_img.zip" "$ZIP_URL"; then
    ls -la "$RUNNER_TEMP/pleos_img.zip"
    rm -rf "$RUNNER_TEMP/pleos_unz"; mkdir -p "$RUNNER_TEMP/pleos_unz" "$IMG_DIR"
    unzip -q "$RUNNER_TEMP/pleos_img.zip" -d "$RUNNER_TEMP/pleos_unz" && rm -f "$RUNNER_TEMP/pleos_img.zip"
    TOP="$(ls "$RUNNER_TEMP/pleos_unz")"
    if [ "$(echo "$TOP" | wc -l)" = 1 ] && [ -d "$RUNNER_TEMP/pleos_unz/$TOP" ]; then
      mv "$RUNNER_TEMP/pleos_unz/$TOP"/* "$IMG_DIR"/
    else
      mv "$RUNNER_TEMP/pleos_unz"/* "$IMG_DIR"/
    fi
    ls "$IMG_DIR"/*.img > /dev/null 2>&1 && INSTALLED=manual
  else
    curl -sS -o /dev/null -D - "$ZIP_URL" | head -20
  fi
fi
result image_installed "$INSTALLED"
[ "$INSTALLED" = no ] && exit 0

echo "== 시스템 이미지 내용"
ls -la "$IMG_DIR" | head -60
du -sh "$IMG_DIR" || true
for f in source.properties build.prop package.xml; do
  [ -f "$IMG_DIR/$f" ] && { echo "--- $f"; head -c 3000 "$IMG_DIR/$f"; echo; cp "$IMG_DIR/$f" "$OUT/image_$f" 2>/dev/null; }
done
DEV_ID=""; DEV_W=""; DEV_H=""; DEV_DENSITY=""
if [ -f "$IMG_DIR/devices.xml" ]; then
  cp "$IMG_DIR/devices.xml" "$OUT/devices.xml"
  python3 scripts/ci/pleos_repo.py device "$IMG_DIR/devices.xml" > "$OUT/device.env"
  cat "$OUT/device.env"
  # shellcheck disable=SC1091
  source "$OUT/device.env"
fi
find "$IMG_DIR" -maxdepth 2 -name "*.ini" -o -maxdepth 2 -name "advancedFeatures*" | head -20
for f in "$IMG_DIR"/advancedFeatures.ini "$IMG_DIR"/data/misc/emulator/config.ini; do
  [ -f "$f" ] && { echo "--- $f"; cat "$f"; }
done

echo "== AVD 만들기"
"$BIN/avdmanager" list device -c > "$OUT/avd_devices.txt" 2>&1 || true
grep -i -E "pleos|connect|automotive" "$OUT/avd_devices.txt" || true
AVD_OK=no
if [ -n "$DEV_ID" ] && echo no | "$BIN/avdmanager" create avd --force -n pleos -k "$PKG_PATH" -d "$DEV_ID" > "$OUT/avd_create.log" 2>&1; then
  AVD_OK=avdmanager
elif echo no | "$BIN/avdmanager" create avd --force -n pleos -k "$PKG_PATH" >> "$OUT/avd_create.log" 2>&1; then
  AVD_OK=avdmanager_default_device
fi
tail -5 "$OUT/avd_create.log" 2>/dev/null || true
AVD_DIR="$HOME/.android/avd/pleos.avd"
if [ "$AVD_OK" = no ]; then
  echo "avdmanager 실패 → 설정 파일을 직접 작성"
  mkdir -p "$AVD_DIR"
  printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/pleos.avd\ntarget=android-%s\n' "$AVD_DIR" "${PKG_API:-34}" > "$HOME/.android/avd/pleos.ini"
  {
    echo "AvdId=pleos"
    echo "PlayStore.enabled=false"
    echo "abi.type=${PKG_ABI:-x86_64}"
    echo "avd.ini.displayname=pleos"
    echo "avd.ini.encoding=UTF-8"
    echo "hw.cpu.arch=x86_64"
    echo "hw.lcd.width=${DEV_W:-1920}"
    echo "hw.lcd.height=${DEV_H:-1080}"
    echo "hw.lcd.density=${DEV_DENSITY:-160}"
    echo "image.sysdir.1=$(echo "$PKG_PATH" | tr ';' '/')/"
    echo "tag.id=${PKG_TAG:-android-automotive}"
    echo "tag.display=${PKG_TAG_DISPLAY:-Automotive}"
  } > "$AVD_DIR/config.ini"
  AVD_OK=manual
fi
# 공통 설정: RAM 4GB(공식 안내), 콜드 부트, 소프트웨어 렌더링(CI 에 GPU 없음)
CFG="$AVD_DIR/config.ini"
set_cfg() { if grep -q "^$1=" "$CFG"; then sed -i "s|^$1=.*|$1=$2|" "$CFG"; else echo "$1=$2" >> "$CFG"; fi; }
set_cfg hw.ramSize 4096
set_cfg hw.cpu.ncore 4
set_cfg hw.gpu.enabled yes
set_cfg hw.gpu.mode swiftshader_indirect
set_cfg hw.keyboard yes
set_cfg disk.dataPartition.size 6G
set_cfg fastboot.forceColdBoot yes
set_cfg hw.camera.back none
set_cfg hw.camera.front none
echo "--- config.ini"; cat "$CFG"
cp "$CFG" "$OUT/avd_config.ini"
result avd_created "$AVD_OK"
df -h / | tail -1
exit 0
