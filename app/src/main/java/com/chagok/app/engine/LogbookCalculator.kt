package com.chagok.app.engine

import com.chagok.app.model.Purpose
import com.chagok.app.model.Trip
import kotlin.math.min
import kotlin.math.roundToLong

/** 서식 ⑪ 총주행거리, ⑫ 업무용 사용거리, ⑬ 업무사용비율과 비용 인정 예시를 계산합니다. */
object LogbookCalculator {

    /** 운행기록 미작성 시 차량 1대당 연간 비용 인정 한도 (법인세법 시행령 제50조의2 제7항) */
    const val NO_LOG_LIMIT_WON = 15_000_000L

    data class Summary(
        val totalKm: Double,
        val commuteKm: Double,
        val businessKm: Double,
        val privateKm: Double,
        val unconfirmedCount: Int,
        val tripCount: Int,
    ) {
        val businessUseKm: Double get() = commuteKm + businessKm
        val ratio: Double get() = if (totalKm > 0.0) businessUseKm / totalKm else 0.0
    }

    /** 같은 tripId 의 마지막 revision 만 남긴 현재 기록 (원장 순서 유지) */
    fun latest(ledger: List<Trip>): List<Trip> =
        ledger.groupBy { it.tripId }.values.map { revs -> revs.maxBy { it.revision } }.sortedBy { it.startEpochMs }

    fun summarize(ledger: List<Trip>): Summary {
        val trips = latest(ledger)
        fun sum(p: Purpose) = trips.filter { it.purpose == p }.sumOf { it.distanceKm }
        return Summary(
            totalKm = trips.sumOf { it.distanceKm },
            commuteKm = sum(Purpose.COMMUTE),
            businessKm = sum(Purpose.BUSINESS),
            privateKm = sum(Purpose.PRIVATE),
            unconfirmedCount = trips.count { it.purpose == Purpose.UNCONFIRMED },
            tripCount = trips.size,
        )
    }

    /** 운행기록 작성 시: 관련비용 × 업무사용비율 / 미작성 시: min(관련비용, 1,500만원) */
    fun deductibleWon(annualCostWon: Long, ratio: Double, hasLog: Boolean): Long =
        if (hasLog) (annualCostWon * ratio).roundToLong() else min(annualCostWon, NO_LOG_LIMIT_WON)
}
