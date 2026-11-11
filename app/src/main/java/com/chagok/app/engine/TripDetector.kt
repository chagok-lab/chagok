package com.chagok.app.engine

/**
 * 운행 판정 상태 기계.
 *
 * IDLE --(누적거리가 기준점 대비 startThresholdKm 이상 증가)--> DRIVING
 * DRIVING --(정차 신호[기어 P 또는 운전석 도어 열림] 후 doorGraceMs 동안 누적거리 변화 없음
 *           | 내비 도착 후 arrivedIdleMs 동안 변화 없음 | parkedIdleMs 동안 변화 없음)--> IDLE (Ended 발생)
 *
 * 정차 신호만으로는 종료를 확정하지 않습니다. 기어를 P 에 두거나 문이 열리면 Arrived(용도 확인 카드용)를 바로 보내고,
 * doorGraceMs 동안 움직이지 않으면 Ended로 확정합니다. 그 사이 다시 출발하면 Resumed를 보내고
 * 같은 운행으로 이어서 기록합니다(편의점 앞 잠깐 정차 등).
 * 기어·도어 신호가 없는 차량에서도 누적거리와 정지 시간만으로 동작합니다.
 */
class TripDetector(
    private val startThresholdKm: Double = 0.1,
    private val arrivedIdleMs: Long = 120_000L,
    private val parkedIdleMs: Long = 600_000L,
    private val minTripKm: Double = 0.1,
    private val doorGraceMs: Long = 60_000L,
    private val maxStepKm: Double = ContinuityChecker.MAX_GAP_KM,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class State { IDLE, DRIVING }

    sealed interface Event {
        data class Started(val startOdoKm: Double, val startEpochMs: Long) : Event
        /** 정차 신호(기어 P·운전석 도어 열림): 아직 확정 전(용도 확인 카드를 바로 띄우기 위한 도착 후보) */
        data class Arrived(
            val startOdoKm: Double,
            val endOdoKm: Double,
            val startEpochMs: Long,
            val endEpochMs: Long,
            val destination: String?,
        ) : Event
        /** 도착 후보 상태에서 다시 출발: 같은 운행으로 이어감 */
        data object Resumed : Event
        data class Ended(
            val startOdoKm: Double,
            val endOdoKm: Double,
            val startEpochMs: Long,
            val endEpochMs: Long,
            val destination: String?,
        ) : Event
    }

    var state: State = State.IDLE
        private set
    var baselineOdoKm: Double? = null
        private set
    var currentOdoKm: Double? = null
        private set
    var tripStartOdoKm: Double? = null
        private set
    private var tripStartMs: Long = 0L
    private var lastMoveMs: Long = 0L
    private var arrived: Boolean = false
    private var doorOpenedMs: Long? = null      // 정차 신호(기어 P·도어 열림)를 받은 시각
    private var stopOdoKm: Double? = null       // 정차 신호를 받았을 때의 누적거리
    private var gearParked: Boolean = false
    private var destination: String? = null

    fun onOdometer(km: Double): Event? {
        val now = clock()
        val previous = currentOdoKm
        currentOdoKm = km
        val base = baselineOdoKm
        if (base == null) {
            baselineOdoKm = km
            return null
        }
        return when (state) {
            State.IDLE -> {
                if (km < base) {          // 단위 변경·센서 초기화 등으로 값이 줄면 기준점을 다시 잡음
                    baselineOdoKm = km
                    null
                } else if (km - base > maxStepKm) {
                    // 정차 중에 한 번에 지나치게 크게 늘어난 값은 주행이 아니라 값 초기화·교체로 보고 기준점만 다시 잡음
                    baselineOdoKm = km
                    null
                } else if (km - base >= startThresholdKm) {
                    state = State.DRIVING
                    tripStartOdoKm = base
                    tripStartMs = now
                    lastMoveMs = now
                    arrived = false
                    Event.Started(base, now)
                } else null
            }
            State.DRIVING -> {
                if (previous == null || km > previous) {
                    val stopKm = stopOdoKm
                    if (doorOpenedMs != null && stopKm != null) {
                        // 정차 신호 뒤에 들어온 값: 기어가 P 이거나 0.1 km 미만이면 늦게 도착한 값으로 보고 운행 끝 값에만 반영
                        if (gearParked || km - stopKm < startThresholdKm) return null
                        doorOpenedMs = null    // 다시 출발하면 같은 운행으로 이어감
                        stopOdoKm = null
                        lastMoveMs = now
                        arrived = false
                        return Event.Resumed
                    }
                    lastMoveMs = now
                    arrived = false        // 다시 움직이면 도착 대기를 해제
                }
                null
            }
        }
    }

    /** 운전석 도어 열림: 정차 신호 */
    fun onDriverDoor(open: Boolean): Event? = if (open) onStopSignal() else null

    /** 기어 위치 변화: P(주차)로 바뀌면 정차 신호. P 인 동안에는 누적거리 값이 늦게 들어와도 재출발로 보지 않음 */
    fun onGear(parked: Boolean): Event? {
        gearParked = parked
        return if (parked) onStopSignal() else null
    }

    /**
     * 정차 신호: 바로 끝내지 않고 Arrived(도착 후보)를 보냅니다.
     * doorGraceMs 동안 누적거리 변화가 없으면 tick()에서 Ended로 확정합니다.
     */
    private fun onStopSignal(): Event? {
        if (state != State.DRIVING) return null
        if (doorGraceMs <= 0L) return end()
        if (doorOpenedMs != null) return null        // 이미 도착 후보 상태(기어 P 뒤 문 열림, 문을 여닫는 반복)
        val start = tripStartOdoKm ?: return null
        val cur = currentOdoKm ?: return null
        val now = clock()
        doorOpenedMs = now
        stopOdoKm = cur
        lastMoveMs = now
        return Event.Arrived(start, cur, tripStartMs, now, destination)
    }

    fun onNaviDestination(name: String?) {
        destination = name?.takeIf { it.isNotBlank() }
    }

    fun onNaviArrived() {
        if (state == State.DRIVING) {
            arrived = true
            lastMoveMs = clock()
        }
    }

    /** 1초 주기로 호출: 도어 열림 후 정지, 도착 후 정지, 또는 장시간 정지를 운행 종료로 판정 */
    fun tick(): Event? {
        if (state != State.DRIVING) return null
        val idle = clock() - lastMoveMs
        val doorDone = doorOpenedMs != null && idle >= doorGraceMs
        // 종료 시각은 판정 시점이 아니라 마지막으로 멈춘 시각(문 열림·도착·마지막 이동)
        return if (doorDone || (arrived && idle >= arrivedIdleMs) || idle >= parkedIdleMs) end(lastMoveMs) else null
    }

    private fun end(atMs: Long = clock()): Event? {
        val start = tripStartOdoKm ?: return null
        val endKm = currentOdoKm ?: return null
        val now = atMs
        state = State.IDLE
        baselineOdoKm = endKm
        tripStartOdoKm = null
        arrived = false
        doorOpenedMs = null
        stopOdoKm = null
        val dest = destination
        destination = null
        return if (endKm - start >= minTripKm) {
            Event.Ended(start, endKm, tripStartMs, now, dest)
        } else null
    }
}
