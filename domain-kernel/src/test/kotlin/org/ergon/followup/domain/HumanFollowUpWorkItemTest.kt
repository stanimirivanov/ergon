package org.ergon.followup.domain

import org.assertj.core.api.Assertions.assertThat
import org.ergon.cases.domain.CaseId
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class HumanFollowUpWorkItemTest {
    @Test
    fun `new work opens against its immutable escalation source`() {
        val item = HumanFollowUpWorkItem.open(ID, SOURCE, OPENED_AT)

        assertThat(item.status).isEqualTo(HumanFollowUpWorkItemStatus.OPEN)
        assertThat(item.caseId).isEqualTo(CASE_ID)
        assertThat(item.runId).isEqualTo(RUN_ID)
        assertThat(item.escalationEventId).isEqualTo(EVENT_ID)
        assertThat(item.reason).isEqualTo(REASON)
        assertThat(item.openedAt).isEqualTo(OPENED_AT)
    }

    @Test
    fun `rehydration preserves constrained durable values`() {
        val snapshot = HumanFollowUpWorkItemSnapshot(ID, CASE_ID, RUN_ID, EVENT_ID, REASON, STATUS, OPENED_AT)

        assertThat(HumanFollowUpWorkItem.rehydrate(snapshot))
            .isEqualTo(HumanFollowUpWorkItem.open(ID, SOURCE, OPENED_AT))
    }

    private companion object {
        val ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val CASE_ID = CaseId(UUID.randomUUID())
        val RUN_ID = ResolutionRunId(UUID.randomUUID())
        val EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        val REASON = ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED
        val STATUS = HumanFollowUpWorkItemStatus.OPEN
        val SOURCE = HumanFollowUpSource(CASE_ID, RUN_ID, EVENT_ID, REASON)
        val OPENED_AT = Instant.parse("2026-09-16T12:00:00Z")
    }
}
