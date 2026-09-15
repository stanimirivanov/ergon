package org.ergon.resolution.domain

import org.assertj.core.api.Assertions.assertThat
import org.ergon.cases.domain.FactId
import org.ergon.cases.domain.ObservationId
import org.ergon.contracts.domain.ContractFactType
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ResolutionOutcomeProofEvaluatorTest {
    @Test
    fun `accepts matching evidence observed after action completion`() {
        val assessment = evaluate(evidence(value = ACTIVE, observationVersion = 5, observedAt = ACTION_COMPLETED_AT))

        assertThat(assessment).isInstanceOf(ResolutionOutcomeProofAssessment.Accepted::class.java)
        val accepted = assessment as ResolutionOutcomeProofAssessment.Accepted
        assertThat(accepted.evidence.value).isEqualTo(ACTIVE)
        assertThat(accepted.caseStreamVersion).isEqualTo(6)
    }

    @Test
    fun `does not reuse matching evidence from the run input snapshot`() {
        val assessment = evaluate(evidence(value = ACTIVE, observationVersion = 3, observedAt = ACTION_COMPLETED_AT))

        assertThat(assessment).isEqualTo(
            ResolutionOutcomeProofAssessment.Pending(
                CONDITION,
                caseStreamVersion = 4,
                ResolutionOutcomeProofPendingReason.ELIGIBLE_EVIDENCE_MISSING,
                evidence = null,
            ),
        )
    }

    @Test
    fun `does not accept an observation whose occurrence predates the action receipt`() {
        val assessment =
            evaluate(
                evidence(
                    value = ACTIVE,
                    observationVersion = 5,
                    observedAt = ACTION_COMPLETED_AT.minusSeconds(1),
                ),
            )

        assertThat((assessment as ResolutionOutcomeProofAssessment.Pending).reason)
            .isEqualTo(ResolutionOutcomeProofPendingReason.ELIGIBLE_EVIDENCE_MISSING)
    }

    @Test
    fun `reports the latest eligible nonmatching value without accepting it`() {
        val olderMatch = evidence(value = ACTIVE, observationVersion = 5, observedAt = ACTION_COMPLETED_AT)
        val latestMismatch =
            evidence(
                value = LOCKED,
                observationVersion = 7,
                observedAt = ACTION_COMPLETED_AT.plusSeconds(1),
            )

        val assessment = evaluate(olderMatch, latestMismatch)

        val pending = assessment as ResolutionOutcomeProofAssessment.Pending
        assertThat(pending.reason).isEqualTo(ResolutionOutcomeProofPendingReason.VALUE_MISMATCH)
        assertThat(pending.evidence).isEqualTo(latestMismatch)
    }

    private fun evaluate(vararg evidence: ResolutionOutcomeEvidence): ResolutionOutcomeProofAssessment =
        ResolutionOutcomeProofEvaluator.evaluate(
            ResolutionOutcomeProofBasis(
                condition = CONDITION,
                runCaseStreamVersion = 4,
                actionCompletedAt = ACTION_COMPLETED_AT,
                caseStreamVersion = evidence.maxOfOrNull { it.factStreamVersion } ?: 4,
                evidence = evidence.toList(),
            ),
        )

    private fun evidence(
        value: ContractFactValue,
        observationVersion: Long,
        observedAt: Instant,
    ) = ResolutionOutcomeEvidence(
        factId = FactId(UUID.randomUUID()),
        fact = ACCOUNT_ACCESS_STATE,
        value = value,
        observationId = ObservationId(UUID.randomUUID()),
        observationStreamVersion = observationVersion,
        factStreamVersion = observationVersion + 1,
        observedAt = observedAt,
        boundAt = observedAt.plusMillis(1),
    )

    companion object {
        private val ACCOUNT_ACCESS_STATE = ContractFactType.of("account.access.state")
        private val ACTIVE = ContractFactValue.of("ACTIVE")
        private val LOCKED = ContractFactValue.of("LOCKED")
        private val CONDITION = FactCondition(ACCOUNT_ACCESS_STATE, ACTIVE)
        private val ACTION_COMPLETED_AT = Instant.parse("2026-09-15T10:00:00Z")
    }
}
