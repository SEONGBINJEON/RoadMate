package com.roadmate.app.core

/**
 * 로드메이트가 하는 말(대사) 모음.
 *
 * 인터넷이나 AI 키가 없어도 항상 동작하는 '기본 대본'이다.
 * AI(Gemini) 대화가 켜져 있으면 대답 이후의 대화는 AI가 이어 가고,
 * AI 응답이 늦거나 실패하면 이 대본의 문장으로 대신한다.
 */
class DialogueScript {

    /** 대화 주제: 첫 질문 + 이어서 물어볼 질문들 */
    private class Topic(val opener: String, val followUps: List<String>)

    private val topics = listOf(
        Topic("잠깐 저랑 이야기 좀 할까요? 요즘 제일 맛있게 드신 음식이 뭐예요?",
            listOf("오, 맛있겠네요! 그건 어디서 먹을 때 제일 맛있어요?", "다음에 꼭 다시 먹고 싶은 메뉴도 있으세요?")),
        Topic("오늘 가시는 곳에서 제일 기대되는 게 뭐예요?",
            listOf("좋네요! 거기 도착하면 제일 먼저 뭐 하실 거예요?", "같이 가시는 분이나 만나실 분도 있으세요?")),
        Topic("휴게소 하면 떠오르는 간식 있으세요? 저는 호두과자가 궁금해요.",
            listOf("그거 맛있죠! 단 거랑 짭짤한 거 중에 뭐가 더 좋으세요?", "기억에 남는 휴게소가 있으면 알려 주세요.")),
        Topic("요즘 자주 듣는 노래 있으세요? 제목이 궁금해요.",
            listOf("좋은 노래네요! 그 노래는 어떤 기분일 때 들으세요?", "운전할 때 신나는 노래랑 잔잔한 노래 중에 뭐가 좋으세요?")),
        Topic("이번 주말에 하고 싶은 일 하나만 말해 주실래요?",
            listOf("멋진 계획이네요! 그거 하려면 뭐부터 준비해야 해요?", "그 다음 주말엔 뭘 해 보고 싶으세요?"))
    )
    private var topicIndex = 0
    private var lastIntro: String? = null
    private var currentTopic = topics[0]
    private var followUpIndex = 0

    private val acknowledgements = listOf("오, 그렇군요!", "좋네요!", "재미있어요.")
    private var ackIndex = 0

    private val closings = listOf(
        "이야기 나눠 주셔서 고마워요. 졸리면 언제든 '쉬고 싶어'라고 말씀하세요.",
        "좋아요! 계속 옆에서 지켜볼게요. 피곤하면 바로 말씀해 주세요."
    )
    private var closingIndex = 0

    /** 먼저 말 걸기. 상황(운전 시간, 순정 졸음 경고)에 맞는 첫마디를 붙인다. */
    fun opener(continuousMinutes: Int, dawWarningOn: Boolean, inHotspot: Boolean = false): String {
        val intro = when {
            dawWarningOn -> "차가 졸음 경고를 보냈어요."
            inHotspot -> "이 구간은 다른 운전자분들도 자주 졸았던 곳이에요."
            continuousMinutes >= 60 -> "쉬지 않고 ${formatMinutes(continuousMinutes)} 운전하셨어요."
            else -> "운전하시느라 고생 많으세요."
        }
        // 같은 이유(예: 졸음 경고가 계속 켜져 있음)로 다시 말을 걸 때는 같은 첫마디를 반복하지 않는다.
        val spoken = if (intro == lastIntro) "그럼 이번엔 다른 이야기 해 볼까요?" else intro
        lastIntro = intro
        currentTopic = topics[topicIndex % topics.size]
        topicIndex++
        followUpIndex = 0
        return "$spoken ${currentTopic.opener}"
    }

    /**
     * 운전자의 대답에 이어서 할 말.
     * keepTalking=true면 같은 주제로 한 번 더 물어보고(대답을 계속 들으며 깨어 있는지 확인),
     * false면 대화를 부드럽게 마무리한다.
     */
    fun replyTo(@Suppress("UNUSED_PARAMETER") answer: String, keepTalking: Boolean = false): String {
        if (keepTalking && followUpIndex < currentTopic.followUps.size) {
            return currentTopic.followUps[followUpIndex++]
        }
        val ack = acknowledgements[ackIndex++ % acknowledgements.size]
        return "$ack ${closings[closingIndex++ % closings.size]}"
    }

    /** 운전자가 쉬고 싶다는 뜻을 말했는지 */
    fun wantsRest(answer: String): Boolean {
        val t = answer.replace(" ", "")
        // '휴게소'라는 단어만으로는 판단하지 않는다 (예: "기억에 남는 휴게소는 군산휴게소" → 쉬자는 뜻이 아님)
        val tired = listOf("졸려", "졸리", "졸린", "졸립", "졸음", "피곤", "힘들어", "쉬고싶", "쉬고파", "쉴래", "쉬자", "쉬어야", "쉬고가", "자고싶", "눈이감")
        val goRest = Regex("(휴게소|쉼터|졸음쉼터)(로|에)?(가|들르|들러|들릴|세워|안내|찾아)")
        return tired.any { t.contains(it) } || goRest.containsMatchIn(t)
    }

    fun restGuide(): String =
        "많이 피곤하신 것 같아요. 가까운 졸음쉼터로 안내할게요. 도착해서 주차하시면 편하게 쉬실 수 있게 준비해 둘게요."

    fun napStart(napMs: Long): String =
        "휴식을 시작해요. ${if (napMs < 60_000L) "${napMs / 1_000L}초" else formatMinutes((napMs / 60_000L).toInt())} 뒤에 깨워 드릴게요."

    fun wakeUp(): String =
        "휴식 시간이 끝났어요. 아직 졸리면 더 쉬어 주세요. 시트와 주변을 확인한 뒤 출발 여부를 결정해 주세요."

    private fun formatMinutes(m: Int): String {
        val h = m / 60
        val min = m % 60
        return when {
            h > 0 && min > 0 -> "${h}시간 ${min}분"
            h > 0 -> "${h}시간"
            else -> "${min}분"
        }
    }
}
