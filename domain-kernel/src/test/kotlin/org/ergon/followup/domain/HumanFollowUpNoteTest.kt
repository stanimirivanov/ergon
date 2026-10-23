package org.ergon.followup.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HumanFollowUpNoteTest {
    @Test
    fun `preserves exact inert text and counts supplementary Unicode characters once`() {
        val text = "😀".repeat(4000)
        assertThat(HumanFollowUpNoteText(text).value).isEqualTo(text)
        assertThat(HumanFollowUpNoteText(" <script>untrusted</script> \n").value).startsWith(" ")
    }

    @Test
    fun `rejects blank oversized or non PostgreSQL Unicode strings`() {
        listOf("", " \n\t", "x".repeat(4001), "😀".repeat(4001), "bad\u0000text", "\uD800", "\uDC00").forEach { text ->
            assertThatThrownBy {
                HumanFollowUpNoteText(text)
            }.isInstanceOf(InvalidHumanFollowUpNoteTextException::class.java)
        }
    }
}
