package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.followup.domain.HumanFollowUpQueueKey
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

    @Test
    fun `open inbox returns a bounded page and cursor from the last visible item`() {
        val first = StoredHumanFollowUpWorkItem(workItem(Instant.parse("2026-09-16T10:00:00Z"), randomId()), NOW)
        val second = StoredHumanFollowUpWorkItem(workItem(Instant.parse("2026-09-16T11:00:00Z"), randomId()), NOW)
        val hiddenLookahead = StoredHumanFollowUpWorkItem(workItem(NOW, randomId()), NOW)
        `when`(
            repository.listOpenForResolver(criteria(limit = 3)),
        ).thenReturn(listOf(first, second, hiddenLookahead))

        val page = service.listOpen(query(limit = 2))

        assertThat(page.items).containsExactly(first, second)
        assertThat(page.nextCursor)
            .isEqualTo(HumanFollowUpWorkItemCursor(second.item.openedAt, second.item.id))
        verify(repository).listOpenForResolver(criteria(limit = 3))
    }

    @Test
    fun `open inbox validates page size and complete cursor before querying storage`() {
        assertThatThrownBy { service.listOpen(query(limit = 0)) }
            .isInstanceOf(InvalidHumanFollowUpWorkItemPageException::class.java)
        assertThatThrownBy { service.listOpen(query(afterOpenedAt = NOW)) }
            .isInstanceOf(InvalidHumanFollowUpWorkItemPageException::class.java)
    }

    @Test
    fun `open inbox passes a validated queue filter to storage`() {
        `when`(
            repository.listOpenForResolver(criteria(queueKey = QUEUE_KEY, limit = 2)),
        ).thenReturn(emptyList())

        val page = service.listOpen(query(queueKey = QUEUE_KEY.value))

        assertThat(page.items).isEmpty()
        verify(repository).listOpenForResolver(criteria(queueKey = QUEUE_KEY, limit = 2))
    }

    @Test
    fun `open inbox rejects a malformed queue key`() {
        assertThatThrownBy {
            service.listOpen(query(queueKey = "Access Restoration"))
        }.isInstanceOf(InvalidHumanFollowUpQueueException::class.java)
    }

    private fun query(
        queueKey: String? = null,
        limit: Int = 1,
        afterOpenedAt: Instant? = null,
        afterWorkItemId: UUID? = null,
    ) = HumanFollowUpInboxQuery(
        TENANT_ID.value,
        ACTOR_ID.value,
        queueKey,
        limit,
        afterOpenedAt,
        afterWorkItemId,
    )

    private fun criteria(
        queueKey: HumanFollowUpQueueKey? = null,
        limit: Int,
    ) = HumanFollowUpInboxCriteria(TENANT_ID, ACTOR_ID, NOW, queueKey, null, limit)

    private fun workItem(
        openedAt: Instant = NOW,
        id: HumanFollowUpWorkItemId = WORK_ITEM_ID,
    ) = HumanFollowUpWorkItem.open(
        id,
        HumanFollowUpSource(
            CaseId(UUID.randomUUID()),
            ResolutionRunId(UUID.randomUUID()),
            ResolutionRunEventId(UUID.randomUUID()),
            ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED,
        ),
        QUEUE_KEY,
        openedAt,
    )

    private fun randomId() = HumanFollowUpWorkItemId(UUID.randomUUID())

    private companion object {
        val TENANT_ID = TenantId(UUID.randomUUID())
        val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
        val QUEUE_KEY = HumanFollowUpQueueKey.ACCESS_RESTORATION
        val NOW = Instant.parse("2026-09-16T12:00:00Z")
    }
}
