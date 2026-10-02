package org.ergon.controlplane.followup.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.controlplane.cases.application.TransactionRunner
import org.ergon.controlplane.identity.application.HumanAuthorityRepository
import org.ergon.controlplane.identity.application.StoredApprovalAuthorityEvidence
import org.ergon.followup.domain.HumanFollowUpClaim
import org.ergon.followup.domain.HumanFollowUpClaimId
import org.ergon.followup.domain.HumanFollowUpOwnershipRevision
import org.ergon.followup.domain.HumanFollowUpWorkItemId
import org.ergon.identity.domain.HumanActorId
import org.ergon.identity.domain.TenantId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HumanFollowUpReleaseServiceTest {
    private val claims = mock(HumanFollowUpClaimRepository::class.java)
    private val commands = mock(HumanFollowUpClaimCommandRepository::class.java)
    private val releases = mock(HumanFollowUpReleaseRepository::class.java)
    private val authorities = mock(HumanAuthorityRepository::class.java)
    private val service =
        HumanFollowUpReleaseService(
            claims,
            commands,
            releases,
            authorities,
            object : TransactionRunner {
                override fun <T : Any> required(block: () -> T): T = block()
            },
            Clock.fixed(NOW, ZoneOffset.UTC),
            enabled = true,
        )

    @Test
    fun `release stays closed until rollout explicitly enables it`() {
        val disabled =
            HumanFollowUpReleaseService(
                claims,
                commands,
                releases,
                authorities,
                object : TransactionRunner {
                    override fun <T : Any> required(block: () -> T): T = block()
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                enabled = false,
            )

        assertThatThrownBy { disabled.release(command()) }
            .isInstanceOf(HumanFollowUpReleaseUnavailableException::class.java)
        verifyNoInteractions(claims, commands, releases, authorities)
    }

    @Test
    fun `current owner releases exact claim at its revision`() {
        authorize(ACTOR_ID)
        `when`(claims.lockOpenWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(true)
        `when`(claims.findByWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(storedClaim())
        `when`(commands.currentOwnershipRevision(TENANT_ID, WORK_ITEM_ID))
            .thenReturn(HumanFollowUpOwnershipRevision(1))
        `when`(releases.record(TENANT_ID, storedClaim(), EVIDENCE_ID, HumanFollowUpOwnershipRevision(2), NOW))
            .thenReturn(storedRelease())

        assertThat(service.release(command()))
            .isEqualTo(HumanFollowUpReleaseRecording(storedRelease(), created = true))
        verify(releases).record(TENANT_ID, storedClaim(), EVIDENCE_ID, HumanFollowUpOwnershipRevision(2), NOW)
    }

    @Test
    fun `exact replay cannot clear a later owner`() {
        authorize(ACTOR_ID)
        `when`(claims.lockOpenWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(true)
        `when`(releases.findByClaim(TENANT_ID, WORK_ITEM_ID, CLAIM_ID)).thenReturn(storedRelease())

        assertThat(service.release(command()))
            .isEqualTo(HumanFollowUpReleaseRecording(storedRelease(), created = false))
        verifyNoInteractions(commands)
    }

    @Test
    fun `different resolver cannot discover or replay another release`() {
        authorize(OTHER_ACTOR_ID)
        `when`(claims.lockOpenWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(true)
        `when`(releases.findByClaim(TENANT_ID, WORK_ITEM_ID, CLAIM_ID)).thenReturn(storedRelease())

        assertThatThrownBy { service.release(command(actorId = OTHER_ACTOR_ID)) }
            .isInstanceOf(HumanFollowUpReleaseNotFoundException::class.java)
        verifyNoInteractions(commands)
    }

    @Test
    fun `stale revision cannot release the current claim`() {
        authorize(ACTOR_ID)
        `when`(claims.lockOpenWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(true)
        `when`(claims.findByWorkItem(TENANT_ID, WORK_ITEM_ID)).thenReturn(storedClaim())
        `when`(commands.currentOwnershipRevision(TENANT_ID, WORK_ITEM_ID))
            .thenReturn(HumanFollowUpOwnershipRevision(3))

        assertThatThrownBy { service.release(command()) }
            .isInstanceOf(HumanFollowUpOwnershipRevisionConflictException::class.java)
    }

    @Test
    fun `invalid revision and lost authority fail before work lookup`() {
        assertThatThrownBy { service.release(command(expectedRevision = -1)) }
            .isInstanceOf(InvalidHumanFollowUpReleaseCommandException::class.java)
        assertThatThrownBy { service.release(command()) }
            .isInstanceOf(CurrentHumanFollowUpResolverAuthorityNotFoundException::class.java)
        verifyNoInteractions(claims, commands, releases)
    }

    private fun authorize(actorId: HumanActorId) {
        `when`(authorities.findCurrent(TENANT_ID, actorId, ApprovalAuthority.RESOLVER, null, NOW))
            .thenReturn(StoredApprovalAuthorityEvidence(evidence(actorId), NOW))
    }

    private fun command(
        actorId: HumanActorId = ACTOR_ID,
        expectedRevision: Long = 1,
    ) = HumanFollowUpReleaseCommand(
        TENANT_ID.value,
        WORK_ITEM_ID.value,
        CLAIM_ID.value,
        actorId.value,
        expectedRevision,
    )

    private fun storedClaim() =
        StoredHumanFollowUpClaim(
            HumanFollowUpClaim.claim(CLAIM_ID, WORK_ITEM_ID, evidence(ACTOR_ID), NOW.minusSeconds(10)),
            NOW.minusSeconds(9),
        )

    private fun storedRelease() =
        StoredHumanFollowUpRelease(
            WORK_ITEM_ID,
            CLAIM_ID,
            ACTOR_ID,
            EVIDENCE_ID,
            HumanFollowUpOwnershipRevision(2),
            NOW,
            NOW,
        )

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

    private companion object {
        val TENANT_ID = TenantId(UUID.randomUUID())
        val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val CLAIM_ID = HumanFollowUpClaimId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
        val OTHER_ACTOR_ID = HumanActorId(UUID.randomUUID())
        val EVIDENCE_ID = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val NOW = Instant.parse("2026-10-02T10:00:00Z")
    }
}
