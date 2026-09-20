package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpQueueKey
import org.ergon.followup.domain.HumanFollowUpSource
import org.ergon.followup.domain.HumanFollowUpWorkItem
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.ergon.resolution.domain.ResolutionRunEscalationReason
import org.ergon.resolution.domain.ResolutionRunEventId
import org.ergon.resolution.domain.ResolutionRunId
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HumanFollowUpClaimServiceTest {
    private val claims = mock(HumanFollowUpClaimRepository::class.java)
    private val authorities = mock(HumanAuthorityRepository::class.java)
    private val identities = mock(HumanFollowUpClaimIdentityGenerator::class.java)
    private val service =
        HumanFollowUpClaimService(
            claims,
            authorities,
            identities,
            object : TransactionRunner {
                override fun <T : Any> required(block: () -> T): T = block()
            },
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    @Test
    fun `current resolver claims open work once`() {
        authorize(ACTOR_ID)
        `when`(claims.lockOpenWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(true)
        `when`(identities.next()).thenReturn(CLAIM_ID)
        val claim = HumanFollowUpClaim.claim(CLAIM_ID, WORK_ITEM_ID, evidence(ACTOR_ID), NOW)
        val stored = StoredHumanFollowUpClaim(claim, NOW)
        `when`(claims.create(TENANT_ID, claim)).thenReturn(stored)

        val result = service.claim(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value)

        assertThat(result).isEqualTo(HumanFollowUpClaimRecording(stored, created = true))
        verify(claims).create(TENANT_ID, claim)
    }

    @Test
    fun `same resolver replays original claim without allocating identity`() {
        authorize(ACTOR_ID)
        `when`(claims.lockOpenWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(true)
        val existing =
            StoredHumanFollowUpClaim(
                HumanFollowUpClaim.claim(CLAIM_ID, WORK_ITEM_ID, evidence(ACTOR_ID), NOW),
                NOW,
            )
        `when`(claims.findByWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(existing)

        val result = service.claim(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value)

        assertThat(result).isEqualTo(HumanFollowUpClaimRecording(existing, created = false))
        verifyNoInteractions(identities)
    }

    @Test
    fun `different resolver cannot replace existing owner`() {
        authorize(OTHER_ACTOR_ID)
        `when`(claims.lockOpenWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(true)
        val existing =
            StoredHumanFollowUpClaim(
                HumanFollowUpClaim.claim(CLAIM_ID, WORK_ITEM_ID, evidence(ACTOR_ID), NOW),
                NOW,
            )
        `when`(claims.findByWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(existing)

        assertThatThrownBy { service.claim(TENANT_ID.value, WORK_ITEM_ID.value, OTHER_ACTOR_ID.value) }
            .isInstanceOf(HumanFollowUpAlreadyClaimedException::class.java)
        verifyNoInteractions(identities)
    }

    @Test
    fun `claim requires authority before revealing work existence`() {
        assertThatThrownBy { service.claim(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(CurrentHumanFollowUpResolverAuthorityNotFoundException::class.java)
        verifyNoInteractions(claims, identities)
    }

    @Test
    fun `authorized resolver cannot claim absent work`() {
        authorize(ACTOR_ID)

        assertThatThrownBy { service.claim(TENANT_ID.value, WORK_ITEM_ID.value, ACTOR_ID.value) }
            .isInstanceOf(HumanFollowUpWorkItemNotFoundException::class.java)
        verifyNoInteractions(identities)
    }

    @Test
    fun `owned work returns a bounded page and cursor from the last visible claim`() {
        val first = ownedWork(NOW.minusSeconds(30), randomClaimId(), randomWorkItemId())
        val second = ownedWork(NOW.minusSeconds(20), randomClaimId(), randomWorkItemId())
        val hiddenLookahead = ownedWork(NOW.minusSeconds(10), randomClaimId(), randomWorkItemId())
        `when`(
            claims.listOwnedForResolver(TENANT_ID, ACTOR_ID, NOW, null, 3),
        ).thenReturn(listOf(first, second, hiddenLookahead))

        val page = service.listOwned(TENANT_ID.value, ACTOR_ID.value, 2, null, null)

        assertThat(page.items).containsExactly(first, second)
        assertThat(page.nextCursor)
            .isEqualTo(
                ResolverOwnedHumanFollowUpCursor(
                    second.claim.claim.claimedAt,
                    second.claim.claim.id,
                ),
            )
        verify(claims).listOwnedForResolver(TENANT_ID, ACTOR_ID, NOW, null, 3)
    }

    @Test
    fun `owned work validates page size and complete cursor before querying storage`() {
        assertThatThrownBy {
            service.listOwned(TENANT_ID.value, ACTOR_ID.value, 0, null, null)
        }.isInstanceOf(InvalidResolverOwnedHumanFollowUpPageException::class.java)
        assertThatThrownBy {
            service.listOwned(TENANT_ID.value, ACTOR_ID.value, 50, NOW, null)
        }.isInstanceOf(InvalidResolverOwnedHumanFollowUpPageException::class.java)

        verifyNoInteractions(claims)
    }

    private fun authorize(actorId: HumanActorId) {
        `when`(authorities.findCurrent(TENANT_ID, actorId, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(StoredApprovalAuthorityEvidence(evidence(actorId), NOW))
    }

    private fun evidence(actorId: HumanActorId) =
        ApprovalAuthorityEvidence(
            EVIDENCE_ID,
            actorId,
            ApprovalAuthority.RESOLVER,
            null,
            ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
            NOW.minusSeconds(60),
            NOW.plusSeconds(60),
        )

    private fun ownedWork(
        claimedAt: Instant,
        claimId: HumanFollowUpClaimId,
        workItemId: HumanFollowUpWorkItemId,
    ): ResolverOwnedHumanFollowUpWork =
        ResolverOwnedHumanFollowUpWork(
            StoredHumanFollowUpWorkItem(
                HumanFollowUpWorkItem.open(
                    workItemId,
                    HumanFollowUpSource(CASE_ID, RUN_ID, EVENT_ID, REASON),
                    HumanFollowUpQueueKey.ACCESS_RESTORATION,
                    OPENED_AT,
                ),
                NOW,
            ),
            StoredHumanFollowUpClaim(
                HumanFollowUpClaim.claim(claimId, workItemId, evidence(ACTOR_ID), claimedAt),
                NOW,
            ),
        )

    private fun randomClaimId() = HumanFollowUpClaimId(UUID.randomUUID())

    private fun randomWorkItemId() = HumanFollowUpWorkItemId(UUID.randomUUID())

    private companion object {
        val TENANT_ID = TenantId(UUID.randomUUID())
        val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val CLAIM_ID = HumanFollowUpClaimId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
        val OTHER_ACTOR_ID = HumanActorId(UUID.randomUUID())
        val EVIDENCE_ID = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val CASE_ID = CaseId(UUID.randomUUID())
        val RUN_ID = ResolutionRunId(UUID.randomUUID())
        val EVENT_ID = ResolutionRunEventId(UUID.randomUUID())
        val REASON = ResolutionRunEscalationReason.RETRY_ATTEMPT_LIMIT_REACHED
        val OPENED_AT = Instant.parse("2026-09-18T11:00:00Z")
        val NOW = Instant.parse("2026-09-18T12:00:00Z")
    }
}
