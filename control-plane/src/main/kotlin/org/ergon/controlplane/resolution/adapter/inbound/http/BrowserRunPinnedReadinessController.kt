package org.ergon.controlplane.resolution.adapter.inbound.http

import com.fasterxml.jackson.annotation.JsonInclude
import org.ergon.contracts.domain.ContractFactValue
import org.ergon.contracts.domain.FactCondition
import org.ergon.controlplane.identity.adapter.inbound.security.AuthenticatedHumanActorResolver
import org.ergon.controlplane.resolution.application.RunPinnedReadiness
import org.ergon.controlplane.resolution.application.RunPinnedReadinessService
import org.ergon.resolution.domain.DocumentaryRequirementAssessment
import org.ergon.resolution.domain.ResolutionReadiness
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Current-supervisor inspection of fixed inputs, not current-case planning,
 * historical reasoning or command authority.
 */
@RestController
@RequestMapping("/bff/v1/tenants/{tenantId}/resolution-runs/{runId}/pinned-readiness")
@ConditionalOnProperty(prefix = "ergon.security.browser-session", name = ["enabled"], havingValue = "true")
class BrowserRunPinnedReadinessController(
    private val actors: AuthenticatedHumanActorResolver,
    private val readiness: RunPinnedReadinessService,
) {
    @GetMapping
    fun get(
        @PathVariable tenantId: UUID,
        @PathVariable runId: UUID,
        @AuthenticationPrincipal principal: OidcUser,
    ): ResponseEntity<BrowserRunPinnedReadiness> {
        val actor =
            actors
                .resolve(tenantId, principal)
                .actor.id.value
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            readiness.get(tenantId, runId, actor).toBrowserReadiness(),
        )
    }
}

/**
 * Fixed case/contract inputs and explicit evaluator identity.
 * READY is not historical selection, execution eligibility or proof.
 */
data class BrowserRunPinnedReadiness(
    val runId: UUID,
    val caseId: UUID,
    val caseStreamVersion: Long,
    val supervisionRevision: Long,
    val observedAt: Instant,
    val contractKey: String,
    val contractRevision: Int,
    val evaluator: String,
    val status: String,
    val missingEvidence: List<String>,
    val applicability: BrowserReadinessApplicability?,
    val selectedEvidence: List<BrowserPinnedEvidenceFact>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val documentaryEvidence: List<BrowserDocumentaryReadiness>? = null,
)

/** Available only after all requirements exist; values preserve exact case-sensitive equality semantics. */
data class BrowserReadinessApplicability(
    val fact: String,
    val expectedValue: String,
    val actualValue: String,
)

private fun RunPinnedReadiness.toBrowserReadiness(): BrowserRunPinnedReadiness {
    val condition =
        when (val result = readiness) {
            is ResolutionReadiness.DocumentaryEvidenceUnavailable -> {
                null
            }

            is ResolutionReadiness.MissingEvidence -> {
                null
            }

            is ResolutionReadiness.Ready -> {
                result.condition.toBrowserApplicability(result.actualValue)
            }

            is ResolutionReadiness.NotApplicable -> {
                result.condition.toBrowserApplicability(result.actualValue)
            }
        }
    return BrowserRunPinnedReadiness(
        pin.runId.value,
        pin.caseId.value,
        pin.caseStreamVersion,
        pin.supervisionRevision.value,
        pin.observedAt,
        pin.revision.contract.key.value,
        pin.revision.contract.revision.value,
        if (pin.revision.contract.documentaryEvidence
                .isEmpty()
        ) {
            "ergon.dev/evaluation/pinned-readiness/v1"
        } else {
            "ergon.dev/evaluation/pinned-readiness/v2"
        },
        when (readiness) {
            is ResolutionReadiness.DocumentaryEvidenceUnavailable -> {
                if (readiness.missing) {
                    "WAITING_FOR_DOCUMENTARY_EVIDENCE"
                } else {
                    "UNSUITABLE_DOCUMENTARY_EVIDENCE"
                }
            }

            is ResolutionReadiness.Ready -> {
                "READY"
            }

            is ResolutionReadiness.NotApplicable -> {
                "NOT_APPLICABLE"
            }

            is ResolutionReadiness.MissingEvidence -> {
                "WAITING_FOR_EVIDENCE"
            }
        },
        (readiness as? ResolutionReadiness.MissingEvidence)?.facts?.map { it.value } ?: emptyList(),
        condition,
        selectedEvidence.map { it.toBrowserFact() },
        documentaryEvidence
            .takeIf {
                pin.revision.contract.documentaryEvidence
                    .isNotEmpty()
            }?.map { it.toBrowserDocumentary() },
    )
}

private fun FactCondition.toBrowserApplicability(actual: ContractFactValue): BrowserReadinessApplicability =
    BrowserReadinessApplicability(
        fact = fact.value,
        expectedValue = expectedValue.value,
        actualValue = actual.value,
    )

/** BFF-owned historical documentary declaration, separate from operational selected evidence and authorization. */
data class BrowserDocumentaryReadiness(
    val bundleId: UUID,
    val sha256: String,
    val status: String,
    val reason: String?,
    val pinStreamVersion: Long?,
    val attachedAt: Instant?,
)

private fun DocumentaryRequirementAssessment.toBrowserDocumentary() =
    BrowserDocumentaryReadiness(
        requirement.bundleId,
        requirement.sha256,
        status.name,
        reason?.name,
        pin?.streamVersion,
        pin?.attachedAt,
    )
