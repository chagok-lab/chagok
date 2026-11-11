package com.chagok.app.navi

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.chagok.app.vehicle.SdkReflect

/**
 * NaviHelper SDK 3.0.5 연동 (공식 샘플 pleos-sample-navi-app 과 같은 초기화·리스너 구조).
 * 사용 이벤트: onDestinationInfo(방문처 이름), onRouteStarted / onDestinationChanged(목적지 재조회), onDestinationArrived(도착).
 */
class NaviSignals(
    context: Context,
    private val onDestination: (String?) -> Unit,
    private val onArrived: () -> Unit,
    private val onStatus: (String) -> Unit,
) : NaviHelperEventListener {

    private val appContext = context.applicationContext
    private var naviHelper: NaviHelper? = null

    fun start() {
        try {
            val helper = NaviHelper(appContext)
            naviHelper = helper
            helper.initialize()
            helper.addListener(this)
            helper.getDestinationInfo()
            onStatus("NaviHelper 연결됨")
        } catch (t: Throwable) {
            runCatching { naviHelper?.release() }
            naviHelper = null
            onStatus("NaviHelper 연결 실패: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun stop() {
        val helper = naviHelper ?: return
        runCatching { helper.removeListener(this) }
        runCatching { helper.release() }
        naviHelper = null
    }

    private fun refreshDestination() {
        runCatching { naviHelper?.getDestinationInfo() }
    }

    // ---- 사용하는 이벤트
    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        val name = SdkReflect.firstString(destinationInfo, listOf("getPoiName", "getName", "getDestinationName", "getAddress"))
        onDestination(name)
    }

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
        refreshDestination()
    }

    override fun onDestinationChanged() {
        refreshDestination()
    }

    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
        onArrived()
    }

    override fun onError(errorCode: NaviErrorCode) {
        onStatus("NaviHelper 오류: $errorCode")
    }

    // ---- 이 앱에서 쓰지 않는 이벤트 (인터페이스 구현)
    @Deprecated("Deprecated by NaviHelperEventListener")
    override fun onRouteEnded() {}
    override fun onRoutingCompleted() {}
    override fun onReRouteEnded() {}
    override fun onRouteOptionChanged(routeOption: RouteOption) {}
    override fun onRouteCancelled() {}
    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {}
    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {}
    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {}
    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {}
    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {}
    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {}
    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {}
    override fun onCurrentRoadSpeedLimit(speedLimit: Int) {}
    override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {}
    override fun onDrivingInfo(drivingInfo: DrivingInfo) {}
    override fun onBookMarkChanged() {}
    override fun onNavigationStatus(status: NavigationStatus) {}
    override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {}
    override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) {}
    override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {}
    override fun onTripInfo(tripInfo: TripInfo) {}
}
