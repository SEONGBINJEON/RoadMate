package com.roadmate.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrowsyMapTest {

    private val map = DrowsyMap.sample()

    @Test
    fun 구간_안에_있으면_찾아준다() {
        assertEquals("안성 부근", map.hotspotAt(65)?.name)
        assertNull(map.hotspotAt(30))
    }

    @Test
    fun 다음_구간까지_남은_거리() {
        val (spot, distance) = map.nextHotspot(50)!!
        assertEquals("안성 부근", spot.name)
        assertEquals(12, distance)
    }

    @Test
    fun 마지막_구간을_지나면_없음() {
        assertNull(map.nextHotspot(130))
    }

    @Test
    fun 신호가_많은_순서() {
        assertEquals("청주 부근", map.ranking().first().name)
    }
}
