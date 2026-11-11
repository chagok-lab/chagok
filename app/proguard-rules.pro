# release 빌드는 난독화를 끈 상태(isMinifyEnabled = false)로 제출합니다.
# 난독화를 켤 경우 Pleos SDK 리플렉션 호출 대상(도메인 API, listener 인터페이스)을 유지해야 합니다.
-keep class ai.pleos.playground.** { *; }
