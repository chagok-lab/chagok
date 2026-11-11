package com.chagok.app.export

import com.chagok.app.engine.LogbookCalculator
import com.chagok.app.model.Purpose
import com.chagok.app.model.Trip
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 국세청 「업무용승용차 운행기록부」 별지 서식 항목 순서로 CSV(엑셀 호환, UTF-8 BOM)를 만듭니다.
 * ③ 사용일자(요일) ④ 사용자(부서, 성명) ⑤ 주행 전 계기판 ⑥ 주행 후 계기판 ⑦ 주행거리 ⑧ 출퇴근 ⑨ 일반업무 ⑩ 비고
 */
object NtsLogbookExporter {
    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN)

    fun toCsv(ledger: List<Trip>, carModel: String, plate: String, zone: ZoneId = ZoneId.of("Asia/Seoul")): String {
        val trips = LogbookCalculator.latest(ledger)
        val s = LogbookCalculator.summarize(ledger)
        val sb = StringBuilder("﻿")
        sb.append("업무용승용차 운행기록부\n")
        sb.append("① 차종,").append(q(carModel)).append(",② 자동차등록번호,").append(q(plate)).append('\n')
        if (trips.any { it.source == "SIMULATOR" }) {
            sb.append("※ 시뮬레이터로 재현한 시연용 기록이 포함되어 있습니다\n")
        }
        sb.append("③ 사용일자(요일),④ 사용자(부서·성명),⑤ 주행 전 계기판의 거리(km),⑥ 주행 후 계기판의 거리(km),")
            .append("⑦ 주행거리(km),⑧ 출·퇴근용(km),⑨ 일반 업무용(km),⑩ 비고\n")
        trips.forEach { t ->
            val day = Instant.ofEpochMilli(t.startEpochMs).atZone(zone).format(dayFormat)
            val commute = if (t.purpose == Purpose.COMMUTE) km(t.distanceKm) else ""
            val business = if (t.purpose == Purpose.BUSINESS) km(t.distanceKm) else ""
            val gap = t.source.endsWith("_GAP")   // 앱이 꺼져 있던 동안의 주행(계기판 차이로 보완)
            val note = when (t.purpose) {
                Purpose.PRIVATE -> "비업무"
                Purpose.UNCONFIRMED -> if (gap) "용도 미확정(앱 미실행 구간 자동 보완)" else "용도 미확정"
                else -> listOfNotNull(t.destination, if (gap) "앱 미실행 구간 자동 보완" else null).joinToString(" / ")
            }
            sb.append(listOf(q(day), q(t.user), km(t.startOdoKm), km(t.endOdoKm), km(t.distanceKm), commute, business, q(note))
                .joinToString(",")).append('\n')
        }
        sb.append('\n')
        sb.append("⑪ 사업연도 총주행 거리(km),").append(km(s.totalKm)).append('\n')
        sb.append("⑫ 사업연도 업무용 사용거리(km),").append(km(s.businessUseKm)).append('\n')
        sb.append("⑬ 업무사용비율(⑫/⑪),").append(String.format(Locale.ROOT, "%.1f%%", s.ratio * 100)).append('\n')
        return sb.toString()
    }

    fun write(dir: File, fileName: String, csv: String): File {
        dir.mkdirs()
        val f = File(dir, fileName)
        f.writeText(csv, Charsets.UTF_8)
        return f
    }

    private fun km(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
}
