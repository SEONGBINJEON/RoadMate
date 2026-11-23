package com.roadmate.app.core

/**
 * 확장 기능 '졸음 지도'.
 *
 * 로드메이트 사용자들의 차에서 졸음 경고가 켜지거나 대답이 없었던 위치를 익명으로 모으면
 * "사람들이 실제로 자주 조는 구간"을 알 수 있다. 그 구간에 들어가기 전에 미리 말을 걸고
 * 가까운 졸음쉼터를 알려준다. 모인 데이터는 도로공사·지자체의 졸음쉼터 설치 근거로 쓸 수 있다.
 *
 * 1차 제출에서는 서버 없이, 고속도로 한 노선 위의 '예시 데이터'로 동작을 보여준다.
 */
class DrowsyMap(val routeName: String, val routeLengthKm: Int, val hotspots: List<Hotspot>) {

    /** 졸음 다발 구간: 노선 시작점 기준 위치(km), 구간 길이, 누적 졸음 신호 수 */
    data class Hotspot(val name: String, val startKm: Int, val lengthKm: Int, val reports: Int) {
        val endKm: Int get() = startKm + lengthKm
        fun contains(km: Int) = km in startKm until endKm
    }

    /** 현재 위치가 졸음 다발 구간 안이면 그 구간 */
    fun hotspotAt(km: Int): Hotspot? = hotspots.firstOrNull { it.contains(km) }

    /** 앞으로 만날 가장 가까운 졸음 다발 구간과 남은 거리(km) */
    fun nextHotspot(km: Int): Pair<Hotspot, Int>? =
        hotspots.filter { it.startKm > km }.minByOrNull { it.startKm }?.let { it to (it.startKm - km) }

    /** 신호가 가장 많은 구간부터 */
    fun ranking(): List<Hotspot> = hotspots.sortedByDescending { it.reports }

    companion object {
        /** 시연용 예시 데이터 (실제 통계가 아님) */
        fun sample() = DrowsyMap(
            routeName = "경부고속도로 서울 → 대전 (예시 데이터)",
            routeLengthKm = 150,
            hotspots = listOf(
                Hotspot("안성 부근", 62, 8, 128),
                Hotspot("천안 부근", 88, 6, 74),
                Hotspot("청주 부근", 118, 10, 211)
            )
        )
    }
}
