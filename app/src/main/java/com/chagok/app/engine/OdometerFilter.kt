package com.chagok.app.engine

/**
 * 계기판 누적거리는 줄어들지 않는 값입니다. 차량 서비스가 직전보다 작은 값(이전에 보관해 둔 지연 값)을
 * 보내는 경우가 있어, 그런 값은 버립니다. 작은 값이 계속 이어지면(계기판 교체·초기화) 새 기준으로 받아들입니다.
 * (Pleos Connect 에뮬레이터 2.1.0 에서 앱 재실행 직후 이전 값이 한 번 더 들어오는 것을 확인하고 추가)
 */
class OdometerFilter(private val resetAfter: Int = 5) {
    private var last: Double? = null
    private var lowerCount = 0

    /** 받아들일 값이면 그 값, 버릴 값이면 null */
    fun accept(km: Double): Double? {
        val prev = last
        if (prev == null || km >= prev) {
            last = km
            lowerCount = 0
            return km
        }
        lowerCount++
        if (lowerCount >= resetAfter) {
            last = km
            lowerCount = 0
            return km
        }
        return null
    }

    fun reset() {
        last = null
        lowerCount = 0
    }
}
