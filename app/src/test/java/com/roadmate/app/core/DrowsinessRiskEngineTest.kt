package com.roadmate.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrowsinessRiskEngineTest {

    private val engine = DrowsinessRiskEngine()

    private fun input(
        minutes: Int = 0, hour: Int = 10, cruise: Int = 0, idle: Int = 0,
        daw: Boolean = false, missed: Int = 0, hotspot: Boolean = false
    ) = DrowsinessRiskEngine.Input(minutes, hour, cruise, idle, daw, missed, hotspot)

    @Test
    fun 짧은_낮_운전은_평상시() {
        val r = engine.evaluate(input(minutes = 30, hour = 10))
        assertEquals(DrowsinessRiskEngine.Level.NORMAL, r.level)
    }

    @Test
    fun 장시간_단조로운_운전은_주의() {
        val r = engine.evaluate(input(minutes = 100, hour = 15, cruise = 30, idle = 20))
        assertEquals(DrowsinessRiskEngine.Level.CAUTION, r.level) // 20+10+10+10 = 50
    }

    @Test
    fun 순정_졸음경고가_켜지면_최소_주의() {
        val r = engine.evaluate(input(minutes = 10, daw = true))
        assertTrue(r.score >= DrowsinessRiskEngine.CAUTION_THRESHOLD)
    }

    @Test
    fun 새벽_장시간_대답없음은_위험() {
        val r = engine.evaluate(input(minutes = 130, hour = 3, missed = 2))
        assertEquals(DrowsinessRiskEngine.Level.DANGER, r.level)
        assertEquals(85, r.score) // 30+15+40
    }

    @Test
    fun 점수는_100을_넘지_않는다() {
        val r = engine.evaluate(input(minutes = 200, hour = 3, cruise = 60, idle = 60, daw = true, missed = 3, hotspot = true))
        assertEquals(100, r.score)
    }
}
