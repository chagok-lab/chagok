# 차곡 (Chagok)

**운전만 하세요. 운행기록부는 차가 차곡차곡 씁니다.**
Pleos Connect 차량에서 계기판 누적거리(Vehicle SDK Odometer)와 내비게이션 목적지(NaviHelper SDK)를 직접 읽어, 운전자가 입력하지 않아도 국세청 서식 「업무용승용차 운행기록부」를 자동으로 완성하는 업무(Business) 앱입니다.

| 주행 중 (입력 차단) | 도착 직후 (1탭 확인) |
|---|---|
| ![주행 중](docs/screens/01_driving.png) | ![도착 직후](docs/screens/02_confirm.png) |
| **월간 리포트 (서식 미리보기)** | **절세 리포트·무결성 검증** |
| ![리포트](docs/screens/03_report.png) | ![절세](docs/screens/04_tax.png) |

> 위 화면은 UI 프로토타입(화면 목업)입니다. 앱 화면은 같은 구성으로 Jetpack Compose 코드를 작성했으며, 에뮬레이터 실행 화면은 검증 후 교체합니다.

---

## 1. 앱 이름과 한 줄 소개

- **앱 이름**: 차곡 (Chagok)
- **한 줄 소개**: 계기판 누적거리와 내비 목적지를 차량에서 직접 읽어 「업무용승용차 운행기록부」 서식에 맞춰 운행 기록을 자동으로 작성하고 내보내는 Pleos Connect 업무 앱

## 2. 실행 방법

### A. Android Studio (권장)
1. Android Studio(최신 버전), JDK 17 준비
2. 이 저장소를 열고 Gradle Sync (Pleos SDK 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/` 가 `settings.gradle.kts` 에 등록되어 있음)
3. Pleos Connect AVD 실행 후 `app` 실행 (Run)

### B. 명령줄
```bash
./gradlew assembleDebug        # 디버그 APK
./gradlew assembleRelease      # 제출용 release APK (로컬 디버그 키로 서명되어 바로 설치 가능)
./gradlew test                 # 단위 테스트 14개 (운행 판정, 누락 구간 보완, 해시 체인, 서식 계산·CSV, 용도 추천)
adb install -r app/build/outputs/apk/release/app-release.apk
```

### C. 시연 순서 (에뮬레이터)
1. 앱 실행 → 상단 상태가 `정차 중`인지 확인
2. Virtual Console(`adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`)의 **Motion** 에서 주행거리를 늘림 → 앱이 `기록 중`으로 바뀌고 입력이 잠김
3. **Convenience** 에서 운전석 도어 열기 → 운행 종료, 용도 확인 카드 표시 → 추천 버튼 1탭
4. `리포트`에서 업무사용비율(⑪⑫⑬) 확인 → `운행기록부 서식 내보내기` → CSV 파일 생성 확인(QR에는 요약 정보만 담김, 파일 전달은 개발 예정)
5. 앱을 끈 상태에서 Virtual Console로 주행거리를 늘린 뒤 다시 실행 → 누락 구간이 `용도 확인 필요` 운행으로 채워지는지 확인
6. 에뮬레이터 데이터가 고정되어 있으면 상단 `시뮬레이터` 스위치를 켜고 `다음 운행 재생`으로 같은 흐름을 재현(시뮬레이터는 도어 확정 대기 3초)

## 3. 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 (Android 14, API 34 시스템 이미지, Form Factor: Automotive, RAM 4GB, Cold boot) |
| 빌드 | AGP 8.8.2, Kotlin 2.0.0, Gradle 8.10.2, JDK 17, compileSdk/targetSdk 35, minSdk 33 |
| SDK | Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`, NaviHelper SDK `ai.pleos.playground:NaviHelper:3.0.5` |
| 퍼미션 | `pleos.car.permission.CAR_DRIVE`, `CAR_MILEAGE`, `CAR_DOORS`, `CAR_INFO`, `CAR_ENERGY`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` |
| Pleos Playground | Vehicle State·NaviHelper SDK는 별도 신청 없이 사용 가능 (프로젝트 생성만 필요) |

> Gradle Sync에서 Vehicle SDK 2.3.2를 받지 못하는 환경이면 `gradle/libs.versions.toml`의 `vehicleSdk`를 공식 샘플 버전 카탈로그 값(2.3.1)으로 바꿔 빌드합니다.

## 4. APK 위치

- `apk/차곡_release.apk` (release 빌드, 설치 가능한 서명 포함)
- 용량 한도를 넘으면 GitHub Releases 에 등록하고 이 항목에 Release 주소를 적습니다.

---

## 현재 구현 상태 (2026.11.11)

| 구분 | 내용 | 확인 방법 |
|---|---|---|
| 구현 | 운행 판정 상태 기계(도어 확정 대기·재출발 이어 기록), 누락 구간 보완, 해시 체인 원장, 서식 CSV, 용도 추천 규칙 | 로직 단위 테스트 14개 통과 |
| 코드 작성 | Vehicle SDK(Odometer·Door·CarInfo·Fuel)·NaviHelper 연동, Jetpack Compose 화면 | Pleos 에뮬레이터 빌드·실연동 검증 예정(11월) |
| 개발 예정 | QR 파일 전달, PDF 출력, 포그라운드 서비스 상시 기록, 확정 후 수정 화면, 계기판 값 수동 보정, Fleet 관리자 웹, 음성 확인(TTS·STT·Gleo AI) | 1차 결과 후~2차 PT |

절세 효과 수치(예: 추가 인정 최대 665만원)는 가정에 따른 계산 사례이며, 실제 인정 금액은 관련 법령, 업무전용자동차보험 가입, 감가상각비 한도, 개별 과세조건에 따라 달라집니다.

## 동작 원리

```
Vehicle SDK Odometer ──┐
Vehicle SDK Door ──────┼─> TripDetector (상태 기계) ─> 원장(해시 체인) ─> 1탭 용도 확정 ─> 서식 CSV
NaviHelper 목적지/도착 ─┘        IDLE ⇄ DRIVING
```

- **운행 시작**: 누적거리가 기준점 대비 0.1 km 이상 증가
- **운행 종료**: 운전석 도어가 열리면 용도 카드를 바로 띄우고(잠정 도착), 60초 동안 움직이지 않을 때 확정. 그 사이 다시 출발하면 같은 운행으로 이어감. 내비 도착 후 2분 정지, 장시간 10분 정지도 종료로 판정
- **누락 구간 보완**: 앱이 꺼져 있던 동안 달린 거리는 마지막 운행의 ⑥과 현재 계기판 값의 차이로 찾아 `용도 확인 필요` 운행으로 채움(0.1 km 미만 무시, 값이 줄거나 2,000 km 넘게 바뀌면 자동 보완 대신 확인 요청)
- 기어(P/D) API는 Vehicle SDK 공개 카탈로그에 없어(공식 샘플 README 명시) 위 신호를 결합합니다.
- **용도 추천**: 주말 → 비업무, 목적지가 회사·집 → 출퇴근, 그 밖의 목적지 → 일반업무, 목적지 없음 → 시간대 규칙
- **수정 이력 추적·무결성 검증**: 차량 원천 값(누적거리·시각)을 revision 0 으로 기록하고, 용도 확정·수정은 새 revision으로 쌓습니다. 한 줄만 고쳐도 이후 해시 검증이 실패합니다. 로컬 해시 체인은 사후 수정을 드러내는 장치이며 모든 조작을 막는다고 보장하지는 않습니다.
- **사생활 보호**: 비업무로 확정하면 목적지를 저장하지 않습니다. 기록은 차량 내 파일에 저장하고, 내보내기를 요청할 때만 밖으로 보냅니다.
- **주행 안전**: 앱 자체 '주행 중' 판정과 AAOS `CarUxRestrictions`를 함께 써서 주행 중 입력을 막습니다.

## 코드 구조

| 경로 | 역할 |
|---|---|
| `vehicle/PleosVehicleSignals.kt` | Vehicle SDK 연동 (Odometer, Door, CarInfo, Fuel) + NaviHelper 시작 |
| `vehicle/SdkReflect.kt` | 공개 문서에 타입 시그니처가 없는 도메인 API를 공식 샘플과 같은 방식으로 호출 |
| `navi/NaviSignals.kt` | NaviHelper SDK 연동 (목적지 조회, 도착 이벤트) |
| `vehicle/SimulatedVehicleSignals.kt` | 에뮬레이터 없이 시연하는 시뮬레이터 (같은 인터페이스) |
| `engine/TripDetector.kt` | 운행 판정 상태 기계 |
| `engine/PurposeAdvisor.kt` | 용도 추천 규칙 |
| `engine/LogbookCalculator.kt` | 서식 ⑪⑫⑬, 비용 인정 예시 계산 |
| `data/HashChain.kt`, `data/TripStore.kt` | 해시 체인 무결성 검증, 추가 전용 원장 |
| `engine/ContinuityChecker.kt` | 계기판 누적거리 연속성 검사(앱 미실행 구간 보완) |
| `export/NtsLogbookExporter.kt` | 업무용승용차 운행기록부 별지 서식 항목 순서의 CSV (UTF-8 BOM) |
| `ux/UxRestrictionWatcher.kt` | 주행 규제 상태 수신 |
| `ui/ChagokScreen.kt` | portrait(상태·1탭) / landscape(상태+리포트) 화면 |

## 참고

- Pleos Playground 문서: Vehicle SDK, NaviHelper SDK, 개발 환경 구성(에뮬레이터), Virtual Console
- 공식 샘플: `Pleos-Playground/pleos-sample-vehicle-app`, `Pleos-Playground/pleos-sample-navi-app` (빌드 구성과 SDK 호출 방식 참고)
- 법령: 법인세법 시행령 제50조의2, 법인세법 시행규칙 별지 서식 「업무용승용차 운행기록부」
