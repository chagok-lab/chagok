package com.chagok.app

import com.chagok.app.engine.ContinuityChecker
import com.chagok.app.engine.OdometerFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalGuardTest {

    @Test
    fun dropsStaleLowerOdometerValueButAcceptsPersistentReset() {
        val f = OdometerFilter(resetAfter = 5)
        assertEquals(12_534.7, f.accept(12_534.7)!!, 1e-9)
        assertNull(f.accept(12_522.3))                           // 앱 재실행 직후 한 번 더 들어온 이전 값: 버림
        assertEquals(12_534.7, f.accept(12_534.7)!!, 1e-9)
        assertEquals(12_535.0, f.accept(12_535.0)!!, 1e-9)
        repeat(4) { assertNull(f.accept(120.0)) }                // 작은 값이 이어지면
        assertEquals(120.0, f.accept(120.0)!!, 1e-9)             // 다섯 번째에 계기판 교체·초기화로 보고 받아들임
        assertEquals(120.4, f.accept(120.4)!!, 1e-9)
    }

    @Test
    fun newTripNeverOverlapsThePreviouslyRecordedRange() {
        // 직전 기록: 12,522.3 → 12,534.7 (앱 미실행 구간 자동 보완)
        assertNull(ContinuityChecker.nonOverlappingStart(12_522.3, 12_534.7, 12_522.3, 12_534.7))           // 같은 구간: 기록하지 않음
        assertEquals(12_534.7, ContinuityChecker.nonOverlappingStart(12_522.3, 12_534.7, 12_530.0, 12_540.0)!!, 1e-9)   // 겹친 부분 제외
        assertEquals(12_534.7, ContinuityChecker.nonOverlappingStart(12_522.3, 12_534.7, 12_534.7, 12_550.0)!!, 1e-9)   // 이어지는 운행은 그대로
        assertEquals(100.0, ContinuityChecker.nonOverlappingStart(12_522.3, 12_534.7, 100.0, 120.0)!!, 1e-9)            // 겹치지 않는 값(계기판 교체)은 그대로
        assertEquals(50.0, ContinuityChecker.nonOverlappingStart(null, null, 50.0, 60.0)!!, 1e-9)                       // 첫 기록
    }
}
