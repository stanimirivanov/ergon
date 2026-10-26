package org.ergon.followup.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RequesterInformationAnswerTest {
    @Test
    fun `retains exact untrusted Unicode text without trimming or instruction interpretation`() {
        for (text in listOf(" 😀".repeat(2000), " <script>inert</script>\n", "\uFEFF")) {
            assertThat(RequesterInformationAnswer(text).value).isEqualTo(text)
        }
    }

    @Test
    fun `rejects whitespace oversize NUL and lone surrogates with an answer specific failure`() {
        val blanks = (Char.MIN_VALUE..Char.MAX_VALUE).filter(Char::isWhitespace).map(Char::toString)
        (blanks + listOf("", "😀".repeat(4001), "x".repeat(4001), "a\u0000", "\uD800", "\uDC00")).forEach {
            assertThatThrownBy { RequesterInformationAnswer(it) }
                .isInstanceOf(InvalidRequesterInformationAnswerException::class.java)
        }
    }
}
