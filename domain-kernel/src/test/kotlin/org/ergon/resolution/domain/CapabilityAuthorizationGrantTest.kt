package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.ergon.cases.domain.CaseId
import org.ergon.contracts.domain.ApprovalRequirement
import org.ergon.contracts.domain.CapabilityName
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.contracts.domain.ResolutionContractKey
import org.ergon.contracts.domain.ResolutionContractRevision
import org.ergon.contracts.domain.ResolutionStepId
import org.ergon.contracts.domain.StepRisk
import org.ergon.identity.domain.HumanActorId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class CapabilityAuthorizationGrantTest {
    @Test
    fun `derives exact run scope and inherits approval request expiry`() {
        val grant = CapabilityAuthorizationGrant.derive(GRANT_ID, basis(), NOW)

        assertThat(grant.approvalDecisionId).isEqualTo(DECISION_ID)
        assertThat(grant.runId).isEqualTo(RUN.id)
        assertThat(grant.caseId).isEqualTo(RUN.caseId)
        assertThat(grant.policyRevision).isEqualTo(RUN.policyRevision)
        assertThat(grant.stepId).isEqualTo(RUN.stepId)
        assertThat(grant.capability).isEqualTo(RUN.capability)
        assertThat(grant.expiresAt).isEqualTo(REQUEST.expiresAt)
    }

    @Test
    fun `rejects a rejection and the exclusive request expiry boundary`() {
        assertThatThrownBy {
            CapabilityAuthorizationGrant.derive(
                GRANT_ID,
                basis(decision = decision(ApprovalDecisionOutcome.REJECTED)),
                NOW,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("authorization grant requires an approved decision")

        assertThatThrownBy {
            CapabilityAuthorizationGrant.derive(GRANT_ID, basis(), REQUEST.expiresAt)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("authorization grant approval request is expired")
    }

    @Test
    fun `rejects mismatched run request decision and authority relationships`() {
        val otherRun = RUN.copy(id = ResolutionRunId(UUID.randomUUID()))
        assertInvalid(basis(run = otherRun), "authorization grant sources do not identify the same run")

        val otherRequest = REQUEST.copy(stepId = ResolutionStepId.of("reset-password"))
        assertInvalid(
            basis(request = otherRequest),
            "authorization grant request does not identify the run step",
        )

        val otherDecision = decision().copyForTest(requestId = ApprovalRequestId(UUID.randomUUID()))
        assertInvalid(
            basis(decision = otherDecision),
            "authorization grant decision does not answer the request",
        )
    }

    private fun assertInvalid(
        basis: CapabilityAuthorizationGrantBasis,
        message: String,
    ) {
        assertThatThrownBy { CapabilityAuthorizationGrant.derive(GRANT_ID, basis, NOW) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(message)
    }

    private fun basis(
        run: ResolutionRunStart = RUN,
        request: ApprovalRequest = REQUEST,
        decision: ApprovalDecision = decision(),
    ) = CapabilityAuthorizationGrantBasis(run, request, decision)

    private fun decision(outcome: ApprovalDecisionOutcome = ApprovalDecisionOutcome.APPROVED): ApprovalDecision =
        ApprovalDecision.rehydrate(
            ApprovalDecisionSnapshot(
                id = DECISION_ID,
                requestId = REQUEST.id,
                runId = RUN.id,
                actorId = HumanActorId(UUID.randomUUID()),
                authorityEvidenceId = ApprovalAuthorityEvidenceId(UUID.randomUUID()),
                authority = ApprovalAuthority.REQUESTER,
                caseId = RUN.caseId,
                outcome = outcome,
                decidedAt = NOW.minus(1, ChronoUnit.MINUTES),
            ),
        )

    private fun ApprovalDecision.copyForTest(requestId: ApprovalRequestId): ApprovalDecision =
        ApprovalDecision.rehydrate(
            ApprovalDecisionSnapshot(
                id = id,
                requestId = requestId,
                runId = runId,
                actorId = actorId,
                authorityEvidenceId = authorityEvidenceId,
                authority = authority,
                caseId = caseId,
                outcome = outcome,
                decidedAt = decidedAt,
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-14T10:00:00Z")
        val GRANT_ID = CapabilityAuthorizationGrantId(UUID.randomUUID())
        val DECISION_ID = ApprovalDecisionId(UUID.randomUUID())
        val RUN =
            ResolutionRunStart(
                id = ResolutionRunId(UUID.randomUUID()),
                caseId = CaseId(UUID.randomUUID()),
                caseStreamVersion = 4,
                contract =
                    ResolutionContractIdentity(
                        ResolutionContractKey.of("restore-workspace-access"),
                        ResolutionContractRevision.of(1),
                    ),
                policyRevision = ResolutionPolicyRevision.of("ergon.dev/policy/access-restoration/v1"),
                stepId = ResolutionStepId.of("unlock-account"),
                capability = CapabilityName.of("identity.account.unlock"),
                effectiveRisk = StepRisk.HIGH,
                requiredApproval = ApprovalRequirement.REQUESTER,
                initialState = ResolutionRunInitialState.WAITING_FOR_APPROVAL,
            )
        val REQUEST =
            ApprovalRequest(
                id = ApprovalRequestId(UUID.randomUUID()),
                runId = RUN.id,
                stepId = RUN.stepId,
                authority = ApprovalAuthority.REQUESTER,
                requestedAt = NOW.minus(5, ChronoUnit.MINUTES),
                expiresAt = NOW.plus(10, ChronoUnit.MINUTES),
            )
    }
}
