package com.chagok.app.vehicle

/** 운행 판정에 필요한 차량·내비 신호. Pleos SDK 구현과 시뮬레이터 구현이 같은 인터페이스를 씁니다. */
interface VehicleSignals {
    val label: String

    fun start(listener: Listener)
    fun stop()

    interface Listener {
        fun onOdometerKm(km: Double)
        fun onDriverDoor(open: Boolean)
        /** 기어 위치: "P" / "R" / "N" / "D", 알 수 없으면 null */
        fun onGear(position: String?) {}
        fun onNaviDestination(name: String?) {}
        fun onNaviArrived() {}
        fun onCarModel(model: String) {}
        /** 보조 신호(기어·도어·내비)를 제공하지 않는 차량 */
        fun onSignalUnavailable(name: String) {}
        fun onStatus(message: String) {}
    }
}
