package com.roadmate.app.core

/**
 * 외부 AI로 보내기 전에 운전자 발화에서 개인을 알아볼 수 있는 값을 가린다.
 *
 * 운영사무국 안내(2026-11-09): "비식별화된 짧은 텍스트를 HTTPS로 외부 LLM에 전송"하는 방식만 허용.
 * 그래서 이메일·차량번호·전화번호·긴 숫자열(계좌·카드·주민번호 등)을 자리표시어로 바꾸고 길이를 제한한다.
 */
object PrivacyFilter {
    const val MAX_LENGTH = 200

    private val rules = listOf(
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "[이메일]",
        Regex("""(?<!\d)\d{2,3}\s?[가-힣]\s?\d{4}(?!\d)""") to "[차량번호]",
        Regex("""(?<!\d)01[016789][-.\s]?\d{3,4}[-.\s]?\d{4}(?!\d)""") to "[전화번호]",
        Regex("""(?<!\d)\d(?:[-\s]?\d){7,}(?!\d)""") to "[숫자]"
    )

    fun mask(text: String): String {
        var out = text
        for ((regex, label) in rules) out = regex.replace(out, label)
        return if (out.length > MAX_LENGTH) out.take(MAX_LENGTH) else out
    }
}
