package com.chagok.app

import com.chagok.app.engine.CarText
import com.chagok.app.engine.DisplayScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayScaleTest {

    @Test
    fun keepsTabletLayoutAndEnlargesOnVehicleDisplay() {
        assertEquals(1.0, DisplayScale.forWindow(1280f, 740f).toDouble(), 1e-6)          // 설계 기준(태블릿 가로)
        assertEquals(1.3125, DisplayScale.forWindow(1680f, 1210f).toDouble(), 1e-4)      // Pleos Connect 에뮬레이터 2.1.0 앱 창
        assertEquals(1.6, DisplayScale.forWindow(4000f, 2400f).toDouble(), 1e-6)         // 최대 배율
        assertEquals(1.0, DisplayScale.forWindow(800f, 1200f).toDouble(), 1e-6)          // 세로형 창은 그대로
        assertEquals(1.0, DisplayScale.forWindow(1000f, 500f).toDouble(), 1e-6)          // 설계보다 작은 창은 축소하지 않음
    }

    @Test
    fun ignoresUnusableCarModelText() {
        assertNull(CarText.cleanModel("\"invalid\""))
        assertNull(CarText.cleanModel("  "))
        assertNull(CarText.cleanModel(null))
        assertEquals("GRANDEUR", CarText.cleanModel(" \"GRANDEUR\" "))
    }
}
