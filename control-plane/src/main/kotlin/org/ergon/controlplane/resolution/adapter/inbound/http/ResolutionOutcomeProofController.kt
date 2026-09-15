package org.ergon.controlplane.resolution.adapter.inbound.http

import org.ergon.controlplane.resolution.application.ResolutionOutcomeProofAssessmentService
import org.ergon.resolution.domain.ResolutionOutcomeEvidence
import org.ergon.resolution.domain.ResolutionOutcomeProofAssessment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Internal read adapter for assessing a run's pinned outcome-proof condition. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/resolution-runs/{runId}/outcome-proof")
class ResolutionOutcomeProofController(
    private val assessments: ResolutionOutcomeProofAssessmentService,
) {
    /** Returns accepted or pending proof without changing run or case state. */
    @GetMapping
    fun assess(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
    ): ResolutionOutcomeProofResponse = assessments.assess(tenantId, runId).toResponse()
}

/** Pinned proof condition and the latest eligible evidence at one case version. */
data class ResolutionOutcomeProofResponse(
    val status: String,
    val pendingReason: String?,
    val caseStreamVersion: Long,
    val fact: String,
    val expectedValue: String,
    val evidence: ResolutionOutcomeEvidenceResponse?,
)

/** Source-bound fact considered by the outcome-proof evaluator. */
data class ResolutionOutcomeEvidenceResponse(
    val factId: UUID,
    val observationId: UUID,
    val observationStreamVersion: Long,
    val factStreamVersion: Long,
    val actualValue: String,
    val observedAt: Instant,
    val boundAt: Instant,
)

private fun ResolutionOutcomeProofAssessment.toResponse(): ResolutionOutcomeProofResponse =
    when (this) {
        is ResolutionOutcomeProofAssessment.Accepted -> {
            ResolutionOutcomeProofResponse(
                status = "ACCEPTED",
                pendingReason = null,
                caseStreamVersion = caseStreamVersion,
                fact = condition.fact.value,
                expectedValue = condition.expectedValue.value,
                evidence = evidence.toResponse(),
            )
        }

        is ResolutionOutcomeProofAssessment.Pending -> {
            ResolutionOutcomeProofResponse(
                status = "PENDING",
                pendingReason = reason.name,
                caseStreamVersion = caseStreamVersion,
                fact = condition.fact.value,
                expectedValue = condition.expectedValue.value,
                evidence = evidence?.toResponse(),
            )
        }
    }

private fun ResolutionOutcomeEvidence.toResponse() =
    ResolutionOutcomeEvidenceResponse(
        factId = factId.value,
        observationId = observationId.value,
        observationStreamVersion = observationStreamVersion,
        factStreamVersion = factStreamVersion,
        actualValue = value.value,
        observedAt = observedAt,
        boundAt = boundAt,
    )
