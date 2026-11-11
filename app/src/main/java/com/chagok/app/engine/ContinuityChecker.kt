package com.chagok.app.engine

import kotlin.math.roundToLong

/**
 * 계기판 누적거리 연속성 검사.
 *
 * 앱이 꺼져 있었거나(종료·재부팅) 차량 신호를 받지 못한 동안 달린 거리는 사라지지 않고
 * 계기판 누적거리에 남습니다. 마지막 운행의 '주행 후 계기판 거리(⑥)'와 지금 계기판 값의 차이를
 * 누락 구간으로 찾아, 용도 확인이 필요한 운행으로 채웁니다. 서식 ⑤⑥이 끊김 없이 이어지게 합니다.
 */
object ContinuityChecker {
    /** 이보다 작은 차이는 계기판 표시 단위(0.1 km) 오차로 보고 무시합니다. */
    const val MIN_GAP_KM = 0.1

    /** 이보다 큰 차이는 계기판 교체·값 초기화 가능성이 있어 자동으로 채우지 않습니다. */
    const val MAX_GAP_KM = 2_000.0

    sealed interface Result {
        /** 누락 없음 */
        data object Continuous : Result
        /** 누락 구간: lastEndOdoKm → currentOdoKm */
        data class Gap(val fromOdoKm: Double, val toOdoKm: Double, val km: Double) : Result
        /** 값이 줄었거나 지나치게 커서 자동 보완하지 않음(사용자 확인 필요) */
        data class Suspicious(val fromOdoKm: Double, val toOdoKm: Double) : Result
    }

    fun check(lastEndOdoKm: Double?, currentOdoKm: Double): Result {
        val last = lastEndOdoKm ?: return Result.Continuous
        val raw = currentOdoKm - last
        return when {
            raw <= -MIN_GAP_KM + EPS -> Result.Suspicious(last, currentOdoKm)   // 0.1 km 이상 줄어듦
            raw < MIN_GAP_KM - EPS -> Result.Continuous                          // 표시 단위 미만 차이
            raw > MAX_GAP_KM -> Result.Suspicious(last, currentOdoKm)
            else -> Result.Gap(last, currentOdoKm, round1(raw))
        }
    }

    /**
     * 새 운행 구간이 직전에 기록한 구간과 겹치면 겹치지 않는 부분만 남깁니다(서식 ⑤는 직전 운행의 ⑥보다 작을 수 없음).
     * 돌려주는 값은 새 운행의 시작 누적거리이며, 남는 거리가 0.1 km 미만이면 null(기록하지 않음)입니다.
     * 직전 구간과 전혀 겹치지 않는 값(계기판 교체 등)은 그대로 둡니다.
     */
    fun nonOverlappingStart(lastStartOdoKm: Double?, lastEndOdoKm: Double?, startOdoKm: Double, endOdoKm: Double): Double? {
        var start = startOdoKm
        if (lastStartOdoKm != null && lastEndOdoKm != null) {
            val overlaps = startOdoKm < lastEndOdoKm - EPS && endOdoKm > lastStartOdoKm + EPS
            if (overlaps) start = lastEndOdoKm
        }
        return if (endOdoKm - start >= MIN_GAP_KM - EPS) start else null
    }

    private const val EPS = 1e-6

    private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0
}
