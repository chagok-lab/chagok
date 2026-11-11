package com.chagok.app.engine

/**
 * 차량 신호를 받기 시작한 직후의 짧은 대기.
 *
 * 차량 서비스는 변화 콜백을 등록한 직후에 이전에 보관해 둔 값을 먼저 보내고, 곧이어 현재 값을 보내는 경우가 있습니다.
 * (Pleos Connect 에뮬레이터 2.1.0 에서 확인: 앱 실행 직후 이전 누적거리가 먼저 오고 약 0.1초 뒤 현재 값이 옴)
 * 그래서 첫 값을 받은 뒤 windowMs 동안은 값을 화면에만 보여 주고, 그 뒤에 받은 값부터 운행 기준점과
 * 누락 구간 판단에 씁니다. 이 시간 동안 들어온 값 가운데 가장 큰 값은 OdometerFilter 가 유지합니다.
 */
class StartupSettle(
    private val windowMs: Long = 3_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var firstAtMs: Long? = null

    /** 값을 받을 때마다 호출: 첫 값을 받은 뒤 windowMs 가 지났으면 true */
    fun ready(): Boolean {
        val now = clock()
        val first = firstAtMs
        if (first == null) {
            firstAtMs = now
            return windowMs <= 0L
        }
        return now - first >= windowMs
    }

    fun reset() {
        firstAtMs = null
    }
}
