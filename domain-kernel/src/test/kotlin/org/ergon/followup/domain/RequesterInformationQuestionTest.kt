package org.ergon.followup.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RequesterInformationQuestionTest {
    @Test
    fun `preserves exact text and counts supplementary characters as code points`() {
        val text = " 😀".repeat(2000)
        assertThat(RequesterInformationQuestion(text).value).isEqualTo(text)
        assertThat(RequesterInformationQuestion(" <script>inert</script>\n").value).startsWith(" ")
    }

    @Test
    fun `rejects empty Unicode whitespace oversized or non PostgreSQL text`() {
        val blanks = (Char.MIN_VALUE..Char.MAX_VALUE).filter(Char::isWhitespace).map(Char::toString)
        (blanks + listOf("", "x".repeat(4001), "😀".repeat(4001), "x\u0000", "\uD800", "\uDC00")).forEach {
            assertThatThrownBy { RequesterInformationQuestion(it) }
                .isInstanceOf(InvalidRequesterInformationQuestionException::class.java)
        }
    }
}
