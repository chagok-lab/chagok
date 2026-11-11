package com.chagok.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.chagok.app.navi.NaviSignals

/**
 * Pleos Connect 실차·에뮬레이터 신호.
 * - Odometer (Vehicle SDK): getCurrentDistanceDriven / registerDistanceDriven → 계기판 누적거리
 * - Door (Vehicle SDK, 공식 샘플과 같은 타입 호출): 운전석(ROW_1_LEFT) 도어 위치 → 운행 종료
 * - CarInfo, Fuel (Vehicle SDK): 차종, 연비
 * - NaviHelper SDK: 목적지, 도착 이벤트
 */
class PleosVehicleSignals(context: Context) : VehicleSignals {
    override val label = "Pleos SDK"

    private val appContext = context.applicationContext
    private val vehicle = Vehicle(appContext)
    private val main = Handler(Looper.getMainLooper())
    private var listener: VehicleSignals.Listener? = null
    private var initialized = false
    private var odometerProxy: Any? = null
    private var navi: NaviSignals? = null

    private val driverDoor: DoorArea? by lazy {
        runCatching { DoorArea(DoorValue.valueOf("ROW_1_LEFT")) }.getOrNull()
    }

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            post { it.onDriverDoor((pos ?: 0) > 0) }
        }

        override fun onFailed(e: Exception) {
            post { it.onStatus("Door: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // 콜백이 오지 않는 환경을 대비해 2초 주기로 누적거리를 함께 조회
    private val odometerPoll = object : Runnable {
        override fun run() {
            readOdometer()
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
        runCatching {
            odometerProxy = SdkReflect.register(vehicle.getOdometer(), "registerDistanceDriven", ::onOdometerValue, ::onError)
        }.onFailure(::onError)
        main.post(odometerPoll)

        // Door: 운전석 도어 위치
        driverDoor?.let { area ->
            runCatching { vehicle.getDoor().registerDoorPosition(area, doorListener) }.onFailure(::onError)
            runCatching {
                vehicle.getDoor().getCurrentDoorPosition(
                    area = area,
                    onComplete = { pos -> post { it.onDriverDoor((pos ?: 0) > 0) } },
                    onFailed = { e -> onError(e) },
                )
            }.onFailure(::onError)
        } ?: listener.onStatus("운전석 도어 영역(ROW_1_LEFT)을 찾지 못해 도어 이벤트 없이 동작")

        runCatching { readCarInfo() }.onFailure(::onError)
        runCatching { readFuelEconomy() }.onFailure(::onError)

        // NaviHelper: 목적지·도착
        runCatching {
            navi = NaviSignals(
                context = appContext,
                onDestination = { name -> post { it.onNaviDestination(name) } },
                onArrived = { post { it.onNaviArrived() } },
                onStatus = { msg -> post { it.onStatus(msg) } },
            ).also { it.start() }
        }.onFailure(::onError)
    }

    override fun stop() {
        main.removeCallbacks(odometerPoll)
        runCatching { navi?.stop() }
        navi = null
        listener = null
        if (!initialized) return
        runCatching { SdkReflect.unregister(vehicle.getOdometer(), "unregisterDistanceDriven", odometerProxy) }
        driverDoor?.let { area -> runCatching { vehicle.getDoor().unregisterDoorPosition(area, doorListener) } }
        runCatching { vehicle.release() }
        initialized = false
    }

    private fun readOdometer() {
        if (!initialized) return
        runCatching {
            SdkReflect.callGet(vehicle.getOdometer(), "getCurrentDistanceDriven", ::onOdometerValue, ::onError)
        }.onFailure(::onError)
    }

    private fun onOdometerValue(value: Any?) {
        val raw = SdkReflect.number(value) ?: return
        val km = Math.round(raw * 10.0) / 10.0   // Float 오차 제거, 서식과 같은 0.1 km 단위
        post { it.onOdometerKm(km) }
    }

    private fun readCarInfo() {
        val carInfo = vehicle.getCarInfo()
        val getter = SdkReflect.findGetterName(carInfo) { it != "getCapabilityInfo" } ?: return
        SdkReflect.callGet(carInfo, getter, { v -> v?.let { model -> post { it.onCarModel(model.toString()) } } }, ::onError)
    }

    private fun readFuelEconomy() {
        val fuel = vehicle.getFuel()
        val getter = SdkReflect.findGetterName(fuel) { it.contains("Economy") || it.contains("Efficiency") } ?: return
        SdkReflect.callGet(fuel, getter, { v -> SdkReflect.number(v)?.let { e -> post { it.onFuelEconomy(e) } } }, ::onError)
    }

    private fun onError(t: Throwable) {
        post { it.onStatus("Vehicle SDK: ${t.message ?: t.javaClass.simpleName}") }
    }

    private fun post(block: (VehicleSignals.Listener) -> Unit) {
        main.post { listener?.let(block) }
    }
}
