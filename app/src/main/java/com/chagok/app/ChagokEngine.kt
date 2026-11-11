package com.chagok.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chagok.app.data.HashChain
import com.chagok.app.data.TripStore
import com.chagok.app.engine.CarText
import com.chagok.app.engine.ContinuityChecker
import com.chagok.app.engine.LogbookCalculator
import com.chagok.app.engine.OdometerFilter
import com.chagok.app.engine.PurposeAdvisor
import com.chagok.app.engine.TripDetector
import com.chagok.app.export.NtsLogbookExporter
import com.chagok.app.model.Purpose
import com.chagok.app.model.Trip
import com.chagok.app.ux.UxRestrictionWatcher
import com.chagok.app.vehicle.PleosVehicleSignals
import com.chagok.app.vehicle.SimulatedVehicleSignals
import com.chagok.app.vehicle.VehicleSignals
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 앱 상태 원천(Application 범위). 화면이 바뀌어도 운행 판정은 계속됩니다.
 * 신호 → TripDetector → 원장(봉인) → 화면, 의 단방향 흐름입니다.
 */
class ChagokEngine(private val context: Context) : VehicleSignals.Listener {

    enum class Mode { PLEOS, SIMULATOR }

    // ---- 화면이 구독하는 상태
    var mode by mutableStateOf(Mode.PLEOS); private set
    var odometerKm by mutableStateOf<Double?>(null); private set
    var driving by mutableStateOf(false); private set
    var tripStartOdoKm by mutableStateOf<Double?>(null); private set
    var destination by mutableStateOf<String?>(null); private set
    var uxRestricted by mutableStateOf(false); private set
    var carModel by mutableStateOf(NOT_SET); private set                // 서식 ① 차종: 사용자가 한 번 입력하는 값(입력 화면은 개발 예정)
    var gear by mutableStateOf<String?>(null); private set              // "P" / "R" / "N" / "D"
    var driverDoorOpen by mutableStateOf<Boolean?>(null); private set
    var unavailableSignals by mutableStateOf<List<String>>(emptyList()); private set
    var status by mutableStateOf(""); private set
    var pendingTripId by mutableStateOf<Long?>(null); private set
    var verifiedCount by mutableStateOf(0); private set
    /** 실차 모드인데 차량 신호(누적거리)가 오지 않는 상태: 화면에 '시뮬레이터로 시연하기' 버튼을 띄움 */
    var noSignal by mutableStateOf(false); private set
    val ledger = mutableStateListOf<Trip>()

    /** 서식 ④ 사용자(부서, 성명), ② 자동차등록번호: 설정 화면 대신 예시 값 */
    // 서식 ②④: 사용자가 한 번 입력하는 값(입력 화면은 개발 예정). 시뮬레이터 모드에서만 시연용 값을 채움
    var userLabel = NOT_SET
    var plateNumber = NOT_SET

    private val zone = ZoneId.of("Asia/Seoul")
    private val main = Handler(Looper.getMainLooper())
    private val store = TripStore(File(context.filesDir, "chagok_ledger.json"))
    private var detector = TripDetector()
    private val odometerFilter = OdometerFilter()
    private var continuityChecked = false

    // 도어 열림 직후의 잠정 운행(아직 봉인 전): 카드를 바로 띄우고, 정차가 확인되면 봉인
    private var provisional: Trip? = null
    private var provisionalPurpose: Purpose? = null
    private var provisionalSkipped = false
    private var signals: VehicleSignals? = null
    private var ux: UxRestrictionWatcher? = null

    private var signalWaitTicks = 0

    private val ticker = object : Runnable {
        override fun run() {
            runCatching { detector.tick()?.let(::handle) }
            // 실차 모드인데 차량 신호가 오지 않을 때: 시뮬레이터 전환 방법을 안내 (자동 전환은 하지 않음)
            if (mode == Mode.PLEOS && odometerKm == null && signalWaitTicks < NO_SIGNAL_HINT_TICKS) {
                signalWaitTicks++
                if (signalWaitTicks == NO_SIGNAL_HINT_TICKS) {
                    noSignal = true
                    status = "차량 신호를 받지 못했습니다. '시뮬레이터로 시연하기'를 누르면 시연 흐름을 재현합니다"
                }
            }
            main.postDelayed(this, 1_000L)
        }
    }

    val inputLocked: Boolean get() = driving || uxRestricted

    fun start() {
        ledger.clear()
        ledger.addAll(store.load())
        verifiedCount = HashChain.verify(ledger)
        pendingTripId = LogbookCalculator.latest(ledger).lastOrNull { it.purpose == Purpose.UNCONFIRMED }?.tripId
        ux = UxRestrictionWatcher(context) { restricted -> uxRestricted = restricted }.also { it.start() }
        switchMode(Mode.PLEOS)
        main.post(ticker)
    }

    fun stop() {
        main.removeCallbacks(ticker)
        runCatching { signals?.stop() }
        runCatching { ux?.stop() }
    }

    fun switchMode(target: Mode) {
        runCatching { signals?.stop() }
        signals = null
        // 실차: 문을 연 뒤 60초 동안 움직이지 않으면 종료 확정 / 시뮬레이터: 시연용으로 3초
        detector = TripDetector(doorGraceMs = if (target == Mode.SIMULATOR) SIM_DOOR_GRACE_MS else DOOR_GRACE_MS)
        continuityChecked = false
        odometerFilter.reset()
        clearProvisional()
        driving = false
        tripStartOdoKm = null
        odometerKm = null
        destination = null
        gear = null
        driverDoorOpen = null
        unavailableSignals = emptyList()
        carModel = NOT_SET
        userLabel = NOT_SET
        plateNumber = NOT_SET
        signalWaitTicks = 0
        noSignal = false

        var fallbackReason: String? = null
        if (target == Mode.PLEOS) {
            try {
                val pleos = PleosVehicleSignals(context)
                mode = Mode.PLEOS
                signals = pleos
                pleos.start(this)
                return
            } catch (t: Throwable) {   // Pleos Connect 가 아닌 기기, SDK 초기화 실패: 시뮬레이터로 전환
                runCatching { signals?.stop() }
                signals = null
                detector = TripDetector(doorGraceMs = SIM_DOOR_GRACE_MS)
                fallbackReason = "Pleos SDK를 사용할 수 없습니다(${t.message ?: t.javaClass.simpleName})"
            }
        }
        mode = Mode.SIMULATOR
        userLabel = DEMO_USER
        plateNumber = DEMO_PLATE
        val simulator = SimulatedVehicleSignals()
        signals = simulator
        simulator.start(this)
        fallbackReason?.let { status = "$it. 시뮬레이터 모드로 시연 흐름을 재현합니다" }
    }

    /** 시뮬레이터 모드에서 운행 1건 재생 (직전 운행의 정차 확인이 끝난 뒤에만) */
    fun simulateNextLeg() {
        if (provisional != null) {
            status = "직전 운행의 정차를 확인하는 중입니다. 잠시 후 다시 눌러 주세요"
            return
        }
        (signals as? SimulatedVehicleSignals)?.runNextLeg()
    }

    fun nextSimulatedDestination(): String? = (signals as? SimulatedVehicleSignals)?.nextLegName()

    // ---- VehicleSignals.Listener
    override fun onOdometerKm(km: Double) {
        noSignal = false
        val accepted = odometerFilter.accept(km) ?: return     // 직전보다 작은 지연 값은 버림
        odometerKm = accepted
        if (!continuityChecked && mode == Mode.PLEOS) {   // 실차 값을 처음 받았을 때 1회
            continuityChecked = true
            reconcileGap(km)
        }
        detector.onOdometer(km)?.let(::handle)
    }

    override fun onDriverDoor(open: Boolean) {
        driverDoorOpen = open
        detector.onDriverDoor(open)?.let(::handle)
    }

    override fun onGear(position: String?) {
        gear = position
        detector.onGear(parked = position == "P")?.let(::handle)
    }

    override fun onSignalUnavailable(name: String) {
        if (name !in unavailableSignals) unavailableSignals = unavailableSignals + name
    }

    override fun onNaviDestination(name: String?) {
        destination = name
        detector.onNaviDestination(name)
    }

    override fun onNaviArrived() = detector.onNaviArrived()

    override fun onCarModel(model: String) {
        CarText.cleanModel(model)?.let { carModel = it }
    }

    /** 화면에 보여 줄 차량 신호 요약 (기어·운전석 도어·내비 목적지) */
    fun signalSummary(): String {
        fun missing(name: String) = if (name in unavailableSignals) "미제공" else "확인 중"
        val door = when (driverDoorOpen) {
            true -> "열림"
            false -> "닫힘"
            null -> missing("도어")
        }
        return "기어 ${gear ?: missing("기어")} · 운전석 도어 $door · 내비 목적지 ${destination ?: "없음"}"
    }

    override fun onStatus(message: String) {
        status = if (noSignal) "$message · 차량 신호가 없으면 '시뮬레이터로 시연하기'를 눌러 주세요" else message
    }

    private fun handle(event: TripDetector.Event) {
        when (event) {
            is TripDetector.Event.Started -> {
                driving = true
                tripStartOdoKm = event.startOdoKm
            }
            is TripDetector.Event.Arrived -> {       // 기어 P·문 열림: 용도 확인 카드를 바로 표시
                driving = false
                val start = guardedStart(event.startOdoKm, event.endOdoKm)
                if (start == null) {                 // 이미 기록된 구간이면 카드를 띄우지 않음
                    clearProvisional()
                } else {
                    provisional = draftTrip(
                        nextTripId(), start, event.endOdoKm, event.startEpochMs, event.endEpochMs, event.destination,
                    )
                    provisionalPurpose = null
                    provisionalSkipped = false
                }
            }
            TripDetector.Event.Resumed -> {          // 잠깐 정차 후 다시 출발: 같은 운행으로 이어감
                driving = true
                clearProvisional()
            }
            is TripDetector.Event.Ended -> {
                driving = false
                tripStartOdoKm = null
                sealNewTrip(event)
            }
        }
    }

    private fun nextTripId(): Long = (ledger.maxOfOrNull { it.tripId } ?: 0L) + 1L

    private fun draftTrip(tripId: Long, startOdo: Double, endOdo: Double, startMs: Long, endMs: Long, dest: String?) = Trip(
        seq = 0L, tripId = tripId, revision = 0,
        startEpochMs = startMs, endEpochMs = endMs,
        startOdoKm = startOdo, endOdoKm = endOdo,
        destination = dest,
        purpose = Purpose.UNCONFIRMED,
        suggested = PurposeAdvisor.suggest(dest, endMs, zone),
        user = userLabel,
        source = mode.name,
        note = null, prevHash = "", hash = "",
    )

    private fun clearProvisional() {
        provisional = null
        provisionalPurpose = null
        provisionalSkipped = false
    }

    /**
     * 운행 종료 확정: 차량 원천 값을 revision 0 으로 봉인합니다.
     * 문이 열린 순간 카드에서 이미 고른 용도가 있으면 revision 1 로 이어서 기록합니다.
     */
    /**
     * 실차 신호로 만든 운행이 직전에 기록한 구간과 겹치지 않도록 시작 누적거리를 맞춥니다(서식 ⑤ ≥ 직전 ⑥).
     * 남는 거리가 없으면 null. 시뮬레이터 시연 기록에는 적용하지 않습니다.
     */
    private fun guardedStart(startKm: Double, endKm: Double): Double? {
        if (mode != Mode.PLEOS) return startKm
        val last = LogbookCalculator.latest(ledger)
            .filter { it.source == Mode.PLEOS.name || it.source == SOURCE_GAP }
            .maxByOrNull { it.endEpochMs }
        return ContinuityChecker.nonOverlappingStart(last?.startOdoKm, last?.endOdoKm, startKm, endKm)
    }

    private fun sealNewTrip(e: TripDetector.Event.Ended) {
        val start = guardedStart(e.startOdoKm, e.endOdoKm)
        if (start == null) {
            clearProvisional()
            status = "이미 기록된 구간이어서 새 운행으로 추가하지 않았습니다"
            return
        }
        val tripId = nextTripId()
        appendAndSave(TripStore.append(ledger, draftTrip(tripId, start, e.endOdoKm, e.startEpochMs, e.endEpochMs, e.destination)))
        val chosen = provisionalPurpose
        val skipped = provisionalSkipped
        clearProvisional()
        val km = String.format(Locale.ROOT, "%.1f", e.endOdoKm - start)
        if (chosen != null) {
            TripStore.revise(ledger, tripId, chosen, note = null)?.let(::appendAndSave)
            if (pendingTripId == tripId) pendingTripId = null
            status = "운행 기록 완료: ${chosen.label} $km km"
        } else {
            if (!skipped) pendingTripId = tripId
            status = "운행 $km km를 기록했습니다. 용도를 확인해 주세요"
        }
    }

    /**
     * 앱이 꺼져 있던 동안(종료·재부팅·신호 끊김) 달린 거리를 찾아 채웁니다.
     * 마지막 실차 운행의 ⑥ 주행 후 계기판 거리와 지금 계기판 값의 차이를 '용도 확인 필요' 운행으로 봉인합니다.
     */
    private fun reconcileGap(currentKm: Double) {
        val last = LogbookCalculator.latest(ledger)
            .filter { it.source == Mode.PLEOS.name || it.source == SOURCE_GAP }
            .maxByOrNull { it.endEpochMs } ?: return
        when (val r = ContinuityChecker.check(last.endOdoKm, currentKm)) {
            is ContinuityChecker.Result.Gap -> {
                val tripId = nextTripId()
                val draft = Trip(
                    seq = 0L, tripId = tripId, revision = 0,
                    startEpochMs = last.endEpochMs, endEpochMs = System.currentTimeMillis(),
                    startOdoKm = r.fromOdoKm, endOdoKm = r.toOdoKm,
                    destination = null,
                    purpose = Purpose.UNCONFIRMED,
                    suggested = Purpose.UNCONFIRMED,
                    user = userLabel,
                    source = SOURCE_GAP,
                    note = GAP_NOTE,
                    prevHash = "", hash = "",
                )
                appendAndSave(TripStore.append(ledger, draft))
                pendingTripId = tripId
                status = "앱이 꺼져 있던 동안 ${r.km} km 주행을 찾아 '용도 확인 필요'로 추가했습니다"
            }
            is ContinuityChecker.Result.Suspicious ->
                status = "계기판 값이 마지막 기록(${last.endOdoKm} km)과 크게 달라 자동 보완하지 않았습니다. 확인이 필요합니다"
            ContinuityChecker.Result.Continuous -> Unit
        }
    }

    /** 원탭 용도 확정 (주행 중에는 호출되지 않도록 화면에서 막음) */
    fun confirm(tripId: Long, purpose: Purpose) {
        if (inputLocked) return
        val p = provisional
        if (p != null && p.tripId == tripId) {      // 아직 봉인 전: 고른 용도를 기억했다가 확정 때 함께 기록
            provisionalPurpose = purpose
            status = "용도를 기록했습니다. 정차가 확인되면 운행이 확정됩니다"
            return
        }
        val rev = TripStore.revise(ledger, tripId, purpose, note = null) ?: return
        appendAndSave(rev)
        if (pendingTripId == tripId) pendingTripId = null
        status = "용도 확정: ${purpose.label}"
    }

    fun skipConfirmation() {
        if (provisional != null) provisionalSkipped = true
        pendingTripId = null
    }

    private fun appendAndSave(t: Trip) {
        ledger.add(t)
        runCatching { store.save(ledger.toList()) }
            .onFailure { e -> status = "기록 저장 실패: ${e.message ?: e.javaClass.simpleName}" }
        verifiedCount = HashChain.verify(ledger)
    }

    fun pendingTrip(): Trip? {
        provisional?.let { p -> return if (provisionalPurpose == null && !provisionalSkipped) p else null }
        return pendingTripId?.let { id -> LogbookCalculator.latest(ledger).firstOrNull { it.tripId == id } }
    }

    fun summary(): LogbookCalculator.Summary = LogbookCalculator.summarize(ledger)

    fun todayTrips(): List<Trip> {
        val today = LocalDate.now(zone)
        return LogbookCalculator.latest(ledger).filter {
            java.time.Instant.ofEpochMilli(it.startEpochMs).atZone(zone).toLocalDate() == today
        }
    }

    /** 운행기록부 서식 CSV를 앱 전용 저장소에 만들고 파일을 돌려줍니다. 실패하면 null. */
    fun exportCsv(): File? = try {
        val csv = NtsLogbookExporter.toCsv(ledger, carModel, plateNumber, zone)
        val month = LocalDate.now(zone).format(DateTimeFormatter.ofPattern("yyyyMM"))
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = NtsLogbookExporter.write(dir, "chagok_logbook_$month.csv", csv)
        // 내보낸 파일의 이름·크기·줄 수만 로그에 남김 (운행 내용은 로그에 쓰지 않음)
        Log.i("Chagok", "export ${file.name} bytes=${file.length()} lines=${csv.lineSequence().count()}")
        file
    } catch (t: Throwable) {
        status = "내보내기 실패: ${t.message ?: t.javaClass.simpleName}"
        null
    }

    companion object {
        const val NOT_SET = "미입력"
        const val DEMO_USER = "영업 홍○○"
        const val DEMO_PLATE = "12가 3456"
        const val DOOR_GRACE_MS = 60_000L
        const val NO_SIGNAL_HINT_TICKS = 5
        const val SIM_DOOR_GRACE_MS = 3_000L
        const val GAP_NOTE = "앱 미실행 구간: 계기판 누적거리 차이로 자동 보완"
        /** 자동 보완 운행 표시(용도 확정 revision에도 그대로 이어짐) */
        const val SOURCE_GAP = "PLEOS_GAP"
    }

    /** 시연 초기화: 원장 비우기 (봉인 기록은 실제 서비스에서는 삭제하지 않음) */
    fun resetForDemo() {
        if (inputLocked) return
        ledger.clear()
        store.save(emptyList())
        verifiedCount = 0
        pendingTripId = null
        clearProvisional()
    }
}
