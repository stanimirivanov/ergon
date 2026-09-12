package org.ergon.controlplane.cases.adapter.inbound.http

import org.ergon.contracts.domain.FactCondition
import org.ergon.contracts.domain.ResolutionContractIdentity
import org.ergon.controlplane.resolution.application.CaseReadiness
import org.ergon.controlplane.resolution.application.ResolutionReadinessService
import org.ergon.resolution.domain.ResolutionReadiness
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Internal query adapter for deterministic resolution readiness decisions. */
@RestController
@RequestMapping("/internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-readiness")
class CaseResolutionReadinessController(
    private val service: ResolutionReadinessService,
) {
    @GetMapping
    fun evaluate(
        @PathVariable tenantId: UUID,
        @PathVariable caseId: UUID,
    ): CaseResolutionReadinessResponse = service.evaluate(tenantId, caseId).toResponse()
}

/**
 * HTTP snapshot of whether one pinned contract may proceed to later planning.
 *
 * [contract] is absent only for `WAITING_FOR_CONTRACT`. [missingEvidence] is
 * populated only for `WAITING_FOR_EVIDENCE`, while [applicability] is present
 * only after every required fact exists.
 */
data class CaseResolutionReadinessResponse(
    val caseId: UUID,
    val caseStreamVersion: Long,
    val status: ResolutionReadinessStatus,
    val contract: ResolutionContractIdentityResponse?,
    val missingEvidence: List<String>,
    val applicability: ResolutionApplicabilityResponse?,
)

/** Closed set of readiness outcomes exposed by the internal HTTP contract. */
enum class ResolutionReadinessStatus {
    WAITING_FOR_CONTRACT,
    WAITING_FOR_EVIDENCE,
    NOT_APPLICABLE,
    READY,
}

/** Exact immutable contract identity used for a readiness decision. */
data class ResolutionContractIdentityResponse(
    val key: String,
    val revision: Int,
)

/** Exact equality evaluated after all required evidence became available. */
data class ResolutionApplicabilityResponse(
    val fact: String,
    val expectedValue: String,
    val actualValue: String,
)

private fun CaseReadiness.toResponse(): CaseResolutionReadinessResponse =
    when (this) {
        is CaseReadiness.WaitingForContract -> {
            CaseResolutionReadinessResponse(
                caseId = caseId,
                caseStreamVersion = caseStreamVersion,
                status = ResolutionReadinessStatus.WAITING_FOR_CONTRACT,
                contract = null,
                missingEvidence = emptyList(),
                applicability = null,
            )
        }

        is CaseReadiness.Evaluated -> {
            readiness.toResponse(this)
        }
    }

private fun ResolutionReadiness.toResponse(snapshot: CaseReadiness.Evaluated): CaseResolutionReadinessResponse =
    when (this) {
        is ResolutionReadiness.MissingEvidence -> {
            snapshot.response(
                status = ResolutionReadinessStatus.WAITING_FOR_EVIDENCE,
                missingEvidence = facts.map { it.value },
            )
        }

        is ResolutionReadiness.NotApplicable -> {
            snapshot.response(
                status = ResolutionReadinessStatus.NOT_APPLICABLE,
                applicability = condition.toResponse(actualValue.value),
            )
        }

        is ResolutionReadiness.Ready -> {
            snapshot.response(
                status = ResolutionReadinessStatus.READY,
                applicability = condition.toResponse(actualValue.value),
            )
        }
    }

private fun CaseReadiness.Evaluated.response(
    status: ResolutionReadinessStatus,
    missingEvidence: List<String> = emptyList(),
    applicability: ResolutionApplicabilityResponse? = null,
) = CaseResolutionReadinessResponse(
    caseId = caseId,
    caseStreamVersion = caseStreamVersion,
    status = status,
    contract = contract.toResponse(),
    missingEvidence = missingEvidence,
    applicability = applicability,
)

private fun ResolutionContractIdentity.toResponse() = ResolutionContractIdentityResponse(key.value, revision.value)

private fun FactCondition.toResponse(actualValue: String): ResolutionApplicabilityResponse =
    ResolutionApplicabilityResponse(fact.value, expectedValue.value, actualValue)
