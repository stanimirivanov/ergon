package org.ergon.followup.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.identity.domain.HumanActorId
import org.ergon.resolution.domain.ApprovalAuthority
import org.ergon.resolution.domain.ApprovalAuthorityEvidence
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceId
import org.ergon.resolution.domain.ApprovalAuthorityEvidenceSource
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class HumanFollowUpClaimTest {
    @Test
    fun `claim retains exact resolver authority attribution`() {
        val claim = HumanFollowUpClaim.claim(CLAIM_ID, WORK_ITEM_ID, evidence(), NOW)

        assertThat(claim.resolverActorId).isEqualTo(ACTOR_ID)
        assertThat(claim.authorityEvidenceId).isEqualTo(EVIDENCE_ID)
        assertThat(claim.claimedAt).isEqualTo(NOW)
    }

    @Test
    fun `claim rejects requester and expired authority`() {
        assertThatThrownBy {
            HumanFollowUpClaim.claim(
                CLAIM_ID,
                WORK_ITEM_ID,
                evidence(ApprovalAuthority.REQUESTER, CaseId(UUID.randomUUID())),
                NOW,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            HumanFollowUpClaim.claim(CLAIM_ID, WORK_ITEM_ID, evidence(expiresAt = NOW), NOW)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun evidence(
        authority: ApprovalAuthority = ApprovalAuthority.RESOLVER,
        caseId: CaseId? = null,
        expiresAt: Instant = NOW.plusSeconds(60),
    ) = ApprovalAuthorityEvidence(
        EVIDENCE_ID,
        ACTOR_ID,
        authority,
        caseId,
        ApprovalAuthorityEvidenceSource.create("workforce-sso", "groups/resolvers"),
        NOW.minusSeconds(60),
        expiresAt,
    )

    private companion object {
        val CLAIM_ID = HumanFollowUpClaimId(UUID.randomUUID())
        val WORK_ITEM_ID = HumanFollowUpWorkItemId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
        val EVIDENCE_ID = ApprovalAuthorityEvidenceId(UUID.randomUUID())
        val NOW = Instant.parse("2026-09-18T12:00:00Z")
    }
}
