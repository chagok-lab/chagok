package com.chagok.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.chagok.app.navi.NaviSignals

/**
 * Pleos Connect 실차·에뮬레이터 신호 (Vehicle SDK 2.3.2, NaviHelper SDK 3.0.5).
 * - Odometer: getCurrentDistanceDriven / registerDistanceDriven → 계기판 누적거리 (서식 ⑤⑥⑦)
 * - Gear: getCurrentGearSelectionState / registerGearSelection → 기어 P 전환을 정차 신호로 사용
 * - Door: getCurrentDoorPosition / registerDoorPosition(운전석 ROW_1_LEFT) → 하차를 정차 신호로 사용
 * - NaviHelper: 목적지, 도착 이벤트 → 방문처(서식 ⑩)와 용도 추천
 * 조회 전용이며 차량을 제어하는 API 는 쓰지 않습니다.
 */
class PleosVehicleSignals(context: Context) : VehicleSignals {
    override val label = "Pleos SDK"

    private val appContext = context.applicationContext
    private val vehicle = Vehicle(appContext)
    private val main = Handler(Looper.getMainLooper())
    private var listener: VehicleSignals.Listener? = null
    private var initialized = false
    private var navi: NaviSignals? = null
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = onOdometerValue(value)
        override fun onFailed(e: Exception) = onOdometerError(e)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = onGearValue(gear)
        override fun onFailed(e: Exception) = onOptionalError("기어", e)
    }

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) = onDoorValue(pos)
        override fun onFailed(e: Exception) = onOptionalError("도어", e)
    }

    // 변화 콜백이 오지 않는 환경을 대비해 2초 주기로 누적거리를 함께 조회
    private val odometerPoll = object : Runnable {
        override fun run() {
            if (initialized) {
                runCatching {
                    vehicle.getOdometer().getCurrentDistanceDriven({ v -> onOdometerValue(v) }, { e -> onOdometerError(e) })
                }.onFailure(::onOdometerError)
            }
            main.postDelayed(this, 2_000L)
        }
    }

    override fun start(listener: VehicleSignals.Listener) {
        this.listener = listener
        try {
            vehicle.initialize()
            initialized = true
        } catch (t: Throwable) {
            // 호출한 쪽(ChagokEngine)이 시뮬레이터로 전환하도록 알림
            throw IllegalStateException("Vehicle SDK 초기화 실패: ${t.message ?: t.javaClass.simpleName}", t)
        }
        listener.onStatus("Vehicle SDK 연결됨")

        // Odometer: 변화 콜백 등록 + 주기 조회
        runCatching { vehicle.getOdometer().registerDistanceDriven(odometerListener) }.onFailure(::onOdometerError)
        main.post(odometerPoll)

        // Gear: 현재 값 조회 + 변화 콜백 (기어 신호가 없는 차량은 도어·정지 시간으로 판정)
        runCatching {
            vehicle.getGear().getCurrentGearSelectionState({ g -> onGearValue(g) }, { e -> onOptionalError("기어", e) })
            vehicle.getGear().registerGearSelection(gearListener)
        }.onFailure { onOptionalError("기어", it) }

        // Door: 운전석 도어 위치
        runCatching {
            vehicle.getDoor().getCurrentDoorPosition(driverDoor, { pos -> onDoorValue(pos) }, { e -> onOptionalError("도어", e) })
            vehicle.getDoor().registerDoorPosition(driverDoor, doorListener)
        }.onFailure { onOptionalError("도어", it) }

        // NaviHelper: 목적지·도착
        runCatching {
            navi = NaviSignals(
                context = appContext,
                onDestination = { name -> post { it.onNaviDestination(name) } },
                onArrived = { post { it.onNaviArrived() } },
                onStatus = { msg -> post { it.onStatus(msg) } },
            ).also { it.start() }
        }.onFailure { onOptionalError("내비", it) }
    }

    override fun stop() {
        main.removeCallbacks(odometerPoll)
        runCatching { navi?.stop() }
        navi = null
        listener = null
        if (!initialized) return
        runCatching { vehicle.getOdometer().unregisterDistanceDriven(odometerListener) }
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getDoor().unregisterDoorPosition(driverDoor, doorListener) }
        runCatching { vehicle.release() }
        initialized = false
    }

    private fun onOdometerValue(value: Float?) {
        val raw = value ?: return
        val km = Math.round(raw * 10.0) / 10.0   // Float 오차 제거, 서식과 같은 0.1 km 단위
        post { it.onOdometerKm(km) }
    }

    private fun onGearValue(gear: VehicleGear?) {
        val position = when (gear) {
            null, VehicleGear.GEAR_UNKNOWN -> null
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            else -> "D"                           // GEAR_DRIVE, GEAR_1 ~ GEAR_9
        }
        post { it.onGear(position) }
    }

    private fun onDoorValue(pos: Int?) {
        post { it.onDriverDoor((pos ?: 0) > 0) }
    }

    /** 누적거리는 필수 신호: 읽지 못하면 화면에 알림 */
    private fun onOdometerError(t: Throwable) {
        Log.w(TAG, "Odometer", t)
        post { it.onStatus("계기판 누적거리를 읽지 못했습니다 (${brief(t)})") }
    }

    /** 기어·도어·내비는 보조 신호: 없으면 남은 신호로 운행을 판정하므로 기록만 남김 */
    private fun onOptionalError(name: String, t: Throwable) {
        Log.i(TAG, "$name 신호 미제공: ${t.message ?: t.javaClass.simpleName}")
        post { it.onSignalUnavailable(name) }
    }

    private fun brief(t: Throwable) = (t.message ?: t.javaClass.simpleName).substringAfterLast(": ").take(60)

    private fun post(block: (VehicleSignals.Listener) -> Unit) {
        main.post { listener?.let(block) }
    }

    private companion object {
        const val TAG = "Chagok"
    }
}
