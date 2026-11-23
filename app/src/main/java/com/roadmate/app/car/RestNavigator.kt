package com.roadmate.app.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3단계 "안내": 현재 위치(FusedLocation)에서 가장 가까운 휴게소·졸음쉼터를 골라
 * 차량 내비게이션 목적지로 설정한다(NaviHelper).
 */
class RestNavigator(
    context: Context,
    private val log: (String) -> Unit
) {
    data class RestSpot(val name: String, val latitude: Double, val longitude: Double, val address: String)

    // 시연용 휴식 장소 목록 (좌표는 대략값). 다음 단계에서 공공데이터(졸음쉼터 목록)로 교체 예정.
    private val spots = listOf(
        RestSpot("서울만남의광장휴게소", 37.4598, 127.0397, "서울특별시 서초구 원지동"),
        RestSpot("죽전휴게소", 37.3299, 127.1046, "경기도 용인시 수지구"),
        RestSpot("기흥휴게소", 37.2365, 127.1186, "경기도 용인시 기흥구")
    )

    private val navi = NaviHelper(context.applicationContext)
    private val location = FusedLocation(context.applicationContext)
    private var lastLocation: LocationData? = null

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location != null) lastLocation = location
        }
        override fun onError(exception: Exception) = log("위치 수신 오류: ${exception.message}")
    }

    init {
        try {
            navi.initialize(
                onConnected = { log("내비게이션 연동(NaviHelper) 준비 완료") },
                onFailed = { e -> log("내비게이션 연동 실패: ${e.message}") }
            )
        } catch (e: Exception) { log("내비게이션 사용 불가: ${e.message}") }
        try {
            location.initialize()
            location.registerFusedLocationCallback(locationListener)
        } catch (e: Exception) { log("위치(FusedLocation) 사용 불가: ${e.message}") }
    }

    /** 가장 가까운 휴식 장소로 길 안내를 요청하고, 그 이름을 돌려준다. */
    fun guideToNearest(): String {
        // 에뮬레이터 기본 위치(미국) 등 후보와 300km 이상 떨어진 위치는 신뢰하지 않는다
        val here = lastLocation?.takeIf { loc ->
            spots.any { distanceKm(loc.latitude, loc.longitude, it.latitude, it.longitude) < 300.0 }
        }
        val target = if (here == null) spots.first()
        else spots.minBy { distanceKm(here.latitude, here.longitude, it.latitude, it.longitude) }
        val distanceText = here?.let { " (약 %.1fkm)".format(distanceKm(it.latitude, it.longitude, target.latitude, target.longitude)) } ?: ""
        try {
            navi.requestRoute(
                RouteInfo(
                    latitude = target.latitude,
                    longitude = target.longitude,
                    poiName = target.name,
                    poiId = "",
                    address = target.address,
                    poiSubId = "0"
                )
            )
            log("🧭 길 안내 요청: ${target.name}$distanceText")
        } catch (e: Exception) {
            log("길 안내 요청 실패: ${e.message}")
        }
        return target.name
    }

    /** 휴식이 끝나면 쉼터 길 안내를 종료한다. (안내가 남아 있으면 에뮬레이터에 Dyno Mode 표시가 남는다) */
    fun cancelGuide() {
        try {
            navi.cancelRoute()
            log("🧭 쉼터 길 안내 종료")
        } catch (e: Exception) { log("길 안내 종료 실패: ${e.message}") }
    }

    fun release() {
        try { location.unregisterFusedLocationCallback(locationListener); location.release() } catch (_: Exception) {}
        try { navi.release() } catch (_: Exception) {}
    }

    /** 두 좌표 사이 거리(km) — 하버사인 공식 */
    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
