package com.chagok.app.vehicle

/** 운행 판정에 필요한 차량·내비 신호. Pleos SDK 구현과 시뮬레이터 구현이 같은 인터페이스를 씁니다. */
interface VehicleSignals {
    val label: String

    fun start(listener: Listener)
    fun stop()

    interface Listener {
        fun onOdometerKm(km: Double)
        fun onDriverDoor(open: Boolean)
        fun onNaviDestination(name: String?) {}
        fun onNaviArrived() {}
        fun onCarModel(model: String) {}
        fun onFuelEconomy(kmPerLiter: Double) {}
        fun onStatus(message: String) {}
    }
}
