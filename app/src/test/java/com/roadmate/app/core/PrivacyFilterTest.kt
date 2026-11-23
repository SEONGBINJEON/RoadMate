package com.roadmate.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFilterTest {

    @Test fun 평범한_대답은_그대로() {
        assertEquals("괜찮아, 20분만 더 갈게", PrivacyFilter.mask("괜찮아, 20분만 더 갈게"))
    }

    @Test fun 전화번호를_가린다() {
        assertEquals("내 번호는 [전화번호]야", PrivacyFilter.mask("내 번호는 010-0000-0000야"))
        assertEquals("[전화번호] 로 연락해", PrivacyFilter.mask("010 0000 0000 로 연락해"))
    }

    @Test fun 이메일과_차량번호를_가린다() {
        assertEquals("[이메일] 보내줘", PrivacyFilter.mask("abc.def@naver.com 보내줘"))
        assertEquals("내 차는 [차량번호]", PrivacyFilter.mask("내 차는 123가 4567"))
    }

    @Test fun 긴_숫자열을_가린다() {
        assertEquals("계좌 [숫자]", PrivacyFilter.mask("계좌 1002-345-678901"))
    }

    @Test fun 너무_긴_말은_자른다() {
        assertTrue(PrivacyFilter.mask("가".repeat(500)).length <= PrivacyFilter.MAX_LENGTH)
    }
}
