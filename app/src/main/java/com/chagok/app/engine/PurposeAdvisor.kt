package com.chagok.app.engine

import com.chagok.app.model.Purpose
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * 용도 추천 규칙. 사용자는 추천값을 1탭으로 확인하거나 다른 버튼을 누릅니다.
 * 1) 주말 운행 → 비업무
 * 2) 목적지가 회사·집 키워드와 일치 → 출퇴근
 * 3) 그 밖의 목적지가 있으면 → 일반업무
 * 4) 목적지가 없으면 출퇴근 시간대(07~10시, 17~21시) → 출퇴근, 그 외 → 일반업무
 */
object PurposeAdvisor {
    val officeKeywords = listOf("회사", "사무실", "본사", "공장", "office")
    val homeKeywords = listOf("집", "자택", "home")

    fun suggest(destination: String?, endEpochMs: Long, zone: ZoneId = ZoneId.of("Asia/Seoul")): Purpose {
        val time = Instant.ofEpochMilli(endEpochMs).atZone(zone)
        if (time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY) return Purpose.PRIVATE
        val name = destination?.lowercase()
        if (name != null) {
            if ((officeKeywords + homeKeywords).any { name.contains(it.lowercase()) }) return Purpose.COMMUTE
            return Purpose.BUSINESS
        }
        val hour = time.hour
        return if (hour in 7..9 || hour in 17..20) Purpose.COMMUTE else Purpose.BUSINESS
    }
}
