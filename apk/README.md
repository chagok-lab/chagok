# apk/

공모전 제출용 release APK 위치입니다.

- 파일: `apk/개인참가_차곡_release.apk`
- 빌드: GitHub Actions(`.github/workflows/build-apk.yml`)가 소스에서 `./gradlew assembleRelease` 로 빌드해 이 폴더에 올립니다.
- 설치: `adb install -r "apk/개인참가_차곡_release.apk"`
- 파일명은 `팀명_앱이름_release.apk` 규칙을 따랐습니다. 개인 참가이고 저장소에 실명을 적지 않기 위해 팀명 자리에 `개인참가` 를 적었습니다.
