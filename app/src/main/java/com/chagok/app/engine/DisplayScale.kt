package com.chagok.app.engine

/**
 * 차량 디스플레이 크기에 맞춘 화면 확대 배율.
 * 화면은 가로 1,280dp x 세로 740dp(태블릿 가로)를 기준으로 설계했습니다.
 * Pleos Connect 에뮬레이터 2.1.0 의 앱 창(약 1,680 x 1,210px, 160dpi)처럼 창이 더 크면
 * 같은 배치를 유지한 채 글자와 버튼을 비례 확대합니다(가로·세로 비율 중 작은 값, 1.0~1.6배).
 */
object DisplayScale {
    const val DESIGN_WIDTH_DP = 1280f
    const val DESIGN_HEIGHT_DP = 740f
    const val MAX_SCALE = 1.6f
    private const val TWO_PANE_MIN_WIDTH_DP = 900f

    fun forWindow(widthDp: Float, heightDp: Float): Float {
        if (widthDp < TWO_PANE_MIN_WIDTH_DP) return 1f      // 세로형(창 1개) 배치는 그대로
        val fit = minOf(widthDp / DESIGN_WIDTH_DP, heightDp / DESIGN_HEIGHT_DP)
        return fit.coerceIn(1f, MAX_SCALE)
    }
}
