package org.ergon.followup.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HumanFollowUpPriorityTest {
    @Test
    fun `priority revisions start at zero and advance independently without wrapping`() {
        assertThat(HumanFollowUpPriorityRevision(0).next().value).isEqualTo(1)
        assertThat(HumanFollowUpPriorityRevision(Long.MAX_VALUE - 1).next().value).isEqualTo(Long.MAX_VALUE)
        assertThatThrownBy { HumanFollowUpPriorityRevision(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HumanFollowUpPriorityRevision(Long.MAX_VALUE).next() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
