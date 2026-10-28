package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HumanNotificationServiceTest {
    private val tenant = TenantId(UUID(0, 1))
    private val actor = HumanActorId(UUID(0, 2))
    private val reader = Mockito.mock(HumanNotificationReader::class.java)
    private val authorities = Mockito.mock(HumanAuthorityRepository::class.java)
    private val at = Instant.EPOCH
    private val evidence =
        StoredApprovalAuthorityEvidence(
            ApprovalAuthorityEvidence(
                ApprovalAuthorityEvidenceId(UUID(0, 4)),
                actor,
                ApprovalAuthority.RESOLVER,
                null,
                ApprovalAuthorityEvidenceSource.create("test", "expiry"),
                at,
                at.plusSeconds(1),
            ),
            at,
        )

    @Test
    fun `requester bound truncates without requiring approval authority or altering order`() {
        val rows =
            (1..51).map {
                HumanNotification(HumanNotificationKind.INFORMATION_REQUESTED, UUID(0, it.toLong()), UUID(0, 3), at)
            }
        Mockito.`when`(reader.latest(tenant, actor, HumanNotificationAudience.REQUESTER, 51)).thenReturn(rows)
        val page = service().list(tenant.value, actor.value, HumanNotificationAudience.REQUESTER)
        assertThat(page.items).containsExactlyElementsOf(rows.take(50))
        assertThat(page.truncated).isTrue()
        Mockito.verifyNoInteractions(authorities)
    }

    @Test
    fun `missing resolver authority prevents every delivery query`() {
        assertThatThrownBy { service().list(tenant.value, actor.value, HumanNotificationAudience.RESOLVER) }
            .isInstanceOf(HumanNotificationsForbiddenException::class.java)
        Mockito.verifyNoInteractions(reader)
    }

    @Test
    fun `authority expiring during retrieval withdraws even an empty result`() {
        val clock = Mockito.mock(Clock::class.java)
        Mockito.`when`(clock.instant()).thenReturn(at, at, at.plusSeconds(1))
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at))
            .thenReturn(evidence)
        Mockito.`when`(reader.latest(tenant, actor, HumanNotificationAudience.RESOLVER, 51)).thenReturn(emptyList())
        assertThatThrownBy { service(clock).list(tenant.value, actor.value, HumanNotificationAudience.RESOLVER) }
            .isInstanceOf(HumanNotificationsForbiddenException::class.java)
        Mockito.verify(authorities).findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at.plusSeconds(1))
    }

    @Test
    fun `expiry during the authority lookup prevents the delivery query`() {
        val clock = Mockito.mock(Clock::class.java)
        Mockito.`when`(clock.instant()).thenReturn(at, at.plusSeconds(1))
        Mockito
            .`when`(authorities.findCurrent(tenant, actor, ApprovalAuthority.RESOLVER, null, at))
            .thenReturn(evidence)
        assertThatThrownBy { service(clock).list(tenant.value, actor.value, HumanNotificationAudience.RESOLVER) }
            .isInstanceOf(HumanNotificationsForbiddenException::class.java)
        Mockito.verifyNoInteractions(reader)
    }

    @Test
    fun `storage failure is not converted into an empty feed`() {
        val defect = IllegalStateException("storage unavailable")
        Mockito.`when`(reader.latest(tenant, actor, HumanNotificationAudience.REQUESTER, 51)).thenThrow(defect)
        assertThatThrownBy { service().list(tenant.value, actor.value, HumanNotificationAudience.REQUESTER) }
            .isSameAs(defect)
    }

    private fun service(clock: Clock = Clock.fixed(at, ZoneOffset.UTC)): HumanNotificationService =
        HumanNotificationService(reader, authorities, clock)
}
