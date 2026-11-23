package com.roadmate.app.core

/**
 * 로드메이트의 "두뇌" — 4단계 흐름(예측 → 대화 → 안내 → 파워냅)을 결정하는 상태 머신.
 *
 * 차량 SDK에서 받은 신호를 on*() 함수로 넣어 주고, tick()을 주기적으로 호출하면
 * 지금 해야 할 행동(Action) 목록을 돌려준다. 실제로 말하기·길 안내·공조 제어를 하는 건
 * 화면(MainActivity)이 이 Action을 받아서 처리한다.
 *
 * 차량 SDK에 의존하지 않는 순수 Kotlin 코드라 에뮬레이터 없이 단위 테스트로 검증할 수 있다.
 */
class RoadMateBrain(
    private val config: Config = Config(),
    private val engine: DrowsinessRiskEngine = DrowsinessRiskEngine(),
    private val script: DialogueScript = DialogueScript(),
    private val now: () -> Long = System::currentTimeMillis
) {

    /** 시간 설정. 시연용(demo)은 기다리는 시간을 짧게 줄인다. */
    data class Config(
        val promptCooldownMs: Long = 2 * 60_000L,  // 먼저 말 건 뒤 다음 말 걸기까지 최소 간격
        val napDurationMs: Long = 15 * 60_000L     // 파워냅 시간
    ) {
        companion object {
            /** 시연용: 말 걸기 간격 15초, 낮잠 30초 */
            val DEMO = Config(promptCooldownMs = 15_000L, napDurationMs = 30_000L)
        }
    }

    /** 현재 단계 */
    enum class Phase { DRIVING, TALKING, GUIDING_TO_REST, AWAITING_NAP_CONFIRMATION, NAPPING }

    /** 두뇌가 내리는 행동 지시 */
    sealed class Action {
        /** 음성으로 말하기. listenAfter=true면 말한 뒤 운전자의 대답을 듣는다. */
        data class Speak(val text: String, val listenAfter: Boolean) : Action()
        /**
         * 운전자의 대답에 이어서 대화하기.
         * AI 대화가 켜져 있으면 AI가 [answer]에 맞는 말을 만들고, 아니면(또는 실패하면) [fallback]을 말한다.
         * listenAfter=true면 말한 뒤 다시 대답을 듣는다.
         */
        data class Reply(val answer: String, val fallback: String, val listenAfter: Boolean) : Action()
        /** 가까운 졸음쉼터로 길 안내 */
        data object NavigateToRest : Action()
        /** 정차 후 낮잠 모드 (조명 어둡게, 수면 온도) */
        data object RequestNapConfirmation : Action()
        data object StartNap : Action()
        data object StopCabin : Action()
        /** 기상 모드 (시원한 바람, 조명 밝게) */
        data object StartWake : Action()
    }

    // ---- 차량에서 들어오는 상태 ----
    var phase: Phase = Phase.DRIVING
        private set
    private var tripMinutes = 0           // 이번 주행의 누적 운전 시간 (Odometer)
    private var restBaselineMinutes = 0   // 마지막으로 쉰 시점의 운전 시간
    private var cruiseOnSinceMs: Long? = null
    private var lastDriverInputMs: Long = now()
    private var dawWarningOn = false
    private var inHotspot = false         // 졸음 지도: 졸음 다발 구간 안인지
    private var missedResponses = 0
    private var parked = true
    private var parkedConfirmed = false
    private var lastPromptMs: Long? = null   // 마지막으로 먼저 말 건 시각
    private var napStartMs: Long = 0
    private var chatTurns = 0                // 이번 말 걸기에서 주고받은 대답 수

    companion object {
        /** 먼저 말 건 뒤 이어서 대화하는 최대 횟수 (대답할 때마다 깨어 있는지 확인) */
        const val MAX_CHAT_TURNS = 3
    }

    /** 화면 표시용 마지막 계산 결과 */
    var lastResult: DrowsinessRiskEngine.Result? = null
        private set

    /** 휴식 이후 연속 운전 시간(분) */
    val continuousMinutes: Int get() = (tripMinutes - restBaselineMinutes).coerceAtLeast(0)

    fun onTripMinutes(minutes: Int) {
        // The vehicle trip counter may reset independently of this Activity.
        val next = minutes.coerceAtLeast(0)
        if (next < tripMinutes) restBaselineMinutes = 0
        tripMinutes = next
    }

    fun onCruise(active: Boolean) {
        cruiseOnSinceMs = if (active) (cruiseOnSinceMs ?: now()) else null
    }

    /** 방향지시등·브레이크 조작 = 운전자가 깨어 있다는 신호 */
    fun onDriverInput() { lastDriverInputMs = now() }

    fun onDawWarning(on: Boolean) { dawWarningOn = on }

    fun onGear(isPark: Boolean, confirmed: Boolean = true) {
        parked = isPark
        parkedConfirmed = isPark && confirmed
    }

    fun confirmNap(): List<Action> {
        if (phase != Phase.AWAITING_NAP_CONFIRMATION || !parkedConfirmed) return emptyList()
        phase = Phase.NAPPING
        napStartMs = now()
        return listOf(Action.StartNap, Action.Speak(script.napStart(config.napDurationMs), false))
    }

    fun cancelRest(): List<Action> {
        if (phase != Phase.NAPPING && phase != Phase.AWAITING_NAP_CONFIRMATION) return emptyList()
        phase = Phase.DRIVING
        lastPromptMs = now()
        return listOf(Action.StopCabin)
    }

    /** 졸음 지도에서 '사람들이 자주 조는 구간'에 들어왔는지 */
    fun onHotspot(inside: Boolean) { inHotspot = inside }

    /** 운전자가 대답했을 때. 돌려준 Action으로 바로 응답한다. */
    fun onAnswer(text: String): List<Action> {
        if (phase == Phase.AWAITING_NAP_CONFIRMATION || phase == Phase.NAPPING) return emptyList()
        missedResponses = 0
        lastDriverInputMs = now()
        return if (script.wantsRest(text)) {
            if (phase == Phase.GUIDING_TO_REST || phase == Phase.NAPPING) {
                return listOf(Action.Speak("네, 지금 쉼터로 안내하고 있어요. 조금만 힘내세요.", listenAfter = false))
            }
            phase = Phase.GUIDING_TO_REST
            listOf(Action.Speak(script.restGuide(), listenAfter = false), Action.NavigateToRest)
        } else {
            chatTurns += 1
            val keepTalking = phase == Phase.TALKING && chatTurns < MAX_CHAT_TURNS
            // 대화 중이거나 막 마무리했으면 그때부터 다시 간격을 둔다 (바로 새 주제로 끼어들지 않음)
            if (phase == Phase.TALKING) lastPromptMs = now()
            listOf(Action.Reply(text, script.replyTo(text, keepTalking), listenAfter = keepTalking))
        }
    }

    /** 말을 걸었는데 정해진 시간 안에 대답이 없을 때 */
    fun onNoAnswer() { missedResponses += 1 }

    /** 주기적으로(예: 1초마다) 호출. 지금 해야 할 행동을 돌려준다. */
    fun tick(hourOfDay: Int, allowConversation: Boolean = true): List<Action> {
        val t = now()

        if (phase == Phase.AWAITING_NAP_CONFIRMATION) {
            return if (!parkedConfirmed) cancelRest() else emptyList()
        }

        // Safety transitions must run even while speech or an AI request is active.
        // 4단계: 파워냅 중이면 시간만 확인
        if (phase == Phase.NAPPING) {
            if (!parkedConfirmed) {
                // Departure is cancellation, not a completed rest. Never move the seat/window.
                return cancelRest()
            }
            if (t - napStartMs >= config.napDurationMs) {
                finishRest()
                return listOf(Action.StartWake, Action.Speak(script.wakeUp(), listenAfter = false))
            }
            return emptyList()
        }

        // 쉬러 가는 중이거나 대화 중에 주차(P)하면 낮잠 모드 시작
        if (parked) {
            if (parkedConfirmed && (phase == Phase.GUIDING_TO_REST || (phase == Phase.TALKING && missedResponses > 0))) {
                phase = Phase.AWAITING_NAP_CONFIRMATION
                return listOf(Action.RequestNapConfirmation)
            }
            return emptyList() // 주차 중에는 말 걸지 않는다
        }

        if (!allowConversation) return emptyList()

        // 1단계: 졸음 위험 예측
        val result = engine.evaluate(
            DrowsinessRiskEngine.Input(
                continuousDrivingMinutes = continuousMinutes,
                hourOfDay = hourOfDay,
                cruiseActiveMinutes = cruiseOnSinceMs?.let { ((t - it) / 60_000L).toInt() } ?: 0,
                minutesSinceDriverInput = ((t - lastDriverInputMs) / 60_000L).toInt(),
                dawWarningOn = dawWarningOn,
                missedResponses = missedResponses,
                inDrowsyHotspot = inHotspot
            )
        )
        lastResult = result

        // 3단계: 위험하면 졸음쉼터로 안내 (한 번만)
        if (result.level == DrowsinessRiskEngine.Level.DANGER && phase != Phase.GUIDING_TO_REST) {
            phase = Phase.GUIDING_TO_REST
            lastPromptMs = t
            return listOf(Action.Speak(script.restGuide(), listenAfter = false), Action.NavigateToRest)
        }

        // 2단계: 주의 단계면 먼저 말 걸기 (너무 자주 말하지 않도록 간격 유지)
        if (result.level == DrowsinessRiskEngine.Level.CAUTION &&
            phase != Phase.GUIDING_TO_REST &&
            (lastPromptMs?.let { t - it >= config.promptCooldownMs } ?: true)
        ) {
            phase = Phase.TALKING
            lastPromptMs = t
            chatTurns = 0
            return listOf(Action.Speak(script.opener(continuousMinutes, dawWarningOn, inHotspot), listenAfter = true))
        }

        if (result.level == DrowsinessRiskEngine.Level.NORMAL && phase == Phase.TALKING) {
            phase = Phase.DRIVING
        }
        return emptyList()
    }

    private fun finishRest() {
        phase = Phase.DRIVING
        lastResult = null
        restBaselineMinutes = tripMinutes
        missedResponses = 0
        dawWarningOn = false
        lastDriverInputMs = now()
        lastPromptMs = now()
    }
}
