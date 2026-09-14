package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.identity.domain.HumanActorId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class ApprovalDecisionTest {
    @Test
    fun `records an attributable decision from matching current requester evidence`() {
        val decision =
            ApprovalDecision.record(
                id = ApprovalDecisionId(UUID.randomUUID()),
                basis = basis(ApprovalAuthority.REQUESTER, ApprovalAuthority.REQUESTER, CASE_ID),
                outcome = ApprovalDecisionOutcome.APPROVED,
                decidedAt = NOW,
            )

        assertThat(decision.actorId).isEqualTo(ACTOR_ID)
        assertThat(decision.caseId).isEqualTo(CASE_ID)
        assertThat(decision.outcome).isEqualTo(ApprovalDecisionOutcome.APPROVED)
    }

    @Test
    fun `records a rejection from tenant-wide resolver evidence`() {
        val decision =
            ApprovalDecision.record(
                id = ApprovalDecisionId(UUID.randomUUID()),
                basis = basis(ApprovalAuthority.RESOLVER, ApprovalAuthority.RESOLVER, null),
                outcome = ApprovalDecisionOutcome.REJECTED,
                decidedAt = NOW,
            )

        assertThat(decision.authority).isEqualTo(ApprovalAuthority.RESOLVER)
        assertThat(decision.outcome).isEqualTo(ApprovalDecisionOutcome.REJECTED)
    }

    @Test
    fun `rejects expired request or evidence at the exclusive validity boundary`() {
        assertThatThrownBy {
            ApprovalDecision.record(
                ApprovalDecisionId(UUID.randomUUID()),
                basis(
                    ApprovalAuthority.REQUESTER,
                    ApprovalAuthority.REQUESTER,
                    CASE_ID,
                    requestExpiresAt = NOW,
                ),
                ApprovalDecisionOutcome.APPROVED,
                NOW,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("approval request is expired")

        assertThatThrownBy {
            ApprovalDecision.record(
                ApprovalDecisionId(UUID.randomUUID()),
                basis(
                    ApprovalAuthority.REQUESTER,
                    ApprovalAuthority.REQUESTER,
                    CASE_ID,
                    evidenceExpiresAt = NOW,
                ),
                ApprovalDecisionOutcome.APPROVED,
                NOW,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("approval authority evidence is expired")
    }

    @Test
    fun `rejects wrong authority and requester case scope`() {
        assertThatThrownBy {
            ApprovalDecision.record(
                ApprovalDecisionId(UUID.randomUUID()),
                basis(ApprovalAuthority.REQUESTER, ApprovalAuthority.RESOLVER, null),
                ApprovalDecisionOutcome.REJECTED,
                NOW,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("approval authority evidence does not satisfy the request")

        assertThatThrownBy {
            ApprovalDecision.record(
                ApprovalDecisionId(UUID.randomUUID()),
                basis(ApprovalAuthority.REQUESTER, ApprovalAuthority.REQUESTER, CaseId(UUID.randomUUID())),
                ApprovalDecisionOutcome.REJECTED,
                NOW,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("approval authority evidence does not match the required scope")
    }

    private fun request(
        authority: ApprovalAuthority,
        expiresAt: Instant = NOW.plus(5, ChronoUnit.MINUTES),
    ) = ApprovalRequest(
        id = ApprovalRequestId(UUID.randomUUID()),
        runId = ResolutionRunId(UUID.randomUUID()),
        stepId = ResolutionStepId.of("unlock-account"),
        authority = authority,
        requestedAt = NOW.minus(5, ChronoUnit.MINUTES),
        expiresAt = expiresAt,
    )

    private fun basis(
        requestedAuthority: ApprovalAuthority,
        evidenceAuthority: ApprovalAuthority,
        evidenceCaseId: CaseId?,
        requestExpiresAt: Instant = NOW.plus(5, ChronoUnit.MINUTES),
        evidenceExpiresAt: Instant = NOW.plus(5, ChronoUnit.MINUTES),
    ) = ApprovalDecisionBasis(
        request = request(requestedAuthority, requestExpiresAt),
        caseId = CASE_ID,
        evidence = evidence(evidenceAuthority, evidenceCaseId, evidenceExpiresAt),
    )

    private fun evidence(
        authority: ApprovalAuthority,
        caseId: CaseId?,
        expiresAt: Instant = NOW.plus(5, ChronoUnit.MINUTES),
    ) = ApprovalAuthorityEvidence(
        id = ApprovalAuthorityEvidenceId(UUID.randomUUID()),
        actorId = ACTOR_ID,
        authority = authority,
        caseId = caseId,
        source = ApprovalAuthorityEvidenceSource.create("workforce-sso", "claims/authority-42"),
        attestedAt = NOW.minus(5, ChronoUnit.MINUTES),
        expiresAt = expiresAt,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-14T10:00:00Z")
        val CASE_ID = CaseId(UUID.randomUUID())
        val ACTOR_ID = HumanActorId(UUID.randomUUID())
    }
}
