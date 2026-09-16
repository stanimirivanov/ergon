package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.followup.domain.HumanFollowUpSource
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HumanFollowUpWorkItemQueryServiceTest {
    private val repository = mock(HumanFollowUpWorkItemRepository::class.java)
    private val service = HumanFollowUpWorkItemQueryService(repository, Clock.fixed(NOW, ZoneOffset.UTC))

    @Test
    fun `resolver-scoped lookup returns stored work`() {
        val stored = StoredHumanFollowUpWorkItem(workItem(), NOW)
        `when`(repository.findForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)).thenReturn(stored)

        val result = service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value)

        assertThat(result).isEqualTo(stored)
        verify(repository).findForResolver(TENANT_ID, WORK_ITEM_ID, ACTOR_ID, NOW)
    }

    @Test
    fun `absence and insufficient authority share the not-found contract`() {
        assertThatThrownBy { service.get(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(HumanFollowUpWorkItemNotFoundException::class.java)
    }

    private fun workItem() =
        HumanFollowUpWorkItem.open(
            WORK_ITEM_ID,
            HumanFollowUpSource(
                CaseId(UUID.randomUUID()),
                ResolutionRunId(UUID.randomUUID()),
                ResolutionRunEventId(UUID.randomUUID()),
                ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED,
            ),
            NOW,
        )

    private companion object {
        val TENANT_ID = TenantId(UUID.randomUUID())
        val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
        val NOW = Instant.parse("2026-09-16T12:00:00Z")
    }
}
