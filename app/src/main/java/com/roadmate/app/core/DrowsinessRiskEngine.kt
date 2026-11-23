package com.roadmate.app.core

/**
 * 졸음 위험 점수 계산기 (로드메이트의 1단계 "예측")
 *
 * 차량 데이터를 받아 0~100점의 졸음 위험 점수를 계산하고,
 * 점수에 따라 어떤 행동을 할지(평상시 / 먼저 말 걸기 / 휴식 안내) 결정한다.
 *
 * 이 클래스는 차량 SDK에 의존하지 않는 순수 Kotlin 코드라서,
 * 에뮬레이터 없이도 단위 테스트로 동작을 검증할 수 있다.
 */
class DrowsinessRiskEngine {

    /** 위험 단계: 단계별로 로드메이트가 하는 행동이 다르다. */
    enum class Level {
        NORMAL,   // 평상시: 가끔 지역 이야기
        CAUTION,  // 주의: 로드메이트가 먼저 말을 건다
        DANGER    // 위험: 졸음쉼터로 안내한다
    }

    /** 점수 계산에 쓰는 입력 값 (차량 SDK에서 받아 채운다). */
    data class Input(
        val continuousDrivingMinutes: Int,   // 쉬지 않고 운전한 시간 (Odometer)
        val hourOfDay: Int,                  // 현재 시각 0~23
        val cruiseActiveMinutes: Int,        // 크루즈 컨트롤을 켜고 달린 시간 (DrivingMode)
        val minutesSinceDriverInput: Int,    // 마지막 방향지시등·브레이크 조작 후 지난 시간
        val dawWarningOn: Boolean,           // 순정 졸음 경고등(DRIVER_ATTENTION) 켜짐 여부 (Safety)
        val missedResponses: Int,            // 로드메이트가 말 걸었는데 대답 못 한 횟수 (STT)
        val inDrowsyHotspot: Boolean = false // '졸음 지도'의 졸음 다발 구간 안인지
    )

    data class Result(val score: Int, val level: Level, val reasons: List<String>)

    fun evaluate(input: Input): Result {
        var score = 0
        val reasons = mutableListOf<String>()

        // 1) 연속 운전 시간: 2시간 넘게 쉬지 않으면 위험이 크게 오른다
        when {
            input.continuousDrivingMinutes >= 120 -> { score += 30; reasons += "2시간 이상 연속 운전" }
            input.continuousDrivingMinutes >= 90 -> { score += 20; reasons += "1시간 30분 이상 연속 운전" }
            input.continuousDrivingMinutes >= 60 -> { score += 10; reasons += "1시간 이상 연속 운전" }
        }

        // 2) 시간대: 새벽(2~5시)과 식후(14~16시)는 졸음이 잘 오는 시간
        if (input.hourOfDay in 2..5) { score += 15; reasons += "새벽 시간대" }
        else if (input.hourOfDay in 14..16) { score += 10; reasons += "식후 졸음 시간대" }

        // 3) 단조로운 주행: 크루즈를 오래 켜고 조작이 거의 없으면 졸기 쉽다
        if (input.cruiseActiveMinutes >= 20) { score += 10; reasons += "크루즈 장시간 사용" }
        if (input.minutesSinceDriverInput >= 15) { score += 10; reasons += "15분 이상 조작 없음" }

        // 4) 졸음 지도: 사람들이 자주 조는 구간
        if (input.inDrowsyHotspot) { score += 10; reasons += "졸음 다발 구간" }

        // 5) 대답 못 함: 말을 걸었는데 반응이 없으면 강한 신호
        if (input.missedResponses > 0) {
            score += 20 * input.missedResponses
            reasons += "대답 없음 ${input.missedResponses}회"
        }

        // 6) 순정 졸음 경고가 켜졌다면 무조건 '주의' 이상
        if (input.dawWarningOn) { score = maxOf(score + 25, CAUTION_THRESHOLD); reasons += "순정 졸음 경고 켜짐" }

        score = score.coerceIn(0, 100)

        val level = when {
            score >= DANGER_THRESHOLD -> Level.DANGER
            score >= CAUTION_THRESHOLD -> Level.CAUTION
            else -> Level.NORMAL
        }
        return Result(score, level, reasons)
    }

    companion object {
        const val CAUTION_THRESHOLD = 40
        const val DANGER_THRESHOLD = 70
    }
}
