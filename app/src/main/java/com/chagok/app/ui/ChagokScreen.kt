package com.chagok.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chagok.app.ChagokEngine
import com.chagok.app.engine.DisplayScale
import com.chagok.app.engine.LogbookCalculator
import com.chagok.app.model.Purpose
import com.chagok.app.model.Trip
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zone = ZoneId.of("Asia/Seoul")
private val hm = DateTimeFormatter.ofPattern("HH:mm")
private val md = DateTimeFormatter.ofPattern("MM/dd(E)", Locale.KOREAN)

private fun km(v: Double) = String.format(Locale.ROOT, "%,.1f", v)
private fun manwon(won: Long) = String.format(Locale.ROOT, "%,d만원", won / 10_000)
private fun time(ms: Long, f: DateTimeFormatter) = Instant.ofEpochMilli(ms).atZone(zone).format(f)

@Composable
fun ChagokScreen(engine: ChagokEngine) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        // 차량 디스플레이는 창이 넓고 시청 거리가 멀어 글자가 작게 보이므로, 창 크기에 맞춰 화면 전체를 비례 확대
        val scale = DisplayScale.forWindow(maxWidth.value, maxHeight.value)
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            ScaledScreen(engine)
        }
    }
}

@Composable
private fun ScaledScreen(engine: ChagokEngine) {
    var exported by remember { mutableStateOf<File?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Header(engine)
            Spacer(Modifier.height(16.dp))
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(0.42f).fillMaxHeight()) { StatusPane(engine) }
                    Column(Modifier.weight(0.58f).fillMaxHeight()) { ReportPane(engine) { exported = it } }
                }
            } else {
                // portrait(창 1개): 핵심 상태와 원탭을 우선, 리포트는 정차 중 전환
                var showReport by remember { mutableStateOf(false) }
                if (!engine.inputLocked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TabButton("운행", !showReport) { showReport = false }
                        TabButton("리포트", showReport) { showReport = true }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (showReport && !engine.inputLocked) ReportPane(engine) { exported = it } else StatusPane(engine)
            }
        }
        exported?.let { file -> ExportDialog(engine, file) { exported = null } }
    }
}

@Composable
private fun Header(engine: ChagokEngine) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Logo()
        Spacer(Modifier.width(12.dp))
        Text("차곡", color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(8.dp))
        Text("업무용 운행기록부", color = C.Sub, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        val (chip, color) = when {
            engine.driving -> "기록 중 · 입력 잠금" to C.Mint
            engine.uxRestricted -> "주행 규제 · 입력 잠금" to C.Amber
            else -> "정차 중" to C.Sub
        }
        Chip(chip, color)
        Spacer(Modifier.width(16.dp))
        Text("시뮬레이터", color = C.Sub, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = engine.mode == ChagokEngine.Mode.SIMULATOR,
            onCheckedChange = { on -> engine.switchMode(if (on) ChagokEngine.Mode.SIMULATOR else ChagokEngine.Mode.PLEOS) },
            enabled = !engine.inputLocked,
        )
    }
}

@Composable
private fun StatusPane(engine: ChagokEngine) {
    val pending = engine.pendingTrip()
    // 화면 높이가 낮은 차량 디스플레이에서도 버튼이 잘리지 않도록 세로 스크롤 허용
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            engine.driving -> DrivingCard(engine)
            pending != null -> ConfirmCard(engine, pending)
            else -> IdleCard(engine)
        }
        val today = engine.todayTrips()
        val work = today.filter { it.purpose.isBusinessUse }.sumOf { it.distanceKm }
        val car = engine.carModel.takeIf { it != ChagokEngine.NOT_SET }?.let { " · 차종 $it" } ?: ""
        InfoLine("오늘 운행 ${today.size}건 · 업무 ${km(work)} km$car")
        val source = if (engine.mode == ChagokEngine.Mode.SIMULATOR) "시뮬레이터" else "Pleos SDK"
        InfoLine("차량 신호($source) · ${engine.signalSummary()}", size = 16)
        if (engine.status.isNotBlank()) Text(engine.status, color = C.Mute, fontSize = 14.sp)
    }
}

@Composable
private fun DrivingCard(engine: ChagokEngine) {
    val start = engine.tripStartOdoKm ?: 0.0
    val now = engine.odometerKm ?: start
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).background(C.Mint, RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(12.dp))
            Text("기록 중", color = C.Mint, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text("${km(now - start)} km", color = C.Text, fontSize = 96.sp, fontWeight = FontWeight.ExtraBold)
        val source = if (engine.mode == ChagokEngine.Mode.SIMULATOR) "시뮬레이터" else "Odometer"
        Text("출발 계기판 ${km(start)} km ($source)", color = C.Sub, fontSize = 22.sp)
        engine.destination?.let { Text("내비 목적지: $it", color = C.Sky, fontSize = 20.sp) }
        Spacer(Modifier.height(8.dp))
        Text("● 주행 중에는 입력을 받지 않습니다", color = C.Amber, fontSize = 20.sp)
    }
}

@Composable
private fun ConfirmCard(engine: ChagokEngine, trip: Trip) {
    CardBox {
        Text("운행 종료 · ${time(trip.endEpochMs, hm)}", color = C.Sub, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${km(trip.distanceKm)} km", color = C.Text, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold)
        Text("계기판 ${km(trip.startOdoKm)} → ${km(trip.endOdoKm)} km", color = C.Sub, fontSize = 22.sp)
        Text("내비 목적지: ${trip.destination ?: "없음"}", color = C.Sky, fontSize = 20.sp)
        if (trip.source.endsWith("_GAP")) {
            Text("앱이 꺼져 있던 동안의 주행입니다 (계기판 누적거리 차이로 자동 보완)", color = C.Amber, fontSize = 18.sp)
        }
    }
    Text("이번 운행의 용도를 확인해 주세요", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    PurposeButton(Purpose.COMMUTE, "서식 ⑧", trip, engine)
    PurposeButton(Purpose.BUSINESS, "서식 ⑨", trip, engine)
    PurposeButton(Purpose.PRIVATE, "거리만 기록 · 목적지 저장 안 함", trip, engine)
    TextButton(onClick = { engine.skipConfirmation() }, enabled = !engine.inputLocked) {
        Text("나중에 한꺼번에 확인", color = C.Sub, fontSize = 18.sp)
    }
}

@Composable
private fun PurposeButton(purpose: Purpose, sub: String, trip: Trip, engine: ChagokEngine) {
    val recommended = trip.suggested == purpose
    Button(
        onClick = { engine.confirm(trip.tripId, purpose) },
        enabled = !engine.inputLocked,
        modifier = Modifier.fillMaxWidth().height(88.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(2.dp, if (recommended) C.Mint else C.Line),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recommended) C.MintBg else C.Card2,
            contentColor = C.Text,
            disabledContainerColor = C.Card,
            disabledContentColor = C.Mute,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(purpose.label, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        Text(if (recommended) "추천 · $sub" else sub, fontSize = 16.sp, color = if (recommended) C.Mint else C.Mute)
    }
}

@Composable
private fun IdleCard(engine: ChagokEngine) {
    CardBox {
        Text("정차 중", color = C.Sub, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(engine.odometerKm?.let { "${km(it)} km" } ?: "신호 대기 중", color = C.Text, fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
        val source = if (engine.mode == ChagokEngine.Mode.SIMULATOR) "시뮬레이터 재현 값" else "Vehicle SDK Odometer"
        Text("현재 계기판 누적거리 ($source)", color = C.Sub, fontSize = 18.sp)
        Text("출발하면 자동으로 기록을 시작합니다", color = C.Mint, fontSize = 20.sp)
    }
    if (engine.mode == ChagokEngine.Mode.PLEOS && engine.noSignal) {
        // Pleos Connect 가 아닌 기기 등 차량 신호가 없을 때: 시연 흐름으로 바로 넘어가는 버튼
        Button(
            onClick = { engine.switchMode(ChagokEngine.Mode.SIMULATOR) },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, C.Amber),
            colors = ButtonDefaults.buttonColors(containerColor = C.Card2, contentColor = C.Text),
        ) {
            Text("차량 신호 없음 · 시뮬레이터로 시연하기", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (engine.mode == ChagokEngine.Mode.SIMULATOR) {
        Button(
            onClick = { engine.simulateNextLeg() },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.Mint, contentColor = C.Bg),
        ) {
            Text("다음 운행 재생 (목적지: ${engine.nextSimulatedDestination() ?: "-"})", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReportPane(engine: ChagokEngine, onExport: (File) -> Unit) {
    val s = engine.summary()
    val annualCost = 25_000_000L   // 예시: 연 관련비용 2,500만원
    val withLog = LogbookCalculator.deductibleWon(annualCost, s.ratio, hasLog = true)
    val noLog = LogbookCalculator.deductibleWon(annualCost, s.ratio, hasLog = false)
    val extra = (withLog - noLog).coerceAtLeast(0L)
    val hasData = s.totalKm > 0.0
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("⑪ 총주행", "${km(s.totalKm)} km", Modifier.weight(1f))
            Kpi("⑫ 업무 사용", "${km(s.businessUseKm)} km", Modifier.weight(1f))
            Kpi("⑬ 업무사용비율", String.format(Locale.ROOT, "%.1f%%", s.ratio * 100), Modifier.weight(1f), highlight = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Kpi("용도 미확정", "${s.unconfirmedCount}건", Modifier.weight(1f))
            Kpi("무결성 검증", "${engine.verifiedCount} / ${engine.ledger.size}", Modifier.weight(1f))
            Kpi("추가 인정 (계산 사례)", if (hasData) "+${manwon(extra)}" else "-", Modifier.weight(1f))
        }
        Text(
            if (hasData) {
                "계산 사례(가정: 연 관련비용 2,500만원): 미작성 시 ${manwon(noLog)}, 기록 시 ${manwon(withLog)} 인정. " +
                    "실제 인정액은 법령, 보험 가입, 과세조건에 따라 달라집니다"
            } else {
                "운행 기록이 쌓이면 업무사용비율로 비용 인정액 계산 사례를 보여 줍니다 (가정: 연 관련비용 2,500만원)"
            },
            color = C.Mute, fontSize = 14.sp,
        )
        TableHeader()
        val trips = LogbookCalculator.latest(engine.ledger).asReversed()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(trips) { t -> TripRow(t) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { engine.exportCsv()?.let(onExport) },
                enabled = !engine.inputLocked && engine.ledger.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.Mint, contentColor = C.Bg),
                modifier = Modifier.height(64.dp),
            ) { Text("운행기록부 서식 내보내기", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { engine.resetForDemo() }, enabled = !engine.inputLocked) {
                Text("시연 초기화", color = C.Mute, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().background(C.Card2, RoundedCornerShape(8.dp)).padding(vertical = 8.dp, horizontal = 10.dp)) {
        Cell("③ 일자", 1.1f, C.Sub); Cell("⑤ 주행 전", 1.2f, C.Sub); Cell("⑥ 주행 후", 1.2f, C.Sub)
        Cell("⑦ 거리", 0.9f, C.Sub); Cell("용도", 0.9f, C.Sub); Cell("⑩ 비고", 1.5f, C.Sub)
    }
}

@Composable
private fun TripRow(t: Trip) {
    val color = when (t.purpose) {
        Purpose.PRIVATE -> C.Violet
        Purpose.UNCONFIRMED -> C.Amber
        else -> C.Text
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 10.dp)) {
        Cell(time(t.startEpochMs, md), 1.1f, color)
        Cell(km(t.startOdoKm), 1.2f, color)
        Cell(km(t.endOdoKm), 1.2f, color)
        Cell(km(t.distanceKm), 0.9f, color)
        Cell(t.purpose.label, 0.9f, color)
        val remark = t.destination ?: if (t.source.endsWith("_GAP")) "자동 보완" else ""
        Cell(if (t.purpose == Purpose.PRIVATE) "비업무" else remark, 1.5f, color)
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, color: Color) {
    Text(text, color = color, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(weight))
}

@Composable
private fun ExportDialog(engine: ChagokEngine, file: File, onDismiss: () -> Unit) {
    val s = engine.summary()
    val payload = "CHAGOK|${file.name}|total=${km(s.totalKm)}|business=${km(s.businessUseKm)}|" +
        "ratio=${String.format(Locale.ROOT, "%.1f", s.ratio * 100)}|sealed=${engine.verifiedCount}/${engine.ledger.size}"
    val qr = remember(payload) { qrBitmap(payload, 480) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("운행기록부 서식을 저장했습니다", fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(bitmap = qr.asImageBitmap(), contentDescription = "운행기록부 내보내기 QR", modifier = Modifier.size(240.dp))
                Spacer(Modifier.height(8.dp))
                Text("업무용승용차 운행기록부 (CSV, 엑셀 호환)", fontSize = 15.sp)
                Text(file.absolutePath, fontSize = 12.sp, color = C.Mute)
                Spacer(Modifier.height(6.dp))
                Text(
                    "QR에는 기록 요약(총주행, 업무사용비율, 무결성 검증)이 담깁니다. 휴대폰 파일 전달은 개발 예정입니다",
                    fontSize = 12.sp, color = C.Mute,
                )
            }
        },
    )
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
}

// ---------------------------------------------------------------- 작은 구성 요소
@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(C.Card, RoundedCornerShape(22.dp))
            .border(1.dp, C.Line, RoundedCornerShape(22.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier, highlight: Boolean = false) {
    Column(
        modifier
            .background(if (highlight) C.MintBg else C.Card, RoundedCornerShape(16.dp))
            .border(1.dp, if (highlight) C.Mint else C.Line, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(label, color = C.Sub, fontSize = 14.sp)
        Text(value, color = if (highlight) C.Mint else C.Text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Box(
        Modifier.border(1.dp, color, RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun InfoLine(text: String, size: Int = 18) {
    Box(
        Modifier.fillMaxWidth().background(Color(0xFF121F35), RoundedCornerShape(16.dp))
            .border(1.dp, C.Line, RoundedCornerShape(16.dp)).padding(16.dp),
    ) { Text(text, color = C.Sub, fontSize = size.sp) }
}

@Composable
private fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) C.Mint else C.Card2,
            contentColor = if (selected) C.Bg else C.Sub,
        ),
    ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Logo() {
    Box(Modifier.size(40.dp).background(C.Mint, RoundedCornerShape(10.dp)).padding(9.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.width(12.dp).height(5.dp).background(C.Bg, RoundedCornerShape(3.dp)))
            Box(Modifier.width(17.dp).height(5.dp).background(C.Bg, RoundedCornerShape(3.dp)))
            Box(Modifier.width(22.dp).height(5.dp).background(C.Bg, RoundedCornerShape(3.dp)))
        }
    }
}
