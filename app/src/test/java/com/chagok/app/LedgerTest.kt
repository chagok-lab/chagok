package com.chagok.app

import com.chagok.app.data.HashChain
import com.chagok.app.data.TripStore
import com.chagok.app.engine.LogbookCalculator
import com.chagok.app.engine.PurposeAdvisor
import com.chagok.app.model.Purpose
import com.chagok.app.model.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LedgerTest {
    private val zone = ZoneId.of("Asia/Seoul")

    private fun draft(id: Long, from: Double, to: Double, dest: String?) = Trip(
        seq = 0, tripId = id, revision = 0, startEpochMs = id * 1000, endEpochMs = id * 1000 + 500,
        startOdoKm = from, endOdoKm = to, destination = dest, purpose = Purpose.UNCONFIRMED,
        suggested = Purpose.BUSINESS, user = "영업 김○○", source = "SIMULATOR", note = null, prevHash = "", hash = "",
    )

    private fun sampleLedger(): List<Trip> {
        val l = mutableListOf<Trip>()
        l += TripStore.append(l, draft(1, 12_480.2, 12_498.6, "회사"))
        l += TripStore.revise(l, 1, Purpose.COMMUTE, null)!!
        l += TripStore.append(l, draft(2, 12_498.6, 12_522.3, "한빛정밀(가상)"))
        l += TripStore.revise(l, 2, Purpose.BUSINESS, null)!!
        l += TripStore.append(l, draft(3, 12_601.0, 12_648.2, "근교 공원"))
        l += TripStore.revise(l, 3, Purpose.PRIVATE, null)!!
        return l
    }

    @Test
    fun hashChainVerifiesAndDetectsTampering() {
        val l = sampleLedger()
        assertEquals(l.size, HashChain.verify(l))
        val tampered = l.toMutableList()
        tampered[2] = tampered[2].copy(endOdoKm = 12_530.0)        // 사후 수정 시도
        assertEquals(2, HashChain.verify(tampered))
    }

    @Test
    fun privatePurposeDropsDestination() {
        val latest = LogbookCalculator.latest(sampleLedger())
        assertNull(latest.first { it.tripId == 3L }.destination)
    }

    @Test
    fun summaryMatchesFormItems() {
        val s = LogbookCalculator.summarize(sampleLedger())
        assertEquals(18.4 + 23.7 + 47.2, s.totalKm, 1e-6)          // ⑪
        assertEquals(18.4 + 23.7, s.businessUseKm, 1e-6)            // ⑫
        assertEquals((18.4 + 23.7) / (18.4 + 23.7 + 47.2), s.ratio, 1e-9)  // ⑬
    }

    @Test
    fun deductibleFollowsNoLogLimit() {
        assertEquals(15_000_000L, LogbookCalculator.deductibleWon(25_000_000L, 0.866, hasLog = false))
        assertEquals(21_650_000L, LogbookCalculator.deductibleWon(25_000_000L, 0.866, hasLog = true))
        assertEquals(9_000_000L, LogbookCalculator.deductibleWon(9_000_000L, 0.5, hasLog = false))
    }

    @Test
    fun purposeAdvisorRules() {
        fun ms(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(Purpose.PRIVATE, PurposeAdvisor.suggest("한빛정밀", ms(2026, 11, 7, 14), zone))   // 토요일
        assertEquals(Purpose.COMMUTE, PurposeAdvisor.suggest("회사", ms(2026, 11, 2, 8), zone))
        assertEquals(Purpose.BUSINESS, PurposeAdvisor.suggest("한빛정밀", ms(2026, 11, 2, 10), zone))
        assertEquals(Purpose.COMMUTE, PurposeAdvisor.suggest(null, ms(2026, 11, 2, 18), zone))
        assertEquals(Purpose.BUSINESS, PurposeAdvisor.suggest(null, ms(2026, 11, 2, 14), zone))
    }
}
