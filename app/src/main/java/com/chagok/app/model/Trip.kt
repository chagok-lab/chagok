package com.chagok.app.model

/** 업무용승용차 운행기록부의 용도 구분 (서식 ⑧ 출퇴근, ⑨ 일반업무) */
enum class Purpose(val label: String) {
    COMMUTE("출퇴근"),
    BUSINESS("일반업무"),
    PRIVATE("비업무"),
    UNCONFIRMED("미확정");

    val isBusinessUse: Boolean get() = this == COMMUTE || this == BUSINESS
}

/**
 * 원장(ledger)의 한 줄. 원장은 추가만 가능하며, 용도 확정·수정은 같은 tripId의 새 revision으로 쌓입니다.
 * startOdoKm / endOdoKm 는 차량 계기판 누적거리(Vehicle SDK Odometer) 원천 값입니다.
 */
data class Trip(
    val seq: Long,                 // 원장 순번 (0부터)
    val tripId: Long,              // 운행 식별자
    val revision: Int,             // 0 = 차량 원천 기록 봉인, 1 이상 = 용도 확정·수정 이력
    val startEpochMs: Long,
    val endEpochMs: Long,
    val startOdoKm: Double,        // 서식 ⑤ 주행 전 계기판의 거리
    val endOdoKm: Double,          // 서식 ⑥ 주행 후 계기판의 거리
    val destination: String?,      // 서식 ⑩ 비고(방문처). 비업무 확정 시 저장하지 않음
    val purpose: Purpose,
    val suggested: Purpose,        // 추천 용도
    val user: String,              // 서식 ④ 사용자(부서, 성명)
    val source: String,            // PLEOS | SIMULATOR
    val note: String?,             // 수정 사유
    val prevHash: String,
    val hash: String,
) {
    /** 서식 ⑦ 주행거리 */
    val distanceKm: Double get() = endOdoKm - startOdoKm
}
