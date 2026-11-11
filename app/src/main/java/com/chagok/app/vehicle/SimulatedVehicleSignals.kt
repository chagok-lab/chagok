package com.chagok.app.vehicle

import android.os.Handler
import android.os.Looper

/**
 * 에뮬레이터 없이 시연·테스트하기 위한 시뮬레이터 (같은 VehicleSignals 인터페이스).
 * '다음 운행' 버튼을 누를 때마다 시나리오의 운행 1건을 빠르게 재생합니다.
 */
class SimulatedVehicleSignals : VehicleSignals {
    override val label = "시뮬레이터"

    data class Leg(val destination: String, val km: Double)

    private val scenario = listOf(
        Leg("회사", 18.4),
        Leg("한빛정밀(가상)", 23.7),
        Leg("거래처 B(가상)", 15.6),
        Leg("자택", 18.4),
    )
    private val main = Handler(Looper.getMainLooper())
    private var listener: VehicleSignals.Listener? = null
    private var odoKm = 12_480.2
    private var legIndex = 0
    var running = false
        private set

    override fun start(listener: VehicleSignals.Listener) {
        this.listener = listener
        listener.onCarModel("그랜저 (시뮬레이터)")
        listener.onOdometerKm(odoKm)
        listener.onGear("P")
        listener.onDriverDoor(false)
        listener.onStatus("시뮬레이터: '다음 운행'으로 운행 1건 재생")
    }

    override fun stop() {
        main.removeCallbacksAndMessages(null)
        running = false
        listener = null
    }

    fun nextLegName(): String = scenario[legIndex % scenario.size].destination

    /** 운행 1건: 목적지 설정 → 기어 D → 0.3 km 단위로 누적거리 증가 → 도착 → 기어 P → 운전석 도어 열림 */
    fun runNextLeg() {
        val l = listener ?: return
        if (running) return
        running = true
        val leg = scenario[legIndex % scenario.size]
        legIndex++
        l.onDriverDoor(false)
        l.onNaviDestination(leg.destination)
        l.onGear("D")
        val target = round1(odoKm + leg.km)
        val step = object : Runnable {
            override fun run() {
                val next = minOf(target, round1(odoKm + 0.3))
                odoKm = next
                listener?.onOdometerKm(next)
                if (next < target) {
                    main.postDelayed(this, 120L)
                } else {
                    listener?.onNaviArrived()
                    main.postDelayed({
                        listener?.onGear("P")              // 주차: 용도 확인 카드 표시
                        main.postDelayed({
                            listener?.onDriverDoor(true)   // 하차
                            running = false
                        }, 600L)
                    }, 600L)
                }
            }
        }
        main.postDelayed(step, 400L)
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0
}
