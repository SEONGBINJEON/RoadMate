package com.roadmate.app.core

import com.roadmate.app.core.RoadMateBrain.Action
import com.roadmate.app.core.RoadMateBrain.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4단계 흐름(예측 → 대화 → 안내 → 파워냅)이 순서대로 동작하는지 검증 */
class RoadMateBrainTest {

    private var clock = 0L
    private val brain = RoadMateBrain(RoadMateBrain.Config.DEMO, now = { clock })

    private fun driving() { brain.onGear(isPark = false) }

    @Test
    fun 짧게_운전하면_말을_걸지_않는다() {
        driving()
        brain.onTripMinutes(20)
        assertTrue(brain.tick(hourOfDay = 10).isEmpty())
        assertEquals(Phase.DRIVING, brain.phase)
    }

    @Test
    fun 순정_졸음경고가_켜지면_먼저_말을_건다() {
        driving()
        brain.onDawWarning(true)
        val actions = brain.tick(hourOfDay = 10)
        val speak = actions.single() as Action.Speak
        assertTrue(speak.listenAfter)
        assertTrue(speak.text.contains("졸음 경고"))
        assertEquals(Phase.TALKING, brain.phase)
    }

    @Test
    fun 말_걸기는_간격을_두고_반복한다() {
        driving()
        brain.onDawWarning(true)
        assertEquals(1, brain.tick(10).size)
        clock += 5_000
        assertTrue(brain.tick(10).isEmpty())        // 15초 간격 전에는 다시 말하지 않음
        clock += 15_000
        assertEquals(1, brain.tick(10).size)
    }

    @Test
    fun 대답이_계속_없으면_졸음쉼터로_안내한다() {
        driving()
        brain.onTripMinutes(130)                    // 30점
        brain.onDawWarning(true)                    // +25점 → 주의
        brain.tick(10)
        brain.onNoAnswer()                          // +20점
        brain.onNoAnswer()                          // +20점 → 95점 위험
        clock += 20_000
        val actions = brain.tick(10)
        assertTrue(actions.contains(Action.NavigateToRest))
        assertEquals(Phase.GUIDING_TO_REST, brain.phase)
    }

    @Test
    fun 쉬고_싶다고_말하면_바로_안내한다() {
        driving()
        val actions = brain.onAnswer("너무 졸려 쉬고 싶어")
        assertTrue(actions.contains(Action.NavigateToRest))
        assertEquals(Phase.GUIDING_TO_REST, brain.phase)
    }

    @Test
    fun 주차하면_낮잠_시간이_지나면_깨운다() {
        driving()
        brain.onTripMinutes(150)
        brain.onAnswer("쉬고 싶어")                  // 안내 단계
        brain.onGear(isPark = true)
        assertTrue(brain.tick(10).contains(Action.RequestNapConfirmation))
        assertEquals(Phase.AWAITING_NAP_CONFIRMATION, brain.phase)
        assertTrue(brain.confirmNap().contains(Action.StartNap))
        assertEquals(Phase.NAPPING, brain.phase)

        clock += 10_000
        assertTrue(brain.tick(10).isEmpty())        // 아직 낮잠 중
        clock += 25_000
        assertTrue(brain.tick(10).contains(Action.StartWake))
        assertEquals(Phase.DRIVING, brain.phase)
        assertEquals(0, brain.continuousMinutes)    // 쉰 뒤에는 연속 운전 시간이 다시 0부터
    }

    @Test
    fun 주차_중에는_말을_걸지_않는다() {
        brain.onGear(isPark = true)
        brain.onDawWarning(true)
        assertTrue(brain.tick(3).isEmpty())
    }

    @Test
    fun 이미_안내_중이면_다시_안내하지_않는다() {
        driving()
        brain.onAnswer("쉬고 싶어")
        val again = brain.onAnswer("쉬고 싶어")
        assertTrue(again.none { it == Action.NavigateToRest })
    }

    @Test
    fun 졸음_다발_구간에서는_더_일찍_말을_건다() {
        driving()
        brain.onTripMinutes(130)                    // 30점 → 평상시
        assertTrue(brain.tick(hourOfDay = 10).isEmpty())
        brain.onHotspot(true)                       // +10점 → 40점 주의
        val speak = brain.tick(hourOfDay = 10).single() as Action.Speak
        assertTrue(speak.text.contains("자주 졸았던"))
    }

    @Test
    fun 대답하면_같은_주제로_이어서_묻고_세번째에_마무리한다() {
        driving()
        brain.onDawWarning(true)
        brain.tick(10)                               // 먼저 말 걸기 → 대화 시작
        val first = brain.onAnswer("김치찌개요").single() as Action.Reply
        assertTrue(first.listenAfter)                // 이어서 질문하고 대답을 듣는다
        assertTrue(first.fallback.endsWith("?"))
        val second = brain.onAnswer("집에서요").single() as Action.Reply
        assertTrue(second.listenAfter)
        val third = brain.onAnswer("네").single() as Action.Reply
        assertTrue(!third.listenAfter)               // 세 번째 대답에서는 마무리
        assertTrue(third.fallback.contains("쉬고 싶어"))
    }

    @Test
    fun 대화가_이어지는_동안에는_새_주제로_끼어들지_않는다() {
        driving()
        brain.onDawWarning(true)
        brain.tick(10)
        clock += 14_000
        brain.onAnswer("음악 듣고 있어요")
        clock += 10_000                              // 처음 말 건 지 24초지만 마지막 대답은 10초 전
        assertTrue(brain.tick(10).isEmpty())
    }

    @Test
    fun 휴게소_이야기만으로는_안내하지_않는다() {
        val script = DialogueScript()
        assertTrue(!script.wantsRest("군산휴게소요"))
        assertTrue(!script.wantsRest("휴게소 호두과자 좋아해요"))
        assertTrue(script.wantsRest("휴게소 가자"))
        assertTrue(script.wantsRest("졸음쉼터로 안내해 줘"))
        assertTrue(script.wantsRest("좀 졸린 것 같아"))
        assertTrue(script.wantsRest("쉬고 싶어"))
    }

    @Test
    fun 대화를_마무리한_직후에는_바로_새_주제로_말걸지_않는다() {
        driving()
        brain.onDawWarning(true)
        brain.tick(10)
        brain.onAnswer("김치찌개"); brain.onAnswer("집에서요")
        clock += 20_000
        brain.onAnswer("네")                         // 마무리
        clock += 2_000
        assertTrue(brain.tick(10).isEmpty())         // 2초 만에 다시 묻지 않음
        clock += 15_000
        assertEquals(1, brain.tick(10).size)         // 간격이 지나면 다시 말 걸기
    }

    @Test
    fun 졸음경고가_계속_켜져_있어도_같은_첫마디를_반복하지_않는다() {
        driving()
        brain.onDawWarning(true)
        val first = (brain.tick(10).single() as Action.Speak).text
        clock += 20_000
        val second = (brain.tick(10).single() as Action.Speak).text
        assertTrue(first.contains("졸음 경고"))
        assertTrue(!second.contains("졸음 경고"))
    }

    @Test
    fun simulatedParkNeverStartsOrRequestsNap() {
        driving()
        brain.onAnswer("쉬고 싶어")
        brain.onGear(true, confirmed = false)
        assertTrue(brain.tick(10).isEmpty())
        assertTrue(brain.confirmNap().isEmpty())
        assertEquals(Phase.GUIDING_TO_REST, brain.phase)
    }

    @Test
    fun parkNeedsExplicitConfirmation() {
        driving()
        brain.onAnswer("쉬고 싶어")
        brain.onGear(true)
        assertEquals(listOf(Action.RequestNapConfirmation), brain.tick(10))
        assertTrue(brain.tick(10).isEmpty())
        assertEquals(Phase.AWAITING_NAP_CONFIRMATION, brain.phase)
    }

    @Test
    fun departureDuringNapCancelsWithoutWakeCommandsOrRestCredit() {
        driving()
        brain.onTripMinutes(130)
        brain.onAnswer("쉬고 싶어")
        brain.onGear(true)
        brain.tick(10)
        brain.confirmNap()
        clock += 1_000
        brain.onGear(false)
        val actions = brain.tick(10, allowConversation = false)
        assertEquals(listOf(Action.StopCabin), actions)
        assertEquals(130, brain.continuousMinutes)
    }

    @Test
    fun signalLossBeforeConfirmationCannotStartNap() {
        driving()
        brain.onAnswer("쉬고 싶어")
        brain.onGear(true)
        brain.tick(10)
        brain.onGear(true, confirmed = false)
        assertTrue(brain.confirmNap().isEmpty())
        assertEquals(listOf(Action.StopCabin), brain.tick(10))
    }

    @Test
    fun userCancellationStopsAndPreservesDrivingTime() {
        driving()
        brain.onTripMinutes(100)
        brain.onAnswer("쉬고 싶어")
        brain.onGear(true)
        brain.tick(10)
        brain.confirmNap()
        assertEquals(listOf(Action.StopCabin), brain.cancelRest())
        assertEquals(100, brain.continuousMinutes)
        clock += 60_000
        assertTrue(brain.tick(10).isEmpty())
    }

    @Test
    fun speechDoesNotPreventNapTimerCompletion() {
        driving()
        brain.onAnswer("쉬고 싶어")
        brain.onGear(true)
        brain.tick(10)
        brain.confirmNap()
        clock += 31_000
        assertTrue(brain.tick(10, allowConversation = false).contains(Action.StartWake))
    }

    @Test
    fun vehicleTripResetDoesNotSuppressFutureContinuousMinutes() {
        driving()
        brain.onTripMinutes(130)
        brain.onAnswer("쉬고 싶어")
        brain.onGear(true)
        brain.tick(10)
        brain.confirmNap()
        clock += 31_000
        brain.tick(10)
        brain.onTripMinutes(5)
        assertEquals(5, brain.continuousMinutes)
    }
}
