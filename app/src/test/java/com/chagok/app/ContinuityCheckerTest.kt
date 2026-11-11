package com.chagok.app

import com.chagok.app.engine.ContinuityChecker
import com.chagok.app.export.NtsLogbookExporter
import com.chagok.app.model.Purpose
import com.chagok.app.model.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityCheckerTest {

    @Test
    fun findsDistanceDrivenWhileAppWasOff() {
        val r = ContinuityChecker.check(lastEndOdoKm = 12_556.3, currentOdoKm = 12_568.7)
        assertTrue(r is ContinuityChecker.Result.Gap)
        r as ContinuityChecker.Result.Gap
        assertEquals(12.4, r.km, 1e-9)                       // 앱이 꺼져 있던 동안 12.4 km
        assertEquals(12_556.3, r.fromOdoKm, 1e-9)            // 서식 ⑤ = 마지막 운행의 ⑥
        assertEquals(12_568.7, r.toOdoKm, 1e-9)
    }

    @Test
    fun ignoresDisplayRoundingAndFirstUse() {
        assertEquals(ContinuityChecker.Result.Continuous, ContinuityChecker.check(12_556.3, 12_556.3))
        assertEquals(ContinuityChecker.Result.Continuous, ContinuityChecker.check(12_556.3, 12_556.35))
        assertEquals(ContinuityChecker.Result.Continuous, ContinuityChecker.check(null, 12_556.3))
    }

    @Test
    fun flagsDecreaseOrHugeJumpWithoutAutoFill() {
        assertTrue(ContinuityChecker.check(12_556.3, 12_400.0) is ContinuityChecker.Result.Suspicious)
        assertTrue(ContinuityChecker.check(12_556.3, 16_000.0) is ContinuityChecker.Result.Suspicious)
    }

    @Test
    fun exportMarksAutoFilledTripInRemarkColumn() {
        val gapTrip = Trip(
            seq = 0, tripId = 9, revision = 1, startEpochMs = 1_793_000_000_000, endEpochMs = 1_793_000_600_000,
            startOdoKm = 12_556.3, endOdoKm = 12_568.7, destination = null, purpose = Purpose.BUSINESS,
            suggested = Purpose.UNCONFIRMED, user = "영업 김○○", source = "PLEOS_GAP", note = null, prevHash = "", hash = "",
        )
        val csv = NtsLogbookExporter.toCsv(listOf(gapTrip), "그랜저", "12가 3456")
        assertTrue(csv.contains("앱 미실행 구간 자동 보완"))
        assertTrue(csv.contains("12556.3") || csv.contains("12,556.3"))
    }
}
