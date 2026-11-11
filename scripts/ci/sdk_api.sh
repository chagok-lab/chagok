#!/usr/bin/env bash
# Pleos SDK(AAR)의 공개 API 서명(javap)을 출력해, 연동 코드가 실제 메서드 이름·타입과 맞는지 확인합니다.
set -u
OUT="${RUNNER_TEMP:-/tmp}/sdk"
WORK="${RUNNER_TEMP:-/tmp}/sdk_work"
mkdir -p "$OUT" "$WORK"
REPO=https://nexus-playground.pleos.ai/repository/maven-releases
get() {   # get <group 경로> <artifact> <version>
  local base="$REPO/$1/$2/$3/$2-$3"
  curl -fsSL -o "$OUT/$2-$3.pom" "$base.pom" || echo "pom download failed: $2"
  if curl -fsSL -o "$WORK/$2.aar" "$base.aar"; then
    mkdir -p "$WORK/$2"; unzip -q -o "$WORK/$2.aar" -d "$WORK/$2"
    ls -la "$WORK/$2" | tee "$OUT/$2_aar_files.txt"
    [ -f "$WORK/$2/AndroidManifest.xml" ] && cp "$WORK/$2/AndroidManifest.xml" "$OUT/$2_AndroidManifest.xml"
    jar tf "$WORK/$2/classes.jar" | grep '\.class$' | sed 's/\.class$//; s#/#.#g' | sort > "$OUT/$2_classes.txt"
    echo "$2 classes: $(wc -l < "$OUT/$2_classes.txt")"
  else
    echo "aar download failed: $2"
  fi
}
get ai/pleos/playground Vehicle 2.3.2
get ai/pleos/playground NaviHelper 3.0.5

sig() {   # sig <artifact> <출력 파일> <정규식>
  local classes
  classes=$(grep -E "$3" "$OUT/$1_classes.txt" | grep -v '\$' | tr '\n' ' ')
  [ -z "$classes" ] && { echo "no classes for $3"; return; }
  # shellcheck disable=SC2086
  javap -cp "$WORK/$1/classes.jar" -public $classes > "$OUT/$2" 2>&1
  echo "$2: $(wc -l < "$OUT/$2") lines"
}
sig Vehicle vehicle_entry.txt '^ai\.pleos\.playground\.vehicle\.Vehicle$'
sig Vehicle vehicle_api.txt '^ai\.pleos\.playground\.vehicle\.api\.[A-Za-z0-9]+$'
sig Vehicle vehicle_listener.txt '^ai\.pleos\.playground\.vehicle\.listener\.'
sig Vehicle vehicle_area.txt '^ai\.pleos\.playground\.vehicle\.(api\.model\.area|constant\.area)\.'
sig Vehicle vehicle_gear.txt '^ai\.pleos\.playground\.vehicle\.(constant\.control\.VehicleGear|listener\.VehicleListener|exception\.[A-Za-z]+)$'
sig NaviHelper navi_helper.txt '^ai\.pleos\.playground\.navi\.helper\.'
# NaviHelper 의 데이터 클래스는 별도 모듈(의존 AAR)에 있으므로 pom 의 의존성도 내려받아 확인
grep -n -A4 "<dependency>" "$OUT/NaviHelper-3.0.5.pom" | head -60
for dep in $(python3 - "$OUT/NaviHelper-3.0.5.pom" <<'PY'
import sys, re
s = open(sys.argv[1]).read()
for g, a, v in re.findall(r'<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>', s):
    if g.startswith('ai.pleos'):
        print(f"{g.replace('.', '/')}:{a}:{v}")
PY
); do
  IFS=: read -r g a v <<< "$dep"
  [ "$a" = NaviHelper ] && continue
  get "$g" "$a" "$v"
  sig "$a" "navi_dep_${a}_data.txt" '\.(DestinationInfo|DestinationArrivedInfo|RouteStartInfo|NavigationStatus)$'
done
# 재정의할 리스너 파라미터의 null 허용 여부 (Kotlin 이 붙이는 NotNull/Nullable 표시)
javap -v -cp "$WORK/Vehicle/classes.jar" ai.pleos.playground.vehicle.listener.GearSelectionListener ai.pleos.playground.vehicle.listener.DistanceDrivenListener ai.pleos.playground.vehicle.listener.DoorPositionListener 2>&1 | grep -E "^  public|RuntimeInvisibleParameterAnnotations|Nullable|NotNull|parameter [0-9]" > "$OUT/listener_nullability.txt"
cat "$OUT/listener_nullability.txt"
grep -n -i -E "odometer|distance|door|carinfo|model|fuel|economy|efficien|gear|speed" "$OUT/vehicle_entry.txt" "$OUT/vehicle_api.txt" | cut -c1-220 | head -150
exit 0
