package com.chagok.app

import com.chagok.app.engine.TripDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {
    private var now = 0L
    private fun detector() = TripDetector(
        arrivedIdleMs = 120_000L, parkedIdleMs = 600_000L, doorGraceMs = 60_000L, clock = { now },
    )

    @Test
    fun startsWhenOdometerIncreasesAndEndsAfterDoorOpenAndNoMovement() {
        val d = detector()
        assertNull(d.onOdometer(12_480.2))                       // 기준점
        val started = d.onOdometer(12_480.4)
        assertTrue(started is TripDetector.Event.Started)
        d.onNaviDestination("회사")
        now += 60_000; d.onOdometer(12_498.6)
        val doorAt = now
        val arrived = d.onDriverDoor(true) as TripDetector.Event.Arrived   // 문 열림: 카드용 잠정 도착
        assertEquals(12_498.6, arrived.endOdoKm, 1e-9)
        assertEquals(TripDetector.State.DRIVING, d.state)        // 도어만으로는 종료하지 않음
        now += 59_000; assertNull(d.tick())
        now += 2_000
        val ended = d.tick() as TripDetector.Event.Ended         // 문을 연 뒤 60초 정지 → 종료 확정
        assertEquals(12_480.2, ended.startOdoKm, 1e-9)           // 서식 ⑤ = 출발 직전 계기판 값
        assertEquals(12_498.6, ended.endOdoKm, 1e-9)             // 서식 ⑥
        assertEquals(doorAt, ended.endEpochMs)                   // 종료 시각 = 문을 연 시각
        assertEquals("회사", ended.destination)
        assertEquals(TripDetector.State.IDLE, d.state)
    }

    @Test
    fun briefDoorOpenThenDrivingAgainKeepsOneTrip() {
        val d = detector()
        d.onOdometer(200.0); d.onOdometer(200.3)                 // 운행 시작
        now += 600_000; d.onOdometer(210.0)
        assertTrue(d.onDriverDoor(true) is TripDetector.Event.Arrived)   // 편의점 앞 잠깐 정차, 문 열림
        now += 30_000; assertNull(d.tick())
        assertNull(d.onDriverDoor(false))
        now += 10_000
        assertEquals(TripDetector.Event.Resumed, d.onOdometer(210.2))    // 다시 출발 → 같은 운행
        now += 50_000; assertNull(d.tick())                      // 문 연 시점 기준 90초가 지나도 계속 운행
        assertEquals(TripDetector.State.DRIVING, d.state)
        now += 300_000; d.onOdometer(215.0)
        d.onDriverDoor(true)
        now += 61_000
        val ended = d.tick() as TripDetector.Event.Ended
        assertEquals(200.0, ended.startOdoKm, 1e-9)              // 한 건으로 이어서 기록
        assertEquals(215.0, ended.endOdoKm, 1e-9)
    }

    @Test
    fun endsAfterNaviArrivalAndIdle() {
        val d = detector()
        d.onOdometer(100.0); d.onOdometer(100.5)
        d.onNaviArrived()
        now += 119_000; assertNull(d.tick())
        now += 2_000
        assertTrue(d.tick() is TripDetector.Event.Ended)
    }

    @Test
    fun ignoresDoorWhenNotDrivingAndTinyMoves() {
        val d = detector()
        d.onOdometer(50.0)
        assertNull(d.onDriverDoor(true))
        assertNull(d.onOdometer(50.05))                          // 기준 0.1 km 미만
        assertEquals(TripDetector.State.IDLE, d.state)
    }
}
